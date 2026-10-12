// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.decode.RestorationUnit
import org.glavo.avif.internal.av1.decode.RestorationUnitMap
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationInfo
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationType


/** Applies the loop-restoration stage of the postfilter pipeline.
 * 
 * Inactive restoration preserves samples exactly. Active restoration consumes decoded
 * restoration-unit syntax and applies per-unit Wiener or self-guided filtering. Wiener filtering
 * uses the AV1 two-stage horizontal/vertical rounding model, while self-guided filtering uses the
 * AV1 self-guided A/B projection model. */
internal object RestorationApplier {
    /** The AV1 restoration processing stripe size in luma samples. */
    private const val RESTORATION_PROC_UNIT_SIZE = 64

    /** The AV1 vertical offset between restoration units and processing stripes. */
    private const val RESTORATION_UNIT_OFFSET = 8

    /** The number of rows overwritten above and below an internal processing stripe. */
    private const val RESTORATION_STRIPE_BORDER = 3

    /** The AV1 Wiener coefficient precision. */
    private const val FILTER_BITS = 7

    /** The AV1 Wiener filter tap count. */
    private const val WIENER_TAP_COUNT = 7

    /** The signed source-sample offset of the first Wiener tap. */
    private const val WIENER_TAP_OFFSET = 3

    /** The final self-guided projection rounding shift used by dav1d. */
    private const val SELF_GUIDED_WEIGHT_BITS = 11

    /** The self-guided 3x3 A/B finish rounding shift. */
    private const val SELF_GUIDED_FILTER_3_BITS = 9

    /** The self-guided paired 5x5 A/B finish rounding shift. */
    private const val SELF_GUIDED_FILTER_5_PAIR_BITS = 9

    /** The self-guided single-row 5x5 A/B finish rounding shift. */
    private const val SELF_GUIDED_FILTER_5_SINGLE_BITS = 8

    /** The maximum self-guided variance table index. */
    private const val SELF_GUIDED_MAX_Z = 255

    /** The AV1 self-guided restoration parameter table in `{r0, e0, r1, e1}` order. */
    private val SELF_GUIDED_PARAMS: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(2, 140, 1, 3236),
        intArrayOf(2, 112, 1, 2158),
        intArrayOf(2, 93, 1, 1618),
        intArrayOf(2, 80, 1, 1438),
        intArrayOf(2, 70, 1, 1295),
        intArrayOf(2, 58, 1, 1177),
        intArrayOf(2, 47, 1, 1079),
        intArrayOf(2, 37, 1, 996),
        intArrayOf(2, 30, 1, 925),
        intArrayOf(2, 25, 1, 863),
        intArrayOf(0, -1, 1, 2589),
        intArrayOf(0, -1, 1, 1618),
        intArrayOf(0, -1, 1, 1177),
        intArrayOf(0, -1, 1, 925),
        intArrayOf(2, 56, 0, -1),
        intArrayOf(2, 22, 0, -1)
    )

    /** The dav1d `sgr_x_by_x` reciprocal values indexed by normalized variance. */
    private val SELF_GUIDED_X_BY_X: IntArray = createSelfGuidedXByXTable()

    /** Applies restoration using a compact restoration-unit map extracted from frame syntax.
     * 
     * @param decodedPlanes the decoded planes after CDEF
     * @param boundaryPlanes the decoded planes after loop filtering and before CDEF
     * @param restoration the normalized frame-level restoration state
     * @param unitMap the compact restoration-unit map, or `null` when syntax is unavailable
     * @return the post-restoration planes
     */
    fun applyPrepared(
        decodedPlanes: DecodedSurface,
        boundaryPlanes: DecodedSurface,
        restoration: RestorationInfo,
        unitMap: RestorationUnitMap?
    ): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val checkedBoundaryPlanes =
            requireNotNullCompat(boundaryPlanes, "boundaryPlanes")
        val checkedRestoration = requireNotNullCompat(restoration, "restoration")
        if (!hasActiveRestoration(checkedRestoration, checkedDecodedPlanes.hasChroma())) {
            return checkedDecodedPlanes
        }
        require(
            !(checkedBoundaryPlanes.bitDepth() != checkedDecodedPlanes.bitDepth()
                    || checkedBoundaryPlanes.chromaFormat() != checkedDecodedPlanes.chromaFormat())
        ) { "Boundary planes must match decoded plane format" }
        checkNotNull(unitMap) { "Active AV1 loop restoration requires decoded restoration unit syntax" }

        val workspace = RestorationWorkspace()
        val lumaPlane = applyPlane(
            checkedDecodedPlanes.lumaPlane(),
            checkedBoundaryPlanes.lumaPlane(),
            checkedDecodedPlanes.bitDepth(),
            checkedDecodedPlanes.chromaFormat(),
            checkedRestoration,
            unitMap,
            0,
            workspace
        )

        var chromaUPlane = checkedDecodedPlanes.chromaUPlane()
        var chromaVPlane = checkedDecodedPlanes.chromaVPlane()
        if (checkedDecodedPlanes.hasChroma()) {
            chromaUPlane = applyPlane(
                requireNotNullCompat(chromaUPlane, "chromaUPlane"),
                requireNotNullCompat(
                    checkedBoundaryPlanes.chromaUPlane(),
                    "boundaryChromaUPlane"
                ),
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.chromaFormat(),
                checkedRestoration,
                unitMap,
                1,
                workspace
            )
            chromaVPlane = applyPlane(
                requireNotNullCompat(chromaVPlane, "chromaVPlane"),
                requireNotNullCompat(
                    checkedBoundaryPlanes.chromaVPlane(),
                    "boundaryChromaVPlane"
                ),
                checkedDecodedPlanes.bitDepth(),
                checkedDecodedPlanes.chromaFormat(),
                checkedRestoration,
                unitMap,
                2,
                workspace
            )
        }

        if (lumaPlane == checkedDecodedPlanes.lumaPlane() && chromaUPlane == checkedDecodedPlanes.chromaUPlane() && chromaVPlane == checkedDecodedPlanes.chromaVPlane()) {
            return checkedDecodedPlanes
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

    /** Returns whether any plane has active frame-level restoration.
     * 
     * @param restoration the frame-level restoration state
     * @param hasChroma whether chroma planes are present
     * @return whether any plane has active frame-level restoration
     */
    fun hasActiveRestoration(restoration: RestorationInfo, hasChroma: Boolean): Boolean {
        val types = restoration.types()
        return types[0] != RestorationType.NONE
                || (hasChroma && (types[1] != RestorationType.NONE
                || types[2] != RestorationType.NONE))
    }

    /** Applies restoration to one plane.
     * 
     * @param plane the source plane
     * @param boundaryPlane the source plane before CDEF, used for internal stripe boundaries
     * @param bitDepth the decoded bit depth
     * @param chromaFormat the decoded chroma format
     * @param restoration the frame-level restoration state
     * @param unitMap the decoded restoration-unit map
     * @param planeIndex the plane index
     * @param workspace the reusable per-frame restoration workspace
     * @return the restored plane, or the original plane when all selected units are disabled
     */
    private fun applyPlane(
        plane: PaddedPlane,
        boundaryPlane: PaddedPlane,
        bitDepth: Int,
        chromaFormat: Av1ChromaFormat,
        restoration: RestorationInfo,
        unitMap: RestorationUnitMap,
        planeIndex: Int,
        workspace: RestorationWorkspace
    ): PaddedPlane {
        val frameType = restoration.types()[planeIndex]
        if (frameType == RestorationType.NONE) {
            return plane
        }
        require(!(boundaryPlane.width() != plane.width() || boundaryPlane.height() != plane.height())) { "Boundary plane dimensions must match decoded plane dimensions" }

        val unitSize =
            1 shl (if (planeIndex == 0) restoration.unitSizeLog2Y() else restoration.unitSizeLog2Uv())
        val source: PlaneSampleSource = DecodedPlaneSource(plane, bitDepth)
        val boundarySource = if (boundaryPlane == plane)
            source
        else
            DecodedPlaneSource(boundaryPlane, bitDepth)
        var destination: PlaneBuffer? = null
        val rows = unitMap.rows(planeIndex)
        val columns = unitMap.columns(planeIndex)
        check(!(rows == 0 || columns == 0)) { "Active AV1 loop restoration requires decoded restoration unit syntax" }
        val subX = chromaSubsamplingX(chromaFormat, planeIndex)
        val subY = chromaSubsamplingY(chromaFormat, planeIndex)
        val verticalOffset = RESTORATION_UNIT_OFFSET shr subY
        val processingStripeHeight = RESTORATION_PROC_UNIT_SIZE shr subY
        val processingUnitWidth = RESTORATION_PROC_UNIT_SIZE shr subX
        for (unitRow in 0..<rows) {
            val baseVerticalLimits = unitLimits(unitRow, unitSize, plane.height())
            val startY = max(0, baseVerticalLimits.start - verticalOffset)
            val endY = if (baseVerticalLimits.end < plane.height())
                baseVerticalLimits.end - verticalOffset
            else
                baseVerticalLimits.end
            for (unitColumn in 0..<columns) {
                val unit = unitMap.unit(planeIndex, unitRow, unitColumn)
                checkNotNull(unit) { "Active AV1 loop restoration requires decoded restoration unit syntax" }
                if (unit.type() == RestorationType.NONE) {
                    continue
                }
                if (destination == null) {
                    destination = PlaneBuffer.Companion.create(plane, bitDepth)
                }
                val horizontalLimits = unitLimits(unitColumn, unitSize, plane.width())
                applyRestorationUnit(
                    source,
                    boundarySource,
                    destination,
                    unit,
                    horizontalLimits.start,
                    startY,
                    horizontalLimits.end,
                    endY,
                    processingStripeHeight,
                    verticalOffset,
                    processingUnitWidth,
                    workspace
                )
            }
        }
        return if (destination != null) destination.toDecodedPlane() else plane
    }

    /** Applies one restoration unit one processing stripe at a time.
     * 
     * @param source the immutable post-CDEF source plane view
     * @param boundarySource the immutable pre-CDEF boundary plane view
     * @param destination the mutable destination plane view
     * @param unit the decoded restoration unit
     * @param startX the inclusive unit start X
     * @param startY the inclusive unit start Y
     * @param endX the exclusive unit end X
     * @param endY the exclusive unit end Y
     * @param processingStripeHeight the processing stripe height for this plane
     * @param verticalOffset the vertical processing stripe offset for this plane
     * @param processingUnitWidth the horizontal processing unit width for this plane
     * @param workspace the reusable per-frame restoration workspace
     */
    private fun applyRestorationUnit(
        source: PlaneSampleSource,
        boundarySource: PlaneSampleSource,
        destination: PlaneBuffer,
        unit: RestorationUnit,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        processingStripeHeight: Int,
        verticalOffset: Int,
        processingUnitWidth: Int,
        workspace: RestorationWorkspace
    ) {
        var stripeStart = startY
        while (stripeStart < endY) {
            val frameStripe = (stripeStart + verticalOffset) / processingStripeHeight
            val nominalStripeHeight = (processingStripeHeight
                    - (if (frameStripe == 0) verticalOffset else 0))
            val stripeEnd = min(endY, stripeStart + nominalStripeHeight)
            val copyAbove = stripeStart != 0
            val stripeBoundaryHeight =
                processingStripeHeight - (if (stripeStart == 0) verticalOffset else 0)
            val copyBelow = stripeStart + stripeBoundaryHeight < source.height()
            val stripeSource = StripeBoundarySource(
                source,
                boundarySource,
                stripeStart,
                stripeEnd,
                copyAbove,
                copyBelow
            )
            if (unit.type() == RestorationType.WIENER) {
                applyWienerUnit(
                    stripeSource,
                    destination,
                    unit,
                    startX,
                    stripeStart,
                    endX,
                    stripeEnd,
                    workspace
                )
            } else if (unit.type() == RestorationType.SELF_GUIDED) {
                applySelfGuidedUnit(
                    stripeSource,
                    destination,
                    unit,
                    startX,
                    stripeStart,
                    endX,
                    stripeEnd,
                    processingUnitWidth,
                    workspace
                )
            } else {
                throw IllegalStateException("Unsupported restoration unit type: " + unit.type())
            }
            stripeStart = stripeEnd
        }
    }

    /** Applies one Wiener restoration unit.
     * 
     * @param source the immutable source plane view
     * @param destination the mutable destination plane view
     * @param unit the decoded Wiener unit
     * @param startX the inclusive unit start X
     * @param startY the inclusive unit start Y
     * @param endX the exclusive unit end X
     * @param endY the exclusive unit end Y
     * @param workspace the reusable per-frame restoration workspace
     */
    private fun applyWienerUnit(
        source: PlaneSampleSource,
        destination: PlaneBuffer,
        unit: RestorationUnit,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        workspace: RestorationWorkspace
    ) {
        val horizontalKernel = workspace.horizontalWienerKernel
        val verticalKernel = workspace.verticalWienerKernel
        fillWienerKernel(unit, 0, horizontalKernel)
        fillWienerKernel(unit, 1, verticalKernel)

        val width = endX - startX
        val height = endY - startY
        val horizontalRowCount = height + WIENER_TAP_COUNT - 1
        val horizontalSamples = workspace.wienerIntermediate(width * horizontalRowCount)
        val sourceRowLength = width + WIENER_TAP_COUNT - 1
        val sourceRow = workspace.sourceRow(sourceRowLength)
        val bitDepth = source.bitDepth()
        val roundBitsH = 3 + (if (bitDepth == 12) 2 else 0)
        val roundingOffsetH = 1 shl (roundBitsH - 1)
        val clipLimit = 1 shl (bitDepth + 1 + FILTER_BITS - roundBitsH)
        val horizontalInitialSum = 1 shl (bitDepth + 6)

        // The vertical stage consumes seven adjacent horizontal rows. Compute each row once
        // instead of recomputing the horizontal convolution for every vertical output tap.
        for (row in 0..<horizontalRowCount) {
            val sourceY = startY + row - WIENER_TAP_OFFSET
            val rowOffset = row * width
            source.copyExtendedRow(
                startX - WIENER_TAP_OFFSET,
                sourceY,
                sourceRowLength,
                sourceRow,
                0
            )
            for (localX in 0..<width) {
                var sum = horizontalInitialSum
                for (tap in 0..<WIENER_TAP_COUNT) {
                    sum += horizontalKernel[tap] * sourceRow[localX + tap]
                }
                horizontalSamples[rowOffset + localX] =
                    clamp((sum + roundingOffsetH) shr roundBitsH, 0, clipLimit - 1)
            }
        }

        val roundBitsV = 11 - (if (bitDepth == 12) 2 else 0)
        val roundingOffsetV = 1 shl (roundBitsV - 1)
        val roundOffset = 1 shl (bitDepth + roundBitsV - 1)
        for (y in startY..<endY) {
            val firstHorizontalRow = y - startY
            for (localX in 0..<width) {
                var sum = -roundOffset
                for (tap in 0..<WIENER_TAP_COUNT) {
                    sum += (verticalKernel[tap]
                            * horizontalSamples[(firstHorizontalRow + tap) * width + localX])
                }
                destination.setSample(
                    startX + localX,
                    y,
                    (sum + roundingOffsetV) shr roundBitsV
                )
            }
        }
    }

    /** Applies one self-guided restoration unit.
     * 
     * @param source the immutable source plane view
     * @param destination the mutable destination plane view
     * @param unit the decoded self-guided unit
     * @param startX the inclusive unit start X
     * @param startY the inclusive unit start Y
     * @param endX the exclusive unit end X
     * @param endY the exclusive unit end Y
     * @param processingUnitWidth the horizontal processing unit width for this plane
     * @param workspace the reusable per-frame restoration workspace
     */
    private fun applySelfGuidedUnit(
        source: PlaneSampleSource,
        destination: PlaneBuffer,
        unit: RestorationUnit,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        processingUnitWidth: Int,
        workspace: RestorationWorkspace
    ) {
        val params: IntArray = SELF_GUIDED_PARAMS[unit.selfGuidedSet()]
        val projection0 = unit.selfGuidedProjectionCoefficient(0)
        val projection1 = unit.selfGuidedProjectionCoefficient(1)
        var chunkStartX = startX
        while (chunkStartX < endX) {
            val chunkEndX = min(endX, chunkStartX + processingUnitWidth)
            val chunkWidth = chunkEndX - chunkStartX
            val stripeHeight = endY - startY
            val radius2Filter = if (params[0] != 0)
                workspace.radius2Intermediate.compute(
                    source, chunkStartX, startY, chunkWidth, stripeHeight, 2, params[1]
                )
            else
                null
            val radius1Filter = if (params[2] != 0)
                workspace.radius1Intermediate.compute(
                    source, chunkStartX, startY, chunkWidth, stripeHeight, 1, params[3]
                )
            else
                null
            val weight0 = if (radius2Filter != null) projection0 else 0
            val weight1 = if (radius1Filter != null) 128 - projection0 - projection1 else 0
            val baseSamples = workspace.sourceRow(chunkWidth)
            for (y in startY..<endY) {
                val localY = y - startY
                source.copyExtendedRow(chunkStartX, y, chunkWidth, baseSamples, 0)
                for (localX in 0..<chunkWidth) {
                    val base = baseSamples[localX]
                    var adjustment = 0
                    if (radius2Filter != null && weight0 != 0) {
                        adjustment += weight0 * radius2Filter.residual5(localX, localY, base)
                    }
                    if (radius1Filter != null && weight1 != 0) {
                        adjustment += weight1 * radius1Filter.residual3(localX, localY, base)
                    }
                    destination.setSample(
                        chunkStartX + localX,
                        y,
                        base + round2(adjustment, SELF_GUIDED_WEIGHT_BITS)
                    )
                }
            }
            chunkStartX += processingUnitWidth
        }
    }

    /** Creates the immutable dav1d `sgr_x_by_x` reciprocal table.
     * 
     * @return all reciprocal values for normalized variance indices `[0, 255]`
     */
    private fun createSelfGuidedXByXTable(): IntArray {
        val values = IntArray(SELF_GUIDED_MAX_Z + 1)
        for (z in 0..<SELF_GUIDED_MAX_Z) {
            values[z] = min(255, (256 + (z shr 1)) / (z + 1))
        }
        return values
    }

    /** Fills one symmetric seven-tap Wiener filter kernel.
     * 
     * @param unit the decoded Wiener restoration unit
     * @param pass the pass index, where zero is horizontal and one is vertical
     * @param kernel the seven-element destination kernel
     */
    private fun fillWienerKernel(unit: RestorationUnit, pass: Int, kernel: IntArray) {
        val c0 = unit.wienerCoefficient(pass, 0)
        val c1 = unit.wienerCoefficient(pass, 1)
        val c2 = unit.wienerCoefficient(pass, 2)
        kernel[0] = c0
        kernel[1] = c1
        kernel[2] = c2
        kernel[3] = (1 shl FILTER_BITS) - 2 * (c0 + c1 + c2)
        kernel[4] = c2
        kernel[5] = c1
        kernel[6] = c0
    }

    /** Clips one integer into inclusive bounds.
     * 
     * @param value the input value
     * @param minimum the inclusive minimum
     * @param maximum the inclusive maximum
     * @return the clipped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(value, maximum))
    }

    /** Rounds one integer right shift by adding half the divisor.
     * 
     * @param value the value to round
     * @param bits the number of bits to discard
     * @return the rounded shifted value
     */
    private fun round2(value: Int, bits: Int): Int {
        if (bits == 0) {
            return value
        }
        return (value + (1 shl (bits - 1))) shr bits
    }

    /** Returns one restoration-unit span using AV1's shortened final-unit rule.
     * 
     * @param unitIndex the zero-based restoration unit index
     * @param unitSize the nominal restoration unit size
     * @param extent the plane extent in samples
     * @return the inclusive-exclusive sample limits for the unit
     */
    private fun unitLimits(unitIndex: Int, unitSize: Int, extent: Int): UnitLimits {
        var start = 0
        for (index in 0..<unitIndex) {
            start += unitSpan(start, unitSize, extent)
        }
        return UnitLimits(start, start + unitSpan(start, unitSize, extent))
    }

    /** Returns one restoration-unit span length from a starting coordinate.
     * 
     * @param start the unit start coordinate
     * @param unitSize the nominal restoration unit size
     * @param extent the plane extent in samples
     * @return the unit span length
     */
    private fun unitSpan(start: Int, unitSize: Int, extent: Int): Int {
        val remaining = extent - start
        val extendedSize = unitSize * 3 / 2
        return if (remaining < extendedSize) remaining else unitSize
    }

    /** Returns the chroma horizontal subsampling shift for one plane.
     * 
     * @param chromaFormat the decoded chroma format
     * @param planeIndex the plane index
     * @return the chroma horizontal subsampling shift for one plane
     */
    private fun chromaSubsamplingX(chromaFormat: Av1ChromaFormat, planeIndex: Int): Int {
        if (planeIndex == 0) {
            return 0
        }
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
        }
    }

    /** Returns the chroma vertical subsampling shift for one plane.
     * 
     * @param chromaFormat the decoded chroma format
     * @param planeIndex the plane index
     * @return the chroma vertical subsampling shift for one plane
     */
    private fun chromaSubsamplingY(chromaFormat: Av1ChromaFormat, planeIndex: Int): Int {
        if (planeIndex == 0) {
            return 0
        }
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420 -> 1
        }
    }

    /** Reusable scratch storage shared by all restoration units in one frame. */
    class RestorationWorkspace
    /** Creates an empty per-frame restoration workspace. */
    {
        /** The horizontal Wiener intermediate rows. */
        var wienerIntermediate = IntArray(0)

        /** One frame-border-extended source row used by Wiener filtering. */
        var sourceRow = IntArray(0)

        /** The reusable horizontal Wiener kernel. */
        val horizontalWienerKernel = IntArray(WIENER_TAP_COUNT)

        /** The reusable vertical Wiener kernel. */
        val verticalWienerKernel = IntArray(WIENER_TAP_COUNT)

        /** The reusable radius-two self-guided fields. */
        val radius2Intermediate = SelfGuidedIntermediate()

        /** The reusable radius-one self-guided fields. */
        val radius1Intermediate = SelfGuidedIntermediate()

        /** Returns horizontal Wiener storage with at least the requested length.
         * 
         * @param requiredLength the minimum number of intermediate samples
         * @return reusable intermediate storage
         */
        fun wienerIntermediate(requiredLength: Int): IntArray {
            if (wienerIntermediate.size < requiredLength) {
                wienerIntermediate = IntArray(requiredLength)
            }
            return wienerIntermediate
        }

        /** Returns source-row storage with at least the requested length.
         * 
         * @param requiredLength the minimum number of source samples
         * @return reusable source-row storage
         */
        fun sourceRow(requiredLength: Int): IntArray {
            if (sourceRow.size < requiredLength) {
                sourceRow = IntArray(requiredLength)
            }
            return sourceRow
        }
    }

    /** Self-guided A/B projection fields for one filter radius. */
    class SelfGuidedIntermediate
    /** Creates an empty reusable self-guided intermediate field. */
    {
        /** The processing block width in samples. */
        var width = 0

        /** The inverted A field used by the dav1d finish equations. */
        var a = IntArray(0)

        /** The inverted B field used by the dav1d finish equations. */
        var b = IntArray(0)

        /** The reusable vertical box-filter column sums. */
        var columnSums = IntArray(0)

        /** The reusable vertical box-filter squared-sample sums. */
        var columnSquareSums = IntArray(0)

        /** One frame-border-extended source row used to update vertical column sums. */
        var sourceRow = IntArray(0)

        /** Computes one self-guided intermediate field.
         * 
         * @param source the source plane
         * @param startX the global processing block start X
         * @param startY the global processing stripe start Y
         * @param width the processing block width in samples
         * @param height the processing stripe height in samples
         * @param radius the self-guided filter radius
         * @param strength the self-guided strength value from the AV1 parameter table
         * @return this populated reusable intermediate field
         */
        fun compute(
            source: PlaneSampleSource,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            radius: Int,
            strength: Int
        ): SelfGuidedIntermediate {
            require(!(radius != 1 && radius != 2)) { "radius must be 1 or 2: $radius" }
            this.width = width
            val count = if (radius == 1) 9 else 25
            val oneByX = if (radius == 1) 455 else 164
            val bitDepthShift = source.bitDepth() - 8
            val fieldWidth = width + 2
            val fieldHeight = height + 2
            val fieldLength = fieldWidth * fieldHeight
            if (a.size < fieldLength) {
                a = IntArray(fieldLength)
                b = IntArray(fieldLength)
            }
            val windowSize = radius * 2 + 1
            val extendedWidth = fieldWidth + radius * 2
            val firstSourceX = startX - 1 - radius
            val firstCenterY = startY - 1
            if (columnSums.size < extendedWidth) {
                columnSums = IntArray(extendedWidth)
                columnSquareSums = IntArray(extendedWidth)
            }
            if (sourceRow.size < extendedWidth) {
                sourceRow = IntArray(extendedWidth)
            }
            // Keep vertical column totals and slide them horizontally so each field sample is O(1).
            for (column in 0..<extendedWidth) {
                columnSums[column] = 0
                columnSquareSums[column] = 0
            }
            for (dy in -radius..radius) {
                source.copyExtendedRow(firstSourceX, firstCenterY + dy, extendedWidth, sourceRow, 0)
                for (column in 0..<extendedWidth) {
                    val sample = sourceRow[column]
                    columnSums[column] += sample
                    columnSquareSums[column] += sample * sample
                }
            }

            for (fieldY in 0..<fieldHeight) {
                if (fieldY != 0) {
                    val centerY = firstCenterY + fieldY
                    val removedY = centerY - radius - 1
                    val addedY = centerY + radius
                    source.copyExtendedRow(firstSourceX, removedY, extendedWidth, sourceRow, 0)
                    for (column in 0..<extendedWidth) {
                        val removedSample = sourceRow[column]
                        columnSums[column] -= removedSample
                        columnSquareSums[column] -= removedSample * removedSample
                    }
                    source.copyExtendedRow(firstSourceX, addedY, extendedWidth, sourceRow, 0)
                    for (column in 0..<extendedWidth) {
                        val addedSample = sourceRow[column]
                        columnSums[column] += addedSample
                        columnSquareSums[column] += addedSample * addedSample
                    }
                }

                var sum = 0
                var sumSquares = 0
                for (column in 0..<windowSize) {
                    sum += columnSums[column]
                    sumSquares += columnSquareSums[column]
                }
                for (fieldX in 0..<fieldWidth) {
                    val projection =
                        projection(sum, sumSquares, count, strength, oneByX, bitDepthShift)
                    val index = fieldY * fieldWidth + fieldX
                    a[index] = projection.a
                    b[index] = projection.b
                    if (fieldX + 1 < fieldWidth) {
                        sum += columnSums[fieldX + windowSize] - columnSums[fieldX]
                        sumSquares += columnSquareSums[fieldX + windowSize] - columnSquareSums[fieldX]
                    }
                }
            }
            return this
        }

        /** Returns one 3x3 residual from the inverted A/B fields.
         * 
         * @param x the sample X coordinate relative to the processing block
         * @param y the sample Y coordinate relative to the processing stripe
         * @param sourceSample the original source sample
         * @return one 3x3 residual
         */
        fun residual3(x: Int, y: Int, sourceSample: Int): Int {
            val weightedB = eightNeighborWeight(b, x, y)
            val weightedA = eightNeighborWeight(a, x, y)
            return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_3_BITS)
        }

        /** Returns one 5x5 residual from the inverted A/B fields.
         * 
         * @param x the sample X coordinate relative to the processing block
         * @param y the sample Y coordinate relative to the processing stripe
         * @param sourceSample the original source sample
         * @return one 5x5 residual
         */
        fun residual5(x: Int, y: Int, sourceSample: Int): Int {
            if ((y and 1) == 0) {
                val weightedB = sixNeighborPairWeight(b, x, y)
                val weightedA = sixNeighborPairWeight(a, x, y)
                return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_5_PAIR_BITS)
            }
            val weightedB = sixNeighborSingleWeight(b, x, y)
            val weightedA = sixNeighborSingleWeight(a, x, y)
            return round2(weightedA - weightedB * sourceSample, SELF_GUIDED_FILTER_5_SINGLE_BITS)
        }

        /** Returns one field value from the stored one-sample halo.
         * 
         * @param values the field storage
         * @param x the sample X coordinate in `[-1, width]`
         * @param y the sample Y coordinate in `[-1, height]`
         * @return one field value
         */
        fun fieldValue(values: IntArray, x: Int, y: Int): Int {
            return values[(y + 1) * (width + 2) + x + 1]
        }

        /** Returns the dav1d eight-neighbor weighted sum for one field.
         * 
         * @param values the field storage
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return the weighted field sum
         */
        fun eightNeighborWeight(values: IntArray, x: Int, y: Int): Int {
            return ((fieldValue(values, x, y)
                    + fieldValue(values, x - 1, y)
                    + fieldValue(values, x + 1, y)
                    + fieldValue(values, x, y - 1)
                    + fieldValue(values, x, y + 1)) * 4
                    + (fieldValue(values, x - 1, y - 1)
                    + fieldValue(values, x + 1, y - 1)
                    + fieldValue(values, x - 1, y + 1)
                    + fieldValue(values, x + 1, y + 1)) * 3)
        }

        /** Returns the dav1d paired-row weighted sum for one 5x5 field.
         * 
         * @param values the field storage
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return the weighted field sum
         */
        fun sixNeighborPairWeight(values: IntArray, x: Int, y: Int): Int {
            val topY = y - 1
            val bottomY = y + 1
            return ((fieldValue(values, x, topY) + fieldValue(values, x, bottomY)) * 6
                    + (fieldValue(values, x - 1, topY)
                    + fieldValue(values, x + 1, topY)
                    + fieldValue(values, x - 1, bottomY)
                    + fieldValue(values, x + 1, bottomY)) * 5)
        }

        /** Returns the dav1d single-row weighted sum for one 5x5 field.
         * 
         * @param values the field storage
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return the weighted field sum
         */
        fun sixNeighborSingleWeight(values: IntArray, x: Int, y: Int): Int {
            return (fieldValue(values, x, y) * 6
                    + (fieldValue(values, x - 1, y) + fieldValue(values, x + 1, y)) * 5)
        }

        companion object {
            /** Computes the inverted dav1d A/B projection values for one box.
             * 
             * @param sum the source box sample sum
             * @param sumSquares the source box squared-sample sum
             * @param count the number of samples in the box
             * @param strength the self-guided strength value
             * @param oneByX the reciprocal normalization constant
             * @param bitDepthShift the decoded bit depth minus eight
             * @return the inverted A/B projection values
             */
            fun projection(
                sum: Int,
                sumSquares: Int,
                count: Int,
                strength: Int,
                oneByX: Int,
                bitDepthShift: Int
            ): Projection {
                val scaledSumSquares = roundForBitDepth(sumSquares, bitDepthShift * 2)
                val scaledSum = roundForBitDepth(sum, bitDepthShift)
                val variance =
                    max(scaledSumSquares.toLong() * count - scaledSum.toLong() * scaledSum, 0L)
                val z = min(
                    SELF_GUIDED_MAX_Z.toLong(),
                    (variance * strength + (1 shl 19)) shr 20
                ).toInt()
                val xByX = SELF_GUIDED_X_BY_X[z]
                val a = ((xByX.toLong() * sum * oneByX + (1 shl 11)) shr 12).toInt()
                return Projection(a, xByX)
            }

            /** Rounds one box statistic down to the normalized 8-bit domain.
             * 
             * @param value the source statistic
             * @param bits the number of bits to remove
             * @return the rounded statistic
             */
            fun roundForBitDepth(value: Int, bits: Int): Int {
                if (bits == 0) {
                    return value
                }
                return (value + ((1 shl bits) shr 1)) shr bits
            }
        }
    }

    /** Inverted self-guided projection values.
     * 
     * @param a the inverted A value
     * @param b the inverted B value
     */
    data class Projection(val a: Int, val b: Int)

    /** Inclusive-exclusive restoration unit limits.
     * 
     * @param start the inclusive start coordinate
     * @param end the exclusive end coordinate
     */
    data class UnitLimits(val start: Int, val end: Int)

    /** Read-only plane source with AV1 frame-border extension. */
    interface PlaneSampleSource {
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

        /** Returns the decoded bit depth.
         * 
         * @return the decoded bit depth
         */
        fun bitDepth(): Int

        /** Returns one frame-border-extended sample.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return one sample
         */
        fun sample(x: Int, y: Int): Int

        /** Copies one frame-border-extended source row into caller-provided storage.
         * 
         * @param startX the first source X coordinate
         * @param y the source Y coordinate
         * @param length the number of samples to copy
         * @param destination the destination storage
         * @param destinationOffset the first destination index
         */
        fun copyExtendedRow(
            startX: Int,
            y: Int,
            length: Int,
            destination: IntArray,
            destinationOffset: Int
        ) {
            for (index in 0..<length) {
                destination[destinationOffset + index] = sample(startX + index, y)
            }
        }
    }

    /** Read-only restoration source backed by one immutable decoded plane. */
    class DecodedPlaneSource(
        plane: PaddedPlane,
        /** The decoded bit depth. */
        val bitDepth: Int
    ) : PlaneSampleSource {
        /** The immutable decoded plane. */
        val plane: PaddedPlane

        init {
            this.plane = requireNotNullCompat(plane, "plane")
        }

        /** Returns the visible plane width.
         * 
         * @return the visible plane width in samples
         */
        override fun width(): Int {
            return plane.width()
        }

        /** Returns the visible plane height.
         * 
         * @return the visible plane height in samples
         */
        override fun height(): Int {
            return plane.height()
        }

        /** Returns the decoded bit depth.
         * 
         * @return the decoded bit depth
         */
        override fun bitDepth(): Int {
            return bitDepth
        }

        /** Returns one frame-border-extended sample.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return the nearest visible-plane sample
         */
        override fun sample(x: Int, y: Int): Int {
            return plane.sample(clamp(x, 0, plane.width() - 1), clamp(y, 0, plane.height() - 1))
        }

        /** Copies one frame-border-extended decoded-plane row.
         * 
         * @param startX the first source X coordinate
         * @param y the source Y coordinate
         * @param length the number of samples to copy
         * @param destination the destination storage
         * @param destinationOffset the first destination index
         */
        override fun copyExtendedRow(
            startX: Int,
            y: Int,
            length: Int,
            destination: IntArray,
            destinationOffset: Int
        ) {
            val clampedY = clamp(y, 0, plane.height() - 1)
            val sourceWidth = plane.width()
            val firstVisibleIndex = clamp(-startX, 0, length)
            val visibleEndIndex = clamp(sourceWidth - startX, firstVisibleIndex, length)
            val leftSample = plane.sample(0, clampedY)
            for (index in 0..<firstVisibleIndex) {
                destination[destinationOffset + index] = leftSample
            }
            for (index in firstVisibleIndex..<visibleEndIndex) {
                destination[destinationOffset + index] = plane.sample(startX + index, clampedY)
            }
            val rightSample = plane.sample(sourceWidth - 1, clampedY)
            for (index in visibleEndIndex..<length) {
                destination[destinationOffset + index] = rightSample
            }
        }
    }

    /** Source view that substitutes AV1 internal restoration stripe boundary rows. */
    class StripeBoundarySource(
        source: PlaneSampleSource,
        boundarySource: PlaneSampleSource,
        /** The inclusive processing stripe start Y. */
        val stripeStart: Int,
        /** The exclusive processing stripe end Y. */
        val stripeEnd: Int,
        /** Whether rows above this stripe use saved boundary lines. */
        val copyAbove: Boolean,
        /** Whether rows below this stripe use saved boundary lines. */
        val copyBelow: Boolean
    ) : PlaneSampleSource {
        /** The post-CDEF source plane. */
        val source: PlaneSampleSource

        /** The pre-CDEF boundary source plane. */
        val boundarySource: PlaneSampleSource

        init {
            this.source = requireNotNullCompat(source, "source")
            this.boundarySource =
                requireNotNullCompat(boundarySource, "boundarySource")
        }

        /** Returns the plane width in samples.
         * 
         * @return the plane width in samples
         */
        override fun width(): Int {
            return source.width()
        }

        /** Returns the plane height in samples.
         * 
         * @return the plane height in samples
         */
        override fun height(): Int {
            return source.height()
        }

        /** Returns the decoded bit depth.
         * 
         * @return the decoded bit depth
         */
        override fun bitDepth(): Int {
            return source.bitDepth()
        }

        /** Returns one sample with internal stripe boundary rows substituted.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return one sample
         */
        override fun sample(x: Int, y: Int): Int {
            if (copyAbove && y >= stripeStart - RESTORATION_STRIPE_BORDER && y < stripeStart) {
                val boundaryY = if (y <= stripeStart - 2) stripeStart - 2 else stripeStart - 1
                return boundarySource.sample(x, boundaryY)
            }
            if (copyBelow && y >= stripeEnd && y < stripeEnd + RESTORATION_STRIPE_BORDER) {
                val boundaryY = if (y == stripeEnd) stripeEnd else stripeEnd + 1
                return boundarySource.sample(x, boundaryY)
            }
            return source.sample(x, y)
        }

        /** Copies one source row after resolving internal stripe-boundary substitution once.
         * 
         * @param startX the first source X coordinate
         * @param y the source Y coordinate
         * @param length the number of samples to copy
         * @param destination the destination storage
         * @param destinationOffset the first destination index
         */
        override fun copyExtendedRow(
            startX: Int,
            y: Int,
            length: Int,
            destination: IntArray,
            destinationOffset: Int
        ) {
            if (copyAbove && y >= stripeStart - RESTORATION_STRIPE_BORDER && y < stripeStart) {
                val boundaryY = if (y <= stripeStart - 2) stripeStart - 2 else stripeStart - 1
                boundarySource.copyExtendedRow(
                    startX,
                    boundaryY,
                    length,
                    destination,
                    destinationOffset
                )
                return
            }
            if (copyBelow && y >= stripeEnd && y < stripeEnd + RESTORATION_STRIPE_BORDER) {
                val boundaryY = if (y == stripeEnd) stripeEnd else stripeEnd + 1
                boundarySource.copyExtendedRow(
                    startX,
                    boundaryY,
                    length,
                    destination,
                    destinationOffset
                )
                return
            }
            source.copyExtendedRow(startX, y, length, destination, destinationOffset)
        }
    }

    /** Mutable plane storage used by restoration filtering. */
    class PlaneBuffer(
        /** The plane width in samples. */
        val width: Int,
        /** The plane height in samples. */
        val height: Int,
        /** The sample stride of one plane row. */
        val stride: Int,
        /** The decoded bit depth. */
        val bitDepth: Int, samples: ShortArray
    ) : PlaneSampleSource {
        /** The maximum legal sample value. */
        val maxSampleValue: Int

        /** The mutable sample storage in row-major order. */
        val samples: ShortArray

        init {
            this.maxSampleValue = (1 shl bitDepth) - 1
            this.samples = requireNotNullCompat(samples, "samples")
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

        /** Returns the decoded bit depth.
         * 
         * @return the decoded bit depth
         */
        override fun bitDepth(): Int {
            return bitDepth
        }

        /** Returns one sample.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @return one sample
         */
        override fun sample(x: Int, y: Int): Int {
            val clampedX = clamp(x, 0, width - 1)
            val clampedY = clamp(y, 0, height - 1)
            return samples[clampedY * stride + clampedX].toInt() and 0xFFFF
        }

        /** Stores one clipped sample.
         * 
         * @param x the sample X coordinate
         * @param y the sample Y coordinate
         * @param value the replacement value
         */
        fun setSample(x: Int, y: Int, value: Int) {
            samples[y * stride + x] = clamp(value, 0, maxSampleValue).toShort()
        }

        /** Returns one immutable decoded plane from the current samples.
         * 
         * @return one immutable decoded plane from the current samples
         */
        fun toDecodedPlane(): PaddedPlane {
            return PaddedPlane.Companion.fromOwnedSamples(width, height, stride, samples)
        }

        companion object {
            /** Creates a mutable copy of one decoded plane.
             * 
             * @param plane the decoded plane
             * @param bitDepth the decoded bit depth
             * @return a mutable copy of one decoded plane
             */
            fun create(plane: PaddedPlane, bitDepth: Int): PlaneBuffer {
                val checkedPlane = requireNotNullCompat(plane, "plane")
                return PlaneBuffer(
                    checkedPlane.width(),
                    checkedPlane.height(),
                    checkedPlane.stride(),
                    bitDepth,
                    checkedPlane.samples()
                )
            }
        }
    }
}
