package com.hyteck.bmwf07launcher.engine

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hyteck.bmwf07launcher.data.Settings

/**
 * Renders both apps inside the launcher window: each pane owns a virtual display that is
 * drawn into a SurfaceView, so the two apps really are two windows of this launcher.
 *
 * Why the old version left a pane small forever (user report, 2026-11-24):
 *  - every ratio change released the display and built a new one, which killed the running
 *    activity and lost the surface; on the way back the pane kept drawing the old small size;
 *  - pausing the launcher destroyed the surface and the display went with it.
 *
 * Now:
 *  1. [SurfaceHolder.setFixedSize] pins the buffer to the exact pane rectangle, then an existing
 *     display is resized with [VirtualDisplay.resize] — never released on a ratio change.
 *  2. [VirtualDisplay.setSurface] attaches the new surface after a pause/return instead of
 *     releasing the display, so the apps on it survive a trip to another app and back.
 *  3. A resize only changes the display and the surface buffer. The app on that display receives
 *     an ordinary configuration change — it is never started again (that repetition is what
 *     turned a divider drag into an ANR in v2.1).
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

    private val displays = HashMap<Side, VirtualDisplay>()
    private val sizes = HashMap<Side, IntArray>()
    private val packages = HashMap<Side, String>()
    private val densities = HashMap<Side, Int>()

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
        register(Side.LEFT, leftSurface)
        register(Side.RIGHT, rightSurface)
    }

    private fun register(side: Side, view: SurfaceView) {
        // v3.4: the pane's SurfaceView is the only place the display's app can be touched, so
        // every event that lands on it is forwarded to the display the pane is showing.
        view.isClickable = true
        view.setOnClickListener { /* the touch itself is forwarded in the listener below */ }
        view.setOnTouchListener { touched, event ->
            val displayId = displays[side]?.display?.displayId ?: -1
            if (displayId >= 0) touchSink?.invoke(displayId, event)
            if (event.actionMasked == MotionEvent.ACTION_UP) touched.performClick()
            true
        }
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attachSurface(side, holder.surface)
                scheduleSync()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    sync(side, width, height)
                } else {
                    scheduleSync()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                handler.removeCallbacks(syncRunnable)
                // Keep the display and its apps; only detach the dead surface.
                displays[side]?.let { display ->
                    runCatching { display.surface = null }
                }
                sizes.remove(side)
            }
        })
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (right - left > 0 && bottom - top > 0) scheduleSync()
            }
        }
    }

    private fun attachSurface(side: Side, surface: Surface) {
        val display = displays[side] ?: return
        runCatching {
            if (display.surface !== surface) display.surface = surface
        }
        sizes.remove(side)
        // The app itself is untouched: it has been running on this display all along and simply
        // gets its picture back.
    }

    private fun viewOf(side: Side): SurfaceView =
        if (side == Side.LEFT) leftSurface else rightSurface

    private fun densityOf(side: Side): Int =
        (densities[side] ?: if (side == Side.LEFT) settings.leftDpi else settings.rightDpi)
            .coerceIn(Settings.MIN_DPI, Settings.MAX_DPI)

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
            val display = displays[side]
            if (display != null && surface != null && surface.isValid && display.surface !== surface) {
                runCatching { display.surface = surface }
                sizes.remove(side)
            }
        }
        scheduleSync()
        handler.removeCallbacks(followUpSync)
        handler.postDelayed(followUpSync, FOLLOW_UP_MS)
    }

    private fun syncSide(side: Side) {
        if (released) return
        val view = viewOf(side)
        if (!view.isShown) return
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
        val holder = viewOf(side).holder
        val surface = holder.surface
        if (surface == null || !surface.isValid) {
            syncing.remove(side)
            return
        }

        val density = densityOf(side)
        val existing = displays[side]
        val record = sizes[side]
        if (existing != null && record != null &&
            record[0] == width && record[1] == height && record[2] == density &&
            existing.surface === surface
        ) {
            syncing.remove(side)
            return
        }

        try {
            // 1) surface buffer = pane rectangle, 2) display = same rectangle.
            runCatching { holder.setFixedSize(width, height) }
            if (existing != null) {
                if (existing.surface !== surface) {
                    runCatching { existing.surface = surface }
                }
                existing.resize(width, height, density)
                sizes[side] = intArrayOf(width, height, density)
            } else {
                val display = createDisplay(side, width, height, density, surface)
                if (display == null) {
                    reportUnavailable()
                    return
                }
                displays[side] = display
                sizes[side] = intArrayOf(width, height, density)
                packages[side]?.let { launchOnDisplay(it, display.display.displayId, reorder = true) }
            }
        } catch (e: Exception) {
            // A display that refuses resize() is recreated once at the new size.
            displays.remove(side)?.release()
            sizes.remove(side)
            val display = createDisplay(side, width, height, density, surface)
            if (display == null) {
                reportUnavailable()
                return
            }
            displays[side] = display
            sizes[side] = intArrayOf(width, height, density)
            packages[side]?.let { launchOnDisplay(it, display.display.displayId, reorder = false) }
        } finally {
            syncing.remove(side)
        }
    }

    private fun reportUnavailable() {
        if (unavailableReported) return
        unavailableReported = true
        onUnavailable("이 기기는 가상 화면 실행을 허용하지 않습니다. '시스템 분할' 방식으로 전환합니다.")
    }

    private fun createDisplay(
        side: Side,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface
    ): VirtualDisplay? {
        val name = "bmw-f07-" + (if (side == Side.LEFT) "left" else "right")
        // v3.4: a trusted display may host another app's activity; PUBLIC is what lets the window
        // manager place an ordinary task on it at all; SUPPORTS_TOUCH is required for injected or
        // dispatched touches. Not every firmware grants TRUSTED to an ordinary app, so each
        // weaker set is tried in turn and a refusal just moves on.
        val flagSets = intArrayOf(
            FLAG_TRUSTED or FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_TRUSTED or FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH or FLAG_OWN_CONTENT_ONLY,
            FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_SUPPORTS_TOUCH,
            0
        )
        for (flags in flagSets) {
            try {
                val display = displayManager.createVirtualDisplay(
                    name,
                    width,
                    height,
                    density,
                    surface,
                    flags
                )
                if (display != null) return display
            } catch (ignored: Exception) {
                // Try the next flag set.
            }
        }
        return null
    }

    override fun launch(side: Side, packageName: String) {
        packages[side] = packageName
        val display = displays[side]
        if (display == null) {
            scheduleSync()
            return
        }
        launchOnDisplay(packageName, display.display.displayId, reorder = false)
    }

    override fun clear(side: Side) {
        // The app keeps its display (another app's task cannot be killed from here); the pane's
        // empty overlay simply covers it until a new app is picked.
        packages.remove(side)
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
        handler.removeCallbacks(syncRunnable)
        handler.removeCallbacks(followUpSync)
        for (display in displays.values) {
            display.release()
        }
        displays.clear()
        sizes.clear()
        packages.clear()
    }

    companion object {
        private const val SYNC_DELAY_MS = 64L
        private const val FOLLOW_UP_MS = 220L

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC / _SUPPORTS_TOUCH / _OWN_CONTENT_ONLY /
        // _TRUSTED (hidden constants).
        private const val FLAG_PUBLIC = 1
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_TRUSTED = 1 shl 10
    }
}
