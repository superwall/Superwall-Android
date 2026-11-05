package com.superwall.sdk.store.transactions

import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.billing.google.toObservedReport
import com.superwall.sdk.billing.google.toStorePurchaseUpdate
import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.store.PurchasingObserverState
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct

// The observer-mode tests describe Play's results as an app reports them. These convert them
// the way `ObserverModeHandler` does at the public edge, so the tests drive the neutral API.

internal suspend fun ObserverModeTracker.handle(
    result: InternalPurchaseResult,
    state: PurchasingObserverState,
) = handle(result.toStorePurchaseUpdate(), checkNotNull(state.toObservedReport()))

internal suspend fun TransactionManager.handle(
    result: InternalPurchaseResult,
    state: PurchasingObserverState,
) = handle(result.toStorePurchaseUpdate(), checkNotNull(state.toObservedReport()))

internal fun ObservedPurchase(
    productId: String,
    details: ProductDetails,
) = ObservedPurchase(productId, StoreProduct(RawStoreProduct.from(details)))
