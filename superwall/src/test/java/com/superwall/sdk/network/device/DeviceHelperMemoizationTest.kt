package com.superwall.sdk.network.device

import android.content.Context
import android.provider.Settings
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.DeviceClassifier
import com.superwall.sdk.analytics.Tier
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.storage.LastPaywallView
import com.superwall.sdk.storage.LatestEnrichment
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.ReviewData
import com.superwall.sdk.storage.TotalPaywallViews
import com.superwall.sdk.store.Entitlements
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class DeviceHelperMemoizationTest {
    private lateinit var context: Context
    private lateinit var deviceHelper: DeviceHelper

    private val storage = mockk<LocalStorage>()
    private val network = mockk<SuperwallAPI>(relaxed = true)
    private val factory = mockk<DeviceHelper.Factory>(relaxed = true)
    private val classifier = mockk<DeviceClassifier>()
    private val superwall = mockk<Superwall>()
    private val entitlements = mockk<Entitlements>()

    private var currentUserId: String? = "user-1"
    private var currentEntitlements = setOf(Entitlement("basic"))

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
            "test-android-id",
        )

        every { storage.apiKey } returns "pk_test_key"
        every { storage.didTrackFirstSession } returns true
        every { storage.read(LatestEnrichment) } returns null
        every { storage.read(LastPaywallView) } returns null
        every { storage.read(TotalPaywallViews) } returns 2
        every { storage.read(ReviewData) } returns null

        coEvery { factory.makeIdentityInfo() } answers {
            IdentityInfo(aliasId = "alias-1", appUserId = currentUserId)
        }
        every { factory.makeLocaleIdentifier() } returns "en_US"
        coEvery { factory.activeProductIds() } returns listOf("com.test.product")
        every { factory.storefrontCountryCode() } returns "US"
        every { factory.makeSuperwallOptions() } returns SuperwallOptions()
        every { factory.experimentalProperties() } returns emptyMap()

        every { classifier.deviceTier() } returns Tier.MID

        every { entitlements.active } answers { currentEntitlements }
        every { superwall.entitlements } returns entitlements
        every { superwall.subscriptionStatus } returns
            MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.Inactive)
        mockkObject(Superwall.Companion)
        every { Superwall.instance } returns superwall

        deviceHelper =
            DeviceHelper(
                context = context,
                storage = storage,
                network = network,
                factory = factory,
                classifier = classifier,
            )
    }

    @After
    fun tearDown() {
        unmockkObject(Superwall.Companion)
    }

    @Test
    fun `getTemplateDevice memoizes the template when nothing changes`() =
        runTest {
            Given("a device helper whose mutable state does not change between calls") {
                When("getting the device template twice") {
                    val first = deviceHelper.getTemplateDevice()
                    val cachedAfterFirst = deviceHelper.cachedTemplate
                    val second = deviceHelper.getTemplateDevice()

                    Then("the template is built once and reused") {
                        assertTrue(first.isNotEmpty())
                        assertNotNull(cachedAfterFirst)
                        assertSame(cachedAfterFirst, deviceHelper.cachedTemplate)
                    }
                    Then("both calls return the same non-volatile values") {
                        assertEquals("user-1", second["appUserId"])
                        assertEquals(first["vendorId"], second["vendorId"])
                        assertEquals(first["activeEntitlements"], second["activeEntitlements"])
                    }
                }
            }
        }

    @Test
    fun `identity change invalidates the memoized template`() =
        runTest {
            Given("a memoized device template") {
                val first = deviceHelper.getTemplateDevice()
                val cachedAfterFirst = deviceHelper.cachedTemplate
                assertEquals("user-1", first["appUserId"])

                When("the user identity changes") {
                    currentUserId = "user-2"
                    val second = deviceHelper.getTemplateDevice()

                    Then("the template is rebuilt with the new identity") {
                        assertEquals("user-2", second["appUserId"])
                        assertNotSame(cachedAfterFirst, deviceHelper.cachedTemplate)
                    }
                }
            }
        }

    @Test
    fun `entitlement change invalidates the memoized template`() =
        runTest {
            Given("a memoized device template") {
                val first = deviceHelper.getTemplateDevice()
                val cachedAfterFirst = deviceHelper.cachedTemplate
                assertEquals(listOf("basic"), first["activeEntitlements"])

                When("the active entitlements change") {
                    currentEntitlements = setOf(Entitlement("basic"), Entitlement("pro"))
                    val second = deviceHelper.getTemplateDevice()

                    Then("the template is rebuilt with the new entitlements") {
                        assertEquals(listOf("basic", "pro"), second["activeEntitlements"])
                        assertNotSame(cachedAfterFirst, deviceHelper.cachedTemplate)
                    }
                }
            }
        }

    @Test
    fun `platform wrapper change invalidates the memoized template`() =
        runTest {
            Given("a memoized device template") {
                deviceHelper.getTemplateDevice()
                val cachedAfterFirst = deviceHelper.cachedTemplate

                When("the platform wrapper is set") {
                    deviceHelper.platformWrapper = "Flutter"
                    val second = deviceHelper.getTemplateDevice()

                    Then("the template is rebuilt with the new wrapper") {
                        assertEquals("Flutter", second["platform_wrapper"])
                        assertNotSame(cachedAfterFirst, deviceHelper.cachedTemplate)
                    }
                }
            }
        }

    @Test
    fun `time-derived fields are overlaid fresh on cache hits`() =
        runTest {
            Given("a memoized device template") {
                deviceHelper.getTemplateDevice()
                val cachedAfterFirst = deviceHelper.cachedTemplate

                When("getting the template again after a paywall view was recorded") {
                    every { storage.read(LastPaywallView) } returns Date(System.currentTimeMillis() - 60_000)
                    val second = deviceHelper.getTemplateDevice()

                    Then("the cached template base is reused") {
                        assertSame(cachedAfterFirst, deviceHelper.cachedTemplate)
                    }
                    Then("the time-derived fields reflect the new state") {
                        assertEquals(0.0, second["daysSinceLastPaywallView"])
                        assertEquals(1.0, second["minutesSinceLastPaywallView"])
                        assertTrue((second["utcDateTime"] as String).isNotEmpty())
                        assertTrue(second["daysSinceInstall"] is Double)
                        assertTrue(second["minutesSinceInstall"] is Double)
                    }
                }
            }
        }

    @Test
    fun `null time-since-paywall fields stay null on cache hits`() =
        runTest {
            Given("a device helper with no recorded paywall view") {
                When("getting the template twice") {
                    deviceHelper.getTemplateDevice()
                    val second = deviceHelper.getTemplateDevice()

                    Then("the null time-since fields are preserved") {
                        assertTrue(second.containsKey("daysSinceLastPaywallView"))
                        assertNull(second["daysSinceLastPaywallView"])
                        assertNull(second["minutesSinceLastPaywallView"])
                    }
                }
            }
        }
}
