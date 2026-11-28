package com.jarvis.control.intel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/*
 * OSINT phase 5 storage. Its own database file (jarvis_osint.db), so investigations never share tables with the
 * existing Intelligence history, snapshots or events. Stays on this phone. Nothing here is uploaded.
 */
class SqlWorkspaceStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "jarvis_osint.db", null, 1), WorkspaceStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX projects_name ON projects(name COLLATE NOCASE)")
        db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, ts INTEGER NOT NULL, kind TEXT, target TEXT, title TEXT, source_url TEXT, findings TEXT)")
        db.execSQL("CREATE INDEX items_project ON items(project_id, ts)")
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, ts INTEGER NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE INDEX notes_project ON notes(project_id, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized override fun projects(): List<Project> {
        val out = ArrayList<Project>()
        val c = readableDatabase.rawQuery("SELECT id,name,created FROM projects ORDER BY created DESC", null)
        while (c.moveToNext()) out.add(Project(c.getLong(0), c.getString(1), c.getLong(2)))
        c.close()
        return out
    }

    @Synchronized override fun createProject(name: String, now: Long): Long {
        val n = name.trim().take(60)
        if (n.isEmpty()) return -1
        return try {
            writableDatabase.insertOrThrow("projects", null, ContentValues().apply { put("name", n); put("created", now) })
        } catch (e: Exception) { -1 }   // duplicate name (case-insensitive)
    }

    @Synchronized override fun deleteProject(projectId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("items", "project_id=?", arrayOf(projectId.toString()))
            db.delete("notes", "project_id=?", arrayOf(projectId.toString()))
            db.delete("projects", "id=?", arrayOf(projectId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized override fun addItem(item: Item): Long {
        val exists = readableDatabase.rawQuery("SELECT 1 FROM projects WHERE id=?", arrayOf(item.projectId.toString())).use { it.moveToFirst() }
        if (!exists) return -1
        val findings = JSONArray()
        for (f in item.findings) findings.put(JSONObject().put("l", f.label).put("v", f.value).put("e", f.ev.name).put("s", f.source))
        return writableDatabase.insert("items", null, ContentValues().apply {
            put("project_id", item.projectId); put("ts", item.ts); put("kind", item.kind); put("target", item.target)
            put("title", item.title); put("source_url", item.sourceUrl); put("findings", findings.toString())
        })
    }

    @Synchronized override fun items(projectId: Long, target: String?): List<Item> {
        val out = ArrayList<Item>()
        val sql = "SELECT id,project_id,ts,kind,target,title,source_url,findings FROM items WHERE project_id=?" +
            (if (target != null) " AND target=? COLLATE NOCASE" else "") + " ORDER BY ts DESC"
        val args = if (target != null) arrayOf(projectId.toString(), target) else arrayOf(projectId.toString())
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(Item(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "",
                c.getString(5) ?: "", c.getString(6) ?: "", parseFindings(c.getString(7) ?: "[]")))
        }
        return out
    }

    @Synchronized override fun deleteItem(projectId: Long, itemId: Long): Boolean =
        writableDatabase.delete("items", "id=? AND project_id=?", arrayOf(itemId.toString(), projectId.toString())) > 0

    @Synchronized override fun addNote(projectId: Long, ts: Long, text: String): Long {
        if (text.isBlank()) return -1
        val exists = readableDatabase.rawQuery("SELECT 1 FROM projects WHERE id=?", arrayOf(projectId.toString())).use { it.moveToFirst() }
        if (!exists) return -1
        return writableDatabase.insert("notes", null, ContentValues().apply { put("project_id", projectId); put("ts", ts); put("text", text.trim().take(2000)) })
    }

    @Synchronized override fun notes(projectId: Long): List<Note> {
        val out = ArrayList<Note>()
        readableDatabase.rawQuery("SELECT id,project_id,ts,text FROM notes WHERE project_id=? ORDER BY ts DESC", arrayOf(projectId.toString())).use { c ->
            while (c.moveToNext()) out.add(Note(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: ""))
        }
        return out
    }

    private fun parseFindings(s: String): List<Finding> {
        val a = try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Finding>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val ev = runCatching { Ev.valueOf(o.getString("e")) }.getOrDefault(Ev.UNAVAILABLE)
            out.add(Finding(o.optString("l"), o.optString("v"), ev, o.optString("s")))
        }
        return out
    }

    companion object {
        @Volatile private var inst: SqlWorkspaceStore? = null
        fun get(ctx: Context): SqlWorkspaceStore = inst ?: synchronized(this) { inst ?: SqlWorkspaceStore(ctx).also { inst = it } }
    }
}

/** Turns a finished report into a saved investigation item. The first web link becomes the item's source link. */
fun reportToItem(r: Report, projectId: Long): Item = Item(
    id = 0, projectId = projectId, ts = r.ts, kind = r.kind, target = r.target, title = r.title,
    sourceUrl = r.findings.firstOrNull { it.source.startsWith("http") }?.source ?: "",
    findings = r.findings.toList(),
)
