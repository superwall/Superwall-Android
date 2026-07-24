package com.superwall.sdk.store

import android.content.Context
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.store.abstractions.product.StoreProduct

/**
 * Use this controller as a [PurchaseController] in [com.superwall.sdk.Superwall.configure] if you:
 * - Want to use custom store products
 * - Also want to keep other purchases going through superwall
 */
class CustomProductPurchaseController(
    val appContext: Context,
    val onPurchase: (customProduct: StoreProduct)-> PurchaseResult
) : PurchaseController by AutomaticPurchaseController(
    appContext,
    IOScope()
){
    override suspend fun purchase(customProduct: StoreProduct): PurchaseResult {
        return onPurchase(customProduct)
    }
}
