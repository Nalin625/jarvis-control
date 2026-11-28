package com.jarvis.control.intel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OBG = Color.parseColor("#0A0F1A")
private val OPANEL = Color.parseColor("#0F1B2D")
private val OLINE = Color.parseColor("#1F3A5A")
private val OACC = Color.parseColor("#4FA8FF")
private val OTXT = Color.parseColor("#D6E6F7")
private val ODIM = Color.parseColor("#6F86A3")

/** Draws an evidence graph. Solid lines are observed in a record; dashed lines are inferred. Thicker = higher confidence. */
class OsintGraphView(ctx: Context, private val g: EvidenceGraph) : View(ctx) {
    private val pNode = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OACC; style = Paint.Style.FILL }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OTXT; textSize = 22f }
    private val dash = DashPathEffect(floatArrayOf(14f, 10f), 0f)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(OBG)
        val pos = g.layout(width.toFloat(), height.toFloat())
        for (e in g.edges) {
            val a = pos[e.from] ?: continue
            val b = pos[e.to] ?: continue
            pLine.color = when (e.confidence) { Confidence.HIGH -> OACC; Confidence.MEDIUM -> Color.parseColor("#7FB8FF"); Confidence.LOW -> ODIM }
            pLine.strokeWidth = when (e.confidence) { Confidence.HIGH -> 4f; Confidence.MEDIUM -> 3f; Confidence.LOW -> 2f }
            pLine.pathEffect = if (e.observed) null else dash
            c.drawLine(a.first, a.second, b.first, b.second, pLine)
        }
        for (n in g.nodes) {
            val p = pos[n.id] ?: continue
            c.drawCircle(p.first, p.second, 10f, pNode)
            c.drawText(n.label.take(30), p.first + 14f, p.second + 7f, pText)
        }
    }
}

/** The OSINT workspace: projects, saved checks, notes, comparison, Markdown export and the relationship graph. */
class OsintActivity : Activity() {
    private class Check(val label: String, val hint: String, val graph: Boolean = false, val run: (String) -> Report)

    private lateinit var store: SqlWorkspaceStore
    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private lateinit var target: EditText
    private lateinit var out: TextView
    private var projectId: Long = -1L
    @Volatile private var lastGraph: EvidenceGraph? = null
    private val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(ts: Long) = dateFmt.format(Date(ts))
    private fun project() = if (projectId < 0) null else store.projects().firstOrNull { it.id == projectId }

    private fun checks(): List<Check> = listOf(
        Check("Mail servers (MX)", "a domain") { OsintDomain.mxRecords(it) },
        Check("SPF, DMARC & DKIM", "a domain") { OsintDomain.spfDmarcDkim(it) },
        Check("robots.txt & sitemaps", "a website") { OsintDomain.robotsAndSitemaps(it) },
        Check("Relationships & graph", "a domain", graph = true) { relationships(it) },
        Check("Email configuration", "an email address") { OsintEmail.check(it) },
        Check("GitHub users", "a GitHub name") { OsintGithub.searchUsers(it, this) },
        Check("GitHub repositories", "a topic or name") { OsintGithub.searchRepos(it, this) },
        Check("GitHub repo details", "owner/name") { OsintGithub.repoDetail(it, this) },
        Check("CVE or keyword", "CVE-2021-44228 or a keyword") { OsintThreat.cve(it, this) },
        Check("Package vulnerabilities", "npm:express@4.17.1") { OsintThreat.osv(it) },
        Check("Reputation (domain or IP)", "a domain or IPv4") { OsintThreat.reputation(it, this) },
    )

    private fun relationships(input: String): Report {
        val rel = OsintDomain.relationships(input)
        lastGraph = rel.graph
        return rel.report
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = SqlWorkspaceStore.get(this)
        window.statusBarColor = OBG
        val root = ScrollView(this).apply { setBackgroundColor(OBG) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(20), px(16), px(28)) }
        root.addView(col)
        setContentView(root)
        render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(text("OSINT WORKSPACE", OACC, 22f, bold = false))
        col.addView(text("Public sources only  ·  projects stay on this phone  ·  every result is labelled", ODIM, 11f))
        if (projectId < 0) renderProjects() else renderProject()
    }

    private fun renderProjects() {
        add(btn("New project") {
            ask("New investigation", "a name, e.g. Company X domains") { name ->
                val id = store.createProject(name, System.currentTimeMillis())
                if (id < 0) toast("Give it a name that is not already used.") else { projectId = id; render() }
            }
        }, 14)
        add(btn("API keys (optional)") { keysDialog() })
        add(btn("Back to Intelligence") { finish() })
        add(text("Projects", OACC, 13f, bold = true), 18)
        val list = store.projects()
        if (list.isEmpty()) add(text("No projects yet. Create one to start saving results.", ODIM, 12f), 6)
        for (p in list) add(btn("${p.name}   ·   ${fmt(p.created)}") { projectId = p.id; render() }, 6)
        status = text("", ODIM, 12f); add(status, 12)
        out = text("", OTXT, 13f); add(out, 8)
    }

    private fun renderProject() {
        val p = project()
        if (p == null) { projectId = -1; render(); return }
        add(btn("← Projects") { projectId = -1; render() }, 14)
        add(text(p.name, OACC, 18f, bold = true), 12)
        target = EditText(this).apply {
            hint = "type the target, e.g. example.com"; setHintTextColor(ODIM); setTextColor(OTXT); setSingleLine(true)
            setBackgroundColor(OPANEL); setPadding(px(10), px(10), px(10), px(10))
        }
        add(target, 10)
        add(text("Run a check on the target (each result is saved to this project with its source and time):", ODIM, 11f), 10)
        for (c in checks()) add(btn(c.label) { runCheck(c) }, 6)
        add(btn("GitHub code search: not offered") { show(OsintGithub.unsupportedNote()) }, 6)
        add(text("Project tools", OACC, 13f, bold = true), 16)
        add(btn("Compare the two latest results for this target") { compareLatest() }, 6)
        add(btn("Add a note") { ask("Note", "what you found or want to remember") { t -> if (store.addNote(p.id, System.currentTimeMillis(), t) < 0) toast("Note not saved."); render() } }, 6)
        add(btn("Export project as Markdown") { show(exportProject(p)) }, 6)
        add(btn("Delete this project") {
            AlertDialog.Builder(this).setTitle("Delete project?")
                .setMessage("Deletes '${p.name}' with its saved results and notes. Other projects are not touched.")
                .setPositiveButton("Delete") { _, _ -> store.deleteProject(p.id); projectId = -1; render() }
                .setNegativeButton("Cancel", null).show()
        }, 6)
        add(text("Saved results", OACC, 13f, bold = true), 16)
        val items = store.items(p.id)
        if (items.isEmpty()) add(text("Nothing saved yet.", ODIM, 12f), 6)
        for (item in items.take(25)) add(btn("${fmt(item.ts)}   ${item.title}") { showItem(p, item) }, 6)
        val notes = store.notes(p.id)
        if (notes.isNotEmpty()) {
            add(text("Notes", OACC, 13f, bold = true), 16)
            for (n in notes.take(10)) add(text("${fmt(n.ts)}  ${n.text}", OTXT, 12f), 6)
        }
        status = text("", ODIM, 12f); add(status, 12)
        out = text("", OTXT, 13f); add(out, 8)
    }

    private fun runCheck(c: Check) {
        val input = target.text.toString().trim()
        if (input.isEmpty()) { status.text = "Type ${c.hint} above first."; return }
        val pid = projectId
        status.text = "Working on ${c.label.lowercase()}... this can take a few seconds."
        Bg.run {
            val r = try { c.run(input) } catch (e: Throwable) {
                Report(c.label, "error", input).also { it.na("Error", "the check stopped unexpectedly (${e.javaClass.simpleName}); nothing was saved") }
            }
            val saved = if (r.kind == "error") -1L else store.addItem(reportToItem(r, pid))
            runOnUiThread {
                status.text = if (saved >= 0) "Saved to this project." else "Not saved (the check failed or the project was removed)."
                show(r, graph = c.graph)
            }
        }
    }

    private var graphView: View? = null
    private var graphLegend: TextView? = null

    /** Shows a report's text. A relationship graph is drawn above the text only when [graph] is set. */
    private fun show(r: Report, graph: Boolean = false) {
        out.text = r.toText()
        val parent = out.parent as LinearLayout
        graphView?.let { parent.removeView(it) }
        graphLegend?.let { parent.removeView(it) }
        graphView = null
        graphLegend = null
        val g = lastGraph
        if (graph && g != null && g.nodes.isNotEmpty()) {
            val legend = text("Graph: solid = read from a record; dashed = inferred. Thicker = higher confidence. Shared addresses are never shown as common ownership.", ODIM, 11f)
            val v = OsintGraphView(this, g)
            val idx = parent.indexOfChild(out)
            parent.addView(legend, idx)
            parent.addView(v, idx + 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(360)))
            graphLegend = legend
            graphView = v
        }
    }

    private fun showItem(p: Project, item: Item) {
        val r = itemToReport(item)
        val actions = arrayOf("Show", "Delete this saved result")
        AlertDialog.Builder(this).setTitle(item.title).setItems(actions) { _, which ->
            if (which == 0) show(r) else AlertDialog.Builder(this)
                .setTitle("Delete this saved result?")
                .setPositiveButton("Delete") { _, _ -> store.deleteItem(p.id, item.id); render() }
                .setNegativeButton("Cancel", null).show()
        }.show()
    }

    private fun itemToReport(it: Item): Report {
        val r = Report(it.title, it.kind, it.target, it.ts)
        for (f in it.findings) r.add(f.label, f.value, f.ev, f.source)
        return r
    }

    private fun compareLatest() {
        val p = project() ?: return
        val t = target.text.toString().trim()
        val pool = store.items(p.id, if (t.isEmpty()) null else t).groupBy { it.title }.values.firstOrNull { it.size >= 2 }
        if (pool == null) { status.text = "Need two saved results with the same check and target. Run the same check twice."; return }
        val newer = pool[0]; val older = pool[1]
        val d = compareItems(older, newer)
        val sb = StringBuilder()
        sb.append("Comparing ${newer.title}\n  older: ${fmt(older.ts)}\n  newer: ${fmt(newer.ts)}\n\n")
        sb.append("New since the older check: ").append(d.added.joinToString("; ").ifEmpty { "none" }).append('\n')
        sb.append("Gone since the older check: ").append(d.removed.joinToString("; ").ifEmpty { "none" }).append('\n')
        sb.append("Changed: ").append(d.changed.joinToString("; ").ifEmpty { "none" }).append('\n')
        sb.append("Not comparable (a lookup failed on one side): ").append(d.notComparable.joinToString("; ").ifEmpty { "none" }).append('\n')
        sb.append("Unchanged: ${d.unchanged}\n\n")
        sb.append("A thing that is gone is not proof that anything was removed or deleted. It may have changed, moved or become unavailable.")
        status.text = "Comparison shown below."
        out.text = sb.toString()
    }

    private fun exportProject(p: Project): Report {
        val items = store.items(p.id)
        val notes = store.notes(p.id)
        val text = exportProjectMarkdown(p, items, notes) { fmt(it) }
        val dir = File(getExternalFilesDir(null) ?: filesDir, "osint_exports").apply { mkdirs() }
        val safe = p.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
        val f = File(dir, "investigation_${safe}_${System.currentTimeMillis()}.md")
        return try {
            f.writeText(text)
            Report("Project exported", "export", p.name).also { it.ver("Saved to", f.absolutePath); it.obs("Items", "${items.size}, notes ${notes.size}") }
        } catch (e: Exception) {
            Report("Project export", "export", p.name).also { it.na("Export", "could not write the file (${e.javaClass.simpleName})") }
        }
    }

    private fun keysDialog() {
        val items = listOf(
            "VirusTotal key" to OsintThreat.KEY_VT,
            "Shodan key" to OsintThreat.KEY_SHODAN,
            "NVD key (optional)" to OsintThreat.KEY_NVD,
            "GitHub token (optional)" to "github_token",
        )
        val labels = items.map { (l, k) -> if (Vault.has(this, k)) "$l  ·  saved" else "$l  ·  not set" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("API keys (stored in the Keystore)")
            .setItems(labels) { _, which ->
                val (label, name) = items[which]
                ask(label, "paste the key; it is never shown again") { k ->
                    if (k.isBlank()) toast("Nothing saved.")
                    else if (OsintThreat.setKey(this, name, k)) toast("Saved.") else toast("Could not save the key.")
                }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun ask(title: String, hint: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { this.hint = hint; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) { if (::status.isInitialized) status.text = s }

    private fun add(v: View, topDp: Int = 0) {
        col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(topDp) })
    }

    private fun text(s: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(OTXT); textSize = 13f
        background = GradientDrawable().apply { setColor(OPANEL); setStroke(px(1), OLINE); cornerRadius = px(8).toFloat() }
        setOnClickListener { onClick() }
    }
}
