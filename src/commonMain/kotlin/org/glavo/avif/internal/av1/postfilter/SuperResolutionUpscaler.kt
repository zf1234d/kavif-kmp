// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.model.FrameHeader


/** Applies the normative AV1 horizontal super-resolution stage to decoded planes.
 * 
 * This stage consumes coded-width samples after loop filtering and CDEF and produces the
 * upscaled-width sample domain consumed by loop restoration and stored reference surfaces. */
internal object SuperResolutionUpscaler {
    /** The number of coefficients in one normative super-resolution filter. */
    private const val FILTER_TAP_COUNT = 8

    /** The normative super-resolution filter normalization shift. */
    private const val FILTER_BITS = 7

    /** The number of AV1 super-resolution fractional phase bits. */
    private const val SUBPEL_BITS = 6

    /** The fixed-point precision used by AV1 super-resolution coordinate stepping. */
    private const val SCALE_SUBPEL_BITS = 14

    /** The mask for one AV1 super-resolution fixed-point coordinate. */
    private const val SCALE_SUBPEL_MASK = (1 shl SCALE_SUBPEL_BITS) - 1

    /** The extra fixed-point bits above the normative filter phase precision. */
    private const val SCALE_EXTRA_BITS = SCALE_SUBPEL_BITS - SUBPEL_BITS

    /** The half-phase offset used when deriving the first output sample position. */
    private const val SCALE_EXTRA_OFFSET = 1 shl (SCALE_EXTRA_BITS - 1)

    /** The signed source-sample offset of the first filter tap.
     * 
     * The normative convolution receives the source pointer one sample before the coded tile
     * origin and then applies the usual three-sample filter offset. */
    private const val FILTER_START_OFFSET = FILTER_TAP_COUNT / 2

    /** The AV1 normative 64-phase horizontal super-resolution filters. */
    private val FILTERS: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(0, 0, 0, 128, 0, 0, 0, 0),
        intArrayOf(0, 0, -1, 128, 2, -1, 0, 0),
        intArrayOf(0, 1, -3, 127, 4, -2, 1, 0),
        intArrayOf(0, 1, -4, 127, 6, -3, 1, 0),
        intArrayOf(0, 2, -6, 126, 8, -3, 1, 0),
        intArrayOf(0, 2, -7, 125, 11, -4, 1, 0),
        intArrayOf(-1, 2, -8, 125, 13, -5, 2, 0),
        intArrayOf(-1, 3, -9, 124, 15, -6, 2, 0),
        intArrayOf(-1, 3, -10, 123, 18, -6, 2, -1),
        intArrayOf(-1, 3, -11, 122, 20, -7, 3, -1),
        intArrayOf(-1, 4, -12, 121, 22, -8, 3, -1),
        intArrayOf(-1, 4, -13, 120, 25, -9, 3, -1),
        intArrayOf(-1, 4, -14, 118, 28, -9, 3, -1),
        intArrayOf(-1, 4, -15, 117, 30, -10, 4, -1),
        intArrayOf(-1, 5, -16, 116, 32, -11, 4, -1),
        intArrayOf(-1, 5, -16, 114, 35, -12, 4, -1),
        intArrayOf(-1, 5, -17, 112, 38, -12, 4, -1),
        intArrayOf(-1, 5, -18, 111, 40, -13, 5, -1),
        intArrayOf(-1, 5, -18, 109, 43, -14, 5, -1),
        intArrayOf(-1, 6, -19, 107, 45, -14, 5, -1),
        intArrayOf(-1, 6, -19, 105, 48, -15, 5, -1),
        intArrayOf(-1, 6, -19, 103, 51, -16, 5, -1),
        intArrayOf(-1, 6, -20, 101, 53, -16, 6, -1),
        intArrayOf(-1, 6, -20, 99, 56, -17, 6, -1),
        intArrayOf(-1, 6, -20, 97, 58, -17, 6, -1),
        intArrayOf(-1, 6, -20, 95, 61, -18, 6, -1),
        intArrayOf(-2, 7, -20, 93, 64, -18, 6, -2),
        intArrayOf(-2, 7, -20, 91, 66, -19, 6, -1),
        intArrayOf(-2, 7, -20, 88, 69, -19, 6, -1),
        intArrayOf(-2, 7, -20, 86, 71, -19, 6, -1),
        intArrayOf(-2, 7, -20, 84, 74, -20, 7, -2),
        intArrayOf(-2, 7, -20, 81, 76, -20, 7, -1),
        intArrayOf(-2, 7, -20, 79, 79, -20, 7, -2),
        intArrayOf(-1, 7, -20, 76, 81, -20, 7, -2),
        intArrayOf(-2, 7, -20, 74, 84, -20, 7, -2),
        intArrayOf(-1, 6, -19, 71, 86, -20, 7, -2),
        intArrayOf(-1, 6, -19, 69, 88, -20, 7, -2),
        intArrayOf(-1, 6, -19, 66, 91, -20, 7, -2),
        intArrayOf(-2, 6, -18, 64, 93, -20, 7, -2),
        intArrayOf(-1, 6, -18, 61, 95, -20, 6, -1),
        intArrayOf(-1, 6, -17, 58, 97, -20, 6, -1),
        intArrayOf(-1, 6, -17, 56, 99, -20, 6, -1),
        intArrayOf(-1, 6, -16, 53, 101, -20, 6, -1),
        intArrayOf(-1, 5, -16, 51, 103, -19, 6, -1),
        intArrayOf(-1, 5, -15, 48, 105, -19, 6, -1),
        intArrayOf(-1, 5, -14, 45, 107, -19, 6, -1),
        intArrayOf(-1, 5, -14, 43, 109, -18, 5, -1),
        intArrayOf(-1, 5, -13, 40, 111, -18, 5, -1),
        intArrayOf(-1, 4, -12, 38, 112, -17, 5, -1),
        intArrayOf(-1, 4, -12, 35, 114, -16, 5, -1),
        intArrayOf(-1, 4, -11, 32, 116, -16, 5, -1),
        intArrayOf(-1, 4, -10, 30, 117, -15, 4, -1),
        intArrayOf(-1, 3, -9, 28, 118, -14, 4, -1),
        intArrayOf(-1, 3, -9, 25, 120, -13, 4, -1),
        intArrayOf(-1, 3, -8, 22, 121, -12, 4, -1),
        intArrayOf(-1, 3, -7, 20, 122, -11, 3, -1),
        intArrayOf(-1, 2, -6, 18, 123, -10, 3, -1),
        intArrayOf(0, 2, -6, 15, 124, -9, 3, -1),
        intArrayOf(0, 2, -5, 13, 125, -8, 2, -1),
        intArrayOf(0, 1, -4, 11, 125, -7, 2, 0),
        intArrayOf(0, 1, -3, 8, 126, -6, 2, 0),
        intArrayOf(0, 1, -3, 6, 127, -4, 1, 0),
        intArrayOf(0, 1, -2, 4, 127, -3, 1, 0),
        intArrayOf(0, 0, -1, 2, 128, -1, 0, 0)
    )

    /** Applies super-resolution when enabled by the supplied frame header.
     * 
     * The input must use the frame's coded width and height. When super-resolution is disabled,
     * this method returns the input object unchanged.
     * 
     * @param decodedPlanes the decoded planes after CDEF in the coded-width domain
     * @param frameHeader the frame header that owns the decoded planes
     * @return the input planes or a new post-super-resolution snapshot
     */
    fun apply(decodedPlanes: DecodedSurface, frameHeader: FrameHeader): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val checkedFrameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        if (!checkedFrameHeader.superResolution().enabled()) {
            return checkedDecodedPlanes
        }

        val frameSize = checkedFrameHeader.frameSize()
        require(
            !(checkedDecodedPlanes.codedWidth() != frameSize.codedWidth()
                    || checkedDecodedPlanes.codedHeight() != frameSize.height())
        ) { "Decoded plane dimensions do not match the coded frame dimensions" }

        val upscaledWidth = frameSize.upscaledWidth()
        val chromaFormat = checkedDecodedPlanes.chromaFormat()
        val bitDepth = checkedDecodedPlanes.bitDepth()
        val upscaledLumaPlane = upscalePlaneHorizontally(
            checkedDecodedPlanes.lumaPlane(),
            upscaledWidth,
            bitDepth,
            8
        )
        val chromaUPlane = checkedDecodedPlanes.chromaUPlane()
        val chromaVPlane = checkedDecodedPlanes.chromaVPlane()
        val upscaledChromaUPlane = if (chromaUPlane != null)
            upscalePlaneHorizontally(
                chromaUPlane,
                chromaWidth(chromaFormat, upscaledWidth),
                bitDepth,
                if (chromaFormat == Av1ChromaFormat.YUV444) 8 else 4
            )
        else
            null
        val upscaledChromaVPlane = if (chromaVPlane != null)
            upscalePlaneHorizontally(
                chromaVPlane,
                chromaWidth(chromaFormat, upscaledWidth),
                bitDepth,
                if (chromaFormat == Av1ChromaFormat.YUV444) 8 else 4
            )
        else
            null
        return DecodedSurface(
            bitDepth,
            chromaFormat,
            upscaledWidth,
            frameSize.height(),
            frameSize.renderWidth(),
            frameSize.renderHeight(),
            upscaledLumaPlane,
            upscaledChromaUPlane,
            upscaledChromaVPlane
        )
    }

    /** Returns the post-super-resolution chroma width for one chroma format.
     * 
     * @param chromaFormat the active decoded chroma layout
     * @param lumaWidth the post-super-resolution luma width
     * @return the post-super-resolution chroma width
     */
    private fun chromaWidth(chromaFormat: Av1ChromaFormat, lumaWidth: Int): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> (lumaWidth + 1) shr 1
            Av1ChromaFormat.YUV444 -> lumaWidth
        }
    }

    /** Upscales one decoded plane horizontally with the normative filter.
     * 
     * @param plane the decoded plane to upscale
     * @param targetWidth the post-upscale plane width
     * @param bitDepth the decoded sample bit depth
     * @param miWidth the plane-local width of one AV1 eight-luma-sample frame-grid unit
     * @return one horizontally upscaled decoded plane
     */
    private fun upscalePlaneHorizontally(
        plane: PaddedPlane,
        targetWidth: Int,
        bitDepth: Int,
        miWidth: Int
    ): PaddedPlane {
        val checkedPlane = requireNotNullCompat(plane, "plane")
        require(targetWidth > 0) { "targetWidth <= 0: $targetWidth" }
        if (targetWidth == checkedPlane.width()) {
            return checkedPlane
        }
        require(targetWidth >= checkedPlane.width()) { "targetWidth is smaller than the source width: $targetWidth < " + checkedPlane.width() }

        val upscaledSamples = ShortArray(targetWidth * checkedPlane.height())
        val sourceWidth = checkedPlane.width()
        val sourceStride = checkedPlane.stride()
        val sourceProcessingWidth = min(sourceStride, roundUp(sourceWidth, miWidth))
        val maximumSample = (1 shl bitDepth) - 1
        val step = convolveStep(sourceWidth, targetWidth)
        val firstPosition = convolveInitialPosition(sourceWidth, targetWidth, step)

        for (y in 0..<checkedPlane.height()) {
            val upscaledRowOffset = y * targetWidth
            var position = firstPosition
            for (x in 0..<targetWidth) {
                val integerPosition = position shr SCALE_SUBPEL_BITS
                val filterIndex = (position and SCALE_SUBPEL_MASK) shr SCALE_EXTRA_BITS
                val filter = FILTERS[filterIndex]
                var sum: Long = 0
                for (tap in 0..<FILTER_TAP_COUNT) {
                    val sourceX = clamp(
                        integerPosition + tap - FILTER_START_OFFSET,
                        0,
                        sourceProcessingWidth - 1
                    )
                    val sourceSample = checkedPlane.storedSample(sourceX, y)
                    sum += sourceSample.toLong() * filter[tap]
                }
                val filteredSample = roundShift(sum, FILTER_BITS)
                upscaledSamples[upscaledRowOffset + x] =
                    clamp(filteredSample, 0, maximumSample).toShort()
                position += step
            }
        }

        return PaddedPlane.Companion.fromOwnedSamples(
            targetWidth,
            checkedPlane.height(),
            targetWidth,
            upscaledSamples
        )
    }

    /** Returns the fixed-point step for one source and target width.
     * 
     * @param sourceWidth the downscaled coded-domain source width
     * @param targetWidth the post-super-resolution target width
     * @return the fixed-point horizontal step
     */
    private fun convolveStep(sourceWidth: Int, targetWidth: Int): Int {
        return (((sourceWidth.toLong() shl SCALE_SUBPEL_BITS) + (targetWidth shr 1)) / targetWidth).toInt()
    }

    /** Returns the fixed-point position for the first output sample.
     * 
     * @param sourceWidth the downscaled coded-domain source width
     * @param targetWidth the post-super-resolution target width
     * @param step the fixed-point horizontal step
     * @return the initial fixed-point horizontal position
     */
    private fun convolveInitialPosition(sourceWidth: Int, targetWidth: Int, step: Int): Int {
        val error = targetWidth.toLong() * step - (sourceWidth.toLong() shl SCALE_SUBPEL_BITS)
        val position = ((((-(targetWidth.toLong() - sourceWidth) shl (SCALE_SUBPEL_BITS - 1))
                + (targetWidth shr 1)) / targetWidth).toInt() + SCALE_EXTRA_OFFSET
                - (error / 2).toInt())
        return position and SCALE_SUBPEL_MASK
    }

    /** Rounds one positive dimension up to a power-of-two alignment.
     * 
     * @param value the positive dimension
     * @param alignment the power-of-two alignment
     * @return the aligned dimension
     */
    private fun roundUp(value: Int, alignment: Int): Int {
        return (value + alignment - 1) and -alignment
    }

    /** Applies AV1 `Round2` to one value using an arithmetic right shift.
     * 
     * @param value the value to round
     * @param bits the number of low bits to discard
     * @return the rounded value
     */
    private fun roundShift(value: Long, bits: Int): Int {
        return toIntExact((value + (1L shl (bits - 1))) shr bits)
    }

    /** Clamps one integer value to inclusive bounds.
     * 
     * @param value the value to clamp
     * @param minimum the inclusive lower bound
     * @param maximum the inclusive upper bound
     * @return the clamped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(maximum, value))
    }
}
