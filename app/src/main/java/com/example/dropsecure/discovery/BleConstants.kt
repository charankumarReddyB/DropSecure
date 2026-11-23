package com.example.dropsecure.discovery

import java.util.UUID

object BleConstants {
    /** Identifies "a DropSecure phone" in BLE advertisements. */
    val SERVICE_UUID: UUID = UUID.fromString("7c5a1d20-3b4e-4f6a-9c1d-5e8a2b9f0d11")

    /** Random per-launch ID carried in the advertisement (BLE payloads are tiny). */
    const val ID_LENGTH = 4

    /** A device not heard from for this long is removed from the list. */
    const val STALE_AFTER_MS = 15_000L
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
