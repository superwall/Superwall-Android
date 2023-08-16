package com.superwall.sdk.store.abstractions.transactions

import com.android.billingclient.api.Purchase
import java.util.UUID
import java.util.Date

data class GoogleBillingPurchaseTransaction(
    val underlyingSK2Transaction: Purchase,
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
) : StoreTransactionType {

    constructor(transaction: Purchase) : this(
        underlyingSK2Transaction = transaction,
        transactionDate =  Date(transaction.purchaseTime), // Replace with correct mapping
        originalTransactionIdentifier = transaction.orderId, // Replace with correct mapping
        state = StoreTransactionState.Purchased, // Replace with correct mapping
        storeTransactionId = transaction.orderId, // Replace with correct mapping
        originalTransactionDate =  Date(transaction.purchaseTime), // Replace with correct mapping
        webOrderLineItemID = null, // Replace with correct mapping
        appBundleId = null, // Replace with correct mapping
        subscriptionGroupId = null, // Replace with correct mapping
        isUpgraded = null, // Replace with correct mapping
        expirationDate = null, // Replace with correct mapping
        offerId = null, // Replace with correct mapping
        revocationDate = null, // Replace with correct mapping
        appAccountToken = null, // Replace with correct mapping
        payment = StorePayment(transaction, ) // Replace with correct mapping
    )
}
