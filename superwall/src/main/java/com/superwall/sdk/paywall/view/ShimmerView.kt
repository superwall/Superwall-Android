package com.superwall.sdk.paywall.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.VectorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.superwall.sdk.R

// Using ImageView instead of AppCompatImageView intentionally —
// this view is created with applicationContext to avoid activity leaks,
// and AppCompatImageView requires a Theme.AppCompat context.
@SuppressLint("AppCompatCustomView")
class ShimmerView(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context.applicationContext, attrs),
    PaywallShimmerView {
    private var animator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var vectorDrawable: VectorDrawable? = null

    // Highlight band swept across the skeleton. SRC_ATOP keeps the band
    // clipped to the skeleton shapes drawn inside the same saveLayer.
    private val shimmerPaint =
        Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
    private val shaderMatrix = Matrix()
    private var gradientWidth = 0f
    private var shimmerTranslate = 0f

    companion object {
        internal const val TAG = "ShimmerView"
        private const val SWEEP_DURATION_MS = 1500L
        private const val PULSE_DURATION_MS = 3000L
        private const val GRADIENT_WIDTH_RATIO = 0.4f
    }

    private var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    private val portraitDrawable: VectorDrawable? by lazy {
        ContextCompat.getDrawable(getContext(), R.drawable.portrait_shimmer_skeleton) as? VectorDrawable
    }
    private val landscapeDrawable: VectorDrawable? by lazy {
        ContextCompat.getDrawable(getContext(), R.drawable.landscape_shimmer_skeleton) as? VectorDrawable
    }
    var tintColorFilter: android.graphics.ColorFilter? = null

    init {
        setTag(TAG)
        checkForOrientationChanges()
    }

    var background: Int = 0
    var isLightBackground: Boolean = false
    var tintColor: Int = 0

    override fun hideShimmer() {
        visibility = View.GONE
        stopShimmer()
    }

    override fun showShimmer() {
        visibility = View.VISIBLE
        startShimmer()
    }

    override fun checkForOrientationChanges() {
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

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Gradient width is derived from the view width, so rebuild on resize.
        if (w != oldw && animator != null) {
            startShimmer()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (animator == null || shimmerPaint.shader == null) {
            super.onDraw(canvas)
            return
        }
        // The background color is drawn outside this layer, so the band only
        // tints the skeleton pixels, not the whole surface.
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        shaderMatrix.setTranslate(shimmerTranslate, 0f)
        shimmerPaint.shader.setLocalMatrix(shaderMatrix)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shimmerPaint)
        canvas.restoreToCount(checkpoint)
    }

    fun startShimmer() {
        stopShimmer()
        if (width == 0) {
            // Not laid out yet; the global layout listener will start us.
            return
        }
        gradientWidth = width * GRADIENT_WIDTH_RATIO
        val base = if (tintColor != 0) tintColor else Color.WHITE
        val highlightAlpha = if (isLightBackground) 128 else 96
        val highlight = Color.argb(highlightAlpha, Color.red(base), Color.green(base), Color.blue(base))
        shimmerPaint.shader =
            LinearGradient(
                0f,
                0f,
                gradientWidth,
                0f,
                intArrayOf(Color.TRANSPARENT, highlight, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        animator =
            ValueAnimator.ofFloat(-gradientWidth, width.toFloat()).apply {
                duration = SWEEP_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    shimmerTranslate = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        // Gentle skeleton fade layered under the sweep. Animates imageAlpha
        // (drawable only) so the view background doesn't pulse with it.
        pulseAnimator =
            ValueAnimator.ofFloat(1f, 0.5f, 1f).apply {
                duration = PULSE_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = PathInterpolator(0.4f, 0f, 0.6f, 1f)
                addUpdateListener {
                    imageAlpha = ((it.animatedValue as Float) * 255).toInt()
                }
                start()
            }
    }

    fun stopShimmer() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        imageAlpha = 255
        invalidate()
    }
}
