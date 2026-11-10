package com.netmonitor.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load saved statistics / outage history / log as soon as the process starts
        // (activity, service, boot receiver all share this process-wide state).
        MonitorState.init(this)
        IpTracker.init(this)
    }
}
