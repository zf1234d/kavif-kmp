// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult
import org.glavo.avif.internal.av1.decode.RestorationUnitMap
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.model.FrameHeader


/** Runs the decoder postfilter stages in AV1 presentation order.
 * 
 * Stored reference surfaces are defined as post-filter, pre-grain snapshots. This class enforces
 * that ordering regardless of whether a particular stage currently changes samples. */
object FramePostprocessor {
    /** Runs postfiltering on one reconstructed frame.
     * 
     * @param decodedPlanes the reconstructed planes to post-process
     * @param frameHeader the normalized frame header that owns the planes
     * @param syntaxDecodeResult the decoded frame syntax that carries block-level postfilter state, or `null`
     * @return the post-filter, pre-grain decoded planes
     */
    /** Runs postfiltering on one reconstructed frame.
     * 
     * @param decodedPlanes the reconstructed planes to post-process
     * @param frameHeader the normalized frame header that owns the planes
     * @return the post-filter, pre-grain decoded planes
     */
    fun postprocess(
        decodedPlanes: DecodedSurface,
        frameHeader: FrameHeader,
        syntaxDecodeResult: FrameSyntaxDecodeResult? = null
    ): DecodedSurface {
        return finish(prepare(decodedPlanes, frameHeader, syntaxDecodeResult))
    }

    /** Extracts syntax-dependent state needed by the postfilter stages.
     * 
     * The returned object retains only compact loop-filter, CDEF, and restoration state. It does
     * not retain `syntaxDecodeResult`, so callers may release a large frame syntax tree before
     * [finish] allocates any destination planes.
     * 
     * @param decodedPlanes the reconstructed planes to post-process
     * @param frameHeader the normalized frame header that owns the planes
     * @param syntaxDecodeResult the decoded frame syntax that carries block-level postfilter state, or `null`
     * @return the prepared postfilter operation
     */
    fun prepare(
        decodedPlanes: DecodedSurface,
        frameHeader: FrameHeader,
        syntaxDecodeResult: FrameSyntaxDecodeResult?
    ): PreparedFrame {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val checkedFrameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        val preparedLoopFilter = LoopFilterApplier.prepare(
            checkedDecodedPlanes,
            checkedFrameHeader,
            syntaxDecodeResult
        )
        val preparedCdef = CdefApplier.prepare(
            checkedDecodedPlanes,
            checkedFrameHeader.cdef(),
            syntaxDecodeResult
        )
        var restorationUnitMap: RestorationUnitMap? = null
        if (RestorationApplier.hasActiveRestoration(
                checkedFrameHeader.restoration(),
                checkedDecodedPlanes.hasChroma()
            )
        ) {
            checkNotNull(syntaxDecodeResult) { "Active AV1 loop restoration requires decoded restoration unit syntax" }
            restorationUnitMap = syntaxDecodeResult.restorationUnitMap()
        }
        return PreparedFrame(
            checkedDecodedPlanes,
            checkedFrameHeader,
            preparedLoopFilter,
            preparedCdef,
            restorationUnitMap
        )
    }

    /** Runs loop filtering, CDEF, super-resolution, and restoration for one prepared frame.
     * 
     * @param preparedFrame the syntax-independent prepared postfilter operation
     * @return the post-filter, pre-grain decoded planes
     */
    fun finish(preparedFrame: PreparedFrame): DecodedSurface {
        val prepared = requireNotNullCompat(preparedFrame, "preparedFrame")
        val afterLoopFilter = LoopFilterApplier.applyPrepared(
            prepared.reconstructedPlanes,
            prepared.preparedLoopFilter
        )
        val checkedFrameHeader = prepared.frameHeader
        val afterCdef = CdefApplier.applyPrepared(afterLoopFilter, prepared.preparedCdef)
        val afterSuperResolution = SuperResolutionUpscaler.apply(afterCdef, checkedFrameHeader)
        var restorationBoundary = afterSuperResolution
        if (RestorationApplier.hasActiveRestoration(
                checkedFrameHeader.restoration(),
                afterLoopFilter.hasChroma()
            ) && afterLoopFilter != afterCdef
        ) {
            restorationBoundary = SuperResolutionUpscaler.apply(afterLoopFilter, checkedFrameHeader)
        }
        return RestorationApplier.applyPrepared(
            afterSuperResolution,
            restorationBoundary,
            checkedFrameHeader.restoration(),
            prepared.restorationUnitMap
        )
    }

    /** Syntax-independent state needed to finish postfiltering one decoded frame.
     * 
     * Instances are created by [prepare] and
     * deliberately do not retain the complete block syntax tree. */
    class PreparedFrame(
        reconstructedPlanes: DecodedSurface,
        frameHeader: FrameHeader,
        preparedLoopFilter: LoopFilterApplier.PreparedApplication,
        preparedCdef: CdefApplier.PreparedApplication,
        /** The compact restoration-unit state, or `null` when restoration is inactive. */
        val restorationUnitMap: RestorationUnitMap?
    ) {
        /** The reconstructed surface before pixel-domain postfiltering. */
        val reconstructedPlanes: DecodedSurface

        /** The normalized frame header. */
        val frameHeader: FrameHeader

        /** The compact prepared loop-filter state. */
        val preparedLoopFilter: LoopFilterApplier.PreparedApplication

        /** The compact prepared CDEF state. */
        val preparedCdef: CdefApplier.PreparedApplication

        init {
            this.reconstructedPlanes =
                requireNotNullCompat(reconstructedPlanes, "reconstructedPlanes")
            this.frameHeader = requireNotNullCompat(frameHeader, "frameHeader")
            this.preparedLoopFilter = requireNotNullCompat(
                preparedLoopFilter,
                "preparedLoopFilter"
            )
            this.preparedCdef = requireNotNullCompat(
                preparedCdef,
                "preparedCdef"
            )
        }
    }
}
