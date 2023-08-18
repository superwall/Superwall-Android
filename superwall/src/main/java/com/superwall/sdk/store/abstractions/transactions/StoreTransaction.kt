package com.superwall.sdk.store.abstractions.transactions

import kotlinx.serialization.Serializable
import java.util.*

@Serializable
class StoreTransaction(
    private val transaction: StoreTransactionType,
    val configRequestId: String,
    val appSessionId: String,
    val triggerSessionId: String?
) : StoreTransactionType {

    private val id = UUID.randomUUID().toString()

    override val transactionDate: Date? get() = transaction.transactionDate
    override val originalTransactionIdentifier: String get() = transaction.originalTransactionIdentifier
    override val state: StoreTransactionState get() = transaction.state
    override val storeTransactionId: String? get() = transaction.storeTransactionId
    override val payment: StorePayment get() = transaction.payment
    override val originalTransactionDate: Date? get() = transaction.originalTransactionDate
    override val webOrderLineItemID: String? get() = transaction.webOrderLineItemID
    override val appBundleId: String? get() = transaction.appBundleId
    override val subscriptionGroupId: String? get() = transaction.subscriptionGroupId
    override val isUpgraded: Boolean? get() = transaction.isUpgraded
    override val expirationDate: Date? get() = transaction.expirationDate
    override val offerId: String? get() = transaction.offerId
    override val revocationDate: Date? get() = transaction.revocationDate
    override val appAccountToken: UUID? get() = transaction.appAccountToken

    // You can define SK1Transaction and SK2Transaction conversion methods here
    // based on the Android billing library.


}


