// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable metadata for an AVIF animated image sequence. */
class AvifSequenceInfo(
    frameCount: Int,
    mediaTimescale: Int,
    mediaDuration: Long,
    repetitionCount: Int,
    frameDurations: IntArray
) {
    /** The number of frames advertised by the container. */
    private val frameCount: Int

    /** The media timescale for frame durations. */
    private val mediaTimescale: Int

    /** The total media duration in media timescale units. */
    private val mediaDuration: Long

    /** The sequence repetition count. */
    private val repetitionCount: Int

    /** Per-frame durations in media timescale units. */
    private val frameDurations: IntArray

    init {
        require(frameCount > 0) { "frameCount <= 0: $frameCount" }
        require(mediaTimescale >= 0) { "mediaTimescale < 0: $mediaTimescale" }
        require(mediaDuration >= 0) { "mediaDuration < 0: $mediaDuration" }
        require(!(repetitionCount < 0 && repetitionCount != REPETITION_COUNT_UNKNOWN && repetitionCount != REPETITION_COUNT_INFINITE)) { "Invalid repetition count: $repetitionCount" }

        val checkedFrameDurations = frameDurations.copyOf()
        require(!(checkedFrameDurations.size != 0 && checkedFrameDurations.size != frameCount)) { "frameDurations length must match frameCount: " + checkedFrameDurations.size }
        for (duration in checkedFrameDurations) {
            require(duration >= 0) { "frameDurations contains a negative duration: $duration" }
        }

        this.frameCount = frameCount
        this.mediaTimescale = mediaTimescale
        this.mediaDuration = mediaDuration
        this.repetitionCount = repetitionCount
        this.frameDurations = checkedFrameDurations
    }

    /** Returns the number of frames advertised by the container.
     * 
     * @return the number of frames advertised by the container
     */
    fun frameCount(): Int {
        return frameCount
    }

    /** Returns the media timescale for frame durations.
     * 
     * A value of zero means the container did not expose sequence timing.
     * 
     * @return the media timescale, or zero when absent
     */
    fun mediaTimescale(): Int {
        return mediaTimescale
    }

    /** Returns the total media duration.
     * 
     * The value is expressed in `mediaTimescale()` units. A value of zero means the container did
     * not expose sequence timing.
     * 
     * @return the total media duration, or zero when absent
     */
    fun mediaDuration(): Long {
        return mediaDuration
    }

    /** Returns the animated-sequence repetition count.
     * 
     * A non-negative value is the number of repetitions after the first playback. Zero means the
     * sequence should play once. [#REPETITION_COUNT_UNKNOWN] means the container did not expose
     * an edit list, and [#REPETITION_COUNT_INFINITE] means the sequence
     * repeats indefinitely.
     * 
     * @return the repetition count, [#REPETITION_COUNT_UNKNOWN], or [#REPETITION_COUNT_INFINITE]
     */
    fun repetitionCount(): Int {
        return repetitionCount
    }

    /** Returns per-frame durations for animated sequences.
     * 
     * Values are expressed in `mediaTimescale()` units. Inputs without timing metadata return an
     * empty array.
     * 
     * @return per-frame durations in media timescale units
     */
    fun frameDurations(): IntArray {
        return frameDurations.copyOf()
    }

    companion object {
        /** Repetition count used when an animated sequence has no edit list. */
        const val REPETITION_COUNT_UNKNOWN: Int = -1

        /** Repetition count used when an animated sequence repeats indefinitely. */
        const val REPETITION_COUNT_INFINITE: Int = -2
    }
}
