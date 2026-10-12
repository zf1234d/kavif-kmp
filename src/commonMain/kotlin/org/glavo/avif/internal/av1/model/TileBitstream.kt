// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.bitstream.BitReader
import org.glavo.avif.internal.av1.entropy.MsacDecoder


/** Read-only view over one tile payload inside a tile group. */
class TileBitstream(tileIndex: Int, data: ByteArray, dataOffset: Int, dataLength: Int) {
    /** The zero-based tile index within the frame. */
    private val tileIndex: Int

    /** The backing payload bytes that contain the tile payload. */
    private val data: ByteArray

    /** The first tile byte inside `data`. */
    private val dataOffset: Int

    /** The byte length of the tile payload. */
    private val dataLength: Int

    init {
        require(tileIndex >= 0) { "tileIndex < 0: $tileIndex" }
        this.data = requireNotNullCompat(data, "data")
        require(dataOffset in 0..data.size) { "dataOffset out of range: $dataOffset" }
        require(!(dataLength < 0 || dataOffset + dataLength > data.size)) { "dataLength out of range: $dataLength" }
        this.tileIndex = tileIndex
        this.dataOffset = dataOffset
        this.dataLength = dataLength
    }

    /** Returns the zero-based tile index within the frame.
     * 
     * @return the zero-based tile index within the frame
     */
    fun tileIndex(): Int {
        return tileIndex
    }

    /** Returns the first tile byte inside the backing payload.
     * 
     * @return the first tile byte inside the backing payload
     */
    fun dataOffset(): Int {
        return dataOffset
    }

    /** Returns the byte length of the tile payload.
     * 
     * @return the byte length of the tile payload
     */
    fun dataLength(): Int {
        return dataLength
    }

    /** Opens a fresh bit reader over the tile payload.
     * 
     * @return a fresh bit reader over the tile payload
     */
    fun openBitReader(): BitReader {
        return BitReader(data, dataOffset, dataLength)
    }

    /** Opens a fresh AV1 multi-symbol arithmetic decoder over the tile payload.
     * 
     * @param disableCdfUpdate whether CDF updates are disabled for the active frame
     * @return a fresh arithmetic decoder over the tile payload
     */
    fun openMsacDecoder(disableCdfUpdate: Boolean): MsacDecoder {
        return MsacDecoder(data, dataOffset, dataLength, disableCdfUpdate)
    }

    /** Returns a copy of the tile payload bytes.
     * 
     * @return a copy of the tile payload bytes
     */
    fun copyBytes(): ByteArray {
        return copyOfRangeCompat(data, dataOffset, dataOffset + dataLength)
    }

    /** Returns a content hash code for the tile payload bytes.
     * 
     * @return the payload content hash code
     */
    fun payloadHashCode(): Int {
        var hashCode = 1
        val dataEnd = dataOffset + dataLength
        for (index in dataOffset..<dataEnd) {
            hashCode = 31 * hashCode + data[index]
        }
        return hashCode
    }

    /** Returns whether another tile bitstream contains the same payload bytes.
     * 
     * Tile indices are not compared.
     * 
     * @param other the tile bitstream to compare
     * @return whether both payload byte ranges have identical contents
     */
    fun hasSamePayload(other: TileBitstream): Boolean {
        val checkedOther = requireNotNullCompat(other, "other")
        return dataLength == checkedOther.dataLength
                && contentEqualsCompat(
            data,
            dataOffset,
            dataOffset + dataLength,
            checkedOther.data,
            checkedOther.dataOffset,
            checkedOther.dataOffset + checkedOther.dataLength
        )
    }
}
