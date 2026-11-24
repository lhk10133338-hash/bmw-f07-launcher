package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.hyteck.bmwf07launcher.util.DEFAULT_LONG_PRESS_MS
import kotlin.math.abs

/**
 * The slim strip at the bottom of each pane, with a small translucent grey bar in the middle.
 *
 * v3.4 (user request, 2026-11-24): "화면 길게 누르는 방식은 안 되는 거 같은니까 화면 하단에 투명한
 * 회색 바 작은 걸 비치하고 그걸 누르게 하자". The strip used to show only a faint white line, so
 * there was nothing to aim at on a car screen. It now draws a clearly visible grey pill
 * (96dp x 6dp, #669E9E9E, brighter while pressed) in the middle of a 28dp tall strip, and a
 * single short tap on it opens that pane's menu:
 *
 * - short tap => pane menu, immediately on finger-up (the main gesture since v3.4)
 * - long press (~0.8 s) => pane menu as well, with a haptic tick (kept from v3.2)
 * - swipe up => pane menu as well
 *
 * The whole strip is the touch target, not just the pill, so a thumb does not have to be exact.
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

    private val density = resources.displayMetrics.density
    private val swipeThreshold = 14f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** The visible grey pill: 96dp wide, 6dp tall, rounded ends. */
    private val barWidth = 96f * density
    private val barHeight = 6f * density
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

    init {
        isClickable = true
        isLongClickable = true
        setBackgroundColor(0x00000000)
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
                if (downY.isNaN()) return true
                val dy = downY - event.y
                downY = Float.NaN
                downX = Float.NaN
                if (!longFired && !moved && dy <= swipeThreshold) {
                    // v3.4: one short tap on the strip is enough to open the pane menu.
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                setBarPressed(false)
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
        /** The small translucent grey bar the user asked for (#669E9E9E). */
        private const val BAR_COLOR = 0x669E9E9E

        /** Brighter while the finger is on it, so the touch is visible on a car screen. */
        private const val BAR_COLOR_PRESSED = 0xCCCFCFCF.toInt()
    }
}
