package com.superwall.sdk.store.abstractions.product

import java.math.BigDecimal
import java.util.*

// Define a typealias for List as there is no exact equivalent for Array in Kotlin
//typealias StoreProductDiscountArray = List<StoreProductDiscount>

interface StoreProductType {
    val productIdentifier: String
    val price: BigDecimal
    val subscriptionGroupIdentifier: String?
//    val swProductTemplateVariablesJson: JsonObject
//    val swProduct: SWProduct
    val localizedPrice: String
    val localizedSubscriptionPeriod: String
    val period: String
    val periodly: String
    val periodWeeks: Int
    val periodWeeksString: String
    val periodMonths: Int
    val periodMonthsString: String
    val periodYears: Int
    val periodYearsString: String
    val periodDays: Int
    val periodDaysString: String
    val dailyPrice: String
    val weeklyPrice: String
    val monthlyPrice: String
    val yearlyPrice: String
    val hasFreeTrial: Boolean
    val trialPeriodEndDate: Date?
    val trialPeriodEndDateString: String
    val trialPeriodDays: Int
    val trialPeriodDaysString: String
    val trialPeriodWeeks: Int
    val trialPeriodWeeksString: String
    val trialPeriodMonths: Int
    val trialPeriodMonthsString: String
    val trialPeriodYears: Int
    val trialPeriodYearsString: String
    val trialPeriodText: String
    val locale: String
    val languageCode: String?
    val currencyCode: String?
    val currencySymbol: String?
    val regionCode: String?
    // Not relevant for Android
//    val isFamilyShareable: Boolean

    /// The period details for products that are subscriptions.
    /// - Returns: `nil` if the product is not a subscription.
    val subscriptionPeriod: SubscriptionPeriod?
//    val introductoryDiscount: StoreProductDiscount?
//    val discounts: StoreProductDiscountArray
}
