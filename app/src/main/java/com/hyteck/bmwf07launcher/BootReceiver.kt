package com.hyteck.bmwf07launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hyteck.bmwf07launcher.data.Settings

/**
 * Starts the launcher when the head unit boots.
 *
 * The system already starts the default HOME app after boot; this receiver covers the case
 * where the unit keeps another home app but the user still wants this one up (the overlay
 * permission keeps the background activity start allowed on Android 10+).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != QUICKBOOT_POWERON) return
        val settings = Settings(context)
        if (!settings.bootAutostart) return
        val launch = Intent(context, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(launch)
        } catch (ignored: Exception) {
            // Background start blocked: the default HOME entry still brings the launcher up.
        }
    }

    companion object {
        private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
