package com.jarvis.control.instagramtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerLogicTest {

    // ---------------------------------------------------------------- username normalization
    @Test fun normalizeStripsSpacesAndOneAt() {
        assertEquals("alpha.beta_1", TrackerLogic.normalizeUsername("  @alpha.beta_1  "))
        assertEquals("plain", TrackerLogic.normalizeUsername("plain"))
    }

    @Test fun normalizeRejectsBadFormats() {
        assertNull(TrackerLogic.normalizeUsername(""))
        assertNull(TrackerLogic.normalizeUsername("@"))
        assertNull(TrackerLogic.normalizeUsername("has space"))
        assertNull(TrackerLogic.normalizeUsername("bad-dash"))
        assertNull(TrackerLogic.normalizeUsername("double..dot"))
        assertNull(TrackerLogic.normalizeUsername("@@twice"))
        assertNull(TrackerLogic.normalizeUsername("a".repeat(31)))
        assertNull(TrackerLogic.normalizeUsername("https://instagram.com/x"))
    }

    @Test fun normalizeAcceptsLengthLimit() {
        assertEquals("a".repeat(30), TrackerLogic.normalizeUsername("a".repeat(30)))
    }

    // ---------------------------------------------------------------- parsing lists
    @Test fun parseRemovesEmptyLinesAndDuplicatesAndKeepsFirstSpelling() {
        val p = TrackerLogic.parseUsernames("Alice\n\n  @alice  \nBob\n")
        assertEquals(listOf("Alice", "Bob"), p.names)
        assertEquals(1, p.duplicates)
        assertEquals(2, p.skipped) // empty line and trailing empty line
        assertTrue(p.invalid.isEmpty())
    }

    @Test fun parseReportsInvalidLinesWithoutStoringThem() {
        val p = TrackerLogic.parseUsernames("good\nbad name\nalso-bad\nok_1")
        assertEquals(listOf("good", "ok_1"), p.names)
        assertEquals(listOf("bad name", "also-bad"), p.invalid)
    }

    @Test fun parseCsvTakesFirstColumnAndSkipsHeader() {
        val csv = "username,notes\nalpha,first\n\"beta\",second\n@gamma,third"
        assertEquals(listOf("alpha", "beta", "gamma"), TrackerLogic.parseUsernames(csv).names)
    }

    @Test fun parseDuplicatesIgnoreCase() {
        val p = TrackerLogic.parseUsernames("Ann\nANN\nann\nBen")
        assertEquals(listOf("Ann", "Ben"), p.names)
        assertEquals(2, p.duplicates)
    }

    @Test fun parseEmptyTextGivesEmptyList() {
        val p = TrackerLogic.parseUsernames("")
        assertTrue(p.names.isEmpty())
        assertTrue(p.invalid.isEmpty())
        val blank = TrackerLogic.parseUsernames("\n   \n\n")
        assertTrue(blank.names.isEmpty())
    }

    @Test fun parseLargeListKeepsEveryDistinctName() {
        val text = (1..5000).joinToString("\n") { "user_$it" } + "\n" + (1..5000).joinToString("\n") { "USER_$it" }
        val p = TrackerLogic.parseUsernames(text)
        assertEquals(5000, p.names.size)
        assertEquals(5000, p.duplicates)
    }

    // ---------------------------------------------------------------- comparison
    @Test fun specExampleAddedRemovedUnchanged() {
        val d = TrackerLogic.compareLists(listOf("A", "B", "C"), listOf("A", "C", "D"))
        assertEquals(listOf("D"), d.added)
        assertEquals(listOf("B"), d.removed)
        assertEquals(listOf("A", "C"), d.unchanged)
    }

    @Test fun bothListsEmpty() {
        val d = TrackerLogic.compareLists(emptyList(), emptyList())
        assertTrue(d.added.isEmpty() && d.removed.isEmpty() && d.unchanged.isEmpty())
    }

    @Test fun oneListEmpty() {
        val onlyNew = TrackerLogic.compareLists(emptyList(), listOf("x", "y"))
        assertEquals(listOf("x", "y"), onlyNew.added)
        assertTrue(onlyNew.removed.isEmpty())
        val onlyOld = TrackerLogic.compareLists(listOf("x", "y"), emptyList())
        assertEquals(listOf("x", "y"), onlyOld.removed)
        assertTrue(onlyOld.added.isEmpty())
    }

    @Test fun comparisonIsCaseInsensitiveAndKeepsDisplaySpelling() {
        val d = TrackerLogic.compareLists(listOf("Alice"), listOf("alice", "BOB"))
        assertTrue(d.removed.isEmpty())
        assertEquals(listOf("alice"), d.unchanged)
        assertEquals(listOf("BOB"), d.added)
    }

    @Test fun duplicatesInInputDoNotCreateExtraRows() {
        val d = TrackerLogic.compareLists(listOf("a", "a", "A"), listOf("a"))
        assertEquals(listOf("a"), d.unchanged)
        assertTrue(d.removed.isEmpty())
    }

    @Test fun outputIsSortedCaseInsensitively() {
        val d = TrackerLogic.compareLists(emptyList(), listOf("zed", "Amy", "bob"))
        assertEquals(listOf("Amy", "bob", "zed"), d.added)
    }

    @Test fun identicalListsAreAllUnchanged() {
        val d = TrackerLogic.compareLists(listOf("a", "b"), listOf("b", "a"))
        assertEquals(listOf("a", "b"), d.unchanged)
        assertTrue(d.added.isEmpty() && d.removed.isEmpty())
    }

    @Test fun largeComparisonCountsAreRight() {
        val old = (1..10000).map { "u$it" }
        val new = (5001..15000).map { "u$it" }
        val d = TrackerLogic.compareLists(old, new)
        assertEquals(5000, d.removed.size)
        assertEquals(5000, d.added.size)
        assertEquals(5000, d.unchanged.size)
    }

    // ---------------------------------------------------------------- counts
    @Test fun countDifferences() {
        assertEquals(15, TrackerLogic.countDiff(300, 315))
        assertEquals(-5, TrackerLogic.countDiff(10, 5))
        assertEquals(0, TrackerLogic.countDiff(7, 7))
        assertNull(TrackerLogic.countDiff(null, 5))
        assertNull(TrackerLogic.countDiff(5, null))
    }

    @Test fun parseCountAcceptsOnlyWholeNonNegativeNumbers() {
        assertEquals(0, TrackerLogic.parseCount("0"))
        assertEquals(315, TrackerLogic.parseCount(" 315 "))
        assertNull(TrackerLogic.parseCount("-1"))
        assertNull(TrackerLogic.parseCount("1.5"))
        assertNull(TrackerLogic.parseCount("1,000"))
        assertNull(TrackerLogic.parseCount("abc"))
        assertNull(TrackerLogic.parseCount(""))
        assertNull(TrackerLogic.parseCount("1234567890")) // more than 9 digits
    }

    // ---------------------------------------------------------------- dates
    @Test fun dateRoundTripAndStrictness() {
        val ts = TrackerLogic.parseDateTime("2026-11-28 09:30")
        assertTrue(ts != null)
        assertEquals("2026-11-28 09:30", TrackerLogic.formatDateTime(ts!!))
        assertNull(TrackerLogic.parseDateTime("2026-02-30 10:00"))
        assertNull(TrackerLogic.parseDateTime("28/11/2026 09:30"))
        assertNull(TrackerLogic.parseDateTime("2026-11-28"))
        assertNull(TrackerLogic.parseDateTime("nonsense"))
    }

    // ---------------------------------------------------------------- duplicates and warnings
    private fun snap(ts: Long, names: List<String> = listOf("a"), f: Int? = 1, g: Int? = 2, kind: String = KIND_LIST, cat: String = CAT_FOLLOWERS) =
        Snapshot(0, 1, ts, kind, cat, f, g, names, SRC_MANUAL, "")

    @Test fun duplicateDetectedWithinWindow() {
        val existing = listOf(snap(1_000_000))
        assertTrue(TrackerLogic.isDuplicate(existing, snap(1_000_000 + 30_000)))
    }

    @Test fun notDuplicateAfterWindowOrDifferentContent() {
        val existing = listOf(snap(1_000_000))
        assertFalse(TrackerLogic.isDuplicate(existing, snap(1_000_000 + 120_000)))
        assertFalse(TrackerLogic.isDuplicate(existing, snap(1_000_000, names = listOf("b"))))
        assertFalse(TrackerLogic.isDuplicate(existing, snap(1_000_000, cat = CAT_FOLLOWING)))
    }

    @Test fun countWarningsNameMissingCounts() {
        assertEquals(2, TrackerLogic.countWarnings(null, null).size)
        assertTrue(TrackerLogic.countWarnings(4, 5).isEmpty())
    }

    @Test fun listWarningsCoverIncompleteSnapshots() {
        assertTrue(TrackerLogic.listWarnings(0, 0, 0, null, SRC_MANUAL).any { it.contains("no usernames") })
        assertTrue(TrackerLogic.listWarnings(3, 2, 1, null, SRC_MANUAL).any { it.contains("not valid") })
        assertTrue(TrackerLogic.listWarnings(3, 0, 0, 10, SRC_MANUAL).any { it.contains("may be incomplete") })
        assertTrue(TrackerLogic.listWarnings(3, 0, 0, null, SRC_FILE).any { it.contains("file") })
        assertTrue(TrackerLogic.listWarnings(3, 0, 0, 3, SRC_MANUAL).isEmpty())
    }

    // ---------------------------------------------------------------- report text
    @Test fun reportContainsDisclaimerLabelsDatesAndCounts() {
        val older = Snapshot(1, 1, TrackerLogic.parseDateTime("2026-11-01 10:00")!!, KIND_LIST, CAT_FOLLOWERS, null, null, listOf("A", "B", "C"), SRC_MANUAL, "")
        val newer = Snapshot(2, 1, TrackerLogic.parseDateTime("2026-11-08 10:00")!!, KIND_LIST, CAT_FOLLOWERS, null, null, listOf("A", "C", "D"), SRC_FILE, "")
        val diff = TrackerLogic.compareLists(older.usernames, newer.usernames)
        val report = TrackerLogic.comparisonReport("My account", older, newer, diff, listOf("w1"), 0L, CAT_FOLLOWERS)
        assertTrue(report.contains("Account: My account"))
        assertTrue(report.contains("2026-11-01 10:00"))
        assertTrue(report.contains("2026-11-08 10:00"))
        assertTrue(report.contains("ADDED USERNAMES (1)"))
        assertTrue(report.contains("REMOVED USERNAMES (1)"))
        assertTrue(report.contains("UNCHANGED USERNAMES (2)"))
        assertTrue(report.contains("COMPLETENESS WARNINGS"))
        assertTrue(report.contains("w1"))
        assertTrue(report.contains(TRACKER_DISCLAIMER))
    }


    @Test fun listSizeLimitIsEnforced() {
        assertFalse(TrackerLogic.listTooLarge(TrackerLogic.MAX_LIST_NAMES))
        assertTrue(TrackerLogic.listTooLarge(TrackerLogic.MAX_LIST_NAMES + 1))
        assertTrue(TrackerLogic.MAX_IMPORT_BYTES > 0)
    }

    @Test fun disclaimerTextIsExact() {
        assertEquals("Results represent differences between user-provided observations. They do not establish when or why an account changed its followers or following list.", TRACKER_DISCLAIMER)
    }
}
