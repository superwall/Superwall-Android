package com.superwall.sdk.store

import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase

sealed class PurchasingObserverState {
    class PurchaseWillBegin(
        val productId: ProductDetails,
    ) : PurchasingObserverState()

    class PurchaseResult(
        val result: BillingResult,
        val purchases: List<Purchase>?,
    ) : PurchasingObserverState()

    class PurchaseError(
        val product: ProductDetails,
        val error: Throwable,
    ) : PurchasingObserverState()
}
