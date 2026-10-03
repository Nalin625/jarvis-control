package com.jarvis.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The newest screen frame (as a JPEG) plus the settings the laptop viewer asked for.
 * JarvisServer serves it at /screen.jpg; ScreenShareService fills it.
 */
object ScreenShareState {
    @Volatile var sharing = false
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var lastRequestMs = 0L   // when the laptop viewer last asked for a frame
    @Volatile var quality = 55         // JPEG quality, chosen by the viewer
    @Volatile var fps = 10             // max frames per second, chosen by the viewer

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var frame: ByteArray? = null
    // starts at the current time so ids keep growing even if the app process restarts
    private var frameId = System.currentTimeMillis()

    fun publish(bytes: ByteArray) {
        lock.withLock {
            frame = bytes
            frameId += 1
            cond.signalAll()
        }
    }

    fun reset() {
        lock.withLock {
            sharing = false
            frame = null
            cond.signalAll()
        }
    }

    /** Waits up to timeoutMs for a frame newer than [after]. Returns (id, jpeg) or null. */
    fun awaitFrame(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? {
        return lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (frameId <= after && sharing && remaining > 0L) {
                remaining = cond.awaitNanos(remaining)
            }
            val f = frame
            if (f != null && frameId > after) Pair(frameId, f) else null
        }
    }
}

/**
 * Mirrors this phone's screen (MediaProjection) into small JPEG frames for the laptop.
 * Must be started from MainActivity right after the user approves the system
 * "start recording or casting" prompt; that approval can't be given remotely.
 *
 * Battery/CPU friendly: with nobody watching it only refreshes one frame per second.
 */
class ScreenShareService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_screen"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var bitmap: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(150_000)
    private var lastEncodeMs = 0L
    private var pumpScheduled = false
    private var w = 0
    private var h = 0

    @Volatile private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis screen projection")
            .setContentText("Your phone screen is being shared with your laptop")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, notification)
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || code == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        teardown()
        val gen = generation

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) {
            stopSelf()
            return
        }
        projection = proj

        val ht = HandlerThread("jarvis-screen")
        ht.start()
        thread = ht
        val hd = Handler(ht.looper)
        handler = hd

        // Android 14 requires a callback to be registered before the display is created.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (gen == generation) stopSelf()   // user tapped the system "stop sharing" chip
            }
        }, hd)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val realW = dm.widthPixels
        val realH = dm.heightPixels
        // longest side capped at 1280px: sharp enough to read, small enough for WiFi
        val scale = min(1f, 1280f / max(realW, realH).toFloat())
        w = max(2, (realW * scale).roundToInt() and 1.inv())
        h = max(2, (realH * scale).roundToInt() and 1.inv())
        val dpi = max(72, (dm.densityDpi * scale).roundToInt())

        val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = rd
        rd.setOnImageAvailableListener({ schedulePump() }, hd)
        display = proj.createVirtualDisplay(
            "jarvis-screen", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            rd.surface, null, hd
        )

        ScreenShareState.width = w
        ScreenShareState.height = h
        ScreenShareState.sharing = true
    }

    /** Runs on the capture thread. Limits how often we encode, but never drops the LAST frame. */
    private fun schedulePump() {
        val hd = handler ?: return
        if (pumpScheduled) return
        val now = SystemClock.elapsedRealtime()
        val watching = now - ScreenShareState.lastRequestMs < 4000
        val interval = if (watching) 1000L / max(1, ScreenShareState.fps) else 1000L
        val wait = max(0L, lastEncodeMs + interval - now)
        pumpScheduled = true
        hd.postDelayed({
            pumpScheduled = false
            pump()
        }, wait)
    }

    private fun pump() {
        val rd = reader ?: return
        val image = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * w
            val bmpW = w + rowPadding / pixelStride

            val existing = bitmap
            val bmp: Bitmap = if (existing != null && existing.width == bmpW && existing.height == h) {
                existing
            } else {
                Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            }
            bmp.copyPixelsFromBuffer(plane.buffer)

            // some phones pad each row; crop the padding away before encoding
            val visible = if (bmpW == w) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
            jpeg.reset()
            visible.compress(Bitmap.CompressFormat.JPEG, ScreenShareState.quality, jpeg)
            if (visible !== bmp) visible.recycle()

            ScreenShareState.publish(jpeg.toByteArray())
            lastEncodeMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            // skip this frame, the next one will try again
        } finally {
            image.close()
        }
    }

    private fun teardown() {
        generation += 1   // makes callbacks from the previous session harmless
        try { display?.release() } catch (e: Exception) { }
        display = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        bitmap = null
        pumpScheduled = false
        ScreenShareState.reset()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen projection", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
