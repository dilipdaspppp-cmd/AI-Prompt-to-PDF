package com.example.aiprompttopdf

import java.io.Writer

/**
 * One conversation for the text file. number = the conversation number shown in the app.
 * imageLines = one ready-made "[IMAGE: ...]" line per picture of this conversation.
 */
class HandoffTurn(val number: Int, val prompt: String, val response: String, val imageLines: List<String>)

/**
 * The plain text file that is handed to the next AI.
 *
 * The prompt and the response are written exactly as they are: nothing is
 * trimmed, escaped or re-wrapped, so every line of code and every space stays.
 * After saving, verify() reads the saved file again and compares it with the
 * original text byte by byte.
 *
 * The file begins with an invisible mark (the UTF-8 byte order mark). A plain
 * text file does not say which alphabet rules it uses, so some apps (Chrome,
 * for one) guess wrongly and show Bengali as rows of strange symbols. The mark
 * tells every app to read the file as UTF-8. It sits before the first line,
 * outside all prompts, responses and code, so the copied text is not touched.
 */
object HandoffText {

    const val UTF8_MARK = "\uFEFF"
    const val RULE = "======================================================================"
    const val END_OF_FILE = "END OF FILE"

    fun promptStart(n: Int): String = "---------- USER PROMPT (Conversation $n) : START ----------"
    fun promptEnd(n: Int): String = "---------- USER PROMPT (Conversation $n) : END ----------"
    fun responseStart(n: Int): String = "---------- AI RESPONSE (Conversation $n) : START ----------"
    fun responseEnd(n: Int): String = "---------- AI RESPONSE (Conversation $n) : END ----------"

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

    fun write(w: Writer, turns: List<HandoffTurn>, generated: String, totalInApp: Int, pictureFiles: Int) {
        w.write(UTF8_MARK)
        w.write("READ THIS FIRST (a note for the AI)\n")
        w.write("This file is an exact copy of a conversation I had with another AI assistant.\n")
        w.write("I could not continue there because its usage limit ran out, so I am continuing with you.\n")
        w.write("\n")
        w.write("How to read this file:\n")
        w.write("- \"USER PROMPT\" is what I wrote. \"AI RESPONSE\" is what that AI answered.\n")
        w.write("- Everything between a START line and its END line is copied exactly, character for\n")
        w.write("  character, including all code, blank lines and indentation.\n")
        w.write("- Where I had attached a picture there is a line that begins with [IMAGE: ...].\n")
        w.write("  The picture itself is attached to this chat as a separate image file, and the same\n")
        w.write("  label is printed in a yellow strip at the top of that picture.\n")
        w.write("\n")
        w.write("Please read everything first. Then answer my new message, continuing from where\n")
        w.write("this conversation stopped.\n")
        w.write("\n")
        w.write("(বাংলায়: এটা আগের একটা AI-এর সাথে আমার কথোপকথনের হুবহু কপি। পুরোটা পড়ে নাও,\n")
        w.write("তারপর আমার নতুন মেসেজ থেকে কাজ চালিয়ে যাও।)\n")
        w.write("\n")
        w.write("Created: ")
        w.write(generated)
        w.write("\n")
        val nums = StringBuilder()
        for (t in turns) {
            if (nums.isNotEmpty()) nums.append(", ")
            nums.append(t.number)
        }
        w.write("Conversations in this file: " + turns.size + " (number " + nums + ")")
        if (totalInApp > turns.size) w.write(" - chosen out of " + totalInApp)
        w.write("\n")
        w.write("Picture files attached separately: " + pictureFiles + "\n")

        for (t in turns) {
            val n = t.number
            w.write("\n")
            w.write(RULE)
            w.write("\n")
            w.write("CONVERSATION $n\n")
            w.write(RULE)
            w.write("\n\n")
            if (t.prompt.isNotEmpty() || t.imageLines.isNotEmpty()) {
                w.write(promptStart(n))
                w.write("\n")
                for (line in t.imageLines) {
                    w.write(line)
                    w.write("\n")
                }
                w.write(t.prompt)
                if (t.prompt.isNotEmpty() && !t.prompt.endsWith("\n")) w.write("\n")
                w.write(promptEnd(n))
                w.write("\n\n")
            }
            if (t.response.isNotEmpty()) {
                w.write(responseStart(n))
                w.write("\n")
                w.write(t.response)
                if (!t.response.endsWith("\n")) w.write("\n")
                w.write(responseEnd(n))
                w.write("\n\n")
            }
            w.flush()
        }
        w.write(RULE)
        w.write("\n")
        w.write(END_OF_FILE)
        w.write("\n")
        w.flush()
    }

    class Check(val ok: Boolean, val lines: Int, val problem: String)

    /**
     * Reads the saved file and checks that every prompt and every response is in it
     * completely and unchanged, in the right place, and that the file is not cut short.
     */
    fun verify(file: ByteArray, turns: List<HandoffTurn>): Check {
        // The invisible mark must be the very first thing in the file.
        if (file.size < 3 || file[0] != 0xEF.toByte() || file[1] != 0xBB.toByte() || file[2] != 0xBF.toByte()) {
            return Check(false, 0, "The mark at the start of the file is missing")
        }
        var pos = 3
        for (t in turns) {
            val n = t.number
            if (t.prompt.isNotEmpty() || t.imageLines.isNotEmpty()) {
                val start = bytes(promptStart(n) + "\n")
                val i = HtmlCodec.indexOf(file, start, pos, file.size)
                if (i < 0) return Check(false, 0, "Conversation $n: prompt start not found")
                var p = i + start.size
                for (line in t.imageLines) {
                    val lb = bytes(line + "\n")
                    if (!regionEquals(file, p, lb)) return Check(false, 0, "Conversation $n: picture line differs")
                    p += lb.size
                }
                val body = bytes(t.prompt)
                if (!regionEquals(file, p, body)) return Check(false, 0, "Conversation $n: prompt text differs")
                p += body.size
                if (t.prompt.isNotEmpty() && !t.prompt.endsWith("\n")) {
                    if (p >= file.size || file[p] != '\n'.code.toByte()) return Check(false, 0, "Conversation $n: prompt is cut short")
                    p++
                }
                val end = bytes(promptEnd(n) + "\n")
                if (!regionEquals(file, p, end)) return Check(false, 0, "Conversation $n: prompt end differs")
                pos = p + end.size
            }
            if (t.response.isNotEmpty()) {
                val start = bytes(responseStart(n) + "\n")
                val i = HtmlCodec.indexOf(file, start, pos, file.size)
                if (i < 0) return Check(false, 0, "Conversation $n: response start not found")
                var p = i + start.size
                val body = bytes(t.response)
                if (!regionEquals(file, p, body)) return Check(false, 0, "Conversation $n: response text differs")
                p += body.size
                if (!t.response.endsWith("\n")) {
                    if (p >= file.size || file[p] != '\n'.code.toByte()) return Check(false, 0, "Conversation $n: response is cut short")
                    p++
                }
                val end = bytes(responseEnd(n) + "\n")
                if (!regionEquals(file, p, end)) return Check(false, 0, "Conversation $n: response end differs")
                pos = p + end.size
            }
        }
        val tail = bytes(RULE + "\n" + END_OF_FILE + "\n")
        if (file.size < tail.size || !regionEquals(file, file.size - tail.size, tail) || file.size - tail.size < pos) {
            return Check(false, 0, "The file is cut short at the end")
        }
        return Check(true, totalLines(turns), "")
    }

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    private fun regionEquals(file: ByteArray, at: Int, part: ByteArray): Boolean {
        if (at < 0 || at + part.size > file.size) return false
        for (k in part.indices) if (file[at + k] != part[k]) return false
        return true
    }
}
