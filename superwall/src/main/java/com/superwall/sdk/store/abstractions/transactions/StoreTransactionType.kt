package com.superwall.sdk.store.abstractions.transactions

import com.android.billingclient.api.BillingClient
import kotlinx.serialization.Serializer
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

@kotlinx.serialization.Serializable
sealed class StoreTransactionState {

    @kotlinx.serialization.Serializable
    object Purchasing : StoreTransactionState() // When purchase is initiated

    @kotlinx.serialization.Serializable
    object Purchased : StoreTransactionState() // When purchase is successful
    @kotlinx.serialization.Serializable
    object Failed : StoreTransactionState() // When purchase has failed
    @kotlinx.serialization.Serializable
    object Restored : StoreTransactionState() // When a previous purchase has been restored
    @kotlinx.serialization.Serializable
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
