package com.jarvis.control.instagramtracker

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Screen-recording import: pure logic. The screen samples frames on this phone and runs ML Kit on them. The text of each
 * frame is then read here with the same rules as screenshots (TrackerOcr). Nothing here stores, uploads or logs usernames.
 */
const val SRC_VIDEO_OCR = "video_ocr"

/** One sampled frame. [text] is null when the frame could not be read. */
data class FrameText(val timeMs: Long, val text: String?)

/** [entries] hold 1-based frame numbers in [OcrEntry.shots]; use [TrackerVideo.frameTimes] to turn them into times. */
data class VideoReview(
    val entries: List<OcrEntry>,
    val rejected: List<String>,
    val repeats: Int,
    val emptyFrames: List<Long>,
    val unreadFrames: Int,
)

object TrackerVideo {
    const val MAX_VIDEO_BYTES = 300L * 1024 * 1024
    const val MAX_DURATION_MS = 10L * 60_000L
    const val MAX_FRAMES = 300
    const val DEFAULT_INTERVAL_MS = 1_000L

    /** Longest side of a frame kept for OCR. Text on phone screens stays readable at this size. */
    const val FRAME_MAX_SIDE = 1600

    /**
     * Size to decode a frame at so that its longest side is at most [maxSide]. The metadata rotation is applied first,
     * because the frame that comes out is upright. Null when the video size is unknown, so the caller decodes normally.
     */
    fun scaledSize(width: Int, height: Int, rotationDeg: Int, maxSide: Int = FRAME_MAX_SIDE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || maxSide <= 0) return null
        val swap = rotationDeg == 90 || rotationDeg == 270
        val w = if (swap) height else width
        val h = if (swap) width else height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return w to h
        val k = maxSide.toDouble() / longest
        return (w * k).roundToInt().coerceAtLeast(1) to (h * k).roundToInt().coerceAtLeast(1)
    }

    /** One frame every second, or less often when a long video would exceed [MAX_FRAMES]. */
    fun intervalMs(durationMs: Long): Long = maxOf(DEFAULT_INTERVAL_MS, (durationMs + MAX_FRAMES - 1) / MAX_FRAMES)

    /** Frame times from 0 up to the end of the video, at [intervalMs]. Never more than [MAX_FRAMES]. */
    fun timestampsMs(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return emptyList()
        val step = intervalMs(durationMs)
        val out = ArrayList<Long>()
        var t = 0L
        while (t < durationMs && out.size < MAX_FRAMES) {
            out.add(t)
            t += step
        }
        return out
    }

    /** Null when the video can be read, otherwise the reason it cannot. An unknown size (-1) is not refused. */
    fun problem(durationMs: Long, sizeBytes: Long, mime: String?): String? = when {
        mime == null || !mime.startsWith("video/") -> "This file is not a video format Android can read here. Try MP4."
        durationMs <= 0L -> "Could not read the length of this video."
        durationMs > MAX_DURATION_MS -> "This video is longer than 10 minutes. Trim it and try again."
        sizeBytes > MAX_VIDEO_BYTES -> "This video is larger than 300 MB. Trim it and try again."
        else -> null
    }

    fun formatTime(ms: Long): String {
        val s = maxOf(0L, ms) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** Times of the frames an entry was seen in, sorted. Frame numbers outside the list are ignored. */
    fun frameTimes(shots: List<Int>, frames: List<FrameText>): List<Long> =
        shots.mapNotNull { frames.getOrNull(it - 1)?.timeMs }.sorted()

    /** "0:03, 0:04, +2 more" style label for the review screen. */
    fun timeLabels(shots: List<Int>, frames: List<FrameText>, max: Int): String {
        val times = frameTimes(shots, frames)
        val shown = times.take(max).joinToString(", ") { formatTime(it) }
        return if (times.size > max) "$shown, +${times.size - max} more" else shown
    }

    /**
     * Reads every sampled frame with the screenshot rules and merges the same username across frames.
     * [frames] must be in time order; frame numbers are their 1-based positions.
     */
    fun review(frames: List<FrameText>): VideoReview {
        val cands = ArrayList<OcrCandidate>()
        val rejected = LinkedHashSet<String>()
        val empty = ArrayList<Long>()
        var unread = 0
        for ((i, f) in frames.withIndex()) {
            val text = f.text
            if (text == null) { unread++; continue }
            if (text.isBlank()) { empty.add(f.timeMs); continue }
            val p = TrackerOcr.parse(text, i + 1)
            cands.addAll(p.candidates)
            rejected.addAll(p.rejected)
        }
        val entries = TrackerOcr.aggregate(cands)
        return VideoReview(entries, rejected.toList(), TrackerOcr.repeats(cands, entries), empty, unread)
    }
}
