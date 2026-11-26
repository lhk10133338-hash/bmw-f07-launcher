package com.hyteck.bmwf07launcher.engine

import android.view.InputEvent
import java.lang.reflect.Method

/**
 * The hidden `IWindowManager` calls CarCar Launcher makes around its virtual displays (v4.0).
 *
 * These were read out of the original APK: `La8/c0` (the plugin's virtual-display manager) calls
 * `IWindowManager.syncInputTransactions(boolean)`, and the AppView view (`Lw7/g`) calls
 * `IWindowManager.dontOverrideDisplayInfo(int)` right after creating its display, plus
 * `IWindowManager.injectInputAfterTransactionsApplied(InputEvent, int)` to send a touch into that
 * display after the display transaction that resized it has been applied.
 *
 * Both are `@hide` and are not declared in the public SDK, so they are looked up by name and every
 * failure is swallowed: a firmware without them keeps working through the public fallbacks.
 */
internal object WindowService {

    /** `WindowManagerGlobal.getWindowManagerService()` — the IWindowManager binder proxy. */
    private val service: Any? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            cls.getMethod("getWindowManagerService").invoke(null)
        } catch (t: Throwable) {
            null
        }
    }

    private val dontOverrideDisplayInfo: Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        method("dontOverrideDisplayInfo", Int::class.javaPrimitiveType!!)
    }

    private val syncInputTransactions: Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        method("syncInputTransactions", Boolean::class.javaPrimitiveType!!)
    }

    private val injectAfterTransactions: Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        method(
            "injectInputAfterTransactionsApplied",
            InputEvent::class.java,
            Int::class.javaPrimitiveType!!
        )
    }

    private fun method(name: String, vararg types: Class<*>): Method? = try {
        service?.javaClass?.getMethod(name, *types)?.also { it.isAccessible = true }
    } catch (t: Throwable) {
        null
    }

    fun isAvailable(): Boolean = service != null

    /**
     * Tells WindowManager not to rewrite the size/density we asked for.
     *
     * CarCar calls this immediately after `createVirtualDisplay`; without it some firmwares
     * replace the requested metrics with the default display's, which is what made an earlier
     * version's panes come back at the wrong size.
     */
    fun dontOverrideDisplayInfo(displayId: Int) {
        if (displayId < 0) return
        val method = dontOverrideDisplayInfo ?: return
        try {
            method.invoke(service, displayId)
        } catch (t: Throwable) {
            // Firmware without the call: the display keeps the platform's own metrics.
        }
    }

    /**
     * Waits for the display transactions that are in flight (a resize, a surface swap) to reach
     * the input dispatcher. CarCar brackets a touch injection with this so a finger that arrives
     * right after a divider move lands in the display at its new geometry.
     */
    fun syncInputTransactions(sync: Boolean = true) {
        val method = syncInputTransactions ?: return
        try {
            method.invoke(service, sync)
        } catch (t: Throwable) {
            // Optional on this firmware.
        }
    }

    /**
     * Injects [event] after the pending display transactions are applied. Returns false when the
     * firmware has no such call, so the caller can fall back to `InputManager.injectInputEvent`.
     */
    fun injectAfterTransactions(event: InputEvent, mode: Int): Boolean {
        val method = injectAfterTransactions ?: return false
        return try {
            (method.invoke(service, event, mode) as? Boolean) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    /** `VirtualDisplay.setDisplayState(boolean)` — freezes/unfreezes a hidden pane (CarCar). */
    fun setDisplayState(display: Any?, on: Boolean): Boolean {
        if (display == null) return false
        return try {
            val method = Class.forName("android.hardware.display.VirtualDisplay")
                .getMethod("setDisplayState", Boolean::class.javaPrimitiveType!!)
            method.isAccessible = true
            method.invoke(display, on)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
