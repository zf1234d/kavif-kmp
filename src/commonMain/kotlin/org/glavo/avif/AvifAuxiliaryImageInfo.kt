// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable metadata for one auxiliary image associated with an AVIF primary image. */
class AvifAuxiliaryImageInfo(
    itemId: Int,
    auxiliaryType: String,
    itemType: String,
    width: Int,
    height: Int,
    bitDepth: AvifBitDepth?,
    chromaFormat: Av1ChromaFormat?
) {
    /** The BMFF item id for the auxiliary image. */
    private val itemId: Int

    /** The `auxC` auxiliary image type string. */
    private val auxiliaryType: String

    /** The BMFF item type for the auxiliary item. */
    private val itemType: String

    /** The auxiliary image width in pixels, or -1 when unknown. */
    private val width: Int

    /** The auxiliary image height in pixels, or -1 when unknown. */
    private val height: Int

    /** The decoded AV1 bit depth, or `null` when the auxiliary item is not an AV1 image. */
    private val bitDepth: AvifBitDepth?

    /** The AV1 chroma sampling layout, or `null` when the auxiliary item is not an AV1 image. */
    private val chromaFormat: Av1ChromaFormat?

    init {
        require(itemId > 0) { "itemId <= 0: $itemId" }
        require(
            !(!isKnownSize(width, height) && !isUnknownSize(
                width,
                height
            ))
        ) { "width and height must both be positive or both be -1" }
        require((bitDepth == null) == (chromaFormat == null)) { "bitDepth and chromaFormat must both be present or both be null" }

        this.itemId = itemId
        this.auxiliaryType = requireNotNullCompat(auxiliaryType, "auxiliaryType")
        this.itemType = requireNotNullCompat(itemType, "itemType")
        this.width = width
        this.height = height
        this.bitDepth = bitDepth
        this.chromaFormat = chromaFormat
    }

    /** Returns the BMFF item id for the auxiliary image.
     * 
     * @return the BMFF item id
     */
    fun itemId(): Int {
        return itemId
    }

    /** Returns the `auxC` auxiliary image type string.
     * 
     * @return the auxiliary image type string
     */
    fun auxiliaryType(): String {
        return auxiliaryType
    }

    /** Returns the BMFF item type for the auxiliary item.
     * 
     * @return the BMFF item type
     */
    fun itemType(): String {
        return itemType
    }

    /** Returns the auxiliary image width in pixels.
     * 
     * A value of -1 means the width is unknown.
     * 
     * @return the auxiliary image width in pixels, or -1 when unknown
     */
    fun width(): Int {
        return width
    }

    /** Returns the auxiliary image height in pixels.
     * 
     * A value of -1 means the height is unknown.
     * 
     * @return the auxiliary image height in pixels, or -1 when unknown
     */
    fun height(): Int {
        return height
    }

    /** Returns the decoded AV1 bit depth.
     * 
     * A `null` value means the auxiliary item is not an AV1 image or does not expose `av1C`.
     * 
     * @return the decoded AV1 bit depth, or `null`
     */
    fun bitDepth(): AvifBitDepth? {
        return bitDepth
    }

    /** Returns the AV1 chroma sampling layout.
     * 
     * A `null` value means the auxiliary item is not an AV1 image or does not expose `av1C`.
     * 
     * @return the AV1 chroma sampling layout, or `null`
     */
    fun chromaFormat(): Av1ChromaFormat? {
        return chromaFormat
    }

    val isAlpha: Boolean
        /** Returns whether this auxiliary image is an alpha image.
         * 
         * @return whether this auxiliary image is an alpha image
         */
        get() = ALPHA_TYPE == auxiliaryType

    val isDepth: Boolean
        /** Returns whether this auxiliary image is a depth image.
         * 
         * @return whether this auxiliary image is a depth image
         */
        get() = DEPTH_TYPE == auxiliaryType

    companion object {
        /** The AVIF auxiliary image type string used for alpha images. */
        const val ALPHA_TYPE: String = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"

        /** The AVIF auxiliary image type string used for depth images. */
        const val DEPTH_TYPE: String = "urn:mpeg:mpegB:cicp:systems:auxiliary:depth"

        /** Returns whether the supplied dimensions represent a known image size.
         * 
         * @param width the width in pixels
         * @param height the height in pixels
         * @return whether both dimensions are positive
         */
        fun isKnownSize(width: Int, height: Int): Boolean {
            return width > 0 && height > 0
        }

        /** Returns whether the supplied dimensions represent an unknown image size.
         * 
         * @param width the width in pixels
         * @param height the height in pixels
         * @return whether both dimensions are -1
         */
        fun isUnknownSize(width: Int, height: Int): Boolean {
            return width == -1 && height == -1
        }
    }
}
