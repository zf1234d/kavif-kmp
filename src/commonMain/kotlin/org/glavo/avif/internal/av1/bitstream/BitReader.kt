// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** MSB-first bit reader for a single AV1 OBU payload. */
class BitReader constructor(
    data: ByteArray,
    startByteOffset: Int = 0,
    byteLength: Int = requireNotNullCompat(
        data,
        "data"
    ).size
) {
    /** The payload bytes being read. */
    private val data: ByteArray

    /** The first readable byte offset inside `data`. */
    private val startByteOffset: Int

    /** The readable byte length inside `data`. */
    private val byteLength: Int

    /** The next unread bit offset from the start of the payload. */
    private var bitOffset = 0

    init {
        this.data = requireNotNullCompat(data, "data")
        require(startByteOffset in 0..data.size) { "startByteOffset out of range: $startByteOffset" }
        require(!(byteLength < 0 || startByteOffset + byteLength > data.size)) { "byteLength out of range: $byteLength" }
        this.startByteOffset = startByteOffset
        this.byteLength = byteLength
    }

    /** Returns the number of unread bits.
     * 
     * @return the number of unread bits
     */
    fun bitsRemaining(): Int {
        return byteLength * Byte.SIZE_BITS - bitOffset
    }

    /** Returns the next unread bit offset from the start of the payload.
     * 
     * @return the next unread bit offset
     */
    fun bitOffset(): Int {
        return bitOffset
    }

    val isByteAligned: Boolean
        /** Returns whether the current position is aligned to the next byte boundary.
         * 
         * @return whether the current position is byte aligned
         */
        get() = (bitOffset and 7) == 0

    /** Returns the next unread byte offset when the reader is byte aligned.
     * 
     * @return the next unread byte offset
     */
    fun byteOffset(): Int {
        check(this.isByteAligned) { "BitReader is not byte aligned" }
        return bitOffset ushr 3
    }

    /** Advances the reader to the next byte boundary. */
    fun byteAlign() {
        val remainder = bitOffset and 7
        if (remainder != 0) {
            bitOffset += Byte.SIZE_BITS - remainder
        }
    }

    /** Reads a single bit.
     * 
     * @return the next bit as `0` or `1`
     * @throws AvifIoException if the payload is truncated
     */
    fun readBit(): Int {
        ensureBitsRemaining(1)
        val byteIndex = startByteOffset + (bitOffset ushr 3)
        val bitIndex = 7 - (bitOffset and 7)
        bitOffset++
        return (data[byteIndex].toInt() ushr bitIndex) and 1
    }

    /** Reads a boolean flag.
     * 
     * @return the next bit as a boolean
     * @throws AvifIoException if the payload is truncated
     */
    fun readFlag(): Boolean {
        return readBit() != 0
    }

    /** Reads up to thirty-two bits as an unsigned literal.
     * 
     * @param bitCount the number of bits to read, in the range `0..32`
     * @return the decoded unsigned literal
     * @throws AvifIoException if the payload is truncated
     */
    fun readBits(bitCount: Int): Long {
        require(bitCount in 0..Int.SIZE_BITS) { "bitCount out of range: $bitCount" }
        if (bitCount == 0) {
            return 0
        }

        ensureBitsRemaining(bitCount)

        var value: Long = 0
        for (i in 0..<bitCount) {
            value = (value shl 1) or readBit().toLong()
        }
        return value
    }

    /** Reads up to thirty-two bits as a signed literal with sign extension.
     * 
     * @param bitCount the number of bits to read, in the range `1..32`
     * @return the decoded signed literal
     * @throws AvifIoException if the payload is truncated
     */
    fun readSignedBits(bitCount: Int): Int {
        require(!(bitCount <= 0 || bitCount > Int.SIZE_BITS)) { "bitCount out of range: $bitCount" }

        val value = readBits(bitCount)
        val shift = (Long.SIZE_BITS - bitCount).toLong()
        return ((value shl shift.toInt()) shr shift.toInt()).toInt()
    }

    /** Reads an unsigned exponential-Golomb style value used by AV1 headers.
     * 
     * @return the decoded unsigned value
     * @throws AvifIoException if the payload is truncated or invalid
     */
    fun readUvlc(): Long {
        var leadingZeros = 0

        while (true) {
            val bit = readBit()
            if (bit == 1) {
                break
            }
            leadingZeros++
            if (leadingZeros == 32) {
                throw AvifIoException("UVLC value exceeds the supported range")
            }
        }

        if (leadingZeros == 0) {
            return 0
        }

        val suffix = readBits(leadingZeros)
        return ((1L shl leadingZeros) - 1L) + suffix
    }

    /** Ensures that the payload exposes at least the requested number of unread bits.
     * 
     * @param bitCount the number of required unread bits
     * @throws EOFException if the payload is truncated
     */
    private fun ensureBitsRemaining(bitCount: Int) {
        if (bitsRemaining() < bitCount) {
            throw EOFException("Unexpected end of OBU payload")
        }
    }
}
