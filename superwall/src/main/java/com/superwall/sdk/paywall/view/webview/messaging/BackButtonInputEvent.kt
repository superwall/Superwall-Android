package com.superwall.sdk.paywall.view.webview.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message injected into the paywall webview when the system back button is
 * re-routed to the paywall (`reroute_back_button` enabled in Paywall settings).
 *
 * The paywall responds by either navigating its flow back one page or posting
 * the `close` message, which dismisses via the standard manual-close path.
 */
@Serializable
data class BackButtonInputEvent(
    @SerialName("event_name")
    val eventName: String = "back_button_input",
    @SerialName("pressed")
    val pressed: Boolean,
)
