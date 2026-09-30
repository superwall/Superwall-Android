package com.superwall.sdk

import android.content.Context
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.StoredSubscriptionStatus
import com.superwall.sdk.store.makeEntitlements
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pins a pattern integrators rely on: overriding
 * [SuperwallDelegate.subscriptionStatusDidChange] and calling
 * [Superwall.setSubscriptionStatus] from inside it to add or remove
 * entitlements before the rest of the app sees the status.
 *
 * It is not a documented API, but apps depend on it, so these tests run the
 * real status listener from [Superwall] against real [com.superwall.sdk.store.Entitlements]
 * and a real [SuperwallDelegateAdapter]. Only the dependency container around
 * them is mocked.
 */
class SubscriptionStatusDelegateOverrideTest {
    private lateinit var superwall: Superwall
    private lateinit var storage: LocalStorage
    private lateinit var ioScope: IOScope
    private val calls = CopyOnWriteArrayList<Pair<Set<String>?, Set<String>?>>()

    private val pro = Entitlement("pro")
    private val legacy = Entitlement("legacy")
    private val custom = Entitlement("custom")

    @Before
    fun setUp() {
        hasInitialized().value = true
        ioScope = IOScope(Dispatchers.Unconfined)
        storage = mockk(relaxed = true)
        every { storage.read(any<Storable<Any>>()) } returns null

        val container = mockk<DependencyContainer>(relaxed = true)
        every { container.storage } returns storage
        every { container.ioScope() } returns ioScope
        every { container.delegateAdapter } returns SuperwallDelegateAdapter()
        every { container.entitlements } returns makeEntitlements(storage, ioScope)
        every { container.testMode.isTestMode } returns false

        superwall =
            Superwall(
                context = mockk<Context>(relaxed = true),
                apiKey = "test",
                purchaseController = null,
                options = null,
                activityProvider = null,
                completion = null,
            )
        Superwall::class.java.getDeclaredField("_dependencyContainer").apply {
            isAccessible = true
            set(superwall, container)
        }
        Superwall::class.java.getDeclaredMethod("addListeners").apply {
            isAccessible = true
            invoke(superwall)
        }
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        hasInitialized().value = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun hasInitialized(): MutableStateFlow<Boolean> =
        Superwall::class.java
            .getDeclaredField("_hasInitialized")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<Boolean>

    /** Installs a delegate that records each call and then runs [override]. */
    private fun overrideWith(override: (to: SubscriptionStatus) -> Unit) {
        superwall.delegate =
            object : SuperwallDelegate {
                override fun subscriptionStatusDidChange(
                    from: SubscriptionStatus,
                    to: SubscriptionStatus,
                ) {
                    calls += from.ids() to to.ids()
                    override(to)
                }
            }
    }

    /** Entitlement ids of an Active status, an empty set for Inactive, null for Unknown. */
    private fun SubscriptionStatus.ids(): Set<String>? =
        when (this) {
            is SubscriptionStatus.Active -> entitlements.map { it.id }.toSet()
            is SubscriptionStatus.Inactive -> emptySet()
            is SubscriptionStatus.Unknown -> null
        }

    private fun awaitStatus(expected: Set<String>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (superwall.subscriptionStatus.value.ids() == expected && calls.lastOrNull()?.second == expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, superwall.subscriptionStatus.value.ids())
        assertEquals("delegate was not told about the final status", expected, calls.lastOrNull()?.second)
    }

    @Test
    fun `delegate can add an entitlement to the status`() {
        Given("a delegate that adds a custom entitlement whenever it is missing") {
            overrideWith { to ->
                if (to is SubscriptionStatus.Active && custom !in to.entitlements) {
                    superwall.setSubscriptionStatus(SubscriptionStatus.Active(to.entitlements + custom))
                }
            }

            When("the status becomes Active without it") {
                superwall.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro)))
                awaitStatus(setOf("pro", "custom"))

                Then("the status and active entitlements include the added one") {
                    assertEquals(setOf("pro", "custom"), superwall.subscriptionStatus.value.ids())
                    assertEquals(setOf("pro", "custom"), superwall.entitlements.active.map { it.id }.toSet())
                }
                And("the delegate saw the original change, then its own override") {
                    assertEquals(
                        listOf(null to setOf("pro"), setOf("pro") to setOf("pro", "custom")),
                        calls.toList(),
                    )
                }
                And("the overridden status is what gets persisted last") {
                    verify {
                        storage.write(
                            StoredSubscriptionStatus,
                            match { it.ids() == setOf("pro", "custom") },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `delegate can add an entitlement using the string overload`() {
        Given("a delegate that re-sets the status by entitlement id") {
            overrideWith { to ->
                if (to is SubscriptionStatus.Active && custom !in to.entitlements) {
                    superwall.setSubscriptionStatus(*(to.entitlements.map { it.id } + "custom").toTypedArray())
                }
            }

            When("the status becomes Active without it") {
                superwall.setSubscriptionStatus("pro")
                awaitStatus(setOf("pro", "custom"))

                Then("the status includes the added entitlement") {
                    assertEquals(setOf("pro", "custom"), superwall.subscriptionStatus.value.ids())
                }
            }
        }
    }

    @Test
    fun `delegate can remove an entitlement from the status`() {
        Given("a delegate that strips the legacy entitlement") {
            overrideWith { to ->
                if (to is SubscriptionStatus.Active && legacy in to.entitlements) {
                    superwall.setSubscriptionStatus(SubscriptionStatus.Active(to.entitlements - legacy))
                }
            }

            When("the status becomes Active with it") {
                superwall.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro, legacy)))
                awaitStatus(setOf("pro"))

                Then("the status no longer carries the removed entitlement") {
                    assertEquals(setOf("pro"), superwall.subscriptionStatus.value.ids())
                }
                And("the delegate saw the original change, then its own override") {
                    assertEquals(
                        listOf(null to setOf("pro", "legacy"), setOf("pro", "legacy") to setOf("pro")),
                        calls.toList(),
                    )
                }
                And("the narrowed status is what gets persisted last") {
                    verify {
                        storage.write(StoredSubscriptionStatus, match { it.ids() == setOf("pro") })
                    }
                }
                // Active statuses only ever add to `entitlements.active`; it is cleared by
                // Inactive/Unknown. Same as before the actor refactor. Pinned so a change
                // here is a deliberate one.
                And("entitlements.active still holds it until the status goes Inactive") {
                    assertEquals(setOf("pro", "legacy"), superwall.entitlements.active.map { it.id }.toSet())
                }
            }
        }
    }

    @Test
    fun `delegate can remove every entitlement by setting Inactive`() {
        Given("a delegate that rejects any Active status") {
            overrideWith { to ->
                if (to is SubscriptionStatus.Active) {
                    superwall.setSubscriptionStatus(SubscriptionStatus.Inactive)
                }
            }

            When("the status becomes Active") {
                superwall.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro, legacy)))
                awaitStatus(emptySet())

                Then("the status is Inactive and nothing is active") {
                    assertTrue(superwall.subscriptionStatus.value is SubscriptionStatus.Inactive)
                    assertTrue(superwall.entitlements.active.isEmpty())
                }
                And("the delegate saw the original change, then its own override") {
                    assertEquals(
                        listOf(null to setOf("pro", "legacy"), setOf("pro", "legacy") to emptySet()),
                        calls.toList(),
                    )
                }
            }
        }
    }

    @Test
    fun `delegate can replace one entitlement with another`() {
        Given("a delegate that swaps legacy for custom") {
            overrideWith { to ->
                if (to is SubscriptionStatus.Active && legacy in to.entitlements) {
                    superwall.setSubscriptionStatus(SubscriptionStatus.Active(to.entitlements - legacy + custom))
                }
            }

            When("the status becomes Active with legacy") {
                superwall.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro, legacy)))
                awaitStatus(setOf("pro", "custom"))

                Then("the status carries the replacement and not the original") {
                    assertEquals(setOf("pro", "custom"), superwall.subscriptionStatus.value.ids())
                }
            }
        }
    }

    @Test
    fun `delegate can grant entitlements when the status goes Inactive`() {
        Given("a delegate that grants a custom entitlement to inactive users") {
            overrideWith { to ->
                if (to is SubscriptionStatus.Inactive) {
                    superwall.setSubscriptionStatus(SubscriptionStatus.Active(setOf(custom)))
                }
            }

            When("the status becomes Inactive") {
                superwall.setSubscriptionStatus(SubscriptionStatus.Inactive)
                awaitStatus(setOf("custom"))

                Then("the status is Active with the granted entitlement") {
                    assertEquals(setOf("custom"), superwall.subscriptionStatus.value.ids())
                    assertEquals(setOf("custom"), superwall.entitlements.active.map { it.id }.toSet())
                }
                And("the delegate saw Inactive, then its own grant") {
                    assertEquals(
                        listOf(null to emptySet(), emptySet<String>() to setOf("custom")),
                        calls.toList(),
                    )
                }
            }
        }
    }

    @Test
    fun `override is applied again on every later status change`() {
        Given("a delegate that adds a custom entitlement whenever it is missing") {
            overrideWith { to ->
                if (to is SubscriptionStatus.Active && custom !in to.entitlements) {
                    superwall.setSubscriptionStatus(SubscriptionStatus.Active(to.entitlements + custom))
                }
            }
            superwall.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro)))
            awaitStatus(setOf("pro", "custom"))

            When("the SDK later sets a status without the custom entitlement") {
                superwall.setSubscriptionStatus(SubscriptionStatus.Active(setOf(pro, legacy)))
                awaitStatus(setOf("pro", "legacy", "custom"))

                Then("the override has been re-applied") {
                    assertEquals(setOf("pro", "legacy", "custom"), superwall.subscriptionStatus.value.ids())
                }
            }
        }
    }
}
