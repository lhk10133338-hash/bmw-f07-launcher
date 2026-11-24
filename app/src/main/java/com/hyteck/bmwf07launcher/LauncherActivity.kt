package com.hyteck.bmwf07launcher

import android.content.Intent
import android.graphics.Rect
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hyteck.bmwf07launcher.data.AppInfo
import com.hyteck.bmwf07launcher.data.AppRepository
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.databinding.ActivityLauncherBinding
import com.hyteck.bmwf07launcher.databinding.DockPresetBinding
import com.hyteck.bmwf07launcher.engine.Side
import com.hyteck.bmwf07launcher.engine.SplitEngine
import com.hyteck.bmwf07launcher.engine.SystemSplitEngine
import com.hyteck.bmwf07launcher.engine.VirtualDisplayEngine
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService
import com.hyteck.bmwf07launcher.ui.AppPickerDialog
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.dp
import com.hyteck.bmwf07launcher.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private lateinit var settings: Settings
    private lateinit var repo: AppRepository
    private var apps: List<AppInfo> = emptyList()

    private var engine: SplitEngine? = null
    private var engineMode: String = ""
    private var appliedLeftDpi: Int = 0
    private var appliedRightDpi: Int = 0

    private var leftPkg: String? = null
    private var rightPkg: String? = null
    private var currentPreset: Int = 0
    private var currentRatio: Int = 50
    private var leftFullscreen = false
    private var rightFullscreen = false
    private var sessionStarted = false
    private var introShowing = false
    private var dockHidden = false

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val dateFormat = SimpleDateFormat("M월 d일 EEE", Locale.KOREA)

    private val presetRows = ArrayList<DockPresetBinding>()
    private val ratioButtons = HashMap<Int, TextView>()

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 20_000L)
        }
    }

    private val dockHideRunnable = Runnable { hideDockNow() }

    private val paneResizeRunnable = Runnable {
        val current = engine ?: return@Runnable
        if (!current.inline) return@Runnable
        current.resize(Side.LEFT)
        current.resize(Side.RIGHT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        settings = Settings(this)
        repo = AppRepository(this)
        apps = repo.apps

        setupTopBar()
        setupDock()
        applyDockLayout()
        applyRatio(settings.ratio, false)
        setupDivider()
        setupPaneClicks()
        setupEngine()
        updateClock()
        updateDockLabels()
        updateEmptyLabels()
        handler.post(clockTick)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (leftFullscreen || rightFullscreen) {
                    setFullscreen(null)
                }
                // A launcher does not exit on back.
            }
        })

        val opened = handleIntent(intent)
        if (!opened) {
            if (settings.introEnabled && savedInstanceState == null) {
                playIntro()
            } else {
                startSession()
            }
        }
        scheduleDockHide()
    }

    // ---------------------------------------------------------------- top bar

    private fun setupTopBar() {
        val ratios = listOf(50, 60, 70, 80, 40, 30, 20)
        binding.ratioGroup.removeAllViews()
        ratioButtons.clear()
        for (ratio in ratios) {
            val button = TextView(this)
            button.text = ratioLabel(ratio)
            button.textSize = 12f
            button.gravity = Gravity.CENTER
            button.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            button.setBackgroundResource(R.drawable.bg_ratio_btn)
            button.setOnClickListener {
                applyRatio(ratio, true)
                schedulePaneResize()
            }
            val lp = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.MATCH_PARENT)
            lp.marginEnd = dp(5)
            binding.ratioGroup.addView(button, lp)
            ratioButtons[ratio] = button
        }

        binding.btnSwap.setOnClickListener { swapPanes() }
        binding.btnLeftFull.setOnClickListener {
            if (leftFullscreen) setFullscreen(null) else setFullscreen(Side.LEFT)
        }
        binding.btnRightFull.setOnClickListener {
            if (rightFullscreen) setFullscreen(null) else setFullscreen(Side.RIGHT)
        }
        binding.btnCloseFull.setOnClickListener { setFullscreen(null) }
        updateRatioButtons()
    }

    private fun ratioLabel(ratio: Int): String = "$ratio : ${100 - ratio}"

    private fun updateRatioButtons() {
        for ((ratio, button) in ratioButtons) {
            val selected = !leftFullscreen && !rightFullscreen && ratio == currentRatio
            button.setBackgroundResource(
                if (selected) R.drawable.bg_ratio_btn_on else R.drawable.bg_ratio_btn
            )
        }
    }

    // ------------------------------------------------------------------- dock

    private fun setupDock() {
        binding.dockPresets.removeAllViews()
        presetRows.clear()
        for (index in 0 until 6) {
            val row = DockPresetBinding.inflate(layoutInflater, binding.dockPresets, false)
            row.root.setOnClickListener { applyPreset(index) }
            row.root.setOnLongClickListener {
                showPresetEditor(index)
                true
            }
            binding.dockPresets.addView(row.root)
            presetRows.add(row)
        }
        binding.dockDrawer.setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
        }
        binding.dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun applyDockLayout() {
        val atLeft = settings.dockAtLeft
        val lp = binding.dock.layoutParams as FrameLayout.LayoutParams
        if (atLeft) {
            lp.width = dp(96)
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.START
            binding.dock.orientation = LinearLayout.VERTICAL
            binding.dockPresets.orientation = LinearLayout.VERTICAL
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = dp(88)
            lp.gravity = Gravity.BOTTOM
            binding.dock.orientation = LinearLayout.HORIZONTAL
            binding.dockPresets.orientation = LinearLayout.HORIZONTAL
        }
        binding.dock.layoutParams = lp

        for (row in presetRows) {
            row.root.layoutParams = if (atLeft) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).also { it.bottomMargin = dp(3) }
            } else {
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).also { it.marginEnd = dp(4) }
            }
        }

        val actionButtonParams = if (atLeft) {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        } else {
            LinearLayout.LayoutParams(
                dp(64),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        binding.dockDrawer.layoutParams = actionButtonParams
        val settingsParams = LinearLayout.LayoutParams(
            actionButtonParams.width,
            actionButtonParams.height
        )
        binding.dockSettings.layoutParams = settingsParams

        applyDockPadding()
    }

    private fun applyDockPadding() {
        if (dockHidden) {
            binding.mainColumn.setPadding(0, 0, 0, 0)
            return
        }
        if (settings.dockAtLeft) {
            binding.mainColumn.setPadding(dp(96), 0, 0, 0)
        } else {
            binding.mainColumn.setPadding(0, 0, 0, dp(88))
        }
    }

    private fun scheduleDockHide() {
        handler.removeCallbacks(dockHideRunnable)
        if (settings.dockAutoHide) {
            handler.postDelayed(dockHideRunnable, 6000L)
        }
    }

    private fun hideDockNow() {
        if (!settings.dockAutoHide || dockHidden) return
        dockHidden = true
        val left = settings.dockAtLeft
        binding.dock.animate()
            .translationX(if (left) (-binding.dock.width).toFloat() else 0f)
            .translationY(if (left) 0f else binding.dock.height.toFloat())
            .setDuration(220L)
            .withEndAction {
                if (dockHidden) binding.dock.visibility = View.GONE
            }
            .start()
        applyDockPadding()
    }

    private fun showDock() {
        if (!dockHidden) {
            scheduleDockHide()
            return
        }
        dockHidden = false
        binding.dock.visibility = View.VISIBLE
        binding.dock.translationX = 0f
        binding.dock.translationY = 0f
        applyDockPadding()
        scheduleDockHide()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::settings.isInitialized && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (settings.dockAutoHide) showDock()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- presets

    private fun presetPackages(index: Int): Pair<String?, String?> {
        val left = settings.presetLeft(index) ?: repo.resolveKey(Settings.DEFAULT_LEFT[index])?.packageName
        val right = settings.presetRight(index) ?: repo.resolveKey(Settings.DEFAULT_RIGHT[index])?.packageName
        return left to right
    }

    private fun updateDockLabels() {
        for (index in 0 until 6) {
            val row = presetRows.getOrNull(index) ?: continue
            val packages = presetPackages(index)
            val leftApp = packages.first
            val rightApp = packages.second
            val leftName = if (leftApp != null) repo.labelOf(leftApp) else repo.fallbackLabel(Settings.DEFAULT_LEFT[index])
            val rightName = if (rightApp != null) repo.labelOf(rightApp) else repo.fallbackLabel(Settings.DEFAULT_RIGHT[index])
            row.leftIcon.setImageDrawable(
                repo.iconOf(leftApp) ?: ContextCompat.getDrawable(this, R.drawable.ic_apps)
            )
            row.rightIcon.setImageDrawable(
                repo.iconOf(rightApp) ?: ContextCompat.getDrawable(this, R.drawable.ic_apps)
            )
            row.label.text = "$leftName + $rightName"
            row.root.alpha = if (leftApp == null || rightApp == null) 0.55f else 1f
            row.root.setBackgroundResource(
                if (index == currentPreset) R.drawable.bg_dock_preset_on else R.drawable.bg_dock_preset
            )
        }
    }

    private fun highlightPreset(index: Int) {
        currentPreset = index
        updateDockLabels()
    }

    private fun applyPreset(index: Int) {
        val packages = presetPackages(index)
        leftPkg = packages.first
        rightPkg = packages.second
        currentPreset = index
        settings.lastPreset = index
        highlightPreset(index)
        updateEmptyLabels()

        if (leftPkg == null && rightPkg == null) {
            toast(getString(R.string.app_missing))
            return
        }
        if (engine?.inline == false && !LauncherAccessibilityService.isEnabled(this)) {
            toast("접근성 서비스를 켜면 두 앱을 나란히 띄울 수 있습니다 (설정 > 접근성 서비스)")
        }
        leftPkg?.let { engine?.launch(Side.LEFT, it) }
        val right = rightPkg
        if (right != null) {
            if (engine?.inline == true) {
                engine?.launch(Side.RIGHT, right)
            } else {
                val delay = if (LauncherAccessibilityService.isEnabled(this)) 120L else 600L
                handler.postDelayed({ engine?.launch(Side.RIGHT, right) }, delay)
            }
        }
    }

    private fun showPresetEditor(index: Int) {
        val packages = presetPackages(index)
        val items = arrayOf(
            "왼쪽: " + repo.labelOf(packages.first),
            "오른쪽: " + repo.labelOf(packages.second),
            "기본값으로 되돌리기"
        )
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle("${index + 1}번 프리셋 · ${Settings.PRESET_TITLES[index]}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> AppPickerDialog.show(this, "왼쪽 앱 선택", apps) { pkg ->
                        settings.setPresetSide(index, true, pkg)
                        updateDockLabels()
                    }
                    1 -> AppPickerDialog.show(this, "오른쪽 앱 선택", apps) { pkg ->
                        settings.setPresetSide(index, false, pkg)
                        updateDockLabels()
                    }
                    else -> {
                        settings.clearPreset(index)
                        updateDockLabels()
                    }
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------ split panes

    private fun setupDivider() {
        binding.divider.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_MOVE -> {
                    val location = IntArray(2)
                    binding.paneRow.getLocationOnScreen(location)
                    val width = binding.paneRow.width
                    if (width > 0) {
                        val ratio = (((event.rawX - location[0]) / width) * 100f)
                            .toInt()
                            .coerceIn(20, 80)
                        applyRatio(ratio, false)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    settings.ratio = currentRatio
                    schedulePaneResize()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPaneClicks() {
        binding.leftEmpty.setOnClickListener {
            if (leftPkg != null && engine?.inline == false) {
                engine?.launch(Side.LEFT, leftPkg!!)
            } else {
                AppPickerDialog.show(this, "왼쪽 창 앱 선택", apps) { pkg ->
                    if (pkg != null) openPackage(pkg, "left")
                }
            }
        }
        binding.rightEmpty.setOnClickListener {
            if (rightPkg != null && engine?.inline == false) {
                engine?.launch(Side.RIGHT, rightPkg!!)
            } else {
                AppPickerDialog.show(this, "오른쪽 창 앱 선택", apps) { pkg ->
                    if (pkg != null) openPackage(pkg, "right")
                }
            }
        }
    }

    private fun applyRatio(ratio: Int, persist: Boolean) {
        currentRatio = ratio.coerceIn(20, 80)
        if (leftFullscreen || rightFullscreen) {
            if (persist) settings.ratio = currentRatio
            return
        }
        val leftParams = binding.leftPane.layoutParams as LinearLayout.LayoutParams
        leftParams.weight = currentRatio.toFloat()
        binding.leftPane.layoutParams = leftParams

        val rightParams = binding.rightPane.layoutParams as LinearLayout.LayoutParams
        rightParams.weight = (100 - currentRatio).toFloat()
        binding.rightPane.layoutParams = rightParams

        updateRatioButtons()
        if (persist) settings.ratio = currentRatio
    }

    private fun swapPanes() {
        val oldLeft = leftPkg
        leftPkg = rightPkg
        rightPkg = oldLeft
        updateEmptyLabels()
        currentPreset = -1
        updateDockLabels()
        leftPkg?.let { engine?.launch(Side.LEFT, it) }
        val right = rightPkg
        if (right != null) {
            if (engine?.inline == true) {
                engine?.launch(Side.RIGHT, right)
            } else {
                handler.postDelayed({ engine?.launch(Side.RIGHT, right) }, 400L)
            }
        }
    }

    private fun setFullscreen(side: Side?) {
        if (side == null) {
            binding.leftPane.visibility = View.VISIBLE
            binding.rightPane.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
            binding.btnCloseFull.visibility = View.GONE
            leftFullscreen = false
            rightFullscreen = false
            applyRatio(settings.ratio, false)
        } else {
            binding.leftPane.visibility = if (side == Side.LEFT) View.VISIBLE else View.GONE
            binding.rightPane.visibility = if (side == Side.RIGHT) View.VISIBLE else View.GONE
            binding.divider.visibility = View.GONE
            val shown = if (side == Side.LEFT) binding.leftPane else binding.rightPane
            val lp = shown.layoutParams as LinearLayout.LayoutParams
            lp.weight = 1f
            shown.layoutParams = lp
            leftFullscreen = side == Side.LEFT
            rightFullscreen = side == Side.RIGHT
            binding.btnCloseFull.visibility = View.VISIBLE
        }
        updateRatioButtons()
        schedulePaneResize()
    }

    private fun schedulePaneResize() {
        handler.removeCallbacks(paneResizeRunnable)
        handler.postDelayed(paneResizeRunnable, 320L)
    }

    private fun paneBounds(): Pair<Rect, Rect> {
        val left = Rect()
        val right = Rect()
        binding.leftPane.getGlobalVisibleRect(left)
        binding.rightPane.getGlobalVisibleRect(right)
        return left to right
    }

    // ----------------------------------------------------------------- engine

    private fun setupEngine() {
        engine?.release()
        engineMode = settings.engine
        appliedLeftDpi = settings.leftDpi
        appliedRightDpi = settings.rightDpi
        engine = if (settings.engine == Settings.ENGINE_VIRTUAL) {
            binding.leftSurface.visibility = View.VISIBLE
            binding.rightSurface.visibility = View.VISIBLE
            VirtualDisplayEngine(
                this,
                binding.leftSurface,
                binding.rightSurface,
                settings
            ) { message -> toast(message) }
        } else {
            binding.leftSurface.visibility = View.GONE
            binding.rightSurface.visibility = View.GONE
            SystemSplitEngine(this, { paneBounds() }) { message -> toast(message) }
        }
    }

    private fun updateEmptyLabels() {
        val inline = engine?.inline == true
        val leftName = leftPkg?.let { repo.labelOf(it) }
        val rightName = rightPkg?.let { repo.labelOf(it) }

        binding.leftEmpty.visibility = if (inline && leftName != null) View.GONE else View.VISIBLE
        binding.leftEmptyText.text =
            if (leftName == null) "왼쪽 창\n앱을 선택하세요" else "왼쪽 창\n$leftName"

        binding.rightEmpty.visibility = if (inline && rightName != null) View.GONE else View.VISIBLE
        binding.rightEmptyText.text =
            if (rightName == null) "오른쪽 창\n앱을 선택하세요" else "오른쪽 창\n$rightName"
    }

    // ------------------------------------------------------------------- apps

    private fun openPackage(pkg: String, side: String) {
        when (side) {
            "left" -> {
                leftPkg = pkg
                currentPreset = matchPreset(pkg, rightPkg)
                updateEmptyLabels()
                highlightPreset(currentPreset)
                engine?.launch(Side.LEFT, pkg)
            }
            "right" -> {
                rightPkg = pkg
                currentPreset = matchPreset(leftPkg, pkg)
                updateEmptyLabels()
                highlightPreset(currentPreset)
                engine?.launch(Side.RIGHT, pkg)
            }
            else -> {
                val launch = repo.launchIntent(pkg)
                if (launch == null) {
                    toast("앱을 실행할 수 없습니다")
                    return
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(launch)
                } catch (e: Exception) {
                    toast("앱 실행 실패: ${e.message}")
                }
            }
        }
    }

    private fun matchPreset(left: String?, right: String?): Int {
        for (index in 0 until 6) {
            val packages = presetPackages(index)
            if (packages.first == left && packages.second == right) return index
        }
        return -1
    }

    // ------------------------------------------------------------------ intro

    private fun playIntro() {
        if (introShowing) return
        introShowing = true
        binding.introLayer.visibility = View.VISIBLE
        binding.introLayer.alpha = 1f
        binding.introLayer.setOnClickListener { finishIntro() }
        binding.skipHint.visibility = View.VISIBLE

        val uri = settings.introVideoUri
        if (uri != null) {
            playVideoIntro(uri)
        } else {
            binding.introView.visibility = View.VISIBLE
            binding.introVideo.visibility = View.GONE
            binding.introView.onFinished = { finishIntro() }
            binding.introView.start()
        }
    }

    private fun playVideoIntro(uri: String) {
        binding.introView.stop()
        binding.introView.visibility = View.GONE
        val video = binding.introVideo
        video.visibility = View.VISIBLE
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            try {
                mp.setVolume(0f, 0f)
            } catch (ignored: Exception) {
                // Volume control is optional.
            }
            try {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            } catch (ignored: Exception) {
                // Scaling mode is optional.
            }
            video.start()
        }
        video.setOnCompletionListener { finishIntro() }
        video.setOnErrorListener { _, _, _ ->
            toast("인트로 동영상을 재생할 수 없습니다. 기본 인트로를 사용합니다.")
            restartAnimatedIntro()
            true
        }
        try {
            video.setVideoURI(Uri.parse(uri))
        } catch (e: Exception) {
            restartAnimatedIntro()
        }
    }

    private fun restartAnimatedIntro() {
        binding.introVideo.visibility = View.GONE
        binding.introView.visibility = View.VISIBLE
        binding.introView.onFinished = { finishIntro() }
        binding.introView.start()
    }

    private fun finishIntro() {
        if (!introShowing) return
        introShowing = false
        binding.introView.onFinished = null
        binding.introView.stop()
        try {
            binding.introVideo.stopPlayback()
        } catch (ignored: Exception) {
            // Video was not playing.
        }
        binding.introVideo.visibility = View.GONE
        binding.introLayer.animate()
            .alpha(0f)
            .setDuration(300L)
            .withEndAction {
                binding.introLayer.visibility = View.GONE
                binding.introLayer.alpha = 1f
                if (!isFinishing && !isDestroyed) {
                    startSession()
                }
            }
            .start()
    }

    // ------------------------------------------------------------------ state

    private fun startSession() {
        if (sessionStarted) return
        sessionStarted = true
        val index = settings.lastPreset
        currentPreset = index
        val packages = presetPackages(index)
        leftPkg = packages.first
        rightPkg = packages.second
        updateEmptyLabels()
        updateDockLabels()
        if (engine?.inline == true) {
            leftPkg?.let { engine?.launch(Side.LEFT, it) }
            rightPkg?.let { engine?.launch(Side.RIGHT, it) }
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: return false
        val side = intent.getStringExtra(EXTRA_SIDE) ?: "left"
        if (!sessionStarted) startSession()
        openPackage(pkg, side)
        intent.removeExtra(EXTRA_PACKAGE)
        intent.removeExtra(EXTRA_SIDE)
        return true
    }

    private fun updateClock() {
        val now = Date()
        binding.clockText.text = clockFormat.format(now)
        binding.dateText.text = dateFormat.format(now)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showDock()
        if (!handleIntent(intent)) {
            updateDockLabels()
            updateEmptyLabels()
            if (settings.introEnabled && !settings.introSkipOnReturn && !introShowing) {
                playIntro()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        if (engineMode.isNotEmpty() && engineMode != settings.engine) {
            setupEngine()
            sessionStarted = false
            startSession()
        }
        val current = engine
        if (current != null && current.inline) {
            if (appliedLeftDpi != settings.leftDpi) {
                appliedLeftDpi = settings.leftDpi
                current.setDensity(Side.LEFT, settings.leftDpi)
            }
            if (appliedRightDpi != settings.rightDpi) {
                appliedRightDpi = settings.rightDpi
                current.setDensity(Side.RIGHT, settings.rightDpi)
            }
        }
        if (!settings.dockAutoHide && dockHidden) {
            showDock()
        }
        if (!leftFullscreen && !rightFullscreen && settings.ratio != currentRatio) {
            applyRatio(settings.ratio, false)
            schedulePaneResize()
        }
        applyDockLayout()
        updateDockLabels()
        updateEmptyLabels()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.introView.onFinished = null
        binding.introView.stop()
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_SIDE = "extra_side"
    }
}
