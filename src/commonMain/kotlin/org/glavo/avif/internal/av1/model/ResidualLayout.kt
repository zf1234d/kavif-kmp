// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Decoded block-level luma and chroma transform residual units in bitstream order. */
class ResidualLayout private constructor(
    position: BlockPosition,
    blockSize: BlockSize,
    lumaUnits: Array<TransformResidualUnit>,
    chromaUUnits: Array<TransformResidualUnit>,
    chromaVUnits: Array<TransformResidualUnit>,
    copyUnits: Boolean
) {
    /** The local tile-relative luma-grid origin of the owning block. */
    private val position: BlockPosition

    /** The coded block size that owns this residual layout. */
    private val blockSize: BlockSize

    /** The luma transform residual units in bitstream order. */
    private val lumaUnits: Array<TransformResidualUnit>

    /** The chroma U transform residual units in bitstream order. */
    private val chromaUUnits: Array<TransformResidualUnit>

    /** The chroma V transform residual units in bitstream order. */
    private val chromaVUnits: Array<TransformResidualUnit>

    /** Creates one block-level residual layout.
     * 
     * @param position the local tile-relative luma-grid origin of the owning block
     * @param blockSize the coded block size that owns this residual layout
     * @param lumaUnits the luma transform residual units in bitstream order
     * @param chromaUUnits the chroma U transform residual units in bitstream order
     * @param chromaVUnits the chroma V transform residual units in bitstream order
     */
    /** Creates one block-level residual layout.
     * 
     * @param position the local tile-relative luma-grid origin of the owning block
     * @param blockSize the coded block size that owns this residual layout
     * @param lumaUnits the luma transform residual units in bitstream order
     */
    constructor(
        position: BlockPosition,
        blockSize: BlockSize,
        lumaUnits: Array<TransformResidualUnit>,
        chromaUUnits: Array<TransformResidualUnit> = EMPTY_UNITS,
        chromaVUnits: Array<TransformResidualUnit> = EMPTY_UNITS
    ) : this(position, blockSize, lumaUnits, chromaUUnits, chromaVUnits, true)

    init {
        this.position = requireNotNullCompat(position, "position")
        this.blockSize = requireNotNullCompat(blockSize, "blockSize")
        val checkedLumaUnits =
            requireNotNullCompat(lumaUnits, "lumaUnits")
        this.lumaUnits =
            if (copyUnits) checkedLumaUnits.copyOf() else checkedLumaUnits
        require(this.lumaUnits.size != 0) { "lumaUnits must not be empty" }
        val checkedChromaUUnits =
            requireNotNullCompat(chromaUUnits, "chromaUUnits")
        val checkedChromaVUnits =
            requireNotNullCompat(chromaVUnits, "chromaVUnits")
        this.chromaUUnits = if (checkedChromaUUnits.size == 0)
            EMPTY_UNITS
        else
            if (copyUnits) checkedChromaUUnits.copyOf() else checkedChromaUUnits
        this.chromaVUnits = if (checkedChromaVUnits.size == 0)
            EMPTY_UNITS
        else
            if (copyUnits) checkedChromaVUnits.copyOf() else checkedChromaVUnits
        require(this.chromaUUnits.size == this.chromaVUnits.size) { "chromaUUnits and chromaVUnits must have the same length" }
    }

    /** Returns the local tile-relative luma-grid origin of the owning block.
     * 
     * @return the local tile-relative luma-grid origin of the owning block
     */
    fun position(): BlockPosition {
        return position
    }

    /** Returns a copy of this residual layout offset by the supplied 4x4-unit delta.
     * 
     * @param deltaX4 the X-axis offset in 4x4 units
     * @param deltaY4 the Y-axis offset in 4x4 units
     * @return a copy of this residual layout offset by the supplied 4x4-unit delta
     */
    fun withOffset(deltaX4: Int, deltaY4: Int): ResidualLayout {
        if (deltaX4 == 0 && deltaY4 == 0) {
            return this
        }
        return ResidualLayout(
            position.offset(deltaX4, deltaY4),
            blockSize,
            offsetUnits(lumaUnits, deltaX4, deltaY4),
            offsetUnits(chromaUUnits, deltaX4, deltaY4),
            offsetUnits(chromaVUnits, deltaX4, deltaY4),
            false
        )
    }

    /** Returns the coded block size that owns this residual layout.
     * 
     * @return the coded block size that owns this residual layout
     */
    fun blockSize(): BlockSize {
        return blockSize
    }

    /** Returns the luma transform residual units in bitstream order.
     * 
     * @return the luma transform residual units in bitstream order
     */
    fun lumaUnits(): Array<TransformResidualUnit> {
        return lumaUnits.copyOf()
    }

    /** Returns the number of luma residual units.
     * 
     * @return the number of luma residual units
     */
    fun lumaUnitCount(): Int {
        return lumaUnits.size
    }

    /** Returns one luma residual unit without copying the complete unit array.
     * 
     * @param index the zero-based unit index in bitstream order
     * @return the selected luma residual unit
     */
    fun lumaUnit(index: Int): TransformResidualUnit {
        return lumaUnits[index]
    }

    /** Returns the chroma U transform residual units in bitstream order.
     * 
     * @return the chroma U transform residual units in bitstream order
     */
    fun chromaUUnits(): Array<TransformResidualUnit> {
        return chromaUUnits.copyOf()
    }

    /** Returns one chroma U residual unit without copying the complete unit array.
     * 
     * @param index the zero-based unit index in bitstream order
     * @return the selected chroma U residual unit
     */
    fun chromaUUnit(index: Int): TransformResidualUnit {
        return chromaUUnits[index]
    }

    /** Returns the chroma V transform residual units in bitstream order.
     * 
     * @return the chroma V transform residual units in bitstream order
     */
    fun chromaVUnits(): Array<TransformResidualUnit> {
        return chromaVUnits.copyOf()
    }

    /** Returns the number of residual units in each chroma plane.
     * 
     * @return the number of chroma U and chroma V residual units
     */
    fun chromaUnitCount(): Int {
        return chromaUUnits.size
    }

    /** Returns one chroma V residual unit without copying the complete unit array.
     * 
     * @param index the zero-based unit index in bitstream order
     * @return the selected chroma V residual unit
     */
    fun chromaVUnit(index: Int): TransformResidualUnit {
        return chromaVUnits[index]
    }

    /** Returns whether this residual layout carries any modeled chroma residual units.
     * 
     * @return whether this residual layout carries any modeled chroma residual units
     */
    fun hasChromaUnits(): Boolean {
        return chromaUUnits.size != 0
    }

    companion object {
        /** Shared empty residual-unit sequence for layouts without chroma units. */
        val EMPTY_UNITS: Array<TransformResidualUnit> =
            arrayOfNullsCompat<TransformResidualUnit>(0)

        /** Creates one residual layout by taking exclusive ownership of all unit arrays.
         * 
         * The caller must not access or modify any unit array after this method returns.
         * 
         * @param position the local tile-relative luma-grid origin of the owning block
         * @param blockSize the coded block size that owns this residual layout
         * @param lumaUnits the exclusively owned luma transform residual units in bitstream order
         * @param chromaUUnits the exclusively owned chroma U transform residual units in bitstream order
         * @param chromaVUnits the exclusively owned chroma V transform residual units in bitstream order
         * @return one residual layout backed by the supplied unit arrays
         */
        fun fromOwnedUnits(
            position: BlockPosition,
            blockSize: BlockSize,
            lumaUnits: Array<TransformResidualUnit>,
            chromaUUnits: Array<TransformResidualUnit>,
            chromaVUnits: Array<TransformResidualUnit>
        ): ResidualLayout {
            return ResidualLayout(
                position,
                blockSize,
                lumaUnits,
                chromaUUnits,
                chromaVUnits,
                false
            )
        }

        /** Returns residual units offset by the supplied 4x4-unit delta.
         * 
         * @param units the source residual units
         * @param deltaX4 the X-axis offset in 4x4 units
         * @param deltaY4 the Y-axis offset in 4x4 units
         * @return residual units offset by the supplied 4x4-unit delta
         */
        fun offsetUnits(
            units: Array<TransformResidualUnit>,
            deltaX4: Int,
            deltaY4: Int
        ): Array<TransformResidualUnit> {
            if (units.size == 0) {
                return EMPTY_UNITS
            }
            val offsetUnits: Array<TransformResidualUnit> =
                arrayOfNullsCompat<TransformResidualUnit>(units.size)
            for (i in units.indices) {
                val unit = units[i]
                offsetUnits[i] = unit.withPosition(unit.position().offset(deltaX4, deltaY4))
            }
            return offsetUnits
        }
    }
}
