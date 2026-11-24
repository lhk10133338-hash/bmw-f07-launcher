package com.hyteck.bmwf07launcher.engine

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService

/**
 * Launches each app as its own task with explicit bounds, using the system free-form /
 * multi-window implementation.
 *
 * A normal app cannot move another app's window, so a ratio change is applied by starting
 * that app again with `FLAG_ACTIVITY_REORDER_TO_FRONT` and the new launch bounds: the task is
 * brought back to the front (without recreating the activity when the app supports it) and the
 * window manager lays it out in the new rectangle.
 *
 * Three reports shaped this class (2026-11-24):
 *  - "the divider drag resizes only one app": two bound launches issued in the same call
 *    collapse into a single transition and only the last window keeps its new rectangle. Both
 *    panes are now moved one after another ([STEP_MS] apart) and the pass is repeated once, so
 *    a dropped launch still ends up in its own half.
 *  - "apps vanish after opening the settings or the app drawer": the opaque launcher window
 *    comes back in front of the floating app windows, so [resume] puts both windows back into
 *    their panes when the launcher returns.
 *  - "one app covers the other": each launch is staggered and re-applied, so the windows never
 *    end up sharing the same rectangle.
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
    private val applied = HashMap<Side, Rect>()
    private val queue = ArrayList<Runnable>()

    private var generation = 0

    /** Rectangle pair that has been moved (and settled) already. */
    private var appliedKey: String? = null

    /** Rectangle pair whose move sequence is running right now. */
    private var movingKey: String? = null

    private var released = false
    private var launchFailureReported = false
    private var accessibilityHintShown = false

    override fun launch(side: Side, packageName: String) {
        packages[side] = packageName
        applied.remove(side)
        val rect = bounds[side]
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            // No rectangle known yet: the next applyBounds() must place this window.
            appliedKey = null
        }
        start(side, packageName, rect ?: Rect(), reorder = false)

        // Keep the other pane's window in its own rectangle, so a freshly opened app never
        // ends up covering it.
        val other = if (side == Side.LEFT) Side.RIGHT else Side.LEFT
        if (packages[other] != null && applied[other] != bounds[other]) {
            val gen = ++generation
            schedule(gen, STEP_MS) { move(other, force = true) }
        }
    }

    override fun applyBounds(left: Rect, right: Rect, commit: Boolean) {
        bounds[Side.LEFT] = Rect(left)
        bounds[Side.RIGHT] = Rect(right)
        if (!commit || released) return
        val key = keyOf()
        // Layout callbacks and onResume report the same rectangles again; moving the windows
        // once per change is enough (repeating it would restart them on every frame).
        if (key == movingKey || key == appliedKey) return
        moveAll(listOf(Side.LEFT, Side.RIGHT), key, repair = true)
    }

    override fun resume(sides: Set<Side>) {
        if (released || sides.isEmpty()) return
        val targets = sides.filter { packages[it] != null }
        if (targets.isEmpty()) return
        for (side in targets) applied.remove(side)
        moveAll(targets, key = null, repair = false)
    }

    override fun clear(side: Side) {
        packages.remove(side)
        applied.remove(side)
        appliedKey = null
    }

    override fun setDensity(side: Side, dpi: Int) {
        // System windows use the density of the display they run on.
    }

    override fun release() {
        released = true
        cancelQueue()
        packages.clear()
        applied.clear()
    }

    // --------------------------------------------------------------- applying

    /**
     * Moves the given windows into their rectangles, one launch at a time (staggered, so the
     * window manager handles each one), and — when [repair] is set — repeats the pass once so a
     * window that ignored the first request is corrected as well.
     */
    private fun moveAll(sides: List<Side>, key: String?, repair: Boolean) {
        generation++
        val gen = generation
        cancelQueue()
        if (key != null) movingKey = key

        val targets = sides.filter { packages[it] != null && bounds[it] != null }
        if (targets.isEmpty()) {
            movingKey = null
            return
        }

        val moved = HashSet<Side>()
        var delay = 0L
        for (side in targets) {
            val captured = side
            schedule(gen, delay) { if (move(captured, force = false)) moved.add(captured) }
            delay += STEP_MS
        }
        if (repair) {
            // A bound launch that arrives while the previous window is still being laid out is
            // dropped by the window manager, which is what leaves one app at the old size.
            for (side in targets) {
                val captured = side
                schedule(gen, delay) { if (moved.contains(captured)) move(captured, force = true) }
                delay += STEP_MS
            }
        }
        schedule(gen, delay) {
            movingKey = null
            if (key != null) appliedKey = key
        }
    }

    private fun schedule(gen: Int, delay: Long, block: () -> Unit) {
        val runnable = Runnable {
            if (!released && gen == generation) block()
        }
        queue.add(runnable)
        handler.postDelayed(runnable, delay)
    }

    private fun cancelQueue() {
        for (runnable in queue) handler.removeCallbacks(runnable)
        queue.clear()
    }

    /** @return true when a launch was actually issued. */
    private fun move(side: Side, force: Boolean): Boolean {
        val pkg = packages[side] ?: return false
        val rect = bounds[side] ?: return false
        if (rect.width() <= 8 || rect.height() <= 8) return false
        if (!force && applied[side] == rect) return false
        start(side, pkg, rect, reorder = true)
        return true
    }

    private fun keyOf(): String {
        val left = bounds[Side.LEFT]
        val right = bounds[Side.RIGHT]
        return "${left?.toShortString()}|${right?.toShortString()}"
    }

    private fun start(side: Side, packageName: String, rect: Rect, reorder: Boolean) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            onMessage("앱을 찾을 수 없습니다: $packageName")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (reorder) {
            // Bring the running window back without recreating it; the window manager then lays
            // it out into the rectangle we pass.
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        } else {
            // A separate task per pane, so the same app can sit in both slots.
            intent.addFlags(
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }

        val options = ActivityOptions.makeBasic()
        if (rect.width() > 0 && rect.height() > 0) {
            options.setLaunchBounds(rect)
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
        applied[side] = Rect(rect)
        val service = LauncherAccessibilityService.instance
        try {
            if (service != null) {
                // The accessibility service may start activities while the launcher itself is in
                // the background, which the second pane's launch needs.
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
     * Asks for a free-form window so the bounds are honoured even when the device is not
     * already in split-screen. This option is a hidden API on some releases, so failure is
     * ignored (the bounds are still passed).
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

    companion object {
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val STEP_MS = 240L
    }
}
