package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.config.ComputedPropertyRequest.ComputedPropertyRequestType
import com.superwall.sdk.models.triggers.RawInterval
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.paywall.presentation.rule_logic.cel.InPeriod.Period.*
import com.superwall.sdk.paywall.presentation.rule_logic.cel.SuperscriptExposedFunction.Names.*
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.storage.core_data.CoreDataManager

sealed class SuperscriptExposedFunction {
    enum class Names(
        val rawName: String,
    ) {
        MINUTES_SINCE("minutesSince"),
        HOURS_SINCE("hoursSince"),
        DAYS_SINCE("daysSince"),
        MONTHS_SINCE("monthsSince"),
        PLACEMENTS_IN_HOUR("placementsInHour"),
        PLACEMENTS_IN_DAY("placementsInDay"),
        PLACEMENTS_IN_WEEK("placementsInWeek"),
        PLACEMENTS_IN_MONTH("placementsInMonth"),
        PLACEMENTS_SINCE_INSTALL("placementsSinceInstall"),
    }

    class MinutesSince(
        override val event: String,
    ) : SuperscriptExposedFunction(),
        TimeSince {
        override val propertyRequest = ComputedPropertyRequestType.MINUTES_SINCE
    }

    class HoursSince(
        override val event: String,
    ) : SuperscriptExposedFunction(),
        TimeSince {
        override val propertyRequest = ComputedPropertyRequestType.HOURS_SINCE
    }

    class DaysSince(
        override val event: String,
    ) : SuperscriptExposedFunction(),
        TimeSince {
        override val propertyRequest = ComputedPropertyRequestType.DAYS_SINCE
    }

    class MonthsSince(
        override val event: String,
    ) : SuperscriptExposedFunction(),
        TimeSince {
        override val propertyRequest = ComputedPropertyRequestType.MONTHS_SINCE
    }

    class PlacementCount(
        val event: String,
        val period: InPeriod.Period,
    ) : SuperscriptExposedFunction() {
        suspend operator fun invoke(storage: CoreDataManager): Int? =
            storage.countTriggerRuleOccurrences(
                TriggerRuleOccurrence(
                    event,
                    Int.MAX_VALUE,
                    rawInterval =
                        RawInterval(
                            type =
                                if (period == INSTALL) {
                                    RawInterval.IntervalType.INFINITY
                                } else {
                                    RawInterval.IntervalType.MINUTES
                                },
                            minutes =
                                when (period) {
                                    HOUR -> 60
                                    DAY -> 60 * 24
                                    WEEK -> 60 * 24 * 7
                                    MONTH -> 60 * 24 * 7 * 30
                                    else -> null
                                },
                        ),
                ),
            )
    }

    companion object {
        fun from(
            name: String,
            args: List<PassableValue>,
        ) = when (name) {
            MINUTES_SINCE.rawName -> MinutesSince((args.first() as PassableValue.StringValue).value)
            HOURS_SINCE.rawName -> HoursSince((args.first() as PassableValue.StringValue).value)
            DAYS_SINCE.rawName -> DaysSince((args.first() as PassableValue.StringValue).value)
            MONTHS_SINCE.rawName -> MonthsSince((args.first() as PassableValue.StringValue).value)
            PLACEMENTS_IN_HOUR.rawName ->
                PlacementCount(
                    event = (args.first() as PassableValue.StringValue).value,
                    HOUR,
                )
            PLACEMENTS_IN_DAY.rawName ->
                PlacementCount(
                    event = (args.first() as PassableValue.StringValue).value,
                    DAY,
                )
            PLACEMENTS_IN_WEEK.rawName ->
                PlacementCount(
                    event = (args.first() as PassableValue.StringValue).value,
                    WEEK,
                )
            PLACEMENTS_IN_MONTH.rawName ->
                PlacementCount(
                    event = (args.first() as PassableValue.StringValue).value,
                    MONTH,
                )
            PLACEMENTS_SINCE_INSTALL.rawName,
            ->
                PlacementCount(
                    event = (args.first() as PassableValue.StringValue).value,
                    period = INSTALL,
                )

            else -> null
        }
    }
}

sealed interface TimeSince {
    val event: String
    val propertyRequest: ComputedPropertyRequestType

    suspend operator fun invoke(storage: CoreDataManager): Int? =
        storage.getComputedPropertySinceEvent(
            null,
            ComputedPropertyRequest(
                propertyRequest,
                event,
            ),
        )
}

sealed interface InPeriod {
    enum class Period {
        HOUR,
        DAY,
        WEEK,
        MONTH,
        INSTALL,
    }

    val event: String
}
