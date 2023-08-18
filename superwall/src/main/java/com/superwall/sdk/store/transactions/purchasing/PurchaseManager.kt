package com.superwall.sdk.store.transactions.purchasing

import com.superwall.sdk.delegate.InternalPurchaseResult
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import java.util.*

sealed class PurchaseError : Throwable() {
    object ProductUnavailable : PurchaseError()
    object Unknown : PurchaseError()
    object NoTransactionDetected : PurchaseError()
    object UnverifiedTransaction : PurchaseError()

    override val message: String?
        get() = when (this) {
            is ProductUnavailable -> "There was an error retrieving the product to purchase."
            is NoTransactionDetected -> "No receipt was found on device for the product transaction."
            is UnverifiedTransaction -> "The product transaction could not be verified."
            is Unknown -> "An unknown error occurred."
        }
}

class PurchaseManager(
    private val storeKitManager: StoreKitManager,
    val hasPurchaseController: Boolean
) {
    suspend fun purchase(product: StoreProduct): InternalPurchaseResult {
        print("! Purchase Manager: ${product.productIdentifier}")
        val purchaseStartAt = Date()

        val result = storeKitManager.coordinator.productPurchaser.purchase(product = product)

        return when (result) {
            is PurchaseResult.Failed -> InternalPurchaseResult.Failed(result.error)
            is PurchaseResult.Pending -> InternalPurchaseResult.Pending
            is PurchaseResult.Cancelled -> InternalPurchaseResult.Cancelled
            is PurchaseResult.Purchased -> try {
                val transaction = storeKitManager.coordinator.txnChecker.getAndValidateLatestTransaction(
                    product.productIdentifier,
                    hasPurchaseController = hasPurchaseController
                )

                if (hasRestored(
                        transaction,
                        hasPurchaseController,
                        purchaseStartAt
                    )
                ) {
                    InternalPurchaseResult.Restored
                } else {
                    InternalPurchaseResult.Purchased(transaction)
                }
            } catch (throwable: Throwable) {
                val error = throwable as? Exception ?: Exception(throwable)
                InternalPurchaseResult.Failed(error)
            }
        }
    }

    private fun hasRestored(
        transaction: StoreTransaction?,
        hasPurchaseController: Boolean,
        purchaseStartAt: Date
    ): Boolean {
        if (hasPurchaseController) {
            return false
        }

        transaction?.let {
            // If has a transaction date and that happened before purchase
            // button was pressed...
            if (it.transactionDate?.before(purchaseStartAt) == true) {
                // ...and if it has an expiration date that expires in the future,
                // then we must have restored.
                it.expirationDate?.let { expirationDate ->
                    if (expirationDate >= Date()) {
                        return true
                    }
                } ?: return true // If no expiration date, it must be a non-consumable product which has been restored.
            }
        }

        return false
    }
}
