package com.example.aiprompttopdf

import java.io.File
import java.io.FileInputStream
import java.io.Writer

/** One conversation ready for export. images = the saved picture files, in order. */
class ExportTurn(val prompt: String, val response: String, val images: List<File>)

/**
 * The backup file (HTML) and its import.
 *
 * Pictures are put into the backup exactly as they were saved (the original
 * file, never shrunk or converted), so importing a backup brings everything
 * back as it was.
 */
object HtmlCodec {

    class ExportStats(var images: Int = 0, var missing: Int = 0)

    class ImportedTurn(val prompt: String, val response: String, val images: List<String>)

    class ImportResult(val turns: List<ImportedTurn>, val failedImages: Int)

    // Order matters: & must be replaced first. Do not change.
    fun escape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    // Order matters: &amp; must be replaced last. Do not change.
    fun unescape(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /** 12 -> ১২ */
    fun bn(n: Int): String {
        val s = n.toString()
        val sb = StringBuilder()
        for (ch in s) {
            if (ch in '0'..'9') sb.append(('০'.code + (ch - '0')).toChar()) else sb.append(ch)
        }
        return sb.toString()
    }

    /** Picture type from the first bytes of the file: "jpeg", "png", "gif", "webp" or null. */
    fun sniff(head: ByteArray, len: Int): String? {
        fun b(i: Int): Int = if (i < len) head[i].toInt() and 0xFF else -1
        if (b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return "jpeg"
        if (b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 &&
            b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A) return "png"
        if (b(0) == 'G'.code && b(1) == 'I'.code && b(2) == 'F'.code && b(3) == '8'.code) return "gif"
        if (b(0) == 'R'.code && b(1) == 'I'.code && b(2) == 'F'.code && b(3) == 'F'.code &&
            b(8) == 'W'.code && b(9) == 'E'.code && b(10) == 'B'.code && b(11) == 'P'.code) return "webp"
        return null
    }

    /** true for a WebP picture stored without loss ("VP8L"). */
    fun isLosslessWebp(head: ByteArray, len: Int): Boolean {
        return len >= 16 && head[12] == 'V'.code.toByte() && head[13] == 'P'.code.toByte() &&
            head[14] == '8'.code.toByte() && head[15] == 'L'.code.toByte()
    }

    fun readHead(f: File, size: Int = 32): ByteArray? {
        return try {
            FileInputStream(f).use { input ->
                val buf = ByteArray(size)
                var n = 0
                while (n < size) {
                    val r = input.read(buf, n, size - n)
                    if (r < 0) break
                    n += r
                }
                if (n < size) buf.copyOf(n) else buf
            }
        } catch (e: Exception) {
            null
        }
    }

    fun typeOfFile(f: File): String? {
        val head = readHead(f) ?: return null
        return sniff(head, head.size)
    }

    // ---------------------------------------------------------------------
    // HTML export (streamed, so huge files are safe)
    // ---------------------------------------------------------------------

    fun writeHtml(w: Writer, turns: List<ExportTurn>, generated: String): ExportStats {
        val stats = ExportStats()
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
        w.write(".img-label{font-size:13px;font-weight:bold;color:#d32f2f;margin:14px 0 4px 0;}\n")
        w.write(".img-missing{font-size:13px;color:#555;background:#fff;border:1px dashed #999;border-radius:8px;padding:10px;margin:4px 0 12px 0;}\n")
        w.write("img{display:block;max-width:100%;height:auto;margin:0 auto 12px auto;border:1px solid #bbb;border-radius:8px;background:#fff;padding:4px;box-sizing:border-box;}\n")
        w.write("#copyToast{display:none;position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:10px 20px;border-radius:24px;font-size:14px;z-index:999;}\n")
        w.write("@page{margin:12mm;}\n")
        w.write("@media print{.copy-btn{display:none;}#copyToast{display:none;}body{background:#fff;margin:0;}img{max-width:100%;}}\n")
        w.write("</style>\n")
        w.write("</head>\n")
        w.write("<body>\n")
        w.write("<h1>AI Chat Export</h1>\n")
        w.write("<p class=\"meta\">Generated: ")
        w.write(escape(generated))
        w.write("</p>\n")
        w.flush()

        for ((index, turn) in turns.withIndex()) {
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

                var k = 0
                for (f in turn.images) {
                    k++
                    writeImage(w, f, tId, k, stats)
                }

                if (turn.prompt.isNotEmpty()) {
                    // The extra newline after <pre> is removed again by the browser
                    // and by the import, so text that starts with an empty line stays intact.
                    w.write("<pre id=\"p")
                    w.write(tId.toString())
                    w.write("\" class=\"prompt-text\">\n")
                    w.write(escape(turn.prompt))
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
                w.write("\" class=\"response-text\">\n")
                w.write(escape(turn.response))
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
        w.write("if(window.AppBridge&&window.AppBridge.copy){var done=false;try{done=window.AppBridge.copy(text);}catch(e){done=false;}if(done){ok();}else{bad();}return;}\n")
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
        return stats
    }

    /** Writes one picture as-is, in small base64 pieces, so a huge picture never sits in memory. */
    private fun writeImage(w: Writer, f: File, conv: Int, number: Int, stats: ExportStats) {
        val label = "কথোপকথন " + bn(conv) + "-এর ছবি " + bn(number) + " (Conversation " + conv + ", Image " + number + ")"
        val type = if (f.isFile) typeOfFile(f) else null
        if (type == null) {
            stats.missing++
            w.write("<div class=\"img-missing\">")
            w.write(label)
            w.write(" — ছবিটা খুঁজে পাওয়া যায়নি</div>\n")
            return
        }
        w.write("<div class=\"img-label\">")
        w.write(label)
        w.write("</div>\n")
        w.write("<img src=\"data:image/")
        w.write(type)
        w.write(";base64,")
        FileInputStream(f).use { input ->
            val buf = ByteArray(3 * 16384) // a multiple of 3, so only the last piece gets '=' padding
            while (true) {
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                w.write(Base64x.encode(buf, 0, n))
                if (n < buf.size) break
            }
        }
        w.write("\" alt=\"")
        w.write(label)
        w.write("\">\n")
        w.flush()
        stats.images++
    }

    // ---------------------------------------------------------------------
    // HTML import (works on the raw bytes, so big files need less memory)
    // ---------------------------------------------------------------------

    /** Reads an HTML file made by this app. saveImage stores one picture and returns its file name. */
    fun parse(html: ByteArray, saveImage: (ByteArray) -> String?): ImportResult {
        val turnMark = ascii("<div class=\"turn\">")
        // Only a real <img> tag counts, so a data URI written inside the
        // prompt text can never be mistaken for a picture.
        val imgMark = ascii("<img src=\"data:image/")
        val b64Mark = ascii("base64,")
        val promptMark = ascii("class=\"prompt-text\">")
        val respMark = ascii("class=\"response-text\">")
        val preEnd = ascii("</pre>")
        val quote = '"'.code.toByte()

        val result = ArrayList<ImportedTurn>()
        var failed = 0
        var idx = indexOf(html, turnMark, 0, html.size)
        while (idx >= 0) {
            val next = indexOf(html, turnMark, idx + turnMark.size, html.size)
            val end = if (next >= 0) next else html.size

            val names = ArrayList<String>()
            var from = idx
            while (true) {
                val ms = indexOf(html, imgMark, from, end)
                if (ms < 0) break
                val bm = indexOf(html, b64Mark, ms, end)
                if (bm < 0) break
                val bs = bm + b64Mark.size
                val be = indexOfByte(html, quote, bs, end)
                if (be < 0) break
                val bytes = Base64x.decode(html, bs, be - bs)
                val name = if (bytes != null && bytes.isNotEmpty()) saveImage(bytes) else null
                if (name != null) names.add(name) else failed++
                from = be + 1
            }

            val prompt = extractPre(html, promptMark, preEnd, idx, end)
            val response = extractPre(html, respMark, preEnd, idx, end)
            if (prompt.isNotEmpty() || response.isNotEmpty() || names.isNotEmpty()) {
                result.add(ImportedTurn(prompt, response, names))
            }
            idx = next
        }
        return ImportResult(result, failed)
    }

    private fun extractPre(html: ByteArray, marker: ByteArray, preEnd: ByteArray, from: Int, to: Int): String {
        val s = indexOf(html, marker, from, to)
        if (s < 0) return ""
        val start = s + marker.size
        val e = indexOf(html, preEnd, start, to)
        if (e < 0) return ""
        val raw = String(html, start, e - start, Charsets.UTF_8)
        return unescape(stripFirstNewline(raw))
    }

    // Browsers throw away the first newline right after a <pre> tag, so the
    // export writes one extra newline there and the import removes it again.
    private fun stripFirstNewline(text: String): String {
        if (text.startsWith("\r\n")) return text.substring(2)
        if (text.startsWith("\n")) return text.substring(1)
        return text
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun indexOf(hay: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        if (needle.isEmpty()) return from
        val first = needle[0]
        val last = to - needle.size
        var i = if (from < 0) 0 else from
        while (i <= last) {
            if (hay[i] == first) {
                var k = 1
                while (k < needle.size && hay[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun indexOfByte(hay: ByteArray, b: Byte, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if (hay[i] == b) return i
            i++
        }
        return -1
    }
}

/** Plain base64 (same on every Android version). */
object Base64x {
    private val ENC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
    private val DEC = IntArray(256).also { t ->
        for (i in t.indices) t[i] = -1
        for (i in ENC.indices) t[ENC[i].code] = i
        t['-'.code] = 62
        t['_'.code] = 63
    }

    fun encode(src: ByteArray, off: Int, len: Int): String {
        val out = CharArray((len + 2) / 3 * 4)
        var i = off
        var o = 0
        val end = off + len
        while (i + 2 < end) {
            val v = ((src[i].toInt() and 0xFF) shl 16) or ((src[i + 1].toInt() and 0xFF) shl 8) or (src[i + 2].toInt() and 0xFF)
            out[o++] = ENC[(v ushr 18) and 63]
            out[o++] = ENC[(v ushr 12) and 63]
            out[o++] = ENC[(v ushr 6) and 63]
            out[o++] = ENC[v and 63]
            i += 3
        }
        val rem = end - i
        if (rem == 1) {
            val v = (src[i].toInt() and 0xFF) shl 16
            out[o++] = ENC[(v ushr 18) and 63]
            out[o++] = ENC[(v ushr 12) and 63]
            out[o++] = '='
            out[o++] = '='
        } else if (rem == 2) {
            val v = ((src[i].toInt() and 0xFF) shl 16) or ((src[i + 1].toInt() and 0xFF) shl 8)
            out[o++] = ENC[(v ushr 18) and 63]
            out[o++] = ENC[(v ushr 12) and 63]
            out[o++] = ENC[(v ushr 6) and 63]
            out[o++] = '='
        }
        return String(out, 0, o)
    }

    /** Decodes base64 text stored as bytes. Spaces and line breaks are skipped. null = not base64. */
    fun decode(src: ByteArray, off: Int, len: Int): ByteArray? {
        val out = ByteArray(len / 4 * 3 + 3)
        var acc = 0
        var bits = 0
        var o = 0
        val end = off + len
        var i = off
        while (i < end) {
            val c = src[i].toInt() and 0xFF
            i++
            if (c == '='.code) break
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) continue
            val d = DEC[c]
            if (d < 0) return null
            acc = (acc shl 6) or d
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc shr bits).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out.copyOf(o)
    }
}
