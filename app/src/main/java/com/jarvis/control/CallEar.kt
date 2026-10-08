package com.jarvis.control

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Listens through the phone mic with our OWN AudioRecord (not the Google recognizer, which Android silences during a call)
 * and cuts out one spoken sentence. Gemini then turns that audio into text.
 */
object CallEar {
    class Result(val wav: ByteArray?, val dead: Boolean, val failed: Boolean)

    @Volatile var stop = false
    private const val RATE = 16000

    @SuppressLint("MissingPermission")
    fun capture(waitMs: Int = 9000, maxMs: Int = 14000): Result {
        stop = false
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return Result(null, false, true)
        var rec: AudioRecord? = null
        for (src in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
            try {
                val r = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
            } catch (e: Exception) { }
        }
        val r = rec ?: return Result(null, false, true)
        val frame = RATE / 50                      // 20 ms
        val buf = ShortArray(frame)
        val pcm = ByteArrayOutputStream()
        val pre = ArrayDeque<ByteArray>()          // 300 ms before speech starts
        var noise = 0.0
        var frames = 0
        var speech = false
        var silentFrames = 0
        var anySignal = false
        val t0 = System.currentTimeMillis()
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) return Result(null, false, true)
            while (!stop) {
                val n = r.read(buf, 0, frame)
                if (n <= 0) { Thread.sleep(10); continue }
                var sum = 0.0
                var peak = 0
                for (i in 0 until n) { val v = abs(buf[i].toInt()); sum += v.toDouble() * v; peak = max(peak, v) }
                val rms = Math.sqrt(sum / n)
                if (peak > 20) anySignal = true
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) { bytes[i * 2] = (buf[i].toInt() and 0xff).toByte(); bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte() }
                frames++
                if (frames <= 15) { noise = if (noise == 0.0) rms else noise * 0.8 + rms * 0.2 }
                val thr = max(450.0, noise * 3.0)
                val now = System.currentTimeMillis() - t0
                if (!speech) {
                    pre.addLast(bytes); if (pre.size > 15) pre.removeFirst()
                    if (frames > 15 && rms > thr) {
                        speech = true
                        pre.forEach { pcm.write(it) }
                        pre.clear()
                        silentFrames = 0
                    } else if (now > waitMs) {
                        return Result(null, !anySignal && now > 3000, false)
                    }
                } else {
                    pcm.write(bytes)
                    if (rms > thr * 0.7) silentFrames = 0 else silentFrames++
                    if (silentFrames > 60 || now > maxMs) break      // 1.2 s of quiet = end of sentence
                }
            }
        } catch (e: Exception) {
            return Result(null, false, true)
        } finally {
            try { r.stop() } catch (e: Exception) { }
            r.release()
        }
        if (!speech || pcm.size() < RATE) return Result(null, false, false)
        return Result(wav(pcm.toByteArray()), false, false)
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun w32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun w16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); w32(36 + pcm.size); out.write("WAVEfmt ".toByteArray())
        w32(16); w16(1); w16(1); w32(RATE); w32(RATE * 2); w16(2); w16(16)
        out.write("data".toByteArray()); w32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
