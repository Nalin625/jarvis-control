package com.jarvis.control

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps this app's latest crashes in a private file on the phone, so you can send them to yourself on purpose.
 * Nothing leaves the phone by itself: the share sheet opens only when you tap Share, and you pick the app.
 */
object CrashLog {
    private const val FILE = "crash.log"
    private const val MARK = "---- "
    private const val MAX_CHARS = 200_000
    private const val KEEP_CHARS = 120_000

    /** Call once when the app starts. A crash still happens as before; this only writes it down first. */
    fun install(app: Context) {
        val ctx = app.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(File(ctx.filesDir, FILE), thread, error)
            } catch (e: Throwable) {
                // writing the log must never hide the real crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Adds one crash to [file]. Once the file grows past the limit, only its newest part is kept. */
    @Synchronized
    fun record(file: File, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file.appendText(MARK + stamp + "  thread " + thread.name + "\n" + error.stackTraceToString() + "\n")
        if (file.length() > MAX_CHARS) {
            file.writeText(file.readText().takeLast(KEEP_CHARS))
        }
    }

    /** How many crashes the text holds. */
    fun countIn(text: String): Int = text.split("\n").count { it.startsWith(MARK) }

    fun text(ctx: Context): String {
        val file = File(ctx.filesDir, FILE)
        return try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun count(ctx: Context): Int = countIn(text(ctx))

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }

    /** Opens the share sheet with the log as plain text. Returns false when there is nothing to send. */
    fun share(from: Context): Boolean {
        val log = text(from)
        if (log.isBlank()) return false
        val body = "Jarvis Control crash log\n" +
            "Phone: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + "\n\n" + log
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Jarvis Control crash log")
            .putExtra(Intent.EXTRA_TEXT, body)
        from.startActivity(Intent.createChooser(send, "Send crash log to"))
        return true
    }
}
