package com.superwall.sdk.billing.observer

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.superwall.sdk.Superwall
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.abstractions.product.RawStoreProduct

fun BillingClient.launchBillingFlowWithSuperwall(
    activity: Activity,
    params: SuperwallBillingFlowParams,
): BillingResult {
    if (Superwall.initialized.not()) {
        throw IllegalStateException("Superwall SDK is not initialized")
    }
    if (Superwall.instance.options.shouldObservePurchases
            .not()
    ) {
        Logger.debug(
            LogLevel.error,
            LogScope.superwallCore,
            "Observer mode is not enabled. In order to observe purchases, please enable it in the SuperwallOptions by setting `shouldObservePurchases` to true.",
            mapOf(
                "method" to "launchBillingFlowWithSuperwall",
                "products" to
                    params.productDetailsParams
                        .map { it.details.productId }
                        .joinToString(", "),
            ),
        )
        return launchBillingFlow(activity, params.toOriginal())
    }

    params.productDetailsParams.forEach {
        val product = RawStoreProduct.from(it.details)
        Superwall.instance.observe(
            PurchasingObserverState.PurchaseWillBegin(product.underlyingProductDetails),
        )
    }
    return launchBillingFlow(activity, params.toOriginal())
}
