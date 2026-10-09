package com.jarvis.control

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Teach JARVIS your gestures, and turn the phone's gesture camera on or off. */
class GestureActivity : AppCompatActivity() {

    private lateinit var trainer: GestureTrainer
    private lateinit var status: TextView
    private lateinit var listText: TextView
    private lateinit var nameBox: EditText
    private lateinit var testBtn: Button
    private lateinit var cameraSwitch: Switch
    private lateinit var sendSwitch: Switch
    private lateinit var pointerSwitch: Switch
    private var recording: String? = null
    private var recordLeft = 0
    private var testing = false
    private var lastRecord = 0L
    private var lastTest = 0L
    private val prefs by lazy { getSharedPreferences("jarvis_control", MODE_PRIVATE) }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setCamera(true) else status.text = "Camera permission is needed for gestures."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trainer = GestureTrainer.shared(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun line(s: String, size: Float = 16f): TextView = TextView(this).apply {
            text = s
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }

        col.addView(line("Jarvis Gestures", 22f))
        status = line("Camera is off.")
        col.addView(status)

        cameraSwitch = Switch(this).apply {
            text = "Camera on (watch for gestures)"
            setOnCheckedChangeListener { _, on -> setCamera(on) }
        }
        col.addView(cameraSwitch)

        sendSwitch = Switch(this).apply {
            text = "Send recognised gestures to the laptop"
            isChecked = prefs.getBoolean("gesture_send", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("gesture_send", on).apply()
                GestureService.sendToLaptop = on
            }
        }
        col.addView(sendSwitch)

        pointerSwitch = Switch(this).apply {
            text = "Move the laptop mouse with my index finger (pinch = click)"
            isChecked = prefs.getBoolean("gesture_pointer", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("gesture_pointer", on).apply()
                GestureService.pointerMode = on
            }
        }
        col.addView(pointerSwitch)

        col.addView(line("1. Type a gesture name, e.g. pinch.\n2. Press Record and hold the sign still.\n3. Press Test to check it."))
        nameBox = EditText(this).apply {
            hint = "gesture name (e.g. pinch)"
            setSingleLine(true)
        }
        col.addView(nameBox)
        col.addView(Button(this).apply {
            text = "Record 10 examples"
            setOnClickListener { startRecording() }
        })
        testBtn = Button(this).apply {
            text = "Test my gestures"
            setOnClickListener { toggleTest() }
        }
        col.addView(testBtn)
        col.addView(Button(this).apply {
            text = "Forget this name"
            setOnClickListener { forgetName() }
        })

        listText = line("")
        col.addView(listText)
        refreshList()

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        GestureService.onHand = { hand -> handleHand(hand) }
        cameraSwitch.isChecked = GestureService.running
    }

    override fun onPause() {
        super.onPause()
        GestureService.onHand = null
        recording = null
        testing = false
        testBtn.text = "Test my gestures"
    }

    private fun setCamera(on: Boolean) {
        if (on == GestureService.running) return
        if (on) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                askCamera.launch(Manifest.permission.CAMERA)
                return
            }
            GestureService.start(this)
            status.text = "Camera starting..."
        } else {
            GestureService.stop(this)
            status.text = "Camera is off."
        }
    }

    private fun startRecording() {
        val name = nameBox.text.toString().trim()
        if (name.isEmpty()) {
            status.text = "Type a gesture name first."
            return
        }
        if (!GestureService.running) setCamera(true)
        recording = name
        recordLeft = 10
        lastRecord = 0L
        status.text = "Hold '$name' steady. Recording..."
    }

    private fun toggleTest() {
        testing = !testing
        testBtn.text = if (testing) "Stop test" else "Test my gestures"
        if (testing && !GestureService.running) setCamera(true)
        if (!testing) status.text = "Test stopped."
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
            "No gestures trained yet."
        } else {
            "Trained gestures:\n" + l.entries.joinToString("\n") { "${it.key}: ${it.value} examples" }
        }
    }

    /** Called on the main thread for every camera frame while this screen is open. */
    private fun handleHand(hand: List<FloatArray>?) {
        val name = recording
        if (name != null) {
            if (hand == null) {
                status.text = "Show your hand to the camera."
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastRecord < 400) return          // one example every 0.4 s, so they differ a little
            lastRecord = now
            val n = trainer.record(name, hand)
            if (n == null) {
                status.text = "Hand not clear. Move closer, in good light."
                return
            }
            recordLeft--
            if (recordLeft <= 0) {
                recording = null
                status.text = "Saved '$name' ($n examples). Press Test to check it."
                refreshList()
            } else {
                status.text = "Recording '$name': $recordLeft to go"
            }
            return
        }
        if (testing) {
            val now = System.currentTimeMillis()
            if (now - lastTest < 300) return
            lastTest = now
            val (label, conf) = if (hand != null) trainer.classify(hand) else (null to 0f)
            status.text = if (label != null) "I see: $label (${(conf * 100).toInt()}% sure)" else "No gesture matched"
        }
    }
}
