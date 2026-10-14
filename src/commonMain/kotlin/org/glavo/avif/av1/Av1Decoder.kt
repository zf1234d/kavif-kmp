// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1DecodedPlanes
import org.glavo.avif.internal.av1.bitstream.BitReader
import org.glavo.avif.internal.av1.bitstream.ObuPacket
import org.glavo.avif.internal.av1.bitstream.ObuStreamReader
import org.glavo.avif.internal.av1.bitstream.ObuType
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecoder
import org.glavo.avif.internal.av1.decode.InvalidFrameSyntaxException
import org.glavo.avif.internal.av1.decode.ReferenceFrameSyntaxState
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader.PartitionNode
import org.glavo.avif.internal.av1.entropy.CdfContext
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.model.FrameAssembly
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.SequenceHeader
import org.glavo.avif.internal.av1.model.TileBitstream
import org.glavo.avif.internal.av1.model.TileGroupHeader
import org.glavo.avif.internal.av1.parse.FrameHeaderParser
import org.glavo.avif.internal.av1.parse.SequenceHeaderParser
import org.glavo.avif.internal.av1.parse.TileBitstreamParser
import org.glavo.avif.internal.av1.parse.TileGroupHeaderParser
import org.glavo.avif.internal.av1.parse.TileListParser
import org.glavo.avif.internal.av1.postfilter.FilmGrainSynthesizer
import org.glavo.avif.internal.av1.postfilter.FramePostprocessor
import org.glavo.avif.internal.av1.recon.FrameReconstructor
import org.glavo.avif.internal.av1.recon.InvalidFrameReconstructionException
import org.glavo.avif.internal.av1.recon.LargeScaleTileOutputBuilder
import org.glavo.avif.internal.av1.recon.ReferenceSurfaceSnapshot
import org.glavo.avif.internal.io.BufferedInput
import org.glavo.avif.internal.io.BufferedInput.OfByteBuf


/** High-level sequential decoder for raw AV1 low-overhead and Annex B streams. */
class Av1Decoder private constructor(
    source: BufferedInput,
    config: Av1DecoderConfig,
    annexB: Boolean
) : AutoCloseable {
    /** The forward-only buffered byte source. */
    private val source: BufferedInput

    /** The immutable decoder configuration. */
    private val config: Av1DecoderConfig

    /** The sequential OBU reader used by this decoder. */
    private val obuReader: ObuStreamReader

    /** The most recently parsed sequence header. */
    private var sequenceHeader: SequenceHeader? = null

    /** The stored reference surfaces used for syntax inheritance and reconstructed-surface reuse. */
    private val referenceSlots: Array<ReferenceSurfaceSnapshot?>?

    /** The currently assembled frame when tile groups span multiple OBUs. */
    private var pendingFrameAssembly: FrameAssembly? = null

    /** The common camera frame retained for Large Scale Tile list decoding. */
    private var largeScaleTileCameraAssembly: FrameAssembly? = null

    /** The internal syntax references captured when the common camera header was parsed. */
    private val largeScaleTileCameraReferenceSyntaxStates: Array<ReferenceFrameSyntaxState?>?

    /** The internal primary-reference syntax state inherited by the common camera frame. */
    private var largeScaleTileCameraCdfReferenceState: ReferenceFrameSyntaxState? = null

    /** The externally indexed anchor frames collected before Large Scale Tile output begins. */
    private val largeScaleTileAnchorFrames: MutableList<ReferenceSurfaceSnapshot>

    /** The last selected spatial-layer output retained for the current temporal unit. */
    private var pendingLayeredOutput: PendingOutput? = null

    /** The most recently completed structural frame-decode result retained for inspection. */
    private var lastFrameSyntaxDecodeResult: FrameSyntaxDecodeResult? = null

    /** Whether completed structural frame-decode results are retained for package tests. */
    private var retainFrameSyntaxDecodeResultsForInspection = false

    /** The AV1 pixel reconstructor used for decoded frame output. */
    private val frameReconstructor: FrameReconstructor

    /** The zero-based presentation index assigned to the next returned frame. */
    private var nextPresentationIndex: Long = 0

    /** Whether this decoder has already been closed. */
    private var closed = false

    init {
        this.source = requireNotNullCompat(source, "source")
        this.config = requireNotNullCompat(config, "config")
        this.obuReader = if (annexB)
            ObuStreamReader.forAnnexB(source, config.obuPayloadSizeLimit())
        else
            ObuStreamReader(source, config.obuPayloadSizeLimit())
        this.referenceSlots = arrayOfNullsCompat<ReferenceSurfaceSnapshot?>(8)
        this.largeScaleTileCameraReferenceSyntaxStates = arrayOfNullsCompat<ReferenceFrameSyntaxState?>(8)
        this.largeScaleTileAnchorFrames = ArrayList<ReferenceSurfaceSnapshot>()
        this.frameReconstructor = FrameReconstructor()
    }

    /** Reads the next decoded frame from the source.
     * 
     * The presentation output is consumed before packed RGB conversion. If conversion is not
     * supported, this method throws and a subsequent read continues with the following output.
     * 
     * @return the next decoded frame, or `null` at end-of-stream
     * @throws AvifIoException if the source is unreadable or the bitstream is malformed
     */
    fun readFrame(): Av1DecodedFrame? {
        val output = readNextOutput()
        if (output == null) {
            return null
        }
        nextPresentationIndex++
        val frame: Av1DecodedFrame?
        try {
            frame = output.createOutput().toFrame()
        } catch (exception: UnsupportedOperationException) {
            throw unsupportedOutputConversion(output.packet, exception)
        }
        return frame
    }

    /** Reads the next decoded presentation output without forcing RGB conversion.
     * 
     * Each successful call consumes exactly one presentation output. The returned object keeps
     * the planes, color configuration, and frame metadata together; [Av1DecodedOutput.toFrame]
     * may be called later to obtain packed ARGB pixels.
     * 
     * @return the next decoded output, or `null` at end-of-stream
     * @throws AvifIoException if the source is unreadable or the bitstream is malformed
     */
    fun readOutput(): Av1DecodedOutput? {
        val output = readNextOutput()
        if (output == null) {
            return null
        }
        nextPresentationIndex++
        return output.createOutput()
    }

    /** Reads the next output frame as postprocessed YUV planes without RGB conversion.
     * 
     * This method applies the same operating-point, frame-output, and film-grain policies as
     * [readFrame()]. It may be interleaved with `readFrame`; each successful call consumes one
     * presentation frame. The returned immutable snapshot retains its own sample storage and does
     * not depend on subsequent decoder operations.
     * 
     * @return the next decoded plane snapshot, or `null` at end-of-stream
     * @throws AvifIoException if the source is unreadable or the bitstream is malformed
     */
    fun readPlanes(): Av1DecodedPlanes? {
        val output = readOutput()
        return if (output == null) null else output.planes()
    }

    /** Decodes packets until one presentation output is available.
     * 
     * @return the next pending presentation output, or `null` at end-of-stream
     * @throws AvifIoException if the source is unreadable or the bitstream is malformed
     */
    private fun readNextOutput(): PendingOutput? {
        ensureOpen()

        while (true) {
            if (obuReader.atTemporalUnitBoundary() && pendingLayeredOutput != null) {
                val output = pendingLayeredOutput
                pendingLayeredOutput = null
                return output
            }
            val packet = obuReader.readObu()
            if (packet == null) {
                if (pendingFrameAssembly != null) {
                    throw incompleteFrameAtEndOfStream(pendingFrameAssembly!!)
                }
                if (pendingLayeredOutput != null) {
                    val output = pendingLayeredOutput
                    pendingLayeredOutput = null
                    return output
                }
                return null
            }

            val type = packet.header.type
            if (config.strictStdCompliance() && type == ObuType.METADATA) {
                validateMetadataType(packet)
            }
            if (type != ObuType.SEQUENCE_HEADER) {
                validateRequiredObuExtension(packet)
            }
            if (type == ObuType.TEMPORAL_DELIMITER) {
                retainLargeScaleTileCameraHeaderAtBoundary(packet)
                if (pendingLayeredOutput != null) {
                    val output = pendingLayeredOutput
                    pendingLayeredOutput = null
                    return output
                }
                continue
            }
            if (type == ObuType.SEQUENCE_HEADER) {
                ensureNoPendingFrameAssembly(
                    packet,
                    "Sequence header OBU appeared before the current frame was completed"
                )
                val parsedSequenceHeader =
                    SequenceHeaderParser.parse(packet, config.strictStdCompliance())
                validateSelectedOperatingPoint(parsedSequenceHeader, packet)
                sequenceHeader = parsedSequenceHeader
                continue
            }
            if (type == ObuType.TILE_LIST) {
                if (!config.largeScaleTileMode()) {
                    throw invalidBitstream(
                        packet,
                        "Tile-list OBU requires Large Scale Tile decoder mode"
                    )
                }
                retainLargeScaleTileCameraHeader(packet)
                return decodeLargeScaleTileList(packet)
            }
            if (!matchesSelectedOperatingPoint(packet)) {
                continue
            }
            if (type == ObuType.FRAME_HEADER) {
                val output = startStandaloneFrameAssembly(packet)
                if (output != null) {
                    val readyOutput = retainLayeredOutput(output)
                    if (readyOutput != null) {
                        return readyOutput
                    }
                }
                continue
            }
            if (type == ObuType.FRAME) {
                val start = startCombinedFrameAssembly(packet)
                if (start.resolvedImmediately()) {
                    val output = start.immediateOutput
                    if (output != null) {
                        val readyOutput = retainLayeredOutput(output)
                        if (readyOutput != null) {
                            return readyOutput
                        }
                    }
                    continue
                }
                val assembly = start.frameAssembly()
                if (assembly.isComplete) {
                    pendingFrameAssembly = null
                    val output = completeFrameAssembly(assembly, packet)
                    if (output != null) {
                        val readyOutput = retainLayeredOutput(output)
                        if (readyOutput != null) {
                            return readyOutput
                        }
                    }
                } else {
                    pendingFrameAssembly = assembly
                }
                continue
            }
            if (type == ObuType.TILE_GROUP) {
                val assembly = appendStandaloneTileGroup(packet)
                if (assembly.isComplete) {
                    pendingFrameAssembly = null
                    val output = completeFrameAssembly(assembly, packet)
                    if (output != null) {
                        val readyOutput = retainLayeredOutput(output)
                        if (readyOutput != null) {
                            return readyOutput
                        }
                    }
                }
                continue
            }
        }
    }

    /** Retains a spatial-layer output until its temporal unit ends when layer collapsing is active.
     * 
     * @param output the newly prepared presentation output
     * @return the output for immediate presentation, or `null` when it was retained
     */
    private fun retainLayeredOutput(output: PendingOutput): PendingOutput? {
        val activeSequenceHeader =
            requireNotNullCompat(sequenceHeader, "sequenceHeader")
        val operatingPoint = activeSequenceHeader.operatingPoint(config.operatingPoint())
        val selectedSpatialLayers = bitCount((operatingPoint.idc() ushr 8) and 0x0F)
        if (config.outputAllLayers() || selectedSpatialLayers <= 1) {
            return output
        }
        pendingLayeredOutput = output
        return null
    }

    /** Reads all remaining decoded frames from the current stream position until end-of-stream.
     * 
     * @return all remaining decoded frames from the current stream position
     * @throws AvifIoException if the source is unreadable or the bitstream is malformed
     */
    fun readAllFrames(): MutableList<Av1DecodedFrame> {
        ensureOpen()

        val frames: MutableList<Av1DecodedFrame> = ArrayList<Av1DecodedFrame>()
        while (true) {
            val frame = readFrame()
            if (frame == null) {
                return ArrayList(frames)
            }
            frames.add(frame)
        }
    }

    /** Closes this decoder and the underlying byte source.
     * 
     * @throws AvifIoException if the underlying source fails to close
     */
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        source.close()
    }

    /** Returns the immutable decoder configuration for this decoder.
     * 
     * @return the immutable decoder configuration
     */
    fun config(): Av1DecoderConfig {
        return config
    }

    /** Returns the most recently completed structural frame-decode result, or `null`.
     * 
     * This package-private accessor exposes structural state for decoder conformance tests.
     * 
     * @return the most recently completed structural frame-decode result, or `null`
     */
    fun lastFrameSyntaxDecodeResult(): FrameSyntaxDecodeResult? {
        return lastFrameSyntaxDecodeResult
    }

    /** Enables retaining future structural frame-decode results for package-level inspection.
     * 
     * Normal decoding does not retain the full syntax tree after reconstruction. Tests that need
     * to inspect that tree must call this method before decoding the relevant frame. */
    fun retainFrameSyntaxDecodeResultsForInspection() {
        retainFrameSyntaxDecodeResultsForInspection = true
    }

    /** Returns one refreshed compact reference-frame syntax state, or `null`.
     * 
     * This package-private accessor exposes reference-slot state for decoder conformance tests.
     * 
     * @param slot the zero-based reference-frame slot
     * @return one refreshed compact reference-frame syntax state, or `null`
     */
    fun referenceFrameSyntaxState(slot: Int): ReferenceFrameSyntaxState? {
        if (slot < 0 || slot >= referenceSlots!!.size) {
            throw IndexOutOfBoundsException("slot out of range: $slot")
        }
        val snapshot = referenceSlots[slot]
        return if (snapshot != null) snapshot.frameSyntaxState else null
    }

    /** Returns one refreshed reference-frame header, or `null`.
     * 
     * This accessor exists for same-package tests while the public output/runtime surface API is
     * still intentionally narrow.
     * 
     * @param slot the zero-based reference-frame slot
     * @return one refreshed reference-frame header, or `null`
     */
    fun referenceFrameHeader(slot: Int): FrameHeader? {
        if (slot < 0 || slot >= referenceSlots!!.size) {
            throw IndexOutOfBoundsException("slot out of range: $slot")
        }
        val snapshot = referenceSlots[slot]
        return if (snapshot != null) snapshot.frameHeader else null
    }

    /** Returns one refreshed reference-surface snapshot, or `null`.
     * 
     * This accessor exists for same-package tests while the public output/runtime surface API is
     * still intentionally narrow.
     * 
     * @param slot the zero-based reference-frame slot
     * @return one refreshed reference-surface snapshot, or `null`
     */
    fun referenceSurfaceSnapshot(slot: Int): ReferenceSurfaceSnapshot? {
        if (slot < 0 || slot >= referenceSlots!!.size) {
            throw IndexOutOfBoundsException("slot out of range: $slot")
        }
        return referenceSlots[slot]
    }

    /** Injects one full reference-slot state for same-package tests.
     * 
     * This helper exists only so package-level tests can exercise public runtime behavior without
     * reflecting into private slot storage.
     * 
     * @param slot the zero-based reference slot to refresh
     * @param sequenceHeader the active sequence header to associate with the injected slot
     * @param referenceSurfaceSnapshot the complete reconstructed reference state
     */
    fun injectReferenceStateForTest(
        slot: Int,
        sequenceHeader: SequenceHeader,
        referenceSurfaceSnapshot: ReferenceSurfaceSnapshot
    ) {
        if (slot < 0 || slot >= referenceSlots!!.size) {
            throw IndexOutOfBoundsException("slot out of range: $slot")
        }

        this.sequenceHeader =
            requireNotNullCompat(sequenceHeader, "sequenceHeader")
        referenceSlots[slot] = requireNotNullCompat(
            referenceSurfaceSnapshot,
            "referenceSurfaceSnapshot"
        )
    }

    /** Ensures that this decoder has not already been closed.
     * 
     * @throws AvifIoException if this decoder has already been closed
     */
    private fun ensureOpen() {
        if (closed) {
            throw AvifIoException("Av1Decoder is closed")
        }
    }

    /** Validates that the configured operating point is declared by a parsed sequence header.
     * 
     * @param parsedSequenceHeader the parsed sequence header
     * @param packet the packet carrying the sequence header
     * @throws Av1DecodeException if the configured operating point is absent
     */
    private fun validateSelectedOperatingPoint(
        parsedSequenceHeader: SequenceHeader,
        packet: ObuPacket
    ) {
        selectedOperatingPoint(parsedSequenceHeader, packet)
    }

    /** Returns whether one OBU belongs to the configured operating point.
     * 
     * @param packet the OBU packet to test
     * @return whether the packet should be decoded for the configured operating point
     * @throws Av1DecodeException if the configured operating point is absent from the active sequence
     */
    private fun matchesSelectedOperatingPoint(packet: ObuPacket): Boolean {
        val activeSequenceHeader = sequenceHeader
        if (activeSequenceHeader == null) {
            return true
        }

        val operatingPoint = selectedOperatingPoint(activeSequenceHeader, packet)
        val idc = operatingPoint.idc()
        if (idc == 0) {
            return true
        }

        val temporalBit = 1 shl packet.header.temporalId
        val spatialBit = 1 shl (packet.header.spatialId + 8)
        return (idc and temporalBit) != 0 && (idc and spatialBit) != 0
    }

    /** Validates the extension-header requirement imposed by non-zero operating-point masks.
     * 
     * @param packet the current OBU packet
     * @throws Av1DecodeException if strict mode requires an extension header that is absent
     */
    private fun validateRequiredObuExtension(packet: ObuPacket) {
        val activeSequenceHeader = sequenceHeader
        if (!config.strictStdCompliance() || activeSequenceHeader == null) {
            return
        }
        var hasLayeredOperatingPoint = false
        for (index in 0..<activeSequenceHeader.operatingPointCount()) {
            if (activeSequenceHeader.operatingPoint(index).idc() != 0) {
                hasLayeredOperatingPoint = true
                break
            }
        }
        if (!hasLayeredOperatingPoint) {
            if (packet.header.extensionFlag) {
                throw invalidBitstream(
                    packet,
                    "OBU extension headers are forbidden when every operating point IDC is zero"
                )
            }
            return
        }
        if (packet.header.extensionFlag) {
            return
        }
        val type = packet.header.type
        if (type != ObuType.FRAME_HEADER && type != ObuType.FRAME && type != ObuType.REDUNDANT_FRAME_HEADER && type != ObuType.TILE_GROUP) {
            return
        }
        throw invalidBitstream(
            packet,
            "Frame and tile OBUs require extension headers when an operating point IDC is non-zero"
        )
    }

    /** Returns the configured operating point from one sequence header.
     * 
     * @param activeSequenceHeader the active sequence header
     * @param packet the packet used for error context
     * @return the configured operating point
     * @throws Av1DecodeException if the configured operating point is absent
     */
    private fun selectedOperatingPoint(
        activeSequenceHeader: SequenceHeader,
        packet: ObuPacket
    ): SequenceHeader.OperatingPoint {
        val operatingPoint = config.operatingPoint()
        if (operatingPoint >= activeSequenceHeader.operatingPointCount()) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.SEQUENCE_HEADER_PARSE,
                "Configured operating point is not declared by the sequence header: $operatingPoint",
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }
        return activeSequenceHeader.operatingPoint(operatingPoint)
    }

    /** Starts a new frame assembly from a standalone frame-header OBU.
     * 
     * @param packet the standalone frame-header OBU
     * @return an immediate `show_existing_frame` output, or `null` when assembly should continue
     * or the requested output is suppressed by the decoder configuration
     * @throws AvifIoException if the OBU is unreadable, malformed, or out of order
     */
    private fun startStandaloneFrameAssembly(packet: ObuPacket): PendingOutput? {
        val activeSequenceHeader = requireSequenceHeader(packet)
        ensureNoPendingFrameAssembly(
            packet,
            "Standalone frame header OBU appeared before the previous frame was completed"
        )

        val frameHeader = FrameHeaderParser.parse(
            packet,
            activeSequenceHeader,
            config.strictStdCompliance(),
            referenceFrameHeadersForParsing()
        )
        enforceFrameSizeLimit(frameHeader, packet)
        if (frameHeader.showExistingFrame()) {
            return outputExistingFrame(packet, frameHeader)
        }

        pendingFrameAssembly = FrameAssembly(
            activeSequenceHeader,
            frameHeader,
            referenceFrameHeadersForParsing(),
            packet.streamOffset,
            packet.obuIndex
        )
        return null
    }

    /** Starts a new frame assembly from a combined `FRAME` OBU.
     * 
     * @param packet the combined frame OBU
     * @return either the started frame assembly including its first tile group or an immediate
     * `show_existing_frame` result
     * @throws AvifIoException if the OBU is unreadable, malformed, or out of order
     */
    private fun startCombinedFrameAssembly(packet: ObuPacket): CombinedFrameStart {
        val activeSequenceHeader = requireSequenceHeader(packet)
        ensureNoPendingFrameAssembly(
            packet,
            "Combined frame OBU appeared before the previous frame was completed"
        )

        val reader = BitReader(packet.payload)
        val frameHeader = FrameHeaderParser.parseFramePayload(
            reader,
            packet,
            activeSequenceHeader,
            config.strictStdCompliance(),
            referenceFrameHeadersForParsing()
        )
        enforceFrameSizeLimit(frameHeader, packet)
        if (frameHeader.showExistingFrame()) {
            reader.byteAlign()
            if (reader.byteOffset() != packet.payload.size) {
                throw invalidBitstream(
                    packet,
                    "Combined frame OBU must not carry tile data when show_existing_frame is set"
                )
            }
            return CombinedFrameStart.immediateOutput(
                outputExistingFrame(
                    packet,
                    frameHeader
                )
            )
        }

        val assembly = FrameAssembly(
            activeSequenceHeader,
            frameHeader,
            referenceFrameHeadersForParsing(),
            packet.streamOffset,
            packet.obuIndex
        )
        reader.byteAlign()
        val tileGroupHeader = TileGroupHeaderParser.parse(reader, packet, frameHeader)
        if (config.strictStdCompliance() && tileGroupHeader.explicitTilePositions) {
            throw invalidBitstream(
                packet,
                "Combined frame OBU must not signal explicit tile positions"
            )
        }
        reader.byteAlign()
        appendTileGroup(assembly, packet, tileGroupHeader, reader.byteOffset())
        return CombinedFrameStart.frameAssembly(assembly)
    }

    /** Finalizes a completed frame assembly by refreshing reference slots and optionally preparing
     * one presentation output.
     * 
     * @param assembly the completed frame assembly
     * @param packet the OBU that completed the frame assembly
     * @return the pending presentation output, or `null` when current output filtering suppresses it
     */
    private fun completeFrameAssembly(assembly: FrameAssembly, packet: ObuPacket): PendingOutput? {
        val frameHeader = assembly.frameHeader()
        if (config.strictStdCompliance()) {
            validateReferenceState(assembly, packet)
        }
        val cdfReferenceState = selectCdfReferenceFrameSyntaxState(frameHeader)
        var syntaxDecodeResult: FrameSyntaxDecodeResult?
        try {
            syntaxDecodeResult = FrameSyntaxDecoder(
                cdfReferenceState,
                referenceFrameSyntaxStatesForDecoding(),
                config.strictStdCompliance()
            ).decode(assembly)
        } catch (exception: InvalidFrameSyntaxException) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.FRAME_DECODE,
                exception.message,
                assembly.streamOffset(),
                assembly.obuIndex(),
                null,
                exception
            )
        }
        lastFrameSyntaxDecodeResult = if (retainFrameSyntaxDecodeResultsForInspection)
            syntaxDecodeResult
        else
            null
        val shouldOutput = !config.largeScaleTileMode() && shouldOutputFrame(frameHeader)
        val needsSurfaceSnapshot = frameHeader.refreshFrameFlags() != 0
        val needsAnchorSnapshot = config.largeScaleTileMode() && frameHeader.showFrame()
        val storedSyntaxState = if (needsSurfaceSnapshot || needsAnchorSnapshot)
            storedReferenceFrameSyntaxState(frameHeader, syntaxDecodeResult, cdfReferenceState)
        else
            null

        var decodedPlanes: DecodedSurface? = null
        if (shouldOutput || needsSurfaceSnapshot || needsAnchorSnapshot) {
            try {
                decodedPlanes = frameReconstructor.reconstruct(
                    syntaxDecodeResult,
                    currentReferenceSurfaceSnapshots(),
                    config.strictStdCompliance()
                )
            } catch (exception: InvalidFrameReconstructionException) {
                throw Av1DecodeException(
                    Av1DecodeErrorCode.INVALID_BITSTREAM,
                    Av1DecodeStage.FRAME_DECODE,
                    exception.message,
                    assembly.streamOffset(),
                    assembly.obuIndex(),
                    null,
                    exception
                )
            }
        }

        var postprocessedPlanes: DecodedSurface? = null
        if (decodedPlanes != null) {
            val preparedPostprocessing = FramePostprocessor.prepare(
                decodedPlanes,
                frameHeader,
                syntaxDecodeResult
            )
            // The prepared postfilter state retains compact maps only. Drop the multi-million-object
            // syntax tree before any pixel-domain postfilter allocates another full-frame surface.
            syntaxDecodeResult = null
            postprocessedPlanes = FramePostprocessor.finish(preparedPostprocessing)
            if (needsSurfaceSnapshot || needsAnchorSnapshot) {
                val snapshot = ReferenceSurfaceSnapshot(
                    frameHeader,
                    requireNotNullCompat(
                        storedSyntaxState,
                        "storedSyntaxState"
                    ),
                    postprocessedPlanes
                )
                if (needsSurfaceSnapshot) {
                    refreshReferenceState(frameHeader, snapshot)
                }
                if (needsAnchorSnapshot) {
                    addLargeScaleTileAnchorFrame(snapshot, packet)
                }
            }
        }
        if (!shouldOutput) {
            return null
        }
        val presentationPlanes = applyPresentationFilters(
            requireNotNullCompat(postprocessedPlanes, "postprocessedPlanes"),
            frameHeader,
            assembly.sequenceHeader().colorConfig()
        )
        return PendingOutput.normal(
            presentationPlanes,
            assembly.sequenceHeader().colorConfig(),
            frameHeader,
            frameHeader.showFrame(),
            nextPresentationIndex,
            packet
        )
    }

    /** Validates that every selected reference frame is populated and sequence-compatible.
     * 
     * @param assembly the completed current frame assembly
     * @param packet the OBU that completed the frame assembly
     * @throws Av1DecodeException if a selected reference is unavailable or incompatible with the current sequence
     */
    private fun validateReferenceState(assembly: FrameAssembly, packet: ObuPacket) {
        val currentSequence = assembly.sequenceHeader()
        val frameHeader = assembly.frameHeader()
        if (frameHeader.frameType() != Av1FrameType.INTER && frameHeader.frameType() != Av1FrameType.SWITCH) {
            return
        }
        for (referenceIndex in 0..6) {
            val slotIndex = frameHeader.referenceFrameIndex(referenceIndex)
            if (slotIndex < 0 || slotIndex >= referenceSlots!!.size) {
                throw invalidBitstream(
                    packet,
                    "Selected reference frame does not identify a valid slot"
                )
            }
            val snapshot = referenceSlots[slotIndex]
            val storedState = if (snapshot != null)
                snapshot.frameSyntaxState
            else
                null
            if (storedState == null) {
                throw invalidBitstream(packet, "Selected reference frame slot is not populated")
            }
            val storedSequence = storedState.sequenceHeader()
            if (storedSequence.profile() != currentSequence.profile()) {
                throw invalidBitstream(
                    packet,
                    "Selected reference frame profile differs from the current sequence"
                )
            }
            if (!compatibleReferenceColorConfig(
                    storedSequence.colorConfig(),
                    currentSequence.colorConfig()
                )
            ) {
                throw invalidBitstream(
                    packet,
                    "Selected reference frame bit depth or color configuration differs from the current sequence"
                )
            }
        }
    }

    /** Parses and appends a standalone tile-group OBU to the current frame assembly.
     * 
     * @param packet the standalone tile-group OBU
     * @return the updated in-progress frame assembly
     * @throws AvifIoException if the OBU is unreadable, malformed, or out of order
     */
    private fun appendStandaloneTileGroup(packet: ObuPacket): FrameAssembly {
        requireSequenceHeader(packet)
        val assembly = requirePendingFrameAssembly(packet)

        val reader = BitReader(packet.payload)
        val tileGroupHeader = TileGroupHeaderParser.parse(reader, packet, assembly.frameHeader())
        reader.byteAlign()
        appendTileGroup(assembly, packet, tileGroupHeader, reader.byteOffset())
        return assembly
    }

    /** Converts a header-only pending frame into the common Large Scale Tile camera frame at a
     * temporal-unit boundary.
     * 
     * @param packet the temporal delimiter that ended the camera-header temporal unit
     * @throws Av1DecodeException if a partially tiled frame reached the boundary
     */
    private fun retainLargeScaleTileCameraHeaderAtBoundary(packet: ObuPacket) {
        if (pendingFrameAssembly == null) {
            return
        }
        if (config.largeScaleTileMode() && pendingFrameAssembly!!.tileGroupCount() == 0) {
            retainLargeScaleTileCameraAssembly(pendingFrameAssembly!!, packet)
            pendingFrameAssembly = null
            return
        }
        ensureNoPendingFrameAssembly(
            packet,
            "Temporal delimiter appeared before the current frame was completed"
        )
    }

    /** Retains a header-only pending camera frame immediately before a tile-list OBU.
     * 
     * @param packet the tile-list OBU requiring the camera frame
     * @throws Av1DecodeException if the pending frame contains tile data or no camera header exists
     */
    private fun retainLargeScaleTileCameraHeader(packet: ObuPacket) {
        if (pendingFrameAssembly != null) {
            if (pendingFrameAssembly!!.tileGroupCount() != 0) {
                throw invalidBitstream(
                    packet,
                    "Tile-list OBU appeared while a tiled frame was incomplete"
                )
            }
            retainLargeScaleTileCameraAssembly(pendingFrameAssembly!!, packet)
            pendingFrameAssembly = null
        }
        if (largeScaleTileCameraAssembly == null) {
            throw invalidBitstream(
                packet,
                "Tile-list OBU appeared before a common camera frame header"
            )
        }
    }

    /** Validates and retains one common Large Scale Tile camera frame.
     * 
     * @param assembly the header-only camera-frame assembly
     * @param packet the OBU used for error context
     * @throws Av1DecodeException if strict conformance validation rejects the camera frame
     */
    private fun retainLargeScaleTileCameraAssembly(
        assembly: FrameAssembly,
        packet: ObuPacket
    ) {
        TileListParser.validateCameraFrame(
            packet,
            assembly.sequenceHeader(),
            assembly.frameHeader(),
            config.strictStdCompliance()
        )
        largeScaleTileCameraAssembly = assembly
        val referenceSyntaxStates = referenceFrameSyntaxStatesForDecoding()!!
        arraycopy(
            referenceSyntaxStates,
            0,
            largeScaleTileCameraReferenceSyntaxStates!!,
            0,
            referenceSyntaxStates.size
        )
        largeScaleTileCameraCdfReferenceState =
            selectCdfReferenceFrameSyntaxState(assembly.frameHeader())
    }

    /** Decodes and assembles one Large Scale Tile list output.
     * 
     * @param packet the tile-list OBU
     * @return the assembled presentation output
     * @throws Av1DecodeException if the list references unavailable anchors or contains invalid tile syntax
     */
    private fun decodeLargeScaleTileList(packet: ObuPacket): PendingOutput {
        val cameraAssembly = requireNotNullCompat(
            largeScaleTileCameraAssembly,
            "largeScaleTileCameraAssembly"
        )
        val cameraSequenceHeader = cameraAssembly.sequenceHeader()
        val cameraFrameHeader = cameraAssembly.frameHeader()
        val tileList = TileListParser.parse(
            packet,
            cameraSequenceHeader,
            cameraFrameHeader,
            config.strictStdCompliance()
        )

        val tiling = cameraFrameHeader.tiling()
        val superblockSize =
            if (cameraSequenceHeader.features().use128x128Superblocks()) 128 else 64
        val tileWidth = ((tiling.columnStartSuperblock(1) - tiling.columnStartSuperblock(0))
                * superblockSize)
        val tileHeight = ((tiling.rowStartSuperblock(1) - tiling.rowStartSuperblock(0))
                * superblockSize)
        val outputWidth: Int
        val outputHeight: Int
        try {
            outputWidth = multiplyExact(tileWidth, tileList.outputTileColumns)
            outputHeight = multiplyExact(tileHeight, tileList.outputTileRows)
        } catch (exception: ArithmeticException) {
            throw invalidBitstream(packet, "Large Scale Tile output dimensions overflow")
        }
        enforceFrameSizeLimit(outputWidth, outputHeight, packet)

        val outputBuilder = LargeScaleTileOutputBuilder(
            cameraSequenceHeader.colorConfig().bitDepth.bits(),
            cameraSequenceHeader.colorConfig().chromaFormat,
            tileWidth,
            tileHeight,
            tileList.outputTileColumns,
            tileList.outputTileRows
        )
        val lastReferenceSlot = cameraFrameHeader.referenceFrameIndex(0)
        if (lastReferenceSlot < 0 || lastReferenceSlot >= referenceSlots!!.size) {
            throw invalidBitstream(
                packet,
                "Large Scale Tile camera frame does not select a valid LAST slot"
            )
        }

        val entries = tileList.entries
        val syntaxKeys: MutableList<LargeScaleTileSyntaxKey> =
            ArrayList<LargeScaleTileSyntaxKey>(entries.size)
        val remainingSyntaxUses: MutableMap<LargeScaleTileSyntaxKey, Int> =
            HashMap<LargeScaleTileSyntaxKey, Int>()
        for (entry in entries) {
            val syntaxKey = LargeScaleTileSyntaxKey(entry.bitstream)
            syntaxKeys.add(syntaxKey)
            remainingSyntaxUses[syntaxKey] = (remainingSyntaxUses[syntaxKey] ?: 0) + 1
        }
        val decodedTileSyntax: MutableMap<LargeScaleTileSyntaxKey, FrameSyntaxDecodeResult> =
            LinkedHashMap<LargeScaleTileSyntaxKey, FrameSyntaxDecodeResult>()
        val decodedTileOutputIndices: MutableMap<LargeScaleTileDecodeKey, Int> =
            HashMap<LargeScaleTileDecodeKey, Int>()
        for (outputTileIndex in entries.indices) {
            val entry = entries.get(outputTileIndex)
            if (entry.anchorFrameIndex >= largeScaleTileAnchorFrames.size) {
                throw invalidBitstream(
                    packet,
                    "Tile-list entry references unavailable anchor frame " + entry.anchorFrameIndex
                )
            }
            val syntaxKey = syntaxKeys.get(outputTileIndex)
            val remainingSyntaxUseCount = requireNotNullCompat(
                remainingSyntaxUses.get(syntaxKey),
                "remainingSyntaxUses"
            )
            val decodeKey = LargeScaleTileDecodeKey(
                entry.anchorFrameIndex,
                syntaxKey
            )
            val decodedOutputTileIndex = decodedTileOutputIndices.get(decodeKey)
            if (decodedOutputTileIndex != null) {
                outputBuilder.copyOutputTile(decodedOutputTileIndex, outputTileIndex)
                finishLargeScaleTileSyntaxUse(
                    syntaxKey,
                    remainingSyntaxUseCount,
                    remainingSyntaxUses,
                    decodedTileSyntax
                )
                continue
            }
            val anchor = largeScaleTileAnchorFrames.get(entry.anchorFrameIndex)
            val referenceSurfaces: Array<ReferenceSurfaceSnapshot?> =
                arrayOfNullsCompat<ReferenceSurfaceSnapshot?>(referenceSlots.size)
            referenceSurfaces[lastReferenceSlot] = anchor

            val sourceTileIndex = entry.bitstream.tileIndex()
            val cachedSyntaxDecodeResult = decodedTileSyntax.get(syntaxKey)
            val syntaxDecodeResult: FrameSyntaxDecodeResult?
            if (cachedSyntaxDecodeResult != null) {
                syntaxDecodeResult = cachedSyntaxDecodeResult
            } else {
                val tileAssembly = FrameAssembly(
                    cameraSequenceHeader,
                    cameraFrameHeader,
                    cameraReferenceFrameHeaders(cameraAssembly),
                    packet.streamOffset,
                    packet.obuIndex
                )
                tileAssembly.addTileForPartialDecode(entry.bitstream)

                val referenceSyntaxStates: Array<ReferenceFrameSyntaxState?> =
                    largeScaleTileCameraReferenceSyntaxStates!!.copyOf(
                        largeScaleTileCameraReferenceSyntaxStates.size
                    )
                try {
                    syntaxDecodeResult = FrameSyntaxDecoder(
                        largeScaleTileCameraCdfReferenceState,
                        referenceSyntaxStates,
                        config.strictStdCompliance()
                    ).decodeTile(tileAssembly, sourceTileIndex)
                } catch (exception: InvalidFrameSyntaxException) {
                    throw Av1DecodeException(
                        Av1DecodeErrorCode.INVALID_BITSTREAM,
                        Av1DecodeStage.FRAME_DECODE,
                        exception.message,
                        packet.streamOffset,
                        packet.obuIndex,
                        null,
                        exception
                    )
                }
                if (config.strictStdCompliance()) {
                    validateLargeScaleTileReferences(syntaxDecodeResult, sourceTileIndex, packet)
                }
                if (remainingSyntaxUseCount > 1) {
                    cacheLargeScaleTileSyntax(decodedTileSyntax, syntaxKey, syntaxDecodeResult)
                }
            }
            lastFrameSyntaxDecodeResult = if (retainFrameSyntaxDecodeResultsForInspection)
                syntaxDecodeResult
            else
                null
            finishLargeScaleTileSyntaxUse(
                syntaxKey,
                remainingSyntaxUseCount,
                remainingSyntaxUses,
                decodedTileSyntax
            )

            val reconstructed: DecodedSurface?
            try {
                reconstructed = frameReconstructor.reconstructTile(
                    syntaxDecodeResult,
                    referenceSurfaces,
                    sourceTileIndex,
                    config.strictStdCompliance()
                )
            } catch (exception: InvalidFrameReconstructionException) {
                throw Av1DecodeException(
                    Av1DecodeErrorCode.INVALID_BITSTREAM,
                    Av1DecodeStage.FRAME_DECODE,
                    exception.message,
                    packet.streamOffset,
                    packet.obuIndex,
                    null,
                    exception
                )
            }
            outputBuilder.copyTile(
                reconstructed,
                0,
                0,
                outputTileIndex
            )
            decodedTileOutputIndices.put(decodeKey, outputTileIndex)
        }

        val outputPlanes = outputBuilder.build()
        return PendingOutput.normal(
            outputPlanes,
            cameraSequenceHeader.colorConfig(),
            cameraFrameHeader,
            true,
            nextPresentationIndex,
            packet
        )
    }

    /** Reconstructs the slot-indexed reference-header snapshot captured by one camera assembly.
     * 
     * @param cameraAssembly the retained common camera-frame assembly
     * @return the selected reference headers indexed by runtime slot
     */
    private fun cameraReferenceFrameHeaders(cameraAssembly: FrameAssembly): Array<FrameHeader?> {
        val cameraFrameHeader = cameraAssembly.frameHeader()
        val headers: Array<FrameHeader?> = arrayOfNullsCompat<FrameHeader?>(referenceSlots!!.size)
        for (referenceFrame in 0..6) {
            val slot = cameraFrameHeader.referenceFrameIndex(referenceFrame)
            if (slot >= 0 && slot < headers.size) {
                headers[slot] = cameraAssembly.referenceFrameHeader(referenceFrame)!!
            }
        }
        return headers
    }

    /** Appends parsed tile-group metadata to the supplied frame assembly.
     * 
     * @param assembly the in-progress frame assembly
     * @param packet the source OBU that carried the tile group
     * @param tileGroupHeader the parsed tile-group header
     * @param tileDataOffset the byte offset of the tile data inside the OBU payload
     * @throws Av1DecodeException if the tile group is out of order or inconsistent with the frame layout
     */
    private fun appendTileGroup(
        assembly: FrameAssembly,
        packet: ObuPacket,
        tileGroupHeader: TileGroupHeader,
        tileDataOffset: Int
    ) {
        if (tileGroupHeader.totalTileCount != assembly.totalTiles()) {
            throw invalidBitstream(
                packet,
                "Tile-group header does not match the active frame tile layout"
            )
        }
        if (tileGroupHeader.startTileIndex != assembly.nextTileIndex()) {
            throw invalidBitstream(
                packet,
                ("Tile groups are out of order: expected tile " + assembly.nextTileIndex()
                        + " but received " + tileGroupHeader.startTileIndex)
            )
        }

        val tiles = TileBitstreamParser.parse(
            packet,
            assembly.frameHeader(),
            tileGroupHeader,
            tileDataOffset
        )
        assembly.addTileGroup(
            tileGroupHeader,
            tiles
        )
    }

    /** Returns the active sequence header or throws a contextual state violation.
     * 
     * @param packet the source OBU packet
     * @return the active sequence header
     * @throws Av1DecodeException if no sequence header has been seen yet
     */
    private fun requireSequenceHeader(packet: ObuPacket): SequenceHeader {
        if (sequenceHeader == null) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.STATE_VIOLATION,
                Av1DecodeStage.FRAME_ASSEMBLY,
                "Frame data appeared before a sequence header OBU",
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }
        return sequenceHeader!!
    }

    /** Returns the current frame assembly or throws a contextual state violation.
     * 
     * @param packet the source OBU packet
     * @return the current in-progress frame assembly
     * @throws Av1DecodeException if no frame header has started a frame assembly yet
     */
    private fun requirePendingFrameAssembly(packet: ObuPacket): FrameAssembly {
        if (pendingFrameAssembly == null) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.STATE_VIOLATION,
                Av1DecodeStage.FRAME_ASSEMBLY,
                "Tile-group OBU appeared before a frame header OBU",
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }
        return pendingFrameAssembly!!
    }

    /** Ensures that no previous frame assembly is still waiting for tile groups.
     * 
     * @param packet the source OBU packet
     * @param message the detailed state-violation message
     * @throws Av1DecodeException if a previous frame assembly is still in progress
     */
    private fun ensureNoPendingFrameAssembly(packet: ObuPacket, message: String) {
        if (pendingFrameAssembly != null) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.STATE_VIOLATION,
                Av1DecodeStage.FRAME_ASSEMBLY,
                message,
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }
    }

    /** Ensures that a referenced `show_existing_frame` slot already contains a decoded frame state.
     * 
     * @param packet the source OBU packet
     * @param existingFrameIndex the referenced frame slot
     * @throws Av1DecodeException if the referenced frame slot has not been populated yet
     */
    private fun requireExistingFrameState(packet: ObuPacket, existingFrameIndex: Int) {
        if (existingFrameIndex < 0 || existingFrameIndex >= referenceSlots!!.size) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.STATE_VIOLATION,
                Av1DecodeStage.FRAME_DECODE,
                "show_existing_frame references an invalid frame slot",
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }
        if (referenceSlots[existingFrameIndex] == null) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.STATE_VIOLATION,
                Av1DecodeStage.FRAME_DECODE,
                "show_existing_frame references a frame slot that has not been populated",
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }
    }

    /** Prepares one `show_existing_frame` output from the requested reference slot, or `null` when
     * current public filtering suppresses presentation.
     * 
     * The output reuses the reconstructed surface stored with the slot's syntax state.
     * Showing a stored key frame refreshes every reference slot before presentation filtering.
     * 
     * @param packet the source OBU packet that requested `show_existing_frame`
     * @param outputRequestHeader the current show-existing-frame request header
     * @return one pending `show_existing_frame` output, or `null` when output filtering suppresses it
     * @throws Av1DecodeException if the referenced slot is invalid or missing complete reference state
     */
    private fun outputExistingFrame(
        packet: ObuPacket,
        outputRequestHeader: FrameHeader
    ): PendingOutput? {
        val existingFrameIndex = outputRequestHeader.existingFrameIndex()
        requireExistingFrameState(packet, existingFrameIndex)
        val referenceSurfaceSnapshot = requireNotNullCompat(
            referenceSlots!![existingFrameIndex],
            "populated reference slot"
        )
        val referencedFrameHeader = referenceSurfaceSnapshot.frameHeader
        if (config.strictStdCompliance() && !referencedFrameHeader.showableFrame()) {
            throw invalidBitstream(
                packet,
                "show_existing_frame references a frame that is not showable"
            )
        }
        if (config.largeScaleTileMode()) {
            addLargeScaleTileAnchorFrame(referenceSurfaceSnapshot, packet)
        }
        refreshReferenceState(outputRequestHeader, referenceSurfaceSnapshot)
        if (config.largeScaleTileMode()) {
            return null
        }
        if (!matchesFrameSelection(
                referencedFrameHeader.frameType(),
                referencedFrameHeader.refreshFrameFlags(),
                config.frameSelection()
            )
        ) {
            return null
        }
        val presentationPlanes = applyPresentationFilters(
            referenceSurfaceSnapshot.decodedPlanes,
            referencedFrameHeader,
            referenceSurfaceSnapshot.frameSyntaxState.sequenceHeader().colorConfig()
        )
        return PendingOutput.existing(
            presentationPlanes,
            referenceSurfaceSnapshot,
            outputRequestHeader,
            nextPresentationIndex,
            packet
        )
    }

    /** Appends one externally indexed anchor surface for later tile-list decoding.
     * 
     * @param snapshot the immutable anchor surface
     * @param packet the OBU that produced or showed the anchor
     * @throws Av1DecodeException if the AV1 maximum of 128 anchor frames is exceeded
     */
    private fun addLargeScaleTileAnchorFrame(
        snapshot: ReferenceSurfaceSnapshot,
        packet: ObuPacket
    ) {
        if (largeScaleTileAnchorFrames.size >= 128) {
            throw invalidBitstream(packet, "Large Scale Tile anchor-frame count exceeds 128")
        }
        largeScaleTileAnchorFrames.add(
            requireNotNullCompat(
                snapshot,
                "snapshot"
            )
        )
    }

    /** Applies presentation-only output filters such as film grain.
     * 
     * Stored reference surfaces remain post-filter, post-super-resolution, and pre-grain.
     * Presentation output may use a grain-applied copy when the current decoder configuration
     * requests it.
     * 
     * @param decodedPlanes the post-filter, post-super-resolution, pre-grain planes
     * @param frameHeader the normalized frame header that owns the output
     * @param colorConfig the sequence color configuration associated with the output surface
     * @return the presentation planes after output-only processing
     */
    private fun applyPresentationFilters(
        decodedPlanes: DecodedSurface,
        frameHeader: FrameHeader,
        colorConfig: Av1ColorConfig
    ): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val checkedFrameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        val checkedColorConfig = requireNotNullCompat(colorConfig, "colorConfig")
        if (config.applyFilmGrain() && checkedFrameHeader.filmGrain().applyGrain()) {
            return FilmGrainSynthesizer.apply(
                checkedDecodedPlanes,
                checkedFrameHeader,
                checkedColorConfig
            )
        }
        return checkedDecodedPlanes
    }

    /** Returns whether the current frame should be exposed through the public API.
     * 
     * @param frameHeader the current frame header
     * @return whether the frame passes visibility and frame-selection filtering
     */
    private fun shouldOutputFrame(frameHeader: FrameHeader): Boolean {
        return (frameHeader.showFrame() || config.outputInvisibleFrames())
                && matchesFrameSelection(
            frameHeader.frameType(),
            frameHeader.refreshFrameFlags(),
            config.frameSelection()
        )
    }

    /** Enforces configured and implementation frame size limits against a parsed frame header.
     * 
     * @param frameHeader the parsed frame header
     * @param packet the source OBU packet
     * @throws Av1DecodeException if a frame size limit is exceeded
     */
    private fun enforceFrameSizeLimit(frameHeader: FrameHeader, packet: ObuPacket) {
        if (frameHeader.showExistingFrame()) {
            return
        }
        enforceFrameSizeLimit(
            frameHeader.frameSize().upscaledWidth(),
            frameHeader.frameSize().height(),
            packet
        )
    }

    /** Enforces configured and implementation frame size limits against explicit output dimensions.
     * 
     * @param width the output luma width
     * @param height the output luma height
     * @param packet the source OBU packet
     * @throws Av1DecodeException if a frame size limit is exceeded
     */
    private fun enforceFrameSizeLimit(width: Int, height: Int, packet: ObuPacket) {
        val frameSizeLimit = config.frameSizeLimit()
        val pixelCount = width.toLong() * height
        val effectiveLimit = if (frameSizeLimit == 0L) Int.MAX_VALUE.toLong() else min(
            frameSizeLimit,
            Int.MAX_VALUE.toLong()
        )
        if (pixelCount > effectiveLimit) {
            val limitKind = if (frameSizeLimit != 0L && frameSizeLimit <= Int.MAX_VALUE)
                "configured"
            else
                "implementation"
            throw Av1DecodeException(
                Av1DecodeErrorCode.FRAME_SIZE_LIMIT_EXCEEDED,
                Av1DecodeStage.FRAME_HEADER_PARSE,
                "Frame size exceeds the $limitKind limit: ${width}x$height",
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }
    }

    /** Returns the compact reference state whose saved CDF should seed the next frame.
     * 
     * AV1 inherits entropy state from `primary_ref_frame` when it is present. When
     * `primary_ref_frame == PRIMARY_REF_NONE`, no reference CDF state is inherited.
     * 
     * @param frameHeader the parsed frame header for the next frame
     * @return the compact reference state that should seed the next frame, or `null`
     */
    private fun selectCdfReferenceFrameSyntaxState(frameHeader: FrameHeader): ReferenceFrameSyntaxState? {
        val primaryRefFrame = frameHeader.primaryRefFrame()
        if (primaryRefFrame < 0 || primaryRefFrame >= 7) {
            return null
        }

        val primarySlot = frameHeader.referenceFrameIndex(primaryRefFrame)
        if (primarySlot < 0 || primarySlot >= referenceSlots!!.size) {
            return null
        }
        val snapshot = referenceSlots[primarySlot]
        return if (snapshot != null) snapshot.frameSyntaxState else null
    }

    /** Creates the compact syntax state stored in refreshed reference slots.
     * 
     * When `refresh_context` is disabled the refreshed slots keep their inherited entropy state,
     * while still receiving the current frame's segment identifiers and temporal motion field.
     * 
     * @param frameHeader the parsed frame header whose refresh flags will be applied
     * @param syntaxDecodeResult the structural frame-decode result produced for the current frame
     * @param cdfReferenceState the inherited compact reference state, or `null`
     * @return the compact syntax state to store in refreshed reference slots
     */
    private fun storedReferenceFrameSyntaxState(
        frameHeader: FrameHeader,
        syntaxDecodeResult: FrameSyntaxDecodeResult,
        cdfReferenceState: ReferenceFrameSyntaxState?
    ): ReferenceFrameSyntaxState {
        val storedFrameCdfContext = if (frameHeader.refreshContext())
            syntaxDecodeResult.savedFrameCdfContext()
        else
            if (cdfReferenceState != null)
                cdfReferenceState.savedFrameCdfContext()
            else
                CdfContext.createDefault(frameHeader.quantization().baseQIndex())
        return ReferenceFrameSyntaxState.fromOwnedSavedFrameCdfContext(
            syntaxDecodeResult,
            storedFrameCdfContext
        )
    }

    /** Refreshes any reference slots targeted by the parsed frame header.
     * 
     * Only successfully reconstructed and postprocessed frames populate these slots, so a failed
     * frame never leaves partially updated parser or surface state.
     * 
     * @param frameHeader the parsed frame header whose refresh flags should be applied
     * @param referenceSurfaceSnapshot the reconstructed reference surface to store
     */
    private fun refreshReferenceState(
        frameHeader: FrameHeader,
        referenceSurfaceSnapshot: ReferenceSurfaceSnapshot
    ) {
        val checkedSnapshot = requireNotNullCompat(
            referenceSurfaceSnapshot,
            "referenceSurfaceSnapshot"
        )
        val refreshFrameFlags = frameHeader.refreshFrameFlags()
        for (i in referenceSlots!!.indices) {
            if ((refreshFrameFlags and (1 shl i)) != 0) {
                referenceSlots[i] = checkedSnapshot
            }
        }
    }

    /** Returns the current reference-frame headers as one parser-facing slot array snapshot.
     * 
     * @return the current reference-frame headers as one parser-facing slot array snapshot
     */
    private fun referenceFrameHeadersForParsing(): Array<FrameHeader?>? {
        val headers = arrayOfNullsCompat<FrameHeader?>(referenceSlots!!.size)
        for (i in referenceSlots.indices) {
            val snapshot = referenceSlots[i]
            headers[i] = if (snapshot != null) snapshot.frameHeader else null
        }
        return headers
    }

    /** Returns the current compact reference syntax states as one slot-indexed array.
     * 
     * @return the current compact reference syntax states as one slot-indexed array
     */
    private fun referenceFrameSyntaxStatesForDecoding(): Array<ReferenceFrameSyntaxState?>? {
        val states = arrayOfNullsCompat<ReferenceFrameSyntaxState?>(referenceSlots!!.size)
        for (i in referenceSlots.indices) {
            val snapshot = referenceSlots[i]
            states[i] = if (snapshot != null) snapshot.frameSyntaxState else null
        }
        return states
    }

    /** Returns the current stored reference surfaces as one slot-indexed snapshot array.
     * 
     * @return the current stored reference surfaces as one slot-indexed snapshot array
     */
    private fun currentReferenceSurfaceSnapshots(): Array<ReferenceSurfaceSnapshot?>? {
        return referenceSlots!!.copyOf(referenceSlots.size)
    }

    /** Identifies one exactly reusable decoded tile syntax result within a single Tile List OBU.
     * 
     * Syntax decoding depends on the selected camera tile and compressed payload, but not on the
     * anchor's pixel surface. Reconstruction applies that surface separately. */
    class LargeScaleTileSyntaxKey(bitstream: TileBitstream) {
        /** The immutable compressed camera-frame tile payload view. */
        val bitstream: TileBitstream

        /** The precomputed content hash code. */
        val hashCode: Int

        init {
            this.bitstream = requireNotNullCompat(bitstream, "bitstream")
            this.hashCode = 31 * (31 + bitstream.tileIndex()) + bitstream.payloadHashCode()
        }

        /** Returns whether another key selects the same source tile and payload bytes.
         * 
         * @param other the object to compare, or `null`
         * @return whether the keys describe the same syntax decoding operation
         */
        override fun equals(other: Any?): Boolean {
            return this === other
                    || other is LargeScaleTileSyntaxKey
                    && bitstream.tileIndex() == other.bitstream.tileIndex() && bitstream.hasSamePayload(
                other.bitstream
            )
        }

        /** Returns the precomputed content hash code.
         * 
         * @return the content hash code
         */
        override fun hashCode(): Int {
            return hashCode
        }
    }

    /** Identifies one exactly reusable reconstructed tile within a single Tile List OBU.
     * 
     * The key adds the anchor selection to an exact syntax key, so reuse cannot change either the
     * compressed tile syntax or its reference surface. */
    class LargeScaleTileDecodeKey(
        /** The externally indexed anchor frame used as `LAST_FRAME`. */
        val anchorFrameIndex: Int, syntaxKey: LargeScaleTileSyntaxKey
    ) {
        /** The exact compressed tile syntax identity. */
        val syntaxKey: LargeScaleTileSyntaxKey

        /** The precomputed content hash code. */
        val hashCode: Int

        init {
            this.syntaxKey = requireNotNullCompat(syntaxKey, "syntaxKey")
            this.hashCode = 31 * (31 + anchorFrameIndex) + syntaxKey.hashCode()
        }

        /** Returns whether another key selects the same anchor, source tile, and payload bytes.
         * 
         * @param other the object to compare, or `null`
         * @return whether the keys describe the same decoding operation
         */
        override fun equals(other: Any?): Boolean {
            return this === other
                    || other is LargeScaleTileDecodeKey
                    && anchorFrameIndex == other.anchorFrameIndex && syntaxKey == other.syntaxKey
        }

        /** Returns the precomputed content hash code.
         * 
         * @return the content hash code
         */
        override fun hashCode(): Int {
            return hashCode
        }
    }

    /** Holds one decoded presentation output before optional RGB conversion.
     * 
     * @param planes the postprocessed presentation planes
     * @param colorConfig the sequence color configuration for a newly decoded frame, or `null` for
     * `show_existing_frame`
     * @param frameHeader the decoded frame header or current `show_existing_frame` request
     * @param showFrame whether a newly decoded frame was directly displayable
     * @param presentationIndex the zero-based presentation index
     * @param packet the OBU that completed or requested the output
     * @param existingSurface the referenced surface for `show_existing_frame`, or `null` for a
     * newly decoded frame
     */
    data class PendingOutput(
        val planes: DecodedSurface,
        val colorConfig: Av1ColorConfig?,
        val frameHeader: FrameHeader,
        val showFrame: Boolean,
        val presentationIndex: Long,
        val packet: ObuPacket,
        val existingSurface: ReferenceSurfaceSnapshot?
    ) {
        /** Creates the public decoded-output representation.
         * 
         * @return the public decoded output
         */
        fun createOutput(): Av1DecodedOutput {
            if (existingSurface != null) {
                val referencedFrameHeader = existingSurface.frameHeader
                return Av1DecodedOutput(
                    planes,
                    existingSurface.frameSyntaxState.sequenceHeader().colorConfig(),
                    referencedFrameHeader.frameType(),
                    true,
                    presentationIndex,
                    frameHeader.temporalId(),
                    frameHeader.spatialId()
                )
            }
            return Av1DecodedOutput(
                planes,
                requireNotNullCompat(colorConfig, "colorConfig"),
                frameHeader.frameType(),
                showFrame,
                presentationIndex,
                frameHeader.temporalId(),
                frameHeader.spatialId()
            )
        }

        companion object {
            /** Creates one output for a newly decoded frame.
             * 
             * @param planes the postprocessed presentation planes
             * @param colorConfig the active sequence color configuration
             * @param frameHeader the decoded frame header
             * @param showFrame whether the frame was directly displayable
             * @param presentationIndex the zero-based presentation index
             * @param packet the OBU that completed the output
             * @return one pending output for a newly decoded frame
             */
            fun normal(
                planes: DecodedSurface,
                colorConfig: Av1ColorConfig,
                frameHeader: FrameHeader,
                showFrame: Boolean,
                presentationIndex: Long,
                packet: ObuPacket
            ): PendingOutput {
                return PendingOutput(
                    requireNotNullCompat(planes, "planes"),
                    requireNotNullCompat(colorConfig, "colorConfig"),
                    requireNotNullCompat(frameHeader, "frameHeader"),
                    showFrame,
                    presentationIndex,
                    requireNotNullCompat(packet, "packet"),
                    null
                )
            }

            /** Creates one output that presents a stored reference surface.
             * 
             * @param planes the postprocessed presentation planes
             * @param existingSurface the stored reference surface
             * @param outputRequestHeader the current `show_existing_frame` request
             * @param presentationIndex the zero-based presentation index
             * @param packet the OBU that requested the output
             * @return one pending output for a stored reference surface
             */
            fun existing(
                planes: DecodedSurface,
                existingSurface: ReferenceSurfaceSnapshot,
                outputRequestHeader: FrameHeader,
                presentationIndex: Long,
                packet: ObuPacket
            ): PendingOutput {
                return PendingOutput(
                    requireNotNullCompat(planes, "planes"),
                    null,
                    requireNotNullCompat(outputRequestHeader, "outputRequestHeader"),
                    false,
                    presentationIndex,
                    requireNotNullCompat(packet, "packet"),
                    requireNotNullCompat(
                        existingSurface,
                        "existingSurface"
                    )
                )
            }
        }
    }

    /** The immediate result of parsing one combined `FRAME` OBU.
     * 
     * Combined frames either start a normal `FrameAssembly` or resolve immediately through the
     * `show_existing_frame` output path.
     * 
     * @param frameAssembly the started frame assembly, or `null` when output resolved immediately
     * @param immediateOutput the immediate output, or `null` when assembly continues or filtering suppresses output
     */
    data class CombinedFrameStart(
        val frameAssembly: FrameAssembly?,
        val immediateOutput: PendingOutput?
    ) {
        /** Returns whether this combined frame resolved immediately through `show_existing_frame`.
         * 
         * @return whether this combined frame resolved immediately through `show_existing_frame`
         */
        fun resolvedImmediately(): Boolean {
            return frameAssembly == null
        }

        /** Returns the started frame assembly.
         * 
         * @return the started frame assembly
         */
        fun frameAssembly(): FrameAssembly {
            checkNotNull(frameAssembly) { "Combined frame start resolved without a frame assembly" }
            return frameAssembly
        }

        companion object {
            /** Creates one result that continues with normal frame assembly.
             * 
             * @param frameAssembly the started frame assembly
             * @return one result that continues with normal frame assembly
             */
            fun frameAssembly(frameAssembly: FrameAssembly): CombinedFrameStart {
                return CombinedFrameStart(
                    requireNotNullCompat(
                        frameAssembly,
                        "frameAssembly"
                    ), null
                )
            }

            /** Creates one result that resolves immediately to output.
             * 
             * @param immediateOutput the immediate output, or `null` when filtering suppresses output
             * @return one result that resolves immediately to output
             */
            fun immediateOutput(immediateOutput: PendingOutput?): CombinedFrameStart {
                return CombinedFrameStart(null, immediateOutput)
            }
        }
    }

    companion object {
        /** Maximum number of decoded Large Scale Tile syntax trees retained for near-term reuse. */
        private const val LARGE_SCALE_TILE_SYNTAX_CACHE_SIZE = 8

        /** Opens a low-overhead AV1 decoder over a byte channel.
         * 
         * On success, the returned decoder owns and closes `source`. The channel must use blocking
         * mode and remain blocking until the decoder is closed.
         * 
         * @param source the channel to read
         * @param config the immutable decoder configuration
         * @return the new AV1 decoder
         * @throws IllegalArgumentException if the channel is selectable and configured as
         * non-blocking
         */
        fun open(
            source: BufferedInput,
            config: Av1DecoderConfig = Av1DecoderConfig.DEFAULT
        ): Av1Decoder {
            return Av1Decoder(source, config, false)
        }

        fun openAnnexB(
            source: BufferedInput,
            config: Av1DecoderConfig = Av1DecoderConfig.DEFAULT
        ): Av1Decoder {
            return Av1Decoder(source, config, true)
        }

        /** Returns a buffered decoder input over a channel.
         * 
         * Internal channel implementations are retained so container-provided unit boundaries remain
         * visible to the low-overhead OBU reader.
         * 
         * @param source the channel to adapt
         * @return a buffered decoder input
         */
        fun bufferedInput(source: BufferedInput): BufferedInput = source

        /** Validates the leading LEB128 metadata type in one metadata OBU.
         * 
         * @param packet the metadata OBU packet
         * @throws Av1DecodeException if the type is truncated, wider than 32 bits, or uses a ninth byte
         */
        fun validateMetadataType(packet: ObuPacket) {
            val payload = packet.payload
            var value: Long = 0
            for (index in 0..7) {
                if (index >= payload.size) {
                    throw invalidBitstream(
                        packet,
                        "Metadata OBU has a truncated metadata_type LEB128 value"
                    )
                }
                val currentByte = payload[index].toInt() and 0xFF
                value = value or ((currentByte and 0x7F).toLong() shl (index * 7))
                if ((currentByte and 0x80) == 0) {
                    if (value > 0xFFFFFFFFL) {
                        throw invalidBitstream(
                            packet,
                            "Metadata type exceeds the unsigned 32-bit range"
                        )
                    }
                    return
                }
            }
            throw invalidBitstream(
                packet,
                "Metadata type LEB128 value continues beyond eight bytes"
            )
        }

        /** Returns whether two sequence color configurations may share reference frames.
         * 
         * @param stored the color configuration used by the stored reference frame
         * @param current the current sequence color configuration
         * @return whether every reference-relevant color property matches
         */
        fun compatibleReferenceColorConfig(
            stored: Av1ColorConfig,
            current: Av1ColorConfig
        ): Boolean {
            return stored.bitDepth == current.bitDepth && stored.monochrome == current.monochrome && stored.colorPrimaries == current.colorPrimaries && stored.transferCharacteristics == current.transferCharacteristics && stored.matrixCoefficients == current.matrixCoefficients && stored.colorRange == current.colorRange && stored.chromaFormat == current.chromaFormat && stored.chromaSamplePosition == current.chromaSamplePosition
        }

        /** Retains one decoded syntax tree in the bounded insertion-order reuse cache.
         * 
         * @param cache the mutable syntax cache
         * @param syntaxKey the exact compressed tile syntax identity
         * @param syntaxDecodeResult the decoded syntax tree to retain
         */
        fun cacheLargeScaleTileSyntax(
            cache: MutableMap<LargeScaleTileSyntaxKey, FrameSyntaxDecodeResult>,
            syntaxKey: LargeScaleTileSyntaxKey,
            syntaxDecodeResult: FrameSyntaxDecodeResult
        ) {
            if (cache.size >= LARGE_SCALE_TILE_SYNTAX_CACHE_SIZE) {
                val oldestKey = cache.keys.iterator().next()
                cache.remove(oldestKey)
            }
            cache.put(syntaxKey, syntaxDecodeResult)
        }

        /** Records one syntax-key use and releases state after its final occurrence.
         * 
         * @param syntaxKey the exact compressed tile syntax identity
         * @param remainingUseCount the use count before consuming the current entry
         * @param remainingUses the mutable remaining-use counts
         * @param cache the mutable decoded syntax cache
         */
        fun finishLargeScaleTileSyntaxUse(
            syntaxKey: LargeScaleTileSyntaxKey,
            remainingUseCount: Int,
            remainingUses: MutableMap<LargeScaleTileSyntaxKey, Int>,
            cache: MutableMap<LargeScaleTileSyntaxKey, FrameSyntaxDecodeResult>
        ) {
            if (remainingUseCount <= 1) {
                remainingUses.remove(syntaxKey)
                cache.remove(syntaxKey)
            } else {
                remainingUses.put(syntaxKey, remainingUseCount - 1)
            }
        }

        /** Verifies that every inter block in one Large Scale Tile uses `LAST_FRAME`.
         * 
         * @param syntaxDecodeResult the decoded camera-tile syntax
         * @param tileIndex the decoded source tile index
         * @param packet the source tile-list OBU
         * @throws Av1DecodeException if a block selects another reference frame
         */
        fun validateLargeScaleTileReferences(
            syntaxDecodeResult: FrameSyntaxDecodeResult,
            tileIndex: Int,
            packet: ObuPacket
        ) {
            for (root in syntaxDecodeResult.tileRoots(tileIndex)) {
                validateLargeScaleTileReferences(root, packet)
            }
        }

        /** Verifies the reference selection in one decoded partition subtree.
         * 
         * @param node the decoded partition node
         * @param packet the source tile-list OBU
         * @throws Av1DecodeException if an inter leaf does not use `LAST_FRAME`
         */
        fun validateLargeScaleTileReferences(
            node: TilePartitionTreeReader.Node,
            packet: ObuPacket
        ) {
            if (node is TilePartitionTreeReader.LeafNode) {
                val referenceFrame = node.header.referenceFrame0()
                if (referenceFrame >= 0 && referenceFrame != 0) {
                    throw invalidBitstream(packet, "Large Scale Tile blocks must use LAST_FRAME")
                }
                return
            }
            val partitionNode =
                node as PartitionNode
            for (childIndex in 0..<partitionNode.childCount()) {
                validateLargeScaleTileReferences(partitionNode.child(childIndex), packet)
            }
        }

        /** Returns whether one frame matches the requested frame selection.
         * 
         * @param frameType the decoded frame type
         * @param refreshFrameFlags the decoded reference-slot refresh mask
         * @param frameSelection the requested frame selection
         * @return whether the frame matches the requested selection
         */
        fun matchesFrameSelection(
            frameType: Av1FrameType,
            refreshFrameFlags: Int,
            frameSelection: Av1FrameSelection
        ): Boolean {
            return when (frameSelection) {
                Av1FrameSelection.ALL -> true
                Av1FrameSelection.REFERENCE -> refreshFrameFlags != 0
                Av1FrameSelection.INTRA -> frameType == Av1FrameType.KEY || frameType == Av1FrameType.INTRA
                Av1FrameSelection.KEY -> frameType == Av1FrameType.KEY
            }
        }

        /** Creates a contextual invalid-bitstream exception for frame-assembly errors.
         * 
         * @param packet the source OBU packet
         * @param message the detailed validation message
         * @return the contextual invalid-bitstream exception
         */
        fun invalidBitstream(packet: ObuPacket, message: String): Av1DecodeException {
            return Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.FRAME_ASSEMBLY,
                message,
                packet.streamOffset,
                packet.obuIndex,
                null
            )
        }

        /** Returns a contextual checked failure for an unsupported output color conversion.
         * 
         * @param packet the OBU whose frame reached output conversion
         * @param exception the unsupported conversion failure
         * @return the contextual unsupported-feature exception
         */
        fun unsupportedOutputConversion(
            packet: ObuPacket,
            exception: UnsupportedOperationException
        ): Av1DecodeException {
            return Av1DecodeException(
                Av1DecodeErrorCode.UNSUPPORTED_FEATURE,
                Av1DecodeStage.OUTPUT_CONVERSION,
                if (exception.message != null)
                    exception.message
                else
                    "AV1 output uses an unsupported color conversion",
                packet.streamOffset,
                packet.obuIndex,
                null,
                exception
            )
        }

        /** Creates an error for end-of-stream while a frame assembly is still incomplete.
         * 
         * @param assembly the incomplete frame assembly
         * @return the contextual invalid-bitstream exception
         */
        fun incompleteFrameAtEndOfStream(assembly: FrameAssembly): Av1DecodeException {
            return Av1DecodeException(
                Av1DecodeErrorCode.INVALID_BITSTREAM,
                Av1DecodeStage.FRAME_ASSEMBLY,
                "End of stream was reached before the current frame tile groups were completed",
                assembly.streamOffset(),
                assembly.obuIndex(),
                null
            )
        }
    }
}
