package com.superwall.sdk.analytics.trigger_session


import android.app.Activity
import com.superwall.sdk.analytics.SessionEventsDelegate
import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.model.TriggerSession
import com.superwall.sdk.analytics.session.AppSession
import com.superwall.sdk.analytics.session.AppSessionManager
import com.superwall.sdk.config.ConfigManager
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.triggers.InternalTriggerResult
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.abstractions.product.StoreProduct

enum class LoadState {
    START,
    END,
    FAIL
}


// TODO: https://linear.app/superwall/issue/SW-2366/[android]-implement-triggersessionmanager
internal class TriggerSessionManager(
    val storage: Storage,
    val configManager: ConfigManager,
    val appSessionManager: AppSessionManager,
    val identityManager: IdentityManager,
    val delegate: SessionEventsDelegate,
    val sessionEventsManager: SessionEventsManager,

    ) {
    val pendingTriggerSessions: MutableMap<String, TriggerSession> = mutableMapOf()
    var activeTriggerSession: TriggerSession? = null
    var observerCancellables: List<Any> = listOf()
    var configListener: Any? = null
    // TODO: Re-enable
//    var transactionCount: TriggerSession.Transaction.Count? = null

    fun listenForConfig() {
        // implementation
    }

    fun addObservers() {
        // implementation
    }

    fun willEnterForeground() {
        // implementation
    }

    fun didEnterBackground() {
        // implementation
    }

    fun createSessions(from: Config) {
        // implementation
    }

    fun activateSession(
        forPresentationInfo: PresentationInfo,
        on: Activity? = null,
        paywall: Paywall? = null,
        triggerResult: InternalTriggerResult? = null,
        trackEvent: (suspend (Trackable) -> TrackingResult)? = null
    ) {
        // implementation
    }

    fun endSession() {
        // implementation
    }

    fun enqueueCurrentTriggerSession() {
        // implementation
    }

    fun enqueuePendingTriggerSessions() {
        // implementation
    }

    fun updateAppSession(to: AppSession) {
        // implementation
    }

    fun trackPaywallOpen() {
        // implementation
    }

    fun trackPaywallClose() {
        // implementation
    }

    fun trackWebviewLoad(forPaywallId: String, state: LoadState) {
        // implementation
    }

    fun trackPaywallResponseLoad(forPaywallId: String?, state: LoadState) {
        // implementation
    }

    fun trackProductsLoad(forPaywallId: String, state: LoadState) {
        // implementation
    }

    fun trackBeginTransaction(of: StoreProduct) {
        // implementation
    }

    fun trackTransactionError() {
        // implementation
    }

    fun trackTransactionAbandon() {
        // implementation
    }

    fun trackTransactionRestoration(withId: String?, product: StoreProduct?) {
        // implementation
    }

    fun trackPendingTransaction() {
        // implementation
    }

    fun trackTransactionSucceeded(
        withId: String?,
        forProduct: StoreProduct,
        isFreeTrialAvailable: Boolean
    ) {
        // implementation
    }

    fun trackTransactionDeferred(withId: String?, forProduct: StoreProduct) {
        // implementation
    }
}
