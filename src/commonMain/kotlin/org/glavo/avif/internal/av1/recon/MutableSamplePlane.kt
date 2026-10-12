// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Provides the mutable sample operations required by intra prediction.
 * 
 * Coordinates use the containing plane's sample grid. Implementations may store the whole plane,
 * retain only the reconstruction region supplied to callers, or expose a writable overlay backed
 * by another plane. */
internal interface MutableSamplePlane {
    /** Returns the plane width in samples.
     * 
     * @return the plane width in samples
     */
    fun width(): Int

    /** Returns the plane height in samples.
     * 
     * @return the plane height in samples
     */
    fun height(): Int

    /** Returns the decoded sample bit depth.
     * 
     * @return the decoded sample bit depth
     */
    fun bitDepth(): Int

    /** Returns the sample at one in-range plane coordinate.
     * 
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @return the sample value
     */
    fun sample(x: Int, y: Int): Int

    /** Stores one sample at an in-range writable coordinate.
     * 
     * @param x     the zero-based horizontal sample coordinate
     * @param y     the zero-based vertical sample coordinate
     * @param value the sample value, clipped to the legal bit-depth range
     */
    fun setSample(x: Int, y: Int, value: Int)

    /** Fills the in-plane portion of one rectangular block with a constant sample value.
     * 
     * The origin must be an in-range writable coordinate. Samples beyond the right or bottom
     * plane edge are ignored, matching coded-block reconstruction at visible frame boundaries.
     * 
     * @param x the zero-based horizontal block origin
     * @param y the zero-based vertical block origin
     * @param blockWidth the positive coded block width in samples
     * @param blockHeight the positive coded block height in samples
     * @param value the sample value, clipped to the legal bit-depth range
     */
    fun fillBlock(x: Int, y: Int, blockWidth: Int, blockHeight: Int, value: Int)
}
