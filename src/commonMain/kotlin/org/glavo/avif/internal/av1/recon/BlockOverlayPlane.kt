// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Stores predicted samples for one block while reading all other samples through a base plane.
 * 
 * Unwritten positions inside the block also read through to the base plane. This preserves the
 * initial predictor state while allowing recursive intra kernels to observe samples they have
 * already written without copying the containing frame. */
internal class BlockOverlayPlane(
    basePlane: MutableSamplePlane,
    originX: Int,
    originY: Int,
    overlayWidth: Int,
    overlayHeight: Int
) : MutableSamplePlane {
    /** The plane that supplies samples outside the overlay and unwritten samples inside it. */
    private val basePlane: MutableSamplePlane

    /** The inclusive horizontal origin of the writable overlay. */
    private val originX: Int

    /** The inclusive vertical origin of the writable overlay. */
    private val originY: Int

    /** The writable overlay width in samples. */
    private val overlayWidth: Int

    /** The writable overlay height in samples. */
    private val overlayHeight: Int

    /** The maximum legal sample value for the plane bit depth. */
    private val maximumSampleValue: Int

    /** The compact overlay sample storage. */
    private val samples: ShortArray

    /** Packed written-state bits for compact overlay positions. */
    private val writtenSampleBits: LongArray

    init {
        this.basePlane = requireNotNullCompat(basePlane, "basePlane")
        require(!(originX < 0 || originX >= basePlane.width())) { "originX out of range: $originX" }
        require(!(originY < 0 || originY >= basePlane.height())) { "originY out of range: $originY" }
        require(overlayWidth > 0) { "overlayWidth out of range: $overlayWidth" }
        require(overlayHeight > 0) { "overlayHeight out of range: $overlayHeight" }
        this.originX = originX
        this.originY = originY
        this.overlayWidth = min(overlayWidth, basePlane.width() - originX)
        this.overlayHeight = min(overlayHeight, basePlane.height() - originY)
        this.maximumSampleValue = (1 shl basePlane.bitDepth()) - 1
        val sampleCount = multiplyExact(this.overlayWidth, this.overlayHeight)
        this.samples = ShortArray(sampleCount)
        this.writtenSampleBits = LongArray(writtenWordCount(sampleCount))
    }

    /** Returns the width of the containing base plane.
     * 
     * @return the containing plane width in samples
     */
    override fun width(): Int {
        return basePlane.width()
    }

    /** Returns the height of the containing base plane.
     * 
     * @return the containing plane height in samples
     */
    override fun height(): Int {
        return basePlane.height()
    }

    /** Returns the base plane's decoded sample bit depth.
     * 
     * @return the decoded sample bit depth
     */
    override fun bitDepth(): Int {
        return basePlane.bitDepth()
    }

    /** Returns an overlay sample when written, or reads through to the base plane otherwise.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @return the overlay or base-plane sample
     */
    override fun sample(x: Int, y: Int): Int {
        val index = overlayIndex(x, y)
        if (index >= 0 && isWritten(index)) {
            return samples[index].toInt() and 0xFFFF
        }
        return basePlane.sample(x, y)
    }

    /** Stores one clipped sample inside the writable overlay.
     * 
     * @param x     the zero-based horizontal sample coordinate
     * @param y     the zero-based vertical sample coordinate
     * @param value the sample value
     */
    override fun setSample(x: Int, y: Int, value: Int) {
        val index = overlayIndex(x, y)
        if (index < 0) {
            throw IndexOutOfBoundsException("coordinate outside block overlay: $x, $y")
        }
        samples[index] = max(0, min(value, maximumSampleValue)).toShort()
        writtenSampleBits[index / Long.SIZE_BITS] =
            writtenSampleBits[index / Long.SIZE_BITS] or (1L shl (index % Long.SIZE_BITS))
    }

    /** Fills the in-plane portion of one rectangular block inside this overlay.
     * 
     * @param x the horizontal block origin in containing-plane coordinates
     * @param y the vertical block origin in containing-plane coordinates
     * @param blockWidth the positive coded block width in samples
     * @param blockHeight the positive coded block height in samples
     * @param value the sample value, clipped to the legal bit-depth range
     */
    override fun fillBlock(x: Int, y: Int, blockWidth: Int, blockHeight: Int, value: Int) {
        require(blockWidth > 0) { "blockWidth <= 0: $blockWidth" }
        require(blockHeight > 0) { "blockHeight <= 0: $blockHeight" }
        val localX = x - originX
        val localY = y - originY
        val writtenWidth = min(blockWidth, width() - x)
        val writtenHeight = min(blockHeight, height() - y)
        if (localX < 0 || writtenWidth <= 0 || writtenWidth > overlayWidth - localX) {
            throw IndexOutOfBoundsException("Block origin is outside overlay horizontal storage")
        }
        if (localY < 0 || writtenHeight <= 0 || writtenHeight > overlayHeight - localY) {
            throw IndexOutOfBoundsException("Block origin is outside overlay vertical storage")
        }

        val sample = max(0, min(value, maximumSampleValue)).toShort()
        for (row in 0..<writtenHeight) {
            val startIndex = (localY + row) * overlayWidth + localX
            fillCompat(samples, startIndex, startIndex + writtenWidth, sample)
            markWrittenRange(startIndex, writtenWidth)
        }
    }

    /** Marks one non-empty contiguous compact sample range as written.
     * 
     * @param startIndex the first compact sample index
     * @param length the positive number of samples to mark
     */
    private fun markWrittenRange(startIndex: Int, length: Int) {
        val endIndex = startIndex + length
        val firstWord = startIndex / Long.SIZE_BITS
        val lastWord = (endIndex - 1) / Long.SIZE_BITS
        val firstMask = -1L shl (startIndex % Long.SIZE_BITS)
        val lastMask = -1L ushr (Long.SIZE_BITS - 1 - ((endIndex - 1) % Long.SIZE_BITS))
        if (firstWord == lastWord) {
            writtenSampleBits[firstWord] = writtenSampleBits[firstWord] or (firstMask and lastMask)
            return
        }
        writtenSampleBits[firstWord] = writtenSampleBits[firstWord] or firstMask
        fillCompat(writtenSampleBits, firstWord + 1, lastWord, -1L)
        writtenSampleBits[lastWord] = writtenSampleBits[lastWord] or lastMask
    }

    /** Returns whether one compact overlay position has been written.
     * 
     * @param index the compact overlay index
     * @return whether the overlay sample has been written
     */
    private fun isWritten(index: Int): Boolean {
        return (writtenSampleBits[index / Long.SIZE_BITS] and (1L shl (index % Long.SIZE_BITS))) != 0L
    }

    /** Returns the compact storage index for one coordinate, or `-1` when it lies outside the overlay.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @return the compact storage index, or `-1`
     */
    private fun overlayIndex(x: Int, y: Int): Int {
        val localX = x - originX
        val localY = y - originY
        if (localX < 0 || localX >= overlayWidth || localY < 0 || localY >= overlayHeight) {
            return -1
        }
        return localY * overlayWidth + localX
    }

    companion object {
        /** Returns the number of words needed to store one written-state bit per sample.
         * 
         * @param sampleCount the positive overlay sample count
         * @return the required packed-word count
         */
        fun writtenWordCount(sampleCount: Int): Int {
            return ((sampleCount.toLong() + Long.SIZE_BITS - 1L) / Long.SIZE_BITS).toInt()
        }
    }
}
