// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.parse

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.av1.Av1DecodeErrorCode
import org.glavo.avif.av1.Av1DecodeException
import org.glavo.avif.av1.Av1DecodeStage
import org.glavo.avif.av1.Av1FrameType
import org.glavo.avif.internal.av1.bitstream.BitReader
import org.glavo.avif.internal.av1.bitstream.ObuPacket
import org.glavo.avif.internal.av1.bitstream.ObuType
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.FrameHeader.CdefInfo
import org.glavo.avif.internal.av1.model.FrameHeader.FilmGrainParams
import org.glavo.avif.internal.av1.model.FrameHeader.FilmGrainPoint
import org.glavo.avif.internal.av1.model.FrameHeader.FrameSize
import org.glavo.avif.internal.av1.model.FrameHeader.GlobalMotionParams
import org.glavo.avif.internal.av1.model.FrameHeader.GlobalMotionType
import org.glavo.avif.internal.av1.model.FrameHeader.InterpolationFilter
import org.glavo.avif.internal.av1.model.FrameHeader.LoopFilterInfo
import org.glavo.avif.internal.av1.model.FrameHeader.QuantizationInfo
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationInfo
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationType
import org.glavo.avif.internal.av1.model.FrameHeader.SegmentData
import org.glavo.avif.internal.av1.model.FrameHeader.SegmentationInfo
import org.glavo.avif.internal.av1.model.FrameHeader.SuperResolutionInfo
import org.glavo.avif.internal.av1.model.FrameHeader.TilingInfo
import org.glavo.avif.internal.av1.model.SequenceHeader
import org.glavo.avif.internal.av1.model.SequenceHeader.AdaptiveBoolean


/** Parser for AV1 frame headers carried by standalone or combined frame OBUs. */
object FrameHeaderParser {
    /** The AV1 `primary_ref_none` sentinel value. */
    private const val PRIMARY_REF_NONE = 7

    /** The AV1 maximum tile column count. */
    private const val MAX_TILE_COLUMNS = 64

    /** The AV1 maximum tile row count. */
    private const val MAX_TILE_ROWS = 64

    /** The AV1 segment count. */
    private const val MAX_SEGMENTS = 8

    /** The AV1 total reference-frame slot count. */
    private const val TOTAL_REFERENCE_FRAMES = 8

    /** The AV1 reference-frame count signaled per frame. */
    private const val REFERENCES_PER_FRAME = 7

    /** The subexponential group-width parameter used by AV1 global motion. */
    private const val GLOBAL_MOTION_SUBEXP_K = 3

    /** The fixed-point precision of AV1 global-motion matrix entries. */
    private const val GLOBAL_MOTION_MATRIX_PRECISION = 16

    /** The signed alpha parameter magnitude bit count used by AV1 global motion. */
    private const val GLOBAL_MOTION_ALPHA_BITS = 12

    /** The AV1 maximum CDEF strength count. */
    private const val MAX_CDEF_STRENGTHS = 8

    /** The AV1 maximum luma film grain point count. */
    private const val MAX_FILM_GRAIN_Y_POINTS = 14

    /** The AV1 maximum chroma film grain point count. */
    private const val MAX_FILM_GRAIN_UV_POINTS = 10

    /** The default loop filter reference deltas. */
    private val DEFAULT_REFERENCE_DELTAS: IntArray =
        intArrayOf(1, 0, 0, 0, -1, 0, -1, -1)

    /** The default loop filter mode deltas. */
    private val DEFAULT_MODE_DELTAS: IntArray = intArrayOf(0, 0)

    /** Parses a standalone AV1 frame header OBU with access to previously refreshed reference headers.
     * 
     * @param obu the standalone frame header OBU
     * @param sequenceHeader the active sequence header
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @return the parsed frame header
     * @throws AvifIoException if the OBU is truncated, unreadable, or invalid
     */
    /** Parses a standalone AV1 frame header OBU.
     * 
     * @param obu the standalone frame header OBU
     * @param sequenceHeader the active sequence header
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @return the parsed frame header
     * @throws AvifIoException if the OBU is truncated, unreadable, or invalid
     */
    fun parse(
        obu: ObuPacket,
        sequenceHeader: SequenceHeader,
        strictStdCompliance: Boolean,
        referenceFrameHeaders: Array<FrameHeader?>? = arrayOfNullsCompat<FrameHeader?>(
            TOTAL_REFERENCE_FRAMES
        )
    ): FrameHeader {
        requireNotNullCompat(obu, "obu")
        requireNotNullCompat(sequenceHeader, "sequenceHeader")
        validateReferenceFrameHeaders(referenceFrameHeaders)
        require(obu.header.type == ObuType.FRAME_HEADER) { "OBU type is not a standalone frame header: " + obu.header.type }

        val reader = BitReader(obu.payload)
        val header = parseFramePayload(
            reader,
            obu,
            sequenceHeader,
            strictStdCompliance,
            referenceFrameHeaders
        )
        try {
            checkTrailingBits(reader, strictStdCompliance)
            return header
        } catch (ex: EOFException) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                Av1DecodeStage.FRAME_HEADER_PARSE,
                "Unexpected end of frame header payload",
                obu.streamOffset,
                obu.obuIndex,
                null,
                ex
            )
        } catch (ex: Av1DecodeException) {
            throw ex
        } catch (ex: AvifIoException) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.FRAME_HEADER_PARSE,
                ex.message,
                obu.streamOffset,
                obu.obuIndex,
                null,
                ex
            )
        }
    }

    /** Parses a frame header payload from a `FRAME_HEADER` or `FRAME` OBU using refreshed reference headers.
     * 
     * @param reader the payload bit reader positioned at the frame header
     * @param obu the source OBU packet
     * @param sequenceHeader the active sequence header
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @return the parsed frame header
     * @throws AvifIoException if the payload is truncated, unreadable, or invalid
     */
    /** Parses a frame header payload from a `FRAME_HEADER` or `FRAME` OBU.
     * 
     * @param reader the payload bit reader positioned at the frame header
     * @param obu the source OBU packet
     * @param sequenceHeader the active sequence header
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @return the parsed frame header
     * @throws AvifIoException if the payload is truncated, unreadable, or invalid
     */
    fun parseFramePayload(
        reader: BitReader,
        obu: ObuPacket,
        sequenceHeader: SequenceHeader,
        strictStdCompliance: Boolean,
        referenceFrameHeaders: Array<FrameHeader?>? = arrayOfNullsCompat<FrameHeader?>(
            TOTAL_REFERENCE_FRAMES
        )
    ): FrameHeader {
        requireNotNullCompat(reader, "reader")
        requireNotNullCompat(obu, "obu")
        requireNotNullCompat(sequenceHeader, "sequenceHeader")
        validateReferenceFrameHeaders(referenceFrameHeaders)
        val type = obu.header.type
        require(!(type != ObuType.FRAME_HEADER && type != ObuType.FRAME)) { "OBU type does not carry a frame header payload: $type" }

        try {
            return parse(reader, obu, sequenceHeader, strictStdCompliance, referenceFrameHeaders)
        } catch (ex: EOFException) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                Av1DecodeStage.FRAME_HEADER_PARSE,
                "Unexpected end of frame header payload",
                obu.streamOffset,
                obu.obuIndex,
                null,
                ex
            )
        } catch (ex: Av1DecodeException) {
            throw ex
        } catch (ex: AvifIoException) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.FRAME_HEADER_PARSE,
                ex.message,
                obu.streamOffset,
                obu.obuIndex,
                null,
                ex
            )
        }
    }

    /** Parses the frame header payload.
     * 
     * @param reader the payload bit reader
     * @param obu the source OBU packet
     * @param sequenceHeader the active sequence header
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @return the parsed frame header
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun parse(
        reader: BitReader,
        obu: ObuPacket,
        sequenceHeader: SequenceHeader,
        strictStdCompliance: Boolean,
        referenceFrameHeaders: Array<FrameHeader?>?
    ): FrameHeader {
        val temporalId = obu.header.temporalId
        val spatialId = obu.header.spatialId

        var showExistingFrame = false
        var existingFrameIndex = 0
        var frameId: Long = 0
        var framePresentationDelay: Long = 0
        val frameType: Av1FrameType?
        val showFrame: Boolean
        val showableFrame: Boolean
        val errorResilientMode: Boolean

        if (!sequenceHeader.reducedStillPictureHeader()) {
            showExistingFrame = reader.readFlag()
        }
        if (showExistingFrame) {
            if (strictStdCompliance && obu.header.type == ObuType.FRAME) {
                fail("Combined frame OBUs must not signal show_existing_frame")
            }
            existingFrameIndex = readInt(reader, 3)
            val existingFrameHeader = referenceFrameHeaders!![existingFrameIndex]
            if (sequenceHeader.timingInfo().decoderModelInfoPresent()
                && !sequenceHeader.timingInfo().equalPictureInterval()
            ) {
                framePresentationDelay =
                    readLong(reader, sequenceHeader.timingInfo().framePresentationDelayLength())
            }
            if (sequenceHeader.frameIdNumbersPresent()) {
                frameId = readLong(reader, sequenceHeader.frameIdBits())
                if (existingFrameHeader != null && existingFrameHeader.frameId() != frameId) {
                    fail("show_existing_frame frame id does not match the referenced slot")
                }
            }

            val existingFrameType = if (existingFrameHeader != null)
                existingFrameHeader.frameType()
            else
                Av1FrameType.INTER
            val existingRefreshFrameFlags = if (existingFrameType == Av1FrameType.KEY) 0xFF else 0
            val existingFilmGrain = if (existingFrameHeader != null)
                existingFrameHeader.filmGrain()
            else
                FilmGrainParams.Companion.disabled()

            return FrameHeader(
                temporalId,
                spatialId,
                true,
                existingFrameIndex,
                frameId,
                framePresentationDelay,
                existingFrameType,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                PRIMARY_REF_NONE,
                0,
                existingRefreshFrameFlags,
                FrameSize(0, 0, 0, 0, 0),
                SuperResolutionInfo(false, 8),
                false,
                false,
                TilingInfo(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, IntArray(0), IntArray(0), 0),
                QuantizationInfo(0, 0, 0, 0, 0, 0, false, 0, 0, 0),
                SegmentationInfo(
                    false,
                    false,
                    false,
                    false,
                    defaultSegments(),
                    BooleanArray(MAX_SEGMENTS),
                    IntArray(MAX_SEGMENTS)
                ),
                FrameHeader.DeltaInfo(false, 0, false, 0, false),
                false,
                LoopFilterInfo(
                    intArrayOf(0, 0),
                    0,
                    0,
                    0,
                    true,
                    true,
                    DEFAULT_REFERENCE_DELTAS,
                    DEFAULT_MODE_DELTAS
                ),
                CdefInfo(0, 0, IntArray(0), IntArray(0)),
                RestorationInfo(
                    arrayOf<RestorationType>(
                        RestorationType.NONE,
                        RestorationType.NONE,
                        RestorationType.NONE
                    ),
                    0,
                    0
                ),
                FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY,
                false,
                existingFilmGrain
            )
        }

        if (sequenceHeader.reducedStillPictureHeader()) {
            frameType = Av1FrameType.KEY
            showFrame = true
        } else {
            frameType = decodeFrameType(readInt(reader, 2))
            showFrame = reader.readFlag()
        }

        if (showFrame) {
            if (sequenceHeader.timingInfo().decoderModelInfoPresent()
                && !sequenceHeader.timingInfo().equalPictureInterval()
            ) {
                framePresentationDelay =
                    readLong(reader, sequenceHeader.timingInfo().framePresentationDelayLength())
            }
            showableFrame = frameType != Av1FrameType.KEY
        } else {
            showableFrame = reader.readFlag()
        }

        errorResilientMode = (frameType == Av1FrameType.KEY && showFrame)
                || frameType == Av1FrameType.SWITCH || sequenceHeader.reducedStillPictureHeader()
                || reader.readFlag()

        val disableCdfUpdate = reader.readFlag()
        val allowScreenContentTools = readFrameScreenContentTools(reader, sequenceHeader)
        var forceIntegerMotionVectors = false
        if (allowScreenContentTools) {
            forceIntegerMotionVectors = readFrameForceIntegerMotionVectors(reader, sequenceHeader)
        }

        if (frameType == Av1FrameType.KEY || frameType == Av1FrameType.INTRA) {
            forceIntegerMotionVectors = true
        }

        if (sequenceHeader.frameIdNumbersPresent()) {
            frameId = readLong(reader, sequenceHeader.frameIdBits())
        }

        var frameSizeOverride = false
        if (!sequenceHeader.reducedStillPictureHeader()) {
            frameSizeOverride = frameType == Av1FrameType.SWITCH || reader.readFlag()
        }

        var frameOffset = 0
        if (sequenceHeader.features().orderHint()) {
            frameOffset = readInt(reader, sequenceHeader.features().orderHintBits())
        }

        val primaryRefFrame = if (!errorResilientMode && isInterOrSwitch(frameType))
            readInt(reader, 3)
        else
            PRIMARY_REF_NONE

        if (sequenceHeader.timingInfo().decoderModelInfoPresent()) {
            val bufferRemovalTimePresent = reader.readFlag()
            if (bufferRemovalTimePresent) {
                for (i in 0..<sequenceHeader.operatingPointCount()) {
                    val operatingPoint = sequenceHeader.operatingPoint(i)
                    if (operatingPoint.decoderModelParamPresent()) {
                        val idc = operatingPoint.idc()
                        val inTemporalLayer = ((idc shr temporalId) and 1) != 0
                        val inSpatialLayer = ((idc shr (spatialId + 8)) and 1) != 0
                        if (idc == 0 || (inTemporalLayer && inSpatialLayer)) {
                            readLong(reader, sequenceHeader.timingInfo().bufferRemovalDelayLength())
                        }
                    }
                }
            }
        }
        val refreshFrameFlags: Int
        var frameReferenceShortSignaling = false
        val referenceFrameIndices = intArrayOf(-1, -1, -1, -1, -1, -1, -1)
        val frameSizeResult: FrameSizeResult?
        var allowIntrabc = false
        var allowHighPrecisionMotionVectors = false
        var subpelFilterMode = InterpolationFilter.EIGHT_TAP_REGULAR
        var switchableMotionMode = false
        var useReferenceFrameMotionVectors = false

        if (!isInterOrSwitch(frameType)) {
            refreshFrameFlags =
                if (frameType == Av1FrameType.KEY && showFrame) 0xFF else readInt(reader, 8)
            if (refreshFrameFlags != 0xFF && errorResilientMode && sequenceHeader.features()
                    .orderHint()
            ) {
                skipReferenceOrderHints(reader, sequenceHeader)
            }
            if (strictStdCompliance && frameType == Av1FrameType.INTRA && refreshFrameFlags == 0xFF) {
                fail("Intra frame headers must not refresh all reference frames in strict mode")
            }

            frameSizeResult = readFrameSize(
                reader,
                sequenceHeader,
                frameSizeOverride,
                false,
                referenceFrameHeaders,
                referenceFrameIndices
            )
            if (allowScreenContentTools && !frameSizeResult.superResolution.enabled()) {
                allowIntrabc = reader.readFlag()
            }
        } else {
            refreshFrameFlags = if (frameType == Av1FrameType.SWITCH) 0xFF else readInt(reader, 8)
            if (errorResilientMode && sequenceHeader.features().orderHint()) {
                skipReferenceOrderHints(reader, sequenceHeader)
            }

            if (sequenceHeader.features().orderHint()) {
                frameReferenceShortSignaling = reader.readFlag()
                if (frameReferenceShortSignaling) {
                    deriveReferenceFrameIndices(
                        reader,
                        sequenceHeader,
                        frameOffset,
                        referenceFrameHeaders,
                        referenceFrameIndices
                    )
                }
            }

            for (i in 0..<REFERENCES_PER_FRAME) {
                if (!frameReferenceShortSignaling) {
                    referenceFrameIndices[i] = readInt(reader, 3)
                }
                if (sequenceHeader.frameIdNumbersPresent()) {
                    val deltaReferenceFrameId =
                        readLong(reader, sequenceHeader.deltaFrameIdBits()) + 1
                    val frameIdMask = (1L shl sequenceHeader.frameIdBits()) - 1
                    val referenceFrameId =
                        (frameId + (1L shl sequenceHeader.frameIdBits()) - deltaReferenceFrameId) and frameIdMask
                    val referenceFrameHeader = referenceFrameHeaders!![referenceFrameIndices[i]]
                    if (referenceFrameHeader != null && referenceFrameHeader.frameId() != referenceFrameId) {
                        fail("Reference frame id does not match the decoded slot")
                    }
                }
            }

            frameSizeResult = readFrameSize(
                reader,
                sequenceHeader,
                frameSizeOverride,
                !errorResilientMode && frameSizeOverride,
                referenceFrameHeaders,
                referenceFrameIndices
            )
            if (!forceIntegerMotionVectors) {
                allowHighPrecisionMotionVectors = reader.readFlag()
            }
            subpelFilterMode = parseInterpolationFilter(reader)
            switchableMotionMode = reader.readFlag()
            if (!errorResilientMode && sequenceHeader.features().refFrameMotionVectors()
                && sequenceHeader.features().orderHint()
            ) {
                useReferenceFrameMotionVectors = reader.readFlag()
            }
        }

        var refreshContext = false
        if (!sequenceHeader.reducedStillPictureHeader() && !disableCdfUpdate) {
            refreshContext = !reader.readFlag()
        }

        val tiling = parseTiling(reader, sequenceHeader, frameSizeResult.frameSize)
        val quantization = parseQuantization(reader, sequenceHeader)
        val segmentation = parseSegmentation(
            reader,
            primaryRefFrame,
            referenceFrameHeaders,
            referenceFrameIndices
        )
        val delta = parseDelta(reader, quantization.baseQIndex(), allowIntrabc)
        val losslessState = deriveLosslessState(quantization, segmentation)
        val loopFilter = parseLoopFilter(
            reader,
            sequenceHeader,
            primaryRefFrame,
            referenceFrameHeaders,
            referenceFrameIndices,
            allowIntrabc,
            losslessState.allLossless
        )
        val cdef = parseCdef(reader, sequenceHeader, allowIntrabc, losslessState.allLossless)
        val restoration = parseRestoration(
            reader,
            sequenceHeader,
            frameSizeResult.superResolution,
            allowIntrabc,
            losslessState.allLossless
        )
        val transformMode = if (losslessState.allLossless)
            FrameHeader.TransformMode.FOUR_BY_FOUR_ONLY
        else
            (if (reader.readFlag()) FrameHeader.TransformMode.SWITCHABLE else FrameHeader.TransformMode.LARGEST)
        val switchableCompoundReferences = isInterOrSwitch(frameType) && reader.readFlag()
        val skipMode = deriveSkipMode(
            reader,
            sequenceHeader,
            frameType,
            frameOffset,
            switchableCompoundReferences,
            referenceFrameHeaders,
            referenceFrameIndices
        )
        var warpedMotion = false
        if (!errorResilientMode && isInterOrSwitch(frameType) && sequenceHeader.features()
                .warpedMotion()
        ) {
            warpedMotion = reader.readFlag()
        }
        val reducedTransformSet = reader.readFlag()
        val globalMotionParameters = parseGlobalMotion(
            reader,
            frameType,
            allowHighPrecisionMotionVectors,
            primaryRefFrame,
            referenceFrameHeaders,
            referenceFrameIndices
        )
        val filmGrain = parseFilmGrain(
            reader,
            sequenceHeader,
            frameType,
            showFrame,
            showableFrame,
            referenceFrameHeaders,
            referenceFrameIndices
        )

        var parsedHeader = FrameHeader(
            temporalId,
            spatialId,
            false,
            0,
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
            frameReferenceShortSignaling,
            referenceFrameIndices,
            frameSizeResult.frameSize,
            frameSizeResult.superResolution,
            allowIntrabc,
            allowHighPrecisionMotionVectors,
            subpelFilterMode,
            switchableMotionMode,
            useReferenceFrameMotionVectors,
            refreshContext,
            tiling,
            quantization,
            losslessState.segmentation,
            delta,
            losslessState.allLossless,
            loopFilter,
            cdef,
            restoration,
            transformMode,
            switchableCompoundReferences,
            skipMode.allowed,
            skipMode.enabled,
            skipMode.referenceIndices,
            warpedMotion,
            reducedTransformSet,
            filmGrain
        )
        parsedHeader = parsedHeader.withGlobalMotionParameters(globalMotionParameters)
        if (strictStdCompliance) {
            FrameHeaderConformanceValidator.validate(
                sequenceHeader,
                parsedHeader,
                referenceFrameHeaders
            )
        }
        return parsedHeader
    }

    /** Validates the supplied reference-frame header array shape.
     * 
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     */
    private fun validateReferenceFrameHeaders(referenceFrameHeaders: Array<FrameHeader?>?) {
        requireNotNullCompat(referenceFrameHeaders, "referenceFrameHeaders")
        require(referenceFrameHeaders!!.size == TOTAL_REFERENCE_FRAMES) { "referenceFrameHeaders.length != 8: " + referenceFrameHeaders.size }
    }

    /** Consumes the error-resilient reference order-hint array when it is present.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @throws AvifIoException if the payload is truncated
     */
    private fun skipReferenceOrderHints(reader: BitReader, sequenceHeader: SequenceHeader) {
        for (i in 0..<TOTAL_REFERENCE_FRAMES) {
            readInt(reader, sequenceHeader.features().orderHintBits())
        }
    }

    /** Derives the reference-frame list from short-signaling syntax.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param frameOffset the current frame order hint
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceFrameIndices the destination LAST..ALTREF slot array
     * @throws AvifIoException if any referenced header is unavailable or invalid
     */
    private fun deriveReferenceFrameIndices(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        frameOffset: Int,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray
    ) {
        referenceFrameIndices[0] = readInt(reader, 3)
        referenceFrameIndices[3] = readInt(reader, 3)

        val referenceOffsets = IntArray(TOTAL_REFERENCE_FRAMES)
        val usedFrames = BooleanArray(TOTAL_REFERENCE_FRAMES)
        for (i in 0..<TOTAL_REFERENCE_FRAMES) {
            val referenceFrameHeader = referenceFrameHeaders!![i]
            if (referenceFrameHeader == null) {
                fail("Short-signaled references require all reference-frame headers to be available")
            }
            referenceOffsets[i] = getPocDiff(
                sequenceHeader.features().orderHintBits(),
                referenceFrameHeader!!.frameOffset(),
                frameOffset
            )
        }
        usedFrames[referenceFrameIndices[0]] = true
        usedFrames[referenceFrameIndices[3]] = true

        var referenceIndex = -1
        for (i in 0..<TOTAL_REFERENCE_FRAMES) {
            val offset = referenceOffsets[i]
            if (!usedFrames[i] && offset >= 0 && (referenceIndex < 0 || offset >= referenceOffsets[referenceIndex])) {
                referenceIndex = i
            }
        }
        if (referenceIndex >= 0) {
            referenceFrameIndices[6] = referenceIndex
            usedFrames[referenceIndex] = true
        }

        for (i in 4..5) {
            referenceIndex = -1
            for (j in 0..<TOTAL_REFERENCE_FRAMES) {
                val offset = referenceOffsets[j]
                if (!usedFrames[j] && offset >= 0 && (referenceIndex < 0 || offset < referenceOffsets[referenceIndex])) {
                    referenceIndex = j
                }
            }
            if (referenceIndex >= 0) {
                referenceFrameIndices[i] = referenceIndex
                usedFrames[referenceIndex] = true
            }
        }

        for (i in 0..<REFERENCES_PER_FRAME - 2) {
            val referenceFrame = when (i) {
                0 -> 1
                1 -> 2
                2 -> 4
                3 -> 5
                4 -> 6
                else -> throw AssertionError(i)
            }
            if (referenceFrameIndices[referenceFrame] >= 0) {
                continue
            }

            referenceIndex = -1
            for (j in 0..<TOTAL_REFERENCE_FRAMES) {
                val offset = referenceOffsets[j]
                if (!usedFrames[j] && offset < 0 && (referenceIndex < 0 || offset >= referenceOffsets[referenceIndex])) {
                    referenceIndex = j
                }
            }
            if (referenceIndex >= 0) {
                referenceFrameIndices[referenceFrame] = referenceIndex
                usedFrames[referenceIndex] = true
            }
        }

        var earliestReference = 0
        for (i in 1..<TOTAL_REFERENCE_FRAMES) {
            if (referenceOffsets[i] < referenceOffsets[earliestReference]) {
                earliestReference = i
            }
        }
        for (i in 0..<REFERENCES_PER_FRAME) {
            if (referenceFrameIndices[i] < 0) {
                referenceFrameIndices[i] = earliestReference
            }
        }
    }

    /** Parses the frame size and render size fields for intra and inter frames.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param frameSizeOverride whether frame size override signaling is enabled
     * @param useReferenceFrameSize whether the frame size may be copied from a reference frame
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceFrameIndices the decoded LAST..ALTREF slot indices
     * @return the parsed frame size and super-resolution state
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun readFrameSize(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        frameSizeOverride: Boolean,
        useReferenceFrameSize: Boolean,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray
    ): FrameSizeResult {
        val upscaledWidth: Int
        val height: Int
        val renderWidth: Int
        val renderHeight: Int
        if (useReferenceFrameSize) {
            for (i in 0..<REFERENCES_PER_FRAME) {
                if (reader.readFlag()) {
                    val referenceSlot = referenceFrameIndices[i]
                    if (referenceSlot < 0 || referenceSlot >= TOTAL_REFERENCE_FRAMES) {
                        fail("Reference-sized frames require a decoded reference-frame slot")
                    }
                    val referenceFrameHeader = referenceFrameHeaders!![referenceSlot]
                    if (referenceFrameHeader == null) {
                        fail("Reference-sized frames require the selected reference-frame header")
                    }
                    upscaledWidth = referenceFrameHeader!!.frameSize().upscaledWidth()
                    height = referenceFrameHeader.frameSize().height()
                    renderWidth = referenceFrameHeader.frameSize().renderWidth()
                    renderHeight = referenceFrameHeader.frameSize().renderHeight()

                    val superResolution = readSuperResolution(reader, sequenceHeader)
                    return FrameSizeResult(
                        FrameSize(
                            computeCodedWidth(
                                upscaledWidth,
                                superResolution.widthScaleDenominator()
                            ),
                            upscaledWidth,
                            height,
                            renderWidth,
                            renderHeight
                        ),
                        superResolution
                    )
                }
            }
        }

        if (frameSizeOverride) {
            upscaledWidth = readInt(reader, sequenceHeader.widthBits()) + 1
            height = readInt(reader, sequenceHeader.heightBits()) + 1
        } else {
            upscaledWidth = sequenceHeader.maxWidth()
            height = sequenceHeader.maxHeight()
        }

        val superResolution = readSuperResolution(reader, sequenceHeader)

        val haveRenderSize = reader.readFlag()
        if (haveRenderSize) {
            renderWidth = readInt(reader, 16) + 1
            renderHeight = readInt(reader, 16) + 1
        } else {
            renderWidth = upscaledWidth
            renderHeight = height
        }

        return FrameSizeResult(
            FrameSize(
                computeCodedWidth(upscaledWidth, superResolution.widthScaleDenominator()),
                upscaledWidth,
                height,
                renderWidth,
                renderHeight
            ),
            superResolution
        )
    }

    /** Parses frame-level super-resolution signaling.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @return the parsed super-resolution state
     * @throws AvifIoException if the payload is truncated
     */
    private fun readSuperResolution(
        reader: BitReader,
        sequenceHeader: SequenceHeader
    ): SuperResolutionInfo {
        val enabled = sequenceHeader.features().superResolution() && reader.readFlag()
        val widthScaleDenominator = if (enabled) 9 + readInt(reader, 3) else 8
        return SuperResolutionInfo(enabled, widthScaleDenominator)
    }

    /** Computes the coded width after optional super-resolution downscaling.
     * 
     * @param upscaledWidth the frame width before super-resolution downscaling
     * @param widthScaleDenominator the super-resolution width denominator
     * @return the coded width after super-resolution downscaling
     */
    private fun computeCodedWidth(upscaledWidth: Int, widthScaleDenominator: Int): Int {
        if (widthScaleDenominator == 8) {
            return upscaledWidth
        }
        return max(
            (upscaledWidth * 8 + (widthScaleDenominator shr 1)) / widthScaleDenominator,
            min(16, upscaledWidth)
        )
    }

    /** Parses tile layout information.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param frameSize the parsed frame dimensions
     * @return the parsed tile layout information
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun parseTiling(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        frameSize: FrameSize
    ): TilingInfo {
        val uniform = reader.readFlag()
        val superblockSizeMinusOne =
            (64 shl (if (sequenceHeader.features().use128x128Superblocks()) 1 else 0)) - 1
        val superblockLog2 = 6 + (if (sequenceHeader.features().use128x128Superblocks()) 1 else 0)
        val superblockWidth = (frameSize.codedWidth() + superblockSizeMinusOne) shr superblockLog2
        val superblockHeight = (frameSize.height() + superblockSizeMinusOne) shr superblockLog2
        val maxTileWidthSuperblocks = 4096 shr superblockLog2
        val maxTileAreaSuperblocks = (4096 * 2304) shr (2 * superblockLog2)
        val minLog2Cols = tileLog2(maxTileWidthSuperblocks, superblockWidth)
        val maxLog2Cols = tileLog2(1, min(superblockWidth, MAX_TILE_COLUMNS))
        val maxLog2Rows = tileLog2(1, min(superblockHeight, MAX_TILE_ROWS))
        val minLog2Tiles =
            max(tileLog2(maxTileAreaSuperblocks, superblockWidth * superblockHeight), minLog2Cols)

        var log2Cols: Int
        var columns = 0
        val columnStarts = IntArray(MAX_TILE_COLUMNS + 1)
        val minLog2Rows: Int
        var log2Rows: Int
        var rows = 0
        val rowStarts = IntArray(MAX_TILE_ROWS + 1)

        if (uniform) {
            log2Cols = minLog2Cols
            while (log2Cols < maxLog2Cols && reader.readFlag()) {
                log2Cols++
            }
            val tileWidth = 1 + ((superblockWidth - 1) shr log2Cols)
            var superblockX = 0
            while (superblockX < superblockWidth) {
                columnStarts[columns++] = superblockX
                superblockX += tileWidth
            }
            minLog2Rows = max(minLog2Tiles - log2Cols, 0)
            log2Rows = minLog2Rows
            while (log2Rows < maxLog2Rows && reader.readFlag()) {
                log2Rows++
            }
            val tileHeight = 1 + ((superblockHeight - 1) shr log2Rows)
            var superblockY = 0
            while (superblockY < superblockHeight) {
                rowStarts[rows++] = superblockY
                superblockY += tileHeight
            }
        } else {
            var widestTile = 0
            var remainingMaxTileArea = superblockWidth * superblockHeight

            var superblockX = 0
            while (superblockX < superblockWidth && columns < MAX_TILE_COLUMNS) {
                val tileWidthSuperblocks =
                    min(superblockWidth - superblockX, maxTileWidthSuperblocks)
                val tileWidth = if (tileWidthSuperblocks > 1)
                    1 + readUniform(reader, tileWidthSuperblocks)
                else
                    1
                columnStarts[columns++] = superblockX
                superblockX += tileWidth
                widestTile = max(widestTile, tileWidth)
            }

            log2Cols = tileLog2(1, columns)
            minLog2Rows = 0
            if (minLog2Tiles > 0) {
                remainingMaxTileArea = remainingMaxTileArea shr minLog2Tiles + 1
            }
            val maxTileHeightSuperblocks = max(remainingMaxTileArea / widestTile, 1)

            var superblockY = 0
            while (superblockY < superblockHeight && rows < MAX_TILE_ROWS) {
                val tileHeightSuperblocks =
                    min(superblockHeight - superblockY, maxTileHeightSuperblocks)
                val tileHeight = if (tileHeightSuperblocks > 1)
                    1 + readUniform(reader, tileHeightSuperblocks)
                else
                    1
                rowStarts[rows++] = superblockY
                superblockY += tileHeight
            }

            log2Rows = tileLog2(1, rows)
        }

        columnStarts[columns] = superblockWidth
        rowStarts[rows] = superblockHeight

        var updateTileIndex = 0
        var sizeBytes = 0
        if (log2Cols != 0 || log2Rows != 0) {
            updateTileIndex = readInt(reader, log2Cols + log2Rows)
            if (updateTileIndex >= columns * rows) {
                fail("Tile update index exceeds the number of tiles")
            }
            sizeBytes = readInt(reader, 2) + 1
        }

        return TilingInfo(
            uniform,
            sizeBytes,
            minLog2Cols,
            maxLog2Cols,
            log2Cols,
            columns,
            minLog2Rows,
            maxLog2Rows,
            log2Rows,
            rows,
            columnStarts.copyOf(columns + 1),
            rowStarts.copyOf(rows + 1),
            updateTileIndex
        )
    }

    /** Parses quantization parameters.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @return the parsed quantization parameters
     * @throws AvifIoException if the payload is truncated
     */
    private fun parseQuantization(
        reader: BitReader,
        sequenceHeader: SequenceHeader
    ): QuantizationInfo {
        val baseQIndex = readInt(reader, 8)
        val yDcDelta = if (reader.readFlag()) reader.readSignedBits(7) else 0

        var uDcDelta = 0
        var uAcDelta = 0
        var vDcDelta = 0
        var vAcDelta = 0
        if (sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.MONOCHROME) {
            val diffUvDelta = sequenceHeader.colorConfig().separateUvDeltaQ && reader.readFlag()
            if (reader.readFlag()) {
                uDcDelta = reader.readSignedBits(7)
            }
            if (reader.readFlag()) {
                uAcDelta = reader.readSignedBits(7)
            }
            if (diffUvDelta) {
                if (reader.readFlag()) {
                    vDcDelta = reader.readSignedBits(7)
                }
                if (reader.readFlag()) {
                    vAcDelta = reader.readSignedBits(7)
                }
            } else {
                vDcDelta = uDcDelta
                vAcDelta = uAcDelta
            }
        }

        val useQuantizationMatrices = reader.readFlag()
        var qmY = 0
        var qmU = 0
        var qmV = 0
        if (useQuantizationMatrices) {
            qmY = readInt(reader, 4)
            qmU = readInt(reader, 4)
            qmV = if (sequenceHeader.colorConfig().separateUvDeltaQ) readInt(reader, 4) else qmU
        }

        return QuantizationInfo(
            baseQIndex,
            yDcDelta,
            uDcDelta,
            uAcDelta,
            vDcDelta,
            vAcDelta,
            useQuantizationMatrices,
            qmY,
            qmU,
            qmV
        )
    }

    /** Resolves the refreshed frame header addressed by `primary_ref_frame`.
     * 
     * @param primaryRefFrame the primary reference-frame position, or `7` when none is used
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceFrameIndices the decoded LAST..ALTREF slot indices
     * @return the refreshed frame header addressed by `primary_ref_frame`, or `null` when none is used
     * @throws AvifIoException if `primary_ref_frame` requires a missing reference header
     */
    private fun resolvePrimaryReferenceFrameHeader(
        primaryRefFrame: Int,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray
    ): FrameHeader? {
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            return null
        }
        val referenceSlot = referenceFrameIndices[primaryRefFrame]
        if (referenceSlot < 0 || referenceSlot >= TOTAL_REFERENCE_FRAMES) {
            fail("primary_ref_frame does not resolve to a valid reference slot")
        }
        val referenceFrameHeader = referenceFrameHeaders!![referenceSlot]
        if (referenceFrameHeader == null) {
            fail("primary_ref_frame requires an available refreshed reference header")
        }
        return referenceFrameHeader
    }

    /** Parses segmentation parameters, optionally inheriting segment data from the primary reference frame.
     * 
     * @param reader the payload bit reader
     * @param primaryRefFrame the primary reference frame index
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceFrameIndices the decoded LAST..ALTREF slot indices
     * @return the parsed segmentation parameters
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun parseSegmentation(
        reader: BitReader,
        primaryRefFrame: Int,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray
    ): SegmentationInfo {
        val enabled = reader.readFlag()
        var segments = defaultSegments()
        val losslessBySegment = BooleanArray(MAX_SEGMENTS)
        val qIndexBySegment = IntArray(MAX_SEGMENTS)

        if (!enabled) {
            return SegmentationInfo(
                false,
                false,
                false,
                false,
                false,
                -1,
                segments,
                losslessBySegment,
                qIndexBySegment
            )
        }

        val updateMap: Boolean
        var temporalUpdate = false
        val updateData: Boolean
        if (primaryRefFrame == PRIMARY_REF_NONE) {
            updateMap = true
            updateData = true
        } else {
            updateMap = reader.readFlag()
            if (updateMap) {
                temporalUpdate = reader.readFlag()
            }
            updateData = reader.readFlag()
        }

        var preskip: Boolean
        var lastActiveSegmentId: Int
        if (updateData) {
            preskip = false
            lastActiveSegmentId = 0
            for (i in 0..<MAX_SEGMENTS) {
                val deltaQEnabled = reader.readFlag()
                val deltaQ = if (deltaQEnabled) reader.readSignedBits(9) else 0
                val deltaLfYVerticalEnabled = reader.readFlag()
                val deltaLfYVertical = if (deltaLfYVerticalEnabled) reader.readSignedBits(7) else 0
                val deltaLfYHorizontalEnabled = reader.readFlag()
                val deltaLfYHorizontal =
                    if (deltaLfYHorizontalEnabled) reader.readSignedBits(7) else 0
                val deltaLfUEnabled = reader.readFlag()
                val deltaLfU = if (deltaLfUEnabled) reader.readSignedBits(7) else 0
                val deltaLfVEnabled = reader.readFlag()
                val deltaLfV = if (deltaLfVEnabled) reader.readSignedBits(7) else 0
                val referenceFrameEnabled = reader.readFlag()
                val referenceFrame = if (referenceFrameEnabled) readInt(reader, 3) else -1
                val skip = reader.readFlag()
                val globalMotion = reader.readFlag()

                if (deltaQEnabled
                    || deltaLfYVerticalEnabled
                    || deltaLfYHorizontalEnabled
                    || deltaLfUEnabled
                    || deltaLfVEnabled
                    || referenceFrameEnabled
                    || skip
                    || globalMotion
                ) {
                    lastActiveSegmentId = i
                }
                if (referenceFrameEnabled || skip || globalMotion) {
                    preskip = true
                }

                segments[i] = SegmentData(
                    deltaQ,
                    deltaLfYVertical,
                    deltaLfYHorizontal,
                    deltaLfU,
                    deltaLfV,
                    referenceFrame,
                    skip,
                    globalMotion
                )
            }
        } else {
            val referenceFrameHeader = resolvePrimaryReferenceFrameHeader(
                primaryRefFrame,
                referenceFrameHeaders,
                referenceFrameIndices
            )
            if (referenceFrameHeader == null) {
                fail("Segmentation data cannot be inherited without a primary reference frame")
            }
            val referencedSegmentation = referenceFrameHeader!!.segmentation()
            preskip = referencedSegmentation.preskip()
            lastActiveSegmentId = referencedSegmentation.lastActiveSegmentId()
            segments = referencedSegmentation.segments()
        }

        return SegmentationInfo(
            enabled,
            updateMap,
            temporalUpdate,
            updateData,
            preskip,
            lastActiveSegmentId,
            segments,
            losslessBySegment,
            qIndexBySegment
        )
    }

    /** Parses delta-q and delta-lf signaling flags.
     * 
     * @param reader the payload bit reader
     * @param baseQIndex the base luma AC quantizer index
     * @param allowIntrabc whether `allow_intrabc` is enabled
     * @return the parsed delta-q and delta-lf signaling flags
     * @throws AvifIoException if the payload is truncated
     */
    private fun parseDelta(
        reader: BitReader,
        baseQIndex: Int,
        allowIntrabc: Boolean
    ): FrameHeader.DeltaInfo {
        if (baseQIndex == 0) {
            return FrameHeader.DeltaInfo(false, 0, false, 0, false)
        }

        val deltaQPresent = reader.readFlag()
        if (!deltaQPresent) {
            return FrameHeader.DeltaInfo(false, 0, false, 0, false)
        }

        val deltaQResLog2 = readInt(reader, 2)
        var deltaLfPresent = false
        var deltaLfResLog2 = 0
        var deltaLfMulti = false
        if (!allowIntrabc) {
            deltaLfPresent = reader.readFlag()
            if (deltaLfPresent) {
                deltaLfResLog2 = readInt(reader, 2)
                deltaLfMulti = reader.readFlag()
            }
        }
        return FrameHeader.DeltaInfo(
            deltaQPresent,
            deltaQResLog2,
            deltaLfPresent,
            deltaLfResLog2,
            deltaLfMulti
        )
    }

    /** Derives per-segment lossless flags and qindex values.
     * 
     * @param quantization the parsed quantization parameters
     * @param segmentation the parsed segmentation parameters
     * @return the derived lossless state
     */
    private fun deriveLosslessState(
        quantization: QuantizationInfo,
        segmentation: SegmentationInfo
    ): DerivedLosslessState {
        val deltaLossless =
            quantization.yDcDelta() == 0 && quantization.uDcDelta() == 0 && quantization.uAcDelta() == 0 && quantization.vDcDelta() == 0 && quantization.vAcDelta() == 0

        val losslessBySegment = BooleanArray(MAX_SEGMENTS)
        val qIndexBySegment = IntArray(MAX_SEGMENTS)
        var allLossless = true
        for (i in 0..<MAX_SEGMENTS) {
            val qIndex = if (segmentation.enabled())
                clipUnsignedByte(quantization.baseQIndex() + segmentation.segment(i).deltaQ())
            else
                quantization.baseQIndex()
            qIndexBySegment[i] = qIndex
            val lossless = qIndex == 0 && deltaLossless
            losslessBySegment[i] = lossless
            allLossless = allLossless and lossless
        }

        val updatedSegmentation = SegmentationInfo(
            segmentation.enabled(),
            segmentation.updateMap(),
            segmentation.temporalUpdate(),
            segmentation.updateData(),
            segmentation.preskip(),
            segmentation.lastActiveSegmentId(),
            segmentation.segments(),
            losslessBySegment,
            qIndexBySegment
        )
        return DerivedLosslessState(updatedSegmentation, allLossless)
    }

    /** Parses loop filter parameters.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param primaryRefFrame the primary reference-frame position, or `7` when none is used
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceFrameIndices the decoded LAST..ALTREF slot indices
     * @param allowIntrabc whether `allow_intrabc` is enabled
     * @param allLossless whether all segments are lossless
     * @return the parsed loop filter parameters
     * @throws AvifIoException if the payload is truncated
     */
    private fun parseLoopFilter(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        primaryRefFrame: Int,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray,
        allowIntrabc: Boolean,
        allLossless: Boolean
    ): LoopFilterInfo {
        val levelY = intArrayOf(0, 0)
        var levelU = 0
        var levelV = 0
        var sharpness = 0
        var modeRefDeltaEnabled = true
        var modeRefDeltaUpdate = true
        var referenceDeltas = DEFAULT_REFERENCE_DELTAS.copyOf()
        var modeDeltas = DEFAULT_MODE_DELTAS.copyOf()

        if (!allLossless && !allowIntrabc) {
            levelY[0] = readInt(reader, 6)
            levelY[1] = readInt(reader, 6)
            if (sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.MONOCHROME
                && (levelY[0] != 0 || levelY[1] != 0)
            ) {
                levelU = readInt(reader, 6)
                levelV = readInt(reader, 6)
            }
            sharpness = readInt(reader, 3)

            if (primaryRefFrame != PRIMARY_REF_NONE) {
                val referenceFrameHeader = resolvePrimaryReferenceFrameHeader(
                    primaryRefFrame,
                    referenceFrameHeaders,
                    referenceFrameIndices
                )
                if (referenceFrameHeader == null) {
                    fail("Loop-filter state cannot be inherited without a primary reference frame")
                }
                referenceDeltas = referenceFrameHeader!!.loopFilter().referenceDeltas()
                modeDeltas = referenceFrameHeader.loopFilter().modeDeltas()
            }

            modeRefDeltaEnabled = reader.readFlag()
            modeRefDeltaUpdate = false
            if (modeRefDeltaEnabled) {
                modeRefDeltaUpdate = reader.readFlag()
                if (modeRefDeltaUpdate) {
                    for (i in referenceDeltas.indices) {
                        if (reader.readFlag()) {
                            referenceDeltas[i] = reader.readSignedBits(7)
                        }
                    }
                    for (i in modeDeltas.indices) {
                        if (reader.readFlag()) {
                            modeDeltas[i] = reader.readSignedBits(7)
                        }
                    }
                }
            }
        }

        return LoopFilterInfo(
            levelY,
            levelU,
            levelV,
            sharpness,
            modeRefDeltaEnabled,
            modeRefDeltaUpdate,
            referenceDeltas,
            modeDeltas
        )
    }

    /** Parses CDEF parameters.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param allowIntrabc whether `allow_intrabc` is enabled
     * @param allLossless whether all segments are lossless
     * @return the parsed CDEF parameters
     * @throws AvifIoException if the payload is truncated
     */
    private fun parseCdef(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        allowIntrabc: Boolean,
        allLossless: Boolean
    ): CdefInfo {
        if (allLossless || !sequenceHeader.features().cdef() || allowIntrabc) {
            return CdefInfo(0, 0, IntArray(0), IntArray(0))
        }

        val damping = readInt(reader, 2) + 3
        val bits = readInt(reader, 2)
        val count = 1 shl bits
        if (count > MAX_CDEF_STRENGTHS) {
            fail("CDEF strength count exceeds the supported maximum")
        }

        val yStrengths = IntArray(count)
        val uvStrengths = IntArray(count)
        for (i in 0..<count) {
            yStrengths[i] = readInt(reader, 6)
            if (sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.MONOCHROME) {
                uvStrengths[i] = readInt(reader, 6)
            }
        }
        return CdefInfo(damping, bits, yStrengths, uvStrengths)
    }

    /** Parses loop restoration parameters.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param superResolution the parsed super-resolution settings
     * @param allowIntrabc whether `allow_intrabc` is enabled
     * @param allLossless whether all segments are lossless
     * @return the parsed loop restoration parameters
     * @throws AvifIoException if the payload is truncated
     */
    private fun parseRestoration(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        superResolution: SuperResolutionInfo,
        allowIntrabc: Boolean,
        allLossless: Boolean
    ): RestorationInfo {
        val types = arrayOf<RestorationType>(
            RestorationType.NONE,
            RestorationType.NONE,
            RestorationType.NONE
        )
        if ((allLossless && !superResolution.enabled()) || !sequenceHeader.features()
                .restoration() || allowIntrabc
        ) {
            return RestorationInfo(types, 0, 0)
        }

        types[0] = decodeRestorationType(readInt(reader, 2))
        if (sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.MONOCHROME) {
            types[1] = decodeRestorationType(readInt(reader, 2))
            types[2] = decodeRestorationType(readInt(reader, 2))
        }

        var unitSizeLog2Y = 8
        var unitSizeLog2Uv = 8
        if (types[0] != RestorationType.NONE || types[1] != RestorationType.NONE || types[2] != RestorationType.NONE) {
            unitSizeLog2Y = 6 + (if (sequenceHeader.features().use128x128Superblocks()) 1 else 0)
            if (reader.readFlag()) {
                unitSizeLog2Y++
                if (!sequenceHeader.features().use128x128Superblocks()) {
                    unitSizeLog2Y += if (reader.readFlag()) 1 else 0
                }
            }
            unitSizeLog2Uv = unitSizeLog2Y
            if ((types[1] != RestorationType.NONE || types[2] != RestorationType.NONE)
                && sequenceHeader.colorConfig().chromaSubsamplingX
                && sequenceHeader.colorConfig().chromaSubsamplingY
            ) {
                unitSizeLog2Uv -= if (reader.readFlag()) 1 else 0
            }
        }

        return RestorationInfo(types, unitSizeLog2Y, unitSizeLog2Uv)
    }

    /** Parses the seven frame-level global-motion parameter sets.
     * 
     * Inter and switch frames inherit each parameter reference from `primary_ref_frame`; other
     * frame types use identity parameters without consuming global-motion syntax.
     * 
     * @param reader the payload bit reader
     * @param frameType the decoded frame type
     * @param allowHighPrecisionMotionVectors whether high-precision motion vectors are allowed
     * @param primaryRefFrame the primary reference position, or `7` when absent
     * @param referenceFrameHeaders the refreshed reference headers indexed by slot
     * @param referenceFrameIndices the current LAST-through-ALTREF slot selection
     * @return the seven decoded global-motion parameter sets
     * @throws AvifIoException if the payload is truncated or inherited state is unavailable
     */
    private fun parseGlobalMotion(
        reader: BitReader,
        frameType: Av1FrameType,
        allowHighPrecisionMotionVectors: Boolean,
        primaryRefFrame: Int,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray
    ): Array<GlobalMotionParams> {
        val parameters: Array<GlobalMotionParams> =
            arrayOfNullsCompat<GlobalMotionParams>(REFERENCES_PER_FRAME)
        if (!isInterOrSwitch(frameType)) {
            fillCompat(parameters, GlobalMotionParams.Companion.identity())
            return parameters
        }

        val primaryReferenceHeader = resolvePrimaryReferenceFrameHeader(
            primaryRefFrame,
            referenceFrameHeaders,
            referenceFrameIndices
        )
        if (primaryRefFrame != PRIMARY_REF_NONE && primaryReferenceHeader == null) {
            fail("Global motion parameters cannot be inherited without a primary reference frame")
        }

        for (referenceFrame in parameters.indices) {
            val referenceParameters = if (primaryReferenceHeader == null)
                GlobalMotionParams.Companion.identity()
            else
                primaryReferenceHeader.globalMotion(referenceFrame)
            parameters[referenceFrame] = parseGlobalMotionParameters(
                reader,
                referenceParameters,
                allowHighPrecisionMotionVectors
            )
        }
        return parameters
    }

    /** Parses one global-motion parameter set relative to inherited reference parameters.
     * 
     * @param reader the payload bit reader
     * @param referenceParameters the inherited parameters used for subexponential recentering
     * @param allowHighPrecisionMotionVectors whether high-precision motion vectors are allowed
     * @return the decoded global-motion parameters
     * @throws AvifIoException if the payload is truncated
     */
    private fun parseGlobalMotionParameters(
        reader: BitReader,
        referenceParameters: GlobalMotionParams,
        allowHighPrecisionMotionVectors: Boolean
    ): GlobalMotionParams {
        if (!reader.readFlag()) {
            return GlobalMotionParams.Companion.identity()
        }

        val type: GlobalMotionType?
        if (reader.readFlag()) {
            type = GlobalMotionType.ROTATION_ZOOM
        } else {
            type = if (reader.readFlag())
                GlobalMotionType.TRANSLATION
            else
                GlobalMotionType.AFFINE
        }

        val matrix: IntArray = GlobalMotionParams.Companion.identity().matrix()
        val translationBits: Int
        val translationShift: Int
        if (type == GlobalMotionType.ROTATION_ZOOM || type == GlobalMotionType.AFFINE) {
            val identityScale = 1 shl GLOBAL_MOTION_MATRIX_PRECISION
            matrix[2] = identityScale + 2 * readSignedSubexpWithReference(
                reader,
                GLOBAL_MOTION_ALPHA_BITS,
                (referenceParameters.matrix(2) - identityScale) shr 1
            )
            matrix[3] = 2 * readSignedSubexpWithReference(
                reader,
                GLOBAL_MOTION_ALPHA_BITS,
                referenceParameters.matrix(3) shr 1
            )
            translationBits = 12
            translationShift = 10
        } else {
            val precisionReduction = if (allowHighPrecisionMotionVectors) 0 else 1
            translationBits = 9 - precisionReduction
            translationShift = 13 + precisionReduction
        }

        if (type == GlobalMotionType.AFFINE) {
            val identityScale = 1 shl GLOBAL_MOTION_MATRIX_PRECISION
            matrix[4] = 2 * readSignedSubexpWithReference(
                reader,
                GLOBAL_MOTION_ALPHA_BITS,
                referenceParameters.matrix(4) shr 1
            )
            matrix[5] = identityScale + 2 * readSignedSubexpWithReference(
                reader,
                GLOBAL_MOTION_ALPHA_BITS,
                (referenceParameters.matrix(5) - identityScale) shr 1
            )
        } else if (type == GlobalMotionType.ROTATION_ZOOM) {
            matrix[4] = -matrix[3]
            matrix[5] = matrix[2]
        }

        matrix[0] = readSignedSubexpWithReference(
            reader,
            translationBits,
            referenceParameters.matrix(0) shr translationShift
        ) shl translationShift
        matrix[1] = readSignedSubexpWithReference(
            reader,
            translationBits,
            referenceParameters.matrix(1) shr translationShift
        ) shl translationShift
        return GlobalMotionParams(type, matrix)
    }

    /** Reads a signed AV1 subexponential value recentered around a reference.
     * 
     * @param reader the payload bit reader
     * @param magnitudeBits the signed magnitude bit count
     * @param reference the signed recentering reference
     * @return the decoded signed value
     * @throws AvifIoException if the payload is truncated
     */
    private fun readSignedSubexpWithReference(
        reader: BitReader,
        magnitudeBits: Int,
        reference: Int
    ): Int {
        val magnitude = 1 shl magnitudeBits
        val maximum = magnitude shl 1
        val unsignedReference = reference + magnitude
        if (unsignedReference < 0 || unsignedReference > maximum) {
            fail("Global motion subexponential reference is out of range: $reference")
        }
        return readUnsignedSubexpWithReference(reader, maximum, unsignedReference) - magnitude
    }

    /** Reads an unsigned finite subexponential value recentered around a reference.
     * 
     * @param reader the payload bit reader
     * @param maximum the inclusive decoded maximum
     * @param reference the unsigned recentering reference in `[0, maximum]`
     * @return the decoded unsigned value
     * @throws AvifIoException if the payload is truncated
     */
    private fun readUnsignedSubexpWithReference(
        reader: BitReader,
        maximum: Int,
        reference: Int
    ): Int {
        var value = 0
        var group = 0
        while (true) {
            val bits =
                if (group == 0) GLOBAL_MOTION_SUBEXP_K else GLOBAL_MOTION_SUBEXP_K + group - 1
            val groupSize = 1 shl bits
            if (maximum < value + 3 * groupSize) {
                value += readUniform(reader, maximum - value + 1)
                break
            }
            if (!reader.readFlag()) {
                value += readInt(reader, bits)
                break
            }
            value += groupSize
            group++
        }

        return if (reference * 2 <= maximum)
            inverseRecenter(reference, value)
        else
            maximum - inverseRecenter(maximum - reference, value)
    }

    /** Applies the AV1 inverse non-negative recentering transform.
     * 
     * @param reference the non-negative recentering reference
     * @param value the coded non-negative value
     * @return the inverse-recentered value
     */
    private fun inverseRecenter(reference: Int, value: Int): Int {
        if (value > (reference shl 1)) {
            return value
        }
        return if ((value and 1) == 0)
            (value shr 1) + reference
        else
            reference - ((value + 1) shr 1)
    }

    /** Parses normalized film grain parameters.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param frameType the current AV1 frame type
     * @param showFrame whether the frame is shown immediately
     * @param showableFrame whether the frame can be shown later
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceFrameIndices the decoded LAST..ALTREF slot indices
     * @return the normalized film grain parameters
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun parseFilmGrain(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        frameType: Av1FrameType,
        showFrame: Boolean,
        showableFrame: Boolean,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray
    ): FilmGrainParams {
        if (!sequenceHeader.features().filmGrainPresent() || (!showFrame && !showableFrame)) {
            return FilmGrainParams.Companion.disabled()
        }

        if (!reader.readFlag()) {
            return FilmGrainParams.Companion.disabled()
        }

        val grainSeed = readInt(reader, 16)
        val updated = frameType != Av1FrameType.INTER || reader.readFlag()
        if (!updated) {
            val referenceFrameIndex = readInt(reader, 3)
            if (!usesReferenceFrameIndex(referenceFrameIndices, referenceFrameIndex)) {
                fail("film_grain_params_ref_idx must match one of the frame reference slots")
            }

            val referenceFrameHeader = referenceFrameHeaders!![referenceFrameIndex]
            if (referenceFrameHeader == null) {
                fail("film_grain_params_ref_idx requires an available refreshed reference header")
            }

            val referencedFilmGrain = referenceFrameHeader!!.filmGrain()
            return FilmGrainParams(
                true,
                grainSeed,
                false,
                referenceFrameIndex,
                referencedFilmGrain.yPoints(),
                referencedFilmGrain.chromaScalingFromLuma(),
                referencedFilmGrain.cbPoints(),
                referencedFilmGrain.crPoints(),
                referencedFilmGrain.scalingShift(),
                referencedFilmGrain.arCoeffLag(),
                referencedFilmGrain.arCoefficientsY(),
                referencedFilmGrain.arCoefficientsCb(),
                referencedFilmGrain.arCoefficientsCr(),
                referencedFilmGrain.arCoeffShift(),
                referencedFilmGrain.grainScaleShift(),
                referencedFilmGrain.cbMult(),
                referencedFilmGrain.cbLumaMult(),
                referencedFilmGrain.cbOffset(),
                referencedFilmGrain.crMult(),
                referencedFilmGrain.crLumaMult(),
                referencedFilmGrain.crOffset(),
                referencedFilmGrain.overlapEnabled(),
                referencedFilmGrain.clipToRestrictedRange()
            )
        }

        val numYPoints = readInt(reader, 4)
        if (numYPoints > MAX_FILM_GRAIN_Y_POINTS) {
            fail("Film grain luma point count exceeds the AV1 maximum of 14")
        }
        val yPoints = parseFilmGrainPoints(reader, numYPoints, "luma")

        val monochrome = sequenceHeader.colorConfig().monochrome
        val chromaScalingFromLuma = !monochrome && reader.readFlag()
        val subsamplingX = sequenceHeader.colorConfig().chromaSubsamplingX
        val subsamplingY = sequenceHeader.colorConfig().chromaSubsamplingY

        val cbPoints: Array<FilmGrainPoint>?
        val crPoints: Array<FilmGrainPoint>?
        if (monochrome || chromaScalingFromLuma || (subsamplingX && subsamplingY && numYPoints == 0)) {
            cbPoints = arrayOfNullsCompat<FilmGrainPoint>(0)
            crPoints = arrayOfNullsCompat<FilmGrainPoint>(0)
        } else {
            val numCbPoints = readInt(reader, 4)
            if (numCbPoints > MAX_FILM_GRAIN_UV_POINTS) {
                fail("Film grain Cb point count exceeds the AV1 maximum of 10")
            }
            cbPoints = parseFilmGrainPoints(reader, numCbPoints, "Cb")

            val numCrPoints = readInt(reader, 4)
            if (numCrPoints > MAX_FILM_GRAIN_UV_POINTS) {
                fail("Film grain Cr point count exceeds the AV1 maximum of 10")
            }
            crPoints = parseFilmGrainPoints(reader, numCrPoints, "Cr")

            if (subsamplingX && subsamplingY) {
                if (numCbPoints == 0 && numCrPoints != 0) {
                    fail("4:2:0 film grain must not signal Cr points without Cb points")
                }
                if (numCbPoints != 0 && numCrPoints == 0) {
                    fail("4:2:0 film grain must signal Cr points when Cb points are present")
                }
            }
        }

        val scalingShift = readInt(reader, 2) + 8
        val arCoeffLag = readInt(reader, 2)
        val numPosLuma = 2 * arCoeffLag * (arCoeffLag + 1)
        val numPosChroma = if (yPoints.size > 0) numPosLuma + 1 else numPosLuma

        val arCoefficientsY =
            if (yPoints.size > 0) parseFilmGrainCoefficients(reader, numPosLuma) else IntArray(0)
        val arCoefficientsCb = if (chromaScalingFromLuma || cbPoints.size > 0)
            parseFilmGrainCoefficients(reader, numPosChroma)
        else
            IntArray(0)
        val arCoefficientsCr = if (chromaScalingFromLuma || crPoints.size > 0)
            parseFilmGrainCoefficients(reader, numPosChroma)
        else
            IntArray(0)

        val arCoeffShift = readInt(reader, 2) + 6
        val grainScaleShift = readInt(reader, 2)

        var cbMult = 0
        var cbLumaMult = 0
        var cbOffset = 0
        if (cbPoints.size > 0) {
            cbMult = readInt(reader, 8) - 128
            cbLumaMult = readInt(reader, 8) - 128
            cbOffset = readInt(reader, 9) - 256
        }

        var crMult = 0
        var crLumaMult = 0
        var crOffset = 0
        if (crPoints.size > 0) {
            crMult = readInt(reader, 8) - 128
            crLumaMult = readInt(reader, 8) - 128
            crOffset = readInt(reader, 9) - 256
        }

        val overlapEnabled = reader.readFlag()
        val clipToRestrictedRange = reader.readFlag()
        return FilmGrainParams(
            true,
            grainSeed,
            true,
            -1,
            yPoints,
            chromaScalingFromLuma,
            cbPoints,
            crPoints,
            scalingShift,
            arCoeffLag,
            arCoefficientsY,
            arCoefficientsCb,
            arCoefficientsCr,
            arCoeffShift,
            grainScaleShift,
            cbMult,
            cbLumaMult,
            cbOffset,
            crMult,
            crLumaMult,
            crOffset,
            overlapEnabled,
            clipToRestrictedRange
        )
    }

    /** Parses ordered film grain scaling points for one component.
     * 
     * @param reader the payload bit reader
     * @param pointCount the number of points to parse
     * @param componentName the component name used in validation messages
     * @return the parsed scaling points
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun parseFilmGrainPoints(
        reader: BitReader,
        pointCount: Int,
        componentName: String
    ): Array<FilmGrainPoint> {
        val points: Array<FilmGrainPoint> = arrayOfNullsCompat<FilmGrainPoint>(pointCount)
        var previousValue = -1
        for (i in 0..<pointCount) {
            val value = readInt(reader, 8)
            val scaling = readInt(reader, 8)
            if (value <= previousValue) {
                fail(componentName + " film grain point values must increase strictly")
            }
            points[i] = FilmGrainPoint(value, scaling)
            previousValue = value
        }
        return points
    }

    /** Parses normalized film grain auto-regressive coefficients.
     * 
     * @param reader the payload bit reader
     * @param coefficientCount the number of coefficients to parse
     * @return the normalized coefficients with the spec's `+128` offset removed
     * @throws AvifIoException if the payload is truncated
     */
    private fun parseFilmGrainCoefficients(reader: BitReader, coefficientCount: Int): IntArray {
        val coefficients = IntArray(coefficientCount)
        for (i in 0..<coefficientCount) {
            coefficients[i] = readInt(reader, 8) - 128
        }
        return coefficients
    }

    /** Returns whether the current frame references the supplied frame slot.
     * 
     * @param referenceFrameIndices the decoded LAST..ALTREF slot indices
     * @param referenceFrameIndex the frame slot to look for
     * @return whether the current frame references the supplied frame slot
     */
    private fun usesReferenceFrameIndex(
        referenceFrameIndices: IntArray,
        referenceFrameIndex: Int
    ): Boolean {
        for (decodedReferenceIndex in referenceFrameIndices) {
            if (decodedReferenceIndex == referenceFrameIndex) {
                return true
            }
        }
        return false
    }

    /** Reads the frame-level screen content tools flag.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @return whether screen content tools are enabled for the frame
     * @throws AvifIoException if the payload is truncated
     */
    private fun readFrameScreenContentTools(
        reader: BitReader,
        sequenceHeader: SequenceHeader
    ): Boolean {
        return when (sequenceHeader.features().screenContentTools()) {
            AdaptiveBoolean.OFF -> false
            AdaptiveBoolean.ON -> true
            AdaptiveBoolean.ADAPTIVE -> reader.readFlag()
        }
    }

    /** Reads the frame-level force-integer-motion-vectors flag.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @return whether integer motion vectors are forced for the frame
     * @throws AvifIoException if the payload is truncated
     */
    private fun readFrameForceIntegerMotionVectors(
        reader: BitReader,
        sequenceHeader: SequenceHeader
    ): Boolean {
        return when (sequenceHeader.features().forceIntegerMotionVectors()) {
            AdaptiveBoolean.OFF -> false
            AdaptiveBoolean.ON -> true
            AdaptiveBoolean.ADAPTIVE -> reader.readFlag()
        }
    }

    /** Parses the frame-level interpolation filter mode for inter prediction.
     * 
     * @param reader the payload bit reader
     * @return the parsed interpolation filter mode
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun parseInterpolationFilter(reader: BitReader): InterpolationFilter {
        if (reader.readFlag()) {
            return InterpolationFilter.SWITCHABLE
        }
        return when (readInt(reader, 2)) {
            0 -> InterpolationFilter.EIGHT_TAP_REGULAR
            1 -> InterpolationFilter.EIGHT_TAP_SMOOTH
            2 -> InterpolationFilter.EIGHT_TAP_SHARP
            3 -> InterpolationFilter.BILINEAR
            else -> throw IllegalStateException("Unexpected interpolation filter code")
        }
    }

    /** Derives skip-mode availability and reads the enabled flag when present.
     * 
     * @param reader the payload bit reader
     * @param sequenceHeader the active sequence header
     * @param frameType the current frame type
     * @param frameOffset the current frame order hint
     * @param switchableCompoundReferences whether compound-reference mode is switchable
     * @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
     * @param referenceFrameIndices the decoded LAST..ALTREF slot indices
     * @return the parsed skip-mode state
     * @throws AvifIoException if referenced headers are unavailable or the payload is truncated
     */
    private fun deriveSkipMode(
        reader: BitReader,
        sequenceHeader: SequenceHeader,
        frameType: Av1FrameType,
        frameOffset: Int,
        switchableCompoundReferences: Boolean,
        referenceFrameHeaders: Array<FrameHeader?>?,
        referenceFrameIndices: IntArray
    ): SkipModeResult {
        if (!switchableCompoundReferences || !isInterOrSwitch(frameType) || !sequenceHeader.features()
                .orderHint()
        ) {
            return SkipModeResult(false, false, intArrayOf(-1, -1))
        }

        var offBefore = -1
        var offAfter = -1
        var offBeforeIndex = -1
        var offAfterIndex = -1
        for (i in 0..<REFERENCES_PER_FRAME) {
            val referenceSlot = referenceFrameIndices[i]
            if (referenceSlot < 0 || referenceSlot >= TOTAL_REFERENCE_FRAMES) {
                fail("Skip-mode derivation requires all reference-frame slots to be decoded")
            }
            val referenceFrameHeader = referenceFrameHeaders!![referenceSlot]
            if (referenceFrameHeader == null) {
                fail("Skip-mode derivation requires all referenced frame headers")
            }
            val referencePoc = referenceFrameHeader!!.frameOffset()
            val diff =
                getPocDiff(sequenceHeader.features().orderHintBits(), referencePoc, frameOffset)
            if (diff > 0) {
                if (offAfter < 0 || getPocDiff(
                        sequenceHeader.features().orderHintBits(),
                        offAfter,
                        referencePoc
                    ) > 0
                ) {
                    offAfter = referencePoc
                    offAfterIndex = i
                }
            } else if (diff < 0) {
                if (offBefore < 0 || getPocDiff(
                        sequenceHeader.features().orderHintBits(),
                        referencePoc,
                        offBefore
                    ) > 0
                ) {
                    offBefore = referencePoc
                    offBeforeIndex = i
                }
            }
        }

        val skipModeReferenceIndices = intArrayOf(-1, -1)
        var skipModeAllowed = false
        if (offBefore >= 0 && offAfter >= 0) {
            skipModeReferenceIndices[0] = min(offBeforeIndex, offAfterIndex)
            skipModeReferenceIndices[1] = max(offBeforeIndex, offAfterIndex)
            skipModeAllowed = true
        } else if (offBefore >= 0) {
            var offBefore2 = -1
            var offBefore2Index = -1
            for (i in 0..<REFERENCES_PER_FRAME) {
                val referenceSlot = referenceFrameIndices[i]
                val referenceFrameHeader = referenceFrameHeaders!![referenceSlot]
                if (referenceFrameHeader == null) {
                    fail("Skip-mode derivation requires all referenced frame headers")
                }
                val referencePoc = referenceFrameHeader!!.frameOffset()
                if (getPocDiff(
                        sequenceHeader.features().orderHintBits(),
                        referencePoc,
                        offBefore
                    ) < 0
                ) {
                    if (offBefore2 < 0 || getPocDiff(
                            sequenceHeader.features().orderHintBits(),
                            referencePoc,
                            offBefore2
                        ) > 0
                    ) {
                        offBefore2 = referencePoc
                        offBefore2Index = i
                    }
                }
            }
            if (offBefore2 >= 0) {
                skipModeReferenceIndices[0] = min(offBeforeIndex, offBefore2Index)
                skipModeReferenceIndices[1] = max(offBeforeIndex, offBefore2Index)
                skipModeAllowed = true
            }
        }

        val skipModeEnabled = skipModeAllowed && reader.readFlag()
        return SkipModeResult(skipModeAllowed, skipModeEnabled, skipModeReferenceIndices)
    }

    /** Returns the wrapped order-hint difference `poc0 - poc1`.
     * 
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @param poc0 the minuend order hint
     * @param poc1 the subtrahend order hint
     * @return the wrapped order-hint difference `poc0 - poc1`
     */
    private fun getPocDiff(orderHintBits: Int, poc0: Int, poc1: Int): Int {
        if (orderHintBits == 0) {
            return 0
        }
        val mask = 1 shl (orderHintBits - 1)
        val diff = poc0 - poc1
        return (diff and (mask - 1)) - (diff and mask)
    }

    /** Maps an AV1 frame type code to the public enum.
     * 
     * @param value the AV1 frame type code
     * @return the mapped public enum
     * @throws AvifIoException if the frame type code is invalid
     */
    private fun decodeFrameType(value: Int): Av1FrameType {
        return when (value) {
            0 -> Av1FrameType.KEY
            1 -> Av1FrameType.INTER
            2 -> Av1FrameType.INTRA
            3 -> Av1FrameType.SWITCH
            else -> throw IllegalStateException("Unexpected frame type code: $value")
        }
    }

    /** Maps an AV1 loop restoration type code to the internal enum.
     * 
     * @param value the AV1 loop restoration type code
     * @return the mapped internal enum
     * @throws AvifIoException if the restoration type code is invalid
     */
    private fun decodeRestorationType(value: Int): RestorationType {
        return when (value) {
            0 -> RestorationType.NONE
            1 -> RestorationType.SWITCHABLE
            2 -> RestorationType.WIENER
            3 -> RestorationType.SELF_GUIDED
            else -> throw IllegalStateException("Unexpected restoration type code: $value")
        }
    }

    /** Returns whether the frame type is inter or switch.
     * 
     * @param frameType the AV1 frame type
     * @return whether the frame type is inter or switch
     */
    private fun isInterOrSwitch(frameType: Av1FrameType): Boolean {
        return frameType == Av1FrameType.INTER || frameType == Av1FrameType.SWITCH
    }

    /** Reads an AV1 uniform integer.
     * 
     * @param reader the payload bit reader
     * @param maxExclusive the exclusive upper bound
     * @return the decoded uniform integer
     * @throws AvifIoException if the payload is truncated
     */
    private fun readUniform(reader: BitReader, maxExclusive: Int): Int {
        if (maxExclusive <= 1) {
            return 0
        }

        val bits = Int.SIZE_BITS - maxExclusive.countLeadingZeroBits()
        val m = (1 shl bits) - maxExclusive
        val value = readInt(reader, bits - 1)
        return if (value < m) value else (value shl 1) - m + readInt(reader, 1)
    }

    /** Computes the smallest `k` such that `size << k >= target`.
     * 
     * @param size the tile unit size
     * @param target the target size
     * @return the smallest `k` such that `size << k >= target`
     */
    private fun tileLog2(size: Int, target: Int): Int {
        var k = 0
        while ((size shl k) < target) {
            k++
        }
        return k
    }

    /** Reads and validates the trailing bits at the end of the payload.
     * 
     * @param reader the payload bit reader
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun checkTrailingBits(reader: BitReader, strictStdCompliance: Boolean) {
        val trailingOneBit = reader.readFlag()
        if (!strictStdCompliance) {
            return
        }
        if (!trailingOneBit) {
            fail("Trailing one bit was not present")
        }
        while (reader.bitsRemaining() > 0) {
            if (reader.readBit() != 0) {
                fail("Non-zero trailing padding bits are not allowed in strict mode")
            }
        }
    }

    /** Clips a value to the unsigned byte range.
     * 
     * @param value the unclipped value
     * @return the value clipped to the unsigned byte range
     */
    private fun clipUnsignedByte(value: Int): Int {
        return max(0, min(255, value))
    }

    /** Returns default per-segment data with `referenceFrame = -1`.
     * 
     * @return default per-segment data
     */
    private fun defaultSegments(): Array<SegmentData> {
        val segments: Array<SegmentData> = arrayOfNullsCompat<SegmentData>(MAX_SEGMENTS)
        for (i in segments.indices) {
            segments[i] = SegmentData(0, 0, 0, 0, 0, -1, false, false)
        }
        return segments
    }

    /** Reads a bounded unsigned integer and narrows it to `int`.
     * 
     * @param reader the payload bit reader
     * @param bitCount the number of bits to read
     * @return the decoded integer value
     * @throws AvifIoException if the payload is truncated
     */
    private fun readInt(reader: BitReader, bitCount: Int): Int {
        return toIntExact(readLong(reader, bitCount))
    }

    /** Reads a bounded unsigned integer and returns it as `long`.
     * 
     * @param reader the payload bit reader
     * @param bitCount the number of bits to read
     * @return the decoded integer value
     * @throws AvifIoException if the payload is truncated
     */
    private fun readLong(reader: BitReader, bitCount: Int): Long {
        return reader.readBits(bitCount)
    }

    /** Throws an `AvifIoException` with a validation message.
     * 
     * @param message the validation message
     * @throws AvifIoException always
     */
    private fun fail(message: String) {
        throw AvifIoException(message)
    }

    /** Parsed skip-mode availability and enabled state.
     * 
     * @param allowed whether skip mode is permitted for the frame
     * @param enabled whether skip mode is enabled for the frame
     * @param referenceIndices the two skip-mode reference positions, or `-1` when unavailable
     */
    class SkipModeResult(
        val allowed: Boolean,
        val enabled: Boolean,
        referenceIndices: IntArray
    ) {
        val referenceIndices: IntArray

        init {
            var referenceIndices = referenceIndices
            referenceIndices = referenceIndices.copyOf()
            this.referenceIndices = referenceIndices
        }
    }

    /** Parsed frame size and super-resolution state.
     * 
     * @param frameSize the parsed frame dimensions
     * @param superResolution the parsed super-resolution settings
     */
    data class FrameSizeResult(
        val frameSize: FrameSize,
        val superResolution: SuperResolutionInfo
    ) {
        init {
            requireNotNullCompat(frameSize, "frameSize")
            requireNotNullCompat(superResolution, "superResolution")
        }
    }

    /** Derived per-segment lossless state.
     * 
     * @param segmentation the segmentation state with derived qindex and lossless arrays
     * @param allLossless whether all segments are lossless
     */
    data class DerivedLosslessState(
        val segmentation: SegmentationInfo,
        val allLossless: Boolean
    ) {
        init {
            requireNotNullCompat(segmentation, "segmentation")
        }
    }
}
