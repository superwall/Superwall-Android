package com.superwall.sdk.config.options

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import org.junit.Assert.assertEquals
import org.junit.Test

class AdConsentTest {
    @Test
    fun `ad consent defaults to granted`() {
        Given("default superwall options") {
            val options = SuperwallOptions()

            Then("both consent signals are granted") {
                assertEquals(AdConsent(AdConsentStatus.GRANTED, AdConsentStatus.GRANTED), options.adConsent)
            }
        }
    }

    @Test
    fun `consent statuses match the wire format`() {
        assertEquals("granted", AdConsentStatus.GRANTED.raw)
        assertEquals("denied", AdConsentStatus.DENIED.raw)
    }

    @Test
    fun `effective consent is unchanged unless tracking is none`() {
        Given("a consent with personalization denied") {
            val consent = AdConsent(adPersonalization = AdConsentStatus.DENIED)

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
            val consent = AdConsent()

            When("tracking is NONE") {
                val effective = consent.effective(EventTrackingBehavior.NONE)

                Then("both signals are denied") {
                    assertEquals(AdConsent(AdConsentStatus.DENIED, AdConsentStatus.DENIED), effective)
                }
            }
        }
    }

    @Test
    fun `toMap encodes ad consent`() {
        Given("options with ad user data denied") {
            val options = SuperwallOptions()
            options.adConsent = AdConsent(adUserData = AdConsentStatus.DENIED)

            When("converting the options to a map") {
                val map = options.toMap()

                Then("ad_consent holds both values") {
                    assertEquals(
                        mapOf("ad_user_data" to "denied", "ad_personalization" to "granted"),
                        map["ad_consent"],
                    )
                }
            }
        }
    }
}
