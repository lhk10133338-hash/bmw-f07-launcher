package com.hyteck.bmwf07launcher.service

import android.view.InputEvent
import android.view.MotionEvent
import com.hyteck.bmwf07launcher.IShizukuControl

/**
 * The shell side of the launcher's touch pipeline (v3.4).
 *
 * Shizuku starts this class in a separate process that runs with the adb shell UID (2000). That
 * UID holds `android.permission.INJECT_EVENTS`, so it is allowed to do what the launcher itself
 * may not: push a touch event into a display that is not the default one. That is the missing
 * piece of the virtual-display engine — both panes are drawn inside the launcher, so the launcher
 * receives the touches, but the apps running on the two virtual displays never saw them.
 *
 * Only two hidden-API reflections are used, and both are resolved lazily so a firmware that lacks
 * one simply disables touch instead of crashing the service:
 *  - `android.hardware.input.InputManager#getInstance()` / `#injectInputEvent(InputEvent, int)`
 *  - `MotionEvent.obtain(..., displayId, flags)`, the 15-argument overload that carries the
 *    target display (the same one scrcpy uses); `setDisplayId(int)` is tried as a fallback.
 *
 * The event is *not* recycled: an asynchronous injection may still be reading it.
 */
class ShizukuControlService : IShizukuControl.Stub() {

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
    }
}
