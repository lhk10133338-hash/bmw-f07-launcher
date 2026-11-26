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
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.shizuku.ShizukuBridge
import com.hyteck.bmwf07launcher.util.dp
import kotlin.math.abs

/**
 * Renders both apps inside the launcher window: each pane owns a virtual display that is
 * drawn into a SurfaceView, so the two apps really are two windows of this launcher.
 *
 * ## Which version this is
 *
 * v4.3. It is **v4.0's output path** (the only one that has ever shown a picture on the car unit)
 * plus the fixes v4.0 actually needed. Everything v4.1 added around the display's lifetime —
 * `setDisplayState` on create/resume, rebuilding a display because `DisplayManager.getDisplay()`
 * did not list it, and the 400 ms re-apply loop — is gone: v4.0 put a picture on the right pane for
 * a whole session with none of it, and v4.1 stopped showing anything at all after adding it.
 *
 * ## What v4.3 fixes (both panes black on the car unit, 2026-11-26)
 *
 *  1. **`setFixedSize` no longer feeds its own dead surface to the display.** v4.2 pinned the
 *     buffer and, in the very same pass, called `create`/`setSurface`/`resize` with the surface
 *     that `setFixedSize` had just scheduled for destruction: a `SurfaceView` throws the current
 *     Surface away and builds another one asynchronously (`surfaceDestroyed` → `surfaceCreated`),
 *     so the display was handed a surface that died one frame later — a black pane. Now the
 *     buffer is pinned only when its `surfaceFrame` really has another size, and after a pin the
 *     pass **ends**: the next scheduled pass runs with the new, stable surface.
 *  2. **A failed route says so on screen.** If the platform privilege behind the trusted display
 *     is missing, or `create`/the app launch fails, a short status line appears over the pane
 *     (a toast alone vanishes before anyone can read it in a car) and the reason is logged. A
 *     healthy pane never shows it.
 *
 * ## What v4.0 does (kept)
 *
 *  1. The pane buffer is pinned to the pane rectangle with [SurfaceHolder.setFixedSize], then the
 *     display is created/resized to the same rectangle. Buffer and display stay on one size.
 *  2. A ratio change only resizes the display; the display and the app on it are never released.
 *     The app gets an ordinary configuration change instead of being started again.
 *  3. On create the size is applied first and the output surface last
 *     ([SystemDisplayHost.create]).
 *
 * ## What is kept from v4.2
 *
 *  1. **Nothing is done from inside a SurfaceView callback.** v4.0 called
 *     `setFixedSize`/`resize` synchronously from `surfaceChanged`, i.e. while the view was still
 *     updating its own surface; on the car unit that left the **left pane black for good** after
 *     a ratio change (user report 2026-11-26) while the right pane kept working. The callbacks now
 *     only schedule a pass on the main looper, and `setFixedSize` runs there, once per real size
 *     change.
 *  2. **A pane's output surface is never detached** with `setSurface(null)` while the pane lives.
 *     Detaching a virtual display's surface is "similar to turning off the screen" (platform
 *     documentation), and with `DESTROY_CONTENT_ON_REMOVAL` the platform throws the running app
 *     away with it — which is exactly how a pane that lost its surface once never came back.
 *     A surface that is being re-created is simply replaced by the next one.
 *
 * A display is rebuilt only when `VirtualDisplay.resize` has failed twice in a row: that is the
 * one case where the display itself is no longer answering. A display that merely is not listed
 * by `DisplayManager` is left alone (v4.1 rebuilt those on every pass and lost both panes).
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

    /** The rectangle the display was last asked for (v4.0's early-out check). */
    private val sizes = HashMap<Side, IntArray>()

    /** The rectangle the SurfaceView's buffer was last pinned to. */
    private val pinned = HashMap<Side, IntArray>()

    /**
     * [width, height, attempts] of the last `setFixedSize` try, so a buffer that does not take the
     * pin is not re-pinned (and its surface not re-created) on every single pass.
     */
    private val pinTries = HashMap<Side, IntArray>()

    /** A one-line status over a pane, shown only when something actually failed (v4.3). */
    private val statusLabels = HashMap<Side, TextView>()

    private val packages = HashMap<Side, String>()
    private val densities = HashMap<Side, Int>()

    /** How often a pane waited for the Shizuku touch service before falling back to itself. */
    private val waits = HashMap<Side, Int>()

    /** Short, bounded retries while a pane's SurfaceView is between two surfaces. */
    private val surfaceWaits = HashMap<Side, Int>()

    /** Consecutive `resize` refusals, so only a display that stopped answering is rebuilt. */
    private val resizeFailures = HashMap<Side, Int>()

    /** The one pending read-back per pane, replaced on every new resize (a drag makes many). */
    private val readBacks = HashMap<Side, Runnable>()

    /** Per side, so resizing the left display never swallows the right one's callback. */
    private val syncing = HashSet<Side>()
    private var unavailableReported = false
    private var launchErrorReported = false
    private var privilegeReported = false
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
            val displayId = displayIds[side] ?: -1
            if (displayId >= 0) touchSink?.invoke(displayId, event)
            if (event.actionMasked == MotionEvent.ACTION_UP) touched.performClick()
            true
        }
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // v4.3: only schedule. The surface this callback announces is created inside
                // SurfaceView's own update; the scheduled pass runs when it is complete and uses
                // it there, the same rule `setFixedSize` follows below.
                scheduleSync()
            }

            /**
             * v4.2: schedules only. Calling into the display (or `setFixedSize`) from here ran
             * inside SurfaceView's own surface update — the re-entrancy that wedged the left pane
             * on the car unit. The view has finished laying itself out by the time the scheduled
             * pass runs.
             */
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                scheduleSync()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // The display, its app and its size stay as they are; the surface that is going
                // away is replaced in surfaceCreated. v4.0 detached the output here, which turns
                // the pane off and, with DESTROY_CONTENT_ON_REMOVAL, takes its app down with it —
                // a pane that hit this once never showed a picture again.
                Log.i(TAG, "pane $side surface destroyed; keeping display ${displayIds[side]}")
                surfaces.remove(side)
                pinned.remove(side)
                scheduleSync()
            }
        })
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (right - left > 0 && bottom - top > 0) scheduleSync()
            }
        }
    }

    /** Puts the pane's new surface on its display, keeping the display and the app. */
    private fun attachSurface(side: Side, surface: Surface) {
        val displayId = displayIds[side] ?: return
        if (surfaces[side] === surface) return
        hosts[side]?.setSurface(displayId, surface)
        surfaces[side] = surface
        // The app itself is untouched: it has been running on this display all along and simply
        // gets its picture back. The rectangle is re-applied by the pass scheduled here.
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
        // v4.3: the SystemUID APK only draws a picture while its process really is the platform
        // UID; on a unit that ended up with a plain app identity, this falls through to a display
        // no other app may be started on, and the pane stays black. Say it once, on screen.
        if (!privilegeReported && context.packageName.endsWith(SYSTEMUID_SUFFIX)) {
            privilegeReported = true
            Log.e(
                TAG,
                "SystemUID build without ADD_TRUSTED_DISPLAY (pkg=${context.packageName}, " +
                    "uid=${android.os.Process.myUid()}) — falling back"
            )
            showStatus(side, "시스템 권한 미인식\n(ADD_TRUSTED_DISPLAY 없음)")
            onMessage("시스템 권한이 인식되지 않았습니다 — 플랫폼 서명으로 설치한 APK인지 확인해 주세요")
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
     * The launcher came back in front (or a surface came back): put the current surface on the
     * display again and re-apply both rectangles. No app is started and no display is rebuilt.
     */
    override fun resume(sides: Set<Side>) {
        if (released) return
        for (side in sides) {
            if (packages[side] == null) continue
            val surface = viewOf(side).holder.surface
            if (surface != null && surface.isValid) attachSurface(side, surface)
        }
        scheduleSync()
        handler.removeCallbacks(followUpSync)
        handler.postDelayed(followUpSync, FOLLOW_UP_MS)
    }

    private fun syncSide(side: Side) {
        if (released) return
        val view = viewOf(side)
        // v4.0: no display work while this pane (or the whole screen) is not on screen. A pane
        // hidden by fullscreen is frozen, not resized, and its rectangle is re-applied when it
        // becomes visible again.
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
        try {
            val view = viewOf(side)
            val holder = view.holder
            val surface = holder.surface
            if (surface == null || !surface.isValid) {
                // Between two surfaces. surfaceCreated/surfaceChanged schedule the real pass;
                // these few retries only cover a callback that was missed.
                val tries = (surfaceWaits[side] ?: 0) + 1
                surfaceWaits[side] = tries
                if (tries <= MAX_SURFACE_WAITS) scheduleSync()
                return
            }
            surfaceWaits.remove(side)

            val host = hostFor(side) ?: run {
                // Still waiting for the Shizuku touch service.
                scheduleSync()
                return
            }
            waits.remove(side)

            val density = densityOf(side)
            val existing = displayIds[side]
            val record = sizes[side]
            val pinnedSize = pinned[side]
            val pinnedSame = pinnedSize != null && pinnedSize[0] == width && pinnedSize[1] == height

            if (existing != null && record != null &&
                record[0] == width && record[1] == height && record[2] == density &&
                surfaces[side] === surface && pinnedSame
            ) {
                return
            }

            // 1) The pane's buffer must be the pane rectangle before the display is pointed at
            //    it. Only a buffer that really has another size is pinned, and a pin always ends
            //    this pass (v4.3).
            if (!pinnedSame) {
                val frame = holder.surfaceFrame
                val frameSame = abs(frame.width() - width) <= SIZE_TOLERANCE &&
                    abs(frame.height() - height) <= SIZE_TOLERANCE
                if (frameSame) {
                    // The SurfaceView is already the pane rectangle (it is `match_parent`, so this
                    // is the normal case). Pinning it again would only make the platform destroy
                    // this surface and build another one — the v4.2 black pane. Just record it.
                    pinned[side] = intArrayOf(width, height)
                } else if (pinTriesFor(side, width, height) <= MAX_PIN_ATTEMPTS &&
                    runCatching { holder.setFixedSize(width, height) }.isSuccess
                ) {
                    pinned[side] = intArrayOf(width, height)
                    Log.i(TAG, "pane $side buffer pinned to ${width}x$height; waiting for the new surface")
                    // v4.3 — the whole point: `setFixedSize` has just scheduled the current
                    // Surface for destruction. Anything handed to the display from this pass (the
                    // create below, `setSurface`, `resize`) would point it at that dead surface.
                    // The new surface arrives through surfaceCreated/surfaceChanged, which
                    // schedule the pass that continues with it.
                    scheduleSync()
                    return
                } else {
                    // Pinning did not take: use the surface the view actually has.
                    pinned[side] = intArrayOf(width, height)
                }
            }

            if (existing != null) {
                // 2) The display is asked for the same rectangle. It keeps its app.
                if (surfaces[side] !== surface) {
                    host.setSurface(existing, surface)
                    surfaces[side] = surface
                }
                if (!host.resize(existing, width, height, density)) {
                    // A display that refuses the size may simply be between two states; the next
                    // layout pass (or the follow-up pass) tries again. Only a display that keeps
                    // refusing is rebuilt, because that is the only way to get a picture back.
                    val failures = (resizeFailures[side] ?: 0) + 1
                    resizeFailures[side] = failures
                    sizes.remove(side)
                    Log.w(TAG, "pane $side resize to ${width}x$height refused ($failures)")
                    if (failures >= MAX_RESIZE_FAILURES) {
                        Log.w(TAG, "pane $side display $existing stopped answering; rebuilding")
                        resizeFailures.remove(side)
                        dropDisplay(side)
                        scheduleSync()
                    }
                    return
                }
                resizeFailures.remove(side)
                sizes[side] = intArrayOf(width, height, density)
                Log.i(TAG, "pane $side display $existing resized to ${width}x$height @$density")
                scheduleReadBack(side, width, height, density, 0)
                return
            }

            // 3) The first pass for this pane: create the display, then start its app.
            val created = host.create(displayName(side), width, height, density, surface)
            if (created < 0) {
                // v4.3: say which route refused, on screen and in the log — a pane that stays
                // black gives nothing else to go on.
                Log.e(
                    TAG,
                    "pane $side create failed via ${hostName(host)} " +
                        "(trusted=${Privileges.canCreateTrustedDisplay(context)}, " +
                        "systemUid=${Privileges.isSystemUid()})"
                )
                showStatus(side, "가상 화면을 만들 수 없습니다\n${hostName(host)}")
                reportUnavailable()
                return
            }
            displayIds[side] = created
            surfaces[side] = surface
            sizes[side] = intArrayOf(width, height, density)
            resizeFailures.remove(side)
            hideStatus(side)
            Log.i(
                TAG,
                "pane $side display $created created at ${width}x$height @$density " +
                    "via ${hostName(host)}, surface ${surface.hashCode()}"
            )
            packages[side]?.let { launchOnDisplay(side, it, created, reorder = true) }
            scheduleReadBack(side, width, height, density, 0)
        } finally {
            syncing.remove(side)
        }
    }

    /**
     * Reads the display's real rectangle once, a moment after it was created or resized, and
     * **only writes a log line** — unless the display is there and clearly kept another size, in
     * which case the size is applied one more time. Nothing is detached, released or rebuilt from
     * here: v4.1's read-back dropped displays that `DisplayManager` did not list for this UID and
     * that is what took both panes off the screen.
     */
    private fun scheduleReadBack(side: Side, width: Int, height: Int, density: Int, attempt: Int) {
        readBacks.remove(side)?.let { handler.removeCallbacks(it) }
        val task = Runnable { readBack(side, width, height, density, attempt) }
        readBacks[side] = task
        handler.postDelayed(task, READ_BACK_DELAY_MS)
    }

    private fun readBack(side: Side, width: Int, height: Int, density: Int, attempt: Int) {
        if (released) return
        val displayId = displayIds[side] ?: return
        val current = sizes[side] ?: return
        if (current[0] != width || current[1] != height || current[2] != density) return
        val display = try {
            displayManager.getDisplay(displayId)
        } catch (t: Throwable) {
            null
        }
        if (display == null) {
            // Not listed for this UID (the pane lives in its own display group). That says
            // nothing about whether it draws, so the pane is left exactly as it is.
            Log.i(TAG, "pane $side display $displayId is not listed by DisplayManager")
            return
        }
        val real = Point()
        val measured = runCatching { display.getRealSize(real) }.isSuccess
        Log.i(
            TAG,
            "pane $side display $displayId reports ${if (measured) "${real.x}x${real.y}" else "no size"}, " +
                "asked ${width}x$height, state ${display.state}"
        )
        if (!measured) return
        if (abs(real.x - width) <= SIZE_TOLERANCE && abs(real.y - height) <= SIZE_TOLERANCE) return
        if (attempt >= MAX_READ_BACK_ATTEMPTS) return
        val host = hosts[side] ?: return
        Log.w(TAG, "pane $side kept ${real.x}x${real.y}; applying ${width}x$height again")
        if (host.resize(displayId, width, height, density)) {
            sizes[side] = intArrayOf(width, height, density)
        }
        scheduleReadBack(side, width, height, density, attempt + 1)
    }

    /**
     * The pane's next `setFixedSize` try for this rectangle: 1 the first time, then 2, 3… A new
     * rectangle starts again at 1.
     */
    private fun pinTriesFor(side: Side, width: Int, height: Int): Int {
        val record = pinTries[side]
        if (record == null || record[0] != width || record[1] != height) {
            pinTries[side] = intArrayOf(width, height, 1)
            return 1
        }
        record[2] += 1
        return record[2]
    }

    private fun hostName(host: DisplayHost): String = when (host) {
        is SystemDisplayHost -> "플랫폼 권한 경로(TRUSTED)"
        is ShizukuDisplayHost -> "Shizuku(shell) 경로"
        else -> "앱 자체 권한 경로"
    }

    /**
     * v4.3: a short status line over a pane, for the cases where the pane cannot show a picture
     * for a reason the log only would keep to itself. A toast is gone before anyone in a car has
     * read it, so the line stays until the pane has a display again ([hideStatus]).
     */
    private fun showStatus(side: Side, text: String) {
        handler.post {
            if (released) return@post
            val view = viewOf(side)
            val parent = view.parent as? ViewGroup ?: return@post
            val label = statusLabels.getOrPut(side) {
                TextView(parent.context).apply {
                    setTextColor(0xFFFFD24A.toInt())
                    textSize = 11f
                    setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
                    setBackgroundColor(0xCC000000.toInt())
                    maxLines = 3
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    ).apply { topMargin = context.dp(6) }
                }.also { parent.addView(it) }
            }
            label.text = text
            label.visibility = View.VISIBLE
        }
    }

    private fun hideStatus(side: Side) {
        handler.post {
            statusLabels[side]?.visibility = View.GONE
        }
    }

    /**
     * Forgets one pane's display so the next pass builds a fresh one. Called only from
     * [sync] after [MAX_RESIZE_FAILURES] refusals in a row — a display that has stopped answering
     * has already lost its content, so nothing is thrown away that was still there.
     */
    private fun dropDisplay(side: Side) {
        val displayId = displayIds.remove(side) ?: return
        hosts[side]?.release(displayId)
        surfaces.remove(side)
        sizes.remove(side)
        pinned.remove(side)
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
        launchOnDisplay(side, packageName, displayId, reorder = false)
    }

    override fun clear(side: Side) {
        // The app keeps its display (another app's task cannot be killed from here); the pane's
        // empty overlay simply covers it until a new app is picked.
        packages.remove(side)
    }

    /**
     * Fullscreen hides the other pane: its display is frozen (`setDisplayState(false)`) instead of
     * being released or left drawing, so the app keeps its state and the GPU is not asked for a
     * picture nobody sees. Nothing else in this engine touches the display state.
     */
    override fun setPaneVisible(side: Side, visible: Boolean) {
        val displayId = displayIds[side] ?: return
        hosts[side]?.setState(displayId, visible)
        // Coming back from fullscreen: its rectangle may have changed while it was frozen.
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

    private fun launchOnDisplay(side: Side, packageName: String, displayId: Int, reorder: Boolean) {
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
            // v4.3: the one failure that reads as "a black pane" from the driver's seat — the
            // display never received the app. Name it on the pane and in the log.
            Log.e(TAG, "pane $side launch of $packageName on display $displayId failed", e)
            showStatus(side, "앱을 이 창에서 열 수 없습니다\n${e.javaClass.simpleName}")
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
        pinned.clear()
        pinTries.clear()
        statusLabels.values.forEach { label -> (label.parent as? ViewGroup)?.removeView(label) }
        statusLabels.clear()
        packages.clear()
        waits.clear()
        surfaceWaits.clear()
        resizeFailures.clear()
        readBacks.clear()
    }

    companion object {
        private const val TAG = "MNLauncher"
        private const val SYNC_DELAY_MS = 64L
        private const val FOLLOW_UP_MS = 220L
        private const val READ_BACK_DELAY_MS = 400L

        /** One re-apply after a real size mismatch, then the pane is left alone. */
        private const val MAX_READ_BACK_ATTEMPTS = 1

        /** A pixel of rounding between the pane rectangle and the display metrics is normal. */
        private const val SIZE_TOLERANCE = 2

        /** ~2.6 s of waiting for the Shizuku touch service before the local host is used. */
        private const val MAX_WAIT_TRIES = 40

        /** ~0.4 s of retries for a pane surface that is being re-created. */
        private const val MAX_SURFACE_WAITS = 6

        /** Refusals in a row before a pane's display is rebuilt (it is not answering any more). */
        private const val MAX_RESIZE_FAILURES = 2

        /** `setFixedSize` tries per pane rectangle before the surface the view has is used. */
        private const val MAX_PIN_ATTEMPTS = 2

        /** The platform-signed build's application id suffix (see app/build.gradle.kts). */
        private const val SYSTEMUID_SUFFIX = ".systemuid"
    }
}
