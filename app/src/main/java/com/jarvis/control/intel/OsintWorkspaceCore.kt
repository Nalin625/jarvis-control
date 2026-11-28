package com.jarvis.control.intel

/*
 * Investigation workspace (OSINT phase 5): the data model, the comparison rules and the Markdown export.
 * Pure Kotlin. The SQLite store (OsintStore.kt) and an in-memory store used by tests both implement [WorkspaceStore].
 */
data class Project(val id: Long, val name: String, val created: Long)

/** One saved result. [findings] keep their own labels (VERIFIED/OBSERVED/ESTIMATE/UNAVAILABLE) and sources. */
data class Item(
    val id: Long,
    val projectId: Long,
    val ts: Long,
    val kind: String,
    val target: String,
    val title: String,
    val sourceUrl: String,
    val findings: List<Finding>,
)

data class Note(val id: Long, val projectId: Long, val ts: Long, val text: String)

interface WorkspaceStore {
    fun projects(): List<Project>
    /** -1 when the name is empty or already used. Names are unique so projects cannot be mixed up. */
    fun createProject(name: String, now: Long): Long
    /** Removes the project together with its items and notes. Nothing outside the project is touched. */
    fun deleteProject(projectId: Long)
    /** -1 when the project does not exist. */
    fun addItem(item: Item): Long
    fun items(projectId: Long, target: String? = null): List<Item>
    /** Only deletes when the item really belongs to [projectId]. */
    fun deleteItem(projectId: Long, itemId: Long): Boolean
    fun addNote(projectId: Long, ts: Long, text: String): Long
    fun notes(projectId: Long): List<Note>
}

/** In-memory store with the same rules as the SQLite one. Used by unit tests. */
class MemWorkspaceStore : WorkspaceStore {
    private val projectList = ArrayList<Project>()
    private val itemList = ArrayList<Item>()
    private val noteList = ArrayList<Note>()
    private var seq = 1L

    override fun projects(): List<Project> = projectList.sortedByDescending { it.created }
    override fun createProject(name: String, now: Long): Long {
        val n = name.trim()
        if (n.isEmpty() || projectList.any { it.name.equals(n, ignoreCase = true) }) return -1
        val p = Project(seq++, n, now)
        projectList.add(p)
        return p.id
    }
    override fun deleteProject(projectId: Long) {
        projectList.removeAll { it.id == projectId }
        itemList.removeAll { it.projectId == projectId }
        noteList.removeAll { it.projectId == projectId }
    }
    override fun addItem(item: Item): Long {
        if (projectList.none { it.id == item.projectId }) return -1
        val saved = item.copy(id = seq++)
        itemList.add(saved)
        return saved.id
    }
    override fun items(projectId: Long, target: String?): List<Item> =
        itemList.filter { it.projectId == projectId && (target == null || it.target.equals(target, ignoreCase = true)) }.sortedByDescending { it.ts }
    override fun deleteItem(projectId: Long, itemId: Long): Boolean = itemList.removeAll { it.id == itemId && it.projectId == projectId }
    override fun addNote(projectId: Long, ts: Long, text: String): Long {
        if (projectList.none { it.id == projectId } || text.isBlank()) return -1
        val n = Note(seq++, projectId, ts, text.trim())
        noteList.add(n)
        return n.id
    }
    override fun notes(projectId: Long): List<Note> = noteList.filter { it.projectId == projectId }.sortedByDescending { it.ts }
}

/**
 * Comparison of two saved observations of the same target.
 *  - added / removed / changed are only reported when BOTH sides were obtained successfully.
 *  - a finding that was UNAVAILABLE on either side goes to [notComparable] instead, so a failed lookup is never read as a removal.
 */
data class Diff(val added: List<String>, val removed: List<String>, val changed: List<String>, val unchanged: Int, val notComparable: List<String>)

fun compareItems(older: Item, newer: Item): Diff {
    fun usable(f: Finding) = f.ev != Ev.UNAVAILABLE
    val oldMap = older.findings.groupBy { it.label }
    val newMap = newer.findings.groupBy { it.label }
    val added = ArrayList<String>(); val removed = ArrayList<String>(); val changed = ArrayList<String>()
    val notComparable = ArrayList<String>()
    var same = 0
    for (label in (oldMap.keys + newMap.keys).toSortedSet()) {
        val o = oldMap[label]?.firstOrNull()
        val n = newMap[label]?.firstOrNull()
        when {
            (o != null && !usable(o)) || (n != null && !usable(n)) -> notComparable.add(label)
            o == null && n != null -> added.add(label)
            o != null && n == null -> removed.add(label)
            o != null && n != null && o.value == n.value -> same++
            o != null && n != null -> changed.add("$label: ${o.value.take(80)} -> ${n.value.take(80)}")
        }
    }
    return Diff(added, removed, changed, same, notComparable)
}

/** Markdown export of one project: its items with sources and timestamps, and its notes. [fmt] turns a timestamp into text. */
fun exportProjectMarkdown(p: Project, items: List<Item>, notes: List<Note>, fmt: (Long) -> String): String = buildString {
    append("# Investigation: ").append(p.name).append("\n\n")
    append("- **Created:** ").append(fmt(p.created)).append('\n')
    append("- **Saved results:** ").append(items.size).append('\n')
    append("- **Notes:** ").append(notes.size).append("\n\n")
    append("> Verified = read from a public record. Observed = seen or measured just now. Estimate = inferred, may be wrong. Unavailable = could not be obtained.\n")
    if (notes.isNotEmpty()) {
        append("\n## Notes\n\n")
        for (n in notes.sortedBy { it.ts }) append("- ").append(fmt(n.ts)).append(": ").append(n.text.replace("\n", " ")).append('\n')
    }
    for (it in items.sortedBy { it.ts }) {
        append("\n## ").append(it.title).append("\n\n")
        append("- **Target:** ").append(it.target).append('\n')
        append("- **Retrieved:** ").append(fmt(it.ts)).append('\n')
        if (it.sourceUrl.isNotBlank()) append("- **Source:** ").append(it.sourceUrl).append('\n')
        append('\n')
        for (f in it.findings) {
            append("- [").append(f.ev.tag).append("] **").append(f.label).append(":** ").append(f.value.replace("\n", " "))
            if (f.source.isNotBlank()) append(" (").append(f.source).append(')')
            append('\n')
        }
    }
}
