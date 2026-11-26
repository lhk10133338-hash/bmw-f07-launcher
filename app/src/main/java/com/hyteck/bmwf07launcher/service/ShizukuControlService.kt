package com.hyteck.bmwf07launcher.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import com.hyteck.bmwf07launcher.IShizukuControl
import com.hyteck.bmwf07launcher.engine.WindowService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * v4.0 follows the original CarCar engine more closely: the display is created without a surface,
 * `IWindowManager.dontOverrideDisplayInfo` keeps our metrics, the surface and the exact size are
 * applied afterwards, and a touch is injected with
 * `IWindowManager.injectInputAfterTransactionsApplied` where the firmware has it, otherwise with
 * `syncInputTransactions` + `InputManager.injectInputEvent` as that plugin does.
 *
 * Only hidden APIs are used, and every one of them is resolved lazily and defensively: a firmware
 * that lacks one disables that single feature instead of crashing the service.
 */
class ShizukuControlService : IShizukuControl.Stub() {

    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    /**
     * A thread with a Looper, used for everything that talks to the display manager.
     *
     * Binder calls arrive on plain binder threads, which have no Looper, and
     * `createVirtualDisplay` builds its callback handler from the calling thread. A dedicated
     * looper thread also keeps the display alive after the calling thread ends.
     */
    private val displayHandler: Handler? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            val ready = CountDownLatch(1)
            var created: Handler? = null
            val thread = Thread({
                Looper.prepare()
                created = Handler(Looper.myLooper()!!)
                ready.countDown()
                Looper.loop()
            }, "bmw-f07-display")
            thread.isDaemon = true
            thread.start()
            if (!ready.await(DISPLAY_THREAD_TIMEOUT_S, TimeUnit.SECONDS)) {
                null
            } else {
                created
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Runs [block] on the display thread and waits for its result (bounded). */
    private fun <T> onDisplayThread(fallback: T, block: () -> T): T {
        val handler = displayHandler ?: return fallback
        if (Looper.myLooper() === handler.looper) {
            return try {
                block()
            } catch (t: Throwable) {
                fallback
            }
        }
        val done = CountDownLatch(1)
        var result = fallback
        handler.post {
            result = try {
                block()
            } catch (t: Throwable) {
                fallback
            }
            done.countDown()
        }
        done.await(DISPLAY_THREAD_TIMEOUT_S, TimeUnit.SECONDS)
        return result
    }

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
        if (width <= 0 || height <= 0) return -1
        val safeName = name ?: "bmw-f07"
        return onDisplayThread(-1) {
            val manager = displayManager ?: return@onDisplayThread -1
            for (flags in FLAG_SETS) {
                try {
                    // CarCar: the display is created without a surface, then resized to the pane
                    // rectangle and given the surface.
                    val display = manager.createVirtualDisplay(
                        safeName,
                        width,
                        height,
                        dpi,
                        null,
                        flags
                    )
                    if (display != null) {
                        val id = display.display.displayId
                        displays[id] = display
                        WindowService.dontOverrideDisplayInfo(id)
                        // v4.1: surface first, size after it (CarCar's order); the other way
                        // round can leave the output and the display on different sizes.
                        if (surface != null) {
                            try {
                                display.surface = surface
                            } catch (t: Throwable) {
                                // The launcher reattaches its surface on the next callback.
                            }
                        }
                        try {
                            display.resize(width, height, dpi)
                        } catch (t: Throwable) {
                            // Optional: the size is already the requested one.
                        }
                        return@onDisplayThread id
                    }
                } catch (t: Throwable) {
                    // A refused flag set (typically TRUSTED without ADD_TRUSTED_DISPLAY): next.
                }
            }
            -1
        }
    }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return onDisplayThread(false) {
            val display = displays[displayId] ?: return@onDisplayThread false
            if (display.display == null) return@onDisplayThread false
            try {
                display.resize(width, height, dpi)
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    override fun setDisplaySurface(displayId: Int, surface: Surface?): Boolean {
        return onDisplayThread(false) {
            val display = displays[displayId] ?: return@onDisplayThread false
            try {
                display.surface = surface
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    override fun releaseDisplay(displayId: Int) {
        val display = displays.remove(displayId) ?: return
        onDisplayThread(false) {
            try {
                display.release()
            } catch (t: Throwable) {
                // Already gone.
            }
            true
        }
    }

    override fun setDisplayState(displayId: Int, on: Boolean): Boolean {
        val display = displays[displayId] ?: return false
        return onDisplayThread(false) {
            WindowService.setDisplayState(display, on)
        }
    }

    // --------------------------------------------------------------- touch

    override fun isReady(): Boolean = inputManager != null && injectMethod != null

    override fun injectTouch(displayId: Int, event: MotionEvent?) {
        if (event == null || displayId < 0) return
        val manager = inputManager ?: return
        val inject = injectMethod ?: return
        val target = withDisplay(event, displayId) ?: return
        // CarCar's AppView path: the event is delivered once the display transactions in flight
        // (a resize, a surface swap) have been applied.
        if (WindowService.injectAfterTransactions(target, MODE_ASYNC)) return
        val action = target.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN ||
            action == MotionEvent.ACTION_HOVER_ENTER
        ) {
            WindowService.syncInputTransactions(true)
        }
        try {
            // MODE_ASYNC (0): never block the launcher's UI thread on the input dispatcher, which
            // is what keeps a drag smooth.
            inject.invoke(manager, target, MODE_ASYNC)
        } catch (t: Throwable) {
            // A refused injection is not fatal: the next event may be accepted.
        }
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            WindowService.syncInputTransactions(true)
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

        /** How long a display call may block the calling binder thread. */
        private const val DISPLAY_THREAD_TIMEOUT_S = 3L

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (hidden constants).
        private const val FLAG_PUBLIC = 1
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

        /**
         * CarCar Launcher's own flag set (3337 = 0xD09) comes first; the weaker sets keep the
         * older behaviour working on a firmware that refuses TRUSTED even to the shell UID.
         */
        private val FLAG_SETS = intArrayOf(
            FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_DESTROY_CONTENT_ON_REMOVAL or
                FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP,
            FLAG_TRUSTED or FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH or FLAG_OWN_CONTENT_ONLY,
            FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            FLAG_SUPPORTS_TOUCH,
            0
        )
    }
}
