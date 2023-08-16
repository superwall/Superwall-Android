package com.superwall.sdk.store.coordinator

import android.content.Context
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.store.StoreKitManager
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.products.GooglePlayProductsFetcher
import com.superwall.sdk.store.transactions.GoogleBillingTransactionVerifier
import kotlinx.coroutines.*


class StoreKitCoordinator(
    val context: Context,
    val delegateAdapter: SuperwallDelegateAdapter,
    val storeKitManager: StoreKitManager,
    private val factory: StoreTransactionFactory,
    val productFetcher: ProductsFetcher = GooglePlayProductsFetcher(context)
) {
    val txnChecker: TransactionChecker
    var productPurchaser: ProductPurchaser = delegateAdapter
    var txnRestorer: TransactionRestorer = delegateAdapter

    init {
        txnChecker = GoogleBillingTransactionVerifier(context)
    }
}
