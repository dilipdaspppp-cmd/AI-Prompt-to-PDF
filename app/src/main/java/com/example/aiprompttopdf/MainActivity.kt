package com.example.aiprompttopdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Background workers for the whole app.
 * io:     saving, loading, adding pictures, import - always one after another,
 *         so a save can never run at the same time as a load.
 * work:   long jobs (making the files for the AI, backup), so saving never waits for them.
 * thumbs: the small preview pictures.
 */
object Disk {
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    val work: ExecutorService = Executors.newSingleThreadExecutor()
    val thumbs: ExecutorService = Executors.newSingleThreadExecutor()
}

class MainActivity : Activity() {

    private lateinit var promptInput: EditText
    private lateinit var responseInput: EditText
    private lateinit var turnsContainer: LinearLayout
    private lateinit var thumbsContainer: LinearLayout
    private lateinit var imageLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var rootScroll: ScrollView
    private lateinit var addImageButton: Button
    private lateinit var addTurnButton: Button
    private lateinit var cancelEditButton: Button
    private lateinit var deleteAllButton: Button
    private lateinit var importHtmlButton: Button
    private lateinit var backupButton: Button
    private lateinit var aiButton: Button

    private lateinit var store: Store

    // Pictures of the conversation in the boxes right now (file names).
    private val currentImages = ArrayList<String>()
    private val turns = ArrayList<ChatTurn>()
    private var editingId = -1L
    private var loaded = false
    private var busy = false
    private var pendingImports = 0
    private var idSeed = System.currentTimeMillis()
    private val thumbCache = HashMap<String, Bitmap>()

    private val PICK_IMAGE = 101
    private val PICK_HTML = 102

    // Claude takes at most this many files in one chat.
    private val MAX_FILES = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(filesDir)

        promptInput = findViewById(R.id.promptInput)
        responseInput = findViewById(R.id.responseInput)
        turnsContainer = findViewById(R.id.turnsContainer)
        thumbsContainer = findViewById(R.id.thumbsContainer)
        imageLabel = findViewById(R.id.imageLabel)
        statusLabel = findViewById(R.id.statusLabel)
        rootScroll = findViewById(R.id.rootScroll)
        addImageButton = findViewById(R.id.addImageButton)
        addTurnButton = findViewById(R.id.addTurnButton)
        cancelEditButton = findViewById(R.id.cancelEditButton)
        deleteAllButton = findViewById(R.id.deleteAllButton)
        importHtmlButton = findViewById(R.id.importHtmlButton)
        backupButton = findViewById(R.id.backupButton)
        aiButton = findViewById(R.id.aiButton)

        addImageButton.setOnClickListener { pickImages() }
        addTurnButton.setOnClickListener { if (ready()) addNewTurn() }
        cancelEditButton.setOnClickListener { if (ready()) cancelEdit() }
        deleteAllButton.setOnClickListener { if (ready()) confirmDeleteAll() }
        importHtmlButton.setOnClickListener { if (ready()) startImport() }
        backupButton.setOnClickListener { if (ready()) startBackup() }
        aiButton.setOnClickListener { if (ready()) startAiExport() }

        enableInnerScroll(promptInput)
        enableInnerScroll(responseInput)

        updateImageViews()
        loadData()
    }

    override fun onPause() {
        super.onPause()
        // Whatever is in the boxes is kept, even if Android closes the app in the background.
        if (loaded) saveDraft()
    }

    //==========================================
    // Helpers
    //==========================================

    @Synchronized
    private fun newId(): Long {
        idSeed += 1
        return idSeed
    }

    private fun bn(n: Int): String = HtmlCodec.bn(n)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun gone(): Boolean = isFinishing || isDestroyed

    private fun refreshButtons() {
        val free = !busy && pendingImports == 0
        addImageButton.isEnabled = !busy
        addTurnButton.isEnabled = free
        cancelEditButton.isEnabled = free
        deleteAllButton.isEnabled = free
        importHtmlButton.isEnabled = free
        backupButton.isEnabled = free
        aiButton.isEnabled = free
    }

    /** false (with a short message) while loading, adding pictures or making files. */
    private fun ready(): Boolean {
        if (!loaded || busy || pendingImports > 0) {
            Toast.makeText(this, "একটু অপেক্ষা করুন, আগের কাজটা এখনও চলছে", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun showStatus(msg: String) {
        statusLabel.text = msg
        statusLabel.visibility = View.VISIBLE
    }

    private fun setBusy(on: Boolean, msg: String?) {
        busy = on
        // Keep the screen on while files are being made, so the work is not paused.
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        refreshButtons()
        if (msg != null) showStatus(msg) else statusLabel.visibility = View.GONE
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

    private fun editingIndex(): Int {
        if (editingId <= 0L) return -1
        for (i in turns.indices) if (turns[i].id == editingId) return i
        return -1
    }

    private fun stampNow(now: Date): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

    private fun readableNow(now: Date): String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(now)

    //==========================================
    // Loading and saving
    //==========================================

    private fun loadData() {
        busy = true
        refreshButtons()
        Disk.io.execute {
            var list: List<ChatTurn> = emptyList()
            var draft: Draft? = null
            try {
                list = store.loadTurns { newId() }
                draft = store.loadDraft()
            } catch (e: Exception) {
            } catch (e: OutOfMemoryError) {
            }
            val ok = store.loadOk
            runOnUiThread {
                turns.clear()
                for (t in list) {
                    turns.add(t)
                    synchronized(this) { if (t.id > idSeed) idSeed = t.id }
                }
                val d = draft
                if (d != null && promptInput.text.isEmpty() && responseInput.text.isEmpty() && currentImages.isEmpty()) {
                    promptInput.setText(d.prompt)
                    responseInput.setText(d.response)
                    currentImages.addAll(d.images)
                    if (d.editingId > 0L && turns.any { it.id == d.editingId }) {
                        editingId = d.editingId
                        addTurnButton.text = "Update Conversation " + (editingIndex() + 1)
                        cancelEditButton.visibility = View.VISIBLE
                    }
                }
                loaded = true
                busy = false
                refreshTurnsList()
                updateImageViews()
                refreshButtons()
                if (!ok && !gone()) {
                    AlertDialog.Builder(this)
                        .setTitle("সাবধান")
                        .setMessage("আগের সেভ করা তালিকাটা পড়া যায়নি। পুরনো ফাইলটা আলাদা করে রেখে দেওয়া হয়েছে, আর এবার কোনো ছবি মুছে ফেলা হবে না।")
                        .setPositiveButton("ঠিক আছে", null)
                        .show()
                }
            }
        }
    }

    private fun currentDraft(): Draft {
        return Draft(promptInput.text.toString(), responseInput.text.toString(), ArrayList(currentImages), editingId)
    }

    private fun saveData() {
        if (!loaded) return
        val snapshot = ArrayList(turns)
        val draft = currentDraft()
        Disk.io.execute {
            try {
                store.saveTurns(snapshot, draft.images)
                store.saveDraft(draft)
            } catch (e: Exception) {
                runOnUiThread { toast("সেভ করা গেল না: " + (e.message ?: "")) }
            }
        }
    }

    private fun saveDraft() {
        if (!loaded) return
        val draft = currentDraft()
        Disk.io.execute {
            try {
                store.saveDraft(draft)
            } catch (e: Exception) {
            }
        }
    }

    //==========================================
    // Pictures
    //==========================================

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(intent, "ছবি বাছুন"), PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == PICK_IMAGE) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val u = clip.getItemAt(i).uri
                    if (u != null) uris.add(u)
                }
            } else {
                val u = data.data
                if (u != null) uris.add(u)
            }
            if (uris.isEmpty()) return
            // Until these pictures are in, "Add", "Edit" and the other buttons wait,
            // so a picture can never land in the wrong conversation.
            pendingImports++
            refreshButtons()
            updateImageViews()
            val cr = contentResolver
            Disk.io.execute {
                val names = ArrayList<String>()
                var failed = 0
                for (u in uris) {
                    val n = ImageStore.importFromUri(cr, u, store)
                    if (n != null) names.add(n) else failed++
                }
                runOnUiThread {
                    pendingImports--
                    currentImages.addAll(names)
                    updateImageViews()
                    refreshButtons()
                    saveDraft()
                    if (failed > 0) {
                        toast(bn(failed) + "টা ছবি পড়া যায়নি। বাকি " + bn(names.size) + "টা যোগ হয়েছে।")
                    } else {
                        toast(bn(names.size) + "টা ছবি যোগ হয়েছে")
                    }
                }
            }
        }

        if (requestCode == PICK_HTML) {
            val uri = data.data ?: return
            pendingImports++
            refreshButtons()
            showStatus("ব্যাকআপ থেকে আনা হচ্ছে…")
            val cr = contentResolver
            Disk.io.execute {
                var result: HtmlCodec.ImportResult? = null
                var error: String? = null
                try {
                    val bytes = readAllBytes(cr, uri)
                    if (bytes == null) {
                        error = "ফাইলটা পড়া যায়নি"
                    } else {
                        result = HtmlCodec.parse(bytes) { b -> ImageStore.importFromBytes(b, store) }
                    }
                } catch (e: Exception) {
                    error = "ফাইলটা পড়া যায়নি: " + (e.message ?: "")
                } catch (e: OutOfMemoryError) {
                    error = "ফাইলটা খুব বড়, ফোনের মেমরিতে আঁটল না"
                }
                runOnUiThread {
                    pendingImports--
                    refreshButtons()
                    if (!busy) statusLabel.visibility = View.GONE
                    val r = result
                    if (r == null || r.turns.isEmpty()) {
                        toast(error ?: "কোনো কথোপকথন পাওয়া যায়নি। এটা কি এই অ্যাপের ব্যাকআপ ফাইল?")
                    } else {
                        for (t in r.turns) turns.add(ChatTurn(newId(), t.prompt, t.response, t.images))
                        refreshTurnsList()
                        saveData()
                        var msg = bn(r.turns.size) + "টা কথোপকথন ফেরত এসেছে"
                        if (r.failedImages > 0) msg += " (" + bn(r.failedImages) + "টা ছবি পড়া যায়নি)"
                        toast(msg)
                    }
                }
            }
        }
    }

    private fun readAllBytes(cr: ContentResolver, uri: Uri): ByteArray? {
        var size = -1L
        try {
            cr.openAssetFileDescriptor(uri, "r")?.use { afd -> size = afd.length }
        } catch (e: Exception) {
        }
        val input = cr.openInputStream(uri) ?: return null
        input.use { s ->
            if (size in 1L..(Int.MAX_VALUE - 64).toLong()) {
                val buf = ByteArray(size.toInt())
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n < buf.size) return buf.copyOf(n)
                val extra = s.readBytes()
                return if (extra.isEmpty()) buf else buf + extra
            }
            return s.readBytes()
        }
    }

    private fun updateImageViews() {
        if (pendingImports > 0) {
            imageLabel.text = "ছবি যোগ হচ্ছে… একটু অপেক্ষা করুন"
        } else if (currentImages.isEmpty()) {
            imageLabel.text = "কোনো ছবি যোগ করা হয়নি"
        } else {
            imageLabel.text = "যোগ করা ছবি: " + bn(currentImages.size) + "টা (ভুল ছবি থাকলে নিচে সরান চাপুন)"
        }
        val it = thumbCache.keys.iterator()
        while (it.hasNext()) {
            if (!currentImages.contains(it.next())) it.remove()
        }
        thumbsContainer.removeAllViews()
        val d = resources.displayMetrics.density
        val thumbH = (96 * d).toInt()
        for ((i, name) in currentImages.withIndex()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)

            val iv = ImageView(this)
            iv.adjustViewBounds = true
            iv.maxHeight = thumbH
            iv.maxWidth = (120 * d).toInt()
            iv.setBackgroundColor(Color.LTGRAY)
            val cached = thumbCache[name]
            if (cached != null) iv.setImageBitmap(cached) else loadThumb(name, iv)
            col.addView(iv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, thumbH))

            val tv = TextView(this)
            tv.text = "ছবি " + bn(i + 1)
            tv.textSize = 12f
            tv.gravity = Gravity.CENTER
            col.addView(tv)

            val rm = Button(this)
            rm.text = "সরান"
            rm.textSize = 11f
            rm.setOnClickListener { if (ready()) removeImage(name) }
            col.addView(rm)

            thumbsContainer.addView(col)
        }
    }

    private fun loadThumb(name: String, iv: ImageView) {
        val f = store.file(name)
        Disk.thumbs.execute {
            val bmp = ImageStore.decodeThumb(f, 240)
            runOnUiThread {
                if (bmp != null) {
                    thumbCache[name] = bmp
                    iv.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun removeImage(name: String) {
        currentImages.remove(name)
        updateImageViews()
        saveDraft()
    }

    //==========================================
    // Conversation list
    //==========================================

    private fun clearInputs() {
        editingId = -1L
        addTurnButton.text = "Add New Conversation"
        cancelEditButton.visibility = View.GONE
        currentImages.clear()
        promptInput.setText("")
        responseInput.setText("")
        updateImageViews()
    }

    /** true when the boxes hold something that is not saved in the list yet. */
    private fun hasUnsavedInput(): Boolean {
        val p = promptInput.text.toString()
        val r = responseInput.text.toString()
        val idx = editingIndex()
        if (idx >= 0) {
            val t = turns[idx]
            return p != t.prompt || r != t.response || currentImages != t.images
        }
        return p.isNotEmpty() || r.isNotEmpty() || currentImages.isNotEmpty()
    }

    private fun addNewTurn() {
        if (!loaded) return
        val prompt = promptInput.text.toString()
        val response = responseInput.text.toString()
        if (prompt.isEmpty() && response.isEmpty() && currentImages.isEmpty()) {
            Toast.makeText(this, "আগে প্রম্পট বা উত্তর লিখুন", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = editingIndex()
        if (idx >= 0) {
            turns[idx] = ChatTurn(turns[idx].id, prompt, response, ArrayList(currentImages))
            Toast.makeText(this, "কথোপকথন " + bn(idx + 1) + " আপডেট হয়েছে", Toast.LENGTH_SHORT).show()
        } else {
            turns.add(ChatTurn(newId(), prompt, response, ArrayList(currentImages)))
            Toast.makeText(this, "কথোপকথন যোগ হয়েছে। মোট: " + bn(turns.size), Toast.LENGTH_SHORT).show()
        }
        clearInputs()
        refreshTurnsList()
        saveData()
    }

    /** Edit was pressed. If the boxes hold unsaved text, ask first instead of throwing it away. */
    private fun requestEdit(id: Long) {
        if (!ready()) return
        if (id == editingId) return
        if (turns.none { it.id == id }) return
        if (!hasUnsavedInput()) {
            startEdit(id)
            return
        }
        val editing = editingIndex()
        val what = if (editing >= 0) {
            "কথোপকথন " + bn(editing + 1) + "-এ আপনার করা বদল এখনো Update করা হয়নি।"
        } else {
            "বাক্সের লেখা বা ছবি এখনো কথোপকথন হিসেবে যোগ করা হয়নি।"
        }
        AlertDialog.Builder(this)
            .setTitle("বাক্সের লেখা সেভ হয়নি")
            .setMessage(what + " অন্য কথোপকথন Edit করলে ওটা মুছে যাবে।")
            .setPositiveButton("আগে সেভ করুন") { _, _ ->
                addNewTurn()
                startEdit(id)
            }
            .setNeutralButton("ফেলে দিন") { _, _ -> startEdit(id) }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun startEdit(id: Long) {
        val index = turns.indexOfFirst { it.id == id }
        if (index < 0) return
        val turn = turns[index]
        editingId = turn.id
        promptInput.setText(turn.prompt)
        responseInput.setText(turn.response)
        currentImages.clear()
        currentImages.addAll(turn.images)
        updateImageViews()
        addTurnButton.text = "Update Conversation " + (index + 1)
        cancelEditButton.visibility = View.VISIBLE
        refreshTurnsList()
        rootScroll.fullScroll(ScrollView.FOCUS_UP)
        saveDraft()
        toast("লেখা বা ছবি বদলে Update বোতাম চাপুন")
    }

    private fun cancelEdit() {
        clearInputs()
        refreshTurnsList()
        saveDraft()
        Toast.makeText(this, "এডিট বাতিল হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun refreshTurnsList() {
        turnsContainer.removeAllViews()
        val editIdx = editingIndex()
        for (i in turns.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, 4, 0, 4)

            val label = TextView(this)
            val imgCount = turns[i].images.size
            if (i == editIdx) {
                label.text = "[*] Conversation " + (i + 1) + " - editing now"
                label.setTextColor(Color.parseColor("#D32F2F"))
            } else {
                label.text = "[OK] Conversation " + (i + 1) + " saved (" + imgCount + " img)"
                label.setTextColor(Color.DKGRAY)
            }
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val id = turns[i].id

            val editBtn = Button(this)
            editBtn.text = "Edit"
            editBtn.textSize = 12f
            editBtn.setOnClickListener { requestEdit(id) }

            val deleteBtn = Button(this)
            deleteBtn.text = "Delete"
            deleteBtn.textSize = 12f
            deleteBtn.setOnClickListener { if (ready()) confirmDelete(id) }

            row.addView(label)
            row.addView(editBtn)
            row.addView(deleteBtn)
            turnsContainer.addView(row)
        }
    }

    private fun confirmDelete(id: Long) {
        val idx = turns.indexOfFirst { it.id == id }
        if (idx < 0) return
        AlertDialog.Builder(this)
            .setTitle("মুছে ফেলবেন?")
            .setMessage("কথোপকথন " + bn(idx + 1) + " মুছে ফেলা হবে। এটা আর ফেরানো যাবে না।")
            .setPositiveButton("হ্যাঁ, মুছুন") { _, _ -> deleteTurn(id) }
            .setNegativeButton("না", null)
            .show()
    }

    private fun deleteTurn(id: Long) {
        val idx = turns.indexOfFirst { it.id == id }
        if (idx < 0) return
        // If this conversation is open for editing, empty the boxes too,
        // so it cannot come back as an extra conversation.
        if (id == editingId) clearInputs()
        turns.removeAt(idx)
        refreshTurnsList()
        saveData()
        Toast.makeText(this, "কথোপকথন " + bn(idx + 1) + " মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteAll() {
        if (turns.isEmpty()) {
            Toast.makeText(this, "মোছার মতো কিছু নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val editing = editingIndex() >= 0
        val extra = if (!editing && hasUnsavedInput()) " বাক্সে যে লেখাটা এখনো যোগ করা হয়নি, সেটা থেকে যাবে।" else ""
        AlertDialog.Builder(this)
            .setTitle("সব মুছে ফেলবেন?")
            .setMessage("সেভ করা সব " + bn(turns.size) + "টা কথোপকথন আর তাদের ছবি মুছে ফেলা হবে। এটা আর ফেরানো যাবে না।" + extra)
            .setPositiveButton("হ্যাঁ, সব মুছুন") { _, _ ->
                // Text in the boxes that was never added is not a saved conversation: it stays.
                if (editingIndex() >= 0) clearInputs()
                turns.clear()
                refreshTurnsList()
                saveData()
                Toast.makeText(this, "সব কথোপকথন মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("না", null)
            .show()
    }

    /** All saved conversations, plus whatever is still in the boxes (not added yet). */
    private fun buildAllTurns(): ArrayList<ChatTurn> {
        val allTurns = ArrayList(turns)
        val curPrompt = promptInput.text.toString()
        val curResponse = responseInput.text.toString()
        val hasCurrent = curPrompt.isNotEmpty() || curResponse.isNotEmpty() || currentImages.isNotEmpty()
        if (hasCurrent) {
            val idx = editingIndex()
            if (idx >= 0) {
                allTurns[idx] = ChatTurn(allTurns[idx].id, curPrompt, curResponse, ArrayList(currentImages))
            } else {
                allTurns.add(ChatTurn(0L, curPrompt, curResponse, ArrayList(currentImages)))
            }
        }
        return allTurns
    }

    //==========================================
    // "AI-কে দিন": one text file + the pictures, each with its label
    //==========================================

    private fun startAiExport() {
        val all = buildAllTurns()
        if (all.isEmpty()) {
            Toast.makeText(this, "দেওয়ার মতো কিছু নেই। আগে প্রম্পট বা উত্তর লিখুন।", Toast.LENGTH_SHORT).show()
            return
        }
        saveDraft()
        if (all.size == 1) {
            planAiExport(all, BooleanArray(1) { true })
            return
        }
        val notAdded = all.size > turns.size
        val labels = Array<CharSequence>(all.size) { i -> conversationLabel(i, all[i], notAdded && i == all.size - 1) }
        val checked = BooleanArray(all.size) { true }
        AlertDialog.Builder(this)
            .setTitle("কোন কোন কথোপকথন AI-কে দেবেন?")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("তৈরি করুন") { _, _ -> planAiExport(all, checked) }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun conversationLabel(i: Int, t: ChatTurn, notAdded: Boolean): String {
        val sb = StringBuilder("কথোপকথন " + bn(i + 1))
        if (t.images.isNotEmpty()) sb.append(" · ").append(bn(t.images.size)).append("টা ছবি")
        if (notAdded) sb.append(" · এখনো যোগ করা হয়নি")
        var preview = if (t.prompt.isNotEmpty()) t.prompt else t.response
        preview = preview.trim().replace('\n', ' ').replace('\r', ' ')
        if (preview.length > 40) preview = preview.take(40) + "…"
        if (preview.isNotEmpty()) sb.append("\n").append(preview)
        return sb.toString()
    }

    private fun planAiExport(all: List<ChatTurn>, checked: BooleanArray) {
        val items = ArrayList<AiExport.Item>()
        for (i in all.indices) {
            if (!checked[i]) continue
            val t = all[i]
            val pics = ArrayList<AiExport.Picture>()
            for ((k, name) in t.images.withIndex()) pics.add(AiExport.Picture(name, k + 1))
            // The conversation keeps the number it has in the app.
            items.add(AiExport.Item(i + 1, t.prompt, t.response, pics))
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "অন্তত একটা কথোপকথন বেছে নিন", Toast.LENGTH_SHORT).show()
            return
        }
        val total = all.size
        val exporter = AiExport(applicationContext, store)
        setBusy(true, "তৈরি হচ্ছে…")
        Disk.work.execute {
            try {
                exporter.plan(items)
            } catch (e: Exception) {
            }
            var fileCount = 1
            for (item in items) for (p in item.pictures) fileCount += p.parts
            runOnUiThread {
                if (fileCount > MAX_FILES && !gone()) {
                    setBusy(false, null)
                    choosePictures(items, total, fileCount, exporter)
                } else {
                    runAiExport(items, total, exporter)
                }
            }
        }
    }

    /** More files than one chat takes: let the user choose which pictures go. */
    private fun choosePictures(items: List<AiExport.Item>, total: Int, fileCount: Int, exporter: AiExport) {
        val pics = ArrayList<AiExport.Picture>()
        val names = ArrayList<CharSequence>()
        for (item in items) {
            for (p in item.pictures) {
                pics.add(p)
                var s = "কথোপকথন " + bn(item.number) + "-এর ছবি " + bn(p.number)
                if (p.parts > 1) s += " (লম্বা ছবি, " + bn(p.parts) + " ভাগে যাবে)"
                names.add(s)
            }
        }
        val checked = BooleanArray(pics.size) { true }
        AlertDialog.Builder(this)
            .setTitle("ফাইল " + bn(fileCount) + "টা, Claude নেয় " + bn(MAX_FILES) + "টা। কোন ছবি দেবেন?")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("তৈরি করুন") { _, _ ->
                for (i in pics.indices) pics[i].include = checked[i]
                runAiExport(items, total, exporter)
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun runAiExport(items: List<AiExport.Item>, total: Int, exporter: AiExport) {
        setBusy(true, "তৈরি হচ্ছে…")
        val now = Date()
        val stamp = stampNow(now)
        val generated = readableNow(now)
        Disk.work.execute {
            var result: AiExport.Result? = null
            var error: String? = null
            try {
                result = exporter.make(items, total, stamp, generated) { msg ->
                    runOnUiThread { if (busy) showStatus(msg) }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } catch (e: OutOfMemoryError) {
                error = "ফোনের মেমরি কম পড়েছে"
            }
            runOnUiThread {
                setBusy(false, null)
                showAiResult(result, error)
            }
        }
    }

    private fun showAiResult(r: AiExport.Result?, error: String?) {
        if (gone()) return
        if (r == null || r.textFile == null) {
            AlertDialog.Builder(this)
                .setTitle("সমস্যা হয়েছে")
                .setMessage("ফাইল তৈরি করা গেল না। " + (error ?: r?.problem ?: ""))
                .setPositiveButton("ঠিক আছে", null)
                .show()
            return
        }
        val sb = StringBuilder()
        sb.append("ফোল্ডার: Download/").append(r.folder).append("\n\n")
        if (r.textOk) {
            sb.append("✓ লেখার ফাইল: ").append(bn(r.conversations)).append("টা কথোপকথন, মোট ").append(bn(r.lines))
                .append(" লাইন। সেভ হওয়া ফাইলটা আসল লেখার সাথে অক্ষরে অক্ষরে মিলিয়ে দেখা হয়েছে, একটা লাইনও বাদ পড়েনি।")
        } else {
            sb.append("⚠ লেখার ফাইলটা মিলিয়ে দেখতে গিয়ে গরমিল পাওয়া গেছে (").append(r.problem)
                .append(")। এই ফাইলটা AI-কে দেবেন না, আবার তৈরি করুন।")
        }
        if (r.pictures > 0) {
            sb.append("\n\n✓ ছবি: ").append(bn(r.pictures)).append("টা")
            if (r.pictureFiles != r.pictures) sb.append(" (লম্বা ছবি ভাগ হয়ে ফাইল ").append(bn(r.pictureFiles)).append("টা)")
            sb.append("। প্রতিটার মাথায় লেখা আছে ওটা কোন কথোপকথনের ছবি।")
        }
        if (r.failedPictures > 0) sb.append("\n\n⚠ ").append(bn(r.failedPictures)).append("টা ছবি তৈরি করা যায়নি।")
        if (r.leftOut > 0) sb.append("\n\n").append(bn(r.leftOut)).append("টা ছবি আপনি বাদ দিয়েছেন।")
        if (r.files.size > MAX_FILES) {
            sb.append("\n\nমোট ফাইল ").append(bn(r.files.size)).append("টা। Claude এক চ্যাটে ").append(bn(MAX_FILES))
                .append("টা নেয়, তাই লেখার ফাইলটা আর সবচেয়ে দরকারি ছবিগুলো দিন।")
        }
        val uris = ArrayList<Uri>()
        for (f in r.files) {
            val u = f.uri
            if (u != null) uris.add(u)
        }
        val canShare = r.textOk && uris.size == r.files.size && uris.isNotEmpty()
        if (canShare) {
            sb.append("\n\nএখন \"শেয়ার করুন\" চেপে Claude বা অন্য AI বেছে নিন। শেয়ার না হলে AI-এর অ্যাপে ফাইল জোড়ার বোতাম চেপে Download-এর এই ফোল্ডার থেকে ফাইলগুলো দিন।")
        } else if (r.textOk) {
            sb.append("\n\nAI-এর অ্যাপে ফাইল জোড়ার বোতাম চেপে এই ফোল্ডার থেকে ফাইলগুলো দিন: ").append(r.textFile.where)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (r.textOk) "তৈরি হয়েছে ✓" else "সাবধান")
            .setMessage(sb.toString())
        if (canShare) {
            b.setPositiveButton("শেয়ার করুন") { _, _ -> shareFiles(uris) }
            b.setNegativeButton("পরে", null)
        } else {
            b.setPositiveButton("ঠিক আছে", null)
        }
        b.show()
    }

    private fun shareFiles(uris: ArrayList<Uri>) {
        val i: Intent
        if (uris.size == 1) {
            i = Intent(Intent.ACTION_SEND)
            i.putExtra(Intent.EXTRA_STREAM, uris[0])
            i.type = "text/plain"
        } else {
            i = Intent(Intent.ACTION_SEND_MULTIPLE)
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.type = "*/*"
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "কোন AI-কে দেবেন?"))
        } catch (e: Exception) {
            toast("শেয়ার করা গেল না। ফাইলগুলো Download ফোল্ডারে আছে।")
        }
    }

    //==========================================
    // Backup (one file with everything) and bringing it back
    //==========================================

    private fun startBackup() {
        val all = buildAllTurns()
        if (all.isEmpty()) {
            Toast.makeText(this, "ব্যাকআপ রাখার মতো কিছু নেই", Toast.LENGTH_SHORT).show()
            return
        }
        saveDraft()
        val exportTurns = ArrayList<ExportTurn>()
        for (t in all) exportTurns.add(ExportTurn(t.prompt, t.response, t.images.map { store.file(it) }))
        val now = Date()
        val name = "AI_Chat_Backup_" + stampNow(now) + ".html"
        val generated = readableNow(now)
        val ctx = applicationContext
        setBusy(true, "ব্যাকআপ তৈরি হচ্ছে…")
        Disk.work.execute {
            var saved: SavedFile? = null
            var error: String? = null
            var verified = false
            var images = 0
            var missing = 0
            try {
                val p = SaveUtil.open(ctx, name, "text/html", null)
                if (p == null) {
                    error = "Download ফোল্ডারে ফাইল তৈরি করা গেল না"
                } else {
                    var ok = false
                    try {
                        val w = BufferedWriter(OutputStreamWriter(p.out, Charsets.UTF_8), 65536)
                        val stats = HtmlCodec.writeHtml(w, exportTurns, generated)
                        w.flush()
                        images = stats.images
                        missing = stats.missing
                        ok = true
                    } finally {
                        saved = SaveUtil.finish(ctx, p, ok)
                    }
                    val s = saved
                    if (s != null) {
                        // Read the backup again and check that everything can be brought back.
                        val back = SaveUtil.readBack(ctx, s)
                        if (back != null) verified = backupMatches(back, exportTurns, images)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } catch (e: OutOfMemoryError) {
                error = "ফোনের মেমরি কম পড়েছে"
            }
            runOnUiThread {
                setBusy(false, null)
                if (!gone()) {
                    val s = saved
                    if (s == null) {
                        AlertDialog.Builder(this)
                            .setTitle("সমস্যা হয়েছে")
                            .setMessage("ব্যাকআপ রাখা গেল না। " + (error ?: ""))
                            .setPositiveButton("ঠিক আছে", null)
                            .show()
                    } else {
                        val sb = StringBuilder()
                        sb.append("সেভ হয়েছে: ").append(s.where).append("\n\n")
                        sb.append("কথোপকথন ").append(bn(exportTurns.size)).append("টা, ছবি ").append(bn(images)).append("টা।")
                        if (verified) {
                            sb.append("\n\n✓ ফাইলটা আবার পড়ে মিলিয়ে দেখা হয়েছে, সব ঠিক আছে।")
                        } else {
                            sb.append("\n\n⚠ ফাইলটা মিলিয়ে দেখা যায়নি। আর একবার ব্যাকআপ নিয়ে দেখুন।")
                        }
                        if (missing > 0) sb.append("\n\n⚠ ").append(bn(missing)).append("টা ছবি খুঁজে পাওয়া যায়নি।")
                        sb.append("\n\nনতুন অ্যাপ বসানোর পর \"ব্যাকআপ ফেরত আনুন\" চেপে এই ফাইলটা বেছে নিলে সব ফিরে আসবে।")
                        AlertDialog.Builder(this)
                            .setTitle("ব্যাকআপ রাখা হয়েছে ✓")
                            .setMessage(sb.toString())
                            .setPositiveButton("ঠিক আছে", null)
                            .show()
                    }
                }
            }
        }
    }

    /** true when the saved backup holds every conversation's text exactly and all pictures. */
    private fun backupMatches(file: ByteArray, expected: List<ExportTurn>, images: Int): Boolean {
        var count = 0
        val res = HtmlCodec.parse(file) { _ ->
            count++
            "x"
        }
        if (res.failedImages != 0 || count != images) return false
        val want = ArrayList<ExportTurn>()
        for (t in expected) {
            if (t.prompt.isNotEmpty() || t.response.isNotEmpty() || t.images.any { it.isFile }) want.add(t)
        }
        if (res.turns.size != want.size) return false
        for (i in want.indices) {
            if (res.turns[i].prompt != want[i].prompt || res.turns[i].response != want[i].response) return false
        }
        return true
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "text/html"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(intent, "ব্যাকআপ ফাইল বাছুন"), PICK_HTML)
    }
}
