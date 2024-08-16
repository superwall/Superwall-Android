package com.superwall.sdk.paywall.presentation.rule_logic.vc.webview

import And
import Given
import Then
import When
import android.util.Log
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallWebviewUrl
import com.superwall.sdk.paywall.vc.web_view.DefaultWebviewClient
import com.superwall.sdk.paywall.vc.web_view.SWWebView
import com.superwall.sdk.paywall.vc.web_view.WebviewClientEvent
import com.superwall.sdk.paywall.vc.web_view.WebviewClientEvent.OnPageFinished
import com.superwall.sdk.paywall.vc.web_view.WebviewError
import com.superwall.sdk.paywall.vc.web_view.messaging.PaywallMessageHandler
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class WebviewFallbackClientTest {
    private suspend fun WebView.clientEvents(mainScope: CoroutineScope) =
        mainScope
            .async {
                (webViewClient as DefaultWebviewClient)
            }.await()
            .webviewClientEvents

    private suspend fun WebView.waitForEvent(
        mainScope: CoroutineScope,
        check: (WebviewClientEvent) -> Boolean,
    ) = clientEvents(mainScope).first(check)

    private val mainScope = CoroutineScope(Dispatchers.Main)

    private fun createPaywallConfig(
        vararg configs: PaywallWebviewUrl,
        maxAttemps: Int = configs.size,
    ) = Paywall.stub().let {
        it.copy(
            urlConfig =
                it.urlConfig!!.copy(
                    maxAttempts = maxAttemps,
                    endpoints = configs.toList(),
                ),
        )
    }

    @Test
    fun test_successful_loading() =
        runTest(timeout = 60.seconds) {
            val handler =
                PaywallMessageHandler(
                    mockk<SessionEventsManager>(),
                    mockk<VariablesFactory>(),
                    this,
                )
            val paywall =
                createPaywallConfig(
                    PaywallWebviewUrl(
                        url = "https://www.google.com",
                        score = 10,
                        timeout = 1000,
                    ),
                )
            val context = InstrumentationRegistry.getInstrumentation().context
            val webview =
                mainScope
                    .async {
                        SWWebView(context, handler)
                    }.await()
            Given("We have list of paywall URLS") {
                When("we try to load one") {
                    mainScope.launch {
                        webview.loadPaywallWithFallbackUrl(paywall)
                    }
                    Then("the loading is successful") {
                        webview
                            .waitForEvent(mainScope) {
                                it is OnPageFinished
                            }.let {
                                assert(it is OnPageFinished)
                            }
                    }
                }
            }
        }

    @Test
    fun test_fail_loading_when_timeout_0() =
        runTest(timeout = 60.seconds) {
            val handler =
                PaywallMessageHandler(
                    mockk<SessionEventsManager>(),
                    mockk<VariablesFactory>(),
                    this,
                )
            val paywall =
                createPaywallConfig(
                    PaywallWebviewUrl(
                        url = "https://www.google.com",
                        score = 10,
                        timeout = 0,
                    ),
                )
            val context = InstrumentationRegistry.getInstrumentation().context
            val webview =
                mainScope
                    .async {
                        SWWebView(context, handler)
                    }.await()
            Given("We have list of paywall URLS") {
                When("we try to load one with timeout 0") {
                    mainScope.launch {
                        webview.loadPaywallWithFallbackUrl(paywall)
                    }
                    Then("the loading fails with an `AllUrlsFailed` error") {
                        val event =
                            webview.waitForEvent(mainScope) {
                                it is WebviewClientEvent.OnError && it.webviewError is WebviewError.AllUrlsFailed
                            } as WebviewClientEvent.OnError
                        val error = event.webviewError as WebviewError.AllUrlsFailed
                        assert(error.urls.containsAll(paywall.urlConfig!!.endpoints.map { it.url }))
                    }
                }
            }
        }

    @Test
    fun test_failed_loading_falls_back_to_next() =
        runTest(timeout = 60.seconds) {
            val handler =
                PaywallMessageHandler(
                    mockk<SessionEventsManager>(),
                    mockk<VariablesFactory>(),
                    this,
                )
            val paywall =
                createPaywallConfig(
                    PaywallWebviewUrl(
                        url = "https://www.this-url-doesnt-exist-so-test-fails.com",
                        timeout = 100,
                        score = 100,
                    ),
                    PaywallWebviewUrl(
                        url = "https://www.wikipedia.org",
                        timeout = 500,
                        score = 10,
                    ),
                )
            val context = InstrumentationRegistry.getInstrumentation().context
            val webview =
                mainScope
                    .async {
                        SWWebView(context, handler)
                    }.await()

            Given("We have list of paywall URLS where the first one fails") {
                When("we try to load one") {
                    mainScope.launch {
                        webview.loadPaywallWithFallbackUrl(paywall)
                    }
                    Then("the loading fails and the next one is loaded") {
                        val events =
                            webview
                                .clientEvents(mainScope)
                                .take(4)
                                .toList()
                        assert(events[0] is WebviewClientEvent.OnError)
                        assert(events.count { it is OnPageFinished && it.url.contains("wiki") } == 1)
                    }
                }
            }
        }

    @Test
    fun test_failed_loading_until_last_with_score_0() =
        runTest {
            val handler =
                PaywallMessageHandler(
                    mockk<SessionEventsManager>(),
                    mockk<VariablesFactory>(),
                    this,
                )
            val paywall =
                createPaywallConfig(
                    PaywallWebviewUrl(
                        url = "https://www.this-url-doesnt-exist-so-test-fails.com",
                        timeout = 1,
                        score = 100,
                    ),
                    PaywallWebviewUrl(
                        url = "https://www.this-url-doesnt-exist-so-test-fails-too.com",
                        timeout = 500,
                        score = 10,
                    ),
                    PaywallWebviewUrl(
                        url = "https://www.wikipedia.org",
                        timeout = 500,
                        score = 0,
                    ),
                )
            val context = InstrumentationRegistry.getInstrumentation().context
            val webview =
                mainScope
                    .async {
                        SWWebView(context, handler)
                    }.await()
            Given("We have list of paywall URLS where the first two fail") {
                When("we try to load the paywall") {
                    mainScope.launch {
                        webview.loadPaywallWithFallbackUrl(paywall)
                    }
                    Then("the loading fails until the last one") {
                        val events =
                            webview
                                .clientEvents(mainScope)
                                .take(7)
                                .toList()
                        And("the last one is the one with score 0") {
                            val last = events.filterIsInstance<OnPageFinished>().last()
                            assert(last.url.contains("wiki"))
                        }
                    }
                }
            }
        }

    @Test
    fun test_failed_loading_all() =
        runTest {
            val handler =
                PaywallMessageHandler(
                    mockk<SessionEventsManager>(),
                    mockk<VariablesFactory>(),
                    this,
                )
            val paywall =
                createPaywallConfig(
                    PaywallWebviewUrl(
                        url = "https://www.this-url-doesnt-exist-so-test-fails.com",
                        timeout = 1,
                        score = 100,
                    ),
                    PaywallWebviewUrl(
                        url = "https://www.this-url-doesnt-exist-so-test-fails-too.com",
                        timeout = 500,
                        score = 10,
                    ),
                    PaywallWebviewUrl(
                        url = "https://www.this-url-doesnt-exist-so-test-fails-third.com",
                        timeout = 500,
                        score = 0,
                    ),
                )
            val context = InstrumentationRegistry.getInstrumentation().context
            val webview =
                mainScope
                    .async {
                        SWWebView(context, handler)
                    }.await()
            Given("We have list of paywall URLS where the first two fail") {
                When("we try to load the paywall") {
                    mainScope.launch {
                        webview.loadPaywallWithFallbackUrl(paywall)
                    }
                    Then("the loading fails with AllUrlsFailed") {
                        val event =
                            webview
                                .clientEvents(mainScope)
                                .first {
                                    it is WebviewClientEvent.OnError && it.webviewError is WebviewError.AllUrlsFailed
                                } as WebviewClientEvent.OnError
                        val error = event.webviewError as WebviewError.AllUrlsFailed
                        assert(error.urls.containsAll(paywall.urlConfig!!.endpoints.map { it.url }))
                    }
                }
            }
        }

    private fun failingUrl(
        index: Int = 0,
        score: Int = 10,
    ) = PaywallWebviewUrl(
        url = "https://www.this-url-doesnt-exist-$index.com/",
        score = score,
        timeout = 0,
    )

    @Test
    fun test_fail_loading_when_max_attempts_breached() =
        runTest(timeout = 60.seconds) {
            val handler =
                PaywallMessageHandler(
                    mockk<SessionEventsManager>(),
                    mockk<VariablesFactory>(),
                    this,
                )
            val paywall =
                createPaywallConfig(
                    *(
                        (0 until 3).map { failingUrl(it) } +
                            (0 until 3).map { failingUrl(it + 3, 0) }
                    ).toTypedArray(),
                    maxAttemps = 3,
                )
            val context = InstrumentationRegistry.getInstrumentation().context
            val webview =
                mainScope
                    .async {
                        SWWebView(context, handler)
                    }.await()
            Given("We have list of paywall URLS") {
                When("we try to load them") {
                    mainScope.launch {
                        webview.loadPaywallWithFallbackUrl(paywall)
                    }
                    Then("we reach max attempts") {
                        val event =
                            webview.waitForEvent(mainScope) {
                                it is WebviewClientEvent.OnError && it.webviewError is WebviewError.MaxAttemptsReached
                            } as WebviewClientEvent.OnError
                        val error = event.webviewError as WebviewError.MaxAttemptsReached
                        Log.e("WebviewFallbackClientTest", "errorUrls: ${error.urls}")
                        assert(error.urls.size == paywall.urlConfig?.maxAttempts)
                    }
                }
            }
        }
}
