// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.color

import org.glavo.avif.internal.compat.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


/** Provides chromaticity definitions for standardized CICP color-primary codes. */
object CicpColorPrimaries {
    /** BT.709/sRGB primaries with a D65 white point. */
    private val BT709 = Definition(0.640, 0.330, 0.300, 0.600, 0.150, 0.060, 0.3127, 0.3290)

    /** BT.470 System M primaries with a C white point. */
    private val BT470M = Definition(0.670, 0.330, 0.210, 0.710, 0.140, 0.080, 0.3100, 0.3160)

    /** BT.470 System B/G primaries with a D65 white point. */
    private val BT470BG = Definition(0.640, 0.330, 0.290, 0.600, 0.150, 0.060, 0.3127, 0.3290)

    /** SMPTE 170M primaries with a D65 white point. */
    private val SMPTE170M = Definition(0.630, 0.340, 0.310, 0.595, 0.155, 0.070, 0.3127, 0.3290)

    /** SMPTE 240M primaries with a D65 white point. */
    private val SMPTE240M: Definition = SMPTE170M

    /** Generic film primaries with a C white point. */
    private val GENERIC_FILM = Definition(0.681, 0.319, 0.243, 0.692, 0.145, 0.049, 0.3100, 0.3160)

    /** BT.2020 primaries with a D65 white point. */
    private val BT2020 = Definition(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290)

    /** SMPTE RP 431-2 primaries with the DCI white point. */
    private val SMPTE431 = Definition(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3140, 0.3510)

    /** SMPTE EG 432-1 Display-P3 primaries with a D65 white point. */
    private val SMPTE432 = Definition(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290)

    /** EBU Tech. 3213-E primaries with a D65 white point. */
    private val EBU3213 = Definition(0.630, 0.340, 0.295, 0.605, 0.155, 0.077, 0.3127, 0.3290)

    /** Returns the chromaticity definition for one CICP color-primary code.
     * 
     * @param colorPrimaries the CICP color-primary code
     * @return the matching definition, or `null` for an unspecified, reserved, or unknown code
     */
    fun find(colorPrimaries: Int): Definition? {
        return when (colorPrimaries) {
            1 -> BT709
            4 -> BT470M
            5 -> BT470BG
            6 -> SMPTE170M
            7 -> SMPTE240M
            8 -> GENERIC_FILM
            9 -> BT2020
            11 -> SMPTE431
            12 -> SMPTE432
            22 -> EBU3213
            else -> null
        }
    }

    /** Returns the effective definition for chromaticity-derived matrix conversion.
     * 
     * Unspecified primary code `2` uses BT.709, matching the default color-primary behavior used
     * by AVIF conversion. Other unknown codes are rejected rather than silently substituted.
     * 
     * @param colorPrimaries the CICP color-primary code
     * @return the matching definition, or BT.709 for code `2`
     * @throws UnsupportedOperationException if the code has no supported chromaticity definition
     */
    fun resolveForMatrix(colorPrimaries: Int): Definition {
        if (colorPrimaries == 2) {
            return BT709
        }
        val definition = find(colorPrimaries)
        if (definition == null) {
            throw UnsupportedOperationException("Unsupported CICP color primaries: $colorPrimaries")
        }
        return definition
    }

    /** Defines RGB primary and white-point chromaticities.
     * 
     * @param redX red-primary x chromaticity
     * @param redY red-primary y chromaticity
     * @param greenX green-primary x chromaticity
     * @param greenY green-primary y chromaticity
     * @param blueX blue-primary x chromaticity
     * @param blueY blue-primary y chromaticity
     * @param whiteX white-point x chromaticity
     * @param whiteY white-point y chromaticity
     */
    data class Definition(
        val redX: Double,
        val redY: Double,
        val greenX: Double,
        val greenY: Double,
        val blueX: Double,
        val blueY: Double,
        val whiteX: Double,
        val whiteY: Double
    ) {
        /** Computes normalized RGB luma coefficients from these chromaticities.
         * 
         * @return the red, green, and blue luma coefficients
         * @throws IllegalStateException if the chromaticities do not define an invertible RGB space
         */
        fun lumaCoefficients(): LumaCoefficients {
            val redZ = 1.0 - redX - redY
            val greenZ = 1.0 - greenX - greenY
            val blueZ = 1.0 - blueX - blueY
            val whiteZ = 1.0 - whiteX - whiteY
            val determinant =
                whiteY * (redX * (greenY * blueZ - blueY * greenZ) + greenX * (blueY * redZ - redY * blueZ) + blueX * (redY * greenZ - greenY * redZ)
                        )
            check(!(abs(determinant) < 1.0e-12)) { "CICP primaries do not define invertible luma coefficients" }
            val red =
                (redY * (whiteX * (greenY * blueZ - blueY * greenZ) + whiteY * (blueX * greenZ - greenX * blueZ) + whiteZ * (greenX * blueY - blueX * greenY)
                        ) / determinant)
            val blue =
                (blueY * (whiteX * (redY * greenZ - greenY * redZ) + whiteY * (greenX * redZ - redX * greenZ) + whiteZ * (redX * greenY - greenX * redY)
                        ) / determinant)
            return LumaCoefficients(red, 1.0 - red - blue, blue)
        }
    }

    /** Holds normalized RGB luma coefficients.
     * 
     * @param red the red luma coefficient
     * @param green the green luma coefficient
     * @param blue the blue luma coefficient
     */
    data class LumaCoefficients(val red: Double, val green: Double, val blue: Double)
}
