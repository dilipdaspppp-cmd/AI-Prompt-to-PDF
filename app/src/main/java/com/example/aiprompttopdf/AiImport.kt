package com.example.aiprompttopdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Brings a downloaded conversation back into the app.
 *
 * The text (messages, replies, code) comes from the text file, exactly as it is
 * written there (see HandoffText.parse). This class does the pictures:
 *  - the yellow label strip at the top of every picture file is taken off again;
 *  - a very tall picture that was saved in several parts is put back together,
 *    and rows that were repeated between two parts are used only once.
 *
 * The text file notes for every picture file how tall its strip is and where the
 * picture sits, so nothing has to be guessed. Only for files made by an older
 * version of the app (no such notes) is the strip found by looking at the picture;
 * if it cannot be found for sure, the picture is kept whole, strip and all,
 * rather than cutting anything off it.
 */
class AiImport(private val store: Store) {

    private var keptWhole = 0

    class Turn(val prompt: String, val response: String, val images: List<String>)

    /**
     * pictures = how many pictures came back;
     * joined   = tall pictures that were put together again from their parts;
     * inParts  = tall pictures that could not be joined and came back as separate parts;
     * withStrip = picture files whose strip could not be found for sure, kept whole;
     * failed   = picture files that could not be read.
     */
    class Result(
        val turns: List<Turn>, val pictures: Int, val joined: Int, val inParts: Int,
        val withStrip: Int, val failed: List<String>
    )

    /** Where the picture itself sits inside a saved file. exact = taken from the notes in the text file. */
    private class Box(val left: Int, val top: Int, val width: Int, val height: Int, val exact: Boolean)

    companion object {
        // A picture bigger than this is not put back together (the phone's memory would not hold it).
        const val MAX_JOIN_PIXELS = 24000000L

        /** Every picture file the text file names, in order. */
        fun wantedFiles(parsed: HandoffText.Parsed): List<String> {
            val out = ArrayList<String>()
            for (t in parsed.turns) {
                for (p in t.pictures) {
                    if (p.state != HandoffPicture.ATTACHED) continue
                    for (part in p.parts) out.add(part.file)
                }
            }
            return out
        }

        /** How many pictures the conversation has in its files. */
        fun pictureCount(parsed: HandoffText.Parsed): Int {
            var n = 0
            for (t in parsed.turns) for (p in t.pictures) if (p.state == HandoffPicture.ATTACHED) n++
            return n
        }

        /** Pictures that were already left out when the files were made. */
        fun notSent(parsed: HandoffText.Parsed): Int {
            var n = 0
            for (t in parsed.turns) for (p in t.pictures) if (p.state != HandoffPicture.ATTACHED) n++
            return n
        }

        /**
         * The chosen file with this name. Capital and small letters count as the same,
         * and so does a different ending (".jpeg" for ".jpg"), because some phones change those.
         */
        fun find(files: Map<String, File>, name: String): File? {
            val f = files[name]
            if (f != null) return f
            for (e in files.entries) if (e.key.equals(name, ignoreCase = true)) return e.value
            val stem = name.substringBeforeLast('.')
            for (e in files.entries) if (e.key.substringBeforeLast('.').equals(stem, ignoreCase = true)) return e.value
            return null
        }
    }

    /** files = the picture files the user chose: file name -> a copy of the file. */
    fun restore(parsed: HandoffText.Parsed, files: Map<String, File>, progress: (String) -> Unit): Result {
        val turns = ArrayList<Turn>()
        val failed = ArrayList<String>()
        var pictures = 0
        var joined = 0
        var inParts = 0
        keptWhole = 0
        var wanted = 0
        for (t in parsed.turns) {
            for (p in t.pictures) {
                if (p.state == HandoffPicture.ATTACHED && p.parts.any { find(files, it.file) != null }) wanted++
            }
        }
        var done = 0
        for (t in parsed.turns) {
            val names = ArrayList<String>()
            for (p in t.pictures) {
                if (p.state != HandoffPicture.ATTACHED) continue
                val have = ArrayList<Pair<PicturePart, File>>()
                for (part in p.parts) {
                    val f = find(files, part.file)
                    if (f != null) have.add(Pair(part, f))
                }
                if (have.isEmpty()) continue
                done++
                progress("ছবি ফেরত আনা হচ্ছে… " + HtmlCodec.bn(done) + " / " + HtmlCodec.bn(wanted))
                val made = ArrayList<String>()
                if (p.parts.size > 1 && have.size == p.parts.size) {
                    val one = join(have)
                    if (one != null) {
                        made.add(one)
                        joined++
                    }
                }
                if (made.isEmpty()) {
                    // A normal picture, or a tall one whose parts cannot be joined: each file on its own.
                    for (h in have) {
                        val n = single(h.first, h.second)
                        if (n != null) made.add(n) else failed.add(h.first.file)
                    }
                }
                if (made.isNotEmpty()) pictures++
                if (made.size > 1) inParts++
                names.addAll(made)
            }
            if (t.prompt.isNotEmpty() || t.response.isNotEmpty() || names.isNotEmpty()) {
                turns.add(Turn(t.prompt, t.response, names))
            }
        }
        return Result(turns, pictures, joined, inParts, keptWhole, failed)
    }

    // ---------------------------------------------------------------------

    private fun box(part: PicturePart, f: File): Box? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, o)
        val fw = o.outWidth
        val fh = o.outHeight
        if (fw <= 0 || fh <= 0) return null
        if (part.strip >= 0 && part.left >= 0 && part.width > 0 && part.height > 0 &&
            part.strip + part.height == fh && part.left + part.width <= fw) {
            return Box(part.left, part.strip, part.width, part.height, true)
        }
        // An older download, or the file was changed on the way: look for the strip in the picture.
        val strip = findStrip(f, fw, fh, if (part.strip < 0) 2.9f else 4.0f)
        if (strip <= 0 || strip >= fh) return Box(0, 0, fw, fh, false)
        return Box(0, strip, fw, fh - strip, false)
    }

    /** One picture file: the strip is taken off, the rest is kept. Returns the new file name, or null. */
    private fun single(part: PicturePart, f: File): String? {
        try {
            val b = box(part, f) ?: return null
            val type = HtmlCodec.typeOfFile(f) ?: return null
            if (b.top == 0 && b.left == 0) {
                // No strip was found: keep the file exactly as it is.
                val name = store.newImageName(if (type == "jpeg") "jpg" else type)
                val out = store.file(name)
                f.copyTo(out, true)
                if (out.length() > 0L) {
                    keptWhole++
                    return name
                }
                out.delete()
                return null
            }
            val bmp = decodeRegion(f, Rect(b.left, b.top, b.left + b.width, b.top + b.height)) ?: return null
            try {
                return saveBitmap(bmp, type != "png")
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    /** The parts of one tall picture, put back together. null = not possible (then the parts stay separate). */
    private fun join(have: List<Pair<PicturePart, File>>): String? {
        var out: Bitmap? = null
        try {
            val boxes = ArrayList<Box>()
            var total = 0L
            for ((i, h) in have.withIndex()) {
                val b = box(h.first, h.second) ?: return null
                if (!b.exact) return null
                if (i > 0 && b.width != boxes[0].width) return null
                val ov = if (i == 0) 0 else h.first.overlap
                if (ov < 0 || ov >= b.height) return null
                boxes.add(b)
                total += b.height - ov
            }
            val w = boxes[0].width
            if (total <= 0L || total > 60000L || w.toLong() * total > MAX_JOIN_PIXELS) return null
            val o = Bitmap.createBitmap(w, total.toInt(), Bitmap.Config.ARGB_8888)
            out = o
            val c = Canvas(o)
            c.drawColor(Color.WHITE)
            var y = 0
            for ((i, h) in have.withIndex()) {
                val b = boxes[i]
                val ov = if (i == 0) 0 else h.first.overlap
                // Rows that are already there from the part before are skipped.
                val piece = decodeRegion(h.second, Rect(b.left, b.top + ov, b.left + b.width, b.top + b.height)) ?: return null
                try {
                    c.drawBitmap(piece, 0f, y.toFloat(), null)
                } finally {
                    piece.recycle()
                }
                y += b.height - ov
            }
            return saveBitmap(o, HtmlCodec.typeOfFile(have[0].second) != "png")
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            out?.recycle()
        }
    }

    private fun saveBitmap(bmp: Bitmap, lossy: Boolean): String? {
        val name = store.newImageName(if (lossy) "jpg" else "png")
        val out = store.file(name)
        var ok = false
        try {
            FileOutputStream(out).use { fos ->
                val bos = BufferedOutputStream(fos, 65536)
                ok = if (lossy) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                } else {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                }
                bos.flush()
            }
        } catch (e: Exception) {
            ok = false
        }
        if (!ok || out.length() <= 0L) {
            out.delete()
            return null
        }
        return name
    }

    /** One rectangle of a picture file, without loading the rest of it. */
    private fun decodeRegion(f: File, r: Rect): Bitmap? {
        var dec: BitmapRegionDecoder? = null
        try {
            dec = BitmapRegionDecoder.newInstance(f.absolutePath, false)
            val o = BitmapFactory.Options()
            o.inPreferredConfig = Bitmap.Config.ARGB_8888
            val b: Bitmap? = dec?.decodeRegion(r, o)
            if (b != null) return b
        } catch (e: Exception) {
        } finally {
            try {
                dec?.recycle()
            } catch (e: Exception) {
            }
        }
        // This phone cannot read the file piece by piece: load it whole and cut the piece out.
        val o = BitmapFactory.Options()
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        val full: Bitmap = BitmapFactory.decodeFile(f.absolutePath, o) ?: return null
        if (r.left < 0 || r.top < 0 || r.right > full.width || r.bottom > full.height || r.right <= r.left || r.bottom <= r.top) {
            full.recycle()
            return null
        }
        val cut = Bitmap.createBitmap(full, r.left, r.top, r.right - r.left, r.bottom - r.top)
        if (cut !== full) full.recycle()
        return cut
    }

    /**
     * Finds where the yellow strip ends by looking at the picture: at the right edge
     * (where the strip never has text) the rows are yellow, then comes the thin black
     * line, then the picture. Returns the first row of the picture, or -1.
     * k = how tall the strip is compared with its text size (2.9 in the older version, 4.0 now).
     */
    private fun findStrip(f: File, fw: Int, fh: Int, k: Float): Int {
        val bandW = maxOf(2, fw / 50)
        val scanH = minOf(fh, fw / 4 + 48)
        if (fw < 8 || scanH < 16) return -1
        val b = decodeRegion(f, Rect(fw - bandW, 0, fw, scanH)) ?: return -1
        try {
            val w = b.width
            val h = b.height
            if (w <= 0 || h <= 0) return -1
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val rr = IntArray(h)
            val gg = IntArray(h)
            val bb = IntArray(h)
            for (y in 0 until h) {
                var r = 0
                var g = 0
                var bl = 0
                for (x in 0 until w) {
                    val c = px[y * w + x]
                    r += (c shr 16) and 255
                    g += (c shr 8) and 255
                    bl += c and 255
                }
                rr[y] = r / w
                gg[y] = g / w
                bb[y] = bl / w
            }
            fun yellow(y: Int): Boolean = rr[y] >= 200 && gg[y] >= 180 && bb[y] <= 150 && rr[y] - bb[y] >= 80
            fun dark(y: Int): Boolean = (rr[y] * 77 + gg[y] * 150 + bb[y] * 29) / 256 <= 90
            var y0 = 0
            while (y0 < h && yellow(y0)) y0++
            if (y0 < 12 || y0 >= h - 1) return -1
            // one or two rows may be blurred between the yellow and the black line
            var d = y0
            while (d < h && d - y0 < 2 && !dark(d)) d++
            if (d >= h || !dark(d)) return -1
            var e = d
            while (e < h && dark(e)) e++
            val run = e - d
            val expect = lineRows(d, k)
            // If the picture itself is dark at the top, the black line seems longer than it is:
            // then the known thickness of the line is used.
            return if (run >= expect - 1 && run <= expect + 1) e else d + expect
        } finally {
            b.recycle()
        }
    }

    /**
     * How many rows the black line under the strip has, when the line begins at row d.
     * (The app draws a strip of S rows with a line of about 0.08 * S / k rows, at least 2.)
     */
    private fun lineRows(d: Int, k: Float): Int {
        for (n in 2..40) {
            val s = d + n
            val lo = Math.round(maxOf(2f, 0.08f * s / k))
            val hi = Math.round(maxOf(2f, 0.08f * (s + 1) / k))
            if (n == lo || n == hi) return n
        }
        return maxOf(2, Math.round(d * 0.08f / k))
    }
}
