package com.jarvis.control

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import org.json.JSONObject

/**
 * Quick Settings tile: tap it to take a photo through the laptop, the same as saying
 * "jarvis click instant" to the laptop. Works from the notification shade without opening the app.
 */
class ClickInstantTileService : TileService() {

    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        paint(busy)
    }

    override fun onClick() {
        super.onClick()
        if (!LaptopApi.paired(this)) {
            Toast.makeText(this, "Pair with your laptop in Jarvis Control first.", Toast.LENGTH_LONG).show()
            return
        }
        if (busy) return                              // a photo is already on its way
        busy = true
        paint(true)
        val app = applicationContext
        Thread {
            val r = LaptopApi.call(app, "POST", "/command", JSONObject().put("command", "click instant"), 20000)
            val text = r.optString("response", "").ifEmpty { r.optString("error", "No reply from the laptop.") }
            ui.post {
                busy = false
                paint(false)
                Toast.makeText(app, text.removePrefix("[local] ").take(200), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun paint(active: Boolean) {
        val t = qsTile ?: return
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Click Instant"
        t.updateTile()
    }
}
