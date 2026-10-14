// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.IntrabcDisplacementValidator.InvalidDisplacementVectorException
import org.glavo.avif.internal.av1.decode.TileDecodeContext.TemporalMotionField
import org.glavo.avif.internal.av1.entropy.CdfContext
import org.glavo.avif.internal.av1.model.FrameAssembly


/** Structural frame decoder that expands every tile into partition trees and saved motion-vector fields. */
class FrameSyntaxDecoder constructor(
    /** The optional compact reference state that provides the inherited frame CDF context. */
    private val referenceCdfFrameSyntaxState: ReferenceFrameSyntaxState?,
    referenceFrameSyntaxStates: Array<ReferenceFrameSyntaxState?>?,
    strictStdCompliance: Boolean = false
) {
    /** The compact reference-frame syntax states indexed by runtime reference slot. */
    private val referenceFrameSyntaxStates: Array<ReferenceFrameSyntaxState?>?

    /** Whether decoded conformance values outside their specified ranges must be rejected. */
    private val strictStdCompliance: Boolean

    /** Creates one structural frame decoder.
     * 
     * @param referenceCdfFrameSyntaxResult the optional reference-frame syntax result that provides the inherited frame CDF context
     */
    constructor(referenceCdfFrameSyntaxResult: FrameSyntaxDecodeResult?) : this(
        if (referenceCdfFrameSyntaxResult == null)
            null
        else
            ReferenceFrameSyntaxState.from(referenceCdfFrameSyntaxResult),
        arrayOfNullsCompat<ReferenceFrameSyntaxState?>(8)
    )

    init {
        val nonNullReferenceFrameSyntaxStates =
            requireNotNullCompat(
                referenceFrameSyntaxStates,
                "referenceFrameSyntaxStates"
            )
        require(nonNullReferenceFrameSyntaxStates.size == 8) { "referenceFrameSyntaxStates.length != 8: " + nonNullReferenceFrameSyntaxStates.size }
        this.referenceFrameSyntaxStates = nonNullReferenceFrameSyntaxStates.copyOf()
        this.strictStdCompliance = strictStdCompliance
    }

    /** Structurally decodes every collected tile in one completed frame assembly.
     * 
     * @param assembly the completed frame assembly to decode
     * @return the structural frame-decode result
     * @throws InvalidFrameSyntaxException if decoded tile syntax violates AV1 structural constraints
     */
    fun decode(assembly: FrameAssembly): FrameSyntaxDecodeResult {
        val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
        require(nonNullAssembly.isComplete) { "Frame assembly is incomplete" }
        return decodeTiles(nonNullAssembly, -1)
    }

    /** Structurally decodes one tile from a partial Large Scale Tile camera-frame assembly.
     * 
     * Tiles other than `tileIndex` are represented by empty structural state in the returned
     * frame-sized result. The assembly must contain the selected tile bitstream but need not cover
     * any other camera-frame tile.
     * 
     * @param assembly the partial camera-frame assembly containing the selected tile
     * @param tileIndex the zero-based source tile index in frame raster order
     * @return a frame-sized structural result containing the selected decoded tile
     * @throws InvalidFrameSyntaxException if decoded tile syntax violates AV1 constraints
     */
    fun decodeTile(assembly: FrameAssembly, tileIndex: Int): FrameSyntaxDecodeResult {
        val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
        require(!(tileIndex < 0 || tileIndex >= nonNullAssembly.totalTiles())) { "tileIndex out of range: $tileIndex" }
        nonNullAssembly.tileBitstream(tileIndex)
        return decodeTiles(nonNullAssembly, tileIndex)
    }

    /** Decodes all tiles or one selected tile into frame-sized structural arrays.
     * 
     * @param assembly the complete or partial frame assembly
     * @param selectedTileIndex the selected tile index, or `-1` to decode every tile
     * @return the frame-sized structural decode result
     */
    private fun decodeTiles(
        assembly: FrameAssembly,
        selectedTileIndex: Int
    ): FrameSyntaxDecodeResult {
        val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
        val tileCount = nonNullAssembly.totalTiles()
        val referenceMotionVectorProjection: ReferenceMotionVectorProjection =
            ReferenceMotionVectorProjection.create(
                nonNullAssembly,
                referenceFrameSyntaxStates
            )
        val referenceSegmentIdMap = referenceSegmentIdMap(nonNullAssembly)
        val currentSegmentIdMap: SegmentIdMap = SegmentIdMap.create(nonNullAssembly)
        val segmentation = nonNullAssembly.frameHeader().segmentation()
        if (segmentation.enabled() && !segmentation.updateMap() && referenceSegmentIdMap != null) {
            currentSegmentIdMap.copyFrom(referenceSegmentIdMap)
        }
        val tileRoots = arrayOfNullsCompat<Array<TilePartitionTreeReader.Node>>(tileCount)
        val decodedTemporalMotionFields: Array<TemporalMotionField> =
            arrayOfNullsCompat<TemporalMotionField>(tileCount)
        val finalTileCdfContexts: Array<CdfContext> = arrayOfNullsCompat<CdfContext>(tileCount)
        val restorationUnitMap: RestorationUnitMap =
            RestorationUnitMap.createEmpty(nonNullAssembly)
        val inheritedCdfContext = referenceCdfContext()
        for (tileIndex in 0..<tileCount) {
            tileRoots[tileIndex] = arrayOfNullsCompat<TilePartitionTreeReader.Node>(0)
            decodedTemporalMotionFields[tileIndex] = TemporalMotionField(0, 0)
            if (selectedTileIndex >= 0 && tileIndex != selectedTileIndex) {
                finalTileCdfContexts[tileIndex] = CdfContext.createDefault(
                    nonNullAssembly.frameHeader().quantization().baseQIndex()
                )
                continue
            }
            val tileContext = createTileContext(
                nonNullAssembly,
                tileIndex,
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap,
                inheritedCdfContext
            )
            val treeReader = TilePartitionTreeReader(tileContext, strictStdCompliance)
            try {
                tileRoots[tileIndex] = treeReader.readTile()
            } catch (exception: InvalidDisplacementVectorException) {
                throw InvalidFrameSyntaxException(exception.message, exception)
            }
            restorationUnitMap.mergeFrom(tileContext.restorationUnitMap())
            decodedTemporalMotionFields[tileIndex] = tileContext.decodedTemporalMotionField()
            finalTileCdfContexts[tileIndex] = tileContext.cdfContext()
        }
        return FrameSyntaxDecodeResult.fromOwnedFrameRelativeState(
            nonNullAssembly,
            tileRoots,
            decodedTemporalMotionFields,
            restorationUnitMap,
            finalTileCdfContexts,
            currentSegmentIdMap
        )
    }

    /** Creates one tile-local decode context with compatible inherited entropy state.
     * 
     * @param assembly the completed frame assembly that owns the tile
     * @param tileIndex the zero-based tile index in frame order
     * @param referenceMotionVectorProjection the immutable current-frame temporal projection
     * @param currentSegmentIdMap the mutable current-frame segment-id map shared by all tiles
     * @param referenceSegmentIdMap the immutable primary-reference segment-id map, or `null`
     * @param inheritedCdfContext the inherited frame CDF context, or `null` to use defaults
     * @return one tile-local decode context
     */
    private fun createTileContext(
        assembly: FrameAssembly,
        tileIndex: Int,
        referenceMotionVectorProjection: ReferenceMotionVectorProjection,
        currentSegmentIdMap: SegmentIdMap,
        referenceSegmentIdMap: SegmentIdMap?,
        inheritedCdfContext: CdfContext?
    ): TileDecodeContext {
        if (inheritedCdfContext == null) {
            return TileDecodeContext.createWithOwnedCdfContext(
                assembly,
                tileIndex,
                CdfContext.createDefault(
                    assembly.frameHeader().quantization().baseQIndex()
                ),
                referenceMotionVectorProjection,
                currentSegmentIdMap,
                referenceSegmentIdMap
            )
        }
        return TileDecodeContext.create(
            assembly,
            tileIndex,
            inheritedCdfContext,
            referenceMotionVectorProjection,
            currentSegmentIdMap,
            referenceSegmentIdMap
        )
    }

    /** Returns the compatible primary-reference segment-id map required by the current frame.
     * 
     * AV1 temporal segmentation prediction and non-updating segmentation maps use the syntax
     * snapshot selected by `primary_ref_frame`. A map from differently sized coded dimensions is
     * not compatible and is treated as absent.
     * 
     * @param assembly the current completed frame assembly
     * @return an independent compatible reference map, or `null`
     */
    private fun referenceSegmentIdMap(assembly: FrameAssembly): SegmentIdMap? {
        val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
        val frameHeader = nonNullAssembly.frameHeader()
        val segmentation = frameHeader.segmentation()
        if (!segmentation.enabled() || (segmentation.updateMap() && !segmentation.temporalUpdate())) {
            return null
        }

        val primaryRefFrame = frameHeader.primaryRefFrame()
        if (primaryRefFrame < 0 || primaryRefFrame >= 7) {
            return null
        }
        val primarySlot = frameHeader.referenceFrameIndex(primaryRefFrame)
        if (primarySlot < 0 || primarySlot >= referenceFrameSyntaxStates!!.size) {
            return null
        }
        val referenceState = referenceFrameSyntaxStates[primarySlot]
        if (referenceState == null) {
            return null
        }

        val referenceMap = referenceState.segmentIdMap()
        val width4 = ((frameHeader.frameSize().codedWidth() + 7) shr 3) shl 1
        val height4 = ((frameHeader.frameSize().height() + 7) shr 3) shl 1
        return if (referenceMap.hasDimensions(width4, height4)) referenceMap else null
    }

    /** Returns the single frame CDF context selected by the reference frame, or `null` when no
     * reference context exists.
     * 
     * @return the inherited frame CDF context, or `null`
     */
    private fun referenceCdfContext(): CdfContext? {
        if (referenceCdfFrameSyntaxState == null) {
            return null
        }
        return referenceCdfFrameSyntaxState.savedFrameCdfContextTemplate()
    }
}
