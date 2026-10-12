// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable metadata for one parsed AVIF image. */
class AvifImageInfo private constructor(
    width: Int,
    height: Int,
    bitDepth: AvifBitDepth,
    chromaFormat: Av1ChromaFormat,
    sequenceInfo: AvifSequenceInfo?,
    transformInfo: AvifImageTransformInfo?,
    auxiliaryImageTypes: Array<String>,
    auxiliaryImages: Array<AvifAuxiliaryImageInfo>,
    explicitAlphaPresent: Boolean,
    alphaPremultiplied: Boolean,
    gainMapInfo: AvifGainMapInfo?,
    colorInfo: AvifColorInfo?,
    iccProfile: ByteArray?,
    exif: ByteArray?,
    xmp: ByteArray?,
    opaqueItemProperties: Array<AvifOpaqueItemProperty>
) {
    /** The display width in pixels. */
    private val width: Int

    /** The display height in pixels. */
    private val height: Int

    /** The decoded bit depth. */
    private val bitDepth: AvifBitDepth

    /** The AV1 chroma sampling layout. */
    private val chromaFormat: Av1ChromaFormat

    /** Whether an alpha auxiliary image is present. */
    private val alphaPresent: Boolean

    /** Whether alpha presence was declared independently of auxiliary image metadata. */
    private val explicitAlphaPresent: Boolean

    /** Whether color samples are premultiplied by the alpha auxiliary image. */
    private val alphaPremultiplied: Boolean

    /** The typed sequence descriptor for animated inputs, or `null` for still images. */
    private val sequenceInfo: AvifSequenceInfo?

    /** The typed image-transform descriptor, or `null` when no image transform is present. */
    private val transformInfo: AvifImageTransformInfo?

    /** Auxiliary image type strings associated with the primary image. */
    private val auxiliaryImageTypes: Array<String>

    /** Auxiliary image descriptors associated with the primary image. */
    private val auxiliaryImages: Array<AvifAuxiliaryImageInfo>

    /** The gain-map descriptor associated with the primary image, or `null`. */
    private val gainMapInfo: AvifGainMapInfo?

    /** The parsed color information, or `null`. */
    private val colorInfo: AvifColorInfo?

    /** The embedded ICC profile payload, or `null`. */
    private val iccProfile: ByteArray?

    /** The embedded Exif metadata payload, or `null`. */
    private val exif: ByteArray?

    /** The embedded XMP metadata payload, or `null`. */
    private val xmp: ByteArray?

    /** Opaque item properties associated with the primary image item. */
    private val opaqueItemProperties: Array<AvifOpaqueItemProperty>

    /** Creates image metadata without optional container metadata.
     * 
     * @param width the display width in pixels
     * @param height the display height in pixels
     * @param bitDepth the decoded bit depth
     * @param chromaFormat the AV1 chroma sampling layout
     */
    constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat
    ) : this(
        width,
        height,
        bitDepth,
        chromaFormat,
        null,
        null,
        arrayOfNullsCompat<String>(0),
        arrayOfNullsCompat<AvifAuxiliaryImageInfo>(0),
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        arrayOfNullsCompat<AvifOpaqueItemProperty>(0)
    )

    init {
        require(width > 0) { "width <= 0: $width" }
        require(height > 0) { "height <= 0: $height" }
        this.width = width
        this.height = height
        this.bitDepth = requireNotNullCompat(bitDepth, "bitDepth")
        this.chromaFormat = requireNotNullCompat(chromaFormat, "chromaFormat")
        this.sequenceInfo = sequenceInfo
        this.transformInfo = transformInfo
        this.auxiliaryImageTypes = auxiliaryImageTypes
        this.auxiliaryImages = auxiliaryImages
        this.explicitAlphaPresent = explicitAlphaPresent
        this.alphaPresent = explicitAlphaPresent
                || containsAuxiliaryImageType(
            auxiliaryImageTypes,
            AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE
        )
                || containsAlphaAuxiliaryImage(auxiliaryImages)
        this.alphaPremultiplied = this.alphaPresent && alphaPremultiplied
        this.gainMapInfo = gainMapInfo
        this.colorInfo = colorInfo
        this.iccProfile = iccProfile
        this.exif = exif
        this.xmp = xmp
        this.opaqueItemProperties = opaqueItemProperties
    }

    /** Returns metadata with the requested sequence descriptor.
     * 
     * @param sequenceInfo the sequence descriptor, or `null` for a still image
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withSequenceInfo(sequenceInfo: AvifSequenceInfo?): AvifImageInfo {
        return if (this.sequenceInfo == sequenceInfo) this else copy(
            sequenceInfo,
            transformInfo,
            auxiliaryImageTypes,
            auxiliaryImages,
            explicitAlphaPresent,
            alphaPremultiplied,
            gainMapInfo,
            colorInfo,
            iccProfile,
            exif,
            xmp,
            opaqueItemProperties
        )
    }

    /** Returns metadata with the requested image-transform descriptor.
     * 
     * @param transformInfo the image-transform descriptor, or `null` when absent
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withTransformInfo(transformInfo: AvifImageTransformInfo?): AvifImageInfo {
        return if (this.transformInfo == transformInfo) this else copy(
            sequenceInfo,
            transformInfo,
            auxiliaryImageTypes,
            auxiliaryImages,
            explicitAlphaPresent,
            alphaPremultiplied,
            gainMapInfo,
            colorInfo,
            iccProfile,
            exif,
            xmp,
            opaqueItemProperties
        )
    }

    /** Returns metadata with the requested auxiliary image information.
     * 
     * When `auxiliaryImageTypes` is `null`, the type strings are derived from `auxiliaryImages`.
     * Both arrays are copied, and `null` arrays are treated as empty.
     * 
     * @param auxiliaryImageTypes the auxiliary image type strings, or `null` to derive them
     * @param auxiliaryImages the auxiliary image descriptors, or `null` when absent
     * @return a new metadata value
     */
    fun withAuxiliaryImages(
        auxiliaryImageTypes: Array<String>?,
        auxiliaryImages: Array<AvifAuxiliaryImageInfo>?
    ): AvifImageInfo {
        val checkedImages: Array<AvifAuxiliaryImageInfo> =
            immutableAuxiliaryImages(auxiliaryImages)
        val checkedTypes: Array<String> = if (auxiliaryImageTypes == null)
            auxiliaryImageTypesFromDescriptors(checkedImages)
        else
            immutableAuxiliaryImageTypes(auxiliaryImageTypes)
        return copy(
            sequenceInfo, transformInfo, checkedTypes, checkedImages, explicitAlphaPresent,
            alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif, xmp, opaqueItemProperties
        )
    }

    /** Returns metadata with explicitly declared alpha semantics.
     * 
     * Auxiliary alpha metadata continues to imply alpha presence even when `alphaPresent` is `false`.
     * Premultiplication is disabled when the resulting metadata has no alpha channel.
     * 
     * @param alphaPresent whether alpha is present independently of auxiliary metadata
     * @param alphaPremultiplied whether source color samples are premultiplied by alpha
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withAlpha(alphaPresent: Boolean, alphaPremultiplied: Boolean): AvifImageInfo {
        val normalizedPremultiplied =
            (alphaPresent || containsAlphaMetadata()) && alphaPremultiplied
        if (explicitAlphaPresent == alphaPresent && this.alphaPremultiplied == normalizedPremultiplied) {
            return this
        }
        return copy(
            sequenceInfo,
            transformInfo,
            auxiliaryImageTypes,
            auxiliaryImages,
            alphaPresent,
            normalizedPremultiplied,
            gainMapInfo,
            colorInfo,
            iccProfile,
            exif,
            xmp,
            opaqueItemProperties
        )
    }

    /** Returns metadata with the requested gain-map descriptor.
     * 
     * @param gainMapInfo the gain-map descriptor, or `null` when absent
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withGainMapInfo(gainMapInfo: AvifGainMapInfo?): AvifImageInfo {
        return if (this.gainMapInfo == gainMapInfo) this else copy(
            sequenceInfo,
            transformInfo,
            auxiliaryImageTypes,
            auxiliaryImages,
            explicitAlphaPresent,
            alphaPremultiplied,
            gainMapInfo,
            colorInfo,
            iccProfile,
            exif,
            xmp,
            opaqueItemProperties
        )
    }

    /** Returns metadata with the requested color descriptor.
     * 
     * @param colorInfo the color descriptor, or `null` when absent
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withColorInfo(colorInfo: AvifColorInfo?): AvifImageInfo {
        return if (this.colorInfo == colorInfo) this else copy(
            sequenceInfo,
            transformInfo,
            auxiliaryImageTypes,
            auxiliaryImages,
            explicitAlphaPresent,
            alphaPremultiplied,
            gainMapInfo,
            colorInfo,
            iccProfile,
            exif,
            xmp,
            opaqueItemProperties
        )
    }

    /** Returns metadata with an embedded ICC profile.
     * 
     * @param iccProfile the profile payload, or `null` to remove it; the array is copied
     * @return a new metadata value
     */
    fun withIccProfile(iccProfile: ByteArray?): AvifImageInfo {
        return copy(
            sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
            alphaPremultiplied, gainMapInfo, colorInfo, immutableBytes(iccProfile), exif, xmp,
            opaqueItemProperties
        )
    }

    /** Returns metadata with embedded Exif data.
     * 
     * @param exif the payload excluding the AVIF Exif header offset field, or `null` to remove it; the array is copied
     * @return a new metadata value
     */
    fun withExif(exif: ByteArray?): AvifImageInfo {
        return copy(
            sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
            alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, immutableBytes(exif), xmp,
            opaqueItemProperties
        )
    }

    /** Returns metadata with embedded XMP data.
     * 
     * @param xmp the XMP payload, or `null` to remove it; the array is copied
     * @return a new metadata value
     */
    fun withXmp(xmp: ByteArray?): AvifImageInfo {
        return copy(
            sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
            alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif, immutableBytes(xmp),
            opaqueItemProperties
        )
    }

    /** Returns metadata with opaque primary-item properties.
     * 
     * @param opaqueItemProperties the property descriptors, or `null` when absent; the array is copied
     * @return a new metadata value
     */
    fun withOpaqueItemProperties(opaqueItemProperties: Array<AvifOpaqueItemProperty>?): AvifImageInfo {
        return copy(
            sequenceInfo, transformInfo, auxiliaryImageTypes, auxiliaryImages, explicitAlphaPresent,
            alphaPremultiplied, gainMapInfo, colorInfo, iccProfile, exif, xmp,
            immutableOpaqueItemProperties(opaqueItemProperties)
        )
    }

    /** Returns the display width in pixels.
     * 
     * @return the display width in pixels
     */
    fun width(): Int {
        return width
    }

    /** Returns the display height in pixels.
     * 
     * @return the display height in pixels
     */
    fun height(): Int {
        return height
    }

    /** Returns the decoded bit depth.
     * 
     * @return the decoded bit depth
     */
    fun bitDepth(): AvifBitDepth {
        return bitDepth
    }

    /** Returns the AV1 chroma sampling layout.
     * 
     * @return the AV1 chroma sampling layout
     */
    fun chromaFormat(): Av1ChromaFormat {
        return chromaFormat
    }

    /** Returns whether an alpha auxiliary image is present.
     * 
     * @return whether an alpha auxiliary image is present
     */
    fun alphaPresent(): Boolean {
        return alphaPresent
    }

    /** Returns whether color samples are premultiplied by the alpha auxiliary image.
     * 
     * Decoded [AvifFrame] pixels are still exposed as non-premultiplied ARGB. This flag reports the
     * source container semantics before conversion.
     * 
     * @return whether the source color samples are premultiplied by alpha
     */
    fun alphaPremultiplied(): Boolean {
        return alphaPremultiplied
    }

    /** Returns whether the input is an animated image sequence.
     * 
     * @return whether the input is an animated image sequence
     */
    fun animated(): Boolean {
        return sequenceInfo != null
    }

    /** Returns the number of frames advertised by the container.
     * 
     * @return the number of frames advertised by the container
     */
    fun frameCount(): Int {
        return if (sequenceInfo == null) 1 else sequenceInfo.frameCount()
    }

    /** Returns the typed animated-sequence descriptor.
     * 
     * Still images return `null`.
     * 
     * @return the sequence descriptor, or `null`
     */
    fun sequenceInfo(): AvifSequenceInfo? {
        return sequenceInfo
    }

    /** Returns the typed AVIF image-transform descriptor.
     * 
     * Inputs without `clap`, `irot`, or `imir` item properties return `null`.
     * 
     * @return the image-transform descriptor, or `null`
     */
    fun transformInfo(): AvifImageTransformInfo? {
        return transformInfo
    }

    /** Returns auxiliary image type strings associated with the primary image.
     * 
     * The returned array includes alpha auxiliary image types when present.
     * 
     * @return auxiliary image type strings associated with the primary image
     */
    fun auxiliaryImageTypes(): Array<String> {
        return auxiliaryImageTypes.copyOf()
    }

    /** Returns auxiliary image descriptors associated with the primary image.
     * 
     * The returned array includes alpha auxiliary images when present.
     * 
     * @return auxiliary image descriptors associated with the primary image
     */
    fun auxiliaryImages(): Array<AvifAuxiliaryImageInfo> {
        return auxiliaryImages.copyOf()
    }

    /** Returns the gain-map descriptor associated with the primary image.
     * 
     * The returned descriptor is present only when the file advertises the `tmap` brand and the
     * `tmap` item is the preferred alternative to the primary image item. The descriptor does not
     * imply that gain-map pixels have been tone-mapped or applied to the base image.
     * 
     * @return the gain-map descriptor, or `null`
     */
    fun gainMapInfo(): AvifGainMapInfo? {
        return gainMapInfo
    }

    /** Returns the parsed color information.
     * 
     * @return the parsed color information, or `null`
     */
    fun colorInfo(): AvifColorInfo? {
        return colorInfo
    }

    /** Returns the embedded ICC profile payload.
     * 
     * @return a read-only view of the embedded ICC profile payload, or `null`
     */
    fun iccProfile(): ByteArray? {
        return iccProfile?.copyOf()
    }

    /** Returns the embedded Exif metadata payload.
     * 
     * The returned payload excludes the AVIF `exif_tiff_header_offset` field
     * and matches the Exif byte sequence exposed by libavif.
     * 
     * @return a read-only view of the embedded Exif metadata payload, or `null`
     */
    fun exif(): ByteArray? {
        return exif?.copyOf()
    }

    /** Returns the embedded XMP metadata payload.
     * 
     * @return a read-only view of the embedded XMP metadata payload, or `null`
     */
    fun xmp(): ByteArray? {
        return xmp?.copyOf()
    }

    /** Returns opaque item properties associated with the primary image item.
     * 
     * The reader stores properties whose box type is not interpreted as a typed AVIF feature. The
     * returned array is empty when no such properties are associated with the primary image item.
     * 
     * @return opaque item properties associated with the primary image item
     */
    fun opaqueItemProperties(): Array<AvifOpaqueItemProperty> {
        return opaqueItemProperties.copyOf()
    }

    /** Creates a copy with normalized immutable component storage.
     * 
     * @param sequenceInfo the sequence descriptor, or `null`
     * @param transformInfo the image-transform descriptor, or `null`
     * @param auxiliaryImageTypes immutable auxiliary image type storage
     * @param auxiliaryImages immutable auxiliary image descriptor storage
     * @param explicitAlphaPresent whether alpha presence was declared independently of auxiliary metadata
     * @param alphaPremultiplied whether source color samples are premultiplied by alpha
     * @param gainMapInfo the gain-map descriptor, or `null`
     * @param colorInfo the color descriptor, or `null`
     * @param iccProfile immutable ICC profile storage, or `null`
     * @param exif immutable Exif storage, or `null`
     * @param xmp immutable XMP storage, or `null`
     * @param opaqueItemProperties immutable opaque-property storage
     * @return the copied metadata
     */
    private fun copy(
        sequenceInfo: AvifSequenceInfo?,
        transformInfo: AvifImageTransformInfo?,
        auxiliaryImageTypes: Array<String>,
        auxiliaryImages: Array<AvifAuxiliaryImageInfo>,
        explicitAlphaPresent: Boolean,
        alphaPremultiplied: Boolean,
        gainMapInfo: AvifGainMapInfo?,
        colorInfo: AvifColorInfo?,
        iccProfile: ByteArray?,
        exif: ByteArray?,
        xmp: ByteArray?,
        opaqueItemProperties: Array<AvifOpaqueItemProperty>
    ): AvifImageInfo {
        return AvifImageInfo(
            width,
            height,
            bitDepth,
            chromaFormat,
            sequenceInfo,
            transformInfo,
            auxiliaryImageTypes,
            auxiliaryImages,
            explicitAlphaPresent,
            alphaPremultiplied,
            gainMapInfo,
            colorInfo,
            iccProfile,
            exif,
            xmp,
            opaqueItemProperties
        )
    }

    /** Returns whether the current auxiliary metadata identifies an alpha image.
     * 
     * @return whether auxiliary alpha metadata is present
     */
    private fun containsAlphaMetadata(): Boolean {
        return containsAuxiliaryImageType(
            auxiliaryImageTypes,
            AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE
        )
                || containsAlphaAuxiliaryImage(auxiliaryImages)
    }

    companion object {
        /** Creates immutable byte-buffer storage for one optional payload.
         * 
         * @param bytes the source bytes, or `null`
         * @return immutable byte-buffer storage, or `null`
         */
        fun immutableBytes(bytes: ByteArray?): ByteArray? {
            if (bytes == null) {
                return null
            }
            return bytes.copyOf()
        }

        /** Creates immutable storage for auxiliary image type strings.
         * 
         * @param auxiliaryImageTypes the source auxiliary image type strings, or `null`
         * @return immutable auxiliary image type storage
         */
        fun immutableAuxiliaryImageTypes(auxiliaryImageTypes: Array<String>?): Array<String> {
            if (auxiliaryImageTypes == null || auxiliaryImageTypes.size == 0) {
                return arrayOfNullsCompat<String>(0)
            }
            val result = auxiliaryImageTypes.copyOf()
            for (auxiliaryImageType in result) {
                requireNotNullCompat(auxiliaryImageType, "auxiliaryImageTypes element")
            }
            return result
        }

        /** Creates immutable storage for auxiliary image descriptors.
         * 
         * @param auxiliaryImages the source auxiliary image descriptors, or `null`
         * @return immutable auxiliary image descriptor storage
         */
        fun immutableAuxiliaryImages(
            auxiliaryImages: Array<AvifAuxiliaryImageInfo>?
        ): Array<AvifAuxiliaryImageInfo> {
            if (auxiliaryImages == null || auxiliaryImages.size == 0) {
                return arrayOfNullsCompat<AvifAuxiliaryImageInfo>(0)
            }
            val result = auxiliaryImages.copyOf()
            for (auxiliaryImage in result) {
                requireNotNullCompat(
                    auxiliaryImage,
                    "auxiliaryImages element"
                )
            }
            return result
        }

        /** Creates immutable storage for opaque item property descriptors.
         * 
         * @param opaqueItemProperties the source opaque item property descriptors, or `null`
         * @return immutable item property descriptor storage
         */
        fun immutableOpaqueItemProperties(
            opaqueItemProperties: Array<AvifOpaqueItemProperty>?
        ): Array<AvifOpaqueItemProperty> {
            if (opaqueItemProperties == null || opaqueItemProperties.size == 0) {
                return arrayOfNullsCompat<AvifOpaqueItemProperty>(0)
            }
            val result = opaqueItemProperties.copyOf()
            for (opaqueItemProperty in result) {
                requireNotNullCompat(
                    opaqueItemProperty,
                    "opaqueItemProperties element"
                )
            }
            return result
        }

        /** Creates auxiliary image type storage from auxiliary image descriptors.
         * 
         * @param auxiliaryImages the auxiliary image descriptors
         * @return auxiliary image type storage
         */
        fun auxiliaryImageTypesFromDescriptors(
            auxiliaryImages: Array<AvifAuxiliaryImageInfo>
        ): Array<String> {
            if (auxiliaryImages.size == 0) {
                return arrayOfNullsCompat<String>(0)
            }
            val result: Array<String> = arrayOfNullsCompat<String>(auxiliaryImages.size)
            var size = 0
            for (auxiliaryImage in auxiliaryImages) {
                val type = auxiliaryImage.auxiliaryType()
                var seen = false
                for (i in 0..<size) {
                    if (result[i] == type) {
                        seen = true
                        break
                    }
                }
                if (!seen) {
                    result[size++] = type
                }
            }
            return copyOfCompat(result, size)
        }

        /** Returns whether an auxiliary image type list contains the requested type.
         * 
         * @param auxiliaryImageTypes the auxiliary image type strings
         * @param expectedType the type to find
         * @return whether the requested type is present
         */
        fun containsAuxiliaryImageType(
            auxiliaryImageTypes: Array<String>,
            expectedType: String
        ): Boolean {
            for (auxiliaryImageType in auxiliaryImageTypes) {
                if (expectedType == auxiliaryImageType) {
                    return true
                }
            }
            return false
        }

        /** Returns whether any auxiliary image descriptor represents alpha samples.
         * 
         * @param auxiliaryImages the auxiliary image descriptors
         * @return whether an alpha auxiliary image is present
         */
        fun containsAlphaAuxiliaryImage(
            auxiliaryImages: Array<AvifAuxiliaryImageInfo>
        ): Boolean {
            for (auxiliaryImage in auxiliaryImages) {
                if (auxiliaryImage.isAlpha) {
                    return true
                }
            }
            return false
        }

        /** Returns a read-only view over immutable payload storage.
         * 
         * @param bytes the immutable payload storage, or `null`
         * @return a read-only view, or `null`
         */
        fun byteView(bytes: ByteArray?): ByteArray? {
            return bytes?.copyOf()
        }
    }
}
