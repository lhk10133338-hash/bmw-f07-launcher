package com.hyteck.bmwf07launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Built-in BMW M style intro used when the user has not chosen a personal .mp4.
 * Three slanted M stripes sweep across the ultra-wide screen, then the wordmark
 * fades in. Tapping anywhere skips it (handled by the hosting activity).
 */
class IntroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val kickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A8A8A")
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private var progress = 0f

    var onFinished: (() -> Unit)? = null

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2300L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { value ->
            progress = value.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onFinished?.invoke()
            }
        })
    }

    fun start() {
        animator.cancel()
        progress = 0f
        invalidate()
        animator.start()
    }

    fun stop() {
        animator.cancel()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val stripeHeight = h * 0.055f
        val gap = stripeHeight * 0.5f
        val totalHeight = stripeHeight * 3f + gap * 2f
        val top = h * 0.5f - totalHeight * 0.5f - h * 0.04f
        val skew = h * 0.16f
        val stripeWidth = w * 0.30f
        val travel = w + stripeWidth + skew * 2f
        val startX = -stripeWidth - skew
        val x = startX + travel * progress

        val colors = intArrayOf(COLOR_LIGHT_BLUE, COLOR_BLUE, COLOR_RED)
        for (i in 0..2) {
            stripePaint.color = colors[i]
            val y = top + i * (stripeHeight + gap)
            val shift = i * h * 0.012f
            path.reset()
            path.moveTo(x + shift, y)
            path.lineTo(x + stripeWidth + shift, y)
            path.lineTo(x + stripeWidth - skew + shift, y + stripeHeight)
            path.lineTo(x - skew + shift, y + stripeHeight)
            path.close()
            canvas.drawPath(path, stripePaint)
        }

        val textAlpha = ((progress - 0.42f) / 0.32f).coerceIn(0f, 1f)
        if (textAlpha > 0f) {
            textPaint.alpha = (textAlpha * 255f).toInt()
            textPaint.textSize = h * 0.115f
            textPaint.letterSpacing = 0.16f
            canvas.drawText("BMW M PERFORMANCE", w / 2f, h * 0.78f, textPaint)

            kickerPaint.alpha = (textAlpha * 190f).toInt()
            kickerPaint.textSize = h * 0.045f
            kickerPaint.letterSpacing = 0.34f
            canvas.drawText("F07  ·  530d xDRIVE", w / 2f, h * 0.875f, kickerPaint)
        }
    }

    companion object {
        private val COLOR_LIGHT_BLUE = Color.parseColor("#43B0E5")
        private val COLOR_BLUE = Color.parseColor("#1C69D4")
        private val COLOR_RED = Color.parseColor("#E2231A")
    }
}
