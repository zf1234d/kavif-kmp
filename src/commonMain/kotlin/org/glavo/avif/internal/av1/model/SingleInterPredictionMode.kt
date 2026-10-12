// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** The AV1 single-reference inter prediction modes in bitstream order. */
enum class SingleInterPredictionMode {
    /** The nearest-motion-vector mode. */
    NEARESTMV,

    /** The near-motion-vector mode. */
    NEARMV,

    /** The global-motion mode. */
    GLOBALMV,

    /** The new-motion-vector mode. */
    NEWMV;

    /** Returns the bitstream symbol index of this mode.
     * 
     * @return the bitstream symbol index of this mode
     */
    fun symbolIndex(): Int {
        return ordinal
    }

    companion object {
        /** The mode constants cached in bitstream symbol order. */
        val VALUES: Array<SingleInterPredictionMode> = entries.toTypedArray()

        /** Maps one decoded bitstream symbol index back to a single-reference inter prediction mode.
         * 
         * @param symbolIndex the decoded bitstream symbol index
         * @return the single-reference inter prediction mode for the supplied symbol index
         */
        fun fromSymbolIndex(symbolIndex: Int): SingleInterPredictionMode {
            require(!(symbolIndex < 0 || symbolIndex >= VALUES.size)) { "symbolIndex out of range: $symbolIndex" }
            return VALUES[symbolIndex]
        }
    }
}
