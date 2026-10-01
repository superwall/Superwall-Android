package com.superwall.sdk.store

import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.product.Store
import java.util.Date

/**
 * Works out what the subscription status should be when a Play Billing read
 * produced no entitlements.
 *
 * An empty read can mean three different things, and only some of them may
 * demote a subscriber:
 *
 * - The queries failed: the billing client was never ready, the query timed
 *   out, or the retries ran out. Play told us nothing, so this is not an
 *   answer and may not demote anyone.
 * - The queries succeeded and came back with no purchases. Play lists every
 *   active purchase it knows about, so this is an answer and demotes
 *   straight away.
 * - The queries came back with an active purchase that maps to no
 *   entitlement. Config no longer knows that product, so this is a mapping
 *   failure, not an answer about the entitlement the product unlocks.
 *
 * On a non-answer we keep the current `Active` status while one of its
 * entitlements is still inside its own expiry date. Two separate questions
 * are being asked of each entitlement. Whether it can hold the status up
 * needs an expiry date the read can be bounded by. Whether it stays in the
 * status needs two things: the read had no authority over it or confirmed
 * it, and its own expiry hasn't passed. A lifetime unlock has no expiry so
 * it can't hold the status up, but an empty read said nothing about it
 * either, so it stays while another entitlement holds. An entitlement the
 * read refuted is dropped, and one whose expiry is already behind us is
 * dropped as well, because time passing needs no read to confirm it.
 *
 * Web entitlements are merged back in from the redeem cache by
 * `Superwall.internallySetSubscriptionStatus`, which is authoritative for
 * them, so a lapsed web record comes straight back until the web poll says
 * otherwise.
 *
 * @param currentStatus The status as it stands, before this read is applied.
 * @param deviceRecords The entitlements from the stored device
 * `CustomerInfo`. The status carries config-shaped entitlements, which have
 * no expiry date and often no store, so the dates come from here.
 * @param activeProductIds The product ids of the purchases the read reported
 * as purchased.
 * @param readReturnedPurchases Whether the read came back with any purchases
 * at all.
 * @param readFailed Whether either query failed after its retries.
 */
internal fun resolveStatusForEmptyRead(
    currentStatus: SubscriptionStatus,
    deviceRecords: Set<Entitlement>,
    activeProductIds: Set<String>,
    readReturnedPurchases: Boolean,
    readFailed: Boolean,
    now: Date = Date(),
): SubscriptionStatus {
    // A read that worked and found nothing is an answer, so let it demote.
    if (!readFailed && !readReturnedPurchases) {
        return SubscriptionStatus.Inactive
    }
    if (currentStatus !is SubscriptionStatus.Active) {
        return SubscriptionStatus.Inactive
    }

    val recordsById = deviceRecords.associateBy { it.id }

    // Only fill in what the entitlement doesn't already state, so a web
    // entitlement keeps its own store and expiry date even when a device
    // record happens to share its id.
    fun expiryOf(entitlement: Entitlement): Date? = entitlement.expiresAt ?: recordsById[entitlement.id]?.expiresAt

    fun storeOf(entitlement: Entitlement): Store? = entitlement.store ?: recordsById[entitlement.id]?.store

    fun isLapsed(entitlement: Entitlement): Boolean {
        val expiresAt = expiryOf(entitlement) ?: return false
        return !expiresAt.after(now)
    }

    fun isRefuted(entitlement: Entitlement): Boolean {
        // A failed read refutes nothing, and a read of Play purchases has no
        // authority over entitlements granted anywhere else. A null store is
        // one we can't place, so it is left alone too.
        if (readFailed || storeOf(entitlement) != Store.PLAY_STORE) {
            return false
        }
        // A still-purchased product that unlocks this entitlement means the
        // empty entitlement set is a mapping failure rather than an answer.
        return entitlement.productIds.none { it in activeProductIds }
    }

    val holdsStatus =
        currentStatus.entitlements.any {
            it.isActive && expiryOf(it) != null && !isLapsed(it) && !isRefuted(it)
        }
    if (!holdsStatus) {
        return SubscriptionStatus.Inactive
    }

    // The entitlement that holds the status always survives, so this is never
    // empty.
    val survivors = currentStatus.entitlements.filterNot { isRefuted(it) || isLapsed(it) }.toSet()
    return SubscriptionStatus.Active(survivors)
}
