package com.netmonitor.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Formatting helpers. Digits are always Western (0-9), like the web version. */
object Fmt {
    fun toWestern(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** 12-hour clock "hh:mm:ss AM/PM", identical to the web version (no locale dependence). */
    fun time(ts: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%02d:%02d:%02d %s".format(
            Locale.US, h12, c.get(Calendar.MINUTE), c.get(Calendar.SECOND), if (h >= 12) "PM" else "AM"
        )
    }

    fun date(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy/M/d", Locale.US).format(Date(ts))

    /** Time of a stored timestamp; falls back to an old saved string when there is no timestamp. */
    fun ts(ts: Long, fallback: String = ""): String =
        if (ts > 0) time(ts) else toWestern(fallback)

    /** Date of a stored timestamp; falls back to an old saved string when there is no timestamp. */
    fun tsDate(ts: Long, fallback: String = ""): String =
        if (ts > 0) date(ts) else toWestern(fallback)

    fun fileDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun duration(secIn: Long): String {
        val sec = maxOf(0L, secIn)
        if (sec < 60) return "$sec ث"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val r = sec % 60
        if (h > 0) return "$h س $m د $r ث"
        return if (r > 0) "$m د $r ث" else "$m د"
    }
}
