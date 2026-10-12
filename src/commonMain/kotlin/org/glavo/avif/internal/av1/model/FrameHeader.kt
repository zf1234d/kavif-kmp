// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.av1.Av1FrameType


/** Parsed AV1 frame header data. */
class FrameHeader {
    /** The temporal layer identifier copied from the OBU header. */
    private val temporalId: Int

    /** The spatial layer identifier copied from the OBU header. */
    private val spatialId: Int

    /** Whether this is a show-existing-frame header. */
    private val showExistingFrame: Boolean

    /** The referenced frame slot when `showExistingFrame` is true. */
    private val existingFrameIndex: Int

    /** The frame identifier when present. */
    private val frameId: Long

    /** The frame presentation delay when present. */
    private val framePresentationDelay: Long

    /** The AV1 frame type. */
    private val frameType: Av1FrameType

    /** Whether the frame is shown immediately. */
    private val showFrame: Boolean

    /** Whether the frame can be shown by a later show-existing-frame header. */
    private val showableFrame: Boolean

    /** Whether error resilient mode is enabled. */
    private val errorResilientMode: Boolean

    /** Whether CDF updates are disabled. */
    private val disableCdfUpdate: Boolean

    /** Whether screen content tools are enabled for this frame. */
    private val allowScreenContentTools: Boolean

    /** Whether integer motion vectors are forced for this frame. */
    private val forceIntegerMotionVectors: Boolean

    /** Whether frame size override signaling is enabled. */
    private val frameSizeOverride: Boolean

    /** The primary reference frame index, or `7` when none is used. */
    private val primaryRefFrame: Int

    /** The frame order hint when present. */
    private val frameOffset: Int

    /** The refresh frame flags bitset. */
    private val refreshFrameFlags: Int

    /** Whether short signaling was used to derive the reference-frame list. */
    private val frameReferenceShortSignaling: Boolean

    /** The decoded reference-frame slot indices for LAST..ALTREF. */
    private val referenceFrameIndices: IntArray

    /** The frame dimensions and render size. */
    private val frameSize: FrameSize

    /** The super-resolution settings for the frame. */
    private val superResolution: SuperResolutionInfo

    /** Whether `allow_intrabc` is enabled. */
    private val allowIntrabc: Boolean

    /** Whether high-precision motion vectors are allowed. */
    private val allowHighPrecisionMotionVectors: Boolean

    /** The frame-level interpolation filter mode for inter prediction. */
    private val subpelFilterMode: InterpolationFilter

    /** Whether motion mode signaling is switchable. */
    private val switchableMotionMode: Boolean

    /** Whether reference-frame motion vectors are enabled. */
    private val useReferenceFrameMotionVectors: Boolean

    /** Whether context refresh is enabled. */
    private val refreshContext: Boolean

    /** The tile layout information. */
    private val tiling: TilingInfo

    /** The quantization parameters. */
    private val quantization: QuantizationInfo

    /** The segmentation parameters. */
    private val segmentation: SegmentationInfo

    /** The delta-q and delta-lf signaling flags. */
    private val delta: DeltaInfo

    /** Whether all segments are lossless. */
    private val allLossless: Boolean

    /** The loop filter parameters. */
    private val loopFilter: LoopFilterInfo

    /** The CDEF parameters. */
    private val cdef: CdefInfo

    /** The loop restoration parameters. */
    private val restoration: RestorationInfo

    /** The transform mode for this frame. */
    private val transformMode: TransformMode

    /** Whether compound reference mode is switchable for inter prediction. */
    private val switchableCompoundReferences: Boolean

    /** Whether skip mode is permitted for this frame. */
    private val skipModeAllowed: Boolean

    /** Whether skip mode is enabled for this frame. */
    private val skipModeEnabled: Boolean

    /** The two reference indices used by skip mode, or `-1` when unavailable. */
    private val skipModeReferenceIndices: IntArray

    /** Whether warped motion is enabled for this frame. */
    private val warpedMotion: Boolean

    /** Whether the reduced transform type set is used. */
    private val reducedTransformSet: Boolean

    /** The decoded global-motion parameters for LAST through ALTREF. */
    private val globalMotionParameters: Array<GlobalMotionParams>

    /** The normalized film grain parameters for this frame. */
    private val filmGrain: FilmGrainParams

    /** Creates a parsed frame header with inter-specific fields defaulted to disabled values and normalized film grain parameters.
     * 
     * @param temporalId the temporal layer identifier
     * @param spatialId the spatial layer identifier
     * @param showExistingFrame whether this is a show-existing-frame header
     * @param existingFrameIndex the referenced frame slot when `showExistingFrame` is true
     * @param frameId the frame identifier when present
     * @param framePresentationDelay the frame presentation delay when present
     * @param frameType the AV1 frame type
     * @param showFrame whether the frame is shown immediately
     * @param showableFrame whether the frame can be shown later
     * @param errorResilientMode whether error resilient mode is enabled
     * @param disableCdfUpdate whether CDF updates are disabled
     * @param allowScreenContentTools whether screen content tools are enabled
     * @param forceIntegerMotionVectors whether integer motion vectors are forced
     * @param frameSizeOverride whether frame size override signaling is enabled
     * @param primaryRefFrame the primary reference frame index, or `7` when none is used
     * @param frameOffset the frame order hint when present
     * @param refreshFrameFlags the refresh frame flags bitset
     * @param frameSize the frame dimensions and render size
     * @param superResolution the super-resolution settings
     * @param allowIntrabc whether `allow_intrabc` is enabled
     * @param refreshContext whether context refresh is enabled
     * @param tiling the tile layout information
     * @param quantization the quantization parameters
     * @param segmentation the segmentation parameters
     * @param delta the delta-q and delta-lf signaling flags
     * @param allLossless whether all segments are lossless
     * @param loopFilter the loop filter parameters
     * @param cdef the CDEF parameters
     * @param restoration the loop restoration parameters
     * @param transformMode the transform mode for this frame
     * @param reducedTransformSet whether the reduced transform type set is used
     * @param filmGrain the normalized film grain parameters
     */
    constructor(
        temporalId: Int,
        spatialId: Int,
        showExistingFrame: Boolean,
        existingFrameIndex: Int,
        frameId: Long,
        framePresentationDelay: Long,
        frameType: Av1FrameType,
        showFrame: Boolean,
        showableFrame: Boolean,
        errorResilientMode: Boolean,
        disableCdfUpdate: Boolean,
        allowScreenContentTools: Boolean,
        forceIntegerMotionVectors: Boolean,
        frameSizeOverride: Boolean,
        primaryRefFrame: Int,
        frameOffset: Int,
        refreshFrameFlags: Int,
        frameSize: FrameSize,
        superResolution: SuperResolutionInfo,
        allowIntrabc: Boolean,
        refreshContext: Boolean,
        tiling: TilingInfo,
        quantization: QuantizationInfo,
        segmentation: SegmentationInfo,
        delta: DeltaInfo,
        allLossless: Boolean,
        loopFilter: LoopFilterInfo,
        cdef: CdefInfo,
        restoration: RestorationInfo,
        transformMode: TransformMode,
        reducedTransformSet: Boolean,
        filmGrain: FilmGrainParams
    ) : this(
        temporalId,
        spatialId,
        showExistingFrame,
        existingFrameIndex,
        frameId,
        framePresentationDelay,
        frameType,
        showFrame,
        showableFrame,
        errorResilientMode,
        disableCdfUpdate,
        allowScreenContentTools,
        forceIntegerMotionVectors,
        frameSizeOverride,
        primaryRefFrame,
        frameOffset,
        refreshFrameFlags,
        false,
        intArrayOf(-1, -1, -1, -1, -1, -1, -1),
        frameSize,
        superResolution,
        allowIntrabc,
        false,
        InterpolationFilter.EIGHT_TAP_REGULAR,
        false,
        false,
        refreshContext,
        tiling,
        quantization,
        segmentation,
        delta,
        allLossless,
        loopFilter,
        cdef,
        restoration,
        transformMode,
        false,
        false,
        false,
        intArrayOf(-1, -1),
        false,
        reducedTransformSet,
        filmGrain
    )

    /** Creates a parsed frame header with normalized film grain parameters.
     * 
     * @param temporalId the temporal layer identifier
     * @param spatialId the spatial layer identifier
     * @param showExistingFrame whether this is a show-existing-frame header
     * @param existingFrameIndex the referenced frame slot when `showExistingFrame` is true
     * @param frameId the frame identifier when present
     * @param framePresentationDelay the frame presentation delay when present
     * @param frameType the AV1 frame type
     * @param showFrame whether the frame is shown immediately
     * @param showableFrame whether the frame can be shown later
     * @param errorResilientMode whether error resilient mode is enabled
     * @param disableCdfUpdate whether CDF updates are disabled
     * @param allowScreenContentTools whether screen content tools are enabled
     * @param forceIntegerMotionVectors whether integer motion vectors are forced
     * @param frameSizeOverride whether frame size override signaling is enabled
     * @param primaryRefFrame the primary reference frame index, or `7` when none is used
     * @param frameOffset the frame order hint when present
     * @param refreshFrameFlags the refresh frame flags bitset
     * @param frameReferenceShortSignaling whether short signaling was used for references
     * @param referenceFrameIndices the decoded reference-frame slot indices for LAST..ALTREF
     * @param frameSize the frame dimensions and render size
     * @param superResolution the super-resolution settings
     * @param allowIntrabc whether `allow_intrabc` is enabled
     * @param allowHighPrecisionMotionVectors whether high-precision motion vectors are allowed
     * @param subpelFilterMode the interpolation filter mode used for inter prediction
     * @param switchableMotionMode whether motion mode signaling is switchable
     * @param useReferenceFrameMotionVectors whether reference-frame motion vectors are enabled
     * @param refreshContext whether context refresh is enabled
     * @param tiling the tile layout information
     * @param quantization the quantization parameters
     * @param segmentation the segmentation parameters
     * @param delta the delta-q and delta-lf signaling flags
     * @param allLossless whether all segments are lossless
     * @param loopFilter the loop filter parameters
     * @param cdef the CDEF parameters
     * @param restoration the loop restoration parameters
     * @param transformMode the transform mode for this frame
     * @param switchableCompoundReferences whether compound reference mode is switchable
     * @param skipModeAllowed whether skip mode is permitted
     * @param skipModeEnabled whether skip mode is enabled
     * @param skipModeReferenceIndices the two skip-mode reference indices, or `-1`
     * @param warpedMotion whether warped motion is enabled
     * @param reducedTransformSet whether the reduced transform type set is used
     * @param filmGrain the normalized film grain parameters
     */
    constructor(
        temporalId: Int,
        spatialId: Int,
        showExistingFrame: Boolean,
        existingFrameIndex: Int,
        frameId: Long,
        framePresentationDelay: Long,
        frameType: Av1FrameType,
        showFrame: Boolean,
        showableFrame: Boolean,
        errorResilientMode: Boolean,
        disableCdfUpdate: Boolean,
        allowScreenContentTools: Boolean,
        forceIntegerMotionVectors: Boolean,
        frameSizeOverride: Boolean,
        primaryRefFrame: Int,
        frameOffset: Int,
        refreshFrameFlags: Int,
        frameReferenceShortSignaling: Boolean,
        referenceFrameIndices: IntArray,
        frameSize: FrameSize,
        superResolution: SuperResolutionInfo,
        allowIntrabc: Boolean,
        allowHighPrecisionMotionVectors: Boolean,
        subpelFilterMode: InterpolationFilter,
        switchableMotionMode: Boolean,
        useReferenceFrameMotionVectors: Boolean,
        refreshContext: Boolean,
        tiling: TilingInfo,
        quantization: QuantizationInfo,
        segmentation: SegmentationInfo,
        delta: DeltaInfo,
        allLossless: Boolean,
        loopFilter: LoopFilterInfo,
        cdef: CdefInfo,
        restoration: RestorationInfo,
        transformMode: TransformMode,
        switchableCompoundReferences: Boolean,
        skipModeAllowed: Boolean,
        skipModeEnabled: Boolean,
        skipModeReferenceIndices: IntArray,
        warpedMotion: Boolean,
        reducedTransformSet: Boolean,
        filmGrain: FilmGrainParams
    ) {
        this.temporalId = temporalId
        this.spatialId = spatialId
        this.showExistingFrame = showExistingFrame
        this.existingFrameIndex = existingFrameIndex
        this.frameId = frameId
        this.framePresentationDelay = framePresentationDelay
        this.frameType = requireNotNullCompat(frameType, "frameType")
        this.showFrame = showFrame
        this.showableFrame = showableFrame
        this.errorResilientMode = errorResilientMode
        this.disableCdfUpdate = disableCdfUpdate
        this.allowScreenContentTools = allowScreenContentTools
        this.forceIntegerMotionVectors = forceIntegerMotionVectors
        this.frameSizeOverride = frameSizeOverride
        this.primaryRefFrame = primaryRefFrame
        this.frameOffset = frameOffset
        this.refreshFrameFlags = refreshFrameFlags
        this.frameReferenceShortSignaling = frameReferenceShortSignaling
        require(referenceFrameIndices.size == 7) { "referenceFrameIndices.length != 7: " + referenceFrameIndices.size }
        this.referenceFrameIndices = referenceFrameIndices.copyOf()
        this.frameSize = requireNotNullCompat(frameSize, "frameSize")
        this.superResolution =
            requireNotNullCompat(superResolution, "superResolution")
        this.allowIntrabc = allowIntrabc
        this.allowHighPrecisionMotionVectors = allowHighPrecisionMotionVectors
        this.subpelFilterMode =
            requireNotNullCompat(subpelFilterMode, "subpelFilterMode")
        this.switchableMotionMode = switchableMotionMode
        this.useReferenceFrameMotionVectors = useReferenceFrameMotionVectors
        this.refreshContext = refreshContext
        this.tiling = requireNotNullCompat(tiling, "tiling")
        this.quantization = requireNotNullCompat(quantization, "quantization")
        this.segmentation = requireNotNullCompat(segmentation, "segmentation")
        this.delta = requireNotNullCompat(delta, "delta")
        this.allLossless = allLossless
        this.loopFilter = requireNotNullCompat(loopFilter, "loopFilter")
        this.cdef = requireNotNullCompat(cdef, "cdef")
        this.restoration = requireNotNullCompat(restoration, "restoration")
        this.transformMode = requireNotNullCompat(transformMode, "transformMode")
        this.switchableCompoundReferences = switchableCompoundReferences
        this.skipModeAllowed = skipModeAllowed
        this.skipModeEnabled = skipModeEnabled
        require(skipModeReferenceIndices.size == 2) { "skipModeReferenceIndices.length != 2: " + skipModeReferenceIndices.size }
        this.skipModeReferenceIndices =
            skipModeReferenceIndices.copyOf()
        this.warpedMotion = warpedMotion
        this.reducedTransformSet = reducedTransformSet
        this.globalMotionParameters = defaultGlobalMotionParameters()
        this.filmGrain = requireNotNullCompat(filmGrain, "filmGrain")
    }

    /** Creates a frame header by replacing the global-motion state of an already parsed header.
     * 
     * All other immutable state is copied from `source`. The supplied array is defensively copied.
     * 
     * @param source the parsed frame header whose remaining state is copied
     * @param globalMotionParameters the seven global-motion parameter sets for LAST through ALTREF
     */
    private constructor(source: FrameHeader, globalMotionParameters: Array<GlobalMotionParams>) {
        val nonNullSource = requireNotNullCompat(source, "source")
        this.temporalId = nonNullSource.temporalId
        this.spatialId = nonNullSource.spatialId
        this.showExistingFrame = nonNullSource.showExistingFrame
        this.existingFrameIndex = nonNullSource.existingFrameIndex
        this.frameId = nonNullSource.frameId
        this.framePresentationDelay = nonNullSource.framePresentationDelay
        this.frameType = nonNullSource.frameType
        this.showFrame = nonNullSource.showFrame
        this.showableFrame = nonNullSource.showableFrame
        this.errorResilientMode = nonNullSource.errorResilientMode
        this.disableCdfUpdate = nonNullSource.disableCdfUpdate
        this.allowScreenContentTools = nonNullSource.allowScreenContentTools
        this.forceIntegerMotionVectors = nonNullSource.forceIntegerMotionVectors
        this.frameSizeOverride = nonNullSource.frameSizeOverride
        this.primaryRefFrame = nonNullSource.primaryRefFrame
        this.frameOffset = nonNullSource.frameOffset
        this.refreshFrameFlags = nonNullSource.refreshFrameFlags
        this.frameReferenceShortSignaling = nonNullSource.frameReferenceShortSignaling
        this.referenceFrameIndices = nonNullSource.referenceFrameIndices.copyOf(
            nonNullSource.referenceFrameIndices.size
        )
        this.frameSize = nonNullSource.frameSize
        this.superResolution = nonNullSource.superResolution
        this.allowIntrabc = nonNullSource.allowIntrabc
        this.allowHighPrecisionMotionVectors = nonNullSource.allowHighPrecisionMotionVectors
        this.subpelFilterMode = nonNullSource.subpelFilterMode
        this.switchableMotionMode = nonNullSource.switchableMotionMode
        this.useReferenceFrameMotionVectors = nonNullSource.useReferenceFrameMotionVectors
        this.refreshContext = nonNullSource.refreshContext
        this.tiling = nonNullSource.tiling
        this.quantization = nonNullSource.quantization
        this.segmentation = nonNullSource.segmentation
        this.delta = nonNullSource.delta
        this.allLossless = nonNullSource.allLossless
        this.loopFilter = nonNullSource.loopFilter
        this.cdef = nonNullSource.cdef
        this.restoration = nonNullSource.restoration
        this.transformMode = nonNullSource.transformMode
        this.switchableCompoundReferences = nonNullSource.switchableCompoundReferences
        this.skipModeAllowed = nonNullSource.skipModeAllowed
        this.skipModeEnabled = nonNullSource.skipModeEnabled
        this.skipModeReferenceIndices = nonNullSource.skipModeReferenceIndices.copyOf(
            nonNullSource.skipModeReferenceIndices.size
        )
        this.warpedMotion = nonNullSource.warpedMotion
        this.reducedTransformSet = nonNullSource.reducedTransformSet
        requireNotNullCompat(
            globalMotionParameters,
            "globalMotionParameters"
        )
        require(globalMotionParameters.size == 7) { "globalMotionParameters.length != 7: " + globalMotionParameters.size }
        this.globalMotionParameters = arrayOfNullsCompat<GlobalMotionParams>(globalMotionParameters.size)
        for (i in globalMotionParameters.indices) {
            this.globalMotionParameters[i] = requireNotNullCompat(
                globalMotionParameters[i],
                "globalMotionParameters[$i]"
            )
        }
        this.filmGrain = nonNullSource.filmGrain
    }

    /** Returns the temporal layer identifier copied from the OBU header.
     * 
     * @return the temporal layer identifier
     */
    fun temporalId(): Int {
        return temporalId
    }

    /** Returns the spatial layer identifier copied from the OBU header.
     * 
     * @return the spatial layer identifier
     */
    fun spatialId(): Int {
        return spatialId
    }

    /** Returns whether this is a show-existing-frame header.
     * 
     * @return whether this is a show-existing-frame header
     */
    fun showExistingFrame(): Boolean {
        return showExistingFrame
    }

    /** Returns the referenced frame slot when `showExistingFrame` is true.
     * 
     * @return the referenced frame slot
     */
    fun existingFrameIndex(): Int {
        return existingFrameIndex
    }

    /** Returns the frame identifier when present.
     * 
     * @return the frame identifier
     */
    fun frameId(): Long {
        return frameId
    }

    /** Returns the frame presentation delay when present.
     * 
     * @return the frame presentation delay
     */
    fun framePresentationDelay(): Long {
        return framePresentationDelay
    }

    /** Returns the AV1 frame type.
     * 
     * @return the AV1 frame type
     */
    fun frameType(): Av1FrameType {
        return frameType
    }

    /** Returns whether the frame is shown immediately.
     * 
     * @return whether the frame is shown immediately
     */
    fun showFrame(): Boolean {
        return showFrame
    }

    /** Returns whether the frame can be shown later.
     * 
     * @return whether the frame can be shown later
     */
    fun showableFrame(): Boolean {
        return showableFrame
    }

    /** Returns whether error resilient mode is enabled.
     * 
     * @return whether error resilient mode is enabled
     */
    fun errorResilientMode(): Boolean {
        return errorResilientMode
    }

    /** Returns whether CDF updates are disabled.
     * 
     * @return whether CDF updates are disabled
     */
    fun disableCdfUpdate(): Boolean {
        return disableCdfUpdate
    }

    /** Returns whether screen content tools are enabled for this frame.
     * 
     * @return whether screen content tools are enabled
     */
    fun allowScreenContentTools(): Boolean {
        return allowScreenContentTools
    }

    /** Returns whether integer motion vectors are forced for this frame.
     * 
     * @return whether integer motion vectors are forced
     */
    fun forceIntegerMotionVectors(): Boolean {
        return forceIntegerMotionVectors
    }

    /** Returns whether frame size override signaling is enabled.
     * 
     * @return whether frame size override signaling is enabled
     */
    fun frameSizeOverride(): Boolean {
        return frameSizeOverride
    }

    /** Returns the primary reference frame index, or `7` when none is used.
     * 
     * @return the primary reference frame index
     */
    fun primaryRefFrame(): Int {
        return primaryRefFrame
    }

    /** Returns the frame order hint when present.
     * 
     * @return the frame order hint
     */
    fun frameOffset(): Int {
        return frameOffset
    }

    /** Returns the refresh frame flags bitset.
     * 
     * @return the refresh frame flags bitset
     */
    fun refreshFrameFlags(): Int {
        return refreshFrameFlags
    }

    /** Returns whether short signaling was used to derive the reference-frame list.
     * 
     * @return whether short signaling was used to derive the reference-frame list
     */
    fun frameReferenceShortSignaling(): Boolean {
        return frameReferenceShortSignaling
    }

    /** Returns a decoded reference-frame slot index by position.
     * 
     * @param index the zero-based LAST..ALTREF position
     * @return the decoded reference-frame slot index
     */
    fun referenceFrameIndex(index: Int): Int {
        return referenceFrameIndices[index]
    }

    /** Returns the frame dimensions and render size.
     * 
     * @return the frame dimensions and render size
     */
    fun frameSize(): FrameSize {
        return frameSize
    }

    /** Returns the super-resolution settings for the frame.
     * 
     * @return the super-resolution settings for the frame
     */
    fun superResolution(): SuperResolutionInfo {
        return superResolution
    }

    /** Returns whether `allow_intrabc` is enabled.
     * 
     * @return whether `allow_intrabc` is enabled
     */
    fun allowIntrabc(): Boolean {
        return allowIntrabc
    }

    /** Returns whether high-precision motion vectors are allowed.
     * 
     * @return whether high-precision motion vectors are allowed
     */
    fun allowHighPrecisionMotionVectors(): Boolean {
        return allowHighPrecisionMotionVectors
    }

    /** Returns the interpolation filter mode used for inter prediction.
     * 
     * @return the interpolation filter mode used for inter prediction
     */
    fun subpelFilterMode(): InterpolationFilter {
        return subpelFilterMode
    }

    /** Returns whether motion mode signaling is switchable.
     * 
     * @return whether motion mode signaling is switchable
     */
    fun switchableMotionMode(): Boolean {
        return switchableMotionMode
    }

    /** Returns whether reference-frame motion vectors are enabled.
     * 
     * @return whether reference-frame motion vectors are enabled
     */
    fun useReferenceFrameMotionVectors(): Boolean {
        return useReferenceFrameMotionVectors
    }

    /** Returns whether context refresh is enabled.
     * 
     * @return whether context refresh is enabled
     */
    fun refreshContext(): Boolean {
        return refreshContext
    }

    /** Returns the tile layout information.
     * 
     * @return the tile layout information
     */
    fun tiling(): TilingInfo {
        return tiling
    }

    /** Returns the quantization parameters.
     * 
     * @return the quantization parameters
     */
    fun quantization(): QuantizationInfo {
        return quantization
    }

    /** Returns the segmentation parameters.
     * 
     * @return the segmentation parameters
     */
    fun segmentation(): SegmentationInfo {
        return segmentation
    }

    /** Returns the delta-q and delta-lf signaling flags.
     * 
     * @return the delta-q and delta-lf signaling flags
     */
    fun delta(): DeltaInfo {
        return delta
    }

    /** Returns whether all segments are lossless.
     * 
     * @return whether all segments are lossless
     */
    fun allLossless(): Boolean {
        return allLossless
    }

    /** Returns the loop filter parameters.
     * 
     * @return the loop filter parameters
     */
    fun loopFilter(): LoopFilterInfo {
        return loopFilter
    }

    /** Returns the CDEF parameters.
     * 
     * @return the CDEF parameters
     */
    fun cdef(): CdefInfo {
        return cdef
    }

    /** Returns the loop restoration parameters.
     * 
     * @return the loop restoration parameters
     */
    fun restoration(): RestorationInfo {
        return restoration
    }

    /** Returns the transform mode for this frame.
     * 
     * @return the transform mode for this frame
     */
    fun transformMode(): TransformMode {
        return transformMode
    }

    /** Returns whether compound reference mode is switchable for inter prediction.
     * 
     * @return whether compound reference mode is switchable for inter prediction
     */
    fun switchableCompoundReferences(): Boolean {
        return switchableCompoundReferences
    }

    /** Returns whether skip mode is permitted for this frame.
     * 
     * @return whether skip mode is permitted for this frame
     */
    fun skipModeAllowed(): Boolean {
        return skipModeAllowed
    }

    /** Returns whether skip mode is enabled for this frame.
     * 
     * @return whether skip mode is enabled for this frame
     */
    fun skipModeEnabled(): Boolean {
        return skipModeEnabled
    }

    /** Returns one skip-mode reference index by position.
     * 
     * @param index the zero-based skip-mode reference position
     * @return the skip-mode reference index, or `-1`
     */
    fun skipModeReferenceIndex(index: Int): Int {
        return skipModeReferenceIndices[index]
    }

    /** Returns whether warped motion is enabled for this frame.
     * 
     * @return whether warped motion is enabled for this frame
     */
    fun warpedMotion(): Boolean {
        return warpedMotion
    }

    /** Returns whether the reduced transform type set is used.
     * 
     * @return whether the reduced transform type set is used
     */
    fun reducedTransformSet(): Boolean {
        return reducedTransformSet
    }

    /** Returns the global-motion parameters for one LAST-through-ALTREF reference position.
     * 
     * @param referenceFrame the zero-based reference position in `[0, 7)`
     * @return the decoded global-motion parameters for the supplied reference
     */
    fun globalMotion(referenceFrame: Int): GlobalMotionParams {
        return globalMotionParameters[checkIndexCompat(
            referenceFrame,
            globalMotionParameters.size
        )]
    }

    /** Returns this header with the supplied global-motion parameters.
     * 
     * The receiver is not modified. The supplied array is defensively copied into the returned
     * header.
     * 
     * @param parameters the seven global-motion parameter sets for LAST through ALTREF
     * @return a copied header carrying the supplied global-motion parameters
     */
    fun withGlobalMotionParameters(parameters: Array<GlobalMotionParams>): FrameHeader {
        return FrameHeader(this, parameters)
    }

    /** Returns the normalized film grain parameters for this frame.
     * 
     * @return the normalized film grain parameters for this frame
     */
    fun filmGrain(): FilmGrainParams {
        return filmGrain
    }

    /** Transform mode values used by AV1 frame headers. */
    enum class TransformMode {
        /** Lossless transform mode that restricts transforms to 4x4. */
        FOUR_BY_FOUR_ONLY,

        /** Largest-allowed transform mode. */
        LARGEST,

        /** Switchable transform mode. */
        SWITCHABLE
    }

    /** Interpolation filter modes used by AV1 inter prediction. */
    enum class InterpolationFilter {
        /** Regular 8-tap interpolation. */
        EIGHT_TAP_REGULAR,

        /** Smooth 8-tap interpolation. */
        EIGHT_TAP_SMOOTH,

        /** Sharp 8-tap interpolation. */
        EIGHT_TAP_SHARP,

        /** Bilinear interpolation. */
        BILINEAR,

        /** Switchable interpolation selected per block. */
        SWITCHABLE
    }

    /** Global warped-motion model types signaled by AV1 frame headers. */
    enum class GlobalMotionType {
        /** No global displacement is applied. */
        IDENTITY,

        /** A translation-only model is applied. */
        TRANSLATION,

        /** A constrained rotation-and-zoom model is applied. */
        ROTATION_ZOOM,

        /** A general affine model is applied. */
        AFFINE
    }

    /** Immutable AV1 global-motion parameters for one inter reference. */
    class GlobalMotionParams(type: GlobalMotionType, matrix: IntArray) {
        /** The decoded global-motion model type. */
        val type: GlobalMotionType

        /** The six AV1 affine matrix entries in fixed-point representation. */
        val matrix: IntArray

        init {
            this.type = requireNotNullCompat(type, "type")
            requireNotNullCompat(matrix, "matrix")
            require(matrix.size == 6) { "matrix.length != 6: " + matrix.size }
            this.matrix = matrix.copyOf()
        }

        /** Returns the decoded global-motion model type.
         * 
         * @return the decoded global-motion model type
         */
        fun type(): GlobalMotionType {
            return type
        }

        /** Returns a copy of the six AV1 affine matrix entries.
         * 
         * @return a copy of the six fixed-point matrix entries
         */
        fun matrix(): IntArray {
            return matrix.copyOf()
        }

        /** Returns one AV1 affine matrix entry.
         * 
         * @param index the zero-based matrix index in `[0, 6)`
         * @return the requested fixed-point matrix entry
         */
        fun matrix(index: Int): Int {
            return matrix[checkIndexCompat(index, matrix.size)]
        }

        companion object {
            /** The identity affine matrix in AV1 fixed-point representation. */
            val IDENTITY_MATRIX: IntArray =
                intArrayOf(0, 0, 1 shl 16, 0, 0, 1 shl 16)

            /** The shared immutable identity parameter set. */
            val IDENTITY = GlobalMotionParams(GlobalMotionType.IDENTITY, IDENTITY_MATRIX)

            /** Returns the shared identity global-motion parameter set.
             * 
             * @return the identity global-motion parameters
             */
            fun identity(): GlobalMotionParams {
                return IDENTITY
            }
        }
    }

    /** Loop restoration filter types used by AV1 frame headers. */
    enum class RestorationType {
        /** Loop restoration is disabled. */
        NONE,

        /** Loop restoration chooses the filter type adaptively. */
        SWITCHABLE,

        /** The Wiener restoration filter is used. */
        WIENER,

        /** The self-guided restoration filter is used. */
        SELF_GUIDED
    }

    /** Parsed frame dimensions and render size. */
    class FrameSize
    /** Creates frame dimensions and render size information.
     * 
     * @param codedWidth the coded frame width
     * @param upscaledWidth the width before super-resolution downscaling
     * @param height the coded frame height
     * @param renderWidth the render width
     * @param renderHeight the render height
     */(
        /** The coded frame width after super-resolution, or the same as `upscaledWidth` when disabled. */
        val codedWidth: Int,
        /** The frame width before super-resolution downscaling. */
        val upscaledWidth: Int,
        /** The coded frame height. */
        val height: Int,
        /** The render width. */
        val renderWidth: Int,
        /** The render height. */
        val renderHeight: Int
    ) {
        /** Returns the coded frame width after super-resolution.
         * 
         * @return the coded frame width after super-resolution
         */
        fun codedWidth(): Int {
            return codedWidth
        }

        /** Returns the frame width before super-resolution downscaling.
         * 
         * @return the frame width before super-resolution downscaling
         */
        fun upscaledWidth(): Int {
            return upscaledWidth
        }

        /** Returns the coded frame height.
         * 
         * @return the coded frame height
         */
        fun height(): Int {
            return height
        }

        /** Returns the render width.
         * 
         * @return the render width
         */
        fun renderWidth(): Int {
            return renderWidth
        }

        /** Returns the render height.
         * 
         * @return the render height
         */
        fun renderHeight(): Int {
            return renderHeight
        }
    }

    /** Parsed super-resolution settings. */
    class SuperResolutionInfo
    /** Creates parsed super-resolution settings.
     * 
     * @param enabled whether super-resolution is enabled
     * @param widthScaleDenominator the width scale denominator
     */(
        /** Whether super-resolution is enabled for the frame. */
        val enabled: Boolean,
        /** The width scale denominator. */
        val widthScaleDenominator: Int
    ) {
        /** Returns whether super-resolution is enabled for the frame.
         * 
         * @return whether super-resolution is enabled
         */
        fun enabled(): Boolean {
            return enabled
        }

        /** Returns the width scale denominator.
         * 
         * @return the width scale denominator
         */
        fun widthScaleDenominator(): Int {
            return widthScaleDenominator
        }
    }

    /** Parsed tile layout information. */
    class TilingInfo(
        uniform: Boolean,
        sizeBytes: Int,
        minLog2Cols: Int,
        maxLog2Cols: Int,
        log2Cols: Int,
        columns: Int,
        minLog2Rows: Int,
        maxLog2Rows: Int,
        log2Rows: Int,
        rows: Int,
        columnStartSuperblocks: IntArray,
        rowStartSuperblocks: IntArray,
        updateTileIndex: Int
    ) {
        /** Whether uniform tiling is enabled. */
        val uniform: Boolean

        /** The number of bytes used for tile sizes when signaled. */
        val sizeBytes: Int

        /** The minimum tile column log2. */
        val minLog2Cols: Int

        /** The maximum tile column log2. */
        val maxLog2Cols: Int

        /** The actual tile column log2. */
        val log2Cols: Int

        /** The tile column count. */
        val columns: Int

        /** The minimum tile row log2. */
        val minLog2Rows: Int

        /** The maximum tile row log2. */
        val maxLog2Rows: Int

        /** The actual tile row log2. */
        val log2Rows: Int

        /** The tile row count. */
        val rows: Int

        /** The tile column start superblock coordinates. */
        val columnStartSuperblocks: IntArray

        /** The tile row start superblock coordinates. */
        val rowStartSuperblocks: IntArray

        /** The tile group update index. */
        val updateTileIndex: Int

        init {
            require(sizeBytes in 0..4) { "sizeBytes out of range: $sizeBytes" }
            this.uniform = uniform
            this.sizeBytes = sizeBytes
            this.minLog2Cols = minLog2Cols
            this.maxLog2Cols = maxLog2Cols
            this.log2Cols = log2Cols
            this.columns = columns
            this.minLog2Rows = minLog2Rows
            this.maxLog2Rows = maxLog2Rows
            this.log2Rows = log2Rows
            this.rows = rows
            this.columnStartSuperblocks = columnStartSuperblocks.copyOf()
            this.rowStartSuperblocks = rowStartSuperblocks.copyOf()
            this.updateTileIndex = updateTileIndex
        }

        /** Returns whether uniform tiling is enabled.
         * 
         * @return whether uniform tiling is enabled
         */
        fun uniform(): Boolean {
            return uniform
        }

        /** Returns the number of bytes used for tile sizes when signaled.
         * 
         * @return the number of bytes used for tile sizes when signaled
         */
        fun sizeBytes(): Int {
            return sizeBytes
        }

        /** Returns the minimum tile column log2.
         * 
         * @return the minimum tile column log2
         */
        fun minLog2Cols(): Int {
            return minLog2Cols
        }

        /** Returns the maximum tile column log2.
         * 
         * @return the maximum tile column log2
         */
        fun maxLog2Cols(): Int {
            return maxLog2Cols
        }

        /** Returns the actual tile column log2.
         * 
         * @return the actual tile column log2
         */
        fun log2Cols(): Int {
            return log2Cols
        }

        /** Returns the tile column count.
         * 
         * @return the tile column count
         */
        fun columns(): Int {
            return columns
        }

        /** Returns the minimum tile row log2.
         * 
         * @return the minimum tile row log2
         */
        fun minLog2Rows(): Int {
            return minLog2Rows
        }

        /** Returns the maximum tile row log2.
         * 
         * @return the maximum tile row log2
         */
        fun maxLog2Rows(): Int {
            return maxLog2Rows
        }

        /** Returns the actual tile row log2.
         * 
         * @return the actual tile row log2
         */
        fun log2Rows(): Int {
            return log2Rows
        }

        /** Returns the tile row count.
         * 
         * @return the tile row count
         */
        fun rows(): Int {
            return rows
        }

        /** Returns one tile-column boundary in superblock coordinates.
         * 
         * @param boundaryIndex the boundary index in `[0, columns()]`
         * @return the selected tile-column boundary
         */
        fun columnStartSuperblock(boundaryIndex: Int): Int {
            return columnStartSuperblocks[checkIndexCompat(
                boundaryIndex,
                columnStartSuperblocks.size
            )]
        }

        /** Returns one tile-row boundary in superblock coordinates.
         * 
         * @param boundaryIndex the boundary index in `[0, rows()]`
         * @return the selected tile-row boundary
         */
        fun rowStartSuperblock(boundaryIndex: Int): Int {
            return rowStartSuperblocks[checkIndexCompat(boundaryIndex, rowStartSuperblocks.size)]
        }

        /** Returns the tile group update index.
         * 
         * @return the tile group update index
         */
        fun updateTileIndex(): Int {
            return updateTileIndex
        }
    }

    /** Parsed quantization parameters. */
    class QuantizationInfo
    /** Creates parsed quantization parameters.
     * 
     * @param baseQIndex the base luma AC quantizer index
     * @param yDcDelta the luma DC delta quantizer
     * @param uDcDelta the chroma U DC delta quantizer
     * @param uAcDelta the chroma U AC delta quantizer
     * @param vDcDelta the chroma V DC delta quantizer
     * @param vAcDelta the chroma V AC delta quantizer
     * @param useQuantizationMatrices whether quantization matrices are enabled
     * @param quantizationMatrixY the luma quantization matrix index
     * @param quantizationMatrixU the chroma U quantization matrix index
     * @param quantizationMatrixV the chroma V quantization matrix index
     */(
        /** The base luma AC quantizer index. */
        val baseQIndex: Int,
        /** The luma DC delta quantizer. */
        val yDcDelta: Int,
        /** The chroma U DC delta quantizer. */
        val uDcDelta: Int,
        /** The chroma U AC delta quantizer. */
        val uAcDelta: Int,
        /** The chroma V DC delta quantizer. */
        val vDcDelta: Int,
        /** The chroma V AC delta quantizer. */
        val vAcDelta: Int,
        /** Whether quantization matrices are enabled. */
        val useQuantizationMatrices: Boolean,
        /** The luma quantization matrix index. */
        val quantizationMatrixY: Int,
        /** The chroma U quantization matrix index. */
        val quantizationMatrixU: Int,
        /** The chroma V quantization matrix index. */
        val quantizationMatrixV: Int
    ) {
        /** Returns the base luma AC quantizer index.
         * 
         * @return the base luma AC quantizer index
         */
        fun baseQIndex(): Int {
            return baseQIndex
        }

        /** Returns the luma DC delta quantizer.
         * 
         * @return the luma DC delta quantizer
         */
        fun yDcDelta(): Int {
            return yDcDelta
        }

        /** Returns the chroma U DC delta quantizer.
         * 
         * @return the chroma U DC delta quantizer
         */
        fun uDcDelta(): Int {
            return uDcDelta
        }

        /** Returns the chroma U AC delta quantizer.
         * 
         * @return the chroma U AC delta quantizer
         */
        fun uAcDelta(): Int {
            return uAcDelta
        }

        /** Returns the chroma V DC delta quantizer.
         * 
         * @return the chroma V DC delta quantizer
         */
        fun vDcDelta(): Int {
            return vDcDelta
        }

        /** Returns the chroma V AC delta quantizer.
         * 
         * @return the chroma V AC delta quantizer
         */
        fun vAcDelta(): Int {
            return vAcDelta
        }

        /** Returns whether quantization matrices are enabled.
         * 
         * @return whether quantization matrices are enabled
         */
        fun useQuantizationMatrices(): Boolean {
            return useQuantizationMatrices
        }

        /** Returns the luma quantization matrix index.
         * 
         * @return the luma quantization matrix index
         */
        fun quantizationMatrixY(): Int {
            return quantizationMatrixY
        }

        /** Returns the chroma U quantization matrix index.
         * 
         * @return the chroma U quantization matrix index
         */
        fun quantizationMatrixU(): Int {
            return quantizationMatrixU
        }

        /** Returns the chroma V quantization matrix index.
         * 
         * @return the chroma V quantization matrix index
         */
        fun quantizationMatrixV(): Int {
            return quantizationMatrixV
        }
    }

    /** Parsed segmentation parameters. */
    class SegmentationInfo(
        /** Whether segmentation is enabled. */
        val enabled: Boolean,
        /** Whether the segmentation map is updated. */
        val updateMap: Boolean,
        /** Whether temporal prediction is used for the segmentation map. */
        val temporalUpdate: Boolean,
        /** Whether segmentation data is updated in this frame. */
        val updateData: Boolean,
        /** Whether any active segment feature must be decoded before the skip flag. */
        val preskip: Boolean,
        /** The highest active segment index, or `-1` when all segment features are defaulted. */
        val lastActiveSegmentId: Int,
        segments: Array<SegmentData>,
        losslessBySegment: BooleanArray,
        qIndexBySegment: IntArray
    ) {
        /** The per-segment data array. */
        val segments: Array<SegmentData>

        /** The derived lossless flag for each segment. */
        val losslessBySegment: BooleanArray

        /** The derived qindex for each segment. */
        val qIndexBySegment: IntArray

        init {
            this.segments = segments.copyOf()
            this.losslessBySegment = losslessBySegment.copyOf()
            this.qIndexBySegment = qIndexBySegment.copyOf()
        }

        /** Creates parsed segmentation parameters with derived fields defaulted to disabled values.
         * 
         * @param enabled whether segmentation is enabled
         * @param updateMap whether the segmentation map is updated
         * @param temporalUpdate whether temporal prediction is used
         * @param updateData whether segmentation data is updated in this frame
         * @param segments the per-segment data array
         * @param losslessBySegment the derived lossless flags
         * @param qIndexBySegment the derived qindex values
         */
        constructor(
            enabled: Boolean,
            updateMap: Boolean,
            temporalUpdate: Boolean,
            updateData: Boolean,
            segments: Array<SegmentData>,
            losslessBySegment: BooleanArray,
            qIndexBySegment: IntArray
        ) : this(
            enabled,
            updateMap,
            temporalUpdate,
            updateData,
            false,
            -1,
            segments,
            losslessBySegment,
            qIndexBySegment
        )

        /** Returns whether segmentation is enabled.
         * 
         * @return whether segmentation is enabled
         */
        fun enabled(): Boolean {
            return enabled
        }

        /** Returns whether the segmentation map is updated.
         * 
         * @return whether the segmentation map is updated
         */
        fun updateMap(): Boolean {
            return updateMap
        }

        /** Returns whether temporal prediction is used for the segmentation map.
         * 
         * @return whether temporal prediction is used
         */
        fun temporalUpdate(): Boolean {
            return temporalUpdate
        }

        /** Returns whether segmentation data is updated in this frame.
         * 
         * @return whether segmentation data is updated
         */
        fun updateData(): Boolean {
            return updateData
        }

        /** Returns whether any active segment feature must be decoded before the skip flag.
         * 
         * @return whether any active segment feature must be decoded before the skip flag
         */
        fun preskip(): Boolean {
            return preskip
        }

        /** Returns the highest active segment index, or `-1` when all features are defaulted.
         * 
         * @return the highest active segment index, or `-1`
         */
        fun lastActiveSegmentId(): Int {
            return lastActiveSegmentId
        }

        /** Returns a segment description by index.
         * 
         * @param index the zero-based segment index
         * @return the segment description
         */
        fun segment(index: Int): SegmentData {
            return segments[index]
        }

        /** Returns the per-segment data array.
         * 
         * @return the per-segment data array
         */
        fun segments(): Array<SegmentData> {
            return segments.copyOf()
        }

        /** Returns the derived lossless flag for a segment.
         * 
         * @param index the zero-based segment index
         * @return the derived lossless flag
         */
        fun lossless(index: Int): Boolean {
            return losslessBySegment[index]
        }

        /** Returns the derived qindex for a segment.
         * 
         * @param index the zero-based segment index
         * @return the derived qindex
         */
        fun qIndex(index: Int): Int {
            return qIndexBySegment[index]
        }
    }

    /** Per-segment feature data. */
    class SegmentData
    /** Creates per-segment feature data.
     * 
     * @param deltaQ the segment delta-q value
     * @param deltaLfYVertical the segment luma vertical loop-filter delta
     * @param deltaLfYHorizontal the segment luma horizontal loop-filter delta
     * @param deltaLfU the segment chroma U loop-filter delta
     * @param deltaLfV the segment chroma V loop-filter delta
     * @param referenceFrame the reference frame index, or `-1` when not present
     * @param skip whether the skip feature is enabled
     * @param globalMotion whether the global motion feature is enabled
     */(
        /** The segment delta-q value. */
        val deltaQ: Int,
        /** The segment luma vertical loop-filter delta. */
        val deltaLfYVertical: Int,
        /** The segment luma horizontal loop-filter delta. */
        val deltaLfYHorizontal: Int,
        /** The segment chroma U loop-filter delta. */
        val deltaLfU: Int,
        /** The segment chroma V loop-filter delta. */
        val deltaLfV: Int,
        /** The reference frame index, or `-1` when not present. */
        val referenceFrame: Int,
        /** Whether the skip feature is enabled. */
        val skip: Boolean,
        /** Whether the global motion feature is enabled. */
        val globalMotion: Boolean
    ) {
        /** Returns the segment delta-q value.
         * 
         * @return the segment delta-q value
         */
        fun deltaQ(): Int {
            return deltaQ
        }

        /** Returns the segment luma vertical loop-filter delta.
         * 
         * @return the segment luma vertical loop-filter delta
         */
        fun deltaLfYVertical(): Int {
            return deltaLfYVertical
        }

        /** Returns the segment luma horizontal loop-filter delta.
         * 
         * @return the segment luma horizontal loop-filter delta
         */
        fun deltaLfYHorizontal(): Int {
            return deltaLfYHorizontal
        }

        /** Returns the segment chroma U loop-filter delta.
         * 
         * @return the segment chroma U loop-filter delta
         */
        fun deltaLfU(): Int {
            return deltaLfU
        }

        /** Returns the segment chroma V loop-filter delta.
         * 
         * @return the segment chroma V loop-filter delta
         */
        fun deltaLfV(): Int {
            return deltaLfV
        }

        /** Returns the reference frame index, or `-1` when not present.
         * 
         * @return the reference frame index, or `-1`
         */
        fun referenceFrame(): Int {
            return referenceFrame
        }

        /** Returns whether the skip feature is enabled.
         * 
         * @return whether the skip feature is enabled
         */
        fun skip(): Boolean {
            return skip
        }

        /** Returns whether the global motion feature is enabled.
         * 
         * @return whether the global motion feature is enabled
         */
        fun globalMotion(): Boolean {
            return globalMotion
        }
    }

    /** Parsed delta-q and delta-lf signaling flags. */
    class DeltaInfo
    /** Creates parsed delta-q and delta-lf signaling flags.
     * 
     * @param deltaQPresent whether delta-q signaling is present
     * @param deltaQResLog2 the delta-q resolution log2
     * @param deltaLfPresent whether delta-lf signaling is present
     * @param deltaLfResLog2 the delta-lf resolution log2
     * @param deltaLfMulti whether multi-component delta-lf signaling is enabled
     */(
        /** Whether delta-q signaling is present. */
        val deltaQPresent: Boolean,
        /** The delta-q resolution log2. */
        val deltaQResLog2: Int,
        /** Whether delta-lf signaling is present. */
        val deltaLfPresent: Boolean,
        /** The delta-lf resolution log2. */
        val deltaLfResLog2: Int,
        /** Whether multi-component delta-lf signaling is enabled. */
        val deltaLfMulti: Boolean
    ) {
        /** Returns whether delta-q signaling is present.
         * 
         * @return whether delta-q signaling is present
         */
        fun deltaQPresent(): Boolean {
            return deltaQPresent
        }

        /** Returns the delta-q resolution log2.
         * 
         * @return the delta-q resolution log2
         */
        fun deltaQResLog2(): Int {
            return deltaQResLog2
        }

        /** Returns whether delta-lf signaling is present.
         * 
         * @return whether delta-lf signaling is present
         */
        fun deltaLfPresent(): Boolean {
            return deltaLfPresent
        }

        /** Returns the delta-lf resolution log2.
         * 
         * @return the delta-lf resolution log2
         */
        fun deltaLfResLog2(): Int {
            return deltaLfResLog2
        }

        /** Returns whether multi-component delta-lf signaling is enabled.
         * 
         * @return whether multi-component delta-lf signaling is enabled
         */
        fun deltaLfMulti(): Boolean {
            return deltaLfMulti
        }
    }

    /** Parsed loop filter parameters. */
    class LoopFilterInfo(
        levelY: IntArray,
        /** The chroma U loop filter level. */
        val levelU: Int,
        /** The chroma V loop filter level. */
        val levelV: Int,
        /** The loop filter sharpness level. */
        val sharpness: Int,
        /** Whether mode/reference deltas are enabled. */
        val modeRefDeltaEnabled: Boolean,
        /** Whether mode/reference deltas are updated in this frame. */
        val modeRefDeltaUpdate: Boolean,
        referenceDeltas: IntArray,
        modeDeltas: IntArray
    ) {
        /** The luma loop filter levels for vertical and horizontal edges. */
        val levelY: IntArray

        /** The reference-frame loop filter deltas. */
        val referenceDeltas: IntArray

        /** The mode loop filter deltas. */
        val modeDeltas: IntArray

        init {
            this.levelY = levelY.copyOf()
            this.referenceDeltas = referenceDeltas.copyOf()
            this.modeDeltas = modeDeltas.copyOf()
        }

        /** Returns the luma loop filter levels.
         * 
         * @return the luma loop filter levels
         */
        fun levelY(): IntArray {
            return levelY.copyOf()
        }

        /** Returns one luma loop-filter level without copying the complete level array.
         * 
         * @param pass the edge pass, `0` for vertical and `1` for horizontal edges
         * @return the selected level, or zero when `pass` is outside the stored array
         */
        fun levelY(pass: Int): Int {
            return if (pass >= 0 && pass < levelY.size) levelY[pass] else 0
        }

        /** Returns the chroma U loop filter level.
         * 
         * @return the chroma U loop filter level
         */
        fun levelU(): Int {
            return levelU
        }

        /** Returns the chroma V loop filter level.
         * 
         * @return the chroma V loop filter level
         */
        fun levelV(): Int {
            return levelV
        }

        /** Returns the loop filter sharpness level.
         * 
         * @return the loop filter sharpness level
         */
        fun sharpness(): Int {
            return sharpness
        }

        /** Returns whether mode/reference deltas are enabled.
         * 
         * @return whether mode/reference deltas are enabled
         */
        fun modeRefDeltaEnabled(): Boolean {
            return modeRefDeltaEnabled
        }

        /** Returns whether mode/reference deltas are updated in this frame.
         * 
         * @return whether mode/reference deltas are updated in this frame
         */
        fun modeRefDeltaUpdate(): Boolean {
            return modeRefDeltaUpdate
        }

        /** Returns the reference-frame loop filter deltas.
         * 
         * @return the reference-frame loop filter deltas
         */
        fun referenceDeltas(): IntArray {
            return referenceDeltas.copyOf()
        }

        /** Returns the number of reference-frame loop-filter deltas.
         * 
         * @return the reference-frame delta count
         */
        fun referenceDeltaCount(): Int {
            return referenceDeltas.size
        }

        /** Returns one reference-frame loop-filter delta.
         * 
         * @param index the zero-based reference-frame index
         * @return the selected reference-frame delta
         */
        fun referenceDelta(index: Int): Int {
            return referenceDeltas[index]
        }

        /** Returns the mode loop filter deltas.
         * 
         * @return the mode loop filter deltas
         */
        fun modeDeltas(): IntArray {
            return modeDeltas.copyOf()
        }

        /** Returns the number of mode loop-filter deltas.
         * 
         * @return the mode delta count
         */
        fun modeDeltaCount(): Int {
            return modeDeltas.size
        }

        /** Returns one mode loop-filter delta.
         * 
         * @param index the zero-based mode index
         * @return the selected mode delta
         */
        fun modeDelta(index: Int): Int {
            return modeDeltas[index]
        }
    }

    /** Parsed CDEF parameters. */
    class CdefInfo(
        /** The CDEF damping value. */
        val damping: Int,
        /** The number of CDEF strength bits. */
        val bits: Int, yStrengths: IntArray, uvStrengths: IntArray
    ) {
        /** The luma CDEF strengths. */
        val yStrengths: IntArray

        /** The chroma CDEF strengths. */
        val uvStrengths: IntArray

        init {
            this.yStrengths = yStrengths.copyOf()
            this.uvStrengths = uvStrengths.copyOf()
        }

        /** Returns the CDEF damping value.
         * 
         * @return the CDEF damping value
         */
        fun damping(): Int {
            return damping
        }

        /** Returns the number of CDEF strength bits.
         * 
         * @return the number of CDEF strength bits
         */
        fun bits(): Int {
            return bits
        }

        /** Returns the luma CDEF strengths.
         * 
         * @return the luma CDEF strengths
         */
        fun yStrengths(): IntArray {
            return yStrengths.copyOf()
        }

        /** Returns the chroma CDEF strengths.
         * 
         * @return the chroma CDEF strengths
         */
        fun uvStrengths(): IntArray {
            return uvStrengths.copyOf()
        }
    }

    /** Parsed loop restoration parameters. */
    class RestorationInfo(
        types: Array<RestorationType>,
        /** The restoration unit size log2 for luma. */
        val unitSizeLog2Y: Int,
        /** The restoration unit size log2 for chroma. */
        val unitSizeLog2Uv: Int
    ) {
        /** The restoration types for Y, U, and V. */
        val types: Array<RestorationType>

        init {
            this.types = types.copyOf()
        }

        /** Returns the restoration types for Y, U, and V.
         * 
         * @return the restoration types for Y, U, and V
         */
        fun types(): Array<RestorationType> {
            return types.copyOf()
        }

        /** Returns the restoration unit size log2 for luma.
         * 
         * @return the restoration unit size log2 for luma
         */
        fun unitSizeLog2Y(): Int {
            return unitSizeLog2Y
        }

        /** Returns the restoration unit size log2 for chroma.
         * 
         * @return the restoration unit size log2 for chroma
         */
        fun unitSizeLog2Uv(): Int {
            return unitSizeLog2Uv
        }
    }

    /** One control point in a film grain scaling function. */
    class FilmGrainPoint
    /** Creates a film grain scaling point.
     * 
     * @param value the sample-domain x coordinate
     * @param scaling the scaling-function y coordinate
     */(
        /** The sample-domain x coordinate in the range `0..255`. */
        val value: Int,
        /** The scaling-function y coordinate in the range `0..255`. */
        val scaling: Int
    ) {
        /** Returns the sample-domain x coordinate in the range `0..255`.
         * 
         * @return the sample-domain x coordinate
         */
        fun value(): Int {
            return value
        }

        /** Returns the scaling-function y coordinate in the range `0..255`.
         * 
         * @return the scaling-function y coordinate
         */
        fun scaling(): Int {
            return scaling
        }
    }

    /** Normalized film grain parameters for a frame. */
    class FilmGrainParams(
        applyGrain: Boolean,
        grainSeed: Int,
        updated: Boolean,
        referenceFrameIndex: Int,
        yPoints: Array<FilmGrainPoint>,
        chromaScalingFromLuma: Boolean,
        cbPoints: Array<FilmGrainPoint>,
        crPoints: Array<FilmGrainPoint>,
        scalingShift: Int,
        arCoeffLag: Int,
        arCoefficientsY: IntArray,
        arCoefficientsCb: IntArray,
        arCoefficientsCr: IntArray,
        arCoeffShift: Int,
        grainScaleShift: Int,
        cbMult: Int,
        cbLumaMult: Int,
        cbOffset: Int,
        crMult: Int,
        crLumaMult: Int,
        crOffset: Int,
        overlapEnabled: Boolean,
        clipToRestrictedRange: Boolean
    ) {
        /** Whether film grain should be synthesized for the frame. */
        val applyGrain: Boolean

        /** The pseudo-random seed used during film grain synthesis. */
        val grainSeed: Int

        /** Whether this frame signaled a fresh parameter set instead of referencing an earlier frame. */
        val updated: Boolean

        /** The referenced frame slot when parameters were inherited, or `-1` when self-contained. */
        val referenceFrameIndex: Int

        /** The luma scaling points. */
        val yPoints: Array<FilmGrainPoint>

        /** Whether chroma scaling is derived from the luma scaling function. */
        val chromaScalingFromLuma: Boolean

        /** The Cb scaling points. */
        val cbPoints: Array<FilmGrainPoint>

        /** The Cr scaling points. */
        val crPoints: Array<FilmGrainPoint>

        /** The normalized grain scaling shift, equal to `grain_scaling_minus_8 + 8`. */
        val scalingShift: Int

        /** The auto-regressive coefficient neighborhood lag. */
        val arCoeffLag: Int

        /** The normalized luma auto-regressive coefficients with the bitstream `+128` offset removed. */
        val arCoefficientsY: IntArray

        /** The normalized Cb auto-regressive coefficients with the bitstream `+128` offset removed. */
        val arCoefficientsCb: IntArray

        /** The normalized Cr auto-regressive coefficients with the bitstream `+128` offset removed. */
        val arCoefficientsCr: IntArray

        /** The normalized auto-regressive coefficient shift, equal to `ar_coeff_shift_minus_6 + 6`. */
        val arCoeffShift: Int

        /** The grain scale shift used during synthesis. */
        val grainScaleShift: Int

        /** The normalized Cb component multiplier for chroma scaling with the bitstream `-128` bias applied. */
        val cbMult: Int

        /** The normalized luma multiplier contributing to the Cb scaling index with the bitstream `-128` bias applied. */
        val cbLumaMult: Int

        /** The normalized Cb scaling-function input offset with the bitstream `-256` bias applied. */
        val cbOffset: Int

        /** The normalized Cr component multiplier for chroma scaling with the bitstream `-128` bias applied. */
        val crMult: Int

        /** The normalized luma multiplier contributing to the Cr scaling index with the bitstream `-128` bias applied. */
        val crLumaMult: Int

        /** The normalized Cr scaling-function input offset with the bitstream `-256` bias applied. */
        val crOffset: Int

        /** Whether overlap between neighboring grain blocks is enabled. */
        val overlapEnabled: Boolean

        /** Whether synthesized samples should be clipped to the restricted range. */
        val clipToRestrictedRange: Boolean

        init {
            validateReferenceState(applyGrain, updated, referenceFrameIndex)
            this.applyGrain = applyGrain
            requireRange(grainSeed, 0, 0xFFFF, "grainSeed")
            this.grainSeed = grainSeed
            this.updated = updated
            this.referenceFrameIndex = referenceFrameIndex
            this.yPoints = copyOfCompat(requireNotNullCompat(yPoints, "yPoints"), yPoints.size)
            validateScalingPoints(this.yPoints, MAX_Y_POINTS, "yPoints")
            this.chromaScalingFromLuma = chromaScalingFromLuma
            this.cbPoints = copyOfCompat(requireNotNullCompat(cbPoints, "cbPoints"), cbPoints.size)
            validateScalingPoints(this.cbPoints, MAX_UV_POINTS, "cbPoints")
            this.crPoints = copyOfCompat(requireNotNullCompat(crPoints, "crPoints"), crPoints.size)
            validateScalingPoints(this.crPoints, MAX_UV_POINTS, "crPoints")
            require(!(chromaScalingFromLuma && (this.cbPoints.size != 0 || this.crPoints.size != 0))) { "Film grain chroma scaling derived from luma must not carry explicit chroma scaling points" }
            requireRange(scalingShift, 8, 11, "scalingShift")
            this.scalingShift = scalingShift
            requireRange(arCoeffLag, 0, 3, "arCoeffLag")
            this.arCoeffLag = arCoeffLag
            this.arCoefficientsY =
                requireNotNullCompat(arCoefficientsY, "arCoefficientsY")
                    .copyOf(arCoefficientsY.size)
            val autoregressiveCoefficientCount = 2 * arCoeffLag * (arCoeffLag + 1)
            val expectedLumaCoefficientCount =
                if (this.yPoints.size > 0) autoregressiveCoefficientCount else 0
            validateCoefficientArray(
                this.arCoefficientsY,
                expectedLumaCoefficientCount,
                "arCoefficientsY"
            )
            this.arCoefficientsCb =
                requireNotNullCompat(arCoefficientsCb, "arCoefficientsCb")
                    .copyOf(arCoefficientsCb.size)
            val expectedChromaCoefficientCount =
                autoregressiveCoefficientCount + (if (this.yPoints.size > 0) 1 else 0)
            validateCoefficientArray(
                this.arCoefficientsCb,
                if (chromaScalingFromLuma || this.cbPoints.size > 0) expectedChromaCoefficientCount else 0,
                "arCoefficientsCb"
            )
            this.arCoefficientsCr =
                requireNotNullCompat(arCoefficientsCr, "arCoefficientsCr")
                    .copyOf(arCoefficientsCr.size)
            validateCoefficientArray(
                this.arCoefficientsCr,
                if (chromaScalingFromLuma || this.crPoints.size > 0) expectedChromaCoefficientCount else 0,
                "arCoefficientsCr"
            )
            requireRange(arCoeffShift, 6, 9, "arCoeffShift")
            this.arCoeffShift = arCoeffShift
            requireRange(grainScaleShift, 0, 3, "grainScaleShift")
            this.grainScaleShift = grainScaleShift
            requireRange(cbMult, -128, 127, "cbMult")
            this.cbMult = cbMult
            requireRange(cbLumaMult, -128, 127, "cbLumaMult")
            this.cbLumaMult = cbLumaMult
            requireRange(cbOffset, -256, 255, "cbOffset")
            this.cbOffset = cbOffset
            requireRange(crMult, -128, 127, "crMult")
            this.crMult = crMult
            requireRange(crLumaMult, -128, 127, "crLumaMult")
            this.crLumaMult = crLumaMult
            requireRange(crOffset, -256, 255, "crOffset")
            this.crOffset = crOffset
            this.overlapEnabled = overlapEnabled
            this.clipToRestrictedRange = clipToRestrictedRange
        }

        /** Returns whether film grain should be synthesized for the frame.
         * 
         * @return whether film grain should be synthesized for the frame
         */
        fun applyGrain(): Boolean {
            return applyGrain
        }

        /** Returns the pseudo-random seed used during synthesis.
         * 
         * @return the pseudo-random seed used during synthesis
         */
        fun grainSeed(): Int {
            return grainSeed
        }

        /** Returns whether this frame signaled a fresh parameter set.
         * 
         * @return whether this frame signaled a fresh parameter set
         */
        fun updated(): Boolean {
            return updated
        }

        /** Returns the referenced frame slot when parameters were inherited, or `-1`.
         * 
         * @return the referenced frame slot when parameters were inherited, or `-1`
         */
        fun referenceFrameIndex(): Int {
            return referenceFrameIndex
        }

        /** Returns the luma scaling points.
         * 
         * @return the luma scaling points
         */
        fun yPoints(): Array<FilmGrainPoint> {
            return yPoints.copyOf()
        }

        /** Returns whether chroma scaling is derived from the luma scaling function.
         * 
         * @return whether chroma scaling is derived from the luma scaling function
         */
        fun chromaScalingFromLuma(): Boolean {
            return chromaScalingFromLuma
        }

        /** Returns the Cb scaling points.
         * 
         * @return the Cb scaling points
         */
        fun cbPoints(): Array<FilmGrainPoint> {
            return cbPoints.copyOf()
        }

        /** Returns the Cr scaling points.
         * 
         * @return the Cr scaling points
         */
        fun crPoints(): Array<FilmGrainPoint> {
            return crPoints.copyOf()
        }

        /** Returns the normalized grain scaling shift.
         * 
         * @return the normalized grain scaling shift
         */
        fun scalingShift(): Int {
            return scalingShift
        }

        /** Returns the auto-regressive coefficient neighborhood lag.
         * 
         * @return the auto-regressive coefficient neighborhood lag
         */
        fun arCoeffLag(): Int {
            return arCoeffLag
        }

        /** Returns the normalized luma auto-regressive coefficients.
         * 
         * @return the normalized luma auto-regressive coefficients
         */
        fun arCoefficientsY(): IntArray {
            return arCoefficientsY.copyOf()
        }

        /** Returns the normalized Cb auto-regressive coefficients.
         * 
         * @return the normalized Cb auto-regressive coefficients
         */
        fun arCoefficientsCb(): IntArray {
            return arCoefficientsCb.copyOf()
        }

        /** Returns the normalized Cr auto-regressive coefficients.
         * 
         * @return the normalized Cr auto-regressive coefficients
         */
        fun arCoefficientsCr(): IntArray {
            return arCoefficientsCr.copyOf()
        }

        /** Returns the normalized auto-regressive coefficient shift.
         * 
         * @return the normalized auto-regressive coefficient shift
         */
        fun arCoeffShift(): Int {
            return arCoeffShift
        }

        /** Returns the grain scale shift used during synthesis.
         * 
         * @return the grain scale shift used during synthesis
         */
        fun grainScaleShift(): Int {
            return grainScaleShift
        }

        /** Returns the normalized Cb component multiplier for chroma scaling.
         * 
         * @return the normalized Cb component multiplier for chroma scaling
         */
        fun cbMult(): Int {
            return cbMult
        }

        /** Returns the normalized luma multiplier contributing to the Cb scaling index.
         * 
         * @return the normalized luma multiplier contributing to the Cb scaling index
         */
        fun cbLumaMult(): Int {
            return cbLumaMult
        }

        /** Returns the normalized Cb scaling-function input offset.
         * 
         * @return the normalized Cb scaling-function input offset
         */
        fun cbOffset(): Int {
            return cbOffset
        }

        /** Returns the normalized Cr component multiplier for chroma scaling.
         * 
         * @return the normalized Cr component multiplier for chroma scaling
         */
        fun crMult(): Int {
            return crMult
        }

        /** Returns the normalized luma multiplier contributing to the Cr scaling index.
         * 
         * @return the normalized luma multiplier contributing to the Cr scaling index
         */
        fun crLumaMult(): Int {
            return crLumaMult
        }

        /** Returns the normalized Cr scaling-function input offset.
         * 
         * @return the normalized Cr scaling-function input offset
         */
        fun crOffset(): Int {
            return crOffset
        }

        /** Returns whether overlap between neighboring grain blocks is enabled.
         * 
         * @return whether overlap between neighboring grain blocks is enabled
         */
        fun overlapEnabled(): Boolean {
            return overlapEnabled
        }

        /** Returns whether synthesized samples should be clipped to the restricted range.
         * 
         * @return whether synthesized samples should be clipped to the restricted range
         */
        fun clipToRestrictedRange(): Boolean {
            return clipToRestrictedRange
        }

        companion object {
            /** The AV1 maximum luma scaling-point count. */
            private const val MAX_Y_POINTS = 14

            /** The AV1 maximum chroma scaling-point count. */
            private const val MAX_UV_POINTS = 10

            /** The total AV1 reference-slot count. */
            private const val TOTAL_REFERENCE_FRAMES = 8

            /** Returns disabled film grain parameters.
             * 
             * @return disabled film grain parameters
             */
            fun disabled(): FilmGrainParams {
                return FilmGrainParams(
                    false,
                    0,
                    false,
                    -1,
                    arrayOfNullsCompat<FilmGrainPoint>(0),
                    false,
                    arrayOfNullsCompat<FilmGrainPoint>(0),
                    arrayOfNullsCompat<FilmGrainPoint>(0),
                    8,
                    0,
                    IntArray(0),
                    IntArray(0),
                    IntArray(0),
                    6,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false
                )
            }

            /** Validates the normalized grain-reference relationship.
             * 
             * @param applyGrain whether film grain is enabled
             * @param updated whether the frame signaled fresh parameters
             * @param referenceFrameIndex the referenced frame slot, or `-1`
             */
            fun validateReferenceState(
                applyGrain: Boolean,
                updated: Boolean,
                referenceFrameIndex: Int
            ) {
                if (!applyGrain) {
                    require(!updated) { "Disabled film grain must not be marked as updated" }
                    require(referenceFrameIndex == -1) { "Disabled film grain must not reference another frame" }
                    return
                }

                if (updated) {
                    require(referenceFrameIndex == -1) { "Updated film grain must not reference another frame" }
                } else require(!(referenceFrameIndex < 0 || referenceFrameIndex >= TOTAL_REFERENCE_FRAMES)) { "Referenced film grain must use one AV1 reference slot in the range 0..7" }
            }

            /** Validates one normalized scaling-point array.
             * 
             * @param points the scaling-point array to validate
             * @param maximumPointCount the AV1 maximum point count for the component
             * @param fieldName the field name used in error messages
             */
            fun validateScalingPoints(
                points: Array<FilmGrainPoint>,
                maximumPointCount: Int,
                fieldName: String
            ) {
                require(points.size <= maximumPointCount) { fieldName + " exceeds the AV1 maximum point count of $maximumPointCount" }

                var previousValue = -1
                for (point in points) {
                    val value = point.value()
                    requireRange(value, 0, 255, fieldName + ".value")
                    requireRange(point.scaling(), 0, 255, fieldName + ".scaling")
                    require(value > previousValue) { fieldName + " values must increase strictly" }
                    previousValue = value
                }
            }

            /** Validates one normalized auto-regressive coefficient array.
             * 
             * @param coefficients the coefficient array to validate
             * @param expectedLength the exact AV1 coefficient count expected for the current state
             * @param fieldName the field name used in error messages
             */
            fun validateCoefficientArray(
                coefficients: IntArray,
                expectedLength: Int,
                fieldName: String
            ) {
                require(coefficients.size == expectedLength) { fieldName + " must contain exactly $expectedLength coefficients" }
                for (coefficient in coefficients) {
                    requireRange(coefficient, -128, 127, fieldName + " element")
                }
            }

            /** Requires one integer value to stay within one inclusive range.
             * 
             * @param value the integer value to validate
             * @param minimum the inclusive minimum
             * @param maximum the inclusive maximum
             * @param fieldName the field name used in error messages
             */
            fun requireRange(value: Int, minimum: Int, maximum: Int, fieldName: String) {
                require(value in minimum..maximum) { fieldName + " must stay in the inclusive range $minimum..$maximum" }
            }
        }
    }

    companion object {
        /** Creates identity global-motion state for all seven inter references.
         * 
         * @return seven identity global-motion parameter sets
         */
        fun defaultGlobalMotionParameters(): Array<GlobalMotionParams> {
            val parameters: Array<GlobalMotionParams> = arrayOfNullsCompat<GlobalMotionParams>(7)
            fillCompat(parameters, GlobalMotionParams.identity())
            return parameters
        }
    }
}
