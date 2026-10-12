// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.image

import org.glavo.avif.internal.compat.arraycopy
import org.glavo.avif.internal.compat.checkFromIndexSize
import org.glavo.avif.internal.compat.fillCompat
import org.glavo.avif.internal.compat.requireNotNullCompat
import kotlin.math.max
import kotlin.math.min

/** Immutable snapshot of one decoded image plane.
 *
 * Samples are stored as unsigned values in the low bits of each `short`. `stride` is measured in
 * samples, not bytes. */
class PaddedPlane private constructor(
    private val width: Int,
    private val height: Int,
    private val stride: Int,
    private val storageHeight: Int,
    private val samples: ShortArray
) {
    constructor(width: Int, height: Int, stride: Int, samples: ShortArray) : this(
        create(width, height, stride, immutableSamples(requireNotNullCompat(samples, "samples")))
    )

    private constructor(state: State) : this(
        state.width,
        state.height,
        state.stride,
        state.storageHeight,
        state.samples
    )

    fun width(): Int = width

    fun height(): Int = height

    fun stride(): Int = stride

    fun storageHeight(): Int = storageHeight

    fun samples(): ShortArray = samples.copyOf()

    fun sampleBuffer(): ShortArray = samples.copyOfRange(0, stride * height)

    fun sample(x: Int, y: Int): Int {
        if (x < 0 || x >= width) {
            throw IndexOutOfBoundsException("x out of range: $x")
        }
        if (y < 0 || y >= height) {
            throw IndexOutOfBoundsException("y out of range: $y")
        }
        return samples[y * stride + x].toInt() and 0xFFFF
    }

    fun storedSample(x: Int, y: Int): Int {
        if (x < 0 || x >= stride) {
            throw IndexOutOfBoundsException("x out of stored range: $x")
        }
        if (y < 0 || y >= storageHeight) {
            throw IndexOutOfBoundsException("y out of stored range: $y")
        }
        return samples[y * stride + x].toInt() and 0xFFFF
    }

    fun copyExtendedRowTo(
        startX: Int,
        y: Int,
        destination: ShortArray,
        destinationOffset: Int,
        length: Int
    ) {
        val checkedDestination = requireNotNullCompat(destination, "destination")
        checkFromIndexSize(destinationOffset, length, checkedDestination.size)
        if (length == 0) {
            return
        }

        val sourceY = max(0, min(y, height - 1))
        val rowOffset = sourceY * stride
        val firstVisibleIndex = min(length.toLong(), max(0L, -startX.toLong())).toInt()
        val visibleEndIndex = min(
            length.toLong(),
            max(firstVisibleIndex.toLong(), width.toLong() - startX)
        ).toInt()
        if (firstVisibleIndex > 0) {
            fillCompat(
                checkedDestination,
                destinationOffset,
                destinationOffset + firstVisibleIndex,
                samples[rowOffset]
            )
        }
        val visibleLength = visibleEndIndex - firstVisibleIndex
        if (visibleLength > 0) {
            arraycopy(
                samples,
                rowOffset + startX + firstVisibleIndex,
                checkedDestination,
                destinationOffset + firstVisibleIndex,
                visibleLength
            )
        }
        if (visibleEndIndex < length) {
            fillCompat(
                checkedDestination,
                destinationOffset + visibleEndIndex,
                destinationOffset + length,
                samples[rowOffset + width - 1]
            )
        }
    }

    class State(
        val width: Int,
        val height: Int,
        val stride: Int,
        val storageHeight: Int,
        val samples: ShortArray
    )

    companion object {
        fun fromOwnedSamples(
            width: Int,
            height: Int,
            stride: Int,
            samples: ShortArray
        ): PaddedPlane {
            return PaddedPlane(
                create(width, height, stride, requireNotNullCompat(samples, "samples"))
            )
        }

        fun immutableSamples(samples: ShortArray): ShortArray = samples.copyOf()

        private fun create(width: Int, height: Int, stride: Int, samples: ShortArray): State {
            require(width > 0) { "width <= 0: $width" }
            require(height > 0) { "height <= 0: $height" }
            require(stride >= width) { "stride < width: $stride" }
            val requiredLength = stride.toLong() * height
            require(requiredLength <= Int.MAX_VALUE) { "plane storage is too large" }
            require(!(samples.size < requiredLength.toInt() || samples.size % stride != 0)) {
                "samples length does not contain complete stored rows"
            }
            return State(width, height, stride, samples.size / stride, samples)
        }
    }
}
