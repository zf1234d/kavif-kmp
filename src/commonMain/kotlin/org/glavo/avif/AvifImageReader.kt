// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min

import org.glavo.avif.av1.Av1ColorConfig
import org.glavo.avif.av1.Av1DecodeErrorCode
import org.glavo.avif.av1.Av1DecodeException
import org.glavo.avif.av1.Av1DecodedOutput
import org.glavo.avif.av1.Av1Decoder
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.output.ArgbOutput
import org.glavo.avif.internal.av1.output.YuvToRgbTransform
import org.glavo.avif.internal.bmff.AvifContainer
import org.glavo.avif.internal.bmff.AvifContainerParser
import org.glavo.avif.internal.bmff.AvifImageSource
import org.glavo.avif.internal.bmff.AvifPayload
import org.glavo.avif.internal.bmff.SampleTransform
import org.glavo.avif.internal.io.AvifDataSource


/** High-level reader for AVIF images.
 * 
 * Readers opened from an input stream or readable channel support sequential [readFrame()] and
 * [readAllFrames()] access. Their indexed, raw-plane, and tone-mapping operations fail with
 * [AvifErrorCode.SEEKABLE_SOURCE_REQUIRED]. Sequential decoding also reports that code when the
 * container layout requires revisiting data outside the bounded progressive read window.
 * After a sequence frame fails, indexed operations remain available for seekable sources, but
 * sequential reading cannot resume because its persistent AV1 decoder may have consumed input. */
class AvifImageReader internal constructor(
    source: AvifDataSource,
    factory: AvifImageReaderFactory
) : AutoCloseable {
    /** The immutable factory options used to create this reader. */
    private val factory: AvifImageReaderFactory

    /** The retained container source. */
    private val source: AvifDataSource

    /** The parsed container data. */
    private val container: AvifContainer

    /** The retained color sequence sample descriptors, or `null` for a still image. */
    private val sequenceSamplePayloads: Array<AvifPayload>?

    /** The retained alpha sequence sample descriptors, or `null` when absent. */
    private val sequenceAlphaSamplePayloads: Array<AvifPayload>?

    /** The retained depth sequence sample descriptors, or `null` when absent. */
    private val sequenceDepthSamplePayloads: Array<AvifPayload>?

    /** The next frame index for sequential reads. */
    private var nextFrameIndex = 0

    /** Whether this reader has been closed. */
    private var closed = false

    /** A persistent AV1 decoder for image sequences, or `null`. */
    private var sequenceAv1Decoder: Av1Decoder? = null

    /** The expected next frame index from the persistent sequence reader. */
    private var sequenceAv1FrameIndex = 0

    /** A persistent AV1 decoder for image-sequence alpha samples, or `null`. */
    private var sequenceAlphaAv1Decoder: Av1Decoder? = null

    /** The expected next frame index from the persistent sequence alpha reader. */
    private var sequenceAlphaAv1FrameIndex = 0

    /** Whether a sequence frame failed after sequential decoding began. */
    private var sequentialSequenceReadFailed = false

    /** The reusable indexed color-sequence cursor, or `null` before the first indexed read. */
    private var indexedColorSequenceCursor: SequenceDecoderCursor? = null

    /** The reusable indexed alpha-sequence cursor, or `null` before the first indexed read. */
    private var indexedAlphaSequenceCursor: SequenceDecoderCursor? = null

    /** The reusable indexed depth-sequence cursor, or `null` before the first indexed read. */
    private var indexedDepthSequenceCursor: SequenceDecoderCursor? = null

    init {
        this.factory = requireNotNullCompat(factory, "factory")
        this.source = requireNotNullCompat(source, "source")
        try {
            this.container =
                AvifContainerParser.parse(source, factory.metadataSizeLimit())
            this.sequenceSamplePayloads = container.samplePayloads()
            this.sequenceAlphaSamplePayloads = container.sequenceAlphaSamplePayloads()
            this.sequenceDepthSamplePayloads = container.sequenceDepthSamplePayloads()
        } catch (exception: AvifDecodeException) {
            try {
                source.close()
            } catch (closeException: AvifIoException) {
                exception.addSuppressed(closeException)
            }
            throw exception
        } catch (exception: RuntimeException) {
            try {
                source.close()
            } catch (closeException: AvifIoException) {
                exception.addSuppressed(closeException)
            }
            throw exception
        } catch (exception: Error) {
            try {
                source.close()
            } catch (closeException: AvifIoException) {
                exception.addSuppressed(closeException)
            }
            throw exception
        }
    }

    /** Returns immutable image metadata parsed from the container.
     * 
     * @return immutable image metadata parsed from the container
     * @throws AvifDecodeException if this reader is closed
     */
    fun info(): AvifImageInfo {
        ensureOpen()
        return container.info()
    }

    /** Reads the next decoded frame.
     * 
     * If a sequence frame fails, subsequent calls fail without attempting to reuse the partially
     * advanced persistent sequence decoder. Indexed access remains available when the source is
     * seekable.
     * 
     * @return the next decoded frame, or `null` at end-of-stream
     * @throws AvifIoException if the frame cannot be decoded or sequential sequence decoding has
     * already failed
     */
    fun readFrame(): AvifFrame? {
        ensureOpen()
        if (sequentialSequenceReadFailed) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sequential sequence decoding cannot resume after a frame failure",
                null
            )
        }
        if (nextFrameIndex >= container.info().frameCount()) {
            return null
        }
        try {
            val frame = if (container.isSequence)
                readSequenceFrameSequential(nextFrameIndex)
            else
                readFrameAtIndex(nextFrameIndex)
            nextFrameIndex++
            return frame
        } catch (exception: AvifIoException) {
            if (container.isSequence) {
                sequentialSequenceReadFailed = true
            }
            throw exception
        } catch (exception: RuntimeException) {
            if (container.isSequence) {
                sequentialSequenceReadFailed = true
            }
            throw exception
        }
    }

    /** Reads the decoded frame at the supplied index.
     * 
     * For image sequences, forward indexed reads reuse decoder state. Reading an index lower than
     * the most recently decoded indexed frame restarts the indexed decoder from the beginning and
     * does not affect sequential [readFrame()] state.
     * 
     * @param frameIndex the zero-based frame index
     * @return the decoded frame
     * @throws AvifIoException if the frame cannot be decoded or the reader was opened from a
     * forward-only stream or channel
     */
    fun readFrame(frameIndex: Int): AvifFrame {
        ensureRandomAccess("readFrame(int)")
        return readFrameAtIndex(frameIndex)
    }

    /** Reads a frame without applying the public indexed-access capability check.
     * 
     * @param frameIndex the zero-based frame index
     * @return the decoded frame
     * @throws AvifIoException if the frame cannot be decoded
     */
    private fun readFrameAtIndex(frameIndex: Int): AvifFrame {
        ensureOpen()
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        if (container.isSequence) {
            return readSequenceFrameRandomAccess(frameIndex)
        }
        val sampleTransform = container.sampleTransform()
        if (sampleTransform != null) {
            return readSampleTransformedFrame(frameIndex, sampleTransform)
        }
        val primarySource = container.primarySource()
        if (primarySource == null) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Primary AV1 item payload is missing",
                null
            )
        }
        val decodedColor = decodeImageSource(primarySource, "Primary AV1 image")
        var rawFrame: AvifFrame = adaptRawPlanes(
            decodedColor.planes,
            decodedColor.colorConfig,
            container.info().colorInfo(),
            frameIndex,
            factory.outputPixelFormat()
        )
        val alphaSource = container.alphaSource()
        if (alphaSource != null) {
            val alphaPlanes = decodeAlphaImageSource(
                alphaSource,
                "Alpha auxiliary AV1 image",
                decodedColor.colorConfig.bitDepth
            ).planes
            if (alphaPlanes.codedWidth() != rawFrame.width() || alphaPlanes.codedHeight() != rawFrame.height()) {
                throw AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Alpha dimensions differ from color dimensions",
                    null
                )
            }
            rawFrame = combineFrameWithAlphaPlane(
                rawFrame,
                alphaPlanes,
                alphaPlanes.bitDepth(),
                frameIndex,
                container.info().alphaPremultiplied()
            )
        }
        return applyTransforms(rawFrame)
    }

    /** Reads raw decoded color planes for the frame at the supplied index.
     * 
     * The returned planes expose the decoded AV1 color image before AVIF auxiliary alpha
     * composition and before AVIF item transforms such as `clap`, `irot`, and `imir`.
     * Grid-derived still images are returned as composed raw planes.
     * 
     * @param frameIndex the zero-based frame index
     * @return raw decoded color planes
     * @throws AvifIoException if the frame cannot be decoded or the reader was opened from a
     * forward-only stream or channel
     */
    fun readRawColorPlanes(frameIndex: Int): Av1DecodedPlanes {
        ensureRandomAccess("readRawColorPlanes(int)")
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        if (container.isSequence) {
            return readSequenceRawColorPlanes(frameIndex)
        }
        val sampleTransform = container.sampleTransform()
        if (sampleTransform != null) {
            return decodeSampleTransform(sampleTransform, false).planes
        }
        val primarySource = container.primarySource()
        if (primarySource == null) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Primary AV1 item payload is missing",
                null
            )
        }
        return decodeImageSource(primarySource, "Primary AV1 image").planes
    }

    /** Reads raw decoded alpha auxiliary planes for the frame at the supplied index.
     * 
     * The returned planes expose an alpha auxiliary AV1 image before AVIF item transforms are
     * applied. They are monochrome and full range; color-coded legacy alpha images expose only
     * their luma plane, and legacy limited-range alpha samples are expanded to full range. A
     * `null` return value means the frame has no alpha auxiliary image.
     * 
     * @param frameIndex the zero-based frame index
     * @return raw decoded alpha auxiliary planes, or `null` when no alpha auxiliary image is present
     * @throws AvifIoException if the alpha auxiliary image cannot be decoded or the reader was opened
     * from a forward-only stream or channel
     */
    fun readRawAlphaPlanes(frameIndex: Int): Av1DecodedPlanes? {
        ensureRandomAccess("readRawAlphaPlanes(int)")
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        if (!container.info().alphaPresent()) {
            return null
        }
        if (container.isSequence) {
            val alphaPayloads = sequenceAlphaSamplePayloads
            if (alphaPayloads == null) {
                return null
            }
            val decodedAlpha = readSequenceRawImage(
                frameIndex,
                alphaPayloads,
                "Alpha sequence frame"
            )
            return normalizeAlphaPlanes(
                decodedAlpha.planes,
                decodedAlpha.colorConfig,
                container.info().bitDepth(),
                "Alpha sequence frame"
            )
        }
        val sampleTransform = container.sampleTransform()
        if (sampleTransform != null) {
            return decodeSampleTransform(sampleTransform, true).planes
        }
        val alphaSource = container.alphaSource()
        return if (alphaSource != null)
            decodeAlphaImageSource(
                alphaSource,
                "Alpha auxiliary AV1 image",
                container.info().bitDepth()
            ).planes
        else
            null
    }

    /** Reads raw decoded gain-map planes for the frame at the supplied index.
     * 
     * The returned planes expose the AV1 image referenced by the AVIF `tmap` gain-map
     * association. A `null` return value means the frame has no gain-map image. The returned
     * planes are not tone-mapped or applied to the base image.
     * 
     * @param frameIndex the zero-based frame index
     * @return raw decoded gain-map planes, or `null` when no gain-map image is present
     * @throws AvifIoException if the gain-map image cannot be decoded or the reader was opened from a
     * forward-only stream or channel
     */
    fun readRawGainMapPlanes(frameIndex: Int): Av1DecodedPlanes? {
        ensureRandomAccess("readRawGainMapPlanes(int)")
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        val gainMapInfo = container.info().gainMapInfo()
        if (gainMapInfo == null) {
            return null
        }
        val gainMapSource = container.gainMapSource()
        if (gainMapSource != null) {
            return decodeImageSource(gainMapSource, "Gain-map AV1 image").planes
        }
        throw unsupported("Gain-map item type is not decodable as AV1 planes: " + gainMapInfo.gainMapItemType())
    }

    /** Reads the decoded frame at the supplied index with its AVIF gain map applied.
     * 
     * The returned frame preserves the regular `readFrame(int)` alpha composition, item
     * transforms, frame index, and packed ARGB pixel format. A `null` return value means the frame
     * has no supported gain-map association.
     * 
     * @param frameIndex the zero-based frame index
     * @param hdrHeadroom the requested display HDR headroom in log2 space
     * @return the tone-mapped frame, or `null` when no supported gain map is present
     * @throws AvifIoException if the base frame or gain-map image cannot be decoded
     */
    fun readToneMappedFrame(frameIndex: Int, hdrHeadroom: Double): AvifFrame? {
        return readToneMappedFrameInternal(frameIndex, hdrHeadroom, null)
    }

    /** Reads the decoded frame at the supplied index with its AVIF gain map applied.
     * 
     * The returned frame preserves the regular `readFrame(int)` alpha composition, item
     * transforms, frame index, and packed ARGB pixel format. RGB channels are converted into the
     * requested CICP output color space after gain-map application. ICC profile application
     * remains metadata-only.
     * 
     * @param frameIndex the zero-based frame index
     * @param hdrHeadroom the requested display HDR headroom in log2 space
     * @param outputColorInfo the requested output CICP color information
     * @return the tone-mapped frame, or `null` when no supported gain map is present
     * @throws AvifIoException if the base frame or gain-map image cannot be decoded
     */
    fun readToneMappedFrame(
        frameIndex: Int,
        hdrHeadroom: Double,
        outputColorInfo: AvifColorInfo
    ): AvifFrame? {
        return readToneMappedFrameInternal(
            frameIndex,
            hdrHeadroom,
            requireNotNullCompat(outputColorInfo, "outputColorInfo")
        )
    }

    /** Reads the decoded frame at the supplied index with optional output color conversion.
     * 
     * @param frameIndex the zero-based frame index
     * @param hdrHeadroom the requested display HDR headroom in log2 space
     * @param outputColorInfo the requested output CICP color information, or `null` for base color space
     * @return the tone-mapped frame, or `null` when no supported gain map is present
     * @throws AvifIoException if the base frame or gain-map image cannot be decoded
     */
    private fun readToneMappedFrameInternal(
        frameIndex: Int,
        hdrHeadroom: Double,
        outputColorInfo: AvifColorInfo?
    ): AvifFrame? {
        ensureRandomAccess("readToneMappedFrame")
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        require(!(!hdrHeadroom.isFinite() || hdrHeadroom < 0.0)) { "hdrHeadroom must be a finite non-negative value" }
        val gainMapInfo = container.info().gainMapInfo()
        if (gainMapInfo == null) {
            return null
        }
        val metadata = gainMapInfo.metadata()
        if (metadata == null) {
            return null
        }
        var gainMapPlanes: Av1DecodedPlanes? = null
        if (AvifGainMapToneMapper.requiresGainMap(metadata, hdrHeadroom)) {
            gainMapPlanes = readRawGainMapPlanes(frameIndex)
            if (gainMapPlanes == null) {
                return null
            }
        }
        try {
            return AvifGainMapToneMapper.apply(
                readFrame(frameIndex),
                gainMapPlanes,
                metadata,
                container.info().colorInfo(),
                gainMapInfo.toneMappedColorInfo(),
                gainMapInfo.gainMapColorInfo(),
                outputColorInfo,
                hdrHeadroom
            )
        } catch (exception: UnsupportedOperationException) {
            throw unsupportedColorConversion(exception)
        }
    }

    /** Reads raw decoded depth auxiliary planes for the frame at the supplied index.
     * 
     * The returned planes expose a depth auxiliary AV1 image before AVIF item transforms are
     * applied. A `null` return value means the frame has no depth auxiliary image.
     * 
     * @param frameIndex the zero-based frame index
     * @return raw decoded depth auxiliary planes, or `null` when no depth auxiliary image is present
     * @throws AvifIoException if the depth auxiliary image cannot be decoded or the reader was opened
     * from a forward-only stream or channel
     */
    fun readRawDepthPlanes(frameIndex: Int): Av1DecodedPlanes? {
        ensureRandomAccess("readRawDepthPlanes(int)")
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        if (container.isSequence) {
            val depthPayloads = sequenceDepthSamplePayloads
            if (depthPayloads == null) {
                return null
            }
            return readSequenceRawAuxiliaryPlanes(frameIndex, depthPayloads, "Depth sequence frame")
        }
        val depthSource = container.depthSource()
        if (depthSource != null) {
            return decodeImageSource(depthSource, "Depth auxiliary AV1 image").planes
        }
        if (!hasAuxiliaryType(container.info(), AvifAuxiliaryImageInfo.Companion.DEPTH_TYPE)) {
            return null
        }
        throw unsupported("Depth auxiliary item type is not decodable as AV1 planes")
    }

    /** Decodes the next frame from an image sequence using the persistent sequential AV1 decoder.
     * 
     * @param frameIndex the zero-based frame index
     * @return the decoded frame
     * @throws AvifIoException if decoding fails
     */
    private fun readSequenceFrameSequential(frameIndex: Int): AvifFrame {
        val payloads = sequenceSamplePayloads
        if (payloads == null || frameIndex >= payloads.size) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        if (frameIndex != sequenceAv1FrameIndex && sequenceAv1Decoder != null) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sequential AVIF sequence reader is out of sync at frame $frameIndex",
                null
            )
        }
        if (sequenceAv1Decoder == null) {
            sequenceAv1Decoder = Av1Decoder.open(
                AvifPayload.openInput(payloads),
                factory.av1DecoderConfig()
            )
            sequenceAv1FrameIndex = 0
        }
        while (sequenceAv1FrameIndex < frameIndex) {
            val skipped = sequenceAv1Decoder!!.readOutput()
            if (skipped == null) throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sequence ended before frame $frameIndex",
                null
            )
            sequenceAv1FrameIndex++
        }
        try {
            val output = sequenceAv1Decoder!!.readOutput()
            if (output == null) throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sequence produced no frame: $frameIndex",
                null
            )
            sequenceAv1FrameIndex++
            val rawFrame: AvifFrame = adaptRawPlanes(
                output.planes(),
                output.colorConfig(),
                container.info().colorInfo(),
                frameIndex,
                factory.outputPixelFormat()
            )
            return combineFrameWithSequenceAlphaSequential(rawFrame, frameIndex)
        } catch (exception: AvifDecodeException) {
            throw exception
        } catch (exception: AvifIoException) {
            throw wrapAv1DecodeFailure(exception)
        }
    }

    /** Decodes raw color planes for one image-sequence frame without mutating sequential playback state.
     * 
     * @param frameIndex the zero-based frame index
     * @return raw decoded color planes
     * @throws AvifIoException if decoding fails
     */
    private fun readSequenceRawColorPlanes(frameIndex: Int): Av1DecodedPlanes {
        val payloads = sequenceSamplePayloads
        if (payloads == null || frameIndex >= payloads.size) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        return readSequenceRawImage(frameIndex, payloads, "Sequence frame").planes
    }

    /** Decodes raw planes for one image-sequence auxiliary frame without mutating playback state.
     * 
     * @param frameIndex the zero-based frame index
     * @param payloads the auxiliary sample payloads
     * @param label the diagnostic label for failures
     * @return raw decoded auxiliary planes
     * @throws AvifIoException if decoding fails
     */
    private fun readSequenceRawAuxiliaryPlanes(
        frameIndex: Int,
        payloads: Array<AvifPayload>,
        label: String
    ): Av1DecodedPlanes {
        if (frameIndex >= payloads.size) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        return readSequenceRawImage(frameIndex, payloads, label).planes
    }

    /** Decodes raw planes for one image-sequence frame without mutating playback state.
     * 
     * @param frameIndex the zero-based frame index
     * @param payloads the sample payloads
     * @param label the diagnostic label for failures
     * @return raw decoded planes
     * @throws AvifIoException if decoding fails
     */
    private fun readSequenceRawImage(
        frameIndex: Int,
        payloads: Array<AvifPayload>,
        label: String
    ): DecodedRawImage {
        try {
            val requiredOutput = indexedSequenceCursor(payloads, label).read(frameIndex)
            return DecodedRawImage(requiredOutput.planes(), requiredOutput.colorConfig())
        } catch (exception: AvifDecodeException) {
            throw exception
        } catch (exception: AvifIoException) {
            throw wrapAv1DecodeFailure(exception)
        }
    }

    /** Returns the reusable indexed decoder cursor for one retained sequence payload array.
     * 
     * @param payloads the retained color, alpha, or depth payload array
     * @param label the diagnostic sequence label used when creating a cursor
     * @return the matching reusable decoder cursor
     */
    private fun indexedSequenceCursor(
        payloads: Array<AvifPayload>,
        label: String
    ): SequenceDecoderCursor {
        if (payloads === sequenceSamplePayloads) {
            if (indexedColorSequenceCursor == null) {
                indexedColorSequenceCursor = SequenceDecoderCursor(payloads, label)
            }
            return indexedColorSequenceCursor!!
        }
        if (payloads === sequenceAlphaSamplePayloads) {
            if (indexedAlphaSequenceCursor == null) {
                indexedAlphaSequenceCursor = SequenceDecoderCursor(payloads, label)
            }
            return indexedAlphaSequenceCursor!!
        }
        if (payloads === sequenceDepthSamplePayloads) {
            if (indexedDepthSequenceCursor == null) {
                indexedDepthSequenceCursor = SequenceDecoderCursor(payloads, label)
            }
            return indexedDepthSequenceCursor!!
        }
        throw IllegalArgumentException("payloads are not retained by this reader")
    }

    /** Decodes one AV1 item payload and selects its requested output spatial layer.
     * 
     * @param payload the AV1 payload to decode
     * @param label the diagnostic label for failures
     * @param operatingPoint the selected AV1 operating-point index
     * @param selectedSpatialLayer the selected spatial-layer identifier, or
     * [AvifImageSource.HIGHEST_SPATIAL_LAYER]
     * @return decoded raw planes and their AV1 color configuration
     * @throws AvifIoException if decoding fails
     */
    private fun decodeRawImage(
        payload: AvifPayload,
        label: String,
        operatingPoint: Int,
        selectedSpatialLayer: Int
    ): DecodedRawImage {
        try {
            Av1Decoder.open(
                payload.openInput(),
                factory.av1DecoderConfig().withOperatingPoint(operatingPoint)
            ).use { rawDecoder ->
                var selectedImage: DecodedRawImage? = null
                var highestSpatialId = -1
                while (true) {
                    val output: Av1DecodedOutput? = rawDecoder.readOutput()
                    if (output == null) {
                        break
                    }
                    val matchesSelection =
                        if (selectedSpatialLayer == AvifImageSource.HIGHEST_SPATIAL_LAYER)
                            output.spatialId() >= highestSpatialId
                        else
                            output.spatialId() == selectedSpatialLayer
                    if (!matchesSelection) {
                        continue
                    }
                    selectedImage = DecodedRawImage(
                        output.planes(),
                        output.colorConfig()
                    )
                    highestSpatialId = output.spatialId()
                    if (selectedSpatialLayer != AvifImageSource.HIGHEST_SPATIAL_LAYER) {
                        break
                    }
                }
                if (selectedImage == null) {
                    val message =
                        if (selectedSpatialLayer == AvifImageSource.HIGHEST_SPATIAL_LAYER)
                            label + " produced no frame"
                        else
                            label + " produced no output for selected spatial layer $selectedSpatialLayer"
                    throw AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, message, null)
                }
                return selectedImage
            }
        } catch (exception: AvifDecodeException) {
            throw exception
        } catch (exception: AvifIoException) {
            throw wrapAv1DecodeFailure(exception)
        }
    }

    /** Decodes and applies one parsed Sample Transform.
     * 
     * @param sampleTransform the parsed Sample Transform
     * @param alpha whether to reconstruct alpha rather than color planes
     * @return the reconstructed planes and primary-input AV1 color configuration
     * @throws AvifIoException if one input image cannot be decoded
     */
    private fun decodeSampleTransform(
        sampleTransform: SampleTransform,
        alpha: Boolean
    ): DecodedSampleTransform {
        val inputPlanes: Array<Av1DecodedPlanes> =
            arrayOfNullsCompat<Av1DecodedPlanes>(sampleTransform.inputCount())
        var primaryColorConfig: Av1ColorConfig? = null
        for (inputIndex in 0..<sampleTransform.inputCount()) {
            val input = sampleTransform.input(inputIndex)
            val source = if (alpha) input.alphaSource else input.colorSource
            if (source == null) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Sample Transform alpha input is missing: $inputIndex",
                    null
                )
            }
            val label =
                "Sample Transform " + (if (alpha) "alpha " else "color ") + "input $inputIndex"
            val decoded = if (alpha)
                decodeAlphaImageSource(source, label, input.colorBitDepth)
            else
                decodeImageSource(source, label)
            inputPlanes[inputIndex] = decoded.planes
            if (inputIndex == sampleTransform.primaryInputIndex()) {
                primaryColorConfig = decoded.colorConfig
            }
        }
        if (primaryColorConfig == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "Sample Transform primary input is missing",
                null
            )
        }
        try {
            val reconstructed = if (alpha)
                sampleTransform.applyAlpha(inputPlanes)
            else
                sampleTransform.apply(inputPlanes)
            return DecodedSampleTransform(reconstructed, primaryColorConfig)
        } catch (exception: IllegalArgumentException) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sample Transform input planes cannot be reconstructed: " + exception.message,
                null,
                exception
            )
        } catch (exception: ArithmeticException) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sample Transform input planes cannot be reconstructed: " + exception.message,
                null,
                exception
            )
        }
    }

    /** Decodes an alpha AV1 image source and validates its required AVIF signaling.
     * 
     * @param source the alpha image source
     * @param label the diagnostic label for failures
     * @param expectedBitDepth the associated master-image bit depth
     * @return decoded raw alpha planes and their AV1 color configuration
     * @throws AvifIoException if the source cannot be decoded or violates AVIF alpha requirements
     */
    private fun decodeAlphaImageSource(
        source: AvifImageSource,
        label: String,
        expectedBitDepth: AvifBitDepth
    ): DecodedRawImage {
        return decodeImageSource(source, label, expectedBitDepth)
    }

    /** Decodes one standalone or grid-derived AV1 image source with optional alpha validation.
     * 
     * @param source the image source
     * @param label the diagnostic label for failures
     * @param expectedAlphaBitDepth the required alpha bit depth, or `null` for a color image
     * @return decoded raw planes and their AV1 color configuration
     * @throws AvifIoException if the image source cannot be decoded
     */
    private fun decodeImageSource(
        source: AvifImageSource,
        label: String,
        expectedAlphaBitDepth: AvifBitDepth? = null
    ): DecodedRawImage {
        if (!source.isGrid) {
            val decoded = decodeRawImage(
                source.payload(0),
                label,
                source.operatingPoint(0),
                source.selectedSpatialLayer(0)
            )
            validateDecodedItemDimensions(source, 0, decoded.planes, label)
            if (expectedAlphaBitDepth != null) {
                return DecodedRawImage(
                    normalizeAlphaPlanes(
                        decoded.planes,
                        decoded.colorConfig,
                        expectedAlphaBitDepth,
                        label
                    ),
                    decoded.colorConfig
                )
            }
            return decoded
        }
        enforceGridFrameSizeLimit(source, label)
        val cellPayloads: Array<AvifPayload> = source.payloads()
        if (cellPayloads.size == 0) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " grid has no cells",
                null
            )
        }
        val cellPlanes: Array<Av1DecodedPlanes> = arrayOfNullsCompat<Av1DecodedPlanes>(cellPayloads.size)
        var colorConfig: Av1ColorConfig? = null
        for (cellIndex in cellPayloads.indices) {
            val decoded = decodeRawImage(
                cellPayloads[cellIndex],
                label + " grid cell $cellIndex",
                source.operatingPoint(cellIndex),
                source.selectedSpatialLayer(cellIndex)
            )
            validateDecodedItemDimensions(
                source,
                cellIndex,
                decoded.planes,
                label + " grid cell $cellIndex"
            )
            if (expectedAlphaBitDepth != null) {
                cellPlanes[cellIndex] = normalizeAlphaPlanes(
                    decoded.planes,
                    decoded.colorConfig,
                    expectedAlphaBitDepth,
                    label + " grid cell $cellIndex"
                )
            } else {
                cellPlanes[cellIndex] = decoded.planes
            }
            if (colorConfig == null) {
                colorConfig = decoded.colorConfig
            }
        }
        validateGridGeometry(
            cellPlanes,
            source.rows(),
            source.columns(),
            source.outputWidth(),
            source.outputHeight(),
            label
        )
        val composed: Av1DecodedPlanes = composeGridRawColorPlanes(
            cellPlanes,
            source.rows(),
            source.columns(),
            source.outputWidth(),
            source.outputHeight()
        )
        return DecodedRawImage(
            composed,
            requireNotNullCompat(colorConfig, "colorConfig")
        )
    }

    /** Enforces configured and implementation frame-size limits against a derived grid canvas.
     * 
     * Individual AV1 cells are checked by `Av1Decoder`; this additional check prevents a
     * collection of individually valid cells from producing an oversized composed image.
     * 
     * @param source the normalized grid image source
     * @param label the diagnostic image label
     * @throws AvifDecodeException if the grid canvas exceeds a frame-size limit
     */
    private fun enforceGridFrameSizeLimit(source: AvifImageSource, label: String) {
        val frameSizeLimit = factory.av1DecoderConfig().frameSizeLimit()
        val pixelCount = source.outputWidth().toLong() * source.outputHeight()
        val effectiveLimit = if (frameSizeLimit == 0L) Int.MAX_VALUE.toLong() else min(
            frameSizeLimit,
            Int.MAX_VALUE.toLong()
        )
        if (pixelCount > effectiveLimit) {
            val limitKind = if (frameSizeLimit != 0L && frameSizeLimit <= Int.MAX_VALUE)
                "configured"
            else
                "implementation"
            throw AvifDecodeException(
                AvifErrorCode.FRAME_SIZE_LIMIT_EXCEEDED,
                (label + " grid size exceeds the $limitKind limit: "
                        + source.outputWidth() + "x" + source.outputHeight()),
                null
            )
        }
    }

    /** Decodes and renders one preferred Sample Transform still image.
     * 
     * @param frameIndex the zero-based frame index
     * @param sampleTransform the parsed Sample Transform
     * @return the reconstructed and transformed AVIF frame
     * @throws AvifIoException if an input image cannot be decoded
     */
    private fun readSampleTransformedFrame(
        frameIndex: Int,
        sampleTransform: SampleTransform
    ): AvifFrame {
        val decodedColor = decodeSampleTransform(sampleTransform, false)
        var rawFrame: AvifFrame = adaptRawPlanes(
            decodedColor.planes,
            decodedColor.primaryColorConfig,
            container.info().colorInfo(),
            frameIndex,
            factory.outputPixelFormat()
        )
        if (container.info().alphaPresent()) {
            val alphaPlanes = decodeSampleTransform(sampleTransform, true).planes
            if (alphaPlanes.codedWidth() != rawFrame.width() || alphaPlanes.codedHeight() != rawFrame.height()) {
                throw AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sample Transform alpha dimensions differ from color dimensions",
                    null
                )
            }
            validateAlphaLumaPlane(
                alphaPlanes.lumaPlane(),
                rawFrame.width(),
                rawFrame.height(),
                "Sample Transform alpha"
            )
            rawFrame = combineFrameWithAlphaPlane(
                rawFrame,
                alphaPlanes,
                alphaPlanes.bitDepth(),
                frameIndex,
                container.info().alphaPremultiplied()
            )
        }
        return applyTransforms(rawFrame)
    }

    /** Decodes one image-sequence frame without mutating the persistent sequential AV1 decoder.
     * 
     * @param frameIndex the zero-based frame index
     * @return the decoded frame
     * @throws AvifIoException if decoding fails
     */
    private fun readSequenceFrameRandomAccess(frameIndex: Int): AvifFrame {
        val payloads = sequenceSamplePayloads
        if (payloads == null || frameIndex >= payloads.size) {
            throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
        }
        try {
            val requiredOutput = indexedSequenceCursor(payloads, "Sequence").read(frameIndex)
            val rawFrame: AvifFrame = adaptRawPlanes(
                requiredOutput.planes(),
                requiredOutput.colorConfig(),
                container.info().colorInfo(),
                frameIndex,
                factory.outputPixelFormat()
            )
            return combineFrameWithSequenceAlphaRandomAccess(rawFrame, frameIndex)
        } catch (exception: AvifDecodeException) {
            throw exception
        } catch (exception: AvifIoException) {
            throw wrapAv1DecodeFailure(exception)
        }
    }

    /** Reads all remaining decoded frames from the current sequential position.
     * 
     * @return all remaining decoded frames
     * @throws AvifIoException if a frame cannot be decoded
     */
    fun readAllFrames(): MutableList<AvifFrame> {
        ensureOpen()
        val frames = ArrayList<AvifFrame>()
        while (true) {
            val frame = readFrame()
            if (frame == null) {
                return ArrayList(frames)
            }
            frames.add(frame)
        }
    }

    /** Closes this reader and its owned container resources.
     * 
     * Repeated calls have no effect. A borrowed input stream or readable channel remains open.
     * The reader is closed even if releasing an AV1 decoder or owned file handle fails. When
     * multiple releases fail, later failures are suppressed on the first.
     * 
     * @throws AvifIoException if an owned decoder or source cannot be released
     */
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        var failure: AvifIoException? = null
        if (sequenceAv1Decoder != null) {
            try {
                sequenceAv1Decoder!!.close()
            } catch (exception: AvifIoException) {
                failure = exception
            }
            sequenceAv1Decoder = null
        }
        if (sequenceAlphaAv1Decoder != null) {
            try {
                sequenceAlphaAv1Decoder!!.close()
            } catch (exception: AvifIoException) {
                if (failure == null) {
                    failure = exception
                } else {
                    failure.addSuppressed(exception)
                }
            }
            sequenceAlphaAv1Decoder = null
        }
        failure = closeSequenceCursor(indexedColorSequenceCursor, failure)
        indexedColorSequenceCursor = null
        failure = closeSequenceCursor(indexedAlphaSequenceCursor, failure)
        indexedAlphaSequenceCursor = null
        failure = closeSequenceCursor(indexedDepthSequenceCursor, failure)
        indexedDepthSequenceCursor = null
        try {
            source.close()
        } catch (exception: AvifIoException) {
            if (failure == null) {
                failure = exception
            } else {
                failure.addSuppressed(exception)
            }
        }
        if (failure != null) {
            throw failure
        }
    }

    /** Ensures that this reader is open.
     * 
     * @throws AvifDecodeException if this reader is closed
     */
    private fun ensureOpen() {
        if (closed) {
            throw AvifDecodeException(AvifErrorCode.CLOSED, "AvifImageReader is closed", null)
        }
    }

    /** Ensures that this reader is open and backed by a seekable source.
     * 
     * @param operation the public operation requiring arbitrary source access
     * @throws AvifDecodeException if this reader is closed or its source is forward-only
     */
    private fun ensureRandomAccess(operation: String) {
        ensureOpen()
        if (!source.isSeekable()) {
            throw AvifDecodeException(
                AvifErrorCode.SEEKABLE_SOURCE_REQUIRED,
                operation + " requires Path, byte[], or ByteBuf input",
                null
            )
        }
    }

    /** Applies container-level transforms (clap, irot, imir) to a decoded frame.
     * 
     * @param frame the raw decoded frame
     * @return the transformed frame, or the same frame when no transforms are present
     */
    private fun applyTransforms(frame: AvifFrame): AvifFrame {
        val info = container.info()
        val transformInfo = info.transformInfo()
        if (transformInfo == null) {
            return frame
        }
        if (frame.pixelFormat() == AvifPixelFormat.ARGB_8888) {
            var pixels: IntArray = frame.intPixels()
            var width = frame.width()
            var height = frame.height()

            if (transformInfo.hasCleanApertureCrop()) {
                val cropped: IntArray = applyClapCropInt(
                    pixels, width,
                    transformInfo.cleanApertureCropX(), transformInfo.cleanApertureCropY(),
                    transformInfo.cleanApertureCropWidth(), transformInfo.cleanApertureCropHeight()
                )
                pixels = cropped
                width = transformInfo.cleanApertureCropWidth()
                height = transformInfo.cleanApertureCropHeight()
            }

            val rotation = transformInfo.rotationCode()
            if (rotation > 0) {
                val rotated: IntArray = applyRotationInt(pixels, width, height, rotation)
                pixels = rotated
                if (rotation == 1 || rotation == 3) {
                    val tmp = width
                    width = height
                    height = tmp
                }
            }

            val mirror = transformInfo.mirrorAxis()
            if (mirror >= 0) {
                pixels = applyMirrorInt(pixels, width, height, mirror)
            }

            return AvifFrame.fromOwnedPixels(
                width, height, frame.bitDepth(),
                frame.chromaFormat(), frame.frameIndex(), pixels
            )
        }
        if (frame.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
            var pixels: LongArray = frame.longPixels()
            var width = frame.width()
            var height = frame.height()

            if (transformInfo.hasCleanApertureCrop()) {
                val cropped: LongArray = applyClapCropLong(
                    pixels, width,
                    transformInfo.cleanApertureCropX(), transformInfo.cleanApertureCropY(),
                    transformInfo.cleanApertureCropWidth(), transformInfo.cleanApertureCropHeight()
                )
                pixels = cropped
                width = transformInfo.cleanApertureCropWidth()
                height = transformInfo.cleanApertureCropHeight()
            }

            val rotation = transformInfo.rotationCode()
            if (rotation > 0) {
                val rotated: LongArray = applyRotationLong(pixels, width, height, rotation)
                pixels = rotated
                if (rotation == 1 || rotation == 3) {
                    val tmp = width
                    width = height
                    height = tmp
                }
            }

            val mirror = transformInfo.mirrorAxis()
            if (mirror >= 0) {
                pixels = applyMirrorLong(pixels, width, height, mirror)
            }

            return AvifFrame.fromOwnedPixels(
                width, height, frame.bitDepth(),
                frame.chromaFormat(), frame.frameIndex(), pixels
            )
        }
        return frame
    }

    /** Combines a sequentially read sequence frame with its matching alpha sample when present.
     * 
     * @param colorFrame the decoded color frame
     * @param frameIndex the zero-based AVIF frame index
     * @return the color frame with sequence alpha applied, or the original frame
     * @throws AvifIoException if the alpha sample cannot be decoded
     */
    private fun combineFrameWithSequenceAlphaSequential(
        colorFrame: AvifFrame,
        frameIndex: Int
    ): AvifFrame {
        val alphaPayloads = sequenceAlphaSamplePayloads
        if (alphaPayloads == null) {
            return colorFrame
        }
        if (frameIndex != sequenceAlphaAv1FrameIndex && sequenceAlphaAv1Decoder != null) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sequential AVIF alpha reader is out of sync at frame $frameIndex",
                null
            )
        }
        if (sequenceAlphaAv1Decoder == null) {
            sequenceAlphaAv1Decoder = Av1Decoder.open(
                AvifPayload.openInput(alphaPayloads),
                factory.av1DecoderConfig()
            )
            sequenceAlphaAv1FrameIndex = 0
        }
        while (sequenceAlphaAv1FrameIndex < frameIndex) {
            val skipped = sequenceAlphaAv1Decoder!!.readOutput()
            if (skipped == null) {
                throw AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequence alpha ended before frame $frameIndex",
                    null
                )
            }
            sequenceAlphaAv1FrameIndex++
        }
        val alphaOutput = sequenceAlphaAv1Decoder!!.readOutput()
        if (alphaOutput == null) {
            throw AvifDecodeException(
                AvifErrorCode.AV1_DECODE_FAILED,
                "Sequence alpha produced no frame: $frameIndex",
                null
            )
        }
        sequenceAlphaAv1FrameIndex++
        val alphaPlanes: Av1DecodedPlanes = normalizeAlphaPlanes(
            alphaOutput.planes(),
            alphaOutput.colorConfig(),
            container.info().bitDepth(),
            "Sequence alpha frame $frameIndex"
        )
        return combineFrameWithDecodedAlpha(
            colorFrame,
            alphaPlanes,
            frameIndex,
            container.info().alphaPremultiplied()
        )
    }

    /** Combines a randomly accessed sequence frame with its matching alpha sample when present.
     * 
     * @param colorFrame the decoded color frame
     * @param frameIndex the zero-based AVIF frame index
     * @return the color frame with sequence alpha applied, or the original frame
     * @throws AvifIoException if the alpha sample cannot be decoded
     */
    private fun combineFrameWithSequenceAlphaRandomAccess(
        colorFrame: AvifFrame,
        frameIndex: Int
    ): AvifFrame {
        val alphaPayloads = sequenceAlphaSamplePayloads
        if (alphaPayloads == null) {
            return colorFrame
        }
        try {
            val requiredOutput =
                indexedSequenceCursor(alphaPayloads, "Sequence alpha").read(frameIndex)
            val alphaPlanes: Av1DecodedPlanes = normalizeAlphaPlanes(
                requiredOutput.planes(),
                requiredOutput.colorConfig(),
                container.info().bitDepth(),
                "Sequence alpha frame $frameIndex"
            )
            return combineFrameWithDecodedAlpha(
                colorFrame,
                alphaPlanes,
                frameIndex,
                container.info().alphaPremultiplied()
            )
        } catch (exception: AvifDecodeException) {
            throw exception
        } catch (exception: AvifIoException) {
            throw wrapAv1DecodeFailure(exception)
        }
    }

    /** Maintains reusable AV1 decoder state for indexed reads of one image sequence.
     * 
     * Forward reads continue from the current decoder position. Reading an earlier frame closes
     * and reopens the decoder, then advances from the beginning. The most recently returned frame
     * remains cached so repeated reads of the same index do not decode it again. */
    inner class SequenceDecoderCursor(
        payloads: Array<AvifPayload>,
        label: String
    ) : AutoCloseable {
        /** The immutable sequence payload descriptors. */
        val payloads: Array<AvifPayload>

        /** The diagnostic sequence label. */
        val label: String

        /** The active AV1 decoder, or `null` before the first read or after closure. */
        var decoder: Av1Decoder? = null

        /** The index to be read next from the active decoder. */
        var nextFrameIndex = 0

        /** The most recently decoded frame index, or `-1` before any successful read. */
        var lastFrameIndex = -1

        /** The most recently decoded output, or `null` before any successful read. */
        var lastOutput: Av1DecodedOutput? = null

        init {
            this.payloads =
                requireNotNullCompat(payloads, "payloads")
            this.label = requireNotNullCompat(label, "label")
        }

        /** Reads one indexed output, reusing forward decoder state when possible.
         * 
         * @param frameIndex the zero-based frame index
         * @return the decoded output
         * @throws AvifIoException if the decoder cannot be opened, advanced, or reset
         */
        fun read(frameIndex: Int): Av1DecodedOutput {
            if (frameIndex < 0 || frameIndex >= payloads.size) {
                throw IndexOutOfBoundsException("frameIndex out of range: $frameIndex")
            }
            if (frameIndex == lastFrameIndex) {
                return requireNotNullCompat(lastOutput, "lastOutput")
            }
            if (frameIndex < lastFrameIndex) {
                reset()
            }
            if (decoder == null) {
                decoder = Av1Decoder.open(
                    AvifPayload.openInput(payloads),
                    factory.av1DecoderConfig()
                )
            }
            try {
                while (nextFrameIndex <= frameIndex) {
                    val output = decoder!!.readOutput()
                    if (output == null) {
                        throw AvifDecodeException(
                            AvifErrorCode.AV1_DECODE_FAILED,
                            label + " ended before frame $frameIndex",
                            null
                        )
                    }
                    lastOutput = output
                    lastFrameIndex = nextFrameIndex
                    nextFrameIndex++
                }
                return requireNotNullCompat(lastOutput, "lastOutput")
            } catch (exception: AvifIoException) {
                try {
                    reset()
                } catch (closeException: AvifIoException) {
                    exception.addSuppressed(closeException)
                }
                throw exception
            } catch (exception: RuntimeException) {
                try {
                    reset()
                } catch (closeException: AvifIoException) {
                    exception.addSuppressed(closeException)
                }
                throw exception
            }
        }

        /** Resets this cursor to the beginning of its payload sequence.
         * 
         * @throws AvifIoException if the active decoder cannot be closed
         */
        fun reset() {
            val activeDecoder = decoder
            decoder = null
            nextFrameIndex = 0
            lastFrameIndex = -1
            lastOutput = null
            if (activeDecoder != null) {
                activeDecoder.close()
            }
        }

        /** Closes the active decoder and clears cached output.
         * 
         * Repeated calls have no effect.
         * 
         * @throws AvifIoException if the active decoder cannot be closed
         */
        override fun close() {
            reset()
        }
    }

    /** Raw decoded image planes and the active AV1 color configuration.
     * 
     * @param planes the decoded raw planes
     * @param colorConfig the active AV1 sequence-header color configuration
     */
    data class DecodedRawImage(
        val planes: Av1DecodedPlanes,
        val colorConfig: Av1ColorConfig
    )

    /** Reconstructed Sample Transform planes and the primary input's AV1 color configuration.
     * 
     * @param planes the reconstructed planes
     * @param primaryColorConfig the primary input's AV1 sequence-header color configuration
     */
    data class DecodedSampleTransform(
        val planes: Av1DecodedPlanes,
        val primaryColorConfig: Av1ColorConfig
    )

    companion object {
        /** Opens an AVIF image reader over a byte array.
         * 
         * This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
         * The reader borrows the array, which must not be modified until the reader is closed.
         * 
         * @param source the complete AVIF source bytes
         * @return a new AVIF image reader
         * @throws AvifDecodeException if the source is not a supported AVIF container
         */
        fun open(source: ByteArray): AvifImageReader {
            return AvifImageReaderFactory.DEFAULT.open(source)
        }

/** Validates a selected AV1 output frame against its associated `ispe` dimensions.
         * 
         * @param source the normalized image source
         * @param itemIndex the zero-based payload or grid-cell index
         * @param planes the selected final decoded planes
         * @param label the diagnostic image label
         * @throws AvifDecodeException if the decoded dimensions differ from `ispe`
         */
        fun validateDecodedItemDimensions(
            source: AvifImageSource,
            itemIndex: Int,
            planes: Av1DecodedPlanes,
            label: String
        ) {
            val expectedWidth = source.itemWidth(itemIndex)
            val expectedHeight = source.itemHeight(itemIndex)
            if (planes.codedWidth() != expectedWidth || planes.codedHeight() != expectedHeight) {
                throw AvifDecodeException(
                    AvifErrorCode.ISPE_SIZE_MISMATCH,
                    (label + " decoded dimensions " + planes.codedWidth() + "x" + planes.codedHeight()
                            + " do not match ispe ${expectedWidth}x$expectedHeight"),
                    null
                )
            }
        }

        /** Renders reconstructed color planes into the requested packed ARGB format.
         * 
         * Container `nclx` metadata takes precedence over the primary input's AV1 sequence-header
         * color configuration, matching the normal still-image path.
         * 
         * @param planes the reconstructed color planes
         * @param av1ColorConfig the primary input's AV1 color configuration
         * @param colorInfo the AVIF container color information, or `null`
         * @param frameIndex the zero-based frame index
         * @param outputPixelFormat the configured packed pixel format, or `null` to select by source bit depth
         * @return the rendered AVIF frame
         * @throws AvifDecodeException if the selected color conversion is unsupported
         */
        fun adaptRawPlanes(
            planes: Av1DecodedPlanes,
            av1ColorConfig: Av1ColorConfig,
            colorInfo: AvifColorInfo?,
            frameIndex: Int,
            outputPixelFormat: AvifPixelFormat?
        ): AvifFrame {
            val pixelFormat = if (outputPixelFormat != null)
                outputPixelFormat
            else
                planes.bitDepth().defaultPixelFormat()
            try {
                val transform: YuvToRgbTransform = if (colorInfo != null)
                    YuvToRgbTransform.Companion.fromColorInfo(
                        colorInfo,
                        planes.chromaFormat() == Av1ChromaFormat.MONOCHROME
                    )
                else
                    YuvToRgbTransform.Companion.fromColorConfig(av1ColorConfig)
                val decodedPlanes: DecodedSurface = toDecodedPlanes(planes)
                if (pixelFormat == AvifPixelFormat.ARGB_8888) {
                    return AvifFrame.fromOwnedPixels(
                        planes.codedWidth(),
                        planes.codedHeight(),
                        planes.bitDepth(),
                        planes.chromaFormat(),
                        frameIndex,
                        ArgbOutput.toOpaqueArgbPixels(decodedPlanes, transform)
                    )
                }
                if (pixelFormat == AvifPixelFormat.ARGB_16161616) {
                    return AvifFrame.fromOwnedPixels(
                        planes.codedWidth(),
                        planes.codedHeight(),
                        planes.bitDepth(),
                        planes.chromaFormat(),
                        frameIndex,
                        ArgbOutput.toOpaqueArgbLongPixels(decodedPlanes, transform)
                    )
                }
                throw IllegalArgumentException("Unsupported pixel format: $pixelFormat")
            } catch (exception: UnsupportedOperationException) {
                throw unsupportedColorConversion(exception)
            }
        }

        /** Converts public raw planes back to the internal output-conversion representation.
         * 
         * @param planes the public raw planes
         * @return equivalent internal decoded planes
         */
        fun toDecodedPlanes(planes: Av1DecodedPlanes): DecodedSurface {
            return DecodedSurface(
                planes.bitDepth().bits(),
                planes.chromaFormat(),
                planes.codedWidth(),
                planes.codedHeight(),
                planes.renderWidth(),
                planes.renderHeight(),
                toDecodedPlane(planes.lumaPlane()),
                toNullableDecodedPlane(planes.chromaUPlane()),
                toNullableDecodedPlane(planes.chromaVPlane())
            )
        }

        /** Converts one public plane to the internal output-conversion representation.
         * 
         * @param plane the public plane
         * @return the equivalent internal plane
         */
        fun toDecodedPlane(plane: Av1DecodedPlane): PaddedPlane {
            return PaddedPlane(plane.width(), plane.height(), plane.stride(), plane.sampleBuffer())
        }

        /** Converts one optional public plane to the internal output-conversion representation.
         * 
         * @param plane the public plane, or `null`
         * @return the equivalent internal plane, or `null`
         */
        fun toNullableDecodedPlane(plane: Av1DecodedPlane?): PaddedPlane? {
            return if (plane != null) toDecodedPlane(plane) else null
        }

        /** Normalizes decoded auxiliary-image planes for use as AVIF alpha.
         * 
         * Color-coded auxiliary images are accepted for compatibility and only their luma plane is
         * retained. Limited-range luma is converted to full range as permitted for legacy AVIF 1.0
         * alpha images.
         * 
         * @param planes the decoded auxiliary-image planes
         * @param colorConfig the auxiliary image's AV1 color configuration
         * @param expectedBitDepth the associated master-image bit depth
         * @param label the diagnostic alpha source label
         * @return monochrome, full-range alpha planes
         * @throws AvifDecodeException if the alpha bit depth differs from the master image
         */
        fun normalizeAlphaPlanes(
            planes: Av1DecodedPlanes,
            colorConfig: Av1ColorConfig,
            expectedBitDepth: AvifBitDepth,
            label: String
        ): Av1DecodedPlanes {
            if (colorConfig.bitDepth != expectedBitDepth) {
                throw AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    (label + " bit depth differs from its master image: "
                            + colorConfig.bitDepth.bits() + " != " + expectedBitDepth.bits()),
                    null
                )
            }
            val lumaPlane = if (colorConfig.colorRange)
                planes.lumaPlane()
            else
                limitedToFullAlphaPlane(planes.lumaPlane(), colorConfig.bitDepth)
            return Av1DecodedPlanes(
                planes.bitDepth(),
                Av1ChromaFormat.MONOCHROME,
                planes.codedWidth(),
                planes.codedHeight(),
                planes.renderWidth(),
                planes.renderHeight(),
                lumaPlane,
                null,
                null
            )
        }

        /** Converts one limited-range alpha luma plane to full range.
         * 
         * @param plane the limited-range luma plane
         * @param bitDepth the decoded AV1 bit depth
         * @return a compact full-range alpha plane
         */
        fun limitedToFullAlphaPlane(
            plane: Av1DecodedPlane,
            bitDepth: AvifBitDepth
        ): Av1DecodedPlane {
            val width = plane.width()
            val height = plane.height()
            val rangeShift = bitDepth.bits() - 8
            val limitedMinimum = 16 shl rangeShift
            val limitedMaximum = 235 shl rangeShift
            val limitedRange = limitedMaximum - limitedMinimum
            val fullMaximum = bitDepth.maxSampleValue()
            val source = plane.sampleBuffer()
            val samples = ShortArray(width * height)
            for (y in 0..<height) {
                val sourceOffset = y * plane.stride()
                val destinationOffset = y * width
                for (x in 0..<width) {
                    val sample = source.get(sourceOffset + x).toInt() and 0xFFFF
                    val fullRangeSample =
                        (((sample - limitedMinimum) * fullMaximum + limitedRange / 2)
                                / limitedRange)
                    samples[destinationOffset + x] = max(
                        0,
                        min(fullMaximum, fullRangeSample)
                    ).toShort()
                }
            }
            return Av1DecodedPlane(
                width,
                height,
                width,
                samples
            )
        }

        /** Returns whether image metadata contains one auxiliary image type.
         * 
         * @param info the image metadata
         * @param auxiliaryType the auxiliary image type
         * @return whether the auxiliary type is present
         */
        fun hasAuxiliaryType(info: AvifImageInfo, auxiliaryType: String): Boolean {
            for (type in info.auxiliaryImageTypes()) {
                if (auxiliaryType == type) {
                    return true
                }
            }
            return false
        }

        /** Composes decoded grid cell raw planes into one canvas.
         * 
         * @param cellPlanes the decoded cell planes in row-major order
         * @param rows the grid row count
         * @param columns the grid column count
         * @param outputWidth the output luma width
         * @param outputHeight the output luma height
         * @return composed raw color planes
         */
        fun composeGridRawColorPlanes(
            cellPlanes: Array<Av1DecodedPlanes>,
            rows: Int,
            columns: Int,
            outputWidth: Int,
            outputHeight: Int
        ): Av1DecodedPlanes {
            require(cellPlanes.size == rows * columns) { "grid cell count does not match rows * columns" }
            val firstCell = cellPlanes[0]
            val bitDepth = firstCell.bitDepth()
            val chromaFormat: Av1ChromaFormat = gridRawPlaneChromaFormat(cellPlanes)
            validateGridRawPlaneCells(cellPlanes, bitDepth, chromaFormat)

            val lumaPlane: Av1DecodedPlane =
                composeGridPlane(lumaPlanes(cellPlanes), rows, columns, outputWidth, outputHeight)
            if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
                return Av1DecodedPlanes(
                    bitDepth, chromaFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                    lumaPlane, null, null
                )
            }

            val chromaWidth: Int = expectedChromaWidth(chromaFormat, outputWidth)
            val chromaHeight: Int = expectedChromaHeight(chromaFormat, outputHeight)
            val chromaUPlane: Av1DecodedPlane = composeGridPlane(
                chromaPlanes(cellPlanes, chromaFormat, bitDepth, true),
                rows,
                columns,
                chromaWidth,
                chromaHeight
            )
            val chromaVPlane: Av1DecodedPlane = composeGridPlane(
                chromaPlanes(cellPlanes, chromaFormat, bitDepth, false),
                rows,
                columns,
                chromaWidth,
                chromaHeight
            )
            return Av1DecodedPlanes(
                bitDepth, chromaFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                lumaPlane, chromaUPlane, chromaVPlane
            )
        }

        /** Validates decoded grid-cell consistency and canvas coverage.
         * 
         * @param cellPlanes the decoded cells in row-major order
         * @param rows the grid row count
         * @param columns the grid column count
         * @param outputWidth the output luma width
         * @param outputHeight the output luma height
         * @param label the diagnostic image label
         * @throws AvifDecodeException if the cells or canvas violate grid requirements
         */
        fun validateGridGeometry(
            cellPlanes: Array<Av1DecodedPlanes>,
            rows: Int,
            columns: Int,
            outputWidth: Int,
            outputHeight: Int,
            label: String
        ) {
            if (cellPlanes.size != rows * columns || cellPlanes.size == 0) {
                throw invalidImageGrid(label + " grid cell count does not match its row and column counts")
            }
            val firstCell = cellPlanes[0]
            val tileWidth = firstCell.codedWidth()
            val tileHeight = firstCell.codedHeight()
            for (cellIndex in 1..<cellPlanes.size) {
                val cell = cellPlanes[cellIndex]
                if (cell.codedWidth() != tileWidth || cell.codedHeight() != tileHeight) {
                    throw invalidImageGrid(
                        (label + " grid cell $cellIndex dimensions "
                                + cell.codedWidth() + "x" + cell.codedHeight()
                                + " differ from the first cell ${tileWidth}x$tileHeight")
                    )
                }
            }
            if (tileWidth < 64 || tileHeight < 64) {
                throw invalidImageGrid(label + " grid cells must be at least 64x64 samples")
            }
            if (tileWidth.toLong() * columns < outputWidth || tileHeight.toLong() * rows < outputHeight) {
                throw invalidImageGrid(label + " grid cells do not cover the output canvas")
            }
            if (tileWidth.toLong() * (columns - 1) >= outputWidth
                || tileHeight.toLong() * (rows - 1) >= outputHeight
            ) {
                throw invalidImageGrid(label + " rightmost or bottommost grid cells do not overlap the output canvas")
            }

            val chromaFormat: Av1ChromaFormat = gridRawPlaneChromaFormat(cellPlanes)
            if ((chromaFormat == Av1ChromaFormat.YUV420 || chromaFormat == Av1ChromaFormat.YUV422)
                && ((tileWidth and 1) != 0 || (outputWidth and 1) != 0)
            ) {
                throw invalidImageGrid(label + " horizontally subsampled grid widths must be even")
            }
            if (chromaFormat == Av1ChromaFormat.YUV420
                && ((tileHeight and 1) != 0 || (outputHeight and 1) != 0)
            ) {
                throw invalidImageGrid(label + " vertically subsampled grid heights must be even")
            }
            if (outputWidth.toLong() * outputHeight > Int.MAX_VALUE) {
                throw unsupported(label + " grid output contains too many samples for a Java array")
            }
        }

        /** Returns the common raw grid chroma format, allowing monochrome cells in a chroma grid.
         * 
         * @param cellPlanes the decoded cell planes
         * @return the grid chroma format
         */
        fun gridRawPlaneChromaFormat(cellPlanes: Array<Av1DecodedPlanes>): Av1ChromaFormat {
            var chromaFormat = Av1ChromaFormat.MONOCHROME
            for (cellPlane in cellPlanes) {
                val cellChromaFormat = cellPlane.chromaFormat()
                if (cellChromaFormat == Av1ChromaFormat.MONOCHROME) {
                    continue
                }
                if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
                    chromaFormat = cellChromaFormat
                } else require(cellChromaFormat == chromaFormat) { "grid cell chroma format mismatch" }
            }
            return chromaFormat
        }

        /** Validates that all grid cells share the same raw-plane format.
         * 
         * @param cellPlanes the decoded cell planes
         * @param bitDepth the expected bit depth
         * @param chromaFormat the expected output chroma format
         */
        fun validateGridRawPlaneCells(
            cellPlanes: Array<Av1DecodedPlanes>,
            bitDepth: AvifBitDepth,
            chromaFormat: Av1ChromaFormat
        ) {
            for (cellPlane in cellPlanes) {
                require(cellPlane.bitDepth() == bitDepth) { "grid cell bit depth mismatch" }
                val cellChromaFormat = cellPlane.chromaFormat()
                require(!(cellChromaFormat != chromaFormat && cellChromaFormat != Av1ChromaFormat.MONOCHROME)) { "grid cell chroma format mismatch" }
            }
        }

        /** Returns luma planes for all grid cells.
         * 
         * @param cellPlanes the decoded cell planes
         * @return luma planes in row-major order
         */
        fun lumaPlanes(cellPlanes: Array<Av1DecodedPlanes>): Array<Av1DecodedPlane> {
            val result: Array<Av1DecodedPlane> = arrayOfNullsCompat<Av1DecodedPlane>(cellPlanes.size)
            for (i in cellPlanes.indices) {
                result[i] = cellPlanes[i].lumaPlane()
            }
            return result
        }

        /** Returns one chroma plane for all grid cells.
         * 
         * @param cellPlanes the decoded cell planes
         * @param chromaFormat the output chroma format
         * @param bitDepth the output bit depth
         * @param chromaU whether to return U instead of V
         * @return chroma planes in row-major order
         */
        fun chromaPlanes(
            cellPlanes: Array<Av1DecodedPlanes>,
            chromaFormat: Av1ChromaFormat,
            bitDepth: AvifBitDepth,
            chromaU: Boolean
        ): Array<Av1DecodedPlane> {
            val result: Array<Av1DecodedPlane> = arrayOfNullsCompat<Av1DecodedPlane>(cellPlanes.size)
            for (i in cellPlanes.indices) {
                val cellPlane = cellPlanes[i]
                val plane = if (chromaU) cellPlane.chromaUPlane() else cellPlane.chromaVPlane()
                result[i] = if (plane != null) plane else neutralChromaPlane(
                    cellPlane,
                    chromaFormat,
                    bitDepth
                )
            }
            return result
        }

        /** Creates a neutral chroma plane for a monochrome grid cell.
         * 
         * @param cellPlane the monochrome cell planes
         * @param chromaFormat the output chroma format
         * @param bitDepth the output bit depth
         * @return a neutral chroma plane matching the cell's target chroma dimensions
         */
        fun neutralChromaPlane(
            cellPlane: Av1DecodedPlanes,
            chromaFormat: Av1ChromaFormat,
            bitDepth: AvifBitDepth
        ): Av1DecodedPlane {
            val width: Int = expectedChromaWidth(chromaFormat, cellPlane.codedWidth())
            val height: Int = expectedChromaHeight(chromaFormat, cellPlane.codedHeight())
            val samples = ShortArray(width * height)
            fillCompat(samples, (1 shl (bitDepth.bits() - 1)).toShort())
            return Av1DecodedPlane(width, height, width, samples)
        }

        /** Composes one plane from row-major grid cell planes.
         * 
         * @param cellPlanes the cell planes
         * @param rows the grid row count
         * @param columns the grid column count
         * @param outputWidth the output plane width
         * @param outputHeight the output plane height
         * @return composed plane
         */
        fun composeGridPlane(
            cellPlanes: Array<Av1DecodedPlane>,
            rows: Int,
            columns: Int,
            outputWidth: Int,
            outputHeight: Int
        ): Av1DecodedPlane {
            val samples = ShortArray(multiplyExact(outputWidth, outputHeight))
            val cellWidth = cellPlanes[0].width()
            val cellHeight = cellPlanes[0].height()
            for (row in 0..<rows) {
                for (col in 0..<columns) {
                    val cellIndex = row * columns + col
                    val cellPlane = cellPlanes[cellIndex]
                    require(!(cellPlane.width() != cellWidth || cellPlane.height() != cellHeight)) { "grid cell plane dimensions mismatch" }
                    copyGridPlaneCell(
                        samples,
                        outputWidth,
                        outputHeight,
                        cellPlane,
                        col * cellWidth,
                        row * cellHeight
                    )
                }
            }
            return Av1DecodedPlane(outputWidth, outputHeight, outputWidth, samples)
        }

        /** Copies one grid cell plane into the destination plane canvas.
         * 
         * @param destination the destination samples
         * @param outputWidth the output plane width
         * @param outputHeight the output plane height
         * @param cellPlane the source cell plane
         * @param xOffset the destination x offset
         * @param yOffset the destination y offset
         */
        fun copyGridPlaneCell(
            destination: ShortArray,
            outputWidth: Int,
            outputHeight: Int,
            cellPlane: Av1DecodedPlane,
            xOffset: Int,
            yOffset: Int
        ) {
            if (xOffset >= outputWidth || yOffset >= outputHeight) {
                return
            }
            val copyWidth = min(cellPlane.width(), outputWidth - xOffset)
            val copyHeight = min(cellPlane.height(), outputHeight - yOffset)
            for (y in 0..<copyHeight) {
                val destinationBase = (yOffset + y) * outputWidth + xOffset
                for (x in 0..<copyWidth) {
                    destination[destinationBase + x] = cellPlane.sample(x, y).toShort()
                }
            }
        }

        /** Returns the expected chroma width for one chroma format.
         * 
         * @param chromaFormat the decoded AV1 chroma sampling layout
         * @param codedWidth the coded luma width in samples
         * @return the expected chroma width
         */
        fun expectedChromaWidth(chromaFormat: Av1ChromaFormat, codedWidth: Int): Int {
            return when (chromaFormat) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> (codedWidth + 1) / 2
                Av1ChromaFormat.YUV444 -> codedWidth
            }
        }

        /** Returns the expected chroma height for one chroma format.
         * 
         * @param chromaFormat the decoded AV1 chroma sampling layout
         * @param codedHeight the coded luma height in samples
         * @return the expected chroma height
         */
        fun expectedChromaHeight(chromaFormat: Av1ChromaFormat, codedHeight: Int): Int {
            return when (chromaFormat) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420 -> (codedHeight + 1) / 2
                Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> codedHeight
            }
        }

        /** Closes one indexed sequence cursor and combines any failure with an earlier failure.
         * 
         * @param cursor the cursor to close, or `null`
         * @param failure the earlier close failure, or `null`
         * @return the first close failure with later failures suppressed, or `null`
         */
        fun closeSequenceCursor(
            cursor: SequenceDecoderCursor?,
            failure: AvifIoException?
        ): AvifIoException? {
            if (cursor == null) {
                return failure
            }
            try {
                cursor.close()
            } catch (exception: AvifIoException) {
                if (failure == null) {
                    return exception
                }
                failure.addSuppressed(exception)
            }
            return failure
        }

        /** Applies a clean-aperture crop to 8-bit pixels.
         * 
         * @param pixels the source pixel array
         * @param srcWidth the source width
         * @param cropX the crop x offset
         * @param cropY the crop y offset
         * @param cropWidth the crop width
         * @param cropHeight the crop height
         * @return the cropped pixel array
         */
        fun applyClapCropInt(
            pixels: IntArray, srcWidth: Int,
            cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int
        ): IntArray {
            val result = IntArray(cropWidth * cropHeight)
            for (y in 0..<cropHeight) {
                val srcRow = (cropY + y) * srcWidth + cropX
                val destRow = y * cropWidth
                arraycopy(pixels, srcRow, result, destRow, cropWidth)
            }
            return result
        }

        /** Applies a clean-aperture crop to 16-bit-per-channel pixels.
         * 
         * @param pixels the source pixel array
         * @param srcWidth the source width
         * @param cropX the crop x offset
         * @param cropY the crop y offset
         * @param cropWidth the crop width
         * @param cropHeight the crop height
         * @return the cropped pixel array
         */
        fun applyClapCropLong(
            pixels: LongArray, srcWidth: Int,
            cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int
        ): LongArray {
            val result = LongArray(cropWidth * cropHeight)
            for (y in 0..<cropHeight) {
                val srcRow = (cropY + y) * srcWidth + cropX
                val destRow = y * cropWidth
                arraycopy(pixels, srcRow, result, destRow, cropWidth)
            }
            return result
        }

        /** Applies rotation to 8-bit pixels.
         * 
         * @param pixels the source pixel array
         * @param width the source width
         * @param height the source height
         * @param rotation the AVIF `irot` rotation code (1=90° CCW, 2=180°, 3=90° CW)
         * @return the rotated pixel array
         */
        fun applyRotationInt(
            pixels: IntArray,
            width: Int,
            height: Int,
            rotation: Int
        ): IntArray {
            return when (rotation) {
                1 -> {
                    val newWidth = height
                    val newHeight = width
                    val result = IntArray(newWidth * newHeight)
                    for (y in 0..<newHeight) {
                        for (x in 0..<newWidth) {
                            result[y * newWidth + x] = pixels[x * width + (width - 1 - y)]
                        }
                    }
                    result
                }

                2 -> {
                    val result = IntArray(width * height)
                    for (y in 0..<height) {
                        for (x in 0..<width) {
                            result[y * width + x] =
                                pixels[(height - 1 - y) * width + (width - 1 - x)]
                        }
                    }
                    result
                }

                3 -> {
                    val newWidth = height
                    val newHeight = width
                    val result = IntArray(newWidth * newHeight)
                    for (y in 0..<newHeight) {
                        for (x in 0..<newWidth) {
                            result[y * newWidth + x] = pixels[(height - 1 - x) * width + y]
                        }
                    }
                    result
                }

                else -> pixels
            }
        }

        /** Applies rotation to 16-bit-per-channel pixels.
         * 
         * @param pixels the source pixel array
         * @param width the source width
         * @param height the source height
         * @param rotation the AVIF `irot` rotation code (1=90° CCW, 2=180°, 3=90° CW)
         * @return the rotated pixel array
         */
        fun applyRotationLong(
            pixels: LongArray,
            width: Int,
            height: Int,
            rotation: Int
        ): LongArray {
            return when (rotation) {
                1 -> {
                    val newWidth = height
                    val newHeight = width
                    val result = LongArray(newWidth * newHeight)
                    for (y in 0..<newHeight) {
                        for (x in 0..<newWidth) {
                            result[y * newWidth + x] = pixels[x * width + (width - 1 - y)]
                        }
                    }
                    result
                }

                2 -> {
                    val result = LongArray(width * height)
                    for (y in 0..<height) {
                        for (x in 0..<width) {
                            result[y * width + x] =
                                pixels[(height - 1 - y) * width + (width - 1 - x)]
                        }
                    }
                    result
                }

                3 -> {
                    val newWidth = height
                    val newHeight = width
                    val result = LongArray(newWidth * newHeight)
                    for (y in 0..<newHeight) {
                        for (x in 0..<newWidth) {
                            result[y * newWidth + x] = pixels[(height - 1 - x) * width + y]
                        }
                    }
                    result
                }

                else -> pixels
            }
        }

        /** Applies mirroring to 8-bit pixels.
         * 
         * @param pixels the source pixel array
         * @param width the source width
         * @param height the source height
         * @param axis the AVIF `imir` mirror axis (0=horizontal axis, 1=vertical axis)
         * @return the mirrored pixel array
         */
        fun applyMirrorInt(pixels: IntArray, width: Int, height: Int, axis: Int): IntArray {
            val result = IntArray(width * height)
            if (axis == 0) {
                for (y in 0..<height) {
                    val srcRow = (height - 1 - y) * width
                    val destRow = y * width
                    arraycopy(pixels, srcRow, result, destRow, width)
                }
            } else {
                for (y in 0..<height) {
                    val rowBase = y * width
                    for (x in 0..<width) {
                        result[rowBase + x] = pixels[rowBase + (width - 1 - x)]
                    }
                }
            }
            return result
        }

        /** Applies mirroring to 16-bit-per-channel pixels.
         * 
         * @param pixels the source pixel array
         * @param width the source width
         * @param height the source height
         * @param axis the AVIF `imir` mirror axis (0=horizontal axis, 1=vertical axis)
         * @return the mirrored pixel array
         */
        fun applyMirrorLong(
            pixels: LongArray,
            width: Int,
            height: Int,
            axis: Int
        ): LongArray {
            val result = LongArray(width * height)
            if (axis == 0) {
                for (y in 0..<height) {
                    val srcRow = (height - 1 - y) * width
                    val destRow = y * width
                    arraycopy(pixels, srcRow, result, destRow, width)
                }
            } else {
                for (y in 0..<height) {
                    val rowBase = y * width
                    for (x in 0..<width) {
                        result[rowBase + x] = pixels[rowBase + (width - 1 - x)]
                    }
                }
            }
            return result
        }

        /** Combines decoded alpha planes with a color frame.
         * 
         * @param colorFrame the decoded color frame
         * @param alphaPlanes the decoded alpha planes
         * @param frameIndex the zero-based AVIF frame index
         * @param alphaPremultiplied whether color samples are premultiplied by alpha
         * @return the combined AVIF frame
         * @throws AvifDecodeException if the alpha dimensions differ
         */
        fun combineFrameWithDecodedAlpha(
            colorFrame: AvifFrame,
            alphaPlanes: Av1DecodedPlanes,
            frameIndex: Int,
            alphaPremultiplied: Boolean
        ): AvifFrame {
            val checkedAlphaPlanes: Av1DecodedPlanes = validateDecodedAlphaPlanes(
                colorFrame.width(),
                colorFrame.height(),
                alphaPlanes
            )
            return combineFrameWithAlphaPlane(
                colorFrame,
                checkedAlphaPlanes,
                checkedAlphaPlanes.bitDepth(),
                frameIndex,
                alphaPremultiplied
            )
        }

        /** Validates decoded alpha planes before composition.
         * 
         * @param expectedWidth the expected alpha width
         * @param expectedHeight the expected alpha height
         * @param alphaPlanes the decoded alpha planes
         * @return the validated alpha planes
         * @throws AvifDecodeException if the alpha planes are incompatible with the color frame
         */
        fun validateDecodedAlphaPlanes(
            expectedWidth: Int,
            expectedHeight: Int,
            alphaPlanes: Av1DecodedPlanes
        ): Av1DecodedPlanes {
            if (alphaPlanes.codedWidth() != expectedWidth || alphaPlanes.codedHeight() != expectedHeight) {
                throw AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Alpha with different decoded dimensions than master image",
                    null
                )
            }
            if (alphaPlanes.chromaFormat() != Av1ChromaFormat.MONOCHROME) {
                throw AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Decoded alpha planes are not monochrome",
                    null
                )
            }
            validateAlphaLumaPlane(alphaPlanes.lumaPlane(), expectedWidth, expectedHeight, "Alpha")
            return alphaPlanes
        }

        /** Validates one alpha luma plane against the expected decoded dimensions.
         * 
         * @param lumaPlane the decoded luma plane used as alpha
         * @param expectedWidth the expected luma width
         * @param expectedHeight the expected luma height
         * @param label the diagnostic alpha source label
         * @throws AvifDecodeException if the luma plane cannot cover the expected alpha image
         */
        fun validateAlphaLumaPlane(
            lumaPlane: Av1DecodedPlane,
            expectedWidth: Int,
            expectedHeight: Int,
            label: String
        ) {
            if (lumaPlane.width() < expectedWidth || lumaPlane.height() < expectedHeight) {
                throw AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    label + " luma plane is smaller than the decoded alpha frame",
                    null
                )
            }
        }

        /** Combines alpha from one raw luma plane into a color frame.
         * 
         * @param color the decoded color frame
         * @param alphaPlanes the decoded alpha planes
         * @param alphaBitDepth the alpha plane bit depth
         * @param frameIndex the zero-based AVIF frame index
         * @return the combined frame
         */
        fun combineFrameWithAlphaPlane(
            color: AvifFrame,
            alphaPlanes: Av1DecodedPlanes,
            alphaBitDepth: AvifBitDepth,
            frameIndex: Int,
            alphaPremultiplied: Boolean
        ): AvifFrame {
            if (color.pixelFormat() == AvifPixelFormat.ARGB_8888) {
                return combineIntPlaneAlpha(
                    color,
                    alphaPlanes,
                    alphaBitDepth,
                    frameIndex,
                    alphaPremultiplied
                )
            }
            if (color.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
                return combineLongPlaneAlpha(
                    color,
                    alphaPlanes,
                    alphaBitDepth,
                    frameIndex,
                    alphaPremultiplied
                )
            }
            throw IllegalArgumentException("Unsupported alpha color frame pixel format: " + color.pixelFormat())
        }

        /** Combines alpha from raw luma plane into an 8-bit color frame. */
        fun combineIntPlaneAlpha(
            color: AvifFrame,
            alphaPlanes: Av1DecodedPlanes,
            alphaBitDepth: AvifBitDepth,
            frameIndex: Int,
            alphaPremultiplied: Boolean
        ): AvifFrame {
            val colorPixels = IntBuf.wrap(color.intPixels())
            val width = color.width()
            val height = color.height()
            val lumaPlane = alphaPlanes.lumaPlane()
            val maxSample = alphaBitDepth.maxSampleValue()
            val combined = IntArray(width * height)
            for (y in 0..<height) {
                for (x in 0..<width) {
                    val alphaSample = lumaPlane.sample(x, y)
                    val alpha8: Int = scaleSampleToByte(alphaSample, maxSample)
                    val i = y * width + x
                    combined[i] = (colorPixels.get(i) and 0x00FFFFFF) or (alpha8 shl 24)
                }
            }
            if (alphaPremultiplied) {
                unpremultiplyIntPixels(combined)
            }
            return AvifFrame.fromOwnedPixels(
                width, height, color.bitDepth(), color.chromaFormat(), frameIndex, combined
            )
        }

        /** Combines alpha from raw luma plane into a 10/12-bit color frame. */
        fun combineLongPlaneAlpha(
            color: AvifFrame,
            alphaPlanes: Av1DecodedPlanes,
            alphaBitDepth: AvifBitDepth,
            frameIndex: Int,
            alphaPremultiplied: Boolean
        ): AvifFrame {
            val colorPixels = LongBuf.wrap(color.longPixels())
            val width = color.width()
            val height = color.height()
            val lumaPlane = alphaPlanes.lumaPlane()
            val maxSample = alphaBitDepth.maxSampleValue()
            val combined = LongArray(width * height)
            for (y in 0..<height) {
                for (x in 0..<width) {
                    val alphaSample = lumaPlane.sample(x, y)
                    val alpha16: Long = scaleSampleToWord(alphaSample, maxSample)
                    val i = y * width + x
                    combined[i] =
                        (colorPixels.get(i) and 0x0000FFFFFFFFFFFFL) or ((alpha16 and 0xFFFFL) shl 48)
                }
            }
            if (alphaPremultiplied) {
                unpremultiplyLongPixels(combined)
            }
            return AvifFrame.fromOwnedPixels(
                width, height, color.bitDepth(), color.chromaFormat(), frameIndex, combined
            )
        }

        /** Converts packed 8-bit ARGB pixels from premultiplied to straight alpha in place.
         * 
         * @param pixels the pixels to convert
         */
        fun unpremultiplyIntPixels(pixels: IntArray) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val alpha = pixel ushr 24
                if (alpha == 0) {
                    pixels[i] = 0
                    continue
                }
                if (alpha == 255) {
                    continue
                }
                val red: Int = unpremultiplyChannel((pixel ushr 16) and 0xFF, alpha, 255)
                val green: Int = unpremultiplyChannel((pixel ushr 8) and 0xFF, alpha, 255)
                val blue: Int = unpremultiplyChannel(pixel and 0xFF, alpha, 255)
                pixels[i] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        /** Converts packed 16-bit-per-channel ARGB pixels from premultiplied to straight alpha in place.
         * 
         * @param pixels the pixels to convert
         */
        fun unpremultiplyLongPixels(pixels: LongArray) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val alpha = ((pixel ushr 48) and 0xFFFFL).toInt()
                if (alpha == 0) {
                    pixels[i] = 0L
                    continue
                }
                if (alpha == 65535) {
                    continue
                }
                val red = unpremultiplyChannel(
                    ((pixel ushr 32) and 0xFFFFL).toInt(),
                    alpha,
                    65535
                ).toLong()
                val green = unpremultiplyChannel(
                    ((pixel ushr 16) and 0xFFFFL).toInt(),
                    alpha,
                    65535
                ).toLong()
                val blue = unpremultiplyChannel((pixel and 0xFFFFL).toInt(), alpha, 65535).toLong()
                pixels[i] = (alpha.toLong() shl 48) or (red shl 32) or (green shl 16) or blue
            }
        }

        /** Converts one premultiplied channel to straight alpha.
         * 
         * @param sample the premultiplied color sample
         * @param alpha the alpha sample
         * @param maxSample the maximum channel sample value
         * @return the straight-alpha channel sample
         */
        fun unpremultiplyChannel(sample: Int, alpha: Int, maxSample: Int): Int {
            val value = (sample.toLong() * maxSample + alpha / 2L) / alpha
            return if (value > maxSample) maxSample else value.toInt()
        }

        /** Scales a decoded alpha sample to an unsigned 8-bit channel.
         * 
         * @param sample the decoded alpha sample
         * @param maxSample the maximum alpha sample value
         * @return the scaled 8-bit alpha channel
         */
        fun scaleSampleToByte(sample: Int, maxSample: Int): Int {
            if (maxSample == 255) {
                return sample
            }
            return (sample * 255 + maxSample / 2) / maxSample
        }

        /** Scales a decoded alpha sample to an unsigned 16-bit channel.
         * 
         * @param sample the decoded alpha sample
         * @param maxSample the maximum alpha sample value
         * @return the scaled 16-bit alpha channel
         */
        fun scaleSampleToWord(sample: Int, maxSample: Int): Long {
            if (maxSample == 65535) {
                return sample.toLong()
            }
            return (sample.toLong() * 65535 + maxSample / 2) / maxSample
        }

        /** Creates an unsupported-feature exception.
         * 
         * @param message the failure message
         * @return an unsupported-feature exception
         */
        fun unsupported(message: String): AvifDecodeException {
            return AvifDecodeException(AvifErrorCode.UNSUPPORTED_FEATURE, message, null)
        }

        /** Creates an invalid-grid exception.
         * 
         * @param message the failure message
         * @return an invalid-grid exception
         */
        fun invalidImageGrid(message: String): AvifDecodeException {
            return AvifDecodeException(AvifErrorCode.INVALID_IMAGE_GRID, message, null)
        }

        /** Creates an unsupported-feature exception for an unavailable CICP color conversion.
         * 
         * @param exception the unsupported color conversion failure
         * @return an unsupported-feature exception retaining the underlying cause
         */
        fun unsupportedColorConversion(exception: UnsupportedOperationException): AvifDecodeException {
            return AvifDecodeException(
                AvifErrorCode.UNSUPPORTED_FEATURE,
                if (exception.message != null)
                    exception.message
                else
                    "AVIF output uses an unsupported color conversion",
                null,
                exception
            )
        }

        /** Wraps one low-level AV1 decoding failure while preserving caller-relevant classification.
         * 
         * @param exception the low-level decoding failure
         * @return the corresponding AVIF decoding failure
         */
        fun wrapAv1DecodeFailure(exception: AvifIoException): AvifDecodeException {
            var cause: Throwable? = exception
            while (cause != null) {
                if (cause is AvifDecodeException) {
                    return cause
                }
                cause = cause.cause
            }
            var code = AvifErrorCode.AV1_DECODE_FAILED
            if (exception is Av1DecodeException) {
                code = when (exception.code()) {
                    Av1DecodeErrorCode.UNSUPPORTED_FEATURE -> AvifErrorCode.UNSUPPORTED_FEATURE
                    Av1DecodeErrorCode.FRAME_SIZE_LIMIT_EXCEEDED -> AvifErrorCode.FRAME_SIZE_LIMIT_EXCEEDED
                    else -> AvifErrorCode.AV1_DECODE_FAILED
                }
            }
            return AvifDecodeException(
                code,
                if (exception.message != null) exception.message else "AV1 decoding failed",
                null,
                exception
            )
        }
}
}
