package com.superwall.sdk.store

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class SubscriptionStatusManagerTest {
    private val pro = Entitlement("pro")
    private val web = Entitlement("web")

    private fun manager(
        entitlements: Entitlements,
        testMode: Boolean = false,
        external: Boolean = false,
    ) = SubscriptionStatusManager(
        entitlements = { entitlements },
        isTestMode = { testMode },
        hasExternalPurchaseController = { external },
    )

    private fun entitlements(webEntitlements: Set<Entitlement> = emptySet()): Entitlements =
        mockk(relaxed = true) {
            every { web } returns webEntitlements
        }

    @Test
    fun `app status is set on entitlements`() =
        Given("a manager outside test mode") {
            val entitlements = entitlements()
            When("the app sets Active") {
                manager(entitlements).setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro)))
                Then("entitlements get that status") {
                    verify { entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro))) }
                }
            }
        }

    @Test
    fun `app status is ignored in test mode`() =
        Given("a manager in test mode") {
            val entitlements = entitlements()
            When("the app sets a status, by value and by id") {
                val manager = manager(entitlements, testMode = true)
                manager.setSubscriptionStatus(SubscriptionStatus.Inactive)
                manager.setSubscriptionStatus(arrayOf("pro"))
                Then("entitlements are untouched") {
                    verify(exactly = 0) { entitlements.setSubscriptionStatus(any()) }
                }
            }
        }

    @Test
    fun `entitlement ids become Active, and no ids Inactive`() =
        Given("a manager outside test mode") {
            val entitlements = entitlements()
            When("the app sets ids, then none") {
                val manager = manager(entitlements)
                manager.setSubscriptionStatus(arrayOf("pro", "web"))
                manager.setSubscriptionStatus(emptyArray())
                Then("the ids become an Active status, then Inactive") {
                    verify { entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro, web))) }
                    verify { entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive) }
                }
            }
        }

    @Test
    fun `internal status is ignored with an external purchase controller or in test mode`() =
        Given("one manager with an external controller and one in test mode") {
            val withController = entitlements()
            val inTestMode = entitlements()
            When("the SDK sets an internal status on both") {
                manager(withController, external = true).internallySetSubscriptionStatus(SubscriptionStatus.Inactive)
                manager(inTestMode, testMode = true).internallySetSubscriptionStatus(SubscriptionStatus.Inactive)
                Then("neither sets entitlements") {
                    verify(exactly = 0) { withController.setSubscriptionStatus(any()) }
                    verify(exactly = 0) { inTestMode.setSubscriptionStatus(any()) }
                }
            }
        }

    @Test
    fun `internal Active merges web entitlements`() =
        Given("a web entitlement") {
            val entitlements = entitlements(setOf(web))
            When("the SDK sets Active with a device entitlement") {
                manager(entitlements).internallySetSubscriptionStatus(SubscriptionStatus.Active(setOf(pro)))
                Then("the status holds both") {
                    verify { entitlements.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro, web))) }
                }
            }
        }

    @Test
    fun `internal Active with no entitlements at all becomes Inactive`() =
        Given("no web entitlements") {
            val entitlements = entitlements()
            When("the SDK sets an empty Active") {
                manager(entitlements).internallySetSubscriptionStatus(SubscriptionStatus.Active(emptySet()))
                Then("the status is Inactive") {
                    verify { entitlements.setSubscriptionStatus(SubscriptionStatus.Inactive) }
                }
            }
        }

    @Test
    fun `internal Inactive keeps web entitlements active`() =
        Given("a web entitlement, and a manager without any") {
            val withWeb = entitlements(setOf(web))
            val withoutWeb = entitlements()
            When("the SDK sets Inactive on both") {
                manager(withWeb).internallySetSubscriptionStatus(SubscriptionStatus.Inactive)
                manager(withoutWeb).internallySetSubscriptionStatus(SubscriptionStatus.Inactive)
                Then("the web one stays Active, the other goes Inactive") {
                    verify { withWeb.setSubscriptionStatus(SubscriptionStatus.Active(setOf(web))) }
                    verify { withoutWeb.setSubscriptionStatus(SubscriptionStatus.Inactive) }
                }
            }
        }

    @Test
    fun `internal Unknown passes through`() =
        Given("a web entitlement") {
            val entitlements = entitlements(setOf(web))
            When("the SDK sets Unknown") {
                manager(entitlements).internallySetSubscriptionStatus(SubscriptionStatus.Unknown)
                Then("the status is Unknown") {
                    verify { entitlements.setSubscriptionStatus(SubscriptionStatus.Unknown) }
                }
            }
        }
}
