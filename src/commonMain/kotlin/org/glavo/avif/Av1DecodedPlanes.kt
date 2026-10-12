// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable decoded AV1 image planes.
 * 
 * The render dimensions retain the AV1 display hint; they do not crop or resample the stored
 * planes. AVIF readers expose these planes before auxiliary alpha composition and item transforms
 * such as `clap`, `irot`, and `imir` are applied. */
class Av1DecodedPlanes(
    bitDepth: AvifBitDepth,
    chromaFormat: Av1ChromaFormat,
    codedWidth: Int,
    codedHeight: Int,
    renderWidth: Int,
    renderHeight: Int,
    lumaPlane: Av1DecodedPlane,
    chromaUPlane: Av1DecodedPlane?,
    chromaVPlane: Av1DecodedPlane?
) {
    /** The decoded sample bit depth. */
    private val bitDepth: AvifBitDepth

    /** The decoded AV1 chroma sampling layout. */
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
    private val lumaPlane: Av1DecodedPlane

    /** The decoded chroma U plane, or `null` for monochrome images. */
    private val chromaUPlane: Av1DecodedPlane?

    /** The decoded chroma V plane, or `null` for monochrome images. */
    private val chromaVPlane: Av1DecodedPlane?

    init {
        require(codedWidth > 0) { "codedWidth <= 0: $codedWidth" }
        require(codedHeight > 0) { "codedHeight <= 0: $codedHeight" }
        require(renderWidth > 0) { "renderWidth <= 0: $renderWidth" }
        require(renderHeight > 0) { "renderHeight <= 0: $renderHeight" }

        this.bitDepth = requireNotNullCompat(bitDepth, "bitDepth")
        this.chromaFormat = requireNotNullCompat(chromaFormat, "chromaFormat")
        this.codedWidth = codedWidth
        this.codedHeight = codedHeight
        this.renderWidth = renderWidth
        this.renderHeight = renderHeight
        this.lumaPlane = requireNotNullCompat(lumaPlane, "lumaPlane")
        this.chromaUPlane = chromaUPlane
        this.chromaVPlane = chromaVPlane
        validatePlanes()
    }

    /** Returns the decoded sample bit depth.
     * 
     * @return the decoded sample bit depth
     */
    fun bitDepth(): AvifBitDepth {
        return bitDepth
    }

    /** Returns the decoded AV1 chroma sampling layout.
     * 
     * @return the decoded AV1 chroma sampling layout
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
    fun lumaPlane(): Av1DecodedPlane {
        return lumaPlane
    }

    /** Returns the decoded chroma U plane.
     * 
     * @return the decoded chroma U plane, or `null` for monochrome images
     */
    fun chromaUPlane(): Av1DecodedPlane? {
        return chromaUPlane
    }

    /** Returns the decoded chroma V plane.
     * 
     * @return the decoded chroma V plane, or `null` for monochrome images
     */
    fun chromaVPlane(): Av1DecodedPlane? {
        return chromaVPlane
    }

    /** Returns whether chroma planes are present.
     * 
     * @return whether chroma planes are present
     */
    fun hasChroma(): Boolean {
        return chromaUPlane != null && chromaVPlane != null
    }

    /** Validates the plane layout against the chroma format. */
    private fun validatePlanes() {
        require(!(lumaPlane.width() != codedWidth || lumaPlane.height() != codedHeight)) { "lumaPlane dimensions do not match coded luma dimensions" }
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
        /** Returns the expected chroma width for one chroma format.
         * 
         * @param chromaFormat the decoded AV1 chroma sampling layout
         * @param codedWidth the coded luma width in samples
         * @return the expected chroma width
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
         * @param chromaFormat the decoded AV1 chroma sampling layout
         * @param codedHeight the coded luma height in samples
         * @return the expected chroma height
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
