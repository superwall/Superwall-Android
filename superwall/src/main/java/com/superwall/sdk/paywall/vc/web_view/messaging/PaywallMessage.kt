package com.superwall.sdk.paywall.vc.web_view

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.URL

data class WrappedPaywallMessages(
    var version: Int = 1,
    val payload: PayloadMessages,
)

data class PayloadMessages(
    val messages: List<PaywallMessage>,
)

sealed class PaywallMessage {
    data class OnReady(
        val paywallJsVersion: String,
    ) : PaywallMessage()

    object TemplateParamsAndUserAttributes : PaywallMessage()

    object Close : PaywallMessage()

    object Restore : PaywallMessage()

    data class OpenUrl(
        val url: URL,
    ) : PaywallMessage()

    data class OpenUrlInSafari(
        val url: URL,
    ) : PaywallMessage()

    data class OpenDeepLink(
        val url: Uri,
    ) : PaywallMessage()

    data class Purchase(
        val product: String,
        val productId: String,
    ) : PaywallMessage()

    data class Custom(
        val data: String,
    ) : PaywallMessage()

    object PaywallOpen : PaywallMessage()
}

fun parseWrappedPaywallMessages(jsonString: String): WrappedPaywallMessages {
    Log.d("SWWebViewInterface", jsonString)
    val jsonObject = JSONObject(jsonString)
    val version = jsonObject.optInt("version", 1)
    val payloadJson = jsonObject.getJSONObject("payload")
    val messagesJsonArray = payloadJson.getJSONArray("events")
    val messages = mutableListOf<PaywallMessage>()

    for (i in 0 until messagesJsonArray.length()) {
        val messageJson = messagesJsonArray.getJSONObject(i)
        messages.add(parsePaywallMessage(messageJson))
    }

    return WrappedPaywallMessages(version, PayloadMessages(messages))
}

private fun parsePaywallMessage(json: JSONObject): PaywallMessage {
    val eventName = json.getString("event_name")

    return when (eventName) {
        "ping" -> PaywallMessage.OnReady(json.getString("version"))
        "close" -> PaywallMessage.Close
        "restore" -> PaywallMessage.Restore
        "open_url" -> PaywallMessage.OpenUrl(URL(json.getString("url")))
        "open_url_external" -> PaywallMessage.OpenUrlInSafari(URL(json.getString("url")))
        "open_deep_link" -> PaywallMessage.OpenDeepLink(Uri.parse(json.getString("link")))
        "purchase" ->
            PaywallMessage.Purchase(
                json.getString("product"),
                json.getString("product_identifier"),
            )
        "custom" -> PaywallMessage.Custom(json.getString("data"))
        else -> throw IllegalArgumentException("Unknown event name: $eventName")
    }
}
