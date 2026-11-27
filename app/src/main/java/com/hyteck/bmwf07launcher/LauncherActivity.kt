package com.hyteck.bmwf07launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.hyteck.bmwf07launcher.data.AppInfo
import com.hyteck.bmwf07launcher.data.AppRepository
import com.hyteck.bmwf07launcher.data.Preset
import com.hyteck.bmwf07launcher.data.Settings
import com.hyteck.bmwf07launcher.util.ScaledActivity
import com.hyteck.bmwf07launcher.databinding.ActivityLauncherBinding
import com.hyteck.bmwf07launcher.databinding.DockPresetBinding
import com.hyteck.bmwf07launcher.engine.Privileges
import com.hyteck.bmwf07launcher.engine.Side
import com.hyteck.bmwf07launcher.engine.SplitEngine
import com.hyteck.bmwf07launcher.engine.SystemSplitEngine
import com.hyteck.bmwf07launcher.engine.VirtualDisplayEngine
import com.hyteck.bmwf07launcher.service.LauncherAccessibilityService
import com.hyteck.bmwf07launcher.shizuku.ShizukuBridge
import com.hyteck.bmwf07launcher.touch.VirtualTouchRouter
import com.hyteck.bmwf07launcher.ui.PanePanel
import com.hyteck.bmwf07launcher.ui.RatioMenu
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.dp
import com.hyteck.bmwf07launcher.util.setOnLongPressAction
import com.hyteck.bmwf07launcher.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarCar-Launcher style home screen:
 * two full-bleed rectangular panes, a slim left dock (clock, preset tiles, save, drawer,
 * settings) and one-pixel divider. No top bar, no bottom bar.
 */
class LauncherActivity : ScaledActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private lateinit var settings: Settings
    private lateinit var repo: AppRepository
    private var apps: List<AppInfo> = emptyList()

    private var engine: SplitEngine? = null
    private var engineMode: String = ""
    private var leftPkg: String? = null
    private var rightPkg: String? = null
    private var ratio: Int = 50
    private var fullscreen: Side? = null
    private var sessionStarted = false
    private var introShowing = false
    private var dockHidden = false
    private var currentPreset: Int = -1
    private var appliedLeftDpi: Int = 0
    private var appliedRightDpi: Int = 0
    private var appliedDockAtLeft: Boolean? = null

    /** v3.4: puts the touches that land on a pane into that pane's virtual display. */
    private val touchRouter by lazy { VirtualTouchRouter(this) }

    /** The "Shizuku 또는 접근성 서비스가 필요합니다" hint is shown once per launch. */
    private var engineHintShown = false

    /**
     * Set when the firmware refused a virtual display this run. The user's stored choice is kept
     * (a Shizuku permission can still be granted later); only this run falls back.
     */
    private var virtualRefused = false

    /** Fired when Shizuku's binder or the touch service appears: the engine may now be switched. */
    private val shizukuStateListener: (ShizukuBridge.State) -> Unit = { state ->
        handler.post {
            if (state == ShizukuBridge.State.READY) virtualRefused = false
            syncEngineWithShizuku()
        }
    }

    /** Last rectangles handed to the engine, so identical layout passes are not repeated. */
    private var lastBoundsKey: String? = null

    /** Each pane owns an inline menu overlay; neither one is a separate window any more. */
    private lateinit var leftPanel: PanePanel
    private lateinit var rightPanel: PanePanel

    private var appliedUiScale = 0

    /**
     * True only when one of the launcher's own screens (settings / app drawer) is being opened.
     * The two free-form windows are put back only on the way back from such a screen; when the
     * user closed an app window with its own × button, the launcher must leave it closed instead
     * of starting it again (v3.2 reopened it every time, without end).
     */
    private var resumeFromOwnScreen = false

    /**
     * True while the settings screen or the app drawer is being opened: the restore of the two
     * free-form windows must not compete with that screen's transition.
     */
    private var suppressAutoRestore = false

    // ------------------------------------------------------- music auto play (v4.9)

    /**
     * v4.9: the PLAY key is no longer sent once, 1.5 s after the tap.
     *
     * On the car unit a music app is still starting at 1.5 s and its LTE link is often still
     * negotiating, so the key was dropped and nothing played (user report of 2026-11-27). The
     * chain below waits the delay chosen in the settings, optionally waits for a data connection,
     * then keeps nudging the player until it reports itself playing.
     *
     * Every step carries the token of the request it belongs to: a newer request, a settings
     * change or a pane that no longer holds the music app makes the remaining steps drop out, so
     * a late step can never play something the user did not ask for.
     */
    private var autoPlayToken = 0
    private var autoPlayNetworkWaitStart = 0L
    private var autoPlaySendCount = 0
    private val autoPlaySteps = ArrayList<Runnable>()

    private val restoreRunnable = Runnable {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@Runnable
        if (suppressAutoRestore) return@Runnable
        if (!sessionStarted) return@Runnable
        if (leftPanel.isOpen || rightPanel.isOpen) return@Runnable
        val current = engine ?: return@Runnable
        val sides = HashSet<Side>()
        val single = fullscreen
        if (single != null) {
            if (pkgOf(single) != null) sides.add(single)
        } else {
            if (leftPkg != null) sides.add(Side.LEFT)
            if (rightPkg != null) sides.add(Side.RIGHT)
        }
        if (sides.isNotEmpty()) current.resume(sides)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val dateFormat = SimpleDateFormat("M/d EEE", Locale.KOREA)
    private val presetRows = ArrayList<DockPresetBinding>()
    private var cachedPresets: List<Preset> = emptyList()

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 20_000L)
        }
    }

    private val dockHideRunnable = Runnable { setDockHidden(true, persist = false) }

    private val boundsRunnable = Runnable { applyBounds(commit = false) }

    private val commitRunnable = Runnable { applyBounds(commit = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersive()

        settings = Settings(this)
        applyTheme()
        repo = AppRepository(this)
        apps = repo.apps
        ShizukuBridge.init(this)
        ShizukuBridge.addListener(shizukuStateListener)
        migrateEngine()
        leftPanel = PanePanel(this, binding.leftOverlay)
        rightPanel = PanePanel(this, binding.rightOverlay)
        appliedUiScale = settings.uiScale

        ratio = settings.ratio
        binding.split.ratio = ratio

        setupDock()
        setupPanes()
        setupDivider()
        setupEngine()
        restoreSession()
        applyDockSide(force = true)
        updatePresetList()
        updateEmptyStates()
        updateClock()
        setDockHidden(settings.dockHidden, persist = false)
        handler.post(clockTick)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (leftPanel.isOpen || rightPanel.isOpen) {
                    closePanePanels()
                    return
                }
                if (fullscreen != null || (engine?.inline == false && (leftPkg != null || rightPkg != null))) {
                    if (fullscreen != null) setFullscreen(null)
                }
                // A car launcher does not leave the screen on back.
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

    // ------------------------------------------------------------------ dock

    /**
     * One-time move back to the free-form engine for installations that v2.1 had switched to the
     * virtual display engine: those panes cannot receive touch, and v3.0 ships without Shizuku or
     * any other privileged helper.
     */
    private fun migrateEngine() {
        if (!settings.engineMigratedV3) {
            if (settings.engine != Settings.ENGINE_SYSTEM) {
                settings.engine = Settings.ENGINE_SYSTEM
            }
            settings.engineMigratedV3 = true
        }
        // v3.4: the user chose the virtual-display engine (2026-11-24). The preference is stored
        // once here; whether it is actually used is decided per launch by [effectiveEngine] —
        // the virtual engine needs Shizuku or the accessibility service to carry the touch.
        if (!settings.engineMigratedV4) {
            settings.engine = Settings.ENGINE_VIRTUAL
            settings.engineMigratedV4 = true
        }
    }

    private fun setupDock() {
        binding.dockDrawer.setOnClickListener { openDrawer() }
        binding.dockSettings.setOnClickListener { openSettings() }
        binding.dockSave.setOnClickListener { saveCurrentPreset() }
        binding.dockHide.setOnClickListener { setDockHidden(true, persist = true) }
        binding.dockEdge.setOnClickListener { setDockHidden(false, persist = true) }
    }

    /**
     * v4.0 테마 — 완전블랙 / 완전블랙.
     *
     * The user asked for "완전블랙 / 완전블랙" in the theme setting. Both entries paint the launcher
     * `#000000`; they differ only in the 1dp seam around the panes, so the choice is visible on an
     * empty screen:
     *  - [Settings.THEME_BLACK] — pure black, no line at all (default).
     *  - [Settings.THEME_BLACK_LINE] — black with the thin #161616 pane outline.
     * Every surface of the launcher is set here, so a screen that was built before the theme was
     * changed is corrected on the next `onResume` as well.
     */
    private fun applyTheme() {
        if (!::binding.isInitialized) return
        binding.root.setBackgroundColor(Color.BLACK)
        binding.rootRow.setBackgroundColor(Color.BLACK)
        binding.split.setBackgroundColor(Color.BLACK)
        binding.introLayer.setBackgroundColor(Color.BLACK)
        binding.leftOverlay.setBackgroundColor(Color.BLACK)
        binding.rightOverlay.setBackgroundColor(Color.BLACK)
        binding.leftEmpty.setBackgroundColor(Color.BLACK)
        binding.rightEmpty.setBackgroundColor(Color.BLACK)
        if (settings.theme == Settings.THEME_BLACK_LINE) {
            binding.leftPane.setBackgroundResource(R.drawable.bg_pane)
            binding.rightPane.setBackgroundResource(R.drawable.bg_pane)
        } else {
            binding.leftPane.setBackgroundColor(Color.BLACK)
            binding.rightPane.setBackgroundColor(Color.BLACK)
        }
    }

    /**
     * Opens a full launcher screen (settings / app drawer).
     *
     * While two free-form windows are running, a restore that fires during the transition would
     * pull those windows back over the new screen and swallow the first tap, so any pending
     * restore is cancelled and autorestored is suppressed until this screen comes back.
     */
    private fun openSettings() {
        suppressAutoRestore = true
        resumeFromOwnScreen = true
        handler.removeCallbacks(restoreRunnable)
        startActivity(Intent(this, SettingsActivity::class.java))
        noTransition()
    }

    private fun openDrawer() {
        suppressAutoRestore = true
        resumeFromOwnScreen = true
        handler.removeCallbacks(restoreRunnable)
        startActivity(Intent(this, AppDrawerActivity::class.java))
        noTransition()
    }

    /** The launcher never animates between its own screens: the panes must not jump. */
    @Suppress("DEPRECATION")
    private fun noTransition() {
        overridePendingTransition(0, 0)
    }

    private fun applyDockSide(force: Boolean = false) {
        if (!force && appliedDockAtLeft == settings.dockAtLeft) {
            if (!dockHidden) binding.dock.visibility = View.VISIBLE
            return
        }
        appliedDockAtLeft = settings.dockAtLeft
        val dock = binding.dock
        val split = binding.split
        binding.rootRow.removeAllViews()
        val dockParams = LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT)
        val splitParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        if (settings.dockAtLeft) {
            binding.rootRow.addView(dock, dockParams)
            binding.rootRow.addView(split, splitParams)
        } else {
            binding.rootRow.addView(split, splitParams)
            binding.rootRow.addView(dock, dockParams)
        }
        binding.dock.animate().cancel()
        binding.dock.translationX = 0f
        binding.dock.translationY = 0f
        if (!dockHidden) binding.dock.visibility = View.VISIBLE
        scheduleBounds(commit = true)
    }

    private fun setDockHidden(hidden: Boolean, persist: Boolean) {
        if (dockHidden == hidden) {
            if (persist) settings.dockHidden = hidden
            return
        }
        dockHidden = hidden
        if (persist) settings.dockHidden = hidden
        binding.dock.animate().cancel()
        // v4.5: the dock is hidden, not removed from the layout. `GONE` used to move both panes by
        // 56dp, and on the virtual-display engine a pane that changes rectangle has to be given a
        // new display (this firmware never re-binds an existing one) — which would restart both
        // apps every time the dock slid away, including on the 6 s auto-hide. `INVISIBLE` keeps the
        // column reserved, so hiding the dock costs a black strip on the left and nothing else.
        binding.dock.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        binding.dockEdge.visibility = if (hidden) View.VISIBLE else View.GONE
        handler.removeCallbacks(dockHideRunnable)
        if (!hidden) scheduleDockHide()
        scheduleBounds(commit = true)
    }

    private fun scheduleDockHide() {
        handler.removeCallbacks(dockHideRunnable)
        if (settings.dockAutoHide && !dockHidden) {
            handler.postDelayed(dockHideRunnable, 6000L)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::settings.isInitialized && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            if (settings.dockAutoHide) scheduleDockHide()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun updateClock() {
        val now = Date()
        binding.clockText.text = clockFormat.format(now)
        binding.dateText.text = dateFormat.format(now)
    }

    // --------------------------------------------------------------- presets

    private fun updatePresetList() {
        binding.presetList.removeAllViews()
        presetRows.clear()
        val presets = settings.presets()
        cachedPresets = presets
        if (presets.isEmpty()) {
            val hint = TextView(this).apply {
                text = getString(R.string.preset_empty_hint)
                textSize = 7f
                setTextColor(0xFF5A5A5A.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(2), dp(8), dp(2), dp(8))
            }
            binding.presetList.addView(hint)
            return
        }
        presets.forEachIndexed { index, preset ->
            val row = DockPresetBinding.inflate(layoutInflater, binding.presetList, false)
            val leftIcon = repo.iconOf(preset.left)
            val rightIcon = repo.iconOf(preset.right)
            row.leftIcon.setImageDrawable(leftIcon ?: ContextCompat.getDrawable(this, R.drawable.ic_plus))
            row.rightIcon.setImageDrawable(rightIcon ?: ContextCompat.getDrawable(this, R.drawable.ic_plus))
            row.leftIcon.alpha = if (preset.left == null) 0.35f else 1f
            row.rightIcon.alpha = if (preset.right == null) 0.35f else 1f
            row.ratioText.text = "${preset.ratio}:${100 - preset.ratio}"
            row.root.setOnClickListener { applyPreset(index) }
            row.root.setOnLongClickListener {
                showPresetMenu(index)
                true
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            params.bottomMargin = dp(3)
            row.root.isFocusable = false
            binding.presetList.addView(row.root, params)
            presetRows.add(row)
        }
        updatePresetHighlight()
    }

    private fun updatePresetHighlight() {
        val presets = cachedPresets
        currentPreset = presets.indexOfFirst {
            it.left == leftPkg && it.right == rightPkg && it.ratio == ratio
        }
        presetRows.forEachIndexed { index, row ->
            row.root.setBackgroundResource(
                if (index == currentPreset) R.drawable.bg_dock_preset_on else R.drawable.bg_dock_preset
            )
        }
    }

    private fun saveCurrentPreset() {
        if (leftPkg == null && rightPkg == null) {
            toast(getString(R.string.preset_empty))
            return
        }
        val presets = cachedPresets
        // v4.12: the ratio is part of the default name as well as of the dock row, so two presets
        // of the same two apps (30:70 and 70:30) can be told apart in the long-press menu too.
        val name = "${repo.labelOf(leftPkg)} + ${repo.labelOf(rightPkg)} " +
            "(${ratio}:${100 - ratio})"
        val added = settings.addPreset(Preset(name, leftPkg, rightPkg, ratio))
        if (!added) {
            toast(
                if (presets.size >= Settings.MAX_PRESETS) {
                    getString(R.string.preset_full, Settings.MAX_PRESETS)
                } else {
                    getString(R.string.preset_duplicate)
                }
            )
            return
        }
        updatePresetList()
        toast(getString(R.string.preset_saved))
    }

    private fun applyPreset(index: Int) {
        val preset = cachedPresets.getOrNull(index) ?: return
        val inlineRatioChanges = engine?.inline == true && (preset.ratio != ratio || fullscreen != null)
        if (inlineRatioChanges) {
            leftPkg = preset.left
            rightPkg = preset.right
            settings.sessionLeft = leftPkg
            settings.sessionRight = rightPkg
            engine?.preparePackages(preset.left, preset.right)
            // v4.11: a pane the preset leaves empty is emptied for real, not just covered.
            if (preset.left == null) engine?.clear(Side.LEFT)
            if (preset.right == null) engine?.clear(Side.RIGHT)
            setFullscreen(null)
            setRatio(preset.ratio, persist = true, commit = true)
            updateEmptyStates()
            currentPreset = index
            updatePresetHighlight()
            preset.left?.let { scheduleAutoPlay(it) }
            preset.right?.let { scheduleAutoPlay(it) }
            return
        }
        // Ratio first, then the apps (the order CarCar users found reliable).
        setFullscreen(null)
        setRatio(preset.ratio, persist = true, commit = false)
        applySide(Side.LEFT, preset.left)
        applySide(Side.RIGHT, preset.right)
        currentPreset = index
        updatePresetHighlight()
        scheduleBounds(commit = true)
    }

    private fun showPresetMenu(index: Int) {
        val preset = cachedPresets.getOrNull(index) ?: return
        val items = arrayOf(
            getString(R.string.preset_menu_run),
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
                    0 -> applyPreset(index)
                    1 -> renamePreset(index)
                    2 -> openPanePicker(Side.LEFT) { pkg ->
                        settings.updatePreset(index) { it.copy(left = pkg) }
                        updatePresetList()
                    }

                    3 -> openPanePicker(Side.RIGHT) { pkg ->
                        settings.updatePreset(index) { it.copy(right = pkg) }
                        updatePresetList()
                    }

                    4 -> {
                        settings.movePreset(index, index - 1)
                        updatePresetList()
                    }

                    5 -> {
                        settings.movePreset(index, index + 1)
                        updatePresetList()
                    }

                    else -> {
                        settings.removePreset(index)
                        updatePresetList()
                        toast(getString(R.string.preset_deleted))
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun renamePreset(index: Int) {
        val preset = cachedPresets.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(preset.name)
            setSelection(text.length)
            setTextColor(0xFFEDEDED.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(12), dp(4), dp(12), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, R.style.Theme_BmwLauncher_Dialog)
            .setTitle(R.string.preset_rename_title)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val name = input.text.toString().trim()
                settings.updatePreset(index) { it.copy(name = name) }
                updatePresetList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ split panes

    private fun setupPanes() {
        binding.leftEmpty.setOnClickListener { pickApp(Side.LEFT) }
        binding.rightEmpty.setOnClickListener { pickApp(Side.RIGHT) }
        // A long press anywhere on the pane (its empty area or its handle) opens that pane's own
        // inline menu: 앱 변경·선택 · 전체화면 · 앱 빼기. v3.2 fires it after ~0.8 s for both, so the
        // menu appears while the finger is still down instead of only when the platform's own
        // (shorter, easily missed) long-click happens to come through.
        binding.leftEmpty.setOnLongPressAction { openPaneMenu(Side.LEFT) }
        binding.rightEmpty.setOnLongPressAction { openPaneMenu(Side.RIGHT) }

        binding.leftHandle.onTap = { openPaneMenu(Side.LEFT) }
        binding.leftHandle.onLongPress = { openPaneMenu(Side.LEFT) }
        binding.leftHandle.onSwipeUp = { openPaneMenu(Side.LEFT) }

        binding.rightHandle.onTap = { openPaneMenu(Side.RIGHT) }
        binding.rightHandle.onLongPress = { openPaneMenu(Side.RIGHT) }
        binding.rightHandle.onSwipeUp = { openPaneMenu(Side.RIGHT) }

        binding.leftFullExit.setOnClickListener { setFullscreen(null) }
        binding.rightFullExit.setOnClickListener { setFullscreen(null) }

        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                scheduleBounds(commit = false)
            }
        }
        binding.leftPane.addOnLayoutChangeListener(layoutListener)
        binding.rightPane.addOnLayoutChangeListener(layoutListener)
    }

    private fun setupDivider() {
        binding.divider.onRatioChange = { value, commit ->
            setRatio(value, persist = commit, commit = commit)
        }
        binding.divider.onOpenRatioMenu = {
            RatioMenu.show(
                this,
                ratio,
                { picked -> setRatio(picked, persist = true, commit = true) },
                { swapPanes() }
            )
        }
    }

    private fun setRatio(value: Int, persist: Boolean, commit: Boolean) {
        ratio = value.coerceIn(20, 80)
        binding.split.ratio = ratio
        if (persist) settings.ratio = ratio
        if (commit) updatePresetHighlight()
        scheduleBounds(commit)
    }

    private fun setFullscreen(side: Side?) {
        closePanePanels()
        val previous = fullscreen
        fullscreen = side
        binding.split.fullscreen = side
        // v4.0: the pane that fullscreen left behind is frozen instead of being left to draw.
        //
        // v4.11: only the pane whose visibility really changes is told. The pane that becomes the
        // fullscreen one was visible all along, and `setDisplayState(true)` on a display that was
        // never frozen is the call that left both panes blank on this firmware in v4.1, so it is
        // no longer sent to it. Going fullscreen freezes the other pane; leaving fullscreen
        // resumes exactly that pane.
        if (previous != side && engine?.inline == true) {
            // The pane that fullscreen had hidden comes back, and the pane the fullscreen one is
            // now covering is frozen. The engine itself ignores "show" for a pane it never froze,
            // so the fullscreen pane is never sent `setDisplayState(true)`.
            if (previous != null) engine?.setPaneVisible(opposite(previous), true)
            if (side != null) engine?.setPaneVisible(opposite(side), false)
        }
        binding.leftFullExit.visibility = if (side == Side.LEFT) View.VISIBLE else View.GONE
        binding.rightFullExit.visibility = if (side == Side.RIGHT) View.VISIBLE else View.GONE
        binding.leftEmpty.isClickable = side != Side.RIGHT
        binding.rightEmpty.isClickable = side != Side.LEFT
        scheduleBounds(commit = true)
    }

    /**
     * Hands the pane rectangles to the engine.
     *
     * While the divider is being dragged only an engine that draws the apps *inside* the launcher
     * (virtual display) is told about it. A free-form engine is only asked once, when the gesture
     * ends, so dragging can never restart another app.
     */
    private fun scheduleBounds(commit: Boolean) {
        // v4.11: a confirmed change is announced the moment it happens. The layout pass that
        // follows it, a frame or two later, then replaces the pane's display without the settle a
        // divider drag needs (see VirtualDisplayEngine.markCommitted).
        if (commit) engine?.markCommitted()
        handler.removeCallbacks(boundsRunnable)
        if (commit || engine?.inline == true) {
            handler.postDelayed(boundsRunnable, if (commit) 0L else 80L)
        }
        if (commit) {
            handler.removeCallbacks(commitRunnable)
            handler.postDelayed(commitRunnable, if (engine?.inline == true) 240L else 320L)
        }
    }

    /**
     * Hands the measured pane rectangles to the engine. Called from every layout change and
     * after the divider is released; the engines resize live (virtual display) or re-place the
     * running windows (system split).
     */
    private fun applyBounds(commit: Boolean) {
        val current = engine ?: return
        if (binding.split.width <= 0) return
        val left = binding.split.screenRectOf(Side.LEFT)
        val right = binding.split.screenRectOf(Side.RIGHT)
        if (left.width() <= 0 || right.width() <= 0) return
        // A floating app window must never start on top of the dock: the dock's own buttons
        // would lose their first tap to the window above them. v3.0 used 2dp, which was not
        // enough — a free-form window carries its border/shadow a few pixels outside the
        // rectangle it was handed, so its left edge still sat on the dock and swallowed taps.
        val dock = dockRectOnScreen()
        val gap = dp(DOCK_GAP_DP)
        var minLeft = 0
        var maxRight = 0
        if (dock != null) {
            if (settings.dockAtLeft) {
                // Never closer than the dock's own right edge plus the gap (or its width + gap).
                minLeft = maxOf(dock.right, dock.width()) + gap
                if (left.left < minLeft) left.left = minLeft
            } else {
                maxRight = dock.left - gap
                if (right.right > maxRight) right.right = maxRight
            }
        }
        current.setDockKeepOut(minLeft, maxRight)
        // v4.0: a virtual-display pane is the whole pane rectangle — no bottom strip is taken
        // out of it, so the app reaches the very bottom edge of the screen. The launcher's handle
        // is a transparent overlay drawn on top of the pane instead. Only the free-form engine,
        // whose window would otherwise cover that overlay, keeps a slim strip free.
        val strip = if (current.inline) 0 else dp(OVERLAY_HANDLE_DP)
        if (left.bottom - strip > left.top + dp(MIN_PANE_DP)) left.bottom -= strip
        if (right.bottom - strip > right.top + dp(MIN_PANE_DP)) right.bottom -= strip
        // Layout callbacks, the divider and onResume all report the same rectangles; hand them
        // to the engine once so the windows are not re-placed (and the screen does not jump).
        val key = "$commit|${left.toShortString()}|${right.toShortString()}|$minLeft|$maxRight"
        if (key == lastBoundsKey) return
        lastBoundsKey = key
        // v4.11: the engine is told whether this pass is a *committed* change (a preset, the ratio
        // menu, a fullscreen toggle, a resume) or a pass in the middle of a drag. An inline engine
        // uses it to choose how long it waits before replacing a pane's display; always sending
        // "committed" took the drag debounce away and could replace a display while the finger was
        // still moving.
        current.applyBounds(left, right, commit)
    }

    /** Area of the visible dock in screen pixels (null when the dock is hidden). */
    private fun dockRectOnScreen(): Rect? {
        if (binding.dock.visibility != View.VISIBLE || binding.dock.width <= 0) return null
        val location = IntArray(2)
        binding.dock.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + binding.dock.width,
            location[1] + binding.dock.height
        )
    }

    // ------------------------------------------------------------- pane menus

    private fun panelOf(side: Side): PanePanel = if (side == Side.LEFT) leftPanel else rightPanel

    private fun closePanePanels() {
        leftPanel.close()
        rightPanel.close()
    }

    private fun paneTitle(side: Side): String =
        getString(if (side == Side.LEFT) R.string.pane_left else R.string.pane_right)

    /** The handle menu of one pane, drawn inside that pane only. */
    private fun openPaneMenu(side: Side) {
        closePanePanels()
        val pkg = pkgOf(side)
        val virtual = engine?.supportsDpi == true
        val subtitle = if (pkg == null) getString(R.string.pane_empty) else repo.labelOf(pkg)
        panelOf(side).showMenu(
            title = paneTitle(side),
            subtitle = subtitle,
            hasApp = pkg != null,
            dpi = if (virtual) dpiOf(side) else null,
            isFullscreen = fullscreen == side,
            onPickApp = { openPanePicker(side) },
            onFullscreen = {
                closePanePanels()
                setFullscreen(if (fullscreen == side) null else side)
            },
            onClear = {
                closePanePanels()
                applySide(side, null)
                toast(getString(R.string.pane_cleared))
            },
            onScale = if (virtual) {
                {
                    panelOf(side).showScale(
                        title = paneTitle(side),
                        subtitle = subtitle,
                        initialDpi = dpiOf(side),
                        requiresVirtual = false
                    ) { dpi -> applyDpi(side, dpi) }
                }
            } else {
                null
            },
            // v4.5: a pane that came back black is rebuilt (new display + app) on request.
            onRedraw = if (virtual && pkg != null) {
                {
                    closePanePanels()
                    engine?.redraw(side)
                    toast(getString(R.string.pane_redraw))
                }
            } else {
                null
            }
        )
    }

    /**
     * Installed-app list of one pane, inside that pane only. [onPicked] overrides what happens
     * with the choice (used when editing a preset's app from the dock).
     */
    private fun openPanePicker(side: Side, onPicked: ((String?) -> Unit)? = null) {
        closePanePanels()
        val pkg = pkgOf(side)
        panelOf(side).showPicker(
            title = paneTitle(side),
            subtitle = if (pkg == null) getString(R.string.app_add_hint)
            else getString(R.string.pane_pick_hint),
            apps = apps,
            current = pkg,
            allowClear = onPicked != null || pkg != null
        ) { picked ->
            if (onPicked != null) onPicked(picked) else applySide(side, picked)
        }
    }

    private fun pickApp(side: Side) {
        val pkg = pkgOf(side)
        if (pkg != null && engine?.inline == false) {
            // In system mode the pane is only a placeholder: tapping it re-opens the app.
            launchApp(side, pkg)
            return
        }
        openPanePicker(side)
    }

    private fun pkgOf(side: Side): String? = if (side == Side.LEFT) leftPkg else rightPkg

    private fun opposite(side: Side): Side = if (side == Side.LEFT) Side.RIGHT else Side.LEFT

    private fun dpiOf(side: Side): Int =
        if (side == Side.LEFT) settings.leftDpi else settings.rightDpi

    /**
     * Launches [pkg] into [side] and, when it is the music app the user designated in the
     * settings, starts playback a moment later.
     */
    private fun launchApp(side: Side, pkg: String) {
        engine?.launch(side, pkg)
        scheduleAutoPlay(pkg)
    }

    /**
     * Music players opened from a launcher often come up paused, so a media PLAY key is sent once
     * the app has had time to show its player (see the user request of 2026-11-24).
     *
     * v4.9: how long that is, and whether to wait for data first, comes from the settings; the
     * key is repeated while the player stays silent (see the user report of 2026-11-27).
     */
    private fun scheduleAutoPlay(pkg: String) {
        if (!settings.autoPlayMusic) {
            cancelAutoPlay()
            return
        }
        val music = settings.musicPackage ?: return
        if (music != pkg) return
        cancelAutoPlay()
        val token = autoPlayToken
        autoPlaySendCount = 0
        autoPlayNetworkWaitStart = 0L
        val delayMs = settings.autoPlayDelaySec * 1_000L
        Log.i(TAG, "auto play: $pkg in ${delayMs}ms, waitNetwork=${settings.autoPlayWaitNetwork}")
        postAutoPlayStep(delayMs) { awaitNetworkOrPlay(token, pkg) }
    }

    /** Drops every waiting auto-play step; a step already running stops on its token. */
    private fun cancelAutoPlay() {
        autoPlayToken++
        for (step in autoPlaySteps) handler.removeCallbacks(step)
        autoPlaySteps.clear()
    }

    private fun postAutoPlayStep(delayMs: Long, step: () -> Unit) {
        val runnable = Runnable(step)
        autoPlaySteps.add(runnable)
        handler.postDelayed(runnable, delayMs)
    }

    /**
     * Waits for a working data connection when the user asked for it: polled once a second, at
     * most 25 s, then the player is tried anyway (a local file plays without a network and a
     * streaming player says so itself).
     */
    private fun awaitNetworkOrPlay(token: Int, pkg: String) {
        if (!autoPlayWanted(token, pkg)) return
        if (!settings.autoPlayWaitNetwork || hasDataConnection()) {
            sendAutoPlayKey(token, pkg)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (autoPlayNetworkWaitStart == 0L) autoPlayNetworkWaitStart = now
        val waited = now - autoPlayNetworkWaitStart
        if (waited >= AUTO_PLAY_NETWORK_TIMEOUT_MS) {
            Log.w(TAG, "auto play: still offline after ${waited}ms, trying $pkg anyway")
            sendAutoPlayKey(token, pkg)
            return
        }
        postAutoPlayStep(AUTO_PLAY_NETWORK_POLL_MS) { awaitNetworkOrPlay(token, pkg) }
    }

    /** Sends one PLAY key, then checks 1.5 s later whether the player really started. */
    private fun sendAutoPlayKey(token: Int, pkg: String) {
        if (!autoPlayWanted(token, pkg)) return
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audio.isMusicActive) {
            // A second key would toggle the song the player is already playing.
            Log.i(TAG, "auto play: music is already playing, leaving it alone")
            return
        }
        autoPlaySendCount++
        Log.i(TAG, "auto play: sending PLAY ($autoPlaySendCount) for $pkg")
        val keyDown = try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            true
        } catch (ignored: Exception) {
            // No audio service, or the platform refuses the key: never crash the launcher.
            false
        }
        if (!keyDown) return
        postAutoPlayStep(AUTO_PLAY_KEY_UP_MS) {
            try {
                audio.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                )
            } catch (ignored: Exception) {
                // The player went away between the two events; nothing to do.
            }
        }
        postAutoPlayStep(AUTO_PLAY_VERIFY_MS) { verifyAutoPlay(token, pkg, audio) }
    }

    private fun verifyAutoPlay(token: Int, pkg: String, audio: AudioManager) {
        if (!autoPlayWanted(token, pkg)) return
        if (audio.isMusicActive) {
            Log.i(TAG, "auto play: $pkg is playing")
            return
        }
        if (autoPlaySendCount >= MAX_AUTO_PLAY_ATTEMPTS) {
            Log.w(TAG, "auto play: no sound after $autoPlaySendCount keys, giving up")
            return
        }
        Log.i(TAG, "auto play: no sound yet, retrying in ${AUTO_PLAY_RETRY_MS}ms")
        postAutoPlayStep(AUTO_PLAY_RETRY_MS) { sendAutoPlayKey(token, pkg) }
    }

    /**
     * True while this request is still the one the user wants: same request, auto play still on,
     * same music app, and that app still placed in one of the panes (closing it stops the chain).
     */
    private fun autoPlayWanted(token: Int, pkg: String): Boolean {
        if (token != autoPlayToken) return false
        if (!settings.autoPlayMusic) return false
        if (settings.musicPackage != pkg) return false
        val single = fullscreen
        return leftPkg == pkg || rightPkg == pkg || (single != null && pkgOf(single) == pkg)
    }

    /**
     * True when Android reports a network that can carry internet traffic. The unit's LTE link
     * takes seconds to appear and `NET_CAPABILITY_VALIDATED` arrives later still, so INTERNET on
     * an existing network is what is asked for here — no captive-portal check.
     */
    private fun hasDataConnection(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return try {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (ignored: Exception) {
            // No ACCESS_NETWORK_STATE (an install from before v4.9): never block the player.
            true
        }
    }

    /** Sets (or clears) one pane and remembers it immediately for the next boot. */
    private fun applySide(side: Side, pkg: String?) {
        if (side == Side.LEFT) leftPkg = pkg else rightPkg = pkg
        settings.sessionLeft = leftPkg
        settings.sessionRight = rightPkg
        val current = engine
        if (pkg == null) {
            // v4.11: an emptied pane also ends the app that is on it (see the engine's clear), so a
            // navigation app cannot keep talking from a pane that looks empty. A pane that was in
            // fullscreen leaves it: an empty pane covering the other one is a dead end.
            if (fullscreen == side) setFullscreen(null)
            current?.clear(side)
        } else {
            launchApp(side, pkg)
        }
        updateEmptyStates()
        updatePresetHighlight()
        scheduleBounds(commit = true)
    }

    private fun applyDpi(side: Side, dpi: Int) {
        val value = dpi.coerceIn(Settings.MIN_DPI, Settings.MAX_DPI)
        if (side == Side.LEFT) settings.leftDpi = value else settings.rightDpi = value
        val current = engine
        if (current == null || !current.supportsDpi) {
            // A free-form window draws with the display's own density.
            toast(getString(R.string.pane_scale_system))
            return
        }
        current.setDensity(side, value)
        if (side == Side.LEFT) appliedLeftDpi = value else appliedRightDpi = value
        scheduleBounds(commit = true)
    }

    private fun swapPanes() {
        val oldLeft = leftPkg
        leftPkg = rightPkg
        rightPkg = oldLeft
        settings.sessionLeft = leftPkg
        settings.sessionRight = rightPkg
        updateEmptyStates()
        updatePresetHighlight()
        // v4.11: both panes are emptied before anything is launched again. Without that, the app
        // that leaves a pane stays on that pane's display behind the new one — a second navigation
        // voice — and the launch of the app that arrives would either duplicate it or take it away
        // from the pane it is still shown in. A swap therefore starts both apps again; what it
        // never does is run one of them twice (see the engine's launch/clear).
        engine?.clear(Side.LEFT)
        engine?.clear(Side.RIGHT)
        leftPkg?.let { launchApp(Side.LEFT, it) }
        val right = rightPkg
        if (right != null) {
            if (engine?.inline == true) {
                launchApp(Side.RIGHT, right)
            } else {
                handler.postDelayed({ launchApp(Side.RIGHT, right) }, PANE_GAP_MS)
            }
        }
        scheduleBounds(commit = true)
    }

    private fun updateEmptyStates() {
        updatePaneEmpty(
            binding.leftEmpty,
            binding.leftEmptyIcon,
            binding.leftEmptyTitle,
            binding.leftEmptyHint,
            leftPkg
        )
        updatePaneEmpty(
            binding.rightEmpty,
            binding.rightEmptyIcon,
            binding.rightEmptyTitle,
            binding.rightEmptyHint,
            rightPkg
        )
    }

    private fun updatePaneEmpty(
        container: View,
        icon: View,
        title: TextView,
        hint: TextView,
        pkg: String?
    ) {
        val inline = engine?.inline == true
        container.visibility = if (inline && pkg != null) View.GONE else View.VISIBLE
        // No "touch to open again" line any more: an empty pane shows one + 버튼, and a pane
        // that already has an app just names it (a long press opens its menu).
        hint.visibility = View.GONE
        if (pkg == null) {
            icon.visibility = View.VISIBLE
            title.text = getString(R.string.app_add)
            hint.text = ""
        } else {
            icon.visibility = View.GONE
            title.text = repo.labelOf(pkg)
            hint.text = ""
        }
    }

    // ----------------------------------------------------------------- engine

    private fun setupEngine() {
        engine?.release()
        lastBoundsKey = null
        engineMode = effectiveEngine()
        appliedLeftDpi = settings.leftDpi
        appliedRightDpi = settings.rightDpi
        engine = if (engineMode == Settings.ENGINE_VIRTUAL) {
            binding.leftSurface.visibility = View.VISIBLE
            binding.rightSurface.visibility = View.VISIBLE
            VirtualDisplayEngine(
                this,
                binding.leftSurface,
                binding.rightSurface,
                settings,
                onMessage = { message -> toast(message) },
                onUnavailable = { message -> handleEngineUnavailable(message) },
                touchSink = { displayId, event -> touchRouter.handle(displayId, event) },
                // v4.12: a ratio change restarts a pane's app on the new display; a music app the
                // user designated is started playing again, as it is on any other open.
                onAppRestarted = { _, pkg -> scheduleAutoPlay(pkg) }
            )
        } else {
            binding.leftSurface.visibility = View.GONE
            binding.rightSurface.visibility = View.GONE
            SystemSplitEngine(this) { message -> toast(message) }
        }
        warnIfVirtualNeedsTouch()
        scheduleBounds(commit = true)
    }

    /**
     * The engine the launcher may actually run right now.
     *
     * v3.4: the virtual-display engine is the one the user chose, but its panes can only be used
     * when the touches have a way into the displays — Shizuku (instant, multi-touch) or the
     * accessibility service (gesture replay, Android 11+). With neither, the free-form engine is
     * the safe fallback: those windows receive touch by themselves.
     */
    private fun effectiveEngine(): String {
        if (settings.engine != Settings.ENGINE_VIRTUAL) return Settings.ENGINE_SYSTEM
        if (virtualRefused) return Settings.ENGINE_SYSTEM
        return if (virtualTouchAvailable()) Settings.ENGINE_VIRTUAL else Settings.ENGINE_SYSTEM
    }

    private fun virtualTouchAvailable(): Boolean {
        // v4.0: the SystemUID build holds INJECT_EVENTS itself, so the panes are touchable with no
        // helper at all — CarCar Launcher's own situation.
        if (Privileges.canInjectInput(this)) return true
        if (LauncherAccessibilityService.isEnabled(this)) return true
        if (ShizukuBridge.isReady()) return true
        // Shizuku is running and this app is allowed: the shell-side touch service is still
        // binding, which takes a moment. Choosing the virtual engine now avoids starting every
        // pane twice (system first, virtual a second later).
        return ShizukuBridge.isRunning() && ShizukuBridge.isGranted()
    }

    private fun warnIfVirtualNeedsTouch() {
        if (engineHintShown) return
        if (settings.engine != Settings.ENGINE_VIRTUAL) return
        if (engineMode == Settings.ENGINE_VIRTUAL) return
        engineHintShown = true
        toast(getString(R.string.engine_virtual_needs_touch))
    }

    /**
     * Shizuku's binder can arrive after the screen is already up (or be granted in the settings
     * screen). When that happens the stored choice may now be runnable, so the engine is swapped
     * the same way a change made in the settings screen would be.
     */
    private fun syncEngineWithShizuku() {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        if (leftPanel.isOpen || rightPanel.isOpen) return
        if (effectiveEngine() == engineMode) return
        val left = leftPkg
        val right = rightPkg
        setupEngine()
        updateEmptyStates()
        left?.let { launchApp(Side.LEFT, it) }
        if (right != null) {
            if (engine?.inline == true) {
                launchApp(Side.RIGHT, right)
            } else {
                handler.postDelayed({ launchApp(Side.RIGHT, right) }, PANE_GAP_MS)
            }
        }
    }

    private fun handleEngineUnavailable(message: String) {
        toast(message)
        if (settings.engine != Settings.ENGINE_VIRTUAL) return
        // The stored choice stays: a display that is refused now may be allowed once Shizuku is
        // set up, so only this run is put on the free-form engine.
        virtualRefused = true
        val left = leftPkg
        val right = rightPkg
        setupEngine()
        updateEmptyStates()
        left?.let { launchApp(Side.LEFT, it) }
        if (right != null) {
            handler.postDelayed({ launchApp(Side.RIGHT, right) }, 300L)
        }
    }
    // ------------------------------------------------------------------ state

    private fun restoreSession() {
        if (settings.rememberSession) {
            leftPkg = repo.installedOrNull(settings.sessionLeft)
            rightPkg = repo.installedOrNull(settings.sessionRight)
        }
        updateEmptyStates()
    }

    /** After the intro (or immediately on a warm start): put the saved apps back. */
    private fun startSession() {
        if (sessionStarted) return
        sessionStarted = true
        if (settings.rememberSession) {
            if (leftPkg == null) leftPkg = repo.installedOrNull(settings.sessionLeft)
            if (rightPkg == null) rightPkg = repo.installedOrNull(settings.sessionRight)
        }
        // The panes only have their final size after the first layout pass; launching earlier put
        // the windows on screen once at their old size and made the start-up look unstable.
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            applyBounds(commit = false)
            leftPkg?.let { launchApp(Side.LEFT, it) }
            val right = rightPkg
            if (right != null) {
                if (engine?.inline == true) {
                    launchApp(Side.RIGHT, right)
                } else {
                    handler.postDelayed({ launchApp(Side.RIGHT, right) }, PANE_GAP_MS)
                }
            }
        }, SESSION_START_DELAY_MS)
        updateEmptyStates()
    }
    private fun handleIntent(intent: Intent?): Boolean {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: return false
        val side = if (intent.getStringExtra(EXTRA_SIDE) == "right") Side.RIGHT else Side.LEFT
        val coldStart = !sessionStarted
        if (coldStart) {
            sessionStarted = true
            // v4.11: a launcher that was killed while the app drawer was open comes back from the
            // drawer's intent alone. The pane the drawer did not name comes back from the
            // remembered session instead of staying empty for the rest of the drive.
            if (settings.rememberSession && pkgOf(opposite(side)) == null) {
                val other = if (side == Side.LEFT) repo.installedOrNull(settings.sessionRight)
                else repo.installedOrNull(settings.sessionLeft)
                if (side == Side.LEFT) rightPkg = other else leftPkg = other
            }
        }
        if (repo.installedOrNull(pkg) == null) {
            toast(getString(R.string.app_missing))
        } else {
            applySide(side, pkg)
        }
        intent.removeExtra(EXTRA_PACKAGE)
        intent.removeExtra(EXTRA_SIDE)
        if (coldStart) {
            updateEmptyStates()
            val otherSide = opposite(side)
            val other = pkgOf(otherSide)
            handler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                applyBounds(commit = false)
                if (other != null) {
                    if (engine?.inline == true) {
                        launchApp(otherSide, other)
                    } else {
                        handler.postDelayed({ launchApp(otherSide, other) }, PANE_GAP_MS)
                    }
                }
            }, SESSION_START_DELAY_MS)
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (dockHidden) setDockHidden(false, persist = false)
        if (!handleIntent(intent)) {
            updatePresetList()
            updateEmptyStates()
            if (settings.introEnabled && !settings.introSkipOnReturn && !introShowing) {
                playIntro()
            }
        }
        // v4.8: the home key brings the launcher back as a new intent as often as it resumes it.
        if (engine?.inline == true) scheduleRestore()
    }

    /**
     * A dialog, the keyboard or a focus change gives the system bars back: hide them again the
     * moment this screen owns the focus, so no status or navigation bar stays over the panes.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersive()
            // v4.8: a covering window that leaves without stopping this activity (an overlay, a
            // dialog of the car's own UI) only hands the focus back — that is a return, too, and
            // the panes are re-checked for it.
            if (sessionStarted && engine?.inline == true) scheduleRestore()
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        applyTheme()
        ShizukuBridge.refresh()
        if (appliedUiScale != 0 && appliedUiScale != settings.uiScale) {
            // The launcher UI size changed in the settings screen: rebuild this screen with the
            // new density so every pane keeps its exact rectangle.
            appliedUiScale = settings.uiScale
            recreate()
            return
        }

        if (engineMode.isNotEmpty() && engineMode != effectiveEngine()) {
            val left = leftPkg
            val right = rightPkg
            setupEngine()
            updateEmptyStates()
            left?.let { launchApp(Side.LEFT, it) }
            if (right != null) handler.postDelayed({ launchApp(Side.RIGHT, right) }, PANE_GAP_MS)
        } else {
            val current = engine
            if (current != null && current.supportsDpi) {
                if (appliedLeftDpi != settings.leftDpi) {
                    appliedLeftDpi = settings.leftDpi
                    current.setDensity(Side.LEFT, settings.leftDpi)
                }
                if (appliedRightDpi != settings.rightDpi) {
                    appliedRightDpi = settings.rightDpi
                    current.setDensity(Side.RIGHT, settings.rightDpi)
                }
            }
        }

        if (fullscreen == null && settings.ratio != ratio) {
            binding.split.ratio = settings.ratio
            ratio = settings.ratio
        }
        if (!settings.dockAutoHide && dockHidden && !settings.dockHidden) {
            setDockHidden(false, persist = false)
        }
        applyDockSide()
        updatePresetList()
        updateEmptyStates()
        scheduleBounds(commit = true)

        // Only a return from the settings screen or the app drawer puts the two apps back into
        // their panes (the opaque launcher window was covering them). Any other resume — most of
        // all the user closing a floating window with its × button — must not start that app
        // again, or closing it would reopen it forever (v3.2).
        //
        // v4.8: that guard only makes sense for the free-form engine. The inline (virtual display)
        // engine has no window to close: when another window — the reverse camera, any app, the
        // car's own menu, the home screen — covers this one, the panes lose their surfaces and
        // come back black ("다른 화면에서 다시 복귀하면 다 그런것같아"), so every resume asks the
        // engine to look at the panes. It is the engine's own task check that decides whether an
        // app is started again, so a pane that is still drawing is left alone.
        suppressAutoRestore = false
        val inline = engine?.inline == true
        if (resumeFromOwnScreen || inline) {
            resumeFromOwnScreen = false
            scheduleRestore()
        } else {
            handler.removeCallbacks(restoreRunnable)
        }
    }

    /**
     * Posts [restoreRunnable] once the window is really back in front (v4.8).
     *
     * Every way a return from another window can arrive on this firmware goes through here:
     * `onResume`, a new launch intent (`onNewIntent` — the home key) and a regained window focus
     * (`onWindowFocusChanged`). Whichever of them the unit delivers, the panes are re-checked.
     */
    private fun scheduleRestore(delayMs: Long = RESTORE_DELAY_MS) {
        if (!sessionStarted) return
        handler.removeCallbacks(restoreRunnable)
        handler.postDelayed(restoreRunnable, delayMs)
    }

    override fun onPause() {
        super.onPause()
        closePanePanels()
        handler.removeCallbacks(restoreRunnable)
    }

    override fun onDestroy() {
        cancelAutoPlay()
        handler.removeCallbacksAndMessages(null)
        ShizukuBridge.removeListener(shizukuStateListener)
        binding.introView.onFinished = null
        binding.introView.stop()
        binding.introVideo.release()
        binding.introImage.setImageDrawable(null)
        engine?.release()
        engine = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ intro

    private fun playIntro() {
        if (introShowing) return
        // v4.0: no logo of our own on the boot screen. A video or picture the user chose is the
        // boot logo; without one (and without the explicit built-in M animation) the launcher
        // goes straight to the panes — the eagle-like splash screen of the original launcher is
        // not reproduced anywhere.
        val uriValue = settings.introVideoUri?.let {
            try {
                Uri.parse(it)
            } catch (e: Exception) {
                null
            }
        }
        if (uriValue == null && !settings.introBuiltin) {
            startSession()
            return
        }
        introShowing = true
        binding.introLayer.visibility = View.VISIBLE
        binding.introLayer.alpha = 1f
        binding.introLayer.setBackgroundColor(Color.BLACK)
        binding.introLayer.setOnClickListener { finishIntro() }
        binding.introLayer.setOnLongClickListener { finishIntro(); true }

        when {
            uriValue != null && isImageIntro(uriValue) -> playImageIntro(uriValue)
            uriValue != null -> playVideoIntro(uriValue.toString())
            else -> playAnimatedIntro()
        }
    }

    /**
     * True when the chosen intro file is a picture. The MIME type stored at pick time is used
     * first; an installation that chose a video before v3.2 has none, so the file name decides.
     */
    private fun isImageIntro(uri: Uri): Boolean {
        val stored = settings.introMediaType
        if (stored != null) return stored.startsWith("image")
        val type = try {
            contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        if (type != null) return type.startsWith("image")
        val name = (uri.lastPathSegment ?: uri.toString()).lowercase()
        return IMAGE_SUFFIXES.any { name.endsWith(".$it") }
    }

    private fun playAnimatedIntro() {
        binding.introImage.visibility = View.GONE
        binding.introImage.setImageDrawable(null)
        binding.introVideo.visibility = View.GONE
        binding.introView.visibility = View.VISIBLE
        binding.introView.onFinished = { finishIntro() }
        binding.introView.start()
    }

    /**
     * A picture intro: shown whole in the middle of the black screen (no cropping) and finished by
     * itself after about 2.5 s. A touch anywhere skips it at once, and no hint is drawn.
     */
    private fun playImageIntro(uri: Uri) {
        binding.introView.onFinished = null
        binding.introView.stop()
        binding.introView.visibility = View.GONE
        binding.introVideo.stopPlayback()
        binding.introVideo.release()
        binding.introVideo.visibility = View.GONE
        binding.introImage.setImageDrawable(null)
        binding.introImage.visibility = View.VISIBLE
        loadIntroImage(uri)
        handler.removeCallbacks(introImageFinishRunnable)
        handler.postDelayed(introImageFinishRunnable, INTRO_IMAGE_MS)
    }

    /**
     * Decodes the chosen picture in the background at the size of the screen, so a full-size phone
     * photo neither blocks the first frame nor exhausts the heap of a head unit. A file that
     * cannot be decoded falls back to the built-in M animation instead of leaving a black screen.
     */
    private fun loadIntroImage(uri: Uri) {
        val targetW = binding.introImage.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val targetH = binding.introImage.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        Thread {
            val bitmap = try {
                decodeScaled(uri, targetW, targetH)
            } catch (e: Exception) {
                null
            } catch (o: OutOfMemoryError) {
                null
            }
            handler.post {
                if (!introShowing || isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    handler.removeCallbacks(introImageFinishRunnable)
                    // v4.0: a broken picture is not replaced by a logo of the launcher; the
                    // boot screen simply ends and the panes come up.
                    if (settings.introBuiltin) restartAnimatedIntro() else finishIntro()
                } else {
                    binding.introImage.setImageBitmap(bitmap)
                }
            }
        }.start()
    }

    private fun decodeScaled(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth ||
            bounds.outHeight / (sample * 2) >= maxHeight
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private val introImageFinishRunnable = Runnable { finishIntro() }

    private fun playVideoIntro(uri: String) {
        binding.introView.stop()
        binding.introView.visibility = View.GONE
        val video = binding.introVideo
        video.visibility = View.VISIBLE
        video.onFinished = { finishIntro() }
        // v4.0: a clip that cannot be played ends the boot screen instead of flashing a logo.
        video.onFailed = { if (settings.introBuiltin) restartAnimatedIntro() else finishIntro() }
        try {
            video.play(Uri.parse(uri))
        } catch (e: Exception) {
            if (settings.introBuiltin) restartAnimatedIntro() else finishIntro()
        }
    }

    private fun restartAnimatedIntro() {
        binding.introVideo.visibility = View.GONE
        binding.introVideo.release()
        binding.introImage.visibility = View.GONE
        binding.introImage.setImageDrawable(null)
        binding.introView.visibility = View.VISIBLE
        binding.introView.onFinished = { finishIntro() }
        binding.introView.start()
    }

    private fun finishIntro() {
        if (!introShowing) return
        introShowing = false
        handler.removeCallbacks(introImageFinishRunnable)
        binding.introView.onFinished = null
        binding.introView.stop()
        binding.introVideo.stopPlayback()
        binding.introVideo.release()
        binding.introVideo.visibility = View.GONE
        binding.introImage.visibility = View.GONE
        binding.introImage.setImageDrawable(null)
        binding.introLayer.animate()
            .alpha(0f)
            .setDuration(260L)
            .withEndAction {
                binding.introLayer.visibility = View.GONE
                binding.introLayer.alpha = 1f
                if (!isFinishing && !isDestroyed) {
                    startSession()
                    scheduleBounds(commit = true)
                }
            }
            .start()
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_SIDE = "extra_side"
        private const val TAG = "MNLauncher"
        private const val RESTORE_DELAY_MS = 280L
        private const val SESSION_START_DELAY_MS = 140L

        /** v4.9 auto play: the key goes DOWN, UP 60 ms later, checked at 1.5 s. */
        private const val AUTO_PLAY_KEY_UP_MS = 60L
        private const val AUTO_PLAY_VERIFY_MS = 1_500L

        /** v4.9: while the player stays silent, try again every 2 s; 1 key + 3 retries. */
        private const val AUTO_PLAY_RETRY_MS = 2_000L
        private const val MAX_AUTO_PLAY_ATTEMPTS = 4

        /** v4.9: data connection polling — once a second, 25 s at most. */
        private const val AUTO_PLAY_NETWORK_POLL_MS = 1_000L
        private const val AUTO_PLAY_NETWORK_TIMEOUT_MS = 25_000L

        /** A picture intro stays about this long unless it is touched (v3.2). */
        private const val INTRO_IMAGE_MS = 2500L

        private val IMAGE_SUFFIXES = listOf(
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif"
        )

        /** Gap between the dock and a floating window, for its border/shadow (v3.1). */
        private const val DOCK_GAP_DP = 8

        /** v4.0: height kept free below a free-form window so the pane handle stays reachable. */
        private const val OVERLAY_HANDLE_DP = 16

        /** A window smaller than this is not placed at all. */
        private const val MIN_PANE_DP = 48

        /** Gap between starting the two panes: one launch each, never in the same frame. */
        private const val PANE_GAP_MS = 350L
    }
}
