package com.example.dropsecure.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random

/**
 * Plain-Kotlin test suite (no framework needed). Run from main() or from the JUnit wrapper.
 * Every test uses real AES-GCM, ECDH, HKDF and real TCP loopback sockets.
 */
object CoreTestSuite {
    private const val MB = 1024 * 1024
    private val cfg = TransferConfig()
    private val tests = ArrayList<Pair<String, () -> Unit>>()

    private fun expect(cond: Boolean, msg: String) { if (!cond) throw AssertionError(msg) }
    private fun test(name: String, body: () -> Unit) { tests.add(name to body) }

    private fun tmpDir(): File = Files.createTempDirectory("dropsecure-test").toFile()

    private fun randomFile(dir: File, name: String, size: Long, seed: Long = 42): File {
        val f = File(dir, name)
        val rnd = Random(seed)
        f.outputStream().buffered().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = size
            while (left > 0) { rnd.nextBytes(buf); val n = minOf(left, buf.size.toLong()).toInt(); out.write(buf, 0, n); left -= n }
        }
        return f
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().toHex()
    }

    private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Runs a full transfer over a (possibly tampered) link. Returns sender and receiver results. */
    private fun transfer(
        tamper: Tamper, src: File, dst: File, store: TransferStateStore,
        id: ByteArray = randomTransferId(),
        source: ChunkSource? = null,
        accept: Boolean = true,
        senderCancel: () -> Boolean = { false },
        onSenderProgress: ProgressListener? = null
    ): Pair<TransferResult, TransferResult> {
        TestLink(tamper).use { link ->
            val (sa, sb) = link.sessions()
            return both(
                {
                    try {
                        (source ?: FileChunkSource(src)).use { FileSender(sa, cfg, id).send(it, onSenderProgress, senderCancel) }
                    } finally { sa.close() }
                },
                {
                    try {
                        FileReceiver(sb, cfg, store) { FileChunkSink(dst) }.receive(onOffer = { _, _ -> accept })
                    } finally { sb.close() }
                }
            )
        }
    }

    init {
        // ---------------- crypto primitives ----------------
        test("HKDF-SHA256 matches RFC 5869 test vector 1") {
            val ikm = ByteArray(22) { 0x0b }
            val salt = hexToBytes("000102030405060708090a0b0c")
            val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
            val prk = Hkdf.extract(salt, ikm)
            expect(prk.toHex() == "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", "PRK mismatch")
            val okm = Hkdf.expand(prk, info, 42)
            expect(okm.toHex() == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", "OKM mismatch")
        }

        test("nonces are unique for 200,000 consecutive records and record counter advances by one") {
            val seen = HashSet<Long>()
            for (c in 0L until 200_000L) {
                val n = Session.nonceFor(c)
                expect(n.size == 12, "nonce size")
                expect(seen.add(ByteBuffer.wrap(n).getLong(4)), "nonce repeated at $c")
            }
            TestLink().use { link ->
                val (a, b) = link.sessions()
                for (i in 0 until 50) a.send(RecordType.CHUNK, i, byteArrayOf(1, 2, 3))
                expect(a.recordsSent == 50L, "counter should be 50, was ${a.recordsSent}")
                for (i in 0 until 50) expect(b.receive().index == i, "order")
            }
        }

        // ---------------- handshake ----------------
        test("handshake: both sides get the same 6-digit code and can talk both ways") {
            val (a, b) = socketPair()
            val (si, sr) = both({ Handshake.initiate(a.getInputStream(), a.getOutputStream()) },
                { Handshake.respond(b.getInputStream(), b.getOutputStream()) })
            expect(si.sas == sr.sas, "SAS differs: ${si.sas} vs ${sr.sas}")
            expect(Regex("\\d{3} \\d{3}").matches(si.sas), "SAS format: ${si.sas}")
            si.send(RecordType.OFFER, 7, "hello".toByteArray())
            val r1 = sr.receive()
            expect(r1.index == 7 && String(r1.payload) == "hello", "initiator->responder failed")
            sr.send(RecordType.RESPONSE, 9, "world".toByteArray())
            val r2 = si.receive()
            expect(r2.index == 9 && String(r2.payload) == "world", "responder->initiator failed")
            si.close(); sr.close()
        }

        test("handshake: every session uses fresh ephemeral keys") {
            val ids = HashSet<String>()
            repeat(3) {
                val (a, b) = socketPair()
                val (si, sr) = both({ Handshake.initiate(a.getInputStream(), a.getOutputStream()) },
                    { Handshake.respond(b.getInputStream(), b.getOutputStream()) })
                expect(ids.add(si.id.toHex()), "session id repeated")
                si.close(); sr.close()
            }
        }

        test("handshake: man-in-the-middle ends up with DIFFERENT codes on the two phones") {
            val (aliceSock, malloryA) = socketPair()
            val (malloryB, bobSock) = socketPair()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val alice = pool.submit(java.util.concurrent.Callable { Handshake.initiate(aliceSock.getInputStream(), aliceSock.getOutputStream()) })
                val mA = pool.submit(java.util.concurrent.Callable { Handshake.respond(malloryA.getInputStream(), malloryA.getOutputStream()) })
                val mB = pool.submit(java.util.concurrent.Callable { Handshake.initiate(malloryB.getInputStream(), malloryB.getOutputStream()) })
                val bob = pool.submit(java.util.concurrent.Callable { Handshake.respond(bobSock.getInputStream(), bobSock.getOutputStream()) })
                val sAlice = alice.get(30, java.util.concurrent.TimeUnit.SECONDS)
                val sBob = bob.get(30, java.util.concurrent.TimeUnit.SECONDS)
                val sMA = mA.get(30, java.util.concurrent.TimeUnit.SECONDS)
                val sMB = mB.get(30, java.util.concurrent.TimeUnit.SECONDS)
                expect(sAlice.sas == sMA.sas, "alice/mallory leg should agree")
                expect(sBob.sas == sMB.sas, "mallory/bob leg should agree")
                expect(sAlice.sas != sBob.sas, "MITM not detectable: Alice and Bob show the same code ${sAlice.sas}")
            } finally { pool.shutdownNow() }
        }

        test("handshake: responder rejects a key reveal that does not match the commitment") {
            val (a, b) = socketPair()
            // byte 37+65+3 is inside the initiator's revealed nonce (M1 is 37 bytes, then pub 65, then nonce)
            val result = both(
                { try { Handshake.initiate(a.getInputStream(), FlipOutputStream(a.getOutputStream(), 37 + 65 + 3)); "initiator-done" } catch (e: Exception) { "initiator-failed" } },
                { try { Handshake.respond(b.getInputStream(), b.getOutputStream()); "accepted" } catch (e: ProtocolException) { "rejected" } }
            )
            expect(result.second == "rejected", "responder accepted a bad commitment: $result")
            a.close(); b.close()
        }

        test("handshake: off-curve, out-of-range and malformed public keys are rejected") {
            fun rejects(bytes: ByteArray): Boolean = try { Handshake.decodePublic(bytes); false } catch (e: ProtocolException) { true }
            val offCurve = ByteArray(65).also { it[0] = 4; for (i in 1 until 65) it[i] = (i % 7 + 1).toByte() }
            expect(rejects(offCurve), "off-curve point accepted")
            expect(rejects(ByteArray(65) { 0xFF.toByte() }.also { it[0] = 4 }), "out-of-range accepted")
            expect(rejects(ByteArray(64)), "short key accepted")
            expect(rejects(ByteArray(65)), "wrong prefix accepted")
        }

        // ---------------- record layer attacks ----------------
        test("records: delivered in order pass; tamper, duplicate, swap and drop are all rejected") {
            fun run(t: Tamper): Int {   // returns how many records were accepted before an error
                TestLink(t).use { link ->
                    val (a, b) = link.sessions()
                    for (i in 0 until 6) a.send(RecordType.CHUNK, i, ByteArray(100) { i.toByte() })
                    var ok = 0
                    try { while (ok < 6) { b.receive(); ok++ } } catch (_: ProtocolException) { } catch (_: java.io.IOException) { }
                    return ok
                }
            }
            expect(run(Tamper.None) == 6, "clean link should deliver 6")
            expect(run(Tamper.FlipByte(2)) == 2, "bit flip not detected")
            expect(run(Tamper.Duplicate(2)) == 3, "replay not detected")
            expect(run(Tamper.Swap(2)) == 2, "reorder not detected")
            expect(run(Tamper.Drop(2)) == 2, "dropped record not detected")
        }

        test("records: oversized frame length is rejected before allocating memory") {
            val (x, y) = socketPair()
            val (_, b) = sessionsOn(x, y)
            val raw = java.io.DataOutputStream(x.getOutputStream())
            raw.writeInt(Int.MAX_VALUE); raw.flush()
            var rejected = false
            try { b.receive() } catch (e: ProtocolException) { rejected = true }
            expect(rejected, "huge frame accepted")
            x.close(); y.close()
        }

        // ---------------- file transfer ----------------
        test("transfer: 10 MB file arrives byte-identical (SHA-256 verified)") {
            val dir = tmpDir(); val src = randomFile(dir, "src.bin", 10L * MB); val dst = File(dir, "dst.part")
            val store = InMemoryTransferStateStore()
            val (s, r) = transfer(Tamper.None, src, dst, store)
            expect(s is TransferResult.Success, "sender: $s")
            expect(r is TransferResult.Success, "receiver: $r")
            expect(sha(src) == sha(dst), "files differ")
            val st = (r as TransferResult.Success).stats
            println("      (loopback 10 MB: ${st.durationMs} ms, ${"%.1f".format(st.throughputMBps)} MB/s, encrypted+verified; not a radio benchmark)")
        }

        test("transfer: empty, tiny, exact-multiple and unaligned sizes") {
            for (size in listOf(0L, 1L, 1023L, cfg.chunkSize.toLong(), cfg.chunkSize + 1L, 3L * cfg.chunkSize - 7)) {
                val dir = tmpDir(); val src = randomFile(dir, "s.bin", size, seed = size); val dst = File(dir, "d.part")
                val (s, r) = transfer(Tamper.None, src, dst, InMemoryTransferStateStore())
                expect(s is TransferResult.Success && r is TransferResult.Success, "size $size failed: $s / $r")
                expect(dst.exists() && sha(src) == sha(dst), "content mismatch at size $size")
            }
        }

        test("transfer: tampered chunk is rejected and the transfer never reports success") {
            val dir = tmpDir(); val src = randomFile(dir, "s.bin", 5L * MB); val dst = File(dir, "d.part")
            val store = InMemoryTransferStateStore(); val id = randomTransferId()
            val (s, r) = transfer(Tamper.FlipByte(4), src, dst, store, id)
            expect(r is TransferResult.Failed, "receiver should fail, got $r")
            expect(s !is TransferResult.Success, "sender must not report success, got $s")
            val st = store.get(id.toHex())!!
            expect(st.status != TransferStatus.COMPLETED, "state must not be COMPLETED")
            expect(ChunkBitmap.fromBytes(st.receivedBitmap, 20)!!.count() <= 3, "bad chunk was recorded as done")
        }

        test("transfer: replayed, reordered and dropped chunks never produce a successful transfer") {
            for (t in listOf(Tamper.Duplicate(5), Tamper.Swap(5), Tamper.Drop(5))) {
                val dir = tmpDir(); val src = randomFile(dir, "s.bin", 5L * MB); val dst = File(dir, "d.part")
                val (s, r) = transfer(t, src, dst, InMemoryTransferStateStore())
                expect(r !is TransferResult.Success, "receiver succeeded under ${t::class.simpleName}")
                expect(s !is TransferResult.Success, "sender succeeded under ${t::class.simpleName}")
            }
        }

        test("transfer: sender that advertises a wrong SHA-256 is caught; partial data is deleted") {
            val dir = tmpDir(); val src = randomFile(dir, "s.bin", 2L * MB); val dst = File(dir, "d.part")
            val store = InMemoryTransferStateStore(); val id = randomTransferId()
            val real = FileChunkSource(src)
            val liar = object : ChunkSource by real { override fun sha256() = ByteArray(32) }
            val (s, r) = transfer(Tamper.None, src, dst, store, id, source = liar)
            expect(r is TransferResult.Failed, "receiver: $r")
            expect(s is TransferResult.Failed, "sender: $s")
            expect(!dst.exists(), "unverified data was left on disk")
            expect(store.get(id.toHex())!!.status == TransferStatus.FAILED, "state should be FAILED")
            real.close()
        }

        test("transfer: resume after connection loss sends only the missing chunks") {
            val dir = tmpDir(); val src = randomFile(dir, "s.bin", 10L * MB); val dst = File(dir, "d.part")
            val store = InMemoryTransferStateStore(); val id = randomTransferId()
            val (_, r1) = transfer(Tamper.CutAfter(30), src, dst, store, id)
            expect(r1 is TransferResult.Interrupted, "first attempt should be interrupted, got $r1")
            val mid = store.get(id.toHex())!!
            expect(mid.status == TransferStatus.INTERRUPTED, "status ${mid.status}")
            expect(mid.bytesDone > 0 && mid.bytesDone < src.length(), "progress not saved: ${mid.bytesDone}")

            val (s2, r2) = transfer(Tamper.None, src, dst, store, id)   // brand-new session, same transfer id
            expect(s2 is TransferResult.Success && r2 is TransferResult.Success, "resume failed: $s2 / $r2")
            val sent = (s2 as TransferResult.Success).stats.bytesThisSession
            expect(sent == src.length() - mid.bytesDone, "expected to send ${src.length() - mid.bytesDone} bytes, sent $sent")
            expect(sha(src) == sha(dst), "resumed file differs")
            println("      (resumed: already had ${mid.bytesDone / 1024} KiB, re-sent only ${sent / 1024} KiB)")
        }

        test("transfer: a corrupted partial file is caught by the final hash, then a clean retry succeeds") {
            val dir = tmpDir(); val src = randomFile(dir, "s.bin", 10L * MB); val dst = File(dir, "d.part")
            val store = InMemoryTransferStateStore(); val id = randomTransferId()
            transfer(Tamper.CutAfter(30), src, dst, store, id)
            java.io.RandomAccessFile(dst, "rw").use { it.seek(100); val b = it.read(); it.seek(100); it.write(b xor 0xFF) }
            val (_, r2) = transfer(Tamper.None, src, dst, store, id)
            expect(r2 is TransferResult.Failed, "corruption not detected: $r2")
            expect(!dst.exists(), "corrupted data kept")
            val (s3, r3) = transfer(Tamper.None, src, dst, store, id)
            expect(s3 is TransferResult.Success && r3 is TransferResult.Success, "clean retry failed: $s3 / $r3")
            expect(sha(src) == sha(dst), "retry file differs")
        }

        test("transfer: receiver can reject the offer; nothing is written") {
            val dir = tmpDir(); val src = randomFile(dir, "s.bin", 1L * MB); val dst = File(dir, "d.part")
            val (s, r) = transfer(Tamper.None, src, dst, InMemoryTransferStateStore(), accept = false)
            expect(s is TransferResult.Rejected && r is TransferResult.Rejected, "got $s / $r")
            expect(!dst.exists(), "file created despite rejection")
        }

        test("transfer: sender cancel stops the transfer and the receiver discards partial data") {
            val dir = tmpDir(); val src = randomFile(dir, "s.bin", 10L * MB); val dst = File(dir, "d.part")
            var chunks = 0
            val (s, r) = transfer(Tamper.None, src, dst, InMemoryTransferStateStore(),
                senderCancel = { chunks > 5 }, onSenderProgress = { _, _ -> chunks++ })
            expect(s is TransferResult.Cancelled, "sender: $s")
            expect(r is TransferResult.Cancelled && r.byPeer, "receiver: $r")
            expect(!dst.exists(), "partial file left behind")
        }

        test("end-to-end: real handshake + transfer, sender as initiator AND as responder, 64 KiB chunks") {
            for (senderIsInitiator in listOf(true, false)) {
                val dir = tmpDir(); val src = randomFile(dir, "s.bin", 3L * MB + 123); val dst = File(dir, "d.part")
                val (x, y) = socketPair()
                val (sx, sy) = both({ Handshake.initiate(x.getInputStream(), x.getOutputStream()) },
                    { Handshake.respond(y.getInputStream(), y.getOutputStream()) })
                expect(sx.sas == sy.sas, "codes differ")
                val (sendSess, recvSess) = if (senderIsInitiator) sx to sy else sy to sx
                val btCfg = TransferConfig(chunkSize = 64 * 1024)
                val (s, r) = both(
                    { try { FileChunkSource(src).use { FileSender(sendSess, btCfg).send(it) } } finally { sendSess.close() } },
                    { try { FileReceiver(recvSess, TransferConfig(), InMemoryTransferStateStore()) { FileChunkSink(dst) }.receive() } finally { recvSess.close() } }
                )
                expect(s is TransferResult.Success && r is TransferResult.Success, "initiator=$senderIsInitiator: $s / $r")
                expect(sha(src) == sha(dst), "content differs (initiator=$senderIsInitiator)")
            }
        }

        // ---------------- malformed / hostile input ----------------
        test("offers: oversized, zero/huge chunk size, bad names and truncated offers are refused") {
            val ok = TransferOffer(randomTransferId(), "a.txt", "text/plain", 1000, 4096, ByteArray(32))
            fun bad(o: TransferOffer): Boolean {
                val dir = tmpDir(); var sinkCreated = false
                TestLink().use { link ->
                    val (sa, sb) = link.sessions()
                    val (_, r) = both(
                        { try { sa.send(RecordType.OFFER, 0, OfferCodec.encode(o)); sa.receive() } catch (_: Exception) {} ; sa.close() },
                        { try { FileReceiver(sb, cfg, InMemoryTransferStateStore()) { sinkCreated = true; FileChunkSink(File(dir, "x")) }.receive() } finally { sb.close() } }
                    )
                    return r is TransferResult.Failed && !sinkCreated
                }
            }
            expect(bad(TransferOffer(ok.transferId, "a", "", cfg.maxFileSize + 1, 4096, ByteArray(32))), "huge size accepted")
            expect(bad(TransferOffer(ok.transferId, "a", "", 1000, 0, ByteArray(32))), "chunk size 0 accepted")
            expect(bad(TransferOffer(ok.transferId, "a", "", 1000, Int.MAX_VALUE, ByteArray(32))), "huge chunk size accepted")
            expect(bad(TransferOffer(ok.transferId, "a", "", -5, 4096, ByteArray(32))), "negative size accepted")
            expect(bad(TransferOffer(ok.transferId, "", "", 10, 4096, ByteArray(32))), "empty name accepted")
            // truncated payload
            var truncatedRefused = false
            TestLink().use { link ->
                val (sa, sb) = link.sessions()
                val (_, r) = both({ sa.send(RecordType.OFFER, 0, OfferCodec.encode(ok).copyOf(20)); sa.close() },
                    { try { FileReceiver(sb, cfg, InMemoryTransferStateStore()) { FileChunkSink(File(tmpDir(), "x")) }.receive() } finally { sb.close() } })
                truncatedRefused = r is TransferResult.Failed
            }
            expect(truncatedRefused, "truncated offer accepted")
        }

        test("offers: file names cannot escape the target folder") {
            expect(OfferCodec.sanitizeFileName("../../etc/passwd") == "passwd", "unix traversal")
            expect(OfferCodec.sanitizeFileName("C:\\Windows\\evil.exe") == "evil.exe", "windows path")
            expect(OfferCodec.sanitizeFileName("..") == "file", "dotdot")
            expect(OfferCodec.sanitizeFileName("") == "file", "empty")
            expect(OfferCodec.sanitizeFileName("a\u0000b?.txt") == "ab.txt", "control chars")
        }

        test("chunks: duplicate or out-of-range chunk indices are protocol errors") {
            val dir = tmpDir()
            for (bad in listOf(0 /*duplicate*/, 99 /*out of range*/)) {
                val offer = TransferOffer(randomTransferId(), "f.bin", "", 3L * 4096, 4096, ByteArray(32))
                TestLink().use { link ->
                    val (sa, sb) = link.sessions()
                    val (_, r) = both({
                        try {
                            sa.send(RecordType.OFFER, 0, OfferCodec.encode(offer)); sa.bindTransfer(offer.transferId)
                            sa.receive()
                            sa.send(RecordType.CHUNK, 0, ByteArray(4096))
                            sa.send(RecordType.CHUNK, bad, ByteArray(4096))
                            sa.receive()
                        } catch (_: Exception) {}
                        sa.close()
                    }, { try { FileReceiver(sb, cfg, InMemoryTransferStateStore()) { FileChunkSink(File(dir, "f$bad")) }.receive() } finally { sb.close() } })
                    expect(r is TransferResult.Failed, "index $bad accepted: $r")
                }
            }
        }

        test("chunks: wrong chunk length is rejected") {
            val dir = tmpDir()
            val offer = TransferOffer(randomTransferId(), "f.bin", "", 3L * 4096, 4096, ByteArray(32))
            TestLink().use { link ->
                val (sa, sb) = link.sessions()
                val (_, r) = both({
                    try { sa.send(RecordType.OFFER, 0, OfferCodec.encode(offer)); sa.bindTransfer(offer.transferId); sa.receive(); sa.send(RecordType.CHUNK, 0, ByteArray(100)); sa.receive() } catch (_: Exception) {}
                    sa.close()
                }, { try { FileReceiver(sb, cfg, InMemoryTransferStateStore()) { FileChunkSink(File(dir, "f")) }.receive() } finally { sb.close() } })
                expect(r is TransferResult.Failed, "short chunk accepted: $r")
            }
        }
    }

    fun runAll(log: (String) -> Unit = ::println) {
        var failed = 0
        for ((name, body) in tests) {
            try { body(); log("PASS  $name") }
            catch (t: Throwable) { failed++; log("FAIL  $name\n      -> ${t::class.simpleName}: ${t.message}") }
        }
        log("\n${tests.size - failed}/${tests.size} tests passed")
        if (failed > 0) throw AssertionError("$failed test(s) failed")
    }
}
