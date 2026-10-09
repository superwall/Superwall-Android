package com.superwall.sdk.paywall.view.webview

import android.view.View
import android.view.ViewGroup
import com.superwall.sdk.models.paywall.PaywallURL
import com.superwall.sdk.paywall.view.webview.messaging.PaywallMessageHandler

interface PaywallWebUI {
    var delegate: PaywallUIDelegate?

    fun onView(perform: View.() -> Unit)

    val messageHandler: PaywallMessageHandler

    fun enableBackgroundRendering()

    fun onPause() {}

    fun onResume() {}

    fun scrollBy(
        x: Int,
        y: Int,
    )

    fun scrollTo(
        x: Int,
        y: Int,
    )

    /**
     * Loads the paywall. [onLoadFailed] fires once the page can't be loaded at all: every
     * URL and retry has been used up. Failures that are still being retried don't report.
     */
    fun setup(
        url: PaywallURL,
        onRenderCrashed: (didCrash: Boolean, priority: Int) -> Unit,
        onLoadFailed: (WebviewError) -> Unit = {},
    )

    fun evaluate(
        code: String,
        resultCallback: ((String?) -> Unit)?,
    )

    fun destroyView()

    var onScrollChangeListener: OnScrollChangeListener?

    interface OnScrollChangeListener {
        fun onScrollChanged(
            currentHorizontalScroll: Int,
            currentVerticalScroll: Int,
            oldHorizontalScroll: Int,
            oldcurrentVerticalScroll: Int,
        )
    }

    fun detach(fromView: ViewGroup)

    fun attach(toView: ViewGroup)
}
