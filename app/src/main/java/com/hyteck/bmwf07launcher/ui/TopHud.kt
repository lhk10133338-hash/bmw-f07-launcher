package com.hyteck.bmwf07launcher.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.hyteck.bmwf07launcher.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v4.13 — 상단 BMW 마크(상시)와 Orbitron 시계(런처가 쉬는 동안).
 *
 * The user's request of 2026-11-27: *"상단에 BMW 마크는 항상 떠 있게, 그 옆에 시계. 메뉴에 들어가면
 * 시계는 안 나오게끔, 마크는 계속."*
 *
 * ### Why this is a view inside every screen instead of a `TYPE_APPLICATION_OVERLAY` window
 *
 * A system overlay would follow the mark over other apps too, but it needs
 * `SYSTEM_ALERT_WINDOW` on the standard build and a separate window lifecycle on the unit; a refused
 * or revoked permission raises `BadTokenException`/`SecurityException` at exactly the moment the
 * launcher must not fail (start-up, a return from the reverse camera). The requirement — mark over
 * the split panes, their inline menus, the app drawer and the settings screen — is fully met by the
 * launcher's own three screens, so the mark is drawn as the topmost child of each of them
 * ([attach]). Nothing runs outside the launcher on the user's behalf.
 *
 * The two panes are `SurfaceView`s; a plain view of the same window is drawn above a `SurfaceView`'s
 * surface (the pane handles already rely on that), so the mark also floats over a navigation app in
 * a pane, in split and in fullscreen.
 *
 * ### Touch
 *
 * Every node in `view_top_hud.xml` is `clickable=false`, `focusable=false`,
 * `focusableInTouchMode=false` and has no background, and the view added here is not clickable
 * either. A non-clickable view does not consume the event, so `ViewGroup.dispatchTouchEvent` hands
 * it to the next sibling underneath: the pane's touch route, the transparent pane handle, the dock
 * tile or the drawer row behave exactly as before.
 *
 * ### Size
 *
 * Sizes are pixels measured from the screen's short side, not `dp`: the launcher's own density is
 * scaled by the "런처 화면 크기" setting ([com.hyteck.bmwf07launcher.util.ScaledActivity]), while the
 * mark must keep the 72 px / 38 px proportion of the 2560×720 mockup whatever that setting is.
 */
class TopHud private constructor(
    private val activity: Activity,
    private val root: View,
    private val mark: ImageView,
    private val clock: TextView
) {

    private val handler = Handler(Looper.getMainLooper())

    /** Orbitron has digits and Latin letters only, so the format is fixed to `PM 7:32`. */
    private val format = SimpleDateFormat("a h:mm", Locale.US)

    private var markHeight = 0

    /** False while the boot intro owns the screen: the intro is never covered (v4.13). */
    private var showing = true

    /** The clock's own switch; the mark stays regardless of it. */
    private var clockWanted = true

    private val tick = object : Runnable {
        override fun run() {
            renderClock()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun setVisible(visible: Boolean) {
        if (showing == visible) {
            if (visible) renderClock()
            return
        }
        showing = visible
        applyVisibility()
        if (visible) renderClock()
    }

    fun setClockVisible(visible: Boolean) {
        if (clockWanted == visible) return
        clockWanted = visible
        applyVisibility()
        // The clock may have been away across a minute boundary; show the right time at once.
        if (visible) renderClock()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun install(showClock: Boolean) {
        // A unit whose font resource cannot be read keeps the platform font: the clock is a
        // convenience, never a reason for the launcher to fail.
        try {
            ResourcesCompat.getFont(activity, R.font.orbitron)?.let { clock.typeface = it }
        } catch (e: Exception) {
            Log.w(TAG, "orbitron font unavailable, using the default typeface", e)
        }
        clockWanted = showClock
        try {
            applyMetrics()
        } catch (e: Exception) {
            Log.w(TAG, "top HUD metrics not applied", e)
        }
        // The mark's measured width is only known after a layout pass, and it changes when the
        // launcher screen is rebuilt at another density: re-place the clock whenever it does.
        mark.addOnLayoutChangeListener { _, _, _, right, _, oldRight, _, _, _ ->
            if (right != oldRight) placeClock()
        }
        renderClock()
        applyVisibility()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun applyMetrics() {
        val metrics = activity.resources.displayMetrics
        val base = min(metrics.widthPixels, metrics.heightPixels).toFloat()
        markHeight = (base * MARK_RATIO).roundToInt().coerceAtLeast(24)
        (mark.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.width = FrameLayout.LayoutParams.WRAP_CONTENT
            params.height = markHeight
            mark.layoutParams = params
        }
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, markHeight * CLOCK_RATIO)
        clock.setShadowLayer(
            markHeight * SHADOW_RATIO,
            markHeight * SHADOW_OFFSET_RATIO,
            markHeight * SHADOW_OFFSET_RATIO,
            CLOCK_SHADOW
        )
        placeClock()
    }

    /**
     * The mark is centred on the screen (`top|center_horizontal`); the clock sits in the free area
     * to its left, ending just outside the mark's left edge with a small gap.
     *
     * The clock is pinned to the right edge of the parent, so the margin that puts its right edge at
     * `markLeft - gap` is `(rootWidth + markWidth) / 2 + gap` (the mockup: 2560, mark 284 px, gap
     * 24 px → 1446 px, right edge at x = 1114, the mark starting at 1138).
     */
    private fun placeClock() {
        // Before the first layout pass the widths are still 0; the mark's own aspect ratio and the
        // screen width give the same numbers, so the clock is never drawn at the wrong edge for a
        // frame and then jumped into place.
        val markWidth = mark.width.takeIf { it > 0 } ?: expectedMarkWidth()
        val rootWidth = root.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        if (rootWidth <= 0 || markWidth <= 0 || markHeight <= 0) return
        val params = clock.layoutParams as? FrameLayout.LayoutParams ?: return
        val gap = (markHeight * CLOCK_GAP_RATIO).roundToInt()
        val margin = (rootWidth + markWidth) / 2 + gap
        if (params.rightMargin != margin) {
            params.rightMargin = margin
            clock.layoutParams = params
        }
    }

    /** The width the mark will have once laid out: the drawable's own ratio at [markHeight]. */
    private fun expectedMarkWidth(): Int {
        val drawable = mark.drawable ?: return 0
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicHeight <= 0) return 0
        return (drawable.intrinsicWidth.toLong() * markHeight / intrinsicHeight).toInt()
    }

    private fun applyVisibility() {
        root.visibility = if (showing) View.VISIBLE else View.GONE
        clock.visibility = if (showing && clockWanted) View.VISIBLE else View.GONE
        if (showing) placeClock()
    }

    private fun renderClock() {
        if (!showing || !clockWanted) return
        clock.text = format.format(Date())
    }

    companion object {
        private const val TAG = "MNHud"

        /** 72 px of 720 (the user's mockup ratio). */
        private const val MARK_RATIO = 0.10f

        /** 38 px of 720: Orbitron at 38 px has a 27 px cap height, as in the mockup. */
        private const val CLOCK_RATIO = 0.528f

        /** 24 px of 72 between the clock and the mark. */
        private const val CLOCK_GAP_RATIO = 0.33f

        /** The subtle drop shadow that keeps the white digits readable on a bright map. */
        private const val SHADOW_RATIO = 0.055f
        private const val SHADOW_OFFSET_RATIO = 0.014f
        private const val CLOCK_SHADOW = 0xCC000000.toInt()

        /** A minute needs no more than this; the text is simply re-read. */
        private const val TICK_MS = 5_000L

        /**
         * Puts the mark (and, when asked, the clock) on top of [activity]'s content.
         *
         * It is the last child of `android.R.id.content`, so it is drawn above the panes, their
         * inline menus and the screen's own lists, and above the boot intro's layer — which is why
         * the intro hides it explicitly through [setVisible]. A screen that cannot be given the
         * mark (no content view, an inflate failure) returns `null` and simply lives without it.
         */
        fun attach(activity: Activity, showClock: Boolean = true): TopHud? {
            return try {
                val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.view_top_hud, content, false)
                content.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP
                    )
                )
                TopHud(
                    activity,
                    view,
                    view.findViewById(R.id.topHudMark),
                    view.findViewById(R.id.topHudClock)
                ).also { it.install(showClock) }
            } catch (e: Exception) {
                Log.w(TAG, "top HUD not shown", e)
                null
            }
        }
    }
}
