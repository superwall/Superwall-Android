package com.superwall.sdk.store.abstractions.product

import java.math.BigDecimal
import java.math.RoundingMode

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

    // TODO: I don't think SkuDetails.SubscriptionPeriod is valid
    companion object {
        fun from(subscriptionPeriodString: String): SubscriptionPeriod? {

            // SW-2216
            // https://linear.app/superwall/issue/SW-2216/%5Bandroid%5D-%5Bv0%5D-figure-out-google-subscription-period-parsing
            // hard coding a month renewal period
            return SubscriptionPeriod(1, Unit.month).normalized()
//            val unit = when (sk1SubscriptionPeriod.unit) {
//                0 -> Unit.day
//                1 -> Unit.week
//                2 -> Unit.month
//                3 -> Unit.year
//                else -> return null
//            }
//
//            return SubscriptionPeriod(sk1SubscriptionPeriod.numberOfUnits, unit)
//                .normalized()

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
