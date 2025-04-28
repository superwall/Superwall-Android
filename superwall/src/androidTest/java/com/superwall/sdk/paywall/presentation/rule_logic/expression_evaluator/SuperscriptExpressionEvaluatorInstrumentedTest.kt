package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import com.superwall.sdk.RuleAttributeFactoryBuilder
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.LocalNotificationTypeSerializer
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.MatchedItem
import com.superwall.sdk.models.triggers.TriggerPreloadBehavior
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.TriggerRuleOutcome
import com.superwall.sdk.models.triggers.UnmatchedRule
import com.superwall.sdk.models.triggers.VariantOption
import com.superwall.sdk.paywall.presentation.rule_logic.cel.SuperscriptEvaluator
import com.superwall.sdk.paywall.presentation.rule_logic.cel.toPassableValue
import com.superwall.sdk.storage.core_data.CoreDataManager
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.modules.serializersModuleOf
import org.junit.Test
import java.util.Date

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
    )

    @Test
    fun testLinkedHashMapSerialization() {
        // Create a LinkedHashMap explicitly
        val deviceAttributes =
            LinkedHashMap<String, Any>().apply {
                put("publicApiKey", "pk_1234")
                put("platform", "Android")
                put("appUserId", "PyopUpiB6vYVlVQ")
            }

        // Create the full structure
        val attributes =
            mapOf(
                "user" to mapOf("isLoggedIn" to true),
                "device" to deviceAttributes, // Use LinkedHashMap here
                "params" to "",
            )

        // Convert to PassableValue
        val passableValue = attributes.toPassableValue()

        // Serialize and check the output
        val serialized =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
                serializersModuleOf(
                    LocalNotificationTypeSerializer,
                )
                classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS
                classDiscriminator = "type"
            }.encodeToString(passableValue)
        println(serialized)

        // Check if the type field appears
        assertTrue(serialized.contains("LinkedHashMap"))
    }

    @Test
    fun test_happy_path_evaluator() =
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
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
}
