package com.superwall.sdk.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class CacheInstrumentedTest {
    val json =
        Json {
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }

    @Before
    fun setUp() =
        runBlocking {
            println("!!setUp - start")
            // Clear all caches
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val cache = Cache(appContext, json = json)
            cache.delete(AppUserId)
            cache.delete(AliasId)
            cache.delete(UserAttributes)
            println("!!setUp - done")
        }

    @Test
    fun test_alias_id() =
        runTest(timeout = 5.minutes) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val cache = Cache(appContext, json = json)

            // Test first read
            val aliasId = cache.read(AppUserId)
            assert(aliasId == null)

            // Test write
            cache.write(AliasId, "testAliasId")
            val updatedAliasId = cache.read(AliasId)
            assert(updatedAliasId == "testAliasId")

            // Test delete
            cache.delete(AliasId)
            val deletedAliasId = cache.read(AliasId)
            assert(deletedAliasId == null)
        }

    @Test
    fun test_app_user_id() =
        runTest(timeout = 5.minutes) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val cache = Cache(appContext, json = json)

            // Test first read
            val appUserId = cache.read(AppUserId)
            assert(appUserId == null)

            // Test write
            cache.write(AppUserId, "testAppUserId")
            val updatedAppUserid = cache.read(AppUserId)
            assert(updatedAppUserid == "testAppUserId")

            // Test delete
            cache.delete(AppUserId)
            val deletedAppUserId = cache.read(AppUserId)
            assert(deletedAppUserId == null)
        }

    @Test
    fun test_user_attributes() =
        runTest(timeout = 5.minutes) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val cache = Cache(appContext, json = json)

            // Test first read
            val userAttributes = cache.read(UserAttributes)
            assert(userAttributes == null)

            // Test write
            val testAttributes = mapOf("key1" to "value1", "key2" to 123)
            cache.write(UserAttributes, testAttributes)
            val updatedUserAttributes = cache.read(UserAttributes)
            assert(updatedUserAttributes == testAttributes)

            // Test delete
            runBlocking {
                cache.delete(UserAttributes)
            }
            val deletedUserAttributes = runBlocking { cache.read(UserAttributes) }
            assert(deletedUserAttributes == null)
        }

    @Test
    fun test_last_paywall_view() =
        runTest(timeout = 5.minutes) {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val cache = Cache(appContext, json = json)

            // Test first read
            val lastPaywallView = cache.read(LastPaywallView)
            assert(lastPaywallView == null)

            // Test write
            val testDate = Date()
            cache.write(LastPaywallView, testDate)
            val updatedLastPaywallView = cache.read(LastPaywallView)
            assert(updatedLastPaywallView == testDate)

            // Test delete
            cache.delete(LastPaywallView)
            val deletedLastPaywallView = cache.read(LastPaywallView)
            assert(deletedLastPaywallView == null)
        }
}
