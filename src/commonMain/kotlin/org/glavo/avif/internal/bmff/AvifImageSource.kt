// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable standalone or grid-derived AV1 image source selected from an AVIF item graph.
 * 
 * Each payload retains the AV1 operating point selected by the matching `a1op` item property and
 * the spatial-layer selection from `lsel`. Standalone sources contain one payload; grid sources
 * contain one payload per cell in row-major order. */
class AvifImageSource private constructor(
    payloads: Array<AvifPayload>,
    operatingPoints: IntArray,
    selectedSpatialLayers: IntArray,
    itemWidths: IntArray,
    itemHeights: IntArray,
    grid: Boolean,
    rows: Int,
    columns: Int,
    outputWidth: Int,
    outputHeight: Int
) {
    /** Immutable AV1 payloads in source order. */
    private val payloads: Array<AvifPayload>

    /** Operating-point indices corresponding to the payloads. */
    private val operatingPoints: IntArray

    /** Selected spatial-layer identifiers corresponding to the payloads. */
    private val selectedSpatialLayers: IntArray

    /** `ispe` widths corresponding to the payloads. */
    private val itemWidths: IntArray

    /** `ispe` heights corresponding to the payloads. */
    private val itemHeights: IntArray
    /** Returns whether this source is grid-derived.
     * 
     * @return whether this source is grid-derived
     */
    /** Whether this source is a grid-derived image. */
    val isGrid: Boolean

    /** The source row count. */
    private val rows: Int

    /** The source column count. */
    private val columns: Int

    /** The reconstructed output width in luma samples. */
    private val outputWidth: Int

    /** The reconstructed output height in luma samples. */
    private val outputHeight: Int

    init {
        requireNotNullCompat(payloads, "payloads")
        requireNotNullCompat(operatingPoints, "operatingPoints")
        requireNotNullCompat(
            selectedSpatialLayers,
            "selectedSpatialLayers"
        )
        requireNotNullCompat(itemWidths, "itemWidths")
        requireNotNullCompat(itemHeights, "itemHeights")
        require(!(rows <= 0 || columns <= 0)) { "Image source row and column counts must be positive" }
        require(!(outputWidth <= 0 || outputHeight <= 0)) { "Image source output dimensions must be positive" }
        require(rows.toLong() * columns == payloads.size.toLong()) { "Image source payload count does not match rows * columns" }
        require(operatingPoints.size == payloads.size) { "Operating-point count does not match payload count" }
        require(selectedSpatialLayers.size == payloads.size) { "Spatial-layer selection count does not match payload count" }
        require(!(itemWidths.size != payloads.size || itemHeights.size != payloads.size)) { "Item dimension count does not match payload count" }
        require(!(!grid && (rows != 1 || columns != 1 || payloads.size != 1))) { "Standalone image sources must contain exactly one payload" }
        for (i in operatingPoints.indices) {
            requireNotNullCompat(payloads[i], "payloads[$i]")
            val operatingPoint = operatingPoints[i]
            require(operatingPoint in 0..31) { "Operating point out of range at index $i: $operatingPoint" }
            val selectedSpatialLayer = selectedSpatialLayers[i]
            require(selectedSpatialLayer in HIGHEST_SPATIAL_LAYER..3) { "Selected spatial layer out of range at index $i: $selectedSpatialLayer" }
            require(!(itemWidths[i] <= 0 || itemHeights[i] <= 0)) {
                ("Item dimensions must be positive at index $i: "
                        + itemWidths[i] + "x" + itemHeights[i])
            }
        }
        this.payloads = payloads.copyOf()
        this.operatingPoints = operatingPoints.copyOf()
        this.selectedSpatialLayers = selectedSpatialLayers.copyOf()
        this.itemWidths = itemWidths.copyOf()
        this.itemHeights = itemHeights.copyOf()
        this.isGrid = grid
        this.rows = rows
        this.columns = columns
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
    }

    /** Returns one AV1 payload descriptor.
     * 
     * @param index the zero-based payload or cell index
     * @return the immutable payload descriptor
     */
    fun payload(index: Int): AvifPayload {
        return payloads[index]
    }

    /** Returns all AV1 payload descriptors.
     * 
     * @return a shallow copy of the immutable payload descriptors
     */
    fun payloads(): Array<AvifPayload> {
        return payloads.copyOf()
    }

    /** Returns the operating-point index for one payload.
     * 
     * @param index the zero-based payload or cell index
     * @return the selected AV1 operating-point index
     */
    fun operatingPoint(index: Int): Int {
        return operatingPoints[index]
    }

    /** Returns the selected spatial layer for one payload.
     * 
     * @param index the zero-based payload or cell index
     * @return the spatial-layer identifier, or [HIGHEST_SPATIAL_LAYER]
     */
    fun selectedSpatialLayer(index: Int): Int {
        return selectedSpatialLayers[index]
    }

    /** Returns the `ispe` width associated with one payload.
     * 
     * @param index the zero-based payload or cell index
     * @return the item width in luma samples
     */
    fun itemWidth(index: Int): Int {
        return itemWidths[index]
    }

    /** Returns the `ispe` height associated with one payload.
     * 
     * @param index the zero-based payload or cell index
     * @return the item height in luma samples
     */
    fun itemHeight(index: Int): Int {
        return itemHeights[index]
    }

    /** Returns the source row count.
     * 
     * @return one for a standalone item or the grid row count
     */
    fun rows(): Int {
        return rows
    }

    /** Returns the source column count.
     * 
     * @return one for a standalone item or the grid column count
     */
    fun columns(): Int {
        return columns
    }

    /** Returns the reconstructed output width.
     * 
     * @return the output width in luma samples
     */
    fun outputWidth(): Int {
        return outputWidth
    }

    /** Returns the reconstructed output height.
     * 
     * @return the output height in luma samples
     */
    fun outputHeight(): Int {
        return outputHeight
    }

    companion object {
        /** Selects the highest output spatial layer in an AV1 image item. */
        const val HIGHEST_SPATIAL_LAYER: Int = -1

        /** Creates a standalone AV1 item source over an existing payload descriptor.
         * 
         * @param payload the AV1 item payload descriptor
         * @param operatingPoint the selected AV1 operating-point index
         * @param selectedSpatialLayer the selected spatial-layer identifier, or [HIGHEST_SPATIAL_LAYER]
         * @param outputWidth the reconstructed output width
         * @param outputHeight the reconstructed output height
         * @return the immutable standalone source
         */
        fun item(
            payload: AvifPayload,
            operatingPoint: Int,
            selectedSpatialLayer: Int,
            outputWidth: Int,
            outputHeight: Int
        ): AvifImageSource {
            return AvifImageSource(
                arrayOf<AvifPayload>(requireNotNullCompat(payload, "payload")),
                intArrayOf(operatingPoint),
                intArrayOf(selectedSpatialLayer),
                intArrayOf(outputWidth),
                intArrayOf(outputHeight),
                false,
                1,
                1,
                outputWidth,
                outputHeight
            )
        }

        /** Creates a grid-derived AV1 image source over existing payload descriptors.
         * 
         * @param cellPayloads the cell AV1 payload descriptors in row-major order
         * @param operatingPoints the selected operating point for each cell
         * @param selectedSpatialLayers the selected spatial layer for each cell, using
         * [HIGHEST_SPATIAL_LAYER] where the highest output layer should be used
         * @param cellWidths the `ispe` width for each cell
         * @param cellHeights the `ispe` height for each cell
         * @param rows the grid row count
         * @param columns the grid column count
         * @param outputWidth the reconstructed output width
         * @param outputHeight the reconstructed output height
         * @return the immutable grid source
         */
        fun grid(
            cellPayloads: Array<AvifPayload>,
            operatingPoints: IntArray,
            selectedSpatialLayers: IntArray,
            cellWidths: IntArray,
            cellHeights: IntArray,
            rows: Int,
            columns: Int,
            outputWidth: Int,
            outputHeight: Int
        ): AvifImageSource {
            return AvifImageSource(
                requireNotNullCompat(
                    cellPayloads,
                    "cellPayloads"
                ),
                requireNotNullCompat(operatingPoints, "operatingPoints"),
                requireNotNullCompat(
                    selectedSpatialLayers,
                    "selectedSpatialLayers"
                ),
                requireNotNullCompat(cellWidths, "cellWidths"),
                requireNotNullCompat(cellHeights, "cellHeights"),
                true,
                rows,
                columns,
                outputWidth,
                outputHeight
            )
        }
    }
}
