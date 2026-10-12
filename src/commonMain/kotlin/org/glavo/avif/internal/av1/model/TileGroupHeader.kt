// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Parsed AV1 tile-group header state for a single OBU.
 * 
 * @param explicitTilePositions whether explicit tile positions were signaled
 * @param startTileIndex the first tile index covered by this tile group
 * @param endTileIndex the last tile index covered by this tile group
 * @param totalTileCount the total tile count declared by the frame header
 */
data class TileGroupHeader(
    val explicitTilePositions: Boolean,
    val startTileIndex: Int,
    val endTileIndex: Int,
    val totalTileCount: Int
) {
    /** Returns the number of tiles covered by this tile group.
     * 
     * @return the number of tiles covered by this tile group
     */
    fun tileCount(): Int {
        return endTileIndex - startTileIndex + 1
    }

    /** Creates parsed tile-group header state. */
    init {
        require(totalTileCount > 0) { "totalTileCount <= 0: $totalTileCount" }
        require(!(startTileIndex < 0 || startTileIndex >= totalTileCount)) { "startTileIndex out of range: $startTileIndex" }
        require(!(endTileIndex < startTileIndex || endTileIndex >= totalTileCount)) { "endTileIndex out of range: $endTileIndex" }
    }
}
