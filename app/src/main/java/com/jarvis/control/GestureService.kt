package com.jarvis.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Watches the front camera for your hand, on the phone only. Matches it against your trained gestures
 * and sends just the gesture NAME to the laptop. No images leave the phone.
 * A notification stays up the whole time the camera is on.
 */
class GestureService : LifecycleService() {

    companion object {
        private const val CHANNEL = "jarvis_gestures"
        private const val NOTIF_ID = 77
        private const val STABLE_FRAMES = 3      // the same gesture this many frames in a row
        private const val COOLDOWN_MS = 1200L   // after a send, wait this long before the next

        @Volatile var running = false
        @Volatile var starting = false
        @Volatile var sendToLaptop = false
        /** Pointer mode: the index finger moves the laptop mouse, a pinch presses it. */
        @Volatile var pointerMode = false

        /** Set by the gesture screen to get each frame's hand (null = no hand). Called on the main thread. */
        @Volatile var onHand: ((List<FloatArray>?) -> Unit)? = null

        /** Set by the gesture screen to hear about status changes. Called on the main thread. */
        @Volatile var onStatus: ((String, String) -> Unit)? = null

        /** The latest status lines: "camera" (what the phone camera is doing) and "laptop" (what the laptop said). */
        val status = ConcurrentHashMap<String, String>()

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GestureService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GestureService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var analysisExecutor: ExecutorService? = null
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var lastLabel: String? = null
    private var streak = 0
    private var lastSent = 0L
    private var lastTs = 0L
    private val trackExec = Executors.newSingleThreadExecutor()
    private val trackBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastScore = 0f
    private var lastTrackSent = 0L
    private var pinching = false
    private var failed = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notice = buildNotification("Starting the camera...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notice)
        }
        if (running || starting) return START_STICKY
        starting = true
        val prefs = getSharedPreferences("jarvis_control", MODE_PRIVATE)
        sendToLaptop = prefs.getBoolean("gesture_send", false)
        pointerMode = prefs.getBoolean("gesture_pointer", false)
        say("camera", "Starting...")
        analysisExecutor = Executors.newSingleThreadExecutor()
        try {
            buildLandmarker()
            bindCamera()
            running = true
        } catch (e: Exception) {
            failed = true
            say("camera", "Camera failed: ${e.message}")
            stopSelf()
        }
        starting = false
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        running = false
        sendToLaptop = false
        pointerMode = false
        onHand = null
        if (!failed) {
            status["camera"] = "Off"
            main.post { onStatus?.invoke("camera", "Off") }
        }
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // already unbound
        }
        try {
            landmarker?.close()
        } catch (e: Exception) {
            // already closed
        }
        analysisExecutor?.shutdownNow()
        trackExec.shutdownNow()
        super.onDestroy()
    }

    /** One status line for the gesture screen. Only a change is shown, so the screen is not redrawn for nothing. */
    private fun say(what: String, text: String) {
        if (status.put(what, text) == text) return
        if (what == "camera") updateNotice(text)
        main.post { onStatus?.invoke(what, text) }
    }

    private fun buildLandmarker() {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ ->
                lastScore = result.handedness().firstOrNull()?.firstOrNull()?.score() ?: 0f
                val hand = result.landmarks().firstOrNull()?.map { floatArrayOf(it.x(), it.y(), it.z()) }
                onFrame(hand)
            }
            .setErrorListener { e -> say("camera", "Hand tracking error: ${e.message}") }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor!!) { frame -> detect(frame) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                say("camera", "On. Show your hand to the camera.")
            } catch (e: Exception) {
                failed = true
                say("camera", "No front camera: ${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Camera frame -> upright bitmap -> hand tracker. Always closes the frame. */
    private fun detect(frame: ImageProxy) {
        try {
            val bmp = frame.toBitmap()
            val m = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
            val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            landmarker?.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            // skip this frame
        } finally {
            frame.close()
        }
    }

    private fun onFrame(hand: List<FloatArray>?) {
        val cb = onHand
        if (cb != null) main.post { cb(hand) }
        if (pointerMode) sendTrack(hand)
        if (!sendToLaptop) return
        val label = if (hand != null) GestureTrainer.shared(this).classify(hand).first else null
        if (label != null && label == lastLabel) {
            streak++
        } else {
            lastLabel = label
            streak = if (label != null) 1 else 0
        }
        val now = System.currentTimeMillis()
        if (label != null && streak >= STABLE_FRAMES && now - lastSent > COOLDOWN_MS) {
            lastSent = now
            streak = 0
            send(label)
        }
    }

    /** Pointer mode: the index fingertip moves the laptop mouse; a pinch presses it. About 10 frames a second. */
    private fun sendTrack(hand: List<FloatArray>?) {
        val now = System.currentTimeMillis()
        if (now - lastTrackSent < 100L) return
        if (!trackBusy.compareAndSet(false, true)) return      // the last frame is still on its way
        lastTrackSent = now
        val body = JSONObject()
        if (hand != null && hand.size >= 21) {
            val tip = hand[8]
            // The front camera image is mirrored, so flip x: moving your hand right moves the pointer right.
            body.put("tip", org.json.JSONArray().put((1.0 - tip[0]).toDouble()).put(tip[1].toDouble()))
            val size = dist(hand[0], hand[9]).coerceAtLeast(1e-4f)
            val gap = dist(hand[4], hand[8]) / size
            if (!pinching && gap < 0.25f) pinching = true
            else if (pinching && gap > 0.40f) pinching = false
            body.put("pinch", pinching)
            body.put("conf", lastScore.toDouble())
            val label = GestureTrainer.shared(this).classify(hand).first
            if (label != null) body.put("label", label)
        } else {
            pinching = false
            body.put("tip", JSONObject.NULL)
            body.put("pinch", false)
            body.put("conf", 0.0)
        }
        trackExec.execute {
            try {
                val reply = LaptopApi.call(this, "POST", "/gesture/track", body, 1500)
                if (reply.has("error")) {
                    say("laptop", "Pointer: " + reply.optString("error"))
                } else {
                    say("laptop", "Pointer: " + reply.optString("note", "ok"))
                }
            } catch (e: Exception) {
                say("laptop", "Pointer: no reply from the laptop")
            } finally {
                trackBusy.set(false)
            }
        }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Sends only the gesture name. The laptop decides what it does (see /gesture on the laptop). */
    private fun send(label: String) {
        val context = getSharedPreferences("jarvis_control", MODE_PRIVATE).getString("gesture_context", "global") ?: "global"
        Thread {
            val reply = LaptopApi.call(this, "POST", "/gesture",
                JSONObject().put("label", label).put("context", context), 3000)
            val text = reply.optString("note", reply.optString("error", "sent"))
            say("laptop", "Gesture '$label': $text")
        }.start()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, GestureActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jarvis gestures")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotice(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Gesture camera", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}
