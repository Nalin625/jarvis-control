package com.jarvis.control

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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

    private lateinit var trainer: GestureTrainer
    private lateinit var cameraLine: TextView
    private lateinit var handLine: TextView
    private lateinit var pointerLine: TextView
    private lateinit var laptopLine: TextView
    private lateinit var cameraBtn: Button
    private lateinit var sendBtn: Button
    private lateinit var pointerBtn: Button
    private lateinit var nameBox: EditText
    private lateinit var recordInfo: TextView
    private lateinit var testBtn: Button
    private lateinit var testInfo: TextView
    private lateinit var listText: TextView
    private var recording: String? = null
    private var recordLeft = 0
    private var testing = false
    private var lastRecord = 0L
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

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = panel("#0B1B28", "#2A6F86")
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(14), 0, 0) }
        addView(txt(title, 13f, "#4FE3FF", bold = true))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trainer = GestureTrainer.shared(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }
        root.addView(txt("JARVIS GESTURES", 26f, "#4FE3FF", bold = true))
        root.addView(txt("Your phone camera watches your hand. Only the gesture name goes to the laptop.", 15f, "#9FB8C6"))

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

        // 3. Teach a gesture
        val teach = card("1 · TEACH A GESTURE")
        teach.addView(txt("Type a name, press RECORD, then hold the sign still for a few seconds.", 15f, "#9FB8C6"))
        nameBox = EditText(this).apply {
            hint = "gesture name, e.g. show tabs"
            setHintTextColor(Color.parseColor("#6E8796"))
            setTextColor(Color.parseColor("#E8F7FF"))
            textSize = 18f
            setSingleLine(true)
        }
        teach.addView(nameBox)
        recordInfo = txt("Not recording.", 16f)
        teach.addView(recordInfo)
        teach.addView(btn("RECORD 10 EXAMPLES") { startRecording() })
        root.addView(teach)

        // 4. Test it
        val test = card("2 · TEST IT")
        testInfo = txt("Press TEST MY GESTURES, then show a sign to the camera.", 18f)
        test.addView(testInfo)
        testBtn = btn("TEST MY GESTURES") { toggleTest() }
        test.addView(testBtn)
        root.addView(test)

        // 5. What is trained
        val trained = card("TRAINED GESTURES")
        listText = txt("", 16f)
        trained.addView(listText)
        trained.addView(btn("FORGET THIS NAME") { forgetName() })
        root.addView(trained)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#05090F"))
            addView(root)
        })
        refreshList()
        refreshButtons()
        checkLaptop()          // one request when the screen opens; nothing repeats by itself
    }

    override fun onResume() {
        super.onResume()
        GestureService.onHand = { hand -> handleHand(hand) }
        GestureService.onStatus = { _, _ -> refreshButtons() }
        refreshButtons()
    }

    override fun onPause() {
        super.onPause()
        GestureService.onHand = null
        GestureService.onStatus = null
        recording = null
        testing = false
        testBtn.text = "TEST MY GESTURES"
        handSeen = null
    }

    private fun setLine(tv: TextView, name: String, value: String) {
        tv.text = "$name:  $value"
    }

    /** Redraws the buttons and the RIGHT NOW lines from the phone's current status. */
    private fun refreshButtons() {
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

    private fun startRecording() {
        val name = nameBox.text.toString().trim()
        if (name.isEmpty()) {
            recordInfo.text = "Type a gesture name in the box first."
            return
        }
        if (!GestureService.running) setCamera(true)
        recording = name
        recordLeft = 10
        lastRecord = 0L
        recordInfo.text = "Hold '$name' steady. Recording..."
    }

    private fun toggleTest() {
        testing = !testing
        testBtn.text = if (testing) "STOP TEST" else "TEST MY GESTURES"
        if (testing && !GestureService.running) setCamera(true)
        testInfo.text = if (testing) "Show a sign to the camera." else "Test stopped."
    }

    private fun forgetName() {
        val name = nameBox.text.toString().trim()
        val ok = trainer.forget(name)
        Toast.makeText(this, if (ok) "Forgot $name" else "No gesture called $name", Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun refreshList() {
        val l = trainer.labels()
        listText.text = if (l.isEmpty()) {
            "No gestures trained yet. Teach one above."
        } else {
            "Trained:\n" + l.entries.joinToString("\n") { "${it.key}:  ${it.value} examples" }
        }
    }

    /** Called on the main thread for every camera frame while this screen is open. */
    private fun handleHand(hand: List<FloatArray>?) {
        val seen = hand != null
        if (seen != handSeen) {                       // only redraw when it changes
            handSeen = seen
            setLine(handLine, "Hand", if (seen) "Seen" else "Not seen")
        }
        val name = recording
        if (name != null) {
            if (hand == null) {
                recordInfo.text = "Show your hand to the camera."
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastRecord < 400) return          // one example every 0.4 s, so they differ a little
            lastRecord = now
            val n = trainer.record(name, hand)
            if (n == null) {
                recordInfo.text = "Hand not clear. Move closer, in good light."
                return
            }
            recordLeft--
            if (recordLeft <= 0) {
                recording = null
                recordInfo.text = "Saved '$name' ($n examples). Now press TEST."
                refreshList()
            } else {
                recordInfo.text = "Recording '$name': $recordLeft to go"
            }
            return
        }
        if (testing) {
            val now = System.currentTimeMillis()
            if (now - lastTest < 300) return
            lastTest = now
            val (label, conf) = if (hand != null) trainer.classify(hand) else (null to 0f)
            testInfo.text = if (label != null) "I see: $label (${(conf * 100).toInt()}% sure)" else "No gesture matched"
        }
    }
}
