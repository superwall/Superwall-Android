package com.superwall.sdk.store

import com.superwall.sdk.dependencies.HasExternalPurchaseControllerFactory
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import com.superwall.sdk.storage.StoredSubscriptionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Facade over the entitlements state of the shared SDK actor.
 *
 * Implements [EntitlementsContext] directly — actions receive `this` as
 * their context, eliminating the intermediate object.
 *
 * State mutations use [actor.update] (synchronous CAS, routed through
 * interceptors). Persistence is dispatched as fire-and-forget actions.
 */
class Entitlements(
    override val storage: Storage,
    override val actor: StateActor<EntitlementsContext, EntitlementsState>,
    actorScope: CoroutineScope,
    factory: Factory,
) : EntitlementsContext,
    HasExternalPurchaseControllerFactory by factory {
    interface Factory : HasExternalPurchaseControllerFactory

    override val scope: CoroutineScope = actorScope

    // -- Status flow (kept in sync with actor state for external collection) --

    private val _status: MutableStateFlow<SubscriptionStatus> =
        MutableStateFlow(actor.state.value.status)

    /**
     * A StateFlow of the entitlement status of the user.
     * You can collect this flow to get notified whenever it changes.
     */
    val status: StateFlow<SubscriptionStatus>
        get() = _status.asStateFlow()

    init {
        scope.launch {
            actor.state.collect { _status.value = it.status }
        }
    }

    // -- Web entitlements (from actor state, updated by WebPaywallRedeemer) --

    val web: Set<Entitlement>
        get() = snapshot.webEntitlements

    private val snapshot get() = actor.state.value

    /**
     * Returns a snapshot of all entitlements by product ID.
     */
    val entitlementsByProductId: Map<String, Set<Entitlement>>
        get() = snapshot.entitlementsByProduct

    internal var activeDeviceEntitlements: Set<Entitlement>
        get() = snapshot.activeDeviceEntitlements
        set(value) {
            actor.update(EntitlementsState.Updates.SetDeviceEntitlements(value))
        }

    /**
     * All entitlements, regardless of whether they're active or not.
     * Includes web entitlements from the latest redemption response.
     */
    val all: Set<Entitlement>
        get() = snapshot.all

    /**
     * The active entitlements.
     * Uses [mergeEntitlementsPrioritized] to deduplicate entitlements by ID,
     * keeping the highest priority version of each and merging productIds.
     */
    val active: Set<Entitlement>
        get() = snapshot.active

    /**
     * The inactive entitlements.
     */
    val inactive: Set<Entitlement>
        get() = all - active

    /**
     * Sets the entitlement status.
     *
     * State update is synchronous ([actor.update] — CAS through interceptors).
     * Persistence is dispatched as a fire-and-forget action.
     */
    fun setSubscriptionStatus(value: SubscriptionStatus) {
        when (value) {
            is SubscriptionStatus.Active -> {
                if (value.entitlements.isEmpty()) {
                    actor.update(EntitlementsState.Updates.SetInactive)
                } else {
                    actor.update(EntitlementsState.Updates.SetActive(value.entitlements.toSet()))
                }
            }
            is SubscriptionStatus.Inactive -> actor.update(EntitlementsState.Updates.SetInactive)
            is SubscriptionStatus.Unknown -> actor.update(EntitlementsState.Updates.SetUnknown)
        }
        _status.value = snapshot.status
        persist(StoredSubscriptionStatus, snapshot.status)
    }

    /**
     * Returns a Set of Entitlements belonging to a given productId.
     */
    internal fun byProductId(id: String): Set<Entitlement> = snapshot.byProductId(id)

    /**
     * Returns a Set of Entitlements belonging to given product IDs.
     */
    fun byProductIds(ids: Set<String>): Set<Entitlement> = snapshot.byProductIds(ids)

    /**
     * Updates the entitlements associated with product IDs and persists them to storage.
     */

    /**
     * Updates the web entitlements from a redemption response.
     */
    internal fun setWebEntitlements(entitlements: Set<Entitlement>) {
        actor.update(EntitlementsState.Updates.SetWebEntitlements(entitlements))
    }

    internal fun addEntitlementsByProductId(idToEntitlements: Map<String, Set<Entitlement>>) {
        actor.update(EntitlementsState.Updates.AddProductEntitlements(idToEntitlements))
        persist(StoredEntitlementsByProductId, snapshot.entitlementsByProduct)
    }
}
