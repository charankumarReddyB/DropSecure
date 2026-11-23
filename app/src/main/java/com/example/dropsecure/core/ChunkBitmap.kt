package com.example.dropsecure.core

class ChunkBitmap private constructor(val size: Int, private val bits: ByteArray) {
    constructor(size: Int) : this(size, ByteArray((size + 7) / 8))

    fun get(i: Int): Boolean = (bits[i shr 3].toInt() shr (i and 7)) and 1 == 1
    fun set(i: Int) { bits[i shr 3] = (bits[i shr 3].toInt() or (1 shl (i and 7))).toByte() }
    fun count(): Int { var c = 0; for (i in 0 until size) if (get(i)) c++; return c }
    fun toBytes(): ByteArray = bits.copyOf()
    fun copy(): ChunkBitmap = ChunkBitmap(size, bits.copyOf())

    companion object {
        /** Returns null if the byte length does not match [size]. Padding bits are cleared. */
        fun fromBytes(bytes: ByteArray, size: Int): ChunkBitmap? {
            if (bytes.size != (size + 7) / 8) return null
            val b = ChunkBitmap(size, bytes.copyOf())
            val extra = bytes.size * 8 - size
            if (extra > 0 && bytes.isNotEmpty()) {
                val mask = (0xFF shr extra)
                b.bits[b.bits.size - 1] = (b.bits[b.bits.size - 1].toInt() and mask).toByte()
            }
            return b
        }
    }
}
