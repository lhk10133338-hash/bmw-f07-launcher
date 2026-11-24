package com.hyteck.bmwf07launcher

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hyteck.bmwf07launcher.data.AppInfo
import com.hyteck.bmwf07launcher.data.AppRepository
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.databinding.ActivityAppDrawerBinding
import com.hyteck.bmwf07launcher.databinding.ItemAppBinding
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.toast

class AppDrawerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDrawerBinding
    private lateinit var settings: Settings
    private lateinit var repo: AppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        settings = Settings(this)
        repo = AppRepository(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.appGrid.layoutManager = GridLayoutManager(this, 8)
        refresh()
    }

    private fun refresh() {
        val hidden = settings.hiddenPackages
        val visible = repo.apps.filter { !hidden.contains(it.packageName) }
        binding.countText.text = "${visible.size}개"
        binding.appGrid.adapter = AppAdapter(visible) { info -> showActions(info) }
    }

    private fun showActions(info: AppInfo) {
        val actions = arrayOf(
            "왼쪽 창에서 열기",
            "오른쪽 창에서 열기",
            "전체 화면으로 열기",
            "이 앱 숨기기"
        )
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle(info.label)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openInLauncher(info.packageName, "left")
                    1 -> openInLauncher(info.packageName, "right")
                    2 -> openFullscreen(info.packageName)
                    else -> {
                        settings.addHidden(info.packageName)
                        refresh()
                        toast("앱을 숨겼습니다. 설정 > 숨긴 앱 관리에서 복원할 수 있습니다.")
                    }
                }
            }
            .setNegativeButton("닫기", null)
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
            toast("앱을 실행할 수 없습니다")
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

    private class AppAdapter(
        private val items: List<AppInfo>,
        private val onClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {

        class Holder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemBinding =
                ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val info = items[position]
            holder.binding.appIcon.setImageDrawable(info.icon)
            holder.binding.appLabel.text = info.label
            holder.binding.root.setOnClickListener { onClick(info) }
        }
    }
}
