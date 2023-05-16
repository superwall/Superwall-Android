package com.superwall.sdk.models.triggers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TriggerRule(
    @Transient var experiment: RawExperiment? = null,
    val experimentId: String,
    val experimentGroupId: String,
    val variants: List<VariantOption>,

    val expression: String? = null,
    val expressionJs: String? = null,
    val occurrence: TriggerRuleOccurrence? = null
) {

    init {
        experiment = RawExperiment(
            id = experimentId,
            groupId = experimentGroupId,
            variants = variants
        )
    }
}
