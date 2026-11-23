package com.example.dropsecure.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Ephemeral ECDH (P-256) with a hash commitment, then HKDF-SHA256 key derivation.
 *
 * Message flow (initiator = whoever connects, responder = whoever accepts):
 *   1. I -> R : magic, version, COMMIT = SHA256(pubI || nonceI)
 *   2. R -> I : pubR, nonceR
 *   3. I -> R : pubI, nonceI          (R checks it against COMMIT)
 *
 * Both sides then compute the same transcript hash and a short authentication string (SAS).
 * The users compare the SAS out loud / on screen. Because the initiator commits to its key BEFORE
 * seeing the responder's, a man-in-the-middle cannot grind keys to make two SAS values match, so a
 * 6-digit code gives roughly a 1-in-1,000,000 chance per attack attempt.
 *
 * IMPORTANT: until BOTH users have confirmed the SAS, the session must not be used for file data.
 * This gives authentication only if users actually compare the codes.
 */
object Handshake {
    private const val MAGIC = 0x44535343          // "DSSC"
    private const val VERSION = 1
    private const val PUB_LEN = 65
    private const val NONCE_LEN = 16
    private val rng = SecureRandom()

    private val COMMIT_LABEL = "DropSecure/1/commit".toByteArray()
    private val TRANSCRIPT_LABEL = "DropSecure/1/transcript".toByteArray()
    private val INFO_I2R = "DropSecure/1/key initiator->responder".toByteArray()
    private val INFO_R2I = "DropSecure/1/key responder->initiator".toByteArray()
    private val INFO_SAS = "DropSecure/1/sas".toByteArray()

    private object Curve {
        val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").let {
            it.init(ECGenParameterSpec("secp256r1"))
            it.getParameterSpec(ECParameterSpec::class.java)
        }
        val p: BigInteger = (params.curve.field as ECFieldFp).p
    }

    fun initiate(input: InputStream, output: OutputStream): Session {
        val din = DataInputStream(BufferedInputStream(input))
        val dout = DataOutputStream(BufferedOutputStream(output))
        val kp = generateKeyPair()
        val pubI = encodePublic(kp.public)
        val nonceI = randomBytes(NONCE_LEN)
        val commit = sha256(COMMIT_LABEL, pubI, nonceI)

        dout.writeInt(MAGIC); dout.writeByte(VERSION); dout.write(commit); dout.flush()

        val pubR = ByteArray(PUB_LEN).also { din.readFully(it) }
        val nonceR = ByteArray(NONCE_LEN).also { din.readFully(it) }
        val peer = decodePublic(pubR)
        if (MessageDigest.isEqual(pubR, pubI)) throw ProtocolException("peer reflected our own key")

        dout.write(pubI); dout.write(nonceI); dout.flush()
        return finish(kp, peer, pubI, nonceI, pubR, nonceR, true, din, dout)
    }

    fun respond(input: InputStream, output: OutputStream): Session {
        val din = DataInputStream(BufferedInputStream(input))
        val dout = DataOutputStream(BufferedOutputStream(output))

        if (din.readInt() != MAGIC) throw ProtocolException("not a DropSecure peer")
        if (din.readUnsignedByte() != VERSION) throw ProtocolException("unsupported protocol version")
        val commit = ByteArray(32).also { din.readFully(it) }

        val kp = generateKeyPair()
        val pubR = encodePublic(kp.public)
        val nonceR = randomBytes(NONCE_LEN)
        dout.write(pubR); dout.write(nonceR); dout.flush()

        val pubI = ByteArray(PUB_LEN).also { din.readFully(it) }
        val nonceI = ByteArray(NONCE_LEN).also { din.readFully(it) }
        if (!MessageDigest.isEqual(commit, sha256(COMMIT_LABEL, pubI, nonceI))) {
            throw ProtocolException("key commitment mismatch (possible attack)")
        }
        val peer = decodePublic(pubI)
        if (MessageDigest.isEqual(pubR, pubI)) throw ProtocolException("peer reflected our own key")
        return finish(kp, peer, pubI, nonceI, pubR, nonceR, false, din, dout)
    }

    private fun finish(
        kp: KeyPair, peer: PublicKey,
        pubI: ByteArray, nonceI: ByteArray, pubR: ByteArray, nonceR: ByteArray,
        isInitiator: Boolean, din: DataInputStream, dout: DataOutputStream
    ): Session {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp.private)
        ka.doPhase(peer, true)
        val shared = ka.generateSecret()

        val transcript = sha256(TRANSCRIPT_LABEL, pubI, nonceI, pubR, nonceR)
        val prk = Hkdf.extract(transcript, shared)
        val keyI2R = Hkdf.expand(prk, INFO_I2R, 32)
        val keyR2I = Hkdf.expand(prk, INFO_R2I, 32)
        val sasBytes = Hkdf.expand(prk, INFO_SAS, 4)
        shared.fill(0); prk.fill(0)

        val n = ByteBuffer.wrap(sasBytes).int.toLong() and 0xFFFFFFFFL
        val v = (n % 1_000_000L).toInt()
        val sas = "%03d %03d".format(v / 1000, v % 1000)

        return if (isInitiator) Session(sas, transcript, keyI2R, keyR2I, din, dout)
        else Session(sas, transcript, keyR2I, keyI2R, din, dout)
    }

    // ---- key encoding / validation -------------------------------------------------------

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        return kpg.generateKeyPair()
    }

    /** Canonical uncompressed point: 0x04 || X(32) || Y(32). */
    internal fun encodePublic(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)
    }

    /** Rejects malformed points, out-of-range coordinates and points that are not on P-256. */
    internal fun decodePublic(bytes: ByteArray): PublicKey {
        if (bytes.size != PUB_LEN || bytes[0].toInt() != 4) throw ProtocolException("bad public key encoding")
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val p = Curve.p
        if (x >= p || y >= p) throw ProtocolException("public key out of range")
        val c = Curve.params.curve
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(c.a.multiply(x)).add(c.b).mod(p)
        if (lhs != rhs) throw ProtocolException("public key is not on the curve")
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), Curve.params))
        } catch (e: Exception) {
            throw ProtocolException("invalid public key", e)
        }
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val len = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - len, out, 32 - len, len)
        return out
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }
}
