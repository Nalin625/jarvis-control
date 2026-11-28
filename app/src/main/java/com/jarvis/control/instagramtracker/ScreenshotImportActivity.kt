package com.jarvis.control.instagramtracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Screenshot OCR import. Only screenshots the user picks are read, and only on this phone (ML Kit, bundled model).
 * Nothing is uploaded, no copies are kept, usernames are never logged, and nothing is saved before the user approves.
 */
class ScreenshotImportActivity : Activity() {
    companion object {
        const val EXTRA_PROFILE = "profile_id"
        private const val REQ_PICK = 91
        private const val MAX_SIDE = 2048
        private const val THUMB_SIDE = 256
        private const val MAX_SHOWN_REJECTED = 20
    }

    private val sBg = Color.parseColor("#0A0F1A")
    private val sPanel = Color.parseColor("#0F1B2D")
    private val sLine = Color.parseColor("#1F3A5A")
    private val sAcc = Color.parseColor("#4FA8FF")
    private val sTxt = Color.parseColor("#D6E6F7")
    private val sDim = Color.parseColor("#6F86A3")
    private val sWarn = Color.parseColor("#FFB86B")

    private lateinit var col: LinearLayout
    private var profileId = -1L

    // selection
    private val shots = ArrayList<Uri>()
    private val thumbs = ArrayList<Bitmap?>()

    // processing and review
    private var busy = false
    private var cancelled = false
    private var reviewing = false
    private var recognizer: TextRecognizer? = null
    private var entries: List<OcrEntry> = emptyList()
    private var rejected: List<String> = emptyList()
    private var emptyShots: List<Int> = emptyList()
    private var failed: List<String> = emptyList()
    private var merged = 0

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        profileId = intent.getLongExtra(EXTRA_PROFILE, -1L)
        window.statusBarColor = sBg
        val root = ScrollView(this).apply { setBackgroundColor(sBg) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(20), px(16), px(28)) }
        root.addView(col)
        setContentView(root)
        if (profileId < 0 || TrackerDb.get(this).profile(profileId) == null) {
            toast("Profile not found.")
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        render()
    }

    private fun render() {
        col.removeAllViews()
        add(text("IMPORT FROM SCREENSHOTS", sAcc, 18f, bold = true), 0)
        add(text("Screenshots are read on this phone only. Nothing is uploaded or kept.", sDim, 11f), 2)
        if (reviewing) renderReview() else renderSelect()
    }

    private fun renderSelect() {
        add(text("1. Choose screenshots you are allowed to use. You can add more before reading.", sTxt, 13f), 12)
        add(btn("Add screenshots (${shots.size}/$OCR_MAX_SHOTS)") { if (!busy) pick() }, 8)
        if (shots.isNotEmpty()) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in shots.indices) {
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, px(8), 0) }
                cell.addView(ImageView(this).apply { setImageBitmap(thumbs[i]); scaleType = ImageView.ScaleType.CENTER_CROP },
                    LinearLayout.LayoutParams(px(96), px(140)))
                cell.addView(btn("Remove") { removeShot(i) }, LinearLayout.LayoutParams(px(96), LinearLayout.LayoutParams.WRAP_CONTENT))
                strip.addView(cell)
            }
            add(HorizontalScrollView(this).apply { addView(strip) }, 10)
        }
        add(btn("Read usernames from ${shots.size} screenshot(s)") { processAll() }
            .apply { isEnabled = shots.isNotEmpty() && !busy }, 12)
        add(btn("Cancel") { cancelImport() }, 6)
        if (busy) add(text("Reading screenshots on this phone...", sDim, 12f), 8)
    }

    private fun renderReview() {
        add(text("2. Review. Fix or remove anything wrong before saving.", sTxt, 13f), 12)
        add(text(OCR_WARNING, sWarn, 12f), 8)
        add(text(OCR_COMPLETENESS, sWarn, 12f), 4)
        add(text("${entries.size} usernames from ${shots.size} screenshot(s). Repeated names merged: $merged.", sTxt, 12f), 10)
        if (emptyShots.isNotEmpty()) add(text("No text found in screenshot(s): ${emptyShots.joinToString(", ")}", sWarn, 12f), 6)
        for (f in failed) add(text(f, sWarn, 12f), 4)
        if (entries.isEmpty()) add(text("No usernames were found in these screenshots.", sDim, 12f), 10)
        for (idx in entries.indices) {
            val en = entries[idx]
            val where = if (en.shots.isEmpty()) "added by you" else "from screenshot " + en.shots.joinToString(", ")
            val status = if (en.uncertain) "CHECK: " + en.reasons.joinToString("; ") else "OK"
            add(text("@${en.name}\n$where  ·  $status", if (en.uncertain) sWarn else sTxt, 13f), 10)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn("Edit") { editEntry(idx) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Delete") { entries = TrackerOcr.withRemove(entries, idx); render() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            add(row, 4)
        }
        if (rejected.isNotEmpty()) {
            add(text("Left out (not usernames): ${rejected.size}", sDim, 12f), 12)
            for (r in rejected.take(MAX_SHOWN_REJECTED)) add(text("  $r", sDim, 11f), 2)
        }
        add(btn("Add username") { addEntry() }, 14)
        add(btn("Choose Followers or Following and save") { approve() }.apply { isEnabled = entries.isNotEmpty() }, 8)
        add(btn("Back to screenshots") { reviewing = false; render() }, 6)
        add(btn("Cancel import") { cancelImport() }, 6)
    }

    // ------------------------------------------------------------------ selection
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Activity result API kept for this simple picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        var skipped = 0
        for (u in uris) {
            if (shots.contains(u)) continue
            if (shots.size >= OCR_MAX_SHOTS) { skipped++; continue }
            shots.add(u)
            thumbs.add(decode(u, THUMB_SIDE))
        }
        render()
        if (skipped > 0) toast("Up to $OCR_MAX_SHOTS screenshots at a time. $skipped skipped.")
    }

    private fun removeShot(i: Int) {
        if (busy || i !in shots.indices) return
        thumbs.removeAt(i)?.recycle()
        shots.removeAt(i)
        render()
    }

    /** Decodes a picked image, scaled so its longest side is at most [maxSide]. Null when it cannot be opened. */
    private fun decode(uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) { null }
    }

    // ------------------------------------------------------------------ reading
    private fun processAll() {
        if (busy || shots.isEmpty()) return
        busy = true
        render()
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer = rec
        val uris = shots.toList()
        val parsed = ArrayList<OcrCandidate>()
        val rejectedAll = ArrayList<String>()
        val emptyList = ArrayList<Int>()
        val fails = ArrayList<String>()

        fun done() {
            if (cancelled) return
            busy = false
            closeRecognizer()
            recognizer = null
            val cands = parsed.toList()
            entries = TrackerOcr.aggregate(cands)
            merged = TrackerOcr.repeats(cands, entries)
            rejected = rejectedAll.toList()
            emptyShots = emptyList.toList()
            failed = fails.toList()
            reviewing = true
            render()
        }

        fun step(i: Int) {
            if (cancelled) { closeRecognizer(); return }
            if (i >= uris.size) { done(); return }
            val shotNo = i + 1
            Thread {
                val bmp = decode(uris[i], MAX_SIDE)
                runOnUiThread {
                    if (bmp == null) {
                        fails.add("Screenshot $shotNo: could not open this image")
                        step(i + 1)
                        return@runOnUiThread
                    }
                    try {
                        rec.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener { r ->
                                bmp.recycle()
                                val p = TrackerOcr.parse(r.text, shotNo)
                                parsed.addAll(p.candidates)
                                rejectedAll.addAll(p.rejected)
                                if (r.text.isBlank()) emptyList.add(shotNo)
                                step(i + 1)
                            }
                            .addOnFailureListener { e ->
                                bmp.recycle()
                                fails.add("Screenshot $shotNo: ${(e.message ?: "text recognition failed").take(80)}")
                                step(i + 1)
                            }
                    } catch (e: Exception) {
                        bmp.recycle()
                        fails.add("Screenshot $shotNo: ${(e.message ?: "text recognition unavailable").take(80)}")
                        step(i + 1)
                    }
                }
            }.start()
        }
        step(0)
    }

    // ------------------------------------------------------------------ review edits
    private fun editEntry(i: Int) = nameDialog("Edit username", entries[i].name) { raw -> TrackerOcr.withEdit(entries, i, raw)?.also { entries = it } }

    private fun addEntry() = nameDialog("Add username", "") { raw -> TrackerOcr.withEdit(entries, -1, raw)?.also { entries = it } }

    private fun nameDialog(title: String, start: String, change: (String) -> List<OcrEntry>?) {
        val et = EditText(this).apply { setText(start); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("Save") { _, _ ->
                if (change(et.text.toString()) == null) toast("Not a valid username. Use letters, numbers, dots and underscores.")
                else render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ approve and save
    private fun approve() {
        if (entries.isEmpty()) { toast("No usernames to save."); return }
        val uncertain = entries.count { it.uncertain }
        if (uncertain == 0) askDate()
        else AlertDialog.Builder(this).setTitle("Some entries need checking")
            .setMessage("$uncertain entr${if (uncertain == 1) "y is" else "ies are"} marked CHECK. Save them as they are?")
            .setPositiveButton("Save anyway") { _, _ -> askDate() }
            .setNegativeButton("Go back", null).show()
    }

    private fun askDate() {
        val et = EditText(this).apply { setText(TrackerLogic.formatDateTime(System.currentTimeMillis())); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("When were these observed?").setMessage("Format: yyyy-MM-dd HH:mm").setView(et)
            .setPositiveButton("Next") { _, _ ->
                val ts = TrackerLogic.parseDateTime(et.text.toString())
                if (ts == null) toast("The date must look like 2026-11-28 09:30.") else chooseCategory(ts)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseCategory(ts: Long) {
        AlertDialog.Builder(this).setTitle("Which list?")
            .setItems(arrayOf("Followers", "Following")) { _, w -> save(ts, if (w == 0) CAT_FOLLOWERS else CAT_FOLLOWING) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun save(ts: Long, category: String) {
        val names = entries.map { it.name }
        if (TrackerLogic.listTooLarge(names.size)) { toast("List has more than ${TrackerLogic.MAX_LIST_NAMES} usernames."); return }
        val snap = Snapshot(0, profileId, ts, KIND_LIST, category, null, null, names, SRC_OCR, "screenshot OCR, ${shots.size} screenshot(s)")
        when (TrackerDb.get(this).addSnapshot(snap)) {
            -1L -> toast("Profile not found. Nothing was saved.")
            -2L -> toast("This list was just saved. Not saved twice.")
            else -> {
                clearAll()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun cancelImport() {
        clearAll()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun clearAll() {
        for (b in thumbs) b?.recycle()
        thumbs.clear()
        shots.clear()
        entries = emptyList()
        rejected = emptyList()
        emptyShots = emptyList()
        failed = emptyList()
        reviewing = false
    }

    override fun onDestroy() {
        closeRecognizer()
        recognizer = null
        clearAll()
        super.onDestroy()
    }

    private fun closeRecognizer() {
        recognizer?.close()
        recognizer = null
    }

    // ------------------------------------------------------------------ helpers
    private fun add(v: View, topDp: Int = 0) {
        col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(topDp) })
    }

    private fun text(s: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = s; setTextColor(color); textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(sTxt); textSize = 13f
        background = GradientDrawable().apply { setColor(sPanel); setStroke(px(1), sLine); cornerRadius = px(8).toFloat() }
        setOnClickListener { onClick() }
    }
}
