package com.superwall.sdk.billing.google

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.superwall.sdk.billing.PurchaseOptions
import com.superwall.sdk.billing.StoreProductKind
import com.superwall.sdk.store.ProductReplacement
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct

/**
 * The offer token Play needs to buy this product's selected offer, or `null` when Play must
 * get none. A one-time product without purchase options takes no token unless an offer was
 * asked for ([requestedOfferId]); Play rejects an empty token.
 */
internal fun RawStoreProduct.offerToken(requestedOfferId: String?): String? {
    val token =
        when (val offer = selectedOffer) {
            is RawStoreProduct.SelectedOfferDetails.Subscription -> offer.underlying.offerToken
            is RawStoreProduct.SelectedOfferDetails.OneTime ->
                if (offer.purchaseOptionId != null || requestedOfferId != null) offer.underlying.offerToken else null
            null -> null
        }
    return token?.takeIf { it.isNotEmpty() }
}

/** The Play params for buying this product's selected offer. */
internal fun RawStoreProduct.toProductDetailsParams(requestedOfferId: String?): BillingFlowParams.ProductDetailsParams =
    BillingFlowParams.ProductDetailsParams
        .newBuilder()
        .setProductDetails(underlyingProductDetails)
        .apply { offerToken(requestedOfferId)?.let { setOfferToken(it) } }
        .build()

/** Play's params for replacing a subscription; `null` without the old purchase token. */
internal fun ProductReplacement.toSubscriptionUpdateParams(): BillingFlowParams.SubscriptionUpdateParams? {
    val oldPurchaseToken = oldPurchaseToken ?: return null
    return BillingFlowParams.SubscriptionUpdateParams
        .newBuilder()
        .setOldPurchaseToken(oldPurchaseToken)
        .apply {
            // Without a mode, Play uses the one configured for the base plan in Play Console
            when (mode) {
                ProductReplacement.Mode.DEFAULT -> Unit
                ProductReplacement.Mode.CHARGE_PRORATED ->
                    setSubscriptionReplacementMode(
                        BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE,
                    )
                ProductReplacement.Mode.CHARGE_FULL_PRICE ->
                    setSubscriptionReplacementMode(
                        BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE,
                    )
            }
        }.build()
}

/** Play's params for a purchase flow buying [product] with [options]. */
internal fun billingFlowParamsOf(
    product: RawStoreProduct,
    options: PurchaseOptions,
): BillingFlowParams =
    BillingFlowParams
        .newBuilder()
        .apply { options.obfuscatedAccountId?.let { setObfuscatedAccountId(it) } }
        .setProductDetailsParamsList(listOf(product.toProductDetailsParams(product.offerId)))
        .apply { options.replacement?.toSubscriptionUpdateParams()?.let { setSubscriptionUpdateParams(it) } }
        .build()

/** Maps Play's product type string to the neutral kind. */
internal fun productKindOf(playProductType: String?): StoreProductKind =
    when (playProductType) {
        BillingClient.ProductType.SUBS -> StoreProductKind.Subscription
        BillingClient.ProductType.INAPP -> StoreProductKind.OneTime
        else -> StoreProductKind.Unknown
    }

/** What kind of product this is, from the Play details it wraps. */
internal val StoreProduct.kind: StoreProductKind
    get() = productKindOf(rawStoreProduct?.underlyingProductDetails?.productType)

/**
 * The price of each phase of this product's selected subscription offer, in micros and in
 * order. Empty for a one-time product or one without a selected offer.
 */
internal val StoreProduct.offerPhasePricesMicros: List<Long>
    get() =
        when (val offer = rawStoreProduct?.selectedOffer) {
            is RawStoreProduct.SelectedOfferDetails.Subscription ->
                offer.underlying.pricingPhases.pricingPhaseList
                    .map { it.priceAmountMicros }
            is RawStoreProduct.SelectedOfferDetails.OneTime, null -> emptyList()
        }
