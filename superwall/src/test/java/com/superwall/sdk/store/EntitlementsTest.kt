package com.superwall.sdk.store

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.EntitlementStatus
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredEntitlementStatus
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementsTest {
    private val storage: Storage = mockk(relaxUnitFun = true)

    private lateinit var entitlements: Entitlements

    @Test
    fun `test initialization with stored entitlement status`() =
        runTest {
            Given("storage contains entitlement status") {
                val storedEntitlements = setOf(Entitlement("test_entitlement"))
                val storedStatus = EntitlementStatus.Active(storedEntitlements)
                every { storage.read(StoredEntitlementStatus) } returns storedStatus
                every { storage.read(StoredEntitlementsByProductId) } returns
                    mapOf(
                        "test" to
                            setOf(
                                Entitlement("test_entitlement"),
                            ),
                    )
                entitlements = Entitlements(storage)

                When("Entitlements is initialized") {
                    val entitlements = Entitlements(storage)

                    Then("it should load the stored status") {
                        assertEquals(storedStatus, entitlements.status.value)
                        assertEquals(storedEntitlements, entitlements.active)
                        assertEquals(storedEntitlements, entitlements.all)
                        assertTrue(entitlements.inactive.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `test setEntitlementStatus with active entitlements`() =
        runTest {
            Given("an Entitlements instance") {
                val activeEntitlements =
                    setOf(
                        Entitlement("entitlement1"),
                        Entitlement("entitlement2"),
                    )
                every { storage.read(StoredEntitlementStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage)

                When("setting active entitlement status") {
                    entitlements.setEntitlementStatus(EntitlementStatus.Active(activeEntitlements))

                    Then("it should update all collections correctly") {
                        assertEquals(activeEntitlements, entitlements.active)
                        assertEquals(activeEntitlements, entitlements.all)
                        assertTrue(entitlements.inactive.isEmpty())
                        assertTrue(entitlements.status.value is EntitlementStatus.Active)
                    }

                    And("it should store the status") {
                        verify {
                            storage.write(
                                StoredEntitlementStatus,
                                EntitlementStatus.Active(activeEntitlements),
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun `test setEntitlementStatus with empty active entitlements`() =
        runTest {
            Given("an Entitlements instance") {
                every { storage.read(StoredEntitlementStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage)

                When("setting active entitlement status with empty set") {
                    entitlements.setEntitlementStatus(EntitlementStatus.Active(emptySet()))

                    Then("it should convert to NoActiveEntitlements status") {
                        assertTrue(entitlements.status.value is EntitlementStatus.Inactive)
                        assertTrue(entitlements.active.isEmpty())
                        assertTrue(entitlements.inactive.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `test setEntitlementStatus with no active entitlements`() =
        runTest {
            Given("an Entitlements instance") {
                every { storage.read(StoredEntitlementStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage)
                When("setting NoActiveEntitlements status") {
                    entitlements.setEntitlementStatus(EntitlementStatus.Inactive)

                    Then("it should clear all collections") {
                        assertTrue(entitlements.active.isEmpty())
                        assertTrue(entitlements.inactive.isEmpty())
                        assertTrue(entitlements.status.value is EntitlementStatus.Inactive)
                    }
                }
            }
        }

    @Test
    fun `test setEntitlementStatus with unknown status`() =
        runTest {
            Given("an Entitlements instance") {
                every { storage.read(StoredEntitlementStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage)
                When("setting Unknown status") {
                    entitlements.setEntitlementStatus(EntitlementStatus.Unknown)

                    Then("it should clear all collections") {
                        assertTrue(entitlements.active.isEmpty())
                        assertTrue(entitlements.inactive.isEmpty())
                        assertTrue(entitlements.all.isEmpty())
                        assertTrue(entitlements.status.value is EntitlementStatus.Unknown)
                    }
                }
            }
        }

    @Test
    fun `test byProductId functionality`() =
        runTest {
            Given("storage contains entitlements by product ID") {
                val productEntitlements =
                    mapOf(
                        "product1" to setOf(Entitlement("entitlement1")),
                        "product2" to setOf(Entitlement("entitlement2")),
                    )
                every { storage.read(StoredEntitlementStatus) } returns
                    EntitlementStatus.Active(
                        setOf(
                            Entitlement("entitlement1"),
                            Entitlement("entitlement2"),
                        ),
                    )
                every { storage.read(StoredEntitlementsByProductId) } returns productEntitlements
                entitlements = Entitlements(storage)

                When("creating a new Entitlements instance") {
                    Then("it should return correct entitlements for each product") {
                        assertEquals(
                            productEntitlements["product1"],
                            entitlements.byProductId("product1"),
                        )
                        assertEquals(
                            productEntitlements["product2"],
                            entitlements.byProductId("product2"),
                        )
                    }

                    And("it should return empty set for unknown product") {
                        assertTrue(entitlements.byProductId("unknown_product").isEmpty())
                    }
                }
            }
        }
}
