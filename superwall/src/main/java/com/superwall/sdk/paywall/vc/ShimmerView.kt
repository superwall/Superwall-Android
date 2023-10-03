package com.superwall.sdk.paywall.vc

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.VectorDrawable
import android.util.AttributeSet
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.superwall.sdk.R

class ShimmerView(
    context: Context,
    backgroundColor: Int,
    val isLightBackground: Boolean,
    val tintColor: Int,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val paint = Paint()
    private var gradientWidth: Float = 0f
    private var animator: ValueAnimator? = null
    private var vectorDrawable: VectorDrawable? = null
    private var maskBitmap: Bitmap? = null

    init {
        setBackgroundColor(backgroundColor)
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        checkForOrientationChanges()
    }

    fun checkForOrientationChanges() {
        val config = resources.configuration
        setDrawableBasedOnOrientation(config)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        setDrawableBasedOnOrientation(newConfig)

        // Redraw the view
        invalidate()
    }

    private fun setDrawableBasedOnOrientation(config: Configuration) {
        // Check the orientation of the screen
        val drawableResId = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            R.drawable.landscape_shimmer_skeleton
        } else {
            R.drawable.portrait_shimmer_skeleton
        }

        // 25% alpha and tint color
        val tintWithAlpha = Color.argb(64, Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor))

        val imageDrawable = ContextCompat.getDrawable(context, drawableResId)?.mutate() as? VectorDrawable
        imageDrawable?.setColorFilter(tintWithAlpha, PorterDuff.Mode.SRC_IN)
        setImageDrawable(imageDrawable)

        // 100% alpha and tint color
        vectorDrawable = ContextCompat.getDrawable(context, drawableResId) as? VectorDrawable
        vectorDrawable?.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)

        // Update the mask bitmap with the new drawable
        vectorDrawable?.let {
            maskBitmap = Bitmap.createBitmap(it.intrinsicWidth, it.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(maskBitmap!!)
            it.setBounds(0, 0, canvas.width, canvas.height)
            it.draw(canvas)
        }
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // Ensure we only listen once to prevent multiple calls
                viewTreeObserver.removeOnGlobalLayoutListener(this)

                // Now start the shimmer
                startShimmer()
            }
        })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopShimmer()
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas ?: return

        val saveLayer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // Draw the shimmer effect
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Prepare the paint for masking
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

        // Draw the masking bitmap
        maskBitmap?.let {
            val srcRect = Rect(0, 0, it.width, it.height)
            val dstRect = Rect(0, 0, width, height)
            canvas.drawBitmap(it, srcRect, dstRect, paint)
        }

        // Clear the Xfermode
        paint.xfermode = null

        // Restore the canvas
        canvas.restoreToCount(saveLayer)
    }

    fun startShimmer() {
        if (width > 0) {
            val shimmerColor1 = 0x00000000 // Fully transparent
            val shimmerColor2: Int

            if (isLightBackground) {
                shimmerColor2 = Color.argb(128, 255, 255, 255) // White with 50% opacity
            } else {
                shimmerColor2 = 0x33FFFFFF // White with 20% opacity
            }

            val shimmerRatio = 0.2f
            gradientWidth = width * shimmerRatio

            val gradient = LinearGradient(
                -gradientWidth, 0f, 0f, 0f,
                intArrayOf(shimmerColor1, shimmerColor2, shimmerColor1),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )

            paint.shader = gradient

            animator = ValueAnimator.ofFloat(-gradientWidth, width + gradientWidth)
                .apply {
                    repeatCount = ValueAnimator.INFINITE
                    duration = 1500
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val translateX = it.animatedValue as Float
                        paint.shader.setLocalMatrix(
                            android.graphics.Matrix().also { it.setTranslate(translateX, 0f) }
                        )
                        invalidate()
                    }
                    start()
                }
        }
    }

    fun stopShimmer() {
        animator?.cancel()
    }
}