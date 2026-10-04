package com.jarvis.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import java.util.Locale

/** "Find my phone": rings the ALARM stream at full volume (even in silent mode) until stopped. */
object FindPhone {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var ringing = false

    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    @Synchronized
    fun start(ctx: Context, seconds: Int = 45) {
        stop(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        mp.setDataSource(ctx, uri)
        mp.isLooping = true
        mp.prepare()
        mp.start()
        player = mp
        ringing = true
        vibrator(ctx).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
        handler.postDelayed({ stop(ctx) }, seconds * 1000L)
    }

    @Synchronized
    fun stop(ctx: Context) {
        handler.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (e: Exception) { /* already stopped */ }
        player?.release()
        player = null
        if (ringing) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (savedVolume >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            vibrator(ctx).cancel()
        }
        ringing = false
    }
}

/** Text-to-speech on the phone ("speak on phone ..."). */
object PhoneTts {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    @Synchronized
    fun speak(ctx: Context, text: String) {
        val t = tts
        if (t != null && ready) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
            return
        }
        pending = text
        if (t == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                synchronized(this) {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        tts?.language = Locale.getDefault()
                        pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "jarvis") }
                    }
                    pending = null
                }
            }
        }
    }
}

object PhoneMedia {
    /** key: play_pause | next | prev | stop  ->  whatever app is playing music reacts. */
    fun send(ctx: Context, key: String): Boolean {
        val code = when (key) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return false
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }
}

object PhoneRinger {
    /** Returns null on success, or an error message. */
    fun set(ctx: Context, mode: String): String? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val target = when (mode) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return "unknown mode"
        }
        return try {
            am.ringerMode = target
            if (am.ringerMode != target) "Android refused: allow Do Not Disturb access for Jarvis Control, or use vibrate" else null
        } catch (e: SecurityException) {
            "Silent needs Do Not Disturb access (Settings > Apps > Special access). Vibrate works without it."
        }
    }
}

object PhoneLocation {
    /** Best last-known fix, or an error string in .second. */
    fun lastFix(ctx: Context): Pair<Location?, String?> {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return Pair(null, "Location permission not granted. Open Jarvis Control and allow location.")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || l.time > best.time) best = l
            } catch (e: SecurityException) {
                // provider not allowed with this permission level
            } catch (e: Exception) {
                // provider missing on this device
            }
        }
        return if (best == null) {
            Pair(null, "No location yet. Set Jarvis Control's location permission to \"Allow all the time\" in Android settings, and turn Location on.")
        } else Pair(best, null)
    }
}
