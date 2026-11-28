package com.jarvis.control.instagramtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerVideoTest {

    // ---------------------------------------------------------------- timestamp sampling
    @Test fun noFramesForZeroOrNegativeDuration() {
        assertTrue(TrackerVideo.timestampsMs(0L).isEmpty())
        assertTrue(TrackerVideo.timestampsMs(-5L).isEmpty())
    }

    @Test fun samplesEverySecondForShortVideo() {
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L), TrackerVideo.timestampsMs(5_000L))
    }

    @Test fun partialLastSecondStillSampled() {
        assertEquals(listOf(0L, 1000L), TrackerVideo.timestampsMs(1_500L))
    }

    @Test fun longVideoStretchesIntervalButStaysUnderFrameCap() {
        val d = TrackerVideo.MAX_DURATION_MS
        assertEquals(2000L, TrackerVideo.intervalMs(d))
        val ts = TrackerVideo.timestampsMs(d)
        assertEquals(300, ts.size)
        assertEquals(0L, ts.first())
        assertTrue(ts.last() < d)
    }

    @Test fun frameCountNeverExceedsCapForAnyDuration() {
        for (d in listOf(1L, 999L, 60_000L, 600_001L, 601_234L, 3_600_000L)) {
            assertTrue("duration $d", TrackerVideo.timestampsMs(d).size <= TrackerVideo.MAX_FRAMES)
        }
    }

    @Test fun timestampsAreStrictlyIncreasing() {
        val ts = TrackerVideo.timestampsMs(123_456L)
        for (i in 1 until ts.size) assertTrue(ts[i] > ts[i - 1])
    }

    // ---------------------------------------------------------------- safeguards
    @Test fun acceptsReasonableVideo() {
        assertNull(TrackerVideo.problem(30_000L, 5_000_000L, "video/mp4"))
        assertNull(TrackerVideo.problem(30_000L, -1L, "video/mp4"))
    }

    @Test fun refusesUnsupportedOrUnreadable() {
        assertTrue(TrackerVideo.problem(30_000L, 1L, null) != null)
        assertTrue(TrackerVideo.problem(30_000L, 1L, "audio/mpeg") != null)
        assertTrue(TrackerVideo.problem(0L, 1L, "video/mp4") != null)
    }

    @Test fun refusesTooLongOrTooLarge() {
        assertTrue(TrackerVideo.problem(TrackerVideo.MAX_DURATION_MS + 1, 1L, "video/mp4")!!.contains("10 minutes"))
        assertTrue(TrackerVideo.problem(1_000L, TrackerVideo.MAX_VIDEO_BYTES + 1, "video/mp4")!!.contains("300 MB"))
        assertNull(TrackerVideo.problem(TrackerVideo.MAX_DURATION_MS, TrackerVideo.MAX_VIDEO_BYTES, "video/mp4"))
    }

    // ---------------------------------------------------------------- labels
    @Test fun formatsTimes() {
        assertEquals("0:00", TrackerVideo.formatTime(0L))
        assertEquals("0:03", TrackerVideo.formatTime(3_400L))
        assertEquals("1:05", TrackerVideo.formatTime(65_000L))
        assertEquals("1:02:03", TrackerVideo.formatTime(3_723_000L))
    }

    @Test fun timeLabelsShortenLongLists() {
        val frames = (0 until 12).map { FrameText(it * 1000L, "x") }
        assertEquals("0:00, 0:01", TrackerVideo.timeLabels(listOf(1, 2), frames, 8))
        assertEquals("0:00, 0:01, 0:02, 0:03, 0:04, 0:05, 0:06, 0:07, +4 more", TrackerVideo.timeLabels((1..12).toList(), frames, 8))
    }

    // ---------------------------------------------------------------- frame review and merging
    @Test fun sameUsernameAcrossFramesMergesAndKeepsTimes() {
        val frames = listOf(
            FrameText(0L, "alice\nbob"),
            FrameText(1000L, "alice\ncarol"),
            FrameText(2000L, "Alice"),
        )
        val r = TrackerVideo.review(frames)
        assertEquals(listOf("alice", "bob", "carol"), r.entries.map { it.name })
        val alice = r.entries[0]
        assertEquals(listOf(0L, 1000L, 2000L), TrackerVideo.frameTimes(alice.shots, frames))
        assertEquals(2, r.repeats)
        assertTrue(r.entries.none { it.uncertain })
    }

    @Test fun emptyAndUnreadFramesAreCountedNotGuessed() {
        val frames = listOf(
            FrameText(0L, ""),
            FrameText(1000L, null),
            FrameText(2000L, "   \n  "),
            FrameText(3000L, "dave"),
        )
        val r = TrackerVideo.review(frames)
        assertEquals(listOf("dave"), r.entries.map { it.name })
        assertEquals(listOf(0L, 2000L), r.emptyFrames)
        assertEquals(1, r.unreadFrames)
        assertEquals(listOf(3000L), TrackerVideo.frameTimes(r.entries[0].shots, frames))
    }

    @Test fun noFramesGivesEmptyReview() {
        val r = TrackerVideo.review(emptyList())
        assertTrue(r.entries.isEmpty() && r.rejected.isEmpty() && r.emptyFrames.isEmpty())
        assertEquals(0, r.unreadFrames)
    }

    @Test fun rejectedLinesAreListedOnceAcrossFrames() {
        val frames = listOf(FrameText(0L, "John Smith\nalice"), FrameText(1000L, "John Smith\nalice"))
        val r = TrackerVideo.review(frames)
        assertEquals(listOf("John Smith"), r.rejected)
        assertEquals(1, r.repeats)
    }

    @Test fun uncertainReadingInOneFrameIsStillFlagged() {
        val frames = listOf(FrameText(0L, "ab"), FrameText(1000L, "ab"))
        val e = TrackerVideo.review(frames).entries.single()
        assertTrue(e.uncertain)
    }

    @Test fun sourceConstantIsVideoOcr() {
        assertEquals("video_ocr", SRC_VIDEO_OCR)
    }

    @Test fun largeRecordingReviewsWithoutLoss() {
        val frames = (0 until TrackerVideo.MAX_FRAMES).map { FrameText(it * 2000L, (1..20).joinToString("\n") { n -> "user_${it}_$n" }) }
        val r = TrackerVideo.review(frames)
        assertEquals(TrackerVideo.MAX_FRAMES * 20, r.entries.size)
    }

    // ---------------------------------------------------------------- memory: target frame size
    @Test fun fourKLandscapeScalesToSixteenHundredLongSide() {
        assertEquals(1600 to 900, TrackerVideo.scaledSize(3840, 2160, 0))
    }

    @Test fun rotatedFourKPortraitScalesUpright() {
        // stored as 3840x2160 but recorded rotated 90 degrees: the upright frame is 2160x3840
        assertEquals(900 to 1600, TrackerVideo.scaledSize(3840, 2160, 90))
        assertEquals(900 to 1600, TrackerVideo.scaledSize(3840, 2160, 270))
    }

    @Test fun smallFramesKeepTheirSize() {
        assertEquals(720 to 1280, TrackerVideo.scaledSize(720, 1280, 0))
        assertEquals(1600 to 900, TrackerVideo.scaledSize(1600, 900, 0))
    }

    @Test fun unknownOrInvalidSizeGivesNull() {
        assertNull(TrackerVideo.scaledSize(0, 0, 0))
        assertNull(TrackerVideo.scaledSize(-1, 1080, 0))
        assertNull(TrackerVideo.scaledSize(1080, 1920, 0, maxSide = 0))
    }

    @Test fun extremeAspectNeverRoundsToZero() {
        val (w, h) = TrackerVideo.scaledSize(10_000, 10, 0)!!
        assertEquals(1600, w)
        assertTrue(h >= 1)
    }

    @Test fun scaledSizeNeverExceedsLimit() {
        for ((w, hgt, r) in listOf(Triple(3840, 2160, 0), Triple(2160, 3840, 90), Triple(7680, 4320, 0), Triple(1080, 2400, 270))) {
            val (sw, sh) = TrackerVideo.scaledSize(w, hgt, r)!!
            assertTrue(maxOf(sw, sh) <= TrackerVideo.FRAME_MAX_SIDE)
        }
    }
}
