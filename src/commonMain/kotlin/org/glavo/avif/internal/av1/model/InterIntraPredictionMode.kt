// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** The AV1 inter-intra prediction modes in bitstream order. */
enum class InterIntraPredictionMode {
    /** DC inter-intra prediction. */
    DC,

    /** Vertical inter-intra prediction. */
    VERTICAL,

    /** Horizontal inter-intra prediction. */
    HORIZONTAL,

    /** Smooth inter-intra prediction. */
    SMOOTH;

    /** Returns the matching luma intra prediction mode used to build the secondary predictor.
     * 
     * @return the matching luma intra prediction mode used to build the secondary predictor
     */
    fun toLumaPredictionMode(): LumaIntraPredictionMode {
        return when (this) {
            InterIntraPredictionMode.DC -> LumaIntraPredictionMode.DC
            InterIntraPredictionMode.VERTICAL -> LumaIntraPredictionMode.VERTICAL
            InterIntraPredictionMode.HORIZONTAL -> LumaIntraPredictionMode.HORIZONTAL
            InterIntraPredictionMode.SMOOTH -> LumaIntraPredictionMode.SMOOTH
        }
    }

    /** Returns the matching chroma intra prediction mode used to build the secondary predictor.
     * 
     * @return the matching chroma intra prediction mode used to build the secondary predictor
     */
    fun toUvPredictionMode(): UvIntraPredictionMode {
        return when (this) {
            InterIntraPredictionMode.DC -> UvIntraPredictionMode.DC
            InterIntraPredictionMode.VERTICAL -> UvIntraPredictionMode.VERTICAL
            InterIntraPredictionMode.HORIZONTAL -> UvIntraPredictionMode.HORIZONTAL
            InterIntraPredictionMode.SMOOTH -> UvIntraPredictionMode.SMOOTH
        }
    }

    companion object {
        /** The mode constants cached in bitstream symbol order. */
        val VALUES: Array<InterIntraPredictionMode> = entries.toTypedArray()

        /** Maps one decoded bitstream symbol index back to an inter-intra prediction mode.
         * 
         * @param symbolIndex the decoded bitstream symbol index
         * @return the inter-intra prediction mode for the supplied symbol index
         */
        fun fromSymbolIndex(symbolIndex: Int): InterIntraPredictionMode {
            require(!(symbolIndex < 0 || symbolIndex >= VALUES.size)) { "symbolIndex out of range: $symbolIndex" }
            return VALUES[symbolIndex]
        }
    }
}
