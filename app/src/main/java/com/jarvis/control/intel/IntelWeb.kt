package com.jarvis.control.intel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** IP intelligence. Free, key-less public sources only. */
object IpTools {
    private const val TOR_LIST = "https://check.torproject.org/torbulkexitlist"

    fun myIp(): String? {
        val r = Net.get("https://api.ipify.org?format=json", timeoutMs = 6000)
        return r.json()?.optString("ip")?.takeIf { it.isNotBlank() }
    }

    fun isPrivate(ip: String): Boolean = try {
        val a = InetAddress.getByName(ip)
        a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || ip.startsWith("100.64.") || ip.startsWith("fc") || ip.startsWith("fd")
    } catch (_: Exception) { false }

    fun validIp(s: String): Boolean = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(s) && s.split('.').all { it.toInt() in 0..255 } || (s.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(s))

    fun report(ctx: Context, input: String): Report {
        var ip = input.trim()
        val mine = ip.isBlank()
        val rep = Report(if (mine) "IP intelligence - my public IP" else "IP intelligence", "ip", if (mine) "my public IP" else ip)
        if (mine) {
            ip = myIp() ?: run { rep.na("Public IP", "could not be read (no internet?)", "https://api.ipify.org"); return rep }
            rep.obs("Public IP", ip, "https://api.ipify.org")
            val db = IntelDb.get(ctx)
            val last = db.lastSnap("ip:mine")
            if (last != null && last != ip) rep.obs("IP changed", "was $last before", "")
            if (last != ip) db.snap("ip:mine", ip)
        } else if (!validIp(ip)) {
            rep.na("Input", "'$ip' is not a valid IP address"); return rep
        }
        if (isPrivate(ip)) {
            rep.ver("Type", "private / local address - it has no public owner or location")
            return rep
        }
        val src = "https://ipwho.is/$ip"
        val g = Net.get(src, timeoutMs = 8000)
        val j = g.json()
        if (j == null || !j.optBoolean("success", false)) {
            rep.na("Geolocation / ISP / ASN", j?.optString("message")?.takeIf { it.isNotBlank() } ?: g.why(), src)
        } else {
            val city = listOf(j.optString("city"), j.optString("region"), j.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            rep.est("Approximate location", "$city (city-level at best; ${"%.2f".format(j.optDouble("latitude"))}, ${"%.2f".format(j.optDouble("longitude"))})", src)
            val con = j.optJSONObject("connection")
            if (con != null) {
                rep.obs("ISP", con.optString("isp").ifBlank { "not reported" }, src)
                rep.obs("Organisation", con.optString("org").ifBlank { "not reported" }, src)
                rep.obs("ASN", if (con.optInt("asn") > 0) "AS${con.optInt("asn")}" else "not reported", src)
            }
            j.optJSONObject("timezone")?.optString("id")?.takeIf { it.isNotBlank() }?.let { rep.est("Time zone", it, src) }
            rep.obs("Address type", j.optString("type", "?"), src)
        }
        // official registration record (RDAP)
        val rd = Net.get("https://rdap.org/ip/$ip", timeoutMs = 9000)
        rd.json()?.let { o ->
            rep.ver("Registered network", listOf(o.optString("name"), o.optString("handle")).filter { it.isNotBlank() }.joinToString(" / ") + (o.optString("country").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), "https://rdap.org/ip/$ip")
            val start = o.optString("startAddress"); val end = o.optString("endAddress")
            if (start.isNotBlank()) rep.ver("Address range", "$start - $end", "https://rdap.org/ip/$ip")
        } ?: rep.na("Registration (RDAP)", rd.why(), "https://rdap.org/ip/$ip")
        // reverse DNS
        val ptr = reverseDns(ip)
        if (ptr != null) rep.ver("Reverse DNS", ptr, "https://dns.google/resolve?name=${arpa(ip)}&type=PTR") else rep.na("Reverse DNS", "no PTR record published")
        // reputation (free, key-less)
        val sfs = "https://api.stopforumspam.org/api?json&ip=${Net.enc(ip)}"
        val sf = Net.get(sfs, timeoutMs = 7000).json()?.optJSONObject("ip")
        if (sf != null) {
            val seen = sf.optInt("appears", 0) == 1
            rep.obs("Spam-report database", if (seen) "listed (${sf.optInt("frequency")} reports, confidence ${sf.optDouble("confidence")}%)" else "not listed", "https://www.stopforumspam.com/search?q=$ip")
        } else rep.na("Spam-report database", "service did not answer", "https://www.stopforumspam.com")
        val tor = torExit(ctx, ip)
        if (tor != null) rep.obs("Tor exit node", if (tor) "yes - this is a Tor exit address" else "no", TOR_LIST) else rep.na("Tor exit node", "list could not be loaded", TOR_LIST)
        rep.summary = "Location is an estimate from a public database. Reputation comes from free public lists only; 'not listed' is not a guarantee of safety."
        return rep
    }

    fun arpa(ip: String): String = if (ip.contains(':')) "" else ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"

    fun reverseDns(ip: String): String? {
        val a = arpa(ip)
        if (a.isNotEmpty()) {
            val r = Net.get("https://dns.google/resolve?name=$a&type=PTR", timeoutMs = 6000).json()
            val ans = r?.optJSONArray("Answer")
            if (ans != null && ans.length() > 0) return ans.getJSONObject(0).optString("data").trimEnd('.')
        }
        return null
    }

    private fun torExit(ctx: Context, ip: String): Boolean? {
        val db = IntelDb.get(ctx)
        var list = db.get("tor_list"); val at = db.get("tor_list_ts", "0").toLongOrNull() ?: 0
        if (list.isBlank() || System.currentTimeMillis() - at > TimeUnit.HOURS.toMillis(12)) {
            val r = Net.get(TOR_LIST, timeoutMs = 9000, maxBytes = 200_000)
            if (r.ok) { list = r.body; db.put("tor_list", list); db.put("tor_list_ts", System.currentTimeMillis().toString()) } else if (list.isBlank()) return null
        }
        return list.lineSequence().any { it.trim() == ip }
    }

    fun history(ctx: Context): Report {
        val rep = Report("Public IP history", "ip", "my public IP")
        val s = IntelDb.get(ctx).snaps("ip:mine", 30)
        if (s.isEmpty()) rep.na("History", "no IP recorded yet - run 'My public IP' first") else for ((t, v) in s) rep.obs(Fmt.date(t), v)
        return rep
    }
}

/** DNS, RDAP, certificates, headers, availability, technologies, subdomains and change tracking for a website. */
object WebTools {
    private fun doh(name: String, type: String): JSONObject? = Net.get("https://dns.google/resolve?name=${Net.enc(name)}&type=$type", timeoutMs = 7000).json()

    fun dnsReport(domain: String): Report {
        val d = Fmt.domainOf(domain)
        val rep = Report("DNS records - $d", "dns", d)
        if (d.isBlank() || !d.contains('.')) { rep.na("Input", "enter a domain such as example.com"); return rep }
        for (t in listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "CAA", "SOA")) {
            val src = "https://dns.google/resolve?name=$d&type=$t"
            val j = doh(d, t)
            if (j == null) { rep.na(t, "lookup failed", src); continue }
            val ans = j.optJSONArray("Answer")
            val vals = ArrayList<String>()
            if (ans != null) for (i in 0 until ans.length()) { val a = ans.getJSONObject(i); if (a.optInt("type") == typeNum(t)) vals.add(a.optString("data")) }
            if (vals.isEmpty()) { if (t in listOf("A", "NS")) rep.na(t, "none found", src) } else rep.ver(t, vals.joinToString("\n") { it.take(200) }, src)
            if (t == "A") rep.ver("DNSSEC validated", if (j.optBoolean("AD")) "yes" else "no (zone not signed or resolver could not validate)", src)
        }
        val txt = doh(d, "TXT")?.optJSONArray("Answer")
        var spf = false
        if (txt != null) for (i in 0 until txt.length()) if (txt.getJSONObject(i).optString("data").contains("v=spf1")) spf = true
        rep.obs("SPF (anti-spoofing) record", if (spf) "present" else "missing - others could forge mail from this domain", "https://dns.google/resolve?name=$d&type=TXT")
        val dm = doh("_dmarc.$d", "TXT")?.optJSONArray("Answer")
        rep.obs("DMARC policy", if (dm != null && dm.length() > 0) dm.getJSONObject(0).optString("data").take(160) else "missing", "https://dns.google/resolve?name=_dmarc.$d&type=TXT")
        return rep
    }

    private fun typeNum(t: String) = mapOf("A" to 1, "NS" to 2, "CNAME" to 5, "SOA" to 6, "MX" to 15, "TXT" to 16, "AAAA" to 28, "CAA" to 257)[t] ?: -1

    /** WHOIS replacement: RDAP is the official, structured successor. Contact details of people are never shown. */
    fun rdapReport(domain: String): Report {
        val d = Fmt.domainOf(domain)
        val rep = Report("Registration (RDAP) - $d", "rdap", d)
        val src = "https://rdap.org/domain/$d"
        val r = Net.get(src, timeoutMs = 10000)
        val j = r.json()
        if (j == null) { rep.na("RDAP record", r.why(), src); return rep }
        rep.ver("Domain", j.optString("ldhName", d), src)
        val st = j.optJSONArray("status"); if (st != null) rep.ver("Status", (0 until st.length()).joinToString(", ") { st.getString(it) }, src)
        val ev = j.optJSONArray("events")
        if (ev != null) for (i in 0 until ev.length()) { val e = ev.getJSONObject(i); rep.ver(e.optString("eventAction").replaceFirstChar { it.uppercase() }, e.optString("eventDate"), src) }
        val ns = j.optJSONArray("nameservers"); if (ns != null && ns.length() > 0) rep.ver("Name servers", (0 until ns.length()).joinToString(", ") { ns.getJSONObject(it).optString("ldhName") }, src)
        val en = j.optJSONArray("entities")
        if (en != null) for (i in 0 until en.length()) {
            val e = en.getJSONObject(i); val roles = e.optJSONArray("roles")?.toString() ?: ""
            if (roles.contains("registrar")) {
                val vc = e.optJSONArray("vcardArray")?.optJSONArray(1)
                var fn = ""
                if (vc != null) for (k in 0 until vc.length()) { val p = vc.optJSONArray(k); if (p != null && p.optString(0) == "fn") fn = p.optString(3) }
                if (fn.isNotBlank()) rep.ver("Registrar", fn, src)
            }
        }
        rep.summary = "Registrant personal details are not displayed (most registries redact them, and this tool does not seek them)."
        domainAgeDays(j)?.let { rep.est("Domain age", "$it days", src) }
        return rep
    }

    fun domainAgeDays(rdap: JSONObject): Long? {
        val ev = rdap.optJSONArray("events") ?: return null
        for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            if (e.optString("eventAction") == "registration") return try {
                val t = java.time.OffsetDateTime.parse(e.optString("eventDate")).toInstant().toEpochMilli()
                (System.currentTimeMillis() - t) / 86_400_000
            } catch (_: Exception) { null }
        }
        return null
    }

    // ---- certificates
    fun certReport(domain: String): Report {
        val host = Fmt.domainOf(domain)
        val rep = Report("SSL certificate - $host", "cert", host)
        var valid = true; var why = ""
        var chain: Array<java.security.cert.Certificate>? = null; var proto = ""; var cipher = ""
        try {
            val s = (SSLContext.getDefault().socketFactory.createSocket(host, 443) as SSLSocket)
            s.soTimeout = 8000; s.startHandshake(); chain = s.session.peerCertificates; proto = s.session.protocol; cipher = s.session.cipherSuite; s.close()
        } catch (e: Exception) {
            valid = false; why = (e.message ?: e.javaClass.simpleName).take(140)
            try {   // inspection only: read the certificate the server presents, send nothing
                val tm = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                    override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val ctx = SSLContext.getInstance("TLS"); ctx.init(null, tm, null)
                val s = ctx.socketFactory.createSocket(host, 443) as SSLSocket
                s.soTimeout = 8000; s.startHandshake(); chain = s.session.peerCertificates; proto = s.session.protocol; cipher = s.session.cipherSuite; s.close()
            } catch (_: Exception) { }
        }
        val src = "https://$host"
        val leaf = chain?.firstOrNull() as? X509Certificate
        if (leaf == null) { rep.na("Certificate", if (why.isNotBlank()) "could not connect on port 443: $why" else "no certificate", src); return rep }
        rep.ver("Trusted by this phone", if (valid) "yes" else "NO - $why", src)
        rep.ver("Subject", leaf.subjectX500Principal.name.take(160), src)
        rep.ver("Issuer", leaf.issuerX500Principal.name.take(160), src)
        rep.ver("Valid from", leaf.notBefore.toString(), src)
        val days = TimeUnit.MILLISECONDS.toDays(leaf.notAfter.time - System.currentTimeMillis())
        rep.ver("Expires", leaf.notAfter.toString() + if (days < 0) "  (EXPIRED ${-days} days ago)" else "  ($days days left)", src)
        if (days in 0..14) rep.est("Expiry warning", "expires in under 15 days", src)
        val sans = leaf.subjectAlternativeNames?.mapNotNull { (it as? List<*>)?.getOrNull(1)?.toString() }.orEmpty()
        if (sans.isNotEmpty()) rep.ver("Names covered (${sans.size})", sans.take(15).joinToString(", ") + if (sans.size > 15) " ..." else "", src)
        rep.ver("Signature", leaf.sigAlgName, src)
        (leaf.publicKey as? RSAPublicKey)?.let { rep.ver("Key", "RSA ${it.modulus.bitLength()} bits" + if (it.modulus.bitLength() < 2048) "  (weak)" else "", src) } ?: rep.ver("Key", leaf.publicKey.algorithm, src)
        rep.obs("Chain length", "${chain!!.size} certificate(s)", src)
        rep.obs("Protocol / cipher", "$proto / $cipher", src)
        rep.ver("Fingerprint (SHA-256)", MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString(":") { "%02X".format(it) }, src)
        rep.est("Rating", when { !valid -> "poor: the certificate is not trusted"; days < 0 -> "poor: expired"; proto == "TLSv1.3" && days > 14 -> "good"; else -> "fair" }, "")
        return rep
    }

    // ---- availability / headers / technology
    private fun url(input: String): String { val t = input.trim(); return if (t.startsWith("http")) t else "https://" + Fmt.domainOf(t) + "/" }

    fun availability(input: String): Report {
        val u = url(input); val host = Fmt.domainOf(input)
        val rep = Report("Availability - $host", "avail", host)
        val r = Net.get(u, follow = true, maxBytes = 50_000)
        if (r.error != null) rep.obs("Reachable", "NO - ${r.error}", u) else rep.obs("Reachable", if (r.code < 500) "yes (HTTP ${r.code})" else "server error (HTTP ${r.code})", u)
        rep.obs("Response time", "${r.ms} ms (including redirects)", u)
        if (r.finalUrl != u) rep.obs("Redirects to", r.finalUrl, u)
        val plain = Net.get("http://$host/", follow = false, maxBytes = 1, method = "HEAD", timeoutMs = 6000)
        if (plain.error == null) rep.obs("HTTP to HTTPS redirect", if (plain.code in 301..308 && (plain.header("Location") ?: "").startsWith("https")) "yes" else "no - plain http answers with ${plain.code}", "http://$host/")
        return rep
    }

    fun headersReport(input: String): Report {
        val u = url(input); val host = Fmt.domainOf(input)
        val rep = Report("HTTP security headers - $host", "headers", host)
        val r = Net.get(u, maxBytes = 20_000)
        if (r.error != null) { rep.na("Request", r.why(), u); return rep }
        var good = 0; var total = 0
        fun check(name: String, ok: (String) -> Boolean, advice: String) {
            total++
            val v = r.header(name)
            if (v == null) rep.obs(name, "MISSING - $advice", u) else { if (ok(v)) { good++; rep.obs(name, v.take(120), u) } else rep.obs(name, "weak: ${v.take(100)} - $advice", u) }
        }
        check("Strict-Transport-Security", { (Regex("max-age=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() ?: 0) >= 15_552_000 }, "forces HTTPS; use max-age of at least 6 months")
        check("Content-Security-Policy", { !it.contains("unsafe-inline") || it.contains("nonce") }, "limits where scripts can come from")
        check("X-Content-Type-Options", { it.contains("nosniff", true) }, "set to nosniff")
        check("Referrer-Policy", { true }, "controls what links reveal")
        check("Permissions-Policy", { true }, "limits camera, location and similar features")
        total++
        val xfo = r.header("X-Frame-Options"); val csp = r.header("Content-Security-Policy") ?: ""
        if (xfo != null || csp.contains("frame-ancestors")) { good++; rep.obs("Clickjacking protection", xfo ?: "frame-ancestors in CSP", u) } else rep.obs("Clickjacking protection", "MISSING - add X-Frame-Options or CSP frame-ancestors", u)
        r.header("Server")?.let { rep.obs("Server header", "$it  (reveals software; consider hiding the version)", u) }
        r.header("X-Powered-By")?.let { rep.obs("X-Powered-By", "$it  (reveals technology)", u) }
        val cookies = r.headers.entries.firstOrNull { it.key.equals("Set-Cookie", true) }?.value.orEmpty()
        if (cookies.isNotEmpty()) {
            val bad = cookies.count { !it.contains("Secure", true) || !it.contains("HttpOnly", true) }
            rep.obs("Cookies (${cookies.size})", if (bad == 0) "all marked Secure and HttpOnly" else "$bad without Secure/HttpOnly", u)
        }
        rep.est("Header score", "$good of $total checks passed", "")
        rep.summary = "Headers are exactly what the site sent to this phone. The score is a simple estimate, not a full security audit."
        return rep
    }

    fun techReport(input: String): Report {
        val u = url(input); val host = Fmt.domainOf(input)
        val rep = Report("Technologies - $host", "tech", host)
        val r = Net.get(u, maxBytes = 400_000)
        if (r.error != null) { rep.na("Request", r.why(), u); return rep }
        val found = LinkedHashMap<String, String>()
        fun hit(n: String, why: String) { if (!found.containsKey(n)) found[n] = why }
        r.header("Server")?.let { hit("Web server: " + it.substringBefore(' '), "Server header") }
        r.header("X-Powered-By")?.let { hit("Platform: $it", "X-Powered-By header") }
        if (r.header("CF-Ray") != null || (r.header("Server") ?: "").contains("cloudflare", true)) hit("Cloudflare (CDN/proxy)", "CF-Ray / Server header")
        if (r.header("X-Vercel-Id") != null) hit("Vercel", "X-Vercel-Id header")
        if (r.header("X-GitHub-Request-Id") != null) hit("GitHub Pages", "X-GitHub-Request-Id header")
        if ((r.header("Via") ?: "").contains("cloudfront", true) || r.header("X-Amz-Cf-Id") != null) hit("Amazon CloudFront", "Via/X-Amz-Cf-Id header")
        val ck = r.headers.entries.firstOrNull { it.key.equals("Set-Cookie", true) }?.value.orEmpty().joinToString(";")
        if (ck.contains("PHPSESSID")) hit("PHP", "PHPSESSID cookie"); if (ck.contains("JSESSIONID")) hit("Java server", "JSESSIONID cookie")
        if (ck.contains("laravel_session")) hit("Laravel", "laravel_session cookie"); if (ck.contains("ASP.NET")) hit("ASP.NET", "session cookie")
        val b = r.body
        Regex("<meta[^>]+name=[\"']generator[\"'][^>]+content=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(b)?.let { hit("Generator: " + it.groupValues[1], "meta generator tag") }
        val sigs = listOf("wp-content" to "WordPress", "/_next/" to "Next.js", "__NEXT_DATA__" to "Next.js", "cdn.shopify.com" to "Shopify", "wixstatic.com" to "Wix", "squarespace" to "Squarespace",
            "/_nuxt/" to "Nuxt (Vue)", "data-reactroot" to "React", "ng-version" to "Angular", "vue" to "Vue (possible)", "jquery" to "jQuery", "bootstrap" to "Bootstrap", "tailwind" to "Tailwind CSS",
            "gtag(" to "Google Analytics / gtag", "googletagmanager.com" to "Google Tag Manager", "fbq(" to "Meta Pixel", "gatsby" to "Gatsby", "webflow" to "Webflow", "cloudflareinsights" to "Cloudflare Analytics")
        for ((k, n) in sigs) if (b.contains(k, true)) hit(n, "page source contains '$k'")
        if (found.isEmpty()) rep.na("Technologies", "nothing recognisable (the site may render with scripts or hide its stack)", u)
        for ((n, why) in found) rep.est(n, "matched: $why", u)
        rep.summary = "Technology identification is a signature guess from public headers and page source. It can be wrong or incomplete."
        return rep
    }

    // ---- subdomains from public certificate-transparency logs
    fun subdomains(input: String): Report {
        val d = Fmt.domainOf(input)
        val rep = Report("Subdomains (certificate logs) - $d", "subdomains", d)
        val src = "https://crt.sh/?q=%25.$d&output=json"
        val r = Net.get(src, timeoutMs = 25000, maxBytes = 3_000_000)
        val arr = r.jsonArray()
        if (arr == null) { rep.na("Certificate-transparency search", r.why() + " (crt.sh is free but often slow; try again)", src); return rep }
        val names = sortedSetOf<String>()
        for (i in 0 until arr.length()) arr.getJSONObject(i).optString("name_value").split('\n').forEach { n -> val x = n.trim().lowercase().removePrefix("*."); if (x.endsWith(d) && !x.contains(' ')) names.add(x) }
        rep.ver("Public names found", "${names.size}", src)
        for (n in names.take(60)) rep.ver("Subdomain", n, "https://crt.sh/?q=$n")
        if (names.size > 60) rep.obs("More", "${names.size - 60} more not shown", src)
        rep.summary = "Only names that appear in public certificates are listed. Nothing was probed or brute-forced."
        return rep
    }

    // ---- change history
    fun changeCheck(ctx: Context, input: String): Report {
        val u = url(input); val host = Fmt.domainOf(input)
        val rep = Report("Website change check - $host", "change", host)
        val r = Net.get(u, maxBytes = 600_000)
        if (r.error != null) { rep.na("Request", r.why(), u); return rep }
        val md = MessageDigest.getInstance("SHA-256")
        val text = r.body.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), "").replace(Regex("\\s+"), " ")
        val bodyHash = md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(r.body)?.groupValues?.get(1)?.trim()?.take(100) ?: ""
        val now = JSONObject().put("hash", bodyHash).put("title", title).put("len", text.length).put("code", r.code).put("server", r.header("Server") ?: "")
        val db = IntelDb.get(ctx); val key = "site:$host"
        val prev = db.snaps(key, 1).firstOrNull()
        rep.obs("Status", "HTTP ${r.code}, ${r.ms} ms", u); rep.obs("Title", title.ifBlank { "(none)" }, u)
        if (prev == null) rep.obs("Baseline", "first snapshot saved; run again later to see changes", u)
        else {
            val p = try { JSONObject(prev.second) } catch (_: Exception) { JSONObject() }
            rep.obs("Compared with", Fmt.ago(prev.first), u)
            val changes = ArrayList<String>()
            if (p.optString("hash") != bodyHash) changes.add("page content changed (${p.optInt("len")} -> ${text.length} characters)")
            if (p.optString("title") != title) changes.add("title: '${p.optString("title")}' -> '$title'")
            if (p.optInt("code") != r.code) changes.add("status ${p.optInt("code")} -> ${r.code}")
            if (p.optString("server") != (r.header("Server") ?: "")) changes.add("server header changed")
            if (changes.isEmpty()) rep.obs("Changes", "none since the last snapshot", u) else changes.forEach { rep.obs("Change", it, u) }
            rep.summary = if (changes.isEmpty()) "No change detected." else "CHANGED: " + changes.size + " difference(s) found."
        }
        db.snap(key, now.toString())
        return rep
    }

    fun watchList(ctx: Context): List<String> = IntelDb.get(ctx).get("watch_sites").split('\n').filter { it.isNotBlank() }
    fun watchAdd(ctx: Context, input: String): Boolean { val h = Fmt.domainOf(input); val l = watchList(ctx).toMutableList(); if (h.isBlank() || l.contains(h) || l.size >= 10) return false; l.add(h); IntelDb.get(ctx).put("watch_sites", l.joinToString("\n")); return true }
    fun watchRemove(ctx: Context, input: String) { IntelDb.get(ctx).put("watch_sites", watchList(ctx).filter { it != Fmt.domainOf(input) }.joinToString("\n")) }

    /** Everything about a site in one report. */
    fun full(ctx: Context, input: String): Report {
        val host = Fmt.domainOf(input)
        val rep = Report("Website intelligence - $host", "website", host)
        for (part in listOf(availability(input), certReport(host), headersReport(input), dnsReport(host), rdapReport(host), techReport(input))) {
            for (f in part.findings) rep.add("${part.title.substringBefore(" - ")}: ${f.label}", f.value, f.ev, f.source)
        }
        rep.summary = "Combined scan of public information. Run 'Subdomains' and 'Change check' separately."
        return rep
    }
}

/** OSINT search: official, free, public APIs only. No account lookups across sites, no scraping of search engines. */
object OsintTools {
    fun githubRepos(q: String): Report {
        val rep = Report("GitHub repository search", "github", q)
        val src = "https://api.github.com/search/repositories?q=${Net.enc(q)}&per_page=8"
        val r = Net.get(src, mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 9000)
        val items = r.json()?.optJSONArray("items")
        if (items == null) { rep.na("Search", r.why(), src); return rep }
        rep.ver("Matches", "${r.json()?.optInt("total_count")} repositories (top 8 shown)", src)
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            rep.ver(o.optString("full_name"), "★ ${o.optInt("stargazers_count")}  ${o.optString("description", "").take(100)}", o.optString("html_url"))
        }
        return rep
    }

    fun mentions(q: String): Report {
        val rep = Report("Public web mentions", "mentions", q)
        val hn = "https://hn.algolia.com/api/v1/search?query=${Net.enc(q)}&tags=story&hitsPerPage=8"
        val h = Net.get(hn, timeoutMs = 8000).json()?.optJSONArray("hits")
        if (h == null) rep.na("Hacker News", "service did not answer", hn) else if (h.length() == 0) rep.obs("Hacker News", "no stories found", hn)
        else for (i in 0 until h.length()) { val o = h.getJSONObject(i); rep.ver("HN: " + o.optString("title").take(90), "${o.optInt("points")} points, ${o.optString("created_at").take(10)}", "https://news.ycombinator.com/item?id=" + o.optString("objectID")) }
        val wk = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${Net.enc(q)}&format=json&srlimit=5&origin=*"
        val w = Net.get(wk, timeoutMs = 8000).json()?.optJSONObject("query")?.optJSONArray("search")
        if (w == null) rep.na("Wikipedia", "service did not answer", wk) else if (w.length() == 0) rep.obs("Wikipedia", "no articles found", wk)
        else for (i in 0 until w.length()) { val o = w.getJSONObject(i); rep.ver("Wikipedia: " + o.optString("title"), o.optString("snippet").replace(Regex("<[^>]+>"), "").take(120), "https://en.wikipedia.org/wiki/" + o.optString("title").replace(' ', '_')) }
        rep.summary = "Sources: Hacker News and Wikipedia public APIs. General web search engines are not scraped, so this is not a complete picture of the web."
        return rep
    }

    /** Check a GitHub handle only (an official API). By design this is NOT a multi-site username hunt. */
    fun githubHandle(handle: String): Report {
        val h = handle.trim().removePrefix("@")
        val rep = Report("GitHub handle check", "handle", h)
        val src = "https://api.github.com/users/${Net.enc(h)}"
        val r = Net.get(src, mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 8000)
        val o = r.json()
        if (r.code == 404) { rep.ver("Handle", "'$h' is not used on GitHub", src); return rep }
        if (o == null || o.optString("login").isBlank()) { rep.na("Lookup", r.why(), src); return rep }
        rep.ver("Handle", o.optString("login") + " (" + o.optString("type") + ")", o.optString("html_url"))
        rep.ver("Public repositories", o.optInt("public_repos").toString(), src)
        rep.ver("Account created", o.optString("created_at").take(10), src)
        rep.summary = "Only basic public GitHub facts are shown. Searching a person's handle across many sites is intentionally not part of this tool."
        return rep
    }

    fun domainOsint(ctx: Context, input: String): Report {
        val d = Fmt.domainOf(input)
        val rep = Report("Domain OSINT - $d", "domainosint", d)
        val parts = listOf(WebTools.rdapReport(d), WebTools.dnsReport(d), WebTools.certReport(d), WebTools.subdomains(d))
        for (p in parts) for (f in p.findings) rep.add("${p.title.substringBefore(" - ")}: ${f.label}", f.value, f.ev, f.source)
        rep.summary = "Public records only: RDAP, DNS, certificate and certificate-transparency data."
        return rep
    }
}
