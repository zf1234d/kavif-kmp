// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable metadata for one AVIF tone-mapped gain map association.
 * 
 * The descriptor exposes container-level `tmap` and gain-map image item metadata. Gain-map
 * pixels can be decoded separately through `AvifImageReader`; this descriptor does not imply that
 * those pixels have been tone-mapped or applied to the base image. */
class AvifGainMapInfo private constructor(
    toneMappedImageItemId: Int,
    baseImageItemId: Int,
    gainMapImageItemId: Int,
    toneMappedItemType: String,
    gainMapItemType: String,
    toneMappedWidth: Int,
    toneMappedHeight: Int,
    gainMapWidth: Int,
    gainMapHeight: Int,
    gainMapBitDepth: AvifBitDepth?,
    gainMapChromaFormat: Av1ChromaFormat?,
    toneMappedColorInfo: AvifColorInfo?,
    toneMappedIccProfile: ByteArray?,
    gainMapColorInfo: AvifColorInfo?,
    metadataVersion: Int,
    metadataMinimumVersion: Int,
    metadataWriterVersion: Int,
    metadata: AvifGainMapMetadata?
) {
    /** The `tmap` derived image item id. */
    private val toneMappedImageItemId: Int

    /** The base image item id referenced by the `tmap` item. */
    private val baseImageItemId: Int

    /** The gain-map image item id referenced by the `tmap` item. */
    private val gainMapImageItemId: Int

    /** The BMFF item type for the tone-mapped item. */
    private val toneMappedItemType: String

    /** The BMFF item type for the gain-map image item. */
    private val gainMapItemType: String

    /** The tone-mapped image width in pixels, or -1 when unknown. */
    private val toneMappedWidth: Int

    /** The tone-mapped image height in pixels, or -1 when unknown. */
    private val toneMappedHeight: Int

    /** The gain-map image width in pixels, or -1 when unknown. */
    private val gainMapWidth: Int

    /** The gain-map image height in pixels, or -1 when unknown. */
    private val gainMapHeight: Int

    /** The gain-map AV1 bit depth, or `null` when unknown. */
    private val gainMapBitDepth: AvifBitDepth?

    /** The gain-map AV1 chroma sampling layout, or `null` when unknown. */
    private val gainMapChromaFormat: Av1ChromaFormat?

    /** The tone-mapped item CICP color information, or `null` when absent. */
    private val toneMappedColorInfo: AvifColorInfo?

    /** The tone-mapped item ICC profile payload, or `null` when absent. */
    private val toneMappedIccProfile: ByteArray?

    /** The gain-map image item CICP color information, or `null` when absent. */
    private val gainMapColorInfo: AvifColorInfo?

    /** The gain-map metadata version field from the `tmap` payload. */
    private val metadataVersion: Int

    /** The minimum metadata version required by the `tmap` payload. */
    private val metadataMinimumVersion: Int

    /** The writer metadata version from the `tmap` payload. */
    private val metadataWriterVersion: Int

    /** The parsed gain-map metadata, or `null` when the metadata version is unsupported. */
    private val metadata: AvifGainMapMetadata?

    /** Creates gain-map association metadata without optional image and payload descriptors.
     * 
     * @param toneMappedImageItemId the `tmap` derived image item id
     * @param baseImageItemId the base image item id referenced by the `tmap` item
     * @param gainMapImageItemId the gain-map image item id referenced by the `tmap` item
     * @param toneMappedItemType the BMFF item type for the tone-mapped item
     * @param gainMapItemType the BMFF item type for the gain-map image item
     */
    constructor(
        toneMappedImageItemId: Int,
        baseImageItemId: Int,
        gainMapImageItemId: Int,
        toneMappedItemType: String,
        gainMapItemType: String
    ) : this(
        toneMappedImageItemId,
        baseImageItemId,
        gainMapImageItemId,
        toneMappedItemType,
        gainMapItemType,
        -1,
        -1,
        -1,
        -1,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        0,
        null
    )

    init {
        require(toneMappedImageItemId > 0) { "toneMappedImageItemId <= 0: $toneMappedImageItemId" }
        require(baseImageItemId > 0) { "baseImageItemId <= 0: $baseImageItemId" }
        require(gainMapImageItemId > 0) { "gainMapImageItemId <= 0: $gainMapImageItemId" }
        require(
            !(!isKnownSize(toneMappedWidth, toneMappedHeight) && !isUnknownSize(
                toneMappedWidth,
                toneMappedHeight
            ))
        ) { "toneMappedWidth and toneMappedHeight must both be positive or both be -1" }
        require(
            !(!isKnownSize(gainMapWidth, gainMapHeight) && !isUnknownSize(
                gainMapWidth,
                gainMapHeight
            ))
        ) { "gainMapWidth and gainMapHeight must both be positive or both be -1" }
        require((gainMapBitDepth == null) == (gainMapChromaFormat == null)) { "gainMapBitDepth and gainMapChromaFormat must both be present or both be null" }
        require(!(metadataVersion < 0 || metadataMinimumVersion < 0 || metadataWriterVersion < 0)) { "metadata version fields must be non-negative" }
        this.toneMappedImageItemId = toneMappedImageItemId
        this.baseImageItemId = baseImageItemId
        this.gainMapImageItemId = gainMapImageItemId
        this.toneMappedItemType =
            requireNotNullCompat(toneMappedItemType, "toneMappedItemType")
        this.gainMapItemType = requireNotNullCompat(gainMapItemType, "gainMapItemType")
        this.toneMappedWidth = toneMappedWidth
        this.toneMappedHeight = toneMappedHeight
        this.gainMapWidth = gainMapWidth
        this.gainMapHeight = gainMapHeight
        this.gainMapBitDepth = gainMapBitDepth
        this.gainMapChromaFormat = gainMapChromaFormat
        this.toneMappedColorInfo = toneMappedColorInfo
        this.toneMappedIccProfile = toneMappedIccProfile
        this.gainMapColorInfo = gainMapColorInfo
        this.metadataVersion = metadataVersion
        this.metadataMinimumVersion = metadataMinimumVersion
        this.metadataWriterVersion = metadataWriterVersion
        this.metadata = metadata
    }

    /** Returns metadata with the requested tone-mapped image dimensions.
     * 
     * Both dimensions must be positive, or both must be `-1` to represent an unknown size.
     * 
     * @param width the image width in pixels, or `-1`
     * @param height the image height in pixels, or `-1`
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withToneMappedSize(width: Int, height: Int): AvifGainMapInfo {
        return if (width == toneMappedWidth && height == toneMappedHeight) this else copy(
            width, height, gainMapWidth, gainMapHeight, gainMapBitDepth, gainMapChromaFormat,
            toneMappedColorInfo, toneMappedIccProfile, gainMapColorInfo, metadataVersion,
            metadataMinimumVersion, metadataWriterVersion, metadata
        )
    }

    /** Returns metadata with the requested gain-map image description.
     * 
     * Both dimensions must be positive, or both must be `-1`. Bit depth and chroma format must
     * either both be present or both be `null`.
     * 
     * @param width the image width in pixels, or `-1`
     * @param height the image height in pixels, or `-1`
     * @param bitDepth the gain-map bit depth, or `null` when unknown
     * @param chromaFormat the gain-map chroma format, or `null` when unknown
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withGainMapImage(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth?,
        chromaFormat: Av1ChromaFormat?
    ): AvifGainMapInfo {
        if (width == gainMapWidth && height == gainMapHeight && bitDepth == gainMapBitDepth && chromaFormat == gainMapChromaFormat) {
            return this
        }
        return copy(
            toneMappedWidth, toneMappedHeight, width, height, bitDepth, chromaFormat,
            toneMappedColorInfo, toneMappedIccProfile, gainMapColorInfo, metadataVersion,
            metadataMinimumVersion, metadataWriterVersion, metadata
        )
    }

    /** Returns metadata with the requested tone-mapped color information.
     * 
     * @param colorInfo the color information, or `null` when absent
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withToneMappedColorInfo(colorInfo: AvifColorInfo?): AvifGainMapInfo {
        return if (colorInfo == toneMappedColorInfo) this else copy(
            toneMappedWidth, toneMappedHeight,
            gainMapWidth, gainMapHeight, gainMapBitDepth, gainMapChromaFormat, colorInfo,
            toneMappedIccProfile, gainMapColorInfo, metadataVersion, metadataMinimumVersion,
            metadataWriterVersion, metadata
        )
    }

    /** Returns metadata with the requested tone-mapped ICC profile.
     * 
     * @param iccProfile the ICC profile payload, or `null` to remove it; the array is copied
     * @return a new metadata value
     */
    fun withToneMappedIccProfile(iccProfile: ByteArray?): AvifGainMapInfo {
        return copy(
            toneMappedWidth, toneMappedHeight, gainMapWidth, gainMapHeight, gainMapBitDepth,
            gainMapChromaFormat, toneMappedColorInfo, immutableBytes(iccProfile), gainMapColorInfo,
            metadataVersion, metadataMinimumVersion, metadataWriterVersion, metadata
        )
    }

    /** Returns metadata with the requested gain-map color information.
     * 
     * @param colorInfo the gain-map color information, or `null` when absent
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withGainMapColorInfo(colorInfo: AvifColorInfo?): AvifGainMapInfo {
        return if (colorInfo == gainMapColorInfo) this else copy(
            toneMappedWidth, toneMappedHeight,
            gainMapWidth, gainMapHeight, gainMapBitDepth, gainMapChromaFormat,
            toneMappedColorInfo, toneMappedIccProfile, colorInfo, metadataVersion,
            metadataMinimumVersion, metadataWriterVersion, metadata
        )
    }

    /** Returns metadata with the requested version fields and parsed payload.
     * 
     * @param version the metadata version field
     * @param minimumVersion the minimum required metadata version field
     * @param writerVersion the writer metadata version field
     * @param metadata the parsed metadata, or `null` when the version is unsupported
     * @return this metadata when unchanged, otherwise a new metadata value
     */
    fun withMetadata(
        version: Int,
        minimumVersion: Int,
        writerVersion: Int,
        metadata: AvifGainMapMetadata?
    ): AvifGainMapInfo {
        if (version == metadataVersion && minimumVersion == metadataMinimumVersion && writerVersion == metadataWriterVersion && metadata === this.metadata) {
            return this
        }
        return copy(
            toneMappedWidth, toneMappedHeight, gainMapWidth, gainMapHeight, gainMapBitDepth,
            gainMapChromaFormat, toneMappedColorInfo, toneMappedIccProfile, gainMapColorInfo,
            version, minimumVersion, writerVersion, metadata
        )
    }

    /** Returns the `tmap` derived image item id.
     * 
     * @return the `tmap` derived image item id
     */
    fun toneMappedImageItemId(): Int {
        return toneMappedImageItemId
    }

    /** Returns the base image item id referenced by the `tmap` item.
     * 
     * @return the base image item id
     */
    fun baseImageItemId(): Int {
        return baseImageItemId
    }

    /** Returns the gain-map image item id referenced by the `tmap` item.
     * 
     * @return the gain-map image item id
     */
    fun gainMapImageItemId(): Int {
        return gainMapImageItemId
    }

    /** Returns the BMFF item type for the tone-mapped item.
     * 
     * @return the BMFF item type for the tone-mapped item
     */
    fun toneMappedItemType(): String {
        return toneMappedItemType
    }

    /** Returns the BMFF item type for the gain-map image item.
     * 
     * @return the BMFF item type for the gain-map image item
     */
    fun gainMapItemType(): String {
        return gainMapItemType
    }

    /** Returns the tone-mapped image width in pixels.
     * 
     * @return the tone-mapped image width in pixels, or -1 when unknown
     */
    fun toneMappedWidth(): Int {
        return toneMappedWidth
    }

    /** Returns the tone-mapped image height in pixels.
     * 
     * @return the tone-mapped image height in pixels, or -1 when unknown
     */
    fun toneMappedHeight(): Int {
        return toneMappedHeight
    }

    /** Returns the gain-map image width in pixels.
     * 
     * @return the gain-map image width in pixels, or -1 when unknown
     */
    fun gainMapWidth(): Int {
        return gainMapWidth
    }

    /** Returns the gain-map image height in pixels.
     * 
     * @return the gain-map image height in pixels, or -1 when unknown
     */
    fun gainMapHeight(): Int {
        return gainMapHeight
    }

    /** Returns the gain-map AV1 bit depth.
     * 
     * @return the gain-map AV1 bit depth, or `null` when unknown
     */
    fun gainMapBitDepth(): AvifBitDepth? {
        return gainMapBitDepth
    }

    /** Returns the gain-map AV1 chroma sampling layout.
     * 
     * @return the gain-map AV1 chroma sampling layout, or `null` when unknown
     */
    fun gainMapChromaFormat(): Av1ChromaFormat? {
        return gainMapChromaFormat
    }

    /** Returns the tone-mapped item CICP color information.
     * 
     * This describes the alternate image represented by the `tmap` derived item. It is distinct
     * from the base image color information exposed by `AvifImageInfo.colorInfo()`.
     * 
     * @return the tone-mapped item CICP color information, or `null` when absent
     */
    fun toneMappedColorInfo(): AvifColorInfo? {
        return toneMappedColorInfo
    }

    /** Returns the tone-mapped item ICC profile payload.
     * 
     * ICC profiles are exposed as metadata only; the pure-Java decoder does not apply ICC color
     * transforms at runtime.
     * 
     * @return the tone-mapped item ICC profile payload, or `null` when absent
     */
    fun toneMappedIccProfile(): ByteArray? {
        return toneMappedIccProfile?.copyOf()
    }

    /** Returns the gain-map image item CICP color information.
     * 
     * The gain-map image uses this CICP information when its YUV planes are converted into RGB
     * gain-map samples.
     * 
     * @return the gain-map image item CICP color information, or `null` when absent
     */
    fun gainMapColorInfo(): AvifColorInfo? {
        return gainMapColorInfo
    }

    /** Returns the gain-map metadata version field from the `tmap` payload.
     * 
     * @return the gain-map metadata version field
     */
    fun metadataVersion(): Int {
        return metadataVersion
    }

    /** Returns the minimum metadata version required by the `tmap` payload.
     * 
     * @return the minimum metadata version required by the `tmap` payload
     */
    fun metadataMinimumVersion(): Int {
        return metadataMinimumVersion
    }

    /** Returns the writer metadata version from the `tmap` payload.
     * 
     * @return the writer metadata version from the `tmap` payload
     */
    fun metadataWriterVersion(): Int {
        return metadataWriterVersion
    }

    /** Returns whether this implementation can parse the advertised gain-map metadata version.
     * 
     * @return whether the gain-map metadata version is supported
     */
    fun metadataSupported(): Boolean {
        return metadata != null
    }

    /** Returns the parsed gain-map metadata.
     * 
     * @return the parsed gain-map metadata, or `null` when unsupported
     */
    fun metadata(): AvifGainMapMetadata? {
        return metadata
    }

    /** Creates a copy with the requested optional descriptors.
     * 
     * @param toneMappedWidth the tone-mapped width
     * @param toneMappedHeight the tone-mapped height
     * @param gainMapWidth the gain-map width
     * @param gainMapHeight the gain-map height
     * @param gainMapBitDepth the gain-map bit depth, or `null`
     * @param gainMapChromaFormat the gain-map chroma format, or `null`
     * @param toneMappedColorInfo the tone-mapped color information, or `null`
     * @param toneMappedIccProfile immutable tone-mapped ICC profile storage, or `null`
     * @param gainMapColorInfo the gain-map color information, or `null`
     * @param metadataVersion the metadata version
     * @param metadataMinimumVersion the minimum metadata version
     * @param metadataWriterVersion the writer metadata version
     * @param metadata the parsed metadata, or `null`
     * @return the copied metadata
     */
    private fun copy(
        toneMappedWidth: Int,
        toneMappedHeight: Int,
        gainMapWidth: Int,
        gainMapHeight: Int,
        gainMapBitDepth: AvifBitDepth?,
        gainMapChromaFormat: Av1ChromaFormat?,
        toneMappedColorInfo: AvifColorInfo?,
        toneMappedIccProfile: ByteArray?,
        gainMapColorInfo: AvifColorInfo?,
        metadataVersion: Int,
        metadataMinimumVersion: Int,
        metadataWriterVersion: Int,
        metadata: AvifGainMapMetadata?
    ): AvifGainMapInfo {
        return AvifGainMapInfo(
            toneMappedImageItemId, baseImageItemId, gainMapImageItemId,
            toneMappedItemType, gainMapItemType, toneMappedWidth, toneMappedHeight, gainMapWidth,
            gainMapHeight, gainMapBitDepth, gainMapChromaFormat, toneMappedColorInfo,
            toneMappedIccProfile, gainMapColorInfo, metadataVersion, metadataMinimumVersion,
            metadataWriterVersion, metadata
        )
    }

    companion object {
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

        /** Creates immutable byte storage.
         * 
         * @param bytes the source bytes, or `null`
         * @return immutable byte storage, or `null`
         */
        fun immutableBytes(bytes: ByteArray?): ByteArray? {
            if (bytes == null) {
                return null
            }
            return bytes.copyOf()
        }

        /** Creates a read-only byte-buffer view.
         * 
         * @param bytes the immutable byte storage, or `null`
         * @return a read-only view, or `null`
         */
        fun byteView(bytes: ByteArray?): ByteArray? {
            return bytes?.copyOf()
        }
    }
}
