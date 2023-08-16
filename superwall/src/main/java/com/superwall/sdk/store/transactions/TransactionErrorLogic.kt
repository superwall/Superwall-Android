package com.superwall.sdk.store.transactions

import android.os.Build
import com.android.billingclient.api.BillingClient // Assuming you're using Android's Billing library for in-app purchases
import com.superwall.sdk.store.abstractions.product.StoreProduct

sealed class TransactionError : Throwable() {
    data class Pending(override val message: String) : TransactionError()
    data class Failure(override val message: String, val product: StoreProduct) : TransactionError()
}



sealed class TransactionErrorLogic {
    class PresentAlert : TransactionErrorLogic() {
        override fun equals(other: Any?): Boolean {
            return this === other
        }

        override fun hashCode(): Int {
            return System.identityHashCode(this)
        }
    }

    class Cancelled : TransactionErrorLogic() {
        override fun equals(other: Any?): Boolean {
            return this === other
        }

        override fun hashCode(): Int {
            return System.identityHashCode(this)
        }
    }

    companion object {
        fun handle(error: Throwable): TransactionErrorLogic {
            //  SW-2213: [android] [v1] refine transaction error logic
            // https://linear.app/superwall/issue/SW-2213/%5Bandroid%5D-%5Bv1%5D-refine-transaction-error-logic

            return TransactionErrorLogic.Cancelled()
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // iOS 15.0 -> Android 12+
//                if (error is StoreKitError) { // Assuming you've a StoreKitError equivalent in Kotlin
//                    when (error) {
//                        is StoreKitError.UserCancelled -> return CANCELLED
//                        else -> return PRESENT_ALERT
//                    }
//                }
//            }

//            if (error is BillingClient.BillingResponseCode) { // Check the type or use the appropriate type if different
//                when (error) {
//                    BillingClient.BillingResponseCode.USER_CANCELED -> return CANCELLED
//                    else -> return PRESENT_ALERT
//                }
//
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // iOS 14 -> Android 10+
//                    when (error) {
//                        // Assuming an equivalent error for overlayTimeout in Android
//                        // BillingClient.BillingResponseCode.OVERLAY_TIMEOUT -> return CANCELLED
//                        else -> return PRESENT_ALERT
//                    }
//                }
//            }

        }
    }
}
//
//// Assuming you'll have an equivalent StoreKitError in Kotlin
//sealed class StoreKitError : Throwable() {
//    object UserCancelled : StoreKitError()
//    // Add other StoreKit errors
//}
