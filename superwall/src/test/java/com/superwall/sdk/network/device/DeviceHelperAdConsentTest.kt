package com.superwall.sdk.network.device

import android.content.Context
import android.provider.Settings
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.DeviceClassifier
import com.superwall.sdk.analytics.Tier
import com.superwall.sdk.config.options.AdConsent
import com.superwall.sdk.config.options.ConsentStatus
import com.superwall.sdk.config.options.EventTrackingBehavior
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
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeviceHelperAdConsentTest {
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
    private val options = SuperwallOptions()

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
        every { factory.makeSuperwallOptions() } returns options
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
    fun `template reports granted consent by default`() =
        runTest {
            Given("default options") {
                When("getting the device template") {
                    val template = deviceHelper.getTemplateDevice()

                    Then("both consent attributes are granted") {
                        assertEquals("granted", template["adUserDataConsent"])
                        assertEquals("granted", template["adPersonalizationConsent"])
                    }
                }
            }
        }

    @Test
    fun `runtime consent change is reflected in the memoized template`() =
        runTest {
            Given("a memoized device template") {
                deviceHelper.getTemplateDevice()
                val cachedAfterFirst = deviceHelper.cachedTemplate

                When("ad consent is changed at runtime") {
                    options.adConsent =
                        AdConsent(
                            adUserData = ConsentStatus.DENIED,
                            adPersonalization = ConsentStatus.GRANTED,
                        )
                    val second = deviceHelper.getTemplateDevice()

                    Then("the template is rebuilt with the new consent") {
                        assertEquals("denied", second["adUserDataConsent"])
                        assertEquals("granted", second["adPersonalizationConsent"])
                        assertNotSame(cachedAfterFirst, deviceHelper.cachedTemplate)
                    }
                }
            }
        }

    @Test
    fun `tracking behavior NONE reports denied regardless of consent`() =
        runTest {
            Given("granted ad consent and a memoized template") {
                options.adConsent = AdConsent()
                deviceHelper.getTemplateDevice()

                When("event tracking is set to NONE") {
                    options.eventTrackingBehavior = EventTrackingBehavior.NONE
                    val template = deviceHelper.getTemplateDevice()

                    Then("both consent attributes are denied") {
                        assertEquals("denied", template["adUserDataConsent"])
                        assertEquals("denied", template["adPersonalizationConsent"])
                    }
                }
            }
        }
}
