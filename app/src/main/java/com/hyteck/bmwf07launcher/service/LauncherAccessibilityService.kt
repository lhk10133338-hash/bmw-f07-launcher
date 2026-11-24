package com.hyteck.bmwf07launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * The accessibility service has two jobs, and it never reads screen content:
 *
 *  - it can start activities with explicit bounds (used by
 *    [com.hyteck.bmwf07launcher.engine.SystemSplitEngine]) in situations where the launcher itself
 *    is not allowed to;
 *  - v3.4: it replays a touch as a gesture *on a chosen display*
 *    ([dispatchVirtualTouch]), so the apps running on the panes' virtual displays can be used even
 *    when Shizuku is not installed. Android 11 added
 *    `GestureDescription.Builder.setDisplayId`, which is what makes that possible.
 */
class LauncherAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No screen content is read.
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Replays [path] as a one-finger gesture on the display [displayId], taking [durationMs].
     *
     * Returns false when this Android release cannot target another display (below Android 11) —
     * the gesture would otherwise land on the car's own screen, which is worse than doing nothing.
     */
    fun dispatchVirtualTouch(displayId: Int, path: Path, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (path.isEmpty) return false
        return try {
            val stroke = GestureDescription.StrokeDescription(
                path,
                0L,
                durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
            )
            val gesture = GestureDescription.Builder()
                .setDisplayId(displayId)
                .addStroke(stroke)
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Throwable) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: LauncherAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            val enabled =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            return enabled.any { info ->
                info.resolveInfo?.serviceInfo?.packageName == context.packageName
            }
        }
    }
}
