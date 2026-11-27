package com.jarvis.control.intel

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** The only file that touches ML Kit (already part of the app for Notes scan). Runs fully on the phone. */
object IntelOcr {
    fun read(bmp: Bitmap, ok: (String) -> Unit, fail: (String) -> Unit) {
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { ok(it.text) }
                .addOnFailureListener { fail((it.message ?: "text recognition failed").take(100)) }
        } catch (e: Throwable) { fail((e.message ?: "text recognition unavailable").take(100)) }
    }
}
