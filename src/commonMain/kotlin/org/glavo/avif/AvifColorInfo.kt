// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** AVIF `nclx` color information from a `colr` item property. */
class AvifColorInfo(
    colorPrimaries: Int, transferCharacteristics: Int, matrixCoefficients: Int,
    /** Whether samples are full range. */
    private val fullRange: Boolean
) {
    /** The CICP color primaries value. */
    private val colorPrimaries: Int

    /** The CICP transfer characteristics value. */
    private val transferCharacteristics: Int

    /** The CICP matrix coefficients value. */
    private val matrixCoefficients: Int

    init {
        this.colorPrimaries = checkedCodePoint(colorPrimaries, "colorPrimaries")
        this.transferCharacteristics =
            checkedCodePoint(transferCharacteristics, "transferCharacteristics")
        this.matrixCoefficients = checkedCodePoint(matrixCoefficients, "matrixCoefficients")
    }

    /** Returns the CICP color primaries value.
     * 
     * @return the CICP color primaries value
     */
    fun colorPrimaries(): Int {
        return colorPrimaries
    }

    /** Returns the CICP transfer characteristics value.
     * 
     * @return the CICP transfer characteristics value
     */
    fun transferCharacteristics(): Int {
        return transferCharacteristics
    }

    /** Returns the CICP matrix coefficients value.
     * 
     * @return the CICP matrix coefficients value
     */
    fun matrixCoefficients(): Int {
        return matrixCoefficients
    }

    /** Returns whether samples are full range.
     * 
     * @return whether samples are full range
     */
    fun fullRange(): Boolean {
        return fullRange
    }

    companion object {
        /** Returns a validated unsigned 16-bit `nclx` code point.
         * 
         * @param value the code point
         * @param name the parameter name
         * @return the validated code point
         */
        fun checkedCodePoint(value: Int, name: String): Int {
            require(value in 0..0xFFFF) { name + " out of range: $value" }
            return value
        }
    }
}
