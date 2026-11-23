package com.example.dropsecure.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import com.example.dropsecure.util.blockingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wi-Fi Direct (Wi-Fi P2P) connection setup.
 *
 *  - Receiver: registers a DNS-SD service {id, name} so senders can find it, becomes visible, then
 *    waits for the sender to connect. The OTHER phone shows Android's own "Accept invitation?" prompt.
 *  - Sender: connects to the chosen device address (groupOwnerIntent = 0, so the receiver usually
 *    becomes group owner, but either outcome works).
 *  - The group owner opens a TCP server socket on [PORT]; the other phone connects to it. The TCP
 *    stream becomes a [Link].
 *
 * Wi-Fi P2P behaviour differs between phone makers; failures are reported as IOException with a
 * readable message instead of being assumed away.
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(context: Context) {

    private val ctx: Context = context.applicationContext
    private val manager: WifiP2pManager? = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val discoveryReceivers = ArrayList<BroadcastReceiver>()
    private var localService: WifiP2pDnsSdServiceInfo? = null
    var myP2pMac: String? = null

    init {
        try {
            val filter = IntentFilter(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            val rx = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION == intent.action) {
                        val dev = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        dev?.deviceAddress?.takeIf { isMacAddress(it) }?.let {
                            myP2pMac = it
                        }
                    }
                }
            }
            registerCompat(rx, filter)
        } catch (_: Exception) {}
    }

    val isSupported: Boolean
        get() = manager != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun isWifiOn(): Boolean =
        (ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    private fun isMacAddress(str: String): Boolean {
        val trimmed = str.trim().uppercase()
        if (trimmed == "02:00:00:00:00:00" || trimmed == "00:00:00:00:00:00") return false
        val regex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        return regex.matches(trimmed)
    }

    private fun freshChannel(): WifiP2pManager.Channel {
        val m = manager ?: throw IOException("Wi-Fi Direct is not supported on this phone.")
        val c = m.initialize(ctx, Looper.getMainLooper()) {
            channel = null
        } ?: throw IOException("Could not start Wi-Fi Direct. Turn Wi-Fi on and try again.")
        channel = c
        return c
    }

    @Synchronized
    private fun channel(): WifiP2pManager.Channel {
        channel?.let { return it }
        return freshChannel()
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Please wait a moment and try again."
        WifiP2pManager.ERROR -> "Wi-Fi Direct framework error. Try toggling Wi-Fi off and on."
        WifiP2pManager.NO_SERVICE_REQUESTS -> "Wi-Fi Direct service error."
        else -> "Wi-Fi Direct connection failed (code $reason). Ensure Wi-Fi and Location are on."
    }

    private fun listener(onOk: () -> Unit = {}, onFail: (Int) -> Unit = {}) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onOk()
            override fun onFailure(reason: Int) = onFail(reason)
        }

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            try {
                block(listener(
                    onOk = { if (cont.isActive) cont.resume(Unit) },
                    onFail = { r -> if (cont.isActive) cont.resumeWithException(IOException(reasonText(r))) }
                ))
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resumeWithException(IOException("Missing Wi-Fi Direct permission."))
            }
        }

    private fun registerCompat(rx: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(rx, filter)
    }

    private fun unregisterQuietly(rx: BroadcastReceiver) {
        try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ group handling

    /** Quietly cancels any pending connection / old group without throwing noisy callbacks. */
    fun resetGroupQuietly() {
        val m = manager ?: return
        val c = channel ?: return
        try { m.cancelConnect(c, null) } catch (_: Exception) {}
        try { m.removeGroup(c, null) } catch (_: Exception) {}
    }

    /** Removes any old group / pending connection so a stale group is never reused. */
    suspend fun resetGroup() {
        resetGroupQuietly()
        delay(400)
    }

    // ------------------------------------------------------------------ receiver side

    /** Makes this phone findable (DNS-SD service + Wi-Fi Direct discovery). */
    suspend fun startReceiving(localId: String, deviceName: String) {
        val m = manager ?: throw IOException("Wi-Fi Direct is not supported on this phone.")
        if (!isWifiOn()) throw IOException("Turn Wi-Fi on to use Wi-Fi Direct.")
        val c = freshChannel()
        resetGroupQuietly()
        delay(300)
        try { m.clearLocalServices(c, null) } catch (_: Exception) {}
        try { m.clearServiceRequests(c, null) } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= 29) {
            val groupConfig = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-DS-$localId")
                .setPassphrase("DropSecure_$localId")
                .build()
            var created = false
            for (attempt in 1..3) {
                try {
                    action { m.createGroup(c, groupConfig, it) }
                    Log.i("DropSecure", "Created autonomous Wi-Fi Direct group DIRECT-DS-$localId")
                    created = true
                    break
                } catch (e: Exception) {
                    Log.w("DropSecure", "createGroup attempt $attempt failed: ${e.message}")
                    delay(500)
                }
            }
            if (!created) {
                try { action { m.createGroup(c, it) } } catch (e: Exception) {
                    Log.w("DropSecure", "Fallback standard createGroup failed: ${e.message}")
                }
            }
        } else {
            try { action { m.createGroup(c, it) } } catch (_: Exception) {}
        }

        val meta = HashMap<String, String>()
        meta["id"] = localId
        meta["name"] = deviceName
        meta["ssid"] = "DIRECT-DS-$localId"
        meta["pass"] = "DropSecure_$localId"
        myP2pMac?.let { meta["mac"] = it }

        val info = WifiP2pDnsSdServiceInfo.newInstance("DropSecure_$localId", SERVICE_TYPE, meta)
        localService = info
        try { action { m.addLocalService(c, info, it) } } catch (_: Exception) {}

        // Put Wi-Fi Direct in listen/discovery mode so incoming connection requests are received
        try { m.discoverPeers(c, null) } catch (_: Exception) {}
    }

    /** Waits (without time limit, cancellable) for a sender to connect, then opens the TCP stream. */
    suspend fun awaitIncoming(): Link {
        val info = awaitGroup(timeoutMs = 0L)
        return openSocket(info)
    }

    suspend fun stopReceiving() {
        val m = manager ?: return
        val c = channel ?: return
        try { m.clearLocalServices(c, null) } catch (_: Exception) {}
        try { m.clearServiceRequests(c, null) } catch (_: Exception) {}
        try { m.stopPeerDiscovery(c, null) } catch (_: Exception) {}
        localService = null
        resetGroupQuietly()
    }

    // ------------------------------------------------------------------ sender side

    suspend fun connect(address: String, bleId: String? = null): Link {
        val m = manager ?: throw IOException("Wi-Fi Direct is not supported on this phone.")
        if (!isWifiOn()) throw IOException("Turn Wi-Fi on to use Wi-Fi Direct.")

        var c = channel()
        try { m.cancelConnect(c, null) } catch (_: Exception) {}
        delay(200)

        var lastError: Exception? = null

        // Strategy A: Connect directly using deterministic credentials if bleId is available (Android 10+)
        if (Build.VERSION.SDK_INT >= 29 && !bleId.isNullOrBlank()) {
            val ssid = "DIRECT-DS-$bleId"
            val pass = "DropSecure_$bleId"
            try {
                Log.i("DropSecure", "Attempting direct group connect to $ssid...")
                val cfg = WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(pass)
                    .build()
                action { m.connect(c, cfg, it) }
                Log.i("DropSecure", "Direct connect command to $ssid accepted by OS!")
                lastError = null
            } catch (e: Exception) {
                Log.w("DropSecure", "Direct group connect failed (${e.message}), trying MAC connection")
                lastError = e
            }
        }

        // Strategy B: Fallback to MAC-based PBC connection
        if (lastError != null || bleId.isNullOrBlank() || Build.VERSION.SDK_INT < 29) {
            val cleanMac = address.trim().uppercase()
            if (isMacAddress(cleanMac)) {
                val cfg = WifiP2pConfig().apply {
                    deviceAddress = cleanMac
                    wps.setup = WpsInfo.PBC
                    groupOwnerIntent = 0
                }
                ensurePeerReady(m, c, cleanMac, maxWaitMs = 4000L)
                for (attempt in 1..3) {
                    try {
                        action { m.connect(c, cfg, it) }
                        lastError = null
                        break
                    } catch (e: Exception) {
                        lastError = e
                        c = freshChannel()
                        try { m.discoverPeers(c, null) } catch (_: Exception) {}
                        delay(1200)
                    }
                }
            }
        }

        if (lastError != null) {
            throw IOException("Could not initiate Wi-Fi Direct connection: ${lastError.message}")
        }

        val info = awaitGroup(CONNECT_TIMEOUT_MS)
        return openSocket(info)
    }

    private suspend fun ensurePeerReady(
        m: WifiP2pManager,
        c: WifiP2pManager.Channel,
        targetMac: String,
        maxWaitMs: Long = 5000L
    ): Boolean {
        try { m.discoverPeers(c, null) } catch (_: Exception) {}
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val found = suspendCancellableCoroutine<Boolean> { cont ->
                try {
                    m.requestPeers(c) { list ->
                        val hasPeer = list?.deviceList.orEmpty().any { dev ->
                            dev.deviceAddress.equals(targetMac, ignoreCase = true)
                        }
                        if (cont.isActive) cont.resume(hasPeer)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (found) return true
            delay(500)
        }
        return false
    }

    // ------------------------------------------------------------------ discovery

    /** Raw Wi-Fi P2P peers (name + MAC). Callback runs on the main thread. */
    fun startPeerDiscovery(onPeers: (List<Pair<String, String>>) -> Unit, onError: (String) -> Unit) {
        val m = manager ?: return onError("Wi-Fi Direct is not supported on this phone.")
        val c = try { channel() } catch (e: IOException) { return onError(e.message ?: "Wi-Fi Direct error") }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    m.requestPeers(c) { list ->
                        val peers = list?.deviceList.orEmpty().mapNotNull { dev ->
                            val addr = dev.deviceAddress?.takeIf { isMacAddress(it) } ?: return@mapNotNull null
                            val name = if (dev.deviceName.isNullOrBlank()) addr else dev.deviceName
                            addr to name
                        }
                        onPeers(peers)
                    }
                } catch (_: SecurityException) {}
            }
        }
        discoveryReceivers.add(rx)
        registerCompat(rx, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
        try {
            m.discoverPeers(c, listener(onFail = { onError(reasonText(it)) }))
        } catch (e: SecurityException) { onError("Missing Wi-Fi Direct permission.") }
    }

    /** DNS-SD discovery of DropSecure receivers: gives (id, name, address). Main-thread callback. */
    fun startServiceDiscovery(onFound: (id: String, name: String, address: String) -> Unit, onError: (String) -> Unit) {
        val m = manager ?: return
        val c = try { channel() } catch (e: IOException) { return onError(e.message ?: "Wi-Fi Direct error") }
        try {
            m.setDnsSdResponseListeners(
                c,
                WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> },
                WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
                    val id = record["id"]
                    val mac = record["mac"]?.takeIf { isMacAddress(it) }
                        ?: device.deviceAddress?.takeIf { isMacAddress(it) }
                        ?: ""
                    if (id != null) onFound(id, record["name"] ?: device.deviceName ?: id, mac)
                }
            )
            m.clearServiceRequests(c, listener())
            m.addServiceRequest(
                c, WifiP2pDnsSdServiceRequest.newInstance(),
                listener(
                    onOk = {
                        try { m.discoverServices(c, listener(onFail = { onError(reasonText(it)) })) }
                        catch (_: SecurityException) { onError("Missing Wi-Fi Direct permission.") }
                    },
                    onFail = { onError(reasonText(it)) }
                )
            )
        } catch (e: SecurityException) { onError("Missing Wi-Fi Direct permission.") }
    }

    /** Re-issues discovery (Wi-Fi P2P discovery stops by itself after about two minutes). */
    fun refreshDiscovery() {
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.discoverPeers(c, listener())
            m.discoverServices(c, listener())
        } catch (_: SecurityException) {}
    }

    fun stopDiscovery() {
        for (rx in discoveryReceivers) unregisterQuietly(rx)
        discoveryReceivers.clear()
        val m = manager ?: return
        val c = channel ?: return
        try {
            m.stopPeerDiscovery(c, listener())
            m.clearServiceRequests(c, listener())
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ sockets

    private suspend fun awaitGroup(timeoutMs: Long = CONNECT_TIMEOUT_MS): WifiP2pInfo {
        val m = manager ?: throw IOException("Wi-Fi Direct is not supported.")
        val c = channel()
        val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE
        var lastDiscoveryRefresh = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val info = suspendCancellableCoroutine<WifiP2pInfo?> { cont ->
                try {
                    m.requestConnectionInfo(c) { i ->
                        if (cont.isActive) cont.resume(i)
                    }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            if (info != null && info.groupFormed) {
                if (info.isGroupOwner || info.groupOwnerAddress != null) {
                    return info
                }
            }
            // Periodically refresh discovery if waiting indefinitely on the receive side
            if (timeoutMs == 0L && System.currentTimeMillis() - lastDiscoveryRefresh > 30_000L) {
                lastDiscoveryRefresh = System.currentTimeMillis()
                try { m.discoverPeers(c, null) } catch (_: Exception) {}
            }
            delay(500)
        }
        throw IOException("The other phone did not accept the Wi-Fi Direct invitation in time.")
    }

    private suspend fun openSocket(info: WifiP2pInfo): Link {
        val m = manager
        val c = channel()
        if (info.isGroupOwner) {
            var server: ServerSocket? = null
            var lastEx: Exception? = null
            for (port in listOf(PORT, 48989, 48990, 48991)) {
                try {
                    val s = ServerSocket()
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(port))
                    server = s
                    break
                } catch (e: Exception) {
                    lastEx = e
                    delay(300)
                }
            }
            val boundServer = server ?: throw IOException("Could not open data server (${lastEx?.message}). Try toggling Wi-Fi off and on.")
            val sock = blockingCancellable({ boundServer.close() }) {
                boundServer.soTimeout = 0
                boundServer.accept()
            }
            try { boundServer.close() } catch (_: Exception) {}
            return socketLink(sock, initiator = false)
        }

        val deadline = System.currentTimeMillis() + CLIENT_RETRY_MS
        var host = info.groupOwnerAddress?.hostAddress
        while ((host == null || host == "0.0.0.0") && System.currentTimeMillis() < deadline) {
            delay(500)
            host = suspendCancellableCoroutine<String?> { cont ->
                try {
                    m?.requestConnectionInfo(c) { i ->
                        if (cont.isActive) cont.resume(i?.groupOwnerAddress?.hostAddress)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        val targetHost = host?.takeIf { it.isNotBlank() && it != "0.0.0.0" } ?: "192.168.49.1"

        while (System.currentTimeMillis() < deadline) {
            for (port in listOf(PORT, 48989, 48990, 48991)) {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(targetHost, port), 3000)
                    return socketLink(s, initiator = true)
                } catch (_: IOException) {
                    try { s.close() } catch (_: Exception) {}
                }
            }
            delay(500)
        }
        throw IOException("Connected to Wi-Fi Direct group, but could not open data socket. Ensure receiver phone stays on Receive screen.")
    }

    private fun socketLink(s: Socket, initiator: Boolean): Link {
        s.tcpNoDelay = true
        s.soTimeout = 30_000
        return Link(s.getInputStream(), s.getOutputStream(), initiator,
            s.inetAddress?.hostAddress ?: "peer", { ms -> s.soTimeout = ms }, { s.close() })
    }

    suspend fun shutdown() {
        stopDiscovery()
        try { stopReceiving() } catch (_: Exception) {}
        val c = channel
        channel = null
        if (c != null && Build.VERSION.SDK_INT >= 27) try { c.close() } catch (_: Exception) {}
    }

    companion object {
        const val PORT = 48988
        const val SERVICE_TYPE = "_dropsecure._tcp"
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val ACCEPT_TIMEOUT_MS = 60_000
        private const val CLIENT_RETRY_MS = 45_000L
    }
}
