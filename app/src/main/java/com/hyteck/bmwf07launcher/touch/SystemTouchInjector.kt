package com.hyteck.bmwf07launcher.touch

import android.content.Context
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.hyteck.bmwf07launcher.engine.Privileges
import com.hyteck.bmwf07launcher.engine.WindowService
import java.lang.reflect.Method

/**
 * Sends a touch into a virtual display from a process that holds `INJECT_EVENTS` — the SystemUID
 * build, and the shell-side service the standard build runs through Shizuku (v4.0).
 *
 * The sequence is the one CarCar Launcher uses (`La8/c0.i(MotionEvent)` in the original APK):
 *
 *  1. `event.setDisplayId(displayId)` — the event is addressed to the pane's display.
 *  2. Before a touch-down (and a pointer event) `IWindowManager.syncInputTransactions(true)` so a
 *     resize that just happened is already in effect.
 *  3. `InputManager.injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC)` — asynchronous, so a
 *     drag never blocks the launcher's UI thread.
 *  4. After a non-up event the transactions are synchronised again.
 *
 * Where the firmware offers it, the stronger `IWindowManager.injectInputAfterTransactionsApplied`
 * (the AppView path in the same APK) is preferred: it delivers the event only once the display
 * transaction it belongs to has been applied, which removes the one-frame jump at the start of a
 * drag. Mouse hover/scroll events skip the synchronisation, exactly as in the original.
 */
object SystemTouchInjector {

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
    private const val MODE_ASYNC = 0

    /** MotionEvent source bits used by the original code. */
    private const val SOURCE_MOUSE = 0x2002

    private val inputManager: Any? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance")
                .invoke(null)
        } catch (t: Throwable) {
            null
        }
    }

    private val injectInputEvent: Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            Class.forName("android.hardware.input.InputManager").getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType!!
            ).also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    private val setDisplayId: Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            MotionEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType!!
            ).also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    /** True when this process may inject input right now. */
    fun canInject(context: Context): Boolean = Privileges.canInjectInput(context)

    /**
     * Injects [event] into [displayId]. Returns false when no route was available, so the caller
     * can fall back to the accessibility gesture.
     */
    fun inject(displayId: Int, event: MotionEvent): Boolean {
        if (displayId < 0) return false
        if (!addressTo(displayId, event)) return false

        if (WindowService.injectAfterTransactions(event, MODE_ASYNC)) return true

        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false

        val action = event.actionMasked
        val fromMouse = event.isFromSource(SOURCE_MOUSE)
        val syncBefore = if (fromMouse) {
            action != MotionEvent.ACTION_SCROLL && action != MotionEvent.ACTION_MOVE
        } else {
            action == MotionEvent.ACTION_DOWN ||
                action == MotionEvent.ACTION_POINTER_DOWN ||
                action == MotionEvent.ACTION_HOVER_ENTER
        }
        val syncAfter = action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_CANCEL &&
            action != MotionEvent.ACTION_HOVER_EXIT

        if (syncBefore) WindowService.syncInputTransactions(true)
        val sent = try {
            (method.invoke(manager, event, MODE_ASYNC) as? Boolean) ?: false
        } catch (t: Throwable) {
            false
        }
        if (syncAfter) WindowService.syncInputTransactions(true)
        return sent
    }

    /**
     * Sends the BACK key to the app on [displayId], the way the AppView of the original launcher
     * does (`KeyEvent` with `setDisplayId` through the same injection path).
     */
    fun injectBack(displayId: Int): Boolean {
        if (displayId < 0) return false
        val down = keyEvent(KeyEvent.ACTION_DOWN, displayId)
        val up = keyEvent(KeyEvent.ACTION_UP, displayId)
        return injectKey(down) && injectKey(up)
    }

    private fun injectKey(event: KeyEvent): Boolean {
        if (WindowService.injectAfterTransactions(event, MODE_ASYNC)) return true
        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false
        return try {
            (method.invoke(manager, event, MODE_ASYNC) as? Boolean) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    private fun keyEvent(action: Int, displayId: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now,
            now,
            action,
            KeyEvent.KEYCODE_BACK,
            /* repeat = */ 0,
            /* metaState = */ 0,
            /* deviceId = */ -1,
            /* scancode = */ 0,
            /* flags = */ 0,
            /* source = */ 0
        )
        try {
            KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType!!)
                .invoke(event, displayId)
        } catch (t: Throwable) {
            // Without a display id the key would go to the car's main screen.
        }
        return event
    }

    /** Points [event] at [displayId]; false when the platform hides `setDisplayId`. */
    private fun addressTo(displayId: Int, event: MotionEvent): Boolean {
        val method = setDisplayId ?: return false
        return try {
            method.invoke(event, displayId)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
