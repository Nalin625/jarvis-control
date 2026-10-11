package com.jarvis.control

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shows the hand gestures the laptop workspace understands, lets you see what the camera and the laptop
 * are doing, turns the camera and laptop control on or off, and (separately) teaches custom signs for the
 * laptop's older gesture map.
 */
class GestureActivity : AppCompatActivity() {

    companion object {
        private const val RECORD_MS = 3000L        // a sign is kept for this long
        private const val GET_READY_MS = 1000L     // a count-in before it is kept
        private const val MAX_RECORD_FRAMES = 120  // enough clear frames; more adds nothing
        private const val CAPTURE_WINDOW_MS = 1000L // a corner is the average of the last second of still fingertips
        private const val CALIB_INTRO = "Calibration teaches the laptop how far your hand must move to reach the whole screen. " +
            "Press CALIBRATE THE REACH, then point at four corners."
        private val CORNER_HINTS = listOf(
            "Point with your index finger at the TOP LEFT of the space you can reach comfortably. Hold still, then press CAPTURE.",
            "Now the TOP RIGHT corner. Hold still, then press CAPTURE.",
            "Now the BOTTOM RIGHT corner. Hold still, then press CAPTURE.",
            "Now the BOTTOM LEFT corner. Hold still, then press CAPTURE."
        )
    }

    /** One line of the action list: the action, its box on screen, and the text that says what it needs. */
    private class ActionRow(val action: MapAction, val box: LinearLayout, val detail: TextView)

    private lateinit var trainer: GestureTrainer
    private lateinit var cameraLine: TextView
    private lateinit var handLine: TextView
    private lateinit var pointerLine: TextView
    private lateinit var laptopLine: TextView
    private lateinit var linkLine: TextView
    private lateinit var cameraBtn: Button
    private lateinit var sendBtn: Button
    private lateinit var pointerBtn: Button
    private lateinit var placeBtn: Button
    private lateinit var recordBtn: Button
    private lateinit var recordInfo: TextView
    private lateinit var selectedText: TextView
    private lateinit var summaryText: TextView
    private lateinit var testBtn: Button
    private lateinit var testInfo: TextView
    private lateinit var cameraView: HandCameraView
    private lateinit var videoBtn: Button
    private lateinit var tuneText: TextView
    private lateinit var tuneNote: TextView
    private lateinit var sensDownBtn: Button
    private lateinit var sensUpBtn: Button
    private lateinit var dwellDownBtn: Button
    private lateinit var dwellUpBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var calibText: TextView
    private lateinit var calibStartBtn: Button
    private lateinit var calibCaptureBtn: Button
    private lateinit var calibCancelBtn: Button
    private lateinit var calibSaveBtn: Button
    private var laptopTuning: JSONObject? = null     // the laptop's settings as it last reported them (null until read)
    private var tuneBusy = false                     // one change at a time: the buttons wait for the laptop's answer
    private var calibrating = false
    private val calibPoints = ArrayList<CalibrationBox.Point>()
    private val recentTips = ArrayList<Pair<Long, CalibrationBox.Point>>()   // index tips seen while calibrating
    private var calibOk: CalibrationBox.Result.Ok? = null     // a box that passed the checks, waiting to be saved
    private var calibMessage = ""
    private var visible = false
    private var actions: List<MapAction> = emptyList()
    private var places: List<String> = emptyList()
    private val rows = ArrayList<ActionRow>()
    private var selected: MapAction? = null
    private var recording: String? = null         // the sign being recorded, while a recording runs
    private var collecting = false                // true during the seconds that are kept
    private val recordFrames = ArrayList<List<FloatArray>>()
    private val handler = Handler(Looper.getMainLooper())

    /** Refreshes the link line once a second while this screen is open. onPause removes it. */
    private val linkTicker = object : Runnable {
        override fun run() {
            if (!visible) return
            refreshLink()
            handler.postDelayed(this, 1000L)
        }
    }
    private var testing = false
    private var lastTest = 0L
    private var handSeen: Boolean? = null
    private val prefs by lazy { getSharedPreferences("jarvis_control", MODE_PRIVATE) }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setCamera(true) else setLine(cameraLine, "Camera", "Allow the camera, then press START CAMERA again.")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun panel(fill: String, stroke: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor(fill))
        setStroke(dp(2), Color.parseColor(stroke))
    }

    private fun txt(s: String, size: Float, color: String = "#E8F7FF", bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = label
        textSize = 16f
        setTextColor(Color.parseColor("#05090F"))
        background = panel("#4FE3FF", "#4FE3FF")
        minimumHeight = dp(52)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(10), 0, 0) }
    }

    /** Glass: a see-through cyan panel with a thin cyan edge. */
    private fun glass(): GradientDrawable = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TL_BR
        colors = intArrayOf(Color.parseColor("#331B9FBF"), Color.parseColor("#0C0A1826"))
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Color.parseColor("#664FE3FF"))
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = glass()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(14), 0, 0) }
        addView(txt(title, 13f, "#4FE3FF", bold = true))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trainer = GestureTrainer.shared(this)
        actions = try {
            GestureMap.load(this)
        } catch (e: Exception) {
            emptyList<MapAction>()           // the screen says so below; nothing else breaks
        }
        places = GestureMap.places(actions)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }
        root.addView(txt("JARVIS GESTURES", 26f, "#4FE3FF", bold = true))
        root.addView(txt("Your phone camera watches your hand. Hand positions go to the laptop only while Laptop control is ON.", 15f, "#9FB8C6"))

        // 0. My camera: what the phone sees, with your hand outlined
        val mine = card("MY CAMERA: WHAT JARVIS SEES")
        cameraView = HandCameraView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300))
                .apply { setMargins(0, dp(10), 0, 0) }
        }
        mine.addView(cameraView)
        videoBtn = btn("") { toggleVideo() }
        mine.addView(videoBtn)
        mine.addView(txt("Your picture stays on this phone. The outline shows the joints the phone found in your hand. The ring on your index fingertip fills when you pinch.", 13f, "#9FB8C6"))
        root.addView(mine)

        // 1. What is happening right now
        val now = card("RIGHT NOW")
        cameraLine = txt("", 18f)
        handLine = txt("", 18f)
        pointerLine = txt("", 16f)
        laptopLine = txt("", 16f)
        linkLine = txt("", 14f, "#9FB8C6")
        now.addView(cameraLine)
        now.addView(handLine)
        now.addView(pointerLine)
        now.addView(laptopLine)
        now.addView(linkLine)
        cameraBtn = btn("START CAMERA") { toggleCamera() }
        now.addView(cameraBtn)
        now.addView(txt("A notification stays up while the camera is on.", 13f, "#9FB8C6"))
        root.addView(now)

        // 2. The workspace gestures: what the laptop does with each one
        val guide = card("GESTURES FOR THE LAPTOP WORKSPACE")
        guide.addView(txt(GestureGuide.intro, 14f, "#9FB8C6"))
        for (g in GestureGuide.items) {
            guide.addView(txt(g.name, 16f, "#E8F7FF", bold = true))
            guide.addView(txt(g.how, 14f, "#9FB8C6"))
        }
        root.addView(guide)

        // 3. Settings: each button says whether it is on
        val settings = card("SETTINGS")
        sendBtn = btn("") { toggleSend() }
        pointerBtn = btn("") { togglePointer() }
        settings.addView(sendBtn)
        settings.addView(pointerBtn)
        settings.addView(txt("Laptop control sends your hand to the laptop, so the workspace pointer follows it. Your laptop's mouse only moves when its workspace is closed and its 'mouse when closed' setting is on.", 14f, "#9FB8C6"))
        settings.addView(btn("CHECK THE LAPTOP") { checkLaptop() })
        root.addView(settings)

        // 3b. Tune the pointer. The laptop keeps the values; this screen asks it to change them and shows its answer.
        val tune = card("TUNE THE POINTER")
        tune.addView(txt("Sensitivity: how far the pointer moves for a hand movement. Dwell: how long to hold still over a card to pick it. Reach: the part of your camera picture that covers the whole screen. The laptop saves these.", 14f, "#9FB8C6"))
        tuneText = txt("", 15f)
        tune.addView(tuneText)
        sensDownBtn = btn("SENSITIVITY  -") { stepSensitivity(false) }
        sensUpBtn = btn("SENSITIVITY  +") { stepSensitivity(true) }
        dwellDownBtn = btn("DWELL  -") { stepDwell(false) }
        dwellUpBtn = btn("DWELL  +") { stepDwell(true) }
        tune.addView(pairRow(sensDownBtn, sensUpBtn))
        tune.addView(pairRow(dwellDownBtn, dwellUpBtn))
        resetBtn = btn("RESET SENSITIVITY, DWELL AND REACH") { resetTuning() }
        tune.addView(resetBtn)
        tuneNote = txt("", 14f, "#9FB8C6")
        tune.addView(tuneNote)

        calibText = txt("", 15f)
        tune.addView(calibText)
        calibStartBtn = btn("CALIBRATE THE REACH") { startCalibration() }
        calibCaptureBtn = btn("CAPTURE") { captureCorner() }
        calibCancelBtn = btn("CANCEL CALIBRATION") { cancelCalibration() }
        calibSaveBtn = btn("SAVE THE REACH TO THE LAPTOP") { saveReach() }
        tune.addView(calibStartBtn)
        tune.addView(calibCaptureBtn)
        tune.addView(calibCancelBtn)
        tune.addView(calibSaveBtn)
        root.addView(tune)

        // 4. Custom signs: optional, for the laptop's older gesture map. The workspace gestures above do not use them.
        val teach = card("CUSTOM SIGNS (OPTIONAL)")
        teach.addView(txt("These signs run the actions in the laptop's gesture map. The workspace gestures above do not use them. Pick an action from the list. Press RECORD, then do its hand sign for 3 seconds, holding it still in view. Turn on Show my video to see your hand while you record.", 15f, "#9FB8C6"))
        placeBtn = btn("") { cyclePlace() }
        teach.addView(placeBtn)
        teach.addView(txt("The place is where you are. The same sign can do a different action in each place, so one sign can cover several actions.", 13f, "#9FB8C6"))
        summaryText = txt("", 15f)
        teach.addView(summaryText)
        selectedText = txt("No action picked yet.", 16f)
        teach.addView(selectedText)
        recordInfo = txt("Not recording.", 16f)
        teach.addView(recordInfo)
        recordBtn = btn("RECORD 3 SECONDS") { startRecording() }
        teach.addView(recordBtn)
        teach.addView(btn("FORGET THIS SIGN") { forgetSelected() })
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (a in actions) {
            val row = buildRow(a)
            rows.add(row)
            listBox.addView(row.box)
        }
        teach.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360))
                .apply { setMargins(0, dp(10), 0, 0) }
            addView(listBox)
        })
        if (actions.isEmpty()) {
            teach.addView(txt("The action list is not in the app. Build the app again with gesture_map.json in app/src/main/assets.", 15f, "#FF9F9F"))
        }
        root.addView(teach)

        // 4. Test it
        val test = card("TEST A CUSTOM SIGN")
        testInfo = txt("Press TEST A SIGN, then show a custom sign to the camera.", 18f)
        test.addView(testInfo)
        testBtn = btn("TEST A SIGN") { toggleTest() }
        test.addView(testBtn)
        root.addView(test)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#05090F"))
            addView(root)
        })
        refreshPlace()
        refreshRows()
        refreshButtons()
        refreshTuning()
        refreshCalib()
        checkLaptop()          // one request when the screen opens; nothing repeats by itself
    }

    override fun onResume() {
        super.onResume()
        visible = true
        GestureService.onHand = { hand -> handleHand(hand) }
        GestureService.onStatus = { _, _ -> refreshButtons() }
        GestureService.onVideo = { pic, hand, sure -> showVideo(pic, hand, sure) }
        applyVideo()
        handler.post(linkTicker)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        GestureService.onHand = null
        GestureService.onStatus = null
        GestureService.onVideo = null
        applyVideo()
        handler.removeCallbacksAndMessages(null)
        recording = null
        collecting = false
        recordFrames.clear()
        GestureService.training = false
        recordBtn.isEnabled = true
        calibrating = false                    // leaving the screen ends a calibration: nothing half-saved
        recentTips.clear()
        refreshCalib()
        testing = false
        testBtn.text = "TEST A SIGN"
        handSeen = null
    }

    private fun setLine(tv: TextView, name: String, value: String) {
        tv.text = "$name:  $value"
    }

    /** The phone sends pictures only while this screen is open and "Show my video" is on. */
    private fun applyVideo() {
        GestureService.videoOn = visible && prefs.getBoolean("gesture_video", true)
        refreshButtons()
    }

    private fun toggleVideo() {
        val on = !prefs.getBoolean("gesture_video", true)
        prefs.edit().putBoolean("gesture_video", on).apply()
        applyVideo()
    }

    /** Called on the main thread about 10 times a second while the video is on. */
    private fun showVideo(pic: Bitmap?, hand: List<FloatArray>?, sure: Float) {
        val name = if (hand != null) trainer.classify(hand).first else null
        cameraView.update(pic, hand, sure, name)
    }

    /** Redraws the buttons and the RIGHT NOW lines from the phone's current status. */
    private fun refreshButtons() {
        val video = prefs.getBoolean("gesture_video", true)
        videoBtn.text = "Show my video:  " + (if (video) "ON" else "OFF")
        when {
            !GestureService.running -> {
                cameraView.clear()
                cameraView.message = "Camera is off. Press START CAMERA."
            }
            !video -> {
                cameraView.clear()
                cameraView.message = "Video is off. The camera still watches your hand."
            }
            else -> cameraView.message = "Waiting for your hand..."
        }
        cameraBtn.text = if (GestureService.running) "STOP CAMERA" else "START CAMERA"
        val send = prefs.getBoolean("gesture_send", false)
        sendBtn.text = "Send gestures to the laptop:  " + (if (send) "ON" else "OFF")
        val pointer = prefs.getBoolean("gesture_pointer", false)
        pointerBtn.text = "Laptop control:  " + (if (pointer) "ON" else "OFF")
        setLine(cameraLine, "Camera", GestureService.status["camera"] ?: "Off")
        if (handSeen == null) setLine(handLine, "Hand", "Waiting for the camera")
        setLine(pointerLine, "Laptop control", when {
            !pointer -> GestureGuide.laptopControlOff
            !GestureService.running -> "On, but the camera is off. Press START CAMERA."
            else -> GestureGuide.laptopControlOn
        })
        setLine(laptopLine, "Laptop", GestureService.status["laptop"] ?: "Not checked yet")
        refreshLink()
    }

    /** The phone's view of the link: packets, round trips, timeouts, and how often a hand was in view. */
    private fun refreshLink() {
        val pointer = prefs.getBoolean("gesture_pointer", false)
        setLine(linkLine, "Link", when {
            !pointer -> "Off. Turn Laptop control on to send your hand."
            !GestureService.running -> "Waiting for the camera."
            else -> describeLink(GestureService.linkStats.summary())
        })
    }

    private fun toggleCamera() = setCamera(!GestureService.running)

    private fun setCamera(on: Boolean) {
        if (on == GestureService.running) {
            refreshButtons()
            return
        }
        if (on) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                askCamera.launch(Manifest.permission.CAMERA)
                return
            }
            GestureService.start(this)     // the service reports "Starting..." and then "On" or the reason it failed
        } else {
            GestureService.stop(this)
        }
        refreshButtons()
    }

    private fun toggleSend() {
        val on = !prefs.getBoolean("gesture_send", false)
        prefs.edit().putBoolean("gesture_send", on).apply()
        GestureService.sendToLaptop = on
        refreshButtons()
    }

    private fun togglePointer() {
        val on = !prefs.getBoolean("gesture_pointer", false)
        prefs.edit().putBoolean("gesture_pointer", on).apply()
        GestureService.pointerMode = on
        refreshButtons()
        if (on) checkLaptop()
    }

    /** Asks the laptop whether it can move the pointer, and shows the answer. One request each time it is pressed. */
    private fun checkLaptop() {
        GestureService.status["laptop"] = "Checking..."
        refreshButtons()
        Thread {
            val reply = LaptopApi.call(this, "GET", "/gesture/track/status", null, 3000)
            val msg = when {
                reply.has("error") -> reply.optString("error")
                // The workspace pointer needs no extra program; only the laptop's mouse helper does.
                !reply.optBoolean("xdotool", true) ->
                    "Connected. The laptop's mouse helper (xdotool) is not installed, so its mouse cannot move. The workspace still works."
                else -> "Connected to the laptop."
            }
            GestureService.status["laptop"] = msg
            // The laptop's tuning, as it holds it now (one more request, same press).
            val tuning = LaptopApi.call(this, "GET", "/gesture/settings", null, 3000)
            runOnUiThread {
                tuning.optJSONObject("settings")?.let { laptopTuning = it }
                if (tuning.optJSONObject("settings") == null) {
                    showTuneNote("Could not read the laptop's settings. " + tuning.optString("error", "Press CHECK THE LAPTOP to try again."))
                }
                refreshButtons()
                refreshTuning()
            }
        }.start()
    }

    // ----- Tuning: the laptop keeps the values. The phone asks for a change and shows the laptop's answer. -----

    private fun showTuneNote(text: String) {
        tuneNote.text = text
    }

    /** Shows the laptop's values. The buttons wait until they are known and no change is in flight. */
    private fun refreshTuning() {
        val s = laptopTuning
        tuneText.text = if (s == null) {
            "Laptop settings: not read yet. Press CHECK THE LAPTOP."
        } else {
            buildString {
                append("Pointer sensitivity: ").append(Tuning.oneDecimal(s.optDouble("sensitivity", Tuning.SENS_DEFAULT)))
                append("  (0.5 steadier ... 2.0 quicker)")
                append("\nDwell: ").append(Tuning.oneDecimal(s.optDouble("dwell_s", Tuning.DWELL_DEFAULT))).append(" seconds to hold still")
                append("\nReach across the picture: ").append(spanText(s.optJSONArray("reach_x")))
                append("\nReach up and down: ").append(spanText(s.optJSONArray("reach_y")))
                if (s.optBoolean("locked")) append("\nThe laptop's workspace is LOCKED. Press L on the laptop to unlock it.")
            }
        }
        val ready = laptopTuning != null && !tuneBusy
        for (b in listOf(sensDownBtn, sensUpBtn, dwellDownBtn, dwellUpBtn, resetBtn)) b.isEnabled = ready
        refreshCalib()
    }

    private fun spanText(a: JSONArray?): String =
        if (a == null || a.length() != 2) "not set" else "${Tuning.percent(a.optDouble(0))} to ${Tuning.percent(a.optDouble(1))}"

    private fun stepSensitivity(up: Boolean) {
        val cur = laptopTuning?.optDouble("sensitivity", Double.NaN) ?: Double.NaN
        if (cur.isNaN()) {
            showTuneNote("Read the laptop's settings first. Press CHECK THE LAPTOP.")
            return
        }
        val next = Tuning.sensitivityStep(cur, up)
        if (next == cur) {
            showTuneNote(if (up) "Sensitivity is already at its quickest." else "Sensitivity is already at its steadiest.")
            return
        }
        sendTuning(JSONObject().put("sensitivity", next), "sensitivity")
    }

    private fun stepDwell(up: Boolean) {
        val cur = laptopTuning?.optDouble("dwell_s", Double.NaN) ?: Double.NaN
        if (cur.isNaN()) {
            showTuneNote("Read the laptop's settings first. Press CHECK THE LAPTOP.")
            return
        }
        val next = Tuning.dwellStep(cur, up)
        if (next == cur) {
            showTuneNote(if (up) "Dwell is already at its longest." else "Dwell is already at its shortest.")
            return
        }
        sendTuning(JSONObject().put("dwell_s", next), "dwell time")
    }

    /** Puts sensitivity, dwell and the reach back to the laptop's defaults. */
    private fun resetTuning() {
        val patch = JSONObject()
            .put("sensitivity", Tuning.SENS_DEFAULT)
            .put("dwell_s", Tuning.DWELL_DEFAULT)
            .put("reach_x", JSONArray(listOf(Tuning.REACH_LO, Tuning.REACH_HI)))
            .put("reach_y", JSONArray(listOf(Tuning.REACH_LO, Tuning.REACH_HI)))
        sendTuning(patch, "the defaults")
    }

    /** One change to the laptop. The laptop checks it and answers; the screen shows that answer, never a guess. */
    private fun sendTuning(patch: JSONObject, what: String, onDone: () -> Unit = {}) {
        if (tuneBusy) return
        tuneBusy = true
        showTuneNote("Saving $what on the laptop...")
        refreshTuning()
        Thread {
            val reply = LaptopApi.call(this, "POST", "/gesture/settings", patch, 3000)
            runOnUiThread {
                tuneBusy = false
                if (reply.optBoolean("ok", false)) {
                    reply.optJSONObject("settings")?.let { laptopTuning = it }
                    showTuneNote(
                        if (reply.optBoolean("saved", true)) "Saved on the laptop."
                        else "In use now, but the laptop could not write it to its file, so it is lost when the laptop restarts."
                    )
                    onDone()
                } else {
                    showTuneNote(refusal(reply))
                }
                refreshTuning()
            }
        }.start()
    }

    /** The words for a refused change: the laptop's reasons when it gives them, otherwise what went wrong. */
    private fun refusal(reply: JSONObject): String {
        val list = reply.optJSONArray("errors")
        if (list != null && list.length() > 0) return (0 until list.length()).joinToString("\n") { list.optString(it) }
        val msg = reply.optString("error", "")
        return if (msg.isNotEmpty()) msg else "The laptop did not accept the change. Press CHECK THE LAPTOP and try again."
    }

    /** Two buttons side by side, each taking half the width. */
    private fun pairRow(left: Button, right: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in listOf(left, right)) {
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(10), dp(4), 0) }
            addView(b)
        }
    }

    // ----- Calibration: four corners of the space the finger can reach. The box is checked before it is saved. -----

    private fun startCalibration() {
        calibrating = true
        calibPoints.clear()
        recentTips.clear()
        calibOk = null
        calibMessage = ""
        if (!GestureService.running) setCamera(true)     // the corners come from the camera; it may ask for permission
        refreshCalib()
    }

    private fun cancelCalibration() {
        calibrating = false
        calibPoints.clear()
        recentTips.clear()
        calibMessage = "Calibration cancelled. Nothing was changed."
        refreshCalib()
    }

    /** CAPTURE: the last second of still fingertip frames becomes the next corner. */
    private fun captureCorner() {
        if (!GestureService.running) {
            calibMessage = "The camera is off. Press START CAMERA, then capture again."
            refreshCalib()
            return
        }
        val now = System.currentTimeMillis()
        val samples = recentTips.filter { now - it.first <= CAPTURE_WINDOW_MS }.map { it.second }
        val got = CalibrationBox.capture(samples)
        when (got) {
            is CalibrationBox.Capture.TooFew ->
                calibMessage = "I can't see your index finger well enough. Show it to the camera, then capture again."
            is CalibrationBox.Capture.Moving ->
                calibMessage = "Your finger moved while I was looking. Hold it still for a second, then capture."
            is CalibrationBox.Capture.Got -> {
                calibPoints.add(got.at)
                calibMessage = ""
                if (calibPoints.size == CalibrationBox.CORNERS) finishCalibration()
            }
        }
        refreshCalib()
    }

    private fun finishCalibration() {
        calibrating = false
        recentTips.clear()
        when (val r = CalibrationBox.fromCorners(calibPoints.toList(), mirror = true)) {
            is CalibrationBox.Result.Ok -> {
                calibOk = r
                calibMessage = ""
            }
            is CalibrationBox.Result.Refused -> {
                calibOk = null
                calibMessage = r.reasons.joinToString("\n") + "\nTo calibrate again, press CALIBRATE THE REACH."
            }
        }
    }

    /** Sends the checked box to the laptop. Nothing is sent unless the box passed the checks. */
    private fun saveReach() {
        val ok = calibOk ?: return
        val patch = JSONObject()
            .put("reach_x", JSONArray(listOf(ok.xLo, ok.xHi)))
            .put("reach_y", JSONArray(listOf(ok.yLo, ok.yHi)))
        sendTuning(patch, "the reach") {
            calibOk = null
            calibMessage = "The laptop uses this reach now."
            refreshCalib()
        }
    }

    /** Shows the calibration step, the buttons that go with it, and any box waiting to be saved. */
    private fun refreshCalib() {
        val ok = calibOk
        calibStartBtn.visibility = if (calibrating) View.GONE else View.VISIBLE
        calibCaptureBtn.visibility = if (calibrating) View.VISIBLE else View.GONE
        calibCancelBtn.visibility = if (calibrating) View.VISIBLE else View.GONE
        calibSaveBtn.visibility = if (ok != null && !calibrating) View.VISIBLE else View.GONE
        calibSaveBtn.isEnabled = !tuneBusy
        calibCaptureBtn.text = "CAPTURE CORNER ${calibPoints.size + 1} OF ${CalibrationBox.CORNERS}"
        calibText.text = when {
            calibrating -> CORNER_HINTS[calibPoints.size] +
                "\nCorners captured: ${calibPoints.size} of ${CalibrationBox.CORNERS}." +
                (if (calibMessage.isEmpty()) "" else "\n$calibMessage")
            ok != null -> "The reach box is ready. Across the picture: ${Tuning.percent(ok.xLo)} to ${Tuning.percent(ok.xHi)}. " +
                "Up and down: ${Tuning.percent(ok.yLo)} to ${Tuning.percent(ok.yHi)}. Save it to the laptop, or calibrate again."
            calibMessage.isNotEmpty() -> calibMessage
            else -> CALIB_INTRO
        }
    }

    // ----- Places: the phone sends the place with each sign, and the laptop picks the action from it -----

    private fun currentPlace(): String {
        val p = prefs.getString("gesture_context", "global") ?: "global"
        return if (p == "global" || p in places) p else "global"
    }

    private fun cyclePlace() {
        if (places.isEmpty()) return
        val next = places[(places.indexOf(currentPlace()) + 1) % places.size]
        prefs.edit().putString("gesture_context", next).apply()
        refreshPlace()
        refreshRows()
    }

    private fun refreshPlace() {
        placeBtn.text = "PLACE: ${currentPlace()}   (tap for the next place)"
    }

    // ----- The action list -----

    private fun buildRow(a: MapAction): ActionRow {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, 0) }
        }
        box.addView(txt("${a.emoji}  ${a.description}", 16f))
        val detail = txt("", 13f, "#9FB8C6")
        box.addView(detail)
        box.setOnClickListener { select(a) }
        return ActionRow(a, box, detail)
    }

    private fun select(a: MapAction) {
        if (recording != null) return          // do not change the sign while it is being recorded
        selected = a
        selectedText.text = "Picked: ${a.emoji} ${a.description}\nSign to record: ${a.sign}"
        recordInfo.text = "Press RECORD 3 SECONDS when you are ready."
        refreshRows()
    }

    /** Redraws every action's line: its sign, whether the sign is trained, and whether it is set up in this place. */
    private fun refreshRows() {
        val trained = trainer.labels()
        val place = currentPlace()
        val signs = actions.map { it.sign }.distinct()
        val done = signs.count { (trained[it] ?: 0) > 0 }
        val extra = trained.keys.filter { it !in signs }.sorted()
        summaryText.text = if (signs.isEmpty()) "" else buildString {
            append("Signs trained: $done of ${signs.size}.")
            if (extra.isNotEmpty()) append("\nAlso trained, not in the action list: ${extra.joinToString(", ")}.")
        }
        val picked = selected?.id
        for (r in rows) {
            val a = r.action
            val n = trained[a.sign] ?: 0
            val inPlace = if (a.place == place) "  ·  this place" else ""
            val state = if (n > 0) "trained ($n examples)" else "not trained yet"
            val others = a.shares.mapNotNull { id -> actions.firstOrNull { it.id == id }?.description }
            val clash = if (others.isEmpty()) "" else "\nShares this sign here with: ${others.joinToString("; ")}. Nothing runs until one of them changes."
            r.detail.text = "sign: ${a.sign}  ·  place: ${a.place}$inPlace\n${GestureMap.statusWord(a.status)}  ·  $state$clash"
            r.box.background = if (picked == a.id) panel("#17324A", "#4FE3FF") else panel("#0C1826", "#26425A")
        }
    }

    /** What the sign does in the current place, picked the same way the laptop picks it. */
    private fun whatRuns(sign: String): String {
        val place = currentPlace()
        val hit = actions.firstOrNull { it.sign == sign && it.place == place }
            ?: actions.firstOrNull { it.sign == sign && it.place == "global" }
            ?: return "nothing is set for this sign here"
        if (hit.shares.isNotEmpty()) return "two actions share this sign here, so nothing runs until one changes"
        return "${hit.description} (${GestureMap.statusWord(hit.status)})"
    }

    // ----- Recording: 1 second to get ready, then 3 seconds of frames are kept -----

    private fun startRecording() {
        val pick = selected
        if (pick == null) {
            recordInfo.text = "Pick an action from the list first."
            return
        }
        if (recording != null) return
        if (!GestureService.running) {
            recordInfo.text = "Turn the camera on first (START CAMERA). When it says On, press RECORD again."
            return
        }
        recording = pick.sign
        collecting = false
        recordFrames.clear()
        GestureService.training = true          // nothing is sent to the laptop while the sign is recorded
        recordBtn.isEnabled = false
        recordInfo.text = "Get ready... then hold the sign."
        handler.postDelayed({ startCollecting() }, GET_READY_MS)
        handler.postDelayed({ finishRecording() }, GET_READY_MS + RECORD_MS)
    }

    private fun startCollecting() {
        if (recording == null) return
        collecting = true
        recordInfo.text = "RECORDING: hold the sign still."
    }

    /** Saves what the recording gave. Frames without a clear hand are skipped. */
    private fun finishRecording() {
        val sign = recording ?: return
        val frames = ArrayList(recordFrames)
        recording = null
        collecting = false
        recordFrames.clear()
        GestureService.training = false
        recordBtn.isEnabled = true
        val result = trainer.recordVideo(sign, frames)
        recordInfo.text = if (result == null) "That sign has no name." else describeRecording(sign, result)
        refreshRows()
    }

    private fun describeRecording(sign: String, r: Recording): String {
        if (r.kept == 0) {
            return "Only ${r.usable} frames had a clear hand, so nothing was saved. Try again: closer, in good light, and keep the hand in view for the whole 3 seconds."
        }
        val base = "Saved '$sign': ${r.kept} examples from ${r.usable} clear frames (${r.total} in all)."
        val look = r.lookalike ?: return base
        return "$base\nThis looks a lot like '$look'. If they are different signs, record one of them again with a clearer difference."
    }

    private fun toggleTest() {
        testing = !testing
        testBtn.text = if (testing) "STOP TEST" else "TEST A SIGN"
        if (testing && !GestureService.running) setCamera(true)
        testInfo.text = if (testing) "Show a sign to the camera." else "Test stopped."
    }

    private fun forgetSelected() {
        val pick = selected
        if (pick == null) {
            Toast.makeText(this, "Pick an action first", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = trainer.forget(pick.sign)
        Toast.makeText(this, if (ok) "Forgot the sign '${pick.sign}'" else "'${pick.sign}' has no examples yet", Toast.LENGTH_SHORT).show()
        refreshRows()
    }

    /** Called on the main thread for every camera frame while this screen is open. */
    private fun handleHand(hand: List<FloatArray>?) {
        val seen = hand != null
        if (seen != handSeen) {                       // only redraw when it changes
            handSeen = seen
            setLine(handLine, "Hand", if (seen) "Seen" else "Not seen")
        }
        if (calibrating && hand != null && hand.size > 8) {
            // Landmark 8 is the index fingertip. Keep only the last second of them for the next CAPTURE.
            val tip = hand[8]
            val now = System.currentTimeMillis()
            recentTips.add(now to CalibrationBox.Point(tip[0].toDouble(), tip[1].toDouble()))
            recentTips.removeAll { now - it.first > CAPTURE_WINDOW_MS }
        }
        if (recording != null) {
            if (collecting) {
                if (hand != null && recordFrames.size < MAX_RECORD_FRAMES) recordFrames.add(hand)
                recordInfo.text = if (hand == null) "RECORDING: the hand is not in view. Bring it back."
                    else "RECORDING: hold the sign still. Hand seen in ${recordFrames.size} frames."
            }
            return
        }
        if (testing) {
            val now = System.currentTimeMillis()
            if (now - lastTest < 300) return
            lastTest = now
            val (label, conf) = if (hand != null) trainer.classify(hand) else (null to 0f)
            testInfo.text = if (label != null) "I see: $label (${(conf * 100).toInt()}% sure)\nIn this place: ${whatRuns(label)}" else "No gesture matched"
        }
    }
}
