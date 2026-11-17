package com.example.isle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Optionally brings the island back after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Store.init(context)
        if (Store.bool("autoStart") && Settings.canDrawOverlays(context)) {
            try {
                OverlayService.start(context)
            } catch (e: Exception) {
                // Some OEMs block background service starts; the user can just open the app.
            }
        }
    }
}
