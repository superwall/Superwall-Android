package com.superwall.sdk.paywall.view.webview

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePreloadScriptTest {
    private fun payloadOf(script: String): JsonObject {
        val prefix = "window.__SW_DEVICE_PRELOAD__ = "
        assertEquals(prefix, script.take(prefix.length))
        assertEquals(";", script.takeLast(1))
        val json = script.removePrefix(prefix).removeSuffix(";")
        return Json.decodeFromString(JsonObject.serializer(), json)
    }

    @Test
    fun `builds exact preload script for a simple locale`() {
        Given("a simple device locale") {
            val locale = "en_US"
            When("building the preload script") {
                val script = DevicePreloadScript.build(locale)
                Then("it matches the exact one-liner the web runtime expects") {
                    assertEquals(
                        "window.__SW_DEVICE_PRELOAD__ = {\"deviceLocale\":\"en_US\"};",
                        script,
                    )
                }
            }
        }
    }

    @Test
    fun `escapes hostile locale strings so they cannot break out of the script`() {
        Given("a hostile locale string containing quotes and JS") {
            val locale = "en\"};alert(1);//"
            When("building the preload script") {
                val script = DevicePreloadScript.build(locale)
                Then("the quote is escaped inside the JSON literal") {
                    assertEquals(
                        "window.__SW_DEVICE_PRELOAD__ = {\"deviceLocale\":\"en\\\"};alert(1);//\"};",
                        script,
                    )
                }
                Then("the payload round-trips back to the original value") {
                    assertEquals(
                        locale,
                        payloadOf(script)["deviceLocale"]!!.jsonPrimitive.content,
                    )
                }
            }
        }
    }

    @Test
    fun `handles longer non-ASCII locales`() {
        Given("a longer locale with script and region subtags") {
            val locale = "zh_Hans_CN"
            When("building the preload script") {
                val script = DevicePreloadScript.build(locale)
                Then("it matches the exact one-liner") {
                    assertEquals(
                        "window.__SW_DEVICE_PRELOAD__ = {\"deviceLocale\":\"zh_Hans_CN\"};",
                        script,
                    )
                }
                Then("the payload round-trips back to the original value") {
                    assertEquals(
                        locale,
                        payloadOf(script)["deviceLocale"]!!.jsonPrimitive.content,
                    )
                }
            }
        }
    }

    @Test
    fun `preserves non-ASCII characters`() {
        Given("a locale string containing non-ASCII characters") {
            val locale = "ja_JP_日本"
            When("building the preload script") {
                val script = DevicePreloadScript.build(locale)
                Then("the payload round-trips back to the original value") {
                    assertEquals(
                        locale,
                        payloadOf(script)["deviceLocale"]!!.jsonPrimitive.content,
                    )
                }
            }
        }
    }
}
