// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Block-level AV1 inter motion compensation mode. */
enum class MotionMode {
    /** Plain translational or global-motion prediction. */
    SIMPLE,

    /** Overlapped block motion compensation using causal neighbor predictors. */
    OBMC,

    /** Local warped motion compensation. */
    LOCAL_WARPED;

    companion object {
        /** Maps one entropy-coded AV1 motion-mode symbol to the internal mode.
         * 
         * @param symbol the zero-based motion-mode symbol
         * @return the matching motion mode
         */
        fun fromSymbolIndex(symbol: Int): MotionMode {
            return when (symbol) {
                0 -> MotionMode.SIMPLE
                1 -> MotionMode.OBMC
                2 -> MotionMode.LOCAL_WARPED
                else -> throw IllegalArgumentException("Motion mode symbol out of range: $symbol")
            }
        }
    }
}
