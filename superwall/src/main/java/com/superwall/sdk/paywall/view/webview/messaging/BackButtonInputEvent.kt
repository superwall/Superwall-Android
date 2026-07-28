package com.superwall.sdk.paywall.view.webview.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message injected into the paywall webview when the system back button is
 * pressed while a paywall is presented.
 *
 * The paywall responds by either navigating its flow back one page or posting
 * the `close` message, which dismisses via the standard manual-close path.
 */
@Serializable
data class BackButtonInputEvent(
    @SerialName("event_name")
    val eventName: String = "back_button_input",
)
