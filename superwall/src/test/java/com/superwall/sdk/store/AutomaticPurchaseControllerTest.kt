@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.billing.DecomposedProductIds
import com.superwall.sdk.billing.google.offerToken
import com.superwall.sdk.products.mockOneTimePurchaseOfferDetails
import com.superwall.sdk.products.mockPricingPhase
import com.superwall.sdk.products.mockProductDetails
import io.mockk.slot
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.StorePurchase
import com.superwall.sdk.billing.StorePurchaseResult
import com.superwall.sdk.billing.StorePurchaseState
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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

    private fun TestScope.makeController(
        store: BillingStore,
        appForegrounded: Flow<Unit> = emptyFlow(),
        // Lets a sync run to completion without a configured SDK
        configurationState: Flow<ConfigurationStatus> = MutableStateFlow(ConfigurationStatus.Configured),
        setSubscriptionStatus: (SubscriptionStatus) -> Unit = {},
    ): AutomaticPurchaseController =
        AutomaticPurchaseController(
            scope = IOScope(UnconfinedTestDispatcher(testScheduler)),
            store = store,
            entitlementsInfo = { entitlements },
            deviceEntitlementRecords = { emptySet() },
            passIdentifiersToPlayStore = { false },
            userId = { "user" },
            externalAccountId = { "external" },
            configurationState = { configurationState },
            setSubscriptionStatus = setSubscriptionStatus,
            appForegrounded = appForegrounded,
        )

    @Suppress("UNCHECKED_CAST")
    private fun hasInitialized(): MutableStateFlow<Boolean> =
        Superwall::class.java
            .getDeclaredField("_hasInitialized")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<Boolean>

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
    fun `purchases are read again when the app returns to the foreground`() =
        runTest {
            hasInitialized().value = true
            try {
                Given("a controller that has finished its launch sync") {
                    val store = store()
                    val foregrounded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
                    makeController(store, foregrounded)
                    advanceUntilIdle()
                    coVerify(exactly = 1) { store.activePurchases() }

                    When("the app returns to the foreground") {
                        foregrounded.emit(Unit)
                        advanceUntilIdle()

                        Then("Play is asked for the purchases again") {
                            coVerify(exactly = 2) { store.activePurchases() }
                        }
                    }
                }
            } finally {
                hasInitialized().value = false
            }
        }

    @Test
    fun `a foreground during the launch sync does not start a second sync`() =
        runTest {
            Given("a controller whose launch sync is waiting for the SDK to initialize") {
                val store = store()
                val foregrounded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
                makeController(store, foregrounded)

                When("the app comes to the foreground and the SDK then initializes") {
                    foregrounded.emit(Unit)
                    hasInitialized().value = true
                    try {
                        advanceUntilIdle()
                    } finally {
                        hasInitialized().value = false
                    }

                    Then("the purchases are read once") {
                        coVerify(exactly = 1) { store.activePurchases() }
                    }
                }
            }
        }

    @Test
    fun `a foreground after the launch sync read the purchases reads them again`() =
        runTest {
            hasInitialized().value = true
            try {
                Given("a launch sync that read an active purchase and is waiting for the config") {
                    val store = store(listOf(purchase("a", acknowledged = true)))
                    val configuration = MutableStateFlow<ConfigurationStatus>(ConfigurationStatus.Pending)
                    val foregrounded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
                    makeController(store, foregrounded, configuration)
                    advanceUntilIdle()
                    coVerify(exactly = 1) { store.activePurchases() }

                    When("the app returns to the foreground and the config then arrives") {
                        foregrounded.emit(Unit)
                        advanceUntilIdle()
                        coVerify(exactly = 1) { store.activePurchases() }
                        configuration.value = ConfigurationStatus.Configured
                        advanceUntilIdle()

                        Then("Play is asked for the purchases again once the launch sync has finished") {
                            coVerify(exactly = 2) { store.activePurchases() }
                        }
                    }
                }
            } finally {
                hasInitialized().value = false
            }
        }

    @Test
    fun `a sync that finishes after a later one does not publish its older read`() =
        runTest {
            hasInitialized().value = true
            Superwall.initialized = true
            try {
                Given("a sync that read an active subscription and is waiting for the config") {
                    val active = listOf(purchase("a", acknowledged = true))
                    val store = store(active)
                    every { entitlements.byProductId(any()) } returns setOf(Entitlement("pro"))
                    val configuration = MutableStateFlow<ConfigurationStatus>(ConfigurationStatus.Pending)
                    val published = mutableListOf<SubscriptionStatus>()
                    val controller =
                        makeController(store, configurationState = configuration, setSubscriptionStatus = { published += it })
                    advanceUntilIdle()

                    When("a later sync finds the subscription gone, and the older one finishes after it") {
                        coEvery { store.activePurchases() } returns Result.success(emptyList())
                        // Nothing active only waits for the config to settle, so this one publishes.
                        configuration.value = ConfigurationStatus.Failed
                        controller.restorePurchases()
                        val afterLaterSync = published.toList()
                        // And this lets the older one through.
                        configuration.value = ConfigurationStatus.Configured
                        advanceUntilIdle()

                        Then("only the later read is published") {
                            assertEquals(1, afterLaterSync.size)
                            assertTrue(afterLaterSync.single() !is SubscriptionStatus.Active)
                            assertEquals(afterLaterSync, published)
                        }
                    }
                }
            } finally {
                Superwall.initialized = false
                hasInitialized().value = false
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

    // A monthly base plan with a 7-day free trial offer and a cheaper intro offer, as Play returns it.
    private fun subscriptionWithTrial() =
        mockProductDetails(
            productId = "sub",
            subscriptionOfferDetails =
                listOf(
                    offer(offerId = null, token = "base-token", mockPricingPhase(price = 9.99)),
                    offer(
                        offerId = "trial",
                        token = "trial-token",
                        mockPricingPhase(price = 0.0, billingPeriod = "P1W", billingCycleCount = 1),
                        mockPricingPhase(price = 9.99),
                    ),
                    offer(
                        offerId = "intro",
                        token = "intro-token",
                        mockPricingPhase(price = 4.99, billingCycleCount = 1),
                        mockPricingPhase(price = 9.99),
                    ),
                ),
        )

    private fun offer(
        offerId: String?,
        token: String,
        vararg phases: ProductDetails.PricingPhase,
    ): ProductDetails.SubscriptionOfferDetails =
        mockk {
            every { offerTags } returns emptyList()
            every { offerToken } returns token
            every { getOfferId() } returns offerId
            every { basePlanId } returns "monthly"
            every { pricingPhases } returns mockk { every { pricingPhaseList } returns phases.toList() }
        }

    /**
     * Buys the paywall product [fullId] the way TransactionManager does, and returns the offer
     * token the controller sends to Play.
     */
    private suspend fun TestScope.offerTokenSentToPlay(
        details: ProductDetails,
        fullId: String,
    ): String? {
        val ids = DecomposedProductIds.from(fullId)
        val paywallProduct =
            RawStoreProduct(
                underlyingProductDetails = details,
                fullIdentifier = fullId,
                basePlanType = ids.basePlanType,
                offerType = ids.offerType,
            )
        val sent = slot<StoreProduct>()
        val store = store()
        coEvery { store.purchase(any(), capture(sent), any()) } returns StorePurchaseResult.Purchased(emptyList())

        makeController(store).purchase(
            mockk<Activity>(relaxed = true),
            StoreProduct(paywallProduct),
            basePlanId = paywallProduct.basePlanId,
            offerId = paywallProduct.offerId,
            replacement = null,
        )

        val bought = sent.captured.rawStoreProduct!!
        return bought.offerToken(bought.offerId)
    }

    @Test
    fun `sw-none buys the base plan even when the base plan has a free trial offer`() =
        runTest {
            Given("a base plan with a free trial offer") {
                val details = subscriptionWithTrial()

                When("the paywall's sw-none product is bought") {
                    val token = offerTokenSentToPlay(details, "sub:monthly:sw-none")

                    Then("Play gets the base plan's token, not the trial's") {
                        assertEquals("base-token", token)
                    }
                }
            }
        }

    @Test
    fun `sw-auto buys the longest free trial`() =
        runTest {
            Given("a base plan with a free trial offer") {
                val details = subscriptionWithTrial()

                When("the paywall's sw-auto product is bought") {
                    val token = offerTokenSentToPlay(details, "sub:monthly:sw-auto")

                    Then("Play gets the trial's token") {
                        assertEquals("trial-token", token)
                    }
                }
            }
        }

    @Test
    fun `a named offer buys that offer`() =
        runTest {
            Given("a base plan with a free trial and an intro offer") {
                val details = subscriptionWithTrial()

                When("the paywall's product names the intro offer") {
                    val token = offerTokenSentToPlay(details, "sub:monthly:intro")

                    Then("Play gets the intro offer's token") {
                        assertEquals("intro-token", token)
                    }
                }
            }
        }

    @Test
    fun `sw-none on a one-time product buys the purchase option without its discount`() =
        runTest {
            Given("a one-time purchase option with a discount offer") {
                val details =
                    mockProductDetails(
                        productId = "lifetime",
                        type = BillingClient.ProductType.INAPP,
                        oneTimePurchaseOfferDetailsList =
                            listOf(
                                mockOneTimePurchaseOfferDetails(price = 49.99, purchaseOptionId = "buy", offerToken = "full-token"),
                                mockOneTimePurchaseOfferDetails(
                                    price = 29.99,
                                    purchaseOptionId = "buy",
                                    offerId = "promo",
                                    offerToken = "promo-token",
                                ),
                            ),
                        subscriptionOfferDetails = null,
                    )

                When("the paywall's sw-none product is bought") {
                    val token = offerTokenSentToPlay(details, "lifetime:buy:sw-none")

                    Then("Play gets the full-price option's token") {
                        assertEquals("full-token", token)
                    }
                }
            }
        }
}
