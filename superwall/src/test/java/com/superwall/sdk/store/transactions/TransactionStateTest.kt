package com.superwall.sdk.store.transactions

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionStateTest {
    private fun product(id: String) = mockk<StoreProduct> { every { fullIdentifier } returns id }

    private fun paywall(product: StoreProduct) =
        PurchaseSource.Internal(product.fullIdentifier, mockk<PaywallViewState>(relaxed = true))

    @Test
    fun `begin from a paywall marks a paywall purchase as running`() {
        Given("no purchase in flight") {
            val product = product("p1")
            val source = paywall(product)

            When("a paywall purchase begins") {
                val state = TransactionState.Updates.Begin("a", product, source).reduce(TransactionState())

                Then("it is purchasing from a paywall") {
                    assertTrue(state.isPurchasing)
                    assertTrue(state.isPurchasingFromPaywall)
                    assertEquals(InFlightPurchase.Purchasing(product, source), state.inFlight["a"])
                }
            }
        }
    }

    @Test
    fun `an external purchase does not count as a paywall purchase`() {
        Given("no purchase in flight") {
            val product = product("p1")

            When("an external purchase begins") {
                val state =
                    TransactionState.Updates
                        .Begin("a", product, PurchaseSource.ExternalPurchase(product))
                        .reduce(TransactionState())

                Then("it is purchasing, but not from a paywall") {
                    assertTrue(state.isPurchasing)
                    assertFalse(state.isPurchasingFromPaywall)
                }
            }
        }
    }

    @Test
    fun `a pending purchase is kept by finish and superseded by a new purchase of its product`() {
        Given("a paywall purchase held for approval") {
            val product = product("p1")
            val pending =
                TransactionState.Updates.MarkPending("a").reduce(
                    TransactionState.Updates.Begin("a", product, paywall(product)).reduce(TransactionState()),
                )

            When("its attempt finishes") {
                val finished = TransactionState.Updates.Finish("a").reduce(pending)

                Then("it stays, as pending, and no longer blocks a paywall purchase") {
                    assertTrue(finished.inFlight["a"] is InFlightPurchase.Pending)
                    assertFalse(finished.isPurchasing)
                    assertFalse(finished.isPurchasingFromPaywall)
                }
            }

            When("a new purchase of the same product begins") {
                val again = TransactionState.Updates.Begin("b", product, paywall(product)).reduce(pending)

                Then("the pending entry is replaced by the new attempt") {
                    assertEquals(setOf("b"), again.inFlight.keys)
                }
            }
        }
    }

    @Test
    fun `finish removes a purchasing attempt and leaves others`() {
        Given("two attempts in flight") {
            val p1 = product("p1")
            val p2 = product("p2")
            val state =
                listOf(
                    TransactionState.Updates.Begin("a", p1, paywall(p1)),
                    TransactionState.Updates.Begin("b", p2, PurchaseSource.ExternalPurchase(p2)),
                ).fold(TransactionState()) { acc, update -> update.reduce(acc) }

            When("the first finishes") {
                val finished = TransactionState.Updates.Finish("a").reduce(state)

                Then("only the second remains") {
                    assertEquals(setOf("b"), finished.inFlight.keys)
                }
            }

            When("an unknown attempt finishes or goes pending") {
                val unchanged =
                    TransactionState.Updates.MarkPending("x").reduce(
                        TransactionState.Updates.Finish("x").reduce(state),
                    )

                Then("nothing changes") {
                    assertEquals(state, unchanged)
                }
            }
        }
    }

    @Test
    fun `set purchasing product ids replaces them`() {
        Given("stored purchasing ids") {
            val state = TransactionState(purchasingProductIds = setOf("a", "b"))

            When("they are set") {
                val updated = TransactionState.Updates.SetPurchasingProductIds(setOf("b")).reduce(state)

                Then("the new set is kept") {
                    assertEquals(setOf("b"), updated.purchasingProductIds)
                }
            }
        }
    }
}
