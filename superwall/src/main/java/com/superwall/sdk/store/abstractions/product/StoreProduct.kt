package com.superwall.sdk.store.abstractions.product

import com.superwall.sdk.models.product.Offer
import com.superwall.sdk.store.testmode.models.SuperwallProductPlatform
import kotlinx.serialization.Serializable
import java.util.*

/**
 * Selection type for base plan/purchase option.
 * When [Auto], the SDK will automatically select the best base plan.
 * When [Specific], the SDK will use the exact base plan ID provided.
 */
@Serializable
sealed class BasePlanType {
    /** Auto-select the best base plan */
    object Auto : BasePlanType()

    /** Use a specific base plan by ID */
    data class Specific(
        val id: String,
    ) : BasePlanType()

    /** Returns the ID if this is a Specific selection, null otherwise */
    val specificId: String?
        get() = (this as? Specific)?.id

    companion object {
        /** Parse a string value - "sw-auto"/null/empty means Auto, otherwise Specific */
        fun from(value: String?): BasePlanType =
            when {
                value.isNullOrEmpty() || value == "sw-auto" -> Auto
                else -> Specific(value)
            }
    }
}

/**
 * Selection type for offer selection.
 * When [Auto], the SDK will automatically select the best offer.
 * When [None], no offer will be selected (use base plan/option only).
 * When [Specific], the SDK will use the exact offer ID provided.
 */
@Serializable
sealed class OfferType {
    /** Auto-select the best offer (e.g., longest free trial or cheapest) */
    object Auto : OfferType()

    /** Don't select any offer - use base plan/option only */
    object None : OfferType()

    /** Use a specific offer by ID */
    data class Specific(
        val id: String,
    ) : OfferType()

    /** Returns the ID if this is a Specific selection, null otherwise */
    val specificId: String?
        get() = (this as? Specific)?.id

    /** Converts to the Offer type used in ProductItem */
    fun toOffer(): Offer =
        when (this) {
            is Auto -> Offer.Automatic()
            is None -> Offer.NoOffer
            is Specific -> Offer.Specified(offerIdentifier = id)
        }

    companion object {
        /** Parse a string value - "sw-auto"/null/empty means Auto, "sw-none" means None, otherwise Specific */
        fun from(value: String?): OfferType =
            when {
                value.isNullOrEmpty() || value == "sw-auto" -> Auto
                value == "sw-none" -> None
                else -> Specific(value)
            }
    }
}

class StoreProduct private constructor(
    val rawStoreProduct: RawStoreProduct?,
    private val backingProduct: StoreProductType,
) : StoreProductType by backingProduct {
    constructor(rawStoreProduct: RawStoreProduct) : this(rawStoreProduct, rawStoreProduct)

    /**
     * Whether this is a custom (store == CUSTOM) product — an API-backed product whose
     * platform is `custom`, purchased through the developer's PurchaseController rather
     * than Google Play Billing.
     */
    val isCustomProduct: Boolean
        get() = (backingProduct as? ApiStoreProduct)?.platform == SuperwallProductPlatform.CUSTOM

    constructor(storeProductType: StoreProductType) : this(null, storeProductType)

    /**
     * Pre-generated transaction id for the current custom-product (store == CUSTOM) purchase,
     * exposed so the developer can read it inside `purchase(activity, product, ...)`. Regenerated on
     * each attempt. Because custom instances are cached and shared, this field is only reliable
     * within a single non-overlapping flow; analytics use a local copy so they stay correct.
     */
    var customTransactionId: String? = null

    companion object {
        /** Builds a StoreProduct backed by an ApiStoreProduct (custom or test-mode product). */
        fun custom(apiStoreProduct: ApiStoreProduct): StoreProduct =
            StoreProduct(
                rawStoreProduct = null,
                backingProduct = apiStoreProduct,
            )
    }
}
