@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store.abstractions.transactions

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class CustomStoreTransactionTest {
    @Test
    fun `custom-transaction constructor sets ids and skips SK2 fields`() {
        Given("a pre-generated transaction id, product id and purchase date") {
            val txnId = "ABC-123-CUSTOM"
            val productId = "stripe_pro_monthly"
            val purchaseDate = Date(1_700_000_000_000L)

            When("a StoreTransaction is built via the custom constructor") {
                val tx =
                    StoreTransaction(
                        customTransactionId = txnId,
                        productIdentifier = productId,
                        purchaseDate = purchaseDate,
                        configRequestId = "req-1",
                        appSessionId = "sess-1",
                    )

                Then("originalTransactionIdentifier and storeTransactionId equal the custom id") {
                    assertEquals(txnId, tx.originalTransactionIdentifier)
                    assertEquals(txnId, tx.storeTransactionId)
                }

                Then("transaction date and original date both equal the purchase date") {
                    assertEquals(purchaseDate, tx.transactionDate)
                    assertEquals(purchaseDate, tx.originalTransactionDate)
                }

                Then("state is Purchased") {
                    assertEquals(StoreTransactionState.Purchased, tx.state)
                }

                Then("payment carries the product identifier") {
                    assertEquals(productId, tx.payment?.productIdentifier)
                }

                Then("SK2 / Play-specific fields are nullable empties") {
                    assertNull(tx.webOrderLineItemID)
                    assertNull(tx.appBundleId)
                    assertNull(tx.subscriptionGroupId)
                    assertNull(tx.isUpgraded)
                    assertNull(tx.expirationDate)
                    assertNull(tx.offerId)
                    assertNull(tx.revocationDate)
                    assertNull(tx.appAccountToken)
                    assertEquals("", tx.purchaseToken)
                    assertNull(tx.signature)
                }

                Then("config and session ids are preserved") {
                    assertEquals("req-1", tx.configRequestId)
                    assertEquals("sess-1", tx.appSessionId)
                }
            }
        }
    }
}
