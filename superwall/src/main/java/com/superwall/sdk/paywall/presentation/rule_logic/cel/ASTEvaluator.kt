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
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.toCELExpression
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.toPassableValue
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

internal fun CELExpression.rewriteASTWith(ctx: PlatformOperations) =
    mapAll { it ->
        when (it) {
            is CELExpression.FunctionCall -> {
                val fn = it.function
                // If it is platform.something(args), replace the expression with the
                // function invocation result
                if (fn is CELExpression.Ident &&
                    it.receiver is CELExpression.Ident &&
                    it.receiver.name == "platform"
                ) {
                    val methodName = fn.name
                    val methodArgs = it.arguments
                    val res = ctx.invoke(methodName, methodArgs.map { it.toPassableValue() })
                    res.toCELExpression()
                } else {
                    it
                }
            }

            // If it is platform.something, replace the expression with the invocation result
            is CELExpression.Member -> {
                val isPurePlatformString = it.expr is CELExpression.Ident && it.expr.name == "platform"
                val attributeExists = it.member is CELMember.Attribute
                if (isPurePlatformString &&
                    attributeExists
                ) {
                    val member = it.member as CELMember.Attribute
                    val res = ctx.invoke(member.name, emptyList())
                    res.toCELExpression()
                } else {
                    it
                }
            }
            else -> it
        }
    }
