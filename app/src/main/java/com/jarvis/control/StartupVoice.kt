package com.jarvis.control

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records your own "startup voice" (up to 15 s) and uploads it to the laptop, which plays it when Jarvis starts. */
object StartupVoice {
    @Volatile var recording = false
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    fun start(ctx: Context): Boolean {
        if (recording || !MicService.hasPermission(ctx)) return false
        MicService.setPaused(true)          // the wake-word listener must let go of the mic
        synchronized(pcm) { pcm.reset() }
        recording = true
        thread = Thread {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec: AudioRecord? = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            } catch (e: Exception) {
                null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                recording = false
            } else {
                rec.startRecording()
                val buf = ByteArray(4096)
                val t0 = System.currentTimeMillis()
                while (recording && System.currentTimeMillis() - t0 < 15000) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
                }
                try { rec.stop() } catch (e: Exception) {}
                rec.release()
                recording = false
            }
        }.also { it.start() }
        return true
    }

    /** Stops, wraps the audio as a WAV and uploads it. done() gets a short status message (background thread). */
    fun stopAndUpload(ctx: Context, done: (String) -> Unit) {
        recording = false
        val app = ctx.applicationContext
        Thread {
            try { thread?.join(2000) } catch (e: Exception) {}
            MicService.setPaused(false)
            val data = synchronized(pcm) { pcm.toByteArray() }
            if (data.size < 16000) {
                done("Too short - hold the mic closer and speak for a few seconds.")
            } else {
                val ok = LaptopApi.postBytes(app, "/startup/voice", wav(data), 15000)
                done(if (ok) "Saved. Your laptop will play it at startup." else "Couldn't reach the laptop. Same WiFi? Connected?")
            }
        }.start()
    }

    private fun wav(pcmData: ByteArray): ByteArray {
        val rate = 16000
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcmData.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcmData.size)
        return h.array() + pcmData
    }
}
