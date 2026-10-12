// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.parse

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.av1.Av1DecodeErrorCode
import org.glavo.avif.av1.Av1DecodeException
import org.glavo.avif.av1.Av1DecodeStage
import org.glavo.avif.internal.av1.bitstream.ObuPacket
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.TileBitstream
import org.glavo.avif.internal.av1.model.TileGroupHeader


/** Parser that converts AV1 tile-data layout into per-tile bitstream views. */
object TileBitstreamParser {
    /** Parses per-tile bitstream views from a tile-group payload.
     * 
     * @param obu the source OBU packet
     * @param frameHeader the active frame header
     * @param tileGroupHeader the parsed tile-group header
     * @param tileDataOffset the byte offset of the tile-data section inside the OBU payload
     * @return the parsed per-tile bitstream views
     * @throws Av1DecodeException if the tile-data layout is malformed
     */
    fun parse(
        obu: ObuPacket,
        frameHeader: FrameHeader,
        tileGroupHeader: TileGroupHeader,
        tileDataOffset: Int
    ): Array<TileBitstream> {
        requireNotNullCompat(obu, "obu")
        requireNotNullCompat(frameHeader, "frameHeader")
        requireNotNullCompat(tileGroupHeader, "tileGroupHeader")

        val payload = obu.payload
        if (tileDataOffset < 0 || tileDataOffset > payload.size) {
            throw invalidBitstream(obu, "Tile-data offset exceeds the OBU payload length")
        }

        val tileCount = tileGroupHeader.tileCount()
        val sizeBytes = frameHeader.tiling().sizeBytes()
        if (tileCount > 1 && sizeBytes <= 0) {
            throw invalidBitstream(
                obu,
                "Multi-tile groups require size bytes in the active frame header"
            )
        }

        val bitstreams: Array<TileBitstream> = arrayOfNullsCompat<TileBitstream>(tileCount)
        var cursor = tileDataOffset
        for (i in 0..<tileCount) {
            val tileDataLength: Int
            if (i == tileCount - 1) {
                tileDataLength = payload.size - cursor
            } else {
                if (cursor + sizeBytes > payload.size) {
                    throw invalidBitstream(obu, "Tile size table exceeds the OBU payload length")
                }

                var tileDataLengthMinusOne = 0
                for (byteIndex in 0..<sizeBytes) {
                    tileDataLengthMinusOne =
                        tileDataLengthMinusOne or ((payload[cursor + byteIndex].toInt() and 0xFF) shl (byteIndex * Byte.SIZE_BITS))
                }
                cursor += sizeBytes
                tileDataLength = tileDataLengthMinusOne + 1
                if (cursor + tileDataLength > payload.size) {
                    throw invalidBitstream(obu, "Tile payload exceeds the OBU payload length")
                }
            }

            bitstreams[i] = TileBitstream(
                tileGroupHeader.startTileIndex + i,
                payload,
                cursor,
                tileDataLength
            )
            cursor += tileDataLength
        }
        return bitstreams
    }

    /** Creates a contextual invalid-bitstream exception for tile-data layout errors.
     * 
     * @param obu the source OBU packet
     * @param message the detailed validation message
     * @return the contextual invalid-bitstream exception
     */
    private fun invalidBitstream(obu: ObuPacket, message: String): Av1DecodeException {
        return Av1DecodeException(
            Av1DecodeErrorCode.INVALID_BITSTREAM,
            Av1DecodeStage.FRAME_ASSEMBLY,
            message,
            obu.streamOffset,
            obu.obuIndex,
            null
        )
    }
}
