package com.jarvis.control.intel

/*
 * OSINT phase 1: domain intelligence. Public DNS, certificate transparency, robots.txt and sitemaps only.
 * Existing checks (RDAP, full DNS list, certificate, headers, subdomains) stay where they are in WebTools; this file adds
 * the mail-configuration detail, robots/sitemaps, and the evidence graph for a domain.
 */
object OsintDomain {

    /** Common DKIM selector names used by large mail providers. A miss does not mean DKIM is absent. */
    private val commonSelectors = listOf("google", "selector1", "selector2", "default", "k1", "s1")

    /** A DKIM key record carries a base64 public key in its p= tag. Any other TXT record is not counted. */
    private val dkimKey = Regex("(^|[;\\s\"])p=[A-Za-z0-9+/=]{40,}", RegexOption.IGNORE_CASE)

    fun mxRecords(input: String): Report {
        val d = OsintLogic.normDomain(input)
        val rep = Report("MX records - $d", "mx", d)
        if (!OsintLogic.validDomain(d)) return rep.also { it.na("Input", "enter a domain such as example.com") }
        val a = OsintDns.query(d, "MX")
        if (!a.ok) { rep.na("MX", "lookup failed: " + a.why.ifBlank { "DNS service did not answer" }, a.source); return rep }
        if (a.status == 3) { rep.na("MX", "domain does not exist", a.source); return rep }
        val (mx, nullMx) = OsintLogic.parseMx(a.data)
        when {
            nullMx -> rep.ver("Mail", "the domain declares that it accepts no mail (null MX)", a.source)
            mx.isEmpty() -> rep.obs("Mail", "no MX records. Some mail servers fall back to the A record, so this is not proof that mail cannot arrive.", a.source)
            else -> {
                rep.ver("MX servers", "${mx.size}", a.source)
                for (m in mx) rep.ver("MX priority ${m.pref}", m.host, a.source)
            }
        }
        rep.summary = "MX lists the servers that receive mail for the domain. Reading it does not contact any mail server."
        return rep
    }

    fun spfDmarcDkim(input: String): Report {
        val d = OsintLogic.normDomain(input)
        val rep = Report("Mail authentication records - $d", "mailauth", d)
        if (!OsintLogic.validDomain(d)) return rep.also { it.na("Input", "enter a domain such as example.com") }
        val txt = OsintDns.query(d, "TXT")
        if (!txt.ok) { rep.na("TXT records", "lookup failed: " + txt.why.ifBlank { "DNS service did not answer" }, txt.source); return rep }
        val spf = OsintLogic.parseSpf(txt.data)
        if (spf == null) rep.obs("SPF", "no SPF record: nothing says which servers may send mail as $d", txt.source)
        else {
            rep.ver("SPF record", spf.record.take(200), txt.source)
            rep.ver("SPF default rule", spf.all ?: "none", txt.source)
            rep.ver("SPF DNS lookups", "${spf.lookupTerms} of 10 allowed (includes not followed)", txt.source)
            for (w in spf.warnings) rep.est("SPF warning", w, txt.source)
        }
        val dmarcSrc = OsintDns.query("_dmarc.$d", "TXT")
        val dm = if (dmarcSrc.ok) OsintLogic.parseDmarc(dmarcSrc.data) else null
        if (!dmarcSrc.ok) rep.na("DMARC", "lookup failed: " + dmarcSrc.why, dmarcSrc.source)
        else if (dm == null) rep.obs("DMARC", "no DMARC record: receivers are not told what to do with mail that fails SPF/DKIM", dmarcSrc.source)
        else {
            rep.ver("DMARC record", dm.record.take(200), dmarcSrc.source)
            rep.ver("DMARC policy", dm.policy ?: "not set", dmarcSrc.source)
            rep.ver("DMARC coverage", "${dm.pct}% of mail", dmarcSrc.source)
            for (w in dm.warnings) rep.est("DMARC warning", w, dmarcSrc.source)
        }
        val found = ArrayList<String>()
        for (s in commonSelectors) {
            val k = OsintDns.query("$s._domainkey.$d", "TXT")
            if (k.ok && k.data.any { dkimKey.containsMatchIn(it) }) found.add(s)
        }
        if (found.isEmpty()) rep.obs("DKIM", "no key found under the ${commonSelectors.size} common selector names. A provider may use another name, so this is not proof DKIM is missing.", "DNS TXT lookups of <selector>._domainkey.$d")
        else rep.ver("DKIM keys", "found under: " + found.joinToString(", "), "DNS TXT lookups of <selector>._domainkey.$d")
        rep.summary = "DNS only. These records describe how mail is set up for the domain. They do not show whether a given message is genuine."
        return rep
    }

    fun robotsAndSitemaps(input: String): Report {
        val d = OsintLogic.normDomain(input)
        val rep = Report("robots.txt and sitemaps - $d", "robots", d)
        if (!OsintLogic.validDomain(d)) return rep.also { it.na("Input", "enter a domain such as example.com") }
        val base = "https://$d/robots.txt"
        val r = Net.get(base, timeoutMs = 8000, maxBytes = 200_000, cacheSec = 1800)
        var declared: List<String> = emptyList()
        when {
            r.code == 404 -> rep.obs("robots.txt", "not present", base)
            !r.ok -> rep.na("robots.txt", r.why(), base)
            else -> {
                val rb = OsintLogic.parseRobots(r.body)
                declared = rb.sitemaps
                rep.ver("robots.txt", "present, ${rb.rules} allow/disallow rules for all crawlers", base)
                rep.ver("Blocks all crawlers", if (rb.blocksAllBots) "yes (Disallow: /)" else "no", base)
                rep.ver("Sitemaps declared", "${rb.sitemaps.size}", base)
            }
        }
        val candidates = (if (declared.isNotEmpty()) declared else listOf("https://$d/sitemap.xml")).take(3)
        for (sm in candidates) {
            val s = Net.get(sm, timeoutMs = 10000, maxBytes = 3_000_000, cacheSec = 1800)
            if (!s.ok) { rep.na("Sitemap", "could not read $sm: " + s.why(), sm); continue }
            val locs = OsintLogic.sitemapLocs(s.body)
            if (OsintLogic.isSitemapIndex(s.body)) rep.ver("Sitemap index", "${locs.size} child sitemap(s) listed (child files are not opened)", sm)
            else rep.ver("Sitemap", "${locs.size} page addresses listed", sm)
            for (u in locs.take(5)) rep.obs("Listed page", u, sm)
        }
        if (candidates.isEmpty()) rep.na("Sitemap", "none declared and no default sitemap", base)
        rep.summary = "Only robots.txt and up to 3 sitemap files are read, once each. The listed pages themselves are not visited."
        return rep
    }

    class Relationships(val facts: DomainFacts, val graph: EvidenceGraph, val report: Report)

    /**
     * Gathers the public records for one domain (addresses, mail servers, name servers, certificate-transparency names)
     * and builds the evidence graph from them. Every link in the report names the record it came from.
     */
    fun relationships(input: String): Relationships {
        val d = OsintLogic.normDomain(input)
        val now = System.currentTimeMillis()
        val rep = Report("Domain relationship evidence - $d", "relations", d, now)
        if (!OsintLogic.validDomain(d)) { rep.na("Input", "enter a domain such as example.com"); return Relationships(DomainFacts(d, now, emptyList(), emptyList(), emptyList(), emptyList(), null, emptyMap()), EvidenceGraph(), rep) }
        val a = OsintDns.query(d, "A")
        val ns = OsintDns.query(d, "NS")
        val mx = OsintDns.query(d, "MX")
        val ctUrl = "https://crt.sh/?q=%25.$d&output=json"
        val ct = Net.get(ctUrl, timeoutMs = 25000, maxBytes = 3_000_000, cacheSec = 3600)
        val arr = ct.jsonArray()
        val ctNames = if (arr == null) emptyList() else {
            val raw = ArrayList<String>()
            for (i in 0 until arr.length()) raw.add(arr.getJSONObject(i).optString("name_value"))
            OsintLogic.ctNames(d, raw)
        }
        val facts = DomainFacts(
            domain = d,
            retrievedAt = now,
            addresses = if (a.ok) a.data.filter { it.isNotBlank() } else emptyList(),
            mxHosts = if (mx.ok) OsintLogic.parseMx(mx.data).first.map { it.host } else emptyList(),
            nameServers = if (ns.ok) ns.data.map { it.removeSuffix(".").lowercase() }.filter { it.isNotBlank() } else emptyList(),
            certNames = ctNames,
            certIssuer = null,
            sourceFor = mapOf("A" to a.source, "MX" to mx.source, "NS" to ns.source, "CT" to ctUrl),
        )
        val g = buildDomainGraph(facts)
        if (!a.ok) rep.na("Addresses", "lookup failed: " + a.why, a.source)
        if (!ct.ok && arr == null) rep.na("Certificate-transparency names", ct.why() + " (crt.sh is slow at times; try again later)", ctUrl)
        for (e in g.edges) {
            val from = g.nodes.firstOrNull { it.id == e.from }?.label ?: e.from
            val to = g.nodes.firstOrNull { it.id == e.to }?.label ?: e.to
            val kind = if (e.observed) Ev.VERIFIED else Ev.ESTIMATE
            rep.add(e.relation.substringBefore(" (").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                "$from -> $to  [${e.confidence.name.lowercase()} confidence, ${if (e.observed) "observed" else "inferred"}]",
                kind, e.evidence.reference)
        }
        if (g.refused.isNotEmpty()) rep.obs("Links not added", "${g.refused.size} refused by the evidence rules", "")
        rep.summary = "Links are read from public DNS and certificate-transparency records. Two domains on the same address, or with names in the same certificate, are shown as links only. " +
            "They do NOT show that the domains have the same owner. Inferred links are marked as such."
        return Relationships(facts, g, rep)
    }
}
