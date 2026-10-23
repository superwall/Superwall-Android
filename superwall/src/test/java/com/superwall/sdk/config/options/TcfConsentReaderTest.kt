package com.superwall.sdk.config.options

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.primitives.SequentialActor
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

private val GRANTED = AdConsentStatus.Granted
private val DENIED = AdConsentStatus.Denied

// Purposes 1-10. Index n-1 is purpose n.
private const val ALL_PURPOSES = "1111111111"

class TcfAdConsentMappingTest {
    @Test
    fun `all four purpose combinations`() {
        Given("GDPR applies") {
            When("purposes 1, 3, 4 and 7 are all agreed") {
                Then("both are granted") {
                    assertEquals(AdConsent.Custom(GRANTED, GRANTED), tcfAdConsent(1, ALL_PURPOSES))
                }
            }
            When("only purposes 1 and 7 are agreed") {
                Then("only ad user data is granted") {
                    assertEquals(AdConsent.Custom(GRANTED, DENIED), tcfAdConsent(1, "1000001000"))
                }
            }
            When("only purposes 3 and 4 are agreed") {
                Then("only ad personalization is granted") {
                    assertEquals(AdConsent.Custom(DENIED, GRANTED), tcfAdConsent(1, "0011000000"))
                }
            }
            When("none of them are agreed") {
                Then("both are denied") {
                    assertEquals(AdConsent.Custom(DENIED, DENIED), tcfAdConsent(1, "0100010111"))
                }
            }
        }
    }

    @Test
    fun `one missing purpose of a pair denies that signal`() {
        Given("purpose 7 and purpose 4 not agreed") {
            Then("both are denied") {
                assertEquals(AdConsent.Custom(DENIED, DENIED), tcfAdConsent(1, "1110000000"))
            }
        }
    }

    @Test
    fun `gdprApplies is read defensively`() {
        Given("purpose consents for everything") {
            Then("1, \"1\" and true mean GDPR applies") {
                assertEquals(AdConsent.Custom(GRANTED, GRANTED), tcfAdConsent(1, ALL_PURPOSES))
                assertEquals(AdConsent.Custom(GRANTED, GRANTED), tcfAdConsent("1", ALL_PURPOSES))
                assertEquals(AdConsent.Custom(GRANTED, GRANTED), tcfAdConsent(true, ALL_PURPOSES))
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
                assertEquals(AdConsent.Custom(DENIED, GRANTED), consent)
            }
        }
    }
}

class ReportedAdConsentTest {
    private val banner = AdConsent.Custom(DENIED, GRANTED)

    @Test
    fun `default is granted when nothing is set`() {
        Given("untouched options and no banner") {
            val reported = reportedAdConsent(SuperwallOptions(), null)

            Then("both are granted from the default source") {
                assertEquals(ReportedAdConsent(AdConsent.Custom(), AdConsentSource.DEFAULT), reported)
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
            val options = SuperwallOptions { adConsent = AdConsent.Custom(adUserData = DENIED, adPersonalization = DENIED) }
            val reported = reportedAdConsent(options, AdConsent.Custom(GRANTED, GRANTED))

            Then("the developer's values are reported") {
                assertEquals(ReportedAdConsent(AdConsent.Custom(DENIED, DENIED), AdConsentSource.DEVELOPER), reported)
                assertEquals("developer", reported.source.raw)
            }
        }
    }

    @Test
    fun `custom consent with the default values still wins over the banner`() {
        Given("the developer assigned custom consent with the default values") {
            val options = SuperwallOptions()
            options.adConsent = AdConsent.Custom()

            When("a banner denies everything") {
                val reported = reportedAdConsent(options, AdConsent.Custom(DENIED, DENIED))

                Then("granted is reported from the developer source") {
                    assertEquals(ReportedAdConsent(AdConsent.Custom(), AdConsentSource.DEVELOPER), reported)
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
                    if (set) adConsent = AdConsent.Custom()
                }

            Then("every source reports denied") {
                val denied = AdConsent.Custom(DENIED, DENIED)
                assertEquals(
                    ReportedAdConsent(denied, AdConsentSource.DEVELOPER),
                    reportedAdConsent(options(set = true), AdConsent.Custom()),
                )
                assertEquals(
                    ReportedAdConsent(denied, AdConsentSource.TCF),
                    reportedAdConsent(options(set = false), AdConsent.Custom()),
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

            Then("is_ad_consent_set is false until assigned") {
                assertEquals(false, options.toMap()["is_ad_consent_set"])
                options.adConsent = AdConsent.Custom()
                assertEquals(true, options.toMap()["is_ad_consent_set"])
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
                assertEquals(AdConsent.Custom(GRANTED, DENIED), reader.read())
                writeBanner("1", "1000001000")
                assertEquals(AdConsent.Custom(GRANTED, DENIED), reader.read())
                writeBanner(true, "1000001000")
                assertEquals(AdConsent.Custom(GRANTED, DENIED), reader.read())
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
            tracker = { tracked += it },
            makeDeviceAttributes = { HashMap(reportedAdConsent(options, reader.read()).toAttributes()) },
            makeConfigAttributes = { configAttributes },
            currentAdConsent = { reportedAdConsent(options, reader.read()) },
            eventTrackingBehavior = { options.eventTrackingBehavior },
            actor = SequentialActor(AdConsentState(), scope),
        )

    @Test
    fun `a banner change after start publishes once with the new values`() =
        runTest {
            Given("an observed reader with no banner yet") {
                val options = SuperwallOptions()
                val tracked = mutableListOf<Trackable>()
                val publisher = observedPublisher(options, tracked, backgroundScope)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner stores consent for ad user data only") {
                    writeBanner(1, "1000001000")
                    testScheduler.runCurrent()

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
                val publisher = observedPublisher(options, tracked, backgroundScope)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner rewrites the same consent and other keys change") {
                    writeBanner(1, ALL_PURPOSES)
                    writeBanner(1, "1111111111111")
                    preferences.edit().putString("IABTCF_TCString", "abc").commit()
                    shadowOf(Looper.getMainLooper()).idle()
                    testScheduler.runCurrent()

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
                val options = SuperwallOptions { adConsent = AdConsent.Custom() }
                val tracked = mutableListOf<Trackable>()
                val publisher = observedPublisher(options, tracked, backgroundScope)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner stores denied consent") {
                    writeBanner(1, "0000000000")
                    testScheduler.runCurrent()

                    Then("nothing is published and the developer's value is still reported") {
                        assertEquals(0, tracked.size)
                        assertEquals(
                            ReportedAdConsent(AdConsent.Custom(), AdConsentSource.DEVELOPER),
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
                val publisher = observedPublisher(options, tracked, backgroundScope)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner stores consent") {
                    writeBanner(1, ALL_PURPOSES)
                    testScheduler.runCurrent()

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
                val publisher = observedPublisher(options, tracked, backgroundScope)
                reader.observeReportedChanges({ options }) { publisher.publish() }

                When("the banner data is cleared") {
                    writeBanner(null, null)
                    testScheduler.runCurrent()

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
