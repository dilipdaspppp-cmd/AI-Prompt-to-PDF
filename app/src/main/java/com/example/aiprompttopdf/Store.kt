package com.example.aiprompttopdf

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Random

/** One saved conversation. images = picture file names inside the conversations folder. */
data class ChatTurn(val id: Long, val prompt: String, val response: String, val images: List<String>)

/** What is in the boxes right now (not yet added as a conversation). */
class Draft(val prompt: String, val response: String, val images: List<String>, val editingId: Long)

/**
 * Saves the conversation list and the unfinished draft in the app's own storage.
 *
 * Safety rules:
 *  - the list is written to a temporary file first and then swapped in one step,
 *    so a sudden power cut can never leave a half-written list;
 *  - if the saved list cannot be read, it is kept aside and NO picture is ever
 *    deleted in that session;
 *  - a picture file is only removed when nothing uses it any more and it is
 *    older than 30 minutes.
 */
class Store(filesDir: File) {

    val dir = File(filesDir, "conversations")

    @Volatile
    var loadOk = true
        private set

    private val random = Random()

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun file(name: String): File = File(dir, name)

    fun newImageName(ext: String): String {
        return "img_" + System.currentTimeMillis() + "_" + Integer.toHexString(random.nextInt() and 0x7fffffff) + "." + ext
    }

    /** idGen gives a fresh id to very old entries that never had one. */
    fun loadTurns(idGen: () -> Long): List<ChatTurn> {
        val main = File(dir, INDEX)
        val tmp = File(dir, INDEX_TMP)
        val fromMain = if (main.exists()) parseIndex(main, idGen) else null
        val fromTmp = if (tmp.exists()) parseIndex(tmp, idGen) else null
        if (fromMain != null && fromTmp != null) {
            // A complete temporary file that is newer means the app stopped
            // just before swapping it in: it holds the latest list.
            return if (tmp.lastModified() >= main.lastModified()) fromTmp else fromMain
        }
        if (fromMain != null) return fromMain
        if (fromTmp != null) return fromTmp
        if (main.exists()) {
            loadOk = false
            main.renameTo(File(dir, "index_broken_" + System.currentTimeMillis() + ".json"))
        }
        return emptyList()
    }

    private fun parseIndex(f: File, idGen: () -> Long): List<ChatTurn>? {
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("turns") ?: JSONArray()
            val list = ArrayList<ChatTurn>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var id = o.optLong("id", 0L)
                if (id <= 0L) id = idGen()
                val names = ArrayList<String>()
                val ia = o.optJSONArray("images")
                if (ia != null) {
                    for (j in 0 until ia.length()) {
                        val n = (ia.optString(j, "") ?: "")
                        if (isSafeName(n) && File(dir, n).isFile) names.add(n)
                    }
                }
                list.add(ChatTurn(id, (o.optString("prompt", "") ?: ""), (o.optString("response", "") ?: ""), names))
            }
            list
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun saveTurns(turns: List<ChatTurn>, draftImages: Collection<String>) {
        val root = JSONObject()
        root.put("version", 2)
        val arr = JSONArray()
        for (t in turns) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("prompt", t.prompt)
            o.put("response", t.response)
            val ia = JSONArray()
            for (n in t.images) ia.put(n)
            o.put("images", ia)
            arr.put(o)
        }
        root.put("turns", arr)
        writeAtomic(File(dir, INDEX), root.toString())
        if (loadOk) cleanup(turns, draftImages)
    }

    fun loadDraft(): Draft? {
        val f = File(dir, DRAFT)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val names = ArrayList<String>()
            val ia = o.optJSONArray("images")
            if (ia != null) {
                for (j in 0 until ia.length()) {
                    val n = (ia.optString(j, "") ?: "")
                    if (isSafeName(n) && File(dir, n).isFile) names.add(n)
                }
            }
            Draft((o.optString("prompt", "") ?: ""), (o.optString("response", "") ?: ""), names, o.optLong("editingId", -1L))
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun saveDraft(d: Draft) {
        val f = File(dir, DRAFT)
        if (d.prompt.isEmpty() && d.response.isEmpty() && d.images.isEmpty() && d.editingId <= 0L) {
            if (f.exists()) f.delete()
            return
        }
        val o = JSONObject()
        o.put("prompt", d.prompt)
        o.put("response", d.response)
        val ia = JSONArray()
        for (n in d.images) ia.put(n)
        o.put("images", ia)
        o.put("editingId", d.editingId)
        writeAtomic(f, o.toString())
    }

    private fun cleanup(turns: List<ChatTurn>, draftImages: Collection<String>) {
        val keep = HashSet<String>()
        keep.add(INDEX)
        keep.add(INDEX_TMP)
        keep.add(DRAFT)
        keep.add(DRAFT + ".tmp")
        for (t in turns) keep.addAll(t.images)
        keep.addAll(draftImages)
        val now = System.currentTimeMillis()
        val files = dir.listFiles() ?: return
        for (f in files) {
            val n = f.name
            if (keep.contains(n) || n.startsWith("index_broken_") || !f.isFile) continue
            if (now - f.lastModified() > 30L * 60L * 1000L) f.delete()
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            try {
                out.fd.sync()
            } catch (e: Exception) {
            }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save " + target.name)
        }
    }

    private fun isSafeName(n: String): Boolean {
        return n.isNotEmpty() && !n.contains('/') && !n.contains('\\') && n != "." && n != ".."
    }

    companion object {
        const val INDEX = "index.json"
        const val INDEX_TMP = "index.json.tmp"
        const val DRAFT = "draft.json"
    }
}
