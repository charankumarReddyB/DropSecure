package com.example.dropsecure.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.dropsecure.util.blockingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM) data channel.
 *
 * BLE advertising cannot carry file data and cannot reveal a phone's Bluetooth Classic address, so this
 * transport uses normal Bluetooth discovery: the receiver must be on the Receive screen and
 * "discoverable"; the sender scans, picks the device and opens an RFCOMM socket.
 *
 * The INSECURE RFCOMM variant is used on purpose: it avoids the system pairing prompt. Confidentiality
 * and peer verification do NOT depend on Bluetooth pairing; they come from the application-level
 * handshake (ECDH + verification code + AES-256-GCM) in core.Handshake.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(context: Context) {

    private val ctx: Context = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var discoveryReceiver: BroadcastReceiver? = null

    val isSupported: Boolean get() = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun pairedPeers(): List<Peer> = try {
        adapter?.bondedDevices.orEmpty().map { Peer(it.name ?: it.address, TransportType.BLUETOOTH, it.address) }
    } catch (_: SecurityException) { emptyList() }

    /** Classic discovery. Callbacks run on the main thread. Returns false if it could not start. */
    fun startDiscovery(onPeer: (name: String?, address: String, rssi: Int?) -> Unit, onDone: () -> Unit): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        stopDiscovery()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val dev = if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (dev != null) {
                            val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                                ?: try { dev.name } catch (_: SecurityException) { null }
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                            onPeer(name, dev.address, if (rssi == Short.MIN_VALUE) null else rssi.toInt())
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onDone()
                }
            }
        }
        discoveryReceiver = rx
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                ctx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            } catch (_: Exception) {
                try { ctx.registerReceiver(rx, filter) } catch (_: Exception) {}
            }
        } else {
            try { ctx.registerReceiver(rx, filter) } catch (_: Exception) {}
        }
        return try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (_: SecurityException) { false }
    }

    fun stopDiscovery() {
        discoveryReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        discoveryReceiver = null
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
    }

    /** Sender side. Tries insecure RFCOMM (no pairing prompt), secure RFCOMM, and channel 1 reflection fallback. */
    suspend fun connect(address: String): Link {
        val a = adapter ?: throw IOException("This phone has no Bluetooth.")
        if (!a.isEnabled) throw IOException("Turn Bluetooth on first.")
        withContext(Dispatchers.IO) { try { a.cancelDiscovery() } catch (_: Exception) {} }
        val dev = a.getRemoteDevice(address)
        var lastError: Exception? = null

        // Attempt 1: Insecure RFCOMM socket (bypasses system pairing prompt)
        try {
            val s = dev.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            return blockingCancellable({ s.close() }) { s.connect(); socketLink(s, true) }
        } catch (e: Exception) {
            lastError = e
        }

        // Attempt 2: Standard Secure RFCOMM socket
        try {
            val s = dev.createRfcommSocketToServiceRecord(SERVICE_UUID)
            return blockingCancellable({ s.close() }) { s.connect(); socketLink(s, true) }
        } catch (e: Exception) {
            lastError = e
        }

        // Attempt 3: Reflection fallback to channel 1
        try {
            val method = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s = method.invoke(dev, 1) as BluetoothSocket
            return blockingCancellable({ s.close() }) { s.connect(); socketLink(s, true) }
        } catch (e: Exception) {
            lastError = e
        }

        throw IOException("Could not connect over Bluetooth (${lastError?.message}). Ensure both phones have Bluetooth turned on and the receiver is on the Receive screen.")
    }

    /** Receiver side. Opens RFCOMM server socket for incoming connections. */
    fun listen(): BtListener {
        val a = adapter ?: throw IOException("This phone has no Bluetooth.")
        if (!a.isEnabled) throw IOException("Turn Bluetooth on first.")
        val server = try {
            a.listenUsingInsecureRfcommWithServiceRecord("DropSecure", SERVICE_UUID)
        } catch (_: Exception) {
            a.listenUsingRfcommWithServiceRecord("DropSecure", SERVICE_UUID)
        }
        return BtListener(server)
    }

    private fun socketLink(s: BluetoothSocket, initiator: Boolean): Link {
        val name = try { s.remoteDevice?.name } catch (_: SecurityException) { null }
        return Link(s.inputStream, s.outputStream, initiator, name ?: "Bluetooth device", {}, { s.close() })
    }

    inner class BtListener(private val server: BluetoothServerSocket) : Closeable {
        suspend fun accept(): Link = blockingCancellable({ server.close() }) {
            socketLink(server.accept(), initiator = false)
        }
        override fun close() { try { server.close() } catch (_: Exception) {} }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b3f1d7a0-6c52-4e1a-8d2f-9a4c7e03b5d8")
    }
}
