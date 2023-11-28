package com.superwall.sdk.store.abstractions.product

import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetails.OneTimePurchaseOfferDetails
import com.android.billingclient.api.ProductDetails.PricingPhase
import com.android.billingclient.api.ProductDetails.PricingPhases
import com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails
import kotlinx.serialization.Serializable

@Serializable
data class SerializableOneTimePurchaseOfferDetails(
    val priceAmountMicros: Long
)

@Serializable
data class SerializablePricingPhase(
    val billingCycleCount: Int,
    val recurrencyMode: Int,
    val priceAmountMicros: Long,
    val billingPeriod: String,
    val formattedPrice: String,
    val priceCurrencyCode: String
)

@Serializable
data class SerializablePricingPhases(
    val pricingPhaseList: List<SerializablePricingPhase>
)

@Serializable
data class SerializableSubscriptionOfferDetails(
    val pricingPhases: SerializablePricingPhases,
    val offerTags: List<String>,
    val basePlanId: String,
    val offerId: String?
)

@Serializable
data class SerializableProductDetails(
    val oneTimePurchaseOfferDetails: SerializableOneTimePurchaseOfferDetails?,
    val subscriptionOfferDetails: List<SerializableSubscriptionOfferDetails>?,
    val productId: String,
    val title: String,
    val description: String,
    val productType: String
) {
  companion object {
      fun from(productDetails: ProductDetails): SerializableProductDetails {
          val oneTimePurchaseOfferDetails = productDetails.oneTimePurchaseOfferDetails?.let {
              SerializableOneTimePurchaseOfferDetails(
                  priceAmountMicros = it.priceAmountMicros
              )
          }

          val subscriptionOfferDetails = productDetails.subscriptionOfferDetails?.map { offerDetails ->
              SerializableSubscriptionOfferDetails(
                  pricingPhases = SerializablePricingPhases(
                      pricingPhaseList = offerDetails.pricingPhases.pricingPhaseList.map { pricingPhase ->
                          SerializablePricingPhase(
                              billingCycleCount = pricingPhase.billingCycleCount,
                              recurrencyMode = pricingPhase.recurrenceMode,
                              priceAmountMicros = pricingPhase.priceAmountMicros,
                              billingPeriod = pricingPhase.billingPeriod,
                              formattedPrice = pricingPhase.formattedPrice,
                              priceCurrencyCode = pricingPhase.priceCurrencyCode
                          )
                      }
                  ),
                  offerTags = offerDetails.offerTags,
                  basePlanId = offerDetails.basePlanId,
                  offerId = offerDetails.offerId
              )
          }

          return SerializableProductDetails(
              oneTimePurchaseOfferDetails = oneTimePurchaseOfferDetails,
              subscriptionOfferDetails = subscriptionOfferDetails,
              productId = productDetails.productId,
              title = productDetails.title,
              description = productDetails.description,
              productType = productDetails.productType
          )
      }
  }
}
