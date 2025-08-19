package com.superwall.sdk.models.attribution

import And
import Given
import Then
import When
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.Superwall
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.storage.CONSTANT_API_KEY
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class AttributionProviderIntegrationTest {
    @Before
    fun setup() {
    }

    @Test
    fun test_attribution_providers_passed_to_redeem_calls() =
        runTest(timeout = 5.minutes) {
            Given("Superwall is configured and we have attribution providers") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val application = context.applicationContext as Application

                if (!Superwall.initialized) {
                    Superwall.configure(
                        application,
                        CONSTANT_API_KEY,
                        options =
                            SuperwallOptions().apply {
                                paywalls.shouldPreload = false
                            },
                    )
                }

                When("we wait for configuration to complete") {
                    delay(1000)
                }

                And("we set integration identifiers for attribution providers") {
                    val attributionIdentifiers =
                        mapOf(
                            AttributionProvider.META to "meta_user_123",
                            AttributionProvider.AMPLITUDE to "amp_user_456",
                            AttributionProvider.MIXPANEL to "mp_distinct_789",
                            AttributionProvider.GOOGLE_ADS to "gclid_abc123",
                            AttributionProvider.ADJUST_ID to "adjust_123",
                            AttributionProvider.AMPLITUDE_DEVICE_ID to "amp_device_456",
                            AttributionProvider.FIREBASE_APP_INSTANCE_ID to "firebase_789",
                            AttributionProvider.CUSTOM to "custom_id_xyz",
                        )

                    Superwall.instance.setIntegrationAttributes(attributionIdentifiers)
                }

                Then("the attribution properties should be available via attributionProps") {
                    val attributionProps = Superwall.instance.integrationAttributes

                    assertEquals("meta_user_123", attributionProps["meta"])
                    assertEquals("amp_user_456", attributionProps["amplitude"])
                    assertEquals("mp_distinct_789", attributionProps["mixpanel"])
                    assertEquals("gclid_abc123", attributionProps["google_ads"])
                    assertEquals("adjust_123", attributionProps["adjustId"])
                    assertEquals("amp_device_456", attributionProps["amplitudeDeviceId"])
                    assertEquals("firebase_789", attributionProps["firebaseAppInstanceId"])
                    assertEquals("custom_id_xyz", attributionProps["custom"])
                    assertEquals(8, attributionProps.size)
                }
            }
        }

    @Test
    fun test_attribution_providers_in_superwall_integration_identifiers() =
        runTest(timeout = 5.minutes) {
            Given("we have Superwall configured with attribution providers") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val application = context.applicationContext as Application

                if (!Superwall.initialized) {
                    Superwall.configure(
                        application,
                        CONSTANT_API_KEY,
                        options =
                            SuperwallOptions().apply {
                                paywalls.shouldPreload = false
                            },
                    )
                }

                delay(1000)

                When("we set integration identifiers") {
                    val attributionIdentifiers =
                        mapOf(
                            AttributionProvider.META to "meta_user_123",
                            AttributionProvider.AMPLITUDE to "amp_user_456",
                            AttributionProvider.GOOGLE_ADS to "gclid_test_123",
                        )

                    Superwall.instance.setIntegrationAttributes(attributionIdentifiers)
                    delay(100) // Allow processing
                }

                Then("the attribution props should be available and correctly formatted") {
                    val attributionProps = Superwall.instance.integrationAttributes

                    assertEquals("meta_user_123", attributionProps["meta"])
                    assertEquals("amp_user_456", attributionProps["amplitude"])
                    assertEquals("gclid_test_123", attributionProps["google_ads"])
                    assertEquals(3, attributionProps.size)

                    And("the attribution props should persist") {
                        delay(100)
                        val propsAgain = Superwall.instance.integrationAttributes
                        assertEquals(attributionProps, propsAgain)
                    }
                }
            }
        }

    @Test
    fun test_empty_attribution_providers() =
        runTest(timeout = 5.minutes) {
            Given("Superwall is configured with no attribution providers") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val application = context.applicationContext as Application

                if (!Superwall.initialized) {
                    Superwall.configure(
                        application,
                        CONSTANT_API_KEY,
                        options =
                            SuperwallOptions().apply {
                                paywalls.shouldPreload = false
                            },
                    )
                }

                delay(1000)

                When("we check attribution properties") {
                    val attributionProps = Superwall.instance.integrationAttributes

                    Then("attribution properties should be empty") {
                        assertTrue("Attribution props should be empty", attributionProps.isEmpty())
                    }
                }
            }
        }

    @Test
    fun test_new_attribution_providers() =
        runTest(timeout = 5.minutes) {
            Given("Superwall is configured") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val application = context.applicationContext as Application

                if (!Superwall.initialized) {
                    Superwall.configure(
                        application,
                        CONSTANT_API_KEY,
                        options =
                            SuperwallOptions().apply {
                                paywalls.shouldPreload = false
                            },
                    )
                }

                delay(1000)

                When("we set various new attribution provider identifiers") {
                    val attributionIdentifiers =
                        mapOf(
                            AttributionProvider.ADJUST_ID to "adjust_test_123",
                            AttributionProvider.AMPLITUDE_DEVICE_ID to "amp_device_test_456",
                            AttributionProvider.AMPLITUDE_USER_ID to "amp_user_test_789",
                            AttributionProvider.APPSFLYER_ID to "appsflyer_test_abc",
                            AttributionProvider.BRAZE_ALIAS_NAME to "braze_alias_test",
                            AttributionProvider.BRAZE_ALIAS_LABEL to "braze_label_test",
                            AttributionProvider.ONESIGNAL_ID to "onesignal_test_123",
                            AttributionProvider.FB_ANON_ID to "fb_anon_test_456",
                            AttributionProvider.FIREBASE_APP_INSTANCE_ID to "firebase_test_789",
                            AttributionProvider.ITERABLE_USER_ID to "iterable_user_test",
                            AttributionProvider.ITERABLE_CAMPAIGN_ID to "iterable_campaign_test",
                            AttributionProvider.ITERABLE_TEMPLATE_ID to "iterable_template_test",
                            AttributionProvider.MIXPANEL_DISTINCT_ID to "mixpanel_distinct_test",
                            AttributionProvider.MPARTICLE_ID to "mparticle_test_123",
                            AttributionProvider.CLEVERTAP_ID to "clevertap_test_456",
                            AttributionProvider.AIRSHIP_CHANNEL_ID to "airship_test_789",
                            AttributionProvider.KOCHAVA_DEVICE_ID to "kochava_test_abc",
                            AttributionProvider.TENJIN_ID to "tenjin_test_def",
                            AttributionProvider.POSTHOG_USER_ID to "posthog_test_ghi",
                            AttributionProvider.CUSTOMERIO_ID to "customerio_test_jkl",
                        )

                    Superwall.instance.setIntegrationAttributes(attributionIdentifiers)
                    delay(100)
                }

                Then("all new attribution providers should be correctly converted") {
                    val attributionProps = Superwall.instance.integrationAttributes

                    assertEquals("adjust_test_123", attributionProps["adjustId"])
                    assertEquals("amp_device_test_456", attributionProps["amplitudeDeviceId"])
                    assertEquals("amp_user_test_789", attributionProps["amplitudeUserId"])
                    assertEquals("appsflyer_test_abc", attributionProps["appsflyerId"])
                    assertEquals("braze_alias_test", attributionProps["brazeAliasName"])
                    assertEquals("braze_label_test", attributionProps["brazeAliasLabel"])
                    assertEquals("onesignal_test_123", attributionProps["onesignalId"])
                    assertEquals("fb_anon_test_456", attributionProps["fbAnonId"])
                    assertEquals("firebase_test_789", attributionProps["firebaseAppInstanceId"])
                    assertEquals("iterable_user_test", attributionProps["iterableUserId"])
                    assertEquals("iterable_campaign_test", attributionProps["iterableCampaignId"])
                    assertEquals("iterable_template_test", attributionProps["iterableTemplateId"])
                    assertEquals("mixpanel_distinct_test", attributionProps["mixpanelDistinctId"])
                    assertEquals("mparticle_test_123", attributionProps["mparticleId"])
                    assertEquals("clevertap_test_456", attributionProps["clevertapId"])
                    assertEquals("airship_test_789", attributionProps["airshipChannelId"])
                    assertEquals("kochava_test_abc", attributionProps["kochavaDeviceId"])
                    assertEquals("tenjin_test_def", attributionProps["tenjinId"])
                    assertEquals("posthog_test_ghi", attributionProps["posthogUserId"])
                    assertEquals("customerio_test_jkl", attributionProps["customerioId"])
                    assertEquals(20, attributionProps.size)
                }
            }
        }
}
