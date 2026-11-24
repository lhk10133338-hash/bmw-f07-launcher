package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The centre divider: nothing but a one-pixel line until it is touched.
 *
 * Drag => free ratio between 20% and 80%. Tap or long-press => the compact ratio menu.
 */
class DividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onRatioChange: ((Int, Boolean) -> Unit)? = null
    var onOpenRatioMenu: (() -> Unit)? = null

    private val linePaint = Paint().apply {
        color = IDLE_COLOR
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private var startX = 0f
    private var dragging = false
    private var moved = false
    private var longFired = false

    private val longPressRunnable = Runnable {
        longFired = true
        performLongClick()
    }

    init {
        isClickable = true
        isLongClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val lineWidth = (1.5f * resources.displayMetrics.density).coerceAtLeast(1f)
        val inset = h * 0.05f
        val cx = w / 2f
        canvas.drawRect(cx - lineWidth / 2f, inset, cx + lineWidth / 2f, h - inset, linePaint)
    }

    fun setActive(active: Boolean) {
        linePaint.color = if (active) ACTIVE_COLOR else IDLE_COLOR
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                moved = false
                longFired = false
                startX = event.rawX
                parent?.requestDisallowInterceptTouchEvent(true)
                setActive(true)
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                if (!moved && abs(event.rawX - startX) > touchSlop) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (moved) emitRatio(event.rawX, false)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                handler.removeCallbacks(longPressRunnable)
                setActive(false)
                if (moved) {
                    emitRatio(event.rawX, true)
                } else if (!longFired) {
                    performClick()
                }
                moved = false
                longFired = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                handler.removeCallbacks(longPressRunnable)
                longFired = false
                setActive(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onOpenRatioMenu?.invoke()
        return true
    }

    override fun performLongClick(): Boolean {
        onOpenRatioMenu?.invoke()
        return true
    }

    private fun emitRatio(rawX: Float, commit: Boolean) {
        val container = parent as? View ?: return
        val location = IntArray(2)
        container.getLocationOnScreen(location)
        val width = container.width
        if (width <= 0) return
        val ratio = (((rawX - location[0]) / width) * 100f).roundToInt()
            .coerceIn(SplitLayout.MIN_RATIO, SplitLayout.MAX_RATIO)
        onRatioChange?.invoke(ratio, commit)
    }

    companion object {
        private const val IDLE_COLOR = 0x59FFFFFF
        private const val ACTIVE_COLOR = 0xFF43B0E5.toInt()
    }
}
