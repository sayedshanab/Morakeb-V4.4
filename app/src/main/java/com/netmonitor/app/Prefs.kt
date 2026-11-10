package com.netmonitor.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("netmonitor", Context.MODE_PRIVATE)

    /** True when the user wants monitoring running (also used to restart after reboot). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Consecutive failed checks before the "disconnected" alarm fires. */
    var threshold: Int
        get() = sp.getInt("threshold", 3)
        set(v) = sp.edit().putInt("threshold", v).apply()

    /** Time between checks, in milliseconds. */
    var intervalMs: Int
        get() = sp.getInt("interval", 3000)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** Keep repeating the alarm sound until it is silenced (device dependent). */
    var repeatAlarm: Boolean
        get() = sp.getBoolean("repeat", true)
        set(v) = sp.edit().putBoolean("repeat", v).apply()

    /** The battery-optimisation prompt is shown automatically only once. */
    var batteryAsked: Boolean
        get() = sp.getBoolean("battery_asked", false)
        set(v) = sp.edit().putBoolean("battery_asked", v).apply()

    /** Seconds between automatic public-IP checks (10 s .. 10 min). */
    var ipIntervalSec: Int
        get() = sp.getInt("ip_interval_sec", 30)
        set(v) = sp.edit().putInt("ip_interval_sec", v).apply()

    /** Alarm loudness 0..300 %. Above 100 % the sound is boosted with a loudness enhancer. */
    var volumePct: Int
        get() = sp.getInt("volume_pct", 150)
        set(v) = sp.edit().putInt("volume_pct", v.coerceIn(0, 300)).apply()
}
