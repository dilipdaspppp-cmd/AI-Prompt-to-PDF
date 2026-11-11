package com.example.aiprompttopdf

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** A file that was saved to the phone. uri is set on Android 10 and newer, file on older phones. */
class SavedFile(val uri: Uri?, val file: File?, val where: String)

/**
 * Saves files into the phone's Download folder (optionally inside a sub-folder).
 * While a file is being written it stays hidden ("pending"); if anything goes
 * wrong, the half-written file is removed, so no broken file is left behind.
 */
object SaveUtil {

    class Pending(val out: OutputStream, val uri: Uri?, val file: File?, val where: String)

    fun open(ctx: Context, fileName: String, mime: String, subFolder: String?): Pending? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            var path = Environment.DIRECTORY_DOWNLOADS
            if (subFolder != null && subFolder.isNotEmpty()) path = path + "/" + subFolder
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out: OutputStream? = try {
                cr.openOutputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (out == null) {
                try {
                    cr.delete(uri, null, null)
                } catch (e: Exception) {
                }
                return null
            }
            return Pending(out, uri, null, "Download/" + (if (subFolder != null && subFolder.isNotEmpty()) subFolder + "/" else "") + fileName)
        } else {
            var dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            if (subFolder != null && subFolder.isNotEmpty()) dir = File(dir, subFolder)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            return Pending(FileOutputStream(f), null, f, f.absolutePath)
        }
    }

    /** ok = false removes the unfinished file. Returns the saved file, or null. */
    fun finish(ctx: Context, p: Pending, ok: Boolean): SavedFile? {
        var good = ok
        try {
            p.out.close()
        } catch (e: Exception) {
            good = false
        }
        val uri = p.uri
        if (uri != null) {
            val cr = ctx.contentResolver
            return try {
                if (good) {
                    val v = ContentValues()
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    cr.update(uri, v, null, null)
                    SavedFile(uri, null, p.where)
                } else {
                    cr.delete(uri, null, null)
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
        if (!good) {
            p.file?.delete()
            return null
        }
        return SavedFile(null, p.file, p.where)
    }

    /** Reads a saved file back from the phone's storage, for checking it. */
    fun readBack(ctx: Context, saved: SavedFile): ByteArray? {
        return try {
            val uri = saved.uri
            if (uri != null) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } else {
                saved.file?.readBytes()
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    fun delete(ctx: Context, saved: SavedFile) {
        try {
            val uri = saved.uri
            if (uri != null) ctx.contentResolver.delete(uri, null, null) else saved.file?.delete()
        } catch (e: Exception) {
        }
    }
}
