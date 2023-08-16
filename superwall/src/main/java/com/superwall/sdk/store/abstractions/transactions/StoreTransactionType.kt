package com.superwall.sdk.store.abstractions.transactions

import com.android.billingclient.api.BillingClient
import java.util.Date
import java.util.UUID

interface StoreTransactionType  {
    val transactionDate: Date?
    val originalTransactionIdentifier: String
    val state: StoreTransactionState
    val storeTransactionId: String?
    val payment: StorePayment

    // MARK: iOS 15 only properties
    val originalTransactionDate: Date?
    val webOrderLineItemID: String?
    val appBundleId: String?
    val subscriptionGroupId: String?
    val isUpgraded: Boolean?
    val expirationDate: Date?
    val offerId: String?
    val revocationDate: Date?
    val appAccountToken: UUID?
}

sealed class StoreTransactionState {
    object Purchasing : StoreTransactionState() // When purchase is initiated
    object Purchased : StoreTransactionState() // When purchase is successful
    object Failed : StoreTransactionState() // When purchase has failed
    object Restored : StoreTransactionState() // When a previous purchase has been restored
    object Deferred : StoreTransactionState() // Optional: When a purchase is pending some external action

    companion object {
        fun from(purchaseResult: Int): StoreTransactionState {
            return when (purchaseResult) {
                BillingClient.BillingResponseCode.OK -> Purchased
                BillingClient.BillingResponseCode.USER_CANCELED -> Purchasing
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> Restored
                // Add other cases based on the specific responses you need to handle
                else -> Failed
            }
        }
    }
}
