package com.example.dropsecure.core

import java.io.Closeable
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class TransferConfig(
    val chunkSize: Int = 256 * 1024,
    val maxFileSize: Long = 4L * 1024 * 1024 * 1024,
    val minChunkSize: Int = 1024,
    val maxChunkSize: Int = 1024 * 1024,
    /** Chunks are fsync'd and recorded as durable in batches of this size. */
    val flushEveryChunks: Int = 16
)

fun interface ProgressListener {
    fun onProgress(bytesDone: Long, totalBytes: Long)
}

class TransferOffer(
    val transferId: ByteArray,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val chunkSize: Int,
    val sha256: ByteArray
) {
    val totalChunks: Int
        get() = if (size == 0L) 0 else ((size + chunkSize - 1) / chunkSize).toInt()
    val idHex: String get() = transferId.toHex()

    fun chunkLength(index: Int): Int =
        if (index == totalChunks - 1) (size - index.toLong() * chunkSize).toInt() else chunkSize
}

enum class TransferStatus { IN_PROGRESS, INTERRUPTED, COMPLETED, FAILED, CANCELLED, REJECTED }

/** Persisted by the receiver. Mirrors the Room entity that will be added in the Android layer. */
data class TransferState(
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val chunkSize: Int,
    val sha256Hex: String,
    /** Bit i set => chunk i was authenticated, written AND fsync'd. */
    val receivedBitmap: ByteArray,
    val bytesDone: Long,
    val status: TransferStatus,
    val updatedAtMs: Long
) {
    fun matches(o: TransferOffer) =
        size == o.size && chunkSize == o.chunkSize && sha256Hex == o.sha256.toHex()

    val resumable: Boolean get() = status == TransferStatus.IN_PROGRESS || status == TransferStatus.INTERRUPTED
}

interface TransferStateStore {
    fun get(transferId: String): TransferState?
    fun put(state: TransferState)
}

class InMemoryTransferStateStore : TransferStateStore {
    private val map = ConcurrentHashMap<String, TransferState>()
    override fun get(transferId: String) = map[transferId]?.let { it.copy(receivedBitmap = it.receivedBitmap.copyOf()) }
    override fun put(state: TransferState) { map[state.transferId] = state.copy(receivedBitmap = state.receivedBitmap.copyOf()) }
}

/** Where the sender reads the file from (a File now; a SAF Uri wrapper in the Android layer). */
interface ChunkSource : Closeable {
    val name: String
    val mimeType: String
    val size: Long
    fun read(index: Int, chunkSize: Int): ByteArray
    fun sha256(): ByteArray
}

/** Where the receiver stores chunks until the whole file is verified. */
interface ChunkSink : Closeable {
    fun length(): Long
    fun writeChunk(index: Int, chunkSize: Int, data: ByteArray, length: Int)
    /** Force written data to stable storage. */
    fun sync()
    fun openRead(): InputStream
    /** Delete all stored data. */
    fun delete()
}

class TransferStats(val bytesThisSession: Long, val totalBytes: Long, val durationMs: Long) {
    val throughputMBps: Double
        get() = if (durationMs <= 0) 0.0 else (bytesThisSession / 1_048_576.0) / (durationMs / 1000.0)
}

sealed class TransferResult {
    class Success(val offer: TransferOffer?, val stats: TransferStats, val sink: ChunkSink?) : TransferResult()
    object Rejected : TransferResult()
    class Cancelled(val byPeer: Boolean) : TransferResult()
    class Interrupted(val cause: Throwable) : TransferResult()
    class Failed(val reason: String) : TransferResult()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun randomTransferId(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
