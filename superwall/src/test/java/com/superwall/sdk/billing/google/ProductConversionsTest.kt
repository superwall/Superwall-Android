@file:Suppress("ktlint:standard:function-naming")

package com.superwall.sdk.billing.google

import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.store.ProductReplacement
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductConversionsTest {
    private fun product(offer: RawStoreProduct.SelectedOfferDetails?): RawStoreProduct =
        mockk {
            every { selectedOffer } returns offer
        }

    private fun oneTime(
        purchaseOptionId: String?,
        token: String = "otp-token",
    ) = RawStoreProduct.SelectedOfferDetails.OneTime(
        underlying = mockk<ProductDetails.OneTimePurchaseOfferDetails> { every { offerToken } returns token },
        purchaseOptionId = purchaseOptionId,
        offerId = null,
    )

    @Test
    fun `a subscription offer passes its token`() {
        Given("a Play value") { Then("the subscription offer's token is used") {
            val offer =
                RawStoreProduct.SelectedOfferDetails.Subscription(
                    mockk<ProductDetails.SubscriptionOfferDetails> { every { offerToken } returns "sub-token" },
                )
            assertEquals("sub-token", product(offer).offerToken(requestedOfferId = null))
        } }
    }

    @Test
    fun `a legacy one-time product passes no token unless an offer was asked for`() {
        Given("a Play value") { Then("no token without purchase options or a requested offer, the token with one") {
            assertNull(product(oneTime(purchaseOptionId = null)).offerToken(requestedOfferId = null))
            assertEquals("otp-token", product(oneTime(purchaseOptionId = null)).offerToken(requestedOfferId = "promo"))
            assertEquals("otp-token", product(oneTime(purchaseOptionId = "option")).offerToken(requestedOfferId = null))
        } }
    }

    @Test
    fun `an empty token or no offer passes no token`() {
        Given("a Play value") { Then("Play never gets an empty token") {
            assertNull(product(oneTime(purchaseOptionId = "option", token = "")).offerToken(requestedOfferId = null))
            assertNull(product(null).offerToken(requestedOfferId = null))
        } }
    }

    @Test
    fun `a replacement needs the old purchase token`() {
        Given("a Play value") { Then("there are no update params without it") {
            assertNull(ProductReplacement(ProductReplacement.Mode.DEFAULT, oldProductId = "old", oldPurchaseToken = null).toSubscriptionUpdateParams())
        } }
    }

    @Test
    fun `Play product types map to neutral kinds`() {
        org.junit.Assert.assertEquals(
            com.superwall.sdk.billing.StoreProductKind.Subscription,
            productKindOf(com.android.billingclient.api.BillingClient.ProductType.SUBS),
        )
        org.junit.Assert.assertEquals(
            com.superwall.sdk.billing.StoreProductKind.OneTime,
            productKindOf(com.android.billingclient.api.BillingClient.ProductType.INAPP),
        )
        org.junit.Assert.assertEquals(com.superwall.sdk.billing.StoreProductKind.Unknown, productKindOf(null))
    }
}
