package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.paywall.presentation.rule_logic.cel.ASTEvaluator.PlatformOperations
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast.CELAtom
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast.CELExpression
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast.CELMember
import com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator.ExpressionEvaluating
import com.superwall.sdk.paywall.presentation.rule_logic.tryToMatchOccurrence
import com.superwall.sdk.storage.Storage
import kotlinx.serialization.json.Json

class ASTEvaluator(
    private val json: Json,
    private val storage: Storage,
    private val factory: RuleAttributesFactory,
) : ExpressionEvaluating {
    override suspend fun evaluateExpression(
        rule: TriggerRule,
        eventData: EventData?,
    ): TriggerRuleOutcome {
        TriggerRuleOutcome.noMatch(
            UnmatchedRule.Source.EXPRESSION,
            rule.experiment.id,
        )
        val factory = factory.makeRuleAttributes(eventData, rule.computedPropertyRequests)
        val toPassableValue = factory.toPassableValue()

        val expression: CELExpression =
            CELExpression.And(
                CELExpression.Atom(
                    CELAtom.Bool(true),
                ),
                CELExpression.Atom(
                    CELAtom.Bool(true),
                ),
            )

        val result = ""
        return if (result == "true") {
            rule.tryToMatchOccurrence(storage, true)
        } else {
            TriggerRuleOutcome.noMatch(
                UnmatchedRule.Source.EXPRESSION,
                rule.experiment.id,
            )
        }
    }

    internal interface PlatformOperations {
        fun invoke(
            name: String,
            args: List<PassableValue>,
        ): PassableValue
    }
}

internal fun CELExpression.rewriteASTWith(ctx: PlatformOperations) {
    mapAll { it ->
        when (it) {
            is CELExpression.FunctionCall -> {
                val fn = it.function
                if (fn is CELExpression.Member &&
                    fn.expr is CELExpression.Ident &&
                    fn.expr.name == "platform" &&
                    fn.member is CELMember.Attribute
                ) {
                    val methodName = fn.member.name
                    val methodArgs = it.arguments
                    CELExpression.Atom(CELAtom.String("mapped"))
                } else {
                    it
                }
            }

            else -> it
        }
    }
}
