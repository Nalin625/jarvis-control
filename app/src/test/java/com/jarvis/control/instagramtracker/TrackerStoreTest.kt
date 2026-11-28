package com.jarvis.control.instagramtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the storage rules against the in-memory store. The SQLite store follows the same contract (see TrackerDb). */
class TrackerStoreTest {
    private val t0 = 1_700_000_000_000L

    private fun list(ts: Long, names: List<String>, cat: String = CAT_FOLLOWERS) =
        Snapshot(0, 0, ts, KIND_LIST, cat, null, null, names, SRC_MANUAL, "note")

    private fun counts(ts: Long, f: Int?, g: Int?) = Snapshot(0, 0, ts, KIND_COUNTS, "", f, g, emptyList(), SRC_MANUAL, "")

    @Test fun permissionMustBeConfirmed() {
        val s = MemTrackerStore()
        assertEquals(-1L, s.createProfile("Mine", "user1", "", t0, false))
        assertTrue(s.profiles().isEmpty())
        assertTrue(s.createProfile("Mine", "user1", "", t0, true) > 0)
    }

    @Test fun invalidUsernameAndLabelRejected() {
        val s = MemTrackerStore()
        assertEquals(-1L, s.createProfile("Mine", "bad name", "", t0, true))
        assertEquals(-1L, s.createProfile("", "user1", "", t0, true))
        assertEquals(-1L, s.createProfile("x".repeat(41), "user1", "", t0, true))
        assertFalse(s.updateProfile(99, "x", "user1", ""))
    }

    @Test fun profilePersistsFieldsAndUpdates() {
        val s = MemTrackerStore()
        val id = s.createProfile("  Mine  ", "@User1", " notes ", t0, true)
        val p = s.profile(id)!!
        assertEquals("Mine", p.label)
        assertEquals("User1", p.username)
        assertEquals("notes", p.notes)
        assertTrue(s.updateProfile(id, "Renamed", "other_1", "n2"))
        assertEquals("other_1", s.profile(id)!!.username)
        assertEquals("Renamed", s.profile(id)!!.label)
    }

    @Test fun snapshotPersistenceAndOrdering() {
        val s = MemTrackerStore()
        val id = s.createProfile("P", "user1", "", t0, true)
        val a = s.addSnapshot(list(t0 + 1_000_000, listOf("A", "B")).copy(profileId = id))
        val b = s.addSnapshot(list(t0 + 2_000_000, listOf("A")).copy(profileId = id))
        assertTrue(a > 0 && b > 0 && a != b)
        val snaps = s.snapshots(id)
        assertEquals(2, snaps.size)
        assertEquals(b, snaps[0].id) // newest first
        assertEquals(listOf("A", "B"), snaps[1].usernames)
        assertEquals(t0 + 2_000_000, s.profile(id)!!.lastObs)
    }

    @Test fun countSnapshotKeepsNullsAndValues() {
        val s = MemTrackerStore()
        val id = s.createProfile("P", "user1", "", t0, true)
        s.addSnapshot(counts(t0 + 5_000_000, 300, null).copy(profileId = id))
        val c = s.snapshots(id).single()
        assertEquals(300, c.followers)
        assertNull(c.following)
    }

    @Test fun duplicateSubmissionBlocked() {
        val s = MemTrackerStore()
        val id = s.createProfile("P", "user1", "", t0, true)
        assertTrue(s.addSnapshot(counts(t0, 10, 20).copy(profileId = id)) > 0)
        assertEquals(-2L, s.addSnapshot(counts(t0 + 10_000, 10, 20).copy(profileId = id)))
        assertEquals(1, s.snapshots(id).size)
    }

    @Test fun snapshotForMissingProfileRejected() {
        val s = MemTrackerStore()
        assertEquals(-1L, s.addSnapshot(counts(t0, 1, 1).copy(profileId = 404)))
    }

    @Test fun deleteSnapshotOnlyRemovesThatOne() {
        val s = MemTrackerStore()
        val id = s.createProfile("P", "user1", "", t0, true)
        val a = s.addSnapshot(list(t0, listOf("A")).copy(profileId = id))
        s.addSnapshot(list(t0 + 1_000_000, listOf("B")).copy(profileId = id))
        assertTrue(s.deleteSnapshot(id, a))
        assertEquals(1, s.snapshots(id).size)
        assertFalse(s.deleteSnapshot(id, a))
    }

    @Test fun renameNotesChangesOnlyNotes() {
        val s = MemTrackerStore()
        val id = s.createProfile("P", "user1", "", t0, true)
        val sid = s.addSnapshot(list(t0, listOf("A")).copy(profileId = id))
        assertTrue(s.renameSnapshotNotes(id, sid, "new note"))
        val snap = s.snapshots(id).single()
        assertEquals("new note", snap.notes)
        assertEquals(listOf("A"), snap.usernames)
        assertFalse(s.renameSnapshotNotes(id + 1, sid, "x"))
    }

    @Test fun deleteProfileCascadesToSnapshotsOnly() {
        val s = MemTrackerStore()
        val keep = s.createProfile("Keep", "keep_user", "", t0, true)
        val gone = s.createProfile("Gone", "gone_user", "", t0, true)
        s.addSnapshot(list(t0, listOf("A")).copy(profileId = keep))
        s.addSnapshot(list(t0, listOf("B")).copy(profileId = gone))
        s.addSnapshot(counts(t0 + 9_000_000, 5, 5).copy(profileId = gone))
        s.deleteProfile(gone)
        assertNull(s.profile(gone))
        assertTrue(s.snapshots(gone).isEmpty())
        assertEquals(1, s.snapshots(keep).size)
        assertEquals(1, s.profiles().size)
    }

    @Test fun incompleteCountSnapshotProducesWarning() {
        val w = TrackerLogic.countWarnings(300, null)
        assertEquals(listOf("following count was not entered"), w)
    }

    @Test fun exportReportFromStoredSnapshotsKeepsDisclaimer() {
        val s = MemTrackerStore()
        val id = s.createProfile("Mine", "user1", "", t0, true)
        s.addSnapshot(list(t0, listOf("A", "B", "C")).copy(profileId = id))
        s.addSnapshot(list(t0 + 3_600_000, listOf("A", "C", "D")).copy(profileId = id))
        val snaps = s.snapshots(id)
        val newer = snaps[0]; val older = snaps[1]
        val diff = TrackerLogic.compareLists(older.usernames, newer.usernames)
        val text = TrackerLogic.comparisonReport("Mine", older, newer, diff, emptyList(), t0, CAT_FOLLOWERS)
        assertTrue(text.contains("ADDED USERNAMES (1)"))
        assertTrue(text.contains(TRACKER_DISCLAIMER))
    }
}
