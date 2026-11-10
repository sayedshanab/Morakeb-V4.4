package com.netmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after a reboot or app update if the user had it switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).enabled) {
            try {
                MonitorService.start(context)
            } catch (_: Exception) { }
        }
    }
}
