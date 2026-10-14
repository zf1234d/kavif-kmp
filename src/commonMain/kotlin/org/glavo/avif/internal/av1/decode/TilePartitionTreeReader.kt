// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader.BlockHeader
import org.glavo.avif.internal.av1.decode.TileSyntaxReader.PartitionBlockLevel
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.InterMotionVector
import org.glavo.avif.internal.av1.model.PartitionType
import org.glavo.avif.internal.av1.model.ResidualLayout
import org.glavo.avif.internal.av1.model.TransformLayout


/** Recursively decodes one tile bitstream into a frame-relative AV1 partition tree.
 * 
 * Entropy and neighbor-context operations use tile-relative coordinates while decoding. Nodes retained
 * in the returned tree use frame-relative coordinates so reconstruction does not need a second tree.
 * 
 * @param strictStdCompliance whether decoded conformance values outside their ranges must be rejected
 */
class TilePartitionTreeReader constructor(
    tileContext: TileDecodeContext,
    strictStdCompliance: Boolean = false
) {
    /** The tile-local decode state that owns the active tile bitstream. */
    private val tileContext: TileDecodeContext

    /** The mutable neighbor context used while scanning the tile. */
    private val neighborContext: BlockNeighborContext

    /** The typed entropy syntax reader used for partition symbols. */
    private val syntaxReader: TileSyntaxReader

    /** The leaf block header reader used for terminal blocks. */
    private val blockHeaderReader: TileBlockHeaderReader

    /** The transform-layout reader used after terminal block headers are decoded. */
    private val transformLayoutReader: TileTransformLayoutReader

    /** The residual syntax reader used after transform layouts are decoded. */
    private val residualSyntaxReader: TileResidualSyntaxReader

    /** The loop-restoration syntax reader used before each superblock partition tree. */
    private val loopRestorationReader: TileLoopRestorationReader

    /** The tile width in AV1 4x4 coding units after 8x8 frame-grid rounding. */
    private val tileWidth4: Int

    /** The tile height in AV1 4x4 coding units after 8x8 frame-grid rounding. */
    private val tileHeight4: Int

    /** The frame-relative X origin of the tile in AV1 4x4 coding units. */
    private val frameOffsetX4: Int

    /** The frame-relative Y origin of the tile in AV1 4x4 coding units. */
    private val frameOffsetY4: Int

    init {
        val nonNullTileContext =
            requireNotNullCompat(tileContext, "tileContext")
        this.tileContext = nonNullTileContext
        this.neighborContext = BlockNeighborContext.Companion.create(nonNullTileContext)
        this.syntaxReader = TileSyntaxReader(nonNullTileContext)
        this.blockHeaderReader = TileBlockHeaderReader(nonNullTileContext, strictStdCompliance)
        this.transformLayoutReader = TileTransformLayoutReader(nonNullTileContext)
        this.residualSyntaxReader =
            TileResidualSyntaxReader(nonNullTileContext, strictStdCompliance)
        this.loopRestorationReader = TileLoopRestorationReader(nonNullTileContext, syntaxReader)
        this.tileWidth4 = nonNullTileContext.codedWidth4()
        this.tileHeight4 = nonNullTileContext.codedHeight4()
        this.frameOffsetX4 = nonNullTileContext.startX() shr 2
        this.frameOffsetY4 = nonNullTileContext.startY() shr 2
    }

    /** Returns the tile-local decode state that owns this tree reader.
     * 
     * @return the tile-local decode state that owns this tree reader
     */
    fun tileContext(): TileDecodeContext {
        return tileContext
    }

    /** Returns the mutable neighbor context used while scanning the tile.
     * 
     * @return the mutable neighbor context used while scanning the tile
     */
    fun neighborContext(): BlockNeighborContext {
        return neighborContext
    }

    /** Reads the current tile into top-level superblock nodes in raster order.
     * 
     * @return the frame-relative top-level superblock nodes in raster order
     */
    fun readTile(): Array<Node> {
        val rootLevel = if (tileContext.superblockSize() == 128)
            SquareBlockLevel.BLOCK_128X128
        else
            SquareBlockLevel.BLOCK_64X64
        val rootSize4 = rootLevel.size4()
        val superblockColumns = (tileWidth4 + rootSize4 - 1) / rootSize4
        val superblockRows = (tileHeight4 + rootSize4 - 1) / rootSize4
        val nodes: MutableList<Node> = ArrayList<Node>(superblockColumns * superblockRows)
        for (row in 0..<superblockRows) {
            for (column in 0..<superblockColumns) {
                val position = BlockPosition(column * rootSize4, row * rootSize4)
                loopRestorationReader.readSuperblock(position, rootSize4, rootSize4)
                val node = readSquare(rootLevel, position)
                if (node != null) {
                    nodes.add(node)
                }
            }
        }
        return nodes.toTypedArray<Node>()
    }

    /** Recursively reads one square partition node.
     * 
     * @param level the current square block level
     * @param position the current square block origin
     * @return the recursively decoded square partition node, or `null` when fully outside the tile
     */
    private fun readSquare(level: SquareBlockLevel, position: BlockPosition): Node? {
        if (position.x4 >= tileWidth4 || position.y4 >= tileHeight4) {
            return null
        }

        val childSize4 = level.childSize4()
        val haveHorizontalSplit = position.x4 + childSize4 < tileWidth4
        val haveVerticalSplit = position.y4 + childSize4 < tileHeight4

        if (!haveHorizontalSplit && !haveVerticalSplit) {
            if (level == SquareBlockLevel.BLOCK_8X8) {
                return readSplitAtEight(position, level)
            }
            return readSquare(level.childLevel(), position)
        }

        if (level == SquareBlockLevel.BLOCK_8X8 && (!haveHorizontalSplit || !haveVerticalSplit)) {
            return readSplitAtEight(position, level)
        }

        val partitionContext = neighborContext.partitionContext(level.partitionShift(), position)
        val partitionType: PartitionType?
        if (haveHorizontalSplit && haveVerticalSplit) {
            partitionType = syntaxReader.readPartition(level.syntaxBlockLevel(), partitionContext)
        } else if (haveHorizontalSplit) {
            partitionType = if (syntaxReader.readHorizontalSplitOnly(
                    level.syntaxBlockLevel(),
                    partitionContext
                )
            )
                PartitionType.SPLIT
            else
                PartitionType.HORIZONTAL
        } else {
            partitionType =
                if (syntaxReader.readVerticalSplitOnly(level.syntaxBlockLevel(), partitionContext))
                    PartitionType.SPLIT
                else
                    PartitionType.VERTICAL
        }
        val node = when (partitionType) {
            PartitionType.NONE -> leaf(position, level.noneSize())

            PartitionType.HORIZONTAL -> partition(
                position, level, partitionType,
                leaf(position, level.horizontalTopSize()),
                leaf(position.offset(0, childSize4), level.horizontalBottomSize())
            )

            PartitionType.VERTICAL -> partition(
                position, level, partitionType,
                leaf(position, level.verticalLeftSize()),
                leaf(position.offset(childSize4, 0), level.verticalRightSize())
            )

            PartitionType.SPLIT -> {
                if (level == SquareBlockLevel.BLOCK_8X8) {
                    readSplitAtEight(position, level)
                } else {
                    partition(
                        position, level, partitionType,
                        readSquare(level.childLevel(), position),
                        readSquare(level.childLevel(), position.offset(childSize4, 0)),
                        readSquare(level.childLevel(), position.offset(0, childSize4)),
                        readSquare(level.childLevel(), position.offset(childSize4, childSize4))
                    )
                }
            }

            PartitionType.T_TOP_SPLIT -> partition(
                position, level, partitionType,
                leaf(position, level.topLeftSquareSize()),
                leaf(position.offset(childSize4, 0), level.topRightSquareSize()),
                leaf(position.offset(0, childSize4), level.horizontalBottomSize())
            )

            PartitionType.T_BOTTOM_SPLIT -> partition(
                position, level, partitionType,
                leaf(position, level.horizontalTopSize()),
                leaf(position.offset(0, childSize4), level.bottomLeftSquareSize()),
                leaf(position.offset(childSize4, childSize4), level.bottomRightSquareSize())
            )

            PartitionType.T_LEFT_SPLIT -> partition(
                position, level, partitionType,
                leaf(position, level.topLeftSquareSize()),
                leaf(position.offset(0, childSize4), level.bottomLeftSquareSize()),
                leaf(position.offset(childSize4, 0), level.verticalRightSize())
            )

            PartitionType.T_RIGHT_SPLIT -> partition(
                position, level, partitionType,
                leaf(position, level.verticalLeftSize()),
                leaf(position.offset(childSize4, 0), level.topRightSquareSize()),
                leaf(position.offset(childSize4, childSize4), level.bottomRightSquareSize())
            )

            PartitionType.HORIZONTAL_4 -> partition(
                position, level, partitionType,
                leaf(position, level.horizontalFourWaySize()),
                leaf(position.offset(0, level.quarterSize4()), level.horizontalFourWaySize()),
                leaf(position.offset(0, level.quarterSize4() * 2), level.horizontalFourWaySize()),
                leaf(position.offset(0, level.quarterSize4() * 3), level.horizontalFourWaySize())
            )

            PartitionType.VERTICAL_4 -> partition(
                position, level, partitionType,
                leaf(position, level.verticalFourWaySize()),
                leaf(position.offset(level.quarterSize4(), 0), level.verticalFourWaySize()),
                leaf(position.offset(level.quarterSize4() * 2, 0), level.verticalFourWaySize()),
                leaf(position.offset(level.quarterSize4() * 3, 0), level.verticalFourWaySize())
            )
        }

        if (partitionType != PartitionType.SPLIT || level == SquareBlockLevel.BLOCK_8X8) {
            neighborContext.updatePartition(
                position,
                level.span8(),
                ABOVE_PARTITION_EDGE_VALUES[level.tableIndex()][partitionType.symbolIndex()],
                LEFT_PARTITION_EDGE_VALUES[level.tableIndex()][partitionType.symbolIndex()]
            )
        }
        return node
    }

    /** Reads the mandatory 8x8 split into 4x4 leaf nodes.
     * 
     * @param position the current 8x8 block origin
     * @param level the current 8x8 square block level
     * @return the partition node that represents the 8x8 split
     */
    private fun readSplitAtEight(position: BlockPosition, level: SquareBlockLevel): PartitionNode {
        return partition(
            position, level, PartitionType.SPLIT,
            leaf(position, BlockSize.SIZE_4X4),
            leaf(position.offset(1, 0), BlockSize.SIZE_4X4),
            leaf(position.offset(0, 1), BlockSize.SIZE_4X4),
            leaf(position.offset(1, 1), BlockSize.SIZE_4X4)
        )
    }

    /** Reads one coded leaf block.
     * 
     * @param position the local tile-relative block origin
     * @param size the block size to read
     * @return the leaf node for the supplied block, or `null` when fully outside the coded tile grid
     */
    private fun leaf(position: BlockPosition, size: BlockSize): LeafNode? {
        if (position.x4 >= tileWidth4 || position.y4 >= tileHeight4) {
            return null
        }
        val header = blockHeaderReader.read(position, size, neighborContext, false)
        if (header.useIntrabc()) {
            IntrabcDisplacementValidator.requireValid(
                tileContext,
                position,
                size,
                requireNotNullCompat(
                    header.motionVector0(),
                    "header.motionVector0()"
                ).vector,
                header.hasChroma()
            )
        }
        val transformLayout = transformLayoutReader.read(header, neighborContext)
        val residualLayout = residualSyntaxReader.read(header, transformLayout, neighborContext)
        neighborContext.updateFromBlockHeader(header)
        neighborContext.updateDefaultTransformContext(position, size)
        if (header.intra() || header.useIntrabc()) {
            val uniformLumaTransformSize =
                transformLayout.uniformLumaTransformSize()
            if (uniformLumaTransformSize != null) {
                if (header.intra()) {
                    neighborContext.updateIntraTransformContext(
                        position,
                        size,
                        uniformLumaTransformSize
                    )
                    neighborContext.updateInterTransformContext(
                        position,
                        size.width4(),
                        size.height4(),
                        uniformLumaTransformSize
                    )
                }
            }
        }
        if (frameOffsetX4 == 0 && frameOffsetY4 == 0) {
            return LeafNode(header, transformLayout, residualLayout)
        }
        header.relocatePosition(transformLayout.position())
        return LeafNode(header, transformLayout, residualLayout)
    }

    /** Creates a partition node and drops children that fell fully outside the tile.
     * 
     * @param position the current square block origin
     * @param level the current square block level
     * @param partitionType the decoded partition type
     * @param children the partition children, some of which may be `null`
     * @return the created partition node
     */
    private fun partition(
        position: BlockPosition,
        level: SquareBlockLevel,
        partitionType: PartitionType,
        vararg children: Node?
    ): PartitionNode {
        var visibleChildCount = 0
        for (child in children) {
            if (child != null) {
                visibleChildCount++
            }
        }
        val visibleChildren: Array<Node?>?
        if (visibleChildCount == children.size) {
            visibleChildren = Array(children.size) { children[it] }
        } else {
            visibleChildren = arrayOfNullsCompat<Node?>(visibleChildCount)
            var destinationIndex = 0
            for (child in children) {
                if (child != null) {
                    visibleChildren[destinationIndex++] = child
                }
            }
        }
        return PartitionNode(
            position.offset(frameOffsetX4, frameOffsetY4),
            level.squareSize(),
            partitionType,
            visibleChildren,
            false
        )
    }

    /** One square block level used by the recursive partition reader. */
    internal enum class SquareBlockLevel
    /** Creates one square block level entry.
     * 
     * @param tableIndex the index used for the partition-edge lookup tables
     * @param size4 the square block size in 4x4 units
     * @param span8 the partition edge span in 8x8 units
     * @param partitionShift the bit shift used by `BlockNeighborContext.partitionContext`
     * @param syntaxBlockLevel the syntax reader partition level used for this square block level
     * @param noneSize the unsplit square block size
     * @param horizontalSize the horizontal partition block size
     * @param verticalSize the vertical partition block size
     * @param horizontalFourWaySize the four-way horizontal stripe size
     * @param verticalFourWaySize the four-way vertical stripe size
     */(
        /** The index used for the partition-edge lookup tables. */
        val tableIndex: Int,
        /** The square block size in 4x4 units. */
        val size4: Int,
        /** The partition edge span in 8x8 units. */
        val span8: Int,
        /** The bit shift used by `BlockNeighborContext.partitionContext`. */
        val partitionShift: Int,
        /** The syntax reader partition level used for this square block level. */
        val syntaxBlockLevel: PartitionBlockLevel,
        /** The unsplit square block size. */
        val noneSize: BlockSize,
        /** The horizontal partition block size. */
        val horizontalSize: BlockSize,
        /** The vertical partition block size. */
        val verticalSize: BlockSize,
        /** The four-way horizontal stripe size. */
        val horizontalFourWaySize: BlockSize,
        /** The four-way vertical stripe size. */
        val verticalFourWaySize: BlockSize
    ) {
        /** 128x128 square blocks. */
        BLOCK_128X128(
            0, 32, 16, 4, PartitionBlockLevel.BLOCK_128X128, BlockSize.SIZE_128X128,
            BlockSize.SIZE_128X64, BlockSize.SIZE_64X128, BlockSize.SIZE_64X16, BlockSize.SIZE_16X64
        ),

        /** 64x64 square blocks. */
        BLOCK_64X64(
            1, 16, 8, 3, PartitionBlockLevel.BLOCK_64X64, BlockSize.SIZE_64X64,
            BlockSize.SIZE_64X32, BlockSize.SIZE_32X64, BlockSize.SIZE_64X16, BlockSize.SIZE_16X64
        ),

        /** 32x32 square blocks. */
        BLOCK_32X32(
            2, 8, 4, 2, PartitionBlockLevel.BLOCK_32X32, BlockSize.SIZE_32X32,
            BlockSize.SIZE_32X16, BlockSize.SIZE_16X32, BlockSize.SIZE_32X8, BlockSize.SIZE_8X32
        ),

        /** 16x16 square blocks. */
        BLOCK_16X16(
            3, 4, 2, 1, PartitionBlockLevel.BLOCK_16X16, BlockSize.SIZE_16X16,
            BlockSize.SIZE_16X8, BlockSize.SIZE_8X16, BlockSize.SIZE_16X4, BlockSize.SIZE_4X16
        ),

        /** 8x8 square blocks. */
        BLOCK_8X8(
            4, 2, 1, 0, PartitionBlockLevel.BLOCK_8X8, BlockSize.SIZE_8X8,
            BlockSize.SIZE_8X4, BlockSize.SIZE_4X8, BlockSize.SIZE_4X4, BlockSize.SIZE_4X4
        );

        /** Returns the index used for the partition-edge lookup tables.
         * 
         * @return the index used for the partition-edge lookup tables
         */
        fun tableIndex(): Int {
            return tableIndex
        }

        /** Returns the square block size in 4x4 units.
         * 
         * @return the square block size in 4x4 units
         */
        fun size4(): Int {
            return size4
        }

        /** Returns the child square size in 4x4 units.
         * 
         * @return the child square size in 4x4 units
         */
        fun childSize4(): Int {
            return size4 shr 1
        }

        /** Returns the four-way stripe size in 4x4 units.
         * 
         * @return the four-way stripe size in 4x4 units
         */
        fun quarterSize4(): Int {
            return size4 shr 2
        }

        /** Returns the partition edge span in 8x8 units.
         * 
         * @return the partition edge span in 8x8 units
         */
        fun span8(): Int {
            return span8
        }

        /** Returns the bit shift used by `BlockNeighborContext.partitionContext`.
         * 
         * @return the bit shift used by `BlockNeighborContext.partitionContext`
         */
        fun partitionShift(): Int {
            return partitionShift
        }

        /** Returns the syntax reader partition level used for this square block level.
         * 
         * @return the syntax reader partition level used for this square block level
         */
        fun syntaxBlockLevel(): PartitionBlockLevel {
            return syntaxBlockLevel
        }

        /** Returns the unsplit square block size.
         * 
         * @return the unsplit square block size
         */
        fun noneSize(): BlockSize {
            return noneSize
        }

        /** Returns the square block size model for this level.
         * 
         * @return the square block size model for this level
         */
        fun squareSize(): BlockSize {
            return noneSize
        }

        /** Returns the top block size for a horizontal partition.
         * 
         * @return the top block size for a horizontal partition
         */
        fun horizontalTopSize(): BlockSize {
            return horizontalSize
        }

        /** Returns the bottom block size for a horizontal partition.
         * 
         * @return the bottom block size for a horizontal partition
         */
        fun horizontalBottomSize(): BlockSize {
            return horizontalSize
        }

        /** Returns the left block size for a vertical partition.
         * 
         * @return the left block size for a vertical partition
         */
        fun verticalLeftSize(): BlockSize {
            return verticalSize
        }

        /** Returns the right block size for a vertical partition.
         * 
         * @return the right block size for a vertical partition
         */
        fun verticalRightSize(): BlockSize {
            return verticalSize
        }

        /** Returns the top-left square size for T-shaped partitions.
         * 
         * @return the top-left square size for T-shaped partitions
         */
        fun topLeftSquareSize(): BlockSize {
            return childLevel().squareSize()
        }

        /** Returns the top-right square size for T-shaped partitions.
         * 
         * @return the top-right square size for T-shaped partitions
         */
        fun topRightSquareSize(): BlockSize {
            return childLevel().squareSize()
        }

        /** Returns the bottom-left square size for T-shaped partitions.
         * 
         * @return the bottom-left square size for T-shaped partitions
         */
        fun bottomLeftSquareSize(): BlockSize {
            return childLevel().squareSize()
        }

        /** Returns the bottom-right square size for T-shaped partitions.
         * 
         * @return the bottom-right square size for T-shaped partitions
         */
        fun bottomRightSquareSize(): BlockSize {
            return childLevel().squareSize()
        }

        /** Returns the four-way horizontal stripe size.
         * 
         * @return the four-way horizontal stripe size
         */
        fun horizontalFourWaySize(): BlockSize {
            return horizontalFourWaySize
        }

        /** Returns the four-way vertical stripe size.
         * 
         * @return the four-way vertical stripe size
         */
        fun verticalFourWaySize(): BlockSize {
            return verticalFourWaySize
        }

        /** Returns the next smaller square block level.
         * 
         * @return the next smaller square block level
         */
        fun childLevel(): SquareBlockLevel {
            return when (this) {
                SquareBlockLevel.BLOCK_128X128 -> SquareBlockLevel.BLOCK_64X64
                SquareBlockLevel.BLOCK_64X64 -> SquareBlockLevel.BLOCK_32X32
                SquareBlockLevel.BLOCK_32X32 -> SquareBlockLevel.BLOCK_16X16
                SquareBlockLevel.BLOCK_16X16 -> SquareBlockLevel.BLOCK_8X8
                SquareBlockLevel.BLOCK_8X8 -> throw IllegalStateException("8x8 blocks have no smaller square level")
            }
        }
    }

    /** One node inside the early tile partition tree. */
    interface Node {
        /** Returns the local tile-relative origin of this node.
         * 
         * @return the local tile-relative origin of this node
         */
        fun position(): BlockPosition?

        /** Returns the block size represented by this node.
         * 
         * @return the block size represented by this node
         */
        fun size(): BlockSize?
    }

    /** One partition tree leaf that already contains a decoded leaf block header.
     * 
     * @param header the decoded leaf block header
     * @param transformLayout the decoded block-level transform layout
     * @param residualLayout the decoded block-level residual layout
     */
    data class LeafNode(
        val header: BlockHeader,
        val transformLayout: TransformLayout,
        val residualLayout: ResidualLayout
    ) : Node {
        /** Returns the local tile-relative origin of this node.
         * 
         * @return the local tile-relative origin of this node
         */
        override fun position(): BlockPosition {
            return header.position()
        }

        /** Returns the block size represented by this node.
         * 
         * @return the block size represented by this node
         */
        override fun size(): BlockSize {
            return header.size()
        }

        init {
            requireNotNullCompat(header, "header")
            requireNotNullCompat(transformLayout, "transformLayout")
            requireNotNullCompat(residualLayout, "residualLayout")
        }
    }

    /** One non-leaf partition node with child nodes in bitstream order. */
    class PartitionNode(
        position: BlockPosition,
        size: BlockSize,
        partitionType: PartitionType,
        children: Array<Node?>,
        copyChildren: Boolean
    ) : Node {
        /** The local tile-relative origin of this node. */
        val position: BlockPosition

        /** The square block size represented by this node. */
        val size: BlockSize

        /** The decoded partition type. */
        val partitionType: PartitionType

        /** The child nodes in bitstream order. */
        val children: Array<Node>

        /** Creates one non-leaf partition node.
         * 
         * @param position the local tile-relative origin of this node
         * @param size the square block size represented by this node
         * @param partitionType the decoded partition type
         * @param children the child nodes in bitstream order
         */
        constructor(
            position: BlockPosition,
            size: BlockSize,
            partitionType: PartitionType,
            children: Array<Node?>
        ) : this(position, size, partitionType, children, true)

        init {
            this.position = requireNotNullCompat(position, "position")
            this.size = requireNotNullCompat(size, "size")
            this.partitionType =
                requireNotNullCompat(partitionType, "partitionType")
            val checkedChildren: Array<Node?> =
                requireNotNullCompat(children, "children")
            this.children = nonNullArray(
                if (copyChildren) checkedChildren.copyOf() else checkedChildren)
        }

        /** Returns the local tile-relative origin of this node.
         * 
         * @return the local tile-relative origin of this node
         */
        override fun position(): BlockPosition {
            return position
        }

        /** Returns the square block size represented by this node.
         * 
         * @return the square block size represented by this node
         */
        override fun size(): BlockSize {
            return size
        }

        /** Returns the decoded partition type.
         * 
         * @return the decoded partition type
         */
        fun partitionType(): PartitionType {
            return partitionType
        }

        /** Returns the child nodes in bitstream order.
         * 
         * @return the child nodes in bitstream order
         */
        fun children(): Array<Node> {
            return children.copyOf()
        }

        /** Returns the number of child nodes.
         * 
         * @return the number of child nodes
         */
        fun childCount(): Int {
            return children.size
        }

        /** Returns one child node without materializing a copy of the complete child array.
         * 
         * @param index the zero-based child index
         * @return the selected child node
         */
        fun child(index: Int): Node {
            return children[index]
        }
    }

    companion object {
        /** The above-edge partition state values copied from `dav1d`. */
        val ABOVE_PARTITION_EDGE_VALUES: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(0x00, 0x00, 0x10, -1, 0x00, 0x10, 0x10, 0x10, -1, -1),
            intArrayOf(0x10, 0x10, 0x18, -1, 0x10, 0x18, 0x18, 0x18, 0x10, 0x1C),
            intArrayOf(0x18, 0x18, 0x1C, -1, 0x18, 0x1C, 0x1C, 0x1C, 0x18, 0x1E),
            intArrayOf(0x1C, 0x1C, 0x1E, -1, 0x1C, 0x1E, 0x1E, 0x1E, 0x1C, 0x1F),
            intArrayOf(0x1E, 0x1E, 0x1F, 0x1F, -1, -1, -1, -1, -1, -1)
        )

        /** The left-edge partition state values copied from `dav1d`. */
        val LEFT_PARTITION_EDGE_VALUES: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(0x00, 0x10, 0x00, -1, 0x10, 0x10, 0x00, 0x10, -1, -1),
            intArrayOf(0x10, 0x18, 0x10, -1, 0x18, 0x18, 0x10, 0x18, 0x1C, 0x10),
            intArrayOf(0x18, 0x1C, 0x18, -1, 0x1C, 0x1C, 0x18, 0x1C, 0x1E, 0x18),
            intArrayOf(0x1C, 0x1E, 0x1C, -1, 0x1E, 0x1E, 0x1C, 0x1E, 0x1F, 0x1C),
            intArrayOf(0x1E, 0x1F, 0x1E, 0x1F, -1, -1, -1, -1, -1, -1)
        )
    }
}
