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
