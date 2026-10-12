// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.image

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.Av1DecodedPlane
import org.glavo.avif.Av1DecodedPlanes
import org.glavo.avif.AvifBitDepth


/** Immutable decoded-plane snapshot produced by AV1 decoding.
 * 
 * Stored planes contain the postprocessed presentation samples. The render dimensions are AV1
 * display hints and do not crop or resample the stored planes. */
class DecodedSurface(
    bitDepth: Int,
    chromaFormat: Av1ChromaFormat,
    codedWidth: Int,
    codedHeight: Int,
    renderWidth: Int,
    renderHeight: Int,
    lumaPlane: PaddedPlane,
    chromaUPlane: PaddedPlane?,
    chromaVPlane: PaddedPlane?
) {
    /** The decoded bit depth. */
    private val bitDepth: Int

    /** The chroma layout of the decoded planes. */
    private val chromaFormat: Av1ChromaFormat

    /** The stored luma width in samples. */
    private val codedWidth: Int

    /** The stored luma height in samples. */
    private val codedHeight: Int

    /** The presentation render width. */
    private val renderWidth: Int

    /** The presentation render height. */
    private val renderHeight: Int

    /** The decoded luma plane. */
    private val lumaPlane: PaddedPlane

    /** The decoded chroma U plane, or `null` for monochrome output. */
    private val chromaUPlane: PaddedPlane?

    /** The decoded chroma V plane, or `null` for monochrome output. */
    private val chromaVPlane: PaddedPlane?

    init {
        require(!(bitDepth != 8 && bitDepth != 10 && bitDepth != 12 && bitDepth != 16)) { "Unsupported bitDepth: $bitDepth" }
        require(codedWidth > 0) { "codedWidth <= 0: $codedWidth" }
        require(codedHeight > 0) { "codedHeight <= 0: $codedHeight" }
        require(renderWidth > 0) { "renderWidth <= 0: $renderWidth" }
        require(renderHeight > 0) { "renderHeight <= 0: $renderHeight" }

        this.bitDepth = bitDepth
        this.chromaFormat = requireNotNullCompat(chromaFormat, "chromaFormat")
        this.codedWidth = codedWidth
        this.codedHeight = codedHeight
        this.renderWidth = renderWidth
        this.renderHeight = renderHeight
        this.lumaPlane = requireNotNullCompat(lumaPlane, "lumaPlane")
        this.chromaUPlane = chromaUPlane
        this.chromaVPlane = chromaVPlane

        require(!(lumaPlane.width() != codedWidth || lumaPlane.height() != codedHeight)) { "lumaPlane dimensions do not match coded luma dimensions" }

        validateChromaPlanes()
    }

    /** Returns the decoded bit depth.
     * 
     * @return the decoded bit depth
     */
    fun bitDepth(): Int {
        return bitDepth
    }

    /** Returns the chroma layout of the decoded planes.
     * 
     * @return the chroma layout of the decoded planes
     */
    fun chromaFormat(): Av1ChromaFormat {
        return chromaFormat
    }

    /** Returns the stored luma width in samples.
     * 
     * @return the stored luma width in samples
     */
    fun codedWidth(): Int {
        return codedWidth
    }

    /** Returns the stored luma height in samples.
     * 
     * @return the stored luma height in samples
     */
    fun codedHeight(): Int {
        return codedHeight
    }

    /** Returns the presentation render width.
     * 
     * @return the presentation render width
     */
    fun renderWidth(): Int {
        return renderWidth
    }

    /** Returns the presentation render height.
     * 
     * @return the presentation render height
     */
    fun renderHeight(): Int {
        return renderHeight
    }

    /** Returns the decoded luma plane.
     * 
     * @return the decoded luma plane
     */
    fun lumaPlane(): PaddedPlane {
        return lumaPlane
    }

    /** Returns the decoded chroma U plane, or `null` for monochrome output.
     * 
     * @return the decoded chroma U plane, or `null` for monochrome output
     */
    fun chromaUPlane(): PaddedPlane? {
        return chromaUPlane
    }

    /** Returns the decoded chroma V plane, or `null` for monochrome output.
     * 
     * @return the decoded chroma V plane, or `null` for monochrome output
     */
    fun chromaVPlane(): PaddedPlane? {
        return chromaVPlane
    }

    /** Returns whether this decoded snapshot contains chroma planes.
     * 
     * @return whether this decoded snapshot contains chroma planes
     */
    fun hasChroma(): Boolean {
        return chromaUPlane != null && chromaVPlane != null
    }

    /** Creates the public visible-plane view of this internal padded surface.
     * 
     * The returned plane buffers share immutable sample storage with this surface but exclude
     * internal rows below the visible plane height.
     * 
     * @return the public decoded planes
     */
    fun toDecodedPlanes(): Av1DecodedPlanes {
        return Av1DecodedPlanes(
            AvifBitDepth.Companion.fromBits(bitDepth),
            chromaFormat,
            codedWidth,
            codedHeight,
            renderWidth,
            renderHeight,
            toDecodedPlane(lumaPlane),
            toNullableDecodedPlane(chromaUPlane),
            toNullableDecodedPlane(chromaVPlane)
        )
    }

    /** Validates the stored chroma-plane arrangement against the chroma format. */
    private fun validateChromaPlanes() {
        if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
            require(!(chromaUPlane != null || chromaVPlane != null)) { "MONOCHROME output must not carry chroma planes" }
            return
        }

        require(!(chromaUPlane == null || chromaVPlane == null)) { "Chroma planes are required for $chromaFormat" }

        val expectedWidth: Int = expectedChromaWidth(chromaFormat, codedWidth)
        val expectedHeight: Int = expectedChromaHeight(chromaFormat, codedHeight)
        require(!(chromaUPlane.width() != expectedWidth || chromaUPlane.height() != expectedHeight)) { "chromaUPlane dimensions do not match chroma format" }
        require(!(chromaVPlane.width() != expectedWidth || chromaVPlane.height() != expectedHeight)) { "chromaVPlane dimensions do not match chroma format" }
    }

    companion object {
        /** Creates a public visible plane over one internal padded plane.
         * 
         * @param plane the internal padded plane
         * @return the public visible plane
         */
        fun toDecodedPlane(plane: PaddedPlane): Av1DecodedPlane {
            return Av1DecodedPlane(
                plane.width(),
                plane.height(),
                plane.stride(),
                plane.sampleBuffer()
            )
        }

        /** Creates a public visible plane over one optional internal padded plane.
         * 
         * @param plane the internal padded plane, or `null`
         * @return the public visible plane, or `null`
         */
        fun toNullableDecodedPlane(plane: PaddedPlane?): Av1DecodedPlane? {
            return if (plane == null) null else toDecodedPlane(plane)
        }

        /** Returns the expected chroma width for one chroma format.
         * 
         * @param chromaFormat the chroma layout of the decoded planes
         * @param codedWidth the coded luma width in samples
         * @return the expected chroma width for one chroma format
         */
        fun expectedChromaWidth(chromaFormat: Av1ChromaFormat, codedWidth: Int): Int {
            return when (chromaFormat) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> (codedWidth + 1) / 2
                Av1ChromaFormat.YUV444 -> codedWidth
            }
        }

        /** Returns the expected chroma height for one chroma format.
         * 
         * @param chromaFormat the chroma layout of the decoded planes
         * @param codedHeight the coded luma height in samples
         * @return the expected chroma height for one chroma format
         */
        fun expectedChromaHeight(chromaFormat: Av1ChromaFormat, codedHeight: Int): Int {
            return when (chromaFormat) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420 -> (codedHeight + 1) / 2
                Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> codedHeight
            }
        }
    }
}
