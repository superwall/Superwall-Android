package com.superwall.sdk.web

import com.superwall.sdk.models.internal.RedemptionResult.PaywallInfo.PaywallProduct
import com.superwall.sdk.store.abstractions.product.StoreProductType
import com.superwall.sdk.store.abstractions.product.SubscriptionPeriod
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.ZoneOffset
import org.threeten.bp.format.DateTimeParseException
import java.math.BigDecimal
import java.util.Date

/** Uses the checkout snapshot for trial analytics, including its original prices and end date. */
internal class RedemptionStoreProduct(
    private val product: PaywallProduct,
) : StoreProductType {
    override val fullIdentifier = product.identifier
    override val productIdentifier = product.identifier
    override val price = BigDecimal.valueOf(product.rawPrice)
    override val localizedPrice = product.price
    override val localizedSubscriptionPeriod = product.localizedPeriod
    override val period = product.period
    override val periodly = product.periodly
    override val periodDays = product.periodDays
    override val periodWeeks = product.periodWeeks
    override val periodMonths = product.periodMonths
    override val periodYears = product.periodYears
    override val periodDaysString = periodDays.toString()
    override val periodWeeksString = periodWeeks.toString()
    override val periodMonthsString = periodMonths.toString()
    override val periodYearsString = periodYears.toString()
    override val dailyPrice = product.dailyPrice
    override val weeklyPrice = product.weeklyPrice
    override val monthlyPrice = product.monthlyPrice
    override val yearlyPrice = product.yearlyPrice
    override val hasFreeTrial = product.trialPeriodDays > 0
    override val localizedTrialPeriodPrice = product.trialPeriodPrice
    override val trialPeriodPrice = BigDecimal.valueOf(product.rawTrialPeriodPrice)
    override val trialPeriodEndDateString = product.trialPeriodEndDate
    override val trialPeriodEndDate: Date? by lazy {
        // Checkout snapshots may contain either an ISO timestamp or a calendar date.
        try {
            Date(Instant.parse(product.trialPeriodEndDate).toEpochMilli())
        } catch (_: DateTimeParseException) {
            try {
                Date(
                    LocalDate
                        .parse(product.trialPeriodEndDate)
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli(),
                )
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
    override val trialPeriodDays = product.trialPeriodDays
    override val trialPeriodWeeks = product.trialPeriodWeeks
    override val trialPeriodMonths = product.trialPeriodMonths
    override val trialPeriodYears = product.trialPeriodYears
    override val trialPeriodDaysString = trialPeriodDays.toString()
    override val trialPeriodWeeksString = trialPeriodWeeks.toString()
    override val trialPeriodMonthsString = trialPeriodMonths.toString()
    override val trialPeriodYearsString = trialPeriodYears.toString()
    override val trialPeriodText = product.trialPeriodText
    override val locale = product.locale
    override val languageCode = product.languageCode
    override val currencyCode = product.currencyCode
    override val currencySymbol = product.currencySymbol
    override val regionCode: String? = null
    override val subscriptionPeriod =
        product.periodDays.takeIf { it > 0 }?.let { SubscriptionPeriod(it, SubscriptionPeriod.Unit.day).normalized() }
    override val productType = if (subscriptionPeriod != null) "subs" else "inapp"

    override fun trialPeriodPricePerUnit(unit: SubscriptionPeriod.Unit): String =
        when (unit) {
            SubscriptionPeriod.Unit.day -> product.trialPeriodDailyPrice
            SubscriptionPeriod.Unit.week -> product.trialPeriodWeeklyPrice
            SubscriptionPeriod.Unit.month -> product.trialPeriodMonthlyPrice
            SubscriptionPeriod.Unit.year -> product.trialPeriodYearlyPrice
        }

    override val attributes: Map<String, String>
        get() = super.attributes + ("periodAlt" to product.periodAlt)
}
