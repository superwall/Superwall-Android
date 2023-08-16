package com.superwall.sdk.store.coordinator

import android.content.Context
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.products.GooglePlayProductsFetcher
import kotlinx.coroutines.*


class NopTransactionChecker: TransactionChecker {
    override suspend fun getAndValidateLatestTransaction(
        productId: String,
        hasPurchaseController: Boolean
    ): StoreTransaction? {
        throw java.lang.Error("Should never need this with a purchase controller")
    }
}

class StoreKitCoordinator(
    val context: Context,
    val delegateAdapter: SuperwallDelegateAdapter,
    val storeKitManager: StoreKitManager,
    private val factory: StoreTransactionFactory,
    val productFetcher: ProductsFetcher = GooglePlayProductsFetcher(context)
) {
    val txnChecker: TransactionChecker
    var productPurchaser: ProductPurchaser
    var txnRestorer: TransactionRestorer

    init {
        productPurchaser = delegateAdapter
        txnRestorer = delegateAdapter
        txnChecker = NopTransactionChecker()
    }
}
