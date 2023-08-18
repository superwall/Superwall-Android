package com.superwall.sdk.paywall.vc

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar

class LoadingViewController(context: Context) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.TRANSPARENT)

        // Create a ProgressBar with the default spinner style
        val progressBar = ProgressBar(context).apply {
            // Ensure it's centered in the LoadingViewController
            val params =
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            layoutParams = params
        }

        // Add the ProgressBar to this view
        addView(progressBar)
    }
}
