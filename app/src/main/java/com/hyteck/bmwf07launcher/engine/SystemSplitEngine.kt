package com.hyteck.bmwf07launcher.engine

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService

/**
 * Launches each app as its own task with explicit bounds, using the system free-form /
 * multi-window implementation.
 *
 * A normal app cannot move another app's window, so a ratio change is applied by starting
 * that app again with `FLAG_ACTIVITY_REORDER_TO_FRONT` and the new launch bounds: the task is
 * brought back to the front (without recreating the activity when the app supports it) and the
 * window manager lays it out in the new rectangle. This is the fix for the "pane shrinks once
 * and then never grows back" report — before, bounds were only read once at launch time.
 */
class SystemSplitEngine(
    private val context: Context,
    private val onMessage: (String) -> Unit
) : SplitEngine {

    override val inline: Boolean = false
    override val supportsDpi: Boolean = false

    private val packages = HashMap<Side, String>()
    private val bounds = HashMap<Side, Rect>()
    private val applied = HashMap<Side, Rect>()
    private var launchFailureReported = false

    override fun launch(side: Side, packageName: String) {
        packages[side] = packageName
        applied.remove(side)
        val rect = bounds[side] ?: Rect()
        start(side, packageName, rect, reorder = false)
    }

    override fun applyBounds(left: Rect, right: Rect, commit: Boolean) {
        bounds[Side.LEFT] = Rect(left)
        bounds[Side.RIGHT] = Rect(right)
        if (!commit) return
        // Re-place the running apps in the new rectangles.
        for (side in Side.values()) {
            val pkg = packages[side] ?: continue
            val rect = bounds[side] ?: continue
            if (rect.width() <= 0 || rect.height() <= 0) continue
            val previous = applied[side]
            if (previous != null && previous == rect) continue
            start(side, pkg, rect, reorder = true)
        }
    }

    override fun clear(side: Side) {
        packages.remove(side)
        applied.remove(side)
    }

    override fun setDensity(side: Side, dpi: Int) {
        // System windows use the density of the display they run on.
    }

    override fun release() {
        packages.clear()
        applied.clear()
    }

    private fun start(side: Side, packageName: String, rect: Rect, reorder: Boolean) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            onMessage("앱을 찾을 수 없습니다: $packageName")
            return
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        if (reorder) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        } else {
            // A separate task per pane, so the same app can sit in both slots and the
            // background copy of the app is not stolen from its own task.
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        val options = ActivityOptions.makeBasic()
        if (rect.width() > 0 && rect.height() > 0) {
            options.setLaunchBounds(rect)
        }
        applyFreeformMode(options)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Keeps the launch inside the launcher's task area on Android 11+.
            try {
                options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            } catch (ignored: Throwable) {
                // Optional hint only.
            }
        }

        val bundle = options.toBundle()
        applied[side] = Rect(rect)
        try {
            val service = LauncherAccessibilityService.instance
            if (service != null && reorder) {
                service.startActivity(intent, bundle)
            } else {
                context.startActivity(intent, bundle)
            }
            launchFailureReported = false
        } catch (e: Exception) {
            if (!launchFailureReported) {
                launchFailureReported = true
                onMessage("앱 실행에 실패했습니다: ${e.message}")
            }
        }
    }

    /**
     * Asks for a free-form window so the bounds are honoured even when the device is not
     * already in split-screen. This option is a hidden API on some releases, so failure is
     * ignored (the bounds are still passed).
     */
    private fun applyFreeformMode(options: ActivityOptions) {
        try {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType!!
            )
            method.invoke(options, WINDOWING_MODE_FREEFORM)
        } catch (ignored: Throwable) {
            // Not available: launch with bounds only.
        }
    }

    companion object {
        private const val WINDOWING_MODE_FREEFORM = 5
    }
}
