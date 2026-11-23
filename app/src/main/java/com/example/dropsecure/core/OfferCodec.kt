package com.example.dropsecure.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Encodes/decodes the file offer. Everything from the peer is validated before use. */
object OfferCodec {
    private const val MAX_OFFER_BYTES = 1024

    fun encode(o: TransferOffer): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.write(o.transferId)
        val name = o.fileName.toByteArray(Charsets.UTF_8)
        out.writeShort(name.size); out.write(name)
        val mime = o.mimeType.toByteArray(Charsets.UTF_8)
        out.writeShort(mime.size); out.write(mime)
        out.writeLong(o.size)
        out.writeInt(o.chunkSize)
        out.write(o.sha256)
        out.flush()
        return bos.toByteArray()
    }

    fun decode(payload: ByteArray, cfg: TransferConfig): TransferOffer {
        if (payload.size > MAX_OFFER_BYTES) throw ProtocolException("offer too large")
        try {
            val inp = DataInputStream(ByteArrayInputStream(payload))
            val id = ByteArray(16).also { inp.readFully(it) }
            val nameLen = inp.readUnsignedShort()
            if (nameLen !in 1..255) throw ProtocolException("bad file name length")
            val name = ByteArray(nameLen).also { inp.readFully(it) }.toString(Charsets.UTF_8)
            val mimeLen = inp.readUnsignedShort()
            if (mimeLen > 127) throw ProtocolException("bad mime length")
            val mime = ByteArray(mimeLen).also { inp.readFully(it) }.toString(Charsets.UTF_8)
            val size = inp.readLong()
            val chunkSize = inp.readInt()
            val sha = ByteArray(32).also { inp.readFully(it) }
            if (inp.available() != 0) throw ProtocolException("trailing bytes in offer")
            if (size < 0 || size > cfg.maxFileSize) throw ProtocolException("file size not allowed")
            if (chunkSize < cfg.minChunkSize || chunkSize > cfg.maxChunkSize) throw ProtocolException("chunk size not allowed")
            return TransferOffer(id, sanitizeFileName(name), mime, size, chunkSize, sha)
        } catch (e: IOException) {
            throw ProtocolException("malformed offer", e)
        }
    }

    /** Removes path components and characters that are unsafe in file names. */
    fun sanitizeFileName(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it.code >= 32 && it !in ":*?\"<>|" }
        n = n.trim().trim('.')
        if (n.isEmpty()) n = "file"
        return n.take(120)
    }

    fun encodeReject(): ByteArray = byteArrayOf(0)

    fun encodeAccept(have: ChunkBitmap): ByteArray {
        val bits = have.toBytes()
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeByte(1); out.writeInt(bits.size); out.write(bits); out.flush()
        return bos.toByteArray()
    }

    /** Returns null for "rejected", otherwise the bitmap of chunks the receiver already has. */
    fun decodeResponse(payload: ByteArray, totalChunks: Int): ChunkBitmap? {
        try {
            val inp = DataInputStream(ByteArrayInputStream(payload))
            when (inp.readUnsignedByte()) {
                0 -> return null
                1 -> {
                    val len = inp.readInt()
                    if (len != (totalChunks + 7) / 8) throw ProtocolException("bad resume bitmap length")
                    val bytes = ByteArray(len).also { inp.readFully(it) }
                    return ChunkBitmap.fromBytes(bytes, totalChunks) ?: throw ProtocolException("bad resume bitmap")
                }
                else -> throw ProtocolException("bad response status")
            }
        } catch (e: IOException) {
            throw ProtocolException("malformed response", e)
        }
    }
}
