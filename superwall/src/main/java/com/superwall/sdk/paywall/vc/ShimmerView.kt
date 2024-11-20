package com.superwall.sdk.paywall.vc

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.VectorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout.LayoutParams
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import com.superwall.sdk.R
import com.superwall.sdk.misc.isDarkColor
import com.superwall.sdk.misc.readableOverlayColor
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState

class ShimmerView(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    private var animator: ValueAnimator? = null
    private var vectorDrawable: VectorDrawable? = null

    companion object {
        internal const val TAG = "ShimmerView"
    }

    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    private val portraitDrawable: VectorDrawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.portrait_shimmer_skeleton) as? VectorDrawable
    }
    private val landscapeDrawable: VectorDrawable? by lazy {
        ContextCompat.getDrawable(context, R.drawable.landscape_shimmer_skeleton) as? VectorDrawable
    }
    private var tintColorFilter: android.graphics.ColorFilter? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setTag(TAG)
        checkForOrientationChanges()
    }

    private var background: Int = 0
    private var isLightBackground: Boolean = false
    private var tintColor: Int = 0

    fun setupFor(
        paywallViewController: PaywallView,
        loadingState: PaywallLoadingState,
    ) {
        (this.parent as? ViewGroup)?.removeView(this)
        if (background != paywallViewController.backgroundColor) {
            background = paywallViewController.backgroundColor
            setBackgroundColor(background)
            isLightBackground = !background.isDarkColor()
            tintColor = background.readableOverlayColor()
            tintColorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    Color.argb(64, Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor)),
                    BlendModeCompat.SRC_IN,
                )
        }

        visibility =
            when (loadingState) {
                is PaywallLoadingState.LoadingURL ->
                    VISIBLE

                else -> GONE
            }
        paywallViewController.addView(this)
        layoutParams =
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        checkForOrientationChanges()
    }

    fun hideShimmer() {
        visibility = View.GONE
        stopShimmer()
    }

    fun showShimmer() {
        visibility = View.VISIBLE
        startShimmer()
    }

    fun checkForOrientationChanges() {
        val config = resources.configuration
        setDrawableBasedOnOrientation(config)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentOrientation == newConfig.orientation) {
            return
        }

        currentOrientation = newConfig.orientation

        setDrawableBasedOnOrientation(newConfig)

        // Redraw the view
        invalidate()
    }

    private fun setDrawableBasedOnOrientation(config: Configuration) {
        vectorDrawable =
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                landscapeDrawable
            } else {
                portraitDrawable
            }

        // Update the mask bitmap with the new drawable

        if (vectorDrawable != drawable) {
            setImageDrawable(vectorDrawable)
        }
        colorFilter = tintColorFilter
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        viewTreeObserver.addOnGlobalLayoutListener(
            object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    // Ensure we only listen once to prevent multiple calls
                    viewTreeObserver.removeOnGlobalLayoutListener(this)

                    // Now start the shimmer
                    startShimmer()
                }
            },
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopShimmer()
    }

    fun startShimmer() {
        stopShimmer()
        animator =
            ValueAnimator.ofFloat(1f, 0.5f, 1f).apply {
                duration = 3000
                repeatCount = ValueAnimator.INFINITE
                interpolator = PathInterpolator(0.4f, 0f, 0.6f, 1f)
                addUpdateListener { animation ->
                    drawable.alpha = (animation.animatedValue as Float * 255).toInt()
                }
                start()
            }
    }

    fun stopShimmer() {
        animator?.cancel()
    }
}
