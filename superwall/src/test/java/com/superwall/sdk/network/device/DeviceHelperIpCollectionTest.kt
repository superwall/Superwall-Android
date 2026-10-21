package com.superwall.sdk.network.device

import android.content.Context
import android.provider.Settings
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.DeviceClassifier
import com.superwall.sdk.analytics.Tier
import com.superwall.sdk.config.options.EventTrackingBehavior
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.models.attribution.AttributionProvider
import com.superwall.sdk.models.config.AttributionOptions
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.MmpAttributionOptions
import com.superwall.sdk.models.enrichment.Enrichment
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.storage.IntegrationAttributes
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.threeten.bp.Instant
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
class DeviceHelperIpCollectionTest {
    private lateinit var context: Context
    private lateinit var deviceHelper: DeviceHelper
    private lateinit var collector: DeviceIpCollector

    private val storage = mockk<LocalStorage>()
    private val factory = mockk<DeviceHelper.Factory>(relaxed = true)
    private val classifier = mockk<DeviceClassifier>()
    private val superwall = mockk<Superwall>()
    private val options = SuperwallOptions()
    private var integrationAttributes: Map<AttributionProvider, String>? = null

    private val observedAt = Instant.now().toString()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test-android-id")

        every { storage.apiKey } returns "pk_test_key"
        every { storage.didTrackFirstSession } returns true
        every { storage.read(LatestEnrichment) } returns null
        every { storage.read(LastPaywallView) } returns null
        every { storage.read(TotalPaywallViews) } returns 2
        every { storage.read(ReviewData) } returns null
        every { storage.read(IntegrationAttributes) } answers { integrationAttributes }

        coEvery { factory.makeIdentityInfo() } returns IdentityInfo(aliasId = "alias-1", appUserId = "user-1")
        every { factory.makeLocaleIdentifier() } returns "en_US"
        coEvery { factory.activeProductIds() } returns emptyList()
        every { factory.storefrontCountryCode() } returns "US"
        every { factory.makeSuperwallOptions() } returns options
        every { factory.experimentalProperties() } returns emptyMap()
        every { classifier.deviceTier() } returns Tier.MID

        val entitlements = mockk<Entitlements>()
        every { entitlements.active } returns emptySet()
        every { superwall.entitlements } returns entitlements
        every { superwall.subscriptionStatus } returns
            MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.Inactive)
        mockkObject(Superwall.Companion)
        every { Superwall.instance } returns superwall

        collector = DeviceIpCollector(emptyList(), CoroutineScope(Dispatchers.Unconfined))
        collector.record(
            mapOf(
                "ipV4" to "203.0.113.7",
                "ipV4ObservedAt" to observedAt,
                "ipV6" to "2001:db8::1",
                "ipV6ObservedAt" to observedAt,
            ),
        )

        deviceHelper =
            DeviceHelper(
                context = context,
                storage = storage,
                network = mockk<SuperwallAPI>(relaxed = true),
                factory = factory,
                classifier = classifier,
                ipCollector = collector,
            )
    }

    @After
    fun tearDown() {
        unmockkObject(Superwall.Companion)
    }

    private fun mmpConfig(enabled: Boolean) =
        Config.stub().copy(attributionOptions = AttributionOptions(mmp = MmpAttributionOptions(enabled)))

    @Test
    fun `both addresses are reported once config turns the MMP on`() =
        runTest {
            Given("config with the MMP on") {
                deviceHelper.startIpCollectionIfEnabled(mmpConfig(enabled = true))

                When("getting the device template") {
                    val template = deviceHelper.getTemplateDevice()

                    Then("it has both addresses and their times") {
                        assertEquals("203.0.113.7", template["ipV4"])
                        assertEquals(observedAt, template["ipV4ObservedAt"])
                        assertEquals("2001:db8::1", template["ipV6"])
                        assertEquals(observedAt, template["ipV6ObservedAt"])
                    }
                }
            }
        }

    @Test
    fun `no addresses are reported while the MMP is off`() =
        runTest {
            Given("config with the MMP off and no AppsFlyer ID") {
                deviceHelper.startIpCollectionIfEnabled(mmpConfig(enabled = false))

                When("getting the device template") {
                    val template = deviceHelper.getTemplateDevice()

                    Then("it has no addresses") {
                        DeviceIpCollector.ATTRIBUTE_KEYS.forEach { assertFalse(template.containsKey(it)) }
                    }
                }
            }
        }

    @Test
    fun `AppsFlyer only gets the IPv4 address`() =
        runTest {
            Given("an AppsFlyer ID and the MMP off") {
                integrationAttributes = mapOf(AttributionProvider.APPSFLYER_ID to "af-id")

                When("getting the device template") {
                    val template = deviceHelper.getTemplateDevice()

                    Then("it has only the IPv4 address") {
                        assertEquals("203.0.113.7", template["ipV4"])
                        assertNull(template["ipV6"])
                    }
                }
            }
        }

    @Test
    fun `no addresses are reported while tracking is off`() =
        runTest {
            Given("the MMP on but tracking set to NONE") {
                deviceHelper.startIpCollectionIfEnabled(mmpConfig(enabled = true))
                options.eventTrackingBehavior = EventTrackingBehavior.NONE

                When("getting the device template") {
                    val template = deviceHelper.getTemplateDevice()

                    Then("it has no addresses") {
                        assertNull(template["ipV4"])
                        assertNull(template["ipV6"])
                    }
                }
            }
        }

    @Test
    fun `enrichment can't fill in the addresses itself`() =
        runTest {
            Given("enrichment that carries an address and the MMP off") {
                deviceHelper.setEnrichment(
                    Enrichment(
                        JsonObject(emptyMap()),
                        JsonObject(mapOf("ipV4" to JsonPrimitive("198.51.100.1"), "demandTier" to JsonPrimitive("gold"))),
                    ),
                )

                When("getting the device template") {
                    val template = deviceHelper.getTemplateDevice()

                    Then("the address is dropped but other enrichment stays") {
                        assertNull(template["ipV4"])
                        assertEquals("gold", template["demandTier"])
                    }
                }
            }
        }

    @Test
    @RobolectricConfig(qualifiers = "sw320dp-w320dp-h640dp-xhdpi")
    fun `screen size is reported in dp`() {
        assertEquals(320, deviceHelper.screenWidth)
        assertEquals(640, deviceHelper.screenHeight)
        assertEquals(2.0, deviceHelper.devicePixelRatio, 0.0)
    }

    @Test
    @RobolectricConfig(qualifiers = "sw320dp")
    fun `a phone-sized screen is a phone`() {
        assertEquals("phone", deviceHelper.interfaceType)
    }

    @Test
    @RobolectricConfig(qualifiers = "sw720dp")
    fun `a tablet-sized screen is a tablet`() {
        assertEquals("tablet", deviceHelper.interfaceType)
    }

    @Test
    fun `preferred locale fields come from the system locale`() {
        assertTrue(deviceHelper.preferredLanguageCode.isNotEmpty())
        assertTrue(deviceHelper.preferredLocaleIdentifier.startsWith(deviceHelper.preferredLanguageCode))
    }
}
