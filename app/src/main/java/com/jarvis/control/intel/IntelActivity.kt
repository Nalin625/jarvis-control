package com.jarvis.control.intel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

private val BG = Color.parseColor("#0A0F1A")
private val PANEL = Color.parseColor("#0F1B2D")
private val LINE = Color.parseColor("#1F3A5A")
private val ACC = Color.parseColor("#4FA8FF")
private val TXT = Color.parseColor("#D6E6F7")
private val DIM = Color.parseColor("#6F86A3")

/** The Intelligence dashboard. Separate from the rest of Jarvis; opened from the phone's OPEN group. */
class IntelActivity : Activity() {
    private class Act(val label: String, val needs: String? = null, val ui: Boolean = false, val save: Boolean = true, val exec: (String) -> Report?)

    private var lastReport: Report? = null
    private var pendingPick: ((Uri) -> Unit)? = null
    private var pendingTree: ((Uri) -> Unit)? = null
    private lateinit var input: EditText
    private lateinit var out: TextView
    private lateinit var status: TextView
    private lateinit var actionBox: LinearLayout

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sections(): LinkedHashMap<String, List<Act>> = linkedMapOf(
        "IP" to listOf(
            Act("My public IP", exec = { IpTools.report(this, "") }),
            Act("Look up an IP", "an IP address, e.g. 8.8.8.8", exec = { IpTools.report(this, it) }),
            Act("My IP history", exec = { IpTools.history(this) })),
        "OSINT" to listOf(
            Act("Domain records (RDAP)", "a domain, e.g. example.com", exec = { WebTools.rdapReport(it) }),
            Act("DNS records", "a domain", exec = { WebTools.dnsReport(it) }),
            Act("Certificate", "a domain", exec = { WebTools.certReport(it) }),
            Act("Domain OSINT (all public records)", "a domain", exec = { OsintTools.domainOsint(this, it) }),
            Act("GitHub repositories", "a topic or project name", exec = { OsintTools.githubRepos(it) }),
            Act("GitHub user check", "a GitHub username", exec = { OsintTools.githubHandle(it) }),
            Act("Public web mentions", "a name or term", exec = { OsintTools.mentions(it) }),
            Act("Investigation workspace (projects, graph, export)", ui = true, exec = { startActivity(Intent(this, OsintActivity::class.java)); null })),
        "SOCIAL" to listOf(
            Act("Set up my Instagram", ui = true, exec = { showSocialSetup(); null }),
            Act("My account analytics", exec = { SocialTools.report(this) })),
        "WEBSITE" to listOf(
            Act("Full website scan", "a website", exec = { WebTools.full(this, it) }),
            Act("Availability & redirects", "a website", exec = { WebTools.availability(it) }),
            Act("Security headers", "a website", exec = { WebTools.headersReport(it) }),
            Act("Technologies (guess)", "a website", exec = { WebTools.techReport(it) }),
            Act("Subdomains (public certificates)", "a domain", exec = { WebTools.subdomains(it) }),
            Act("Check for changes now", "a website", exec = { WebTools.changeCheck(this, it) }),
            Act("Watch this site for changes", "a website", exec = { msg("Watch site", if (WebTools.watchAdd(this, it)) "Watching ${Fmt.domainOf(it)}. Background checks run about every 6 hours." else "Couldn't add it (already watched, or 10 sites max).") })),
        "NETWORK" to listOf(
            Act("This network", exec = { NetTools.localInfo(this) }),
            Act("Latency", exec = { NetTools.latency(this) }),
            Act("Internet connectivity", exec = { NetTools.connectivity(this) }),
            Act("DNS diagnostics", exec = { NetTools.dnsDiagnostics() }),
            Act("Devices on my Wi-Fi", "type yes to confirm this is a network you own or run", exec = {
                if (it.trim().lowercase() != "yes") msg("Devices", "Not scanned. Type 'yes' in the box to confirm this network is yours (or one you administer).")
                else NetTools.deviceReport(this)
            }),
            Act("Network history", exec = { NetTools.history(this) })),
        "MEDIA" to listOf(
            Act("Analyse a picture or PDF", ui = true, exec = { pickFile { uri -> Bg.run { val r = MediaTools.analyze(this, uri); runOnUiThread { show(r, true) } } }; null }),
            Act("Read text in a picture (OCR)", ui = true, exec = { pickFile { uri -> Bg.run { MediaTools.ocr(this, uri) { r -> runOnUiThread { show(r, true) } } } }; null }),
            Act("Find duplicate pictures in a folder", ui = true, exec = { pickFolder { tree -> Bg.run { val r = MediaTools.duplicates(this, tree) { p -> runOnUiThread { status.text = "Scanning... $p%" } }; runOnUiThread { show(r, true) } } }; null }),
            Act("Watch a file for changes", ui = true, exec = { pickFile { uri -> Bg.run { val m = MediaTools.integrityAdd(this, uri); runOnUiThread { show(msg("Watch file", m), false) } } }; null }),
            Act("Check watched files now", exec = { MediaTools.integrityCheck(this) })),
        "SECURITY" to listOf(
            Act("Check a link for phishing", "a link or domain", exec = { SecTools.checkUrl(it) }),
            Act("Check an email for breaches", "your own email address", exec = { SecTools.breach(it) }),
            Act("Check a password (never stored)", ui = true, exec = { showPasswordCheck(); null }),
            Act("Device security check", exec = { SecTools.deviceSecurity(this) }),
            Act("App security audit", exec = { SecTools.appAudit(this) }),
            Act("Local security report", exec = { SecTools.localReport(this) }),
            Act("Security events", exec = { SecTools.eventsReport(this) })),
        "HISTORY" to listOf(
            Act("Recent investigations", save = false, exec = { historyList() }),
            Act("Open an investigation", "its number from the list", save = false, exec = { n -> IntelDb.get(this).report(n.toLongOrNull() ?: -1)?.also { lastReport = it } ?: msg("History", "No investigation with that number.") }),
            Act("Delete an investigation", "its number from the list", save = false, exec = { n -> IntelDb.get(this).delete(n.toLongOrNull() ?: -1); msg("History", "Deleted if it existed.") }),
            Act("Clear all history", ui = true, save = false, exec = { confirmClear(); null }),
            Act("Background monitor: turn on", save = false, exec = { IntelWork.schedule(this); msg("Monitor", "On. Checks watched sites, files, internet and your account about every 6 hours, on any network.") }),
            Act("Background monitor: turn off", save = false, exec = { IntelWork.cancel(this); msg("Monitor", "Off.") }),
            Act("Monitor now", save = false, exec = { Report("Monitor check", "monitor", "now").also { r -> IntelWork.runChecks(this).forEach { r.obs("Check", it) } } })),
        "REPORTS" to listOf(
            Act("Export last report (.md)", save = false, exec = { exportLast() }),
            Act("Show exported files", save = false, exec = { listExports() })),
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(20), px(16), px(28)) }
        root.addView(col)
        col.addView(TextView(this).apply { text = "INTELLIGENCE"; setTextColor(ACC); textSize = 22f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); letterSpacing = 0.2f })
        col.addView(TextView(this).apply { text = "Free public sources  ·  stays on this phone  ·  every result is labelled"; setTextColor(DIM); textSize = 11f })
        val names = sections().keys.toList()
        for (row in names.chunked(3)) {
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (n in row) h.addView(btn(n) { selectSection(n) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(px(3), px(8), px(3), 0) })
            col.addView(h)
        }
        status = TextView(this).apply { setTextColor(DIM); textSize = 12f; setPadding(0, px(10), 0, 0) }
        col.addView(status)
        input = EditText(this).apply {
            hint = "type here: a domain, IP, link, email ..."; setHintTextColor(DIM); setTextColor(TXT); setSingleLine(true)
            setBackgroundColor(PANEL); setPadding(px(10), px(10), px(10), px(10))
        }
        col.addView(input, lpW(8))
        actionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actionBox)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE; setTextColor(TXT); textSize = 12f; setTextIsSelectable(true)
            setBackgroundColor(Color.parseColor("#0C1626")); setPadding(px(10), px(10), px(10), px(10))
        }
        col.addView(out, lpW(12))
        setContentView(root)
        selectSection(names.first())
    }

    private fun lpW(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }

    private fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(TXT); textSize = 12f; stateListAnimator = null
        background = GradientDrawable().apply { setColor(PANEL); setStroke(px(1), LINE); cornerRadius = px(8).toFloat() }
        setPadding(px(6), px(8), px(6), px(8))
        setOnClickListener { onClick() }
    }

    private fun selectSection(name: String) {
        actionBox.removeAllViews()
        for (a in sections()[name].orEmpty()) actionBox.addView(btn(a.label) { runAct(a) }, lpW(6))
        status.text = "$name: choose an action. Results appear below."
    }

    private fun runAct(a: Act) {
        val text = input.text.toString().trim()
        if (a.needs != null && text.isEmpty()) { status.text = "Type ${a.needs} in the box above, then press the button."; return }
        if (a.ui) { a.exec(text); return }
        status.text = "Working... (free public sources can take a few seconds)"
        Bg.run {
            val r = try { a.exec(text) } catch (e: Throwable) { msg(a.label, "Something went wrong: " + (e.message ?: e.javaClass.simpleName)) }
            runOnUiThread { if (r != null) show(r, a.save) }
        }
    }

    private fun show(r: Report, save: Boolean) {
        lastReport = r
        out.text = r.toText()
        status.text = "Done." + if (save) " Saved to history." else ""
        if (save) Bg.run { try { IntelDb.get(this).save(r) } catch (_: Throwable) { } }
    }

    private fun msg(title: String, text: String): Report = Report(title, "note", "").also { it.obs("Info", text) }

    // ---- pickers and dialogs (they run on the screen and call back when the user is done)
    private fun pickFile(then: (Uri) -> Unit) { pendingPick = then; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1) }
    private fun pickFolder(then: (Uri) -> Unit) { pendingTree = then; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 2) }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { status.text = "Cancelled."; return }
        when (requestCode) {
            1 -> pendingPick?.invoke(uri)
            2 -> pendingTree?.invoke(uri)
        }
    }

    private fun showSocialSetup() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), 0) }
        val id = EditText(this).apply { hint = "your account ID (numbers)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val tok = EditText(this).apply { hint = "access token"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(id); box.addView(tok)
        AlertDialog.Builder(this).setTitle("Your Instagram account")
            .setMessage("Only accounts you own or manage. Get the ID and an access token from Meta for Developers (free). The token is encrypted on this phone.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val m = SocialTools.setup(this, id.text.toString(), tok.text.toString())
                tok.text = null
                status.text = m
                Bg.run { val r = SocialTools.report(this); runOnUiThread { show(r, true) } }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showPasswordCheck() {
        val pw = EditText(this).apply { hint = "password to test"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Password exposure check")
            .setMessage("Only the first 5 characters of a hash are sent. The password is not stored.")
            .setView(pw)
            .setPositiveButton("Check") { _, _ ->
                val p = pw.text.toString(); pw.text = null
                status.text = "Checking..."
                Bg.run { val r = SecTools.passwordExposure(p); runOnUiThread { show(r, true) } }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Clear all investigation history?")
            .setMessage("This deletes every saved report and the event log on this phone.")
            .setPositiveButton("Clear") { _, _ -> IntelDb.get(this).clearAll(); status.text = "History cleared." }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- history and reports
    private fun historyList(): Report {
        val rep = Report("Investigation history", "history", "this phone")
        val rows = IntelDb.get(this).history(40)
        if (rows.isEmpty()) rep.na("History", "empty so far")
        for (r in rows) rep.obs("#${r.id}  ${Fmt.date(r.ts)}", "${r.kind}: ${r.target}")
        rep.summary = "Type a number in the box and press 'Open an investigation'."
        return rep
    }

    private fun exportDir(): File = File(getExternalFilesDir(null), "intel_reports").apply { mkdirs() }

    private fun exportLast(): Report {
        val r = lastReport ?: return msg("Export", "Run something first, then export it.")
        val f = File(exportDir(), "report_${System.currentTimeMillis()}.md")
        f.writeText(r.toMarkdown())
        return Report("Report exported", "export", f.name).also { it.ver("Saved to", f.absolutePath); it.obs("Open with", "any text or markdown viewer") }
    }

    private fun listExports(): Report {
        val rep = Report("Exported reports", "export", exportDir().absolutePath)
        val files = exportDir().listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) rep.na("Files", "none yet")
        for (f in files.take(30)) rep.obs(f.name, Fmt.size(f.length()) + "  " + Fmt.date(f.lastModified()))
        return rep
    }
}
