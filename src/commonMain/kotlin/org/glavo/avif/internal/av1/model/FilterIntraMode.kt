// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** The AV1 recursive filter-intra modes in bitstream order. */
enum class FilterIntraMode {
    /** Recursive DC filter intra prediction. */
    DC,

    /** Recursive vertical filter intra prediction. */
    VERTICAL,

    /** Recursive horizontal filter intra prediction. */
    HORIZONTAL,

    /** Recursive diagonal-157 filter intra prediction. */
    DIAGONAL_157,

    /** Recursive Paeth-style filter intra prediction. */
    PAETH;

    /** Returns the bitstream symbol index of this filter intra mode.
     * 
     * @return the bitstream symbol index of this filter intra mode
     */
    fun symbolIndex(): Int {
        return ordinal
    }

    companion object {
        /** The mode constants cached in bitstream symbol order. */
        val VALUES: Array<FilterIntraMode> = entries.toTypedArray()

        /** Maps a decoded bitstream symbol index back to a filter intra mode.
         * 
         * @param symbolIndex the decoded bitstream symbol index
         * @return the filter intra mode for the supplied symbol index
         */
        fun fromSymbolIndex(symbolIndex: Int): FilterIntraMode {
            require(!(symbolIndex < 0 || symbolIndex >= VALUES.size)) { "symbolIndex out of range: $symbolIndex" }
            return VALUES[symbolIndex]
        }
    }
}
