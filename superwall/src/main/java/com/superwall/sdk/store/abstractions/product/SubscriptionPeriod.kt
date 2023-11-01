package com.superwall.sdk.store.abstractions.product

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.Period
import java.time.temporal.ChronoUnit
import java.util.*

data class SubscriptionPeriod(val value: Int, val unit: Unit, val currency: Currency, val pricingFactor: Long = 1_000_000) {
    enum class Unit {
        day,
        week,
        month,
        year
    }

    val daysPerUnit: Double
        get() = when (unit) {
            Unit.day -> 1.0
            Unit.week -> 7.0
            Unit.month -> 30.0
            Unit.year -> 365.0
        }

    fun normalized(): SubscriptionPeriod {
        return when (unit) {
            Unit.day -> if (value % 7 == 0) copy(value = value / 7, unit = Unit.week) else this
            Unit.month -> if (value % 12 == 0) copy(value = value / 12, unit = Unit.year) else this
            else -> this
        }
    }

    fun toPeriod(): Period {
        return when (unit) {
            Unit.day -> Period.ofDays(value)
            Unit.week -> Period.ofWeeks(value)
            Unit.month -> Period.ofMonths(value)
            Unit.year -> Period.ofYears(value)
        }
    }

    companion object {
        fun from(subscriptionPeriodString: String, currency: Currency): SubscriptionPeriod? {
            val period = try {
                Period.parse(subscriptionPeriodString)
            } catch (e: Exception) {
                return null
            }

            val totalDays = (period.toTotalMonths().toInt() * 30 + period.days).toInt()
            val weeks = (totalDays / 7).toInt()
            val days = (totalDays % 7).toInt()

            return when {
                period.years > 0 -> SubscriptionPeriod(period.years, Unit.year, currency)
                period.toTotalMonths() > 0 -> SubscriptionPeriod(
                    period.toTotalMonths().toInt(),
                    Unit.month,
                    currency
                )
                weeks > 0 -> SubscriptionPeriod(weeks, Unit.week, currency)
                days > 0 -> SubscriptionPeriod(days, Unit.day, currency)
                else -> null
            }?.normalized()
        }
    }

    val period: String
        get() {
            return when (unit) {
                Unit.day -> if (value == 7) "week" else "day"
                Unit.week -> "week"
                Unit.month -> when (value) {
                    2 -> "2 months"
                    3 -> "quarter"
                    6 -> "6 months"
                    else -> "month"
                }
                Unit.year -> "year"
            }
        }

    val periodly: String
        get() {
            val subscriptionPeriod = this
            return when (subscriptionPeriod.unit) {
                Unit.month -> when (subscriptionPeriod.value) {
                    2, 6 -> "every $period"
                    else -> "${period}ly"
                }
                else -> "${period}ly"
            }
        }

    val periodDays: Int
        get() {
            val subscriptionPeriod = this
            val numberOfUnits = subscriptionPeriod.value

            return when (subscriptionPeriod.unit) {
                SubscriptionPeriod.Unit.day -> 1 * numberOfUnits
                SubscriptionPeriod.Unit.month -> 30 * numberOfUnits  // Assumes 30 days in a month
                SubscriptionPeriod.Unit.week -> 7 * numberOfUnits   // Assumes 7 days in a week
                SubscriptionPeriod.Unit.year -> 365 * numberOfUnits // Assumes 365 days in a year
                else -> 0
            }
        }

    val periodWeeks: Int
        get() {
            val subscriptionPeriod = this
            val numberOfUnits = subscriptionPeriod.value

            return when (subscriptionPeriod.unit) {
                Unit.day -> (1 * numberOfUnits) / 7
                Unit.week -> numberOfUnits
                Unit.month -> 4 * numberOfUnits
                Unit.year -> 52 * numberOfUnits
            }
        }

    val periodMonths: Int
        get() {
            val subscriptionPeriod = this
            val numberOfUnits = subscriptionPeriod.value

            return when (subscriptionPeriod.unit) {
                SubscriptionPeriod.Unit.day -> numberOfUnits / 30       // Assumes 30 days in a month
                SubscriptionPeriod.Unit.month -> numberOfUnits
                SubscriptionPeriod.Unit.week -> numberOfUnits / 4      // Assumes 4 weeks in a month
                SubscriptionPeriod.Unit.year -> numberOfUnits * 12     // Assumes 12 months in a year
                else -> 0
            }
        }

    val periodYears: Int
        get() {
            val subscriptionPeriod = this
            val numberOfUnits = subscriptionPeriod.value

            return when (subscriptionPeriod.unit) {
                Unit.day -> numberOfUnits / 365
                Unit.week -> numberOfUnits / 52
                Unit.month -> numberOfUnits / 12
                Unit.year -> numberOfUnits
            }
        }

    fun dailyPrice(rawPrice: BigDecimal): String {
        if (rawPrice == BigDecimal.ZERO) {
            return "$0.00"
        }

        val numberFormatter = DecimalFormat.getCurrencyInstance()
        numberFormatter.currency = currency
        return numberFormatter.format(pricePerDay(rawPrice))
    }

    fun weeklyPrice(price: BigDecimal): String {
        if (price == BigDecimal.ZERO) {
            return "$0.00"
        }

        val numberFormatter = DecimalFormat.getCurrencyInstance()
        numberFormatter.currency = currency
        return numberFormatter.format(pricePerWeek(price))
    }

    fun monthlyPrice(price: BigDecimal): String {
        if (price == BigDecimal.ZERO) {
            return "$0.00"
        }

        val numberFormatter = DecimalFormat.getCurrencyInstance()
        numberFormatter.currency = currency
        return numberFormatter.format(pricePerMonth(price))
    }

    fun yearlyPrice(price: BigDecimal): String {
        if (price == BigDecimal.ZERO) {
            return "$0.00"
        }

        val numberFormatter = DecimalFormat.getCurrencyInstance()
        numberFormatter.currency = currency
        return numberFormatter.format(pricePerYear(price))
    }


    fun pricePerDay(price: BigDecimal): BigDecimal {
        when (unit) {
            Unit.day -> return _truncateDecimal(price.divide(BigDecimal(value)))
            Unit.week -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).divide(BigDecimal(  7), RoundingMode.HALF_EVEN))
            Unit.month -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).divide(BigDecimal( 30), RoundingMode.HALF_EVEN))
            Unit.year -> return  _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).divide( BigDecimal( 365), RoundingMode.HALF_EVEN))
        }
    }

    fun pricePerWeek(price: BigDecimal): BigDecimal {
        when (unit) {
            Unit.day -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).multiply(BigDecimal(  7)))
            Unit.week -> return _truncateDecimal(price.divide(BigDecimal(value)))
            Unit.month -> return _truncateDecimal(price.divide(BigDecimal(value * 4), RoundingMode.DOWN))
            Unit.year -> return _truncateDecimal(price.divide(BigDecimal(value * 52), RoundingMode.DOWN))
        }
    }

    fun pricePerMonth(price: BigDecimal): BigDecimal {
        when (unit) {
            Unit.day -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).multiply(BigDecimal( 30)))
            Unit.week -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).multiply(BigDecimal( 30.0 / 7.0)))
            Unit.month -> return _truncateDecimal(price.divide(BigDecimal(value)))
            Unit.year -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).divide(BigDecimal(12), RoundingMode.HALF_EVEN))
        }
    }

    fun pricePerYear(price: BigDecimal): BigDecimal {
        when (unit) {
            Unit.day -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).multiply(BigDecimal( 365)))
            Unit.week -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).multiply(BigDecimal( 365.0 / 7)))
            Unit.month -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN).multiply(BigDecimal(12)))
            Unit.year -> return _truncateDecimal(price.divide(BigDecimal(value), RoundingMode.HALF_EVEN))
        }
    }

    fun _truncateDecimal(decimal: BigDecimal, places: Int = currency.defaultFractionDigits ?: 2): BigDecimal {
        // First we need to divide by the main google product scaling factor


        val factor = BigDecimal.TEN.pow(places) // Create a factor of 10^decimalPlaces
        val result: BigDecimal =
            decimal.multiply(factor) // Multiply the original number by the factor
                .setScale(0, BigDecimal.ROUND_DOWN) // Set scale to 0 and ROUND_DOWN to truncate
                .divide(factor) // Divide back by the factor to get the truncated number
        return result
    }

}
