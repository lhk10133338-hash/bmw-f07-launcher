package com.hyteck.bmwf07launcher.engine

import java.lang.reflect.Method

/**
 * The hidden `IWindowManager` calls CarCar Launcher makes around its virtual displays (v4.0).
 *
 * These were read out of the original APK: `La8/c0` (the plugin's virtual-display manager) calls
 * `IWindowManager.syncInputTransactions(boolean)`, and the AppView view (`Lw7/g`) calls
 * `IWindowManager.dontOverrideDisplayInfo(int)` right after creating its display.
 *
 * **v4.6 removed `injectInputAfterTransactionsApplied` from here.** The AppView of the original
 * calls it from its own window's thread; a launcher that calls it from the thread its fingers
 * arrive on waits for the display transactions of a *replaced* display and freezes the whole
 * screen (see [com.hyteck.bmwf07launcher.touch.SystemTouchInjector]). A touch is now always
 * `InputManager.injectInputEvent(..., MODE_ASYNC)` on a thread of its own.
 *
 * The remaining calls are `@hide` and are not declared in the public SDK, so they are looked up by
 * name and every failure is swallowed: a firmware without them keeps working through the public
 * fallbacks.
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
     *
     * This is a blocking binder call. It must never be made from the launcher's main thread —
     * [com.hyteck.bmwf07launcher.touch.SystemTouchInjector] calls it on its own injector thread.
     */
    fun syncInputTransactions(sync: Boolean = true) {
        val method = syncInputTransactions ?: return
        try {
            method.invoke(service, sync)
        } catch (t: Throwable) {
            // Optional on this firmware.
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
