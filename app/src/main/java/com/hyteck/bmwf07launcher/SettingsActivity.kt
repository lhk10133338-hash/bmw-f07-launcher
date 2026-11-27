package com.hyteck.bmwf07launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hyteck.bmwf07launcher.data.AppRepository
import com.hyteck.bmwf07launcher.data.Preset
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.engine.Privileges
import com.hyteck.bmwf07launcher.util.ScaledActivity
import com.hyteck.bmwf07launcher.databinding.ActivitySettingsBinding
import com.hyteck.bmwf07launcher.databinding.ItemPresetEditBinding
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService
import com.hyteck.bmwf07launcher.shizuku.ShizukuBridge
import com.hyteck.bmwf07launcher.ui.AppPickerDialog
import com.hyteck.bmwf07launcher.ui.TopHud
import com.hyteck.bmwf07launcher.util.BootFileBrowser
import com.hyteck.bmwf07launcher.util.BootMediaPicker
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.dp
import com.hyteck.bmwf07launcher.util.toast
import rikka.shizuku.Shizuku

class SettingsActivity : ScaledActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private lateinit var repo: AppRepository

    /** v4.13: the BMW mark stays over this screen as well; the clock belongs to the launcher. */
    private var hud: TopHud? = null

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

    /**
     * v4.2: whatever picker the unit answered with (`OPEN_DOCUMENT`, `GET_CONTENT` or a gallery
     * `PICK`) lands here. The file is copied into app storage right away: the boot logo plays
     * before any URI grant exists, so a `content://` handle alone would be gone by the next boot.
     */
    private val pickIntroMedia =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uri = data.data
                ?: data.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
                ?: return@registerForActivityResult
            importIntroMedia(uri)
        }

    /**
     * Storage access the built-in browser needs to list media. On the SystemUID build these are
     * already granted; on the standard build the answer only decides how much the list can see.
     */
    private val mediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // v4.3: whatever the user answered, the folder browser opens with what it can see.
            pendingAfterPermission?.invoke()
            pendingAfterPermission = null
        }

    /** Work to continue once a storage permission prompt has been answered (v4.3). */
    private var pendingAfterPermission: (() -> Unit)? = null

    /** Set while the "모든 파일 접근" settings screen is in front, so its return opens the browser. */
    private var waitingStorageReturn = false

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
        hud = TopHud.attach(this, showClock = false)

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
        setupTheme()
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
        // v4.3: back from the "모든 파일 접근" screen — open the browser the user was after.
        if (waitingStorageReturn) {
            waitingStorageReturn = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
                showBootFileBrowser()
            }
        }
    }

    override fun onDestroy() {
        hud?.release()
        hud = null
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
            // v4.3: a short press opens the launcher's own folder browser. The unit's own picker
            // (the one that shows only LOCAL > Ts) is a secondary button inside it — handing the
            // job to that picker first is what kept the user out of the folder their file is in.
            openBootFileBrowser()
        }
        binding.btnResetVideo.setOnClickListener {
            settings.introVideoUri = null
            settings.introMediaType = null
            settings.introBuiltin = false
            updateVideoName()
            toast("부팅 로고 없이 바로 시작합니다")
        }
        // v4.0: the built-in M animation is only used when the user asks for it; the boot screen
        // has no logo of its own otherwise.
        binding.btnBuiltinIntro.setOnClickListener {
            settings.introVideoUri = null
            settings.introMediaType = null
            settings.introBuiltin = true
            updateVideoName()
            toast("내장 M 인트로를 부팅 로고로 사용합니다")
        }
    }

    /**
     * v4.0 테마 — 완전블랙 / 완전블랙. Both paint the launcher `#000000`; the second one drops the
     * 1dp pane outline, so the whole screen is one flat black rectangle.
     */
    private fun setupTheme() {
        binding.rbThemeBlack.isChecked = settings.theme == Settings.THEME_BLACK
        binding.rbThemeBlackLine.isChecked = settings.theme == Settings.THEME_BLACK_LINE
        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.theme =
                if (checkedId == R.id.rbThemeBlackLine) Settings.THEME_BLACK_LINE
                else Settings.THEME_BLACK
        }
    }

    private fun updateVideoName() {
        val uri = settings.introVideoUri
        if (uri == null) {
            binding.tvVideoName.text = if (settings.introBuiltin) {
                getString(R.string.intro_builtin)
            } else {
                getString(R.string.intro_video_none)
            }
        } else {
            val name = try {
                Uri.parse(uri).lastPathSegment ?: uri
            } catch (e: Exception) {
                uri
            }
            binding.tvVideoName.text = getString(R.string.intro_video_selected, name)
        }
    }

    /**
     * Copies the picked file into the app's own storage and makes it the boot logo (v4.2).
     *
     * The copy runs off the main thread because a chosen video can be tens of megabytes; the
     * settings are written back on the main thread once the file is there.
     */
    private fun importIntroMedia(uri: Uri) {
        toast("부팅 로고 파일을 복사하고 있습니다…")
        Thread {
            val file = BootMediaPicker.copyIn(this, uri, "boot-logo")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    toast("선택한 파일을 읽을 수 없습니다. 다른 파일을 골라 주세요")
                    return@runOnUiThread
                }
                // v3.2: a picture is a valid intro too, so the MIME type is remembered next to the
                // URI and the launcher knows whether to decode an image or play a video.
                settings.introVideoUri = Uri.fromFile(file).toString()
                settings.introMediaType = BootMediaPicker.mediaTypeOf(file.name)
                settings.introBuiltin = false
                updateVideoName()
                toast(
                    if (isImageType(settings.introMediaType)) "인트로 사진으로 지정했습니다"
                    else "인트로 동영상으로 지정했습니다"
                )
            }
        }.start()
    }

    /** Short press of the pick button: the launcher's own folder browser (v4.3). */
    private fun openBootFileBrowser() {
        // v4.3: the folder browser needs whole-storage access to walk every folder. Without it the
        // list still opens, it just cannot read some of them.
        ensureStorageAccess { showBootFileBrowser() }
    }

    /** The launcher's own tree browser — the primary way to pick a boot video or picture. */
    private fun showBootFileBrowser() {
        BootFileBrowser(
            this,
            onPick = { uri -> importIntroMedia(uri) },
            onSystemPicker = { openSystemPicker() },
            onSearch = { showBuiltInMediaBrowser() }
        ).show()
    }

    /** The unit's own picker, kept as a secondary button in the browser dialog. */
    private fun openSystemPicker() {
        val opened = BootMediaPicker.launch { intent -> pickIntroMedia.launch(intent) }
        if (!opened) toast("이 기기에는 시스템 사진/동영상 선택기가 없습니다")
    }

    /**
     * v4.3: whole-storage access, so the tree browser can show every folder a file could be in.
     * The SystemUID build runs as `android.uid.system` and reads everything already, so it goes
     * straight on. Otherwise "모든 파일 접근" is offered, and declining still opens the browser.
     */
    private fun ensureStorageAccess(then: () -> Unit) {
        if (Privileges.isSystemUid()) {
            then()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                then()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("저장소 전체 접근")
                .setMessage(
                    "부팅 동영상은 어느 폴더에나 있을 수 있습니다.\n" +
                        "'모든 파일 접근'을 허용하면 기기의 모든 폴더를 볼 수 있고, " +
                        "허용하지 않으면 일부 폴더만 보입니다."
                )
                .setPositiveButton("권한 설정 열기") { _, _ -> openAllFilesSettings() }
                .setNeutralButton("그냥 둘러보기") { _, _ -> then() }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            then()
            return
        }
        pendingAfterPermission = then
        mediaPermission.launch(arrayOf(permission))
    }

    private fun openAllFilesSettings() {
        val intents = listOf(
            Intent(
                AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        for (intent in intents) {
            try {
                waitingStorageReturn = true
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next form (an older firmware only has the global screen).
            }
        }
        waitingStorageReturn = false
        toast("저장소 권한 설정 화면을 열 수 없습니다")
    }

    /**
     * The fallback for a unit without a picker: every video and picture the launcher can reach,
     * from the gallery's MediaStore index and from Download / Movies / Pictures / DCIM, listed in
     * one dialog. The scan runs on a worker thread, so a large DCIM folder cannot freeze the
     * settings screen.
     */
    private fun showBuiltInMediaBrowser() {
        toast("미디어 파일을 찾고 있습니다…")
        Thread {
            val items = BootMediaPicker.scan(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (items.isEmpty()) {
                    showNoMediaHint()
                    return@runOnUiThread
                }
                val labels = items.map { it.label() }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.intro_browser_title))
                    .setItems(labels) { _, which -> importIntroMedia(items[which].uri) }
                    .setNegativeButton(getString(R.string.intro_browser_cancel), null)
                    .show()
            }
        }.start()
    }

    /** Nothing found: says where to put the file, and offers to look again. */
    private fun showNoMediaHint() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.intro_browser_title))
            .setMessage(getString(R.string.intro_browser_empty))
            .setPositiveButton(getString(R.string.intro_browser_again)) { _, _ ->
                showBuiltInMediaBrowser()
            }
            .setNegativeButton(getString(R.string.intro_browser_cancel), null)
            .show()
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
            1. 기본 런처: '기본 런처로 설정'에서 BMW MN-Launcher를 고릅니다.
               (부팅 시 자동 실행이 켜져 있으면 부팅 완료 후에도 스스로 뜹니다.)

            2. 분할 방식
               · 가상 디스플레이(권장): 런처 안에 두 앱을 직접 그립니다. 제목줄이 없고 비율·배율이 즉시 반영됩니다.
                 표준 APK는 Shizuku 권한으로, SystemUID APK는 플랫폼 권한으로 이 화면을 만듭니다.
                 표준 APK에서 Shizuku가 없으면 접근성 서비스, 그것도 없으면 자유창 안전 모드로 자동 실행됩니다.
               · 자유창 안전 모드: 자유 형식 창이 켜져 있어야 2:8 같은 비율이 적용됩니다.
                 설정 > 개발자 옵션 > '자유 형식 창'을 켜고, 접근성 서비스를 함께 켜 주세요.

            3. 화면 사용법
               · 가운데 얇은 선을 좌우로 끌면 5:5 ~ 2:8로 조절됩니다. 선을 톡 누르면 비율 메뉴.
               · 각 창 아래 하단 가장자리에 비치는 가는 회색 바를 짧게 누르면 앱 변경·전체화면·앱 빼기·화면 배율 메뉴가 열립니다.
                 바는 몇 초 뒤 흐려지며, 앱 화면을 가리지 않도록 투명 오버레이로 그려집니다.
               · 바를 위로 쓰다듬거나 길게 눌러도 같은 메뉴가 열립니다.
               · 독의 + 는 지금 두 앱과 비율을 프리셋으로 저장합니다.
               · 독의 ‹ 를 누르면 독이 숨고, 화면 왼쪽 가장자리의 얇은 선을 누르면 다시 나옵니다.

            4. 시작 화면
               · 부팅 로고는 직접 고른 동영상·사진만 표시합니다. 지정하지 않으면 로고 없이 바로 시작합니다.
                 (설정 > 시작 및 복원 > 부팅 동영상·사진 선택)

            5. 상태 유지
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
        // v4.0: which privileged route this installation can take (SystemUID APK vs. Shizuku).
        val system = if (Privileges.canCreateTrustedDisplay(this)) {
            "시스템 권한: 사용 가능 (SystemUID · 카카런처와 같은 직접 방식)"
        } else {
            "시스템 권한: 없음 (표준 APK · Shizuku 필요)"
        }
        binding.engineDesc.text = "$base\n$system\n$accessibility"
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
            updateAutoPlayControlsEnabled()
        }
        binding.autoPlayDelaySeek.max = Settings.MAX_AUTO_PLAY_DELAY_SEC
        binding.autoPlayDelaySeek.progress = settings.autoPlayDelaySec
        binding.autoPlayDelayValue.text =
            getString(R.string.auto_play_delay_value, settings.autoPlayDelaySec)
        binding.autoPlayDelaySeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val seconds = progress.coerceIn(
                        Settings.MIN_AUTO_PLAY_DELAY_SEC,
                        Settings.MAX_AUTO_PLAY_DELAY_SEC
                    )
                    binding.autoPlayDelayValue.text =
                        getString(R.string.auto_play_delay_value, seconds)
                    if (fromUser) settings.autoPlayDelaySec = seconds
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    // no-op
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    // no-op
                }
            }
        )
        binding.switchAutoPlayNetwork.isChecked = settings.autoPlayWaitNetwork
        binding.switchAutoPlayNetwork.setOnCheckedChangeListener { _, checked ->
            settings.autoPlayWaitNetwork = checked
        }
        updateAutoPlayControlsEnabled()
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

    /**
     * v4.9: the delay slider and the network switch only matter while auto play is on; they are
     * greyed out otherwise so the screen still says which settings are in force.
     */
    private fun updateAutoPlayControlsEnabled() {
        val enabled = settings.autoPlayMusic
        binding.tvAutoPlayDelayTitle.isEnabled = enabled
        binding.autoPlayDelaySeek.isEnabled = enabled
        binding.autoPlayDelayValue.isEnabled = enabled
        binding.switchAutoPlayNetwork.isEnabled = enabled
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
