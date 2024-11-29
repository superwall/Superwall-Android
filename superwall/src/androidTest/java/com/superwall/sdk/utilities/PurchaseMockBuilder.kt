package com.superwall.sdk.utilities

import com.android.billingclient.api.Purchase
import org.json.JSONArray
import org.json.JSONObject

class PurchaseMockBuilder {
    private val purchaseJson = JSONObject()

    fun setPurchaseState(state: Int): PurchaseMockBuilder {
        purchaseJson.put("purchaseState", if (state == 2) 4 else state)
        return this
    }

    fun setPurchaseTime(time: Long): PurchaseMockBuilder {
        purchaseJson.put("purchaseTime", time)
        return this
    }

    fun setOrderId(orderId: String?): PurchaseMockBuilder {
        purchaseJson.put("orderId", orderId)
        return this
    }

    fun setProductId(productId: String?): PurchaseMockBuilder {
        val productIds = JSONArray()
        productIds.put(productId)
        purchaseJson.put("productIds", productIds)
        // For backward compatibility
        purchaseJson.put("productId", productId)
        return this
    }

    fun setQuantity(quantity: Int): PurchaseMockBuilder {
        purchaseJson.put("quantity", quantity)
        return this
    }

    fun setPurchaseToken(token: String?): PurchaseMockBuilder {
        purchaseJson.put("token", token)
        purchaseJson.put("purchaseToken", token)
        return this
    }

    fun setPackageName(packageName: String?): PurchaseMockBuilder {
        purchaseJson.put("packageName", packageName)
        return this
    }

    fun setDeveloperPayload(payload: String?): PurchaseMockBuilder {
        purchaseJson.put("developerPayload", payload)
        return this
    }

    fun setAcknowledged(acknowledged: Boolean): PurchaseMockBuilder {
        purchaseJson.put("acknowledged", acknowledged)
        return this
    }

    fun setAutoRenewing(autoRenewing: Boolean): PurchaseMockBuilder {
        purchaseJson.put("autoRenewing", autoRenewing)
        return this
    }

    fun setAccountIdentifiers(
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
    ): PurchaseMockBuilder {
        purchaseJson.put("obfuscatedAccountId", obfuscatedAccountId)
        purchaseJson.put("obfuscatedProfileId", obfuscatedProfileId)
        return this
    }

    fun build(): Purchase = Purchase(purchaseJson.toString(), "dummy-signature")

    companion object {
        fun createDefaultPurchase(id: String): Purchase =
            PurchaseMockBuilder()
                .setPurchaseState(Purchase.PurchaseState.PURCHASED)
                .setPurchaseTime(System.currentTimeMillis())
                .setOrderId("GPA.1234-5678-9012-34567")
                .setProductId(id)
                .setQuantity(1)
                .setPurchaseToken("opaque-token-up-to-1950-characters")
                .setPackageName("com.superwall.sdk")
                .setDeveloperPayload("")
                .setAcknowledged(true)
                .setAutoRenewing(true)
                .build()
    }
}
