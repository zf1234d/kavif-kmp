// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** The AV1 compound inter prediction modes in bitstream order. */
enum class CompoundInterPredictionMode {
    /** The nearest/nearest compound mode. */
    NEARESTMV_NEARESTMV,

    /** The near/near compound mode. */
    NEARMV_NEARMV,

    /** The nearest/new compound mode. */
    NEARESTMV_NEWMV,

    /** The new/nearest compound mode. */
    NEWMV_NEARESTMV,

    /** The near/new compound mode. */
    NEARMV_NEWMV,

    /** The new/near compound mode. */
    NEWMV_NEARMV,

    /** The global/global compound mode. */
    GLOBALMV_GLOBALMV,

    /** The new/new compound mode. */
    NEWMV_NEWMV;

    /** Returns the bitstream symbol index of this mode.
     * 
     * @return the bitstream symbol index of this mode
     */
    fun symbolIndex(): Int {
        return ordinal
    }

    /** Returns whether this compound mode carries a near-motion-vector component.
     * 
     * @return whether this compound mode carries a near-motion-vector component
     */
    fun usesNearMotionVector(): Boolean {
        return this == CompoundInterPredictionMode.NEARMV_NEARMV || this == CompoundInterPredictionMode.NEARMV_NEWMV || this == CompoundInterPredictionMode.NEWMV_NEARMV
    }

    companion object {
        /** The mode constants cached in bitstream symbol order. */
        val VALUES: Array<CompoundInterPredictionMode> =
            entries.toTypedArray()

        /** Maps one decoded bitstream symbol index back to a compound inter prediction mode.
         * 
         * @param symbolIndex the decoded bitstream symbol index
         * @return the compound inter prediction mode for the supplied symbol index
         */
        fun fromSymbolIndex(symbolIndex: Int): CompoundInterPredictionMode {
            require(!(symbolIndex < 0 || symbolIndex >= VALUES.size)) { "symbolIndex out of range: $symbolIndex" }
            return VALUES[symbolIndex]
        }
    }
}
