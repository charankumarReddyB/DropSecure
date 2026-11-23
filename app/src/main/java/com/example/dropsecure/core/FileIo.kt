package com.example.dropsecure.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

class FileChunkSource(
    private val file: File,
    override val mimeType: String = "application/octet-stream",
    override val name: String = file.name
) : ChunkSource {
    override val size: Long = file.length()
    private val raf = RandomAccessFile(file, "r")
    private var cachedHash: ByteArray? = null

    override fun read(index: Int, chunkSize: Int): ByteArray {
        val off = index.toLong() * chunkSize
        val len = minOf(chunkSize.toLong(), size - off).toInt()
        require(off >= 0 && len >= 0) { "chunk out of range" }
        val buf = ByteArray(len)
        raf.seek(off)
        raf.readFully(buf)
        return buf
    }

    override fun sha256(): ByteArray {
        cachedHash?.let { return it }
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().also { cachedHash = it }
    }

    override fun close() = raf.close()
}

/** Writes chunks at their final offsets in a temporary file. Never exposes partial data as a finished file. */
class FileChunkSink(private val file: File) : ChunkSink {
    private var raf: RandomAccessFile? = null

    private fun handle(): RandomAccessFile =
        raf ?: RandomAccessFile(file, "rw").also { raf = it }

    override fun length(): Long = if (file.exists()) file.length() else 0L

    override fun writeChunk(index: Int, chunkSize: Int, data: ByteArray, length: Int) {
        val r = handle()
        r.seek(index.toLong() * chunkSize)
        r.write(data, 0, length)
    }

    override fun sync() { raf?.fd?.sync() }

    override fun openRead(): InputStream {
        if (!file.exists()) file.createNewFile()   // an empty file never had a chunk written
        return FileInputStream(file)
    }

    override fun delete() {
        close()
        file.delete()
    }

    override fun close() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
    }
}
