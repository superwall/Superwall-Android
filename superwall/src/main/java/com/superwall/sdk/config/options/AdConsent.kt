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
 * uploads to Google Ads and Meta.
 *
 * - [adUserData]: consent to send user data to ad networks for advertising.
 * - [adPersonalization]: consent for them to use that data for personalized advertising.
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

/**
 * Where the reported ad consent came from, sent as the `adConsentSource` device attribute.
 */
internal enum class AdConsentSource(
    val raw: String,
) {
    DEVELOPER("developer"),
    TCF("tcf"),
    DEFAULT("default"),
}

/**
 * The ad consent reported to Superwall, with [source] naming what supplied it before the
 * [EventTrackingBehavior.NONE] rule was applied.
 */
internal data class ReportedAdConsent(
    val consent: AdConsent,
    val source: AdConsentSource,
)

/** The device attributes this consent is reported as. */
internal fun ReportedAdConsent.toAttributes(): Map<String, String> =
    mapOf(
        "adUserDataConsent" to consent.adUserData.raw,
        "adPersonalizationConsent" to consent.adPersonalization.raw,
        "adConsentSource" to source.raw,
    )

/** Whether device attributes that carried [sent] no longer report this consent. */
internal fun ReportedAdConsent.differsFrom(sent: Map<String, Any>): Boolean =
    toAttributes().any { (key, value) -> sent[key] != value }

/**
 * Picks the consent to report: the developer's [SuperwallOptions.adConsent] if it was ever
 * set, else the IAB TCF [bannerConsent] if present, else the granted default. Everything is
 * denied while [SuperwallOptions.eventTrackingBehavior] is [EventTrackingBehavior.NONE].
 */
internal fun reportedAdConsent(
    options: SuperwallOptions,
    bannerConsent: AdConsent?,
): ReportedAdConsent {
    val (consent, source) =
        when {
            options.isAdConsentSet -> options.adConsent to AdConsentSource.DEVELOPER
            bannerConsent != null -> bannerConsent to AdConsentSource.TCF
            else -> AdConsent() to AdConsentSource.DEFAULT
        }
    return ReportedAdConsent(consent.effective(options.eventTrackingBehavior), source)
}
