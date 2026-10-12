// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader.PartitionNode
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.FrameAssembly


/** Converts decoded tile-local partition trees into frame-local immutable views.
 * 
 * AV1 entropy decoding uses tile-local block coordinates for neighbor contexts. Pixel
 * reconstruction and postfilters write into full-frame planes, so they must consume equivalent
 * trees whose block, transform, and residual positions are offset by each tile origin. */
object FrameLocalPartitionTrees {
    /** Returns frame-local partition trees for every decoded tile.
     * 
     * @param assembly the frame assembly that owns the tile geometry
     * @param tileRootsByTile the tile-local partition trees grouped by tile index
     * @return frame-local partition trees for every decoded tile
     */
    fun create(
        assembly: FrameAssembly,
        tileRootsByTile: Array<Array<TilePartitionTreeReader.Node>>
    ): Array<Array<TilePartitionTreeReader.Node>> {
        val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
        val nonNullTileRootsByTile: Array<Array<TilePartitionTreeReader.Node>> =
            requireNotNullCompat(
                tileRootsByTile,
                "tileRootsByTile"
            )
        require(nonNullTileRootsByTile.size == nonNullAssembly.totalTiles()) { "tileRootsByTile.length != totalTiles: " + nonNullTileRootsByTile.size }

        val frameLocalRoots: Array<Array<TilePartitionTreeReader.Node>> =
            arrayOfNullsCompat<Array<TilePartitionTreeReader.Node>>(nonNullTileRootsByTile.size)
        for (tileIndex in nonNullTileRootsByTile.indices) {
            frameLocalRoots[tileIndex] = FrameLocalPartitionTrees.create(
                nonNullAssembly,
                tileIndex,
                nonNullTileRootsByTile[tileIndex]
            )
        }
        return frameLocalRoots
    }

    /** Returns frame-local partition roots for one decoded tile.
     * 
     * @param assembly the frame assembly that owns the tile geometry
     * @param tileIndex the zero-based tile index in frame order
     * @param tileRoots the tile-local partition roots for that tile
     * @return frame-local partition roots for one decoded tile
     */
    fun create(
        assembly: FrameAssembly,
        tileIndex: Int,
        tileRoots: Array<TilePartitionTreeReader.Node>
    ): Array<TilePartitionTreeReader.Node> {
        val nonNullTileRoots =
            requireNotNullCompat(tileRoots, "tileRoots")
        val tileOrigin = tileOrigin4(assembly, tileIndex)
        if (tileOrigin.x4 == 0 && tileOrigin.y4 == 0) {
            return nonNullTileRoots.copyOf()
        }

        val frameLocalRoots: Array<TilePartitionTreeReader.Node> =
            arrayOfNullsCompat<TilePartitionTreeReader.Node>(nonNullTileRoots.size)
        for (i in nonNullTileRoots.indices) {
            frameLocalRoots[i] = offsetNode(nonNullTileRoots[i], tileOrigin.x4, tileOrigin.y4)
        }
        return frameLocalRoots
    }

    /** Returns the frame-local tile origin in 4x4 units.
     * 
     * @param assembly the frame assembly that owns the tile geometry
     * @param tileIndex the zero-based tile index in frame order
     * @return the frame-local tile origin in 4x4 units
     */
    fun tileOrigin4(assembly: FrameAssembly, tileIndex: Int): BlockPosition {
        val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
        val tiling = nonNullAssembly.frameHeader().tiling()
        val columns = tiling.columns()
        val rows = tiling.rows()
        if (tileIndex < 0 || tileIndex >= columns * rows) {
            throw IndexOutOfBoundsException("tileIndex out of range: $tileIndex")
        }

        val tileColumn = tileIndex % columns
        val tileRow = tileIndex / columns
        val superblockSize4 =
            if (nonNullAssembly.sequenceHeader().features().use128x128Superblocks()) 32 else 16
        return BlockPosition(
            tiling.columnStartSuperblock(tileColumn) * superblockSize4,
            tiling.rowStartSuperblock(tileRow) * superblockSize4
        )
    }

    /** Returns a frame-local copy of one decoded partition node.
     * 
     * @param node the tile-local partition node
     * @param deltaX4 the X-axis tile-origin offset in 4x4 units
     * @param deltaY4 the Y-axis tile-origin offset in 4x4 units
     * @return a frame-local copy of one decoded partition node
     */
    private fun offsetNode(
        node: TilePartitionTreeReader.Node,
        deltaX4: Int,
        deltaY4: Int
    ): TilePartitionTreeReader.Node {
        val nonNullNode = requireNotNullCompat(node, "node")
        if (deltaX4 == 0 && deltaY4 == 0) {
            return nonNullNode
        }
        if (nonNullNode is TilePartitionTreeReader.LeafNode) {
            val header = nonNullNode.header
            return TilePartitionTreeReader.LeafNode(
                header.withPosition(header.position().offset(deltaX4, deltaY4)),
                nonNullNode.transformLayout.withOffset(deltaX4, deltaY4),
                nonNullNode.residualLayout.withOffset(deltaX4, deltaY4)
            )
        }

        val partitionNode = nonNullNode as PartitionNode
        val offsetChildren: Array<TilePartitionTreeReader.Node> =
            arrayOfNullsCompat<TilePartitionTreeReader.Node>(partitionNode.childCount())
        for (i in offsetChildren.indices) {
            offsetChildren[i] = offsetNode(partitionNode.child(i), deltaX4, deltaY4)
        }
        return PartitionNode(
            partitionNode.position().offset(deltaX4, deltaY4),
            partitionNode.size(),
            partitionNode.partitionType(),
            nullableArray(offsetChildren)
        )
    }
}
