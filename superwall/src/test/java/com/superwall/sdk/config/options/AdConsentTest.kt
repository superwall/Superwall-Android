package com.superwall.sdk.config.options

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import org.junit.Assert.assertEquals
import org.junit.Test

class AdConsentTest {
    @Test
    fun `ad consent defaults to unspecified, which stands for granted`() {
        Given("default superwall options") {
            val options = SuperwallOptions()

            Then("consent is unspecified and both signals are granted") {
                assertEquals(AdConsent.Unspecified, options.adConsent)
                assertEquals(false, options.isAdConsentSet)
                assertEquals(AdConsent.Custom(AdConsentStatus.Granted, AdConsentStatus.Granted), options.adConsent.statuses)
            }
        }
    }

    @Test
    fun `only custom consent counts as set`() {
        Given("options with custom consent") {
            val options = SuperwallOptions()
            options.adConsent = AdConsent.Custom()

            Then("it's set until it's unspecified again") {
                assertEquals(true, options.isAdConsentSet)
                options.adConsent = AdConsent.Unspecified
                assertEquals(false, options.isAdConsentSet)
            }
        }
    }

    @Test
    fun `consent statuses match the wire format`() {
        assertEquals("granted", AdConsentStatus.Granted.raw)
        assertEquals("denied", AdConsentStatus.Denied.raw)
    }

    @Test
    fun `effective consent is unchanged unless tracking is none`() {
        Given("a consent with personalization denied") {
            val consent = AdConsent.Custom(adPersonalization = AdConsentStatus.Denied)

            When("tracking is ALL or SUPERWALL_ONLY") {
                Then("the consent is reported as set") {
                    assertEquals(consent, consent.effective(EventTrackingBehavior.ALL))
                    assertEquals(consent, consent.effective(EventTrackingBehavior.SUPERWALL_ONLY))
                }
            }
        }
    }

    @Test
    fun `effective consent is denied when tracking is none`() {
        Given("a fully granted consent") {
            val consent = AdConsent.Custom()

            When("tracking is NONE") {
                val effective = consent.effective(EventTrackingBehavior.NONE)

                Then("both signals are denied") {
                    assertEquals(AdConsent.Custom(AdConsentStatus.Denied, AdConsentStatus.Denied), effective)
                }
            }
        }
    }

    @Test
    fun `toMap encodes ad consent`() {
        Given("options with ad user data denied") {
            val options = SuperwallOptions()
            options.adConsent = AdConsent.Custom(adUserData = AdConsentStatus.Denied)

            When("converting the options to a map") {
                val map = options.toMap()

                Then("both values are flat keys, as on iOS") {
                    assertEquals("denied", map["ad_user_data_consent"])
                    assertEquals("granted", map["ad_personalization_consent"])
                    assertEquals(null, map["ad_consent"])
                }
            }
        }
    }

    @Test
    fun `a sent consent differs only when a reported value or the source changed`() {
        Given("tcf consent with personalization denied") {
            val current =
                ReportedAdConsent(
                    AdConsent.Custom(adPersonalization = AdConsentStatus.Denied),
                    AdConsentSource.TCF,
                )

            Then("attributes carrying the same values match") {
                assertEquals(false, current.differsFrom(HashMap<String, Any>(current.toAttributes())))
            }
            And("an older granted send or a different source differ") {
                assertEquals(
                    true,
                    current.differsFrom(
                        hashMapOf(
                            "adUserDataConsent" to "granted",
                            "adPersonalizationConsent" to "granted",
                            "adConsentSource" to "tcf",
                        ),
                    ),
                )
                assertEquals(
                    true,
                    current.differsFrom(
                        hashMapOf(
                            "adUserDataConsent" to "granted",
                            "adPersonalizationConsent" to "denied",
                            "adConsentSource" to "default",
                        ),
                    ),
                )
            }
            And("attributes without consent keys, such as an empty template, never differ") {
                assertEquals(false, current.differsFrom(hashMapOf("deviceModel" to "Pixel")))
                assertEquals(false, current.differsFrom(HashMap()))
            }
        }
    }

    @Test
    fun `consent is resent only when it changed since the last send`() {
        Given("tcf consent with personalization denied") {
            val current =
                ReportedAdConsent(
                    AdConsent.Custom(adPersonalization = AdConsentStatus.Denied),
                    AdConsentSource.TCF,
                )

            Then("nothing is resent before the first send or while tracking is off") {
                assertEquals(false, current.needsRepublish(null, EventTrackingBehavior.ALL))
                assertEquals(
                    false,
                    current.needsRepublish(mapOf("adUserDataConsent" to "granted"), EventTrackingBehavior.NONE),
                )
            }
            And("the same consent isn't resent but a change is") {
                assertEquals(false, current.needsRepublish(current.toAttributes(), EventTrackingBehavior.ALL))
                assertEquals(
                    true,
                    current.needsRepublish(
                        mapOf(
                            "adUserDataConsent" to "granted",
                            "adPersonalizationConsent" to "granted",
                            "adConsentSource" to "default",
                        ),
                        EventTrackingBehavior.ALL,
                    ),
                )
            }
        }
    }
}
