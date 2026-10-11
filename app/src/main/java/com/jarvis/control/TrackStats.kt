package com.jarvis.control

import java.util.Locale

/**
 * The phone's own view of its link to the laptop: frames with a hand in view, packets sent, replies, round trips,
 * dropped frames, and why the rest failed. Pure Kotlin with no Android classes, so JUnit can test it on a computer.
 *
 * GestureService calls it from the camera thread and from network threads. Every method takes [lock].
 *
 * What it can and cannot say: a timeout means no reply came back in time. It does not say whether the packet was
 * lost on the way or the laptop was slow. The laptop's own sequence-gap count (jarvis_link_stats.py) is the loss figure.
 */
class TrackStats(private val rttWindow: Int = 100, private val rateWindow: Int = 50) {

    /** A short summary for the screen. */
    data class Summary(
        val sentPerSecond: Double,
        val roundTripMs: Long?,
        val timeouts: Long,
        val noReply: Long,
        val busyDrops: Long,
        val detectionPercent: Int?,
    )

    private val lock = Any()
    private var frames = 0L
    private var withHand = 0L
    private var sent = 0L
    private var busy = 0L
    private var answered = 0L
    private var timeouts = 0L
    private var unreachable = 0L
    private var badReply = 0L
    private var serverErrors = 0L
    private val rtts = ArrayDeque<Long>()
    private val sentAt = ArrayDeque<Long>()

    fun reset() {
        synchronized(lock) {
            frames = 0L; withHand = 0L; sent = 0L; busy = 0L; answered = 0L
            timeouts = 0L; unreachable = 0L; badReply = 0L; serverErrors = 0L
            rtts.clear()
            sentAt.clear()
        }
    }

    /** Called for every camera frame the hand detector has processed. */
    fun onFrame(hasHand: Boolean) {
        synchronized(lock) {
            frames++
            if (hasHand) withHand++
        }
    }

    /** Called when a frame is skipped because the previous packet is still on its way. */
    fun onBusyDrop() {
        synchronized(lock) { busy++ }
    }

    /** Called when a packet is handed to the network, with the time in milliseconds. */
    fun onSent(nowMs: Long) {
        synchronized(lock) {
            sent++
            sentAt.addLast(nowMs)
            if (sentAt.size > rateWindow) sentAt.removeFirst()
        }
    }

    /**
     * Called when a packet's reply, or its failure, comes back.
     * [errorKind] is null for a good reply, otherwise the kind LaptopApi reports: "timeout", "unreachable",
     * "bad_reply", "server" (the laptop answered with an error) or "not_paired".
     */
    fun onReply(rttMs: Long, errorKind: String?) {
        synchronized(lock) {
            when (errorKind) {
                null -> {
                    answered++
                    addRtt(rttMs)
                }
                "server" -> {
                    answered++
                    serverErrors++
                    addRtt(rttMs)
                }
                "timeout" -> timeouts++
                "bad_reply" -> badReply++
                else -> unreachable++          // "unreachable", "not_paired", or anything new
            }
        }
    }

    /** The counters sent to the laptop in each packet's "diag" field. Keys match DIAG_FIELDS in jarvis_link_stats.py. */
    fun diag(): Map<String, Any> = synchronized(lock) {
        val out = LinkedHashMap<String, Any>()
        out["sent"] = sent
        out["frames"] = frames
        out["with_hand"] = withHand
        out["busy"] = busy
        out["timeouts"] = timeouts
        out["unreachable"] = unreachable
        out["bad_reply"] = badReply
        out["unanswered"] = timeouts + unreachable + badReply
        val rtt = medianRttLocked()
        if (rtt != null) out["rtt_ms"] = rtt
        out
    }

    fun summary(): Summary = synchronized(lock) {
        Summary(
            sentPerSecond = rateLocked(),
            roundTripMs = medianRttLocked(),
            timeouts = timeouts,
            noReply = timeouts + unreachable + badReply,
            busyDrops = busy,
            detectionPercent = if (frames == 0L) null else (100 * withHand / frames).toInt(),
        )
    }

    private fun addRtt(ms: Long) {
        rtts.addLast(ms.coerceAtLeast(0L))
        if (rtts.size > rttWindow) rtts.removeFirst()
    }

    private fun medianRttLocked(): Long? {
        if (rtts.isEmpty()) return null
        val sorted = rtts.sorted()
        return sorted[sorted.size / 2]
    }

    private fun rateLocked(): Double {
        if (sentAt.size < 2) return 0.0
        val span = sentAt.last() - sentAt.first()
        return if (span <= 0L) 0.0 else (sentAt.size - 1) * 1000.0 / span
    }
}

/** One line for the screen, for example: "9.8 packets a second · 42 ms round trip · 0 timeouts · hand seen in 88% of frames". */
fun describeLink(s: TrackStats.Summary): String {
    val parts = ArrayList<String>()
    parts += if (s.sentPerSecond <= 0.0) "no packets yet"
             else String.format(Locale.US, "%.1f packets a second", s.sentPerSecond)
    parts += s.roundTripMs?.let { "$it ms round trip" } ?: "no reply yet"
    parts += if (s.timeouts == 1L) "1 timeout" else "${s.timeouts} timeouts"
    s.detectionPercent?.let { parts += "hand seen in $it% of frames" }
    if (s.busyDrops > 0) parts += "${s.busyDrops} frames skipped while waiting"
    return parts.joinToString(" · ")
}
