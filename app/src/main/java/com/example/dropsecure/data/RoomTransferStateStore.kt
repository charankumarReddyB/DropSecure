package com.example.dropsecure.data

import com.example.dropsecure.core.TransferState
import com.example.dropsecure.core.TransferStateStore
import com.example.dropsecure.core.TransferStatus

/**
 * Implements the core's resume store on top of Room. Must be called off the main thread
 * (transfers run on Dispatchers.IO). Only the fields owned by the protocol are overwritten;
 * metadata set by the transfer manager (peer, transport, timings...) is preserved.
 */
class RoomTransferStateStore(private val dao: TransferDao) : TransferStateStore {

    override fun get(transferId: String): TransferState? = dao.get(transferId)?.let {
        TransferState(
            it.transferId, it.fileName, it.mimeType, it.size, it.chunkSize, it.sha256Hex,
            it.receivedBitmap, it.bytesDone,
            runCatching { TransferStatus.valueOf(it.status) }.getOrDefault(TransferStatus.FAILED),
            it.updatedAtMs
        )
    }

    override fun put(state: TransferState) {
        val old = dao.get(state.transferId)
        val base = old ?: TransferEntity(
            transferId = state.transferId, direction = "RECEIVE", transport = "UNKNOWN", peerName = "",
            fileName = state.fileName, mimeType = state.mimeType, size = state.size, chunkSize = state.chunkSize,
            sha256Hex = state.sha256Hex, receivedBitmap = state.receivedBitmap, bytesDone = state.bytesDone,
            status = state.status.name, sourceUri = null, tempPath = null, savedTo = null,
            startedAtMs = state.updatedAtMs, updatedAtMs = state.updatedAtMs, durationMs = 0,
            bytesThisSession = 0, throughputMBps = 0.0, discoveryMs = -1, connectMs = -1, resumeCount = 0,
            integrityFailure = false, batteryStartPct = -1, batteryEndPct = -1, error = null
        )
        dao.upsert(
            base.copy(
                fileName = state.fileName, mimeType = state.mimeType, size = state.size, chunkSize = state.chunkSize,
                sha256Hex = state.sha256Hex, receivedBitmap = state.receivedBitmap, bytesDone = state.bytesDone,
                status = state.status.name, updatedAtMs = state.updatedAtMs
            )
        )
    }
}
