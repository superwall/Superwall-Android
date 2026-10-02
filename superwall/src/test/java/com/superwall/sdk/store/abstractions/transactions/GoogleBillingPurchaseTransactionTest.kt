@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.store.abstractions.transactions

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleBillingPurchaseTransactionTest {
    @Test
    fun `originalOrderId strips the Play recurrence suffix`() {
        Given("Google Play order ids with and without recurrence suffixes") {
            val baseOrderId = "GPA.1234-1234-1234-12345"

            When("deriving the original order id") {
                Then("an id without a suffix is unchanged") {
                    assertEquals(baseOrderId, GoogleBillingPurchaseTransaction.originalOrderId(baseOrderId))
                }

                Then("a `..0` suffix is stripped") {
                    assertEquals(baseOrderId, GoogleBillingPurchaseTransaction.originalOrderId("$baseOrderId..0"))
                }

                Then("a `..1` suffix is stripped") {
                    assertEquals(baseOrderId, GoogleBillingPurchaseTransaction.originalOrderId("$baseOrderId..1"))
                }

                Then("a multi-digit `..12` suffix is stripped") {
                    assertEquals(baseOrderId, GoogleBillingPurchaseTransaction.originalOrderId("$baseOrderId..12"))
                }

                Then("single dots within the id are not truncated") {
                    assertEquals(
                        "GPA.1234-1234-1234-12345",
                        GoogleBillingPurchaseTransaction.originalOrderId("GPA.1234-1234-1234-12345"),
                    )
                }

                Then("a null order id stays null") {
                    assertNull(GoogleBillingPurchaseTransaction.originalOrderId(null))
                }
            }
        }
    }
}
