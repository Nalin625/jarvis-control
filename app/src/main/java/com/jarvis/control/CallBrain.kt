package com.jarvis.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The call assistant's brain, fully on the phone: Ollama (Termux) at 127.0.0.1:11434. No laptop needed. */
object CallBrain {
    private var model = ""
    private val bye = Regex("\\b(bye|goodbye|good bye|that'?s all|that is all|nothing else|alvida)\\b", RegexOption.IGNORE_CASE)

    private fun http(path: String, body: JSONObject?, timeoutMs: Int): JSONObject? = try {
        val c = URL("http://127.0.0.1:11434$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = timeoutMs
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val text = c.inputStream.bufferedReader().readText()
        c.disconnect()
        JSONObject(text)
    } catch (e: Exception) { null }

    private fun pickModel(ctx: Context): String {
        if (model.isNotEmpty()) return model
        val want = ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE).getString("call_model", "") ?: ""
        val arr = http("/api/tags", null, 3000)?.optJSONArray("models") ?: return ""
        var first = ""
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i).optString("name")
            if (first.isEmpty()) first = n
            if (want.isNotEmpty() && n.startsWith(want)) { model = n; return n }
        }
        model = first
        return first
    }

    /** Call text is processed only by the Ollama endpoint on this phone. */
    private fun chat(ctx: Context, system: String, msgs: List<Pair<String, String>>, maxTokens: Int, timeoutMs: Int): String? =
        ollamaChat(ctx, system, msgs, maxTokens, timeoutMs)

    private fun ollamaChat(ctx: Context, system: String, msgs: List<Pair<String, String>>, maxTokens: Int, timeoutMs: Int): String? {
        val m = pickModel(ctx)
        if (m.isEmpty()) return null
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for ((r, t) in msgs) arr.put(JSONObject().put("role", if (r == "caller") "user" else "assistant").put("content", t))
        val body = JSONObject().put("model", m).put("stream", false).put("messages", arr)
            .put("options", JSONObject().put("num_predict", maxTokens).put("temperature", 0.5))
        val out = http("/api/chat", body, timeoutMs)?.optJSONObject("message")?.optString("content")?.trim()
        return if (out.isNullOrEmpty()) null else out
    }

    private fun clean(t: String): String =
        t.replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("[*_#`>]+"), "").replace(Regex("\\s+"), " ").trim()

    /** history ends with the caller's newest sentence. Returns (what to say, hang up after?) or null if the AI is unreachable. */
    fun reply(ctx: Context, history: List<Pair<String, String>>): Pair<String, Boolean>? {
        val owner = ctx.getSharedPreferences("jarvis_control", Context.MODE_PRIVATE).getString("owner_name", "")?.ifBlank { null } ?: "the owner"
        val system = "You are Jarvis, the voice assistant answering a phone call for $owner, who cannot pick up right now. " +
            "You are speaking out loud on a live call: reply with ONE or TWO short, plain sentences. No lists, no markdown, no emojis. " +
            "Find out who is calling and why, and take a clear message. Ask for a callback number only if it is missing. " +
            "Never reveal anything private about $owner (location, schedule, accounts, numbers, passwords). " +
            "Never agree to payments, OTPs, codes or anything risky; just say you will pass the message on. " +
            "Reply in the same language the caller uses (English, Hindi or Hinglish). " +
            "When the caller is finished or says goodbye, thank them, say the message will be passed on, and end your reply with [END]."
        val raw = chat(ctx, system, history.takeLast(14), 90, 45000) ?: return null
        val said = history.lastOrNull { it.first == "caller" }?.second ?: ""
        val end = raw.contains("[END]") || bye.containsMatchIn(said)
        val text = clean(raw.replace("[END]", " ")).take(320).ifBlank { "Sorry, could you say that again?" }
        return Pair(text, end)
    }

    /** Writes the summary of a finished call, stores it on the phone, returns it. */
    fun finish(ctx: Context, who: String, number: String, seconds: Long, history: List<Pair<String, String>>): String {
        val caller = history.filter { it.first == "caller" }.map { it.second }
        val summary0 = if (caller.isEmpty()) "Jarvis answered but the caller did not say anything."
        else {
            val talk = history.joinToString("\n") { (if (it.first == "caller") "Caller: " else "Jarvis: ") + it.second }
            chat(ctx, "Summarize this phone call for the owner. Reply in plain text with exactly these labelled lines, each one short, writing 'None' when empty, and never invent anything:\nReason: why they called\nSummary: what was said\nImportant: names, numbers, times, facts mentioned\nDecisions: anything agreed\nAction items: what the owner should do\nFollow-up: whether and when to call back",
                listOf(Pair("caller", "Phone call from $who.\n\n" + talk.takeLast(4000))), 320, 90000)?.let { clean(it) }
                ?: ("Caller said: " + caller.joinToString(" / ").take(300))
        }
        val dur = "%d:%02d".format(seconds / 60, seconds % 60)
        val summary = "CALL HANDLED BY JARVIS\nCaller: $who\nDuration: $dur\n\n" + summary0
        try {
            val f = File(ctx.filesDir, "calls.json")
            val arr = if (f.exists()) JSONArray(f.readText()) else JSONArray()
            val turns = JSONArray()
            history.forEach { turns.put(JSONArray().put(it.first).put(it.second)) }
            arr.put(JSONObject().put("ts", System.currentTimeMillis()).put("who", who).put("number", number)
                .put("seconds", seconds).put("summary", summary).put("turns", turns))
            val keep = JSONArray()
            for (i in maxOf(0, arr.length() - 100) until arr.length()) keep.put(arr.get(i))
            f.writeText(keep.toString())
        } catch (e: Exception) { }
        return summary
    }

    /** The last [n] call summaries as text, newest first. */
    fun recent(ctx: Context, n: Int): String {
        return try {
            val f = File(ctx.filesDir, "calls.json")
            if (!f.exists()) return "No calls handled yet."
            val arr = JSONArray(f.readText())
            if (arr.length() == 0) return "No calls handled yet."
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
            val sb = StringBuilder()
            for (i in arr.length() - 1 downTo maxOf(0, arr.length() - n)) {
                val o = arr.getJSONObject(i)
                sb.append(fmt.format(Date(o.optLong("ts")))).append(" - ").append(o.optString("who"))
                    .append(" (").append(o.optLong("seconds")).append("s)\n").append(o.optString("summary")).append("\n\n")
            }
            sb.toString().trim()
        } catch (e: Exception) { "Could not read the call log." }
    }
}
