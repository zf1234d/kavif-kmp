// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader.PartitionNode
import org.glavo.avif.internal.av1.model.FrameAssembly


/** Stores one frame's segment identifiers on the padded AV1 4x4 coding grid. */
class SegmentIdMap {
    /** The padded frame width in 4x4 coding units. */
    private val width4: Int

    /** The padded frame height in 4x4 coding units. */
    private val height4: Int

    /** The segment identifiers in row-major order. */
    private val segmentIds: ByteArray

    /** Creates a zero-filled segment-id map.
     * 
     * @param width4 the padded frame width in 4x4 coding units
     * @param height4 the padded frame height in 4x4 coding units
     */
    private constructor(width4: Int, height4: Int) {
        require(!(width4 < 0 || height4 < 0)) { "Negative segment-id map dimensions: ${width4}x$height4" }
        this.width4 = width4
        this.height4 = height4
        this.segmentIds = ByteArray(multiplyExact(width4, height4))
    }

    /** Creates a segment-id map containing a copy of the supplied values.
     * 
     * @param width4 the padded frame width in 4x4 coding units
     * @param height4 the padded frame height in 4x4 coding units
     * @param segmentIds the row-major segment identifiers to copy
     */
    private constructor(width4: Int, height4: Int, segmentIds: ByteArray) {
        this.width4 = width4
        this.height4 = height4
        this.segmentIds = requireNotNullCompat(segmentIds, "segmentIds").copyOf(
            multiplyExact(width4, height4)
        )
    }

    /** Returns an independent copy of this map.
     * 
     * @return an independent segment-id map copy
     */
    fun copy(): SegmentIdMap {
        return SegmentIdMap(width4, height4, segmentIds)
    }

    /** Returns whether this map has the supplied dimensions.
     * 
     * @param expectedWidth4 the expected padded width in 4x4 coding units
     * @param expectedHeight4 the expected padded height in 4x4 coding units
     * @return whether both dimensions match
     */
    fun hasDimensions(expectedWidth4: Int, expectedHeight4: Int): Boolean {
        return width4 == expectedWidth4 && height4 == expectedHeight4
    }

    /** Returns a segment identifier, or zero when the coordinate is outside the map.
     * 
     * @param x4 the frame-relative X coordinate in 4x4 coding units
     * @param y4 the frame-relative Y coordinate in 4x4 coding units
     * @return the stored segment identifier, or zero outside the map
     */
    fun getOrZero(x4: Int, y4: Int): Int {
        if (x4 < 0 || x4 >= width4 || y4 < 0 || y4 >= height4) {
            return 0
        }
        return segmentIds[y4 * width4 + x4].toInt() and 0xFF
    }

    /** Returns the minimum segment identifier in a clipped rectangular block footprint.
     * 
     * @param startX4 the frame-relative block origin X in 4x4 coding units
     * @param startY4 the frame-relative block origin Y in 4x4 coding units
     * @param blockWidth4 the block width in 4x4 coding units
     * @param blockHeight4 the block height in 4x4 coding units
     * @return the minimum segment identifier, or zero when the footprint does not intersect the map
     */
    fun minimum(startX4: Int, startY4: Int, blockWidth4: Int, blockHeight4: Int): Int {
        val endX4 = min(width4, startX4 + blockWidth4)
        val endY4 = min(height4, startY4 + blockHeight4)
        val clippedStartX4 = max(0, startX4)
        val clippedStartY4 = max(0, startY4)
        if (clippedStartX4 >= endX4 || clippedStartY4 >= endY4) {
            return 0
        }

        var minimum = 7
        for (y4 in clippedStartY4..<endY4) {
            val rowOffset = y4 * width4
            for (x4 in clippedStartX4..<endX4) {
                minimum = min(minimum, segmentIds[rowOffset + x4].toInt() and 0xFF)
                if (minimum == 0) {
                    return 0
                }
            }
        }
        return minimum
    }

    /** Fills a clipped rectangular block footprint with one segment identifier.
     * 
     * @param startX4 the frame-relative block origin X in 4x4 coding units
     * @param startY4 the frame-relative block origin Y in 4x4 coding units
     * @param blockWidth4 the block width in 4x4 coding units
     * @param blockHeight4 the block height in 4x4 coding units
     * @param segmentId the segment identifier in `[0, 7]`
     */
    fun fill(startX4: Int, startY4: Int, blockWidth4: Int, blockHeight4: Int, segmentId: Int) {
        require(!(segmentId < 0 || segmentId >= 8)) { "segmentId out of range: $segmentId" }
        val endX4 = min(width4, startX4 + blockWidth4)
        val endY4 = min(height4, startY4 + blockHeight4)
        val clippedStartX4 = max(0, startX4)
        val clippedStartY4 = max(0, startY4)
        for (y4 in clippedStartY4..<endY4) {
            fillCompat(
                segmentIds,
                y4 * width4 + clippedStartX4,
                y4 * width4 + endX4,
                segmentId.toByte()
            )
        }
    }

    /** Replaces this map with another map having identical dimensions.
     * 
     * @param source the source map to copy
     */
    fun copyFrom(source: SegmentIdMap) {
        val nonNullSource = requireNotNullCompat(source, "source")
        require(nonNullSource.hasDimensions(width4, height4)) { "Segment-id map dimensions differ" }
        arraycopy(nonNullSource.segmentIds, 0, segmentIds, 0, segmentIds.size)
    }

    /** Fills the map from one decoded tree node.
     * 
     * @param node the decoded node to visit
     * @param tileStartX4 the tile's frame-relative X origin in 4x4 coding units
     * @param tileStartY4 the tile's frame-relative Y origin in 4x4 coding units
     */
    private fun fillDecodedNode(
        node: TilePartitionTreeReader.Node,
        tileStartX4: Int,
        tileStartY4: Int
    ) {
        if (node is TilePartitionTreeReader.LeafNode) {
            val position = node.position()
            val size = node.size()
            fill(
                tileStartX4 + position.x4,
                tileStartY4 + position.y4,
                size.width4(),
                size.height4(),
                node.header.segmentId()
            )
            return
        }
        val partition = node as PartitionNode
        for (childIndex in 0..<partition.childCount()) {
            fillDecodedNode(partition.child(childIndex), tileStartX4, tileStartY4)
        }
    }

    companion object {
        /** Creates a zero-filled map sized for the supplied frame assembly.
         * 
         * @param assembly the frame assembly whose padded coding grid determines the map dimensions
         * @return a zero-filled segment-id map
         */
        fun create(assembly: FrameAssembly): SegmentIdMap {
            val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
            val width4 = ((nonNullAssembly.frameHeader().frameSize().codedWidth() + 7) shr 3) shl 1
            val height4 = ((nonNullAssembly.frameHeader().frameSize().height() + 7) shr 3) shl 1
            return SegmentIdMap(width4, height4)
        }

        /** Reconstructs a segment-id map from decoded partition-tree leaves.
         * 
         * This fallback is used for results assembled directly by callers. Decoder-produced results
         * retain their exact map separately so `segmentation_update_map = 0` does not lose per-cell
         * identifiers inside a block.
         * 
         * @param assembly the frame assembly that owns the decoded trees
         * @param tileRoots the decoded partition roots for each tile in frame order
         * @return the reconstructed segment-id map
         */
        fun fromDecodedBlocks(
            assembly: FrameAssembly,
            tileRoots: Array<Array<TilePartitionTreeReader.Node>>
        ): SegmentIdMap {
            val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
            val nonNullTileRoots: Array<Array<TilePartitionTreeReader.Node>> =
                requireNotNullCompat(
                    tileRoots,
                    "tileRoots"
                )
            val map: SegmentIdMap = create(nonNullAssembly)
            val tileColumns = nonNullAssembly.frameHeader().tiling().columns()
            val superblockSize4 =
                if (nonNullAssembly.sequenceHeader().features().use128x128Superblocks()) 32 else 16
            val tiling = nonNullAssembly.frameHeader().tiling()
            for (tileIndex in nonNullTileRoots.indices) {
                val tileColumn = tileIndex % tileColumns
                val tileRow = tileIndex / tileColumns
                val startX4 = tiling.columnStartSuperblock(tileColumn) * superblockSize4
                val startY4 = tiling.rowStartSuperblock(tileRow) * superblockSize4
                for (root in requireNotNullCompat(
                    nonNullTileRoots[tileIndex],
                    "tileRoots[$tileIndex]"
                )) {
                    map.fillDecodedNode(
                        requireNotNullCompat(
                            root,
                            "tile root"
                        ), startX4, startY4
                    )
                }
            }
            return map
        }
    }
}
