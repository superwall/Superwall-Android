package com.superwall.sdk.models.triggers

import ComputedPropertyRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class UnmatchedRule(
    val source: Source,
    val experimentId: String
) {
    enum class Source(val rawValue: String) {
        EXPRESSION("EXPRESSION"),
        OCCURRENCE("OCCURRENCE")
    }
}

data class MatchedItem(
    val rule: TriggerRule,
    val unsavedOccurrence: TriggerRuleOccurrence? = null
)

sealed class TriggerRuleOutcome {

    data class NoMatch(val unmatchedRule: UnmatchedRule) : TriggerRuleOutcome()
    data class Match(val matchedItem: MatchedItem) : TriggerRuleOutcome()

    companion object {
        fun noMatch(source: UnmatchedRule.Source, experimentId: String): TriggerRuleOutcome {
            return NoMatch(UnmatchedRule(source, experimentId))
        }

        fun match(rule: TriggerRule, unsavedOccurrence: TriggerRuleOccurrence? = null): TriggerRuleOutcome {
            return Match(MatchedItem(rule, unsavedOccurrence))
        }
    }

    override fun equals(other: Any?): Boolean {
        return when {
            this is NoMatch && other is NoMatch ->
                unmatchedRule.source == other.unmatchedRule.source &&
                        unmatchedRule.experimentId == other.unmatchedRule.experimentId
            this is Match && other is Match ->
                matchedItem.rule == other.matchedItem.rule &&
                        matchedItem.unsavedOccurrence == other.matchedItem.unsavedOccurrence
            else -> false
        }
    }
}

@Serializable
data class TriggerRule(
    var experimentId: String,
    var experimentGroupId: String,
    var variants: List<VariantOption>,
    val expression: String? = null,
    val expressionJs: String? = null,
    val occurrence: TriggerRuleOccurrence? = null,
    @SerialName("computed_properties")
    val computedPropertyRequests: List<ComputedPropertyRequest> = emptyList()
) {

    val experiment: RawExperiment
        get() {
            return RawExperiment(
                id = this.experimentId,
                groupId = this.experimentGroupId,
                variants = this.variants
            )
        }

    companion object {
        fun stub() = TriggerRule(
            experimentId = "1",
            experimentGroupId = "2",
            variants = listOf(
                VariantOption(
                    type = Experiment.Variant.VariantType.HOLDOUT,
                    id = "3",
                    percentage = 20,
                    paywallId = null
                )
            ),
            expression = null,
            expressionJs = null,
            occurrence = null,
            computedPropertyRequests = emptyList()
        )
    }
}