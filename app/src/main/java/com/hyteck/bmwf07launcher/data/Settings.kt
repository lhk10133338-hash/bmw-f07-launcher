package com.hyteck.bmwf07launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved split combination: two app slots plus the ratio they open with.
 * Presets live in the dock (CarCar style) and are created from the running screen.
 */
data class Preset(
    val name: String,
    val left: String?,
    val right: String?,
    val ratio: Int
) {
    fun isEmpty(): Boolean = left == null && right == null
}

/**
 * All persisted launcher state.
 *
 * Every write goes through [commit] instead of [android.content.SharedPreferences.Editor.apply]:
 * a head unit can lose power the instant the ignition goes off, and an asynchronous write may
 * not have reached the disk by then. The data is tiny, so the synchronous write costs nothing.
 */
class Settings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun write(block: (android.content.SharedPreferences.Editor) -> Unit) {
        val editor = prefs.edit()
        block(editor)
        editor.commit()
    }

    private fun putString(key: String, value: String?) {
        write { editor ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
    }

    var engine: String
        get() = prefs.getString(KEY_ENGINE, ENGINE_SYSTEM) ?: ENGINE_SYSTEM
        set(value) = putString(KEY_ENGINE, value)

    /** Left pane width percentage, 20..80. */
    var ratio: Int
        get() = prefs.getInt(KEY_RATIO, 50).coerceIn(20, 80)
        set(value) = write { it.putInt(KEY_RATIO, value.coerceIn(20, 80)) }

    var leftDpi: Int
        get() = prefs.getInt(KEY_LEFT_DPI, DEFAULT_DPI).coerceIn(MIN_DPI, MAX_DPI)
        set(value) = write { it.putInt(KEY_LEFT_DPI, value.coerceIn(MIN_DPI, MAX_DPI)) }

    var rightDpi: Int
        get() = prefs.getInt(KEY_RIGHT_DPI, DEFAULT_DPI).coerceIn(MIN_DPI, MAX_DPI)
        set(value) = write { it.putInt(KEY_RIGHT_DPI, value.coerceIn(MIN_DPI, MAX_DPI)) }

    /**
     * Size of the launcher's own UI (settings, app picker, app drawer) in percent.
     * 100 uses the device density; lower values fit more on a 2560×720 screen.
     */
    var uiScale: Int
        get() = prefs.getInt(KEY_UI_SCALE, 100).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
        set(value) = write { it.putInt(KEY_UI_SCALE, value.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)) }

    /** Left/right apps of the running session, restored after a reboot when [rememberSession]. */
    var sessionLeft: String?
        get() = prefs.getString(KEY_SESSION_LEFT, null)
        set(value) = putString(KEY_SESSION_LEFT, value)

    var sessionRight: String?
        get() = prefs.getString(KEY_SESSION_RIGHT, null)
        set(value) = putString(KEY_SESSION_RIGHT, value)

    var rememberSession: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_SESSION, true)
        set(value) = write { it.putBoolean(KEY_REMEMBER_SESSION, value) }

    var bootAutostart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTOSTART, true)
        set(value) = write { it.putBoolean(KEY_BOOT_AUTOSTART, value) }

    var dockAtLeft: Boolean
        get() = prefs.getBoolean(KEY_DOCK_LEFT, true)
        set(value) = write { it.putBoolean(KEY_DOCK_LEFT, value) }

    var dockAutoHide: Boolean
        get() = prefs.getBoolean(KEY_DOCK_AUTO_HIDE, false)
        set(value) = write { it.putBoolean(KEY_DOCK_AUTO_HIDE, value) }

    var dockHidden: Boolean
        get() = prefs.getBoolean(KEY_DOCK_HIDDEN, false)
        set(value) = write { it.putBoolean(KEY_DOCK_HIDDEN, value) }

    var introEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTRO, true)
        set(value) = write { it.putBoolean(KEY_INTRO, value) }

    var introSkipOnReturn: Boolean
        get() = prefs.getBoolean(KEY_INTRO_SKIP_RETURN, true)
        set(value) = write { it.putBoolean(KEY_INTRO_SKIP_RETURN, value) }

    var introVideoUri: String?
        get() = prefs.getString(KEY_INTRO_URI, null)
        set(value) = putString(KEY_INTRO_URI, value)

    var lastPreset: Int
        get() = prefs.getInt(KEY_LAST_PRESET, -1)
        set(value) = write { it.putInt(KEY_LAST_PRESET, value) }

    val hiddenPackages: MutableSet<String>
        get() = (prefs.getStringSet(KEY_HIDDEN, null) ?: emptySet()).toMutableSet()

    fun addHidden(pkg: String) {
        val set = hiddenPackages
        set.add(pkg)
        write { it.putStringSet(KEY_HIDDEN, set) }
    }

    fun removeHidden(pkg: String) {
        val set = hiddenPackages
        set.remove(pkg)
        write { it.putStringSet(KEY_HIDDEN, set) }
    }

    /** Apps pinned to the top of the app drawer. */
    val favorites: MutableSet<String>
        get() = (prefs.getStringSet(KEY_FAVORITES, null) ?: emptySet()).toMutableSet()

    fun toggleFavorite(pkg: String): Boolean {
        val set = favorites
        val added = if (set.contains(pkg)) {
            set.remove(pkg)
            false
        } else {
            set.add(pkg)
            true
        }
        write { it.putStringSet(KEY_FAVORITES, set) }
        return added
    }

    // --------------------------------------------------------------- presets

    fun presets(): MutableList<Preset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return ArrayList()
        val result = ArrayList<Preset>()
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result.add(
                    Preset(
                        name = item.optString("name", ""),
                        left = if (item.isNull("left")) null else item.optString("left", null),
                        right = if (item.isNull("right")) null else item.optString("right", null),
                        ratio = item.optInt("ratio", 50).coerceIn(20, 80)
                    )
                )
            }
        } catch (ignored: Exception) {
            return ArrayList()
        }
        return result
    }

    fun savePresets(list: List<Preset>) {
        val array = JSONArray()
        for (preset in list.take(MAX_PRESETS)) {
            val item = JSONObject()
            item.put("name", preset.name)
            item.put("left", preset.left ?: JSONObject.NULL)
            item.put("right", preset.right ?: JSONObject.NULL)
            item.put("ratio", preset.ratio.coerceIn(20, 80))
            array.put(item)
        }
        putString(KEY_PRESETS, array.toString())
    }

    fun addPreset(preset: Preset): Boolean {
        val list = presets()
        if (list.size >= MAX_PRESETS) return false
        if (list.any { it.left == preset.left && it.right == preset.right && it.ratio == preset.ratio }) {
            return false
        }
        list.add(preset)
        savePresets(list)
        return true
    }

    fun removePreset(index: Int) {
        val list = presets()
        if (index in list.indices) {
            list.removeAt(index)
            savePresets(list)
        }
    }

    fun movePreset(from: Int, to: Int) {
        val list = presets()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        savePresets(list)
    }

    fun updatePreset(index: Int, transform: (Preset) -> Preset) {
        val list = presets()
        if (index !in list.indices) return
        list[index] = transform(list[index])
        savePresets(list)
    }

    fun hasPresets(): Boolean = presets().isNotEmpty()

    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_VIRTUAL = "virtual"

        const val MAX_PRESETS = 12
        const val MIN_DPI = 120
        const val MAX_DPI = 360

        /** Default per-pane app scale: 200 dpi keeps a 2560×720 split readable, not huge. */
        const val DEFAULT_DPI = 200

        /** Launcher UI scale bounds in percent (see [uiScale]). */
        const val MIN_UI_SCALE = 65
        const val MAX_UI_SCALE = 130
        const val UI_SCALE_STEP = 5

        private const val PREFS_NAME = "bmw_launcher_settings"
        private const val KEY_ENGINE = "engine"
        private const val KEY_RATIO = "ratio"
        private const val KEY_LEFT_DPI = "left_dpi"
        private const val KEY_RIGHT_DPI = "right_dpi"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val KEY_DOCK_LEFT = "dock_left"
        private const val KEY_DOCK_AUTO_HIDE = "dock_auto_hide"
        private const val KEY_DOCK_HIDDEN = "dock_hidden"
        private const val KEY_INTRO = "intro_enabled"
        private const val KEY_INTRO_SKIP_RETURN = "intro_skip_return"
        private const val KEY_INTRO_URI = "intro_uri"
        private const val KEY_LAST_PRESET = "last_preset"
        private const val KEY_HIDDEN = "hidden_packages"
        private const val KEY_FAVORITES = "favorite_packages"
        private const val KEY_PRESETS = "split_presets_v2"
        private const val KEY_REMEMBER_SESSION = "remember_session"
        private const val KEY_BOOT_AUTOSTART = "boot_autostart"
        private const val KEY_SESSION_LEFT = "session_left"
        private const val KEY_SESSION_RIGHT = "session_right"

        /**
         * The six combinations the user asked for on 2026-11-24, imported with one tap
         * from the settings screen (the dock starts empty, as in CarCar Launcher).
         * Keys are resolved to installed packages by [AppRepository.resolveKey].
         */
        val BMW_PRESETS = listOf(
            Triple("nmap", "flo", "네이버지도 + FLO"),
            Triple("nmap", "qq", "네이버지도 + QQ MUSIC"),
            Triple("nmap", "revanced", "네이버지도 + ReVanced"),
            Triple("tmap", "flo", "티맵 + FLO"),
            Triple("tmap", "qq", "티맵 + QQ MUSIC"),
            Triple("tmap", "revanced", "티맵 + ReVanced")
        )
    }
}
