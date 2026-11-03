package com.superwall.sdk.store

import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseFacadeTest {
    private val purchased = PurchaseResult.Purchased()
    private val source = slot<TransactionManager.PurchaseSource>()
    private val transactionManager =
        mockk<TransactionManager> {
            coEvery { purchase(capture(source), true) } returns purchased
        }
    private val internalController = mockk<InternalPurchaseController>(relaxed = true)
    private val storeManager =
        mockk<StoreManager> {
            every { purchaseController } returns internalController
        }
    private var hasInternalController = true
    private val facade =
        PurchaseFacade(
            transactionManager = { transactionManager },
            storeManager = { storeManager },
            hasInternalPurchaseController = { hasInternalController },
        )

    @Test
    fun `purchasing a StoreProduct is an external purchase that dismisses`() =
        runTest {
            val product = mockk<StoreProduct>()
            val result = facade.purchase(product)
            Given("a store product") {
                When("it is purchased") {
                    Then("the transaction manager's result comes back") {
                        assertSame(purchased, result.getOrThrow())
                    }
                    And("the source is an external purchase of that product") {
                        val captured = source.captured as TransactionManager.PurchaseSource.ExternalPurchase
                        assertSame(product, captured.product)
                    }
                }
            }
        }

    @Test
    fun `purchasing ProductDetails wraps them in a StoreProduct`() =
        runTest {
            val details = mockk<ProductDetails>(relaxed = true) { every { productId } returns "pro" }
            val result = facade.purchase(details)
            Given("Play product details") {
                Then("they are purchased as an external purchase of the same product id") {
                    assertSame(purchased, result.getOrThrow())
                    val captured = source.captured as TransactionManager.PurchaseSource.ExternalPurchase
                    assertEquals("pro", captured.product.fullIdentifier)
                }
            }
        }

    @Test
    fun `purchasing a product id looks the product up first`() =
        runTest {
            val product = mockk<StoreProduct>()
            coEvery { storeManager.getProductsWithoutPaywall(listOf("pro"), any()) } returns mapOf("pro" to product)
            val result = facade.purchase("pro")
            Given("a product id the store knows") {
                Then("that product is purchased") {
                    assertSame(purchased, result.getOrThrow())
                    assertSame(product, (source.captured as TransactionManager.PurchaseSource.ExternalPurchase).product)
                }
            }
        }

    @Test
    fun `purchasing an unknown product id fails without purchasing`() =
        runTest {
            coEvery { storeManager.getProductsWithoutPaywall(listOf("missing"), any()) } returns emptyMap()
            val result = facade.purchase("missing")
            Given("a product id the store does not know") {
                Then("the result is an IllegalArgumentException") {
                    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                }
                And("no purchase starts") {
                    coVerify(exactly = 0) { transactionManager.purchase(any(), any()) }
                }
            }
        }

    @Test
    fun `consuming restores when the SDK handles purchases`() =
        runTest {
            coEvery { storeManager.consume("token") } returns Result.success("token")
            val result = facade.consume("token")
            Given("the SDK's own purchase controller") {
                Then("the token comes back and purchases are restored") {
                    assertEquals("token", result.getOrThrow())
                    coVerify(exactly = 1) { internalController.restorePurchases() }
                }
            }
        }

    @Test
    fun `consuming does not restore with an external purchase controller`() =
        runTest {
            hasInternalController = false
            coEvery { storeManager.consume("token") } returns Result.success("token")
            val result = facade.consume("token")
            Given("an app-provided purchase controller") {
                Then("the token comes back and nothing is restored") {
                    assertEquals("token", result.getOrThrow())
                    coVerify(exactly = 0) { internalController.restorePurchases() }
                }
            }
        }

    @Test
    fun `a failed consume returns the store's error and does not restore`() =
        runTest {
            val error = IllegalStateException("consume failed")
            coEvery { storeManager.consume("token") } returns Result.failure(error)
            val result = facade.consume("token")
            Given("a store that fails to consume") {
                Then("the error comes back") {
                    assertSame(error, result.exceptionOrNull())
                    coVerify(exactly = 0) { internalController.restorePurchases() }
                }
            }
        }

    @Test
    fun `restoring goes through the transaction manager without a paywall`() =
        runTest {
            val restored = RestorationResult.Restored()
            coEvery { transactionManager.tryToRestorePurchases(null) } returns restored
            val result = facade.restorePurchases()
            Given("a restore outside a paywall") {
                Then("the transaction manager's result comes back") {
                    assertSame(restored, result.getOrThrow())
                }
            }
        }
}
