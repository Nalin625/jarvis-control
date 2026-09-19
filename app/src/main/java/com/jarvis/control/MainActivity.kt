package com.jarvis.control

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.control.databinding.ActivityMainBinding
import java.net.NetworkInterface
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
            binding.statusLabel.text = "Token saved. Restart the server for it to take effect."
        }

        requestNeededPermissions()
        updateButtons()
    }

    private fun requestNeededPermissions() {
        val toRequest = mutableListOf<String>()
        toRequest.add(Manifest.permission.CAMERA) // for flashlight control
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
        binding.statusLabel.text = "Running. Your laptop can now reach this phone."
        updateButtons()
    }

    private fun stopJarvisService() {
        stopService(Intent(this, JarvisService::class.java))
        serviceRunning = false
        binding.statusLabel.text = "Stopped."
        updateButtons()
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
