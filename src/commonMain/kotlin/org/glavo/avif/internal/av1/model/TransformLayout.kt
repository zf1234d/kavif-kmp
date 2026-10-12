// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** One decoded block-level luma and chroma transform layout produced before coefficient syntax is read. */
class TransformLayout private constructor(
    position: BlockPosition,
    blockSize: BlockSize,
    visibleWidth4: Int,
    visibleHeight4: Int,
    visibleWidthPixels: Int,
    visibleHeightPixels: Int,
    maxLumaTransformSize: TransformSize,
    chromaTransformSize: TransformSize?,
    variableLumaTransformTree: Boolean,
    lumaUnits: Array<TransformUnit>,
    chromaUnits: Array<TransformUnit>,
    copyUnits: Boolean
) {
    /** The local tile-relative luma-grid origin of the owning block. */
    private val position: BlockPosition

    /** The coded block size that owns this transform layout. */
    private val blockSize: BlockSize

    /** The visible block width in 4x4 units after clipping against tile bounds. */
    private val visibleWidth4: Int

    /** The visible block height in 4x4 units after clipping against tile bounds. */
    private val visibleHeight4: Int

    /** The exact visible block width in pixels after clipping against tile bounds. */
    private val visibleWidthPixels: Int

    /** The exact visible block height in pixels after clipping against tile bounds. */
    private val visibleHeightPixels: Int

    /** The largest luma transform size allowed by the current block and frame layout. */
    private val maxLumaTransformSize: TransformSize

    /** The largest chroma transform size allowed by the current block and frame layout, or `null`. */
    private val chromaTransformSize: TransformSize?

    /** Whether this layout came from a variable luma transform tree. */
    private val variableLumaTransformTree: Boolean

    /** The luma transform units in bitstream order. */
    private val lumaUnits: Array<TransformUnit>

    /** The shared chroma transform units for the U and V planes in bitstream order. */
    private val chromaUnits: Array<TransformUnit>

    /** Creates one decoded block-level transform layout.
     * 
     * @param position the local tile-relative luma-grid origin of the owning block
     * @param blockSize the coded block size that owns this transform layout
     * @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
     * @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
     * @param visibleWidthPixels the exact coded-grid block width in pixels after clipping against tile bounds
     * @param visibleHeightPixels the exact coded-grid block height in pixels after clipping against tile bounds
     * @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
     * @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
     * @param variableLumaTransformTree whether this layout came from a variable luma transform tree
     * @param lumaUnits the luma transform units in bitstream order
     * @param chromaUnits the shared chroma transform units for the U and V planes in bitstream order
     */
    /** Creates one decoded block-level transform layout.
     * 
     * @param position the local tile-relative luma-grid origin of the owning block
     * @param blockSize the coded block size that owns this transform layout
     * @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
     * @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
     * @param visibleWidthPixels the exact coded-grid block width in pixels after clipping against tile bounds
     * @param visibleHeightPixels the exact coded-grid block height in pixels after clipping against tile bounds
     * @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
     * @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
     * @param variableLumaTransformTree whether this layout came from a variable luma transform tree
     * @param lumaUnits the luma transform units in bitstream order
     */
    constructor(
        position: BlockPosition,
        blockSize: BlockSize,
        visibleWidth4: Int,
        visibleHeight4: Int,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        maxLumaTransformSize: TransformSize,
        chromaTransformSize: TransformSize?,
        variableLumaTransformTree: Boolean,
        lumaUnits: Array<TransformUnit>,
        chromaUnits: Array<TransformUnit> = defaultChromaUnits(position, chromaTransformSize)
    ) : this(
        position,
        blockSize,
        visibleWidth4,
        visibleHeight4,
        visibleWidthPixels,
        visibleHeightPixels,
        maxLumaTransformSize,
        chromaTransformSize,
        variableLumaTransformTree,
        lumaUnits,
        chromaUnits,
        true
    )

    init {
        this.position = requireNotNullCompat(position, "position")
        this.blockSize = requireNotNullCompat(blockSize, "blockSize")
        require(!(visibleWidth4 <= 0 || visibleWidth4 > blockSize.width4())) { "visibleWidth4 out of range: $visibleWidth4" }
        require(!(visibleHeight4 <= 0 || visibleHeight4 > blockSize.height4())) { "visibleHeight4 out of range: $visibleHeight4" }
        this.visibleWidth4 = visibleWidth4
        this.visibleHeight4 = visibleHeight4
        require(!(visibleWidthPixels <= 0 || visibleWidthPixels > blockSize.widthPixels())) { "visibleWidthPixels out of range: $visibleWidthPixels" }
        require(!(visibleHeightPixels <= 0 || visibleHeightPixels > blockSize.heightPixels())) { "visibleHeightPixels out of range: $visibleHeightPixels" }
        require(visibleWidthPixels <= (visibleWidth4 shl 2)) { "visibleWidthPixels exceeds visibleWidth4 coverage" }
        require(visibleHeightPixels <= (visibleHeight4 shl 2)) { "visibleHeightPixels exceeds visibleHeight4 coverage" }
        this.visibleWidthPixels = visibleWidthPixels
        this.visibleHeightPixels = visibleHeightPixels
        this.maxLumaTransformSize =
            requireNotNullCompat(maxLumaTransformSize, "maxLumaTransformSize")
        this.chromaTransformSize = chromaTransformSize
        this.variableLumaTransformTree = variableLumaTransformTree
        val checkedLumaUnits = requireNotNullCompat(lumaUnits, "lumaUnits")
        this.lumaUnits =
            if (copyUnits) checkedLumaUnits.copyOf() else checkedLumaUnits
        require(this.lumaUnits.size != 0) { "lumaUnits must not be empty" }
        val checkedChromaUnits =
            requireNotNullCompat(chromaUnits, "chromaUnits")
        this.chromaUnits = if (checkedChromaUnits.size == 0)
            EMPTY_UNITS
        else
            if (copyUnits) checkedChromaUnits.copyOf() else checkedChromaUnits
        require(!(chromaTransformSize == null && this.chromaUnits.size != 0)) { "chromaUnits must be empty when chromaTransformSize is null" }
        require(!(chromaTransformSize != null && this.chromaUnits.size == 0)) { "chromaUnits must not be empty when chromaTransformSize is present" }
        for (chromaUnit in this.chromaUnits) {
            require(chromaUnit.size == chromaTransformSize) { "chroma unit size does not match chromaTransformSize" }
        }
    }

    /** Creates one decoded block-level transform layout whose exact visible pixel dimensions match
     * its visible 4x4-grid coverage and whose chroma layout contains one unit when present.
     * 
     * @param position the local tile-relative luma-grid origin of the owning block
     * @param blockSize the coded block size that owns this transform layout
     * @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
     * @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
     * @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
     * @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
     * @param variableLumaTransformTree whether this layout came from a variable luma transform tree
     * @param lumaUnits the luma transform units in bitstream order
     */
    constructor(
        position: BlockPosition,
        blockSize: BlockSize,
        visibleWidth4: Int,
        visibleHeight4: Int,
        maxLumaTransformSize: TransformSize,
        chromaTransformSize: TransformSize?,
        variableLumaTransformTree: Boolean,
        lumaUnits: Array<TransformUnit>
    ) : this(
        position,
        blockSize,
        visibleWidth4,
        visibleHeight4,
        visibleWidth4 shl 2,
        visibleHeight4 shl 2,
        maxLumaTransformSize,
        chromaTransformSize,
        variableLumaTransformTree,
        lumaUnits,
        defaultChromaUnits(position, chromaTransformSize)
    )

    /** Returns the local tile-relative luma-grid origin of the owning block.
     * 
     * @return the local tile-relative luma-grid origin of the owning block
     */
    fun position(): BlockPosition {
        return position
    }

    /** Returns a copy of this transform layout offset by the supplied 4x4-unit delta.
     * 
     * @param deltaX4 the X-axis offset in 4x4 units
     * @param deltaY4 the Y-axis offset in 4x4 units
     * @return a copy of this transform layout offset by the supplied 4x4-unit delta
     */
    fun withOffset(deltaX4: Int, deltaY4: Int): TransformLayout {
        if (deltaX4 == 0 && deltaY4 == 0) {
            return this
        }
        return TransformLayout(
            position.offset(deltaX4, deltaY4),
            blockSize,
            visibleWidth4,
            visibleHeight4,
            visibleWidthPixels,
            visibleHeightPixels,
            maxLumaTransformSize,
            chromaTransformSize,
            variableLumaTransformTree,
            offsetUnits(lumaUnits, deltaX4, deltaY4),
            offsetUnits(chromaUnits, deltaX4, deltaY4),
            false
        )
    }

    /** Returns the coded block size that owns this transform layout.
     * 
     * @return the coded block size that owns this transform layout
     */
    fun blockSize(): BlockSize {
        return blockSize
    }

    /** Returns the visible block width in 4x4 units after clipping against tile bounds.
     * 
     * @return the visible block width in 4x4 units after clipping against tile bounds
     */
    fun visibleWidth4(): Int {
        return visibleWidth4
    }

    /** Returns the visible block height in 4x4 units after clipping against tile bounds.
     * 
     * @return the visible block height in 4x4 units after clipping against tile bounds
     */
    fun visibleHeight4(): Int {
        return visibleHeight4
    }

    /** Returns the exact visible block width in pixels after clipping against tile bounds.
     * 
     * @return the exact visible block width in pixels after clipping against tile bounds
     */
    fun visibleWidthPixels(): Int {
        return visibleWidthPixels
    }

    /** Returns the exact visible block height in pixels after clipping against tile bounds.
     * 
     * @return the exact visible block height in pixels after clipping against tile bounds
     */
    fun visibleHeightPixels(): Int {
        return visibleHeightPixels
    }

    /** Returns the largest luma transform size allowed by the current block and frame layout.
     * 
     * @return the largest luma transform size allowed by the current block and frame layout
     */
    fun maxLumaTransformSize(): TransformSize {
        return maxLumaTransformSize
    }

    /** Returns the largest chroma transform size allowed by the current block and frame layout, or `null`.
     * 
     * @return the largest chroma transform size allowed by the current block and frame layout, or `null`
     */
    fun chromaTransformSize(): TransformSize? {
        return chromaTransformSize
    }

    /** Returns whether this layout came from a variable luma transform tree.
     * 
     * @return whether this layout came from a variable luma transform tree
     */
    fun variableLumaTransformTree(): Boolean {
        return variableLumaTransformTree
    }

    /** Returns the luma transform units in bitstream order.
     * 
     * @return the luma transform units in bitstream order
     */
    fun lumaUnits(): Array<TransformUnit> {
        return lumaUnits.copyOf()
    }

    /** Returns the number of luma transform units.
     * 
     * @return the number of luma transform units
     */
    fun lumaUnitCount(): Int {
        return lumaUnits.size
    }

    /** Returns one luma transform unit without copying the complete unit array.
     * 
     * @param index the zero-based unit index in bitstream order
     * @return the selected luma transform unit
     */
    fun lumaUnit(index: Int): TransformUnit {
        return lumaUnits[index]
    }

    /** Returns the shared chroma transform units for the U and V planes in bitstream order.
     * 
     * @return the shared chroma transform units for the U and V planes in bitstream order
     */
    fun chromaUnits(): Array<TransformUnit> {
        return chromaUnits.copyOf()
    }

    /** Returns the number of shared chroma transform units.
     * 
     * @return the number of chroma transform units
     */
    fun chromaUnitCount(): Int {
        return chromaUnits.size
    }

    /** Returns one shared chroma transform unit without copying the complete unit array.
     * 
     * @param index the zero-based unit index in bitstream order
     * @return the selected chroma transform unit
     */
    fun chromaUnit(index: Int): TransformUnit {
        return chromaUnits[index]
    }

    /** Returns the uniform luma transform size, or `null` when the layout mixes sizes.
     * 
     * @return the uniform luma transform size, or `null` when the layout mixes sizes
     */
    fun uniformLumaTransformSize(): TransformSize? {
        val uniformSize = lumaUnits[0].size
        for (i in 1..<lumaUnits.size) {
            if (lumaUnits[i].size != uniformSize) {
                return null
            }
        }
        return uniformSize
    }

    companion object {
        /** Shared empty transform-unit sequence for layouts without chroma units. */
        val EMPTY_UNITS: Array<TransformUnit> = arrayOfNullsCompat<TransformUnit>(0)

        /** Creates one transform layout by taking exclusive ownership of both unit arrays.
         * 
         * The caller must not access or modify either unit array after this method returns.
         * 
         * @param position the local tile-relative luma-grid origin of the owning block
         * @param blockSize the coded block size that owns this transform layout
         * @param visibleWidth4 the visible block width in 4x4 units after clipping against tile bounds
         * @param visibleHeight4 the visible block height in 4x4 units after clipping against tile bounds
         * @param visibleWidthPixels the exact coded-grid block width in pixels after clipping against tile bounds
         * @param visibleHeightPixels the exact coded-grid block height in pixels after clipping against tile bounds
         * @param maxLumaTransformSize the largest luma transform size allowed by the current block and frame layout
         * @param chromaTransformSize the largest chroma transform size allowed by the current block and frame layout, or `null`
         * @param variableLumaTransformTree whether this layout came from a variable luma transform tree
         * @param lumaUnits the exclusively owned luma transform units in bitstream order
         * @param chromaUnits the exclusively owned shared chroma transform units in bitstream order
         * @return one transform layout backed by the supplied unit arrays
         */
        fun fromOwnedUnits(
            position: BlockPosition,
            blockSize: BlockSize,
            visibleWidth4: Int,
            visibleHeight4: Int,
            visibleWidthPixels: Int,
            visibleHeightPixels: Int,
            maxLumaTransformSize: TransformSize,
            chromaTransformSize: TransformSize?,
            variableLumaTransformTree: Boolean,
            lumaUnits: Array<TransformUnit>,
            chromaUnits: Array<TransformUnit>
        ): TransformLayout {
            return TransformLayout(
                position,
                blockSize,
                visibleWidth4,
                visibleHeight4,
                visibleWidthPixels,
                visibleHeightPixels,
                maxLumaTransformSize,
                chromaTransformSize,
                variableLumaTransformTree,
                lumaUnits,
                chromaUnits,
                false
            )
        }

        /** Returns a single-unit chroma layout when a chroma transform size is present.
         * 
         * @param position the local tile-relative luma-grid origin of the owning block
         * @param chromaTransformSize the chroma transform size, or `null`
         * @return the single-unit chroma layout, or an empty array for monochrome input
         */
        fun defaultChromaUnits(
            position: BlockPosition,
            chromaTransformSize: TransformSize?
        ): Array<TransformUnit> {
            if (chromaTransformSize == null) {
                return EMPTY_UNITS
            }
            return arrayOf<TransformUnit>(TransformUnit(position, chromaTransformSize))
        }

        /** Returns transform units offset by the supplied 4x4-unit delta.
         * 
         * @param units the source transform units
         * @param deltaX4 the X-axis offset in 4x4 units
         * @param deltaY4 the Y-axis offset in 4x4 units
         * @return transform units offset by the supplied 4x4-unit delta
         */
        fun offsetUnits(
            units: Array<TransformUnit>,
            deltaX4: Int,
            deltaY4: Int
        ): Array<TransformUnit> {
            if (units.size == 0) {
                return EMPTY_UNITS
            }
            val offsetUnits: Array<TransformUnit> = arrayOfNullsCompat<TransformUnit>(units.size)
            for (i in units.indices) {
                val unit = units[i]
                offsetUnits[i] = unit.withPosition(unit.position.offset(deltaX4, deltaY4))
            }
            return offsetUnits
        }
    }
}
