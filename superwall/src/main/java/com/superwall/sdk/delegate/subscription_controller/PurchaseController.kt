package com.superwall.sdk.delegate.subscription_controller

import android.app.Activity
import androidx.annotation.MainThread
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.store.abstractions.product.StoreProduct

/**
 * The interface that handles Superwall's subscription-related logic.
 *
 * By default, the Superwall SDK handles all subscription-related logic. However, if you'd like more
 * control, you can return a `PurchaseController` when configuring the SDK via
 * `Superwall.configure(apiKey, purchaseController, options, completion)`.
 *
 * When implementing this, you also need to set the subscription status using
 * `Superwall.subscriptionStatus`.
 *
 * To learn how to implement the `PurchaseController` in your app
 * and best practices, see [Purchases and Subscription Status](https://docs.superwall.com/docs/advanced-configuration).
 */
interface PurchaseController {
    /**
     * Called when the user initiates purchasing of a product.
     *
     * Add your purchase logic here and return its result. You can use Android's Billing APIs,
     * or if you use RevenueCat, you can call [`Purchases.shared.purchase(product)`](https://revenuecat.github.io/purchases-android-docs/documentation/revenuecat/purchases/purchase(product,completion)).
     *
     * @param productDefaults The `ProductDetails` the user would like to purchase.
     * @param basePlanId An optional base plan identifier of the product that's being purchased.
     * @param offerId An optional offer identifier of the product that's being purchased.
     * @return A `PurchaseResult` object, which is the result of your purchase logic.
     * **Note**: Make sure you handle all cases of `PurchaseResult`.
     */
    @MainThread
    suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult

    /**
     * Called when the user initiates a restore.
     *
     * Add your restore logic here, making sure that the user's subscription status is updated after restore,
     * and return its result.
     *
     * @return A `RestorationResult` that's `Restored` if the user's purchases were restored or `Failed(error)` if they weren't.
     * **Note**: `Restored` does not imply the user has an active subscription, it just means the restore had no errors.
     */
    @MainThread
    suspend fun restorePurchases(): RestorationResult

    /**
     * Called when the user initiates purchasing of a **custom** product — a product whose
     * `store` is `CUSTOM` and whose metadata is sourced from the Superwall API rather than
     * Google Play. Implement this to route the purchase through your own payment system
     * (e.g. Stripe, web checkout).
     *
     * The default implementation returns `PurchaseResult.Failed` to signal that the
     * controller does not handle custom products. Override it if you've configured any
     * custom products in the Superwall dashboard.
     *
     * @param customProduct The Superwall [StoreProduct] (with `isCustomProduct == true`) the
     * user would like to purchase. Its [StoreProduct.customTransactionId] is pre-generated
     * by the SDK and used as the original transaction identifier in analytics.
     */
    @MainThread
    suspend fun purchase(customProduct: StoreProduct): PurchaseResult =
        PurchaseResult.Failed(
            "This PurchaseController does not implement purchase(customProduct:). " +
                "Override it to handle custom (store == CUSTOM) products.",
        )
}
