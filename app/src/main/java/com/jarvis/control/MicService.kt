package com.jarvis.control

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * The phone IS the microphone. This service listens (speech-to-text on the phone),
 * waits for the wake word ("jarvis ..."), and sends ONLY the finished command text to the
 * laptop (POST /voice/command). The laptop does no listening and keeps no audio open.
 */
class MicService : Service() {

    companion object {
        private const val CHANNEL = "jarvis_mic"
        @Volatile var alive = false
        @Volatile var listening = false      // "always listen for the wake word"
        @Volatile var paused = false         // paused while the startup-voice recorder owns the mic
        @Volatile private var instance: MicService? = null

        fun hasPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Call while the app is on screen (Android blocks starting a mic service from the background). */
        fun start(ctx: Context): Boolean {
            if (!hasPermission(ctx)) return false
            return try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MicService::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun setListening(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE).edit().putBoolean("wake_listen", on).apply()
            listening = on
            if (on && !alive) start(ctx)
            instance?.refresh()
        }

        /** Listen for one sentence (laptop mic button) and send it without needing the wake word. */
        fun listenOnce(ctx: Context) {
            if (!alive) start(ctx)
            instance?.requestOnce()
        }

        fun setPaused(p: Boolean) {
            paused = p
            instance?.refresh()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var once = false
    private var awaitUntil = 0L
    private var audio: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone microphone", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun note(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Jarvis phone mic")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun setNote(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(2, note(text))
        } catch (e: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = note("Ready")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(2, n)
        }
        alive = true
        instance = this
        listening = getSharedPreferences("jarvis_control", MODE_PRIVATE).getBoolean("wake_listen", false)
        refresh()
        return START_STICKY
    }

    fun refresh() {
        handler.post { update() }
    }

    fun requestOnce() {
        handler.post {
            once = true
            update()
        }
    }

    private fun update() {
        val want = (listening || once) && !paused
        if (want && !running) begin()
        if (!want && running) stopRec()
        if (!want && !paused) setNote("Idle")
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    private fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setNote("No speech service - install/update the Google app")
            once = false
            return
        }
        running = true
        muteBeep(true)
        setNote(if (once && !listening) "Listening for one command..." else "Listening for \"jarvis\"...")
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        try {
            recognizer?.startListening(recognizerIntent())
        } catch (e: Exception) {
            again(2000)
        }
    }

    private fun stopRec() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (e: Exception) {}
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        muteBeep(false)
    }

    private fun again(delayMs: Long) {
        if (!running) return
        handler.postDelayed({
            if (running) {
                try {
                    recognizer?.cancel()
                    recognizer?.startListening(recognizerIntent())
                } catch (e: Exception) {
                }
            }
        }, delayMs)
    }

    /** The recogniser beeps every time it restarts; silence the notification/system streams while listening. */
    private fun muteBeep(mute: Boolean) {
        val a = audio ?: return
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        try { a.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0) } catch (e: Exception) {}
        try { a.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0) } catch (e: Exception) {}
    }

    private fun handleText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val low = text.lowercase()
        if (once) {
            once = false
            send(text)
            return
        }
        val wake = getSharedPreferences("jarvis_control", MODE_PRIVATE).getString("wake_word", "jarvis") ?: "jarvis"
        val idx = low.indexOf(wake.lowercase())
        if (idx >= 0) {
            val cmd = low.substring(idx + wake.length).trim(' ', ',', '.', '!', '?')
            if (cmd.isEmpty()) {
                awaitUntil = System.currentTimeMillis() + 8000   // "jarvis" alone: the next sentence is the command
                setNote("Yes? Say your command...")
            } else {
                send(cmd)
            }
        } else if (System.currentTimeMillis() < awaitUntil) {
            awaitUntil = 0L
            send(text)
        }
        // anything else is background talk: ignored, never sent anywhere
    }

    private fun send(cmd: String) {
        setNote("Sent: $cmd")
        val app = applicationContext
        Thread {
            LaptopApi.call(app, "POST", "/voice/command", JSONObject().put("text", cmd), 5000)
        }.start()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    listening = false; once = false
                    stopRec()
                    setNote("Microphone not allowed")
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (once && !listening) once = false
                    if (listening || once) again(200) else stopRec()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> again(1500)
                else -> again(2500)
            }
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()
            if (text != null) handleText(text)
            if (listening || once) again(200) else stopRec()
        }
    }

    override fun onDestroy() {
        alive = false
        instance = null
        stopRec()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
