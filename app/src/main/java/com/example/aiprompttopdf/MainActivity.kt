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
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
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

    private val promptBgPaint = Paint().apply {
        color = Color.parseColor("#FFEBEE")
        style = Paint.Style.FILL
    }

    private val responseBgPaint = Paint().apply {
        color = Color.parseColor("#E3F2FD")
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
        findViewById<Button>(R.id.generateHtmlButton).setOnClickListener {
            generateHtml()
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

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        return try {
            var w = bmp.width
            var h = bmp.height
            if (w <= 0 || h <= 0) return ""
            val MAX_W = 1000
            val MAX_H = 1400
            var scale = 1f
            if (w > MAX_W) {
                scale = MAX_W.toFloat() / w
            }
            if (h > MAX_H) {
                val heightScale = MAX_H.toFloat() / h
                if (heightScale < scale) scale = heightScale
            }
            if (scale < 1f) {
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            val scaled = if (w == bmp.width && h == bmp.height) {
                bmp
            } else {
                Bitmap.createScaledBitmap(bmp, w, h, true)
            }
            val whiteBackground = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(whiteBackground)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            val stream = ByteArrayOutputStream()
            whiteBackground.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            if (scaled !== bmp) {
                scaled.recycle()
            }
            whiteBackground.recycle()
            encoded
        } catch (e: Exception) {
            ""
        }
    }

    private fun generateHtml() {
        val allTurns = ArrayList(turns)
        val curPrompt = promptInput.text.toString()
        val curResponse = responseInput.text.toString()
        if (curPrompt.isNotEmpty() || curResponse.isNotEmpty() || currentImages.isNotEmpty()) {
            allTurns.add(ChatTurn(curPrompt, curResponse, ArrayList(currentImages)))
        }
        if (allTurns.isEmpty()) {
            Toast.makeText(this, "HTML বানানোর মতো কোনো ডেটা নেই", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n")
        sb.append("<html lang=\"bn\">\n")
        sb.append("<head>\n")
        sb.append("<meta charset=\"UTF-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        sb.append("<title>AI Chat Export</title>\n")
        sb.append("<style>\n")
        sb.append("body{font-family:sans-serif;margin:16px;background:#fafafa;color:#111;}\n")
        sb.append("h1{font-size:22px;margin-bottom:4px;}\n")
        sb.append(".meta{font-size:12px;color:#555;margin-top:0;margin-bottom:18px;}\n")
        sb.append(".turn{background:#fff;border:1px solid #ddd;border-radius:12px;padding:14px;margin-bottom:20px;}\n")
        sb.append(".turn h2{margin:0 0 12px 0;font-size:18px;color:#333;}\n")
        sb.append(".prompt-box{border:2px solid #d32f2f;border-radius:10px;padding:10px;margin-bottom:14px;background:#FFEBEE;}\n")
        sb.append(".prompt-box h3{margin:0 0 8px 0;color:#d32f2f;font-size:15px;}\n")
        sb.append(".prompt-text{color:#d32f2f;}\n")
        sb.append(".response-box{border:2px solid #1565c0;border-radius:10px;padding:10px;background:#E3F2FD;}\n")
        sb.append(".response-box h3{margin:0 0 8px 0;color:#1565c0;font-size:15px;}\n")
        sb.append(".response-text{color:#1565c0;}\n")
        sb.append("pre{white-space:pre-wrap;word-wrap:break-word;font-family:monospace;font-size:13px;line-height:1.5;margin:0;padding:10px;background:#ffffff;border-radius:8px;border:1px solid #e0e0e0;overflow-x:auto;}\n")
        sb.append("img{display:block;max-width:100%;height:auto;max-height:520px;object-fit:contain;margin:12px auto;border:1px solid #bbb;border-radius:8px;background:#fff;padding:4px;}\n")
        sb.append("</style>\n")
        sb.append("</head>\n")
        sb.append("<body>\n")
        sb.append("<h1>AI Chat Export</h1>\n")
        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        sb.append("<p class=\"meta\">Generated: ").append(escapeHtml(dateStr)).append("</p>\n")

        for ((index, turn) in allTurns.withIndex()) {
            sb.append("<div class=\"turn\">\n")
            sb.append("<h2>Turn ").append(index + 1).append("</h2>\n")

            if (turn.prompt.isNotEmpty() || turn.images.isNotEmpty()) {
                sb.append("<div class=\"prompt-box\">\n")
                sb.append("<h3>User Prompt</h3>\n")
                
                for (bmp in turn.images) {
                    val base64Image = bitmapToBase64(bmp)
                    if (base64Image.isNotEmpty()) {
                        sb.append("<img src=\"data:image/jpeg;base64,")
                        sb.append(base64Image)
                        sb.append("\" alt=\"Prompt image\">\n")
                    }
                }
                
                if (turn.prompt.isNotEmpty()) {
                    sb.append("<pre class=\"prompt-text\">")
                    sb.append(escapeHtml(turn.prompt))
                    sb.append("</pre>\n")
                }
                sb.append("</div>\n")
            }

            if (turn.response.isNotEmpty()) {
                sb.append("<div class=\"response-box\">\n")
                sb.append("<h3>AI Response</h3>\n")
                sb.append("<pre class=\"response-text\">")
                sb.append(escapeHtml(turn.response))
                sb.append("</pre>\n")
                sb.append("</div>\n")
            }
            sb.append("</div>\n")
        }
        sb.append("</body>\n")
        sb.append("</html>\n")
        saveHtml(sb.toString())
    }

    private fun saveHtml(html: String) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "AI_Chat_" + timeStamp + ".html"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(html.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "HTML Saved Successfully: " + fileName, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "HTML সেভ ব্যর্থ হয়েছে", Toast.LENGTH_LONG).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeText(html, Charsets.UTF_8)
                Toast.makeText(this, "HTML Saved Successfully: " + fileName, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

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
            val part = parts[i]
            if (part.isEmpty()) continue
            result.add(Segment(part, i % 2 == 1))
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

        fun drawSection(
            segments: List<Segment>,
            basePaint: TextPaint,
            borderPaint: Paint,
            bgPaint: Paint,
            sectionTitle: String? = null,
            images: List<Bitmap> = emptyList()
        ) {
            var sectionTop = y
            var hasContent = false

            if (sectionTitle != null) {
                val titleLayout = makeLayout(sectionTitle, titlePaint, CONTENT_W - 2 * PAD)
                val titleH = titleLayout.height + PAD
                if (y + titleH > PAGE_H - MARGIN) {
                    newPage()
                    sectionTop = y
                }
                canvas.save()
                canvas.translate((MARGIN + PAD).toFloat(), y + PAD / 2)
                titleLayout.draw(canvas)
                canvas.restore()
                y += titleH
                hasContent = true
            }

            for (bmp in images) {
                if (bmp.width <= 0 || bmp.height <= 0) continue
                val MAX_IMG_W = (CONTENT_W * 0.85).toInt()
                val MAX_IMG_H = (PAGE_H / 3).toInt()
                var w = bmp.width
                var h = bmp.height
                val ratioW = MAX_IMG_W.toFloat() / w
                val ratioH = MAX_IMG_H.toFloat() / h
                val ratio = if (ratioW < ratioH) ratioW else ratioH
                if (ratio < 1.0f) {
                    w = (w * ratio).toInt()
                    h = (h * ratio).toInt()
                }
                val imgH = h + 2 * PAD
                if (y + imgH > PAGE_H - MARGIN) {
                    if (hasContent) {
                        canvas.drawRect(MARGIN.toFloat(), sectionTop, (MARGIN + CONTENT_W).toFloat(), y, bgPaint)
                        canvas.drawRect(MARGIN.toFloat(), sectionTop, (MARGIN + CONTENT_W).toFloat(), y, borderPaint)
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

            for (seg in segments) {
                if (seg.text.isBlank()) continue
                val paint = if (seg.isCode) codePaint else basePaint
                val bg = if (seg.isCode) codeBgPaint else null
                val lines = seg.text.split("\n")
                for (ln in lines) {
                    val shown = if (ln.isEmpty()) " " else ln
                    val layout = makeLayout(shown, paint, CONTENT_W - 2 * PAD)
                    val h = layout.height + 2 * PAD
                    if (y + h > PAGE_H - MARGIN) {
                        if (hasContent) {
                            canvas.drawRect(MARGIN.toFloat(), sectionTop, (MARGIN + CONTENT_W).toFloat(), y, bgPaint)
                            canvas.drawRect(MARGIN.toFloat(), sectionTop, (MARGIN + CONTENT_W).toFloat(), y, borderPaint)
                        }
                        newPage()
                        sectionTop = y
                    }
                    if (bg != null) {
                        canvas.drawRect(MARGIN.toFloat(), y, (MARGIN + CONTENT_W).toFloat(), y + h, bg)
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
                canvas.drawRect(MARGIN.toFloat(), sectionTop, (MARGIN + CONTENT_W).toFloat(), y, bgPaint)
                canvas.drawRect(MARGIN.toFloat(), sectionTop, (MARGIN + CONTENT_W).toFloat(), y, borderPaint)
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

            if (turn.prompt.isNotBlank() || turn.images.isNotEmpty()) {
                renderer.drawSection(
                    segments = listOf(Segment(turn.prompt, false)),
                    basePaint = promptPaint,
                    borderPaint = redBorder,
                    bgPaint = promptBgPaint,
                    sectionTitle = "User Prompt",
                    images = turn.images
                )
            }
            if (turn.response.isNotBlank()) {
                renderer.drawSection(
                    segments = parseSegments(turn.response),
                    basePaint = responsePaint,
                    borderPaint = blueBorder,
                    bgPaint = responseBgPaint,
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
