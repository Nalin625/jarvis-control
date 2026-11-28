package com.jarvis.control.intel

import com.jarvis.control.intel.OsintLogic.emailSyntaxOk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing and validation rules for the OSINT phases. Pure functions, no network. */
class OsintLogicTest {

    @Test fun normalisesDomains() {
        assertEquals("example.com", OsintLogic.normDomain("https://Example.com/path?x=1"))
        assertEquals("example.com", OsintLogic.normDomain("example.com."))
        assertEquals("example.com", OsintLogic.normDomain("  EXAMPLE.com:443 "))
    }

    @Test fun validatesDomains() {
        assertTrue(OsintLogic.validDomain("example.com"))
        assertTrue(OsintLogic.validDomain("sub.example.co.uk"))
        assertFalse(OsintLogic.validDomain("exa mple.com"))
        assertFalse(OsintLogic.validDomain("localhost"))
        assertFalse(OsintLogic.validDomain("-bad.example.com"))
    }

    @Test fun parsesMxInPriorityOrder() {
        val (mx, nullMx) = OsintLogic.parseMx(listOf("20 mx2.example.com.", "10 mx1.example.com."))
        assertEquals(listOf("mx1.example.com", "mx2.example.com"), mx.map { it.host })
        assertFalse(nullMx)
    }

    @Test fun recognisesNullMx() {
        val (mx, nullMx) = OsintLogic.parseMx(listOf("0 ."))
        assertTrue(nullMx)
        assertTrue(mx.isEmpty())
    }

    @Test fun readsSpfRecordAndDefaultRule() {
        val spf = OsintLogic.parseSpf(listOf("\"v=spf1 include:_spf.google.com ~all\""))
        assertNotNull(spf)
        assertEquals("~all", spf!!.all)
        assertEquals(1, spf.lookupTerms)
        assertTrue(spf.warnings.isEmpty())
    }

    @Test fun missingSpfIsNull() {
        assertNull(OsintLogic.parseSpf(listOf("\"google-site-verification=abc\"")))
    }

    @Test fun flagsWeakAndDuplicateSpf() {
        val weak = OsintLogic.parseSpf(listOf("\"v=spf1 +all\""))!!
        assertTrue(weak.warnings.any { it.contains("any server") })
        val dup = OsintLogic.parseSpf(listOf("\"v=spf1 -all\"", "\"v=spf1 ~all\""))!!
        assertTrue(dup.warnings.any { it.contains("more than one") })
    }

    @Test fun flagsTooManySpfLookups() {
        val terms = (1..11).joinToString(" ") { "include:h$it.example.com" }
        val spf = OsintLogic.parseSpf(listOf("\"v=spf1 $terms -all\""))!!
        assertEquals(11, spf.lookupTerms)
        assertTrue(spf.warnings.any { it.contains("limit is 10") })
    }

    @Test fun readsDmarcPolicyAndCoverage() {
        val d = OsintLogic.parseDmarc(listOf("\"v=DMARC1; p=reject; rua=mailto:r@example.com; pct=50\""))!!
        assertEquals("reject", d.policy)
        assertEquals(50, d.pct)
        assertTrue(d.hasReports)
        assertTrue(d.warnings.any { it.contains("only 50%") })
    }

    @Test fun dmarcMonitorOnlyIsWarned() {
        val d = OsintLogic.parseDmarc(listOf("v=DMARC1; p=none"))!!
        assertTrue(d.warnings.any { it.contains("monitored") })
        assertTrue(d.warnings.any { it.contains("rua") })
    }

    @Test fun missingDmarcIsNull() {
        assertNull(OsintLogic.parseDmarc(listOf("something else")))
    }

    @Test fun parsesRobotsForStarGroupOnly() {
        val text = """
            # comment
            User-agent: *
            Disallow: /admin
            Allow: /public

            User-agent: BadBot
            Disallow: /

            Sitemap: https://example.com/sitemap.xml
        """.trimIndent()
        val r = OsintLogic.parseRobots(text)
        assertEquals(listOf("https://example.com/sitemap.xml"), r.sitemaps)
        assertFalse("a rule for BadBot must not count as blocking everyone", r.blocksAllBots)
        assertEquals(2, r.rules)
    }

    @Test fun detectsBlockAllCrawlers() {
        assertTrue(OsintLogic.parseRobots("User-agent: *\nDisallow: /").blocksAllBots)
    }

    @Test fun readsSitemapLocations() {
        val xml = "<urlset><url><loc>https://a.com/x</loc></url><url><loc> https://a.com/y?a=1&amp;b=2 </loc></url></urlset>"
        assertEquals(listOf("https://a.com/x", "https://a.com/y?a=1&b=2"), OsintLogic.sitemapLocs(xml))
        assertFalse(OsintLogic.isSitemapIndex(xml))
        assertTrue(OsintLogic.isSitemapIndex("<sitemapindex><sitemap><loc>https://a.com/1.xml</loc></sitemap></sitemapindex>"))
    }

    @Test fun certificateNamesStayUnderTheDomain() {
        val names = OsintLogic.ctNames("example.com", listOf("*.example.com\nevilexample.com\nwww.example.com", "example.com", "other.org", "a b.example.com"))
        assertEquals(listOf("example.com", "www.example.com"), names)
    }

    @Test fun parsesIpv4() {
        assertEquals(0x01020304L, OsintLogic.ip4ToLong("1.2.3.4"))
        assertNull(OsintLogic.ip4ToLong("1.2.3"))
        assertNull(OsintLogic.ip4ToLong("256.1.1.1"))
    }

    @Test fun matchesListedRanges() {
        val ranges = OsintLogic.parseCidrList(listOf("1.2.3.0/24 ; SBL1", "# comment", "10.0.0.0/8", "garbage", "0.0.0.0/0"))
        assertEquals("1.2.3.0/24", OsintLogic.listedRange("1.2.3.200", ranges.filter { it.raw != "0.0.0.0/0" }))
        assertNull(OsintLogic.listedRange("1.2.4.1", ranges.filter { it.raw != "0.0.0.0/0" }))
        assertEquals("10.0.0.0/8", OsintLogic.listedRange("10.255.255.255", ranges.filter { it.raw != "0.0.0.0/0" }))
        assertNotNull(OsintLogic.listedRange("8.8.8.8", ranges))
        assertNull(OsintLogic.listedRange("not-an-ip", ranges))
    }

    @Test fun readsFeedHostsOnly() {
        val hosts = OsintLogic.feedHosts(listOf("https://login.evil.example/path?x=1", "http://user@1.2.3.4:8080/a", "not a url"))
        assertEquals(setOf("login.evil.example", "1.2.3.4"), hosts)
    }

    @Test fun validatesEmailSyntax() {
        assertTrue(emailSyntaxOk("a.b@example.com"))
        assertTrue(emailSyntaxOk("x@sub.example.co.uk"))
        assertFalse(emailSyntaxOk("a..b@example.com"))
        assertFalse(emailSyntaxOk("@example.com"))
        assertFalse(emailSyntaxOk("a@localhost"))
        assertFalse(emailSyntaxOk("a@example"))
        assertFalse(emailSyntaxOk("no-at-sign"))
    }

    @Test fun masksEmailInReports() {
        assertEquals("a***@example.com", OsintLogic.maskEmail("Alice@Example.com"))
    }

    @Test fun validatesCveIds() {
        assertTrue(OsintLogic.validCve("CVE-2021-44228"))
        assertFalse(OsintLogic.validCve("CVE-21-1"))
        assertFalse(OsintLogic.validCve("log4j"))
    }

    @Test fun cvssBands() {
        assertEquals("none", OsintLogic.cvssSeverity(0.0))
        assertEquals("low", OsintLogic.cvssSeverity(3.9))
        assertEquals("medium", OsintLogic.cvssSeverity(6.9))
        assertEquals("high", OsintLogic.cvssSeverity(8.8))
        assertEquals("critical", OsintLogic.cvssSeverity(9.8))
    }

    @Test fun summarisesVerdictsWithoutCallingOneListingAVerdict() {
        val s = summarizeVerdicts(listOf(
            Verdict("A", VState.LISTED, ""), Verdict("B", VState.CLEAN, ""),
            Verdict("C", VState.NEEDS_KEY, ""), Verdict("D", VState.UNKNOWN, ""),
        ))
        assertTrue(s, s.contains("1 of 2 checked sources list it"))
        assertTrue(s.contains("1 need an API key"))
        assertTrue(s.contains("one list is not a verdict"))
        assertTrue(summarizeVerdicts(listOf(Verdict("C", VState.NEEDS_KEY, ""))).startsWith("No source could give a result."))
    }
}
