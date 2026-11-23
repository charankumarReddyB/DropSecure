package com.example.dropsecure.core

import java.io.IOException
import java.security.MessageDigest

/**
 * Receives one file over an already-verified [Session].
 *
 * Safety rules enforced here:
 *  - Nothing is accepted until [onOffer] returns true (user approval).
 *  - Every chunk must authenticate (AES-GCM), have a valid index, the exact expected length, and not
 *    be a duplicate.
 *  - A chunk is recorded as "done" in the persisted bitmap only AFTER it was written and fsync'd.
 *  - The finished file is exposed (Success) only after the SHA-256 of all stored data equals the hash
 *    in the offer. On mismatch the partial data and progress are deleted.
 */
class FileReceiver(
    private val session: Session,
    private val config: TransferConfig,
    private val store: TransferStateStore,
    private val sinkFactory: (TransferOffer) -> ChunkSink
) {
    fun receive(
        onOffer: (offer: TransferOffer, resuming: Boolean) -> Boolean = { _, _ -> true },
        listener: ProgressListener? = null,
        isCancelled: () -> Boolean = { false }
    ): TransferResult {
        val startNs = System.nanoTime()
        var offer: TransferOffer? = null
        var sink: ChunkSink? = null
        var durable: ChunkBitmap? = null
        var have: ChunkBitmap? = null
        val inflight = ArrayList<Int>()
        var durableBytes = 0L
        var bytesDone = 0L
        var sessionBytes = 0L

        fun persist(status: TransferStatus, bitmap: ChunkBitmap? = durable, bytes: Long = durableBytes) {
            val o = offer ?: return
            val b = bitmap ?: return
            store.put(
                TransferState(
                    o.idHex, o.fileName, o.mimeType, o.size, o.chunkSize, o.sha256.toHex(),
                    b.toBytes(), bytes, status, System.currentTimeMillis()
                )
            )
        }

        fun flush() {
            val s = sink ?: return
            val o = offer ?: return
            val d = durable ?: return
            s.sync()
            for (i in inflight) { d.set(i); durableBytes += o.chunkLength(i) }
            inflight.clear()
            persist(TransferStatus.IN_PROGRESS)
        }

        fun flushQuietly() { try { flush() } catch (_: Exception) {} }

        try {
            val first = session.receive()
            if (first.type != RecordType.OFFER) throw ProtocolException("expected OFFER")
            val o = OfferCodec.decode(first.payload, config)
            offer = o
            session.bindTransfer(o.transferId)
            val total = o.totalChunks

            val prior = store.get(o.idHex)?.takeIf { it.resumable && it.matches(o) }
            var d = prior?.let { ChunkBitmap.fromBytes(it.receivedBitmap, total) } ?: ChunkBitmap(total)
            val resuming = d.count() > 0

            if (!onOffer(o, resuming)) {
                session.send(RecordType.RESPONSE, 0, OfferCodec.encodeReject())
                return TransferResult.Rejected
            }

            val s = sinkFactory(o)
            sink = s

            // Partial file must still be at least as long as the data we claim to have.
            if (resuming) {
                var needed = 0L
                for (i in 0 until total) if (d.get(i)) needed = maxOf(needed, i.toLong() * o.chunkSize + o.chunkLength(i))
                if (s.length() < needed) d = ChunkBitmap(total)
            }
            durable = d
            have = d.copy()
            for (i in 0 until total) if (d.get(i)) durableBytes += o.chunkLength(i)
            bytesDone = durableBytes
            persist(TransferStatus.IN_PROGRESS)

            session.send(RecordType.RESPONSE, 0, OfferCodec.encodeAccept(d))
            listener?.onProgress(bytesDone, o.size)

            while (true) {
                val r = session.receive()
                when (r.type) {
                    RecordType.CHUNK -> {
                        val idx = r.index
                        if (idx < 0 || idx >= total) throw ProtocolException("chunk index out of range")
                        if (have!!.get(idx)) throw ProtocolException("duplicate chunk")
                        val expected = o.chunkLength(idx)
                        if (r.payload.size != expected) throw ProtocolException("bad chunk length")
                        s.writeChunk(idx, o.chunkSize, r.payload, r.payload.size)
                        have.set(idx)
                        inflight.add(idx)
                        bytesDone += expected; sessionBytes += expected
                        if (inflight.size >= config.flushEveryChunks) flush()
                        listener?.onProgress(bytesDone, o.size)
                        if (isCancelled()) {
                            try { session.send(RecordType.CANCEL, 0, ByteArray(0)) } catch (_: Exception) {}
                            s.delete()
                            persist(TransferStatus.CANCELLED, ChunkBitmap(total), 0)
                            return TransferResult.Cancelled(false)
                        }
                    }
                    RecordType.DONE -> {
                        if (have!!.count() != total) throw ProtocolException("DONE before all chunks arrived")
                        flush()
                        s.close()
                        val ok = hashMatches(s, o)
                        session.send(RecordType.RESULT, 0, byteArrayOf(if (ok) 1 else 0))
                        if (!ok) {
                            s.delete()
                            persist(TransferStatus.FAILED, ChunkBitmap(total), 0)
                            return TransferResult.Failed("SHA-256 mismatch: file corrupted or tampered")
                        }
                        persist(TransferStatus.COMPLETED, have, o.size)
                        val ms = (System.nanoTime() - startNs) / 1_000_000
                        return TransferResult.Success(o, TransferStats(sessionBytes, o.size, ms), s)
                    }
                    RecordType.CANCEL -> {
                        flushQuietly()
                        s.delete()
                        persist(TransferStatus.CANCELLED, ChunkBitmap(total), 0)
                        return TransferResult.Cancelled(true)
                    }
                    else -> throw ProtocolException("unexpected record type ${r.type}")
                }
            }
        } catch (e: ProtocolException) {
            flushQuietly()
            persist(TransferStatus.INTERRUPTED)
            return TransferResult.Failed(e.message ?: "protocol error")
        } catch (e: IOException) {
            flushQuietly()
            persist(TransferStatus.INTERRUPTED)
            return if (isCancelled()) TransferResult.Cancelled(false) else TransferResult.Interrupted(e)
        } finally {
            try { sink?.close() } catch (_: Exception) {}
        }
    }

    private fun hashMatches(sink: ChunkSink, o: TransferOffer): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        sink.openRead().use { ins ->
            val buf = ByteArray(64 * 1024)
            var remaining = o.size
            while (remaining > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) return false
                md.update(buf, 0, n); remaining -= n
            }
        }
        return MessageDigest.isEqual(md.digest(), o.sha256)
    }
}
