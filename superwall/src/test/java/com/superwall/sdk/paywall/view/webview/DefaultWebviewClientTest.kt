package com.superwall.sdk.paywall.view.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultWebviewClientTest {
    private val paywallUrl = "https://templates.superwall.com/paywall"
    private val runtime = "https://cdn.superwall.com/runtime/sha/6402ed906d40"

    private fun client() = DefaultWebviewClient(forUrl = paywallUrl, ioScope = CoroutineScope(Dispatchers.Unconfined))

    private fun request(
        url: String,
        mainFrame: Boolean = false,
    ): WebResourceRequest =
        mockk {
            every { isForMainFrame } returns mainFrame
            every { this@mockk.url } returns Uri.parse(url)
        }

    private fun DefaultWebviewClient.eventFor404(request: WebResourceRequest): WebviewClientEvent {
        onReceivedHttpError(null, request, null)
        return webviewClientEvents.replayCache.last()
    }

    @Test
    fun `a missing font under the runtime path is a resource error`() =
        Given("a paywall whose font has no file") {
            val fontUrl = "$runtime/store/document/undefined"

            val event = When("the font request gets a 404") { client().eventFor404(request(fontUrl)) }

            Then("it is reported against the font and does not fail the page") {
                assertTrue(event is WebviewClientEvent.OnResourceError)
                val error = (event as WebviewClientEvent.OnResourceError).webviewError
                assertEquals(fontUrl, (error as WebviewError.NetworkError).url)
            }
        }

    @Test
    fun `a missing script is a resource error wherever it is served from`() =
        Given("a paywall whose script is missing") {
            val event =
                When("the script request gets a 404") {
                    client().eventFor404(request("$runtime/entrypoint.js?v=1"))
                }

            Then("it does not fail the page") {
                assertTrue(event is WebviewClientEvent.OnResourceError)
            }
        }

    @Test
    fun `a main frame failure fails the page`() =
        Given("a paywall whose page is missing") {
            val event =
                When("the page request gets a 404") {
                    client().eventFor404(request(paywallUrl, mainFrame = true))
                }

            Then("the page has failed") {
                assertTrue(event is WebviewClientEvent.OnError)
            }
        }
}
