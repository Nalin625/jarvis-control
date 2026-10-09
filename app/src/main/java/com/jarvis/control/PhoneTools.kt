package com.jarvis.control

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import java.util.Locale

/**
 * Small phone tools started from the home grid. Each returns a short, human-readable result.
 * Nothing here needs root or a permission beyond what the manifest already declares.
 */
object PhoneTools {
    private var torchOn = false

    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1e9)

    /** Starts an activity; returns null on success or an error message. */
    private fun launch(ctx: Context, intent: Intent): String? = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "No app on this phone can open that."
    } catch (e: Exception) {
        "Could not open it: ${e.message}"
    }

    /** Toggles the rear flashlight (torch). */
    fun flashlight(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            "This phone has no flash."
        } else {
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            if (torchOn) "Flashlight on." else "Flashlight off."
        }
    } catch (e: Exception) {
        torchOn = false
        "Flashlight failed: ${e.message}"
    }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        }
        return ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** A short double buzz, so you can find the phone or check that the vibration works. */
    fun buzz(ctx: Context): String {
        vibrator(ctx).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        return "Buzzing."
    }

    /** Battery percentage and whether it is charging. */
    fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = if (pct in 0..100) "$pct%" else "unknown"
        return "Battery $level, " + (if (charging) "charging." else "not charging.")
    }

    /** Free and total space in the phone's data partition. */
    fun storage(ctx: Context): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        val free = st.availableBlocksLong * st.blockSizeLong
        return "Storage: ${gb(free)} free of ${gb(total)}."
    }

    /** Model, Android version and memory. */
    fun info(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}. " +
            "RAM ${gb(mi.availMem)} free of ${gb(mi.totalMem)}."
    }

    fun openWifi(ctx: Context): String = launch(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) ?: "Opened Wi-Fi settings."

    fun openBluetooth(ctx: Context): String =
        launch(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) ?: "Opened Bluetooth settings."

    fun openDnd(ctx: Context): String =
        launch(ctx, Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")) ?: "Opened Do Not Disturb settings."

    fun openCamera(ctx: Context): String =
        launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) ?: "Opened the camera."

    fun openCallLog(ctx: Context): String =
        launch(ctx, Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://call_log/calls"))) ?: "Opened the call log."

    /** Text of the most recent call summary Jarvis wrote (or a note that there is none). */
    fun lastCall(ctx: Context): String = CallBrain.recent(ctx, 1)

    /** Copies the most recent call summary to the clipboard. */
    fun copyLastCall(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Jarvis call summary", CallBrain.recent(ctx, 1)))
        "Copied the last call summary."
    } catch (e: Exception) {
        "Could not copy: ${e.message}"
    }

    /** Raises media volume one step and reports the new level. */
    fun musicVolumeUp(ctx: Context): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Music volume $now of ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}."
    }
}
