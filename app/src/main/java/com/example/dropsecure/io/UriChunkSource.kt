package com.example.dropsecure.io

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.dropsecure.core.ChunkSource
import com.example.dropsecure.core.FileChunkSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Reads a file chosen with the Storage Access Framework. Uses random-access reads straight from the
 * provider when it supports them; otherwise (cloud/pipe providers) it falls back to one temporary
 * copy in the app cache, which is deleted on close.
 */
class UriChunkSource(context: Context, uri: Uri) : ChunkSource {
    override val name: String
    override val mimeType: String
    override val size: Long

    private var input: ParcelFileDescriptor.AutoCloseInputStream? = null
    private var channel: FileChannel? = null
    private var fallback: FileChunkSource? = null
    private var tempCopy: File? = null
    private var cachedHash: ByteArray? = null

    init {
        val resolver = context.contentResolver
        var n: String? = null
        var s = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) n = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) s = c.getLong(si)
            }
        }
        name = n ?: "file"
        mimeType = resolver.getType(uri) ?: "application/octet-stream"

        var ch: FileChannel? = null
        try {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
            val ins = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            try {
                ch = ins.channel
                ch.position(0)                 // throws on non-seekable providers
                input = ins
                channel = ch
                if (s < 0) s = ch.size()
            } catch (e: IOException) {
                try { ins.close() } catch (_: Exception) {}
                ch = null
            }
        } catch (e: IOException) { ch = null }

        if (ch == null) {
            val tmp = File(context.cacheDir, "send-${System.nanoTime()}.tmp")
            resolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                ?: throw IOException("Cannot read the selected file.")
            tempCopy = tmp
            fallback = FileChunkSource(tmp, mimeType, name)
            s = tmp.length()
        }
        size = s
    }

    override fun read(index: Int, chunkSize: Int): ByteArray {
        fallback?.let { return it.read(index, chunkSize) }
        val ch = channel ?: throw IOException("closed")
        val off = index.toLong() * chunkSize
        val len = minOf(chunkSize.toLong(), size - off).toInt()
        require(off >= 0 && len >= 0) { "chunk out of range" }
        val buf = ByteBuffer.allocate(len)
        var pos = off
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) throw IOException("File ended unexpectedly (was it changed while sending?)")
            pos += n
        }
        return buf.array()
    }

    override fun sha256(): ByteArray {
        cachedHash?.let { return it }
        fallback?.let { return it.sha256().also { h -> cachedHash = h } }
        val ch = channel ?: throw IOException("closed")
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteBuffer.allocate(64 * 1024)
        var pos = 0L
        while (pos < size) {
            buf.clear()
            buf.limit(minOf(buf.capacity().toLong(), size - pos).toInt())
            val n = ch.read(buf, pos)
            if (n < 0) throw IOException("File ended unexpectedly")
            md.update(buf.array(), 0, n)
            pos += n
        }
        return md.digest().also { cachedHash = it }
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { fallback?.close() } catch (_: Exception) {}
        tempCopy?.delete()
    }
}
