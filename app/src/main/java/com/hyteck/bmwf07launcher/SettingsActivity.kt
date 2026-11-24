package com.hyteck.bmwf07launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.hyteck.bmwf07launcher.data.AppRepository
import com.hyteck.bmwf07launcher.data.Preset
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.util.ScaledActivity
import com.hyteck.bmwf07launcher.databinding.ActivitySettingsBinding
import com.hyteck.bmwf07launcher.databinding.ItemPresetEditBinding
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService
import com.hyteck.bmwf07launcher.shizuku.ShizukuBridge
import com.hyteck.bmwf07launcher.ui.AppPickerDialog
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.dp
import com.hyteck.bmwf07launcher.util.toast
import rikka.shizuku.Shizuku

class SettingsActivity : ScaledActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private lateinit var repo: AppRepository

    /** v3.4: Shizuku's own permission dialog reports back here. */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_SHIZUKU) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            toast(
                getString(
                    if (granted) R.string.shizuku_granted_toast else R.string.shizuku_denied_toast
                )
            )
            ShizukuBridge.refresh()
            updateShizukuLabel()
        }

    /** The bridge reports every change of state (binder arrived, service connected…). */
    private val shizukuStateListener: (ShizukuBridge.State) -> Unit = {
        runOnUiThread { updateShizukuLabel() }
    }

    private val presetRows = ArrayList<ItemPresetEditBinding>()

    /** UI scale this screen was built with, so the screen knows when it has to be rebuilt. */
    private var appliedScale = 0

    private val pickIntroMedia =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (ignored: SecurityException) {
                    // Some document providers do not offer persistable access.
                }
                // v3.2: a picture is a valid intro too, so the MIME type is remembered next to the
                // URI and the launcher knows whether to decode an image or play a video.
                settings.introVideoUri = uri.toString()
                settings.introMediaType = resolveMediaType(uri)
                updateVideoName()
                toast(
                    if (isImageType(settings.introMediaType)) "인트로 사진을 지정했습니다"
                    else "인트로 동영상을 지정했습니다"
                )
            }
        }

    /** MIME type of a picked document, from the provider or, failing that, from its name. */
    private fun resolveMediaType(uri: Uri): String {
        val fromProvider = try {
            contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        if (fromProvider != null) return fromProvider
        val name = (uri.lastPathSegment ?: uri.toString()).lowercase()
        val images = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        return if (images.any { name.endsWith(".$it") }) "image/*" else "video/*"
    }

    private fun isImageType(type: String?): Boolean = type?.startsWith("image") == true

    /** The bars come back after a dialog or a focus change: hide them again at once (v3.2). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        settings = Settings(this)
        repo = AppRepository(this)
        appliedScale = settings.uiScale

        ShizukuBridge.init(this)
        ShizukuBridge.addListener(shizukuStateListener)
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            // Shizuku is not installed (or too old): the status row still explains it.
        }

        binding.btnBack.setOnClickListener { finish() }

        setupStart()
        setupEngine()
        setupShizuku()
        setupRatio()
        setupDpi()
        setupLauncherScale()
        setupMusic()
        setupPresets()
        setupDock()
        setupPermissions()

        updateVideoName()
    }

    /** Leaving the drawer must not animate over the launcher's panes. */
    override fun finish() {
        super.finish()
        noTransition()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        ShizukuBridge.refresh()
        updateShizukuLabel()
    }

    override fun onDestroy() {
        ShizukuBridge.removeListener(shizukuStateListener)
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (e: Exception) {
            // Nothing to remove.
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun noTransition() {
        overridePendingTransition(0, 0)
    }

    // -------------------------------------------------------- start & restore

    private fun setupStart() {
        binding.switchRememberSession.isChecked = settings.rememberSession
        binding.switchBoot.isChecked = settings.bootAutostart
        binding.switchIntro.isChecked = settings.introEnabled
        binding.switchSkipReturn.isChecked = settings.introSkipOnReturn

        binding.switchRememberSession.setOnCheckedChangeListener { _, checked ->
            settings.rememberSession = checked
        }
        binding.switchBoot.setOnCheckedChangeListener { _, checked ->
            settings.bootAutostart = checked
        }
        binding.switchIntro.setOnCheckedChangeListener { _, checked ->
            settings.introEnabled = checked
        }
        binding.switchSkipReturn.setOnCheckedChangeListener { _, checked ->
            settings.introSkipOnReturn = checked
        }
        binding.btnPickVideo.setOnClickListener {
            try {
                // Both kinds of media: the launcher shows a picture for ~2.5 s or plays a video.
                pickIntroMedia.launch(arrayOf("video/*", "image/*"))
            } catch (e: Exception) {
                toast("사진/동영상 선택기를 열 수 없습니다")
            }
        }
        binding.btnResetVideo.setOnClickListener {
            settings.introVideoUri = null
            settings.introMediaType = null
            updateVideoName()
            toast("기본 M 인트로를 사용합니다")
        }
    }

    private fun updateVideoName() {
        val uri = settings.introVideoUri
        if (uri == null) {
            binding.tvVideoName.text = getString(R.string.intro_video_none)
        } else {
            val name = try {
                Uri.parse(uri).lastPathSegment ?: uri
            } catch (e: Exception) {
                uri
            }
            binding.tvVideoName.text = getString(R.string.intro_video_selected, name)
        }
    }

    // ---------------------------------------------------------------- engine

    private fun setupEngine() {
        binding.rbSystem.isChecked = settings.engine == Settings.ENGINE_SYSTEM
        binding.rbVirtual.isChecked = settings.engine == Settings.ENGINE_VIRTUAL
        binding.engineGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.engine =
                if (checkedId == R.id.rbVirtual) Settings.ENGINE_VIRTUAL else Settings.ENGINE_SYSTEM
            updateEngineDescription()
        }
        binding.btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                toast("접근성 설정을 열 수 없습니다")
            }
        }
        binding.btnOverlay.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                toast("오버레이 설정을 열 수 없습니다")
            }
        }
        binding.btnHelp.setOnClickListener { showHelp() }
        updateEngineDescription()
    }

    private fun showHelp() {
        val message = """
            1. 기본 런처: '기본 런처로 설정'에서 BMW F07 Launcher를 고릅니다.
               (부팅 시 자동 실행이 켜져 있으면 부팅 완료 후에도 스스로 뜹니다.)

            2. 분할 방식
               · 가상 디스플레이(권장): 런처 안에 두 앱을 직접 그립니다. 제목줄이 없고 비율·배율이 즉시 반영됩니다.
                 창 안의 터치는 Shizuku가 있으면 즉시, 없으면 접근성 서비스가 대신 전달합니다.
               · 자유창 안전 모드: 자유 형식 창이 켜져 있어야 2:8 같은 비율이 적용됩니다.
                 설정 > 개발자 옵션 > '자유 형식 창'을 켜고, 접근성 서비스를 함께 켜 주세요.

            3. 화면 사용법
               · 가운데 얇은 선을 좌우로 끌면 5:5 ~ 2:8로 조절됩니다. 선을 톡 누르면 비율 메뉴.
               · 각 창 아래 반투명 회색 작은 바를 한 번 짧게 누르면 앱 변경·전체화면·앱 배기·화면 배율 메뉴가 열립니다.
               · 바를 위로 쓰다듬거나 길게 눌러도 같은 메뉴가 열립니다.
               · 독의 + 는 지금 두 앱과 비율을 프리셋으로 저장합니다.
               · 독의 ‹ 를 누르면 독이 숨고, 화면 왼쪽 가장자리의 얇은 선을 누르면 다시 나옵니다.

            4. 상태 유지
               · '마지막 분할 상태 기억'이 켜져 있으면 재부팅 후에도 마지막 앱·비율·배율로 시작합니다.
               · 모든 설정은 즉시 저장되어 시동이 꺼져도 사라지지 않습니다.
        """.trimIndent()
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle("설정 안내")
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun updateEngineDescription() {
        val base = if (settings.engine == Settings.ENGINE_VIRTUAL) {
            getString(R.string.engine_virtual_desc)
        } else {
            getString(R.string.engine_system_desc)
        }
        val accessibility = if (LauncherAccessibilityService.isEnabled(this)) {
            "접근성 서비스: 켜짐"
        } else {
            "접근성 서비스: 꺼짐"
        }
        binding.engineDesc.text = "$base\n$accessibility"
        updateShizukuLabel()
    }

    // --------------------------------------------------------------- shizuku

    /**
     * v3.4: the virtual-display engine needs a way to carry touch into its displays. This row
     * states which of the two helpers is available and asks Shizuku for access in one tap.
     */
    private fun setupShizuku() {
        binding.btnShizuku.setOnClickListener {
            when (ShizukuBridge.state) {
                ShizukuBridge.State.READY -> toast(getString(R.string.shizuku_ready))
                ShizukuBridge.State.CONNECTING -> toast(getString(R.string.shizuku_connecting))
                ShizukuBridge.State.PERMISSION -> {
                    if (!ShizukuBridge.requestPermission(REQUEST_SHIZUKU)) {
                        showShizukuHelp()
                    }
                }

                ShizukuBridge.State.NOT_RUNNING -> showShizukuHelp()
            }
        }
        updateShizukuLabel()
    }

    private fun updateShizukuLabel() {
        if (!::binding.isInitialized) return
        val state = ShizukuBridge.state
        binding.tvShizukuStatus.text = when (state) {
            ShizukuBridge.State.READY -> getString(R.string.shizuku_ready)
            ShizukuBridge.State.CONNECTING -> getString(R.string.shizuku_connecting)
            ShizukuBridge.State.PERMISSION -> getString(R.string.shizuku_permission)
            ShizukuBridge.State.NOT_RUNNING -> getString(R.string.shizuku_not_running)
        }
        binding.btnShizuku.text = if (state == ShizukuBridge.State.PERMISSION) {
            getString(R.string.shizuku_grant)
        } else {
            getString(R.string.perm_shizuku)
        }
    }

    private fun showShizukuHelp() {
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle(R.string.shizuku_help)
            .setMessage(R.string.shizuku_help_body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ----------------------------------------------------------------- ratio

    private fun setupRatio() {
        binding.ratioSeek.max = 60
        binding.ratioSeek.progress = settings.ratio - 20
        binding.ratioValue.text = ratioLabel(settings.ratio)
        binding.ratioSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = progress + 20
                binding.ratioValue.text = ratioLabel(ratio)
                if (fromUser) settings.ratio = ratio
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }
        })
    }

    private fun ratioLabel(ratio: Int): String = "$ratio : ${100 - ratio}"

    // ------------------------------------------------------------------- dpi

    private fun setupDpi() {
        val maxProgress = (Settings.MAX_DPI - Settings.MIN_DPI) / 10
        binding.leftDpiSeek.max = maxProgress
        binding.rightDpiSeek.max = maxProgress
        binding.leftDpiSeek.progress = (settings.leftDpi - Settings.MIN_DPI) / 10
        binding.rightDpiSeek.progress = (settings.rightDpi - Settings.MIN_DPI) / 10
        binding.leftDpiValue.text = dpiLabel(settings.leftDpi)
        binding.rightDpiValue.text = dpiLabel(settings.rightDpi)

        binding.leftDpiSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dpi = Settings.MIN_DPI + progress * 10
                binding.leftDpiValue.text = dpiLabel(dpi)
                if (fromUser) settings.leftDpi = dpi
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }
        })

        binding.rightDpiSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                val dpi = Settings.MIN_DPI + progress * 10
                binding.rightDpiValue.text = dpiLabel(dpi)
                if (fromUser) settings.rightDpi = dpi
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }
        })
    }

    private fun dpiLabel(dpi: Int): String =
        getString(R.string.dpi_value_percent, dpi, (dpi * 100) / 160)

    // --------------------------------------------------------- launcher scale

    private fun setupLauncherScale() {
        val step = Settings.UI_SCALE_STEP
        binding.launcherScaleSeek.max = (Settings.MAX_UI_SCALE - Settings.MIN_UI_SCALE) / step
        binding.launcherScaleSeek.progress = (settings.uiScale - Settings.MIN_UI_SCALE) / step
        binding.launcherScaleValue.text = getString(R.string.launcher_scale_value, settings.uiScale)
        binding.launcherScaleHint.text = getString(R.string.launcher_scale_hint)
        binding.launcherScaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = Settings.MIN_UI_SCALE + progress * step
                binding.launcherScaleValue.text = getString(R.string.launcher_scale_value, percent)
                if (fromUser) settings.uiScale = percent
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Rebuild this screen so the new size is visible immediately.
                if (settings.uiScale != appliedScale) {
                    appliedScale = settings.uiScale
                    recreate()
                }
            }
        })
    }

    // ----------------------------------------------------------------- music

    private fun setupMusic() {
        updateMusicLabel()
        binding.switchAutoPlay.isChecked = settings.autoPlayMusic
        binding.switchAutoPlay.setOnCheckedChangeListener { _, checked ->
            settings.autoPlayMusic = checked
        }
        binding.btnPickMusic.setOnClickListener {
            AppPickerDialog.show(this, getString(R.string.music_pick), repo.apps) { pkg ->
                settings.musicPackage = pkg
                updateMusicLabel()
            }
        }
        binding.btnClearMusic.setOnClickListener {
            settings.musicPackage = null
            updateMusicLabel()
        }
    }

    private fun updateMusicLabel() {
        val pkg = settings.musicPackage
        binding.tvMusicApp.text = if (pkg == null) {
            getString(R.string.music_none)
        } else {
            getString(R.string.music_selected, repo.labelOf(pkg))
        }
    }

    // --------------------------------------------------------------- presets

    private fun setupPresets() {
        binding.btnImportDefaults.setOnClickListener {
            var added = 0
            for ((leftKey, rightKey, name) in Settings.BMW_PRESETS) {
                val left = repo.resolveKey(leftKey)?.packageName
                val right = repo.resolveKey(rightKey)?.packageName
                if (left == null && right == null) continue
                if (settings.addPreset(Preset(name, left, right, 50))) added++
            }
            refreshPresetRows()
            toast(
                if (added == 0) getString(R.string.presets_exist)
                else getString(R.string.imported, added)
            )
        }
        refreshPresetRows()
    }

    private fun refreshPresetRows() {
        binding.presetList.removeAllViews()
        presetRows.clear()
        val presets = settings.presets()
        if (presets.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.empty_presets)
                setTextColor(0xFF6E6E6E.toInt())
                textSize = 11f
                setPadding(0, dp(6), 0, dp(6))
            }
            binding.presetList.addView(empty)
            return
        }
        presets.forEachIndexed { index, preset ->
            val row = ItemPresetEditBinding.inflate(layoutInflater, binding.presetList, false)
            row.presetName.text = preset.name.ifBlank { getString(R.string.preset_name_default, index + 1) }
            row.leftButton.text = "◀ " + labelOf(preset.left)
            row.rightButton.text = labelOf(preset.right) + " ▶"
            row.ratioText.text = "${preset.ratio}:${100 - preset.ratio}"
            row.leftButton.setOnClickListener { pickPresetApp(index, true) }
            row.rightButton.setOnClickListener { pickPresetApp(index, false) }
            row.root.setOnLongClickListener {
                showPresetMenu(index)
                true
            }
            binding.presetList.addView(row.root)
            presetRows.add(row)
        }
    }

    private fun labelOf(pkg: String?): String =
        if (pkg == null) getString(R.string.app_add) else repo.labelOf(pkg)

    private fun pickPresetApp(index: Int, left: Boolean) {
        AppPickerDialog.show(
            this,
            getString(if (left) R.string.preset_menu_left else R.string.preset_menu_right),
            repo.apps
        ) { pkg ->
            settings.updatePreset(index) {
                if (left) it.copy(left = pkg) else it.copy(right = pkg)
            }
            refreshPresetRows()
        }
    }

    private fun showPresetMenu(index: Int) {
        val preset = settings.presets().getOrNull(index) ?: return
        val items = arrayOf(
            getString(R.string.preset_menu_rename),
            getString(R.string.preset_menu_left),
            getString(R.string.preset_menu_right),
            getString(R.string.preset_menu_up),
            getString(R.string.preset_menu_down),
            getString(R.string.preset_menu_delete)
        )
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle(preset.name.ifBlank { getString(R.string.preset_name_default, index + 1) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renamePreset(index)
                    1 -> pickPresetApp(index, true)
                    2 -> pickPresetApp(index, false)
                    3 -> {
                        settings.movePreset(index, index - 1)
                        refreshPresetRows()
                    }

                    4 -> {
                        settings.movePreset(index, index + 1)
                        refreshPresetRows()
                    }

                    else -> {
                        settings.removePreset(index)
                        refreshPresetRows()
                        toast(getString(R.string.preset_deleted))
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun renamePreset(index: Int) {
        val preset = settings.presets().getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(preset.name)
            setSelection(text.length)
            setTextColor(0xFFEDEDED.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(12), dp(4), dp(12), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle(R.string.preset_rename_title)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val name = input.text.toString().trim()
                settings.updatePreset(index) { it.copy(name = name) }
                refreshPresetRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ dock

    private fun setupDock() {
        binding.rbDockLeft.isChecked = settings.dockAtLeft
        binding.rbDockRight.isChecked = !settings.dockAtLeft
        binding.dockGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.dockAtLeft = checkedId != R.id.rbDockRight
        }
        binding.switchAutoHide.isChecked = settings.dockAutoHide
        binding.switchAutoHide.setOnCheckedChangeListener { _, checked ->
            settings.dockAutoHide = checked
        }
    }

    // ------------------------------------------------------------ permissions

    private fun setupPermissions() {
        binding.btnHome.setOnClickListener {
            try {
                startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                toast("홈 설정을 열 수 없습니다")
            }
        }
        binding.btnHidden.setOnClickListener { showHiddenApps() }
    }

    private fun showHiddenApps() {
        val hidden = settings.hiddenPackages.toList()
        if (hidden.isEmpty()) {
            toast("숨긴 앱이 없습니다")
            return
        }
        val labels = hidden.map { repo.labelOf(it) }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle("숨긴 앱 (누르면 복원)")
            .setItems(labels) { _, which ->
                settings.removeHidden(hidden[which])
                toast(getString(R.string.hidden_restored))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private companion object {
        /** Request code of Shizuku's permission dialog. */
        const val REQUEST_SHIZUKU = 4201
    }
}
