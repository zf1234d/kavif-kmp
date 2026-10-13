// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** One decoded transform residual unit.
 * 
 * Coefficients are stored as signed transform-domain levels in natural raster order under the
 * explicit transform type carried by this unit. The metadata also retains the visible write-back
 * rectangle and coefficient-neighbor context produced by AV1 entropy decoding. */
class TransformResidualUnit {
    /** The block origin of this transform residual unit in luma 4x4 units. */
    private val position: BlockPosition

    /** The transform size used by this residual unit. */
    private val size: TransformSize

    /** The transform type used by this residual unit. */
    private val transformType: TransformType

    /** The scan index of the last non-zero coefficient, or `-1` for all-zero units. */
    private val endOfBlockIndex: Int

    /** The signed transform-domain coefficients in natural raster order, or empty internal
     * storage for all-zero and compact DC-only units. */
    private val coefficients: IntArray

    /** The signed DC coefficient, or zero for an all-zero unit. */
    private val dcCoefficient: Int

    /** The exact portion of the residual width that lies inside the coded frame or tile boundary. */
    private val visibleWidthPixels: Int

    /** The exact portion of the residual height that lies inside the coded frame or tile boundary. */
    private val visibleHeightPixels: Int

    /** The coefficient-context byte written back to neighbor state. */
    private val coefficientContextByte: Int

    /** Creates one transform residual unit.
     * 
     * @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
     * @param size the transform size used by this residual unit
     * @param transformType the transform type used by this residual unit
     * @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
     * @param coefficients the signed transform-domain coefficients in natural raster order
     * @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
     * @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
     * @param coefficientContextByte the coefficient-context byte written back to neighbor state
     */
    constructor(
        position: BlockPosition,
        size: TransformSize,
        transformType: TransformType,
        endOfBlockIndex: Int,
        coefficients: IntArray,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        coefficientContextByte: Int
    ) : this(
        position,
        size,
        transformType,
        endOfBlockIndex,
        coefficients,
        visibleWidthPixels,
        visibleHeightPixels,
        coefficientContextByte,
        true
    )

    /** Creates one residual unit with copied or exclusively transferred coefficients.
     * 
     * @param position the residual-unit origin
     * @param size the transform size
     * @param transformType the transform type
     * @param endOfBlockIndex the last non-zero scan index
     * @param coefficients the natural-order coefficients
     * @param visibleWidthPixels the visible residual width
     * @param visibleHeightPixels the visible residual height
     * @param coefficientContextByte the coefficient-context byte
     * @param copyCoefficients whether to copy non-zero coefficient storage
     */
    private constructor(
        position: BlockPosition,
        size: TransformSize,
        transformType: TransformType,
        endOfBlockIndex: Int,
        coefficients: IntArray,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        coefficientContextByte: Int,
        copyCoefficients: Boolean
    ) {
        this.position = requireNotNullCompat(position, "position")
        this.size = requireNotNullCompat(size, "size")
        this.transformType = requireNotNullCompat(transformType, "transformType")
        require(!(endOfBlockIndex < -1 || endOfBlockIndex >= size.widthPixels() * size.heightPixels())) { "endOfBlockIndex out of range: $endOfBlockIndex" }
        this.endOfBlockIndex = endOfBlockIndex
        val checkedCoefficients = requireNotNullCompat(coefficients, "coefficients")
        require(checkedCoefficients.size == size.widthPixels() * size.heightPixels()) { "coefficients length does not match transform area" }
        require(!(visibleWidthPixels <= 0 || visibleWidthPixels > size.widthPixels())) { "visibleWidthPixels out of range: $visibleWidthPixels" }
        require(!(visibleHeightPixels <= 0 || visibleHeightPixels > size.heightPixels())) { "visibleHeightPixels out of range: $visibleHeightPixels" }
        if (endOfBlockIndex < 0) {
            for (coefficient in checkedCoefficients) {
                require(coefficient == 0) { "all-zero residual units must not carry coefficients" }
            }
        }
        this.coefficients = if (endOfBlockIndex < 0)
            EMPTY_COEFFICIENTS
        else
            if (copyCoefficients) checkedCoefficients.copyOf() else checkedCoefficients
        this.dcCoefficient = if (endOfBlockIndex < 0) 0 else checkedCoefficients[0]
        this.visibleWidthPixels = visibleWidthPixels
        this.visibleHeightPixels = visibleHeightPixels
        require(coefficientContextByte in 0..0xFF) { "coefficientContextByte out of range: $coefficientContextByte" }
        this.coefficientContextByte = coefficientContextByte
    }

    /** Creates one compact DC-only transform residual unit.
     * 
     * @param position the residual-unit origin in luma 4x4 units
     * @param size the transform size
     * @param transformType the transform type
     * @param dcCoefficient the signed DC coefficient
     * @param visibleWidthPixels the visible residual width
     * @param visibleHeightPixels the visible residual height
     * @param coefficientContextByte the coefficient-context byte
     */
    private constructor(
        position: BlockPosition,
        size: TransformSize,
        transformType: TransformType,
        dcCoefficient: Int,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        coefficientContextByte: Int
    ) {
        this.position = requireNotNullCompat(position, "position")
        this.size = requireNotNullCompat(size, "size")
        this.transformType = requireNotNullCompat(transformType, "transformType")
        this.endOfBlockIndex = 0
        this.coefficients = EMPTY_COEFFICIENTS
        this.dcCoefficient = dcCoefficient
        require(!(visibleWidthPixels <= 0 || visibleWidthPixels > size.widthPixels())) { "visibleWidthPixels out of range: $visibleWidthPixels" }
        require(!(visibleHeightPixels <= 0 || visibleHeightPixels > size.heightPixels())) { "visibleHeightPixels out of range: $visibleHeightPixels" }
        this.visibleWidthPixels = visibleWidthPixels
        this.visibleHeightPixels = visibleHeightPixels
        require(coefficientContextByte in 0..0xFF) { "coefficientContextByte out of range: $coefficientContextByte" }
        this.coefficientContextByte = coefficientContextByte
    }

    /** Creates one all-zero transform residual unit using the shared empty coefficient representation.
     * 
     * @param position the tile-relative block origin in luma 4x4 units
     * @param size the transform size used by this residual unit
     * @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
     * @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
     * @param coefficientContextByte the coefficient-context byte written back to neighbor state
     */
    private constructor(
        position: BlockPosition,
        size: TransformSize,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        coefficientContextByte: Int
    ) {
        this.position = requireNotNullCompat(position, "position")
        this.size = requireNotNullCompat(size, "size")
        this.transformType = TransformType.DCT_DCT
        this.endOfBlockIndex = -1
        this.coefficients = EMPTY_COEFFICIENTS
        this.dcCoefficient = 0
        require(!(visibleWidthPixels <= 0 || visibleWidthPixels > size.widthPixels())) { "visibleWidthPixels out of range: $visibleWidthPixels" }
        require(!(visibleHeightPixels <= 0 || visibleHeightPixels > size.heightPixels())) { "visibleHeightPixels out of range: $visibleHeightPixels" }
        this.visibleWidthPixels = visibleWidthPixels
        this.visibleHeightPixels = visibleHeightPixels
        require(coefficientContextByte in 0..0xFF) { "coefficientContextByte out of range: $coefficientContextByte" }
        this.coefficientContextByte = coefficientContextByte
    }

    /** Creates a position-adjusted unit that shares the source unit's immutable coefficients.
     * 
     * @param position the replacement residual-unit position
     * @param source the residual unit whose non-position state is retained
     */
    private constructor(position: BlockPosition, source: TransformResidualUnit) {
        this.position = requireNotNullCompat(position, "position")
        val nonNullSource = requireNotNullCompat(source, "source")
        this.size = nonNullSource.size
        this.transformType = nonNullSource.transformType
        this.endOfBlockIndex = nonNullSource.endOfBlockIndex
        this.coefficients = nonNullSource.coefficients
        this.dcCoefficient = nonNullSource.dcCoefficient
        this.visibleWidthPixels = nonNullSource.visibleWidthPixels
        this.visibleHeightPixels = nonNullSource.visibleHeightPixels
        this.coefficientContextByte = nonNullSource.coefficientContextByte
    }

    /** Creates one transform residual unit whose exact visible footprint matches the coded
     * transform size.
     * 
     * @param position the tile-relative block origin of this transform residual unit in luma 4x4 units
     * @param size the transform size used by this residual unit
     * @param transformType the transform type used by this residual unit
     * @param endOfBlockIndex the scan index of the last non-zero coefficient, or `-1` for all-zero units
     * @param coefficients the signed transform-domain coefficients in natural raster order
     * @param coefficientContextByte the coefficient-context byte written back to neighbor state
     */
    constructor(
        position: BlockPosition,
        size: TransformSize,
        transformType: TransformType,
        endOfBlockIndex: Int,
        coefficients: IntArray,
        coefficientContextByte: Int
    ) : this(
        position,
        size,
        transformType,
        endOfBlockIndex,
        coefficients,
        size.widthPixels(),
        size.heightPixels(),
        coefficientContextByte
    )

    /** Returns the block origin of this transform residual unit in luma 4x4 units.
     * 
     * @return the block origin of this transform residual unit in luma 4x4 units
     */
    fun position(): BlockPosition {
        return position
    }

    /** Returns a copy of this residual unit with a replaced position.
     * 
     * @param position the replacement residual-unit position
     * @return a copy of this residual unit with a replaced position
     */
    fun withPosition(position: BlockPosition): TransformResidualUnit {
        val nonNullPosition = requireNotNullCompat(position, "position")
        if (this.position.x4 == nonNullPosition.x4 && this.position.y4 == nonNullPosition.y4) {
            return this
        }
        return TransformResidualUnit(nonNullPosition, this)
    }

    /** Returns the transform size used by this residual unit.
     * 
     * @return the transform size used by this residual unit
     */
    fun size(): TransformSize {
        return size
    }

    /** Returns the transform type used by this residual unit.
     * 
     * @return the transform type used by this residual unit
     */
    fun transformType(): TransformType {
        return transformType
    }

    /** Returns whether the transform block is signaled as all-zero through `txb_skip`.
     * 
     * @return whether the transform block is signaled as all-zero through `txb_skip`
     */
    fun allZero(): Boolean {
        return endOfBlockIndex < 0
    }

    /** Returns the scan index of the last non-zero coefficient, or `-1` for all-zero units.
     * 
     * @return the scan index of the last non-zero coefficient, or `-1` for all-zero units
     */
    fun endOfBlockIndex(): Int {
        return endOfBlockIndex
    }

    /** Returns the signed transform-domain coefficients in natural raster order.
     * 
     * @return the signed transform-domain coefficients in natural raster order
     */
    fun coefficients(): IntArray {
        if (coefficients.size != 0) {
            return coefficients.copyOf()
        }
        val result = IntArray(coefficientCount())
        if (!allZero()) {
            result[0] = dcCoefficient
        }
        return result
    }

    /** Returns the number of coefficients in this transform unit.
     * 
     * @return the transform area in coefficients
     */
    fun coefficientCount(): Int {
        return size.widthPixels() * size.heightPixels()
    }

    /** Returns one signed transform-domain coefficient without copying the coefficient block.
     * 
     * All-zero units return `0` for every valid coefficient index.
     * 
     * @param coefficientIndex the natural raster-order coefficient index
     * @return the signed transform-domain coefficient at `coefficientIndex`
     */
    fun coefficient(coefficientIndex: Int): Int {
        checkIndexCompat(coefficientIndex, coefficientCount())
        if (coefficients.size == 0) {
            return if (coefficientIndex == 0) dcCoefficient else 0
        }
        return coefficients[coefficientIndex]
    }

    /** Returns the exact portion of the residual width inside the coded frame or tile boundary.
     * 
     * @return the exact visible residual width in pixels
     */
    fun visibleWidthPixels(): Int {
        return visibleWidthPixels
    }

    /** Returns the exact portion of the residual height inside the coded frame or tile boundary.
     * 
     * @return the exact visible residual height in pixels
     */
    fun visibleHeightPixels(): Int {
        return visibleHeightPixels
    }

    /** Returns the decoded DC coefficient in natural raster order.
     * 
     * @return the decoded DC coefficient in natural raster order
     */
    fun dcCoefficient(): Int {
        return dcCoefficient
    }

    /** Returns the coefficient-context byte written back to neighbor state.
     * 
     * @return the coefficient-context byte written back to neighbor state
     */
    fun coefficientContextByte(): Int {
        return coefficientContextByte
    }

    companion object {
        /** Shared internal coefficient storage for transform units signaled as all-zero. */
        val EMPTY_COEFFICIENTS: IntArray = IntArray(0)

        /** Creates one residual unit by taking exclusive ownership of its coefficient array.
         * 
         * The caller must not access or modify `coefficients` after this method returns.
         * 
         * @param position the residual-unit origin in luma 4x4 units
         * @param size the transform size
         * @param transformType the transform type
         * @param endOfBlockIndex the last non-zero scan index
         * @param coefficients the exclusively owned natural-order coefficients
         * @param visibleWidthPixels the visible residual width
         * @param visibleHeightPixels the visible residual height
         * @param coefficientContextByte the coefficient-context byte
         * @return one residual unit backed by `coefficients`
         */
        fun fromOwnedCoefficients(
            position: BlockPosition,
            size: TransformSize,
            transformType: TransformType,
            endOfBlockIndex: Int,
            coefficients: IntArray,
            visibleWidthPixels: Int,
            visibleHeightPixels: Int,
            coefficientContextByte: Int
        ): TransformResidualUnit {
            return TransformResidualUnit(
                position,
                size,
                transformType,
                endOfBlockIndex,
                coefficients,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte,
                false
            )
        }

        /** Creates one DC-only residual unit without allocating a full coefficient block.
         * 
         * [coefficient] and [coefficients()] continue to expose the complete natural-order
         * coefficient block, with every AC coefficient equal to zero.
         * 
         * @param position the residual-unit origin in luma 4x4 units
         * @param size the transform size
         * @param transformType the transform type
         * @param dcCoefficient the signed DC coefficient
         * @param visibleWidthPixels the visible residual width
         * @param visibleHeightPixels the visible residual height
         * @param coefficientContextByte the coefficient-context byte
         * @return one compact DC-only residual unit
         */
        fun dcOnly(
            position: BlockPosition,
            size: TransformSize,
            transformType: TransformType,
            dcCoefficient: Int,
            visibleWidthPixels: Int,
            visibleHeightPixels: Int,
            coefficientContextByte: Int
        ): TransformResidualUnit {
            return TransformResidualUnit(
                position,
                size,
                transformType,
                dcCoefficient,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte
            )
        }

        /** Creates one all-zero `DCT_DCT` residual unit without allocating coefficient storage.
         * 
         * @param position the tile-relative block origin in luma 4x4 units
         * @param size the transform size used by this residual unit
         * @param visibleWidthPixels the exact portion of the residual width inside the coded frame or tile boundary
         * @param visibleHeightPixels the exact portion of the residual height inside the coded frame or tile boundary
         * @param coefficientContextByte the coefficient-context byte written back to neighbor state
         * @return one all-zero transform residual unit
         */
        fun allZero(
            position: BlockPosition,
            size: TransformSize,
            visibleWidthPixels: Int,
            visibleHeightPixels: Int,
            coefficientContextByte: Int
        ): TransformResidualUnit {
            return TransformResidualUnit(
                position,
                size,
                visibleWidthPixels,
                visibleHeightPixels,
                coefficientContextByte
            )
        }
    }
}
