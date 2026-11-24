package com.hyteck.bmwf07launcher

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.hyteck.bmwf07launcher.engine.Side
import com.hyteck.bmwf07launcher.engine.SplitEngine
import com.hyteck.bmwf07launcher.engine.SystemSplitEngine
import com.hyteck.bmwf07launcher.engine.VirtualDisplayEngine
import com.hyteck.bmwf07launcher.ui.PanePanel
import com.hyteck.bmwf07launcher.ui.RatioMenu
import com.hyteck.bmwf07launcher.util.applyImmersive
import com.hyteck.bmwf07launcher.util.dp
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

    /** Last rectangles handed to the engine, so identical layout passes are not repeated. */
    private var lastBoundsKey: String? = null

    /** Each pane owns an inline menu overlay; neither one is a separate window any more. */
    private lateinit var leftPanel: PanePanel
    private lateinit var rightPanel: PanePanel

    /** Frames the launcher has been resumed; the first one belongs to the screen coming up. */
    private var resumeCount = 0
    private var appliedUiScale = 0

    /**
     * True while the settings screen or the app drawer is being opened: the restore of the two
     * free-form windows must not compete with that screen's transition.
     */
    private var suppressAutoRestore = false

    private val autoPlayRunnable = Runnable {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            handler.postDelayed({
                try {
                    audio.dispatchMediaKeyEvent(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
                    )
                } catch (ignored: Exception) {
                    // The player went away between the two events; nothing to do.
                }
            }, 60L)
        } catch (ignored: Exception) {
            // No audio service, or the platform refuses the key: never crash the launcher.
        }
    }

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
        repo = AppRepository(this)
        apps = repo.apps
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
        if (settings.engineMigratedV3) return
        if (settings.engine != Settings.ENGINE_SYSTEM) {
            settings.engine = Settings.ENGINE_SYSTEM
        }
        settings.engineMigratedV3 = true
    }

    private fun setupDock() {
        binding.dockDrawer.setOnClickListener { openDrawer() }
        binding.dockSettings.setOnClickListener { openSettings() }
        binding.dockSave.setOnClickListener { saveCurrentPreset() }
        binding.dockHide.setOnClickListener { setDockHidden(true, persist = true) }
        binding.dockEdge.setOnClickListener { setDockHidden(false, persist = true) }
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
        handler.removeCallbacks(restoreRunnable)
        startActivity(Intent(this, SettingsActivity::class.java))
        noTransition()
    }

    private fun openDrawer() {
        suppressAutoRestore = true
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
        binding.dock.visibility = if (hidden) View.GONE else View.VISIBLE
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
        val name = "${repo.labelOf(leftPkg)} + ${repo.labelOf(rightPkg)}"
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
        // inline menu: change app · remove app · scale · fullscreen.
        binding.leftEmpty.setOnLongClickListener {
            openPaneMenu(Side.LEFT)
            true
        }
        binding.rightEmpty.setOnLongClickListener {
            openPaneMenu(Side.RIGHT)
            true
        }

        binding.leftHandle.setOnClickListener { openPaneMenu(Side.LEFT) }
        binding.leftHandle.setOnLongClickListener {
            openPaneMenu(Side.LEFT)
            true
        }
        binding.leftHandle.onSwipeUp = { openPaneMenu(Side.LEFT) }

        binding.rightHandle.setOnClickListener { openPaneMenu(Side.RIGHT) }
        binding.rightHandle.setOnLongClickListener {
            openPaneMenu(Side.RIGHT)
            true
        }
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
        fullscreen = side
        binding.split.fullscreen = side
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
        // would lose their first tap to the window above them.
        val dock = dockRectOnScreen()
        if (dock != null) {
            val margin = dp(2)
            if (settings.dockAtLeft) {
                if (left.left < dock.right + margin) left.left = dock.right + margin
            } else if (right.right > dock.left - margin) {
                right.right = dock.left - margin
            }
        }
        // Layout callbacks, the divider and onResume all report the same rectangles; hand them
        // to the engine once so the windows are not re-placed (and the screen does not jump).
        val key = "$commit|${left.toShortString()}|${right.toShortString()}"
        if (key == lastBoundsKey) return
        lastBoundsKey = key
        current.applyBounds(left, right, commit || current.inline)
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
     * Music players opened from a launcher often come up paused, so a media PLAY key is sent
     * once the app has had time to show its player (see the user request of 2026-11-24).
     */
    private fun scheduleAutoPlay(pkg: String) {
        if (!settings.autoPlayMusic) return
        val music = settings.musicPackage ?: return
        if (music != pkg) return
        handler.removeCallbacks(autoPlayRunnable)
        handler.postDelayed(autoPlayRunnable, AUTO_PLAY_DELAY_MS)
    }

    /** Sets (or clears) one pane and remembers it immediately for the next boot. */
    private fun applySide(side: Side, pkg: String?) {
        if (side == Side.LEFT) leftPkg = pkg else rightPkg = pkg
        settings.sessionLeft = leftPkg
        settings.sessionRight = rightPkg
        val current = engine
        if (pkg == null) {
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
        engine?.clear(Side.LEFT)
        engine?.clear(Side.RIGHT)
        leftPkg?.let { launchApp(Side.LEFT, it) }
        val right = rightPkg
        if (right != null) {
            handler.postDelayed({ launchApp(Side.RIGHT, right) }, PANE_GAP_MS)
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
                settings,
                onMessage = { message -> toast(message) },
                onUnavailable = { message -> handleEngineUnavailable(message) }
            )
        } else {
            binding.leftSurface.visibility = View.GONE
            binding.rightSurface.visibility = View.GONE
            SystemSplitEngine(this) { message -> toast(message) }
        }
        scheduleBounds(commit = true)
    }

    private fun handleEngineUnavailable(message: String) {
        toast(message)
        if (settings.engine != Settings.ENGINE_VIRTUAL) return
        settings.engine = Settings.ENGINE_SYSTEM
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
        if (!sessionStarted) {
            sessionStarted = true
        }
        if (repo.installedOrNull(pkg) == null) {
            toast(getString(R.string.app_missing))
        } else {
            applySide(side, pkg)
        }
        intent.removeExtra(EXTRA_PACKAGE)
        intent.removeExtra(EXTRA_SIDE)
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
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()

        if (appliedUiScale != 0 && appliedUiScale != settings.uiScale) {
            // The launcher UI size changed in the settings screen: rebuild this screen with the
            // new density so every pane keeps its exact rectangle.
            appliedUiScale = settings.uiScale
            recreate()
            return
        }

        if (engineMode.isNotEmpty() && engineMode != settings.engine) {
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

        // Coming back from the settings screen, the app drawer or any other screen: put the two
        // apps back into their panes (the opaque launcher window was covering them).
        if (resumeCount > 0) {
            suppressAutoRestore = false
            handler.removeCallbacks(restoreRunnable)
            handler.postDelayed(restoreRunnable, RESTORE_DELAY_MS)
        }
        resumeCount++
    }

    override fun onPause() {
        super.onPause()
        closePanePanels()
        handler.removeCallbacks(restoreRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.introView.onFinished = null
        binding.introView.stop()
        binding.introVideo.release()
        engine?.release()
        engine = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ intro

    private fun playIntro() {
        if (introShowing) return
        introShowing = true
        binding.introLayer.visibility = View.VISIBLE
        binding.introLayer.alpha = 1f
        binding.introLayer.setOnClickListener { finishIntro() }

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
        video.onFinished = { finishIntro() }
        video.onFailed = { restartAnimatedIntro() }
        try {
            video.play(Uri.parse(uri))
        } catch (e: Exception) {
            restartAnimatedIntro()
        }
    }

    private fun restartAnimatedIntro() {
        binding.introVideo.visibility = View.GONE
        binding.introVideo.release()
        binding.introView.visibility = View.VISIBLE
        binding.introView.onFinished = { finishIntro() }
        binding.introView.start()
    }

    private fun finishIntro() {
        if (!introShowing) return
        introShowing = false
        binding.introView.onFinished = null
        binding.introView.stop()
        binding.introVideo.stopPlayback()
        binding.introVideo.release()
        binding.introVideo.visibility = View.GONE
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
        private const val RESTORE_DELAY_MS = 280L
        private const val AUTO_PLAY_DELAY_MS = 1500L
        private const val SESSION_START_DELAY_MS = 140L

        /** Gap between starting the two panes: one launch each, never in the same frame. */
        private const val PANE_GAP_MS = 350L
    }
}
