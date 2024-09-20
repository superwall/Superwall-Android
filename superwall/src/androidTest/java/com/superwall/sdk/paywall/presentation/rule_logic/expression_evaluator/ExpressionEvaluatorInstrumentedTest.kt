package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import ComputedPropertyRequest
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.MatchedItem
import com.superwall.sdk.models.triggers.TriggerPreloadBehavior
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.models.triggers.VariantOption
import com.superwall.sdk.paywall.presentation.rule_logic.javascript.SandboxJavascriptEvaluator
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.StorageMock
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date

class RuleAttributeFactoryBuilder : RuleAttributesFactory {
    override suspend fun makeRuleAttributes(
        event: EventData?,
        computedPropertyRequests: List<ComputedPropertyRequest>,
    ): Map<String, Any> =
        mapOf(
            "user" to
                mapOf(
                    "id" to "123",
                    "email" to "test@gmail.com",
                ),
        )
}

class ExpressionEvaluatorInstrumentedTest {
    var sandbox: JavaScriptSandbox? = null

    @Before
    fun setup() =
        runBlocking {
            sandbox = JavaScriptSandbox.createConnectedInstanceAsync(InstrumentationRegistry.getInstrumentation().targetContext).await()
        }

    @After
    fun tearDown() =
        runBlocking {
            sandbox?.killImmediatelyOnThread()
            sandbox?.close()
            sandbox = null
        }

    private fun CoroutineScope.evaluatorFor(storage: LocalStorage) =
        SandboxJavascriptEvaluator(
            sandbox ?: error("Sandbox not initialized"),
            storage = storage,
            ioScope = this,
        )

    @Test
    fun test_happy_path_evaluator() =
        runTest {
            // get context
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val ruleAttributes = RuleAttributeFactoryBuilder()
            val storage = StorageMock(context = context)

            val expressionEvaluator =
                ExpressionEvaluator(
                    evaluator =
                        evaluatorFor(
                            storage = storage,
                        ),
                    storage = storage,
                    factory = ruleAttributes,
                )

            val rule =
                TriggerRule(
                    experimentId = "1",
                    experimentGroupId = "2",
                    variants =
                        listOf(
                            VariantOption(
                                type = Experiment.Variant.VariantType.HOLDOUT,
                                id = "3",
                                percentage = 20,
                                paywallId = null,
                            ),
                        ),
                    expression = "user.id == '123'",
                    expressionJs = null,
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            val result =
                expressionEvaluator.evaluateExpression(
                    rule = rule,
                    eventData =
                        EventData(
                            name = "test",
                            parameters = mapOf("id" to "123"),
                            createdAt = Date(),
                        ),
                )
            assertEquals(TriggerRuleOutcome.match(rule = rule), result)
        }

    @Test
    fun test_expression_evaluator_expression_js() =
        runTest {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val ruleAttributes = RuleAttributeFactoryBuilder()
            val storage = StorageMock(context = context)

            val expressionEvaluator =
                ExpressionEvaluator(
                    evaluator =
                        evaluatorFor(
                            storage = storage,
                        ),
                    storage = storage,
                    factory = ruleAttributes,
                )

            val trueRule =
                TriggerRule(
                    experimentId = "1",
                    experimentGroupId = "2",
                    variants =
                        listOf(
                            VariantOption(
                                type = Experiment.Variant.VariantType.HOLDOUT,
                                id = "3",
                                percentage = 20,
                                paywallId = null,
                            ),
                        ),
                    expression = null,
                    expressionJs = "function superwallEvaluator(){ return true }; superwallEvaluator",
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            val falseRule =
                TriggerRule(
                    experimentId = "1",
                    experimentGroupId = "2",
                    variants =
                        listOf(
                            VariantOption(
                                type = Experiment.Variant.VariantType.HOLDOUT,
                                id = "3",
                                percentage = 20,
                                paywallId = null,
                            ),
                        ),
                    expression = null,
                    expressionJs = "function superwallEvaluator(){ return false }; superwallEvaluator",
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            var trueResult =
                expressionEvaluator.evaluateExpression(
                    rule = trueRule,
                    eventData =
                        EventData(
                            name = "test",
                            parameters = mapOf("id" to "123"),
                            createdAt = Date(),
                        ),
                )
            assert(trueResult == TriggerRuleOutcome.match(trueRule))

            var falseResult =
                expressionEvaluator.evaluateExpression(
                    rule = falseRule,
                    eventData =
                        EventData(
                            name = "test",
                            parameters = mapOf("id" to "123"),
                            createdAt = Date(),
                        ),
                )

            assert(
                falseResult ==
                    TriggerRuleOutcome.noMatch(
                        source = UnmatchedRule.Source.EXPRESSION,
                        experimentId = "1",
                    ),
            )
        }

    @Test
    fun multi_threaded() =
        runTest {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val ruleAttributes = RuleAttributeFactoryBuilder()
            val storage = StorageMock(context = context)

            val expressionEvaluator: ExpressionEvaluator =
                ExpressionEvaluator(
                    evaluator =
                        evaluatorFor(
                            storage = storage,
                        ),
                    storage = storage,
                    factory = ruleAttributes,
                )

            val trueRule =
                TriggerRule(
                    experimentId = "1",
                    experimentGroupId = "2",
                    variants =
                        listOf(
                            VariantOption(
                                type = Experiment.Variant.VariantType.HOLDOUT,
                                id = "3",
                                percentage = 20,
                                paywallId = null,
                            ),
                        ),
                    expression = "user.id == '123'",
                    expressionJs = null,
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            val falseRule =
                TriggerRule(
                    experimentId = "1",
                    experimentGroupId = "2",
                    variants =
                        listOf(
                            VariantOption(
                                type = Experiment.Variant.VariantType.HOLDOUT,
                                id = "3",
                                percentage = 20,
                                paywallId = null,
                            ),
                        ),
                    expression = null,
                    expressionJs = "function() { return false; }",
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            val trueResult =
                async {
                    expressionEvaluator.evaluateExpression(
                        rule = trueRule,
                        eventData =
                            EventData(
                                name = "test",
                                parameters = mapOf("id" to "123"),
                                createdAt = Date(),
                            ),
                    )
                }

            val falseResult =
                async {
                    expressionEvaluator.evaluateExpression(
                        rule = falseRule,
                        eventData =
                            EventData(
                                name = "test",
                                parameters = mapOf("id" to "123"),
                                createdAt = Date(),
                            ),
                    )
                }

            // Await all the results
            val results = listOf(trueResult.await(), falseResult.await())
            val expectedResults =
                listOf(
                    TriggerRuleOutcome.Match(matchedItem = MatchedItem(rule = trueRule)),
                    TriggerRuleOutcome.noMatch(
                        source = UnmatchedRule.Source.EXPRESSION,
                        experimentId = "1",
                    ),
                )

            assert(results == expectedResults)
        }

    @Test
    fun test_no_expression() =
        runTest {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val ruleAttributes = RuleAttributeFactoryBuilder()
            val storage = StorageMock(context = context)

            val expressionEvaluator: ExpressionEvaluator =
                ExpressionEvaluator(
                    evaluator =
                        evaluatorFor(
                            storage = storage,
                        ),
                    storage = storage,
                    factory = ruleAttributes,
                )

            val rule =
                TriggerRule(
                    experimentId = "1",
                    experimentGroupId = "2",
                    variants =
                        listOf(
                            VariantOption(
                                type = Experiment.Variant.VariantType.HOLDOUT,
                                id = "3",
                                percentage = 20,
                                paywallId = null,
                            ),
                        ),
                    expression = null,
                    expressionJs = null,
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            val result =
                expressionEvaluator.evaluateExpression(
                    rule = rule,
                    eventData =
                        EventData(
                            name = "test",
                            parameters = mapOf("id" to "123"),
                            createdAt = Date(),
                        ),
                )

            assert(result == TriggerRuleOutcome.match(rule = rule))
        }
}
