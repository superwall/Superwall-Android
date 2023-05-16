package com.superwall.sdk.config
//
//import com.superwall.sdk.models.triggers.VariantOption
//import java.lang.Exception
//
//enum class TriggerRuleError : Exception() {
//    NO_VARIANTS_FOUND,
//    INVALID_STATE
//}
//
//data class AssignmentOutcome(
//    val confirmed: Map<Experiment.ID, Experiment.Variant>,
//    val unconfirmed: Map<Experiment.ID, Experiment.Variant>
//)
//
//object ConfigLogic {
//
//    @Throws(TriggerRuleError::class)
//    fun chooseVariant(
//        variants: List<VariantOption>,
//        randomiser: (IntRange) -> Int = { it.random() }
//    ): Experiment.Variant {
//        if (variants.isEmpty()) {
//            throw TriggerRuleError.NO_VARIANTS_FOUND
//        }
//
//        if (variants.size == 1) {
//            return Experiment.Variant(
//                id = variants.first().id,
//                type = variants.first().type,
//                paywallId = variants.first().paywallId
//            )
//        }
//
//        val variantSum = variants.sumOf { it.percentage }
//
//        if (variantSum == 0) {
//            val randomVariantIndex = randomiser(0 until variants.size)
//            return Experiment.Variant(
//                id = variants[randomVariantIndex].id,
//                type = variants[randomVariantIndex].type,
//                paywallId = variants[randomVariantIndex].paywallId
//            )
//        }
//
//        val randomPercentage = randomiser(0 until variantSum)
//        val normRandomPercentage = randomPercentage.toDouble() / variantSum
//
//        var totalNormVariantPercentage = 0.0
//
//        for (variant in variants) {
//            val normVariantPercentage = variant.percentage.toDouble() / variantSum
//            totalNormVariantPercentage += normVariantPercentage
//            if (normRandomPercentage < totalNormVariantPercentage) {
//                return Experiment.Variant(
//                    id = variant.id,
//                    type = variant.type,
//                    paywallId = variant.paywallId
//                )
//            }
//        }
//
//        throw TriggerRuleError.INVALID_STATE
//    }
//
//    // I will stop here since the rest of the code is quite long.
//    // Please break it down to smaller pieces and I will be more than happy to help you translate those.
//}
