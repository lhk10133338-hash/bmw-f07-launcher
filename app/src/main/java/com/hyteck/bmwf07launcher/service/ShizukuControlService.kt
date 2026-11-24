package com.hyteck.bmwf07launcher.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import com.hyteck.bmwf07launcher.IShizukuControl
import java.util.concurrent.ConcurrentHashMap

/**
 * The shell side of the launcher's virtual-display engine (v3.4).
 *
 * Shizuku starts this class in a separate process that runs with the adb shell UID (2000), which
 * in AOSP is signed like the platform and therefore holds two permissions the launcher itself
 * cannot get:
 *
 *  - **`ADD_TRUSTED_DISPLAY`** — a virtual display may only host another app's activity when it is
 *    *trusted*. A display created by an ordinary app makes every `setLaunchDisplayId` fail with
 *    `SecurityException`, even for the app's own activity (verified against Android 15 behaviour
 *    of `createVirtualDisplay` + `ActivityOptions`). The display is therefore created here with
 *    `VIRTUAL_DISPLAY_FLAG_TRUSTED`, falling back to `PUBLIC` and then plain flags.
 *  - **`INJECT_EVENTS`** — pushes a touch into a display that is not the default one, like
 *    `adb shell input`.
 *
 * Only hidden APIs are used, and every one of them is resolved lazily and defensively: a firmware
 * that lacks one disables that single feature instead of crashing the service.
 */
class ShizukuControlService : IShizukuControl.Stub() {

    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    /**
     * A system context of this process. Shizuku's user service process runs on an
     * `ActivityThread`, so the system context (which owns the display manager) can be borrowed
     * from it; without it no display can be created and the launcher falls back to its own.
     */
    private val systemContext: Context? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
                ?: return@lazy null
            activityThread.getMethod("getSystemContext").invoke(current) as? Context
        } catch (t: Throwable) {
            null
        }
    }

    private val displayManager: DisplayManager? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            systemContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        } catch (t: Throwable) {
            null
        }
    }

    private val inputManager: Any? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance")
                .invoke(null)
        } catch (t: Throwable) {
            null
        }
    }

    private val injectMethod: java.lang.reflect.Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Class.forName("android.hardware.input.InputManager").getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** The 15-argument `MotionEvent.obtain` that takes a displayId (hidden since API 29). */
    private val obtainWithDisplay: java.lang.reflect.Method? by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        try {
            MotionEvent::class.java.methods.firstOrNull {
                it.name == "obtain" && it.parameterTypes.size == 15
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------- displays

    override fun createDisplay(
        name: String?,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?
    ): Int {
        val manager = displayManager ?: return -1
        if (width <= 0 || height <= 0) return -1
        val safeName = name ?: "bmw-f07"
        for (flags in FLAG_SETS) {
            try {
                val display = manager.createVirtualDisplay(
                    safeName,
                    width,
                    height,
                    dpi,
                    surface,
                    flags
                )
                if (display != null) {
                    val id = display.display.displayId
                    displays[id] = display
                    return id
                }
            } catch (t: Throwable) {
                // A refused flag set (typically TRUSTED without ADD_TRUSTED_DISPLAY): next one.
            }
        }
        return -1
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val display = displays[displayId] ?: return false
        if (width <= 0 || height <= 0) return false
        return try {
            display.resize(width, height, dpi)
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun setDisplaySurface(displayId: Int, surface: Surface?): Boolean {
        val display = displays[displayId] ?: return false
        return try {
            display.surface = surface
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.let { display ->
            try {
                display.release()
            } catch (t: Throwable) {
                // Already gone.
            }
        }
    }

    // --------------------------------------------------------------- touch

    override fun isReady(): Boolean = inputManager != null && injectMethod != null

    override fun injectTouch(displayId: Int, event: MotionEvent?) {
        if (event == null || displayId < 0) return
        val manager = inputManager ?: return
        val inject = injectMethod ?: return
        val target = withDisplay(event, displayId) ?: return
        try {
            // MODE_ASYNC (0): never block the launcher's UI thread on the input dispatcher, which
            // is what keeps a drag smooth.
            inject.invoke(manager, target, MODE_ASYNC)
        } catch (t: Throwable) {
            // A refused injection is not fatal: the next event may be accepted.
        }
    }

    override fun destroy() {
        for (id in displays.keys.toList()) {
            releaseDisplay(id)
        }
        System.exit(0)
    }

    /**
     * Returns an event bound to [displayId]. The events that cross Binder are rebuilt from their
     * pointer data, which also lets the device id be cleared so the injected stream looks like a
     * virtual touch screen rather than a second report from the real one.
     */
    private fun withDisplay(event: MotionEvent, displayId: Int): MotionEvent? {
        obtainWithDisplay?.let { method ->
            try {
                val count = event.pointerCount
                val properties = Array(count) { index ->
                    MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
                }
                val coords = Array(count) { index ->
                    MotionEvent.PointerCoords().also { event.getPointerCoords(index, it) }
                }
                val built = method.invoke(
                    null,
                    event.downTime,
                    event.eventTime,
                    event.action,
                    count,
                    properties,
                    coords,
                    event.metaState,
                    event.buttonState,
                    event.xPrecision,
                    event.yPrecision,
                    VIRTUAL_DEVICE_ID,
                    event.edgeFlags,
                    event.source,
                    displayId,
                    0
                ) as? MotionEvent
                if (built != null) return built
            } catch (t: Throwable) {
                // Fall through to setDisplayId.
            }
        }
        return try {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
            event
        } catch (t: Throwable) {
            // Without a display id the event would land on the car's main screen, which is worse
            // than dropping it.
            null
        }
    }

    companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        private const val MODE_ASYNC = 0

        /** A virtual device id: the event did not come from a real input device. */
        private const val VIRTUAL_DEVICE_ID = 0

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (hidden constants).
        private const val FLAG_PUBLIC = 1
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_TRUSTED = 1 shl 10

        /**
         * A trusted display is the only kind that may host another app's activity; the weaker
         * sets keep the older behaviour working on a firmware that refuses TRUSTED even to the
         * shell UID.
         */
        private val FLAG_SETS = intArrayOf(
            FLAG_TRUSTED or FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_SUPPORTS_TOUCH,
            0
        )
    }
}
