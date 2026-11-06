package com.jarvis.control

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Notes scan: photograph a handwritten page, read it on the phone (ML Kit, offline, free), let you correct it,
 * and keep the correction. Your "words the phone got right first time" percentage is worked out from those saves.
 * Nothing is sent to the cloud.
 */
class NotesScanActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CAMERA = 77
    }

    private val notesDir by lazy { File(filesDir, "notes").apply { mkdirs() } }
    private val logFile by lazy { File(notesDir, "corrections.jsonl") }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var previewView: PreviewView
    private lateinit var overlay: PageOverlayView
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var saveButton: Button
    private lateinit var stats: TextView

    private var imageCapture: ImageCapture? = null
    private var machineText: String = ""
    private var pageFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        overlay = PageOverlayView(this).apply { visibility = View.GONE }
        status = TextView(this).apply {
            text = "Point the camera at the notebook page, then tap SCAN."
            textSize = 16f
        }
        val scanButton = Button(this).apply {
            text = "SCAN PAGE"
            setOnClickListener { capture() }
        }
        editor = EditText(this).apply {
            hint = "The phone's reading appears here. Fix the wrong words, then tap SAVE CORRECTION."
            minLines = 6
            visibility = View.GONE
        }
        saveButton = Button(this).apply {
            text = "SAVE CORRECTION"
            visibility = View.GONE
            setOnClickListener { save() }
        }
        stats = TextView(this).apply { textSize = 15f }
        // The app theme is dark, so give every text box a light colour (otherwise it is black on black).
        val lightText = 0xFFEAEAEA.toInt()
        status.setTextColor(lightText)
        stats.setTextColor(lightText)
        editor.setTextColor(lightText)
        editor.setHintTextColor(0xFF9A9A9A.toInt())

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(overlay, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status)
            addView(scanButton)
            addView(editor)
            addView(saveButton)
            addView(stats)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        refreshStats()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera()
            else status.text = "Camera permission is needed to scan a page."
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder().build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            imageCapture = capture
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val capture = imageCapture ?: run {
            status.text = "Camera isn't ready yet."
            return
        }
        val file = File(notesDir, "page_${System.currentTimeMillis()}.jpg")
        status.text = "Reading the page..."
        overlay.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    pageFile = file
                    readPage(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { status.text = "Couldn't take the photo: ${exception.message}" }
                }
            }
        )
    }

    /**
     * Make the photo easier to read before ML Kit sees it: fix the camera rotation, drop colour,
     * and raise the contrast so pencil or light ink stands out from the paper.
     */
    private fun preparePage(file: File): Bitmap? {
        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val contrast = 1.5f
        val offset = (1f - contrast) * 128f
        val colour = ColorMatrix(floatArrayOf(
            contrast * 0.33f, contrast * 0.59f, contrast * 0.11f, 0f, offset,
            contrast * 0.33f, contrast * 0.59f, contrast * 0.11f, 0f, offset,
            contrast * 0.33f, contrast * 0.59f, contrast * 0.11f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        Canvas(out).drawBitmap(rotated, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(colour) })
        return out
    }

    private fun readPage(file: File) {
        val bitmap = preparePage(file)
        if (bitmap == null) {
            status.text = "Couldn't open the photo."
            return
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                machineText = result.text
                // Highlight every word the phone picked, on the photo it read.
                val words = result.textBlocks
                    .flatMap { block -> block.lines.flatMap { line -> line.elements } }
                    .mapNotNull { it.boundingBox?.let { box -> RectF(box) } }
                previewView.visibility = View.GONE
                overlay.visibility = View.VISIBLE
                overlay.show(bitmap, words)
                editor.setText(machineText)
                editor.visibility = View.VISIBLE
                saveButton.visibility = View.VISIBLE
                status.text = if (machineText.isBlank())
                    "The phone found no text. Type the page into the box and save."
                else
                    "Check the reading. Fix the wrong words, then save."
            }
            .addOnFailureListener { e ->
                status.text = "Couldn't read the page: ${e.message}"
            }
    }

    private fun save() {
        val corrected = editor.text.toString().trim()
        if (corrected.isEmpty()) {
            status.text = "Nothing to save. Type or fix the text first."
            return
        }
        val accuracy = NotesMath.wordAccuracy(machineText, corrected)
        val record = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("photo", pageFile?.name ?: "")
            .put("machine", machineText)
            .put("corrected", corrected)
            .put("accuracy", accuracy)
        ioExecutor.execute {
            logFile.appendText(record.toString() + "\n")
            runOnUiThread {
                status.text = "Saved. The phone got %.0f%% of this page right first time.".format(accuracy * 100)
                editor.setText("")
                editor.visibility = View.GONE
                saveButton.visibility = View.GONE
                refreshStats()
            }
        }
    }

    private fun refreshStats() {
        val rows = try {
            if (logFile.exists()) logFile.readLines().filter { it.isNotBlank() } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (rows.isEmpty()) {
            stats.text = "No corrected pages yet."
            return
        }
        val values = rows.mapNotNull { runCatching { JSONObject(it).getDouble("accuracy") }.getOrNull() }
        val average = if (values.isEmpty()) 0.0 else values.average()
        stats.text = "Pages corrected: ${rows.size}\nWords the phone got right first time: %.0f%%".format(average * 100)
    }
}

/** Shows the photo the phone read, with a yellow box over every word it picked. */
class PageOverlayView(context: Context) : View(context) {

    private var page: Bitmap? = null
    private val boxes = mutableListOf<RectF>()
    private val fillPaint = Paint().apply { color = 0x55FFEB3B; style = Paint.Style.FILL }
    private val strokePaint = Paint().apply { color = 0xFFFFEB3B.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun show(bitmap: Bitmap, words: List<RectF>) {
        page = bitmap
        boxes.clear()
        boxes.addAll(words)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = page ?: return
        if (width == 0 || height == 0) return
        // Fit the whole page inside the box, keeping its shape.
        val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, pagePaint)
        for (box in boxes) {
            canvas.drawRect(box, fillPaint)
            canvas.drawRect(box, strokePaint)
        }
        canvas.restore()
    }
}
