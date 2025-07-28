package com.superwall.sdk.models.customer

import com.superwall.sdk.models.entitlements.Entitlement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A class that contains the latest subscription and entitlement info about the customer.
 * These objects are non-mutable and do not update automatically.
 */
@Serializable
data class CustomerInfo(
    /** The subscription transactions the user has made. The transactions are
     * ordered by purchase date in ascending order. */
    @SerialName("subscriptions")
    val subscriptions: List<SubscriptionTransaction>,
    /** The non-subscription transactions the user has made. The transactions are
     * ordered by purchase date in ascending order. */
    @SerialName("nonSubscriptions")
    val nonSubscriptions: List<NonSubscriptionTransaction>,
    /** The ID of the user. Equivalent to Superwall.userId. */
    @SerialName("userId")
    val userId: String,
    /** All entitlements available to the user. */
    @SerialName("entitlements")
    val entitlements: List<Entitlement>,
    /** Internally set to `true` on first ever load of CustomerInfo. */
    @SerialName("isBlank")
    internal val isBlank: Boolean = false,
) {
    /** A Set of the product identifiers for the active subscriptions. */
    @Transient
    val activeSubscriptionProductIds: Set<String>
        get() =
            subscriptions
                .filter { it.isActive }
                .map { it.productId }
                .toSet()

    companion object {
        /**
         * Creates a blank CustomerInfo instance for testing or default states.
         */
        fun empty(): CustomerInfo =
            CustomerInfo(
                subscriptions = emptyList(),
                nonSubscriptions = emptyList(),
                userId = "",
                entitlements = emptyList(),
                isBlank = true,
            )
    }
}
