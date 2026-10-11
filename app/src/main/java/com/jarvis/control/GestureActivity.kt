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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Teach JARVIS your gestures, see what the camera and the laptop are doing,
 * and turn the camera and the laptop pointer on or off.
 */
class GestureActivity : AppCompatActivity() {

    companion object {
        private const val RECORD_MS = 3000L        // a sign is kept for this long
        private const val GET_READY_MS = 1000L     // a count-in before it is kept
        private const val MAX_RECORD_FRAMES = 120  // enough clear frames; more adds nothing
    }

    /** One line of the action list: the action, its box on screen, and the text that says what it needs. */
    private class ActionRow(val action: MapAction, val box: LinearLayout, val detail: TextView)

    private lateinit var trainer: GestureTrainer
    private lateinit var cameraLine: TextView
    private lateinit var handLine: TextView
    private lateinit var pointerLine: TextView
    private lateinit var laptopLine: TextView
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
    private var visible = false
    private var actions: List<MapAction> = emptyList()
    private var places: List<String> = emptyList()
    private val rows = ArrayList<ActionRow>()
    private var selected: MapAction? = null
    private var recording: String? = null         // the sign being recorded, while a recording runs
    private var collecting = false                // true during the seconds that are kept
    private val recordFrames = ArrayList<List<FloatArray>>()
    private val handler = Handler(Looper.getMainLooper())
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
        root.addView(txt("Your phone camera watches your hand. Only the gesture name goes to the laptop.", 15f, "#9FB8C6"))

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
        now.addView(cameraLine)
        now.addView(handLine)
        now.addView(pointerLine)
        now.addView(laptopLine)
        cameraBtn = btn("START CAMERA") { toggleCamera() }
        now.addView(cameraBtn)
        now.addView(txt("A notification stays up while the camera is on.", 13f, "#9FB8C6"))
        root.addView(now)

        // 2. Settings: each button says whether it is on
        val settings = card("SETTINGS")
        sendBtn = btn("") { toggleSend() }
        pointerBtn = btn("") { togglePointer() }
        settings.addView(sendBtn)
        settings.addView(pointerBtn)
        settings.addView(txt("Pointer: point with your index finger to move the laptop mouse. Pinch to click. Make a fist to pause, open palm to resume.", 14f, "#9FB8C6"))
        settings.addView(btn("CHECK THE LAPTOP") { checkLaptop() })
        root.addView(settings)

        // 3. Teach each action: pick it, then record its sign for 3 seconds
        val teach = card("1 · TEACH EACH ACTION")
        teach.addView(txt("Pick an action from the list. Press RECORD, then do its hand sign for 3 seconds, holding it still in view. Turn on Show my video to see your hand while you record.", 15f, "#9FB8C6"))
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
        val test = card("2 · TEST IT")
        testInfo = txt("Press TEST MY GESTURES, then show a sign to the camera.", 18f)
        test.addView(testInfo)
        testBtn = btn("TEST MY GESTURES") { toggleTest() }
        test.addView(testBtn)
        root.addView(test)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#05090F"))
            addView(root)
        })
        refreshPlace()
        refreshRows()
        refreshButtons()
        checkLaptop()          // one request when the screen opens; nothing repeats by itself
    }

    override fun onResume() {
        super.onResume()
        visible = true
        GestureService.onHand = { hand -> handleHand(hand) }
        GestureService.onStatus = { _, _ -> refreshButtons() }
        GestureService.onVideo = { pic, hand, sure -> showVideo(pic, hand, sure) }
        applyVideo()
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
        testing = false
        testBtn.text = "TEST MY GESTURES"
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
        pointerBtn.text = "Laptop pointer (index finger):  " + (if (pointer) "ON" else "OFF")
        setLine(cameraLine, "Camera", GestureService.status["camera"] ?: "Off")
        if (handSeen == null) setLine(handLine, "Hand", "Waiting for the camera")
        setLine(pointerLine, "Pointer", when {
            !pointer -> "Off"
            !GestureService.running -> "On, but the camera is off. Press START CAMERA."
            else -> "On. Your index finger moves the laptop mouse."
        })
        setLine(laptopLine, "Laptop", GestureService.status["laptop"] ?: "Not checked yet")
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
            val noHelper = "Connected, but the laptop has no mouse helper. On the laptop run:  sudo apt install xdotool"
            val msg = when {
                reply.has("error") -> reply.optString("error")
                !reply.optBoolean("xdotool", true) -> noHelper
                else -> "Connected. The laptop can move the pointer."
            }
            GestureService.status["laptop"] = msg
            runOnUiThread { refreshButtons() }
        }.start()
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
        testBtn.text = if (testing) "STOP TEST" else "TEST MY GESTURES"
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
