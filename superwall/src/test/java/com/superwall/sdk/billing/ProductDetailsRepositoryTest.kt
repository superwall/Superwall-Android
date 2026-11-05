@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductDetailsRepositoryTest {
    private fun product(id: String): StoreProduct =
        mockk {
            every { fullIdentifier } returns id
        }

    @Test
    fun `an empty cache needs every id queried`() {
        Given("an empty repository") {
            val repository = ProductDetailsRepository()

            When("two ids are looked up") {
                val lookup = repository.lookup(setOf("a", "b"))

                Then("both are missing and nothing is cached") {
                    assertEquals(ProductLookup.Partial(emptySet(), setOf("a", "b")), lookup)
                }
            }
        }
    }

    @Test
    fun `found products are answered from the cache`() {
        Given("a repository that stored a query for a and b") {
            val repository = ProductDetailsRepository()
            val a = product("a")
            val b = product("b")
            repository.store(setOf("a", "b"), setOf(a, b))

            When("both are looked up") {
                val lookup = repository.lookup(setOf("a", "b"))

                Then("they come from the cache") {
                    assertEquals(ProductLookup.AllCached(setOf(a, b)), lookup)
                }
            }

            When("a and an unknown c are looked up") {
                val lookup = repository.lookup(setOf("a", "c"))

                Then("only c needs a query") {
                    assertEquals(ProductLookup.Partial(setOf(a), setOf("c")), lookup)
                }
            }
        }
    }

    @Test
    fun `an id Play didn't return fails without another query`() {
        Given("a repository that queried a and b but got only a") {
            val repository = ProductDetailsRepository()
            repository.store(setOf("a", "b"), setOf(product("a")))

            When("b is looked up") {
                val lookup = repository.lookup(setOf("a", "b"))

                Then("it is known to be missing") {
                    assertTrue(lookup is ProductLookup.KnownMissing)
                    assertEquals(
                        "Failed to query product details for b",
                        (lookup as ProductLookup.KnownMissing).error.message,
                    )
                }
            }
        }
    }

    @Test
    fun `clear forgets every product`() {
        Given("a repository with a cached product") {
            val repository = ProductDetailsRepository()
            repository.store(setOf("a"), setOf(product("a")))

            When("it is cleared") {
                repository.clear()

                Then("the product needs a query again") {
                    assertEquals(ProductLookup.Partial(emptySet(), setOf("a")), repository.lookup(setOf("a")))
                }
            }
        }
    }
}
