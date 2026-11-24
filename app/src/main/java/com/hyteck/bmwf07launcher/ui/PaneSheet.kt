package com.hyteck.bmwf07launcher.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hyteck.bmwf07launcher.R
import com.hyteck.bmwf07launcher.util.dp

/**
 * The small black sheet that a pane handle opens: change app, fullscreen, clear, pane scale.
 * Everything is rectangular and quiet; it never covers more than a corner of the screen.
 */
object PaneSheet {

    fun show(
        activity: Activity,
        title: String,
        subtitle: String,
        dpi: Int?,
        onPickApp: () -> Unit,
        onFullscreen: () -> Unit,
        onClear: () -> Unit,
        onScale: (() -> Unit)?
    ) {
        val dialog = Dialog(activity, R.style.Theme_BmwLauncher_Dialog)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
        }

        content.addView(text(activity, title, 14f, Color.parseColor("#FFEDEDED"), bold = true))
        content.addView(
            text(
                activity,
                if (dpi == null) subtitle else subtitle + " · " + activity.getString(R.string.dpi_value, dpi),
                10f,
                Color.parseColor("#FF8A8A8A")
            ).apply {
                setPadding(0, activity.dp(3), 0, 0)
            }
        )

        content.addView(
            row(
                activity,
                button(activity, activity.getString(R.string.pane_change_app), { dialog.dismiss(); onPickApp() }),
                button(activity, activity.getString(R.string.pane_fullscreen), { dialog.dismiss(); onFullscreen() })
            ).apply { setPadding(0, activity.dp(10), 0, 0) }
        )

        val secondRow = row(
            activity,
            button(activity, activity.getString(R.string.pane_clear), { dialog.dismiss(); onClear() }),
            button(
                activity,
                if (dpi == null) activity.getString(R.string.close) else activity.getString(R.string.pane_scale),
                {
                    dialog.dismiss()
                    if (dpi == null) Unit else onScale?.invoke()
                }
            )
        )
        secondRow.setPadding(0, activity.dp(6), 0, 0)
        content.addView(secondRow)

        dialog.setContentView(content)
        dialog.window?.setLayout(activity.dp(340), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.show()
    }

    /** Per-pane density dialog. */
    fun showScale(
        activity: Activity,
        title: String,
        initialDpi: Int,
        onApply: (Int) -> Unit
    ) {
        showDpi(activity, title, initialDpi, onApply)
    }

    private fun showDpi(activity: Activity, title: String, initialDpi: Int, onApply: (Int) -> Unit) {
        val dialog = Dialog(activity, R.style.Theme_BmwLauncher_Dialog)
        var value = initialDpi.coerceIn(100, 480)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
        }
        content.addView(text(activity, title, 13f, Color.parseColor("#FFEDEDED"), bold = true))
        val valueView = text(activity, activity.getString(R.string.dpi_value, value), 22f, Color.parseColor("#FF43B0E5"), bold = true)
        valueView.gravity = Gravity.CENTER
        valueView.setPadding(0, activity.dp(12), 0, activity.dp(12))
        content.addView(valueView)

        val minus = button(activity, "- 10", {
            value = (value - 10).coerceAtLeast(100)
            valueView.text = activity.getString(R.string.dpi_value, value)
        })
        val plus = button(activity, "+ 10", {
            value = (value + 10).coerceAtMost(480)
            valueView.text = activity.getString(R.string.dpi_value, value)
        })
        content.addView(
            row(activity, minus, plus).apply { setPadding(0, 0, 0, activity.dp(8)) }
        )

        val apply = button(activity, activity.getString(R.string.apply), {
            onApply(value)
            dialog.dismiss()
        })
        val close = button(activity, activity.getString(R.string.close), { dialog.dismiss() })
        content.addView(row(activity, apply, close))

        dialog.setContentView(content)
        dialog.window?.setLayout(activity.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.show()
    }

    internal fun row(activity: Activity, vararg views: View): LinearLayout {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        views.forEachIndexed { index, view ->
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.marginStart = activity.dp(6)
            layout.addView(view, params)
        }
        return layout
    }

    internal fun button(activity: Activity, label: String, onClick: () -> Unit): TextView =
        text(activity, label, 13f, Color.parseColor("#FFEDEDED")).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_ratio_btn)
            isClickable = true
            isFocusable = true
            setPadding(activity.dp(6), activity.dp(12), activity.dp(6), activity.dp(12))
            setOnClickListener { onClick() }
        }

    internal fun text(
        activity: Activity,
        label: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(activity).apply {
        text = label
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
}
