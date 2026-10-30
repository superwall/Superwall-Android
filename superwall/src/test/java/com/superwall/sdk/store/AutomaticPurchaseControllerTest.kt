@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.superwall.sdk.billing.Store as BillingStore

/**
 * The automatic controller owns no billing client: it buys, queries and acknowledges through the
 * SDK's [BillingStore], so the SDK sees every purchase it makes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticPurchaseControllerTest {
    private val entitlements =
        mockk<Entitlements>(relaxed = true) {
            every { status } returns MutableStateFlow(SubscriptionStatus.Unknown)
            every { web } returns emptySet()
        }

    private val externalPurchases = MutableSharedFlow<StorePurchase>(extraBufferCapacity = 8)

    private fun store(
        purchases: List<StorePurchase> = emptyList(),
        onAcknowledge: suspend (StorePurchase) -> Result<Unit> = { Result.success(Unit) },
    ): BillingStore =
        mockk(relaxed = true) {
            every { this@mockk.externalPurchases } returns this@AutomaticPurchaseControllerTest.externalPurchases
            coEvery { activePurchases() } returns Result.success(purchases)
            coEvery { ownedPurchases() } returns purchases
            coEvery { acknowledge(any()) } coAnswers { onAcknowledge(firstArg()) }
        }

    private fun TestScope.makeController(store: BillingStore): AutomaticPurchaseController =
        AutomaticPurchaseController(
            scope = IOScope(UnconfinedTestDispatcher(testScheduler)),
            store = store,
            entitlementsInfo = { entitlements },
            deviceEntitlementRecords = { emptySet() },
            passIdentifiersToPlayStore = { false },
            userId = { "user" },
            externalAccountId = { "external" },
            // Lets a sync run to completion without a configured SDK
            configurationState = { MutableStateFlow(ConfigurationStatus.Configured) },
            setSubscriptionStatus = {},
        )

    private fun purchase(
        token: String,
        state: StorePurchaseState = StorePurchaseState.Purchased,
        acknowledged: Boolean = false,
    ) = StorePurchase(
        token = token,
        orderId = "GPA.$token",
        productIds = listOf("product_$token"),
        purchaseTime = 0L,
        state = state,
        autoRenewing = true,
        acknowledged = acknowledged,
    )

    private fun product(): StoreProduct =
        mockk(relaxed = true) {
            every { rawStoreProduct } returns mockk<RawStoreProduct>(relaxed = true)
        }

    private suspend fun AutomaticPurchaseController.buy() = purchase(mockk<Activity>(relaxed = true), product(), null, null, null)

    @Test
    fun `purchase fails instead of hanging when billing is unavailable`() =
        runTest {
            Given("a store that can't connect to billing") {
                val store = store()
                coEvery { store.purchase(any(), any(), any()) } returns
                    StorePurchaseResult.Failed(Throwable("Billing client not ready"))
                val controller = makeController(store)

                When("a purchase is attempted") {
                    val result = controller.buy()

                    Then("it fails as billing unavailable") {
                        assertEquals(
                            "Google Play Billing is not available on this device.",
                            (result as PurchaseResult.Failed).errorMessage,
                        )
                    }
                }
            }
        }

    @Test
    fun `purchase returns the result of its own billing flow, with the purchase`() =
        runTest {
            Given("a store whose flow reports a purchase") {
                val bought = purchase("token")
                val store = store()
                coEvery { store.purchase(any(), any(), any()) } returns StorePurchaseResult.Purchased(listOf(bought))
                val controller = makeController(store)

                When("the product is bought") {
                    val result = controller.buy()

                    Then("the result carries the purchase") {
                        assertEquals(listOf(bought), (result as PurchaseResult.Purchased).purchases)
                    }

                    And("the account id is passed to the store") {
                        coVerify { store.purchase(any(), any(), match { it.obfuscatedAccountId == "external" }) }
                    }
                }
            }
        }

    @Test
    fun `purchase maps a cancelled flow to Cancelled and a Play error to its message`() =
        runTest {
            Given("a store whose flows are cancelled, then fail with ITEM_ALREADY_OWNED") {
                val store = store()
                coEvery { store.purchase(any(), any(), any()) } returnsMany
                    listOf(
                        StorePurchaseResult.Cancelled,
                        StorePurchaseResult.Failed(Exception(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED.toString())),
                    )
                val controller = makeController(store)

                When("two purchases are attempted") {
                    val cancelled = controller.buy()
                    val failed = controller.buy()

                    Then("the first is cancelled and the second fails with Play's error") {
                        assertTrue(cancelled is PurchaseResult.Cancelled)
                        assertNotEquals(
                            "Google Play Billing is not available on this device.",
                            (failed as PurchaseResult.Failed).errorMessage,
                        )
                    }
                }
            }
        }

    @Test
    fun `sync acknowledges purchases that were never acknowledged`() =
        runTest {
            Given("a purchase that completed outside the billing flow") {
                val acknowledged = mutableListOf<String>()
                val store =
                    store(
                        purchases =
                            listOf(
                                purchase("unacknowledged"),
                                purchase("already", acknowledged = true),
                                purchase("pending", state = StorePurchaseState.Pending),
                            ),
                        onAcknowledge = {
                            acknowledged += it.token
                            Result.success(Unit)
                        },
                    )
                val controller = makeController(store)
                advanceUntilIdle()

                When("the subscription status is synced") {
                    controller.restorePurchases()
                    advanceUntilIdle()

                    Then("only the purchased, unacknowledged purchase is acknowledged") {
                        assertEquals(listOf("unacknowledged"), acknowledged)
                    }
                }
            }
        }

    @Test
    fun `failed acknowledgements are retried`() =
        runTest {
            Given("a purchase whose first acknowledgement fails") {
                var calls = 0
                val store =
                    store(
                        purchases = listOf(purchase("token")),
                        onAcknowledge = {
                            if (calls++ == 0) Result.failure(Throwable("SERVICE_UNAVAILABLE")) else Result.success(Unit)
                        },
                    )
                val controller = makeController(store)
                advanceUntilIdle()

                When("the subscription status is synced") {
                    controller.restorePurchases()
                    advanceUntilIdle()

                    Then("the acknowledgement is retried until it succeeds") {
                        assertEquals(2, calls)
                    }
                }
            }
        }

    @Test
    fun `a purchase seen by the purchase update and the sync is acknowledged once`() =
        runTest {
            Given("an acknowledgement that has not answered yet") {
                val bought = purchase("token")
                val answer = CompletableDeferred<Result<Unit>>()
                val store = store(purchases = listOf(bought), onAcknowledge = { answer.await() })
                val controller = makeController(store)
                advanceUntilIdle()

                When("the store reports the purchase and the sync sees it too") {
                    externalPurchases.emit(bought)
                    controller.restorePurchases()

                    Then("it is acknowledged once") {
                        coVerify(exactly = 1) { store.acknowledge(bought) }
                    }
                }
                answer.complete(Result.success(Unit))
            }
        }

    @Test
    fun `a failed read falls back to the purchases the store could read`() =
        runTest {
            Given("a store whose full read fails but which owns an unacknowledged purchase") {
                val bought = purchase("token")
                val store = store(purchases = listOf(bought))
                coEvery { store.activePurchases() } returns Result.failure(Throwable("Play unavailable"))
                val controller = makeController(store)
                advanceUntilIdle()

                When("the subscription status is synced") {
                    controller.restorePurchases()
                    advanceUntilIdle()

                    Then("the purchases it could read are still acknowledged") {
                        coVerify(atLeast = 1) { store.ownedPurchases() }
                        coVerify(exactly = 1) { store.acknowledge(bought) }
                    }
                }
            }
        }
}
