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
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var promptInput: EditText
    private lateinit var responseInput: EditText
    private lateinit var turnsContainer: LinearLayout
    private lateinit var imageLabel: TextView
    private lateinit var addTurnButton: Button
    private lateinit var cancelEditButton: Button
    private lateinit var rootScroll: ScrollView
    private val currentImages = ArrayList<Bitmap>()
    private val turns = ArrayList<ChatTurn>()
    private var editingIndex = -1
    private val PICK_IMAGE = 101
    private val PICK_HTML = 102

    data class ChatTurn(
        val prompt: String,
        val response: String,
        val images: List<Bitmap>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        promptInput = findViewById(R.id.promptInput)
        responseInput = findViewById(R.id.responseInput)
        turnsContainer = findViewById(R.id.turnsContainer)
        imageLabel = findViewById(R.id.imageLabel)
        addTurnButton = findViewById(R.id.addTurnButton)
        cancelEditButton = findViewById(R.id.cancelEditButton)
        rootScroll = findViewById(R.id.rootScroll)

        findViewById<Button>(R.id.addImageButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(Intent.createChooser(intent, "ছবি নির্বাচন করুন"), PICK_IMAGE)
        }
        addTurnButton.setOnClickListener {
            addNewTurn()
        }
        cancelEditButton.setOnClickListener {
            cancelEdit()
        }
        findViewById<Button>(R.id.deleteAllButton).setOnClickListener {
            deleteAllTurns()
        }
        findViewById<Button>(R.id.importHtmlButton).setOnClickListener {
            startImport()
        }
        findViewById<Button>(R.id.generateHtmlButton).setOnClickListener {
            generateHtml()
        }
        loadData()
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
        if (requestCode == PICK_HTML && resultCode == RESULT_OK && data != null) {
            data.data?.let { uri ->
                val html = readTextFromUri(uri)
                if (html != null) {
                    importHtml(html)
                } else {
                    Toast.makeText(this, "ফাইল পড়া যায়নি", Toast.LENGTH_LONG).show()
                }
            }
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

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
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
        if (editingIndex in turns.indices) {
            turns[editingIndex] = ChatTurn(prompt, response, ArrayList(currentImages))
            Toast.makeText(this, "Conversation " + (editingIndex + 1) + " আপডেট হয়েছে", Toast.LENGTH_SHORT).show()
        } else {
            turns.add(ChatTurn(prompt, response, ArrayList(currentImages)))
            Toast.makeText(this, "Conversation যোগ হয়েছে। মোট: " + turns.size, Toast.LENGTH_SHORT).show()
        }
        resetEditState()
        currentImages.clear()
        refreshTurnsList()
        saveData()
        promptInput.setText("")
        responseInput.setText("")
        imageLabel.text = "কোনো ছবি নেই"
    }

    private fun startEdit(index: Int) {
        val turn = turns[index]
        editingIndex = index
        promptInput.setText(turn.prompt)
        responseInput.setText(turn.response)
        currentImages.clear()
        currentImages.addAll(turn.images)
        imageLabel.text = "সংযুক্ত ছবি: " + currentImages.size + " টি"
        addTurnButton.text = "Update Conversation " + (index + 1)
        cancelEditButton.visibility = View.VISIBLE
        refreshTurnsList()
        rootScroll.fullScroll(ScrollView.FOCUS_UP)
        Toast.makeText(this, "পরিবর্তন করে Update বাটন চাপুন", Toast.LENGTH_LONG).show()
    }

    private fun resetEditState() {
        editingIndex = -1
        addTurnButton.text = "Add New Conversation"
        cancelEditButton.visibility = View.GONE
    }

    private fun cancelEdit() {
        resetEditState()
        currentImages.clear()
        promptInput.setText("")
        responseInput.setText("")
        imageLabel.text = "কোনো ছবি নেই"
        refreshTurnsList()
        Toast.makeText(this, "এডিট বাতিল হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun refreshTurnsList() {
        turnsContainer.removeAllViews()
        for (i in turns.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, 4, 0, 4)

            val label = TextView(this)
            if (i == editingIndex) {
                label.text = "✏️ Conversation " + (i + 1) + " এডিট হচ্ছে..."
                label.setTextColor(Color.parseColor("#D32F2F"))
            } else {
                label.text = "✔ Conversation " + (i + 1) + " সংরক্ষিত"
                label.setTextColor(Color.DKGRAY)
            }
            label.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )

            val editBtn = Button(this)
            editBtn.text = "এডিট"
            editBtn.textSize = 12f
            val index = i
            editBtn.setOnClickListener {
                startEdit(index)
            }

            val deleteBtn = Button(this)
            deleteBtn.text = "ডিলিট"
            deleteBtn.textSize = 12f
            deleteBtn.setOnClickListener {
                if (editingIndex == index) {
                    resetEditState()
                } else if (editingIndex > index) {
                    editingIndex--
                }
                turns.removeAt(index)
                refreshTurnsList()
                saveData()
                Toast.makeText(this, "Conversation " + (index + 1) + " ডিলিট হয়েছে", Toast.LENGTH_SHORT).show()
            }

            row.addView(label)
            row.addView(editBtn)
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
        resetEditState()
        currentImages.clear()
        promptInput.setText("")
        responseInput.setText("")
        imageLabel.text = "কোনো ছবি নেই"
        refreshTurnsList()
        saveData()
        Toast.makeText(this, "সব Conversation ডিলিট হয়েছে", Toast.LENGTH_SHORT).show()
    }

    //==========================================
    // HTML Import — পুরনো ফাইল থেকে ডেটা ফেরত
    //==========================================
    private fun startImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "text/html"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(intent, "HTML ফাইল নির্বাচন করুন"), PICK_HTML)
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    private fun extractPre(chunk: String, marker: String): String {
        val s = chunk.indexOf(marker)
        if (s < 0) return ""
        val start = s + marker.length
        val end = chunk.indexOf("</pre>", start)
        if (end < 0) return ""
        return unescapeHtml(chunk.substring(start, end))
    }

    private fun extractResponse(chunk: String): String {
        val hiddenMarker = "style=\"display:none\">"
        val hs = chunk.indexOf(hiddenMarker)
        if (hs >= 0) {
            val start = hs + hiddenMarker.length
            val end = chunk.indexOf("</pre>", start)
            if (end >= 0) return unescapeHtml(chunk.substring(start, end))
        }
        return extractPre(chunk, "class=\"response-text\">")
    }

    private fun importHtml(html: String) {
        val marker = "<div class=\"turn\">"
        var count = 0
        var idx = html.indexOf(marker)
        while (idx >= 0) {
            val nextIdx = html.indexOf(marker, idx + marker.length)
            val chunk = if (nextIdx >= 0) html.substring(idx, nextIdx) else html.substring(idx)

            val images = ArrayList<Bitmap>()
            val imgMarker = "data:image/jpeg;base64,"
            var searchFrom = 0
            while (true) {
                val ms = chunk.indexOf(imgMarker, searchFrom)
                if (ms < 0) break
                val bStart = ms + imgMarker.length
                val bEnd = chunk.indexOf("\"", bStart)
                if (bEnd < 0) break
                try {
                    val bytes = Base64.decode(chunk.substring(bStart, bEnd), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) images.add(bmp)
                } catch (ex: Exception) {
                }
                searchFrom = bEnd + 1
            }

            val prompt = extractPre(chunk, "class=\"prompt-text\">")
            val response = extractResponse(chunk)

            if (prompt.isNotEmpty() || response.isNotEmpty() || images.isNotEmpty()) {
                turns.add(ChatTurn(prompt, response, images))
                count++
            }
            idx = nextIdx
        }
        if (count > 0) {
            refreshTurnsList()
            saveData()
            Toast.makeText(this, count.toString() + " টি Conversation ইমপোর্ট হয়েছে", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "ডেটা পাওয়া যায়নি — ফাইলটি আমাদের অ্যাপের বানানো কিনা দেখুন", Toast.LENGTH_LONG).show()
        }
    }

    //==========================================
    // Auto Save — অ্যাপ বন্ধ করলেও ডেটা থাকবে
    //==========================================
    private fun saveData() {
        try {
            val dir = File(filesDir, "conversations")
            if (!dir.exists()) dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val root = JSONObject()
            val arr = JSONArray()
            for ((tIndex, turn) in turns.withIndex()) {
                val obj = JSONObject()
                obj.put("prompt", turn.prompt)
                obj.put("response", turn.response)
                val imgArr = JSONArray()
                for ((i, bmp) in turn.images.withIndex()) {
                    val fname = "img_" + tIndex + "_" + i + ".jpg"
                    val f = File(dir, fname)
                    FileOutputStream(f).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    imgArr.put(fname)
                }
                obj.put("images", imgArr)
                arr.put(obj)
            }
            root.put("turns", arr)
            File(dir, "index.json").writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    private fun loadData() {
        try {
            val dir = File(filesDir, "conversations")
            val indexFile = File(dir, "index.json")
            if (!indexFile.exists()) return
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("turns") ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val prompt = obj.optString("prompt", "")
                val response = obj.optString("response", "")
                val images = ArrayList<Bitmap>()
                val imgArr = obj.optJSONArray("images")
                if (imgArr != null) {
                    for (j in 0 until imgArr.length()) {
                        val f = File(dir, imgArr.getString(j))
                        if (f.exists()) {
                            val bmp = BitmapFactory.decodeFile(f.absolutePath)
                            if (bmp != null) images.add(bmp)
                        }
                    }
                }
                turns.add(ChatTurn(prompt, response, images))
            }
            if (turns.isNotEmpty()) refreshTurnsList()
        } catch (e: Exception) {
        }
    }

    private fun buildAllTurns(): ArrayList<ChatTurn> {
        val allTurns = ArrayList(turns)
        val curPrompt = promptInput.text.toString()
        val curResponse = responseInput.text.toString()
        val hasCurrent = curPrompt.isNotEmpty() || curResponse.isNotEmpty() || currentImages.isNotEmpty()
        if (hasCurrent) {
            if (editingIndex in allTurns.indices) {
                allTurns[editingIndex] = ChatTurn(curPrompt, curResponse, ArrayList(currentImages))
            } else {
                allTurns.add(ChatTurn(curPrompt, curResponse, ArrayList(currentImages)))
            }
        }
        return allTurns
    }

    //==========================================
    // HTML Export — AI-safe, অক্ষত কপি
    //==========================================
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
        val allTurns = buildAllTurns()
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
                sb.append("<pre id=\"r").append(tId).append("\" class=\"response-text\">")
                sb.append(escapeHtml(turn.response))
                sb.append("</pre>\n")
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
