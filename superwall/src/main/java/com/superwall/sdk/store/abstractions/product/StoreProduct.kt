package com.superwall.sdk.store.abstractions.product


import com.android.billingclient.api.SkuDetails
import com.superwall.sdk.contrib.threeteen.AmountFormats
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.SimpleDateFormat
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
        get() = BigDecimal(rawStoreProduct.skuDetails.priceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)

    val trialPrice: BigDecimal
        get() = BigDecimal(rawStoreProduct.skuDetails.introductoryPriceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)

    override val subscriptionGroupIdentifier: String?
        get() = ""
//        get() = TODO("This information is not available in SkuDetails")

    override val subscriptionPeriod: SubscriptionPeriod?
        get() {
            return try {
                SubscriptionPeriod.from(
                    rawStoreProduct.skuDetails.subscriptionPeriod,
                    Currency.getInstance(rawStoreProduct.skuDetails.priceCurrencyCode)
                )
            } catch (e: Exception) {
                null
            }
        }

    /**
     * The trial subscription period of the product.
     */
    val trialSubscriptionPeriod: SubscriptionPeriod?
        get() {
            return try {
                SubscriptionPeriod.from(
                    rawStoreProduct.skuDetails.freeTrialPeriod,
                    Currency.getInstance(rawStoreProduct.skuDetails.priceCurrencyCode)
                )
            } catch (e: Exception) {
                null
            }
        }

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
        get() = subscriptionPeriod?.periodMonths ?: 0

    override val periodMonthsString: String
        get() = periodMonths.toString()

    override val periodYears: Int
        get() = subscriptionPeriod?.periodYears ?: 0

    override val periodYearsString: String
        get() = periodYears.toString()

    override val periodDays: Int
        get() = subscriptionPeriod?.periodDays ?: 0

    override val periodDaysString: String
        get() = periodDays.toString()

    override val dailyPrice: String
        get() = subscriptionPeriod?.dailyPrice(price) ?: "n/a"

    override val weeklyPrice: String
        get() = subscriptionPeriod?.weeklyPrice(price) ?: "n/a"

    override val monthlyPrice: String
        get() = subscriptionPeriod?.monthlyPrice(price) ?: "n/a"

    override val yearlyPrice: String
        get() = subscriptionPeriod?.yearlyPrice(price) ?: "n/a"


    override val hasFreeTrial: Boolean
        get() = rawStoreProduct.skuDetails.freeTrialPeriod.isNotEmpty()

    override val trialPeriodEndDate: Date?
        get() = if (trialSubscriptionPeriod != null)  {
            val calendar = Calendar.getInstance()
            when (trialSubscriptionPeriod!!.unit) {
                SubscriptionPeriod.Unit.day -> calendar.add(Calendar.DAY_OF_YEAR, trialSubscriptionPeriod!!.value)
                SubscriptionPeriod.Unit.week -> calendar.add(Calendar.WEEK_OF_YEAR, trialSubscriptionPeriod!!.value)
                SubscriptionPeriod.Unit.month -> calendar.add(Calendar.MONTH, trialSubscriptionPeriod!!.value)
                SubscriptionPeriod.Unit.year -> calendar.add(Calendar.YEAR, trialSubscriptionPeriod!!.value)
            }
            calendar.time
        } else {
            null
        }

    override val trialPeriodEndDateString: String
        get() = if (trialPeriodEndDate != null) {
            val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            dateFormatter.format(trialPeriodEndDate!!)
        } else {
            ""
        }

    override val trialPeriodDays: Int
        get() =
            trialSubscriptionPeriod?.periodDays ?:  0

    override val trialPeriodDaysString: String
        get() = trialPeriodDays.toString()

    override val trialPeriodWeeks: Int
        get() {
            val trialPeriod = trialSubscriptionPeriod ?: return 0
            val numberOfUnits = trialPeriod.value

            return when (trialPeriod.unit) {
                SubscriptionPeriod.Unit.day -> numberOfUnits / 7
                SubscriptionPeriod.Unit.month -> 4 * numberOfUnits  // Assumes 4 weeks in a month
                SubscriptionPeriod.Unit.week -> 1 * numberOfUnits
                SubscriptionPeriod.Unit.year -> 52 * numberOfUnits  // Assumes 52 weeks in a year
                else -> 0
            }
        }

    override val trialPeriodWeeksString: String
        get() = trialPeriodWeeks.toString()

    override val trialPeriodMonths: Int
        get() =
            trialSubscriptionPeriod?.periodMonths ?:  0

    override val trialPeriodMonthsString: String
        get() = trialPeriodMonths.toString()

    override val trialPeriodYears: Int
        get() =
            trialSubscriptionPeriod?.periodYears ?:  0

    override val trialPeriodYearsString: String
        get() = trialPeriodYears.toString()

    override val trialPeriodText: String
        get() = trialSubscriptionPeriod?.period ?: ""

    override val localizedTrialPeriodPrice: String
        get() = rawStoreProduct.skuDetails.introductoryPrice

    override val locale: String
        get() = Locale.getDefault().toString()

    override val languageCode: String?
        get() = Locale.getDefault().language

    override val currencyCode: String?
        get() = rawStoreProduct.skuDetails.priceCurrencyCode

    override val currencySymbol: String?
        get() = Currency.getInstance(rawStoreProduct.skuDetails.priceCurrencyCode).symbol

    override val regionCode: String?
        get() = Locale.getDefault().country


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
            attributes["rawTrialPeriodPrice"] = trialPrice.toString()
            attributes["trialPeriodPrice"] = localizedTrialPeriodPrice
            attributes["trialPeriodDailyPrice"] = trialSubscriptionPeriod?.dailyPrice(trialPrice) ?: "n/a"
            attributes["trialPeriodWeeklyPrice"] = trialSubscriptionPeriod?.weeklyPrice(trialPrice) ?: "n/a"
            attributes["trialPeriodMonthlyPrice"] = trialSubscriptionPeriod?.monthlyPrice(trialPrice) ?: "n/a"
            attributes["trialPeriodYearlyPrice"] = trialSubscriptionPeriod?.yearlyPrice(trialPrice) ?: "n/a"
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