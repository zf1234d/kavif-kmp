// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.entropy

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.model.TileBitstream


/** AV1 multi-symbol arithmetic decoder for a single tile bitstream. */
class MsacDecoder(data: ByteArray, dataOffset: Int, dataLength: Int, disableCdfUpdate: Boolean) {
    /** The backing bytes that contain the entropy-coded tile payload. */
    private val data: ByteArray

    /** The exclusive end offset of the readable slice inside `data`. */
    private val endOffset: Int

    /** Whether decoded symbols are allowed to update their CDFs in place. */
    private val allowCdfUpdate: Boolean

    /** The next unread byte offset inside `data`. */
    private var position: Int

    /** The decoder difference register. */
    private var difference: Long = 0

    /** The decoder range register. */
    private var range: Int

    /** The number of valid bits currently buffered in `difference`. */
    private var count: Int

    /** Creates an arithmetic decoder over a tile bitstream.
     * 
     * @param bitstream the tile bitstream to decode
     * @param disableCdfUpdate whether CDF updates are disabled for the active frame
     */
    constructor(bitstream: TileBitstream, disableCdfUpdate: Boolean) : this(
        requireNotNullCompat(bitstream, "bitstream").copyBytes(),
        0,
        bitstream.dataLength(),
        disableCdfUpdate
    )

    init {
        this.data = requireNotNullCompat(data, "data")
        require(dataOffset in 0..data.size) { "dataOffset out of range: $dataOffset" }
        require(!(dataLength < 0 || dataOffset + dataLength > data.size)) { "dataLength out of range: $dataLength" }

        this.position = dataOffset
        this.endOffset = dataOffset + dataLength
        this.allowCdfUpdate = !disableCdfUpdate
        this.range = 0x8000
        this.count = -15
        refill()
    }

    /** Returns whether decoded symbols are allowed to update their CDFs in place.
     * 
     * @return whether decoded symbols are allowed to update their CDFs in place
     */
    fun allowCdfUpdate(): Boolean {
        return allowCdfUpdate
    }

    /** Returns the current decoder range register.
     * 
     * @return the current decoder range register
     */
    fun range(): Int {
        return range
    }

    /** Returns the current buffered-bit count used by the entropy decoder.
     * 
     * @return the current buffered-bit count used by the entropy decoder
     */
    fun count(): Int {
        return count
    }

    /** Decodes an equiprobable binary value.
     * 
     * @return the decoded binary value
     */
    fun decodeBooleanEqui(): Boolean {
        val localRange = range
        var localDifference = difference
        var value: Int = ((localRange ushr 8) shl 7) + MINIMUM_PROBABILITY
        val split = (value.toLong()) shl (WINDOW_SIZE - 16)
        val upperPartition = compareUnsigned(localDifference, split) >= 0
        localDifference -= if (upperPartition) split else 0L
        value += if (upperPartition) (localRange - (value shl 1)) else 0
        normalize(localDifference, value)
        return !upperPartition
    }

    /** Decodes a binary value with the supplied probability that the bit is one.
     * 
     * @param probability the Q15 probability that the decoded bit is one
     * @return the decoded binary value
     */
    fun decodeBoolean(probability: Int): Boolean {
        require(probability in 0..32768) { "probability out of range: $probability" }

        val localRange = range
        var localDifference = difference
        var value: Int =
            (((localRange ushr 8) * (probability ushr PROBABILITY_SHIFT) ushr (7 - PROBABILITY_SHIFT))
                    + MINIMUM_PROBABILITY)
        val split = (value.toLong()) shl (WINDOW_SIZE - 16)
        val upperPartition = compareUnsigned(localDifference, split) >= 0
        localDifference -= if (upperPartition) split else 0L
        value += if (upperPartition) (localRange - (value shl 1)) else 0
        normalize(localDifference, value)
        return !upperPartition
    }

    /** Decodes a binary value and updates an AV1 boolean CDF in place when allowed.
     * 
     * @param cdf the boolean CDF array in AV1 Q15 form
     * @return the decoded binary value
     */
    fun decodeBooleanAdapt(cdf: IntArray): Boolean {
        requireNotNullCompat(cdf, "cdf")
        require(cdf.size >= 2) { "Boolean CDF array must have at least two entries" }

        val bit = decodeBoolean(cdf[0])
        if (allowCdfUpdate) {
            val countValue = cdf[1]
            val rate = 4 + (countValue ushr 4)
            if (bit) {
                cdf[0] += (32768 - cdf[0]) ushr rate
            } else {
                cdf[0] -= cdf[0] ushr rate
            }
            cdf[1] = countValue + (if (countValue < 32) 1 else 0)
        }
        return bit
    }

    /** Decodes a symbol from an AV1 inverse cumulative distribution function.
     * 
     * @param cdf the inverse CDF table in AV1 Q15 form
     * @param symbolLimit the maximum decoded symbol value, with the update count stored at `cdf[symbolLimit]`
     * @return the decoded symbol index
     */
    fun decodeSymbolAdapt(cdf: IntArray, symbolLimit: Int): Int {
        requireNotNullCompat(cdf, "cdf")
        require(!(symbolLimit <= 0 || symbolLimit > 15)) { "symbolLimit out of range: $symbolLimit" }
        require(cdf.size >= symbolLimit + 1) { "CDF array length does not cover the requested symbol range" }

        val differenceHigh = (difference ushr (WINDOW_SIZE - 16)).toInt()
        val scaledRange = range ushr 8
        var upperBound = range
        var lowerBound: Int
        var symbol = -1
        do {
            symbol++
            lowerBound = upperBound
            if (symbol == symbolLimit) {
                upperBound = 0
            } else {
                upperBound = scaledRange * (cdf[symbol] ushr PROBABILITY_SHIFT)
                upperBound = upperBound ushr 7 - PROBABILITY_SHIFT
                upperBound += MINIMUM_PROBABILITY * (symbolLimit - symbol)
            }
        } while (differenceHigh < upperBound)

        normalize(
            difference - ((upperBound.toLong()) shl (WINDOW_SIZE - 16)),
            lowerBound - upperBound
        )

        if (allowCdfUpdate) {
            val countValue = cdf[symbolLimit]
            val rate = 4 + (countValue ushr 4) + (if (symbolLimit > 2) 1 else 0)
            for (i in 0..<symbol) {
                cdf[i] += (32768 - cdf[i]) ushr rate
            }
            for (i in symbol..<symbolLimit) {
                cdf[i] -= cdf[i] ushr rate
            }
            cdf[symbolLimit] = countValue + (if (countValue < 32) 1 else 0)
        }

        return symbol
    }

    /** Decodes the AV1 high-token extension tree used by coefficient coding.
     * 
     * @param cdf the AV1 inverse CDF table in Q15 form
     * @return the decoded high token value
     */
    fun decodeHighToken(cdf: IntArray): Int {
        requireNotNullCompat(cdf, "cdf")

        var tokenBranch = decodeSymbolAdapt(cdf, 3)
        var token = 3 + tokenBranch
        if (tokenBranch == 3) {
            tokenBranch = decodeSymbolAdapt(cdf, 3)
            token = 6 + tokenBranch
            if (tokenBranch == 3) {
                tokenBranch = decodeSymbolAdapt(cdf, 3)
                token = 9 + tokenBranch
                if (tokenBranch == 3) {
                    token = 12 + decodeSymbolAdapt(cdf, 3)
                }
            }
        }
        return token
    }

    /** Decodes an unsigned literal made of equiprobable binary values.
     * 
     * @param bitCount the number of equiprobable bits to decode
     * @return the decoded unsigned literal
     */
    fun decodeBools(bitCount: Int): Int {
        require(bitCount in 0..Int.SIZE_BITS) { "bitCount out of range: $bitCount" }

        var value = 0
        for (i in 0..<bitCount) {
            value = (value shl 1) or (if (decodeBooleanEqui()) 1 else 0)
        }
        return value
    }

    /** Decodes an AV1 uniformly distributed integer in `[0, symbolCount)`.
     * 
     * @param symbolCount the exclusive upper bound of the decoded value
     * @return the decoded uniformly distributed integer
     */
    fun decodeUniform(symbolCount: Int): Int {
        require(symbolCount > 0) { "symbolCount <= 0: $symbolCount" }
        if (symbolCount == 1) {
            return 0
        }

        val width = Int.SIZE_BITS - symbolCount.countLeadingZeroBits()
        val cutoff = (1 shl width) - symbolCount
        val value = decodeBools(width - 1)
        return if (value < cutoff) value else (value shl 1) - cutoff + (if (decodeBooleanEqui()) 1 else 0)
    }

    /** Decodes an AV1 subexponential value recentered around the supplied reference.
     * 
     * @param reference the recentering reference value
     * @param symbolCount the exclusive upper bound of the decoded value
     * @param k the subexponential group width
     * @return the decoded subexponential value
     */
    fun decodeSubexp(reference: Int, symbolCount: Int, k: Int): Int {
        require(symbolCount > 0) { "symbolCount <= 0: $symbolCount" }
        require(k >= 0) { "k < 0: $k" }

        var groupIndex = 0
        var offset = 0
        while (true) {
            val groupBits = if (groupIndex == 0) k else k + groupIndex - 1
            val groupSize = 1 shl groupBits
            if (symbolCount <= offset + 3 * groupSize) {
                val value = decodeUniform(symbolCount - offset) + offset
                return inverseRecenterFinite(reference, symbolCount, value)
            }

            if (!decodeBooleanEqui()) {
                val value = decodeBools(groupBits) + offset
                return inverseRecenterFinite(reference, symbolCount, value)
            }

            groupIndex++
            offset += groupSize
        }
    }

    /** Refills the difference register from the remaining tile payload bytes. */
    private fun refill() {
        var shift: Int = WINDOW_SIZE - count - 24
        var localDifference = difference
        do {
            if (position >= endOffset) {
                localDifference = localDifference or ((0xFFL.inv()) shl shift).inv()
                break
            }
            localDifference =
                localDifference or ((((data[position++].toInt() xor 0xFF) and 0xFF).toLong()) shl shift)
            shift -= Byte.SIZE_BITS
        } while (shift >= 0)

        difference = localDifference
        count = WINDOW_SIZE - shift - 24
    }

    /** Renormalizes the difference and range registers after a symbol was decoded.
     * 
     * @param localDifference the updated difference register
     * @param localRange the updated range register
     */
    private fun normalize(localDifference: Long, localRange: Int) {
        val shift = 15 - (31 - localRange.countLeadingZeroBits())
        val localCount = count
        difference = localDifference shl shift
        range = localRange shl shift
        count = localCount - shift
        if (compareUnsigned(localCount, shift) < 0) {
            refill()
        }
    }

    companion object {
        /** The AV1 entropy probability precision shift. */
        private const val PROBABILITY_SHIFT = 6

        /** The minimum probability quantum used by AV1 entropy decoding. */
        private const val MINIMUM_PROBABILITY = 4

        /** The decoder window size in bits. */
        const val WINDOW_SIZE = Long.SIZE_BITS

        /** Re-centers a finite subexponential coded value around the supplied reference.
         * 
         * @param reference the recentering reference value
         * @param symbolCount the exclusive upper bound of the decoded value
         * @param value the raw finite subexponential code value
         * @return the decoded value in `[0, symbolCount)`
         */
        fun inverseRecenterFinite(reference: Int, symbolCount: Int, value: Int): Int {
            return if (reference * 2 <= symbolCount)
                inverseRecenter(reference, value)
            else
                symbolCount - 1 - inverseRecenter(symbolCount - 1 - reference, value)
        }

        /** Re-centers a subexponential coded value around the supplied reference.
         * 
         * @param reference the recentering reference value
         * @param value the recentered code value
         * @return the de-recentered value
         */
        fun inverseRecenter(reference: Int, value: Int): Int {
            if (value > (reference shl 1)) {
                return value
            }
            return if ((value and 1) == 0)
                reference + (value ushr 1)
            else
                reference - ((value + 1) ushr 1)
        }
    }
}
