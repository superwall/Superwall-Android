package com.superwall.sdk.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.storage.keys.AliasId
import com.superwall.sdk.storage.keys.AppUserId
import com.superwall.sdk.storage.keys.LastPaywallView
import com.superwall.sdk.storage.keys.UserAttributes
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

@RunWith(AndroidJUnit4::class)
class CacheInstrumentedTest {

    @Before
    fun setUp() = runBlocking {
        println("!!setUp - start")
        // Clear all caches
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Cache(appContext)
        cache.appUserId.delete()
        cache.aliasId.delete()
        cache.userAttributes.delete()
        println("!!setUp - done")
    }

    @Test
    fun test_alias_id() = runTest {

        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Cache(appContext)

        // Test first read
        val aliasId = cache.appUserId.get()
        assert(aliasId == null)

        // Test write
        cache.aliasId.set(AliasId("testAliasId"))
        val updatedAliasId = cache.aliasId.get()
        assert(updatedAliasId?.aliasId == "testAliasId")


        // Test delete
        cache.aliasId.delete()
        val deletedAliasId = cache.aliasId.get()
        assert(deletedAliasId == null)
    }


    @Test
    fun test_app_user_id() = runTest {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Cache(appContext)

        // Test first read
        val appUserId = cache.appUserId.get()
        assert(appUserId == null)

        // Test write
        cache.appUserId.set(AppUserId("testAppUserId"))
        val updatedAppUserid = cache.appUserId.get()
        assert(updatedAppUserid?.appUserId == "testAppUserId")

        // Test delete
        cache.appUserId.delete()
        val deletedAppUserId = cache.appUserId.get()
        assert(deletedAppUserId == null)
    }

    @Test
    fun test_user_attributes() = runTest {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Cache(appContext)

        // Test first read
        val userAttributes = cache.userAttributes.get()
        assert(userAttributes == null)

        // Test write
        val testAttributes = UserAttributes(mapOf("key1" to "value1", "key2" to 123))
        cache.userAttributes.set(testAttributes)
        val updatedUserAttributes = cache.userAttributes.get()
        assert(updatedUserAttributes?.attributes == testAttributes.attributes)

        // Test delete
        cache.userAttributes.delete()
        val deletedUserAttributes = cache.userAttributes.get()
        assert(deletedUserAttributes == null)
    }

    @Test
    fun test_last_paywall_view() = runTest {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Cache(appContext)

        // Test first read
        val lastPaywallView = cache.lastPaywallView.get()
        assert(lastPaywallView == null)

        // Test write
        val testDate = Date()
        val testLastPaywallView = LastPaywallView(testDate)
        cache.lastPaywallView.set(testLastPaywallView)
        val updatedLastPaywallView = cache.lastPaywallView.get()
        assert(updatedLastPaywallView?.date == testLastPaywallView.date)

        // Test delete
        cache.lastPaywallView.delete()
        val deletedLastPaywallView = cache.lastPaywallView.get()
        assert(deletedLastPaywallView == null)
    }
}