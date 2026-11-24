package com.hyteck.bmwf07launcher.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hyteck.bmwf07launcher.R
import com.hyteck.bmwf07launcher.util.dp

/**
 * The three building blocks the launcher's dark rectangular menus are made of.
 * Shared by [PanePanel] (inside a pane) and [RatioMenu] (above the divider) so both look
 * and feel the same.
 */
internal fun paneText(
    activity: Activity,
    label: String,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false
): TextView = TextView(activity).apply {
    text = label
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

internal fun paneButton(
    activity: Activity,
    label: String,
    heightDp: Int = 46,
    onClick: () -> Unit
): TextView = paneText(activity, label, 13f, 0xFFEDEDED.toInt()).apply {
    gravity = Gravity.CENTER
    setBackgroundResource(R.drawable.bg_ratio_btn)
    isClickable = true
    isFocusable = true
    setPadding(activity.dp(4), 0, activity.dp(4), 0)
    maxLines = 2
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        activity.dp(heightDp)
    )
}

internal fun paneRow(activity: Activity, vararg views: View): LinearLayout {
    val layout = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    views.forEachIndexed { index, view ->
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (index > 0) params.marginStart = activity.dp(5)
        layout.addView(view, params)
    }
    return layout
}

/** Any paragraph of quiet grey helper text. */
internal fun paneHint(activity: Activity, label: String, sizeSp: Float = 9f): TextView =
    paneText(activity, label, sizeSp, 0xFF8A8A8A.toInt()).apply {
        setPadding(0, activity.dp(6), 0, 0)
    }
