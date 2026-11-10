package com.netmonitor.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            // While monitoring is off the service is not driving the IP check: do it from here.
            if (!MonitorState.running) {
                try { IpTracker.maybeAutoCheck(this@MainActivity, isOnline()) } catch (_: Exception) { }
            }
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var circle: TextView
    private lateinit var tvFails: TextView
    private lateinit var tvLossSub: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvPingSub: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvDownSub: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvLongestSub: TextView
    private lateinit var tvLastDur: TextView
    private lateinit var tvLastRange: TextView
    private lateinit var tvTotalDur: TextView
    private lateinit var banner: TextView
    private lateinit var recBanner: TextView
    private lateinit var logBox: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var outageScroll: ScrollView
    private lateinit var outageTable: TableLayout
    private lateinit var outageBadge: TextView
    private lateinit var logBadge: TextView
    private lateinit var volLabel: TextView
    private lateinit var volSeek: SeekBar
    private lateinit var btnToggle: Button
    private lateinit var btnMute: Button
    private lateinit var btnBattery: Button
    private lateinit var btnTestDown: Button
    private lateinit var btnTestUp: Button
    private lateinit var spThreshold: Spinner
    private lateinit var spInterval: Spinner
    private lateinit var cbRepeat: CheckBox
    private lateinit var tvIp: TextView
    private lateinit var tvIpSub: TextView
    private lateinit var btnIpCopy: Button
    private lateinit var btnIpRefresh: Button
    private lateinit var spIpInterval: Spinner
    private lateinit var ipTable: TableLayout
    private lateinit var ipScroll: ScrollView
    private lateinit var ipBadge: TextView

    private val thresholds = listOf(2, 3, 5)
    private val intervals = listOf(1, 3, 5, 10, 15, 60)

    private var lastLogVersion = -1
    private var lastHistoryVersion = -1
    private var ongoingDurTv: TextView? = null
    private var lastIpVersion = -1

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startMonitoring() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.ensureChannels(this)

        circle = findViewById(R.id.statusCircle)
        tvFails = findViewById(R.id.failCount)
        tvLossSub = findViewById(R.id.packetLossSub)
        tvPing = findViewById(R.id.pingTime)
        tvPingSub = findViewById(R.id.pingAvgSub)
        tvDown = findViewById(R.id.downDuration)
        tvDownSub = findViewById(R.id.currentDownSub)
        tvCount = findViewById(R.id.disconnectCount)
        tvLongestSub = findViewById(R.id.longestDownSub)
        tvLastDur = findViewById(R.id.lastDownDuration)
        tvLastRange = findViewById(R.id.lastDownTimeRange)
        tvTotalDur = findViewById(R.id.totalDownDuration)
        banner = findViewById(R.id.disconnectBanner)
        recBanner = findViewById(R.id.reconnectBanner)
        logBox = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        outageScroll = findViewById(R.id.outageScroll)
        outageTable = findViewById(R.id.outageTable)
        outageBadge = findViewById(R.id.outageCountBadge)
        logBadge = findViewById(R.id.logCountBadge)
        volLabel = findViewById(R.id.volLabel)
        volSeek = findViewById(R.id.volumeSeek)
        btnToggle = findViewById(R.id.toggleBtn)
        btnMute = findViewById(R.id.muteBtn)
        btnBattery = findViewById(R.id.batteryBtn)
        btnTestDown = findViewById(R.id.testDown)
        btnTestUp = findViewById(R.id.testUp)
        spThreshold = findViewById(R.id.thresholdSelect)
        spInterval = findViewById(R.id.intervalSelect)
        cbRepeat = findViewById(R.id.repeatCheck)
        tvIp = findViewById(R.id.ipValue)
        tvIpSub = findViewById(R.id.ipSourceSub)
        btnIpCopy = findViewById(R.id.ipCopyBtn)
        btnIpRefresh = findViewById(R.id.ipRefreshBtn)
        spIpInterval = findViewById(R.id.ipIntervalSelect)
        ipTable = findViewById(R.id.ipHistoryTable)
        ipScroll = findViewById(R.id.ipHistoryScroll)
        ipBadge = findViewById(R.id.ipHistoryBadge)

        allowNestedScroll(logScroll)
        allowNestedScroll(outageScroll)
        allowNestedScroll(ipScroll)
        setupSpinners()
        setupVolume()
        setupIp()

        cbRepeat.isChecked = prefs.repeatAlarm
        cbRepeat.setOnCheckedChangeListener { _, checked -> prefs.repeatAlarm = checked }

        btnToggle.setOnClickListener {
            if (MonitorState.running) stopMonitoring() else requestAndStart()
        }
        btnMute.setOnClickListener { Notifier.mute(this) }

        btnTestDown.setOnClickListener {
            if (AlarmPlayer.playingRes == R.raw.disconnect) {
                AlarmPlayer.stop()
            } else {
                MonitorState.log("🚨 تجربة نغمة الفصل (القوة: ${prefs.volumePct}%)", "fail")
                AlarmPlayer.play(this, R.raw.disconnect, false)
            }
            render()
            handler.postDelayed({ render() }, 1700)
        }
        btnTestUp.setOnClickListener {
            if (AlarmPlayer.playingRes == R.raw.reconnect) {
                AlarmPlayer.stop()
            } else {
                MonitorState.log("✅ تجربة نغمة الرجوع (القوة: ${prefs.volumePct}%)", "ok")
                AlarmPlayer.play(this, R.raw.reconnect, false)
            }
            render()
            handler.postDelayed({ render() }, 1600)
        }

        findViewById<Button>(R.id.clearHistory).setOnClickListener {
            MonitorState.clearAll()
            IpTracker.resetDisplay(this)
            MonitorState.log("🗑️ تم تصفير السجل المحفوظ وبدء جلسة إحصائيات جديدة.", "ok")
            render()
        }
        btnBattery.setOnClickListener { requestBatteryExemption() }

        if (MonitorState.snapshot().isEmpty()) {
            MonitorState.log("v4.4 جاهز (By Mahmoud_Kamal) - فحص حقيقي من طرف لطرف، يعمل مع الـ VPN ووقت AM/PM", "ok")
        }

        // Process was killed while monitoring was on: bring the service back.
        if (prefs.enabled && !MonitorState.running) {
            try { MonitorService.start(this) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------ setup

    @SuppressLint("ClickableViewAccessibility")
    private fun allowNestedScroll(sv: ScrollView) {
        sv.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun setupVolume() {
        volSeek.progress = prefs.volumePct
        updateVolLabel(prefs.volumePct)
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.volumePct = progress
                updateVolLabel(progress)
                AlarmPlayer.setBoost(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateVolLabel(pct: Int) {
        volLabel.text = "$pct%"
        volLabel.setTextColor(Color.parseColor(if (pct > 100) "#ffaa00" else "#00ff88"))
    }

    private fun setupIp() {
        spIpInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, IpTracker.INTERVAL_LABELS
        )
        spIpInterval.setSelection(IpTracker.INTERVALS.indexOf(prefs.ipIntervalSec).coerceAtLeast(0))
        spIpInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val value = IpTracker.INTERVALS[pos]
                if (value != prefs.ipIntervalSec) {
                    prefs.ipIntervalSec = value
                    IpTracker.setIntervalSec(value)
                    MonitorState.log(
                        "⏱️ تم تغيير مدة فحص الـ IP التلقائي إلى: ${IpTracker.intervalText(value)} (محفوظة)", "ok"
                    )
                    IpTracker.resetDisplay(this@MainActivity)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnIpRefresh.setOnClickListener { IpTracker.fetch(this, "manual") }
        btnIpCopy.setOnClickListener {
            val ip = MonitorState.lastPublicIp
            if (ip.isEmpty()) return@setOnClickListener
            try {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ip", ip))
                MonitorState.log("📋 تم نسخ الـ IP إلى الحافظة: $ip", "ok")
                btnIpCopy.text = "✅ تم النسخ"
                handler.postDelayed({ btnIpCopy.text = "📋 نسخ" }, 1500)
            } catch (_: Exception) {
                MonitorState.log("⚠️ تعذر نسخ الـ IP", "warn")
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupSpinners() {
        spThreshold.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("2 ثواني (حساس)", "3 ثواني (موصى به)", "5 ثواني")
        )
        spThreshold.setSelection(thresholds.indexOf(prefs.threshold).coerceAtLeast(0))
        spThreshold.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val value = thresholds[pos]
                if (value != prefs.threshold) {
                    prefs.threshold = value
                    render()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "كل 1 ثانية (دقيق، أعلى استهلاك)",
                "كل 3 ثواني (متوازن)",
                "كل 5 ثواني",
                "كل 10 ثواني",
                "كل 15 ثانية (موفر للطاقة)",
                "كل 60 ثانية (أقصى توفير)"
            )
        )
        spInterval.setSelection(intervals.indexOf(prefs.intervalMs / 1000).coerceAtLeast(0))
        spInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val ms = intervals[pos] * 1000
                if (ms != prefs.intervalMs) {
                    prefs.intervalMs = ms
                    // Restart the loop so the new interval applies immediately.
                    if (MonitorState.running) MonitorService.start(this@MainActivity)
                    render()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ----------------------------------------------------------- actions

    private fun requestAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        prefs.enabled = true
        MonitorState.running = true
        MonitorService.start(this)
        render()
        // One-time system prompt so Android doesn't freeze the service in Doze / battery saver.
        if (!isIgnoringBattery() && !prefs.batteryAsked) {
            prefs.batteryAsked = true
            requestBatteryExemption()
        }
    }

    private fun stopMonitoring() {
        prefs.enabled = false
        stopService(Intent(this, MonitorService::class.java))
        Notifier.mute(this)
        render()
    }

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // --------------------------------------------------------- rendering

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setCircle(bg: String, stroke: String, text: String) {
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(bg))
            setStroke(dp(4), Color.parseColor(stroke))
        }
        circle.background = d
        circle.setTextColor(Color.parseColor(stroke))
        circle.text = text
    }

    private fun render() {
        val s = MonitorState
        val thr = prefs.threshold
        val run = s.running
        val now = System.currentTimeMillis()
        val liveDownSec = if (s.downSince > 0) maxOf(1L, (now - s.downSince) / 1000) else 0L

        when {
            !run -> setCircle("#181818", "#666666", "جاهز\nللبدء")
            s.status == Status.FAIL -> setCircle("#3d0a0a", "#ff3333", "فاصل!\n$thr/$thr")
            s.status == Status.WARN ->
                setCircle("#2a1a00", "#ffaa00", "فقد باكت\n${minOf(s.fails, thr)}/$thr")
            else -> setCircle("#0a3d1f", "#00ff88", "متصل\n${if (s.latency >= 0) "${s.latency}ms" else ""}")
        }

        tvFails.text = "${minOf(s.fails, thr)} / $thr"
        tvLossSub.text = "إجمالي فقد الباكت: ${s.totalTimeouts}"

        when {
            run && s.latency >= 0 -> {
                tvPing.text = "${s.latency} ms"
                tvPingSub.text = if (s.latency < 90) "حالة الخط: ممتاز" else "حالة الخط: مستقر"
            }
            run && s.fails > 0 -> {
                tvPing.text = "Timeout"
                tvPingSub.text = "لا توجد استجابة!"
            }
            run -> {
                tvPing.text = "--"
                tvPingSub.text = "جاري فحص الاتصال..."
            }
            else -> {
                tvPing.text = "--"
                tvPingSub.text = "حالة الخط: مستعد"
            }
        }

        when {
            run && liveDownSec > 0 -> {
                tvDown.text = Fmt.duration(liveDownSec)
                tvDownSub.text = "بدأ منذ ${Fmt.time(s.downSince)}"
            }
            run -> {
                tvDown.text = "--"
                tvDownSub.text = "الخط متصل الآن ✓"
            }
            else -> {
                tvDown.text = "--"
                tvDownSub.text = "المراقبة متوقفة"
            }
        }

        tvCount.text = "${s.disconnectCount}"
        tvLongestSub.text =
            if (s.longestDowntimeSec > 0) "أطول فصل: ${Fmt.duration(s.longestDowntimeSec)}" else "أطول فصل: --"

        if (s.disconnected && liveDownSec > 0) {
            tvLastDur.text = Fmt.duration(liveDownSec)
            tvLastRange.text = "بدأ ${Fmt.ts(s.lastDownStartTs, s.lastDownStart)} (جاري الآن...)"
        } else if (s.lastDownSec > 0 || s.lastDownStart.isNotEmpty()) {
            tvLastDur.text = Fmt.duration(s.lastDownSec)
            val st = Fmt.ts(s.lastDownStartTs, s.lastDownStart)
            val en = Fmt.ts(s.lastDownEndTs, s.lastDownEnd)
            tvLastRange.text = if (s.lastDownEnd.isNotEmpty()) "$st ← $en" else "بدأ $st"
        } else {
            tvLastDur.text = "--"
            tvLastRange.text = "لم يسجل انقطاع بعد"
        }
        tvTotalDur.text = Fmt.duration(s.totalDowntimeSec)

        banner.visibility = if (s.disconnected) View.VISIBLE else View.GONE
        btnMute.visibility = if (s.disconnected) View.VISIBLE else View.GONE
        if (!s.disconnected && s.reconnectAt > 0 && now - s.reconnectAt < 5000) {
            recBanner.text = s.reconnectText
            recBanner.visibility = View.VISIBLE
        } else {
            recBanner.visibility = View.GONE
        }

        if (run) {
            btnToggle.text = "⏸️ وقف المراقبة"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ff3333"))
            btnToggle.setTextColor(Color.WHITE)
        } else {
            btnToggle.text = "▶️ ابدأ المراقبة"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00ff88"))
            btnToggle.setTextColor(Color.BLACK)
        }

        btnTestDown.text =
            if (AlarmPlayer.playingRes == R.raw.disconnect) "⏹️ إيقاف النغمة" else "🔊 جرب نغمة الفصل"
        btnTestUp.text =
            if (AlarmPlayer.playingRes == R.raw.reconnect) "⏹️ إيقاف النغمة" else "🔊 جرب نغمة الرجوع"

        tvIp.text = s.ipValueText
        tvIp.setTextColor(
            Color.parseColor(
                when (s.ipState) {
                    1 -> "#ffaa00"
                    2 -> "#ff8888"
                    else -> "#00ff88"
                }
            )
        )
        tvIpSub.text = s.ipSubText
        btnIpCopy.isEnabled = s.lastPublicIp.isNotEmpty()
        if (lastIpVersion != s.ipVersion) {
            lastIpVersion = s.ipVersion
            rebuildIpTable()
        }

        btnBattery.visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE

        if (lastHistoryVersion != s.historyVersion) {
            lastHistoryVersion = s.historyVersion
            rebuildOutageTable()
        }
        if (s.disconnected && liveDownSec > 0) ongoingDurTv?.text = Fmt.duration(liveDownSec)

        if (lastLogVersion != s.logVersion) {
            lastLogVersion = s.logVersion
            val lines = s.snapshot()
            logBadge.text = "${lines.size} حدث مسجل"
            refreshLog(lines)
            logScroll.scrollTo(0, 0)
        }
    }

    private val arabicRe = Regex("[\\u0600-\\u06FF]")
    private var lastTopLine: LogLine? = null

    /** Newest lines are prepended; the list is only rebuilt completely when it was cleared. */
    private fun refreshLog(lines: List<LogLine>) {
        val top = lastTopLine
        val k = if (top == null) -1 else lines.indexOfFirst { it === top }
        if (k < 0 || logBox.childCount == 0) {
            logBox.removeAllViews()
            for (l in lines) logBox.addView(logRow(l))
        } else {
            for (i in k - 1 downTo 0) logBox.addView(logRow(lines[i]), 0)
            while (logBox.childCount > lines.size) logBox.removeViewAt(logBox.childCount - 1)
        }
        lastTopLine = lines.firstOrNull()
    }

    /**
     * One log line: time stamp pinned to the far left (always left-to-right), message beside it.
     * An Arabic message is right-to-left and starts from the far right; other text stays left.
     */
    private fun logRow(l: LogLine): View {
        val rtl = arabicRe.containsMatchIn(l.msg)
        val color = Color.parseColor(
            when (l.type) {
                "warn" -> "#ffaa00"
                "fail" -> "#ff5555"
                else -> "#00ff88"
            }
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(3), 0, dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val time = TextView(this).apply {
            text = "[${Fmt.ts(l.ts, l.time)}]"
            setTextColor(Color.parseColor("#38bdf8"))
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(8) }
        }
        val msg = TextView(this).apply {
            text = l.msg
            setTextColor(color)
            textSize = 13f
            textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
            gravity = if (rtl) Gravity.RIGHT else Gravity.LEFT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(time)
        row.addView(msg)
        return row
    }

    private fun cell(text: String, color: Int = Color.parseColor("#eeeeee"), bold: Boolean = false, span: Int = 1): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(3), dp(7), dp(3), dp(7))
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT
            ).also { if (span > 1) it.span = span }
        }

    private fun rebuildOutageTable() {
        val list = MonitorState.outageSnapshot()
        outageBadge.text = "${list.size} انقطاع مسجل"
        outageTable.removeAllViews()
        ongoingDurTv = null

        val head = TableRow(this).apply { setBackgroundColor(Color.parseColor("#222222")) }
        for (h in listOf("#", "التاريخ", "بداية الفصل", "عودة النت", "مدة الانقطاع", "البنج")) {
            head.addView(cell(h, Color.parseColor("#cccccc"), bold = true))
        }
        outageTable.addView(head)

        if (list.isEmpty()) {
            val r = TableRow(this)
            r.addView(cell("لا توجد انقطاعات مسجلة حتى الآن", Color.parseColor("#666666"), span = 6))
            outageTable.addView(r)
            return
        }

        val liveSec = if (MonitorState.downSince > 0)
            maxOf(1L, (System.currentTimeMillis() - MonitorState.downSince) / 1000) else 0L

        list.forEachIndexed { idx, o ->
            val r = TableRow(this)
            val num = list.size - idx
            if (o.ongoing) {
                r.setBackgroundColor(Color.parseColor("#3d0a0a"))
                val red = Color.parseColor("#ff8888")
                r.addView(cell("$num", red, true))
                r.addView(cell(Fmt.tsDate(o.startTs, o.date), red, true))
                r.addView(cell(Fmt.ts(o.startTs, o.startTime), red, true))
                r.addView(cell("🔴 جاري الآن...", red, true))
                val dur = cell(Fmt.duration(if (liveSec > 0) liveSec else o.durationSec), red, true)
                ongoingDurTv = dur
                r.addView(dur)
                r.addView(cell("--", red, true))
            } else {
                r.addView(cell("$num"))
                r.addView(cell(Fmt.tsDate(o.startTs, o.date)))
                r.addView(cell(Fmt.ts(o.startTs, o.startTime)))
                r.addView(cell(Fmt.ts(o.endTs, o.endTime)))
                r.addView(cell(Fmt.duration(o.durationSec), Color.parseColor("#ffaa00"), true))
                r.addView(cell(o.recoveryPing, Color.parseColor("#00ff88")))
            }
            outageTable.addView(r)
        }
    }

    private fun rebuildIpTable() {
        val list = MonitorState.ipSnapshot()
        ipBadge.text = "${list.size} عنوان مسجل"
        ipTable.removeAllViews()

        val head = TableRow(this).apply { setBackgroundColor(Color.parseColor("#222222")) }
        for (h in listOf("#", "عنوان الـ IP", "التاريخ - الوقت")) {
            head.addView(cell(h, Color.parseColor("#cccccc"), bold = true))
        }
        ipTable.addView(head)

        if (list.isEmpty()) {
            val r = TableRow(this)
            r.addView(cell("لم يُسجل أي عنوان IP بعد", Color.parseColor("#666666"), span = 3))
            ipTable.addView(r)
            return
        }
        list.forEachIndexed { idx, e ->
            val r = TableRow(this)
            if (idx == 0) r.setBackgroundColor(Color.parseColor("#0a3d1f"))
            r.addView(cell("${list.size - idx}"))
            r.addView(cell(e.ip, Color.parseColor("#00ff88"), true))
            r.addView(cell("${Fmt.date(e.ts)} - ${Fmt.time(e.ts)}"))
            ipTable.addView(r)
        }
    }

    override fun onStart() {
        super.onStart()
        MonitorState.addListener(listener)
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        MonitorState.removeListener(listener)
        super.onStop()
    }
}
