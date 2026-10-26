package com.jarvis.control

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Jarvis camera (phone). The phone's own maximum zoom, tap to focus, the rules pick the exposure,
 *  a burst of 5 shots keeps the sharpest, saved to Pictures/Jarvis. Learning mode remembers your exposure choices.
 *  Enhancer is the slot for an on-phone AI model. */
class CameraZoomActivity : ComponentActivity() {
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var maxZoom = 1f
    private var lastLuma = 128.0
    private var sceneName = "NORMAL"
    private var manualExposure = true
    private var currentIso = 200
    private var currentShutterNs = 8_333_333L   // 1/120 s
    private val work = Executors.newSingleThreadExecutor()
    private val enhancer: Enhancer = SmallSrEnhancer()
    private lateinit var learner: CameraLearning
    private lateinit var preview: PreviewView
    private lateinit var hud: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var evBar: SeekBar
    private lateinit var learnButton: Button
    private lateinit var modeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        learner = CameraLearning(this)
        preview = PreviewView(this)
        hud = TextView(this).apply {
            setTextColor(Color.rgb(0, 220, 255))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(24, 16, 24, 16)
        }
        zoomBar = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) applyZoom(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        evBar = SeekBar(this).apply {
            max = 4
            progress = 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) setExposure(p - 2, learn = true)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        learnButton = Button(this).apply {
            text = learnLabel()
            setOnClickListener {
                learner.enabled = !learner.enabled
                text = learnLabel()
                updateHud()
            }
        }
        modeButton = Button(this).apply {
            text = modeLabel()
            setOnClickListener {
                manualExposure = !manualExposure
                text = modeLabel()
                if (manualExposure) applyIsoShutter(currentIso, currentShutterNs) else clearManual()
                updateHud()
            }
        }
        val shutter = Button(this).apply {
            text = "SHOOT"
            setOnClickListener { takeBurst() }
        }
        val root = FrameLayout(this)
        root.addView(modeButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 60) })
        root.addView(preview,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(24, 48, 24, 24) })
        root.addView(zoomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(48, 0, 48, 300) })
        root.addView(evBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(48, 0, 48, 220) })
        root.addView(learnButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(24, 0, 0, 60) })
        root.addView(shutter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, 60) })
        setContentView(root)
        preview.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) focusAt(e.x, e.y)
            true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
        }
    }

    private fun learnLabel(): String = if (learner.enabled) "LEARN: ON" else "LEARN: OFF"

    private fun modeLabel(): String = if (manualExposure) "ISO: RULES" else "ISO: AUTO"

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (requestCode == 42) {
            Toast.makeText(this, "The camera needs permission to work.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, imageCapture)
            camera = cam
            cam.cameraInfo.zoomState.observe(this) { zs ->
                maxZoom = zs.maxZoomRatio
                updateHud(zs.zoomRatio)
            }
            // learning mode: start from the exposure you chose last time
            val st = cam.cameraInfo.exposureState
            if (st.isExposureCompensationSupported) {
                val r = st.exposureCompensationRange
                cam.cameraControl.setExposureCompensationIndex(learner.bias.roundToInt().coerceIn(r.lower, r.upper))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyZoom(progress: Int) {
        val ratio = 1f + (maxZoom - 1f) * progress / 100f
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /** Your own choice from the exposure bar. Learning mode remembers it. */
    private fun setExposure(ev: Int, learn: Boolean) {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return
        val r = st.exposureCompensationRange
        val idx = ev.coerceIn(r.lower, r.upper)
        cam.cameraControl.setExposureCompensationIndex(idx)
        if (learn) learner.learnFromUser(idx)
        updateHud()
    }

    /** Sets the sensor ISO and shutter time (phone's own limits respected). Auto exposure is switched off while this is on. */
    private fun applyIsoShutter(iso: Int, shutterNs: Long) {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val isoRange: Range<Int>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val timeRange: Range<Long>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoV = isoRange?.clamp(iso) ?: iso
        val timeV = timeRange?.clamp(shutterNs) ?: shutterNs
        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoV)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, timeV)
            .build()
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts)
    }

    /** Back to the phone's own auto exposure. */
    private fun clearManual() {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
    }

    /** The rules' own change, not learned. */
    private fun adjustByRules(step: Int) {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported || step == 0) return
        val r = st.exposureCompensationRange
        val idx = (st.exposureCompensationIndex + step).coerceIn(r.lower, r.upper)
        cam.cameraControl.setExposureCompensationIndex(idx)
        updateHud()
    }

    private fun focusAt(x: Float, y: Float) {
        val point = preview.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    private fun updateHud(zoomRatio: Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f) {
        val shutterDenom = (1_000_000_000L / currentShutterNs).coerceAtLeast(1L)
        val exposureLine = if (manualExposure) "RULES: ISO $currentIso  shutter 1/$shutterDenom" else "AUTO: phone sets ISO and shutter"
        hud.text = String.format(
            "ZOOM %.1fx  (phone max %.1fx)\nSCENE %s\n%s\nLEARN %s  bias %.1f\nTap the picture to focus",
            zoomRatio, maxZoom, sceneName, exposureLine, if (learner.enabled) "on" else "off", learner.bias)
    }

    private fun takeBurst() {
        val ic = imageCapture ?: return
        val frames = ArrayList<ByteArray>()
        val main = ContextCompat.getMainExecutor(this)
        fun shoot() {
            ic.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    image.close()
                    frames.add(bytes)
                    if (frames.size == 1) {
                        // the rules read the first shot, then move the exposure for the rest of the burst
                        work.execute {
                            val gray = decodeGray(bytes)?.first
                            val luma = gray?.let { CameraBrain.meanLuma(it) } ?: 128.0
                            val clipped = gray?.let { CameraBrain.clippedFraction(it) } ?: 0.0
                            val scene = gray?.let { CameraBrain.classify(it) } ?: "NORMAL"
                            runOnUiThread {
                                lastLuma = luma
                                sceneName = scene
                                if (manualExposure) {
                                    val cam = camera
                                    val range = cam?.let { Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
                                    val lo = range?.lower ?: 100
                                    val hi = range?.upper ?: 800
                                    currentIso = if (clipped > 0.02) {
                                        // highlights blown out: lower the ISO even if the average looks fine
                                        (currentIso / 2).coerceIn(lo, hi)
                                    } else {
                                        CameraBrain.adjustIso(currentIso, luma, lo, hi, CameraBrain.targetFor(scene))
                                    }
                                    applyIsoShutter(currentIso, currentShutterNs)
                                } else {
                                    adjustByRules(CameraBrain.evStep(luma))
                                }
                                updateHud()
                                window.decorView.postDelayed({ shoot() }, 350)
                            }
                        }
                    } else if (frames.size < 5) {
                        shoot()
                    } else {
                        work.execute { pickAndSave(frames.toList()) }
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { Toast.makeText(this@CameraZoomActivity, "Shot failed.", Toast.LENGTH_SHORT).show() }
                }
            })
        }
        shoot()
    }

    /** Background thread: keeps the sharpest frame, saves it to the gallery. */
    private fun pickAndSave(frames: List<ByteArray>) {
        val scores = frames.map { sharpness(it) }
        val best = SharpnessMath.bestIndex(scores)
        if (best < 0) return
        runOnUiThread { Toast.makeText(this, "Enhancing with Jarvis AI, this can take a while...", Toast.LENGTH_LONG).show() }
        val originalOk = saveToGallery(frames[best], "jarvis_zoom")
        // stack the burst (align + average) for less noise; the AI copy works on the stacked picture
        val stacked = stackBurst(frames, best)
        if (stacked != null) saveToGallery(stacked, "jarvis_zoom_STACK")
        val aiOk = saveToGallery(enhancer.enhance(stacked ?: frames[best]), "jarvis_zoom_AI")
        runOnUiThread {
            val msg = when {
                originalOk && aiOk -> "Saved original, stacked and AI-enhanced copies to Pictures/Jarvis"
                originalOk -> "Saved original. The AI copy failed."
                else -> "Could not save the photo"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Grayscale pixels of a JPEG, at half size (about a quarter of the pixels). */
    /** Stacks the burst into one JPEG (half size, aligned to the sharpest frame). Null if the frames cannot be decoded. */
    private fun stackBurst(frames: List<ByteArray>, ref: Int): ByteArray? {
        if (frames.size < 2) return null
        val decoded = frames.map { decodeArgb(it) ?: return null }
        val w = decoded[0].second
        val h = decoded[0].third
        if (decoded.any { it.second != w || it.third != h }) return null
        val out = FrameStack.alignAndAverage(decoded.map { it.first }, w, h, ref)
        val bmp = android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    /** Decodes a JPEG at half size into ARGB pixels: pixels, width, height. */
    private fun decodeArgb(jpeg: ByteArray): Triple<IntArray, Int, Int>? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Triple(px, w, h)
    }

    private fun decodeGray(jpeg: ByteArray): Pair<IntArray, Int>? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val w = bmp.width
        val h = bmp.height
        val gray = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = bmp.getPixel(x, y)
            gray[y * w + x] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        bmp.recycle()
        return Pair(gray, w)
    }

    private fun sharpness(jpeg: ByteArray): Double {
        val g = decodeGray(jpeg) ?: return 0.0
        return SharpnessMath.laplacianVariance(g.first, g.second, g.first.size / g.second)
    }

    private fun saveToGallery(jpeg: ByteArray, prefix: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val written = contentResolver.openOutputStream(uri)?.use { it.write(jpeg); true } ?: false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return written
    }

    override fun onDestroy() {
        if (manualExposure) clearManual()
        work.shutdown()
        super.onDestroy()
    }
}
