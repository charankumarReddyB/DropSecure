package com.example.dropsecure.io

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.dropsecure.core.ChunkSink
import com.example.dropsecure.settings.AppSettings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Moves a VERIFIED file out of the app-private temp folder to its final place:
 *  1. the folder the user picked in Settings (Storage Access Framework), else
 *  2. Downloads/DropSecure through MediaStore (Android 10+), else
 *  3. the app's own Downloads folder (Android 8-9).
 */
class FileSaver(private val ctx: Context, private val settings: AppSettings) {

    /** @return a human-readable location of the saved file. */
    fun save(sink: ChunkSink, fileName: String, mime: String, expectedSize: Long): String {
        val tree = settings.saveTreeUri.value
        val location: String
        if (tree != null) {
            val treeUri = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, fileName)
                ?: throw IOException("Could not create the file in the chosen folder.")
            ctx.contentResolver.openOutputStream(doc)?.use { out -> sink.openRead().use { copy(it, out, expectedSize) } }
                ?: throw IOException("Could not write to the chosen folder.")
            location = doc.toString()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DropSecure")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create a file in Downloads.")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> sink.openRead().use { copy(it, out, expectedSize) } }
                    ?: throw IOException("Could not write to Downloads.")
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, done, null, null)
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null)
                throw e
            }
            location = "Downloads/DropSecure/$fileName"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            dir.mkdirs()
            val target = uniqueFile(dir, fileName)
            target.outputStream().use { out -> sink.openRead().use { copy(it, out, expectedSize) } }
            location = target.absolutePath
        }
        return location
    }

    private fun copy(input: InputStream, out: OutputStream, expected: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.flush()
        if (total != expected) throw IOException("Saved size ($total) does not match the expected size ($expected).")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }
}
