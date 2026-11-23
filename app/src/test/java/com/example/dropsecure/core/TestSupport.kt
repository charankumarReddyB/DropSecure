package com.example.dropsecure.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed class Tamper {
    object None : Tamper()
    class FlipByte(val frame: Int) : Tamper()
    class Duplicate(val frame: Int) : Tamper()
    class Swap(val frame: Int) : Tamper()
    class Drop(val frame: Int) : Tamper()
    class CutAfter(val frames: Int) : Tamper()
}

/** Flips one bit at a given absolute byte offset of the stream. */
class FlipOutputStream(private val out: OutputStream, private val at: Long) : OutputStream() {
    private var pos = 0L
    override fun write(b: Int) { out.write(if (pos++ == at) b xor 1 else b) }
    override fun write(b: ByteArray, off: Int, len: Int) {
        val c = b.copyOfRange(off, off + len)
        if (at >= pos && at < pos + len) { val i = (at - pos).toInt(); c[i] = (c[i].toInt() xor 1).toByte() }
        pos += len
        out.write(c)
    }
    override fun flush() = out.flush()
    override fun close() = out.close()
}

fun loopback(): InetAddress = InetAddress.getLoopbackAddress()

fun socketPair(): Pair<Socket, Socket> {
    val srv = ServerSocket(0, 1, loopback())
    val pool = Executors.newSingleThreadExecutor()
    val f = pool.submit(Callable { srv.accept() })
    val a = Socket(loopback(), srv.localPort)
    val b = f.get(10, TimeUnit.SECONDS)
    srv.close(); pool.shutdown()
    a.soTimeout = 30_000; b.soTimeout = 30_000
    return a to b
}

/**
 * A -> proxy -> B. Frames flowing A->B can be tampered with, duplicated, swapped, dropped or cut.
 * Sessions get random test keys, so this exercises the record layer and the file protocol directly.
 */
class TestLink(private val tamper: Tamper = Tamper.None) : Closeable {
    val a: Socket
    val b: Socket
    private val pa: Socket
    private val pb: Socket

    init {
        val srvB = ServerSocket(0, 1, loopback())
        val proxySrv = ServerSocket(0, 1, loopback())
        val pool = Executors.newFixedThreadPool(2)
        val bf = pool.submit(Callable { srvB.accept() })
        val paf = pool.submit(Callable { proxySrv.accept() })
        pb = Socket(loopback(), srvB.localPort)
        a = Socket(loopback(), proxySrv.localPort)
        pa = paf.get(10, TimeUnit.SECONDS)
        b = bf.get(10, TimeUnit.SECONDS)
        srvB.close(); proxySrv.close(); pool.shutdown()
        for (s in listOf(a, b, pa, pb)) s.soTimeout = 30_000
        Thread { pumpFrames(pa, pb, tamper) }.apply { isDaemon = true }.start()
        Thread { pumpRaw(pb, pa) }.apply { isDaemon = true }.start()
    }

    fun sessions(): Pair<Session, Session> = sessionsOn(a, b)

    private fun closeAll() { for (s in listOf(a, b, pa, pb)) try { s.close() } catch (_: Exception) {} }
    override fun close() = closeAll()

    private fun finish(src: Socket, dst: Socket, clean: Boolean) {
        if (clean) { try { dst.shutdownOutput() } catch (_: Exception) {} }
        else { try { dst.close() } catch (_: Exception) {}; try { src.close() } catch (_: Exception) {} }
    }

    private fun pumpRaw(src: Socket, dst: Socket) {
        var clean = true
        try {
            val i = src.getInputStream(); val o = dst.getOutputStream()
            val buf = ByteArray(8192)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
        } catch (_: Exception) { clean = false }
        finish(src, dst, clean)
    }

    private fun pumpFrames(src: Socket, dst: Socket, t: Tamper) {
        try {
            val din = DataInputStream(BufferedInputStream(src.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(dst.getOutputStream()))
            fun write(body: ByteArray) { dout.writeInt(body.size); dout.write(body); dout.flush() }
            var idx = 0
            var held: ByteArray? = null
            while (true) {
                val len = try { din.readInt() } catch (_: java.io.EOFException) { finish(src, dst, true); return }
                if (len < 0 || len > 4 * 1024 * 1024) { finish(src, dst, false); return }
                val body = ByteArray(len)
                din.readFully(body)
                when (t) {
                    is Tamper.None -> write(body)
                    is Tamper.FlipByte -> {
                        if (idx == t.frame) body[body.size - 1] = (body[body.size - 1].toInt() xor 1).toByte()
                        write(body)
                    }
                    is Tamper.Duplicate -> { write(body); if (idx == t.frame) write(body) }
                    is Tamper.Swap -> {
                        if (idx == t.frame) held = body
                        else { write(body); if (idx == t.frame + 1) { write(held!!); held = null } }
                    }
                    is Tamper.Drop -> if (idx != t.frame) write(body)
                    is Tamper.CutAfter -> { if (idx >= t.frames) { closeAll(); return }; write(body) }
                }
                idx++
            }
        } catch (_: Exception) { finish(src, dst, false) }
    }
}

fun <A, B> both(a: () -> A, b: () -> B): Pair<A, B> {
    val pool = Executors.newFixedThreadPool(2)
    try {
        val fa = pool.submit(Callable { a() })
        val fb = pool.submit(Callable { b() })
        try {
            return fa.get(90, TimeUnit.SECONDS) to fb.get(90, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    } finally { pool.shutdownNow() }
}

/** Two sessions with random test keys on a given socket pair (bypasses the handshake). */
fun sessionsOn(a: Socket, b: Socket): Pair<Session, Session> {
    val rnd = SecureRandom()
    val k1 = ByteArray(32).also { rnd.nextBytes(it) }
    val k2 = ByteArray(32).also { rnd.nextBytes(it) }
    val sid = ByteArray(32).also { rnd.nextBytes(it) }
    fun din(s: Socket) = DataInputStream(BufferedInputStream(s.getInputStream()))
    fun dout(s: Socket) = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
    val sa = Session("000 000", sid, k1.copyOf(), k2.copyOf(), din(a), dout(a))
    val sb = Session("000 000", sid, k2.copyOf(), k1.copyOf(), din(b), dout(b))
    return sa to sb
}
