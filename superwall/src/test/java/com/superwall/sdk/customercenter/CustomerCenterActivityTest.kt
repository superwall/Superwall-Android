package com.superwall.sdk.customercenter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.customercenter.CustomerCenterConfiguration.Support
import com.superwall.sdk.customercenter.CustomerCenterFixtures.customerInfo
import com.superwall.sdk.customercenter.CustomerCenterFixtures.entitlement
import com.superwall.sdk.customercenter.CustomerCenterFixtures.nonSubscription
import com.superwall.sdk.customercenter.CustomerCenterFixtures.subscription
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.product.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CustomerCenterActivityTest {
    private class Host(
        override var session: CustomerCenterManager.Session?,
    ) : CustomerCenterSessionHost {
        var ended = 0

        override fun sessionEnded(ended: CustomerCenterManager.Session) {
            this.ended += 1
            session = null
            ended.viewModel.dismiss()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val originalHost = CustomerCenterActivity.sessionHost

    @After
    fun tearDown() {
        CustomerCenterActivity.sessionHost = originalHost
        scope.cancel()
    }

    private fun launch(
        info: CustomerInfo,
        configuration: CustomerCenterConfiguration = CustomerCenterConfiguration.default,
        products: Map<String, ProductDisplayInfo> = emptyMap(),
        callbacks: CustomerCenterCallbacks = CustomerCenterCallbacks(),
    ): Pair<ActivityController<CustomerCenterActivity>, Host> {
        val viewModel =
            CustomerCenterViewModel(
                configuration = configuration,
                dependencies =
                    CustomerCenterDependencies(
                        FakeCustomerInfo(info),
                        FakeProducts(products),
                        FakeRestorer(),
                        FakeOpener(),
                        FakeTracker(),
                        FakeEnvironment(),
                    ),
                strings = CustomerCenterStrings.english,
                scope = scope,
                callbacks = callbacks,
            )
        val host = Host(CustomerCenterManager.Session(viewModel, ActivityReference(), null, null))
        CustomerCenterActivity.sessionHost = { host }
        val controller = Robolectric.buildActivity(CustomerCenterActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller to host
    }

    private fun View.texts(): List<String> =
        when (this) {
            is TextView -> listOf(text.toString())
            is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
            else -> emptyList()
        }

    private fun View.findByTag(tag: String): View? =
        when {
            this.tag == tag -> this
            this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findByTag(tag) }
            else -> null
        }

    /** Writes the screen to `build/customer-center-screenshots` when asked to, for eyeballing the layout. */
    private fun snapshot(
        controller: ActivityController<CustomerCenterActivity>,
        name: String,
    ) {
        if (System.getProperty("customerCenterScreenshots") == null && System.getenv("CUSTOMER_CENTER_SCREENSHOTS") == null) return
        val root = controller.get().window.decorView
        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/customer-center-screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `management screen lists purchases, actions and account details`() =
        Given("a customer with a Play subscription, a web subscription and a one-off purchase") {
            val info =
                customerInfo(
                    subscriptions =
                        listOf(
                            subscription("monthly"),
                            subscription("web_annual", store = Store.STRIPE, willRenew = false),
                        ),
                    nonSubscriptions = listOf(nonSubscription("coins")),
                    entitlements = listOf(entitlement("pro", productIds = setOf("web_annual"))),
                )
            val products =
                mapOf("monthly" to ProductDisplayInfo("monthly", title = "Pro Monthly", localizedPrice = "$4.99", localizedPeriod = "month"))
            val configuration = CustomerCenterConfiguration.default.copy(support = Support(email = "help@app.com", latestAppVersion = "9.0"))
            val (controller, _) = When("the Customer Center opens") { launch(info, configuration, products) }
            val root = controller.get().window.decorView
            snapshot(controller, "management")
            Then("it shows every purchase and the account-level actions") {
                val texts = root.texts()
                assertTrue(texts.contains("Manage your subscription"))
                assertTrue(texts.contains("Pro Monthly"))
                assertTrue(texts.contains("pro"))
                assertTrue(texts.contains("Update available"))
                assertTrue(texts.contains("Restore purchases"))
                assertTrue(texts.contains("Contact support"))
                assertTrue(texts.contains("User ID"))
                assertNotNull(root.findByTag("customer_center.purchase.coins"))
            }

            When("the Play subscription is opened") {
                root.findByTag("customer_center.purchase.monthly")!!.performClick()
                shadowOf(Looper.getMainLooper()).idle()
            }
            snapshot(controller, "detail")
            Then("its detail screen offers the purchase's own actions") {
                val texts = root.texts()
                assertTrue(texts.contains("Change plan"))
                assertTrue(texts.contains("Request a refund"))
                assertTrue(texts.contains("Cancel subscription"))
                assertTrue(!texts.contains("Restore purchases"))
            }

            When("back is pressed") {
                controller.get().onBackPressedDispatcher.onBackPressed()
                shadowOf(Looper.getMainLooper()).idle()
            }
            Then("the list shows again") { assertTrue(root.texts().contains("Restore purchases")) }
        }

    @Test
    fun `no-purchases screen offers restore`() =
        Given("a customer with nothing") {
            val (controller, _) = When("the Customer Center opens") { launch(customerInfo()) }
            snapshot(controller, "no_purchases")
            Then("it says so and offers restore") {
                val texts = controller.get().window.decorView.texts()
                assertTrue(texts.contains("No subscriptions found"))
                assertTrue(texts.contains("Restore purchases"))
            }
        }

    @Test
    fun `finishing ends the presentation once`() =
        Given("a presented Customer Center") {
            var dismissals = 0
            val (controller, host) = launch(customerInfo(), callbacks = CustomerCenterCallbacks(didDismiss = { dismissals += 1 }))
            When("it's closed") {
                controller.get().finish()
                controller.pause().stop().destroy()
            }
            Then("the host hears about it once") {
                assertEquals(1, host.ended)
                assertEquals(1, dismissals)
            }
        }

    @Test
    fun `an activity with no presentation finishes straight away`() =
        Given("no presentation") {
            CustomerCenterActivity.sessionHost = { null }
            val controller = When("the activity starts") { Robolectric.buildActivity(CustomerCenterActivity::class.java).setup() }
            Then("it finishes") { assertTrue(controller.get().isFinishing) }
        }
}
