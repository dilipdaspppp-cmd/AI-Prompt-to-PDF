package com.example.aiprompttopdf

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
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

    data class ChatTurn(
        val prompt: String,
        val response: String,
        val images: List<Bitmap>
    )

    data class Segment(
        val text: String,
        val isCode: Boolean
    )

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
        findViewById<Button>(R.id.deleteAllButton).setOnClickListener {
            deleteAllTurns()
        }
        findViewById<Button>(R.id.generateHtmlButton).setOnClickListener {
            generateHtml()
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
        refreshTurnsList()
        promptInput.setText("")
        responseInput.setText("")
        imageLabel.text = "কোনো ছবি নেই"
        Toast.makeText(this, "Conversation যোগ হয়েছে। মোট: " + turns.size, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTurnsList() {
        turnsContainer.removeAllViews()
        for (i in turns.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, 4, 0, 4)

            val label = TextView(this)
            label.text = "✔ Conversation " + (i + 1) + " সংরক্ষিত"
            label.setTextColor(Color.DKGRAY)
            label.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )

            val deleteBtn = Button(this)
            deleteBtn.text = "ডিলিট"
            deleteBtn.textSize = 12f
            val index = i
            deleteBtn.setOnClickListener {
                turns.removeAt(index)
                refreshTurnsList()
                Toast.makeText(this, "Conversation " + (index + 1) + " ডিলিট হয়েছে", Toast.LENGTH_SHORT).show()
            }

            row.addView(label)
            row.addView(deleteBtn)
            turnsContainer.addView(row)
        }
    }

    private fun deleteAllTurns() {
        if (turns.isEmpty()) {
            Toast.makeText(this, "ডিলিট করার মতো কিছু নেই", Toast.LENGTH_SHORT).show()
            return
        }
        turns.clear()
        refreshTurnsList()
        Toast.makeText(this, "সব Conversation ডিলিট হয়েছে", Toast.LENGTH_SHORT).show()
    }

    //==========================================
    // HTML Export — AI-safe, কপি বাটনসহ
    //==========================================
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
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
        sb.append(".turn{background:#fff;border:1px solid #ddd;border-radius:12px;padding:14px;margin-bottom:45px;}\n")
        sb.append(".turn h2{margin:0 0 12px 0;font-size:18px;color:#333;}\n")
        sb.append(".prompt-box{border:2px solid #d32f2f;border-radius:10px;padding:10px;margin-bottom:14px;background:#FFEBEE;}\n")
        sb.append(".response-box{border:2px solid #1565c0;border-radius:10px;padding:10px;background:#E3F2FD;}\n")
        sb.append(".prompt-box h3{color:#d32f2f;font-size:15px;}\n")
        sb.append(".response-box h3{color:#1565c0;font-size:15px;}\n")
        sb.append(".box-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;}\n")
        sb.append(".box-head h3{margin:0;}\n")
        sb.append(".copy-btn{border:none;background:#333;color:#fff;border-radius:6px;padding:6px 12px;font-size:12px;cursor:pointer;}\n")
        sb.append(".copy-btn:active{background:#555;}\n")
        sb.append("pre{white-space:pre-wrap;word-wrap:break-word;font-family:monospace;font-size:13px;line-height:1.5;margin:0;padding:10px;background:#ffffff;border-radius:8px;border:1px solid #e0e0e0;overflow-x:auto;}\n")
        sb.append(".prompt-text{color:#d32f2f;}\n")
        sb.append(".response-text{color:#1565c0;}\n")
        sb.append(".code-block{margin:10px 0;border-radius:8px;overflow:hidden;border:1px solid #444;}\n")
        sb.append(".code-head{display:flex;justify-content:space-between;align-items:center;background:#1e1e1e;color:#9cdcfe;padding:6px 10px;font-size:12px;font-family:monospace;}\n")
        sb.append(".code-head .copy-btn{background:#3c3c3c;}\n")
        sb.append("pre.code-text{background:#2d2d2d;color:#e6e6e6;border:none;border-radius:0;}\n")
        sb.append("img{display:block;max-width:100%;height:auto;max-height:520px;object-fit:contain;margin:12px auto;border:1px solid #bbb;border-radius:8px;background:#fff;padding:4px;}\n")
        sb.append("#copyToast{display:none;position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:10px 20px;border-radius:24px;font-size:14px;z-index:999;}\n")
        sb.append("</style>\n")
        sb.append("</head>\n")
        sb.append("<body>\n")
        sb.append("<h1>AI Chat Export</h1>\n")
        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        sb.append("<p class=\"meta\">Generated: ").append(escapeHtml(dateStr)).append("</p>\n")

        for ((index, turn) in allTurns.withIndex()) {
            val tId = index + 1
            sb.append("<div class=\"turn\">\n")
            sb.append("<h2>Conversation ").append(tId).append("</h2>\n")

            if (turn.prompt.isNotEmpty() || turn.images.isNotEmpty()) {
                sb.append("<div class=\"prompt-box\">\n")
                sb.append("<div class=\"box-head\">\n")
                sb.append("<h3>User Prompt</h3>\n")
                if (turn.prompt.isNotEmpty()) {
                    sb.append("<button class=\"copy-btn\" onclick=\"copyEl(this,'p").append(tId).append("')\">📋 Copy</button>\n")
                }
                sb.append("</div>\n")

                for (bmp in turn.images) {
                    val base64Image = bitmapToBase64(bmp)
                    if (base64Image.isNotEmpty()) {
                        sb.append("<img src=\"data:image/jpeg;base64,")
                        sb.append(base64Image)
                        sb.append("\" alt=\"Prompt image\">\n")
                    }
                }

                if (turn.prompt.isNotEmpty()) {
                    sb.append("<pre id=\"p").append(tId).append("\" class=\"prompt-text\">")
                    sb.append(escapeHtml(turn.prompt))
                    sb.append("</pre>\n")
                }
                sb.append("</div>\n")
            }

            if (turn.response.isNotEmpty()) {
                sb.append("<div class=\"response-box\">\n")
                sb.append("<div class=\"box-head\">\n")
                sb.append("<h3>AI Response</h3>\n")
                sb.append("<button class=\"copy-btn\" onclick=\"copyEl(this,'r").append(tId).append("')\">📋 Copy Full Response</button>\n")
                sb.append("</div>\n")

                sb.append("<pre id=\"r").append(tId).append("\" style=\"display:none\">")
                sb.append(escapeHtml(turn.response))
                sb.append("</pre>\n")

                val segments = parseSegments(turn.response)
                var codeCounter = 0
                for (seg in segments) {
                    val clean = seg.text.trim('\n')
                    if (clean.isBlank()) continue
                    if (seg.isCode) {
                        codeCounter++
                        val cId = "c" + tId + "_" + codeCounter
                        sb.append("<div class=\"code-block\">\n")
                        sb.append("<div class=\"code-head\"><span>CODE</span><button class=\"copy-btn\" onclick=\"copyEl(this,'").append(cId).append("')\">📋 Copy</button></div>\n")
                        sb.append("<pre id=\"").append(cId).append("\" class=\"code-text\">")
                        sb.append(escapeHtml(clean))
                        sb.append("</pre>\n")
                        sb.append("</div>\n")
                    } else {
                        sb.append("<pre class=\"response-text\">")
                        sb.append(escapeHtml(clean))
                        sb.append("</pre>\n")
                    }
                }
                sb.append("</div>\n")
            }
            sb.append("</div>\n")
        }

        sb.append("<div id=\"copyToast\">✅ কপি হয়েছে!</div>\n")
        sb.append("<script>\n")
        sb.append("function copyEl(btn,id){var el=document.getElementById(id);if(!el)return;doCopy(el.textContent,btn);}\n")
        sb.append("function doCopy(text,btn){function ok(){showToast();if(btn){var old=btn.textContent;btn.textContent='✅ Copied!';setTimeout(function(){btn.textContent=old;},1500);}}\n")
        sb.append("if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(text).then(ok,function(){legacy(text);ok();});}else{legacy(text);ok();}}\n")
        sb.append("function legacy(text){var ta=document.createElement('textarea');ta.value=text;ta.setAttribute('readonly','');ta.style.position='fixed';ta.style.top='-1000px';document.body.appendChild(ta);ta.select();ta.setSelectionRange(0,text.length);try{document.execCommand('copy');}catch(e){}\n")
        sb.append("document.body.removeChild(ta);}\n")
        sb.append("var toastTimer=null;\n")
        sb.append("function showToast(){var t=document.getElementById('copyToast');if(!t)return;t.style.display='block';if(toastTimer)clearTimeout(toastTimer);toastTimer=setTimeout(function(){t.style.display='none';},1500);}\n")
        sb.append("</script>\n")
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
}
