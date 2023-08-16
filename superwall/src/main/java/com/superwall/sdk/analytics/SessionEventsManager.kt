package com.superwall.sdk.analytics

import com.superwall.sdk.analytics.model.TriggerSession
import com.superwall.sdk.analytics.session.AppSession
import com.superwall.sdk.analytics.trigger_session.TriggerSessionManager
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.network.Network
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.keys.Transactions
import com.superwall.sdk.storage.keys.TriggerSessions
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

interface SessionEventsDelegate {
    val triggerSession: TriggerSessionManager

    suspend fun enqueue(triggerSession: TriggerSession)
    suspend fun enqueue(triggerSessions: List<TriggerSession>)
    suspend fun enqueue(transaction: StoreTransaction)
}

// TODO: Re-enable session stuff

class SessionEventsManager(
//    private val queue: SessionEnqueuable,
    private val storage: Storage,
    private val network: Network,
    private val configManager: ConfigManager,
    private val factory: TriggerSessionManagerFactory
): CoroutineScope, SessionEventsDelegate {

    // The Coroutine Context is derived from SupervisorJob() + Dispatchers.Default
    // so that if a child job fails it does not affect the other child jobs
    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Default

    // The trigger session manager.
    override val triggerSession by lazy { factory.makeTriggerSessionManager() }

    // A queue of trigger session events that get sent to the server.
    private var cancellables: MutableList<Job> = mutableListOf()

    init {
        launch {
            postCachedSessionEvents()
        }
    }

    private suspend fun postCachedSessionEvents() {
//        configManager.config?.featureFlags?.enableSessionEvents?.let { enableSessionEvents ->
//            if (enableSessionEvents) {
//                val cachedTriggerSessions = storage.get(TriggerSessions::class.java) ?: listOf()
//                val cachedTransactions = storage.get(Transactions::class.java) ?: listOf()
//
//                if (cachedTriggerSessions.isEmpty() && cachedTransactions.isEmpty()) {
//                    return
//                }
//
//                val sessionEvents = SessionEventsRequest(
//                    triggerSessions = cachedTriggerSessions,
//                    transactions = cachedTransactions
//                )
//
//                network.sendSessionEvents(sessionEvents)
//
//                storage.clearCachedSessionEvents()
//            }
//        }
    }

    suspend fun updateAppSession(appSession: AppSession) {
//        triggerSession.updateAppSession(to = appSession)
    }

    override suspend fun enqueue(triggerSession: TriggerSession) {
//        configManager.config?.featureFlags?.enableSessionEvents?.let { enableSessionEvents ->
//            if (enableSessionEvents) {
//                queue.enqueue(triggerSession)
//            }
//        }
    }

    override suspend fun enqueue(triggerSessions: List<TriggerSession>) {
//        configManager.config?.featureFlags?.enableSessionEvents?.let { enableSessionEvents ->
//            if (enableSessionEvents) {
//                queue.enqueue(triggerSessions)
//            }
//        }
    }

    override suspend fun enqueue(transaction: StoreTransaction) {
//        configManager.config?.featureFlags?.enableSessionEvents?.let { enableSessionEvents ->
//            if (enableSessionEvents) {
//                queue.enqueue(transaction)
//            }
//        }
    }
}
