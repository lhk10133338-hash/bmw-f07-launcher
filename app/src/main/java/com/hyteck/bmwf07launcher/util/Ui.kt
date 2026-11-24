package com.hyteck.bmwf07launcher.util

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Toast

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Full-screen, edge-to-edge black window for the launcher. */
fun Activity.applyImmersive() {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}
