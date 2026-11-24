package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.hyteck.bmwf07launcher.util.DEFAULT_LONG_PRESS_MS
import kotlin.math.abs

/**
 * The slim bar at the bottom of each pane (CarCar's "handle under the widget").
 *
 * Nothing is shown but a short line, so the app underneath stays readable. The strip itself is
 * taller than the line (20dp) so it is easy to hit with a thumb on a car screen.
 *
 * - short tap => pane menu
 * - long press (~0.8 s) => pane menu right away, with a haptic tick (v3.2: the menu must open
 *   without waiting for the finger to lift, and the strip is the only part of a pane that a
 *   floating app window may leave reachable)
 * - swipe up => pane menu
 *
 * The view consumes its own gestures, so a long press never turns into a click afterwards and
 * the pane menu cannot be opened twice by one finger.
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

    private val paint = Paint().apply {
        color = LINE_COLOR
        isAntiAlias = true
    }

    private val density = resources.displayMetrics.density
    private val swipeThreshold = 14f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = Float.NaN
    private var downX = Float.NaN
    private var moved = false
    private var longFired = false

    private val longPressRunnable = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    init {
        isClickable = true
        isLongClickable = true
        setBackgroundColor(0x00000000)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lineWidth = 44f * density
        val lineHeight = 2f * density
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawRect(
            cx - lineWidth / 2f,
            cy - lineHeight / 2f,
            cx + lineWidth / 2f,
            cy + lineHeight / 2f,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                downX = event.x
                moved = false
                longFired = false
                isPressed = true
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
                if (downY.isNaN()) return true
                val dy = downY - event.y
                downY = Float.NaN
                downX = Float.NaN
                if (!longFired && !moved && dy <= swipeThreshold) {
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
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

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LINE_COLOR = 0x88FFFFFF.toInt()
    }
}
