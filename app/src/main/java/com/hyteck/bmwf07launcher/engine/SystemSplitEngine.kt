package com.hyteck.bmwf07launcher.engine

import android.app.ActivityOptions
import android.content.Context
import android.graphics.Rect
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService

/**
 * Launches each app as its own task with explicit bounds, using the system
 * multi-window / freeform implementation. The accessibility service is used
 * when available because it may start activities that the launcher alone
 * cannot while it is not in the foreground.
 */
class SystemSplitEngine(
    private val context: Context,
    private val boundsProvider: () -> Pair<Rect, Rect>,
    private val onMessage: (String) -> Unit
) : SplitEngine {

    override val inline: Boolean = false

    override fun launch(side: Side, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            onMessage("앱을 찾을 수 없습니다: $packageName")
            return
        }
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        val bounds = boundsProvider()
        val rect = if (side == Side.LEFT) bounds.first else bounds.second
        startWithBounds(intent, rect)
    }

    private fun startWithBounds(intent: android.content.Intent, rect: Rect) {
        val options = ActivityOptions.makeBasic()
        if (rect.width() > 0 && rect.height() > 0) {
            options.setLaunchBounds(rect)
        }
        applyFreeformMode(options)
        val bundle = options.toBundle()
        try {
            val service = LauncherAccessibilityService.instance
            if (service != null) {
                service.startActivity(intent, bundle)
            } else {
                context.startActivity(intent, bundle)
            }
        } catch (e: Exception) {
            onMessage("앱 실행에 실패했습니다: ${e.message}")
        }
    }

    /**
     * Asks for a free-form window so the bounds are honoured even when the
     * device is not already in split-screen. This option is a hidden API on
     * some releases, so failure is ignored (the bounds are still passed).
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

    override fun close(side: Side) {
        // Nothing to release: the apps are separate system tasks.
    }

    override fun setDensity(side: Side, dpi: Int) {
        // System windows receive the density of their own display.
    }

    override fun resize(side: Side) {
        // Bounds are read at launch time.
    }

    override fun release() {
        // Nothing to release.
    }

    companion object {
        private const val WINDOWING_MODE_FREEFORM = 5
    }
}
