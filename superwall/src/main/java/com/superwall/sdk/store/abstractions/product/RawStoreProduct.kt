package com.superwall.sdk.store.abstractions.product
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetails.PricingPhase
import com.android.billingclient.api.ProductDetails.SubscriptionOfferDetails
import com.android.billingclient.api.SkuDetails
import com.superwall.sdk.contrib.threeteen.AmountFormats
import kotlinx.serialization.Transient
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Period
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale

class RawStoreProduct(
    val underlyingProductDetails: ProductDetails,
    override val fullIdentifier: String,
    val basePlanId: String?,
    val offerType: OfferType?
) : StoreProductType {
    @Transient
    private val priceFormatterProvider = PriceFormatterProvider()

    private val priceFormatter: NumberFormat?
        get() = currencyCode?.let {
            priceFormatterProvider.priceFormatter(it)
        }

    override val productIdentifier: String
        get() = underlyingProductDetails.productId

    override val price: BigDecimal
        get() {
            underlyingProductDetails.oneTimePurchaseOfferDetails?.let { offerDetails ->
                return BigDecimal(offerDetails.priceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)
            }

            return basePriceForSelectedOffer()
        }

    override val localizedPrice: String
        get() {
            return priceFormatter?.format(price) ?: ""
        }

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
                }
            } ?: 0
        }

    override val periodDaysString: String
        get() = periodDays.toString()

    override val dailyPrice: String
        get() {
            val basePrice = basePriceForSelectedOffer()

            if (basePrice == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"

            val pricePerDay = subscriptionPeriod.pricePerDay(basePrice)

            return priceFormatter?.format(pricePerDay) ?: "n/a"
        }

    override val weeklyPrice: String
        get() {
            val basePrice = basePriceForSelectedOffer()

            if (basePrice == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"

            val pricePerWeek = subscriptionPeriod.pricePerWeek(basePrice)

            return priceFormatter?.format(pricePerWeek) ?: "n/a"
        }

    override val monthlyPrice: String
        get() {
            val basePrice = basePriceForSelectedOffer()

            if (basePrice == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"

            val pricePerMonth = subscriptionPeriod.pricePerMonth(basePrice)

            return priceFormatter?.format(pricePerMonth) ?: "n/a"
        }

    override val yearlyPrice: String
        get() {
            val basePrice = basePriceForSelectedOffer()

            if (basePrice == BigDecimal.ZERO) {
                return priceFormatter?.format(BigDecimal.ZERO) ?: "$0.00"
            }

            val subscriptionPeriod = this.subscriptionPeriod ?: return "n/a"
            val pricePerYear = subscriptionPeriod.pricePerYear(basePrice)

            return priceFormatter?.format(pricePerYear) ?: "n/a"
        }

    private fun basePriceForSelectedOffer(): BigDecimal {
        val selectedOffer = getSelectedOffer() ?: return BigDecimal.ZERO
        val pricingPhase = selectedOffer.pricingPhases.pricingPhaseList.last().priceAmountMicros
        return BigDecimal(pricingPhase).divide(BigDecimal(1_000_000), 2, RoundingMode.DOWN)
    }

        override val hasFreeTrial: Boolean
        get() {
            if (underlyingProductDetails.oneTimePurchaseOfferDetails != null) {
                return false
            }

            val selectedOffer = getSelectedOffer() ?: return false

            // Check for free trial phase in pricing phases, excluding the base pricing
            return selectedOffer.pricingPhases.pricingPhaseList
                .dropLast(1)
                .any { it.priceAmountMicros == 0L }
        }

    override val localizedTrialPeriodPrice: String
        get() = priceFormatter?.format(trialPeriodPrice) ?: "$0.00"

    override val trialPeriodPrice: BigDecimal
        get() {

            // Handle one-time purchase
            // TODO: Handle oneTimePurchaseOfferDetails correctly
            if (underlyingProductDetails.oneTimePurchaseOfferDetails != null) {
                return BigDecimal.ZERO
            }

            val selectedOffer = getSelectedOffer() ?: return BigDecimal.ZERO

            val pricingWithoutBase = selectedOffer.pricingPhases.pricingPhaseList.dropLast(1)
            if (pricingWithoutBase.isEmpty()) return BigDecimal.ZERO

            // Check for free trial phase
            val freeTrialPhase = pricingWithoutBase.firstOrNull { it.priceAmountMicros == 0L }
            if (freeTrialPhase != null) return BigDecimal.ZERO

            // Check for discounted phase
            val discountedPhase = pricingWithoutBase.firstOrNull { it.priceAmountMicros > 0 }
            return discountedPhase?.let {
                BigDecimal(it.priceAmountMicros).divide(BigDecimal(1_000_000), 2, RoundingMode.DOWN)
            } ?: BigDecimal.ZERO
        }

    private fun getSelectedOffer(): SubscriptionOfferDetails? {
        if (underlyingProductDetails.oneTimePurchaseOfferDetails != null) {
            return null
        }
        // Retrieve the subscription offer details from the product details
        val subscriptionOfferDetails = underlyingProductDetails.subscriptionOfferDetails ?: return null
        // If there's no base plan ID, return the first base plan we come across.
        if (basePlanId == null) {
            return subscriptionOfferDetails.firstOrNull { it.pricingPhases.pricingPhaseList.size == 1 }
        }

        // Get the offers that match the given base plan ID.
        val offersForBasePlan = subscriptionOfferDetails.filter { it.basePlanId == basePlanId }

        // In offers that match base plan, if there's only 1 pricing phase then this offer represents the base plan.
        val basePlan = offersForBasePlan.firstOrNull { it.pricingPhases.pricingPhaseList.size == 1 } ?: return null

        when (offerType) {
            is OfferType.Auto -> {
                // For automatically selecting an offer:
                //  - Filters out offers with "ignore-offer" tag
                //  - Uses offer with longest free trial or cheapest first phase
                //  - Falls back to use base plan
                val validOffers = offersForBasePlan
                    // Ignore base plan
                    .filter { it.pricingPhases.pricingPhaseList.size != 1 }
                    // Ignore those with a tag that contains "ignore-offer"
                    .filter { !it.offerTags.any { it.contains("-ignore-offer") }}
                return findLongestFreeTrial(validOffers) ?: findLowestNonFreeOffer(validOffers) ?: basePlan
            }
            is OfferType.Offer -> {
                // If an offer ID is given, return that one.
                return offersForBasePlan.firstOrNull { it.offerId == offerType.id }
            }
            else -> {
                // If no offer specified, return base plan.
                return basePlan
            }
        }
    }

    private fun findLongestFreeTrial(offers: List<SubscriptionOfferDetails>): SubscriptionOfferDetails? {
        return offers.mapNotNull { offer ->
            offer.pricingPhases.pricingPhaseList
                .dropLast(1)
                .firstOrNull {
                    it.priceAmountMicros == 0L
                }?.let { pricingPhase ->
                    val period = Period.parse(pricingPhase.billingPeriod)
                    val totalDays = period.toTotalMonths() * 30 + period.days
                    Pair(offer, totalDays)
                }
        }.maxByOrNull { it.second }?.first
    }

    private fun findLowestNonFreeOffer(offers: List<SubscriptionOfferDetails>): SubscriptionOfferDetails? {
        val hi = offers.mapNotNull { offer ->
            offer.pricingPhases.pricingPhaseList.dropLast(1).firstOrNull {
                it.priceAmountMicros > 0L
            }?.let { pricingPhase ->
                Pair(offer, pricingPhase.priceAmountMicros)
            }
        }.minByOrNull { it.second }?.first
        return hi
    }

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
            }
        }

    // TODO: Differs from iOS, using device locale here instead of product locale
    override val locale: String
        get() = Locale.getDefault().toString()

    // TODO: Differs from iOS, using device language code here instead of product language code
    override val languageCode: String?
        get() = Locale.getDefault().language

    override val currencyCode: String?
        get() {
            val selectedOffer = getSelectedOffer() ?: return null
            return selectedOffer.pricingPhases.pricingPhaseList.last().priceCurrencyCode
        }

    override val currencySymbol: String?
        get() {
            return currencyCode?.let { Currency.getInstance(it).symbol }
        }

    override val regionCode: String?
        get() = Locale.getDefault().country

    override val subscriptionPeriod: SubscriptionPeriod?
        get() {
            if (underlyingProductDetails.oneTimePurchaseOfferDetails != null) {
                return null
            }

            val selectedOffer = getSelectedOffer() ?: return null
            val baseBillingPeriod = selectedOffer.pricingPhases.pricingPhaseList.last().billingPeriod

            return try {
                SubscriptionPeriod.from(baseBillingPeriod)
            } catch (e: Exception) {
                null
            }
        }

    override fun trialPeriodPricePerUnit(unit: SubscriptionPeriod.Unit): String {
        val pricingPhase = getSelectedOfferPricingPhase() ?: return priceFormatter?.format(0) ?: "$0.00"

        if (pricingPhase.priceAmountMicros == 0L) {
            return priceFormatter?.format(0) ?: "$0.00"
        }

        val introPrice = pricePerUnit(
            unit = unit,
            pricingPhase = pricingPhase
        )

        return priceFormatter?.format(introPrice) ?: "$0.00"
    }

    private fun pricePerUnit(
        unit: SubscriptionPeriod.Unit,
        pricingPhase: PricingPhase
    ): BigDecimal {
        if (pricingPhase.priceAmountMicros == 0L) {
            return BigDecimal.ZERO
        } else {
            // The total cost that you'll pay
            val trialPeriodPrice = BigDecimal(pricingPhase.priceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)
            val introCost = trialPeriodPrice.multiply(BigDecimal(pricingPhase.billingCycleCount))

            // The number of total units normalized to the unit you want.
            val billingPeriod = getSelectedOfferPricingPhase()?.billingPeriod

            // Attempt to create a SubscriptionPeriod from billingPeriod.
            // Return null if there's an exception or if billingPeriod is null.
            val trialSubscriptionPeriod = try {
                billingPeriod?.let { SubscriptionPeriod.from(it) }
            } catch (e: Exception) {
                null
            }
            // TODO: THE PERIODSPERUNIT IS WRONG
            val periodPerUnit2 = periodsPerUnit(unit)
            val introPeriods = periodsPerUnit(unit).multiply(BigDecimal(pricingPhase.billingCycleCount))
                .multiply(BigDecimal(trialSubscriptionPeriod?.value ?: 0))
            println(unit.toString())
            println(introPeriods)
            println(periodPerUnit2)
            println(trialSubscriptionPeriod?.value)
            println(pricingPhase.billingCycleCount)
            val introPayment: BigDecimal
            if (introPeriods < BigDecimal.ONE) {
                // If less than 1, it means the intro period doesn't exceed a full unit.
                introPayment = introCost
            } else {
                // Otherwise, divide the total cost by the normalized intro periods.
                introPayment = introCost.divide(introPeriods, 2, RoundingMode.DOWN)
            }

            return introPayment
        }
    }

    private fun periodsPerUnit(unit: SubscriptionPeriod.Unit): BigDecimal {
        return when (unit) {
            SubscriptionPeriod.Unit.day -> {
                when (trialSubscriptionPeriod?.unit) {
                    SubscriptionPeriod.Unit.day -> BigDecimal(1)
                    SubscriptionPeriod.Unit.week -> BigDecimal(7)
                    SubscriptionPeriod.Unit.month -> BigDecimal(30)
                    SubscriptionPeriod.Unit.year -> BigDecimal(365)
                    else -> BigDecimal.ZERO
                }
            }
            SubscriptionPeriod.Unit.week -> {
                when (trialSubscriptionPeriod?.unit) {
                    SubscriptionPeriod.Unit.day -> BigDecimal(1).divide(BigDecimal(7), 6, RoundingMode.DOWN)
                    SubscriptionPeriod.Unit.week -> BigDecimal(1)
                    SubscriptionPeriod.Unit.month -> BigDecimal(4)
                    SubscriptionPeriod.Unit.year -> BigDecimal(52)
                    else -> BigDecimal.ZERO
                }
            }
            SubscriptionPeriod.Unit.month -> {
                when (trialSubscriptionPeriod?.unit) {
                    SubscriptionPeriod.Unit.day -> BigDecimal(1).divide(BigDecimal(30), 6, RoundingMode.DOWN)
                    SubscriptionPeriod.Unit.week -> BigDecimal(1).divide(BigDecimal(4), 6, RoundingMode.DOWN)
                    SubscriptionPeriod.Unit.month -> BigDecimal(1)
                    SubscriptionPeriod.Unit.year -> BigDecimal(12)
                    else -> BigDecimal.ZERO
                }
            }
            SubscriptionPeriod.Unit.year -> {
                when (trialSubscriptionPeriod?.unit) {
                    SubscriptionPeriod.Unit.day -> BigDecimal(1).divide(BigDecimal(365), 6, RoundingMode.DOWN)
                    SubscriptionPeriod.Unit.week -> BigDecimal(1).divide(BigDecimal(52), 6, RoundingMode.DOWN)
                    SubscriptionPeriod.Unit.month -> BigDecimal(1).divide(BigDecimal(12), 6, RoundingMode.DOWN)
                    SubscriptionPeriod.Unit.year -> BigDecimal.ONE
                    else -> BigDecimal.ZERO
                }
            }
        }
    }


    private fun getSelectedOfferPricingPhase(): PricingPhase? {
        // Get the selected offer; return null if it's null.
        val selectedOffer = getSelectedOffer() ?: return null

        // Find the first free trial phase or discounted phase.
        return selectedOffer.pricingPhases.pricingPhaseList
            .firstOrNull { it.priceAmountMicros == 0L }
            ?: selectedOffer.pricingPhases.pricingPhaseList
                .dropLast(1)
                .firstOrNull { it.priceAmountMicros != 0L }
    }

    val trialSubscriptionPeriod: SubscriptionPeriod?
        get() {
            // If oneTimePurchaseOfferDetails is not null, return null for trial subscription period.
            if (underlyingProductDetails.oneTimePurchaseOfferDetails != null) {
                return null
            }

            val billingPeriod = getSelectedOfferPricingPhase()?.billingPeriod

            // Attempt to create a SubscriptionPeriod from billingPeriod.
            // Return null if there's an exception or if billingPeriod is null.
            return try {
                billingPeriod?.let { SubscriptionPeriod.from(it) }
            } catch (e: Exception) {
                null
            }
        }
}

val SkuDetails.priceValue: BigDecimal
    get() = BigDecimal(priceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)

val SkuDetails.introductoryPriceValue: BigDecimal
    get() = BigDecimal(introductoryPriceAmountMicros).divide(BigDecimal(1_000_000), 6, RoundingMode.DOWN)
