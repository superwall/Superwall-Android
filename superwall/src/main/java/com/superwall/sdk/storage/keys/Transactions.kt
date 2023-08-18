package com.superwall.sdk.storage.keys

import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TransactionsConfig: StorageConfig {
    override val key: String = "store.transactions.v2"
    override var directory: CacheDirectory = CacheDirectory.Cache
}

//TODO: Replace with real data
@Serializable
data class StoreTransaction(val id: String)

@Serializable
data class Transactions(val transactions: List<StoreTransaction>)

class TransactionsManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper

    suspend fun get(): Transactions? = mutex.withLock {
        this.cacheHelper.read(TransactionsConfig)?.let {
            return@withLock Json.decodeFromString(it.decodeToString())
        }
    }

    suspend fun set(transactions: Transactions) = mutex.withLock {
        this.cacheHelper.write(TransactionsConfig, Json.encodeToString(transactions).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete() = mutex.withLock {
        this.cacheHelper.delete(TransactionsConfig)
    }
}
