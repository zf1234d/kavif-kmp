// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader.PartitionNode
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.model.FrameHeader.CdefInfo


/** Applies the AV1 CDEF stage of the postfilter pipeline.
 * 
 * The implementation mirrors the dav1d CDEF scalar path at decoded-plane granularity: strength
 * values are scaled by bit depth, dominant directions are detected from luma 8x8 units, chroma
 * primary filtering reuses the luma direction, stored edge padding participates up to the aligned
 * CDEF processing boundary, and fully skipped CDEF units are preserved. */
object CdefApplier {
    /** The luma CDEF unit size in samples. */
    private const val CDEF_UNIT_SIZE = 8

    /** Sentinel used for samples beyond the aligned CDEF processing grid. */
    private const val MISSING_SAMPLE = Int.MIN_VALUE

    /** Direction-search divisors copied from the AV1 CDEF direction estimator. */
    private val DIRECTION_DIVISORS: IntArray =
        intArrayOf(840, 420, 280, 210, 168, 140, 120)

    /** Direction vectors for the two primary or secondary CDEF tap distances. */
    private val FILTER_DIRECTIONS: Array<Array<IntArray>> = arrayOf<Array<IntArray>>(
        arrayOf<IntArray>(intArrayOf(1, -1), intArrayOf(2, -2)),
        arrayOf<IntArray>(intArrayOf(1, 0), intArrayOf(2, -1)),
        arrayOf<IntArray>(intArrayOf(1, 0), intArrayOf(2, 0)),
        arrayOf<IntArray>(intArrayOf(1, 0), intArrayOf(2, 1)),
        arrayOf<IntArray>(intArrayOf(1, 1), intArrayOf(2, 2)),
        arrayOf<IntArray>(intArrayOf(0, 1), intArrayOf(1, 2)),
        arrayOf<IntArray>(intArrayOf(0, 1), intArrayOf(0, 2)),
        arrayOf<IntArray>(intArrayOf(0, 1), intArrayOf(-1, 2))
    )

    /** Chroma direction remapping used by AV1 for `YUV422`. */
    private val I422_UV_DIRECTIONS: IntArray = intArrayOf(7, 0, 2, 4, 5, 6, 6, 6)

    /** Applies CDEF to one decoded frame using block-level syntax for CDEF index selection.
     * 
     * @param decodedPlanes the decoded planes after loop filtering
     * @param cdef the normalized frame-level CDEF state
     * @param syntaxDecodeResult the decoded block syntax that carries CDEF indices, or `null`
     * @return the post-CDEF planes
     */
    fun apply(
        decodedPlanes: DecodedSurface,
        cdef: CdefInfo,
        syntaxDecodeResult: FrameSyntaxDecodeResult?
    ): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        return applyPrepared(
            checkedDecodedPlanes,
            prepare(
                checkedDecodedPlanes,
                requireNotNullCompat(cdef, "cdef"),
                syntaxDecodeResult
            )
        )
    }

    /** Extracts the compact frame state required while applying CDEF.
     * 
     * The returned state does not retain `syntaxDecodeResult`. Callers may therefore release the
     * complete frame syntax tree before [applyPrepared]
     * allocates destination planes.
     * 
     * @param decodedPlanes the decoded planes after loop filtering
     * @param cdef the normalized frame-level CDEF state
     * @param syntaxDecodeResult the decoded block syntax that carries CDEF indices, or `null`
     * @return the compact prepared CDEF state
     */
    fun prepare(
        decodedPlanes: DecodedSurface,
        cdef: CdefInfo,
        syntaxDecodeResult: FrameSyntaxDecodeResult?
    ): PreparedApplication {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val checkedCdef = requireNotNullCompat(cdef, "cdef")
        val yStrengths = checkedCdef.yStrengths()
        val uvStrengths = checkedCdef.uvStrengths()
        if (!hasActiveStrengths(yStrengths, uvStrengths, checkedDecodedPlanes.hasChroma())) {
            return PreparedApplication.Companion.inactive(checkedDecodedPlanes)
        }
        checkNotNull(syntaxDecodeResult) { "Active AV1 CDEF filtering requires decoded block CDEF indices" }

        val unitColumns = cdefUnitCount(checkedDecodedPlanes.codedWidth())
        val unitRows = cdefUnitCount(checkedDecodedPlanes.codedHeight())
        val cdefUnitMap = buildCdefUnitMap(syntaxDecodeResult, unitColumns, unitRows)
        val bitDepthShift = checkedDecodedPlanes.bitDepth() - 8
        val damping = checkedCdef.damping() + bitDepthShift
        return CdefApplier.PreparedApplication(
            checkedDecodedPlanes,
            yStrengths,
            uvStrengths,
            unitColumns,
            unitRows,
            bitDepthShift,
            damping,
            cdefUnitMap
        )
    }

    /** Applies one previously prepared CDEF operation.
     * 
     * @param decodedPlanes the same loop-filtered surface used to prepare the operation
     * @param preparedApplication the compact prepared CDEF state
     * @return the post-CDEF planes
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

        val yStrengths = prepared.yStrengths
        val uvStrengths = prepared.uvStrengths
        val unitColumns = prepared.unitColumns
        val unitRows = prepared.unitRows
        val bitDepthShift = prepared.bitDepthShift
        val damping = prepared.damping
        val cdefUnitMap: CdefUnitMap? =
            requireNotNullCompat(prepared.cdefUnitMap, "cdefUnitMap")
        val directionMap = CdefApplier.buildDirectionMap(
            checkedDecodedPlanes.lumaPlane(),
            checkedDecodedPlanes.bitDepth(),
            yStrengths,
            uvStrengths,
            cdefUnitMap!!,
            unitColumns,
            unitRows,
            unitColumns * CDEF_UNIT_SIZE,
            unitRows * CDEF_UNIT_SIZE,
            checkedDecodedPlanes.hasChroma(),
            CdefWorkspace()
        )

        val lumaPlane = CdefApplier.applyPlane(
            checkedDecodedPlanes.lumaPlane(),
            bitDepthShift,
            damping,
            yStrengths,
            cdefUnitMap,
            directionMap,
            unitColumns,
            unitRows,
            CDEF_UNIT_SIZE,
            CDEF_UNIT_SIZE,
            true,
            false
        )
        var chromaUPlane = checkedDecodedPlanes.chromaUPlane()
        var chromaVPlane = checkedDecodedPlanes.chromaVPlane()
        if (checkedDecodedPlanes.hasChroma()) {
            val chromaUnitWidth = max(
                1,
                CDEF_UNIT_SIZE shr chromaSubsamplingX(checkedDecodedPlanes.chromaFormat())
            )
            val chromaUnitHeight = max(
                1,
                CDEF_UNIT_SIZE shr chromaSubsamplingY(checkedDecodedPlanes.chromaFormat())
            )
            val i422Chroma = checkedDecodedPlanes.chromaFormat() == Av1ChromaFormat.YUV422
            chromaUPlane = CdefApplier.applyPlane(
                requireNotNullCompat(chromaUPlane, "chromaUPlane"),
                bitDepthShift,
                damping - 1,
                uvStrengths,
                cdefUnitMap,
                directionMap,
                unitColumns,
                unitRows,
                chromaUnitWidth,
                chromaUnitHeight,
                false,
                i422Chroma
            )
            chromaVPlane = CdefApplier.applyPlane(
                requireNotNullCompat(chromaVPlane, "chromaVPlane"),
                bitDepthShift,
                damping - 1,
                uvStrengths,
                cdefUnitMap,
                directionMap,
                unitColumns,
                unitRows,
                chromaUnitWidth,
                chromaUnitHeight,
                false,
                i422Chroma
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

    /** Returns whether any CDEF strength can change visible samples.
     * 
     * @param yStrengths the encoded luma CDEF strength table
     * @param uvStrengths the encoded chroma CDEF strength table
     * @param hasChroma whether the frame has chroma planes
     * @return whether any CDEF strength can change visible samples
     */
    private fun hasActiveStrengths(
        yStrengths: IntArray,
        uvStrengths: IntArray,
        hasChroma: Boolean
    ): Boolean {
        return hasActiveStrength(yStrengths) || (hasChroma && hasActiveStrength(uvStrengths))
    }

    /** Returns whether any encoded strength contains a non-zero primary or secondary component.
     * 
     * @param strengths the encoded CDEF strength table
     * @return whether any encoded strength is non-zero
     */
    private fun hasActiveStrength(strengths: IntArray): Boolean {
        for (strength in strengths) {
            if (strength != 0) {
                return true
            }
        }
        return false
    }

    /** Builds the luma CDEF-unit map from decoded partition-tree leaves.
     * 
     * @param syntaxDecodeResult the decoded block syntax that carries CDEF indices
     * @param unitColumns the luma CDEF-unit column count
     * @param unitRows the luma CDEF-unit row count
     * @return the row-major CDEF-unit map
     */
    private fun buildCdefUnitMap(
        syntaxDecodeResult: FrameSyntaxDecodeResult,
        unitColumns: Int,
        unitRows: Int
    ): CdefUnitMap {
        val cdefIndices = IntArray(unitColumns * unitRows)
        val nonSkipUnits = BooleanArray(unitColumns * unitRows)
        for (tileIndex in 0..<syntaxDecodeResult.tileCount()) {
            val frameLocalRoots = syntaxDecodeResult.tileRoots(tileIndex)
            for (root in frameLocalRoots) {
                fillCdefUnitMap(root, cdefIndices, nonSkipUnits, unitColumns, unitRows)
            }
        }
        return CdefUnitMap(cdefIndices, nonSkipUnits)
    }

    /** Fills CDEF-unit syntax state covered by one partition-tree node.
     * 
     * @param node the partition-tree node to visit
     * @param cdefIndices the mutable row-major CDEF index map
     * @param nonSkipUnits the mutable row-major non-skip map
     * @param unitColumns the luma CDEF-unit column count
     * @param unitRows the luma CDEF-unit row count
     */
    private fun fillCdefUnitMap(
        node: TilePartitionTreeReader.Node,
        cdefIndices: IntArray,
        nonSkipUnits: BooleanArray,
        unitColumns: Int,
        unitRows: Int
    ) {
        if (node is TilePartitionTreeReader.LeafNode) {
            var blockCdefIndex = node.header.cdefIndex()
            if (blockCdefIndex < 0) {
                blockCdefIndex = 0
            }
            val startX = node.header.position().x4 shl 2
            val startY = node.header.position().y4 shl 2
            val endX = startX + node.transformLayout.visibleWidthPixels()
            val endY = startY + node.transformLayout.visibleHeightPixels()
            val startUnitX = clamp(startX / CDEF_UNIT_SIZE, 0, unitColumns - 1)
            val startUnitY = clamp(startY / CDEF_UNIT_SIZE, 0, unitRows - 1)
            val endUnitX = clamp((endX + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE, 0, unitColumns)
            val endUnitY = clamp((endY + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE, 0, unitRows)
            val skip = node.header.skip()
            for (unitY in startUnitY..<endUnitY) {
                for (unitX in startUnitX..<endUnitX) {
                    val unitIndex = unitY * unitColumns + unitX
                    if (!skip) {
                        cdefIndices[unitIndex] = blockCdefIndex
                        nonSkipUnits[unitIndex] = true
                    } else if (!nonSkipUnits[unitIndex]) {
                        cdefIndices[unitIndex] = blockCdefIndex
                    }
                }
            }
            return
        }

        val partitionNode = node as PartitionNode
        for (childIndex in 0..<partitionNode.childCount()) {
            fillCdefUnitMap(
                partitionNode.child(childIndex),
                cdefIndices,
                nonSkipUnits,
                unitColumns,
                unitRows
            )
        }
    }

    /** Builds luma-derived direction and variance state for each CDEF unit.
     * 
     * @param lumaPlane the decoded luma plane
     * @param bitDepth the decoded bit depth
     * @param yStrengths the encoded luma CDEF strength table
     * @param uvStrengths the encoded chroma CDEF strength table
     * @param cdefUnitMap the row-major CDEF-unit syntax map
     * @param unitColumns the luma CDEF-unit column count
     * @param unitRows the luma CDEF-unit row count
     * @param processingWidth the CDEF-grid-aligned luma processing width
     * @param processingHeight the CDEF-grid-aligned luma processing height
     * @param hasChroma whether the frame has chroma planes
     * @param workspace the reusable per-frame CDEF workspace
     * @return the row-major luma-derived CDEF direction map
     */
    private fun buildDirectionMap(
        lumaPlane: PaddedPlane,
        bitDepth: Int,
        yStrengths: IntArray,
        uvStrengths: IntArray,
        cdefUnitMap: CdefUnitMap,
        unitColumns: Int,
        unitRows: Int,
        processingWidth: Int,
        processingHeight: Int,
        hasChroma: Boolean,
        workspace: CdefWorkspace
    ): CdefDirectionMap {
        val directions = IntArray(unitColumns * unitRows)
        val variances = IntArray(unitColumns * unitRows)
        val bitDepthShift = bitDepth - 8
        for (unitY in 0..<unitRows) {
            val startY = unitY * CDEF_UNIT_SIZE
            if (startY >= lumaPlane.height()) {
                continue
            }
            for (unitX in 0..<unitColumns) {
                val startX = unitX * CDEF_UNIT_SIZE
                if (startX >= lumaPlane.width()) {
                    continue
                }
                val unitIndex = unitY * unitColumns + unitX
                if (!cdefUnitMap.nonSkip(unitIndex)) {
                    continue
                }
                if (!requiresDirection(
                        yStrengths,
                        uvStrengths,
                        cdefUnitMap.cdefIndex(unitIndex),
                        hasChroma
                    )
                ) {
                    continue
                }
                detectDirection(
                    lumaPlane,
                    startX,
                    startY,
                    processingWidth,
                    processingHeight,
                    bitDepthShift,
                    workspace
                )
                directions[unitIndex] = workspace.detectedDirection
                variances[unitIndex] = workspace.detectedVariance
            }
        }
        return CdefDirectionMap(directions, variances)
    }

    /** Returns whether the selected CDEF strengths require luma direction detection.
     * 
     * @param yStrengths the encoded luma CDEF strength table
     * @param uvStrengths the encoded chroma CDEF strength table
     * @param cdefIndex the selected CDEF index
     * @param hasChroma whether the frame has chroma planes
     * @return whether the selected strengths contain an active primary filter
     */
    private fun requiresDirection(
        yStrengths: IntArray,
        uvStrengths: IntArray,
        cdefIndex: Int,
        hasChroma: Boolean
    ): Boolean {
        if (hasSelectedPrimaryStrength(yStrengths, cdefIndex)) {
            return true
        }
        return hasChroma && hasSelectedPrimaryStrength(uvStrengths, cdefIndex)
    }

    /** Returns whether one selected encoded strength has an active primary component.
     * 
     * @param strengths the encoded CDEF strength table
     * @param cdefIndex the selected CDEF index
     * @return whether the selected encoded strength has an active primary component
     */
    private fun hasSelectedPrimaryStrength(strengths: IntArray, cdefIndex: Int): Boolean {
        if (strengths.size == 0) {
            return false
        }
        return (strengthForIndex(strengths, cdefIndex) shr 2) != 0
    }

    /** Applies CDEF to one plane.
     * 
     * @param plane the source plane to filter
     * @param bitDepthShift the decoded bit-depth shift from 8-bit samples
     * @param damping the bit-depth-adjusted CDEF damping value
     * @param strengths the encoded strength table for the plane
     * @param cdefUnitMap the row-major luma CDEF-unit syntax map
     * @param directionMap the row-major luma-derived CDEF direction map
     * @param unitColumns the luma CDEF-unit column count
     * @param unitRows the luma CDEF-unit row count
     * @param unitWidth the plane-local CDEF-unit width
     * @param unitHeight the plane-local CDEF-unit height
     * @param luma whether this invocation filters the luma plane
     * @param i422Chroma whether this invocation filters an `YUV422` chroma plane
     * @return the filtered plane, or the original plane when all selected units are inactive
     */
    private fun applyPlane(
        plane: PaddedPlane,
        bitDepthShift: Int,
        damping: Int,
        strengths: IntArray,
        cdefUnitMap: CdefUnitMap,
        directionMap: CdefDirectionMap,
        unitColumns: Int,
        unitRows: Int,
        unitWidth: Int,
        unitHeight: Int,
        luma: Boolean,
        i422Chroma: Boolean
    ): PaddedPlane {
        if (!hasActiveStrength(strengths)) {
            return plane
        }
        var outputSamples: ShortArray? = null
        val maximumSample = (1 shl (bitDepthShift + 8)) - 1
        val processingWidth = min(unitColumns * unitWidth, plane.stride())
        val processingHeight = min(unitRows * unitHeight, plane.storageHeight())
        for (unitY in 0..<unitRows) {
            val planeStartY = unitY * unitHeight
            if (planeStartY >= processingHeight) {
                continue
            }
            val planeEndY = min(processingHeight, planeStartY + unitHeight)
            for (unitX in 0..<unitColumns) {
                val planeStartX = unitX * unitWidth
                if (planeStartX >= processingWidth) {
                    continue
                }
                val unitIndex = unitY * unitColumns + unitX
                if (!cdefUnitMap.nonSkip(unitIndex)) {
                    continue
                }
                val encodedStrength = strengthForIndex(strengths, cdefUnitMap.cdefIndex(unitIndex))
                var primaryStrength = decodePrimaryStrength(encodedStrength, bitDepthShift)
                val secondaryStrength = decodeSecondaryStrength(encodedStrength, bitDepthShift)
                if (primaryStrength == 0 && secondaryStrength == 0) {
                    continue
                }
                var direction = 0
                if (primaryStrength != 0) {
                    direction = directionMap.direction(unitIndex)
                    if (luma) {
                        primaryStrength =
                            adjustStrength(primaryStrength, directionMap.variance(unitIndex))
                    } else if (i422Chroma) {
                        direction = I422_UV_DIRECTIONS[direction]
                    }
                }
                if (primaryStrength == 0 && secondaryStrength == 0) {
                    continue
                }
                if (outputSamples == null) {
                    outputSamples = plane.samples()
                }
                filterUnit(
                    plane,
                    outputSamples,
                    planeStartX,
                    planeStartY,
                    min(processingWidth, planeStartX + unitWidth),
                    planeEndY,
                    processingWidth,
                    processingHeight,
                    max(0, damping),
                    primaryStrength,
                    secondaryStrength,
                    direction,
                    bitDepthShift,
                    maximumSample
                )
            }
        }
        return if (outputSamples != null)
            PaddedPlane.Companion.fromOwnedSamples(
                plane.width(),
                plane.height(),
                plane.stride(),
                outputSamples
            )
        else
            plane
    }

    /** Returns one encoded CDEF strength selected by the decoded block CDEF index.
     * 
     * @param strengths the encoded strength table
     * @param cdefIndex the decoded block CDEF index
     * @return the selected encoded CDEF strength
     */
    private fun strengthForIndex(strengths: IntArray, cdefIndex: Int): Int {
        check(cdefIndex >= 0) { "Active AV1 CDEF filtering requires a decoded CDEF index for every filtered unit" }
        check(cdefIndex < strengths.size) { "Decoded AV1 CDEF index exceeds the frame strength table: $cdefIndex" }
        return strengths[cdefIndex]
    }

    /** Decodes one packed primary CDEF strength and scales it for the frame bit depth.
     * 
     * @param strength the packed AV1 CDEF strength
     * @param bitDepthShift the decoded bit-depth shift from 8-bit samples
     * @return the decoded primary CDEF strength
     */
    private fun decodePrimaryStrength(strength: Int, bitDepthShift: Int): Int {
        return (strength shr 2) shl bitDepthShift
    }

    /** Decodes one packed secondary CDEF strength and scales it for the frame bit depth.
     * 
     * @param strength the packed AV1 CDEF strength
     * @param bitDepthShift the decoded bit-depth shift from 8-bit samples
     * @return the decoded secondary CDEF strength
     */
    private fun decodeSecondaryStrength(strength: Int, bitDepthShift: Int): Int {
        var secondary = strength and 3
        if (secondary == 3) {
            secondary++
        }
        return secondary shl bitDepthShift
    }

    /** Applies CDEF to one rectangular unit using decoded scalar strengths.
     * 
     * @param plane the source plane metadata
     * @param outputSamples the mutable output samples
     * @param startX the inclusive unit start X coordinate
     * @param startY the inclusive unit start Y coordinate
     * @param endX the exclusive unit end X coordinate
     * @param endY the exclusive unit end Y coordinate
     * @param processingWidth the exclusive CDEF-grid processing boundary in X
     * @param processingHeight the exclusive CDEF-grid processing boundary in Y
     * @param damping the strength-adjusted CDEF damping value
     * @param primaryStrength the decoded primary CDEF strength
     * @param secondaryStrength the decoded secondary CDEF strength
     * @param direction the luma-derived dominant CDEF direction
     * @param bitDepthShift the decoded bit-depth shift from 8-bit samples
     * @param maximumSample the maximum legal output sample value
     */
    private fun filterUnit(
        plane: PaddedPlane,
        outputSamples: ShortArray,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        processingWidth: Int,
        processingHeight: Int,
        damping: Int,
        primaryStrength: Int,
        secondaryStrength: Int,
        direction: Int,
        bitDepthShift: Int,
        maximumSample: Int
    ) {
        val primaryTap = 4 - ((primaryStrength shr bitDepthShift) and 1)
        val primaryDamping =
            if (primaryStrength > 0) max(0, damping - floorLog2(primaryStrength)) else
                0
        val secondaryDamping =
            if (secondaryStrength > 0) max(0, damping - floorLog2(secondaryStrength)) else
                0
        val clipToNeighborRange = primaryStrength > 0 && secondaryStrength > 0

        for (y in startY..<endY) {
            val rowOffset = y * plane.stride()
            for (x in startX..<endX) {
                val center = plane.storedSample(x, y)
                var sum = 0
                var minimum = center
                var maximum = center
                if (primaryStrength > 0) {
                    var tap = primaryTap
                    for (distanceIndex in 0..1) {
                        val step = FILTER_DIRECTIONS[direction][distanceIndex]
                        val positive = sampleOrMissing(
                            plane, x + step[0], y + step[1], processingWidth, processingHeight
                        )
                        val negative = sampleOrMissing(
                            plane, x - step[0], y - step[1], processingWidth, processingHeight
                        )
                        sum += tap * constrainSample(
                            positive,
                            center,
                            primaryStrength,
                            primaryDamping
                        )
                        sum += tap * constrainSample(
                            negative,
                            center,
                            primaryStrength,
                            primaryDamping
                        )
                        if (clipToNeighborRange) {
                            minimum = includeMinimum(minimum, positive)
                            minimum = includeMinimum(minimum, negative)
                            maximum = includeMaximum(maximum, positive)
                            maximum = includeMaximum(maximum, negative)
                        }
                        tap = (tap and 3) or 2
                    }
                }
                if (secondaryStrength > 0) {
                    val secondaryDirection0 = (direction + 2) and 7
                    val secondaryDirection1 = (direction + 6) and 7
                    for (distanceIndex in 0..1) {
                        val tap = 2 - distanceIndex
                        val step0 = FILTER_DIRECTIONS[secondaryDirection0][distanceIndex]
                        val step1 = FILTER_DIRECTIONS[secondaryDirection1][distanceIndex]
                        val positive0 = sampleOrMissing(
                            plane, x + step0[0], y + step0[1], processingWidth, processingHeight
                        )
                        val negative0 = sampleOrMissing(
                            plane, x - step0[0], y - step0[1], processingWidth, processingHeight
                        )
                        val positive1 = sampleOrMissing(
                            plane, x + step1[0], y + step1[1], processingWidth, processingHeight
                        )
                        val negative1 = sampleOrMissing(
                            plane, x - step1[0], y - step1[1], processingWidth, processingHeight
                        )
                        sum += tap * constrainSample(
                            positive0,
                            center,
                            secondaryStrength,
                            secondaryDamping
                        )
                        sum += tap * constrainSample(
                            negative0,
                            center,
                            secondaryStrength,
                            secondaryDamping
                        )
                        sum += tap * constrainSample(
                            positive1,
                            center,
                            secondaryStrength,
                            secondaryDamping
                        )
                        sum += tap * constrainSample(
                            negative1,
                            center,
                            secondaryStrength,
                            secondaryDamping
                        )
                        if (clipToNeighborRange) {
                            minimum = includeMinimum(includeMinimum(minimum, positive0), negative0)
                            minimum = includeMinimum(includeMinimum(minimum, positive1), negative1)
                            maximum = includeMaximum(includeMaximum(maximum, positive0), negative0)
                            maximum = includeMaximum(includeMaximum(maximum, positive1), negative1)
                        }
                    }
                }
                var filtered = center + ((sum - (if (sum < 0) 1 else 0) + 8) shr 4)
                if (clipToNeighborRange) {
                    filtered = clamp(filtered, minimum, maximum)
                }
                outputSamples[rowOffset + x] = clamp(filtered, 0, maximumSample).toShort()
            }
        }
    }

    /** Detects one luma unit's dominant direction using reusable partial sums.
     * 
     * @param plane the source plane metadata
     * @param startX the inclusive unit start X coordinate
     * @param startY the inclusive unit start Y coordinate
     * @param processingWidth the CDEF-grid-aligned luma processing width
     * @param processingHeight the CDEF-grid-aligned luma processing height
     * @param bitDepthShift the decoded bit-depth shift from 8-bit samples
     * @param workspace the reusable per-frame CDEF workspace that receives the result
     */
    private fun detectDirection(
        plane: PaddedPlane,
        startX: Int,
        startY: Int,
        processingWidth: Int,
        processingHeight: Int,
        bitDepthShift: Int,
        workspace: CdefWorkspace
    ) {
        val partialSumHv = workspace.partialSumHv
        val partialSumDiag = workspace.partialSumDiag
        val partialSumAlt = workspace.partialSumAlt
        for (row in partialSumHv) {
            fillCompat(row, 0)
        }
        for (row in partialSumDiag) {
            fillCompat(row, 0)
        }
        for (row in partialSumAlt) {
            fillCompat(row, 0)
        }

        for (y in 0..<CDEF_UNIT_SIZE) {
            for (x in 0..<CDEF_UNIT_SIZE) {
                val sample = samplePaddedOrClamped(
                    plane,
                    startX + x,
                    startY + y,
                    processingWidth,
                    processingHeight
                )
                val px = (sample shr bitDepthShift) - 128
                partialSumDiag[0][y + x] += px
                partialSumAlt[0][y + (x shr 1)] += px
                partialSumHv[0][y] += px
                partialSumAlt[1][3 + y - (x shr 1)] += px
                partialSumDiag[1][7 + y - x] += px
                partialSumAlt[2][3 - (y shr 1) + x] += px
                partialSumHv[1][x] += px
                partialSumAlt[3][(y shr 1) + x] += px
            }
        }

        val cost = workspace.directionCosts
        fillCompat(cost, 0)
        for (n in 0..7) {
            cost[2] += partialSumHv[0][n].toLong() * partialSumHv[0][n]
            cost[6] += partialSumHv[1][n].toLong() * partialSumHv[1][n]
        }
        cost[2] *= 105
        cost[6] *= 105

        for (n in 0..6) {
            val divisor = DIRECTION_DIVISORS[n]
            cost[0] += (partialSumDiag[0][n].toLong() * partialSumDiag[0][n]
                    + partialSumDiag[0][14 - n].toLong() * partialSumDiag[0][14 - n]) * divisor
            cost[4] += (partialSumDiag[1][n].toLong() * partialSumDiag[1][n]
                    + partialSumDiag[1][14 - n].toLong() * partialSumDiag[1][14 - n]) * divisor
        }
        cost[0] += partialSumDiag[0][7].toLong() * partialSumDiag[0][7] * 105
        cost[4] += partialSumDiag[1][7].toLong() * partialSumDiag[1][7] * 105

        for (n in 0..3) {
            val costIndex = n * 2 + 1
            for (m in 0..4) {
                cost[costIndex] += partialSumAlt[n][3 + m].toLong() * partialSumAlt[n][3 + m]
            }
            cost[costIndex] *= 105
            for (m in 0..2) {
                val divisor = DIRECTION_DIVISORS[2 * m + 1]
                cost[costIndex] += (partialSumAlt[n][m].toLong() * partialSumAlt[n][m]
                        + partialSumAlt[n][10 - m].toLong() * partialSumAlt[n][10 - m]) * divisor
            }
        }

        var bestDirection = 0
        var bestCost = cost[0]
        for (direction in 1..<cost.size) {
            if (cost[direction] > bestCost) {
                bestCost = cost[direction]
                bestDirection = direction
            }
        }
        val variance = (bestCost - cost[bestDirection xor 4]) shr 10
        workspace.detectedDirection = bestDirection
        workspace.detectedVariance = min(Int.MAX_VALUE.toLong(), variance).toInt()
    }

    /** Applies the luma primary-strength variance adjustment.
     * 
     * @param strength the bit-depth-scaled primary strength
     * @param variance the CDEF direction estimator variance
     * @return the adjusted primary strength
     */
    private fun adjustStrength(strength: Int, variance: Int): Int {
        if (variance == 0) {
            return 0
        }
        val scaledVariance = variance shr 6
        val shift = if (scaledVariance != 0) min(floorLog2(scaledVariance), 12) else 0
        return (strength * (4 + shift) + 8) shr 4
    }

    /** Applies the AV1 CDEF constraint function to one sample.
     * 
     * @param sample the neighbor sample, or `MISSING_SAMPLE`
     * @param center the current center sample
     * @param threshold the active CDEF threshold
     * @param damping the strength-adjusted CDEF damping value
     * @return the constrained signed delta
     */
    private fun constrainSample(sample: Int, center: Int, threshold: Int, damping: Int): Int {
        if (sample == MISSING_SAMPLE) {
            return 0
        }
        return constrain(sample - center, threshold, damping)
    }

    /** Applies the AV1 CDEF constraint function to one neighbor delta.
     * 
     * @param difference the signed neighbor-center delta
     * @param threshold the active CDEF threshold
     * @param damping the strength-adjusted CDEF damping value
     * @return the constrained signed delta
     */
    private fun constrain(difference: Int, threshold: Int, damping: Int): Int {
        if (threshold == 0) {
            return 0
        }
        val magnitude = abs(difference)
        val constrainedMagnitude = min(magnitude, max(0, threshold - (magnitude shr damping)))
        return if (difference < 0) -constrainedMagnitude else constrainedMagnitude
    }

    /** Returns the minimum after optionally including one non-missing neighbor sample.
     * 
     * @param current the current minimum
     * @param sample the candidate neighbor sample, or `MISSING_SAMPLE`
     * @return the updated minimum
     */
    private fun includeMinimum(current: Int, sample: Int): Int {
        return if (sample == MISSING_SAMPLE) current else min(current, sample)
    }

    /** Returns the maximum after optionally including one non-missing neighbor sample.
     * 
     * @param current the current maximum
     * @param sample the candidate neighbor sample, or `MISSING_SAMPLE`
     * @return the updated maximum
     */
    private fun includeMaximum(current: Int, sample: Int): Int {
        return if (sample == MISSING_SAMPLE) current else max(current, sample)
    }

    /** Returns one source sample or the AV1 missing-edge sentinel.
     * 
     * @param plane the source plane metadata
     * @param x the sample X coordinate
     * @param y the sample Y coordinate
     * @param processingWidth the exclusive CDEF-grid processing boundary in X
     * @param processingHeight the exclusive CDEF-grid processing boundary in Y
     * @return one source sample or `MISSING_SAMPLE`
     */
    private fun sampleOrMissing(
        plane: PaddedPlane,
        x: Int,
        y: Int,
        processingWidth: Int,
        processingHeight: Int
    ): Int {
        if (x < 0 || x >= processingWidth || y < 0 || y >= processingHeight) {
            return MISSING_SAMPLE
        }
        return plane.storedSample(x, y)
    }

    /** Returns one stored source sample with processing-boundary extension for direction estimation.
     * 
     * @param plane the source plane metadata
     * @param x the unclamped X coordinate
     * @param y the unclamped Y coordinate
     * @param processingWidth the available CDEF-grid processing width
     * @param processingHeight the available CDEF-grid processing height
     * @return one edge-extended source sample
     */
    private fun samplePaddedOrClamped(
        plane: PaddedPlane,
        x: Int,
        y: Int,
        processingWidth: Int,
        processingHeight: Int
    ): Int {
        val clampedX = clamp(x, 0, processingWidth - 1)
        val clampedY = clamp(y, 0, processingHeight - 1)
        return plane.storedSample(clampedX, clampedY)
    }

    /** Returns the number of luma CDEF units needed to cover one plane extent.
     * 
     * @param extent the luma plane extent in samples
     * @return the number of luma CDEF units
     */
    private fun cdefUnitCount(extent: Int): Int {
        return (extent + CDEF_UNIT_SIZE - 1) / CDEF_UNIT_SIZE
    }

    /** Returns the horizontal chroma subsampling shift for one chroma format.
     * 
     * @param chromaFormat the decoded chroma format
     * @return the horizontal chroma subsampling shift
     */
    private fun chromaSubsamplingX(chromaFormat: Av1ChromaFormat): Int {
        return when (requireNotNullCompat(chromaFormat, "chromaFormat")) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
        }
    }

    /** Returns the vertical chroma subsampling shift for one chroma format.
     * 
     * @param chromaFormat the decoded chroma format
     * @return the vertical chroma subsampling shift
     */
    private fun chromaSubsamplingY(chromaFormat: Av1ChromaFormat): Int {
        return when (requireNotNullCompat(chromaFormat, "chromaFormat")) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420 -> 1
        }
    }

    /** Returns the base-2 floor logarithm of a positive integer.
     * 
     * @param value the positive integer
     * @return the base-2 floor logarithm
     */
    private fun floorLog2(value: Int): Int {
        return 31 - value.countLeadingZeroBits()
    }

    /** Clamps one signed value into one inclusive integer range.
     * 
     * @param value the candidate value
     * @param minimum the inclusive lower bound
     * @param maximum the inclusive upper bound
     * @return the clamped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(maximum, value))
    }

    /** Compact CDEF state that remains valid after the complete frame syntax tree is released. */
    class PreparedApplication(
        decodedPlanes: DecodedSurface,
        yStrengths: IntArray,
        uvStrengths: IntArray,
        unitColumns: Int,
        unitRows: Int,
        bitDepthShift: Int,
        damping: Int,
        cdefUnitMap: CdefUnitMap?
    ) {
        /** The expected decoded bit depth. */
        val bitDepth: Int

        /** The expected chroma format. */
        val chromaFormat: Av1ChromaFormat

        /** The expected coded luma width. */
        val codedWidth: Int

        /** The expected coded luma height. */
        val codedHeight: Int

        /** The encoded luma strength table. */
        val yStrengths: IntArray

        /** The encoded chroma strength table. */
        val uvStrengths: IntArray

        /** The luma CDEF-unit column count. */
        val unitColumns: Int

        /** The luma CDEF-unit row count. */
        val unitRows: Int

        /** The decoded bit-depth shift from eight-bit samples. */
        val bitDepthShift: Int

        /** The bit-depth-adjusted luma damping value. */
        val damping: Int

        /** The compact decoded CDEF-unit syntax, or `null` when CDEF is inactive. */
        val cdefUnitMap: CdefUnitMap?

        init {
            val checkedDecodedPlanes =
                requireNotNullCompat(decodedPlanes, "decodedPlanes")
            this.bitDepth = checkedDecodedPlanes.bitDepth()
            this.chromaFormat = checkedDecodedPlanes.chromaFormat()
            this.codedWidth = checkedDecodedPlanes.codedWidth()
            this.codedHeight = checkedDecodedPlanes.codedHeight()
            this.yStrengths = requireNotNullCompat(yStrengths, "yStrengths")
            this.uvStrengths = requireNotNullCompat(uvStrengths, "uvStrengths")
            this.unitColumns = unitColumns
            this.unitRows = unitRows
            this.bitDepthShift = bitDepthShift
            this.damping = damping
            this.cdefUnitMap = cdefUnitMap
        }

        /** Returns whether this operation applies any CDEF filtering.
         * 
         * @return whether CDEF is active
         */
        fun active(): Boolean {
            return cdefUnitMap != null
        }

        /** Verifies that an operation is applied to the surface for which it was prepared.
         * 
         * @param decodedPlanes the candidate loop-filtered surface
         */
        fun validateSurface(decodedPlanes: DecodedSurface) {
            val checkedDecodedPlanes =
                requireNotNullCompat(decodedPlanes, "decodedPlanes")
            require(!(checkedDecodedPlanes.bitDepth() != bitDepth || checkedDecodedPlanes.chromaFormat() != chromaFormat || checkedDecodedPlanes.codedWidth() != codedWidth || checkedDecodedPlanes.codedHeight() != codedHeight)) { "Prepared CDEF state does not match decoded surface" }
        }

        companion object {
            /** Creates an inactive prepared operation for one decoded surface.
             * 
             * @param decodedPlanes the surface for which CDEF is inactive
             * @return the inactive prepared operation
             */
            fun inactive(decodedPlanes: DecodedSurface): PreparedApplication {
                return PreparedApplication(
                    decodedPlanes,
                    IntArray(0),
                    IntArray(0),
                    0,
                    0,
                    0,
                    0,
                    null
                )
            }
        }
    }

    /** Reusable per-frame storage for CDEF direction detection. */
    class CdefWorkspace
    /** Creates one empty reusable CDEF workspace. */
    {
        /** Horizontal and vertical direction partial sums. */
        val partialSumHv: Array<IntArray> = Array<IntArray>(2) { IntArray(8) }

        /** Diagonal direction partial sums. */
        val partialSumDiag: Array<IntArray> = Array<IntArray>(2) { IntArray(15) }

        /** Near-horizontal and near-vertical direction partial sums. */
        val partialSumAlt: Array<IntArray> = Array<IntArray>(4) { IntArray(11) }

        /** Direction costs accumulated from the partial sums. */
        val directionCosts = LongArray(8)

        /** The dominant direction produced by the most recent direction search. */
        var detectedDirection = 0

        /** The directional variance produced by the most recent direction search. */
        var detectedVariance = 0
    }

    /** Row-major decoded CDEF syntax state for luma CDEF units.
     * 
     * The caller must relinquish both arrays after construction.
     * 
     * @param cdefIndices the exclusively owned CDEF index array for each luma CDEF unit
     * @param nonSkipUnits the exclusively owned non-skip array for each luma CDEF unit
     */
    data class CdefUnitMap(
        val cdefIndices: IntArray,
        val nonSkipUnits: BooleanArray
    ) {
        /** Returns the decoded CDEF index for one luma CDEF unit.
         * 
         * @param unitIndex the row-major CDEF unit index
         * @return the decoded CDEF index for one luma CDEF unit
         */
        fun cdefIndex(unitIndex: Int): Int {
            return cdefIndices[unitIndex]
        }

        /** Returns whether one luma CDEF unit contains at least one non-skipped block.
         * 
         * @param unitIndex the row-major CDEF unit index
         * @return whether one luma CDEF unit contains at least one non-skipped block
         */
        fun nonSkip(unitIndex: Int): Boolean {
            return nonSkipUnits[unitIndex]
        }

        /** Creates one row-major decoded CDEF syntax map. */
        init {
            requireNotNullCompat(
                cdefIndices, "cdefIndices"
            )
            requireNotNullCompat(
                nonSkipUnits, "nonSkipUnits"
            )
        }
    }

    /** Row-major luma-derived CDEF direction state.
     * 
     * The caller must relinquish both arrays after construction.
     * 
     * @param directions the exclusively owned dominant-direction array for each luma CDEF unit
     * @param variances the exclusively owned directional-variance array for each luma CDEF unit
     */
    data class CdefDirectionMap(
        val directions: IntArray,
        val variances: IntArray
    ) {
        /** Returns the dominant direction for one luma CDEF unit.
         * 
         * @param unitIndex the row-major CDEF unit index
         * @return the dominant direction for one luma CDEF unit
         */
        fun direction(unitIndex: Int): Int {
            return directions[unitIndex]
        }

        /** Returns the directional variance for one luma CDEF unit.
         * 
         * @param unitIndex the row-major CDEF unit index
         * @return the directional variance for one luma CDEF unit
         */
        fun variance(unitIndex: Int): Int {
            return variances[unitIndex]
        }

        /** Creates one row-major luma-derived CDEF direction map. */
        init {
            requireNotNullCompat(
                directions, "directions"
            )
            requireNotNullCompat(
                variances, "variances"
            )
        }
    }
}
