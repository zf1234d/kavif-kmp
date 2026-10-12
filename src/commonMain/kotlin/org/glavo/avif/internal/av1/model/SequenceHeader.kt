// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.av1.Av1ColorConfig


/** Parsed AV1 sequence header data. */
class SequenceHeader(
    /** The AV1 profile. */
    private val profile: Int,
    /** The maximum coded frame width for the sequence. */
    private val maxWidth: Int,
    /** The maximum coded frame height for the sequence. */
    private val maxHeight: Int,
    timingInfo: TimingInfo,
    operatingPoints: Array<OperatingPoint>,
    /** Whether the sequence is marked as still-picture only. */
    private val stillPicture: Boolean,
    /** Whether the sequence uses the reduced still-picture header form. */
    private val reducedStillPictureHeader: Boolean,
    /** The encoded bit width used for frame widths. */
    private val widthBits: Int,
    /** The encoded bit width used for frame heights. */
    private val heightBits: Int,
    /** Whether frame identifiers are present in frame headers. */
    private val frameIdNumbersPresent: Boolean,
    /** The number of bits used for delta frame identifiers. */
    private val deltaFrameIdBits: Int,
    /** The number of bits used for frame identifiers. */
    private val frameIdBits: Int,
    features: FeatureConfig,
    colorConfig: Av1ColorConfig
) {
    /** The timing and decoder model information for the sequence. */
    private val timingInfo: TimingInfo

    /** The operating point definitions declared by the sequence. */
    private val operatingPoints: Array<OperatingPoint>

    /** The feature flags enabled by the sequence. */
    private val features: FeatureConfig

    /** The color and chroma configuration for the sequence. */
    private val colorConfig: Av1ColorConfig

    init {
        this.timingInfo = requireNotNullCompat(timingInfo, "timingInfo")
        this.operatingPoints =
            copyOfCompat(requireNotNullCompat(operatingPoints, "operatingPoints"), operatingPoints.size)
        this.features = requireNotNullCompat(features, "features")
        this.colorConfig = requireNotNullCompat(colorConfig, "colorConfig")
    }

    /** Returns the AV1 profile.
     * 
     * @return the AV1 profile
     */
    fun profile(): Int {
        return profile
    }

    /** Returns the maximum coded frame width for the sequence.
     * 
     * @return the maximum coded frame width
     */
    fun maxWidth(): Int {
        return maxWidth
    }

    /** Returns the maximum coded frame height for the sequence.
     * 
     * @return the maximum coded frame height
     */
    fun maxHeight(): Int {
        return maxHeight
    }

    /** Returns the timing and decoder model information for the sequence.
     * 
     * @return the timing and decoder model information
     */
    fun timingInfo(): TimingInfo {
        return timingInfo
    }

    /** Returns the declared operating points.
     * 
     * @return the declared operating points
     */
    fun operatingPoints(): Array<OperatingPoint> {
        return operatingPoints.copyOf()
    }

    /** Returns the operating point count.
     * 
     * @return the operating point count
     */
    fun operatingPointCount(): Int {
        return operatingPoints.size
    }

    /** Returns a declared operating point by index.
     * 
     * @param index the zero-based operating point index
     * @return the declared operating point
     */
    fun operatingPoint(index: Int): OperatingPoint {
        return operatingPoints[index]
    }

    /** Returns whether the sequence is marked as still-picture only.
     * 
     * @return whether the sequence is still-picture only
     */
    fun stillPicture(): Boolean {
        return stillPicture
    }

    /** Returns whether the sequence uses the reduced still-picture header form.
     * 
     * @return whether the reduced header form is used
     */
    fun reducedStillPictureHeader(): Boolean {
        return reducedStillPictureHeader
    }

    /** Returns the encoded bit width used for frame widths.
     * 
     * @return the encoded bit width used for frame widths
     */
    fun widthBits(): Int {
        return widthBits
    }

    /** Returns the encoded bit width used for frame heights.
     * 
     * @return the encoded bit width used for frame heights
     */
    fun heightBits(): Int {
        return heightBits
    }

    /** Returns whether frame identifiers are present in frame headers.
     * 
     * @return whether frame identifiers are present
     */
    fun frameIdNumbersPresent(): Boolean {
        return frameIdNumbersPresent
    }

    /** Returns the number of bits used for delta frame identifiers.
     * 
     * @return the number of bits used for delta frame identifiers
     */
    fun deltaFrameIdBits(): Int {
        return deltaFrameIdBits
    }

    /** Returns the number of bits used for frame identifiers.
     * 
     * @return the number of bits used for frame identifiers
     */
    fun frameIdBits(): Int {
        return frameIdBits
    }

    /** Returns the feature flags enabled by the sequence.
     * 
     * @return the enabled sequence features
     */
    fun features(): FeatureConfig {
        return features
    }

    /** Returns the color and chroma configuration for the sequence.
     * 
     * @return the color and chroma configuration
     */
    fun colorConfig(): Av1ColorConfig {
        return colorConfig
    }

    /** Tri-state AV1 boolean syntax elements. */
    enum class AdaptiveBoolean {
        /** The feature is disabled. */
        OFF,

        /** The feature is enabled. */
        ON,

        /** The feature is signaled adaptively at frame level. */
        ADAPTIVE
    }

    /** Timing and decoder model information declared by the sequence. */
    class TimingInfo
    /** Creates timing and decoder model information.
     * 
     * @param present whether timing information is present
     * @param numUnitsInTick the AV1 `num_units_in_tick` value
     * @param timeScale the AV1 `time_scale` value
     * @param equalPictureInterval whether equal picture interval signaling is present
     * @param numTicksPerPicture the number of ticks per picture when signaled
     * @param decoderModelInfoPresent whether decoder model info is present
     * @param encoderDecoderBufferDelayLength the number of bits used for decoder/encoder buffer delay fields
     * @param numUnitsInDecodingTick the AV1 `num_units_in_decoding_tick` value
     * @param bufferRemovalDelayLength the number of bits used for buffer removal delay fields
     * @param framePresentationDelayLength the number of bits used for frame presentation delay fields
     * @param displayModelInfoPresent whether display model info is present
     */(
        /** Whether timing information is present. */
        val present: Boolean,
        /** The AV1 `num_units_in_tick` value. */
        val numUnitsInTick: Long,
        /** The AV1 `time_scale` value. */
        val timeScale: Long,
        /** Whether equal picture interval signaling is present. */
        val equalPictureInterval: Boolean,
        /** The number of ticks per picture when signaled. */
        val numTicksPerPicture: Long,
        /** Whether decoder model info is present. */
        val decoderModelInfoPresent: Boolean,
        /** The number of bits used for decoder/encoder buffer delay fields. */
        val encoderDecoderBufferDelayLength: Int,
        /** The AV1 `num_units_in_decoding_tick` value. */
        val numUnitsInDecodingTick: Long,
        /** The number of bits used for buffer removal delay fields. */
        val bufferRemovalDelayLength: Int,
        /** The number of bits used for frame presentation delay fields. */
        val framePresentationDelayLength: Int,
        /** Whether display model info is present. */
        val displayModelInfoPresent: Boolean
    ) {
        /** Returns whether timing information is present.
         * 
         * @return whether timing information is present
         */
        fun present(): Boolean {
            return present
        }

        /** Returns the AV1 `num_units_in_tick` value.
         * 
         * @return the AV1 `num_units_in_tick` value
         */
        fun numUnitsInTick(): Long {
            return numUnitsInTick
        }

        /** Returns the AV1 `time_scale` value.
         * 
         * @return the AV1 `time_scale` value
         */
        fun timeScale(): Long {
            return timeScale
        }

        /** Returns whether equal picture interval signaling is present.
         * 
         * @return whether equal picture interval signaling is present
         */
        fun equalPictureInterval(): Boolean {
            return equalPictureInterval
        }

        /** Returns the number of ticks per picture when signaled.
         * 
         * @return the number of ticks per picture
         */
        fun numTicksPerPicture(): Long {
            return numTicksPerPicture
        }

        /** Returns whether decoder model info is present.
         * 
         * @return whether decoder model info is present
         */
        fun decoderModelInfoPresent(): Boolean {
            return decoderModelInfoPresent
        }

        /** Returns the number of bits used for decoder/encoder buffer delay fields.
         * 
         * @return the decoder/encoder buffer delay field width
         */
        fun encoderDecoderBufferDelayLength(): Int {
            return encoderDecoderBufferDelayLength
        }

        /** Returns the AV1 `num_units_in_decoding_tick` value.
         * 
         * @return the AV1 `num_units_in_decoding_tick` value
         */
        fun numUnitsInDecodingTick(): Long {
            return numUnitsInDecodingTick
        }

        /** Returns the number of bits used for buffer removal delay fields.
         * 
         * @return the buffer removal delay field width
         */
        fun bufferRemovalDelayLength(): Int {
            return bufferRemovalDelayLength
        }

        /** Returns the number of bits used for frame presentation delay fields.
         * 
         * @return the frame presentation delay field width
         */
        fun framePresentationDelayLength(): Int {
            return framePresentationDelayLength
        }

        /** Returns whether display model info is present.
         * 
         * @return whether display model info is present
         */
        fun displayModelInfoPresent(): Boolean {
            return displayModelInfoPresent
        }
    }

    /** Operating point data declared by the sequence. */
    class OperatingPoint
    /** Creates an operating point description.
     * 
     * @param majorLevel the AV1 major level
     * @param minorLevel the AV1 minor level
     * @param initialDisplayDelay the initial display delay, or `10` when not signaled
     * @param idc the AV1 operating point IDC mask
     * @param tier whether the operating point uses high tier
     * @param decoderModelParamPresent whether decoder model parameters are present
     * @param displayModelParamPresent whether display model parameters are present
     * @param operatingParameterInfo the optional operating parameter info
     */(
        /** The AV1 major level. */
        val majorLevel: Int,
        /** The AV1 minor level. */
        val minorLevel: Int,
        /** The initial display delay, or `10` when not signaled. */
        val initialDisplayDelay: Int,
        /** The AV1 operating point IDC mask. */
        val idc: Int,
        /** Whether the operating point uses high tier. */
        val tier: Boolean,
        /** Whether decoder model parameters are present for this operating point. */
        val decoderModelParamPresent: Boolean,
        /** Whether display model parameters are present for this operating point. */
        val displayModelParamPresent: Boolean,
        /** The optional operating parameter info for this operating point. */
        val operatingParameterInfo: OperatingParameterInfo?
    ) {
        /** Returns the AV1 major level.
         * 
         * @return the AV1 major level
         */
        fun majorLevel(): Int {
            return majorLevel
        }

        /** Returns the AV1 minor level.
         * 
         * @return the AV1 minor level
         */
        fun minorLevel(): Int {
            return minorLevel
        }

        /** Returns the initial display delay, or `10` when not signaled.
         * 
         * @return the initial display delay
         */
        fun initialDisplayDelay(): Int {
            return initialDisplayDelay
        }

        /** Returns the AV1 operating point IDC mask.
         * 
         * @return the AV1 operating point IDC mask
         */
        fun idc(): Int {
            return idc
        }

        /** Returns whether the operating point uses high tier.
         * 
         * @return whether the operating point uses high tier
         */
        fun tier(): Boolean {
            return tier
        }

        /** Returns whether decoder model parameters are present.
         * 
         * @return whether decoder model parameters are present
         */
        fun decoderModelParamPresent(): Boolean {
            return decoderModelParamPresent
        }

        /** Returns whether display model parameters are present.
         * 
         * @return whether display model parameters are present
         */
        fun displayModelParamPresent(): Boolean {
            return displayModelParamPresent
        }

        /** Returns the optional operating parameter info.
         * 
         * @return the optional operating parameter info, or `null`
         */
        fun operatingParameterInfo(): OperatingParameterInfo? {
            return operatingParameterInfo
        }
    }

    /** Operating parameter info declared for an operating point. */
    class OperatingParameterInfo
    /** Creates operating parameter info.
     * 
     * @param decoderBufferDelay the decoder buffer delay
     * @param encoderBufferDelay the encoder buffer delay
     * @param lowDelayMode whether low-delay mode is enabled
     */(
        /** The decoder buffer delay. */
        val decoderBufferDelay: Long,
        /** The encoder buffer delay. */
        val encoderBufferDelay: Long,
        /** Whether low-delay mode is enabled. */
        val lowDelayMode: Boolean
    ) {
        /** Returns the decoder buffer delay.
         * 
         * @return the decoder buffer delay
         */
        fun decoderBufferDelay(): Long {
            return decoderBufferDelay
        }

        /** Returns the encoder buffer delay.
         * 
         * @return the encoder buffer delay
         */
        fun encoderBufferDelay(): Long {
            return encoderBufferDelay
        }

        /** Returns whether low-delay mode is enabled.
         * 
         * @return whether low-delay mode is enabled
         */
        fun lowDelayMode(): Boolean {
            return lowDelayMode
        }
    }

    /** Feature flags declared by the sequence. */
    class FeatureConfig(
        /** Whether 128x128 superblocks are used. */
        val use128x128Superblocks: Boolean,
        /** Whether filter intra prediction is enabled. */
        val filterIntra: Boolean,
        /** Whether intra edge filtering is enabled. */
        val intraEdgeFilter: Boolean,
        /** Whether inter-intra prediction is enabled. */
        val interIntra: Boolean,
        /** Whether masked compound prediction is enabled. */
        val maskedCompound: Boolean,
        /** Whether warped motion is enabled. */
        val warpedMotion: Boolean,
        /** Whether dual filtering is enabled. */
        val dualFilter: Boolean,
        /** Whether order hint signaling is enabled. */
        val orderHint: Boolean,
        /** Whether joint compound prediction is enabled. */
        val jointCompound: Boolean,
        /** Whether reference frame motion vectors are enabled. */
        val refFrameMotionVectors: Boolean,
        screenContentTools: AdaptiveBoolean,
        forceIntegerMotionVectors: AdaptiveBoolean,
        /** The number of bits used for order hints. */
        val orderHintBits: Int,
        /** Whether super-resolution is enabled. */
        val superResolution: Boolean,
        /** Whether CDEF is enabled. */
        val cdef: Boolean,
        /** Whether loop restoration is enabled. */
        val restoration: Boolean,
        /** Whether film grain signaling is enabled. */
        val filmGrainPresent: Boolean
    ) {
        /** The screen content tools mode. */
        val screenContentTools: AdaptiveBoolean

        /** The force integer motion vector mode. */
        val forceIntegerMotionVectors: AdaptiveBoolean

        init {
            this.screenContentTools =
                requireNotNullCompat(screenContentTools, "screenContentTools")
            this.forceIntegerMotionVectors = requireNotNullCompat(
                forceIntegerMotionVectors,
                "forceIntegerMotionVectors"
            )
        }

        /** Returns whether 128x128 superblocks are used.
         * 
         * @return whether 128x128 superblocks are used
         */
        fun use128x128Superblocks(): Boolean {
            return use128x128Superblocks
        }

        /** Returns whether filter intra prediction is enabled.
         * 
         * @return whether filter intra prediction is enabled
         */
        fun filterIntra(): Boolean {
            return filterIntra
        }

        /** Returns whether intra edge filtering is enabled.
         * 
         * @return whether intra edge filtering is enabled
         */
        fun intraEdgeFilter(): Boolean {
            return intraEdgeFilter
        }

        /** Returns whether inter-intra prediction is enabled.
         * 
         * @return whether inter-intra prediction is enabled
         */
        fun interIntra(): Boolean {
            return interIntra
        }

        /** Returns whether masked compound prediction is enabled.
         * 
         * @return whether masked compound prediction is enabled
         */
        fun maskedCompound(): Boolean {
            return maskedCompound
        }

        /** Returns whether warped motion is enabled.
         * 
         * @return whether warped motion is enabled
         */
        fun warpedMotion(): Boolean {
            return warpedMotion
        }

        /** Returns whether dual filtering is enabled.
         * 
         * @return whether dual filtering is enabled
         */
        fun dualFilter(): Boolean {
            return dualFilter
        }

        /** Returns whether order hint signaling is enabled.
         * 
         * @return whether order hint signaling is enabled
         */
        fun orderHint(): Boolean {
            return orderHint
        }

        /** Returns whether joint compound prediction is enabled.
         * 
         * @return whether joint compound prediction is enabled
         */
        fun jointCompound(): Boolean {
            return jointCompound
        }

        /** Returns whether reference frame motion vectors are enabled.
         * 
         * @return whether reference frame motion vectors are enabled
         */
        fun refFrameMotionVectors(): Boolean {
            return refFrameMotionVectors
        }

        /** Returns the screen content tools mode.
         * 
         * @return the screen content tools mode
         */
        fun screenContentTools(): AdaptiveBoolean {
            return screenContentTools
        }

        /** Returns the force integer motion vector mode.
         * 
         * @return the force integer motion vector mode
         */
        fun forceIntegerMotionVectors(): AdaptiveBoolean {
            return forceIntegerMotionVectors
        }

        /** Returns the number of bits used for order hints.
         * 
         * @return the number of bits used for order hints
         */
        fun orderHintBits(): Int {
            return orderHintBits
        }

        /** Returns whether super-resolution is enabled.
         * 
         * @return whether super-resolution is enabled
         */
        fun superResolution(): Boolean {
            return superResolution
        }

        /** Returns whether CDEF is enabled.
         * 
         * @return whether CDEF is enabled
         */
        fun cdef(): Boolean {
            return cdef
        }

        /** Returns whether loop restoration is enabled.
         * 
         * @return whether loop restoration is enabled
         */
        fun restoration(): Boolean {
            return restoration
        }

        /** Returns whether film grain signaling is enabled.
         * 
         * @return whether film grain signaling is enabled
         */
        fun filmGrainPresent(): Boolean {
            return filmGrainPresent
        }
    }
}
