//package com.superwall.sdk.api
//
//import org.json.JSONObject
//
//enum class ProductType {
//    PRIMARY,
//    SECONDARY,
//    TERTIARY;
//
//    companion object {
//        fun fromRawValue(rawValue: String): ProductType? {
//            return when (rawValue) {
//                "primary" -> PRIMARY
//                "secondary" -> SECONDARY
//                "tertiary" -> TERTIARY
//                else -> null
//            }
//        }
//    }
//
//    fun rawValue(): String {
//        return when (this) {
//            PRIMARY -> "primary"
//            SECONDARY -> "secondary"
//            TERTIARY -> "tertiary"
//        }
//    }
//}
//
//class Product(val type: ProductType, val id: String) {
//    companion object {
//        fun fromJson(json: JSONObject): Product? {
//            val rawType = json.optString("product", null) ?: return null
//            val productType = ProductType.fromRawValue(rawType) ?: return null
//            val productId = json.optString("productId", null) ?: return null
//            return Product(productType, productId)
//        }
//    }
//
//    fun toJson(): JSONObject {
//        val json = JSONObject()
//        json.put("product", type.rawValue())
//        json.put("productId", id)
//        return json
//    }
//}
