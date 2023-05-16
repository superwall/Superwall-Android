package com.superwall.sdk.billing

import com.android.billingclient.api.SkuDetails
import org.json.JSONObject
import java.math.BigDecimal
import java.util.*

class GooglePlayStoreProduct(private val skuDetails: SkuDetails) : StoreProductType {
    override val productIdentifier: String
        get() = skuDetails.sku

    override val price: BigDecimal
        get() = BigDecimal(skuDetails.priceAmountMicros / 1_000_000.0)

    // These properties might not be applicable or retrievable from SkuDetails.
    // Some assumptions have been made here.
    override val subscriptionGroupIdentifier: String?
        get() = null

    override val swProductTemplateVariablesJson: JSONObject
        get() = JSONObject() // Replace with actual JSON

//    override val swProduct: SWProduct
//        get() = SWProduct() // Replace with actual SWProduct

    override val localizedPrice: String
        get() = skuDetails.price

    override val localizedSubscriptionPeriod: String
        get() = "" // Not available from SkuDetails

    override val period: String
        get() = skuDetails.subscriptionPeriod

    override val periodly: String
        get() = "" // Not available from SkuDetails

    override val periodWeeks: Int
        get() = 0 // Not available from SkuDetails

    override val periodWeeksString: String
        get() = "" // Not available from SkuDetails

    override val periodMonths: Int
//        get() = skuDetails.subscriptionPeriod?.unit?.let { if (it == SkuDetails.SubscriptionPeriod.Unit.MONTH) skuDetails.subscriptionPeriod.count else 0 } ?: 0
        get() = 0

    override val periodMonthsString: String
        get() = periodMonths.toString()

    override val periodYears: Int
    get() = 0
//        get() = skuDetails.subscriptionPeriod?.unit?.let { if (it == SkuDetails.SubscriptionPeriod.Unit.YEAR) skuDetails.subscriptionPeriod.count else 0 } ?: 0

    override val periodYearsString: String
        get() = periodYears.toString()

    override val periodDays: Int
        get() = parseBillingPeriod(skuDetails.subscriptionPeriod)

    override val periodDaysString: String
        get() = "${periodDays}"

    // Below details are not available from SkuDetails
    // TODO: Figure out the proper formatting
    override val dailyPrice: String
        get() = "${price / BigDecimal(periodDays)}"

    override val weeklyPrice: String
        get() = "${price / BigDecimal(periodDays * 7)}}"

    override val monthlyPrice: String
        get() = "${price / BigDecimal(periodDays * 30)}"

    override val yearlyPrice: String
        get() = "${price / BigDecimal(periodDays * 365)}"

    override val hasFreeTrial: Boolean
        get() = trialPeriodDays > 0

    // TODO: Figure out the syntax for this
    override val trialPeriodEndDate: Date?
        get() = _trialPeriodEndDate()

    private fun _trialPeriodEndDate(): Date? {
        if (!hasFreeTrial) {
            return null
        }
        val calendar = Calendar.getInstance()
        val date = Date()
        calendar.time = date
        calendar.add(Calendar.DATE, trialPeriodDays)
        return calendar.time
    }

    override val trialPeriodEndDateString: String
        get() = _trialPeriodEndDate()?.toString() ?: ""

    override val trialPeriodDays: Int
        get() = parseBillingPeriod(skuDetails.freeTrialPeriod)

    override val trialPeriodDaysString: String
        get() = "$trialPeriodDays"

    override val trialPeriodWeeks: Int
        get() = trialPeriodDays / 7

    override val trialPeriodWeeksString: String
        get() = "${trialPeriodWeeks}"

    override val trialPeriodMonths: Int
        get() = trialPeriodDays / 30

    override val trialPeriodMonthsString: String
        get() = "${trialPeriodMonths}"

    override val trialPeriodYears: Int
        get() = trialPeriodDays / 365

    override val trialPeriodYearsString: String
        get() = "${trialPeriodYears}"

    override val trialPeriodText: String
        get() = when {
            trialPeriodYears > 0 -> trialPeriodYearsString
            trialPeriodMonths > 0 -> trialPeriodMonthsString
            trialPeriodWeeks > 0 -> trialPeriodWeeksString
            else -> trialPeriodDaysString
        }


    override val locale: String
        get() =
            Locale.getDefault().toString()

    override val languageCode: String?
        get() = Locale.getDefault().language

    override val currencyCode: String?
        get() = skuDetails.priceCurrencyCode

    override val currencySymbol: String?
        get() = Currency.getInstance(skuDetails.priceCurrencyCode).symbol

    override val regionCode: String?
        get() = ""

    private fun parseBillingPeriod(period: String): Int {
        if (period.isEmpty())  {
            return 0
        }

        // Default periods in days
        val daysInMonth = 30
        val daysInWeek = 7

        // Remove 'P' prefix and split by remaining identifiers
        val splitPeriod = period.removePrefix("P").split("M", "W", "D")

        var totalDays = 0

        splitPeriod.forEachIndexed { index, part ->
            if (part.isNotEmpty()) {
                when (index) {
                    0 -> totalDays += part.toInt() * daysInMonth  // Months
                    1 -> totalDays += part.toInt() * daysInWeek   // Weeks
                    2 -> totalDays += part.toInt()                 // Days
                }
            }
        }

        return totalDays
    }
}
