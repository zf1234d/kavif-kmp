// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** The thirteen AV1 luma intra prediction modes in bitstream order. */
enum class LumaIntraPredictionMode
/** Creates one luma intra prediction mode entry.
 * 
 * @param contextIndex the coarsened intra-mode context class used by key-frame Y-mode CDFs
 */(
    /** The coarsened intra-mode context class used by key-frame Y-mode CDFs. */
    private val contextIndex: Int
) {
    /** DC prediction. */
    DC(0),

    /** Vertical prediction. */
    VERTICAL(1),

    /** Horizontal prediction. */
    HORIZONTAL(2),

    /** Diagonal-down-left prediction. */
    DIAGONAL_DOWN_LEFT(3),

    /** Diagonal-down-right prediction. */
    DIAGONAL_DOWN_RIGHT(4),

    /** Vertical-right prediction. */
    VERTICAL_RIGHT(4),

    /** Horizontal-down prediction. */
    HORIZONTAL_DOWN(4),

    /** Horizontal-up prediction. */
    HORIZONTAL_UP(4),

    /** Vertical-left prediction. */
    VERTICAL_LEFT(3),

    /** Smooth prediction. */
    SMOOTH(0),

    /** Smooth vertical prediction. */
    SMOOTH_VERTICAL(1),

    /** Smooth horizontal prediction. */
    SMOOTH_HORIZONTAL(2),

    /** Paeth prediction. */
    PAETH(0);

    /** Returns the coarsened intra-mode context class used by key-frame Y-mode CDFs.
     * 
     * @return the coarsened intra-mode context class used by key-frame Y-mode CDFs
     */
    fun contextIndex(): Int {
        return contextIndex
    }

    /** Returns the bitstream symbol index of this mode.
     * 
     * @return the bitstream symbol index of this mode
     */
    fun symbolIndex(): Int {
        return ordinal
    }

    val isDirectional: Boolean
        /** Returns whether this mode uses the directional angle-delta syntax element.
         * 
         * @return whether this mode uses the directional angle-delta syntax element
         */
        get() = ordinal >= LumaIntraPredictionMode.VERTICAL.ordinal && ordinal <= LumaIntraPredictionMode.VERTICAL_LEFT.ordinal

    /** Returns the zero-based directional angle-delta CDF index for this mode.
     * 
     * @return the zero-based directional angle-delta CDF index for this mode
     */
    fun angleDeltaContextIndex(): Int {
        check(this.isDirectional) { "Mode does not use angle_delta: $this" }
        return ordinal - LumaIntraPredictionMode.VERTICAL.ordinal
    }

    companion object {
        /** The mode constants cached in bitstream symbol order. */
        val VALUES: Array<LumaIntraPredictionMode> = entries.toTypedArray()

        /** Maps a decoded bitstream symbol index back to a luma intra prediction mode.
         * 
         * @param symbolIndex the decoded bitstream symbol index
         * @return the luma intra prediction mode for the supplied symbol index
         */
        fun fromSymbolIndex(symbolIndex: Int): LumaIntraPredictionMode {
            require(!(symbolIndex < 0 || symbolIndex >= VALUES.size)) { "symbolIndex out of range: $symbolIndex" }
            return VALUES[symbolIndex]
        }
    }
}
