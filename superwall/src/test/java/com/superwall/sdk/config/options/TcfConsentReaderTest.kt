package com.superwall.sdk.config.options

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

private val GRANTED = AdConsentStatus.GRANTED
private val DENIED = AdConsentStatus.DENIED

// Purposes 1-10. Index n-1 is purpose n.
private const val ALL_PURPOSES = "1111111111"

class TcfAdConsentMappingTest {
    @Test
    fun `all four purpose combinations`() {
        Given("GDPR applies") {
            When("purposes 1, 3, 4 and 7 are all agreed") {
                Then("both are granted") {
                    assertEquals(AdConsent(GRANTED, GRANTED), tcfAdConsent(1, ALL_PURPOSES))
                }
            }
            When("only purposes 1 and 7 are agreed") {
                Then("only ad user data is granted") {
                    assertEquals(AdConsent(GRANTED, DENIED), tcfAdConsent(1, "1000001000"))
                }
            }
            When("only purposes 3 and 4 are agreed") {
                Then("only ad personalization is granted") {
                    assertEquals(AdConsent(DENIED, GRANTED), tcfAdConsent(1, "0011000000"))
                }
            }
            When("none of them are agreed") {
                Then("both are denied") {
                    assertEquals(AdConsent(DENIED, DENIED), tcfAdConsent(1, "0100010111"))
                }
            }
        }
    }

    @Test
    fun `one missing purpose of a pair denies that signal`() {
        Given("purpose 7 and purpose 4 not agreed") {
            Then("both are denied") {
                assertEquals(AdConsent(DENIED, DENIED), tcfAdConsent(1, "1110000000"))
            }
        }
    }

    @Test
    fun `gdprApplies is read defensively`() {
        Given("purpose consents for everything") {
            Then("1, \"1\" and true mean GDPR applies") {
                assertEquals(AdConsent(GRANTED, GRANTED), tcfAdConsent(1, ALL_PURPOSES))
                assertEquals(AdConsent(GRANTED, GRANTED), tcfAdConsent("1", ALL_PURPOSES))
                assertEquals(AdConsent(GRANTED, GRANTED), tcfAdConsent(true, ALL_PURPOSES))
            }
            Then("anything else means there is no banner consent") {
                assertNull(tcfAdConsent(0, ALL_PURPOSES))
                assertNull(tcfAdConsent(null, ALL_PURPOSES))
                assertNull(tcfAdConsent("0", ALL_PURPOSES))
                assertNull(tcfAdConsent("true", ALL_PURPOSES))
                assertNull(tcfAdConsent(false, ALL_PURPOSES))
                assertNull(tcfAdConsent(2, ALL_PURPOSES))
                assertNull(tcfAdConsent(1.0f, ALL_PURPOSES))
            }
        }
    }

    @Test
    fun `missing or empty purpose consents mean no banner consent`() {
        Given("GDPR applies") {
            Then("an empty, missing or non-string value gives no consent") {
                assertNull(tcfAdConsent(1, ""))
                assertNull(tcfAdConsent(1, null))
                assertNull(tcfAdConsent(1, 1111111))
            }
        }
    }

    @Test
    fun `a too short string counts as not agreed`() {
        Given("only purposes 1-4 are stored, all agreed") {
            val consent = tcfAdConsent(1, "1111")

            Then("purpose 7 is not agreed, so ad user data is denied") {
                assertEquals(AdConsent(DENIED, GRANTED), consent)
            }
        }
    }
}

class ReportedAdConsentTest {
    private val banner = AdConsent(DENIED, GRANTED)

    @Test
    fun `default is granted when nothing is set`() {
        Given("untouched options and no banner") {
            val reported = reportedAdConsent(SuperwallOptions(), null)

            Then("both are granted from the default source") {
                assertEquals(ReportedAdConsent(AdConsent(), AdConsentSource.DEFAULT), reported)
                assertEquals("default", reported.source.raw)
            }
        }
    }

    @Test
    fun `banner consent is used when the developer never set one`() {
        Given("untouched options and a banner") {
            val reported = reportedAdConsent(SuperwallOptions(), banner)

            Then("the banner's values are reported from the tcf source") {
                assertEquals(ReportedAdConsent(banner, AdConsentSource.TCF), reported)
                assertEquals("tcf", reported.source.raw)
            }
        }
    }

    @Test
    fun `developer consent wins over the banner`() {
        Given("the developer denied ad user data and a banner granting personalization only") {
            val options = SuperwallOptions { adConsent = AdConsent(adUserData = DENIED, adPersonalization = DENIED) }
            val reported = reportedAdConsent(options, AdConsent(GRANTED, GRANTED))

            Then("the developer's values are reported") {
                assertEquals(ReportedAdConsent(AdConsent(DENIED, DENIED), AdConsentSource.DEVELOPER), reported)
                assertEquals("developer", reported.source.raw)
            }
        }
    }

    @Test
    fun `explicitly setting the default value still wins over the banner`() {
        Given("the developer assigned the default consent") {
            val options = SuperwallOptions()
            options.adConsent = AdConsent()

            When("a banner denies everything") {
                val reported = reportedAdConsent(options, AdConsent(DENIED, DENIED))

                Then("granted is reported from the developer source") {
                    assertEquals(ReportedAdConsent(AdConsent(), AdConsentSource.DEVELOPER), reported)
                }
            }
        }
    }

    @Test
    fun `tracking NONE denies everything but keeps the source`() {
        Given("tracking is NONE") {
            fun options(set: Boolean) =
                SuperwallOptions {
                    eventTrackingBehavior = EventTrackingBehavior.NONE
                    if (set) adConsent = AdConsent()
                }

            Then("every source reports denied") {
                val denied = AdConsent(DENIED, DENIED)
                assertEquals(
                    ReportedAdConsent(denied, AdConsentSource.DEVELOPER),
                    reportedAdConsent(options(set = true), AdConsent()),
                )
                assertEquals(
                    ReportedAdConsent(denied, AdConsentSource.TCF),
                    reportedAdConsent(options(set = false), AdConsent()),
                )
                assertEquals(
                    ReportedAdConsent(denied, AdConsentSource.DEFAULT),
                    reportedAdConsent(options(set = false), null),
                )
            }
        }
    }

    @Test
    fun `config attributes keep the raw option and whether it was set`() {
        Given("untouched options") {
            val options = SuperwallOptions()

            Then("ad_consent_set is false until assigned") {
                assertEquals(false, options.toMap()["ad_consent_set"])
                options.adConsent = AdConsent()
                assertEquals(true, options.toMap()["ad_consent_set"])
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class TcfConsentReaderTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var reader: TcfConsentReader
    private val configAttributes: Trackable = mockk()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        reader = TcfConsentReader(preferences)
    }

    private fun writeBanner(
        gdprApplies: Any?,
        purposes: String?,
    ) {
        preferences
            .edit()
            .apply {
                when (gdprApplies) {
                    is Int -> putInt(IABTCF_GDPR_APPLIES, gdprApplies)
                    is String -> putString(IABTCF_GDPR_APPLIES, gdprApplies)
                    is Boolean -> putBoolean(IABTCF_GDPR_APPLIES, gdprApplies)
                    null -> remove(IABTCF_GDPR_APPLIES)
                }
                if (purposes == null) remove(IABTCF_PURPOSE_CONSENTS) else putString(IABTCF_PURPOSE_CONSENTS, purposes)
            }.commit()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `reads the banner from preferences whatever type gdprApplies has`() {
        Given("a banner granting only ad user data") {
            Then("an Int, String or Boolean gdprApplies is read") {
                writeBanner(1, "1000001000")
                assertEquals(AdConsent(GRANTED, DENIED), reader.read())
                writeBanner("1", "1000001000")
                assertEquals(AdConsent(GRANTED, DENIED), reader.read())
                writeBanner(true, "1000001000")
                assertEquals(AdConsent(GRANTED, DENIED), reader.read())
            }
            Then("0 or missing gdprApplies gives no consent") {
                writeBanner(0, "1000001000")
                assertNull(reader.read())
                writeBanner(null, "1000001000")
                assertNull(reader.read())
            }
            Then("empty purposes give no consent") {
                writeBanner(1, "")
                assertNull(reader.read())
            }
        }
    }

    @Test
    fun `nothing stored gives no consent`() {
        assertNull(reader.read())
    }

    private fun observedPublisher(
        options: SuperwallOptions,
        tracked: MutableList<Trackable>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): AdConsentPublisher =
        AdConsentPublisher(
            scope = scope,
            track = { tracked += it },
            makeDeviceAttributes = {
                val reported = reportedAdConsent(options, reader.read())
                hashMapOf(
                    "adUserDataConsent" to reported.consent.adUserData.raw,
                    "adPersonalizationConsent" to reported.consent.adPersonalization.raw,
                    "adConsentSource" to reported.source.raw,
                )
            },
            makeConfigAttributes = { configAttributes },
        )

    @Test
    fun `a banner change after start publishes once with the new values`() =
        runTest {
            Given("an observed reader with no banner yet") {
                val options = SuperwallOptions()
                val tracked = mutableListOf<Trackable>()
                val publisher = observedPublisher(options, tracked, this@runTest)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner stores consent for ad user data only") {
                    writeBanner(1, "1000001000")
                    testScheduler.advanceUntilIdle()

                    Then("exactly one publish reports the banner values from tcf") {
                        val devices = tracked.filterIsInstance<InternalSuperwallEvent.DeviceAttributes>()
                        assertEquals(1, devices.size)
                        val attributes = devices.single().deviceAttributes
                        assertEquals("granted", attributes["adUserDataConsent"])
                        assertEquals("denied", attributes["adPersonalizationConsent"])
                        assertEquals("tcf", attributes["adConsentSource"])
                    }
                }
            }
        }

    @Test
    fun `no publish when the banner write leaves consent unchanged`() =
        runTest {
            Given("an observed reader with a banner already stored") {
                writeBanner(1, ALL_PURPOSES)
                val options = SuperwallOptions()
                val tracked = mutableListOf<Trackable>()
                val publisher = observedPublisher(options, tracked, this@runTest)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner rewrites the same consent and other keys change") {
                    writeBanner(1, ALL_PURPOSES)
                    writeBanner(1, "1111111111111")
                    preferences.edit().putString("IABTCF_TCString", "abc").commit()
                    shadowOf(Looper.getMainLooper()).idle()
                    testScheduler.advanceUntilIdle()

                    Then("nothing is published") {
                        assertEquals(0, tracked.size)
                    }
                }
            }
        }

    @Test
    fun `no publish when the developer's consent takes precedence`() =
        runTest {
            Given("the developer set consent, even to the default") {
                val options = SuperwallOptions { adConsent = AdConsent() }
                val tracked = mutableListOf<Trackable>()
                val publisher = observedPublisher(options, tracked, this@runTest)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner stores denied consent") {
                    writeBanner(1, "0000000000")
                    testScheduler.advanceUntilIdle()

                    Then("nothing is published and the developer's value is still reported") {
                        assertEquals(0, tracked.size)
                        assertEquals(
                            ReportedAdConsent(AdConsent(), AdConsentSource.DEVELOPER),
                            reportedAdConsent(options, reader.read()),
                        )
                    }
                }
            }
        }

    @Test
    fun `no publish while tracking is NONE`() =
        runTest {
            Given("tracking is NONE") {
                val options = SuperwallOptions { eventTrackingBehavior = EventTrackingBehavior.NONE }
                val tracked = mutableListOf<Trackable>()
                val publisher = observedPublisher(options, tracked, this@runTest)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner stores consent") {
                    writeBanner(1, ALL_PURPOSES)
                    testScheduler.advanceUntilIdle()

                    Then("nothing is published") {
                        assertEquals(0, tracked.size)
                    }
                }
            }
        }

    @Test
    fun `withdrawing the banner falls back to the default`() =
        runTest {
            Given("an observed reader with a denying banner") {
                writeBanner(1, "0000000000")
                val options = SuperwallOptions()
                val tracked = mutableListOf<Trackable>()
                val publisher = observedPublisher(options, tracked, this@runTest)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner data is cleared") {
                    writeBanner(null, null)
                    testScheduler.advanceUntilIdle()

                    Then("one publish reports granted from the default source") {
                        val devices = tracked.filterIsInstance<InternalSuperwallEvent.DeviceAttributes>()
                        assertEquals(1, devices.size)
                        assertEquals("granted", devices.single().deviceAttributes["adUserDataConsent"])
                        assertEquals("default", devices.single().deviceAttributes["adConsentSource"])
                    }
                }
            }
        }
}
