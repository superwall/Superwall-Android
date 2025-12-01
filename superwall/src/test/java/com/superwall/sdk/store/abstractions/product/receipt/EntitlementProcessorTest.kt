package com.superwall.sdk.store.abstractions.product.receipt

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.entitlements.Entitlement
import org.junit.Assert.*
import org.junit.Test
import java.util.*

/**
 * Tests for EntitlementProcessor - ported from iOS EntitlementProcessorTests.swift
 */
class EntitlementProcessorTest {
    // MARK: - Mock Transaction Types

    data class MockTransaction(
        override val productId: String,
        override val transactionId: String,
        override val purchaseDate: Date,
        override val originalPurchaseDate: Date,
        override val expirationDate: Date?,
        override val isRevoked: Boolean,
        override val productType: EntitlementTransactionType,
        override val willRenew: Boolean,
        override val renewedAt: Date?,
        override val isInGracePeriod: Boolean,
        override val isInBillingRetryPeriod: Boolean,
        override val isActive: Boolean,
    ) : EntitlementTransaction

    // MARK: - Helper Methods

    private fun createMockTransaction(
        productId: String = "test_product",
        transactionId: String = "txn_123",
        purchaseDate: Date = Date(),
        originalPurchaseDate: Date? = null,
        expirationDate: Date? = null,
        isRevoked: Boolean = false,
        productType: EntitlementTransactionType = EntitlementTransactionType.CONSUMABLE,
        willRenew: Boolean = false,
        renewedAt: Date? = null,
        isInGracePeriod: Boolean = false,
        isInBillingRetryPeriod: Boolean = false,
        isActive: Boolean = false,
    ): MockTransaction =
        MockTransaction(
            productId = productId,
            transactionId = transactionId,
            purchaseDate = purchaseDate,
            originalPurchaseDate = originalPurchaseDate ?: purchaseDate,
            expirationDate = expirationDate,
            isRevoked = isRevoked,
            productType = productType,
            willRenew = willRenew,
            renewedAt = renewedAt,
            isInGracePeriod = isInGracePeriod,
            isInBillingRetryPeriod = isInBillingRetryPeriod,
            isActive = isActive,
        )

    private fun createEntitlement(
        id: String = "test_entitlement",
        productIds: Set<String> = setOf("test_product"),
    ): Entitlement =
        Entitlement(
            id = id,
            type = Entitlement.Type.SERVICE_LEVEL,
            isActive = false,
            productIds = productIds,
        )

    private val processor = EntitlementProcessor()

    // MARK: - Basic Processing Tests

    @Test
    fun `process empty transactions returns empty entitlements`() {
        Given("empty transactions and entitlements maps") {
            val transactionsByEntitlement: Map<String, List<EntitlementTransaction>> = emptyMap()
            val rawEntitlementsByProductId: Map<String, Set<Entitlement>> = emptyMap()

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("result should be empty") {
                    assertTrue(result.isEmpty())
                }
            }
        }
    }

    @Test
    fun `process lifetime non-consumable product`() {
        Given("a non-consumable transaction and entitlement") {
            val transaction =
                createMockTransaction(
                    productId = "lifetime_product",
                    isRevoked = false,
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                    isActive = true,
                )

            val entitlement =
                createEntitlement(
                    id = "lifetime_entitlement",
                    productIds = setOf("lifetime_product"),
                )

            val transactionsByEntitlement = mapOf("lifetime_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("lifetime_product" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["lifetime_product"]?.firstOrNull()

                Then("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }

                And("entitlement should be lifetime") {
                    assertEquals(true, processedEntitlement?.isLifetime)
                }

                And("latest product ID should be set") {
                    assertEquals("lifetime_product", processedEntitlement?.latestProductId)
                }
            }
        }
    }

    @Test
    fun `process active subscription`() {
        Given("an active auto-renewable subscription") {
            val futureDate = Date(System.currentTimeMillis() + 3600_000) // 1 hour from now
            val transaction =
                createMockTransaction(
                    productId = "subscription_product",
                    expirationDate = futureDate,
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    willRenew = true,
                    isActive = true,
                )

            val entitlement =
                createEntitlement(
                    id = "subscription_entitlement",
                    productIds = setOf("subscription_product"),
                )

            val transactionsByEntitlement = mapOf("subscription_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("subscription_product" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["subscription_product"]?.firstOrNull()

                Then("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }

                And("entitlement should not be lifetime") {
                    assertEquals(false, processedEntitlement?.isLifetime)
                }

                And("entitlement should auto-renew") {
                    assertEquals(true, processedEntitlement?.willRenew)
                }

                And("expiration date should match") {
                    assertEquals(futureDate, processedEntitlement?.expiresAt)
                }
            }
        }
    }

    @Test
    fun `process expired subscription`() {
        Given("an expired subscription") {
            val pastDate = Date(System.currentTimeMillis() - 3600_000) // 1 hour ago
            val transaction =
                createMockTransaction(
                    productId = "expired_product",
                    expirationDate = pastDate,
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "expired_entitlement",
                    productIds = setOf("expired_product"),
                )

            val transactionsByEntitlement = mapOf("expired_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("expired_product" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["expired_product"]?.firstOrNull()

                Then("entitlement should be inactive") {
                    assertEquals(false, processedEntitlement?.isActive)
                }

                And("expiration date should match") {
                    assertEquals(pastDate, processedEntitlement?.expiresAt)
                }
            }
        }
    }

    // MARK: - Multiple Transaction Tests

    @Test
    fun `process multiple transactions for same entitlement`() {
        Given("older and newer transactions for the same entitlement") {
            val baseDate = Date()
            val olderTransaction =
                createMockTransaction(
                    productId = "product1",
                    transactionId = "txn_1",
                    purchaseDate = Date(baseDate.time - 1000),
                    expirationDate = Date(baseDate.time - 500),
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    isActive = false,
                )

            val newerTransaction =
                createMockTransaction(
                    productId = "product1",
                    transactionId = "txn_2",
                    purchaseDate = baseDate,
                    expirationDate = Date(baseDate.time + 3600_000),
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    willRenew = true,
                    isActive = true,
                )

            val entitlement =
                createEntitlement(
                    id = "multi_entitlement",
                    productIds = setOf("product1"),
                )

            val transactionsByEntitlement = mapOf("multi_entitlement" to listOf<EntitlementTransaction>(olderTransaction, newerTransaction))
            val rawEntitlementsByProductId = mapOf("product1" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["product1"]?.firstOrNull()

                Then("entitlement should be active (from newer transaction)") {
                    assertEquals(true, processedEntitlement?.isActive)
                }

                And("entitlement should auto-renew") {
                    assertEquals(true, processedEntitlement?.willRenew)
                }

                And("latest product ID should be set") {
                    assertEquals("product1", processedEntitlement?.latestProductId)
                }
            }
        }
    }

    @Test
    fun `process renewal tracking`() {
        Given("a renewed subscription transaction") {
            val baseDate = Date()
            val originalDate = Date(baseDate.time - 86400_000) // 1 day ago
            val renewalDate = baseDate

            val transaction =
                createMockTransaction(
                    productId = "renewal_product",
                    purchaseDate = renewalDate,
                    originalPurchaseDate = originalDate,
                    expirationDate = Date(baseDate.time + 3600_000),
                    isRevoked = false,
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "renewal_entitlement",
                    productIds = setOf("renewal_product"),
                )

            val transactionsByEntitlement = mapOf("renewal_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("renewal_product" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["renewal_product"]?.firstOrNull()

                Then("startsAt should be the original purchase date") {
                    assertEquals(originalDate, processedEntitlement?.startsAt)
                }

                And("renewedAt should be the renewal date") {
                    assertEquals(renewalDate, processedEntitlement?.renewedAt)
                }
            }
        }
    }

    // MARK: - Multiple Product Tests

    @Test
    fun `process entitlement with multiple lifetime products`() {
        Given("two non-consumable products for the same entitlement") {
            val transaction1 =
                createMockTransaction(
                    productId = "product1",
                    transactionId = "txn_1",
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val transaction2 =
                createMockTransaction(
                    productId = "product2",
                    transactionId = "txn_2",
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "multi_product_entitlement",
                    productIds = setOf("product1", "product2"),
                )

            val transactionsByEntitlement = mapOf("multi_product_entitlement" to listOf<EntitlementTransaction>(transaction1, transaction2))
            val rawEntitlementsByProductId =
                mapOf(
                    "product1" to setOf(entitlement),
                    "product2" to setOf(entitlement),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("result should have 2 products") {
                    assertEquals(2, result.size)
                }

                And("both products should be active") {
                    assertEquals(true, result["product1"]?.firstOrNull()?.isActive)
                    assertEquals(true, result["product2"]?.firstOrNull()?.isActive)
                }

                And("both products should have 2 product IDs") {
                    assertEquals(2, result["product1"]?.firstOrNull()?.productIds?.size)
                    assertEquals(2, result["product2"]?.firstOrNull()?.productIds?.size)
                }
            }
        }
    }

    // MARK: - Edge Cases

    @Test
    fun `process non-consumable without expiration`() {
        Given("a non-consumable transaction without expiration date") {
            val transaction =
                createMockTransaction(
                    productId = "non_consumable",
                    expirationDate = null,
                    isRevoked = false,
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "non_consumable_entitlement",
                    productIds = setOf("non_consumable"),
                )

            val transactionsByEntitlement = mapOf("non_consumable_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("non_consumable" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["non_consumable"]?.firstOrNull()

                Then("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }

                And("entitlement should be lifetime") {
                    assertEquals(true, processedEntitlement?.isLifetime)
                }

                And("expiration should be null") {
                    assertNull(processedEntitlement?.expiresAt)
                }

                And("latest product ID should be set") {
                    assertEquals("non_consumable", processedEntitlement?.latestProductId)
                }
            }
        }
    }

    @Test
    fun `process consumable transaction`() {
        Given("a consumable transaction") {
            val transaction =
                createMockTransaction(
                    productId = "consumable",
                    productType = EntitlementTransactionType.CONSUMABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "consumable_entitlement",
                    productIds = setOf("consumable"),
                )

            val transactionsByEntitlement = mapOf("consumable_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("consumable" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["consumable"]?.firstOrNull()

                Then("entitlement should not be active (consumables don't grant active status)") {
                    assertEquals(false, processedEntitlement?.isActive)
                }
            }
        }
    }

    // MARK: - Complex Scenarios

    @Test
    fun `process mixed product types in same entitlement`() {
        Given("a lifetime product and an expired subscription for the same entitlement") {
            val lifetimeTransaction =
                createMockTransaction(
                    productId = "lifetime_product",
                    transactionId = "txn_lifetime",
                    isRevoked = false,
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val subscriptionTransaction =
                createMockTransaction(
                    productId = "subscription_product",
                    transactionId = "txn_subscription",
                    expirationDate = Date(System.currentTimeMillis() - 3600_000), // Expired
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "mixed_entitlement",
                    productIds = setOf("lifetime_product", "subscription_product"),
                )

            val transactionsByEntitlement =
                mapOf(
                    "mixed_entitlement" to listOf<EntitlementTransaction>(lifetimeTransaction, subscriptionTransaction),
                )
            val rawEntitlementsByProductId =
                mapOf(
                    "lifetime_product" to setOf(entitlement),
                    "subscription_product" to setOf(entitlement),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("result should have 2 products") {
                    assertEquals(2, result.size)
                }

                And("each product should have exactly one entitlement") {
                    assertEquals(1, result["lifetime_product"]?.size)
                    assertEquals(1, result["subscription_product"]?.size)
                }

                val lifetimeResult = result["lifetime_product"]?.firstOrNull()
                val subscriptionResult = result["subscription_product"]?.firstOrNull()

                And("both should reference the same entitlement ID") {
                    assertEquals("mixed_entitlement", lifetimeResult?.id)
                    assertEquals("mixed_entitlement", subscriptionResult?.id)
                }

                And("both should have identical enriched entitlement data") {
                    assertEquals(lifetimeResult, subscriptionResult)
                }

                And("entitlement should be active (lifetime takes precedence)") {
                    assertEquals(true, lifetimeResult?.isActive)
                }

                And("entitlement should be lifetime") {
                    assertEquals(true, lifetimeResult?.isLifetime)
                }

                And("latest product ID should be the lifetime product") {
                    assertEquals("lifetime_product", lifetimeResult?.latestProductId)
                }

                And("productIds should contain both products") {
                    assertEquals(setOf("lifetime_product", "subscription_product"), lifetimeResult?.productIds)
                }
            }
        }
    }

    @Test
    fun `process multiple entitlements for same product`() {
        Given("one product unlocking multiple entitlements") {
            val transaction =
                createMockTransaction(
                    productId = "shared_product",
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val entitlement1 =
                createEntitlement(
                    id = "entitlement_1",
                    productIds = setOf("shared_product"),
                )

            val entitlement2 =
                createEntitlement(
                    id = "entitlement_2",
                    productIds = setOf("shared_product"),
                )

            val transactionsByEntitlement =
                mapOf(
                    "entitlement_1" to listOf<EntitlementTransaction>(transaction),
                    "entitlement_2" to listOf<EntitlementTransaction>(transaction),
                )
            val rawEntitlementsByProductId =
                mapOf(
                    "shared_product" to setOf(entitlement1, entitlement2),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val entitlements = result["shared_product"]

                Then("product should have 2 entitlements") {
                    assertEquals(2, entitlements?.size)
                }

                And("entitlement_1 should be included") {
                    assertTrue(entitlements?.any { it.id == "entitlement_1" } == true)
                }

                And("entitlement_2 should be included") {
                    assertTrue(entitlements?.any { it.id == "entitlement_2" } == true)
                }
            }
        }
    }

    @Test
    fun `process latest product ID selection with multiple renewable transactions`() {
        Given("older and newer subscription products for the same entitlement") {
            val baseDate = Date()
            val olderTransaction =
                createMockTransaction(
                    productId = "old_product",
                    transactionId = "txn_old",
                    purchaseDate = Date(baseDate.time - 7200_000), // 2 hours ago
                    expirationDate = Date(baseDate.time - 3600_000), // Expired 1 hour ago
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                )

            val newerTransaction =
                createMockTransaction(
                    productId = "new_product",
                    transactionId = "txn_new",
                    purchaseDate = Date(baseDate.time - 1800_000), // 30 minutes ago
                    expirationDate = Date(baseDate.time + 3600_000), // Active
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    isActive = true,
                )

            val entitlement =
                createEntitlement(
                    id = "version_entitlement",
                    productIds = setOf("old_product", "new_product"),
                )

            val transactionsByEntitlement =
                mapOf(
                    "version_entitlement" to listOf<EntitlementTransaction>(olderTransaction, newerTransaction),
                )
            val rawEntitlementsByProductId =
                mapOf(
                    "old_product" to setOf(entitlement),
                    "new_product" to setOf(entitlement),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["new_product"]?.firstOrNull()

                Then("latest product ID should be the newer product") {
                    assertEquals("new_product", processedEntitlement?.latestProductId)
                }

                And("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }
            }
        }
    }

    @Test
    fun `process entitlement with no matching raw entitlements`() {
        Given("a transaction with no matching raw entitlements") {
            val transaction =
                createMockTransaction(
                    productId = "orphan_product",
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val transactionsByEntitlement = mapOf("orphan_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId: Map<String, Set<Entitlement>> = emptyMap()

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("result should be empty") {
                    assertTrue(result.isEmpty())
                }
            }
        }
    }

    @Test
    fun `process entitlement with mismatched IDs`() {
        Given("a transaction with different entitlement ID than raw entitlement") {
            val transaction =
                createMockTransaction(
                    productId = "product_a",
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "different_entitlement",
                    productIds = setOf("product_a"),
                )

            val transactionsByEntitlement = mapOf("transaction_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("product_a" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("raw entitlement should be returned (inactive)") {
                    assertEquals(1, result["product_a"]?.size)
                }

                And("entitlement ID should match raw entitlement") {
                    assertEquals("different_entitlement", result["product_a"]?.firstOrNull()?.id)
                }

                And("entitlement should be inactive") {
                    assertEquals(false, result["product_a"]?.firstOrNull()?.isActive)
                }
            }
        }
    }

    @Test
    fun `process complex expiration date selection`() {
        Given("multiple transactions with different expiration dates including revoked") {
            val baseDate = Date()

            val transactions =
                listOf(
                    createMockTransaction(
                        productId = "product1",
                        transactionId = "txn_1",
                        expirationDate = Date(baseDate.time + 1800_000), // 30 min
                        isRevoked = false,
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    ),
                    createMockTransaction(
                        productId = "product2",
                        transactionId = "txn_2",
                        expirationDate = Date(baseDate.time + 3600_000), // 1 hour
                        isRevoked = false,
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    ),
                    createMockTransaction(
                        productId = "product3",
                        transactionId = "txn_3",
                        expirationDate = Date(baseDate.time + 4000_000), // 66 mins (latest but revoked)
                        isRevoked = true,
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    ),
                )

            val entitlement =
                createEntitlement(
                    id = "complex_entitlement",
                    productIds = setOf("product1", "product2", "product3"),
                )

            val transactionsByEntitlement = mapOf("complex_entitlement" to transactions.map { it as EntitlementTransaction })
            val rawEntitlementsByProductId =
                mapOf(
                    "product1" to setOf(entitlement),
                    "product2" to setOf(entitlement),
                    "product3" to setOf(entitlement),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["product1"]?.firstOrNull()

                Then("expiration should be from non-revoked transaction with latest date") {
                    assertEquals(Date(baseDate.time + 3600_000), processedEntitlement?.expiresAt)
                }

                And("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }
            }
        }
    }

    @Test
    fun `process non-renewable subscription`() {
        Given("a non-renewable subscription") {
            val futureDate = Date(System.currentTimeMillis() + 3600_000)
            val transaction =
                createMockTransaction(
                    productId = "non_renewable_product",
                    expirationDate = futureDate,
                    productType = EntitlementTransactionType.NON_RENEWABLE,
                    willRenew = false,
                    isActive = true,
                )

            val entitlement =
                createEntitlement(
                    id = "non_renewable_entitlement",
                    productIds = setOf("non_renewable_product"),
                )

            val transactionsByEntitlement = mapOf("non_renewable_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("non_renewable_product" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["non_renewable_product"]?.firstOrNull()

                Then("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }

                And("entitlement should not be lifetime") {
                    assertEquals(false, processedEntitlement?.isLifetime)
                }

                And("entitlement should not auto-renew") {
                    assertEquals(false, processedEntitlement?.willRenew)
                }

                And("expiration date should match") {
                    assertEquals(futureDate, processedEntitlement?.expiresAt)
                }
            }
        }
    }

    @Test
    fun `process large number of transactions`() {
        Given("100 transactions") {
            val baseDate = Date()
            val transactions = mutableListOf<MockTransaction>()

            for (i in 0 until 100) {
                transactions.add(
                    createMockTransaction(
                        productId = "product_$i",
                        transactionId = "txn_$i",
                        purchaseDate = Date(baseDate.time + i * 60_000),
                        expirationDate = Date(baseDate.time + i * 60_000 + 3600_000),
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                        isActive = i > 95,
                    ),
                )
            }

            val entitlement =
                createEntitlement(
                    id = "bulk_entitlement",
                    productIds = transactions.map { it.productId }.toSet(),
                )

            val transactionsByEntitlement = mapOf("bulk_entitlement" to transactions.map { it as EntitlementTransaction })
            val rawEntitlementsByProductId = transactions.associate { it.productId to setOf(entitlement) }

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("result should have 100 products") {
                    assertEquals(100, result.size)
                }

                val latestProductId = "product_99"
                val processedEntitlement = result[latestProductId]?.firstOrNull()

                And("latest product should be product_99") {
                    assertEquals(latestProductId, processedEntitlement?.latestProductId)
                }

                And("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }
            }
        }
    }

    @Test
    fun `process transactions with extreme dates`() {
        Given("transactions with extreme past and future dates") {
            val distantPast = Date(Long.MIN_VALUE / 2)
            val distantFuture = Date(Long.MAX_VALUE / 2)

            val transaction =
                createMockTransaction(
                    productId = "extreme_product",
                    purchaseDate = distantPast,
                    originalPurchaseDate = distantPast,
                    expirationDate = distantFuture,
                    productType = EntitlementTransactionType.AUTO_RENEWABLE,
                    isActive = true,
                )

            val entitlement =
                createEntitlement(
                    id = "extreme_entitlement",
                    productIds = setOf("extreme_product"),
                )

            val transactionsByEntitlement = mapOf("extreme_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("extreme_product" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result["extreme_product"]?.firstOrNull()

                Then("startsAt should be distant past") {
                    assertEquals(distantPast, processedEntitlement?.startsAt)
                }

                And("expiresAt should be distant future") {
                    assertEquals(distantFuture, processedEntitlement?.expiresAt)
                }

                And("entitlement should be active") {
                    assertEquals(true, processedEntitlement?.isActive)
                }
            }
        }
    }

    @Test
    fun `process empty product IDs`() {
        Given("a transaction with empty product ID") {
            val transaction =
                createMockTransaction(
                    productId = "",
                    productType = EntitlementTransactionType.NON_CONSUMABLE,
                )

            val entitlement =
                createEntitlement(
                    id = "empty_entitlement",
                    productIds = setOf(""),
                )

            val transactionsByEntitlement = mapOf("empty_entitlement" to listOf<EntitlementTransaction>(transaction))
            val rawEntitlementsByProductId = mapOf("" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                val processedEntitlement = result[""]?.firstOrNull()

                Then("entitlement should exist") {
                    assertNotNull(processedEntitlement)
                }

                And("productIds should contain empty string") {
                    assertTrue(processedEntitlement?.productIds?.contains("") == true)
                }
            }
        }
    }

    // MARK: - Performance Tests

    @Test
    fun `process entitlements with many-to-many relationships`() {
        Given("10 entitlements each with 5 products") {
            val transactionsByEntitlement = mutableMapOf<String, List<EntitlementTransaction>>()
            val rawEntitlementsByProductId = mutableMapOf<String, MutableSet<Entitlement>>()

            for (entitlementIndex in 0 until 10) {
                val entitlementId = "entitlement_$entitlementIndex"
                val productIds = mutableSetOf<String>()
                val transactions = mutableListOf<MockTransaction>()

                for (productIndex in 0 until 5) {
                    val productId = "product_$productIndex"
                    productIds.add(productId)

                    transactions.add(
                        createMockTransaction(
                            productId = productId,
                            transactionId = "txn_${entitlementIndex}_$productIndex",
                            productType = EntitlementTransactionType.NON_CONSUMABLE,
                        ),
                    )

                    val entitlement =
                        createEntitlement(
                            id = entitlementId,
                            productIds = productIds.toSet(),
                        )

                    rawEntitlementsByProductId.getOrPut(productId) { mutableSetOf() }.add(entitlement)
                }

                transactionsByEntitlement[entitlementId] = transactions.map { it as EntitlementTransaction }
            }

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("result should have 5 products") {
                    assertEquals(5, result.size)
                }

                And("each product should have 10 entitlements") {
                    for (productId in result.keys) {
                        assertEquals(10, result[productId]?.size)
                    }
                }
            }
        }
    }

    // MARK: - Transaction Processing Tests

    @Test
    fun `process transactions into subscription and non-subscription objects`() {
        Given("consumable, lifetime, and subscription transactions") {
            val transactions =
                listOf<EntitlementTransaction>(
                    createMockTransaction(
                        productId = "consumable_product",
                        transactionId = "txn_1",
                        productType = EntitlementTransactionType.CONSUMABLE,
                        isActive = true,
                    ),
                    createMockTransaction(
                        productId = "lifetime_product",
                        transactionId = "txn_2",
                        productType = EntitlementTransactionType.NON_CONSUMABLE,
                        isActive = true,
                    ),
                    createMockTransaction(
                        productId = "subscription_product",
                        transactionId = "txn_3",
                        expirationDate = Date(System.currentTimeMillis() + 3600_000),
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                        willRenew = true,
                        isInGracePeriod = false,
                        isInBillingRetryPeriod = false,
                        isActive = true,
                    ),
                )

            When("processing transactions") {
                val (nonSubscriptions, subscriptions) = processor.processTransactions(transactions)

                Then("there should be 2 non-subscriptions") {
                    assertEquals(2, nonSubscriptions.size)
                }

                And("there should be 1 subscription") {
                    assertEquals(1, subscriptions.size)
                }

                val consumable = nonSubscriptions.find { it.productId == "consumable_product" }
                And("consumable should be marked as consumable") {
                    assertEquals(true, consumable?.isConsumable)
                    assertEquals("txn_1", consumable?.transactionId)
                }

                val lifetime = nonSubscriptions.find { it.productId == "lifetime_product" }
                And("lifetime should not be marked as consumable") {
                    assertEquals(false, lifetime?.isConsumable)
                    assertEquals("txn_2", lifetime?.transactionId)
                }

                val subscription = subscriptions.firstOrNull()
                And("subscription should have correct properties") {
                    assertEquals("subscription_product", subscription?.productId)
                    assertEquals("txn_3", subscription?.transactionId)
                    assertEquals(true, subscription?.willRenew)
                    assertEquals(true, subscription?.isActive)
                    assertEquals(false, subscription?.isInGracePeriod)
                    assertEquals(false, subscription?.isInBillingRetryPeriod)
                }
            }
        }
    }

    @Test
    fun `process transactions separates autoRenewable and nonRenewable into subscriptions`() {
        Given("auto-renewable and non-renewable subscription transactions") {
            val transactions =
                listOf<EntitlementTransaction>(
                    createMockTransaction(
                        productId = "auto_renewable",
                        transactionId = "txn_auto",
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                        willRenew = true,
                        isActive = true,
                    ),
                    createMockTransaction(
                        productId = "non_renewable",
                        transactionId = "txn_non_renewable",
                        productType = EntitlementTransactionType.NON_RENEWABLE,
                        willRenew = false,
                        isActive = true,
                    ),
                )

            When("processing transactions") {
                val (_, subscriptions) = processor.processTransactions(transactions)

                Then("there should be 2 subscriptions") {
                    assertEquals(2, subscriptions.size)
                }

                And("auto_renewable should be included") {
                    assertTrue(subscriptions.any { it.productId == "auto_renewable" })
                }

                And("non_renewable should be included") {
                    assertTrue(subscriptions.any { it.productId == "non_renewable" })
                }
            }
        }
    }

    @Test
    fun `process transactions preserves subscription state fields`() {
        Given("subscriptions with various states") {
            val transactions =
                listOf<EntitlementTransaction>(
                    createMockTransaction(
                        productId = "grace_period_sub",
                        transactionId = "txn_grace",
                        expirationDate = Date(System.currentTimeMillis() + 3600_000),
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                        willRenew = true,
                        isInGracePeriod = true,
                        isInBillingRetryPeriod = false,
                        isActive = true,
                    ),
                    createMockTransaction(
                        productId = "billing_retry_sub",
                        transactionId = "txn_billing",
                        expirationDate = Date(System.currentTimeMillis() + 3600_000),
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                        willRenew = true,
                        isInGracePeriod = false,
                        isInBillingRetryPeriod = true,
                        isActive = true,
                    ),
                    createMockTransaction(
                        productId = "revoked_sub",
                        transactionId = "txn_revoked",
                        expirationDate = Date(System.currentTimeMillis() + 3600_000),
                        isRevoked = true,
                        productType = EntitlementTransactionType.AUTO_RENEWABLE,
                        willRenew = false,
                        isInGracePeriod = false,
                        isInBillingRetryPeriod = false,
                        isActive = false,
                    ),
                )

            When("processing transactions") {
                val (_, subscriptions) = processor.processTransactions(transactions)

                Then("there should be 3 subscriptions") {
                    assertEquals(3, subscriptions.size)
                }

                val gracePeriodSub = subscriptions.find { it.productId == "grace_period_sub" }
                And("grace period subscription should have correct state") {
                    assertEquals(true, gracePeriodSub?.isInGracePeriod)
                    assertEquals(false, gracePeriodSub?.isInBillingRetryPeriod)
                    assertEquals(true, gracePeriodSub?.willRenew)
                    assertEquals(true, gracePeriodSub?.isActive)
                }

                val billingRetrySub = subscriptions.find { it.productId == "billing_retry_sub" }
                And("billing retry subscription should have correct state") {
                    assertEquals(false, billingRetrySub?.isInGracePeriod)
                    assertEquals(true, billingRetrySub?.isInBillingRetryPeriod)
                    assertEquals(true, billingRetrySub?.willRenew)
                    assertEquals(true, billingRetrySub?.isActive)
                }

                val revokedSub = subscriptions.find { it.productId == "revoked_sub" }
                And("revoked subscription should have correct state") {
                    assertEquals(true, revokedSub?.isRevoked)
                    assertEquals(false, revokedSub?.willRenew)
                    assertEquals(false, revokedSub?.isActive)
                }
            }
        }
    }

    @Test
    fun `process transactions preserves revoked field for non-subscriptions`() {
        Given("active and revoked non-consumable transactions") {
            val transactions =
                listOf<EntitlementTransaction>(
                    createMockTransaction(
                        productId = "active_lifetime",
                        transactionId = "txn_active",
                        isRevoked = false,
                        productType = EntitlementTransactionType.NON_CONSUMABLE,
                    ),
                    createMockTransaction(
                        productId = "revoked_lifetime",
                        transactionId = "txn_revoked",
                        isRevoked = true,
                        productType = EntitlementTransactionType.NON_CONSUMABLE,
                    ),
                )

            When("processing transactions") {
                val (nonSubscriptions, _) = processor.processTransactions(transactions)

                Then("there should be 2 non-subscriptions") {
                    assertEquals(2, nonSubscriptions.size)
                }

                val activeLifetime = nonSubscriptions.find { it.productId == "active_lifetime" }
                And("active lifetime should not be revoked") {
                    assertEquals(false, activeLifetime?.isRevoked)
                }

                val revokedLifetime = nonSubscriptions.find { it.productId == "revoked_lifetime" }
                And("revoked lifetime should be revoked") {
                    assertEquals(true, revokedLifetime?.isRevoked)
                }
            }
        }
    }

    // MARK: - No Transactions Tests

    @Test
    fun `process with no transactions but single inactive entitlement`() {
        Given("no transactions but a raw entitlement exists") {
            val entitlement =
                createEntitlement(
                    id = "entitlement_1",
                    productIds = setOf("product_a"),
                )

            val transactionsByEntitlement: Map<String, List<EntitlementTransaction>> = emptyMap()
            val rawEntitlementsByProductId = mapOf("product_a" to setOf(entitlement))

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("entitlement should be returned") {
                    assertEquals(1, result["product_a"]?.size)
                }

                And("entitlement ID should match") {
                    assertEquals("entitlement_1", result["product_a"]?.firstOrNull()?.id)
                }

                And("entitlement should be inactive") {
                    assertEquals(false, result["product_a"]?.firstOrNull()?.isActive)
                }
            }
        }
    }

    @Test
    fun `process with no transactions but multiple inactive entitlements`() {
        Given("no transactions but multiple raw entitlements exist") {
            val entitlement1 = createEntitlement(id = "entitlement_1", productIds = setOf("product_a"))
            val entitlement2 = createEntitlement(id = "entitlement_2", productIds = setOf("product_b"))
            val entitlement3 = createEntitlement(id = "entitlement_3", productIds = setOf("product_c"))

            val transactionsByEntitlement: Map<String, List<EntitlementTransaction>> = emptyMap()
            val rawEntitlementsByProductId =
                mapOf(
                    "product_a" to setOf(entitlement1),
                    "product_b" to setOf(entitlement2),
                    "product_c" to setOf(entitlement3),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("all entitlements should be returned") {
                    assertEquals(1, result["product_a"]?.size)
                    assertEquals(1, result["product_b"]?.size)
                    assertEquals(1, result["product_c"]?.size)
                }

                And("all entitlements should have correct IDs") {
                    assertEquals("entitlement_1", result["product_a"]?.firstOrNull()?.id)
                    assertEquals("entitlement_2", result["product_b"]?.firstOrNull()?.id)
                    assertEquals("entitlement_3", result["product_c"]?.firstOrNull()?.id)
                }

                And("all entitlements should be inactive") {
                    assertEquals(false, result["product_a"]?.firstOrNull()?.isActive)
                    assertEquals(false, result["product_b"]?.firstOrNull()?.isActive)
                    assertEquals(false, result["product_c"]?.firstOrNull()?.isActive)
                }
            }
        }
    }

    @Test
    fun `process with no transactions but entitlement with multiple products`() {
        Given("no transactions but entitlement with multiple products") {
            val entitlement =
                createEntitlement(
                    id = "premium_tier",
                    productIds = setOf("monthly", "annual", "lifetime"),
                )

            val transactionsByEntitlement: Map<String, List<EntitlementTransaction>> = emptyMap()
            val rawEntitlementsByProductId =
                mapOf(
                    "monthly" to setOf(entitlement),
                    "annual" to setOf(entitlement),
                    "lifetime" to setOf(entitlement),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("same entitlement should be returned for all products") {
                    assertEquals("premium_tier", result["monthly"]?.firstOrNull()?.id)
                    assertEquals("premium_tier", result["annual"]?.firstOrNull()?.id)
                    assertEquals("premium_tier", result["lifetime"]?.firstOrNull()?.id)
                }

                And("all should be inactive") {
                    assertEquals(false, result["monthly"]?.firstOrNull()?.isActive)
                    assertEquals(false, result["annual"]?.firstOrNull()?.isActive)
                    assertEquals(false, result["lifetime"]?.firstOrNull()?.isActive)
                }
            }
        }
    }

    @Test
    fun `process with no transactions and multiple entitlements per product`() {
        Given("no transactions but multiple entitlements per product") {
            val entitlement1 = createEntitlement(id = "basic_tier", productIds = setOf("product_a"))
            val entitlement2 = createEntitlement(id = "premium_tier", productIds = setOf("product_a"))

            val transactionsByEntitlement: Map<String, List<EntitlementTransaction>> = emptyMap()
            val rawEntitlementsByProductId =
                mapOf(
                    "product_a" to setOf(entitlement1, entitlement2),
                )

            When("building entitlements from transactions") {
                val result =
                    processor.buildEntitlementsFromTransactions(
                        transactionsByEntitlement = transactionsByEntitlement,
                        rawEntitlementsByProductId = rawEntitlementsByProductId,
                    )

                Then("both entitlements should be returned for the product") {
                    assertEquals(2, result["product_a"]?.size)
                }

                val entitlementIds = result["product_a"]?.map { it.id } ?: emptyList()
                And("basic_tier should be included") {
                    assertTrue(entitlementIds.contains("basic_tier"))
                }

                And("premium_tier should be included") {
                    assertTrue(entitlementIds.contains("premium_tier"))
                }

                And("all should be inactive") {
                    val allInactive = result["product_a"]?.all { !it.isActive } ?: false
                    assertTrue(allInactive)
                }
            }
        }
    }
}
