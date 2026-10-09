package com.superwall.sdk.store

import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import com.superwall.sdk.storage.StoredSubscriptionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Facade over the entitlements state held in a [StateActor].
 *
 * Implements [EntitlementsContext] directly — actions receive `this` as
 * their context, eliminating the intermediate object.
 *
 * State mutations use [StateActor.update] (synchronous CAS, routed through
 * interceptors) and are persisted immediately through [persist]. The initial
 * state is rebuilt from storage by [createInitialEntitlementsState] before
 * the actor starts, so cached status, product entitlements and web
 * entitlements are available synchronously after construction.
 */
class Entitlements(
    override val storage: Storage,
    actorScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    override val actor: StateActor<EntitlementsContext, EntitlementsState> =
        StateActor(createInitialEntitlementsState(storage), actorScope),
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit = {},
) : EntitlementsContext {
    override val scope: CoroutineScope = actorScope

    // -- Status flow (kept in sync with actor state for external collection) --

    private val _status: MutableStateFlow<SubscriptionStatus> =
        MutableStateFlow(actor.state.value.status)

    /**
     * A StateFlow of the entitlement status of the user. Set this using
     * [Superwall.instance.setEntitlementStatus].
     *
     * You can collect this flow to get notified whenever it changes.
     */
    val status: StateFlow<SubscriptionStatus>
        get() = _status.asStateFlow()

    init {
        scope.launch {
            actor.state.collect { _status.value = it.status }
        }
    }

    private val snapshot get() = actor.state.value

    /**
     * Active web entitlements from the latest redemption response.
     * Updated by [WebPaywallRedeemer] through [setWebEntitlements].
     */
    val web: Set<Entitlement>
        get() = snapshot.webEntitlements

    /**
     * Returns a snapshot of all entitlements by product ID.
     * Used when loading purchases to enrich entitlements with transaction data.
     */
    val entitlementsByProductId: Map<String, Set<Entitlement>>
        get() = snapshot.entitlementsByProduct

    internal var activeDeviceEntitlements: Set<Entitlement>
        get() = snapshot.activeDeviceEntitlements
        set(value) {
            update(EntitlementsState.Updates.SetDeviceEntitlements(value))
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
        get() = snapshot.inactive

    /**
     * Sets the entitlement status and updates the corresponding entitlement collections.
     *
     * The state update is synchronous; the new status is persisted right away.
     */
    fun setSubscriptionStatus(value: SubscriptionStatus) {
        when (value) {
            is SubscriptionStatus.Active -> {
                if (value.entitlements.isEmpty()) {
                    update(EntitlementsState.Updates.SetInactive)
                } else {
                    update(EntitlementsState.Updates.SetActive(value.entitlements.toSet()))
                }
            }

            is SubscriptionStatus.Inactive -> update(EntitlementsState.Updates.SetInactive)
            is SubscriptionStatus.Unknown -> update(EntitlementsState.Updates.SetUnknown)
        }
        _status.value = snapshot.status
        persist(StoredSubscriptionStatus, snapshot.status)
    }

    /**
     * Checks for entitlements belonging to the product.
     * First checks exact matches, then checks containing matches
     * by product ID + baseplan and productId so user doesn't remain without entitlements
     * if they purchased the product. This ensures users dont lose access for their subscription.
     */
    internal fun byProductId(id: String): Set<Entitlement> = snapshot.byProductId(id)

    /**
     * Returns a Set of Entitlements belonging to given product IDs.
     */
    fun byProductIds(ids: Set<String>): Set<Entitlement> = snapshot.byProductIds(ids)

    /**
     * Replaces the active web entitlements from a redemption response.
     */
    internal fun setWebEntitlements(entitlements: Set<Entitlement>) {
        update(EntitlementsState.Updates.SetWebEntitlements(entitlements))
    }

    /**
     * Updates the entitlements associated with product IDs and persists them to storage.
     */
    internal fun addEntitlementsByProductId(idToEntitlements: Map<String, Set<Entitlement>>) {
        update(EntitlementsState.Updates.AddProductEntitlements(idToEntitlements))
        persist(StoredEntitlementsByProductId, snapshot.entitlementsByProduct)
    }
}
