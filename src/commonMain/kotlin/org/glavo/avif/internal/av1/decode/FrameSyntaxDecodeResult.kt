// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.TileDecodeContext.TemporalMotionField
import org.glavo.avif.internal.av1.entropy.CdfContext
import org.glavo.avif.internal.av1.model.FrameAssembly


/** Structural frame-decode result produced before pixel reconstruction begins. */
class FrameSyntaxDecodeResult private constructor(
    assembly: FrameAssembly,
    tileRoots: Array<Array<TilePartitionTreeReader.Node>>,
    decodedTemporalMotionFields: Array<TemporalMotionField>,
    restorationUnitMap: RestorationUnitMap,
    finalTileCdfContexts: Array<CdfContext>,
    segmentIdMap: SegmentIdMap,
    frameRelativeTileRoots: Boolean,
    takeOwnership: Boolean
) {
    /** The fully assembled frame that was structurally decoded. */
    private val assembly: FrameAssembly

    /** The frame-relative decoded top-level partition roots for each tile in frame order. */
    private val tileRoots: Array<Array<TilePartitionTreeReader.Node>>

    /** The tile-local temporal motion fields produced while decoding the current frame. */
    private val decodedTemporalMotionFields: Array<TemporalMotionField>

    /** The decoded frame-level loop-restoration unit syntax. */
    private val restorationUnitMap: RestorationUnitMap

    /** The exact current-frame segment identifiers on the padded 4x4 coding grid. */
    private val segmentIdMap: SegmentIdMap

    /** The final tile-local CDF contexts produced while decoding the current frame. */
    private val finalTileCdfContexts: Array<CdfContext>

    /** Creates one structural frame-decode result.
     * 
     * @param assembly the fully assembled frame that was structurally decoded
     * @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
     * @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
     * @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
     */
    constructor(
        assembly: FrameAssembly,
        tileRoots: Array<Array<TilePartitionTreeReader.Node>>,
        decodedTemporalMotionFields: Array<TemporalMotionField>,
        finalTileCdfContexts: Array<CdfContext>
    ) : this(
        assembly,
        tileRoots,
        decodedTemporalMotionFields,
        RestorationUnitMap.Companion.createEmpty(
            requireNotNullCompat(
                assembly,
                "assembly"
            )
        ),
        finalTileCdfContexts
    )

    /** Creates one structural frame-decode result.
     * 
     * @param assembly the fully assembled frame that was structurally decoded
     * @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
     * @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
     * @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
     * @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
     */
    /** Creates one structural frame-decode result.
     * 
     * Final tile CDF contexts default to fresh `CdfContext.createDefault()` copies.
     * 
     * @param assembly the fully assembled frame that was structurally decoded
     * @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
     * @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
     */
    constructor(
        assembly: FrameAssembly,
        tileRoots: Array<Array<TilePartitionTreeReader.Node>>,
        decodedTemporalMotionFields: Array<TemporalMotionField>,
        restorationUnitMap: RestorationUnitMap = RestorationUnitMap.Companion.createEmpty(
            requireNotNullCompat(
                assembly,
                "assembly"
            )
        ),
        finalTileCdfContexts: Array<CdfContext> = createDefaultTileCdfContexts(
            requireNotNullCompat(
                assembly,
                "assembly"
            ).totalTiles()
        )
    ) : this(
        assembly,
        tileRoots,
        decodedTemporalMotionFields,
        restorationUnitMap,
        finalTileCdfContexts,
        SegmentIdMap.Companion.fromDecodedBlocks(assembly, tileRoots)
    )

    /** Creates one structural frame-decode result with an exact segment-id map.
     * 
     * @param assembly the fully assembled frame that was structurally decoded
     * @param tileRoots the decoded top-level partition roots for each tile in frame order
     * @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
     * @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
     * @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
     * @param segmentIdMap the exact current-frame segment identifiers
     * @param frameRelativeTileRoots whether `tileRoots` already use frame-relative coordinates
     */
    /** Creates one structural frame-decode result with an exact segment-id map.
     * 
     * @param assembly the fully assembled frame that was structurally decoded
     * @param tileRoots the tile-relative decoded top-level partition roots for each tile in frame order
     * @param decodedTemporalMotionFields the tile-local temporal motion fields produced while decoding the current frame
     * @param restorationUnitMap the decoded frame-level loop-restoration unit syntax
     * @param finalTileCdfContexts the final tile-local CDF contexts produced while decoding the current frame
     * @param segmentIdMap the exact current-frame segment identifiers
     */
    internal constructor(
        assembly: FrameAssembly,
        tileRoots: Array<Array<TilePartitionTreeReader.Node>>,
        decodedTemporalMotionFields: Array<TemporalMotionField>,
        restorationUnitMap: RestorationUnitMap,
        finalTileCdfContexts: Array<CdfContext>,
        segmentIdMap: SegmentIdMap,
        frameRelativeTileRoots: Boolean = false
    ) : this(
        assembly,
        tileRoots,
        decodedTemporalMotionFields,
        restorationUnitMap,
        finalTileCdfContexts,
        segmentIdMap,
        frameRelativeTileRoots,
        false
    )

    init {
        this.assembly = requireNotNullCompat(assembly, "assembly")
        requireNotNullCompat(tileRoots, "tileRoots")
        requireNotNullCompat(
            decodedTemporalMotionFields,
            "decodedTemporalMotionFields"
        )
        requireNotNullCompat(finalTileCdfContexts, "finalTileCdfContexts")
        require(tileRoots.size == assembly.totalTiles()) { "tileRoots.length != totalTiles: " + tileRoots.size }
        require(decodedTemporalMotionFields.size == assembly.totalTiles()) { "decodedTemporalMotionFields.length != totalTiles: " + decodedTemporalMotionFields.size }
        require(finalTileCdfContexts.size == assembly.totalTiles()) { "finalTileCdfContexts.length != totalTiles: " + finalTileCdfContexts.size }

        val normalizedTileRoots = if (frameRelativeTileRoots)
            tileRoots
        else
            FrameLocalPartitionTrees.create(assembly, tileRoots)
        for (i in normalizedTileRoots.indices) {
            requireNotNullCompat(
                normalizedTileRoots[i],
                "tileRoots[$i]"
            )
        }
        for (i in decodedTemporalMotionFields.indices) {
            requireNotNullCompat(
                decodedTemporalMotionFields[i],
                "decodedTemporalMotionFields[$i]"
            )
        }
        for (i in finalTileCdfContexts.indices) {
            requireNotNullCompat(
                finalTileCdfContexts[i],
                "finalTileCdfContexts[$i]"
            )
        }

        if (takeOwnership) {
            this.tileRoots = normalizedTileRoots
            this.decodedTemporalMotionFields = decodedTemporalMotionFields
            this.restorationUnitMap =
                requireNotNullCompat(restorationUnitMap, "restorationUnitMap")
            this.segmentIdMap = requireNotNullCompat(segmentIdMap, "segmentIdMap")
            this.finalTileCdfContexts = finalTileCdfContexts
        } else {
        this.tileRoots = arrayOfNullsCompat<Array<TilePartitionTreeReader.Node>>(normalizedTileRoots.size)
        for (i in normalizedTileRoots.indices) {
            this.tileRoots[i] =
                copyOfCompat(normalizedTileRoots[i], normalizedTileRoots[i].size)
        }
        this.decodedTemporalMotionFields =
            arrayOfNullsCompat<TemporalMotionField>(decodedTemporalMotionFields.size)
        for (i in decodedTemporalMotionFields.indices) {
            this.decodedTemporalMotionFields[i] = decodedTemporalMotionFields[i].copy()
        }
        this.restorationUnitMap =
            requireNotNullCompat(restorationUnitMap, "restorationUnitMap")
                .copy()
        this.segmentIdMap =
            requireNotNullCompat(segmentIdMap, "segmentIdMap").copy()
        this.finalTileCdfContexts = arrayOfNullsCompat<CdfContext>(finalTileCdfContexts.size)
        for (i in finalTileCdfContexts.indices) {
            this.finalTileCdfContexts[i] = finalTileCdfContexts[i].copy()
        }
        }
    }

    /** Returns the fully assembled frame that was structurally decoded.
     * 
     * @return the fully assembled frame that was structurally decoded
     */
    fun assembly(): FrameAssembly {
        return assembly
    }

    /** Returns the number of decoded tiles in this frame result.
     * 
     * @return the number of decoded tiles in this frame result
     */
    fun tileCount(): Int {
        return tileRoots.size
    }

    /** Returns a snapshot of the frame-relative decoded top-level partition roots for every tile.
     * 
     * @return a snapshot of the frame-relative decoded top-level partition roots for every tile
     */
    fun tileRoots(): Array<Array<TilePartitionTreeReader.Node>> {
        val copy: Array<Array<TilePartitionTreeReader.Node>> =
            arrayOfNullsCompat<Array<TilePartitionTreeReader.Node>>(tileRoots.size)
        for (i in tileRoots.indices) {
            copy[i] = copyOfCompat(tileRoots[i], tileRoots[i].size)
        }
        return copy
    }

    /** Returns a snapshot of the frame-relative decoded top-level partition roots for one tile.
     * 
     * @param tileIndex the zero-based tile index in frame order
     * @return a snapshot of the frame-relative decoded top-level partition roots for one tile
     */
    fun tileRoots(tileIndex: Int): Array<TilePartitionTreeReader.Node> {
        val checkedTileIndex = checkedTileIndex(tileIndex)
        return copyOfCompat(tileRoots[checkedTileIndex], tileRoots[checkedTileIndex].size)
    }

    /** Returns an independent copy of the decoded frame's segment-id map.
     * 
     * @return an independent segment-id map copy
     */
    fun segmentIdMap(): SegmentIdMap {
        return segmentIdMap.copy()
    }

    /** Returns a snapshot of the tile-local temporal motion fields for every tile.
     * 
     * @return a snapshot of the tile-local temporal motion fields for every tile
     */
    fun decodedTemporalMotionFields(): Array<TemporalMotionField> {
        val copy: Array<TemporalMotionField> =
            arrayOfNullsCompat<TemporalMotionField>(decodedTemporalMotionFields.size)
        for (i in decodedTemporalMotionFields.indices) {
            copy[i] = decodedTemporalMotionFields[i].copy()
        }
        return copy
    }

    /** Returns a snapshot of the tile-local temporal motion field for one tile.
     * 
     * @param tileIndex the zero-based tile index in frame order
     * @return a snapshot of the tile-local temporal motion field for one tile
     */
    fun decodedTemporalMotionField(tileIndex: Int): TemporalMotionField {
        return decodedTemporalMotionFields[checkedTileIndex(tileIndex)].copy()
    }

    /** Returns a snapshot of the decoded loop-restoration unit syntax.
     * 
     * @return a snapshot of the decoded loop-restoration unit syntax
     */
    fun restorationUnitMap(): RestorationUnitMap {
        return restorationUnitMap.copy()
    }

    /** Returns a snapshot of the final tile-local CDF contexts for every tile.
     * 
     * @return a snapshot of the final tile-local CDF contexts for every tile
     */
    fun finalTileCdfContexts(): Array<CdfContext> {
        val copy: Array<CdfContext> = arrayOfNullsCompat<CdfContext>(finalTileCdfContexts.size)
        for (i in finalTileCdfContexts.indices) {
            copy[i] = finalTileCdfContexts[i].copy()
        }
        return copy
    }

    /** Returns a snapshot of the final tile-local CDF context for one tile.
     * 
     * @param tileIndex the zero-based tile index in frame order
     * @return a snapshot of the final tile-local CDF context for one tile
     */
    fun finalTileCdfContext(tileIndex: Int): CdfContext {
        return finalTileCdfContexts[checkedTileIndex(tileIndex)].copy()
    }

    /** Returns the frame CDF context saved for later `primary_ref_frame` inheritance.
     * 
     * The context is selected by `context_update_tile_id`. Its thresholds retain the selected
     * tile's final values, while every adaptive-symbol counter is reset as required at the frame
     * context update boundary. The returned context is independent of this result.
     * 
     * @return an independent inheritable frame CDF context
     */
    fun savedFrameCdfContext(): CdfContext {
        return finalTileCdfContexts[assembly.frameHeader().tiling().updateTileIndex()]
            .copyWithResetSymbolCounters()
    }

    /** Validates and returns one tile index.
     * 
     * @param tileIndex the zero-based tile index in frame order
     * @return the validated tile index
     */
    private fun checkedTileIndex(tileIndex: Int): Int {
        if (tileIndex < 0 || tileIndex >= tileRoots.size) {
            throw IndexOutOfBoundsException("tileIndex out of range: $tileIndex")
        }
        return tileIndex
    }

    companion object {
        /** Creates a frame result by taking ownership of freshly decoded frame-relative state.
         * 
         * The caller must not access or modify any supplied array, map, field, or CDF context after
         * this method returns. This entry point is reserved for the structural decoder's completed
         * tile state; public constructors continue to make independent snapshots.
         * 
         * @param assembly the fully assembled frame that was structurally decoded
         * @param tileRoots the owned frame-relative partition roots for each tile
         * @param decodedTemporalMotionFields the owned tile-local temporal motion fields
         * @param restorationUnitMap the owned frame-level loop-restoration unit syntax
         * @param finalTileCdfContexts the owned final tile-local CDF contexts
         * @param segmentIdMap the owned exact current-frame segment identifiers
         * @return a structural frame result backed by the supplied state
         */
        fun fromOwnedFrameRelativeState(
            assembly: FrameAssembly,
            tileRoots: Array<Array<TilePartitionTreeReader.Node>>,
            decodedTemporalMotionFields: Array<TemporalMotionField>,
            restorationUnitMap: RestorationUnitMap,
            finalTileCdfContexts: Array<CdfContext>,
            segmentIdMap: SegmentIdMap
        ): FrameSyntaxDecodeResult {
            return FrameSyntaxDecodeResult(
                assembly,
                tileRoots,
                decodedTemporalMotionFields,
                restorationUnitMap,
                finalTileCdfContexts,
                segmentIdMap,
                true,
                true
            )
        }

        /** Creates default tile-local CDF contexts for the supplied tile count.
         * 
         * @param tileCount the number of tiles in the frame
         * @return default tile-local CDF contexts for the supplied tile count
         */
        fun createDefaultTileCdfContexts(tileCount: Int): Array<CdfContext> {
            val contexts: Array<CdfContext> = arrayOfNullsCompat<CdfContext>(tileCount)
            for (i in 0..<tileCount) {
                contexts[i] = CdfContext.createDefault()
            }
            return contexts
        }
    }
}
