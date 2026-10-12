// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.parse

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.av1.Av1FrameType
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.FrameHeader.FrameSize
import org.glavo.avif.internal.av1.model.SequenceHeader


/** Validates strict AV1 frame-header constraints that depend on previously parsed state. */
internal object FrameHeaderConformanceValidator {
    /** Number of reference slots signaled by one inter frame. */
    private const val REFERENCES_PER_FRAME = 7

    /** Validates level limits and reference-frame dimension ratios for one parsed frame.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed current frame header
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @throws AvifIoException if the frame violates a strict conformance constraint
     */
    fun validate(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader,
        referenceFrameHeaders: Array<FrameHeader?>?
    ) {
        requireNotNullCompat(sequenceHeader, "sequenceHeader")
        requireNotNullCompat(frameHeader, "frameHeader")
        requireNotNullCompat(referenceFrameHeaders, "referenceFrameHeaders")

        if (frameHeader.allLossless() && frameHeader.delta().deltaQPresent()) {
            throw AvifIoException("Coded-lossless frames must not signal delta_q_present")
        }
        validateSequenceFrameDimensions(sequenceHeader, frameHeader)
        validateShortSignaledReferences(sequenceHeader, frameHeader, referenceFrameHeaders)
        validateLevelLimits(sequenceHeader, frameHeader)
        for (referenceIndex in 0..<REFERENCES_PER_FRAME) {
            val slotIndex = frameHeader.referenceFrameIndex(referenceIndex)
            if (slotIndex < 0) {
                continue
            }
            val referenceFrameHeader = referenceFrameHeaders!![slotIndex]
            if (referenceFrameHeader != null) {
                validateReferenceDimensions(
                    frameHeader.frameSize(),
                    referenceFrameHeader.frameSize()
                )
            }
        }
    }

    /** Validates sequence and common-camera-frame requirements imposed by Large Scale Tile mode.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed current frame header
     * @throws AvifIoException if the frame violates a Large Scale Tile requirement
     */
    fun validateLargeScaleTileFrame(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader
    ) {
        requireNotNullCompat(sequenceHeader, "sequenceHeader")
        requireNotNullCompat(frameHeader, "frameHeader")
        val features = sequenceHeader.features()
        if (features.superResolution()) {
            throw AvifIoException("Large Scale Tile mode requires enable_superres to be zero")
        }
        if (features.orderHint()) {
            throw AvifIoException("Large Scale Tile mode requires enable_order_hint to be zero")
        }
        if (sequenceHeader.stillPicture()) {
            throw AvifIoException("Large Scale Tile mode requires still_picture to be zero")
        }
        if (features.filmGrainPresent()) {
            throw AvifIoException("Large Scale Tile mode requires film_grain_params_present to be zero")
        }
        if (sequenceHeader.timingInfo().present()) {
            throw AvifIoException("Large Scale Tile mode requires timing_info_present_flag to be zero")
        }
        if (sequenceHeader.timingInfo().decoderModelInfoPresent()) {
            throw AvifIoException("Large Scale Tile mode requires decoder_model_info_present_flag to be zero")
        }
        if (sequenceHeader.timingInfo().displayModelInfoPresent()) {
            throw AvifIoException("Large Scale Tile mode requires initial_display_delay_present_flag to be zero")
        }
        if (features.restoration()) {
            throw AvifIoException("Large Scale Tile mode requires enable_restoration to be zero")
        }
        if (features.cdef()) {
            throw AvifIoException("Large Scale Tile mode requires enable_cdef to be zero")
        }
        if (sequenceHeader.colorConfig().monochrome) {
            throw AvifIoException("Large Scale Tile mode requires mono_chrome to be zero")
        }

        val tiling = frameHeader.tiling()
        for (row in 0..<tiling.rows()) {
            if (tiling.rowStartSuperblock(row + 1) - tiling.rowStartSuperblock(row) != 1) {
                throw AvifIoException("Large Scale Tile camera tiles must be exactly one superblock high")
            }
        }
        val tileWidthSuperblocks = tiling.columnStartSuperblock(1) - tiling.columnStartSuperblock(0)
        for (column in 1..<tiling.columns()) {
            if (tiling.columnStartSuperblock(column + 1)
                - tiling.columnStartSuperblock(column) != tileWidthSuperblocks
            ) {
                throw AvifIoException("Large Scale Tile camera tiles must have identical widths")
            }
        }

        val frameSize = frameHeader.frameSize()
        val miColumns = ((frameSize.codedWidth() + 7) shr 3) shl 1
        val miRows = ((frameSize.height() + 7) shr 3) shl 1
        if (frameSize.codedWidth() != miColumns * 4 || frameSize.height() != miRows * 4) {
            throw AvifIoException("Large Scale Tile frame dimensions must be integer MI units")
        }
        if (frameHeader.showExistingFrame()) {
            throw AvifIoException("Large Scale Tile camera frames must not signal show_existing_frame")
        }
        if (frameHeader.frameType() != Av1FrameType.INTER) {
            throw AvifIoException("Large Scale Tile camera frames must use INTER_FRAME")
        }
        if (!frameHeader.showFrame()) {
            throw AvifIoException("Large Scale Tile camera frames must set show_frame")
        }
        if (frameHeader.errorResilientMode()) {
            throw AvifIoException("Large Scale Tile camera frames must disable error_resilient_mode")
        }
        if (!frameHeader.disableCdfUpdate()) {
            throw AvifIoException("Large Scale Tile frames must disable CDF updates")
        }
        if (frameHeader.refreshContext()) {
            throw AvifIoException("Large Scale Tile frames must disable frame-end CDF updates")
        }
        if (frameHeader.delta().deltaQPresent() || frameHeader.delta().deltaLfPresent()) {
            throw AvifIoException("Large Scale Tile frames must not signal delta quantizer or loop-filter updates")
        }
        if (frameHeader.frameSizeOverride()) {
            throw AvifIoException("Large Scale Tile camera frames must disable frame-size override")
        }
        if (frameHeader.refreshFrameFlags() != 0) {
            throw AvifIoException("Large Scale Tile camera frames must not refresh reference slots")
        }
        if (frameHeader.useReferenceFrameMotionVectors()) {
            throw AvifIoException("Large Scale Tile camera frames must not use reference-frame motion vectors")
        }
        if (frameHeader.segmentation().temporalUpdate()) {
            throw AvifIoException("Large Scale Tile camera frames must disable temporal segmentation updates")
        }
        if (frameHeader.switchableCompoundReferences()) {
            throw AvifIoException("Large Scale Tile camera frames must disable compound references")
        }
        if (frameHeader.loopFilter().levelY(0) != 0 || frameHeader.loopFilter().levelY(1) != 0) {
            throw AvifIoException("Large Scale Tile camera frames must disable loop filtering")
        }
        if (frameHeader.superResolution().enabled()) {
            throw AvifIoException("Large Scale Tile frames must not use super-resolution")
        }
    }

    /** Validates explicitly signaled frame dimensions against the sequence-header maxima.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed current frame header
     * @throws AvifIoException if the frame exceeds either declared maximum dimension
     */
    private fun validateSequenceFrameDimensions(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader
    ) {
        val frameSize = frameHeader.frameSize()
        if (frameSize.upscaledWidth() > sequenceHeader.maxWidth()) {
            throw AvifIoException("Frame width exceeds the sequence-header maximum")
        }
        if (frameSize.height() > sequenceHeader.maxHeight()) {
            throw AvifIoException("Frame height exceeds the sequence-header maximum")
        }
    }

    /** Validates the LAST and GOLDEN order hints used by short reference signaling.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed current frame header
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @throws AvifIoException if either explicitly selected reference is not earlier in output order
     */
    private fun validateShortSignaledReferences(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader,
        referenceFrameHeaders: Array<FrameHeader?>?
    ) {
        if (!frameHeader.frameReferenceShortSignaling()) {
            return
        }
        validateEarlierReferenceOrderHint(
            sequenceHeader,
            frameHeader,
            referenceFrameHeaders,
            0,
            "LAST"
        )
        validateEarlierReferenceOrderHint(
            sequenceHeader,
            frameHeader,
            referenceFrameHeaders,
            3,
            "GOLDEN"
        )
    }

    /** Validates one short-signaled reference against the current frame order hint.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed current frame header
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceIndex the LAST..ALTREF reference position
     * @param referenceName the reference name used in diagnostics
     * @throws AvifIoException if the reference is unavailable or is not earlier in output order
     */
    private fun validateEarlierReferenceOrderHint(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceIndex: Int,
        referenceName: String
    ) {
        val slotIndex = frameHeader.referenceFrameIndex(referenceIndex)
        if (slotIndex < 0 || slotIndex >= referenceFrameHeaders!!.size) {
            throw AvifIoException(referenceName + " reference does not select a valid frame slot")
        }
        val referenceFrameHeader = referenceFrameHeaders[slotIndex]
        if (referenceFrameHeader == null) {
            throw AvifIoException(referenceName + " reference selects an unavailable frame slot")
        }
        val relativeDistance = relativeDistance(
            sequenceHeader.features().orderHintBits(),
            referenceFrameHeader.frameOffset(),
            frameHeader.frameOffset()
        )
        if (relativeDistance >= 0) {
            throw AvifIoException(referenceName + " reference order hint is not earlier than the current frame")
        }
    }

    /** Returns the wrapped AV1 relative distance between two order hints.
     * 
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @param first the first order hint
     * @param second the second order hint
     * @return the wrapped signed distance `first - second`
     */
    private fun relativeDistance(orderHintBits: Int, first: Int, second: Int): Int {
        val mask = 1 shl (orderHintBits - 1)
        val difference = first - second
        return (difference and (mask - 1)) - (difference and mask)
    }

    /** Validates the current frame against each operating point that contains its layer.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed current frame header
     * @throws AvifIoException if the frame exceeds an applicable level limit
     */
    private fun validateLevelLimits(sequenceHeader: SequenceHeader, frameHeader: FrameHeader) {
        var croppedTileDimensionsValidated = false
        for (index in 0..<sequenceHeader.operatingPointCount()) {
            val operatingPoint = sequenceHeader.operatingPoint(index)
            if (!containsLayer(
                    operatingPoint.idc(),
                    frameHeader.temporalId(),
                    frameHeader.spatialId()
                )
            ) {
                continue
            }
            val limits = levelLimits(operatingPoint.majorLevel(), operatingPoint.minorLevel())
            if (limits != null) {
                if (!croppedTileDimensionsValidated) {
                    validateCroppedTileDimensions(sequenceHeader, frameHeader)
                    croppedTileDimensionsValidated = true
                }
                validateLevelLimits(
                    frameHeader,
                    limits,
                    operatingPoint.majorLevel(),
                    operatingPoint.minorLevel()
                )
            }
        }
    }

    /** Returns whether an operating-point mask contains one temporal and spatial layer pair.
     * 
     * @param operatingPointIdc the operating-point layer mask
     * @param temporalId the frame temporal layer identifier
     * @param spatialId the frame spatial layer identifier
     * @return whether the frame belongs to the operating point
     */
    private fun containsLayer(operatingPointIdc: Int, temporalId: Int, spatialId: Int): Boolean {
        if (operatingPointIdc == 0) {
            return true
        }
        return (operatingPointIdc and (1 shl temporalId)) != 0
                && (operatingPointIdc and (1 shl (spatialId + 8))) != 0
    }

    /** Validates frame dimensions and tile counts against one level-limit row.
     * 
     * @param frameHeader the parsed current frame header
     * @param limits the applicable level limits
     * @param majorLevel the declared major level
     * @param minorLevel the declared minor level
     * @throws AvifIoException if the frame exceeds a level limit
     */
    private fun validateLevelLimits(
        frameHeader: FrameHeader,
        limits: LevelLimits,
        majorLevel: Int,
        minorLevel: Int
    ) {
        val frameSize = frameHeader.frameSize()
        val tiling = frameHeader.tiling()
        val upscaledWidth = frameSize.upscaledWidth()
        val frameHeight = frameSize.height()
        val pictureSize = upscaledWidth.toLong() * frameHeight
        val tileCount = tiling.columns().toLong() * tiling.rows()
        val level = majorLevel.toString() + ".$minorLevel"

        if (upscaledWidth < 16) {
            throw AvifIoException("Frame width is smaller than the AV1 level minimum of 16 pixels")
        }
        if (frameHeight < 16) {
            throw AvifIoException("Frame height is smaller than the AV1 level minimum of 16 pixels")
        }
        if (pictureSize > limits.maximumPictureSize) {
            throw AvifIoException("Frame picture size exceeds AV1 level $level MaxPicSize")
        }
        if (upscaledWidth > limits.maximumHorizontalSize) {
            throw AvifIoException("Frame upscaled width exceeds AV1 level $level MaxHSize")
        }
        if (frameHeight > limits.maximumVerticalSize) {
            throw AvifIoException("Frame height exceeds AV1 level $level MaxVSize")
        }
        if (tileCount > limits.maximumTileCount) {
            throw AvifIoException("Frame tile count exceeds AV1 level $level MaxTiles")
        }
        if (tiling.columns() > limits.maximumTileColumns) {
            throw AvifIoException("Frame tile-column count exceeds AV1 level $level MaxTileCols")
        }
    }

    /** Validates that every cropped tile retains at least eight pixels in each dimension.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed current frame header
     * @throws AvifIoException if a cropped edge tile is narrower or shorter than eight pixels
     */
    private fun validateCroppedTileDimensions(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader
    ) {
        val tiling = frameHeader.tiling()
        val superblockSize = if (sequenceHeader.features().use128x128Superblocks()) 128 else 64
        val frameWidth = frameHeader.frameSize().codedWidth()
        val frameHeight = frameHeader.frameSize().height()

        for (column in 0..<tiling.columns()) {
            val start = min(frameWidth, tiling.columnStartSuperblock(column) * superblockSize)
            val end = min(frameWidth, tiling.columnStartSuperblock(column + 1) * superblockSize)
            if (end - start < 8) {
                throw AvifIoException("Cropped tile width is smaller than eight pixels")
            }
        }
        for (row in 0..<tiling.rows()) {
            val start = min(frameHeight, tiling.rowStartSuperblock(row) * superblockSize)
            val end = min(frameHeight, tiling.rowStartSuperblock(row + 1) * superblockSize)
            if (end - start < 8) {
                throw AvifIoException("Cropped tile height is smaller than eight pixels")
            }
        }
    }

    /** Validates the AV1 dimension-ratio limits between a frame and one referenced frame.
     * 
     * @param currentSize the current coded frame dimensions
     * @param referenceSize the referenced frame dimensions
     * @throws AvifIoException if either dimension ratio is outside the permitted range
     */
    fun validateReferenceDimensions(
        currentSize: FrameSize,
        referenceSize: FrameSize
    ) {
        requireNotNullCompat(currentSize, "currentSize")
        requireNotNullCompat(referenceSize, "referenceSize")
        val frameWidth = currentSize.codedWidth()
        val frameHeight = currentSize.height()
        val referenceWidth = referenceSize.upscaledWidth()
        val referenceHeight = referenceSize.height()

        if (2L * frameWidth < referenceWidth || 2L * frameHeight < referenceHeight) {
            throw AvifIoException("Referenced frame dimensions exceed twice the current frame dimensions")
        }
        if (frameWidth > 16L * referenceWidth || frameHeight > 16L * referenceHeight) {
            throw AvifIoException("Current frame dimensions exceed sixteen times the referenced frame dimensions")
        }
    }

    /** Returns the AV1 level-limit row for a defined level.
     * 
     * Undefined level indices intentionally return `null`; the specification does not map them to
     * a parameter-limit row.
     * 
     * @param majorLevel the declared major level
     * @param minorLevel the declared minor level
     * @return the corresponding level limits, or `null` for an undefined level index
     */
    private fun levelLimits(majorLevel: Int, minorLevel: Int): LevelLimits? {
        return when (majorLevel * 10 + minorLevel) {
            20 -> LevelLimits(147456, 2048, 1152, 8, 4)
            21 -> LevelLimits(278784, 2816, 1584, 8, 4)
            30 -> LevelLimits(665856, 4352, 2448, 16, 6)
            31 -> LevelLimits(1065024, 5504, 3096, 16, 6)
            40, 41 -> LevelLimits(2359296, 6144, 3456, 32, 8)
            50, 51, 52, 53 -> LevelLimits(8912896, 8192, 4352, 64, 8)
            60, 61, 62, 63 -> LevelLimits(35651584, 16384, 8704, 128, 16)
            else -> null
        }
    }

    /** Defines the frame-size and tile-count limits for one AV1 level row.
     * 
     * @param maximumPictureSize the maximum luma picture area
     * @param maximumHorizontalSize the maximum upscaled frame width
     * @param maximumVerticalSize the maximum frame height
     * @param maximumTileCount the maximum number of tiles
     * @param maximumTileColumns the maximum number of tile columns
     */
    data class LevelLimits(
        val maximumPictureSize: Int,
        val maximumHorizontalSize: Int,
        val maximumVerticalSize: Int,
        val maximumTileCount: Int,
        val maximumTileColumns: Int
    )
}
