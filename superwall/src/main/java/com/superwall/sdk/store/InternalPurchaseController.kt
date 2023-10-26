package com.superwall.sdk.store

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.SkuDetails
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.delegate.subscription_controller.PurchaseControllerJava
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.store.transactions.GoogleBillingTransactionVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class InternalPurchaseController(
    private val kotlinPurchaseController: PurchaseController?,
    private val javaPurchaseController: PurchaseControllerJava?,
    val context: Context
): PurchaseController {
    val hasExternalPurchaseController: Boolean
        get() = kotlinPurchaseController != null || javaPurchaseController != null
    val transactionVerifier = GoogleBillingTransactionVerifier(context)

    suspend fun tryToRestore(paywallViewController: PaywallViewController) {
        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallTransactions,
            message = "Attempting Restore"
        )

        paywallViewController.loadingState = PaywallLoadingState.LoadingPurchase()

        val restorationResult = restorePurchases()

        val hasRestored = restorationResult is RestorationResult.Restored
        val isUserSubscribed = Superwall.instance.subscriptionStatus.value == SubscriptionStatus.ACTIVE

        if (hasRestored && isUserSubscribed) {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallTransactions,
                message = "Transactions Restored"
            )
            transactionWasRestored(paywallViewController)
        } else {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallTransactions,
                message = "Transactions Failed to Restore"
            )

            paywallViewController.presentAlert(
                title = Superwall.instance.options.paywalls.restoreFailed.title,
                message = Superwall.instance.options.paywalls.restoreFailed.message,
                closeActionTitle = Superwall.instance.options.paywalls.restoreFailed.closeButtonTitle
            )
        }
    }

    private suspend fun transactionWasRestored(paywallViewController: PaywallViewController) {
        val paywallInfo = paywallViewController.info

        val trackedEvent = InternalSuperwallEvent.Transaction(
            state = InternalSuperwallEvent.Transaction.State.Restore(),
            paywallInfo = paywallInfo,
            product = null,
            model = null
        )
        Superwall.instance.track(trackedEvent)

        if (Superwall.instance.options.paywalls.automaticallyDismiss) {
            Superwall.instance.dismiss(paywallViewController, result = PaywallResult.Restored())
        }
    }

    override suspend fun purchase(activity: Activity, product: SkuDetails): PurchaseResult {
        // TODO: Await beginPurchase with purchasing coordinator: https://linear.app/superwall/issue/SW-2415/[android]-implement-purchasingcoordinator

        if (kotlinPurchaseController != null) {
            return kotlinPurchaseController.purchase(activity, product)
        } else if (javaPurchaseController != null) {
            return suspendCoroutine { continuation ->
                javaPurchaseController.purchase(product) { result ->
                    continuation.resume(result)
                }
            }
        } else {
            // Here is where we would implement our own product purchaser.
            return PurchaseResult.Cancelled()
        }

//
//        kotlinPurchaseController?.let {
//            return it.purchase(currentActivity as Activity, sk1Product.skuDetails)
//            }
//
//            // There used to be a failure if raw store product wasn't present but there isn't anymore...
//            // not sure why
//        }
//
//        // SW-2217
//        // https://linear.app/superwall/issue/SW-2217/%5Bandroid%5D-%5Bv1%5D-add-back-support-for-javanon-kotlinxcoroutines-purchase
////        javaPurchaseController?.let {
////            product.sk1Product?.let { sk1Product ->
////                return it.purchase(sk1Product)
////            } ?: return PurchaseResult.failed(PurchaseError.productUnavailable)
////        }
//        return PurchaseResult.Cancelled()
    }

    override suspend fun restorePurchases(): RestorationResult {
        if (kotlinPurchaseController != null) {
            return kotlinPurchaseController.restorePurchases()
        } else if (javaPurchaseController != null) {
            return suspendCoroutine<RestorationResult> { continuation ->
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