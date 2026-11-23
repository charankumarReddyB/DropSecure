package com.example.dropsecure.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

enum class TransportType(val label: String) {
    WIFI_DIRECT("Wi-Fi Direct"),
    BLUETOOTH("Bluetooth")
}

/** A device the sender can connect to. [address] is a Wi-Fi P2P or Bluetooth MAC address. */
data class Peer(
    val name: String,
    val transport: TransportType,
    val address: String,
    /** Time from starting the scan until this peer was first usable, or -1 if unknown. */
    val discoveryMs: Long = -1,
    val bleId: String? = null
)

/**
 * A connected byte stream to one peer, whatever the radio. The secure session (core.Handshake)
 * runs on top of it. [isInitiator] is true for the side that opened the connection.
 */
class Link(
    val input: InputStream,
    val output: OutputStream,
    val isInitiator: Boolean,
    val remoteName: String,
    private val timeoutSetter: (Int) -> Unit = {},
    private val closer: () -> Unit
) : Closeable {
    /** Read timeout in ms (0 = none). No-op for transports that do not support it. */
    fun setTimeout(ms: Int) { try { timeoutSetter(ms) } catch (_: Exception) {} }
    override fun close() { try { closer() } catch (_: Exception) {} }
}
