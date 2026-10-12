// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** One AV1 motion vector stored in eighth-pel units.
 * 
 * @param rowEighthPel the signed vertical component in eighth-pel units
 * @param columnEighthPel the signed horizontal component in eighth-pel units
 */
data class MotionVector(val rowEighthPel: Int, val columnEighthPel: Int) {
    companion object {
        /** The shared zero motion-vector instance. */
        val ZERO = MotionVector(0, 0)

        /** Returns the shared zero motion-vector instance.
         * 
         * @return the shared zero motion-vector instance
         */
        fun zero(): MotionVector {
            return ZERO
        }
    }
}
