package com.jarvis.control.intel

/*
 * Evidence graph (OSINT phase 6). Pure Kotlin, no Android imports.
 *
 * Rules enforced here, not just in the UI:
 *  - every edge has a relation, a source, a reference (URL or record id) and a retrieval time, or it is refused;
 *  - an edge that is inferred (not read from a record) can never be HIGH confidence;
 *  - a shared IP, certificate or name never means two entities are the same person or organisation.
 *    Shared hosting is recorded as an inferred LOW-confidence edge with that wording, nothing stronger.
 */
enum class NodeType { DOMAIN, IP, DNS_RECORD, CERTIFICATE, GITHUB_USER, REPOSITORY, ORGANIZATION, VULNERABILITY, THREAT_INDICATOR }

enum class Confidence { HIGH, MEDIUM, LOW }

data class GNode(val id: String, val type: NodeType, val label: String)

/** Where a link came from: [source] is the provider (e.g. "Google DNS-over-HTTPS"), [reference] the exact URL or record id. */
data class Evidence(val source: String, val reference: String, val retrievedAt: Long)

data class GEdge(
    val from: String,
    val to: String,
    val relation: String,
    val observed: Boolean,
    val confidence: Confidence,
    val evidence: Evidence,
)

class EvidenceGraph {
    private val nodeMap = LinkedHashMap<String, GNode>()
    private val edgeList = ArrayList<GEdge>()
    /** Edges refused by the rules, with the reason. Shown so nothing disappears silently. */
    val refused = ArrayList<String>()

    val nodes: List<GNode> get() = nodeMap.values.toList()
    val edges: List<GEdge> get() = edgeList.toList()

    fun node(type: NodeType, label: String): GNode {
        val l = label.trim()
        val id = "${type.name}:${l.lowercase()}"
        return nodeMap.getOrPut(id) { GNode(id, type, l) }
    }

    /** Adds one edge if it passes every rule. Returns false (and records why) otherwise. */
    fun link(from: GNode, to: GNode, relation: String, observed: Boolean, confidence: Confidence, evidence: Evidence): Boolean {
        val why = when {
            relation.isBlank() -> "no relation given"
            evidence.source.isBlank() || evidence.reference.isBlank() -> "no source reference: ${from.label} -> ${to.label}"
            evidence.retrievedAt <= 0L -> "no retrieval time: ${from.label} -> ${to.label}"
            from.id == to.id -> "self link ignored"
            !observed && confidence == Confidence.HIGH -> "inferred links cannot be HIGH confidence: ${from.label} -> ${to.label}"
            edgeList.any { it.from == from.id && it.to == to.id && it.relation == relation } -> null  // duplicate: quietly skipped
            else -> null
        }
        if (why != null) { refused.add(why); return false }
        if (edgeList.any { it.from == from.id && it.to == to.id && it.relation == relation }) return false
        nodeMap.getOrPut(from.id) { from }
        nodeMap.getOrPut(to.id) { to }
        edgeList.add(GEdge(from.id, to.id, relation, observed, confidence, evidence))
        return true
    }

    /**
     * Adds LOW-confidence, inferred "shares an IP address" edges between domains that resolve to the same IP.
     * This says only that they share an address, which is common on shared hosting. It never implies common ownership.
     */
    fun inferSharedAddresses(evidence: Evidence) {
        val ipNodes = nodes.filter { it.type == NodeType.IP }
        for (ip in ipNodes) {
            val domains = edges.filter { it.to == ip.id && it.relation == "RESOLVES_TO" }.map { it.from }.distinct()
            for (i in domains.indices) for (j in i + 1 until domains.size) {
                val a = nodeMap[domains[i]] ?: continue
                val b = nodeMap[domains[j]] ?: continue
                link(a, b, "SHARES_IP_ADDRESS (inferred: ${ip.label})", observed = false, confidence = Confidence.LOW,
                    evidence = evidence.copy(reference = "same address ${ip.label}; ${evidence.reference}"))
            }
        }
    }

    fun toText(): String = buildString {
        append("Relationship graph: ").append(nodes.size).append(" entities, ").append(edges.size).append(" links\n")
        for (e in edges) {
            val a = nodeMap[e.from]?.label ?: e.from
            val b = nodeMap[e.to]?.label ?: e.to
            append("- ").append(a).append(" --[").append(e.relation).append("]--> ").append(b)
            append("  (").append(if (e.observed) "observed" else "inferred").append(", ").append(e.confidence.name.lowercase()).append(" confidence)\n")
            append("    source: ").append(e.evidence.source).append(" | ").append(e.evidence.reference).append('\n')
        }
        if (refused.isNotEmpty()) append("Not added (rules): ").append(refused.size).append('\n')
    }

    fun toMarkdown(): String = buildString {
        append("## Relationship graph\n\n")
        append("| From | Relation | To | Observed/inferred | Confidence | Source | Reference | Retrieved |\n")
        append("|---|---|---|---|---|---|---|---|\n")
        for (e in edges) {
            val a = nodeMap[e.from]?.label ?: e.from
            val b = nodeMap[e.to]?.label ?: e.to
            append("| ").append(a).append(" | ").append(e.relation).append(" | ").append(b).append(" | ")
            append(if (e.observed) "observed" else "inferred").append(" | ").append(e.confidence.name.lowercase()).append(" | ")
            append(e.evidence.source).append(" | ").append(e.evidence.reference.replace("|", "%7C")).append(" | ")
            append(e.evidence.retrievedAt).append(" |\n")
        }
    }

    /** Circular layout: deterministic positions so the picture does not jump between redraws. */
    fun layout(width: Float, height: Float): Map<String, Pair<Float, Float>> {
        val n = nodeMap.size
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) * 0.36f
        val out = LinkedHashMap<String, Pair<Float, Float>>()
        nodeMap.values.forEachIndexed { i, node ->
            val ang = if (n <= 1) 0.0 else (2 * Math.PI * i / n) - Math.PI / 2
            out[node.id] = (cx + r * Math.cos(ang)).toFloat() to (cy + r * Math.sin(ang)).toFloat()
        }
        return out
    }
}

/** Facts collected for one domain. Built by the network layer, turned into a graph here. */
data class DomainFacts(
    val domain: String,
    val retrievedAt: Long,
    val addresses: List<String>,       // A records
    val mxHosts: List<String>,
    val nameServers: List<String>,
    val certNames: List<String>,       // names from certificate transparency under this domain
    val certIssuer: String?,           // issuer of the live certificate, if read
    val sourceFor: Map<String, String>, // record kind -> source reference URL
)

/** Builds the evidence graph for a domain from observed records only. */
fun buildDomainGraph(f: DomainFacts): EvidenceGraph {
    val g = EvidenceGraph()
    val d = g.node(NodeType.DOMAIN, f.domain)
    fun ev(kind: String, provider: String) = Evidence(provider, f.sourceFor[kind] ?: "", f.retrievedAt)
    for (ip in f.addresses) g.link(d, g.node(NodeType.IP, ip), "RESOLVES_TO", true, Confidence.HIGH, ev("A", "DNS (Google DNS-over-HTTPS)"))
    for (mx in f.mxHosts) g.link(d, g.node(NodeType.DNS_RECORD, "MX " + mx), "MAIL_SERVER", true, Confidence.HIGH, ev("MX", "DNS (Google DNS-over-HTTPS)"))
    for (ns in f.nameServers) g.link(d, g.node(NodeType.DNS_RECORD, "NS " + ns), "NAME_SERVER", true, Confidence.HIGH, ev("NS", "DNS (Google DNS-over-HTTPS)"))
    if (f.certIssuer != null) {
        val c = g.node(NodeType.CERTIFICATE, "Certificate for ${f.domain} (${f.certIssuer})")
        g.link(d, c, "PRESENTS_CERTIFICATE", true, Confidence.HIGH, ev("CERT", "The site's own TLS certificate"))
    }
    for (name in f.certNames) {
        if (name == f.domain) continue
        g.link(d, g.node(NodeType.DOMAIN, name), "NAME_IN_CERTIFICATE_LOG", true, Confidence.HIGH, ev("CT", "crt.sh certificate transparency"))
    }
    g.inferSharedAddresses(Evidence("DNS (Google DNS-over-HTTPS)", "shared address inference", f.retrievedAt))
    return g
}
