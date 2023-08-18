package com.superwall.sdk.store.abstractions.product

import java.math.BigDecimal
import java.math.RoundingMode

data class SubscriptionPeriod(val value: Int, val unit: Unit) {
    enum class Unit {
        day,
        week,
        month,
        year,
        unknown
    }

    val daysPerUnit: Double
        get() = when (unit) {
            Unit.day -> 1.0
            Unit.week -> 7.0
            Unit.month -> 30.0
            Unit.year -> 365.0
            Unit.unknown -> 0.0
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


        /*
        MIT License

        Copyright (c) 2018 RevenueCat, Inc.

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
        SOFTWARE.

         */

        // SW-2216
        // https://linear.app/superwall/issue/SW-2216/%5Bandroid%5D-%5Bv0%5D-figure-out-google-subscription-period-parsing
        fun from(subscriptionPeriodString: String): SubscriptionPeriod? {

            // Takes from https://stackoverflow.com/a/32045167
            val regex = "^P(?!\$)(\\d+(?:\\.\\d+)?Y)?(\\d+(?:\\.\\d+)?M)?(\\d+(?:\\.\\d+)?W)?(\\d+(?:\\.\\d+)?D)?\$"
                .toRegex()
                .matchEntire(subscriptionPeriodString)

            regex?.let { periodResult ->
                val toInt = fun(part: String): Int {
                    return part.dropLast(1).toIntOrNull() ?: 0
                }

                val (year, month, week, day) = periodResult.destructured

                val yearInt = toInt(year)
                val monthInt = toInt(month)
                val weekInt = toInt(week)
                val dayInt = toInt(day)

                return if (yearInt > 0) {
                    SubscriptionPeriod(yearInt, Unit.year).normalized()
                } else if (monthInt > 0) {
                    SubscriptionPeriod(monthInt, Unit.month).normalized()
                } else if (weekInt > 0) {
                    SubscriptionPeriod(weekInt, Unit.week).normalized()
                } else if (dayInt > 0) {
                    SubscriptionPeriod(dayInt, Unit.day).normalized()
                } else {
                    SubscriptionPeriod(0, Unit.unknown).normalized()
                }
            }

            return SubscriptionPeriod(0, Unit.unknown).normalized()
        }
    }

    fun pricePerDay( price: BigDecimal): BigDecimal {
        val periodsPerDay: BigDecimal = BigDecimal(value) * BigDecimal(daysPerUnit)
        return price.divide(periodsPerDay, 2, RoundingMode.DOWN)
    }

    fun pricePerWeek( price: BigDecimal): BigDecimal {
        val periodsPerWeek: BigDecimal = BigDecimal(value) * BigDecimal(daysPerUnit) / BigDecimal(7)
        return price.divide(periodsPerWeek, 2, RoundingMode.DOWN)
    }

    fun pricePerMonth( price: BigDecimal): BigDecimal {
        val periodsPerMonth: BigDecimal = BigDecimal(value) * BigDecimal(daysPerUnit) / BigDecimal(30)
        return price.divide(periodsPerMonth, 2, RoundingMode.DOWN)
    }

    fun pricePerYear( price: BigDecimal): BigDecimal {
        val periodsPerYear: BigDecimal = BigDecimal(value) * BigDecimal(daysPerUnit) / BigDecimal(365)
        return price.divide(periodsPerYear, 2, RoundingMode.DOWN)
    }
}
