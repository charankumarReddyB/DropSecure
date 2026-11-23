package com.example.dropsecure.core

import java.io.IOException

/**
 * Sends one file over an already-verified [Session].
 *
 * Resume: reuse the same [transferId] on a NEW session. The receiver answers with the bitmap of
 * chunks it has durably stored, and the sender transmits only the missing ones.
 *
 * Cancel: set [isCancelled] (checked between chunks) or close the session from another thread.
 */
class FileSender(
    private val session: Session,
    private val config: TransferConfig = TransferConfig(),
    val transferId: ByteArray = randomTransferId()
) {
    fun send(
        source: ChunkSource,
        listener: ProgressListener? = null,
        isCancelled: () -> Boolean = { false }
    ): TransferResult {
        val startNs = System.nanoTime()
        try {
            if (source.size > config.maxFileSize) return TransferResult.Failed("file too large")
            val chunkSize = config.chunkSize
            val offer = TransferOffer(transferId, source.name, source.mimeType, source.size, chunkSize, source.sha256())
            val total = offer.totalChunks

            session.send(RecordType.OFFER, 0, OfferCodec.encode(offer))
            session.bindTransfer(transferId)

            val resp = session.receive()
            if (resp.type == RecordType.CANCEL) return TransferResult.Cancelled(true)
            if (resp.type != RecordType.RESPONSE) throw ProtocolException("expected RESPONSE")
            val have = OfferCodec.decodeResponse(resp.payload, total) ?: return TransferResult.Rejected

            var bytesDone = 0L
            for (i in 0 until total) if (have.get(i)) bytesDone += offer.chunkLength(i)
            var sent = 0L
            listener?.onProgress(bytesDone, offer.size)

            for (i in 0 until total) {
                if (have.get(i)) continue
                if (isCancelled()) {
                    try { session.send(RecordType.CANCEL, 0, ByteArray(0)) } catch (_: Exception) {}
                    return TransferResult.Cancelled(false)
                }
                val data = source.read(i, chunkSize)
                session.send(RecordType.CHUNK, i, data)
                bytesDone += data.size; sent += data.size
                listener?.onProgress(bytesDone, offer.size)
            }

            session.send(RecordType.DONE, 0, ByteArray(0))
            val res = session.receive()
            if (res.type == RecordType.CANCEL) return TransferResult.Cancelled(true)
            if (res.type != RecordType.RESULT || res.payload.size != 1) throw ProtocolException("expected RESULT")
            if (res.payload[0].toInt() != 1) return TransferResult.Failed("receiver reported integrity check failure")

            val ms = (System.nanoTime() - startNs) / 1_000_000
            return TransferResult.Success(offer, TransferStats(sent, offer.size, ms), null)
        } catch (e: ProtocolException) {
            return TransferResult.Failed(e.message ?: "protocol error")
        } catch (e: IOException) {
            return if (isCancelled()) TransferResult.Cancelled(false) else TransferResult.Interrupted(e)
        }
    }
}
