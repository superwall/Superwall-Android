package com.example.superapp.utils

import android.app.Activity
import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.Superwall
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.superapp.Keys

/**
 * A purchase controller that does nothing, so that the subscription status is whatever the test
 * sets and stays that way.
 *
 * Without a purchase controller the SDK manages the status itself: it reads the purchases from
 * Google Play at launch and on every return to the foreground, and publishes what it finds. On a
 * device where Play billing works, that lands on top of a status a test set with
 * `setSubscriptionStatus`, at a moment that changes from run to run, and a test for a subscribed
 * user sees a paywall.
 */
class ManualSubscriptionPurchaseController : PurchaseController {
    @Deprecated("Deprecated in the SDK")
    override suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult = PurchaseResult.Cancelled()

    override suspend fun restorePurchases(): RestorationResult = RestorationResult.Restored()
}

/**
 * Configures the SDK for tests that set the subscription status themselves. The status starts as
 * Inactive, where it would otherwise stay Unknown and hold every paywall back.
 */
fun configureWithManualSubscriptionStatus(apiKey: String = Keys.CONSTANT_API_KEY) {
    Superwall.configure(
        getInstrumentation().targetContext.applicationContext as Application,
        apiKey,
        purchaseController = ManualSubscriptionPurchaseController(),
        options =
            SuperwallOptions().apply {
                paywalls.shouldPreload = false
            },
    )
    Superwall.instance.setSubscriptionStatus(SubscriptionStatus.Inactive)
}
