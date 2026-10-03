package com.jarvis.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** Quick Settings tile: swipe down, tap "Jarvis" to start or stop the control server. */
class JarvisTileService : TileService() {

    companion object {
        fun refresh(context: Context) {
            try {
                TileService.requestListeningState(context, ComponentName(context, JarvisTileService::class.java))
            } catch (e: Exception) {
                // tile not added by the user: nothing to refresh
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        paint()
    }

    override fun onClick() {
        super.onClick()
        if (JarvisService.running) {
            stopService(Intent(this, JarvisService::class.java))
            JarvisService.running = false
        } else {
            val p = getSharedPreferences("jarvis_control", Context.MODE_PRIVATE)
            val svc = Intent(this, JarvisService::class.java)
                .putExtra(JarvisService.ACTION_TOKEN_EXTRA, p.getString("token", "") ?: "")
            ContextCompat.startForegroundService(this, svc)
            JarvisService.running = true
        }
        paint()
    }

    private fun paint() {
        val t = qsTile ?: return
        t.state = if (JarvisService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Jarvis"
        t.updateTile()
    }
}
