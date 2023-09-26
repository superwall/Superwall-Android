package com.superwall.sdk.store.abstractions.product


import com.android.billingclient.api.SkuDetails
import com.superwall.sdk.contrib.threeteen.AmountFormats
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.text.DecimalFormat
import java.util.*

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

    override val subscriptionPeriod: SubscriptionPeriod?
        get() = SubscriptionPeriod.from(rawStoreProduct.skuDetails.subscriptionPeriod)

    override val localizedPrice: String
        get() = rawStoreProduct.skuDetails.price

    override val localizedSubscriptionPeriod: String
        get() =  if (subscriptionPeriod != null)  AmountFormats.wordBased(
            subscriptionPeriod?.toPeriod()!!, Locale.getDefault()
        ) else ""

    override val period: String
        get() = subscriptionPeriod?.period ?: ""

    override val periodly: String
        get() = subscriptionPeriod?.periodly ?: ""

    override val periodWeeks: Int
        get() = subscriptionPeriod?.periodWeeks ?: 0

    override val periodWeeksString: String
        get() = periodWeeks.toString()

    override val periodMonths: Int
        get() = 0 // subscriptionPeriod?.pe

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
        get() {
            if (price == BigDecimal(0.00)) {
                return "$0.00"
            }

            val numberFormatter = DecimalFormat.getCurrencyInstance()
            numberFormatter.currency = Currency.getInstance(Locale.getDefault())

            val subscriptionPeriod = subscriptionPeriod
            if (subscriptionPeriod == null) {
                return "n/a"
            }

            val numberOfUnits = subscriptionPeriod.value
            var periods: BigDecimal = BigDecimal(1)
            val inputPrice = price

            when (subscriptionPeriod.unit) {
                SubscriptionPeriod.Unit.year -> periods = BigDecimal(365 * numberOfUnits)
                SubscriptionPeriod.Unit.month -> periods = BigDecimal(30 * numberOfUnits)
                SubscriptionPeriod.Unit.week -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(7), BigDecimal.ROUND_HALF_EVEN)
                SubscriptionPeriod.Unit.day -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(1), BigDecimal.ROUND_HALF_EVEN)
            }

            val dailyPriceDecimal = inputPrice.divide(periods, 2, BigDecimal.ROUND_HALF_EVEN)  // 2 is the scale (number of decimal places)
            println("!!! dailyPrice  $inputPrice, $periods, $dailyPriceDecimal")
            return numberFormatter.format(dailyPriceDecimal.toDouble()) ?: "n/a"
        }

    override val weeklyPrice: String
        get() {
            if (price == BigDecimal(0.00)) {
                return "$0.00"
            }

            val numberFormatter = DecimalFormat.getCurrencyInstance()
            numberFormatter.currency = Currency.getInstance(Locale.getDefault())

            val subscriptionPeriod = subscriptionPeriod
            if (subscriptionPeriod == null) {
                return "n/a"
            }

            val numberOfUnits = subscriptionPeriod.value
            var periods: BigDecimal = BigDecimal(1)
            val inputPrice = price

            when (subscriptionPeriod.unit) {
                SubscriptionPeriod.Unit.year -> periods = BigDecimal(52 * numberOfUnits)
                SubscriptionPeriod.Unit.month -> periods = BigDecimal(4 * numberOfUnits)
                SubscriptionPeriod.Unit.week -> periods = BigDecimal(numberOfUnits)
                SubscriptionPeriod.Unit.day -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(7), BigDecimal.ROUND_HALF_EVEN)
            }

            val weeklyPriceDecimal = inputPrice.divide(periods, 2, BigDecimal.ROUND_HALF_EVEN)  // 2 is the scale (number of decimal places)
            println("!!! weeklyPrice  $inputPrice, $periods, $weeklyPriceDecimal")
            return numberFormatter.format(weeklyPriceDecimal.toDouble()) ?: "n/a"
        }
    override val monthlyPrice: String
        get() {
            if (price == BigDecimal(0.00)) {
                return "$0.00"
            }

            val numberFormatter = DecimalFormat.getCurrencyInstance()
            numberFormatter.currency = Currency.getInstance(Locale.getDefault())

            val subscriptionPeriod = subscriptionPeriod
            if (subscriptionPeriod == null) {
                return "n/a"
            }

            val numberOfUnits = subscriptionPeriod.value
            var periods: BigDecimal = BigDecimal(1)
            val inputPrice = price

            when (subscriptionPeriod.unit) {
                SubscriptionPeriod.Unit.year -> periods = BigDecimal(12 * numberOfUnits)
                SubscriptionPeriod.Unit.month -> periods = BigDecimal(numberOfUnits)
                SubscriptionPeriod.Unit.week -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(4), BigDecimal.ROUND_HALF_EVEN) // Assumes 4 weeks per month
                SubscriptionPeriod.Unit.day -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(30), BigDecimal.ROUND_HALF_EVEN) // Assumes 30 days per month
            }

            val monthlyPriceDecimal = inputPrice.divide(periods, 2, BigDecimal.ROUND_HALF_EVEN)  // 2 is the scale (number of decimal places)
            println("!!! monthlyPrice  $inputPrice, $periods, $monthlyPriceDecimal")
            return numberFormatter.format(monthlyPriceDecimal.toDouble()) ?: "n/a"
        }

    override val yearlyPrice: String
        get() {
            if (price == BigDecimal(0.00)) {
                return "$0.00"
            }

            val numberFormatter = DecimalFormat.getCurrencyInstance()
            numberFormatter.currency = Currency.getInstance(Locale.getDefault())

            val subscriptionPeriod = subscriptionPeriod
            if (subscriptionPeriod == null) {
                return "n/a"
            }

            val numberOfUnits = subscriptionPeriod.value
            var periods: BigDecimal = BigDecimal(1)
            val inputPrice = price

            when (subscriptionPeriod.unit) {
                SubscriptionPeriod.Unit.year -> periods = BigDecimal(numberOfUnits)
                SubscriptionPeriod.Unit.month -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(12), BigDecimal.ROUND_HALF_EVEN)
                SubscriptionPeriod.Unit.week -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(52), BigDecimal.ROUND_HALF_EVEN)
                SubscriptionPeriod.Unit.day -> periods = BigDecimal(numberOfUnits).divide(BigDecimal(365), BigDecimal.ROUND_HALF_EVEN)
            }

            val yearlyPriceDecimal = inputPrice.divide(periods, 2, BigDecimal.ROUND_HALF_EVEN)  // 2 is the scale (number of decimal places)
            println("!!! yearlyPrice  $inputPrice, $periods, $yearlyPriceDecimal")
            return numberFormatter.format(yearlyPriceDecimal.toDouble()) ?: "n/a"
        }



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