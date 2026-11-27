package com.jarvis.control.intel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/** Image and document intelligence. Works only on files you pick; nothing is uploaded anywhere. */
object MediaTools {
    private fun nameSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var n = uri.lastPathSegment ?: "file"; var s = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { n = it.getString(0) ?: n; s = if (it.isNull(1)) -1 else it.getLong(1) }
            }
        } catch (_: Exception) { }
        return n to s
    }

    fun sha256(ctx: Context, uri: Uri): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { st -> val b = ByteArray(65536); while (true) { val n = st.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    /** One report for any file: hash and basics, then image or PDF specifics. */
    fun analyze(ctx: Context, uri: Uri): Report {
        val (name, size) = nameSize(ctx, uri)
        val mime = ctx.contentResolver.getType(uri) ?: ""
        val rep = Report("File analysis - $name", "file", name)
        rep.ver("Size", if (size >= 0) Fmt.size(size) else "unknown", "")
        rep.ver("Type", mime.ifBlank { "unknown" }, "")
        sha256(ctx, uri)?.let { rep.ver("SHA-256", it, "") } ?: rep.na("SHA-256", "file could not be read")
        when {
            mime.startsWith("image/") -> image(ctx, uri, rep)
            mime == "application/pdf" || name.endsWith(".pdf", true) -> pdf(ctx, uri, rep)
        }
        rep.summary = "Read locally from the file's own bytes. The hash lets you check later that the file has not changed."
        return rep
    }

    private fun image(ctx: Context, uri: Uri, rep: Report) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0) { rep.ver("Dimensions", "${o.outWidth} x ${o.outHeight} px (${"%.1f".format(o.outWidth * o.outHeight / 1_000_000.0)} MP)"); rep.ver("Decoded type", o.outMimeType ?: "?") }
        // EXIF
        try {
            ctx.contentResolver.openInputStream(uri)?.use { st ->
                val e = ExifInterface(st)
                fun t(tag: String, label: String) { e.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { rep.ver(label, it) } }
                t(ExifInterface.TAG_MAKE, "Camera make"); t(ExifInterface.TAG_MODEL, "Camera model"); t(ExifInterface.TAG_SOFTWARE, "Software")
                t(ExifInterface.TAG_DATETIME_ORIGINAL, "Taken"); t(ExifInterface.TAG_DATETIME, "Modified")
                t(ExifInterface.TAG_EXPOSURE_TIME, "Exposure time"); t(ExifInterface.TAG_F_NUMBER, "Aperture f/"); t(ExifInterface.TAG_FOCAL_LENGTH, "Focal length")
                t(ExifInterface.TAG_ISO_SPEED_RATINGS, "ISO"); t(ExifInterface.TAG_FLASH, "Flash"); t(ExifInterface.TAG_ORIENTATION, "Orientation")
                val ll = FloatArray(2)
                if (e.getLatLong(ll)) rep.ver("GPS location in file", "%.5f, %.5f  (this photo reveals where it was taken - remove location before sharing)".format(ll[0], ll[1]))
                else rep.obs("GPS location", "none stored in this file")
                if (e.getAttribute(ExifInterface.TAG_MAKE) == null && e.getAttribute(ExifInterface.TAG_DATETIME) == null) rep.na("EXIF", "no camera metadata (screenshots and edited/shared images usually have none)")
            }
        } catch (_: Exception) { rep.na("EXIF", "could not be read from this file type") }
        // pixel statistics on a small copy
        try {
            val o2 = BitmapFactory.Options().apply { inSampleSize = (maxOf(o.outWidth, o.outHeight) / 256).coerceAtLeast(1) }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            if (bmp != null) {
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                for (p in px) { r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
                val lum = (0.299 * r + 0.587 * g + 0.114 * b) / n
                rep.est("Average colour", "RGB(${r / n}, ${g / n}, ${b / n})"); rep.est("Brightness", "${lum.toInt()} / 255 " + when { lum < 60 -> "(dark)"; lum > 200 -> "(very bright)"; else -> "(normal)" })
                rep.est("Sharpness", sharpness(bmp))
                bmp.recycle()
            }
        } catch (_: Exception) { }
    }

    private fun sharpness(bmp: Bitmap): String {
        val w = bmp.width; val h = bmp.height
        if (w < 8 || h < 8) return "image too small to judge"
        val gray = IntArray(w * h); val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) gray[i] = (((px[i] shr 16) and 255) * 3 + ((px[i] shr 8) and 255) * 6 + (px[i] and 255)) / 10
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val l = 4 * gray[y * w + x] - gray[y * w + x - 1] - gray[y * w + x + 1] - gray[(y - 1) * w + x] - gray[(y + 1) * w + x]
            sum += l; sq += l.toDouble() * l; n++
        }
        val v = sq / n - (sum / n) * (sum / n)
        return "${v.toInt()} " + when { v < 40 -> "(blurry)"; v < 150 -> "(soft)"; else -> "(sharp)" }
    }

    // ---- OCR (ML Kit text recognition that the app already contains; runs on the phone, free)
    fun ocr(ctx: Context, uri: Uri, done: (Report) -> Unit) {
        val (name, _) = nameSize(ctx, uri)
        val rep = Report("Text in image (OCR) - $name", "ocr", name)
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            val o2 = BitmapFactory.Options().apply { inSampleSize = (maxOf(o.outWidth, o.outHeight) / 2000).coerceAtLeast(1) }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            if (bmp == null) { rep.na("Image", "could not be opened"); done(rep); return }
            IntelOcr.read(bmp, { text ->
                if (text.isBlank()) rep.obs("Text", "no readable text found") else { rep.obs("Characters read", text.length.toString()); rep.obs("Text", text.take(3000)) }
                rep.summary = "Recognised on this phone (Latin script). Handwriting and unusual fonts may be misread - check against the picture."
                done(rep)
            }, { err -> rep.na("OCR", err); done(rep) })
        } catch (e: Throwable) { rep.na("OCR", (e.message ?: "failed").take(100)); done(rep) }
    }

    // ---- PDF metadata (reads the document's own info; no PDF library needed)
    private fun pdf(ctx: Context, uri: Uri, rep: Report) {
        val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, 6_000_000) } } catch (_: Exception) { null }
        if (bytes == null) { rep.na("PDF", "could not be read"); return }
        val s = String(bytes, Charsets.ISO_8859_1)
        rep.ver("PDF version", Regex("%PDF-(\\d\\.\\d)").find(s)?.groupValues?.get(1) ?: "unknown")
        var found = 0
        for ((k, label) in listOf("Title" to "Title", "Author" to "Author", "Subject" to "Subject", "Creator" to "Created with", "Producer" to "PDF producer", "CreationDate" to "Created", "ModDate" to "Modified")) {
            val m = Regex("/$k\\s*\\(((?:\\\\.|[^\\\\)])*)\\)").findAll(s).lastOrNull() ?: continue
            var v = m.groupValues[1].replace("\\(", "(").replace("\\)", ")").trim()
            if (k.endsWith("Date")) v = v.removePrefix("D:").let { d -> if (d.length >= 14) "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)} ${d.substring(8, 10)}:${d.substring(10, 12)}:${d.substring(12, 14)}" else d }
            if (v.isNotBlank()) { rep.ver(label, v.take(150)); found++ }
        }
        if (found == 0) rep.na("Document info", "none found (the file may store it in compressed form, which this simple reader does not open)")
        rep.est("Pages", Regex("/Type\\s*/Page(?![s\\w])").findAll(s).count().let { if (it > 0) "$it (counted from the file)" else "could not be counted" })
        rep.obs("Encrypted", if (s.contains("/Encrypt")) "yes" else "no")
        rep.obs("Contains XMP metadata", if (s.contains("<x:xmpmeta")) "yes" else "no")
        val risks = ArrayList<String>()
        if (Regex("/JavaScript|/JS\\b").containsMatchIn(s)) risks.add("JavaScript")
        if (s.contains("/OpenAction") || s.contains("/AA")) risks.add("runs something on open")
        if (s.contains("/Launch")) risks.add("can launch programs")
        if (s.contains("/EmbeddedFile")) risks.add("embedded files")
        rep.est("Active content", if (risks.isEmpty()) "none detected" else "detected: " + risks.joinToString() + " - open only if you trust the sender")
    }

    private fun readCapped(st: InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val b = ByteArray(65536); var t = 0
        while (t < cap) { val n = st.read(b); if (n < 0) break; out.write(b, 0, n); t += n }
        return out.toByteArray()
    }

    // ---- duplicates: exact copies (SHA-256) and look-alikes (perceptual hash), inside a folder you choose
    class Img(val uri: Uri, val name: String, val size: Long, var sha: String = "", var dh: Long = 0)

    fun listImages(ctx: Context, tree: Uri, cap: Int = 400): List<Img> {
        val out = ArrayList<Img>()
        fun walk(docId: String, depth: Int) {
            if (out.size >= cap || depth > 3) return
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            try {
                ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < cap) {
                        val id = c.getString(0); val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, depth + 1)
                        else if (mime.startsWith("image/")) out.add(Img(DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getString(1) ?: id, if (c.isNull(3)) 0 else c.getLong(3)))
                    }
                }
            } catch (_: Exception) { }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    private fun dHash(ctx: Context, uri: Uri): Long {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return 0
        val o2 = BitmapFactory.Options().apply { inSampleSize = (maxOf(o.outWidth, o.outHeight) / 128).coerceAtLeast(1) }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return 0
        val sm = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val a = sm.getPixel(x, y); val b = sm.getPixel(x + 1, y)
            val la = ((a shr 16) and 255) * 3 + ((a shr 8) and 255) * 6 + (a and 255); val lb = ((b shr 16) and 255) * 3 + ((b shr 8) and 255) * 6 + (b and 255)
            h = (h shl 1) or (if (la > lb) 1L else 0L)
        }
        if (sm !== bmp) sm.recycle(); bmp.recycle()
        return h
    }

    fun duplicates(ctx: Context, tree: Uri, progress: (Int) -> Unit = {}): Report {
        val rep = Report("Duplicate images", "duplicates", tree.lastPathSegment ?: "folder")
        val imgs = listImages(ctx, tree)
        if (imgs.isEmpty()) { rep.na("Images", "no images found in that folder"); return rep }
        imgs.forEachIndexed { i, im -> im.sha = sha256(ctx, im.uri) ?: ""; im.dh = dHash(ctx, im.uri); if (i % 10 == 0) progress((i + 1) * 100 / imgs.size) }
        rep.obs("Images scanned", imgs.size.toString() + if (imgs.size >= 400) " (limit reached)" else "")
        val exact = imgs.filter { it.sha.isNotBlank() }.groupBy { it.sha }.values.filter { it.size > 1 }
        rep.ver("Exact duplicate groups", exact.size.toString())
        for (g in exact) rep.ver("Identical (${g.size} copies, ${Fmt.size(g[0].size)} each)", g.joinToString("\n") { it.name })
        val used = exact.flatten().toHashSet()
        val rest = imgs.filter { it !in used && it.dh != 0L }
        val seen = HashSet<Img>(); var sim = 0
        for (i in rest.indices) {
            if (rest[i] in seen) continue
            val grp = arrayListOf(rest[i])
            for (j in i + 1 until rest.size) if (rest[j] !in seen && java.lang.Long.bitCount(rest[i].dh xor rest[j].dh) <= 5) grp.add(rest[j])
            if (grp.size > 1) { seen.addAll(grp); sim++; rep.est("Look-alike group $sim", grp.joinToString("\n") { it.name }) }
        }
        if (sim == 0) rep.obs("Look-alikes", "none found")
        rep.summary = "Identical = same SHA-256. Look-alike = very similar picture (resized, re-saved), an estimate - check before deleting. Nothing was deleted."
        return rep
    }

    // ---- file integrity monitoring
    class Watched(val uri: String, val name: String, val sha: String, val size: Long)

    private fun load(ctx: Context): MutableList<Watched> {
        val out = ArrayList<Watched>()
        try { val a = JSONArray(IntelDb.get(ctx).get("integrity", "[]")); for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Watched(o.getString("u"), o.getString("n"), o.getString("h"), o.optLong("s"))) } } catch (_: Exception) { }
        return out
    }
    private fun save(ctx: Context, l: List<Watched>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name).put("h", it.sha).put("s", it.size)) }
        IntelDb.get(ctx).put("integrity", a.toString())
    }

    fun integrityAdd(ctx: Context, uri: Uri): String {
        try { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val (n, s) = nameSize(ctx, uri)
        val h = sha256(ctx, uri) ?: return "Could not read that file."
        val l = load(ctx); if (l.size >= 30) return "Limit of 30 watched files reached."
        l.removeAll { it.uri == uri.toString() }; l.add(Watched(uri.toString(), n, h, s)); save(ctx, l)
        return "Watching $n (SHA-256 ${h.take(12)}…)."
    }
    fun integrityRemoveAll(ctx: Context) = save(ctx, emptyList())

    fun integrityCheck(ctx: Context, notify: Boolean = false): Report {
        val rep = Report("File integrity check", "integrity", "watched files")
        val l = load(ctx)
        if (l.isEmpty()) { rep.na("Watched files", "none yet - add a file first"); return rep }
        var changed = 0
        for (w in l) {
            val h = sha256(ctx, Uri.parse(w.uri))
            when {
                h == null -> { rep.obs(w.name, "MISSING or unreadable now", ""); changed++; IntelDb.get(ctx).event("warn", "Watched file missing: ${w.name}") }
                h != w.sha -> { rep.obs(w.name, "CHANGED (was ${w.sha.take(10)}…, now ${h.take(10)}…)", ""); changed++; IntelDb.get(ctx).event("warn", "Watched file changed: ${w.name}") }
                else -> rep.ver(w.name, "unchanged (SHA-256 ${h.take(12)}…)", "")
            }
        }
        rep.summary = if (changed == 0) "All ${l.size} files match their saved hashes." else "$changed of ${l.size} files differ from the saved baseline. A change can be normal if you edited the file."
        return rep
    }
    fun watchedCount(ctx: Context) = load(ctx).size
}
