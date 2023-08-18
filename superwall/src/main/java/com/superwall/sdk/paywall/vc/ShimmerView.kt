package com.superwall.sdk.paywall.vc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator

class ShimmerView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint()
    private var gradientWidth: Float = 0f
    private var animator: ValueAnimator? = null

    init {
        setBackgroundColor(Color.LTGRAY)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        canvas?.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    fun startShimmer() {
        if (width > 0) {
            val shimmerColor1 = 0x00000000 // Fully transparent
            val shimmerColor2 = 0x33FFFFFF // White with 20% opacity

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