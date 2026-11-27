package com.superwall.sdk.network.signing

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.network.NetworkError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses the Superwall services really sent when signing through emulated KMS, recorded by
 * superwall/paywall-next `script/e2b/response-signing-e2e.mjs` (`vectors/live-kms.json` there).
 * Copy a refreshed file into `src/test/resources/response-signing/`; don't edit it by hand.
 */
class LiveResponseVectorsTest {
    private val vectors: JsonObject =
        Json
            .parseToJsonElement(
                javaClass.classLoader!!
                    .getResource("response-signing/live-kms.json")!!
                    .readText(),
            ).jsonObject

    private val roots =
        vectors["roots"]!!.jsonArray.map {
            ResponseVerifier.TrustedRoot(StrictBase64.decode(it.jsonObject["publicKey"]!!.jsonPrimitive.content)!!)
        }

    private val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    /** Checks a recorded response the way the SDK does, with enforcement on. */
    private fun check(
        case: JsonObject,
        body: ByteArray,
    ): NetworkError? {
        val context = case["context"]!!.jsonObject
        val check =
            ResponseSignatureCheck(
                isEnforced = { true },
                report = {},
                verifier = ResponseVerifier(roots),
                nowSeconds = { case["now"]!!.jsonPrimitive.long },
            )
        return check.check(
            signed =
                SignedResponse(
                    ResponseVerifier.Endpoint.entries.first { it.wireName == context.string("endpoint") },
                    context.string("subject"),
                    context.string("deviceId"),
                ),
            apiKey = context.string("apiKey"),
            nonce = context.string("nonce"),
            signatureHeader = case.string("header"),
            body = body,
        )
    }

    @Test
    fun `responses the services signed through KMS verify, and stop verifying when changed`() {
        Given("entitlements and static_config responses recorded from both services") {
            assertEquals(
                setOf("entitlements", "static_config"),
                cases.map { it["context"]!!.jsonObject.string("endpoint") }.toSet(),
            )
            for (case in cases) {
                val name = case.string("name")
                val body = StrictBase64.decode(case.string("body"))!!
                val error = When("$name is checked as received") { check(case, body) }
                Then("$name is used") { assertNull(error) }

                val tampered = body.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
                val rejected = When("one byte of $name's body changes") { check(case, tampered) }
                Then("it's rejected as an invalid signature") {
                    assertTrue(rejected is NetworkError.InvalidResponseSignature)
                    assertEquals("invalid_signature", (rejected as NetworkError.InvalidResponseSignature).reason)
                }
            }
        }
    }
}
