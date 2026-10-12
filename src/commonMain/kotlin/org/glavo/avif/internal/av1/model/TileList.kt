// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Describes one parsed AV1 Large Scale Tile list OBU.
 * 
 * @param outputTileColumns the output-frame width in tiles
 * @param outputTileRows the output-frame height in tiles
 * @param entries the coded tiles in output raster order
 */
class TileList(val outputTileColumns: Int, val outputTileRows: Int, entries: MutableList<Entry>) {
    /** Describes one coded tile and the external anchor frame used for its inter prediction.
     * 
     * @param anchorFrameIndex the zero-based external anchor-frame index
     * @param tileRow the source tile row in the common camera frame
     * @param tileColumn the source tile column in the common camera frame
     * @param bitstream the coded tile payload
     */
    data class Entry(
        val anchorFrameIndex: Int,
        val tileRow: Int,
        val tileColumn: Int,
        val bitstream: TileBitstream
    ) {
        /** Creates one validated tile-list entry. */
        init {
            require(!(anchorFrameIndex < 0 || anchorFrameIndex >= 128)) { "anchorFrameIndex out of range: $anchorFrameIndex" }
            require(!(tileRow < 0 || tileRow >= 64)) { "tileRow out of range: $tileRow" }
            require(!(tileColumn < 0 || tileColumn >= 64)) { "tileColumn out of range: $tileColumn" }
            requireNotNullCompat(bitstream, "bitstream")
        }
    }

    val entries: MutableList<Entry>

    /** Creates a validated immutable tile list. */
    init {
        var entries = entries
        require(!(outputTileColumns <= 0 || outputTileColumns > 256)) { "outputTileColumns out of range: $outputTileColumns" }
        require(!(outputTileRows <= 0 || outputTileRows > 256)) { "outputTileRows out of range: $outputTileRows" }
        entries = requireNotNullCompat(entries, "entries").toMutableList()
        require(!(entries.isEmpty() || entries.size > outputTileColumns.toLong() * outputTileRows)) { "Tile-list entry count exceeds its output tile grid" }
        this.entries = entries
    }
}
