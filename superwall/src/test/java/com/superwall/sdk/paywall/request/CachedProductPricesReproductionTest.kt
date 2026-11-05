package com.superwall.sdk.paywall.request

import TemplateLogic
import com.superwall.sdk.config.PaywallPreload
import com.superwall.sdk.dependencies.VariablesFactory
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.view.webview.templating.models.Variables
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.abstractions.product.StoreProduct
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for product reference names lost from cached config in 2.8.2. */
class CachedProductPricesReproductionTest {
    private val json = JsonFactory.JSON
    private val names = listOf("primary", "secondary", "offer")
    private val ids =
        listOf("yearly23:yearly23-base:yearly23-trial", "monthly23:monthly23-base:monthly23-trial", "yearly19_3_dc:p1y:introprice")
    private val prices = listOf("€39.99", "€4.99", "€23.99")

    private fun freshConfig(): Config {
        val items =
            ids.mapIndexed { index, id ->
                val parts = id.split(":")
                json.decodeFromString<ProductItem>(
                    """{"reference_name":"${names[index]}","sw_composite_product_id":"$id",
                "store_product":{"store":"PLAY_STORE","product_identifier":"${parts[0]}",
                "base_plan_identifier":"${parts[1]}","offer":{"type":"SPECIFIED","offer_identifier":"${parts[2]}"}}}""",
                )
            }
        val paywall = Paywall.stub().copy(productVariables = null, swProductVariablesTemplate = null)
        paywall.productItems = items
        return Config.stub().copy(paywalls = listOf(paywall))
    }

    private fun restoredConfig(fresh: Config): Config =
        json.decodeFromString(Config.serializer(), json.encodeToString(Config.serializer(), fresh))

    @Test
    fun `product reference name survives serialization`() {
        val original =
            freshConfig()
                .paywalls
                .single()
                .productItems
                .last()
        val restored = json.decodeFromString(ProductItem.serializer(), json.encodeToString(ProductItem.serializer(), original))
        assertEquals(original.fullProductId, restored.fullProductId)
        assertEquals("Product cache round-trip must preserve the template reference", "offer", restored.name)
    }

    @Test
    fun `whole config round trip preserves all product references`() {
        val restored = restoredConfig(freshConfig())
        assertEquals(
            ids,
            restored.paywalls
                .single()
                .productItems
                .map { it.fullProductId },
        )
        assertEquals(
            names,
            restored.paywalls
                .single()
                .productItems
                .map { it.name },
        )
    }

    @Test
    fun `fresh config supplies named prices to webview`() =
        runTest {
            val fresh = freshConfig()
            val harness = Harness(fresh)
            assertNamedPrices(harness.templates(harness.load()))
        }

    @Test
    fun `same build refresh repairs prices after loading disk cached config`() =
        runTest {
            val fresh = freshConfig()
            val restored = restoredConfig(fresh)
            val harness = Harness(restored)
            val cached = harness.load()
            val before = harness.templates(cached)
            val loadedPrices =
                before[1].jsonObject["variables"]!!.jsonObject["products"]!!.jsonArray.map {
                    it.jsonObject.values
                        .single()
                        .jsonObject["price"]!!
                        .jsonPrimitive.content
                }
            assertEquals("Product prices must be loaded before the config refresh", prices, loadedPrices)
            println("Product bindings before refresh: ${before[0]}")
            harness.config = fresh
            // The same production diff + invalidation used by ConfigState.RefreshConfig in 2.8.2.
            val changed = PaywallPreload.changedPaywallIds(restored, fresh)
            assertTrue("Fixture must keep the same paywall build", changed.isEmpty())
            harness.manager.removeCachedPaywalls(changed)
            assertNamedPrices(harness.templates(harness.load()))
        }

    @Test
    fun `281 full request cache reset recovers named prices`() =
        runTest {
            val fresh = freshConfig()
            val harness = Harness(restoredConfig(fresh))
            harness.load()
            harness.config = fresh
            // 2.8.1 invalidated every request entry on config refresh.
            harness.manager.resetCache()
            assertNamedPrices(harness.templates(harness.load()))
        }

    private fun assertNamedPrices(templates: JsonArray) {
        val productRefs = templates[0].jsonObject["products"]!!.jsonArray.map { it.jsonObject["product"]!!.jsonPrimitive.content }
        val variables = templates[1].jsonObject["variables"]!!.jsonObject["products"]!!.jsonArray
        assertEquals("WebView product references must be addressable by name", names, productRefs)
        names.forEachIndexed { index, name ->
            assertEquals(
                prices[index],
                variables[index]
                    .jsonObject[name]!!
                    .jsonObject["price"]!!
                    .jsonPrimitive.content,
            )
        }
    }

    private inner class Harness(
        var config: Config,
    ) {
        private val store = StoreManager(mockk(relaxed = true), mockk(relaxed = true), { mockk(relaxed = true) }, track = {})
        private val factory =
            mockk<PaywallRequestManagerDepFactory> {
                every { makeDeviceInfo() } returns mockk { every { locale } returns "en_GB" }
                every { makeStaticPaywall(any(), any()) } answers { config.paywalls.single() }
                every { activePaywallId() } returns null
                every { currentCustomerInfo() } returns CustomerInfo.empty()
            }
        val manager =
            PaywallRequestManager(
                store,
                mockk<Network>(),
                factory,
                IOScope(Dispatchers.Unconfined),
                track = {},
                getGlobalOverrides = { emptyMap() },
                trackScope = IOScope(Dispatchers.Unconfined),
            )

        init {
            ids.forEachIndexed { index, id ->
                store.cacheProduct(
                    id,
                    mockk<StoreProduct>(relaxed = true) {
                        every { fullIdentifier } returns id
                        every { attributes } returns mapOf("identifier" to id, "price" to prices[index])
                    },
                )
            }
        }

        suspend fun load(): Paywall {
            val result =
                manager.getPaywall(
                    PaywallRequest(
                        null,
                        ResponseIdentifiers(config.paywalls.single().identifier),
                        PaywallRequest.Overrides(null, false),
                        false,
                        "register",
                        0,
                    ),
                )
            check(result is Either.Success) { "Product request failed: $result" }
            assertNull("Prices must load without billing errors", result.value.productsLoadingInfo.failAt)
            return result.value
        }

        suspend fun templates(paywall: Paywall): JsonArray {
            val variablesFactory = mockk<VariablesFactory>()
            coEvery { variablesFactory.makeJsonVariables(any(), any(), any()) } coAnswers {
                Variables(firstArg(), emptyMap(), emptyMap(), emptyMap()).templated()
            }
            return json.parseToJsonElement(TemplateLogic.getBase64EncodedTemplates(json, paywall, null, variablesFactory) { it }).jsonArray
        }
    }
}
