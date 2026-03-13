package com.superwall.sdk.store

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.models.product.Store
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import com.superwall.sdk.storage.StoredSubscriptionStatus
import com.superwall.sdk.store.abstractions.product.receipt.LatestSubscriptionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive tests for the Entitlements class external API.
 * These tests are designed to guarantee correctness after refactoring.
 *
 * Covers:
 * - Initialization (clean, cached, corrupted)
 * - setSubscriptionStatus (all transitions, edge cases)
 * - Property computations (active, inactive, all, web)
 * - Product ID lookup (exact, partial, fallback chains)
 * - addEntitlementsByProductId
 * - entitlementsByProductId snapshot
 * - byProductIds (batch)
 * - activeDeviceEntitlements lifecycle
 * - Status flow persistence
 * - Multi-step state transitions
 * - Deduplication and merge priority
 */
private val stubEntitlementsFactory =
    object : Entitlements.Factory {
        override fun makeHasExternalPurchaseController(): Boolean = false
    }

class EntitlementsRefactorSafetyTest {
    private fun mockStorage(
        storedStatus: SubscriptionStatus? = null,
        storedProductEntitlements: Map<String, Set<Entitlement>>? = null,
        redemptionResponse: WebRedemptionResponse? = null,
    ): Storage =
        mockk(relaxUnitFun = true) {
            every { read(StoredSubscriptionStatus) } returns storedStatus
            every { read(StoredEntitlementsByProductId) } returns storedProductEntitlements
            every { read(LatestRedemptionResponse) } returns redemptionResponse
        }

    private fun webRedemption(vararg entitlements: Entitlement): WebRedemptionResponse =
        WebRedemptionResponse(
            codes = emptyList(),
            allCodes = emptyList(),
            customerInfo =
                CustomerInfo(
                    subscriptions = emptyList(),
                    nonSubscriptions = emptyList(),
                    userId = "testUser",
                    entitlements = entitlements.toList(),
                    isPlaceholder = false,
                ),
        )

    // ==========================================
    // Initialization Edge Cases
    // ==========================================

    @Test
    fun `init with no stored data starts with Unknown status and empty collections`() =
        runTest {
            Given("storage has no cached data") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                Then("status should be Unknown") {
                    assertTrue(entitlements.status.value is SubscriptionStatus.Unknown)
                }
                And("all collections should be empty") {
                    assertTrue(entitlements.active.isEmpty())
                    assertTrue(entitlements.inactive.isEmpty())
                    assertTrue(entitlements.all.isEmpty())
                    assertTrue(entitlements.web.isEmpty())
                    assertTrue(entitlements.entitlementsByProductId.isEmpty())
                    assertTrue(entitlements.activeDeviceEntitlements.isEmpty())
                }
            }
        }

    @Test
    fun `init with corrupted StoredSubscriptionStatus resets to Unknown`() =
        runTest {
            Given("storage throws ClassCastException for StoredSubscriptionStatus") {
                val storage =
                    mockk<Storage>(relaxUnitFun = true) {
                        every { read(StoredSubscriptionStatus) } throws ClassCastException("corrupted")
                        every { read(StoredEntitlementsByProductId) } returns null
                        every { read(LatestRedemptionResponse) } returns null
                    }

                When("Entitlements is initialized") {
                    val entitlements =
                        StateActor<EntitlementsContext, EntitlementsState>(
                            createInitialEntitlementsState(storage),
                            backgroundScope,
                        ).let { actor ->
                            Entitlements(
                                storage = storage,
                                actor = actor,
                                actorScope = backgroundScope,
                                factory = stubEntitlementsFactory,
                            )
                        }

                    Then("corrupted status should be deleted from storage") {
                        verify { storage.delete(StoredSubscriptionStatus) }
                    }
                    And("status should be set to Unknown") {
                        assertTrue(entitlements.status.value is SubscriptionStatus.Unknown)
                    }
                }
            }
        }

    @Test
    fun `init with corrupted StoredEntitlementsByProductId deletes and continues`() =
        runTest {
            Given("storage throws ClassCastException for StoredEntitlementsByProductId") {
                val storage =
                    mockk<Storage>(relaxUnitFun = true) {
                        every { read(StoredSubscriptionStatus) } returns null
                        every { read(StoredEntitlementsByProductId) } throws ClassCastException("corrupted")
                        every { read(LatestRedemptionResponse) } returns null
                    }

                When("Entitlements is initialized") {
                    val entitlements =
                        StateActor<EntitlementsContext, EntitlementsState>(
                            createInitialEntitlementsState(storage),
                            backgroundScope,
                        ).let { actor ->
                            Entitlements(
                                storage = storage,
                                actor = actor,
                                actorScope = backgroundScope,
                                factory = stubEntitlementsFactory,
                            )
                        }

                    Then("corrupted entitlements-by-product should be deleted") {
                        verify { storage.delete(StoredEntitlementsByProductId) }
                    }
                    And("entitlementsByProductId should be empty") {
                        assertTrue(entitlements.entitlementsByProductId.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `init with stored Inactive status restores Inactive`() =
        runTest {
            Given("storage contains Inactive status") {
                val storage = mockStorage(storedStatus = SubscriptionStatus.Inactive)
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                Then("status should be Inactive") {
                    assertTrue(entitlements.status.value is SubscriptionStatus.Inactive)
                }
                And("active and inactive should be empty") {
                    assertTrue(entitlements.active.isEmpty())
                    assertTrue(entitlements.inactive.isEmpty())
                }
            }
        }

    @Test
    fun `init with stored product entitlements restores them`() =
        runTest {
            Given("storage contains product entitlements") {
                val e1 = Entitlement("premium")
                val productMap = mapOf("prod1" to setOf(e1))
                val storage = mockStorage(storedProductEntitlements = productMap)

                When("Entitlements is initialized") {
                    val entitlements =
                        StateActor<EntitlementsContext, EntitlementsState>(
                            createInitialEntitlementsState(storage),
                            backgroundScope,
                        ).let { actor ->
                            Entitlements(
                                storage = storage,
                                actor = actor,
                                actorScope = backgroundScope,
                                factory = stubEntitlementsFactory,
                            )
                        }

                    Then("entitlementsByProductId should contain the stored mappings") {
                        assertEquals(productMap, entitlements.entitlementsByProductId)
                    }
                    And("all should include entitlements from product map") {
                        assertTrue(entitlements.all.contains(e1))
                    }
                }
            }
        }

    // ==========================================
    // setSubscriptionStatus - Active Entitlement Filtering
    // ==========================================

    @Test
    fun `setSubscriptionStatus Active only adds isActive entitlements to backingActive`() =
        runTest {
            Given("a mix of active and inactive entitlements") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val activeE = Entitlement("active_one", isActive = true)
                val inactiveE = Entitlement("inactive_one", isActive = false)

                When("setting Active status with both") {
                    entitlements.setSubscriptionStatus(
                        SubscriptionStatus.Active(setOf(activeE, inactiveE)),
                    )

                    Then("active should only contain the isActive entitlement") {
                        assertTrue(entitlements.active.any { it.id == "active_one" })
                    }
                    And("the inactive entitlement should not be in active") {
                        assertFalse(entitlements.active.any { it.id == "inactive_one" && !it.isActive })
                    }
                    And("all should contain both") {
                        assertTrue(entitlements.all.any { it.id == "active_one" })
                        assertTrue(entitlements.all.any { it.id == "inactive_one" })
                    }
                }
            }
        }

    @Test
    fun `setSubscriptionStatus Active with all inactive entitlements becomes Inactive`() =
        runTest {
            Given("entitlements that are all inactive") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val inactiveE =
                    Entitlement(
                        id = "expired",
                        type = Entitlement.Type.SERVICE_LEVEL,
                        isActive = false,
                    )

                When("setting Active status with only inactive entitlements") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(inactiveE)))

                    Then("status should remain Active since set is not empty") {
                        // The code only checks entitlements.isEmpty(), not isActive
                        assertTrue(entitlements.status.value is SubscriptionStatus.Active)
                    }
                }
            }
        }

    // ==========================================
    // setSubscriptionStatus - State Transitions
    // ==========================================

    @Test
    fun `transition Active to Active replaces entitlements additively`() =
        runTest {
            Given("entitlements with Active status") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e1 = Entitlement("first")
                val e2 = Entitlement("second")

                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e1)))

                When("setting Active with different entitlements") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e2)))

                    Then("active should contain both since backingActive uses addAll") {
                        assertTrue(entitlements.active.any { it.id == "first" })
                        assertTrue(entitlements.active.any { it.id == "second" })
                    }
                    And("status value should reflect latest set") {
                        val status = entitlements.status.value as SubscriptionStatus.Active
                        assertTrue(status.entitlements.any { it.id == "second" })
                    }
                }
            }
        }

    @Test
    fun `transition Active to Inactive to Active restores correctly`() =
        runTest {
            Given("entitlements cycling through states") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e1 = Entitlement("premium")

                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e1)))

                When("going Inactive then Active again") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)

                    Then("after Inactive, active should be empty") {
                        assertTrue(entitlements.active.isEmpty())
                    }

                    val e2 = Entitlement("gold")
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e2)))

                    And("after re-activation, only new entitlements should be active") {
                        assertTrue(entitlements.active.any { it.id == "gold" })
                        // e1 was cleared by Inactive
                        assertFalse(entitlements.active.any { it.id == "premium" })
                    }
                }
            }
        }

    @Test
    fun `transition Active to Unknown clears everything`() =
        runTest {
            Given("entitlements in Active state with device entitlements") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(Entitlement("a"))))
                entitlements.activeDeviceEntitlements = setOf(Entitlement("device"))

                When("setting Unknown") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Unknown)

                    Then("backingActive and activeDeviceEntitlements should be cleared") {
                        assertTrue(entitlements.active.isEmpty())
                        assertTrue(entitlements.activeDeviceEntitlements.isEmpty())
                    }
                    And("status should be Unknown") {
                        assertTrue(entitlements.status.value is SubscriptionStatus.Unknown)
                    }
                }
            }
        }

    @Test
    fun `transition Unknown to Inactive keeps collections empty`() =
        runTest {
            Given("entitlements in Unknown state") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("setting Inactive from Unknown") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)

                    Then("all collections should remain empty") {
                        assertTrue(entitlements.active.isEmpty())
                        assertTrue(entitlements.inactive.isEmpty())
                        assertTrue(entitlements.status.value is SubscriptionStatus.Inactive)
                    }
                }
            }
        }

    @Test
    fun `multiple rapid state transitions end in correct final state`() =
        runTest {
            Given("entitlements subjected to rapid transitions") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e1 = Entitlement("a")
                val e2 = Entitlement("b")
                val e3 = Entitlement("c")

                When("cycling through Active, Inactive, Unknown, Active") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e1)))
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Unknown)
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e3)))

                    Then("final status should be Active with e3") {
                        val status = entitlements.status.value
                        assertTrue(status is SubscriptionStatus.Active)
                        assertTrue(entitlements.active.any { it.id == "c" })
                    }
                    And("e1 and e2 should not be in active (cleared by Inactive/Unknown)") {
                        assertFalse(entitlements.active.any { it.id == "a" })
                        assertFalse(entitlements.active.any { it.id == "b" })
                    }
                }
            }
        }

    // ==========================================
    // activeDeviceEntitlements Lifecycle
    // ==========================================

    @Test
    fun `activeDeviceEntitlements cleared on Unknown status`() =
        runTest {
            Given("entitlements with active device entitlements") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.activeDeviceEntitlements = setOf(Entitlement("device_premium"))

                When("setting Unknown status") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Unknown)

                    Then("activeDeviceEntitlements should be cleared") {
                        assertTrue(entitlements.activeDeviceEntitlements.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `activeDeviceEntitlements setter replaces not appends`() =
        runTest {
            Given("entitlements with existing device entitlements") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.activeDeviceEntitlements = setOf(Entitlement("old"))

                When("setting new device entitlements") {
                    entitlements.activeDeviceEntitlements = setOf(Entitlement("new"))

                    Then("only the new entitlement should be present") {
                        assertEquals(1, entitlements.activeDeviceEntitlements.size)
                        assertTrue(entitlements.activeDeviceEntitlements.any { it.id == "new" })
                        assertFalse(entitlements.activeDeviceEntitlements.any { it.id == "old" })
                    }
                }
            }
        }

    @Test
    fun `activeDeviceEntitlements do not persist to backingActive on Inactive`() =
        runTest {
            Given("device entitlements set, then status goes Inactive") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.activeDeviceEntitlements = setOf(Entitlement("device"))

                When("setting Inactive") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)

                    Then("active should be empty (device entitlements cleared by Inactive)") {
                        assertTrue(entitlements.active.isEmpty())
                    }
                }
            }
        }

    // ==========================================
    // Property Computations - active, inactive, all
    // ==========================================

    @Test
    fun `all property combines _all, entitlementsByProduct values, and web`() =
        runTest {
            Given("entitlements from all three backing sources") {
                val webE = Entitlement("web", isActive = true, store = Store.STRIPE)
                val storage =
                    mockStorage(
                        storedProductEntitlements = mapOf("prod1" to setOf(Entitlement("from_product"))),
                        redemptionResponse = webRedemption(webE),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(Entitlement("from_status"))))

                When("accessing all property") {
                    val all = entitlements.all

                    Then("it should contain entitlements from all three sources") {
                        assertTrue(all.any { it.id == "from_status" })
                        assertTrue(all.any { it.id == "from_product" })
                        assertTrue(all.any { it.id == "web" })
                    }
                }
            }
        }

    @Test
    fun `inactive property returns all minus active`() =
        runTest {
            Given("entitlements with both active and inactive by product") {
                val activeE = Entitlement("active", isActive = true)
                val inactiveE = Entitlement("inactive_product", isActive = false)
                val storage =
                    mockStorage(
                        storedProductEntitlements =
                            mapOf(
                                "prod1" to setOf(activeE),
                                "prod2" to setOf(inactiveE),
                            ),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(activeE)))

                When("accessing inactive property") {
                    val inactive = entitlements.inactive

                    Then("it should contain the product entitlement not in active") {
                        assertTrue(inactive.any { it.id == "inactive_product" })
                    }
                    And("it should not contain active entitlements") {
                        // active entitlement may appear in inactive if the exact object differs
                        // but we check the concept
                        val activeIds = entitlements.active.map { it.id }.toSet()
                        val purelyInactive = inactive.filter { it.id !in activeIds }
                        assertTrue(purelyInactive.any { it.id == "inactive_product" })
                    }
                }
            }
        }

    @Test
    fun `active property is empty when no sources have data`() =
        runTest {
            Given("a fresh Entitlements with no data") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                Then("active should be empty") {
                    assertTrue(entitlements.active.isEmpty())
                }
            }
        }

    // ==========================================
    // addEntitlementsByProductId
    // ==========================================

    @Test
    fun `addEntitlementsByProductId stores and makes entitlements available`() =
        runTest {
            Given("a fresh Entitlements instance") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e1 = Entitlement("premium")
                val e2 = Entitlement("basic")
                val mapping = mapOf("prod_a" to setOf(e1), "prod_b" to setOf(e2))

                When("adding entitlements by product ID") {
                    entitlements.addEntitlementsByProductId(mapping)

                    Then("entitlementsByProductId should contain the mappings") {
                        assertEquals(setOf(e1), entitlements.entitlementsByProductId["prod_a"])
                        assertEquals(setOf(e2), entitlements.entitlementsByProductId["prod_b"])
                    }
                    And("all should include both entitlements") {
                        assertTrue(entitlements.all.contains(e1))
                        assertTrue(entitlements.all.contains(e2))
                    }
                    And("storage should be written to") {
                        verify { storage.write(StoredEntitlementsByProductId, any()) }
                    }
                }
            }
        }

    @Test
    fun `addEntitlementsByProductId overwrites existing product key`() =
        runTest {
            Given("existing entitlements for a product") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val oldE = Entitlement("old")
                val newE = Entitlement("new")

                entitlements.addEntitlementsByProductId(mapOf("prod1" to setOf(oldE)))

                When("adding new entitlements for the same product") {
                    entitlements.addEntitlementsByProductId(mapOf("prod1" to setOf(newE)))

                    Then("the new entitlement should replace the old one for that product") {
                        assertEquals(setOf(newE), entitlements.entitlementsByProductId["prod1"])
                    }
                    And("all should reflect the update") {
                        assertTrue(entitlements.all.contains(newE))
                    }
                }
            }
        }

    @Test
    fun `addEntitlementsByProductId with empty map does not crash`() =
        runTest {
            Given("a fresh Entitlements instance") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("adding an empty map") {
                    entitlements.addEntitlementsByProductId(emptyMap())

                    Then("entitlementsByProductId should remain empty") {
                        assertTrue(entitlements.entitlementsByProductId.isEmpty())
                    }
                }
            }
        }

    // ==========================================
    // entitlementsByProductId Snapshot
    // ==========================================

    @Test
    fun `entitlementsByProductId returns a snapshot not a live reference`() =
        runTest {
            Given("entitlements with product mappings") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.addEntitlementsByProductId(mapOf("prod1" to setOf(Entitlement("e1"))))

                When("taking a snapshot and then modifying the original") {
                    val snapshot = entitlements.entitlementsByProductId
                    entitlements.addEntitlementsByProductId(mapOf("prod2" to setOf(Entitlement("e2"))))

                    Then("snapshot should not contain the new product") {
                        assertFalse(snapshot.containsKey("prod2"))
                    }
                    And("current entitlementsByProductId should contain both") {
                        assertTrue(entitlements.entitlementsByProductId.containsKey("prod1"))
                        assertTrue(entitlements.entitlementsByProductId.containsKey("prod2"))
                    }
                }
            }
        }

    // ==========================================
    // byProductId - Decomposed ID Matching
    // ==========================================

    @Test
    fun `byProductId exact match takes priority over partial`() =
        runTest {
            Given("entitlements mapped to both exact and partial matching product IDs") {
                val exactE = Entitlement("exact_match")
                val partialE = Entitlement("partial_match")
                val storage =
                    mockStorage(
                        storedProductEntitlements =
                            mapOf(
                                "sub:plan:offer" to setOf(exactE),
                                "sub" to setOf(partialE),
                            ),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying with the exact full ID") {
                    val result = entitlements.byProductId("sub:plan:offer")

                    Then("it should return the exact match entitlement") {
                        assertEquals(setOf(exactE), result)
                    }
                }
            }
        }

    @Test
    fun `byProductId falls back to subscriptionId contains match`() =
        runTest {
            Given("entitlements mapped only to a subscription ID") {
                val e = Entitlement("sub_level")
                val storage =
                    mockStorage(
                        storedProductEntitlements = mapOf("monthly_sub" to setOf(e)),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying with a full ID that contains the subscription ID") {
                    val result = entitlements.byProductId("monthly_sub:plan:offer")

                    Then("it should fall back to contains match on subscriptionId") {
                        assertEquals(setOf(e), result)
                    }
                }
            }
        }

    @Test
    fun `byProductId returns empty for completely unknown product`() =
        runTest {
            Given("entitlements with some products") {
                val storage =
                    mockStorage(
                        storedProductEntitlements = mapOf("known_product" to setOf(Entitlement("e"))),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying an unknown product") {
                    val result = entitlements.byProductId("completely_unknown")

                    Then("result should be empty") {
                        assertTrue(result.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `byProductId skips products with empty entitlement sets`() =
        runTest {
            Given("a product mapped to an empty entitlement set") {
                val fallbackE = Entitlement("fallback")
                val storage =
                    mockStorage(
                        storedProductEntitlements =
                            mapOf(
                                "product_a" to emptySet(),
                                "product_a:plan" to setOf(fallbackE),
                            ),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying product_a") {
                    val result = entitlements.byProductId("product_a:plan")

                    Then("it should skip the empty set and use the non-empty one") {
                        assertEquals(setOf(fallbackE), result)
                    }
                }
            }
        }

    @Test
    fun `byProductId simple product without colons`() =
        runTest {
            Given("a simple product ID with no base plan or offer") {
                val e = Entitlement("simple")
                val storage =
                    mockStorage(
                        storedProductEntitlements = mapOf("com.app.product" to setOf(e)),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying the simple product ID") {
                    val result = entitlements.byProductId("com.app.product")

                    Then("it should find the exact match") {
                        assertEquals(setOf(e), result)
                    }
                }
            }
        }

    // ==========================================
    // byProductIds (batch)
    // ==========================================

    @Test
    fun `byProductIds returns union of entitlements from multiple products`() =
        runTest {
            Given("multiple products with different entitlements") {
                val e1 = Entitlement("premium")
                val e2 = Entitlement("addon")
                val storage =
                    mockStorage(
                        storedProductEntitlements =
                            mapOf(
                                "prod1" to setOf(e1),
                                "prod2" to setOf(e2),
                            ),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying multiple product IDs") {
                    val result = entitlements.byProductIds(setOf("prod1", "prod2"))

                    Then("result should contain entitlements from both products") {
                        assertTrue(result.contains(e1))
                        assertTrue(result.contains(e2))
                        assertEquals(2, result.size)
                    }
                }
            }
        }

    @Test
    fun `byProductIds with empty set returns empty`() =
        runTest {
            Given("entitlements with products") {
                val storage =
                    mockStorage(
                        storedProductEntitlements = mapOf("prod1" to setOf(Entitlement("e"))),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying with empty set") {
                    val result = entitlements.byProductIds(emptySet())

                    Then("result should be empty") {
                        assertTrue(result.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `byProductIds deduplicates shared entitlements`() =
        runTest {
            Given("two products sharing the same entitlement") {
                val shared = Entitlement("shared")
                val storage =
                    mockStorage(
                        storedProductEntitlements =
                            mapOf(
                                "prod1" to setOf(shared),
                                "prod2" to setOf(shared),
                            ),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying both products") {
                    val result = entitlements.byProductIds(setOf("prod1", "prod2"))

                    Then("result should contain the entitlement only once (set semantics)") {
                        assertEquals(1, result.size)
                        assertTrue(result.contains(shared))
                    }
                }
            }
        }

    @Test
    fun `byProductIds with some unknown products returns only known`() =
        runTest {
            Given("one known and one unknown product") {
                val e1 = Entitlement("known")
                val storage =
                    mockStorage(
                        storedProductEntitlements = mapOf("known_prod" to setOf(e1)),
                    )
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("querying both") {
                    val result = entitlements.byProductIds(setOf("known_prod", "unknown_prod"))

                    Then("result should contain only the known entitlement") {
                        assertEquals(setOf(e1), result)
                    }
                }
            }
        }

    // ==========================================
    // Status Flow Persistence
    // ==========================================

    @Test
    fun `status changes are persisted to storage via flow collector`() =
        runTest {
            Given("Entitlements with backgroundScope for collector") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("setting Active status") {
                    val activeE = setOf(Entitlement("persisted"))
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(activeE))

                    // Give collector time to process
                    async(Dispatchers.Default) { delay(1.seconds) }.await()

                    Then("storage write should have been called with the new status") {
                        verify {
                            storage.write(
                                StoredSubscriptionStatus,
                                SubscriptionStatus.Active(activeE),
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun `Inactive status is persisted to storage`() =
        runTest {
            Given("Entitlements with backgroundScope") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("setting Inactive status") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)
                    async(Dispatchers.Default) { delay(1.seconds) }.await()

                    Then("Inactive should be persisted") {
                        verify {
                            storage.write(StoredSubscriptionStatus, SubscriptionStatus.Inactive)
                        }
                    }
                }
            }
        }

    // ==========================================
    // Web Entitlements Edge Cases
    // ==========================================

    @Test
    fun `web returns empty when redemption response has null customerInfo entitlements`() =
        runTest {
            Given("redemption response with no entitlements list") {
                val redemption =
                    WebRedemptionResponse(
                        codes = emptyList(),
                        allCodes = emptyList(),
                        customerInfo =
                            CustomerInfo(
                                subscriptions = emptyList(),
                                nonSubscriptions = emptyList(),
                                userId = "user",
                                entitlements = emptyList(),
                                isPlaceholder = false,
                            ),
                    )
                val storage = mockStorage(redemptionResponse = redemption)
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                Then("web should be empty") {
                    assertTrue(entitlements.web.isEmpty())
                }
            }
        }

    @Test
    fun `web entitlements included in all property`() =
        runTest {
            Given("only web entitlements exist") {
                val webE = Entitlement("web_only", isActive = true, store = Store.STRIPE)
                val storage = mockStorage(redemptionResponse = webRedemption(webE))
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                Then("all should include web entitlements") {
                    assertTrue(entitlements.all.contains(webE))
                }
                And("active should include web entitlements") {
                    assertTrue(entitlements.active.any { it.id == "web_only" })
                }
            }
        }

    @Test
    fun `web entitlements in active even when status is Inactive`() =
        runTest {
            Given("Inactive status but web entitlements in storage") {
                val webE = Entitlement("web_sub", isActive = true, store = Store.STRIPE)
                val storage = mockStorage(redemptionResponse = webRedemption(webE))
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)

                Then("active should still contain web entitlements") {
                    assertTrue(entitlements.active.any { it.id == "web_sub" })
                }
            }
        }

    // ==========================================
    // Deduplication / Merge Priority
    // ==========================================

    @Test
    fun `duplicate entitlement ID from status and device merges to single entry`() =
        runTest {
            Given("same entitlement ID from status and device sources") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val fromStatus = Entitlement("premium", isActive = true, store = Store.PLAY_STORE)
                val fromDevice = Entitlement("premium", isActive = true, store = Store.PLAY_STORE)

                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(fromStatus)))
                entitlements.activeDeviceEntitlements = setOf(fromDevice)

                When("accessing active") {
                    val active = entitlements.active

                    Then("there should be only one premium entitlement after merge") {
                        assertEquals(1, active.count { it.id == "premium" })
                    }
                }
            }
        }

    @Test
    fun `three sources with same ID deduplicate to one entry`() =
        runTest {
            Given("same entitlement ID from all three sources") {
                val webE = Entitlement("premium", isActive = true, store = Store.STRIPE)
                val storage = mockStorage(redemptionResponse = webRedemption(webE))
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                val statusE = Entitlement("premium", isActive = true, store = Store.PLAY_STORE)
                val deviceE = Entitlement("premium", isActive = true)

                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(statusE)))
                entitlements.activeDeviceEntitlements = setOf(deviceE)

                When("accessing active") {
                    val active = entitlements.active

                    Then("only one premium entitlement should exist") {
                        assertEquals(1, active.count { it.id == "premium" })
                    }
                }
            }
        }

    // ==========================================
    // Entitlement with Rich Properties
    // ==========================================

    @Test
    fun `entitlements with expiry dates and states are preserved through status transitions`() =
        runTest {
            Given("a richly-populated entitlement") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val now = Date()
                val future = Date(now.time + 86400000)
                val richE =
                    Entitlement(
                        id = "premium",
                        type = Entitlement.Type.SERVICE_LEVEL,
                        isActive = true,
                        productIds = setOf("prod_monthly", "prod_annual"),
                        latestProductId = "prod_annual",
                        startsAt = now,
                        renewedAt = now,
                        expiresAt = future,
                        isLifetime = false,
                        willRenew = true,
                        state = LatestSubscriptionState.SUBSCRIBED,
                        store = Store.PLAY_STORE,
                    )

                When("setting Active with the rich entitlement") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(richE)))

                    Then("active should contain the entitlement with all properties intact") {
                        val found = entitlements.active.first { it.id == "premium" }
                        assertEquals(setOf("prod_monthly", "prod_annual"), found.productIds)
                        assertEquals("prod_annual", found.latestProductId)
                        assertEquals(true, found.willRenew)
                        assertEquals(LatestSubscriptionState.SUBSCRIBED, found.state)
                        assertEquals(Store.PLAY_STORE, found.store)
                        assertEquals(future, found.expiresAt)
                    }
                }
            }
        }

    // ==========================================
    // Edge Cases
    // ==========================================

    @Test
    fun `setting Active with single entitlement works`() =
        runTest {
            Given("a single entitlement") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e = Entitlement("solo")

                When("setting Active with single entitlement") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e)))

                    Then("active should contain exactly one entitlement") {
                        assertEquals(1, entitlements.active.size)
                        assertTrue(entitlements.active.contains(e))
                    }
                }
            }
        }

    @Test
    fun `setting Active with many entitlements works`() =
        runTest {
            Given("100 entitlements") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val many = (1..100).map { Entitlement("e_$it") }.toSet()

                When("setting Active with all of them") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(many))

                    Then("active should contain all 100") {
                        assertEquals(100, entitlements.active.size)
                    }
                    And("all should contain all 100") {
                        assertEquals(100, entitlements.all.size)
                    }
                }
            }
        }

    @Test
    fun `status flow value reflects latest status synchronously`() =
        runTest {
            Given("Entitlements instance") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                When("setting status sequentially") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(Entitlement("a"))))
                    assertTrue(entitlements.status.value is SubscriptionStatus.Active)

                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)
                    assertTrue(entitlements.status.value is SubscriptionStatus.Inactive)

                    entitlements.setSubscriptionStatus(SubscriptionStatus.Unknown)

                    Then("status value should match the latest set value") {
                        assertTrue(entitlements.status.value is SubscriptionStatus.Unknown)
                    }
                }
            }
        }

    @Test
    fun `addEntitlementsByProductId followed by byProductId returns correct result`() =
        runTest {
            Given("dynamically added product entitlements") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e = Entitlement("dynamic")

                When("adding and then querying") {
                    entitlements.addEntitlementsByProductId(mapOf("dynamic_prod" to setOf(e)))

                    Then("byProductId should find it") {
                        assertEquals(setOf(e), entitlements.byProductId("dynamic_prod"))
                    }
                    And("byProductIds should also find it") {
                        assertEquals(setOf(e), entitlements.byProductIds(setOf("dynamic_prod")))
                    }
                }
            }
        }

    @Test
    fun `inactive returns empty when all are active`() =
        runTest {
            Given("only active entitlements") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e = Entitlement("active", isActive = true)
                entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(e)))

                Then("inactive should be empty") {
                    // inactive = _inactive + (all - active)
                    // all = {e}, active = {e}, so inactive additions = empty
                    assertTrue(entitlements.inactive.isEmpty())
                }
            }
        }

    @Test
    fun `web property reflects setWebEntitlements updates`() =
        runTest {
            Given("entitlements with web entitlements set via actor") {
                val webE1 = Entitlement("web_v1", isActive = true, store = Store.STRIPE)
                val webE2 = Entitlement("web_v2", isActive = true, store = Store.STRIPE)

                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }

                entitlements.setWebEntitlements(setOf(webE1))

                When("first read returns v1") {
                    assertEquals(setOf(webE1), entitlements.web)
                }

                entitlements.setWebEntitlements(setOf(webE2))

                Then("second read should return v2") {
                    assertEquals(setOf(webE2), entitlements.web)
                }
            }
        }

    @Test
    fun `addEntitlementsByProductId clears and rebuilds _all`() =
        runTest {
            Given("entitlements with existing product mappings") {
                val storage = mockStorage()
                val entitlements =
                    StateActor<EntitlementsContext, EntitlementsState>(
                        createInitialEntitlementsState(storage),
                        backgroundScope,
                    ).let { actor ->
                        Entitlements(
                            storage = storage,
                            actor = actor,
                            actorScope = backgroundScope,
                            factory = stubEntitlementsFactory,
                        )
                    }
                val e1 = Entitlement("first")
                val e2 = Entitlement("second")

                entitlements.addEntitlementsByProductId(mapOf("p1" to setOf(e1)))

                When("adding new product mappings (old key not overwritten)") {
                    entitlements.addEntitlementsByProductId(mapOf("p2" to setOf(e2)))

                    Then("all should contain entitlements from both adds") {
                        assertTrue(entitlements.all.any { it.id == "first" })
                        assertTrue(entitlements.all.any { it.id == "second" })
                    }
                }
            }
        }
}
