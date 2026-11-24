package com.hyteck.bmwf07launcher

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hyteck.bmwf07launcher.data.AppInfo
import com.hyteck.bmwf07launcher.data.AppRepository
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.util.ScaledActivity
import com.hyteck.bmwf07launcher.databinding.ActivityAppDrawerBinding
import com.hyteck.bmwf07launcher.databinding.ItemAppBinding
import com.hyteck.bmwf07launcher.databinding.ItemSectionBinding
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.toast

/**
 * Full app drawer: favourites pinned at the top, then every other installed app.
 * Long-press an app for the pane choices and for hiding/pinning it.
 */
class AppDrawerActivity : ScaledActivity() {

    private lateinit var binding: ActivityAppDrawerBinding
    private lateinit var settings: Settings
    private lateinit var repo: AppRepository

    /** The bars come back after a dialog or a focus change: hide them again at once (v3.2). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        settings = Settings(this)
        repo = AppRepository(this)

        binding.btnBack.setOnClickListener { finish() }
        val spanCount = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density) / 110)
            .toInt()
            .coerceIn(6, 16)
        val manager = GridLayoutManager(this, spanCount)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val adapter = binding.appGrid.adapter ?: return 1
                return if (adapter.getItemViewType(position) == TYPE_SECTION) spanCount else 1
            }
        }
        binding.appGrid.layoutManager = manager
        refresh()
    }

    /** Leaving the drawer must not animate over the launcher's panes. */
    override fun finish() {
        super.finish()
        noTransition()
    }

    @Suppress("DEPRECATION")
    private fun noTransition() {
        overridePendingTransition(0, 0)
    }

    private fun refresh() {
        val hidden = settings.hiddenPackages
        val favorites = settings.favorites
        val visible = repo.apps.filter { !hidden.contains(it.packageName) }
        val pinned = visible.filter { favorites.contains(it.packageName) }
            .sortedBy { it.label.lowercase() }
        val others = visible.filterNot { favorites.contains(it.packageName) }

        val rows = ArrayList<DrawerRow>()
        if (pinned.isNotEmpty()) {
            rows.add(DrawerRow.Section(getString(R.string.favorite_section)))
            pinned.forEach { rows.add(DrawerRow.App(it, true)) }
        }
        rows.add(DrawerRow.Section(getString(R.string.all_apps_section)))
        others.forEach { rows.add(DrawerRow.App(it, false)) }

        binding.countText.text = "${visible.size}개"
        binding.appGrid.adapter = DrawerAdapter(
            rows,
            onClick = { info -> openFullscreen(info.packageName) },
            onLongClick = { info -> showActions(info) }
        )
    }

    private fun showActions(info: AppInfo) {
        val favorite = settings.favorites.contains(info.packageName)
        val actions = arrayOf(
            getString(R.string.open_left),
            getString(R.string.open_right),
            getString(R.string.open_fullscreen),
            getString(if (favorite) R.string.favorite_remove else R.string.favorite_add),
            getString(R.string.hidden_app)
        )
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle(info.label)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openInLauncher(info.packageName, "left")
                    1 -> openInLauncher(info.packageName, "right")
                    2 -> openFullscreen(info.packageName)
                    3 -> {
                        settings.toggleFavorite(info.packageName)
                        refresh()
                    }

                    else -> {
                        settings.addHidden(info.packageName)
                        refresh()
                        toast(getString(R.string.hidden_done))
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun openInLauncher(pkg: String, side: String) {
        val intent = Intent(this, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LauncherActivity.EXTRA_PACKAGE, pkg)
            .putExtra(LauncherActivity.EXTRA_SIDE, side)
        startActivity(intent)
        finish()
    }

    private fun openFullscreen(pkg: String) {
        val launch = repo.launchIntent(pkg)
        if (launch == null) {
            toast(getString(R.string.app_missing))
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
            finish()
        } catch (e: Exception) {
            toast("앱 실행 실패: ${e.message}")
        }
    }

    private sealed class DrawerRow {
        class Section(val title: String) : DrawerRow()
        class App(val info: AppInfo, val favorite: Boolean) : DrawerRow()
    }

    private class DrawerAdapter(
        private val rows: List<DrawerRow>,
        private val onClick: (AppInfo) -> Unit,
        private val onLongClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is DrawerRow.Section) TYPE_SECTION else TYPE_APP

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_SECTION) {
                SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
            } else {
                AppHolder(ItemAppBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is DrawerRow.Section -> (holder as SectionHolder).binding.sectionTitle.text = row.title
                is DrawerRow.App -> {
                    val appHolder = holder as AppHolder
                    appHolder.binding.appIcon.setImageDrawable(row.info.icon)
                    appHolder.binding.appLabel.text = row.info.label
                    appHolder.binding.favoriteBadge.visibility =
                        if (row.favorite) android.view.View.VISIBLE else android.view.View.GONE
                    appHolder.binding.favoriteBadge.imageTintList =
                        ColorStateList.valueOf(FAVORITE_TINT)
                    appHolder.binding.root.setOnClickListener {
                        onClick(row.info)
                    }
                    appHolder.binding.root.setOnLongClickListener {
                        onLongClick(row.info)
                        true
                    }
                }
            }
        }

        private class SectionHolder(val binding: ItemSectionBinding) :
            RecyclerView.ViewHolder(binding.root)

        private class AppHolder(val binding: ItemAppBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_APP = 1
        private const val FAVORITE_TINT = 0xFF43B0E5.toInt()
    }
}
