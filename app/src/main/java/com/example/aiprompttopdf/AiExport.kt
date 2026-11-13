package com.example.aiprompttopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Makes the files that are handed to the next AI:
 *  - ONE plain text file with every message, reply and all code, copied exactly;
 *  - every picture as its own image file, with a yellow strip at the top that says
 *    which message it belongs to ("Picture 1 of 2 for MY MESSAGE 3") and shows the
 *    first words of that message.
 * Everything is saved into Download/AI_Chat_<name>_<date_time>/ .
 * Every picture file carries the date and time of its download in its name, so
 * pictures of two different downloads can never be mixed up.
 *
 * A very tall picture (a long scrolling screenshot) is saved in several parts,
 * because an AI shrinks a tall picture until its text cannot be read. It is cut
 * only in an empty gap between two lines of text; if no gap is found, a small
 * strip is repeated in the next part, so nothing is lost.
 *
 * For every saved picture file the text file notes how tall the yellow strip is
 * and where the picture sits, so the app can later take the strip off again and
 * put the parts of a tall picture back together (see AiImport).
 */
class AiExport(private val ctx: Context, private val store: Store) {

    /** name = file name in the app's storage, number = its number inside its message. */
    class Picture(val name: String, val number: Int) {
        var include = true
        var parts = 1
    }

    class Item(val number: Int, val prompt: String, val response: String, val pictures: List<Picture>)

    class Result(
        val files: List<SavedFile>,
        val folder: String,
        val textFile: SavedFile?,
        val conversations: Int,
        val lines: Int,
        val textOk: Boolean,
        val problem: String,
        val pictures: Int,
        val pictureFiles: Int,
        val failedPictures: Int,
        val leftOut: Int
    )

    private class SavedPart(val part: PicturePart, val file: SavedFile)

    /** The first words of a message, on one line. more = the message goes on after them. */
    private class Words(val text: String, val more: Boolean)

    companion object {
        const val MAX_RATIO = 2.6f   // taller than this (height / width): saved in parts
        const val PART_RATIO = 2.2f  // height / width of one part
        const val MAX_W = 1600
        const val MIN_W = 480
        private val STRIP = 0xFFFFEB3B.toInt()

        private fun partRows(w: Int): Int = maxOf(200, (w * PART_RATIO).toInt())

        /** About how many files a picture of this size becomes. */
        fun estimateParts(w: Int, h: Int): Int {
            if (w <= 0 || h <= 0) return 1
            if (h <= w * MAX_RATIO) return 1
            val rows = partRows(w)
            var n = 1
            var rest = h
            while (rest > rows * 5 / 4) {
                rest -= rows
                n++
            }
            return n
        }

        /**
         * The part of a name that can safely be used in a folder name: letters and
         * digits of any language, with "_" in place of spaces. At most 40 characters.
         */
        fun safeName(title: String): String {
            val sb = StringBuilder()
            for (ch in title) {
                if (sb.length >= 40) break
                val type = Character.getType(ch)
                val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
                if (Character.isLetterOrDigit(ch) || (mark && sb.isNotEmpty())) {
                    sb.append(ch)
                } else if (ch == '-') {
                    if (sb.isNotEmpty()) sb.append('-')
                } else if (ch == ' ' || ch == '_' || ch == '\t' || ch == '\n' || ch == '\r') {
                    if (sb.isNotEmpty() && sb[sb.length - 1] != '_') sb.append('_')
                }
            }
            while (sb.isNotEmpty() && (sb[sb.length - 1] == '_' || sb[sb.length - 1] == '-')) sb.setLength(sb.length - 1)
            return sb.toString()
        }

        /** The folder of one download: AI_Chat_<name>_<date_time>, or AI_Chat_<date_time> without a name. */
        fun folderName(title: String, stamp: String): String {
            val safe = safeName(title)
            return if (safe.isEmpty()) "AI_Chat_" + stamp else "AI_Chat_" + safe + "_" + stamp
        }
    }

    /** Fills in how many files each picture will become (reads only the picture sizes). */
    fun plan(items: List<Item>) {
        for (item in items) {
            for (p in item.pictures) {
                try {
                    val o = BitmapFactory.Options()
                    o.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(store.file(p.name).absolutePath, o)
                    p.parts = estimateParts(o.outWidth, o.outHeight)
                } catch (e: Exception) {
                    p.parts = 1
                }
            }
        }
    }

    fun make(items: List<Item>, totalInApp: Int, title: String, stamp: String, generated: String, progress: (String) -> Unit): Result {
        val folder = folderName(title, stamp)
        val pictureFiles = ArrayList<SavedFile>()
        val turns = ArrayList<HandoffTurn>()
        var pictures = 0
        var failed = 0
        var leftOut = 0
        var wanted = 0
        for (item in items) for (p in item.pictures) if (p.include) wanted++
        var done = 0

        for (item in items) {
            val pics = ArrayList<HandoffPicture>()
            val words = firstWords(item.prompt)
            for (p in item.pictures) {
                if (!p.include) {
                    leftOut++
                    pics.add(HandoffPicture(p.number, HandoffPicture.LEFT_OUT, ArrayList()))
                    continue
                }
                done++
                progress("ছবি তৈরি হচ্ছে… " + HtmlCodec.bn(done) + " / " + HtmlCodec.bn(wanted))
                val saved = exportPicture(store.file(p.name), item.number, p.number, item.pictures.size, words, folder, stamp)
                if (saved.isEmpty()) {
                    failed++
                    pics.add(HandoffPicture(p.number, HandoffPicture.FAILED, ArrayList()))
                } else {
                    pictures++
                    val parts = ArrayList<PicturePart>()
                    for (s in saved) {
                        parts.add(s.part)
                        pictureFiles.add(s.file)
                    }
                    pics.add(HandoffPicture(p.number, HandoffPicture.ATTACHED, parts))
                }
            }
            turns.add(HandoffTurn(item.number, item.prompt, item.response, pics))
        }

        progress("লেখার ফাইল তৈরি হচ্ছে…")
        var textFile: SavedFile? = null
        var ok = false
        var problem = ""
        var lineCount = 0
        // The file names stay plain (English letters and digits), so every AI app takes them.
        val pending = SaveUtil.open(ctx, "AI_Chat_" + stamp + ".txt", "text/plain", folder)
        if (pending == null) {
            problem = "Download ফোল্ডারে লেখার ফাইল তৈরি করা গেল না"
        } else {
            var written = false
            try {
                val w = BufferedWriter(OutputStreamWriter(pending.out, Charsets.UTF_8), 65536)
                HandoffText.write(w, turns, title, generated, totalInApp)
                w.flush()
                written = true
            } catch (e: Exception) {
                problem = e.message ?: e.toString()
            } catch (e: OutOfMemoryError) {
                problem = "ফোনের মেমরি কম পড়েছে"
            }
            textFile = SaveUtil.finish(ctx, pending, written)
            val tf = textFile
            if (tf == null) {
                if (problem.isEmpty()) problem = "লেখার ফাইলটা সেভ করা গেল না"
            } else {
                // Read the saved file again and compare it with the original text.
                progress("লেখার ফাইল মিলিয়ে দেখা হচ্ছে…")
                val back = SaveUtil.readBack(ctx, tf)
                if (back == null) {
                    problem = "সেভ হওয়া ফাইলটা আবার পড়া গেল না"
                } else {
                    try {
                        val c = HandoffText.verify(back, turns, title)
                        ok = c.ok
                        lineCount = c.lines
                        if (!c.ok) problem = c.problem
                    } catch (e: OutOfMemoryError) {
                        problem = "ফাইলটা মিলিয়ে দেখার সময় ফোনের মেমরি কম পড়েছে"
                    }
                }
            }
        }

        val files = ArrayList<SavedFile>()
        val tf = textFile
        if (tf != null) files.add(tf)
        files.addAll(pictureFiles)
        return Result(files, folder, textFile, items.size, lineCount, ok, problem, pictures, pictureFiles.size, failed, leftOut)
    }

    // ---------------------------------------------------------------------

    private fun firstWords(prompt: String): Words {
        val sb = StringBuilder()
        var space = false
        var more = false
        var i = 0
        while (i < prompt.length) {
            val ch = prompt[i]
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                space = sb.isNotEmpty()
            } else {
                if (sb.length >= 80) {
                    more = true
                    break
                }
                if (space) sb.append(' ')
                space = false
                sb.append(ch)
            }
            i++
        }
        return Words(dropBrokenEnd(sb.toString()), more)
    }

    /** Never leave half of an emoji at the end of a text. */
    private fun dropBrokenEnd(s: String): String {
        if (s.isNotEmpty() && Character.isHighSurrogate(s[s.length - 1])) return s.substring(0, s.length - 1)
        return s
    }

    /** The third line of the strip: the first words of the message, as many as fit. */
    private fun wordsLine(words: Words, msg: Int, paint: Paint, maxW: Float): String {
        if (words.text.isEmpty()) return "(MY MESSAGE $msg has no text, only pictures)"
        val head = "MY MESSAGE $msg begins: \""
        var t = words.text
        var more = words.more
        var guard = 0
        while (guard < 200) {
            guard++
            val line = head + t + (if (more) "..." else "") + "\""
            if (t.isEmpty() || paint.measureText(line) <= maxW) return line
            more = true
            val sp = t.lastIndexOf(' ')
            t = if (sp > 0) t.substring(0, sp) else dropBrokenEnd(t.substring(0, t.length - 1))
        }
        return head + "...\""
    }

    /** Saves one picture (in one or more parts). An empty list means it failed. */
    private fun exportPicture(src: File, msg: Int, img: Int, total: Int, words: Words, folder: String, stamp: String): List<SavedPart> {
        val result = ArrayList<SavedPart>()
        var source: PictureSource? = null
        var complete = false
        try {
            if (!src.isFile) return result
            val head = HtmlCodec.readHead(src) ?: return result
            val type = HtmlCodec.sniff(head, head.size) ?: return result
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(src.absolutePath, o)
            val w = o.outWidth
            val h = o.outHeight
            if (w <= 0 || h <= 0) return result
            // A photo-like picture is saved as JPEG; a lossless picture stays lossless (PNG).
            val lossy = type == "jpeg" || (type == "webp" && !HtmlCodec.isLosslessWebp(head, head.size))
            val ext = if (lossy) "jpg" else "png"
            val mime = if (lossy) "image/jpeg" else "image/png"
            var sample = 1
            while (w / (sample * 2) >= MAX_W) sample *= 2

            val s = PictureSource(src, w, h, type)
            source = s
            val ranges = computeRanges(s, w, h, sample)
            val n = ranges.size
            for (i in 0 until n) {
                val name = if (n == 1) {
                    String.format(Locale.US, "Message%02d_Picture%d_%s.%s", msg, img, stamp, ext)
                } else {
                    String.format(Locale.US, "Message%02d_Picture%d_part%dof%d_%s.%s", msg, img, i + 1, n, stamp, ext)
                }
                // Rows at the top of this part that are also at the end of the part before it.
                val overlap = if (i == 0) 0 else s.overlapRows(ranges[i - 1][1], ranges[i][0], sample)
                val saved = savePart(s, ranges[i][0], ranges[i][1], sample, msg, img, total, i + 1, n, overlap, words, name, mime, lossy, folder)
                    ?: return result
                result.add(saved)
            }
            complete = true
            return result
        } catch (e: Exception) {
            return result
        } catch (e: OutOfMemoryError) {
            return result
        } finally {
            source?.close()
            if (!complete) {
                // Never leave half of a picture behind.
                for (r in result) SaveUtil.delete(ctx, r.file)
                result.clear()
            }
        }
    }

    /** Where a picture is cut: a list of [top, bottom) rows. A normal picture gives one range. */
    private fun computeRanges(s: PictureSource, w: Int, h: Int, sample: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        if (h <= w * MAX_RATIO) {
            out.add(intArrayOf(0, h))
            return out
        }
        // Whole steps of `sample`, so the repeated strip is a whole number of saved rows.
        val rows = maxOf(sample, partRows(w) / sample * sample)
        var top = 0
        var guard = 0
        while (h - top > rows * 5 / 4 && guard < 300) {
            guard++
            val maxCut = top + rows
            val search = minOf(rows / 4, 400)
            val cut = s.findCut(maxCut, search)
            if (cut > top + rows / 2) {
                out.add(intArrayOf(top, cut))
                top = cut
            } else {
                // No empty gap here: repeat a small strip in the next part.
                out.add(intArrayOf(top, maxCut))
                top = maxCut - minOf(120, rows / 5) / sample * sample
            }
        }
        out.add(intArrayOf(top, h))
        return out
    }

    private fun savePart(
        s: PictureSource, top: Int, bottom: Int, sample: Int, msg: Int, img: Int, total: Int, part: Int, parts: Int,
        overlap: Int, words: Words, name: String, mime: String, lossy: Boolean, folder: String
    ): SavedPart? {
        val bmp = s.decode(top, bottom, sample) ?: return null
        var out: Bitmap? = null
        try {
            val bw = bmp.width
            val bh = bmp.height
            val outW = maxOf(bw, MIN_W)
            val line1 = "Picture $img of $total for MY MESSAGE $msg" + (if (parts > 1) " (part $part of $parts)" else "")
            val line2 = "আমার মেসেজ " + HtmlCodec.bn(msg) + "-এর ছবি " + HtmlCodec.bn(img) + " (মোট " + HtmlCodec.bn(total) + "টা)" +
                (if (parts > 1) " (অংশ " + HtmlCodec.bn(part) + " / " + HtmlCodec.bn(parts) + ")" else "")
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.BLACK
            var ts = outW * 0.040f
            paint.textSize = ts
            while (ts > 9f && maxOf(paint.measureText(line1), paint.measureText(line2)) > outW * 0.94f) {
                ts *= 0.93f
                paint.textSize = ts
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG)
            small.typeface = Typeface.DEFAULT
            small.color = Color.BLACK
            small.textSize = ts * 0.82f
            val line3 = wordsLine(words, msg, small, outW * 0.94f)

            val stripH = (ts * 4.0f).toInt()
            val o = Bitmap.createBitmap(outW, stripH + bh, Bitmap.Config.ARGB_8888)
            out = o
            val c = Canvas(o)
            c.drawColor(Color.WHITE)
            val fill = Paint()
            fill.color = STRIP
            c.drawRect(0f, 0f, outW.toFloat(), stripH.toFloat(), fill)
            fill.color = Color.BLACK
            c.drawRect(0f, stripH - maxOf(2f, ts * 0.08f), outW.toFloat(), stripH.toFloat(), fill)
            val pad = outW * 0.03f
            c.drawText(line1, pad, ts * 1.15f, paint)
            c.drawText(line2, pad, ts * 2.35f, paint)
            c.drawText(line3, pad, ts * 3.45f, small)
            // The picture itself goes under the strip, untouched.
            val left = (outW - bw) / 2
            c.drawBitmap(bmp, left.toFloat(), stripH.toFloat(), null)

            val pending = SaveUtil.open(ctx, name, mime, folder) ?: return null
            var ok = false
            try {
                val bos = BufferedOutputStream(pending.out, 65536)
                ok = if (lossy) {
                    o.compress(Bitmap.CompressFormat.JPEG, 92, bos)
                } else {
                    o.compress(Bitmap.CompressFormat.PNG, 100, bos)
                }
                bos.flush()
            } catch (e: Exception) {
                ok = false
            }
            val saved = SaveUtil.finish(ctx, pending, ok) ?: return null
            val ov = if (overlap < 0) 0 else minOf(overlap, bh - 1)
            return SavedPart(PicturePart(name, stripH, left, bw, bh, ov), saved)
        } finally {
            out?.recycle()
            if (!s.isShared(bmp)) bmp.recycle()
        }
    }

    /** Reads parts of a picture without loading the whole picture into memory. */
    private class PictureSource(private val file: File, private val w: Int, private val h: Int, type: String) {
        private var region: BitmapRegionDecoder? = null
        private var full: Bitmap? = null

        init {
            if (type != "gif") {
                region = try {
                    BitmapRegionDecoder.newInstance(file.absolutePath, false)
                } catch (e: Exception) {
                    null
                }
            }
            if (region == null) {
                var sample = 1
                while (w / sample > 2048) sample *= 2
                val o = BitmapFactory.Options()
                o.inSampleSize = sample
                full = try {
                    BitmapFactory.decodeFile(file.absolutePath, o)
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
        }

        /** Rows [top, bottom) of the picture, made `sample` times smaller. */
        fun decode(top: Int, bottom: Int, sample: Int): Bitmap? {
            val r = region
            if (r != null) {
                val o = BitmapFactory.Options()
                o.inSampleSize = sample
                o.inPreferredConfig = Bitmap.Config.ARGB_8888
                return try {
                    r.decodeRegion(Rect(0, top, w, bottom), o)
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            val f = full ?: return null
            val k = f.height.toFloat() / h
            val t = (top * k).toInt().coerceIn(0, f.height - 1)
            val b = Math.ceil((bottom * k).toDouble()).toInt().coerceIn(t + 1, f.height)
            return try {
                Bitmap.createBitmap(f, 0, t, f.width, b - t)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        /** How many saved rows at the top of a part repeat the end of the part before it. */
        fun overlapRows(prevBottom: Int, top: Int, sample: Int): Int {
            if (prevBottom <= top) return 0
            if (region != null) return (prevBottom - top) / sample
            val f = full ?: return 0
            val k = f.height.toFloat() / h
            val t = (top * k).toInt().coerceIn(0, f.height - 1)
            val pb = Math.ceil((prevBottom * k).toDouble()).toInt().coerceIn(1, f.height)
            return maxOf(0, pb - t)
        }

        fun isShared(b: Bitmap): Boolean = b === full

        /** A safe row to cut at near maxCut (looking up to `search` rows above it), or -1. */
        fun findCut(maxCut: Int, search: Int): Int {
            if (search < 8) return -1
            val from = maxCut - search
            val b = decode(from, maxCut, 1) ?: return -1
            try {
                val bw = b.width
                val bh = b.height
                if (bw <= 0 || bh <= 0) return -1
                val px = IntArray(bw * bh)
                b.getPixels(px, 0, bw, 0, 0, bw, bh)
                val r = ImageCut.findCut(px, bw, bh)
                if (r < 0) return -1
                return from + (r.toLong() * search / bh).toInt()
            } catch (e: OutOfMemoryError) {
                return -1
            } finally {
                if (b !== full) b.recycle()
            }
        }

        fun close() {
            region?.recycle()
            region = null
            full?.recycle()
            full = null
        }
    }
}
