package com.superwall.sdk.store.abstractions.transactions

import com.android.billingclient.api.Purchase
import java.util.Date
import java.util.UUID

data class GooglePlayStoreTransaction(
    val underlyingGooglePlayTransaction: Purchase,
    override val transactionDate: Date?,
    override val originalTransactionIdentifier: String,
    override val state: StoreTransactionState,
    override val storeTransactionId: String?,
    override val originalTransactionDate: Date?,
    override val webOrderLineItemID: String?,
    override val appBundleId: String?,
    override val subscriptionGroupId: String?,
    override val isUpgraded: Boolean?,
    override val expirationDate: Date?,
    override val offerId: String?,
    override val revocationDate: Date?,
    override val appAccountToken: UUID?,
    override val payment: StorePayment
): StoreTransactionType {

    constructor(transaction: Purchase) : this(
        underlyingGooglePlayTransaction = transaction,
        transactionDate = Date(transaction.purchaseTime),
        originalTransactionIdentifier = transaction.originalJson,
        state = StoreTransactionState.Purchased,
        storeTransactionId = transaction.orderId,
        originalTransactionDate = null, // This information might not be available directly from Google Play Billing
        webOrderLineItemID = null, // This information might not be available directly from Google Play Billing
        appBundleId = null, // This information might not be available directly from Google Play Billing
        subscriptionGroupId = null, // This information might not be available directly from Google Play Billing
        isUpgraded = null, // This information might not be available directly from Google Play Billing
        expirationDate = null, // This information might not be available directly from Google Play Billing
        offerId = null, // This information might not be available directly from Google Play Billing
        revocationDate = null, // This information might not be available directly from Google Play Billing
        appAccountToken = null, // This information might not be available directly from Google Play Billing
        payment = StorePayment(transaction)
    )
}
