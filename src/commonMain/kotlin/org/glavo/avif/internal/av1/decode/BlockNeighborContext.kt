// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.log2
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.av1.Av1FrameType
import org.glavo.avif.internal.av1.decode.BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader.BlockHeader
import org.glavo.avif.internal.av1.decode.TileDecodeContext.TemporalMotionBlock
import org.glavo.avif.internal.av1.decode.TileDecodeContext.TemporalMotionField
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode
import org.glavo.avif.internal.av1.model.CompoundPredictionType
import org.glavo.avif.internal.av1.model.FrameHeader.GlobalMotionType
import org.glavo.avif.internal.av1.model.FrameHeader.InterpolationFilter
import org.glavo.avif.internal.av1.model.InterMotionVector
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode
import org.glavo.avif.internal.av1.model.MotionVector
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode
import org.glavo.avif.internal.av1.model.TransformSize
import org.glavo.avif.internal.av1.model.TransformUnit


/** Tile-local neighbor context state used to derive block-level syntax contexts while scanning a tile. */
class BlockNeighborContext private constructor(
    /** The tile width rounded up to 4x4 units. */
    private val tileWidth4: Int,
    /** The tile height rounded up to 4x4 units. */
    private val tileHeight4: Int,
    /** The coded frame width rounded up to 4x4 units on the 8x8 motion-vector grid. */
    private val frameWidth4: Int,
    /** The coded frame height rounded up to 4x4 units on the 8x8 motion-vector grid. */
    private val frameHeight4: Int,
    /** The horizontal chroma subsampling shift, or `0` when chroma uses full horizontal resolution. */
    private val chromaSubsamplingX: Int,
    /** The vertical chroma subsampling shift, or `0` when chroma uses full vertical resolution. */
    private val chromaSubsamplingY: Int,
    decodedTemporalMotionField: TemporalMotionField,
    referenceMotionVectorProjection: ReferenceMotionVectorProjection,
    currentSegmentIdMap: SegmentIdMap,
    /** Whether decoded block segment identifiers replace the current map footprint. */
    private val updateSegmentIdMap: Boolean,
    /** The tile's frame-relative horizontal origin in 8x8 units. */
    private val tileStartX8: Int,
    /** The tile's frame-relative vertical origin in 8x8 units. */
    private val tileStartY8: Int,
    storedBlocks: Array<StoredBlock>,
    aboveIntra: ByteArray,
    leftIntra: ByteArray,
    aboveSkip: ByteArray,
    leftSkip: ByteArray,
    aboveSkipMode: ByteArray,
    leftSkipMode: ByteArray,
    aboveCompoundReference: ByteArray,
    leftCompoundReference: ByteArray,
    aboveCompoundPredictionType: ByteArray,
    leftCompoundPredictionType: ByteArray,
    aboveReferenceFrame0: ByteArray,
    leftReferenceFrame0: ByteArray,
    aboveReferenceFrame1: ByteArray,
    leftReferenceFrame1: ByteArray,
    aboveMotionVector0: Array<InterMotionVector>,
    leftMotionVector0: Array<InterMotionVector>,
    aboveMotionVector1: Array<InterMotionVector>,
    leftMotionVector1: Array<InterMotionVector>,
    aboveUsesNewMotionVector: ByteArray,
    leftUsesNewMotionVector: ByteArray,
    aboveInterpolationFilterHorizontal: ByteArray,
    leftInterpolationFilterHorizontal: ByteArray,
    aboveInterpolationFilterVertical: ByteArray,
    leftInterpolationFilterVertical: ByteArray,
    aboveSegmentPredicted: ByteArray,
    leftSegmentPredicted: ByteArray,
    abovePaletteSize: ByteArray,
    leftPaletteSize: ByteArray,
    aboveChromaPaletteSize: ByteArray,
    leftChromaPaletteSize: ByteArray,
    abovePaletteEntries: Array<Array<IntArray>>,
    leftPaletteEntries: Array<Array<IntArray>>,
    aboveMode: Array<LumaIntraPredictionMode>,
    leftMode: Array<LumaIntraPredictionMode>,
    abovePartition: ByteArray,
    leftPartition: ByteArray,
    aboveTransformWidthLog2: ByteArray,
    leftTransformHeightLog2: ByteArray,
    aboveInterTransformWidthLog2: ByteArray,
    leftInterTransformHeightLog2: ByteArray,
    aboveLumaCoefficientContext: ByteArray,
    leftLumaCoefficientContext: ByteArray,
    aboveChromaCoefficientContext: Array<ByteArray>,
    leftChromaCoefficientContext: Array<ByteArray>
) {
    /** The tile-local temporal motion field produced while decoding the current frame. */
    private val decodedTemporalMotionField: TemporalMotionField

    /** The current frame's immutable projected reference motion field. */
    private val referenceMotionVectorProjection: ReferenceMotionVectorProjection

    /** The mutable current-frame segment identifiers shared by all tiles. */
    private val currentSegmentIdMap: SegmentIdMap

    /** The stored decoded block map indexed in tile-relative 4x4 units. */
    private val storedBlocks: Array<StoredBlock>

    /** Reusable candidate workspace whose active prefix is copied into each returned context. */
    private val provisionalCandidateScratch: Array<ProvisionalMotionVectorCandidate> =
        arrayOfNullsCompat<ProvisionalMotionVectorCandidate>(
            PROVISIONAL_CANDIDATE_CAPACITY
        )

    /** Reusable reference-count workspace for the immediately reduced syntax contexts. */
    private val referenceCountScratch = IntArray(4)

    /** The above-edge intra flags indexed in 4x4 units. */
    private val aboveIntra: ByteArray

    /** The left-edge intra flags indexed in 4x4 units. */
    private val leftIntra: ByteArray

    /** The above-edge skip flags indexed in 4x4 units. */
    private val aboveSkip: ByteArray

    /** The left-edge skip flags indexed in 4x4 units. */
    private val leftSkip: ByteArray

    /** The above-edge skip-mode flags indexed in 4x4 units. */
    private val aboveSkipMode: ByteArray

    /** The left-edge skip-mode flags indexed in 4x4 units. */
    private val leftSkipMode: ByteArray

    /** The above-edge compound-reference flags indexed in 4x4 units. */
    private val aboveCompoundReference: ByteArray

    /** The left-edge compound-reference flags indexed in 4x4 units. */
    private val leftCompoundReference: ByteArray

    /** The above-edge compound-prediction type context values indexed in 4x4 units. */
    private val aboveCompoundPredictionType: ByteArray

    /** The left-edge compound-prediction type context values indexed in 4x4 units. */
    private val leftCompoundPredictionType: ByteArray

    /** The above-edge primary reference-frame indices indexed in 4x4 units. */
    private val aboveReferenceFrame0: ByteArray

    /** The left-edge primary reference-frame indices indexed in 4x4 units. */
    private val leftReferenceFrame0: ByteArray

    /** The above-edge secondary reference-frame indices indexed in 4x4 units. */
    private val aboveReferenceFrame1: ByteArray

    /** The left-edge secondary reference-frame indices indexed in 4x4 units. */
    private val leftReferenceFrame1: ByteArray

    /** The above-edge primary motion vectors indexed in 4x4 units. */
    private val aboveMotionVector0: Array<InterMotionVector>

    /** The left-edge primary motion vectors indexed in 4x4 units. */
    private val leftMotionVector0: Array<InterMotionVector>

    /** The above-edge secondary motion vectors indexed in 4x4 units. */
    private val aboveMotionVector1: Array<InterMotionVector>

    /** The left-edge secondary motion vectors indexed in 4x4 units. */
    private val leftMotionVector1: Array<InterMotionVector>

    /** The above-edge `NEWMV` usage flags indexed in 4x4 units. */
    private val aboveUsesNewMotionVector: ByteArray

    /** The left-edge `NEWMV` usage flags indexed in 4x4 units. */
    private val leftUsesNewMotionVector: ByteArray

    /** The above-edge horizontal switchable interpolation-filter symbols indexed in 4x4 units. */
    private val aboveInterpolationFilterHorizontal: ByteArray

    /** The left-edge horizontal switchable interpolation-filter symbols indexed in 4x4 units. */
    private val leftInterpolationFilterHorizontal: ByteArray

    /** The above-edge vertical switchable interpolation-filter symbols indexed in 4x4 units. */
    private val aboveInterpolationFilterVertical: ByteArray

    /** The left-edge vertical switchable interpolation-filter symbols indexed in 4x4 units. */
    private val leftInterpolationFilterVertical: ByteArray

    /** The above-edge segmentation-prediction flags indexed in 4x4 units. */
    private val aboveSegmentPredicted: ByteArray

    /** The left-edge segmentation-prediction flags indexed in 4x4 units. */
    private val leftSegmentPredicted: ByteArray

    /** The above-edge luma palette sizes indexed in 4x4 units. */
    private val abovePaletteSize: ByteArray

    /** The left-edge luma palette sizes indexed in 4x4 units. */
    private val leftPaletteSize: ByteArray

    /** The above-edge chroma palette sizes indexed in 4x4 units. */
    private val aboveChromaPaletteSize: ByteArray

    /** The left-edge chroma palette sizes indexed in 4x4 units. */
    private val leftChromaPaletteSize: ByteArray

    /** The above-edge palette entries indexed as plane/x4/palette index. */
    private val abovePaletteEntries: Array<Array<IntArray>>

    /** The left-edge palette entries indexed as plane/y4/palette index. */
    private val leftPaletteEntries: Array<Array<IntArray>>

    /** The above-edge luma modes indexed in 4x4 units. */
    private val aboveMode: Array<LumaIntraPredictionMode>

    /** The left-edge luma modes indexed in 4x4 units. */
    private val leftMode: Array<LumaIntraPredictionMode>

    /** The above-edge partition context state indexed in 8x8 units. */
    private val abovePartition: ByteArray

    /** The left-edge partition context state indexed in 8x8 units. */
    private val leftPartition: ByteArray

    /** The above-edge transform-context widths indexed in 4x4 units. */
    private val aboveTransformWidthLog2: ByteArray

    /** The left-edge transform-context heights indexed in 4x4 units. */
    private val leftTransformHeightLog2: ByteArray

    /** The above-edge inter var-tx widths indexed in 4x4 units. */
    private val aboveInterTransformWidthLog2: ByteArray

    /** The left-edge inter var-tx heights indexed in 4x4 units. */
    private val leftInterTransformHeightLog2: ByteArray

    /** The above-edge luma coefficient-context bytes indexed in 4x4 units. */
    private val aboveLumaCoefficientContext: ByteArray

    /** The left-edge luma coefficient-context bytes indexed in 4x4 units. */
    private val leftLumaCoefficientContext: ByteArray

    /** The above-edge chroma coefficient-context bytes indexed as plane/chroma-4x4 X. */
    private val aboveChromaCoefficientContext: Array<ByteArray>

    /** The left-edge chroma coefficient-context bytes indexed as plane/chroma-4x4 Y. */
    private val leftChromaCoefficientContext: Array<ByteArray>

    init {
        this.decodedTemporalMotionField = requireNotNullCompat(
            decodedTemporalMotionField,
            "decodedTemporalMotionField"
        )
        this.referenceMotionVectorProjection =
            requireNotNullCompat(
                referenceMotionVectorProjection,
                "referenceMotionVectorProjection"
            )
        this.currentSegmentIdMap =
            requireNotNullCompat(currentSegmentIdMap, "currentSegmentIdMap")
        this.storedBlocks = requireNotNullCompat(storedBlocks, "storedBlocks")
        this.aboveIntra = requireNotNullCompat(aboveIntra, "aboveIntra")
        this.leftIntra = requireNotNullCompat(leftIntra, "leftIntra")
        this.aboveSkip = requireNotNullCompat(aboveSkip, "aboveSkip")
        this.leftSkip = requireNotNullCompat(leftSkip, "leftSkip")
        this.aboveSkipMode = requireNotNullCompat(aboveSkipMode, "aboveSkipMode")
        this.leftSkipMode = requireNotNullCompat(leftSkipMode, "leftSkipMode")
        this.aboveCompoundReference =
            requireNotNullCompat(aboveCompoundReference, "aboveCompoundReference")
        this.leftCompoundReference =
            requireNotNullCompat(leftCompoundReference, "leftCompoundReference")
        this.aboveCompoundPredictionType = requireNotNullCompat(
            aboveCompoundPredictionType,
            "aboveCompoundPredictionType"
        )
        this.leftCompoundPredictionType = requireNotNullCompat(
            leftCompoundPredictionType,
            "leftCompoundPredictionType"
        )
        this.aboveReferenceFrame0 =
            requireNotNullCompat(aboveReferenceFrame0, "aboveReferenceFrame0")
        this.leftReferenceFrame0 =
            requireNotNullCompat(leftReferenceFrame0, "leftReferenceFrame0")
        this.aboveReferenceFrame1 =
            requireNotNullCompat(aboveReferenceFrame1, "aboveReferenceFrame1")
        this.leftReferenceFrame1 =
            requireNotNullCompat(leftReferenceFrame1, "leftReferenceFrame1")
        this.aboveMotionVector0 = requireNotNullCompat(
            aboveMotionVector0,
            "aboveMotionVector0"
        )
        this.leftMotionVector0 =
            requireNotNullCompat(leftMotionVector0, "leftMotionVector0")
        this.aboveMotionVector1 = requireNotNullCompat(
            aboveMotionVector1,
            "aboveMotionVector1"
        )
        this.leftMotionVector1 =
            requireNotNullCompat(leftMotionVector1, "leftMotionVector1")
        this.aboveUsesNewMotionVector =
            requireNotNullCompat(aboveUsesNewMotionVector, "aboveUsesNewMotionVector")
        this.leftUsesNewMotionVector =
            requireNotNullCompat(leftUsesNewMotionVector, "leftUsesNewMotionVector")
        this.aboveInterpolationFilterHorizontal = requireNotNullCompat(
            aboveInterpolationFilterHorizontal,
            "aboveInterpolationFilterHorizontal"
        )
        this.leftInterpolationFilterHorizontal = requireNotNullCompat(
            leftInterpolationFilterHorizontal,
            "leftInterpolationFilterHorizontal"
        )
        this.aboveInterpolationFilterVertical = requireNotNullCompat(
            aboveInterpolationFilterVertical,
            "aboveInterpolationFilterVertical"
        )
        this.leftInterpolationFilterVertical = requireNotNullCompat(
            leftInterpolationFilterVertical,
            "leftInterpolationFilterVertical"
        )
        this.aboveSegmentPredicted =
            requireNotNullCompat(aboveSegmentPredicted, "aboveSegmentPredicted")
        this.leftSegmentPredicted =
            requireNotNullCompat(leftSegmentPredicted, "leftSegmentPredicted")
        this.abovePaletteSize =
            requireNotNullCompat(abovePaletteSize, "abovePaletteSize")
        this.leftPaletteSize = requireNotNullCompat(leftPaletteSize, "leftPaletteSize")
        this.aboveChromaPaletteSize =
            requireNotNullCompat(aboveChromaPaletteSize, "aboveChromaPaletteSize")
        this.leftChromaPaletteSize =
            requireNotNullCompat(leftChromaPaletteSize, "leftChromaPaletteSize")
        this.abovePaletteEntries = requireNotNullCompat(
            abovePaletteEntries,
            "abovePaletteEntries"
        )
        this.leftPaletteEntries = requireNotNullCompat(
            leftPaletteEntries,
            "leftPaletteEntries"
        )
        this.aboveMode =
            requireNotNullCompat(aboveMode, "aboveMode")
        this.leftMode = requireNotNullCompat(leftMode, "leftMode")
        this.abovePartition = requireNotNullCompat(abovePartition, "abovePartition")
        this.leftPartition = requireNotNullCompat(leftPartition, "leftPartition")
        this.aboveTransformWidthLog2 =
            requireNotNullCompat(aboveTransformWidthLog2, "aboveTransformWidthLog2")
        this.leftTransformHeightLog2 =
            requireNotNullCompat(leftTransformHeightLog2, "leftTransformHeightLog2")
        this.aboveInterTransformWidthLog2 = requireNotNullCompat(
            aboveInterTransformWidthLog2,
            "aboveInterTransformWidthLog2"
        )
        this.leftInterTransformHeightLog2 = requireNotNullCompat(
            leftInterTransformHeightLog2,
            "leftInterTransformHeightLog2"
        )
        this.aboveLumaCoefficientContext = requireNotNullCompat(
            aboveLumaCoefficientContext,
            "aboveLumaCoefficientContext"
        )
        this.leftLumaCoefficientContext = requireNotNullCompat(
            leftLumaCoefficientContext,
            "leftLumaCoefficientContext"
        )
        this.aboveChromaCoefficientContext = requireNotNullCompat(
            aboveChromaCoefficientContext,
            "aboveChromaCoefficientContext"
        )
        this.leftChromaCoefficientContext = requireNotNullCompat(
            leftChromaCoefficientContext,
            "leftChromaCoefficientContext"
        )
    }

    /** Returns the tile width rounded up to 4x4 units.
     * 
     * @return the tile width rounded up to 4x4 units
     */
    fun tileWidth4(): Int {
        return tileWidth4
    }

    /** Returns the tile height rounded up to 4x4 units.
     * 
     * @return the tile height rounded up to 4x4 units
     */
    fun tileHeight4(): Int {
        return tileHeight4
    }

    /** Returns whether the supplied block position has a top neighbor inside the tile.
     * 
     * @param position the current block position
     * @return whether the supplied block position has a top neighbor inside the tile
     */
    fun hasTopNeighbor(position: BlockPosition): Boolean {
        return requireNotNullCompat(position, "position").y4 > 0
    }

    /** Returns whether the supplied block position has a left neighbor inside the tile.
     * 
     * @param position the current block position
     * @return whether the supplied block position has a left neighbor inside the tile
     */
    fun hasLeftNeighbor(position: BlockPosition): Boolean {
        return requireNotNullCompat(position, "position").x4 > 0
    }

    /** Returns the intra/inter context for the supplied block position.
     * 
     * @param position the current block position
     * @return the intra/inter context for the supplied block position
     */
    fun intraContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val haveTop = hasTopNeighbor(nonNullPosition)
        val haveLeft = hasLeftNeighbor(nonNullPosition)
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        if (haveLeft) {
            if (haveTop) {
                val context = leftIntra[y4] + aboveIntra[x4]
                return context + (if (context == 2) 1 else 0)
            }
            return leftIntra[y4] * 2
        }
        return if (haveTop) aboveIntra[x4] * 2 else 0
    }

    /** Returns the skip context for the supplied block position.
     * 
     * @param position the current block position
     * @return the skip context for the supplied block position
     */
    fun skipContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        var context = 0
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSkip[nonNullPosition.x4].toInt()
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSkip[nonNullPosition.y4].toInt()
        }
        return context
    }

    /** Returns the skip-mode context for the supplied block position.
     * 
     * @param position the current block position
     * @return the skip-mode context for the supplied block position
     */
    fun skipModeContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        var context = 0
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSkipMode[nonNullPosition.x4].toInt()
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSkipMode[nonNullPosition.y4].toInt()
        }
        return context
    }

    /** Returns the compound-reference context for the supplied block position.
     * 
     * @param position the current block position
     * @return the compound-reference context for the supplied block position
     */
    fun compoundReferenceContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val haveTop = hasTopNeighbor(nonNullPosition)
        val haveLeft = hasLeftNeighbor(nonNullPosition)
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        if (haveTop) {
            if (haveLeft) {
                if (aboveCompoundReference[x4].toInt() != 0) {
                    if (leftCompoundReference[y4].toInt() != 0) {
                        return 4
                    }
                    return 2 + (if ((leftReferenceFrame0[y4].toInt() and 0xFF) >= 4) 1 else 0)
                }
                if (leftCompoundReference[y4].toInt() != 0) {
                    return 2 + (if ((aboveReferenceFrame0[x4].toInt() and 0xFF) >= 4) 1 else 0)
                }
                return ((if (leftReferenceFrame0[y4] >= 4) 1 else 0)
                        xor (if (aboveReferenceFrame0[x4] >= 4) 1 else 0))
            }
            return if (aboveCompoundReference[x4].toInt() != 0) 3 else (if (aboveReferenceFrame0[x4] >= 4) 1 else 0)
        }
        if (haveLeft) {
            return if (leftCompoundReference[y4].toInt() != 0) 3 else (if (leftReferenceFrame0[y4] >= 4) 1 else 0)
        }
        return 1
    }

    /** Returns the compound-direction context for the supplied block position.
     * 
     * @param position the current block position
     * @return the compound-direction context for the supplied block position
     */
    fun compoundDirectionContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val haveTop = hasTopNeighbor(nonNullPosition)
        val haveLeft = hasLeftNeighbor(nonNullPosition)
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        if (haveTop && haveLeft) {
            val aboveIntra = this.aboveIntra[x4].toInt()
            val leftIntra = this.leftIntra[y4].toInt()
            if (aboveIntra != 0 && leftIntra != 0) {
                return 2
            }
            if (aboveIntra != 0 || leftIntra != 0) {
                val useLeft = aboveIntra != 0
                val off = if (useLeft) y4 else x4
                if ((if (useLeft) leftCompoundReference[off] else aboveCompoundReference[off]).toInt() == 0) {
                    return 2
                }
                return 1 + 2 * (if (hasUnidirectionalCompoundReference(useLeft, off)) 1 else 0)
            }

            val aboveCompound = aboveCompoundReference[x4].toInt() != 0
            val leftCompound = leftCompoundReference[y4].toInt() != 0
            val aboveRef0 = aboveReferenceFrame0[x4].toInt()
            val leftRef0 = leftReferenceFrame0[y4].toInt()
            if (!aboveCompound && !leftCompound) {
                return 1 + (if ((aboveRef0 >= 4) == (leftRef0 >= 4)) 2 else 0)
            }
            if (!aboveCompound || !leftCompound) {
                val useAbove = aboveCompound
                val off = if (useAbove) x4 else y4
                if (!hasUnidirectionalCompoundReference(!useAbove, off)) {
                    return 1
                }
                return 3 + ((if ((aboveRef0 >= 4) == (leftRef0 >= 4)) 1 else 0))
            }
            val aboveUni = hasUnidirectionalCompoundReference(false, x4)
            val leftUni = hasUnidirectionalCompoundReference(true, y4)
            if (!aboveUni && !leftUni) {
                return 0
            }
            if (!aboveUni || !leftUni) {
                return 2
            }
            return 3 + (if ((aboveRef0 == 4) == (leftRef0 == 4)) 1 else 0)
        }
        if (haveTop || haveLeft) {
            val useLeft = haveLeft
            val off = if (useLeft) nonNullPosition.y4 else nonNullPosition.x4
            if ((if (useLeft) leftIntra[off] else aboveIntra[off]).toInt() != 0) {
                return 2
            }
            if ((if (useLeft) leftCompoundReference[off] else aboveCompoundReference[off]).toInt() == 0) {
                return 2
            }
            return if (hasUnidirectionalCompoundReference(useLeft, off)) 4 else 0
        }
        return 2
    }

    /** Returns the masked-compound context for the supplied block position.
     * 
     * @param position the current block position
     * @return the masked-compound context for the supplied block position
     */
    fun maskedCompoundContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        var context = 0
        if (hasTopNeighbor(nonNullPosition)) {
            context += maskedCompoundNeighborContext(false, nonNullPosition.x4)
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += maskedCompoundNeighborContext(true, nonNullPosition.y4)
        }
        return min(context, 5)
    }

    /** Returns the joint-compound context for the supplied block and reference pair.
     * 
     * @param position the current block position
     * @param currentFrameOffset the current frame order hint
     * @param referenceFrameOffset0 the primary reference frame order hint
     * @param referenceFrameOffset1 the secondary reference frame order hint
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @return the joint-compound context for the supplied block and reference pair
     */
    fun jointCompoundContext(
        position: BlockPosition,
        currentFrameOffset: Int,
        referenceFrameOffset0: Int,
        referenceFrameOffset1: Int,
        orderHintBits: Int
    ): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val distance0: Int =
            abs(orderHintDifference(orderHintBits, referenceFrameOffset0, currentFrameOffset))
        val distance1: Int =
            abs(orderHintDifference(orderHintBits, currentFrameOffset, referenceFrameOffset1))
        var context = if (distance0 == distance1) 3 else 0
        if (hasTopNeighbor(nonNullPosition)) {
            context += jointCompoundNeighborContext(false, nonNullPosition.x4)
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += jointCompoundNeighborContext(true, nonNullPosition.y4)
        }
        return context
    }

    /** Returns the switchable interpolation-filter context for the supplied block position and direction.
     * 
     * The context matches the primary current-block reference against neighboring edge state and
     * uses the AV1 switchable-filter sentinel when no usable neighbor filter is available.
     * 
     * @param position the current block position
     * @param referenceFrame0 the primary current-block reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary current-block reference in internal LAST..ALTREF order, or `-1`
     * Direction zero selects the vertical filter and direction one selects the horizontal filter,
     * matching the AV1 syntax order.
     * 
     * @param direction the zero-based interpolation-filter syntax direction in `[0, 2)`
     * @return the switchable interpolation-filter context for the supplied block position and direction
     */
    fun interpolationFilterContext(
        position: BlockPosition,
        referenceFrame0: Int,
        referenceFrame1: Int,
        direction: Int
    ): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val checkedDirection: Int = requireInterpolationFilterDirection(direction)
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        val aboveFilter: Byte = if (hasTopNeighbor(nonNullPosition))
            matchingInterpolationFilterSymbol(
                referenceFrame0,
                aboveIntra[x4].toInt() != 0,
                aboveCompoundReference[x4].toInt() != 0,
                aboveReferenceFrame0[x4],
                aboveReferenceFrame1[x4],
                if (checkedDirection == 0) aboveInterpolationFilterVertical[x4] else aboveInterpolationFilterHorizontal[x4]
            )
        else
            INTERPOLATION_FILTER_UNSET
        val leftFilter: Byte = if (hasLeftNeighbor(nonNullPosition))
            matchingInterpolationFilterSymbol(
                referenceFrame0,
                leftIntra[y4].toInt() != 0,
                leftCompoundReference[y4].toInt() != 0,
                leftReferenceFrame0[y4],
                leftReferenceFrame1[y4],
                if (checkedDirection == 0) leftInterpolationFilterVertical[y4] else leftInterpolationFilterHorizontal[y4]
            )
        else
            INTERPOLATION_FILTER_UNSET
        val contextBase = if (referenceFrame1 >= 0) 4 else 0
        if (aboveFilter == leftFilter) {
            return contextBase + (aboveFilter.toInt() and 0xFF)
        }
        if (aboveFilter == INTERPOLATION_FILTER_UNSET) {
            return contextBase + (leftFilter.toInt() and 0xFF)
        }
        if (leftFilter == INTERPOLATION_FILTER_UNSET) {
            return contextBase + (aboveFilter.toInt() and 0xFF)
        }
        return contextBase + INTERPOLATION_FILTER_UNSET
    }

    /** Returns the single-reference primary context for the supplied block position.
     * 
     * @param position the current block position
     * @return the single-reference primary context for the supplied block position
     */
    fun singleReferenceContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val count = resetReferenceCountScratch()
        accumulateForwardBackwardCounts(count, false, nonNullPosition)
        return if (count[0] == count[1]) 1 else if (count[0] < count[1]) 0 else 2
    }

    /** Returns the forward-reference context for the supplied block position.
     * 
     * @param position the current block position
     * @return the forward-reference context for the supplied block position
     */
    fun forwardReferenceContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val count = resetReferenceCountScratch()
        accumulateReferenceCounts(count, nonNullPosition, 0, 4)
        count[0] += count[1]
        count[2] += count[3]
        return if (count[0] == count[2]) 1 else if (count[0] < count[2]) 0 else 2
    }

    /** Returns the LAST-vs-LAST2 reference context for the supplied block position.
     * 
     * @param position the current block position
     * @return the LAST-vs-LAST2 reference context for the supplied block position
     */
    fun forwardReference1Context(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val count = resetReferenceCountScratch()
        accumulateReferenceCounts(count, nonNullPosition, 0, 2)
        return if (count[0] == count[1]) 1 else if (count[0] < count[1]) 0 else 2
    }

    /** Returns the LAST3-vs-GOLDEN reference context for the supplied block position.
     * 
     * @param position the current block position
     * @return the LAST3-vs-GOLDEN reference context for the supplied block position
     */
    fun forwardReference2Context(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val count = resetReferenceCountScratch()
        accumulateReferenceCounts(count, nonNullPosition, 2, 2)
        return if (count[0] == count[1]) 1 else if (count[0] < count[1]) 0 else 2
    }

    /** Returns the backward-reference primary context for the supplied block position.
     * 
     * @param position the current block position
     * @return the backward-reference primary context for the supplied block position
     */
    fun backwardReferenceContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val count = resetReferenceCountScratch()
        accumulateReferenceCounts(count, nonNullPosition, 4, 3)
        count[1] += count[0]
        return if (count[2] == count[1]) 1 else if (count[1] < count[2]) 0 else 2
    }

    /** Returns the BWDREF-vs-ALTREF2 reference context for the supplied block position.
     * 
     * @param position the current block position
     * @return the BWDREF-vs-ALTREF2 reference context for the supplied block position
     */
    fun backwardReference1Context(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val count = resetReferenceCountScratch()
        accumulateReferenceCounts(count, nonNullPosition, 4, 3)
        return if (count[0] == count[1]) 1 else if (count[0] < count[1]) 0 else 2
    }

    /** Returns the LAST2/LAST3/GOLDEN unidirectional-reference context for the supplied block position.
     * 
     * @param position the current block position
     * @return the LAST2/LAST3/GOLDEN unidirectional-reference context for the supplied block position
     */
    fun unidirectionalReference1Context(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val count = resetReferenceCountScratch()
        accumulateReferenceCounts(count, nonNullPosition, 1, 3)
        count[1] += count[2]
        return if (count[0] == count[1]) 1 else if (count[0] < count[1]) 0 else 2
    }

    /** Returns the displacement-vector predictor for one intrabc block.
     * 
     * The scan uses the same direct above/left, top-right, top-left, and secondary spatial order
     * as the AV1 reference-motion-vector stack. The first non-zero candidate among the nearest
     * two is returned; when no usable candidate exists, the caller-supplied fallback is returned.
     * 
     * @param position the current tile-relative block position
     * @param size the current block size
     * @param fallback the frame-derived fallback displacement vector
     * @return the selected displacement-vector predictor in eighth-pel units
     */
    fun intrabcReferenceMotionVector(
        position: BlockPosition,
        size: BlockSize,
        fallback: MotionVector
    ): MotionVector {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        val nonNullFallback = requireNotNullCompat(fallback, "fallback")
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        val blockWidth4 = nonNullSize.width4()
        val blockHeight4 = nonNullSize.height4()
        val visibleWidth4 = min(min(blockWidth4, 16), tileWidth4 - x4)
        val visibleHeight4 = min(min(blockHeight4, 16), tileHeight4 - y4)
        require(!(visibleWidth4 <= 0 || visibleHeight4 <= 0)) { "Intrabc block lies outside the tile" }

        val zeroGlobalMotion = ExactSpatialGlobalMotion(
            MotionVector.Companion.zero(),
            MotionVector.Companion.zero(),
            false,
            false
        )
        val candidates = provisionalCandidateScratch
        var candidateCount = 0
        var maximumRows = 0
        var scannedRows = -1
        if (y4 > 0) {
            maximumRows = min((y4 + 1) shr 1, 2 + (if (blockHeight4 > 1) 1 else 0))
            val topScan = scanExactRefMvsRow(
                y4 - 1,
                x4,
                blockWidth4,
                visibleWidth4,
                maximumRows,
                if (blockWidth4 >= 16) 4 else 1,
                false,
                INTRABC_REFERENCE_FRAME,
                -1,
                zeroGlobalMotion,
                candidates,
                candidateCount
            )
            candidateCount = topScan.candidateCount
            scannedRows = topScan.scanDistance
        }

        var maximumColumns = 0
        var scannedColumns = -1
        if (x4 > 0) {
            maximumColumns = min((x4 + 1) shr 1, 2 + (if (blockWidth4 > 1) 1 else 0))
            val leftScan = scanExactRefMvsColumn(
                x4 - 1,
                y4,
                blockHeight4,
                visibleHeight4,
                maximumColumns,
                if (blockHeight4 >= 16) 4 else 1,
                false,
                INTRABC_REFERENCE_FRAME,
                -1,
                zeroGlobalMotion,
                candidates,
                candidateCount
            )
            candidateCount = leftScan.candidateCount
            scannedColumns = leftScan.scanDistance
        }

        if (scannedRows >= 0 && max(
                blockWidth4,
                blockHeight4
            ) <= 16 && x4 + blockWidth4 < tileWidth4
        ) {
            val topRightResult: Long = addExactSpatialCandidate(
                storedBlockAtOrNull(x4 + blockWidth4, y4 - 1),
                false,
                INTRABC_REFERENCE_FRAME,
                -1,
                zeroGlobalMotion,
                4,
                candidates,
                candidateCount
            )
            candidateCount = spatialCandidateCount(topRightResult)
        }

        val nearestCandidateCount = candidateCount
        for (index in 0..<nearestCandidateCount) {
            candidates[index].increaseWeight(640)
        }

        if (scannedRows >= 0 && scannedColumns >= 0) {
            val topLeftResult: Long = addExactSpatialCandidate(
                storedBlockAtOrNull(x4 - 1, y4 - 1),
                false,
                INTRABC_REFERENCE_FRAME,
                -1,
                zeroGlobalMotion,
                4,
                candidates,
                candidateCount
            )
            candidateCount = spatialCandidateCount(topLeftResult)
        }

        for (spatialDepth in 2..3) {
            if (spatialDepth > scannedRows && spatialDepth <= maximumRows) {
                val secondaryTop = scanExactRefMvsRow(
                    secondarySpatialOffsetCoordinate(y4, spatialDepth),
                    x4 or 1,
                    blockWidth4,
                    visibleWidth4,
                    1 + maximumRows - spatialDepth,
                    if (blockWidth4 >= 16) 4 else 2,
                    false,
                    INTRABC_REFERENCE_FRAME,
                    -1,
                    zeroGlobalMotion,
                    candidates,
                    candidateCount
                )
                candidateCount = secondaryTop.candidateCount
                scannedRows += secondaryTop.scanDistance
            }
            if (spatialDepth > scannedColumns && spatialDepth <= maximumColumns) {
                val secondaryLeft = scanExactRefMvsColumn(
                    secondarySpatialOffsetCoordinate(x4, spatialDepth),
                    y4 or 1,
                    blockHeight4,
                    visibleHeight4,
                    1 + maximumColumns - spatialDepth,
                    if (blockHeight4 >= 16) 4 else 2,
                    false,
                    INTRABC_REFERENCE_FRAME,
                    -1,
                    zeroGlobalMotion,
                    candidates,
                    candidateCount
                )
                candidateCount = secondaryLeft.candidateCount
                scannedColumns += secondaryLeft.scanDistance
            }
        }

        sortDescending(candidates, 0, nearestCandidateCount)
        sortDescending(candidates, nearestCandidateCount, candidateCount)
        val frameX4 = (tileStartX8 shl 1) + x4
        val frameY4 = (tileStartY8 shl 1) + y4
        val minimumColumn: Int = (-(frameX4 + blockWidth4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER)
        val maximumColumn: Int = ((frameWidth4 - frameX4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER)
        val minimumRow: Int = (-(frameY4 + blockHeight4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER)
        val maximumRow: Int = ((frameHeight4 - frameY4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER)
        for (index in 0..<min(2, candidateCount)) {
            val candidate: MotionVector = clampReferenceMotionVector(
                candidates[index].motionVector0(),
                minimumRow,
                maximumRow,
                minimumColumn,
                maximumColumn
            ).vector
            if (candidate.rowEighthPel != 0 || candidate.columnEighthPel != 0) {
                return candidate
            }
        }
        return nonNullFallback
    }

    /** Builds an inter-mode syntax context from spatial and projected temporal neighbors.
     * 
     * Non-translation global-motion types affect exact spatial candidates: an eligible
     * neighboring `GLOBALMV` block contributes the current block's global vector, because an
     * affine or rotation/zoom vector varies with block position. Translation-only callers may
     * use the shorter overload.
     * 
     * @param position the current block position
     * @param size the current block size
     * @param compoundReference whether the current block uses compound references
     * @param referenceFrame0 the primary current-block reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary current-block reference in internal LAST..ALTREF order, or `-1`
     * @param globalMotionVector0 the current block's primary global-motion vector
     * @param globalMotionVector1 the current block's secondary global-motion vector, or zero for single-reference blocks
     * @param globalMotionType0 the primary reference's global-motion type
     * @param globalMotionType1 the secondary reference's global-motion type, or identity for single-reference blocks
     * @return an inter-mode syntax context derived from available neighbors
     */
    fun provisionalInterModeContext(
        position: BlockPosition,
        size: BlockSize,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        globalMotionVector0: MotionVector,
        globalMotionVector1: MotionVector,
        globalMotionType0: GlobalMotionType,
        globalMotionType1: GlobalMotionType
    ): ProvisionalInterModeContext {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        val nonNullGlobalMotionVector0 =
            requireNotNullCompat(globalMotionVector0, "globalMotionVector0")
        val nonNullGlobalMotionVector1 =
            requireNotNullCompat(globalMotionVector1, "globalMotionVector1")
        val nonNullGlobalMotionType0 =
            requireNotNullCompat(globalMotionType0, "globalMotionType0")
        val nonNullGlobalMotionType1 =
            requireNotNullCompat(globalMotionType1, "globalMotionType1")
        require(referenceFrame0 >= 0) { "referenceFrame0 < 0" }
        require(!(!compoundReference && referenceFrame1 >= 0)) { "Single-reference blocks must not carry referenceFrame1" }
        require(!(compoundReference && referenceFrame1 < 0)) { "Compound-reference blocks must carry referenceFrame1" }

        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        val blockWidth4 = nonNullSize.width4()
        val blockHeight4 = nonNullSize.height4()
        val visibleWidth4 = min(min(blockWidth4, 16), tileWidth4 - x4)
        val visibleHeight4 = min(min(blockHeight4, 16), tileHeight4 - y4)
        var directRowMatch = false
        var directColumnMatch = false
        var rowReferenceMatch = false
        var columnReferenceMatch = false
        var haveNewMotionVectorMatch = false
        var globalMotionContext = if (referenceMotionVectorProjection.enabled()) 1 else 0
        val exactSpatialGlobalMotion = ExactSpatialGlobalMotion(
            nonNullGlobalMotionVector0,
            nonNullGlobalMotionVector1,
            nonNullGlobalMotionType0.ordinal > GlobalMotionType.TRANSLATION.ordinal,
            nonNullGlobalMotionType1.ordinal > GlobalMotionType.TRANSLATION.ordinal
        )
        val candidates = provisionalCandidateScratch
        var candidateCount = 0

        var maximumRows = 0
        var scannedRows = -1
        if (y4 > 0) {
            maximumRows = min((y4 + 1) shr 1, 2 + (if (blockHeight4 > 1) 1 else 0))
            val topScan = scanExactRefMvsRow(
                y4 - 1,
                x4,
                blockWidth4,
                visibleWidth4,
                maximumRows,
                if (blockWidth4 >= 16) 4 else 1,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                exactSpatialGlobalMotion,
                candidates,
                candidateCount
            )
            candidateCount = topScan.candidateCount
            scannedRows = topScan.scanDistance
            directRowMatch = topScan.referenceMatch
            rowReferenceMatch = topScan.referenceMatch
            haveNewMotionVectorMatch = haveNewMotionVectorMatch or topScan.haveNewMotionVectorMatch
        }

        var maximumColumns = 0
        var scannedColumns = -1
        if (x4 > 0) {
            maximumColumns = min((x4 + 1) shr 1, 2 + (if (blockWidth4 > 1) 1 else 0))
            val leftScan = scanExactRefMvsColumn(
                x4 - 1,
                y4,
                blockHeight4,
                visibleHeight4,
                maximumColumns,
                if (blockHeight4 >= 16) 4 else 1,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                exactSpatialGlobalMotion,
                candidates,
                candidateCount
            )
            candidateCount = leftScan.candidateCount
            scannedColumns = leftScan.scanDistance
            directColumnMatch = leftScan.referenceMatch
            columnReferenceMatch = leftScan.referenceMatch
            haveNewMotionVectorMatch = haveNewMotionVectorMatch or leftScan.haveNewMotionVectorMatch
        }

        if (scannedRows >= 0 && max(
                blockWidth4,
                blockHeight4
            ) <= 16 && x4 + blockWidth4 < tileWidth4
        ) {
            val topRight: Long = addExactSpatialCandidate(
                storedBlockAtOrNull(x4 + blockWidth4, y4 - 1),
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                exactSpatialGlobalMotion,
                4,
                candidates,
                candidateCount
            )
            candidateCount = spatialCandidateCount(topRight)
            directRowMatch = directRowMatch or spatialReferenceMatch(topRight)
            rowReferenceMatch = rowReferenceMatch or spatialReferenceMatch(topRight)
            haveNewMotionVectorMatch =
                haveNewMotionVectorMatch or spatialNewMotionVectorMatch(topRight)
        }

        val nearestCandidateCount = candidateCount
        for (i in 0..<nearestCandidateCount) {
            candidates[i].increaseWeight(640)
        }

        val temporalScan = scanTemporalMotionField(
            nonNullPosition,
            nonNullSize,
            compoundReference,
            referenceFrame0,
            referenceFrame1,
            nonNullGlobalMotionVector0,
            candidates,
            candidateCount,
            globalMotionContext
        )
        candidateCount = temporalScan.candidateCount
        globalMotionContext = temporalScan.globalMotionContext

        if (scannedRows >= 0 && scannedColumns >= 0) {
            val topLeft: Long = addExactSpatialCandidate(
                storedBlockAtOrNull(x4 - 1, y4 - 1),
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                exactSpatialGlobalMotion,
                4,
                candidates,
                candidateCount
            )
            candidateCount = spatialCandidateCount(topLeft)
            rowReferenceMatch = rowReferenceMatch or spatialReferenceMatch(topLeft)
        }

        for (spatialDepth in 2..3) {
            if (spatialDepth > scannedRows && spatialDepth <= maximumRows) {
                val secondaryTop = scanExactRefMvsRow(
                    secondarySpatialOffsetCoordinate(y4, spatialDepth),
                    x4 or 1,
                    blockWidth4,
                    visibleWidth4,
                    1 + maximumRows - spatialDepth,
                    if (blockWidth4 >= 16) 4 else 2,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    exactSpatialGlobalMotion,
                    candidates,
                    candidateCount
                )
                candidateCount = secondaryTop.candidateCount
                scannedRows += secondaryTop.scanDistance
                rowReferenceMatch = rowReferenceMatch or secondaryTop.referenceMatch
            }
            if (spatialDepth > scannedColumns && spatialDepth <= maximumColumns) {
                val secondaryLeft = scanExactRefMvsColumn(
                    secondarySpatialOffsetCoordinate(x4, spatialDepth),
                    y4 or 1,
                    blockHeight4,
                    visibleHeight4,
                    1 + maximumColumns - spatialDepth,
                    if (blockHeight4 >= 16) 4 else 2,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    exactSpatialGlobalMotion,
                    candidates,
                    candidateCount
                )
                candidateCount = secondaryLeft.candidateCount
                scannedColumns += secondaryLeft.scanDistance
                columnReferenceMatch = columnReferenceMatch or secondaryLeft.referenceMatch
            }
        }

        sortDescending(candidates, 0, nearestCandidateCount)
        sortDescending(candidates, nearestCandidateCount, candidateCount)
        if (compoundReference && candidateCount < 2) {
            candidateCount = appendCompoundExtendedSpatialCandidates(
                x4,
                y4,
                min(visibleWidth4, visibleHeight4),
                scannedRows >= 0,
                scannedColumns >= 0,
                referenceFrame0,
                referenceFrame1,
                nonNullGlobalMotionVector0,
                nonNullGlobalMotionVector1,
                candidates,
                candidateCount
            )
        } else if (candidateCount < 2) {
            candidateCount = appendSingleExtendedSpatialCandidates(
                x4,
                y4,
                min(visibleWidth4, visibleHeight4),
                scannedRows >= 0,
                scannedColumns >= 0,
                referenceFrame0,
                candidates,
                candidateCount
            )
        }
        var syntaxCandidateCount = candidateCount
        val frameX4 = (tileStartX8 shl 1) + x4
        val frameY4 = (tileStartY8 shl 1) + y4
        val minimumColumn: Int = (-(frameX4 + blockWidth4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER)
        val maximumColumn: Int = ((frameWidth4 - frameX4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER)
        val minimumRow: Int = (-(frameY4 + blockHeight4) * MOTION_VECTOR_UNITS_PER_4X4
                - REFERENCE_MOTION_VECTOR_BORDER)
        val maximumRow: Int = ((frameHeight4 - frameY4) * MOTION_VECTOR_UNITS_PER_4X4
                + REFERENCE_MOTION_VECTOR_BORDER)
        for (index in 0..<candidateCount) {
            val candidate = candidates[index]
            val secondaryMotionVector = candidate.motionVector1()
            val clampedPrimary: InterMotionVector = clampReferenceMotionVector(
                candidate.motionVector0(),
                minimumRow,
                maximumRow,
                minimumColumn,
                maximumColumn
            )
            val clampedSecondary: InterMotionVector? = if (secondaryMotionVector == null)
                null
            else
                clampReferenceMotionVector(
                    secondaryMotionVector,
                    minimumRow,
                    maximumRow,
                    minimumColumn,
                    maximumColumn
                )
            if (clampedPrimary !== candidate.motionVector0() || clampedSecondary !== secondaryMotionVector) {
                candidates[index] = ProvisionalMotionVectorCandidate(
                    candidate.weight(),
                    clampedPrimary,
                    clampedSecondary,
                    candidate.synthetic()
                )
            }
        }
        while (candidateCount < 2) {
            candidates[candidateCount++] = ProvisionalMotionVectorCandidate(
                2,
                InterMotionVector.Companion.predicted(nonNullGlobalMotionVector0),
                if (compoundReference) InterMotionVector.Companion.predicted(
                    nonNullGlobalMotionVector1
                ) else null,
                true
            )
        }
        if (compoundReference) {
            syntaxCandidateCount = candidateCount
        }
        val refMvsContextSummary: RefMvsContextSummary = summarizeDirectRefMvsContexts(
            (if (directRowMatch) 1 else 0) + (if (directColumnMatch) 1 else 0),
            (if (rowReferenceMatch) 1 else 0) + (if (columnReferenceMatch) 1 else 0),
            haveNewMotionVectorMatch
        )

        return ProvisionalInterModeContext.Companion.fromOwnedCandidates(
            refMvsContextSummary.singleNewMvContext,
            globalMotionContext,
            refMvsContextSummary.singleReferenceMvContext,
            refMvsContextSummary.compoundInterModeContext,
            syntaxCandidateCount,
            copyOfCompat(candidates, candidateCount)
        )
    }

    /** Completes a short compound-reference candidate stack from direct-edge neighbors.
     * 
     * Each requested reference first reuses matching neighbor components, then sign-normalized
     * components associated with other references, and finally its block-center global motion.
     * The two independently completed component lists are paired by index, matching AV1's
     * compound extended-candidate construction.
     * 
     * @param x4 the current block origin X coordinate in tile-relative 4x4 units
     * @param y4 the current block origin Y coordinate in tile-relative 4x4 units
     * @param span4 the bounded direct-edge span in 4x4 units
     * @param hasTop whether a direct top edge is available
     * @param hasLeft whether a direct left edge is available
     * @param referenceFrame0 the requested primary reference
     * @param referenceFrame1 the requested secondary reference
     * @param globalMotionVector0 the primary global-motion fallback
     * @param globalMotionVector1 the secondary global-motion fallback
     * @param destination the destination candidate stack
     * @param count the number of active candidates already stored
     * @return the completed candidate count, which is always two
     */
    private fun appendCompoundExtendedSpatialCandidates(
        x4: Int,
        y4: Int,
        span4: Int,
        hasTop: Boolean,
        hasLeft: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        globalMotionVector0: MotionVector,
        globalMotionVector1: MotionVector,
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int
    ): Int {
        val matching0: MutableList<MotionVector> = ArrayList<MotionVector>(2)
        val matching1: MutableList<MotionVector> = ArrayList<MotionVector>(2)
        val alternate0: MutableList<MotionVector> = ArrayList<MotionVector>(2)
        val alternate1: MutableList<MotionVector> = ArrayList<MotionVector>(2)

        if (hasTop) {
            var offset4 = 0
            while (offset4 < span4) {
                val block = storedBlockAtOrNull(x4 + offset4, y4 - 1)
                collectCompoundExtendedComponents(
                    block,
                    referenceFrame0,
                    referenceFrame1,
                    matching0,
                    matching1,
                    alternate0,
                    alternate1
                )
                offset4 += if (block != null) max(1, block.width4()) else 1
            }
        }
        if (hasLeft) {
            var offset4 = 0
            while (offset4 < span4) {
                val block = storedBlockAtOrNull(x4 - 1, y4 + offset4)
                collectCompoundExtendedComponents(
                    block,
                    referenceFrame0,
                    referenceFrame1,
                    matching0,
                    matching1,
                    alternate0,
                    alternate1
                )
                offset4 += if (block != null) max(1, block.height4()) else 1
            }
        }

        val first: ProvisionalMotionVectorCandidate = compoundExtendedCandidate(
            matching0,
            matching1,
            alternate0,
            alternate1,
            globalMotionVector0,
            globalMotionVector1,
            0
        )
        val second: ProvisionalMotionVectorCandidate = compoundExtendedCandidate(
            matching0,
            matching1,
            alternate0,
            alternate1,
            globalMotionVector0,
            globalMotionVector1,
            1
        )
        if (count == 0) {
            destination[0] = first
            destination[1] = second
        } else if (equivalentMotionVectorCandidate(destination[0], first)) {
            destination[1] = second
        } else {
            destination[1] = first
        }
        return 2
    }

    /** Collects matching and sign-normalized alternate components from one direct-edge block.
     * 
     * @param block the stored direct-edge block, or `null`
     * @param referenceFrame0 the requested primary reference
     * @param referenceFrame1 the requested secondary reference
     * @param matching0 primary components already using `referenceFrame0`
     * @param matching1 secondary components already using `referenceFrame1`
     * @param alternate0 components reusable for the primary reference after sign normalization
     * @param alternate1 components reusable for the secondary reference after sign normalization
     */
    private fun collectCompoundExtendedComponents(
        block: StoredBlock?,
        referenceFrame0: Int,
        referenceFrame1: Int,
        matching0: MutableList<MotionVector>,
        matching1: MutableList<MotionVector>,
        alternate0: MutableList<MotionVector>,
        alternate1: MutableList<MotionVector>
    ) {
        if (block == null || block.intra()) {
            return
        }
        collectCompoundExtendedComponent(
            block.referenceFrame0(),
            block.motionVector0().vector,
            referenceFrame0,
            referenceFrame1,
            matching0,
            matching1,
            alternate0,
            alternate1
        )
        if (block.compoundReference()) {
            collectCompoundExtendedComponent(
                block.referenceFrame1(),
                block.motionVector1().vector,
                referenceFrame0,
                referenceFrame1,
                matching0,
                matching1,
                alternate0,
                alternate1
            )
        }
    }

    /** Classifies one neighboring motion component for both requested compound references.
     * 
     * @param neighborReferenceFrame the neighbor component reference
     * @param neighborMotionVector the neighbor component vector
     * @param referenceFrame0 the requested primary reference
     * @param referenceFrame1 the requested secondary reference
     * @param matching0 primary components already using `referenceFrame0`
     * @param matching1 secondary components already using `referenceFrame1`
     * @param alternate0 components reusable for the primary reference after sign normalization
     * @param alternate1 components reusable for the secondary reference after sign normalization
     */
    private fun collectCompoundExtendedComponent(
        neighborReferenceFrame: Int,
        neighborMotionVector: MotionVector,
        referenceFrame0: Int,
        referenceFrame1: Int,
        matching0: MutableList<MotionVector>,
        matching1: MutableList<MotionVector>,
        alternate0: MutableList<MotionVector>,
        alternate1: MutableList<MotionVector>
    ) {
        if (neighborReferenceFrame < 0) {
            return
        }
        val nonNullMotionVector =
            requireNotNullCompat(neighborMotionVector, "neighborMotionVector")
        if (neighborReferenceFrame == referenceFrame0) {
            appendExtendedComponent(matching0, nonNullMotionVector)
            appendExtendedComponent(
                alternate1,
                normalizeExtendedMotionVector(
                    nonNullMotionVector,
                    neighborReferenceFrame,
                    referenceFrame1
                )
            )
        } else if (neighborReferenceFrame == referenceFrame1) {
            appendExtendedComponent(matching1, nonNullMotionVector)
            appendExtendedComponent(
                alternate0,
                normalizeExtendedMotionVector(
                    nonNullMotionVector,
                    neighborReferenceFrame,
                    referenceFrame0
                )
            )
        } else {
            appendExtendedComponent(
                alternate0,
                normalizeExtendedMotionVector(
                    nonNullMotionVector,
                    neighborReferenceFrame,
                    referenceFrame0
                )
            )
            appendExtendedComponent(
                alternate1,
                normalizeExtendedMotionVector(
                    nonNullMotionVector,
                    neighborReferenceFrame,
                    referenceFrame1
                )
            )
        }
    }

    /** Returns a neighbor component normalized to the temporal direction of one requested reference.
     * 
     * @param motionVector the neighbor component
     * @param neighborReferenceFrame the neighbor component reference
     * @param requestedReferenceFrame the requested reference
     * @return the direction-normalized component
     */
    private fun normalizeExtendedMotionVector(
        motionVector: MotionVector,
        neighborReferenceFrame: Int,
        requestedReferenceFrame: Int
    ): MotionVector {
        val nonNullMotionVector = requireNotNullCompat(motionVector, "motionVector")
        if (referenceMotionVectorProjection.signBias(neighborReferenceFrame)
            == referenceMotionVectorProjection.signBias(requestedReferenceFrame)
        ) {
            return nonNullMotionVector
        }
        return MotionVector(
            -nonNullMotionVector.rowEighthPel,
            -nonNullMotionVector.columnEighthPel
        )
    }

    /** Appends single-reference extended candidates from the direct top and left edges.
     * 
     * This fallback scan reuses motion vectors carried by neighbors with any inter reference.
     * Vectors whose reference lies on the opposite temporal side of the current frame are
     * negated. Unlike the final global-motion fallback, appended entries are real DRL-visible
     * candidates.
     * 
     * @param x4 the current block origin X coordinate in tile-relative 4x4 units
     * @param y4 the current block origin Y coordinate in tile-relative 4x4 units
     * @param span4 the bounded direct-edge span in 4x4 units
     * @param hasTop whether a direct top edge is available
     * @param hasLeft whether a direct left edge is available
     * @param referenceFrame the current block reference in internal LAST..ALTREF order
     * @param destination the destination candidate stack
     * @param count the number of active candidates already stored
     * @return the updated real candidate count
     */
    private fun appendSingleExtendedSpatialCandidates(
        x4: Int,
        y4: Int,
        span4: Int,
        hasTop: Boolean,
        hasLeft: Boolean,
        referenceFrame: Int,
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int
    ): Int {
        var count = count
        if (hasTop) {
            var offset4 = 0
            while (offset4 < span4 && count < 2) {
                val block = storedBlockAtOrNull(x4 + offset4, y4 - 1)
                count = appendSingleExtendedCandidate(block, referenceFrame, destination, count)
                offset4 += if (block != null) max(1, block.width4()) else 1
            }
        }
        if (hasLeft) {
            var offset4 = 0
            while (offset4 < span4 && count < 2) {
                val block = storedBlockAtOrNull(x4 - 1, y4 + offset4)
                count = appendSingleExtendedCandidate(block, referenceFrame, destination, count)
                offset4 += if (block != null) max(1, block.height4()) else 1
            }
        }
        return count
    }

    /** Appends the unique motion components carried by one extended spatial neighbor.
     * 
     * @param block the stored inter neighbor, or `null`
     * @param referenceFrame the current block reference in internal LAST..ALTREF order
     * @param destination the destination candidate stack
     * @param count the number of active candidates already stored
     * @return the updated real candidate count
     */
    private fun appendSingleExtendedCandidate(
        block: StoredBlock?,
        referenceFrame: Int,
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int
    ): Int {
        var count = count
        if (block == null || block.intra()) {
            return count
        }
        count = appendSingleExtendedComponent(
            block.referenceFrame0(),
            block.motionVector0(),
            referenceFrame,
            destination,
            count
        )
        if (block.compoundReference()) {
            count = appendSingleExtendedComponent(
                block.referenceFrame1(),
                block.motionVector1(),
                referenceFrame,
                destination,
                count
            )
        }
        return count
    }

    /** Appends one sign-normalized extended motion-vector component when it is unique.
     * 
     * @param neighborReferenceFrame the neighbor component reference in internal LAST..ALTREF order
     * @param neighborMotionVector the neighbor component motion vector
     * @param referenceFrame the current block reference in internal LAST..ALTREF order
     * @param destination the destination candidate stack
     * @param count the number of active candidates already stored
     * @return the updated real candidate count
     */
    private fun appendSingleExtendedComponent(
        neighborReferenceFrame: Int,
        neighborMotionVector: InterMotionVector,
        referenceFrame: Int,
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int
    ): Int {
        if (neighborReferenceFrame < 0 || count >= destination.size) {
            return count
        }
        var vector = requireNotNullCompat(
            neighborMotionVector,
            "neighborMotionVector"
        ).vector
        if (referenceMotionVectorProjection.signBias(referenceFrame)
            != referenceMotionVectorProjection.signBias(neighborReferenceFrame)
        ) {
            vector = MotionVector(-vector.rowEighthPel, -vector.columnEighthPel)
        }
        for (index in 0..<count) {
            if (destination[index].motionVector0().vector == vector) {
                return count
            }
        }
        val candidate =
            ProvisionalMotionVectorCandidate(
                2,
                InterMotionVector.Companion.predicted(vector),
                null,
                false
            )
        destination[count] = candidate
        return count + 1
    }

    /** Returns the temporal segmentation-prediction context for the supplied block position.
     * 
     * @param position the current block position
     * @return the temporal segmentation-prediction context for the supplied block position
     */
    fun segmentPredictionContext(position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        var context = 0
        if (hasTopNeighbor(nonNullPosition)) {
            context += aboveSegmentPredicted[nonNullPosition.x4].toInt()
        }
        if (hasLeftNeighbor(nonNullPosition)) {
            context += leftSegmentPredicted[nonNullPosition.y4].toInt()
        }
        return context
    }

    /** Returns the current-frame predicted segment identifier and context for the supplied block position.
     * 
     * @param position the current block position
     * @return the current-frame predicted segment identifier and context
     */
    fun currentSegmentPrediction(position: BlockPosition): SegmentPrediction {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val haveTop = hasTopNeighbor(nonNullPosition)
        val haveLeft = hasLeftNeighbor(nonNullPosition)
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        val frameX4 = (tileStartX8 shl 1) + x4
        val frameY4 = (tileStartY8 shl 1) + y4
        if (haveLeft && haveTop) {
            val left = currentSegmentIdMap.getOrZero(frameX4 - 1, frameY4)
            val above = currentSegmentIdMap.getOrZero(frameX4, frameY4 - 1)
            val aboveLeft = currentSegmentIdMap.getOrZero(frameX4 - 1, frameY4 - 1)
            val context: Int
            if (left == above && aboveLeft == left) {
                context = 2
            } else if (left == above || aboveLeft == left || above == aboveLeft) {
                context = 1
            } else {
                context = 0
            }
            return SegmentPrediction(if (above == aboveLeft) above else left, context)
        }

        val predictedSegmentId = if (haveLeft)
            currentSegmentIdMap.getOrZero(frameX4 - 1, frameY4)
        else
            if (haveTop)
                currentSegmentIdMap.getOrZero(frameX4, frameY4 - 1)
            else
                0
        return SegmentPrediction(predictedSegmentId, 0)
    }

    /** Returns the above-edge luma palette size for the supplied X coordinate in 4x4 units.
     * 
     * @param x4 the X coordinate in 4x4 units
     * @return the above-edge luma palette size for the supplied X coordinate in 4x4 units
     */
    fun abovePaletteSize(x4: Int): Int {
        return abovePaletteSize[x4].toInt() and 0xFF
    }

    /** Returns the left-edge luma palette size for the supplied Y coordinate in 4x4 units.
     * 
     * @param y4 the Y coordinate in 4x4 units
     * @return the left-edge luma palette size for the supplied Y coordinate in 4x4 units
     */
    fun leftPaletteSize(y4: Int): Int {
        return leftPaletteSize[y4].toInt() and 0xFF
    }

    /** Returns the above-edge chroma palette size for the supplied X coordinate in 4x4 units.
     * 
     * @param x4 the X coordinate in 4x4 units
     * @return the above-edge chroma palette size for the supplied X coordinate in 4x4 units
     */
    fun aboveChromaPaletteSize(x4: Int): Int {
        return aboveChromaPaletteSize[x4].toInt() and 0xFF
    }

    /** Returns the left-edge chroma palette size for the supplied Y coordinate in 4x4 units.
     * 
     * @param y4 the Y coordinate in 4x4 units
     * @return the left-edge chroma palette size for the supplied Y coordinate in 4x4 units
     */
    fun leftChromaPaletteSize(y4: Int): Int {
        return leftChromaPaletteSize[y4].toInt() and 0xFF
    }

    /** Returns one above-edge palette entry for the supplied plane, X coordinate, and palette index.
     * 
     * @param plane the plane index, where `0` is Y, `1` is U, and `2` is V
     * @param x4 the X coordinate in 4x4 units
     * @param index the zero-based palette entry index below the active palette size for `plane`
     * @return one above-edge palette entry for the supplied plane and coordinate
     */
    fun abovePaletteEntry(plane: Int, x4: Int, index: Int): Int {
        val checkedPlane = checkIndexCompat(plane, abovePaletteEntries.size)
        val activeSize = if (checkedPlane == 0) abovePaletteSize(x4) else aboveChromaPaletteSize(x4)
        return abovePaletteEntries[checkedPlane][x4][checkIndexCompat(index, activeSize)]
    }

    /** Returns one left-edge palette entry for the supplied plane, Y coordinate, and palette index.
     * 
     * @param plane the plane index, where `0` is Y, `1` is U, and `2` is V
     * @param y4 the Y coordinate in 4x4 units
     * @param index the zero-based palette entry index below the active palette size for `plane`
     * @return one left-edge palette entry for the supplied plane and coordinate
     */
    fun leftPaletteEntry(plane: Int, y4: Int, index: Int): Int {
        val checkedPlane = checkIndexCompat(plane, leftPaletteEntries.size)
        val activeSize = if (checkedPlane == 0) leftPaletteSize(y4) else leftChromaPaletteSize(y4)
        return leftPaletteEntries[checkedPlane][y4][checkIndexCompat(index, activeSize)]
    }

    /** Returns the above-edge luma mode for the supplied X coordinate in 4x4 units.
     * 
     * @param x4 the X coordinate in 4x4 units
     * @return the above-edge luma mode for the supplied X coordinate in 4x4 units
     */
    fun aboveMode(x4: Int): LumaIntraPredictionMode {
        return aboveMode[x4]
    }

    /** Returns the left-edge luma mode for the supplied Y coordinate in 4x4 units.
     * 
     * @param y4 the Y coordinate in 4x4 units
     * @return the left-edge luma mode for the supplied Y coordinate in 4x4 units
     */
    fun leftMode(y4: Int): LumaIntraPredictionMode {
        return leftMode[y4]
    }

    /** Returns the partition context for the supplied block-level shift and block position.
     * 
     * @param partitionShift the partition-bit shift derived from the current square block level
     * @param position the current block position
     * @return the partition context for the supplied block-level shift and block position
     */
    fun partitionContext(partitionShift: Int, position: BlockPosition): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val x8 = nonNullPosition.x8()
        val y8 = nonNullPosition.y8()
        return ((abovePartition[x8].toInt() shr partitionShift) and 1) + (((leftPartition[y8].toInt() shr partitionShift) and 1) shl 1)
    }

    /** Returns the transform-size context for one intra-like block and maximum luma transform size.
     * 
     * This matches the `dav1d` `get_tx_ctx()` rule that compares the stored top and left
     * transform-context dimensions against the currently allowed maximum transform size.
     * 
     * @param position the current block position
     * @param maxTransformSize the largest luma transform size allowed for the current block
     * @return the transform-size context for one intra-like block
     */
    fun transformSizeContext(position: BlockPosition, maxTransformSize: TransformSize): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullMaxTransformSize =
            requireNotNullCompat(maxTransformSize, "maxTransformSize")
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        return ((if (leftTransformHeightLog2[y4] >= nonNullMaxTransformSize.log2Height4()) 1 else 0)
                + (if (aboveTransformWidthLog2[x4] >= nonNullMaxTransformSize.log2Width4()) 1 else 0))
    }

    /** Returns the inter var-tx split context for one transform region and maximum transform size.
     * 
     * This matches `dav1d`'s `read_tx_tree()` rule that compares the current top and left inter
     * transform-context dimensions against the transform width and height being split.
     * 
     * @param position the local tile-relative origin of the current transform region
     * @param transformSize the transform size currently being considered for splitting
     * @return the inter var-tx split context in `[0, 3)`
     */
    fun interTransformSplitContext(position: BlockPosition, transformSize: TransformSize): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val x4 = nonNullPosition.x4
        val y4 = nonNullPosition.y4
        return ((if (aboveInterTransformWidthLog2[x4] < nonNullTransformSize.log2Width4()) 1 else 0)
                + (if (leftInterTransformHeightLog2[y4] < nonNullTransformSize.log2Height4()) 1 else 0))
    }

    /** Returns the luma coefficient skip-context for one transform unit.
     * 
     * This matches the luma `dav1d` `get_skip_ctx()` path by merging the already stored top and
     * left coefficient-context bytes across the current transform span and mapping the merged
     * counts through the `dav1d_skip_ctx` table.
     * 
     * @param blockSize the coded block size that owns the current transform unit
     * @param transformUnit the current luma transform unit
     * @return the luma coefficient skip-context in `[0, 7)`
     */
    fun lumaCoefficientSkipContext(blockSize: BlockSize, transformUnit: TransformUnit): Int {
        val nonNullTransformUnit =
            requireNotNullCompat(transformUnit, "transformUnit")
        return lumaCoefficientSkipContext(
            blockSize,
            nonNullTransformUnit.position.x4,
            nonNullTransformUnit.position.y4,
            nonNullTransformUnit.size
        )
    }

    /** Returns the luma coefficient skip-context for one tile-local transform span.
     * 
     * @param blockSize the coded block size that owns the current transform unit
     * @param x4 the tile-local transform origin on the luma-grid X axis
     * @param y4 the tile-local transform origin on the luma-grid Y axis
     * @param transformSize the current luma transform size
     * @return the luma coefficient skip-context in `[0, 7)`
     */
    fun lumaCoefficientSkipContext(
        blockSize: BlockSize,
        x4: Int,
        y4: Int,
        transformSize: TransformSize
    ): Int {
        val nonNullBlockSize = requireNotNullCompat(blockSize, "blockSize")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        if (nonNullBlockSize.width4() == nonNullTransformSize.width4()
            && nonNullBlockSize.height4() == nonNullTransformSize.height4()
        ) {
            return 0
        }

        val aboveContext: Int = mergeCoefficientContext(
            aboveLumaCoefficientContext,
            x4,
            nonNullTransformSize.width4()
        )
        val leftContext: Int = mergeCoefficientContext(
            leftLumaCoefficientContext,
            y4,
            nonNullTransformSize.height4()
        )
        return LUMA_COEFFICIENT_SKIP_CONTEXTS[min(
            aboveContext and 0x3F,
            4
        )][min(leftContext and 0x3F, 4)]
    }

    /** Returns the luma DC-sign context for one transform unit.
     * 
     * This follows `dav1d`'s `get_dc_sign_ctx()` rule by merging the sign classes stored in the
     * current top and left coefficient-context bytes across the visible transform span.
     * 
     * @param transformUnit the current luma transform unit
     * @return the luma DC-sign context in `[0, 3)`
     */
    fun lumaDcSignContext(transformUnit: TransformUnit): Int {
        val nonNullTransformUnit =
            requireNotNullCompat(transformUnit, "transformUnit")
        return lumaDcSignContext(
            nonNullTransformUnit.position.x4,
            nonNullTransformUnit.position.y4,
            nonNullTransformUnit.size
        )
    }

    /** Returns the luma DC-sign context for one tile-local transform span.
     * 
     * @param x4 the tile-local transform origin on the luma-grid X axis
     * @param y4 the tile-local transform origin on the luma-grid Y axis
     * @param transformSize the current luma transform size
     * @return the luma DC-sign context in `[0, 3)`
     */
    fun lumaDcSignContext(x4: Int, y4: Int, transformSize: TransformSize): Int {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val signBalance: Int = sumDcSignClasses(
            aboveLumaCoefficientContext,
            x4,
            nonNullTransformSize.width4()
        ) + sumDcSignClasses(
            leftLumaCoefficientContext,
            y4,
            nonNullTransformSize.height4()
        )
        return (if (signBalance != 0) 1 else 0) + (if (signBalance > 0) 1 else 0)
    }

    /** Returns the chroma coefficient skip-context for one transform unit on the supplied plane.
     * 
     * Chroma coefficient-skip syntax uses dav1d's dedicated context range `7..12` instead of the
     * luma skip-context lookup table. The context is based on whether the coded chroma block spans
     * multiple transform units and whether top or left chroma coefficient edges contain any
     * non-zero history for the current plane.
     * 
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @param blockSize the coded block size that owns the current chroma transform unit
     * @param position the current block origin in tile-relative luma 4x4 units
     * @param transformSize the current chroma transform size
     * @return the chroma coefficient skip-context in `[7, 13)`
     */
    fun chromaCoefficientSkipContext(
        plane: Int,
        blockSize: BlockSize,
        position: BlockPosition,
        transformSize: TransformSize
    ): Int {
        val nonNullPosition = requireNotNullCompat(position, "position")
        return chromaCoefficientSkipContext(
            plane,
            blockSize,
            nonNullPosition.x4,
            nonNullPosition.y4,
            transformSize
        )
    }

    /** Returns the chroma coefficient skip-context for one tile-local transform span.
     * 
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @param blockSize the coded block size that owns the current transform unit
     * @param x4 the tile-local transform origin on the luma-grid X axis
     * @param y4 the tile-local transform origin on the luma-grid Y axis
     * @param transformSize the current chroma transform size
     * @return the chroma coefficient skip-context in `[7, 13)`
     */
    fun chromaCoefficientSkipContext(
        plane: Int,
        blockSize: BlockSize,
        x4: Int,
        y4: Int,
        transformSize: TransformSize
    ): Int {
        val nonNullBlockSize = requireNotNullCompat(blockSize, "blockSize")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val aboveContexts = selectAboveChromaCoefficientContext(plane)
        val leftContexts = selectLeftChromaCoefficientContext(plane)
        val notOneBlock =
            chromaBlockLog2Width4(nonNullBlockSize) > nonNullTransformSize.log2Width4()
                    || chromaBlockLog2Height4(nonNullBlockSize) > nonNullTransformSize.log2Height4()
        val aboveHasNonZero: Boolean = hasNonZeroCoefficientContext(
            aboveContexts,
            x4 shr chromaSubsamplingX,
            nonNullTransformSize.width4()
        )
        val leftHasNonZero: Boolean = hasNonZeroCoefficientContext(
            leftContexts,
            y4 shr chromaSubsamplingY,
            nonNullTransformSize.height4()
        )
        return 7 + (if (notOneBlock) 3 else 0) + (if (aboveHasNonZero) 1 else 0) + (if (leftHasNonZero) 1 else 0)
    }

    /** Returns the chroma DC-sign context for one tile-local transform span.
     * 
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @param x4 the tile-local transform origin on the luma-grid X axis
     * @param y4 the tile-local transform origin on the luma-grid Y axis
     * @param transformSize the current chroma transform size
     * @return the chroma DC-sign context in `[0, 3)`
     */
    fun chromaDcSignContext(plane: Int, x4: Int, y4: Int, transformSize: TransformSize): Int {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val aboveContexts = selectAboveChromaCoefficientContext(plane)
        val leftContexts = selectLeftChromaCoefficientContext(plane)
        val signBalance: Int = sumDcSignClasses(
            aboveContexts,
            x4 shr chromaSubsamplingX,
            nonNullTransformSize.width4()
        ) + sumDcSignClasses(
            leftContexts,
            y4 shr chromaSubsamplingY,
            nonNullTransformSize.height4()
        )
        return (if (signBalance != 0) 1 else 0) + (if (signBalance > 0) 1 else 0)
    }

    /** Updates the default transform-context dimensions after one block header is decoded.
     * 
     * Inter blocks use their coded block dimensions for subsequent transform-size contexts, which
     * matches `dav1d`'s `tx_intra` edge-state updates. Intra blocks may later override this with
     * the chosen luma transform size once transform syntax has been read.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded coded block size
     */
    fun updateDefaultTransformContext(position: BlockPosition, size: BlockSize) {
        updateTransformContext(
            requireNotNullCompat(position, "position"),
            requireNotNullCompat(size, "size"),
            size.log2Width4(),
            size.log2Height4()
        )
    }

    /** Updates the transform-size context after one intra-like block chooses its luma transform size.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded coded block size
     * @param transformSize the chosen luma transform size
     */
    fun updateIntraTransformContext(
        position: BlockPosition,
        size: BlockSize,
        transformSize: TransformSize
    ) {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        updateTransformContext(
            requireNotNullCompat(position, "position"),
            requireNotNullCompat(size, "size"),
            nonNullTransformSize.log2Width4(),
            nonNullTransformSize.log2Height4()
        )
    }

    /** Updates the inter var-tx context for one transform region.
     * 
     * @param position the local tile-relative origin of the current transform region
     * @param width4 the transform-region width in 4x4 units
     * @param height4 the transform-region height in 4x4 units
     * @param transformSize the chosen transform size for the current region
     */
    fun updateInterTransformContext(
        position: BlockPosition,
        width4: Int,
        height4: Int,
        transformSize: TransformSize
    ) {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        updateInterTransformContext(
            requireNotNullCompat(position, "position"),
            width4,
            height4,
            nonNullTransformSize.log2Width4(),
            nonNullTransformSize.log2Height4()
        )
    }

    /** Updates the inter var-tx context for one region using raw width/height log2 values.
     * 
     * This variant is used when switchable inter transform mode stores the coded block dimensions
     * rather than a legal transform enum, such as 128x128 superblocks.
     * 
     * @param position the local tile-relative origin of the current region
     * @param width4 the region width in 4x4 units
     * @param height4 the region height in 4x4 units
     * @param widthLog2 the stored width in `log2(4x4 units)`
     * @param heightLog2 the stored height in `log2(4x4 units)`
     */
    fun updateInterTransformContext(
        position: BlockPosition,
        width4: Int,
        height4: Int,
        widthLog2: Int,
        heightLog2: Int
    ) {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val endX4 = min(tileWidth4, nonNullPosition.x4 + width4)
        val endY4 = min(tileHeight4, nonNullPosition.y4 + height4)
        for (x4 in nonNullPosition.x4..<endX4) {
            aboveInterTransformWidthLog2[x4] = widthLog2.toByte()
        }
        for (y4 in nonNullPosition.y4..<endY4) {
            leftInterTransformHeightLog2[y4] = heightLog2.toByte()
        }
    }

    /** Updates the luma coefficient-context state after one transform residual unit is decoded.
     * 
     * @param transformUnit the decoded luma transform residual unit
     * @param coefficientContextByte the coefficient-context byte written back for the decoded unit
     */
    fun updateLumaCoefficientContext(transformUnit: TransformUnit, coefficientContextByte: Int) {
        val nonNullTransformUnit =
            requireNotNullCompat(transformUnit, "transformUnit")
        updateLumaCoefficientContext(
            nonNullTransformUnit.position.x4,
            nonNullTransformUnit.position.y4,
            nonNullTransformUnit.size,
            coefficientContextByte
        )
    }

    /** Updates the luma coefficient-context state for one tile-local transform span.
     * 
     * @param x4 the tile-local transform origin on the luma-grid X axis
     * @param y4 the tile-local transform origin on the luma-grid Y axis
     * @param transformSize the decoded luma transform size
     * @param coefficientContextByte the coefficient-context byte written back for the decoded unit
     */
    fun updateLumaCoefficientContext(
        x4: Int,
        y4: Int,
        transformSize: TransformSize,
        coefficientContextByte: Int
    ) {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        require(coefficientContextByte in 0..0xFF) { "coefficientContextByte out of range: $coefficientContextByte" }
        val endX4 = min(tileWidth4, x4 + nonNullTransformSize.width4())
        val endY4 = min(tileHeight4, y4 + nonNullTransformSize.height4())
        val storedValue = coefficientContextByte.toByte()
        for (currentX4 in x4..<endX4) {
            aboveLumaCoefficientContext[currentX4] = storedValue
        }
        for (currentY4 in y4..<endY4) {
            leftLumaCoefficientContext[currentY4] = storedValue
        }
    }

    /** Updates the chroma coefficient-context state after one chroma transform residual unit is decoded.
     * 
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @param position the current block origin in tile-relative luma 4x4 units
     * @param transformSize the current chroma transform size
     * @param coefficientContextByte the coefficient-context byte written back for the decoded unit
     */
    fun updateChromaCoefficientContext(
        plane: Int,
        position: BlockPosition,
        transformSize: TransformSize,
        coefficientContextByte: Int
    ) {
        val nonNullPosition = requireNotNullCompat(position, "position")
        updateChromaCoefficientContext(
            plane,
            nonNullPosition.x4,
            nonNullPosition.y4,
            transformSize,
            coefficientContextByte
        )
    }

    /** Updates the chroma coefficient-context state for one tile-local transform span.
     * 
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @param x4 the tile-local transform origin on the luma-grid X axis
     * @param y4 the tile-local transform origin on the luma-grid Y axis
     * @param transformSize the decoded chroma transform size
     * @param coefficientContextByte the coefficient-context byte written back for the decoded unit
     */
    fun updateChromaCoefficientContext(
        plane: Int,
        x4: Int,
        y4: Int,
        transformSize: TransformSize,
        coefficientContextByte: Int
    ) {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        require(coefficientContextByte in 0..0xFF) { "coefficientContextByte out of range: $coefficientContextByte" }
        val aboveContexts = selectAboveChromaCoefficientContext(plane)
        val leftContexts = selectLeftChromaCoefficientContext(plane)
        val startX4 = x4 shr chromaSubsamplingX
        val startY4 = y4 shr chromaSubsamplingY
        val endX4 = min(aboveContexts.size, startX4 + nonNullTransformSize.width4())
        val endY4 = min(leftContexts.size, startY4 + nonNullTransformSize.height4())
        val storedValue = coefficientContextByte.toByte()
        for (currentX4 in startX4..<endX4) {
            aboveContexts[currentX4] = storedValue
        }
        for (currentY4 in startY4..<endY4) {
            leftContexts[currentY4] = storedValue
        }
    }

    /** Writes one transform-context width/height pair across the visible edges of one block span.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded coded block size
     * @param widthLog2 the transform-context width in `log2(4x4 units)`
     * @param heightLog2 the transform-context height in `log2(4x4 units)`
     */
    private fun updateTransformContext(
        position: BlockPosition,
        size: BlockSize,
        widthLog2: Int,
        heightLog2: Int
    ) {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        val endX4 = min(tileWidth4, nonNullPosition.x4 + nonNullSize.width4())
        val endY4 = min(tileHeight4, nonNullPosition.y4 + nonNullSize.height4())
        for (x4 in nonNullPosition.x4..<endX4) {
            aboveTransformWidthLog2[x4] = widthLog2.toByte()
        }
        for (y4 in nonNullPosition.y4..<endY4) {
            leftTransformHeightLog2[y4] = heightLog2.toByte()
        }
    }

    /** Returns the effective chroma-block width log2 in chroma 4x4 units for one coded block size.
     * 
     * @param blockSize the coded block size to inspect
     * @return the effective chroma-block width log2 in chroma 4x4 units
     */
    private fun chromaBlockLog2Width4(blockSize: BlockSize): Int {
        val nonNullBlockSize = requireNotNullCompat(blockSize, "blockSize")
        val log2Width4 = nonNullBlockSize.log2Width4()
        return max(0, log2Width4 - (if (log2Width4 != 0) chromaSubsamplingX else 0))
    }

    /** Returns the effective chroma-block height log2 in chroma 4x4 units for one coded block size.
     * 
     * @param blockSize the coded block size to inspect
     * @return the effective chroma-block height log2 in chroma 4x4 units
     */
    private fun chromaBlockLog2Height4(blockSize: BlockSize): Int {
        val nonNullBlockSize = requireNotNullCompat(blockSize, "blockSize")
        val log2Height4 = nonNullBlockSize.log2Height4()
        return max(0, log2Height4 - (if (log2Height4 != 0) chromaSubsamplingY else 0))
    }

    /** Returns the stored above-edge chroma coefficient-context bytes for one chroma plane.
     * 
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @return the stored above-edge chroma coefficient-context bytes for the supplied plane
     */
    private fun selectAboveChromaCoefficientContext(plane: Int): ByteArray {
        return aboveChromaCoefficientContext[requireChromaPlaneIndex(plane)]
    }

    /** Returns the stored left-edge chroma coefficient-context bytes for one chroma plane.
     * 
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @return the stored left-edge chroma coefficient-context bytes for the supplied plane
     */
    private fun selectLeftChromaCoefficientContext(plane: Int): ByteArray {
        return leftChromaCoefficientContext[requireChromaPlaneIndex(plane)]
    }

    /** Returns whether the current block has an already-decoded inter neighbor usable by OBMC.
     * 
     * This follows AV1's 8x8-granularity neighbor scan against the causal above and left edges.
     * 
     * @param position the local tile-relative block origin
     * @param size the current block size
     * @return whether at least one above or left neighbor can provide an OBMC predictor
     */
    fun hasOverlappableCandidates(position: BlockPosition, size: BlockSize): Boolean {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        val startX4 = nonNullPosition.x4
        val startY4 = nonNullPosition.y4
        val endX4 = min(tileWidth4, startX4 + nonNullSize.width4())
        val endY4 = min(tileHeight4, startY4 + nonNullSize.height4())

        if (startY4 > 0) {
            var x4 = startX4
            while (x4 < endX4) {
                val sampleX4 = min(aboveIntra.size - 1, x4 or 1)
                if (aboveIntra[sampleX4].toInt() == 0 && aboveReferenceFrame0[sampleX4] >= 0) {
                    return true
                }
                x4 += 2
            }
        }
        if (startX4 > 0) {
            var y4 = startY4
            while (y4 < endY4) {
                val sampleY4 = min(leftIntra.size - 1, y4 or 1)
                if (leftIntra[sampleY4].toInt() == 0 && leftReferenceFrame0[sampleY4] >= 0) {
                    return true
                }
                y4 += 2
            }
        }
        return false
    }

    /** Returns whether the current block has already-decoded causal samples usable by local warped motion.
     * 
     * Local warped motion can only use single-reference inter neighbors that share the current
     * primary reference. The causal sample set includes direct top and left edges plus eligible
     * top-left and top-right corner blocks.
     * 
     * @param position the local tile-relative block origin
     * @param size the current block size
     * @param referenceFrame0 the current block primary inter reference in internal LAST..ALTREF order
     * @return whether at least one compatible causal neighbor can seed local warped motion
     */
    fun hasLocalWarpSamples(
        position: BlockPosition,
        size: BlockSize,
        referenceFrame0: Int
    ): Boolean {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        if (referenceFrame0 < 0) {
            return false
        }

        val startX4 = nonNullPosition.x4
        val startY4 = nonNullPosition.y4
        val endX4 = min(tileWidth4, startX4 + nonNullSize.width4())
        val endY4 = min(tileHeight4, startY4 + nonNullSize.height4())
        val hasTop = startY4 > 0
        val hasLeft = startX4 > 0
        if (startY4 > 0 && hasLocalWarpSampleAlongSpan(
                true,
                startY4 - 1,
                startX4,
                endX4,
                referenceFrame0
            )
        ) {
            return true
        }
        if (startX4 > 0 && hasLocalWarpSampleAlongSpan(
                false,
                startX4 - 1,
                startY4,
                endY4,
                referenceFrame0
            )
        ) {
            return true
        }

        var hasTopLeft = hasTop && hasLeft
        var hasTopRight = hasTop
                && max(nonNullSize.width4(), nonNullSize.height4()) < 32 && endX4 < tileWidth4
        if (hasTop) {
            val firstTopBlock = storedBlockAtOrNull(startX4, startY4 - 1)
            if (firstTopBlock != null && firstTopBlock.width4() >= nonNullSize.width4()) {
                if (firstTopBlock.originX4() != startX4) {
                    hasTopLeft = false
                }
                if (firstTopBlock.originX4() + firstTopBlock.width4() > endX4) {
                    hasTopRight = false
                }
            }
        }
        if (hasLeft) {
            val firstLeftBlock = storedBlockAtOrNull(startX4 - 1, startY4)
            if (firstLeftBlock != null && firstLeftBlock.height4() >= nonNullSize.height4() && firstLeftBlock.originY4() != startY4) {
                hasTopLeft = false
            }
        }
        if (hasTopLeft && isLocalWarpSampleBlock(
                storedBlockAtOrNull(startX4 - 1, startY4 - 1),
                referenceFrame0
            )
        ) {
            return true
        }
        return hasTopRight
                && isLocalWarpSampleBlock(storedBlockAtOrNull(endX4, startY4 - 1), referenceFrame0)
    }

    /** Updates the neighbor state after decoding one block header.
     * 
     * @param header the decoded block header that should become the new above/left edge state
     */
    fun updateFromBlockHeader(header: BlockHeader) {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val position = nonNullHeader.position()
        val size = nonNullHeader.size()
        val intra = (if (nonNullHeader.intra() || nonNullHeader.useIntrabc()) 1 else 0).toByte()
        val skip = (if (nonNullHeader.skip()) 1 else 0).toByte()
        val skipMode = (if (nonNullHeader.skipMode()) 1 else 0).toByte()
        val compoundReference = (if (nonNullHeader.compoundReference()) 1 else 0).toByte()
        val compoundPredictionType: Byte =
            compoundPredictionTypeContext(nonNullHeader.compoundPredictionType())
        val segmentPredicted = (if (nonNullHeader.segmentPredicted()) 1 else 0).toByte()
        val segmentId = nonNullHeader.segmentId().toByte()
        val paletteSize = nonNullHeader.yPaletteSize().toByte()
        val chromaPaletteSize = nonNullHeader.uvPaletteSize().toByte()
        val referenceFrame0 = nonNullHeader.referenceFrame0().toByte()
        val referenceFrame1 = nonNullHeader.referenceFrame1().toByte()
        val motionVector0: InterMotionVector = fallbackMotionVector(nonNullHeader.motionVector0())
        val motionVector1: InterMotionVector = fallbackMotionVector(nonNullHeader.motionVector1())
        val usesNewMotionVector = (if (usesNewMotionVector(nonNullHeader)) 1 else 0).toByte()
        val usesGlobalMotionMode: Boolean = usesGlobalMotionMode(nonNullHeader)
        val horizontalInterpolationFilter: Byte =
            interpolationFilterSymbol(nonNullHeader.horizontalInterpolationFilter())
        val verticalInterpolationFilter: Byte =
            interpolationFilterSymbol(nonNullHeader.verticalInterpolationFilter())
        val mode = if (nonNullHeader.intra()) nonNullHeader.yMode() else LumaIntraPredictionMode.DC
        val endX4 = min(tileWidth4, position.x4 + size.width4())
        val endY4 = min(tileHeight4, position.y4 + size.height4())
        val storedBlock = StoredBlock(
            position.x4,
            position.y4,
            size.width4(),
            size.height4(),
            segmentId.toInt() and 0xFF,
            intra.toInt() != 0,
            nonNullHeader.useIntrabc(),
            compoundReference.toInt() != 0,
            nonNullHeader.interIntra(),
            referenceFrame0.toInt(),
            referenceFrame1.toInt(),
            motionVector0,
            motionVector1,
            usesNewMotionVector.toInt() != 0,
            usesGlobalMotionMode
        )
        if (updateSegmentIdMap) {
            currentSegmentIdMap.fill(
                (tileStartX8 shl 1) + position.x4,
                (tileStartY8 shl 1) + position.y4,
                size.width4(),
                size.height4(),
                segmentId.toInt() and 0xFF
            )
        }
        for (x4 in position.x4..<endX4) {
            aboveIntra[x4] = intra
            aboveSkip[x4] = skip
            aboveSkipMode[x4] = skipMode
            aboveCompoundReference[x4] = compoundReference
            aboveCompoundPredictionType[x4] = compoundPredictionType
            aboveReferenceFrame0[x4] = referenceFrame0
            aboveReferenceFrame1[x4] = referenceFrame1
            aboveMotionVector0[x4] = motionVector0
            aboveMotionVector1[x4] = motionVector1
            aboveUsesNewMotionVector[x4] = usesNewMotionVector
            aboveInterpolationFilterHorizontal[x4] = horizontalInterpolationFilter
            aboveInterpolationFilterVertical[x4] = verticalInterpolationFilter
            aboveSegmentPredicted[x4] = segmentPredicted
            abovePaletteSize[x4] = paletteSize
            aboveChromaPaletteSize[x4] = chromaPaletteSize
            Companion.copyPaletteEntries(
                nonNullHeader,
                abovePaletteEntries[0][x4],
                abovePaletteEntries[1][x4],
                abovePaletteEntries[2][x4]
            )
            aboveMode[x4] = mode!!
        }
        for (y4 in position.y4..<endY4) {
            leftIntra[y4] = intra
            leftSkip[y4] = skip
            leftSkipMode[y4] = skipMode
            leftCompoundReference[y4] = compoundReference
            leftCompoundPredictionType[y4] = compoundPredictionType
            leftReferenceFrame0[y4] = referenceFrame0
            leftReferenceFrame1[y4] = referenceFrame1
            leftMotionVector0[y4] = motionVector0
            leftMotionVector1[y4] = motionVector1
            leftUsesNewMotionVector[y4] = usesNewMotionVector
            leftInterpolationFilterHorizontal[y4] = horizontalInterpolationFilter
            leftInterpolationFilterVertical[y4] = verticalInterpolationFilter
            leftSegmentPredicted[y4] = segmentPredicted
            leftPaletteSize[y4] = paletteSize
            leftChromaPaletteSize[y4] = chromaPaletteSize
            Companion.copyPaletteEntries(
                nonNullHeader,
                leftPaletteEntries[0][y4],
                leftPaletteEntries[1][y4],
                leftPaletteEntries[2][y4]
            )
            leftMode[y4] = mode!!
        }
        for (y4 in position.y4..<endY4) {
            for (x4 in position.x4..<endX4) {
                storedBlocks[blockIndex(x4, y4)] = storedBlock
            }
        }
        updateDecodedTemporalMotionField(nonNullHeader, endX4, endY4, motionVector0, motionVector1)
    }

    /** Updates the current-frame temporal motion field with one decoded block header.
     * 
     * This write-back path does not feed samples back into the current frame's spatial candidate
     * scan. The stored samples remain available for refreshed reference state, although frames
     * that request temporal `refmvs` projection are rejected before tile decoding.
     * 
     * @param header the decoded block header that should be projected into the current-frame temporal field
     * @param endX4 the exclusive end X coordinate of the decoded block in 4x4 units
     * @param endY4 the exclusive end Y coordinate of the decoded block in 4x4 units
     * @param motionVector0 the normalized primary motion-vector state chosen for the block
     * @param motionVector1 the normalized secondary motion-vector state chosen for the block
     */
    private fun updateDecodedTemporalMotionField(
        header: BlockHeader,
        endX4: Int,
        endY4: Int,
        motionVector0: InterMotionVector,
        motionVector1: InterMotionVector
    ) {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val startX8 = nonNullHeader.position().x8()
        val startY8 = nonNullHeader.position().y8()
        val endX8 = min(decodedTemporalMotionField.width8(), (endX4 + 1) shr 1)
        val endY8 = min(decodedTemporalMotionField.height8(), (endY4 + 1) shr 1)
        if (startX8 >= endX8 || startY8 >= endY8) {
            return
        }

        val temporalMotionBlock: TemporalMotionBlock? = createDecodedTemporalMotionBlock(
            nonNullHeader,
            motionVector0,
            motionVector1
        )
        for (y8 in startY8..<endY8) {
            for (x8 in startX8..<endX8) {
                if (temporalMotionBlock == null) {
                    decodedTemporalMotionField.clearBlock(x8, y8)
                } else {
                    decodedTemporalMotionField.setBlock(x8, y8, temporalMotionBlock)
                }
            }
        }
    }

    /** Returns the flattened stored-block index for one tile-relative 4x4 coordinate.
     * 
     * @param x4 the tile-relative X coordinate in 4x4 units
     * @param y4 the tile-relative Y coordinate in 4x4 units
     * @return the flattened stored-block index for one tile-relative 4x4 coordinate
     */
    private fun blockIndex(x4: Int, y4: Int): Int {
        return y4 * tileWidth4 + x4
    }

    /** Returns the stored decoded block covering one tile-relative 4x4 coordinate, or `null`.
     * 
     * @param x4 the tile-relative X coordinate in 4x4 units
     * @param y4 the tile-relative Y coordinate in 4x4 units
     * @return the stored decoded block covering one tile-relative 4x4 coordinate, or `null`
     */
    private fun storedBlockAt(x4: Int, y4: Int): StoredBlock? {
        return storedBlocks[blockIndex(x4, y4)]
    }

    /** Returns whether a stored row or column contains a compatible local-warp sample block.
     * 
     * @param rowScan whether the scan walks a fixed row instead of a fixed column
     * @param fixedCoordinate4 the fixed row or column coordinate in 4x4 units
     * @param spanStart4 the inclusive start of the scanned span on the varying axis
     * @param spanEnd4 the exclusive end of the scanned span on the varying axis
     * @param referenceFrame0 the current block primary inter reference in internal LAST..ALTREF order
     * @return whether a compatible local-warp sample block was found
     */
    private fun hasLocalWarpSampleAlongSpan(
        rowScan: Boolean,
        fixedCoordinate4: Int,
        spanStart4: Int,
        spanEnd4: Int,
        referenceFrame0: Int
    ): Boolean {
        var previousBlock: StoredBlock? = null
        for (varyingCoordinate4 in spanStart4..<spanEnd4) {
            val storedBlock = if (rowScan)
                storedBlockAt(varyingCoordinate4, fixedCoordinate4)
            else
                storedBlockAt(fixedCoordinate4, varyingCoordinate4)
            if (storedBlock == null || storedBlock == previousBlock) {
                continue
            }
            previousBlock = storedBlock
            if (isLocalWarpSampleBlock(storedBlock, referenceFrame0)) {
                return true
            }
        }
        return false
    }

    /** Returns whether one stored neighbor uses a unidirectional compound reference pair.
     * 
     * @param leftEdge whether the stored neighbor lives on the left edge instead of the above edge
     * @param index the edge index in 4x4 units
     * @return whether one stored neighbor uses a unidirectional compound reference pair
     */
    private fun hasUnidirectionalCompoundReference(leftEdge: Boolean, index: Int): Boolean {
        val referenceFrame0 =
            (if (leftEdge) leftReferenceFrame0[index] else aboveReferenceFrame0[index]).toInt()
        val referenceFrame1 =
            (if (leftEdge) leftReferenceFrame1[index] else aboveReferenceFrame1[index]).toInt()
        return (referenceFrame0 < 4) == (referenceFrame1 < 4)
    }

    /** Returns the masked-compound context contribution from one stored edge neighbor.
     * 
     * @param leftEdge whether the stored neighbor lives on the left edge instead of the above edge
     * @param index the edge index in 4x4 units
     * @return the masked-compound context contribution from the stored neighbor
     */
    private fun maskedCompoundNeighborContext(leftEdge: Boolean, index: Int): Int {
        val compoundPredictionType =
            (
                    if (leftEdge) leftCompoundPredictionType[index] else aboveCompoundPredictionType[index]).toInt()
        val referenceFrame0 =
            (if (leftEdge) leftReferenceFrame0[index] else aboveReferenceFrame0[index]).toInt()
        if (compoundPredictionType >= CompoundPredictionType.SEGMENT.contextValue()) {
            return 1
        }
        return if (referenceFrame0 == 6) 3 else 0
    }

    /** Returns the joint-compound context contribution from one stored edge neighbor.
     * 
     * @param leftEdge whether the stored neighbor lives on the left edge instead of the above edge
     * @param index the edge index in 4x4 units
     * @return the joint-compound context contribution from the stored neighbor
     */
    private fun jointCompoundNeighborContext(leftEdge: Boolean, index: Int): Int {
        val compoundPredictionType =
            (
                    if (leftEdge) leftCompoundPredictionType[index] else aboveCompoundPredictionType[index]).toInt()
        val referenceFrame0 =
            (if (leftEdge) leftReferenceFrame0[index] else aboveReferenceFrame0[index]).toInt()
        return if (compoundPredictionType >= CompoundPredictionType.AVERAGE.contextValue() || referenceFrame0 == 6) 1 else 0
    }

    /** Clears and returns the reusable reference-count workspace.
     * 
     * @return the zero-filled four-entry reference-count workspace
     */
    private fun resetReferenceCountScratch(): IntArray {
        fillCompat(referenceCountScratch, 0)
        return referenceCountScratch
    }

    /** Accumulates forward-vs-backward reference counts from already-decoded neighbors.
     * 
     * @param count the two-entry destination array for forward and backward reference counts
     * @param includeIntra whether intra-coded neighbors should also contribute
     * @param position the current block position
     */
    private fun accumulateForwardBackwardCounts(
        count: IntArray,
        includeIntra: Boolean,
        position: BlockPosition
    ) {
        val x4 = position.x4
        val y4 = position.y4
        if (hasTopNeighbor(position) && (includeIntra || aboveIntra[x4].toInt() == 0)) {
            count[if ((aboveReferenceFrame0[x4].toInt() and 0xFF) >= 4) 1 else 0]++
            if (aboveCompoundReference[x4].toInt() != 0) {
                count[if ((aboveReferenceFrame1[x4].toInt() and 0xFF) >= 4) 1 else 0]++
            }
        }
        if (hasLeftNeighbor(position) && (includeIntra || leftIntra[y4].toInt() == 0)) {
            count[if ((leftReferenceFrame0[y4].toInt() and 0xFF) >= 4) 1 else 0]++
            if (leftCompoundReference[y4].toInt() != 0) {
                count[if ((leftReferenceFrame1[y4].toInt() and 0xFF) >= 4) 1 else 0]++
            }
        }
    }

    /** Accumulates reference counts for one contiguous range of reference-frame indices.
     * 
     * @param count the destination count array with at least `length` entries
     * @param position the current block position
     * @param startReference the inclusive first tracked reference-frame index
     * @param length the number of tracked reference-frame indices
     */
    private fun accumulateReferenceCounts(
        count: IntArray,
        position: BlockPosition,
        startReference: Int,
        length: Int
    ) {
        val x4 = position.x4
        val y4 = position.y4
        if (hasTopNeighbor(position) && aboveIntra[x4].toInt() == 0) {
            incrementReferenceCount(count, aboveReferenceFrame0[x4].toInt(), startReference, length)
            if (aboveCompoundReference[x4].toInt() != 0) {
                incrementReferenceCount(
                    count,
                    aboveReferenceFrame1[x4].toInt(),
                    startReference,
                    length
                )
            }
        }
        if (hasLeftNeighbor(position) && leftIntra[y4].toInt() == 0) {
            incrementReferenceCount(count, leftReferenceFrame0[y4].toInt(), startReference, length)
            if (leftCompoundReference[y4].toInt() != 0) {
                incrementReferenceCount(
                    count,
                    leftReferenceFrame1[y4].toInt(),
                    startReference,
                    length
                )
            }
        }
    }

    /** Scans one AV1 `refmvs` row using decoded block dimensions to advance between candidates.
     * 
     * @param fixedY4 the fixed tile-relative row coordinate
     * @param startX4 the first tile-relative column coordinate
     * @param blockWidth4 the current block width in 4x4 units
     * @param visibleWidth4 the bounded scan width in 4x4 units
     * @param maximumRows the maximum spatial row depth used for candidate weighting
     * @param step4 the minimum horizontal scan step in 4x4 units
     * @param compoundReference whether the current block uses compound references
     * @param referenceFrame0 the primary current-block reference
     * @param referenceFrame1 the secondary current-block reference, or `-1`
     * @param globalMotion the current block's global-motion candidates
     * @param destination the destination candidate stack
     * @param count the number of active candidates already stored
     * @return the updated candidate state and spatial-depth contribution
     */
    private fun scanExactRefMvsRow(
        fixedY4: Int,
        startX4: Int,
        blockWidth4: Int,
        visibleWidth4: Int,
        maximumRows: Int,
        step4: Int,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        globalMotion: ExactSpatialGlobalMotion,
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int
    ): ExactSpatialScanResult {
        var count = count
        val firstBlock = storedBlockAtOrNull(startX4, fixedY4)
        val firstWidth4 = if (firstBlock != null) firstBlock.width4() else 1
        var length4 = max(step4, min(blockWidth4, firstWidth4))
        if (blockWidth4 <= firstWidth4) {
            val firstHeight4 = if (firstBlock != null) firstBlock.height4() else 1
            val weightFactor = if (blockWidth4 == 1)
                2
            else max(2, min(2 * maximumRows, firstHeight4))
            val candidateResult: Long = addExactSpatialCandidate(
                firstBlock,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                globalMotion,
                length4 * weightFactor,
                destination,
                count
            )
            return ExactSpatialScanResult(
                spatialCandidateCount(candidateResult),
                spatialReferenceMatch(candidateResult),
                spatialNewMotionVectorMatch(candidateResult),
                weightFactor shr 1
            )
        }

        var referenceMatch = false
        var haveNewMotionVectorMatch = false
        var offset4 = 0
        while (true) {
            val block = storedBlockAtOrNull(startX4 + offset4, fixedY4)
            val candidateResult: Long = addExactSpatialCandidate(
                block,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                globalMotion,
                length4 * 2,
                destination,
                count
            )
            count = spatialCandidateCount(candidateResult)
            referenceMatch = referenceMatch or spatialReferenceMatch(candidateResult)
            haveNewMotionVectorMatch =
                haveNewMotionVectorMatch or spatialNewMotionVectorMatch(candidateResult)
            offset4 += length4
            if (offset4 >= visibleWidth4) {
                return ExactSpatialScanResult(count, referenceMatch, haveNewMotionVectorMatch, 1)
            }
            val nextBlock = storedBlockAtOrNull(startX4 + offset4, fixedY4)
            length4 = max(step4, if (nextBlock != null) nextBlock.width4() else 1)
        }
    }

    /** Scans one AV1 `refmvs` column using decoded block dimensions to advance between candidates.
     * 
     * @param fixedX4 the fixed tile-relative column coordinate
     * @param startY4 the first tile-relative row coordinate
     * @param blockHeight4 the current block height in 4x4 units
     * @param visibleHeight4 the bounded scan height in 4x4 units
     * @param maximumColumns the maximum spatial column depth used for candidate weighting
     * @param step4 the minimum vertical scan step in 4x4 units
     * @param compoundReference whether the current block uses compound references
     * @param referenceFrame0 the primary current-block reference
     * @param referenceFrame1 the secondary current-block reference, or `-1`
     * @param globalMotion the current block's global-motion candidates
     * @param destination the destination candidate stack
     * @param count the number of active candidates already stored
     * @return the updated candidate state and spatial-depth contribution
     */
    private fun scanExactRefMvsColumn(
        fixedX4: Int,
        startY4: Int,
        blockHeight4: Int,
        visibleHeight4: Int,
        maximumColumns: Int,
        step4: Int,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        globalMotion: ExactSpatialGlobalMotion,
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int
    ): ExactSpatialScanResult {
        var count = count
        val firstBlock = storedBlockAtOrNull(fixedX4, startY4)
        val firstHeight4 = if (firstBlock != null) firstBlock.height4() else 1
        var length4 = max(step4, min(blockHeight4, firstHeight4))
        if (blockHeight4 <= firstHeight4) {
            val firstWidth4 = if (firstBlock != null) firstBlock.width4() else 1
            val weightFactor = if (blockHeight4 == 1)
                2
            else max(2, min(2 * maximumColumns, firstWidth4))
            val candidateResult: Long = addExactSpatialCandidate(
                firstBlock,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                globalMotion,
                length4 * weightFactor,
                destination,
                count
            )
            return ExactSpatialScanResult(
                spatialCandidateCount(candidateResult),
                spatialReferenceMatch(candidateResult),
                spatialNewMotionVectorMatch(candidateResult),
                weightFactor shr 1
            )
        }

        var referenceMatch = false
        var haveNewMotionVectorMatch = false
        var offset4 = 0
        while (true) {
            val block = storedBlockAtOrNull(fixedX4, startY4 + offset4)
            val candidateResult: Long = addExactSpatialCandidate(
                block,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                globalMotion,
                length4 * 2,
                destination,
                count
            )
            count = spatialCandidateCount(candidateResult)
            referenceMatch = referenceMatch or spatialReferenceMatch(candidateResult)
            haveNewMotionVectorMatch =
                haveNewMotionVectorMatch or spatialNewMotionVectorMatch(candidateResult)
            offset4 += length4
            if (offset4 >= visibleHeight4) {
                return ExactSpatialScanResult(count, referenceMatch, haveNewMotionVectorMatch, 1)
            }
            val nextBlock = storedBlockAtOrNull(fixedX4, startY4 + offset4)
            length4 = max(step4, if (nextBlock != null) nextBlock.height4() else 1)
        }
    }

    /** Returns one stored block without throwing when a coordinate is outside the tile.
     * 
     * @param x4 the tile-relative X coordinate in 4x4 units
     * @param y4 the tile-relative Y coordinate in 4x4 units
     * @return the stored block covering the coordinate, or `null`
     */
    private fun storedBlockAtOrNull(x4: Int, y4: Int): StoredBlock? {
        if (x4 < 0 || x4 >= tileWidth4 || y4 < 0 || y4 >= tileHeight4) {
            return null
        }
        return storedBlockAt(x4, y4)
    }

    /** Samples projected temporal motion vectors over one current-block footprint.
     * 
     * Main samples cover at most an 8x8 temporal-grid region and use a two-cell stride for block
     * dimensions of at least 64 pixels. The three AV1 edge probes are included for eligible block
     * dimensions inside the current 64-pixel temporal group.
     * 
     * @param position the current tile-relative block position
     * @param size the current block size
     * @param compoundReference whether the current block uses compound references
     * @param referenceFrame0 the primary current-block reference
     * @param referenceFrame1 the secondary current-block reference, or `-1`
     * @param globalMotionVector0 the primary block global-motion vector
     * @param destination the destination candidate array
     * @param count the number of active candidates already stored
     * @param initialGlobalMotionContext the initial temporal `globalmv` context
     * @return the updated candidate count and temporal `globalmv` context
     */
    private fun scanTemporalMotionField(
        position: BlockPosition,
        size: BlockSize,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        globalMotionVector0: MotionVector,
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int,
        initialGlobalMotionContext: Int
    ): TemporalScanResult {
        var count = count
        if (!referenceMotionVectorProjection.enabled()) {
            return TemporalScanResult(count, 0)
        }

        val x4 = position.x4
        val y4 = position.y4
        val width4 = min(min(size.width4(), 16), tileWidth4 - x4)
        val height4 = min(min(size.height4(), 16), tileHeight4 - y4)
        val width8 = min((width4 + 1) shr 1, 8)
        val height8 = min((height4 + 1) shr 1, 8)
        val stepX8 = if (size.width4() >= 16) 2 else 1
        val stepY8 = if (size.height4() >= 16) 2 else 1
        val originX8 = tileStartX8 + position.x8()
        val originY8 = tileStartY8 + position.y8()
        var globalMotionContext = initialGlobalMotionContext
        var y8 = 0
        while (y8 < height8) {
            var x8 = 0
            while (x8 < width8) {
                val motionVector0 = referenceMotionVectorProjection.motionVectorAt(
                    originX8 + x8,
                    originY8 + y8,
                    referenceFrame0
                )
                if (motionVector0 == null) {
                    x8 += stepX8
                    continue
                }
                if (x8 == 0 && y8 == 0) {
                    globalMotionContext =
                        if (differsFromGlobalMotion(motionVector0, globalMotionVector0)) 1 else 0
                }
                count = appendTemporalCandidate(
                    destination,
                    count,
                    motionVector0,
                    if (compoundReference)
                        referenceMotionVectorProjection.motionVectorAt(
                            originX8 + x8,
                            originY8 + y8,
                            referenceFrame1
                        )
                    else
                        null,
                    compoundReference
                )
                x8 += stepX8
            }
            y8 += stepY8
        }

        if (min(size.width4(), size.height4()) >= 2
            && max(size.width4(), size.height4()) < 16
        ) {
            val localX8 = position.x8()
            val localY8 = position.y8()
            val blockWidth8 = size.width4() shr 1
            val blockHeight8 = size.height4() shr 1
            val tileEndX8 = tileWidth4 shr 1
            val tileEndY8 = tileHeight4 shr 1
            val bottomY8 = localY8 + blockHeight8
            val hasBottom = bottomY8 < min(tileEndY8, (localY8 and 7.inv()) + 8)
            if (hasBottom && localX8 - 1 >= max(0, localX8 and 7.inv())) {
                count = appendTemporalCandidateAt(
                    destination,
                    count,
                    tileStartX8 + localX8 - 1,
                    tileStartY8 + bottomY8,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1
                )
            }
            val rightX8 = localX8 + blockWidth8
            if (rightX8 < min(tileEndX8, (localX8 and 7.inv()) + 8)) {
                if (hasBottom) {
                    count = appendTemporalCandidateAt(
                        destination,
                        count,
                        tileStartX8 + rightX8,
                        tileStartY8 + bottomY8,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1
                    )
                }
                if (localY8 + blockHeight8 - 1 < min(tileEndY8, (localY8 and 7.inv()) + 8)) {
                    count = appendTemporalCandidateAt(
                        destination,
                        count,
                        tileStartX8 + rightX8,
                        tileStartY8 + localY8 + blockHeight8 - 1,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1
                    )
                }
            }
        }
        return TemporalScanResult(count, globalMotionContext)
    }

    /** Appends one projected temporal candidate read at a frame-relative coordinate.
     * 
     * @param destination the destination candidate array
     * @param count the number of active candidates already stored
     * @param x8 the frame-relative X coordinate in 8x8 units
     * @param y8 the frame-relative Y coordinate in 8x8 units
     * @param compoundReference whether the current block uses compound references
     * @param referenceFrame0 the primary current-block reference
     * @param referenceFrame1 the secondary current-block reference, or `-1`
     * @return the updated candidate count
     */
    private fun appendTemporalCandidateAt(
        destination: Array<ProvisionalMotionVectorCandidate>,
        count: Int,
        x8: Int,
        y8: Int,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int
    ): Int {
        val motionVector0 = referenceMotionVectorProjection.motionVectorAt(x8, y8, referenceFrame0)
        if (motionVector0 == null) {
            return count
        }
        return appendTemporalCandidate(
            destination,
            count,
            motionVector0,
            if (compoundReference) referenceMotionVectorProjection.motionVectorAt(
                x8,
                y8,
                referenceFrame1
            ) else null,
            compoundReference
        )
    }

    /** Updates the partition edge state after a non-deferred partition decision.
     * 
     * @param position the current square block position
     * @param span8 the edge span to update in 8x8 units
     * @param aboveValue the value to store on the above edge
     * @param leftValue the value to store on the left edge
     */
    fun updatePartition(position: BlockPosition, span8: Int, aboveValue: Int, leftValue: Int) {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val startX8 = nonNullPosition.x8()
        val startY8 = nonNullPosition.y8()
        val endX8 = min(abovePartition.size, startX8 + span8)
        val endY8 = min(leftPartition.size, startY8 + span8)
        for (x8 in startX8..<endX8) {
            abovePartition[x8] = aboveValue.toByte()
        }
        for (y8 in startY8..<endY8) {
            leftPartition[y8] = leftValue.toByte()
        }
    }

    /** Current-block global vectors used when exact spatial candidates came from global-warp blocks.
     * 
     * @param primary the primary current-block global-motion vector
     * @param secondary the secondary current-block global-motion vector
     * @param primaryNonTranslation whether the primary global model varies with block position
     * @param secondaryNonTranslation whether the secondary global model varies with block position
     */
    data class ExactSpatialGlobalMotion(
        val primary: MotionVector,
        val secondary: MotionVector,
        val primaryNonTranslation: Boolean,
        val secondaryNonTranslation: Boolean
    ) {
        init {
            requireNotNullCompat(primary, "primary")
            requireNotNullCompat(secondary, "secondary")
        }
    }

    /** An inter-mode syntax context derived from spatial and projected temporal candidates. */
    class ProvisionalInterModeContext private constructor(
        /** The zero-based provisional `newmv` context index in `[0, 6)`. */
        val singleNewMvContext: Int,
        singleGlobalMvContext: Int,
        singleReferenceMvContext: Int,
        compoundInterModeContext: Int,
        syntaxCandidateCount: Int,
        candidates: Array<ProvisionalMotionVectorCandidate>,
        takeOwnership: Boolean
    ) {
        /** The zero-based temporal `globalmv` context index in `[0, 2)`. */
        val singleGlobalMvContext: Int

        /** The zero-based provisional `refmv` context index in `[0, 6)`. */
        val singleReferenceMvContext: Int

        /** The zero-based provisional compound inter-mode context index in `[0, 8)`. */
        val compoundInterModeContext: Int

        /** The number of candidates reported to dynamic-reference-list syntax. */
        val syntaxCandidateCount: Int

        /** The provisional motion-vector candidates sorted in descending weight order. */
        val candidates: Array<ProvisionalMotionVectorCandidate>

        /** Creates one provisional inter-mode syntax context.
         * 
         * @param singleNewMvContext the zero-based provisional `newmv` context index in `[0, 6)`
         * @param singleGlobalMvContext the zero-based temporal `globalmv` context index in `[0, 2)`
         * @param singleReferenceMvContext the zero-based provisional `refmv` context index in `[0, 6)`
         * @param compoundInterModeContext the zero-based provisional compound inter-mode context index in `[0, 8)`
         * @param syntaxCandidateCount the number of candidates visible to dynamic-reference-list syntax
         * @param candidates the provisional motion-vector candidates sorted in descending weight order
         */
        constructor(
            singleNewMvContext: Int,
            singleGlobalMvContext: Int,
            singleReferenceMvContext: Int,
            compoundInterModeContext: Int,
            syntaxCandidateCount: Int,
            candidates: Array<ProvisionalMotionVectorCandidate>
        ) : this(
            singleNewMvContext,
            singleGlobalMvContext,
            singleReferenceMvContext,
            compoundInterModeContext,
            syntaxCandidateCount,
            candidates,
            false
        )

        init {
            require(singleGlobalMvContext in 0..1) { "singleGlobalMvContext out of range: $singleGlobalMvContext" }
            this.singleGlobalMvContext = singleGlobalMvContext
            this.singleReferenceMvContext = singleReferenceMvContext
            this.compoundInterModeContext = compoundInterModeContext
            val checkedCandidates =
                requireNotNullCompat(
                    candidates,
                    "candidates"
                )
            this.candidates = if (takeOwnership)
                checkedCandidates
            else checkedCandidates.copyOf()
            require(syntaxCandidateCount in 0..this.candidates.size) { "syntaxCandidateCount out of range: $syntaxCandidateCount" }
            this.syntaxCandidateCount = syntaxCandidateCount
        }

        /** Returns the zero-based provisional `newmv` context index in `[0, 6)`.
         * 
         * @return the zero-based provisional `newmv` context index in `[0, 6)`
         */
        fun singleNewMvContext(): Int {
            return singleNewMvContext
        }

        /** Returns the temporal `globalmv` context.
         * 
         * @return the zero-based temporal `globalmv` context index in `[0, 2)`
         */
        fun singleGlobalMvContext(): Int {
            return singleGlobalMvContext
        }

        /** Returns the zero-based provisional `refmv` context index in `[0, 6)`.
         * 
         * @return the zero-based provisional `refmv` context index in `[0, 6)`
         */
        fun singleReferenceMvContext(): Int {
            return singleReferenceMvContext
        }

        /** Returns the zero-based provisional compound inter-mode context index in `[0, 8)`.
         * 
         * @return the zero-based provisional compound inter-mode context index in `[0, 8)`
         */
        fun compoundInterModeContext(): Int {
            return compoundInterModeContext
        }

        /** Returns the number of candidates visible to dynamic-reference-list syntax.
         * 
         * Single-reference global-motion fallback slots remain addressable through
         * [motionVectorCandidate] but are not included in this count.
         * 
         * @return the number of candidates visible to dynamic-reference-list syntax
         */
        fun candidateCount(): Int {
            return syntaxCandidateCount
        }

        /** Returns one provisional candidate weight by index.
         * 
         * @param index the zero-based candidate index
         * @return one provisional candidate weight by index
         */
        fun candidateWeight(index: Int): Int {
            return candidate(checkIndexCompat(index, candidates.size)).weight()
        }

        /** Returns one provisional primary motion vector by index.
         * 
         * @param index the zero-based candidate index
         * @return one provisional primary motion vector by index
         */
        fun candidateMotionVector0(index: Int): InterMotionVector {
            return candidate(checkIndexCompat(index, candidates.size)).motionVector0()
        }

        /** Returns one provisional secondary motion vector by index, or `null`.
         * 
         * @param index the zero-based candidate index
         * @return one provisional secondary motion vector by index, or `null`
         */
        fun candidateMotionVector1(index: Int): InterMotionVector? {
            return candidate(checkIndexCompat(index, candidates.size)).motionVector1()
        }

        /** Returns the provisional dynamic-reference-list context for one candidate boundary.
         * 
         * This follows `dav1d`'s threshold rule over the candidate-stack weights.
         * 
         * @param referenceIndex the zero-based candidate boundary index
         * @return the zero-based provisional dynamic-reference-list context in `[0, 3)`
         */
        fun drlContext(referenceIndex: Int): Int {
            val index = checkIndexCompat(referenceIndex + 1, candidates.size) - 1
            if (candidates[index].weight() >= 640) {
                return if (candidates[index + 1].weight() < 640) 1 else 0
            }
            return if (candidates[index + 1].weight() < 640) 2 else 0
        }

        /** Returns one stored provisional motion-vector candidate by index.
         * 
         * @param index the zero-based candidate index
         * @return one stored provisional motion-vector candidate by index
         */
        fun candidate(index: Int): ProvisionalMotionVectorCandidate {
            return candidates[checkIndexCompat(index, candidates.size)]
        }

        /** Returns the number of de-duplicated spatial motion-vector candidates currently available.
         * 
         * The returned stack keeps real neighbor-derived candidates first and appends at most one
         * synthetic zero fallback at the end.
         * 
         * @return the number of de-duplicated spatial motion-vector candidates currently available
         */
        fun motionVectorCandidateCount(): Int {
            return candidates.size
        }

        /** Returns one de-duplicated spatial motion-vector candidate by index.
         * 
         * @param index the zero-based spatial motion-vector candidate index
         * @return one de-duplicated spatial motion-vector candidate by index
         */
        fun motionVectorCandidate(index: Int): ProvisionalMotionVectorCandidate {
            return candidates[checkIndexCompat(index, candidates.size)]
        }

        /** One provisional motion-vector candidate derived from one bounded neighbor source. */
        class ProvisionalMotionVectorCandidate(
            /** The candidate weight, mutable only while the owning context is being assembled. */
            var weight: Int,
            motionVector0: InterMotionVector,
            /** The secondary provisional motion vector carried by this candidate, or `null`. */
            val motionVector1: InterMotionVector?,
            /** Whether this candidate is the synthetic zero baseline instead of a real neighbor. */
            val synthetic: Boolean
        ) {
            /** The primary provisional motion vector carried by this candidate. */
            val motionVector0: InterMotionVector

            init {
                this.motionVector0 =
                    requireNotNullCompat(motionVector0, "motionVector0")
            }

            /** Returns the candidate weight used for DRL-context derivation.
             * 
             * @return the candidate weight used for DRL-context derivation
             */
            fun weight(): Int {
                return weight
            }

            /** Returns the primary provisional motion vector carried by this candidate.
             * 
             * @return the primary provisional motion vector carried by this candidate
             */
            fun motionVector0(): InterMotionVector {
                return motionVector0
            }

            /** Returns the secondary provisional motion vector carried by this candidate, or `null`.
             * 
             * @return the secondary provisional motion vector carried by this candidate, or `null`
             */
            fun motionVector1(): InterMotionVector? {
                return motionVector1
            }

            /** Returns whether this candidate is the synthetic zero baseline.
             * 
             * @return whether this candidate is the synthetic zero baseline
             */
            fun synthetic(): Boolean {
                return synthetic
            }

            /** Increases this candidate's weight while its owning context is being assembled.
             * 
             * @param increment the non-negative weight increment
             */
            fun increaseWeight(increment: Int) {
                require(increment >= 0) { "increment < 0: $increment" }
                weight += increment
            }
        }

        companion object {
            /** Creates one provisional context that owns an exact candidate snapshot.
             * 
             * @param singleNewMvContext the zero-based provisional `newmv` context index in `[0, 6)`
             * @param singleGlobalMvContext the zero-based temporal `globalmv` context index in `[0, 2)`
             * @param singleReferenceMvContext the zero-based provisional `refmv` context index in `[0, 6)`
             * @param compoundInterModeContext the zero-based provisional compound inter-mode context index in `[0, 8)`
             * @param syntaxCandidateCount the number of candidates visible to dynamic-reference-list syntax
             * @param candidates the exclusively owned exact candidate snapshot
             * @return one provisional inter-mode context backed by `candidates`
             */
            fun fromOwnedCandidates(
                singleNewMvContext: Int,
                singleGlobalMvContext: Int,
                singleReferenceMvContext: Int,
                compoundInterModeContext: Int,
                syntaxCandidateCount: Int,
                candidates: Array<ProvisionalMotionVectorCandidate>
            ): ProvisionalInterModeContext {
                return ProvisionalInterModeContext(
                    singleNewMvContext,
                    singleGlobalMvContext,
                    singleReferenceMvContext,
                    compoundInterModeContext,
                    syntaxCandidateCount,
                    candidates,
                    true
                )
            }
        }
    }

    /** One stored decoded block reused by the bounded spatial scan. */
    class StoredBlock(
        /** The block origin X coordinate in tile-relative 4x4 units. */
        val originX4: Int,
        /** The block origin Y coordinate in tile-relative 4x4 units. */
        val originY4: Int,
        /** The block width in 4x4 units. */
        val width4: Int,
        /** The block height in 4x4 units. */
        val height4: Int,
        /** The decoded segment identifier. */
        val segmentId: Int,
        /** Whether the stored block is intra-coded. */
        val intra: Boolean,
        /** Whether the stored block uses same-frame intrabc prediction. */
        val intrabc: Boolean,
        /** Whether the stored block uses compound inter references. */
        val compoundReference: Boolean,
        /** Whether the stored block blends inter and intra prediction. */
        val interIntra: Boolean,
        /** The stored primary inter reference in internal LAST..ALTREF order. */
        val referenceFrame0: Int,
        /** The stored secondary inter reference in internal LAST..ALTREF order, or `-1`. */
        val referenceFrame1: Int,
        motionVector0: InterMotionVector,
        motionVector1: InterMotionVector,
        /** Whether the stored block used any `NEWMV`-carrying inter mode. */
        val usesNewMotionVector: Boolean,
        /** Whether the stored block selected the all-global inter mode. */
        val usesGlobalMotionMode: Boolean
    ) {
        /** The stored primary motion-vector state. */
        val motionVector0: InterMotionVector

        /** The stored secondary motion-vector state. */
        val motionVector1: InterMotionVector

        init {
            this.motionVector0 =
                requireNotNullCompat(motionVector0, "motionVector0")
            this.motionVector1 =
                requireNotNullCompat(motionVector1, "motionVector1")
        }

        /** Returns the block origin X coordinate in tile-relative 4x4 units.
         * 
         * @return the block origin X coordinate in tile-relative 4x4 units
         */
        fun originX4(): Int {
            return originX4
        }

        /** Returns the block origin Y coordinate in tile-relative 4x4 units.
         * 
         * @return the block origin Y coordinate in tile-relative 4x4 units
         */
        fun originY4(): Int {
            return originY4
        }

        /** Returns the block width in 4x4 units.
         * 
         * @return the block width in 4x4 units
         */
        fun width4(): Int {
            return width4
        }

        /** Returns the block height in 4x4 units.
         * 
         * @return the block height in 4x4 units
         */
        fun height4(): Int {
            return height4
        }

        /** Returns whether the stored block is intra-coded.
         * 
         * @return whether the stored block is intra-coded
         */
        fun intra(): Boolean {
            return intra
        }

        /** Returns whether the stored block uses same-frame intrabc prediction.
         * 
         * @return whether the stored block uses same-frame intrabc prediction
         */
        fun intrabc(): Boolean {
            return intrabc
        }

        /** Returns whether the stored block uses compound inter references.
         * 
         * @return whether the stored block uses compound inter references
         */
        fun compoundReference(): Boolean {
            return compoundReference
        }

        /** Returns whether the stored block blends inter and intra prediction.
         * 
         * @return whether the stored block blends inter and intra prediction
         */
        fun interIntra(): Boolean {
            return interIntra
        }

        /** Returns the stored primary inter reference in internal LAST..ALTREF order.
         * 
         * @return the stored primary inter reference in internal LAST..ALTREF order
         */
        fun referenceFrame0(): Int {
            return referenceFrame0
        }

        /** Returns the stored secondary inter reference in internal LAST..ALTREF order, or `-1`.
         * 
         * @return the stored secondary inter reference in internal LAST..ALTREF order, or `-1`
         */
        fun referenceFrame1(): Int {
            return referenceFrame1
        }

        /** Returns the stored primary motion-vector state.
         * 
         * @return the stored primary motion-vector state
         */
        fun motionVector0(): InterMotionVector {
            return motionVector0
        }

        /** Returns the stored secondary motion-vector state.
         * 
         * @return the stored secondary motion-vector state
         */
        fun motionVector1(): InterMotionVector {
            return motionVector1
        }

        /** Returns whether the stored block used any `NEWMV`-carrying inter mode.
         * 
         * @return whether the stored block used any `NEWMV`-carrying inter mode
         */
        fun usesNewMotionVector(): Boolean {
            return usesNewMotionVector
        }

        /** Returns whether the stored block selected the all-global inter mode.
         * 
         * @return whether the stored block selected the all-global inter mode
         */
        fun usesGlobalMotionMode(): Boolean {
            return usesGlobalMotionMode
        }
    }

    /** The result of one dimension-aware AV1 spatial row or column scan.
     * 
     * @param candidateCount the updated number of valid candidates
     * @param referenceMatch whether the scan found the requested reference selection
     * @param haveNewMotionVectorMatch whether a matching block used a `NEWMV`-carrying mode
     * @param scanDistance the spatial-depth contribution returned by the scan
     */
    data class ExactSpatialScanResult(
        val candidateCount: Int,
        val referenceMatch: Boolean,
        val haveNewMotionVectorMatch: Boolean,
        val scanDistance: Int
    )

    /** The result of sampling the current block's projected temporal motion field.
     * 
     * @param candidateCount the updated number of valid weighted candidates
     * @param globalMotionContext the temporal `globalmv` context in `[0, 2)`
     */
    data class TemporalScanResult(val candidateCount: Int, val globalMotionContext: Int)

    /** AV1 `refmvs` syntax contexts derived from the scanned neighbors.
     * 
     * @param singleNewMvContext the zero-based `newmv` context index in `[0, 6)`
     * @param singleReferenceMvContext the zero-based `refmv` context index in `[0, 6)`
     * @param compoundInterModeContext the zero-based compound inter-mode context index in `[0, 8)`
     */
    data class RefMvsContextSummary(
        val singleNewMvContext: Int,
        val singleReferenceMvContext: Int,
        val compoundInterModeContext: Int
    )

    /** The current-frame segment prediction for one block position.
     * 
     * @param predictedSegmentId the predicted segment identifier derived from already-decoded neighbors
     * @param context the zero-based segment-id context derived from already-decoded neighbors
     */
    data class SegmentPrediction(val predictedSegmentId: Int, val context: Int)
    companion object {
        /** Shared provisional zero vector used when a decoded block has no stored motion vector. */
        val PREDICTED_ZERO_MOTION_VECTOR: InterMotionVector =
            InterMotionVector.Companion.predicted(MotionVector.Companion.zero())

        /** The maximum number of provisional motion-vector candidate entries retained locally. */
        private const val PROVISIONAL_CANDIDATE_CAPACITY = 8

        /** The frame-border extension allowed for reference motion vectors in eighth-pel units. */
        const val REFERENCE_MOTION_VECTOR_BORDER = 16 * 8

        /** The number of eighth-pel motion-vector units represented by one 4x4 coding unit. */
        const val MOTION_VECTOR_UNITS_PER_4X4 = 4 * 8

        /** Internal reference selector used while scanning same-frame intrabc displacement vectors. */
        const val INTRABC_REFERENCE_FRAME = -2

        /** The AV1 coefficient-context byte that marks one transform block as all-zero. */
        private const val ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40

        /** The inter var-tx edge state used where no top or left transform neighbor is available. */
        val UNAVAILABLE_INTER_TRANSFORM_LOG2 = TransformSize.TX_64X64.log2Width4().toByte()

        /** The switchable interpolation-filter symbol used for regular 8-tap filtering. */
        private const val INTERPOLATION_FILTER_REGULAR: Byte = 0

        /** The switchable interpolation-filter symbol used for smooth 8-tap filtering. */
        private const val INTERPOLATION_FILTER_SMOOTH: Byte = 1

        /** The switchable interpolation-filter symbol used for sharp 8-tap filtering. */
        private const val INTERPOLATION_FILTER_SHARP: Byte = 2

        /** The switchable interpolation-filter sentinel used when no usable neighbor filter is available. */
        private const val INTERPOLATION_FILTER_UNSET: Byte = 3

        /** The `dav1d` luma coefficient skip-context lookup table indexed by merged top and left counts. */
        val LUMA_COEFFICIENT_SKIP_CONTEXTS: Array<IntArray> =
            arrayOf<IntArray>(
                intArrayOf(1, 2, 2, 2, 3),
                intArrayOf(2, 4, 4, 4, 5),
                intArrayOf(2, 4, 4, 4, 5),
                intArrayOf(2, 4, 4, 4, 5),
                intArrayOf(3, 5, 5, 5, 6)
            )

        /** Creates initialized neighbor context state for one tile.
         * 
         * @param tileContext the tile-local decode context
         * @return initialized neighbor context state for one tile
         */
        fun create(tileContext: TileDecodeContext): BlockNeighborContext {
            val nonNullTileContext =
                requireNotNullCompat(tileContext, "tileContext")
            val tileWidth4 = nonNullTileContext.codedWidth4()
            val tileHeight4 = nonNullTileContext.codedHeight4()
            val frameWidth4 =
                ((nonNullTileContext.frameHeader().frameSize().codedWidth() + 7) shr 3) shl 1
            val frameHeight4 =
                ((nonNullTileContext.frameHeader().frameSize().height() + 7) shr 3) shl 1
            val tileWidth8 = (tileWidth4 + 1) shr 1
            val tileHeight8 = (tileHeight4 + 1) shr 1
            val chromaSubsamplingX: Int =
                chromaSubsamplingX(nonNullTileContext.sequenceHeader().colorConfig().chromaFormat)
            val chromaSubsamplingY: Int =
                chromaSubsamplingY(nonNullTileContext.sequenceHeader().colorConfig().chromaFormat)
            val chromaTileWidth4: Int = chromaTileSpan(tileWidth4, chromaSubsamplingX)
            val chromaTileHeight4: Int = chromaTileSpan(tileHeight4, chromaSubsamplingY)
            val keyFrame = nonNullTileContext.frameHeader().frameType() == Av1FrameType.KEY

            val aboveIntra = ByteArray(tileWidth4)
            val leftIntra = ByteArray(tileHeight4)
            val aboveReferenceFrame0 = ByteArray(tileWidth4)
            val leftReferenceFrame0 = ByteArray(tileHeight4)
            val aboveReferenceFrame1 = ByteArray(tileWidth4)
            val leftReferenceFrame1 = ByteArray(tileHeight4)
            val aboveTransformWidthLog2 = ByteArray(tileWidth4)
            val leftTransformHeightLog2 = ByteArray(tileHeight4)
            val aboveInterTransformWidthLog2 = ByteArray(tileWidth4)
            val leftInterTransformHeightLog2 = ByteArray(tileHeight4)
            val aboveLumaCoefficientContext = ByteArray(tileWidth4)
            val leftLumaCoefficientContext = ByteArray(tileHeight4)
            val aboveChromaCoefficientContext: Array<ByteArray> =
                Array(2) { ByteArray(chromaTileWidth4) }
            val leftChromaCoefficientContext: Array<ByteArray> =
                Array(2) { ByteArray(chromaTileHeight4) }
            val aboveMotionVector0: Array<InterMotionVector> =
                arrayOfNullsCompat<InterMotionVector>(tileWidth4)
            val leftMotionVector0: Array<InterMotionVector> =
                arrayOfNullsCompat<InterMotionVector>(tileHeight4)
            val aboveMotionVector1: Array<InterMotionVector> =
                arrayOfNullsCompat<InterMotionVector>(tileWidth4)
            val leftMotionVector1: Array<InterMotionVector> =
                arrayOfNullsCompat<InterMotionVector>(tileHeight4)
            val aboveInterpolationFilterHorizontal = ByteArray(tileWidth4)
            val leftInterpolationFilterHorizontal = ByteArray(tileHeight4)
            val aboveInterpolationFilterVertical = ByteArray(tileWidth4)
            val leftInterpolationFilterVertical = ByteArray(tileHeight4)
            val aboveMode: Array<LumaIntraPredictionMode> =
                arrayOfNullsCompat<LumaIntraPredictionMode>(tileWidth4)
            val leftMode: Array<LumaIntraPredictionMode> =
                arrayOfNullsCompat<LumaIntraPredictionMode>(tileHeight4)
            val defaultMotionVector: InterMotionVector = PREDICTED_ZERO_MOTION_VECTOR
            fillCompat(aboveTransformWidthLog2, -1.toByte())
            fillCompat(leftTransformHeightLog2, -1.toByte())
            fillCompat(aboveInterTransformWidthLog2, UNAVAILABLE_INTER_TRANSFORM_LOG2)
            fillCompat(leftInterTransformHeightLog2, UNAVAILABLE_INTER_TRANSFORM_LOG2)
            fillCompat(aboveLumaCoefficientContext, ALL_ZERO_COEFFICIENT_CONTEXT_BYTE.toByte())
            fillCompat(leftLumaCoefficientContext, ALL_ZERO_COEFFICIENT_CONTEXT_BYTE.toByte())
            fillCompat(
                aboveChromaCoefficientContext[0],
                ALL_ZERO_COEFFICIENT_CONTEXT_BYTE.toByte()
            )
            fillCompat(
                aboveChromaCoefficientContext[1],
                ALL_ZERO_COEFFICIENT_CONTEXT_BYTE.toByte()
            )
            fillCompat(leftChromaCoefficientContext[0], ALL_ZERO_COEFFICIENT_CONTEXT_BYTE.toByte())
            fillCompat(leftChromaCoefficientContext[1], ALL_ZERO_COEFFICIENT_CONTEXT_BYTE.toByte())
            fillCompat(aboveReferenceFrame0, -1.toByte())
            fillCompat(leftReferenceFrame0, -1.toByte())
            fillCompat(aboveReferenceFrame1, -1.toByte())
            fillCompat(leftReferenceFrame1, -1.toByte())
            fillCompat(aboveMotionVector0, defaultMotionVector)
            fillCompat(leftMotionVector0, defaultMotionVector)
            fillCompat(aboveMotionVector1, defaultMotionVector)
            fillCompat(leftMotionVector1, defaultMotionVector)
            fillCompat(aboveInterpolationFilterHorizontal, INTERPOLATION_FILTER_UNSET)
            fillCompat(leftInterpolationFilterHorizontal, INTERPOLATION_FILTER_UNSET)
            fillCompat(aboveInterpolationFilterVertical, INTERPOLATION_FILTER_UNSET)
            fillCompat(leftInterpolationFilterVertical, INTERPOLATION_FILTER_UNSET)
            fillCompat(aboveMode, LumaIntraPredictionMode.DC)
            fillCompat(leftMode, LumaIntraPredictionMode.DC)
            if (keyFrame) {
                fillCompat(aboveIntra, 1.toByte())
                fillCompat(leftIntra, 1.toByte())
            }

            return BlockNeighborContext(
                tileWidth4,
                tileHeight4,
                frameWidth4,
                frameHeight4,
                chromaSubsamplingX,
                chromaSubsamplingY,
                nonNullTileContext.decodedTemporalMotionField(),
                nonNullTileContext.referenceMotionVectorProjection(),
                nonNullTileContext.currentSegmentIdMap(),
                nonNullTileContext.frameHeader().segmentation().enabled()
                        && nonNullTileContext.frameHeader().segmentation().updateMap(),
                nonNullTileContext.startX() shr 3,
                nonNullTileContext.startY() shr 3,
                arrayOfNullsCompat<StoredBlock>(tileWidth4 * tileHeight4),
                aboveIntra,
                leftIntra,
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                aboveReferenceFrame0,
                leftReferenceFrame0,
                aboveReferenceFrame1,
                leftReferenceFrame1,
                aboveMotionVector0,
                leftMotionVector0,
                aboveMotionVector1,
                leftMotionVector1,
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                aboveInterpolationFilterHorizontal,
                leftInterpolationFilterHorizontal,
                aboveInterpolationFilterVertical,
                leftInterpolationFilterVertical,
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                ByteArray(tileWidth4),
                ByteArray(tileHeight4),
                Array<Array<IntArray>>(3) { Array<IntArray>(tileWidth4) { IntArray(8) } },
                Array<Array<IntArray>>(3) { Array<IntArray>(tileHeight4) { IntArray(8) } },
                aboveMode,
                leftMode,
                ByteArray(tileWidth8),
                ByteArray(tileHeight8),
                aboveTransformWidthLog2,
                leftTransformHeightLog2,
                aboveInterTransformWidthLog2,
                leftInterTransformHeightLog2,
                aboveLumaCoefficientContext,
                leftLumaCoefficientContext,
                aboveChromaCoefficientContext,
                leftChromaCoefficientContext
            )
        }

        /** Returns the horizontal chroma subsampling shift for one decoded chroma format.
         * 
         * @param chromaFormat the decoded sequence chroma format
         * @return the horizontal chroma subsampling shift for the supplied chroma format
         */
        fun chromaSubsamplingX(chromaFormat: Av1ChromaFormat): Int {
            val nonNullChromaFormat =
                requireNotNullCompat(chromaFormat, "chromaFormat")
            return when (nonNullChromaFormat) {
                Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
                Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
            }
        }

        /** Returns the vertical chroma subsampling shift for one decoded chroma format.
         * 
         * @param chromaFormat the decoded sequence chroma format
         * @return the vertical chroma subsampling shift for the supplied chroma format
         */
        fun chromaSubsamplingY(chromaFormat: Av1ChromaFormat): Int {
            val nonNullChromaFormat =
                requireNotNullCompat(chromaFormat, "chromaFormat")
            return when (nonNullChromaFormat) {
                Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
                Av1ChromaFormat.YUV420 -> 1
            }
        }

        /** Returns the rounded chroma-grid span corresponding to one luma-grid span and subsampling shift.
         * 
         * @param lumaSpan4 the luma-grid span in 4x4 units
         * @param subsamplingShift the chroma subsampling shift for the relevant axis
         * @return the rounded chroma-grid span corresponding to the supplied luma-grid span
         */
        fun chromaTileSpan(lumaSpan4: Int, subsamplingShift: Int): Int {
            if (lumaSpan4 <= 0) {
                return 0
            }
            return (lumaSpan4 + (1 shl subsamplingShift) - 1) shr subsamplingShift
        }

        /** Clamps one candidate component to AV1's extended frame boundary.
         * 
         * @param motionVector the candidate component and its resolved state
         * @param minimumRow the inclusive minimum row component in eighth-pel units
         * @param maximumRow the inclusive maximum row component in eighth-pel units
         * @param minimumColumn the inclusive minimum column component in eighth-pel units
         * @param maximumColumn the inclusive maximum column component in eighth-pel units
         * @return the original component when already in range, or a state-preserving clamped component
         */
        fun clampReferenceMotionVector(
            motionVector: InterMotionVector,
            minimumRow: Int,
            maximumRow: Int,
            minimumColumn: Int,
            maximumColumn: Int
        ): InterMotionVector {
            val nonNullMotionVector =
                requireNotNullCompat(motionVector, "motionVector")
            val vector = nonNullMotionVector.vector
            val row = max(minimumRow, min(maximumRow, vector.rowEighthPel))
            val column = max(minimumColumn, min(maximumColumn, vector.columnEighthPel))
            if (row == vector.rowEighthPel && column == vector.columnEighthPel) {
                return nonNullMotionVector
            }
            return InterMotionVector(MotionVector(row, column), nonNullMotionVector.resolved)
        }

        /** Appends one component while retaining at most the first two values.
         * 
         * @param destination the ordered component list
         * @param motionVector the component to append
         */
        fun appendExtendedComponent(
            destination: MutableList<MotionVector>,
            motionVector: MotionVector
        ) {
            if (destination.size < 2) {
                destination.add(requireNotNullCompat(motionVector, "motionVector"))
            }
        }

        /** Returns one component from matching, alternate, and global fallback sources in priority order.
         * 
         * @param matching matching-reference components
         * @param alternate sign-normalized alternate-reference components
         * @param globalMotionVector the global-motion fallback
         * @param index the zero-based completed component index in `[0, 2)`
         * @return the selected component
         */
        fun compoundExtendedComponent(
            matching: MutableList<MotionVector>,
            alternate: MutableList<MotionVector>,
            globalMotionVector: MotionVector,
            index: Int
        ): MotionVector {
            var sourceIndex = index
            if (sourceIndex < matching.size) {
                return matching.get(sourceIndex)
            }
            sourceIndex -= matching.size
            if (sourceIndex < alternate.size) {
                return alternate.get(sourceIndex)
            }
            return requireNotNullCompat(globalMotionVector, "globalMotionVector")
        }

        /** Builds one paired compound extended candidate at the supplied completed-list index.
         * 
         * @param matching0 matching components for the primary reference
         * @param matching1 matching components for the secondary reference
         * @param alternate0 alternate components for the primary reference
         * @param alternate1 alternate components for the secondary reference
         * @param globalMotionVector0 the primary global-motion fallback
         * @param globalMotionVector1 the secondary global-motion fallback
         * @param index the zero-based completed candidate index in `[0, 2)`
         * @return the paired candidate
         */
        fun compoundExtendedCandidate(
            matching0: MutableList<MotionVector>,
            matching1: MutableList<MotionVector>,
            alternate0: MutableList<MotionVector>,
            alternate1: MutableList<MotionVector>,
            globalMotionVector0: MotionVector,
            globalMotionVector1: MotionVector,
            index: Int
        ): ProvisionalMotionVectorCandidate {
            return ProvisionalMotionVectorCandidate(
                2,
                InterMotionVector.Companion.predicted(
                    compoundExtendedComponent(
                        matching0,
                        alternate0,
                        globalMotionVector0,
                        index
                    )
                ),
                InterMotionVector.Companion.predicted(
                    compoundExtendedComponent(
                        matching1,
                        alternate1,
                        globalMotionVector1,
                        index
                    )
                ),
                false
            )
        }

        /** Returns whether two provisional candidates carry equal primary and secondary vectors.
         * 
         * @param left the first candidate
         * @param right the second candidate
         * @return whether both vector pairs are equal
         */
        fun equivalentMotionVectorCandidate(
            left: ProvisionalMotionVectorCandidate,
            right: ProvisionalMotionVectorCandidate
        ): Boolean {
            val nonNullLeft = requireNotNullCompat(left, "left")
            val nonNullRight =
                requireNotNullCompat(right, "right")
            return nonNullLeft.motionVector0().vector == nonNullRight.motionVector0().vector
                    && equivalentMotionVector(
                nonNullLeft.motionVector1(),
                nonNullRight.motionVector1()
            )
        }

        /** Returns the odd-aligned coordinate used for one secondary spatial offset layer.
         * 
         * @param coordinate4 the current block start coordinate in 4x4 units
         * @param secondaryOffset the one-based secondary offset layer index starting at `2`
         * @return the odd-aligned secondary scan coordinate in 4x4 units
         */
        fun secondarySpatialOffsetCoordinate(coordinate4: Int, secondaryOffset: Int): Int {
            return (coordinate4 - (secondaryOffset shl 1) + 1) or 1
        }

        /** Merges one coefficient-context span by OR-ing the stored bytes across the visible edge range.
         * 
         * @param contexts the stored coefficient-context edge bytes
         * @param start the inclusive start coordinate in 4x4 units
         * @param span the requested span length in 4x4 units
         * @return the merged coefficient-context byte across the requested visible edge range
         */
        fun mergeCoefficientContext(contexts: ByteArray, start: Int, span: Int): Int {
            val end = min(contexts.size, start + span)
            var merged: Int = ALL_ZERO_COEFFICIENT_CONTEXT_BYTE
            for (index in start..<end) {
                merged = merged or (contexts[index].toInt() and 0xFF)
            }
            return merged
        }

        /** Returns whether one coefficient-context edge span contains any non-zero residual history.
         * 
         * @param contexts the stored coefficient-context edge bytes
         * @param start the inclusive start coordinate in 4x4 units
         * @param span the requested span length in 4x4 units
         * @return whether any stored context byte differs from the all-zero sentinel
         */
        fun hasNonZeroCoefficientContext(
            contexts: ByteArray,
            start: Int,
            span: Int
        ): Boolean {
            val end = min(contexts.size, start + span)
            for (index in start..<end) {
                if ((contexts[index].toInt() and 0xFF) != ALL_ZERO_COEFFICIENT_CONTEXT_BYTE) {
                    return true
                }
            }
            return false
        }

        /** Sums the stored DC-sign classes across one visible coefficient-context edge span.
         * 
         * Stored coefficient-context bytes encode negative DC blocks as class `0`, all-zero or zero-DC
         * blocks as class `1`, and positive DC blocks as class `2`. This helper converts the stored
         * bytes back into the signed balance used by `dav1d`'s `get_dc_sign_ctx()`.
         * 
         * @param contexts the stored coefficient-context edge bytes
         * @param start the inclusive start coordinate in 4x4 units
         * @param span the requested span length in 4x4 units
         * @return the signed DC-sign balance across the requested visible edge range
         */
        fun sumDcSignClasses(contexts: ByteArray, start: Int, span: Int): Int {
            val end = min(contexts.size, start + span)
            var sum = 0
            for (index in start..<end) {
                val signClass = (contexts[index].toInt() and 0xFF) ushr 6
                if (signClass == 0) {
                    sum--
                } else if (signClass == 2) {
                    sum++
                }
            }
            return sum
        }

        /** Validates and returns one chroma plane index used by the chroma coefficient-context helpers.
         * 
         * @param plane the requested chroma plane index
         * @return the same chroma plane index after validation
         */
        fun requireChromaPlaneIndex(plane: Int): Int {
            require(!(plane != 0 && plane != 1)) { "plane must be 0 (U) or 1 (V): $plane" }
            return plane
        }

        /** Validates and returns one switchable interpolation-filter direction index.
         * 
         * Direction `0` selects the horizontal filter symbol and direction `1` selects the vertical
         * filter symbol.
         * 
         * @param direction the requested switchable interpolation-filter direction index
         * @return the same direction index after validation
         */
        fun requireInterpolationFilterDirection(direction: Int): Int {
            require(!(direction != 0 && direction != 1)) { "direction must be 0 (horizontal) or 1 (vertical): $direction" }
            return direction
        }

        /** Returns the edge filter symbol that matches the current primary reference, or the unset sentinel.
         * 
         * @param referenceFrame0 the current primary inter reference in internal LAST..ALTREF order
         * @param neighborIntra whether the stored neighbor is intra-coded
         * @param neighborCompound whether the stored neighbor uses compound references
         * @param neighborReferenceFrame0 the stored neighbor primary inter reference in internal LAST..ALTREF order
         * @param neighborReferenceFrame1 the stored neighbor secondary inter reference in internal LAST..ALTREF order, or `-1`
         * @param storedFilterSymbol the stored edge filter symbol
         * @return the edge filter symbol that matches the current primary reference, or the unset sentinel
         */
        fun matchingInterpolationFilterSymbol(
            referenceFrame0: Int,
            neighborIntra: Boolean,
            neighborCompound: Boolean,
            neighborReferenceFrame0: Byte,
            neighborReferenceFrame1: Byte,
            storedFilterSymbol: Byte
        ): Byte {
            if (neighborIntra || storedFilterSymbol == INTERPOLATION_FILTER_UNSET) {
                return INTERPOLATION_FILTER_UNSET
            }
            if ((neighborReferenceFrame0.toInt() and 0xFF) == referenceFrame0) {
                return storedFilterSymbol
            }
            if (neighborCompound && (neighborReferenceFrame1.toInt() and 0xFF) == referenceFrame0) {
                return storedFilterSymbol
            }
            return INTERPOLATION_FILTER_UNSET
        }

        /** Returns the switchable interpolation-filter symbol stored for one decoded filter, or the unset sentinel.
         * 
         * @param interpolationFilter the decoded interpolation filter, or `null`
         * @return the switchable interpolation-filter symbol stored for one decoded filter, or the unset sentinel
         */
        fun interpolationFilterSymbol(interpolationFilter: InterpolationFilter?): Byte {
            if (interpolationFilter == null) {
                return INTERPOLATION_FILTER_UNSET
            }
            return when (interpolationFilter) {
                InterpolationFilter.EIGHT_TAP_REGULAR -> INTERPOLATION_FILTER_REGULAR
                InterpolationFilter.EIGHT_TAP_SMOOTH -> INTERPOLATION_FILTER_SMOOTH
                InterpolationFilter.EIGHT_TAP_SHARP -> INTERPOLATION_FILTER_SHARP
                InterpolationFilter.BILINEAR, InterpolationFilter.SWITCHABLE -> INTERPOLATION_FILTER_UNSET
            }
        }

        /** Copies one block header's palette entries into fixed-size neighbor caches.
         * 
         * Only entries below the active palette sizes are observable through the neighbor context.
         * 
         * @param header the source block header
         * @param yEntries the luma palette cache
         * @param uEntries the U chroma palette cache
         * @param vEntries the V chroma palette cache
         */
        fun copyPaletteEntries(
            header: BlockHeader,
            yEntries: IntArray,
            uEntries: IntArray,
            vEntries: IntArray
        ) {
            for (index in 0..<header.yPaletteSize()) {
                yEntries[index] = header.yPaletteColor(index)
            }
            for (index in 0..<header.uvPaletteSize()) {
                uEntries[index] = header.uPaletteColor(index)
                vEntries[index] = header.vPaletteColor(index)
            }
        }

        /** Creates the temporal motion-field sample contributed by one decoded block header, or `null`.
         * 
         * Intra and `intrabc` blocks do not contribute temporal motion samples. Inter samples are
         * stored with the already normalized motion-vector states carried by the block header.
         * 
         * @param header the decoded block header
         * @param motionVector0 the normalized primary motion-vector state chosen for the block
         * @param motionVector1 the normalized secondary motion-vector state chosen for the block
         * @return the temporal motion-field sample contributed by the block, or `null`
         */
        fun createDecodedTemporalMotionBlock(
            header: BlockHeader,
            motionVector0: InterMotionVector,
            motionVector1: InterMotionVector
        ): TemporalMotionBlock? {
            val nonNullHeader = requireNotNullCompat(header, "header")
            if (nonNullHeader.intra() || nonNullHeader.useIntrabc() || nonNullHeader.referenceFrame0() < 0) {
                return null
            }
            if (nonNullHeader.compoundReference()) {
                if (nonNullHeader.referenceFrame1() < 0) {
                    return null
                }
                return TemporalMotionBlock.Companion.compoundReference(
                    nonNullHeader.referenceFrame0(),
                    nonNullHeader.referenceFrame1(),
                    requireNotNullCompat(motionVector0, "motionVector0"),
                    requireNotNullCompat(motionVector1, "motionVector1")
                )
            }
            return TemporalMotionBlock.Companion.singleReference(
                nonNullHeader.referenceFrame0(),
                requireNotNullCompat(motionVector0, "motionVector0")
            )
        }

        /** Returns whether one stored block can seed current-block local warped motion.
         * 
         * @param storedBlock the stored causal neighbor block
         * @param referenceFrame0 the current block primary inter reference in internal LAST..ALTREF order
         * @return whether one stored block can seed current-block local warped motion
         */
        fun isLocalWarpSampleBlock(
            storedBlock: StoredBlock?,
            referenceFrame0: Int
        ): Boolean {
            return storedBlock != null && !storedBlock.intra() && !storedBlock.compoundReference() && !storedBlock.interIntra() && storedBlock.referenceFrame0() == referenceFrame0 && storedBlock.motionVector0().resolved
        }

        /** Returns one stored edge motion vector, falling back to a provisional zero vector.
         * 
         * @param motionVector the stored motion vector, or `null`
         * @return one stored edge motion vector, falling back to a provisional zero vector
         */
        fun fallbackMotionVector(motionVector: InterMotionVector?): InterMotionVector {
            return if (motionVector != null) motionVector else PREDICTED_ZERO_MOTION_VECTOR
        }

        /** Returns whether one decoded block used any `NEWMV`-carrying inter mode.
         * 
         * @param header the decoded block header
         * @return whether the decoded block used any `NEWMV`-carrying inter mode
         */
        fun usesNewMotionVector(header: BlockHeader): Boolean {
            val nonNullHeader = requireNotNullCompat(header, "header")
            if (nonNullHeader.singleInterMode() != null) {
                return nonNullHeader.singleInterMode() == SingleInterPredictionMode.NEWMV
            }
            if (nonNullHeader.compoundInterMode() == null) {
                return false
            }
            return when (nonNullHeader.compoundInterMode()) {
                CompoundInterPredictionMode.NEARESTMV_NEARESTMV, CompoundInterPredictionMode.NEARMV_NEARMV, CompoundInterPredictionMode.GLOBALMV_GLOBALMV -> false
                CompoundInterPredictionMode.NEARESTMV_NEWMV, CompoundInterPredictionMode.NEWMV_NEARESTMV, CompoundInterPredictionMode.NEARMV_NEWMV, CompoundInterPredictionMode.NEWMV_NEARMV, CompoundInterPredictionMode.NEWMV_NEWMV -> true
                null -> false
            }
        }

        /** Returns whether one decoded block selected the all-global inter mode.
         * 
         * @param header the decoded block header
         * @return whether the block selected `GLOBALMV` or `GLOBALMV_GLOBALMV`
         */
        fun usesGlobalMotionMode(header: BlockHeader): Boolean {
            val nonNullHeader = requireNotNullCompat(header, "header")
            return (nonNullHeader.singleInterMode()
                    == SingleInterPredictionMode.GLOBALMV)
                    || (nonNullHeader.compoundInterMode()
                    == CompoundInterPredictionMode.GLOBALMV_GLOBALMV)
        }

        /** Returns the edge context value stored for one compound prediction type.
         * 
         * @param compoundPredictionType the decoded compound prediction type, or `null`
         * @return the edge context value stored for the supplied type
         */
        fun compoundPredictionTypeContext(compoundPredictionType: CompoundPredictionType?): Byte {
            return (if (compoundPredictionType == null) 0 else compoundPredictionType.contextValue()).toByte()
        }

        /** Returns the wrapped order-hint difference `poc0 - poc1`.
         * 
         * @param orderHintBits the number of order-hint bits declared by the sequence
         * @param poc0 the minuend order hint
         * @param poc1 the subtrahend order hint
         * @return the wrapped order-hint difference `poc0 - poc1`
         */
        fun orderHintDifference(orderHintBits: Int, poc0: Int, poc1: Int): Int {
            if (orderHintBits == 0) {
                return 0
            }
            val mask = 1 shl (orderHintBits - 1)
            val diff = poc0 - poc1
            return (diff and (mask - 1)) - (diff and mask)
        }

        /** Increments one counted reference bucket when the supplied stored reference falls in range.
         * 
         * @param count the destination count array
         * @param referenceFrame the stored reference-frame index
         * @param startReference the inclusive first tracked reference-frame index
         * @param length the number of tracked reference-frame indices
         */
        fun incrementReferenceCount(
            count: IntArray,
            referenceFrame: Int,
            startReference: Int,
            length: Int
        ) {
            val index = referenceFrame - startReference
            if (index >= 0 && index < length) {
                count[index]++
            }
        }

        /** Adds one matching spatial candidate to an AV1 `refmvs` stack.
         * 
         * @param block the decoded spatial block, or `null`
         * @param compoundReference whether the current block uses compound references
         * @param referenceFrame0 the primary current-block reference
         * @param referenceFrame1 the secondary current-block reference, or `-1`
         * @param globalMotion the current block's global-motion candidates
         * @param weight the spatial candidate weight
         * @param destination the destination candidate stack
         * @param count the number of active candidates already stored
         * @return the updated stack and reference-match state
         */
        fun addExactSpatialCandidate(
            block: StoredBlock?,
            compoundReference: Boolean,
            referenceFrame0: Int,
            referenceFrame1: Int,
            globalMotion: ExactSpatialGlobalMotion,
            weight: Int,
            destination: Array<ProvisionalMotionVectorCandidate>,
            count: Int
        ): Long {
            if (block == null) {
                return spatialCandidateResult(count, false, false)
            }
            if (referenceFrame0 == INTRABC_REFERENCE_FRAME) {
                if (!block.intrabc()) {
                    return spatialCandidateResult(count, false, false)
                }
                val candidate =
                    ProvisionalMotionVectorCandidate(
                        weight,
                        block.motionVector0().asPredicted(),
                        null,
                        false
                    )
                return spatialCandidateResult(
                    appendProvisionalCandidate(destination, count, candidate),
                    true,
                    false
                )
            }
            if (block.intra()) {
                return spatialCandidateResult(count, false, false)
            }
            val candidate: ProvisionalMotionVectorCandidate? =
                provisionalMotionVectorCandidate(
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    block,
                    globalMotion,
                    weight
                )
            if (candidate == null) {
                return spatialCandidateResult(count, false, false)
            }
            return spatialCandidateResult(
                appendProvisionalCandidate(destination, count, candidate),
                true,
                block.usesNewMotionVector()
            )
        }

        /** Packs one spatial-candidate result into an allocation-free primitive value.
         * 
         * @param candidateCount the updated candidate count
         * @param referenceMatch whether the reference selection matched
         * @param haveNewMotionVectorMatch whether the matching block used a new motion vector
         * @return the packed spatial-candidate result
         */
        fun spatialCandidateResult(
            candidateCount: Int,
            referenceMatch: Boolean,
            haveNewMotionVectorMatch: Boolean
        ): Long {
            return (toUnsignedLong(candidateCount)
                    or (if (referenceMatch) 1L shl 32 else 0L)
                    or (if (haveNewMotionVectorMatch) 1L shl 33 else 0L))
        }

        /** Returns the candidate count from one packed spatial-candidate result.
         * 
         * @param result the packed result
         * @return the updated candidate count
         */
        fun spatialCandidateCount(result: Long): Int {
            return result.toInt()
        }

        /** Returns whether one packed spatial-candidate result matched the reference selection.
         * 
         * @param result the packed result
         * @return whether the reference selection matched
         */
        fun spatialReferenceMatch(result: Long): Boolean {
            return (result and (1L shl 32)) != 0L
        }

        /** Returns whether one packed result matched a block using a new motion vector.
         * 
         * @param result the packed result
         * @return whether the matching block used a new motion vector
         */
        fun spatialNewMotionVectorMatch(result: Long): Boolean {
            return (result and (1L shl 33)) != 0L
        }

        /** Appends or reweights one temporal motion-vector candidate.
         * 
         * @param destination the destination candidate array
         * @param count the number of active candidates already stored
         * @param motionVector0 the projected primary vector
         * @param motionVector1 the projected secondary vector, or `null`
         * @param compoundReference whether a secondary vector is required
         * @return the updated candidate count
         */
        fun appendTemporalCandidate(
            destination: Array<ProvisionalMotionVectorCandidate>,
            count: Int,
            motionVector0: MotionVector,
            motionVector1: MotionVector?,
            compoundReference: Boolean
        ): Int {
            var count = count
            if (compoundReference && motionVector1 == null) {
                return count
            }
            val candidate =
                ProvisionalMotionVectorCandidate(
                    2,
                    InterMotionVector.Companion.predicted(motionVector0),
                    if (compoundReference)
                        InterMotionVector.Companion.predicted(
                            requireNotNullCompat(
                                motionVector1,
                                "motionVector1"
                            )
                        )
                    else
                        null,
                    false
                )
            for (i in 0..<count) {
                val existing = destination[i]
                if (!existing.synthetic() && existing.motionVector0().vector == candidate.motionVector0().vector
                    && equivalentMotionVector(existing.motionVector1(), candidate.motionVector1())
                ) {
                    existing.increaseWeight(2)
                    return count
                }
            }
            if (count < destination.size) {
                destination[count++] = candidate
            }
            return count
        }

        /** Returns whether two optional inter motion vectors carry equal vector values.
         * 
         * @param left the first optional motion-vector state
         * @param right the second optional motion-vector state
         * @return whether both values are absent or carry equal vectors
         */
        fun equivalentMotionVector(
            left: InterMotionVector?,
            right: InterMotionVector?
        ): Boolean {
            return if (left == null) right == null else right != null && left.vector == right.vector
        }

        /** Returns whether one projected vector differs materially from the block global vector.
         * 
         * @param projected the projected temporal vector
         * @param global the block global-motion vector
         * @return whether either component differs by at least two luma pixels
         */
        fun differsFromGlobalMotion(
            projected: MotionVector,
            global: MotionVector
        ): Boolean {
            return (abs(projected.columnEighthPel - global.columnEighthPel) or abs(projected.rowEighthPel - global.rowEighthPel)) >= 16
        }

        /** Builds one provisional motion-vector candidate from one stored neighbor.
         * 
         * @param compoundReference whether the current block uses compound references
         * @param referenceFrame0 the primary current-block reference
         * @param referenceFrame1 the secondary current-block reference, or `-1`
         * @param block the stored neighboring block
         * @param globalMotion the current block's global-motion candidates
         * @param weight the provisional candidate weight to assign
         * @return one matching motion-vector candidate, or `null` when the neighbor does not carry the requested reference selection
         */
        fun provisionalMotionVectorCandidate(
            compoundReference: Boolean,
            referenceFrame0: Int,
            referenceFrame1: Int,
            block: StoredBlock,
            globalMotion: ExactSpatialGlobalMotion,
            weight: Int
        ): ProvisionalMotionVectorCandidate? {
            val nonNullBlock: StoredBlock = requireNotNullCompat(block, "block")
            val nonNullGlobalMotion: ExactSpatialGlobalMotion =
                requireNotNullCompat(globalMotion, "globalMotion")
            val replaceGlobalMotion = nonNullBlock.usesGlobalMotionMode()
                    && nonNullBlock.width4() >= 2 && nonNullBlock.height4() >= 2
            if (compoundReference) {
                if (!nonNullBlock.compoundReference() || referenceFrame0 != nonNullBlock.referenceFrame0() || referenceFrame1 != nonNullBlock.referenceFrame1()) {
                    return null
                }
                return ProvisionalMotionVectorCandidate(
                    weight,
                    if (replaceGlobalMotion && nonNullGlobalMotion.primaryNonTranslation)
                        InterMotionVector.Companion.resolved(nonNullGlobalMotion.primary)
                    else
                        nonNullBlock.motionVector0(),
                    if (replaceGlobalMotion && nonNullGlobalMotion.secondaryNonTranslation)
                        InterMotionVector.Companion.resolved(nonNullGlobalMotion.secondary)
                    else
                        nonNullBlock.motionVector1(),
                    false
                )
            }
            if (referenceFrame0 == nonNullBlock.referenceFrame0()) {
                return ProvisionalMotionVectorCandidate(
                    weight,
                    if (replaceGlobalMotion && nonNullGlobalMotion.primaryNonTranslation)
                        InterMotionVector.Companion.resolved(nonNullGlobalMotion.primary)
                    else
                        nonNullBlock.motionVector0(),
                    null,
                    false
                )
            }
            if (nonNullBlock.compoundReference() && referenceFrame0 == nonNullBlock.referenceFrame1()) {
                return ProvisionalMotionVectorCandidate(
                    weight,
                    if (replaceGlobalMotion && nonNullGlobalMotion.primaryNonTranslation)
                        InterMotionVector.Companion.resolved(nonNullGlobalMotion.primary)
                    else
                        nonNullBlock.motionVector1(),
                    null,
                    false
                )
            }
            return null
        }

        /** Appends one provisional candidate or merges its weight into an equivalent candidate.
         * 
         * @param destination the destination candidate array
         * @param count the number of valid candidates currently stored in `destination`
         * @param candidate the base candidate to append
         * @return the updated candidate count
         */
        fun appendProvisionalCandidate(
            destination: Array<ProvisionalMotionVectorCandidate>,
            count: Int,
            candidate: ProvisionalMotionVectorCandidate
        ): Int {
            var count = count
            val nonNullCandidate =
                requireNotNullCompat(candidate, "candidate")
            for (i in 0..<count) {
                val existing = destination[i]
                if (!existing.synthetic() && existing.motionVector0().vector == nonNullCandidate.motionVector0().vector
                    && equivalentMotionVector(
                        existing.motionVector1(),
                        nonNullCandidate.motionVector1()
                    )
                ) {
                    existing.increaseWeight(nonNullCandidate.weight())
                    return count
                }
            }
            if (count < destination.size) {
                destination[count++] = nonNullCandidate
            }
            return count
        }

        /** Sorts a prefix of the supplied provisional candidate array in descending weight order.
         * 
         * Real neighbor-derived candidates win ties over the synthetic zero baseline.
         * 
         * @param values the provisional candidate array to sort
         * @param start the inclusive first candidate to sort
         * @param end the exclusive end candidate index
         */
        fun sortDescending(
            values: Array<ProvisionalMotionVectorCandidate>,
            start: Int,
            end: Int
        ) {
            for (i in start + 1..<end) {
                val current = values[i]
                var j = i - 1
                while (j >= start && compareCandidates(current, values[j]) < 0) {
                    values[j + 1] = values[j]
                    j--
                }
                values[j + 1] = current
            }
        }

        /** Compares two provisional motion-vector candidates for descending sort order.
         * 
         * @param left the first candidate
         * @param right the second candidate
         * @return the comparison result used for descending sort order
         */
        fun compareCandidates(
            left: ProvisionalMotionVectorCandidate,
            right: ProvisionalMotionVectorCandidate
        ): Int {
            val nonNullLeft = requireNotNullCompat(left, "left")
            val nonNullRight =
                requireNotNullCompat(right, "right")
            val weightCompare = nonNullRight.weight().compareTo(nonNullLeft.weight())
            if (weightCompare != 0) {
                return weightCompare
            }
            if (nonNullLeft.synthetic() != nonNullRight.synthetic()) {
                return if (nonNullLeft.synthetic()) 1 else -1
            }
            return 0
        }

        /** Summarizes AV1 spatial-match counts into `refmvs` syntax contexts.
         * 
         * This summary mirrors `dav1d_refmvs_find()`'s `nearest_match` / `ref_match_count` /
         * `have_newmv` handling. Projected temporal candidates do not contribute to these spatial
         * match counts.
         * 
         * @param nearestMatchCount the number of direct top/left matches in `[0, 2]`
         * @param referenceMatchCount the number of spatial row/column match groups in `[0, 2]`
         * @param haveNewMotionVectorMatch whether any matching spatial neighbor used a `NEWMV`-carrying mode
         * @return the summarized AV1 `refmvs` syntax contexts
         */
        fun summarizeDirectRefMvsContexts(
            nearestMatchCount: Int,
            referenceMatchCount: Int,
            haveNewMotionVectorMatch: Boolean
        ): RefMvsContextSummary {
            val refmvContext: Int
            val newmvContext: Int
            when (nearestMatchCount) {
                0 -> {
                    refmvContext = min(2, referenceMatchCount)
                    newmvContext = if (referenceMatchCount > 0) 1 else 0
                }

                1 -> {
                    refmvContext = min(referenceMatchCount * 3, 4)
                    newmvContext = 3 - (if (haveNewMotionVectorMatch) 1 else 0)
                }

                else -> {
                    refmvContext = 5
                    newmvContext = 5 - (if (haveNewMotionVectorMatch) 1 else 0)
                }
            }

            val compoundInterModeContext = when (refmvContext shr 1) {
                0 -> min(newmvContext, 1)
                1 -> 1 + min(newmvContext, 3)
                else -> max(4, min(7, 3 + newmvContext))
            }
            return RefMvsContextSummary(newmvContext, refmvContext, compoundInterModeContext)
        }
    }
}
