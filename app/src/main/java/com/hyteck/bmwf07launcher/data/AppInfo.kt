package com.hyteck.bmwf07launcher.data

import android.graphics.drawable.Drawable

/**
 * A launchable application shown in the dock / drawer / picker.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)
