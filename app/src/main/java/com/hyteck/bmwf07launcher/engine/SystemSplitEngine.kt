package com.hyteck.bmwf07launcher.engine

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService

/**
 * Places each app as its own free-form task in one of the two panes.
 *
 * ## What changed in 3.0, and why
 *
 * v2.1 resized the panes by starting the app again on every divider change, with a "repair" pass
 * that launched each window a second time and a repeat on every resume. 네이버지도 and QQ음악HD
 * answered that with `응답하지 않음` and were killed (user report, 2026-11-24). A normal app cannot
 * resize another app's task — `resizeTask` needs the shell/root identity — so this engine does the
 * only thing that is allowed and safe:
 *
 *  1. **One launch per app selection.** Picking an app (or a preset) starts it exactly once with
 *     `setLaunchBounds(rect)`. The two panes are started 350 ms apart.
 *  2. **One placement per gesture, at most.** Changing the ratio re-places each window once
 *     (350 ms apart) when the divider is released or a ratio preset is tapped. Dragging never
 *     touches another app's window, and nothing is repeated or "repaired".
 *  3. **One raise on return.** Coming back from the settings screen or the app drawer raises the
 *     two running tasks again (no bounds, no restart), so they are not left behind the launcher.
 *  4. If the firmware refuses free-form bounds, the apps simply open where the system puts them:
 *     the launcher reports it once and never retries, so no app is ever restarted in a loop.
 *
 * ## 3.1
 *
 * The free-form window is also kept off the launcher's dock. v3.0 left only 2dp between the
 * dock and the left pane, and a window's own border/shadow reached past that onto the dock,
 * where it swallowed the first tap on the dock buttons. [setDockKeepOut] carries the dock's edge
 * here, and every rectangle is clamped against it before a launch and before a re-place.
 */
class SystemSplitEngine(
    private val context: Context,
    private val onMessage: (String) -> Unit
) : SplitEngine {

    override val inline: Boolean = false
    override val supportsDpi: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    private val packages = HashMap<Side, String>()
    private val bounds = HashMap<Side, Rect>()

    /** Rectangle each window was last placed at, so an unchanged gesture costs no launch. */
    private val applied = HashMap<Side, Rect>()

    private val queue = ArrayList<Runnable>()

    /** Screen-x edges the launcher's dock keeps out of reach; 0 means no dock on that side. */
    private var dockMinLeft = 0
    private var dockMaxRight = 0

    private var released = false
    private var lastLaunchAt = 0L
    private var launchFailureReported = false
    private var accessibilityHintShown = false

    // ---------------------------------------------------------------- launching

    override fun launch(side: Side, packageName: String) {
        if (released) return
        packages[side] = packageName
        applied.remove(side)
        lastLaunchAt = SystemClock.uptimeMillis()
        place(side, packageName, bounds[side], reorder = false)

        // The new window came to the front; a single call 350 ms later keeps the other pane in
        // its own rectangle instead of letting the fresh window cover it.
        val other = if (side == Side.LEFT) Side.RIGHT else Side.LEFT
        val otherPkg = packages[other] ?: return
        val otherRect = bounds[other] ?: return
        post(STEP_MS) {
            if (packages[other] == otherPkg) place(other, otherPkg, otherRect, reorder = true)
        }
    }

    /**
     * Remembers where the launcher's dock is, so no floating window is ever placed or launched on
     * top of it (v3.1: an app window that covered the dock stole the first tap from its buttons).
     */
    override fun setDockKeepOut(minLeft: Int, maxRight: Int) {
        if (released) return
        dockMinLeft = minLeft.coerceAtLeast(0)
        dockMaxRight = maxRight.coerceAtLeast(0)
    }

    override fun applyBounds(left: Rect, right: Rect, commit: Boolean) {
        if (released) return
        bounds[Side.LEFT] = keepOffDock(Side.LEFT, left)
        bounds[Side.RIGHT] = keepOffDock(Side.RIGHT, right)
        // While the divider is being dragged nothing outside this app is touched at all.
        if (!commit) return

        var delay = 0L
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            val pkg = packages[side] ?: continue
            val rect = bounds[side] ?: continue
            if (rect.width() <= 32 || rect.height() <= 32) continue
            if (sameRect(applied[side], rect)) continue
            post(delay) {
                if (packages[side] == pkg) place(side, pkg, rect, reorder = true)
            }
            delay += STEP_MS
        }
    }

    /**
     * Brings the two running apps back in front after the launcher was covered by the settings
     * screen or the app drawer. Their size is left alone: this only raises the existing task.
     */
    override fun resume(sides: Set<Side>) {
        if (released || sides.isEmpty()) return
        // A launch that just happened already put the window in front.
        if (SystemClock.uptimeMillis() - lastLaunchAt < LAUNCH_GRACE_MS) return

        var delay = 0L
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            if (!sides.contains(side)) continue
            val pkg = packages[side] ?: continue
            post(delay) {
                if (packages[side] == pkg) {
                    place(side, pkg, null, reorder = true)
                }
            }
            delay += STEP_MS
        }
    }

    override fun clear(side: Side) {
        packages.remove(side)
        applied.remove(side)
    }

    override fun setDensity(side: Side, dpi: Int) {
        // A free-form window draws with the density of the display it runs on.
    }

    override fun release() {
        released = true
        for (runnable in queue) handler.removeCallbacks(runnable)
        queue.clear()
        packages.clear()
        applied.clear()
    }

    // ------------------------------------------------------------------ one call

    /**
     * Starts or raises one app, optionally demanding a rectangle. This is the only place in the
     * launcher that starts another app's activity, and it is called once per user gesture.
     */
    private fun place(side: Side, packageName: String, rect: Rect?, reorder: Boolean) {
        if (released) return
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            onMessage("앱을 찾을 수 없습니다: $packageName")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (reorder) {
            // Raise the task that is already running; it is not recreated.
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        } else {
            // A separate task per pane, so the same app can sit in both slots.
            intent.addFlags(
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }

        val options = ActivityOptions.makeBasic()
        // The rectangle is re-checked here as well: a launch may use bounds that were stored
        // before the dock moved or came back, and the window must still stay off the dock.
        val target = if (rect == null) null else keepOffDock(side, rect)
        if (target != null && target.width() > 32 && target.height() > 32) {
            options.setLaunchBounds(target)
            applied[side] = Rect(target)
        }
        applyFreeformMode(options)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            } catch (ignored: Throwable) {
                // Optional hint only.
            }
        }

        val bundle = options.toBundle()
        val service = LauncherAccessibilityService.instance
        try {
            if (service != null) {
                // The pane that is opened while the launcher is already behind needs this.
                service.startActivity(intent, bundle)
            } else {
                context.startActivity(intent, bundle)
            }
            launchFailureReported = false
        } catch (e: Exception) {
            if (!launchFailureReported) {
                launchFailureReported = true
                onMessage("앱 실행에 실패했습니다: ${e.message}")
            }
        }
        if (service == null && !accessibilityHintShown && packages.size > 1) {
            accessibilityHintShown = true
            onMessage("한쪽 창만 크기가 바뀌면 설정에서 접근성 서비스를 켜 주세요.")
        }
    }

    /**
     * Asks for a free-form window so the bounds are honoured even when the device is not already
     * in split-screen. This option is a hidden API on some releases, so failure is ignored.
     */
    private fun applyFreeformMode(options: ActivityOptions) {
        try {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType!!
            )
            method.invoke(options, WINDOWING_MODE_FREEFORM)
        } catch (ignored: Throwable) {
            // Not available: launch with bounds only.
        }
    }

    private fun post(delay: Long, block: () -> Unit) {
        val runnable = Runnable { if (!released) block() }
        queue.add(runnable)
        handler.postDelayed(runnable, delay)
    }

    /**
     * Moves [rect] out of the dock's strip, keeping it the same size where possible. A rectangle
     * the dock would squeeze down to an unusable width is left as it is.
     */
    private fun keepOffDock(side: Side, rect: Rect): Rect {
        if (dockMinLeft <= 0 && dockMaxRight <= 0) return Rect(rect)
        val safe = Rect(rect)
        if (side == Side.LEFT && dockMinLeft > 0 && safe.left < dockMinLeft) {
            safe.left = dockMinLeft
        }
        if (side == Side.RIGHT && dockMaxRight > 0 && safe.right > dockMaxRight) {
            safe.right = dockMaxRight
        }
        if (safe.width() <= 32 || safe.height() <= 32) return Rect(rect)
        return safe
    }

    private fun sameRect(a: Rect?, b: Rect?): Boolean {
        if (a == null || b == null) return false
        return kotlin.math.abs(a.left - b.left) <= 2 && kotlin.math.abs(a.top - b.top) <= 2 &&
            kotlin.math.abs(a.right - b.right) <= 2 && kotlin.math.abs(a.bottom - b.bottom) <= 2
    }

    companion object {
        private const val WINDOWING_MODE_FREEFORM = 5

        /** Gap between the two panes so the window manager handles each launch separately. */
        private const val STEP_MS = 350L

        /** A resume right after a launch would only start the same activity twice. */
        private const val LAUNCH_GRACE_MS = 900L
    }
}
