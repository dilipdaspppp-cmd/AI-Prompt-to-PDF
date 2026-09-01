package com.example.aiprompttopdf

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var promptInput: EditText
    private lateinit var responseInput: EditText
    private lateinit var turnsContainer: LinearLayout
    private lateinit var imageLabel: TextView

    private val currentImages = ArrayList<Bitmap>()
    private val turns = ArrayList<ChatTurn>()

    private val PICK_IMAGE = 101

    // A4 size in points
    private val PAGE_W = 595
    private val PAGE_H = 842
    private val MARGIN = 40
    private val PAD = 8
    private val GAP = 14f
    private val CONTENT_W: Int
        get() = PAGE_W - 2 * MARGIN

    data class ChatTurn(
        val prompt: String,
        val response: String,
        val images: List<Bitmap>
    )

    data class Segment(
        val text: String,
        val isCode: Boolean
    )

    // ---------- Paints ----------
    private val promptPaint = TextPaint().apply {
        color = Color.RED
        textSize = 13f
        isAntiAlias = true
    }
    private val responsePaint = TextPaint().apply {
        color = Color.BLUE
        textSize = 13f
        isAntiAlias = true
    }
    private val codePaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 12f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    private val titlePaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val codeBgPaint = Paint().apply {
        color = Color.parseColor("#ECECEC")
        style = Paint.Style.FILL
    }
    private val redBorder = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val blueBorder = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        promptInput = findViewById(R.id.promptInput)
        responseInput = findViewById(R.id.responseInput)
        turnsContainer = findViewById(R.id.turnsContainer)
        imageLabel = findViewById(R.id.imageLabel)

        findViewById<Button>(R.id.addImageButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(Intent.createChooser(intent, "ছবি নির্বাচন করুন"), PICK_IMAGE)
        }

        findViewById<Button>(R.id.addTurnButton).setOnClickListener {
            addNewTurn()
        }

        findViewById<Button>(R.id.generatePdfButton).setOnClickListener {
            generatePdf()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    decodeBitmap(clip.getItemAt(i).uri)?.let { currentImages.add(it) }
                }
            } else {
                data.data?.let { uri ->
                    decodeBitmap(uri)?.let { currentImages.add(it) }
                }
            }
            imageLabel.text = "সংযুক্ত ছবি: " + currentImages.size + " টি"
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun addNewTurn() {
        // টেক্সট হুবহু রাখা হয়েছে — কোনো trim করা হয়নি, যাতে স্পেস/ইনডেন্টেশন/লাইন ব্রেক অক্ষত থাকে
        val prompt = promptInput.text.toString()
        val response = responseInput.text.toString()
        if (prompt.isEmpty() && response.isEmpty() && currentImages.isEmpty()) {
            Toast.makeText(this, "প্রম্পট বা উত্তর লিখুন", Toast.LENGTH_SHORT).show()
            return
        }
        turns.add(ChatTurn(prompt, response, ArrayList(currentImages)))
        currentImages.clear()

        val label = TextView(this)
        label.text = "✔ টার্ন " + turns.size + " সংরক্ষিত"
        label.setTextColor(Color.DKGRAY)
        label.setPadding(0, 8, 0, 8)
        turnsContainer.addView(label)

        promptInput.setText("")
        responseInput.setText("")
        imageLabel.text = "কোনো ছবি নেই"
        Toast.makeText(this, "টার্ন যোগ হয়েছে। মোট: " + turns.size, Toast.LENGTH_SHORT).show()
    }

    // ---------- PDF Generation ----------
    private fun makeLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun parseSegments(response: String): List<Segment> {
        val result = ArrayList<Segment>()
        val parts = response.split("```")
        for (i in parts.indices) {
            var part = parts[i]
            if (part.isEmpty()) continue
            if (i % 2 == 1) {
                // কোড ব্লক — প্রথম লাইনে ভাষার নাম (যেমন kotlin, python) থাকলে বাদ দেওয়া হচ্ছে
                val nl = part.indexOf("\n")
                if (nl > 0) {
                    val firstLine = part.substring(0, nl).trim()
                    if (firstLine.length < 20 && !firstLine.contains(" ")) {
                        part = part.substring(nl + 1)
                    }
                }
                result.add(Segment(part, true))
            } else {
                result.add(Segment(part, false))
            }
        }
        return result
    }

    inner class PdfRenderer {
        val document = PdfDocument()
        private var pageNumber = 0
        private lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = 0f

        fun start() {
            newPage()
        }

        fun newPage() {
            if (pageNumber > 0) {
                document.finishPage(page)
            }
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
            page = document.startPage(info)
            canvas = page.canvas
            y = MARGIN.toFloat()
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_H - MARGIN) {
                newPage()
            }
        }

        fun finish() {
            document.finishPage(page)
        }

        fun drawTitle(text: String) {
            val layout = makeLayout(text, titlePaint, CONTENT_W)
            ensureSpace(layout.height + 10f)
            canvas.save()
            canvas.translate(MARGIN.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 10f
        }

        // বর্ডারসহ একটি সেকশন আঁকে; পেজ বদলালে বর্ডার আগের পেজে বন্ধ করে নতুন পেজে আবার শুরু করে
        fun drawSection(segments: List<Segment>, basePaint: TextPaint, borderPaint: Paint) {
            var sectionTop = y
            var hasContent = false

            for (seg in segments) {
                val paint = if (seg.isCode) codePaint else basePaint
                val bg = if (seg.isCode) codeBgPaint else null
                // লাইন ধরে ধরে আঁকা হচ্ছে যাতে পেজিনেশন নিরাপদ থাকে;
                // লম্বা লাইন StaticLayout দিয়ে র‍্যাপ হয়, ডানদিকে কাটে না
                val lines = seg.text.split("\n")
                for (ln in lines) {
                    val shown = if (ln.isEmpty()) " " else ln
                    val layout = makeLayout(shown, paint, CONTENT_W - 2 * PAD)
                    val h = layout.height + 2 * PAD

                    if (y + h > PAGE_H - MARGIN) {
                        if (hasContent) {
                            canvas.drawRect(
                                MARGIN.toFloat(), sectionTop,
                                (MARGIN + CONTENT_W).toFloat(), y, borderPaint
                            )
                        }
                        newPage()
                        sectionTop = y
                    }

                    if (bg != null) {
                        canvas.drawRect(
                            MARGIN.toFloat(), y,
                            (MARGIN + CONTENT_W).toFloat(), y + h, bg
                        )
                    }
                    canvas.save()
                    canvas.translate((MARGIN + PAD).toFloat(), y + PAD)
                    layout.draw(canvas)
                    canvas.restore()
                    y += h
                    hasContent = true
                }
            }

            if (hasContent) {
                canvas.drawRect(
                    MARGIN.toFloat(), sectionTop,
                    (MARGIN + CONTENT_W).toFloat(), y, borderPaint
                )
                y += GAP
            }
        }

        fun drawImages(bitmaps: List<Bitmap>) {
            for (bmp in bitmaps) {
                if (bmp.width <= 0 || bmp.height <= 0) continue
                // পেজের প্রস্থ অনুযায়ী স্বয়ংক্রিয় রিসাইজ
                var w = CONTENT_W
                var h = (bmp.height * (CONTENT_W.toFloat() / bmp.width)).toInt()
                val maxH = PAGE_H - 2 * MARGIN
                if (h > maxH) {
                    val s = maxH.toFloat() / h
                    h = maxH
                    w = (w * s).toInt()
                }
                ensureSpace(h.toFloat())
                val left = MARGIN + (CONTENT_W - w) / 2
                val dest = RectF(left.toFloat(), y, (left + w).toFloat(), y + h)
                canvas.drawBitmap(bmp, null, dest, null)
                y += h + GAP
            }
        }
    }

    private fun generatePdf() {
        val allTurns = ArrayList(turns)

        // শেষবারের ইনপুট Add New Turn না চাপলেও PDF-এ যুক্ত হবে
        val curPrompt = promptInput.text.toString()
        val curResponse = responseInput.text.toString()
        if (curPrompt.isNotEmpty() || curResponse.isNotEmpty() || currentImages.isNotEmpty()) {
            allTurns.add(ChatTurn(curPrompt, curResponse, ArrayList(currentImages)))
        }

        if (allTurns.isEmpty()) {
            Toast.makeText(this, "PDF বানানোর মতো কোনো ডেটা নেই", Toast.LENGTH_SHORT).show()
            return
        }

        val renderer = PdfRenderer()
        renderer.start()

        for ((index, turn) in allTurns.withIndex()) {
            renderer.drawTitle("Turn " + (index + 1))

            if (turn.prompt.isNotEmpty()) {
                renderer.drawSection(listOf(Segment(turn.prompt, false)), promptPaint, redBorder)
            }
            if (turn.images.isNotEmpty()) {
                renderer.drawImages(turn.images)
            }
            if (turn.response.isNotEmpty()) {
                renderer.drawSection(parseSegments(turn.response), responsePaint, blueBorder)
            }
        }

        renderer.finish()
        savePdf(renderer.document)
        renderer.document.close()
    }

    private fun savePdf(document: PdfDocument) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "AI_Chat_" + timeStamp + ".pdf"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android Q+ : MediaStore API দিয়ে পাবলিক Downloads ফোল্ডারে সেভ
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        document.writeTo(out)
                    }
                    Toast.makeText(this, "PDF Saved Successfully: " + fileName, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "PDF সেভ ব্যর্থ হয়েছে", Toast.LENGTH_LONG).show()
                }
            } else {
                // Android 9 বা তার নিচে
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    document.writeTo(out)
                }
                Toast.makeText(this, "PDF Saved Successfully: " + fileName, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
}
