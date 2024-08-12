package com.superwall.sdk.paywall.presentation.rule_logic

import com.superwall.sdk.paywall.presentation.rule_logic.cel.ASTEvaluator
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast.CELAtom
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast.CELExpression
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast.CELMember
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.ast.CELRelationOp
import com.superwall.sdk.paywall.presentation.rule_logic.cel.rewriteASTWith
import org.junit.Assert.assertEquals
import org.junit.Test

class ASTRewriteTest {
    @Test
    fun rewrite_ast_platform_references() {
        // Arrange
        val expression: CELExpression =
            CELExpression.And(
                CELExpression.Relation(
                    CELExpression.FunctionCall(
                        CELExpression.Member(
                            CELExpression.Ident("platform"),
                            CELMember.Attribute("myMethod"),
                        ),
                        null,
                        listOf(CELExpression.Atom(CELAtom.String("name"))),
                    ),
                    CELRelationOp.Equals,
                    CELExpression.Member(
                        CELExpression.Ident("platform"),
                        CELMember.Attribute("name"),
                    ),
                ),
                CELExpression.Relation(
                    CELExpression.Member(
                        CELExpression.Ident("user"),
                        CELMember.Attribute("test"),
                    ),
                    CELRelationOp.Equals,
                    CELExpression.Atom(CELAtom.Int(1)),
                ),
            )

        // Act
        val transformedExpression =
            expression.rewriteASTWith(
                object : ASTEvaluator.PlatformOperations {
                    override fun invoke(
                        name: String,
                        args: List<PassableValue>,
                    ): PassableValue = PassableValue.StringValue("mapped:$name:${args.first()}")
                },
            )

        // Assert
        val expectedExpression =
            CELExpression.And(
                CELExpression.Relation(
                    CELExpression.Atom(CELAtom.String("mapped")),
                    CELRelationOp.Equals,
                    CELExpression.Atom(CELAtom.String("mapped")),
                ),
                CELExpression.Relation(
                    CELExpression.Member(
                        CELExpression.Ident("user"),
                        CELMember.Attribute("test"),
                    ),
                    CELRelationOp.Equals,
                    CELExpression.Atom(CELAtom.Int(1)),
                ),
            )

        assertEquals(expectedExpression, transformedExpression)
    }
}
