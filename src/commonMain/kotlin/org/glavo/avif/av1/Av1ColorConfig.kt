// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.AvifBitDepth


/** Describes the color and chroma configuration declared by an AV1 sequence header.
 * 
 * Integer color-description values use the code points defined by the AV1 bitstream
 * specification. When [#colorDescriptionPresent()] is `false`, those values contain the AV1
 * defaults parsed for the sequence rather than externally supplied metadata.
 * 
 * @param bitDepth the decoded AV1 bit depth
 * @param monochrome whether the sequence is monochrome
 * @param colorDescriptionPresent whether explicit color description fields are present
 * @param colorPrimaries the AV1 color primaries code
 * @param transferCharacteristics the AV1 transfer characteristics code
 * @param matrixCoefficients the AV1 matrix coefficients code
 * @param colorRange whether full-range color samples are used
 * @param chromaFormat the decoded chroma layout
 * @param chromaSamplePosition the AV1 chroma sample position code
 * @param chromaSubsamplingX whether chroma is subsampled horizontally
 * @param chromaSubsamplingY whether chroma is subsampled vertically
 * @param separateUvDeltaQ whether separate UV delta quantization is enabled
 */
data class Av1ColorConfig(
    val bitDepth: AvifBitDepth,
    val monochrome: Boolean,
    val colorDescriptionPresent: Boolean,
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val colorRange: Boolean,
    val chromaFormat: Av1ChromaFormat,
    val chromaSamplePosition: Int,
    val chromaSubsamplingX: Boolean,
    val chromaSubsamplingY: Boolean,
    val separateUvDeltaQ: Boolean
) {
    /** Creates an AV1 color configuration from a numeric bit depth.
     * 
     * @param bitDepth the decoded AV1 bit count
     * @param monochrome whether the sequence is monochrome
     * @param colorDescriptionPresent whether explicit color description fields are present
     * @param colorPrimaries the AV1 color primaries code
     * @param transferCharacteristics the AV1 transfer characteristics code
     * @param matrixCoefficients the AV1 matrix coefficients code
     * @param colorRange whether full-range color samples are used
     * @param chromaFormat the decoded chroma layout
     * @param chromaSamplePosition the AV1 chroma sample position code
     * @param chromaSubsamplingX whether chroma is subsampled horizontally
     * @param chromaSubsamplingY whether chroma is subsampled vertically
     * @param separateUvDeltaQ whether separate UV delta quantization is enabled
     */
    constructor(
        bitDepth: Int,
        monochrome: Boolean,
        colorDescriptionPresent: Boolean,
        colorPrimaries: Int,
        transferCharacteristics: Int,
        matrixCoefficients: Int,
        colorRange: Boolean,
        chromaFormat: Av1ChromaFormat,
        chromaSamplePosition: Int,
        chromaSubsamplingX: Boolean,
        chromaSubsamplingY: Boolean,
        separateUvDeltaQ: Boolean
    ) : this(
        AvifBitDepth.Companion.fromBits(bitDepth),
        monochrome,
        colorDescriptionPresent,
        colorPrimaries,
        transferCharacteristics,
        matrixCoefficients,
        colorRange,
        chromaFormat,
        chromaSamplePosition,
        chromaSubsamplingX,
        chromaSubsamplingY,
        separateUvDeltaQ
    )

    /** Creates an AV1 color configuration. */
    init {
        requireNotNullCompat(bitDepth, "bitDepth")
        requireNotNullCompat(chromaFormat, "chromaFormat")
        require(bitDepth != AvifBitDepth.SIXTEEN_BITS) { "AV1 does not support 16-bit coded samples" }
        checkByteCodePoint(colorPrimaries, "colorPrimaries")
        checkByteCodePoint(transferCharacteristics, "transferCharacteristics")
        checkByteCodePoint(matrixCoefficients, "matrixCoefficients")
        require(chromaSamplePosition in 0..3) { "chromaSamplePosition out of range: $chromaSamplePosition" }
        require(monochrome == (chromaFormat == Av1ChromaFormat.MONOCHROME)) { "monochrome and chromaFormat are inconsistent" }
        val expectedSubsamplingX = chromaFormat != Av1ChromaFormat.YUV444
        val expectedSubsamplingY = chromaFormat == Av1ChromaFormat.MONOCHROME
                || chromaFormat == Av1ChromaFormat.YUV420
        require(!(chromaSubsamplingX != expectedSubsamplingX || chromaSubsamplingY != expectedSubsamplingY)) { "chromaFormat and subsampling flags are inconsistent" }
    }

    companion object {
        /** Validates an AV1 eight-bit color-description code point.
         * 
         * @param value the code point
         * @param name the component name
         */
        fun checkByteCodePoint(value: Int, name: String) {
            require(value in 0..0xFF) { name + " out of range: $value" }
        }
    }
}
