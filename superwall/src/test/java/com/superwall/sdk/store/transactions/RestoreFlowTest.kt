package com.superwall.sdk.store.transactions

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.delegate.subscription_controller.PurchaseController
import com.superwall.sdk.misc.AlertControllerFactory.AlertProps
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreFlowTest {
    private val tracked = mutableListOf<TrackableSuperwallEvent>()
    private val alerts = mutableListOf<AlertProps>()
    private val dismissed = mutableListOf<Pair<String, PaywallResult>>()
    private var status: SubscriptionStatus = SubscriptionStatus.Inactive
    private var web: Set<Entitlement> = emptySet()
    private var device: Set<Entitlement> = emptySet()
    private var webOpened = 0
    private val purchaseController = mockk<PurchaseController>()
    private val factory =
        mockk<TransactionManager.Factory>(relaxed = true) {
            every { isWebToAppEnabled() } returns false
            every { makeSuperwallOptions().paywalls.automaticallyDismiss } returns true
        }

    private val flow =
        RestoreFlow(
            TransactionDependencies(
                storeManager = mockk(relaxed = true),
                purchaseController = purchaseController,
                eventsQueue = mockk(relaxed = true),
                storage = mockk(relaxed = true),
                activityProvider = mockk(relaxed = true),
                factory = factory,
                ioScope = IOScope(Dispatchers.Unconfined),
                track = { tracked += it },
                dismiss = { key, result -> dismissed += key to result },
                showAlert = { alerts += it },
                subscriptionStatus = { status },
                entitlementsById = { setOf(Entitlement(it)) },
                allEntitlementsByProductId = { emptyMap() },
                webEntitlements = { web },
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
                activeDeviceEntitlements = { device },
                openRestoreOnWeb = { webOpened++ },
            ),
        )

    private fun paywall(vararg productIds: String) =
        mockk<PaywallView>(relaxed = true) {
            every { state.paywall.productIds } returns productIds.toList()
            every { state.info } returns PaywallInfo.empty()
        }

    private fun restoreEvents() = tracked.filterIsInstance<InternalSuperwallEvent.Restore>().map { it.state }

    @Test
    fun `a restore that covers the paywall completes and dismisses it`() =
        runTest {
            Given("a restore that leaves the user with the paywall's entitlement") {
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()
                status = SubscriptionStatus.Active(setOf(Entitlement("pro")))
                val view = paywall("pro")

                When("the paywall restores") {
                    val result = flow.restore(view, presentsFailureAlert = true)

                    Then("it tracks start and complete, dismisses the paywall and leaves the flow idle") {
                        assertTrue(result is RestorationResult.Restored)
                        assertTrue(restoreEvents().first() is InternalSuperwallEvent.Restore.State.Start)
                        assertTrue(restoreEvents().any { it is InternalSuperwallEvent.Restore.State.Complete })
                        assertTrue(tracked.any { it is InternalSuperwallEvent.Transaction })
                        assertEquals(1, dismissed.size)
                        assertTrue(dismissed.single().second is PaywallResult.Restored)
                        assertTrue(alerts.isEmpty())
                        assertEquals(RestoreState.Idle, flow.state.value)
                    }
                }
            }
        }

    @Test
    fun `a failed restore without web checkout shows the paywall's failure alert`() =
        runTest {
            Given("a restore that restores nothing, with web checkout off") {
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Failed(Exception("nope"))
                val view = paywall("pro")

                When("the paywall restores") {
                    flow.restore(view, presentsFailureAlert = true)

                    Then("it tracks the failure and alerts on the paywall, not through the web prompt") {
                        assertTrue(restoreEvents().any { it is InternalSuperwallEvent.Restore.State.Failure })
                        verify { view.showAlert(any(), any(), any(), any(), any(), any()) }
                        assertTrue(alerts.isEmpty())
                        assertTrue(dismissed.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `a failed restore with web checkout offers to restore on the web`() =
        runTest {
            Given("a restore that restores nothing, with web checkout on") {
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()
                every { factory.isWebToAppEnabled() } returns true

                When("a restore runs outside a paywall") {
                    flow.restore(null, presentsFailureAlert = true)

                    Then("the no-subscription web prompt shows, and its action opens restore on the web") {
                        val prompt = alerts.single()
                        assertEquals("No Subscription Found", prompt.title)
                        prompt.action!!.invoke()
                        assertEquals(1, webOpened)
                    }
                }
            }
        }

    @Test
    fun `a restore missing the paywall's web entitlements asks about the web and resets the paywall`() =
        runTest {
            Given("a restore that leaves the user active but without the paywall's entitlement, web checkout on") {
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Restored()
                every { factory.isWebToAppEnabled() } returns true
                status = SubscriptionStatus.Active(setOf(Entitlement("basic")))
                device = setOf(Entitlement("basic"))
                val view = paywall("pro")

                When("the paywall restores") {
                    flow.restore(view, presentsFailureAlert = true)

                    Then("the paywall is ready again and the restored-on-Play web prompt shows") {
                        verify {
                            view.updateState(
                                match { it is PaywallViewState.Updates.SetLoadingState && it.newState is PaywallLoadingState.Ready },
                            )
                        }
                        assertEquals("Restore via the web?", alerts.single().title)
                        assertFalse(restoreEvents().any { it is InternalSuperwallEvent.Restore.State.Complete })
                    }
                }
            }
        }

    @Test
    fun `a caller with its own UI gets no SDK prompt`() =
        runTest {
            Given("a failed restore with web checkout on") {
                coEvery { purchaseController.restorePurchases() } returns RestorationResult.Failed(null)
                every { factory.isWebToAppEnabled() } returns true

                When("the Customer Center restores without failure alerts") {
                    flow.restore(null, presentsFailureAlert = false)

                    Then("the failure is tracked but nothing is shown") {
                        assertTrue(restoreEvents().any { it is InternalSuperwallEvent.Restore.State.Failure })
                        assertTrue(alerts.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `the state counts restores while they run`() =
        runTest {
            Given("a restore waiting on the purchase controller") {
                val gate = CompletableDeferred<RestorationResult>()
                coEvery { purchaseController.restorePurchases() } coAnswers { gate.await() }
                val running = async(Dispatchers.Unconfined) { flow.restore(null, presentsFailureAlert = false) }

                Then("the flow is restoring until the controller answers") {
                    assertTrue(flow.state.value.isRestoring)
                    gate.complete(RestorationResult.Restored())
                    running.await()
                    assertEquals(RestoreState.Idle, flow.state.value)
                }
            }
        }

    @Test
    fun `an observed purchase that restored tracks a restore without dismissing`() =
        runTest {
            Given("an observer-mode product") {
                val product = mockk<com.superwall.sdk.store.abstractions.product.StoreProduct>(relaxed = true)

                When("its purchase turns out to be a restore") {
                    flow.restored(product, PurchaseSource.ObserverMode(product))

                    Then("a restore transaction is tracked and no paywall is dismissed") {
                        val transaction = tracked.filterIsInstance<InternalSuperwallEvent.Transaction>().single()
                        assertTrue(transaction.state is InternalSuperwallEvent.Transaction.State.Restore)
                        assertTrue(dismissed.isEmpty())
                    }
                }
            }
        }
}
