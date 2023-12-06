package com.superwall.sdk.store.abstractions.product

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*

class PriceFormatterProvider {
    private var cachedPriceFormatter: NumberFormat? = null

    fun priceFormatter(currencyCode: String): NumberFormat {
        val currency = currency(currencyCode)
        return cachedPriceFormatter?.takeIf { it.currency == currency }
            ?: makePriceFormatter(currencyCode).also {
                cachedPriceFormatter = it
            }
    }

    private fun makePriceFormatter(currencyCode: String): NumberFormat {
        return DecimalFormat.getCurrencyInstance().also {
            it.currencyCode = currencyCode
        }
    }

    private fun currency(currencyCode: String): Currency? {
        return try {
            Currency.getInstance(currencyCode)
        } catch (e: Exception) {
            null
        }
    }
}

var NumberFormat.currencyCode: String?
    get() = this.currency?.currencyCode
    set(value) {
        this.currency = try {
            if (value != null) Currency.getInstance(value) else null
        } catch (e: Exception) {
            null
        }
    }
