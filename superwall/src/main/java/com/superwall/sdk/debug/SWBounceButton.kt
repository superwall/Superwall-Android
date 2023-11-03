package com.superwall.sdk.debug

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils

//interface BounceButtonToggleDelegate {
//    fun buttonDidToggle(button: SWBounceButton, isOn: Boolean)
//}
//
//class SWBounceButton(context: Context) : AppCompatButton(context) {
//    var greedyTouches = true
//    var toggleValue: Any? = null
//    var toggleKey = "key"
//    var isOn = false
//    var canToggle = false
//    var oldTitle = ""
//    var showLoading = false
//        set(value) {
//            field = value
//            if (value) {
//                if (oldTitle.isEmpty()) {
//                    oldTitle = text.toString()
//                }
//                text = ""
//                activityIndicator.startAnimating()
//                isEnabled = false
//            } else {
//                text = oldTitle
//                oldTitle = ""
//                activityIndicator.stopAnimating()
//                isEnabled = true
//            }
//        }
//
//    private val activityIndicator = ProgressBar(context).apply {
//        isIndeterminate = true
//        visibility = View.GONE
//    }
//
//    var onBackgroundColor: Int = Color.BLUE
//    var offBackgroundColor: Int = Color.GRAY
//    var bounceButtonToggleDelegate: BounceButtonToggleDelegate? = null
//    var shouldOnlyAnimateText = false
//    var shouldAnimateLightly = false
//    private var didAddTargetForCustomAction = false
//
//    var action: ((SWBounceButton) -> Unit)? = null
//        set(value) {
//            field = value
//            if (!didAddTargetForCustomAction) {
//                setOnClickListener { tapped(it as SWBounceButton) }
//                didAddTargetForCustomAction = true
//            }
//        }
//
//    init {
//        setTextColor(Color.BLACK)
//        setOnClickListener { tappedBounceButton(it as SWBounceButton) }
//        addView(activityIndicator)
//        // Set up constraints for activityIndicator here
//    }
//
//    private fun tapped(button: SWBounceButton) {
//        action?.invoke(button)
//    }
//
//    private fun tappedBounceButton(button: SWBounceButton) {
//        if (isEnabled) {
//            // Use Haptic Feedback here
//        }
//        shouldToggle()
//    }
//
//    private fun shouldToggle() {
//        if (canToggle) {
//            isOn = !isOn
//            bounceButtonToggleDelegate?.buttonDidToggle(this, isOn)
//            setBackgroundColor(if (isOn) onBackgroundColor else offBackgroundColor)
//        }
//    }
//
//    override fun setPressed(pressed: Boolean) {
//        if (shouldOnlyAnimateText) {
//            animateTitleScale(pressed)
//        } else {
//            if (shouldAnimateLightly) {
//                animateScaleLightly(pressed)
//            } else {
//                animateScale(pressed)
//            }
//        }
//        super.setPressed(pressed)
//    }
//
//    override fun onAttachedToWindow() {
//        super.onAttachedToWindow()
//        if (canToggle) {
//            setBackgroundColor(if (isOn) onBackgroundColor else offBackgroundColor)
//        }
//    }
//
//    override fun hitTest(x: Int, y: Int, event: MotionEvent?): Boolean {
//        if (isHidden || !isEnabled || alpha < 0.01) {
//            return false
//        }
//        val inset = if (greedyTouches) -15f else -10f
//        val largerFrame = RectF(bounds).apply {
//            inset(inset, inset)
//        }
//        return largerFrame.contains(x.toFloat(), y.toFloat())
//    }
//
//    // Define the animations here
//    private fun animateScale(pressed: Boolean) { /*...*/ }
//    private fun animateScaleLightly(pressed: Boolean) { /*...*/ }
//    private fun animateTitleScale(pressed: Boolean) { /*...*/ }
//}
