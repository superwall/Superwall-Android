package com.superwall.sdk.store

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.delegate.subscription_controller.PurchaseControllerJava
import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class InternalPurchaseController(
    private val kotlinPurchaseController: PurchaseController?,
    private val javaPurchaseController: PurchaseControllerJava?,
    val context: Context,
) : PurchaseController {
    val hasExternalPurchaseController: Boolean
        get() = !hasInternalPurchaseController

    val hasInternalPurchaseController: Boolean
        get() = kotlinPurchaseController is AutomaticPurchaseController

    @Deprecated(
        "Implement purchase(activity, product, basePlanId, offerId) instead. " +
            "It receives a StoreProduct, which also supports custom store products.",
        ReplaceWith("purchase(activity, product, basePlanId, offerId)"),
    )
    override suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult {
        if (kotlinPurchaseController != null) {
            @Suppress("DEPRECATION")
            return kotlinPurchaseController.purchase(activity, productDetails, basePlanId, offerId)
        }
        return purchaseWithJavaController(productDetails, basePlanId, offerId)
    }

    override suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult {
        if (kotlinPurchaseController != null) {
            return kotlinPurchaseController.purchase(activity, product, basePlanId, offerId)
        }

        // PurchaseControllerJava predates StoreProduct — bridge Play products to its
        // ProductDetails signature; custom products can't be represented there.
        val productDetails = product.rawStoreProduct?.underlyingProductDetails
        if (productDetails != null) {
            return purchaseWithJavaController(productDetails, basePlanId, offerId)
        }
        return PurchaseResult.Failed(
            "No PurchaseController configured to handle custom product purchase.",
        )
    }

    private suspend fun purchaseWithJavaController(
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult {
        if (javaPurchaseController != null) {
            return suspendCoroutine { continuation ->
                javaPurchaseController.purchase(productDetails, basePlanId, offerId) { result ->
                    continuation.resume(result)
                }
            }
        }
        // Here is where we would implement our own product purchaser.
        return PurchaseResult.Cancelled()
    }

    override suspend fun restorePurchases(): RestorationResult {
        if (kotlinPurchaseController != null) {
            return kotlinPurchaseController.restorePurchases()
        } else if (javaPurchaseController != null) {
            return suspendCoroutine { continuation ->
                javaPurchaseController.restorePurchases { result, error ->
                    if (error == null) {
                        continuation.resume(result)
                    } else {
                        continuation.resume(RestorationResult.Failed(error))
                    }
                }
            }
        } else {
            // Here is where we would implement our own restoration
            return RestorationResult.Failed(null)
        }
    }
}
