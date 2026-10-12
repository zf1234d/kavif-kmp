// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** One AV1 rectangular transform size.
 * 
 * Width and height are expressed in luma-grid 4x4 units to match the existing block-position
 * model used by the structural syntax reader. */
enum class TransformSize
/** Creates one AV1 transform-size entry.
 * 
 * @param width4 the transform width in 4x4 units
 * @param height4 the transform height in 4x4 units
 * @param log2Width4 the base-2 logarithm of the transform width in 4x4 units
 * @param log2Height4 the base-2 logarithm of the transform height in 4x4 units
 * @param minSquareLevel the smallest square transform level touched by this rectangular size
 * @param maxSquareLevel the largest square transform level touched by this rectangular size
 * @param coefficientContextIndex the AV1 coefficient-context group index used by coefficient CDF tables
 * @param subSize the next smaller transform size used when descending transform-depth trees, or `null`
 */(
    /** The transform width in 4x4 units. */
    private val width4: Int,
    /** The transform height in 4x4 units. */
    private val height4: Int,
    /** The base-2 logarithm of the transform width in 4x4 units. */
    private val log2Width4: Int,
    /** The base-2 logarithm of the transform height in 4x4 units. */
    private val log2Height4: Int,
    /** The smallest square transform level touched by this rectangular size. */
    private val minSquareLevel: Int,
    /** The largest square transform level touched by this rectangular size. */
    private val maxSquareLevel: Int,
    /** The AV1 coefficient-context group index used by coefficient skip and token CDF tables. */
    private val coefficientContextIndex: Int,
    /** The next smaller transform size used when descending transform-depth trees, or `null`. */
    private val subSize: TransformSize?
) {
    /** A 4x4 transform. */
    TX_4X4(1, 1, 0, 0, 0, 0, 0, null),

    /** An 8x8 transform. */
    TX_8X8(2, 2, 1, 1, 1, 1, 1, TX_4X4),

    /** A 16x16 transform. */
    TX_16X16(4, 4, 2, 2, 2, 2, 2, TX_8X8),

    /** A 32x32 transform. */
    TX_32X32(8, 8, 3, 3, 3, 3, 3, TX_16X16),

    /** A 64x64 transform. */
    TX_64X64(16, 16, 4, 4, 4, 4, 4, TX_32X32),

    /** A 4x8 transform. */
    RTX_4X8(1, 2, 0, 1, 0, 1, 1, TX_4X4),

    /** An 8x4 transform. */
    RTX_8X4(2, 1, 1, 0, 0, 1, 1, TX_4X4),

    /** An 8x16 transform. */
    RTX_8X16(2, 4, 1, 2, 1, 2, 2, TX_8X8),

    /** A 16x8 transform. */
    RTX_16X8(4, 2, 2, 1, 1, 2, 2, TX_8X8),

    /** A 16x32 transform. */
    RTX_16X32(4, 8, 2, 3, 2, 3, 3, TX_16X16),

    /** A 32x16 transform. */
    RTX_32X16(8, 4, 3, 2, 2, 3, 3, TX_16X16),

    /** A 32x64 transform. */
    RTX_32X64(8, 16, 3, 4, 3, 4, 4, TX_32X32),

    /** A 64x32 transform. */
    RTX_64X32(16, 8, 4, 3, 3, 4, 4, TX_32X32),

    /** A 4x16 transform. */
    RTX_4X16(1, 4, 0, 2, 0, 2, 1, RTX_4X8),

    /** A 16x4 transform. */
    RTX_16X4(4, 1, 2, 0, 0, 2, 1, RTX_8X4),

    /** An 8x32 transform. */
    RTX_8X32(2, 8, 1, 3, 1, 3, 2, RTX_8X16),

    /** A 32x8 transform. */
    RTX_32X8(8, 2, 3, 1, 1, 3, 2, RTX_16X8),

    /** A 16x64 transform. */
    RTX_16X64(4, 16, 2, 4, 2, 4, 3, RTX_16X32),

    /** A 64x16 transform. */
    RTX_64X16(16, 4, 4, 2, 2, 4, 3, RTX_32X16);

    /** Returns the transform width in 4x4 units.
     * 
     * @return the transform width in 4x4 units
     */
    fun width4(): Int {
        return width4
    }

    /** Returns the transform height in 4x4 units.
     * 
     * @return the transform height in 4x4 units
     */
    fun height4(): Int {
        return height4
    }

    /** Returns the transform width in pixels.
     * 
     * @return the transform width in pixels
     */
    fun widthPixels(): Int {
        return width4 shl 2
    }

    /** Returns the transform height in pixels.
     * 
     * @return the transform height in pixels
     */
    fun heightPixels(): Int {
        return height4 shl 2
    }

    /** Returns the base-2 logarithm of the transform width in 4x4 units.
     * 
     * @return the base-2 logarithm of the transform width in 4x4 units
     */
    fun log2Width4(): Int {
        return log2Width4
    }

    /** Returns the base-2 logarithm of the transform height in 4x4 units.
     * 
     * @return the base-2 logarithm of the transform height in 4x4 units
     */
    fun log2Height4(): Int {
        return log2Height4
    }

    /** Returns the smallest square transform level touched by this rectangular size.
     * 
     * @return the smallest square transform level touched by this rectangular size
     */
    fun minSquareLevel(): Int {
        return minSquareLevel
    }

    /** Returns the largest square transform level touched by this rectangular size.
     * 
     * @return the largest square transform level touched by this rectangular size
     */
    fun maxSquareLevel(): Int {
        return maxSquareLevel
    }

    /** Returns the AV1 coefficient-context group index used by coefficient skip and token CDF tables.
     * 
     * @return the AV1 coefficient-context group index used by coefficient skip and token CDF tables
     */
    fun coefficientContextIndex(): Int {
        return coefficientContextIndex
    }

    val isSquare: Boolean
        /** Returns whether this transform size is square.
         * 
         * @return whether this transform size is square
         */
        get() = width4 == height4

    /** Returns whether this transform size has a smaller transform-size descendant.
     * 
     * @return whether this transform size has a smaller transform-size descendant
     */
    fun hasSubSize(): Boolean {
        return subSize != null
    }

    /** Returns the next smaller transform size used when descending transform-depth trees.
     * 
     * @return the next smaller transform size used when descending transform-depth trees
     */
    fun subSize(): TransformSize {
        checkNotNull(subSize) { "Transform size has no smaller descendant: $this" }
        return subSize
    }

    companion object {
        /** The transform-size constants cached in declaration order. */
        val VALUES: Array<TransformSize> = entries.toTypedArray()

        /** Returns the transform size that exactly matches the supplied 4x4-unit dimensions, or `null`.
         * 
         * @param width4 the width in 4x4 units
         * @param height4 the height in 4x4 units
         * @return the transform size that exactly matches the supplied 4x4-unit dimensions, or `null`
         */
        fun forDimensions(width4: Int, height4: Int): TransformSize? {
            for (value in VALUES) {
                if (value.width4 == width4 && value.height4 == height4) {
                    return value
                }
            }
            return null
        }
    }
}
