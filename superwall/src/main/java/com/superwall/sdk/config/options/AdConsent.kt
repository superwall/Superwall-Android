package com.superwall.sdk.config.options

/**
 * Whether the user has granted or denied a consent signal.
 */
enum class AdConsentStatus(
    val raw: String,
) {
    GRANTED("granted"),
    DENIED("denied"),
    ;

    override fun toString(): String = raw
}

/**
 * The user's consent for ad measurement, forwarded with the conversions Superwall
 * uploads to Google Ads. Other ad networks don't use it yet.
 *
 * - [adUserData]: consent to send user data to Google for advertising.
 * - [adPersonalization]: consent for Google to use that data for personalized advertising.
 *
 * Both default to [AdConsentStatus.GRANTED].
 */
data class AdConsent(
    val adUserData: AdConsentStatus = AdConsentStatus.GRANTED,
    val adPersonalization: AdConsentStatus = AdConsentStatus.GRANTED,
)

/**
 * The consent actually reported. Everything is denied when [EventTrackingBehavior.NONE]
 * is set, regardless of [AdConsent].
 */
internal fun AdConsent.effective(eventTrackingBehavior: EventTrackingBehavior): AdConsent =
    if (eventTrackingBehavior == EventTrackingBehavior.NONE) {
        AdConsent(adUserData = AdConsentStatus.DENIED, adPersonalization = AdConsentStatus.DENIED)
    } else {
        this
    }

internal fun AdConsent.toMap(): Map<String, Any> =
    mapOf(
        "ad_user_data" to adUserData.raw,
        "ad_personalization" to adPersonalization.raw,
    )
