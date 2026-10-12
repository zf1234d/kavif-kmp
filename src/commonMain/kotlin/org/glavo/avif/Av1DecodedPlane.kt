// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable decoded AV1 sample plane.
 * 
 * Samples are unsigned values stored in the low bits of each `short`. `stride` is measured in
 * samples, not bytes. */
class Av1DecodedPlane(width: Int, height: Int, stride: Int, samples: ShortArray) {
    /** The plane width in samples. */
    private val width: Int

    /** The plane height in samples. */
    private val height: Int

    /** The plane row stride in samples. */
    private val stride: Int

    /** The immutable row-major sample storage. */
    private val samples: ShortArray

    init {
        require(width > 0) { "width <= 0: $width" }
        require(height > 0) { "height <= 0: $height" }
        require(stride >= width) { "stride < width: $stride" }
        val requiredLength = stride.toLong() * height
        require(requiredLength <= Int.MAX_VALUE) { "plane storage is too large" }
        val checkedSamples =
            requireNotNullCompat(samples, "samples")
        require(checkedSamples.size == requiredLength.toInt()) { "samples length does not match stride * height" }
        this.width = width
        this.height = height
        this.stride = stride
        this.samples = checkedSamples.copyOf()
    }

    /** Returns the plane width in samples.
     * 
     * @return the plane width in samples
     */
    fun width(): Int {
        return width
    }

    /** Returns the plane height in samples.
     * 
     * @return the plane height in samples
     */
    fun height(): Int {
        return height
    }

    /** Returns the plane row stride in samples.
     * 
     * @return the plane row stride in samples
     */
    fun stride(): Int {
        return stride
    }

    /** Returns a copy of the unsigned plane samples.
     * 
     * @return a copy of the unsigned plane samples
     */
    fun samples(): ShortArray {
        return samples.copyOf()
    }

    /** Returns a read-only view of the unsigned plane samples.
     * 
     * @return a read-only view of the unsigned plane samples
     */
    fun sampleBuffer(): ShortArray {
        return samples.copyOf()
    }

    /** Returns one unsigned sample value.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @return one unsigned sample value
     */
    fun sample(x: Int, y: Int): Int {
        if (x < 0 || x >= width) {
            throw IndexOutOfBoundsException("x out of range: $x")
        }
        if (y < 0 || y >= height) {
            throw IndexOutOfBoundsException("y out of range: $y")
        }
        return samples[y * stride + x].toInt() and 0xFFFF
    }

    companion object {
        /** Creates immutable sample storage from an array.
         * 
         * @param samples the source samples
         * @return immutable sample storage
         */
        fun immutableSamples(samples: ShortArray): ShortArray {
            return samples.copyOf()
        }
    }
}
