package com.superwall.sdk.customercenter

import com.superwall.sdk.customercenter.CustomerCenterConfiguration.PathType
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.store.abstractions.product.receipt.LatestPeriodType
import java.math.BigDecimal
import java.util.Date

internal data class PathResolutionContext(
    val purchase: PurchasePresentation?,
    val product: ProductDisplayInfo? = null,
    val supportEmailAvailable: Boolean,
    val webManagementUrl: String? = null,
    val canOpenUrls: Boolean = true,
    /**
     * `true` when resolving a screen's main action list (management / no-purchases), where
     * restore is always available; `false` when resolving a drilled-in purchase detail screen.
     */
    val isScreenLevel: Boolean = false,
    val now: Date = Date(),
)

internal sealed class ResolvedPathDestination {
    object Restore : ResolvedPathDestination()

    /** Google Play's own page for the subscription, where it can be cancelled. */
    data class PlayStoreManage(
        val productId: String,
    ) : ResolvedPathDestination()

    data class WebManage(
        val url: String,
    ) : ResolvedPathDestination()

    /**
     * A web-store subscription with no management page configured. There's nowhere to send the
     * customer, so the row explains where to find the link instead of disappearing and leaving
     * them with no way to manage a subscription they're paying for.
     */
    object WebManageUnavailable : ResolvedPathDestination()

    /** Google Play takes refund requests on its order history page. */
    data class Refund(
        val productId: String,
    ) : ResolvedPathDestination()

    /** Google Play's page for the subscription, where the customer can switch plans. */
    data class ChangePlan(
        val productId: String,
        val productIds: List<String>?,
    ) : ResolvedPathDestination()

    object ContactSupport : ResolvedPathDestination()

    data class Url(
        val url: String,
        val inApp: Boolean,
    ) : ResolvedPathDestination()

    data class Custom(
        val identifier: String,
    ) : ResolvedPathDestination()

    /**
     * Whether this destination hands the customer off to a web management page — or explains that
     * there isn't one. Surveys are skipped for these: the survey gates an action, and here the
     * action either leaves the app entirely or can't be performed at all.
     */
    val isWebManagement: Boolean
        get() = this is WebManage || this is WebManageUnavailable
}

internal data class ResolvedPath(
    val path: CustomerCenterConfiguration.Path,
    val destination: ResolvedPathDestination,
) {
    val id: String get() = path.id
}

internal object CustomerCenterPathResolver {
    fun resolve(
        paths: List<CustomerCenterConfiguration.Path>,
        context: PathResolutionContext,
    ): List<ResolvedPath> =
        paths.mapNotNull { path ->
            destination(path, context)?.let { ResolvedPath(path, it) }
        }

    private fun manageSubscriptionDestination(context: PathResolutionContext): ResolvedPathDestination? {
        val purchase = context.purchase ?: return null
        val sub = purchase.subscription

        if (purchase.store == Store.PLAY_STORE) {
            // Google Play doesn't report an expiration date for a subscription on the device, so
            // unlike the other stores there's no date to require here.
            if (sub == null || !sub.isActive || !sub.willRenew || sub.isRevoked) return null
            return ResolvedPathDestination.PlayStoreManage(sub.productId)
        }

        if (purchase.store !in webStores) return null

        // A one-off purchase has nothing to manage, and neither has a subscription that has lapsed
        // or been revoked.
        when (val kind = purchase.kind) {
            is PurchaseKind.NonSubscription -> return null
            is PurchaseKind.Subscription -> if (kind.transaction.isRevoked) return null
            is PurchaseKind.EntitlementOnly -> Unit
        }
        if (!purchase.isActive) return null

        // A comped grant — an entitlement with no transaction *and* no store behind it — has
        // nothing to manage anywhere, so offer the page only if one exists and never claim a
        // receipt was sent. The signal is the null store, not the missing transaction: a web
        // purchase arrives as a bare entitlement whenever the backend sends no matching
        // transaction, and that entitlement still carries its store.
        val kind = purchase.kind
        if (kind is PurchaseKind.EntitlementOnly && kind.entitlement.store == null) {
            return context.webManagementUrl?.let { ResolvedPathDestination.WebManage(it) }
        }
        return context.webManagementUrl?.let { ResolvedPathDestination.WebManage(it) }
            ?: ResolvedPathDestination.WebManageUnavailable
    }

    private fun destination(
        path: CustomerCenterConfiguration.Path,
        context: PathResolutionContext,
    ): ResolvedPathDestination? {
        val purchase = context.purchase
        val sub = purchase?.subscription
        val isPlayStore = purchase?.store == Store.PLAY_STORE

        return when (val type = path.type) {
            // Restore is always available at screen level; it's only hidden on drilled-in
            // purchase detail screens.
            PathType.Restore ->
                if (purchase == null || context.isScreenLevel) ResolvedPathDestination.Restore else null

            PathType.ContactSupport ->
                if (context.supportEmailAvailable && context.canOpenUrls) ResolvedPathDestination.ContactSupport else null

            is PathType.Url -> {
                if (!context.canOpenUrls) return null
                val isWeb = CustomerCenterUrls.scheme(type.url) in setOf("http", "https")
                ResolvedPathDestination.Url(type.url, inApp = type.openMethod == CustomerCenterConfiguration.OpenMethod.IN_APP && isWeb)
            }

            is PathType.Custom -> ResolvedPathDestination.Custom(type.identifier)

            PathType.ManageSubscription -> manageSubscriptionDestination(context)

            is PathType.Refund -> {
                if (!isPlayStore || sub == null || sub.isRevoked || sub.offerType == LatestPeriodType.TRIAL) return null
                val price = context.product?.price
                if (price != null && price <= BigDecimal.ZERO) return null
                val window = type.windowMillis
                if (window != null && sub.purchaseDate.time + window < context.now.time) return null
                ResolvedPathDestination.Refund(sub.productId)
            }

            is PathType.ChangePlan -> {
                if (!isPlayStore || sub == null || !sub.isActive || sub.isRevoked) return null
                if (purchase?.badge == PurchaseBadge.LIFETIME) return null
                if (context.product?.isAutoRenewable == false) return null
                ResolvedPathDestination.ChangePlan(sub.productId, type.productIds)
            }
        }
    }
}

internal object PlayStoreLinks {
    /**
     * Google Play's page for one subscription. Play identifies the subscription by its product
     * id, without any base plan or offer the SDK's full identifier carries.
     */
    fun subscription(
        productId: String,
        packageName: String,
    ): String = "https://play.google.com/store/account/subscriptions?sku=${productId.substringBefore(':')}&package=$packageName"

    /** Where Google Play takes refund requests. */
    const val ORDER_HISTORY = "https://play.google.com/store/account/orderhistory"

    fun appListing(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"
}

/** Why a detail screen has no actions, when it has none. */
internal sealed class DetailEmptyState {
    /**
     * Nothing is left to do: the subscription is revoked or lapsed, or a comped grant has no page
     * to send anyone to.
     */
    object NothingToDo : DetailEmptyState()

    /**
     * The customer is still paying for this, from a store this SDK can't drive — the App Store on
     * an Android client, or a developer's own system. [storeLabelKey] names the store when there
     * is a name for it.
     */
    data class ManagedElsewhere(
        val storeLabelKey: String?,
    ) : DetailEmptyState()
}

internal object DetailEmptyStateResolver {
    /** `null` when the detail screen has actions to show; otherwise which sentence to show instead. */
    fun resolve(
        purchase: PurchasePresentation,
        hasActions: Boolean,
    ): DetailEmptyState? {
        if (hasActions) return null
        // A lifetime grant is excluded on purpose: nothing renews, so there is nothing to manage
        // anywhere.
        val isLive = purchase.isActive && purchase.badge != PurchaseBadge.REVOKED && purchase.badge != PurchaseBadge.LIFETIME
        if (!isLive || purchase.store in drivableStores) return DetailEmptyState.NothingToDo
        // Only a store with a real name fills the sentence. `OTHER` and `CUSTOM` carry the label
        // "Other", and "manage this subscription through Other" is worse than the generic line.
        return when (purchase.store) {
            Store.APP_STORE -> DetailEmptyState.ManagedElsewhere(purchase.storeLabelKey)
            else -> DetailEmptyState.ManagedElsewhere(null)
        }
    }
}
