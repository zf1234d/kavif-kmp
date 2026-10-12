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
import org.glavo.avif.internal.av1.bitstream.ObuType
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.SequenceHeader
import org.glavo.avif.internal.av1.model.TileBitstream
import org.glavo.avif.internal.av1.model.TileList


/** Parses AV1 Large Scale Tile list OBUs against a common camera frame header. */
object TileListParser {
    /** The maximum tile-list entry count permitted by the AV1 specification. */
    private const val MAX_TILE_LIST_ENTRIES = 512

    /** Parses one tile-list OBU.
     * 
     * @param obu the source tile-list OBU
     * @param sequenceHeader the sequence header associated with the common camera frame
     * @param cameraFrameHeader the active common camera frame header
     * @param strictStdCompliance whether Large Scale Tile conformance constraints must be enforced
     * @return the parsed tile list
     * @throws Av1DecodeException if the payload or an entry violates tile-list syntax
     */
    fun parse(
        obu: ObuPacket,
        sequenceHeader: SequenceHeader,
        cameraFrameHeader: FrameHeader,
        strictStdCompliance: Boolean
    ): TileList {
        requireNotNullCompat(obu, "obu")
        requireNotNullCompat(sequenceHeader, "sequenceHeader")
        requireNotNullCompat(cameraFrameHeader, "cameraFrameHeader")
        require(obu.header.type == ObuType.TILE_LIST) { "OBU type is not TILE_LIST: " + obu.header.type }
        validateCameraFrame(obu, sequenceHeader, cameraFrameHeader, strictStdCompliance)

        val payload = obu.payload
        if (payload.size < 4) {
            throw invalidBitstream(obu, "Tile-list header is truncated")
        }
        val outputTileColumns = (payload[0].toInt() and 0xFF) + 1
        val outputTileRows = (payload[1].toInt() and 0xFF) + 1
        val tileCount = readUnsigned16(payload, 2) + 1
        if (tileCount > MAX_TILE_LIST_ENTRIES) {
            throw invalidBitstream(obu, "Tile-list entry count exceeds 512")
        }
        if (tileCount > outputTileColumns.toLong() * outputTileRows) {
            throw invalidBitstream(obu, "Tile-list entries exceed the output tile grid")
        }

        val tiling = cameraFrameHeader.tiling()
        val entries: MutableList<TileList.Entry> = ArrayList<TileList.Entry>(tileCount)
        var cursor = 4
        for (tile in 0..<tileCount) {
            if (cursor + 5 > payload.size) {
                throw invalidBitstream(obu, "Tile-list entry header is truncated")
            }
            val anchorFrameIndex = payload[cursor].toInt() and 0xFF
            val tileRow = payload[cursor + 1].toInt() and 0xFF
            val tileColumn = payload[cursor + 2].toInt() and 0xFF
            val tileDataLength = readUnsigned16(payload, cursor + 3) + 1
            cursor += 5

            if (anchorFrameIndex >= 128) {
                throw invalidBitstream(obu, "Tile-list anchor frame index exceeds 127")
            }
            if (tileRow >= tiling.rows()) {
                throw invalidBitstream(
                    obu,
                    "Tile-list source row exceeds the camera frame tile grid"
                )
            }
            if (tileColumn >= tiling.columns()) {
                throw invalidBitstream(
                    obu,
                    "Tile-list source column exceeds the camera frame tile grid"
                )
            }
            if (tileDataLength > payload.size - cursor) {
                throw invalidBitstream(obu, "Tile-list coded tile data is truncated")
            }

            val tileIndex = tileRow * tiling.columns() + tileColumn
            entries.add(
                TileList.Entry(
                    anchorFrameIndex,
                    tileRow,
                    tileColumn,
                    TileBitstream(tileIndex, payload, cursor, tileDataLength)
                )
            )
            cursor += tileDataLength
        }
        if (cursor != payload.size) {
            throw invalidBitstream(obu, "Tile-list OBU has trailing payload bytes")
        }
        return TileList(outputTileColumns, outputTileRows, entries)
    }

    /** Validates the common camera frame when strict Large Scale Tile conformance is enabled.
     * 
     * @param obu the OBU used for error context
     * @param sequenceHeader the sequence header associated with the camera frame
     * @param cameraFrameHeader the common camera frame header
     * @param strictStdCompliance whether conformance constraints must be enforced
     * @throws Av1DecodeException if strict validation finds a nonconformant value
     */
    fun validateCameraFrame(
        obu: ObuPacket,
        sequenceHeader: SequenceHeader,
        cameraFrameHeader: FrameHeader,
        strictStdCompliance: Boolean
    ) {
        requireNotNullCompat(obu, "obu")
        requireNotNullCompat(sequenceHeader, "sequenceHeader")
        requireNotNullCompat(cameraFrameHeader, "cameraFrameHeader")
        if (!strictStdCompliance) {
            return
        }
        try {
            FrameHeaderConformanceValidator.validateLargeScaleTileFrame(
                sequenceHeader,
                cameraFrameHeader
            )
        } catch (exception: AvifIoException) {
            throw TileListParser.invalidBitstream(obu, exception.message!!)
        }
    }

    /** Reads one big-endian unsigned 16-bit literal.
     * 
     * @param data the source payload
     * @param offset the first source byte
     * @return the unsigned literal
     */
    private fun readUnsigned16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) shl 8 or (data[offset + 1].toInt() and 0xFF)
    }

    /** Creates a contextual invalid-bitstream exception for tile-list errors.
     * 
     * @param obu the source OBU
     * @param message the detailed validation message
     * @return the contextual exception
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
