package com.superwall.sdk.view

import android.app.Activity
import com.superwall.sdk.deprecated.PaywallMessage
import com.superwall.sdk.misc.ActivityLifecycleTracker
import com.superwall.sdk.misc.runOnUiThread
import com.superwall.sdk.models.paywall.Paywall


class PaywallViewManager(currentActivity: Activity, paywall: Paywall) : PaywallMessageDelegate {
    private var currentActivity: Activity = currentActivity
    private var paywall: Paywall = paywall

    companion object {
        public fun showPaywall(paywall: Paywall) {

           val currentActivity = ActivityLifecycleTracker.instance.getCurrentActivity()
            if (currentActivity != null) {
                // TODO: Think about if we need to dismiss the current paywall

                initPaywall(currentActivity, paywall)
            }
        }

        private fun initPaywall(currentActivity: Activity, paywall: Paywall) {
            val paywallManager = PaywallViewManager(currentActivity, paywall)
            paywallManager.setupWebView()


        }
    }

    private var webView: SWWebView? = null

    private fun setupWebView() {
        webView = SWWebView(this,  currentActivity as Activity, null)
        webView!!.loadUrl(paywall.url.toString())

        // TODO: Wait for ping...

        createNewPopupView(webView!!)
    }

    private var paywallView: PaywallView? = null

    private fun createNewPopupView(webView: SWWebView) {
        if (paywallView != null) {
            paywallView!!.dismiss()
        }

        paywallView = PaywallView(webView)
        paywallView!!.showView(currentActivity)
    }


    override fun didReceiveMessage(message: PaywallMessage) {
        if (message == PaywallMessage.Close) {
            if (paywallView != null) {
                paywallView!!.dismiss()
            }
        }
        if (message is PaywallMessage.OnReady) {
            if (paywallView != null) {
                // Publish the pjs event
                runOnUiThread {

                    webView!!.evaluateJavascript("window.paywall.accept64('".plus( paywall.htmlSubstitutions).plus( "');")) {

                    }
                }
            }
        }
    }
}