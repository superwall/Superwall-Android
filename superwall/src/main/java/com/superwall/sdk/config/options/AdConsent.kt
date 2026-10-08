package com.superwall.sdk.config.options

/**
 * Whether the user has granted or denied a consent signal.
 */
enum class ConsentStatus(
    val raw: String,
) {
    GRANTED("granted"),
    DENIED("denied"),
    ;

    override fun toString(): String = raw
}

/**
 * The user's consent for ad measurement, forwarded with conversions that Superwall
 * uploads to ad networks such as Google Ads.
 *
 * - [adUserData]: consent to send user data to the ad network for advertising.
 * - [adPersonalization]: consent to use that data for personalized advertising.
 *
 * Both default to [ConsentStatus.GRANTED].
 */
data class AdConsent(
    val adUserData: ConsentStatus = ConsentStatus.GRANTED,
    val adPersonalization: ConsentStatus = ConsentStatus.GRANTED,
)

/**
 * The consent actually reported. Everything is denied when [EventTrackingBehavior.NONE]
 * is set, regardless of [AdConsent].
 */
internal fun AdConsent.effective(eventTrackingBehavior: EventTrackingBehavior): AdConsent =
    if (eventTrackingBehavior == EventTrackingBehavior.NONE) {
        AdConsent(adUserData = ConsentStatus.DENIED, adPersonalization = ConsentStatus.DENIED)
    } else {
        this
    }

internal fun AdConsent.toMap(): Map<String, Any> =
    mapOf(
        "ad_user_data" to adUserData.raw,
        "ad_personalization" to adPersonalization.raw,
    )
