package com.jarvis.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class JarvisService : Service() {

    private var server: JarvisServer? = null

    companion object {
        const val PORT = 8765
        const val SERVICE_CHANNEL_ID = "jarvis_service"
        const val NOTIFY_CHANNEL_ID = "jarvis_notifications"
        const val ACTION_TOKEN_EXTRA = "token"

        /** True while the control server is up (read by the Quick Settings tile). */
        @Volatile var running = false
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(ACTION_TOKEN_EXTRA) ?: ""

        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Jarvis Control")
            .setContentText("Listening on port $PORT for commands from your laptop")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        startForeground(1, notification)

        if (server == null) {
            server = JarvisServer(applicationContext, PORT).also {
                it.token = token
                it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            // turning the server on also starts the AI (Ollama) in Termux, if enabled in the app
            val p = getSharedPreferences("jarvis_control", MODE_PRIVATE)
            if (p.getBoolean("termux_autostart", false)) {
                val cmd = p.getString("termux_command", null)?.takeIf { it.isNotBlank() }
                    ?: TermuxLauncher.DEFAULT_COMMAND
                TermuxLauncher.run(applicationContext, cmd)
            }
        } else {
            server?.token = token
        }

        running = true
        JarvisTileService.refresh(this)

        // send any phone-start events the laptop has not confirmed yet (e.g. it was off at boot)
        Thread { PhoneStartReporter.deliver(applicationContext, 10, 30_000L) }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        JarvisTileService.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID, "Jarvis background service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(serviceChannel)

            val notifyChannel = NotificationChannel(
                NOTIFY_CHANNEL_ID, "Messages from Jarvis",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(notifyChannel)
        }
    }
}
