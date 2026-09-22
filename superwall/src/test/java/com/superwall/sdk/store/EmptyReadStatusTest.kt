package com.superwall.sdk.store

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.product.Store
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/**
 * Covers the guard that stops an empty Play Billing read from demoting a
 * subscriber whose entitlement hasn't expired.
 */
class EmptyReadStatusTest {
    private val now = Date()
    private val tomorrow = Date(now.time + 86_400_000)
    private val yesterday = Date(now.time - 86_400_000)

    private fun entitlement(
        id: String,
        store: Store? = Store.PLAY_STORE,
        expiresAt: Date? = tomorrow,
        productIds: Set<String> = setOf("$id.product"),
        isActive: Boolean = true,
    ) = Entitlement(
        id = id,
        isActive = isActive,
        productIds = productIds,
        expiresAt = expiresAt,
        store = store,
    )

    private fun activeIds(status: SubscriptionStatus): Set<String> =
        (status as SubscriptionStatus.Active).entitlements.map { it.id }.toSet()

    @Test
    fun `a failed read keeps an unexpired subscriber active`() {
        Given("a Play subscriber whose subscription runs until tomorrow") {
            val current = SubscriptionStatus.Active(setOf(entitlement("pro")))

            When("both purchase queries fail") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("they stay active") {
                    assertEquals(setOf("pro"), activeIds(result))
                }
            }
        }
    }

    @Test
    fun `a read that worked and found nothing still demotes`() {
        Given("a Play subscriber whose subscription runs until tomorrow") {
            val current = SubscriptionStatus.Active(setOf(entitlement("pro")))

            When("the queries succeed and report no purchases at all") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = false,
                        now = now,
                    )

                Then("the answer is taken at face value and they go inactive") {
                    assertEquals(SubscriptionStatus.Inactive, result)
                }
            }
        }
    }

    @Test
    fun `an expiry date the status lacks is read from the device records`() {
        Given("a status holding a config-shaped entitlement with no expiry date") {
            val current =
                SubscriptionStatus.Active(
                    setOf(entitlement("pro", store = null, expiresAt = null)),
                )
            val records = setOf(entitlement("pro", expiresAt = tomorrow))

            When("the read fails") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = records,
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("the device record's date holds the status up") {
                    assertEquals(setOf("pro"), activeIds(result))
                }
            }
        }
    }

    @Test
    fun `an entitlement past its own expiry is dropped on a failed read`() {
        Given("one subscription that ran out yesterday and one that runs until tomorrow") {
            val current =
                SubscriptionStatus.Active(
                    setOf(
                        entitlement("lapsed", expiresAt = yesterday),
                        entitlement("live", expiresAt = tomorrow),
                    ),
                )

            When("the read fails") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("only the live one survives - time passing needs no read to confirm it") {
                    assertEquals(setOf("live"), activeIds(result))
                }
            }
        }
    }

    @Test
    fun `everything lapsed goes inactive even on a failed read`() {
        Given("a subscription that ran out yesterday") {
            val current =
                SubscriptionStatus.Active(setOf(entitlement("pro", expiresAt = yesterday)))

            When("the read fails") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("nothing holds the status up") {
                    assertEquals(SubscriptionStatus.Inactive, result)
                }
            }
        }
    }

    @Test
    fun `a mapping failure keeps the entitlement its purchase unlocks`() {
        Given("an active purchase that config no longer maps to an entitlement") {
            val current =
                SubscriptionStatus.Active(
                    setOf(entitlement("pro", productIds = setOf("pro.monthly"))),
                )

            When("the read returns that purchase but produces no entitlements") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = setOf("pro.monthly"),
                        readReturnedPurchases = true,
                        readFailed = false,
                        now = now,
                    )

                Then("the paying subscriber is not locked out over a lost mapping") {
                    assertEquals(setOf("pro"), activeIds(result))
                }
            }
        }
    }

    @Test
    fun `a refuted play entitlement is dropped while a live web one holds`() {
        Given("a cancelled Play entitlement next to a live web one") {
            val current =
                SubscriptionStatus.Active(
                    setOf(
                        entitlement("play", productIds = setOf("play.monthly")),
                        entitlement("web", store = Store.STRIPE),
                    ),
                )

            When("the read returns an unrelated purchase and no entitlements") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = setOf("something.else"),
                        readReturnedPurchases = true,
                        readFailed = false,
                        now = now,
                    )

                Then("only the web entitlement survives") {
                    assertEquals(setOf("web"), activeIds(result))
                }
            }
        }
    }

    @Test
    fun `a read of play purchases cannot refute a web entitlement`() {
        Given("a web subscriber with no Play purchases") {
            val current =
                SubscriptionStatus.Active(setOf(entitlement("web", store = Store.STRIPE)))

            When("the read returns an unrelated Play purchase and no entitlements") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = setOf("something.else"),
                        readReturnedPurchases = true,
                        readFailed = false,
                        now = now,
                    )

                Then("the web entitlement holds the status on its own") {
                    assertEquals(setOf("web"), activeIds(result))
                }
            }
        }
    }

    @Test
    fun `an entitlement with no store is left alone`() {
        Given("an entitlement we can't place next to a live Play one") {
            val current =
                SubscriptionStatus.Active(
                    setOf(
                        entitlement("granted", store = null),
                        entitlement("play", productIds = setOf("play.monthly")),
                    ),
                )

            When("the read returns only the Play purchase and no entitlements") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = setOf("play.monthly"),
                        readReturnedPurchases = true,
                        readFailed = false,
                        now = now,
                    )

                Then("both survive") {
                    assertEquals(setOf("granted", "play"), activeIds(result))
                }
            }
        }
    }

    @Test
    fun `a lifetime unlock survives but cannot hold the status on its own`() {
        Given("a lifetime unlock with no expiry date") {
            val lifetime = entitlement("lifetime", expiresAt = null)

            When("the read fails and nothing else is active") {
                val alone =
                    resolveStatusForEmptyRead(
                        currentStatus = SubscriptionStatus.Active(setOf(lifetime)),
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("it cannot hold the status up, so a revoked lifetime still demotes") {
                    assertEquals(SubscriptionStatus.Inactive, alone)
                }
            }

            When("the read fails and a dated subscription is also active") {
                val together =
                    resolveStatusForEmptyRead(
                        currentStatus =
                            SubscriptionStatus.Active(setOf(lifetime, entitlement("pro"))),
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("it stays, because the read said nothing about it") {
                    assertEquals(setOf("lifetime", "pro"), activeIds(together))
                }
            }
        }
    }

    @Test
    fun `an inactive entitlement cannot hold the status up`() {
        Given("a status carrying an entitlement flagged inactive") {
            val current =
                SubscriptionStatus.Active(setOf(entitlement("pro", isActive = false)))

            When("the read fails") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("nothing holds the status up") {
                    assertEquals(SubscriptionStatus.Inactive, result)
                }
            }
        }
    }

    @Test
    fun `there is nothing to hold when the status is not active`() {
        Given("a user who was never active") {
            When("the read fails") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = SubscriptionStatus.Unknown,
                        deviceRecords = emptySet(),
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("they stay inactive") {
                    assertEquals(SubscriptionStatus.Inactive, result)
                }
            }
        }
    }

    @Test
    fun `a web entitlement keeps its own expiry over a device record sharing its id`() {
        Given("a live web entitlement whose id also has a lapsed device record") {
            val current =
                SubscriptionStatus.Active(
                    setOf(entitlement("pro", store = Store.STRIPE, expiresAt = tomorrow)),
                )
            val records = setOf(entitlement("pro", expiresAt = yesterday))

            When("the read fails") {
                val result =
                    resolveStatusForEmptyRead(
                        currentStatus = current,
                        deviceRecords = records,
                        activeProductIds = emptySet(),
                        readReturnedPurchases = false,
                        readFailed = true,
                        now = now,
                    )

                Then("the web dates win and it survives") {
                    assertEquals(setOf("pro"), activeIds(result))
                }
            }
        }
    }
}
