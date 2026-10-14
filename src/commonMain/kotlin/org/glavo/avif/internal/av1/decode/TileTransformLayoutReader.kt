// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader.BlockHeader
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.TransformLayout
import org.glavo.avif.internal.av1.model.TransformSize
import org.glavo.avif.internal.av1.model.TransformUnit


/** Reader for block-level transform layouts that sit between block headers and coefficient syntax.
 * 
 * Handles uniform intra-like transform sizes, switchable transform-depth symbols, explicit chroma
 * transform-unit tiling, lossless 4x4 tiling, and switchable inter variable-transform trees.
 * Entropy contexts use tile-local positions, while returned layouts and units use frame-relative
 * positions so callers can retain them without a second relocation pass. */
class TileTransformLayoutReader(tileContext: TileDecodeContext) {
    /** The tile-local decode state that owns the active frame and sequence headers. */
    private val tileContext: TileDecodeContext

    /** The typed syntax reader used for transform-size symbols. */
    private val syntaxReader: TileSyntaxReader

    /** The tile origin on the frame luma-grid X axis. */
    private val frameOffsetX4: Int

    /** The tile origin on the frame luma-grid Y axis. */
    private val frameOffsetY4: Int

    init {
        val nonNullTileContext =
            requireNotNullCompat(tileContext, "tileContext")
        this.tileContext = nonNullTileContext
        this.syntaxReader = TileSyntaxReader(nonNullTileContext)
        this.frameOffsetX4 = nonNullTileContext.startX() shr 2
        this.frameOffsetY4 = nonNullTileContext.startY() shr 2
    }

    /** Returns the tile-local decode state that owns this transform-layout reader.
     * 
     * @return the tile-local decode state that owns this transform-layout reader
     */
    fun tileContext(): TileDecodeContext {
        return tileContext
    }

    /** Decodes the transform layout for one already-decoded leaf block header.
     * 
     * @param header the decoded leaf block header whose position is tile-local
     * @param neighborContext the mutable neighbor context that supplies transform-size contexts
     * @return the decoded transform layout with frame-relative retained positions
     */
    fun read(header: BlockHeader, neighborContext: BlockNeighborContext): TransformLayout {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val nonNullNeighborContext =
            requireNotNullCompat(neighborContext, "neighborContext")
        val position = nonNullHeader.position()
        val size = nonNullHeader.size()
        val visibleWidth4 = min(size.width4(), tileContext.codedWidth4() - position.x4)
        val visibleHeight4 = min(size.height4(), tileContext.codedHeight4() - position.y4)
        val visibleWidthPixels = visibleWidth4 shl 2
        val visibleHeightPixels = visibleHeight4 shl 2

        val lossless = tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId())
        val maxLumaTransformSize = size.maxLumaTransformSize()
        var chromaTransformSize = if (nonNullHeader.hasChroma())
            size.maxChromaTransformSize(tileContext.sequenceHeader().colorConfig().chromaFormat)
        else
            null
        val effectiveMaxLumaTransformSize =
            if (lossless) TransformSize.TX_4X4 else maxLumaTransformSize
        if (lossless) {
            chromaTransformSize = TransformSize.TX_4X4
        }
        val chromaUnits = if (chromaTransformSize != null)
            tileChromaUnits(
                position,
                size,
                visibleWidthPixels,
                visibleHeightPixels,
                chromaTransformSize,
                tileContext.sequenceHeader().colorConfig().chromaFormat
            )
        else
            EMPTY_TRANSFORM_UNITS

        val lumaUnits: Array<TransformUnit>?
        val variableLumaTransformTree: Boolean
        if (nonNullHeader.intra() && !nonNullHeader.useIntrabc()) {
            val uniformLumaTransformSize = selectUniformIntraTransformSize(
                nonNullHeader,
                nonNullNeighborContext,
                lossless,
                effectiveMaxLumaTransformSize
            )
            lumaUnits =
                tileUniformUnits(position, visibleWidth4, visibleHeight4, uniformLumaTransformSize)
            variableLumaTransformTree = false
        } else {
            val interTransformResult = readInterTransformLayout(
                nonNullHeader,
                nonNullNeighborContext,
                lossless,
                effectiveMaxLumaTransformSize,
                visibleWidth4,
                visibleHeight4
            )
            lumaUnits = interTransformResult.lumaUnits
            variableLumaTransformTree = interTransformResult.variableTransformTree
        }

        return TransformLayout.Companion.fromOwnedUnits(
            framePosition(position),
            size,
            visibleWidth4,
            visibleHeight4,
            visibleWidthPixels,
            visibleHeightPixels,
            effectiveMaxLumaTransformSize,
            chromaTransformSize,
            variableLumaTransformTree,
            lumaUnits,
            chromaUnits
        )
    }

    /** Selects the uniform luma transform size used by one intra-like block.
     * 
     * @param header the decoded leaf block header
     * @param neighborContext the mutable neighbor context that supplies transform-size contexts
     * @param lossless whether the current block is lossless through segmentation
     * @param maxLumaTransformSize the largest luma transform size allowed for this block
     * @return the uniform luma transform size used by the current block
     */
    private fun selectUniformIntraTransformSize(
        header: BlockHeader,
        neighborContext: BlockNeighborContext,
        lossless: Boolean,
        maxLumaTransformSize: TransformSize
    ): TransformSize {
        if (lossless || tileContext.frameHeader()
                .transformMode() == FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY
        ) {
            return TransformSize.TX_4X4
        }
        if (tileContext.frameHeader().transformMode() != FrameHeader.TransformMode.SWITCHABLE
            || maxLumaTransformSize.maxSquareLevel() <= 0
        ) {
            return maxLumaTransformSize
        }

        var depth = syntaxReader.readTransformDepth(
            maxLumaTransformSize,
            neighborContext.transformSizeContext(header.position(), maxLumaTransformSize)
        )
        var selectedSize = maxLumaTransformSize
        while (depth-- > 0 && selectedSize.hasSubSize()) {
            selectedSize = selectedSize.subSize()
        }
        return selectedSize
    }

    /** Reads the luma transform layout for one inter-like block.
     * 
     * @param header the decoded leaf block header
     * @param neighborContext the mutable neighbor context that supplies inter var-tx contexts
     * @param lossless whether the current block is lossless through segmentation
     * @param maxLumaTransformSize the largest luma transform size allowed for this block
     * @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
     * @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
     * @return the decoded inter-like luma transform layout
     */
    private fun readInterTransformLayout(
        header: BlockHeader,
        neighborContext: BlockNeighborContext,
        lossless: Boolean,
        maxLumaTransformSize: TransformSize,
        visibleWidth4: Int,
        visibleHeight4: Int
    ): InterTransformResult {
        val position = header.position()
        val size = header.size()
        if (header.skip()) {
            neighborContext.updateInterTransformContext(
                position,
                visibleWidth4,
                visibleHeight4,
                size.log2Width4(),
                size.log2Height4()
            )
            val skippedTransformSize = if (lossless) TransformSize.TX_4X4 else maxLumaTransformSize
            return InterTransformResult(
                tileUniformUnits(position, visibleWidth4, visibleHeight4, skippedTransformSize),
                false
            )
        }
        if (maxLumaTransformSize == TransformSize.TX_4X4) {
            neighborContext.updateInterTransformContext(
                position,
                visibleWidth4,
                visibleHeight4,
                TransformSize.TX_4X4
            )
            return InterTransformResult(
                tileUniformUnits(
                    position,
                    visibleWidth4,
                    visibleHeight4,
                    TransformSize.TX_4X4
                ), false
            )
        }
        if (lossless) {
            neighborContext.updateInterTransformContext(
                position,
                visibleWidth4,
                visibleHeight4,
                TransformSize.TX_4X4
            )
            return InterTransformResult(
                tileUniformUnits(
                    position,
                    visibleWidth4,
                    visibleHeight4,
                    TransformSize.TX_4X4
                ), false
            )
        }
        if (tileContext.frameHeader().transformMode() != FrameHeader.TransformMode.SWITCHABLE) {
            return InterTransformResult(
                tileUniformUnits(
                    position,
                    visibleWidth4,
                    visibleHeight4,
                    maxLumaTransformSize
                ), false
            )
        }

        val units: MutableList<TransformUnit> = ArrayList<TransformUnit>()
        var offsetY4 = 0
        while (offsetY4 < visibleHeight4) {
            val regionVisibleHeight4 =
                min(maxLumaTransformSize.height4(), visibleHeight4 - offsetY4)
            var offsetX4 = 0
            while (offsetX4 < visibleWidth4) {
                val regionVisibleWidth4 =
                    min(maxLumaTransformSize.width4(), visibleWidth4 - offsetX4)
                readInterTransformTree(
                    position.offset(offsetX4, offsetY4),
                    maxLumaTransformSize,
                    regionVisibleWidth4,
                    regionVisibleHeight4,
                    0,
                    neighborContext,
                    units
                )
                offsetX4 += maxLumaTransformSize.width4()
            }
            offsetY4 += maxLumaTransformSize.height4()
        }
        return InterTransformResult(units.toTypedArray<TransformUnit>(), true)
    }

    /** Recursively reads one inter var-tx region and appends its visible luma transform units.
     * 
     * @param position the local tile-relative origin of the current transform region
     * @param transformSize the transform size currently being considered for splitting
     * @param visibleWidth4 the visible region width in 4x4 units after clipping against tile bounds
     * @param visibleHeight4 the visible region height in 4x4 units after clipping against tile bounds
     * @param depth the recursive var-tx depth in `[0, 2]`
     * @param neighborContext the mutable neighbor context that supplies and stores inter var-tx state
     * @param destination the destination list that receives visible luma transform units in bitstream order
     */
    private fun readInterTransformTree(
        position: BlockPosition,
        transformSize: TransformSize,
        visibleWidth4: Int,
        visibleHeight4: Int,
        depth: Int,
        neighborContext: BlockNeighborContext,
        destination: MutableList<TransformUnit>
    ) {
        if (visibleWidth4 <= 0 || visibleHeight4 <= 0) {
            return
        }

        var split = false
        if (depth < 2 && transformSize != TransformSize.TX_4X4) {
            val tableIndex =
                2 * (TransformSize.TX_64X64.maxSquareLevel() - transformSize.maxSquareLevel()) - depth
            split = syntaxReader.readTransformSplitFlag(
                tableIndex,
                neighborContext.interTransformSplitContext(position, transformSize)
            )
        }

        if (split && transformSize.maxSquareLevel() > TransformSize.TX_8X8.maxSquareLevel()) {
            val subSize = transformSize.subSize()
            val childWidth4 = subSize.width4()
            val childHeight4 = subSize.height4()
            readInterTransformTree(
                position,
                subSize,
                min(childWidth4, visibleWidth4),
                min(childHeight4, visibleHeight4),
                depth + 1,
                neighborContext,
                destination
            )
            if (transformSize.width4() >= transformSize.height4()) {
                readInterTransformTree(
                    position.offset(childWidth4, 0),
                    subSize,
                    min(childWidth4, max(0, visibleWidth4 - childWidth4)),
                    min(childHeight4, visibleHeight4),
                    depth + 1,
                    neighborContext,
                    destination
                )
            }
            if (transformSize.height4() >= transformSize.width4()) {
                readInterTransformTree(
                    position.offset(0, childHeight4),
                    subSize,
                    min(childWidth4, visibleWidth4),
                    min(childHeight4, max(0, visibleHeight4 - childHeight4)),
                    depth + 1,
                    neighborContext,
                    destination
                )
                if (transformSize.width4() >= transformSize.height4()) {
                    readInterTransformTree(
                        position.offset(childWidth4, childHeight4),
                        subSize,
                        min(childWidth4, max(0, visibleWidth4 - childWidth4)),
                        min(childHeight4, max(0, visibleHeight4 - childHeight4)),
                        depth + 1,
                        neighborContext,
                        destination
                    )
                }
            }
            return
        }

        val finalTransformSize = if (split) TransformSize.TX_4X4 else transformSize
        neighborContext.updateInterTransformContext(
            position,
            visibleWidth4,
            visibleHeight4,
            finalTransformSize
        )
        appendUniformUnits(destination, position, visibleWidth4, visibleHeight4, finalTransformSize)
    }

    /** Tiles one visible block span with repeated luma transform units in AV1 processing order.
     * 
     * Blocks wider or taller than 64 luma samples are divided into 64x64-or-smaller regions.
     * Every region is completed before prediction and reconstruction advance to the next region.
     * 
     * @param position the local tile-relative origin of the owning block
     * @param visibleWidth4 the visible block width in 4x4 units
     * @param visibleHeight4 the visible block height in 4x4 units
     * @param transformSize the repeated luma transform size
     * @return frame-relative luma transform units that cover the visible block span
     */
    private fun tileUniformUnits(
        position: BlockPosition,
        visibleWidth4: Int,
        visibleHeight4: Int,
        transformSize: TransformSize
    ): Array<TransformUnit> {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val units: Array<TransformUnit> = arrayOfNullsCompat<TransformUnit>(
            tiledUnitCount(
                visibleWidth4,
                visibleHeight4,
                TRANSFORM_REGION_SIZE4,
                TRANSFORM_REGION_SIZE4,
                nonNullTransformSize.width4(),
                nonNullTransformSize.height4()
            )
        )
        var unitIndex = 0
        var regionY4 = 0
        while (regionY4 < visibleHeight4) {
            val regionEndY4 = min(regionY4 + TRANSFORM_REGION_SIZE4, visibleHeight4)
            var regionX4 = 0
            while (regionX4 < visibleWidth4) {
                val regionEndX4 = min(regionX4 + TRANSFORM_REGION_SIZE4, visibleWidth4)
                var y4 = regionY4
                while (y4 < regionEndY4) {
                    var x4 = regionX4
                    while (x4 < regionEndX4) {
                        units[unitIndex++] = TransformUnit(
                            BlockPosition(
                                nonNullPosition.x4 + x4 + frameOffsetX4,
                                nonNullPosition.y4 + y4 + frameOffsetY4
                            ),
                            nonNullTransformSize
                        )
                        x4 += nonNullTransformSize.width4()
                    }
                    y4 += nonNullTransformSize.height4()
                }
                regionX4 += TRANSFORM_REGION_SIZE4
            }
            regionY4 += TRANSFORM_REGION_SIZE4
        }
        return units
    }

    /** Appends repeated luma transform units in AV1 processing order.
     * 
     * @param destination the destination list
     * @param position the local tile-relative origin of the owning block
     * @param visibleWidth4 the visible block width in 4x4 units
     * @param visibleHeight4 the visible block height in 4x4 units
     * @param transformSize the repeated luma transform size
     */
    private fun appendUniformUnits(
        destination: MutableList<TransformUnit>,
        position: BlockPosition,
        visibleWidth4: Int,
        visibleHeight4: Int,
        transformSize: TransformSize
    ) {
        val nonNullDestination =
            requireNotNullCompat(destination, "destination")
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        var regionY4 = 0
        while (regionY4 < visibleHeight4) {
            val regionEndY4 = min(regionY4 + TRANSFORM_REGION_SIZE4, visibleHeight4)
            var regionX4 = 0
            while (regionX4 < visibleWidth4) {
                val regionEndX4 = min(regionX4 + TRANSFORM_REGION_SIZE4, visibleWidth4)
                var y4 = regionY4
                while (y4 < regionEndY4) {
                    var x4 = regionX4
                    while (x4 < regionEndX4) {
                        nonNullDestination.add(
                            TransformUnit(
                                BlockPosition(
                                    nonNullPosition.x4 + x4 + frameOffsetX4,
                                    nonNullPosition.y4 + y4 + frameOffsetY4
                                ),
                                nonNullTransformSize
                            )
                        )
                        x4 += nonNullTransformSize.width4()
                    }
                    y4 += nonNullTransformSize.height4()
                }
                regionX4 += TRANSFORM_REGION_SIZE4
            }
            regionY4 += TRANSFORM_REGION_SIZE4
        }
    }

    /** Tiles one visible block span with chroma transform units in AV1 processing order.
     * 
     * The returned unit positions are widened back into the frame-relative shared luma-grid
     * `BlockPosition` coordinate space so reconstruction can keep using one position type.
     * 
     * @param position the local tile-relative luma-grid origin of the owning block
     * @param blockSize the coded block size that owns the chroma units
     * @param visibleWidthPixels the exact visible luma width in pixels
     * @param visibleHeightPixels the exact visible luma height in pixels
     * @param transformSize the repeated chroma transform size
     * @param chromaFormat the active decoded chroma layout
     * @return frame-relative chroma transform units covering the visible chroma span
     */
    private fun tileChromaUnits(
        position: BlockPosition,
        blockSize: BlockSize,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        transformSize: TransformSize,
        chromaFormat: Av1ChromaFormat
    ): Array<TransformUnit> {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullBlockSize = requireNotNullCompat(blockSize, "blockSize")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val subsamplingX: Int = chromaSubsamplingX(chromaFormat)
        val subsamplingY: Int = chromaSubsamplingY(chromaFormat)
        val originX4: Int =
            chromaOrigin4(nonNullPosition.x4, nonNullBlockSize.width4(), subsamplingX)
        val originY4: Int =
            chromaOrigin4(nonNullPosition.y4, nonNullBlockSize.height4(), subsamplingY)
        val visibleChromaWidthPixels: Int = chromaDimension(
            ((nonNullPosition.x4 shl 2) + visibleWidthPixels) - (originX4 shl 2),
            subsamplingX
        )
        val visibleChromaHeightPixels: Int = chromaDimension(
            ((nonNullPosition.y4 shl 2) + visibleHeightPixels) - (originY4 shl 2),
            subsamplingY
        )
        val regionWidthPixels: Int = (TRANSFORM_REGION_SIZE4 shl 2) shr subsamplingX
        val regionHeightPixels: Int = (TRANSFORM_REGION_SIZE4 shl 2) shr subsamplingY
        val units: Array<TransformUnit> = arrayOfNullsCompat<TransformUnit>(
            tiledUnitCount(
                visibleChromaWidthPixels,
                visibleChromaHeightPixels,
                regionWidthPixels,
                regionHeightPixels,
                nonNullTransformSize.widthPixels(),
                nonNullTransformSize.heightPixels()
            )
        )
        var unitIndex = 0
        var regionY = 0
        while (regionY < visibleChromaHeightPixels) {
            val regionEndY = min(regionY + regionHeightPixels, visibleChromaHeightPixels)
            var regionX = 0
            while (regionX < visibleChromaWidthPixels) {
                val regionEndX = min(regionX + regionWidthPixels, visibleChromaWidthPixels)
                var y = regionY
                while (y < regionEndY) {
                    var x = regionX
                    while (x < regionEndX) {
                        units[unitIndex++] = TransformUnit(
                            BlockPosition(
                                originX4 + ((x shr 2) shl subsamplingX) + frameOffsetX4,
                                originY4 + ((y shr 2) shl subsamplingY) + frameOffsetY4
                            ),
                            nonNullTransformSize
                        )
                        x += nonNullTransformSize.widthPixels()
                    }
                    y += nonNullTransformSize.heightPixels()
                }
                regionX += regionWidthPixels
            }
            regionY += regionHeightPixels
        }
        return units
    }

    /** Returns one tile-local position translated into the retained frame coordinate space.
     * 
     * @param position the tile-local block position
     * @return the corresponding frame-relative block position
     */
    private fun framePosition(position: BlockPosition): BlockPosition {
        val nonNullPosition = requireNotNullCompat(position, "position")
        if ((frameOffsetX4 or frameOffsetY4) == 0) {
            return nonNullPosition
        }
        return BlockPosition(
            nonNullPosition.x4 + frameOffsetX4,
            nonNullPosition.y4 + frameOffsetY4
        )
    }

    /** One decoded inter-like transform result.
     * 
     * @param lumaUnits the decoded luma transform units in bitstream order
     * @param variableTransformTree whether the layout came from a variable luma transform tree
     */
    data class InterTransformResult(
        val lumaUnits: Array<TransformUnit>,
        val variableTransformTree: Boolean
    ) {
        init {
            requireNotNullCompat(
                lumaUnits, "lumaUnits"
            )
        }
    }

    companion object {
        /** Shared empty transform-unit sequence for blocks without modeled chroma transforms. */
        val EMPTY_TRANSFORM_UNITS: Array<TransformUnit> =
            arrayOfNullsCompat<TransformUnit>(0)

        /** The width and height of one AV1 transform-processing region in 4x4 luma units. */
        private const val TRANSFORM_REGION_SIZE4 = 16

        /** Returns the number of repeated units produced by region-local transform tiling.
         * 
         * @param visibleWidth the visible width in the caller's coordinate units
         * @param visibleHeight the visible height in the caller's coordinate units
         * @param regionWidth the processing-region width in the same units
         * @param regionHeight the processing-region height in the same units
         * @param unitWidth the transform-unit width in the same units
         * @param unitHeight the transform-unit height in the same units
         * @return the exact number of transform units required to cover the visible span
         */
        fun tiledUnitCount(
            visibleWidth: Int,
            visibleHeight: Int,
            regionWidth: Int,
            regionHeight: Int,
            unitWidth: Int,
            unitHeight: Int
        ): Int {
            var count = 0
            var regionY = 0
            while (regionY < visibleHeight) {
                val currentRegionHeight = min(regionHeight, visibleHeight - regionY)
                val verticalUnits = (currentRegionHeight + unitHeight - 1) / unitHeight
                var regionX = 0
                while (regionX < visibleWidth) {
                    val currentRegionWidth = min(regionWidth, visibleWidth - regionX)
                    val horizontalUnits = (currentRegionWidth + unitWidth - 1) / unitWidth
                    count += horizontalUnits * verticalUnits
                    regionX += regionWidth
                }
                regionY += regionHeight
            }
            return count
        }

        /** Returns the luma-grid origin for one chroma block span.
         * 
         * @param position4 the luma-grid coordinate of the syntax block
         * @param size4 the luma-grid span of the syntax block
         * @param subsamplingShift the chroma subsampling shift for the axis
         * @return the luma-grid origin for the shared chroma footprint
         */
        fun chromaOrigin4(position4: Int, size4: Int, subsamplingShift: Int): Int {
            if (subsamplingShift == 0 || size4 > subsamplingShift) {
                return position4
            }
            val mask = (1 shl subsamplingShift) - 1
            return position4 and mask.inv()
        }

        /** Returns the chroma-plane dimension corresponding to one visible luma span.
         * 
         * @param lumaDimension the visible luma span in pixels
         * @param subsamplingShift the chroma subsampling shift for the axis
         * @return the corresponding chroma-plane dimension
         */
        fun chromaDimension(lumaDimension: Int, subsamplingShift: Int): Int {
            return (lumaDimension + (1 shl subsamplingShift) - 1) shr subsamplingShift
        }

        /** Returns the horizontal chroma subsampling shift for one decoded chroma format.
         * 
         * @param chromaFormat the active decoded chroma layout
         * @return the horizontal chroma subsampling shift
         */
        fun chromaSubsamplingX(chromaFormat: Av1ChromaFormat): Int {
            return when (requireNotNullCompat(chromaFormat, "chromaFormat")) {
                Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
                Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
            }
        }

        /** Returns the vertical chroma subsampling shift for one decoded chroma format.
         * 
         * @param chromaFormat the active decoded chroma layout
         * @return the vertical chroma subsampling shift
         */
        fun chromaSubsamplingY(chromaFormat: Av1ChromaFormat): Int {
            return when (requireNotNullCompat(chromaFormat, "chromaFormat")) {
                Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
                Av1ChromaFormat.YUV420 -> 1
            }
        }
    }
}
