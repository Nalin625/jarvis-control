package com.jarvis.control

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Every notification that pops up on this phone is kept here (on the phone itself, no laptop needed).
 * Dismissing a notification in the status bar never removes it from here. Only you remove it
 * (swipe it away, or CLEAR ALL). Starred ones survive CLEAR ALL.
 */
object NotifStore {
    class Row(val id: Long, val pkg: String, val app: String, val title: String, val text: String,
              val ts: Long, val saved: Boolean, val read: Boolean)

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "jarvis_notifs.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE notifs (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, title TEXT, text TEXT, ts INTEGER, saved INTEGER DEFAULT 0, read INTEGER DEFAULT 0)")
            db.execSQL("CREATE INDEX notifs_ts ON notifs(ts)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    @Volatile private var db: Db? = null
    private fun h(ctx: Context): Db = db ?: synchronized(this) { db ?: Db(ctx).also { db = it } }

    private const val KEEP = 2000   // newest unstarred notifications kept

    /** Saves one notification. Returns false if it was a duplicate (the same text posted again within 90 s). */
    @Synchronized
    fun add(ctx: Context, pkg: String, app: String, title: String, text: String, ts: Long): Boolean {
        val d = h(ctx).writableDatabase
        d.rawQuery("SELECT 1 FROM notifs WHERE pkg=? AND title=? AND text=? AND ts>? LIMIT 1",
            arrayOf(pkg, title, text, (ts - 90_000L).toString())).use { if (it.moveToFirst()) return false }
        val v = ContentValues().apply {
            put("pkg", pkg); put("app", app); put("title", title); put("text", text); put("ts", ts)
        }
        d.insert("notifs", null, v)
        d.execSQL("DELETE FROM notifs WHERE saved=0 AND id NOT IN (SELECT id FROM notifs WHERE saved=0 ORDER BY ts DESC LIMIT $KEEP)")
        return true
    }

    @Synchronized
    fun list(ctx: Context, savedOnly: Boolean, limit: Int = 400): List<Row> {
        val out = ArrayList<Row>()
        val where = if (savedOnly) "WHERE saved=1 " else ""
        h(ctx).readableDatabase.rawQuery(
            "SELECT id,pkg,app,title,text,ts,saved,read FROM notifs ${where}ORDER BY ts DESC LIMIT $limit", null).use {
            while (it.moveToNext()) out.add(Row(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: "",
                it.getString(4) ?: "", it.getLong(5), it.getInt(6) == 1, it.getInt(7) == 1))
        }
        return out
    }

    @Synchronized
    fun counts(ctx: Context): IntArray {   // total, unread, saved
        val r = IntArray(3)
        h(ctx).readableDatabase.rawQuery("SELECT COUNT(*), SUM(CASE WHEN read=0 THEN 1 ELSE 0 END), SUM(saved) FROM notifs", null).use {
            if (it.moveToFirst()) { r[0] = it.getInt(0); r[1] = it.getInt(1); r[2] = it.getInt(2) }
        }
        return r
    }

    @Synchronized fun unread(ctx: Context): Int = counts(ctx)[1]

    @Synchronized fun markAllRead(ctx: Context) { h(ctx).writableDatabase.execSQL("UPDATE notifs SET read=1 WHERE read=0") }

    @Synchronized fun delete(ctx: Context, id: Long) { h(ctx).writableDatabase.execSQL("DELETE FROM notifs WHERE id=?", arrayOf<Any>(id)) }

    @Synchronized fun setSaved(ctx: Context, id: Long, saved: Boolean) {
        h(ctx).writableDatabase.execSQL("UPDATE notifs SET saved=? WHERE id=?", arrayOf<Any>(if (saved) 1 else 0, id))
    }

    /** CLEAR ALL: removes everything except starred ones (or everything when [includeSaved]). Returns how many were removed. */
    @Synchronized
    fun clearAll(ctx: Context, includeSaved: Boolean = false): Int =
        h(ctx).writableDatabase.delete("notifs", if (includeSaved) null else "saved=0", null)
}
