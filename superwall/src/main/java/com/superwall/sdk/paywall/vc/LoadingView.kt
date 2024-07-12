package com.superwall.sdk.paywall.vc

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState

class LoadingView(
    context: Context,
) : FrameLayout(context) {
    companion object {
        internal const val TAG = "LoadingViewController"
    }

    init {
        setTag(TAG)
        setBackgroundColor(Color.TRANSPARENT)

        // Create a ProgressBar with the default spinner style
        val progressBar =
            ProgressBar(context).apply {
                // Ensure it's centered in the LoadingViewController
                val params =
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                layoutParams = params
            }

        // Add the ProgressBar to this view
        addView(progressBar)

        // Set an OnTouchListener that does nothing but consume touch events
        // to prevent taps from reaching the view this loading indicator obstructs
        setOnTouchListener { _, _ -> true }
    }

    fun setupFor(
        paywallViewController: PaywallView,
        loadingState: PaywallLoadingState,
    ) {
        (this.parent as? ViewGroup)?.removeView(this)
        paywallViewController.addView(this)
        visibility =
            when (loadingState) {
                is PaywallLoadingState.LoadingPurchase, is PaywallLoadingState.ManualLoading ->
                    VISIBLE

                else -> GONE
            }
    }
}
