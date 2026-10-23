package com.superwall.sdk.config.options

/**
 * Whether the user has granted or denied a consent signal.
 */
sealed class AdConsentStatus(
    val raw: String,
) {
    object Granted : AdConsentStatus("granted")

    object Denied : AdConsentStatus("denied")

    override fun toString(): String = raw
}

/**
 * The user's consent for ad measurement, forwarded with the conversions Superwall
 * uploads to Google Ads and Meta.
 */
sealed class AdConsent {
    /**
     * The app hasn't set consent. The SDK uses the consent stored by an IAB TCF consent
     * banner when GDPR applies, and otherwise reports both as [AdConsentStatus.Granted].
     */
    object Unspecified : AdConsent()

    /**
     * Consent the app collected itself, which takes precedence over an IAB TCF consent banner.
     *
     * - [adUserData]: consent to send user data to ad networks for advertising.
     * - [adPersonalization]: consent for them to use that data for personalized advertising.
     *
     * Both default to [AdConsentStatus.Granted].
     */
    data class Custom(
        val adUserData: AdConsentStatus = AdConsentStatus.Granted,
        val adPersonalization: AdConsentStatus = AdConsentStatus.Granted,
    ) : AdConsent()
}

/** The statuses this consent stands for, which are both granted while it's [AdConsent.Unspecified]. */
internal val AdConsent.statuses: AdConsent.Custom
    get() =
        when (this) {
            is AdConsent.Custom -> this
            AdConsent.Unspecified -> AdConsent.Custom()
        }

/**
 * The consent actually reported. Everything is denied when [EventTrackingBehavior.NONE]
 * is set, regardless of [AdConsent].
 */
internal fun AdConsent.Custom.effective(eventTrackingBehavior: EventTrackingBehavior): AdConsent.Custom =
    if (eventTrackingBehavior == EventTrackingBehavior.NONE) {
        AdConsent.Custom(adUserData = AdConsentStatus.Denied, adPersonalization = AdConsentStatus.Denied)
    } else {
        this
    }

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
    val consent: AdConsent.Custom,
    val source: AdConsentSource,
)

/** The device attribute keys ad consent is reported under. */
internal val AD_CONSENT_ATTRIBUTE_KEYS = setOf("adUserDataConsent", "adPersonalizationConsent", "adConsentSource")

/** The device attributes this consent is reported as. */
internal fun ReportedAdConsent.toAttributes(): Map<String, String> =
    mapOf(
        "adUserDataConsent" to consent.adUserData.raw,
        "adPersonalizationConsent" to consent.adPersonalization.raw,
        "adConsentSource" to source.raw,
    )

/**
 * Whether device attributes that carried [sent] report a different consent. Attributes with
 * no consent keys, such as an empty template, never differ.
 */
internal fun ReportedAdConsent.differsFrom(sent: Map<String, Any>): Boolean {
    val attributes = toAttributes()
    if (attributes.keys.none(sent::containsKey)) return false
    return attributes.any { (key, value) -> sent[key] != value }
}

/**
 * Whether this consent should be sent again because it differs from [lastSent], the consent
 * attributes of the latest device attributes send. Never before the first send, or while
 * tracking is [EventTrackingBehavior.NONE].
 */
internal fun ReportedAdConsent.needsRepublish(
    lastSent: Map<String, Any>?,
    eventTrackingBehavior: EventTrackingBehavior,
): Boolean {
    if (eventTrackingBehavior == EventTrackingBehavior.NONE || lastSent == null) {
        return false
    }
    return differsFrom(lastSent)
}

/**
 * Picks the consent to report: the developer's [SuperwallOptions.adConsent] if it's
 * [AdConsent.Custom], else the IAB TCF [bannerConsent] if present, else the granted default.
 * Everything is denied while [SuperwallOptions.eventTrackingBehavior] is [EventTrackingBehavior.NONE].
 */
internal fun reportedAdConsent(
    options: SuperwallOptions,
    bannerConsent: AdConsent.Custom?,
): ReportedAdConsent {
    val developerConsent = options.adConsent
    val (consent, source) =
        when {
            developerConsent is AdConsent.Custom -> developerConsent to AdConsentSource.DEVELOPER
            bannerConsent != null -> bannerConsent to AdConsentSource.TCF
            else -> AdConsent.Custom() to AdConsentSource.DEFAULT
        }
    return ReportedAdConsent(consent.effective(options.eventTrackingBehavior), source)
}
