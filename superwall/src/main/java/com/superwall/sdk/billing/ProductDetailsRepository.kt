package com.superwall.sdk.billing

import com.superwall.sdk.store.abstractions.product.StoreProduct
import java.util.concurrent.ConcurrentHashMap

/**
 * The products one [GoogleBillingWrapper] has fetched from Google Play, keyed by full product id.
 *
 * It belongs to that wrapper instance, so a second configure (or a test) starts with an empty
 * cache instead of seeing another instance's products. A product Play didn't return is cached
 * as missing, so asking for it again fails fast without another query.
 */
internal class ProductDetailsRepository {
    private val cache = ConcurrentHashMap<String, CachedProduct>()

    /** What a request for [fullProductIds] can be answered with from the cache. */
    fun lookup(fullProductIds: Set<String>): ProductLookup {
        val found = mutableSetOf<StoreProduct>()
        val missing = mutableSetOf<String>()
        for (id in fullProductIds) {
            when (val cached = cache[id]) {
                is CachedProduct.Found -> found += cached.product
                is CachedProduct.NotFound -> return ProductLookup.KnownMissing(cached.error)
                null -> missing += id
            }
        }
        return if (missing.isEmpty()) {
            ProductLookup.AllCached(found)
        } else {
            ProductLookup.Partial(cached = found, missingIds = missing)
        }
    }

    /** The ids among [fullProductIds] that Play was already asked for and didn't return. */
    fun knownMissing(fullProductIds: Set<String>): Set<String> =
        fullProductIds.filterTo(mutableSetOf()) { cache[it] is CachedProduct.NotFound }

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
        (requestedIds - foundIds).forEach { id ->
            cache[id] =
                CachedProduct.NotFound(Exception("Failed to query product details for $id"))
        }
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

    data class NotFound(
        val error: Throwable,
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

    /** An id was already queried and Play didn't return it. */
    data class KnownMissing(
        val error: Throwable,
    ) : ProductLookup
}
