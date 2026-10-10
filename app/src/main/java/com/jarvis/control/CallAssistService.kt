package com.jarvis.control

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/**
 * Call assistant. Modes (pref "call_mode"): "off", "ask" (alert with a "Jarvis answers" button),
 * "auto" (Jarvis answers by itself after "call_delay" seconds unless you tap "I'll answer").
 *
 * Android does not let apps talk into a phone call, so this uses the speakerphone trick:
 * answer -> speaker on -> Jarvis speaks (TTS) through the speaker and the phone mic carries it to the caller;
 * the caller's voice comes out of the speaker and the phone mic + speech recognition hears it.
 * Call text processing is local-only; call data is never sent to the laptop.
 */
class CallAssistService : Service() {

    companion object {
        private const val CH_ON = "jarvis_call_on"
        private const val CH_ALERT = "jarvis_call_alert"
        const val ACT_ANSWER = "com.jarvis.control.CALL_ANSWER"
        const val ACT_MINE = "com.jarvis.control.CALL_MINE"
        const val ACT_DECLINE = "com.jarvis.control.CALL_DECLINE"
        const val ACT_TAKEOVER = "com.jarvis.control.CALL_TAKEOVER"

        @Volatile var alive = false
        @Volatile var inSession = false
        @Volatile private var instance: CallAssistService? = null

        val PERMS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )

        fun hasCorePerms(ctx: Context): Boolean = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO
        ).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

        fun mode(ctx: Context): String =
            ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE).getString("call_mode", "off") ?: "off"

        /** Call while the app is on screen (Android blocks starting a mic service from the background). */
        fun start(ctx: Context): Boolean {
            if (!hasCorePerms(ctx)) return false
            return try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, CallAssistService::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, CallAssistService::class.java)) } catch (e: Exception) { }
        }

        fun setMode(ctx: Context, m: String): Boolean {
            ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE).edit().putString("call_mode", m).apply()
            if (m == "off") { stop(ctx); return true }
            return if (alive) true else start(ctx)
        }

        /** Called by the notification listener for WhatsApp call notifications. */
        fun waPosted(sbn: StatusBarNotification) {
            val s = instance ?: return
            s.handler.post { s.onWa(sbn) }
        }

        fun waRemoved(key: String) {
            val s = instance ?: return
            s.handler.post { s.onWaGone(key) }
        }

        /** Called by JarvisInCallService (Jarvis is the phone's call manager): the reliable route. */
        fun telecomAdded(c: android.telecom.Call) {
            val s = instance
            if (s == null) { start(appCtx ?: return); return }
            s.handler.post { s.onTelecomAdded(c) }
        }
        fun telecomState(c: android.telecom.Call, st: Int) { instance?.let { s -> s.handler.post { s.onTelecomState(c, st) } } }
        fun telecomRemoved(c: android.telecom.Call) { instance?.let { s -> s.handler.post { s.onTelecomRemoved(c) } } }
        var appCtx: Context? = null
        fun isCallManager(ctx: Context): Boolean = try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == ctx.packageName
        } catch (e: Exception) { false }

        fun mineNow() { instance?.let { s -> s.handler.post { s.handler.removeCallbacks(s.autoAnswerRunnable()); s.cancelAlertPublic() } } }
        fun takeOverNow() { instance?.let { s -> s.handler.post { s.takeOver() } } }

        fun answerNow(): Boolean {
            val s = instance ?: return false
            if (!s.ringing) return false
            s.handler.post { s.answer() }
            return true
        }

        /** Talk to Jarvis exactly like a caller would, without a real call. */
        fun test(): Boolean {
            val s = instance ?: return false
            if (inSession) return false
            s.handler.post { s.beginSession(true) }
            return true
        }
    }

    val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Triple<String, String, Int>? = null
    private var recognizer: SpeechRecognizer? = null
    private var audio: AudioManager? = null

    var ringing = false
    private var announced = false
    private var jarvisAnswered = false
    private var callerNumber = ""
    private var callerName = ""

    private var testing = false
    private var callId = ""
    private var startMs = 0L
    private var noSpeech = 0
    private var errors = 0
    private var turns = 0
    private val history = ArrayList<Pair<String, String>>()
    private var usedLaptop = false

    var tcall: android.telecom.Call? = null
    @Volatile private var earBroken = false

    private var waMode = false
    private var waKey = ""
    private var waAnswer: PendingIntent? = null
    private var waHangup: PendingIntent? = null

    private val autoAnswer = Runnable { if (ringing) answer() }
    fun autoAnswerRunnable(): Runnable = autoAnswer
    fun cancelAlertPublic() = cancelAlert()

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (st) {
                TelephonyManager.EXTRA_STATE_RINGING -> if (tcall == null) onRinging(num)
                TelephonyManager.EXTRA_STATE_OFFHOOK -> if (tcall == null) onOffhook()
                TelephonyManager.EXTRA_STATE_IDLE -> if (tcall == null) onIdle()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_ON, "Call assistant", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("jarvis_call_summary", "Call summaries", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Incoming call (Jarvis)", NotificationManager.IMPORTANCE_HIGH))
        }
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(phoneReceiver, f)
        tts = TextToSpeech(this) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                val r = t.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { spoken(id ?: "") } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { handler.post { spoken(id ?: "") } }
                })
                ttsReady = true
                pendingSpeech?.let { speak(it.first, it.second, it.third); pendingSpeech = null }
            }
        }
    }

    private fun foregroundNote(text: String): Notification =
        NotificationCompat.Builder(this, CH_ON)
            .setContentTitle("Jarvis call assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = foregroundNote("Mode: " + mode(this).uppercase())
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(3, n)
        alive = true
        instance = this
        when (intent?.action) {
            ACT_ANSWER -> answer()
            ACT_MINE -> { handler.removeCallbacks(autoAnswer); cancelAlert() }
            ACT_DECLINE -> { handler.removeCallbacks(autoAnswer); cancelAlert(); declineCall() }
            ACT_TAKEOVER -> takeOver()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(phoneReceiver) } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        if (inSession) endSession()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        cancelAlert()
        alive = false
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ phone state
    private fun delaySec(): Int = getSharedPreferences("jarvis_control", MODE_PRIVATE).getInt("call_delay", 15)

    // ---- call manager (InCallService) route
    private fun numberOf(c: android.telecom.Call): String = c.details?.handle?.schemeSpecificPart.orEmpty()

    fun onTelecomAdded(c: android.telecom.Call) {
        tcall = c
        waMode = false
        if (c.state == android.telecom.Call.STATE_RINGING) onTelecomState(c, c.state)
    }

    fun onTelecomState(c: android.telecom.Call, st: Int) {
        if (tcall !== c) return
        when (st) {
            android.telecom.Call.STATE_RINGING -> {
                if (mode(this) == "off" || inSession) return
                if (!ringing) { ringing = true; announced = false; callerNumber = ""; callerName = "" }
                val num = numberOf(c)
                if (num.isNotBlank() && num != callerNumber) { callerNumber = num; callerName = lookupName(num) }
                ringCommon()
            }
            android.telecom.Call.STATE_ACTIVE -> {
                handler.removeCallbacks(autoAnswer); ringing = false; cancelAlert()
                if (jarvisAnswered && !inSession) beginSession(false)
            }
            android.telecom.Call.STATE_DISCONNECTED -> onTelecomRemoved(c)
        }
    }

    fun onTelecomRemoved(c: android.telecom.Call) {
        if (tcall !== c) return
        tcall = null
        handler.removeCallbacks(autoAnswer)
        ringing = false
        cancelAlert()
        if (inSession && !testing) endSession()
        jarvisAnswered = false
    }

    private fun setSpeaker(on: Boolean) {
        try { audio?.isSpeakerphoneOn = on } catch (e: Exception) { }
        try {
            JarvisInCallService.instance?.setAudioRoute(if (on) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE)
        } catch (e: Exception) { }
    }

    private fun onRinging(num: String?) {
        if (mode(this) == "off" || inSession || waMode) return
        if (!ringing) {
            ringing = true
            announced = false
            callerNumber = ""
            callerName = ""
        }
        if (!num.isNullOrBlank() && num != callerNumber) {
            callerNumber = num
            callerName = lookupName(num)
        }
        ringCommon()
    }

    private fun ringCommon() {
        showAlert()
        if (!announced) {
            announced = true
            if (mode(this) == "auto") handler.postDelayed(autoAnswer, delaySec() * 1000L)
        }
    }

    // ------------------------------------------------------------ WhatsApp calls (read from the call notification)
    private fun actionOf(n: Notification, vararg words: String): PendingIntent? {
        val acts = n.actions ?: return null
        for (a in acts) {
            val t = a.title?.toString()?.lowercase() ?: continue
            if (words.any { t.contains(it) }) return a.actionIntent
        }
        return null
    }

    fun onWa(sbn: StatusBarNotification) {
        if (mode(this) == "off") return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val answer = actionOf(n, "answer", "accept")
        val hang = actionOf(n, "hang up", "hangup", "end call", "leave")
        val isCall = n.category == Notification.CATEGORY_CALL || answer != null || hang != null
        if (!isCall) return
        if (answer != null && !inSession && !jarvisAnswered) {
            // a WhatsApp call is ringing
            if (waKey != sbn.key || !ringing) {
                waMode = true
                waKey = sbn.key
                ringing = true
                announced = false
                callerNumber = ""
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                callerName = "WhatsApp: " + (if (title.contains("call", true) && text.isNotBlank()) text else title)
            }
            waAnswer = answer
            if (hang != null) waHangup = hang
            ringCommon()
        } else if (waMode && jarvisAnswered && !inSession && answer == null) {
            // the call is now connected (the notification switched to "ongoing call")
            if (hang != null) waHangup = hang
            waKey = sbn.key
            ringing = false
            cancelAlert()
            beginSession(false)
        } else if (waMode && hang != null) {
            waHangup = hang
        }
    }

    fun onWaGone(key: String) {
        if (!waMode || key != waKey) return
        if (jarvisAnswered && !inSession) return   // the ringing notification is replaced by the ongoing-call one
        handler.removeCallbacks(autoAnswer)
        ringing = false
        cancelAlert()
        if (inSession && !testing) endSession()
        waMode = false
        waKey = ""
        waAnswer = null
        waHangup = null
        jarvisAnswered = false
    }

    private fun onOffhook() {
        if (waMode) return
        handler.removeCallbacks(autoAnswer)
        ringing = false
        cancelAlert()
        if (jarvisAnswered && !inSession) beginSession(false)
    }

    private fun onIdle() {
        if (waMode) return
        handler.removeCallbacks(autoAnswer)
        ringing = false
        cancelAlert()
        if (inSession && !testing) endSession()
        jarvisAnswered = false
    }

    fun answer() {
        if (!ringing) return
        handler.removeCallbacks(autoAnswer)
        if (!canUsePrivateSpeechRecognition()) {
            showAlert()
            android.widget.Toast.makeText(this, "On-device speech recognition is unavailable. Please answer normally.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (waMode) {
            try {
                waAnswer?.send()
                jarvisAnswered = true
                handler.postDelayed({ if (!inSession && waMode) { waMode = false; jarvisAnswered = false; ringing = false } }, 25000)
            } catch (e: Exception) {
            }
            cancelAlert()
            return
        }
        val tc = tcall
        if (tc != null) {
            try { tc.answer(0); jarvisAnswered = true } catch (e: Exception) { }
            cancelAlert()
            return
        }
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            jarvisAnswered = true
        } catch (e: SecurityException) {
            android.widget.Toast.makeText(this, "Allow Phone calls permission to let Jarvis handle calls.", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
        }
        cancelAlert()
    }

    /** DECLINE: reject the ringing call with the official Telecom API (WhatsApp: its own Hang up action). */
    private fun declineCall() {
        try {
            val tc = tcall
            if (tc != null) { tc.reject(false, null); ringing = false; return }
            if (waMode) { waHangup?.send(); waMode = false; ringing = false; return }
            if (Build.VERSION.SDK_INT >= 28) (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
        } catch (e: Exception) { }
        ringing = false
    }

    /** TAKE OVER: stop the AI conversation and leave the live call to the user (the call is NOT hung up). */
    fun takeOver() {
        if (!inSession) return
        val wasTest = testing
        endSession()
        if (!wasTest) setSpeaker(false)
        try { startForeground(3, foregroundNote("You took over the call"), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) } catch (e: Exception) { }
    }

    private fun hangUp() {
        if (testing) { endSession(); return }
        val tc = tcall
        if (tc != null) { try { tc.disconnect() } catch (e: Exception) { }; handler.postDelayed({ if (inSession) endSession() }, 3000); return }
        if (waMode) {
            try { waHangup?.send() } catch (e: Exception) { }
            handler.postDelayed({ if (inSession) endSession() }, 3000)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            }
        } catch (e: Exception) {
        }
        handler.postDelayed({ if (inSession) endSession() }, 3000)
    }

    private fun lookupName(number: String): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) ?: "" else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------ alert notification
    private fun showAlert() {
        val who = if (callerName.isNotBlank()) callerName else if (callerNumber.isNotBlank()) callerNumber else "Unknown number"
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val ans = PendingIntent.getService(this, 11, Intent(this, CallAssistService::class.java).setAction(ACT_ANSWER), flags)
        val mine = PendingIntent.getService(this, 12, Intent(this, CallAssistService::class.java).setAction(ACT_MINE), flags)
        val dec = PendingIntent.getService(this, 13, Intent(this, CallAssistService::class.java).setAction(ACT_DECLINE), flags)
        val auto = mode(this) == "auto"
        val canAssist = canUsePrivateSpeechRecognition()
        val builder = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call from $who")
            .setContentText(
                if (!canAssist) "On-device speech recognition unavailable. Answer normally."
                else if (auto) "Jarvis answers in ${delaySec()} s unless you tap I'll answer"
                else "Let Jarvis answer this call?"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
        if (canAssist) builder.addAction(0, "Let Jarvis handle", ans)
        val n = builder
            .addAction(0, "Talk myself", mine)
            .addAction(0, "Decline", dec)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(4, n) } catch (e: Exception) { }
    }

    private fun cancelAlert() {
        try { getSystemService(NotificationManager::class.java).cancel(4) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------ the conversation
    fun beginSession(test: Boolean) {
        if (inSession) return
        inSession = true
        testing = test
        callId = System.currentTimeMillis().toString()
        startMs = System.currentTimeMillis()
        noSpeech = 0
        errors = 0
        turns = 0
        history.clear()
        usedLaptop = false
        MicService.pauseMic(true)
        try {
            val tk = PendingIntent.getService(this, 14, Intent(this, CallAssistService::class.java).setAction(ACT_TAKEOVER), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val note = NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("JARVIS IS HANDLING THE CALL")
                .setContentText("Tap TAKE OVER to talk yourself")
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true).addAction(0, "TAKE OVER", tk).build()
            getSystemService(NotificationManager::class.java).notify(5, note)
        } catch (e: Exception) { }
        if (!test) {
            val am = audio
            setSpeaker(true)
            if (am != null) {
                try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) } catch (e: Exception) { }
            }
        }
        handler.postDelayed({
            if (inSession) {
                val hi = "Hello, this is Jarvis, the assistant. He can't take your call right now. Who is calling, and how can I help?"
                history.add(Pair("jarvis", hi))
                speak(hi, "greet", TextToSpeech.QUEUE_FLUSH)
            }
        }, if (test) 300L else 1200L)
    }

    private fun endSession() {
        if (!inSession) return
        inSession = false
        try { getSystemService(NotificationManager::class.java).cancel(5) } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        try { tts?.stop() } catch (e: Exception) { }
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
        if (!testing) setSpeaker(false)
        MicService.pauseMic(false)
        val who = if (testing) "Test call" else if (callerName.isNotBlank()) callerName else if (callerNumber.isNotBlank()) callerNumber else "Unknown number"
        val num = if (testing) "test" else callerNumber
        val secs = (System.currentTimeMillis() - startMs) / 1000
        val h = ArrayList(history)
        Thread {
            val summary = CallBrain.finish(applicationContext, who, num, secs, h)
            try {
                val n = NotificationCompat.Builder(applicationContext, "jarvis_call_summary")
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Call from $who")
                    .setContentText(summary.lineSequence().firstOrNull() ?: summary)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                    .setAutoCancel(true)
                    .build()
                getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt() + 100, n)
            } catch (e: Exception) { }
        }.start()
        testing = false
        jarvisAnswered = false
    }

    private fun speak(text: String, id: String, queue: Int) {
        val t = tts
        if (t == null || !ttsReady) { pendingSpeech = Triple(text, id, queue); return }
        t.speak(text, queue, Bundle(), id)
    }

    private fun spoken(id: String) {
        if (!inSession) return
        when (id) {
            "filler" -> {}
            "final" -> hangUp()
            else -> listen()
        }
    }

    private fun canUsePrivateSpeechRecognition(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun listen() {
        if (!inSession) return
        if (!canUsePrivateSpeechRecognition()) {
            speak("On-device speech recognition is unavailable. I cannot safely handle this call. Goodbye.", "final", TextToSpeech.QUEUE_FLUSH)
            return
        }
        try { recognizer?.destroy() } catch (e: Exception) { }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Sorry, I can't listen right now. Please call back later. Goodbye.", "final", TextToSpeech.QUEUE_FLUSH)
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(code: Int) { handler.post { heardNothing(code) } }
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                handler.post { if (text.isBlank()) heardNothing(SpeechRecognizer.ERROR_NO_MATCH) else heard(text) }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { r.startListening(i) } catch (e: Exception) { handler.postDelayed({ listen() }, 1000) }
    }

    private fun heardNothing(code: Int) {
        if (!inSession) return
        val silent = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (silent) {
            noSpeech++
            when {
                noSpeech >= 4 -> speak("I can't hear anything, so I'll hang up now. Goodbye.", "final", TextToSpeech.QUEUE_FLUSH)
                noSpeech == 2 -> speak("Hello? Are you still there?", "again", TextToSpeech.QUEUE_FLUSH)
                else -> handler.postDelayed({ listen() }, 300)
            }
        } else {
            errors++
            if (errors > 6) speak("Sorry, something is wrong on my side. Please call back later. Goodbye.", "final", TextToSpeech.QUEUE_FLUSH)
            else handler.postDelayed({ listen() }, 800)
        }
    }

    private fun heard(text: String) {
        if (!inSession) return
        noSpeech = 0
        turns++
        if (turns > 14) {
            speak("I have your message and will pass it on. Thank you, goodbye.", "final", TextToSpeech.QUEUE_FLUSH)
            return
        }
        val filler = Runnable { if (inSession) speak("One moment.", "filler", TextToSpeech.QUEUE_ADD) }
        handler.postDelayed(filler, 3500)
        history.add(Pair("caller", text))
        val snapshot = ArrayList(history)
        Thread {
            var reply = ""
            var end = false
            val local = CallBrain.reply(applicationContext, snapshot)
            if (local != null) {
                reply = local.first
                end = local.second
            }
            handler.post {
                handler.removeCallbacks(filler)
                if (!inSession) return@post
                if (reply.isBlank()) {
                    speak("Sorry, I am having trouble right now. Please call back later. Goodbye.", "final", TextToSpeech.QUEUE_ADD)
                } else {
                    history.add(Pair("jarvis", reply))
                    speak(reply, if (end) "final" else "reply", TextToSpeech.QUEUE_ADD)
                }
            }
        }.start()
    }
}
