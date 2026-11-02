package com.superwall.sdk.store

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.storage.LatestCustomerInfo
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StoredSubscriptionStatus
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionStatusObserverTest {
    private val scope = IOScope(Dispatchers.Unconfined)
    private val status = MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.Unknown)
    private val customerInfo = MutableStateFlow(CustomerInfo.empty())
    private val storage = mockk<Storage>(relaxed = true)
    private val delegate = mockk<SuperwallDelegateAdapter>(relaxed = true)
    private val tracked = mutableListOf<Trackable>()
    private var rechecks = 0

    private val pro = Entitlement("pro", isActive = true)

    private fun start() =
        SubscriptionStatusObserver(
            scope = scope,
            subscriptionStatus = status,
            customerInfo = customerInfo,
            storage = storage,
            delegate = { delegate },
            track = { tracked += it },
            recheckPreload = { rechecks++ },
        ).start()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the cached status is not reported`() =
        Given("a started observer") {
            start()
            Then("nothing is reported for the starting values") {
                verify(exactly = 0) { delegate.subscriptionStatusDidChange(any(), any()) }
                verify(exactly = 0) { delegate.customerInfoDidChange(any(), any()) }
                assertTrue(tracked.isEmpty())
                assertEquals(0, rechecks)
            }
        }

    @Test
    fun `a status change is saved, told to the delegate, tracked and rechecks preload`() =
        Given("a started observer") {
            start()
            When("the status becomes Active") {
                val active = SubscriptionStatus.Active(setOf(pro))
                status.value = active
                Then("it is saved and the delegate hears Unknown to Active") {
                    verify { storage.write(StoredSubscriptionStatus, active) }
                    verify { delegate.subscriptionStatusDidChange(SubscriptionStatus.Unknown, active) }
                }
                And("it is tracked once and the preload is rechecked") {
                    val event = tracked.single() as InternalSuperwallEvent.SubscriptionStatusDidChange
                    assertEquals(active, event.subscriptionStatus)
                    assertEquals(1, rechecks)
                }
            }
        }

    @Test
    fun `enriching product ids does not report a change`() =
        Given("an observer that already saw an Active status") {
            start()
            status.value = SubscriptionStatus.Active(setOf(pro))
            When("the same entitlement gains product ids") {
                status.value = SubscriptionStatus.Active(setOf(pro.copy(productIds = setOf("p1"), latestProductId = "p1")))
                Then("the delegate was told only once") {
                    verify(exactly = 1) { delegate.subscriptionStatusDidChange(any(), any()) }
                }
            }
        }

    @Test
    fun `a customer info change is saved, told and tracked`() =
        Given("a started observer") {
            start()
            When("customer info changes") {
                val old = customerInfo.value
                val new = CustomerInfo.empty().copy(userId = "user", isPlaceholder = false)
                customerInfo.value = new
                Then("it is saved, the delegate hears it and it is tracked") {
                    verify { storage.write(LatestCustomerInfo, new) }
                    verify { delegate.customerInfoDidChange(old, new) }
                    assertEquals(listOf<Trackable>(InternalSuperwallEvent.CustomerInfoDidChange(old, new)), tracked)
                    assertEquals(1, rechecks)
                }
            }
        }

    @Test
    fun `user-facing status comparison`() =
        Given("pairs of statuses") {
            val same = SubscriptionStatusObserver.Companion::isSameUserFacingStatus
            Then("same kinds and same entitlements compare equal") {
                assertTrue(same(SubscriptionStatus.Inactive, SubscriptionStatus.Inactive))
                assertTrue(same(SubscriptionStatus.Active(setOf(pro)), SubscriptionStatus.Active(setOf(pro.copy(productIds = setOf("x"))))))
            }
            And("different kinds, ids, counts or user-facing fields do not") {
                assertFalse(same(SubscriptionStatus.Unknown, SubscriptionStatus.Inactive))
                assertFalse(same(SubscriptionStatus.Active(setOf(pro)), SubscriptionStatus.Active(setOf(Entitlement("other", isActive = true)))))
                assertFalse(same(SubscriptionStatus.Active(setOf(pro)), SubscriptionStatus.Active(setOf(pro, Entitlement("other")))))
                assertFalse(same(SubscriptionStatus.Active(setOf(pro)), SubscriptionStatus.Active(setOf(pro.copy(isActive = false)))))
            }
        }
}
