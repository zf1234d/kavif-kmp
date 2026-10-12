// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** The AV1 compound prediction blend types stored by the block syntax. */
enum class CompoundPredictionType
/** Creates one compound prediction type.
 * 
 * @param contextValue the AV1 neighbor-context value corresponding to this type
 */(
    /** The AV1 neighbor-context value corresponding to this compound prediction type. */
    private val contextValue: Int
) {
    /** Joint-distance weighted compound averaging. */
    WEIGHTED_AVERAGE(1),

    /** Simple compound averaging. */
    AVERAGE(2),

    /** Difference-derived segment mask compound blending. */
    SEGMENT(3),

    /** Wedge-mask compound blending. */
    WEDGE(4);

    /** Returns the AV1 neighbor-context value corresponding to this compound prediction type.
     * 
     * @return the AV1 neighbor-context value corresponding to this compound prediction type
     */
    fun contextValue(): Int {
        return contextValue
    }
}
