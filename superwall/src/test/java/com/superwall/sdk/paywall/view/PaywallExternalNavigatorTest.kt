package com.superwall.sdk.paywall.view

import android.content.ActivityNotFoundException
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.view.ExternalNavigationState.Actions
import com.superwall.sdk.paywall.view.ExternalNavigationState.PaymentSheet
import com.superwall.sdk.paywall.view.ExternalNavigationState.Updates
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallExternalNavigatorTest {
    private val paywall = MutableStateFlow(PaywallViewState(paywall = Paywall.stub(), locale = "en-US"))
    private val calls = mutableListOf<String>()
    private var launch: CheckoutLaunch = CheckoutLaunch.Launched
    private var failWith: Throwable? = null

    private fun navigator() =
        PaywallExternalNavigator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            storage = mockk(relaxed = true),
            tracker = {},
            checkoutLauncher = { url ->
                calls += "checkout:$url"
                failWith?.let { throw it }
                launch
            },
            customTab = { url ->
                failWith?.let { throw it }
                calls += "tab:$url"
            },
            externalBrowser = { url ->
                failWith?.let { throw it }
                calls += "external:$url"
            },
            deepLink = { url ->
                failWith?.let { throw it }
                calls += "deeplink:$url"
            },
            onDeepLinkOpened = { url -> calls += "event:$url" },
            applyPaywallUpdate = { update -> paywall.update(update.transform) },
        )

    @Test
    fun `sheet updates open and close the payment sheet`() {
        Given("a closed payment sheet") {
            val state = ExternalNavigationState()

            Then("opening records the url and closing clears it") {
                val opened = Updates.SheetOpened("https://a").reduce(state)
                assertEquals(PaymentSheet.Open("https://a"), opened.paymentSheet)
                assertEquals(PaymentSheet.Closed, Updates.SheetClosed.reduce(opened).paymentSheet)
            }
        }
    }

    @Test
    fun `opening checkout launches it and leaves the sheet open, at once`() {
        Given("a navigator with a launcher") {
            val navigator = navigator()

            When("web checkout is opened") {
                navigator.dispatch(Actions.OpenPaymentSheet("https://checkout"))

                Then("checkout is launched and the sheet counts as open before dispatch returns") {
                    assertEquals(listOf("checkout:https://checkout"), calls)
                    assertTrue(navigator.isPaymentSheetOpen)
                }
            }
        }
    }

    @Test
    fun `checkout with nothing to launch it closes the sheet again`() {
        Given("no in-window host and no registered launcher") {
            launch = CheckoutLaunch.NoLauncher
            val navigator = navigator()

            When("web checkout is opened") {
                navigator.dispatch(Actions.OpenPaymentSheet("https://checkout"))

                Then("the sheet counts as closed") {
                    assertFalse(navigator.isPaymentSheetOpen)
                }
            }
        }
    }

    @Test
    fun `a checkout that throws keeps the sheet open, as before`() {
        Given("a launcher that throws") {
            failWith = IllegalStateException("boom")
            val navigator = navigator()

            When("web checkout is opened") {
                navigator.dispatch(Actions.OpenPaymentSheet("https://checkout"))

                Then("the error is swallowed and the sheet still counts as open") {
                    assertTrue(navigator.isPaymentSheetOpen)
                }
            }
        }
    }

    @Test
    fun `the checkout tab closing closes the sheet and the browser`() {
        Given("an open checkout sheet and a presented browser") {
            val navigator = navigator()
            navigator.dispatch(Actions.OpenPaymentSheet("https://checkout"))
            paywall.update(PaywallViewState.Updates.SetBrowserPresented(true).transform)

            When("the Custom Tab reports it closed") {
                navigator.dispatch(Actions.CheckoutClosed(0))

                Then("neither counts as presented") {
                    assertFalse(navigator.isPaymentSheetOpen)
                    assertFalse(paywall.value.isBrowserViewPresented)
                }
            }
        }
    }

    @Test
    fun `sheetClosed closes an open sheet`() {
        Given("an open checkout sheet") {
            val navigator = navigator()
            navigator.dispatch(Actions.OpenPaymentSheet("https://checkout"))

            When("the host reports the sheet won't open") {
                navigator.sheetClosed()

                Then("it counts as closed") {
                    assertFalse(navigator.isPaymentSheetOpen)
                }
            }
        }
    }

    @Test
    fun `the in-app browser opens before it is marked presented`() {
        Given("a navigator") {
            val navigator = navigator()

            When("a URL is opened in-app") {
                navigator.dispatch(Actions.OpenInAppBrowser("https://example.com"))

                Then("a Custom Tab opens and the browser counts as presented") {
                    assertEquals(listOf("tab:https://example.com"), calls)
                    assertTrue(paywall.value.isBrowserViewPresented)
                }
            }
        }
    }

    @Test
    fun `an in-app browser that fails to open is not marked presented`() {
        Given("a Custom Tab that throws") {
            failWith = IllegalStateException("no browser")
            val navigator = navigator()

            When("a URL is opened in-app") {
                navigator.dispatch(Actions.OpenInAppBrowser("https://example.com"))

                Then("the error is swallowed and the browser doesn't count as presented") {
                    assertFalse(paywall.value.isBrowserViewPresented)
                }
            }
        }
    }

    @Test
    fun `an invalid url opens nothing`() {
        Given("a navigator") {
            val navigator = navigator()

            When("malformed URLs are opened in each browser") {
                navigator.dispatch(Actions.OpenInAppBrowser("not a url"))
                navigator.dispatch(Actions.OpenExternalBrowser("not a url"))

                Then("no browser opens and nothing is marked presented") {
                    assertTrue(calls.isEmpty())
                    assertFalse(paywall.value.isBrowserViewPresented)
                }
            }
        }
    }

    @Test
    fun `the external browser is marked presented before it opens`() {
        Given("a browser that throws") {
            failWith = IllegalStateException("no browser")
            val navigator = navigator()

            When("a URL is opened externally") {
                navigator.dispatch(Actions.OpenExternalBrowser("https://example.com"))

                Then("the browser already counts as presented, as before") {
                    assertTrue(paywall.value.isBrowserViewPresented)
                }
            }
        }
    }

    @Test
    fun `a deep link is reported then opened`() {
        Given("a navigator") {
            val navigator = navigator()

            When("a deep link is opened") {
                navigator.dispatch(Actions.OpenDeepLink("myapp://home"))

                Then("the event comes first, then the app handles it") {
                    assertEquals(listOf("event:myapp://home", "deeplink:myapp://home"), calls)
                }
            }
        }
    }

    @Test
    fun `a deep link nobody handles is still reported`() {
        Given("no activity for the deep link") {
            failWith = ActivityNotFoundException()
            val navigator = navigator()

            When("a deep link is opened") {
                navigator.dispatch(Actions.OpenDeepLink("myapp://home"))

                Then("the event is reported and the failure swallowed") {
                    assertEquals(listOf("event:myapp://home"), calls)
                }
            }
        }
    }
}
