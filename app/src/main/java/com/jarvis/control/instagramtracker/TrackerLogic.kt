package com.jarvis.control.instagramtracker

import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Instagram Observation Tracker (core). Pure Kotlin: no network, no Android, no Instagram access.
 * It only records and compares observations that the user types or imports themselves.
 * Results are differences between two user-provided lists. They never say who followed or unfollowed.
 */
const val TRACKER_DISCLAIMER = "Results represent differences between user-provided observations. They do not establish when or why an account changed its followers or following list."
const val TRACKER_PERMISSION = "I own this account, or I have permission to record its counts and lists. Nothing is fetched from Instagram."

const val KIND_COUNTS = "counts"
const val KIND_LIST = "list"
const val CAT_FOLLOWERS = "followers"
const val CAT_FOLLOWING = "following"
const val SRC_MANUAL = "manual"
const val SRC_FILE = "file"

data class Profile(val id: Long, val label: String, val username: String, val notes: String, val created: Long, val lastObs: Long, val confirmed: Boolean)

/** COUNTS snapshots hold follower and following numbers. LIST snapshots hold usernames for one [listCategory]. */
data class Snapshot(
    val id: Long, val profileId: Long, val ts: Long, val kind: String, val listCategory: String,
    val followers: Int?, val following: Int?, val usernames: List<String>, val source: String, val notes: String,
)

data class ParsedList(val names: List<String>, val invalid: List<String>, val duplicates: Int, val skipped: Int)
data class ListDiff(val added: List<String>, val removed: List<String>, val unchanged: List<String>)

object TrackerLogic {
    private val userRe = Regex("^(?!.*\\.\\.)[A-Za-z0-9._]{1,30}$")
    private val countRe = Regex("^\\d{1,9}$")
    const val DUPLICATE_WINDOW_MS = 60_000L
    /** Largest imported file accepted, in bytes. Larger files are refused, never silently cut short. */
    const val MAX_IMPORT_BYTES = 1_000_000
    /** Most usernames one list may hold. Keeps each stored row well under Android's cursor row limit. */
    const val MAX_LIST_NAMES = 20_000

    fun listTooLarge(names: Int): Boolean = names > MAX_LIST_NAMES

    /** Removes spaces and one leading @, then checks the format (letters, numbers, dots, underscores; 1-30 characters). */
    fun normalizeUsername(raw: String): String? {
        var t = raw.trim()
        if (t.startsWith("@")) t = t.substring(1).trim()
        return if (userRe.matches(t)) t else null
    }

    /**
     * One username per line, or the first column of a CSV line. Empty lines and a "username" header are skipped.
     * Duplicates are matched ignoring case; the first spelling seen is kept for display.
     */
    fun parseUsernames(text: String): ParsedList {
        val seen = LinkedHashMap<String, String>()
        val invalid = ArrayList<String>()
        var dup = 0
        var skipped = 0
        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty()) { skipped++; continue }
            if (line.contains(',')) line = line.substringBefore(',').trim().trim('"').trim()
            if (line.isEmpty()) { skipped++; continue }
            if (line.equals("username", ignoreCase = true) || line.equals("usernames", ignoreCase = true)) { skipped++; continue }
            val n = normalizeUsername(line)
            if (n == null) { invalid.add(line.take(60)); continue }
            val key = n.lowercase()
            if (seen.containsKey(key)) dup++ else seen[key] = n
        }
        return ParsedList(seen.values.toList(), invalid, dup, skipped)
    }

    /** Added = in the newer list only. Removed = in the older list only. Unchanged = in both. Case-insensitive, sorted A-Z. */
    fun compareLists(older: List<String>, newer: List<String>): ListDiff {
        val o = LinkedHashMap<String, String>(); for (u in older) o.putIfAbsent(u.lowercase(), u)
        val n = LinkedHashMap<String, String>(); for (u in newer) n.putIfAbsent(u.lowercase(), u)
        return ListDiff(
            added = n.filterKeys { it !in o }.values.sortedBy { it.lowercase() },
            removed = o.filterKeys { it !in n }.values.sortedBy { it.lowercase() },
            unchanged = n.filterKeys { it in o }.values.sortedBy { it.lowercase() },
        )
    }

    /** Newer minus older, or null when either number is missing. */
    fun countDiff(older: Int?, newer: Int?): Int? = if (older == null || newer == null) null else newer - older

    /** Non-negative whole number up to 9 digits, or null. */
    fun parseCount(s: String): Int? {
        val t = s.trim()
        return if (countRe.matches(t)) t.toInt() else null
    }

    private fun dateFormat(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }

    /** Strict "yyyy-MM-dd HH:mm" in the phone's time zone, or null when the text is not a real date. */
    fun parseDateTime(s: String): Long? = try { dateFormat().parse(s.trim())?.time } catch (_: Exception) { null }

    fun formatDateTime(ts: Long): String = dateFormat().format(java.util.Date(ts))

    /** True when the same content for the same profile and kind was saved within the duplicate window. */
    fun isDuplicate(existing: List<Snapshot>, c: Snapshot): Boolean = existing.any { e ->
        e.profileId == c.profileId && e.kind == c.kind && e.listCategory == c.listCategory &&
            e.followers == c.followers && e.following == c.following && e.usernames == c.usernames &&
            kotlin.math.abs(e.ts - c.ts) < DUPLICATE_WINDOW_MS
    }

    fun countWarnings(followers: Int?, following: Int?): List<String> {
        val w = ArrayList<String>()
        if (followers == null) w.add("follower count was not entered")
        if (following == null) w.add("following count was not entered")
        return w
    }

    /** Warnings that say when a username list may be incomplete. Never treated as confirmed removals. */
    fun listWarnings(names: Int, invalid: Int, duplicates: Int, enteredCount: Int?, source: String): List<String> {
        val w = ArrayList<String>()
        if (names == 0) w.add("the list has no usernames")
        if (invalid > 0) w.add("$invalid line(s) were not valid usernames and were left out")
        if (duplicates > 0) w.add("$duplicates duplicate line(s) were merged")
        if (enteredCount != null && enteredCount != names) w.add("you entered $enteredCount as the count, but the list has $names usernames: the list may be incomplete")
        if (source == SRC_FILE) w.add("this came from a file: check the file was complete before comparing")
        return w
    }

    fun comparisonReport(label: String, older: Snapshot, newer: Snapshot, diff: ListDiff, warnings: List<String>, comparedAt: Long, category: String): String = buildString {
        append("Account: ").append(label).append('\n')
        append("Compared: ").append(category).append(" lists\n")
        append("Older observation: ").append(formatDateTime(older.ts)).append(" (").append(older.usernames.size).append(" usernames)\n")
        append("Newer observation: ").append(formatDateTime(newer.ts)).append(" (").append(newer.usernames.size).append(" usernames)\n")
        val d = newer.usernames.size - older.usernames.size
        append("Difference in list size: ").append(if (d >= 0) "+$d" else "$d").append('\n')
        append("Comparison made: ").append(formatDateTime(comparedAt)).append("\n\n")
        append("ADDED USERNAMES (").append(diff.added.size).append(")\n")
        diff.added.forEach { append("  ").append(it).append('\n') }
        append("\nREMOVED USERNAMES (").append(diff.removed.size).append(")\n")
        diff.removed.forEach { append("  ").append(it).append('\n') }
        append("\nUNCHANGED USERNAMES (").append(diff.unchanged.size).append(")\n")
        diff.unchanged.forEach { append("  ").append(it).append('\n') }
        if (warnings.isNotEmpty()) {
            append("\nCOMPLETENESS WARNINGS\n")
            warnings.forEach { append("  - ").append(it).append('\n') }
        }
        append("\n").append(TRACKER_DISCLAIMER).append('\n')
    }
}

/** Storage contract. The SQLite version (TrackerDb) and the in-memory version (tests) follow the same rules. */
interface TrackerStore {
    fun profiles(): List<Profile>
    fun profile(id: Long): Profile?
    /** -1 when the label or username is invalid, or the permission was not confirmed. */
    fun createProfile(label: String, username: String, notes: String, now: Long, confirmed: Boolean): Long
    fun updateProfile(id: Long, label: String, username: String, notes: String): Boolean
    /** Deletes the profile and every snapshot that belongs to it, in one transaction. */
    fun deleteProfile(id: Long)
    /** Returns the new id, -1 when the profile does not exist, or -2 for a duplicate submission. */
    fun addSnapshot(s: Snapshot): Long
    fun snapshots(profileId: Long): List<Snapshot>
    fun deleteSnapshot(profileId: Long, id: Long): Boolean
    fun renameSnapshotNotes(profileId: Long, id: Long, notes: String): Boolean
}

class MemTrackerStore : TrackerStore {
    private val profileList = ArrayList<Profile>()
    private val snapList = ArrayList<Snapshot>()
    private var seq = 1L

    override fun profiles(): List<Profile> = profileList.sortedByDescending { it.created }
    override fun profile(id: Long): Profile? = profileList.firstOrNull { it.id == id }

    override fun createProfile(label: String, username: String, notes: String, now: Long, confirmed: Boolean): Long {
        val l = label.trim()
        val u = TrackerLogic.normalizeUsername(username) ?: return -1
        if (!confirmed || l.isEmpty() || l.length > 40) return -1
        val p = Profile(seq++, l, u, notes.trim().take(500), now, 0L, true)
        profileList.add(p)
        return p.id
    }

    override fun updateProfile(id: Long, label: String, username: String, notes: String): Boolean {
        val old = profile(id) ?: return false
        val l = label.trim()
        val u = TrackerLogic.normalizeUsername(username) ?: return false
        if (l.isEmpty() || l.length > 40) return false
        val i = profileList.indexOf(old)
        profileList[i] = old.copy(label = l, username = u, notes = notes.trim().take(500))
        return true
    }

    override fun deleteProfile(id: Long) {
        profileList.removeAll { it.id == id }
        snapList.removeAll { it.profileId == id }
    }

    override fun addSnapshot(s: Snapshot): Long {
        val p = profile(s.profileId) ?: return -1
        if (TrackerLogic.isDuplicate(snapList.filter { it.profileId == s.profileId }, s)) return -2
        val saved = s.copy(id = seq++)
        snapList.add(saved)
        val i = profileList.indexOf(p)
        profileList[i] = p.copy(lastObs = maxOf(p.lastObs, s.ts))
        return saved.id
    }

    override fun snapshots(profileId: Long): List<Snapshot> = snapList.filter { it.profileId == profileId }.sortedByDescending { it.ts }

    override fun deleteSnapshot(profileId: Long, id: Long): Boolean = snapList.removeAll { it.id == id && it.profileId == profileId }

    override fun renameSnapshotNotes(profileId: Long, id: Long, notes: String): Boolean {
        val i = snapList.indexOfFirst { it.id == id && it.profileId == profileId }
        if (i < 0) return false
        snapList[i] = snapList[i].copy(notes = notes.trim().take(300))
        return true
    }
}
