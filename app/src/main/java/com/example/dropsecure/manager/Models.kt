package com.example.dropsecure.manager

import com.example.dropsecure.transport.TransportType

enum class Direction { SEND, RECEIVE }

enum class Stage {
    QUEUED, CONNECTING, KEY_EXCHANGE, VERIFY_CODE, AWAITING_ACCEPT, TRANSFERRING,
    DONE, FAILED, CANCELLED, REJECTED, INTERRUPTED;

    val finished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED || this == REJECTED || this == INTERRUPTED
}

/** Everything the UI needs to draw one transfer. */
data class JobUi(
    val jobId: String,
    val direction: Direction,
    val transport: TransportType,
    val peerName: String,
    val stage: Stage,
    val fileName: String = "",
    val mimeType: String = "",
    val totalBytes: Long = 0,
    val bytesDone: Long = 0,
    val speedBps: Double = 0.0,
    val etaSeconds: Long? = null,
    /** Short verification code both users must compare. */
    val sas: String? = null,
    val codeConfirmed: Boolean = false,
    /** Receiver only: an offer is waiting for Accept / Reject. */
    val offerPending: Boolean = false,
    val resuming: Boolean = false,
    val message: String? = null,
    val transferId: String? = null,
    val savedTo: String? = null,
    val durationMs: Long? = null,
    val throughputMBps: Double? = null
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

data class ReceiveStatus(
    val active: Boolean = false,
    val transport: TransportType? = null,
    val deviceName: String = "",
    val localId: String = "",
    val message: String = ""
)
