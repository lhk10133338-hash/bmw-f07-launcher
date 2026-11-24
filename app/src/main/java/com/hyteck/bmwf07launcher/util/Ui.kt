package com.hyteck.bmwf07launcher.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * Full-screen, edge-to-edge black window for the launcher.
 *
 * v3.2: the old `systemUiVisibility` flags alone were not enough — on LDPlayer (and on some head
 * units) the status bar with the clock and the battery icons stayed on screen, and the navigation
 * bar kept coming back after a swipe. So this now
 *
 *  1. tells the window to draw behind the system bars (`setDecorFitsSystemWindows(false)`, so the
 *     panes reach the very edge of the screen),
 *  2. hides both bars through [WindowInsetsControllerCompat] and sets the "immersive sticky"
 *     behaviour, where a swipe only shows them as a translucent overlay that fades out again,
 *  3. keeps the legacy flags as well for Android 9/10 devices whose compatibility layer does not
 *     forward the inset controller,
 *  4. lets the content paint into a display cutout, so a notch never pushes a pane inward.
 *
 * It is called again from `onResume` and `onWindowFocusChanged` of every screen: the bars come
 * back after a system dialog, the keyboard or a focus change, and hiding them again is the only
 * way to keep the launcher completely full screen.
 */
fun Activity.applyImmersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    // Sticky immersive: a swipe from the edge only shows the bars for a moment.
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LOW_PROFILE
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}

/**
 * Long press of a plain view after [timeoutMs] (~0.8 s), while a short touch still runs the
 * view's own click listener.
 *
 * The platform's own long-press timeout is shorter and, on the thin pane strips of this launcher,
 * a finger that rests for a moment before lifting used to fire a click instead of the menu. Here
 * the menu opens as soon as the finger has stayed on the view for [timeoutMs], the view gives a
 * short haptic tick, and the finger-up that ends that long press is swallowed so the view does
 * not also perform its click afterwards.
 */
fun View.setOnLongPressAction(timeoutMs: Long = DEFAULT_LONG_PRESS_MS, onLongPress: () -> Unit) {
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    val view = this
    setOnTouchListener(object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var fired = false

        private val fire = Runnable {
            fired = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    fired = false
                    v.postDelayed(fire, timeoutMs)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!fired && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                        v.removeCallbacks(fire)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(fire)
                    if (fired) {
                        // The menu is already open: do not let the same gesture click through it.
                        v.isPressed = false
                        return true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(fire)
                    v.isPressed = false
                }
            }
            return false
        }
    })
}

/** Long press used by the pane strip and the empty pane (user wording: "약 1초"). */
const val DEFAULT_LONG_PRESS_MS = 800L
