package com.superwall.sdk.view

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import com.superwall.sdk.deprecated.PaywallMessage
import com.superwall.sdk.deprecated.WrappedPaywallMessages
import com.superwall.sdk.deprecated.parseWrappedPaywallMessages

interface PaywallMessageDelegate {
    fun didReceiveMessage(message: PaywallMessage)
}

class SWWebViewInterface(
    delegate: PaywallMessageDelegate,
    private val context: Context,
) {
    private val delegate: PaywallMessageDelegate = delegate

    @JavascriptInterface
    fun postMessage(message: String) {
        // Print out the message to the console using Log.d
        Log.d("SWWebViewInterface", message)

        // Attempt to parse the message to json
        // and print out the version number
        val wrappedPaywallMessages: WrappedPaywallMessages
        try {
            wrappedPaywallMessages = parseWrappedPaywallMessages(message)
        } catch (e: Throwable) {
            Log.e("SWWebViewInterface", "Error parsing message", e)
            return
        }

        // Loop through the messages and print out the event name
        for (paywallMessage in wrappedPaywallMessages.payload.messages) {
            Log.d("SWWebViewInterface", paywallMessage.javaClass.simpleName)
            delegate.didReceiveMessage(paywallMessage)
        }
    }
}
