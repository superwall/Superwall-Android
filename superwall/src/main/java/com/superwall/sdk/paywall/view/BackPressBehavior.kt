package com.superwall.sdk.paywall.view

import com.superwall.sdk.models.paywall.Paywall

internal enum class BackPressBehavior {
    /** The host app's `onBackPressed` callback consumed the press. */
    CONSUMED_BY_APP,

    /** Forward the press into the paywall as a `back_button_input` message. */
    FORWARD_TO_PAYWALL,

    /** Dismiss the paywall, matching a manual close. */
    DISMISS,
}

/**
 * Decides what a system back press does while a paywall is presented.
 *
 * With `reroute_back_button` enabled in Paywall settings, the host app's
 * `PaywallOptions.onBackPressed` callback gets first refusal; if it doesn't
 * consume the press, the press is forwarded into the paywall, which either
 * navigates its flow back one page or posts `close` to dismiss. With the
 * setting disabled the paywall dismisses immediately.
 */
internal fun backPressBehavior(
    rerouteBackButton: Paywall.ToggleMode?,
    consumedByApp: () -> Boolean,
): BackPressBehavior =
    when {
        rerouteBackButton != Paywall.ToggleMode.ENABLED -> BackPressBehavior.DISMISS
        consumedByApp() -> BackPressBehavior.CONSUMED_BY_APP
        else -> BackPressBehavior.FORWARD_TO_PAYWALL
    }
