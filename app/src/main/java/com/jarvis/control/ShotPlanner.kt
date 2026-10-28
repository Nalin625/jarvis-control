package com.jarvis.control

import android.hardware.camera2.CaptureRequest
import kotlin.math.pow
import kotlin.math.roundToInt

/** One decision the camera made. The camera shows them one at a time, so you can see how the photo is made. */
data class ShotStep(val title: String, val value: String, val reason: String)

/** The settings for the next picture, with the reason for each step. */
data class ShotPlan(
    val scene: String,
    val iso: Int,
    val shutterNs: Long,
    val awbMode: Int,
    val focusInfinity: Boolean,
    val steps: List<ShotStep>
)

/** Chooses the camera settings from the light, using the rules from photography guides (see docs/camera_settings_research.md).
 *  isoStops and shutterStops are your feedback: each one moves the settings by that many stops (0 = the rules as they are). */
object ShotPlanner {
    const val NS_1_500 = 2_000_000L
    const val NS_1_250 = 4_000_000L
    const val NS_1_125 = 8_000_000L
    const val NS_1_30 = 33_333_333L   // handheld floor for a phone's main camera (about 1 over the focal length)

    fun plan(
        scene: String,
        mean: Double,
        clipped: Double,
        isoLo: Int,
        isoHi: Int,
        shutterLoNs: Long,
        shutterHiNs: Long,
        isoStops: Double = 0.0,
        shutterStops: Double = 0.0
    ): ShotPlan {
        val steps = ArrayList<ShotStep>()
        steps.add(ShotStep("Light", scene,
            "average brightness ${mean.toInt()} of 255, blown highlights ${"%.1f".format(clipped * 100)}%"))
        val iso: Int
        val shutter: Long
        val reason: String
        when (scene) {
            "NIGHT" -> {
                if (clipped > 0.005) {
                    iso = 400
                    shutter = NS_1_250
                    reason = "a bright light in the dark (like the moon): a fast shutter and low ISO keep it from blowing out"
                } else {
                    iso = 1600
                    shutter = NS_1_30
                    reason = "dark sky: a high ISO and a 1/30 s shutter bring out detail; slower than 1/30 s blurs when hand-held"
                }
            }
            "BACKLIT" -> {
                iso = 100
                shutter = NS_1_500
                reason = "a bright sky behind a darker subject: exposing for the sky keeps its colour and clouds"
            }
            "BRIGHT" -> {
                iso = 100
                shutter = NS_1_500
                reason = "very bright light: the lowest ISO and fastest shutter protect the sky from blowing out"
            }
            else -> {
                if (mean < 90) {
                    iso = 400
                    shutter = NS_1_30
                    reason = "a dim room: a little more ISO, but never slower than 1/30 s when hand-held"
                } else {
                    iso = 200
                    shutter = NS_1_250
                    reason = "ordinary daylight: a low ISO keeps grain out and a fast shutter stops blur"
                }
            }
        }
        val tuned = if (isoStops != 0.0 || shutterStops != 0.0) " (adjusted by your feedback)" else ""
        val finalIso = (iso * 2.0.pow(isoStops)).roundToInt().coerceIn(isoLo, isoHi)
        val finalShutter = (shutter * 2.0.pow(shutterStops)).toLong().coerceIn(shutterLoNs, shutterHiNs)
        steps.add(ShotStep("Exposure", "ISO $finalIso, 1/${1_000_000_000L / finalShutter} s", reason + tuned))

        val night = scene == "NIGHT"
        if (night) {
            steps.add(ShotStep("White balance", "daylight", "a night sky and the moon look natural with a daylight setting"))
            steps.add(ShotStep("Focus", "infinity", "distant lights and the moon are sharpest with focus set far away, not searching"))
            steps.add(ShotStep("Steady", "hold still", "rest the phone on something or use a tripod; the frames are stacked to cut noise"))
        } else {
            steps.add(ShotStep("White balance", "auto", "daylight scenes are left to the phone's auto colour"))
            steps.add(ShotStep("Focus", "continuous", "keeps the subject sharp if it moves"))
        }
        steps.add(ShotStep("Composition", "subject on a third",
            "place the main subject where the grid lines cross, for a balanced photo"))
        return ShotPlan(
            scene = scene,
            iso = finalIso,
            shutterNs = finalShutter,
            awbMode = if (night) CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT else CaptureRequest.CONTROL_AWB_MODE_AUTO,
            focusInfinity = night,
            steps = steps
        )
    }
}
