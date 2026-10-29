package com.superwall.sdk.store

/**
 * Describes an existing subscription that a purchase replaces, instead of the
 * purchase being bought alongside it.
 *
 * A paywall's Purchase action sets this when it's configured to replace the user's
 * subscription. The SDK only passes it on when the user has an active subscription
 * that shares an entitlement with the product being purchased; otherwise the purchase
 * is a normal one.
 *
 * @property mode How the store should charge for the switch.
 * @property oldProductId The identifier of the product being replaced, if known. For
 * Google Play products this is the subscription ID of the purchase being replaced.
 * @property oldPurchaseToken The Google Play purchase token of the subscription being
 * replaced, used as `SubscriptionUpdateParams.oldPurchaseToken`. Null for custom
 * store products.
 */
data class ProductReplacement(
    val mode: Mode,
    val oldProductId: String?,
    val oldPurchaseToken: String?,
) {
    enum class Mode(
        val rawName: String,
    ) {
        /** Let the store decide, using the replacement mode configured in Google Play Console. */
        DEFAULT("default"),

        /**
         * Replace it now and charge the prorated price difference immediately. Google Play
         * only supports this for upgrades, where the price per time unit increases.
         */
        CHARGE_PRORATED("charge_prorated"),

        /** Replace it now and charge the full price of the new product immediately. */
        CHARGE_FULL_PRICE("charge_full_price"),
        ;

        companion object {
            fun fromRawName(rawName: String?): Mode? = entries.firstOrNull { it.rawName == rawName }
        }
    }
}
