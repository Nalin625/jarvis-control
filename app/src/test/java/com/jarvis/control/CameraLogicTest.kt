package com.jarvis.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Virtual camera: synthetic frames (no real camera, no phone) run through the same scene classifier and
 * exposure planner the app uses. Each test builds a fake scene from brightness values and checks the decision.
 * Runs on the JVM in the "Phone unit tests" workflow.
 */
class CameraLogicTest {

    /** A tiny grayscale frame: 'share' of pixels at 'value', the rest at 'other'. */
    private fun frame(value: Int, other: Int = value, share: Double = 1.0, size: Int = 1000): IntArray {
        val px = IntArray(size)
        val n = (share * size).toInt()
        for (i in 0 until size) px[i] = if (i < n) value else other
        return px
    }

    /** Synthetic scene: a mix of dark, mid and bright pixels, like a real photo. */
    private fun randomScene(rng: Random): IntArray {
        val dark = rng.nextDouble()          // share of deep shadow
        val bright = rng.nextDouble() * 0.2  // share of near-white highlights
        val base = rng.nextInt(256)          // overall level
        val px = IntArray(1000)
        for (i in px.indices) {
            val r = rng.nextDouble()
            px[i] = when {
                r < dark * 0.5 -> rng.nextInt(21)                    // deep shadow (0..20)
                r > 1.0 - bright -> 250 + rng.nextInt(6)             // blown highlight (250..255)
                else -> (base + rng.nextInt(41) - 20).coerceIn(0, 255)
            }
        }
        return px
    }

    // ---- scene classification ----

    @Test fun darkFrameIsNight() {
        assertEquals("NIGHT", CameraBrain.classify(frame(20)))
    }

    @Test fun midFrameIsNormal() {
        assertEquals("NORMAL", CameraBrain.classify(frame(120)))
    }

    @Test fun veryBrightFrameIsBright() {
        assertEquals("BRIGHT", CameraBrain.classify(frame(200)))
    }

    @Test fun bright_sky_with_dark_subject_is_backlit() {
        // 10% blown sky, 40% deep shadow, rest mid-grey
        val px = IntArray(1000)
        for (i in px.indices) px[i] = when {
            i < 100 -> 255
            i < 500 -> 10
            else -> 110
        }
        assertEquals("BACKLIT", CameraBrain.classify(px))
    }

    // ---- exposure plans ----

    @Test fun darkSkyUsesHighIsoAndOneThirtieth() {
        val plan = ShotPlanner.plan("NIGHT", mean = 30.0, clipped = 0.0,
            isoLo = 50, isoHi = 3200, shutterLoNs = 1_000_000L, shutterHiNs = 1_000_000_000L)
        assertEquals(1600, plan.iso)
        assertEquals(ShotPlanner.NS_1_30, plan.shutterNs)
    }

    @Test fun nightHeadlightUsesLowIsoAndFastShutter() {
        val plan = ShotPlanner.plan("NIGHT", mean = 40.0, clipped = 0.01,
            isoLo = 50, isoHi = 3200, shutterLoNs = 1_000_000L, shutterHiNs = 1_000_000_000L)
        assertEquals(400, plan.iso)
        assertEquals(ShotPlanner.NS_1_250, plan.shutterNs)
    }

    @Test fun brightSceneUsesLowestIso() {
        val plan = ShotPlanner.plan("BRIGHT", mean = 210.0, clipped = 0.0,
            isoLo = 50, isoHi = 3200, shutterLoNs = 1_000_000L, shutterHiNs = 1_000_000_000L)
        assertEquals(100, plan.iso)
        assertEquals(ShotPlanner.NS_1_500, plan.shutterNs)
    }

    @Test fun feedbackOffsetsAreClampedToTheCameraLimits() {
        // a huge feedback push must never go past the camera's ISO range
        val plan = ShotPlanner.plan("NORMAL", mean = 120.0, clipped = 0.0,
            isoLo = 50, isoHi = 800, shutterLoNs = 1_000_000L, shutterHiNs = 1_000_000_000L,
            isoStops = 10.0)
        assertEquals(800, plan.iso)
    }

    // ---- exposure correction ----

    @Test fun darkFrameAsksForMoreExposure() {
        assertEquals(2, CameraBrain.evStep(60.0))
    }

    @Test fun rightBrightnessNeedsNoChange() {
        assertEquals(0, CameraBrain.evStep(CameraBrain.TARGET_LUMA))
    }

    @Test fun overexposedFrameAsksForLessExposure() {
        assertEquals(-2, CameraBrain.evStep(200.0))
    }

    @Test fun brighterSceneGetsLowerIso() {
        assertEquals(100, CameraBrain.isoFor(160.0))
        assertEquals(800, CameraBrain.isoFor(50.0))
    }

    // ---- sweep: many random synthetic scenes must always give a valid plan ----

    @Test fun randomSyntheticScenesAlwaysGiveValidPlans() {
        val rng = Random(42)
        val validScenes = setOf("NIGHT", "BACKLIT", "BRIGHT", "NORMAL")
        repeat(500) {
            val px = randomScene(rng)
            val scene = CameraBrain.classify(px)
            assertTrue("unknown scene $scene", scene in validScenes)
            val plan = ShotPlanner.plan(scene,
                mean = CameraBrain.meanLuma(px),
                clipped = CameraBrain.clippedFraction(px),
                isoLo = 50, isoHi = 3200,
                shutterLoNs = 1_000_000L, shutterHiNs = 1_000_000_000L,
                isoStops = rng.nextGaussian().coerceIn(-2.0, 2.0),
                shutterStops = rng.nextGaussian().coerceIn(-2.0, 2.0))
            assertTrue("iso ${plan.iso} out of range", plan.iso in 50..3200)
            assertTrue("shutter ${plan.shutterNs} out of range", plan.shutterNs in 1_000_000L..1_000_000_000L)
        }
    }
}
