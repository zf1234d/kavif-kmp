// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import org.glavo.avif.internal.av1.model.FilterIntraMode
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode


/** Reconstructs AV1 intra-predicted luma and chroma blocks with instance-owned scratch storage.
 * 
 * This predictor implements non-directional modes, directional prediction with signed
 * `angle_delta`, luma filter-intra, and CFL chroma prediction for `YUV420`, `YUV422`, and `YUV444`.
 * Frame edges use midpoint samples when top or left neighbors are unavailable.
 * One instance must not execute prediction calls concurrently. */
internal class IntraPredictor
/** Creates an intra predictor with isolated reusable scratch storage. */
{
    /** Lazily created reusable intra-reference buffers owned by this predictor. */
    private var predictionWorkspace: Workspace? = null

    /** Reconstructs one luma intra-predicted block directly into the destination plane with
     * explicit directional-edge availability.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the luma intra prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     */
    fun predictLuma(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mode: LumaIntraPredictionMode,
        angleDelta: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        directionalTopReferenceLength: Int,
        directionalLeftReferenceLength: Int
    ) {
        predictLuma(
            plane,
            x,
            y,
            width,
            height,
            mode,
            angleDelta,
            intraEdgeFilterEnabled,
            smoothEdgeReferences,
            directionalTopReferenceLength,
            directionalLeftReferenceLength,
            0,
            0,
            plane.width(),
            plane.height()
        )
    }

    /** Reconstructs one luma intra-predicted block directly into the destination plane with
     * explicit directional-edge and tile-boundary availability.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the luma intra prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     */
    /** Reconstructs one luma intra-predicted block directly into the destination plane.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the luma intra prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     */
    fun predictLuma(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mode: LumaIntraPredictionMode,
        angleDelta: Int,
        intraEdgeFilterEnabled: Boolean = false,
        smoothEdgeReferences: Boolean = false,
        directionalTopReferenceLength: Int = -1,
        directionalLeftReferenceLength: Int = -1,
        leftBoundary: Int = 0,
        topBoundary: Int = 0,
        rightBoundary: Int = plane.width(),
        bottomBoundary: Int = plane.height()
    ) {
        predict(
            plane,
            x,
            y,
            width,
            height,
            predictionMode(mode),
            angleDelta,
            intraEdgeFilterEnabled,
            smoothEdgeReferences,
            directionalTopReferenceLength,
            directionalLeftReferenceLength,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
    }

    /** Reconstructs one luma filter-intra block directly into the destination plane.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the filter-intra mode
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     */
    /** Reconstructs one luma filter-intra block directly into the destination plane.
     * 
     * Implements the AV1 recursive 4x2 filter-intra algorithm for the syntax-legal size range up
     * to `32x32`, including partially visible right and bottom prediction units.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the filter-intra mode
     */
    fun predictFilterIntraLuma(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mode: FilterIntraMode,
        leftBoundary: Int = 0,
        topBoundary: Int = 0,
        rightBoundary: Int = plane.width(),
        bottomBoundary: Int = plane.height()
    ) {
        require(width > 0) { "width <= 0: $width" }
        require(height > 0) { "height <= 0: $height" }
        check(!(width > 32 || height > 32)) { "filter_intra requires block dimensions no larger than 32x32: ${width}x$height" }

        val defaultSample = 1 shl (plane.bitDepth() - 1)
        val taps: Array<IntArray> = FILTER_INTRA_TAPS[mode.symbolIndex()]
        val predictionWidth = (width + 3) and 3.inv()
        val predictionHeight = (height + 1) and 1.inv()
        var stripeY = 0
        while (stripeY < predictionHeight) {
            val topReferenceY = y + stripeY - 1
            val currentY = y + stripeY
            var blockX = 0
            while (blockX < predictionWidth) {
                val currentX = x + blockX
                val leftReferenceX = currentX - 1
                val p0 = filterIntraTopLeftReference(
                    plane,
                    x,
                    y,
                    currentX,
                    currentY,
                    topReferenceY,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                val p1 = filterIntraTopReference(
                    plane,
                    x,
                    y,
                    currentX,
                    topReferenceY,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                val p2 = filterIntraTopReference(
                    plane,
                    x,
                    y,
                    currentX + 1,
                    topReferenceY,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                val p3 = filterIntraTopReference(
                    plane,
                    x,
                    y,
                    currentX + 2,
                    topReferenceY,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                val p4 = filterIntraTopReference(
                    plane,
                    x,
                    y,
                    currentX + 3,
                    topReferenceY,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                val p5 = filterIntraLeftReference(
                    plane,
                    x,
                    y,
                    leftReferenceX,
                    currentY,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                val p6 = filterIntraLeftReference(
                    plane,
                    x,
                    y,
                    leftReferenceX,
                    currentY + 1,
                    defaultSample,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                for (yy in 0..1) {
                    for (xx in 0..3) {
                        val tap = taps[(yy shl 2) + xx]
                        val predicted =
                            (tap[0] * p0 + tap[1] * p1 + tap[2] * p2 + tap[3] * p3 + tap[4] * p4 + tap[5] * p5 + tap[6] * p6 + 8) shr 4
                        if (blockX + xx < width && stripeY + yy < height) {
                            setSampleIfInside(plane, currentX + xx, currentY + yy, predicted)
                        }
                    }
                }
                blockX += 4
            }
            stripeY += 2
        }
    }

    /** Returns one filter-intra top-left reference sample.
     * 
     * @param plane the mutable destination plane
     * @param blockX the block origin X coordinate
     * @param blockY the block origin Y coordinate
     * @param currentX the current recursive unit origin X coordinate
     * @param currentY the current recursive unit origin Y coordinate
     * @param topReferenceY the top reference row for this recursive unit
     * @param defaultSample the midpoint frame-edge default sample
     * @return one filter-intra top-left reference sample
     */
    private fun filterIntraTopLeftReference(
        plane: MutableSamplePlane,
        blockX: Int,
        blockY: Int,
        currentX: Int,
        currentY: Int,
        topReferenceY: Int,
        defaultSample: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): Int {
        if (currentX > blockX) {
            return filterIntraTopReference(
                plane,
                blockX,
                blockY,
                currentX - 1,
                topReferenceY,
                defaultSample,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
            )
        }
        if (currentY > blockY) {
            return filterIntraLeftReference(
                plane,
                blockX,
                blockY,
                currentX - 1,
                currentY - 1,
                defaultSample,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
            )
        }
        return defaultTopLeft(plane, blockX, blockY, defaultSample, leftBoundary, topBoundary)
    }

    /** Returns one filter-intra top reference sample with AV1 frame-edge fallback.
     * 
     * @param plane the mutable destination plane
     * @param blockX the block origin X coordinate
     * @param blockY the block origin Y coordinate
     * @param sampleX the top reference X coordinate
     * @param topReferenceY the top reference row for this recursive unit
     * @param defaultSample the midpoint frame-edge default sample
     * @return one filter-intra top reference sample
     */
    private fun filterIntraTopReference(
        plane: MutableSamplePlane,
        blockX: Int,
        blockY: Int,
        sampleX: Int,
        topReferenceY: Int,
        defaultSample: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): Int {
        if (topReferenceY >= blockY) {
            return edgeExtendedSample(plane, sampleX, topReferenceY)
        }
        if (topReferenceY < topBoundary) {
            return if (blockX > leftBoundary) edgeExtendedSample(
                plane,
                blockX - 1,
                blockY
            ) else defaultSample - 1
        }
        val maxX = max(0, min(rightBoundary, plane.width()) - 1)
        val maxY = max(0, min(bottomBoundary, plane.height()) - 1)
        return plane.sample(min(sampleX, maxX), min(topReferenceY, maxY))
    }

    /** Returns one filter-intra left reference sample with AV1 frame-edge fallback.
     * 
     * @param plane the mutable destination plane
     * @param blockX the block origin X coordinate
     * @param blockY the block origin Y coordinate
     * @param leftReferenceX the left reference column for this recursive unit
     * @param sampleY the left reference Y coordinate
     * @param defaultSample the midpoint frame-edge default sample
     * @return one filter-intra left reference sample
     */
    private fun filterIntraLeftReference(
        plane: MutableSamplePlane,
        blockX: Int,
        blockY: Int,
        leftReferenceX: Int,
        sampleY: Int,
        defaultSample: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): Int {
        if (leftReferenceX >= blockX) {
            return edgeExtendedSample(plane, leftReferenceX, sampleY)
        }
        if (leftReferenceX < leftBoundary) {
            return if (blockY > topBoundary) edgeExtendedSample(
                plane,
                blockX,
                blockY - 1
            ) else defaultSample + 1
        }
        val maxX = max(0, min(rightBoundary, plane.width()) - 1)
        val maxY = max(0, min(bottomBoundary, plane.height()) - 1)
        return plane.sample(min(leftReferenceX, maxX), min(sampleY, maxY))
    }

    /** Reconstructs one chroma intra-predicted block directly into the destination plane with
     * explicit directional-edge and tile-boundary availability.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the chroma intra prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     */
    /** Reconstructs one chroma intra-predicted block directly into the destination plane with
     * explicit directional-edge availability.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the chroma intra prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     */
    /** Reconstructs one chroma intra-predicted block directly into the destination plane.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the chroma intra prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     */
    fun predictChroma(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mode: UvIntraPredictionMode,
        angleDelta: Int,
        intraEdgeFilterEnabled: Boolean = false,
        smoothEdgeReferences: Boolean = false,
        directionalTopReferenceLength: Int = -1,
        directionalLeftReferenceLength: Int = -1,
        leftBoundary: Int = 0,
        topBoundary: Int = 0,
        rightBoundary: Int = plane.width(),
        bottomBoundary: Int = plane.height()
    ) {
        predict(
            plane,
            x,
            y,
            width,
            height,
            predictionMode(mode),
            angleDelta,
            intraEdgeFilterEnabled,
            smoothEdgeReferences,
            directionalTopReferenceLength,
            directionalLeftReferenceLength,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
    }

    /** Reconstructs one CFL chroma block directly into the destination plane with tile-boundary
     * aware DC prediction.
     * 
     * @param chromaPlane the mutable chroma destination plane
     * @param lumaPlane the already reconstructed luma plane
     * @param chromaX the zero-based horizontal chroma sample coordinate
     * @param chromaY the zero-based vertical chroma sample coordinate
     * @param lumaX the zero-based horizontal luma sample coordinate
     * @param lumaY the zero-based vertical luma sample coordinate
     * @param width the chroma block width in samples
     * @param height the chroma block height in samples
     * @param alpha the signed CFL alpha
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param leftBoundary the first chroma sample column available to this tile
     * @param topBoundary the first chroma sample row available to this tile
     * @param rightBoundary the exclusive chroma sample column available to this tile
     * @param bottomBoundary the exclusive chroma sample row available to this tile
     */
    /** Reconstructs one CFL chroma block directly into the destination plane.
     * 
     * The predictor consumes already reconstructed luma samples, including applied luma
     * residuals, and accepts the subsampling shifts used by `YUV420`, `YUV422`, and `YUV444`.
     * 
     * @param chromaPlane the mutable chroma destination plane
     * @param lumaPlane the already reconstructed luma plane
     * @param chromaX the zero-based horizontal chroma sample coordinate
     * @param chromaY the zero-based vertical chroma sample coordinate
     * @param lumaX the zero-based horizontal luma sample coordinate
     * @param lumaY the zero-based vertical luma sample coordinate
     * @param width the chroma block width in samples
     * @param height the chroma block height in samples
     * @param alpha the signed CFL alpha
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     */
    fun predictChromaCfl(
        chromaPlane: MutableSamplePlane,
        lumaPlane: MutableSamplePlane,
        chromaX: Int,
        chromaY: Int,
        lumaX: Int,
        lumaY: Int,
        width: Int,
        height: Int,
        alpha: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        leftBoundary: Int = 0,
        topBoundary: Int = 0,
        rightBoundary: Int = chromaPlane.width(),
        bottomBoundary: Int = chromaPlane.height()
    ) {
        predictChromaCfl(
            chromaPlane,
            lumaPlane,
            chromaX,
            chromaY,
            lumaX,
            lumaY,
            width,
            height,
            alpha,
            subsamplingX,
            subsamplingY,
            width,
            height,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
    }

    /** Reconstructs one CFL chroma block with explicit stored-luma and tile boundaries.
     * 
     * The stored dimensions describe the subsampled luma footprint populated by decoded luma
     * transform units. Values outside that footprint are padded from its last column or row before
     * the block average is subtracted.
     * 
     * @param chromaPlane the mutable chroma destination plane
     * @param lumaPlane the already reconstructed luma plane
     * @param chromaX the zero-based horizontal chroma sample coordinate
     * @param chromaY the zero-based vertical chroma sample coordinate
     * @param lumaX the zero-based horizontal luma sample coordinate
     * @param lumaY the zero-based vertical luma sample coordinate
     * @param width the chroma block width in samples
     * @param height the chroma block height in samples
     * @param alpha the signed CFL alpha
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param storedWidth the subsampled luma width populated before right-edge padding
     * @param storedHeight the subsampled luma height populated before bottom-edge padding
     * @param leftBoundary the first chroma sample column available to this tile
     * @param topBoundary the first chroma sample row available to this tile
     * @param rightBoundary the exclusive chroma sample column available to this tile
     * @param bottomBoundary the exclusive chroma sample row available to this tile
     */
    fun predictChromaCfl(
        chromaPlane: MutableSamplePlane,
        lumaPlane: MutableSamplePlane,
        chromaX: Int,
        chromaY: Int,
        lumaX: Int,
        lumaY: Int,
        width: Int,
        height: Int,
        alpha: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        storedWidth: Int,
        storedHeight: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ) {
        require(width > 0) { "width <= 0: $width" }
        require(height > 0) { "height <= 0: $height" }
        require(subsamplingX in 0..1) { "subsamplingX must be 0 or 1: $subsamplingX" }
        require(subsamplingY in 0..1) { "subsamplingY must be 0 or 1: $subsamplingY" }
        require(!(storedWidth <= 0 || storedWidth > width)) { "storedWidth out of range: $storedWidth" }
        require(!(storedHeight <= 0 || storedHeight > height)) { "storedHeight out of range: $storedHeight" }
        val dc = dcPredictionValue(
            chromaPlane,
            chromaX,
            chromaY,
            width,
            height,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        val ac = cflAc(
            lumaPlane,
            lumaX,
            lumaY,
            width,
            height,
            storedWidth,
            storedHeight,
            subsamplingX,
            subsamplingY
        )
        for (row in 0..<height) {
            for (column in 0..<width) {
                val diff = alpha * ac[row * width + column]
                val predicted = dc + applySign((abs(diff) + 32) shr 6, diff)
                setSampleIfInside(chromaPlane, chromaX + column, chromaY + row, predicted)
            }
        }
    }

    /** Reconstructs one `YUV420` CFL chroma block directly into the destination plane.
     * 
     * @param chromaPlane the mutable chroma destination plane
     * @param lumaPlane the already reconstructed luma plane
     * @param chromaX the zero-based horizontal chroma sample coordinate
     * @param chromaY the zero-based vertical chroma sample coordinate
     * @param lumaX the zero-based horizontal luma sample coordinate
     * @param lumaY the zero-based vertical luma sample coordinate
     * @param width the chroma block width in samples
     * @param height the chroma block height in samples
     * @param alpha the signed CFL alpha
     */
    fun predictChromaCflI420(
        chromaPlane: MutableSamplePlane,
        lumaPlane: MutableSamplePlane,
        chromaX: Int,
        chromaY: Int,
        lumaX: Int,
        lumaY: Int,
        width: Int,
        height: Int,
        alpha: Int
    ) {
        predictChromaCfl(
            chromaPlane,
            lumaPlane,
            chromaX,
            chromaY,
            lumaX,
            lumaY,
            width,
            height,
            alpha,
            1,
            1
        )
    }

    /** Maps one luma prediction mode to its internal prediction kernel.
     * 
     * @param mode the luma intra prediction mode
     * @return the internal prediction mode
     */
    private fun predictionMode(mode: LumaIntraPredictionMode): PredictionMode {
        return when (mode) {
            LumaIntraPredictionMode.DC -> PredictionMode.DC
            LumaIntraPredictionMode.VERTICAL -> PredictionMode.VERTICAL
            LumaIntraPredictionMode.HORIZONTAL -> PredictionMode.HORIZONTAL
            LumaIntraPredictionMode.DIAGONAL_DOWN_LEFT -> PredictionMode.DIAGONAL_DOWN_LEFT
            LumaIntraPredictionMode.DIAGONAL_DOWN_RIGHT -> PredictionMode.DIAGONAL_DOWN_RIGHT
            LumaIntraPredictionMode.VERTICAL_RIGHT -> PredictionMode.VERTICAL_RIGHT
            LumaIntraPredictionMode.HORIZONTAL_DOWN -> PredictionMode.HORIZONTAL_DOWN
            LumaIntraPredictionMode.HORIZONTAL_UP -> PredictionMode.HORIZONTAL_UP
            LumaIntraPredictionMode.VERTICAL_LEFT -> PredictionMode.VERTICAL_LEFT
            LumaIntraPredictionMode.SMOOTH -> PredictionMode.SMOOTH
            LumaIntraPredictionMode.SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL
            LumaIntraPredictionMode.SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL
            LumaIntraPredictionMode.PAETH -> PredictionMode.PAETH
        }
    }

    /** Maps one chroma prediction mode to its internal prediction kernel.
     * 
     * @param mode the chroma intra prediction mode
     * @return the internal prediction mode
     */
    private fun predictionMode(mode: UvIntraPredictionMode): PredictionMode {
        return when (mode) {
            UvIntraPredictionMode.DC -> PredictionMode.DC
            UvIntraPredictionMode.VERTICAL -> PredictionMode.VERTICAL
            UvIntraPredictionMode.HORIZONTAL -> PredictionMode.HORIZONTAL
            UvIntraPredictionMode.DIAGONAL_DOWN_LEFT -> PredictionMode.DIAGONAL_DOWN_LEFT
            UvIntraPredictionMode.DIAGONAL_DOWN_RIGHT -> PredictionMode.DIAGONAL_DOWN_RIGHT
            UvIntraPredictionMode.VERTICAL_RIGHT -> PredictionMode.VERTICAL_RIGHT
            UvIntraPredictionMode.HORIZONTAL_DOWN -> PredictionMode.HORIZONTAL_DOWN
            UvIntraPredictionMode.HORIZONTAL_UP -> PredictionMode.HORIZONTAL_UP
            UvIntraPredictionMode.VERTICAL_LEFT -> PredictionMode.VERTICAL_LEFT
            UvIntraPredictionMode.SMOOTH -> PredictionMode.SMOOTH
            UvIntraPredictionMode.SMOOTH_VERTICAL -> PredictionMode.SMOOTH_VERTICAL
            UvIntraPredictionMode.SMOOTH_HORIZONTAL -> PredictionMode.SMOOTH_HORIZONTAL
            UvIntraPredictionMode.PAETH -> PredictionMode.PAETH
            UvIntraPredictionMode.CFL -> throw IllegalArgumentException("CFL requires predictChromaCfl with reconstructed luma")
        }
    }

    /** Reconstructs one supported intra-predicted block directly into the destination plane with
     * explicit tile-boundary availability.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the supported internal prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     */
    private fun predict(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mode: PredictionMode,
        angleDelta: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        directionalTopReferenceLength: Int,
        directionalLeftReferenceLength: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ) {
        require(width > 0) { "width <= 0: $width" }
        require(height > 0) { "height <= 0: $height" }
        if (width > MAX_INTRA_PREDICTION_AXIS_SIZE || height > MAX_INTRA_PREDICTION_AXIS_SIZE) {
            predictLargeBlock(
                plane,
                x,
                y,
                width,
                height,
                mode,
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                directionalLeftReferenceLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
            )
            return
        }

        if (mode.usesDirectionalPrediction(angleDelta)) {
            predictDirectional(
                plane,
                x,
                y,
                width,
                height,
                mode,
                angleDelta,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                directionalLeftReferenceLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
            )
            return
        }

        val defaultSample = 1 shl (plane.bitDepth() - 1)
        val referenceWidth =
            if (mode.usesHorizontalSmoothReference()) smoothWeightAxisSize(width) else width
        val referenceHeight =
            if (mode.usesVerticalSmoothReference()) smoothWeightAxisSize(height) else height
        val top = topReferenceSamples(
            plane,
            x,
            y,
            referenceWidth,
            defaultSample,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        val left = leftReferenceSamples(
            plane,
            x,
            y,
            referenceHeight,
            defaultSample,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )

        val topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary)
        when (mode) {
            PredictionMode.DC -> predictDc(
                plane,
                x,
                y,
                width,
                height,
                top,
                left,
                defaultSample,
                leftBoundary,
                topBoundary
            )

            PredictionMode.VERTICAL -> predictVertical(plane, x, y, width, height, top)
            PredictionMode.HORIZONTAL -> predictHorizontal(plane, x, y, width, height, left)
            PredictionMode.PAETH -> {
                if (x <= leftBoundary && y <= topBoundary) {
                    plane.fillBlock(x, y, width, height, defaultSample)
                } else if (x <= leftBoundary) {
                    predictVertical(plane, x, y, width, height, top)
                } else if (y <= topBoundary) {
                    predictHorizontal(plane, x, y, width, height, left)
                } else {
                    predictPaeth(plane, x, y, width, height, top, left, topLeft)
                }
            }

            PredictionMode.SMOOTH -> predictSmooth(plane, x, y, width, height, top, left)
            PredictionMode.SMOOTH_VERTICAL -> predictSmoothVertical(
                plane,
                x,
                y,
                width,
                height,
                top,
                left
            )

            PredictionMode.SMOOTH_HORIZONTAL -> predictSmoothHorizontal(
                plane,
                x,
                y,
                width,
                height,
                top,
                left
            )
            else -> throw IllegalStateException("Unsupported intra prediction mode: $mode")
        }
    }

    /** Reconstructs a large intra block through 64x64-or-smaller prediction-kernel regions.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the supported internal prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     */
    private fun predictLargeBlock(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mode: PredictionMode,
        angleDelta: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        directionalTopReferenceLength: Int,
        directionalLeftReferenceLength: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ) {
        var offsetY = 0
        while (offsetY < height) {
            val subHeight = min(MAX_INTRA_PREDICTION_AXIS_SIZE, height - offsetY)
            var offsetX = 0
            while (offsetX < width) {
                val subWidth = min(MAX_INTRA_PREDICTION_AXIS_SIZE, width - offsetX)
                predict(
                    plane,
                    x + offsetX,
                    y + offsetY,
                    subWidth,
                    subHeight,
                    mode,
                    angleDelta,
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    directionalTopReferenceLength,
                    directionalLeftReferenceLength,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
                offsetX += MAX_INTRA_PREDICTION_AXIS_SIZE
            }
            offsetY += MAX_INTRA_PREDICTION_AXIS_SIZE
        }
    }

    /** Reconstructs one directional intra-predicted block.
     * 
     * The implementation follows the AV1 zone-1/2/3 directional interpolation model and applies
     * the sequence-controlled intra-edge filtering or upsampling pre-pass before interpolation.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param mode the directional-capable prediction mode
     * @param angleDelta the signed directional angle delta
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     */
    private fun predictDirectional(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mode: PredictionMode,
        angleDelta: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        directionalTopReferenceLength: Int,
        directionalLeftReferenceLength: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ) {
        val angle = mode.directionalBaseAngle() + 3 * angleDelta
        check(!(angle < 0 || angle > 270)) { "Directional angle is out of range: $angle" }

        val defaultSample = 1 shl (plane.bitDepth() - 1)
        if (angle == 90 || (angle < 90 && y <= topBoundary)) {
            predictVertical(
                plane,
                x,
                y,
                width,
                height,
                topDirectionalReferences(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    defaultSample,
                    width,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
            )
            return
        }
        if (angle == 180 || (angle > 180 && x <= leftBoundary)) {
            predictHorizontal(
                plane,
                x,
                y,
                width,
                height,
                leftDirectionalReferences(
                    plane,
                    x,
                    y,
                    width,
                    height,
                    defaultSample,
                    height,
                    leftBoundary,
                    topBoundary,
                    rightBoundary,
                    bottomBoundary
                )
            )
            return
        }
        if (angle < 90) {
            predictDirectionalZone1(
                plane,
                x,
                y,
                width,
                height,
                angle,
                defaultSample,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
            )
            return
        }
        if (angle < 180) {
            predictDirectionalZone2(
                plane,
                x,
                y,
                width,
                height,
                angle,
                defaultSample,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                directionalTopReferenceLength,
                directionalLeftReferenceLength,
                leftBoundary,
                topBoundary,
                rightBoundary,
                bottomBoundary
            )
            return
        }
        predictDirectionalZone3(
            plane,
            x,
            y,
            width,
            height,
            angle,
            defaultSample,
            intraEdgeFilterEnabled,
            smoothEdgeReferences,
            directionalLeftReferenceLength,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
    }

    /** Reconstructs one zone-1 directional block that projects from the top edge.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param angle the absolute AV1 intra prediction angle
     * @param defaultSample the frame-edge default sample
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     */
    private fun predictDirectionalZone1(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        angle: Int,
        defaultSample: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        directionalTopReferenceLength: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ) {
        val availableTopLength = directionalReferenceLength(
            directionalTopReferenceLength,
            width,
            width + min(width, height)
        )
        val topReferences = topDirectionalReferences(
            plane,
            x,
            y,
            width,
            height,
            defaultSample,
            availableTopLength,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        val topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary)
        var dx = directionalDerivative(angle shr 1)
        val referenceSpan = width + height
        val edgeAngle = 90 - angle
        val top: IntArray?
        val maxBase: Int
        val baseIncrement: Int
        if (intraEdgeFilterEnabled && useDirectionalEdgeUpsample(
                referenceSpan,
                edgeAngle,
                smoothEdgeReferences
            )
        ) {
            top = upsampleDirectionalEdge(
                topReferences,
                topLeft,
                referenceSpan,
                -1,
                availableTopLength,
                plane.bitDepth(),
                false,
                PROCESSED_TOP_REFERENCE_BANK
            )
            dx = dx shl 1
            maxBase = top.size - 1
            baseIncrement = 2
        } else {
            val filterStrength = if (intraEdgeFilterEnabled)
                directionalEdgeFilterStrength(referenceSpan, edgeAngle, smoothEdgeReferences)
            else
                0
            if (filterStrength != 0) {
                top = filterDirectionalEdge(
                    topReferences,
                    topLeft,
                    referenceSpan,
                    0,
                    referenceSpan,
                    -1,
                    availableTopLength,
                    filterStrength,
                    PROCESSED_TOP_REFERENCE_BANK
                )
                maxBase = referenceSpan - 1
            } else {
                top = topReferences
                maxBase = top.size - 1
            }
            baseIncrement = 1
        }
        var row = 0
        var xpos = dx
        while (row < height) {
            val frac = xpos and 0x3E
            var column = 0
            var base = xpos shr 6
            while (column < width) {
                if (base < maxBase) {
                    setSampleIfInside(
                        plane,
                        x + column,
                        y + row,
                        interpolate(top[base], top[base + 1], frac)
                    )
                } else {
                    for (remaining in column..<width) {
                        setSampleIfInside(plane, x + remaining, y + row, top[maxBase])
                    }
                    break
                }
                column++
                base += baseIncrement
            }
            row++
            xpos += dx
        }
    }

    /** Reconstructs one zone-2 directional block that blends the top and left edges.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param angle the absolute AV1 intra prediction angle
     * @param defaultSample the frame-edge default sample
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param directionalTopReferenceLength the available top-edge directional reference length, or `-1` for default
     * @param directionalLeftReferenceLength the available left-edge directional reference length, or `-1` for default
     */
    private fun predictDirectionalZone2(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        angle: Int,
        defaultSample: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        directionalTopReferenceLength: Int,
        directionalLeftReferenceLength: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ) {
        val availableTopLength =
            directionalReferenceAvailability(directionalTopReferenceLength, width)
        val availableLeftLength =
            directionalReferenceAvailability(directionalLeftReferenceLength, height)
        val topReferences = topDirectionalReferences(
            plane,
            x,
            y,
            width,
            height,
            defaultSample,
            width,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        val leftReferences = leftDirectionalReferences(
            plane,
            x,
            y,
            width,
            height,
            defaultSample,
            height,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        var topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary)
        val dy = directionalDerivative((angle - 90) shr 1)
        val dx = directionalDerivative((180 - angle) shr 1)
        val referenceSpan = width + height
        if (intraEdgeFilterEnabled && x > leftBoundary && y > topBoundary && referenceSpan >= 24) {
            topLeft = filterDirectionalEdgeCorner(topLeft, topReferences[0], leftReferences[0])
        }
        val upsampleTop = intraEdgeFilterEnabled
                && useDirectionalEdgeUpsample(referenceSpan, angle - 90, smoothEdgeReferences)
        val upsampleLeft = intraEdgeFilterEnabled
                && useDirectionalEdgeUpsample(referenceSpan, 180 - angle, smoothEdgeReferences)
        val topEdge: IntArray?
        val topBaseOffset: Int
        if (upsampleTop) {
            topEdge = upsampleDirectionalEdge(
                topReferences,
                topLeft,
                width + 1,
                0,
                width + 1,
                plane.bitDepth(),
                true,
                TOP_EDGE_BANK
            )
            topBaseOffset = 2
        } else {
            val filterStrength = if (intraEdgeFilterEnabled)
                directionalEdgeFilterStrength(referenceSpan, angle - 90, smoothEdgeReferences)
            else
                0
            val filteredTopReferences = if (filterStrength != 0 && availableTopLength > 0)
                filterDirectionalEdge(
                    topReferences,
                    topLeft,
                    width,
                    0,
                    availableTopLength,
                    -1,
                    availableTopLength,
                    filterStrength,
                    PROCESSED_TOP_REFERENCE_BANK
                )
            else
                topReferences
            topEdge = edgeWithTopLeft(filteredTopReferences, topLeft, TOP_EDGE_BANK)
            topBaseOffset = 1
        }
        val leftEdge: IntArray?
        val leftBaseOffset: Int
        if (upsampleLeft) {
            leftEdge = upsampleDirectionalEdge(
                leftReferences,
                topLeft,
                height + 1,
                0,
                height + 1,
                plane.bitDepth(),
                true,
                LEFT_EDGE_BANK
            )
            leftBaseOffset = 2
        } else {
            val filterStrength = if (intraEdgeFilterEnabled)
                directionalEdgeFilterStrength(referenceSpan, 180 - angle, smoothEdgeReferences)
            else
                0
            val filteredLeftReferences = if (filterStrength != 0 && availableLeftLength > 0)
                filterDirectionalEdge(
                    leftReferences,
                    topLeft,
                    height,
                    0,
                    availableLeftLength,
                    -1,
                    availableLeftLength,
                    filterStrength,
                    PROCESSED_LEFT_REFERENCE_BANK
                )
            else
                leftReferences
            leftEdge = edgeWithTopLeft(filteredLeftReferences, topLeft, LEFT_EDGE_BANK)
            leftBaseOffset = 1
        }
        val topMinimumBase = -topBaseOffset
        val topFractionBits = 6 - (if (upsampleTop) 1 else 0)
        val leftFractionBits = 6 - (if (upsampleLeft) 1 else 0)
        for (row in 0..<height) {
            for (column in 0..<width) {
                val topPosition = (column shl 6) - (row + 1) * dx
                val baseX = topPosition shr topFractionBits
                if (baseX >= topMinimumBase) {
                    setSampleIfInside(
                        plane,
                        x + column,
                        y + row,
                        interpolate(
                            edgeSample(topEdge, baseX + topBaseOffset),
                            edgeSample(topEdge, baseX + topBaseOffset + 1),
                            directionalFraction(topPosition, upsampleTop)
                        )
                    )
                } else {
                    val leftPosition = (row shl 6) - (column + 1) * dy
                    val baseY = leftPosition shr leftFractionBits
                    setSampleIfInside(
                        plane,
                        x + column,
                        y + row,
                        interpolate(
                            edgeSample(leftEdge, baseY + leftBaseOffset),
                            edgeSample(leftEdge, baseY + leftBaseOffset + 1),
                            directionalFraction(leftPosition, upsampleLeft)
                        )
                    )
                }
            }
        }
    }

    /** Reconstructs one zone-3 directional block that projects from the left edge.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param angle the absolute AV1 intra prediction angle
     * @param defaultSample the frame-edge default sample
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     */
    private fun predictDirectionalZone3(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        angle: Int,
        defaultSample: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        directionalLeftReferenceLength: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ) {
        val availableLeftLength = directionalReferenceLength(
            directionalLeftReferenceLength,
            height,
            height + min(width, height)
        )
        val leftReferences = leftDirectionalReferences(
            plane,
            x,
            y,
            width,
            height,
            defaultSample,
            availableLeftLength,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        val topLeft = defaultTopLeft(plane, x, y, defaultSample, leftBoundary, topBoundary)
        var dy = directionalDerivative((270 - angle) shr 1)
        val referenceSpan = width + height
        val edgeAngle = angle - 180
        val left: IntArray?
        val maxBase: Int
        val baseIncrement: Int
        if (intraEdgeFilterEnabled && useDirectionalEdgeUpsample(
                referenceSpan,
                edgeAngle,
                smoothEdgeReferences
            )
        ) {
            left = upsampleDirectionalEdge(
                leftReferences,
                topLeft,
                referenceSpan,
                -1,
                availableLeftLength,
                plane.bitDepth(),
                false,
                PROCESSED_LEFT_REFERENCE_BANK
            )
            dy = dy shl 1
            maxBase = left.size - 1
            baseIncrement = 2
        } else {
            val filterStrength = if (intraEdgeFilterEnabled)
                directionalEdgeFilterStrength(referenceSpan, edgeAngle, smoothEdgeReferences)
            else
                0
            if (filterStrength != 0) {
                left = filterDirectionalEdge(
                    leftReferences,
                    topLeft,
                    referenceSpan,
                    0,
                    referenceSpan,
                    -1,
                    availableLeftLength,
                    filterStrength,
                    PROCESSED_LEFT_REFERENCE_BANK
                )
                maxBase = referenceSpan - 1
            } else {
                left = leftReferences
                maxBase = left.size - 1
            }
            baseIncrement = 1
        }
        var column = 0
        var ypos = dy
        while (column < width) {
            val frac = ypos and 0x3E
            var row = 0
            var base = ypos shr 6
            while (row < height) {
                if (base < maxBase) {
                    val predicted = interpolate(left[base], left[base + 1], frac)
                    setSampleIfInside(plane, x + column, y + row, predicted)
                } else {
                    for (remaining in row..<height) {
                        setSampleIfInside(plane, x + column, y + remaining, left[maxBase])
                    }
                    break
                }
                row++
                base += baseIncrement
            }
            column++
            ypos += dy
        }
    }

    /** Returns a bounded directional reference length.
     * 
     * @param requestedLength the requested available reference length, or a negative value for default
     * @param minimumLength the minimum number of references required by the predictor
     * @param defaultLength the default reference length when availability is not explicitly constrained
     * @return the bounded directional reference length
     */
    private fun directionalReferenceLength(
        requestedLength: Int,
        minimumLength: Int,
        defaultLength: Int
    ): Int {
        if (requestedLength < 0) {
            return defaultLength
        }
        return clamp(requestedLength, minimumLength, defaultLength)
    }

    /** Returns the available portion of one zone-2 directional reference edge.
     * 
     * @param requestedLength the requested available reference length, or a negative value for default
     * @param defaultLength the full edge length when availability is not explicitly constrained
     * @return the bounded number of available reference samples
     */
    private fun directionalReferenceAvailability(requestedLength: Int, defaultLength: Int): Int {
        if (requestedLength < 0) {
            return defaultLength
        }
        return clamp(requestedLength, 0, defaultLength)
    }

    /** Returns the fallback top-left predictor sample for one block origin with tile boundaries.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param defaultSample the frame-edge default sample
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @return the fallback top-left predictor sample for one block origin with tile boundaries
     */
    private fun defaultTopLeft(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        defaultSample: Int,
        leftBoundary: Int,
        topBoundary: Int
    ): Int {
        if (x > leftBoundary) {
            return if (y > topBoundary) plane.sample(x - 1, y - 1) else plane.sample(x - 1, y)
        }
        if (y > topBoundary) {
            return plane.sample(x, y - 1)
        }
        return defaultSample
    }

    /** Reconstructs one DC-predicted block.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param top the top reference samples, including the smooth right-edge reference when clipped
     * @param left the left reference samples, including the smooth bottom-edge reference when clipped
     * @param defaultSample the frame-edge default sample
     */
    private fun predictDc(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: IntArray,
        left: IntArray,
        defaultSample: Int,
        leftBoundary: Int,
        topBoundary: Int
    ) {
        val value = dcPredictionValue(
            width,
            height,
            top,
            left,
            defaultSample,
            y > topBoundary,
            x > leftBoundary
        )
        plane.fillBlock(x, y, width, height, value)
    }

    /** Returns the stable DC predictor value for one block with tile-boundary availability.
     * 
     * @param plane the destination plane that supplies already reconstructed neighbors
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     * @return the stable DC predictor value for one block with tile-boundary availability
     */
    private fun dcPredictionValue(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): Int {
        val defaultSample = 1 shl (plane.bitDepth() - 1)
        val top = topReferenceSamples(
            plane,
            x,
            y,
            width,
            defaultSample,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        val left = leftReferenceSamples(
            plane,
            x,
            y,
            height,
            defaultSample,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
        return dcPredictionValue(
            width,
            height,
            top,
            left,
            defaultSample,
            y > topBoundary,
            x > leftBoundary
        )
    }

    /** Returns the stable DC predictor value for one block using caller-supplied edge samples and
     * explicit availability flags.
     * 
     * @param width the block width in samples
     * @param height the block height in samples
     * @param top the top reference samples
     * @param left the left reference samples
     * @param defaultSample the frame-edge default sample
     * @param haveTop whether the top edge is available
     * @param haveLeft whether the left edge is available
     * @return the stable DC predictor value for one block
     */
    private fun dcPredictionValue(
        width: Int,
        height: Int,
        top: IntArray,
        left: IntArray,
        defaultSample: Int,
        haveTop: Boolean,
        haveLeft: Boolean
    ): Int {
        if (haveTop && haveLeft) {
            var sum = 0
            for (sample in top) {
                sum += sample
            }
            for (sample in left) {
                sum += sample
            }
            return (sum + ((width + height) shr 1)) / (width + height)
        }
        if (haveTop) {
            var sum = 0
            for (sample in top) {
                sum += sample
            }
            return (sum + (width shr 1)) / width
        }
        if (haveLeft) {
            var sum = 0
            for (sample in left) {
                sum += sample
            }
            return (sum + (height shr 1)) / height
        }
        return defaultSample
    }

    /** Returns the signed CFL AC buffer for one subsampled chroma block.
     * 
     * The returned buffer has one entry per chroma sample in raster order and accepts the
     * subsampling shifts used by `YUV420`, `YUV422`, and `YUV444`.
     * 
     * @param lumaPlane the already reconstructed luma plane
     * @param lumaX the zero-based horizontal luma sample coordinate
     * @param lumaY the zero-based vertical luma sample coordinate
     * @param chromaWidth the chroma block width in samples
     * @param chromaHeight the chroma block height in samples
     * @param storedWidth the width populated by reconstructed luma before right-edge padding
     * @param storedHeight the height populated by reconstructed luma before bottom-edge padding
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @return the signed CFL AC buffer for one subsampled chroma block
     */
    private fun cflAc(
        lumaPlane: MutableSamplePlane,
        lumaX: Int,
        lumaY: Int,
        chromaWidth: Int,
        chromaHeight: Int,
        storedWidth: Int,
        storedHeight: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): IntArray {
        val ac = IntArray(chromaWidth * chromaHeight)
        val horizontalSpan = 1 shl subsamplingX
        val verticalSpan = 1 shl subsamplingY
        val valueShift = 3 - subsamplingX - subsamplingY
        for (row in 0..<storedHeight) {
            val rowOffset = row * chromaWidth
            val sourceY = lumaY + (row shl subsamplingY)
            for (column in 0..<storedWidth) {
                val sourceX = lumaX + (column shl subsamplingX)
                var acSum = 0
                for (sampleY in 0..<verticalSpan) {
                    for (sampleX in 0..<horizontalSpan) {
                        acSum += lumaPlane.sample(sourceX + sampleX, sourceY + sampleY)
                    }
                }
                ac[rowOffset + column] = acSum shl valueShift
            }
            val lastValue = ac[rowOffset + storedWidth - 1]
            for (column in storedWidth..<chromaWidth) {
                ac[rowOffset + column] = lastValue
            }
        }
        for (row in storedHeight..<chromaHeight) {
            val rowOffset = row * chromaWidth
            val previousRowOffset = rowOffset - chromaWidth
            arraycopy(ac, previousRowOffset, ac, rowOffset, chromaWidth)
        }

        var sum = 1 shl (
            chromaWidth.countTrailingZeroBits() + chromaHeight.countTrailingZeroBits() - 1
        )
        for (value in ac) {
            sum += value
        }
        val shift =
            chromaWidth.countTrailingZeroBits() + chromaHeight.countTrailingZeroBits()
        val average = sum shr shift
        for (i in ac.indices) {
            ac[i] -= average
        }
        return ac
    }

    /** Applies the sign of one source value to one magnitude.
     * 
     * @param magnitude the unsigned magnitude
     * @param signedSource the value whose sign should be copied
     * @return the signed magnitude
     */
    private fun applySign(magnitude: Int, signedSource: Int): Int {
        return if (signedSource < 0) -magnitude else magnitude
    }

    /** Returns one reconstructed sample with right and bottom buffer-edge extension.
     * 
     * @param plane the source plane
     * @param x the requested horizontal coordinate
     * @param y the requested vertical coordinate
     * @return the nearest in-buffer sample
     */
    private fun edgeExtendedSample(plane: MutableSamplePlane, x: Int, y: Int): Int {
        val clampedX = max(0, min(x, plane.width() - 1))
        val clampedY = max(0, min(y, plane.height() - 1))
        return plane.sample(clampedX, clampedY)
    }

    /** Stores one predicted sample when it lies inside the destination plane.
     * 
     * Intra prediction is evaluated over the coded block size. Right and bottom frame-edge blocks
     * may therefore generate samples outside the visible plane; AV1 simply clips those writes.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param value the predicted sample value
     */
    private fun setSampleIfInside(plane: MutableSamplePlane, x: Int, y: Int, value: Int) {
        if (x >= 0 && x < plane.width() && y >= 0 && y < plane.height()) {
            plane.setSample(x, y, value)
        }
    }

    /** Reconstructs one vertical-predicted block.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param top the top reference samples
     */
    private fun predictVertical(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: IntArray
    ) {
        for (row in 0..<height) {
            for (column in 0..<width) {
                setSampleIfInside(plane, x + column, y + row, top[column])
            }
        }
    }

    /** Reconstructs one horizontal-predicted block.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param left the left reference samples
     */
    private fun predictHorizontal(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        left: IntArray
    ) {
        for (row in 0..<height) {
            val value = left[row]
            for (column in 0..<width) {
                setSampleIfInside(plane, x + column, y + row, value)
            }
        }
    }

    /** Reconstructs one Paeth-predicted block.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param top the top reference samples
     * @param left the left reference samples
     * @param topLeft the top-left reference sample
     */
    private fun predictPaeth(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: IntArray,
        left: IntArray,
        topLeft: Int
    ) {
        for (row in 0..<height) {
            val leftValue = left[row]
            for (column in 0..<width) {
                val topValue = top[column]
                val base = leftValue + topValue - topLeft
                val leftDiff = abs(leftValue - base)
                val topDiff = abs(topValue - base)
                val topLeftDiff = abs(topLeft - base)
                val predicted = if (leftDiff <= topDiff && leftDiff <= topLeftDiff)
                    leftValue
                else
                    if (topDiff <= topLeftDiff) topValue else topLeft
                setSampleIfInside(plane, x + column, y + row, predicted)
            }
        }
    }

    /** Reconstructs one smooth-predicted block.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param top the top reference samples
     * @param left the left reference samples
     */
    private fun predictSmooth(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: IntArray,
        left: IntArray
    ) {
        val horizontalWeights = smoothWeights(top.size)
        val verticalWeights = smoothWeights(left.size)
        val right = top[horizontalWeights.size - 1]
        val bottom = left[verticalWeights.size - 1]
        for (row in 0..<height) {
            for (column in 0..<width) {
                val predicted =
                    (verticalWeights[row] * top[column] + (256 - verticalWeights[row]) * bottom + horizontalWeights[column] * left[row] + (256 - horizontalWeights[column]) * right)
                setSampleIfInside(plane, x + column, y + row, (predicted + 256) shr 9)
            }
        }
    }

    /** Reconstructs one vertically smoothed block.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param top the top reference samples
     * @param left the left reference samples, including the smooth bottom-edge reference when clipped
     */
    private fun predictSmoothVertical(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: IntArray,
        left: IntArray
    ) {
        val verticalWeights = smoothWeights(left.size)
        val bottom = left[verticalWeights.size - 1]
        for (row in 0..<height) {
            for (column in 0..<width) {
                val predicted = (verticalWeights[row] * top[column]
                        + (256 - verticalWeights[row]) * bottom)
                setSampleIfInside(plane, x + column, y + row, (predicted + 128) shr 8)
            }
        }
    }

    /** Reconstructs one horizontally smoothed block.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param top the top reference samples, including the smooth right-edge reference when clipped
     * @param left the left reference samples
     */
    private fun predictSmoothHorizontal(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: IntArray,
        left: IntArray
    ) {
        val horizontalWeights = smoothWeights(top.size)
        val right = top[horizontalWeights.size - 1]
        for (row in 0..<height) {
            for (column in 0..<width) {
                val predicted = (horizontalWeights[column] * left[row]
                        + (256 - horizontalWeights[column]) * right)
                setSampleIfInside(plane, x + column, y + row, (predicted + 128) shr 8)
            }
        }
    }

    /** Returns one top-edge directional reference buffer with tile-bounded top-right extension.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param defaultSample the frame-edge default sample
     * @param length the required reference-buffer length
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     * @return one top-edge directional reference buffer with tile-bounded top-right extension
     */
    private fun topDirectionalReferences(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        defaultSample: Int,
        length: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): IntArray {
        return topReferenceSamples(
            plane,
            x,
            y,
            length,
            defaultSample,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
    }

    /** Returns one left-edge directional reference buffer with tile-bounded bottom-left extension.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param width the block width in samples
     * @param height the block height in samples
     * @param defaultSample the frame-edge default sample
     * @param length the required reference-buffer length
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     * @return one left-edge directional reference buffer with tile-bounded bottom-left extension
     */
    private fun leftDirectionalReferences(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        defaultSample: Int,
        length: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): IntArray {
        return leftReferenceSamples(
            plane,
            x,
            y,
            length,
            defaultSample,
            leftBoundary,
            topBoundary,
            rightBoundary,
            bottomBoundary
        )
    }

    /** Returns one top-edge reference buffer with AV1 tile-edge fallback and right extension.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param length the required reference-buffer length
     * @param defaultSample the midpoint frame-edge default sample
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     * @return one top-edge reference buffer with AV1 tile-edge fallback and right extension
     */
    private fun topReferenceSamples(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        length: Int,
        defaultSample: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): IntArray {
        val references = referenceBuffer(TOP_REFERENCE_BANK, length)
        if (y <= topBoundary) {
            val sample = if (x > leftBoundary) plane.sample(x - 1, y) else defaultSample - 1
            fillReferences(references, sample)
            return references
        }
        val maxX = max(0, min(rightBoundary, plane.width()) - 1)
        for (i in references.indices) {
            var sampleX = x + i
            if (sampleX > maxX) {
                sampleX = maxX
            }
            references[i] = plane.sample(sampleX, y - 1)
        }
        return references
    }

    /** Returns one left-edge reference buffer with AV1 tile-edge fallback and bottom extension.
     * 
     * @param plane the mutable destination plane
     * @param x the zero-based horizontal sample coordinate
     * @param y the zero-based vertical sample coordinate
     * @param length the required reference-buffer length
     * @param defaultSample the midpoint frame-edge default sample
     * @param leftBoundary the first sample column available to this tile
     * @param topBoundary the first sample row available to this tile
     * @param rightBoundary the exclusive sample column available to this tile
     * @param bottomBoundary the exclusive sample row available to this tile
     * @return one left-edge reference buffer with AV1 tile-edge fallback and bottom extension
     */
    private fun leftReferenceSamples(
        plane: MutableSamplePlane,
        x: Int,
        y: Int,
        length: Int,
        defaultSample: Int,
        leftBoundary: Int,
        topBoundary: Int,
        rightBoundary: Int,
        bottomBoundary: Int
    ): IntArray {
        val references = referenceBuffer(LEFT_REFERENCE_BANK, length)
        if (x <= leftBoundary) {
            val sample = if (y > topBoundary) plane.sample(x, y - 1) else defaultSample + 1
            fillReferences(references, sample)
            return references
        }
        val maxY = max(0, min(bottomBoundary, plane.height()) - 1)
        for (i in references.indices) {
            var sampleY = y + i
            if (sampleY > maxY) {
                sampleY = maxY
            }
            references[i] = plane.sample(x - 1, sampleY)
        }
        return references
    }

    /** Fills a reference buffer with one sample.
     * 
     * @param references the reference buffer to fill
     * @param sample the sample value to store
     */
    private fun fillReferences(references: IntArray, sample: Int) {
        for (i in references.indices) {
            references[i] = sample
        }
    }

    /** Returns whether an intra directional edge should be upsampled before interpolation.
     * 
     * @param edgeSpan the sum of the prediction block width and height
     * @param edgeAngle the acute angle between the prediction ray and the sampled reference edge
     * @param smoothEdgeReferences whether neighboring smooth predictors reduce filtering strength
     * @return whether the edge should be upsampled
     */
    private fun useDirectionalEdgeUpsample(
        edgeSpan: Int,
        edgeAngle: Int,
        smoothEdgeReferences: Boolean
    ): Boolean {
        return edgeAngle < 40 && edgeSpan <= (16 shr (if (smoothEdgeReferences) 1 else 0))
    }

    /** Returns the AV1 intra-edge filter strength for one directional edge.
     * 
     * @param edgeSpan the sum of the prediction block width and height
     * @param edgeAngle the acute angle between the prediction ray and the sampled reference edge
     * @param smoothEdgeReferences whether neighboring smooth predictors reduce filtering strength
     * @return the filter strength in `[0, 3]`
     */
    private fun directionalEdgeFilterStrength(
        edgeSpan: Int,
        edgeAngle: Int,
        smoothEdgeReferences: Boolean
    ): Int {
        if (smoothEdgeReferences) {
            if (edgeSpan <= 8) {
                if (edgeAngle >= 64) {
                    return 2
                }
                if (edgeAngle >= 40) {
                    return 1
                }
            } else if (edgeSpan <= 16) {
                if (edgeAngle >= 48) {
                    return 2
                }
                if (edgeAngle >= 20) {
                    return 1
                }
            } else if (edgeSpan <= 24) {
                if (edgeAngle >= 4) {
                    return 3
                }
            } else {
                return 3
            }
        } else {
            if (edgeSpan <= 8) {
                if (edgeAngle >= 56) {
                    return 1
                }
            } else if (edgeSpan <= 16) {
                if (edgeAngle >= 40) {
                    return 1
                }
            } else if (edgeSpan <= 24) {
                if (edgeAngle >= 32) {
                    return 3
                }
                if (edgeAngle >= 16) {
                    return 2
                }
                if (edgeAngle >= 8) {
                    return 1
                }
            } else if (edgeSpan <= 32) {
                if (edgeAngle >= 32) {
                    return 3
                }
                if (edgeAngle >= 4) {
                    return 2
                }
                return 1
            } else {
                return 3
            }
        }
        return 0
    }

    /** Returns the filtered top-left corner sample used by AV1 zone-2 directional prediction.
     * 
     * @param topLeft the original shared top-left reference sample
     * @param topReference the first top-edge reference sample
     * @param leftReference the first left-edge reference sample
     * @return the filtered shared top-left corner sample
     */
    private fun filterDirectionalEdgeCorner(
        topLeft: Int,
        topReference: Int,
        leftReference: Int
    ): Int {
        return (leftReference * 5 + topLeft * 6 + topReference * 5 + 8) shr 4
    }

    /** Returns a filtered directional reference edge.
     * 
     * The source edge uses conceptual index `-1` for the top-left sample and non-negative indices
     * for the top or left samples extending away from it.
     * 
     * @param references the edge references excluding the top-left sample
     * @param topLeft the top-left reference sample
     * @param outputLength the filtered output length
     * @param limitFrom the first output index that may be filtered
     * @param limitTo the exclusive last output index that may be filtered
     * @param from the inclusive source clamp lower bound
     * @param to the exclusive source clamp upper bound
     * @param strength the AV1 intra-edge filter strength
     * @param bufferBank the workspace bank that owns the returned edge
     * @return the filtered directional reference edge
     */
    private fun filterDirectionalEdge(
        references: IntArray,
        topLeft: Int,
        outputLength: Int,
        limitFrom: Int,
        limitTo: Int,
        from: Int,
        to: Int,
        strength: Int,
        bufferBank: Int
    ): IntArray {
        val output = referenceBuffer(bufferBank, outputLength)
        val kernel: IntArray = INTRA_EDGE_FILTER_KERNELS[strength - 1]
        var index = 0
        while (index < min(outputLength, limitFrom)) {
            output[index] =
                directionalEdgeSourceSample(references, topLeft, -1, clamp(index, from, to - 1))
            index++
        }
        while (index < min(outputLength, limitTo)) {
            var sum = 0
            for (tap in kernel.indices) {
                val sourceIndex = clamp(index - 2 + tap, from, to - 1)
                sum += directionalEdgeSourceSample(
                    references,
                    topLeft,
                    -1,
                    sourceIndex
                ) * kernel[tap]
            }
            output[index] = (sum + 8) shr 4
            index++
        }
        while (index < outputLength) {
            output[index] =
                directionalEdgeSourceSample(references, topLeft, -1, clamp(index, from, to - 1))
            index++
        }
        return output
    }

    /** Returns an upsampled directional reference edge.
     * 
     * @param references the edge references excluding the top-left sample
     * @param topLeft the top-left reference sample
     * @param outputEvenCount the number of original even-position samples to expose
     * @param from the inclusive source clamp lower bound
     * @param to the exclusive source clamp upper bound
     * @param bitDepth the decoded sample bit depth
     * @param includeTopLeft whether conceptual source index `0` addresses the top-left sample
     * @param bufferBank the workspace bank that owns the returned edge
     * @return the upsampled directional reference edge
     */
    private fun upsampleDirectionalEdge(
        references: IntArray,
        topLeft: Int,
        outputEvenCount: Int,
        from: Int,
        to: Int,
        bitDepth: Int,
        includeTopLeft: Boolean,
        bufferBank: Int
    ): IntArray {
        val output = referenceBuffer(bufferBank, outputEvenCount * 2 - 1)
        val topLeftIndex = if (includeTopLeft) 0 else -1
        val maximumSample = (1 shl bitDepth) - 1
        var index = 0
        while (index < outputEvenCount - 1) {
            output[index shl 1] = directionalEdgeSourceSample(
                references,
                topLeft,
                topLeftIndex,
                clamp(index, from, to - 1)
            )
            var sum = 0
            for (tap in INTRA_EDGE_UPSAMPLE_KERNEL.indices) {
                val sourceIndex = clamp(index + tap - 1, from, to - 1)
                sum += (directionalEdgeSourceSample(references, topLeft, topLeftIndex, sourceIndex)
                        * INTRA_EDGE_UPSAMPLE_KERNEL[tap])
            }
            output[(index shl 1) + 1] = clamp((sum + 8) shr 4, 0, maximumSample)
            index++
        }
        output[index shl 1] = directionalEdgeSourceSample(
            references,
            topLeft,
            topLeftIndex,
            clamp(index, from, to - 1)
        )
        return output
    }

    /** Returns an edge array whose first entry is the top-left reference sample.
     * 
     * @param references the top or left references excluding the top-left sample
     * @param topLeft the top-left reference sample
     * @param bufferBank the workspace bank that owns the returned edge
     * @return a reference edge with the top-left sample at index `0`
     */
    private fun edgeWithTopLeft(references: IntArray, topLeft: Int, bufferBank: Int): IntArray {
        val edge = referenceBuffer(bufferBank, references.size + 1)
        edge[0] = topLeft
        arraycopy(references, 0, edge, 1, references.size)
        return edge
    }

    /** Returns one sample from an edge array, extending the outermost entries.
     * 
     * @param edge the edge array to read
     * @param index the requested conceptual edge index
     * @return the edge sample at the requested index
     */
    private fun edgeSample(edge: IntArray, index: Int): Int {
        if (index <= 0) {
            return edge[0]
        }
        if (index >= edge.size) {
            return edge[edge.size - 1]
        }
        return edge[index]
    }

    /** Returns one conceptual directional-edge source sample.
     * 
     * @param references the edge references excluding the top-left sample
     * @param topLeft the top-left reference sample
     * @param topLeftIndex the conceptual source index that addresses the top-left sample
     * @param index the requested conceptual source index
     * @return the requested directional-edge source sample
     */
    private fun directionalEdgeSourceSample(
        references: IntArray,
        topLeft: Int,
        topLeftIndex: Int,
        index: Int
    ): Int {
        if (index == topLeftIndex) {
            return topLeft
        }
        val referenceIndex = index - topLeftIndex - 1
        if (referenceIndex <= 0) {
            return references[0]
        }
        if (referenceIndex >= references.size) {
            return references[references.size - 1]
        }
        return references[referenceIndex]
    }

    /** Returns one directional interpolation result between two edge samples.
     * 
     * @param sample0 the first edge sample
     * @param sample1 the second edge sample
     * @param fraction the AV1 fractional interpolation position in `[0, 62]`
     * @return one directional interpolation result between two edge samples
     */
    private fun interpolate(sample0: Int, sample1: Int, fraction: Int): Int {
        return (sample0 * (64 - fraction) + sample1 * fraction + 32) shr 6
    }

    /** Returns the AV1 directional interpolation fraction for one projected coordinate.
     * 
     * @param projectedCoordinate the signed projected coordinate in 1/64 sample units
     * @param upsampled whether the sampled edge has been upsampled by two
     * @return the interpolation fraction in `[0, 62]`
     */
    private fun directionalFraction(projectedCoordinate: Int, upsampled: Boolean): Int {
        return (projectedCoordinate shl (if (upsampled) 1 else 0)) and 0x3E
    }

    /** Clamps one integer to an inclusive range.
     * 
     * @param value the value to clamp
     * @param minimum the inclusive lower bound
     * @param maximum the inclusive upper bound
     * @return the clamped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(maximum, value))
    }

    /** Returns one exact-length reference buffer owned by this predictor.
     * 
     * @param bank the independently reusable workspace bank
     * @param length the exact required buffer length
     * @return the reusable reference buffer
     */
    private fun referenceBuffer(bank: Int, length: Int): IntArray {
        var workspace = predictionWorkspace
        if (workspace == null) {
            workspace = Workspace()
            predictionWorkspace = workspace
        }
        return workspace.referenceBuffer(bank, length)
    }

    /** Returns the smooth-predictor weight array for one supported block dimension.
     * 
     * @param size the block width or height in samples
     * @return the smooth-predictor weight array for one supported block dimension
     */
    private fun smoothWeights(size: Int): IntArray {
        return when (size) {
            1 -> SMOOTH_WEIGHTS_1
            2 -> SMOOTH_WEIGHTS_2
            4 -> SMOOTH_WEIGHTS_4
            8 -> SMOOTH_WEIGHTS_8
            16 -> SMOOTH_WEIGHTS_16
            32 -> SMOOTH_WEIGHTS_32
            64 -> SMOOTH_WEIGHTS_64
            else -> throw IllegalStateException("Unsupported smooth predictor size: $size")
        }
    }

    /** Returns the coded smooth-prediction reference axis for one visible edge length.
     * 
     * Smooth prediction weights are tabled by coded block axis. A clipped right or bottom edge may
     * expose fewer visible samples, so reconstruction uses the next legal smooth axis and writes
     * only the visible footprint.
     * 
     * @param visibleSize the visible edge length in samples
     * @return the smooth-prediction axis used to select weights and the far-edge reference
     */
    private fun smoothWeightAxisSize(visibleSize: Int): Int {
        if (visibleSize <= 1) {
            return 1
        }
        if (visibleSize <= 2) {
            return 2
        }
        if (visibleSize <= 4) {
            return 4
        }
        if (visibleSize <= 8) {
            return 8
        }
        if (visibleSize <= 16) {
            return 16
        }
        if (visibleSize <= 32) {
            return 32
        }
        if (visibleSize <= 64) {
            return 64
        }
        throw IllegalStateException("Unsupported smooth predictor size: $visibleSize")
    }

    /** Returns one AV1 directional derivative table entry.
     * 
     * @param halfAngleIndex the zero-based half-angle table index
     * @return one AV1 directional derivative table entry
     */
    private fun directionalDerivative(halfAngleIndex: Int): Int {
        check(!(halfAngleIndex < 0 || halfAngleIndex >= DIRECTIONAL_DERIVATIVES.size)) { "Directional derivative index out of range: $halfAngleIndex" }
        return DIRECTIONAL_DERIVATIVES[halfAngleIndex]
    }

    /** Lazily allocated reference arrays separated by simultaneous prediction role and length. */
    class Workspace
    /** Creates an initially empty prediction workspace. */
    {
        /** Nullable exact-length buffers indexed first by bank and then by buffer length. */
        val referenceBuffers: Array<Array<IntArray?>> = Array(REFERENCE_BUFFER_BANK_COUNT) {
            arrayOfNullsCompat<IntArray?>(MAX_REFERENCE_BUFFER_LENGTH + 1)
        }

        /** Returns one exact-length buffer from an independently reusable bank.
         * 
         * @param bank the zero-based buffer bank
         * @param length the exact required buffer length
         * @return the selected reusable buffer
         */
        fun referenceBuffer(bank: Int, length: Int): IntArray {
            require(!(bank < 0 || bank >= referenceBuffers.size)) { "Reference buffer bank out of range: $bank" }
            require(!(length <= 0 || length > MAX_REFERENCE_BUFFER_LENGTH)) { "Reference buffer length out of range: $length" }
            var buffer = referenceBuffers[bank][length]
            if (buffer == null) {
                buffer = IntArray(length)
                referenceBuffers[bank][length] = buffer
            }
            return buffer
        }
    }

    /** Internal intra-prediction kernel identifiers. */
    enum class PredictionMode {
        /** DC prediction. */
        DC,

        /** Vertical prediction. */
        VERTICAL,

        /** Horizontal prediction. */
        HORIZONTAL,

        /** Diagonal-down-left directional prediction. */
        DIAGONAL_DOWN_LEFT,

        /** Diagonal-down-right directional prediction. */
        DIAGONAL_DOWN_RIGHT,

        /** Vertical-right directional prediction. */
        VERTICAL_RIGHT,

        /** Horizontal-down directional prediction. */
        HORIZONTAL_DOWN,

        /** Horizontal-up directional prediction. */
        HORIZONTAL_UP,

        /** Vertical-left directional prediction. */
        VERTICAL_LEFT,

        /** Paeth prediction. */
        PAETH,

        /** Smooth prediction. */
        SMOOTH,

        /** Smooth vertical prediction. */
        SMOOTH_VERTICAL,

        /** Smooth horizontal prediction. */
        SMOOTH_HORIZONTAL;

        /** Returns whether this prediction mode currently routes through the directional predictor.
         * 
         * @param angleDelta the signed directional angle delta
         * @return whether this prediction mode currently routes through the directional predictor
         */
        fun usesDirectionalPrediction(angleDelta: Int): Boolean {
            if (angleDelta != 0 && (this == PredictionMode.VERTICAL || this == PredictionMode.HORIZONTAL)) {
                return true
            }
            return when (this) {
                PredictionMode.DIAGONAL_DOWN_LEFT, PredictionMode.DIAGONAL_DOWN_RIGHT, PredictionMode.VERTICAL_RIGHT, PredictionMode.HORIZONTAL_DOWN, PredictionMode.HORIZONTAL_UP, PredictionMode.VERTICAL_LEFT -> true
                else -> false
            }
        }

        /** Returns whether this mode needs a smooth horizontal axis for clipped-edge prediction.
         * 
         * @return whether this mode needs a smooth horizontal axis for clipped-edge prediction
         */
        fun usesHorizontalSmoothReference(): Boolean {
            return this == PredictionMode.SMOOTH || this == PredictionMode.SMOOTH_HORIZONTAL
        }

        /** Returns whether this mode needs a smooth vertical axis for clipped-edge prediction.
         * 
         * @return whether this mode needs a smooth vertical axis for clipped-edge prediction
         */
        fun usesVerticalSmoothReference(): Boolean {
            return this == PredictionMode.SMOOTH || this == PredictionMode.SMOOTH_VERTICAL
        }

        /** Returns the AV1 directional base angle for one directional-capable mode.
         * 
         * @return the AV1 directional base angle for one directional-capable mode
         */
        fun directionalBaseAngle(): Int {
            return when (this) {
                PredictionMode.VERTICAL -> DIRECTIONAL_BASE_ANGLES[0]
                PredictionMode.HORIZONTAL -> DIRECTIONAL_BASE_ANGLES[1]
                PredictionMode.DIAGONAL_DOWN_LEFT -> DIRECTIONAL_BASE_ANGLES[2]
                PredictionMode.DIAGONAL_DOWN_RIGHT -> DIRECTIONAL_BASE_ANGLES[3]
                PredictionMode.VERTICAL_RIGHT -> DIRECTIONAL_BASE_ANGLES[4]
                PredictionMode.HORIZONTAL_DOWN -> DIRECTIONAL_BASE_ANGLES[5]
                PredictionMode.HORIZONTAL_UP -> DIRECTIONAL_BASE_ANGLES[6]
                PredictionMode.VERTICAL_LEFT -> DIRECTIONAL_BASE_ANGLES[7]
                else -> throw IllegalStateException("Mode does not use directional prediction: $this")
            }
        }
    }

    companion object {
        /** The AV1 directional base angles in `VERTICAL`-through-`VERTICAL_LEFT` order. */
        val DIRECTIONAL_BASE_ANGLES: IntArray = intArrayOf(
            90, 180, 45, 135, 113, 157, 203, 67
        )

        /** The maximum axis length passed to one AV1 intra-prediction kernel invocation. */
        private const val MAX_INTRA_PREDICTION_AXIS_SIZE = 64

        /** The largest reusable directional edge produced from a 64x64 prediction kernel. */
        private const val MAX_REFERENCE_BUFFER_LENGTH = 255

        /** Workspace bank for unmodified top-edge references. */
        private const val TOP_REFERENCE_BANK = 0

        /** Workspace bank for unmodified left-edge references. */
        private const val LEFT_REFERENCE_BANK = 1

        /** Workspace bank for filtered or upsampled top-edge references. */
        private const val PROCESSED_TOP_REFERENCE_BANK = 2

        /** Workspace bank for filtered or upsampled left-edge references. */
        private const val PROCESSED_LEFT_REFERENCE_BANK = 3

        /** Workspace bank for zone-2 top edges that include the top-left sample. */
        private const val TOP_EDGE_BANK = 4

        /** Workspace bank for zone-2 left edges that include the top-left sample. */
        private const val LEFT_EDGE_BANK = 5

        /** Number of independently reusable reference-buffer banks. */
        private const val REFERENCE_BUFFER_BANK_COUNT = 6

        /** The AV1 directional derivative table indexed by half-angle. */
        val DIRECTIONAL_DERIVATIVES: IntArray = intArrayOf(
            0,
            1023, 0,
            547,
            372, 0, 0,
            273,
            215, 0,
            178,
            151, 0,
            132,
            116, 0,
            102, 0,
            90,
            80, 0,
            71,
            64, 0,
            57,
            51, 0,
            45, 0,
            40,
            35, 0,
            31,
            27, 0,
            23,
            19, 0,
            15, 0,
            11, 0,
            7,
            3
        )

        /** The AV1 filter-intra tap sets in `FilterIntraMode` order.
         * 
         * Each mode stores eight per-pixel tap vectors for the 4x2 recursive prediction unit in
         * raster order. Every tap vector multiplies:
         * 1. the current unit top-left reference
         * 2. the four top reference samples
         * 3. the two left reference samples */
        val FILTER_INTRA_TAPS: Array<Array<IntArray>> =
            arrayOf<Array<IntArray>>(
                arrayOf<IntArray>(
                    intArrayOf(-6, 10, 0, 0, 0, 12, 0),
                    intArrayOf(-5, 2, 10, 0, 0, 9, 0),
                    intArrayOf(-3, 1, 1, 10, 0, 7, 0),
                    intArrayOf(-3, 1, 1, 2, 10, 5, 0),
                    intArrayOf(-4, 6, 0, 0, 0, 2, 12),
                    intArrayOf(-3, 2, 6, 0, 0, 2, 9),
                    intArrayOf(-3, 2, 2, 6, 0, 2, 7),
                    intArrayOf(-3, 1, 2, 2, 6, 3, 5)
                ),
                arrayOf<IntArray>(
                    intArrayOf(-10, 16, 0, 0, 0, 10, 0),
                    intArrayOf(-6, 0, 16, 0, 0, 6, 0),
                    intArrayOf(-4, 0, 0, 16, 0, 4, 0),
                    intArrayOf(-2, 0, 0, 0, 16, 2, 0),
                    intArrayOf(-10, 16, 0, 0, 0, 0, 10),
                    intArrayOf(-6, 0, 16, 0, 0, 0, 6),
                    intArrayOf(-4, 0, 0, 16, 0, 0, 4),
                    intArrayOf(-2, 0, 0, 0, 16, 0, 2)
                ),
                arrayOf<IntArray>(
                    intArrayOf(-8, 8, 0, 0, 0, 16, 0),
                    intArrayOf(-8, 0, 8, 0, 0, 16, 0),
                    intArrayOf(-8, 0, 0, 8, 0, 16, 0),
                    intArrayOf(-8, 0, 0, 0, 8, 16, 0),
                    intArrayOf(-4, 4, 0, 0, 0, 0, 16),
                    intArrayOf(-4, 0, 4, 0, 0, 0, 16),
                    intArrayOf(-4, 0, 0, 4, 0, 0, 16),
                    intArrayOf(-4, 0, 0, 0, 4, 0, 16)
                ),
                arrayOf<IntArray>(
                    intArrayOf(-2, 8, 0, 0, 0, 10, 0),
                    intArrayOf(-1, 3, 8, 0, 0, 6, 0),
                    intArrayOf(-1, 2, 3, 8, 0, 4, 0),
                    intArrayOf(0, 1, 2, 3, 8, 2, 0),
                    intArrayOf(-1, 4, 0, 0, 0, 3, 10),
                    intArrayOf(-1, 3, 4, 0, 0, 4, 6),
                    intArrayOf(-1, 2, 3, 4, 0, 4, 4),
                    intArrayOf(-1, 2, 2, 3, 4, 3, 3)
                ),
                arrayOf<IntArray>(
                    intArrayOf(-12, 14, 0, 0, 0, 14, 0),
                    intArrayOf(-10, 0, 14, 0, 0, 12, 0),
                    intArrayOf(-9, 0, 0, 14, 0, 11, 0),
                    intArrayOf(-8, 0, 0, 0, 14, 10, 0),
                    intArrayOf(-10, 12, 0, 0, 0, 0, 14),
                    intArrayOf(-9, 1, 12, 0, 0, 0, 12),
                    intArrayOf(-8, 0, 0, 12, 0, 1, 11),
                    intArrayOf(-7, 0, 0, 1, 12, 1, 9)
                )
            )

        /** The AV1 intra-edge filter kernels indexed by `strength - 1`. */
        val INTRA_EDGE_FILTER_KERNELS: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(0, 4, 8, 4, 0),
            intArrayOf(0, 5, 6, 5, 0),
            intArrayOf(2, 4, 4, 4, 2)
        )

        /** The AV1 intra-edge upsampling kernel. */
        val INTRA_EDGE_UPSAMPLE_KERNEL: IntArray = intArrayOf(-1, 9, 9, -1)

        /** The smooth predictor weights for a width or height of one sample. */
        val SMOOTH_WEIGHTS_1: IntArray = intArrayOf(255)

        /** The smooth predictor weights for a width or height of two samples. */
        val SMOOTH_WEIGHTS_2: IntArray = intArrayOf(255, 128)

        /** The smooth predictor weights for a width or height of four samples. */
        val SMOOTH_WEIGHTS_4: IntArray = intArrayOf(255, 149, 85, 64)

        /** The smooth predictor weights for a width or height of eight samples. */
        val SMOOTH_WEIGHTS_8: IntArray =
            intArrayOf(255, 197, 146, 105, 73, 50, 37, 32)

        /** The smooth predictor weights for a width or height of sixteen samples. */
        val SMOOTH_WEIGHTS_16: IntArray = intArrayOf(
            255, 225, 196, 170, 145, 123, 102, 84,
            68, 54, 43, 33, 26, 20, 17, 16
        )

        /** The smooth predictor weights for a width or height of thirty-two samples. */
        val SMOOTH_WEIGHTS_32: IntArray = intArrayOf(
            255, 240, 225, 210, 196, 182, 169, 157,
            145, 133, 122, 111, 101, 92, 83, 74,
            66, 59, 52, 45, 39, 34, 29, 25,
            21, 17, 14, 12, 10, 9, 8, 8
        )

        /** The smooth predictor weights for a width or height of sixty-four samples. */
        val SMOOTH_WEIGHTS_64: IntArray = intArrayOf(
            255, 248, 240, 233, 225, 218, 210, 203,
            196, 189, 182, 176, 169, 163, 156, 150,
            144, 138, 133, 127, 121, 116, 111, 106,
            101, 96, 91, 86, 82, 77, 73, 69,
            65, 61, 57, 54, 50, 47, 44, 41,
            38, 35, 32, 29, 27, 25, 22, 20,
            18, 16, 15, 13, 12, 10, 9, 8,
            7, 6, 6, 5, 5, 4, 4, 4
        )
    }
}
