package com.jarvis.control.instagramtracker

/*
 * Screenshot OCR import: pure logic. The screen (ScreenshotImportActivity) feeds in text that ML Kit already read on
 * this phone. Nothing here stores, uploads or logs usernames. Uncertain readings are flagged, never silently accepted.
 */
const val SRC_OCR = "screenshot_ocr"
const val OCR_WARNING = "OCR results may contain mistakes or omissions. Review the extracted usernames before saving."
const val OCR_COMPLETENESS = "Screenshots may not show the complete list. Only usernames visible in the selected screenshots are included."
const val OCR_MAX_SHOTS = 20

data class OcrCandidate(val name: String, val shot: Int, val reasons: List<String>)

/** [shots] are 1-based screenshot numbers; empty when the user added the name by hand. */
data class OcrEntry(val name: String, val shots: List<Int>, val reasons: List<String>) {
    val uncertain: Boolean get() = reasons.isNotEmpty()
}

data class OcrParse(val candidates: List<OcrCandidate>, val rejected: List<String>)

object TrackerOcr {
    /** Instagram screen labels that are not usernames. They are skipped. */
    private val uiWords = setOf(
        "follow", "following", "followers", "message", "messages", "remove", "search", "suggested", "posts", "reels",
        "edit", "share", "more", "home", "explore", "like", "likes", "reply", "view", "see", "all", "today", "yesterday",
        "ago", "active", "requested", "mutual", "tagged", "notifications", "activity", "profile", "options", "back",
        "done", "cancel", "save", "next", "contacts", "close",
    )
    private val cutEnd = Regex("(…|\\.{3})$")

    /** Reads one screenshot's OCR text. Lines that are not usernames go to [OcrParse.rejected]. */
    fun parse(text: String, shot: Int): OcrParse {
        val out = ArrayList<OcrCandidate>()
        val rejected = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val cut = cutEnd.containsMatchIn(line)
            var t = line.replace(cutEnd, "").trim()
            if (t.startsWith("@")) t = t.substring(1).trim()
            if (t.lowercase() in uiWords) continue
            val n = TrackerLogic.normalizeUsername(t)
            if (n == null) { rejected.add(line.take(60)); continue }
            val reasons = ArrayList<String>()
            if (cut) reasons.add("may be cut off")
            if (n.length < 3) reasons.add("very short")
            if (n.any { it.isDigit() } && n.any { it == 'O' || it == 'l' || it == 'I' }) reasons.add("0/O and 1/l/I are easy to mix up: check spelling")
            out.add(OcrCandidate(n, shot, reasons))
        }
        return OcrParse(out, rejected)
    }

    private class Acc(val name: String) {
        val shots = LinkedHashSet<Int>()
        var clean = false
        val reasons = LinkedHashSet<String>()
    }

    /** One entry per username across all screenshots. A clean reading of a name clears its CHECK flag. */
    fun aggregate(cands: List<OcrCandidate>): List<OcrEntry> {
        val map = LinkedHashMap<String, Acc>()
        for (c in cands) {
            val a = map.getOrPut(c.name.lowercase()) { Acc(c.name) }
            a.shots.add(c.shot)
            if (c.reasons.isEmpty()) a.clean = true else a.reasons.addAll(c.reasons)
        }
        return map.values
            .map { OcrEntry(it.name, it.shots.toList(), if (it.clean) emptyList() else it.reasons.toList()) }
            .sortedBy { it.name.lowercase() }
    }

    /** Readings that were merged into an earlier one (the same name seen again). */
    fun repeats(cands: List<OcrCandidate>, entries: List<OcrEntry>): Int = cands.size - entries.size

    /** Replaces entry [index] with [raw]; index -1 adds a new name. Null when [raw] is not a valid username. */
    fun withEdit(entries: List<OcrEntry>, index: Int, raw: String): List<OcrEntry>? {
        val n = TrackerLogic.normalizeUsername(raw) ?: return null
        val original = entries.getOrNull(index)
        val rest = entries.filterIndexed { i, _ -> i != index }
        return mergeInto(rest, OcrEntry(n, original?.shots ?: emptyList(), emptyList()))
    }

    fun withRemove(entries: List<OcrEntry>, index: Int): List<OcrEntry> = entries.filterIndexed { i, _ -> i != index }

    /** Same name (ignoring case) merges into the existing entry, keeping its spelling and flags. */
    private fun mergeInto(list: List<OcrEntry>, e: OcrEntry): List<OcrEntry> {
        val i = list.indexOfFirst { it.name.equals(e.name, ignoreCase = true) }
        val out = list.toMutableList()
        if (i < 0) out.add(e) else out[i] = out[i].copy(shots = (out[i].shots + e.shots).distinct())
        return out.sortedBy { it.name.lowercase() }
    }
}
