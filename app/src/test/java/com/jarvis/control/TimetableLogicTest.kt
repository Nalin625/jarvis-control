package com.jarvis.control

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home-screen widget's logic for today's blocks. Pure functions: no phone, laptop or Android needed. */
class TimetableLogicTest {

    private fun block(id: Int, title: String, start: String, end: String, done: Boolean = false): JSONObject =
        JSONObject().put("id", id).put("title", title).put("start", start).put("end", end).put("done", done)

    private val morning = block(1, "Study block 1", "09:30", "11:00")
    private val gym = block(2, "Gym", "18:00", "19:00")
    private val dinner = block(3, "Dinner", "20:00", "20:45")

    @Test
    fun todayIsAnIsoDate() {
        assertTrue(TimetableCache.today().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun minutesOfReadsHoursAndMinutes() {
        assertEquals(570, TimetableCache.minutesOf("09:30"))
        assertEquals(425, TimetableCache.minutesOf(" 07:05 "))
        assertEquals(1439, TimetableCache.minutesOf("23:59"))
    }

    @Test
    fun minutesOfRejectsBadTimes() {
        assertEquals(-1, TimetableCache.minutesOf(""))
        assertEquals(-1, TimetableCache.minutesOf("24:00"))
        assertEquals(-1, TimetableCache.minutesOf("9:60"))
        assertEquals(-1, TimetableCache.minutesOf("noon"))
        assertEquals(-1, TimetableCache.minutesOf("10"))
    }

    @Test
    fun nextForSkipsFinishedAndDoneBlocks() {
        assertSame(gym, TimetableCache.nextFor(listOf(morning, gym, dinner), 12 * 60))
        // at exactly 19:00 the gym has ended (its end is not after now), so dinner is next
        assertSame(dinner, TimetableCache.nextFor(listOf(morning, gym, dinner), 19 * 60))
        val doneGym = block(2, "Gym", "18:00", "19:00", done = true)
        assertSame(dinner, TimetableCache.nextFor(listOf(morning, doneGym, dinner), 17 * 60))
        assertNull(TimetableCache.nextFor(listOf(morning, gym), 23 * 60))
    }

    @Test
    fun emptyDayAsksForAnOpen() {
        assertArrayEquals(
            arrayOf("TODAY", "No plan saved yet", "Open Jarvis Control while the laptop is on"),
            TimetableCache.widgetLines(emptyList(), 600)
        )
    }

    @Test
    fun showsNowWhileABlockRuns() {
        assertArrayEquals(
            arrayOf("NOW", "Study block 1", "until 11:00"),
            TimetableCache.widgetLines(listOf(morning, gym), 10 * 60)
        )
    }

    @Test
    fun showsNextBeforeABlockStarts() {
        assertArrayEquals(
            arrayOf("NEXT", "Gym", "starts at 18:00"),
            TimetableCache.widgetLines(listOf(morning, gym), 12 * 60)
        )
    }

    @Test
    fun saysAllDoneWhenEveryBlockIsTicked() {
        val a = block(1, "Study block 1", "09:30", "11:00", done = true)
        val b = block(2, "Gym", "18:00", "19:00", done = true)
        assertArrayEquals(
            arrayOf("TODAY", "All blocks done", "2 of 2 ticked"),
            TimetableCache.widgetLines(listOf(a, b), 23 * 60)
        )
    }

    @Test
    fun saysNothingElseWhenSomeAreLeftButNoneLaterToday() {
        val gymDone = block(2, "Gym", "18:00", "19:00", done = true)
        assertArrayEquals(
            arrayOf("TODAY", "Nothing else today", "1 of 2 ticked"),
            TimetableCache.widgetLines(listOf(morning, gymDone), 23 * 60)
        )
    }

    @Test
    fun nextChangeIsTheNextStartOrEnd() {
        assertEquals(660, TimetableCache.nextChangeMinute(listOf(morning, gym, dinner), 600))   // morning ends 11:00
        assertEquals(1080, TimetableCache.nextChangeMinute(listOf(morning, gym, dinner), 660))  // gym starts 18:00
        assertNull(TimetableCache.nextChangeMinute(listOf(morning), 23 * 60))
    }

    @Test
    fun blocksWithBadTimesAreIgnored() {
        val broken = block(4, "Broken", "", "")
        assertEquals(660, TimetableCache.nextChangeMinute(listOf(broken, morning), 600))
        assertSame(morning, TimetableCache.nextFor(listOf(broken, morning), 600))
    }
}
