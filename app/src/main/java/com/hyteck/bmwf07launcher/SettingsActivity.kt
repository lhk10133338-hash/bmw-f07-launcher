package com.hyteck.bmwf07launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hyteck.bmwf07launcher.data.AppRepository
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.databinding.ActivitySettingsBinding
import com.hyteck.bmwf07launcher.databinding.ItemPresetEditBinding
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService
import com.hyteck.bmwf07launcher.ui.AppPickerDialog
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.toast

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private lateinit var repo: AppRepository

    private val presetRows = ArrayList<ItemPresetEditBinding>()

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: SecurityException) {
                // Some document providers do not offer persistable access.
            }
            settings.introVideoUri = uri.toString()
            updateVideoName()
            toast("인트로 동영상을 지정했습니다")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        settings = Settings(this)
        repo = AppRepository(this)

        binding.btnBack.setOnClickListener { finish() }

        setupEngine()
        setupRatio()
        setupDpi()
        setupPresets()
        setupIntro()
        setupDock()
        setupPermissions()

        updateVideoName()
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
            1. 기본 런처: '기본 런처로 설정'을 누르고 BMW F07 Launcher를 고릅니다.

            2. 시스템 분할 방식: '접근성 서비스 켜기'에서 'BMW F07 런처 분할 실행'을 켭니다.
               자유 형식 창이 꺼져 있으면 개발자 옵션의 '자유 형식 창'을 켜세요. 2:8 같은 비율은 이 기능이 있어야 적용됩니다.

            3. 가상 디스플레이 방식: '오버레이 권한 허용' 후 위에서 방식을 '가상 디스플레이'로 선택합니다.
               펌웨어가 허용하지 않으면 안내가 나오고, 이때는 시스템 분할 방식을 사용하세요.

            4. 프리셋 편집: 런처 독의 버튼을 길게 누르면 왼쪽/오른쪽 앱을 바꿀 수 있습니다.
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
        binding.leftDpiSeek.max = 36
        binding.rightDpiSeek.max = 36
        binding.leftDpiSeek.progress = (settings.leftDpi - 120) / 10
        binding.rightDpiSeek.progress = (settings.rightDpi - 120) / 10
        binding.leftDpiValue.text = "${settings.leftDpi} dpi"
        binding.rightDpiValue.text = "${settings.rightDpi} dpi"

        binding.leftDpiSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dpi = 120 + progress * 10
                binding.leftDpiValue.text = "$dpi dpi"
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
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dpi = 120 + progress * 10
                binding.rightDpiValue.text = "$dpi dpi"
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

    // --------------------------------------------------------------- presets

    private fun setupPresets() {
        binding.presetList.removeAllViews()
        presetRows.clear()
        for (index in 0 until 6) {
            val row = ItemPresetEditBinding.inflate(layoutInflater, binding.presetList, false)
            row.presetName.text = "${index + 1}"
            row.leftButton.setOnClickListener {
                AppPickerDialog.show(this, "${index + 1}번 프리셋 왼쪽 앱", repo.apps) { pkg ->
                    settings.setPresetSide(index, true, pkg)
                    refreshPresetRows()
                }
            }
            row.rightButton.setOnClickListener {
                AppPickerDialog.show(this, "${index + 1}번 프리셋 오른쪽 앱", repo.apps) { pkg ->
                    settings.setPresetSide(index, false, pkg)
                    refreshPresetRows()
                }
            }
            binding.presetList.addView(row.root)
            presetRows.add(row)
        }
        refreshPresetRows()
    }

    private fun refreshPresetRows() {
        for (index in 0 until 6) {
            val row = presetRows.getOrNull(index) ?: continue
            val left = settings.presetLeft(index)
                ?: repo.resolveKey(Settings.DEFAULT_LEFT[index])?.packageName
            val right = settings.presetRight(index)
                ?: repo.resolveKey(Settings.DEFAULT_RIGHT[index])?.packageName

            val leftName = if (left != null) {
                repo.labelOf(left)
            } else {
                repo.fallbackLabel(Settings.DEFAULT_LEFT[index]) + " (미설치)"
            }
            val rightName = if (right != null) {
                repo.labelOf(right)
            } else {
                repo.fallbackLabel(Settings.DEFAULT_RIGHT[index]) + " (미설치)"
            }
            row.leftButton.text = "◀ $leftName"
            row.rightButton.text = "$rightName ▶"
        }
    }

    // ----------------------------------------------------------------- intro

    private fun setupIntro() {
        binding.switchIntro.isChecked = settings.introEnabled
        binding.switchSkipReturn.isChecked = settings.introSkipOnReturn
        binding.switchIntro.setOnCheckedChangeListener { _, checked ->
            settings.introEnabled = checked
        }
        binding.switchSkipReturn.setOnCheckedChangeListener { _, checked ->
            settings.introSkipOnReturn = checked
        }
        binding.btnPickVideo.setOnClickListener {
            try {
                pickVideo.launch(arrayOf("video/*"))
            } catch (e: Exception) {
                toast("동영상 선택기를 열 수 없습니다")
            }
        }
        binding.btnResetVideo.setOnClickListener {
            settings.introVideoUri = null
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

    // ------------------------------------------------------------------ dock

    private fun setupDock() {
        binding.rbDockBottom.isChecked = !settings.dockAtLeft
        binding.rbDockLeft.isChecked = settings.dockAtLeft
        binding.dockGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.dockAtLeft = checkedId == R.id.rbDockLeft
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
                toast("앱 서랍에 다시 표시합니다")
            }
            .setNegativeButton("닫기", null)
            .show()
    }
}
