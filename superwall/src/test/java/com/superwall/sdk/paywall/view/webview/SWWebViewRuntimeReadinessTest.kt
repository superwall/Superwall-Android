package com.superwall.sdk.paywall.view.webview

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.options.PaywallOptions
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallWebviewUrl
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * How [SWWebView] feeds runtime readiness to its client: which failed runtime requests the
 * client treats as the page failing. The rules themselves are in [PaywallRuntimeReadinessTest].
 */
@RunWith(RobolectricTestRunner::class)
class SWWebViewRuntimeReadinessTest {
    private val firstUrl = "https://templates.superwall.com/first"
    private val secondUrl = "https://templates.superwall.com/second"
    private val runtimeScript = "https://content.superwalleditor.com/runtime/entrypoint.js"

    // A code no other error in these tests uses, to tell the runtime failure's event apart.
    private val mainFrameErrorCode = -99

    private fun request(
        url: String,
        mainFrame: Boolean = false,
    ): WebResourceRequest =
        mockk {
            every { isForMainFrame } returns mainFrame
            every { this@mockk.url } returns Uri.parse(url)
        }

    private val paywall =
        Paywall.stub().copy(
            urlConfig =
                PaywallWebviewUrl.Config(
                    maxAttempts = 3,
                    endpoints =
                        listOf(
                            PaywallWebviewUrl(firstUrl, timeout = 60_000, score = 100),
                            PaywallWebviewUrl(secondUrl, timeout = 60_000, score = 0),
                        ),
                ),
        )

    // The view's scopes run on real dispatchers, so wait for their work to arrive.
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the web view" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** Fails a runtime request and returns whether the client took it as the page failing. */
    private fun WebviewFallbackClient.runtimeRequestFailsThePage(webView: SWWebView): Boolean {
        val before = webviewClientEvents.replayCache.lastOrNull()
        onReceivedHttpError(webView, request(runtimeScript), null)
        awaitUntil { webviewClientEvents.replayCache.lastOrNull() !== before }
        return webviewClientEvents.replayCache.last() is WebviewClientEvent.OnError
    }

    private fun withLoadingWebView(block: (SWWebView, WebviewFallbackClient, () -> Int) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val webView =
            testSWWebView(
                controller.get(),
                options = PaywallOptions().apply { optimisticLoading = true },
            )
        var readyStates = 0
        // PaywallView tells the web view about every Ready loading state.
        webView.delegate =
            mockk(relaxed = true) {
                every { updateState(any()) } answers {
                    val update = firstArg<PaywallViewState.Updates>()
                    if (update is PaywallViewState.Updates.SetLoadingState && update.newState is PaywallLoadingState.Ready) {
                        readyStates += 1
                        webView.onPaywallReady()
                    }
                }
            }
        try {
            webView.loadPaywallWithFallbackUrl(paywall)
            shadowOf(Looper.getMainLooper()).idle()
            block(webView, shadowOf(webView).webViewClient as WebviewFallbackClient) { readyStates }
        } finally {
            controller.pause().stop().destroy()
            webView.destroy()
        }
    }

    @Test
    fun `an optimistic ready does not stop a failed runtime request failing the page`() =
        Given("a paywall loading with optimistic loading") {
            withLoadingWebView { webView, client, readyStates ->
                When("the page finishes and is optimistically marked ready") {
                    client.onPageFinished(webView, firstUrl)
                    awaitUntil { readyStates() == 1 }
                }

                Then("a runtime request that fails still fails the page") {
                    assertTrue(client.runtimeRequestFailsThePage(webView))
                }
            }
        }

    @Test
    fun `a fallback URL starts without the previous page's readiness`() =
        Given("a paywall whose first URL became ready") {
            withLoadingWebView { webView, client, _ ->
                webView.onPaywallReady()
                assertTrue(!client.runtimeRequestFailsThePage(webView))

                When("the page fails and the next URL is loaded") {
                    val error =
                        mockk<WebResourceError>(relaxed = true) {
                            every { errorCode } returns mainFrameErrorCode
                        }
                    client.onReceivedError(webView, request(firstUrl, mainFrame = true), error)
                    awaitUntil { shadowOf(webView).lastLoadedUrl?.startsWith(secondUrl) == true }
                }

                Then("a runtime request that fails on it fails the page") {
                    assertTrue(client.runtimeRequestFailsThePage(webView))
                }
            }
        }

    private fun withDirectlyLoadedWebView(block: (SWWebView, DefaultWebviewClient) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val webView = testSWWebView(controller.get())
        try {
            webView.loadUrl(firstUrl)
            shadowOf(Looper.getMainLooper()).idle()
            block(webView, shadowOf(webView).webViewClient as DefaultWebviewClient)
        } finally {
            controller.pause().stop().destroy()
            webView.destroy()
        }
    }

    /** Fails a runtime request before the paywall is ready and waits for the view to act on it. */
    private fun DefaultWebviewClient.failRuntimeRequest(webView: SWWebView) {
        onReceivedHttpError(webView, request(runtimeScript), null)
        awaitUntil { webviewClientEvents.replayCache.lastOrNull() is WebviewClientEvent.OnError }
        // The view handles the event on the main thread a moment after it is emitted.
        val settled = System.currentTimeMillis() + 300
        awaitUntil { System.currentTimeMillis() >= settled }
    }

    /** Runs past every retry wait and returns whether the view loaded the page again. */
    private fun SWWebView.reloadedAfterTheRetryWait(original: DefaultWebviewClient): Boolean {
        // The main looper's clock only moves when told to, so the retry wait is exact.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        // Each load gets a client of its own.
        return shadowOf(this).webViewClient !== original
    }

    @Test
    fun `a runtime request that fails before the paywall is ready reloads the page`() =
        Given("a paywall that is loading") {
            withDirectlyLoadedWebView { webView, client ->
                When("a runtime request fails and the paywall never becomes ready") {
                    client.failRuntimeRequest(webView)
                }

                Then("the page is loaded again") {
                    assertTrue(webView.reloadedAfterTheRetryWait(client))
                }
            }
        }

    @Test
    fun `a paywall that becomes ready while a reload is waiting is not reloaded`() =
        Given("a paywall with a reload waiting after a runtime request failed") {
            withDirectlyLoadedWebView { webView, client ->
                client.failRuntimeRequest(webView)

                When("the paywall reports that it is ready") {
                    webView.onPaywallReady()
                }

                Then("the working page is left alone") {
                    assertTrue(!webView.reloadedAfterTheRetryWait(client))
                }
            }
        }
}
