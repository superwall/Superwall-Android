package com.superwall.sdk.store.transactions

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.storage.Storage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionContextTest {
    private fun dependencies(
        storage: Storage = mockk(relaxed = true),
        track: suspend (TrackableSuperwallEvent) -> Unit = {},
        factory: TransactionManager.Factory = mockk(relaxed = true),
    ) = TransactionDependencies(
        storeManager = mockk(relaxed = true),
        purchaseController = mockk(relaxed = true),
        eventsQueue = mockk(relaxed = true),
        storage = storage,
        activityProvider = mockk(relaxed = true),
        factory = factory,
        ioScope = mockk(relaxed = true),
        track = track,
        dismiss = { _, _ -> },
        showAlert = {},
        subscriptionStatus = { mockk() },
        entitlementsById = { emptySet() },
        allEntitlementsByProductId = { emptyMap() },
        webEntitlements = { emptySet() },
        currentCustomerInfo = { mockk() },
        showRestoreDialogForWeb = {},
        notifyBackendOfReceipts = {},
        refreshReceipt = {},
        updateState = { _, _ -> },
        notifyOfTransactionComplete = { _, _, _, _ -> },
        notifyOfTransactionAbandon = {},
        testMode = null,
        testModeTransactionHandler = null,
        setSubscriptionStatus = null,
        activeDeviceEntitlements = { emptySet() },
        openRestoreOnWeb = {},
    )

    private class TestTransactionContext(
        override val dependencies: TransactionDependencies,
        override val scope: CoroutineScope,
    ) : TransactionContext {
        override val actor = StateActor<TransactionContext, TransactionState>(TransactionState(), scope)
    }

    private class TestRestoreContext(
        override val dependencies: TransactionDependencies,
        override val scope: CoroutineScope,
    ) : RestoreContext {
        override val actor = StateActor<RestoreContext, RestoreState>(RestoreState.Idle, scope)
    }

    private class TestObserverModeContext(
        override val dependencies: TransactionDependencies,
        override val scope: CoroutineScope,
    ) : ObserverModeContext {
        override val actor = StateActor<ObserverModeContext, ObserverModeState>(ObserverModeState(), scope)
    }

    @Test
    fun `every context reads storage and tracking from the shared dependencies`() =
        runTest {
            Given("one set of transaction dependencies") {
                val storage = mockk<Storage>(relaxed = true)
                val tracked = mutableListOf<TrackableSuperwallEvent>()
                val deps = dependencies(storage = storage, track = { tracked += it })
                val scope = TestScope(StandardTestDispatcher(testScheduler))

                When("the three contexts are built on it") {
                    val contexts =
                        listOf(
                            TestTransactionContext(deps, scope),
                            TestRestoreContext(deps, scope),
                            TestObserverModeContext(deps, scope),
                        )
                    val event = mockk<TrackableSuperwallEvent>()
                    contexts.forEach { it.tracker(event) }

                    Then("each uses the same storage and the same tracker") {
                        contexts.forEach { assertSame(storage, it.storage) }
                        assertEquals(listOf(event, event, event), tracked)
                    }
                }
            }
        }

    @Test
    fun `demand and observe option are read fresh from the factory`() {
        Given("a factory whose demand and options change") {
            var score = 1
            val options = SuperwallOptions().apply { shouldObservePurchases = false }
            val factory =
                mockk<TransactionManager.Factory>(relaxed = true) {
                    every { demandScore() } answers { score }
                    every { demandTier() } returns "tier"
                    every { makeSuperwallOptions() } returns options
                }
            val deps = dependencies(factory = factory)

            When("they change after the dependencies are built") {
                val before = deps.demand()
                score = 2
                options.shouldObservePurchases = true

                Then("each read sees the current value") {
                    assertEquals(TransactionAnalytics.Demand(1, "tier"), before)
                    assertEquals(TransactionAnalytics.Demand(2, "tier"), deps.demand())
                    assertTrue(deps.observesPurchases)
                }
            }
        }
    }

    @Test
    fun `state types start empty`() {
        Given("fresh transaction, restore and observer-mode states") {
            Then("nothing is in flight, no restore runs and nothing is observed") {
                assertEquals(false, TransactionState().isPurchasing)
                assertSame(RestoreState.Idle, RestoreState.Idle)
                assertEquals(null, ObserverModeState().latest)
            }
        }
    }
}
