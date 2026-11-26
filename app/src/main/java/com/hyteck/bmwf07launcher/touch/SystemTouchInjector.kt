package com.hyteck.bmwf07launcher.touch

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.hyteck.bmwf07launcher.engine.Privileges
import com.hyteck.bmwf07launcher.engine.WindowService
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends a touch into a virtual display from a process that holds `INJECT_EVENTS` — the SystemUID
 * build, and the shell-side service the standard build runs through Shizuku (v4.0).
 *
 * ## v4.6 — the injection never runs on the launcher's UI thread (2026-11-26)
 *
 * The car unit reported v4.5 as: the split ratio (a preset or a divider drag) makes **the whole
 * screen stop responding to touch**, and only a restart brings it back. The touch listener runs on
 * the launcher's main thread, and until v4.6 it did the platform work there too:
 *
 *  - `IWindowManager.injectInputAfterTransactionsApplied` (CarCar uses it from its *AppView*, never
 *    from a view that shares the launcher's own thread) — a synchronous binder call that waits for
 *    the display transactions in flight to be applied, and
 *  - `IWindowManager.syncInputTransactions(true)` after **every non-up event**, i.e. on every
 *    `ACTION_MOVE` of a drag, because the condition was the exact inverse of the original's.
 *
 * A ratio change replaces both panes' displays, so those two calls meet a display being created,
 * a task being moved and the old display being released — exactly the moment the input dispatcher
 * and the window manager are busy with this process. A binder call that waits there blocks the
 * launcher's main thread, the launcher owns the whole screen (and the focus), and nothing that
 * arrives on display 0 is handled any more. That is the freeze; a restart starts a new process and
 * a new main thread, which is why the user's "껐다 키면 다시 동작해" was always true.
 *
 * v4.6 therefore keeps this object on one rule: **the UI thread only copies the event**. The copy
 * is handed to a dedicated looper thread ([InjectThread]) that addresses it to the display, does
 * the CarCar synchronisation around it and injects it asynchronously (`MODE_ASYNC`), then recycles
 * the copy. Nothing here can block the thread a finger is delivered on, so even if a binder call
 * were to hang, the panes go quiet instead of the whole screen.
 *
 * The sequence is the one CarCar Launcher uses (`La8/c0.i(MotionEvent)` in the original APK):
 *
 *  1. `event.setDisplayId(displayId)` — the event is addressed to the pane's display.
 *  2. Before a touch-down (and a pointer event) `IWindowManager.syncInputTransactions(true)` so a
 *     resize that just happened is already in effect.
 *  3. `InputManager.injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC)` — asynchronous, so a
 *     drag never blocks on the input dispatcher.
 *  4. Only when the gesture **ends** (`ACTION_UP` / `ACTION_CANCEL` / `ACTION_HOVER_EXIT`) the
 *     transactions are synchronised again, the way the original does it.
 *
 * Mouse hover/scroll events skip the synchronisation, exactly as in the original.
 */
object SystemTouchInjector {

    private const val TAG = "MNLauncher"

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
    private const val MODE_ASYNC = 0

    /** MotionEvent source bits used by the original code. */
    private const val SOURCE_MOUSE = 0x2002

    /**
     * How many touch events may wait for the injector thread at once.
     *
     * A drag can produce an event every 8 ms while a synchronisation is in flight. Past this bound
     * only the intermediate moves are dropped, never a down or an up: a gesture keeps its start
     * and its end, and a queue that somehow stalls cannot grow without limit.
     */
    private const val MAX_PENDING = 24

    /** Cached answer of [canInject]: a permission query is an IPC and must not run per event. */
    @Volatile
    private var injectAllowed: Boolean? = null

    private val pending = AtomicInteger(0)
    private var dropReported = false

    /** The one thread that talks to the platform for a touch. Started on the first event. */
    private val thread: HandlerThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        InjectThread().apply { start() }
    }

    private val handler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(thread.looper)
    }

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
    fun canInject(context: Context): Boolean {
        injectAllowed?.let { return it }
        val allowed = Privileges.canInjectInput(context)
        injectAllowed = allowed
        return allowed
    }

    /**
     * Hands [event] to the injector thread and returns true when the route exists.
     *
     * The caller is the pane's `SurfaceView` touch listener, i.e. the launcher's main thread: the
     * only work done here is `MotionEvent.obtain(event)`, because the platform recycles the
     * original as soon as the listener returns. Everything else — the display id, the transaction
     * synchronisation, the injection — happens on [InjectThread], and false is returned only when
     * this process has no injection route at all, so the caller can fall back to Shizuku or to the
     * accessibility gesture.
     */
    fun inject(displayId: Int, event: MotionEvent): Boolean {
        if (displayId < 0) return false
        if (setDisplayId == null) return false
        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_MOVE && pending.get() >= MAX_PENDING) {
            if (!dropReported) {
                dropReported = true
                Log.w(TAG, "touch injector is behind ($MAX_PENDING waiting); dropping move events")
            }
            // Consumed all the same: the gesture is ours, a fallback replay would double it.
            return true
        }

        val copy = try {
            MotionEvent.obtain(event)
        } catch (t: Throwable) {
            return false
        }
        pending.incrementAndGet()
        val posted = try {
            handler.post {
                try {
                    deliver(manager, method, displayId, copy)
                } finally {
                    copy.recycle()
                    pending.decrementAndGet()
                }
            }
        } catch (t: Throwable) {
            false
        }
        if (!posted) {
            // The injector thread could not be reached: give the event back and let the caller use
            // its next route.
            copy.recycle()
            pending.decrementAndGet()
            return false
        }
        return true
    }

    /**
     * The CarCar sequence for one event, on [InjectThread]. `MODE_ASYNC` plus a synchronisation
     * only around the ends of a gesture: nothing here waits for a frame of a drag.
     */
    private fun deliver(manager: Any, method: Method, displayId: Int, event: MotionEvent) {
        if (!addressTo(displayId, event)) return

        val action = event.actionMasked
        val fromMouse = event.isFromSource(SOURCE_MOUSE)
        val syncBefore = if (fromMouse) {
            action != MotionEvent.ACTION_SCROLL && action != MotionEvent.ACTION_MOVE
        } else {
            action == MotionEvent.ACTION_DOWN ||
                action == MotionEvent.ACTION_POINTER_DOWN ||
                action == MotionEvent.ACTION_HOVER_ENTER
        }
        // `La8/c0.i`: a gesture is bracketed, not a frame. v4.5 had this inverted and synchronised
        // after every move of a drag.
        val syncAfter = action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL ||
            action == MotionEvent.ACTION_HOVER_EXIT

        if (syncBefore) WindowService.syncInputTransactions(true)
        val sent = try {
            (method.invoke(manager, event, MODE_ASYNC) as? Boolean) ?: false
        } catch (t: Throwable) {
            false
        }
        if (!sent) {
            Log.d(TAG, "touch inject refused (display $displayId, action $action)")
        }
        if (syncAfter) WindowService.syncInputTransactions(true)
    }

    /**
     * Sends the BACK key to the app on [displayId], the way the AppView of the original launcher
     * does (`KeyEvent` with `setDisplayId` through the same injection path).
     */
    fun injectBack(displayId: Int): Boolean {
        if (displayId < 0) return false
        val manager = inputManager ?: return false
        val method = injectInputEvent ?: return false
        val down = keyEvent(KeyEvent.ACTION_DOWN, displayId)
        val up = keyEvent(KeyEvent.ACTION_UP, displayId)
        return try {
            handler.post {
                injectKey(manager, method, down)
                injectKey(manager, method, up)
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun injectKey(manager: Any, method: Method, event: KeyEvent) {
        try {
            method.invoke(manager, event, MODE_ASYNC)
        } catch (t: Throwable) {
            // A refused key is not fatal: the next one may be accepted.
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

    /**
     * The injector thread. A touch that arrives is worth a display priority: it is what makes a
     * drag follow a finger on the pane.
     */
    private class InjectThread : HandlerThread("MN-TouchInject") {
        override fun onLooperPrepared() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            } catch (t: Throwable) {
                // The default priority is fine.
            }
        }
    }
}
