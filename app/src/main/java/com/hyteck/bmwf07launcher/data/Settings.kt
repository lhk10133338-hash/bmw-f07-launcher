package com.hyteck.bmwf07launcher.data

import android.content.Context

/**
 * All persisted launcher state: engine, split ratio, per-pane DPI, six presets,
 * intro options, dock options and the last session.
 */
class Settings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var engine: String
        get() = prefs.getString(KEY_ENGINE, ENGINE_SYSTEM) ?: ENGINE_SYSTEM
        set(value) {
            prefs.edit().putString(KEY_ENGINE, value).apply()
        }

    /** Left pane width percentage, 20..80. */
    var ratio: Int
        get() = prefs.getInt(KEY_RATIO, 50).coerceIn(20, 80)
        set(value) {
            prefs.edit().putInt(KEY_RATIO, value.coerceIn(20, 80)).apply()
        }

    var leftDpi: Int
        get() = prefs.getInt(KEY_LEFT_DPI, 240).coerceIn(120, 480)
        set(value) {
            prefs.edit().putInt(KEY_LEFT_DPI, value.coerceIn(120, 480)).apply()
        }

    var rightDpi: Int
        get() = prefs.getInt(KEY_RIGHT_DPI, 240).coerceIn(120, 480)
        set(value) {
            prefs.edit().putInt(KEY_RIGHT_DPI, value.coerceIn(120, 480)).apply()
        }

    var dockAtLeft: Boolean
        get() = prefs.getBoolean(KEY_DOCK_LEFT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DOCK_LEFT, value).apply()
        }

    var dockAutoHide: Boolean
        get() = prefs.getBoolean(KEY_DOCK_AUTO_HIDE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DOCK_AUTO_HIDE, value).apply()
        }

    var introEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTRO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_INTRO, value).apply()
        }

    var introSkipOnReturn: Boolean
        get() = prefs.getBoolean(KEY_INTRO_SKIP_RETURN, true)
        set(value) {
            prefs.edit().putBoolean(KEY_INTRO_SKIP_RETURN, value).apply()
        }

    var introVideoUri: String?
        get() = prefs.getString(KEY_INTRO_URI, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_INTRO_URI) else editor.putString(KEY_INTRO_URI, value)
            editor.apply()
        }

    var lastPreset: Int
        get() = prefs.getInt(KEY_LAST_PRESET, 0).coerceIn(0, 5)
        set(value) {
            prefs.edit().putInt(KEY_LAST_PRESET, value.coerceIn(0, 5)).apply()
        }

    var lastLeftPkg: String?
        get() = prefs.getString(KEY_LAST_LEFT, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_LEFT, value).apply()
        }

    var lastRightPkg: String?
        get() = prefs.getString(KEY_LAST_RIGHT, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_RIGHT, value).apply()
        }

    val hiddenPackages: MutableSet<String>
        get() = (prefs.getStringSet(KEY_HIDDEN, null) ?: emptySet()).toMutableSet()

    fun addHidden(pkg: String) {
        val set = hiddenPackages
        set.add(pkg)
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun removeHidden(pkg: String) {
        val set = hiddenPackages
        set.remove(pkg)
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun presetLeft(index: Int): String? = prefs.getString("preset_${index}_left", null)

    fun presetRight(index: Int): String? = prefs.getString("preset_${index}_right", null)

    fun setPresetSide(index: Int, left: Boolean, pkg: String?) {
        val key = if (left) "preset_${index}_left" else "preset_${index}_right"
        val editor = prefs.edit()
        if (pkg == null) editor.remove(key) else editor.putString(key, pkg)
        editor.apply()
    }

    fun clearPreset(index: Int) {
        prefs.edit()
            .remove("preset_${index}_left")
            .remove("preset_${index}_right")
            .apply()
    }

    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_VIRTUAL = "virtual"

        private const val PREFS_NAME = "bmw_launcher_settings"
        private const val KEY_ENGINE = "engine"
        private const val KEY_RATIO = "ratio"
        private const val KEY_LEFT_DPI = "left_dpi"
        private const val KEY_RIGHT_DPI = "right_dpi"
        private const val KEY_DOCK_LEFT = "dock_left"
        private const val KEY_DOCK_AUTO_HIDE = "dock_auto_hide"
        private const val KEY_INTRO = "intro_enabled"
        private const val KEY_INTRO_SKIP_RETURN = "intro_skip_return"
        private const val KEY_INTRO_URI = "intro_uri"
        private const val KEY_LAST_PRESET = "last_preset"
        private const val KEY_LAST_LEFT = "last_left"
        private const val KEY_LAST_RIGHT = "last_right"
        private const val KEY_HIDDEN = "hidden_packages"

        val DEFAULT_LEFT = listOf("nmap", "nmap", "nmap", "tmap", "tmap", "tmap")
        val DEFAULT_RIGHT = listOf("flo", "qq", "revanced", "flo", "qq", "revanced")
        val PRESET_TITLES = listOf(
            "네이버지도 + FLO",
            "네이버지도 + QQ MUSIC",
            "네이버지도 + ReVanced",
            "티맵 + FLO",
            "티맵 + QQ MUSIC",
            "티맵 + ReVanced"
        )
    }
}
