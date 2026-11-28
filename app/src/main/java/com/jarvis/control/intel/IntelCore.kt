package com.jarvis.control.intel

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Jarvis Intelligence module - core pieces. Everything in package com.jarvis.control.intel is self-contained:
 * it uses only Android framework classes (plus ML Kit OCR that the app already ships) and no existing app code.
 *
 * How results are labelled (the report keeps these four apart on purpose):
 *   VERIFIED     read straight from an authoritative public record (DNS, RDAP, the site's own certificate/headers, a file's own bytes)
 *   OBSERVATION  measured or seen by this phone just now (latency, a response, a device answering)
 *   ESTIMATE     inferred or approximate (IP geolocation, technology guesses, risk scores)
 *   UNAVAILABLE  could not be obtained, with the reason
 */
enum class Ev(val tag: String) { VERIFIED("VERIFIED"), OBSERVATION("OBSERVED"), ESTIMATE("ESTIMATE"), UNAVAILABLE("UNAVAILABLE") }

data class Finding(val label: String, val value: String, val ev: Ev, val source: String = "")

class Report(val title: String, val kind: String, val target: String, var ts: Long = System.currentTimeMillis()) {
    val findings = mutableListOf<Finding>()
    var summary: String = ""

    fun ver(label: String, value: String, src: String = "") = add(label, value, Ev.VERIFIED, src)
    fun obs(label: String, value: String, src: String = "") = add(label, value, Ev.OBSERVATION, src)
    fun est(label: String, value: String, src: String = "") = add(label, value, Ev.ESTIMATE, src)
    fun na(label: String, why: String, src: String = "") = add(label, why, Ev.UNAVAILABLE, src)
    fun add(label: String, value: String, ev: Ev, src: String = ""): Report { findings.add(Finding(label, value, ev, src)); return this }

    fun whenText(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    fun toText(): String = buildString {
        append(title).append('\n').append("Target: ").append(target).append('\n').append("Time: ").append(whenText()).append('\n')
        if (summary.isNotBlank()) append(summary).append('\n')
        append('\n')
        for (f in findings) {
            append('[').append(f.ev.tag).append("] ").append(f.label).append(": ").append(f.value).append('\n')
            if (f.source.isNotBlank()) append("    source: ").append(f.source).append('\n')
        }
    }

    fun toMarkdown(): String = buildString {
        append("# ").append(title).append("\n\n- **Target:** ").append(target).append("\n- **Generated:** ").append(whenText()).append("\n- **Kind:** ").append(kind).append("\n")
        if (summary.isNotBlank()) append("\n> ").append(summary.replace("\n", "\n> ")).append("\n")
        for (ev in Ev.values()) {
            val list = findings.filter { it.ev == ev }
            if (list.isEmpty()) continue
            append("\n## ").append(ev.tag.lowercase().replaceFirstChar { it.uppercase() }).append("\n\n")
            for (f in list) {
                append("- **").append(f.label).append(":** ").append(f.value.replace("\n", " "))
                if (f.source.isNotBlank()) append(" ([source](").append(f.source).append("))")
                append("\n")
            }
        }
        append("\n---\nVerified = read from a public record. Observed = measured by the phone. Estimate = inferred, may be wrong. Unavailable = could not be obtained.\n")
    }

    fun toJson(): String {
        val o = JSONObject().put("title", title).put("kind", kind).put("target", target).put("ts", ts).put("summary", summary)
        val a = JSONArray()
        for (f in findings) a.put(JSONObject().put("l", f.label).put("v", f.value).put("e", f.ev.name).put("s", f.source))
        return o.put("f", a).toString()
    }

    companion object {
        fun fromJson(s: String): Report? = try {
            val o = JSONObject(s)
            val r = Report(o.getString("title"), o.optString("kind"), o.optString("target"), o.optLong("ts"))
            r.summary = o.optString("summary")
            val a = o.getJSONArray("f")
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                r.add(f.getString("l"), f.getString("v"), runCatching { Ev.valueOf(f.getString("e")) }.getOrDefault(Ev.UNAVAILABLE), f.optString("s"))
            }
            r
        } catch (e: Exception) { null }
    }
}

/** One place for background work, so screens never block the UI thread. */
object Bg {
    private val pool = Executors.newFixedThreadPool(4)
    fun run(block: () -> Unit) { pool.execute { try { block() } catch (_: Throwable) { } } }
    val executor get() = pool
}

class NetResult(val code: Int, val body: String, val headers: Map<String, List<String>>, val ms: Long, val error: String?, val finalUrl: String) {
    val ok get() = error == null && code in 200..299
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.joinToString(", ")
    fun json(): JSONObject? = try { JSONObject(body) } catch (_: Exception) { null }
    fun jsonArray(): JSONArray? = try { JSONArray(body) } catch (_: Exception) { null }
    /** A short reason in plain words when this call did not work. */
    fun why(): String = error ?: when (code) {
        429 -> "rate limited by the service, try again in a few minutes"
        403 -> "refused (403)" + (if (header("x-ratelimit-remaining") == "0") " - rate limit reached, try later" else "")
        404 -> "not found (404)"
        in 500..599 -> "the service had a problem ($code)"
        else -> "HTTP $code"
    }
}

/** Plain HTTP helper: short timeouts, size cap, never throws, redirects followed by hand (so they can be counted and shown). */
object Net {
    private const val UA = "Mozilla/5.0 (Linux; Android) JarvisIntel/1.0"

    /** Answers for the same public address are reused for [cacheSec] seconds (only successful, unauthenticated GETs). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, NetResult>>()

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000, maxBytes: Int = 600_000, follow: Boolean = true, method: String = "GET", cacheSec: Int = 0): NetResult {
        val cacheable = cacheSec > 0 && headers.isEmpty() && method == "GET"
        if (cacheable) {
            val hit = cache[url]
            if (hit != null && System.currentTimeMillis() - hit.first < cacheSec * 1000L) return hit.second
        }
        val r = fetch(url, headers, timeoutMs, maxBytes, follow, method)
        if (cacheable && r.ok) {
            if (cache.size > 300) cache.clear()
            cache[url] = System.currentTimeMillis() to r
        }
        return r
    }

    private fun fetch(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Int, follow: Boolean, method: String): NetResult {
        var cur = url
        val t0 = System.currentTimeMillis()
        var hops = 0
        while (true) {
            try {
                val c = URL(cur).openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false; c.requestMethod = method
                c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "*/*")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val code = c.responseCode
                val hdr = c.headerFields.filterKeys { it != null }
                if (follow && code in 301..308 && code != 304 && hops < 6) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (!loc.isNullOrBlank()) { cur = URL(URL(cur), loc).toString(); hops++; continue }
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val buf = ByteArrayOutputStream()
                if (stream != null && method != "HEAD") {
                    val tmp = ByteArray(8192); var total = 0
                    while (total < maxBytes) { val n = stream.read(tmp); if (n < 0) break; buf.write(tmp, 0, n); total += n }
                    stream.close()
                }
                c.disconnect()
                return NetResult(code, buf.toString("UTF-8"), hdr, System.currentTimeMillis() - t0, null, cur)
            } catch (e: SocketTimeoutException) {
                return NetResult(0, "", emptyMap(), System.currentTimeMillis() - t0, "timed out", cur)
            } catch (e: UnknownHostException) {
                return NetResult(0, "", emptyMap(), System.currentTimeMillis() - t0, "no internet or the name does not exist", cur)
            } catch (e: Exception) {
                return NetResult(0, "", emptyMap(), System.currentTimeMillis() - t0, (e.javaClass.simpleName + ": " + (e.message ?: "")).take(120), cur)
            }
        }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

/** Local history, snapshots (time series), events and settings. Stays on this phone. */
class IntelDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "jarvis_intel.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, target TEXT, title TEXT, json TEXT)")
        db.execSQL("CREATE TABLE snaps(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, ts INTEGER, v TEXT)")
        db.execSQL("CREATE INDEX snaps_k ON snaps(k, ts)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, level TEXT, text TEXT)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    @Synchronized fun save(r: Report): Long {
        val cv = cv("ts" to r.ts, "kind" to r.kind, "target" to r.target, "title" to r.title, "json" to r.toJson())
        val id = writableDatabase.insert("history", null, cv)
        writableDatabase.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 300)")   // keep storage small
        return id
    }
    class Row(val id: Long, val ts: Long, val kind: String, val target: String, val title: String)
    @Synchronized fun history(limit: Int = 100, kind: String? = null): List<Row> {
        val out = ArrayList<Row>()
        val c = readableDatabase.rawQuery("SELECT id,ts,kind,target,title FROM history " + (if (kind != null) "WHERE kind=? " else "") + "ORDER BY id DESC LIMIT $limit", if (kind != null) arrayOf(kind) else null)
        while (c.moveToNext()) out.add(Row(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)))
        c.close(); return out
    }
    @Synchronized fun report(id: Long): Report? {
        val c = readableDatabase.rawQuery("SELECT json FROM history WHERE id=?", arrayOf(id.toString()))
        val r = if (c.moveToFirst()) Report.fromJson(c.getString(0)) else null
        c.close(); return r
    }
    @Synchronized fun delete(id: Long) { writableDatabase.delete("history", "id=?", arrayOf(id.toString())) }
    @Synchronized fun clearAll() { for (t in listOf("history", "snaps", "events")) writableDatabase.delete(t, null, null) }

    @Synchronized fun snap(k: String, v: String) {
        writableDatabase.insert("snaps", null, cv("k" to k, "ts" to System.currentTimeMillis(), "v" to v))
        writableDatabase.execSQL("DELETE FROM snaps WHERE k=? AND id NOT IN (SELECT id FROM snaps WHERE k=? ORDER BY id DESC LIMIT 200)", arrayOf(k, k))
    }
    @Synchronized fun snaps(k: String, limit: Int = 30): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val c = readableDatabase.rawQuery("SELECT ts,v FROM snaps WHERE k=? ORDER BY id DESC LIMIT $limit", arrayOf(k))
        while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        c.close(); return out
    }
    fun lastSnap(k: String): String? = snaps(k, 1).firstOrNull()?.second

    @Synchronized fun event(level: String, text: String) {
        writableDatabase.insert("events", null, cv("ts" to System.currentTimeMillis(), "level" to level, "text" to text.take(300)))
        writableDatabase.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 500)")
    }
    @Synchronized fun events(limit: Int = 60, sinceMs: Long = 0): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        val c = readableDatabase.rawQuery("SELECT ts,level,text FROM events WHERE ts>=? ORDER BY id DESC LIMIT $limit", arrayOf(sinceMs.toString()))
        while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
        c.close(); return out
    }

    @Synchronized fun get(k: String, def: String = ""): String {
        val c = readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k))
        val v = if (c.moveToFirst()) c.getString(0) else def
        c.close(); return v
    }
    @Synchronized fun put(k: String, v: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", arrayOf(k, v)) }

    companion object {
        @Volatile private var inst: IntelDb? = null
        fun get(ctx: Context): IntelDb = inst ?: synchronized(this) { inst ?: IntelDb(ctx).also { inst = it } }
    }
}

private fun cv(vararg p: Pair<String, Any>): ContentValues {
    val c = ContentValues()
    for ((k, v) in p) when (v) { is Long -> c.put(k, v); is Int -> c.put(k, v); else -> c.put(k, v.toString()) }
    return c
}

/** Secrets (API tokens you type in) are encrypted with a key that lives in the Android Keystore and never leaves it. */
object Vault {
    private const val ALIAS = "jarvis_intel_vault"
    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("jarvis_intel_vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    fun put(ctx: Context, name: String, secret: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit().putString(name, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply(); true
    } catch (e: Exception) { false }

    fun get(ctx: Context, name: String): String? {
        val stored = prefs(ctx).getString(name, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    fun has(ctx: Context, name: String) = prefs(ctx).contains(name)
    fun remove(ctx: Context, name: String) { prefs(ctx).edit().remove(name).apply() }
    fun mask(s: String): String = if (s.length <= 8) "••••" else s.take(3) + "••••••" + s.takeLast(2)
}

object Fmt {
    fun ago(ts: Long): String {
        val m = (System.currentTimeMillis() - ts) / 60000
        return when { m < 1 -> "just now"; m < 90 -> "$m min ago"; m < 2880 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
    }
    fun date(ts: Long): String = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(ts))
    fun size(b: Long): String = when { b < 1024 -> "$b B"; b < 1048576 -> "%.1f KB".format(b / 1024.0); else -> "%.1f MB".format(b / 1048576.0) }
    fun spark(values: List<Double>): String {
        if (values.isEmpty()) return ""
        val lo = values.min(); val hi = values.max(); val ch = "▁▂▃▄▅▆▇█"
        return values.joinToString("") { if (hi == lo) "▄" else ch[((it - lo) / (hi - lo) * 7).toInt().coerceIn(0, 7)].toString() }
    }
    fun domainOf(input: String): String {
        var s = input.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        return try { java.net.IDN.toASCII(s.trim('.')) } catch (_: Exception) { s.trim('.') }
    }
}
