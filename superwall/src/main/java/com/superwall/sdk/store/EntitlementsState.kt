package com.superwall.sdk.store

import com.superwall.sdk.billing.DecomposedProductIds
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.models.customer.mergeEntitlementsPrioritized
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import com.superwall.sdk.storage.StoredSubscriptionStatus

data class EntitlementsState(
    val status: SubscriptionStatus = SubscriptionStatus.Unknown,
    val entitlementsByProduct: Map<String, Set<Entitlement>> = emptyMap(),
    val activeDeviceEntitlements: Set<Entitlement> = emptySet(),
    val backingActive: Set<Entitlement> = emptySet(),
    /** Active web entitlements from the latest redemption response. */
    val webEntitlements: Set<Entitlement> = emptySet(),
    /** Tracks all entitlements seen from status updates + product updates. */
    val allTracked: Set<Entitlement> = emptySet(),
) {
    // -- Derived properties --

    val all: Set<Entitlement>
        get() = allTracked + entitlementsByProduct.values.flatten() + webEntitlements

    val active: Set<Entitlement>
        get() =
            mergeEntitlementsPrioritized(
                (backingActive + activeDeviceEntitlements + webEntitlements).toList(),
            ).toSet()

    val inactive: Set<Entitlement>
        get() = all - active

    // -- Product ID lookup (pure, operates on current state) --

    internal fun byProductId(id: String): Set<Entitlement> {
        val decomposed = DecomposedProductIds.from(id)
        return checkFor(
            listOf(
                decomposed.fullId,
                "${decomposed.subscriptionId}:${decomposed.basePlanId ?: ""}:${decomposed.offerType.specificId ?: ""}",
                "${decomposed.subscriptionId}:${decomposed.basePlanId ?: ""}",
            ),
        ) ?: checkFor(
            listOf(
                "${decomposed.subscriptionId}:${decomposed.basePlanId ?: ""}:",
                decomposed.subscriptionId,
            ),
            isExact = false,
        ) ?: emptySet()
    }

    fun byProductIds(ids: Set<String>): Set<Entitlement> = ids.flatMap { byProductId(it) }.toSet()

    private fun checkFor(
        toCheck: List<String>,
        isExact: Boolean = true,
    ): Set<Entitlement>? {
        if (toCheck.isEmpty()) return null
        val item = toCheck.first()
        val next = toCheck.drop(1)
        return entitlementsByProduct.entries
            .firstOrNull {
                (if (isExact) it.key == item else it.key.contains(item)) &&
                    it.value.isNotEmpty()
            }?.value ?: checkFor(next, isExact)
    }

    // -----------------------------------------------------------------------
    // Pure state mutations — (EntitlementsState) -> EntitlementsState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (EntitlementsState) -> EntitlementsState,
    ) : Reducer<EntitlementsState> {
        data class SetActive(
            val entitlements: Set<Entitlement>,
        ) : Updates({ state ->
                state.copy(
                    status = SubscriptionStatus.Active(entitlements),
                    backingActive = state.backingActive + entitlements.filter { it.isActive },
                    allTracked = state.allTracked + entitlements,
                )
            })

        object SetInactive : Updates({ state ->
            state.copy(
                status = SubscriptionStatus.Inactive,
                activeDeviceEntitlements = emptySet(),
                backingActive = emptySet(),
            )
        })

        object SetUnknown : Updates({ state ->
            state.copy(
                status = SubscriptionStatus.Unknown,
                backingActive = emptySet(),
                activeDeviceEntitlements = emptySet(),
            )
        })

        data class AddProductEntitlements(
            val idToEntitlements: Map<String, Set<Entitlement>>,
        ) : Updates({ state ->
                val newProducts =
                    state.entitlementsByProduct +
                        idToEntitlements.mapValues { (_, v) -> v.toSet() }
                state.copy(
                    entitlementsByProduct = newProducts,
                    allTracked = newProducts.values.flatten().toSet(),
                )
            })

        data class SetDeviceEntitlements(
            val entitlements: Set<Entitlement>,
        ) : Updates({ state ->
                state.copy(activeDeviceEntitlements = entitlements)
            })

        data class SetWebEntitlements(
            val entitlements: Set<Entitlement>,
        ) : Updates({ state ->
                state.copy(webEntitlements = entitlements)
            })
    }

    // -----------------------------------------------------------------------
    // Actions — async work via EntitlementsContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend EntitlementsContext.() -> Unit,
    ) : TypedAction<EntitlementsContext>
}

/**
 * Builds initial EntitlementsState from storage BEFORE the actor starts.
 */
internal fun createInitialEntitlementsState(storage: Storage): EntitlementsState {
    val status =
        try {
            storage.read(StoredSubscriptionStatus)
        } catch (e: ClassCastException) {
            storage.delete(StoredSubscriptionStatus)
            null
        }

    val productEntitlements =
        try {
            storage.read(StoredEntitlementsByProductId)
        } catch (e: ClassCastException) {
            storage.delete(StoredEntitlementsByProductId)
            null
        }

    var state = EntitlementsState()

    // Replay status to populate backingActive/allTracked correctly
    if (status != null) {
        state =
            when (status) {
                is SubscriptionStatus.Active -> {
                    if (status.entitlements.isEmpty()) {
                        EntitlementsState.Updates.SetInactive.reduce(state)
                    } else {
                        EntitlementsState.Updates.SetActive(status.entitlements.toSet()).reduce(state)
                    }
                }
                is SubscriptionStatus.Inactive -> EntitlementsState.Updates.SetInactive.reduce(state)
                is SubscriptionStatus.Unknown -> state
            }
    }

    if (productEntitlements != null) {
        state = EntitlementsState.Updates.AddProductEntitlements(productEntitlements).reduce(state)
    }

    // Restore web entitlements from latest redemption response
    val webEntitlements =
        try {
            storage
                .read(LatestRedemptionResponse)
                ?.customerInfo
                ?.entitlements
                ?.filter { it.isActive }
                ?.toSet()
        } catch (_: Exception) {
            null
        }
    if (!webEntitlements.isNullOrEmpty()) {
        state = EntitlementsState.Updates.SetWebEntitlements(webEntitlements).reduce(state)
    }

    return state
}
