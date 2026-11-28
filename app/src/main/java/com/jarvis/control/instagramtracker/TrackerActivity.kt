package com.jarvis.control.instagramtracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.widget.Toast
import java.util.Date

private val TBG = Color.parseColor("#0A0F1A")
private val TPANEL = Color.parseColor("#0F1B2D")
private val TLINE = Color.parseColor("#1F3A5A")
private val TACC = Color.parseColor("#4FA8FF")
private val TTXT = Color.parseColor("#D6E6F7")
private val TDIM = Color.parseColor("#6F86A3")

/**
 * Instagram Observation Tracker screen. Everything here is typed or imported by the user.
 * Nothing is fetched from Instagram, nothing runs in the background, and nothing leaves the phone.
 */
class TrackerActivity : Activity() {
    private lateinit var store: TrackerDb
    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private lateinit var out: TextView
    private var profileId = -1L

    // comparison state (kept so the sort and search buttons can redraw it)
    private var cmpOlder: Snapshot? = null
    private var cmpNewer: Snapshot? = null
    private var cmpCategory = CAT_FOLLOWERS
    private var cmpWarnings: List<String> = emptyList()
    private var cmpQuery = ""
    private var cmpDescending = false
    private var cmpSearch: EditText? = null

    private val importCode = 77
    private val importShotsCode = 78

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(ts: Long) = if (ts <= 0L) "never" else TrackerLogic.formatDateTime(ts)
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = TrackerDb.get(this)
        window.statusBarColor = TBG
        val root = ScrollView(this).apply { setBackgroundColor(TBG) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(20), px(16), px(28)) }
        root.addView(col)
        setContentView(root)
        render()
    }

    private fun render() {
        cmpSearch = null
        col.removeAllViews()
        col.addView(text("INSTAGRAM OBSERVATION TRACKER", TACC, 18f, bold = false))
        col.addView(text("Your own notes and lists only  ·  nothing is fetched from Instagram  ·  stays on this phone", TDIM, 11f))
        if (profileId < 0) renderProfiles() else renderProfile()
    }

    // ------------------------------------------------------------------ profiles
    private fun renderProfiles() {
        add(btn("New profile") { newProfile() }, 14)
        add(btn("Back to Intelligence") { finish() }, 6)
        add(text("Profiles", TACC, 13f, bold = true), 16)
        val list = store.profiles()
        if (list.isEmpty()) add(text("No profiles yet. Create one for an account you own or have permission to record.", TDIM, 12f), 6)
        for (p in list) add(btn("${p.label}   ·   @${p.username}\nlast observation: ${fmt(p.lastObs)}") { profileId = p.id; render() }, 6)
        status = text("", TDIM, 12f); add(status, 12)
        out = text("", TTXT, 13f); add(out, 8)
    }

    private fun newProfile() {
        AlertDialog.Builder(this).setTitle("Before you start")
            .setMessage(TRACKER_PERMISSION)
            .setPositiveButton("I confirm") { _, _ -> profileForm(null) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun profileForm(existing: Profile?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), 0) }
        val label = field(box, "label, e.g. My Instagram", existing?.label.orEmpty())
        val user = field(box, "username or profile link", existing?.username.orEmpty())
        val notes = field(box, "notes (optional)", existing?.notes.orEmpty(), multi = true)
        AlertDialog.Builder(this).setTitle(if (existing == null) "New profile" else "Edit profile").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val ok = if (existing == null) store.createProfile(label.text.toString(), user.text.toString(), notes.text.toString(), System.currentTimeMillis(), true) >= 0
                    else store.updateProfile(existing.id, label.text.toString(), user.text.toString(), notes.text.toString())
                if (!ok) toast("Check the label (1-40 characters) and the username (letters, numbers, dots, underscores).")
                else { status.text = "Saved."; render() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ one profile
    private fun renderProfile() {
        val p = store.profile(profileId)
        if (p == null) { profileId = -1; render(); return }
        add(btn("← Profiles") { profileId = -1; render() }, 14)
        add(text(p.label, TACC, 18f, bold = true), 12)
        add(text("@${p.username}   ·   created ${fmt(p.created)}\nlast observation: ${fmt(p.lastObs)}" + if (p.notes.isNotBlank()) "\n${p.notes}" else "", TTXT, 12f), 6)
        add(text("Record what you observe", TACC, 13f, bold = true), 16)
        add(btn("Enter follower and following counts") { countForm(p) }, 6)
        add(btn("Paste a username list") { listForm(p, null, SRC_MANUAL) }, 6)
        add(btn("Import a TXT or CSV list") { pickFile() }, 6)
        add(btn("Import from screenshots (on this phone)") {
            startActivityForResult(Intent(this, ScreenshotImportActivity::class.java).putExtra(ScreenshotImportActivity.EXTRA_PROFILE, p.id), importShotsCode)
        }, 6)
        add(text("Compare", TACC, 13f, bold = true), 16)
        add(btn("Compare two list snapshots") { compareStart(p) }, 6)
        add(btn("Count history") { showCounts(p) }, 6)
        add(btn("Observation history") { showHistory(p) }, 6)
        add(text("Profile", TACC, 13f, bold = true), 16)
        add(btn("Edit profile") { profileForm(p) }, 6)
        add(btn("Delete profile and all its observations") { confirmDelete(p) }, 6)
        status = text("", TDIM, 12f); add(status, 12)
        out = text("", TTXT, 13f); add(out, 8)
    }

    private fun confirmDelete(p: Profile) {
        AlertDialog.Builder(this).setTitle("Delete '${p.label}'?")
            .setMessage("Deletes this profile and every saved count and list for it. Nothing else is touched.")
            .setPositiveButton("Delete") { _, _ -> store.deleteProfile(p.id); profileId = -1; render() }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ counts
    private fun countForm(p: Profile) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), 0) }
        val followers = field(box, "follower count (whole number)", "", number = true)
        val following = field(box, "following count (whole number)", "", number = true)
        val when_ = field(box, "observed at: yyyy-MM-dd HH:mm", TrackerLogic.formatDateTime(System.currentTimeMillis()))
        val notes = field(box, "notes (optional)", "", multi = true)
        AlertDialog.Builder(this).setTitle("Counts observed by you").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val f = followers.text.toString().trim()
                val g = following.text.toString().trim()
                val fv = if (f.isEmpty()) null else TrackerLogic.parseCount(f)
                val gv = if (g.isEmpty()) null else TrackerLogic.parseCount(g)
                val ts = TrackerLogic.parseDateTime(when_.text.toString())
                when {
                    f.isNotEmpty() && fv == null -> toast("Follower count must be a whole number, 0 or more.")
                    g.isNotEmpty() && gv == null -> toast("Following count must be a whole number, 0 or more.")
                    fv == null && gv == null -> toast("Enter at least one count.")
                    ts == null -> toast("The date must look like 2026-11-28 09:30.")
                    else -> {
                        val r = store.addSnapshot(Snapshot(0, p.id, ts, KIND_COUNTS, "", fv, gv, emptyList(), SRC_MANUAL, notes.text.toString().trim()))
                        status.text = when (r) {
                            -2L -> "Not saved twice: the same observation was just recorded."
                            -1L -> "Could not save: the profile is missing."
                            else -> "Saved. " + TrackerLogic.countWarnings(fv, gv).joinToString("; ").ifEmpty { "Both counts recorded." }
                        }
                        render()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showCounts(p: Profile) {
        hideCompareSearch()
        val counts = store.snapshots(p.id).filter { it.kind == KIND_COUNTS }.sortedBy { it.ts }
        val sb = StringBuilder()
        if (counts.isEmpty()) sb.append("No counts recorded yet.")
        for (i in counts.indices) {
            val c = counts[i]
            sb.append(fmt(c.ts)).append(":  followers ").append(c.followers ?: "not entered")
                .append("   following ").append(c.following ?: "not entered").append('\n')
            if (i > 0) {
                val prev = counts[i - 1]
                val df = TrackerLogic.countDiff(prev.followers, c.followers)
                val dg = TrackerLogic.countDiff(prev.following, c.following)
                sb.append("   difference since ").append(fmt(prev.ts)).append(":  followers ").append(df.signed())
                    .append("   following ").append(dg.signed()).append('\n')
            }
            if (c.notes.isNotBlank()) sb.append("   note: ").append(c.notes).append('\n')
            sb.append('\n')
        }
        sb.append("Differences are between your own observations. They do not prove when or why any account activity happened.")
        out.text = sb.toString()
        status.text = "Count history."
    }

    private fun Int?.signed(): String = when {
        this == null -> "not available"
        this > 0 -> "+$this"
        else -> "$this"
    }

    // ------------------------------------------------------------------ username lists
    private fun pickCategory(then: (String) -> Unit) {
        AlertDialog.Builder(this).setTitle("Which list?")
            .setItems(arrayOf("Followers", "Following")) { _, which -> then(if (which == 0) CAT_FOLLOWERS else CAT_FOLLOWING) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun listForm(p: Profile, preset: String?, source: String) {
        pickCategory { category ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), 0) }
            val names = field(box, "one username per line (@ is optional)", preset.orEmpty(), multi = true)
            names.minLines = 6
            val when_ = field(box, "observed at: yyyy-MM-dd HH:mm", TrackerLogic.formatDateTime(System.currentTimeMillis()))
            val notes = field(box, "notes (optional)", "", multi = true)
            AlertDialog.Builder(this).setTitle("$category list").setView(box)
                .setPositiveButton("Save list") { _, _ ->
                    val parsed = TrackerLogic.parseUsernames(names.text.toString())
                    val ts = TrackerLogic.parseDateTime(when_.text.toString())
                    when {
                        ts == null -> toast("The date must look like 2026-11-28 09:30.")
                        parsed.names.isEmpty() -> toast("No valid usernames found. Nothing was saved.")
                        TrackerLogic.listTooLarge(parsed.names.size) -> toast("List has more than ${TrackerLogic.MAX_LIST_NAMES} usernames. Split it into smaller lists.")
                        else -> {
                            val r = store.addSnapshot(Snapshot(0, p.id, ts, KIND_LIST, category, null, null, parsed.names, source, notes.text.toString().trim()))
                            val warnings = TrackerLogic.listWarnings(parsed.names.size, parsed.invalid.size, parsed.duplicates, null, source)
                            status.text = when (r) {
                                -2L -> "Not saved twice: the same list was just recorded."
                                -1L -> "Could not save: the profile is missing."
                                else -> "Saved ${parsed.names.size} usernames. " + warnings.joinToString("; ")
                            }
                            if (parsed.invalid.isNotEmpty()) out.text = "Lines left out (not valid usernames):\n" + parsed.invalid.joinToString("\n")
                            render()
                        }
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*")
        startActivityForResult(i, importCode)
    }

    @Deprecated("Activity result API kept for this simple file pick")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == importShotsCode) {
            if (resultCode == RESULT_OK) { render(); status.text = "Screenshot list saved." }
            return
        }
        if (requestCode != importCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val p = store.profile(profileId) ?: return
        val limit = TrackerLogic.MAX_IMPORT_BYTES
        val text = try {
            contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(limit + 1)
                var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                if (n > limit) null else String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (_: Exception) { null }
        if (text == null) { toast("File is unreadable or larger than 1 MB. Nothing was saved."); return }
        if (text.isBlank()) { toast("Could not read that file."); return }
        val parsed = TrackerLogic.parseUsernames(text)
        if (parsed.names.isEmpty()) { toast("No valid usernames in that file. Nothing was saved."); return }
        if (TrackerLogic.listTooLarge(parsed.names.size)) { toast("List has more than ${TrackerLogic.MAX_LIST_NAMES} usernames. Split it into smaller lists."); return }
        val preview = "Found ${parsed.names.size} usernames" + (if (parsed.invalid.isNotEmpty()) ", ${parsed.invalid.size} invalid lines skipped" else "") + "."
        pickCategory { category ->
            val when_ = TrackerLogic.formatDateTime(System.currentTimeMillis())
            val r = store.addSnapshot(Snapshot(0, p.id, System.currentTimeMillis(), KIND_LIST, category, null, null, parsed.names, SRC_FILE, "imported from file"))
            status.text = when (r) {
                -2L -> "Not saved twice: the same list was just imported."
                -1L -> "Could not save: the profile is missing."
                else -> "$preview Saved as $category at $when_. " + TrackerLogic.listWarnings(parsed.names.size, parsed.invalid.size, parsed.duplicates, null, SRC_FILE).joinToString("; ")
            }
            render()
        }
    }

    // ------------------------------------------------------------------ history
    private fun showHistory(p: Profile) {
        hideCompareSearch()
        val snaps = store.snapshots(p.id)
        if (snaps.isEmpty()) { out.text = "No observations saved yet."; return }
        out.text = ""
        for (s in snaps) {
            val label = if (s.kind == KIND_COUNTS) "${fmt(s.ts)}  counts: followers ${s.followers ?: "-"}, following ${s.following ?: "-"}"
                else "${fmt(s.ts)}  ${s.listCategory} list: ${s.usernames.size} usernames (${s.source})"
            add(btn(label + if (s.notes.isNotBlank()) "\nnote: ${s.notes}" else "") { snapshotOptions(p, s) }, 6)
        }
    }

    private fun snapshotOptions(p: Profile, s: Snapshot) {
        AlertDialog.Builder(this).setTitle(fmt(s.ts))
            .setItems(arrayOf("Rename note", "Delete this observation", "Close")) { _, which ->
                when (which) {
                    0 -> {
                        val et = EditText(this).apply { setText(s.notes); setSingleLine(false) }
                        AlertDialog.Builder(this).setTitle("Note").setView(et)
                            .setPositiveButton("Save") { _, _ -> store.renameSnapshotNotes(p.id, s.id, et.text.toString()); render() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    1 -> AlertDialog.Builder(this).setTitle("Delete this observation?")
                        .setPositiveButton("Delete") { _, _ -> store.deleteSnapshot(p.id, s.id); render() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    // ------------------------------------------------------------------ comparison
    private fun compareStart(p: Profile) {
        pickCategory { category ->
            val lists = store.snapshots(p.id).filter { it.kind == KIND_LIST && it.listCategory == category }.sortedBy { it.ts }
            if (lists.size < 2) { status.text = "Need two $category lists saved first. Paste or import another list."; return@pickCategory }
            val labels = lists.map { "${fmt(it.ts)}  (${it.usernames.size} usernames, ${it.source})" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Older list").setItems(labels) { _, oi ->
                val older = lists[oi]
                val newerChoices = lists.filter { it.ts > older.ts }
                if (newerChoices.isEmpty()) { status.text = "Pick an older list that has a newer one after it."; return@setItems }
                AlertDialog.Builder(this).setTitle("Newer list")
                    .setItems(newerChoices.map { "${fmt(it.ts)}  (${it.usernames.size} usernames, ${it.source})" }.toTypedArray()) { _, ni ->
                        startCompare(p, older, newerChoices[ni], category)
                    }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun startCompare(p: Profile, older: Snapshot, newer: Snapshot, category: String) {
        cmpOlder = older; cmpNewer = newer; cmpCategory = category
        cmpWarnings = buildList {
            for (s in listOf(older, newer)) {
                if (s.usernames.isEmpty()) add("${fmt(s.ts)} list has no usernames")
                if (s.source == SRC_FILE) add("${fmt(s.ts)} list came from a file: check it was complete")
            }
        }
        cmpQuery = ""; cmpDescending = false
        renderCompare(p)
    }

    private fun renderCompare(p: Profile) {
        val older = cmpOlder ?: return
        val newer = cmpNewer ?: return
        val full = TrackerLogic.compareLists(older.usernames, newer.usernames)
        val q = cmpQuery.trim().lowercase()
        fun pick(l: List<String>): List<String> {
            val f = if (q.isEmpty()) l else l.filter { it.lowercase().contains(q) }
            return if (cmpDescending) f.reversed() else f
        }
        val shown = ListDiff(pick(full.added), pick(full.removed), pick(full.unchanged))
        val report = TrackerLogic.comparisonReport(p.label, older, newer, shown, cmpWarnings, System.currentTimeMillis(), cmpCategory)
        out.text = (if (q.isEmpty()) "" else "Search \"$q\": counts below are only for matching names.\n\n") + report
        status.text = "Comparison of ${cmpCategory} lists."
        // search and sort controls sit above the report
        val existing = cmpSearch
        if (existing == null) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val et = EditText(this).apply { hint = "search usernames"; setHintTextColor(TDIM); setTextColor(TTXT); setSingleLine(true); setBackgroundColor(TPANEL); setPadding(px(10), px(10), px(10), px(10)) }
            cmpSearch = et
            box.addView(et)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn("Apply search") { cmpQuery = et.text.toString(); renderCompare(p) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Sort Z-A / A-Z") { cmpDescending = !cmpDescending; renderCompare(p) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
            col.addView(box, col.indexOfChild(out))
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun hideCompareSearch() {
        val box = cmpSearch?.parent as? View ?: return
        col.removeView(box)
        cmpSearch = null
        cmpQuery = ""
    }

    private fun field(box: LinearLayout, hint: String, value: String, number: Boolean = false, multi: Boolean = false): EditText {
        val et = EditText(this).apply {
            this.hint = hint; setText(value)
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        box.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        return et
    }

    private fun add(v: View, topDp: Int = 0) {
        col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(topDp) })
    }

    private fun text(s: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = s; setTextColor(color); textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(TTXT); textSize = 13f
        background = GradientDrawable().apply { setColor(TPANEL); setStroke(px(1), TLINE); cornerRadius = px(8).toFloat() }
        setOnClickListener { onClick() }
    }
}
