package com.jarvis.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The phone-side assistant behind "hold the mic -> MOBILE". Light on purpose:
 *  1. common phone commands are understood right here (no internet, no key, instant);
 *  2. anything else goes to Gemini using an API key fetched from the laptop's Jarvis (kept only in memory);
 *  3. if the laptop is in private mode it hands out no key, and only step 1 works.
 * It can open things and read info. It never calls, texts, deletes or pays by itself.
 */
object MobileAssistant {
    @Volatile private var key = ""
    @Volatile private var model = "gemini-3.5-flash-lite"
    @Volatile private var keyAt = 0L

    /** Understands the common phrases locally. -> (action, argument) or null. */
    fun parseLocal(raw: String): Pair<String, String>? {
        var t = raw.lowercase().trim().trim('.', '!', '?', ',')
        t = t.replace(Regex("^(?:hey |ok |okay )?(?:jarvis[, ]*)?(?:please |can you |could you |kindly )*"), "").trim()
        t = t.replace(Regex("\\s+(?:please|for me|now)$"), "").trim()
        fun has(vararg p: String) = p.any { Regex("\\b$it\\b").containsMatchIn(t) }
        Regex("^(?:play|search|find) (.+?) (?:on|in) youtube$").find(t)?.let { return "youtube_search" to it.groupValues[1] }
        Regex("^youtube (.+)$").find(t)?.let { return "youtube_search" to it.groupValues[1] }
        Regex("^(?:search(?: for)?|google|look up|web search) (.+)$").find(t)?.let { return "web_search" to it.groupValues[1] }
        Regex("^(?:dial|call) ([+\\d][\\d \\-]{4,})$").find(t)?.let { return "dial" to it.groupValues[1].replace(Regex("[ \\-]"), "") }
        Regex("^(?:open|launch|start|go to) (.+)$").find(t)?.let { m ->
            val n = m.groupValues[1].removePrefix("the ").removePrefix("my ").trim()
            val map = mapOf("calendar" to "calendar", "contacts" to "contacts", "gallery" to "gallery", "photos" to "gallery", "maps" to "maps",
                "youtube" to "youtube", "whatsapp" to "whatsapp", "gmail" to "gmail", "mail" to "gmail", "music" to "music", "calculator" to "calculator",
                "files" to "files", "file manager" to "files", "dialer" to "dialer", "keypad" to "dialer", "camera" to "camera", "clock" to "clock",
                "alarm" to "clock", "alarms" to "clock", "timer" to "timer", "wifi" to "wifi", "bluetooth" to "bluetooth", "settings" to "wifi")
            return (map[n]?.let { it to "" }) ?: ("open_app" to n)
        }
        return when {
            has("torch", "flashlight", "flash light") -> "flashlight" to ""
            has("battery") -> "battery" to ""
            has("storage", "free space") -> "storage" to ""
            has("phone info", "device info") -> "info" to ""
            has("wifi name", "which wifi") -> "wifi_name" to ""
            has("wifi") -> "wifi" to ""
            has("bluetooth") -> "bluetooth" to ""
            has("do not disturb", "dnd", "silent mode") -> "dnd" to ""
            has("camera", "take a photo", "selfie") -> "camera" to ""
            has("last call") -> "last_call" to ""
            has("call log", "recent calls", "missed calls") -> "calllog" to ""
            has("vibrate", "buzz") -> "buzz" to ""
            has("volume up", "louder") -> "volume_up" to ""
            has("brightness up", "brighter") -> "brightness_up" to ""
            has("brightness down", "dimmer", "darker") -> "brightness_down" to ""
            has("airplane mode", "flight mode") -> "airplane" to ""
            has("hotspot") -> "hotspot" to ""
            has("timer") -> "timer" to ""
            has("alarm", "clock") -> "clock" to ""
            has("ip address", "my ip") -> "ip" to ""
            has("network type", "mobile data") -> "network" to ""
            has("uptime") -> "uptime" to ""
            else -> null
        }
    }

    /** Runs one phone action. Safe ones only. */
    fun run(ctx: Context, action: String, arg: String): String = try {
        when (action) {
            "flashlight" -> PhoneTools.flashlight(ctx)
            "battery" -> PhoneTools.battery(ctx)
            "storage" -> PhoneTools.storage(ctx)
            "info" -> PhoneTools.info(ctx)
            "wifi" -> PhoneTools.openWifi(ctx)
            "wifi_name" -> PhoneTools2.wifiName(ctx)
            "bluetooth" -> PhoneTools.openBluetooth(ctx)
            "dnd" -> PhoneTools.openDnd(ctx)
            "camera" -> PhoneTools.openCamera(ctx)
            "calllog" -> PhoneTools.openCallLog(ctx)
            "last_call" -> PhoneTools.lastCall(ctx)
            "buzz" -> PhoneTools.buzz(ctx)
            "volume_up" -> PhoneTools.musicVolumeUp(ctx)
            "brightness_up" -> PhoneTools2.brightnessUp(ctx)
            "brightness_down" -> PhoneTools2.brightnessDown(ctx)
            "airplane" -> PhoneTools2.airplaneMode(ctx)
            "hotspot" -> PhoneTools2.hotspot(ctx)
            "clock" -> PhoneTools2.openClock(ctx)
            "timer" -> PhoneTools2.openTimers(ctx)
            "calendar" -> PhoneTools2.openCalendar(ctx)
            "contacts" -> PhoneTools2.openContacts(ctx)
            "gallery" -> PhoneTools2.openGallery(ctx)
            "maps" -> PhoneTools2.openMaps(ctx)
            "youtube" -> PhoneTools2.openYouTube(ctx)
            "whatsapp" -> PhoneTools2.openWhatsApp(ctx)
            "gmail" -> PhoneTools2.openGmail(ctx)
            "music" -> PhoneTools2.openMusic(ctx)
            "calculator" -> PhoneTools2.openCalculator(ctx)
            "files" -> PhoneTools2.openFiles(ctx)
            "dialer" -> PhoneTools2.openDialer(ctx)
            "ip" -> PhoneTools2.phoneIp(ctx)
            "network" -> PhoneTools2.networkType(ctx)
            "uptime" -> PhoneTools2.uptime()
            "dial" -> view(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(arg))), "Dialer ready with $arg - press call yourself.")
            "web_search" -> view(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(arg, "UTF-8"))), "Searching: $arg")
            "youtube_search" -> view(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(arg, "UTF-8"))), "YouTube: $arg")
            "open_app" -> openByName(ctx, arg)
            else -> "I don't know how to do '$action' on the phone."
        }
    } catch (e: Exception) {
        "That failed: " + (e.message ?: "").take(60)
    }

    private fun view(ctx: Context, i: Intent, ok: String): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return ok
    }

    private fun openByName(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(main, 0)
            .map { it to it.loadLabel(pm).toString().lowercase() }
            .filter { it.second.contains(name) }
            .minByOrNull { it.second.length } ?: return "I can't find an app called '$name'."
        val li = pm.getLaunchIntentForPackage(hit.first.activityInfo.packageName) ?: return "I can't open '$name'."
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(li)
        return "Opened ${hit.second}."
    }

    // ---- the AI part: key comes from the laptop's Jarvis ----
    private fun fetchKey(ctx: Context): String? {
        if (key.isNotEmpty() && System.currentTimeMillis() - keyAt < 3_600_000L) return null
        val r = LaptopApi.call(ctx, "GET", "/phone/ai_key", null, 4000)
        if (r.optBoolean("private", false)) return "private"
        val k = r.optString("key", "")
        if (k.isEmpty()) return r.optString("error", "the laptop has no Gemini key")
        key = k; model = r.optString("model", model).ifEmpty { model }; keyAt = System.currentTimeMillis()
        return null
    }

    private const val ACTIONS = "flashlight, battery, storage, info, wifi, wifi_name, bluetooth, dnd, camera, calllog, last_call, buzz, volume_up, " +
        "brightness_up, brightness_down, airplane, hotspot, clock, timer, calendar, contacts, gallery, maps, youtube, whatsapp, gmail, music, " +
        "calculator, files, dialer, ip, network, uptime, dial(arg=number), web_search(arg), youtube_search(arg), open_app(arg=app name)"

    private fun askGemini(text: String): JSONObject? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text",
                "You are the phone assistant inside Jarvis. Turn the user's spoken request into ONE JSON object. Either " +
                "{\"action\":\"<name>\",\"arg\":\"<text or empty>\"} using only these actions: $ACTIONS - or {\"say\":\"short answer\"} for questions or " +
                "things you cannot do. Never invent actions. Never place calls or send messages."))))
            .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 200))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 15000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) { val code = c.responseCode; c.disconnect(); if (code == 429 || code == 400 || code == 403) key = ""; return null }
        val txt = c.inputStream.bufferedReader().readText(); c.disconnect()
        val part = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return JSONObject(part.substring(part.indexOf('{'), part.lastIndexOf('}') + 1))
    }

    /** Blocking: call from a background thread. Returns what to tell the user. */
    fun handle(ctx: Context, text: String): String {
        parseLocal(text)?.let { return run(ctx, it.first, it.second) }
        val err = fetchKey(ctx)
        if (err == "private") return "Private mode is on, so I only do basic phone commands. Try: torch, battery, open youtube."
        if (err != null) return "I understood \"$text\" but can't reach my AI key ($err). Basic commands still work."
        val j = try { askGemini(text) } catch (e: Exception) { null }
            ?: return "My AI didn't answer. Basic commands still work."
        j.optString("say").takeIf { it.isNotEmpty() }?.let { return it }
        val a = j.optString("action")
        return if (a.isEmpty()) "I didn't understand that." else run(ctx, a, j.optString("arg"))
    }
}
