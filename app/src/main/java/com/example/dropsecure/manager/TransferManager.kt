package com.example.dropsecure.manager

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import com.example.dropsecure.core.FileChunkSink
import com.example.dropsecure.core.FileReceiver
import com.example.dropsecure.core.FileSender
import com.example.dropsecure.core.Handshake
import com.example.dropsecure.core.ProgressListener
import com.example.dropsecure.core.Session
import com.example.dropsecure.core.TransferConfig
import com.example.dropsecure.core.TransferOffer
import com.example.dropsecure.core.TransferResult
import com.example.dropsecure.core.TransferStatus
import com.example.dropsecure.core.randomTransferId
import com.example.dropsecure.core.toHex
import com.example.dropsecure.data.TransferDao
import com.example.dropsecure.data.TransferEntity
import com.example.dropsecure.data.RoomTransferStateStore
import com.example.dropsecure.discovery.BleAdvertiser
import com.example.dropsecure.discovery.toHex as bleHex
import com.example.dropsecure.io.FileSaver
import com.example.dropsecure.io.UriChunkSource
import com.example.dropsecure.settings.AppSettings
import com.example.dropsecure.transport.BluetoothTransport
import com.example.dropsecure.transport.Link
import java.io.IOException
import com.example.dropsecure.transport.Peer
import com.example.dropsecure.transport.TransportType
import com.example.dropsecure.transport.WifiDirectTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Orchestrates every transfer: connect -> key exchange -> user verifies code -> (receiver approves) ->
 * encrypted chunked transfer -> verification -> history.
 *
 * Limits (resource exhaustion): Wi-Fi Direct sends run ONE at a time (a phone normally holds one
 * Wi-Fi P2P group), Bluetooth sends at most 3 at a time, and the receive side handles one incoming
 * transfer at a time. Every receiver gets its own handshake and therefore its own session keys.
 */
class TransferManager(
    private val app: Context,
    private val dao: TransferDao,
    private val settings: AppSettings,
    private val saver: FileSaver
) {
    private class Live(val id: String, val direction: Direction, val transport: TransportType) {
        @Volatile var cancelled = false
        @Volatile var stage: Stage = Stage.QUEUED
        @Volatile var codeFuture: CompletableFuture<Boolean>? = null
        @Volatile var offerFuture: CompletableFuture<Boolean>? = null
        @Volatile var link: Link? = null
        @Volatile var session: Session? = null
        @Volatile var job: Job? = null
    }

    private class Progress(val total: Long) {
        var startBytes = -1L
        var startMark = 0L
        var lastT = 0L
        var lastB = 0L
        var ema = 0.0
        var lastUi = 0L
        var lastDb = 0L
        var done = 0L
    }

    private class Outcome(val stage: Stage, val status: TransferStatus, val message: String?, val integrity: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = RoomTransferStateStore(dao)
    val wifi = WifiDirectTransport(app)
    val bluetooth = BluetoothTransport(app)
    private val advertiser = BleAdvertiser(app)
    private val wifiGate = Semaphore(1)
    private val btGate = Semaphore(3)
    private val live = ConcurrentHashMap<String, Live>()
    private val incomingDir = File(app.filesDir, "incoming").apply { mkdirs() }

    private val _jobs = MutableStateFlow<List<JobUi>>(emptyList())
    val jobs: StateFlow<List<JobUi>> = _jobs.asStateFlow()
    private val _receiving = MutableStateFlow(ReceiveStatus())
    val receiving: StateFlow<ReceiveStatus> = _receiving.asStateFlow()
    private var receiveJob: Job? = null

    val history: Flow<List<TransferEntity>> = dao.observeAll()

    fun onAppStart() {
        scope.launch { dao.markStaleInterrupted() }
    }

    // ================================================================== public API

    /** Starts one independent transfer (own connection, own keys) per selected peer. */
    fun startSend(uri: Uri, peers: List<Peer>, transport: TransportType, resumeTransferId: String? = null): List<String> {
        TransferService.start(app)
        val ids = ArrayList<String>()
        for ((i, p) in peers.withIndex()) {
            val id = UUID.randomUUID().toString()
            val l = Live(id, Direction.SEND, transport)
            live[id] = l
            _jobs.update { it + JobUi(id, Direction.SEND, transport, p.name, Stage.QUEUED, message = "Waiting in queue") }
            val resume = if (peers.size == 1 && i == 0) resumeTransferId else null
            l.job = scope.launch { runSend(l, p, uri, resume) }
            ids.add(id)
        }
        return ids
    }

    fun startReceiving(transport: TransportType) {
        TransferService.start(app)
        if (receiveJob?.isActive == true) {
            if (_receiving.value.transport == transport && _receiving.value.active) return
            receiveJob?.cancel()
        }
        receiveJob = scope.launch { receiveLoop(transport) }
    }

    fun stopReceiving() {
        for (l in live.values) if (l.direction == Direction.RECEIVE) cancel(l.id)
        receiveJob?.cancel()
        TransferService.stop(app)
    }

    fun confirmCode(jobId: String, matches: Boolean) {
        live[jobId]?.codeFuture?.complete(matches)
        mutate(jobId) { it.copy(codeConfirmed = true) }
    }

    fun respondToOffer(jobId: String, accept: Boolean) {
        live[jobId]?.offerFuture?.complete(accept)
    }

    fun cancel(jobId: String) {
        val l = live[jobId] ?: return
        l.cancelled = true
        l.codeFuture?.complete(false)
        l.offerFuture?.complete(false)
        val early = l.stage == Stage.QUEUED || l.stage == Stage.CONNECTING || l.stage == Stage.KEY_EXCHANGE || l.stage == Stage.VERIFY_CODE
        if (early) {
            if (l.direction == Direction.SEND) l.job?.cancel()
            forceClose(l)
        } else {
            // Graceful: the transfer loop notices the flag between chunks. Force-close if it is blocked.
            scope.launch { delay(1500); if (live.containsKey(jobId)) forceClose(l) }
        }
    }

    fun dismiss(jobId: String) {
        _jobs.update { list -> list.filter { it.jobId != jobId || !it.stage.finished } }
    }

    fun deleteRecord(transferId: String) {
        scope.launch {
            dao.get(transferId)?.tempPath?.let { File(it).delete() }
            dao.delete(transferId)
        }
    }

    fun clearFinishedRecords() { scope.launch { dao.clearFinished() } }

    suspend fun exportCsv(target: Uri): Int = withContext(Dispatchers.IO) {
        val rows = dao.getAll()
        val sb = StringBuilder()
        sb.append("transferId,direction,transport,peer,fileName,sizeBytes,status,startedAt,durationMs,")
        sb.append("bytesThisSession,throughputMBps,discoveryMs,connectMs,resumeCount,integrityFailure,")
        sb.append("batteryStartPct,batteryEndPct,error\n")
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        for (r in rows) {
            sb.append(listOf(
                q(r.transferId), r.direction, r.transport, q(r.peerName), q(r.fileName), r.size, r.status,
                r.startedAtMs, r.durationMs, r.bytesThisSession, String.format(java.util.Locale.US, "%.4f", r.throughputMBps),
                r.discoveryMs, r.connectMs, r.resumeCount, r.integrityFailure, r.batteryStartPct, r.batteryEndPct, q(r.error)
            ).joinToString(",")).append('\n')
        }
        app.contentResolver.openOutputStream(target)?.use { it.write(sb.toString().toByteArray()) }
        rows.size
    }

    // ================================================================== sender

    private suspend fun runSend(l: Live, peer: Peer, uri: Uri, resumeId: String?) {
        val transport = l.transport
        val gate = if (transport == TransportType.WIFI_DIRECT) wifiGate else btGate
        var link: Link? = null
        var session: Session? = null
        var source: UriChunkSource? = null
        var idHex: String? = null
        var connectMs = -1L
        var result: TransferResult? = null
        var failure: String? = null
        var codeRejected = false
        var stats: Pair<Long, Long>? = null           // (durationMs, bytesThisSession)
        val progress = Progress(0)
        val batteryStart = battery()

        try {
            gate.withPermit {
                setStage(l, Stage.CONNECTING, "Preparing file...")
                val src = withContext(Dispatchers.IO) { UriChunkSource(app, uri) }
                source = src
                val id = resumeId?.let { hexToBytes(it) } ?: randomTransferId()
                idHex = id.toHex()
                val old = dao.get(idHex!!)
                withContext(Dispatchers.IO) {
                    dao.upsert(
                        (old ?: newEntity(idHex!!, Direction.SEND, transport, peer.name, src.name, src.mimeType, src.size)).copy(
                            peerName = peer.name, transport = transport.name, sourceUri = uri.toString(),
                            status = TransferStatus.IN_PROGRESS.name, updatedAtMs = now(), discoveryMs = peer.discoveryMs,
                            resumeCount = (old?.resumeCount ?: 0) + if (resumeId != null && old != null) 1 else 0,
                            batteryStartPct = batteryStart, error = null
                        )
                    )
                }
                mutate(l.id) { it.copy(fileName = src.name, mimeType = src.mimeType, totalBytes = src.size, transferId = idHex, resuming = old != null && resumeId != null) }
                setStage(l, Stage.CONNECTING, "Connecting to ${peer.name}...")

                val t0 = SystemClock.elapsedRealtime()
                val lk = if (transport == TransportType.WIFI_DIRECT) {
                    setStage(l, Stage.CONNECTING, "Connecting to ${peer.name} over Wi-Fi Direct...")
                    try {
                        wifi.connect(peer.address, peer.bleId)
                    } catch (e: Exception) {
                        throw IOException("Could not connect to ${peer.name} (${e.message}). Make sure ${peer.name} is on the Receive screen and taps ACCEPT on the Wi-Fi Direct invitation prompt.")
                    }
                } else {
                    if (peer.address.isBlank() || peer.address == "02:00:00:00:00:00" || peer.address.startsWith("DS-")) {
                        throw IOException("Bluetooth Classic address for ${peer.name} is not resolved yet. Make sure ${peer.name} is on the Receive screen and tapped 'Make discoverable', or pair both phones in Bluetooth Settings.")
                    }
                    bluetooth.connect(peer.address)
                }
                link = lk; l.link = lk
                connectMs = SystemClock.elapsedRealtime() - t0

                setStage(l, Stage.KEY_EXCHANGE, "Exchanging keys...")
                lk.setTimeout(30_000)
                val s = withContext(Dispatchers.IO) {
                    if (lk.isInitiator) Handshake.initiate(lk.input, lk.output) else Handshake.respond(lk.input, lk.output)
                }
                session = s; l.session = s
                mutate(l.id) { it.copy(sas = s.sas) }
                setStage(l, Stage.VERIFY_CODE, "Compare this code with the other phone")
                val ok = awaitUser(120) { l.codeFuture = it }
                if (!ok) { codeRejected = true; return@withPermit }

                lk.setTimeout(150_000)
                setStage(l, Stage.AWAITING_ACCEPT, "Waiting for ${peer.name} to accept...")
                val tracker = Progress(src.size)
                result = withContext(Dispatchers.IO) {
                    FileSender(s, configFor(transport), id).send(
                        src, progressListener(l, tracker, saveToDb = true, entityId = idHex), { l.cancelled }
                    )
                }
                progress.done = tracker.done
                if (tracker.startBytes >= 0) stats = (SystemClock.elapsedRealtime() - tracker.startMark) to 0L
                progress.startMark = tracker.startMark; progress.startBytes = tracker.startBytes
            }
        } catch (e: CancellationException) {
            l.cancelled = true
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val outcome = when {
                    l.cancelled -> Outcome(Stage.CANCELLED, TransferStatus.CANCELLED, "Cancelled", false)
                    codeRejected -> Outcome(Stage.CANCELLED, TransferStatus.CANCELLED, "The verification code was not confirmed.", false)
                    result != null -> outcomeOf(result, Direction.SEND)
                    else -> Outcome(Stage.FAILED, TransferStatus.FAILED, failure ?: "Transfer failed", false)
                }
                val r = result
                val bytesThisSession = (r as? TransferResult.Success)?.stats?.bytesThisSession ?: 0L
                val durationMs = stats?.first ?: 0L
                val mbps = if (durationMs > 0 && bytesThisSession > 0) (bytesThisSession / 1_048_576.0) / (durationMs / 1000.0) else 0.0
                idHex?.let { id ->
                    dao.get(id)?.let { e ->
                        dao.upsert(
                            e.copy(
                                status = outcome.status.name,
                                bytesDone = if (outcome.status == TransferStatus.COMPLETED) e.size else maxOf(e.bytesDone, progress.done),
                                durationMs = durationMs, bytesThisSession = bytesThisSession, throughputMBps = mbps,
                                connectMs = connectMs, integrityFailure = e.integrityFailure || outcome.integrity,
                                batteryEndPct = battery(), error = outcome.message, updatedAtMs = now()
                            )
                        )
                    }
                }
                mutate(l.id) {
                    it.copy(stage = outcome.stage, message = outcome.message, speedBps = 0.0, etaSeconds = null,
                        durationMs = durationMs.takeIf { d -> d > 0 }, throughputMBps = mbps.takeIf { v -> v > 0 },
                        bytesDone = if (outcome.stage == Stage.DONE) it.totalBytes else it.bytesDone)
                }
                try { session?.close() } catch (_: Exception) {}
                try { link?.close() } catch (_: Exception) {}
                try { source?.close() } catch (_: Exception) {}
                if (transport == TransportType.WIFI_DIRECT) { try { wifi.resetGroup() } catch (_: Exception) {} }
                live.remove(l.id)
            }
        }
    }

    // ================================================================== receiver

    private suspend fun receiveLoop(transport: TransportType) {
        val name = settings.deviceName.value
        var listener: BluetoothTransport.BtListener? = null
        val idBytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val localId = idBytes.bleHex()
        val btFallbackListener = try { bluetooth.listen() } catch (_: Exception) { null }
        try {
            advertiser.start(idBytes, {}, { msg -> setReceiveMessage("$msg (BLE is optional)") })
            if (transport == TransportType.WIFI_DIRECT) {
                wifi.startReceiving(localId, name)
            } else {
                listener = btFallbackListener
            }
            _receiving.value = ReceiveStatus(true, transport, name, localId, "Waiting for a sender...")
            while (currentCoroutineContext().isActive) {
                val link = try {
                    if (transport == TransportType.WIFI_DIRECT) {
                        wifi.awaitIncoming()
                    } else listener!!.accept()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setReceiveMessage("Waiting for sender: ${e.message ?: "Retrying..."}")
                    delay(1500)
                    if (transport == TransportType.WIFI_DIRECT) { try { wifi.startReceiving(localId, name) } catch (_: Exception) {} }
                    continue
                }
                setReceiveMessage("Connected. Securing the connection...")
                handleIncoming(link, transport)
                if (transport == TransportType.WIFI_DIRECT) {
                    try { wifi.startReceiving(localId, name) } catch (e: Exception) { setReceiveMessage("Could not become visible again: ${e.message}") }
                }
                setReceiveMessage("Waiting for a sender...")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _receiving.value = ReceiveStatus(active = false, transport = transport, message = e.message ?: "Receiving stopped")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                advertiser.stop()
                listener?.close()
                if (transport == TransportType.WIFI_DIRECT) { try { wifi.stopReceiving() } catch (_: Exception) {} }
                if (_receiving.value.active) {
                    _receiving.value = ReceiveStatus()
                }
            }
        }
    }

    private suspend fun handleIncoming(link: Link, transport: TransportType) {
        val id = UUID.randomUUID().toString()
        val l = Live(id, Direction.RECEIVE, transport)
        l.link = link
        live[id] = l
        _jobs.update { it + JobUi(id, Direction.RECEIVE, transport, link.remoteName, Stage.KEY_EXCHANGE, message = "Exchanging keys...") }
        l.stage = Stage.KEY_EXCHANGE

        var session: Session? = null
        var idHex: String? = null
        var failure: String? = null
        var codeRejected = false
        var result: TransferResult? = null
        var savedTo: String? = null
        var peerName = link.remoteName
        var tracked: Progress? = null
        val batteryStart = battery()

        try {
            link.setTimeout(30_000)
            val s = withContext(Dispatchers.IO) {
                if (link.isInitiator) Handshake.initiate(link.input, link.output) else Handshake.respond(link.input, link.output)
            }
            session = s; l.session = s
            mutate(id) { it.copy(sas = s.sas) }
            setStage(l, Stage.VERIFY_CODE, "Compare this code with the other phone")
            val ok = awaitUser(120) { l.codeFuture = it }
            if (!ok) { codeRejected = true } else {
                link.setTimeout(150_000)
                setStage(l, Stage.AWAITING_ACCEPT, "Waiting for the sender...")
                val receiver = FileReceiver(s, TransferConfig(), store) { offer ->
                    FileChunkSink(File(incomingDir, offer.idHex + ".part"))
                }
                val prog = Progress(0)
                tracked = prog
                result = withContext(Dispatchers.IO) {
                    receiver.receive(
                        onOffer = { offer, resuming ->
                            idHex = offer.idHex
                            decideOffer(l, offer, resuming, transport, peerName)
                        },
                        listener = ProgressListener { done, total ->
                            reportProgress(l, prog, done, total, saveToDb = false, entityId = null)
                        },
                        isCancelled = { l.cancelled }
                    )
                }
                val r = result
                if (r is TransferResult.Success && r.sink != null) {
                    try {
                        savedTo = withContext(Dispatchers.IO) { saver.save(r.sink, r.offer!!.fileName, r.offer.mimeType.ifBlank { "application/octet-stream" }, r.offer.size) }
                    } catch (e: Exception) {
                        failure = "Verified, but could not save the file: ${e.message}"
                        result = null
                    } finally {
                        try { r.sink.delete() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: CancellationException) {
            l.cancelled = true
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val outcome = when {
                    l.cancelled -> Outcome(Stage.CANCELLED, TransferStatus.CANCELLED, "Cancelled", false)
                    codeRejected -> Outcome(Stage.CANCELLED, TransferStatus.CANCELLED, "The verification code was not confirmed.", false)
                    result != null -> outcomeOf(result, Direction.RECEIVE)
                    else -> Outcome(Stage.FAILED, TransferStatus.FAILED, failure ?: "Transfer failed", false)
                }
                val p = tracked
                val durationMs = if (p != null && p.startBytes >= 0) SystemClock.elapsedRealtime() - p.startMark else 0L
                val bytesThisSession = (result as? TransferResult.Success)?.stats?.bytesThisSession ?: 0L
                val mbps = if (durationMs > 0 && bytesThisSession > 0) (bytesThisSession / 1_048_576.0) / (durationMs / 1000.0) else 0.0
                idHex?.let { tid ->
                    dao.get(tid)?.let { e ->
                        // Keep the status the protocol layer stored for resumable failures; override otherwise.
                        val keepCoreStatus = (result is TransferResult.Interrupted || result is TransferResult.Failed) &&
                            outcome.stage != Stage.CANCELLED && e.status == TransferStatus.INTERRUPTED.name
                        val finalStatus = if (keepCoreStatus || (result is TransferResult.Failed && e.status == TransferStatus.FAILED.name)) e.status else outcome.status.name
                        if (outcome.status == TransferStatus.CANCELLED || outcome.status == TransferStatus.FAILED && failure != null) {
                            e.tempPath?.let { File(it).delete() }
                        }
                        dao.upsert(
                            e.copy(
                                status = finalStatus, savedTo = savedTo ?: e.savedTo,
                                bytesDone = if (outcome.status == TransferStatus.COMPLETED) e.size else e.bytesDone,
                                durationMs = durationMs, bytesThisSession = bytesThisSession, throughputMBps = mbps,
                                integrityFailure = e.integrityFailure || outcome.integrity,
                                batteryStartPct = batteryStart, batteryEndPct = battery(),
                                error = outcome.message, updatedAtMs = now()
                            )
                        )
                    }
                }
                mutate(id) {
                    it.copy(stage = outcome.stage, message = outcome.message, savedTo = savedTo, speedBps = 0.0, etaSeconds = null, offerPending = false,
                        durationMs = durationMs.takeIf { d -> d > 0 }, throughputMBps = mbps.takeIf { v -> v > 0 },
                        bytesDone = if (outcome.stage == Stage.DONE) it.totalBytes else it.bytesDone)
                }
                try { session?.close() } catch (_: Exception) {}
                try { link.close() } catch (_: Exception) {}
                live.remove(id)
            }
        }
    }

    /** Runs on the receive thread; blocks until the user accepts or rejects. */
    private fun decideOffer(l: Live, offer: TransferOffer, resuming: Boolean, transport: TransportType, peerName: String): Boolean {
        mutate(l.id) {
            it.copy(fileName = offer.fileName, mimeType = offer.mimeType, totalBytes = offer.size, transferId = offer.idHex,
                resuming = resuming, offerPending = true, message = null)
        }
        l.stage = Stage.AWAITING_ACCEPT
        val needed = offer.size + 16L * 1024 * 1024
        val free = incomingDir.usableSpace
        if (free < needed) {
            mutate(l.id) { it.copy(offerPending = false, message = "Not enough free storage for this file.") }
            recordRejected(offer, transport, peerName, "Not enough free storage")
            return false
        }
        val fut = CompletableFuture<Boolean>()
        l.offerFuture = fut
        val accepted = try { fut.get(180, TimeUnit.SECONDS) } catch (e: TimeoutException) { false }
        mutate(l.id) { it.copy(offerPending = false) }
        if (!accepted) {
            recordRejected(offer, transport, peerName, "You declined the file")
            return false
        }
        val old = dao.get(offer.idHex)
        val base = old ?: newEntity(offer.idHex, Direction.RECEIVE, transport, peerName, offer.fileName, offer.mimeType, offer.size)
        dao.upsert(
            base.copy(
                peerName = peerName, transport = transport.name, chunkSize = offer.chunkSize, sha256Hex = offer.sha256.toHex(),
                tempPath = File(incomingDir, offer.idHex + ".part").absolutePath,
                status = TransferStatus.IN_PROGRESS.name, updatedAtMs = now(),
                resumeCount = (old?.resumeCount ?: 0) + if (resuming) 1 else 0, error = null
            )
        )
        l.stage = Stage.TRANSFERRING
        return true
    }

    private fun recordRejected(offer: TransferOffer, transport: TransportType, peerName: String, why: String) {
        if (dao.get(offer.idHex) != null) return   // keep existing partial-transfer record untouched
        dao.upsert(
            newEntity(offer.idHex, Direction.RECEIVE, transport, peerName, offer.fileName, offer.mimeType, offer.size)
                .copy(status = TransferStatus.REJECTED.name, error = why, chunkSize = offer.chunkSize, sha256Hex = offer.sha256.toHex())
        )
    }

    // ================================================================== helpers

    private fun configFor(t: TransportType) =
        if (t == TransportType.BLUETOOTH) TransferConfig(chunkSize = 64 * 1024) else TransferConfig()

    private fun outcomeOf(r: TransferResult?, dir: Direction): Outcome = when (r) {
        is TransferResult.Success -> Outcome(Stage.DONE, TransferStatus.COMPLETED, null, false)
        is TransferResult.Rejected -> Outcome(Stage.REJECTED, TransferStatus.REJECTED, "The file was declined.", false)
        is TransferResult.Cancelled -> Outcome(Stage.CANCELLED, TransferStatus.CANCELLED, if (r.byPeer) "The other phone cancelled the transfer." else "Cancelled", false)
        is TransferResult.Interrupted -> Outcome(Stage.INTERRUPTED, TransferStatus.INTERRUPTED, "Connection lost. You can resume this transfer.", false)
        is TransferResult.Failed -> {
            val integrity = r.reason.contains("SHA-256") || r.reason.contains("integrity") || r.reason.contains("authentication")
            Outcome(Stage.FAILED, if (dir == Direction.SEND) TransferStatus.FAILED else TransferStatus.INTERRUPTED, r.reason, integrity)
        }
        null -> Outcome(Stage.FAILED, TransferStatus.FAILED, "Transfer failed", false)
    }

    private fun progressListener(l: Live, p: Progress, saveToDb: Boolean, entityId: String?) =
        ProgressListener { done, total -> reportProgress(l, p, done, total, saveToDb, entityId) }

    private fun reportProgress(l: Live, p: Progress, done: Long, total: Long, saveToDb: Boolean, entityId: String?) {
        val t = SystemClock.elapsedRealtime()
        p.done = done
        if (p.startBytes < 0) {
            p.startBytes = done; p.startMark = t; p.lastT = t; p.lastB = done; p.lastUi = t; p.lastDb = t
            l.stage = Stage.TRANSFERRING
            mutate(l.id) { it.copy(stage = Stage.TRANSFERRING, totalBytes = total, bytesDone = done, message = null) }
            return
        }
        if (t - p.lastT >= 500) {
            val inst = (done - p.lastB) * 1000.0 / (t - p.lastT)
            p.ema = if (p.ema == 0.0) inst else 0.7 * p.ema + 0.3 * inst
            p.lastT = t; p.lastB = done
        }
        if (t - p.lastUi >= 250 || done >= total) {
            p.lastUi = t
            val eta = if (t - p.startMark > 3000 && p.ema > 1024) ((total - done) / p.ema).toLong() else null
            mutate(l.id) { it.copy(bytesDone = done, totalBytes = total, speedBps = p.ema, etaSeconds = eta) }
        }
        if (saveToDb && entityId != null && t - p.lastDb >= 1000) {
            p.lastDb = t
            try { dao.updateProgress(entityId, done, now()) } catch (_: Exception) {}
        }
    }

    private fun setStage(l: Live, s: Stage, msg: String?) {
        l.stage = s
        mutate(l.id) { it.copy(stage = s, message = msg) }
    }

    private fun mutate(id: String, f: (JobUi) -> JobUi) {
        _jobs.update { list -> list.map { if (it.jobId == id) f(it) else it } }
    }

    private fun setReceiveMessage(m: String) { _receiving.update { it.copy(message = m) } }

    private suspend fun awaitUser(timeoutSec: Long, register: (CompletableFuture<Boolean>) -> Unit): Boolean {
        val f = CompletableFuture<Boolean>()
        register(f)
        return withContext(Dispatchers.IO) { try { f.get(timeoutSec, TimeUnit.SECONDS) } catch (e: TimeoutException) { false } }
    }

    private fun forceClose(l: Live) {
        try { l.session?.close() } catch (_: Exception) {}
        try { l.link?.close() } catch (_: Exception) {}
    }

    private fun now() = System.currentTimeMillis()

    private fun battery(): Int = try {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level * 100 / scale else -1
    } catch (_: Exception) { -1 }

    private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun newEntity(id: String, dir: Direction, t: TransportType, peer: String, name: String, mime: String, size: Long) = TransferEntity(
        transferId = id, direction = dir.name, transport = t.name, peerName = peer, fileName = name, mimeType = mime,
        size = size, chunkSize = 0, sha256Hex = "", receivedBitmap = ByteArray(0), bytesDone = 0,
        status = TransferStatus.IN_PROGRESS.name, sourceUri = null, tempPath = null, savedTo = null,
        startedAtMs = now(), updatedAtMs = now(), durationMs = 0, bytesThisSession = 0, throughputMBps = 0.0,
        discoveryMs = -1, connectMs = -1, resumeCount = 0, integrityFailure = false,
        batteryStartPct = -1, batteryEndPct = -1, error = null
    )
}
