// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.decode.TileDecodeContext.TemporalMotionBlock
import org.glavo.avif.internal.av1.decode.TileDecodeContext.TemporalMotionField
import org.glavo.avif.internal.av1.entropy.CdfContext
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.FrameHeader.TilingInfo
import org.glavo.avif.internal.av1.model.SequenceHeader


/** Stores the compact syntax state required when a decoded frame remains in a reference slot.
 * 
 * Partition trees, transform layouts, and coefficient arrays are reconstruction-only data and are
 * intentionally excluded. Instances retain the saved frame CDF, segment identifiers, temporal
 * motion field, and frame-header history required by later AV1 syntax decoding. */
class ReferenceFrameSyntaxState private constructor(
    sequenceHeader: SequenceHeader,
    frameHeader: FrameHeader,
    referenceFrameHeaders: Array<FrameHeader?>?,
    decodedTemporalMotionFields: Array<TemporalMotionField>,
    segmentIdMap: SegmentIdMap,
    savedFrameCdfContext: CdfContext
) {
    /** The sequence header active for the stored frame. */
    private val sequenceHeader: SequenceHeader

    /** The stored frame header. */
    private val frameHeader: FrameHeader

    /** The reference-frame headers indexed by the stored frame's runtime reference slots. */
    private val referenceFrameHeaders: Array<FrameHeader?>?

    /** The tile-local temporal motion fields produced by the stored frame. */
    private val decodedTemporalMotionFields: Array<TemporalMotionField>

    /** The exact stored-frame segment identifiers. */
    private val segmentIdMap: SegmentIdMap

    /** The inheritable frame CDF context with adaptive counters reset. */
    private val savedFrameCdfContext: CdfContext

    init {
        this.sequenceHeader =
            requireNotNullCompat(sequenceHeader, "sequenceHeader")
        this.frameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        val checkedHeaders = requireNotNullCompat(
            referenceFrameHeaders,
            "referenceFrameHeaders"
        )
        require(checkedHeaders.size == 8) { "referenceFrameHeaders.length != 8: " + checkedHeaders.size }
        this.referenceFrameHeaders = checkedHeaders

        val checkedFields = requireNotNullCompat(
            decodedTemporalMotionFields,
            "decodedTemporalMotionFields"
        )
        val expectedTileCount = frameHeader.tiling().columns() * frameHeader.tiling().rows()
        require(checkedFields.size == expectedTileCount) { "decodedTemporalMotionFields.length != tile count: " + checkedFields.size }
        for (index in checkedFields.indices) {
            requireNotNullCompat(
                checkedFields[index],
                "decodedTemporalMotionFields[$index]"
            )
        }
        this.decodedTemporalMotionFields = checkedFields
        this.segmentIdMap = requireNotNullCompat(segmentIdMap, "segmentIdMap")
        this.savedFrameCdfContext =
            requireNotNullCompat(savedFrameCdfContext, "savedFrameCdfContext")
    }

    /** Returns the sequence header active for the stored frame.
     * 
     * @return the stored sequence header
     */
    fun sequenceHeader(): SequenceHeader {
        return sequenceHeader
    }

    /** Returns the stored frame header.
     * 
     * @return the stored frame header
     */
    fun frameHeader(): FrameHeader {
        return frameHeader
    }

    /** Returns one reference-frame header used by the stored frame.
     * 
     * @param referenceFrame the internal LAST..ALTREF reference index
     * @return the referenced frame header, or `null` when unavailable
     */
    fun referenceFrameHeader(referenceFrame: Int): FrameHeader? {
        val slot = frameHeader.referenceFrameIndex(referenceFrame)
        return if (slot >= 0 && slot < referenceFrameHeaders!!.size) referenceFrameHeaders[slot] else null
    }

    /** Returns an independent copy of the stored segment-id map.
     * 
     * @return the stored segment identifiers
     */
    fun segmentIdMap(): SegmentIdMap {
        return segmentIdMap.copy()
    }

    /** Returns an independent inheritable frame CDF context.
     * 
     * @return the saved frame CDF
     */
    fun savedFrameCdfContext(): CdfContext {
        return savedFrameCdfContext.copy()
    }

    /** Returns the internal saved CDF template for immediate package-local copying.
     * 
     * The caller must not modify or retain the returned context. This view exists so tile setup
     * can copy the immutable template once instead of first creating an intermediate snapshot.
     * 
     * @return the internal saved frame CDF template
     */
    fun savedFrameCdfContextTemplate(): CdfContext {
        return savedFrameCdfContext
    }

    /** Returns the temporal motion block at one frame-relative 8x8 coordinate.
     * 
     * @param x8 the frame-relative X coordinate in 8x8 units
     * @param y8 the frame-relative Y coordinate in 8x8 units
     * @return the immutable temporal motion block, or `null` outside the stored field
     */
    fun decodedTemporalMotionBlockAt(x8: Int, y8: Int): TemporalMotionBlock? {
        if (x8 < 0 || y8 < 0) {
            return null
        }
        val frameWidth8 = (frameHeader.frameSize().codedWidth() + 7) shr 3
        val frameHeight8 = (frameHeader.frameSize().height() + 7) shr 3
        if (x8 >= frameWidth8 || y8 >= frameHeight8) {
            return null
        }

        val superblockSize8 = if (sequenceHeader.features().use128x128Superblocks()) 16 else 8
        val tiling = frameHeader.tiling()
        val tileColumn: Int = containingTileColumn(tiling, x8 / superblockSize8)
        val tileRow: Int = containingTileRow(tiling, y8 / superblockSize8)
        val tileIndex = tileRow * tiling.columns() + tileColumn
        val localX8 = x8 - tiling.columnStartSuperblock(tileColumn) * superblockSize8
        val localY8 = y8 - tiling.rowStartSuperblock(tileRow) * superblockSize8
        val field = decodedTemporalMotionFields[tileIndex]
        if (localX8 >= field.width8() || localY8 >= field.height8()) {
            return null
        }
        return field.block(localX8, localY8)
    }

    companion object {
        /** Creates a compact reference state from one complete structural decode result.
         * 
         * @param syntaxDecodeResult the complete structural decode result
         * @return a compact reference state using the result's saved frame CDF
         */
        fun from(syntaxDecodeResult: FrameSyntaxDecodeResult): ReferenceFrameSyntaxState {
            val checkedResult = requireNotNullCompat(
                syntaxDecodeResult,
                "syntaxDecodeResult"
            )
            return fromOwnedSavedFrameCdfContext(
                checkedResult,
                checkedResult.savedFrameCdfContext()
            )
        }

        /** Creates a compact reference state with an explicitly selected saved frame CDF.
         * 
         * @param syntaxDecodeResult the complete structural decode result
         * @param savedFrameCdfContext the CDF context to inherit from the stored frame
         * @return a compact reference state independent of the supplied mutable snapshots
         */
        fun from(
            syntaxDecodeResult: FrameSyntaxDecodeResult,
            savedFrameCdfContext: CdfContext
        ): ReferenceFrameSyntaxState {
            return fromResult(syntaxDecodeResult, savedFrameCdfContext, true)
        }

        /** Creates compact reference state by taking ownership of a fresh saved frame CDF.
         * 
         * The caller must not access or modify `ownedSavedFrameCdfContext` after this method returns.
         * Other retained state is obtained as independent snapshots from `syntaxDecodeResult`.
         * 
         * @param syntaxDecodeResult the complete structural decode result
         * @param ownedSavedFrameCdfContext the fresh CDF context transferred to the reference state
         * @return compact reference state backed by the transferred saved CDF
         */
        fun fromOwnedSavedFrameCdfContext(
            syntaxDecodeResult: FrameSyntaxDecodeResult,
            ownedSavedFrameCdfContext: CdfContext
        ): ReferenceFrameSyntaxState {
            return fromResult(syntaxDecodeResult, ownedSavedFrameCdfContext, false)
        }

        /** Creates compact reference state from one structural result.
         * 
         * @param syntaxDecodeResult the complete structural decode result
         * @param savedFrameCdfContext the copied or transferred saved frame CDF
         * @param copySavedFrameCdfContext whether to copy the supplied saved frame CDF
         * @return compact reference state independent of the structural result
         */
        fun fromResult(
            syntaxDecodeResult: FrameSyntaxDecodeResult,
            savedFrameCdfContext: CdfContext,
            copySavedFrameCdfContext: Boolean
        ): ReferenceFrameSyntaxState {
            val checkedResult = requireNotNullCompat(
                syntaxDecodeResult,
                "syntaxDecodeResult"
            )
            val assembly = checkedResult.assembly()
            val frameHeader = assembly.frameHeader()
            val referenceFrameHeaders: Array<FrameHeader?>? = arrayOfNullsCompat<FrameHeader?>(8)
            for (referenceFrame in 0..6) {
                val slot = frameHeader.referenceFrameIndex(referenceFrame)
                if (slot >= 0 && slot < referenceFrameHeaders!!.size) {
                    referenceFrameHeaders[slot] = assembly.referenceFrameHeader(referenceFrame)
                }
            }
            return ReferenceFrameSyntaxState(
                assembly.sequenceHeader(),
                frameHeader,
                referenceFrameHeaders,
                checkedResult.decodedTemporalMotionFields(),
                checkedResult.segmentIdMap(),
                if (copySavedFrameCdfContext)
                    requireNotNullCompat(savedFrameCdfContext, "savedFrameCdfContext")
                        .copy()
                else
                    requireNotNullCompat(savedFrameCdfContext, "savedFrameCdfContext")
            )
        }

        /** Returns the tile-column interval containing one superblock coordinate.
         * 
         * @param tiling the frame tile layout
         * @param coordinate the superblock coordinate to locate
         * @return the zero-based containing interval
         */
        fun containingTileColumn(tiling: TilingInfo, coordinate: Int): Int {
            var low = 0
            var high = tiling.columns()
            while (low + 1 < high) {
                val middle = (low + high) ushr 1
                if (coordinate < tiling.columnStartSuperblock(middle)) {
                    high = middle
                } else {
                    low = middle
                }
            }
            return low
        }

        /** Returns the tile-row interval containing one superblock coordinate.
         * 
         * @param tiling the frame tile layout
         * @param coordinate the superblock coordinate to locate
         * @return the zero-based containing interval
         */
        fun containingTileRow(tiling: TilingInfo, coordinate: Int): Int {
            var low = 0
            var high = tiling.rows()
            while (low + 1 < high) {
                val middle = (low + high) ushr 1
                if (coordinate < tiling.rowStartSuperblock(middle)) {
                    high = middle
                } else {
                    low = middle
                }
            }
            return low
        }
    }
}
