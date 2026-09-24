package com.superwall.sdk.customercenter

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.android.billingclient.api.BillingClient
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.store.abstractions.product.ApiStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallProduct
import com.superwall.sdk.store.testmode.models.SuperwallProductPlatform
import com.superwall.sdk.store.testmode.models.SuperwallProductsResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.Date
import java.util.Locale

internal interface CustomerCenterCustomerInfoProviding {
    suspend fun fetchCustomerInfo(): CustomerInfo

    /**
     * Reloads purchases from Google Play before returning fresh customer info. Use after the
     * customer comes back from Google Play's subscription pages: changes made there aren't pushed
     * to the app, so a cached read would miss them.
     */
    suspend fun refreshPurchases(): CustomerInfo

    /** Customer info as it changes, not including the current value. */
    val updates: Flow<CustomerInfo>
}

internal interface CustomerCenterProductsProviding {
    /** What can be had without waiting on the catalogue: Google Play, plus the catalogue if cached. */
    suspend fun products(ids: Set<String>): Map<String, ProductDisplayInfo>

    /**
     * Fills products Google Play can't resolve, such as web purchases, from the Superwall
     * catalogue. May make a network request.
     */
    suspend fun catalogueProducts(ids: Set<String>): Map<String, ProductDisplayInfo>
}

internal interface CustomerCenterRestoring {
    suspend fun restorePurchases(): RestorationResult
}

internal interface CustomerCenterUrlOpening {
    val canOpenUrls: Boolean

    /** Opens [url], in a Custom Tab when [inApp]. Returns `false` when nothing could handle it. */
    fun open(
        url: String,
        inApp: Boolean = false,
    ): Boolean
}

internal interface CustomerCenterEventTracking {
    suspend fun track(event: TrackableSuperwallEvent)
}

internal interface CustomerCenterEnvironmentProviding {
    val appVersion: String
    val osVersion: String
    val deviceModel: String
    val sdkVersion: String
    val userId: String
    val isSandbox: Boolean
    val packageName: String
    val webManagementUrl: String?
    val originalDownloadDate: Date?
    val locale: Locale
}

internal data class CustomerCenterDependencies(
    val customerInfo: CustomerCenterCustomerInfoProviding,
    val products: CustomerCenterProductsProviding,
    val restore: CustomerCenterRestoring,
    val urlOpener: CustomerCenterUrlOpening,
    val tracker: CustomerCenterEventTracking,
    val environment: CustomerCenterEnvironmentProviding,
) {
    companion object {
        fun live(
            container: DependencyContainer,
            configuration: CustomerCenterConfiguration,
            activity: () -> Activity?,
        ): CustomerCenterDependencies =
            CustomerCenterDependencies(
                customerInfo = LiveCustomerInfoProvider(container),
                products = LiveProductsProvider(container),
                restore = LiveRestorer,
                urlOpener = LiveUrlOpener(container.context, activity),
                tracker = LiveEventTracker,
                environment = LiveEnvironment(container, configuration.support.webManagementUrl),
            )
    }
}

// region Live adapters

private class LiveCustomerInfoProvider(
    private val container: DependencyContainer,
) : CustomerCenterCustomerInfoProviding {
    override suspend fun fetchCustomerInfo(): CustomerInfo {
        val current = Superwall.instance.customerInfo
        // The first read after launch can still be the placeholder. Give the real value a moment
        // to arrive rather than rendering "no purchases" for a customer who has some.
        return withTimeoutOrNull(CUSTOMER_INFO_TIMEOUT_MS) { current.first { !it.isPlaceholder } }
            ?: current.value
    }

    override suspend fun refreshPurchases(): CustomerInfo {
        container.storeManager.loadPurchasedProducts(container.entitlements.entitlementsByProductId)
        return fetchCustomerInfo()
    }

    override val updates: Flow<CustomerInfo>
        get() = Superwall.instance.customerInfo.drop(1)

    private companion object {
        const val CUSTOMER_INFO_TIMEOUT_MS = 5_000L
    }
}

private class LiveProductsProvider(
    private val container: DependencyContainer,
) : CustomerCenterProductsProviding {
    override suspend fun products(ids: Set<String>): Map<String, ProductDisplayInfo> {
        if (ids.isEmpty()) return emptyMap()
        val fetched =
            Superwall.instance
                .getProducts(*ids.toTypedArray())
                .getOrNull()
                .orEmpty()
        val resolved =
            ids
                .mapNotNull { id ->
                    val product = fetched[id] ?: fetched.values.firstOrNull { it.productIdentifier == id }
                    product?.let { id to ProductDisplayInfo.from(id, it) }
                }.toMap()
        // A catalogue fetched earlier in the visit fills web products straight away, so a second
        // load doesn't flash placeholders for details it already has.
        val cached = CatalogueCache.shared.freshResponse() ?: return resolved
        return fillingGaps(resolved, ids, cached.data)
    }

    override suspend fun catalogueProducts(ids: Set<String>): Map<String, ProductDisplayInfo> {
        if (ids.isEmpty()) return emptyMap()
        val response =
            try {
                CatalogueCache.shared.products {
                    // Bounded deliberately: the cards show placeholders until this answers.
                    withTimeoutOrNull(CATALOGUE_TIMEOUT_MS) {
                        when (val result = container.network.getSuperwallProducts()) {
                            is Either.Success -> result.value
                            is Either.Failure -> throw result.error
                        }
                    } ?: throw IllegalStateException("Timed out loading Superwall products.")
                }
            } catch (e: Throwable) {
                // Advisory: the cards still render, just without a price.
                Logger.debug(
                    LogLevel.warn,
                    LogScope.customerCenter,
                    "Couldn't load Superwall products, so web purchases will show without a price.",
                    error = e,
                )
                return emptyMap()
            }
        return fillingGaps(emptyMap(), ids, response.data)
    }

    companion object {
        /** How long the catalogue gets before the screen gives up on prices and renders without them. */
        const val CATALOGUE_TIMEOUT_MS = 5_000L
    }
}

/**
 * Fills the products Google Play couldn't resolve from the Superwall catalogue.
 *
 * The gap is every id Google Play didn't return, which includes Play products whenever a billing
 * lookup fails. Filling those from the catalogue would quote the dashboard's price instead of what
 * the customer is actually charged, so this is restricted to products Google Play was never going
 * to resolve.
 */
internal fun fillingGaps(
    resolved: Map<String, ProductDisplayInfo>,
    requested: Set<String>,
    catalogue: List<SuperwallProduct>,
): Map<String, ProductDisplayInfo> {
    val missing = requested - resolved.keys
    if (missing.isEmpty()) return resolved
    val filled = resolved.toMutableMap()
    for (product in catalogue) {
        if (product.identifier !in missing || product.platform == SuperwallProductPlatform.ANDROID) continue
        val storeProduct = StoreProduct(ApiStoreProduct(product))
        filled[product.identifier] = ProductDisplayInfo.from(product.identifier, storeProduct, name = product.name)
    }
    return filled
}

/**
 * @param name A display name from outside Google Play — the Superwall catalogue, for a web product
 *   Google Play can't resolve. Ignored when `null` or empty.
 */
internal fun ProductDisplayInfo.Companion.from(
    productId: String,
    product: StoreProduct,
    name: String? = null,
): ProductDisplayInfo {
    val details = product.rawStoreProduct?.underlyingProductDetails
    val displayName = name?.takeIf { it.isNotEmpty() } ?: details?.name?.takeIf { it.isNotEmpty() }
    return ProductDisplayInfo(
        productId = productId,
        title = displayName,
        localizedPrice = product.localizedPrice,
        price = product.price,
        localizedPeriod = if (product.subscriptionPeriod == null) null else product.period.takeIf { it.isNotEmpty() },
        isAutoRenewable = details?.let { it.productType == BillingClient.ProductType.SUBS },
    )
}

/**
 * Holds the Superwall catalogue for a short while.
 *
 * Products are refetched on load, on every restore, and each time the customer comes back from a
 * store page — so without this a web-store customer pays for the catalogue several times inside one
 * visit, for a product list that changes on the dashboard's timescale rather than the customer's.
 */
internal class CatalogueCache(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: Pair<SuperwallProductsResponse, Long>? = null

    /** The fetch in progress, if any, so a caller arriving mid-flight shares it. */
    private var inFlight: CompletableDeferred<SuperwallProductsResponse>? = null

    /**
     * Returns the cached catalogue when it is still fresh, otherwise awaits [fetch] and keeps it.
     * A throwing [fetch] is not cached, and every caller sharing that flight sees the same error.
     */
    suspend fun products(fetch: suspend () -> SuperwallProductsResponse): SuperwallProductsResponse {
        val (deferred, isOwner) =
            mutex.withLock {
                cached?.let { (response, at) -> if (isFresh(at)) return response }
                inFlight?.let { return@withLock it to false }
                val created = CompletableDeferred<SuperwallProductsResponse>()
                inFlight = created
                created to true
            }
        if (!isOwner) return deferred.await()
        try {
            val response = fetch()
            mutex.withLock {
                cached = response to now()
                inFlight = null
            }
            deferred.complete(response)
            return response
        } catch (e: Throwable) {
            mutex.withLock { inFlight = null }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    /** The cached catalogue if it's still fresh, without fetching. */
    suspend fun freshResponse(): SuperwallProductsResponse? =
        mutex.withLock {
            cached?.takeIf { isFresh(it.second) }?.first
        }

    private fun isFresh(at: Long): Boolean = now() - at < TTL_MS

    companion object {
        const val TTL_MS = 5 * 60 * 1_000L
        val shared = CatalogueCache()
    }
}

private object LiveRestorer : CustomerCenterRestoring {
    override suspend fun restorePurchases(): RestorationResult =
        Superwall.instance.dependencyContainer.transactionManager.tryToRestorePurchases(
            paywallView = null,
            presentsFailureAlert = false,
        )
}

private class LiveUrlOpener(
    context: Context,
    private val activity: () -> Activity?,
) : CustomerCenterUrlOpening {
    private val appContext = context.applicationContext

    override val canOpenUrls: Boolean = true

    override fun open(
        url: String,
        inApp: Boolean,
    ): Boolean {
        val host = activity()
        val uri = Uri.parse(url)
        return try {
            if (inApp && host != null) {
                CustomTabsIntent.Builder().build().launchUrl(host, uri)
            } else {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (host != null) {
                    host.startActivity(intent)
                } else {
                    appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            true
        } catch (e: ActivityNotFoundException) {
            Logger.debug(LogLevel.warn, LogScope.customerCenter, "Nothing on this device can open $url.")
            false
        } catch (e: Throwable) {
            Logger.debug(LogLevel.warn, LogScope.customerCenter, "Couldn't open $url.", error = e)
            false
        }
    }
}

private object LiveEventTracker : CustomerCenterEventTracking {
    override suspend fun track(event: TrackableSuperwallEvent) {
        Superwall.instance.track(event)
    }
}

private class LiveEnvironment(
    private val container: DependencyContainer,
    private val webManagementOverride: String?,
) : CustomerCenterEnvironmentProviding {
    override val appVersion: String get() = container.deviceHelper.appVersion
    override val osVersion: String get() = container.deviceHelper.osVersion
    override val deviceModel: String get() = container.deviceHelper.model
    override val sdkVersion: String get() = container.deviceHelper.sdkVersion
    override val userId: String get() = Superwall.instance.userId
    override val isSandbox: Boolean get() = container.makeIsSandbox()
    override val packageName: String get() = container.context.packageName
    override val webManagementUrl: String?
        get() = WebManagementUrlResolver.resolve(webManagementOverride, container.restoreUrl())
    override val originalDownloadDate: Date? get() = container.deviceHelper.appInstallDateValue
    override val locale: Locale
        get() =
            container.context.resources.configuration.locales
                .get(0) ?: Locale.getDefault()
}

// endregion

/** Holds a reference that doesn't keep an activity alive. */
internal class ActivityReference {
    private var ref: WeakReference<Activity>? = null

    fun set(activity: Activity?) {
        ref = activity?.let(::WeakReference)
    }

    fun get(): Activity? = ref?.get()
}
