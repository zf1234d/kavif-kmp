// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.AvifDecodeException
import org.glavo.avif.AvifErrorCode
import org.glavo.avif.internal.io.AvifDataSource


/** Bounded big-endian byte reader for BMFF boxes. */
class BoxInput private constructor(source: AvifDataSource, start: Int, end: Int) {
    /** The retained positional source. */
    private val source: AvifDataSource

    /** The inclusive lower bound for this view. */
    private val start: Int

    /** The exclusive upper bound for this view. */
    private val end: Int

    /** The current absolute read offset. */
    private var offset: Int

    /** Creates a bounded input over a byte array.
     * 
     * The input retains the array without copying it. The caller must not modify the array while
     * this input or one of its slices is in use.
     * 
     * @param source the complete source bytes
     */
    constructor(source: ByteArray) : this(
        AvifDataSource.Companion.ofBytes(
            requireNotNullCompat(
                source,
                "source"
            )
        )
    )

    /** Creates a bounded input over a retained positional source.
     * 
     * @param source the retained positional source
     */
    constructor(source: AvifDataSource) : this(source, 0, checkedSourceSize(source))

    init {
        this.source = requireNotNullCompat(source, "source")
        require(!(start < 0 || end < start || end > source.limit())) { "invalid input bounds: $start..$end" }
        this.start = start
        this.end = end
        this.offset = start
    }

    /** Returns the current absolute read offset.
     * 
     * @return the current absolute read offset
     */
    fun offset(): Int {
        return offset
    }

    /** Returns the exclusive upper bound for this view.
     * 
     * @return the exclusive upper bound for this view
     */
    fun end(): Int {
        return end
    }

    /** Returns the remaining byte count.
     * 
     * @return the remaining byte count
     */
    fun remaining(): Int {
        return end - offset
    }

    /** Returns whether unread bytes remain.
     * 
     * @return whether unread bytes remain
     */
    fun hasRemaining(): Boolean {
        return offset < end
    }

    /** Reads one unsigned 8-bit integer.
     * 
     * @return one unsigned 8-bit integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readU8(): Int {
        ensureAvailable(1)
        return toUnsignedInt(readSourceByte(offset++))
    }

    /** Reads one signed 8-bit integer.
     * 
     * @return one signed 8-bit integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readI8(): Int {
        return readU8().toByte().toInt()
    }

    /** Reads one unsigned 16-bit big-endian integer.
     * 
     * @return one unsigned 16-bit big-endian integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readU16(): Int {
        ensureAvailable(2)
        val value = ((toUnsignedInt(readSourceByte(offset)) shl 8)
                or toUnsignedInt(readSourceByte(offset + 1)))
        offset += 2
        return value
    }

    /** Reads one signed 16-bit big-endian integer.
     * 
     * @return one signed 16-bit big-endian integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readI16(): Int {
        return readU16().toShort().toInt()
    }

    /** Reads one unsigned 24-bit big-endian integer.
     * 
     * @return one unsigned 24-bit big-endian integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readU24(): Int {
        ensureAvailable(3)
        val value = ((toUnsignedInt(readSourceByte(offset)) shl 16)
                or (toUnsignedInt(readSourceByte(offset + 1)) shl 8)
                or toUnsignedInt(readSourceByte(offset + 2)))
        offset += 3
        return value
    }

    /** Reads one unsigned 32-bit big-endian integer.
     * 
     * @return one unsigned 32-bit big-endian integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readU32(): Long {
        ensureAvailable(4)
        val value = ((toUnsignedInt(readSourceByte(offset)).toLong() shl 24)
                or (toUnsignedInt(readSourceByte(offset + 1)).toLong() shl 16)
                or (toUnsignedInt(readSourceByte(offset + 2)).toLong() shl 8)
                or toUnsignedInt(readSourceByte(offset + 3)).toLong())
        offset += 4
        return value
    }

    /** Reads one signed 32-bit big-endian integer.
     * 
     * @return one signed 32-bit big-endian integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readI32(): Int {
        return readU32().toInt()
    }

    /** Reads one unsigned 64-bit big-endian integer that fits in `long`.
     * 
     * @return one unsigned 64-bit big-endian integer
     * @throws AvifDecodeException if the input is truncated or the value exceeds `Long.MAX_VALUE`
     */
    fun readU64(): Long {
        ensureAvailable(8)
        val high = readU32()
        val low = readU32()
        if ((high and 0x80000000L) != 0L) {
            throw parseFailed("64-bit box value exceeds supported range", (offset - 8).toLong())
        }
        return (high shl 32) or low
    }

    /** Reads one signed 64-bit big-endian integer.
     * 
     * @return one signed 64-bit big-endian integer
     * @throws AvifDecodeException if the input is truncated
     */
    fun readI64(): Long {
        val high = readU32()
        val low = readU32()
        return (high shl 32) or low
    }

    /** Reads one fixed-length four-character code.
     * 
     * @return one fixed-length four-character code
     * @throws AvifDecodeException if the input is truncated
     */
    fun readFourCc(): String {
        return readBytes(4).decodeToIso88591()
    }

    /** Reads one byte array.
     * 
     * @param length the byte count to read
     * @return one byte array
     * @throws AvifDecodeException if the input is truncated
     */
    fun readBytes(length: Int): ByteArray {
        require(length >= 0) { "length < 0: $length" }
        ensureAvailable(length)
        val result: ByteArray
        try {
            result = source.readBytes(offset.toLong(), length)
        } catch (exception: AvifIoException) {
            throw readFailed(exception, offset)
        }
        offset += length
        return result
    }

    /** Skips bytes.
     * 
     * @param length the byte count to skip
     * @throws AvifDecodeException if the input is truncated
     */
    fun skip(length: Int) {
        require(length >= 0) { "length < 0: $length" }
        ensureAvailable(length)
        offset += length
    }

    /** Creates a child input over one absolute byte range.
     * 
     * @param absoluteStart the absolute child start offset
     * @param size the child byte size
     * @return a child input over one absolute byte range
     * @throws AvifDecodeException if the range is outside this view
     */
    fun slice(absoluteStart: Int, size: Int): BoxInput {
        if (size < 0 || absoluteStart < start || absoluteStart > end || size > end - absoluteStart) {
            throw truncated("child box exceeds parent bounds", absoluteStart.toLong())
        }
        return BoxInput(source, absoluteStart, absoluteStart + size)
    }

    /** Reads one source byte and translates I/O failures to a container parse failure.
     * 
     * @param absoluteOffset the absolute source offset
     * @return the requested byte
     * @throws AvifDecodeException if the source cannot be read
     */
    private fun readSourceByte(absoluteOffset: Int): kotlin.Byte {
        try {
            return source.readByte(absoluteOffset.toLong())
        } catch (exception: AvifIoException) {
            throw readFailed(exception, absoluteOffset)
        }
    }

    /** Reads the next BMFF box header.
     * 
     * @return the next BMFF box header
     * @throws AvifDecodeException if the header is malformed or truncated
     */
    fun readBoxHeader(): BoxHeader {
        val boxOffset = offset
        ensureAvailable(8)
        val smallSize = readU32()
        val type = readFourCc()

        var boxSize = smallSize
        var sizeZero = false
        if (smallSize == 1L) {
            boxSize = readU64()
        } else if (smallSize == 0L) {
            sizeZero = true
            boxSize = (end - boxOffset).toLong()
        }

        val headerSize = offset - boxOffset
        if (boxSize < headerSize || boxSize > Int.MAX_VALUE) {
            throw parseFailed(
                "invalid BMFF box size for $type: $boxSize",
                boxOffset.toLong()
            )
        }
        val payloadSize = boxSize.toInt() - headerSize
        if (payloadSize > end - offset) {
            throw truncated("BMFF box payload exceeds parent bounds: $type", boxOffset.toLong())
        }
        return BoxHeader(type, boxOffset, offset, payloadSize, sizeZero)
    }

    /** Moves to the end of one parsed box.
     * 
     * @param header the parsed box header
     * @throws AvifDecodeException if the box end is outside this view
     */
    fun skipBoxPayload(header: BoxHeader) {
        val endOffset = header.endOffset()
        if (endOffset < offset || endOffset > end) {
            throw truncated(
                "BMFF box payload exceeds parent bounds: " + header.type,
                header.offset.toLong()
            )
        }
        offset = endOffset
    }

    /** Fails if the requested byte count is not available.
     * 
     * @param count the requested byte count
     * @throws AvifDecodeException if the input is truncated
     */
    private fun ensureAvailable(count: Int) {
        if (count < 0 || count > end - offset) {
            throw truncated("Unexpected end of BMFF input", offset.toLong())
        }
    }

    companion object {
        /** Returns the supported integer size of a complete source.
         * 
         * @param source the source to inspect
         * @return the source-position limit as an integer
         * @throws IllegalArgumentException if the source exceeds the parser's integer offset range
         */
        fun checkedSourceSize(source: AvifDataSource): Int {
            val size = requireNotNullCompat(source, "source").limit()
            require(size <= Int.MAX_VALUE) { "source exceeds supported size: $size" }
            return size.toInt()
        }

        /** Creates a container parse failure for an underlying positional-read failure.
         * 
         * @param cause the source read failure
         * @param absoluteOffset the attempted absolute source offset
         * @return the translated decode exception
         */
        fun readFailed(cause: AvifIoException, absoluteOffset: Int): AvifDecodeException {
            if (cause is AvifDecodeException) {
                return cause
            }
            return AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "Cannot read AVIF container data: " + cause.message,
                absoluteOffset.toLong(),
                cause
            )
        }

        /** Creates a BMFF parse failure.
         * 
         * @param message the failure message
         * @param byteOffset the associated byte offset
         * @return a BMFF parse failure
         */
        fun parseFailed(message: String, byteOffset: Long): AvifDecodeException {
            return AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, byteOffset)
        }

        /** Creates a truncation failure.
         * 
         * @param message the failure message
         * @param byteOffset the associated byte offset
         * @return a truncation failure
         */
        fun truncated(message: String, byteOffset: Long): AvifDecodeException {
            return AvifDecodeException(AvifErrorCode.TRUNCATED_DATA, message, byteOffset)
        }
    }
}
