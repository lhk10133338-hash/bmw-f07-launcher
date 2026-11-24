package com.hyteck.bmwf07launcher.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.hyteck.bmwf07launcher.R
import com.hyteck.bmwf07launcher.data.AppInfo

/** Searchable installed-app picker used by the presets and the drawer. */
object AppPickerDialog {

    fun show(
        context: Context,
        title: String,
        apps: List<AppInfo>,
        onPick: (String?) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null)
        val input = view.findViewById<EditText>(R.id.filterInput)
        val list = view.findViewById<ListView>(R.id.appList)
        val adapter = AppRowAdapter(context, apps)
        list.adapter = adapter

        val dialog = AlertDialog.Builder(context, R.style.Theme_BmwLauncher_Dialog)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("취소", null)
            .setNeutralButton("선택 안 함") { _, _ -> onPick(null) }
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val info = adapter.getItem(position) as? AppInfo
            if (info != null) {
                onPick(info.packageName)
                dialog.dismiss()
            }
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
        dialog.show()
    }
}

private class AppRowAdapter(
    private val context: Context,
    private val all: List<AppInfo>
) : BaseAdapter() {

    private val shown = ArrayList<AppInfo>(all)

    override fun getCount(): Int = shown.size

    override fun getItem(position: Int): Any? = shown[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_app_row, parent, false)
        val icon = view.findViewById<ImageView>(R.id.rowIcon)
        val label = view.findViewById<TextView>(R.id.rowLabel)
        val pkg = view.findViewById<TextView>(R.id.rowPackage)
        val info = shown[position]
        icon.setImageDrawable(info.icon)
        label.text = info.label
        pkg.text = info.packageName
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
