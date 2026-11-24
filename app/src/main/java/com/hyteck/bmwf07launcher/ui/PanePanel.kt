package com.hyteck.bmwf07launcher.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hyteck.bmwf07launcher.R
import com.hyteck.bmwf07launcher.data.AppInfo
import com.hyteck.bmwf07launcher.util.dp

/**
 * A pane's own menu and app picker, drawn *inside* that pane instead of as a Dialog window.
 *
 * Why this exists (user report, 2026-11-24): a Dialog is a separate window of the launcher, so
 * in free-form mode another app's floating window (Naver Map, on the left) was drawn on top of
 * the picker and hid half of it. An inline overlay is a child of the pane itself, so the left
 * pane's menu can only ever appear over the left pane and the right pane's menu can only appear
 * over the right pane — the neighbouring window never covers it.
 *
 * Everything is flat black, 0dp corners, and sized from the pane's own width: a 2:8 pane gets
 * one column of buttons, smaller text and no package names, a wide pane gets two columns.
 */
class PanePanel(
    private val activity: Activity,
    private val host: FrameLayout
) {

    val isOpen: Boolean get() = host.visibility == View.VISIBLE

    fun close() {
        host.removeAllViews()
        host.visibility = View.GONE
    }

    /** App change / fullscreen / clear / scale for one pane, straight inside that pane. */
    fun showMenu(
        title: String,
        subtitle: String,
        dpi: Int?,
        isFullscreen: Boolean,
        onPickApp: () -> Unit,
        onFullscreen: () -> Unit,
        onClear: () -> Unit,
        onScale: (() -> Unit)?
    ) {
        val root = prepare()
        val narrow = isNarrow()
        root.addView(header(title, subtitle, narrow))
        root.addView(spacer(activity.dp(8)))

        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries.add(activity.getString(R.string.pane_change_app) to onPickApp)
        entries.add(
            activity.getString(
                if (isFullscreen) R.string.pane_restore else R.string.pane_fullscreen
            ) to onFullscreen
        )
        entries.add(activity.getString(R.string.pane_clear) to onClear)
        if (onScale != null) {
            entries.add(activity.getString(R.string.pane_scale) to onScale)
        }

        if (narrow || entries.size < 4) {
            entries.forEachIndexed { index, entry ->
                root.addView(
                    paneButton(activity, entry.first) { entry.second.invoke() },
                    buttonParams(index)
                )
            }
        } else {
            entries.chunked(2).forEachIndexed { rowIndex, pair ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { index, entry ->
                    val button = paneButton(activity, entry.first) { entry.second.invoke() }
                    val params = LinearLayout.LayoutParams(0, activity.dp(48), 1f)
                    if (index > 0) params.marginStart = activity.dp(6)
                    row.addView(button, params)
                }
                if (pair.size == 1) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                }
                root.addView(row, buttonParams(rowIndex))
            }
        }

        if (dpi == null) {
            root.addView(
                paneHint(
                    activity,
                    activity.getString(R.string.pane_scale_system),
                    10f
                )
            )
        }
    }

    /**
     * The installed-app list. [allowClear] adds the "선택 안 함" button that empties the pane,
     * [current] marks the app that is already in this pane.
     */
    fun showPicker(
        title: String,
        subtitle: String,
        apps: List<AppInfo>,
        current: String?,
        allowClear: Boolean,
        onPick: (String?) -> Unit
    ) {
        val root = prepare()
        val narrow = isNarrow()
        root.addView(header(title, subtitle, narrow))

        val input = EditText(activity).apply {
            hint = activity.getString(R.string.picker_search_hint)
            textSize = if (narrow) 11f else 12f
            setTextColor(0xFFEDEDED.toInt())
            setHintTextColor(0xFF6E6E6E.toInt())
            background = ContextCompat.getDrawable(activity, R.drawable.bg_ratio_btn)
            setPadding(activity.dp(8), 0, activity.dp(8), 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        root.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(36)).apply {
                topMargin = activity.dp(6)
                bottomMargin = activity.dp(6)
            }
        )

        val adapter = AppPickAdapter(activity, apps, narrow, current)
        val list = ListView(activity).apply {
            divider = ColorDrawable(0xFF1B1B1B.toInt())
            dividerHeight = activity.dp(1)
        }
        list.adapter = adapter
        val empty = paneText(
            activity,
            activity.getString(R.string.picker_no_result),
            10f,
            0xFF6E6E6E.toInt()
        ).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val listParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(list, listParams)
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.emptyView = empty

        list.setOnItemClickListener { _, _, position, _ ->
            val info = adapter.getItem(position) as? AppInfo ?: return@setOnItemClickListener
            close()
            onPick(info.packageName)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // no-op
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // no-op
            }

            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })

        if (allowClear) {
            root.addView(
                paneButton(activity, activity.getString(R.string.pane_pick_none)) {
                    close()
                    onPick(null)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    activity.dp(40)
                ).apply { topMargin = activity.dp(6) }
            )
        }
    }

    /**
     * Per-pane density inside the pane. [requiresVirtual] is set while the free-form engine is
     * running: there the density cannot be applied, so the apply button switches the launcher to
     * the virtual-display engine first.
     */
    fun showScale(
        title: String,
        subtitle: String,
        initialDpi: Int,
        requiresVirtual: Boolean,
        onApply: (Int) -> Unit
    ) {
        val root = prepare()
        val narrow = isNarrow()
        root.addView(header(title, subtitle, narrow))

        val min = com.hyteck.bmwf07launcher.data.Settings.MIN_DPI
        val max = com.hyteck.bmwf07launcher.data.Settings.MAX_DPI
        var value = initialDpi.coerceIn(min, max)

        val valueView = paneText(activity, dpiLabel(value), 26f, 0xFF43B0E5.toInt(), bold = true)
        valueView.gravity = Gravity.CENTER
        valueView.setPadding(0, activity.dp(10), 0, activity.dp(4))
        root.addView(valueView)

        val stepRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun addStep(label: String, delta: Int) {
            val button = paneButton(activity, label, 42) {
                value = (value + delta).coerceIn(min, max)
                valueView.text = dpiLabel(value)
            }
            val params = LinearLayout.LayoutParams(0, activity.dp(42), 1f)
            if (stepRow.childCount > 0) params.marginStart = activity.dp(5)
            stepRow.addView(button, params)
        }
        addStep("- 40", -40)
        addStep("- 10", -10)
        addStep("+ 10", 10)
        addStep("+ 40", 40)
        root.addView(stepRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = activity.dp(4) })

        if (requiresVirtual) {
            root.addView(paneHint(activity, activity.getString(R.string.pane_scale_system), 10f))
        }

        val apply = paneButton(
            activity,
            activity.getString(
                if (requiresVirtual) R.string.scale_apply_virtual else R.string.apply
            ),
            46
        ) {
            close()
            onApply(value)
        }
        root.addView(apply, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            activity.dp(46)
        ).apply { topMargin = activity.dp(8) })
        root.addView(
            paneButton(activity, activity.getString(R.string.close), 42) { close() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(42)
            ).apply { topMargin = activity.dp(5) }
        )
    }

    // ---------------------------------------------------------------- pieces

    private fun prepare(): LinearLayout {
        host.removeAllViews()
        host.visibility = View.VISIBLE
        val narrow = isNarrow()
        val pad = activity.dp(if (narrow) 8 else 14)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, activity.dp(6), pad, activity.dp(10))
            isClickable = true
            isFocusable = true
        }
        host.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    /** True for a 2:8-style pane, where two button columns and package names do not fit. */
    private fun isNarrow(): Boolean {
        val width = host.width
        return width > 0 && width < activity.dp(300)
    }

    private fun header(title: String, subtitle: String, narrow: Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            paneText(activity, title, if (narrow) 12f else 13f, 0xFFEDEDED.toInt(), bold = true)
                .apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
        )
        if (subtitle.isNotEmpty()) {
            texts.addView(
                paneText(activity, subtitle, 9f, 0xFF8A8A8A.toInt()).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, activity.dp(1), 0, 0)
                }
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeButton = paneText(activity, "×", 20f, 0xFF9E9E9E.toInt()).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(R.string.pane_menu_close)
            setOnClickListener { close() }
        }
        row.addView(closeButton, LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)))
        return row
    }

    private fun buttonParams(index: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            activity.dp(46)
        ).apply { topMargin = activity.dp(if (index == 0) 0 else 5) }

    private fun spacer(height: Int): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun dpiLabel(dpi: Int): String =
        activity.getString(R.string.dpi_value_percent, dpi, (dpi * 100) / 160)
}

/** Row adapter for the in-pane picker. Korean app names first, package name only as extra text. */
private class AppPickAdapter(
    private val activity: Activity,
    private val all: List<AppInfo>,
    private val narrow: Boolean,
    private val current: String?
) : BaseAdapter() {

    private val shown = ArrayList<AppInfo>(all)

    override fun getCount(): Int = shown.size

    override fun getItem(position: Int): Any? = shown[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: activity.layoutInflater.inflate(R.layout.item_app_pick, parent, false)
        val info = shown[position]
        view.findViewById<ImageView>(R.id.pickIcon).setImageDrawable(info.icon)
        view.findViewById<TextView>(R.id.pickLabel).apply {
            text = info.label.ifBlank { info.packageName }
            textSize = if (narrow) 11f else 12f
        }
        view.findViewById<TextView>(R.id.pickPackage).apply {
            text = info.packageName
            visibility = if (narrow) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.pickCurrent).visibility =
            if (info.packageName == current) View.VISIBLE else View.GONE
        return view
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        shown.clear()
        if (q.isEmpty()) {
            shown.addAll(all)
        } else {
            shown.addAll(
                all.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            )
        }
        notifyDataSetChanged()
    }
}
