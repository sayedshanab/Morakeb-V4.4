package com.netmonitor.app

/** ISP-ready reports (same content as the web version's CSV / TXT export). */
object Report {
    private const val Q = "\""
    private const val CRLF = "\r\n"

    fun csv(): String {
        val s = MonitorState
        val outs = s.outageSnapshot()
        val rows = ArrayList<List<String>>()
        rows.add(
            listOf(
                "رقم الانقطاع", "التاريخ", "وقت بداية الفصل", "وقت عودة الاتصال",
                "مدة الانقطاع (بالثواني)", "مدة الانقطاع (مفصل)", "البنج بعد العودة", "الحالة"
            )
        )
        outs.forEachIndexed { idx, o ->
            rows.add(
                listOf(
                    "${outs.size - idx}", o.date, o.startTime,
                    if (o.ongoing) "جاري الآن" else o.endTime,
                    "${o.durationSec}", Fmt.duration(o.durationSec), o.recoveryPing,
                    if (o.ongoing) "مقطوع حالياً" else "تمت العودة"
                )
            )
        }
        rows.add(emptyList())
        rows.add(listOf("ملخص التقرير (By Mahmoud_Kamal)", "", "", "", "", "", "", ""))
        rows.add(
            listOf(
                "إجمالي مرات الفصل الكلية", "${s.disconnectCount}",
                "إجمالي فقد الباكت", "${s.totalTimeouts}",
                "إجمالي وقت الانقطاع", Fmt.duration(s.totalDowntimeSec),
                "أطول فترة انقطاع", Fmt.duration(s.longestDowntimeSec)
            )
        )
        // BOM so Excel opens the Arabic text correctly.
        return "﻿" + rows.joinToString(CRLF) { r ->
            r.joinToString(",") { c -> Q + c.replace(Q, Q + Q) + Q }
        }
    }

    fun txt(): String {
        val s = MonitorState
        val outs = s.outageSnapshot()
        val logs = s.snapshot()
        val bar = "===================================================================="
        val lines = ArrayList<String>()
        lines += bar
        lines += "   🌐 تقرير حالة وانقطاعات الإنترنت (ISP Technical Outage Report)"
        lines += "   مراقب النت الذكي v4.4 - (By Mahmoud_Kamal)"
        lines += "   تاريخ استخراج التقرير: ${Fmt.date()} - ${Fmt.time()}"
        lines += bar
        lines += ""
        lines += "[ أولاً: ملخص إحصائيات الخط ]"
        lines += "- إجمالي مرات الفصل الكامل    : ${s.disconnectCount} مرة"
        lines += "- إجمالي فقد الباكت (Timeouts): ${s.totalTimeouts} باكت"
        lines += "- مدة آخر انقطاع مسجل         : ${Fmt.duration(s.lastDownSec)} " +
            "(${s.lastDownStart.ifEmpty { "--" }} -> ${s.lastDownEnd.ifEmpty { "--" }})"
        lines += "- أطول فترة انقطاع متواصلة    : ${Fmt.duration(s.longestDowntimeSec)}"
        lines += "- إجمالي وقت انقطاع الخدمة    : ${Fmt.duration(s.totalDowntimeSec)} (${s.totalDowntimeSec} ثانية)"
        lines += ""
        lines += bar
        lines += "[ ثانياً: جدول سجل فترات الانقطاع التفصيلي (${outs.size} سجل) ]"
        lines += bar
        if (outs.isEmpty()) {
            lines += "لا توجد انقطاعات كاملة مسجلة في هذه الفترة."
        } else {
            outs.forEachIndexed { idx, o ->
                val endLabel = if (o.ongoing) "جاري حتى الآن" else o.endTime
                lines += "#${outs.size - idx} | التاريخ: ${o.date} | من: ${o.startTime} | إلى: $endLabel | " +
                    "المدة: ${Fmt.duration(o.durationSec)} (${o.durationSec}s) | البنج بعد العودة: ${o.recoveryPing}"
            }
        }
        lines += ""
        lines += bar
        lines += "[ ثالثاً: السجل الزمني للأحداث وفقد الباكت (آخر ${logs.size} حدث) ]"
        lines += bar
        for (l in logs) lines += "[${l.date} - ${l.time}] ${l.msg}"
        return "﻿" + lines.joinToString(CRLF)
    }
}
