package com.hyteck.bmwf07launcher.engine

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.shizuku.ShizukuBridge
import kotlin.math.abs

/**
 * Renders both apps inside the launcher window: each pane owns a virtual display that is
 * drawn into a SurfaceView, so the two apps really are two windows of this launcher.
 *
 * History that shaped this file:
 *  - v3.x released the display on every ratio change, which killed the running activity (the
 *    pane came back at its old small size). A resize now keeps the display and its app.
 *  - v4.0 pinned the pane buffer with `SurfaceHolder.setFixedSize` and did the display work
 *    inside `surfaceChanged`. On the car unit that left the **left pane black for good** as soon
 *    as the ratio changed (user report, 2026-11-26): `setFixedSize` from inside SurfaceView's own
 *    callback re-enters the view's surface update, and a pinned buffer no longer follows the
 *    view's layout, so the display and its output surface end up on different sizes with nothing
 *    driving them back together.
 *
 * What the original CarCar Launcher does (read out of `La8/c0` / `Lw7/g` with dexdump), and what
 * this engine now does too:
 *  1. The display is created without a surface; the pane's output surface is attached **before**
 *     the size is changed, never after (`setSurface` then `resize`), and the surface is never
 *     detached with `setSurface(null)` while the pane is alive — that only tears down the output
 *     the running app is drawing into.
 *  2. The display is asked for the exact pane rectangle, and `IWindowManager.dontOverrideDisplayInfo`
 *     keeps the firmware from replacing those metrics.
 *  3. No `setFixedSize`: the SurfaceView is `match_parent`, so the framework resizes its buffer to
 *     the pane on every layout by itself. Pinning it here is what fought the layout.
 *  4. Nothing talks to the display manager from inside a SurfaceView callback. The callbacks only
 *     schedule work, so the view finishes its own surface update first (no re-entrancy, and a
 *     callback for one pane can never be swallowed by the other pane's resize).
 *  5. Every resize is followed by a short verification: if the platform dropped the display, or it
 *     is still reporting another size, the surface is attached again and the size re-applied. A
 *     display that was removed is rebuilt and its app put back — the only way to show something
 *     again, because the platform destroyed that display's content with it.
 *
 * v3.4 — the display is made where the platform allows it to host apps (see [DisplayHost]),
 * and every touch that lands on a pane is forwarded into that pane's display ([touchSink]).
 */
class VirtualDisplayEngine(
    private val context: Context,
    private val leftSurface: SurfaceView,
    private val rightSurface: SurfaceView,
    private val settings: Settings,
    private val onMessage: (String) -> Unit,
    private val onUnavailable: (String) -> Unit,
    /**
     * v3.4: hands one touch received by a pane to the app on that pane's display
     * (`displayId`, event). See [com.hyteck.bmwf07launcher.touch.VirtualTouchRouter].
     */
    private val touchSink: ((Int, MotionEvent) -> Unit)? = null
) : SplitEngine {

    override val inline: Boolean = true
    override val supportsDpi: Boolean = true

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    /** Who creates the displays: the shell side when Shizuku is bound, else this process. */
    private val hosts = HashMap<Side, DisplayHost>()
    private val displayIds = HashMap<Side, Int>()
    private val surfaces = HashMap<Side, Surface>()

    private val sizes = HashMap<Side, IntArray>()
    private val packages = HashMap<Side, String>()
    private val densities = HashMap<Side, Int>()

    /** False only while fullscreen hides this pane: a hidden pane is frozen, not resized. */
    private val visible = HashMap<Side, Boolean>()

    /** How often a pane waited for the Shizuku touch service before falling back to itself. */
    private val waits = HashMap<Side, Int>()

    /** Per side, so resizing the left display never swallows the right one's callback. */
    private val syncing = HashSet<Side>()
    private var unavailableReported = false
    private var launchErrorReported = false
    private var released = false

    private val syncRunnable = Runnable {
        syncSide(Side.LEFT)
        syncSide(Side.RIGHT)
    }

    private val followUpSync = Runnable {
        syncSide(Side.LEFT)
        syncSide(Side.RIGHT)
    }

    init {
        densities[Side.LEFT] = settings.leftDpi
        densities[Side.RIGHT] = settings.rightDpi
        visible[Side.LEFT] = true
        visible[Side.RIGHT] = true
        register(Side.LEFT, leftSurface)
        register(Side.RIGHT, rightSurface)
    }

    private fun register(side: Side, view: SurfaceView) {
        // v3.4: the pane's SurfaceView is the only place the display's app can be touched, so
        // every event that lands on it is forwarded to the display the pane is showing.
        view.isClickable = true
        view.setOnClickListener { /* the touch itself is forwarded in the listener below */ }
        view.setOnTouchListener { touched, event ->
            val displayId = displayIds[side] ?: -1
            if (displayId >= 0) touchSink?.invoke(displayId, event)
            if (event.actionMasked == MotionEvent.ACTION_UP) touched.performClick()
            true
        }
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attachSurface(side, holder.surface)
                scheduleSync()
            }

            /**
             * v4.1: only schedules the work. Touching the display (or `setFixedSize`) here ran
             * inside SurfaceView's own surface update, which is what wedged the pane on the car
             * unit. The view has already laid itself out by the time the scheduled sync runs.
             */
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                scheduleSync()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Keep the display, its app and its output surface glued together: the surface
                // that is going away is replaced by the next one in surfaceCreated. Detaching it
                // here (`setSurface(null)`) makes the platform tear the pane's output down and,
                // with DESTROY_CONTENT_ON_REMOVAL, throw the running app away with it.
                surfaces.remove(side)
                sizes.remove(side)
                scheduleSync()
            }
        })
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (right - left > 0 && bottom - top > 0) scheduleSync()
            }
        }
    }

    private fun attachSurface(side: Side, surface: Surface) {
        val displayId = displayIds[side] ?: return
        if (surfaces[side] === surface) return
        hosts[side]?.setSurface(displayId, surface)
        surfaces[side] = surface
        sizes.remove(side)
        // The app itself is untouched: it has been running on this display all along and simply
        // gets its picture back.
    }

    private fun viewOf(side: Side): SurfaceView =
        if (side == Side.LEFT) leftSurface else rightSurface

    private fun densityOf(side: Side): Int =
        (densities[side] ?: if (side == Side.LEFT) settings.leftDpi else settings.rightDpi)
            .coerceIn(Settings.MIN_DPI, Settings.MAX_DPI)

    /**
     * The host for [side], or null while the Shizuku touch service is still starting.
     *
     * Waiting matters: a display made here cannot host another app on a stock Android, so a pane
     * that starts a second too early would be stuck on the wrong (local) display. The wait is
     * short and bounded; afterwards the local host is used and a refused launch is reported.
     */
    private fun hostFor(side: Side): DisplayHost? {
        hosts[side]?.let { return it }
        // v4.0: the SystemUID build holds ADD_TRUSTED_DISPLAY itself, so it creates CarCar's
        // trusted display in-process, exactly like the original launcher.
        if (Privileges.canCreateTrustedDisplay(context)) {
            val host = SystemDisplayHost(displayManager)
            hosts[side] = host
            return host
        }
        val control = ShizukuBridge.controlOrNull()
        if (control != null) {
            val host = ShizukuDisplayHost(control)
            hosts[side] = host
            return host
        }
        if (ShizukuBridge.awaitingTouchService()) {
            val tries = (waits[side] ?: 0) + 1
            waits[side] = tries
            if (tries < MAX_WAIT_TRIES) return null
        }
        val host = LocalDisplayHost(displayManager)
        hosts[side] = host
        return host
    }

    private fun scheduleSync() {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, SYNC_DELAY_MS)
    }

    override fun applyBounds(left: Rect, right: Rect, commit: Boolean) {
        // The SurfaceViews already have the exact pane size; re-read it and resize the displays.
        // One extra pass follows a commit so a resize that landed between two layout frames is
        // not left behind. Neither pass starts an app.
        scheduleSync()
        if (commit) {
            handler.removeCallbacks(followUpSync)
            handler.postDelayed(followUpSync, FOLLOW_UP_MS)
        }
    }

    /**
     * The launcher came back in front (or a surface came back): re-attach the surfaces, resize
     * both displays and bring the running apps back to the front of their own displays.
     */
    override fun resume(sides: Set<Side>) {
        if (released) return
        for (side in sides) {
            if (packages[side] == null) continue
            val surface = viewOf(side).holder.surface
            if (surface != null && surface.isValid) attachSurface(side, surface)
            if (visible[side] != false) {
                displayIds[side]?.let { hosts[side]?.setState(it, true) }
            }
        }
        scheduleSync()
        handler.removeCallbacks(followUpSync)
        handler.postDelayed(followUpSync, FOLLOW_UP_MS)
    }

    private fun syncSide(side: Side) {
        if (released) return
        val view = viewOf(side)
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        sync(side, width, height)
    }

    private fun sync(side: Side, width: Int, height: Int) {
        if (released) return
        if (!syncing.add(side)) {
            // A callback for this side arrived while its own resize was running: do it again so
            // the newest size wins (this used to be a single flag shared by both panes, which
            // silently dropped the other pane's resize).
            scheduleSync()
            return
        }
        try {
            val holder = viewOf(side).holder
            val surface = holder.surface
            if (surface == null || !surface.isValid) {
                // The pane's surface is being re-created; its own callback schedules the retry.
                Log.i(TAG, "pane $side has no live surface yet")
                scheduleSync()
                return
            }

            val host = hostFor(side) ?: run {
                // Still waiting for the Shizuku touch service.
                scheduleSync()
                return
            }
            waits.remove(side)

            val density = densityOf(side)
            var existing = displayIds[side]
            if (existing != null && displayManager.getDisplay(existing) == null) {
                // The platform removed the display (a lost output surface does that). Its content
                // went with it, so the display is rebuilt at the current size and the app is put
                // back on it — otherwise the pane would stay black for good.
                Log.w(TAG, "pane $side display $existing is gone; rebuilding")
                dropDisplay(side)
                existing = null
            }

            val record = sizes[side]
            if (existing != null && record != null &&
                record[0] == width && record[1] == height && record[2] == density &&
                surfaces[side] === surface
            ) {
                return
            }

            if (existing != null) {
                // CarCar's order: the output surface first, the new size last.
                if (surfaces[side] !== surface) {
                    host.setSurface(existing, surface)
                    surfaces[side] = surface
                }
                if (!host.resize(existing, width, height, density)) {
                    // A display that refuses resize() is rebuilt once at the new size.
                    dropDisplay(side)
                    scheduleSync()
                    return
                }
                sizes[side] = intArrayOf(width, height, density)
                if (visible[side] != false) host.setState(existing, true)
                scheduleVerify(side, width, height, density, 0)
                return
            }

            val created = host.create(displayName(side), width, height, density, surface)
            if (created < 0) {
                reportUnavailable()
                return
            }
            displayIds[side] = created
            surfaces[side] = surface
            sizes[side] = intArrayOf(width, height, density)
            if (visible[side] != false) host.setState(created, true)
            packages[side]?.let { launchOnDisplay(it, created, reorder = true) }
            scheduleVerify(side, width, height, density, 0)
        } finally {
            syncing.remove(side)
        }
    }

    /**
     * Waits a moment, then checks that the display really has the size the pane asked for.
     *
     * A resize that the platform accepted but did not apply (or a display that was removed under
     * us) is what leaves a pane black while the other one keeps working, so the result is read
     * back instead of trusted: the surface is attached again and the size re-applied up to
     * [MAX_VERIFY_ATTEMPTS] times. No app is ever started from here.
     */
    private fun scheduleVerify(side: Side, width: Int, height: Int, density: Int, attempt: Int) {
        handler.postDelayed({ verify(side, width, height, density, attempt) }, VERIFY_DELAY_MS)
    }

    private fun verify(side: Side, width: Int, height: Int, density: Int, attempt: Int) {
        if (released) return
        val displayId = displayIds[side] ?: return
        // A newer size arrived while this check was waiting: that sync owns the pane now, and
        // re-applying the old rectangle here would undo a divider drag.
        val current = sizes[side] ?: return
        if (current[0] != width || current[1] != height || current[2] != density) return
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            Log.w(TAG, "pane $side lost display $displayId after resize")
            dropDisplay(side)
            scheduleSync()
            return
        }
        val real = Point()
        runCatching { display.getRealSize(real) }
        val matches = abs(real.x - width) <= SIZE_TOLERANCE && abs(real.y - height) <= SIZE_TOLERANCE
        if (matches) return
        if (attempt >= MAX_VERIFY_ATTEMPTS) {
            Log.w(TAG, "pane $side kept ${real.x}x${real.y}, wanted ${width}x$height")
            return
        }
        val host = hosts[side] ?: return
        val surface = viewOf(side).holder.surface
        if (surface != null && surface.isValid) {
            Log.i(TAG, "pane $side is ${real.x}x${real.y}; re-applying ${width}x$height")
            host.setSurface(displayId, surface)
            surfaces[side] = surface
            host.resize(displayId, width, height, density)
            sizes[side] = intArrayOf(width, height, density)
            if (visible[side] != false) host.setState(displayId, true)
        }
        scheduleVerify(side, width, height, density, attempt + 1)
    }

    /** Forgets one pane's display so the next sync builds a fresh one (the app is kept). */
    private fun dropDisplay(side: Side) {
        val displayId = displayIds.remove(side) ?: return
        hosts[side]?.release(displayId)
        surfaces.remove(side)
        sizes.remove(side)
    }

    private fun reportUnavailable() {
        if (unavailableReported) return
        unavailableReported = true
        onUnavailable(
            "이 기기는 가상 화면에 앱을 띄우는 것을 허용하지 않습니다. '자유창 안전 모드'로 전환합니다."
        )
    }

    private fun displayName(side: Side): String =
        "bmw-f07-" + (if (side == Side.LEFT) "left" else "right")

    override fun launch(side: Side, packageName: String) {
        packages[side] = packageName
        val displayId = displayIds[side]
        if (displayId == null) {
            scheduleSync()
            return
        }
        launchOnDisplay(packageName, displayId, reorder = false)
    }

    override fun clear(side: Side) {
        // The app keeps its display (another app's task cannot be killed from here); the pane's
        // empty overlay simply covers it until a new app is picked.
        packages.remove(side)
    }

    /**
     * Fullscreen hides the other pane: its display is frozen (`setDisplayState(false)`) instead of
     * being released or left drawing, so the app keeps its state and the GPU is not asked for a
     * picture nobody sees.
     */
    override fun setPaneVisible(side: Side, visible: Boolean) {
        this.visible[side] = visible
        val displayId = displayIds[side] ?: return
        hosts[side]?.setState(displayId, visible)
        // Coming back from fullscreen: the pane may have been left frozen at an old size.
        if (visible) scheduleSync()
    }

    override fun setDensity(side: Side, dpi: Int) {
        densities[side] = dpi.coerceIn(Settings.MIN_DPI, Settings.MAX_DPI)
        val view = viewOf(side)
        if (view.width > 0 && view.height > 0) {
            sync(side, view.width, view.height)
        } else {
            scheduleSync()
        }
    }

    private fun launchOnDisplay(packageName: String, displayId: Int, reorder: Boolean) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            onMessage("앱을 찾을 수 없습니다: $packageName")
            return
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        if (reorder) {
            // Bring the already running activity back without recreating it; the window manager
            // then lays it out into the resized display.
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val options = ActivityOptions.makeBasic()
        options.setLaunchDisplayId(displayId)
        try {
            context.startActivity(intent, options.toBundle())
            launchErrorReported = false
        } catch (e: Exception) {
            if (!launchErrorReported) {
                launchErrorReported = true
                reportUnavailable()
            }
        }
    }

    override fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            val displayId = displayIds[side]
            if (displayId != null) hosts[side]?.release(displayId)
        }
        displayIds.clear()
        surfaces.clear()
        hosts.clear()
        sizes.clear()
        packages.clear()
        waits.clear()
        visible.clear()
    }

    companion object {
        private const val TAG = "MNLauncher"
        private const val SYNC_DELAY_MS = 64L
        private const val FOLLOW_UP_MS = 220L
        private const val VERIFY_DELAY_MS = 400L

        /** One re-attach plus the read-back; after that the pane is left as it is. */
        private const val MAX_VERIFY_ATTEMPTS = 2

        /** A pixel of rounding between the pane rectangle and the display metrics is normal. */
        private const val SIZE_TOLERANCE = 2

        /** ~2.6 s of waiting for the Shizuku touch service before the local host is used. */
        private const val MAX_WAIT_TRIES = 40
    }
}
