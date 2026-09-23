package com.superwall.sdk.customercenter

import com.superwall.sdk.models.customer.NonSubscriptionTransaction
import com.superwall.sdk.models.customer.SubscriptionTransaction
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.product.Store

/** An action the user selected in the Customer Center. */
sealed class CustomerCenterAction {
    object Restore : CustomerCenterAction() {
        override fun toString() = "Restore"
    }

    object ManageSubscription : CustomerCenterAction() {
        override fun toString() = "ManageSubscription"
    }

    object Refund : CustomerCenterAction() {
        override fun toString() = "Refund"
    }

    object ChangePlan : CustomerCenterAction() {
        override fun toString() = "ChangePlan"
    }

    object ContactSupport : CustomerCenterAction() {
        override fun toString() = "ContactSupport"
    }

    data class Url(
        val url: String,
    ) : CustomerCenterAction()

    data class Custom(
        val identifier: String,
    ) : CustomerCenterAction()

    /** Snake-case name used in events. */
    internal val analyticsName: String
        get() =
            when (this) {
                Restore -> "restore"
                ManageSubscription -> "manage_subscription"
                Refund -> "refund"
                ChangePlan -> "change_plan"
                ContactSupport -> "contact_support"
                is Url -> "url"
                is Custom -> "custom"
            }

    internal companion object {
        fun from(pathType: CustomerCenterConfiguration.PathType): CustomerCenterAction =
            when (pathType) {
                CustomerCenterConfiguration.PathType.Restore -> Restore
                CustomerCenterConfiguration.PathType.ManageSubscription -> ManageSubscription
                is CustomerCenterConfiguration.PathType.Refund -> Refund
                is CustomerCenterConfiguration.PathType.ChangePlan -> ChangePlan
                CustomerCenterConfiguration.PathType.ContactSupport -> ContactSupport
                is CustomerCenterConfiguration.PathType.Url -> Url(pathType.url)
                is CustomerCenterConfiguration.PathType.Custom -> Custom(pathType.identifier)
            }
    }
}

/**
 * Outcome of a refund request made from the Customer Center.
 *
 * Google Play takes refund requests on its own pages, so the SDK can't see how one ends:
 * [SUCCESS] means the request was handed to Google Play, [ERROR] that it couldn't be.
 */
enum class CustomerCenterRefundStatus {
    SUCCESS,
    USER_CANCELLED,
    ERROR,
    ;

    internal val analyticsName: String
        get() =
            when (this) {
                SUCCESS -> "success"
                USER_CANCELLED -> "user_cancelled"
                ERROR -> "error"
            }
}

/** Which Customer Center screen was shown, reported on [com.superwall.sdk.analytics.superwall.SuperwallEvent.CustomerCenterOpen]. */
enum class CustomerCenterScreenType {
    /** The user has, or had, at least one purchase. */
    MANAGEMENT,

    /** The user has no purchases. */
    NO_PURCHASES,
    ;

    internal val analyticsName: String
        get() =
            when (this) {
                MANAGEMENT -> "management"
                NO_PURCHASES -> "no_purchases"
            }
}

/** The purchase a Customer Center action applies to. */
data class CustomerCenterPurchase(
    /**
     * The product purchased. `null` for an entitlement with no product behind it, such as a
     * manually granted one.
     */
    val productId: String?,
    /** Where the purchase was made. */
    val store: Store,
    /**
     * The entitlements the purchase unlocks, including any it no longer grants: for a purchase
     * with a transaction behind it these are every entitlement the product has ever unlocked, so
     * check [Entitlement.isActive] before treating one as current.
     */
    val entitlements: Set<Entitlement>,
    /** The subscription, when the purchase is one. */
    val subscription: SubscriptionTransaction? = null,
    /** The one-time purchase, when the purchase is one. */
    val nonSubscription: NonSubscriptionTransaction? = null,
)
