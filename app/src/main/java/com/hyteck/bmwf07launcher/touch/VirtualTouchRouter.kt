package com.hyteck.bmwf07launcher.touch

import android.content.Context
import android.graphics.Path
import android.view.MotionEvent
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService
import com.hyteck.bmwf07launcher.shizuku.ShizukuBridge
import kotlin.math.abs

/**
 * Sends the touches a pane receives to the app running on that pane's virtual display (v3.4).
 *
 * A virtual display's content is drawn inside the launcher, so the launcher is what receives the
 * finger — the app on the display never does. Three routes put it back, strongest first:
 *
 *  0. **In-process injection** (SystemUID build): the launcher process is the platform UID and
 *     holds `INJECT_EVENTS`, so [SystemTouchInjector] addresses the event to the display and
 *     injects it directly — CarCar Launcher's own path.
 *  1. **Shizuku** (standard build, instant): the event is parceled to [com.hyteck.bmwf07launcher.
 *     service.ShizukuControlService], which injects it into the display with `InputManager`.
 *     Down, move, up and multi-touch all go through, so dragging and scrolling feel native.
 *  2. **Accessibility service** (no Shizuku needed): the finger's path is collected and replayed
 *     with `GestureDescription` on that display when the finger lifts (a tap becomes a 50 ms
 *     stroke at one point). `GestureDescription.Builder.setDisplayId` needs Android 11+, and a
 *     stroke is a single finger, so multi-touch is not available on this route.
 *
 * When neither route exists the caller must not be running the virtual-display engine at all; the
 * launcher selects the engine accordingly.
 */
class VirtualTouchRouter(private val context: Context) {

    /** Path of the gesture currently being collected for the accessibility route. */
    private val points = ArrayList<Float>()
    private var gestureDisplayId = -1
    private var gestureDownTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    /**
     * One touch event from a pane's SurfaceView. [displayId] is the virtual display that pane
     * shows; its pixel size equals the SurfaceView's, so the coordinates need no scaling.
     */
    fun handle(displayId: Int, event: MotionEvent) {
        if (displayId < 0) return
        if (SystemTouchInjector.canInject(context) && SystemTouchInjector.inject(displayId, event)) {
            points.clear()
            gestureDisplayId = -1
            return
        }
        if (ShizukuBridge.injectTouch(displayId, event)) {
            // Shizuku took it whole: drop any half-collected fallback gesture.
            points.clear()
            gestureDisplayId = -1
            return
        }
        collect(displayId, event)
    }

    // ------------------------------------------------------- accessibility route

    private fun collect(displayId: Int, event: MotionEvent) {
        val service = LauncherAccessibilityService.instance ?: return
        // A stroke is one finger; a second pointer is simply ignored.
        if (event.pointerCount > 1) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                points.clear()
                gestureDisplayId = -1
            }
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                points.add(event.x)
                points.add(event.y)
                lastX = event.x
                lastY = event.y
                gestureDisplayId = displayId
                gestureDownTime = event.eventTime
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureDisplayId != displayId) return
                if (abs(event.x - lastX) < SAMPLE_PX && abs(event.y - lastY) < SAMPLE_PX) return
                points.add(event.x)
                points.add(event.y)
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP -> {
                if (gestureDisplayId != displayId) return
                points.add(event.x)
                points.add(event.y)
                dispatch(
                    service,
                    displayId,
                    points,
                    (event.eventTime - gestureDownTime).coerceIn(0L, MAX_STROKE_MS)
                )
                points.clear()
                gestureDisplayId = -1
            }

            MotionEvent.ACTION_CANCEL -> {
                points.clear()
                gestureDisplayId = -1
            }
        }
    }

    private fun dispatch(
        service: LauncherAccessibilityService,
        displayId: Int,
        collected: List<Float>,
        elapsedMs: Long
    ) {
        if (collected.size < 2) return
        val path = Path()
        path.moveTo(collected[0], collected[1])
        var index = 2
        while (index + 1 < collected.size) {
            path.lineTo(collected[index], collected[index + 1])
            index += 2
        }
        // A tap is a single point: a stroke that does not move still reads as a click there.
        val duration = if (collected.size <= 2) TAP_MS else elapsedMs.coerceAtLeast(TAP_MS)
        service.dispatchVirtualTouch(displayId, path, duration)
    }

    companion object {
        /** A stroke that stays at one point for this long is a tap. */
        private const val TAP_MS = 50L

        /** Move points closer than this are dropped, so a path stays small. */
        private const val SAMPLE_PX = 3f

        /** GestureDescription refuses strokes longer than a minute. */
        private const val MAX_STROKE_MS = 30_000L
    }
}
