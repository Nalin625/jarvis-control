package com.jarvis.control.intel

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/*
 * Shared network helpers for the OSINT phases. Everything here fails soft: it returns a reason instead of throwing.
 * API keys are sent only in request headers and are never written to a Report, a log or an exception message.
 */

/** Result of one DNS-over-HTTPS question. [ok] is false when the lookup itself failed (not when the name has no records). */
class DnsAnswer(val ok: Boolean, val status: Int, val data: List<String>, val authenticated: Boolean, val why: String, val source: String)

object OsintDns {
    private const val SRC = "Google DNS-over-HTTPS (dns.google)"
    private val typeNum = mapOf("A" to 1, "NS" to 2, "CNAME" to 5, "SOA" to 6, "MX" to 15, "TXT" to 16, "AAAA" to 28, "CAA" to 257)

    /** Answers are reused for 30 minutes so a repeated check does not hit the DNS service again. */
    fun query(name: String, type: String): DnsAnswer {
        val url = "https://dns.google/resolve?name=${Net.enc(name)}&type=$type"
        val r = Net.get(url, timeoutMs = 7000, cacheSec = 1800)
        val j = r.json() ?: return DnsAnswer(false, -1, emptyList(), false, r.why(), url)
        val status = j.optInt("Status", -1)
        val want = typeNum[type] ?: -1
        val out = ArrayList<String>()
        val ans = j.optJSONArray("Answer")
        if (ans != null) for (i in 0 until ans.length()) {
            val a = ans.getJSONObject(i)
            if (a.optInt("type") == want) out.add(a.optString("data"))
        }
        return DnsAnswer(status == 0 || status == 3, status, out, j.optBoolean("AD"), if (status == 3) "name does not exist" else "", url)
    }

    val source get() = SRC
}

object OsintHttp {
    /** POST a JSON body and read the reply. Used only for public query APIs that take a body (OSV). */
    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 12000): NetResult {
        val t0 = System.currentTimeMillis()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "JarvisIntel/1.0")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val buf = ByteArrayOutputStream()
            if (stream != null) {
                val tmp = ByteArray(8192); var total = 0
                while (total < 1_000_000) { val n = stream.read(tmp); if (n < 0) break; buf.write(tmp, 0, n); total += n }
                stream.close()
            }
            val hdr = c.headerFields.filterKeys { it != null }
            c.disconnect()
            NetResult(code, buf.toString("UTF-8"), hdr, System.currentTimeMillis() - t0, null, url)
        } catch (e: java.net.SocketTimeoutException) {
            NetResult(0, "", emptyMap(), System.currentTimeMillis() - t0, "timed out", url)
        } catch (e: java.net.UnknownHostException) {
            NetResult(0, "", emptyMap(), System.currentTimeMillis() - t0, "no internet connection", url)
        } catch (e: Exception) {
            NetResult(0, "", emptyMap(), System.currentTimeMillis() - t0, e.javaClass.simpleName, url)
        }
    }

    /** Fetch a public text feed (a list of lines). Cached for six hours; the reply size is capped. */
    fun feedLines(url: String, maxBytes: Int = 4_000_000): Pair<List<String>?, NetResult> {
        val r = Net.get(url, timeoutMs = 15000, maxBytes = maxBytes, cacheSec = 6 * 3600)
        if (!r.ok) return null to r
        // A feed cut off at the size limit would be an incomplete list: a missing entry must never read as "not listed".
        if (r.body.length >= maxBytes) {
            return null to NetResult(r.code, "", r.headers, r.ms, "the list is larger than the size limit, so it could not be read completely", r.finalUrl)
        }
        return r.body.lines() to r
    }
}
