package com.superwallsupport.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * End to end against paywall-next's fixture server:
 *
 *   PORT=8791 pnpm --filter @superwall/inbox-test-client fixture-server
 *   SUPPORT_FIXTURE_URL=http://localhost:8791 ./gradlew :superwall-support:testDebugUnitTest
 *
 * Skipped when SUPPORT_FIXTURE_URL isn't set. Against a real inbox-api (the Core lane's
 * preview) set SUPPORT_API_KEY (e.g. pk_echo) and SUPPORT_FIXTURE_SCRIPTS=0: the scripted
 * conversations are fixture-server only and are skipped.
 */
class FixtureServerTest {
    private lateinit var baseUrl: String
    private val client = OkHttpClient()
    private val plainKey = System.getenv("SUPPORT_API_KEY") ?: "pk_test_plain"

    private fun requireScripts() = assumeTrue("fixture-server scripts only", System.getenv("SUPPORT_FIXTURE_SCRIPTS") != "0")

    /** Waits until the client holds exactly what the server's window holds. */
    private suspend fun waitInSync(
        transport: SupportTransport,
        atLeast: Int,
        timeoutMillis: Long = 30_000,
    ) = waitUntil("in sync with the server (>= $atLeast entries)", timeoutMillis) {
        val server = runCatching { serverEntries(transport) }.getOrNull()
        server != null && server.size >= atLeast && transport.read { log.entries } == server
    }

    companion object {
        /** 256 random bits, as inbox-api requires of an install secret (43 base64url characters). */
        fun installSecret(): String {
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    @Before
    fun setUp() {
        val url = System.getenv("SUPPORT_FIXTURE_URL")
        assumeTrue("Set SUPPORT_FIXTURE_URL to run against the fixture server", url != null)
        baseUrl = url!!
    }

    private fun transport(
        apiKey: String,
        install: String = installSecret(),
        queueFile: File? = null,
        replayCap: Int = 60,
        dropAfter: ((Long) -> Boolean)? = null,
    ) = SupportTransport(
        SupportTransport.Configuration(
            baseUrl = baseUrl,
            apiKey = apiKey,
            sdk = SupportSdk("test", "0.0.0"),
            locale = "en",
            proofs = listOf(SupportProof("install_secret", install)),
            backoffInitialMillis = 50,
            backoffMaxMillis = 400,
            socketReplayCap = replayCap,
            sendQueueFile = queueFile,
            dropAfterAcceptingForTesting = dropAfter,
        ),
        client,
    )

    private suspend fun serverEntries(transport: SupportTransport): List<WireEntry> {
        val session = transport.read { session }!!
        val window = SupportHttpClient(baseUrl, client).entries(session.sessionToken, session.conversationId, limit = 200)
        return window.decodedEntries
    }

    private suspend fun waitUntil(
        what: String,
        timeoutMillis: Long = 15_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private suspend fun waitForScript(
        transport: SupportTransport,
        script: String,
    ) {
        val transcript = FixtureTest.load("wire/conversations/$script.json") as kotlinx.serialization.json.JsonObject
        val count = (transcript["window"] as kotlinx.serialization.json.JsonObject)["entries"].let { (it as kotlinx.serialization.json.JsonArray).size }
        val status = ((transcript["frames"] as kotlinx.serialization.json.JsonArray).last() as kotlinx.serialization.json.JsonObject)["state"]?.stringOrNull
        waitUntil("$script played out", 20_000) {
            transport.read { log.entries.size == count && log.tail == null && log.status == status }
        }
    }

    private suspend fun post(
        transport: SupportTransport,
        count: Int,
        prefix: String,
    ) {
        val session = transport.read { session }!!
        val http = SupportHttpClient(baseUrl, client)
        repeat(count) { index ->
            http.sendMessage(session.sessionToken, session.conversationId, SendMessage("$prefix-$index", "Message $index"))
        }
    }

    @Test
    fun pacedScriptsArriveExact() =
        runBlocking {
            requireScripts()
            for (script in listOf("billing-question", "human-handoff", "future-kinds")) {
                val transport = transport("pk_fixture_$script")
                transport.start()
                waitForScript(transport, script)
                assertEquals(script, serverEntries(transport), transport.read { log.entries })
                assertEquals(script, 0, transport.read { stats.gaps })
                transport.stop()
            }
        }

    /** The transport cuts its own socket right after each new seq while 30 messages land. */
    @Test
    fun resumesFromEverySeq() =
        runBlocking {
            val seen = Collections.synchronizedSet(HashSet<Long>())
            val transport = transport(plainKey, dropAfter = { seen.add(it) })
            transport.start()
            waitUntil("live") { transport.read { state } == SupportConnectionState.Live }
            post(transport, 30, "every")
            // One reconnect per entry: against a remote server each is a fresh TLS handshake.
            waitInSync(transport, 30, 120_000)
            waitUntil("live") { transport.read { state } == SupportConnectionState.Live }
            val held = transport.read { log.entries }
            assertEquals(serverEntries(transport), held)
            assertEquals((1L..held.size.toLong()).toList(), held.map { it.seq })
            val stats = transport.read { stats }
            assertEquals(held.size, seen.size)
            assertEquals(0, stats.gaps)
            println("resume from every seq: ${seen.size} drops, ${stats.connects} connects, gaps ${stats.gaps}, dups ${stats.duplicates}")
            transport.stop()
        }

    @Test
    fun backgroundThenForeground() =
        runBlocking {
            val transport = transport(plainKey)
            transport.start()
            waitUntil("live") { transport.read { state } == SupportConnectionState.Live }
            transport.setForeground(false)
            waitUntil("idle") { transport.read { state } == SupportConnectionState.Idle }
            post(transport, 10, "bg")
            transport.setForeground(true)
            waitInSync(transport, 10)
            transport.stop()
        }

    @Test
    fun networkLossThenReturn() =
        runBlocking {
            requireScripts()
            val transport = transport("pk_fixture_future-kinds")
            transport.start()
            waitUntil("first entry") { transport.read { log.entries.isNotEmpty() } }
            transport.setNetworkAvailable(false)
            waitUntil("offline") { transport.read { state } == SupportConnectionState.Offline }
            delay(1_000)
            val started = System.currentTimeMillis()
            transport.setNetworkAvailable(true)
            waitUntil("live", 5_000) { transport.read { state } == SupportConnectionState.Live }
            println("network back -> live in ${System.currentTimeMillis() - started} ms")
            waitForScript(transport, "future-kinds")
            assertEquals(serverEntries(transport), transport.read { log.entries })
            transport.stop()
        }

    @Test
    fun sendQueueSurvivesRelaunchAndLandsOnce() =
        runBlocking {
            val install = installSecret()
            val file = File.createTempFile("send-queue", ".json").also { it.delete() }
            try {
                val first = transport(plainKey, install, file)
                first.start()
                val conversationId = first.read { session }!!.conversationId
                first.setNetworkAvailable(false)
                waitUntil("offline") { first.read { state } == SupportConnectionState.Offline }
                val pending = first.send("Where is my receipt?")
                first.stop()
                assertTrue(file.readText().contains(pending.clientMessageId))

                val second = transport(plainKey, install, file)
                second.start(conversationId)
                waitUntil("drained") { second.read { pendingMessages.isEmpty() } }
                waitUntil("entry over the socket") {
                    second.read { log.entries.any { it.clientMessageId == pending.clientMessageId } }
                }
                val session = second.read { session }!!
                val again =
                    SupportHttpClient(baseUrl, client)
                        .sendMessage(session.sessionToken, conversationId, SendMessage(pending.clientMessageId, "ignored"))
                assertTrue(again.duplicate)
                waitInSync(second, 1)
                val server = serverEntries(second)
                assertEquals(1, server.count { it.clientMessageId == pending.clientMessageId })
                second.stop()
            } finally {
                file.delete()
            }
        }

    @Test
    fun farBehindCatchesUpOverHttp() =
        runBlocking {
            val transport = transport(plainKey, replayCap = 10)
            transport.start()
            waitUntil("live") { transport.read { state } == SupportConnectionState.Live }
            post(transport, 2, "held")
            waitInSync(transport, 2)
            transport.setForeground(false)
            waitUntil("idle") { transport.read { state } == SupportConnectionState.Idle }
            post(transport, 25, "far")
            transport.setForeground(true)
            waitInSync(transport, 27)
            waitUntil("live") { transport.read { state } == SupportConnectionState.Live }
            assertEquals(1, transport.read { stats.httpCatchUps })
            transport.stop()
        }
}
