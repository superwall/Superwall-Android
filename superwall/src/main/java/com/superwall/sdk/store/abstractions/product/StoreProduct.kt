package com.superwall.sdk.store.abstractions.product

import com.superwall.sdk.models.product.Offer
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.util.*

/**
 * Selection type for base plan/purchase option.
 * When [Auto], the SDK will automatically select the best base plan.
 * When [Specific], the SDK will use the exact base plan ID provided.
 */
@Serializable
sealed class BasePlanType {
    /** Auto-select the best base plan */
    object Auto : BasePlanType()

    /** Use a specific base plan by ID */
    data class Specific(
        val id: String,
    ) : BasePlanType()

    /** Returns the ID if this is a Specific selection, null otherwise */
    val specificId: String?
        get() = (this as? Specific)?.id

    companion object {
        /** Parse a string value - "sw-auto"/null/empty means Auto, otherwise Specific */
        fun from(value: String?): BasePlanType =
            when {
                value.isNullOrEmpty() || value == "sw-auto" -> Auto
                else -> Specific(value)
            }
    }
}

/**
 * Selection type for offer selection.
 * When [Auto], the SDK will automatically select the best offer.
 * When [None], no offer will be selected (use base plan/option only).
 * When [Specific], the SDK will use the exact offer ID provided.
 */
@Serializable
sealed class OfferType {
    /** Auto-select the best offer (e.g., longest free trial or cheapest) */
    object Auto : OfferType()

    /** Don't select any offer - use base plan/option only */
    object None : OfferType()

    /** Use a specific offer by ID */
    data class Specific(
        val id: String,
    ) : OfferType()

    /** Returns the ID if this is a Specific selection, null otherwise */
    val specificId: String?
        get() = (this as? Specific)?.id

    /** Converts to the Offer type used in ProductItem */
    fun toOffer(): Offer =
        when (this) {
            is Auto -> Offer.Automatic()
            is None -> Offer.NoOffer
            is Specific -> Offer.Specified(offerIdentifier = id)
        }

    companion object {
        /** Parse a string value - "sw-auto"/null/empty means Auto, "sw-none" means None, otherwise Specific */
        fun from(value: String?): OfferType =
            when {
                value.isNullOrEmpty() || value == "sw-auto" -> Auto
                value == "sw-none" -> None
                else -> Specific(value)
            }
    }
}

class StoreProduct(
    val rawStoreProduct: RawStoreProduct,
) : StoreProductType {
    override val fullIdentifier: String
        get() = rawStoreProduct.fullIdentifier

    override val productIdentifier: String
        get() = rawStoreProduct.productIdentifier

    override val price: BigDecimal
        get() = rawStoreProduct.price

    override val productType: String
        get() = rawStoreProduct.productType

    override val localizedPrice: String
        get() = rawStoreProduct.localizedPrice

    override val localizedSubscriptionPeriod: String
        get() = rawStoreProduct.localizedSubscriptionPeriod

    override val period: String
        get() = rawStoreProduct.period

    override val periodly: String
        get() = rawStoreProduct.periodly

    override val periodWeeks: Int
        get() = rawStoreProduct.periodWeeks

    override val periodWeeksString: String
        get() = rawStoreProduct.periodWeeksString

    override val periodMonths: Int
        get() = rawStoreProduct.periodMonths

    override val periodMonthsString: String
        get() = rawStoreProduct.periodMonthsString

    override val periodYears: Int
        get() = rawStoreProduct.periodYears

    override val periodYearsString: String
        get() = rawStoreProduct.periodYearsString

    override val periodDays: Int
        get() = rawStoreProduct.periodDays

    override val periodDaysString: String
        get() = rawStoreProduct.periodDaysString

    override val dailyPrice: String
        get() = rawStoreProduct.dailyPrice

    override val weeklyPrice: String
        get() = rawStoreProduct.weeklyPrice

    override val monthlyPrice: String
        get() = rawStoreProduct.monthlyPrice

    override val yearlyPrice: String
        get() = rawStoreProduct.yearlyPrice

    override val hasFreeTrial: Boolean
        get() = rawStoreProduct.hasFreeTrial

    override val localizedTrialPeriodPrice: String
        get() = rawStoreProduct.localizedTrialPeriodPrice

    override val trialPeriodPrice: BigDecimal
        get() = rawStoreProduct.trialPeriodPrice

    override val trialPeriodEndDate: Date?
        get() = rawStoreProduct.trialPeriodEndDate

    override val trialPeriodEndDateString: String
        get() = rawStoreProduct.trialPeriodEndDateString

    override val trialPeriodDays: Int
        get() = rawStoreProduct.trialPeriodDays

    override val trialPeriodDaysString: String
        get() = rawStoreProduct.trialPeriodDaysString

    override val trialPeriodWeeks: Int
        get() = rawStoreProduct.trialPeriodWeeks

    override val trialPeriodWeeksString: String
        get() = rawStoreProduct.trialPeriodWeeksString

    override val trialPeriodMonths: Int
        get() = rawStoreProduct.trialPeriodMonths

    override val trialPeriodMonthsString: String
        get() = rawStoreProduct.trialPeriodMonthsString

    override val trialPeriodYears: Int
        get() = rawStoreProduct.trialPeriodYears

    override val trialPeriodYearsString: String
        get() = rawStoreProduct.trialPeriodYearsString

    override val trialPeriodText: String
        get() = rawStoreProduct.trialPeriodText

    override val locale: String
        get() = rawStoreProduct.locale

    override val languageCode: String?
        get() = rawStoreProduct.languageCode

    override val currencyCode: String?
        get() = rawStoreProduct.currencyCode

    override val currencySymbol: String?
        get() = rawStoreProduct.currencySymbol

    override val regionCode: String?
        get() = rawStoreProduct.regionCode

    override val subscriptionPeriod: SubscriptionPeriod?
        get() = rawStoreProduct.subscriptionPeriod

    override fun trialPeriodPricePerUnit(unit: SubscriptionPeriod.Unit): String = rawStoreProduct.trialPeriodPricePerUnit(unit)
}
