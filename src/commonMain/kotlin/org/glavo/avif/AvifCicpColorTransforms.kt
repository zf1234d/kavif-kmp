// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.exp
import org.glavo.avif.internal.color.CicpColorPrimaries


/** Pure-Java CICP transfer-function and RGB-primary conversion helpers.
 * 
 * The helper covers every CICP transfer characteristic defined for normalized non-negative
 * channels and the RGB primary sets described by [CicpColorPrimaries]. Missing or unspecified
 * signaling uses BT.709/sRGB defaults; reserved or unknown explicit signaling is rejected. */
internal object AvifCicpColorTransforms {
    /** BT.709/sRGB color primaries. */
    private const val PRIMARIES_BT709 = 1

    /** Unspecified CICP color primaries. */
    private const val PRIMARIES_UNSPECIFIED = 2

    /** SMPTE ST 428 CIE XYZ color coordinates. */
    private const val PRIMARIES_XYZ = 10

    /** Unspecified CICP transfer characteristics. */
    private const val TRANSFER_UNSPECIFIED = 2

    /** sRGB transfer characteristics. */
    private const val TRANSFER_SRGB = 13

    /** Linear transfer characteristics. */
    private const val TRANSFER_LINEAR = 8

    /** H.273 BT.709-family transfer-function scale. */
    private const val BT709_ALPHA = 1.099296826809442

    /** H.273 BT.709-family linear-light threshold. */
    private const val BT709_BETA = 0.018053968510807

    /** H.273 BT.709-family encoded threshold. */
    private const val BT709_ENCODED_THRESHOLD = 4.5 * BT709_BETA

    /** H.273 SMPTE 240M transfer-function scale. */
    private const val SMPTE240_ALPHA = 1.111572195921731

    /** H.273 SMPTE 240M linear-light threshold. */
    private const val SMPTE240_BETA = 0.022821585529445

    /** H.273 SMPTE 240M encoded threshold. */
    private const val SMPTE240_ENCODED_THRESHOLD = 4.0 * SMPTE240_BETA

    /** Lower linear-light endpoint represented by transfer characteristic 9. */
    private const val LOG100_LINEAR_THRESHOLD = 0.01

    /** Lower linear-light endpoint represented by transfer characteristic 10. */
    private val LOG316_LINEAR_THRESHOLD = sqrt(10.0) / 1000.0

    /** SMPTE ST 428 reference-output scale from H.273. */
    private const val SMPTE428_ENCODE_SCALE = 48.0 / 52.37

    /** SMPTE ST 2084 perceptual quantizer transfer characteristics. */
    private const val TRANSFER_PQ = 16

    /** ARIB STD-B67 hybrid log-gamma transfer characteristics. */
    private const val TRANSFER_HLG = 18

    /** sRGB linear-to-gamma threshold. */
    private const val SRGB_LINEAR_THRESHOLD = 0.0031308

    /** sRGB gamma-to-linear threshold. */
    private const val SRGB_GAMMA_THRESHOLD = 0.04045

    /** HLG transfer function constant `a`. */
    private const val HLG_A = 0.17883277

    /** HLG transfer function constant `b`. */
    private const val HLG_B = 0.28466892

    /** HLG transfer function constant `c`. */
    private const val HLG_C = 0.55991073

    /** PQ transfer function constant `m1`. */
    private const val PQ_M1 = 2610.0 / 16384.0

    /** PQ transfer function constant `m2`. */
    private const val PQ_M2 = 2523.0 / 32.0

    /** PQ transfer function constant `c1`. */
    private const val PQ_C1 = 3424.0 / 4096.0

    /** PQ transfer function constant `c2`. */
    private const val PQ_C2 = 2413.0 / 128.0

    /** PQ transfer function constant `c3`. */
    private const val PQ_C3 = 2392.0 / 128.0

    /** Identity RGB conversion matrix. */
    private val IDENTITY = RgbMatrix(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
    )

    /** Converts one gamma-encoded RGB channel to linear light.
     * 
     * @param value the normalized gamma-encoded value
     * @param colorInfo the CICP color information, or `null` for sRGB fallback
     * @return the normalized linear-light value
     * @throws UnsupportedOperationException if an explicit transfer characteristic is unsupported
     */
    fun gammaToLinear(value: Double, colorInfo: AvifColorInfo?): Double {
        val clamped = clampUnit(value)
        val transferCharacteristics = transferCharacteristics(colorInfo)
        return when (transferCharacteristics) {
            TRANSFER_LINEAR -> clamped
            1, 6, 11, 12, 14, 15 -> bt709ToLinear(clamped)
            4 -> clamped.pow(2.2)
            5 -> clamped.pow(2.8)
            7 -> smpte240ToLinear(clamped)
            9 -> logarithmicToLinear(clamped, 2.0)
            10 -> logarithmicToLinear(clamped, 2.5)
            TRANSFER_PQ -> pqToLinear(clamped)
            17 -> smpte428ToLinear(clamped)
            TRANSFER_HLG -> hlgToLinear(clamped)
            TRANSFER_SRGB -> srgbToLinear(clamped)
            else -> throw UnsupportedOperationException(
                "Unsupported CICP transfer characteristics: $transferCharacteristics"
            )
        }
    }

    /** Converts one linear-light RGB channel to gamma encoding.
     * 
     * @param value the normalized linear-light value
     * @param colorInfo the CICP color information, or `null` for sRGB fallback
     * @return the normalized gamma-encoded value
     * @throws UnsupportedOperationException if an explicit transfer characteristic is unsupported
     */
    fun linearToGamma(value: Double, colorInfo: AvifColorInfo?): Double {
        val clamped = clampUnit(value)
        val transferCharacteristics = transferCharacteristics(colorInfo)
        return when (transferCharacteristics) {
            TRANSFER_LINEAR -> clamped
            1, 6, 11, 12, 14, 15 -> linearToBt709(clamped)
            4 -> clamped.pow(1.0 / 2.2)
            5 -> clamped.pow(1.0 / 2.8)
            7 -> linearToSmpte240(clamped)
            9 -> linearToLogarithmic(clamped, LOG100_LINEAR_THRESHOLD, 2.0)
            10 -> linearToLogarithmic(clamped, LOG316_LINEAR_THRESHOLD, 2.5)
            TRANSFER_PQ -> linearToPq(clamped)
            17 -> linearToSmpte428(clamped)
            TRANSFER_HLG -> linearToHlg(clamped)
            TRANSFER_SRGB -> linearToSrgb(clamped)
            else -> throw UnsupportedOperationException(
                "Unsupported CICP transfer characteristics: $transferCharacteristics"
            )
        }
    }

    /** Builds a linear-light RGB conversion matrix between two CICP primary sets.
     * 
     * @param sourceInfo the source color information, or `null` for BT.709/sRGB fallback
     * @param targetInfo the target color information, or `null` for BT.709/sRGB fallback
     * @return an RGB conversion matrix
     * @throws UnsupportedOperationException if either explicit primary set is unsupported
     * @throws IllegalStateException if a supported primary definition cannot produce an invertible matrix
     */
    fun conversionMatrix(sourceInfo: AvifColorInfo?, targetInfo: AvifColorInfo?): RgbMatrix {
        val sourcePrimaries = colorPrimaries(sourceInfo)
        val targetPrimaries = colorPrimaries(targetInfo)
        val sourceToXyz = primarySetToXyzMatrix(sourcePrimaries)
        val targetToXyz = primarySetToXyzMatrix(targetPrimaries)
        check(!(sourceToXyz == null || targetToXyz == null)) { "Supported CICP primaries produced a singular RGB conversion matrix" }
        if (sourcePrimaries == targetPrimaries) {
            return IDENTITY
        }
        val xyzToTarget = targetToXyz.inverse()
        checkNotNull(xyzToTarget) { "Supported target CICP primaries produced a singular RGB conversion matrix" }
        return xyzToTarget.multiply(sourceToXyz)
    }

    /** Returns whether two CICP descriptors have the same effective RGB color space.
     * 
     * RGB frame output is affected by color primaries and transfer characteristics. Matrix
     * coefficients and sample range describe YUV storage and are intentionally ignored here.
     * 
     * @param first the first color information, or `null`
     * @param second the second color information, or `null`
     * @return whether both descriptors resolve to the same effective RGB color space
     */
    fun sameEffectiveRgbColorSpace(first: AvifColorInfo?, second: AvifColorInfo?): Boolean {
        return colorPrimaries(first) == colorPrimaries(second)
                && transferCharacteristics(first) == transferCharacteristics(second)
    }

    /** Clamps a sample to the closed unit interval.
     * 
     * @param value the sample value
     * @return the clamped value
     */
    fun clampUnit(value: Double): Double {
        if (value <= 0.0) {
            return 0.0
        }
        if (value >= 1.0) {
            return 1.0
        }
        return value
    }

    /** Returns the CICP color primaries with a BT.709 fallback.
     * 
     * @param colorInfo the color information, or `null`
     * @return the effective color primaries
     */
    private fun colorPrimaries(colorInfo: AvifColorInfo?): Int {
        if (colorInfo == null || colorInfo.colorPrimaries() == PRIMARIES_UNSPECIFIED) {
            return PRIMARIES_BT709
        }
        return colorInfo.colorPrimaries()
    }

    /** Returns the CICP transfer characteristics with an sRGB fallback.
     * 
     * @param colorInfo the color information, or `null`
     * @return the effective transfer characteristics
     */
    private fun transferCharacteristics(colorInfo: AvifColorInfo?): Int {
        if (colorInfo == null || colorInfo.transferCharacteristics() == TRANSFER_UNSPECIFIED) {
            return TRANSFER_SRGB
        }
        return colorInfo.transferCharacteristics()
    }

    /** Returns the primary definition for one supported effective CICP code.
     * 
     * @param colorPrimaries the effective CICP color primaries value
     * @return the matching primary definition
     * @throws UnsupportedOperationException if the explicit primary set is unsupported
     */
    private fun requirePrimaries(colorPrimaries: Int): CicpColorPrimaries.Definition {
        val result = CicpColorPrimaries.find(colorPrimaries)
        if (result == null) {
            throw UnsupportedOperationException("Unsupported CICP color primaries: $colorPrimaries")
        }
        return result
    }

    /** Builds a primary-coordinate-set-to-XYZ matrix for one supported CICP code.
     * 
     * CICP code `10` already labels its three channels as CIE XYZ and therefore uses the identity
     * matrix. Other supported codes are conventional RGB primary definitions.
     * 
     * @param colorPrimaries the effective CICP color-primary value
     * @return the coordinate-set-to-XYZ matrix, or `null` when the definition is singular
     * @throws UnsupportedOperationException if the explicit primary set is unsupported
     */
    private fun primarySetToXyzMatrix(colorPrimaries: Int): RgbMatrix? {
        if (colorPrimaries == PRIMARIES_XYZ) {
            return IDENTITY
        }
        return rgbToXyzMatrix(requirePrimaries(colorPrimaries))
    }

    /** Computes an RGB-to-XYZ matrix for one primary definition.
     * 
     * @param primaries the primary and white-point chromaticities
     * @return the RGB-to-XYZ matrix, or `null` when the chromaticities are singular
     */
    private fun rgbToXyzMatrix(primaries: CicpColorPrimaries.Definition): RgbMatrix? {
        val redZ = 1.0 - primaries.redX - primaries.redY
        val greenZ = 1.0 - primaries.greenX - primaries.greenY
        val blueZ = 1.0 - primaries.blueX - primaries.blueY
        val whiteZ = 1.0 - primaries.whiteX - primaries.whiteY
        if (primaries.redY == 0.0 || primaries.greenY == 0.0 || primaries.blueY == 0.0 || primaries.whiteY == 0.0) {
            return null
        }
        val matrix = RgbMatrix(
            primaries.redX / primaries.redY,
            primaries.greenX / primaries.greenY,
            primaries.blueX / primaries.blueY,
            1.0, 1.0, 1.0,
            redZ / primaries.redY,
            greenZ / primaries.greenY,
            blueZ / primaries.blueY
        )
        val inverse = matrix.inverse()
        if (inverse == null) {
            return null
        }
        val white = doubleArrayOf(
            primaries.whiteX / primaries.whiteY,
            1.0,
            whiteZ / primaries.whiteY
        )
        inverse.apply(white)
        val redScale = white[0]
        val greenScale = white[1]
        val blueScale = white[2]
        return RgbMatrix(
            redScale * primaries.redX / primaries.redY,
            greenScale * primaries.greenX / primaries.greenY,
            blueScale * primaries.blueX / primaries.blueY,
            redScale,
            greenScale,
            blueScale,
            redScale * redZ / primaries.redY,
            greenScale * greenZ / primaries.greenY,
            blueScale * blueZ / primaries.blueY
        )
    }

    /** Converts an sRGB gamma-encoded sample to linear light.
     * 
     * @param value the normalized gamma-encoded sample
     * @return the normalized linear-light sample
     */
    private fun srgbToLinear(value: Double): Double {
        if (value <= SRGB_GAMMA_THRESHOLD) {
            return value / 12.92
        }
        return ((value + 0.055) / 1.055).pow(2.4)
    }

    /** Converts a linear-light sample to sRGB gamma encoding.
     * 
     * @param value the normalized linear-light sample
     * @return the normalized gamma-encoded sample
     */
    private fun linearToSrgb(value: Double): Double {
        if (value <= SRGB_LINEAR_THRESHOLD) {
            return value * 12.92
        }
        return 1.055 * value.pow(1.0 / 2.4) - 0.055
    }

    /** Converts a BT.709-family gamma-encoded sample to linear light.
     * 
     * @param value the normalized gamma-encoded sample
     * @return the normalized linear-light sample
     */
    private fun bt709ToLinear(value: Double): Double {
        if (value < BT709_ENCODED_THRESHOLD) {
            return value / 4.5
        }
        return ((value + BT709_ALPHA - 1.0) / BT709_ALPHA).pow(1.0 / 0.45)
    }

    /** Converts a linear-light sample to BT.709-family gamma encoding.
     * 
     * @param value the normalized linear-light sample
     * @return the normalized gamma-encoded sample
     */
    private fun linearToBt709(value: Double): Double {
        if (value < BT709_BETA) {
            return value * 4.5
        }
        return BT709_ALPHA * value.pow(0.45) - (BT709_ALPHA - 1.0)
    }

    /** Converts a SMPTE 240M gamma-encoded sample to linear light.
     * 
     * @param value the normalized gamma-encoded sample
     * @return the normalized linear-light sample
     */
    private fun smpte240ToLinear(value: Double): Double {
        if (value < SMPTE240_ENCODED_THRESHOLD) {
            return value / 4.0
        }
        return ((value + SMPTE240_ALPHA - 1.0) / SMPTE240_ALPHA).pow(1.0 / 0.45)
    }

    /** Converts a linear-light sample to SMPTE 240M gamma encoding.
     * 
     * @param value the normalized linear-light sample
     * @return the normalized gamma-encoded sample
     */
    private fun linearToSmpte240(value: Double): Double {
        if (value < SMPTE240_BETA) {
            return value * 4.0
        }
        return SMPTE240_ALPHA * value.pow(0.45) - (SMPTE240_ALPHA - 1.0)
    }

    /** Converts a logarithmically encoded sample to linear light.
     * 
     * Encoded black represents an interval of linear-light values. This inverse selects zero,
     * the lower endpoint of that interval, so exact black remains black.
     * 
     * @param value the normalized logarithmically encoded sample
     * @param divisor the base-10 logarithm divisor from H.273
     * @return the normalized linear-light sample
     */
    private fun logarithmicToLinear(value: Double, divisor: Double): Double {
        if (value <= 0.0) {
            return 0.0
        }
        return 10.0.pow(divisor * (value - 1.0))
    }

    /** Converts a linear-light sample to logarithmic encoding.
     * 
     * @param value the normalized linear-light sample
     * @param threshold the inclusive lower endpoint of the logarithmic segment
     * @param divisor the base-10 logarithm divisor from H.273
     * @return the normalized logarithmically encoded sample
     */
    private fun linearToLogarithmic(value: Double, threshold: Double, divisor: Double): Double {
        if (value <= threshold) {
            return 0.0
        }
        return 1.0 + log10(value) / divisor
    }

    /** Converts a SMPTE ST 428 gamma-encoded sample to normalized output light.
     * 
     * @param value the normalized gamma-encoded sample
     * @return the normalized linear-light sample
     */
    private fun smpte428ToLinear(value: Double): Double {
        return value.pow(2.6) / SMPTE428_ENCODE_SCALE
    }

    /** Converts normalized output light to SMPTE ST 428 gamma encoding.
     * 
     * @param value the normalized linear-light sample
     * @return the normalized gamma-encoded sample
     */
    private fun linearToSmpte428(value: Double): Double {
        return (SMPTE428_ENCODE_SCALE * value).pow(1.0 / 2.6)
    }

    /** Converts a PQ-encoded sample to normalized linear light.
     * 
     * @param value the normalized PQ-encoded sample
     * @return the normalized linear-light sample
     */
    private fun pqToLinear(value: Double): Double {
        val powered = value.pow(1.0 / PQ_M2)
        val numerator = max(powered - PQ_C1, 0.0)
        val denominator = PQ_C2 - PQ_C3 * powered
        if (denominator <= 0.0) {
            return 1.0
        }
        return (numerator / denominator).pow(1.0 / PQ_M1)
    }

    /** Converts a normalized linear-light sample to PQ encoding.
     * 
     * @param value the normalized linear-light sample
     * @return the normalized PQ-encoded sample
     */
    private fun linearToPq(value: Double): Double {
        val powered = value.pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * powered) / (1.0 + PQ_C3 * powered)).pow(PQ_M2)
    }

    /** Converts an HLG-encoded sample to normalized linear light.
     * 
     * @param value the normalized HLG-encoded sample
     * @return the normalized linear-light sample
     */
    private fun hlgToLinear(value: Double): Double {
        if (value <= 0.5) {
            return value * value / 3.0
        }
        return (exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0
    }

    /** Converts a normalized linear-light sample to HLG encoding.
     * 
     * @param value the normalized linear-light sample
     * @return the normalized HLG-encoded sample
     */
    private fun linearToHlg(value: Double): Double {
        if (value <= 1.0 / 12.0) {
            return sqrt(3.0 * value)
        }
        return HLG_A * ln(12.0 * value - HLG_B) + HLG_C
    }

    /** A 3x3 RGB conversion matrix. */
    internal class RgbMatrix
    /** Creates a 3x3 RGB conversion matrix.
     * 
     * @param m00 matrix entry at row 0, column 0
     * @param m01 matrix entry at row 0, column 1
     * @param m02 matrix entry at row 0, column 2
     * @param m10 matrix entry at row 1, column 0
     * @param m11 matrix entry at row 1, column 1
     * @param m12 matrix entry at row 1, column 2
     * @param m20 matrix entry at row 2, column 0
     * @param m21 matrix entry at row 2, column 1
     * @param m22 matrix entry at row 2, column 2
     */
    constructor(
        /** Matrix entry at row 0, column 0. */
        val m00: Double,
        /** Matrix entry at row 0, column 1. */
        val m01: Double,
        /** Matrix entry at row 0, column 2. */
        val m02: Double,
        /** Matrix entry at row 1, column 0. */
        val m10: Double,
        /** Matrix entry at row 1, column 1. */
        val m11: Double,
        /** Matrix entry at row 1, column 2. */
        val m12: Double,
        /** Matrix entry at row 2, column 0. */
        val m20: Double,
        /** Matrix entry at row 2, column 1. */
        val m21: Double,
        /** Matrix entry at row 2, column 2. */
        val m22: Double
    ) {
        /** Applies this matrix in place to an RGB triplet.
         * 
         * @param rgb the mutable RGB triplet
         */
        fun apply(rgb: DoubleArray) {
            val red = rgb[0]
            val green = rgb[1]
            val blue = rgb[2]
            rgb[0] = m00 * red + m01 * green + m02 * blue
            rgb[1] = m10 * red + m11 * green + m12 * blue
            rgb[2] = m20 * red + m21 * green + m22 * blue
        }

        /** Multiplies this matrix by another matrix.
         * 
         * @param other the right-hand matrix
         * @return the matrix product
         */
        fun multiply(other: RgbMatrix): RgbMatrix {
            return RgbMatrix(
                m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
                m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
                m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
                m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
                m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
                m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
                m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
                m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
                m20 * other.m02 + m21 * other.m12 + m22 * other.m22
            )
        }

        /** Computes the inverse matrix.
         * 
         * @return the inverse matrix, or `null` for singular matrices
         */
        fun inverse(): RgbMatrix? {
            val c00 = m11 * m22 - m12 * m21
            val c01 = m12 * m20 - m10 * m22
            val c02 = m10 * m21 - m11 * m20
            val determinant = m00 * c00 + m01 * c01 + m02 * c02
            if (abs(determinant) < 1.0e-12) {
                return null
            }
            val scale = 1.0 / determinant
            return RgbMatrix(
                c00 * scale,
                (m02 * m21 - m01 * m22) * scale,
                (m01 * m12 - m02 * m11) * scale,
                c01 * scale,
                (m00 * m22 - m02 * m20) * scale,
                (m02 * m10 - m00 * m12) * scale,
                c02 * scale,
                (m01 * m20 - m00 * m21) * scale,
                (m00 * m11 - m01 * m10) * scale
            )
        }
    }
}
