package com.hyteck.bmwf07launcher.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.MotionEvent
import com.hyteck.bmwf07launcher.IShizukuControl
import com.hyteck.bmwf07launcher.service.ShizukuControlService
import rikka.shizuku.Shizuku

/**
 * The launcher's side of Shizuku, v3.4.
 *
 * Shizuku (a separate app the user installs once) lets an ordinary app run a small service inside
 * a process that has the adb shell identity. This launcher uses exactly one thing from it: the
 * touch injection of [ShizukuControlService], which makes the two virtual-display panes actually
 * usable.
 *
 * The bridge stays silent and harmless when Shizuku is missing: every call is a state check, and
 * the launcher then falls back to the accessibility-gesture route and, without that too, to the
 * free-form engine.
 */
object ShizukuBridge {

    enum class State {
        /** Shizuku is not installed, or its service is not running. */
        NOT_RUNNING,

        /** Shizuku is running but this app has not been granted access yet. */
        PERMISSION,

        /** Access granted; the shell-side touch service is being started. */
        CONNECTING,

        /** Touches can be injected into a virtual display right now. */
        READY
    }

    @Volatile
    private var control: IShizukuControl? = null

    @Volatile
    private var starting = false

    private var appContext: Context? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private val listeners = LinkedHashSet<(State) -> Unit>()

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else {
                notifyListeners()
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        bind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        control = null
        starting = false
        notifyListeners()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            control = service?.let { IShizukuControl.Stub.asInterface(it) }
            starting = false
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            control = null
            starting = false
            notifyListeners()
        }
    }

    /** Idempotent: every screen of the launcher may call this in `onCreate`. */
    fun init(context: Context) {
        if (appContext != null) {
            bind()
            return
        }
        val app = context.applicationContext
        appContext = app
        userServiceArgs = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ShizukuControlService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("touch")
            .version(SERVICE_VERSION)
            .debuggable(false)
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            // A Shizuku too old for these callbacks: the state checks still work.
        }
        bind()
    }

    /** Re-checks the state; called from `onResume` so a Shizuku started later is picked up. */
    fun refresh() = bind()

    val state: State
        get() {
            if (!isRunning()) return State.NOT_RUNNING
            if (!isGranted()) return State.PERMISSION
            return if (control != null) State.READY else State.CONNECTING
        }

    /** True when a touch can be injected right now. */
    fun isReady(): Boolean = control != null && isRunning()

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Opens Shizuku's own permission dialog. Must be called from an Activity. */
    fun requestPermission(requestCode: Int): Boolean = try {
        Shizuku.requestPermission(requestCode)
        true
    } catch (e: Throwable) {
        false
    }

    /**
     * Hands one touch event to the shell side. Returns false when no injection took place, so the
     * caller can fall back to the accessibility route.
     */
    fun injectTouch(displayId: Int, event: MotionEvent): Boolean {
        val service = control ?: return false
        if (displayId < 0) return false
        return try {
            service.injectTouch(displayId, event)
            true
        } catch (e: Throwable) {
            // Dead binder or a refused event: try the fallback.
            false
        }
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun bind() {
        val args = userServiceArgs ?: return
        if (control != null || starting) return
        if (!isRunning() || !isGranted()) {
            notifyListeners()
            return
        }
        starting = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            starting = false
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val current = state
        // The listeners only touch the UI; they are posted by the caller's own thread check.
        for (listener in listeners.toList()) {
            try {
                listener(current)
            } catch (e: Exception) {
                // A listener must never break the bridge.
            }
        }
    }

    /** Bump this when [ShizukuControlService] changes, so Shizuku restarts the old instance. */
    private const val SERVICE_VERSION = 2
}
