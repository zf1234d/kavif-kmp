// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

import org.glavo.avif.AvifCicpColorTransforms.RgbMatrix
import org.glavo.avif.internal.av1.output.YuvToRgbTransform


/** Applies parsed AVIF gain-map metadata to decoded RGB frame output.
 * 
 * This implementation follows the ISO gain-map equation over normalized RGB samples, uses CICP
 * transfer functions where available, converts between supported RGB primary sets in linear
 * light, and leaves ICC transforms metadata-only. */
internal object AvifGainMapToneMapper {
    /** Applies one gain map to a decoded base frame.
     * 
     * @param baseFrame the decoded base frame
     * @param gainMapPlanes the decoded gain-map planes, or `null` when the gain-map weight is zero
     * @param metadata the parsed gain-map metadata
     * @param baseColorInfo the base image CICP color information, or `null`
     * @param toneMappedColorInfo the tone-mapped item CICP color information, or `null`
     * @param gainMapColorInfo the gain-map image item CICP color information, or `null`
     * @param outputColorInfo the requested output CICP color information, or `null` to preserve the base color space
     * @param hdrHeadroom the requested display HDR headroom in log2 space
     * @return the tone-mapped frame, or the original frame when no pixel transform is needed
     */
    fun apply(
        baseFrame: AvifFrame,
        gainMapPlanes: Av1DecodedPlanes?,
        metadata: AvifGainMapMetadata,
        baseColorInfo: AvifColorInfo?,
        toneMappedColorInfo: AvifColorInfo?,
        gainMapColorInfo: AvifColorInfo?,
        outputColorInfo: AvifColorInfo?,
        hdrHeadroom: Double
    ): AvifFrame {
        val checkedBaseFrame = requireNotNullCompat(baseFrame, "baseFrame")
        val math = GainMapMath(
            requireNotNullCompat(metadata, "metadata"),
            baseColorInfo,
            toneMappedColorInfo,
            outputColorInfo,
            hdrHeadroom
        )
        if (!math.requiresPixelTransform()) {
            return checkedBaseFrame
        }
        var sampler: GainMapSampler? = null
        if (math.requiresGainMap()) {
            sampler = GainMapSampler(
                requireNotNullCompat(gainMapPlanes, "gainMapPlanes"),
                checkedBaseFrame.width(),
                checkedBaseFrame.height(),
                gainMapColorInfo
            )
        }
        if (checkedBaseFrame.pixelFormat() == AvifPixelFormat.ARGB_8888) {
            return applyToIntFrame(checkedBaseFrame, sampler, math)
        }
        if (checkedBaseFrame.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
            return applyToLongFrame(checkedBaseFrame, sampler, math)
        }
        throw IllegalArgumentException("Unsupported pixel format: " + checkedBaseFrame.pixelFormat())
    }

    /** Returns whether the gain-map planes must be decoded for one requested headroom.
     * 
     * @param metadata the parsed gain-map metadata
     * @param hdrHeadroom the requested display HDR headroom in log2 space
     * @return whether the gain-map planes must be decoded
     */
    fun requiresGainMap(metadata: AvifGainMapMetadata, hdrHeadroom: Double): Boolean {
            require(!(!hdrHeadroom.isFinite() || hdrHeadroom < 0.0)) { "hdrHeadroom must be a finite non-negative value" }
        val checkedMetadata = requireNotNullCompat(metadata, "metadata")
        return GainMapMath.Companion.computeWeight(
            hdrHeadroom,
            checkedMetadata.baseHdrHeadroom(),
            checkedMetadata.alternateHdrHeadroom()
        ) != 0.0
    }

    /** Applies gain-map metadata to an 8-bit ARGB frame.
     * 
     * @param baseFrame the decoded base frame
     * @param sampler the gain-map sampler, or `null` when only output color conversion is needed
     * @param math the precomputed gain-map math state
     * @return the tone-mapped frame
     */
    private fun applyToIntFrame(
        baseFrame: AvifFrame,
        sampler: GainMapSampler?,
        math: GainMapMath
    ): AvifFrame {
        val basePixels = IntBuf.wrap(baseFrame.intPixels())
        val width = baseFrame.width()
        val height = baseFrame.height()
        val toneMapped = IntArray(width * height)
        val mappedRgb = DoubleArray(3)
        val useGainMap = math.requiresGainMap()
        val checkedSampler: GainMapSampler? =
            if (useGainMap) requireNotNullCompat(sampler, "sampler") else null
        for (y in 0..<height) {
            val row = y * width
            for (x in 0..<width) {
                val pixel = basePixels.get(row + x)
                val alpha = pixel and -0x1000000
                val redSample = ((pixel ushr 16) and 0xFF) / 255.0
                val greenSample = ((pixel ushr 8) and 0xFF) / 255.0
                val blueSample = (pixel and 0xFF) / 255.0
                if (useGainMap) {
                    math.applyGainMap(
                        redSample,
                        greenSample,
                        blueSample,
                        checkedSampler!!.channel(x, y, 0),
                        checkedSampler.channel(x, y, 1),
                        checkedSampler.channel(x, y, 2),
                        mappedRgb
                    )
                } else {
                    math.convertBaseOnly(redSample, greenSample, blueSample, mappedRgb)
                }
                val red = unitToByte(mappedRgb[0])
                val green = unitToByte(mappedRgb[1])
                val blue = unitToByte(mappedRgb[2])
                toneMapped[row + x] = alpha or (red shl 16) or (green shl 8) or blue
            }
        }
        return AvifFrame.fromOwnedPixels(
            width,
            height,
            baseFrame.bitDepth(),
            baseFrame.chromaFormat(),
            baseFrame.frameIndex(),
            toneMapped
        )
    }

    /** Applies gain-map metadata to a 16-bit-per-channel ARGB frame.
     * 
     * @param baseFrame the decoded base frame
     * @param sampler the gain-map sampler, or `null` when only output color conversion is needed
     * @param math the precomputed gain-map math state
     * @return the tone-mapped frame
     */
    private fun applyToLongFrame(
        baseFrame: AvifFrame,
        sampler: GainMapSampler?,
        math: GainMapMath
    ): AvifFrame {
        val basePixels = LongBuf.wrap(baseFrame.longPixels())
        val width = baseFrame.width()
        val height = baseFrame.height()
        val toneMapped = LongArray(width * height)
        val mappedRgb = DoubleArray(3)
        val useGainMap = math.requiresGainMap()
        val checkedSampler: GainMapSampler? =
            if (useGainMap) requireNotNullCompat(sampler, "sampler") else null
        for (y in 0..<height) {
            val row = y * width
            for (x in 0..<width) {
                val pixel = basePixels.get(row + x)
                val alpha = pixel and -0x1000000000000L
                val redSample = ((pixel ushr 32) and 0xFFFFL) / 65535.0
                val greenSample = ((pixel ushr 16) and 0xFFFFL) / 65535.0
                val blueSample = (pixel and 0xFFFFL) / 65535.0
                if (useGainMap) {
                    math.applyGainMap(
                        redSample,
                        greenSample,
                        blueSample,
                        checkedSampler!!.channel(x, y, 0),
                        checkedSampler.channel(x, y, 1),
                        checkedSampler.channel(x, y, 2),
                        mappedRgb
                    )
                } else {
                    math.convertBaseOnly(redSample, greenSample, blueSample, mappedRgb)
                }
                val red = unitToWord(mappedRgb[0])
                val green = unitToWord(mappedRgb[1])
                val blue = unitToWord(mappedRgb[2])
                toneMapped[row + x] = alpha or (red shl 32) or (green shl 16) or blue
            }
        }
        return AvifFrame.fromOwnedPixels(
            width,
            height,
            baseFrame.bitDepth(),
            baseFrame.chromaFormat(),
            baseFrame.frameIndex(),
            toneMapped
        )
    }

    /** Converts a normalized value to an unsigned 8-bit channel.
     * 
     * @param value the normalized value
     * @return the unsigned 8-bit channel
     */
    private fun unitToByte(value: kotlin.Double): Int {
        return roundCompat(AvifCicpColorTransforms.clampUnit(value) * 255.0).toInt()
    }

    /** Converts a normalized value to an unsigned 16-bit channel.
     * 
     * @param value the normalized value
     * @return the unsigned 16-bit channel
     */
    private fun unitToWord(value: kotlin.Double): Long {
        return roundCompat(AvifCicpColorTransforms.clampUnit(value) * 65535.0)
    }

    /** Precomputed gain-map equation state. */
    class GainMapMath(
        metadata: AvifGainMapMetadata,
        baseColorInfo: AvifColorInfo?,
        toneMappedColorInfo: AvifColorInfo?,
        outputColorInfo: AvifColorInfo?,
        hdrHeadroom: kotlin.Double
    ) {
        /** The gain-map application weight. */
        val weight: kotlin.Double

        /** Per-channel minimum gain-map values in log2 space. */
        val gainMapMin: DoubleArray

        /** Per-channel maximum gain-map values in log2 space. */
        val gainMapMax: DoubleArray

        /** Per-channel inverse gamma values. */
        val gammaInverse: DoubleArray

        /** Per-channel base image offsets. */
        val baseOffset: DoubleArray

        /** Per-channel alternate image offsets. */
        val alternateOffset: DoubleArray

        /** Base gamma-to-linear transfer color information. */
        val baseColorInfo: AvifColorInfo?

        /** Output linear-to-gamma transfer color information. */
        val outputColorInfo: AvifColorInfo?

        /** Base-to-gain-map-math linear RGB primary conversion. */
        val inputConversion: RgbMatrix

        /** Gain-map-math-to-output linear RGB primary conversion. */
        val outputConversion: RgbMatrix

        /** Base-to-output linear RGB primary conversion used when gain-map weight is zero. */
        val baseOutputConversion: RgbMatrix

        /** Whether output color conversion is required when the gain-map weight is zero. */
        val outputColorConversionRequired: Boolean

        init {
        require(!(!hdrHeadroom.isFinite() || hdrHeadroom < 0.0)) { "hdrHeadroom must be a finite non-negative value" }
            val gainMapMathColorInfo =
                if (metadata.useBaseColorSpace() || toneMappedColorInfo == null) baseColorInfo else toneMappedColorInfo
            val effectiveOutputColorInfo =
                if (outputColorInfo != null) outputColorInfo else baseColorInfo
            this.weight = computeWeight(
                hdrHeadroom,
                metadata.baseHdrHeadroom(),
                metadata.alternateHdrHeadroom()
            )
            this.gainMapMin = signedFractionsToDoubles(metadata.gainMapMin())
            this.gainMapMax = signedFractionsToDoubles(metadata.gainMapMax())
            this.gammaInverse = inverseUnsignedFractions(metadata.gainMapGamma())
            this.baseOffset = signedFractionsToDoubles(metadata.baseOffset())
            this.alternateOffset = signedFractionsToDoubles(metadata.alternateOffset())
            this.baseColorInfo = baseColorInfo
            this.outputColorInfo = effectiveOutputColorInfo
            this.inputConversion =
                AvifCicpColorTransforms.conversionMatrix(baseColorInfo, gainMapMathColorInfo)
            this.outputConversion = AvifCicpColorTransforms.conversionMatrix(
                gainMapMathColorInfo,
                effectiveOutputColorInfo
            )
            this.baseOutputConversion =
                AvifCicpColorTransforms.conversionMatrix(baseColorInfo, effectiveOutputColorInfo)
            this.outputColorConversionRequired =
                !AvifCicpColorTransforms.sameEffectiveRgbColorSpace(
                    baseColorInfo,
                    effectiveOutputColorInfo
                )
        }

        /** Returns whether the gain map must be sampled.
         * 
         * @return whether the gain map must be sampled
         */
        fun requiresGainMap(): Boolean {
            return weight != 0.0
        }

        /** Returns whether any pixel transform is required.
         * 
         * @return whether any pixel transform is required
         */
        fun requiresPixelTransform(): Boolean {
            return requiresGainMap() || outputColorConversionRequired
        }

        /** Converts one normalized base RGB triplet into the requested output color space.
         * 
         * @param baseRed the normalized base red channel
         * @param baseGreen the normalized base green channel
         * @param baseBlue the normalized base blue channel
         * @param outputRgb the mutable normalized gamma-encoded output RGB triplet
         */
        fun convertBaseOnly(
            baseRed: kotlin.Double,
            baseGreen: kotlin.Double,
            baseBlue: kotlin.Double,
            outputRgb: DoubleArray
        ) {
            outputRgb[0] = AvifCicpColorTransforms.gammaToLinear(baseRed, baseColorInfo)
            outputRgb[1] = AvifCicpColorTransforms.gammaToLinear(baseGreen, baseColorInfo)
            outputRgb[2] = AvifCicpColorTransforms.gammaToLinear(baseBlue, baseColorInfo)
            baseOutputConversion.apply(outputRgb)
            outputRgb[0] = AvifCicpColorTransforms.linearToGamma(outputRgb[0], outputColorInfo)
            outputRgb[1] = AvifCicpColorTransforms.linearToGamma(outputRgb[1], outputColorInfo)
            outputRgb[2] = AvifCicpColorTransforms.linearToGamma(outputRgb[2], outputColorInfo)
        }

        /** Applies gain-map math to one normalized gamma-encoded RGB triplet.
         * 
         * @param baseRed the normalized base red channel
         * @param baseGreen the normalized base green channel
         * @param baseBlue the normalized base blue channel
         * @param gainMapRed the normalized gain-map red channel
         * @param gainMapGreen the normalized gain-map green channel
         * @param gainMapBlue the normalized gain-map blue channel
         * @param outputRgb the mutable normalized gamma-encoded output RGB triplet
         */
        fun applyGainMap(
            baseRed: kotlin.Double,
            baseGreen: kotlin.Double,
            baseBlue: kotlin.Double,
            gainMapRed: kotlin.Double,
            gainMapGreen: kotlin.Double,
            gainMapBlue: kotlin.Double,
            outputRgb: DoubleArray
        ) {
            outputRgb[0] = AvifCicpColorTransforms.gammaToLinear(baseRed, baseColorInfo)
            outputRgb[1] = AvifCicpColorTransforms.gammaToLinear(baseGreen, baseColorInfo)
            outputRgb[2] = AvifCicpColorTransforms.gammaToLinear(baseBlue, baseColorInfo)
            inputConversion.apply(outputRgb)
            outputRgb[0] = applyChannel(0, outputRgb[0], gainMapRed)
            outputRgb[1] = applyChannel(1, outputRgb[1], gainMapGreen)
            outputRgb[2] = applyChannel(2, outputRgb[2], gainMapBlue)
            outputConversion.apply(outputRgb)
            outputRgb[0] = AvifCicpColorTransforms.linearToGamma(outputRgb[0], outputColorInfo)
            outputRgb[1] = AvifCicpColorTransforms.linearToGamma(outputRgb[1], outputColorInfo)
            outputRgb[2] = AvifCicpColorTransforms.linearToGamma(outputRgb[2], outputColorInfo)
        }

        /** Applies gain-map math to one linear-light channel.
         * 
         * @param channel the RGB channel index
         * @param baseLinear the normalized linear-light base value
         * @param gainMapSample the normalized gain-map sample
         * @return the normalized linear-light tone-mapped value
         */
        fun applyChannel(
            channel: Int,
            baseLinear: kotlin.Double,
            gainMapSample: kotlin.Double
        ): kotlin.Double {
            val gainMapEncoded = clampUnit(gainMapSample)
            val gainMapLog2 = lerp(
                gainMapMin[channel],
                gainMapMax[channel],
                gainMapEncoded.pow(gammaInverse[channel])
            )
            return ((baseLinear + baseOffset[channel]) * 2.0.pow(gainMapLog2 * weight)
                    - alternateOffset[channel])
        }

        companion object {
            /** Computes the gain-map application weight for one display headroom.
             * 
             * @param hdrHeadroom the requested display HDR headroom in log2 space
             * @param baseHdrHeadroom the base image HDR headroom
             * @param alternateHdrHeadroom the alternate image HDR headroom
             * @return the gain-map application weight
             */
            fun computeWeight(
                hdrHeadroom: kotlin.Double,
                baseHdrHeadroom: AvifUnsignedFraction,
                alternateHdrHeadroom: AvifUnsignedFraction
            ): kotlin.Double {
                val base = baseHdrHeadroom.toDouble()
                val alternate = alternateHdrHeadroom.toDouble()
                if (base == alternate) {
                    return 0.0
                }
                val weight = clampUnit((hdrHeadroom - base) / (alternate - base))
                return if (alternate < base) -weight else weight
            }

            /** Linearly interpolates between two values.
             * 
             * @param a the first value
             * @param b the second value
             * @param weight the interpolation weight
             * @return the interpolated value
             */
            fun lerp(
                a: kotlin.Double,
                b: kotlin.Double,
                weight: kotlin.Double
            ): kotlin.Double {
                return (1.0 - weight) * a + weight * b
            }

            /** Clamps a normalized sample to the closed unit interval.
             * 
             * @param value the sample value
             * @return the clamped value
             */
            fun clampUnit(value: kotlin.Double): kotlin.Double {
                return AvifCicpColorTransforms.clampUnit(value)
            }

            /** Converts signed fractions to `double` values.
             * 
             * @param fractions the source fractions
             * @return converted values
             */
            fun signedFractionsToDoubles(
                fractions: Array<AvifSignedFraction>
            ): DoubleArray {
                val result = DoubleArray(fractions.size)
                for (i in fractions.indices) {
                    result[i] = fractions[i].toDouble()
                }
                return result
            }

            /** Converts unsigned gamma fractions to inverse `double` values.
             * 
             * @param fractions the source gamma fractions
             * @return inverse gamma values
             */
            fun inverseUnsignedFractions(
                fractions: Array<AvifUnsignedFraction>
            ): DoubleArray {
                val result = DoubleArray(fractions.size)
                for (i in fractions.indices) {
                    result[i] = 1.0 / fractions[i].toDouble()
                }
                return result
            }
        }
    }

    /** Samples normalized RGB gain-map values at base-frame coordinates. */
    class GainMapSampler(
        planes: Av1DecodedPlanes,
        baseWidth: Int,
        baseHeight: Int,
        gainMapColorInfo: AvifColorInfo?
    ) {
        /** The decoded gain-map planes. */
        val planes: Av1DecodedPlanes

        /** The base frame width in pixels. */
        val baseWidth: Int

        /** The base frame height in pixels. */
        val baseHeight: Int

        /** The maximum gain-map sample value. */
        val maxSample: Int

        /** The gain-map YUV-to-RGB transform. */
        val yuvToRgbTransform: YuvToRgbTransform

        /** The cached RGB pixel for the last sampled gain-map coordinate. */
        var cachedRgb = 0

        /** The last sampled gain-map x coordinate, or -1 before the first sample. */
        var cachedX = -1

        /** The last sampled gain-map y coordinate, or -1 before the first sample. */
        var cachedY = -1

        init {
            require(!(baseWidth <= 0 || baseHeight <= 0)) { "base frame dimensions must be positive" }
            this.planes = requireNotNullCompat(planes, "planes")
            this.baseWidth = baseWidth
            this.baseHeight = baseHeight
            this.maxSample = planes.bitDepth().maxSampleValue()
            this.yuvToRgbTransform = if (gainMapColorInfo != null)
                YuvToRgbTransform.Companion.fromColorInfo(
                    gainMapColorInfo,
                    planes.chromaFormat() == Av1ChromaFormat.MONOCHROME
                )
            else
                YuvToRgbTransform.Companion.BT601_FULL_RANGE
        }

        /** Returns a normalized gain-map RGB channel for one base pixel coordinate.
         * 
         * @param baseX the base frame x coordinate
         * @param baseY the base frame y coordinate
         * @param channel the RGB channel index
         * @return the normalized gain-map channel
         */
        fun channel(baseX: Int, baseY: Int, channel: Int): kotlin.Double {
            val x = mapCoordinate(baseX, baseWidth, planes.codedWidth())
            val y = mapCoordinate(baseY, baseHeight, planes.codedHeight())
            if (planes.chromaFormat() == Av1ChromaFormat.MONOCHROME) {
                return sampleToUnit(planes.lumaPlane().sample(x, y))
            }
            val rgb = rgbAt(x, y)
            return when (channel) {
                0 -> ((rgb ushr 16) and 0xFF) / 255.0
                1 -> ((rgb ushr 8) and 0xFF) / 255.0
                2 -> (rgb and 0xFF) / 255.0
                else -> throw IllegalArgumentException("channel out of range: $channel")
            }
        }

        /** Converts one gain-map YUV sample location to an 8-bit RGB pixel.
         * 
         * @param x the gain-map luma x coordinate
         * @param y the gain-map luma y coordinate
         * @return an opaque ARGB pixel
         */
        fun rgbAt(x: Int, y: Int): Int {
            if (x == cachedX && y == cachedY) {
                return cachedRgb
            }
            val chromaUPlane = planes.chromaUPlane()
            val chromaVPlane = planes.chromaVPlane()
            require(!(chromaUPlane == null || chromaVPlane == null)) { "Gain-map chroma planes are missing" }
            val chromaX = chromaX(x)
            val chromaY = chromaY(y)
            cachedRgb = yuvToRgbTransform.toOpaqueArgb(
                planes.lumaPlane().sample(x, y),
                chromaUPlane.sample(chromaX, chromaY),
                chromaVPlane.sample(chromaX, chromaY),
                planes.bitDepth().bits()
            )
            cachedX = x
            cachedY = y
            return cachedRgb
        }

        /** Converts one decoded gain-map sample to the normalized unit interval.
         * 
         * @param sample the decoded sample
         * @return the normalized sample
         */
        fun sampleToUnit(sample: Int): kotlin.Double {
            return AvifCicpColorTransforms.clampUnit(sample.toDouble() / maxSample.toDouble())
        }

        /** Returns the chroma x coordinate for one luma x coordinate.
         * 
         * @param x the luma x coordinate
         * @return the chroma x coordinate
         */
        fun chromaX(x: Int): Int {
            return when (planes.chromaFormat()) {
                Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> x
                Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> x shr 1
            }
        }

        /** Returns the chroma y coordinate for one luma y coordinate.
         * 
         * @param y the luma y coordinate
         * @return the chroma y coordinate
         */
        fun chromaY(y: Int): Int {
            return when (planes.chromaFormat()) {
                Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> y
                Av1ChromaFormat.YUV420 -> y shr 1
            }
        }

        companion object {
            /** Maps a base-frame coordinate into the gain-map coordinate space.
             * 
             * @param value the base-frame coordinate
             * @param sourceSize the base-frame dimension
             * @param targetSize the gain-map dimension
             * @return the mapped gain-map coordinate
             */
            fun mapCoordinate(value: Int, sourceSize: Int, targetSize: Int): Int {
                val mapped = value.toLong() * targetSize / sourceSize
                if (mapped >= targetSize) {
                    return targetSize - 1
                }
                return mapped.toInt()
            }
        }
    }
}
