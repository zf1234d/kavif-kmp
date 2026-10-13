// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.AvifImageReader
import org.glavo.avif.internal.io.AvifDataSource
import org.glavo.avif.internal.io.BufferedInput


/** Describes one logical AV1 payload as one or more ranges of retained source data.
 * 
 * A payload does not own or close its source. Its views remain readable only while the owning
 * [AvifImageReader] remains open. */
class AvifPayload private constructor(
    source: AvifDataSource,
    offsets: LongArray,
    lengths: IntArray
) {
    /** The source containing every extent. */
    private val source: AvifDataSource

    /** The absolute source offset of each extent. */
    private val offsets: LongArray

    /** The byte length of each extent. */
    private val lengths: IntArray

    /** The total logical payload length. */
    private val length: Int

    init {
        this.source = requireNotNullCompat(source, "source")
        requireNotNullCompat(offsets, "offsets")
        requireNotNullCompat(lengths, "lengths")
        require(!(offsets.size == 0 || offsets.size != lengths.size)) { "Payload ranges must be non-empty and have matching arrays" }

        var totalLength = 0L
        for (i in offsets.indices) {
            val offset = offsets[i]
            val rangeLength = lengths[i]
            require(!(offset < 0 || rangeLength < 0 || offset > source.limit() || rangeLength > source.limit() - offset)) { "Payload range outside source at index $i: $offset + $rangeLength" }
            totalLength += rangeLength.toLong()
            require(totalLength <= MAX_PAYLOAD_SIZE) { "Payload exceeds supported byte-array size: $totalLength" }
        }
        this.offsets = offsets.copyOf()
        this.lengths = lengths.copyOf()
        this.length = totalLength.toInt()
    }

    /** Returns the logical payload length.
     * 
     * @return the sum of all extent lengths in bytes
     */
    fun length(): Int {
        return length
    }

    /** Copies this payload into a newly allocated byte array.
     * 
     * @return the complete logical payload bytes
     * @throws AvifIoException if the backing source cannot be read
     */
    fun readBytes(): ByteArray {
        val result = ByteArray(length)
        var destinationOffset = 0
        for (i in offsets.indices) {
            val rangeLength = lengths[i]
            source.readFully(offsets[i], ByteBuf.wrap(result, destinationOffset, rangeLength))
            destinationOffset += rangeLength
        }
        return result
    }

    /** Buffered logical concatenation of payload ranges. */
    class PayloadInput(payloads: Array<AvifPayload>) : BufferedInput() {
        /** The source corresponding to each flattened extent. */
        val sources: Array<AvifDataSource?>

        /** The absolute source offset of each flattened extent. */
        val sourceOffsets: LongArray

        /** The logical exclusive end offset of each flattened extent. */
        val extentEndOffsets: LongArray

        /** The logical exclusive end offset of each payload unit. */
        val payloadEndOffsets: LongArray

        /** The total logical input size. */
        val totalSize: Long

        /** The flattened extent currently being loaded. */
        var extentIndex = 0

        /** The byte count already loaded from the current extent. */
        var extentPosition = 0

        /** The logical position immediately after all bytes loaded into the staging buffer. */
        var loadedPosition: Long = 0

        init {
            requireNotNullCompat(payloads, "payloads")
            var extentCount = 0
            for (i in payloads.indices) {
                extentCount = addExact(
                    extentCount,
                    requireNotNullCompat(
                        payloads[i],
                        "payloads[$i]"
                    ).offsets.size
                )
            }
            this.sources = arrayOfNullsCompat<AvifDataSource?>(extentCount)
            this.sourceOffsets = LongArray(extentCount)
            this.extentEndOffsets = LongArray(extentCount)
            this.payloadEndOffsets = LongArray(payloads.size)

            var flattenedIndex = 0
            var logicalOffset = 0L
            for (payloadIndex in payloads.indices) {
                val payload = payloads[payloadIndex]
                for (rangeIndex in payload.offsets.indices) {
                    sources[flattenedIndex] = payload.source
                    sourceOffsets[flattenedIndex] = payload.offsets[rangeIndex]
                    logicalOffset += payload.lengths[rangeIndex].toLong()
                    extentEndOffsets[flattenedIndex] = logicalOffset
                    flattenedIndex++
                }
                payloadEndOffsets[payloadIndex] = logicalOffset
            }
            this.totalSize = logicalOffset
        }

        /** Fills the staging buffer from the current logical extent position.
         * 
         * @param required the minimum unread byte count needed by the caller
         * @throws AvifIoException if a payload source cannot be read
         */
        override fun fillBuffer(required: Int) {
            val target = prepareForFill(required)
            try {
                while (target.hasRemaining() && extentIndex < sources.size) {
                    val extentStart =
                        if (extentIndex == 0) 0L else extentEndOffsets[extentIndex - 1]
                    val extentLength = toIntExact(extentEndOffsets[extentIndex] - extentStart)
                    val available = extentLength - extentPosition
                    if (available == 0) {
                        extentIndex++
                        extentPosition = 0
                        continue
                    }

                    val chunk = min(target.remaining(), available)
                    val originalLimit = target.limit()
                    target.limit(target.position() + chunk)
                    try {
                        sources[extentIndex]!!.readFully(
                            sourceOffsets[extentIndex] + extentPosition,
                            target
                        )
                    } finally {
                        target.limit(originalLimit)
                    }
                    extentPosition += chunk
                    loadedPosition += chunk.toLong()
                }
            } finally {
                target.flip()
            }
        }

        /** Returns the unread byte count in the current payload unit.
         * 
         * @return the exact unread byte count in the current payload, or zero at end-of-input
         * @throws AvifIoException if this input is closed
         */
        override fun currentUnitRemaining(): Long {
            ensureOpen()
            val consumedPosition = loadedPosition - buffer.remaining()
            var payloadIndex = payloadEndOffsets.binarySearch(consumedPosition + 1L)
            if (payloadIndex < 0) {
                payloadIndex = -payloadIndex - 1
            }
            return if (payloadIndex < payloadEndOffsets.size)
                payloadEndOffsets[payloadIndex] - consumedPosition
            else
                0L
        }

        /** Skips bytes by repositioning the logical extent cursor.
         * 
         * @param len the number of bytes to skip
         * @throws AvifIoException if this input is closed or fewer than `len` bytes remain
         */
        override fun skip(len: Long) {
            require(len >= 0) { "len < 0: $len" }
            ensureOpen()
            if (len == 0L) {
                return
            }
            val consumedPosition = loadedPosition - buffer.remaining()
            if (len > totalSize - consumedPosition) {
                throw EOFException("Unexpected end of input")
            }
            setLogicalPosition(consumedPosition + len)
        }

        /** Repositions this input to one logical payload offset and clears buffered data.
         * 
         * @param position the logical offset in the concatenated payloads
         */
        fun setLogicalPosition(position: Long) {
            clearBuffer()
            loadedPosition = position
            if (position == totalSize) {
                extentIndex = sources.size
                extentPosition = 0
                return
            }
            var index = extentEndOffsets.binarySearch(position + 1L)
            if (index < 0) {
                index = -index - 1
            }
            extentIndex = index
            val extentStart = if (index == 0) 0L else extentEndOffsets[index - 1]
            extentPosition = toIntExact(position - extentStart)
        }

        /** Closes this logical view without closing shared payload sources. */
        override fun close() {
            closed = true
        }
    }

    fun openInput(): BufferedInput {
        return openInput(arrayOf(this))
    }

    companion object {

        /** The largest payload that the byte-array based AV1 packet layer can consume. */
        const val MAX_PAYLOAD_SIZE = Int.MAX_VALUE - 8

        /** Creates a payload over source ranges without copying their contents.
         * 
         * @param source the source containing every range
         * @param offsets the absolute source offsets
         * @param lengths the source-range lengths
         * @return the immutable payload descriptor
         */
        fun ofRanges(
            source: AvifDataSource,
            offsets: LongArray,
            lengths: IntArray
        ): AvifPayload {
            return AvifPayload(source, offsets, lengths)
        }

        /** Opens a buffered input over payloads in sequence.
         * 
         * Each payload remains a separate externally framed input unit as reported by
         * [BufferedInput.currentUnitRemaining()]. Closing the returned input does not close any
         * payload source.
         * 
         * @param payloads the payloads to read in order
         * @return a new input positioned at the first byte of the first payload
         */
        /** Opens a buffered input over this payload.
         * 
         * Closing the returned input does not close the payload's source.
         * 
         * @return a new input positioned at the first payload byte
         */
        fun openInput(payloads: Array<AvifPayload>): BufferedInput {
            return PayloadInput(payloads)
        }
    }
}
