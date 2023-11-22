package com.superwall.sdk.store.abstractions.product
import com.android.billingclient.api.SkuDetails
import com.superwall.sdk.contrib.threeteen.AmountFormats
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale

@Serializable
class RawStoreProduct(
    @Serializable(with = SkuDetailsSerializer::class) val underlyingSkuDetails: SkuDetails
) : StoreProductType {
    @Transient
    private val priceFormatterProvider = PriceFormatterProvider()

    private val priceFormatter: NumberFormat?
        get() = currencyCode?.let {
            priceFormatterProvider.priceFormatter(it)
        }

    override val productIdentifier: String
        get() = underlyingSkuDetails.sku

    override val price: BigDecimal
        get() = underlyingSkuDetails.priceValue

    override val localizedPrice: String
        get() = priceFormatter?.format(underlyingSkuDetails.priceValue) ?: ""

    override val localizedSubscriptionPeriod: String
        get() = subscriptionPeriod?.let {
            AmountFormats.wordBased(it.toPeriod(), Locale.getDefault())
        } ?: ""

    override val period: String
        get() {
            return subscriptionPeriod?.let {
                return when (it.unit) {
                    SubscriptionPeriod.Unit.day -> if (it.value == 7) "week" else "day"
                    SubscriptionPeriod.Unit.week -> "week"
                    SubscriptionPeriod.Unit.month -> when (it.value) {
                        2 -> "2 months"
                        3 -> "quarter"
                        6 -> "6 months"
                        else -> "month"
                    }
                    SubscriptionPeriod.Unit.year -> "year"
                }
            } ?: ""
        }

    override val periodly: String
        get() {
            return subscriptionPeriod?.let {
                return when (it.unit) {
                    SubscriptionPeriod.Unit.month -> when (it.value) {
                        2, 6 -> "every $period"
                        else -> "${period}ly"
                    }
                    else -> "${period}ly"
                }
            } ?: ""
        }

    override val periodWeeks: Int
        get() = subscriptionPeriod?.let {
            val numberOfUnits = it.value
            when (it.unit) {
                SubscriptionPeriod.Unit.day -> (1 * numberOfUnits) / 7
                SubscriptionPeriod.Unit.week -> numberOfUnits
                SubscriptionPeriod.Unit.month -> 4 * numberOfUnits
                SubscriptionPeriod.Unit.year -> 52 * numberOfUnits
                else -> 0
            }
        } ?: 0

    override val periodWeeksString: String
        get() = periodWeeks.toString()

    override val periodMonths: Int
        get() = subscriptionPeriod?.let {
            val numberOfUnits = it.value
            when (it.unit) {
                SubscriptionPeriod.Unit.day -> numberOfUnits / 30
                SubscriptionPeriod.Unit.week -> numberOfUnits / 4
                SubscriptionPeriod.Unit.month -> numberOfUnits
                SubscriptionPeriod.Unit.year -> 12 * numberOfUnits
                else -> 0
            }
        } ?: 0

    override val periodMonthsString: String
        get() =  periodMonths.toString()

    override val periodYears: Int
        get() = subscriptionPeriod?.let {
            val numberOfUnits = it.value
            when (it.unit) {
                SubscriptionPeriod.Unit.day -> numberOfUnits / 365
                SubscriptionPeriod.Unit.week -> numberOfUnits / 52
                SubscriptionPeriod.Unit.month -> numberOfUnits / 12
                SubscriptionPeriod.Unit.year -> numberOfUnits
                else -> 0
            }
        } ?: 0

    override val periodYearsString: String
        get() = periodYears.toString()

    override val periodDays: Int
        get() {
            return subscriptionPeriod?.let {
                val numberOfUnits = it.value

                return when (it.unit) {
                    SubscriptionPeriod.Unit.day -> 1 * numberOfUnits
                    SubscriptionPeriod.Unit.month -> 30 * numberOfUnits  // Assumes 30 days in a month
                    SubscriptionPeriod.Unit.week -> 7 * numberOfUnits   // Assumes 7 days in a week
                    SubscriptionPeriod.Unit.year -> 365 * numberOfUnits // Assumes 365 days in a year
                    else -> 0
                }
            } ?: 0
        }

    override val periodDaysString: String
        get() = periodDays.toString()

    override val dailyPrice: String
        get() {
            if (underlyingSkuDetails.priceValue == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"

            val inputPrice = underlyingSkuDetails.priceValue
            val pricePerDay = subscriptionPeriod.pricePerDay(inputPrice)

            return priceFormatter?.format(pricePerDay) ?: "n/a"
        }

    override val weeklyPrice: String
        get() {
            if (underlyingSkuDetails.priceValue == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"

            val inputPrice = underlyingSkuDetails.priceValue
            val pricePerWeek = subscriptionPeriod.pricePerWeek(inputPrice)

            return priceFormatter?.format(pricePerWeek) ?: "n/a"
        }

    override val monthlyPrice: String
        get() {
            if (underlyingSkuDetails.priceValue == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"

            val inputPrice = underlyingSkuDetails.priceValue
            val pricePerMonth = subscriptionPeriod.pricePerMonth(inputPrice)

            return priceFormatter?.format(pricePerMonth) ?: "n/a"
        }

    override val yearlyPrice: String
        get() {
            if (underlyingSkuDetails.priceValue == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"

            val inputPrice = underlyingSkuDetails.priceValue
            val pricePerYear = subscriptionPeriod.pricePerYear(inputPrice)

            return priceFormatter?.format(pricePerYear) ?: "n/a"
        }

    override val hasFreeTrial: Boolean
        get() = underlyingSkuDetails.freeTrialPeriod.isNotEmpty()

    override val localizedTrialPeriodPrice: String
        get() = priceFormatter?.format(trialPeriodPrice) ?: "$0.00"

    override val trialPeriodPrice: BigDecimal
        get() = underlyingSkuDetails.introductoryPriceValue

    override val trialPeriodEndDate: Date?
        get() = trialSubscriptionPeriod?.let {
            val calendar = Calendar.getInstance()
            when (it.unit) {
                SubscriptionPeriod.Unit.day -> calendar.add(Calendar.DAY_OF_YEAR, it.value)
                SubscriptionPeriod.Unit.week -> calendar.add(Calendar.WEEK_OF_YEAR, it.value)
                SubscriptionPeriod.Unit.month -> calendar.add(Calendar.MONTH, it.value)
                SubscriptionPeriod.Unit.year -> calendar.add(Calendar.YEAR, it.value)
            }
            calendar.time
        }

    override val trialPeriodEndDateString: String
        get() = trialPeriodEndDate?.let {
            val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            dateFormatter.format(it)
        } ?: ""

    override val trialPeriodDays: Int
        get() {
            return trialSubscriptionPeriod?.let {
                val numberOfUnits = it.value

                return when (it.unit) {
                    SubscriptionPeriod.Unit.day -> 1 * numberOfUnits
                    SubscriptionPeriod.Unit.month -> 30 * numberOfUnits  // Assumes 30 days in a month
                    SubscriptionPeriod.Unit.week -> 7 * numberOfUnits   // Assumes 7 days in a week
                    SubscriptionPeriod.Unit.year -> 365 * numberOfUnits // Assumes 365 days in a year
                    else -> 0
                }
            } ?: 0
        }

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
        get() = trialSubscriptionPeriod?.let {
            val numberOfUnits = it.value
            when (it.unit) {
                SubscriptionPeriod.Unit.day -> numberOfUnits / 30
                SubscriptionPeriod.Unit.week -> numberOfUnits / 4
                SubscriptionPeriod.Unit.month -> numberOfUnits
                SubscriptionPeriod.Unit.year -> 12 * numberOfUnits
                else -> 0
            }
        } ?: 0

    override val trialPeriodMonthsString: String
        get() = trialPeriodMonths.toString()

    override val trialPeriodYears: Int
        get() = trialSubscriptionPeriod?.let {
            val numberOfUnits = it.value
            when (it.unit) {
                SubscriptionPeriod.Unit.day -> numberOfUnits / 365
                SubscriptionPeriod.Unit.week -> numberOfUnits / 52
                SubscriptionPeriod.Unit.month -> numberOfUnits / 12
                SubscriptionPeriod.Unit.year -> numberOfUnits
                else -> 0
            }
        } ?: 0

    override val trialPeriodYearsString: String
        get() = trialPeriodYears.toString()

    override val trialPeriodText: String
        get() {
            val trialPeriod = trialSubscriptionPeriod ?: return ""
            val units = trialPeriod.value

            return when (trialPeriod.unit) {
                SubscriptionPeriod.Unit.day -> "${units}-day"
                SubscriptionPeriod.Unit.month -> "${units * 30}-day"
                SubscriptionPeriod.Unit.week -> "${units * 7}-day"
                SubscriptionPeriod.Unit.year -> "${units * 365}-day"
                else -> ""
            }
        }

    // TODO: Differs from iOS, using device locale here instead of product locale
    override val locale: String
        get() = Locale.getDefault().toString()

    // TODO: Differs from iOS, using device language code here instead of product language code
    override val languageCode: String?
        get() = Locale.getDefault().language

    override val currencyCode: String?
        get() = underlyingSkuDetails.priceCurrencyCode

    override val currencySymbol: String?
        get() = Currency.getInstance(underlyingSkuDetails.priceCurrencyCode).symbol

    override val regionCode: String?
        get() = Locale.getDefault().country

    override val subscriptionPeriod: SubscriptionPeriod?
        get() {
            return try {
                SubscriptionPeriod.from(underlyingSkuDetails.subscriptionPeriod)
            } catch (e: Exception) {
                null
            }
        }

    override fun trialPeriodPricePerUnit(unit: SubscriptionPeriod.Unit): String {
        // TODO: ONLY supporting free trial and similar; see `StoreProductDiscount`
        //  pricePerUnit for other cases
        val introductoryDiscount = underlyingSkuDetails.introductoryPriceValue
        return priceFormatter?.format(introductoryDiscount) ?: "$0.00"
    }
}

val SkuDetails.priceValue: BigDecimal
    get() = BigDecimal(priceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)

val SkuDetails.introductoryPriceValue: BigDecimal
    get() = BigDecimal(introductoryPriceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)

val RawStoreProduct.trialSubscriptionPeriod: SubscriptionPeriod?
    get() {
        return try {
            SubscriptionPeriod.from(underlyingSkuDetails.freeTrialPeriod)
        } catch (e: Exception) {
            null
        }
    }