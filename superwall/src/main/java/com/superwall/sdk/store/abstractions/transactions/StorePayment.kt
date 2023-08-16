package com.superwall.sdk.store.abstractions.transactions

import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails


// SW-2212: [android] [v0] make sure the productIdentifier is properly propagated
// https://linear.app/superwall/issue/SW-2212/%5Bandroid%5D-%5Bv0%5D-make-sure-the-productidentifier-is-properly-propagated

@kotlinx.serialization.Serializable
class StorePayment(
    // TODO: Make sure productIdentifier is propogated correctly
//    val productIdentifier: String,
    val quantity: Int,
    val discountIdentifier: String? // This may not have a direct equivalent in Android
) {

    constructor(purchase: Purchase) : this(
//        productIdentifier,
        quantity = 1, // Quantity is typically 1 for in-app products, but this may vary depending on your setup
        discountIdentifier = null // Adjust as needed for Android
    )
}
