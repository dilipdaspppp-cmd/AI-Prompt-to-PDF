package com.example.aiprompttopdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Background workers for the whole app.
 * io:     saving, loading, adding pictures, bringing files back - always one after
 *         another, so a save can never run at the same time as a load.
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

    // The name given to the last download (or read from the files that were brought back).
    private var lastTitle = ""

    private val PICK_IMAGE = 101
    private val PICK_BACK = 103

    /** What the user chose after pressing "bring back". */
    private class Selection {
        var error: String? = null
        var parsed: HandoffText.Parsed? = null          // a downloaded conversation (text file + pictures)
        val files = HashMap<String, File>()             // its picture files: name -> copy
        val missing = ArrayList<String>()               // picture files that were not chosen
        var backup: HtmlCodec.ImportResult? = null      // a backup file
        var ignored = 0                                 // chosen files that do not belong
    }

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

        if (requestCode == PICK_BACK) {
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
            pendingImports++
            refreshButtons()
            showStatus("বেছে নেওয়া ফাইলগুলো পড়া হচ্ছে…")
            val cr = contentResolver
            val tmpDir = bringBackDir()
            Disk.io.execute {
                var sel = Selection()
                try {
                    sel = readSelection(cr, uris, tmpDir)
                } catch (e: Exception) {
                    sel.error = "ফাইলগুলো পড়া যায়নি: " + (e.message ?: "")
                } catch (e: OutOfMemoryError) {
                    sel.error = "ফাইলটা খুব বড়, ফোনের মেমরিতে আঁটল না"
                }
                val result = sel
                runOnUiThread {
                    pendingImports--
                    refreshButtons()
                    if (!busy) statusLabel.visibility = View.GONE
                    if (!gone()) showSelection(result, tmpDir)
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
                lastTitle = ""
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
            askName(all, BooleanArray(1) { true })
            return
        }
        val notAdded = all.size > turns.size
        val labels = Array<CharSequence>(all.size) { i -> conversationLabel(i, all[i], notAdded && i == all.size - 1) }
        val checked = BooleanArray(all.size) { true }
        AlertDialog.Builder(this)
            .setTitle("কোন কোন কথোপকথন AI-কে দেবেন?")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("এগিয়ে যান") { _, _ ->
                if (checked.none { it }) {
                    Toast.makeText(this, "অন্তত একটা কথোপকথন বেছে নিন", Toast.LENGTH_SHORT).show()
                } else {
                    askName(all, checked)
                }
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    /** A name for this download. It becomes part of the folder name, so it is easy to find later. */
    private fun askName(all: List<ChatTurn>, checked: BooleanArray) {
        val d = resources.displayMetrics.density
        val input = EditText(this)
        input.setSingleLine(true)
        input.hint = "যেমন: আমার অ্যাপের কাজ"
        input.setText(lastTitle)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        box.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("এই কথোপকথনের একটা নাম দিন")
            .setMessage("নামটা Download-এর ফোল্ডারের নামে থাকবে, পরে খুঁজে পেতে সুবিধা হবে। নাম না দিলে শুধু তারিখ থাকবে।")
            .setView(box)
            .setPositiveButton("তৈরি করুন") { _, _ ->
                val title = HandoffText.cleanTitle(input.text.toString())
                lastTitle = title
                if (ready()) planAiExport(all, checked, title)
            }
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

    private fun planAiExport(all: List<ChatTurn>, checked: BooleanArray, title: String) {
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
                    choosePictures(items, total, title, fileCount, exporter)
                } else {
                    runAiExport(items, total, title, exporter)
                }
            }
        }
    }

    /** More files than one chat takes: let the user choose which pictures go. */
    private fun choosePictures(items: List<AiExport.Item>, total: Int, title: String, fileCount: Int, exporter: AiExport) {
        val pics = ArrayList<AiExport.Picture>()
        val names = ArrayList<CharSequence>()
        for (item in items) {
            for (p in item.pictures) {
                pics.add(p)
                var s = "মেসেজ " + bn(item.number) + "-এর ছবি " + bn(p.number)
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
                runAiExport(items, total, title, exporter)
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun runAiExport(items: List<AiExport.Item>, total: Int, title: String, exporter: AiExport) {
        setBusy(true, "তৈরি হচ্ছে…")
        val now = Date()
        val stamp = stampNow(now)
        val generated = readableNow(now)
        Disk.work.execute {
            var result: AiExport.Result? = null
            var error: String? = null
            try {
                result = exporter.make(items, total, title, stamp, generated) { msg ->
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
                .append(" লাইন। সেভ হওয়া ফাইলটা আসল লেখার সাথে অক্ষরে অক্ষরে মিলিয়ে দেখা হয়েছে, একটা লাইনও বাদ পড়েনি। ফাইলটা থেকে সব আবার হুবহু ফেরত পড়া যাচ্ছে, সেটাও দেখে নেওয়া হয়েছে।")
        } else {
            sb.append("⚠ লেখার ফাইলটা মিলিয়ে দেখতে গিয়ে গরমিল পাওয়া গেছে (").append(r.problem)
                .append(")। এই ফাইলটা AI-কে দেবেন না, আবার তৈরি করুন।")
        }
        if (r.pictures > 0) {
            sb.append("\n\n✓ ছবি: ").append(bn(r.pictures)).append("টা")
            if (r.pictureFiles != r.pictures) sb.append(" (লম্বা ছবি ভাগ হয়ে ফাইল ").append(bn(r.pictureFiles)).append("টা)")
            sb.append("। প্রতিটার মাথায় লেখা আছে ওটা আপনার কত নম্বর মেসেজের ছবি, সাথে ওই মেসেজের প্রথম কয়েকটা শব্দ।")
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
            sb.append("\n\nপরে এই কথোপকথন আবার অ্যাপে আনতে চাইলে \"ফেরত আনুন\" চেপে এই ফোল্ডারের লেখার ফাইল আর ছবিগুলো একসাথে বেছে নেবেন।")
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
    // Backup (one file with everything)
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
                        sb.append("\n\nনতুন অ্যাপ বসানোর পর \"ফেরত আনুন\" চেপে শুধু এই ফাইলটা বেছে নিলে সব ফিরে আসবে।")
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

    //==========================================
    // "ফেরত আনুন": a downloaded conversation (its text file + pictures), or a backup file
    //==========================================

    private fun startImport() {
        AlertDialog.Builder(this)
            .setTitle("কী ফেরত আনবেন?")
            .setMessage(
                "১) AI-কে দেওয়ার জন্য ডাউনলোড করা কথোপকথন:\n" +
                    "Download-এর ভেতরে ওই কথোপকথনের ফোল্ডারটা খুলুন। লেখার ফাইলটায় (.txt) আঙুল চেপে ধরে রাখুন, " +
                    "তারপর ওই ফোল্ডারের সব ছবিতে একবার করে টোকা দিন। শেষে উপরে \"Select\" বা \"Open\" লেখায় চাপুন।\n\n" +
                    "২) ব্যাকআপ ফাইল:\nশুধু ওই একটা ফাইলে টোকা দিন।\n\n" +
                    "একবারে একটা ফোল্ডারের ফাইলই বেছে নিন।"
            )
            .setPositiveButton("ফাইল বাছুন") { _, _ -> openPicker() }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun openPicker() {
        // Every kind of file is allowed here on purpose: with a narrower filter
        // some phones show the text file or the pictures greyed out.
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(intent, PICK_BACK)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                startActivityForResult(Intent.createChooser(intent, "ফাইল বাছুন"), PICK_BACK)
            } catch (e2: Exception) {
                toast("এই ফোনে ফাইল বাছার পর্দা খোলা গেল না")
            }
        }
    }

    /** Copies of the chosen picture files are kept here while they are being brought back. */
    private fun bringBackDir(): File = File(cacheDir, "bringback")

    private fun clearDir(dir: File) {
        try {
            val list = dir.listFiles() ?: return
            for (f in list) f.delete()
        } catch (e: Exception) {
        }
    }

    private fun readHead(cr: ContentResolver, uri: Uri): ByteArray? {
        return try {
            cr.openInputStream(uri)?.use { s ->
                val buf = ByteArray(64)
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun copyToFile(cr: ContentResolver, uri: Uri, target: File): Boolean {
        return try {
            val input = cr.openInputStream(uri) ?: return false
            input.use { inp ->
                FileOutputStream(target).use { out -> inp.copyTo(out, 65536) }
            }
            target.length() > 0L
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    private fun sizeOf(cr: ContentResolver, uri: Uri): Long {
        var size = -1L
        try {
            cr.openAssetFileDescriptor(uri, "r")?.use { afd -> size = afd.length }
        } catch (e: Exception) {
        }
        return size
    }

    /** true when the first bytes look like our text file ("READ THIS FIRST") or like a backup file ("<"). */
    private fun beginsLikeOurs(head: ByteArray): Boolean {
        var i = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < head.size && (head[i] == ' '.code.toByte() || head[i] == '\n'.code.toByte() || head[i] == '\r'.code.toByte() || head[i] == '\t'.code.toByte())) i++
        if (i >= head.size) return false
        if (head[i] == '<'.code.toByte()) return true
        val mark = "READ THIS FIRST".toByteArray(Charsets.UTF_8)
        if (i + mark.size > head.size) return false
        for (k in mark.indices) if (head[i + k] != mark[k]) return false
        return true
    }

    /** The file's own name, as the phone shows it (for example "Message01_Picture1_20261113_101500.jpg"). */
    private fun displayName(cr: ContentResolver, uri: Uri): String {
        try {
            val c = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        val n: String? = c.getString(0)
                        if (n != null && n.isNotEmpty()) return n
                    }
                } finally {
                    c.close()
                }
            }
        } catch (e: Exception) {
        }
        val last = uri.lastPathSegment ?: return ""
        return last.substringAfterLast('/').substringAfterLast(':')
    }

    /** Runs in the background: looks at every chosen file and works out what was chosen. */
    private fun readSelection(cr: ContentResolver, uris: List<Uri>, tmpDir: File): Selection {
        val sel = Selection()
        if (!tmpDir.exists()) tmpDir.mkdirs()
        clearDir(tmpDir)
        val downloads = ArrayList<HandoffText.Parsed>()
        val backups = ArrayList<ByteArray>()
        val turnMark = "<div class=\"turn\">".toByteArray(Charsets.UTF_8)
        var pictures = 0
        var other = 0
        var sameName: String? = null
        for ((i, uri) in uris.withIndex()) {
            val head = readHead(cr, uri)
            if (head == null || head.isEmpty()) {
                other++
                continue
            }
            if (HtmlCodec.sniff(head, head.size) != null) {
                // a picture
                // A picture whose name the phone does not tell can never be mixed up with another one.
                val shown = displayName(cr, uri)
                val name = if (shown.isEmpty()) "unnamed-" + i else shown
                val copy = File(tmpDir, "p" + i + ".bin")
                if (!copyToFile(cr, uri, copy)) {
                    other++
                } else if (sel.files.containsKey(name)) {
                    sameName = name
                } else {
                    sel.files[name] = copy
                    pictures++
                }
                continue
            }
            // A very big file that does not even begin like one of ours (a video, say) is not loaded at all.
            if (sizeOf(cr, uri) > 32L * 1024L * 1024L && !beginsLikeOurs(head)) {
                other++
                continue
            }
            val bytes = readAllBytes(cr, uri)
            if (bytes == null) {
                other++
                continue
            }
            val parsed = HandoffText.parse(bytes)
            if (parsed != null) {
                downloads.add(parsed)
            } else if (HtmlCodec.indexOf(bytes, turnMark, 0, bytes.size) >= 0) {
                backups.add(bytes)
            } else {
                other++
            }
        }

        if (downloads.size > 1) {
            sel.error = "একসাথে " + bn(downloads.size) + "টা কথোপকথনের লেখার ফাইল বাছা হয়েছে। একবারে একটা ফোল্ডারের ফাইলই বেছে নিন: সেই ফোল্ডারের লেখার ফাইল আর তার ছবিগুলো।"
            return sel
        }
        if (downloads.size == 1) {
            val dup = sameName
            if (dup != null) {
                sel.error = "একই নামের দুটো ছবি বাছা হয়েছে (" + dup + ")। মনে হচ্ছে দুটো আলাদা ফোল্ডারের ছবি মিশে গেছে। একবারে একটা ফোল্ডারের ফাইলই বেছে নিন।"
                return sel
            }
            val parsed = downloads[0]
            sel.parsed = parsed
            val wanted = AiImport.wantedFiles(parsed)
            for (name in wanted) if (AiImport.find(sel.files, name) == null) sel.missing.add(name)
            var extra = 0
            val used = HashSet<File>()
            for (name in wanted) {
                val f = AiImport.find(sel.files, name)
                if (f != null) used.add(f)
            }
            for (f in sel.files.values) if (!used.contains(f)) extra++
            sel.ignored = extra + other + backups.size
            return sel
        }
        if (backups.size > 1) {
            sel.error = "একসাথে " + bn(backups.size) + "টা ব্যাকআপ ফাইল বাছা হয়েছে। একবারে একটা ব্যাকআপ ফাইল বেছে নিন।"
            return sel
        }
        if (backups.size == 1) {
            sel.backup = HtmlCodec.parse(backups[0]) { b -> ImageStore.importFromBytes(b, store) }
            return sel
        }
        if (pictures > 0) {
            sel.error = "শুধু ছবি বাছা হয়েছে, লেখার ফাইলটা (.txt) বাছা হয়নি। ওই ফোল্ডারের লেখার ফাইলটা আর ছবিগুলো একসাথে বেছে নিন।"
        } else {
            sel.error = "বেছে নেওয়া ফাইল এই অ্যাপের তৈরি নয়, অথবা ফাইলটা নষ্ট হয়ে গেছে। \"AI-কে দিন\" দিয়ে তৈরি ফোল্ডারের লেখার ফাইল, বা \"ব্যাকআপ রাখুন\" দিয়ে তৈরি ব্যাকআপ ফাইল বেছে নিন।"
        }
        return sel
    }

    private fun showNote(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("ঠিক আছে", null)
            .show()
    }

    /** Shows what was found in the chosen files and asks before anything is added to the list. */
    private fun showSelection(sel: Selection, tmpDir: File) {
        val error = sel.error
        if (error != null) {
            showNote("ফেরত আনা গেল না", error)
            return
        }
        val backup = sel.backup
        if (backup != null) {
            if (backup.turns.isEmpty()) {
                showNote("ফেরত আনা গেল না", "এই ফাইলে কোনো কথোপকথন পাওয়া যায়নি। এটা কি এই অ্যাপের ব্যাকআপ ফাইল?")
            } else if (turns.isEmpty()) {
                addBackup(backup)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("ব্যাকআপ ফেরত আনব?")
                    .setMessage(
                        "ব্যাকআপ ফাইলে " + bn(backup.turns.size) + "টা কথোপকথন আছে।\n\nতালিকায় এখন " + bn(turns.size) +
                            "টা কথোপকথন আছে। ব্যাকআপেরগুলো তার পরে যোগ হবে, আগেরগুলো মুছবে না।"
                    )
                    .setPositiveButton("হ্যাঁ, ফেরত আনুন") { _, _ -> if (ready()) addBackup(backup) }
                    .setNegativeButton("বাতিল", null)
                    .show()
            }
            return
        }
        val parsed = sel.parsed ?: return
        var havePictures = 0
        for (t in parsed.turns) {
            for (p in t.pictures) {
                if (p.state == HandoffPicture.ATTACHED && p.parts.any { AiImport.find(sel.files, it.file) != null }) havePictures++
            }
        }
        val allPictures = AiImport.pictureCount(parsed)
        val sb = StringBuilder()
        if (parsed.title.isNotEmpty()) sb.append("নাম: ").append(parsed.title).append("\n")
        sb.append("কথোপকথন: ").append(bn(parsed.turns.size)).append("টা\n")
        sb.append("ছবি: ").append(bn(havePictures)).append("টা")
        if (havePictures < allPictures) sb.append(" (থাকার কথা ").append(bn(allPictures)).append("টা)")
        if (sel.missing.isNotEmpty()) {
            sb.append("\n\n⚠ এই ছবির ফাইলগুলো বাছা হয়নি:")
            for ((i, name) in sel.missing.withIndex()) {
                if (i >= 10) {
                    sb.append("\n… আরও ").append(bn(sel.missing.size - 10)).append("টা")
                    break
                }
                sb.append("\n• ").append(name)
            }
            sb.append("\n\nএভাবে ফেরত আনলে ওই ছবিগুলো থাকবে না। সব ছবি চাইলে \"বাতিল\" চেপে আবার লেখার ফাইলের সাথে সব ছবি বেছে নিন।")
        }
        val notSent = AiImport.notSent(parsed)
        if (notSent > 0) {
            sb.append("\n\nডাউনলোড করার সময়ই ").append(bn(notSent)).append("টা ছবি বাদ ছিল, তাই সেগুলো এই ফাইলগুলোর মধ্যে নেই।")
        }
        if (parsed.format == 1) {
            sb.append("\n\nএগুলো অ্যাপের আগের সংস্করণে ডাউনলোড করা ফাইল। লেখা আর কোড ফেরত আসবে। ভাগ হয়ে যাওয়া লম্বা ছবি জোড়া লাগবে না, টুকরো হিসেবেই আসবে।")
        }
        if (sel.ignored > 0) {
            sb.append("\n\nবেছে নেওয়া ").append(bn(sel.ignored)).append("টা ফাইল এই কথোপকথনের নয়, সেগুলো বাদ থাকবে।")
        }
        if (turns.isNotEmpty()) {
            sb.append("\n\nতালিকায় এখন ").append(bn(turns.size)).append("টা কথোপকথন আছে। এগুলো তার পরে যোগ হবে, আগেরগুলো মুছবে না।")
        }
        AlertDialog.Builder(this)
            .setTitle("ফেরত আনব?")
            .setMessage(sb.toString())
            .setPositiveButton("হ্যাঁ, ফেরত আনুন") { _, _ -> if (ready()) restoreDownload(parsed, sel, tmpDir) }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun addBackup(backup: HtmlCodec.ImportResult) {
        for (t in backup.turns) turns.add(ChatTurn(newId(), t.prompt, t.response, t.images))
        refreshTurnsList()
        saveData()
        var msg = bn(backup.turns.size) + "টা কথোপকথন ফেরত এসেছে"
        if (backup.failedImages > 0) msg += " (" + bn(backup.failedImages) + "টা ছবি পড়া যায়নি)"
        toast(msg)
    }

    private fun restoreDownload(parsed: HandoffText.Parsed, sel: Selection, tmpDir: File) {
        pendingImports++
        refreshButtons()
        showStatus("ফেরত আনা হচ্ছে…")
        val importer = AiImport(store)
        Disk.io.execute {
            var res: AiImport.Result? = null
            var error: String? = null
            try {
                res = importer.restore(parsed, sel.files) { msg ->
                    runOnUiThread { if (pendingImports > 0 && !busy) showStatus(msg) }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } catch (e: OutOfMemoryError) {
                error = "ফোনের মেমরি কম পড়েছে"
            }
            clearDir(tmpDir)
            runOnUiThread {
                pendingImports--
                refreshButtons()
                if (!busy) statusLabel.visibility = View.GONE
                val r = res
                if (r == null || r.turns.isEmpty()) {
                    if (!gone()) showNote("ফেরত আনা গেল না", error ?: "ফাইলে কোনো কথোপকথন পাওয়া যায়নি।")
                } else {
                    for (t in r.turns) turns.add(ChatTurn(newId(), t.prompt, t.response, t.images))
                    if (parsed.title.isNotEmpty()) lastTitle = parsed.title
                    refreshTurnsList()
                    saveData()
                    if (!gone()) showRestored(parsed, sel, r)
                }
            }
        }
    }

    private fun showRestored(parsed: HandoffText.Parsed, sel: Selection, r: AiImport.Result) {
        val sb = StringBuilder()
        sb.append("✓ ").append(bn(r.turns.size)).append("টা কথোপকথন তালিকার শেষে যোগ হয়েছে।")
        if (parsed.format == 2) sb.append(" লেখা আর কোড ফাইলে যেমন ছিল, হুবহু তেমনই এসেছে।")
        if (r.pictures > 0) {
            sb.append("\n\n✓ ছবি: ").append(bn(r.pictures)).append("টা।")
            if (r.withStrip == 0) sb.append(" মাথার হলুদ পট্টি সরিয়ে দেওয়া হয়েছে।")
            if (r.joined > 0) sb.append(" ভাগ হয়ে থাকা ").append(bn(r.joined)).append("টা লম্বা ছবি আবার জুড়ে একটা করা হয়েছে।")
        }
        if (r.inParts > 0) {
            sb.append("\n\n").append(bn(r.inParts)).append("টা লম্বা ছবি জোড়া যায়নি, সেগুলো টুকরো হিসেবেই এসেছে (কিছু বাদ পড়েনি)।")
        }
        if (r.withStrip > 0) {
            sb.append("\n\n⚠ ").append(bn(r.withStrip)).append("টা ছবির হলুদ পট্টি নিশ্চিতভাবে চেনা যায়নি। ভুল করে ছবি কেটে না ফেলার জন্য সেগুলো পট্টিসহই রাখা হয়েছে।")
        }
        if (sel.missing.isNotEmpty()) {
            sb.append("\n\n⚠ ").append(bn(sel.missing.size)).append("টা ছবির ফাইল বাছা হয়নি, তাই সেগুলো আসেনি।")
        }
        if (r.failed.isNotEmpty()) {
            sb.append("\n\n⚠ এই ছবির ফাইলগুলো পড়া যায়নি:")
            for (f in r.failed) sb.append("\n• ").append(f)
        }
        sb.append("\n\nDownload-এর ফোল্ডারটা যেমন ছিল তেমনই আছে। আর দরকার না থাকলে ফোনের ফাইল অ্যাপ থেকে নিজে মুছে দিতে পারেন।")
        showNote("ফেরত এসেছে ✓", sb.toString())
    }
}
