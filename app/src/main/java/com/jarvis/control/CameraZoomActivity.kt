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
    private val work = Executors.newSingleThreadExecutor()
    private var enhancer: Enhancer = NoEnhancer
    private lateinit var learner: CameraLearning
    private lateinit var preview: PreviewView
    private lateinit var hud: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var evBar: SeekBar
    private lateinit var learnButton: Button

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
        val shutter = Button(this).apply {
            text = "SHOOT"
            setOnClickListener { takeBurst() }
        }
        val root = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        val iso = CameraBrain.isoFor(lastLuma)
        val shutterDenom = (1_000_000_000L / CameraBrain.shutterNsFor(lastLuma, 1_000_000_000L / 30)).coerceAtLeast(1L)
        hud.text = String.format(
            "ZOOM %.1fx  (phone max %.1fx)\nRULES: ISO %d  shutter 1/%d\nLEARN %s  bias %.1f\nTap the picture to focus",
            zoomRatio, maxZoom, iso, shutterDenom, if (learner.enabled) "on" else "off", learner.bias)
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
                            val luma = decodeGray(bytes)?.let { CameraBrain.meanLuma(it.first) } ?: 128.0
                            runOnUiThread {
                                lastLuma = luma
                                adjustByRules(CameraBrain.evStep(luma))
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
        val result = enhancer.enhance(frames[best])
        val ok = saveToGallery(result)
        runOnUiThread {
            Toast.makeText(this, if (ok) "Saved to Pictures/Jarvis" else "Could not save the photo", Toast.LENGTH_SHORT).show()
        }
    }

    /** Grayscale pixels of a JPEG, at half size (about a quarter of the pixels). */
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

    private fun saveToGallery(jpeg: ByteArray): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "jarvis_zoom_${System.currentTimeMillis()}.jpg")
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
        work.shutdown()
        super.onDestroy()
    }
}
