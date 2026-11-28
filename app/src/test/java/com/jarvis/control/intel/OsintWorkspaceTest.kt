package com.jarvis.control.intel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Projects, isolation, deletion, comparison and export. Runs against the in-memory store that follows the same rules as SQLite. */
class OsintWorkspaceTest {

    private fun item(pid: Long, ts: Long, target: String, vararg f: Finding) =
        Item(0, pid, ts, "dns", target, "DNS records - $target", "https://dns.google/x", f.toList())

    @Test fun projectNamesAreUniqueAndNotEmpty() {
        val s = MemWorkspaceStore()
        assertTrue(s.createProject("Company X", 1L) >= 0)
        assertEquals(-1L, s.createProject("company x", 2L))
        assertEquals(-1L, s.createProject("   ", 3L))
        assertEquals(1, s.projects().size)
    }

    @Test fun itemsStayInTheirProject() {
        val s = MemWorkspaceStore()
        val p1 = s.createProject("One", 1L); val p2 = s.createProject("Two", 2L)
        s.addItem(item(p1, 10L, "a.com", Finding("A", "1.2.3.4", Ev.VERIFIED, "https://dns")))
        s.addItem(item(p2, 11L, "b.com", Finding("A", "5.6.7.8", Ev.VERIFIED, "https://dns")))
        assertEquals(listOf("a.com"), s.items(p1).map { it.target })
        assertEquals(listOf("b.com"), s.items(p2).map { it.target })
    }

    @Test fun addingToMissingProjectFails() {
        val s = MemWorkspaceStore()
        assertEquals(-1L, s.addItem(item(99L, 1L, "a.com")))
        assertEquals(-1L, s.addNote(99L, 1L, "hello"))
    }

    @Test fun deletingAnItemNeedsItsOwnProject() {
        val s = MemWorkspaceStore()
        val p1 = s.createProject("One", 1L); val p2 = s.createProject("Two", 2L)
        val id = s.addItem(item(p1, 10L, "a.com"))
        assertFalse("another project must not delete it", s.deleteItem(p2, id))
        assertEquals(1, s.items(p1).size)
        assertTrue(s.deleteItem(p1, id))
        assertEquals(0, s.items(p1).size)
    }

    @Test fun deletingAProjectRemovesOnlyItsOwnData() {
        val s = MemWorkspaceStore()
        val p1 = s.createProject("One", 1L); val p2 = s.createProject("Two", 2L)
        s.addItem(item(p1, 10L, "a.com")); s.addItem(item(p2, 11L, "b.com"))
        s.addNote(p1, 5L, "note one"); s.addNote(p2, 6L, "note two")
        s.deleteProject(p1)
        assertEquals(0, s.items(p1).size)
        assertEquals(0, s.notes(p1).size)
        assertEquals(1, s.items(p2).size)
        assertEquals(1, s.notes(p2).size)
        assertEquals(listOf("Two"), s.projects().map { it.name })
    }

    @Test fun historicalResultsAreNeverOverwritten() {
        val s = MemWorkspaceStore()
        val p = s.createProject("P", 1L)
        s.addItem(item(p, 10L, "a.com", Finding("A", "1.1.1.1", Ev.VERIFIED)))
        s.addItem(item(p, 20L, "a.com", Finding("A", "2.2.2.2", Ev.VERIFIED)))
        assertEquals(2, s.items(p).size)
        assertEquals(listOf(20L, 10L), s.items(p).map { it.ts })
    }

    @Test fun notesRejectBlankText() {
        val s = MemWorkspaceStore()
        val p = s.createProject("P", 1L)
        assertEquals(-1L, s.addNote(p, 1L, "   "))
        assertTrue(s.addNote(p, 2L, " real note ") >= 0)
        assertEquals("real note", s.notes(p)[0].text)
    }

    @Test fun comparesTwoObservations() {
        val older = item(1L, 10L, "a.com",
            Finding("A", "1.2.3.4", Ev.VERIFIED), Finding("MX", "mx1", Ev.VERIFIED), Finding("TXT", "old", Ev.VERIFIED))
        val newer = item(1L, 20L, "a.com",
            Finding("A", "1.2.3.4", Ev.VERIFIED), Finding("TXT", "new", Ev.VERIFIED), Finding("SOA", "x", Ev.VERIFIED))
        val d = compareItems(older, newer)
        assertEquals(listOf("SOA"), d.added)
        assertEquals(listOf("MX"), d.removed)
        assertEquals(1, d.changed.size)
        assertTrue(d.changed[0].startsWith("TXT"))
        assertEquals(1, d.unchanged)
        assertTrue(d.notComparable.isEmpty())
    }

    @Test fun failedLookupIsNotReadAsRemoval() {
        val older = item(1L, 10L, "a.com", Finding("MX", "mx1", Ev.VERIFIED))
        val newer = item(1L, 20L, "a.com", Finding("MX", "lookup failed", Ev.UNAVAILABLE))
        val d = compareItems(older, newer)
        assertTrue("a failed lookup must not be reported as removed", d.removed.isEmpty())
        assertEquals(listOf("MX"), d.notComparable)
    }

    @Test fun exportsMarkdownWithSourcesAndNotes() {
        val s = MemWorkspaceStore()
        val p = s.createProject("Test case", 1L)
        s.addItem(item(p, 10L, "a.com", Finding("A", "1.2.3.4", Ev.VERIFIED, "https://dns.google/x")))
        s.addNote(p, 11L, "checking the mail setup")
        val md = exportProjectMarkdown(s.projects()[0], s.items(p), s.notes(p)) { "t$it" }
        assertTrue(md.contains("# Investigation: Test case"))
        assertTrue(md.contains("[VERIFIED] **A:** 1.2.3.4 (https://dns.google/x)"))
        assertTrue(md.contains("checking the mail setup"))
        assertTrue(md.contains("**Source:** https://dns.google/x"))
    }
}
