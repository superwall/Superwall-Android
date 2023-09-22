package com.superwall.sdk.paywall.presentation.rule_logic.expression_evaluator

import ComputedPropertyRequest
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.VariantOption
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.presentation.rule_logic.RuleAttributes
import com.superwall.sdk.storage.StorageMock
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import java.util.*

class RuleAttributeFactoryBuilder : RuleAttributesFactory {
    override suspend fun makeRuleAttributes(
        event: EventData?,
        computedPropertyRequests: List<ComputedPropertyRequest>
    ): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put("user", mapOf(
            "id" to "123",
            "email" to "test@gmail.com"
        ))

        return jsonObject
    }
}

// TODO: Update for 3.4.0
//class ExpressionEvaluatorInstrumentedTest {
//
//    @Test
//    fun test_happy_path_evaluator() = runTest {
//        // get context
//        val context = InstrumentationRegistry.getInstrumentation().targetContext
//        val ruleAttributes = RuleAttributeFactoryBuilder()
//        val storage = StorageMock(context = context)
//
//        val expressionEvaluator = ExpressionEvaluator(
//            context = context,
//            storage = storage,
//            factory = ruleAttributes
//        )
//
//        val result = expressionEvaluator.evaluateExpression(
//            rule = TriggerRule(
//                experimentId = "1",
//                experimentGroupId = "2",
//                variants = listOf(
//                    VariantOption(
//                        type = Experiment.Variant.VariantType.HOLDOUT,
//                        id = "3",
//                        percentage = 20,
//                        paywallId = null
//                    )
//                ),
//                expression = "user.id == '123'",
//                expressionJs = null,
//            ),
//            eventData = EventData(
//                name = "test",
//                parameters = JSONObject().put("id", "123"),
//                createdAt = Date()
//            ),
//            isPreemptive = true
//        )
//
//        println("result: $result")
//
//        assert(result == true)
//    }
//
//
//    @Test
//    fun test_expression_evaluator_expression_js() = runTest {
//        val context = InstrumentationRegistry.getInstrumentation().targetContext
//        val ruleAttributes = RuleAttributeFactoryBuilder()
//        val storage = StorageMock(context = context)
//
//        val expressionEvaluator = ExpressionEvaluator(
//            context = context,
//            storage = storage,
//            factory = ruleAttributes
//        )
//
//        val trueRule = TriggerRule(
//            experimentId = "1",
//            experimentGroupId = "2",
//            variants = listOf(
//                VariantOption(
//                    type = Experiment.Variant.VariantType.HOLDOUT,
//                    id = "3",
//                    percentage = 20,
//                    paywallId = null
//                )
//            ),
//            expression = null,
//            expressionJs = "function superwallEvaluator(){ return true }; superwallEvaluator",
//        )
//
//        val falseRule = TriggerRule(
//            experimentId = "1",
//            experimentGroupId = "2",
//            variants = listOf(
//                VariantOption(
//                    type = Experiment.Variant.VariantType.HOLDOUT,
//                    id = "3",
//                    percentage = 20,
//                    paywallId = null
//                )
//            ),
//            expression = null,
//            expressionJs = "function superwallEvaluator(){ return false }; superwallEvaluator",
//        )
//
//        var trueResult = expressionEvaluator.evaluateExpression(
//            rule = trueRule,
//            eventData = EventData(
//                name = "test",
//                parameters = JSONObject().put("id", "123"),
//                createdAt = Date()
//            ),
//            isPreemptive = true
//        )
//        assert(trueResult == true)
//
//        var falseResult = expressionEvaluator.evaluateExpression(
//            rule = falseRule,
//            eventData = EventData(
//                name = "test",
//                parameters = JSONObject().put("id", "123"),
//                createdAt = Date()
//            ),
//            isPreemptive = true
//        )
//        assert(falseResult == false)
//    }
//
//
//    @Test
//    fun multi_threaded() = runTest {
//
//
//        val context = InstrumentationRegistry.getInstrumentation().targetContext
//        val ruleAttributes = RuleAttributeFactoryBuilder()
//        val storage = StorageMock(context = context)
//
//        val expressionEvaluator: ExpressionEvaluator = ExpressionEvaluator(
//            context = context,
//            storage = storage,
//            factory = ruleAttributes
//        )
//
//        val trueRule = TriggerRule(
//            experimentId = "1",
//            experimentGroupId = "2",
//            variants = listOf(
//                VariantOption(
//                    type = Experiment.Variant.VariantType.HOLDOUT,
//                    id = "3",
//                    percentage = 20,
//                    paywallId = null
//                )
//            ),
//            expression = "user.id == '123'",
//            expressionJs = null
//        )
//
//        val falseRule = TriggerRule(
//            experimentId = "1",
//            experimentGroupId = "2",
//            variants = listOf(
//                VariantOption(
//                    type = Experiment.Variant.VariantType.HOLDOUT,
//                    id = "3",
//                    percentage = 20,
//                    paywallId = null
//                )
//            ),
//            expression = null,
//            expressionJs = "function() { return false; }",
//        )
//
//
//        val trueResult = async {
//            expressionEvaluator.evaluateExpression(
//                rule = trueRule,
//                eventData = EventData(
//                    name = "test",
//                    parameters = JSONObject().put("id", "123"),
//                    createdAt = Date()
//                ),
//                isPreemptive = true
//            )
//        }
//
//        val falseResult = async {
//            expressionEvaluator.evaluateExpression(
//                rule = falseRule,
//                eventData = EventData(
//                    name = "test",
//                    parameters = JSONObject().put("id", "123"),
//                    createdAt = Date()
//                ),
//                isPreemptive = true
//            )
//        }
//
//        // Await all the results
//        val results = listOf(trueResult.await(), falseResult.await())
//        assert(results == listOf(true, false))
//    }
//
//
//    @Test
//    fun test_no_expression() = runTest {
//
//        val context = InstrumentationRegistry.getInstrumentation().targetContext
//        val ruleAttributes = RuleAttributeFactoryBuilder()
//        val storage = StorageMock(context = context)
//
//        val expressionEvaluator: ExpressionEvaluator = ExpressionEvaluator(
//            context = context,
//            storage = storage,
//            factory = ruleAttributes
//        )
//
//        val result = expressionEvaluator.evaluateExpression(
//            rule = TriggerRule(
//                experimentId = "1",
//                experimentGroupId = "2",
//                variants = listOf(
//                    VariantOption(
//                        type = Experiment.Variant.VariantType.HOLDOUT,
//                        id = "3",
//                        percentage = 20,
//                        paywallId = null
//                    )
//                ),
//                expression = null,
//                expressionJs = null,
//            ),
//            eventData = EventData(
//                name = "test",
//                parameters = JSONObject().put("id", "123"),
//                createdAt = Date()
//            ),
//            isPreemptive = true
//        )
//        assert(result == true)
//    }
//}
//
//
//fun runWithRule(rule: TriggerRule) {
//    val context = InstrumentationRegistry.getInstrumentation().targetContext
//    val ruleAttributes = RuleAttributeFactoryBuilder()
//    val storage = StorageMock(context = context)
//
//    val expressionEvaluator = ExpressionEvaluator(
//        context = context,
//        storage = storage,
//        factory = ruleAttributes
//    )
//
//
//}