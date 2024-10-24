package com.superwall.sdk.billing

import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction

interface Billing {
    suspend fun awaitGetProducts(identifiers: Set<String>): Set<StoreProduct>

    suspend fun getLatestTransaction(factory: StoreTransactionFactory): StoreTransaction?
}
