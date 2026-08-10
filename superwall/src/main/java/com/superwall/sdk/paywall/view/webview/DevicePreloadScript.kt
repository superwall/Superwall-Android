package com.superwall.sdk.paywall.view.webview

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the JavaScript snippet that seeds the paywall web runtime with device
 * data as soon as the page starts loading.
 *
 * The web runtime reads `window.__SW_DEVICE_PRELOAD__` at boot and uses
 * `deviceLocale` to render translations on first paint, instead of waiting for
 * the `template_variables` message (which is gated on product/billing loading).
 * The locale value must be identical to the `deviceLocale` the SDK later sends
 * in `template_variables`, so that message is a visual no-op.
 */
internal object DevicePreloadScript {
    /**
     * Returns a one-line script of the form:
     * `window.__SW_DEVICE_PRELOAD__ = {"deviceLocale":"en_US"};`
     *
     * The payload is serialized with kotlinx.serialization so hostile locale
     * strings (quotes, backslashes, etc.) are escaped and cannot break out of
     * the JSON literal.
     */
    fun build(deviceLocale: String): String {
        val payload =
            buildJsonObject {
                put("deviceLocale", deviceLocale)
            }
        return "window.__SW_DEVICE_PRELOAD__ = $payload;"
    }
}
