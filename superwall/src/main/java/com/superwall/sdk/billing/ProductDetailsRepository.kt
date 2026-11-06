package com.superwall.sdk.billing

import com.superwall.sdk.store.abstractions.product.StoreProduct
import java.util.concurrent.ConcurrentHashMap

/**
 * The products one [GoogleBillingWrapper] has fetched from Google Play, keyed by full product id.
 *
 * It belongs to that wrapper instance, so a second configure (or a test) starts with an empty
 * cache instead of seeing another instance's products. A product Play didn't return is
 * remembered as missing for [MISSING_PRODUCT_TTL_MS]: until then it is left out of requests
 * instead of being queried again, and after that it is asked for again, so a product that goes
 * live is picked up. A missing product never fails a request for other products.
 */
// How long a product id that Play didn't return is skipped before we ask for it again.
// Short enough that a product going live is picked up quickly, long enough that a
// paywall referencing a stale id doesn't re-query it on every load.
internal const val MISSING_PRODUCT_TTL_MS = 5L * 60L * 1000L

internal class ProductDetailsRepository(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, CachedProduct>()

    /** The cached entry for [id], forgetting a missing one that is due to be asked for again. */
    private fun entry(id: String): CachedProduct? {
        val cached = cache[id]
        if (cached is CachedProduct.NotFound && now() - cached.missedAt >= MISSING_PRODUCT_TTL_MS) {
            cache.remove(id, cached)
            return null
        }
        return cached
    }

    /** What a request for [fullProductIds] can be answered with from the cache. */
    fun lookup(fullProductIds: Set<String>): ProductLookup {
        val found = mutableSetOf<StoreProduct>()
        val missing = mutableSetOf<String>()
        for (id in fullProductIds) {
            when (val cached = entry(id)) {
                is CachedProduct.Found -> found += cached.product
                // Skipped, not failed, so a missing id never breaks the other ids' load.
                is CachedProduct.NotFound -> Unit
                null -> missing += id
            }
        }
        return if (missing.isEmpty()) {
            ProductLookup.AllCached(found)
        } else {
            ProductLookup.Partial(cached = found, missingIds = missing)
        }
    }

    /** The ids among [fullProductIds] that Play recently didn't return. */
    fun knownMissing(fullProductIds: Set<String>): Set<String> =
        fullProductIds.filterTo(mutableSetOf()) { entry(it) is CachedProduct.NotFound }

    /**
     * Caches the result of a query for [requestedIds]: each of [products] as found, and every
     * requested id Play didn't return as missing.
     */
    fun store(
        requestedIds: Set<String>,
        products: Set<StoreProduct>,
    ) {
        val foundIds = products.map { it.fullIdentifier }.toSet()
        products.forEach { cache[it.fullIdentifier] = CachedProduct.Found(it) }
        val missedAt = now()
        (requestedIds - foundIds).forEach { id -> cache[id] = CachedProduct.NotFound(missedAt) }
    }

    fun clear() {
        cache.clear()
    }
}

/** One cache entry: a product Play returned, or an id it didn't. */
internal sealed interface CachedProduct {
    data class Found(
        val product: StoreProduct,
    ) : CachedProduct

    /** Play didn't return the id when asked at [missedAt]. */
    data class NotFound(
        val missedAt: Long,
    ) : CachedProduct
}

/** The answer [ProductDetailsRepository.lookup] gives for a set of product ids. */
internal sealed interface ProductLookup {
    /** Every id is cached as found. */
    data class AllCached(
        val products: Set<StoreProduct>,
    ) : ProductLookup

    /** Some ids still need a query to Play; [cached] are the ones already known. */
    data class Partial(
        val cached: Set<StoreProduct>,
        val missingIds: Set<String>,
    ) : ProductLookup
}
