package com.jarvis.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.control.databinding.ActivityMainBinding
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import android.os.Handler
import android.os.Looper
import kotlin.random.Random
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var serviceRunning = false
    private var pageIndex = 0
    private var scanAnim: ValueAnimator? = null
    private var deckResponse: android.widget.TextView? = null
    private var homeGrid: android.widget.LinearLayout? = null
    private var deckGrid: android.widget.LinearLayout? = null
    private var historyRow: android.widget.LinearLayout? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results not individually needed — we just proceed either way and
           JarvisServer reports a clear per-feature error if something's missing */ }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) {
            MicService.start(this)
            Toast.makeText(this, "Microphone allowed. Switch on \"Always listen\" to start.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Microphone not allowed - the laptop can't use your phone mic.", Toast.LENGTH_LONG).show()
        }
    }

    // Android asks the user "Start recording or casting?" — this launches that prompt and,
    // if accepted, hands the approval to ScreenShareService.
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenShareService::class.java)
                .putExtra(ScreenShareService.EXTRA_CODE, result.resultCode)
                .putExtra(ScreenShareService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, svc)
            binding.root.postDelayed({ updateScreenUi() }, 900)
        } else {
            setStatus(binding.screenStatusLabel, "Permission was declined", R.color.warning)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("jarvis_control", MODE_PRIVATE)

        val savedToken = prefs.getString("token", null) ?: generateToken().also {
            prefs.edit().putString("token", it).apply()
        }
        binding.tokenField.setText(savedToken)

        binding.ipLabel.text = "This phone's IP: ${getLocalIpAddress() ?: "not connected to WiFi"}\nPort: ${JarvisService.PORT}"

        binding.startButton.setOnClickListener { startJarvisService() }
        binding.stopButton.setOnClickListener { stopJarvisService() }
        binding.saveTokenButton.setOnClickListener {
            val newToken = binding.tokenField.text.toString().trim()
            prefs.edit().putString("token", newToken).apply()
            setStatus(binding.statusLabel, "Token saved \u2014 restart the server for it to take effect", R.color.text_dim)
        }

        // ---- Phone -> laptop notification forwarding ----
        binding.laptopIpField.setText(prefs.getString("laptop_ip", ""))
        binding.laptopPortField.setText(prefs.getString("laptop_port", "8899"))
        binding.laptopTokenField.setText(prefs.getString("laptop_token", ""))
        binding.notifForwardCheck.isChecked = prefs.getBoolean("notif_forward_enabled", false)

        binding.saveLaptopButton.setOnClickListener {
            prefs.edit()
                .putString("laptop_ip", binding.laptopIpField.text.toString().trim())
                .putString("laptop_port", binding.laptopPortField.text.toString().trim().ifEmpty { "8899" })
                .putString("laptop_token", binding.laptopTokenField.text.toString().trim())
                .apply()
            binding.notifAccessStatusLabel.text = "\u25CF Laptop connection saved"
            binding.notifAccessStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        }

        binding.notifForwardCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notif_forward_enabled", checked).apply()
        }

        binding.grantNotifAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ---- One-step pairing with the laptop ----
        binding.pairButton.setOnClickListener { pairWithLaptop() }

        // ---- Start the AI in Termux together with the server ----
        binding.termuxAutoStartCheck.isChecked = prefs.getBoolean("termux_autostart", false)
        binding.termuxCommandField.setText(prefs.getString("termux_command", null) ?: TermuxLauncher.DEFAULT_COMMAND)
        binding.termuxAutoStartCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("termux_autostart", checked).apply()
        }
        binding.startAiButton.setOnClickListener {
            val cmd = binding.termuxCommandField.text.toString().trim().ifEmpty { TermuxLauncher.DEFAULT_COMMAND }
            prefs.edit().putString("termux_command", cmd).apply()
            val err = TermuxLauncher.run(this, cmd)
            if (err == null) setStatus(binding.aiStatusLabel, "Start command sent to Termux", R.color.success)
            else setStatus(binding.aiStatusLabel, err, R.color.warning)
        }

        // ---- Screen projection to the laptop ----
        binding.shareScreenButton.setOnClickListener {
            if (ScreenShareState.sharing) {
                stopService(Intent(this, ScreenShareService::class.java))
                binding.root.postDelayed({ updateScreenUi() }, 500)
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        // ---- Easy connect: find the laptop, approve a matching code on the laptop ----
        binding.findLaptopButton.setOnClickListener { findAndConnect() }
        binding.unpairButton.setOnClickListener { unpair() }
        binding.openRemoteButton.setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        // ---- Voice: the phone is the always-on microphone; startup voice recorder ----
        fun voiceButton(label: String, onClick: () -> Unit): android.widget.Button =
            android.widget.Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
                setBackgroundResource(R.drawable.btn_primary)
                backgroundTintList = null
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
                setOnClickListener { onClick() }
            }

        val voiceStatus = android.widget.TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Phone mic: wake word \"" + (prefs.getString("wake_word", "jarvis") ?: "jarvis") + "\""
        }
        val say: (String) -> Unit = { msg -> runOnUiThread { voiceStatus.text = msg } }

        val wakeSwitch = android.widget.Switch(this).apply {
            text = "Always listen for \"Jarvis\" (phone mic)"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            isChecked = prefs.getBoolean("wake_listen", false) && MicService.hasPermission(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (on && !MicService.hasPermission(this@MainActivity)) {
                    isChecked = false
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    if (on) MicService.start(this@MainActivity)
                    MicService.setListening(this@MainActivity, on)
                    say(if (on) "Listening. Say: Jarvis, open chrome. Only commands go to the laptop." else "Phone mic is off.")
                }
            }
        }
        lateinit var recBtn: android.widget.Button
        recBtn = voiceButton("RECORD STARTUP VOICE") {
            if (!StartupVoice.recording && recBtn.text.toString().startsWith("RECORD")) {
                if (!MicService.hasPermission(this)) {
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else if (StartupVoice.start(this)) {
                    recBtn.text = "STOP & SAVE (max 15 s)"
                    say("Recording... speak your startup message.")
                }
            } else {
                recBtn.text = "RECORD STARTUP VOICE"
                say("Saving...")
                StartupVoice.stopAndUpload(this) { msg -> say(msg) }
            }
        }
        val playBtn = voiceButton("PLAY STARTUP VOICE ON LAPTOP") {
            Thread {
                val r = LaptopApi.call(this, "POST", "/command", JSONObject().put("command", "startup voice test"))
                say(r.optString("response", r.optString("error", "Done")))
            }.start()
        }
        val micAllow = voiceButton("ALLOW MICROPHONE") { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }

        val voiceCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            addView(wakeSwitch)
            addView(micAllow)
            addView(recBtn)
            addView(playBtn)
            addView(voiceStatus)
        }
        val remoteParent = binding.openRemoteButton.parent as android.widget.LinearLayout
        remoteParent.addView(voiceCard, remoteParent.indexOfChild(binding.openRemoteButton) + 1)
        if (MicService.hasPermission(this) && !MicService.alive) MicService.start(this)

        // ---- Call assistant: Jarvis answers calls, talks to the caller, summary goes to the laptop ----
        val callStatus = android.widget.TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        fun callModeLabel(): String = when (CallAssistService.mode(this)) {
            "ask" -> "ASK ME for each call"
            "auto" -> "AUTO after " + prefs.getInt("call_delay", 15) + " s"
            else -> "OFF"
        }
        lateinit var callModeBtn: android.widget.Button
        lateinit var callDelayBtn: android.widget.Button
        callModeBtn = voiceButton("CALL ASSISTANT: " + callModeLabel()) {
            if (!CallAssistService.hasCorePerms(this)) {
                permissionLauncher.launch(CallAssistService.PERMS)
                callStatus.text = "Allow phone, microphone and contacts, then tap again."
            } else {
                val next = when (CallAssistService.mode(this)) { "off" -> "ask"; "ask" -> "auto"; else -> "off" }
                val ok = CallAssistService.setMode(this, next)
                callModeBtn.text = "CALL ASSISTANT: " + callModeLabel()
                callStatus.text = if (!ok) "Could not start. Allow the permissions." else when (next) {
                    "ask" -> "Each call shows buttons: Jarvis answers / I'll answer."
                    "auto" -> "Jarvis answers by itself after the delay. Tap I'll answer on the alert to take it yourself."
                    else -> "Call assistant is off."
                }
            }
        }
        callDelayBtn = voiceButton("AUTO-ANSWER DELAY: " + prefs.getInt("call_delay", 15) + " s") {
            val cur = prefs.getInt("call_delay", 15)
            val nxt = if (cur <= 8) 15 else if (cur <= 15) 25 else 8
            prefs.edit().putInt("call_delay", nxt).apply()
            callDelayBtn.text = "AUTO-ANSWER DELAY: $nxt s"
            callModeBtn.text = "CALL ASSISTANT: " + callModeLabel()
        }
        val callTestBtn = voiceButton("TEST CALL ASSISTANT (no real call)") {
            if (!CallAssistService.hasCorePerms(this)) {
                permissionLauncher.launch(CallAssistService.PERMS)
            } else {
                if (!CallAssistService.alive) CallAssistService.start(this)
                callStatus.text = "Test: speak to the phone like a caller. Say goodbye to finish."
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!CallAssistService.test()) callStatus.text = "Turn the call assistant on first (ASK or AUTO)."
                }, 1200)
            }
        }
        fun keyLabel(): String {
            val k = prefs.getString("call_gemini_key", "").orEmpty()
            return if (k.isEmpty()) "PASTE GEMINI KEY (copy it first)" else "GEMINI KEY SAVED ...${k.takeLast(4)} (hold to remove)"
        }
        lateinit var callKeyBtn: android.widget.Button
        callKeyBtn = voiceButton(keyLabel()) {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val txt = clip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (txt.startsWith("AIza") && txt.length > 30 && !txt.contains(" ")) {
                prefs.edit().putString("call_gemini_key", txt).apply()
                callKeyBtn.text = keyLabel()
                callStatus.text = "Gemini key saved. Calls use Gemini first, the phone AI is the backup."
            } else {
                callStatus.text = "Copy your Gemini API key first (starts with AIza), then tap this again."
            }
        }
        callKeyBtn.setOnLongClickListener {
            prefs.edit().remove("call_gemini_key").apply()
            callKeyBtn.text = keyLabel()
            callStatus.text = "Gemini key removed. Calls use the phone AI."
            true
        }
        val callLogBtn = voiceButton("SHOW LAST CALL SUMMARIES") {
            callStatus.text = "Reading..."
            Thread { val t = CallBrain.recent(this, 3); runOnUiThread { callStatus.text = t } }.start()
        }
        callStatus.text = "Put the phone face-up in a quiet place. The speaker is used so the caller can hear Jarvis."
        val callCard = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            addView(callModeBtn)
            addView(callDelayBtn)
            addView(callKeyBtn)
            addView(callTestBtn)
            addView(callLogBtn)
            addView(callStatus)
        }
        remoteParent.addView(callCard, remoteParent.indexOfChild(voiceCard) + 1)
        if (CallAssistService.mode(this) != "off" && CallAssistService.hasCorePerms(this) && !CallAssistService.alive) CallAssistService.start(this)

        binding.bootStartCheck.isChecked = prefs.getBoolean("start_on_boot", false)
        binding.bootStartCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("start_on_boot", checked).apply()
        }

        requestNeededPermissions()
        updateButtons()

        // ---- pages: Home / Deck / Link / Core / Setup ----
        buildHome()
        buildStatusStrip()
        buildDeck()
        buildThemeCard()
        buildRemoteCard()
        binding.navBar.onSelect = { showPage(it) }
        pageIndex = savedInstanceState?.getInt("page") ?: 0
        showPage(pageIndex, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pageIndex != 0) showPage(0) else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        Fx.attachAll(binding.root)
        if (savedInstanceState == null) enterPage(pageIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", pageIndex)
    }

    // ------------------------------------------------------------ pages
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun pageViews(): List<View> =
        listOf(binding.pageHome, binding.pageDeck, binding.pageLink, binding.pageCore, binding.pageSetup)

    private fun columnOf(i: Int): android.view.ViewGroup = when (i) {
        0 -> binding.homeColumn
        1 -> binding.deckColumn
        2 -> binding.contentColumn
        3 -> binding.coreColumn
        else -> binding.setupColumn
    }

    /** Staggered fade/rise of a page's blocks, plus a springy pop of its tiles. */
    private fun enterPage(i: Int) {
        Fx.stagger(columnOf(i))
        val grid = when (i) { 0 -> homeGrid; 1 -> deckGrid; else -> null }
        if (grid != null) Fx.popIn(Fx.tilesOf(grid), 26L)
    }

    private fun showPage(i: Int, animate: Boolean = true) {
        val pages = pageViews()
        val old = pages[pageIndex]
        val nw = pages[i]
        val dir = if (i >= pageIndex) 1f else -1f
        val same = old === nw
        pageIndex = i
        binding.navBar.select(i, animate)
        if (!animate) {
            pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
            return
        }
        if (same) return
        old.animate().cancel()
        old.animate().alpha(0f).translationX(-dir * dpf(36f)).setDuration(140).withEndAction {
            if (pages[pageIndex] !== old) {
                old.visibility = View.GONE
                old.alpha = 1f
                old.translationX = 0f
            }
        }.start()
        nw.animate().cancel()
        nw.visibility = View.VISIBLE
        nw.alpha = 0f
        nw.translationX = dir * dpf(36f)
        nw.animate().alpha(1f).translationX(0f).setStartDelay(90).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
        binding.pagesFrame.postDelayed({ enterPage(i) }, 90)
    }

    private fun buildHome() {
        val tiles = listOf(
            Tile("\u26A1", "Server on") { startJarvisService() },
            Tile("\u23F9", "Server off") { stopJarvisService() },
            Tile("\uD83D\uDCE1", "Find laptop") { findAndConnect() },
            Tile("\uD83D\uDDA5", "Remote") { startActivity(Intent(this, RemoteActivity::class.java)) },
            Tile("\uD83E\uDDE0", "Start AI") { binding.startAiButton.performClick(); showPage(3) },
            Tile("\uD83D\uDCFA", "Share screen") { binding.shareScreenButton.performClick() },
            Tile("\uD83C\uDF99", "Mic access") { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            Tile("\uD83D\uDD14", "Notif access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            Tile("\uD83D\uDD17", "Pair") { showPage(2) },
            Tile("\uD83D\uDDC2", "Laptop deck") { showPage(1) },
            Tile("\uD83D\uDCCB", "Paste to laptop") { pasteToLaptop() },
            Tile("\uD83D\uDCCA", "Laptop status") { showPage(1); sendDeck("status report") },
            Tile("\uD83C\uDFA8", "Theme") { toggleTheme() },
            Tile("\u2699", "Setup") { showPage(4) }
        )
        val g = Fx.grid(this, tiles, 3)
        homeGrid = g
        binding.homeColumn.addView(g)
    }

    private fun buildDeck() {
        val tiles = listOf(
            Tile("\uD83D\uDD0A", "Vol up") { sendDeck("volume up") },
            Tile("\uD83D\uDD09", "Vol down") { sendDeck("volume down") },
            Tile("\uD83D\uDD07", "Mute") { sendDeck("mute") },
            Tile("\uD83D\uDD08", "Unmute") { sendDeck("unmute") },
            Tile("\u2600", "Bright +") { sendDeck("brightness up") },
            Tile("\uD83C\uDF19", "Bright -") { sendDeck("brightness down") },
            Tile("\uD83D\uDCF8", "Screenshot") { sendDeck("screenshot") },
            Tile("\uD83D\uDD12", "Lock") { sendDeck("lock") },
            Tile("\uD83D\uDD0B", "Battery") { sendDeck("battery") },
            Tile("\uD83D\uDCBE", "RAM") { sendDeck("ram usage") },
            Tile("\uD83D\uDCF6", "WiFi") { sendDeck("wifi status") },
            Tile("\uD83C\uDF10", "IP") { sendDeck("ip address") },
            Tile("\uD83D\uDCF0", "News") { sendDeck("news") },
            Tile("\u26C5", "Weather") { sendDeck("weather") },
            Tile("\uD83E\uDE7A", "Diagnose") { sendDeck("run diagnostics") },
            Tile("\uD83D\uDCA4", "Sleep") { sendDeck("sleep") },
            Tile("\uD83D\uDCCA", "Status") { sendDeck("status report") },
            Tile("\uD83D\uDEB6", "Away mode") { sendDeck("away mode") },
            Tile("\u2615", "Stay awake") { sendDeck("stay awake on") },
            Tile("\u23F2", "Off in...") { pickShutdownTimer() },
            Tile("\u274C", "Cancel timer") { sendDeck("cancel shutdown") },
            Tile("\uD83D\uDD04", "Restart") { sendDeck("restart") },
            Tile("\u23FB", "Shut down") { sendDeck("shutdown") }
        )
        val g = Fx.grid(this, tiles, 4, 74f)
        deckGrid = g
        val col = binding.deckColumn
        col.addView(g)

        val field = android.widget.EditText(this).apply {
            hint = "type any Jarvis command"
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_faint))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_input)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            maxLines = 1
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            val p = dpf(12f).toInt()
            setPadding(p, p, p, p)
        }
        val send = android.widget.Button(this).apply {
            text = "SEND"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
            setBackgroundResource(R.drawable.btn_primary)
            backgroundTintList = null
        }
        val fire = {
            val c = field.text.toString().trim()
            if (c.isNotEmpty()) { sendDeck(c); field.setText("") }
        }
        send.setOnClickListener { fire() }
        field.setOnEditorActionListener { _, _, _ -> fire(); true }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpf(12f).toInt() }
            layoutParams = lp
            addView(field, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpf(8f).toInt() })
        }
        col.addView(row)

        val card = HudCard(this).apply {
            val pd = dpf(14f).toInt()
            setPadding(pd, pd, pd, pd)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpf(12f).toInt() }
        }
        val resp = android.widget.TextView(this).apply {
            text = "Tap a tile. The laptop's reply shows here."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        card.addView(resp)
        deckResponse = resp
        col.addView(card)

        val hs = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpf(10f).toInt() }
        }
        val hr = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        hs.addView(hr)
        historyRow = hr
        col.addView(hs)
        refreshHistory()
    }

    /** Feature 1: choose when the laptop should shut down (15 min / 30 min / 1 h / 2 h). */
    private fun pickShutdownTimer() {
        val labels = arrayOf("In 15 minutes", "In 30 minutes", "In 1 hour", "In 2 hours")
        val cmds = arrayOf("shutdown in 15 minutes", "shutdown in 30 minutes", "shutdown in 1 hour", "shutdown in 2 hours")
        AlertDialog.Builder(this)
            .setTitle("Shut the laptop down...")
            .setItems(labels) { _, i -> sendDeck(cmds[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Feature 4: while the Deck page is open, the phone's volume keys change the LAPTOP volume. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (pageIndex == 1 && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            sendDeck(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) "volume up" else "volume down")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Feature 5: send whatever is on this phone's clipboard to the laptop's clipboard. */
    private fun pasteToLaptop() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Your clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val r = LaptopApi.call(this, "POST", "/clipboard", JSONObject().put("text", text), 8000)
            val ok = r.optBoolean("ok", false)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Sent to the laptop clipboard" else r.optString("error", "Couldn't send"), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Feature 3: a live strip on Home with the laptop's battery, load and IPs (refreshes every 30 s). */
    private var statusStrip: android.widget.TextView? = null
    private var statusTicks = 0

    private fun buildStatusStrip() {
        val tv = android.widget.TextView(this).apply {
            text = "LAPTOP  //  waiting for the link..."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10.5f
            setBackgroundResource(R.drawable.bg_chip)
            val p = dpf(10f).toInt()
            setPadding(p, p, p, p)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpf(10f).toInt() }
        }
        statusStrip = tv
        binding.homeColumn.addView(tv, 1)   // right under the header
    }

    private fun refreshStatusStrip() {
        val tv = statusStrip ?: return
        if (!laptopOnline || pageIndex != 0) return
        Thread {
            val r = LaptopApi.call(this, "POST", "/command", JSONObject().put("command", "status report"), 6000)
            val txt = r.optString("response", "").removePrefix("[local] ").trim()
            if (txt.isNotEmpty()) runOnUiThread { tv.text = txt.replace("  |  ", "  \u00B7  ") }
        }.start()
    }

    private fun deckHistory(): List<String> {
        val raw = prefs.getString("deck_history", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun pushHistory(cmd: String) {
        val list = (listOf(cmd) + deckHistory().filter { it != cmd }).take(8)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("deck_history", arr.toString()).apply()
        refreshHistory()
    }

    private fun pinned(): Set<String> {
        val raw = prefs.getString("deck_pins", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    private fun togglePin(cmd: String) {
        val set = pinned().toMutableSet()
        if (!set.add(cmd)) set.remove(cmd)
        val arr = org.json.JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString("deck_pins", arr.toString()).apply()
        refreshHistory()
        Toast.makeText(this, if (cmd in set) "Pinned: $cmd" else "Unpinned", Toast.LENGTH_SHORT).show()
    }

    /** Feature 2: chips with pinned (long-press, shown first with a star) and recent commands; tap to run. */
    private fun refreshHistory() {
        val row = historyRow ?: return
        row.removeAllViews()
        val pins = pinned()
        val all = pins.toList() + deckHistory().filter { it !in pins }
        for (c in all) {
            row.addView(android.widget.TextView(this).apply {
                text = if (c in pins) "\u2605 $c" else c
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(ContextCompat.getColor(this@MainActivity, if (c in pins) R.color.accent else R.color.text_dim))
                setBackgroundResource(R.drawable.bg_chip)
                val ph = dpf(12f).toInt(); val pv = dpf(7f).toInt()
                setPadding(ph, pv, ph, pv)
                isClickable = true
                setOnClickListener { sendDeck(c) }
                setOnLongClickListener { togglePin(c); true }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpf(6f).toInt() }
                Fx.press(this)
            })
        }
    }

    private fun sendDeck(cmd: String) {
        val out = deckResponse ?: return
        out.setTextColor(ContextCompat.getColor(this, R.color.accent))
        out.text = "> $cmd\n..."
        Thread {
            val t0 = System.nanoTime()
            var r = LaptopApi.call(this, "POST", "/command", JSONObject().put("command", cmd), 20000)
            if (r.optString("error", "").startsWith("Can't reach")) {
                // one automatic retry (phones often wake the radio on the first try)
                Thread.sleep(700)
                r = LaptopApi.call(this, "POST", "/command", JSONObject().put("command", cmd), 20000)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val err = r.optString("error", "")
            val hasRemote = !(prefs.getString("laptop_ip_remote", "") ?: "").isEmpty()
            val tip = if (err.startsWith("Can't reach") && !hasRemote)
                "\nAway from home? Install Tailscale on both devices (Setup > FAR AWAY)." else ""
            val text = if (err.isNotEmpty()) err + tip else r.optString("response", "Done")
            runOnUiThread {
                out.setTextColor(ContextCompat.getColor(this, if (err.isNotEmpty()) R.color.warning else R.color.text_primary))
                out.text = "> $cmd\n$text" + if (err.isEmpty()) "\n\u2014 ${ms} ms" else ""
                out.performHapticFeedback(
                    if (err.isEmpty()) android.view.HapticFeedbackConstants.VIRTUAL_KEY
                    else android.view.HapticFeedbackConstants.LONG_PRESS
                )
                if (err.isEmpty()) pushHistory(cmd)
            }
        }.start()
    }

    // ------------------------------------------------------------ theme (cyan <-> black)
    private fun toggleTheme() {
        val black = !prefs.getBoolean("theme_black", false)
        prefs.edit().putBoolean("theme_black", black).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (black) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )  // recreates the activity with the other colour set; the current page is restored
    }

    /** Setup page: the laptop's internet (Tailscale) address, used when the home-WiFi address doesn't answer. */
    private fun buildRemoteCard() {
        val card = HudCard(this).apply {
            val pd = dpf(14f).toInt()
            setPadding(pd, pd, pd, pd)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpf(12f).toInt() }
        }
        card.addView(android.widget.TextView(this).apply {
            text = "FAR AWAY  //  INTERNET LINK"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textSize = 12f
        })
        card.addView(android.widget.TextView(this).apply {
            text = "Install Tailscale on this phone and the laptop, then the laptop's 100.x address appears here by itself after pairing. At home the WiFi address is used; anywhere else this one."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            textSize = 11f
            setPadding(0, dpf(6f).toInt(), 0, dpf(8f).toInt())
        })
        val f = android.widget.EditText(this).apply {
            hint = "laptop internet IP (100.x.x.x)"
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_faint))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_input)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            isSingleLine = true
            val p = dpf(12f).toInt()
            setPadding(p, p, p, p)
            setText(prefs.getString("laptop_ip_remote", ""))
        }
        card.addView(f)
        card.addView(android.widget.Button(this).apply {
            text = "SAVE INTERNET IP"
            isAllCaps = false
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
            setBackgroundResource(R.drawable.btn_primary)
            backgroundTintList = null
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpf(8f).toInt() }
            setOnClickListener {
                prefs.edit().putString("laptop_ip_remote", f.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                checkLaptopHealth()
            }
        })
        binding.setupColumn.addView(card, 2)
    }

    private fun buildThemeCard() {
        val card = HudCard(this).apply {
            val pd = dpf(14f).toInt()
            setPadding(pd, pd, pd, pd)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpf(12f).toInt() }
        }
        val black = prefs.getBoolean("theme_black", false)
        card.addView(android.widget.TextView(this).apply {
            text = "THEME  //  " + if (black) "BLACK" else "CYAN"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textSize = 12f
        })
        card.addView(android.widget.Button(this).apply {
            text = if (black) "SWITCH TO CYAN" else "SWITCH TO BLACK"
            isAllCaps = false
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
            setBackgroundResource(R.drawable.btn_primary)
            backgroundTintList = null
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpf(10f).toInt() }
            setOnClickListener { toggleTheme() }
        })
        // right after the page title (index 0)
        binding.setupColumn.addView(card, 1)
    }

    // ------------------------------------------------------------ connection health (while app is open)
    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthTick = object : Runnable {
        override fun run() {
            checkLaptopHealth()
            if (statusTicks++ % 6 == 0) refreshStatusStrip()
            healthHandler.postDelayed(this, 5000)
        }
    }

    // ------------------------------------------------------------ hero (reactor + chips + stat tiles)
    private var laptopOnline = false
    private var aiOnline = false

    private fun setChip(view: android.widget.TextView, on: Boolean, warn: Boolean = false) {
        view.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.success else if (warn) R.color.warning else R.color.text_dim)
        )
    }

    private fun refreshHero() {
        val running = serviceRunning || JarvisService.running
        binding.reactorView.level = when {
            running && laptopOnline -> 2
            running || laptopOnline -> 1
            else -> 0
        }
        val (badge, colour) = when {
            running && laptopOnline -> Pair("\u25CF LINKED", R.color.success)
            running -> Pair("\u25CF SERVER UP", R.color.accent)
            laptopOnline -> Pair("\u25CF LAPTOP SEEN", R.color.accent)
            else -> Pair("\u25CF OFFLINE", R.color.warning)
        }
        binding.heroBadge.text = badge
        binding.heroBadge.setTextColor(ContextCompat.getColor(this, colour))
        val paired = !(prefs.getString("laptop_ip", "") ?: "").isEmpty()
        setChip(binding.chipServer, running)
        setChip(binding.chipLaptop, laptopOnline, warn = paired && !laptopOnline)
        setChip(binding.chipAi, aiOnline)
    }

    private fun updateBatteryTile() {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        binding.statBattery.text = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "\u2014"
    }

    /** Ollama runs on this same phone (Termux), so "AI online" = port 11434 answers locally. */
    private fun checkAi() {
        Thread {
            var up = false
            try {
                val c = URL("http://127.0.0.1:11434/api/tags").openConnection() as HttpURLConnection
                c.connectTimeout = 700
                c.readTimeout = 700
                up = c.responseCode == 200
                c.disconnect()
            } catch (e: Exception) {
                up = false
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                aiOnline = up
                refreshHero()
            }
        }.start()
    }

    private fun checkLaptopHealth() {
        checkAi()
        updateBatteryTile()
        val ip = prefs.getString("laptop_ip", "") ?: ""
        if (ip.isEmpty()) {
            setStatus(binding.healthLabel, "Not connected", R.color.text_dim)
            laptopOnline = false
            binding.statLatency.text = "\u2014"
            binding.statLaptop.text = "\u2014"
            refreshHero()
            return
        }
        val port = prefs.getString("laptop_port", "8899") ?: "8899"
        Thread {
            var ms = -1L
            val hostIp = LaptopApi.host(this).ifEmpty { ip }
            try {
                val t0 = System.nanoTime()
                val c = URL("http://$hostIp:$port/hello").openConnection() as HttpURLConnection
                c.connectTimeout = 2500
                c.readTimeout = 2500
                if (c.responseCode == 200) ms = (System.nanoTime() - t0) / 1_000_000
                c.disconnect()
            } catch (e: Exception) {
                ms = -1L
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                laptopOnline = ms >= 0
                binding.statLaptop.text = hostIp
                binding.statLatency.text = if (ms >= 0) "$ms ms" else "\u2014"
                val remoteIp = prefs.getString("laptop_ip_remote", "") ?: ""
                val via = if (remoteIp.isNotEmpty() && hostIp == remoteIp && hostIp != ip) "  \u00B7  via INTERNET" else "  \u00B7  via WiFi"
                if (ms >= 0) setStatus(binding.healthLabel, "Connected to $hostIp  \u00B7  $ms ms$via", R.color.success)
                else setStatus(binding.healthLabel, "Laptop $hostIp not reachable (laptop on? Tailscale on?)", R.color.warning)
                refreshHero()
            }
        }.start()
    }

    // ------------------------------------------------------------ find + connect
    private fun findAndConnect() {
        binding.findLaptopButton.isEnabled = false
        binding.connectCodeLabel.visibility = android.view.View.GONE
        setStatus(binding.connectStatusLabel, "Looking for your laptop...", R.color.text_dim)
        Thread {
            val laptops = DiscoveryClient.find()
            runOnUiThread {
                if (laptops.isEmpty()) {
                    binding.findLaptopButton.isEnabled = true
                    setStatus(
                        binding.connectStatusLabel,
                        "No laptop found. Same WiFi? Is Jarvis running? (Or use the code box below.)",
                        R.color.warning
                    )
                } else {
                    connectToLaptop(laptops[0])
                }
            }
        }.start()
    }

    private fun connectToLaptop(laptop: FoundLaptop) {
        val phoneToken = binding.tokenField.text.toString().trim()
        prefs.edit().putString("token", phoneToken).apply()
        setStatus(binding.connectStatusLabel, "Asking ${laptop.name} to connect...", R.color.text_dim)
        Thread {
            var error: String? = null
            var code = ""
            var id = ""
            try {
                val c = URL("http://${laptop.ip}:${laptop.port}/connect/request").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("name", Build.MODEL)
                    .put("control_port", JarvisService.PORT)
                    .put("phone_token", phoneToken)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val reply = JSONObject(stream.bufferedReader().readText())
                if (reply.optBoolean("ok")) {
                    code = reply.optString("code")
                    id = reply.optString("id")
                } else error = reply.optString("error", "laptop refused")
                c.disconnect()
            } catch (e: Exception) {
                error = "Can't reach the laptop at ${laptop.ip}"
            }
            if (error != null) {
                runOnUiThread {
                    binding.findLaptopButton.isEnabled = true
                    setStatus(binding.connectStatusLabel, error, R.color.warning)
                }
                return@Thread
            }
            runOnUiThread {
                binding.connectCodeLabel.text = code
                binding.connectCodeLabel.visibility = android.view.View.VISIBLE
                setStatus(binding.connectStatusLabel, "Tap Approve on the laptop if it shows the same code", R.color.accent)
            }
            waitForApproval(laptop, id)
        }.start()
    }

    private fun waitForApproval(laptop: FoundLaptop, id: String) {
        val deadline = System.currentTimeMillis() + 65_000
        var state = "pending"
        var lanToken = ""
        var tsIp = ""
        while (System.currentTimeMillis() < deadline && state == "pending") {
            try {
                Thread.sleep(1000)
                val c = URL("http://${laptop.ip}:${laptop.port}/connect/status?id=$id").openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 3000
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val reply = JSONObject(stream.bufferedReader().readText())
                state = reply.optString("state", "pending")
                if (state == "approved") { lanToken = reply.optString("lan_token"); tsIp = reply.optString("ts_ip") }
                c.disconnect()
            } catch (e: Exception) {
                // transient network hiccup: keep polling until the deadline
            }
        }
        runOnUiThread {
            binding.findLaptopButton.isEnabled = true
            binding.connectCodeLabel.visibility = android.view.View.GONE
            when (state) {
                "approved" -> {
                    prefs.edit()
                        .putString("laptop_ip", laptop.ip)
                        .putString("laptop_port", laptop.port.toString())
                        .putString("laptop_token", lanToken)
                        .putBoolean("notif_forward_enabled", true)
                        .apply()
                    if (tsIp.isNotEmpty()) prefs.edit().putString("laptop_ip_remote", tsIp).apply()
                    binding.laptopIpField.setText(laptop.ip)
                    binding.laptopPortField.setText(laptop.port.toString())
                    binding.laptopTokenField.setText(lanToken)
                    binding.notifForwardCheck.isChecked = true
                    setStatus(binding.connectStatusLabel, "Connected to ${laptop.name}", R.color.success)
                    Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show()
                    checkLaptopHealth()
                }
                "denied" -> setStatus(binding.connectStatusLabel, "The laptop said no", R.color.warning)
                else -> setStatus(binding.connectStatusLabel, "Timed out. Tap Find my laptop to try again", R.color.warning)
            }
        }
    }

    private fun unpair() {
        val ip = prefs.getString("laptop_ip", "") ?: ""
        val port = prefs.getString("laptop_port", "8899") ?: "8899"
        val tok = prefs.getString("laptop_token", "") ?: ""
        if (ip.isNotEmpty()) {
            // tell the laptop to forget this phone (best effort: fine if it is off)
            Thread {
                try {
                    val c = URL("http://$ip:$port/phone/bye").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 2500
                    c.readTimeout = 2500
                    c.setRequestProperty("X-Jarvis-Token", tok)
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write("{}".toByteArray()) }
                    c.responseCode
                    c.disconnect()
                } catch (e: Exception) {
                    // laptop unreachable: local unpair still happens below
                }
            }.start()
        }
        prefs.edit()
            .remove("laptop_ip").remove("laptop_ip_remote").remove("laptop_token")
            .putBoolean("notif_forward_enabled", false)
            .apply()
        binding.laptopIpField.setText("")
        binding.laptopTokenField.setText("")
        binding.notifForwardCheck.isChecked = false
        setStatus(binding.connectStatusLabel, "Unpaired. Tap Find my laptop to connect again", R.color.text_dim)
        setStatus(binding.healthLabel, "Not connected", R.color.text_dim)
    }

    private fun updateScreenUi() {
        if (ScreenShareState.sharing) {
            binding.shareScreenButton.text = "Stop sharing screen"
            setStatus(binding.screenStatusLabel, "Sharing \u2014 open Phone Screen on the laptop", R.color.success)
        } else {
            binding.shareScreenButton.text = "Start sharing screen"
            setStatus(binding.screenStatusLabel, "Not sharing", R.color.text_dim)
        }
    }

    private fun pairWithLaptop() {
        val raw = binding.pairCodeField.text.toString().trim()
        val m = Regex("""^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d+))?\D+(\d{6})$""").find(raw)
        if (m == null) {
            setStatus(binding.pairStatusLabel, "Type the laptop's code like: 192.168.1.5 482913", R.color.warning)
            return
        }
        val ip = m.groupValues[1]
        val port = m.groupValues[2].ifEmpty { "8899" }
        val pin = m.groupValues[3]
        val phoneToken = binding.tokenField.text.toString().trim()
        prefs.edit().putString("token", phoneToken).apply()
        setStatus(binding.pairStatusLabel, "Pairing...", R.color.text_dim)

        Thread {
            var error: String? = null
            var lanToken = ""
            var tsIp = ""
            try {
                val conn = URL("http://$ip:$port/pair").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("pin", pin)
                    .put("control_port", JarvisService.PORT)
                    .put("phone_token", phoneToken)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val reply = JSONObject(stream.bufferedReader().readText())
                if (reply.optBoolean("ok")) { lanToken = reply.optString("lan_token"); tsIp = reply.optString("ts_ip") } else error = reply.optString("error", "pairing failed")
                conn.disconnect()
            } catch (e: Exception) {
                error = "Can't reach the laptop at $ip (same WiFi? Jarvis server running?)"
            }
            runOnUiThread {
                if (error != null) {
                    setStatus(binding.pairStatusLabel, error, R.color.warning)
                } else {
                    prefs.edit()
                        .putString("laptop_ip", ip)
                        .putString("laptop_port", port)
                        .putString("laptop_token", lanToken)
                        .putBoolean("notif_forward_enabled", true)
                        .apply()
                    if (tsIp.isNotEmpty()) prefs.edit().putString("laptop_ip_remote", tsIp).apply()
                    binding.laptopIpField.setText(ip)
                    binding.laptopPortField.setText(port)
                    binding.laptopTokenField.setText(lanToken)
                    binding.notifForwardCheck.isChecked = true
                    setStatus(binding.pairStatusLabel, "Paired with $ip \u2014 tokens filled in on both sides", R.color.success)
                    Toast.makeText(this, "Paired!", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // notification access is granted/revoked from a separate system settings screen,
        // so re-check every time the user comes back to this activity
        updateNotifAccessLabel()
        updateScreenUi()
        FindPhone.stop(this)  // opening the app silences "find my phone"
        if (JarvisService.running && !serviceRunning) {
            // started from the Quick Settings tile or at boot
            serviceRunning = true
            setStatus(binding.statusLabel, "Running \u2014 your laptop can now reach this phone", R.color.success)
            updateButtons()
        }
        healthHandler.removeCallbacks(healthTick)
        healthHandler.post(healthTick)
        startScan()
    }

    private fun startScan() {
        scanAnim?.cancel()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val band = dpf(90f)
        scanAnim = ValueAnimator.ofFloat(-band, h).apply {
            duration = 7000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { binding.scanLine.translationY = it.animatedValue as Float }
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        healthHandler.removeCallbacks(healthTick)
        scanAnim?.cancel()
    }

    private fun updateNotifAccessLabel() {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        val granted = enabledPackages.contains(packageName)
        if (granted) {
            setStatus(binding.notifAccessStatusLabel, "Notification access granted", R.color.success)
        } else {
            setStatus(binding.notifAccessStatusLabel, "Notification access NOT granted \u2014 tap the button above", R.color.warning)
        }
    }

    private fun requestNeededPermissions() {
        val toRequest = mutableListOf<String>()
        toRequest.add(Manifest.permission.CAMERA) // for flashlight control
        toRequest.add(Manifest.permission.ACCESS_FINE_LOCATION) // "phone location" from the laptop
        toRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        toRequest.add("com.termux.permission.RUN_COMMAND") // to start the AI in Termux
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = toRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startJarvisService() {
        val token = binding.tokenField.text.toString().trim()
        val intent = Intent(this, JarvisService::class.java)
        intent.putExtra(JarvisService.ACTION_TOKEN_EXTRA, token)
        ContextCompat.startForegroundService(this, intent)
        serviceRunning = true
        setStatus(binding.statusLabel, "Running \u2014 your laptop can now reach this phone", R.color.success)
        updateButtons()
    }

    private fun stopJarvisService() {
        stopService(Intent(this, JarvisService::class.java))
        serviceRunning = false
        setStatus(binding.statusLabel, "Stopped", R.color.text_dim)
        updateButtons()
    }

    private fun setStatus(view: android.widget.TextView, text: String, colorRes: Int) {
        view.text = "\u25CF $text"
        view.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateButtons() {
        binding.startButton.isEnabled = !serviceRunning
        binding.stopButton.isEnabled = serviceRunning
        refreshHero()
    }

    private fun generateToken(): String {
        val chars = "abcdef0123456789"
        return (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}
