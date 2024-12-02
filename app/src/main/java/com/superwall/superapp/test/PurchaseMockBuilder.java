package com.superwall.superapp.test;

import com.android.billingclient.api.Purchase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class PurchaseMockBuilder {
    private JSONObject purchaseJson;
    
    public PurchaseMockBuilder() {
        purchaseJson = new JSONObject();
    }
    
    public static Purchase createDefaultPurchase() throws JSONException {
        return new PurchaseMockBuilder()
            .setPurchaseState(Purchase.PurchaseState.PURCHASED)
            .setPurchaseTime(System.currentTimeMillis())
            .setOrderId("GPA.1234-5678-9012-34567")
            .setProductId("premium_subscription")
            .setQuantity(1)
            .setPurchaseToken("opaque-token-up-to-1950-characters")
            .setPackageName("com.example.app")
            .setDeveloperPayload("")
            .setAcknowledged(true)
            .setAutoRenewing(true)
            .build();
    }
    
    public PurchaseMockBuilder setPurchaseState(int state) throws JSONException {
        purchaseJson.put("purchaseState", state == 2 ? 4 : state);
        return this;
    }
    
    public PurchaseMockBuilder setPurchaseTime(long time) throws JSONException {
        purchaseJson.put("purchaseTime", time);
        return this;
    }
    
    public PurchaseMockBuilder setOrderId(String orderId) throws JSONException {
        purchaseJson.put("orderId", orderId);
        return this;
    }
    
    public PurchaseMockBuilder setProductId(String productId) throws JSONException {
        JSONArray productIds = new JSONArray();
        productIds.put(productId);
        purchaseJson.put("productIds", productIds);
        // For backward compatibility
        purchaseJson.put("productId", productId);
        return this;
    }
    
    public PurchaseMockBuilder setQuantity(int quantity) throws JSONException {
        purchaseJson.put("quantity", quantity);
        return this;
    }
    
    public PurchaseMockBuilder setPurchaseToken(String token) throws JSONException {
        purchaseJson.put("token", token);
        purchaseJson.put("purchaseToken", token);
        return this;
    }
    
    public PurchaseMockBuilder setPackageName(String packageName) throws JSONException {
        purchaseJson.put("packageName", packageName);
        return this;
    }
    
    public PurchaseMockBuilder setDeveloperPayload(String payload) throws JSONException {
        purchaseJson.put("developerPayload", payload);
        return this;
    }
    
    public PurchaseMockBuilder setAcknowledged(boolean acknowledged) throws JSONException {
        purchaseJson.put("acknowledged", acknowledged);
        return this;
    }
    
    public PurchaseMockBuilder setAutoRenewing(boolean autoRenewing) throws JSONException {
        purchaseJson.put("autoRenewing", autoRenewing);
        return this;
    }
    
    public PurchaseMockBuilder setAccountIdentifiers(String obfuscatedAccountId, String obfuscatedProfileId) throws JSONException {
        purchaseJson.put("obfuscatedAccountId", obfuscatedAccountId);
        purchaseJson.put("obfuscatedProfileId", obfuscatedProfileId);
        return this;
    }
    
    public Purchase build() throws JSONException {
        return new Purchase(purchaseJson.toString(), "dummy-signature");
    }
}