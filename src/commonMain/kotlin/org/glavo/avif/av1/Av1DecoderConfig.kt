// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif.av1

class Av1DecoderConfig private constructor(
    private val applyFilmGrain: Boolean,
    private val strictStdCompliance: Boolean,
    private val outputInvisibleFrames: Boolean,
    private val outputAllLayers: Boolean,
    private val largeScaleTileMode: Boolean,
    private val frameSelection: Av1FrameSelection,
    private val operatingPoint: Int,
    private val frameSizeLimit: Long,
    private val obuPayloadSizeLimit: Long,
) {
    init {
        if (operatingPoint < 0 || operatingPoint > 31) {
            throw IllegalArgumentException("operatingPoint out of range: $operatingPoint")
        }
        if (frameSizeLimit < 0) {
            throw IllegalArgumentException("frameSizeLimit < 0: $frameSizeLimit")
        }
        if (obuPayloadSizeLimit < 0) {
            throw IllegalArgumentException("obuPayloadSizeLimit < 0: $obuPayloadSizeLimit")
        }
    }

    fun applyFilmGrain(): Boolean = applyFilmGrain
    fun strictStdCompliance(): Boolean = strictStdCompliance
    fun outputInvisibleFrames(): Boolean = outputInvisibleFrames
    fun outputAllLayers(): Boolean = outputAllLayers
    fun largeScaleTileMode(): Boolean = largeScaleTileMode
    fun frameSelection(): Av1FrameSelection = frameSelection
    fun operatingPoint(): Int = operatingPoint
    fun frameSizeLimit(): Long = frameSizeLimit
    fun obuPayloadSizeLimit(): Long = obuPayloadSizeLimit

    fun withApplyFilmGrain(value: Boolean): Av1DecoderConfig {
        return if (value == applyFilmGrain) this
        else copy(value, strictStdCompliance, outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit, obuPayloadSizeLimit)
    }

    fun withStrictStdCompliance(value: Boolean): Av1DecoderConfig {
        return if (value == strictStdCompliance) this
        else copy(applyFilmGrain, value, outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit, obuPayloadSizeLimit)
    }

    fun withOutputInvisibleFrames(value: Boolean): Av1DecoderConfig {
        return if (value == outputInvisibleFrames) this
        else copy(applyFilmGrain, strictStdCompliance, value, outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit, obuPayloadSizeLimit)
    }

    fun withOutputAllLayers(value: Boolean): Av1DecoderConfig {
        return if (value == outputAllLayers) this
        else copy(applyFilmGrain, strictStdCompliance, outputInvisibleFrames, value, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit, obuPayloadSizeLimit)
    }

    fun withLargeScaleTileMode(value: Boolean): Av1DecoderConfig {
        return if (value == largeScaleTileMode) this
        else copy(applyFilmGrain, strictStdCompliance, outputInvisibleFrames, outputAllLayers, value, frameSelection, operatingPoint, frameSizeLimit, obuPayloadSizeLimit)
    }

    fun withFrameSelection(value: Av1FrameSelection): Av1DecoderConfig {
        return if (value === frameSelection) this
        else copy(applyFilmGrain, strictStdCompliance, outputInvisibleFrames, outputAllLayers, largeScaleTileMode, value, operatingPoint, frameSizeLimit, obuPayloadSizeLimit)
    }

    fun withOperatingPoint(value: Int): Av1DecoderConfig {
        return if (value == operatingPoint) this
        else copy(applyFilmGrain, strictStdCompliance, outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection, value, frameSizeLimit, obuPayloadSizeLimit)
    }

    fun withFrameSizeLimit(value: Long): Av1DecoderConfig {
        return if (value == frameSizeLimit) this
        else copy(applyFilmGrain, strictStdCompliance, outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, value, obuPayloadSizeLimit)
    }

    fun withObuPayloadSizeLimit(value: Long): Av1DecoderConfig {
        return if (value == obuPayloadSizeLimit) this
        else copy(applyFilmGrain, strictStdCompliance, outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit, value)
    }

    companion object {
        private const val DEFAULT_FRAME_SIZE_LIMIT = 8192L * 8192L
        private const val DEFAULT_OBU_PAYLOAD_SIZE_LIMIT = 256L * 1024L * 1024L

        val DEFAULT: Av1DecoderConfig = Av1DecoderConfig(
            true,
            false,
            false,
            false,
            false,
            Av1FrameSelection.ALL,
            0,
            DEFAULT_FRAME_SIZE_LIMIT,
            DEFAULT_OBU_PAYLOAD_SIZE_LIMIT,
        )

        fun copy(
            applyFilmGrain: Boolean,
            strictStdCompliance: Boolean,
            outputInvisibleFrames: Boolean,
            outputAllLayers: Boolean,
            largeScaleTileMode: Boolean,
            frameSelection: Av1FrameSelection,
            operatingPoint: Int,
            frameSizeLimit: Long,
            obuPayloadSizeLimit: Long,
        ): Av1DecoderConfig {
            return Av1DecoderConfig(
                applyFilmGrain,
                strictStdCompliance,
                outputInvisibleFrames,
                outputAllLayers,
                largeScaleTileMode,
                frameSelection,
                operatingPoint,
                frameSizeLimit,
                obuPayloadSizeLimit,
            )
        }
    }
}
