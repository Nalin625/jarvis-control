package com.jarvis.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TrackStats and describeLink. Pure Kotlin: no phone, camera or laptop is needed.
 * These are simulated tests. They check the counting, not the real link. See REAL_CAMERA_TEST.md.
 *
 * Not compiled or run in the sandbox that wrote this (no Kotlin compiler or Gradle dependency access there).
 * Run with:  ./gradlew testDebugUnitTest
 */
class TrackStatsTest {

    @Test
    fun emptyStatsReportNothingYet() {
        val s = TrackStats().summary()
        assertEquals(0.0, s.sentPerSecond, 0.0)
        assertNull(s.roundTripMs)
        assertNull(s.detectionPercent)
        assertEquals(0L, s.timeouts)
        assertEquals("no packets yet · no reply yet · 0 timeouts", describeLink(s))
    }

    @Test
    fun detectionIsTheShareOfFramesWithAHand() {
        val t = TrackStats()
        t.onFrame(true)
        t.onFrame(true)
        t.onFrame(false)
        assertEquals(66, t.summary().detectionPercent)
    }

    @Test
    fun busyDropsAreCounted() {
        val t = TrackStats()
        t.onBusyDrop()
        t.onBusyDrop()
        assertEquals(2L, t.summary().busyDrops)
        assertTrue(describeLink(t.summary()).contains("2 frames skipped while waiting"))
    }

    @Test
    fun sendRateFromSendTimes() {
        val t = TrackStats()
        for (ms in 0L..1000L step 100L) t.onSent(ms)      // 11 sends across one second
        assertEquals(10.0, t.summary().sentPerSecond, 0.01)
    }

    @Test
    fun roundTripIsTheMedianOfReplies() {
        val t = TrackStats()
        t.onReply(10, null)
        t.onReply(90, null)
        t.onReply(50, null)
        assertEquals(50L, t.summary().roundTripMs)
    }

    @Test
    fun timeoutsAndUnreachableAreCountedSeparately() {
        val t = TrackStats()
        t.onReply(1500, "timeout")
        t.onReply(3, "unreachable")
        t.onReply(3, "bad_reply")
        val s = t.summary()
        assertEquals(1L, s.timeouts)
        assertEquals(3L, s.noReply)
        assertNull("no good reply, so no round trip", s.roundTripMs)
    }

    @Test
    fun serverErrorStillCountsAsAnAnsweredPacket() {
        val t = TrackStats()
        t.onReply(40, "server")
        val s = t.summary()
        assertEquals(0L, s.timeouts)
        assertEquals(40L, s.roundTripMs)
    }

    @Test
    fun diagCarriesTheCountersTheLaptopExpects() {
        val t = TrackStats()
        t.onFrame(true)
        t.onSent(0L)
        t.onBusyDrop()
        t.onReply(1500, "timeout")
        val d = t.diag()
        assertEquals(1L, d["sent"])
        assertEquals(1L, d["frames"])
        assertEquals(1L, d["with_hand"])
        assertEquals(1L, d["busy"])
        assertEquals(1L, d["timeouts"])
        assertEquals(0L, d["unreachable"])
        assertEquals(0L, d["bad_reply"])
        assertEquals(1L, d["unanswered"])
        assertFalse("no round trip yet, so no rtt_ms key", d.containsKey("rtt_ms"))
    }

    @Test
    fun diagIncludesRoundTripOnceThereIsOne() {
        val t = TrackStats()
        t.onReply(42, null)
        assertEquals(42L, t.diag()["rtt_ms"])
    }

    @Test
    fun resetClearsEverything() {
        val t = TrackStats()
        t.onFrame(true)
        t.onSent(0L)
        t.onReply(10, null)
        t.reset()
        val s = t.summary()
        assertNull(s.detectionPercent)
        assertNull(s.roundTripMs)
        assertEquals(0.0, s.sentPerSecond, 0.0)
    }

    @Test
    fun singularTimeoutWording() {
        val t = TrackStats()
        t.onReply(1500, "timeout")
        assertTrue(describeLink(t.summary()).contains("1 timeout"))
        assertFalse(describeLink(t.summary()).contains("1 timeouts"))
    }

    @Test
    fun rateUsesDecimalPointWhateverTheLocale() {
        val t = TrackStats()
        t.onSent(0L)
        t.onSent(150L)
        t.onSent(300L)
        assertTrue(describeLink(t.summary()).startsWith("6.7 packets a second"))
    }
}
