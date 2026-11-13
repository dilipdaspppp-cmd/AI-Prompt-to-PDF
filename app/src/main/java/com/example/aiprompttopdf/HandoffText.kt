package com.example.aiprompttopdf

import java.io.Writer

/**
 * One saved image file of a picture. A normal picture has one; a very tall picture
 * (a long scrolling screenshot) is saved in several parts.
 *  file    = file name in the Download folder
 *  strip   = height of the yellow label strip at the top, in pixels
 *  left, width, height = where the picture itself sits under the strip
 *  overlap = rows at the top of this part that repeat the end of the part before it
 * A value of -1 means "not known" (files made by an older version of the app).
 */
class PicturePart(val file: String, val strip: Int, val left: Int, val width: Int, val height: Int, val overlap: Int)

/** One picture of a message. number = its number inside that message. */
class HandoffPicture(val number: Int, val state: Int, val parts: List<PicturePart>) {
    companion object {
        const val ATTACHED = 0
        const val LEFT_OUT = 1  // the user chose not to send it
        const val FAILED = 2    // the picture file could not be made
    }
}

/** One message of mine with the AI's reply. number = the conversation number shown in the app. */
class HandoffTurn(val number: Int, val prompt: String, val response: String, val pictures: List<HandoffPicture>)

/**
 * The plain text file that is handed to the next AI, and reading such a file back.
 *
 * The message and the reply are written exactly as they are: nothing is trimmed,
 * escaped or re-wrapped, so every line of code and every space stays. After saving,
 * verify() reads the saved file again, compares it with the original text byte by
 * byte, and also reads the whole file back the way "bring back" does.
 *
 * The file begins with an invisible mark (the UTF-8 byte order mark). A plain text
 * file does not say which alphabet rules it uses, so some apps (Chrome, for one)
 * guess wrongly and show Bengali as rows of strange symbols. The mark tells every
 * app to read the file as UTF-8. It sits before the first line, outside all text.
 *
 * At the end of the file there is a small "APP DATA" block. It holds the exact
 * length of every message and reply, so the app can load the file back without
 * guessing, even when the text itself contains lines that look like our markers.
 */
object HandoffText {

    const val UTF8_MARK = "\uFEFF"
    const val RULE = "======================================================================"
    const val END_OF_FILE = "END OF FILE"
    const val APP_DATA = "APP DATA (made by the app, only used to load this file back into the app; an AI can ignore this block)"

    fun messageStart(n: Int): String = "---------- MY MESSAGE $n : START ----------"
    fun messageEnd(n: Int): String = "---------- MY MESSAGE $n : END ----------"
    fun replyStart(n: Int): String = "---------- AI REPLY $n : START ----------"
    fun replyEnd(n: Int): String = "---------- AI REPLY $n : END ----------"

    /** How many lines a text has (a last line without a line break still counts). */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        var n = 0
        for (ch in text) if (ch == '\n') n++
        return if (text.endsWith("\n")) n else n + 1
    }

    fun totalLines(turns: List<HandoffTurn>): Int {
        var n = 0
        for (t in turns) n += lineCount(t.prompt) + lineCount(t.response)
        return n
    }

    /** A title is one short line of plain text. */
    fun cleanTitle(title: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < title.length && sb.length < 60) {
            val ch = title[i]
            if (Character.isHighSurrogate(ch)) {
                // An emoji is two units: keep both or neither.
                if (i + 1 < title.length && Character.isLowSurrogate(title[i + 1])) {
                    if (sb.length + 2 <= 60) sb.append(ch).append(title[i + 1])
                    i += 2
                    continue
                }
            } else if (Character.isLowSurrogate(ch)) {
                // a broken half: dropped
            } else if (ch < ' ' || ch == '\u007F' || ch == '\u2028' || ch == '\u2029' || ch == '\uFEFF') {
                sb.append(' ')
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString().trim()
    }

    private fun pictureWords(p: HandoffPicture, total: Int, n: Int): String {
        return "PICTURE " + p.number + " of " + total + " for MY MESSAGE " + n
    }

    fun write(w: Writer, turns: List<HandoffTurn>, title: String, generated: String, totalInApp: Int) {
        val name = cleanTitle(title)
        var pictureFiles = 0
        for (t in turns) for (p in t.pictures) if (p.state == HandoffPicture.ATTACHED) pictureFiles += p.parts.size

        w.write(UTF8_MARK)
        w.write("READ THIS FIRST (a note for the AI)\n")
        w.write("This file is an exact copy of ONE conversation I had with another AI assistant.\n")
        w.write("I could not continue there because its usage limit ran out, so I am continuing with you.\n")
        w.write("\n")
        w.write("How to read this file:\n")
        w.write("- It is a single conversation, in order from top to bottom.\n")
        w.write("- \"MY MESSAGE 1\" is what I wrote, and \"AI REPLY 1\" is what that AI answered to it.\n")
        w.write("  Then comes \"MY MESSAGE 2\" with \"AI REPLY 2\", and so on. The numbers only show the order.\n")
        w.write("- Everything between a START line and its END line is copied exactly, character for\n")
        w.write("  character, including all code, blank lines and indentation.\n")
        w.write("- Pictures are not inside this file. Where I had attached a picture to a message, a line\n")
        w.write("  beginning with [PICTURE ...] names its image file. I attach those image files to this\n")
        w.write("  chat separately. A yellow strip at the top of every picture says which of my messages\n")
        w.write("  it belongs to.\n")
        if (totalInApp > turns.size) {
            w.write("- I left out some messages on purpose, so the numbers can jump.\n")
        }
        w.write("\n")
        w.write("Please read everything first. Then answer my new message, continuing from where\n")
        w.write("this conversation stopped.\n")
        w.write("\n")
        w.write("(বাংলায়: এটা আগের একটা AI-এর সাথে আমার একটাই কথোপকথনের হুবহু কপি, পরপর সাজানো।\n")
        w.write("পুরোটা পড়ে নাও, তারপর আমার নতুন মেসেজ থেকে কাজ চালিয়ে যাও।)\n")
        w.write("\n")
        if (name.isNotEmpty()) w.write("Title: " + name + "\n")
        w.write("Created: " + generated + "\n")
        val nums = StringBuilder()
        for (t in turns) {
            if (nums.isNotEmpty()) nums.append(", ")
            nums.append(t.number)
        }
        w.write("Messages in this file: " + turns.size + " (MY MESSAGE " + nums + ")\n")
        w.write("Picture files attached separately: " + pictureFiles + "\n")
        w.write("\n")

        // Which image file belongs to which message, all in one place.
        if (pictureFiles == 0) {
            w.write("PICTURE LIST: there are no pictures in this conversation.\n")
        } else {
            w.write("PICTURE LIST (which image file belongs to which message)\n")
            for (t in turns) {
                for (p in t.pictures) {
                    if (p.state != HandoffPicture.ATTACHED) continue
                    for ((i, part) in p.parts.withIndex()) {
                        w.write("- " + part.file + " : picture " + p.number + " of " + t.pictures.size + " for MY MESSAGE " + t.number)
                        if (p.parts.size > 1) w.write(" (part " + (i + 1) + " of " + p.parts.size + " of a very tall picture)")
                        w.write("\n")
                    }
                }
            }
        }

        for (t in turns) {
            val n = t.number
            w.write("\n")
            w.write(RULE)
            w.write("\n")
            w.write("MY MESSAGE $n  and  AI REPLY $n\n")
            w.write(RULE)
            w.write("\n\n")

            if (t.pictures.isNotEmpty()) {
                w.write("Pictures attached to MY MESSAGE $n: " + t.pictures.size + "\n")
                for (p in t.pictures) {
                    w.write("[" + pictureWords(p, t.pictures.size, n) + " | ")
                    if (p.state == HandoffPicture.LEFT_OUT) {
                        w.write("I did not attach this picture")
                    } else if (p.state == HandoffPicture.FAILED) {
                        w.write("this picture could not be attached")
                    } else if (p.parts.size == 1) {
                        w.write("image file \"" + p.parts[0].file + "\"")
                    } else {
                        w.write("a very tall picture, sent as " + p.parts.size + " image files: ")
                        for ((i, part) in p.parts.withIndex()) {
                            if (i > 0) w.write(", ")
                            w.write("\"" + part.file + "\"")
                        }
                    }
                    w.write("]\n")
                }
                w.write("\n")
            } else {
                w.write("Pictures attached to MY MESSAGE $n: none\n\n")
            }

            if (t.prompt.isNotEmpty()) {
                w.write(messageStart(n))
                w.write("\n")
                w.write(t.prompt)
                if (!t.prompt.endsWith("\n")) w.write("\n")
                w.write(messageEnd(n))
                w.write("\n\n")
            } else {
                w.write("(MY MESSAGE $n had no text.)\n\n")
            }

            if (t.response.isNotEmpty()) {
                w.write(replyStart(n))
                w.write("\n")
                w.write(t.response)
                if (!t.response.endsWith("\n")) w.write("\n")
                w.write(replyEnd(n))
                w.write("\n")
            } else {
                w.write("(No AI reply was saved for MY MESSAGE $n.)\n")
            }
            w.flush()
        }

        // The block the app itself reads when this file is brought back.
        w.write("\n")
        w.write(RULE)
        w.write("\n")
        w.write(APP_DATA)
        w.write("\n")
        w.write("format=2\n")
        w.write("title=" + name + "\n")
        for (t in turns) {
            w.write("message=" + t.number + " my_bytes=" + bytes(t.prompt).size + " reply_bytes=" + bytes(t.response).size + "\n")
            for (p in t.pictures) {
                if (p.state != HandoffPicture.ATTACHED) {
                    w.write("picture=" + p.number + " message=" + t.number + " state=" + p.state + "\n")
                    continue
                }
                for ((i, part) in p.parts.withIndex()) {
                    w.write("picture=" + p.number + " message=" + t.number + " state=0 part=" + (i + 1) + " parts=" + p.parts.size +
                        " strip=" + part.strip + " left=" + part.left + " width=" + part.width + " height=" + part.height +
                        " overlap=" + part.overlap + " file=" + part.file + "\n")
                }
            }
        }
        w.write(RULE)
        w.write("\n")
        w.write(END_OF_FILE)
        w.write("\n")
        w.flush()
    }

    class Check(val ok: Boolean, val lines: Int, val problem: String)

    /**
     * Reads the saved file and checks that every message and every reply is in it
     * completely and unchanged, in the right place, that the file is not cut short,
     * and that reading the file back gives exactly the same conversation again.
     */
    fun verify(file: ByteArray, turns: List<HandoffTurn>, title: String): Check {
        // The invisible mark must be the very first thing in the file.
        if (file.size < 3 || file[0] != 0xEF.toByte() || file[1] != 0xBB.toByte() || file[2] != 0xBF.toByte()) {
            return Check(false, 0, "The mark at the start of the file is missing")
        }
        var pos = 3
        for (t in turns) {
            val n = t.number
            if (t.prompt.isNotEmpty()) {
                val p = checkBlock(file, pos, messageStart(n), t.prompt, messageEnd(n))
                if (p < 0) return Check(false, 0, "MY MESSAGE $n is not in the file exactly")
                pos = p
            }
            if (t.response.isNotEmpty()) {
                val p = checkBlock(file, pos, replyStart(n), t.response, replyEnd(n))
                if (p < 0) return Check(false, 0, "AI REPLY $n is not in the file exactly")
                pos = p
            }
        }
        val tail = bytes(RULE + "\n" + END_OF_FILE + "\n")
        if (file.size < tail.size || !regionEquals(file, file.size - tail.size, tail) || file.size - tail.size < pos) {
            return Check(false, 0, "The file is cut short at the end")
        }

        // Read the file back the way "bring back" does, and compare everything.
        val back = parse(file) ?: return Check(false, 0, "The file cannot be read back")
        if (back.format != 2 || back.title != cleanTitle(title) || back.turns.size != turns.size) {
            return Check(false, 0, "Reading the file back gives a different conversation")
        }
        for (i in turns.indices) {
            val a = turns[i]
            val b = back.turns[i]
            if (a.number != b.number || !same(a.prompt, b.prompt) || !same(a.response, b.response) || a.pictures.size != b.pictures.size) {
                return Check(false, 0, "Reading back MY MESSAGE " + a.number + " gives different text")
            }
            for (k in a.pictures.indices) {
                val pa = a.pictures[k]
                val pb = b.pictures[k]
                if (pa.number != pb.number || pa.state != pb.state || pa.parts.size != pb.parts.size) {
                    return Check(false, 0, "Reading back the pictures of MY MESSAGE " + a.number + " gives a different list")
                }
                for (j in pa.parts.indices) {
                    val x = pa.parts[j]
                    val y = pb.parts[j]
                    if (x.file != y.file || x.strip != y.strip || x.left != y.left || x.width != y.width ||
                        x.height != y.height || x.overlap != y.overlap) {
                        return Check(false, 0, "Reading back picture file " + x.file + " gives different details")
                    }
                }
            }
        }
        return Check(true, totalLines(turns), "")
    }

    /** Checks one START..text..END block at or after `from`. Returns the position after it, or -1. */
    private fun checkBlock(file: ByteArray, from: Int, startLine: String, text: String, endLine: String): Int {
        // The START line must be a whole line of its own.
        val start = bytes("\n" + startLine + "\n")
        val i = HtmlCodec.indexOf(file, start, maxOf(0, from - 1), file.size)
        if (i < 0) return -1
        var p = i + start.size
        val body = bytes(text)
        if (!regionEquals(file, p, body)) return -1
        p += body.size
        if (!text.endsWith("\n")) {
            if (p >= file.size || file[p] != '\n'.code.toByte()) return -1
            p++
        }
        val end = bytes(endLine + "\n")
        if (!regionEquals(file, p, end)) return -1
        return p + end.size
    }

    // ---------------------------------------------------------------------
    // Reading a file back
    // ---------------------------------------------------------------------

    /** format 2 = made by this version (exact). format 1 = made by an older version (close, but see parseOld). */
    class Parsed(val format: Int, val title: String, val turns: List<HandoffTurn>)

    /** Reads a text file made by this app. Returns null when it is not such a file or is damaged. */
    fun parse(file: ByteArray): Parsed? {
        return try {
            parseNew(file) ?: parseOld(file)
        } catch (e: Exception) {
            null
        }
    }

    private class Entry(val number: Int, val myBytes: Int, val replyBytes: Int) {
        val pictures = ArrayList<IntArray>()          // [picture number, state]
        val parts = HashMap<Int, ArrayList<PicturePart>>()
    }

    private fun parseNew(file: ByteArray): Parsed? {
        val head = bytes("\n" + RULE + "\n" + "APP DATA (")
        val tail = bytes(RULE + "\n" + END_OF_FILE + "\n")
        if (file.size < tail.size || !regionEquals(file, file.size - tail.size, tail)) return null
        // The real block is the LAST one in the file (text before it may contain look-alikes).
        var at = -1
        var from = 0
        while (true) {
            val i = HtmlCodec.indexOf(file, head, from, file.size)
            if (i < 0) break
            at = i
            from = i + 1
        }
        if (at < 0) return null
        val blockStart = at + 1 + bytes(RULE + "\n").size
        val blockEnd = file.size - tail.size
        if (blockEnd <= blockStart) return null
        val lines = String(file, blockStart, blockEnd - blockStart, Charsets.UTF_8).split("\n")
        if (lines.size < 3 || !lines[0].startsWith("APP DATA (") || lines[1] != "format=2" || !lines[2].startsWith("title=")) return null
        val title = lines[2].substring("title=".length)

        val entries = ArrayList<Entry>()
        for (k in 3 until lines.size) {
            val line = lines[k]
            if (line.isEmpty()) continue
            if (line.startsWith("message=")) {
                val m = fields(line)
                entries.add(Entry(num(m, "message"), num(m, "my_bytes"), num(m, "reply_bytes")))
            } else if (line.startsWith("picture=")) {
                if (entries.isEmpty()) return null
                val e = entries[entries.size - 1]
                // The file name comes last and is taken whole, so any name works.
                val fi = line.indexOf(" file=")
                val m = fields(if (fi >= 0) line.substring(0, fi) else line)
                val number = num(m, "picture")
                if (num(m, "message") != e.number) return null
                val state = num(m, "state")
                if (e.pictures.isEmpty() || e.pictures[e.pictures.size - 1][0] != number) e.pictures.add(intArrayOf(number, state))
                if (state == HandoffPicture.ATTACHED) {
                    if (fi < 0) return null
                    val name = line.substring(fi + " file=".length)
                    var list = e.parts[number]
                    if (list == null) {
                        list = ArrayList()
                        e.parts[number] = list
                    }
                    list.add(PicturePart(name, num(m, "strip"), num(m, "left"), num(m, "width"), num(m, "height"), num(m, "overlap")))
                }
            } else {
                return null
            }
        }

        val turns = ArrayList<HandoffTurn>()
        var pos = if (file.size >= 3 && file[0] == 0xEF.toByte() && file[1] == 0xBB.toByte() && file[2] == 0xBF.toByte()) 3 else 0
        for (e in entries) {
            var prompt = ""
            var reply = ""
            if (e.myBytes > 0) {
                val r = readBlock(file, pos, at, messageStart(e.number), e.myBytes, messageEnd(e.number)) ?: return null
                prompt = r.first
                pos = r.second
            }
            if (e.replyBytes > 0) {
                val r = readBlock(file, pos, at, replyStart(e.number), e.replyBytes, replyEnd(e.number)) ?: return null
                reply = r.first
                pos = r.second
            }
            val pics = ArrayList<HandoffPicture>()
            for (p in e.pictures) {
                val parts: List<PicturePart> = e.parts[p[0]] ?: ArrayList()
                pics.add(HandoffPicture(p[0], p[1], parts))
            }
            turns.add(HandoffTurn(e.number, prompt, reply, pics))
        }
        return Parsed(2, title, turns)
    }

    /** Reads exactly `length` bytes of text after the START line. Returns (text, position after the END line). */
    private fun readBlock(file: ByteArray, from: Int, limit: Int, startLine: String, length: Int, endLine: String): Pair<String, Int>? {
        // The START line must be a whole line of its own.
        val start = bytes("\n" + startLine + "\n")
        val i = HtmlCodec.indexOf(file, start, maxOf(0, from - 1), limit)
        if (i < 0) return null
        var p = i + start.size
        if (length < 0 || p + length > limit) return null
        val text = String(file, p, length, Charsets.UTF_8)
        // The bytes must be whole characters: turning the text back into bytes gives the same bytes.
        if (!regionEquals(file, p, bytes(text))) return null
        p += length
        if (!text.endsWith("\n")) {
            if (p >= file.size || file[p] != '\n'.code.toByte()) return null
            p++
        }
        val end = bytes(endLine + "\n")
        if (!regionEquals(file, p, end)) return null
        return Pair(text, p + end.size)
    }

    private fun fields(line: String): HashMap<String, String> {
        val m = HashMap<String, String>()
        for (tok in line.split(" ")) {
            val eq = tok.indexOf('=')
            if (eq > 0) m[tok.substring(0, eq)] = tok.substring(eq + 1)
        }
        return m
    }

    private fun num(m: HashMap<String, String>, key: String): Int {
        val v = m[key] ?: throw IllegalArgumentException("missing " + key)
        return v.toInt()
    }

    /**
     * A text file made by an older version of the app (it says "USER PROMPT (Conversation n)").
     * Those files have no APP DATA block, so the end of each text is found by its END line,
     * and the strip height of the pictures is not known (-1): it is measured from the picture.
     * The file is read strictly in the order the old app wrote it; if anything is not where
     * it should be, the file is refused (null) instead of being read wrongly.
     */
    private fun parseOld(file: ByteArray): Parsed? {
        // Look at the first bytes before anything else, so a big file of another kind costs nothing.
        var off = 0
        if (file.size >= 3 && file[0] == 0xEF.toByte() && file[1] == 0xBB.toByte() && file[2] == 0xBF.toByte()) off = 3
        if (!regionEquals(file, off, bytes("READ THIS FIRST"))) return null
        val text = String(file, off, file.size - off, Charsets.UTF_8)
        val headMark = "\n" + RULE + "\nCONVERSATION "
        val afterHead = RULE + "\n\n"
        val fileEnd = RULE + "\n" + END_OF_FILE + "\n"
        val turns = ArrayList<HandoffTurn>()
        var p = text.indexOf(headMark)
        if (p < 0) return null
        while (true) {
            val numStart = p + headMark.length
            val lineEnd = text.indexOf('\n', numStart)
            if (lineEnd < 0) return null
            val n = text.substring(numStart, lineEnd).toIntOrNull() ?: return null
            p = lineEnd + 1
            if (!text.startsWith(afterHead, p)) return null
            p += afterHead.length

            var prompt = ""
            var reply = ""
            val ps = "---------- USER PROMPT (Conversation $n) : START ----------\n"
            if (text.startsWith(ps, p)) {
                val r = oldBlock(text, p, ps, "---------- USER PROMPT (Conversation $n) : END ----------\n") ?: return null
                prompt = r.first
                p = r.second
                if (!text.startsWith("\n", p)) return null
                p++
            }
            val rs = "---------- AI RESPONSE (Conversation $n) : START ----------\n"
            if (text.startsWith(rs, p)) {
                val r = oldBlock(text, p, rs, "---------- AI RESPONSE (Conversation $n) : END ----------\n") ?: return null
                reply = r.first
                p = r.second
                if (!text.startsWith("\n", p)) return null
                p++
            }

            val pics = ArrayList<HandoffPicture>()
            // Only the lines the old app itself wrote count as picture lines.
            while (prompt.startsWith("[IMAGE: Conversation $n, Image " + (pics.size + 1) + " | ")) {
                val le = prompt.indexOf('\n')
                val line = if (le >= 0) prompt.substring(0, le) else prompt
                prompt = if (le >= 0) prompt.substring(le + 1) else ""
                val number = pics.size + 1
                if (line.contains("was not attached")) {
                    pics.add(HandoffPicture(number, HandoffPicture.LEFT_OUT, ArrayList()))
                } else if (line.contains("could not be attached")) {
                    pics.add(HandoffPicture(number, HandoffPicture.FAILED, ArrayList()))
                } else {
                    val parts = ArrayList<PicturePart>()
                    var q = line.indexOf('"')
                    while (q >= 0) {
                        val q2 = line.indexOf('"', q + 1)
                        if (q2 < 0) break
                        parts.add(PicturePart(line.substring(q + 1, q2), -1, -1, -1, -1, -1))
                        q = line.indexOf('"', q2 + 1)
                    }
                    pics.add(HandoffPicture(number, if (parts.isEmpty()) HandoffPicture.FAILED else HandoffPicture.ATTACHED, parts))
                }
            }
            if (prompt.isNotEmpty() || reply.isNotEmpty() || pics.isNotEmpty()) {
                turns.add(HandoffTurn(n, dropLastNewline(prompt), dropLastNewline(reply), pics))
            }

            if (text.startsWith(headMark, p)) continue
            if (text.startsWith(fileEnd, p) && p + fileEnd.length == text.length) break
            return null
        }
        if (turns.isEmpty()) return null
        return Parsed(1, "", turns)
    }

    /**
     * One START..END block of an old file, beginning at `at`. If the text itself holds a whole
     * copied START..END pair of the same kind, the pair is skipped, so the real END is found.
     * Returns (text, position after the END line).
     */
    private fun oldBlock(text: String, at: Int, start: String, end: String): Pair<String, Int>? {
        val b = at + start.length
        var depth = 1
        var q = b
        var guard = 0
        while (guard < 100000) {
            guard++
            val e = lineIndex(text, end, q)
            if (e < 0) return null
            val s2 = lineIndex(text, start, q)
            if (s2 >= 0 && s2 < e) {
                depth++
                q = s2 + start.length
            } else {
                depth--
                if (depth == 0) return Pair(text.substring(b, e), e + end.length)
                q = e + end.length
            }
        }
        return null
    }

    /** Where `line` begins at the start of a line, at or after `from`; -1 if nowhere. */
    private fun lineIndex(text: String, line: String, from: Int): Int {
        var i = text.indexOf(line, from)
        while (i > 0 && text[i - 1] != '\n') i = text.indexOf(line, i + 1)
        return i
    }

    // The old writer added a line break after text that did not end with one.
    private fun dropLastNewline(s: String): String = if (s.endsWith("\n")) s.substring(0, s.length - 1) else s

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    // The same text, as it is really stored in the file.
    private fun same(a: String, b: String): Boolean = a == b || bytes(a).contentEquals(bytes(b))

    private fun regionEquals(file: ByteArray, at: Int, part: ByteArray): Boolean {
        if (at < 0 || at + part.size > file.size) return false
        for (k in part.indices) if (file[at + k] != part[k]) return false
        return true
    }
}
