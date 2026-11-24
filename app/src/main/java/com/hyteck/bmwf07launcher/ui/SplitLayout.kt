package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.view.View
import android.widget.FrameLayout
import com.hyteck.bmwf07launcher.R
import com.hyteck.bmwf07launcher.engine.Side

/**
 * The two-pane area. Laid out by an explicit ratio instead of layout weights so a pane always
 * gets exactly the width the divider says, and both directions (shrink and grow) go through the
 * same requestLayout path.
 *
 * Fullscreen keeps the other pane at its normal width and only makes it invisible: setting it
 * GONE/zero-width would destroy the SurfaceView's surface and, with the virtual display engine,
 * take the running app down with it.
 */
class SplitLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var ratio: Int = 50
        set(value) {
            val clamped = value.coerceIn(MIN_RATIO, MAX_RATIO)
            if (field == clamped) return
            field = clamped
            requestLayout()
            invalidate()
        }

    var fullscreen: Side? = null
        set(value) {
            if (field == value) return
            field = value
            updatePaneVisibility()
            requestLayout()
        }

    private val leftPane: View? get() = findViewById(R.id.leftPane)
    private val rightPane: View? get() = findViewById(R.id.rightPane)
    private val dividerView: View? get() = findViewById(R.id.divider)

    private val densityScale = resources.displayMetrics.density

    /** Width of the slim divider strip in pixels. */
    val dividerSize: Int
        get() = (12 * densityScale).toInt().coerceAtLeast(6)

    init {
        setWillNotDraw(true)
    }

    fun paneOf(side: Side): View? = if (side == Side.LEFT) leftPane else rightPane

    /** Pane rectangle in screen coordinates, for engines that place real system windows. */
    fun screenRectOf(side: Side): Rect {
        val rect = Rect()
        val view = paneOf(side) ?: return rect
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        rect.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
        return rect
    }

    private fun updatePaneVisibility() {
        leftPane?.visibility = if (fullscreen == Side.RIGHT) View.INVISIBLE else View.VISIBLE
        rightPane?.visibility = if (fullscreen == Side.LEFT) View.INVISIBLE else View.VISIBLE
        dividerView?.visibility = if (fullscreen == null) View.VISIBLE else View.INVISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val divider = if (fullscreen == null) dividerSize else 0
        val available = (width - divider).coerceAtLeast(2)
        val leftWidth = (available * ratio / 100f).toInt().coerceIn(1, available - 1)
        val rightWidth = (available - leftWidth).coerceAtLeast(1)

        val leftTarget = if (fullscreen == Side.LEFT) width else leftWidth
        val rightTarget = if (fullscreen == Side.RIGHT) width else rightWidth

        measureExactly(leftPane, leftTarget, height)
        measureExactly(dividerView, divider, height)
        measureExactly(rightPane, rightTarget, height)
        setMeasuredDimension(width, height)
    }

    private fun measureExactly(view: View?, width: Int, height: Int) {
        if (view == null) return
        view.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val divider = if (fullscreen == null) dividerSize else 0
        val available = (width - divider).coerceAtLeast(2)
        val leftWidth = (available * ratio / 100f).toInt().coerceIn(1, available - 1)
        val rightWidth = (available - leftWidth).coerceAtLeast(1)

        when (fullscreen) {
            Side.LEFT -> {
                leftPane?.layout(0, 0, width, height)
                rightPane?.layout(0, 0, rightWidth, height)
                dividerView?.layout(width, 0, width, height)
            }

            Side.RIGHT -> {
                rightPane?.layout(0, 0, width, height)
                leftPane?.layout(0, 0, leftWidth, height)
                dividerView?.layout(width, 0, width, height)
            }

            null -> {
                leftPane?.layout(0, 0, leftWidth, height)
                dividerView?.layout(leftWidth, 0, leftWidth + divider, height)
                rightPane?.layout(leftWidth + divider, 0, width, height)
            }
        }
    }

    companion object {
        const val MIN_RATIO = 20
        const val MAX_RATIO = 80
    }
}
