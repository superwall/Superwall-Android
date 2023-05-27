package com.superwall.sdk.paywall.vc.web_view.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class PaywallWebEvent {
    object Closed : PaywallWebEvent()
    @SerialName("initiate_purchase") data class InitiatePurchase(val productId: String) : PaywallWebEvent()
    object InitiateRestore : PaywallWebEvent()
    @SerialName("custom") data class Custom(val string: String) : PaywallWebEvent()
    @SerialName("opened_url") data class OpenedURL(val url: String) : PaywallWebEvent()
    @SerialName("opened_url_in_safari") data class OpenedUrlInSafari(val url: String) : PaywallWebEvent()
    @SerialName("opened_deep_link") data class OpenedDeepLink(val url: String) : PaywallWebEvent()
}