// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** A signed rational value carried by AVIF gain-map metadata.
 * 
 * @param numerator the signed 32-bit numerator
 * @param denominator the unsigned 32-bit denominator
 */
data class AvifSignedFraction(val numerator: Int, val denominator: Long) {
    /** Returns the signed numerator.
     * 
     * @return the signed numerator
     */
    fun numerator(): Int {
        return numerator
    }

    /** Returns the unsigned denominator.
     * 
     * @return the unsigned denominator
     */
    fun denominator(): Long {
        return denominator
    }

    /** Returns this rational value as a finite `double`.
     * 
     * @return this rational value as a finite `double`
     */
    fun toDouble(): Double {
        return numerator.toDouble() / denominator.toDouble()
    }

    init {
        require(!(denominator <= 0 || denominator > UINT32_MAX)) { "denominator must be in 1..2^32-1: $denominator" }
    }

    companion object {
        /** The maximum unsigned 32-bit value representable in a Java `long`. */
        private const val UINT32_MAX = 0xFFFFFFFFL
    }
}
