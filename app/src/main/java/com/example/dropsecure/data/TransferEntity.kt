package com.example.dropsecure.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per transfer (one per receiver when sending to several phones). */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val direction: String,              // "SEND" | "RECEIVE"
    val transport: String,              // TransportType name
    val peerName: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val chunkSize: Int,
    val sha256Hex: String,
    /** Bit i set = chunk i authenticated, written and fsync'd (receiver side). */
    val receivedBitmap: ByteArray,
    val bytesDone: Long,
    val status: String,                 // core.TransferStatus name
    val sourceUri: String?,             // sender: file to resume from
    val tempPath: String?,              // receiver: partial file
    val savedTo: String?,               // receiver: final location
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val durationMs: Long,               // last session: acceptance -> verified completion
    val bytesThisSession: Long,
    val throughputMBps: Double,
    val discoveryMs: Long,              // scan start -> peer usable (-1 unknown)
    val connectMs: Long,                // connection set-up time (-1 unknown)
    val resumeCount: Int,
    val integrityFailure: Boolean,
    val batteryStartPct: Int,           // -1 unknown (1% resolution only)
    val batteryEndPct: Int,
    val error: String?
)
