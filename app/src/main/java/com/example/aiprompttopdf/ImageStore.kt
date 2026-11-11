package com.example.aiprompttopdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Brings pictures into the app.
 *
 * A normal picture (JPEG, PNG, WebP, GIF) is saved byte-for-byte as it is:
 * never shrunk, never converted, so long code screenshots stay sharp.
 * Only pictures a browser or an AI cannot show (for example HEIC) or photos
 * stored sideways are converted once into a normal upright picture.
 */
object ImageStore {

    fun importFromUri(cr: ContentResolver, uri: Uri, store: Store): String? {
        val tmp = File(store.dir, "in_" + System.nanoTime() + ".tmp")
        try {
            val input = cr.openInputStream(uri) ?: return null
            input.use { inp ->
                FileOutputStream(tmp).use { out -> inp.copyTo(out, 65536) }
            }
            return finishImport(tmp, store)
        } catch (e: Exception) {
            tmp.delete()
            return null
        } catch (e: OutOfMemoryError) {
            tmp.delete()
            return null
        }
    }

    fun importFromBytes(bytes: ByteArray, store: Store): String? {
        val tmp = File(store.dir, "in_" + System.nanoTime() + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            return finishImport(tmp, store)
        } catch (e: Exception) {
            tmp.delete()
            return null
        } catch (e: OutOfMemoryError) {
            tmp.delete()
            return null
        }
    }

    private fun finishImport(tmp: File, store: Store): String? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(tmp.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            tmp.delete()
            return null
        }
        val type = HtmlCodec.typeOfFile(tmp)
        val rotation = exifRotation(tmp)
        if (type != null && rotation == 0) {
            val name = store.newImageName(if (type == "jpeg") "jpg" else type)
            if (tmp.renameTo(store.file(name))) return name
            tmp.delete()
            return null
        }
        val name = convert(tmp, bounds.outWidth, bounds.outHeight, rotation, store)
        tmp.delete()
        return name
    }

    private fun exifRotation(f: File): Int {
        return try {
            val exif = ExifInterface(f.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun convert(src: File, w: Int, h: Int, rotation: Int, store: Store): String? {
        var sample = 1
        while (w / sample > 6000 || h / sample > 6000 || (w.toLong() / sample) * (h.toLong() / sample) > 40000000L) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        var bmp: Bitmap = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return null
        try {
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                bmp = r
            }
            val png = bmp.hasAlpha()
            val name = store.newImageName(if (png) "png" else "jpg")
            val out = store.file(name)
            FileOutputStream(out).use { os ->
                if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, os) else bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
            }
            if (out.length() <= 0L) {
                out.delete()
                return null
            }
            return name
        } finally {
            bmp.recycle()
        }
    }

    /** Small preview picture for the main screen. Very tall pictures show their top part. */
    fun decodeThumb(f: File, maxSide: Int): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (w / (sample * 2) >= maxSide / 2 && h / (sample * 2) >= maxSide / 2) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val full = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            val limitH = full.width * 3
            if (full.height <= limitH) return full
            val top = Bitmap.createBitmap(full, 0, 0, full.width, limitH)
            if (top !== full) full.recycle()
            return top
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    /** Puts a picture with see-through parts onto white (JPEG has no see-through). */
    fun onWhite(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(o)
        c.drawColor(Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        return o
    }
}
