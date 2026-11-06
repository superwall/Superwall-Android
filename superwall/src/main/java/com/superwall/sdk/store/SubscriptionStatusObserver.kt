package com.superwall.sdk.store

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.misc.SuperwallScope
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.LatestCustomerInfo
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredSubscriptionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.scan

/**
 * Tells the app, analytics and storage when the subscription status or the customer info
 * changes: once per real change, never for the cached value it starts from.
 */
internal class SubscriptionStatusObserver(
    private val scope: SuperwallScope,
    private val subscriptionStatus: Flow<SubscriptionStatus>,
    private val customerInfo: Flow<CustomerInfo>,
    private val storage: Storage,
    private val delegate: () -> SuperwallDelegateAdapter,
    private val track: suspend (Trackable) -> Unit,
    private val recheckPreload: suspend () -> Unit,
) {
    /** Starts both listeners. Call it once, after the cached status and customer info are set. */
    fun start() {
        scope.launchWithTracking {
            subscriptionStatus
                // Default StateFlow equality re-fires when entitlement product fields get
                // enriched (productIds, latestProductId) even though the user-facing
                // status is unchanged. Dedupe ignoring product identifiers — see
                // Entitlement.isDistinct.
                .distinctUntilChanged(::isSameUserFacingStatus)
                // Pair each status with the one before it. Entitlements persists the
                // new status before this collector runs, so storage can't supply `from`.
                .scan<SubscriptionStatus, Pair<SubscriptionStatus?, SubscriptionStatus>?>(null) { previous, newStatus ->
                    Pair(previous?.second, newStatus)
                }.filterNotNull()
                .filter { it.first != null } // Drops the cached/initial emission
                .collect { (previous, newValue) ->
                    // Save and handle the new value
                    val oldValue = previous ?: SubscriptionStatus.Unknown
                    storage.write(StoredSubscriptionStatus, newValue)
                    delegate().subscriptionStatusDidChange(oldValue, newValue)
                    track(InternalSuperwallEvent.SubscriptionStatusDidChange(newValue))
                    recheckPreload()
                }
        }
        scope.launchWithTracking {
            customerInfo
                .distinctUntilChanged()
                .scan<CustomerInfo, Pair<CustomerInfo?, CustomerInfo>?>(null) { previousPair, newStatus ->
                    Pair(previousPair?.second, newStatus)
                }.filterNotNull()
                .filter { it.first != null }
                .collect {
                    val (old, new) = it
                    storage.write(LatestCustomerInfo, new)
                    delegate().customerInfoDidChange(old!!, new)
                    track(InternalSuperwallEvent.CustomerInfoDidChange(old, new))
                    recheckPreload()
                }
        }
    }

    internal companion object {
        /**
         * True when the user would see no difference between [old] and [new]: same kind of
         * status and, when Active, the same entitlements by id and user-facing fields.
         */
        fun isSameUserFacingStatus(
            old: SubscriptionStatus,
            new: SubscriptionStatus,
        ): Boolean =
            when {
                old === new -> true
                old is SubscriptionStatus.Active && new is SubscriptionStatus.Active -> {
                    if (old.entitlements.size != new.entitlements.size) {
                        false
                    } else {
                        val newById = new.entitlements.associateBy { it.id }
                        old.entitlements.all { o ->
                            val n = newById[o.id] ?: return@all false
                            !o.isDistinct(n)
                        }
                    }
                }
                else -> old::class == new::class
            }
    }
}
