package com.superwall.sdk.delegate

import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.delegate.subscription_controller.PurchaseControllerJava
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.coordinator.ProductPurchaser
import com.superwall.sdk.store.coordinator.TransactionRestorer
import java.net.URL

class SuperwallDelegateAdapter(
    private val kotlinPurchaseController: PurchaseController?,
    private val javaPurchaseController: PurchaseControllerJava?
): ProductPurchaser, TransactionRestorer {
    val hasPurchaseController: Boolean
        get() = kotlinPurchaseController != null || javaPurchaseController != null

    var kotlinDelegate: SuperwallDelegate? = null
    var javaDelegate: SuperwallDelegateJava? = null

    suspend fun handleCustomPaywallAction(name: String) {
        kotlinDelegate?.handleCustomPaywallAction(name)
            ?: javaDelegate?.handleCustomPaywallAction(name)
    }

    private suspend fun willDismissPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.willDismissPaywall(paywallInfo)
            ?: javaDelegate?.willDismissPaywall(paywallInfo)
    }

    private suspend fun willPresentPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.willPresentPaywall(paywallInfo)
            ?: javaDelegate?.willPresentPaywall(paywallInfo)
    }

    private suspend fun didDismissPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.didDismissPaywall(paywallInfo)
            ?: javaDelegate?.didDismissPaywall(paywallInfo)
    }

    private suspend fun didPresentPaywall(paywallInfo: PaywallInfo) {
        kotlinDelegate?.didPresentPaywall(paywallInfo)
            ?: javaDelegate?.didPresentPaywall(paywallInfo)
    }

    suspend fun paywallWillOpenURL(url: URL) {
        kotlinDelegate?.paywallWillOpenURL(url)
            ?: javaDelegate?.paywallWillOpenURL(url)
    }

    suspend fun paywallWillOpenDeepLink(url: URL) {
        kotlinDelegate?.paywallWillOpenDeepLink(url)
            ?: javaDelegate?.paywallWillOpenDeepLink(url)
    }

    suspend fun handleSuperwallEvent(eventInfo: SuperwallEventInfo) {
        kotlinDelegate?.handleSuperwallEvent(eventInfo)
            ?: javaDelegate?.handleSuperwallEvent(eventInfo)
    }

    fun subscriptionStatusDidChange(newValue: SubscriptionStatus) {
        kotlinDelegate?.subscriptionStatusDidChange(newValue)
            ?: javaDelegate?.subscriptionStatusDidChange(newValue)
    }

    private suspend fun handleLog(
        level: String,
        scope: String,
        message: String?,
        info: Map<String, Any>?,
        error: Throwable?
    ) {
        Logger.debug(
            logLevel = LogLevel.valueOf(level),
            scope = LogScope.valueOf(scope),
            message = message ?: "No message",
            info = info,
            error = error
        )
    }

    // Product Purchaser Extension
    // @MainScope
    override suspend fun purchase(product: StoreProduct): PurchaseResult {
        kotlinPurchaseController?.let {
            product.rawStoreProduct?.let { sk1Product ->
                return it.purchase(sk1Product.skuDetails)
            }

            // There used to be a failure if raw store product wasn't present but there isn't anymore...
            // not sure why
        }

        // SW-2217
        // https://linear.app/superwall/issue/SW-2217/%5Bandroid%5D-%5Bv1%5D-add-back-support-for-javanon-kotlinxcoroutines-purchase
//        javaPurchaseController?.let {
//            product.sk1Product?.let { sk1Product ->
//                return it.purchase(sk1Product)
//            } ?: return PurchaseResult.failed(PurchaseError.productUnavailable)
//        }
        return PurchaseResult.Cancelled()
    }

    // Transaction Restorer Extension
//    @MainScope
     override suspend fun restorePurchases(): RestorationResult {
        kotlinPurchaseController?.let {
            return it.restorePurchases()
        }
        // SW-2217
        // https://linear.app/superwall/issue/SW-2217/%5Bandroid%5D-%5Bv1%5D-add-back-support-for-javanon-kotlinxcoroutines-purchase
//        javaPurchaseController?.let {
//            return it.restorePurchases()
//        }
        return RestorationResult.Failed(null)
    }
}
