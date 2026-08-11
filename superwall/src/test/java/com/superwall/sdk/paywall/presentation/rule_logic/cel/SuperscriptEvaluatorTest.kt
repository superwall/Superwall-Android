package com.superwall.sdk.paywall.presentation.rule_logic.cel

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.assertTrue
import com.superwall.sdk.dependencies.RuleAttributesFactory
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.paywall.presentation.rule_logic.cel.models.PassableValue
import com.superwall.sdk.storage.core_data.CoreDataManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SuperscriptEvaluatorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val storage = mockk<CoreDataManager>()
    private val factory = mockk<RuleAttributesFactory>(relaxed = true)
    private val evaluator =
        SuperscriptEvaluator(
            json = json,
            ioScope = IOScope(),
            storage = storage,
            factory = factory,
        )

    private fun sharedAttributes(): PassableValue.MapValue =
        PassableValue.MapValue(
            mapOf(
                "user" to PassableValue.MapValue(mapOf("age" to PassableValue.IntValue(20))),
                "device" to PassableValue.MapValue(mapOf("os" to PassableValue.StringValue("Android"))),
                "params" to PassableValue.StringValue(""),
            ),
        )

    @Test
    fun returnsSharedAttributesUnchangedWithoutComputedProperties() =
        runTest {
            Given("shared attributes and a rule without computed property requests") {
                val base = sharedAttributes()

                val result =
                    When("computed properties are overlaid") {
                        evaluator.overlayComputedProperties(base, null, emptyList())
                    }

                Then("the shared attributes are returned as-is and storage is never queried") {
                    assertTrue(result === base)
                    coVerify(exactly = 0) { storage.getComputedPropertySinceEvent(any(), any()) }
                }
            }
        }

    @Test
    fun resolvesComputedPropertiesFreshOnEveryOverlay() =
        runTest {
            Given("shared attributes and a computed property whose value changes between evaluations") {
                val base = sharedAttributes()
                val request =
                    ComputedPropertyRequest(
                        type = ComputedPropertyRequest.ComputedPropertyRequestType.DAYS_SINCE,
                        eventName = "campaign_trigger",
                    )
                coEvery { storage.getComputedPropertySinceEvent(null, request) } returns 1 andThen 2

                val first =
                    When("the same shared attributes are overlaid twice") {
                        evaluator.overlayComputedProperties(base, null, listOf(request))
                    }
                val second = evaluator.overlayComputedProperties(base, null, listOf(request))

                Then("each overlay resolves the computed property fresh") {
                    fun deviceOf(attributes: PassableValue.MapValue): Map<String, PassableValue> =
                        (attributes.value["device"] as PassableValue.MapValue).value

                    coVerify(exactly = 2) { storage.getComputedPropertySinceEvent(null, request) }
                    assertEquals(PassableValue.IntValue(1), deviceOf(first)["daysSince_campaign_trigger"])
                    assertEquals(PassableValue.IntValue(2), deviceOf(second)["daysSince_campaign_trigger"])

                    assertEquals(PassableValue.StringValue("Android"), deviceOf(first)["os"])
                    assertEquals(null, deviceOf(base)["daysSince_campaign_trigger"])
                    assertTrue(base.value["user"] === first.value["user"])
                }
            }
        }
}
