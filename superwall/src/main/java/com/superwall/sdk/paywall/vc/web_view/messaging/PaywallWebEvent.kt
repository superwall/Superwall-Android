package com.superwall.sdk.paywall.vc.web_view.messaging

import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.net.URL

@Serializable
sealed class PaywallWebEvent {
    object Closed : PaywallWebEvent()

    @SerialName("initiate_purchase")
    data class InitiatePurchase(
        val productId: String,
    ) : PaywallWebEvent()

    object InitiateRestore : PaywallWebEvent()

    @SerialName("custom")
    data class Custom(
        val string: String,
    ) : PaywallWebEvent()

    @SerialName("opened_url")
    data class OpenedURL(
        val url: URL,
    ) : PaywallWebEvent()

    @SerialName("opened_url_in_safari")
    data class OpenedUrlInChrome(
        val url: URL,
    ) : PaywallWebEvent()

    @SerialName("opened_deep_link")
    data class OpenedDeepLink(
        val url: Uri,
    ) : PaywallWebEvent()

    @SerialName("custom_placement")
    data class CustomPlacement(
        val name: String,
        val params: JSONObject,
    ) : PaywallWebEvent()
}
