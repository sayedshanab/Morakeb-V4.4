package com.netmonitor.app

import android.content.Context
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Public IP tracking (v4.4): asks 6 sources in parallel, takes the majority answer, keeps a
 * persistent history of every change and notifies when the address changes.
 *
 * It is driven by the monitoring service (so it keeps working with the screen off) and by the
 * UI while monitoring is stopped, via [maybeAutoCheck].
 */
object IpTracker {
    private class Source(val name: String, val url: String)

    private val SOURCES = listOf(
        Source("Cloudflare Trace", "https://www.cloudflare.com/cdn-cgi/trace"),
        Source("Cloudflare 1.1.1.1", "https://1.1.1.1/cdn-cgi/trace"),
        Source("Amazon AWS", "https://checkip.amazonaws.com/"),
        Source("ipify", "https://api.ipify.org?format=text"),
        Source("ipify IPv4", "https://api4.ipify.org?format=text"),
        Source("icanhazip", "https://ipv4.icanhazip.com")
    )

    /** Choices of the "check every" selector, in seconds. */
    val INTERVALS = listOf(10, 30, 60, 120, 180, 300, 600)

    private const val FETCH_TIMEOUT_MS = 5000
    private const val STALE_MS = 15_000L
    private const val RECONNECT_DEBOUNCE_MS = 3000L

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ip-fetch").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private val inflight = CopyOnWriteArrayList<HttpURLConnection>()
    private val lineRegex = Regex("^ip=(.+)$", RegexOption.MULTILINE)
    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    @Volatile private var fetching = false
    @Volatile private var fetchStart = 0L
    @Volatile private var lastAttempt = 0L
    @Volatile private var intervalSec = 30

    val isFetching: Boolean get() = fetching

    // ------------------------------------------------------------ text helpers

    fun intervalText(sec: Int): String {
        if (sec < 60) return "$sec ثانية"
        val m = sec / 60
        if (m == 1) return "دقيقة"
        if (m == 2) return "دقيقتين"
        return "$m" + (if (m <= 10) " دقائق" else " دقيقة")
    }

    /** Selector labels, same order as [INTERVALS]. */
    val INTERVAL_LABELS = listOf(
        "10 ثواني", "30 ثانية", "دقيقة واحدة", "دقيقتان", "3 دقائق", "5 دقائق", "10 دقائق"
    )

    // ------------------------------------------------------------ setup

    /** Called once from [App]: restores the saved interval and the "last saved address" text. */
    fun init(ctx: Context) {
        intervalSec = Prefs(ctx).ipIntervalSec
        resetDisplay(ctx)
    }

    fun setIntervalSec(sec: Int) {
        intervalSec = sec
    }

    /** Initial text under the address (also used after "clear history"). */
    fun resetDisplay(ctx: Context) {
        val s = MonitorState
        intervalSec = Prefs(ctx).ipIntervalSec
        val first = s.ipSnapshot().firstOrNull()
        s.ipState = 0
        if (first != null) {
            s.ipSubText = "📦 آخر عنوان محفوظ: ${first.ip} منذ ${Fmt.time(first.ts)} | " +
                "فحص تلقائي كل ${intervalText(intervalSec)}"
        } else {
            s.ipSubText = "فحص تلقائي كل ${intervalText(intervalSec)} - وتسجيل أي تغيير مع وقته"
        }
        s.publish()
    }

    // ------------------------------------------------------------ triggers

    /** Cheap: call it often. Starts a check only when the saved interval has passed. */
    fun maybeAutoCheck(ctx: Context, online: Boolean) {
        if (!online) return
        val now = System.currentTimeMillis()
        if (lastAttempt == 0L) {
            fetch(ctx, "startup")
        } else if (now - lastAttempt >= intervalSec * 1000L) {
            fetch(ctx, "auto")
        }
    }

    /**
     * reason: "startup" | "auto" | "manual" | "reconnect" | "timeouts".
     * "manual" and "reconnect" restart a check that is already running; the others wait for it.
     */
    fun fetch(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val myGen = synchronized(this) {
            if (fetching) {
                val stale = now - fetchStart > STALE_MS
                val recentReconnect = reason == "reconnect" && now - fetchStart < RECONNECT_DEBOUNCE_MS
                if (recentReconnect) return
                if (reason == "manual" || reason == "reconnect" || stale) abortInflight() else return
            }
            fetching = true
            fetchStart = now
            lastAttempt = now
            generation.incrementAndGet()
        }
        val s = MonitorState
        s.ipState = 1
        s.ipSubText = "⏳ جاري فحص الـ IP عبر ${SOURCES.size} مصادر بالتوازي..."
        s.publish()
        try {
            pool.execute { runFetch(app, reason, myGen) }
        } catch (_: Exception) {
            fetching = false
        }
    }

    private fun abortInflight() {
        for (c in inflight) {
            try { c.disconnect() } catch (_: Exception) { }
        }
        inflight.clear()
    }

    // ------------------------------------------------------------ the check

    private fun runFetch(app: Context, reason: String, myGen: Int) {
        // The CPU may be asleep between monitoring cycles: keep it awake for this check only.
        val wl = try {
            app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmonitor:ip").apply {
                    setReferenceCounted(false)
                    acquire(12_000L)
                }
        } catch (_: Exception) {
            null
        }
        try {
            val tasks = SOURCES.map { src -> Callable<Pair<String, String>?> { fetchOne(src) } }
            val futures: List<Future<Pair<String, String>?>> = try {
                pool.invokeAll(tasks, FETCH_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                emptyList()
            }
            val results = ArrayList<Pair<String, String>>()
            for (f in futures) {
                try {
                    if (!f.isCancelled) f.get()?.let { results.add(it) }
                } catch (_: Exception) { }
            }
            // A newer check replaced this one: ignore this (late) answer.
            if (myGen != generation.get()) return
            fetching = false
            inflight.clear()
            handleResults(app, reason, results)
        } catch (_: Throwable) {
            if (myGen == generation.get()) fetching = false
        } finally {
            try { if (wl != null && wl.isHeld) wl.release() } catch (_: Exception) { }
        }
    }

    /** Returns (ip, source name) or null. */
    private fun fetchOne(src: Source): Pair<String, String>? {
        var conn: HttpURLConnection? = null
        try {
            val sep = if (src.url.contains('?')) "&t=" else "?t="
            val url = URL(src.url + sep + System.currentTimeMillis() + System.nanoTime())
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "NetMonitor")
            }
            inflight.add(conn)
            if (conn.responseCode !in 200..299) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }.take(4096)
            val ip = normalize(txt)
            return if (ip.isNotEmpty()) ip to src.name else null
        } catch (_: Exception) {
            return null
        } finally {
            conn?.let {
                inflight.remove(it)
                try { it.disconnect() } catch (_: Exception) { }
            }
        }
    }

    /** Extracts a valid IPv4 address from a reply ("ip=1.2.3.4" trace format or plain text). */
    private fun normalize(raw: String): String {
        var txt = raw
        lineRegex.find(txt)?.let { txt = it.groupValues[1] }
        val m = ipv4Regex.find(txt) ?: return ""
        for (part in m.value.split('.')) {
            if ((part.toIntOrNull() ?: 999) > 255) return ""
        }
        return m.value
    }

    private fun handleResults(app: Context, reason: String, results: List<Pair<String, String>>) {
        val s = MonitorState

        if (results.isEmpty()) {
            s.ipState = 2
            if (s.lastPublicIp.isEmpty()) s.ipValueText = "فشل الجلب"
            s.ipSubText =
                "❌ فشلت كل مصادر الـ IP - اضغط 🔄 تحديث للمحاولة مرة أخرى " +
                "(تأكد أن مانع الإعلانات/VPN لا يحظر الطلبات)"
            if (reason == "manual" || reason == "reconnect") {
                s.log("⚠️ تعذر جلب عنوان الـ IP من كل المصادر", "warn")
            }
            s.publish()
            return
        }

        val votes = LinkedHashMap<String, Int>()
        val names = HashMap<String, MutableList<String>>()
        for ((ip, name) in results) {
            votes[ip] = (votes[ip] ?: 0) + 1
            names.getOrPut(ip) { ArrayList() }.add(name)
        }

        val previous = s.lastPublicIp
        // Most votes first; on a tie prefer an address that differs from the last known one.
        val ranked = votes.keys.sortedWith(
            compareByDescending<String> { votes[it] ?: 0 }
                .thenByDescending { if (it != previous) 1 else 0 }
        )
        val best = ranked[0]
        val sourceText = (names[best] ?: emptyList<String>()).joinToString(" + ") +
            " (${votes[best]}/${results.size})"

        registerIp(app, best, sourceText, reason)

        if (ranked.size > 1) {
            s.log(
                "⚠️ المصادر اختلفت: " + ranked.joinToString(" | ") { "$it ×${votes[it]}" },
                "warn"
            )
        }
    }

    private fun registerIp(app: Context, ip: String, source: String, reason: String) {
        val s = MonitorState
        val now = System.currentTimeMillis()
        val previous = s.lastPublicIp
        val isNew = ip != previous

        s.ipState = 0
        s.ipValueText = ip
        s.ipSubText = "✅ المصدر: $source | آخر فحص: ${Fmt.time(now)}" +
            (if (isNew) "" else " (بدون تغيير)")
        s.lastPublicIp = ip

        if (isNew) {
            s.addIp(ip, now, source)
            val label = if (reason == "startup" || previous.isEmpty()) "الـ IP الحالي: " else "تغيّر الـ IP إلى: "
            s.log("🌐 $label$ip (المصدر: $source)", "ok")
            // The very first address ever recorded is not a "change".
            if (previous.isNotEmpty()) {
                try { Notifier.ipChanged(app, ip) } catch (_: Exception) { }
            }
        }
        s.publish()
    }
}
