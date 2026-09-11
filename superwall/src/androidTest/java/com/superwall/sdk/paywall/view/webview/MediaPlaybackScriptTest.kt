package com.superwall.sdk.paywall.view.webview

import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the injected script against real HTML video elements and Chromium playback. */
@RunWith(AndroidJUnit4::class)
class MediaPlaybackScriptTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView

    @Before
    fun setUp() {
        val video = instrumentation.context.assets.open("media-playback.mp4").use { it.readBytes() }
        val source = "data:video/mp4;base64," + Base64.encodeToString(video, Base64.NO_WRAP)
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.countDown()
                    }
                }
            webView.loadDataWithBaseURL(
                "https://localhost/",
                """
                <html><body>
                <video id="playing" muted loop playsinline src="$source"></video>
                <video id="manual" muted loop playsinline src="$source"></video>
                </body></html>
                """.trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }
        assertTrue("Page did not load", loaded.await(10, TimeUnit.SECONDS))
        await("document.getElementById('playing').readyState >= 3")
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { webView.destroy() }
    }

    @Test
    fun pausesAndResumesOnlyPreviouslyPlayingVideo() {
        evaluate("document.getElementById('playing').play()")
        await("document.getElementById('playing').currentTime > 0")
        evaluate(MediaPlaybackScript.build(false))
        evaluate(MediaPlaybackScript.build(false)) // Repeated lifecycle callbacks must preserve the set.
        assertEquals("true", evaluate("document.getElementById('playing').paused"))
        val pausedTime = evaluate("document.getElementById('playing').currentTime")
        Thread.sleep(150)
        assertEquals(pausedTime, evaluate("document.getElementById('playing').currentTime"))
        evaluate(MediaPlaybackScript.build(true))
        await("!document.getElementById('playing').paused")
        await("document.getElementById('playing').currentTime !== $pausedTime")
        assertEquals("true", evaluate("document.getElementById('manual').paused"))
    }

    @Test
    fun blocksLateAutoplayUntilPresentation() {
        evaluate(MediaPlaybackScript.build(false))
        evaluate(
            """
            window.late = document.getElementById('playing').cloneNode();
            late.id = 'late';
            late.autoplay = true;
            document.body.appendChild(late);
            """.trimIndent(),
        )
        await("window.__swMediaPlayback.suspended.has(late) && late.paused")
        evaluate(MediaPlaybackScript.build(true))
        await("!late.paused && late.currentTime > 0")
    }

    @Test
    fun doesNotResumeRemovedOrManuallyPausedMedia() {
        evaluate(MediaPlaybackScript.build(true))
        evaluate("document.getElementById('playing').play()")
        await("document.getElementById('playing').currentTime > 0")
        evaluate("document.getElementById('playing').pause()")
        evaluate(MediaPlaybackScript.build(false))
        evaluate(MediaPlaybackScript.build(true))
        assertEquals("true", evaluate("document.getElementById('playing').paused"))

        evaluate("document.getElementById('playing').play()")
        await("!document.getElementById('playing').paused")
        evaluate(MediaPlaybackScript.build(false))
        evaluate("window.removed = document.getElementById('playing'); removed.remove()")
        evaluate(MediaPlaybackScript.build(true))
        assertEquals("true", evaluate("removed.paused"))
        assertEquals("0", evaluate("window.__swMediaPlayback.suspended.size"))
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) {
                result.set(it)
                done.countDown()
            }
        }
        assertTrue("JavaScript callback timed out", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun await(condition: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (evaluate(condition) == "true") return
            Thread.sleep(50)
        }
        assertEquals(condition, "true", evaluate(condition))
    }
}
