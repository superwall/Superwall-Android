package com.superwall.sdk.customercenter

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.store.testmode.models.SuperwallProduct
import com.superwall.sdk.store.testmode.models.SuperwallProductPlatform
import com.superwall.sdk.store.testmode.models.SuperwallProductPrice
import com.superwall.sdk.store.testmode.models.SuperwallProductsResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomerCenterStringsTest {
    private val resDir =
        listOf(File("src/main/res"), File("superwall/src/main/res")).first { it.isDirectory }

    private fun strings(file: File): Map<String, String> =
        Regex("<string name=\"superwall_(customer_center_[a-z_]+)\">(.*?)</string>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(value: String) = Regex("%\\d\\\$s").findAll(value).map { it.value }.sorted().toList()

    @Test
    fun `the English fallback matches the default resources`() =
        Given("the default string resources") {
            val resources = strings(File(resDir, "values/superwall_customer_center_strings.xml"))
            Then("every resource has an identical English fallback") {
                assertEquals(resources.keys, CustomerCenterStrings.englishStrings.keys)
                resources.forEach { (key, value) ->
                    val unescaped = value.replace("\\'", "'").replace("&amp;", "&")
                    assertEquals(key, unescaped, CustomerCenterStrings.englishStrings[key])
                }
            }
        }

    @Test
    fun `every translation keeps the English placeholders`() =
        Given("every localized strings file") {
            val english = strings(File(resDir, "values/superwall_customer_center_strings.xml"))
            val translations =
                resDir
                    .listFiles { file -> file.name.startsWith("values-") }!!
                    .mapNotNull { dir -> File(dir, "superwall_customer_center_strings.xml").takeIf { it.exists() } }
            Then("there's a translation for each of the iOS SDK's languages") { assertTrue(translations.size >= 40) }
            Then("each only uses known keys, with the same placeholders") {
                for (file in translations) {
                    strings(file).forEach { (key, value) ->
                        val source = english[key]
                        assertNotNull("${file.parentFile.name}: unknown key $key", source)
                        assertEquals("${file.parentFile.name}: $key", placeholders(source!!), placeholders(value))
                        assertTrue("${file.parentFile.name}: $key has an unescaped apostrophe", !Regex("(?<!\\\\)'").containsMatchIn(value))
                    }
                }
            }
        }

    @Test
    fun `every key the code asks for exists`() =
        Given("the keys used across the Customer Center") {
            val sourceDir =
                listOf(File("src/main/java"), File("superwall/src/main/java"))
                    .first { it.isDirectory }
                    .resolve("com/superwall/sdk/customercenter")
            val used =
                sourceDir
                    .listFiles()!!
                    .filter { it.name != "CustomerCenterStrings.kt" }
                    .flatMap { Regex("\"(customer_center_[a-z_]+)\"").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
                    .toSet()
            Then("each has an English string") {
                assertTrue(used.isNotEmpty())
                val missing = used - CustomerCenterStrings.englishStrings.keys
                assertTrue("Missing: $missing", missing.isEmpty())
            }
        }

    @Test
    fun `format arguments fill in`() =
        Given("the English strings") {
            val value = When("formatting") { CustomerCenterStrings.english.string("customer_center_renews_on_for", "Jan 1", "$1") }
            Then("both arguments land") { assertEquals("Renews on Jan 1 for $1", value) }
            Then("an unknown key reads as itself") { assertEquals("nope", CustomerCenterStrings.english.string("nope")) }
        }

    @Test
    fun `the catalogue fills only what Google Play can't`() =
        Given("a catalogue with a web and an Android product") {
            val catalogue =
                listOf(
                    SuperwallProduct(identifier = "web", name = "Web Pro", platform = SuperwallProductPlatform.STRIPE, price = SuperwallProductPrice(999, "USD")),
                    SuperwallProduct(identifier = "play", platform = SuperwallProductPlatform.ANDROID),
                )
            val filled = When("filling the gaps") { fillingGaps(emptyMap(), setOf("web", "play"), catalogue) }
            Then("only the web product is filled, with its name") {
                assertEquals(setOf("web"), filled.keys)
                assertEquals("Web Pro", filled["web"]?.title)
            }
        }

    @Test
    fun `the catalogue is fetched once and shared`() =
        Given("a cold catalogue cache") {
            runTest {
                var now = 0L
                val cache = CatalogueCache { now }
                var fetches = 0
                val gate = CompletableDeferred<Unit>()
                val response = SuperwallProductsResponse(emptyList())
                val fetch: suspend () -> SuperwallProductsResponse = {
                    fetches += 1
                    gate.await()
                    response
                }
                When("two callers overlap") {
                    val first = async { cache.products(fetch) }
                    val second = async { cache.products(fetch) }
                    testScheduler.runCurrent()
                    gate.complete(Unit)
                    first.await()
                    second.await()
                }
                Then("there's one fetch, and the answer is kept while fresh") {
                    assertEquals(1, fetches)
                    assertEquals(response, cache.freshResponse())
                }
                When("the cache goes stale") { now += CatalogueCache.TTL_MS }
                Then("it fetches again") {
                    assertEquals(null, cache.freshResponse())
                    cache.products(fetch)
                    assertEquals(2, fetches)
                }
            }
        }

    @Test
    fun `a failed catalogue fetch isn't remembered`() =
        Given("a fetch that fails") {
            runTest {
                val cache = CatalogueCache { 0L }
                When("it fails") { runCatching { cache.products { throw IllegalStateException("offline") } } }
                Then("the next call tries again") {
                    val response = cache.products { SuperwallProductsResponse(emptyList()) }
                    assertEquals(SuperwallProductsResponse(emptyList()), response)
                }
            }
        }
}
