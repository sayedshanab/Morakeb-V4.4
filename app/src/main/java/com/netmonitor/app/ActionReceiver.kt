package com.netmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the "silence" and "stop" buttons on notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Notifier.ACTION_MUTE -> Notifier.mute(context)
            Notifier.ACTION_STOP -> {
                Prefs(context).enabled = false
                context.stopService(Intent(context, MonitorService::class.java))
                Notifier.mute(context)
            }
        }
    }
}
