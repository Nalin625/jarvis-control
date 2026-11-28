package com.jarvis.control.intel

/*
 * OSINT phase 2: email intelligence, limited to configuration.
 * Syntax, the domain's mail servers, whether those servers resolve, and the SPF/DMARC policy.
 * It does NOT connect to a mail server, send mail, or try an address. So it cannot say whether a mailbox exists.
 * Breach exposure for an email address is already handled by SecTools.breach and is not repeated here.
 */
object OsintEmail {

    fun check(input: String): Report {
        val raw = input.trim()
        val rep = Report("Email configuration check", "email", OsintLogic.maskEmail(raw))
        if (!OsintLogic.emailSyntaxOk(raw)) {
            rep.ver("Syntax", "not a valid email address format", "local check")
            rep.summary = "Enter an address such as name@example.com."
            return rep
        }
        rep.ver("Syntax", "valid address format", "local check")
        val domain = OsintLogic.emailDomain(raw)

        val mxAns = OsintDns.query(domain, "MX")
        if (!mxAns.ok) {
            rep.na("Domain configuration", "DNS lookup failed: " + mxAns.why.ifBlank { "service did not answer" }, mxAns.source)
            return rep
        }
        if (mxAns.status == 3) {
            rep.ver("Domain exists", "no - the domain name is not registered in DNS", mxAns.source)
            rep.summary = "Mail to this address cannot be delivered because the domain does not exist."
            return rep
        }
        rep.ver("Domain exists", "yes (DNS answers for it)", mxAns.source)

        val (mx, nullMx) = OsintLogic.parseMx(mxAns.data)
        when {
            nullMx -> rep.ver("Mail servers", "none - the domain declares it accepts no mail", mxAns.source)
            mx.isEmpty() -> {
                val fallback = OsintDns.query(domain, "A")
                if (fallback.data.isNotEmpty()) rep.obs("Mail servers", "no MX records; mail falls back to the domain's address record (${fallback.data.first()})", mxAns.source)
                else rep.est("Mail servers", "none found: mail to this address is probably not accepted", mxAns.source)
            }
            else -> {
                rep.ver("Mail servers", "${mx.size} configured", mxAns.source)
                for (m in mx.take(3)) {
                    val host = OsintDns.query(m.host, "A")
                    val label = "Mail server ${m.pref}: ${m.host}"
                    when {
                        !host.ok -> rep.na(label, "could not check: " + host.why, host.source)
                        host.data.isEmpty() -> rep.est(label, "name does not resolve to an address: this mail server is misconfigured", host.source)
                        else -> rep.ver(label, "resolves to ${host.data.first()}", host.source)
                    }
                }
            }
        }

        val txt = OsintDns.query(domain, "TXT")
        val spf = if (txt.ok) OsintLogic.parseSpf(txt.data) else null
        if (!txt.ok) rep.na("SPF", "lookup failed", txt.source)
        else if (spf == null) rep.obs("SPF", "no SPF record", txt.source)
        else { rep.ver("SPF", "present (default rule: ${spf.all ?: "none"})", txt.source); for (w in spf.warnings) rep.est("SPF warning", w, txt.source) }

        val dm = OsintDns.query("_dmarc.$domain", "TXT")
        val dmarc = if (dm.ok) OsintLogic.parseDmarc(dm.data) else null
        if (!dm.ok) rep.na("DMARC", "lookup failed", dm.source)
        else if (dmarc == null) rep.obs("DMARC", "no DMARC record", dm.source)
        else { rep.ver("DMARC", "present (policy: ${dmarc.policy ?: "not set"})", dm.source); for (w in dmarc.warnings) rep.est("DMARC warning", w, dm.source) }

        rep.na("Mailbox exists?", "cannot be checked from outside. Only the mail provider or the owner can confirm a mailbox, and this tool never contacts a mail server or tries the address.", "")
        rep.summary = "This checks the domain's mail setup only. A valid-looking address on a correctly set-up domain can still have no mailbox behind it."
        return rep
    }
}
