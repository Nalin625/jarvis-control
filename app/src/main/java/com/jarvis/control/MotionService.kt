package com.jarvis.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Shared state for the motion watch (read by the phone's control server). */
object MotionBridge {
    @Volatile var running = false
    @Volatile var score = 0.0          // average brightness change between the last two looks (0-255)
    @Volatile var lastMotionAt = 0L    // uptime ms of the last look that showed movement
    @Volatile var lastAskAt = 0L       // when the laptop last asked; the watch stops when nobody has asked for a while
}

/**
 * Motion-first watch: the front camera is read at about 2 looks a second, but only a tiny grid of brightness values
 * is kept, compared with the previous look, and thrown away. No picture is stored or sent anywhere.
 * The laptop asks "moving?" and only then takes a real picture for its answer.
 */
class MotionService : LifecycleService() {
    companion object {
        private const val CHANNEL = "jarvis_motion"
        private const val NOTIF_ID = 7710
        private const val IDLE_STOP_MS = 120_000L
        private const val GRID_W = 32
        private const val GRID_H = 24
        private const val MOVE_THRESHOLD = 6.0

        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, MotionService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MotionService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var analysisExec: ExecutorService? = null
    private var prev: IntArray? = null
    private var lastLook = 0L
    private var provider: ProcessCameraProvider? = null

    private val idleCheck = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - MotionBridge.lastAskAt > IDLE_STOP_MS) stopSelf()
            else main.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jarvis")
            .setContentText("Watching for movement")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTIF_ID, notice)
        MotionBridge.running = true
        MotionBridge.lastAskAt = SystemClock.uptimeMillis()
        analysisExec = Executors.newSingleThreadExecutor()
        bindCamera()
        main.postDelayed(idleCheck, 15_000L)
    }

    override fun onDestroy() {
        main.removeCallbacks(idleCheck)
        try { provider?.unbindAll() } catch (_: Exception) { }
        analysisExec?.shutdownNow()
        MotionBridge.running = false
        MotionBridge.score = 0.0
        super.onDestroy()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(320, 240))
                    .build()
                analysis.setAnalyzer(analysisExec!!) { frame -> look(frame) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) {
                // no front camera, or the gesture camera is using it: the laptop falls back to its picture check
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun look(frame: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastLook < 400L) return          // about 2-3 looks a second is plenty
            lastLook = now
            val plane = frame.planes[0]
            val buf = plane.buffer
            val w = frame.width
            val h = frame.height
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val cur = IntArray(GRID_W * GRID_H)
            var i = 0
            for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
                val x = (gx * w) / GRID_W
                val y = (gy * h) / GRID_H
                val idx = y * rs + x * ps
                cur[i++] = if (idx >= 0 && idx < buf.limit()) (buf.get(idx).toInt() and 0xff) else 0
            }
            val p = prev
            if (p != null) {
                var sum = 0L
                for (k in cur.indices) sum += kotlin.math.abs(cur[k] - p[k])
                val s = sum.toDouble() / cur.size
                MotionBridge.score = s
                if (s >= MOVE_THRESHOLD) MotionBridge.lastMotionAt = now
            }
            prev = cur
        } catch (_: Exception) {
        } finally {
            frame.close()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis motion watch", NotificationManager.IMPORTANCE_MIN))
    }
}
