package com.superwall.sdk.config.options

import android.content.SharedPreferences

internal const val IABTCF_GDPR_APPLIES = "IABTCF_gdprApplies"
internal const val IABTCF_PURPOSE_CONSENTS = "IABTCF_PurposeConsents"

/**
 * Maps the IAB TCF values a consent banner stores to ad consent, or `null` when the banner
 * holds no consent: GDPR doesn't apply, or no purpose consents are stored.
 *
 * Ad user data needs purposes 1 and 7; ad personalization needs purposes 3 and 4.
 * [gdprApplies] is accepted as `1`, `"1"` or `true`, since some banners store it untyped.
 */
internal fun tcfAdConsent(
    gdprApplies: Any?,
    purposeConsents: Any?,
): AdConsent? {
    val applies =
        when (gdprApplies) {
            is Int -> gdprApplies == 1
            is String -> gdprApplies == "1"
            is Boolean -> gdprApplies
            else -> false
        }
    if (!applies || purposeConsents !is String || purposeConsents.isEmpty()) {
        return null
    }

    fun agreed(vararg purposes: Int) = purposes.all { purposeConsents.getOrNull(it - 1) == '1' }

    fun status(granted: Boolean) = if (granted) AdConsentStatus.GRANTED else AdConsentStatus.DENIED

    return AdConsent(
        adUserData = status(agreed(1, 7)),
        adPersonalization = status(agreed(3, 4)),
    )
}

/**
 * Reads the ad consent stored by the app's IAB TCF consent banner in its default
 * SharedPreferences, and reports changes to it.
 */
internal class TcfConsentReader(
    private val preferences: SharedPreferences,
) {
    private var lastConsent: AdConsent? = null

    // SharedPreferences holds listeners weakly, so this keeps it registered.
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun read(): AdConsent? =
        try {
            tcfAdConsent(
                gdprApplies = preferences.untyped(IABTCF_GDPR_APPLIES),
                purposeConsents = preferences.untyped(IABTCF_PURPOSE_CONSENTS),
            )
        } catch (_: Throwable) {
            null
        }

    /**
     * Calls [onChange] with the previous and new banner consent whenever a write to the
     * TCF keys changes it. Only the first call registers.
     */
    @Synchronized
    fun observe(onChange: (previous: AdConsent?, current: AdConsent?) -> Unit) {
        if (listener != null) return
        lastConsent = read()
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                // A null key means the preferences were cleared.
                if (key != null && key != IABTCF_GDPR_APPLIES && key != IABTCF_PURPOSE_CONSENTS) {
                    return@OnSharedPreferenceChangeListener
                }
                val (previous, current) =
                    synchronized(this) {
                        val previous = lastConsent
                        val current = read()
                        lastConsent = current
                        previous to current
                    }
                if (previous != current) {
                    onChange(previous, current)
                }
            }
        this.listener = listener
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Calls [onChange] when a banner change alters the consent reported with [options]:
     * not while the developer's [SuperwallOptions.adConsent] takes precedence, and not
     * while tracking is [EventTrackingBehavior.NONE], since nothing is sent then.
     */
    fun observeReportedChanges(
        options: () -> SuperwallOptions,
        onChange: () -> Unit,
    ) = observe { previous, current ->
        val currentOptions = options()
        if (currentOptions.eventTrackingBehavior == EventTrackingBehavior.NONE) {
            return@observe
        }
        if (reportedAdConsent(currentOptions, previous) != reportedAdConsent(currentOptions, current)) {
            onChange()
        }
    }

    private fun SharedPreferences.untyped(key: String): Any? {
        if (!contains(key)) return null
        return runCatching { getInt(key, 0) }.getOrNull()
            ?: runCatching { getString(key, null) }.getOrNull()
            ?: runCatching { getBoolean(key, false) }.getOrNull()
    }
}
