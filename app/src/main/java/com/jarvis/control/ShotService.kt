package com.jarvis.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** What one photo request ends with: the JPEG bytes and where the phone kept a copy, or why it failed. */
class ShotResult(val jpeg: ByteArray?, val width: Int, val height: Int, val savedAs: String, val error: String?)

/**
 * The laptop's "click instant". The HTTP route asks for one photo and waits here until ShotService
 * delivers it. One photo at a time: a second request while one is running is refused, not queued.
 */
object ShotBridge {
    private val slot = Semaphore(1)
    @Volatile private var waiting: CompletableFuture<ShotResult>? = null

    fun take(ctx: Context, facing: String, maxSide: Int, quality: Int): ShotResult {
        if (!slot.tryAcquire(1, TimeUnit.SECONDS)) {
            return ShotResult(null, 0, 0, "", "a photo is already being taken, try again in a moment")
        }
        try {
            val future = CompletableFuture<ShotResult>()
            waiting = future
            val intent = Intent(ctx, ShotService::class.java)
                .putExtra(ShotService.EXTRA_FACING, facing)
                .putExtra(ShotService.EXTRA_MAX_SIDE, maxSide)
                .putExtra(ShotService.EXTRA_QUALITY, quality)
            ContextCompat.startForegroundService(ctx, intent)
            return try {
                future.get(16, TimeUnit.SECONDS)
            } catch (e: Exception) {
                ShotResult(null, 0, 0, "", "the phone did not finish the photo in time")
            } finally {
                waiting = null
            }
        } catch (e: Exception) {
            return ShotResult(null, 0, 0, "", e.message ?: "could not start the camera service")
        } finally {
            slot.release()
        }
    }

    fun deliver(result: ShotResult) {
        waiting?.complete(result)
    }
}

/**
 * Takes one still with Camera2 and no preview. It runs as a camera foreground service, which is what lets
 * Android open the camera while the app is in the background. It never runs silently: a notification is
 * posted when the photo is saved, and the laptop's voice reply says so too.
 */
class ShotService : Service() {

    companion object {
        const val EXTRA_FACING = "facing"
        const val EXTRA_MAX_SIDE = "max_side"
        const val EXTRA_QUALITY = "quality"
        private const val CHANNEL_RUN = "jarvis_camera_run"
        private const val CHANNEL_DONE = "jarvis_camera_done"
        private const val NOTIF_RUN = 7701
        private const val NOTIF_DONE = 7702
        private const val WATCHDOG_MS = 14000L
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notice = NotificationCompat.Builder(this, CHANNEL_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jarvis")
            .setContentText("Taking a photo for the laptop")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_RUN, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_RUN, notice)
        }

        val facing = intent?.getStringExtra(EXTRA_FACING) ?: "back"
        val maxSide = intent?.getIntExtra(EXTRA_MAX_SIDE, 1600) ?: 1600
        val quality = intent?.getIntExtra(EXTRA_QUALITY, 88) ?: 88

        if (thread == null) {
            val ht = HandlerThread("JarvisShot")
            ht.start()
            thread = ht
            handler = Handler(ht.looper)
        }
        val h = handler ?: return START_NOT_STICKY
        h.post { start(facing, maxSide, quality) }
        h.postDelayed({ finish(null, 0, 0, "", "the camera took too long") }, WATCHDOG_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseHardware()
        super.onDestroy()
    }

    private fun start(facing: String, maxSide: Int, quality: Int) {
        val wantFacing = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT
                         else CameraCharacteristics.LENS_FACING_BACK
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == wantFacing
            }
        } catch (e: Exception) {
            null
        }
        if (id == null) {
            finish(null, 0, 0, "", "this phone has no $facing camera")
            return
        }
        val chars = cm.getCameraCharacteristics(id)
        val size = pickSize(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG), maxSide) ?: Size(1280, 960)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        configure(device, size, sensor, quality)
                    } catch (e: Exception) {
                        finish(null, 0, 0, "", "could not set up the camera: ${e.message}")
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    finish(null, 0, 0, "", "the camera disconnected")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    val busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                        error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                    finish(null, 0, 0, "", if (busy)
                        "the camera is busy (the gesture camera or another app has it)"
                    else "camera error $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            finish(null, 0, 0, "", "camera permission is missing: open Jarvis Control once and allow the camera")
        } catch (e: Exception) {
            finish(null, 0, 0, "", "could not open the camera: ${e.message}")
        }
    }

    /** The biggest JPEG size that fits inside maxSide; if none fits, the smallest one. */
    private fun pickSize(sizes: Array<Size>?, maxSide: Int): Size? {
        val list = sizes?.toList() ?: return null
        val fit = list.filter { maxOf(it.width, it.height) <= maxSide }
        return fit.maxByOrNull { it.width.toLong() * it.height }
            ?: list.minByOrNull { it.width.toLong() * it.height }
    }

    private fun configure(device: CameraDevice, size: Size, sensor: Int, quality: Int) {
        val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        reader = rd
        rd.setOnImageAvailableListener(ImageReader.OnImageAvailableListener { r ->
            val image = r.acquireLatestImage()
            if (image != null) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    onPhoto(bytes, size.width, size.height)
                } finally {
                    image.close()
                }
            }
        }, handler)

        device.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                // give auto-exposure and focus a moment to settle before the shutter
                handler?.postDelayed({ shoot(device, s, rd, sensor, quality) }, 700)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                finish(null, 0, 0, "", "could not start the camera session")
            }
        }, handler)
    }

    private fun shoot(device: CameraDevice, s: CameraCaptureSession, rd: ImageReader, sensor: Int, quality: Int) {
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rd.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, sensor)
                set(CaptureRequest.JPEG_QUALITY, quality.toByte())
            }.build()
            s.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(sess: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    finish(null, 0, 0, "", "the shutter failed (reason ${failure.reason})")
                }
            }, handler)
        } catch (e: Exception) {
            finish(null, 0, 0, "", "could not take the photo: ${e.message}")
        }
    }

    private fun onPhoto(bytes: ByteArray, w: Int, h: Int) {
        if (finished) return
        val saved = try { saveCopy(bytes) } catch (e: Exception) { "" }
        finish(bytes, w, h, saved, null)
    }

    /** Keeps a copy in the phone's gallery under Pictures/Jarvis (Android 10+), or in the app's folder below that. */
    private fun saveCopy(bytes: ByteArray): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "jarvis-$stamp.jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return ""
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "Pictures/Jarvis/$name"
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Jarvis")
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            file.absolutePath
        }
    }

    private fun finish(jpeg: ByteArray?, w: Int, h: Int, saved: String, error: String?) {
        if (finished) return
        finished = true
        ShotBridge.deliver(ShotResult(jpeg, w, h, saved, error))
        if (jpeg != null) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val note = NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Jarvis took a photo")
                .setContentText(if (saved.isEmpty()) "Sent to the laptop" else "Saved: $saved")
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_DONE, note)
        }
        handler?.post {
            releaseHardware()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            thread?.quitSafely()
        } ?: stopSelf()
    }

    private fun releaseHardware() {
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { camera?.close() } catch (e: Exception) { }
        camera = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Camera for Jarvis", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Photos from Jarvis", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
