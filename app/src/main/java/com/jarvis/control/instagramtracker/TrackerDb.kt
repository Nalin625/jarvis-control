package com.jarvis.control.instagramtracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local SQLite storage for the Instagram Observation Tracker. Its own file (jarvis_instagram_observations.db),
 * no other Jarvis data is read or written here. No network, no sync, no background access.
 */
class TrackerDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "jarvis_instagram_observations.db", null, 1), TrackerStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profiles(id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT NOT NULL, username TEXT NOT NULL, notes TEXT, created INTEGER NOT NULL, last_obs INTEGER NOT NULL DEFAULT 0, confirmed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE snapshots(id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL, list_category TEXT, followers INTEGER, following INTEGER, usernames TEXT, source TEXT, notes TEXT)")
        db.execSQL("CREATE INDEX snapshots_profile ON snapshots(profile_id, ts)")
    }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized override fun profiles(): List<Profile> {
        val out = ArrayList<Profile>()
        readableDatabase.rawQuery("SELECT id,label,username,notes,created,last_obs,confirmed FROM profiles ORDER BY created DESC", null).use { c ->
            while (c.moveToNext()) out.add(readProfile(c))
        }
        return out
    }

    @Synchronized override fun profile(id: Long): Profile? =
        readableDatabase.rawQuery("SELECT id,label,username,notes,created,last_obs,confirmed FROM profiles WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) readProfile(c) else null
        }

    private fun readProfile(c: android.database.Cursor) = Profile(
        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4), c.getLong(5), c.getInt(6) == 1,
    )

    @Synchronized override fun createProfile(label: String, username: String, notes: String, now: Long, confirmed: Boolean): Long {
        val l = label.trim()
        val u = TrackerLogic.normalizeUsername(username) ?: return -1
        if (!confirmed || l.isEmpty() || l.length > 40) return -1
        return writableDatabase.insert("profiles", null, ContentValues().apply {
            put("label", l); put("username", u); put("notes", notes.trim().take(500)); put("created", now); put("last_obs", 0L); put("confirmed", 1)
        })
    }

    @Synchronized override fun updateProfile(id: Long, label: String, username: String, notes: String): Boolean {
        val l = label.trim()
        val u = TrackerLogic.normalizeUsername(username) ?: return false
        if (l.isEmpty() || l.length > 40) return false
        val rows = writableDatabase.update("profiles", ContentValues().apply {
            put("label", l); put("username", u); put("notes", notes.trim().take(500))
        }, "id=?", arrayOf(id.toString()))
        return rows > 0
    }

    /** One transaction: the snapshots go first, then the profile. Either both are removed or neither is. */
    @Synchronized override fun deleteProfile(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("snapshots", "profile_id=?", arrayOf(id.toString()))
            db.delete("profiles", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized override fun addSnapshot(s: Snapshot): Long {
        if (profile(s.profileId) == null) return -1
        if (TrackerLogic.isDuplicate(snapshots(s.profileId), s)) return -2
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("snapshots", null, ContentValues().apply {
                put("profile_id", s.profileId); put("ts", s.ts); put("kind", s.kind); put("list_category", s.listCategory)
                if (s.followers != null) put("followers", s.followers) else putNull("followers")
                if (s.following != null) put("following", s.following) else putNull("following")
                put("usernames", s.usernames.joinToString("\n")); put("source", s.source); put("notes", s.notes.take(300))
            })
            db.execSQL("UPDATE profiles SET last_obs = MAX(last_obs, ?) WHERE id = ?", arrayOf<Any>(s.ts, s.profileId))
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    @Synchronized override fun snapshots(profileId: Long): List<Snapshot> {
        val out = ArrayList<Snapshot>()
        readableDatabase.rawQuery("SELECT id,profile_id,ts,kind,list_category,followers,following,usernames,source,notes FROM snapshots WHERE profile_id=? ORDER BY ts DESC, id DESC", arrayOf(profileId.toString())).use { c ->
            while (c.moveToNext()) {
                val names = (c.getString(7) ?: "").split('\n').filter { it.isNotEmpty() }
                out.add(Snapshot(
                    id = c.getLong(0), profileId = c.getLong(1), ts = c.getLong(2), kind = c.getString(3) ?: "",
                    listCategory = c.getString(4) ?: "",
                    followers = if (c.isNull(5)) null else c.getInt(5),
                    following = if (c.isNull(6)) null else c.getInt(6),
                    usernames = names, source = c.getString(8) ?: "", notes = c.getString(9) ?: "",
                ))
            }
        }
        return out
    }

    @Synchronized override fun deleteSnapshot(profileId: Long, id: Long): Boolean =
        writableDatabase.delete("snapshots", "id=? AND profile_id=?", arrayOf(id.toString(), profileId.toString())) > 0

    @Synchronized override fun renameSnapshotNotes(profileId: Long, id: Long, notes: String): Boolean =
        writableDatabase.update("snapshots", ContentValues().apply { put("notes", notes.trim().take(300)) }, "id=? AND profile_id=?", arrayOf(id.toString(), profileId.toString())) > 0

    companion object {
        @Volatile private var inst: TrackerDb? = null
        fun get(ctx: Context): TrackerDb = inst ?: synchronized(this) { inst ?: TrackerDb(ctx).also { inst = it } }
    }
}
