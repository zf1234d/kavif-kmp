// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.output

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.AvifColorInfo
import org.glavo.avif.av1.Av1ColorConfig
import org.glavo.avif.internal.color.CicpColorPrimaries


/** Fixed-point YUV-to-RGB transform for opaque ARGB output.
 * 
 * Coefficients are stored in the nominal 8-bit sample domain and use 16 fractional bits. The
 * transform can emit either packed `0xAARRGGBB` pixels for 8-bit output or packed
 * `0xAAAA_RRRR_GGGG_BBBB` pixels for higher-bit-depth output. The transform does not model
 * alpha; callers always receive non-premultiplied opaque pixels. */
class YuvToRgbTransform private constructor(
    lumaOffset: Int,
    chromaCenter: Int,
    lumaCoefficient: Int,
    redCoefficientU: Int,
    redCoefficientV: Int,
    greenCoefficientU: Int,
    greenCoefficientV: Int,
    blueCoefficientU: Int,
    blueCoefficientV: Int,
    identityMatrix: Boolean
) {
    /** The luma offset removed before matrix multiplication. */
    private val lumaOffset: Int

    /** The neutral chroma sample value. */
    private val chromaCenter: Int

    /** The fixed-point luma coefficient shared by every output channel. */
    private val lumaCoefficient: Int

    /** The fixed-point red-from-U coefficient. */
    private val redCoefficientU: Int

    /** The fixed-point red-from-V coefficient. */
    private val redCoefficientV: Int

    /** The fixed-point green-from-U coefficient. */
    private val greenCoefficientU: Int

    /** The fixed-point green-from-V coefficient. */
    private val greenCoefficientV: Int

    /** The fixed-point blue-from-U coefficient. */
    private val blueCoefficientU: Int

    /** The fixed-point blue-from-V coefficient. */
    private val blueCoefficientV: Int

    /** Whether this transform maps AV1 identity-matrix planes directly as RGB samples. */
    private val identityMatrix: Boolean

    /** Creates one fixed-point YUV-to-RGB transform for 8-bit sample domains.
     * 
     * @param lumaOffset the luma offset removed before matrix multiplication
     * @param chromaCenter the neutral chroma sample value
     * @param lumaCoefficient the fixed-point luma coefficient shared by every output channel
     * @param redCoefficientV the fixed-point red-from-V coefficient
     * @param greenCoefficientU the fixed-point green-from-U coefficient
     * @param greenCoefficientV the fixed-point green-from-V coefficient
     * @param blueCoefficientU the fixed-point blue-from-U coefficient
     */
    constructor(
        lumaOffset: Int,
        chromaCenter: Int,
        lumaCoefficient: Int,
        redCoefficientV: Int,
        greenCoefficientU: Int,
        greenCoefficientV: Int,
        blueCoefficientU: Int
    ) : this(
        lumaOffset,
        chromaCenter,
        lumaCoefficient,
        0,
        redCoefficientV,
        greenCoefficientU,
        greenCoefficientV,
        blueCoefficientU,
        0,
        false
    )

    init {
        require(lumaOffset >= 0) { "lumaOffset < 0: $lumaOffset" }
        require(chromaCenter >= 0) { "chromaCenter < 0: $chromaCenter" }
        require(lumaCoefficient > 0) { "lumaCoefficient <= 0: $lumaCoefficient" }
        this.lumaOffset = lumaOffset
        this.chromaCenter = chromaCenter
        this.lumaCoefficient = lumaCoefficient
        this.redCoefficientU = redCoefficientU
        this.redCoefficientV = redCoefficientV
        this.greenCoefficientU = greenCoefficientU
        this.greenCoefficientV = greenCoefficientV
        this.blueCoefficientU = blueCoefficientU
        this.blueCoefficientV = blueCoefficientV
        this.identityMatrix = identityMatrix
    }

    /** Returns the luma offset removed before matrix multiplication.
     * 
     * @return the luma offset removed before matrix multiplication
     */
    fun lumaOffset(): Int {
        return lumaOffset
    }

    /** Returns the neutral chroma sample value.
     * 
     * @return the neutral chroma sample value
     */
    fun chromaCenter(): Int {
        return chromaCenter
    }

    /** Returns the fixed-point luma coefficient shared by every output channel.
     * 
     * @return the fixed-point luma coefficient shared by every output channel
     */
    fun lumaCoefficient(): Int {
        return lumaCoefficient
    }

    /** Returns the fixed-point red-from-U coefficient.
     * 
     * @return the fixed-point red-from-U coefficient
     */
    fun redCoefficientU(): Int {
        return redCoefficientU
    }

    /** Returns the fixed-point red-from-V coefficient.
     * 
     * @return the fixed-point red-from-V coefficient
     */
    fun redCoefficientV(): Int {
        return redCoefficientV
    }

    /** Returns the fixed-point green-from-U coefficient.
     * 
     * @return the fixed-point green-from-U coefficient
     */
    fun greenCoefficientU(): Int {
        return greenCoefficientU
    }

    /** Returns the fixed-point green-from-V coefficient.
     * 
     * @return the fixed-point green-from-V coefficient
     */
    fun greenCoefficientV(): Int {
        return greenCoefficientV
    }

    /** Returns the fixed-point blue-from-U coefficient.
     * 
     * @return the fixed-point blue-from-U coefficient
     */
    fun blueCoefficientU(): Int {
        return blueCoefficientU
    }

    /** Returns the fixed-point blue-from-V coefficient.
     * 
     * @return the fixed-point blue-from-V coefficient
     */
    fun blueCoefficientV(): Int {
        return blueCoefficientV
    }

    /** Converts one luma sample with neutral chroma into an opaque grayscale ARGB pixel.
     * 
     * @param ySample the 8-bit luma sample
     * @return the packed opaque grayscale ARGB pixel
     */
    fun toOpaqueGrayArgb(ySample: Int): Int {
        if (identityMatrix) {
            val gray: Int = clampToByte(ySample)
            return packOpaqueArgb(gray, gray, gray)
        }
        return toOpaqueArgb(ySample, chromaCenter, chromaCenter)
    }

    /** Converts one luma sample with neutral chroma into an opaque grayscale ARGB pixel.
     * 
     * @param ySample the luma sample in the caller-supplied bit depth
     * @param bitDepth the decoded sample bit depth
     * @return the packed opaque grayscale ARGB pixel
     */
    fun toOpaqueGrayArgb(ySample: Int, bitDepth: Int): Int {
        if (bitDepth == 8) {
            return toOpaqueGrayArgb(ySample)
        }
        if (identityMatrix) {
            val gray: Int = sampleToByte(clampToSampleDepth(ySample, maxSample(bitDepth)), bitDepth)
            return packOpaqueArgb(gray, gray, gray)
        }
        val neutralChroma: Int = scaleNominalCodeValue(chromaCenter, bitDepth)
        return toOpaqueArgb(ySample, neutralChroma, neutralChroma, bitDepth)
    }

    /** Converts one YUV sample triplet into a packed opaque ARGB pixel.
     * 
     * @param ySample the 8-bit luma sample
     * @param uSample the 8-bit chroma U sample
     * @param vSample the 8-bit chroma V sample
     * @return the packed opaque non-premultiplied ARGB pixel
     */
    fun toOpaqueArgb(ySample: Int, uSample: Int, vSample: Int): Int {
        if (identityMatrix) {
            return packOpaqueArgb(clampToByte(vSample), clampToByte(ySample), clampToByte(uSample))
        }

        val scaledLuma = max(0, ySample - lumaOffset) * lumaCoefficient
        val centeredU = uSample - chromaCenter
        val centeredV = vSample - chromaCenter

        val red: Int = clampToByte(
            (scaledLuma + centeredU * redCoefficientU + centeredV * redCoefficientV + ROUNDING) shr FRACTION_BITS
        )
        val green: Int = clampToByte(
            (scaledLuma + centeredU * greenCoefficientU + centeredV * greenCoefficientV + ROUNDING) shr FRACTION_BITS
        )
        val blue: Int = clampToByte(
            (scaledLuma + centeredU * blueCoefficientU + centeredV * blueCoefficientV + ROUNDING) shr FRACTION_BITS
        )
        return -0x1000000 or (red shl 16) or (green shl 8) or blue
    }

    /** Converts one YUV sample triplet into a packed opaque ARGB pixel.
     * 
     * The YUV samples are interpreted in their original decoded bit depth. RGB channels are first
     * computed in that same sample domain, then reduced to unsigned 8-bit output channels.
     * 
     * @param ySample the luma sample in the caller-supplied bit depth
     * @param uSample the chroma U sample in the caller-supplied bit depth
     * @param vSample the chroma V sample in the caller-supplied bit depth
     * @param bitDepth the decoded sample bit depth
     * @return the packed opaque non-premultiplied ARGB pixel
     */
    fun toOpaqueArgb(ySample: Int, uSample: Int, vSample: Int, bitDepth: Int): Int {
        if (bitDepth == 8) {
            return toOpaqueArgb(ySample, uSample, vSample)
        }

        val sampleMax: Int = maxSample(bitDepth)
        if (identityMatrix) {
            return packOpaqueArgb(
                sampleToByte(clampToSampleDepth(vSample, sampleMax), bitDepth),
                sampleToByte(clampToSampleDepth(ySample, sampleMax), bitDepth),
                sampleToByte(clampToSampleDepth(uSample, sampleMax), bitDepth)
            )
        }

        val scaledLumaOffset: Int = scaleNominalCodeValue(lumaOffset, bitDepth)
        val scaledChromaCenter: Int = scaleNominalCodeValue(chromaCenter, bitDepth)

        val scaledLuma = max(0, ySample - scaledLumaOffset).toLong() * lumaCoefficient
        val centeredU = uSample - scaledChromaCenter
        val centeredV = vSample - scaledChromaCenter

        val red = normalizeHighBitDepthChannel(
            (((scaledLuma
                    + centeredU.toLong() * redCoefficientU + centeredV.toLong() * redCoefficientV + ROUNDING)) shr FRACTION_BITS).toInt(),
            bitDepth,
            sampleMax
        )
        val green = normalizeHighBitDepthChannel(
            (((scaledLuma
                    + centeredU.toLong() * greenCoefficientU + centeredV.toLong() * greenCoefficientV + ROUNDING)) shr FRACTION_BITS).toInt(),
            bitDepth,
            sampleMax
        )
        val blue = normalizeHighBitDepthChannel(
            (((scaledLuma
                    + centeredU.toLong() * blueCoefficientU + centeredV.toLong() * blueCoefficientV + ROUNDING)) shr FRACTION_BITS).toInt(),
            bitDepth,
            sampleMax
        )
        return packOpaqueArgb(
            sampleToByte(red, bitDepth),
            sampleToByte(green, bitDepth),
            sampleToByte(blue, bitDepth)
        )
    }

    /** Converts one luma sample with neutral chroma into an opaque 16-bit-per-channel grayscale
     * ARGB pixel.
     * 
     * @param ySample the luma sample in the caller-supplied bit depth
     * @param bitDepth the decoded sample bit depth
     * @return the packed opaque grayscale ARGB pixel in `0xAAAA_RRRR_GGGG_BBBB` format
     */
    fun toOpaqueGrayArgb64(ySample: Int, bitDepth: Int): Long {
        if (identityMatrix) {
            val gray: Int =
                expandSampleToWord(clampToSampleDepth(ySample, maxSample(bitDepth)), bitDepth)
            return packOpaqueArgb64(gray, gray, gray)
        }
        val neutralChroma: Int = scaleNominalCodeValue(chromaCenter, bitDepth)
        return toOpaqueArgb64(ySample, neutralChroma, neutralChroma, bitDepth)
    }

    /** Converts one YUV sample triplet into a packed opaque 16-bit-per-channel ARGB pixel.
     * 
     * The YUV samples are interpreted in their original decoded bit depth. RGB channels are first
     * computed in that same sample domain, then expanded to unsigned 16-bit output channels.
     * 
     * @param ySample the luma sample in the caller-supplied bit depth
     * @param uSample the chroma U sample in the caller-supplied bit depth
     * @param vSample the chroma V sample in the caller-supplied bit depth
     * @param bitDepth the decoded sample bit depth
     * @return the packed opaque non-premultiplied ARGB pixel in `0xAAAA_RRRR_GGGG_BBBB` format
     */
    fun toOpaqueArgb64(ySample: Int, uSample: Int, vSample: Int, bitDepth: Int): Long {
        val sampleMax: Int = maxSample(bitDepth)
        if (identityMatrix) {
            return packOpaqueArgb64(
                expandSampleToWord(clampToSampleDepth(vSample, sampleMax), bitDepth),
                expandSampleToWord(clampToSampleDepth(ySample, sampleMax), bitDepth),
                expandSampleToWord(clampToSampleDepth(uSample, sampleMax), bitDepth)
            )
        }

        val scaledLumaOffset: Int = scaleNominalCodeValue(lumaOffset, bitDepth)
        val scaledChromaCenter: Int = scaleNominalCodeValue(chromaCenter, bitDepth)

        val scaledLuma = max(0, ySample - scaledLumaOffset).toLong() * lumaCoefficient
        val centeredU = uSample - scaledChromaCenter
        val centeredV = vSample - scaledChromaCenter

        val red = normalizeHighBitDepthChannel(
            (((scaledLuma
                    + centeredU.toLong() * redCoefficientU + centeredV.toLong() * redCoefficientV + ROUNDING)) shr FRACTION_BITS).toInt(),
            bitDepth,
            sampleMax
        )
        val green = normalizeHighBitDepthChannel(
            (((scaledLuma
                    + centeredU.toLong() * greenCoefficientU + centeredV.toLong() * greenCoefficientV + ROUNDING)) shr FRACTION_BITS).toInt(),
            bitDepth,
            sampleMax
        )
        val blue = normalizeHighBitDepthChannel(
            (((scaledLuma
                    + centeredU.toLong() * blueCoefficientU + centeredV.toLong() * blueCoefficientV + ROUNDING)) shr FRACTION_BITS).toInt(),
            bitDepth,
            sampleMax
        )
        return packOpaqueArgb64(
            expandSampleToWord(red, bitDepth),
            expandSampleToWord(green, bitDepth),
            expandSampleToWord(blue, bitDepth)
        )
    }

    /** Normalizes one computed high-bit-depth channel into the decoded sample domain.
     * 
     * Limited-range transforms compute channels in a nominal 8-bit scale expanded by shifting,
     * so high-bit-depth output must map that nominal maximum onto the real sample maximum.
     * 
     * @param value the computed channel value before final clamping
     * @param bitDepth the decoded sample bit depth
     * @param sampleMax the inclusive maximum sample value for the decoded bit depth
     * @return the normalized and clamped channel value
     */
    private fun normalizeHighBitDepthChannel(value: Int, bitDepth: Int, sampleMax: Int): Int {
        if (lumaOffset == 0) {
            return clampToSampleDepth(value, sampleMax)
        }
        val nominalMax: Int = scaleNominalCodeValue(255, bitDepth)
        val normalized = ((value.toLong() * sampleMax + (nominalMax ushr 1)) / nominalMax).toInt()
        return clampToSampleDepth(normalized, sampleMax)
    }

    companion object {
        /** The fixed-point coefficient scaling factor. */
        private const val FRACTION_BITS = 16

        /** The rounding term applied before shifting back to 8-bit RGB. */
        const val ROUNDING = 1 shl (FRACTION_BITS - 1)

        /** Full-range `BT.601` coefficients for 8-bit YUV samples. */
        val BT601_FULL_RANGE: YuvToRgbTransform = YuvToRgbTransform(
            0,
            128,
            65536,
            91881,
            -22554,
            -46802,
            116130
        )

        /** Limited-range `BT.601` coefficients for nominal 8-bit YUV samples. */
        val BT601_LIMITED_RANGE: YuvToRgbTransform = createMatrixTransform(0.2990, 0.1140, false)

        /** Full-range `BT.709` coefficients for nominal 8-bit YUV samples. */
        val BT709_FULL_RANGE: YuvToRgbTransform = createMatrixTransform(0.2126, 0.0722, true)

        /** Limited-range `BT.709` coefficients for nominal 8-bit YUV samples. */
        val BT709_LIMITED_RANGE: YuvToRgbTransform = createMatrixTransform(0.2126, 0.0722, false)

        /** Full-range FCC coefficients for nominal 8-bit YUV samples. */
        val FCC_FULL_RANGE: YuvToRgbTransform = createMatrixTransform(0.30, 0.11, true)

        /** Limited-range FCC coefficients for nominal 8-bit YUV samples. */
        val FCC_LIMITED_RANGE: YuvToRgbTransform = createMatrixTransform(0.30, 0.11, false)

        /** Full-range `BT.2020` non-constant-luminance coefficients for nominal 8-bit YUV samples. */
        val BT2020_NCL_FULL_RANGE: YuvToRgbTransform = createMatrixTransform(0.2627, 0.0593, true)

        /** Limited-range `BT.2020` non-constant-luminance coefficients for nominal 8-bit YUV samples. */
        val BT2020_NCL_LIMITED_RANGE: YuvToRgbTransform =
            createMatrixTransform(0.2627, 0.0593, false)

        /** Full-range `SMPTE 240M` coefficients for nominal 8-bit YUV samples. */
        val SMPTE240M_FULL_RANGE: YuvToRgbTransform = createMatrixTransform(0.2120, 0.0870, true)

        /** Limited-range `SMPTE 240M` coefficients for nominal 8-bit YUV samples. */
        val SMPTE240M_LIMITED_RANGE: YuvToRgbTransform =
            createMatrixTransform(0.2120, 0.0870, false)

        /** Full-range `YCgCo` inverse transform for equal-depth component samples. */
        val YCGCO_FULL_RANGE: YuvToRgbTransform = createYcgcoTransform(true)

        /** Limited-range `YCgCo` inverse transform for equal-depth component samples. */
        val YCGCO_LIMITED_RANGE: YuvToRgbTransform = createYcgcoTransform(false)

        /** Full-range RGB identity mapping used by AV1 identity matrix signaling. */
        val RGB_IDENTITY: YuvToRgbTransform = YuvToRgbTransform(
            0,
            0,
            65536,
            0,
            0,
            0,
            0,
            0,
            0,
            true
        )

        /** Selects a display transform for one parsed AV1 color configuration.
         * 
         * Unspecified matrix coefficients use `BT.601` while preserving the signaled sample range.
         * Matrix coefficients are irrelevant for monochrome planes. Explicit matrix families that
         * this implementation does not model are rejected instead of being rendered with a different
         * matrix.
         * 
         * @param colorConfig the AV1 color configuration
         * @return the selected fixed-point YUV-to-RGB transform
         * @throws UnsupportedOperationException if a chroma stream signals an unsupported explicit matrix family
         */
        fun fromColorConfig(colorConfig: Av1ColorConfig): YuvToRgbTransform {
            val checkedColorConfig =
                requireNotNullCompat(colorConfig, "colorConfig")
            val fullRange = checkedColorConfig.colorRange
            return fromMatrixCoefficients(
                checkedColorConfig.matrixCoefficients,
                checkedColorConfig.colorPrimaries,
                fullRange,
                checkedColorConfig.monochrome
            )
        }

        /** Selects a display transform for one AVIF container `nclx` color property.
         * 
         * The output path currently models YUV matrix and range conversion. ICC profile
         * application and transfer-function adaptation remain metadata-only.
         * 
         * @param colorInfo the AVIF container color information
         * @param monochrome whether the decoded AV1 planes are monochrome
         * @return the selected fixed-point YUV-to-RGB transform
         * @throws UnsupportedOperationException if chroma planes use an unsupported explicit matrix family
         */
        fun fromColorInfo(colorInfo: AvifColorInfo, monochrome: Boolean): YuvToRgbTransform {
            val checkedColorInfo = requireNotNullCompat(colorInfo, "colorInfo")
            return fromMatrixCoefficients(
                checkedColorInfo.matrixCoefficients(),
                checkedColorInfo.colorPrimaries(),
                checkedColorInfo.fullRange(),
                monochrome
            )
        }

        /** Selects a display transform from CICP matrix coefficients and sample-range signaling.
         * 
         * @param matrixCoefficients the CICP matrix coefficients value
         * @param colorPrimaries the CICP color-primary value used by chromaticity-derived matrices
         * @param fullRange whether samples are full range
         * @param monochrome whether the decoded planes are monochrome
         * @return the selected fixed-point YUV-to-RGB transform
         * @throws UnsupportedOperationException if chroma planes use an unsupported explicit matrix family
         */
        fun fromMatrixCoefficients(
            matrixCoefficients: Int,
            colorPrimaries: Int,
            fullRange: Boolean,
            monochrome: Boolean
        ): YuvToRgbTransform {
            if (monochrome) {
                return defaultTransform(fullRange)
            }
            return when (matrixCoefficients) {
                0 -> RGB_IDENTITY
                1 -> if (fullRange) BT709_FULL_RANGE else BT709_LIMITED_RANGE
                2 -> defaultTransform(fullRange)
                4 -> if (fullRange) FCC_FULL_RANGE else FCC_LIMITED_RANGE
                5, 6 -> if (fullRange) BT601_FULL_RANGE else BT601_LIMITED_RANGE
                7 -> if (fullRange) SMPTE240M_FULL_RANGE else SMPTE240M_LIMITED_RANGE
                8 -> if (fullRange) YCGCO_FULL_RANGE else YCGCO_LIMITED_RANGE
                9 -> if (fullRange) BT2020_NCL_FULL_RANGE else BT2020_NCL_LIMITED_RANGE
                12 -> chromaticityDerivedTransform(colorPrimaries, fullRange)
                else -> throw UnsupportedOperationException(
                    "Unsupported CICP matrix coefficients: $matrixCoefficients"
                )
            }
        }

        /** Creates a non-constant-luminance transform from signaled color-primary chromaticities.
         * 
         * @param colorPrimaries the CICP color-primary value
         * @param fullRange whether samples are full range
         * @return the chromaticity-derived transform
         * @throws UnsupportedOperationException if the color-primary code is unsupported
         * @throws IllegalStateException if the primary definition cannot produce luma coefficients
         */
        fun chromaticityDerivedTransform(
            colorPrimaries: Int,
            fullRange: Boolean
        ): YuvToRgbTransform {
            val coefficients =
                CicpColorPrimaries.resolveForMatrix(colorPrimaries).lumaCoefficients()
            return createMatrixTransform(coefficients.red, coefficients.blue, fullRange)
        }

        /** Creates one matrix transform from luma coefficients and range signaling.
         * 
         * @param redLumaCoefficient the red channel luma coefficient `Kr`
         * @param blueLumaCoefficient the blue channel luma coefficient `Kb`
         * @param fullRange whether nominal full-range sample values are used
         * @return one fixed-point transform
         */
        fun createMatrixTransform(
            redLumaCoefficient: Double,
            blueLumaCoefficient: Double,
            fullRange: Boolean
        ): YuvToRgbTransform {
            val greenLumaCoefficient = 1.0 - redLumaCoefficient - blueLumaCoefficient
            val lumaScale = if (fullRange) 1.0 else 255.0 / 219.0
            val chromaScale = if (fullRange) 1.0 else 255.0 / 224.0
            return YuvToRgbTransform(
                if (fullRange) 0 else 16,
                128,
                fixed(lumaScale),
                fixed(2.0 * (1.0 - redLumaCoefficient) * chromaScale),
                fixed(-2.0 * blueLumaCoefficient * (1.0 - blueLumaCoefficient) / greenLumaCoefficient * chromaScale),
                fixed(-2.0 * redLumaCoefficient * (1.0 - redLumaCoefficient) / greenLumaCoefficient * chromaScale),
                fixed(2.0 * (1.0 - blueLumaCoefficient) * chromaScale)
            )
        }

        /** Creates the equal-bit-depth inverse `YCgCo` transform defined by H.273.
         * 
         * Limited-range `YCgCo` applies the luma-range scale to every reconstructed RGB component;
         * its reversible color-difference samples do not use the conventional 224-code chroma range.
         * 
         * @param fullRange whether nominal full-range component values are used
         * @return one fixed-point inverse `YCgCo` transform
         */
        fun createYcgcoTransform(fullRange: Boolean): YuvToRgbTransform {
            val componentScale = if (fullRange) 1.0 else 255.0 / 219.0
            return YuvToRgbTransform(
                if (fullRange) 0 else 16,
                128,
                fixed(componentScale),
                fixed(-componentScale),
                fixed(componentScale),
                fixed(componentScale),
                0,
                fixed(-componentScale),
                fixed(-componentScale),
                false
            )
        }

        /** Converts a floating-point coefficient into 16-bit fixed point.
         * 
         * @param value the floating-point coefficient
         * @return the rounded fixed-point coefficient
         */
        fun fixed(value: Double): Int {
            return roundCompat(value * (1 shl FRACTION_BITS)).toInt()
        }

        /** Returns the default transform for unspecified matrix coefficients or monochrome output.
         * 
         * @param fullRange whether the signaled samples are full range
         * @return the default transform
         */
        fun defaultTransform(fullRange: Boolean): YuvToRgbTransform {
            return if (fullRange) BT601_FULL_RANGE else BT601_LIMITED_RANGE
        }

        /** Packs three unsigned 8-bit channels into opaque `0xAARRGGBB` output.
         * 
         * @param red the 8-bit red channel
         * @param green the 8-bit green channel
         * @param blue the 8-bit blue channel
         * @return the packed opaque ARGB pixel
         */
        fun packOpaqueArgb(red: Int, green: Int, blue: Int): Int {
            return -0x1000000 or (red shl 16) or (green shl 8) or blue
        }

        /** Clamps one integer channel value to the unsigned 8-bit range.
         * 
         * @param value the channel value before clamping
         * @return the clamped unsigned 8-bit channel value
         */
        fun clampToByte(value: Int): Int {
            if (value <= 0) {
                return 0
            }
            if (value >= 255) {
                return 255
            }
            return value
        }

        /** Clamps one integer channel value to the caller-supplied decoded sample range.
         * 
         * @param value the channel value before clamping
         * @param sampleMax the inclusive maximum sample value for the decoded bit depth
         * @return the clamped channel value
         */
        fun clampToSampleDepth(value: Int, sampleMax: Int): Int {
            if (value <= 0) {
                return 0
            }
            if (value >= sampleMax) {
                return sampleMax
            }
            return value
        }

        /** Reduces one decoded sample value to an unsigned 8-bit output channel.
         * 
         * @param sample the decoded sample value
         * @param bitDepth the decoded sample bit depth
         * @return the reduced unsigned 8-bit channel
         */
        fun sampleToByte(sample: Int, bitDepth: Int): Int {
            val sampleMax: Int = maxSample(bitDepth)
            return ((sample.toLong() * 255 + (sampleMax ushr 1)) / sampleMax).toInt()
        }

        /** Packs three unsigned 16-bit channels into opaque `0xAAAA_RRRR_GGGG_BBBB` output.
         * 
         * @param red the 16-bit red channel
         * @param green the 16-bit green channel
         * @param blue the 16-bit blue channel
         * @return the packed opaque ARGB pixel
         */
        fun packOpaqueArgb64(red: Int, green: Int, blue: Int): Long {
            return (-0x1000000000000L
                    or (red.toLong() shl 32)
                    or (green.toLong() shl 16)
                    or (blue.toLong() and 0xFFFFL))
        }

        /** Expands one decoded sample to an unsigned 16-bit output channel.
         * 
         * @param sample the decoded sample value
         * @param bitDepth the decoded sample bit depth
         * @return the expanded unsigned 16-bit output channel
         */
        fun expandSampleToWord(sample: Int, bitDepth: Int): Int {
            val sampleMax: Int = maxSample(bitDepth)
            return ((sample.toLong() * 65535 + (sampleMax ushr 1)) / sampleMax).toInt()
        }

        /** Scales one nominal 8-bit code value into the caller-supplied decoded bit depth.
         * 
         * @param nominalCodeValue the nominal 8-bit code value
         * @param bitDepth the decoded sample bit depth
         * @return the scaled code value in the decoded sample domain
         */
        fun scaleNominalCodeValue(nominalCodeValue: Int, bitDepth: Int): Int {
            require(bitDepth in 8..16) { "Unsupported bitDepth: $bitDepth" }
            return nominalCodeValue shl (bitDepth - 8)
        }

        /** Returns the inclusive maximum sample value for one decoded bit depth.
         * 
         * @param bitDepth the decoded sample bit depth
         * @return the inclusive maximum sample value
         */
        fun maxSample(bitDepth: Int): Int {
            require(bitDepth in 1..16) { "Unsupported bitDepth: $bitDepth" }
            return (1 shl bitDepth) - 1
        }
    }
}
