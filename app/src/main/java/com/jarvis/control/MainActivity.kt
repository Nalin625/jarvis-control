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
import kotlin.random.Random
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var serviceRunning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results not individually needed — we just proceed either way and
           JarvisServer reports a clear per-feature error if something's missing */ }

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

        // ---- Jarvis home screen: reactor on top; HOLD it to open every app on the phone ----
        run {
            val scrollRoot = binding.root as android.view.ViewGroup
            val column = scrollRoot.getChildAt(0) as? android.widget.LinearLayout
            if (column != null) {
                val d = resources.displayMetrics.density
                val reactor = ReactorView(this)
                reactor.level = 2
                column.addView(reactor, 1, android.widget.LinearLayout.LayoutParams(-1, (190 * d).toInt()))
                val hint = android.widget.TextView(this)
                hint.text = "HOLD THE REACTOR FOR ALL APPS"
                hint.setTextColor(0xFF3D5157.toInt())
                hint.textSize = 10f
                hint.letterSpacing = 0.15f
                hint.gravity = android.view.Gravity.CENTER
                hint.setPadding(0, 0, 0, (14 * d).toInt())
                column.addView(hint, 2)
                HomeDrawer.attach(this, reactor)
            }
        }
        // as a home screen, Back just returns to the top instead of leaving
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
            }
        })

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

        requestNeededPermissions()
        updateButtons()
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
