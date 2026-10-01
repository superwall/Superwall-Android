@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.delegate.PurchaseResult
import com.superwall.sdk.misc.IOScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticPurchaseControllerTest {
    private fun TestScope.makeController(getBilling: () -> BillingClient): AutomaticPurchaseController =
        AutomaticPurchaseController(
            context = mockk(relaxed = true),
            scope = IOScope(UnconfinedTestDispatcher(testScheduler)),
            entitlementsInfo = { mockk(relaxed = true) },
            getBilling = { _, _ -> getBilling() },
        )

    private fun clientFinishingSetupWith(code: Int): BillingClient =
        mockk(relaxed = true) {
            every { isReady } returns false
            every { startConnection(any()) } answers {
                firstArg<BillingClientStateListener>().onBillingSetupFinished(
                    BillingResult
                        .newBuilder()
                        .setResponseCode(code)
                        .setDebugMessage("")
                        .build(),
                )
            }
        }

    @Test
    fun `purchase fails instead of throwing or hanging when the billing client can't be created`() =
        runTest {
            Given("a device where creating the billing client throws") {
                val controller = makeController { throw IllegalStateException("No Play Store") }
                advanceUntilIdle()

                When("a purchase is attempted") {
                    @Suppress("DEPRECATION")
                    val result = controller.purchase(mockk<Activity>(relaxed = true), mockk<ProductDetails>(relaxed = true), null, null)

                    Then("it fails") {
                        assertTrue(result is PurchaseResult.Failed)
                    }
                }
            }
        }

    @Test
    fun `purchase fails after one more connection attempt when billing is unavailable`() =
        runTest {
            Given("a device where billing setup reports BILLING_UNAVAILABLE") {
                val client = clientFinishingSetupWith(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
                val controller = makeController { client }
                advanceUntilIdle()

                When("a purchase is attempted") {
                    @Suppress("DEPRECATION")
                    val result = controller.purchase(mockk<Activity>(relaxed = true), mockk<ProductDetails>(relaxed = true), null, null)

                    Then("it fails") {
                        assertTrue(result is PurchaseResult.Failed)
                    }

                    And("the connection was retried once for the purchase") {
                        verify(exactly = 2) { client.startConnection(any()) }
                        assertEquals(0L, testScheduler.currentTime)
                    }
                }
            }
        }
}
