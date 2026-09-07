package com.superwall.sdk.paywall.view.webview.templating

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.paywall.view.webview.templating.models.Variables
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Uses the real config and template serializers and a real emulator WebView.
 * The HTML is a minimal price-binding fixture, not the customer's hosted paywall runtime.
 */
@RunWith(AndroidJUnit4::class)
class CachedProductPricesWebViewTest {
    private val json = JsonFactory.JSON

    @Test
    fun freshConfigDisplaysOfferPrice() {
        assertEquals("€23.99", renderOfferPrice(cached = false))
    }

    @Test
    fun diskCachedConfigDisplaysOfferPrice() {
        // Fails on 2.8.2: price data exists but the "offer" binding is absent.
        assertEquals("€23.99", renderOfferPrice(cached = true))
    }

    private fun renderOfferPrice(cached: Boolean): String {
        val item = json.decodeFromString<ProductItem>(
            """{"reference_name":"offer","store_product":{"store":"PLAY_STORE",
            "product_identifier":"yearly19_3_dc","base_plan_identifier":"p1y",
            "offer":{"type":"SPECIFIED","offer_identifier":"introprice"}}}""",
        )
        val paywall = Paywall.stub().copy(productVariables = null, swProductVariablesTemplate = null)
        paywall.productItems = listOf(item)
        val fresh = Config.stub().copy(paywalls = listOf(paywall))
        val config = if (cached) json.decodeFromString(Config.serializer(), json.encodeToString(Config.serializer(), fresh)) else fresh
        val product = config.paywalls.single().productItems.single()
        val variables = Variables(
            listOf(ProductVariable(product.name, mapOf("price" to "€23.99"))),
            emptyMap(), emptyMap(), emptyMap(),
        )
        val payload = json.encodeToString(Variables.serializer(), variables)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val finished = CountDownLatch(1)
        val output = AtomicReference<String>()
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript("document.getElementById('price').textContent") {
                        output.set(it)
                        finished.countDown()
                    }
                }
            }
            webView.loadDataWithBaseURL(
                "https://localhost/", """
                <html><body><span id="price"></span><script>
                const variables = $payload;
                const products = Object.assign({}, ...variables.products);
                document.getElementById('price').textContent = products.offer?.price ?? '';
                </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null,
            )
        }
        try {
            assertTrue("WebView did not finish loading", finished.await(20, TimeUnit.SECONDS))
            return json.parseToJsonElement(output.get()).jsonPrimitive.content
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }
}
