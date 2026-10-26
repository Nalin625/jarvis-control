package com.jarvis.control

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** On-phone AI: runs the small model (SmallSr) over the picture in 128-pixel tiles, so memory stays small.
 *  Slow on big photos: it is meant to run on a background thread. */
class SmallSrEnhancer : Enhancer {
    override fun enhance(jpeg: ByteArray): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        src.recycle()
        val outPixels = IntArray(w * h)
        val tile = 128
        val halo = SmallSr.HALO
        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                val x0 = max(0, tx - halo)
                val y0 = max(0, ty - halo)
                val x1 = min(w, tx + tile + halo)
                val y1 = min(h, ty + tile + halo)
                val pw = x1 - x0
                val ph = y1 - y0
                val plane = pw * ph
                val inp = FloatArray(3 * plane)
                for (y in 0 until ph) for (x in 0 until pw) {
                    val c = pixels[(y0 + y) * w + (x0 + x)]
                    val i = y * pw + x
                    inp[i] = Color.red(c) / 255f
                    inp[plane + i] = Color.green(c) / 255f
                    inp[2 * plane + i] = Color.blue(c) / 255f
                }
                val res = SmallSr.enhancePatch(inp, ph, pw)
                val cx1 = min(w, tx + tile)
                val cy1 = min(h, ty + tile)
                for (y in ty until cy1) for (x in tx until cx1) {
                    val i = (y - y0) * pw + (x - x0)
                    val r = (res[i] * 255f).roundToInt().coerceIn(0, 255)
                    val g = (res[plane + i] * 255f).roundToInt().coerceIn(0, 255)
                    val b = (res[2 * plane + i] * 255f).roundToInt().coerceIn(0, 255)
                    outPixels[y * w + x] = Color.rgb(r, g, b)
                }
                tx += tile
            }
            ty += tile
        }
        val out = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        out.recycle()
        return bos.toByteArray()
    }
}
