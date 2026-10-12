// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.AvifImageInfo


/** Parsed AVIF container data required to decode still images and image sequences.
 * 
 * Still images expose normalized standalone or grid-derived [AvifImageSource] instances. Image
 * sequences instead expose one AV1 payload per sample. Display metadata, timing, and item
 * transforms are retained by [#info()]. */
class AvifContainer {
    /** The parsed image metadata. */
    private val info: AvifImageInfo

    /** The Sample Transform selected as a preferred alternative, or `null`. */
    private val sampleTransform: SampleTransform?

    /** The primary standalone or grid-derived image source, or `null` for a sequence. */
    private val primarySource: AvifImageSource?

    /** The alpha auxiliary image source, or `null` when absent or sequence-based. */
    private val alphaSource: AvifImageSource?

    /** The depth auxiliary image source, or `null` when absent or sequence-based. */
    private val depthSource: AvifImageSource?

    /** The gain-map image source, or `null` when absent or descriptor-only. */
    private val gainMapSource: AvifImageSource?

    /** The AV1 OBU payloads for sequence color samples, or `null` for a still image. */
    private val samplePayloads: Array<AvifPayload>?

    /** The AV1 OBU payloads for sequence alpha samples, or `null` when absent. */
    private val sequenceAlphaSamplePayloads: Array<AvifPayload>?

    /** The AV1 OBU payloads for sequence depth samples, or `null` when absent. */
    private val sequenceDepthSamplePayloads: Array<AvifPayload>?

    /** Creates parsed still-image container data from normalized image sources.
     * 
     * @param info the parsed still-image metadata
     * @param primarySource the primary standalone or grid-derived image source
     * @param alphaSource the alpha auxiliary image source, or `null`
     * @param depthSource the depth auxiliary image source, or `null`
     * @param gainMapSource the gain-map image source, or `null`
     * @param sampleTransform the preferred Sample Transform, or `null`
     */
    constructor(
        info: AvifImageInfo,
        primarySource: AvifImageSource,
        alphaSource: AvifImageSource?,
        depthSource: AvifImageSource?,
        gainMapSource: AvifImageSource?,
        sampleTransform: SampleTransform?
    ) {
        this.info = requireNotNullCompat(info, "info")
        require(!info.animated()) { "Still-image container metadata must not be animated" }
        this.sampleTransform = sampleTransform
        this.primarySource = requireNotNullCompat(primarySource, "primarySource")
        this.alphaSource = alphaSource
        this.depthSource = depthSource
        this.gainMapSource = gainMapSource
        this.samplePayloads = null
        this.sequenceAlphaSamplePayloads = null
        this.sequenceDepthSamplePayloads = null
    }

    /** Creates parsed image-sequence container data.
     * 
     * @param info the parsed image-sequence metadata
     * @param samplePayloads the AV1 OBU payloads for color samples in presentation order
     * @param sequenceAlphaSamplePayloads the AV1 OBU payloads for alpha samples, or `null`
     * @param sequenceDepthSamplePayloads the AV1 OBU payloads for depth samples, or `null`
     */
    constructor(
        info: AvifImageInfo,
        samplePayloads: Array<AvifPayload>,
        sequenceAlphaSamplePayloads: Array<AvifPayload>?,
        sequenceDepthSamplePayloads: Array<AvifPayload>?
    ) {
        this.info = requireNotNullCompat(info, "info")
        require(info.animated()) { "Image-sequence container metadata must be animated" }
        requireNotNullCompat(samplePayloads, "samplePayloads")
        require(samplePayloads.size == info.frameCount()) { "samplePayloads length must match the advertised frame count" }
        require(!(sequenceAlphaSamplePayloads != null && sequenceAlphaSamplePayloads.size != samplePayloads.size)) { "sequenceAlphaSamplePayloads length must match samplePayloads length" }
        require(!(sequenceDepthSamplePayloads != null && sequenceDepthSamplePayloads.size != samplePayloads.size)) { "sequenceDepthSamplePayloads length must match samplePayloads length" }
        this.sampleTransform = null
        this.primarySource = null
        this.alphaSource = null
        this.depthSource = null
        this.gainMapSource = null
        this.samplePayloads = copyPayloadDescriptors(samplePayloads)
        this.sequenceAlphaSamplePayloads = if (sequenceAlphaSamplePayloads != null)
            copyPayloadDescriptors(sequenceAlphaSamplePayloads)
        else
            null
        this.sequenceDepthSamplePayloads = if (sequenceDepthSamplePayloads != null)
            copyPayloadDescriptors(sequenceDepthSamplePayloads)
        else
            null
    }

    /** Returns the parsed image metadata.
     * 
     * @return the parsed image metadata
     */
    fun info(): AvifImageInfo {
        return info
    }

    /** Returns the preferred Sample Transform selected for this still image.
     * 
     * @return the Sample Transform, or `null` when no preferred `sato` alternative is present
     */
    fun sampleTransform(): SampleTransform? {
        return sampleTransform
    }

    /** Returns the primary standalone or grid-derived image source.
     * 
     * @return the primary image source, or `null` for a sequence
     */
    fun primarySource(): AvifImageSource? {
        return primarySource
    }

    /** Returns the alpha auxiliary image source.
     * 
     * @return the alpha image source, or `null`
     */
    fun alphaSource(): AvifImageSource? {
        return alphaSource
    }

    /** Returns the depth auxiliary image source.
     * 
     * @return the depth image source, or `null`
     */
    fun depthSource(): AvifImageSource? {
        return depthSource
    }

    /** Returns the gain-map image source.
     * 
     * @return the gain-map image source, or `null`
     */
    fun gainMapSource(): AvifImageSource? {
        return gainMapSource
    }

    val isSequence: Boolean
        /** Returns whether this container represents an AVIS image sequence.
         * 
         * @return whether sequence sample payloads are present
         */
        get() = samplePayloads != null

    /** Returns the AV1 OBU payloads for color samples in presentation order.
     * 
     * @return immutable payload descriptors, or `null` for a still image
     */
    fun samplePayloads(): Array<AvifPayload>? {
        return if (samplePayloads != null) samplePayloads.copyOf() else null
    }

    /** Returns the AV1 OBU payloads for alpha samples in presentation order.
     * 
     * @return immutable payload descriptors, or `null` when no sequence alpha track is present
     */
    fun sequenceAlphaSamplePayloads(): Array<AvifPayload>? {
        return if (sequenceAlphaSamplePayloads != null) sequenceAlphaSamplePayloads.copyOf() else null
    }

    /** Returns the AV1 OBU payloads for depth samples in presentation order.
     * 
     * @return immutable payload descriptors, or `null` when no sequence depth track is present
     */
    fun sequenceDepthSamplePayloads(): Array<AvifPayload>? {
        return if (sequenceDepthSamplePayloads != null) sequenceDepthSamplePayloads.copyOf() else null
    }

    companion object {
        /** Copies and validates a payload descriptor array.
         * 
         * @param payloads the source payload descriptors
         * @return an independent descriptor array
         */
        fun copyPayloadDescriptors(
            payloads: Array<AvifPayload>
        ): Array<AvifPayload> {
            val result = payloads.copyOf()
            for (i in payloads.indices) {
                requireNotNullCompat(result[i], "payloads[$i]")
            }
            return result
        }
    }
}
