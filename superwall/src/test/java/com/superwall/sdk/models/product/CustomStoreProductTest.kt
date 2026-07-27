@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.models.product

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomStoreProductTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    @Test
    fun `deserializes a CUSTOM store product item via the polymorphic serializer`() {
        Given("a JSON store_product payload with store CUSTOM") {
            val payload =
                """
                {
                  "store": "CUSTOM",
                  "product_identifier": "stripe_pro_monthly"
                }
                """.trimIndent()

            When("decoded via StoreProductSerializer") {
                val decoded = json.decodeFromString(StoreProductSerializer, payload)

                Then("the result is a Custom variant with the right identifier") {
                    assertTrue(decoded is ProductItem.StoreProductType.Custom)
                    val custom = (decoded as ProductItem.StoreProductType.Custom).product
                    assertEquals("stripe_pro_monthly", custom.productIdentifier)
                    assertEquals(Store.CUSTOM, custom.store)
                    assertEquals("stripe_pro_monthly", custom.fullIdentifier)
                }
            }
        }
    }

    @Test
    fun `round-trips CustomStoreProduct through the serializer`() {
        Given("a Custom store product type") {
            val original =
                ProductItem.StoreProductType.Custom(
                    CustomStoreProduct(productIdentifier = "stripe_pro_yearly"),
                )

            When("encoded then decoded") {
                val encoded = json.encodeToString(StoreProductSerializer, original)
                val decoded = json.decodeFromString(StoreProductSerializer, encoded)

                Then("the result equals the original") {
                    assertTrue(decoded is ProductItem.StoreProductType.Custom)
                    assertEquals(
                        original.product.productIdentifier,
                        (decoded as ProductItem.StoreProductType.Custom).product.productIdentifier,
                    )
                }
            }
        }
    }

    @Test
    fun `deserializes an OTHER store product with custom fields as Custom`() {
        Given("a store_product payload with store OTHER carrying a product_identifier") {
            // API contract: the backend resolves custom store products to OTHER so SDKs
            // that predate CUSTOM ignore them.
            val payload =
                """
                {
                  "store": "OTHER",
                  "product_identifier": "custom_year_10_trial_week"
                }
                """.trimIndent()

            When("decoded via StoreProductSerializer") {
                val decoded = json.decodeFromString(StoreProductSerializer, payload)

                Then("the result is a Custom variant preserving the wire store value") {
                    assertTrue(decoded is ProductItem.StoreProductType.Custom)
                    val custom = (decoded as ProductItem.StoreProductType.Custom).product
                    assertEquals("custom_year_10_trial_week", custom.productIdentifier)
                    assertEquals(Store.OTHER, custom.store)
                }
            }
        }
    }

    @Test
    fun `deserializes a products_v2 OTHER entry as a Custom product item`() {
        Given("a full products_v2 entry as served by the paywall endpoint") {
            val payload =
                """
                {
                  "sw_composite_product_id": "custom_year_10_trial_week",
                  "reference_name": "primary",
                  "store_product": {
                    "store": "OTHER",
                    "product_identifier": "custom_year_10_trial_week"
                  },
                  "entitlements": [
                    { "identifier": "default", "type": "SERVICE_LEVEL" }
                  ]
                }
                """.trimIndent()

            When("decoded as a ProductItem") {
                val decoded = json.decodeFromString(ProductItemSerializer, payload)

                Then("it is a Custom product with the composite id") {
                    assertTrue(decoded.type is ProductItem.StoreProductType.Custom)
                    assertEquals("custom_year_10_trial_week", decoded.compositeId)
                    assertEquals("custom_year_10_trial_week", decoded.fullProductId)
                }
            }
        }
    }

    @Test
    fun `deserializes a crossplatform OTHER store product with custom fields as Custom`() {
        Given("a crossplatform product payload with store OTHER carrying a product_identifier") {
            val payload =
                """
                {
                  "sw_composite_product_id": "custom_year_10_trial_week",
                  "store_product": {
                    "store": "OTHER",
                    "product_identifier": "custom_year_10_trial_week"
                  },
                  "entitlements": []
                }
                """.trimIndent()

            When("decoded as a CrossplatformProduct") {
                val decoded = json.decodeFromString(CrossplatformProduct.serializer(), payload)

                Then("the store product is the Custom variant") {
                    assertTrue(decoded.storeProduct is CrossplatformProduct.StoreProduct.Custom)
                    assertEquals(
                        "custom_year_10_trial_week",
                        (decoded.storeProduct as CrossplatformProduct.StoreProduct.Custom).productIdentifier,
                    )
                }
            }
        }
    }

    @Test
    fun `ProductItem fullProductId returns the identifier for Custom`() {
        Given("a ProductItem wrapping a Custom store product") {
            val item =
                ProductItem(
                    compositeId = "stripe_pro_monthly",
                    name = "pro",
                    type =
                        ProductItem.StoreProductType.Custom(
                            CustomStoreProduct(productIdentifier = "stripe_pro_monthly"),
                        ),
                    entitlements = emptySet(),
                )

            Then("fullProductId is the underlying identifier") {
                assertEquals("stripe_pro_monthly", item.fullProductId)
            }
        }
    }
}
