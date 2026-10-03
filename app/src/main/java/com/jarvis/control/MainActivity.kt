package com.jarvis.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
                setTextColor(android.graphics.Color.parseColor("#E8FBFF"))
                setBackgroundResource(R.drawable.btn_primary)
                backgroundTintList = null
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
                setOnClickListener { onClick() }
            }

        val voiceStatus = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#9FD8E6"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Phone mic: wake word \"" + (prefs.getString("wake_word", "jarvis") ?: "jarvis") + "\""
        }
        val say: (String) -> Unit = { msg -> runOnUiThread { voiceStatus.text = msg } }

        val wakeSwitch = android.widget.Switch(this).apply {
            text = "Always listen for \"Jarvis\" (phone mic)"
            setTextColor(android.graphics.Color.parseColor("#E8FBFF"))
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

        binding.bootStartCheck.isChecked = prefs.getBoolean("start_on_boot", false)
        binding.bootStartCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("start_on_boot", checked).apply()
        }

        requestNeededPermissions()
        updateButtons()

        // entrance: cards slide in one after another
        if (savedInstanceState == null) {
            val col = binding.contentColumn
            for (i in 0 until col.childCount) {
                val v = col.getChildAt(i)
                v.alpha = 0f
                v.translationY = 48f
                v.animate().alpha(1f).translationY(0f).setStartDelay(70L * i).setDuration(480).start()
            }
        }
    }

    // ------------------------------------------------------------ connection health (while app is open)
    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthTick = object : Runnable {
        override fun run() {
            checkLaptopHealth()
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
            try {
                val t0 = System.nanoTime()
                val c = URL("http://$ip:$port/hello").openConnection() as HttpURLConnection
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
                binding.statLaptop.text = ip
                binding.statLatency.text = if (ms >= 0) "$ms ms" else "\u2014"
                if (ms >= 0) setStatus(binding.healthLabel, "Connected to $ip  \u00B7  $ms ms", R.color.success)
                else setStatus(binding.healthLabel, "Laptop $ip not reachable (same WiFi? laptop on?)", R.color.warning)
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
        while (System.currentTimeMillis() < deadline && state == "pending") {
            try {
                Thread.sleep(1000)
                val c = URL("http://${laptop.ip}:${laptop.port}/connect/status?id=$id").openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 3000
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val reply = JSONObject(stream.bufferedReader().readText())
                state = reply.optString("state", "pending")
                if (state == "approved") lanToken = reply.optString("lan_token")
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
            .remove("laptop_ip").remove("laptop_token")
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
                if (reply.optBoolean("ok")) lanToken = reply.optString("lan_token") else error = reply.optString("error", "pairing failed")
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
    }

    override fun onPause() {
        super.onPause()
        healthHandler.removeCallbacks(healthTick)
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
