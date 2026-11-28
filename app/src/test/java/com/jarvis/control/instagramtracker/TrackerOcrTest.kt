package com.jarvis.control.instagramtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerOcrTest {
    private fun names(p: OcrParse) = p.candidates.map { it.name }

    @Test fun extractsPlainAndAtUsernames() {
        val p = TrackerOcr.parse("alice\n@bob_2\n  carol.x  \n", 1)
        assertEquals(listOf("alice", "bob_2", "carol.x"), names(p))
        assertTrue(p.rejected.isEmpty())
    }

    @Test fun skipsInstagramScreenWords() {
        val p = TrackerOcr.parse("Follow\nFollowing\nMessage\nRemove\nalice", 1)
        assertEquals(listOf("alice"), names(p))
        assertTrue(p.rejected.isEmpty())
    }

    @Test fun displayNamesWithSpacesAreRejectedNotGuessed() {
        val p = TrackerOcr.parse("John Smith\nalice", 1)
        assertEquals(listOf("alice"), names(p))
        assertEquals(listOf("John Smith"), p.rejected)
    }

    @Test fun invalidCharactersRejected() {
        val p = TrackerOcr.parse("bad-name\nok_name\n!!!", 1)
        assertEquals(listOf("ok_name"), names(p))
        assertEquals(listOf("bad-name", "!!!"), p.rejected)
    }

    @Test fun emptyOcrOutputGivesNothing() {
        val p = TrackerOcr.parse("", 1)
        assertTrue(p.candidates.isEmpty() && p.rejected.isEmpty())
        assertTrue(TrackerOcr.parse("   \n\n", 2).candidates.isEmpty())
        assertTrue(TrackerOcr.aggregate(emptyList()).isEmpty())
    }

    @Test fun cutOffLineIsFlaggedNotDropped() {
        val c = TrackerOcr.parse("longusername_ab…\nlongusername_ab...", 1).candidates
        assertEquals(2, c.size)
        assertEquals("longusername_ab", c[0].name)
        assertTrue(c.all { it.reasons.contains("may be cut off") })
    }

    @Test fun shortNameFlagged() {
        val c = TrackerOcr.parse("ab", 1).candidates.single()
        assertTrue(c.reasons.contains("very short"))
    }

    @Test fun ambiguousDigitLetterMixFlagged() {
        val c = TrackerOcr.parse("l0rd_x1", 1).candidates.single()
        assertTrue(c.reasons.any { it.contains("check spelling") })
        val clean = TrackerOcr.parse("plain_name", 1).candidates.single()
        assertTrue(clean.reasons.isEmpty())
    }

    @Test fun sameNameAcrossScreenshotsMergesAndKeepsAllSources() {
        val cands = TrackerOcr.parse("Alice\nbob", 1).candidates + TrackerOcr.parse("alice\ncarol", 2).candidates
        val entries = TrackerOcr.aggregate(cands)
        assertEquals(listOf("Alice", "bob", "carol"), entries.map { it.name })
        assertEquals(listOf(1, 2), entries[0].shots)
        assertEquals(1, TrackerOcr.repeats(cands, entries))
    }

    @Test fun cleanReadingClearsCheckFlag() {
        val cands = TrackerOcr.parse("longusername_ab…", 1).candidates + TrackerOcr.parse("longusername_ab", 2).candidates
        val e = TrackerOcr.aggregate(cands).single()
        assertFalse(e.uncertain)
    }

    @Test fun allUncertainReadingsStayFlagged() {
        val e = TrackerOcr.aggregate(TrackerOcr.parse("ab", 1).candidates + TrackerOcr.parse("ab", 2).candidates).single()
        assertTrue(e.uncertain)
        assertTrue(e.reasons.contains("very short"))
    }

    @Test fun editValidAndInvalid() {
        val entries = TrackerOcr.aggregate(TrackerOcr.parse("ab\nxyz", 1).candidates)
        val edited = TrackerOcr.withEdit(entries, 0, "@abcd")!!
        assertEquals(listOf("abcd", "xyz"), edited.map { it.name })
        assertFalse(edited[0].uncertain)
        assertNull(TrackerOcr.withEdit(entries, 0, "bad name"))
    }

    @Test fun editIntoExistingNameMerges() {
        val entries = TrackerOcr.aggregate(TrackerOcr.parse("alice\nbob", 1).candidates + TrackerOcr.parse("bob", 2).candidates)
        val merged = TrackerOcr.withEdit(entries, 0, "BOB")!!
        assertEquals(listOf("bob"), merged.map { it.name })
        assertEquals(listOf(1, 2), merged[0].shots)
    }

    @Test fun removeAndManualAdd() {
        val entries = TrackerOcr.aggregate(TrackerOcr.parse("alice\nbob", 1).candidates)
        val removed = TrackerOcr.withRemove(entries, 0)
        assertEquals(listOf("bob"), removed.map { it.name })
        val added = TrackerOcr.withEdit(removed, -1, "carol")!!
        assertTrue(added.first { it.name == "carol" }.shots.isEmpty())
    }

    @Test fun cancelledImportWritesNothing() {
        val store = MemTrackerStore()
        val id = store.createProfile("P", "user1", "", 1L, true)
        val before = store.snapshots(id).size
        TrackerOcr.aggregate(TrackerOcr.parse("alice", 1).candidates)
        assertEquals(before, store.snapshots(id).size)
    }

    @Test fun approvedImportSavesWithOcrSourceAndBlocksDuplicate() {
        val store = MemTrackerStore()
        val id = store.createProfile("P", "user1", "", 1L, true)
        val entries = TrackerOcr.aggregate(TrackerOcr.parse("alice\nbob", 1).candidates)
        val r = store.addSnapshot(Snapshot(0, id, 1_700_000_000_000L, KIND_LIST, CAT_FOLLOWING, null, null, entries.map { it.name }, SRC_OCR, "x"))
        assertTrue(r > 0)
        val saved = store.snapshots(id).single()
        assertEquals(SRC_OCR, saved.source)
        assertEquals(listOf("alice", "bob"), saved.usernames)
        assertEquals(-2L, store.addSnapshot(Snapshot(0, id, 1_700_000_010_000L, KIND_LIST, CAT_FOLLOWING, null, null, listOf("alice", "bob"), SRC_OCR, "x")))
    }

    @Test fun largeOcrOutputAggregates() {
        val text = (1..5000).joinToString("\n") { "fan_$it" }
        val cands = TrackerOcr.parse(text, 1).candidates + TrackerOcr.parse(text, 2).candidates
        val entries = TrackerOcr.aggregate(cands)
        assertEquals(5000, entries.size)
        assertEquals(5000, TrackerOcr.repeats(cands, entries))
    }

    @Test fun warningTextIsExact() {
        assertEquals("OCR results may contain mistakes or omissions. Review the extracted usernames before saving.", OCR_WARNING)
    }
}
