// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.parse

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.av1.Av1ColorConfig
import org.glavo.avif.av1.Av1DecodeErrorCode
import org.glavo.avif.av1.Av1DecodeException
import org.glavo.avif.av1.Av1DecodeStage
import org.glavo.avif.internal.av1.bitstream.BitReader
import org.glavo.avif.internal.av1.bitstream.ObuPacket
import org.glavo.avif.internal.av1.bitstream.ObuType
import org.glavo.avif.internal.av1.model.SequenceHeader
import org.glavo.avif.internal.av1.model.SequenceHeader.AdaptiveBoolean
import org.glavo.avif.internal.av1.model.SequenceHeader.FeatureConfig
import org.glavo.avif.internal.av1.model.SequenceHeader.OperatingParameterInfo


/** Parser for AV1 sequence header OBUs. */
object SequenceHeaderParser {
    /** The AV1 `BT.709` color primaries code. */
    private const val COLOR_PRI_BT709 = 1

    /** The AV1 `unknown` color primaries code. */
    private const val COLOR_PRI_UNKNOWN = 2

    /** The AV1 `unknown` transfer characteristics code. */
    private const val TRANSFER_UNKNOWN = 2

    /** The AV1 `sRGB` transfer characteristics code. */
    private const val TRANSFER_SRGB = 13

    /** The AV1 `identity` matrix coefficients code. */
    private const val MATRIX_IDENTITY = 0

    /** The AV1 `unknown` matrix coefficients code. */
    private const val MATRIX_UNKNOWN = 2

    /** The AV1 `unknown` chroma sample position code. */
    private const val CHROMA_UNKNOWN = 0

    /** Parses an AV1 sequence header OBU payload.
     * 
     * @param obu the sequence header OBU packet
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @return the parsed sequence header
     * @throws AvifIoException if the OBU is truncated, unreadable, or invalid
     */
    fun parse(obu: ObuPacket, strictStdCompliance: Boolean): SequenceHeader {
        requireNotNullCompat(obu, "obu")
        require(obu.header.type == ObuType.SEQUENCE_HEADER) { "OBU type is not a sequence header: " + obu.header.type }

        val reader = BitReader(obu.payload)

        try {
            val header = parse(reader, strictStdCompliance)
            checkTrailingBits(reader, strictStdCompliance)
            return header
        } catch (ex: EOFException) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                Av1DecodeStage.SEQUENCE_HEADER_PARSE,
                "Unexpected end of sequence header OBU",
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
                Av1DecodeStage.SEQUENCE_HEADER_PARSE,
                ex.message,
                obu.streamOffset,
                obu.obuIndex,
                null,
                ex
            )
        }
    }

    /** Parses the sequence header payload.
     * 
     * @param reader the payload bit reader
     * @param strictStdCompliance whether strict standards compliance should be enforced
     * @return the parsed sequence header
     * @throws AvifIoException if the payload is truncated or invalid
     */
    private fun parse(reader: BitReader, strictStdCompliance: Boolean): SequenceHeader {
        val profile = readInt(reader, 3)
        if (profile > 2) {
            fail("Sequence header profile out of range: $profile")
        }

        val stillPicture = reader.readFlag()
        val reducedStillPictureHeader = reader.readFlag()
        if (reducedStillPictureHeader && !stillPicture) {
            fail("Reduced still-picture header requires still_picture to be set")
        }

        val timingInfo: SequenceHeader.TimingInfo?
        val operatingPoints: Array<SequenceHeader.OperatingPoint>?

        if (reducedStillPictureHeader) {
            operatingPoints = arrayOf<SequenceHeader.OperatingPoint>(
                SequenceHeader.OperatingPoint(
                    readInt(reader, 3),
                    readInt(reader, 2),
                    10,
                    0,
                    false,
                    false,
                    false,
                    null
                )
            )
            timingInfo = SequenceHeader.TimingInfo(false, 0, 0, false, 0, false, 0, 0, 0, 0, false)
        } else {
            val timingInfoPresent = reader.readFlag()
            var numUnitsInTick: Long = 0
            var timeScale: Long = 0
            var equalPictureInterval = false
            var numTicksPerPicture: Long = 0
            var decoderModelInfoPresent = false
            var encoderDecoderBufferDelayLength = 0
            var numUnitsInDecodingTick: Long = 0
            var bufferRemovalDelayLength = 0
            var framePresentationDelayLength = 0

            if (timingInfoPresent) {
                numUnitsInTick = readLong(reader, 32)
                timeScale = readLong(reader, 32)
                if (strictStdCompliance && (numUnitsInTick == 0L || timeScale == 0L)) {
                    fail("Timing info contains zero-valued required fields")
                }

                equalPictureInterval = reader.readFlag()
                if (equalPictureInterval) {
                    numTicksPerPicture = reader.readUvlc() + 1
                }

                decoderModelInfoPresent = reader.readFlag()
                if (decoderModelInfoPresent) {
                    encoderDecoderBufferDelayLength = readInt(reader, 5) + 1
                    numUnitsInDecodingTick = readLong(reader, 32)
                    if (strictStdCompliance && numUnitsInDecodingTick == 0L) {
                        fail("Decoder model info contains a zero num_units_in_decoding_tick")
                    }
                    bufferRemovalDelayLength = readInt(reader, 5) + 1
                    framePresentationDelayLength = readInt(reader, 5) + 1
                }
            }

            val displayModelInfoPresent = reader.readFlag()
            timingInfo = SequenceHeader.TimingInfo(
                timingInfoPresent,
                numUnitsInTick,
                timeScale,
                equalPictureInterval,
                numTicksPerPicture,
                decoderModelInfoPresent,
                encoderDecoderBufferDelayLength,
                numUnitsInDecodingTick,
                bufferRemovalDelayLength,
                framePresentationDelayLength,
                displayModelInfoPresent
            )

            val operatingPointCount = readInt(reader, 5) + 1
            operatingPoints = arrayOfNullsCompat<SequenceHeader.OperatingPoint>(operatingPointCount)
            for (i in 0..<operatingPointCount) {
                val idc = readInt(reader, 12)
                if (idc != 0 && (((idc and 0xFF) == 0) || ((idc and 0xF00) == 0))) {
                    fail("Operating point IDC must reference both temporal and spatial layers when non-zero")
                }

                val majorLevel = 2 + readInt(reader, 3)
                val minorLevel = readInt(reader, 2)
                var tier = false
                if (majorLevel > 3) {
                    tier = reader.readFlag()
                }

                var decoderModelParamPresent = false
                var parameterInfo: OperatingParameterInfo? = null
                if (timingInfo.decoderModelInfoPresent()) {
                    decoderModelParamPresent = reader.readFlag()
                    if (decoderModelParamPresent) {
                        parameterInfo = OperatingParameterInfo(
                            readLong(reader, timingInfo.encoderDecoderBufferDelayLength()),
                            readLong(reader, timingInfo.encoderDecoderBufferDelayLength()),
                            reader.readFlag()
                        )
                    }
                }

                var displayModelParamPresent = false
                if (timingInfo.displayModelInfoPresent()) {
                    displayModelParamPresent = reader.readFlag()
                }
                val initialDisplayDelay =
                    if (displayModelParamPresent) readInt(reader, 4) + 1 else 10

                operatingPoints[i] = SequenceHeader.OperatingPoint(
                    majorLevel,
                    minorLevel,
                    initialDisplayDelay,
                    idc,
                    tier,
                    decoderModelParamPresent,
                    displayModelParamPresent,
                    parameterInfo
                )
            }
        }

        val widthBits = readInt(reader, 4) + 1
        val heightBits = readInt(reader, 4) + 1
        val maxWidth = readInt(reader, widthBits) + 1
        val maxHeight = readInt(reader, heightBits) + 1

        var frameIdNumbersPresent = false
        var deltaFrameIdBits = 0
        var frameIdBits = 0
        if (!reducedStillPictureHeader) {
            frameIdNumbersPresent = reader.readFlag()
            if (frameIdNumbersPresent) {
                deltaFrameIdBits = readInt(reader, 4) + 2
                frameIdBits = readInt(reader, 3) + deltaFrameIdBits + 1
            }
        }

        val use128x128Superblocks = reader.readFlag()
        val filterIntra = reader.readFlag()
        val intraEdgeFilter = reader.readFlag()
        var interIntra = false
        var maskedCompound = false
        var warpedMotion = false
        var dualFilter = false
        var orderHint = false
        var jointCompound = false
        var refFrameMotionVectors = false
        val screenContentTools: AdaptiveBoolean?
        val forceIntegerMotionVectors: AdaptiveBoolean?
        var orderHintBits = 0

        if (reducedStillPictureHeader) {
            screenContentTools = AdaptiveBoolean.ADAPTIVE
            forceIntegerMotionVectors = AdaptiveBoolean.ADAPTIVE
        } else {
            interIntra = reader.readFlag()
            maskedCompound = reader.readFlag()
            warpedMotion = reader.readFlag()
            dualFilter = reader.readFlag()
            orderHint = reader.readFlag()
            if (orderHint) {
                jointCompound = reader.readFlag()
                refFrameMotionVectors = reader.readFlag()
            }
            screenContentTools = readAdaptiveBoolean(reader)
            if (screenContentTools != AdaptiveBoolean.OFF) {
                forceIntegerMotionVectors = readAdaptiveBoolean(reader)
            } else {
                forceIntegerMotionVectors = AdaptiveBoolean.ADAPTIVE
            }
            if (orderHint) {
                orderHintBits = readInt(reader, 3) + 1
            }
        }

        val superResolution = reader.readFlag()
        val cdef = reader.readFlag()
        val restoration = reader.readFlag()

        var bitDepth = if (reader.readFlag()) 10 else 8
        if (profile == 2 && bitDepth == 10 && reader.readFlag()) {
            bitDepth = 12
        }

        var monochrome = false
        if (profile != 1) {
            monochrome = reader.readFlag()
        }

        val colorDescriptionPresent = reader.readFlag()
        var colorPrimaries = COLOR_PRI_UNKNOWN
        var transferCharacteristics = TRANSFER_UNKNOWN
        var matrixCoefficients = MATRIX_UNKNOWN
        if (colorDescriptionPresent) {
            colorPrimaries = readInt(reader, 8)
            transferCharacteristics = readInt(reader, 8)
            matrixCoefficients = readInt(reader, 8)
        }

        val colorRange: Boolean
        val chromaFormat: Av1ChromaFormat?
        val chromaSamplePosition: Int
        var chromaSubsamplingX = false
        var chromaSubsamplingY = false
        var separateUvDeltaQ = false

        if (monochrome) {
            colorRange = reader.readFlag()
            chromaFormat = Av1ChromaFormat.MONOCHROME
            chromaSubsamplingX = true
            chromaSubsamplingY = true
            chromaSamplePosition = CHROMA_UNKNOWN
        } else if (colorPrimaries == COLOR_PRI_BT709 && transferCharacteristics == TRANSFER_SRGB && matrixCoefficients == MATRIX_IDENTITY) {
            chromaFormat = Av1ChromaFormat.YUV444
            colorRange = true
            if (profile != 1 && !(profile == 2 && bitDepth == 12)) {
                fail("Identity matrix RGB signaling is only valid for profile 1 or profile 2 12-bit streams")
            }
            chromaSamplePosition = CHROMA_UNKNOWN
        } else {
            colorRange = reader.readFlag()
            when (profile) {
                0 -> {
                    chromaFormat = Av1ChromaFormat.YUV420
                    chromaSubsamplingX = true
                    chromaSubsamplingY = true
                }

                1 -> chromaFormat = Av1ChromaFormat.YUV444
                2 -> {
                    if (bitDepth == 12) {
                        chromaSubsamplingX = reader.readFlag()
                        if (chromaSubsamplingX) {
                            chromaSubsamplingY = reader.readFlag()
                        }
                    } else {
                        chromaSubsamplingX = true
                    }
                    chromaFormat = if (chromaSubsamplingX)
                        (if (chromaSubsamplingY) Av1ChromaFormat.YUV420 else Av1ChromaFormat.YUV422)
                    else
                        Av1ChromaFormat.YUV444
                }

                else -> throw IllegalStateException("Unexpected profile: $profile")
            }
            chromaSamplePosition =
                if (chromaSubsamplingX && chromaSubsamplingY) readInt(reader, 2) else CHROMA_UNKNOWN
        }

        if (strictStdCompliance && matrixCoefficients == MATRIX_IDENTITY && chromaFormat != Av1ChromaFormat.YUV444) {
            fail("Identity matrix coefficients require YUV444 chroma in strict mode")
        }

        if (!monochrome) {
            separateUvDeltaQ = reader.readFlag()
        }

        val filmGrainPresent = reader.readFlag()

        return SequenceHeader(
            profile,
            maxWidth,
            maxHeight,
            timingInfo,
            operatingPoints,
            stillPicture,
            reducedStillPictureHeader,
            widthBits,
            heightBits,
            frameIdNumbersPresent,
            deltaFrameIdBits,
            frameIdBits,
            FeatureConfig(
                use128x128Superblocks,
                filterIntra,
                intraEdgeFilter,
                interIntra,
                maskedCompound,
                warpedMotion,
                dualFilter,
                orderHint,
                jointCompound,
                refFrameMotionVectors,
                screenContentTools,
                forceIntegerMotionVectors,
                orderHintBits,
                superResolution,
                cdef,
                restoration,
                filmGrainPresent
            ),
            Av1ColorConfig(
                bitDepth,
                monochrome,
                colorDescriptionPresent,
                colorPrimaries,
                transferCharacteristics,
                matrixCoefficients,
                colorRange,
                chromaFormat,
                chromaSamplePosition,
                chromaSubsamplingX,
                chromaSubsamplingY,
                separateUvDeltaQ
            )
        )
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

    /** Reads an AV1 adaptive boolean syntax element.
     * 
     * @param reader the payload bit reader
     * @return the decoded adaptive boolean
     * @throws AvifIoException if the payload is truncated
     */
    private fun readAdaptiveBoolean(reader: BitReader): AdaptiveBoolean {
        if (reader.readFlag()) {
            return AdaptiveBoolean.ADAPTIVE
        }
        return if (reader.readFlag()) AdaptiveBoolean.ON else AdaptiveBoolean.OFF
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
}
