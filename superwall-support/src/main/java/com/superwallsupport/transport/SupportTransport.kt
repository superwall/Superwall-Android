package com.superwallsupport.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** What the transport tells its owner (the chat UI, M2). Delivered on the transport's thread. */
sealed class SupportTransportEvent {
    data class ConnectionChanged(
        val state: SupportConnectionState,
    ) : SupportTransportEvent()

    /** Entries, the tail, status or presence changed. Read [SupportTransport.log]. */
    data object LogChanged : SupportTransportEvent()

    /** A pending send was added, retried, confirmed or failed. */
    data object PendingChanged : SupportTransportEvent()

    data class Error(
        val code: String,
        val retryable: Boolean,
    ) : SupportTransportEvent()
}

sealed class SupportConnectionState {
    /** Not started, or backgrounded: no socket (background delivery is push). */
    data object Idle : SupportConnectionState()

    data object Connecting : SupportConnectionState()

    /** `ready` received, replaying the backlog. */
    data object CatchingUp : SupportConnectionState()

    data object Live : SupportConnectionState()

    data class WaitingToRetry(
        val millis: Long,
    ) : SupportConnectionState()

    data object Offline : SupportConnectionState()

    /** Won't reconnect: the conversation is gone, or this build isn't served. */
    data class Stopped(
        val reason: String,
    ) : SupportConnectionState()
}

/**
 * The Support transport for one conversation: session start, the live socket,
 * resume from seq, HTTP windows, the send queue and attachment upload.
 *
 * Rules from the M0 transport spike: hold the socket only while foregrounded;
 * on foreground or network-available reconnect at once with backoff reset; ping
 * every 10 s with a 5 s deadline, during catch-up too; replay at most about 60
 * entries over the socket and read anything older over HTTP windows.
 *
 * All state lives on one confined dispatcher, so it is never touched concurrently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportTransport internal constructor(
    val configuration: Configuration,
    private val http: SupportHttpClient,
    private val socketFactory: SupportSocketFactory,
    private val logger: (String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    data class Configuration(
        /** The inbox API origin, e.g. `https://inbox-api.superwall.com`. */
        val baseUrl: String,
        val apiKey: String,
        val sdk: SupportSdk,
        val locale: String,
        val storefront: String? = null,
        val capabilities: SupportCapabilities = SupportCapabilities.TEXT_ONLY,
        val proofs: List<SupportProof>,
        val pingIntervalMillis: Long = 10_000,
        val pingDeadlineMillis: Long = 5_000,
        /** Behind by more than this at `ready`: catch up over HTTP windows instead. */
        val socketReplayCap: Int = 60,
        val windowSize: Int = SupportWire.DEFAULT_WINDOW,
        val backoffInitialMillis: Long = 500,
        val backoffMaxMillis: Long = 8_000,
        val sendBackoffMaxMillis: Long = 30_000,
        /** Where the send queue is saved; null keeps it in memory only. */
        val sendQueueFile: File? = null,
        /** Testing only: return true to cut the socket right after accepting this seq. */
        val dropAfterAcceptingForTesting: ((Long) -> Boolean)? = null,
    ) {
        override fun toString() = "Configuration(baseUrl=$baseUrl, sdk=$sdk)"
    }

    /** Counters for tests and diagnostics. Never message content. */
    data class Stats(
        val connects: Int = 0,
        val drops: Int = 0,
        val gaps: Int = 0,
        val duplicates: Int = 0,
        val pingTimeouts: Int = 0,
        val httpCatchUps: Int = 0,
        val resyncs: Int = 0,
        val ignoredFrames: Int = 0,
    )

    /** OkHttp stays an implementation detail: it isn't part of the add-on's public API. */
    constructor(
        configuration: Configuration,
        logger: (String) -> Unit = {},
    ) : this(configuration, OkHttpClient(), logger)

    internal constructor(
        configuration: Configuration,
        client: OkHttpClient,
        logger: (String) -> Unit = {},
    ) : this(
        configuration,
        SupportHttpClient(configuration.baseUrl, client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()),
        // The socket's liveness is our own ping, so OkHttp's timeouts are off for it.
        client
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
            .let { socketClient -> SupportSocketFactory { url -> OkHttpSupportSocket(socketClient, url) } },
        logger,
    )

    private val confined = dispatcher
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val queue = SendQueueStore(configuration.sendQueueFile)

    var session: SessionStarted? = null
        private set
    val log = ConversationLog()
    var state: SupportConnectionState = SupportConnectionState.Idle
        private set(value) {
            if (field != value) {
                field = value
                emit(SupportTransportEvent.ConnectionChanged(value))
            }
        }
    var stats = Stats()
        private set

    @Volatile
    private var eventHandler: ((SupportTransportEvent) -> Unit)? = null
    private var foreground = true
    private var networkAvailable = true
    private var started = false
    private var starting = false
    private var stopped = false

    private var socket: SupportSocket? = null
    private var generation = 0
    private var readyHead: Long? = null
    private var lastFrameAt = 0L
    private var backoff = configuration.backoffInitialMillis
    private var retryJob: Job? = null
    private var pingJob: Job? = null
    private var consecutiveGaps = 0
    private var sendJob: Job? = null
    private var sendBackoff = configuration.backoffInitialMillis

    fun setEventHandler(handler: ((SupportTransportEvent) -> Unit)?) {
        eventHandler = handler
    }

    /** Runs [block] on the transport's thread, for reading [log], [state] or [stats] safely. */
    suspend fun <T> read(block: SupportTransport.() -> T): T = withContext(confined) { block() }

    val pendingMessages: List<PendingMessage>
        get() {
            val conversationId = session?.conversationId ?: return emptyList()
            return queue.all.filter { it.conversationId == conversationId }
        }

    // region Lifecycle

    /**
     * Starts a session, loads the newest window and connects. Throws only when the
     * session can't start (call again to retry). A window that fails to load doesn't
     * stop it: the socket's catch-up reads it over HTTP instead.
     */
    suspend fun start(conversationId: String? = null) =
        withContext(confined) {
            if (started || starting) return@withContext
            starting = true
            try {
                startSession(conversationId)
            } catch (error: SupportTransportException) {
                starting = false
                throw error
            }
            started = true
            starting = false
            try {
                reloadNewestWindow()
            } catch (error: SupportTransportException) {
                logger("first window failed (${error.message}); the socket catches up")
            }
            connect()
            flushSends()
        }

    /** Closes the socket and stops reconnecting. Pending sends stay queued. */
    suspend fun stop() =
        withContext(confined) {
            stopped = true
            closeSocket("stopped")
            retryJob?.cancel()
            sendJob?.cancel()
            state = SupportConnectionState.Stopped("stopped")
            scope.cancel()
        }

    /** The app moved to the foreground or the background. */
    fun setForeground(isForeground: Boolean) =
        post {
            if (isForeground == foreground) {
                if (isForeground) probeOrConnect()
                return@post
            }
            foreground = isForeground
            if (isForeground) {
                probeOrConnect()
                flushSends(resetBackoff = true)
            } else {
                // Held only while foregrounded: Android cuts the network ~5 s into background anyway.
                retryJob?.cancel()
                closeSocket("background")
                if (state !is SupportConnectionState.Stopped) state = SupportConnectionState.Idle
            }
        }

    /** The OS reported the default network came or went. */
    fun setNetworkAvailable(available: Boolean) =
        post {
            if (available == networkAvailable) return@post
            networkAvailable = available
            if (available) {
                backoff = configuration.backoffInitialMillis
                if (socket == null) connect()
                flushSends(resetBackoff = true)
            } else {
                retryJob?.cancel()
                closeSocket("offline")
                if (state !is SupportConnectionState.Stopped) state = SupportConnectionState.Offline
            }
        }

    private fun probeOrConnect() {
        backoff = configuration.backoffInitialMillis
        val current = socket
        // A socket that survived may be dead without knowing it: ping now and check the deadline.
        if (current != null) sendPing(current, generation) else connect()
    }

    private fun post(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    // endregion

    // region Session and windows

    private suspend fun startSession(conversationId: String?) {
        val request =
            SessionStart(
                apiKey = configuration.apiKey,
                sdk = configuration.sdk,
                locale = configuration.locale,
                storefront = configuration.storefront,
                capabilities = configuration.capabilities,
                proofs = configuration.proofs,
                conversationId = conversationId ?: session?.conversationId,
            )
        val started = http.startSession(request)
        started.redirect?.let {
            stopped = true
            session = started
            state = SupportConnectionState.Stopped("redirect")
            throw SupportTransportException.Redirected(it.url)
        }
        if (session?.conversationId != started.conversationId) log.reset(started.conversationId)
        session = started
        logger("session started, conversation ${started.conversationId}")
    }

    /** Runs an authenticated command, starting a new session once if the token is refused. */
    private suspend fun <T> authenticated(call: suspend (token: String, conversationId: String) -> T): T {
        val current = session ?: throw SupportTransportException.NotStarted()
        return try {
            call(current.sessionToken, current.conversationId)
        } catch (error: SupportTransportException.Wire) {
            if (error.body.tag != "SessionInvalid") throw error
            startSession(current.conversationId)
            val renewed = session ?: throw SupportTransportException.NotStarted()
            call(renewed.sessionToken, renewed.conversationId)
        }
    }

    private suspend fun reloadNewestWindow() {
        val window = authenticated { token, id -> http.entries(token, id, limit = configuration.windowSize) }
        log.replace(window)
        emit(SupportTransportEvent.LogChanged)
    }

    private suspend fun catchUpOverHttp() {
        stats = stats.copy(httpCatchUps = stats.httpCatchUps + 1)
        while (true) {
            val cursor = log.cursor
            val window =
                authenticated { token, id -> http.entries(token, id, after = cursor, limit = configuration.windowSize) }
            log.applyCatchUp(window)
            emit(SupportTransportEvent.LogChanged)
            if (!window.hasMore || log.cursor == cursor) break
        }
    }

    /** Scrolls back: the window before the oldest entry held. */
    suspend fun loadOlder() =
        withContext(confined) {
            val oldest = log.oldestSeq ?: return@withContext
            if (!log.hasOlder) return@withContext
            val window =
                authenticated { token, id -> http.entries(token, id, before = oldest, limit = configuration.windowSize) }
            log.applyOlder(window)
            emit(SupportTransportEvent.LogChanged)
        }

    // endregion

    // region Socket

    private fun liveUrl(conversationId: String): String {
        val base = configuration.baseUrl.trimEnd('/')
        val ws =
            when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
        return "$ws/v1/conversations/$conversationId/live"
    }

    private fun connect() {
        val current = session
        if (!started || stopped || !foreground || !networkAvailable || socket != null || current == null) {
            if (!networkAvailable && !stopped) state = SupportConnectionState.Offline
            return
        }
        retryJob?.cancel()
        generation += 1
        val generation = generation
        val socket = socketFactory.make(liveUrl(current.conversationId))
        this.socket = socket
        readyHead = null
        lastFrameAt = System.currentTimeMillis()
        stats = stats.copy(connects = stats.connects + 1)
        state = SupportConnectionState.Connecting
        val hello = ClientFrame.Hello(current.sessionToken, current.conversationId, log.cursor, log.cursorId).encoded()
        scope.launch { run(socket, generation, hello) }
        startPings(socket, generation)
    }

    private suspend fun run(
        socket: SupportSocket,
        generation: Int,
        hello: String,
    ) {
        try {
            if (!socket.send(hello)) throw SupportSocket.Closed(socket.closeCode, "hello not sent")
            while (true) {
                val text = socket.receive()
                if (generation != this.generation) return
                handle(text, socket, generation)
                if (generation != this.generation) return
            }
        } catch (error: SupportSocket.Closed) {
            if (generation == this.generation) dropped(socket.closeCode, error.message ?: "closed")
        }
    }

    private suspend fun handle(
        text: String,
        socket: SupportSocket,
        generation: Int,
    ) {
        lastFrameAt = System.currentTimeMillis()
        when (val decoded = WireFrameDecoder.decode(text)) {
            is DecodedFrame.Ignored -> {
                stats = stats.copy(ignoredFrames = stats.ignoredFrames + 1)
                logger("ignored frame: ${decoded.reason}")
            }
            is DecodedFrame.Frame -> handle(decoded.frame, socket)
        }
    }

    private suspend fun handle(
        frame: ServerFrame,
        socket: SupportSocket,
    ) {
        when (frame) {
            is ServerFrame.Ready -> {
                log.ready()
                readyHead = frame.head
                emit(SupportTransportEvent.LogChanged)
                if (frame.head - log.cursor > configuration.socketReplayCap) {
                    // Too far behind for the socket: read windows over HTTP, then reconnect from there.
                    // Holding nothing (the first window failed): load the newest window, not all history.
                    val holdsNothing = log.cursor == 0L
                    replaceSocket("catch up over HTTP") { if (holdsNothing) reloadNewestWindow() else catchUpOverHttp() }
                    return
                }
                state = if (log.cursor >= frame.head) SupportConnectionState.Live else SupportConnectionState.CatchingUp
            }
            is ServerFrame.Entry ->
                when (val outcome = log.applyEntryFrame(frame.seq, frame.prev, frame.entry)) {
                    EntryFrameOutcome.Accept -> {
                        consecutiveGaps = 0
                        // Progress: the path works, so a drop after this retries from the first step.
                        backoff = configuration.backoffInitialMillis
                        log.entry(frame.seq)?.clientMessageId?.let { id ->
                            if (queue.remove(id)) emit(SupportTransportEvent.PendingChanged)
                        }
                        emit(SupportTransportEvent.LogChanged)
                        val head = readyHead
                        if (state == SupportConnectionState.CatchingUp && head != null && log.cursor >= head) {
                            state = SupportConnectionState.Live
                        }
                        if (configuration.dropAfterAcceptingForTesting?.invoke(frame.seq) == true) {
                            socket.close(SupportCloseCode.GOING_AWAY, "test drop")
                            dropped(null, "test drop after seq ${frame.seq}")
                        }
                    }
                    EntryFrameOutcome.Duplicate -> stats = stats.copy(duplicates = stats.duplicates + 1)
                    is EntryFrameOutcome.Gap -> {
                        stats = stats.copy(gaps = stats.gaps + 1)
                        consecutiveGaps += 1
                        logger("gap: holding ${outcome.expected}, frame prev ${outcome.prev}; reconnecting")
                        replaceSocket("gap", useBackoff = consecutiveGaps > 3) {}
                    }
                }
            is ServerFrame.Tail -> {
                log.applyTail(frame.messageId, frame.offset, frame.text)
                emit(SupportTransportEvent.LogChanged)
            }
            is ServerFrame.TailCancel -> {
                log.cancelTail(frame.messageId)
                emit(SupportTransportEvent.LogChanged)
            }
            is ServerFrame.Status -> {
                log.setStatus(frame.state, frame.etaSeconds)
                emit(SupportTransportEvent.LogChanged)
            }
            is ServerFrame.Presence -> {
                log.setPresence(frame.participants)
                emit(SupportTransportEvent.LogChanged)
            }
            is ServerFrame.Resync -> {
                stats = stats.copy(resyncs = stats.resyncs + 1)
                logger("resync: ${frame.reason}")
                replaceSocket("resync") { reloadNewestWindow() }
            }
            // The close that follows says what to do (session invalid, not found …).
            is ServerFrame.Error -> emit(SupportTransportEvent.Error(frame.code, frame.retryable))
            ServerFrame.Pong -> Unit
        }
    }

    /** Drops this socket, runs [work] (an HTTP read), then reconnects. */
    private suspend fun replaceSocket(
        reason: String,
        useBackoff: Boolean = false,
        work: suspend () -> Unit,
    ) {
        closeSocket(reason)
        try {
            work()
        } catch (error: SupportTransportException) {
            logger("$reason failed: ${error.message}")
            scheduleRetry()
            return
        }
        if (useBackoff) scheduleRetry() else connect()
    }

    private fun closeSocket(reason: String) {
        generation += 1
        pingJob?.cancel()
        pingJob = null
        socket?.close(SupportCloseCode.NORMAL, reason)
        socket = null
        readyHead = null
    }

    private fun dropped(
        closeCode: Int?,
        reason: String,
    ) {
        stats = stats.copy(drops = stats.drops + 1)
        logger("socket dropped: code ${closeCode ?: "none"}, $reason")
        closeSocket("dropped")
        when (closeCode) {
            SupportCloseCode.SESSION_INVALID ->
                scope.launch {
                    try {
                        startSession(session?.conversationId)
                        connect()
                    } catch (_: SupportTransportException) {
                        scheduleRetry()
                    }
                }
            SupportCloseCode.PROTOCOL_UNSUPPORTED -> {
                stopped = true
                state = SupportConnectionState.Stopped("protocol_unsupported")
            }
            SupportCloseCode.NOT_FOUND -> {
                stopped = true
                state = SupportConnectionState.Stopped("not_found")
            }
            // 1001, 1011, 4029, 4003 or a network failure: back off and retry.
            else -> scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (stopped) return
        if (!foreground) {
            state = SupportConnectionState.Idle
            return
        }
        if (!networkAvailable) {
            state = SupportConnectionState.Offline
            return
        }
        retryJob?.cancel()
        // Full jitter on the current step, so many clients don't reconnect in lockstep.
        val delayMillis = Random.nextLong(backoff / 2, backoff + 1)
        backoff = (backoff * 2).coerceAtMost(configuration.backoffMaxMillis)
        state = SupportConnectionState.WaitingToRetry(delayMillis)
        retryJob =
            scope.launch {
                delay(delayMillis)
                if (socket == null) connect()
            }
    }

    // endregion

    // region Liveness

    private fun startPings(
        socket: SupportSocket,
        generation: Int,
    ) {
        pingJob?.cancel()
        val interval =
            minOf(configuration.pingIntervalMillis, (session?.heartbeatSeconds ?: 10) * 1000L)
        pingJob =
            scope.launch {
                while (isActive) {
                    delay(interval)
                    sendPing(socket, generation)
                }
            }
    }

    /** Sends a ping; drops the socket if no frame at all arrives by the deadline, during catch-up too. */
    private fun sendPing(
        socket: SupportSocket,
        generation: Int,
    ) {
        if (generation != this.generation) return
        val sentAt = System.currentTimeMillis()
        socket.send(SupportWire.PING_FRAME)
        scope.launch {
            delay(configuration.pingDeadlineMillis)
            if (generation == this@SupportTransport.generation && this@SupportTransport.socket != null && lastFrameAt < sentAt) {
                stats = stats.copy(pingTimeouts = stats.pingTimeouts + 1)
                dropped(null, "no frame within ${configuration.pingDeadlineMillis} ms of a ping")
            }
        }
    }

    // endregion

    // region Sending

    /**
     * Queues a message and sends it. Returns at once; the queue retries with the same
     * `clientMessageId` until the server confirms it.
     */
    suspend fun send(
        text: String,
        attachmentIds: List<String> = emptyList(),
    ): PendingMessage =
        withContext(confined) {
            val current = session ?: throw SupportTransportException.NotStarted()
            val clientMessageId = UUID.randomUUID().toString()
            SendMessage(clientMessageId, text, attachmentIds.takeIf { it.isNotEmpty() }?.map(::AttachmentRef)).validate()
            val pending =
                PendingMessage(clientMessageId, current.conversationId, text, attachmentIds, System.currentTimeMillis())
            queue.append(pending)
            emit(SupportTransportEvent.PendingChanged)
            flushSends()
            pending
        }

    /** Retries a failed send by hand, with the same `clientMessageId`. */
    fun retry(clientMessageId: String) =
        post {
            queue.update(clientMessageId) { it.copy(state = PendingMessage.State.PENDING, failure = null) }
            emit(SupportTransportEvent.PendingChanged)
            flushSends(resetBackoff = true)
        }

    fun discard(clientMessageId: String) =
        post {
            if (queue.remove(clientMessageId)) emit(SupportTransportEvent.PendingChanged)
        }

    private fun flushSends(resetBackoff: Boolean = false) {
        if (resetBackoff) sendBackoff = configuration.backoffInitialMillis
        if (sendJob?.isActive == true || session == null || stopped) return
        sendJob = scope.launch { drainSendQueue() }
    }

    private suspend fun drainSendQueue() {
        while (networkAvailable) {
            val conversationId = session?.conversationId ?: return
            val next = queue.next(conversationId) ?: return
            queue.update(next.clientMessageId) { it.copy(attempts = it.attempts + 1) }
            val message =
                SendMessage(
                    next.clientMessageId,
                    next.text,
                    next.attachmentIds.takeIf { it.isNotEmpty() }?.map(::AttachmentRef),
                    seenSeq = log.cursor.takeIf { it > 0 },
                )
            try {
                val accepted = authenticated { token, id -> http.sendMessage(token, id, message) }
                sendBackoff = configuration.backoffInitialMillis
                queue.remove(next.clientMessageId)
                log.holdOutOfBand(WireEntry.decode(accepted.entry))
                emit(SupportTransportEvent.PendingChanged)
                emit(SupportTransportEvent.LogChanged)
            } catch (error: SupportTransportException) {
                if (error.isRetryable) {
                    logger("send retry after ${error.message}")
                    val wait = sendBackoff
                    sendBackoff = (sendBackoff * 2).coerceAtMost(configuration.sendBackoffMaxMillis)
                    delay(wait)
                } else {
                    val tag = (error as? SupportTransportException.Wire)?.body?.tag ?: error.javaClass.simpleName
                    queue.update(next.clientMessageId) { it.copy(state = PendingMessage.State.FAILED, failure = tag) }
                    emit(SupportTransportEvent.PendingChanged)
                    emit(SupportTransportEvent.Error(tag, false))
                }
            }
        }
    }

    // endregion

    // region Attachments and other commands

    /** Asks for an upload, PUTs the bytes to the presigned URL, then completes it. */
    suspend fun uploadAttachment(
        bytes: ByteArray,
        filename: String,
        mime: String,
        kind: String = "file",
    ): UploadCompleted =
        withContext(confined) {
            if (bytes.isEmpty() || bytes.size > SupportWire.MAX_ATTACHMENT_BYTES) {
                throw SupportTransportException.AttachmentTooLarge(SupportWire.MAX_ATTACHMENT_BYTES)
            }
            val request = UploadRequest(kind, filename.take(255), mime, bytes.size.toLong(), SupportHttpClient.sha256Hex(bytes))
            withRetries {
                val intent = authenticated { token, id -> http.createUpload(token, id, request) }
                http.put(intent.upload, bytes)
                authenticated { token, _ -> http.completeUpload(token, intent.attachmentId) }
            }
        }

    suspend fun attachmentUrl(attachmentId: String): String =
        withContext(confined) { authenticated { token, _ -> http.attachmentUrl(token, attachmentId) }.url }

    suspend fun markRead(seq: Long) =
        withContext(confined) { withRetries { authenticated { token, id -> http.markRead(token, id, seq) } } }

    suspend fun cardAction(
        cardId: String,
        request: CardActionRequest,
    ) = withContext(confined) {
        val accepted = withRetries { authenticated { token, _ -> http.cardAction(token, cardId, request) } }
        accepted.entries.forEach { log.holdOutOfBand(WireEntry.decode(it)) }
        emit(SupportTransportEvent.LogChanged)
    }

    suspend fun toolCallResult(
        callId: String,
        request: ToolCallResultRequest,
    ) = withContext(confined) { withRetries { authenticated { token, _ -> http.toolCallResult(token, callId, request) } } }

    fun setTyping(active: Boolean) = post { socket?.send(ClientFrame.Typing(active).encoded()) }

    private suspend fun <T> withRetries(
        attempts: Int = 3,
        call: suspend () -> T,
    ): T {
        var wait = configuration.backoffInitialMillis
        var attempt = 1
        while (true) {
            try {
                return call()
            } catch (error: SupportTransportException) {
                if (!error.isRetryable || attempt >= attempts) throw error
                attempt += 1
                delay(wait)
                wait = (wait * 2).coerceAtMost(configuration.backoffMaxMillis)
            }
        }
    }

    // endregion

    private fun emit(event: SupportTransportEvent) {
        eventHandler?.invoke(event)
    }

    /** Drops the socket as if the network had cut it. For test apps and debugging only. */
    fun dropConnectionForTesting() =
        post {
            val current = socket ?: return@post
            current.close(SupportCloseCode.GOING_AWAY, "simulated")
            dropped(null, "simulated")
        }
}
