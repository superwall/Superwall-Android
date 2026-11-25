package com.superwall.sdk.network.signing

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.network.NetworkError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseSignatureCheckTest {
    private val vectors: JsonObject =
        Json
            .parseToJsonElement(
                javaClass.classLoader!!
                    .getResource("response-signing/v1.json")!!
                    .readText(),
            ).jsonObject

    private val roots =
        vectors["roots"]!!.jsonArray.map {
            ResponseVerifier.TrustedRoot(StrictBase64.decode(it.jsonObject["publicKey"]!!.jsonPrimitive.content)!!)
        }

    private fun case(name: String): JsonObject =
        vectors["cases"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private class Harness(
        roots: List<ResponseVerifier.TrustedRoot>,
        now: Long,
        var enforce: Boolean = false,
    ) {
        val reported = mutableListOf<ResponseSignatureFailure>()
        val check =
            ResponseSignatureCheck(
                isEnforced = { enforce },
                report = { reported += it },
                verifier = ResponseVerifier(roots),
                nowSeconds = { now },
            )
    }

    /** Runs a vector through the check as the SDK would, with its context as the request's. */
    private fun Harness.run(case: JsonObject): NetworkError? {
        val context = case["context"]!!.jsonObject
        return check.check(
            signed =
                SignedResponse(
                    ResponseVerifier.Endpoint.entries.first { it.wireName == context.string("endpoint") },
                    context.string("subject"),
                    context.string("deviceId"),
                ),
            apiKey = context.string("apiKey"),
            nonce = context.string("nonce"),
            signatureHeader = case["header"]!!.jsonPrimitive.takeIf { it.isString }?.content,
            body = StrictBase64.decode(case.string("body"))!!,
        )
    }

    @Test
    fun `a verified response is used and nothing is reported`() {
        Given("a signed entitlements response") {
            val case = case("entitlements_verified")
            val harness = Harness(roots, case["now"]!!.jsonPrimitive.long, enforce = true)
            val error = When("it's checked with enforcement on") { harness.run(case) }
            Then("it's used") {
                assertNull(error)
                assertTrue(harness.reported.isEmpty())
            }
        }
    }

    @Test
    fun `report-only uses a bad response but reports it`() {
        Given("a response for another user, signatures not enforced") {
            val case = case("subject_changed")
            val harness = Harness(roots, case["now"]!!.jsonPrimitive.long)
            val error = When("it's checked") { harness.run(case) }
            Then("it's used, and the failure is reported with its reason") {
                assertNull(error)
                assertEquals(
                    listOf(ResponseSignatureFailure(ResponseVerifier.Endpoint.ENTITLEMENTS, "invalid_signature", false)),
                    harness.reported,
                )
            }
        }
    }

    @Test
    fun `enforcement rejects a bad or unsigned response`() {
        Given("signatures are enforced") {
            val case = case("unsigned")
            val harness = Harness(roots, case["now"]!!.jsonPrimitive.long, enforce = true)
            Then("an unsigned response fails like a network error") {
                val error = harness.run(case)
                assertTrue(error is NetworkError.InvalidResponseSignature)
                assertEquals("unsigned", (error as NetworkError.InvalidResponseSignature).reason)
                assertEquals(true, harness.reported.single().isEnforced)
            }
            Then("so does a tampered one") {
                val error = harness.run(case("statement_tampered"))
                assertEquals("invalid_credential_signature", (error as NetworkError.InvalidResponseSignature).reason)
            }
        }
        Given("signatures aren't enforced") {
            val harness = Harness(roots, 0)
            Then("an unsigned response is used, and not reported") {
                assertNull(harness.run(case("unsigned")))
                assertTrue(harness.reported.isEmpty())
            }
        }
    }

    @Test
    fun `a request without an API key can't verify`() {
        Given("a signed response, but no API key on the request") {
            val case = case("entitlements_verified")
            val harness = Harness(roots, case["now"]!!.jsonPrimitive.long, enforce = true)
            val error =
                When("it's checked") {
                    harness.check.check(
                        SignedResponse(ResponseVerifier.Endpoint.ENTITLEMENTS, "user_123", "d"),
                        apiKey = null,
                        nonce = "n",
                        signatureHeader = case.string("header"),
                        body = ByteArray(0),
                    )
                }
            Then("it's rejected") { assertTrue(error is NetworkError.InvalidResponseSignature) }
        }
    }

    @Test
    fun `nonces are fresh base64url and bearer tokens are read from Authorization`() {
        Given("two nonces") {
            val first = ResponseSignatureCheck.makeNonce()
            val second = ResponseSignatureCheck.makeNonce()
            Then("each is 22 base64url characters, and they differ") {
                for (nonce in listOf(first, second)) {
                    assertEquals(22, nonce.length)
                    assertTrue(nonce.all { it.isLetterOrDigit() || it == '-' || it == '_' })
                }
                assertNotEquals(first, second)
            }
        }
        Given("Authorization headers") {
            Then("only a bearer token is an API key") {
                assertEquals("pk_1", ResponseSignatureCheck.bearerToken("Bearer pk_1"))
                assertNull(ResponseSignatureCheck.bearerToken("Basic abc"))
                assertNull(ResponseSignatureCheck.bearerToken(null))
            }
        }
    }

    @Test
    fun `the failure event is private and carries the reason only`() {
        Given("a failure") {
            val event = ResponseSignatureFailure(ResponseVerifier.Endpoint.STATIC_CONFIG, "unknown_root", true)
            Then("it can't trigger a paywall and has just three parameters") {
                assertEquals("response_signature_failed", event.rawName)
                assertTrue(!event.canImplicitlyTriggerPaywall)
                assertEquals(
                    mapOf("endpoint" to "static_config", "reason" to "unknown_root", "is_enforced" to true),
                    event.audienceFilterParams,
                )
                assertTrue(runBlocking { event.getSuperwallParameters() }.isEmpty())
            }
        }
    }
}
