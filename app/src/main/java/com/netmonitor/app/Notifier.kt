package com.netmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifier {
    // Channel settings cannot be changed after creation, so bump the suffix when they change.
    const val CH_STATUS = "status_v1"
    const val CH_DOWN = "down_v2"
    const val CH_UP = "up_v2"
    const val CH_IP = "ip_v1"

    const val ID_FG = 1
    const val ID_DOWN = 2
    const val ID_UP = 3
    const val ID_IP = 4

    const val ACTION_MUTE = "com.netmonitor.app.MUTE"
    const val ACTION_STOP = "com.netmonitor.app.STOP"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Old channels had a notification sound that many phones silence: sounds are now
        // played by AlarmPlayer on the alarm stream, the channels only pop up + vibrate.
        nm.deleteNotificationChannel("down_v1")
        nm.deleteNotificationChannel("up_v1")

        val status = NotificationChannel(
            CH_STATUS, "حالة المراقبة", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إشعار ثابت يبقي المراقبة شغالة في الخلفية"
            setShowBadge(false)
        }

        val down = NotificationChannel(
            CH_DOWN, "تنبيه فصل النت", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "إشعار منبثق عند فصل الانترنت (النغمة تُشغَّل من التطبيق)"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
        }

        val up = NotificationChannel(
            CH_UP, "تنبيه رجوع النت", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "إشعار منبثق عند رجوع الانترنت (النغمة تُشغَّل من التطبيق)"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
        }

        val ip = NotificationChannel(
            CH_IP, "تغيّر عنوان الـ IP", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "إشعار عند تغيّر عنوان الـ IP العام"
        }

        nm.createNotificationChannel(status)
        nm.createNotificationChannel(down)
        nm.createNotificationChannel(up)
        nm.createNotificationChannel(ip)
    }

    /** The public IP address changed (same message as the web version). */
    fun ipChanged(ctx: Context, ip: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(ctx, CH_IP)
            .setSmallIcon(R.drawable.ic_net)
            .setContentTitle("🌐 تغيّر عنوان الـ IP")
            .setContentText("العنوان الجديد: $ip")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        nm.notify(ID_IP, n)
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionIntent(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** The persistent notification required for a foreground service. */
    fun status(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_net)
            .setContentTitle("مراقب النت الذكي")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "إيقاف", actionIntent(ctx, ACTION_STOP, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun disconnect(ctx: Context, threshold: Int, repeat: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Sound first: it must play even if the notification is hidden or silenced.
        AlarmPlayer.play(ctx, R.raw.disconnect, repeat)
        val n = NotificationCompat.Builder(ctx, CH_DOWN)
            .setSmallIcon(R.drawable.ic_net)
            .setContentTitle("🚨 الانترنت فصل!")
            .setContentText("فشل الاتصال $threshold مرات متتالية")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(actionIntent(ctx, ACTION_MUTE, 3))
            .addAction(0, "🔇 إسكات", actionIntent(ctx, ACTION_MUTE, 1))
            .build()
        nm.notify(ID_DOWN, n)
    }

    fun reconnect(ctx: Context, downSeconds: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_DOWN)
        AlarmPlayer.play(ctx, R.raw.reconnect, false) // also stops the looping disconnect alarm
        val n = NotificationCompat.Builder(ctx, CH_UP)
            .setSmallIcon(R.drawable.ic_net)
            .setContentTitle("✅ الانترنت رجع!")
            .setContentText("استمر الانقطاع لمدة ${Fmt.duration(downSeconds)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(openApp(ctx))
            .build()
        nm.notify(ID_UP, n)
    }

    fun mute(ctx: Context) {
        AlarmPlayer.stop()
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_DOWN)
    }
}
