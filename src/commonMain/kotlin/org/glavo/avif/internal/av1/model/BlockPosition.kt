// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** A block origin expressed in 4x4 units within the caller's active coordinate space.
 * 
 * @param x4 the X coordinate in 4x4 units
 * @param y4 the Y coordinate in 4x4 units
 */
data class BlockPosition(val x4: Int, val y4: Int) {
    /** Returns the X coordinate in 8x8 units.
     * 
     * @return the X coordinate in 8x8 units
     */
    fun x8(): Int {
        return x4 shr 1
    }

    /** Returns the Y coordinate in 8x8 units.
     * 
     * @return the Y coordinate in 8x8 units
     */
    fun y8(): Int {
        return y4 shr 1
    }

    /** Returns this position offset by the supplied 4x4-unit delta.
     * 
     * @param deltaX4 the X-axis offset in 4x4 units
     * @param deltaY4 the Y-axis offset in 4x4 units
     * @return the offset position, or this position when both deltas are zero
     */
    fun offset(deltaX4: Int, deltaY4: Int): BlockPosition {
        if (deltaX4 == 0 && deltaY4 == 0) {
            return this
        }
        return BlockPosition(x4 + deltaX4, y4 + deltaY4)
    }

    init {
        require(x4 >= 0) { "x4 < 0: $x4" }
        require(y4 >= 0) { "y4 < 0: $y4" }
    }
}
