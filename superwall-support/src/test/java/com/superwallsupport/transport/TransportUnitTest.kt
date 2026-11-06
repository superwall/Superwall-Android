package com.superwallsupport.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The transport against a scripted socket and stubbed HTTP, for cases a real server won't produce on demand. */
class TransportUnitTest {
    companion object {
        const val CONVERSATION = "conv_01M6R13Q95YDTN13DKKN7BFQD2"

        fun entryId(seq: Long) = "ent_" + seq.toString().padStart(26, '0')

        fun entryFrame(
            seq: Long,
            prev: Long,
        ) = buildJsonObject {
            put("type", "entry")
            put("seq", seq)
            put("prev", prev)
            put("entry", FixtureTest.entry(seq, entryId(seq)))
        }.toString()

        fun ready(
            after: Long,
            head: Long,
        ) = """{"type":"ready","protocol":"superwall-support/1","conversationId":"$CONVERSATION","after":$after,"head":$head}"""
    }

    private val sockets = FakeSocketFactory()
    private val http = StubHttp()

    private fun transport() =
        SupportTransport(
            SupportTransport.Configuration(
                baseUrl = "https://inbox.test",
                apiKey = "pk_unit",
                sdk = SupportSdk("test", "0"),
                locale = "en",
                proofs = listOf(SupportProof("install_secret", "i")),
                backoffInitialMillis = 20,
                backoffMaxMillis = 50,
                pingIntervalMillis = 200,
                pingDeadlineMillis = 100,
            ),
            SupportHttpClient("https://inbox.test", OkHttpClient.Builder().addInterceptor(http).build()),
            sockets,
            logger = {},
        )

    private suspend fun waitUntil(
        what: String,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun hello(socket: FakeSocket) = ClientFrame.decode(socket.sent.first()) as ClientFrame.Hello

    @Test
    fun helloCarriesTheCursor() =
        runBlocking {
            http.windowEntries = listOf(1, 2, 3)
            val transport = transport()
            transport.start()
            waitUntil("socket") { sockets.made.size == 1 && sockets.latest.sent.isNotEmpty() }
            val hello = hello(sockets.latest)
            assertEquals(CONVERSATION, hello.conversationId)
            assertEquals(3L, hello.after)
            assertEquals(entryId(3), hello.afterId)
            transport.stop()
        }

    @Test
    fun pingDeadlineRunsDuringCatchUp() =
        runBlocking {
            http.windowEntries = listOf(1)
            val transport = transport()
            transport.start()
            waitUntil("socket") { sockets.made.size == 1 }
            val first = sockets.latest
            first.answersPings = false
            first.deliver(ready(1, 5))
            first.deliver(entryFrame(2, 1))
            waitUntil("catching up") { transport.read { state } == SupportConnectionState.CatchingUp }
            waitUntil("timeout and a new socket") { sockets.made.size == 2 && sockets.latest.sent.isNotEmpty() }
            assertEquals(1, transport.read { stats.pingTimeouts })
            assertEquals(2L, hello(sockets.latest).after)
            transport.stop()
        }

    @Test
    fun gapReconnectsWithTheCursor() =
        runBlocking {
            http.windowEntries = listOf(1)
            val transport = transport()
            transport.start()
            waitUntil("socket") { sockets.made.size == 1 }
            sockets.latest.deliver(ready(1, 4))
            sockets.latest.deliver(entryFrame(3, 2))
            waitUntil("reconnect") { sockets.made.size == 2 }
            assertEquals(1, transport.read { stats.gaps })
            assertEquals(1L, transport.read { log.cursor })
            transport.stop()
        }

    @Test
    fun resyncReloadsTheNewestWindow() =
        runBlocking {
            http.windowEntries = listOf(1, 2)
            val transport = transport()
            transport.start()
            waitUntil("socket") { sockets.made.size == 1 }
            http.windowEntries = listOf(10, 11, 12)
            sockets.latest.deliver("""{"type":"resync","reason":"boundary_mismatch","head":12}""")
            waitUntil("reconnect") { sockets.made.size == 2 && sockets.latest.sent.isNotEmpty() }
            assertEquals(listOf(10L, 11L, 12L), transport.read { log.entries.map { it.seq } })
            assertEquals(12L, hello(sockets.latest).after)
            transport.stop()
        }

    @Test
    fun closeCodes() =
        runBlocking {
            for ((code, expected) in listOf(
                SupportCloseCode.NOT_FOUND to "not_found",
                SupportCloseCode.PROTOCOL_UNSUPPORTED to "protocol_unsupported",
            )) {
                val made = sockets.made.size
                val transport = transport()
                transport.start()
                waitUntil("socket") { sockets.made.size == made + 1 }
                sockets.latest.closeFromServer(code)
                waitUntil("stopped") { transport.read { state } == SupportConnectionState.Stopped(expected) }
                delay(100)
                assertEquals("never retries after $code", made + 1, sockets.made.size)
            }
        }

    @Test
    fun sessionInvalidStartsANewSession() =
        runBlocking {
            val transport = transport()
            transport.start()
            waitUntil("socket") { sockets.made.size == 1 }
            sockets.latest.closeFromServer(SupportCloseCode.SESSION_INVALID)
            waitUntil("new socket") { sockets.made.size == 2 && sockets.latest.sent.isNotEmpty() }
            assertEquals(2, http.sessionsStarted)
            assertEquals("tok_2", hello(sockets.latest).sessionToken)
            transport.stop()
        }

    @Test
    fun tailIsTruncateThenAppendAndSealedByItsEntry() =
        runBlocking {
            http.windowEntries = listOf(1)
            val transport = transport()
            transport.start()
            waitUntil("socket") { sockets.made.size == 1 }
            val socket = sockets.latest
            val id = entryId(2)
            socket.deliver(ready(1, 1))
            socket.deliver("""{"type":"tail","messageId":"$id","offset":0,"text":"You renewed "}""")
            socket.deliver("""{"type":"tail","messageId":"$id","offset":12,"text":"the Year"}""")
            socket.deliver("""{"type":"tail","messageId":"$id","offset":12,"text":"the Year"}""")
            socket.deliver("""{"type":"tail","messageId":"$id","offset":99,"text":"zzz"}""")
            waitUntil("tail") { transport.read { log.tail?.text } == "You renewed the Year" }
            socket.deliver(entryFrame(2, 1))
            waitUntil("sealed") { transport.read { log.tail } == null }
            assertEquals(2, transport.read { log.entries.size })
            transport.stop()
        }

    @Test
    fun uploadAsksPutsThenCompletes() =
        runBlocking {
            val transport = transport()
            transport.start()
            val bytes = "diagnostic log".toByteArray()
            val completed = transport.uploadAttachment(bytes, "log.txt", "text/plain")
            assertEquals("quarantine", completed.status)
            assertEquals(
                listOf(
                    "POST /v1/conversations/$CONVERSATION/attachments",
                    "PUT https://uploads.test/put/att_1",
                    "POST /v1/attachments/att_01M6R13Q95YDTN13DKKN7BFQD2/complete",
                ),
                http.calls.filter { !it.startsWith("POST /v1/sessions") && !it.startsWith("GET") },
            )
            assertEquals("diagnostic log", http.uploaded)
            assertEquals(SupportHttpClient.sha256Hex(bytes), (http.uploadRequest?.get("sha256") as JsonPrimitive).content)
            transport.stop()
        }

    @Test
    fun sendRetriesTheSameClientMessageId() =
        runBlocking {
            http.failSends = 2
            val transport = transport()
            transport.start()
            val pending = transport.send("Hello")
            waitUntil("accepted") { transport.read { pendingMessages.isEmpty() } }
            assertEquals(List(3) { pending.clientMessageId }, http.sendIds.toList())
            transport.stop()
        }

    @Test
    fun nonRetryableSendFailsThenRetriesByHand() =
        runBlocking {
            http.rejectSends = true
            val transport = transport()
            transport.start()
            val pending = transport.send("Hello")
            waitUntil("failed") { transport.read { pendingMessages.firstOrNull()?.state } == PendingMessage.State.FAILED }
            assertEquals("NotPermitted", transport.read { pendingMessages.first().failure })
            http.rejectSends = false
            transport.retry(pending.clientMessageId)
            waitUntil("accepted") { transport.read { pendingMessages.isEmpty() } }
            transport.stop()
        }

    /** The network dies between session start and the first window: start still connects. */
    @Test
    fun firstWindowFailureStillConnects() =
        runBlocking {
            http.failEntries = 1
            http.windowEntries = (1L..100L).toList()
            val transport = transport()
            transport.start()
            waitUntil("socket") { sockets.made.size == 1 }
            sockets.latest.deliver(ready(0, 100))
            waitUntil("newest window") { transport.read { log.cursor } == 100L }
            assertTrue("no catch-up from 0", http.calls.none { it.contains("after=") })
            transport.stop()
        }

    @Test
    fun overlongMessageIsRefused() =
        runBlocking {
            val transport = transport()
            transport.start()
            try {
                transport.send("a".repeat(SupportWire.MAX_MESSAGE_LENGTH + 1))
                throw AssertionError("expected MessageTooLong")
            } catch (_: SupportTransportException.MessageTooLong) {
            }
            assertTrue(transport.read { pendingMessages.isEmpty() })
            transport.stop()
        }
}

class FakeSocket : SupportSocket {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<String>()

    @Volatile var answersPings = true

    @Volatile override var closeCode: Int? = null

    override fun send(text: String): Boolean {
        if (inbox.isClosedForSend) return false
        sent.add(text)
        if (text == SupportWire.PING_FRAME && answersPings) deliver("""{"type":"pong"}""")
        return true
    }

    override suspend fun receive(): String = inbox.receiveCatching().getOrNull() ?: throw SupportSocket.Closed(closeCode, "closed")

    override fun close(
        code: Int,
        reason: String,
    ) {
        inbox.close()
    }

    fun deliver(text: String) {
        inbox.trySend(text)
    }

    fun closeFromServer(code: Int) {
        closeCode = code
        inbox.close()
    }
}

class FakeSocketFactory : SupportSocketFactory {
    val made = CopyOnWriteArrayList<FakeSocket>()
    val latest: FakeSocket get() = made.last()

    override fun make(url: String): SupportSocket = FakeSocket().also { made.add(it) }
}

/** Answers the inbox API's HTTP calls from memory, as an OkHttp interceptor. */
class StubHttp : Interceptor {
    @Volatile var windowEntries: List<Long> = emptyList()

    @Volatile var sessionsStarted = 0

    @Volatile var failSends = 0

    @Volatile var failEntries = 0

    @Volatile var rejectSends = false
    val calls = CopyOnWriteArrayList<String>()
    val sendIds = CopyOnWriteArrayList<String>()

    @Volatile var uploadRequest: JsonObject? = null

    @Volatile var uploaded: String? = null

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() } ?: ""
        calls.add(
            if (url.host == "uploads.test") "${request.method} $url" else "${request.method} ${url.encodedPath}${url.encodedQuery?.let { "?$it" } ?: ""}",
        )
        val path = url.encodedPath
        val (code, json) =
            when {
                request.method == "POST" && path == "/v1/sessions" -> {
                    sessionsStarted += 1
                    200 to
                        """{"protocol":"superwall-support/1","sessionId":"sess_01M6R13Q96S0JV0S92F7TEJ3CH","sessionToken":"tok_$sessionsStarted","expiresAt":"2026-11-06T09:55:23.174Z","applicationId":1,"conversationId":"${TransportUnitTest.CONVERSATION}","proofs":[],"caps":{"ui":{"v":1,"elements":{}},"cards":[],"actions":[]},"consentRequired":false,"theme":{},"human":{"available":false},"heartbeatSeconds":25}"""
                }
                request.method == "GET" && path.endsWith("/entries") && failEntries > 0 -> {
                    failEntries -= 1
                    503 to """{"_tag":"WireUnavailable","message":"Down"}"""
                }
                request.method == "GET" && path.endsWith("/entries") ->
                    200 to
                        buildJsonObject {
                            put("conversationId", TransportUnitTest.CONVERSATION)
                            put(
                                "entries",
                                kotlinx.serialization.json.JsonArray(
                                    windowEntries.map { FixtureTest.entry(it, TransportUnitTest.entryId(it)) },
                                ),
                            )
                            put("head", windowEntries.lastOrNull() ?: 0)
                            put("hasMore", false)
                        }.toString()
                path.endsWith("/messages") -> {
                    val id = (parseJson(body) as JsonObject)["clientMessageId"]!!.stringOrNull!!
                    sendIds.add(id)
                    when {
                        rejectSends -> 403 to """{"_tag":"NotPermitted","message":"No","retryable":false}"""
                        failSends > 0 -> {
                            failSends -= 1
                            503 to """{"_tag":"WireUnavailable","message":"Try again"}"""
                        }
                        else -> {
                            val entry =
                                JsonObject(
                                    FixtureTest.entry(99, TransportUnitTest.entryId(99), "person_message") +
                                        mapOf(
                                            "clientMessageId" to JsonPrimitive(id),
                                            "author" to buildJsonObject { put("type", "person") },
                                        ),
                                )
                            200 to
                                buildJsonObject {
                                    put("entry", entry)
                                    put("duplicate", false)
                                }.toString()
                        }
                    }
                }
                path.endsWith("/attachments") -> {
                    uploadRequest = parseJson(body) as JsonObject
                    200 to
                        """{"attachmentId":"att_01M6R13Q95YDTN13DKKN7BFQD2","upload":{"method":"PUT","url":"https://uploads.test/put/att_1","headers":{"content-type":"text/plain"},"expiresAt":"2026-11-06T10:10:00.000Z"}}"""
                }
                request.method == "PUT" -> {
                    uploaded = body
                    200 to ""
                }
                path.endsWith("/complete") ->
                    200 to """{"attachmentId":"att_01M6R13Q95YDTN13DKKN7BFQD2","status":"quarantine"}"""
                else -> 404 to """{"_tag":"ConversationNotFound","message":"No"}"""
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
