package com.superwall.sdk.models.triggers

import ComputedPropertyRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TriggerRule(
    var experimentId: String,
    var experimentGroupId: String,
    var variants: List<VariantOption>,
    val expression: String? = null,
    val expressionJs: String? = null,
    val occurrence: TriggerRuleOccurrence? = null,
    @SerialName("computedProperties")
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