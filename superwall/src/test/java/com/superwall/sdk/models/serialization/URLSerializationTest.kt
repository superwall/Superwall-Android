package com.superwall.sdk.models.serialization

import com.superwall.sdk.models.paywall.PaywallURL
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL

class PaywallURLSerializerTest {
    private val json =
        Json {
            serializersModule =
                SerializersModule {
                    contextual(PaywallURL::class, PaywallURLSerializer)
                }
        }

    @Serializable
    data class TestData(
        val url:
            @Serializable(with = PaywallURLSerializer::class)
            URL,
    )

    @Test
    fun testSerializeDeserialize() {
        val originalUrl = URL("https://example.com")
        val data = TestData(originalUrl)

        val jsonString = json.encodeToString(data)
        val decodedData = json.decodeFromString<TestData>(jsonString)

        assertEquals(originalUrl, decodedData.url)
    }

    @Test
    fun testDeserializeInvalidUrl() {
        val jsonString = """{"url":"not_a_valid_url"}"""

        val exception =
            kotlin
                .runCatching {
                    json.decodeFromString<TestData>(jsonString)
                }.exceptionOrNull()
        assert(exception != null)
    }
}
