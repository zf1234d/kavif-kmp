// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationType


/** Reads AV1 loop-restoration unit syntax that is signaled before each superblock partition tree. */
internal class TileLoopRestorationReader(
    tileContext: TileDecodeContext,
    syntaxReader: TileSyntaxReader
) {
    /** The tile-local decode state. */
    private val tileContext: TileDecodeContext

    /** The typed entropy reader. */
    private val syntaxReader: TileSyntaxReader

    /** The active frame header. */
    private val frameHeader: FrameHeader

    /** The active decoded chroma format. */
    private val chromaFormat: Av1ChromaFormat

    /** The frame-level restoration types. */
    private val frameTypes: Array<RestorationType>

    /** The per-plane, per-bitstream-axis, per-coefficient Wiener references.
     * 
     * The bitstream axis order is vertical followed by horizontal. */
    private val referenceWienerCoefficients: Array<Array<IntArray>>

    /** The per-plane self-guided projection references. */
    private val referenceSelfGuidedProjectionCoefficients: Array<IntArray>

    init {
        this.tileContext = requireNotNullCompat(tileContext, "tileContext")
        this.syntaxReader = requireNotNullCompat(syntaxReader, "syntaxReader")
        this.frameHeader = tileContext.frameHeader()
        this.chromaFormat = tileContext.sequenceHeader().colorConfig().chromaFormat
        this.frameTypes = frameHeader.restoration().types()
        this.referenceWienerCoefficients =
            Array(3) { Array(2) { IntArray(3) } }
        this.referenceSelfGuidedProjectionCoefficients = Array(3) { IntArray(2) }
        for (plane in 0..2) {
            for (pass in 0..1) {
                arraycopy(
                    WIENER_TAPS_MID,
                    0,
                    referenceWienerCoefficients[plane][pass],
                    0,
                    WIENER_TAPS_MID.size
                )
            }
            arraycopy(
                SELF_GUIDED_PROJECTION_MID,
                0,
                referenceSelfGuidedProjectionCoefficients[plane],
                0,
                SELF_GUIDED_PROJECTION_MID.size
            )
        }
    }

    /** Reads all restoration units intersected by one superblock.
     * 
     * @param tileRelativePosition the superblock position in tile-relative 4x4 units
     * @param width4 the superblock width in 4x4 units
     * @param height4 the superblock height in 4x4 units
     */
    fun readSuperblock(tileRelativePosition: BlockPosition, width4: Int, height4: Int) {
        val checkedPosition =
            requireNotNullCompat(tileRelativePosition, "tileRelativePosition")
        if (frameHeader.allowIntrabc()) {
            return
        }
        val frameRow4 = (tileContext.startY() shr 2) + checkedPosition.y4
        val frameColumn4 = (tileContext.startX() shr 2) + checkedPosition.x4
        for (plane in frameTypes.indices) {
            if (frameTypes[plane] == RestorationType.NONE) {
                continue
            }
            val subX = if (plane == 0) 0 else chromaSubsamplingX()
            val subY = if (plane == 0) 0 else chromaSubsamplingY()
            val unitSize = 1 shl (if (plane == 0)
                frameHeader.restoration().unitSizeLog2Y()
            else
                frameHeader.restoration().unitSizeLog2Uv())
            val unitRows = tileContext.restorationUnitMap().rows(plane)
            val unitColumns = tileContext.restorationUnitMap().columns(plane)
            val unitRowStart: Int = divideCeil(frameRow4 * (MI_SIZE shr subY), unitSize)
            val unitRowEnd =
                min(unitRows, divideCeil((frameRow4 + height4) * (MI_SIZE shr subY), unitSize))
            val numerator: Int
            val denominator: Int
            if (frameHeader.superResolution().enabled()) {
                numerator =
                    (MI_SIZE shr subX) * frameHeader.superResolution().widthScaleDenominator()
                denominator = unitSize * SUPERRES_NUMERATOR
            } else {
                numerator = MI_SIZE shr subX
                denominator = unitSize
            }
            val unitColumnStart: Int = divideCeil(frameColumn4 * numerator, denominator)
            val unitColumnEnd =
                min(unitColumns, divideCeil((frameColumn4 + width4) * numerator, denominator))
            for (unitRow in unitRowStart..<unitRowEnd) {
                for (unitColumn in unitColumnStart..<unitColumnEnd) {
                    if (!tileContext.restorationUnitMap().hasUnit(plane, unitRow, unitColumn)) {
                        tileContext.restorationUnitMap()
                            .setUnit(plane, unitRow, unitColumn, readUnit(plane))
                    }
                }
            }
        }
    }

    /** Reads one restoration unit for a plane.
     * 
     * @param plane the plane index
     * @return one decoded restoration unit
     */
    private fun readUnit(plane: Int): RestorationUnit {
        val unitType = syntaxReader.readRestorationUnitType(frameTypes[plane])
        if (unitType == RestorationType.NONE) {
            return RestorationUnit.Companion.none()
        }
        if (unitType == RestorationType.WIENER) {
            return readWienerUnit(plane)
        }
        if (unitType == RestorationType.SELF_GUIDED) {
            val set = syntaxReader.readUnsignedBits(4)
            return readSelfGuidedUnit(plane, set)
        }
        throw IllegalStateException("Unsupported concrete restoration unit type: $unitType")
    }

    /** Reads one Wiener coefficient table into the plane reference state.
     * 
     * @param plane the plane index
     * @return one decoded Wiener restoration unit
     */
    private fun readWienerUnit(plane: Int): RestorationUnit {
        val bitstreamCoefficients = referenceWienerCoefficients[plane]
        for (pass in 0..1) {
            val firstCoefficient = if (plane == 0) 0 else 1
            if (firstCoefficient == 1) {
                bitstreamCoefficients[pass][0] = 0
            }
            for (coefficient in firstCoefficient..2) {
                val value = syntaxReader.readWienerCoefficient(
                    coefficient,
                    bitstreamCoefficients[pass][coefficient]
                )
                bitstreamCoefficients[pass][coefficient] = value
            }
        }
        return RestorationUnit.Companion.wiener(
            bitstreamCoefficients[1][0],
            bitstreamCoefficients[1][1],
            bitstreamCoefficients[1][2],
            bitstreamCoefficients[0][0],
            bitstreamCoefficients[0][1],
            bitstreamCoefficients[0][2]
        )
    }

    /** Reads one self-guided projection coefficient pair into the plane reference state.
     * 
     * @param plane the plane index
     * @param set the self-guided parameter set
     * @return one decoded self-guided restoration unit
     */
    private fun readSelfGuidedUnit(plane: Int, set: Int): RestorationUnit {
        val checkedSet = checkIndexCompat(set, SELF_GUIDED_PARAMS.size)
        val coefficients = referenceSelfGuidedProjectionCoefficients[plane]
        for (coefficient in 0..1) {
            val radius: Int = SELF_GUIDED_PARAMS[checkedSet][coefficient * 2]
            val value: Int
            if (radius != 0) {
                value = syntaxReader.readSelfGuidedProjectionCoefficient(
                    coefficient,
                    coefficients[coefficient]
                )
            } else if (coefficient == 1) {
                value = syntaxReader.derivedSelfGuidedProjectionCoefficient1(
                    coefficients[0]
                )
            } else {
                value = 0
            }
            coefficients[coefficient] = value
        }
        return RestorationUnit.Companion.selfGuided(checkedSet, coefficients[0], coefficients[1])
    }

    /** Returns the chroma horizontal subsampling shift for the active chroma format.
     * 
     * @return the chroma horizontal subsampling shift for the active chroma format
     */
    private fun chromaSubsamplingX(): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
        }
    }

    /** Returns the chroma vertical subsampling shift for the active chroma format.
     * 
     * @return the chroma vertical subsampling shift for the active chroma format
     */
    private fun chromaSubsamplingY(): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420 -> 1
        }
    }

    companion object {
        /** The luma sample size represented by one mode-info unit. */
        private const val MI_SIZE = 4

        /** The super-resolution numerator used by AV1. */
        private const val SUPERRES_NUMERATOR = 8

        /** The initial Wiener coefficient references for each pass. */
        val WIENER_TAPS_MID: IntArray = intArrayOf(3, -7, 15)

        /** The initial self-guided projection coefficient references. */
        val SELF_GUIDED_PROJECTION_MID: IntArray = intArrayOf(-32, 31)

        /** The AV1 self-guided restoration parameter table in `{r0, e0, r1, e1}` order. */
        val SELF_GUIDED_PARAMS: Array<IntArray> = arrayOf<IntArray>(
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

        /** Divides two positive integers with ceiling rounding.
         * 
         * @param numerator the numerator
         * @param denominator the positive denominator
         * @return the ceiling-rounded quotient
         */
        fun divideCeil(numerator: Int, denominator: Int): Int {
            return (numerator + denominator - 1) / denominator
        }
    }
}
