package com.superwall.sdk.store

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import com.superwall.sdk.storage.StoredSubscriptionStatus
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class EntitlementsTest {
    private val storage: Storage =
        mockk(relaxUnitFun = true) {
            every { read(LatestRedemptionResponse) } returns null
        }

    private lateinit var entitlements: Entitlements

    @Test
    fun `test initialization with stored entitlement status`() =
        runTest {
            Given("storage contains entitlement status") {
                val storedEntitlements = setOf(Entitlement("test_entitlement"))
                val storedStatus = SubscriptionStatus.Active(storedEntitlements)
                every { storage.read(StoredSubscriptionStatus) } returns storedStatus
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
                every {
                    storage.write(
                        StoredSubscriptionStatus,
                        SubscriptionStatus.Active(activeEntitlements),
                    )
                } just Runs
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage, scope = backgroundScope)

                When("setting active entitlement status") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(activeEntitlements))
                    Then("it should update all collections correctly") {
                        assertEquals(activeEntitlements, entitlements.active)
                        assertEquals(activeEntitlements, entitlements.all)
                        assertTrue(entitlements.inactive.isEmpty())
                        assertTrue(entitlements.status.value is SubscriptionStatus.Active)
                    }
                    async(Dispatchers.Default) {
                        delay(1.seconds)
                    }.await()
                    And("it should store the status") {
                        verify {
                            storage.write(
                                StoredSubscriptionStatus,
                                SubscriptionStatus.Active(activeEntitlements),
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
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage)

                When("setting active entitlement status with empty set") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(emptySet()))

                    Then("it should convert to NoActiveEntitlements status") {
                        assertTrue(entitlements.status.value is SubscriptionStatus.Inactive)
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
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage)
                When("setting NoActiveEntitlements status") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)

                    Then("it should clear all collections") {
                        assertTrue(entitlements.active.isEmpty())
                        assertTrue(entitlements.inactive.isEmpty())
                        assertTrue(entitlements.status.value is SubscriptionStatus.Inactive)
                    }
                }
            }
        }

    @Test
    fun `test setEntitlementStatus with unknown status`() =
        runTest {
            Given("an Entitlements instance") {
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns null
                entitlements = Entitlements(storage)
                When("setting Unknown status") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Unknown)

                    Then("it should clear all collections") {
                        assertTrue(entitlements.active.isEmpty())
                        assertTrue(entitlements.inactive.isEmpty())
                        assertTrue(entitlements.all.isEmpty())
                        assertTrue(entitlements.status.value is SubscriptionStatus.Unknown)
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
                every { storage.read(StoredSubscriptionStatus) } returns
                    SubscriptionStatus.Active(
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

    @Test
    fun `test byProductId matches partial product IDs with contains logic`() =
        runTest {
            Given("storage contains entitlement with key subscription_monthly colon p1m-high") {
                val premiumEntitlement = Entitlement("premium_access")
                val wrongEntitlement = Entitlement("fake")
                val productEntitlements =
                    mapOf(
                        "subscription_monthly:p1m-high" to setOf(wrongEntitlement),
                        "subscription_monthly:p1m:freetrial" to setOf(premiumEntitlement),
                    )
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns productEntitlements
                entitlements = Entitlements(storage)

                When("querying with subscription_monthly colon p1m colon freetrial") {
                    val result = entitlements.byProductId("subscription_monthly:p1m:freetrial")

                    Then(
                        "it should match the entitlement because subscription_monthly colon p1m is contained in subscription_monthly colon p1m-high",
                    ) {
                        assertEquals(setOf(premiumEntitlement), result)
                    }
                }
            }
        }

    @Test
    fun `test activeDeviceEntitlements returns only active device entitlements not all`() =
        runTest {
            Given("entitlements with both active and inactive device entitlements") {
                val activeEntitlement = Entitlement("premium", isActive = true)
                val inactiveEntitlement = Entitlement("basic", isActive = false)
                val productEntitlements =
                    mapOf(
                        "product_premium" to setOf(activeEntitlement),
                        "product_basic" to setOf(inactiveEntitlement),
                    )
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns productEntitlements
                entitlements = Entitlements(storage)

                When("setting active device entitlements to only the active one") {
                    entitlements.activeDeviceEntitlements = setOf(activeEntitlement)

                    Then("activeDeviceEntitlements should only contain the active entitlement") {
                        assertEquals(setOf(activeEntitlement), entitlements.activeDeviceEntitlements)
                    }

                    And("all should contain both entitlements") {
                        assertTrue(entitlements.all.contains(activeEntitlement))
                        assertTrue(entitlements.all.contains(inactiveEntitlement))
                    }

                    And("activeDeviceEntitlements should NOT equal all") {
                        assertTrue(
                            "activeDeviceEntitlements should not return all entitlements - " +
                                "this would cause inactive entitlements to be treated as active",
                            entitlements.activeDeviceEntitlements != entitlements.all,
                        )
                    }
                }
            }
        }

    @Test
    fun `test activeDeviceEntitlements is empty when no active device purchases`() =
        runTest {
            Given("entitlements from config but no active device purchases") {
                val configEntitlement1 = Entitlement("default", isActive = false)
                val configEntitlement2 = Entitlement("test", isActive = false)
                val productEntitlements =
                    mapOf(
                        "product_monthly" to setOf(configEntitlement1),
                        "product_annual" to setOf(configEntitlement1, configEntitlement2),
                    )
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns productEntitlements
                entitlements = Entitlements(storage)

                When("no active device entitlements are set") {
                    // activeDeviceEntitlements not set, should be empty

                    Then("activeDeviceEntitlements should be empty") {
                        assertTrue(
                            "activeDeviceEntitlements should be empty when no device purchases are active",
                            entitlements.activeDeviceEntitlements.isEmpty(),
                        )
                    }

                    And("all should still contain config entitlements") {
                        assertTrue(entitlements.all.isNotEmpty())
                    }
                }
            }
        }

    @Test
    fun `test activeDeviceEntitlements cleared on subscription status change to inactive`() =
        runTest {
            Given("entitlements with active device entitlements") {
                val activeEntitlement = Entitlement("premium", isActive = true)
                val inactiveEntitlement = Entitlement("basic", isActive = false)
                val productEntitlements =
                    mapOf(
                        "product_premium" to setOf(activeEntitlement),
                        "product_basic" to setOf(inactiveEntitlement),
                    )
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns productEntitlements
                entitlements = Entitlements(storage, scope = backgroundScope)
                entitlements.activeDeviceEntitlements = setOf(activeEntitlement)

                When("subscription status is set to Inactive") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive)

                    Then("activeDeviceEntitlements should be cleared") {
                        assertTrue(
                            "activeDeviceEntitlements should be cleared when status becomes Inactive",
                            entitlements.activeDeviceEntitlements.isEmpty(),
                        )
                    }

                    And("all should still contain config entitlements") {
                        assertTrue(entitlements.all.contains(activeEntitlement))
                        assertTrue(entitlements.all.contains(inactiveEntitlement))
                    }
                }
            }
        }

    @Test
    fun `test active getter combines backingActive and activeDeviceEntitlements correctly`() =
        runTest {
            Given("entitlements with both status-based and device-based active entitlements") {
                val statusActiveEntitlement = Entitlement("status_active", isActive = true)
                val deviceActiveEntitlement = Entitlement("device_active", isActive = true)
                val inactiveEntitlement = Entitlement("inactive", isActive = false)
                val productEntitlements =
                    mapOf(
                        "product_status" to setOf(statusActiveEntitlement),
                        "product_device" to setOf(deviceActiveEntitlement),
                        "product_inactive" to setOf(inactiveEntitlement),
                    )
                every { storage.read(StoredSubscriptionStatus) } returns null
                every { storage.read(StoredEntitlementsByProductId) } returns productEntitlements
                entitlements = Entitlements(storage, scope = backgroundScope)

                When("setting both status and device entitlements") {
                    entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(statusActiveEntitlement)))
                    entitlements.activeDeviceEntitlements = setOf(deviceActiveEntitlement)

                    Then("active should contain both status and device entitlements") {
                        assertTrue(entitlements.active.contains(statusActiveEntitlement))
                        assertTrue(entitlements.active.contains(deviceActiveEntitlement))
                    }

                    And("active should NOT contain inactive entitlements") {
                        assertTrue(
                            "active should not contain inactive entitlements from config",
                            !entitlements.active.contains(inactiveEntitlement),
                        )
                    }

                    And("activeDeviceEntitlements should only contain device entitlements") {
                        assertEquals(setOf(deviceActiveEntitlement), entitlements.activeDeviceEntitlements)
                    }
                }
            }
        }
}
