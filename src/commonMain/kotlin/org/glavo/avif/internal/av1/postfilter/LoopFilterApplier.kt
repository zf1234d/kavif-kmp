// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader.BlockHeader
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader.PartitionNode
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.FrameHeader.LoopFilterInfo
import org.glavo.avif.internal.av1.model.FrameHeader.SegmentData
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode
import org.glavo.avif.internal.av1.model.TransformSize
import org.glavo.avif.internal.av1.model.TransformUnit


/** Applies the loop-filter stage of the AV1 postfilter pipeline.
 * 
 * Inactive loop filtering preserves samples exactly. Active loop filtering uses decoded block and
 * transform boundaries to run the deblocking sample filters before CDEF sees the frame. */
object LoopFilterApplier {
    /** The number of luma samples represented by one AV1 mode-info unit. */
    private const val MI_SIZE = 4

    /** The maximum AV1 loop-filter level. */
    private const val MAX_LOOP_FILTER_LEVEL = 63

    /** Packed filter-mask bit indicating high edge variance. */
    private const val FILTER_MASK_HIGH_EDGE_VARIANCE = 1

    /** Packed filter-mask bit indicating that filtering should be applied. */
    private const val FILTER_MASK_FILTER = 1 shl 1

    /** Packed filter-mask bit indicating a flat immediate neighborhood. */
    private const val FILTER_MASK_FLAT = 1 shl 2

    /** Packed filter-mask bit indicating a flat extended neighborhood. */
    private const val FILTER_MASK_FLAT2 = 1 shl 3

    /** Bit mask for one effective loop-filter level in compact block state. */
    private const val FILTER_STATE_LEVEL_MASK = 0x3F

    /** Bit shift for the second effective loop-filter level in compact block state. */
    private const val FILTER_STATE_SECOND_LEVEL_SHIFT = 6

    /** Compact block-state bit indicating a skipped inter block. */
    private const val FILTER_STATE_SKIPPED_INTER = 1 shl 12

    /** Boundary-map bit indicating that one plane covers the indexed luma-grid cell. */
    private const val BOUNDARY_STATE_COVERED = 1

    /** Boundary-map bit indicating the left edge of one decoded block. */
    private const val BOUNDARY_STATE_BLOCK_LEFT = 1 shl 1

    /** Boundary-map bit indicating the top edge of one decoded block. */
    private const val BOUNDARY_STATE_BLOCK_TOP = 1 shl 2

    /** Boundary-map bit indicating the left edge of one transform unit. */
    private const val BOUNDARY_STATE_TRANSFORM_LEFT = 1 shl 3

    /** Boundary-map bit indicating the top edge of one transform unit. */
    private const val BOUNDARY_STATE_TRANSFORM_TOP = 1 shl 4

    /** Transform-size constants indexed by their declaration ordinal. */
    private val TRANSFORM_SIZES: Array<TransformSize> =
        TransformSize.entries.toTypedArray()

    /** Applies loop filtering to one reconstructed frame using decoded block syntax.
     * 
     * @param decodedPlanes the reconstructed planes to post-process
     * @param frameHeader the normalized frame header that owns the planes
     * @param syntaxDecodeResult the decoded frame syntax that carries block and transform edges
     * @return the post-loop-filter planes
     */
    fun apply(
        decodedPlanes: DecodedSurface,
        frameHeader: FrameHeader,
        syntaxDecodeResult: FrameSyntaxDecodeResult?
    ): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        return applyPrepared(
            checkedDecodedPlanes,
            prepare(
                checkedDecodedPlanes,
                requireNotNullCompat(frameHeader, "frameHeader"),
                syntaxDecodeResult
            )
        )
    }

    /** Extracts the compact frame state required while applying loop filtering.
     * 
     * The returned state does not retain `syntaxDecodeResult`. Callers may therefore release the
     * complete frame syntax tree before [applyPrepared]
     * allocates destination planes.
     * 
     * @param decodedPlanes the reconstructed planes to post-process
     * @param frameHeader the normalized frame header that owns the planes
     * @param syntaxDecodeResult the decoded frame syntax that carries block and transform edges, or `null`
     * @return the compact prepared loop-filter state
     */
    fun prepare(
        decodedPlanes: DecodedSurface,
        frameHeader: FrameHeader,
        syntaxDecodeResult: FrameSyntaxDecodeResult?
    ): PreparedApplication {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val checkedFrameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        val loopFilter = checkedFrameHeader.loopFilter()
        if (!hasActiveLevels(loopFilter, checkedDecodedPlanes.hasChroma())) {
            return PreparedApplication.Companion.inactive(checkedDecodedPlanes)
        }
        checkNotNull(syntaxDecodeResult) { "Active AV1 loop filtering requires decoded block edge state" }

        var activePlaneMask = 0
        if (loopFilter.levelY(0) != 0 || loopFilter.levelY(1) != 0) {
            activePlaneMask = activePlaneMask or 1
        }
        if (checkedDecodedPlanes.hasChroma() && loopFilter.levelU() != 0) {
            activePlaneMask = activePlaneMask or (1 shl 1)
        }
        if (checkedDecodedPlanes.hasChroma() && loopFilter.levelV() != 0) {
            activePlaneMask = activePlaneMask or (1 shl 2)
        }
        return LoopFilterApplier.PreparedApplication(
            checkedDecodedPlanes,
            loopFilter.sharpness(),
            activePlaneMask,
            LoopFilterBlockMap.Companion.create(
                syntaxDecodeResult,
                checkedDecodedPlanes,
                checkedFrameHeader
            )
        )
    }

    /** Applies one previously prepared loop-filter operation.
     * 
     * @param decodedPlanes the same reconstructed surface used to prepare the operation
     * @param preparedApplication the compact prepared loop-filter state
     * @return the post-loop-filter planes
     */
    fun applyPrepared(
        decodedPlanes: DecodedSurface,
        preparedApplication: PreparedApplication
    ): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val prepared =
            requireNotNullCompat(preparedApplication, "preparedApplication")
        prepared.validateSurface(checkedDecodedPlanes)
        if (!prepared.active()) {
            return checkedDecodedPlanes
        }

        val blockMap: LoopFilterBlockMap? =
            requireNotNullCompat(prepared.blockMap, "blockMap")
        var lumaPlane = checkedDecodedPlanes.lumaPlane()
        if (prepared.isPlaneActive(0)) {
            lumaPlane = LoopFilterApplier.applyPlane(
                lumaPlane,
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.chromaFormat(),
                blockMap!!,
                prepared.sharpness,
                0
            )
        }

        var chromaUPlane = checkedDecodedPlanes.chromaUPlane()
        var chromaVPlane = checkedDecodedPlanes.chromaVPlane()
        if (checkedDecodedPlanes.hasChroma()) {
            if (prepared.isPlaneActive(1)) {
                chromaUPlane = LoopFilterApplier.applyPlane(
                    requireNotNullCompat(
                        chromaUPlane,
                        "decodedPlanes.chromaUPlane()"
                    ),
                    checkedDecodedPlanes.bitDepth(),
                    checkedDecodedPlanes.chromaFormat(),
                    blockMap!!,
                    prepared.sharpness,
                    1
                )
            }
            if (prepared.isPlaneActive(2)) {
                chromaVPlane = LoopFilterApplier.applyPlane(
                    requireNotNullCompat(
                        chromaVPlane,
                        "decodedPlanes.chromaVPlane()"
                    ),
                    checkedDecodedPlanes.bitDepth(),
                    checkedDecodedPlanes.chromaFormat(),
                    blockMap!!,
                    prepared.sharpness,
                    2
                )
            }
        }

        return DecodedSurface(
            checkedDecodedPlanes.bitDepth(),
            checkedDecodedPlanes.chromaFormat(),
            checkedDecodedPlanes.codedWidth(),
            checkedDecodedPlanes.codedHeight(),
            checkedDecodedPlanes.renderWidth(),
            checkedDecodedPlanes.renderHeight(),
            lumaPlane,
            chromaUPlane,
            chromaVPlane
        )
    }

    /** Copies one active plane and applies both loop-filter passes.
     * 
     * @param plane the immutable source plane
     * @param bitDepth the decoded sample bit depth
     * @param chromaFormat the decoded chroma format
     * @param blockMap the decoded block and transform map
     * @param sharpness the frame loop-filter sharpness
     * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
     * @return the filtered immutable plane
     */
    private fun applyPlane(
        plane: PaddedPlane,
        bitDepth: Int,
        chromaFormat: Av1ChromaFormat,
        blockMap: LoopFilterBlockMap,
        sharpness: Int,
        planeIndex: Int
    ): PaddedPlane {
        val destination: PlaneBuffer = PlaneBuffer.Companion.create(plane, bitDepth)
        val subX = if (planeIndex == 0) 0 else chromaSubsamplingX(chromaFormat)
        val subY = if (planeIndex == 0) 0 else chromaSubsamplingY(chromaFormat)
        destination.setProcessingExtent(
            alignedPlaneBoundaryDimension(destination.width(), subX),
            alignedPlaneBoundaryDimension(destination.height(), subY)
        )
        val edgeSamples = IntArray(14)
        applyPass(destination, chromaFormat, blockMap, sharpness, planeIndex, 0, edgeSamples)
        applyPass(destination, chromaFormat, blockMap, sharpness, planeIndex, 1, edgeSamples)
        return destination.toDecodedPlane()
    }

    /** Applies one vertical or horizontal loop-filter pass to one plane.
     * 
     * @param plane the mutable plane buffer
     * @param chromaFormat the decoded chroma format
     * @param blockMap the decoded block and transform map
     * @param sharpness the frame loop-filter sharpness
     * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
     * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
     * @param edgeSamples reusable boundary-relative sample storage
     */
    private fun applyPass(
        plane: PlaneBuffer,
        chromaFormat: Av1ChromaFormat,
        blockMap: LoopFilterBlockMap,
        sharpness: Int,
        planeIndex: Int,
        pass: Int,
        edgeSamples: IntArray
    ) {
        val subX = if (planeIndex == 0) 0 else chromaSubsamplingX(chromaFormat)
        val subY = if (planeIndex == 0) 0 else chromaSubsamplingY(chromaFormat)
        val rowStep = max(1, 1 shl subY)
        val colStep = max(1, 1 shl subX)
        if (pass == 0) {
            var row4 = 0
            while (row4 < blockMap.height4()) {
                var col4 = colStep
                while (col4 < blockMap.width4()) {
                    filterEdge(
                        plane,
                        blockMap,
                        sharpness,
                        planeIndex,
                        pass,
                        row4,
                        col4,
                        subX,
                        subY,
                        edgeSamples
                    )
                    col4 += colStep
                }
                row4 += rowStep
            }
        } else {
            var row4 = rowStep
            while (row4 < blockMap.height4()) {
                var col4 = 0
                while (col4 < blockMap.width4()) {
                    filterEdge(
                        plane,
                        blockMap,
                        sharpness,
                        planeIndex,
                        pass,
                        row4,
                        col4,
                        subX,
                        subY,
                        edgeSamples
                    )
                    col4 += colStep
                }
                row4 += rowStep
            }
        }
    }

    /** Applies loop filtering along one 4x4-grid edge when the decoded syntax permits it.
     * 
     * @param plane the mutable plane buffer
     * @param blockMap the decoded block and transform map
     * @param sharpness the frame loop-filter sharpness
     * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
     * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
     * @param row4 the luma 4x4 row coordinate of the edge
     * @param col4 the luma 4x4 column coordinate of the edge
     * @param subX the plane horizontal subsampling shift
     * @param subY the plane vertical subsampling shift
     * @param edgeSamples reusable boundary-relative sample storage
     */
    private fun filterEdge(
        plane: PlaneBuffer,
        blockMap: LoopFilterBlockMap,
        sharpness: Int,
        planeIndex: Int,
        pass: Int,
        row4: Int,
        col4: Int,
        subX: Int,
        subY: Int,
        edgeSamples: IntArray
    ) {
        val prevCol4 = col4 - (if (pass == 0) max(1, 1 shl subX) else 0)
        val prevRow4 = row4 - (if (pass == 1) max(1, 1 shl subY) else 0)
        val currentIndex = blockMap.indexAt(col4, row4)
        val previousIndex = blockMap.indexAt(prevCol4, prevRow4)
        if (currentIndex < 0 || previousIndex < 0) {
            return
        }

        if (!blockMap.isCoveredAt(currentIndex, planeIndex)
            || !blockMap.isCoveredAt(previousIndex, planeIndex)
        ) {
            return
        }

        val blockEdge = blockMap.isBlockBoundaryAt(currentIndex, planeIndex, pass)
        val transformEdge = blockMap.isTransformBoundaryAt(currentIndex, planeIndex, pass)
        if (!blockEdge && !transformEdge) {
            return
        }
        if (!blockEdge && blockMap.isSkippedInterAt(currentIndex, planeIndex)
            && blockMap.isSkippedInterAt(previousIndex, planeIndex)
        ) {
            return
        }

        val x = (col4 * MI_SIZE) shr subX
        val y = (row4 * MI_SIZE) shr subY
        if (pass == 0) {
            if (x <= 0 || x >= plane.processingWidth() || y < 0 || y >= plane.processingHeight()) {
                return
            }
        } else {
            if (y <= 0 || y >= plane.processingHeight() || x < 0 || x >= plane.processingWidth()) {
                return
            }
        }

        val dx = if (pass == 0) 1 else 0
        val dy = if (pass == 0) 0 else 1
        val filterSize = filterSize(
            blockMap.transformSizeAt(currentIndex, planeIndex),
            blockMap.transformSizeAt(previousIndex, planeIndex),
            planeIndex,
            pass
        )
        val level = blockMap.filterLevelAt(currentIndex, planeIndex, pass)
        var strength = filterStrength(level, sharpness)
        if (strengthLevel(strength) == 0) {
            strength = filterStrength(
                blockMap.filterLevelAt(previousIndex, planeIndex, pass),
                sharpness
            )
        }
        if (strengthLevel(strength) == 0) {
            return
        }

        val samples = min(
            MI_SIZE,
            if (pass == 0) plane.processingHeight() - y else plane.processingWidth() - x
        )
        for (i in 0..<samples) {
            val sampleX = x + (if (pass == 0) 0 else i)
            val sampleY = y + (if (pass == 0) i else 0)
            applySampleFilter(plane, sampleX, sampleY, dx, dy, filterSize, strength, edgeSamples)
        }
    }

    /** Returns whether one decoded block skips transforms while using inter prediction.
     * 
     * @param header the decoded block header
     * @return whether the block is a skipped inter block
     */
    private fun isSkippedInter(header: BlockHeader): Boolean {
        return header.skip() && !header.intra() && !header.useIntrabc()
    }

    /** Packs two effective filter levels and the skipped-inter flag into one compact cell value.
     * 
     * @param firstLevel the first effective filter level
     * @param secondLevel the second effective filter level
     * @param skippedInter whether the owning block is a skipped inter block
     * @return the packed compact cell value
     */
    private fun packFilterState(firstLevel: Int, secondLevel: Int, skippedInter: Boolean): Short {
        var state = firstLevel or (secondLevel shl FILTER_STATE_SECOND_LEVEL_SHIFT)
        if (skippedInter) {
            state = state or FILTER_STATE_SKIPPED_INTER
        }
        return state.toShort()
    }

    /** Applies one sample filter at a block edge.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param filterSize the selected maximum filter size in samples
     * @param strength the derived filter strength parameters
     * @param edgeSamples reusable boundary-relative sample storage
     */
    private fun applySampleFilter(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        filterSize: Int,
        strength: Int,
        edgeSamples: IntArray
    ) {
        val mask = filterMask(plane, x, y, dx, dy, filterSize, strength, edgeSamples)
        if ((mask and FILTER_MASK_FILTER) == 0) {
            return
        }
        if (filterSize == 4 || (mask and FILTER_MASK_FLAT) == 0) {
            narrowFilter(
                plane,
                x,
                y,
                dx,
                dy,
                (mask and FILTER_MASK_HIGH_EDGE_VARIANCE) != 0,
                edgeSamples
            )
        } else if (filterSize == 6) {
            wideFilterSix(plane, x, y, dx, dy, edgeSamples)
        } else if (filterSize == 8 || (mask and FILTER_MASK_FLAT2) == 0) {
            wideFilterEight(plane, x, y, dx, dy, edgeSamples)
        } else {
            wideFilterSixteen(plane, x, y, dx, dy, edgeSamples)
        }
    }

    /** Computes the filter masks for one boundary sample.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param filterSize the selected maximum filter size
     * @param strength the derived filter strength parameters
     * @param edgeSamples reusable boundary-relative sample storage
     * @return the filter masks for one boundary sample
     */
    private fun filterMask(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        filterSize: Int,
        strength: Int,
        edgeSamples: IntArray
    ): Int {
        val firstOffset = if (filterSize == 4) -2 else if (filterSize == 6) -3 else -4
        val lastOffset = if (filterSize == 4) 1 else if (filterSize == 6) 2 else 3
        loadEdgeSamples(plane, x, y, dx, dy, firstOffset, lastOffset, edgeSamples)
        val bitDepthShift = plane.bitDepth() - 8
        val thresholdBd = 1 shl bitDepthShift
        val limitBd = strengthLimit(strength) shl bitDepthShift
        val blimitBd = strengthBoundaryLimit(strength) shl bitDepthShift
        val threshBd = strengthThreshold(strength) shl bitDepthShift
        val p0 = edgeSamples[6]
        val p1 = edgeSamples[5]
        val q0 = edgeSamples[7]
        val q1 = edgeSamples[8]

        val highEdgeVariance = abs(p1 - p0) > threshBd || abs(q1 - q0) > threshBd
        var filtered =
            abs(p1 - p0) <= limitBd && abs(q1 - q0) <= limitBd && abs(p0 - q0) * 2 + (abs(p1 - q1) shr 1) <= blimitBd
        var flat = false
        var flat2 = false
        if (filterSize > 4) {
            val p2 = edgeSamples[4]
            val q2 = edgeSamples[9]
            filtered = filtered
                    && abs(p2 - p1) <= limitBd && abs(q2 - q1) <= limitBd
            flat =
                abs(p2 - p0) <= thresholdBd && abs(p1 - p0) <= thresholdBd && abs(q1 - q0) <= thresholdBd && abs(
                    q2 - q0
                ) <= thresholdBd
            if (filterSize > 6) {
                val p3 = edgeSamples[3]
                val q3 = edgeSamples[10]
                filtered = filtered
                        && abs(p3 - p2) <= limitBd && abs(q3 - q2) <= limitBd
                flat = flat
                        && abs(p3 - p0) <= thresholdBd && abs(q3 - q0) <= thresholdBd
            }
        }
        if (filterSize >= 16 && flat) {
            loadEdgeSamples(plane, x, y, dx, dy, -7, -5, edgeSamples)
            loadEdgeSamples(plane, x, y, dx, dy, 4, 6, edgeSamples)
            flat2 =
                abs(edgeSamples[0] - p0) <= thresholdBd && abs(edgeSamples[13] - q0) <= thresholdBd && abs(
                    edgeSamples[1] - p0
                ) <= thresholdBd && abs(edgeSamples[12] - q0) <= thresholdBd && abs(edgeSamples[2] - p0) <= thresholdBd && abs(
                    edgeSamples[11] - q0
                ) <= thresholdBd
        }
        return ((if (highEdgeVariance) FILTER_MASK_HIGH_EDGE_VARIANCE else 0)
                or (if (filtered) FILTER_MASK_FILTER else 0)
                or (if (flat) FILTER_MASK_FLAT else 0)
                or (if (flat2) FILTER_MASK_FLAT2 else 0))
    }

    /** Applies the AV1 narrow loop filter to one boundary sample.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param highEdgeVariance whether high edge variance was detected
     * @param edgeSamples boundary-relative samples loaded while computing the filter mask
     */
    private fun narrowFilter(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        highEdgeVariance: Boolean,
        edgeSamples: IntArray
    ) {
        val bitDepth = plane.bitDepth()
        val signedLimit = 1 shl (bitDepth - 1)
        val offset = 0x80 shl (bitDepth - 8)
        val qs0 = edgeSamples[7] - offset
        val qs1 = edgeSamples[8] - offset
        val ps0 = edgeSamples[6] - offset
        val ps1 = edgeSamples[5] - offset
        var filter = if (highEdgeVariance) filter4Clamp(ps1 - qs1, signedLimit) else 0
        filter = filter4Clamp(filter + 3 * (qs0 - ps0), signedLimit)
        val filter1 = filter4Clamp(filter + 4, signedLimit) shr 3
        val filter2 = filter4Clamp(filter + 3, signedLimit) shr 3
        setSampleRelative(plane, x, y, dx, dy, 0, filter4Clamp(qs0 - filter1, signedLimit) + offset)
        setSampleRelative(
            plane,
            x,
            y,
            dx,
            dy,
            -1,
            filter4Clamp(ps0 + filter2, signedLimit) + offset
        )
        if (!highEdgeVariance) {
            val secondaryFilter = round2(filter1, 1)
            setSampleRelative(
                plane,
                x,
                y,
                dx,
                dy,
                1,
                filter4Clamp(qs1 - secondaryFilter, signedLimit) + offset
            )
            setSampleRelative(
                plane,
                x,
                y,
                dx,
                dy,
                -2,
                filter4Clamp(ps1 + secondaryFilter, signedLimit) + offset
            )
        }
    }

    /** Applies the AV1 6-tap wide loop filter to one boundary sample.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param edgeSamples boundary-relative samples loaded while computing the filter mask
     */
    private fun wideFilterSix(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        edgeSamples: IntArray
    ) {
        val p2 = edgeSamples[4]
        val p1 = edgeSamples[5]
        val p0 = edgeSamples[6]
        val q0 = edgeSamples[7]
        val q1 = edgeSamples[8]
        val q2 = edgeSamples[9]
        setSampleRelative(plane, x, y, dx, dy, -2, (p2 + 2 * p2 + 2 * p1 + 2 * p0 + q0 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, -1, (p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, 0, (p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, 1, (p0 + 2 * q0 + 2 * q1 + 2 * q2 + q2 + 4) shr 3)
    }

    /** Applies the AV1 8-tap wide loop filter to one boundary sample.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param edgeSamples boundary-relative samples loaded while computing the filter mask
     */
    private fun wideFilterEight(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        edgeSamples: IntArray
    ) {
        val p3 = edgeSamples[3]
        val p2 = edgeSamples[4]
        val p1 = edgeSamples[5]
        val p0 = edgeSamples[6]
        val q0 = edgeSamples[7]
        val q1 = edgeSamples[8]
        val q2 = edgeSamples[9]
        val q3 = edgeSamples[10]
        setSampleRelative(plane, x, y, dx, dy, -3, (p3 + p3 + p3 + 2 * p2 + p1 + p0 + q0 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, -2, (p3 + p3 + p2 + 2 * p1 + p0 + q0 + q1 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, -1, (p3 + p2 + p1 + 2 * p0 + q0 + q1 + q2 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, 0, (p2 + p1 + p0 + 2 * q0 + q1 + q2 + q3 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, 1, (p1 + p0 + q0 + 2 * q1 + q2 + q3 + q3 + 4) shr 3)
        setSampleRelative(plane, x, y, dx, dy, 2, (p0 + q0 + q1 + 2 * q2 + q3 + q3 + q3 + 4) shr 3)
    }

    /** Applies the AV1 16-tap wide loop filter to one boundary sample.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param edgeSamples boundary-relative samples loaded while computing the filter mask
     */
    private fun wideFilterSixteen(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        edgeSamples: IntArray
    ) {
        val p6 = edgeSamples[0]
        val p5 = edgeSamples[1]
        val p4 = edgeSamples[2]
        val p3 = edgeSamples[3]
        val p2 = edgeSamples[4]
        val p1 = edgeSamples[5]
        val p0 = edgeSamples[6]
        val q0 = edgeSamples[7]
        val q1 = edgeSamples[8]
        val q2 = edgeSamples[9]
        val q3 = edgeSamples[10]
        val q4 = edgeSamples[11]
        val q5 = edgeSamples[12]
        val q6 = edgeSamples[13]
        setSampleRelative(
            plane, x, y, dx, dy, -6,
            (p6 + p6 + p6 + p6 + p6 + p6 * 2 + p5 * 2 + p4 * 2 + p3 + p2 + p1 + p0 + q0 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, -5,
            (p6 + p6 + p6 + p6 + p6 + p5 * 2 + p4 * 2 + p3 * 2 + p2 + p1 + p0 + q0 + q1 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, -4,
            (p6 + p6 + p6 + p6 + p5 + p4 * 2 + p3 * 2 + p2 * 2 + p1 + p0 + q0 + q1 + q2 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, -3,
            (p6 + p6 + p6 + p5 + p4 + p3 * 2 + p2 * 2 + p1 * 2 + p0 + q0 + q1 + q2 + q3 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, -2,
            (p6 + p6 + p5 + p4 + p3 + p2 * 2 + p1 * 2 + p0 * 2 + q0 + q1 + q2 + q3 + q4 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, -1,
            (p6 + p5 + p4 + p3 + p2 + p1 * 2 + p0 * 2 + q0 * 2 + q1 + q2 + q3 + q4 + q5 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, 0,
            (p5 + p4 + p3 + p2 + p1 + p0 * 2 + q0 * 2 + q1 * 2 + q2 + q3 + q4 + q5 + q6 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, 1,
            (p4 + p3 + p2 + p1 + p0 + q0 * 2 + q1 * 2 + q2 * 2 + q3 + q4 + q5 + q6 + q6 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, 2,
            (p3 + p2 + p1 + p0 + q0 + q1 * 2 + q2 * 2 + q3 * 2 + q4 + q5 + q6 + q6 + q6 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, 3,
            (p2 + p1 + p0 + q0 + q1 + q2 * 2 + q3 * 2 + q4 * 2 + q5 + q6 + q6 + q6 + q6 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, 4,
            (p1 + p0 + q0 + q1 + q2 + q3 * 2 + q4 * 2 + q5 * 2 + q6 + q6 + q6 + q6 + q6 + 8) shr 4
        )
        setSampleRelative(
            plane, x, y, dx, dy, 5,
            (p0 + q0 + q1 + q2 + q3 + q4 * 2 + q5 * 2 + q6 * 2 + q6 + q6 + q6 + q6 + q6 + 8) shr 4
        )
    }

    /** Returns the loop-filter level for one block, plane, and pass.
     * 
     * @param frameHeader the normalized frame header that owns the block
     * @param header the decoded block header
     * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
     * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
     * @return the loop-filter level for one block, plane, and pass
     */
    private fun filterLevel(
        frameHeader: FrameHeader,
        header: BlockHeader,
        planeIndex: Int,
        pass: Int
    ): Int {
        val componentIndex = if (planeIndex == 0) pass else planeIndex + 1
        val loopFilter = frameHeader.loopFilter()
        val baseLevel = planeFilterLevel(loopFilter, planeIndex, pass)
        val deltaLf = if (frameHeader.delta().deltaLfMulti())
            header.deltaLfValue(componentIndex)
        else
            header.deltaLfValue(0)
        var level = clamp(baseLevel + deltaLf, 0, MAX_LOOP_FILTER_LEVEL)
        if (frameHeader.segmentation().enabled()) {
            val segment = frameHeader.segmentation().segment(header.segmentId())
            level = clamp(
                level + segmentLoopFilterDelta(segment, componentIndex),
                0,
                MAX_LOOP_FILTER_LEVEL
            )
        }
        if (loopFilter.modeRefDeltaEnabled()) {
            val shift = level shr 5
            val referenceIndex = if (header.intra()) 0 else header.referenceFrame0() + 1
            if (referenceIndex >= 0 && referenceIndex < loopFilter.referenceDeltaCount()) {
                level += loopFilter.referenceDelta(referenceIndex) shl shift
            }
            if (!header.intra() && !header.useIntrabc()) {
                val modeIndex = loopFilterModeIndex(header)
                if (modeIndex >= 0 && modeIndex < loopFilter.modeDeltaCount()) {
                    level += loopFilter.modeDelta(modeIndex) shl shift
                }
            }
            level = clamp(level, 0, MAX_LOOP_FILTER_LEVEL)
        }
        return level
    }

    /** Returns the component-specific frame loop-filter level.
     * 
     * @param loopFilter the normalized loop-filter state
     * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
     * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
     * @return the component-specific frame loop-filter level
     */
    private fun planeFilterLevel(loopFilter: LoopFilterInfo, planeIndex: Int, pass: Int): Int {
        if (planeIndex == 0) {
            return loopFilter.levelY(pass)
        }
        return if (planeIndex == 1) loopFilter.levelU() else loopFilter.levelV()
    }

    /** Returns the segment-level loop-filter delta for one component index.
     * 
     * @param segment the segment feature data
     * @param componentIndex the loop-filter component index
     * @return the segment-level loop-filter delta for one component index
     */
    private fun segmentLoopFilterDelta(segment: SegmentData, componentIndex: Int): Int {
        return when (componentIndex) {
            0 -> segment.deltaLfYVertical()
            1 -> segment.deltaLfYHorizontal()
            2 -> segment.deltaLfU()
            3 -> segment.deltaLfV()
            else -> 0
        }
    }

    /** Returns the two-entry loop-filter mode-delta index for one block.
     * 
     * @param header the decoded block header
     * @return the two-entry loop-filter mode-delta index for one block
     */
    private fun loopFilterModeIndex(header: BlockHeader): Int {
        if (header.intra() || header.useIntrabc()) {
            return 0
        }
        val singleMode = header.singleInterMode()
        if (singleMode != null) {
            return if (singleMode == SingleInterPredictionMode.GLOBALMV) 0 else 1
        }
        val compoundMode = header.compoundInterMode()
        if (compoundMode == null) {
            return 0
        }
        return if (compoundMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV) 0 else 1
    }

    /** Derives AV1 loop-filter strength parameters from one filter level.
     * 
     * @param level the effective loop-filter level
     * @param sharpness the frame loop-filter sharpness
     * @return AV1 loop-filter strength parameters
     */
    private fun filterStrength(level: Int, sharpness: Int): Int {
        if (level <= 0) {
            return 0
        }
        val shift = if (sharpness > 4) 2 else (if (sharpness > 0) 1 else 0)
        var limit = level shr shift
        if (sharpness > 0) {
            limit = clamp(limit, 1, 9 - sharpness)
        } else {
            limit = max(1, limit)
        }
        return level or (limit shl 6) or ((2 * (level + 2) + limit) shl 12) or ((level shr 4) shl 20)
    }

    /** Returns the effective level from packed loop-filter strength parameters.
     * 
     * @param strength the packed strength parameters
     * @return the effective loop-filter level
     */
    private fun strengthLevel(strength: Int): Int {
        return strength and 0x3F
    }

    /** Returns the mask limit from packed loop-filter strength parameters.
     * 
     * @param strength the packed strength parameters
     * @return the filter mask limit
     */
    private fun strengthLimit(strength: Int): Int {
        return (strength ushr 6) and 0x3F
    }

    /** Returns the boundary limit from packed loop-filter strength parameters.
     * 
     * @param strength the packed strength parameters
     * @return the boundary limit
     */
    private fun strengthBoundaryLimit(strength: Int): Int {
        return (strength ushr 12) and 0xFF
    }

    /** Returns the high-edge-variance threshold from packed loop-filter strength parameters.
     * 
     * @param strength the packed strength parameters
     * @return the high-edge-variance threshold
     */
    private fun strengthThreshold(strength: Int): Int {
        return (strength ushr 20) and 0x03
    }

    /** Returns the maximum filter size allowed by the transform sizes across one edge.
     * 
     * @param txSize the current transform size
     * @param previousTxSize the transform size on the other side of the edge
     * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
     * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
     * @return the maximum filter size allowed by the transform sizes across one edge
     */
    private fun filterSize(
        txSize: TransformSize,
        previousTxSize: TransformSize,
        planeIndex: Int,
        pass: Int
    ): Int {
        val baseSize = if (pass == 0) min(
            txSize.widthPixels(),
            previousTxSize.widthPixels()
        ) else min(txSize.heightPixels(), previousTxSize.heightPixels())
        if (planeIndex != 0) {
            return if (baseSize >= 8) 6 else 4
        }
        if (baseSize >= 16) {
            return 16
        }
        if (baseSize >= 8) {
            return 8
        }
        return 4
    }

    /** Returns whether any loop-filter level can affect the decoded frame.
     * 
     * @param loopFilter the normalized loop-filter state
     * @param hasChroma whether the decoded frame has chroma planes
     * @return whether any loop-filter level can affect the decoded frame
     */
    private fun hasActiveLevels(loopFilter: LoopFilterInfo, hasChroma: Boolean): Boolean {
        val levelY = loopFilter.levelY()
        return (levelY.size > 0 && levelY[0] != 0)
                || (levelY.size > 1 && levelY[1] != 0)
                || (hasChroma && (loopFilter.levelU() != 0 || loopFilter.levelV() != 0))
    }

    /** Loads a contiguous range of boundary-relative samples.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param firstOffset the first signed boundary-relative sample offset
     * @param lastOffset the last signed boundary-relative sample offset
     * @param edgeSamples destination storage indexed by `offset + 7`
     */
    private fun loadEdgeSamples(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        firstOffset: Int,
        lastOffset: Int,
        edgeSamples: IntArray
    ) {
        plane.loadRelativeSamples(x, y, dx, dy, firstOffset, lastOffset, edgeSamples)
    }

    /** Stores one sample at a boundary-relative offset.
     * 
     * @param plane the mutable plane buffer
     * @param x the first sample on the right or lower side of the boundary
     * @param y the first sample on the right or lower side of the boundary
     * @param dx the horizontal offset across the boundary
     * @param dy the vertical offset across the boundary
     * @param offset the signed boundary-relative sample offset
     * @param value the replacement sample value
     */
    private fun setSampleRelative(
        plane: PlaneBuffer,
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        offset: Int,
        value: Int
    ) {
        val sampleX = x + dx * offset
        val sampleY = y + dy * offset
        if (plane.contains(sampleX, sampleY)) {
            plane.setSample(sampleX, sampleY, value)
        }
    }

    /** Returns the chroma horizontal subsampling shift for one chroma format.
     * 
     * @param chromaFormat the decoded chroma format
     * @return the chroma horizontal subsampling shift for one chroma format
     */
    private fun chromaSubsamplingX(chromaFormat: Av1ChromaFormat): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
        }
    }

    /** Returns the chroma vertical subsampling shift for one chroma format.
     * 
     * @param chromaFormat the decoded chroma format
     * @return the chroma vertical subsampling shift for one chroma format
     */
    private fun chromaSubsamplingY(chromaFormat: Av1ChromaFormat): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420 -> 1
        }
    }

    /** Returns the padded plane boundary available to loop-filter taps along one axis.
     * 
     * @param dimension the visible plane dimension in samples
     * @param subsampling the plane subsampling shift for this axis
     * @return the dimension rounded up to the AV1 eight-luma-sample boundary
     */
    private fun alignedPlaneBoundaryDimension(dimension: Int, subsampling: Int): Int {
        val alignment = 8 shr subsampling
        return (dimension + alignment - 1) and -alignment
    }

    /** Clips one integer into inclusive bounds.
     * 
     * @param value the input value
     * @param minimum the inclusive lower bound
     * @param maximum the inclusive upper bound
     * @return the clipped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(value, maximum))
    }

    /** Rounds one integer right shift by adding half the divisor.
     * 
     * @param value the input value
     * @param bits the number of low bits to discard
     * @return the rounded shifted value
     */
    private fun round2(value: Int, bits: Int): Int {
        return (value + (1 shl (bits - 1))) shr bits
    }

    /** Clips one signed narrow-filter intermediate to the decoded bit-depth range.
     * 
     * @param value the input value
     * @param signedLimit the positive exclusive bound for the signed intermediate
     * @return the clipped narrow-filter intermediate
     */
    private fun filter4Clamp(value: Int, signedLimit: Int): Int {
        return clamp(value, -signedLimit, signedLimit - 1)
    }

    /** Mutable plane storage used by loop filtering. */
    class PlaneBuffer(
        /** The plane width in samples. */
        val width: Int,
        /** The plane height in samples. */
        val height: Int,
        /** The sample stride of one stored row. */
        val stride: Int,
        /** The number of stored rows, including bottom padding. */
        val storageHeight: Int,
        bitDepth: Int,
        samples: ShortArray
    ) {
        /** The horizontal sample extent filtered for the current plane. */
        var processingWidth: Int

        /** The vertical sample extent filtered for the current plane. */
        var processingHeight: Int

        /** The decoded sample bit depth. */
        val bitDepth: Int

        /** The maximum legal sample value for this bit depth. */
        val maxSampleValue: Int

        /** The mutable sample storage in row-major order. */
        val samples: ShortArray

        init {
            this.processingWidth = width
            this.processingHeight = height
            this.bitDepth = bitDepth
            this.maxSampleValue = (1 shl bitDepth) - 1
            this.samples = requireNotNullCompat(samples, "samples")
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

        /** Sets the padded sample extent processed by loop filtering.
         * 
         * @param requestedWidth the requested processing width
         * @param requestedHeight the requested processing height
         */
        fun setProcessingExtent(requestedWidth: Int, requestedHeight: Int) {
            processingWidth = min(stride, requestedWidth)
            processingHeight = min(storageHeight, requestedHeight)
        }

        /** Returns the horizontal sample extent processed by loop filtering.
         * 
         * @return the processing width
         */
        fun processingWidth(): Int {
            return processingWidth
        }

        /** Returns the vertical sample extent processed by loop filtering.
         * 
         * @return the processing height
         */
        fun processingHeight(): Int {
            return processingHeight
        }

        /** Returns the decoded sample bit depth.
         * 
         * @return the decoded sample bit depth
         */
        fun bitDepth(): Int {
            return bitDepth
        }

        /** Returns whether one sample coordinate is inside this plane.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return whether one sample coordinate is inside this plane
         */
        fun contains(x: Int, y: Int): Boolean {
            return x >= 0 && y >= 0 && x < processingWidth && y < processingHeight
        }

        /** Returns one mutable sample.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return one mutable sample
         */
        fun sample(x: Int, y: Int): Int {
            return samples[y * stride + x].toInt() and 0xFFFF
        }

        /** Returns one sample after extending the nearest frame-edge sample beyond the plane.
         * 
         * @param x the sample X coordinate, which may be outside the plane
         * @param y the sample Y coordinate, which may be outside the plane
         * @return the nearest in-plane sample
         */
        fun sampleClamped(x: Int, y: Int): Int {
            return sample(clamp(x, 0, processingWidth - 1), clamp(y, 0, processingHeight - 1))
        }

        /** Loads one contiguous boundary-relative sample range into edge-sample storage.
         * 
         * @param x the boundary origin x coordinate
         * @param y the boundary origin y coordinate
         * @param dx the horizontal offset across the boundary
         * @param dy the vertical offset across the boundary
         * @param firstOffset the first signed boundary-relative offset
         * @param lastOffset the last signed boundary-relative offset
         * @param destination destination storage indexed by `offset + 7`
         */
        fun loadRelativeSamples(
            x: Int,
            y: Int,
            dx: Int,
            dy: Int,
            firstOffset: Int,
            lastOffset: Int,
            destination: IntArray
        ) {
            val firstX = x + dx * firstOffset
            val firstY = y + dy * firstOffset
            val lastX = x + dx * lastOffset
            val lastY = y + dy * lastOffset
            if (contains(firstX, firstY) && contains(lastX, lastY)) {
                var sampleIndex = firstY * stride + firstX
                val sampleStep = dy * stride + dx
                for (offset in firstOffset..lastOffset) {
                    destination[offset + 7] = samples[sampleIndex].toInt() and 0xFFFF
                    sampleIndex += sampleStep
                }
                return
            }

            for (offset in firstOffset..lastOffset) {
                destination[offset + 7] = sampleClamped(x + dx * offset, y + dy * offset)
            }
        }

        /** Stores one sample after clipping it to this plane bit depth.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @param value the replacement sample value
         */
        fun setSample(x: Int, y: Int, value: Int) {
            samples[y * stride + x] = clamp(value, 0, maxSampleValue).toShort()
        }

        /** Returns one immutable decoded-plane snapshot from the current samples.
         * 
         * @return one immutable decoded-plane snapshot from the current samples
         */
        fun toDecodedPlane(): PaddedPlane {
            return PaddedPlane.Companion.fromOwnedSamples(width, height, stride, samples)
        }

        companion object {
            /** Creates a mutable copy of one decoded plane.
             * 
             * @param plane the immutable decoded plane
             * @param bitDepth the decoded sample bit depth
             * @return a mutable copy of one decoded plane
             */
            fun create(plane: PaddedPlane, bitDepth: Int): PlaneBuffer {
                val checkedPlane = requireNotNullCompat(plane, "plane")
                return PlaneBuffer(
                    checkedPlane.width(),
                    checkedPlane.height(),
                    checkedPlane.stride(),
                    checkedPlane.storageHeight(),
                    bitDepth,
                    checkedPlane.samples()
                )
            }
        }
    }

    /** Compact loop-filter state that remains valid after the complete frame syntax tree is released. */
    class PreparedApplication(
        decodedPlanes: DecodedSurface,
        sharpness: Int,
        activePlaneMask: Int,
        blockMap: LoopFilterBlockMap?
    ) {
        /** The expected decoded bit depth. */
        val bitDepth: Int

        /** The expected chroma format. */
        val chromaFormat: Av1ChromaFormat

        /** The expected coded luma width. */
        val codedWidth: Int

        /** The expected coded luma height. */
        val codedHeight: Int

        /** The frame loop-filter sharpness. */
        val sharpness: Int

        /** The bit mask of planes with active frame-level filtering. */
        val activePlaneMask: Int

        /** The compact decoded block map, or `null` when loop filtering is inactive. */
        val blockMap: LoopFilterBlockMap?

        init {
            val checkedDecodedPlanes =
                requireNotNullCompat(decodedPlanes, "decodedPlanes")
            this.bitDepth = checkedDecodedPlanes.bitDepth()
            this.chromaFormat = checkedDecodedPlanes.chromaFormat()
            this.codedWidth = checkedDecodedPlanes.codedWidth()
            this.codedHeight = checkedDecodedPlanes.codedHeight()
            this.sharpness = sharpness
            this.activePlaneMask = activePlaneMask
            this.blockMap = blockMap
        }

        /** Returns whether this operation applies any loop filtering.
         * 
         * @return whether loop filtering is active
         */
        fun active(): Boolean {
            return blockMap != null
        }

        /** Returns whether one image plane has a nonzero frame-level filter.
         * 
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @return whether the selected plane requires loop filtering
         */
        fun isPlaneActive(planeIndex: Int): Boolean {
            return (activePlaneMask and (1 shl checkIndexCompat(planeIndex, 3))) != 0
        }

        /** Verifies that an operation is applied to the surface for which it was prepared.
         * 
         * @param decodedPlanes the candidate reconstructed surface
         */
        fun validateSurface(decodedPlanes: DecodedSurface) {
            val checkedDecodedPlanes =
                requireNotNullCompat(decodedPlanes, "decodedPlanes")
            require(!(checkedDecodedPlanes.bitDepth() != bitDepth || checkedDecodedPlanes.chromaFormat() != chromaFormat || checkedDecodedPlanes.codedWidth() != codedWidth || checkedDecodedPlanes.codedHeight() != codedHeight)) { "Prepared loop-filter state does not match decoded surface" }
        }

        companion object {
            /** Creates an inactive prepared operation for one decoded surface.
             * 
             * @param decodedPlanes the surface for which loop filtering is inactive
             * @return the inactive prepared operation
             */
            fun inactive(decodedPlanes: DecodedSurface): PreparedApplication {
                return PreparedApplication(decodedPlanes, 0, 0, null)
            }
        }
    }

    /** Compact block and transform boundary state indexed in luma 4x4 units.
     * 
     * The map stores only primitive values so it does not retain decoded partition, block, or
     * transform objects after preparation completes. */
    class LoopFilterBlockMap(
        /** The frame width rounded up to 4x4 units. */
        val width4: Int,
        /** The frame height rounded up to 4x4 units. */
        val height4: Int, chromaSubsamplingX: Int, chromaSubsamplingY: Int
    ) {
        /** The packed luma coverage and block/transform boundaries for each 4x4 cell. */
        val lumaBoundaryStates: ByteArray

        /** The luma transform-size ordinal covering each 4x4 cell. */
        val lumaTransformSizeOrdinals: ByteArray

        /** The packed luma filter levels and skipped-inter flag covering each 4x4 cell. */
        val lumaFilterStates: ShortArray

        /** The packed chroma coverage and block/transform boundaries for each luma-grid 4x4 cell. */
        val chromaBoundaryStates: ByteArray

        /** The chroma transform-size ordinal covering each luma-grid 4x4 cell. */
        val chromaTransformSizeOrdinals: ByteArray

        /** The packed U/V filter levels and skipped-inter flag covering each luma-grid 4x4 cell. */
        val chromaFilterStates: ShortArray

        /** The chroma horizontal subsampling shift. */
        val chromaSubsamplingX: Int

        /** The chroma vertical subsampling shift. */
        val chromaSubsamplingY: Int

        init {
            val cellCount = width4 * height4
            this.lumaBoundaryStates = ByteArray(cellCount)
            this.lumaTransformSizeOrdinals = ByteArray(cellCount)
            this.lumaFilterStates = ShortArray(cellCount)
            this.chromaBoundaryStates = ByteArray(cellCount)
            this.chromaTransformSizeOrdinals = ByteArray(cellCount)
            this.chromaFilterStates = ShortArray(cellCount)
            this.chromaSubsamplingX = chromaSubsamplingX
            this.chromaSubsamplingY = chromaSubsamplingY
        }

        /** Returns the frame width rounded up to 4x4 units.
         * 
         * @return the frame width rounded up to 4x4 units
         */
        fun width4(): Int {
            return width4
        }

        /** Returns the frame height rounded up to 4x4 units.
         * 
         * @return the frame height rounded up to 4x4 units
         */
        fun height4(): Int {
            return height4
        }

        /** Returns the flat cell index for one luma-grid coordinate.
         * 
         * @param x4 the luma 4x4 X coordinate
         * @param y4 the luma 4x4 Y coordinate
         * @return the flat cell index, or `-1` when outside the map
         */
        fun indexAt(x4: Int, y4: Int): Int {
            if (x4 < 0 || y4 < 0 || x4 >= width4 || y4 >= height4) {
                return -1
            }
            return y4 * width4 + x4
        }

        /** Returns whether one plane covers the indexed luma-grid cell.
         * 
         * @param index the flat cell index
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @return whether the selected plane covers the cell
         */
        fun isCoveredAt(index: Int, planeIndex: Int): Boolean {
            return (boundaryStateAt(index, planeIndex) and BOUNDARY_STATE_COVERED) != 0
        }

        /** Returns whether the indexed cell begins a plane-specific block along one filter pass.
         * 
         * @param index the flat cell index
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
         * @return whether the cell begins a decoded block along the selected pass
         */
        fun isBlockBoundaryAt(index: Int, planeIndex: Int, pass: Int): Boolean {
            val mask = if (pass == 0) BOUNDARY_STATE_BLOCK_LEFT else BOUNDARY_STATE_BLOCK_TOP
            return (boundaryStateAt(index, planeIndex) and mask) != 0
        }

        /** Returns whether the indexed cell begins a plane-specific transform along one filter pass.
         * 
         * @param index the flat cell index
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
         * @return whether the cell begins a transform unit along the selected pass
         */
        fun isTransformBoundaryAt(index: Int, planeIndex: Int, pass: Int): Boolean {
            val mask =
                if (pass == 0) BOUNDARY_STATE_TRANSFORM_LEFT else BOUNDARY_STATE_TRANSFORM_TOP
            return (boundaryStateAt(index, planeIndex) and mask) != 0
        }

        /** Returns the packed plane-specific boundary state at one cell.
         * 
         * @param index the flat cell index
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @return the packed coverage and boundary state
         */
        fun boundaryStateAt(index: Int, planeIndex: Int): Int {
            return toUnsignedInt((if (planeIndex == 0) lumaBoundaryStates else chromaBoundaryStates)[index])
        }

        /** Returns the plane-specific transform size at one covered cell.
         * 
         * @param index the flat cell index
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @return the transform size covering the cell
         */
        fun transformSizeAt(index: Int, planeIndex: Int): TransformSize {
            val ordinal = toUnsignedInt(
                (if (planeIndex == 0) lumaTransformSizeOrdinals else chromaTransformSizeOrdinals)[index]
            )
            return TRANSFORM_SIZES[ordinal]
        }

        /** Returns one effective plane/pass filter level at one covered cell.
         * 
         * @param index the flat cell index
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @param pass the edge pass, `0` for vertical edges and `1` for horizontal edges
         * @return the effective filter level
         */
        fun filterLevelAt(index: Int, planeIndex: Int, pass: Int): Int {
            val state =
                shortToUnsignedInt((if (planeIndex == 0) lumaFilterStates else chromaFilterStates)[index])
            val levelIndex = if (planeIndex == 0) pass else planeIndex - 1
            return (state shr (levelIndex * FILTER_STATE_SECOND_LEVEL_SHIFT)) and FILTER_STATE_LEVEL_MASK
        }

        /** Returns whether the plane-specific block at one cell is a skipped inter block.
         * 
         * @param index the flat cell index
         * @param planeIndex the plane index, `0` for luma, `1` for U, and `2` for V
         * @return whether the block is a skipped inter block
         */
        fun isSkippedInterAt(index: Int, planeIndex: Int): Boolean {
            val state =
                shortToUnsignedInt((if (planeIndex == 0) lumaFilterStates else chromaFilterStates)[index])
            return (state and FILTER_STATE_SKIPPED_INTER) != 0
        }

        /** Adds one partition node and all descendant leaves to this map.
         * 
         * @param node the decoded partition node
         * @param frameHeader the normalized frame header used to derive effective filter levels
         */
        fun addNode(node: TilePartitionTreeReader.Node, frameHeader: FrameHeader) {
            if (node is TilePartitionTreeReader.LeafNode) {
                addLeaf(node, frameHeader)
                return
            }
            val partitionNode = node as PartitionNode
            for (childIndex in 0..<partitionNode.childCount()) {
                addNode(partitionNode.child(childIndex), frameHeader)
            }
        }

        /** Adds one leaf to every 4x4 cell that it covers.
         * 
         * @param leafNode the decoded partition leaf
         * @param frameHeader the normalized frame header used to derive effective filter levels
         */
        fun addLeaf(leafNode: TilePartitionTreeReader.LeafNode, frameHeader: FrameHeader) {
            val header = leafNode.header
            val startX4 = max(0, header.position().x4)
            val startY4 = max(0, header.position().y4)
            val transformLayout = leafNode.transformLayout
            val endX4 = min(width4, startX4 + transformLayout.visibleWidth4())
            val endY4 = min(height4, startY4 + transformLayout.visibleHeight4())
            val skippedInter = isSkippedInter(header)
            val lumaFilterState = packFilterState(
                filterLevel(frameHeader, header, 0, 0),
                filterLevel(frameHeader, header, 0, 1),
                skippedInter
            )
            for (y4 in startY4..<endY4) {
                for (x4 in startX4..<endX4) {
                    val index = y4 * width4 + x4
                    var boundaryState = BOUNDARY_STATE_COVERED
                    if (x4 == startX4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_BLOCK_LEFT
                    }
                    if (y4 == startY4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_BLOCK_TOP
                    }
                    lumaBoundaryStates[index] =
                        (lumaBoundaryStates[index].toInt() or boundaryState.toByte().toInt()).toByte()
                    lumaFilterStates[index] = lumaFilterState
                }
            }

            if (transformLayout.lumaUnitCount() == 0) {
                fillDefaultLumaTransform(
                    startX4,
                    startY4,
                    endX4,
                    endY4,
                    transformLayout.maxLumaTransformSize()
                )
            } else {
                val firstUnit = transformLayout.lumaUnit(0)
                fillDefaultLumaTransform(
                    startX4,
                    startY4,
                    endX4,
                    endY4,
                    firstUnit.size
                )
                for (unitIndex in 0..<transformLayout.lumaUnitCount()) {
                    val transformUnit = transformLayout.lumaUnit(unitIndex)
                    fillLumaTransform(
                        startX4,
                        startY4,
                        endX4,
                        endY4,
                        transformUnit
                    )
                }
            }
            if (header.hasChroma() && transformLayout.chromaUnitCount() != 0) {
                val chromaFilterState = packFilterState(
                    filterLevel(frameHeader, header, 1, 0),
                    filterLevel(frameHeader, header, 2, 0),
                    skippedInter
                )
                val firstChromaUnit = transformLayout.chromaUnit(0)
                var chromaStartX4 = firstChromaUnit.position.x4
                var chromaStartY4 = firstChromaUnit.position.y4
                var chromaEndX4 = (chromaStartX4
                        + (firstChromaUnit.size.width4() shl chromaSubsamplingX))
                var chromaEndY4 = (chromaStartY4
                        + (firstChromaUnit.size.height4() shl chromaSubsamplingY))
                for (unitIndex in 0..<transformLayout.chromaUnitCount()) {
                    val transformUnit = transformLayout.chromaUnit(unitIndex)
                    chromaStartX4 = min(chromaStartX4, transformUnit.position.x4)
                    chromaStartY4 = min(chromaStartY4, transformUnit.position.y4)
                    chromaEndX4 = max(
                        chromaEndX4,
                        transformUnit.position.x4 + (transformUnit.size.width4() shl chromaSubsamplingX)
                    )
                    chromaEndY4 = max(
                        chromaEndY4,
                        transformUnit.position.y4 + (transformUnit.size.height4() shl chromaSubsamplingY)
                    )
                    fillChromaTransform(
                        chromaFilterState,
                        transformUnit
                    )
                }
                markChromaBlockBoundaries(chromaStartX4, chromaStartY4, chromaEndX4, chromaEndY4)
            }
        }

        /** Fills the luma-grid coverage of one decoded chroma transform unit.
         * 
         * @param filterState the packed U/V filter levels and skipped-inter flag
         * @param transformUnit the decoded chroma transform unit
         */
        fun fillChromaTransform(
            filterState: kotlin.Short,
            transformUnit: TransformUnit
        ) {
            val unitStartX4 = max(0, transformUnit.position.x4)
            val unitStartY4 = max(0, transformUnit.position.y4)
            val unitEndX4 = min(
                width4,
                transformUnit.position.x4 + (transformUnit.size.width4() shl chromaSubsamplingX)
            )
            val unitEndY4 = min(
                height4,
                transformUnit.position.y4 + (transformUnit.size.height4() shl chromaSubsamplingY)
            )
            val transformSizeOrdinal = transformUnit.size.ordinal.toByte()
            for (y4 in unitStartY4..<unitEndY4) {
                for (x4 in unitStartX4..<unitEndX4) {
                    val index = y4 * width4 + x4
                    var boundaryState = BOUNDARY_STATE_COVERED
                    if (x4 == unitStartX4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_TRANSFORM_LEFT
                    }
                    if (y4 == unitStartY4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_TRANSFORM_TOP
                    }
                    chromaBoundaryStates[index] =
                        (chromaBoundaryStates[index].toInt() or boundaryState.toByte().toInt()).toByte()
                    chromaFilterStates[index] = filterState
                    chromaTransformSizeOrdinals[index] = transformSizeOrdinal
                }
            }
        }

        /** Marks the outer left and top boundaries of one rectangular chroma block footprint.
         * 
         * @param startX4 the inclusive chroma footprint start X in luma 4x4 units
         * @param startY4 the inclusive chroma footprint start Y in luma 4x4 units
         * @param endX4 the exclusive chroma footprint end X in luma 4x4 units
         * @param endY4 the exclusive chroma footprint end Y in luma 4x4 units
         */
        fun markChromaBlockBoundaries(startX4: Int, startY4: Int, endX4: Int, endY4: Int) {
            val boundedStartX4 = max(0, startX4)
            val boundedStartY4 = max(0, startY4)
            val boundedEndX4 = min(width4, endX4)
            val boundedEndY4 = min(height4, endY4)
            if (boundedStartX4 >= boundedEndX4 || boundedStartY4 >= boundedEndY4) {
                return
            }
            for (y4 in boundedStartY4..<boundedEndY4) {
                val index = y4 * width4 + boundedStartX4
                if ((chromaBoundaryStates[index].toInt() and BOUNDARY_STATE_COVERED) != 0) {
                    chromaBoundaryStates[index] =
                        (chromaBoundaryStates[index].toInt() or BOUNDARY_STATE_BLOCK_LEFT.toByte().toInt()).toByte()
                }
            }
            val rowStart = boundedStartY4 * width4
            for (x4 in boundedStartX4..<boundedEndX4) {
                val index = rowStart + x4
                if ((chromaBoundaryStates[index].toInt() and BOUNDARY_STATE_COVERED) != 0) {
                    chromaBoundaryStates[index] =
                        (chromaBoundaryStates[index].toInt() or BOUNDARY_STATE_BLOCK_TOP.toByte().toInt()).toByte()
                }
            }
        }

        /** Initializes every cell in one leaf with one default luma transform.
         * 
         * @param startX4 the leaf start X in luma 4x4 units
         * @param startY4 the leaf start Y in luma 4x4 units
         * @param endX4 the exclusive leaf end X in luma 4x4 units
         * @param endY4 the exclusive leaf end Y in luma 4x4 units
         * @param transformSize the default transform size
         */
        fun fillDefaultLumaTransform(
            startX4: Int,
            startY4: Int,
            endX4: Int,
            endY4: Int,
            transformSize: TransformSize
        ) {
            val transformSizeOrdinal = transformSize.ordinal.toByte()
            for (y4 in startY4..<endY4) {
                for (x4 in startX4..<endX4) {
                    val index = y4 * width4 + x4
                    var boundaryState = BOUNDARY_STATE_COVERED
                    if (x4 == startX4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_TRANSFORM_LEFT
                    }
                    if (y4 == startY4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_TRANSFORM_TOP
                    }
                    lumaBoundaryStates[index] =
                        (lumaBoundaryStates[index].toInt() or boundaryState.toByte().toInt()).toByte()
                    lumaTransformSizeOrdinals[index] = transformSizeOrdinal
                }
            }
        }

        /** Fills bounded luma-grid coverage with one decoded transform.
         * 
         * @param startX4 the inclusive coverage bound in luma 4x4 units
         * @param startY4 the inclusive coverage bound in luma 4x4 units
         * @param endX4 the exclusive coverage bound in luma 4x4 units
         * @param endY4 the exclusive coverage bound in luma 4x4 units
         * @param transformUnit the transform unit to fill
         */
        fun fillLumaTransform(
            startX4: Int,
            startY4: Int,
            endX4: Int,
            endY4: Int,
            transformUnit: TransformUnit
        ) {
            val unitStartX4 = max(startX4, transformUnit.position.x4)
            val unitStartY4 = max(startY4, transformUnit.position.y4)
            val unitEndX4 = min(endX4, transformUnit.position.x4 + transformUnit.size.width4())
            val unitEndY4 = min(endY4, transformUnit.position.y4 + transformUnit.size.height4())
            val transformSizeOrdinal = transformUnit.size.ordinal.toByte()
            for (y4 in unitStartY4..<unitEndY4) {
                for (x4 in unitStartX4..<unitEndX4) {
                    val index = y4 * width4 + x4
                    var boundaryState = BOUNDARY_STATE_COVERED
                    if (x4 == unitStartX4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_TRANSFORM_LEFT
                    }
                    if (y4 == unitStartY4) {
                        boundaryState = boundaryState or BOUNDARY_STATE_TRANSFORM_TOP
                    }
                    lumaBoundaryStates[index] =
                        (lumaBoundaryStates[index].toInt() or boundaryState.toByte().toInt()).toByte()
                    lumaTransformSizeOrdinals[index] = transformSizeOrdinal
                }
            }
        }

        companion object {
            /** Creates one compact block and transform lookup map.
             * 
             * @param syntaxDecodeResult the decoded frame syntax
             * @param decodedPlanes the reconstructed planes to post-process
             * @param frameHeader the normalized frame header used to derive effective filter levels
             * @return one compact block and transform lookup map
             */
            fun create(
                syntaxDecodeResult: FrameSyntaxDecodeResult,
                decodedPlanes: DecodedSurface,
                frameHeader: FrameHeader
            ): LoopFilterBlockMap {
                val map = LoopFilterBlockMap(
                    (decodedPlanes.codedWidth() + MI_SIZE - 1) / MI_SIZE,
                    (decodedPlanes.codedHeight() + MI_SIZE - 1) / MI_SIZE,
                    chromaSubsamplingX(decodedPlanes.chromaFormat()),
                    chromaSubsamplingY(decodedPlanes.chromaFormat())
                )
                val frameLocalTileRoots = syntaxDecodeResult.tileRoots()
                for (tileRoots in frameLocalTileRoots) {
                    for (root in tileRoots) {
                        map.addNode(root, frameHeader)
                    }
                }
                return map
            }
        }
    }
}
