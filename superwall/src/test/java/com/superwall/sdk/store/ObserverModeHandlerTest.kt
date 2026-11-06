package com.superwall.sdk.store

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.billing.StorePurchaseUpdate
import com.superwall.sdk.store.transactions.ObservedReport
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.store.transactions.TransactionManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverModeHandlerTest {
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private var observing = true
    private val handler =
        ObserverModeHandler(
            scope = IOScope(Dispatchers.Unconfined),
            shouldObservePurchases = { observing },
            transactionManager = { transactionManager },
        )
    private val product = mockk<ProductDetails>(relaxed = true) { every { productId } returns "pro" }

    @Test
    fun `nothing is tracked while observing purchases is off`() =
        Given("shouldObservePurchases is false") {
            observing = false
            When("a purchase begins and fails") {
                handler.observe(PurchasingObserverState.PurchaseWillBegin(product))
                handler.observe(PurchasingObserverState.PurchaseError(product, IllegalStateException()))
                Then("the transaction manager is never called") {
                    coVerify(exactly = 0) { transactionManager.prepareToPurchase(any(), any()) }
                    coVerify(exactly = 0) { transactionManager.handle(any(), any()) }
                }
            }
        }

    @Test
    fun `a purchase that begins is prepared in observer mode`() =
        Given("observing is on") {
            When("a purchase begins") {
                handler.observe(PurchasingObserverState.PurchaseWillBegin(product))
                Then("the product is prepared with an observer-mode source") {
                    val source = slot<TransactionManager.PurchaseSource>()
                    coVerify(exactly = 1) { transactionManager.prepareToPurchase(any(), capture(source)) }
                    val observer = source.captured as TransactionManager.PurchaseSource.ObserverMode
                    assertEquals("pro", observer.product.fullIdentifier)
                }
            }
        }

    @Test
    fun `a purchase error is handled as a failed purchase`() =
        Given("observing is on") {
            val error = IllegalStateException("declined")
            val state = PurchasingObserverState.PurchaseError(product, error)
            When("the purchase fails") {
                handler.observe(state)
                Then("the transaction manager handles a Failed result with that error") {
                    val result = slot<StorePurchaseUpdate>()
                    val report = slot<ObservedReport>()
                    coVerify(exactly = 1) { transactionManager.handle(capture(result), capture(report)) }
                    assertSame(error, (result.captured as StorePurchaseUpdate.Failed).error)
                    val reported = report.captured as ObservedReport.Error
                    assertSame(error, reported.error)
                    assertEquals(product.productId, reported.product.fullIdentifier)
                }
            }
        }

    @Test
    fun `a purchase result is handled once per internal result`() =
        Given("observing is on and the user cancelled") {
            val billingResult =
                BillingResult
                    .newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED)
                    .build()
            val state = PurchasingObserverState.PurchaseResult(billingResult, emptyList())
            When("the result is observed") {
                handler.observe(state)
                Then("the transaction manager handles a Cancelled result") {
                    val result = slot<StorePurchaseUpdate>()
                    val report = slot<ObservedReport>()
                    coVerify(exactly = 1) { transactionManager.handle(capture(result), capture(report)) }
                    assertTrue(result.captured is StorePurchaseUpdate.Cancelled)
                    assertEquals(ObservedReport.Result(emptyList()), report.captured)
                }
                And("nothing is prepared") {
                    coVerify(exactly = 0) { transactionManager.prepareToPurchase(any(), any()) }
                }
            }
        }
}
