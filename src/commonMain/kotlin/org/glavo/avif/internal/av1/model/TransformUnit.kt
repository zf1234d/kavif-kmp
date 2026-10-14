// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** One transform unit inside a decoded block-level transform layout.
 * 
 * @param position the tile-relative shared luma-grid origin of this transform unit
 * @param size the transform size used by this transform unit
 */
data class TransformUnit(val position: BlockPosition, val size: TransformSize) {
    /** Returns a copy of this transform unit with a replaced position.
     * 
     * @param position the replacement transform-unit position
     * @return a copy of this transform unit with a replaced position
     */
    fun withPosition(position: BlockPosition): TransformUnit {
        val nonNullPosition = requireNotNullCompat(position, "position")
        if (this.position.x4 == nonNullPosition.x4 && this.position.y4 == nonNullPosition.y4) {
            return this
        }
        return TransformUnit(nonNullPosition, size)
    }

    init {
        requireNotNullCompat(position, "position")
        requireNotNullCompat(size, "size")
    }
}
