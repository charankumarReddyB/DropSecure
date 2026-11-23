package com.example.dropsecure.discovery

import android.content.Context
import android.os.SystemClock
import com.example.dropsecure.transport.BluetoothTransport
import com.example.dropsecure.transport.Peer
import com.example.dropsecure.transport.TransportType
import com.example.dropsecure.transport.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One row in the "nearby devices" list. */
data class NearbyPeer(
    val key: String,
    val name: String,
    val transport: TransportType,
    val address: String?,            // null until the Wi-Fi P2P or Bluetooth Classic address is known
    val bleId: String? = null,
    val rssi: Int? = null,
    val verifiedDropSecure: Boolean,
    val discoveryMs: Long
) {
    val sendable: Boolean get() = if (transport == TransportType.BLUETOOTH) {
        !address.isNullOrBlank() && address != "02:00:00:00:00:00"
    } else {
        (!address.isNullOrBlank() && address != "02:00:00:00:00:00") || !bleId.isNullOrBlank()
    }
    fun toPeer(): Peer = Peer(name, transport, address ?: bleId.orEmpty(), discoveryMs, bleId = bleId)
}

data class DiscoveryStatus(val scanning: Boolean = false, val message: String? = null)

/**
 * Finds nearby receivers.
 *
 * Wi-Fi Direct mode: BLE scanning shows DropSecure phones that are physically close (by random ID);
 * Wi-Fi P2P service discovery maps that ID to a name and a Wi-Fi Direct address. Wi-Fi P2P peers that
 * are not matched to a DropSecure ID are listed as "unverified" so the user can still choose them.
 *
 * Bluetooth mode: classic Bluetooth discovery (+ already paired devices).
 */
class DiscoveryController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val wifi: WifiDirectTransport,
    private val bt: BluetoothTransport,
    private val transport: TransportType
) {
    private class BleInfo(val address: String, var rssi: Int, var lastSeen: Long, val firstSeen: Long, val deviceName: String? = null, val classicAddress: String? = null)
    private class P2pInfo(val name: String, val address: String, var lastSeen: Long, val firstSeen: Long)
    private class BtInfo(val name: String?, val address: String, var rssi: Int?, val firstSeen: Long)

    private val lock = Any()
    private val ble = HashMap<String, BleInfo>()
    private val p2p = HashMap<String, P2pInfo>()
    private val rawP2p = HashMap<String, Pair<String, Long>>()    // address -> (name, firstSeen)
    private val classic = HashMap<String, BtInfo>()
    private val scanner = BleScanner(ctx)
    private var startedAt = 0L
    private var ticker: Job? = null

    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()
    private val _status = MutableStateFlow(DiscoveryStatus())
    val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    fun start() {
        stop()
        synchronized(lock) { ble.clear(); p2p.clear(); rawP2p.clear(); classic.clear() }
        startedAt = SystemClock.elapsedRealtime()
        _status.value = DiscoveryStatus(true, null)
        if (transport == TransportType.WIFI_DIRECT) startWifi() else startBluetooth()
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(3_000)
                publish()
                n++
                if (transport == TransportType.WIFI_DIRECT) {
                    if (n % 6 == 0) wifi.refreshDiscovery()
                } else {
                    for (p in bt.pairedPeers()) {
                        synchronized(lock) { if (!classic.containsKey(p.address)) classic[p.address] = BtInfo(p.name, p.address, null, 0) }
                    }
                    if (n % 5 == 0) {
                        bt.startDiscovery(
                            onPeer = { name, address, rssi ->
                                synchronized(lock) {
                                    val e = classic[address]
                                    if (e == null) classic[address] = BtInfo(name, address, rssi, elapsed())
                                    else {
                                        if (name != null) classic[address] = BtInfo(name, address, rssi ?: e.rssi, e.firstSeen)
                                        else e.rssi = rssi
                                    }
                                }
                                publish()
                            },
                            onDone = { publish() }
                        )
                    }
                }
            }
        }
    }

    fun stop(stopWifi: Boolean = true) {
        ticker?.cancel(); ticker = null
        scanner.stop()
        if (stopWifi) wifi.stopDiscovery()
        bt.stopDiscovery()
        _status.value = _status.value.copy(scanning = false)
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt
    private fun setMessage(m: String) { _status.value = _status.value.copy(message = m) }

    // ---------------------------------------------------------------- Wi-Fi Direct (+BLE)

    private fun startWifi() {
        if (!wifi.isSupported) { setMessage("This phone does not support Wi-Fi Direct."); return }
        if (!wifi.isWifiOn()) { setMessage("Turn Wi-Fi on to use Wi-Fi Direct."); return }
        scanner.start(
            onDevice = { id, address, rssi, devName, classicAddress ->
                val now = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    val e = ble[id]
                    if (e == null) ble[id] = BleInfo(address, rssi, now, elapsed(), devName, classicAddress) else { e.rssi = rssi; e.lastSeen = now }
                }
                publish()
            },
            onError = { setMessage("$it (BLE is optional; Wi-Fi Direct discovery continues)") }
        )
        wifi.startServiceDiscovery(
            onFound = { id, name, address ->
                val now = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    val e = p2p[id]
                    if (e == null) p2p[id] = P2pInfo(name, address, now, elapsed()) else e.lastSeen = now
                }
                publish()
            },
            onError = { setMessage(it) }
        )
        wifi.startPeerDiscovery(
            onPeers = { list ->
                synchronized(lock) {
                    for ((addr, name) in list) {
                        rawP2p[addr] = name to elapsed()
                    }
                }
                publish()
            },
            onError = { setMessage(it) }
        )
    }

    // ---------------------------------------------------------------- Bluetooth

    private fun startBluetooth() {
        if (!bt.isSupported) { setMessage("This phone has no Bluetooth."); return }
        if (!bt.isEnabled()) { setMessage("Turn Bluetooth on first."); return }
        scanner.start(
            onDevice = { id, address, rssi, devName, classicAddress ->
                val now = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    val e = ble[id]
                    if (e == null) ble[id] = BleInfo(address, rssi, now, elapsed(), devName, classicAddress) else { e.rssi = rssi; e.lastSeen = now }
                }
                publish()
            },
            onError = { /* BLE scan error fallback */ }
        )
        val ok = bt.startDiscovery(
            onPeer = { name, address, rssi ->
                synchronized(lock) {
                    val e = classic[address]
                    if (e == null) classic[address] = BtInfo(name, address, rssi, elapsed())
                    else {
                        if (name != null) classic[address] = BtInfo(name, address, rssi ?: e.rssi, e.firstSeen)
                        else e.rssi = rssi
                    }
                }
                publish()
            },
            onDone = { _status.value = _status.value.copy(scanning = false); publish() }
        )
        if (!ok) { setMessage("Bluetooth scan could not start (check permissions)."); _status.value = _status.value.copy(scanning = false) }
        for (p in bt.pairedPeers()) {
            synchronized(lock) { if (!classic.containsKey(p.address)) classic[p.address] = BtInfo(p.name, p.address, null, 0) }
        }
        publish()
    }

    // ---------------------------------------------------------------- merge for the UI

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val out = ArrayList<NearbyPeer>()
        synchronized(lock) {
            ble.entries.removeAll { now - it.value.lastSeen > BleConstants.STALE_AFTER_MS }
            p2p.entries.removeAll { now - it.value.lastSeen > 45_000 }

            if (transport == TransportType.WIFI_DIRECT) {
                val ids = (ble.keys + p2p.keys).toSortedSet()
                val matchedAddresses = HashSet<String>()
                for (id in ids) {
                    val b = ble[id]; val p = p2p[id]
                    val validP2pAddr = p?.address?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
                    val matchedRaw = rawP2p.entries.firstOrNull {
                        (validP2pAddr != null && it.key == validP2pAddr) ||
                        it.value.first.contains(id, ignoreCase = true) ||
                        (b?.deviceName != null && (it.value.first.equals(b.deviceName, ignoreCase = true) || it.value.first.contains(b.deviceName, ignoreCase = true) || b.deviceName.contains(it.value.first, ignoreCase = true))) ||
                        rawP2p.size == 1
                    }
                    // Wi-Fi Direct connection REQUIRES a REAL Wi-Fi P2P MAC address!
                    val p2pAddress = validP2pAddr ?: matchedRaw?.key ?: if (rawP2p.size == 1) rawP2p.keys.first() else null
                    if (p2pAddress != null && p2pAddress != "02:00:00:00:00:00") matchedAddresses.add(p2pAddress)

                    val rawName = p?.name ?: b?.deviceName ?: matchedRaw?.value?.first
                    val cleanName = if (rawName.isNullOrBlank() || rawName == p2pAddress) "DropSecure phone (DS-$id)" else "$rawName (DS-$id)"
                    out.add(
                        NearbyPeer(
                            key = "id:$id",
                            name = cleanName,
                            transport = transport,
                            address = p2pAddress,
                            bleId = id,
                            rssi = b?.rssi,
                            verifiedDropSecure = true,
                            discoveryMs = p?.firstSeen ?: b?.firstSeen ?: -1
                        )
                    )
                }
                for ((addr, v) in rawP2p) {
                    if (addr in matchedAddresses) continue
                    val cleanName = if (v.first.isBlank() || v.first == addr) "Wi-Fi Direct Phone (${addr.takeLast(5)})" else v.first
                    out.add(
                        NearbyPeer(
                            key = "addr:$addr",
                            name = cleanName,
                            transport = transport,
                            address = addr,
                            bleId = null,
                            rssi = null,
                            verifiedDropSecure = true,
                            discoveryMs = v.second
                        )
                    )
                }
            } else {
                ble.entries.removeAll { now - it.value.lastSeen > BleConstants.STALE_AFTER_MS }
                val addedAddresses = HashSet<String>()
                val firstBleId = ble.keys.firstOrNull()

                for (c in classic.values.sortedBy { it.name ?: it.address }) {
                    addedAddresses.add(c.address)
                    val cName = c.name
                    val matchedBleEntry = ble.entries.firstOrNull { entry ->
                        val bName = entry.value.deviceName
                        entry.value.address == c.address ||
                        (cName != null && bName != null && (
                            cName.equals(bName, ignoreCase = true) ||
                            cName.contains(bName, ignoreCase = true) ||
                            bName.contains(cName, ignoreCase = true)
                        )) ||
                        (cName != null && (cName.contains(entry.key, ignoreCase = true) || cName.contains("DropSecure", ignoreCase = true)))
                    }
                    val matchedBleId = matchedBleEntry?.key

                    val baseName = if (c.name.isNullOrBlank()) "Bluetooth Phone (${c.address.takeLast(5)})" else c.name
                    val displayName = if (matchedBleId != null) "$baseName (DS-$matchedBleId)" else baseName
                    out.add(
                        NearbyPeer(
                            key = "bt:${c.address}",
                            name = displayName,
                            transport = transport,
                            address = c.address,
                            bleId = matchedBleId,
                            rssi = c.rssi ?: matchedBleEntry?.value?.rssi,
                            verifiedDropSecure = matchedBleId != null,
                            discoveryMs = c.firstSeen
                        )
                    )
                }

                for ((id, b) in ble) {
                    val bName = b.deviceName
                    val matchedClassic = classic[b.address] ?: classic.values.firstOrNull { c ->
                        val cName = c.name
                        cName != null && bName != null && (
                            cName.equals(bName, ignoreCase = true) ||
                            cName.contains(bName, ignoreCase = true) ||
                            bName.contains(cName, ignoreCase = true)
                        )
                    }

                    // Priority 1: Classic MAC carried directly inside DropSecure BLE advertisement
                    // Priority 2: Matched Classic peer by name or address
                    val addr = b.classicAddress ?: matchedClassic?.address
                    if (addr != null && addr in addedAddresses) continue
                    if (addr != null) addedAddresses.add(addr)

                    val baseName = matchedClassic?.name ?: b.deviceName ?: "DropSecure Phone"
                    val displayName = "$baseName (DS-$id)"
                    out.add(
                        NearbyPeer(
                            key = "ble_bt:$id",
                            name = displayName,
                            transport = transport,
                            address = addr,
                            bleId = id,
                            rssi = b.rssi,
                            verifiedDropSecure = true,
                            discoveryMs = b.firstSeen
                        )
                    )
                }
            }
        }
        _peers.value = out
    }
}
