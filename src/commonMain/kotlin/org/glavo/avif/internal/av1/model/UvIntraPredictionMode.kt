// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** The AV1 chroma intra prediction modes in bitstream order. */
enum class UvIntraPredictionMode {
    /** DC prediction. */
    DC,

    /** Vertical prediction. */
    VERTICAL,

    /** Horizontal prediction. */
    HORIZONTAL,

    /** Diagonal-down-left prediction. */
    DIAGONAL_DOWN_LEFT,

    /** Diagonal-down-right prediction. */
    DIAGONAL_DOWN_RIGHT,

    /** Vertical-right prediction. */
    VERTICAL_RIGHT,

    /** Horizontal-down prediction. */
    HORIZONTAL_DOWN,

    /** Horizontal-up prediction. */
    HORIZONTAL_UP,

    /** Vertical-left prediction. */
    VERTICAL_LEFT,

    /** Smooth prediction. */
    SMOOTH,

    /** Smooth vertical prediction. */
    SMOOTH_VERTICAL,

    /** Smooth horizontal prediction. */
    SMOOTH_HORIZONTAL,

    /** Paeth prediction. */
    PAETH,

    /** Chroma-from-luma prediction. */
    CFL;

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
        get() = ordinal >= UvIntraPredictionMode.VERTICAL.ordinal && ordinal <= UvIntraPredictionMode.VERTICAL_LEFT.ordinal

    /** Returns the zero-based directional angle-delta CDF index for this mode.
     * 
     * @return the zero-based directional angle-delta CDF index for this mode
     */
    fun angleDeltaContextIndex(): Int {
        check(this.isDirectional) { "Mode does not use angle_delta: $this" }
        return ordinal - UvIntraPredictionMode.VERTICAL.ordinal
    }

    companion object {
        /** The mode constants cached in bitstream symbol order. */
        val VALUES: Array<UvIntraPredictionMode> = entries.toTypedArray()

        /** Maps a decoded bitstream symbol index back to a chroma intra prediction mode.
         * 
         * @param symbolIndex the decoded bitstream symbol index
         * @return the chroma intra prediction mode for the supplied symbol index
         */
        fun fromSymbolIndex(symbolIndex: Int): UvIntraPredictionMode {
            require(!(symbolIndex < 0 || symbolIndex >= VALUES.size)) { "symbolIndex out of range: $symbolIndex" }
            return VALUES[symbolIndex]
        }
    }
}
