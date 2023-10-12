package com.superwall.superapp

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.SkuDetails
import com.revenuecat.purchases.*
import com.revenuecat.purchases.interfaces.GetStoreProductsCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreTransaction
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.identity.setUserAttributes
import kotlinx.coroutines.CompletableDeferred

// Extension function to convert callback to suspend function
suspend fun Purchases.awaitProducts(productIds: List<String>): List<StoreProduct> {
    val deferred = CompletableDeferred<List<StoreProduct>>()
    getProducts(productIds, object : GetStoreProductsCallback {
        override fun onReceived(storeProducts: List<StoreProduct>) {
            deferred.complete(storeProducts)
        }

        override fun onError(error: PurchasesError) {
            // Not sure about this cast...
            deferred.completeExceptionally(Exception(error.message))
        }
    })
    return deferred.await()
}

interface PurchaseCompletion {
    var storeTransaction: StoreTransaction
    var customerInfo: CustomerInfo
}

suspend fun Purchases.awaitPurchase(activity: Activity, storeProduct: StoreProduct): PurchaseCompletion {
    val deferred = CompletableDeferred<PurchaseCompletion>()
    purchase(PurchaseParams.Builder(activity, storeProduct).build(), object : PurchaseCallback {
        override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
            deferred.complete(object : PurchaseCompletion {
                override var storeTransaction: StoreTransaction = storeTransaction
                override var customerInfo: CustomerInfo = customerInfo
            })
        }

        override fun onError(error: PurchasesError, p1: Boolean) {
            deferred.completeExceptionally(Exception(error.toString()))
        }
    })
    return deferred.await()
}

suspend fun Purchases.awaitRestoration(): CustomerInfo {
    val deferred = CompletableDeferred<CustomerInfo>()
    restorePurchases(object : ReceiveCustomerInfoCallback {
        override fun onReceived(purchaserInfo: CustomerInfo) {
            deferred.complete(purchaserInfo)
        }

        override fun onError(error: PurchasesError) {
            deferred.completeExceptionally(error as Throwable)
        }
    })
    return deferred.await()
}

class RevenueCatPurchaseController(val context: Context): PurchaseController, UpdatedCustomerInfoListener {

    init {
        Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(PurchasesConfiguration.Builder(context, "goog_DCSOujJzRNnPmxdgjOwdOOjwilC").build())

        // Make sure we get the updates
        Purchases.sharedInstance.updatedCustomerInfoListener = this
    }

    fun syncSubscriptionStatus() {
        // Refetch the customer info on load
        Purchases.sharedInstance.getCustomerInfoWith {
            if (hasAnyActiveEntitlements(it)) {
                setSubscriptionStatus(SubscriptionStatus.ACTIVE)
            }  else {
                setSubscriptionStatus(SubscriptionStatus.INACTIVE)
            }
        }
    }

    /**
     * Callback for rc customer updated info
     */
    override fun onReceived(customerInfo: CustomerInfo) {
       if (hasAnyActiveEntitlements(customerInfo)) {
           setSubscriptionStatus(SubscriptionStatus.ACTIVE)
       }  else {
           setSubscriptionStatus(SubscriptionStatus.INACTIVE)
       }
    }

    /**
     * Initiate a purchase
     */
    override suspend fun purchase(activity: Activity, product: SkuDetails): PurchaseResult {
        val products = Purchases.sharedInstance.awaitProducts(listOf(product.sku))
        val product = products.firstOrNull()
            ?: return PurchaseResult.Failed(Exception("Product not found"))
        try {
           Purchases.sharedInstance.awaitPurchase(activity, product)
        } catch (e: Throwable) {
            return PurchaseResult.Failed(e)
        }
        return PurchaseResult.Purchased()
    }

    /**
     * Restore purchases
     */
    override suspend fun restorePurchases(): RestorationResult {
        try {
            if (hasAnyActiveEntitlements(Purchases.sharedInstance.awaitRestoration())) {
                return RestorationResult.Restored()
            } else {
                return RestorationResult.Failed(Exception("No active entitlements"))
            }
        } catch (e: Throwable) {
            return RestorationResult.Failed(e)
        }
    }

    /**
     * Check if the customer has any active entitlements
     */
    private fun hasAnyActiveEntitlements(customerInfo: CustomerInfo): Boolean {
        val entitlements = customerInfo.entitlements.active.values.map { it.identifier }
        return entitlements.isNotEmpty()
    }

    private fun setSubscriptionStatus(subscriptionStatus: SubscriptionStatus) {
        if (Superwall.initialized) {
            Superwall.instance.setSubscriptionStatus(subscriptionStatus)
        }
    }
}