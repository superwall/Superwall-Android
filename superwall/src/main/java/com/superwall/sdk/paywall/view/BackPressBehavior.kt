package com.superwall.sdk.paywall.view

import com.superwall.sdk.models.paywall.Paywall

/**
 * Decides whether the host app's `PaywallOptions.onBackPressed` callback
 * consumes a system back press. The callback is only consulted when
 * `reroute_back_button` is enabled in Paywall settings and a callback is set.
 *
 * A press the app doesn't consume is forwarded into the paywall as a
 * `back_button_input` message: the paywall navigates its flow back one page
 * when possible, and otherwise posts `close` to dismiss through the
 * standard manual-close path.
 */
internal fun isBackPressConsumedByApp(
    rerouteBackButton: Paywall.ToggleMode?,
    consumedByApp: () -> Boolean,
): Boolean = rerouteBackButton == Paywall.ToggleMode.ENABLED && consumedByApp()
