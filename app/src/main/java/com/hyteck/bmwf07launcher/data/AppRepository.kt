package com.hyteck.bmwf07launcher.data

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Installed-app lookup with the Korean / global package names used by the six default presets.
 */
class AppRepository(private val context: Context) {

    private val cachedApps: List<AppInfo> by lazy { loadLaunchableApps() }

    val apps: List<AppInfo>
        get() = cachedApps

    private fun loadLaunchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val result = ArrayList<AppInfo>()
        for (info in resolved) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == context.packageName) continue
            val label = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            val icon = try {
                info.loadIcon(pm)
            } catch (e: Exception) {
                null
            }
            result.add(AppInfo(pkg, label, icon))
        }
        return result
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Resolves a preset slot key ("nmap", "tmap", "flo", "qq", "revanced") to an installed app. */
    fun resolveKey(key: String): AppInfo? = when (key) {
        "nmap" -> exact("com.nhn.android.nmap")
        "tmap" -> exact("com.skt.tmap.ku") ?: exact("com.skt.tmap")
        "flo" -> exact("skplanet.musicmate")
        "qq" -> exact("com.tencent.qqmusic") ?: firstContaining("qqmusic")
        "revanced" -> exact("app.revanced.android.youtube")
            ?: firstContaining("revanced")
            ?: firstContaining("youtube")
        else -> null
    }

    private fun exact(pkg: String): AppInfo? = cachedApps.firstOrNull { it.packageName == pkg }

    private fun firstContaining(text: String): AppInfo? =
        cachedApps.firstOrNull { it.packageName.contains(text, ignoreCase = true) }

    fun fallbackLabel(key: String): String = when (key) {
        "nmap" -> "네이버지도"
        "tmap" -> "티맵"
        "flo" -> "FLO"
        "qq" -> "QQ MUSIC"
        "revanced" -> "ReVanced"
        else -> key
    }

    fun labelOf(pkg: String?): String {
        if (pkg == null) return "없음"
        return cachedApps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    }

    fun iconOf(pkg: String?): Drawable? {
        if (pkg == null) return null
        return cachedApps.firstOrNull { it.packageName == pkg }?.icon
    }

    fun launchIntent(pkg: String): android.content.Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)
}
