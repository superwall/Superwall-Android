package com.example.superapp.utils

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebView
import androidx.core.view.children
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.screenshot.Screenshot
import com.superwall.sdk.Superwall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ScreenSettle"

/**
 * Waits until the screen has stopped changing and returns that frame: [stableFrames] captures in a
 * row, [interval] apart, that are identical below the status bar.
 *
 * This replaces sleeping for a guessed time before a screenshot. A slow device waits longer, a
 * fast one doesn't wait at all. A screen that never settles (a video, a spinner) gives up after
 * [timeout] and returns the last frame, which is what a fixed sleep would have captured.
 *
 * The waiting runs on [Dispatchers.Default] because tests run inside `runTest`, where a plain
 * `delay` is skipped.
 */
suspend fun awaitScreenSettled(
    timeout: Duration = 5.seconds,
    interval: Duration = 150.milliseconds,
    stableFrames: Int = 3,
): Bitmap =
    withContext(Dispatchers.Default) {
        val ignoredTop = statusBarHeightPx()
        var last = Screenshot.capture().bitmap
        var same = 1
        val settled =
            withTimeoutOrNull(timeout) {
                while (same < stableFrames) {
                    delay(interval)
                    val next = Screenshot.capture().bitmap
                    same = if (next.sameBelow(last, ignoredTop)) same + 1 else 1
                    last = next
                }
                true
            }
        if (settled == null) Log.w(TAG, "Screen was still changing after $timeout; using the last frame")
        last
    }

/**
 * Waits until the presented paywall's page has finished loading what it draws: the document, its
 * fonts and its images. `paywallWebviewLoad_complete` fires before web fonts and images are in,
 * which is where a screenshot taken straight after it goes wrong.
 *
 * Returns false when no paywall is showing or the page didn't get there in [timeout].
 */
suspend fun awaitPaywallContentReady(timeout: Duration = 10.seconds): Boolean =
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeout) {
            while (!paywallContentReady()) delay(100.milliseconds)
            true
        } ?: false.also { Log.w(TAG, "Paywall content wasn't ready after $timeout") }
    }

private const val CONTENT_READY_JS =
    "(function(){try{return document.readyState==='complete'" +
        "&&(!document.fonts||document.fonts.status==='loaded')" +
        "&&Array.prototype.every.call(document.images,function(i){return i.complete;});}" +
        "catch(e){return false;}})()"

private suspend fun paywallContentReady(): Boolean {
    val result = CompletableDeferred<Boolean>()
    getInstrumentation().runOnMainSync {
        val webView =
            Superwall.instance.paywallView
                ?.children
                ?.firstOrNull { it is WebView } as? WebView
        if (webView == null) {
            result.complete(false)
        } else {
            webView.evaluateJavascript(CONTENT_READY_JS) { result.complete(it == "true") }
        }
    }
    return withTimeoutOrNull(2.seconds) { result.await() } ?: false
}

/** Height of the status bar in pixels on this device. Its clock and icons change between runs. */
fun statusBarHeightPx(): Int {
    val resources = getInstrumentation().targetContext.resources
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

private fun Bitmap.sameBelow(
    other: Bitmap,
    top: Int,
): Boolean {
    if (width != other.width || height != other.height) return false
    if (top <= 0 || top >= height) return sameAs(other)
    val rows = height - top
    val a = IntArray(width * rows)
    val b = IntArray(width * rows)
    getPixels(a, 0, width, 0, top, width, rows)
    other.getPixels(b, 0, width, 0, top, width, rows)
    return a.contentEquals(b)
}
