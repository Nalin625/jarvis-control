package com.jarvis.control.intel

/*
 * OSINT phases 1-4: pure parsing and validation rules. No Android imports, so these run as plain JVM unit tests.
 * The network layers (OsintDomain, OsintEmail, OsintGithub, OsintThreat) feed raw strings in here and show the results.
 */
object OsintLogic {

    // ---------------------------------------------------------------- domains
    fun normDomain(s: String): String = s.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore('?').substringBefore(':').removeSuffix(".")

    private val domainRe = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    fun validDomain(d: String): Boolean = domainRe.matches(d)

    /** DNS TXT answers come back as quoted pieces, e.g. "\"v=spf1 \" \"include:x ~all\"". Join them into one string. */
    fun joinTxt(raw: String): String = raw.replace("\" \"", "").trim().trim('"')

    // ---------------------------------------------------------------- MX
    data class Mx(val pref: Int, val host: String)

    /** DoH MX answers look like "10 mx1.example.com.". A null MX ("0 .") means the domain accepts no mail. */
    fun parseMx(data: List<String>): Pair<List<Mx>, Boolean> {
        var nullMx = false
        val out = ArrayList<Mx>()
        for (s in data) {
            val p = s.trim().split(Regex("\\s+"))
            if (p.size < 2) continue
            val pref = p[0].toIntOrNull() ?: continue
            val host = p[1].removeSuffix(".").lowercase()
            if (host.isEmpty()) nullMx = true else out.add(Mx(pref, host))
        }
        return out.sortedBy { it.pref } to nullMx
    }

    // ---------------------------------------------------------------- SPF
    data class Spf(val record: String, val all: String?, val lookupTerms: Int, val warnings: List<String>)

    private val allRe = Regex("^[-~?+]?all$")

    /** Reads one SPF record from TXT answers. Lookup count is the number of terms that cost a DNS lookup (not followed into includes). */
    fun parseSpf(txt: List<String>): Spf? {
        val records = txt.map { joinTxt(it) }.filter { it.lowercase().startsWith("v=spf1") }
        if (records.isEmpty()) return null
        val rec = records.first()
        val terms = rec.split(Regex("\\s+")).drop(1).map { it.lowercase() }
        val all = terms.firstOrNull { allRe.matches(it) }
        val lookups = terms.count { t ->
            t.startsWith("include:") || t.startsWith("exists:") || t.startsWith("redirect=") ||
                t == "a" || t.startsWith("a:") || t == "mx" || t.startsWith("mx:") || t == "ptr" || t.startsWith("ptr:")
        }
        val w = ArrayList<String>()
        if (records.size > 1) w.add("more than one SPF record: receivers treat SPF as invalid")
        when (all) {
            null -> w.add("no default (all) rule: unlisted senders are not clearly rejected")
            "+all", "all", "?all" -> w.add("weak: the record lets any server send as this domain")
        }
        if (lookups > 10) w.add("$lookups DNS lookups in this record (limit is 10): receivers may give up on it")
        return Spf(rec, all, lookups, w)
    }

    // ---------------------------------------------------------------- DMARC
    data class Dmarc(val record: String, val policy: String?, val pct: Int, val hasReports: Boolean, val warnings: List<String>)

    fun parseDmarc(txt: List<String>): Dmarc? {
        val rec = txt.map { joinTxt(it) }.firstOrNull { it.lowercase().startsWith("v=dmarc1") } ?: return null
        val tags = rec.split(';').map { it.trim() }.filter { it.contains('=') }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
        val policy = tags["p"]?.lowercase()
        val pct = tags["pct"]?.toIntOrNull()?.coerceIn(0, 100) ?: 100
        val rua = !tags["rua"].isNullOrBlank()
        val w = ArrayList<String>()
        when (policy) {
            null -> w.add("no p= policy: DMARC is not enforced")
            "none" -> w.add("policy is none: spoofed mail is monitored, not rejected or quarantined")
            "quarantine", "reject" -> Unit
            else -> w.add("unknown policy '$policy'")
        }
        if (pct < 100) w.add("policy applies to only $pct% of mail")
        if (!rua) w.add("no aggregate report address (rua=): the owner gets no DMARC reports")
        return Dmarc(rec, policy, pct, rua, w)
    }

    // ---------------------------------------------------------------- robots.txt and sitemaps
    data class Robots(val sitemaps: List<String>, val blocksAllBots: Boolean, val rules: Int)

    /** Standard robots.txt rules: groups of User-agent lines followed by Allow/Disallow. Only the '*' group is summarised. */
    fun parseRobots(text: String): Robots {
        val sitemaps = ArrayList<String>()
        var agents = mutableListOf<String>()
        var prevWasAgent = false
        var blocksAll = false
        var rules = 0
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (!line.contains(':')) continue
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            when (key) {
                "user-agent" -> {
                    if (!prevWasAgent) agents = mutableListOf()
                    agents.add(value.lowercase())
                    prevWasAgent = true
                    continue
                }
                "sitemap" -> if (value.startsWith("http")) sitemaps.add(value)
                "disallow" -> if ("*" in agents) {
                    if (value == "/") blocksAll = true
                    if (value.isNotEmpty()) rules++
                }
                "allow" -> if ("*" in agents && value.isNotEmpty()) rules++
            }
            prevWasAgent = false
        }
        return Robots(sitemaps.distinct(), blocksAll, rules)
    }

    private val locRe = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>", RegexOption.IGNORE_CASE)
    fun sitemapLocs(xml: String): List<String> = locRe.findAll(xml).map { it.groupValues[1].replace("&amp;", "&") }.toList()
    fun isSitemapIndex(xml: String): Boolean = Regex("<sitemapindex", RegexOption.IGNORE_CASE).containsMatchIn(xml)

    // ---------------------------------------------------------------- certificate transparency names
    /**
     * Names from crt.sh's name_value field that belong to [domain] itself or to a label under it.
     * Unlike the older check, "evilexample.com" does NOT count as a subdomain of "example.com".
     */
    fun ctNames(domain: String, nameValues: List<String>): List<String> {
        val d = normDomain(domain)
        return nameValues.flatMap { it.split('\n') }
            .map { it.trim().lowercase().removePrefix("*.") }
            .filter { it.isNotEmpty() && !it.contains(' ') && (it == d || it.endsWith(".$d")) }
            .distinct().sorted()
    }

    // ---------------------------------------------------------------- IPv4 reputation lists (Spamhaus DROP / EDROP)
    data class Range(val start: Long, val end: Long, val raw: String)

    fun ip4ToLong(ip: String): Long? {
        val p = ip.trim().split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) {
            val n = x.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private val cidrRe = Regex("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})/(\\d{1,2})$")

    /** Parses lines such as "1.2.3.0/24 ; SBL12345". Comments and malformed lines are skipped. */
    fun parseCidrList(lines: List<String>): List<Range> {
        val out = ArrayList<Range>()
        for (l in lines) {
            val s = l.substringBefore(';').trim()
            val m = cidrRe.find(s) ?: continue
            val base = ip4ToLong(m.groupValues[1]) ?: continue
            val p = m.groupValues[2].toIntOrNull() ?: continue
            if (p !in 0..32) continue
            val mask = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
            val start = base and mask
            out.add(Range(start, start or (mask.inv() and 0xFFFFFFFFL), s))
        }
        return out
    }

    /** The listed range that contains [ip], or null when the address is not listed (or is not IPv4). */
    fun listedRange(ip: String, ranges: List<Range>): String? {
        val v = ip4ToLong(ip) ?: return null
        return ranges.firstOrNull { v in it.start..it.end }?.raw
    }

    // ---------------------------------------------------------------- URL feeds (OpenPhish style: one URL per line)
    /** Hosts from a feed of URLs. Only the host is kept, not paths or query strings. */
    fun feedHosts(lines: List<String>): Set<String> {
        val out = HashSet<String>()
        for (l in lines) {
            val t = l.trim()
            if (!t.startsWith("http")) continue
            val host = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase()
            if (host.isNotEmpty()) out.add(host)
        }
        return out
    }

    // ---------------------------------------------------------------- email syntax
    private val localRe = Regex("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+$")

    /** Syntax only. A valid address can still be a mailbox that does not exist; this function cannot tell. */
    fun emailSyntaxOk(addr: String): Boolean {
        val t = addr.trim()
        if (t.length > 254) return false
        val at = t.lastIndexOf('@')
        if (at <= 0 || at == t.length - 1) return false
        val local = t.substring(0, at)
        val dom = t.substring(at + 1)
        if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        if (!localRe.matches(local)) return false
        return validDomain(dom.lowercase())
    }

    fun emailDomain(addr: String): String = addr.trim().substringAfterLast('@', "").lowercase()

    /** Shows only the first letter of the mailbox name, so reports do not repeat the full address. */
    fun maskEmail(addr: String): String {
        val a = addr.trim().lowercase()
        return a.substringBefore('@').take(1) + "***@" + a.substringAfter('@', "?")
    }

    // ---------------------------------------------------------------- CVE and severity
    private val cveRe = Regex("^CVE-\\d{4}-\\d{4,}$", RegexOption.IGNORE_CASE)
    fun validCve(id: String): Boolean = cveRe.matches(id.trim())

    /** CVSS v3 qualitative bands from the FIRST specification. */
    fun cvssSeverity(score: Double): String = when {
        score <= 0.0 -> "none"
        score < 4.0 -> "low"
        score < 7.0 -> "medium"
        score < 9.0 -> "high"
        else -> "critical"
    }

    // ---------------------------------------------------------------- GitHub
    fun githubRepoName(s: String): Boolean = Regex("^[A-Za-z0-9_.-]{1,100}$").matches(s.trim())
    fun githubLoginOk(s: String): Boolean = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$").matches(s.trim())
}
