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
        @Volatile var sendToLaptop = false

        /** Set by the trainer screen to get each frame's hand (null = no hand). Called on the main thread. */
        @Volatile var onHand: ((List<FloatArray>?) -> Unit)? = null

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

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notice = buildNotification("Watching for your gestures")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notice)
        }
        if (running) return START_STICKY
        sendToLaptop = getSharedPreferences("jarvis_control", MODE_PRIVATE).getBoolean("gesture_send", false)
        analysisExecutor = Executors.newSingleThreadExecutor()
        try {
            buildLandmarker()
            bindCamera()
            running = true
        } catch (e: Exception) {
            updateNotice("Gesture camera failed: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        running = false
        sendToLaptop = false
        onHand = null
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
        super.onDestroy()
    }

    private fun buildLandmarker() {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ ->
                val hand = result.landmarks().firstOrNull()?.map { floatArrayOf(it.x(), it.y(), it.z()) }
                onFrame(hand)
            }
            .setErrorListener { e -> updateNotice("Hand tracking error: ${e.message}") }
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
            } catch (e: Exception) {
                updateNotice("No front camera available: ${e.message}")
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

    /** Sends only the gesture name. The laptop decides what it does (see /gesture on the laptop). */
    private fun send(label: String) {
        val context = getSharedPreferences("jarvis_control", MODE_PRIVATE).getString("gesture_context", "global") ?: "global"
        Thread {
            val reply = LaptopApi.call(this, "POST", "/gesture",
                JSONObject().put("label", label).put("context", context), 3000)
            val text = reply.optString("note", reply.optString("error", "sent"))
            updateNotice("$label -> $text")
        }.start()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, GestureActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jarvis gestures: camera is ON")
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
