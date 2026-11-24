package com.hyteck.bmwf07launcher.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import com.hyteck.bmwf07launcher.data.Settings
import kotlin.math.roundToInt

/**
 * Base activity for every screen of the launcher.
 *
 * On a 2560×720 head unit (and in LDPlayer) the default density makes text and icons in the
 * settings screen, the app picker and the app drawer too large to see at a glance, so the
 * "런처 화면 크기" setting scales the whole screen by adjusting the activity's density. Only the
 * launcher's own UI scales; the apps in the panes keep their own per-pane dpi.
 */
open class ScaledActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val percent = Settings(newBase).uiScale
        if (percent == 100) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        val base = if (config.densityDpi > 0) {
            config.densityDpi
        } else {
            newBase.resources.displayMetrics.densityDpi
        }
        config.densityDpi = (base * percent / 100f).roundToInt().coerceIn(72, 960)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
