package com.superwall.sdk.models.paywall

import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.Test

class PaywallProductTest {
    @Test
    fun `test parsing of config`() {
        val productString =
            """
            {"product":  "primary", "product_id":  "abc-def:ghi:jkl", "product_id_android":  "abc-def:ghi:jkl"}
            """.trimIndent()

        val json =
            Json {
                ignoreUnknownKeys = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            }
        val product = json.decodeFromString<Product>(productString)
        assert(product != null)
        assert(product.id == "abc-def:ghi:jkl")
        assert(product.type == ProductType.PRIMARY)
    }
}
