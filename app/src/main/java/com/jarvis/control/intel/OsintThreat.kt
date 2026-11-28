package com.jarvis.control.intel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/*
 * OSINT phase 4: threat intelligence from public sources, with optional keyed providers.
 *
 * Free, no key:  NVD (CVE records), CISA KEV (known exploited), OSV (open-source package vulnerabilities),
 *                OpenPhish community feed (phishing hosts), Spamhaus DROP/EDROP (hijacked or abusive IPv4 ranges).
 * Optional keys: VirusTotal (NEEDS API KEY), Shodan (NEEDS API KEY), NVD key (raises NVD's rate limit).
 *                Keys are kept in the Keystore vault and are never written to a report or a log.
 *
 * A listing in one feed is an observation, not proof of malice. Reports say so.
 */
enum class VState { LISTED, CLEAN, UNKNOWN, NEEDS_KEY }

data class Verdict(val source: String, val state: VState, val detail: String)

/** Plain-words summary across sources. Pure, so it is unit-tested. */
fun summarizeVerdicts(v: List<Verdict>): String {
    val keyed = v.count { it.state == VState.NEEDS_KEY }
    val checked = v.count { it.state != VState.NEEDS_KEY && it.state != VState.UNKNOWN }
    val listed = v.count { it.state == VState.LISTED }
    val clean = v.count { it.state == VState.CLEAN }
    val unknown = v.count { it.state == VState.UNKNOWN }
    val head = if (checked == 0) "No source could give a result." else "$listed of $checked checked sources list it; $clean do not."
    val tail = (if (unknown > 0) " $unknown could not be checked." else "") + (if (keyed > 0) " $keyed need an API key you have not added." else "")
    return head + tail + " Each source covers different things, so one list is not a verdict."
}

object OsintThreat {
    private const val KEV = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"
    private const val OPENPHISH = "https://openphish.com/feed.txt"
    private const val DROP = "https://www.spamhaus.org/drop/drop.txt"
    private const val EDROP = "https://www.spamhaus.org/drop/edrop.txt"
    private const val NVD = "https://services.nvd.nist.gov/rest/json/cves/2.0"

    /** Key names in the vault. Set through the dashboard; never shown back in full. */
    const val KEY_VT = "vt_key"
    const val KEY_SHODAN = "shodan_key"
    const val KEY_NVD = "nvd_key"

    private fun nvdHeaders(ctx: Context?): Map<String, String> {
        val k = ctx?.let { Vault.get(it, KEY_NVD) }
        return if (k.isNullOrBlank()) emptyMap() else mapOf("apiKey" to k)
    }

    /** Removes a secret from any text before it reaches a report. */
    private fun scrub(s: String, secret: String?): String = if (secret.isNullOrBlank()) s else s.replace(secret, "***")

    // ------------------------------------------------------------ CVE
    fun cve(input: String, ctx: Context?): Report {
        val q = input.trim().uppercase()
        if (!OsintLogic.validCve(q)) return cveKeyword(input.trim(), ctx)
        val rep = Report("Vulnerability - $q", "cve", q)
        val url = "$NVD?cveId=$q"
        val r = Net.get(url, nvdHeaders(ctx), timeoutMs = 15000, cacheSec = 3600)
        val arr = r.json()?.optJSONArray("vulnerabilities")
        when {
            arr == null -> rep.na("NVD record", r.why() + " (NVD allows only a few requests every 30 seconds without a key)", url)
            arr.length() == 0 -> rep.obs("NVD record", "no record for $q", url)
            else -> fillCve(rep, arr.getJSONObject(0).getJSONObject("cve"), url)
        }
        val kev = kevIds()
        when {
            kev.first == null -> rep.na("Known exploited (CISA KEV)", "catalogue not available: " + kev.second, KEV)
            q in kev.first!! -> rep.ver("Known exploited (CISA KEV)", "yes - CISA lists it as actively exploited", KEV)
            else -> rep.obs("Known exploited (CISA KEV)", "not in the catalogue (that does not mean it is not exploited)", KEV)
        }
        rep.summary = "NVD and CISA data are public. Scores come from NVD; severity bands follow the CVSS specification."
        return rep
    }

    private fun fillCve(rep: Report, c: JSONObject, url: String) {
        rep.ver("Published", c.optString("published").take(16), url)
        rep.ver("Last modified", c.optString("lastModified").take(16), url)
        val desc = c.optJSONArray("descriptions")
        var en = ""
        if (desc != null) for (i in 0 until desc.length()) { val d = desc.getJSONObject(i); if (d.optString("lang") == "en") en = d.optString("value") }
        rep.ver("Description", en.ifBlank { "none given" }.take(400), url)
        val m = c.optJSONObject("metrics")
        val v31 = m?.optJSONArray("cvssMetricV31")?.optJSONObject(0)?.optJSONObject("cvssData")
        val v30 = m?.optJSONArray("cvssMetricV30")?.optJSONObject(0)?.optJSONObject("cvssData")
        val v2 = m?.optJSONArray("cvssMetricV2")?.optJSONObject(0)?.optJSONObject("cvssData")
        val cvss = v31 ?: v30
        if (cvss != null) {
            val score = cvss.optDouble("baseScore", -1.0)
            rep.ver("CVSS " + (if (v31 != null) "3.1" else "3.0") + " base score", "%.1f (${OsintLogic.cvssSeverity(score)})".format(score), url)
        } else if (v2 != null) {
            rep.est("CVSS 2 base score", "%.1f (older scoring; ${OsintLogic.cvssSeverity(v2.optDouble("baseScore", -1.0))})".format(v2.optDouble("baseScore", -1.0)), url)
        } else rep.na("CVSS score", "not scored yet", url)
        val refs = c.optJSONArray("references")
        if (refs != null) for (i in 0 until minOf(refs.length(), 2)) rep.obs("Reference", refs.getJSONObject(i).optString("url"), url)
    }

    private fun cveKeyword(q: String, ctx: Context?): Report {
        val rep = Report("Vulnerability keyword search - $q", "cve-search", q)
        if (q.length < 3) return rep.also { it.na("Search", "enter at least 3 characters, or a CVE id such as CVE-2021-44228") }
        val url = "$NVD?keywordSearch=${Net.enc(q)}&resultsPerPage=5"
        val r = Net.get(url, nvdHeaders(ctx), timeoutMs = 15000, cacheSec = 3600)
        val arr = r.json()?.optJSONArray("vulnerabilities")
        if (arr == null) { rep.na("NVD", r.why(), url); return rep }
        rep.obs("Matches", "${r.json()?.optInt("totalResults")} in NVD (top ${minOf(arr.length(), 5)} shown)", url)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i).getJSONObject("cve")
            val id = c.optString("id")
            rep.ver(id, c.optJSONArray("descriptions")?.optJSONObject(0)?.optString("value")?.take(160) ?: "no description", "https://nvd.nist.gov/vuln/detail/$id")
        }
        rep.summary = "Keyword matches are not checked against any product version. Look at each record before deciding it applies to you."
        return rep
    }

    /** The CISA catalogue of CVEs known to be exploited. Returns the ids, or null plus the reason it could not be read. */
    private fun kevIds(): Pair<Set<String>?, String> {
        val r = Net.get(KEV, timeoutMs = 20000, maxBytes = 8_000_000, cacheSec = 6 * 3600)
        val arr: JSONArray = r.json()?.optJSONArray("vulnerabilities") ?: return Pair(null, "could not be read (" + r.why() + ")")
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).optString("cveID").uppercase())
        return Pair(ids, "")
    }

    // ------------------------------------------------------------ OSV (open-source packages)
    /** Input: "npm:express@4.17.1", "PyPI:requests", or an advisory id such as GHSA-xxxx-xxxx-xxxx or CVE-... */
    fun osv(input: String): Report {
        val t = input.trim()
        val rep = Report("Open-source vulnerabilities - $t", "osv", t)
        if (t.isEmpty()) return rep.also { it.na("Input", "enter ecosystem:package, e.g. npm:express@4.17.1") }
        val idOnly = Regex("^(GHSA-[a-z0-9-]+|CVE-\\d{4}-\\d+|PYSEC-\\d+|OSV-\\d+)$", RegexOption.IGNORE_CASE).matches(t)
        if (idOnly) {
            val src = "https://api.osv.dev/v1/vulns/" + t.uppercase()
            val r = Net.get(src, timeoutMs = 12000, cacheSec = 3600)
            val o = r.json()
            if (o == null) { rep.na("Advisory", r.why(), src); return rep }
            rep.ver(o.optString("id"), o.optString("summary").ifBlank { o.optString("details").take(200) }, "https://osv.dev/vulnerability/${o.optString("id")}")
            rep.obs("Modified", o.optString("modified").take(10), src)
            return rep
        }
        if (!t.contains(':')) return rep.also { it.na("Input", "use ecosystem:package, e.g. npm:express@4.17.1") }
        val eco = t.substringBefore(':').trim()
        var rest = t.substringAfter(':').trim()
        var version = ""
        val at = rest.lastIndexOf('@')
        if (at > 0) { version = rest.substring(at + 1); rest = rest.substring(0, at) }
        val body = JSONObject().put("package", JSONObject().put("name", rest).put("ecosystem", eco))
        if (version.isNotBlank()) body.put("version", version)
        val src = "https://api.osv.dev/v1/query"
        val r = OsintHttp.postJson(src, body)
        val o = r.json()
        if (o == null) { rep.na("OSV query", r.why(), src); return rep }
        val vulns: JSONArray? = o.optJSONArray("vulns")
        if (vulns == null || vulns.length() == 0) {
            rep.obs("Known vulnerabilities", "none recorded in OSV for $rest" + (if (version.isNotBlank()) " $version" else "") + ". Only covers what is in OSV's database.", src)
            return rep
        }
        rep.ver("Known vulnerabilities", "${vulns.length()} recorded in OSV (top ${minOf(vulns.length(), 8)} shown)", src)
        for (i in 0 until minOf(vulns.length(), 8)) {
            val v = vulns.getJSONObject(i)
            rep.ver(v.optString("id"), v.optString("summary").ifBlank { v.optString("details").take(160) }.take(200), "https://osv.dev/vulnerability/${v.optString("id")}")
        }
        rep.summary = "Ecosystems: npm, PyPI, Maven, Go, crates.io, RubyGems, Packagist, NuGet and others OSV supports."
        return rep
    }

    // ------------------------------------------------------------ reputation of a domain or IP
    fun reputation(input: String, ctx: Context?): Report {
        val isIp = OsintLogic.ip4ToLong(input.trim()) != null
        val t = if (isIp) input.trim() else OsintLogic.normDomain(input)
        val rep = Report("Reputation - $t", "reputation", t)
        if (!isIp && !OsintLogic.validDomain(t)) return rep.also { it.na("Input", "enter a domain or an IPv4 address") }
        val verdicts = ArrayList<Verdict>()

        if (isIp) {
            val ranges = ArrayList<OsintLogic.Range>()
            var dropErr = ""
            for (u in listOf(DROP, EDROP)) {
                val (lines, r) = OsintHttp.feedLines(u)
                if (lines == null) { dropErr = r.why(); continue }
                ranges.addAll(OsintLogic.parseCidrList(lines))
            }
            if (ranges.isEmpty()) verdicts.add(Verdict("Spamhaus DROP/EDROP", VState.UNKNOWN, "could not be read: $dropErr"))
            else {
                val hit = OsintLogic.listedRange(t, ranges)
                verdicts.add(if (hit != null) Verdict("Spamhaus DROP/EDROP", VState.LISTED, "inside the listed range $hit")
                    else Verdict("Spamhaus DROP/EDROP", VState.CLEAN, "not inside a listed range"))
            }
        } else {
            val (lines, r) = OsintHttp.feedLines(OPENPHISH)
            if (lines == null) verdicts.add(Verdict("OpenPhish feed", VState.UNKNOWN, "could not be read: " + r.why()))
            else {
                val hosts = OsintLogic.feedHosts(lines)
                verdicts.add(if (t in hosts) Verdict("OpenPhish feed", VState.LISTED, "this exact host is in the phishing feed")
                    else Verdict("OpenPhish feed", VState.CLEAN, "host not in the feed (${hosts.size} hosts in the feed)"))
            }
        }

        val vtKey = ctx?.let { Vault.get(it, KEY_VT) }
        if (vtKey.isNullOrBlank()) verdicts.add(Verdict("VirusTotal", VState.NEEDS_KEY, "NEEDS API KEY (free key, add in the OSINT workspace)"))
        else {
            val url = if (isIp) "https://www.virustotal.com/api/v3/ip_addresses/$t" else "https://www.virustotal.com/api/v3/domains/$t"
            val r = Net.get(url, mapOf("x-apikey" to vtKey), timeoutMs = 12000)
            val stats = r.json()?.optJSONObject("data")?.optJSONObject("attributes")?.optJSONObject("last_analysis_stats")
            if (stats == null) verdicts.add(Verdict("VirusTotal", VState.UNKNOWN, scrub(r.why(), vtKey)))
            else {
                val bad = stats.optInt("malicious") + stats.optInt("suspicious")
                verdicts.add(if (bad > 0) Verdict("VirusTotal", VState.LISTED, "$bad security engines flag it (of ${bad + stats.optInt("harmless") + stats.optInt("undetected")} that scanned)")
                    else Verdict("VirusTotal", VState.CLEAN, "no engine flags it"))
                rep.ver("VirusTotal link", "https://www.virustotal.com/gui/" + (if (isIp) "ip-address/" else "domain/") + t, "https://www.virustotal.com")
            }
        }

        val shKey = ctx?.let { Vault.get(it, KEY_SHODAN) }
        if (isIp) {
            if (shKey.isNullOrBlank()) verdicts.add(Verdict("Shodan", VState.NEEDS_KEY, "NEEDS API KEY (free key, add in the OSINT workspace)"))
            else {
                val url = "https://api.shodan.io/shodan/host/$t?key=$shKey"
                val r = Net.get(url, timeoutMs = 12000)
                val o = r.json()
                if (o == null) verdicts.add(Verdict("Shodan", VState.UNKNOWN, scrub(r.why(), shKey)))
                else {
                    val ports = o.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                    rep.obs("Shodan: open ports seen", if (ports.isEmpty()) "none recorded" else ports.joinToString(", "), "https://www.shodan.io/host/$t")
                    verdicts.add(Verdict("Shodan", VState.CLEAN, "host record found (${ports.size} open ports recorded); Shodan does not judge reputation"))
                }
            }
        }

        for (v in verdicts) when (v.state) {
            VState.LISTED -> rep.est("${v.source}: listed", v.detail, "")
            VState.CLEAN -> rep.obs(v.source, v.detail, "")
            VState.UNKNOWN -> rep.na(v.source, v.detail, "")
            VState.NEEDS_KEY -> rep.na(v.source, v.detail, "")
        }
        rep.summary = summarizeVerdicts(verdicts) + " A listing is not a confirmed attack; a clean result is not a guarantee."
        return rep
    }

    fun setKey(ctx: Context, name: String, key: String): Boolean = Vault.put(ctx, name, key.trim())
    fun hasKey(ctx: Context, name: String): Boolean = Vault.has(ctx, name)
}
