// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.entropy.CdfContext
import org.glavo.avif.internal.av1.entropy.MsacDecoder
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.FrameAssembly
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.InterMotionVector
import org.glavo.avif.internal.av1.model.SequenceHeader
import org.glavo.avif.internal.av1.model.TileBitstream


/** Tile-local decode state derived from a fully assembled frame and one collected tile bitstream.
 * 
 * Pixel-space end coordinates are exclusive. */
class TileDecodeContext private constructor(
    assembly: FrameAssembly,
    sequenceHeader: SequenceHeader,
    frameHeader: FrameHeader,
    tileBitstream: TileBitstream,
    msacDecoder: MsacDecoder,
    cdfContext: CdfContext,
    decodedTemporalMotionField: TemporalMotionField,
    referenceMotionVectorProjection: ReferenceMotionVectorProjection,
    currentSegmentIdMap: SegmentIdMap,
    /** The immutable primary-reference segment-id map, or `null` when none is compatible. */
    private val referenceSegmentIdMap: SegmentIdMap?,
    blockSyntaxState: BlockSyntaxState,
    restorationUnitMap: RestorationUnitMap,
    /** The zero-based tile index within the frame. */
    private val tileIndex: Int,
    /** The zero-based tile row within the frame. */
    private val tileRow: Int,
    /** The zero-based tile column within the frame. */
    private val tileColumn: Int,
    /** The superblock size in pixels for the active sequence. */
    private val superblockSize: Int,
    /** The inclusive start superblock column for the tile. */
    private val columnStartSuperblock: Int,
    /** The exclusive end superblock column for the tile. */
    private val columnEndSuperblock: Int,
    /** The inclusive start superblock row for the tile. */
    private val rowStartSuperblock: Int,
    /** The exclusive end superblock row for the tile. */
    private val rowEndSuperblock: Int,
    /** The inclusive start X coordinate in pixels. */
    private val startX: Int,
    /** The exclusive end X coordinate in pixels. */
    private val endX: Int,
    /** The inclusive start Y coordinate in pixels. */
    private val startY: Int,
    /** The exclusive end Y coordinate in pixels. */
    private val endY: Int,
    /** The tile width in AV1 4x4 coding units after 8x8 frame-grid rounding. */
    private val codedWidth4: Int,
    /** The tile height in AV1 4x4 coding units after 8x8 frame-grid rounding. */
    private val codedHeight4: Int
) {
    /** The frame assembly that owns the tile. */
    private val assembly: FrameAssembly

    /** The active sequence header for the tile. */
    private val sequenceHeader: SequenceHeader

    /** The active frame header for the tile. */
    private val frameHeader: FrameHeader

    /** The selected tile bitstream. */
    private val tileBitstream: TileBitstream

    /** The tile-local arithmetic decoder. */
    private val msacDecoder: MsacDecoder

    /** The tile-local mutable CDF context. */
    private val cdfContext: CdfContext

    /** The tile-local temporal motion field being produced while decoding the current frame. */
    private val decodedTemporalMotionField: TemporalMotionField

    /** The immutable reference-frame motion-vector projection shared by the frame's tiles. */
    private val referenceMotionVectorProjection: ReferenceMotionVectorProjection

    /** The mutable current-frame segment-id map shared by the frame's tiles. */
    private val currentSegmentIdMap: SegmentIdMap

    /** The mutable tile-local block syntax state shared across superblocks. */
    private val blockSyntaxState: BlockSyntaxState

    /** The tile-local loop-restoration units decoded from this tile. */
    private val restorationUnitMap: RestorationUnitMap

    init {
        this.assembly = requireNotNullCompat(assembly, "assembly")
        this.sequenceHeader =
            requireNotNullCompat(sequenceHeader, "sequenceHeader")
        this.frameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        this.tileBitstream = requireNotNullCompat(tileBitstream, "tileBitstream")
        this.msacDecoder = requireNotNullCompat(msacDecoder, "msacDecoder")
        this.cdfContext = requireNotNullCompat(cdfContext, "cdfContext")
        this.decodedTemporalMotionField = requireNotNullCompat(
            decodedTemporalMotionField,
            "decodedTemporalMotionField"
        )
        this.referenceMotionVectorProjection =
            requireNotNullCompat(
                referenceMotionVectorProjection,
                "referenceMotionVectorProjection"
            )
        this.currentSegmentIdMap =
            requireNotNullCompat(currentSegmentIdMap, "currentSegmentIdMap")
        this.blockSyntaxState =
            requireNotNullCompat(blockSyntaxState, "blockSyntaxState")
        this.restorationUnitMap =
            requireNotNullCompat(restorationUnitMap, "restorationUnitMap")
    }

    /** Returns the frame assembly that owns the tile.
     * 
     * @return the frame assembly that owns the tile
     */
    fun assembly(): FrameAssembly {
        return assembly
    }

    /** Returns the active sequence header for the tile.
     * 
     * @return the active sequence header for the tile
     */
    fun sequenceHeader(): SequenceHeader {
        return sequenceHeader
    }

    /** Returns the active frame header for the tile.
     * 
     * @return the active frame header for the tile
     */
    fun frameHeader(): FrameHeader {
        return frameHeader
    }

    /** Returns the refreshed reference-frame header for one internal LAST..ALTREF reference index.
     * 
     * @param referenceFrame the internal LAST..ALTREF reference index
     * @return the refreshed reference-frame header for the supplied reference, or `null`
     */
    fun referenceFrameHeader(referenceFrame: Int): FrameHeader? {
        return assembly.referenceFrameHeader(referenceFrame)
    }

    /** Returns the selected tile bitstream.
     * 
     * @return the selected tile bitstream
     */
    fun tileBitstream(): TileBitstream {
        return tileBitstream
    }

    /** Returns the tile-local arithmetic decoder.
     * 
     * @return the tile-local arithmetic decoder
     */
    fun msacDecoder(): MsacDecoder {
        return msacDecoder
    }

    /** Returns the tile-local mutable CDF context.
     * 
     * @return the tile-local mutable CDF context
     */
    fun cdfContext(): CdfContext {
        return cdfContext
    }

    /** Returns the tile-local temporal motion field produced while decoding the current frame.
     * 
     * @return the tile-local temporal motion field produced while decoding the current frame
     */
    fun decodedTemporalMotionField(): TemporalMotionField {
        return decodedTemporalMotionField
    }

    /** Returns the immutable reference-frame motion-vector projection for the current frame.
     * 
     * @return the immutable reference-frame motion-vector projection for the current frame
     */
    fun referenceMotionVectorProjection(): ReferenceMotionVectorProjection {
        return referenceMotionVectorProjection
    }

    /** Returns the mutable current-frame segment-id map shared by all tiles.
     * 
     * @return the current-frame segment-id map
     */
    fun currentSegmentIdMap(): SegmentIdMap {
        return currentSegmentIdMap
    }

    /** Returns the minimum primary-reference segment identifier covered by one local block.
     * 
     * A missing or dimension-incompatible primary-reference map is represented as an all-zero map.
     * 
     * @param position the local tile-relative block origin
     * @param size the block size whose previous-frame footprint is queried
     * @return the minimum primary-reference segment identifier, or zero when no map is available
     */
    fun referenceSegmentId(position: BlockPosition, size: BlockSize): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        if (referenceSegmentIdMap == null) {
            return 0
        }
        return referenceSegmentIdMap.minimum(
            (startX shr 2) + nonNullPosition.x4,
            (startY shr 2) + nonNullPosition.y4,
            nonNullSize.width4(),
            nonNullSize.height4()
        )
    }

    /** Returns the mutable tile-local block syntax state shared across superblocks.
     * 
     * @return the mutable tile-local block syntax state shared across superblocks
     */
    fun blockSyntaxState(): BlockSyntaxState {
        return blockSyntaxState
    }

    /** Returns the tile-local loop-restoration units decoded from this tile.
     * 
     * @return the tile-local loop-restoration units decoded from this tile
     */
    fun restorationUnitMap(): RestorationUnitMap {
        return restorationUnitMap
    }

    /** Returns the zero-based tile index within the frame.
     * 
     * @return the zero-based tile index within the frame
     */
    fun tileIndex(): Int {
        return tileIndex
    }

    /** Returns the zero-based tile row within the frame.
     * 
     * @return the zero-based tile row within the frame
     */
    fun tileRow(): Int {
        return tileRow
    }

    /** Returns the zero-based tile column within the frame.
     * 
     * @return the zero-based tile column within the frame
     */
    fun tileColumn(): Int {
        return tileColumn
    }

    /** Returns the superblock size in pixels for the active sequence.
     * 
     * @return the superblock size in pixels for the active sequence
     */
    fun superblockSize(): Int {
        return superblockSize
    }

    /** Returns the inclusive start superblock column for the tile.
     * 
     * @return the inclusive start superblock column for the tile
     */
    fun columnStartSuperblock(): Int {
        return columnStartSuperblock
    }

    /** Returns the exclusive end superblock column for the tile.
     * 
     * @return the exclusive end superblock column for the tile
     */
    fun columnEndSuperblock(): Int {
        return columnEndSuperblock
    }

    /** Returns the inclusive start superblock row for the tile.
     * 
     * @return the inclusive start superblock row for the tile
     */
    fun rowStartSuperblock(): Int {
        return rowStartSuperblock
    }

    /** Returns the exclusive end superblock row for the tile.
     * 
     * @return the exclusive end superblock row for the tile
     */
    fun rowEndSuperblock(): Int {
        return rowEndSuperblock
    }

    /** Returns the inclusive start X coordinate in pixels.
     * 
     * @return the inclusive start X coordinate in pixels
     */
    fun startX(): Int {
        return startX
    }

    /** Returns the exclusive end X coordinate in pixels.
     * 
     * @return the exclusive end X coordinate in pixels
     */
    fun endX(): Int {
        return endX
    }

    /** Returns the inclusive start Y coordinate in pixels.
     * 
     * @return the inclusive start Y coordinate in pixels
     */
    fun startY(): Int {
        return startY
    }

    /** Returns the exclusive end Y coordinate in pixels.
     * 
     * @return the exclusive end Y coordinate in pixels
     */
    fun endY(): Int {
        return endY
    }

    /** Returns the tile width in pixels.
     * 
     * @return the tile width in pixels
     */
    fun width(): Int {
        return endX - startX
    }

    /** Returns the tile height in pixels.
     * 
     * @return the tile height in pixels
     */
    fun height(): Int {
        return endY - startY
    }

    /** Returns the tile width in AV1 4x4 coding units after 8x8 frame-grid rounding.
     * 
     * @return the tile width in AV1 4x4 coding units after 8x8 frame-grid rounding
     */
    fun codedWidth4(): Int {
        return codedWidth4
    }

    /** Returns the tile height in AV1 4x4 coding units after 8x8 frame-grid rounding.
     * 
     * @return the tile height in AV1 4x4 coding units after 8x8 frame-grid rounding
     */
    fun codedHeight4(): Int {
        return codedHeight4
    }

    /** A tile-local temporal motion field sampled in 8x8 units. */
    class TemporalMotionField(width8: Int, height8: Int) {
        /** The tile width rounded up to 8x8 units. */
        val width8: Int

        /** The tile height rounded up to 8x8 units. */
        val height8: Int

        /** The temporal motion blocks indexed in tile-relative 8x8 units. */
        val blocks: Array<TemporalMotionBlock?>

        init {
            require(width8 >= 0) { "width8 < 0: $width8" }
            require(height8 >= 0) { "height8 < 0: $height8" }
            this.width8 = width8
            this.height8 = height8
            this.blocks = arrayOfNullsCompat<TemporalMotionBlock?>(width8 * height8)
        }

        /** Returns the tile width rounded up to 8x8 units.
         * 
         * @return the tile width rounded up to 8x8 units
         */
        fun width8(): Int {
            return width8
        }

        /** Returns the tile height rounded up to 8x8 units.
         * 
         * @return the tile height rounded up to 8x8 units
         */
        fun height8(): Int {
            return height8
        }

        /** Stores one temporal motion block at the supplied tile-relative 8x8 coordinate.
         * 
         * @param x8 the tile-relative X coordinate in 8x8 units
         * @param y8 the tile-relative Y coordinate in 8x8 units
         * @param block the temporal motion block to store
         */
        fun setBlock(x8: Int, y8: Int, block: TemporalMotionBlock) {
            blocks[index(x8, y8)] = requireNotNullCompat(block, "block")
        }

        /** Clears the temporal motion block stored at the supplied tile-relative 8x8 coordinate.
         * 
         * @param x8 the tile-relative X coordinate in 8x8 units
         * @param y8 the tile-relative Y coordinate in 8x8 units
         */
        fun clearBlock(x8: Int, y8: Int) {
            blocks[index(x8, y8)] = null
        }

        /** Returns the temporal motion block stored at the supplied tile-relative 8x8 coordinate, or `null`.
         * 
         * @param x8 the tile-relative X coordinate in 8x8 units
         * @param y8 the tile-relative Y coordinate in 8x8 units
         * @return the temporal motion block stored at the supplied tile-relative 8x8 coordinate, or `null`
         */
        fun block(x8: Int, y8: Int): TemporalMotionBlock? {
            return blocks[index(x8, y8)]
        }

        /** Returns a shallow copy of this temporal motion field.
         * 
         * The copied field owns a new storage array while reusing immutable temporal-motion block
         * entries.
         * 
         * @return a shallow copy of this temporal motion field
         */
        fun copy(): TemporalMotionField {
            val copy = TemporalMotionField(width8, height8)
            arraycopy(blocks, 0, copy.blocks, 0, blocks.size)
            return copy
        }

        /** Returns the flat array index for one tile-relative 8x8 coordinate.
         * 
         * @param x8 the tile-relative X coordinate in 8x8 units
         * @param y8 the tile-relative Y coordinate in 8x8 units
         * @return the flat array index for one tile-relative 8x8 coordinate
         */
        fun index(x8: Int, y8: Int): Int {
            if (x8 < 0 || x8 >= width8) {
                throw IndexOutOfBoundsException("x8 out of range: $x8")
            }
            if (y8 < 0 || y8 >= height8) {
                throw IndexOutOfBoundsException("y8 out of range: $y8")
            }
            return y8 * width8 + x8
        }
    }

    /** One temporal motion-field sample projected into the current tile.
     * 
     * @param compoundReference whether the temporal sample carries compound references
     * @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary reference frame in internal LAST..ALTREF order, or `-1`
     * @param motionVector0 the primary temporal motion-vector state
     * @param motionVector1 the secondary temporal motion-vector state, or `null`
     */
    data class TemporalMotionBlock(
        val compoundReference: Boolean,
        val referenceFrame0: Int,
        val referenceFrame1: Int,
        val motionVector0: InterMotionVector,
        val motionVector1: InterMotionVector?
    ) {
        /** Creates one temporal motion-field sample. */
        init {
            require(referenceFrame0 >= 0) { "referenceFrame0 < 0: $referenceFrame0" }
            if (compoundReference) {
                require(referenceFrame1 >= 0) { "Compound temporal motion blocks must carry referenceFrame1" }
                requireNotNull(motionVector1) { "Compound temporal motion blocks must carry motionVector1" }
            } else {
                require(referenceFrame1 < 0) { "Single-reference temporal motion blocks must not carry referenceFrame1" }
                require(motionVector1 == null) { "Single-reference temporal motion blocks must not carry motionVector1" }
            }
            requireNotNullCompat(motionVector0, "motionVector0")
        }

        companion object {
            /** Creates one single-reference temporal motion-field sample.
             * 
             * @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
             * @param motionVector0 the primary temporal motion-vector state
             * @return one single-reference temporal motion-field sample
             */
            fun singleReference(
                referenceFrame0: Int,
                motionVector0: InterMotionVector
            ): TemporalMotionBlock {
                return TemporalMotionBlock(false, referenceFrame0, -1, motionVector0, null)
            }

            /** Creates one compound-reference temporal motion-field sample.
             * 
             * @param referenceFrame0 the primary reference frame in internal LAST..ALTREF order
             * @param referenceFrame1 the secondary reference frame in internal LAST..ALTREF order
             * @param motionVector0 the primary temporal motion-vector state
             * @param motionVector1 the secondary temporal motion-vector state
             * @return one compound-reference temporal motion-field sample
             */
            fun compoundReference(
                referenceFrame0: Int,
                referenceFrame1: Int,
                motionVector0: InterMotionVector,
                motionVector1: InterMotionVector
            ): TemporalMotionBlock {
                return TemporalMotionBlock(
                    true,
                    referenceFrame0,
                    referenceFrame1,
                    motionVector0,
                    motionVector1
                )
            }
        }
    }

    /** Mutable tile-local block syntax state shared across superblocks. */
    class BlockSyntaxState(
        /** The current luma AC quantizer index carried across superblocks. */
        var currentQIndex: Int
    ) {
        /** The current delta-lf state carried across superblocks. */
        val currentDeltaLfValues: IntArray

        /** The current superblock origin X coordinate in tile-relative 4x4 units, or `-1`. */
        var currentSuperblockOriginX4: Int

        /** The current superblock origin Y coordinate in tile-relative 4x4 units, or `-1`. */
        var currentSuperblockOriginY4: Int

        /** The current superblock CDEF indices in raster quadrant order. */
        val cdefIndices: IntArray

        init {
            this.currentDeltaLfValues = IntArray(4)
            this.currentSuperblockOriginX4 = -1
            this.currentSuperblockOriginY4 = -1
            this.cdefIndices = intArrayOf(-1, -1, -1, -1)
        }

        /** Returns the current luma AC quantizer index carried across superblocks.
         * 
         * @return the current luma AC quantizer index carried across superblocks
         */
        fun currentQIndex(): Int {
            return currentQIndex
        }

        /** Returns the current delta-lf value for one runtime slot.
         * 
         * @param index the zero-based delta-lf runtime slot index in `[0, 4)`
         * @return the current delta-lf value for the supplied runtime slot
         */
        fun currentDeltaLfValue(index: Int): Int {
            return currentDeltaLfValues[checkIndexCompat(index, currentDeltaLfValues.size)]
        }

        /** Updates the current delta-lf value for one runtime slot.
         * 
         * @param index the zero-based delta-lf runtime slot index in `[0, 4)`
         * @param value the replacement delta-lf value
         */
        fun setCurrentDeltaLfValue(index: Int, value: Int) {
            currentDeltaLfValues[checkIndexCompat(index, currentDeltaLfValues.size)] = value
        }

        /** Returns the live delta-lf runtime slots for immediate package-local inspection.
         * 
         * The caller must not modify or retain the returned array.
         * 
         * @return the current delta-lf runtime slots
         */
        fun currentDeltaLfValuesView(): IntArray {
            return currentDeltaLfValues
        }

        /** Resets the current-superblock CDEF cache when decoding enters a different superblock.
         * 
         * @param position the current block origin in tile-relative 4x4 units
         * @param superblockSize the active superblock size in pixels
         */
        fun enterSuperblock(position: BlockPosition, superblockSize: Int) {
            val nonNullPosition = requireNotNullCompat(position, "position")
            val superblockSize4 = superblockSize shr 2
            val superblockOriginX4 = (nonNullPosition.x4 / superblockSize4) * superblockSize4
            val superblockOriginY4 = (nonNullPosition.y4 / superblockSize4) * superblockSize4
            if (superblockOriginX4 != currentSuperblockOriginX4 || superblockOriginY4 != currentSuperblockOriginY4) {
                currentSuperblockOriginX4 = superblockOriginX4
                currentSuperblockOriginY4 = superblockOriginY4
                fillCompat(cdefIndices, -1)
            }
        }

        /** Returns the current cached CDEF index for one superblock quadrant, or `-1`.
         * 
         * @param index the zero-based quadrant index in `[0, 4)`
         * @return the current cached CDEF index for one superblock quadrant, or `-1`
         */
        fun cdefIndex(index: Int): Int {
            return cdefIndices[checkIndexCompat(index, cdefIndices.size)]
        }

        /** Updates the cached CDEF index for one superblock quadrant.
         * 
         * @param index the zero-based quadrant index in `[0, 4)`
         * @param value the replacement cached CDEF index, or `-1`
         */
        fun setCdefIndex(index: Int, value: Int) {
            cdefIndices[checkIndexCompat(index, cdefIndices.size)] = value
        }
    }

    companion object {
        /** Creates tile-local decode state with a fresh default CDF context.
         * 
         * @param assembly the frame assembly that owns the tile
         * @param tileIndex the zero-based tile index within the frame
         * @return tile-local decode state for the selected tile
         */
        fun create(assembly: FrameAssembly, tileIndex: Int): TileDecodeContext {
            val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
            return create(
                nonNullAssembly,
                tileIndex,
                CdfContext.Companion.createDefault(
                    nonNullAssembly.frameHeader().quantization().baseQIndex()
                )
            )
        }

        /** Creates tile-local decode state with a copy of the supplied base CDF context.
         * 
         * @param assembly the frame assembly that owns the tile
         * @param tileIndex the zero-based tile index within the frame
         * @param baseCdfContext the base CDF context template to copy for this tile
         * @return tile-local decode state for the selected tile
         */
        fun create(
            assembly: FrameAssembly,
            tileIndex: Int,
            baseCdfContext: CdfContext
        ): TileDecodeContext {
            val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
            return create(
                nonNullAssembly,
                tileIndex,
                baseCdfContext,
                ReferenceMotionVectorProjection.Companion.create(
                    nonNullAssembly,
                    arrayOfNullsCompat<ReferenceFrameSyntaxState?>(8)
                ),
                SegmentIdMap.Companion.create(nonNullAssembly),
                null
            )
        }

        /** Creates tile-local decode state with supplied entropy and temporal-projection state.
         * 
         * The CDF context is copied for the tile. The immutable projection is shared without copying.
         * 
         * @param assembly the frame assembly that owns the tile
         * @param tileIndex the zero-based tile index within the frame
         * @param baseCdfContext the base CDF context template to copy for this tile
         * @param referenceMotionVectorProjection the immutable current-frame temporal projection
         * @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
         * @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
         * @return tile-local decode state for the selected tile
         */
        fun create(
            assembly: FrameAssembly,
            tileIndex: Int,
            baseCdfContext: CdfContext,
            referenceMotionVectorProjection: ReferenceMotionVectorProjection,
            currentSegmentIdMap: SegmentIdMap,
            referenceSegmentIdMap: SegmentIdMap?
        ): TileDecodeContext {
            return create(
                assembly,
                tileIndex,
                baseCdfContext,
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap,
                true
            )
        }

        /** Creates tile-local decode state by taking ownership of a fresh CDF context.
         * 
         * The caller must not access or modify `ownedCdfContext` after this method returns. The
         * immutable projection is shared, and the segment-id maps retain their existing ownership.
         * 
         * @param assembly the frame assembly that owns the tile
         * @param tileIndex the zero-based tile index within the frame
         * @param ownedCdfContext the fresh CDF context transferred to this tile
         * @param referenceMotionVectorProjection the immutable current-frame temporal projection
         * @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
         * @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
         * @return tile-local decode state for the selected tile
         */
        fun createWithOwnedCdfContext(
            assembly: FrameAssembly,
            tileIndex: Int,
            ownedCdfContext: CdfContext,
            referenceMotionVectorProjection: ReferenceMotionVectorProjection,
            currentSegmentIdMap: SegmentIdMap,
            referenceSegmentIdMap: SegmentIdMap?
        ): TileDecodeContext {
            return create(
                assembly,
                tileIndex,
                ownedCdfContext,
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap,
                false
            )
        }

        /** Creates tile-local decode state with either copied or transferred CDF storage.
         * 
         * @param assembly the frame assembly that owns the tile
         * @param tileIndex the zero-based tile index within the frame
         * @param baseCdfContext the base or transferred CDF context
         * @param referenceMotionVectorProjection the immutable current-frame temporal projection
         * @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
         * @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
         * @param copyCdfContext whether to copy the supplied CDF context
         * @return tile-local decode state for the selected tile
         */
        fun create(
            assembly: FrameAssembly,
            tileIndex: Int,
            baseCdfContext: CdfContext,
            referenceMotionVectorProjection: ReferenceMotionVectorProjection,
            currentSegmentIdMap: SegmentIdMap,
            referenceSegmentIdMap: SegmentIdMap?,
            copyCdfContext: Boolean
        ): TileDecodeContext {
            val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
            val checkedCdfContext =
                requireNotNullCompat(baseCdfContext, "baseCdfContext")
            val tileCdfContext = if (copyCdfContext) checkedCdfContext.copy() else checkedCdfContext
            val nonNullReferenceMotionVectorProjection =
                requireNotNullCompat(
                    referenceMotionVectorProjection,
                    "referenceMotionVectorProjection"
                )
            val nonNullCurrentSegmentIdMap =
                requireNotNullCompat(currentSegmentIdMap, "currentSegmentIdMap")
            val tileBitstream = nonNullAssembly.tileBitstream(tileIndex)
            val sequenceHeader = nonNullAssembly.sequenceHeader()
            val frameHeader = nonNullAssembly.frameHeader()
            val tiling = frameHeader.tiling()

            val columns = tiling.columns()
            val tileRow = tileIndex / columns
            val tileColumn = tileIndex % columns
            val columnStartSuperblock = tiling.columnStartSuperblock(tileColumn)
            val columnEndSuperblock = tiling.columnStartSuperblock(tileColumn + 1)
            val rowStartSuperblock = tiling.rowStartSuperblock(tileRow)
            val rowEndSuperblock = tiling.rowStartSuperblock(tileRow + 1)
            val superblockSize = if (sequenceHeader.features().use128x128Superblocks()) 128 else 64
            val startX = columnStartSuperblock * superblockSize
            val endX =
                min(frameHeader.frameSize().codedWidth(), columnEndSuperblock * superblockSize)
            val startY = rowStartSuperblock * superblockSize
            val endY = min(frameHeader.frameSize().height(), rowEndSuperblock * superblockSize)
            val superblockSize4 = superblockSize shr 2
            val frameWidth4 = ((frameHeader.frameSize().codedWidth() + 7) shr 3) shl 1
            val frameHeight4 = ((frameHeader.frameSize().height() + 7) shr 3) shl 1
            val startX4 = columnStartSuperblock * superblockSize4
            val endX4 = min(frameWidth4, columnEndSuperblock * superblockSize4)
            val startY4 = rowStartSuperblock * superblockSize4
            val endY4 = min(frameHeight4, rowEndSuperblock * superblockSize4)
            val codedWidth4 = endX4 - startX4
            val codedHeight4 = endY4 - startY4
            val width8 = (codedWidth4 + 1) shr 1
            val height8 = (codedHeight4 + 1) shr 1

            return TileDecodeContext(
                nonNullAssembly,
                sequenceHeader,
                frameHeader,
                tileBitstream,
                tileBitstream.openMsacDecoder(frameHeader.disableCdfUpdate()),
                tileCdfContext,
                TemporalMotionField(width8, height8),
                nonNullReferenceMotionVectorProjection,
                nonNullCurrentSegmentIdMap,
                referenceSegmentIdMap,
                BlockSyntaxState(frameHeader.quantization().baseQIndex()),
                RestorationUnitMap.Companion.createEmpty(nonNullAssembly),
                tileIndex,
                tileRow,
                tileColumn,
                superblockSize,
                columnStartSuperblock,
                columnEndSuperblock,
                rowStartSuperblock,
                rowEndSuperblock,
                startX,
                endX,
                startY,
                endY,
                codedWidth4,
                codedHeight4
            )
        }
    }
}
