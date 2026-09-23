package com.superwall.sdk.customercenter

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Path
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Screen
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Support
import com.superwall.sdk.customercenter.CustomerCenterFixtures.customerInfo
import com.superwall.sdk.customercenter.CustomerCenterFixtures.entitlement
import com.superwall.sdk.customercenter.CustomerCenterFixtures.subscription
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.product.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale

class CustomerCenterViewModelTest {
    private class Harness(
        val testScope: TestScope,
        info: CustomerInfo,
        configuration: CustomerCenterConfiguration = CustomerCenterConfiguration.default,
        products: FakeProducts = FakeProducts(),
        environment: FakeEnvironment = FakeEnvironment(),
        callbacks: CustomerCenterCallbacks = CustomerCenterCallbacks(),
    ) {
        val customerInfo = FakeCustomerInfo(info)
        val products = products
        val restorer = FakeRestorer()
        val opener = FakeOpener()
        val tracker = FakeTracker()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler) + SupervisorJob())
        val viewModel =
            CustomerCenterViewModel(
                configuration = configuration,
                dependencies = CustomerCenterDependencies(customerInfo, products, restorer, opener, tracker, environment),
                strings = CustomerCenterStrings.english,
                scope = scope,
                callbacks = callbacks,
                minimumRestoreDurationMs = 0,
            )

        inline fun <reified T> events() = tracker.events.filterIsInstance<T>()

        fun state() = viewModel.state.value

        fun close() = scope.cancel()
    }

    private fun harness(
        info: CustomerInfo,
        configuration: CustomerCenterConfiguration = CustomerCenterConfiguration.default,
        products: FakeProducts = FakeProducts(),
        environment: FakeEnvironment = FakeEnvironment(),
        callbacks: CustomerCenterCallbacks = CustomerCenterCallbacks(),
        block: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        val harness = Harness(this, info, configuration, products, environment, callbacks)
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `loading a customer with purchases shows the management screen and tracks open once`() =
        Given("a customer with an active subscription") {
            harness(customerInfo(subscriptions = listOf(subscription()))) { h ->
                When("the screen loads twice") {
                    h.viewModel.load()
                    h.viewModel.load()
                }
                Then("it shows the management screen") {
                    assertEquals(CustomerCenterScreenState.MANAGEMENT, h.state().screen)
                    assertEquals(1, h.state().purchases.size)
                }
                Then("open is tracked once, for the management screen") {
                    val opens = h.events<InternalSuperwallEvent.CustomerCenterOpen>()
                    assertEquals(1, opens.size)
                    assertEquals(CustomerCenterScreenType.MANAGEMENT, opens.single().screen)
                    assertEquals(mapOf("screen" to "management", "presentation" to "sheet"), opens.single().getSuperwallParameters())
                }
            }
        }

    @Test
    fun `a customer with nothing sees the no-purchases screen`() =
        Given("a customer with no purchases") {
            harness(customerInfo()) { h ->
                When("the screen loads") { h.viewModel.load() }
                Then("it shows the no-purchases screen offering restore") {
                    assertEquals(CustomerCenterScreenState.NO_PURCHASES, h.state().screen)
                    assertEquals(listOf("restore"), h.viewModel.paths(null).map { it.id })
                    assertEquals(CustomerCenterScreenType.NO_PURCHASES, h.events<InternalSuperwallEvent.CustomerCenterOpen>().single().screen)
                }
            }
        }

    @Test
    fun `a path with a survey asks first, then acts`() =
        Given("an active Play subscription and the default configuration") {
            val selected = mutableListOf<Pair<CustomerCenterAction, String>>()
            val answered = mutableListOf<String>()
            val callbacks =
                CustomerCenterCallbacks(
                    didSelectAction = { action, pathId, _ -> selected.add(action to pathId) },
                    didCompleteSurvey = { _, optionId, _, _ -> answered.add(optionId) },
                )
            harness(customerInfo(subscriptions = listOf(subscription("monthly:base"))), callbacks = callbacks) { h ->
                h.viewModel.load()
                val purchase = h.state().purchases.single()
                val manage = h.viewModel.paths(purchase, isScreenLevel = false).first { it.id == "manage_subscription" }

                When("the manage path is tapped") { h.viewModel.select(manage, purchase) }
                Then("the survey shows and nothing opens yet") {
                    assertEquals(CustomerCenterSheet.Survey("manage_subscription"), h.state().sheet)
                    assertTrue(h.opener.opened.isEmpty())
                    assertEquals(listOf(CustomerCenterAction.ManageSubscription to "manage_subscription"), selected)
                    assertEquals("manage_subscription", h.events<InternalSuperwallEvent.CustomerCenterAction>().single().pathId)
                }

                When("the survey is answered") { h.viewModel.answerSurvey("too_expensive") }
                Then("the answer is reported and Google Play's subscription page opens") {
                    assertEquals(listOf("too_expensive"), answered)
                    val response = h.events<InternalSuperwallEvent.CustomerCenterSurveyResponse>().single()
                    assertEquals("cancel_survey", response.surveyId)
                    assertEquals("monthly:base", response.productId)
                    assertNull(h.state().sheet)
                    assertEquals(
                        "https://play.google.com/store/account/subscriptions?sku=monthly&package=com.example.app" to false,
                        h.opener.opened.single(),
                    )
                }

                When("the customer comes back") {
                    h.viewModel.onResume()
                    h.viewModel.onResume()
                }
                Then("purchases are reloaded once") { assertEquals(1, h.customerInfo.refreshCount) }
            }
        }

    @Test
    fun `cancelling a survey drops the action`() =
        Given("a pending survey") {
            harness(customerInfo(subscriptions = listOf(subscription()))) { h ->
                h.viewModel.load()
                val purchase = h.state().purchases.single()
                h.viewModel.select(h.viewModel.paths(purchase, false).first { it.id == "manage_subscription" }, purchase)
                When("it is cancelled") { h.viewModel.sheetDismissed() }
                Then("nothing opens and answering later does nothing") {
                    h.viewModel.answerSurvey("dont_use")
                    assertNull(h.state().sheet)
                    assertTrue(h.opener.opened.isEmpty())
                    assertTrue(h.events<InternalSuperwallEvent.CustomerCenterSurveyResponse>().isEmpty())
                }
            }
        }

    @Test
    fun `restore can be vetoed`() =
        Given("a host that declines restores") {
            harness(customerInfo(), callbacks = CustomerCenterCallbacks(shouldRestore = { false })) { h ->
                When("restore runs") { h.viewModel.performRestore() }
                Then("nothing is restored") {
                    assertEquals(0, h.restorer.count)
                    assertEquals(CustomerCenterRestoreState.IDLE, h.state().restoreState)
                }
            }
        }

    @Test
    fun `restore reports whether it found anything`() =
        Given("a customer with no purchases") {
            harness(customerInfo()) { h ->
                When("restore finds nothing") { h.viewModel.performRestore() }
                Then("it says so") { assertEquals(CustomerCenterRestoreState.NOT_FOUND, h.state().restoreState) }

                When("restore brings a subscription back") {
                    h.viewModel.restoreAlertDismissed()
                    h.customerInfo.info = customerInfo(subscriptions = listOf(subscription()))
                    h.viewModel.performRestore()
                }
                Then("it reports success and shows the purchase") {
                    assertEquals(CustomerCenterRestoreState.RESTORED, h.state().restoreState)
                    assertEquals(CustomerCenterScreenState.MANAGEMENT, h.state().screen)
                }

                When("restore fails outright") {
                    h.restorer.result = RestorationResult.Failed(null)
                    h.viewModel.performRestore()
                }
                Then("it reports nothing found") { assertEquals(CustomerCenterRestoreState.NOT_FOUND, h.state().restoreState) }
            }
        }

    @Test
    fun `refund hands off to Google Play and reports the outcome`() =
        Given("an active Play subscription") {
            val refunds = mutableListOf<Pair<String, CustomerCenterRefundStatus>>()
            val callbacks = CustomerCenterCallbacks(didCompleteRefund = { id, status -> refunds.add(id to status) })
            harness(customerInfo(subscriptions = listOf(subscription())), callbacks = callbacks) { h ->
                h.viewModel.load()
                val purchase = h.state().purchases.single()
                val refund = h.viewModel.paths(purchase, false).first { it.id == "refund" }

                When("refund is tapped") { h.viewModel.select(refund, purchase) }
                Then("Google Play's order history opens and success is reported") {
                    assertEquals(PlayStoreLinks.ORDER_HISTORY, h.opener.opened.single().first)
                    assertEquals(listOf("monthly" to CustomerCenterRefundStatus.SUCCESS), refunds)
                    assertEquals(
                        mapOf("product_id" to "monthly", "status" to "success"),
                        h.events<InternalSuperwallEvent.CustomerCenterRefundRequest>().single().getSuperwallParameters(),
                    )
                }

                When("the page can't be opened") {
                    h.opener.opens = false
                    h.viewModel.select(refund, purchase)
                }
                Then("an error is reported and shown on the card") {
                    assertEquals(CustomerCenterRefundStatus.ERROR, refunds.last().second)
                    assertEquals("monthly" to CustomerCenterRefundStatus.ERROR, h.state().refundResult)
                }
            }
        }

    @Test
    fun `contact support falls back to showing the address`() =
        Given("a support email and no mail app") {
            val config = CustomerCenterConfiguration.default.copy(support = Support(email = "help@app.com"))
            harness(customerInfo(subscriptions = listOf(subscription())), configuration = config) { h ->
                h.viewModel.load()
                h.opener.opens = false
                val support = h.viewModel.paths(null).first { it.id == "contact_support" }
                When("contact support is tapped") { h.viewModel.select(support, null) }
                Then("the address is shown") {
                    assertTrue(h.opener.opened.single().first.startsWith("mailto:help@app.com?"))
                    assertEquals(CustomerCenterSheet.NoMailApp("help@app.com"), h.state().sheet)
                }
            }
        }

    @Test
    fun `update banner shows for an older install until dismissed`() =
        Given("a configured latest version newer than the install") {
            val config = CustomerCenterConfiguration.default.copy(support = Support(latestAppVersion = "1.1.0"))
            harness(customerInfo(subscriptions = listOf(subscription())), configuration = config) { h ->
                When("the screen loads") { h.viewModel.load() }
                Then("the banner shows") { assertTrue(h.state().showsUpdateBanner) }
                When("the customer continues") { h.viewModel.continueAfterUpdateWarning() }
                Then("it stays hidden, even after a reload") {
                    h.viewModel.load()
                    assertFalse(h.state().showsUpdateBanner)
                }
            }
        }

    @Test
    fun `duplicate banner shows for Play and web subscriptions at once`() =
        Given("an active Play and an active Stripe subscription") {
            val info = customerInfo(subscriptions = listOf(subscription("play"), subscription("web", store = Store.STRIPE)))
            harness(info) { h ->
                When("the screen loads") { h.viewModel.load() }
                Then("the duplicate banner shows") { assertTrue(h.state().showsDuplicateBanner) }
            }
        }

    @Test
    fun `web products fill in from the catalogue after the first draw`() =
        Given("a Stripe subscription Google Play can't describe") {
            val products = FakeProducts(catalogue = mapOf("web" to ProductDisplayInfo("web", title = "Web Pro", localizedPrice = "$9.99")))
            val info =
                customerInfo(
                    subscriptions = listOf(subscription("web", store = Store.STRIPE)),
                    entitlements = listOf(entitlement("pro", productIds = setOf("web"))),
                )
            harness(info, products = products) { h ->
                When("the screen loads") {
                    h.viewModel.load()
                    advanceUntilIdle()
                }
                Then("the catalogue is asked for just that product and its name is shown") {
                    assertEquals(listOf(setOf("web")), products.catalogueRequests)
                    val purchase = h.state().purchases.single()
                    assertEquals("Web Pro", purchase.title)
                    assertFalse(purchase.isAwaitingCatalogue)
                }
            }
        }

    @Test
    fun `customer info updates re-render the screen`() =
        Given("a loaded customer with nothing") {
            harness(customerInfo()) { h ->
                h.viewModel.load()
                When("a subscription arrives") {
                    h.customerInfo.updates.emit(customerInfo(subscriptions = listOf(subscription())))
                }
                Then("the management screen shows") { assertEquals(CustomerCenterScreenState.MANAGEMENT, h.state().screen) }
            }
        }

    @Test
    fun `only one path runs at a time`() =
        Given("a loaded screen") {
            harness(customerInfo(), callbacks = CustomerCenterCallbacks(shouldRestore = { kotlinx.coroutines.awaitCancellation() })) { h ->
                h.viewModel.load()
                val restore = h.viewModel.paths(null).single()
                When("restore is tapped twice while the first is waiting") {
                    h.viewModel.onPathTapped(restore, null)
                    h.viewModel.onPathTapped(restore, null)
                }
                Then("the action is tracked once and the row is busy") {
                    assertEquals(1, h.events<InternalSuperwallEvent.CustomerCenterAction>().size)
                    assertEquals("restore", h.state().busyPathId)
                }
            }
        }

    @Test
    fun `dismissal is reported once`() =
        Given("a presented Customer Center") {
            var dismissals = 0
            harness(customerInfo(), callbacks = CustomerCenterCallbacks(didDismiss = { dismissals += 1 })) { h ->
                When("it's dismissed twice") {
                    h.viewModel.dismiss()
                    h.viewModel.dismiss()
                }
                Then("the host hears once and close is tracked once") {
                    assertEquals(1, dismissals)
                    assertEquals(1, h.events<InternalSuperwallEvent.CustomerCenterClose>().size)
                }
            }
        }

    @Test
    fun `only the first restore answer from a delegate counts`() =
        Given("a delegate that answers twice") {
            val delegate =
                object : CustomerCenterDelegate {
                    override fun customerCenterShouldRestorePurchases(proceed: (Boolean) -> Unit) {
                        proceed(false)
                        proceed(true)
                    }
                }
            runTest {
                val answer = When("asked") { CustomerCenterCallbacks.from(delegate).shouldRestore!!.invoke() }
                Then("the first answer wins without crashing") { assertFalse(answer) }
            }
        }

    @Test
    fun `detail screens without actions explain why`() =
        Given("an App Store subscription on Android") {
            val config =
                CustomerCenterConfiguration.default.copy(
                    managementScreen = Screen(paths = listOf(Path.restore(), Path.manageSubscription())),
                )
            harness(customerInfo(subscriptions = listOf(subscription(store = Store.APP_STORE))), configuration = config) { h ->
                h.viewModel.load()
                val purchase = h.state().purchases.single()
                Then("the detail screen says to manage it through the App Store") {
                    assertEquals(
                        DetailEmptyState.ManagedElsewhere("customer_center_store_app_store"),
                        h.viewModel.detailEmptyState(purchase),
                    )
                }
            }
        }
}
