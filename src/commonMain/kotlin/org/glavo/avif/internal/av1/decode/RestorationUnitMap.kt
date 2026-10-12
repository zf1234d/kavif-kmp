// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.model.FrameAssembly
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationType


/** Frame-level storage for decoded loop-restoration units.
 * 
 * The map is indexed by plane, unit row, and unit column. Inactive frame-level planes may have
 * zero-sized maps, while active planes require an explicitly decoded unit at every used location. */
class RestorationUnitMap private constructor(columnsByPlane: IntArray, rowsByPlane: IntArray) {
    /** The unit column count for each plane. */
    private val columnsByPlane: IntArray

    /** The unit row count for each plane. */
    private val rowsByPlane: IntArray

    /** The flat unit storage for each plane. */
    private val unitsByPlane: Array<Array<RestorationUnit?>>

    init {
        this.columnsByPlane = requireNotNullCompat(columnsByPlane, "columnsByPlane")
            .copyOf(columnsByPlane.size)
        this.rowsByPlane =
            requireNotNullCompat(rowsByPlane, "rowsByPlane").copyOf(rowsByPlane.size)
        require(!(this.columnsByPlane.size != 3 || this.rowsByPlane.size != 3)) { "Restoration unit dimensions must cover exactly three planes" }
        this.unitsByPlane = Array(3) { plane ->
            require(!(this.columnsByPlane[plane] < 0 || this.rowsByPlane[plane] < 0)) { "Restoration unit dimensions must not be negative" }
            arrayOfNullsCompat<RestorationUnit?>(this.columnsByPlane[plane] * this.rowsByPlane[plane])
        }
    }

    /** Returns a deep copy of this map.
     * 
     * @return a deep copy of this map
     */
    fun copy(): RestorationUnitMap {
        val copy = RestorationUnitMap(columnsByPlane, rowsByPlane)
        for (plane in unitsByPlane.indices) {
            arraycopy(
                unitsByPlane[plane],
                0,
                copy.unitsByPlane[plane],
                0,
                unitsByPlane[plane].size
            )
        }
        return copy
    }

    /** Merges decoded units from another map into this map.
     * 
     * @param source the source map to merge
     */
    fun mergeFrom(source: RestorationUnitMap) {
        val checkedSource = requireNotNullCompat(source, "source")
        for (plane in 0..2) {
            require(
                !(checkedSource.columnsByPlane[plane] != columnsByPlane[plane]
                        || checkedSource.rowsByPlane[plane] != rowsByPlane[plane])
            ) { "Restoration unit map dimensions do not match" }
            for (index in unitsByPlane[plane].indices) {
                val unit = checkedSource.unitsByPlane[plane][index]
                if (unit != null) {
                    unitsByPlane[plane][index] = unit
                }
            }
        }
    }

    /** Returns the unit column count for one plane.
     * 
     * @param plane the plane index
     * @return the unit column count for one plane
     */
    fun columns(plane: Int): Int {
        return columnsByPlane[checkIndexCompat(plane, columnsByPlane.size)]
    }

    /** Returns the unit row count for one plane.
     * 
     * @param plane the plane index
     * @return the unit row count for one plane
     */
    fun rows(plane: Int): Int {
        return rowsByPlane[checkIndexCompat(plane, rowsByPlane.size)]
    }

    /** Returns one decoded restoration unit.
     * 
     * @param plane the plane index
     * @param row the restoration-unit row
     * @param column the restoration-unit column
     * @return one decoded restoration unit, or `null` when not decoded
     */
    fun unit(plane: Int, row: Int, column: Int): RestorationUnit? {
        return unitsByPlane[checkIndexCompat(plane, unitsByPlane.size)][index(
            plane,
            row,
            column
        )]
    }

    /** Stores one decoded restoration unit.
     * 
     * @param plane the plane index
     * @param row the restoration-unit row
     * @param column the restoration-unit column
     * @param unit the decoded restoration unit
     */
    fun setUnit(plane: Int, row: Int, column: Int, unit: RestorationUnit) {
        unitsByPlane[checkIndexCompat(plane, unitsByPlane.size)][index(plane, row, column)] =
            requireNotNullCompat(unit, "unit")
    }

    /** Returns whether a unit location has already been decoded.
     * 
     * @param plane the plane index
     * @param row the restoration-unit row
     * @param column the restoration-unit column
     * @return whether a unit location has already been decoded
     */
    fun hasUnit(plane: Int, row: Int, column: Int): Boolean {
        return unit(plane, row, column) != null
    }

    /** Returns the flat index for one unit coordinate.
     * 
     * @param plane the plane index
     * @param row the restoration-unit row
     * @param column the restoration-unit column
     * @return the flat index for one unit coordinate
     */
    private fun index(plane: Int, row: Int, column: Int): Int {
        val columns = columnsByPlane[plane]
        val rows = rowsByPlane[plane]
        if (column < 0 || column >= columns) {
            throw IndexOutOfBoundsException("Restoration unit column out of range: $column")
        }
        if (row < 0 || row >= rows) {
            throw IndexOutOfBoundsException("Restoration unit row out of range: $row")
        }
        return row * columns + column
    }

    companion object {
        /** Creates an empty map matching one frame assembly.
         * 
         * @param assembly the frame assembly that owns the restoration syntax
         * @return an empty map matching one frame assembly
         */
        fun createEmpty(assembly: FrameAssembly): RestorationUnitMap {
            val checkedAssembly = requireNotNullCompat(assembly, "assembly")
            val frameHeader = checkedAssembly.frameHeader()
            val chromaFormat = checkedAssembly.sequenceHeader().colorConfig().chromaFormat
            val columns = IntArray(3)
            val rows = IntArray(3)
            for (plane in 0..2) {
                if (plane > 0 && chromaFormat == Av1ChromaFormat.MONOCHROME) {
                    continue
                }
                if (frameHeader.restoration().types()[plane] == RestorationType.NONE) {
                    continue
                }
                val unitSize = 1 shl (if (plane == 0)
                    frameHeader.restoration().unitSizeLog2Y()
                else
                    frameHeader.restoration().unitSizeLog2Uv())
                val width = if (plane == 0)
                    frameHeader.frameSize().upscaledWidth()
                else
                    chromaWidth(chromaFormat, frameHeader.frameSize().upscaledWidth())
                val height = if (plane == 0)
                    frameHeader.frameSize().height()
                else
                    chromaHeight(chromaFormat, frameHeader.frameSize().height())
                columns[plane] = countUnits(unitSize, width)
                rows[plane] = countUnits(unitSize, height)
            }
            return RestorationUnitMap(columns, rows)
        }

        /** Counts restoration units for one plane dimension.
         * 
         * @param unitSize the restoration unit size in samples
         * @param frameSize the plane dimension in samples
         * @return the number of units needed for the dimension
         */
        fun countUnits(unitSize: Int, frameSize: Int): Int {
            return max((frameSize + (unitSize shr 1)) / unitSize, 1)
        }

        /** Returns one chroma plane width for a luma width.
         * 
         * @param chromaFormat the decoded chroma format
         * @param lumaWidth the luma width in samples
         * @return one chroma plane width for a luma width
         */
        fun chromaWidth(chromaFormat: Av1ChromaFormat, lumaWidth: Int): Int {
            return when (chromaFormat) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> (lumaWidth + 1) shr 1
                Av1ChromaFormat.YUV444 -> lumaWidth
            }
        }

        /** Returns one chroma plane height for a luma height.
         * 
         * @param chromaFormat the decoded chroma format
         * @param lumaHeight the luma height in samples
         * @return one chroma plane height for a luma height
         */
        fun chromaHeight(chromaFormat: Av1ChromaFormat, lumaHeight: Int): Int {
            return when (chromaFormat) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420 -> (lumaHeight + 1) shr 1
                Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> lumaHeight
            }
        }
    }
}
