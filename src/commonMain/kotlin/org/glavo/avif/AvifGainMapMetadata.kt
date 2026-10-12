// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.requireNotNullCompat

/** Parsed ISO 21496-1 gain-map metadata from an AVIF `tmap` item.
 *
 * The per-channel arrays always contain three RGB entries. Single-channel metadata is expanded
 * by repeating channel 0 into channels 1 and 2, matching the AVIF gain-map metadata syntax.
 *
 * @param multichannel whether the source metadata signaled three independent channels
 * @param useBaseColorSpace whether tone mapping should be performed in the base image color space
 * @param baseHdrHeadroom the log2-encoded HDR headroom of the base image
 * @param alternateHdrHeadroom the log2-encoded HDR headroom of the alternate image
 * @param gainMapMin per-channel minimum gain-map values in log2 space
 * @param gainMapMax per-channel maximum gain-map values in log2 space
 * @param gainMapGamma per-channel gain-map encoding gamma values
 * @param baseOffset per-channel base image offsets
 * @param alternateOffset per-channel alternate image offsets
 */
class AvifGainMapMetadata(
    private val _multichannel: Boolean,
    private val _useBaseColorSpace: Boolean,
    baseHdrHeadroom: AvifUnsignedFraction,
    alternateHdrHeadroom: AvifUnsignedFraction,
    gainMapMin: Array<AvifSignedFraction>,
    gainMapMax: Array<AvifSignedFraction>,
    gainMapGamma: Array<AvifUnsignedFraction>,
    baseOffset: Array<AvifSignedFraction>,
    alternateOffset: Array<AvifSignedFraction>
) {
    private val _baseHdrHeadroom: AvifUnsignedFraction
    private val _alternateHdrHeadroom: AvifUnsignedFraction
    private val _gainMapMin: Array<AvifSignedFraction>
    private val _gainMapMax: Array<AvifSignedFraction>
    private val _gainMapGamma: Array<AvifUnsignedFraction>
    private val _baseOffset: Array<AvifSignedFraction>
    private val _alternateOffset: Array<AvifSignedFraction>

    init {
        val checkedBaseHdrHeadroom =
            requireNotNullCompat(baseHdrHeadroom, "baseHdrHeadroom")
        val checkedAlternateHdrHeadroom =
            requireNotNullCompat(alternateHdrHeadroom, "alternateHdrHeadroom")
        val checkedGainMapMin = copySignedFractions(gainMapMin, "gainMapMin")
        val checkedGainMapMax = copySignedFractions(gainMapMax, "gainMapMax")
        val checkedGainMapGamma = copyUnsignedFractions(gainMapGamma, "gainMapGamma")
        val checkedBaseOffset = copySignedFractions(baseOffset, "baseOffset")
        val checkedAlternateOffset = copySignedFractions(alternateOffset, "alternateOffset")

        for (i in 0 until CHANNEL_COUNT) {
            require(
                compareSignedFractions(checkedGainMapMax[i], checkedGainMapMin[i]) >= 0
            ) { "gainMapMax is less than gainMapMin for channel $i" }
            require(checkedGainMapGamma[i].numerator() != 0L) { "gainMapGamma numerator is zero for channel $i" }
        }
        this._baseHdrHeadroom = checkedBaseHdrHeadroom
        this._alternateHdrHeadroom = checkedAlternateHdrHeadroom
        this._gainMapMin = checkedGainMapMin
        this._gainMapMax = checkedGainMapMax
        this._gainMapGamma = checkedGainMapGamma
        this._baseOffset = checkedBaseOffset
        this._alternateOffset = checkedAlternateOffset
    }

    /** Returns whether the source metadata signaled three independent channels. */
    fun multichannel(): Boolean = _multichannel

    /** Returns whether tone mapping should be performed in the base image color space. */
    fun useBaseColorSpace(): Boolean = _useBaseColorSpace

    /** Returns the log2-encoded HDR headroom of the base image. */
    fun baseHdrHeadroom(): AvifUnsignedFraction = _baseHdrHeadroom

    /** Returns the log2-encoded HDR headroom of the alternate image. */
    fun alternateHdrHeadroom(): AvifUnsignedFraction = _alternateHdrHeadroom

    /** Returns per-channel minimum gain-map values in log2 space. */
    fun gainMapMin(): Array<AvifSignedFraction> = _gainMapMin.copyOf()

    /** Returns per-channel maximum gain-map values in log2 space. */
    fun gainMapMax(): Array<AvifSignedFraction> = _gainMapMax.copyOf()

    /** Returns per-channel gain-map encoding gamma values. */
    fun gainMapGamma(): Array<AvifUnsignedFraction> = _gainMapGamma.copyOf()

    /** Returns per-channel base image offsets. */
    fun baseOffset(): Array<AvifSignedFraction> = _baseOffset.copyOf()

    /** Returns per-channel alternate image offsets. */
    fun alternateOffset(): Array<AvifSignedFraction> = _alternateOffset.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is AvifGainMapMetadata) {
            return false
        }
        return _multichannel == other._multichannel &&
            _useBaseColorSpace == other._useBaseColorSpace &&
            _baseHdrHeadroom == other._baseHdrHeadroom &&
            _alternateHdrHeadroom == other._alternateHdrHeadroom &&
            _gainMapMin.contentEquals(other._gainMapMin) &&
            _gainMapMax.contentEquals(other._gainMapMax) &&
            _gainMapGamma.contentEquals(other._gainMapGamma) &&
            _baseOffset.contentEquals(other._baseOffset) &&
            _alternateOffset.contentEquals(other._alternateOffset)
    }

    override fun hashCode(): Int {
        var result = _multichannel.hashCode()
        result = 31 * result + _useBaseColorSpace.hashCode()
        result = 31 * result + _baseHdrHeadroom.hashCode()
        result = 31 * result + _alternateHdrHeadroom.hashCode()
        result = 31 * result + _gainMapMin.contentHashCode()
        result = 31 * result + _gainMapMax.contentHashCode()
        result = 31 * result + _gainMapGamma.contentHashCode()
        result = 31 * result + _baseOffset.contentHashCode()
        result = 31 * result + _alternateOffset.contentHashCode()
        return result
    }

    override fun toString(): String {
        return ("AvifGainMapMetadata["
            + "multichannel=$_multichannel"
            + ", useBaseColorSpace=$_useBaseColorSpace"
            + ", baseHdrHeadroom=$_baseHdrHeadroom"
            + ", alternateHdrHeadroom=$_alternateHdrHeadroom"
            + ", gainMapMin=" + _gainMapMin.contentToString()
            + ", gainMapMax=" + _gainMapMax.contentToString()
            + ", gainMapGamma=" + _gainMapGamma.contentToString()
            + ", baseOffset=" + _baseOffset.contentToString()
            + ", alternateOffset=" + _alternateOffset.contentToString() + ']')
    }

    companion object {
        private const val CHANNEL_COUNT = 3

        fun copySignedFractions(
            values: Array<AvifSignedFraction>,
            name: String
        ): Array<AvifSignedFraction> {
            requireNotNullCompat(values, name)
            require(values.size == CHANNEL_COUNT) { name + " length must be 3: " + values.size }
            val copy = values.copyOf()
            for (i in 0 until CHANNEL_COUNT) {
                copy[i] = requireNotNullCompat(copy[i], name + "[$i]")
            }
            return copy
        }

        fun copyUnsignedFractions(
            values: Array<AvifUnsignedFraction>,
            name: String
        ): Array<AvifUnsignedFraction> {
            requireNotNullCompat(values, name)
            require(values.size == CHANNEL_COUNT) { name + " length must be 3: " + values.size }
            val copy = values.copyOf()
            for (i in 0 until CHANNEL_COUNT) {
                copy[i] = requireNotNullCompat(copy[i], name + "[$i]")
            }
            return copy
        }

        fun compareSignedFractions(
            left: AvifSignedFraction,
            right: AvifSignedFraction
        ): Int {
            val leftScaled = left.numerator().toLong() * right.denominator()
            val rightScaled = right.numerator().toLong() * left.denominator()
            return leftScaled.compareTo(rightScaled)
        }
    }
}
