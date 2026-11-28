package com.jarvis.control.intel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Evidence rules for the relationship graph. */
class OsintGraphTest {
    private val now = 1_700_000_000_000L
    private fun ev(ref: String = "https://dns.google/resolve?name=a.com&type=A") = Evidence("Google DNS", ref, now)

    @Test fun refusesEdgesWithoutSource() {
        val g = EvidenceGraph()
        val a = g.node(NodeType.DOMAIN, "a.com"); val b = g.node(NodeType.IP, "1.2.3.4")
        assertFalse(g.link(a, b, "RESOLVES_TO", true, Confidence.HIGH, Evidence("", "ref", now)))
        assertFalse(g.link(a, b, "RESOLVES_TO", true, Confidence.HIGH, Evidence("Google DNS", "", now)))
        assertFalse(g.link(a, b, "RESOLVES_TO", true, Confidence.HIGH, Evidence("Google DNS", "ref", 0L)))
        assertEquals(0, g.edges.size)
        assertEquals(3, g.refused.size)
    }

    @Test fun neverGivesInferredLinksHighConfidence() {
        val g = EvidenceGraph()
        val a = g.node(NodeType.DOMAIN, "a.com"); val b = g.node(NodeType.DOMAIN, "b.com")
        assertFalse(g.link(a, b, "SHARES_IP_ADDRESS", false, Confidence.HIGH, ev()))
        assertTrue(g.link(a, b, "SHARES_IP_ADDRESS", false, Confidence.LOW, ev()))
    }

    @Test fun acceptsObservedHighConfidenceAndRefusesDuplicates() {
        val g = EvidenceGraph()
        val a = g.node(NodeType.DOMAIN, "a.com"); val b = g.node(NodeType.IP, "1.2.3.4")
        assertTrue(g.link(a, b, "RESOLVES_TO", true, Confidence.HIGH, ev()))
        assertFalse(g.link(a, b, "RESOLVES_TO", true, Confidence.HIGH, ev()))
        assertEquals(1, g.edges.size)
        assertEquals(Evidence("Google DNS", "https://dns.google/resolve?name=a.com&type=A", now), g.edges[0].evidence)
    }

    @Test fun refusesSelfLinks() {
        val g = EvidenceGraph()
        val a = g.node(NodeType.DOMAIN, "a.com")
        assertFalse(g.link(a, a, "NAME_IN_CERTIFICATE_LOG", true, Confidence.HIGH, ev()))
    }

    @Test fun domainGraphUsesOnlyRecordsWithReferences() {
        val facts = DomainFacts(
            domain = "a.com", retrievedAt = now, addresses = listOf("1.2.3.4"), mxHosts = listOf("mx.a.com"),
            nameServers = listOf("ns1.a.com"), certNames = listOf("a.com", "www.a.com"), certIssuer = null,
            sourceFor = mapOf("A" to "https://dns.google/a", "MX" to "https://dns.google/mx", "NS" to "https://dns.google/ns", "CT" to "https://crt.sh/a"),
        )
        val g = buildDomainGraph(facts)
        assertTrue(g.edges.all { it.evidence.reference.isNotBlank() && it.evidence.retrievedAt == now })
        assertTrue(g.edges.any { it.relation == "RESOLVES_TO" && it.observed })
        assertTrue(g.edges.any { it.relation == "NAME_IN_CERTIFICATE_LOG" && it.to.endsWith("www.a.com") })
        assertEquals("a.com names itself are not linked", 0, g.edges.count { it.relation == "NAME_IN_CERTIFICATE_LOG" && it.to.endsWith(":a.com") })
    }

    @Test fun missingSourceReferenceIsRefusedNotSilentlyAdded() {
        val facts = DomainFacts("a.com", now, listOf("1.2.3.4"), emptyList(), emptyList(), emptyList(), null, emptyMap())
        val g = buildDomainGraph(facts)
        assertEquals(0, g.edges.size)
        assertEquals(1, g.refused.size)
    }

    @Test fun sharedAddressIsLowConfidenceInferenceNeverOwnership() {
        val g = EvidenceGraph()
        val ip = g.node(NodeType.IP, "1.2.3.4")
        g.link(g.node(NodeType.DOMAIN, "a.com"), ip, "RESOLVES_TO", true, Confidence.HIGH, ev("https://dns.google/a"))
        g.link(g.node(NodeType.DOMAIN, "b.com"), ip, "RESOLVES_TO", true, Confidence.HIGH, ev("https://dns.google/b"))
        g.inferSharedAddresses(Evidence("DNS", "shared address inference", now))
        val shared = g.edges.filter { it.relation.startsWith("SHARES_IP_ADDRESS") }
        assertEquals(1, shared.size)
        assertFalse(shared[0].observed)
        assertEquals(Confidence.LOW, shared[0].confidence)
        assertTrue(shared[0].relation.contains("inferred"))
    }

    @Test fun markdownShowsSourcesAndTimes() {
        val g = EvidenceGraph()
        g.link(g.node(NodeType.DOMAIN, "a.com"), g.node(NodeType.IP, "1.2.3.4"), "RESOLVES_TO", true, Confidence.HIGH, ev("ref|with-pipe"))
        val md = g.toMarkdown()
        assertTrue(md.contains("RESOLVES_TO"))
        assertTrue(md.contains("ref%7Cwith-pipe"))
        assertTrue(md.contains(now.toString()))
        assertTrue(md.contains("observed"))
    }

    @Test fun layoutPlacesEveryNodeInsideTheCanvas() {
        val g = EvidenceGraph()
        for (i in 1..6) g.node(NodeType.DOMAIN, "d$i.com")
        val pos = g.layout(400f, 300f)
        assertEquals(6, pos.size)
        for ((x, y) in pos.values) {
            assertTrue(x in 0f..400f && y in 0f..300f)
        }
    }
}
