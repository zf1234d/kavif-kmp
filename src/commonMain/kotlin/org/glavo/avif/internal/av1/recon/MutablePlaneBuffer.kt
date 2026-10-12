// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.image.PaddedPlane


/** Mutable decoded-plane buffer used while reconstruction is still in progress.
 * 
 * Samples are stored as unsigned values in the low bits of each `short`. The mutable buffer uses
 * tightly packed storage for either the complete plane or one coordinate-preserving subregion. */
internal class MutablePlaneBuffer constructor(
    width: Int,
    height: Int,
    bitDepth: Int,
    originX: Int = 0,
    originY: Int = 0,
    storageWidth: Int = width,
    storageHeight: Int = height
) : MutableSamplePlane {
    /** The containing plane width in samples. */
    private val width: Int

    /** The containing plane height in samples. */
    private val height: Int

    /** The horizontal origin of the retained storage region. */
    private val originX: Int

    /** The vertical origin of the retained storage region. */
    private val originY: Int

    /** The retained storage width in samples. */
    private val storageWidth: Int

    /** The retained storage height in samples. */
    private val storageHeight: Int

    /** The decoded sample bit depth. */
    private val bitDepth: Int

    /** The maximum legal sample value for this bit depth. */
    private val maxSampleValue: Int

    /** The tightly packed mutable sample buffer. */
    private val samples: ShortArray

    /** Packed written-state bits for retained sample positions. */
    private val writtenSampleBits: LongArray

    /** Creates a mutable buffer that retains one subregion in containing-plane coordinates.
     * 
     * @param width the containing plane width in samples
     * @param height the containing plane height in samples
     * @param bitDepth the decoded sample bit depth
     * @param originX the horizontal storage origin in containing-plane coordinates
     * @param originY the vertical storage origin in containing-plane coordinates
     * @param storageWidth the retained storage width in samples
     * @param storageHeight the retained storage height in samples
     */
    init {
        require(width > 0) { "width <= 0: $width" }
        require(height > 0) { "height <= 0: $height" }
        require(!(bitDepth <= 0 || bitDepth > 15)) { "bitDepth out of range: $bitDepth" }
        require(!(originX < 0 || storageWidth <= 0 || originX + storageWidth > width)) { "Horizontal storage region is outside the plane" }
        require(!(originY < 0 || storageHeight <= 0 || originY + storageHeight > height)) { "Vertical storage region is outside the plane" }
        this.width = width
        this.height = height
        this.originX = originX
        this.originY = originY
        this.storageWidth = storageWidth
        this.storageHeight = storageHeight
        this.bitDepth = bitDepth
        this.maxSampleValue = (1 shl bitDepth) - 1
        val sampleCount = multiplyExact(storageWidth, storageHeight)
        this.samples = ShortArray(sampleCount)
        this.writtenSampleBits = LongArray(writtenWordCount(sampleCount))
    }

    /** Returns the plane width in samples.
     * 
     * @return the plane width in samples
     */
    override fun width(): Int {
        return width
    }

    /** Returns the plane height in samples.
     * 
     * @return the plane height in samples
     */
    override fun height(): Int {
        return height
    }

    /** Returns the decoded sample bit depth.
     * 
     * @return the decoded sample bit depth
     */
    override fun bitDepth(): Int {
        return bitDepth
    }

    /** Returns the maximum legal sample value for this bit depth.
     * 
     * @return the maximum legal sample value for this bit depth
     */
    fun maxSampleValue(): Int {
        return maxSampleValue
    }

    /** Returns one already reconstructed sample.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @return one already reconstructed sample
     */
    override fun sample(x: Int, y: Int): Int {
        return samples[storageIndex(x, y)].toInt() and 0xFFFF
    }

    /** Stores one reconstructed sample after clipping it into the legal bit-depth range.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param value the reconstructed sample value
     */
    override fun setSample(x: Int, y: Int, value: Int) {
        val index = storageIndex(x, y)
        samples[index] = clipped(value).toShort()
        markWritten(index)
    }

    /** Fills the in-plane portion of one rectangular block and marks it as written.
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
        val writtenWidth = min(blockWidth, width - x)
        val writtenHeight = min(blockHeight, height - y)
        if (localX < 0 || writtenWidth <= 0 || writtenWidth > storageWidth - localX) {
            throw IndexOutOfBoundsException("Block origin is outside retained horizontal storage")
        }
        if (localY < 0 || writtenHeight <= 0 || writtenHeight > storageHeight - localY) {
            throw IndexOutOfBoundsException("Block origin is outside retained vertical storage")
        }

        val sample = clipped(value).toShort()
        for (row in 0..<writtenHeight) {
            val startIndex = (localY + row) * storageWidth + localX
            fillCompat(samples, startIndex, startIndex + writtenWidth, sample)
            markWrittenRange(startIndex, writtenWidth)
        }
    }

    /** Adds one constant signed value to every sample in a retained rectangular block.
     * 
     * Each result is clipped independently to the legal bit-depth range and marked as written.
     * 
     * @param x the horizontal block origin in containing-plane coordinates
     * @param y the vertical block origin in containing-plane coordinates
     * @param blockWidth the positive block width in samples
     * @param blockHeight the positive block height in samples
     * @param value the signed value to add
     */
    fun addConstantBlock(x: Int, y: Int, blockWidth: Int, blockHeight: Int, value: Int) {
        val localX = x - originX
        val localY = y - originY
        if (blockWidth <= 0 || localX < 0 || blockWidth > storageWidth - localX) {
            throw IndexOutOfBoundsException("Block is outside retained horizontal storage")
        }
        if (blockHeight <= 0 || localY < 0 || blockHeight > storageHeight - localY) {
            throw IndexOutOfBoundsException("Block is outside retained vertical storage")
        }

        for (row in 0..<blockHeight) {
            val startIndex = (localY + row) * storageWidth + localX
            var index = startIndex
            val rowEnd = startIndex + blockWidth
            while (index < rowEnd) {
                samples[index] = clipped((samples[index].toInt() and 0xFFFF) + value).toShort()
                index++
            }
            markWrittenRange(startIndex, blockWidth)
        }
    }

    /** Copies one edge-extended source block into retained storage and marks it as written.
     * 
     * @param source the immutable source plane
     * @param destinationX the horizontal destination origin in containing-plane coordinates
     * @param destinationY the vertical destination origin in containing-plane coordinates
     * @param sourceX the horizontal source origin
     * @param sourceY the vertical source origin
     * @param blockWidth the positive block width in samples
     * @param blockHeight the positive block height in samples
     */
    fun copyExtendedBlockFrom(
        source: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        sourceX: Int,
        sourceY: Int,
        blockWidth: Int,
        blockHeight: Int
    ) {
        val checkedSource = requireNotNullCompat(source, "source")
        val localX = destinationX - originX
        val localY = destinationY - originY
        if (blockWidth <= 0 || localX < 0 || blockWidth > storageWidth - localX) {
            throw IndexOutOfBoundsException("Block is outside retained horizontal storage")
        }
        if (blockHeight <= 0 || localY < 0 || blockHeight > storageHeight - localY) {
            throw IndexOutOfBoundsException("Block is outside retained vertical storage")
        }

        for (row in 0..<blockHeight) {
            val destinationIndex = (localY + row) * storageWidth + localX
            checkedSource.copyExtendedRowTo(
                sourceX,
                sourceY + row,
                samples,
                destinationIndex,
                blockWidth
            )
            markWrittenRange(destinationIndex, blockWidth)
        }
    }

    /** Returns whether one retained-region sample has been written by reconstruction.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @return whether the retained sample has been written, or `false` outside retained storage
     */
    fun hasWrittenSample(x: Int, y: Int): Boolean {
        val localX = x - originX
        val localY = y - originY
        return localX >= 0 && localX < storageWidth && localY >= 0 && localY < storageHeight && isWritten(
            localY * storageWidth + localX
        )
    }

    /** Returns one sample when it lies inside retained storage, or the fallback value otherwise.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param fallbackValue the fallback value returned outside the plane bounds
     * @return one retained sample, or the supplied fallback value
     */
    fun sampleOrFallback(x: Int, y: Int, fallbackValue: Int): Int {
        val localX = x - originX
        val localY = y - originY
        if (localX < 0 || localX >= storageWidth || localY < 0 || localY >= storageHeight) {
            return fallbackValue
        }
        return samples[localY * storageWidth + localX].toInt() and 0xFFFF
    }

    /** Converts this mutable reconstruction buffer into one immutable decoded-plane snapshot.
     * 
     * @return one immutable decoded-plane snapshot
     * @throws IllegalStateException if this buffer retains only a plane subregion
     */
    fun toDecodedPlane(): PaddedPlane {
        requireCompletePlaneStorage()
        return PaddedPlane(width, height, width, samples)
    }

    /** Transfers the retained storage region into one immutable coordinate-local decoded plane.
     * 
     * The caller must permanently discard this mutable buffer after the transfer. The requested
     * dimensions are relative to the retained region and may omit its right or bottom padding.
     * 
     * @param croppedWidth the visible retained-region width in samples
     * @param croppedHeight the visible retained-region height in samples
     * @return one immutable plane that owns the retained sample storage
     */
    fun takeStoredDecodedPlane(croppedWidth: Int, croppedHeight: Int): PaddedPlane {
        require(!(croppedWidth <= 0 || croppedWidth > storageWidth)) { "croppedWidth out of range: $croppedWidth" }
        require(!(croppedHeight <= 0 || croppedHeight > storageHeight)) { "croppedHeight out of range: $croppedHeight" }
        return PaddedPlane.Companion.fromOwnedSamples(
            croppedWidth,
            croppedHeight,
            storageWidth,
            samples
        )
    }

    /** Creates an independent mutable copy of this plane buffer.
     * 
     * @return an independent mutable copy of this plane buffer
     */
    fun copy(): MutablePlaneBuffer {
        val copy = MutablePlaneBuffer(
            width,
            height,
            bitDepth,
            originX,
            originY,
            storageWidth,
            storageHeight
        )
        arraycopy(samples, 0, copy.samples, 0, samples.size)
        arraycopy(writtenSampleBits, 0, copy.writtenSampleBits, 0, writtenSampleBits.size)
        return copy
    }

    /** Marks one compact sample position as written.
     * 
     * @param index the compact sample index
     */
    private fun markWritten(index: Int) {
        writtenSampleBits[index / Long.SIZE_BITS] =
            writtenSampleBits[index / Long.SIZE_BITS] or (1L shl (index % Long.SIZE_BITS))
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

    /** Returns whether one compact sample position has been written.
     * 
     * @param index the compact sample index
     * @return whether the sample has been written
     */
    private fun isWritten(index: Int): Boolean {
        return (writtenSampleBits[index / Long.SIZE_BITS] and (1L shl (index % Long.SIZE_BITS))) != 0L
    }

    /** Returns the compact storage index for one containing-plane coordinate.
     * 
     * @param x the horizontal containing-plane coordinate
     * @param y the vertical containing-plane coordinate
     * @return the compact storage index
     */
    private fun storageIndex(x: Int, y: Int): Int {
        val localX = x - originX
        val localY = y - originY
        if (localX < 0 || localX >= storageWidth) {
            throw IndexOutOfBoundsException("x outside retained storage: $x")
        }
        if (localY < 0 || localY >= storageHeight) {
            throw IndexOutOfBoundsException("y outside retained storage: $y")
        }
        return localY * storageWidth + localX
    }

    /** Ensures that snapshot operations requiring a top-left plane own complete storage. */
    private fun requireCompletePlaneStorage() {
        check(!(originX != 0 || originY != 0 || storageWidth != width || storageHeight != height)) { "Operation requires complete plane storage" }
    }

    /** Clips one sample value into the legal bit-depth range.
     * 
     * @param value the sample value to clip
     * @return the clipped sample value
     */
    private fun clipped(value: Int): Int {
        if (value <= 0) {
            return 0
        }
        if (value >= maxSampleValue) {
            return maxSampleValue
        }
        return value
    }

    companion object {
        /** Returns the number of words needed to store one written-state bit per sample.
         * 
         * @param sampleCount the positive retained sample count
         * @return the required packed-word count
         */
        fun writtenWordCount(sampleCount: Int): Int {
            return ((sampleCount.toLong() + Long.SIZE_BITS - 1L) / Long.SIZE_BITS).toInt()
        }
    }
}
