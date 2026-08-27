package com.superwall.sdk.network

import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.models.attribution.AttributionProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `MMPMatchResponseTests` on iOS. The decoder configuration under test is the one
 * `DependencyContainer` hands to `MmpService`'s `CustomHttpUrlConnection`.
 */
class MmpMatchResponseTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            namingStrategy = null
            coerceInputValues = true
        }

    private fun decode(raw: String): MmpMatchResponse = json.decodeFromString(raw)


    @Test
    fun `decodes queryParams with a duplicated key as an array`() {
        val response =
            decode(
                """
                {
                  "matched": true,
                  "queryParams": { "utm_source": "google", "tag": ["a", "b"] }
                }
                """.trimIndent(),
            )

        assertTrue(response.matched)
        assertEquals("google", response.queryParams?.get("utm_source")?.jsonPrimitive?.contentOrNull)
        assertEquals(2, (response.queryParams?.get("tag") as JsonArray).size)
    }

    @Test
    fun `decodes an unknown confidence tier as null instead of throwing`() {
        val response =
            decode(
                """
                { "matched": true, "confidence": "extremely_high" }
                """.trimIndent(),
            )

        assertTrue(response.matched)
        assertNull(response.confidence)
    }

    @Test
    fun `decodes known confidence levels`() {
        assertEquals(
            AttributionMatchInfo.Confidence.HIGH,
            decode("""{ "matched": true, "confidence": "high" }""").confidence,
        )
        assertEquals(
            AttributionMatchInfo.Confidence.MEDIUM,
            decode("""{ "matched": true, "confidence": "medium" }""").confidence,
        )
        assertEquals(
            AttributionMatchInfo.Confidence.LOW,
            decode("""{ "matched": true, "confidence": "low" }""").confidence,
        )
    }

    @Test
    fun `decodes an unmatched response with null fields`() {
        val response =
            decode(
                """
                {
                  "matched": false,
                  "confidence": null,
                  "matchScore": null,
                  "clickId": null,
                  "network": null,
                  "acquisitionAttributes": null,
                  "breakdown": { "reason": "no_click_found" }
                }
                """.trimIndent(),
            )

        assertEquals(false, response.matched)
        assertNull(response.confidence)
        assertNull(response.matchScore)
        assertNull(response.acquisitionAttributes)
        assertEquals(
            "no_click_found",
            (response.breakdown?.get("reason") as JsonPrimitive).contentOrNull,
        )
    }

    @Test
    fun `decodes unknown top level keys without failing`() {
        val response =
            decode(
                """
                { "matched": true, "somethingNewFromTheBackend": { "a": 1 } }
                """.trimIndent(),
            )

        assertTrue(response.matched)
    }

    @Test
    fun `encodes the request without null fields and in camelCase`() {
        val encoder =
            Json {
                namingStrategy = null
                explicitNulls = false
            }
        val encoded =
            encoder.encodeToString(
                MmpMatchRequest.serializer(),
                MmpMatchRequest(
                    platform = "android",
                    appUserId = "abc",
                    installReferrerClickId = 42L,
                ),
            )

        assertTrue(encoded.contains("\"appUserId\":\"abc\""))
        assertTrue(encoded.contains("\"installReferrerClickId\":42"))
        assertTrue(!encoded.contains("deviceId"))
    }

    @Test
    fun `promotes the advertising identifiers into their own fields`() {
        val promoted =
            mapOf(
                AttributionProvider.GOOGLE_ADS.rawName to "aaid-value",
                AttributionProvider.GOOGLE_APP_SET.rawName to "app-set-value",
                AttributionProvider.ADJUST_ID.rawName to "adjust-value",
            ).promoteAdvertisingIds()

        assertEquals("aaid-value", promoted.aaid)
        assertEquals("app-set-value", promoted.appSetId)
        // Promoted keys must not be sent twice.
        assertEquals(mapOf("adjustId" to "adjust-value"), promoted.remaining)
    }

    @Test
    fun `leaves the advertising identifiers null when the developer set none`() {
        val promoted =
            mapOf(AttributionProvider.ADJUST_ID.rawName to "adjust-value").promoteAdvertisingIds()

        assertNull(promoted.aaid)
        assertNull(promoted.appSetId)
        assertEquals(1, promoted.remaining.size)
    }

    @Test
    fun `treats a blank advertising identifier as absent but still consumes the key`() {
        val promoted = mapOf(AttributionProvider.GOOGLE_ADS.rawName to "").promoteAdvertisingIds()

        assertNull(promoted.aaid)
        assertTrue(promoted.remaining.isEmpty())
    }

    @Test
    fun `encodes the promoted advertising identifiers as top level fields`() {
        val encoder =
            Json {
                namingStrategy = null
                explicitNulls = false
            }
        val promoted =
            mapOf(AttributionProvider.GOOGLE_ADS.rawName to "aaid-value").promoteAdvertisingIds()
        val encoded =
            encoder.encodeToString(
                MmpMatchRequest.serializer(),
                MmpMatchRequest(platform = "android", aaid = promoted.aaid),
            )

        assertTrue(encoded.contains("\"aaid\":\"aaid-value\""))
        assertTrue(!encoded.contains("googleAds"))
    }

    @Test
    fun `encodes integration attributes when the developer has set any`() {
        val encoder =
            Json {
                namingStrategy = null
                explicitNulls = false
            }
        val encoded =
            encoder.encodeToString(
                MmpMatchRequest.serializer(),
                MmpMatchRequest(
                    platform = "android",
                    integrationAttributes =
                        mapOf(
                            "googleAppSetId" to "app-set-id",
                            "adjustId" to "adjust-id",
                        ),
                ),
            )

        assertTrue(encoded.contains("\"googleAppSetId\":\"app-set-id\""))
        assertTrue(encoded.contains("\"adjustId\":\"adjust-id\""))
    }

    @Test
    fun `omits integration attributes entirely when none are set`() {
        val encoder =
            Json {
                namingStrategy = null
                explicitNulls = false
            }
        val encoded =
            encoder.encodeToString(
                MmpMatchRequest.serializer(),
                MmpMatchRequest(platform = "android"),
            )

        assertTrue(!encoded.contains("integrationAttributes"))
    }
}
