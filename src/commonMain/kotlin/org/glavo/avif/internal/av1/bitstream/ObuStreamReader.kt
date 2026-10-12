// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.av1.Av1DecodeErrorCode
import org.glavo.avif.av1.Av1DecodeException
import org.glavo.avif.av1.Av1DecodeStage
import org.glavo.avif.internal.io.BufferedInput


/** Sequential OBU reader for raw AV1 low-overhead or Annex B bitstreams. */
class ObuStreamReader private constructor(
    input: BufferedInput,
    annexB: Boolean,
    payloadSizeLimit: Long
) {
    /** The forward-only buffered byte source. */
    private val input: BufferedInput

    /** Whether the source uses Annex B temporal-unit and frame-unit framing. */
    private val annexB: Boolean

    /** The effective maximum encoded payload retained for one OBU. */
    private val maximumPayloadSize: Int

    /** The next unread byte offset in the source. */
    private var streamOffset: Long = 0

    /** The next unread OBU index in the source. */
    private var obuIndex = 0

    /** Whether end-of-stream has already been observed. */
    private var endOfStream = false

    /** The bytes following the current Annex B frame unit within its temporal unit. */
    private var temporalUnitRemaining: Long = 0

    /** The unread bytes within the current Annex B frame unit. */
    private var frameUnitRemaining: Long = 0

    /** Whether at least one Annex B temporal unit has been opened. */
    private var annexBStarted = false

    /** Creates a sequential OBU reader.
     * 
     * @param input the forward-only buffered byte source
     */
    constructor(input: BufferedInput) : this(input, false, DEFAULT_PAYLOAD_SIZE_LIMIT)

    /** Creates a sequential OBU reader with an encoded-payload resource limit.
     * 
     * @param input the forward-only buffered byte source
     * @param payloadSizeLimit the maximum encoded payload retained for one OBU, or `0` to apply
     * only the implementation limit
     */
    constructor(input: BufferedInput, payloadSizeLimit: Long) : this(input, false, payloadSizeLimit)

    init {
        require(payloadSizeLimit >= 0) { "payloadSizeLimit < 0: $payloadSizeLimit" }
        this.input = requireNotNullCompat(input, "input")
        this.annexB = annexB
        this.maximumPayloadSize = min(
            if (payloadSizeLimit == 0L) MAX_PAYLOAD_SIZE.toLong() else payloadSizeLimit,
            MAX_PAYLOAD_SIZE.toLong()
        ).toInt()
    }

    /** Reads the next OBU packet from the source.
     * 
     * @return the next OBU packet, or `null` at end-of-stream
     * @throws AvifIoException if the source is truncated or the OBU is malformed
     */
    fun readObu(): ObuPacket? {
        return if (annexB) readAnnexBObu() else readLowOverheadObu()
    }

    /** Returns whether the reader is positioned between two Annex B temporal units.
     * 
     * The result becomes `true` after the final OBU of a temporal unit is returned and remains
     * true until the next call to [#readObu()] starts another temporal unit. Low-overhead readers
     * always return `false` because that framing has no external temporal-unit boundary.
     * 
     * @return whether an Annex B temporal unit has just been completed
     */
    fun atTemporalUnitBoundary(): Boolean {
        return annexB && annexBStarted && temporalUnitRemaining == 0L && frameUnitRemaining == 0L
    }

    /** Reads the next OBU from a low-overhead source.
     * 
     * @return the next OBU packet, or `null` at end-of-stream
     * @throws AvifIoException if the source is truncated or malformed
     */
    private fun readLowOverheadObu(): ObuPacket? {
        if (endOfStream) {
            return null
        }

        val obuOffset = streamOffset
        val currentObuIndex = obuIndex
        val unitRemainingAtObuStart = input.currentUnitRemaining()

        val firstByte: Int
        try {
            firstByte = input.readUnsignedByte()
        } catch (ignored: EOFException) {
            endOfStream = true
            return null
        }
        streamOffset++

        return readObuPacket(firstByte, obuOffset, currentObuIndex, unitRemainingAtObuStart, false)
    }

    /** Reads the next OBU from an Annex B source.
     * 
     * @return the next OBU packet, or `null` at end-of-stream
     * @throws AvifIoException if an external length field or enclosed OBU is malformed
     */
    private fun readAnnexBObu(): ObuPacket? {
        if (endOfStream) {
            return null
        }

        while (frameUnitRemaining == 0L) {
            while (temporalUnitRemaining == 0L) {
                val temporalUnitSize = readAnnexBLength(
                    true,
                    -1L,
                    "temporal unit"
                )
                if (temporalUnitSize == null) {
                    endOfStream = true
                    return null
                }
                annexBStarted = true
                temporalUnitRemaining = temporalUnitSize.value
            }

            val frameUnitSize = requireNotNullCompat(
                readAnnexBLength(
                    false,
                    temporalUnitRemaining,
                    "frame unit"
                )
            )
            temporalUnitRemaining -= frameUnitSize.byteCount.toLong()
            if (frameUnitSize.value > temporalUnitRemaining) {
                throw invalidAnnexBFraming(
                    ("Annex B frame unit exceeds its temporal unit: " + frameUnitSize.value
                            + " > $temporalUnitRemaining"),
                    streamOffset - frameUnitSize.byteCount,
                    null
                )
            }
            temporalUnitRemaining -= frameUnitSize.value
            frameUnitRemaining = frameUnitSize.value
        }

        val obuSize = requireNotNullCompat(
            readAnnexBLength(
                false,
                frameUnitRemaining,
                "OBU"
            )
        )
        frameUnitRemaining -= obuSize.byteCount.toLong()
        if (obuSize.value == 0L) {
            throw invalidAnnexBFraming(
                "Annex B OBU length must be positive",
                streamOffset - obuSize.byteCount,
                null
            )
        }
        if (obuSize.value > frameUnitRemaining) {
            throw invalidAnnexBFraming(
                "Annex B OBU exceeds its frame unit: " + obuSize.value + " > $frameUnitRemaining",
                streamOffset - obuSize.byteCount,
                null
            )
        }
        frameUnitRemaining -= obuSize.value

        val obuOffset = streamOffset
        val currentObuIndex = obuIndex
        val firstByte: Int
        try {
            firstByte = input.readUnsignedByte()
        } catch (exception: EOFException) {
            throw unexpectedAnnexBEof("Unexpected end of Annex B OBU header", obuOffset, exception)
        }
        streamOffset++
        return readObuPacket(firstByte, obuOffset, currentObuIndex, obuSize.value, true)
    }

    /** Parses one OBU after its first header byte has been consumed.
     * 
     * @param firstByte the first OBU header byte
     * @param obuOffset the byte offset of that header byte
     * @param currentObuIndex the zero-based OBU index
     * @param unitRemainingAtObuStart the enclosing unit length starting at the header, or `-1`
     * @param requireExactUnitSize whether the OBU must consume the complete enclosing unit
     * @return the parsed OBU packet
     * @throws AvifIoException if the header, size, or payload is malformed
     */
    private fun readObuPacket(
        firstByte: Int,
        obuOffset: Long,
        currentObuIndex: Int,
        unitRemainingAtObuStart: Long,
        requireExactUnitSize: Boolean
    ): ObuPacket {
        if ((firstByte and 0x80) != 0) {
            throw invalidHeader("OBU forbidden bit must be zero", obuOffset, currentObuIndex)
        }

        val typeId = (firstByte ushr 3) and 0x0F
        val extensionFlag = ((firstByte ushr 2) and 1) != 0
        val hasSizeField = ((firstByte ushr 1) and 1) != 0
        val reservedBit = (firstByte and 1) != 0

        if (reservedBit) {
            throw invalidHeader("OBU reserved bit must be zero", obuOffset, currentObuIndex)
        }

        val type: ObuType? = ObuType.Companion.fromId(typeId)
        if (type == null) {
            throw invalidHeader("Unsupported OBU type: $typeId", obuOffset, currentObuIndex)
        }

        var temporalId = 0
        var spatialId = 0
        if (extensionFlag) {
            val extensionByte = readUnsignedByte(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                "Unexpected end of OBU extension",
                obuOffset,
                currentObuIndex
            )
            streamOffset++
            temporalId = (extensionByte ushr 5) and 0x07
            spatialId = (extensionByte ushr 3) and 0x03
            if ((extensionByte and 0x07) != 0) {
                throw invalidHeader(
                    "OBU extension reserved bits must be zero",
                    obuOffset,
                    currentObuIndex
                )
            }
        }

        var payloadSize = -1L
        if (hasSizeField) {
            val sizeResult: Leb128.ReadResult?
            try {
                sizeResult = Leb128.readUnsigned(input, 8)
            } catch (ex: EOFException) {
                throw Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.OBU_READ,
                    "Unexpected end of OBU size field",
                    obuOffset,
                    currentObuIndex,
                    null,
                    ex
                )
            } catch (ex: AvifIoException) {
                throw Av1DecodeException(
                    Av1DecodeErrorCode.INVALID_LEB128,
                    Av1DecodeStage.OBU_READ,
                    ex.message,
                    obuOffset,
                    currentObuIndex,
                    null,
                    ex
                )
            }
            streamOffset += sizeResult.byteCount.toLong()
            payloadSize = sizeResult.value
        }

        val headerSize = streamOffset - obuOffset
        if (unitRemainingAtObuStart >= 0L && headerSize > unitRemainingAtObuStart) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                Av1DecodeStage.OBU_READ,
                "OBU header exceeds its externally bounded input unit",
                obuOffset,
                currentObuIndex,
                null
            )
        }
        var unboundedPayload: ByteArray? = null
        if (!hasSizeField) {
            if (unitRemainingAtObuStart < 0L) {
                unboundedPayload = readPayloadToEnd(obuOffset, currentObuIndex)
                payloadSize = unboundedPayload.size.toLong()
                endOfStream = true
            } else {
                payloadSize = unitRemainingAtObuStart - headerSize
            }
        } else if (unitRemainingAtObuStart >= 0L
            && payloadSize > unitRemainingAtObuStart - headerSize
        ) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.UNEXPECTED_EOF,
                Av1DecodeStage.OBU_READ,
                "OBU payload exceeds its externally bounded input unit",
                obuOffset,
                currentObuIndex,
                null
            )
        }
        if (requireExactUnitSize && payloadSize != unitRemainingAtObuStart - headerSize) {
            throw invalidAnnexBFraming(
                ("Annex B OBU length does not match its header and payload: expected "
                        + unitRemainingAtObuStart + " bytes but parsed " + (headerSize + payloadSize)),
                obuOffset,
                null
            )
        }
        if (payloadSize > maximumPayloadSize) {
            throw Av1DecodeException(
                Av1DecodeErrorCode.OBU_PAYLOAD_SIZE_LIMIT_EXCEEDED,
                Av1DecodeStage.OBU_READ,
                ("OBU payload exceeds the configured or implementation size limit: "
                        + payloadSize + " > $maximumPayloadSize"),
                obuOffset,
                currentObuIndex,
                null
            )
        }

        val payload: ByteArray?
        if (unboundedPayload != null) {
            payload = unboundedPayload
        } else {
            try {
                payload = input.readByteArray(payloadSize.toInt())
            } catch (ex: EOFException) {
                throw Av1DecodeException(
                    Av1DecodeErrorCode.UNEXPECTED_EOF,
                    Av1DecodeStage.OBU_READ,
                    "Unexpected end of OBU payload",
                    obuOffset,
                    currentObuIndex,
                    null,
                    ex
                )
            }
        }
        streamOffset += payloadSize
        obuIndex++

        return ObuPacket(
            ObuHeader(type, extensionFlag, hasSizeField, temporalId, spatialId),
            payload,
            obuOffset,
            currentObuIndex
        )
    }

    /** Reads one Annex B unsigned length field and accounts for its bytes in the stream offset.
     * 
     * Padded encodings are accepted because the AV1 syntax permits any encoding of up to eight
     * bytes whose decoded value fits the unsigned 32-bit range.
     * 
     * @param allowEndOfStream whether EOF before the first byte denotes normal stream completion
     * @param enclosingRemaining the unread enclosing-unit size, or `-1` when unbounded
     * @param description the length-field subject used in diagnostics
     * @return the decoded length, or `null` for permitted clean EOF
     * @throws AvifIoException if the length is truncated, too long, out of range, or crosses its unit
     */
    private fun readAnnexBLength(
        allowEndOfStream: Boolean,
        enclosingRemaining: Long,
        description: String
    ): Leb128.ReadResult? {
        val lengthOffset = streamOffset
        var value = 0L
        var shift = 0
        for (byteCount in 1..8) {
            if (enclosingRemaining >= 0L && byteCount > enclosingRemaining) {
                throw invalidAnnexBFraming(
                    "Annex B $description length field exceeds its enclosing unit",
                    lengthOffset,
                    null
                )
            }

            val current: Int
            try {
                current = input.readUnsignedByte()
            } catch (exception: EOFException) {
                if (allowEndOfStream && byteCount == 1) {
                    return null
                }
                throw unexpectedAnnexBEof(
                    "Unexpected end of Annex B $description length field",
                    lengthOffset,
                    exception
                )
            }
            streamOffset++
            value = value or ((current and 0x7F).toLong() shl shift)
            if ((current and 0x80) == 0) {
                if (value > 0xFFFFFFFFL) {
                    throw invalidAnnexBLeb128(
                        "Annex B $description length exceeds the unsigned 32-bit range: $value",
                        lengthOffset,
                        null
                    )
                }
                return Leb128.ReadResult(value, byteCount)
            }
            shift += 7
        }
        throw invalidAnnexBLeb128(
            "Annex B $description length exceeds eight bytes",
            lengthOffset,
            null
        )
    }

    /** Reads a size-less final OBU payload until the backing input reaches EOF.
     * 
     * @param obuOffset the OBU header offset
     * @param currentObuIndex the OBU index
     * @return the payload bytes consumed before EOF
     * @throws AvifIoException if the input fails before reaching EOF or the payload is too large
     */
    private fun readPayloadToEnd(obuOffset: Long, currentObuIndex: Int): ByteArray {
        val payload = ByteArrayOutput()
        while (true) {
            val value: Int
            try {
                value = input.readUnsignedByte()
            } catch (ignored: EOFException) {
                return payload.toByteArray()
            }
            if (payload.size() == maximumPayloadSize) {
                throw Av1DecodeException(
                    Av1DecodeErrorCode.OBU_PAYLOAD_SIZE_LIMIT_EXCEEDED,
                    Av1DecodeStage.OBU_READ,
                    ("OBU payload exceeds the configured or implementation size limit: "
                            + (maximumPayloadSize + 1L) + " > $maximumPayloadSize"),
                    obuOffset,
                    currentObuIndex,
                    null
                )
            }
            payload.write(value)
        }
    }

    /** Reads the next unsigned byte or throws a contextual decode exception.
     * 
     * @param errorCode the error code to use if EOF is encountered
     * @param message the contextual error message
     * @param obuOffset the OBU header offset
     * @param currentObuIndex the OBU index
     * @return the next unsigned byte
     * @throws AvifIoException if the source is truncated or unreadable
     */
    private fun readUnsignedByte(
        errorCode: Av1DecodeErrorCode,
        message: String,
        obuOffset: Long,
        currentObuIndex: Int
    ): Int {
        try {
            return input.readUnsignedByte()
        } catch (ex: EOFException) {
            throw Av1DecodeException(
                errorCode,
                Av1DecodeStage.OBU_READ,
                message,
                obuOffset,
                currentObuIndex,
                null,
                ex
            )
        }
    }

    /** Creates a contextual exception for malformed Annex B unit nesting or lengths.
     * 
     * @param message the detailed error message
     * @param offset the byte offset of the failing external length or enclosed OBU
     * @param cause the underlying I/O failure, or `null`
     * @return the contextual invalid-bitstream exception
     */
    private fun invalidAnnexBFraming(
        message: String,
        offset: Long,
        cause: Throwable?
    ): Av1DecodeException {
        return Av1DecodeException(
            Av1DecodeErrorCode.INVALID_BITSTREAM,
            Av1DecodeStage.OBU_READ,
            message,
            offset,
            obuIndex,
            null,
            cause
        )
    }

    /** Creates a contextual exception for an invalid Annex B LEB128 length field.
     * 
     * @param message the detailed error message
     * @param offset the byte offset of the failing length field
     * @param cause the underlying I/O failure, or `null`
     * @return the contextual invalid-LEB128 exception
     */
    private fun invalidAnnexBLeb128(
        message: String,
        offset: Long,
        cause: Throwable?
    ): Av1DecodeException {
        return Av1DecodeException(
            Av1DecodeErrorCode.INVALID_LEB128,
            Av1DecodeStage.OBU_READ,
            message,
            offset,
            obuIndex,
            null,
            cause
        )
    }

    /** Creates a contextual exception for truncated Annex B framing.
     * 
     * @param message the detailed error message
     * @param offset the byte offset where the truncated structure started
     * @param cause the underlying end-of-input exception
     * @return the contextual unexpected-EOF exception
     */
    private fun unexpectedAnnexBEof(
        message: String,
        offset: Long,
        cause: EOFException
    ): Av1DecodeException {
        return Av1DecodeException(
            Av1DecodeErrorCode.UNEXPECTED_EOF,
            Av1DecodeStage.OBU_READ,
            message,
            offset,
            obuIndex,
            null,
            cause
        )
    }

    companion object {
        /** The default maximum encoded payload retained for one OBU. */
        const val DEFAULT_PAYLOAD_SIZE_LIMIT = 256L * 1024L * 1024L

        /** The largest OBU payload representable by the decoder's byte-array storage. */
        const val MAX_PAYLOAD_SIZE = Int.MAX_VALUE - 8

        /** Creates a sequential reader for an Annex B AV1 bitstream.
         * 
         * @param input the forward-only buffered byte source
         * @return a reader that consumes Annex B temporal, frame, and OBU length fields
         */
        fun forAnnexB(input: BufferedInput): ObuStreamReader {
            return ObuStreamReader(input, true, DEFAULT_PAYLOAD_SIZE_LIMIT)
        }

        /** Creates a sequential Annex B reader with an encoded-payload resource limit.
         * 
         * @param input the forward-only buffered byte source
         * @param payloadSizeLimit the maximum encoded payload retained for one OBU, or `0` to apply
         * only the implementation limit
         * @return a reader that consumes Annex B temporal, frame, and OBU length fields
         */
        fun forAnnexB(input: BufferedInput, payloadSizeLimit: Long): ObuStreamReader {
            return ObuStreamReader(input, true, payloadSizeLimit)
        }

        /** Creates a contextual invalid-header exception.
         * 
         * @param message the detailed error message
         * @param obuOffset the OBU header offset
         * @param currentObuIndex the OBU index
         * @return the contextual invalid-header exception
         */
        fun invalidHeader(
            message: String,
            obuOffset: Long,
            currentObuIndex: Int
        ): Av1DecodeException {
            return Av1DecodeException(
                Av1DecodeErrorCode.INVALID_OBU_HEADER,
                Av1DecodeStage.OBU_READ,
                message,
                obuOffset,
                currentObuIndex,
                null
            )
        }
    }
}
