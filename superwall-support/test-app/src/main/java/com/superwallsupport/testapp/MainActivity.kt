package com.superwallsupport.testapp

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.superwallsupport.transport.EntryWindow
import com.superwallsupport.transport.SupportLifecycleMonitor
import com.superwallsupport.transport.SupportProof
import com.superwallsupport.transport.SupportSdk
import com.superwallsupport.transport.SupportTransport
import com.superwallsupport.transport.SupportTransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections

/**
 * Intent extras (adb shell am start ... --es/--ei/--ez):
 *   baseUrl       inbox API origin, default http://10.0.2.2:8791 (the host, from the emulator)
 *   apiKey        default pk_fixture_billing-question
 *   dropEverySeq  cut the socket after each new seq
 *   sendBurst     send N messages through the queue on start
 *   verifyEvery   compare with the server's window every N seconds
 * Log lines: logcat tag SWSUPPORT, and files/support-transport.log.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var transport: SupportTransport
    private lateinit var monitor: SupportLifecycleMonitor
    private lateinit var view: TextView
    private lateinit var logFile: File
    private val lines = StringBuilder()
    private val started = System.currentTimeMillis()
    private val client = OkHttpClient()
    private var baseUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { textSize = 10f; setPadding(24, 48, 24, 24) }
        setContentView(ScrollView(this).apply { addView(view) })
        logFile = File(filesDir, "support-transport.log").also { it.writeText("") }

        baseUrl = intent.getStringExtra("baseUrl") ?: "http://10.0.2.2:8791"
        val apiKey = intent.getStringExtra("apiKey") ?: "pk_fixture_billing-question"
        val dropEverySeq = intent.getBooleanExtra("dropEverySeq", false)
        val burst = intent.getIntExtra("sendBurst", 0)
        val verifyEvery = intent.getIntExtra("verifyEvery", 0)
        val dropped = Collections.synchronizedSet(HashSet<Long>())
        transport =
            SupportTransport(
                SupportTransport.Configuration(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    sdk = SupportSdk("android", "test", addonVersion = "transport-m1"),
                    locale = resources.configuration.locales[0].toLanguageTag(),
                    // 256 random bits, as inbox-api requires of an install secret.
                    proofs = listOf(SupportProof("install_secret", installSecret())),
                    sendQueueFile = File(filesDir, "send-queue.json").also { it.delete() },
                    dropAfterAcceptingForTesting = if (dropEverySeq) { seq -> dropped.add(seq) } else null,
                ),
            )
        monitor = SupportLifecycleMonitor(this, transport)
        log("launch apiKey=$apiKey dropEverySeq=$dropEverySeq")
        transport.setEventHandler { event -> scope.launch { onEvent(event) } }
        monitor.start()
        scope.launch {
            try {
                transport.start()
            } catch (error: Exception) {
                // The transport keeps retrying on its own; wait for its session.
                log("start failed: ${error.javaClass.simpleName}; the transport retries")
            }
            while (transport.session == null) delay(100)
            val session = transport.session!!
            // Test app only: the host script posts into this conversation with it.
            log("started conversation ${session.conversationId} token ${session.sessionToken}")
            repeat(burst) { index -> transport.send("Test message ${index + 1}") }
            if (verifyEvery > 0) {
                while (true) {
                    delay(verifyEvery * 1000L)
                    verify()
                }
            }
        }
    }

    private fun onEvent(event: SupportTransportEvent) {
        val cursor = transport.snapshot.value.cursor
        val stats = transport.stats
        when (event) {
            is SupportTransportEvent.ConnectionChanged ->
                log(
                    "state ${event.state} cursor=$cursor connects=${stats.connects} drops=${stats.drops} gaps=${stats.gaps} " +
                        "dups=${stats.duplicates} httpCatchUps=${stats.httpCatchUps} pingTimeouts=${stats.pingTimeouts}",
                )
            is SupportTransportEvent.PendingChanged -> log("pending ${transport.pendingMessages.size}")
            is SupportTransportEvent.Error -> log("error ${event.code} retryable=${event.retryable}")
            SupportTransportEvent.LogChanged -> Unit
        }
    }

    /** Compares what the client holds with the server's newest window. */
    private suspend fun verify() {
        val session = transport.session ?: return
        val window =
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url("$baseUrl/v1/conversations/${session.conversationId}/entries?limit=200")
                            .header("Authorization", "Bearer ${session.sessionToken}")
                            .build()
                    val body = client.newCall(request).execute().use { it.body!!.string() }
                    Json { ignoreUnknownKeys = true }.decodeFromString(EntryWindow.serializer(), body)
                }
            }.getOrNull() ?: return log("verify failed")
        val held = transport.snapshot.value.entries
        val server = window.decodedEntries
        log("verify exact=${held == server} held=${held.map { it.seq }} server=${server.map { it.seq }}")
    }

    private fun log(message: String) {
        val line = "%8.3f %s".format((System.currentTimeMillis() - started) / 1000.0, message)
        Log.i("SWSUPPORT", line)
        logFile.appendText(line + "\n")
        lines.insert(0, line + "\n")
        if (lines.length > 8000) lines.setLength(8000)
        view.text = lines
    }

    private fun installSecret(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    override fun onDestroy() {
        monitor.stop()
        super.onDestroy()
    }
}
