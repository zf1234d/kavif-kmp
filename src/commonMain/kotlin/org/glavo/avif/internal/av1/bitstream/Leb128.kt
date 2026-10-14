// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.io.BufferedInput


/** Utility methods for reading unsigned LEB128 values from AV1 bitstreams. */
object Leb128 {
    /** The maximum supported unsigned value. */
    private const val MAX_UNSIGNED_VALUE = 0xFFFFFFFFL

    /** Reads an unsigned LEB128 value from a buffered byte source.
     * 
     * @param input the buffered byte source
     * @param maxBytes the maximum number of bytes to consume
     * @return the decoded LEB128 value and byte count
     * @throws AvifIoException if the source is truncated or unreadable
     */
    fun readUnsigned(input: BufferedInput, maxBytes: Int): ReadResult {
        requireNotNullCompat(input, "input")
        require(maxBytes > 0) { "maxBytes <= 0: $maxBytes" }

        var value: Long = 0
        var shift = 0

        for (byteCount in 1..maxBytes) {
            val current = input.readUnsignedByte()
            value = value or ((current and 0x7F).toLong() shl shift)

            if ((current and 0x80) == 0) {
                if (value > MAX_UNSIGNED_VALUE) {
                    throw AvifIoException("LEB128 value exceeds 32-bit unsigned range: $value")
                }
                return ReadResult(value, byteCount)
            }

            shift += 7
        }

        throw EOFException("LEB128 value exceeds the supported byte length")
    }

    /** Decoded unsigned LEB128 value and encoded byte count.
     * 
     * @param value the decoded unsigned value
     * @param byteCount the number of bytes consumed from the source
     */
    data class ReadResult(val value: Long, val byteCount: Int) {
        init {
            require(value >= 0) { "value < 0: $value" }
            require(byteCount > 0) { "byteCount <= 0: $byteCount" }
        }
    }
}
