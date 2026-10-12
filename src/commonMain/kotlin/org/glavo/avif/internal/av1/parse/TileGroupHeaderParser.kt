// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.parse

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.av1.Av1DecodeErrorCode
import org.glavo.avif.av1.Av1DecodeException
import org.glavo.avif.av1.Av1DecodeStage
import org.glavo.avif.internal.av1.bitstream.BitReader
import org.glavo.avif.internal.av1.bitstream.ObuPacket
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.TileGroupHeader


/** Parser for AV1 tile-group headers embedded inside `TILE_GROUP` and `FRAME` OBUs. */
object TileGroupHeaderParser {
    /** Parses a tile-group header from the supplied bit reader.
     * 
     * @param reader the payload bit reader positioned at the tile-group header
     * @param obu the source OBU packet
     * @param frameHeader the active frame header
     * @return the parsed tile-group header
     * @throws AvifIoException if the payload is truncated or invalid
     */
    fun parse(reader: BitReader, obu: ObuPacket, frameHeader: FrameHeader): TileGroupHeader {
        requireNotNullCompat(reader, "reader")
        requireNotNullCompat(obu, "obu")
        requireNotNullCompat(frameHeader, "frameHeader")

        val totalTiles = frameHeader.tiling().columns() * frameHeader.tiling().rows()
        if (totalTiles <= 0) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.FRAME_ASSEMBLY,
                "Frame header does not describe any tiles",
                obu.streamOffset,
                obu.obuIndex,
                null
            )
        }

        try {
            val explicitTilePositions = totalTiles > 1 && reader.readFlag()
            val startTileIndex: Int
            val endTileIndex: Int
            if (explicitTilePositions) {
                val indexBitCount =
                    frameHeader.tiling().log2Cols() + frameHeader.tiling().log2Rows()
                startTileIndex = toIntExact(reader.readBits(indexBitCount))
                endTileIndex = toIntExact(reader.readBits(indexBitCount))
            } else {
                startTileIndex = 0
                endTileIndex = totalTiles - 1
            }

            if (startTileIndex > endTileIndex) {
                throw invalidBitstream(
                    obu,
                    "Tile-group start index exceeds end index: $startTileIndex > $endTileIndex"
                )
            }
            if (endTileIndex >= totalTiles) {
                throw invalidBitstream(
                    obu,
                    "Tile-group end index exceeds the frame tile count: $endTileIndex >= $totalTiles"
                )
            }

            return TileGroupHeader(explicitTilePositions, startTileIndex, endTileIndex, totalTiles)
        } catch (ex: EOFException) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                Av1DecodeStage.FRAME_ASSEMBLY,
                "Unexpected end of tile-group header",
                obu.streamOffset,
                obu.obuIndex,
                null,
                ex
            )
        }
    }

    /** Creates a contextual invalid-bitstream exception for tile-group assembly errors.
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
