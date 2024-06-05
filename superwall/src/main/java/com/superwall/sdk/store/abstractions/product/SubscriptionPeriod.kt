package com.superwall.sdk.store.abstractions.product

import org.threeten.bp.Period
import java.math.BigDecimal
import java.math.RoundingMode

data class SubscriptionPeriod(
    val value: Int,
    val unit: Unit,
) {
    enum class Unit {
        day,
        week,
        month,
        year,
    }

    val daysPerUnit: Double
        get() =
            when (unit) {
                Unit.day -> 1.0
                Unit.week -> 7.0
                Unit.month -> 30.0
                Unit.year -> 365.0
            }

    fun normalized(): SubscriptionPeriod =
        when (unit) {
            Unit.day -> if (value % 7 == 0) copy(value = value / 7, unit = Unit.week) else this
            Unit.month -> if (value % 12 == 0) copy(value = value / 12, unit = Unit.year) else this
            else -> this
        }

    fun toPeriod(): Period =
        when (unit) {
            Unit.day -> Period.ofDays(value)
            Unit.week -> Period.ofWeeks(value)
            Unit.month -> Period.ofMonths(value)
            Unit.year -> Period.ofYears(value)
        }

    companion object {
        fun from(subscriptionPeriodString: String): SubscriptionPeriod? {
            val period =
                try {
                    Period.parse(subscriptionPeriodString)
                } catch (e: Throwable) {
                    return null
                }

            val totalDays = (period.toTotalMonths().toInt() * 30 + period.days).toInt()
            val weeks = (totalDays / 7).toInt()
            val days = (totalDays % 7).toInt()

            return when {
                period.years > 0 -> SubscriptionPeriod(period.years, Unit.year)
                period.toTotalMonths() > 0 ->
                    SubscriptionPeriod(
                        period.toTotalMonths().toInt(),
                        Unit.month,
                    )
                weeks > 0 -> SubscriptionPeriod(weeks, Unit.week)
                days > 0 -> SubscriptionPeriod(days, Unit.day)
                else -> null
            }?.normalized()
        }
    }

    private val roundingMode = RoundingMode.DOWN
    private val calculationScale = 7
    private val outputScale = 2

    fun pricePerDay(price: BigDecimal): BigDecimal {
        val periodsPerDay: BigDecimal =
            when (this.unit) {
                SubscriptionPeriod.Unit.day -> BigDecimal.ONE
                SubscriptionPeriod.Unit.week -> BigDecimal(7)
                SubscriptionPeriod.Unit.month -> BigDecimal(30)
                SubscriptionPeriod.Unit.year -> BigDecimal(365)
            } * BigDecimal(this.value)

        return price.divide(periodsPerDay, outputScale, roundingMode)
    }

    fun pricePerWeek(price: BigDecimal): BigDecimal {
        val periodsPerWeek: BigDecimal =
            when (this.unit) {
                SubscriptionPeriod.Unit.day -> BigDecimal.ONE.divide(BigDecimal(7))
                SubscriptionPeriod.Unit.week -> BigDecimal.ONE
                SubscriptionPeriod.Unit.month -> BigDecimal(4)
                SubscriptionPeriod.Unit.year -> BigDecimal(52)
            } * BigDecimal(this.value)

        return price.divide(periodsPerWeek, outputScale, roundingMode)
    }

    fun pricePerMonth(price: BigDecimal): BigDecimal {
        val periodsPerMonth: BigDecimal =
            when (this.unit) {
                SubscriptionPeriod.Unit.day -> BigDecimal.ONE.divide(BigDecimal(30), calculationScale, roundingMode)
                SubscriptionPeriod.Unit.week -> BigDecimal.ONE.divide(BigDecimal(30.0 / 7.0), calculationScale, roundingMode)
                SubscriptionPeriod.Unit.month -> BigDecimal.ONE
                SubscriptionPeriod.Unit.year -> BigDecimal(12)
            } * BigDecimal(this.value)

        return price.divide(periodsPerMonth, outputScale, roundingMode)
    }

    fun pricePerYear(price: BigDecimal): BigDecimal {
        val periodsPerYear: BigDecimal =
            when (this.unit) {
                SubscriptionPeriod.Unit.day -> BigDecimal.ONE.divide(BigDecimal(365), calculationScale, roundingMode)
                SubscriptionPeriod.Unit.week -> BigDecimal.ONE.divide(BigDecimal(52), calculationScale, roundingMode)
                SubscriptionPeriod.Unit.month -> BigDecimal.ONE.divide(BigDecimal(12), calculationScale, roundingMode)
                SubscriptionPeriod.Unit.year -> BigDecimal.ONE
            }.multiply(BigDecimal(this.value))

        return price.divide(periodsPerYear, outputScale, roundingMode)
    }
}
