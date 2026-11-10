package com.netmonitor.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

enum class Status { IDLE, OK, WARN, FAIL }

/** [ts] is the exact time; [time] / [date] are only used for entries saved by older versions. */
data class LogLine(
    val time: String,
    val msg: String,
    val type: String,
    val date: String = "",
    val ts: Long = 0L
)

data class Outage(
    val id: Long,
    val date: String,
    val startTime: String,
    var endTime: String,
    var durationSec: Long,
    var recoveryPing: String,
    var ongoing: Boolean,
    val startTs: Long = 0L,
    var endTs: Long = 0L
)

data class IpEntry(val ip: String, val ts: Long, val source: String)

/**
 * Process-wide state shared between the foreground service and the UI.
 * Statistics, outage history, public-IP history and the event log are persisted (like
 * localStorage in the web version) and survive app / phone restarts.
 */
object MonitorState {
    // ---- live state (not persisted)
    @Volatile var running = false
    @Volatile var status = Status.IDLE
    @Volatile var fails = 0
    @Volatile var latency = -1
    @Volatile var downSince = 0L
    @Volatile var disconnected = false
    @Volatile var reconnectAt = 0L
    @Volatile var reconnectText = ""

    // ---- persisted statistics
    @Volatile var disconnectCount = 0
    @Volatile var totalTimeouts = 0
    @Volatile var totalDowntimeSec = 0L
    @Volatile var longestDowntimeSec = 0L
    @Volatile var lastDownSec = 0L
    @Volatile var lastDownStart = ""
    @Volatile var lastDownEnd = ""
    @Volatile var lastDownStartTs = 0L
    @Volatile var lastDownEndTs = 0L

    // ---- public IP (display state; the history list below is persisted)
    @Volatile var lastPublicIp = ""
    @Volatile var ipValueText = "--"

    /** 0 = normal (green), 1 = checking (orange), 2 = failed (red). */
    @Volatile var ipState = 0
    @Volatile var ipSubText = ""

    @Volatile var logVersion = 0
    @Volatile var historyVersion = 0
    @Volatile var ipVersion = 0

    private const val MAX_LOG = 250
    private const val MAX_OUTAGES = 200
    private const val MAX_IPS = 100

    private val lock = Any()
    private val lines = ArrayList<LogLine>()
    private val outages = ArrayList<Outage>()
    private val ips = ArrayList<IpEntry>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var sp: SharedPreferences? = null
    @Volatile private var saveScheduled = false

    // ------------------------------------------------------------------ log

    fun log(msg: String, type: String = "ok") {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            lines.add(0, LogLine(Fmt.time(now), Fmt.toWestern(msg), type, Fmt.date(now), now))
            while (lines.size > MAX_LOG) lines.removeAt(lines.size - 1)
            logVersion++
        }
        saveSoon()
        publish()
    }

    fun snapshot(): List<LogLine> = synchronized(lock) { ArrayList(lines) }

    fun outageSnapshot(): List<Outage> = synchronized(lock) { outages.map { it.copy() } }

    fun ipSnapshot(): List<IpEntry> = synchronized(lock) { ArrayList(ips) }

    // -------------------------------------------------------------- outages

    /** Called when "disconnected" is declared. */
    fun openOutage(startTs: Long, initialDurSec: Long) {
        synchronized(lock) {
            lastDownStart = Fmt.time(startTs)
            lastDownStartTs = startTs
            lastDownEnd = ""
            lastDownEndTs = 0L
            lastDownSec = initialDurSec
            outages.add(
                0,
                Outage(
                    System.currentTimeMillis(), Fmt.date(startTs), lastDownStart, "",
                    initialDurSec, "--", true, startTs, 0L
                )
            )
            while (outages.size > MAX_OUTAGES) outages.removeAt(outages.size - 1)
            historyVersion++
        }
        saveSoon()
    }

    /** Called when the connection returns (or monitoring is stopped during an outage). */
    fun closeOutage(endTs: Long, durSec: Long, recoveryPing: String) {
        synchronized(lock) {
            val endStr = Fmt.time(endTs)
            outages.firstOrNull()?.let {
                if (it.ongoing) {
                    it.ongoing = false
                    it.endTime = endStr
                    it.endTs = endTs
                    it.durationSec = durSec
                    it.recoveryPing = recoveryPing
                }
            }
            lastDownSec = durSec
            lastDownEnd = endStr
            lastDownEndTs = endTs
            totalDowntimeSec += durSec
            if (durSec > longestDowntimeSec) longestDowntimeSec = durSec
            historyVersion++
        }
        saveSoon()
    }

    /** Monitoring stopped while the line was down: close the open row. */
    fun stopOngoing() {
        if (disconnected && downSince > 0) {
            val now = System.currentTimeMillis()
            closeOutage(now, maxOf(1L, Math.round((now - downSince) / 1000.0)), "--")
        }
    }

    // ------------------------------------------------------------ public IP

    /** Newest first, capped at 100 entries (same as the web version). */
    fun addIp(ip: String, ts: Long, source: String) {
        synchronized(lock) {
            ips.add(0, IpEntry(ip, ts, source))
            while (ips.size > MAX_IPS) ips.removeAt(ips.size - 1)
            ipVersion++
        }
        saveSoon()
    }

    fun clearAll() {
        synchronized(lock) {
            lines.clear()
            outages.clear()
            ips.clear()
            disconnectCount = 0
            totalTimeouts = 0
            totalDowntimeSec = 0L
            longestDowntimeSec = 0L
            lastDownSec = 0L
            lastDownStart = ""
            lastDownEnd = ""
            lastDownStartTs = 0L
            lastDownEndTs = 0L
            lastPublicIp = ""
            ipValueText = "--"
            ipState = 0
            logVersion++
            historyVersion++
            ipVersion++
        }
        saveNow()
        publish()
    }

    /** Live values only (statistics / history are kept). */
    fun reset() {
        running = false
        status = Status.IDLE
        fails = 0
        latency = -1
        downSince = 0L
        disconnected = false
        publish()
    }

    // ---------------------------------------------------------- UI listeners

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun publish() {
        main.post { listeners.forEach { it() } }
    }

    // ----------------------------------------------------------- persistence

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("netmonitor_history", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val p = sp ?: return
        try {
            p.getString("stats", null)?.let { j ->
                val o = JSONObject(j)
                disconnectCount = o.optInt("disconnectCount")
                totalTimeouts = o.optInt("totalTimeouts")
                totalDowntimeSec = o.optLong("totalDowntimeSec")
                longestDowntimeSec = o.optLong("longestDowntimeSec")
                lastDownSec = o.optLong("lastDownSec")
                lastDownStart = o.optString("lastDownStart")
                lastDownEnd = o.optString("lastDownEnd")
                lastDownStartTs = o.optLong("lastDownStartTs")
                lastDownEndTs = o.optLong("lastDownEndTs")
            }
            var hadOngoing = false
            p.getString("outages", null)?.let { j ->
                val a = JSONArray(j)
                synchronized(lock) {
                    outages.clear()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val wasOngoing = o.optBoolean("ongoing")
                        if (wasOngoing) hadOngoing = true
                        outages.add(
                            Outage(
                                o.optLong("id"),
                                o.optString("date"),
                                o.optString("startTime"),
                                if (wasOngoing) "أُغلق التطبيق" else o.optString("endTime"),
                                o.optLong("durationSec"),
                                o.optString("recoveryPing", "--"),
                                false,
                                o.optLong("startTs"),
                                // An outage that was still open when the app closed has no real end time.
                                if (wasOngoing) 0L else o.optLong("endTs")
                            )
                        )
                    }
                }
            }
            if (hadOngoing && lastDownEnd.isEmpty()) lastDownEnd = "أُغلق التطبيق"
            p.getString("logs", null)?.let { j ->
                val a = JSONArray(j)
                synchronized(lock) {
                    lines.clear()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        lines.add(
                            LogLine(
                                o.optString("time"),
                                o.optString("msg"),
                                o.optString("type", "ok"),
                                o.optString("date"),
                                o.optLong("ts")
                            )
                        )
                    }
                }
            }
            p.getString("ips", null)?.let { j ->
                val a = JSONArray(j)
                synchronized(lock) {
                    ips.clear()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val ip = o.optString("ip")
                        if (ip.isNotEmpty()) ips.add(IpEntry(ip, o.optLong("ts"), o.optString("source", "--")))
                    }
                    ips.firstOrNull()?.let {
                        lastPublicIp = it.ip
                        ipValueText = it.ip
                    }
                }
            }
            logVersion++
            historyVersion++
            ipVersion++
        } catch (_: Exception) { }
    }

    /** Debounced: at most one disk write every ~2 s even if logging every second. */
    private fun saveSoon() {
        if (saveScheduled) return
        saveScheduled = true
        main.postDelayed({
            saveScheduled = false
            saveNow()
        }, 2000)
    }

    fun saveNow() {
        val p = sp ?: return
        try {
            val stats = JSONObject()
                .put("disconnectCount", disconnectCount)
                .put("totalTimeouts", totalTimeouts)
                .put("totalDowntimeSec", totalDowntimeSec)
                .put("longestDowntimeSec", longestDowntimeSec)
                .put("lastDownSec", lastDownSec)
                .put("lastDownStart", lastDownStart)
                .put("lastDownEnd", lastDownEnd)
                .put("lastDownStartTs", lastDownStartTs)
                .put("lastDownEndTs", lastDownEndTs)
            val outArr = JSONArray()
            val logArr = JSONArray()
            val ipArr = JSONArray()
            val ds = downSince
            synchronized(lock) {
                for (o in outages) {
                    var dur = o.durationSec
                    if (o.ongoing && ds > 0) dur = maxOf(dur, (System.currentTimeMillis() - ds) / 1000)
                    outArr.put(
                        JSONObject().put("id", o.id).put("date", o.date).put("startTime", o.startTime)
                            .put("endTime", o.endTime).put("durationSec", dur)
                            .put("recoveryPing", o.recoveryPing).put("ongoing", o.ongoing)
                            .put("startTs", o.startTs).put("endTs", o.endTs)
                    )
                }
                for (l in lines) {
                    logArr.put(
                        JSONObject().put("time", l.time).put("msg", l.msg).put("type", l.type)
                            .put("date", l.date).put("ts", l.ts)
                    )
                }
                for (e in ips) {
                    ipArr.put(JSONObject().put("ip", e.ip).put("ts", e.ts).put("source", e.source))
                }
            }
            p.edit()
                .putString("stats", stats.toString())
                .putString("outages", outArr.toString())
                .putString("logs", logArr.toString())
                .putString("ips", ipArr.toString())
                .apply()
        } catch (_: Exception) { }
    }
}
