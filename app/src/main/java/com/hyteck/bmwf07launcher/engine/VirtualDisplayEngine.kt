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
import android.view.Display
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
 * ## v4.5 — a pane's display is replaced, never re-pointed (2026-11-26)
 *
 * The car unit reported this for every version from v4.0 to v4.4: both panes draw on a fresh start,
 * the moment the split ratio moves the left pane stays black, and going into the launcher's own
 * settings screen and coming back blacks out both — while killing and restarting the launcher puts
 * the picture back, every time.
 *
 * The last part is the whole diagnosis: **a display this firmware has just created draws, and a
 * display that is asked to follow our SurfaceView afterwards does not.** AOSP v4.5's own
 * `VirtualDisplayAdapter` says the same thing at a lower level: `setSurfaceLocked` only records a
 * pending change and `resizeLocked`only records a pending size; both are flushed into one
 * `SurfaceControl.Transaction` on the *display* (`setDisplaySurface` / `setDisplaySize`), and on
 * this head unit's SurfaceFlinger the display that was created against surface A keeps drawing
 * into A' geometry and never picks up a new binding. CarCar Launcher never hits it because it
 * never reuses a surface: for every size change `La8/c0.b()` throws the old Flutter surface into a
 * pending list, makes a **new** one, calls `setSurface(new)` and only then `resize()`.
 *
 * A native `SurfaceView` cannot mint a new surface on demand, so v4.5 does what the user confirmed
 * works — it creates the display again:
 *
 *  1. A pane's rectangle or surface changed → wait until the layout has settled (a divider drag
 *     makes dozens of passes per second, and only the final rectangle is worth a display).
 *  2. Create a **new** display at that rectangle, on the pane's current surface.
 *  3. Move the app's root task onto it with [TaskMover] (`moveRootTaskToDisplay`, the call behind
 *     `am display move-stack`), so a navigation route or a player keeps running; when the platform
 *     refuses, start the app on the new display instead.
 *  4. Release the old display.
 *
 * Nothing is ever detached from a display that stays alive, and no display is resized in place.
 *
 * ## v4.6 — a ratio change no longer takes the whole screen's touch with it (2026-11-26)
 *
 * The car unit tested v4.5 and reported: "전보다는 좋아졌어 그런데 화면비율 변경이 들어가면 화면전체가
 * 터치가 안되는 문제가있어. 물론 껏다키면 다시동작해". A ratio change moves *both* panes, so both
 * displays were replaced at once, each of them while the launcher's main thread was also doing the
 * touch injection of the pane underneath the finger (see
 * [com.hyteck.bmwf07launcher.touch.SystemTouchInjector]). Two display transactions, a task move and
 * a release on top of a binder call that waits for exactly those transactions is what the input
 * dispatcher stalls on, and the launcher's main thread is where the stall landed.
 *
 * v4.6 handles it on both sides of that:
 *
 *  - the touch injection is copied on the main thread and delivered on a thread of its own, so no
 *    platform call in the touch path can ever block the thread a finger arrives on;
 *  - only **one** pane's display is replaced at a time ([swapping]); the other pane waits its turn
 *    (250 ms), so a ratio change no longer puts two display transactions in flight together;
 *  - the old displays are released one at a time, a moment apart ([retire]), instead of both at
 *    the same instant.
 *
 * The extra few hundred milliseconds are invisible (the app is already on its new display); the
 * stall was not.
 *
 * ## v4.8 — a pane comes back after *any* window has taken the screen (2026-11-27)
 *
 * The car unit reported on v4.6: "4.6에서 후진을 넣으면 화면이 카메라로바뀌거든 근데 끝나고 다시 런쳐로
 * 돌아가는데 이때 화면이 모두 까매.계속 반복" — and then, asked which screen: "후진이 문제는 아닌것같고
 * 다른 화면에서 다시 복귀하면 다 그런것같아". It is not the camera: every return from a window in
 * front of this launcher left both panes black.
 *
 * While another window covers this launcher the pane SurfaceViews lose their surface
 * (`surfaceDestroyed`) and get a new one on the way back, but the display a pane created is still
 * bound to the old BufferQueue — the binding this firmware never picks up again (see v4.5) — and
 * nothing on the way back replaced it:
 *
 *  - [resume] — the one call made for a return — *skipped* a pane whose surface had been reported
 *    lost ([surfaceLost]) without looking at it, exactly the pane that needed a new display, and it
 *    never checked the surface at all, so a pane whose surface came back before that call was left
 *    on its dead binding;
 *  - `LauncherActivity.onResume()` only asked for [resume] on a return from the launcher's own
 *    screens (the v3.2 free-form guard), so a return from the camera or any other app never
 *    reached this class at all.
 *
 * v4.8 makes the return a path of its own ([repairPane]):
 *
 *  1. Every return looks at each pane's *current* surface and display instead of trusting a
 *     callback or a flag: a surface the display is not bound to, a reported surface loss, or a
 *     display the platform no longer lists all mean the pane gets a new display.
 *  2. The app is woken on that new display. Moving its root task keeps the app's state, but an
 *     activity that was stopped while it was behind another window draws nothing until it is
 *     resumed, so the activity that is on top of its task is brought to the front
 *     ([wakeOnDisplay]); the app's own main activity is only a fallback, because re-ordering that
 *     one would move a route or a player off the screen.
 *  3. A pane that is already drawing is only checked for its app's task and left alone, and every
 *     step is retried a few times, because a launcher that returns is laid out and shown only
 *     after `onResume` has already run.
 *
 * ## v4.10 — a return from another window takes the same road as a ratio change (2026-11-27)
 *
 * The car unit tested v4.9: "비율 조정은 잘 되는데, 음, 후진 카메라 갔다가 다시 돌아오면 까맣게 변하는 현상은
 * 변함이 없어. 다시 확인해 봐." So v4.7's route is the one that draws — a *new* `SurfaceView` with its own
 * BufferQueue, a display created on it — and the return still did not take it.
 *
 * The reason was the condition that chose that route: `swapDisplay()` only minted a new `SurfaceView`
 * when the pane's record was bound to the *same* `Surface` object (`pane.surface === surface`), which
 * is what a ratio change looks like. A return from the camera is the opposite: `surfaceDestroyed` and
 * then `surfaceCreated` give the SurfaceView a **different** Surface, so the pane went down the
 * re-point path — `host.create(..., surface)` on the SurfaceView that had just lost a surface, the
 * one thing this firmware has never drawn into (v4.5, v4.8).
 *
 * v4.10 therefore treats a reported surface loss exactly like a ratio change:
 *
 *  1. [swapDisplay] mints a new `SurfaceView` when the pane's surface moved **or** the pane reported a
 *     loss, and [replaceDisplayNow] no longer skips a pane whose geometry still matches while a loss
 *     is on record. The count of loss-driven rebuilds per pane is bounded, so a firmware that refuses
 *     every new display cannot turn a return into an endless mint-and-restart loop.
 *  2. The app is woken on its new display with an **explicit component and no `ACTION_MAIN` action**
 *     ([wakeOnDisplay]); an app whose top activity has no MAIN filter is still resumed, and its main
 *     activity is not pulled in front of an open route or player.
 *  3. The first wake can land before the new display and its surface have settled, so one more wake
 *     follows about 200 ms later ([scheduleRewake]) — only for the same display generation and only
 *     while the engine is alive.
 *  4. A committed bounds pass ([applyBounds] with `commit = true`, which `onResume` sends at the same
 *     moment it asks for the repair) now expires after [COMMIT_WINDOW_MS] instead of staying set until
 *     some later pass consumes it, so it can never make a later divider drag swap a display mid-gesture.
 *     The repair and the committed pass both end in the same `scheduleSwap` slot per pane, so one
 *     replacement goes ahead and the other request simply re-times it.
 *
 * ## What else is kept from v4.0–v4.4
 *
 *  - The first display of a pane is created exactly the way v4.0 created it and the way CarCar
 *    creates it (`createVirtualDisplay(name, w, h, dpi, null, 0xD09)`, then `dontOverrideDisplayInfo`,
 *    then the rectangle, then the surface — see [SystemDisplayHost]).
 *  - **Nothing is done from inside a SurfaceView callback**: the callbacks only schedule a pass on
 *    the main looper, and the display work happens outside `SurfaceView`'s own update.
 *  - A failed route says so on screen ([showStatus]) and in the `MNLauncher` log, because a black
 *    pane in a car gives nothing else to go on.
 *  - A hidden pane (fullscreen) is frozen with `setDisplayState(false)` instead of being released,
 *    so the app keeps its state.
 *  - A pane whose app died (the platform is free to kill a display's content) is started again when
 *    the launcher comes back in front ([resume]).
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

    /**
     * One pane's live display: which display it is, which surface it was created on and the
     * rectangle it was created at. A display whose record no longer matches the pane is replaced.
     */
    private class Pane(
        val displayId: Int,
        val surface: Surface,
        val width: Int,
        val height: Int,
        val dpi: Int
    )

    private class PendingSwap(
        val host: DisplayHost,
        val oldPane: Pane,
        val oldView: SurfaceView
    )

    private var leftView: SurfaceView = leftSurface
    private var rightView: SurfaceView = rightSurface

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    /** Who creates the displays: the shell side when Shizuku is bound, else this process. */
    private val hosts = HashMap<Side, DisplayHost>()
    private val panes = HashMap<Side, Pane>()
    private val pendingSwaps = HashMap<Side, PendingSwap>()
    private val pendingTimeouts = HashMap<Side, Runnable>()

    /** A one-line status over a pane, shown only when something actually failed (v4.3). */
    private val statusLabels = HashMap<Side, TextView>()

    private val packages = HashMap<Side, String>()
    private val activePackages = HashMap<Side, String>()
    private val densities = HashMap<Side, Int>()

    /** How often a pane waited for the Shizuku touch service before falling back to itself. */
    private val waits = HashMap<Side, Int>()

    /** Short, bounded retries while a pane's SurfaceView is between two surfaces. */
    private val surfaceWaits = HashMap<Side, Int>()

    /** The one pending display replacement per pane, re-posted while the layout keeps moving. */
    private val swaps = HashMap<Side, Runnable>()

    /** When this pane's display was last replaced, so a jittery layout cannot replace it forever. */
    private val swappedAt = HashMap<Side, Long>()

    /** True while a pane's SurfaceView is between two surfaces (the display work is deferred). */
    private val surfaceLost = HashSet<Side>()

    /** How many displays a pane has been given, for the log. */
    private val generations = HashMap<Side, Int>()

    /** Displays whose release is scheduled; [retire] lets one go at a time. */
    private val retiring = ArrayDeque<Pair<DisplayHost, Int>>()

    /** True while [pumpRetire] is waiting to release the next display. */
    private var retiringBusy = false

    /** The pane whose display is being replaced right now; only one replacement at a time (v4.6). */
    private var swapping: Side? = null

    /** When the last pane display was replaced, so the two panes keep a gap between them (v4.6). */
    private var lastSwapEndedAt = 0L

    /** True when the latest [applyBounds] came from a committed choice (ratio menu, preset, release). */
    private var commitPending = false

    /** When that committed choice arrived; it only settles quickly for a moment (v4.10). */
    private var commitAt = 0L

    /**
     * Loss-driven display rebuilds per pane (v4.10). A return rebuilds a pane on a brand-new
     * SurfaceView; if the platform cannot give that pane a display, the count stops the retries
     * instead of minting a view and restarting the app every second forever.
     */
    private val lossRepairs = HashMap<Side, Int>()

    private var unavailableReported = false
    private var launchErrorReported = false
    private var privilegeReported = false
    private var released = false

    private val syncRunnable = Runnable {
        syncSide(Side.LEFT)
        syncSide(Side.RIGHT)
    }

    init {
        densities[Side.LEFT] = settings.leftDpi
        densities[Side.RIGHT] = settings.rightDpi
        register(Side.LEFT, leftView)
        register(Side.RIGHT, rightView)
    }

    private fun register(side: Side, view: SurfaceView) {
        // v3.4: the pane's SurfaceView is the only place the display's app can be touched, so
        // every event that lands on it is forwarded to the display the pane is showing.
        view.isClickable = true
        view.setOnClickListener { /* the touch itself is forwarded in the listener below */ }
        view.setOnTouchListener { touched, event ->
            if (released || viewOf(side) !== touched) return@setOnTouchListener false
            val displayId = panes[side]?.displayId ?: -1
            if (displayId >= 0) touchSink?.invoke(displayId, event)
            if (event.actionMasked == MotionEvent.ACTION_UP) touched.performClick()
            true
        }
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (released || viewOf(side) !== view) return
                // v4.3: only schedule. The surface this callback announces is created inside
                // SurfaceView's own update; the pass runs when it is complete.
                Log.i(TAG, "pane $side surface created")
                if (pendingSwaps.containsKey(side)) {
                    handler.post { syncSide(side) }
                } else {
                    scheduleSync()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (released || viewOf(side) !== view) return
                if (pendingSwaps.containsKey(side)) {
                    handler.post { syncSide(side) }
                } else {
                    scheduleSync()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (released || viewOf(side) !== view) return
                // The display is left alone here: it is not detached and not released, so its app
                // keeps running. The new surface this pane gets next is a different object, and
                // the next pass replaces the display onto it ([replaceDisplay]) — the one route
                // this firmware has always drawn.
                Log.i(TAG, "pane $side surface destroyed; display ${panes[side]?.displayId} kept")
                surfaceLost.add(side)
                scheduleSync()
            }
        })
        view.addOnLayoutChangeListener { changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (released || viewOf(side) !== changed) return@addOnLayoutChangeListener
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (right - left > 0 && bottom - top > 0) scheduleSync()
            }
        }
    }

    private fun viewOf(side: Side): SurfaceView =
        if (side == Side.LEFT) leftView else rightView

    private fun setView(side: Side, view: SurfaceView) {
        if (side == Side.LEFT) leftView = view else rightView = view
    }

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
        // The SurfaceViews already have the exact pane size; the pass below compares that size with
        // the size the pane's display was created at and replaces the display if it moved.
        if (commit) {
            commitPending = true
            commitAt = System.currentTimeMillis()
        }
        scheduleSync()
    }

    /**
     * The launcher came back in front: re-apply both rectangles, and — only for a pane that really
     * went through a surface loss — start its app again when the platform took it away (a released
     * display takes its content with it, and a firmware may reclaim it as well).
     *
     * A plain return from the launcher's own screens is not such a moment any more: those screens
     * are translucent (v4.5), the launcher is never stopped and its surfaces are never destroyed,
     * so [surfaceLost] stays empty and nothing here touches the displays.
     */
    override fun resume(sides: Set<Side>) {
        if (released || sides.isEmpty()) return
        repairSides(HashSet(sides), 0)
    }

    /**
     * One pass of [resume] over [sides], repeated a few times for a pane that is not ready yet.
     *
     * A launcher that just came back from another window is still being laid out and shown when
     * `onResume` runs, so a pane can have no surface or no size for another moment. Returning to it
     * later is what makes the fix reliable; the number of passes is small and bounded (v4.8).
     */
    private fun repairSides(sides: Set<Side>, pass: Int) {
        if (released) return
        var again = false
        for (side in sides) {
            if (!repairPane(side)) again = true
        }
        scheduleSync()
        if (again && pass < MAX_RESUME_PASSES) {
            handler.postDelayed({
                if (!released) repairSides(sides, pass + 1)
            }, RESUME_RETRY_MS)
        }
    }

    /**
     * Brings one pane back after a return from another window (v4.8 — see the class header).
     *
     * @return true when this pass left nothing to do for the pane, false to be looked at again.
     */
    private fun repairPane(side: Side): Boolean {
        val pkg = packages[side] ?: return true
        // A replacement that is already on its way brings the picture and the app back by itself.
        if (pendingSwaps.containsKey(side)) return true
        val view = viewOf(side)
        if (!view.isShown) return false
        val surface = view.holder.surface
        if (surface == null || !surface.isValid) return false
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false
        val host = hostFor(side) ?: return false

        val pane = panes[side]
        if (pane == null) {
            // There is no display to come back to (a first pass after a restart, or a display the
            // platform dropped): make one now instead of waiting for a callback.
            createDisplay(side, host, width, height, densityOf(side), surface)
            return true
        }

        val surfaceMoved = pane.surface !== surface
        val lost = surfaceLost.contains(side)
        val alive = displayAlive(host, pane.displayId)
        if (surfaceMoved || lost || !alive) {
            if (surfaceMoved) surfaceLost.add(side)
            Log.i(
                TAG,
                "pane $side came back (newSurface=$surfaceMoved, lost=$lost, alive=$alive); " +
                    "replacing display ${pane.displayId}"
            )
            scheduleSwap(side, SURFACE_SWAP_MS)
            return true
        }

        if (!TaskMover.hasTask(pane.displayId, pkg)) {
            Log.w(TAG, "pane $side app $pkg is gone from display ${pane.displayId}; starting it again")
            launchOnDisplay(side, pkg, pane.displayId, reorder = false)
        }
        return true
    }

    /**
     * True when [displayId] is still one the platform lists.
     *
     * Only asked when this process owns the display: a display created by the Shizuku service
     * belongs to another UID and the platform does not report it to this app, so its silence
     * would say nothing. A display that was taken away while this launcher was behind another
     * window leaves a pane that keeps its (now dead) record and never redraws, so a return has to
     * look. Anything that cannot be read is taken as alive: rebuilding a display out of ignorance
     * would restart the app for nothing.
     */
    private fun displayAlive(host: DisplayHost, displayId: Int): Boolean {
        if (host !is SystemDisplayHost) return true
        return try {
            // A platform that cannot even name the main display is not answering about this one.
            displayManager.getDisplay(displayId) != null ||
                displayManager.getDisplay(Display.DEFAULT_DISPLAY) == null
        } catch (t: Throwable) {
            true
        }
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

        val surface = view.holder.surface
        if (surface == null || !surface.isValid) {
            // Between two surfaces. surfaceCreated/surfaceChanged schedule the real pass; these
            // few retries only cover a callback that was missed.
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

        val pending = pendingSwaps[side]
        if (pending != null) {
            finishPendingSwap(side, pending, view, width, height, densityOf(side), surface)
            return
        }

        val pane = panes[side]
        if (pane == null) {
            createDisplay(side, host, width, height, densityOf(side), surface)
            return
        }

        val dpi = densityOf(side)
        val surfaceReplaced = pane.surface !== surface
        val geometryMoved = abs(pane.width - width) > SIZE_TOLERANCE ||
            abs(pane.height - height) > SIZE_TOLERANCE || pane.dpi != dpi
        // v4.8: a surface the pane reported lost is replaced even when the SurfaceView hands back
        // the same Surface object — a display bound to a BufferQueue that went away does not start
        // drawing into it again, and that is what a return from another window looked like.
        val lossReported = surfaceLost.contains(side)
        if (!surfaceReplaced && !geometryMoved && !lossReported) return

        // v4.10: a committed pass keeps its short settle only for a moment. `onResume` sends one at
        // the same instant it asks for the return repair, and a flag that stayed set would later
        // make a divider drag replace a display in the middle of the gesture (GEOMETRY_SETTLE_MS
        // exists for exactly that).
        val committed = commitPending &&
            System.currentTimeMillis() - commitAt <= COMMIT_WINDOW_MS

        // A drag makes dozens of these passes; only the rectangle it settles on is worth a display.
        val settleMs = when {
            surfaceReplaced || lossReported -> SURFACE_SWAP_MS
            committed -> COMMIT_SETTLE_MS
            else -> GEOMETRY_SETTLE_MS
        }
        scheduleSwap(side, settleMs)
    }

    /** Re-posts the pane's replacement; it runs once the pane has stopped moving. */
    private fun scheduleSwap(side: Side, delayMs: Long) {
        swaps.remove(side)?.let { handler.removeCallbacks(it) }
        // One replacement per pane per moment: a display that keeps being replaced would restart
        // its app again and again, which is worse than a pane that waits a second.
        val since = System.currentTimeMillis() - (swappedAt[side] ?: 0L)
        val wait = maxOf(delayMs, SWAP_COOLDOWN_MS - since)
        val task = Runnable { replaceDisplay(side) }
        swaps[side] = task
        handler.postDelayed(task, wait)
    }

    /** The first display of a pane — the path that has drawn on the unit since v4.0. */
    private fun createDisplay(
        side: Side,
        host: DisplayHost,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ) {
        val created = host.create(displayName(side), width, height, dpi, surface)
        if (created < 0) {
            // v4.3: say which route refused, on screen and in the log — a pane that stays black
            // gives nothing else to go on.
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
        panes[side] = Pane(created, surface, width, height, dpi)
        swappedAt[side] = System.currentTimeMillis()
        surfaceLost.remove(side)
        lossRepairs.remove(side)
        generations[side] = (generations[side] ?: 0) + 1
        hideStatus(side)
        Log.i(
            TAG,
            "pane $side display $created created at ${width}x$height @$dpi " +
                "via ${hostName(host)}, surface ${surface.hashCode()}"
        )
        packages[side]?.let { launchOnDisplay(side, it, created, reorder = true) }
        scheduleReport(side, created)
    }

    /**
     * Gives a pane a new display at its current rectangle and surface, moves the running app onto
     * it and releases the old one (v4.5 — see the class header).
     */
    private fun replaceDisplay(side: Side) {
        if (released) return
        if (pendingSwaps.containsKey(side)) return
        if (swapping != null) {
            // The other pane's display is being replaced right now.
            scheduleSwap(side, RETRY_MS)
            return
        }
        // A ratio change moves both panes, so both replacements come due in the same frame. Two
        // display transactions (a create, a task move, a release) in one moment is exactly what
        // the input dispatcher stalled on; v4.6 does one pane, waits a beat, then the other.
        val gap = System.currentTimeMillis() - lastSwapEndedAt
        if (lastSwapEndedAt != 0L && gap < SWAP_GAP_MS) {
            scheduleSwap(side, SWAP_GAP_MS - gap + 20L)
            return
        }
        val before = panes[side]
        swapping = side
        try {
            replaceDisplayNow(side)
        } finally {
            // If mintSurfaceView() started an async SurfaceView swap, finishPendingSwap() (or its
            // timeout) will release the slot once the new display is created.
            if (!pendingSwaps.containsKey(side)) {
                swapping = null
                if (panes[side] !== before) lastSwapEndedAt = System.currentTimeMillis()
            }
        }
    }

    /** The body of [replaceDisplay]; runs only while this pane holds the one replacement slot. */
    private fun replaceDisplayNow(side: Side) {
        if (released) return
        val view = viewOf(side)
        if (!view.isShown) return
        val width = view.width
        val height = view.height
        val surface = view.holder.surface
        val pane = panes[side]
        val host = hosts[side] ?: hostFor(side) ?: run {
            scheduleSwap(side, RETRY_MS)
            return
        }
        if (width <= 0 || height <= 0 || surface == null || !surface.isValid) {
            scheduleSwap(side, RETRY_MS)
            return
        }
        val dpi = densityOf(side)
        if (pane == null) {
            createDisplay(side, host, width, height, dpi, surface)
            return
        }
        // v4.10: a pane whose surface was reported lost is rebuilt even when its rectangle and
        // Surface object still match — otherwise a return from another window would decide there
        // is nothing to do and leave the pane on its dead binding.
        if (pane.surface === surface &&
            abs(pane.width - width) <= SIZE_TOLERANCE &&
            abs(pane.height - height) <= SIZE_TOLERANCE && pane.dpi == dpi &&
            !surfaceLost.contains(side)
        ) {
            return
        }
        swapDisplay(side, host, pane, width, height, dpi, surface)
    }

    private fun swapDisplay(
        side: Side,
        host: DisplayHost,
        pane: Pane,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ) {
        // v4.7: never bind a newly created VirtualDisplay onto a Surface that an earlier
        // VirtualDisplay is still holding (the root cause of right-side black margins and
        // intermittent blackouts on ratio change). Mint a fresh SurfaceView so the new display
        // gets a brand-new BufferQueue at the exact new size, while the old display retires on
        // its own old Surface.
        //
        // v4.10: a pane coming back from behind another window takes this same route. Its
        // SurfaceView was destroyed and re-created, so `pane.surface !== surface` — but a display
        // made on a SurfaceView that has already lost one surface never draws here (v4.5, v4.8),
        // which is why the ratio path worked and the return did not.
        val lost = surfaceLost.contains(side)
        if (pane.surface === surface || lost) {
            val oldView = viewOf(side)
            val parent = oldView.parent as? ViewGroup
            if (parent != null) {
                if (!lost) {
                    mintSurfaceView(side, host, pane, oldView, parent)
                    return
                }
                val repairs = (lossRepairs[side] ?: 0) + 1
                lossRepairs[side] = repairs
                if (repairs <= MAX_LOSS_REPAIRS) {
                    Log.i(
                        TAG,
                        "pane $side rebuilding on a fresh SurfaceView after a surface loss " +
                            "($repairs/$MAX_LOSS_REPAIRS)"
                    )
                    mintSurfaceView(side, host, pane, oldView, parent)
                    return
                }
                // Bounded: stop rebuilding on the loss alone and rebind what the pane has now, so
                // a firmware that refuses the new display cannot loop here forever.
                Log.w(
                    TAG,
                    "pane $side gave up rebuilding on a surface loss after $repairs attempts; " +
                        "re-pointing display ${pane.displayId}"
                )
                surfaceLost.remove(side)
            }
        }
        val created = host.create(displayName(side), width, height, dpi, surface)
        if (created < 0) {
            Log.e(
                TAG,
                "pane $side could not create a replacement display via ${hostName(host)}; " +
                    "re-pointing display ${pane.displayId}"
            )
            host.setSurface(pane.displayId, surface)
            host.resize(pane.displayId, width, height, dpi)
            panes[side] = Pane(pane.displayId, surface, width, height, dpi)
            // The record now matches the surface; a loss left set here would make every later pass
            // try to replace the display again.
            surfaceLost.remove(side)
            showStatus(side, "화면을 다시 만들 수 없습니다\n${hostName(host)}")
            return
        }
        completeSwap(side, host, pane, created, surface, width, height, dpi)
    }

    private fun mintSurfaceView(
        side: Side,
        host: DisplayHost,
        oldPane: Pane,
        oldView: SurfaceView,
        parent: ViewGroup
    ) {
        val newView = SurfaceView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = oldView.visibility
        }
        setView(side, newView)
        register(side, newView)
        pendingSwaps[side] = PendingSwap(host, oldPane, oldView)
        val timeout = Runnable {
            if (released) return@Runnable
            val stale = pendingSwaps.remove(side) ?: return@Runnable
            pendingTimeouts.remove(side)
            Log.w(TAG, "pane $side fresh SurfaceView timed out; restoring previous view")
            setView(side, stale.oldView)
            (newView.parent as? ViewGroup)?.removeView(newView)
            if (swapping == side) {
                swapping = null
                lastSwapEndedAt = System.currentTimeMillis()
            }
            scheduleSync()
        }
        pendingTimeouts[side] = timeout
        handler.postDelayed(timeout, PENDING_SWAP_TIMEOUT_MS)
        val insertIndex = (parent.indexOfChild(oldView) + 1).coerceIn(0, parent.childCount)
        parent.addView(newView, insertIndex)
    }

    private fun finishPendingSwap(
        side: Side,
        pending: PendingSwap,
        newView: SurfaceView,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ) {
        pendingTimeouts.remove(side)?.let { handler.removeCallbacks(it) }
        pendingSwaps.remove(side)
        val created = pending.host.create(displayName(side), width, height, dpi, surface)
        if (created < 0) {
            Log.e(
                TAG,
                "pane $side could not create a replacement display via ${hostName(pending.host)}; " +
                    "restoring previous view"
            )
            setView(side, pending.oldView)
            (newView.parent as? ViewGroup)?.removeView(newView)
            pending.host.resize(pending.oldPane.displayId, width, height, dpi)
            showStatus(side, "화면을 다시 만들 수 없습니다\n${hostName(pending.host)}")
        } else {
            completeSwap(side, pending.host, pending.oldPane, created, surface, width, height, dpi)
            (pending.oldView.parent as? ViewGroup)?.removeView(pending.oldView)
        }
        if (swapping == side) {
            swapping = null
            lastSwapEndedAt = System.currentTimeMillis()
        }
    }

    private fun completeSwap(
        side: Side,
        host: DisplayHost,
        oldPane: Pane,
        created: Int,
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int
    ) {
        // v4.8: read the loss before clearing it — a pane that comes back from behind another
        // window needs its app woken on the new display even when moving the task succeeded.
        val wasSurfaceLost = surfaceLost.remove(side)
        // The pane has a working display again; the loss-driven rebuild budget starts over.
        lossRepairs.remove(side)
        panes[side] = Pane(created, surface, width, height, dpi)
        swappedAt[side] = System.currentTimeMillis()
        val generation = (generations[side] ?: 0) + 1
        generations[side] = generation
        hideStatus(side)
        Log.i(
            TAG,
            "pane $side display ${oldPane.displayId} -> $created (#$generation, " +
                "${width}x$height @$dpi, surface ${surface.hashCode()})"
        )

        val pkg = packages[side]
        val sameApp = pkg != null && pkg == activePackages[side]
        val moved = sameApp && TaskMover.moveRootTasks(oldPane.displayId, created, pkg)
        if (pkg != null && !moved) {
            // Either the package changed (e.g. preset) or the move was refused: start the desired
            // app directly on the new display.
            launchOnDisplay(side, pkg, created, reorder = sameApp, fatal = false)
        } else if (pkg != null && wasSurfaceLost) {
            // The pane was behind another window, so its app was stopped while nothing drew into
            // it. The move kept the app and its state, but a stopped activity produces no frames
            // until it is resumed: bring it back to the front of its own display (v4.8).
            Log.i(TAG, "pane $side app $pkg came back with the pane; waking it on display $created")
            wakeOnDisplay(side, pkg, created)
            // v4.10: the display and its surface have just been created, so the first wake can be
            // swallowed by a window manager that has not laid the new display out yet. One more
            // wake a moment later is what makes the return reliable.
            scheduleRewake(side, pkg, created)
        }
        // The app has left the old display (moved) or a new instance is starting on the new one:
        // its content is not needed any more.
        retire(host, oldPane.displayId)
        scheduleReport(side, created)
    }

    /**
     * Queues [displayId] for release, [delayMs] from now (v4.6).
     *
     * A ratio change replaces both panes, and releasing two displays in the same moment is one of
     * the display transactions the input dispatcher stalled on. [pumpRetire] therefore lets one
     * go, then the next, spacing them by [RELEASE_DELAY_MS].
     */
    private fun retire(host: DisplayHost, displayId: Int, delayMs: Long = RELEASE_DELAY_MS) {
        retiring.addLast(host to displayId)
        pumpRetire(delayMs)
    }

    private fun pumpRetire(delayMs: Long = RELEASE_DELAY_MS) {
        if (retiringBusy) return
        val next = retiring.removeFirstOrNull() ?: return
        retiringBusy = true
        handler.postDelayed({
            retiringBusy = false
            if (!released) next.first.release(next.second)
            pumpRetire(delayMs)
        }, delayMs)
    }

    /**
     * Reads the display's real rectangle back once, a moment after it was created, and writes a log
     * line — the one thing that tells a later reader whether the display kept the size it was made
     * with. Nothing is detached or rebuilt from here.
     */
    private fun scheduleReport(side: Side, displayId: Int) {
        if (!VERBOSE_REPORT) return
        handler.postDelayed({
            if (released) return@postDelayed
            val display = try {
                displayManager.getDisplay(displayId)
            } catch (t: Throwable) {
                null
            }
            if (display == null) {
                Log.i(TAG, "pane $side display $displayId is not listed by DisplayManager")
                return@postDelayed
            }
            val real = Point()
            val measured = runCatching { display.getRealSize(real) }.isSuccess
            val state = runCatching { display.state }.getOrNull()
            Log.i(
                TAG,
                "pane $side display $displayId reports " +
                    "${if (measured) "${real.x}x${real.y}" else "no size"}, " +
                    "state ${state ?: "?"}"
            )
            val pane = panes[side]
            if (pane != null && pane.displayId == displayId && measured &&
                (abs(real.x - pane.width) > SIZE_TOLERANCE || abs(real.y - pane.height) > SIZE_TOLERANCE)
            ) {
                Log.w(
                    TAG,
                    "pane $side display $displayId kept ${real.x}x${real.y} " +
                        "instead of ${pane.width}x${pane.height}"
                )
            }
        }, REPORT_DELAY_MS)
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
        val displayId = panes[side]?.displayId
        if (displayId == null) {
            scheduleSync()
            return
        }
        launchOnDisplay(side, packageName, displayId, reorder = false)
    }

    override fun preparePackages(left: String?, right: String?) {
        if (left != null) packages[Side.LEFT] = left else clear(Side.LEFT)
        if (right != null) packages[Side.RIGHT] = right else clear(Side.RIGHT)
    }

    override fun clear(side: Side) {
        // The app keeps its display (another app's task cannot be killed from here); the pane's
        // empty overlay simply covers it until a new app is picked.
        packages.remove(side)
        activePackages.remove(side)
    }

    /**
     * Fullscreen hides the other pane: its display is frozen (`setDisplayState(false)`) instead of
     * being released or left drawing, so the app keeps its state and the GPU is not asked for a
     * picture nobody sees. Coming back unfreezes it and re-attaches the pane's current surface.
     */
    override fun setPaneVisible(side: Side, visible: Boolean) {
        val pane = panes[side] ?: return
        val host = hosts[side] ?: return
        host.setState(pane.displayId, visible)
        if (visible) {
            val surface = viewOf(side).holder.surface
            if (surface != null && surface.isValid) host.setSurface(pane.displayId, surface)
            scheduleSync()
        }
    }

    override fun setDensity(side: Side, dpi: Int) {
        densities[side] = dpi.coerceIn(Settings.MIN_DPI, Settings.MAX_DPI)
        scheduleSync()
    }

    /**
     * Gives this pane a brand-new display on demand (the pane menu's "화면 다시 그리기"): the
     * escape hatch for a pane that came back black.
     */
    override fun redraw(side: Side) {
        if (released) return
        val old = panes.remove(side)
        val host = hosts[side]
        if (old != null && host != null) {
            // Give the new display time to take the app before the old one takes it away.
            retire(host, old.displayId, RELEASE_DELAY_MS + 400L)
            Log.i(TAG, "pane $side redraw requested; display ${old.displayId} will be released")
        }
        if (viewOf(side).width > 0 && viewOf(side).height > 0) {
            scheduleSwap(side, 0L)
        } else {
            scheduleSync()
        }
    }

    /**
     * Brings the pane's app back to the front of its own display without changing what it shows
     * (v4.8).
     *
     * The activity on top of the app's task is the one that is re-ordered. A launch intent would
     * re-order the app's *main* activity instead, which moves an open route or player off the
     * screen on every return; that launch intent is only the fallback, for a firmware whose task
     * information cannot be read.
     */
    private fun wakeOnDisplay(side: Side, packageName: String, displayId: Int) {
        val top = TaskMover.topActivityOn(displayId, packageName)
        if (top == null) {
            launchOnDisplay(side, packageName, displayId, reorder = true, fatal = false)
            return
        }
        // v4.10: no ACTION_MAIN any more. It is not needed to start an explicit component, and a
        // top activity that has no MAIN filter in its manifest was the one case the wake could
        // not resume at all.
        val intent = Intent().apply {
            component = top
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val options = ActivityOptions.makeBasic()
        options.setLaunchDisplayId(displayId)
        try {
            context.startActivity(intent, options.toBundle())
            activePackages[side] = packageName
            launchErrorReported = false
        } catch (e: Exception) {
            // A component this build may not start (the standard build runs as a plain app): the
            // app's own launch intent is the way that always exists.
            Log.w(TAG, "pane $side wake of ${top.flattenToShortString()} failed (${e.javaClass.simpleName})")
            launchOnDisplay(side, packageName, displayId, reorder = true, fatal = false)
        }
    }

    /**
     * Wakes a pane's app once more, a moment after the display it came back on was created
     * (v4.10).
     *
     * Moving the task and the first wake happen in the same frame that creates the display, and a
     * stopped app on a display the window manager has not laid out yet can miss it. The second
     * wake is skipped when the engine was released or the pane already has another display, so a
     * quick second return cannot be woken twice on the wrong display. The app's top activity is
     * woken again (not its main activity), and only when its task is really gone from the display
     * is the launch intent used.
     */
    private fun scheduleRewake(side: Side, packageName: String, displayId: Int) {
        handler.postDelayed({
            if (released) return@postDelayed
            if (panes[side]?.displayId != displayId) return@postDelayed
            if (packages[side] != packageName) return@postDelayed
            Log.i(TAG, "pane $side re-waking $packageName on display $displayId")
            if (TaskMover.hasTask(displayId, packageName)) {
                wakeOnDisplay(side, packageName, displayId)
            } else {
                launchOnDisplay(side, packageName, displayId, reorder = true, fatal = false)
            }
        }, WAKE_RETRY_MS)
    }

    private fun launchOnDisplay(
        side: Side,
        packageName: String,
        displayId: Int,
        reorder: Boolean,
        /** When false, a refusal only shows on the pane; it does not condemn the whole engine. */
        fatal: Boolean = true
    ) {
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
            activePackages[side] = packageName
            launchErrorReported = false
        } catch (e: Exception) {
            // v4.3: the one failure that reads as "a black pane" from the driver's seat — the
            // display never received the app. Name it on the pane and in the log.
            Log.e(TAG, "pane $side launch of $packageName on display $displayId failed", e)
            showStatus(side, "앱을 이 창에서 열 수 없습니다\n${e.javaClass.simpleName}")
            if (fatal && !launchErrorReported) {
                launchErrorReported = true
                reportUnavailable()
            }
        }
    }

    override fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            swaps.remove(side)?.let { handler.removeCallbacks(it) }
            val displayId = panes[side]?.displayId
            if (displayId != null) hosts[side]?.release(displayId)
        }
        panes.clear()
        hosts.clear()
        // A display whose release was still pending would otherwise outlive the engine.
        retiring.forEach { (owner, displayId) -> owner.release(displayId) }
        retiring.clear()
        retiringBusy = false
        swapping = null
        lastSwapEndedAt = 0L
        statusLabels.values.forEach { label -> (label.parent as? ViewGroup)?.removeView(label) }
        statusLabels.clear()
        pendingTimeouts.values.forEach { handler.removeCallbacks(it) }
        pendingTimeouts.clear()
        pendingSwaps.clear()
        lossRepairs.clear()
        commitPending = false
        commitAt = 0L
        restoreOriginalSurface(leftView, leftSurface)
        restoreOriginalSurface(rightView, rightSurface)
        leftView = leftSurface
        rightView = rightSurface
        packages.clear()
        activePackages.clear()
        waits.clear()
        surfaceWaits.clear()
        swaps.clear()
        swappedAt.clear()
        surfaceLost.clear()
    }

    private fun restoreOriginalSurface(current: SurfaceView, original: SurfaceView) {
        if (current === original) return
        val parent = current.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(current).coerceAtLeast(0)
        parent.removeView(current)
        if (original.parent == null) {
            original.visibility = current.visibility
            parent.addView(original, index.coerceAtMost(parent.childCount))
        }
    }

    companion object {
        private const val TAG = "MNLauncher"
        private const val SYNC_DELAY_MS = 64L

        /** A replacement waits this long after the last pass that still saw a moved rectangle. */
        private const val GEOMETRY_SETTLE_MS = 380L

        /** Committed ratio/preset changes settle quickly without waiting for drag debouncing. */
        private const val COMMIT_SETTLE_MS = 60L

        /** A destroyed-and-recreated surface is replaced quickly; the pane is not visible then. */
        private const val SURFACE_SWAP_MS = 120L

        /** The old display is released once the app has had time to arrive on the new one. */
        private const val RELEASE_DELAY_MS = 600L

        private const val RETRY_MS = 250L
        private const val PENDING_SWAP_TIMEOUT_MS = 1000L

        /** Do not replace the same pane's display more often than this (an app restart each time). */
        private const val SWAP_COOLDOWN_MS = 250L

        /** The two panes' displays are never replaced in the same moment (v4.6). */
        private const val SWAP_GAP_MS = 180L

        /** A read-back of the display's size, only for the log. */
        private const val REPORT_DELAY_MS = 700L
        private const val VERBOSE_REPORT = true

        /** A pixel of rounding between the pane rectangle and the display metrics is normal. */
        private const val SIZE_TOLERANCE = 2

        /** ~2.6 s of waiting for the Shizuku touch service before the local host is used. */
        private const val MAX_WAIT_TRIES = 40

        /** ~0.4 s of retries for a pane surface that is being re-created. */
        private const val MAX_SURFACE_WAITS = 6

        /** A pane that a return found not laid out yet is looked at again a few times (v4.8). */
        private const val MAX_RESUME_PASSES = 6
        private const val RESUME_RETRY_MS = 350L

        /** How many times a pane may be rebuilt on a reported surface loss before giving up (v4.10). */
        private const val MAX_LOSS_REPAIRS = 3

        /** The second wake after a return, once the new display has settled (v4.10). */
        private const val WAKE_RETRY_MS = 220L

        /** A committed bounds pass settles quickly only this long after it arrived (v4.10). */
        private const val COMMIT_WINDOW_MS = 2000L

        /** The platform-signed build's application id suffix (see app/build.gradle.kts). */
        private const val SYSTEMUID_SUFFIX = ".systemuid"
    }
}
