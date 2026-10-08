package com.jarvis.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt

/**
 * Teaches JARVIS your gestures. Keeps only the 21 hand-landmark numbers per example, never images.
 * Same logic as jarvis_gesture_trainer.py (the laptop reference copy).
 */
class GestureTrainer private constructor(private val file: File) {

    companion object {
        private const val MAX_SAMPLES = 40
        private const val MIN_SPREAD = 0.02f
        private const val K = 3
        private const val MATCH_LIMIT = 0.35f
        private const val DIMS = 63

        @Volatile
        private var shared: GestureTrainer? = null

        /** One trainer for the whole app, so the camera service and the trainer screen see the same examples. */
        fun shared(ctx: Context): GestureTrainer =
            shared ?: synchronized(this) {
                shared ?: GestureTrainer(File(ctx.applicationContext.filesDir, "gestures.json")).also { shared = it }
            }

        /** 21 landmarks become 63 numbers that ignore where the hand is and how close it is. Null if unusable. */
        fun normalize(pts: List<FloatArray>): FloatArray? {
            if (pts.size != 21) return null
            val w = pts[0]
            val rel = pts.map { floatArrayOf(it[0] - w[0], it[1] - w[1], it[2] - w[2]) }
            val m = rel[9]
            val scale = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
            if (scale < MIN_SPREAD) return null
            val out = FloatArray(DIMS)
            for (i in 0 until 21) {
                out[i * 3] = rel[i][0] / scale
                out[i * 3 + 1] = rel[i][1] / scale
                out[i * 3 + 2] = rel[i][2] / scale
            }
            return out
        }

        private fun dist(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) {
                val d = a[i] - b[i]
                s += d * d
            }
            return sqrt(s / DIMS)
        }
    }

    private val samples = HashMap<String, MutableList<FloatArray>>()

    init {
        load()
    }

    fun labels(): Map<String, Int> = samples.mapValues { it.value.size }

    /** Adds one example. Returns how many examples this gesture now has, or null if the hand was unusable. */
    @Synchronized
    fun record(rawName: String, pts: List<FloatArray>): Int? {
        val label = rawName.trim().lowercase().replace(' ', '_')
        if (label.isEmpty()) return null
        val vec = normalize(pts) ?: return null
        val list = samples.getOrPut(label) { mutableListOf() }
        list.add(vec)
        while (list.size > MAX_SAMPLES) list.removeAt(0)
        save()
        return list.size
    }

    @Synchronized
    fun forget(rawName: String): Boolean {
        val label = rawName.trim().lowercase().replace(' ', '_')
        val had = samples.remove(label) != null
        save()
        return had
    }

    /** The matching gesture name and how sure (0..1), or null when nothing is close enough. */
    @Synchronized
    fun classify(pts: List<FloatArray>): Pair<String?, Float> {
        val vec = normalize(pts) ?: return null to 0f
        val scored = ArrayList<Pair<Float, String>>()
        for ((label, vecs) in samples) {
            for (v in vecs) scored.add(dist(vec, v) to label)
        }
        if (scored.isEmpty()) return null to 0f
        scored.sortBy { it.first }
        val top = scored.take(K)
        val avg = top.map { it.first }.average().toFloat()
        if (avg > MATCH_LIMIT) return null to 0f
        val votes = top.groupingBy { it.second }.eachCount()
        val most = votes.values.maxOrNull() ?: 1
        val best = top.first { votes[it.second] == most }.second
        val conf = (1f - avg / MATCH_LIMIT).coerceIn(0f, 1f)
        return best to conf
    }

    private fun save() {
        val s = JSONObject()
        for ((label, vecs) in samples) {
            val arr = JSONArray()
            for (v in vecs) {
                val a = JSONArray()
                for (x in v) a.put(x.toDouble())
                arr.put(a)
            }
            s.put(label, arr)
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("samples", s).toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val s = JSONObject(file.readText()).optJSONObject("samples") ?: return
            s.keys().forEach { label ->
                val arr = s.getJSONArray(label)
                val list = mutableListOf<FloatArray>()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    list.add(FloatArray(a.length()) { a.getDouble(it).toFloat() })
                }
                samples[label] = list
            }
        } catch (e: Exception) {
            samples.clear()   // unreadable file: start again rather than crash
        }
    }
}
