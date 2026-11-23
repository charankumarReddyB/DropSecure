package com.example.dropsecure.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Peer violated the protocol, sent malformed data, or failed authentication. */
open class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

object RecordType {
    const val OFFER = 1
    const val RESPONSE = 2
    const val CHUNK = 3
    const val DONE = 4
    const val RESULT = 5
    const val CANCEL = 6
}

class Record(val type: Int, val index: Int, val payload: ByteArray)

/**
 * An authenticated-encryption channel over any pair of streams (a Wi-Fi Direct TCP socket,
 * a Bluetooth RFCOMM socket, ...). Created by [Handshake].
 *
 * Wire frame:  int length | byte type | int index | AES-256-GCM(ciphertext || 16-byte tag)
 *
 * Security properties:
 *  - Each direction has its own key, so a (key, nonce) pair can never repeat between directions.
 *  - Nonce = 12-byte big-endian record counter, never transmitted. The counter increases on every
 *    record, so a nonce is never reused under one key. The receiver expects exactly the next counter,
 *    so replayed, duplicated, reordered or dropped records fail authentication.
 *  - Associated data = sessionId || type || index || transferId, so a record cannot be moved to a
 *    different session, record type, chunk position or transfer.
 */
class Session internal constructor(
    val sas: String,
    sessionId: ByteArray,
    sendKeyBytes: ByteArray,
    recvKeyBytes: ByteArray,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val maxFrameBytes: Int = MAX_FRAME_BYTES
) : Closeable {

    private val sessionId = sessionId.copyOf()
    @Volatile private var sendKey: SecretKeySpec? = SecretKeySpec(sendKeyBytes, "AES")
    @Volatile private var recvKey: SecretKeySpec? = SecretKeySpec(recvKeyBytes, "AES")
    private val sendLock = Any()
    private val recvLock = Any()
    private var sendCounter = 0L
    private var recvCounter = 0L
    @Volatile private var transferId = ByteArray(0)

    init {
        sendKeyBytes.fill(0)
        recvKeyBytes.fill(0)
    }

    internal val recordsSent: Long get() = sendCounter
    internal val id: ByteArray get() = sessionId.copyOf()

    /** Binds all following records to one transfer. Both sides must call this at the same point. */
    fun bindTransfer(id: ByteArray) {
        transferId = id.copyOf()
    }

    private fun aad(type: Int, index: Int): ByteArray =
        ByteBuffer.allocate(sessionId.size + 1 + 4 + transferId.size)
            .put(sessionId).put(type.toByte()).putInt(index).put(transferId).array()

    fun send(type: Int, index: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset) {
        synchronized(sendLock) {
            val key = sendKey ?: throw IOException("session closed")
            if (sendCounter >= MAX_RECORDS) throw ProtocolException("record limit reached; start a new session")
            val counter = sendCounter++            // consume the nonce BEFORE use, even if the write fails
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonceFor(counter)))
            cipher.updateAAD(aad(type, index))
            val ct = cipher.doFinal(payload, offset, length)
            output.writeInt(1 + 4 + ct.size)
            output.writeByte(type)
            output.writeInt(index)
            output.write(ct)
            output.flush()
        }
    }

    fun receive(): Record {
        synchronized(recvLock) {
            val key = recvKey ?: throw IOException("session closed")
            val total = input.readInt()
            if (total < 1 + 4 + TAG_BYTES || total > maxFrameBytes) throw ProtocolException("bad frame length")
            val type = input.readUnsignedByte()
            val index = input.readInt()
            val ct = ByteArray(total - 5)
            input.readFully(ct)
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonceFor(recvCounter)))
                cipher.updateAAD(aad(type, index))
                cipher.doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw ProtocolException("authentication failed (tampered, replayed or reordered data)", e)
            }
            recvCounter++
            return Record(type, index, plain)
        }
    }

    override fun close() {
        sendKey = null
        recvKey = null
        try { output.close() } catch (_: Exception) {}
        try { input.close() } catch (_: Exception) {}
    }

    companion object {
        const val TAG_BYTES = 16
        const val MAX_FRAME_BYTES = 1024 * 1024 + 1024
        const val MAX_RECORDS = 1L shl 40

        internal fun nonceFor(counter: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
    }
}
