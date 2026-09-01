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

        // আপডেট করা ফাংশন: এখানে এখন টাইটেল এবং ছবি আঁকা হচ্ছে
        fun drawSection(
            segments: List<Segment>,
            basePaint: TextPaint,
            borderPaint: Paint,
            sectionTitle: String? = null,
            images: List<Bitmap> = emptyList()
        ) {
            var sectionTop = y
            var hasContent = false

            // ১. সেকশনের টাইটেল (যেমন: User Prompt বা AI Response) আঁকা
            if (sectionTitle != null) {
                val titleLayout = makeLayout(sectionTitle, titlePaint, CONTENT_W - 2 * PAD)
                val titleH = titleLayout.height + PAD
                
                if (y + titleH > PAGE_H - MARGIN) {
                    newPage()
                    sectionTop = y
                }
                
                canvas.save()
                canvas.translate((MARGIN + PAD).toFloat(), y + PAD/2)
                titleLayout.draw(canvas)
                canvas.restore()
                y += titleH
                hasContent = true
            }

            // ২. টেক্সট বা কোড আঁকা
            for (seg in segments) {
                if (seg.text.isBlank()) continue // ফাঁকা থাকলে আঁকবে না
                
                val paint = if (seg.isCode) codePaint else basePaint
                val bg = if (seg.isCode) codeBgPaint else null
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

            // ৩. ছবিগুলো সেকশনের ভেতরেই আঁকা (নির্দিষ্ট সাইজে রিসাইজ করে যাতে কাটে না)
            for (bmp in images) {
                if (bmp.width <= 0 || bmp.height <= 0) continue
                
                // নির্দিষ্ট মাপের ভেতর রাখার জন্য ম্যাক্সিমাম সাইজ
                val MAX_IMG_W = (CONTENT_W * 0.85).toInt()
                val MAX_IMG_H = (PAGE_H / 3).toInt() 
                
                var w = bmp.width
                var h = bmp.height
                
                // Aspect ratio বজায় রেখে রিসাইজ করা
                val ratioW = MAX_IMG_W.toFloat() / w
                val ratioH = MAX_IMG_H.toFloat() / h
                val ratio = minOf(ratioW, ratioH)
                
                if (ratio < 1.0f) {
                    w = (w * ratio).toInt()
                    h = (h * ratio).toInt()
                }
                
                val imgH = h + 2 * PAD
                if (y + imgH > PAGE_H - MARGIN) {
                    if (hasContent) {
                        canvas.drawRect(
                            MARGIN.toFloat(), sectionTop,
                            (MARGIN + CONTENT_W).toFloat(), y, borderPaint
                        )
                    }
                    newPage()
                    sectionTop = y
                }
                
                val left = MARGIN + (CONTENT_W - w) / 2
                val dest = RectF(left.toFloat(), y + PAD, (left + w).toFloat(), y + PAD + h)
                canvas.drawBitmap(bmp, null, dest, null)
                y += imgH
                hasContent = true
            }

            // ৪. সেকশনের বর্ডার আঁকা
            if (hasContent) {
                canvas.drawRect(
                    MARGIN.toFloat(), sectionTop,
                    (MARGIN + CONTENT_W).toFloat(), y, borderPaint
                )
                y += GAP
            }
        }
    }

    private fun generatePdf() {
        val allTurns = ArrayList(turns)

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

            // User Prompt সেকশন (লেখা এবং ছবি একসাথে একই লাল বর্ডারের ভেতর)
            if (turn.prompt.isNotBlank() || turn.images.isNotEmpty()) {
                renderer.drawSection(
                    segments = listOf(Segment(turn.prompt, false)),
                    basePaint = promptPaint,
                    borderPaint = redBorder,
                    sectionTitle = "User Prompt",
                    images = turn.images
                )
            }
            
            // AI Response সেকশন
            if (turn.response.isNotBlank()) {
                renderer.drawSection(
                    segments = parseSegments(turn.response),
                    basePaint = responsePaint,
                    borderPaint = blueBorder,
                    sectionTitle = "AI Response"
                )
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
