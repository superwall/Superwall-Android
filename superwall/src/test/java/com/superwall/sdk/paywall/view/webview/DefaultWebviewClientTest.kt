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
    private val runtime = "https://user-content.superwalleditor.com/runtime/sha/6402ed906d40"

    private fun client(ready: Boolean = false) =
        DefaultWebviewClient(
            forUrl = paywallUrl,
            ioScope = CoroutineScope(Dispatchers.Unconfined),
            isPaywallReady = { ready },
        )

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
    fun `a missing font under the runtime path is a resource error once the paywall is ready`() =
        Given("a ready paywall whose font has no file") {
            val fontUrl = "$runtime/store/document/undefined"

            val event = When("the font request gets a 404") { client(ready = true).eventFor404(request(fontUrl)) }

            Then("it is reported against the font and does not fail the page") {
                assertTrue(event is WebviewClientEvent.OnResourceError)
                val error = (event as WebviewClientEvent.OnResourceError).webviewError
                assertEquals(fontUrl, (error as WebviewError.NetworkError).url)
            }
        }

    @Test
    fun `any runtime request that fails before the paywall is ready fails the page`() =
        Given("a paywall that is not ready") {
            val events =
                When("requests under the runtime path get a 404") {
                    listOf(
                        "$runtime/store/document/undefined",
                        "https://content.superwalleditor.com/runtime",
                    ).map { client().eventFor404(request(it)) }
                }

            Then("the page has failed") {
                assertTrue(events.all { it is WebviewClientEvent.OnError })
            }
        }

    @Test
    fun `a missing runtime script fails a page that is not ready`() =
        Given("a paywall whose runtime bundle is missing") {
            val event =
                When("the script request gets a 404 before the paywall is ready") {
                    client().eventFor404(request("$runtime/entrypoint.js?v=1"))
                }

            Then("the page has failed") {
                assertTrue(event is WebviewClientEvent.OnError)
            }
        }

    @Test
    fun `a runtime script that fails after the paywall is ready is a resource error`() =
        Given("a paywall that is ready") {
            val event =
                When("a script request gets a 404") {
                    client(ready = true).eventFor404(request("$runtime/chunk.js"))
                }

            Then("it does not fail the page") {
                assertTrue(event is WebviewClientEvent.OnResourceError)
            }
        }

    @Test
    fun `a missing third party script is a resource error`() =
        Given("a paywall with an analytics script that is blocked") {
            val event =
                When("the script request fails before the paywall is ready") {
                    client().eventFor404(request("https://cdn.analytics.example/tag.js"))
                }

            Then("it does not fail the page") {
                assertTrue(event is WebviewClientEvent.OnResourceError)
            }
        }

    @Test
    fun `a missing runtime script on the content host fails a page that is not ready`() =
        Given("a paywall whose runtime is served from content.superwalleditor.com") {
            val event =
                When("the runtime request gets a 404 before the paywall is ready") {
                    client().eventFor404(request("https://content.superwalleditor.com/runtime/entrypoint.js"))
                }

            Then("the page has failed") {
                assertTrue(event is WebviewClientEvent.OnError)
            }
        }

    @Test
    fun `a missing request on a Superwall host that is not the runtime is a resource error`() =
        Given("a paywall that loads other files from Superwall hosts") {
            val urls =
                listOf(
                    "https://templates.superwall.com/runtime/entrypoint.js",
                    "https://content.superwalleditor.com/assets/widget.js",
                    "https://superwalleditor.com.example.org/runtime/entrypoint.js",
                    "https://notsuperwalleditor.com/runtime/entrypoint.js",
                )

            val events =
                When("each request fails before the paywall is ready") {
                    urls.map { client().eventFor404(request(it)) }
                }

            Then("none of them fails the page") {
                assertTrue(events.all { it is WebviewClientEvent.OnResourceError })
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
