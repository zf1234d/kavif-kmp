// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult
import org.glavo.avif.internal.av1.decode.ReferenceFrameSyntaxState
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.model.FrameHeader


/** Immutable reference-surface snapshot stored for later frame reuse.
 * 
 * The stored planes represent post-filter, post-super-resolution, pre-grain image state.
 * 
 * @param frameHeader the frame header that owns this reference surface
 * @param frameSyntaxState the compact syntax state associated with the stored surface
 * @param decodedPlanes the stored post-filter, post-super-resolution, pre-grain decoded planes
 */
data class ReferenceSurfaceSnapshot(
    val frameHeader: FrameHeader,
    val frameSyntaxState: ReferenceFrameSyntaxState,
    val decodedPlanes: DecodedSurface
) {
    /** Creates one immutable reference-surface snapshot.
     * 
     * @param frameHeader the frame header that owns this reference surface
     * @param frameSyntaxDecodeResult the complete structural decode result to compact for reference use
     * @param decodedPlanes the stored post-filter, post-super-resolution, pre-grain decoded planes
     */
    constructor(
        frameHeader: FrameHeader,
        frameSyntaxDecodeResult: FrameSyntaxDecodeResult,
        decodedPlanes: DecodedSurface
    ) : this(
        frameHeader,
        ReferenceFrameSyntaxState.Companion.from(frameSyntaxDecodeResult),
        decodedPlanes
    )

    init {
        requireNotNullCompat(frameHeader, "frameHeader")
        requireNotNullCompat(frameSyntaxState, "frameSyntaxState")
        requireNotNullCompat(decodedPlanes, "decodedPlanes")
        require(frameSyntaxState.frameHeader() == frameHeader) { "frameHeader does not match the compact syntax-state header" }
    }
}
