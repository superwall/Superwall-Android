package com.superwall.sdk.store.abstractions.product

import com.android.billingclient.api.SkuDetails
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.util.*



import kotlinx.serialization.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializer(forClass = SkuDetails::class)
object SkuDetailsSerializer : KSerializer<SkuDetails> {
    override fun serialize(encoder: Encoder, value: SkuDetails) {
        encoder.encodeString(value.price)  // replace "property" with actual property name
    }

    override fun deserialize(decoder: Decoder): SkuDetails {
        val property = decoder.decodeString()
        return SkuDetails(property)  // replace with actual SkuDetails constructor
    }
}

@Serializable
data class RawStoreProduct(@Serializable(with = SkuDetailsSerializer::class) val skuDetails: SkuDetails)

// TODO: Fill in all these with appropirate implementations

@Serializable
class StoreProduct(
    val rawStoreProduct: RawStoreProduct
) : StoreProductType {

    override val productIdentifier: String
        get() = rawStoreProduct.skuDetails.sku

    override val price: BigDecimal
        get() = BigDecimal(rawStoreProduct.skuDetails.priceAmountMicros) / BigDecimal(1_000_000)

    override val subscriptionGroupIdentifier: String?
        get() = ""
//        get() = TODO("This information is not available in SkuDetails")

    // I think this is the v2 product variables we never shipped so I'm going to skip it
//    override val swProductTemplateVariablesJson: JsonObject
//        get() = TODO("Implement based on your needs")

    override val subscriptionPeriod: SubscriptionPeriod?
        get() =  SubscriptionPeriod.from(rawStoreProduct.skuDetails.subscriptionPeriod)

    override val localizedPrice: String
        get() = rawStoreProduct.skuDetails.price

    override val localizedSubscriptionPeriod: String
        get() = rawStoreProduct.skuDetails.subscriptionPeriod

    override val period: String
        get() = rawStoreProduct.skuDetails.subscriptionPeriod

    override val periodly: String
        get() = "" // TODO: Implement based on your needs

    override val periodWeeks: Int
        get() = 0 // Convert period into weeks based on your needs

    override val periodWeeksString: String
        get() = periodWeeks.toString()

    override val periodMonths: Int
        get() = 0 // Convert period into months based on your needs

    override val periodMonthsString: String
        get() = periodMonths.toString()

    override val periodYears: Int
        get() = 0 // Convert period into years based on your needs

    override val periodYearsString: String
        get() = periodYears.toString()

    override val periodDays: Int
        get() = 0 // Convert period into days based on your needs

    override val periodDaysString: String
        get() = periodDays.toString()

    override val dailyPrice: String
        get() = "" // Calculate daily price based on your needs

    override val weeklyPrice: String
        get() = "" // Calculate weekly price based on your needs

    override val monthlyPrice: String
        get() = "" // Calculate monthly price based on your needs

    override val yearlyPrice: String
        get() = "" // Calculate yearly price based on your needs

    override val hasFreeTrial: Boolean
        get() = rawStoreProduct.skuDetails.freeTrialPeriod.isNotEmpty()

    override val trialPeriodEndDate: Date?
        get() = null // Calculate trial period end date based on your needs

    override val trialPeriodEndDateString: String
        get() = "" // Format trialPeriodEndDate as a string based on your needs

    override val trialPeriodDays: Int
        get() = 0 // Calculate trial period days based on your needs

    override val trialPeriodDaysString: String
        get() = trialPeriodDays.toString()

    override val trialPeriodWeeks: Int
        get() = 0 // Calculate trial period weeks based on your needs

    override val trialPeriodWeeksString: String
        get() = trialPeriodWeeks.toString()

    override val trialPeriodMonths: Int
        get() = 0 // Calculate trial period months based on your needs

    override val trialPeriodMonthsString: String
        get() = trialPeriodMonths.toString()

    override val trialPeriodYears: Int
        get() = 0 // Calculate trial period years based on your needs

    override val trialPeriodYearsString: String
        get() = trialPeriodYears.toString()

    override val trialPeriodText: String
        get() = rawStoreProduct.skuDetails.freeTrialPeriod

    override val locale: String
        get() = "" // Get the locale based on your needs

    override val languageCode: String?
        get() = null // Get the language code based on your needs

    override val currencyCode: String?
        get() = rawStoreProduct.skuDetails.priceCurrencyCode

    override val currencySymbol: String?
        get() = null // Get the currency symbol based on your needs

    override val regionCode: String?
        get() = null // Get the region code based on your needs


    val attributes: Map<String, String>
        get() {
            val attributes = mutableMapOf<String, String>()

            attributes["rawPrice"] = price.toString()
            attributes["price"] = localizedPrice
            attributes["periodAlt"] = localizedSubscriptionPeriod
            attributes["localizedPeriod"] = localizedSubscriptionPeriod
            attributes["period"] = period
            attributes["periodly"] = periodly
            attributes["weeklyPrice"] = weeklyPrice
            attributes["dailyPrice"] = dailyPrice
            attributes["monthlyPrice"] = monthlyPrice
            attributes["yearlyPrice"] = yearlyPrice
            attributes["trialPeriodDays"] = trialPeriodDaysString
            attributes["trialPeriodWeeks"] = trialPeriodWeeksString
            attributes["trialPeriodMonths"] = trialPeriodMonthsString
            attributes["trialPeriodYears"] = trialPeriodYearsString
            attributes["trialPeriodText"] = trialPeriodText
            attributes["trialPeriodEndDate"] = trialPeriodEndDateString
            attributes["periodDays"] = periodDaysString
            attributes["periodWeeks"] = periodWeeksString
            attributes["periodMonths"] = periodMonthsString
            attributes["periodYears"] = periodYearsString
            attributes["locale"] = locale
            attributes["languageCode"] = languageCode ?: "n/a"
            attributes["currencyCode"] = currencyCode ?: "n/a"
            attributes["currencySymbol"] = currencySymbol ?: "n/a"
            attributes["identifier"] = productIdentifier

            return attributes
        }

}