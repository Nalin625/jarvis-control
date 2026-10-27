package com.jarvis.control

import android.hardware.camera2.CaptureRequest

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

/** Chooses the camera settings from the light. The rules come from photography guides (see docs/camera_settings_research.md):
 *  night sky and moon need a daylight white balance, focus at infinity, a steady phone and low noise;
 *  bright skies need a low ISO and fast shutter so they do not blow out. These are fixed rules, not a trained network. */
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
        shutterHiNs: Long
    ): ShotPlan {
        val steps = ArrayList<ShotStep>()
        steps.add(ShotStep("Light", scene,
            "average brightness ${mean.toInt()} of 255, blown highlights ${"%.1f".format(clipped * 100)}%"))
        var iso: Int
        var shutter: Long
        var awb = CaptureRequest.CONTROL_AWB_MODE_AUTO
        var focusInf = false
        when (scene) {
            "NIGHT" -> {
                if (clipped > 0.005) {
                    iso = 400
                    shutter = NS_1_250
                    steps.add(ShotStep("Exposure", "ISO 400, 1/250 s",
                        "a bright light in the dark (like the moon): a fast shutter and low ISO keep it from blowing out"))
                } else {
                    iso = 1600
                    shutter = NS_1_30
                    steps.add(ShotStep("Exposure", "ISO 1600, 1/30 s",
                        "dark sky: a high ISO and a 1/30 s shutter bring out detail; slower than 1/30 s blurs when hand-held"))
                }
                awb = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                steps.add(ShotStep("White balance", "daylight",
                    "a night sky and the moon look natural with a daylight setting"))
                focusInf = true
                steps.add(ShotStep("Focus", "infinity",
                    "distant lights and the moon are sharpest with focus set far away, not searching"))
                steps.add(ShotStep("Steady", "hold still",
                    "rest the phone on something or use a tripod; the 5 frames are stacked to cut noise"))
            }
            "BACKLIT" -> {
                iso = 100
                shutter = NS_1_500
                steps.add(ShotStep("Exposure", "ISO 100, 1/500 s",
                    "a bright sky behind a darker subject: exposing for the sky keeps its colour and clouds"))
            }
            "BRIGHT" -> {
                iso = 100
                shutter = NS_1_500
                steps.add(ShotStep("Exposure", "ISO 100, 1/500 s",
                    "very bright light: the lowest ISO and fastest shutter protect the sky from blowing out"))
            }
            else -> {
                if (mean < 90) {
                    iso = 400
                    shutter = NS_1_30
                    steps.add(ShotStep("Exposure", "ISO 400, 1/30 s",
                        "a dim room: a little more ISO, but never slower than 1/30 s when hand-held"))
                } else {
                    iso = 200
                    shutter = NS_1_250
                    steps.add(ShotStep("Exposure", "ISO 200, 1/250 s",
                        "ordinary daylight: a low ISO keeps grain out and a fast shutter stops blur"))
                }
            }
        }
        if (scene != "NIGHT") {
            steps.add(ShotStep("White balance", "auto", "daylight scenes are left to the phone's auto colour"))
            steps.add(ShotStep("Focus", "continuous", "keeps the subject sharp if it moves"))
        }
        steps.add(ShotStep("Composition", "subject on a third",
            "place the main subject where the grid lines cross, for a balanced photo"))
        return ShotPlan(
            scene = scene,
            iso = iso.coerceIn(isoLo, isoHi),
            shutterNs = shutter.coerceIn(shutterLoNs, shutterHiNs),
            awbMode = awb,
            focusInfinity = focusInf,
            steps = steps
        )
    }
}
