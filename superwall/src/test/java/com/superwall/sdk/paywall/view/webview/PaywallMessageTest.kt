package com.superwall.sdk.paywall.view.webview

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessage
import com.superwall.sdk.paywall.view.webview.messaging.parseWrappedPaywallMessages
import com.superwall.sdk.store.ProductReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaywallMessageTest {
    private fun parsePurchase(extraFields: String): PaywallMessage.Purchase {
        val message =
            """
            {"version":1,"payload":{"events":[{"event_name":"purchase","product":"primary",
            "product_identifier":"annual","should_dismiss":true$extraFields}]}}
            """.trimIndent()
        return parseWrappedPaywallMessages(message)
            .getOrThrow()
            .payload.messages
            .single() as PaywallMessage.Purchase
    }

    @Test
    fun purchase_withReplacement_parsesMode() {
        Given("a purchase message that asks to replace the existing subscription") {
            When("it is parsed") {
                val purchase = parsePurchase(""","replacement":{"mode":"charge_full_price"}""")

                Then("the replacement mode is set") {
                    assertEquals("annual", purchase.productId)
                    assertEquals(ProductReplacement.Mode.CHARGE_FULL_PRICE, purchase.replacementMode)
                }
            }
        }
    }

    @Test
    fun purchase_withoutReplacement_hasNoMode() {
        Given("a purchase message from a paywall that doesn't replace subscriptions") {
            When("it is parsed") {
                val purchase = parsePurchase("")

                Then("there is no replacement mode") {
                    assertNull(purchase.replacementMode)
                }
            }
        }
    }

    @Test
    fun purchase_withUnknownReplacementMode_hasNoMode() {
        Given("a purchase message with a replacement mode this SDK doesn't know") {
            When("it is parsed") {
                val purchase = parsePurchase(""","replacement":{"mode":"charge_later"}""")

                Then("it is parsed as a normal purchase") {
                    assertNull(purchase.replacementMode)
                }
            }
        }
    }

    //    private val jsonFormat = Json { ignoreUnknownKeys = true }
//
//    @Test
//    fun testOnReadySerialization() {
//        val onReadyMessage = PaywallMessage.OnReady(version = "1.0.0")
//        val expectedJson = """{
//            "event_name": "ping",
//            "version": "1.0.0"
//        }"""
//
//
//        val actualJson = jsonFormat.encodeToString(onReadyMessage)
//        assertEquals(expectedJson, actualJson)
//
//        val actualMessage = jsonFormat.decodeFromString<PaywallMessage.OnReady>(expectedJson)
//        assertEquals(onReadyMessage, actualMessage)
//    }
//
//    @Test
//    fun testOpenUrlSerialization() {
//        val openUrlMessage = PaywallMessage.OpenUrl(url = "https://example.com")
//        val expectedJson = """{"url":"https://example.com"}"""
//
//        val actualJson = jsonFormat.encodeToString(openUrlMessage)
//        assertEquals(expectedJson, actualJson)
//
//        val actualMessage = jsonFormat.decodeFromString<PaywallMessage.OpenUrl>(expectedJson)
//        assertEquals(openUrlMessage, actualMessage)
//    }

    // Similar tests for other types...
}
