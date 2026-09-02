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
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var promptInput: EditText
    private lateinit var responseInput: EditText
    private lateinit var turnsContainer: LinearLayout
    private lateinit var imageLabel: TextView
    private lateinit var rootScroll: ScrollView
    private lateinit var addImageButton: Button
    private lateinit var addTurnButton: Button
    private lateinit var cancelEditButton: Button
    private lateinit var deleteAllButton: Button
    private lateinit var importHtmlButton: Button
    private lateinit var generateHtmlButton: Button

    private val currentImages = ArrayList<Bitmap>()
    private val turns = ArrayList<ChatTurn>()
    private var editingIndex = -1

    private val PICK_IMAGE = 101
    private val PICK_HTML = 102

    // Max image size. Big enough to keep code screenshots readable.
    private val MAX_W = 1440
    private val MAX_H = 2400

    // true  = PNG export (lossless, code stays sharp, bigger file)
    // false = JPEG 95 export (smaller file, tiny quality loss)
    private val EXPORT_PNG = true

    private val io = Executors.newSingleThreadExecutor()
    private var busy = false
    private var idSeed = System.currentTimeMillis()

    data class ChatTurn(
        val id: Long,
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
        rootScroll = findViewById(R.id.rootScroll)
        addImageButton = findViewById(R.id.addImageButton)
        addTurnButton = findViewById(R.id.addTurnButton)
        cancelEditButton = findViewById(R.id.cancelEditButton)
        deleteAllButton = findViewById(R.id.deleteAllButton)
        importHtmlButton = findViewById(R.id.importHtmlButton)
        generateHtmlButton = findViewById(R.id.generateHtmlButton)

        addImageButton.setOnClickListener { pickImages() }
        addTurnButton.setOnClickListener { addNewTurn() }
        cancelEditButton.setOnClickListener { cancelEdit() }
        deleteAllButton.setOnClickListener { deleteAllTurns() }
        importHtmlButton.setOnClickListener { startImport() }
        generateHtmlButton.setOnClickListener { generateHtml() }

        enableInnerScroll(promptInput)
        enableInnerScroll(responseInput)

        updateImageLabel()
        loadData()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    //==========================================
    // Helpers
    //==========================================

    @Synchronized
    private fun newId(): Long {
        idSeed += 1
        return idSeed
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun setBusy(value: Boolean) {
        val on = !value
        addImageButton.isEnabled = on
        addTurnButton.isEnabled = on
        cancelEditButton.isEnabled = on
        deleteAllButton.isEnabled = on
        importHtmlButton.isEnabled = on
        generateHtmlButton.isEnabled = on
    }

    private fun startWork(): Boolean {
        if (busy) {
            Toast.makeText(this, "Please wait, previous task is still running", Toast.LENGTH_SHORT).show()
            return false
        }
        busy = true
        setBusy(true)
        return true
    }

    private fun endWork() {
        runOnUiThread {
            busy = false
            setBusy(false)
        }
    }

    private fun enableInnerScroll(view: EditText) {
        view.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun updateImageLabel() {
        if (currentImages.isEmpty()) {
            imageLabel.text = "No image attached"
        } else {
            imageLabel.text = "Attached images: " + currentImages.size
        }
    }

    //==========================================
    // Safe bitmap decoding (prevents OutOfMemory)
    //==========================================

    private fun sampleSizeFor(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (w / s > MAX_W || h / s > MAX_H) {
            s *= 2
            if (s >= 64) break
        }
        return s
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            return contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun decodeFileSampled(path: String): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun decodeBytesSampled(bytes: ByteArray): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    //==========================================
    // Image picking
    //==========================================

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(intent, "Select images"), PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(i).uri)
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty() && startWork()) {
                io.execute {
                    val loaded = ArrayList<Bitmap>()
                    for (u in uris) {
                        decodeBitmap(u)?.let { loaded.add(it) }
                    }
                    runOnUiThread {
                        currentImages.addAll(loaded)
                        updateImageLabel()
                    }
                    endWork()
                }
            }
        }

        if (requestCode == PICK_HTML && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null && startWork()) {
                io.execute {
                    importFromUri(uri)
                    endWork()
                }
            }
        }
    }

    //==========================================
    // Conversation list
    //==========================================

    private fun addNewTurn() {
        val prompt = promptInput.text.toString()
        val response = responseInput.text.toString()

        if (prompt.isEmpty() && response.isEmpty() && currentImages.isEmpty()) {
            Toast.makeText(this, "Write a prompt or a response first", Toast.LENGTH_SHORT).show()
            return
        }

        if (editingIndex in turns.indices) {
            turns[editingIndex] = ChatTurn(newId(), prompt, response, ArrayList(currentImages))
            Toast.makeText(this, "Conversation " + (editingIndex + 1) + " updated", Toast.LENGTH_SHORT).show()
        } else {
            turns.add(ChatTurn(newId(), prompt, response, ArrayList(currentImages)))
            Toast.makeText(this, "Conversation added. Total: " + turns.size, Toast.LENGTH_SHORT).show()
        }

        resetEditState()
        currentImages.clear()
        refreshTurnsList()
        saveData()
        promptInput.setText("")
        responseInput.setText("")
        updateImageLabel()
    }

    private fun startEdit(index: Int) {
        if (index !in turns.indices) return
        val turn = turns[index]
        editingIndex = index
        promptInput.setText(turn.prompt)
        responseInput.setText(turn.response)
        currentImages.clear()
        currentImages.addAll(turn.images)
        updateImageLabel()
        addTurnButton.text = "Update Conversation " + (index + 1)
        cancelEditButton.visibility = View.VISIBLE
        refreshTurnsList()
        rootScroll.fullScroll(ScrollView.FOCUS_UP)
        Toast.makeText(this, "Edit the text, then press the Update button", Toast.LENGTH_LONG).show()
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
        updateImageLabel()
        refreshTurnsList()
        Toast.makeText(this, "Editing cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun refreshTurnsList() {
        turnsContainer.removeAllViews()
        for (i in turns.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, 4, 0, 4)

            val label = TextView(this)
            val imgCount = turns[i].images.size
            if (i == editingIndex) {
                label.text = "[*] Conversation " + (i + 1) + " - editing now"
                label.setTextColor(Color.parseColor("#D32F2F"))
            } else {
                label.text = "[OK] Conversation " + (i + 1) + " saved (" + imgCount + " img)"
                label.setTextColor(Color.DKGRAY)
            }
            label.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )

            val index = i

            val editBtn = Button(this)
            editBtn.text = "Edit"
            editBtn.textSize = 12f
            editBtn.setOnClickListener { startEdit(index) }

            val deleteBtn = Button(this)
            deleteBtn.text = "Delete"
            deleteBtn.textSize = 12f
            deleteBtn.setOnClickListener {
                if (index !in turns.indices) return@setOnClickListener
                if (editingIndex == index) {
                    resetEditState()
                } else if (editingIndex > index) {
                    editingIndex -= 1
                }
                turns.removeAt(index)
                refreshTurnsList()
                saveData()
                Toast.makeText(this, "Conversation " + (index + 1) + " deleted", Toast.LENGTH_SHORT).show()
            }

            row.addView(label)
            row.addView(editBtn)
            row.addView(deleteBtn)
            turnsContainer.addView(row)
        }
    }

    private fun deleteAllTurns() {
        if (turns.isEmpty()) {
            Toast.makeText(this, "Nothing to delete", Toast.LENGTH_SHORT).show()
            return
        }
        turns.clear()
        resetEditState()
        currentImages.clear()
        promptInput.setText("")
        responseInput.setText("")
        updateImageLabel()
        refreshTurnsList()
        saveData()
        Toast.makeText(this, "All conversations deleted", Toast.LENGTH_SHORT).show()
    }

    private fun buildAllTurns(): ArrayList<ChatTurn> {
        val allTurns = ArrayList(turns)
        val curPrompt = promptInput.text.toString()
        val curResponse = responseInput.text.toString()
        val hasCurrent = curPrompt.isNotEmpty() || curResponse.isNotEmpty() || currentImages.isNotEmpty()
        if (hasCurrent) {
            if (editingIndex in allTurns.indices) {
                allTurns[editingIndex] = ChatTurn(
                    allTurns[editingIndex].id,
                    curPrompt,
                    curResponse,
                    ArrayList(currentImages)
                )
            } else {
                allTurns.add(ChatTurn(newId(), curPrompt, curResponse, ArrayList(currentImages)))
            }
        }
        return allTurns
    }

    //==========================================
    // Auto save (crash safe, lossless PNG)
    //==========================================

    private fun saveData() {
        val snapshot = ArrayList(turns)
        io.execute { writeDataSync(snapshot) }
    }

    private fun writeDataSync(list: List<ChatTurn>) {
        try {
            val dir = File(filesDir, "conversations")
            if (!dir.exists()) dir.mkdirs()

            val keep = HashSet<String>()
            keep.add("index.json")

            val root = JSONObject()
            val arr = JSONArray()

            for (turn in list) {
                val obj = JSONObject()
                obj.put("id", turn.id)
                obj.put("prompt", turn.prompt)
                obj.put("response", turn.response)
                val imgArr = JSONArray()
                for (i in turn.images.indices) {
                    val fname = "img_" + turn.id + "_" + i + ".png"
                    val target = File(dir, fname)
                    if (!target.exists()) {
                        val tmp = File(dir, fname + ".tmp")
                        FileOutputStream(tmp).use { out ->
                            turn.images[i].compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.delete()
                            continue
                        }
                    }
                    keep.add(fname)
                    imgArr.put(fname)
                }
                obj.put("images", imgArr)
                arr.put(obj)
            }
            root.put("turns", arr)

            val tmpIndex = File(dir, "index.json.tmp")
            tmpIndex.writeText(root.toString(), Charsets.UTF_8)
            val indexFile = File(dir, "index.json")
            if (indexFile.exists()) indexFile.delete()
            tmpIndex.renameTo(indexFile)

            dir.listFiles()?.forEach { f ->
                if (!keep.contains(f.name)) f.delete()
            }
        } catch (e: Exception) {
        } catch (e: OutOfMemoryError) {
        }
    }

    private fun loadData() {
        if (!startWork()) return
        io.execute {
            val loaded = ArrayList<ChatTurn>()
            var maxId = 0L
            try {
                val dir = File(filesDir, "conversations")
                val indexFile = File(dir, "index.json")
                if (indexFile.exists()) {
                    val root = JSONObject(indexFile.readText(Charsets.UTF_8))
                    val arr = root.optJSONArray("turns")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val prompt = obj.optString("prompt", "")
                            val response = obj.optString("response", "")
                            var id = obj.optLong("id", 0L)
                            if (id <= 0L) id = newId()
                            if (id > maxId) maxId = id
                            val images = ArrayList<Bitmap>()
                            val imgArr = obj.optJSONArray("images")
                            if (imgArr != null) {
                                for (j in 0 until imgArr.length()) {
                                    val f = File(dir, imgArr.getString(j))
                                    if (f.exists()) {
                                        decodeFileSampled(f.absolutePath)?.let { images.add(it) }
                                    }
                                }
                            }
                            loaded.add(ChatTurn(id, prompt, response, images))
                        }
                    }
                }
            } catch (e: Exception) {
            } catch (e: OutOfMemoryError) {
            }

            runOnUiThread {
                if (loaded.isNotEmpty()) {
                    turns.clear()
                    turns.addAll(loaded)
                    if (maxId > idSeed) idSeed = maxId
                    refreshTurnsList()
                }
            }
            endWork()
        }
    }

    //==========================================
    // HTML import (jpeg / png / webp all supported)
    //==========================================

    private fun startImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "text/html"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(intent, "Select an HTML file"), PICK_HTML)
    }

    // Order matters: &amp; must be replaced last. Do not change.
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

    private fun importFromUri(uri: Uri) {
        val html = readTextFromUri(uri)
        if (html == null) {
            showToast("The file could not be read")
            return
        }

        val parsed = ArrayList<ChatTurn>()
        val marker = "<div class=\"turn\">"
        val b64Mark = "base64,"
        var idx = html.indexOf(marker)

        while (idx >= 0) {
            val nextIdx = html.indexOf(marker, idx + marker.length)
            val chunk = if (nextIdx >= 0) html.substring(idx, nextIdx) else html.substring(idx)

            val images = ArrayList<Bitmap>()
            var searchFrom = 0
            while (true) {
                val ms = chunk.indexOf("data:image/", searchFrom)
                if (ms < 0) break
                val bm = chunk.indexOf(b64Mark, ms)
                if (bm < 0) break
                val bStart = bm + b64Mark.length
                val bEnd = chunk.indexOf("\"", bStart)
                if (bEnd < 0) break
                try {
                    val bytes = Base64.decode(chunk.substring(bStart, bEnd), Base64.DEFAULT)
                    decodeBytesSampled(bytes)?.let { images.add(it) }
                } catch (ex: Exception) {
                } catch (ex: OutOfMemoryError) {
                }
                searchFrom = bEnd + 1
            }

            val prompt = extractPre(chunk, "class=\"prompt-text\">")
            val response = extractPre(chunk, "class=\"response-text\">")

            if (prompt.isNotEmpty() || response.isNotEmpty() || images.isNotEmpty()) {
                parsed.add(ChatTurn(newId(), prompt, response, images))
            }
            idx = nextIdx
        }

        runOnUiThread {
            if (parsed.isNotEmpty()) {
                turns.addAll(parsed)
                refreshTurnsList()
                saveData()
                Toast.makeText(this, parsed.size.toString() + " conversation(s) imported", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "No data found. Was this file made by this app?", Toast.LENGTH_LONG).show()
            }
        }
    }

    //==========================================
    // HTML export (streamed, so huge files are safe)
    //==========================================

    // Order matters: & must be replaced first. Do not change.
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun generateHtml() {
        val allTurns = buildAllTurns()
        if (allTurns.isEmpty()) {
            Toast.makeText(this, "There is no data to export", Toast.LENGTH_SHORT).show()
            return
        }
        if (!startWork()) return
        Toast.makeText(this, "Generating HTML, please wait...", Toast.LENGTH_SHORT).show()
        io.execute {
            exportHtml(allTurns)
            endWork()
        }
    }

    private fun exportHtml(allTurns: List<ChatTurn>) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "AI_Chat_" + timeStamp + ".html"
        try {
            val writer: BufferedWriter
            val where: String

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    showToast("HTML save failed")
                    return
                }
                val out = contentResolver.openOutputStream(uri)
                if (out == null) {
                    showToast("HTML save failed")
                    return
                }
                writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 65536)
                where = "Download/" + fileName
            } else {
                val dir = getExternalFilesDir(null) ?: filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 65536)
                where = file.absolutePath
            }

            writer.use { w -> writeHtmlTo(w, allTurns) }
            showToast("HTML Saved: " + where)
        } catch (e: Exception) {
            showToast("Error: " + e.message)
        } catch (e: OutOfMemoryError) {
            showToast("Error: out of memory. Try exporting fewer images at a time.")
        }
    }

    private fun writeHtmlTo(w: Writer, allTurns: List<ChatTurn>) {
        w.write("<!DOCTYPE html>\n")
        w.write("<html lang=\"en\">\n")
        w.write("<head>\n")
        w.write("<meta charset=\"UTF-8\">\n")
        w.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        w.write("<title>AI Chat Export</title>\n")
        w.write("<style>\n")
        w.write("body{font-family:sans-serif;margin:16px;background:#fafafa;color:#111;-webkit-print-color-adjust:exact;print-color-adjust:exact;}\n")
        w.write("h1{font-size:22px;margin-bottom:4px;}\n")
        w.write(".meta{font-size:12px;color:#555;margin-top:0;margin-bottom:18px;}\n")
        w.write(".turn{background:#fff;border:1px solid #ddd;border-radius:12px;padding:14px;margin-bottom:45px;}\n")
        w.write(".turn h2{margin:0 0 12px 0;font-size:18px;color:#333;}\n")
        w.write(".prompt-box{border:2px solid #d32f2f;border-radius:10px;padding:10px;margin-bottom:14px;background:#FFEBEE;}\n")
        w.write(".response-box{border:2px solid #1565c0;border-radius:10px;padding:10px;background:#E3F2FD;}\n")
        w.write(".prompt-box h3{color:#d32f2f;font-size:15px;}\n")
        w.write(".response-box h3{color:#1565c0;font-size:15px;}\n")
        w.write(".box-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;}\n")
        w.write(".box-head h3{margin:0;}\n")
        w.write(".copy-btn{border:none;background:#333;color:#fff;border-radius:6px;padding:6px 12px;font-size:12px;cursor:pointer;}\n")
        w.write(".copy-btn:active{background:#555;}\n")
        w.write("pre{white-space:pre-wrap;word-wrap:break-word;overflow-wrap:break-word;font-family:monospace;font-size:13px;line-height:1.5;margin:0;padding:10px;background:#ffffff;border-radius:8px;border:1px solid #e0e0e0;}\n")
        w.write(".prompt-text{color:#d32f2f;}\n")
        w.write(".response-text{color:#1565c0;}\n")
        w.write("img{display:block;max-width:100%;height:auto;max-height:520px;object-fit:contain;margin:12px auto;border:1px solid #bbb;border-radius:8px;background:#fff;padding:4px;}\n")
        w.write("#copyToast{display:none;position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:10px 20px;border-radius:24px;font-size:14px;z-index:999;}\n")
        w.write("@page{margin:12mm;}\n")
        w.write("@media print{.copy-btn{display:none;}#copyToast{display:none;}body{background:#fff;margin:0;}}\n")
        w.write("</style>\n")
        w.write("</head>\n")
        w.write("<body>\n")
        w.write("<h1>AI Chat Export</h1>\n")

        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        w.write("<p class=\"meta\">Generated: ")
        w.write(escapeHtml(dateStr))
        w.write("</p>\n")
        w.flush()

        for ((index, turn) in allTurns.withIndex()) {
            val tId = index + 1
            w.write("<div class=\"turn\">\n")
            w.write("<h2>Conversation ")
            w.write(tId.toString())
            w.write("</h2>\n")

            if (turn.prompt.isNotEmpty() || turn.images.isNotEmpty()) {
                w.write("<div class=\"prompt-box\">\n")
                w.write("<div class=\"box-head\">\n")
                w.write("<h3>User Prompt</h3>\n")
                if (turn.prompt.isNotEmpty()) {
                    w.write("<button class=\"copy-btn\" onclick=\"copyEl(this,'p")
                    w.write(tId.toString())
                    w.write("')\">Copy</button>\n")
                }
                w.write("</div>\n")

                for (bmp in turn.images) {
                    writeImage(w, bmp)
                }

                if (turn.prompt.isNotEmpty()) {
                    w.write("<pre id=\"p")
                    w.write(tId.toString())
                    w.write("\" class=\"prompt-text\">")
                    w.write(escapeHtml(turn.prompt))
                    w.write("</pre>\n")
                }
                w.write("</div>\n")
            }

            if (turn.response.isNotEmpty()) {
                w.write("<div class=\"response-box\">\n")
                w.write("<div class=\"box-head\">\n")
                w.write("<h3>AI Response</h3>\n")
                w.write("<button class=\"copy-btn\" onclick=\"copyEl(this,'r")
                w.write(tId.toString())
                w.write("')\">Copy Full Response</button>\n")
                w.write("</div>\n")
                w.write("<pre id=\"r")
                w.write(tId.toString())
                w.write("\" class=\"response-text\">")
                w.write(escapeHtml(turn.response))
                w.write("</pre>\n")
                w.write("</div>\n")
            }

            w.write("</div>\n")
            w.flush()
        }

        w.write("<div id=\"copyToast\">Copied!</div>\n")
        w.write("<script>\n")
        w.write("function copyEl(btn,id){var el=document.getElementById(id);if(!el)return;doCopy(el.textContent,btn);}\n")
        w.write("function doCopy(text,btn){\n")
        w.write("function ok(){showToast('Copied!');if(btn){var old=btn.textContent;btn.textContent='Copied!';setTimeout(function(){btn.textContent=old;},1500);}}\n")
        w.write("function bad(){showToast('Copy failed - please select the text and copy manually');}\n")
        w.write("if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(text).then(ok,function(){if(legacy(text)){ok();}else{bad();}});}\n")
        w.write("else{if(legacy(text)){ok();}else{bad();}}}\n")
        w.write("function legacy(text){var done=false;var ta=document.createElement('textarea');ta.value=text;ta.setAttribute('readonly','');ta.style.position='fixed';ta.style.top='-1000px';document.body.appendChild(ta);ta.select();ta.setSelectionRange(0,text.length);try{done=document.execCommand('copy');}catch(e){done=false;}\n")
        w.write("document.body.removeChild(ta);return done;}\n")
        w.write("var toastTimer=null;\n")
        w.write("function showToast(msg){var t=document.getElementById('copyToast');if(!t)return;t.textContent=msg;t.style.display='block';if(toastTimer)clearTimeout(toastTimer);toastTimer=setTimeout(function(){t.style.display='none';},1800);}\n")
        w.write("</script>\n")
        w.write("</body>\n")
        w.write("</html>\n")
        w.flush()
    }

    // Writes one image straight into the stream in small base64 chunks,
    // so a huge screenshot never has to sit in memory as one giant string.
    private fun writeImage(w: Writer, bmp: Bitmap) {
        var scaled: Bitmap? = null
        var flat: Bitmap? = null
        try {
            var tw = bmp.width
            var th = bmp.height
            if (tw <= 0 || th <= 0) return

            var scale = 1f
            if (tw > MAX_W) scale = MAX_W.toFloat() / tw
            if (th > MAX_H) {
                val hs = MAX_H.toFloat() / th
                if (hs < scale) scale = hs
            }
            if (scale < 1f) {
                tw = (tw * scale).toInt()
                th = (th * scale).toInt()
            }
            if (tw < 1) tw = 1
            if (th < 1) th = 1

            val src: Bitmap
            if (tw == bmp.width && th == bmp.height) {
                src = bmp
            } else {
                val s = Bitmap.createScaledBitmap(bmp, tw, th, true)
                scaled = s
                src = s
            }

            val flatBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            flat = flatBmp
            val canvas = Canvas(flatBmp)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(src, 0f, 0f, null)

            val stream = ByteArrayOutputStream()
            if (EXPORT_PNG) {
                flatBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } else {
                flatBmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            val bytes = stream.toByteArray()
            stream.close()
            if (bytes.isEmpty()) return

            w.write("<img src=\"data:image/")
            if (EXPORT_PNG) w.write("png") else w.write("jpeg")
            w.write(";base64,")
            var off = 0
            while (off < bytes.size) {
                var len = bytes.size - off
                if (len > 3072) len = 3072
                w.write(Base64.encodeToString(bytes, off, len, Base64.NO_WRAP))
                off += len
            }
            w.write("\" alt=\"Prompt image\">\n")
            w.flush()
        } catch (e: Exception) {
        } catch (e: OutOfMemoryError) {
        } finally {
            if (scaled != null && scaled !== bmp) scaled.recycle()
            if (flat != null) flat.recycle()
        }
    }
}
