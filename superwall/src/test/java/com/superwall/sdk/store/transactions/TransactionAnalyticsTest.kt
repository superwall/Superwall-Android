package com.superwall.sdk.store.transactions

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.Transaction.TransactionSource
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionAnalyticsTest {
    private val paywallInfo = mockk<PaywallInfo>(relaxed = true)
    private val state = mockk<PaywallViewState>(relaxed = true) { every { info } returns paywallInfo }
    private val demand = TransactionAnalytics.Demand(score = 42, tier = "gold")

    private fun product(
        subscription: Boolean = true,
        custom: Boolean = false,
    ) = mockk<StoreProduct>(relaxed = true) {
        every { fullIdentifier } returns "sub:base"
        every { subscriptionPeriod } returns if (subscription) mockk(relaxed = true) else null
        every { isCustomProduct } returns custom
    }

    private val internal = PurchaseSource.Internal("sub:base", state)

    private fun external(product: StoreProduct) = PurchaseSource.ExternalPurchase(product)

    private fun observer(product: StoreProduct) = PurchaseSource.ObserverMode(product)

    @Test
    fun `start reports the paywall for an internal purchase`() {
        Given("a purchase started from a paywall") {
            val product = product()
            When("the start event is built") {
                val event = TransactionAnalytics.start(product, internal, demand)
                Then("it is internal, not observed, carries the paywall and the demand") {
                    assertTrue(event.state is InternalSuperwallEvent.Transaction.State.Start)
                    assertSame(paywallInfo, event.paywallInfo)
                    assertEquals(TransactionSource.INTERNAL, event.source)
                    assertFalse(event.isObserved)
                    assertEquals(42, event.demandScore)
                    assertEquals("gold", event.demandTier)
                    assertNull(event.model)
                }
            }
        }
    }

    @Test
    fun `start marks observer mode as observed and external`() {
        Given("a purchase the SDK only observes") {
            val product = product()
            When("the start event is built") {
                val event = TransactionAnalytics.start(product, observer(product), demand)
                Then("it is external and observed") {
                    assertEquals(TransactionSource.EXTERNAL, event.source)
                    assertTrue(event.isObserved)
                }
            }
        }
    }

    @Test
    fun `abandon of an external purchase is external and not observed`() {
        Given("an external purchase the user cancelled") {
            val product = product()
            When("the abandon event is built") {
                val event = TransactionAnalytics.abandon(product, external(product), demand)
                Then("it is external, not observed and an abandon") {
                    assertTrue(event.state is InternalSuperwallEvent.Transaction.State.Abandon)
                    assertEquals(TransactionSource.EXTERNAL, event.source)
                    assertFalse(event.isObserved)
                }
            }
        }
    }

    @Test
    fun `failure and pending of a paywall purchase report the observe option`() {
        Given("a paywall purchase while the SDK observes purchases") {
            val product = product()
            When("the failure and pending events are built") {
                val failure = TransactionAnalytics.failure("boom", product, internal, observesPurchases = true, demand)
                val pending = TransactionAnalytics.pending(internal, observesPurchases = true, demand)
                Then("both are internal and observed, and carry their errors") {
                    assertEquals(TransactionSource.INTERNAL, failure.source)
                    assertTrue(failure.isObserved)
                    val fail = failure.state as InternalSuperwallEvent.Transaction.State.Fail
                    assertEquals("boom", (fail.error as TransactionError.Failure).message)
                    assertTrue(pending.isObserved)
                    assertNull(pending.product)
                    val pend = pending.state as InternalSuperwallEvent.Transaction.State.Fail
                    assertTrue(pend.error is TransactionError.Pending)
                }
            }
        }
    }

    @Test
    fun `failure of an external purchase ignores the observe option`() {
        Given("an external purchase while the SDK observes purchases") {
            val product = product()
            When("the failure event is built") {
                val event = TransactionAnalytics.failure("boom", product, external(product), observesPurchases = true, demand)
                Then("it is external and not observed") {
                    assertEquals(TransactionSource.EXTERNAL, event.source)
                    assertFalse(event.isObserved)
                }
            }
        }
    }

    @Test
    fun `complete carries the transaction, attributes and storefront`() {
        Given("a completed paywall purchase") {
            val product = product()
            val transaction = mockk<StoreTransaction>()
            When("the complete event is built") {
                val event =
                    TransactionAnalytics.complete(
                        product = product,
                        transaction = transaction,
                        purchaseSource = internal,
                        demand = demand,
                        userAttributes = mapOf("a" to 1),
                        storefrontCountryCode = "US",
                    )
                Then("it holds every input and is not observed") {
                    assertSame(transaction, event.model)
                    assertEquals(mapOf("a" to 1), event.userAttributes)
                    assertEquals("US", event.storefrontCountryCode)
                    assertFalse(event.isObserved)
                    assertEquals(TransactionSource.INTERNAL, event.source)
                }
            }
        }
    }

    @Test
    fun `every transaction event of a custom product reports the CUSTOM store`() {
        Given("a custom product bought through the developer's checkout") {
            val product = product(custom = true)
            When("each transaction event is built") {
                val events =
                    listOf(
                        TransactionAnalytics.start(product, internal, demand),
                        TransactionAnalytics.abandon(product, internal, demand),
                        TransactionAnalytics.failure("boom", product, internal, observesPurchases = false, demand),
                        TransactionAnalytics.complete(product, null, external(product), demand, null, null),
                        TransactionAnalytics.restored(product, RestoreType.ViaRestore, internal, false, demand),
                    )
                Then("they all report CUSTOM, not Google Play") {
                    events.forEach { assertEquals("CUSTOM", it.store) }
                }
            }
        }
    }

    @Test
    fun `transaction events of a Play product report the PLAY_STORE store`() {
        Given("a Google Play product") {
            val product = product()
            When("the start, complete and pending events are built") {
                val events =
                    listOf(
                        TransactionAnalytics.start(product, internal, demand),
                        TransactionAnalytics.complete(product, null, internal, demand, null, null),
                        TransactionAnalytics.pending(internal, observesPurchases = false, demand),
                    )
                Then("they report PLAY_STORE") {
                    events.forEach { assertEquals("PLAY_STORE", it.store) }
                }
            }
        }
    }

    @Test
    fun `a custom transaction_complete sends store CUSTOM`() =
        runTest {
            Given("a completed custom product purchase") {
                val event =
                    TransactionAnalytics.complete(
                        product = product(custom = true),
                        transaction = null,
                        purchaseSource = internal,
                        demand = demand,
                        userAttributes = null,
                        storefrontCountryCode = null,
                    )
                When("its parameters are built") {
                    val params = event.getSuperwallParameters()
                    Then("the store parameter is CUSTOM") {
                        assertEquals("CUSTOM", params["store"])
                    }
                }
            }
        }

    @Test
    fun `purchaseKind picks one-time, trial or subscription`() {
        Given("three completed purchases") {
            When("the purchase kind is built for each") {
                val oneTime = TransactionAnalytics.purchaseKind(product(subscription = false), internal, didStartFreeTrial = true)
                val trial = TransactionAnalytics.purchaseKind(product(), internal, didStartFreeTrial = true)
                val sub = TransactionAnalytics.purchaseKind(product(), internal, didStartFreeTrial = false)
                Then("a product without a period is one-time, else trial or subscription") {
                    assertTrue(oneTime is InternalSuperwallEvent.NonRecurringProductPurchase)
                    assertTrue(trial is InternalSuperwallEvent.FreeTrialStart)
                    assertTrue(sub is InternalSuperwallEvent.SubscriptionStart)
                }
            }
        }
    }

    @Test
    fun `restored keeps observer mode as its own source`() {
        Given("a restore found during an observed purchase") {
            val product = product()
            When("the restored event is built") {
                val event =
                    TransactionAnalytics.restored(
                        product = product,
                        restoreType = RestoreType.ViaRestore,
                        purchaseSource = observer(product),
                        observesPurchases = false,
                        demand = demand,
                    )
                Then("the source is observer and observed follows the option") {
                    assertEquals(TransactionSource.OBSERVER, event.source)
                    assertFalse(event.isObserved)
                    assertTrue(event.state is InternalSuperwallEvent.Transaction.State.Restore)
                }
            }
        }
    }

    @Test
    fun `restore failure message explains a restored but inactive user`() {
        Given("a restore that reported restored but left no entitlements") {
            When("the failure message is built") {
                val restoredInactive =
                    TransactionAnalytics.restoreFailureMessage(RestorationResult.Restored(), hasEntitlements = false)
                val failed =
                    TransactionAnalytics.restoreFailureMessage(
                        RestorationResult.Failed(Exception("network")),
                        hasEntitlements = false,
                    )
                Then("each names its cause") {
                    assertTrue(restoredInactive.startsWith("Transactions Failed to Restore."))
                    assertTrue(restoredInactive.contains("subscription status is \"inactive\""))
                    assertTrue(failed.endsWith("Original restoration error message: network"))
                }
            }
        }
    }

    @Test
    fun `restore events carry their state`() {
        Given("a paywall restoring") {
            When("the restore events are built") {
                val start = TransactionAnalytics.restoreStart(paywallInfo)
                val complete = TransactionAnalytics.restoreComplete(paywallInfo)
                val failure = TransactionAnalytics.restoreFailure("nope", paywallInfo)
                Then("each has its state and the paywall") {
                    assertEquals(InternalSuperwallEvent.Restore.State.Start, start.state)
                    assertEquals(InternalSuperwallEvent.Restore.State.Complete, complete.state)
                    assertEquals("nope", (failure.state as InternalSuperwallEvent.Restore.State.Failure).reason)
                    assertSame(paywallInfo, failure.paywallInfo)
                }
            }
        }
    }
}
