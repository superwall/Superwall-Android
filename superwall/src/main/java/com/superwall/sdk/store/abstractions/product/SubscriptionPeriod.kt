package com.superwall.sdk.store.abstractions.product

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Period
import java.time.temporal.ChronoUnit

data class SubscriptionPeriod(val value: Int, val unit: Unit) {
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
        fun from(subscriptionPeriodString: String): SubscriptionPeriod? {
            val period = try {
                Period.parse(subscriptionPeriodString)
            } catch (e: Exception) {
                return null
            }

            val totalDays = (period.toTotalMonths().toInt() * 30 + period.days).toInt()
            val weeks = (totalDays / 7).toInt()
            val days = (totalDays % 7).toInt()

            return when {
                period.years > 0 -> SubscriptionPeriod(period.years, Unit.year)
                period.toTotalMonths() > 0 -> SubscriptionPeriod(
                    period.toTotalMonths().toInt(),
                    Unit.month
                )
                weeks > 0 -> SubscriptionPeriod(weeks, Unit.week)
                days > 0 -> SubscriptionPeriod(days, Unit.day)
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
    fun pricePerDay(price: BigDecimal): BigDecimal {
        val periodsPerDay: BigDecimal = BigDecimal(value) * BigDecimal(daysPerUnit)
        return price.divide(periodsPerDay, 2, RoundingMode.DOWN)
    }

    fun pricePerWeek(price: BigDecimal): BigDecimal {
        val periodsPerWeek: BigDecimal = BigDecimal(value) * BigDecimal(daysPerUnit) / BigDecimal(7)
        return price.divide(periodsPerWeek, 2, RoundingMode.DOWN)
    }

    fun pricePerMonth(price: BigDecimal): BigDecimal {
        val periodsPerMonth: BigDecimal =
            BigDecimal(value) * BigDecimal(daysPerUnit) / BigDecimal(30)
        return price.divide(periodsPerMonth, 2, RoundingMode.DOWN)
    }

    fun pricePerYear(price: BigDecimal): BigDecimal {
        val periodsPerYear: BigDecimal =
            BigDecimal(value) * BigDecimal(daysPerUnit) / BigDecimal(365)
        return price.divide(periodsPerYear, 2, RoundingMode.DOWN)
    }
}
