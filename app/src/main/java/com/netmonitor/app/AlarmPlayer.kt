package com.netmonitor.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlin.math.log10

/**
 * Plays the alert tones on the ALARM stream. This does not depend on notification-channel
 * sound settings (which many phone makers silence), so the tone is heard whenever the
 * alarm volume is up.
 *
 * Loudness follows the 0..300 % slider:
 *   0 %        muted
 *   1..100 %   player volume
 *   101..300 % player at full volume + LoudnessEnhancer gain (300 % = about +9.5 dB),
 *              and the system alarm volume is raised to maximum.
 */
object AlarmPlayer {
    private const val MAX_LOOP_MS = 10 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }
    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null

    /** Raw resource currently playing, or 0. Used by the UI for the "stop tone" buttons. */
    @Volatile
    var playingRes: Int = 0
        private set

    @Synchronized
    fun play(ctx: Context, res: Int, loop: Boolean) {
        stop()
        val app = ctx.applicationContext
        val pct = Prefs(app).volumePct
        if (pct <= 0) return // muted with the slider
        ensureAudible(app, pct)
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            app.resources.openRawResourceFd(res).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            mp.isLooping = loop
            mp.setOnCompletionListener { m -> release(m) }
            mp.setOnErrorListener { m, _, _ -> release(m); true }
            mp.prepare()
            player = mp
            playingRes = res
            applyBoost(mp, pct)
            mp.start()
            if (loop) main.postDelayed(stopper, MAX_LOOP_MS)
        } catch (_: Exception) {
            player = null
            playingRes = 0
        }
    }

    /** Live update while a tone is playing (slider moved). */
    @Synchronized
    fun setBoost(pct: Int) {
        val mp = player ?: return
        applyBoost(mp, pct)
    }

    private fun applyBoost(mp: MediaPlayer, pct: Int) {
        val base = pct.coerceIn(0, 100) / 100f
        try { mp.setVolume(base, base) } catch (_: Exception) { }
        try {
            if (pct > 100) {
                val e = enhancer ?: LoudnessEnhancer(mp.audioSessionId).also { enhancer = it }
                e.setTargetGain((2000 * log10(pct / 100.0)).toInt()) // millibels
                e.setEnabled(true)
            } else {
                enhancer?.setEnabled(false)
            }
        } catch (_: Throwable) { }
    }

    private fun releaseEnhancer() {
        try { enhancer?.release() } catch (_: Throwable) { }
        enhancer = null
    }

    @Synchronized
    private fun release(m: MediaPlayer) {
        try { m.release() } catch (_: Exception) { }
        if (player === m) {
            player = null
            playingRes = 0
            releaseEnhancer()
        }
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(stopper)
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        player = null
        playingRes = 0
        releaseEnhancer()
    }

    /** Boost needs the system alarm volume at max; an alarm at volume 0 is useless anyway. */
    private fun ensureAudible(app: Context, pct: Int) {
        try {
            val am = app.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (pct > 100) {
                if (cur < max) am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            } else if (cur == 0) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.7).toInt().coerceAtLeast(1), 0)
            }
        } catch (_: Exception) { }
    }
}
