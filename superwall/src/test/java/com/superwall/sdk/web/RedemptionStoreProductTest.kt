package com.superwall.sdk.web

import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.models.internal.RedemptionResult.PaywallInfo.PaywallProduct
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.storage.LatestRedemptionResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.Date

class RedemptionStoreProductTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val response = trialRedemptionFixture()
    private val info = (response.codes.single() as RedemptionResult.Success).redemptionInfo
    private val product = info.paywallInfo!!.product!!

    @Test
    fun `existing six argument Java constructor remains available`() {
        val constructor =
            RedemptionResult.PaywallInfo::class.java.getConstructor(
                String::class.java,
                String::class.java,
                Map::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        val legacy = constructor.newInstance("paywall", "placement", emptyMap<String, String>(), "variant", "experiment", "product")
        assertEquals("product", legacy.productIdentifier)
        assertNull(legacy.product)
    }

    @Test
    fun `all checkout product variables survive decoding and cache round trip`() {
        val fixture = requireNotNull(javaClass.getResource("/web-redemption-trial.json")).readText()
        val expected =
            json
                .parseToJsonElement(fixture)
                .jsonObject["codes"]!!
                .jsonArray
                .single()
                .jsonObject["redemptionInfo"]!!
                .jsonObject["paywallInfo"]!!
                .jsonObject["product"]
        assertEquals(expected, json.encodeToJsonElement(PaywallProduct.serializer(), product))
        val cacheJson = JsonFactory.JSON
        val cached = cacheJson.encodeToString(LatestRedemptionResponse.serializer, response)
        val restored = cacheJson.decodeFromString(LatestRedemptionResponse.serializer, cached)
        assertEquals(response.codes, restored.codes)
        assertEquals(response.customerInfo, restored.customerInfo)
    }

    @Test
    fun `legacy and null product responses still decode`() {
        val encoded = json.encodeToJsonElement(RedemptionResult.PaywallInfo.serializer(), info.paywallInfo!!).jsonObject
        for (legacy in listOf(JsonObject(encoded - "product"), JsonObject(encoded + ("product" to JsonNull)))) {
            val decoded = json.decodeFromJsonElement(RedemptionResult.PaywallInfo.serializer(), legacy)
            assertNull(decoded.product)
            assertEquals("test_product", decoded.productIdentifier)
        }
    }

    @Test
    fun `product is retained without the deprecated identifier`() {
        val encoded = json.encodeToJsonElement(RedemptionResult.PaywallInfo.serializer(), info.paywallInfo!!).jsonObject
        val decoded = json.decodeFromJsonElement(RedemptionResult.PaywallInfo.serializer(), JsonObject(encoded - "productIdentifier"))
        assertNull(decoded.productIdentifier)
        assertEquals(product, decoded.product)
    }

    @Test
    fun `adapter preserves prices periods and trial end rather than recalculating them`() {
        val adapted = RedemptionStoreProduct(product)
        assertEquals(BigDecimal("9.99"), adapted.price)
        assertEquals("$0.00", adapted.localizedTrialPeriodPrice)
        assertEquals("7-day free trial", adapted.trialPeriodText)
        assertEquals("mo", adapted.attributes["periodAlt"])
        assertEquals("month", adapted.attributes["localizedPeriod"])
        assertEquals("2026-09-14T12:30:00.000Z", adapted.attributes["trialPeriodEndDate"])
        assertEquals(Date(1789389000000L), adapted.trialPeriodEndDate)
        assertEquals("$0.00", adapted.attributes["trialPeriodWeeklyPrice"])
    }

    @Test
    fun `date only and invalid trial dates do not affect original callback text`() {
        val dateOnly = RedemptionStoreProduct(product.copy(trialPeriodEndDate = "2026-09-14"))
        assertEquals(Date(1789344000000L), dateOnly.trialPeriodEndDate)
        for (value in listOf("", "not a date")) {
            val adapted = RedemptionStoreProduct(product.copy(trialPeriodEndDate = value))
            assertNull(adapted.trialPeriodEndDate)
            assertEquals(value, adapted.trialPeriodEndDateString)
        }
    }
}
