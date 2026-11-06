package com.superwallsupport.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Entries, the tail, status or presence changed. Read [SupportTransport.snapshot]. */
    data object LogChanged : SupportTransportEvent()

    /** A pending send was added, retried, confirmed or failed. */
    data object PendingChanged : SupportTransportEvent()

    data class Error(
        val code: String,
        val retryable: Boolean,
    ) : SupportTransportEvent()
}

/**
 * Everything a UI draws, as immutable values, published after every change. Safe to
 * read from any thread; collect it on the main thread.
 */
data class SupportSnapshot(
    val state: SupportConnectionState = SupportConnectionState.Idle,
    val conversationId: String? = null,
    val entries: List<WireEntry> = emptyList(),
    val tail: TailState? = null,
    val status: String? = null,
    val statusEtaSeconds: Int? = null,
    val presence: List<PresenceParticipant> = emptyList(),
    val hasOlder: Boolean = false,
    val cursor: Long = 0,
    val pending: List<PendingMessage> = emptyList(),
)

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
        /** Never prints the API key or proof values. */
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
        SupportHttpClient(
            configuration.baseUrl,
            client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build(),
            // Uploads are up to 25 MB on mobile links: no overall call timeout, only stalls.
            client
                .newBuilder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
        ),
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

    // Nothing unexpected (a full disk, a throwing event handler) may crash the app.
    private val scope =
        CoroutineScope(
            SupervisorJob() + confined + CoroutineExceptionHandler { _, error -> logger("transport error: ${error.javaClass.simpleName}") },
        )
    private val queue = SendQueueStore(configuration.sendQueueFile, logger)

    @Volatile
    var session: SessionStarted? = null
        private set

    /** Confined to the transport's thread: read it through [read], or use [snapshot]. */
    internal val log = ConversationLog()

    @Volatile
    var state: SupportConnectionState = SupportConnectionState.Idle
        private set(value) {
            if (field != value) {
                field = value
                emit(SupportTransportEvent.ConnectionChanged(value))
            }
        }

    @Volatile
    var stats = Stats()
        private set

    private val snapshots = MutableStateFlow(SupportSnapshot())

    /** What a UI draws, republished after every change. */
    val snapshot: StateFlow<SupportSnapshot> = snapshots.asStateFlow()

    @Volatile
    private var eventHandler: ((SupportTransportEvent) -> Unit)? = null
    private var foreground = true
    private var networkAvailable = true
    private var started = false
    private var stopped = false

    /** A start that failed retryably: retried on backoff, and at once on network or foreground. */
    private var startWanted = false
    private var startConversationId: String? = null
    private var startRetryJob: Job? = null

    /** An HTTP read that replaces the socket is running: no socket may open until it ends. */
    private var httpBusy = false

    /** Bumped whenever the log is replaced, so a scroll-back in flight is dropped. */
    private var logEpoch = 0
    private var sessionRenewals = 0

    /** A 4001 renewal is running: no socket opens with the old token meanwhile. */
    private var renewing = false

    /** The last `error` frame on this socket, for a close that comes without a code. */
    private var lastErrorCode: String? = null
    private var sendGeneration = 0

    private var socket: SupportSocket? = null
    private var generation = 0
    private var readyHead: Long? = null
    private var lastFrameAt = 0L
    private var backoff = configuration.backoffInitialMillis
    private var retryJob: Job? = null
    private var pingJob: Job? = null
    private var consecutiveGaps = 0
    private var consecutiveResyncs = 0

    /** When the socket went live: a drop after a ping interval starts backoff over. */
    private var liveSince: Long? = null
    private var sendJob: Job? = null
    private var sendBackoff = configuration.backoffInitialMillis

    fun setEventHandler(handler: ((SupportTransportEvent) -> Unit)?) {
        eventHandler = handler
    }

    /**
     * Runs [block] on the transport's thread. Never call it with runBlocking from the
     * event handler: the handler runs on that thread, and it would deadlock.
     */
    internal suspend fun <T> read(block: SupportTransport.() -> T): T = withContext(confined) { block() }

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
    suspend fun start(conversationId: String? = null) {
        // Runs in the transport's own scope: a caller cancelled mid-start (the user left
        // the screen) can't leave it half started. A start already running (the background
        // retry, or another caller) is shared rather than skipped.
        val deferred =
            try {
                withContext(confined) {
                    if (started) return@withContext null
                    startInFlight ?: scope.async { startInScope(conversationId) }.also { startInFlight = it }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                throw SupportTransportException.NotStarted()
            } ?: return
        try {
            deferred.await()
        } catch (error: kotlinx.coroutines.CancellationException) {
            // stop() cancelled the transport mid-start: report it as not started, not as a
            // cancellation of the caller's own coroutine.
            if (stopped) throw SupportTransportException.NotStarted()
            throw error
        }
    }

    private var startInFlight: kotlinx.coroutines.Deferred<Unit>? = null

    private suspend fun startInScope(conversationId: String?) {
        startRetryJob?.cancel()
        try {
            stopped = false
            try {
                startSession(conversationId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (other: Exception) {
                // Anything at all ends as a retryable start or a reported failure, never silence.
                val error = other as? SupportTransportException ?: SupportTransportException.Network(other.javaClass.simpleName)
                if (stopped) throw error
                if (error.isRetryable) {
                    // Opened offline, or the server is down: keep trying without the caller.
                    startWanted = true
                    startConversationId = conversationId
                    state =
                        when {
                            !foreground -> SupportConnectionState.Idle
                            networkAvailable -> SupportConnectionState.WaitingToRetry(backoff)
                            else -> SupportConnectionState.Offline
                        }
                    scheduleStartRetry()
                } else {
                    // Not worth retrying (a 4xx, a redirect): say so and stop asking.
                    if (startWanted && error !is SupportTransportException.Redirected) {
                        startWanted = false
                        state = SupportConnectionState.Stopped("start_failed")
                    }
                    emit(SupportTransportEvent.Error((error as? SupportTransportException.Wire)?.body?.tag ?: error.javaClass.simpleName, false))
                }
                throw error
            }
            if (stopped) throw SupportTransportException.NotStarted()
            startWanted = false
            backoff = configuration.backoffInitialMillis
            httpBusy = true
            try {
                reloadNewestWindow()
            } catch (error: SupportTransportException) {
                logger("first window failed (${error.javaClass.simpleName}); the socket catches up")
            } finally {
                httpBusy = false
            }
            started = true
            connect()
            flushSends()
        } finally {
            startInFlight = null
        }
    }

    private fun scheduleStartRetry() {
        startRetryJob?.cancel()
        if (!networkAvailable || !foreground) return
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(configuration.backoffMaxMillis)
        startRetryJob =
            scope.launch {
                delay(wait)
                // Not cancelled by the start it runs.
                startRetryJob = null
                retryStart()
            }
    }

    private suspend fun retryStart() {
        if (!startWanted || started || stopped || startInFlight != null) return
        val deferred = scope.async { startInScope(startConversationId) }
        startInFlight = deferred
        try {
            deferred.await()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Scheduled again inside when it is still retryable.
        }
    }

    /**
     * Closes the socket and stops reconnecting, for good: build a new transport to
     * start again. Pending sends stay queued on disk.
     */
    suspend fun stop() =
        withContext(confined) {
            stopped = true
            startWanted = false
            startRetryJob?.cancel()
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
                if (startWanted) {
                    backoff = configuration.backoffInitialMillis
                    retryStart()
                }
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
            if (available == networkAvailable) {
                // Another network came or went while one stays up (Wi-Fi to cell): the socket
                // may be bound to the one going away, so probe it. Without a socket, leave any
                // pending retry and its backoff alone.
                val current = socket
                if (available && current != null && state != SupportConnectionState.Connecting) sendPing(current, generation)
                return@post
            }
            networkAvailable = available
            if (available) {
                backoff = configuration.backoffInitialMillis
                if (startWanted) retryStart()
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
        // Still connecting: its handshake has no frame to answer with yet, so don't probe it.
        if (state == SupportConnectionState.Connecting) return
        val current = socket
        // A socket that survived may be dead without knowing it: ping now and check the deadline.
        if (current != null) sendPing(current, generation) else connect()
    }

    private fun post(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** Monotonic, so a wall-clock change can't fake a dead socket. */
    private fun now() = System.nanoTime() / 1_000_000

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
            session = started
            stopForGood("redirect")
            throw SupportTransportException.Redirected(it.url)
        }
        val switched = session != null && session?.conversationId != started.conversationId
        if (session?.conversationId != started.conversationId) {
            // A new conversation: the old socket and log belong to the old one.
            if (switched) closeSocket("new conversation")
            log.reset(started.conversationId)
            logEpoch += 1
        }
        val previous = session?.conversationId
        session = started
        if (switched && previous != null) {
            // The old conversation's queued sends can't go anywhere now: fail them visibly.
            val stranded = queue.all.filter { it.conversationId == previous && it.state == PendingMessage.State.PENDING }
            stranded.forEach { item ->
                queue.update(item.clientMessageId) { it.copy(state = PendingMessage.State.FAILED, failure = "conversation_changed") }
            }
            if (stranded.isNotEmpty()) emit(SupportTransportEvent.Error("conversation_changed", false))
            emit(SupportTransportEvent.PendingChanged)
            emit(SupportTransportEvent.LogChanged)
        }
        // The socket was closed for the old conversation: open one for the new one.
        if (switched && this.started) connect()
        logger("session started, conversation ${started.conversationId}")
    }

    /** Runs an authenticated command, starting a new session once if the token is refused. */
    private suspend fun <T> authenticated(call: suspend (token: String, conversationId: String) -> T): T {
        val current = session ?: throw SupportTransportException.NotStarted()
        return try {
            call(current.sessionToken, current.conversationId)
        } catch (error: SupportTransportException.Wire) {
            if (error.body.tag != "SessionInvalid" && error.body.tag != "HTTP401") throw error
            startSession(current.conversationId)
            // Never replay a command against another conversation: a message for A must not land in B.
            val renewed = session?.takeIf { it.conversationId == current.conversationId } ?: throw error
            call(renewed.sessionToken, renewed.conversationId)
        }
    }

    private suspend fun reloadNewestWindow() {
        val window = authenticated { token, id -> http.entries(token, id, limit = configuration.windowSize) }
        log.replace(window)
        logEpoch += 1
        reconcileQueue(window)
        emit(SupportTransportEvent.LogChanged)
    }

    /**
     * Reads `after=cursor` windows until caught up. Past [MAX_CATCH_UP_WINDOWS] it gives
     * up on the gap and loads the newest window; scrolling back fills the rest.
     */
    private suspend fun catchUpOverHttp() {
        stats = stats.copy(httpCatchUps = stats.httpCatchUps + 1)
        val epoch = logEpoch
        repeat(MAX_CATCH_UP_WINDOWS) {
            // Backgrounded, stopped, or the log was replaced (a new conversation): stop reading.
            if (!foreground || stopped || epoch != logEpoch) return
            val cursor = log.cursor
            val window =
                authenticated { token, id -> http.entries(token, id, after = cursor, limit = configuration.windowSize) }
            if (epoch != logEpoch) return
            log.applyCatchUp(window)
            reconcileQueue(window)
            emit(SupportTransportEvent.LogChanged)
            if (!window.hasMore || log.cursor == cursor) return
        }
        reloadNewestWindow()
    }

    /** Sends confirmed by an entry that arrived over HTTP leave the queue, failed ones included. */
    private fun reconcileQueue(window: EntryWindow) {
        var changed = false
        window.decodedEntries.forEach { entry -> entry.clientMessageId?.let { if (queue.remove(it)) changed = true } }
        if (changed) emit(SupportTransportEvent.PendingChanged)
    }

    /** Scrolls back: the window before the oldest entry held. */
    suspend fun loadOlder() =
        withContext(confined) {
            val oldest = log.oldestSeq ?: return@withContext
            if (!log.hasOlder) return@withContext
            val epoch = logEpoch
            val window =
                authenticated { token, id -> http.entries(token, id, before = oldest, limit = configuration.windowSize) }
            // The log was replaced meanwhile: this window belongs to the old one.
            if (epoch != logEpoch || log.oldestSeq != oldest) return@withContext
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
        if (!started || stopped || httpBusy || renewing || !foreground || !networkAvailable || socket != null || current == null) {
            if (!networkAvailable && !stopped) state = SupportConnectionState.Offline
            return
        }
        retryJob?.cancel()
        generation += 1
        val generation = generation
        val socket = socketFactory.make(liveUrl(current.conversationId))
        this.socket = socket
        readyHead = null
        lastErrorCode = null
        lastFrameAt = now()
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
            // An `error` frame before the close says what it means when no code came with it.
            // OkHttp reports a close frame without a code as 1005; 1006 is an abnormal close.
            val reported = socket.closeCode?.takeUnless { it == 1005 || it == 1006 }
            if (generation == this.generation) dropped(reported ?: closeCodeFor(lastErrorCode), error.message ?: "closed")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (other: Exception) {
            // Anything else while handling a frame: drop this socket and retry, never a dead reader.
            if (generation == this.generation) dropped(null, other.javaClass.simpleName)
        }
    }

    private fun closeCodeFor(errorCode: String?): Int? =
        when (errorCode) {
            "session_invalid" -> SupportCloseCode.SESSION_INVALID
            "conversation_not_found", "not_found" -> SupportCloseCode.NOT_FOUND
            "protocol_unsupported" -> SupportCloseCode.PROTOCOL_UNSUPPORTED
            else -> null
        }

    private suspend fun handle(
        text: String,
        socket: SupportSocket,
        generation: Int,
    ) {
        lastFrameAt = now()
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
        // Only an error frame right before the close explains it; any later frame clears it.
        if (frame !is ServerFrame.Error) lastErrorCode = null
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
                state = SupportConnectionState.CatchingUp
                checkCaughtUp()
            }
            is ServerFrame.Entry ->
                when (val outcome = log.applyEntryFrame(frame.seq, frame.prev, frame.entry)) {
                    EntryFrameOutcome.Accept -> {
                        consecutiveGaps = 0
                        consecutiveResyncs = 0
                        sessionRenewals = 0
                        // Progress: the path works, so a drop after this retries from the first step.
                        backoff = configuration.backoffInitialMillis
                        log.entry(frame.seq)?.clientMessageId?.let { id ->
                            if (queue.remove(id)) emit(SupportTransportEvent.PendingChanged)
                        }
                        emit(SupportTransportEvent.LogChanged)
                        checkCaughtUp()
                        if (configuration.dropAfterAcceptingForTesting?.invoke(frame.seq) == true) {
                            socket.close(SupportCloseCode.GOING_AWAY, "test drop")
                            dropped(null, "test drop after seq ${frame.seq}")
                        }
                    }
                    EntryFrameOutcome.Duplicate -> {
                        stats = stats.copy(duplicates = stats.duplicates + 1)
                        checkCaughtUp()
                    }
                    is EntryFrameOutcome.Gap -> {
                        stats = stats.copy(gaps = stats.gaps + 1)
                        consecutiveGaps += 1
                        logger("gap: holding ${outcome.expected}, frame prev ${outcome.prev}; reconnecting")
                        replaceSocket("gap", useBackoff = consecutiveGaps > 3, readsHttp = false) {}
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
                consecutiveResyncs += 1
                logger("resync: ${frame.reason}")
                // A server that keeps answering resync gets backoff, not a reload loop.
                replaceSocket("resync", useBackoff = consecutiveResyncs > 3) { reloadNewestWindow() }
            }
            // The close that follows says what to do (session invalid, not found …).
            is ServerFrame.Error -> {
                lastErrorCode = frame.code
                emit(SupportTransportEvent.Error(frame.code, frame.retryable))
            }
            ServerFrame.Pong -> Unit
        }
    }

    /** Live once the cursor reaches the head `ready` announced. */
    private fun checkCaughtUp() {
        val head = readyHead ?: return
        if (state != SupportConnectionState.CatchingUp || log.cursor < head) return
        state = SupportConnectionState.Live
        // consecutiveResyncs is not reset here: a server that answers ready then resync must
        // keep backing off. Only an accepted entry resets it.
        liveSince = now()
    }

    /** Drops this socket, runs [work] (an HTTP read), then reconnects. No socket opens meanwhile. */
    private suspend fun replaceSocket(
        reason: String,
        useBackoff: Boolean = false,
        readsHttp: Boolean = true,
        work: suspend () -> Unit,
    ) {
        closeSocket(reason)
        val cursorBefore = log.cursor
        httpBusy = true
        state = SupportConnectionState.CatchingUp
        try {
            work()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Anything at all: the transport must end with a socket or a retry timer.
            logger("$reason failed: ${error.javaClass.simpleName}")
            httpBusy = false
            scheduleRetry()
            return
        } finally {
            httpBusy = false
        }
        // An HTTP read that moved nothing (bad data) must not loop ready → read → connect.
        val noProgress = readsHttp && log.cursor <= cursorBefore
        if (useBackoff || noProgress) scheduleRetry() else connect()
    }

    private fun closeSocket(reason: String) {
        generation += 1
        pingJob?.cancel()
        pingJob = null
        socket?.close(SupportCloseCode.NORMAL, reason)
        socket = null
        readyHead = null
        liveSince = null
    }

    private fun dropped(
        closeCode: Int?,
        reason: String,
    ) {
        stats = stats.copy(drops = stats.drops + 1)
        // A server that accepts and drops at once keeps backing off; a socket that lasted resets it.
        liveSince?.let {
            if (now() - it >= configuration.pingIntervalMillis) {
                backoff = configuration.backoffInitialMillis
                sessionRenewals = 0
            }
        }
        liveSince = null
        logger("socket dropped: code ${closeCode ?: "none"}, $reason")
        closeSocket("dropped")
        when (closeCode) {
            SupportCloseCode.SESSION_INVALID -> {
                state = SupportConnectionState.Connecting
                renewing = true
                scope.launch {
                    sessionRenewals += 1
                    try {
                        startSession(session?.conversationId)
                        renewing = false
                        // A server that keeps refusing new tokens gets backoff, not a tight loop.
                        if (sessionRenewals > 1) scheduleRetry() else connect()
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        renewing = false
                        scheduleRetry()
                    } finally {
                        renewing = false
                    }
                }
            }
            SupportCloseCode.PROTOCOL_UNSUPPORTED -> stopForGood("protocol_unsupported")
            SupportCloseCode.NOT_FOUND -> stopForGood("not_found")
            // 1001, 1011, 4029, 4003 or a network failure: back off and retry.
            else -> scheduleRetry()
        }
    }

    /** Won't reconnect: closes the socket and fails pending sends so they don't sit forever. */
    companion object {
        const val MAX_CATCH_UP_WINDOWS = 5
    }

    private fun stopForGood(reason: String) {
        stopped = true
        retryJob?.cancel()
        closeSocket(reason)
        sendJob?.cancel()
        var changed = false
        val conversationId = session?.conversationId
        queue.all.filter { it.state == PendingMessage.State.PENDING && it.conversationId == conversationId }.forEach { item ->
            queue.update(item.clientMessageId) { it.copy(state = PendingMessage.State.FAILED, failure = reason) }
            changed = true
        }
        if (changed) emit(SupportTransportEvent.PendingChanged)
        state = SupportConnectionState.Stopped(reason)
    }

    private fun scheduleRetry() {
        if (stopped || socket != null) return
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
        // The server's heartbeat is an upper bound; never below a second, whatever it says.
        val interval =
            minOf(configuration.pingIntervalMillis, (session?.heartbeatSeconds?.takeIf { it > 0 } ?: 10) * 1000L)
                .coerceAtLeast(1_000)
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
        val sentAt = now()
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
            // Stopped for good, or from another conversation: it can't be sent from here.
            if (stopped) return@post
            if (queue.all.firstOrNull { it.clientMessageId == clientMessageId }?.conversationId != session?.conversationId) return@post
            queue.update(clientMessageId) { it.copy(state = PendingMessage.State.PENDING, failure = null) }
            emit(SupportTransportEvent.PendingChanged)
            flushSends(resetBackoff = true)
        }

    fun discard(clientMessageId: String) =
        post {
            if (queue.remove(clientMessageId)) emit(SupportTransportEvent.PendingChanged)
        }

    /**
     * Starts draining the queue. With [resetBackoff] (foreground, network back, a manual
     * retry) a drain asleep in backoff is woken: retrying the same id is safe.
     */
    private fun flushSends(resetBackoff: Boolean = false) {
        if (resetBackoff) {
            sendBackoff = configuration.backoffInitialMillis
            sendJob?.cancel()
            sendJob = null
        }
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
                // Only into the log of the conversation it was sent to.
                if (session?.conversationId == conversationId) log.holdOutOfBand(WireEntry.decode(accepted.entry))
                emit(SupportTransportEvent.PendingChanged)
                emit(SupportTransportEvent.LogChanged)
            } catch (error: SupportTransportException) {
                if (error.isRetryable) {
                    // Cancelled to restart at once: leave the backoff to the new drain.
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
                    logger("send retry after ${error.javaClass.simpleName}")
                    val wait = maxOf(sendBackoff, error.retryAfterMillis ?: 0)
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
            // Hashing up to 25 MB stays off the transport's thread.
            val sha256 = withContext(Dispatchers.IO) { SupportHttpClient.sha256Hex(bytes) }
            val request = UploadRequest(kind, filename.take(255), mime, bytes.size.toLong(), sha256)
            // Each step retries on its own, so a failed complete doesn't re-upload 25 MB.
            val intent = withRetries { authenticated { token, id -> http.createUpload(token, id, request) } }
            withRetries { http.put(intent.upload, bytes) }
            withRetries { authenticated { token, _ -> http.completeUpload(token, intent.attachmentId) } }
        }

    suspend fun attachmentUrl(attachmentId: String): String =
        withContext(confined) { authenticated { token, _ -> http.attachmentUrl(token, attachmentId) }.url }

    suspend fun markRead(seq: Long) =
        withContext(confined) { withRetries { authenticated { token, id -> http.markRead(token, id, seq) } } }

    suspend fun cardAction(
        cardId: String,
        request: CardActionRequest,
    ) = withContext(confined) {
        val conversationId = session?.conversationId
        val accepted = withRetries { authenticated { token, _ -> http.cardAction(token, cardId, request) } }
        if (session?.conversationId == conversationId) accepted.entries.forEach { log.holdOutOfBand(WireEntry.decode(it)) }
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
                delay(maxOf(wait, error.retryAfterMillis ?: 0))
                wait = (wait * 2).coerceAtMost(configuration.backoffMaxMillis)
            }
        }
    }

    // endregion

    private fun emit(event: SupportTransportEvent) {
        snapshots.value =
            SupportSnapshot(
                state = state,
                conversationId = log.conversationId,
                entries = log.entries,
                tail = log.tail,
                status = log.status,
                statusEtaSeconds = log.statusEtaSeconds,
                presence = log.presence,
                hasOlder = log.hasOlder,
                cursor = log.cursor,
                pending = pendingMessages,
            )
        // A throwing handler must not break the transport mid-step.
        eventHandler?.let { handler -> runCatching { handler(event) }.onFailure { logger("event handler threw ${it.javaClass.simpleName}") } }
    }

    /** Drops the socket as if the network had cut it. For test apps and debugging only. */
    fun dropConnectionForTesting() =
        post {
            val current = socket ?: return@post
            current.close(SupportCloseCode.GOING_AWAY, "simulated")
            dropped(null, "simulated")
        }
}
