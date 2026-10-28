package com.jarvis.control

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Jarvis camera (phone). The AI reads the light, chooses ISO, shutter, white balance and focus, and shows each
 *  decision as it makes it. SHOOT goes step by step; FIX applies everything at once. A burst of 5 is stacked,
 *  given a look for day or night, and saved to Pictures/Jarvis. */
class CameraZoomActivity : ComponentActivity() {
    /** What the live picture looks like right now. */
    private data class Reading(val scene: String, val mean: Double, val clipped: Double)

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var extManager: ExtensionsManager? = null
    private var maxZoom = 1f
    private var zoomNow = 1f

    @Volatile private var live: Reading? = null
    private var shownScene = ""
    private var sceneName = "NORMAL"

    private var aiOn = true                  // AI picks ISO, shutter, white balance and focus
    private var nightMode = false            // the phone's own night processing (only where the camera offers it)
    private var nightBoost = 0               // our own night mode: 0 = AUTO, 1 = ON, 2 = OFF
    private var currentIso = 200
    private var currentShutterNs = ShotPlanner.NS_1_125
    private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var wbIndex = 0                  // 0 = AI decides white balance; otherwise a fixed choice
    private var focusInfinity = false
    private var focusLocked = false          // true after a tap: focus stays on that spot
    private lateinit var focusButton: Button
    private var gridOn = false
    private var peakOn = false
    private var running = false
    private var pendingFix: Boolean? = null  // a run waiting for the camera to rebind (true = apply all at once)

    private val wbChoices: List<Int?> = listOf(
        null,
        CaptureRequest.CONTROL_AWB_MODE_AUTO,
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    )

    private lateinit var learner: CameraLearning
    private lateinit var preview: PreviewView
    private lateinit var peakView: ImageView
    private lateinit var gridView: GridLines
    private lateinit var hud: TextView
    private lateinit var logView: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var evBar: SeekBar
    private lateinit var aiButton: Button
    private lateinit var nightButton: Button
    private lateinit var wbButton: Button
    private lateinit var gridButton: Button
    private lateinit var peakButton: Button
    private lateinit var learnButton: Button
    private lateinit var fixButton: Button
    private lateinit var shootButton: Button
    private lateinit var tuning: PhotoTuning
    private lateinit var feedbackRow: HorizontalScrollView
    private lateinit var progressBar: ProgressBar
    private var feedbackScene = "NORMAL"
    private var smMean = -1.0
    private var smClip = 0.0
    private var smShadow = 0.0
    private val logLines = ArrayList<String>()
    private val work = Executors.newSingleThreadExecutor()
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val enhancer: Enhancer = SmallSrEnhancer()

    private val density: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        learner = CameraLearning(this)
        preview = PreviewView(this)
        peakView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.85f
            visibility = View.GONE
        }
        gridView = GridLines(this).apply { visibility = View.GONE }
        hud = TextView(this).apply {
            setTextColor(Color.rgb(230, 240, 250))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            background = roundedBox(Color.argb(170, 8, 12, 18), 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        logView = TextView(this).apply {
            setTextColor(Color.rgb(200, 214, 228))
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            maxLines = 8
        }
        zoomBar = SeekBar(this).apply { max = 100; progress = 0 }
        zoomBar.onUser { applyZoom(it) }
        evBar = SeekBar(this).apply { max = 4; progress = 2 }
        evBar.onUser { setExposure(it - 2, learn = true) }

        aiButton = chip { toggleAi() }
        nightButton = chip { cycleNight() }
        wbButton = chip { cycleWb() }
        gridButton = chip {
            gridOn = !gridOn
            gridView.visibility = if (gridOn) View.VISIBLE else View.GONE
            updateChips()
        }
        peakButton = chip {
            peakOn = !peakOn
            peakView.visibility = if (peakOn) View.VISIBLE else View.GONE
            if (!peakOn) peakView.setImageBitmap(null)
            updateChips()
        }
        focusButton = chip { toggleFocusLock() }
        learnButton = chip {
            learner.enabled = !learner.enabled
            updateChips()
            updateStatus()
        }
        fixButton = chip { runSequence(true) }.apply { text = "FIX"; textSize = 14f }
        shootButton = Button(this).apply {
            text = "SHOOT"
            setTextColor(Color.BLACK)
            textSize = 15f
            background = GradientDrawable().apply {
                setShape(GradientDrawable.OVAL)
                setColor(Color.rgb(0, 220, 255))
            }
            setOnClickListener { runSequence(false) }
        }

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in listOf(aiButton, nightButton, wbButton, gridButton, peakButton, focusButton, learnButton)) {
            chipRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        val scroll = HorizontalScrollView(this).apply {
            setHorizontalScrollBarEnabled(false)
            addView(chipRow)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fixButton, LinearLayout.LayoutParams(dp(84), dp(56)))
            addView(shootButton, LinearLayout.LayoutParams(dp(96), dp(96)).apply { setMargins(dp(16), 0, dp(16), 0) })
            addView(TextView(this@CameraZoomActivity).apply {
                text = "5 shots\nstacked"
                setTextColor(Color.rgb(160, 175, 190))
                textSize = 11f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(84), dp(56)))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        tuning = PhotoTuning(this)
        val feedbackInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((label, fb) in listOf(
            "Good" to PhotoTuning.Feedback.GOOD,
            "Too dark" to PhotoTuning.Feedback.DARK,
            "Too bright" to PhotoTuning.Feedback.BRIGHT,
            "Noisy" to PhotoTuning.Feedback.NOISY,
            "Blurry" to PhotoTuning.Feedback.BLURRY
        )) {
            feedbackInner.addView(chip { giveFeedback(fb) }.apply { text = label; textSize = 11f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                })
        }
        feedbackRow = HorizontalScrollView(this).apply {
            setHorizontalScrollBarEnabled(false)
            visibility = View.GONE
            addView(feedbackInner)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(Color.argb(205, 10, 14, 20), 22)
            setPadding(dp(16), dp(12), dp(16), dp(14))
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { bottomMargin = dp(6) })
            addView(logView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(zoomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(evBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(feedbackRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            })
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(peakView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(gridView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(40), dp(12), 0) })
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        setContentView(root)

        preview.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) focusAt(e.x, e.y)
            true
        }
        updateChips()
        updateStatus()
        logLine("Ready. SHOOT goes step by step; FIX applies everything at once.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
        }
    }

    // ---- small view helpers ----

    private fun roundedBox(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun chip(onClick: () -> Unit): Button = Button(this).apply {
        setAllCaps(false)
        textSize = 12f
        setTextColor(Color.WHITE)
        background = roundedBox(Color.argb(90, 255, 255, 255), 14)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun SeekBar.onUser(f: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) f(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    /** One line in the log panel: the last four decisions, each with its reason. */
    private fun logLine(text: String) {
        logLines.add(text)
        while (logLines.size > 40) logLines.removeAt(0)
        logView.text = logLines.takeLast(4).joinToString("\n")
    }

    private fun updateStatus() {
        val exposure = when {
            nightMode -> "night mode (phone)"
            aiOn -> "AI: ISO $currentIso, 1/${1_000_000_000L / currentShutterNs} s"
            else -> "phone auto exposure"
        }
        hud.text = String.format(
            "LIGHT %s   SCENE %s\n%s\nZOOM %.1fx of %.1fx max",
            live?.scene ?: "--", sceneName, exposure, zoomNow, maxZoom)
    }

    private fun updateChips() {
        aiButton.text = if (aiOn) "AI: ON" else "AI: OFF (phone)"
        nightButton.text = when {
            nightMode -> "NIGHT: PHONE"
            nightBoost == 1 -> "NIGHT: ON"
            nightBoost == 2 -> "NIGHT: OFF"
            else -> "NIGHT: AUTO"
        }
        wbButton.text = wbLabel()
        gridButton.text = if (gridOn) "GRID: ON" else "GRID: OFF"
        peakButton.text = if (peakOn) "PEAK: ON" else "PEAK: OFF"
        learnButton.text = if (learner.enabled) "LEARN: ON" else "LEARN: OFF"
        focusButton.text = if (focusLocked) "FOCUS: TAP" else "FOCUS: AUTO"
    }

    private fun wbLabel(): String = if (wbIndex == 0) "WB: AI" else when (awbMode) {
        CaptureRequest.CONTROL_AWB_MODE_AUTO -> "WB: AUTO"
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "WB: SUN"
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "WB: BULB"
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "WB: TUBE"
        else -> "WB: CLOUD"
    }

    // ---- switches ----

    private fun toggleAi() {
        aiOn = !aiOn
        logLine("AI control: " + if (aiOn) "on, it chooses ISO, shutter, white balance and focus" else "off, the phone decides exposure")
        pushCaptureOptions()
        updateChips()
        updateStatus()
    }

    private fun cycleWb() {
        wbIndex = (wbIndex + 1) % wbChoices.size
        awbMode = wbChoices[wbIndex] ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
        logLine(if (wbIndex == 0) "White balance: AI decides" else "White balance: you chose ${wbLabel().removePrefix("WB: ").lowercase()}")
        pushCaptureOptions()
        updateChips()
    }

    /** Your rating of the last photo: teaches the planner for that kind of light. */
    private fun giveFeedback(fb: PhotoTuning.Feedback) {
        tuning.learn(feedbackScene, fb)
        feedbackRow.visibility = View.GONE
        logLine("Learned: ${fb.name.lowercase()} for ${feedbackScene.lowercase()} light. The next ${feedbackScene.lowercase()} shot uses it.")
    }

    /** Our own night mode: AUTO (dark scenes only), ON (every shot), OFF. Takes 8 frames instead of 5 for night. */
    private fun cycleNight() {
        nightBoost = (nightBoost + 1) % 3
        logLine("Night mode: " + listOf("AUTO (dark scenes)", "ON (8 frames)", "OFF")[nightBoost])
        updateChips()
    }

    // ---- camera binding ----

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
            val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extFuture.addListener({
                extManager = extFuture.get()
                bindCamera(provider)
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        imageCapture = ic
        provider.unbindAll()
        val base = CameraSelector.DEFAULT_BACK_CAMERA
        val em = extManager
        val selector = if (nightMode && em != null && em.isExtensionAvailable(base, ExtensionMode.NIGHT))
            em.getExtensionEnabledCameraSelector(base, ExtensionMode.NIGHT) else base
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Peaking.ANALYSIS_SIZE)
            .build()
            .also { it.setAnalyzer(analysisExec, Peaking.LiveAnalyzer { gray, w, h -> onLiveFrame(gray, w, h) }) }
        val cam = try {
            provider.bindToLifecycle(this, selector, previewUseCase, ic, analysis)
        } catch (e: IllegalArgumentException) {
            // some phone night modes cannot run the live analysis: shoot without the live reading
            logLine("Live light reading is not available in this mode")
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, previewUseCase, ic)
        }
        camera = cam
        cam.cameraInfo.zoomState.observe(this) { zs ->
            maxZoom = zs.maxZoomRatio
            zoomNow = zs.zoomRatio
            updateStatus()
        }
        // learning mode: start from the exposure you chose last time
        val st = cam.cameraInfo.exposureState
        if (st.isExposureCompensationSupported) {
            val r = st.exposureCompensationRange
            cam.cameraControl.setExposureCompensationIndex(learner.bias.roundToInt().coerceIn(r.lower, r.upper))
        }
        pushCaptureOptions()
        updateStatus()
        val f = pendingFix
        if (f != null) {
            pendingFix = null
            window.decorView.postDelayed({ runSequence(f) }, 1200L)
        }
    }

    /** Runs on the analysis thread for each live frame: reads the light, and draws peaking if it is on. */
    private fun onLiveFrame(gray: IntArray, w: Int, h: Int) {
        val m = CameraBrain.meanLuma(gray)
        val c = CameraBrain.clippedFraction(gray)
        val sh = CameraBrain.shadowFraction(gray)
        // smooth over recent frames, so the reading does not flip when the phone moves
        smMean = if (smMean < 0) m else smMean * 0.85 + m * 0.15
        smClip = smClip * 0.85 + c * 0.15
        smShadow = smShadow * 0.85 + sh * 0.15
        val scene = CameraBrain.classifyStats(smMean, smClip, smShadow)
        live = Reading(scene, smMean, smClip)
        val edges = if (peakOn) Peaking.edges(gray, w, h) else null
        runOnUiThread {
            if (edges != null) peakView.setImageBitmap(android.graphics.Bitmap.createBitmap(edges, w, h, android.graphics.Bitmap.Config.ARGB_8888))
            if (scene != shownScene) {
                shownScene = scene
                updateStatus()
            }
        }
    }

    /** Sends the AI's choices to the camera in one go (Camera2 options replace each other). */
    private fun pushCaptureOptions() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        if (aiOn && !nightMode) {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val isoRange: Range<Int>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val timeRange: Range<Long>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoRange?.clamp(currentIso) ?: currentIso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, timeRange?.clamp(currentShutterNs) ?: currentShutterNs)
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        if (focusLocked) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        } else if (aiOn && focusInfinity) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
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
        updateStatus()
    }

    /** A tap focuses and exposes on that spot, and keeps it there (no auto-cancel) until you press FOCUS. */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).disableAutoCancel().build())
        focusLocked = true
        focusInfinity = false
        pushCaptureOptions()
        logLine("Focus locked where you tapped. Press FOCUS to let the phone find it again")
        updateChips()
    }

    /** FOCUS button: releases a tap lock so the phone finds the focus automatically again. */
    private fun toggleFocusLock() {
        if (focusLocked) {
            camera?.cameraControl?.cancelFocusAndMetering()
            focusLocked = false
            pushCaptureOptions()
            logLine("Focus: the phone finds it automatically again")
        } else {
            logLine("Tap the picture to lock the focus on that spot")
        }
        updateChips()
    }

    // ---- the run: decide, show each step, shoot, save ----

    /** fixAll = apply every decision at once. Otherwise one decision at a time, with a short pause, so you can watch. */
    private fun runSequence(fixAll: Boolean) {
        if (running) return
        feedbackRow.visibility = View.GONE
        if (imageCapture == null || camera == null) {
            logLine("The camera is still starting")
            return
        }
        val r = live ?: Reading("NORMAL", 128.0, 0.0)
        val extAvailable = extManager?.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT) == true
        if (r.scene == "NIGHT" && !nightMode && extAvailable) {
            nightMode = true
            logLine("Dark scene: switching on the phone's night mode, then shooting")
            updateChips()
            pendingFix = fixAll
            startCamera()
            return
        }
        val info = Camera2CameraInfo.from(camera!!.cameraInfo)
        val isoRange: Range<Int>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val timeRange: Range<Long>? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val plan = ShotPlanner.plan(
            r.scene, r.mean, r.clipped,
            isoRange?.lower ?: 100, isoRange?.upper ?: 3200,
            timeRange?.lower ?: 100_000L, timeRange?.upper ?: 500_000_000L,
            tuning.isoStops(r.scene), tuning.shutterStops(r.scene)
        )
        sceneName = plan.scene
        running = true
        val steps = plan.steps

        fun applyStep(i: Int) {
            if (i >= steps.size) {
                startBurst()
                return
            }
            val st = steps[i]
            logLine("${st.title}: ${st.value}\n    ${st.reason}")
            when (st.title) {
                "Exposure" -> if (aiOn && !nightMode) {
                    currentIso = plan.iso
                    currentShutterNs = plan.shutterNs
                    pushCaptureOptions()
                } else if (!aiOn) {
                    logLine("    AI is off: the phone keeps its own exposure")
                }
                "White balance" -> if (aiOn && wbIndex == 0) {
                    awbMode = plan.awbMode
                    pushCaptureOptions()
                }
                "Focus" -> if (focusLocked) {
                    logLine("    your tap point stays in focus")
                } else if (aiOn) {
                    focusInfinity = plan.focusInfinity
                    pushCaptureOptions()
                }
            }
            updateStatus()
            if (fixAll) applyStep(i + 1) else window.decorView.postDelayed({ applyStep(i + 1) }, 700L)
        }
        applyStep(0)
    }

    private fun shotInfo(): String = when {
        nightMode -> "night mode"
        aiOn -> "ISO $currentIso, 1/${1_000_000_000L / currentShutterNs} s"
        else -> "phone auto"
    }

    /** A decoded picture: ARGB pixels, width, height. */
    private class Argb(val px: IntArray, val w: Int, val h: Int)

    private fun hideProgress() {
        progressBar.isIndeterminate = false
        progressBar.visibility = View.GONE
    }

    /** Bright-light bracket: normal shots at the planned exposure, two at a quarter of the shutter time (two stops darker),
     *  and two at a sixteenth (four stops darker), so very bright lights keep their detail.
     *  Night: 10 shots (6 + 2 + 2). Day: 7 shots (3 + 2 + 2). Without AI control: five shots at the phone's exposure. */
    private fun startBurst() {
        val ic = imageCapture ?: run {
            running = false
            return
        }
        val bracket = aiOn && !nightMode
        val longNs = currentShutterNs
        val night = nightBoost == 1 || (nightBoost == 0 && sceneName == "NIGHT")
        val total = if (bracket) (if (night) 10 else 7) else 5
        val split = if (bracket) total - 4 else total
        val frames = ArrayList<ByteArray>()
        val main = ContextCompat.getMainExecutor(this)
        progressBar.isIndeterminate = false
        progressBar.max = total
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        fun shutterFor(i: Int): Long = when {
            !bracket || i < split -> longNs
            i < split + 2 -> longNs / 4
            else -> longNs / 16
        }
        fun shoot() {
            if (bracket) {
                currentShutterNs = shutterFor(frames.size)
                pushCaptureOptions()
            }
            logLine("Shot ${frames.size + 1} of $total (${shotInfo()})")
            ic.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    image.close()
                    frames.add(bytes)
                    progressBar.progress = frames.size
                    if (frames.size < total) {
                        window.decorView.postDelayed({ shoot() }, if (bracket) 450L else 350L)
                    } else {
                        currentShutterNs = longNs
                        running = false
                        progressBar.isIndeterminate = true
                        logLine("Saving every shot, stacking each group, fusing the exposures, applying the look")
                        work.execute { pickAndSave(frames.toList(), split) }
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    currentShutterNs = longNs
                    running = false
                    hideProgress()
                    logLine("Shot failed")
                    Toast.makeText(this@CameraZoomActivity, "Shot failed.", Toast.LENGTH_SHORT).show()
                }
            })
        }
        shoot()
    }

    /** Background thread: saves every shot, stacks each exposure group, fuses the exposures, applies the look,
     *  and saves the copies. Groups: normal = frames[0 until split], dark = next two, darker = the rest. */
    private fun pickAndSave(frames: List<ByteArray>, split: Int) {
        frames.forEachIndexed { i, f -> saveToGallery(f, "jarvis_shot_${i + 1}") }
        val normal = frames.subList(0, split)
        val best = SharpnessMath.bestIndex(normal.map { sharpness(it) })
        if (best < 0) {
            runOnUiThread { hideProgress() }
            return
        }
        val originalOk = saveToGallery(normal[best], "jarvis_zoom")
        val longStack = stackArgb(normal, best)
            ?: decodeArgb(normal[best])?.let { Argb(it.first, it.second, it.third) }
        if (longStack == null) {
            runOnUiThread {
                hideProgress()
                logLine("Could not read the photo")
            }
            return
        }
        val darkFrames = if (frames.size > split) frames.subList(split, minOf(split + 2, frames.size)) else emptyList()
        val darkerFrames = if (frames.size > split + 2) frames.subList(split + 2, frames.size) else emptyList()
        val darkStack = if (darkFrames.size >= 2) stackArgb(darkFrames, 0) else null
        val darkerStack = if (darkerFrames.size >= 2) stackArgb(darkerFrames, 0) else null
        var fused = longStack
        for (extra in listOfNotNull(darkStack, darkerStack)) {
            if (extra.w == fused.w && extra.h == fused.h) {
                fused = Argb(ToneMap.fuseExposures(fused.px, extra.px), fused.w, fused.h)
            }
        }
        saveToGallery(encodeArgb(longStack), "jarvis_zoom_STACK")
        if (fused !== longStack) saveToGallery(encodeArgb(fused), "jarvis_zoom_FUSED")
        val look = toneArgb(fused, sceneName)
        saveToGallery(look, "jarvis_zoom_HDR")
        val aiOk = saveToGallery(enhancer.enhance(look), "jarvis_zoom_AI")
        runOnUiThread {
            hideProgress()
            val msg = when {
                originalOk && aiOk -> "Saved every shot plus the $sceneName look (stacked, fused, HDR, AI) in Pictures/Jarvis"
                originalOk -> "Saved the shots. The AI copy failed."
                else -> "Could not save the photo"
            }
            logLine(msg)
            if (originalOk) {
                feedbackScene = sceneName
                feedbackRow.visibility = View.VISIBLE
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Aligns and averages several frames into one picture. Null if they cannot be read or differ in size. */
    private fun stackArgb(frames: List<ByteArray>, ref: Int): Argb? {
        if (frames.size < 2) return null
        val decoded = frames.map { decodeArgb(it) ?: return null }
        val w = decoded[0].second
        val h = decoded[0].third
        if (decoded.any { it.second != w || it.third != h }) return null
        return Argb(FrameStack.alignAndAverage(decoded.map { it.first }, w, h, ref), w, h)
    }

    private fun encodeArgb(a: Argb): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(a.px, a.w, a.h, android.graphics.Bitmap.Config.ARGB_8888)
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    /** Highlight recovery, shadow lift and detail, with the look for the scene (night or day). */
    private fun toneArgb(a: Argb, scene: String): ByteArray {
        val fused = ToneMap.fuse(a.px)
        val look = if (scene == "NIGHT") ToneMap.nightLook(fused) else ToneMap.dayLook(fused)
        return encodeArgb(Argb(ToneMap.refine(look, a.w, a.h), a.w, a.h))
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

    /** Grayscale pixels of a JPEG, at half size. */
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
        work.shutdown()
        analysisExec.shutdown()
        super.onDestroy()
    }
}
