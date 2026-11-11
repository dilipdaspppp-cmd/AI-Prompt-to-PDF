package com.example.aiprompttopdf

/**
 * Finds a safe place to cut a very tall screenshot into parts:
 * a thin band of rows with no text in it (the gap between two lines).
 * A row "has text" when the colour jumps sharply many times along it;
 * the gap between lines has almost no jumps, even inside chat bubbles.
 */
object ImageCut {

    private fun lum(c: Int): Int {
        return (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
    }

    fun isQuietRow(px: IntArray, off: Int, width: Int): Boolean {
        val margin = width * 4 / 100 // skip the edges, where scroll bars live
        val from = margin
        val to = width - margin
        if (to - from < 2) return true
        var transitions = 0
        var prev = lum(px[off + from])
        var x = from + 1
        while (x < to) {
            val l = lum(px[off + x])
            val d = if (l > prev) l - prev else prev - l
            if (d > 40) {
                transitions++
                if (transitions > 6) return false
            }
            prev = l
            x++
        }
        return true
    }

    /**
     * px holds `rows` rows of `width` pixels. Looks from the bottom up for three
     * quiet rows in a row and returns the row to cut at (inside the quiet band),
     * or -1 when there is no safe place.
     */
    fun findCut(px: IntArray, width: Int, rows: Int): Int {
        var run = 0
        var r = rows - 1
        while (r >= 0) {
            if (isQuietRow(px, r * width, width)) {
                run++
                if (run >= 3) return r + 1
            } else {
                run = 0
            }
            r--
        }
        return -1
    }
}
