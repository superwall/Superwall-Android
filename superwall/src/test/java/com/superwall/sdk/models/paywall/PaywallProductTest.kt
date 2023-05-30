package com.superwall.sdk.models.paywall

import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductType


import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.json.JSONObject
import org.junit.Test
import java.io.InputStream

class PaywallProductTest {

    @Test
    fun `test parsing of config`() {
        val productString = """
            {"product":  "primary", "product_id":  "abc-def"}
        """.trimIndent()


        val json = Json {
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
        val product = json.decodeFromString<Product>(productString)
        assert(product != null)
        assert(product.id == "abc-def")
        assert(product.type == ProductType.PRIMARY)
    }
}
