package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.hyteck.bmwf07launcher.util.DEFAULT_LONG_PRESS_MS
import kotlin.math.abs

/**
 * The slim handle at the bottom of each pane (v4.0: a transparent overlay).
 *
 * Up to v3.4 the handle owned a 28dp strip below the app, so the app never reached the bottom edge
 * of the screen. The user asked for a split screen that fills the panel without top/bottom space
 * (2026-11-26), so that strip is gone: the SurfaceView is laid out edge to edge and this view is
 * drawn on top of its bottom edge. It paints nothing but a small translucent pill (72dp x 4dp),
 * fades itself to 28% after a few seconds so it does not sit on the app picture, and comes back to
 * full brightness the moment a finger lands on it:
 *
 * - short tap => pane menu, immediately on finger-up
 * - long press (~0.8 s) => pane menu as well, with a haptic tick
 * - swipe up => pane menu as well
 *
 * The whole 18dp strip is the touch target, not just the pill, so a thumb does not have to be exact.
 * The view consumes its own gestures, so a long press never turns into a click afterwards and the
 * pane menu cannot be opened twice by one finger.
 */
class PaneHandle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onSwipeUp: (() -> Unit)? = null

    /** Fired after the finger has rested on the strip for [DEFAULT_LONG_PRESS_MS]. */
    var onLongPress: (() -> Unit)? = null

    /** Fired for a short touch; the click listener still works for accessibility and DPAD. */
    var onTap: (() -> Unit)? = null

    private val barPaint = Paint().apply { isAntiAlias = true }

    private val handler = Handler(Looper.getMainLooper())

    private val density = resources.displayMetrics.density
    private val swipeThreshold = 14f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** The visible pill: 72dp wide, 4dp tall, rounded ends. */
    private val barWidth = 72f * density
    private val barHeight = 4f * density
    private val barRadius = barHeight / 2f
    private val barRect = RectF()

    private var downY = Float.NaN
    private var downX = Float.NaN
    private var moved = false
    private var longFired = false
    private var pressed = false

    private val longPressRunnable = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    /** Dims the pill while nothing touches it, so the app picture stays unobstructed. */
    private val dimRunnable = Runnable {
        animate().alpha(DIM_ALPHA).setDuration(DIM_FADE_MS).start()
    }

    init {
        isClickable = true
        isLongClickable = true
        setBackgroundColor(0x00000000)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alpha = 1f
        scheduleDim()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(dimRunnable)
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, DIM_DELAY_MS)
    }

    private fun wake() {
        handler.removeCallbacks(dimRunnable)
        if (alpha < 1f) animate().alpha(1f).setDuration(120L).start()
        scheduleDim()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        barRect.set(
            cx - barWidth / 2f,
            cy - barHeight / 2f,
            cx + barWidth / 2f,
            cy + barHeight / 2f
        )
        barPaint.color = if (pressed) BAR_COLOR_PRESSED else BAR_COLOR
        canvas.drawRoundRect(barRect, barRadius, barRadius, barPaint)
    }

    private fun setBarPressed(value: Boolean) {
        if (pressed == value) return
        pressed = value
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
                downY = event.y
                downX = event.x
                moved = false
                longFired = false
                isPressed = true
                setBarPressed(true)
                postDelayed(longPressRunnable, DEFAULT_LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (downY.isNaN()) return true
                val dy = downY - event.y
                val dx = abs(event.x - downX)
                if (!moved && (dx > touchSlop || abs(dy) > touchSlop)) {
                    moved = true
                    // A swipe is not a long press; the menu opens when the finger lifts instead.
                    removeCallbacks(longPressRunnable)
                }
                if (dy > swipeThreshold) {
                    // Upward swipe: the menu comes now rather than on release.
                    removeCallbacks(longPressRunnable)
                    setBarPressed(false)
                    if (!longFired) {
                        longFired = true
                        onSwipeUp?.invoke()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                setBarPressed(false)
                scheduleDim()
                if (downY.isNaN()) return true
                val dy = downY - event.y
                downY = Float.NaN
                downX = Float.NaN
                if (!longFired && !moved && dy <= swipeThreshold) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                setBarPressed(false)
                scheduleDim()
                downY = Float.NaN
                longFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    companion object {
        /** The small translucent pill of v4.0. */
        private const val BAR_COLOR = 0x55B4B4B4

        /** Brighter while the finger is on it, so the touch is visible on a car screen. */
        private const val BAR_COLOR_PRESSED = 0xCCE6E6E6.toInt()

        /** How long the pill stays bright after the last touch. */
        private const val DIM_DELAY_MS = 4000L

        /** Brightness once nothing has touched the pane for a while. */
        private const val DIM_ALPHA = 0.28f

        private const val DIM_FADE_MS = 400L
    }
}
