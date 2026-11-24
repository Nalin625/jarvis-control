package com.jarvis.control

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Line icons drawn with paths (no pictures, no emoji). 24 x 24 grid. Generated from a tiny shape list. */
object Ico {
    private val defs = mapOf(
        "game" to "R2 7 20 11 5;P7 10 7 15;P4.5 12.5 9.5 12.5;O16 11 1.1;O18.6 13.8 1.1",
        "bolt" to "Q13 2 5 13 11 13 10 22 19 10 13 10",
        "stop" to "R6 6 12 12 2",
        "radar" to "O12 12 9;O12 12 5;P12 12 18.5 5.5;O12 12 1",
        "remote" to "R3 4 18 15 2;P9 22 15 22;P12 19 12 22;Q9 8 9 15 11 13 13.5 13.5 14.5 12 12.5 11",
        "share" to "R3 4 18 12 2;P8 20 16 20;P12 16 12 20;P12 13 12 7;P9 10 12 7 15 10",
        "mic" to "R9 3 6 11 3;A12 11 7 0 180;P12 18 12 21;P9 21 15 21",
        "bell" to "A12 10 6 180 180;P6 10 6 15 4 18 20 18 18 15 18 10;A12 20 2 0 180",
        "link" to "R2.5 9 10 6 3;R11.5 9 10 6 3;P9 12 15 12",
        "paste" to "R5 5 14 17 2;R9 3 6 4 1.5;P8 11 16 11;P8 15 14 15",
        "bars" to "P5 20 5 13;P10 20 10 6;P15 20 15 10;P20 20 20 3",
        "theme" to "O12 12 8;P12 4 12 20;A12 12 4 -90 180",
        "sliders" to "P4 8 20 8;P4 16 20 16;O9 8 2.2;O15 16 2.2",
        "laptop" to "R4 5 16 11 1.5;P2 19 22 19",
        "apps" to "R4 4 6 6 1.5;R14 4 6 6 1.5;R4 14 6 6 1.5;R14 14 6 6 1.5",
        "volup" to "Q3 9 7 9 11 5 11 19 7 15 3 15;A11 12 4 -55 110;A11 12 8 -55 110",
        "voldn" to "Q3 9 7 9 11 5 11 19 7 15 3 15;A11 12 4 -55 110",
        "mute" to "Q3 9 7 9 11 5 11 19 7 15 3 15;P15 9 21 15;P21 9 15 15",
        "unmute" to "Q3 9 7 9 11 5 11 19 7 15 3 15;A11 12 5 -45 90;P16 7 16 17",
        "sun" to "O12 12 4;P12 3 12 5.5;P12 18.5 12 21;P3 12 5.5 12;P18.5 12 21 12;P5.6 5.6 7.4 7.4;P16.6 16.6 18.4 18.4;P18.4 5.6 16.6 7.4;P7.4 16.6 5.6 18.4",
        "moon" to "A12 12 8 45 270;B17.66 6.34 12.5 9 12.5 15 17.66 17.66",
        "shot" to "P4 9 4 4 9 4;P15 4 20 4 20 9;P20 15 20 20 15 20;P9 20 4 20 4 15;O12 12 2.5",
        "lock" to "R5 11 14 9 2;A12 8 4 180 180;P8 8 8 11;P16 8 16 11;P12 14.5 12 16.5",
        "battery" to "R3 8 16 9 2;P20 11 20 14;P6 10.5 6 13.5;P9 10.5 9 13.5;P12 10.5 12 13.5",
        "chip" to "R6 6 12 12 1.5;P9 6 9 3;P12 6 12 3;P15 6 15 3;P9 18 9 21;P12 18 12 21;P15 18 15 21;P6 9 3 9;P6 12 3 12;P6 15 3 15;P18 9 21 9;P18 12 21 12;P18 15 21 15",
        "wifi" to "A12 19 4 225 90;A12 19 8 225 90;A12 19 12 225 90;O12 19 0.8",
        "globe" to "O12 12 9;P3 12 21 12;B12 3 7.5 8 7.5 16 12 21;B12 3 16.5 8 16.5 16 12 21",
        "news" to "R4 4 16 16 2;P8 9 16 9;P8 13 16 13;P8 17 13 17",
        "cloud" to "P6 19 17 19;B17 19 22 19 22 14.5 17 13;B17 13 17 8 11.5 8 10.5 12;B10.5 12 5 11 3 15.5 6 19",
        "pulse" to "P2 12 7 12 9.5 6 12.5 18 15 12 22 12",
        "sleep" to "A11 13 7 45 270;B15.95 8.05 11.5 10.5 11.5 15.5 15.95 17.95;P15 4 19 4 15 8 19 8",
        "away" to "R4 4 9 16 1;P12 12 21 12;P17.5 8 21 12 17.5 16",
        "eye" to "B3 12 8 6 16 6 21 12;B21 12 16 18 8 18 3 12;O12 12 3",
        "timer" to "O12 13 8;P12 13 12 9;P9 3 15 3;P12 3 12 5",
        "cancel" to "O12 12 9;P8.5 8.5 15.5 15.5;P15.5 8.5 8.5 15.5",
        "restart" to "A12 12 8 -60 300;P7.5 5.5 8 1.5;P7.5 5.5 11.5 6.2",
        "power" to "A12 13 8 -50 280;P12 3 12 12",
        "phone" to "R7 2 10 20 2;P11 18 13 18",
        "torch" to "Q8 3 16 3 14 9 10 9;R10 9 4 12 1;P12 12 12 14",
        "play" to "Q8 5 8 19 19 12",
        "next" to "Q6 5 6 19 15 12;P18 5 18 19",
        "prev" to "Q18 5 18 19 9 12;P6 5 6 19",
        "camera" to "R3 7 18 13 2;O12 13 3.5;P8 7 9.5 4 14.5 4 16 7",
        "bt" to "P7 8 17 16 12 21 12 3 17 8 7 16",
        "plane" to "Q12 3 14 9 21 13 21 15 14 13 14 18 16 20 16 21 12 20 8 21 8 20 10 18 10 13 3 15 3 13 10 9",
        "pin" to "A12 10 6 180 180;P6 10 12 21 18 10;O12 10 2",
        "clock" to "O12 12 9;P12 7 12 12 15 14",
        "cal" to "R4 5 16 15 2;P4 10 20 10;P8 3 8 7;P16 3 16 7",
        "people" to "O12 9 3.5;A12 20 7 180 180",
        "image" to "R3 4 18 16 2;O9 10 1.5;P4 18 10 13 14 16 17 12 20 15",
        "calc" to "R5 3 14 18 2;P8 7 16 7;P8 12 9 12;P12 12 13 12;P8 16 9 16;P12 16 13 16",
        "chat" to "R3 4 18 13 3;P8 17 8 21 12 17",
        "mail" to "R3 5 18 14 2;P3 7 12 14 21 7",
        "music" to "P9 18 9 6 18 4 18 16;O7 18 2;O16 16 2",
        "disk" to "R4 5 16 5 1;R4 14 16 5 1;P7 7.5 7.5 7.5;P7 16.5 7.5 16.5",
        "info" to "O12 12 9;P12 11 12 17;O12 8 0.5",
        "buzz" to "R8 4 8 16 2;P4 8 4 16;P20 8 20 16;P2 10 2 14;P22 10 22 14",
        "heart" to "Q12 20 4 12 4 8 7 5 10 5 12 8 14 5 17 5 20 8 20 12",
        "alert" to "Q12 3 22 20 2 20;P12 10 12 14;O12 17 0.5",
        "hotspot" to "O12 14 2;A12 14 6 210 120;A12 14 10 210 120",
        "star" to "Q12 3 14.5 9 21 9.5 16 13.5 17.5 20 12 16.5 6.5 20 8 13.5 3 9.5 9.5 9",
        "refresh" to "A12 12 8 -60 300;P7.5 5.5 8 1.5;P7.5 5.5 11.5 6.2",
        "x" to "P6 6 18 18;P18 6 6 18",
        "bell2" to "A12 10 6 180 180;P6 10 6 15 4 18 20 18 18 15 18 10;A12 20 2 0 180",
        "insta" to "R4 4 16 16 5;O12 12 3.8;O16.8 7.2 0.6",
        "claude" to "P12 3 12 21;P4.2 7.5 19.8 16.5;P4.2 16.5 19.8 7.5",
        "chrome" to "O12 12 9;O12 12 3.5;P12 8.5 19.5 8.5;P8.9 13.8 4.6 6.4;P15.1 13.8 10.8 21"
    )
    private val cache = HashMap<String, Path>()
    private val oval = RectF()

    fun path(id: String): Path = cache.getOrPut(id) { build(defs[id] ?: "O12 12 8") }

    private fun build(spec: String): Path {
        val p = Path()
        for (op in spec.split(";")) {
            val n = op.substring(1).trim().split(" ").filter { it.isNotEmpty() }.map { it.toFloat() }
            when (op[0]) {
                'P', 'Q' -> {
                    p.moveTo(n[0], n[1])
                    var i = 2
                    while (i + 1 < n.size) { p.lineTo(n[i], n[i + 1]); i += 2 }
                    if (op[0] == 'Q') p.close()
                }
                'O' -> p.addCircle(n[0], n[1], n[2], Path.Direction.CW)
                'A' -> { oval.set(n[0] - n[2], n[1] - n[2], n[0] + n[2], n[1] + n[2]); p.addArc(oval, n[3], n[4]) }
                'R' -> { oval.set(n[0], n[1], n[0] + n[2], n[1] + n[3]); p.addRoundRect(oval, n[4], n[4], Path.Direction.CW) }
                'B' -> { p.moveTo(n[0], n[1]); p.cubicTo(n[2], n[3], n[4], n[5], n[6], n[7]) }
            }
        }
        return p
    }

    /** Draws icon [id] centred at (cx, cy), [size] pixels wide, with the stroke style of [p]. */
    fun draw(c: Canvas, id: String, cx: Float, cy: Float, size: Float, p: Paint) {
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(s, s)
        val w = p.strokeWidth
        p.strokeWidth = w / s
        c.drawPath(path(id), p)
        p.strokeWidth = w
        c.restore()
    }
}
