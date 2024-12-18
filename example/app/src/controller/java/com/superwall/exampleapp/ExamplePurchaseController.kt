package com.superwall.exampleapp

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController

class ExamplePurchaseController : PurchaseController {
    override suspend fun purchase(
        activity: Activity,
        productDetails: ProductDetails,
        basePlanId: String?,
        offerId: String?,
    ): PurchaseResult {
        // Here you would implement your purchase logic
        Superwall.instance.setEntitlementStatus("pro")
        return PurchaseResult.Purchased()
    }

    override suspend fun restorePurchases(): RestorationResult {
        // Here you would implement your restore purchases logic
        return RestorationResult.Restored()
    }
}
