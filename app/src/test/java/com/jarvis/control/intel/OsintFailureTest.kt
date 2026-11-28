package com.jarvis.control.intel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure paths and report output. The tests use inputs that are rejected before any network call, and a host name
 * that cannot exist, so they pass with or without internet access.
 */
class OsintFailureTest {

    @Test fun badDomainIsRejectedWithoutAnyNetworkCall() {
        val r = OsintDomain.mxRecords("not a domain")
        assertTrue(r.findings.any { it.ev == Ev.UNAVAILABLE && it.label == "Input" })
    }

    @Test fun badEmailSyntaxIsReportedNotChecked() {
        val r = OsintEmail.check("bad-address")
        assertTrue(r.findings.any { it.label == "Syntax" && it.value.contains("not a valid") })
        assertFalse(r.findings.any { it.label.startsWith("Mail") })
    }

    @Test fun reputationRejectsNonsenseInput() {
        val r = OsintThreat.reputation("!!!", null)
        assertTrue(r.findings.any { it.ev == Ev.UNAVAILABLE })
    }

    @Test fun githubInputIsValidatedBeforeAnyRequest() {
        assertTrue(OsintGithub.repoDetail("no-slash-here", null).findings.any { it.ev == Ev.UNAVAILABLE })
        assertTrue(OsintGithub.searchUsers("x", null).findings.any { it.ev == Ev.UNAVAILABLE })
    }

    @Test fun cveWithoutValidIdFallsBackToKeywordInputCheck() {
        val r = OsintThreat.cve("ab", null)
        assertTrue(r.findings.any { it.ev == Ev.UNAVAILABLE })
    }

    @Test fun unreachableHostIsReportedAsAnError() {
        val r = Net.get("https://no-such-host.invalid/", timeoutMs = 4000)
        assertFalse(r.ok)
        assertNotNull(r.why())
        assertTrue(r.why().isNotBlank())
    }

    @Test fun dnsLookupFailureIsUnavailableNotEmpty() {
        val a = OsintDns.query("example.invalid", "A")
        // Either the resolver said the name does not exist (ok, status 3) or it could not be reached (not ok). Never a made-up answer.
        assertTrue(a.data.isEmpty())
        if (!a.ok) assertTrue(a.why.isNotBlank() || a.status == -1)
    }

    @Test fun reportTextAndMarkdownKeepEvidenceLabels() {
        val r = Report("Test report", "test", "example.com", 1_700_000_000_000L)
        r.ver("Certificate", "valid", "https://example.com")
        r.est("Rating", "fair", "")
        r.na("Feed", "could not be read", "")
        val txt = r.toText()
        assertTrue(txt.contains("[VERIFIED] Certificate: valid"))
        assertTrue(txt.contains("[ESTIMATE] Rating: fair"))
        assertTrue(txt.contains("[UNAVAILABLE] Feed: could not be read"))
        val md = r.toMarkdown()
        assertTrue(md.contains("## Verified"))
        assertTrue(md.contains("## Unavailable"))
        assertTrue(md.contains("[source](https://example.com)"))
    }

    @Test fun verdictFromUnreadableFeedIsUnknownNotClean() {
        // An unreadable feed must never read as "not listed".
        val v = Verdict("Feed", VState.UNKNOWN, "could not be read")
        val s = summarizeVerdicts(listOf(v))
        assertFalse(s.contains("do not"))
        assertTrue(s.contains("could not be checked"))
    }

    @Test fun workspaceRejectsItemsForDeletedProjects() {
        val s = MemWorkspaceStore()
        val p = s.createProject("gone", 1L)
        s.deleteProject(p)
        assertEquals(-1L, s.addItem(Item(0, p, 2L, "x", "t", "title", "", emptyList())))
    }
}
