package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import com.superwall.sdk.RuleAttributeFactoryBuilder
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.MatchedItem
import com.superwall.sdk.models.triggers.TriggerPreloadBehavior
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.models.triggers.VariantOption
import com.superwall.sdk.paywall.presentation.rule_logic.cel.SuperscriptEvaluator
import com.superwall.sdk.storage.core_data.CoreDataManager
import io.mockk.coEvery
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.Date
import kotlin.time.Duration.Companion.minutes

class SuperscriptExpressionEvaluatorInstrumentedTest {
    private val attributesFactory = RuleAttributeFactoryBuilder()

    private fun CoroutineScope.evaluatorFor(
        storage: CoreDataManager,
        factoryBuilder: RuleAttributesFactory,
    ) = SuperscriptEvaluator(
        storage = storage,
        json =
            Json {
                classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS
                classDiscriminator = "type"
            },
        factory = factoryBuilder,
        ioScope = IOScope(this.coroutineContext),
        trackError = {},
    )

    @Test
    fun test_happy_path_evaluator() =
        runTest(timeout = 5.minutes) {
            // get context
            val storage = mockk<CoreDataManager>()

            val expressionEvaluator =
                evaluatorFor(
                    storage = storage,
                    factoryBuilder = attributesFactory,
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
                    expressionCEL = "user.id == \"123\"",
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
        runTest(timeout = 5.minutes) {
            val storage = mockk<CoreDataManager>()

            val expressionEvaluator =
                evaluatorFor(
                    storage = storage,
                    factoryBuilder = attributesFactory,
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
                    expressionJs = null,
                    expressionCEL = "true",
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
                    expressionCEL = "false",
                    expressionJs = null,
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            val trueResult =
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

            val falseResult =
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
        runTest(timeout = 5.minutes) {
            val storage = mockk<CoreDataManager>()

            val expressionEvaluator =
                evaluatorFor(
                    storage = storage,
                    factoryBuilder = attributesFactory,
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
                    expressionCEL = "user.id == \"123\"",
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
                    expressionCEL = "false",
                    expressionJs = null,
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
        runTest(timeout = 5.minutes) {
            val storage = mockk<CoreDataManager>()

            val expressionEvaluator =
                evaluatorFor(
                    storage = storage,
                    factoryBuilder = attributesFactory,
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

            assertEquals(TriggerRuleOutcome.match(rule = rule), result)
        }

    // "Days since" for the placement the filter is being checked for is the time
    // since the one before it, not since the one that has just been tracked.
    @Test
    fun test_time_since_the_placement_being_checked() =
        runTest(timeout = 5.minutes) {
            val evaluating =
                EventData(
                    name = "paywall_decline",
                    parameters = emptyMap(),
                    createdAt = Date(),
                )
            // Storage answers 10 days when it's told which placement to skip, and 0
            // when it isn't, as it would with the placement already saved.
            val storage = mockk<CoreDataManager>()
            coEvery { storage.getComputedPropertySinceEvent(null, any()) } returns 0
            coEvery { storage.getComputedPropertySinceEvent(evaluating, any()) } returns 10

            val expressionEvaluator =
                evaluatorFor(
                    storage = storage,
                    factoryBuilder = attributesFactory,
                )

            fun ruleExpecting(days: Int) =
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
                    expressionCEL = "device.daysSince(\"paywall_decline\") == $days",
                    expressionJs = null,
                    preload =
                        TriggerRule.TriggerPreload(
                            behavior = TriggerPreloadBehavior.ALWAYS,
                            requiresReEvaluation = false,
                        ),
                )

            val tenDaysRule = ruleExpecting(days = 10)
            assertEquals(
                TriggerRuleOutcome.match(rule = tenDaysRule),
                expressionEvaluator.evaluateExpression(rule = tenDaysRule, eventData = evaluating),
            )
            assertEquals(
                TriggerRuleOutcome.noMatch(
                    source = UnmatchedRule.Source.EXPRESSION,
                    experimentId = "1",
                ),
                expressionEvaluator.evaluateExpression(rule = ruleExpecting(days = 0), eventData = evaluating),
            )
        }
}
