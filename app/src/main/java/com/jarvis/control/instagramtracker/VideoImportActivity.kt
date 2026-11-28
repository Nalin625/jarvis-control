package com.jarvis.control.instagramtracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screen-recording import. The user picks a recording they are allowed to use. Frames are sampled on this phone
 * (one per second, fewer for long videos), read with ML Kit one at a time, and reviewed before anything is saved.
 * Nothing is uploaded, no frame or video copy is kept, usernames are never logged, and cancelling saves nothing.
 */
class VideoImportActivity : Activity() {
    companion object {
        const val EXTRA_PROFILE = "profile_id"
        private const val REQ_PICK = 93
        private const val FRAME_WAIT_S = 30L
        private const val SHOWN_TIMES = 8
        private const val SHOWN_REJECTED = 20
    }

    private val vBg = Color.parseColor("#0A0F1A")
    private val vPanel = Color.parseColor("#0F1B2D")
    private val vLine = Color.parseColor("#1F3A5A")
    private val vAcc = Color.parseColor("#4FA8FF")
    private val vTxt = Color.parseColor("#D6E6F7")
    private val vDim = Color.parseColor("#6F86A3")
    private val vWarn = Color.parseColor("#FFB86B")

    private lateinit var col: LinearLayout
    private var profileId = -1L

    // selected recording (metadata only; the video itself is never copied)
    private var videoUri: Uri? = null
    private var videoName = ""
    private var durationMs = 0L
    private var sizeBytes = -1L
    private var frameDims: Pair<Int, Int>? = null
    private val times: List<Long> get() = TrackerVideo.timestampsMs(durationMs)

    // reading
    private var busy = false
    @Volatile private var cancelled = false
    @Volatile private var waiting: CountDownLatch? = null
    private var progressView: TextView? = null
    private var recognizer: TextRecognizer? = null

    // review
    private var reviewing = false
    private var frames: List<FrameText> = emptyList()
    private var entries: List<OcrEntry> = emptyList()
    private var rejected: List<String> = emptyList()
    private var repeats = 0
    private var emptyFrames: List<Long> = emptyList()
    private var unreadFrames = 0

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        profileId = intent.getLongExtra(EXTRA_PROFILE, -1L)
        window.statusBarColor = vBg
        val root = ScrollView(this).apply { setBackgroundColor(vBg) }
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
        progressView = null
        col.removeAllViews()
        add(text("IMPORT FROM VIDEO", vAcc, 18f, bold = true), 0)
        add(text("Screen recordings are read on this phone only. Frames are sampled, not every frame. Nothing is uploaded.", vDim, 11f), 2)
        if (reviewing) renderReview() else renderSelect()
    }

    private fun renderSelect() {
        if (videoUri == null) {
            add(text("1. Choose a screen recording you are allowed to use. MP4 works best. Up to 10 minutes and 300 MB.", vTxt, 13f), 12)
            add(btn("Choose video") { if (!busy) pick() }, 8)
        } else {
            add(text("1. Ready to read", vTxt, 13f), 12)
            add(text("$videoName\n${TrackerVideo.formatTime(durationMs)}  ·  ${sizeText()}  ·  ${times.size} frames, one every ${TrackerVideo.intervalMs(durationMs) / 1000} s", vTxt, 12f), 6)
            if (!busy) {
                add(btn("Read frames") { readFrames() }, 10)
                add(btn("Choose a different video") { videoUri = null; render() }, 6)
            }
        }
        if (busy) {
            val pv = text("Starting...", vDim, 12f)
            progressView = pv
            add(pv, 10)
            add(btn("Cancel reading") { cancelImport() }, 8)
        } else {
            add(btn("Cancel import") { cancelImport() }, 6)
        }
    }

    private fun renderReview() {
        add(text("2. Review. Approve only what you checked.", vTxt, 13f), 12)
        add(text(OCR_WARNING, vWarn, 12f), 8)
        add(text("Only sampled frames were read. A username that never appeared in a sampled frame is not in this list.", vWarn, 12f), 4)
        add(text("${entries.size} usernames from ${frames.size} frames. Repeats merged: $repeats. Frames with no text: ${emptyFrames.size}. Frames not read: $unreadFrames.", vTxt, 12f), 10)
        if (entries.isEmpty()) add(text("No usernames were found in these frames.", vDim, 12f), 10)
        for (idx in entries.indices) {
            val en = entries[idx]
            val where = if (en.shots.isEmpty()) "added by you" else "frames at " + TrackerVideo.timeLabels(en.shots, frames, SHOWN_TIMES)
            val status = if (en.uncertain) "CHECK: " + en.reasons.joinToString("; ") else "OK"
            add(text("@${en.name}\n$where  ·  $status", if (en.uncertain) vWarn else vTxt, 13f), 10)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn("Edit") { editEntry(idx) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Delete") { entries = TrackerOcr.withRemove(entries, idx); render() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            add(row, 4)
        }
        if (rejected.isNotEmpty()) {
            add(text("Left out (not usernames): ${rejected.size}", vDim, 12f), 12)
            for (r in rejected.take(SHOWN_REJECTED)) add(text("  $r", vDim, 11f), 2)
        }
        add(btn("Add username") { addEntry() }, 14)
        add(btn("Choose date, Followers or Following, and save") { approve() }.apply { isEnabled = entries.isNotEmpty() }, 8)
        add(btn("Back to video") { reviewing = false; render() }, 6)
        add(btn("Cancel import") { cancelImport() }, 6)
    }

    // ------------------------------------------------------------------ choosing a video
    private fun pick() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/3gpp", "video/webm")),
            REQ_PICK)
    }

    @Deprecated("Activity result API kept for this simple picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadVideo(uri)
    }

    /** Reads only the name, size and length. The video is not copied anywhere. */
    private fun loadVideo(uri: Uri) {
        var name = ""
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: ""
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) { }
        Thread {
            var dur = 0L
            var mime: String? = null
            var err: String? = null
            var w = 0
            var h = 0
            var rot = 0
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this@VideoImportActivity, uri)
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } catch (_: Exception) {
                err = "Could not open this video. The format may not be supported."
            } finally {
                try { r.release() } catch (_: Exception) { }
            }
            val dims = TrackerVideo.scaledSize(w, h, rot)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val problem = err ?: TrackerVideo.problem(dur, size, mime)
                if (problem != null) { toast(problem); return@runOnUiThread }
                videoUri = uri
                videoName = name
                durationMs = dur
                sizeBytes = size
                frameDims = dims
                render()
            }
        }.start()
    }

    // ------------------------------------------------------------------ reading frames
    private fun readFrames() {
        val uri = videoUri ?: return
        if (busy) return
        val ts = times
        if (ts.isEmpty()) { toast("This video has no readable frames."); return }
        busy = true
        cancelled = false
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer = rec
        val out = ArrayList<FrameText>()
        val dims = frameDims
        render()

        // One worker thread owns the retriever. Each frame is handed to ML Kit on the UI thread and the worker waits for its result,
        // so frames are processed strictly one after another and only one bitmap is alive at a time.
        Thread {
            val retriever = MediaMetadataRetriever()
            var opened = false
            try {
                retriever.setDataSource(this@VideoImportActivity, uri)
                opened = true
            } catch (_: Exception) { }
            try {
                if (opened) {
                    for ((i, t) in ts.withIndex()) {
                        if (cancelled) break
                        val bmp = extractFrame(retriever, t, dims)
                        val latch = CountDownLatch(1)
                        waiting = latch
                        runOnUiThread {
                            progressView?.text = "Reading frame ${i + 1} of ${ts.size} at ${TrackerVideo.formatTime(t)}"
                            if (cancelled || bmp == null) {
                                bmp?.recycle()
                                if (!cancelled) out.add(FrameText(t, null))
                                latch.countDown()
                                return@runOnUiThread
                            }
                            try {
                                rec.process(InputImage.fromBitmap(bmp, 0))
                                    .addOnSuccessListener { r -> bmp.recycle(); out.add(FrameText(t, r.text)); latch.countDown() }
                                    .addOnFailureListener { bmp.recycle(); out.add(FrameText(t, null)); latch.countDown() }
                            } catch (_: Exception) {
                                bmp.recycle()
                                out.add(FrameText(t, null))
                                latch.countDown()
                            }
                        }
                        latch.await(FRAME_WAIT_S, TimeUnit.SECONDS)
                        waiting = null
                    }
                }
            } finally {
                try { retriever.release() } catch (_: Exception) { }
                runOnUiThread { if (!cancelled) finishReading(out.sortedBy { it.timeMs }, ts.size, opened) }
            }
        }.start()
    }

    /**
     * One frame at the target size. On Android 8.1+ the decoder scales it directly, so a 4K frame is never fully decoded.
     * On Android 8.0 the full frame is decoded and scaled straight away. Any failure, including running out of memory,
     * returns null: that frame counts as not read and the import carries on.
     */
    private fun extractFrame(r: MediaMetadataRetriever, t: Long, dims: Pair<Int, Int>?): Bitmap? {
        val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return try {
            if (Build.VERSION.SDK_INT >= 27 && dims != null) r.getScaledFrameAtTime(t * 1000L, option, dims.first, dims.second)
            else r.getFrameAtTime(t * 1000L, option)?.let { scaleDown(it) }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Scales a full-size frame to the target size and releases the original. */
    private fun scaleDown(b: Bitmap): Bitmap {
        val (w, h) = TrackerVideo.scaledSize(b.width, b.height, 0) ?: return b
        if (w == b.width && h == b.height) return b
        val scaled = Bitmap.createScaledBitmap(b, w, h, true)
        b.recycle()
        return scaled
    }

    private fun finishReading(read: List<FrameText>, total: Int, opened: Boolean) {
        busy = false
        closeRecognizer()
        if (!opened) {
            toast("Could not read frames from this video.")
            render()
            return
        }
        frames = read
        val r = TrackerVideo.review(read)
        entries = r.entries
        rejected = r.rejected
        repeats = r.repeats
        emptyFrames = r.emptyFrames
        unreadFrames = r.unreadFrames + (total - read.size)
        reviewing = true
        render()
    }

    private fun closeRecognizer() {
        recognizer?.close()
        recognizer = null
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
        AlertDialog.Builder(this).setTitle("When was this recording made?").setMessage("Format: yyyy-MM-dd HH:mm").setView(et)
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
        val snap = Snapshot(0, profileId, ts, KIND_LIST, category, null, null, names, SRC_VIDEO_OCR,
            String.format(Locale.US, "video OCR, %d frames every %d s", frames.size, TrackerVideo.intervalMs(durationMs) / 1000))
        when (TrackerDb.get(this).addSnapshot(snap)) {
            -1L -> toast("Profile not found. Nothing was saved.")
            -2L -> toast("This list was just saved. Not saved twice.")
            else -> {
                clearState()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    /** Cancel at any stage: nothing is saved, and reading stops after the frame in progress. */
    private fun cancelImport() {
        cancelled = true
        waiting?.countDown()
        clearState()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun clearState() {
        busy = false
        closeRecognizer()
        videoUri = null
        frames = emptyList()
        entries = emptyList()
        rejected = emptyList()
        emptyFrames = emptyList()
        reviewing = false
    }

    override fun onDestroy() {
        cancelled = true
        waiting?.countDown()
        closeRecognizer()
        clearState()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers
    private fun sizeText() = if (sizeBytes < 0) "size unknown" else String.format(Locale.US, "%.1f MB", sizeBytes / 1048576.0)

    private fun add(v: View, topDp: Int = 0) {
        col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(topDp) })
    }

    private fun text(s: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = s; setTextColor(color); textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(vTxt); textSize = 13f
        background = GradientDrawable().apply { setColor(vPanel); setStroke(px(1), vLine); cornerRadius = px(8).toFloat() }
        setOnClickListener { onClick() }
    }
}
