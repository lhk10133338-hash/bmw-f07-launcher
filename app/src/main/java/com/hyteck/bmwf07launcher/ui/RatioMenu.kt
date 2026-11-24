package com.hyteck.bmwf07launcher.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hyteck.bmwf07launcher.R
import com.hyteck.bmwf07launcher.engine.Side
import com.hyteck.bmwf07launcher.util.dp

/**
 * Tap/long-press on the divider: eight rectangular buttons, nothing else.
 */
object RatioMenu {

    private val RATIOS = listOf(50, 60, 70, 80, 40, 30, 20)

    fun show(
        activity: Activity,
        current: Int,
        onPick: (Int) -> Unit,
        onSwap: () -> Unit
    ) {
        val dialog = Dialog(activity, R.style.Theme_BmwLauncher_Dialog)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(12))
        }

        content.addView(
            paneText(activity, activity.getString(R.string.ratio_title), 11f, Color.parseColor("#FF8A8A8A"))
        )

        val firstRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val secondRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        RATIOS.forEachIndexed { index, ratio ->
            val button = ratioButton(activity, ratio, ratio == current) {
                dialog.dismiss()
                onPick(ratio)
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            params.marginStart = if (index % 4 == 0) 0 else activity.dp(4)
            if (index < 4) firstRow.addView(button, params) else secondRow.addView(button, params)
        }

        val swap = ratioButtonText(activity, activity.getString(R.string.ratio_swap), false) {
            dialog.dismiss()
            onSwap()
        }
        val swapParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        swapParams.marginStart = activity.dp(4)
        secondRow.addView(swap, swapParams)

        firstRow.setPadding(0, activity.dp(8), 0, 0)
        secondRow.setPadding(0, activity.dp(4), 0, 0)
        content.addView(firstRow)
        content.addView(secondRow)

        dialog.setContentView(content)
        dialog.window?.setLayout(activity.dp(420), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.show()
    }

    private fun ratioButton(activity: Activity, ratio: Int, selected: Boolean, onClick: () -> Unit): TextView =
        ratioButtonText(activity, "${ratio} : ${100 - ratio}", selected, onClick)

    private fun ratioButtonText(activity: Activity, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        paneText(activity, label, 13f, Color.parseColor("#FFEDEDED")).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(if (selected) R.drawable.bg_ratio_btn_on else R.drawable.bg_ratio_btn)
            isClickable = true
            isFocusable = true
            setPadding(activity.dp(4), activity.dp(10), activity.dp(4), activity.dp(10))
            setOnClickListener { onClick() }
        }

    fun fullscreenSide(ratio: Int): Side =
        if (ratio >= 50) Side.LEFT else Side.RIGHT
}
