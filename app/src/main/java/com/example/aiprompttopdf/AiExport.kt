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
 *  - ONE plain text file with every prompt, response and all code, copied exactly;
 *  - every picture as its own image file, with a yellow strip at the top that says
 *    which conversation it belongs to ("Conversation 2 - Image 1").
 * Everything is saved into Download/AI_Chat_<date_time>/ .
 *
 * A very tall picture (a long scrolling screenshot) is saved in several parts,
 * because an AI shrinks a tall picture until its text cannot be read. It is cut
 * only in an empty gap between two lines of text; if no gap is found, a small
 * strip is repeated in the next part, so nothing is lost.
 */
class AiExport(private val ctx: Context, private val store: Store) {

    /** name = file name in the app's storage, number = its number inside its conversation. */
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

    fun make(items: List<Item>, totalInApp: Int, stamp: String, generated: String, progress: (String) -> Unit): Result {
        val folder = "AI_Chat_" + stamp
        val pictureFiles = ArrayList<SavedFile>()
        val turns = ArrayList<HandoffTurn>()
        var pictures = 0
        var failed = 0
        var leftOut = 0
        var wanted = 0
        for (item in items) for (p in item.pictures) if (p.include) wanted++
        var done = 0

        for (item in items) {
            val lines = ArrayList<String>()
            for (p in item.pictures) {
                val label = "Conversation " + item.number + ", Image " + p.number
                if (!p.include) {
                    leftOut++
                    lines.add("[IMAGE: $label | this picture was not attached]")
                    continue
                }
                done++
                progress("ছবি তৈরি হচ্ছে… " + HtmlCodec.bn(done) + " / " + HtmlCodec.bn(wanted))
                val saved = exportPicture(store.file(p.name), item.number, p.number, folder)
                if (saved.isEmpty()) {
                    failed++
                    lines.add("[IMAGE: $label | this picture could not be attached]")
                } else {
                    pictures++
                    if (saved.size == 1) {
                        lines.add("[IMAGE: $label | attached separately as file \"" + saved[0].first + "\"]")
                    } else {
                        val sb = StringBuilder()
                        for (s in saved) {
                            if (sb.isNotEmpty()) sb.append(", ")
                            sb.append('"').append(s.first).append('"')
                        }
                        lines.add("[IMAGE: $label | a very tall picture, attached separately in " + saved.size + " parts: " + sb + "]")
                    }
                    for (s in saved) pictureFiles.add(s.second)
                }
            }
            turns.add(HandoffTurn(item.number, item.prompt, item.response, lines))
        }

        progress("লেখার ফাইল তৈরি হচ্ছে…")
        var textFile: SavedFile? = null
        var ok = false
        var problem = ""
        var lineCount = 0
        val pending = SaveUtil.open(ctx, "AI_Chat_" + stamp + ".txt", "text/plain", folder)
        if (pending == null) {
            problem = "Download ফোল্ডারে লেখার ফাইল তৈরি করা গেল না"
        } else {
            var written = false
            try {
                val w = BufferedWriter(OutputStreamWriter(pending.out, Charsets.UTF_8), 65536)
                HandoffText.write(w, turns, generated, totalInApp, pictureFiles.size)
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
                    val c = HandoffText.verify(back, turns)
                    ok = c.ok
                    lineCount = c.lines
                    if (!c.ok) problem = c.problem
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

    /** Saves one picture (in one or more parts). An empty list means it failed. */
    private fun exportPicture(src: File, conv: Int, img: Int, folder: String): List<Pair<String, SavedFile>> {
        val result = ArrayList<Pair<String, SavedFile>>()
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
            val ranges = computeRanges(s, w, h)
            val n = ranges.size
            for (i in 0 until n) {
                val name = if (n == 1) {
                    String.format(Locale.US, "Conv%02d_Image%d.%s", conv, img, ext)
                } else {
                    String.format(Locale.US, "Conv%02d_Image%d_part%dof%d.%s", conv, img, i + 1, n, ext)
                }
                val saved = savePart(s, ranges[i][0], ranges[i][1], sample, conv, img, i + 1, n, name, mime, lossy, folder)
                    ?: return result
                result.add(Pair(name, saved))
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
                for (r in result) SaveUtil.delete(ctx, r.second)
                result.clear()
            }
        }
    }

    /** Where a picture is cut: a list of [top, bottom) rows. A normal picture gives one range. */
    private fun computeRanges(s: PictureSource, w: Int, h: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        if (h <= w * MAX_RATIO) {
            out.add(intArrayOf(0, h))
            return out
        }
        val rows = partRows(w)
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
                top = maxCut - minOf(120, rows / 5)
            }
        }
        out.add(intArrayOf(top, h))
        return out
    }

    private fun savePart(
        s: PictureSource, top: Int, bottom: Int, sample: Int, conv: Int, img: Int, part: Int, parts: Int,
        name: String, mime: String, lossy: Boolean, folder: String
    ): SavedFile? {
        val bmp = s.decode(top, bottom, sample) ?: return null
        var out: Bitmap? = null
        try {
            val bw = bmp.width
            val bh = bmp.height
            val outW = maxOf(bw, MIN_W)
            val line1 = "Conversation " + conv + " - Image " + img + (if (parts > 1) " (part $part of $parts)" else "")
            val line2 = "কথোপকথন " + HtmlCodec.bn(conv) + "-এর ছবি " + HtmlCodec.bn(img) +
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
            val stripH = (ts * 2.9f).toInt()
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
            // The picture itself goes under the strip, untouched.
            c.drawBitmap(bmp, (outW - bw) / 2f, stripH.toFloat(), null)

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
            return SaveUtil.finish(ctx, pending, ok)
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
