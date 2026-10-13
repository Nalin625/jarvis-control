package com.jarvis.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Click Instant" inside Instagram. While Instagram is in front, the laptop asks this service to press
 * Instagram's own shutter button. It finds the button by its label. If the button has no label, the laptop
 * gets one screenshot back, asks Gemini where the button is, and sends the tap here.
 *
 * It can read the screen only while it is switched on in Settings > Accessibility, and it reads it only
 * when the laptop asks. Nothing is stored or sent from here except that one screenshot, which goes to the laptop.
 */
class InstaShutterService : AccessibilityService() {

    companion object {
        const val INSTAGRAM = "com.instagram.android"

        @Volatile var instance: InstaShutterService? = null
        @Volatile var foreground: String = ""

        private val SHUTTER_LABEL = Regex("take ?(a )?(photo|picture|pic)|capture|shutter", RegexOption.IGNORE_CASE)
    }

    private val main = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foreground = event.packageName?.toString().orEmpty()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        foreground = ""
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Presses the shutter by its label. Returns "tapped", "no label", or "error: ...". */
    fun tapShutterByLabel(): String = onMain {
        val root = rootInActiveWindow ?: return@onMain "error: Instagram's window is not readable"
        val node = findShutter(root) ?: return@onMain "no label"
        if (pressNode(node)) "tapped" else "error: the shutter was found but could not be pressed"
    }

    /** Taps a point given as fractions of the screen (0 to 1), the same numbers the AI reads off the screenshot. */
    fun tapNormalized(fx: Double, fy: Double): String = onMain {
        val m = resources.displayMetrics
        if (gestureTap((fx * m.widthPixels).toFloat(), (fy * m.heightPixels).toFloat())) {
            "tapped"
        } else {
            "error: the tap could not be sent"
        }
    }

    /** A JPEG of the whole screen, 720 pixels wide. Needs Android 11 or newer; returns null otherwise or on failure. */
    fun screenshotJpeg(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val answer = CompletableFuture<ByteArray?>()
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                answer.complete(try {
                    val buffer = result.hardwareBuffer
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val soft = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (soft == null) {
                        null
                    } else {
                        val w = 720
                        val h = soft.height * w / soft.width
                        val small = Bitmap.createScaledBitmap(soft, w, h, true)
                        val out = ByteArrayOutputStream()
                        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                })
            }

            override fun onFailure(errorCode: Int) {
                answer.complete(null)
            }
        })
        return try {
            answer.get(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    /** Runs [block] on the main thread and waits for its answer, so the HTTP thread never touches the UI thread. */
    private fun onMain(block: () -> String): String {
        val answer = CompletableFuture<String>()
        main.post {
            answer.complete(try {
                block()
            } catch (e: Exception) {
                "error: ${e.message}"
            })
        }
        return try {
            answer.get(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "error: the phone did not answer in time"
        }
    }

    /** Breadth-first search for a node whose description, text or id names a shutter. */
    private fun findShutter(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 1000) {
            val node = queue.removeFirst()
            seen++
            val label = listOfNotNull(node.contentDescription, node.text, node.viewIdResourceName)
                .joinToString(" ")
            if (label.isNotBlank() && SHUTTER_LABEL.containsMatchIn(label) &&
                !label.contains("video", ignoreCase = true)
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    /** Clicks the node or the nearest clickable parent; if neither works, taps the middle of the node. */
    private fun pressNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        for (depth in 0 until 4) {
            val c = current ?: break
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = c.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return gestureTap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
