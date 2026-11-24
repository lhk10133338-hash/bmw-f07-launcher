package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The slim bar at the bottom of each pane (CarCar's "handle under the widget").
 *
 * Nothing is shown but a short line, so the app underneath stays readable.
 * Tap => pane menu, long-press => fullscreen, swipe up => pane menu.
 */
class PaneHandle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onSwipeUp: (() -> Unit)? = null

    private val paint = Paint().apply {
        color = LINE_COLOR
        isAntiAlias = true
    }

    private val density = resources.displayMetrics.density
    private val swipeThreshold = 14f * density
    private var downY = Float.NaN

    init {
        isClickable = true
        isLongClickable = true
        setBackgroundColor(0x00000000)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lineWidth = 34f * density
        val lineHeight = 1.6f * density
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawRect(cx - lineWidth / 2f, cy - lineHeight / 2f, cx + lineWidth / 2f, cy + lineHeight / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = event.y
            MotionEvent.ACTION_UP -> {
                val dy = if (downY.isNaN()) 0f else downY - event.y
                downY = Float.NaN
                if (dy > swipeThreshold) {
                    onSwipeUp?.invoke()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> downY = Float.NaN
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val LINE_COLOR = 0x66FFFFFF
    }
}
