// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** One inter motion-vector state that may already be final or still provisional.
 * 
 * @param vector the underlying motion-vector value in eighth-pel units
 * @param resolved whether the vector is final for the current block
 */
data class InterMotionVector(val vector: MotionVector, val resolved: Boolean) {
    /** Returns this motion vector downgraded to a provisional predictor.
     * 
     * @return this motion vector downgraded to a provisional predictor
     */
    fun asPredicted(): InterMotionVector {
        return if (resolved) InterMotionVector(vector, false) else this
    }

    /** Returns this motion vector promoted to a final block vector.
     * 
     * @return this motion vector promoted to a final block vector
     */
    fun asResolved(): InterMotionVector {
        return if (resolved) this else InterMotionVector(vector, true)
    }

    /** Creates one inter motion-vector state. */
    init {
        requireNotNullCompat(vector, "vector")
    }

    companion object {
        /** Creates one final inter motion-vector state.
         * 
         * @param vector the underlying motion-vector value in eighth-pel units
         * @return one final inter motion-vector state
         */
        fun resolved(vector: MotionVector): InterMotionVector {
            return InterMotionVector(vector, true)
        }

        /** Creates one provisional inter motion-vector state.
         * 
         * @param vector the underlying motion-vector value in eighth-pel units
         * @return one provisional inter motion-vector state
         */
        fun predicted(vector: MotionVector): InterMotionVector {
            return InterMotionVector(vector, false)
        }
    }
}
