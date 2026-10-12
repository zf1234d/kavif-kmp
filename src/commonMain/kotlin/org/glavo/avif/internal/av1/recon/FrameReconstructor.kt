// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.av1.Av1FrameType
import org.glavo.avif.internal.av1.decode.FrameSyntaxDecodeResult
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader.BlockHeader
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader
import org.glavo.avif.internal.av1.decode.TilePartitionTreeReader.PartitionNode
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode
import org.glavo.avif.internal.av1.model.CompoundPredictionType
import org.glavo.avif.internal.av1.model.FilterIntraMode
import org.glavo.avif.internal.av1.model.FrameAssembly
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.FrameHeader.GlobalMotionType
import org.glavo.avif.internal.av1.model.FrameHeader.InterpolationFilter
import org.glavo.avif.internal.av1.model.FrameHeader.QuantizationInfo
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode
import org.glavo.avif.internal.av1.model.InterMotionVector
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode
import org.glavo.avif.internal.av1.model.MotionMode
import org.glavo.avif.internal.av1.model.ResidualLayout
import org.glavo.avif.internal.av1.model.SequenceHeader
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode
import org.glavo.avif.internal.av1.model.TransformLayout
import org.glavo.avif.internal.av1.model.TransformResidualUnit
import org.glavo.avif.internal.av1.model.TransformType
import org.glavo.avif.internal.av1.model.TransformUnit
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode
import org.glavo.avif.internal.av1.recon.WarpedMotion.Sample


/** Reconstructs decoded AV1 frame syntax into luma and chroma sample planes.
 * 
 * Reconstruction supports AV1's 8-, 10-, and 12-bit monochrome and 4:2:0, 4:2:2, and 4:4:4
 * layouts. The pipeline covers intra, inter, compound, inter-intra, `intrabc`, OBMC, warped-motion,
 * palette, residual, and stored-reference prediction paths in the coded frame domain before
 * frame-level filtering and super-resolution are applied by the caller.
 * Instances reuse mutable scratch storage and must not reconstruct frames concurrently. Separate
 * instances have independent storage and may be used concurrently. */
class FrameReconstructor
/** Creates a frame reconstructor with isolated reusable prediction and transform storage.
 * 
 * The storage grows to the largest prediction and transform processed by this instance and is
 * retained for reuse until the reconstructor becomes unreachable. */
{
    /** Scratch storage for separable inter-prediction filtering owned by this reconstructor. */
    private val interPredictionWorkspace = InterPredictionWorkspace()

    /** Intra predictor with scratch storage owned by this reconstructor. */
    private val intraPredictor = IntraPredictor()

    /** Inverse transformer with scratch storage and clip state owned by this reconstructor. */
    private val inverseTransformer = InverseTransformer()

    /** Reusable per-block flags that track applied chroma residual units. */
    private var appliedChromaResiduals = BooleanArray(0)

    /** Tile-local sample boundaries for luma and chroma prediction references.
     * 
     * @param lumaStartX the first luma sample column available to this tile
     * @param lumaStartY the first luma sample row available to this tile
     * @param lumaEndX the exclusive luma sample column available to this tile
     * @param lumaEndY the exclusive luma sample row available to this tile
     * @param chromaStartX the first chroma sample column available to this tile
     * @param chromaStartY the first chroma sample row available to this tile
     * @param chromaEndX the exclusive chroma sample column available to this tile
     * @param chromaEndY the exclusive chroma sample row available to this tile
     */
    data class TileSampleBounds(
        val lumaStartX: Int,
        val lumaStartY: Int,
        val lumaEndX: Int,
        val lumaEndY: Int,
        val chromaStartX: Int,
        val chromaStartY: Int,
        val chromaEndX: Int,
        val chromaEndY: Int
    )

    /** Subsampled luma footprint populated for one CFL block before edge padding.
     * 
     * @param width the populated chroma-domain width
     * @param height the populated chroma-domain height
     */
    data class CflStoredSize(val width: Int, val height: Int)

    /** AV1 fixed-point scale factors for one stored reference surface.
     * 
     * @param horizontalFactor the Q14 horizontal reference scale factor
     * @param verticalFactor the Q14 vertical reference scale factor
     * @param scaled whether either reference-frame dimension differs from the current coded frame
     */
    data class ReferenceScale(
        val horizontalFactor: Int,
        val verticalFactor: Int,
        val scaled: Boolean
    )

    /** Reusable scratch storage for one inter-predicted block. */
    class InterPredictionWorkspace
    /** Creates an empty inter-prediction workspace. */
    {
        /** Edge-extended reference samples reused by unscaled prediction passes. */
        var referenceSamples = ShortArray(0)

        /** Horizontally filtered samples consumed by the vertical pass. */
        var horizontalSamples = IntArray(0)

        /** Primary compound predictor samples retained until blending. */
        var compoundPrediction0 = IntArray(0)

        /** Secondary compound predictor samples retained until blending. */
        var compoundPrediction1 = IntArray(0)

        /** Returns edge-extended reference storage with at least the requested length.
         * 
         * @param requiredLength the minimum number of reference samples
         * @return reusable reference sample storage
         */
        fun referenceSamples(requiredLength: Int): ShortArray {
            if (referenceSamples.size < requiredLength) {
                referenceSamples = ShortArray(requiredLength)
            }
            return referenceSamples
        }

        /** Returns horizontal intermediate storage with at least the requested length.
         * 
         * @param requiredLength the minimum number of intermediate samples
         * @return reusable horizontal intermediate storage
         */
        fun horizontalSamples(requiredLength: Int): IntArray {
            if (horizontalSamples.size < requiredLength) {
                horizontalSamples = IntArray(requiredLength)
            }
            return horizontalSamples
        }

        /** Returns primary compound predictor storage with at least the requested length.
         * 
         * @param requiredLength the minimum number of predictor samples
         * @return reusable primary compound predictor storage
         */
        fun compoundPrediction0(requiredLength: Int): IntArray {
            if (compoundPrediction0.size < requiredLength) {
                compoundPrediction0 = IntArray(requiredLength)
            }
            return compoundPrediction0
        }

        /** Returns secondary compound predictor storage with at least the requested length.
         * 
         * @param requiredLength the minimum number of predictor samples
         * @return reusable secondary compound predictor storage
         */
        fun compoundPrediction1(requiredLength: Int): IntArray {
            if (compoundPrediction1.size < requiredLength) {
                compoundPrediction1 = IntArray(requiredLength)
            }
            return compoundPrediction1
        }
    }

    /** Reconstructs one structural frame result with optional strict transform conformance checks.
     * 
     * @param syntaxDecodeResult the structural frame result to reconstruct
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param strictStdCompliance whether malformed transform values must be rejected
     * @return one decoded-plane snapshot
     * @throws InvalidFrameReconstructionException if strict reconstruction detects a nonconformant value
     */
    /** Reconstructs one supported structural frame result into decoded planes using the supplied
     * stored reference surfaces for inter prediction.
     * 
     * @param syntaxDecodeResult the structural frame result to reconstruct
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @return one decoded-plane snapshot
     */
    /** Reconstructs one supported structural frame result into decoded planes.
     * 
     * @param syntaxDecodeResult the structural frame result to reconstruct
     * @return one decoded-plane snapshot
     */
    fun reconstruct(
        syntaxDecodeResult: FrameSyntaxDecodeResult,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>? = arrayOfNullsCompat<ReferenceSurfaceSnapshot?>(
            0
        ),
        strictStdCompliance: Boolean = false
    ): DecodedSurface {
        return reconstructRegion(
            syntaxDecodeResult,
            referenceSurfaceSnapshots,
            strictStdCompliance,
            -1
        )
    }

    /** Reconstructs one decoded tile into compact tile-local output planes.
     * 
     * The partition syntax and prediction coordinates remain frame-relative, but mutable sample
     * storage is allocated only for the selected tile. Prediction references remain full-frame
     * surfaces supplied by the caller.
     * 
     * @param syntaxDecodeResult the structural frame result containing the decoded tile
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param tileIndex the zero-based tile index to reconstruct
     * @param strictStdCompliance whether malformed transform values must be rejected
     * @return compact decoded planes containing only the selected tile
     * @throws IllegalArgumentException if `tileIndex` is outside the decoded frame's tile range
     * @throws InvalidFrameReconstructionException if strict reconstruction detects a nonconformant value
     */
    fun reconstructTile(
        syntaxDecodeResult: FrameSyntaxDecodeResult,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        tileIndex: Int,
        strictStdCompliance: Boolean
    ): DecodedSurface {
        return reconstructRegion(
            syntaxDecodeResult,
            referenceSurfaceSnapshots,
            strictStdCompliance,
            tileIndex
        )
    }

    /** Reconstructs either a complete frame or one selected tile.
     * 
     * @param syntaxDecodeResult the structural frame result to reconstruct
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param strictStdCompliance whether malformed transform values must be rejected
     * @param selectedTileIndex the selected tile index, or `-1` for the complete frame
     * @return the reconstructed complete-frame or compact tile planes
     */
    private fun reconstructRegion(
        syntaxDecodeResult: FrameSyntaxDecodeResult,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        strictStdCompliance: Boolean,
        selectedTileIndex: Int
    ): DecodedSurface {
        val checkedSyntaxDecodeResult = requireNotNullCompat(
            syntaxDecodeResult,
            "syntaxDecodeResult"
        )
        val checkedReferenceSurfaceSnapshots =
            requireNotNullCompat(
                referenceSurfaceSnapshots,
                "referenceSurfaceSnapshots"
            )
        val assembly = checkedSyntaxDecodeResult.assembly()
        val sequenceHeader = assembly.sequenceHeader()
        val frameHeader = assembly.frameHeader()
        val frameSize = frameHeader.frameSize()
        val chromaFormat = sequenceHeader.colorConfig().chromaFormat

        validateFrameConfiguration(sequenceHeader, frameHeader)
        require(!(selectedTileIndex < -1 || selectedTileIndex >= checkedSyntaxDecodeResult.tileCount())) { "selectedTileIndex out of range: $selectedTileIndex" }

        val alignedLumaWidth = alignedLumaDimension(frameSize.codedWidth())
        val alignedLumaHeight = alignedLumaDimension(frameSize.height())
        val frameBoundaryWidth = alignedFrameBoundaryDimension(frameSize.codedWidth())
        val frameBoundaryHeight = alignedFrameBoundaryDimension(frameSize.height())
        val selectedTileBounds = if (selectedTileIndex >= 0)
            tileSampleBounds(
                assembly,
                selectedTileIndex,
                chromaFormat,
                frameBoundaryWidth,
                frameBoundaryHeight
            )
        else
            null
        val storageStartX = if (selectedTileBounds != null) selectedTileBounds.lumaStartX else 0
        val storageStartY = if (selectedTileBounds != null) selectedTileBounds.lumaStartY else 0
        val storageEndX = if (selectedTileBounds != null)
            paddedTileStorageEnd(selectedTileBounds.lumaEndX, frameBoundaryWidth, alignedLumaWidth)
        else
            alignedLumaWidth
        val storageEndY = if (selectedTileBounds != null)
            paddedTileStorageEnd(
                selectedTileBounds.lumaEndY,
                frameBoundaryHeight,
                alignedLumaHeight
            )
        else
            alignedLumaHeight
        val bitDepth = sequenceHeader.colorConfig().bitDepth.bits()
        val lumaPlane = MutablePlaneBuffer(
            alignedLumaWidth,
            alignedLumaHeight,
            bitDepth,
            storageStartX,
            storageStartY,
            storageEndX - storageStartX,
            storageEndY - storageStartY
        )
        val chromaUPlane = createChromaPlane(
            chromaFormat,
            alignedLumaWidth,
            alignedLumaHeight,
            bitDepth,
            storageStartX,
            storageStartY,
            storageEndX,
            storageEndY
        )
        val chromaVPlane = createChromaPlane(
            chromaFormat,
            alignedLumaWidth,
            alignedLumaHeight,
            bitDepth,
            storageStartX,
            storageStartY,
            storageEndX,
            storageEndY
        )

        val tileRootsByTile = checkedSyntaxDecodeResult.tileRoots()
        val decodedBlockMap = if (selectedTileBounds != null)
            DecodedBlockMap.createRegion(
                tileRootsByTile,
                selectedTileBounds.lumaStartX,
                selectedTileBounds.lumaStartY,
                selectedTileBounds.lumaEndX,
                selectedTileBounds.lumaEndY
            )
        else
            DecodedBlockMap.create(tileRootsByTile, alignedLumaWidth, alignedLumaHeight)
        val firstTileIndex = if (selectedTileIndex >= 0) selectedTileIndex else 0
        val lastTileIndex =
            if (selectedTileIndex >= 0) selectedTileIndex + 1 else tileRootsByTile.size
        for (tileIndex in firstTileIndex..<lastTileIndex) {
            val tileBounds = tileSampleBounds(
                assembly,
                tileIndex,
                chromaFormat,
                frameBoundaryWidth,
                frameBoundaryHeight
            )
            val tileRoots = tileRootsByTile[tileIndex]
            for (root in tileRoots) {
                reconstructNode(
                    root,
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    chromaFormat,
                    frameHeader,
                    sequenceHeader.features().orderHintBits(),
                    sequenceHeader.features().intraEdgeFilter(),
                    checkedReferenceSurfaceSnapshots,
                    decodedBlockMap,
                    tileBounds,
                    strictStdCompliance
                )
            }
        }

        val outputLumaWidth = if (selectedTileBounds != null)
            selectedTileBounds.lumaEndX - selectedTileBounds.lumaStartX
        else
            frameSize.codedWidth()
        val outputLumaHeight = if (selectedTileBounds != null)
            selectedTileBounds.lumaEndY - selectedTileBounds.lumaStartY
        else
            frameSize.height()
        val outputChromaWidth = chromaWidth(chromaFormat, outputLumaWidth)
        val outputChromaHeight = chromaHeight(chromaFormat, outputLumaHeight)
        val decodedLumaPlane = lumaPlane.takeStoredDecodedPlane(outputLumaWidth, outputLumaHeight)
        val decodedChromaUPlane = if (chromaUPlane != null)
            chromaUPlane.takeStoredDecodedPlane(outputChromaWidth, outputChromaHeight)
        else
            null
        val decodedChromaVPlane = if (chromaVPlane != null)
            chromaVPlane.takeStoredDecodedPlane(outputChromaWidth, outputChromaHeight)
        else
            null

        return DecodedSurface(
            bitDepth,
            chromaFormat,
            outputLumaWidth,
            outputLumaHeight,
            if (selectedTileBounds != null) outputLumaWidth else frameSize.renderWidth(),
            if (selectedTileBounds != null) outputLumaHeight else frameSize.renderHeight(),
            decodedLumaPlane,
            decodedChromaUPlane,
            decodedChromaVPlane
        )
    }

    /** Returns the sample boundaries for one frame tile.
     * 
     * @param assembly the frame assembly that owns tile geometry
     * @param tileIndex the zero-based tile index in frame order
     * @param chromaFormat the active decoded chroma layout
     * @param lumaWidth the MI-grid-aligned luma frame width
     * @param lumaHeight the MI-grid-aligned luma frame height
     * @return the sample boundaries for one frame tile
     */
    private fun tileSampleBounds(
        assembly: FrameAssembly,
        tileIndex: Int,
        chromaFormat: Av1ChromaFormat,
        lumaWidth: Int,
        lumaHeight: Int
    ): TileSampleBounds {
        val tiling = assembly.frameHeader().tiling()
        val columns = tiling.columns()
        val tileColumn = tileIndex % columns
        val tileRow = tileIndex / columns
        val superblockSize =
            if (assembly.sequenceHeader().features().use128x128Superblocks()) 128 else 64
        val lumaStartX = min(lumaWidth, tiling.columnStartSuperblock(tileColumn) * superblockSize)
        val lumaStartY = min(lumaHeight, tiling.rowStartSuperblock(tileRow) * superblockSize)
        val lumaEndX = min(lumaWidth, tiling.columnStartSuperblock(tileColumn + 1) * superblockSize)
        val lumaEndY = min(lumaHeight, tiling.rowStartSuperblock(tileRow + 1) * superblockSize)
        return TileSampleBounds(
            lumaStartX,
            lumaStartY,
            lumaEndX,
            lumaEndY,
            chromaWidth(chromaFormat, lumaStartX),
            chromaHeight(chromaFormat, lumaStartY),
            chromaWidth(chromaFormat, lumaEndX),
            chromaHeight(chromaFormat, lumaEndY)
        )
    }

    /** Returns full-frame sample boundaries for callers that reconstruct a standalone tree.
     * 
     * @param chromaFormat the active decoded chroma layout
     * @param lumaWidth the internal luma plane width
     * @param lumaHeight the internal luma plane height
     * @return full-frame sample boundaries for callers that reconstruct a standalone tree
     */
    private fun fullFrameSampleBounds(
        chromaFormat: Av1ChromaFormat,
        lumaWidth: Int,
        lumaHeight: Int
    ): TileSampleBounds {
        return TileSampleBounds(
            0,
            0,
            lumaWidth,
            lumaHeight,
            0,
            0,
            chromaWidth(chromaFormat, lumaWidth),
            chromaHeight(chromaFormat, lumaHeight)
        )
    }

    /** Validates the sequence and frame configuration used for pixel reconstruction.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the active frame header
     */
    private fun validateFrameConfiguration(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader
    ) {
        check(!(sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.MONOCHROME && sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.YUV420 && sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.YUV422 && sequenceHeader.colorConfig().chromaFormat != Av1ChromaFormat.YUV444)) {
            ("Pixel reconstruction requires an MONOCHROME, YUV420, YUV422, or YUV444 chroma format: "
                    + sequenceHeader.colorConfig().chromaFormat)
        }
        if (frameHeader.frameType() != Av1FrameType.KEY && frameHeader.frameType() != Av1FrameType.INTRA) {
            if (frameHeader.frameType() == Av1FrameType.INTER || frameHeader.frameType() == Av1FrameType.SWITCH) {
                return
            }
            throw IllegalStateException(
                "Pixel reconstruction requires a key, intra, inter, or switch frame: "
                        + frameHeader.frameType()
            )
        }
    }

    /** Creates one mutable chroma plane for the supplied chroma format, or `null` for monochrome.
     * 
     * @param chromaFormat the active decoded chroma layout
     * @param alignedLumaWidth the aligned luma-plane width in samples
     * @param alignedLumaHeight the aligned luma-plane height in samples
     * @param bitDepth the decoded sample bit depth
     * @param lumaStorageStartX the inclusive luma storage X boundary
     * @param lumaStorageStartY the inclusive luma storage Y boundary
     * @param lumaStorageEndX the exclusive luma storage X boundary
     * @param lumaStorageEndY the exclusive luma storage Y boundary
     * @return one mutable chroma plane for the supplied chroma format, or `null` for monochrome
     */
    private fun createChromaPlane(
        chromaFormat: Av1ChromaFormat,
        alignedLumaWidth: Int,
        alignedLumaHeight: Int,
        bitDepth: Int,
        lumaStorageStartX: Int,
        lumaStorageStartY: Int,
        lumaStorageEndX: Int,
        lumaStorageEndY: Int
    ): MutablePlaneBuffer? {
        val planeWidth = chromaWidth(chromaFormat, alignedLumaWidth)
        val planeHeight = chromaHeight(chromaFormat, alignedLumaHeight)
        val originX = chromaWidth(chromaFormat, lumaStorageStartX)
        val originY = chromaHeight(chromaFormat, lumaStorageStartY)
        val storageEndX = chromaWidth(chromaFormat, lumaStorageEndX)
        val storageEndY = chromaHeight(chromaFormat, lumaStorageEndY)
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME -> null
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> MutablePlaneBuffer(
                planeWidth,
                planeHeight,
                bitDepth,
                originX,
                originY,
                storageEndX - originX,
                storageEndY - originY
            )
        }
    }

    /** Extends an edge tile's retained storage through reconstruction padding.
     * 
     * @param tileEnd the exclusive tile boundary
     * @param frameBoundary the exclusive MI-grid-aligned frame boundary
     * @param paddedFrameEnd the exclusive reconstruction-buffer boundary
     * @return the retained exclusive storage boundary
     */
    private fun paddedTileStorageEnd(tileEnd: Int, frameBoundary: Int, paddedFrameEnd: Int): Int {
        return if (tileEnd == frameBoundary) paddedFrameEnd else tileEnd
    }

    /** Returns an AV1 luma dimension rounded up far enough to hold a complete edge inter-intra
     * predictor.
     * 
     * @param dimension the uncropped luma dimension in samples
     * @return the dimension rounded up to a multiple of 32 samples
     */
    private fun alignedLumaDimension(dimension: Int): Int {
        return (dimension + INTER_INTRA_MAX_BLOCK_DIMENSION - 1) and -INTER_INTRA_MAX_BLOCK_DIMENSION
    }

    /** Returns the luma boundary represented by the AV1 frame MI grid.
     * 
     * @param dimension the uncropped luma dimension in samples
     * @return the dimension rounded up to the eight-sample MI-grid boundary
     */
    private fun alignedFrameBoundaryDimension(dimension: Int): Int {
        return (dimension + 7) and 7.inv()
    }

    /** Returns the chroma-plane width for one output luma width.
     * 
     * @param chromaFormat the active decoded chroma format
     * @param lumaWidth the output luma width in samples
     * @return the chroma-plane width for the supplied luma width
     */
    private fun chromaWidth(chromaFormat: Av1ChromaFormat, lumaWidth: Int): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> (lumaWidth + 1) shr 1
            Av1ChromaFormat.YUV444 -> lumaWidth
        }
    }

    /** Returns the chroma-plane height for one output luma height.
     * 
     * @param chromaFormat the active decoded chroma format
     * @param lumaHeight the output luma height in samples
     * @return the chroma-plane height for the supplied luma height
     */
    private fun chromaHeight(chromaFormat: Av1ChromaFormat, lumaHeight: Int): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME -> 0
            Av1ChromaFormat.YUV420 -> (lumaHeight + 1) shr 1
            Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> lumaHeight
        }
    }

    /** Chroma plane selector used when reusing the shared OBMC prediction path. */
    enum class ChromaPlane {
        /** Chroma U plane. */
        U,

        /** Chroma V plane. */
        V
    }

    /** Frame-local leaf lookup table indexed in 4x4 units. */
    class DecodedBlockMap(originX4: Int, originY4: Int, width4: Int, height4: Int) {
        /** The horizontal map origin in 4x4 units. */
        val originX4: Int

        /** The vertical map origin in 4x4 units. */
        val originY4: Int

        /** The retained map width in 4x4 units. */
        val width4: Int

        /** The retained map height in 4x4 units. */
        val height4: Int

        /** The leaf nodes indexed by 4x4 position. */
        val leaves: Array<TilePartitionTreeReader.LeafNode>

        /** The partition traversal order of each mapped 4x4 position. */
        val decodeOrders: IntArray

        /** The traversal order assigned to the next leaf. */
        var nextDecodeOrder = 0

        init {
            require(originX4 >= 0) { "originX4 < 0: $originX4" }
            require(originY4 >= 0) { "originY4 < 0: $originY4" }
            require(width4 > 0) { "width4 <= 0: $width4" }
            require(height4 > 0) { "height4 <= 0: $height4" }
            this.originX4 = originX4
            this.originY4 = originY4
            this.width4 = width4
            this.height4 = height4
            this.leaves =
                arrayOfNullsCompat<TilePartitionTreeReader.LeafNode>(multiplyExact(width4, height4))
            this.decodeOrders = IntArray(this.leaves.size)
            fillCompat(this.decodeOrders, -1)
        }

        /** Adds one decoded partition node and all descendant leaves to this map.
         * 
         * @param node the decoded partition node
         */
        fun addNode(node: TilePartitionTreeReader.Node) {
            val nonNullNode = requireNotNullCompat(node, "node")
            if (nonNullNode is TilePartitionTreeReader.LeafNode) {
                addLeaf(nonNullNode)
                return
            }
            val partitionNode = nonNullNode as PartitionNode
            for (childIndex in 0..<partitionNode.childCount()) {
                addNode(partitionNode.child(childIndex))
            }
        }

        /** Adds one decoded leaf to every 4x4 position it covers.
         * 
         * @param leafNode the decoded partition leaf
         */
        fun addLeaf(leafNode: TilePartitionTreeReader.LeafNode) {
            val header = requireNotNullCompat(
                leafNode,
                "leafNode"
            ).header
            val decodeOrder = nextDecodeOrder++
            val endX4 = min(originX4 + width4, header.position().x4 + header.size().width4())
            val endY4 = min(originY4 + height4, header.position().y4 + header.size().height4())
            for (y4 in max(originY4, header.position().y4)..<endY4) {
                for (x4 in max(originX4, header.position().x4)..<endX4) {
                    val index = storageIndex(x4, y4)
                    leaves[index] = leafNode
                    decodeOrders[index] = decodeOrder
                }
            }
        }

        /** Returns the decoded leaf that covers one 4x4 position.
         * 
         * @param x4 the horizontal 4x4 coordinate
         * @param y4 the vertical 4x4 coordinate
         * @return the decoded leaf that covers the position, or `null`
         */
        fun leafAt(x4: Int, y4: Int): TilePartitionTreeReader.LeafNode? {
            if (x4 < originX4 || y4 < originY4 || x4 >= originX4 + width4 || y4 >= originY4 + height4) {
                return null
            }
            return leaves[storageIndex(x4, y4)]
        }

        /** Returns whether one candidate leaf precedes the current leaf in partition traversal.
         * 
         * @param candidate the candidate causal leaf
         * @param current the current leaf
         * @return whether the candidate was decoded before the current leaf
         */
        fun isCausal(
            candidate: TilePartitionTreeReader.LeafNode,
            current: TilePartitionTreeReader.LeafNode
        ): Boolean {
            val candidateHeader = requireNotNullCompat(
                candidate,
                "candidate"
            ).header
            val currentHeader =
                requireNotNullCompat(current, "current").header
            val candidateIndex =
                storageIndex(candidateHeader.position().x4, candidateHeader.position().y4)
            val currentIndex =
                storageIndex(currentHeader.position().x4, currentHeader.position().y4)
            return decodeOrders[candidateIndex] >= 0 && decodeOrders[candidateIndex] < decodeOrders[currentIndex]
        }

        /** Returns the decoded leaf that owns one chroma-grid position.
         * 
         * Subsampled chroma syntax may be attached to only one of several luma leaves sharing the
         * same chroma cell. The search therefore examines the corresponding luma-grid footprint
         * from bottom-right to top-left and returns the leaf that actually carries chroma state.
         * 
         * @param chromaX4 the horizontal coordinate in 4x4 chroma units
         * @param chromaY4 the vertical coordinate in 4x4 chroma units
         * @param chromaSubsamplingX the horizontal chroma subsampling shift
         * @param chromaSubsamplingY the vertical chroma subsampling shift
         * @return the decoded chroma-owning leaf, or `null`
         */
        fun chromaLeafAt(
            chromaX4: Int,
            chromaY4: Int,
            chromaSubsamplingX: Int,
            chromaSubsamplingY: Int
        ): TilePartitionTreeReader.LeafNode? {
            if (chromaX4 < 0 || chromaY4 < 0) {
                return null
            }
            val startX4 = max(originX4, chromaX4 shl chromaSubsamplingX)
            val startY4 = max(originY4, chromaY4 shl chromaSubsamplingY)
            val endX4 = min(originX4 + width4, (chromaX4 + 1) shl chromaSubsamplingX)
            val endY4 = min(originY4 + height4, (chromaY4 + 1) shl chromaSubsamplingY)
            for (y4 in endY4 - 1 downTo startY4) {
                for (x4 in endX4 - 1 downTo startX4) {
                    val leafNode = leafAt(x4, y4)
                    if (leafNode != null && leafNode.header.hasChroma()) {
                        return leafNode
                    }
                }
            }
            return null
        }

        /** Returns the compact map index for one retained frame-relative 4x4 coordinate.
         * 
         * @param x4 the horizontal 4x4 coordinate
         * @param y4 the vertical 4x4 coordinate
         * @return the compact map index
         */
        fun storageIndex(x4: Int, y4: Int): Int {
            val localX4 = x4 - originX4
            val localY4 = y4 - originY4
            if (localX4 < 0 || localX4 >= width4 || localY4 < 0 || localY4 >= height4) {
                throw IndexOutOfBoundsException("Block coordinate outside retained map: $x4, $y4")
            }
            return localY4 * width4 + localX4
        }

        companion object {
            /** Creates one decoded block map from decoded tile partition roots.
             * 
             * @param tileRootsByTile the decoded partition roots grouped by tile
             * @param frameWidth the coded frame width in pixels
             * @param frameHeight the coded frame height in pixels
             * @return one decoded block map from decoded tile partition roots
             */
            fun create(
                tileRootsByTile: Array<Array<TilePartitionTreeReader.Node>>,
                frameWidth: Int,
                frameHeight: Int
            ): DecodedBlockMap {
                return createRegion(tileRootsByTile, 0, 0, frameWidth, frameHeight)
            }

            /** Creates one decoded block map retaining only one frame-relative rectangular region.
             * 
             * @param tileRootsByTile the decoded partition roots grouped by tile
             * @param startX the inclusive horizontal region boundary in pixels
             * @param startY the inclusive vertical region boundary in pixels
             * @param endX the exclusive horizontal region boundary in pixels
             * @param endY the exclusive vertical region boundary in pixels
             * @return one decoded block map for the selected region
             */
            fun createRegion(
                tileRootsByTile: Array<Array<TilePartitionTreeReader.Node>>,
                startX: Int,
                startY: Int,
                endX: Int,
                endY: Int
            ): DecodedBlockMap {
                require(!(startX < 0 || startY < 0 || endX <= startX || endY <= startY)) { "Invalid decoded block map region" }
                val originX4 = startX shr 2
                val originY4 = startY shr 2
                val endX4 = (endX + 3) shr 2
                val endY4 = (endY + 3) shr 2
                val map = DecodedBlockMap(
                    originX4,
                    originY4,
                    endX4 - originX4,
                    endY4 - originY4
                )
                for (tileRoots in requireNotNullCompat(
                    tileRootsByTile,
                    "tileRootsByTile"
                )) {
                    for (root in tileRoots) {
                        map.addNode(root)
                    }
                }
                return map
            }
        }
    }

    /** Recursively reconstructs one partition-tree node using the supplied decoded-block map.
     * 
     * @param node the partition-tree node to reconstruct
     * @param lumaPlane the mutable luma plane
     * @param chromaUPlane the mutable chroma U plane, or `null`
     * @param chromaVPlane the mutable chroma V plane, or `null`
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructNode(
        node: TilePartitionTreeReader.Node,
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        orderHintBits: Int,
        intraEdgeFilterEnabled: Boolean,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds,
        strictStdCompliance: Boolean
    ) {
        if (node is TilePartitionTreeReader.LeafNode) {
            reconstructLeaf(
                node,
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                chromaFormat,
                frameHeader,
                orderHintBits,
                intraEdgeFilterEnabled,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds,
                strictStdCompliance
            )
            return
        }

        val partitionNode = node as PartitionNode
        for (childIndex in 0..<partitionNode.childCount()) {
            reconstructNode(
                partitionNode.child(childIndex),
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                chromaFormat,
                frameHeader,
                orderHintBits,
                intraEdgeFilterEnabled,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds,
                strictStdCompliance
            )
        }
    }

    /** Reconstructs one supported partition-tree leaf.
     * 
     * @param leafNode the partition-tree leaf to reconstruct
     * @param lumaPlane the mutable luma plane
     * @param chromaUPlane the mutable chroma U plane, or `null`
     * @param chromaVPlane the mutable chroma V plane, or `null`
     * @param chromaFormat the active decoded chroma layout
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence
     * @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructLeaf(
        leafNode: TilePartitionTreeReader.LeafNode,
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        orderHintBits: Int,
        intraEdgeFilterEnabled: Boolean,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds,
        strictStdCompliance: Boolean
    ) {
        val header = leafNode.header
        val transformLayout = leafNode.transformLayout
        val residualLayout = leafNode.residualLayout
        validateLeaf(
            header,
            transformLayout,
            residualLayout,
            chromaFormat
        )

        val lumaX = header.position().x4 shl 2
        val lumaY = header.position().y4 shl 2
        var lumaResidualsApplied = false

        if (header.useIntrabc()) {
            reconstructIntrabcPrediction(
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                header,
                transformLayout,
                chromaFormat,
                frameHeader
            )
        } else if (header.intra()) {
            if (header.yPaletteSize() != 0) {
                reconstructLumaPalette(
                    lumaPlane,
                    header,
                    lumaX,
                    lumaY,
                    header.size().widthPixels(),
                    header.size().heightPixels()
                )
            } else if (header.filterIntraMode() != null) {
                reconstructFilterIntraLuma(
                    lumaPlane,
                    residualLayout,
                    header,
                    frameHeader,
                    header.filterIntraMode()!!,
                    tileBounds,
                    strictStdCompliance
                )
                lumaResidualsApplied = true
            } else {
                val smoothEdgeReferences =
                    lumaSmoothEdgeReferences(header, decodedBlockMap, tileBounds)
                reconstructIntraLuma(
                    lumaPlane,
                    residualLayout,
                    header,
                    frameHeader,
                    intraEdgeFilterEnabled,
                    smoothEdgeReferences,
                    tileBounds,
                    strictStdCompliance
                )
                lumaResidualsApplied = true
            }
        } else {
            reconstructInterPrediction(
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                header,
                transformLayout,
                chromaFormat,
                frameHeader,
                orderHintBits,
                referenceSurfaceSnapshots,
                decodedBlockMap,
                tileBounds
            )
        }

        if (!lumaResidualsApplied) {
            reconstructLumaResiduals(
                lumaPlane,
                residualLayout,
                header,
                frameHeader,
                strictStdCompliance
            )
        }

        if (header.hasChroma() && chromaUPlane != null && chromaVPlane != null) {
            val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
            val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
            val chromaX = chromaBlockX(header, chromaSubsamplingX)
            val chromaY = chromaBlockY(header, chromaSubsamplingY)
            val chromaLumaX = chromaLumaBlockX(header, chromaSubsamplingX)
            val chromaLumaY = chromaLumaBlockY(header, chromaSubsamplingY)
            val visibleChromaWidth =
                visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX)
            val visibleChromaHeight =
                visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY)
            val codedChromaWidth = codedChromaBlockWidth(header, chromaSubsamplingX)
            val codedChromaHeight = codedChromaBlockHeight(header, chromaSubsamplingY)
            var chromaResidualsApplied = false
            if (header.intra()) {
                if (header.uvPaletteSize() != 0) {
                    reconstructChromaPalette(
                        chromaUPlane,
                        chromaVPlane,
                        header,
                        chromaFormat,
                        visibleChromaWidth,
                        visibleChromaHeight
                    )
                } else if (header.uvMode() == UvIntraPredictionMode.CFL) {
                    val cflStoredSize = cflStoredSize(
                        transformLayout,
                        chromaLumaX,
                        chromaLumaY,
                        codedChromaWidth,
                        codedChromaHeight,
                        chromaSubsamplingX,
                        chromaSubsamplingY
                    )
                    intraPredictor.predictChromaCfl(
                        chromaUPlane,
                        lumaPlane,
                        chromaX,
                        chromaY,
                        chromaLumaX,
                        chromaLumaY,
                        codedChromaWidth,
                        codedChromaHeight,
                        header.cflAlphaU(),
                        chromaSubsamplingX,
                        chromaSubsamplingY,
                        cflStoredSize.width,
                        cflStoredSize.height,
                        tileBounds.chromaStartX,
                        tileBounds.chromaStartY,
                        tileBounds.chromaEndX,
                        tileBounds.chromaEndY
                    )
                    intraPredictor.predictChromaCfl(
                        chromaVPlane,
                        lumaPlane,
                        chromaX,
                        chromaY,
                        chromaLumaX,
                        chromaLumaY,
                        codedChromaWidth,
                        codedChromaHeight,
                        header.cflAlphaV(),
                        chromaSubsamplingX,
                        chromaSubsamplingY,
                        cflStoredSize.width,
                        cflStoredSize.height,
                        tileBounds.chromaStartX,
                        tileBounds.chromaStartY,
                        tileBounds.chromaEndX,
                        tileBounds.chromaEndY
                    )
                } else {
                    reconstructIntraChroma(
                        chromaUPlane,
                        chromaVPlane,
                        transformLayout,
                        residualLayout,
                        header,
                        frameHeader,
                        chromaFormat,
                        intraEdgeFilterEnabled,
                        chromaSmoothEdgeReferences(
                            header,
                            decodedBlockMap,
                            chromaFormat,
                            tileBounds
                        ),
                        tileBounds,
                        strictStdCompliance
                    )
                    chromaResidualsApplied = true
                }
            }

            if (!chromaResidualsApplied) {
                reconstructChromaResiduals(
                    chromaUPlane,
                    chromaVPlane,
                    residualLayout,
                    frameHeader,
                    chromaFormat,
                    blockQIndex(header, frameHeader),
                    strictStdCompliance
                )
            }
        }
    }

    /** Returns whether the luma intra-edge references adjacent to one block are marked smooth.
     * 
     * @param header the current decoded block header
     * @param decodedBlockMap the decoded leaf map used for causal neighbor lookup
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     * @return whether an adjacent top or left luma edge comes from a smooth intra predictor
     */
    private fun lumaSmoothEdgeReferences(
        header: BlockHeader,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds
    ): Boolean {
        val x4 = header.position().x4
        val y4 = header.position().y4
        val lumaX = x4 shl 2
        val lumaY = y4 shl 2
        return (lumaY > tileBounds.lumaStartY && hasSmoothLumaMode(
            decodedBlockMap.leafAt(
                x4,
                y4 - 1
            )
        ))
                || (lumaX > tileBounds.lumaStartX && hasSmoothLumaMode(
            decodedBlockMap.leafAt(
                x4 - 1,
                y4
            )
        ))
    }

    /** Returns whether the chroma intra-edge references adjacent to one block are marked smooth.
     * 
     * @param header the current decoded block header
     * @param decodedBlockMap the decoded leaf map used for causal neighbor lookup
     * @param chromaFormat the active decoded chroma layout
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     * @return whether an adjacent top or left chroma edge comes from a smooth intra predictor
     */
    private fun chromaSmoothEdgeReferences(
        header: BlockHeader,
        decodedBlockMap: DecodedBlockMap,
        chromaFormat: Av1ChromaFormat,
        tileBounds: TileSampleBounds
    ): Boolean {
        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val chromaX4 = chromaX shr 2
        val chromaY4 = chromaY shr 2
        return (chromaY > tileBounds.chromaStartY
                && hasSmoothChromaMode(
            decodedBlockMap.chromaLeafAt(
                chromaX4,
                chromaY4 - 1,
                chromaSubsamplingX,
                chromaSubsamplingY
            )
        ))
                || (chromaX > tileBounds.chromaStartX
                && hasSmoothChromaMode(
            decodedBlockMap.chromaLeafAt(
                chromaX4 - 1,
                chromaY4,
                chromaSubsamplingX,
                chromaSubsamplingY
            )
        ))
    }

    /** Returns whether one decoded leaf used a smooth luma intra mode.
     * 
     * @param leafNode the decoded neighbor leaf, or `null`
     * @return whether the leaf used a smooth luma intra mode
     */
    private fun hasSmoothLumaMode(leafNode: TilePartitionTreeReader.LeafNode?): Boolean {
        if (leafNode == null || !leafNode.header.intra()) {
            return false
        }
        return isSmoothLumaMode(leafNode.header.yMode())
    }

    /** Returns whether one decoded leaf used a smooth chroma intra mode.
     * 
     * @param leafNode the decoded neighbor leaf, or `null`
     * @return whether the leaf used a smooth chroma intra mode
     */
    private fun hasSmoothChromaMode(leafNode: TilePartitionTreeReader.LeafNode?): Boolean {
        if (leafNode == null || !leafNode.header.intra() || !leafNode.header.hasChroma()) {
            return false
        }
        return isSmoothChromaMode(leafNode.header.uvMode())
    }

    /** Returns whether one luma intra mode is a smooth predictor.
     * 
     * @param mode the luma intra mode, or `null`
     * @return whether the mode is smooth
     */
    private fun isSmoothLumaMode(mode: LumaIntraPredictionMode?): Boolean {
        return mode == LumaIntraPredictionMode.SMOOTH || mode == LumaIntraPredictionMode.SMOOTH_VERTICAL || mode == LumaIntraPredictionMode.SMOOTH_HORIZONTAL
    }

    /** Returns whether one chroma intra mode is a smooth predictor.
     * 
     * @param mode the chroma intra mode, or `null`
     * @return whether the mode is smooth
     */
    private fun isSmoothChromaMode(mode: UvIntraPredictionMode?): Boolean {
        return mode == UvIntraPredictionMode.SMOOTH || mode == UvIntraPredictionMode.SMOOTH_VERTICAL || mode == UvIntraPredictionMode.SMOOTH_HORIZONTAL
    }

    /** Validates one decoded partition-tree leaf before pixel reconstruction.
     * 
     * @param header the decoded block header
     * @param transformLayout the decoded block transform layout
     * @param residualLayout the decoded block residual layout
     * @param chromaFormat the active decoded chroma layout
     */
    private fun validateLeaf(
        header: BlockHeader,
        transformLayout: TransformLayout,
        residualLayout: ResidualLayout,
        chromaFormat: Av1ChromaFormat
    ) {
        if (header.useIntrabc()) {
            check(!(header.motionVector0() == null || !header.motionVector0()!!.resolved)) { "intrabc reconstruction requires one resolved motion vector" }
            check(!header.compoundReference()) { "intrabc reconstruction does not support compound references" }
        }
        check(!(header.hasChroma() && chromaFormat == Av1ChromaFormat.MONOCHROME)) { "Monochrome reconstruction encountered a block with chroma samples" }
        check(!(!header.hasChroma() && residualLayout.hasChromaUnits())) { "Chroma residuals require a block with chroma samples" }
        if (header.hasChroma()) {
            check(!(header.intra() && header.uvPaletteSize() == 0 && header.uvMode() == null)) { "Chroma reconstruction requires uvMode" }
            check(!(residualLayout.hasChromaUnits() && transformLayout.chromaTransformSize() == null)) { "Chroma residuals require a chroma transform size" }
        }
        check(!(transformLayout.visibleWidth4() <= 0 || transformLayout.visibleHeight4() <= 0)) { "Empty transform layout is not reconstructable" }
        if (!header.intra() && !header.useIntrabc()) {
            checkNotNull(header.motionVector0()) { "Inter reconstruction requires one resolved primary motion vector" }
            check(header.motionVector0()!!.resolved) { "Inter reconstruction requires one resolved primary motion vector" }
            if (header.compoundReference()) {
                checkNotNull(header.motionVector1()) { "Compound inter reconstruction requires one resolved secondary motion vector" }
                check(header.motionVector1()!!.resolved) { "Compound inter reconstruction requires one resolved secondary motion vector" }
            }
            check(!(header.yPaletteSize() != 0 || header.uvPaletteSize() != 0)) { "Inter blocks must not carry palette syntax" }
            check(!(header.interIntra() && !InterIntraMasks.supportsInterIntra(header.size()))) { "Inter-intra reconstruction encountered an unsupported block size" }
        }
    }

    /** Reconstructs the inter prediction for one decoded block.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the inter state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used by OBMC neighbor lookup
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     */
    private fun reconstructInterPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        orderHintBits: Int,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds
    ) {
        val frameLumaWidth = frameHeader.frameSize().codedWidth()
        val frameLumaHeight = frameHeader.frameSize().height()
        if (header.compoundReference()) {
            reconstructCompoundInterPrediction(
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                header,
                transformLayout,
                chromaFormat,
                frameHeader,
                frameLumaWidth,
                frameLumaHeight,
                orderHintBits,
                referenceSurfaceSnapshots
            )
        } else {
            if (usesGlobalWarpedPrediction(header, frameHeader)) {
                reconstructGlobalWarpedInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    frameLumaWidth,
                    frameLumaHeight,
                    referenceSurfaceSnapshots,
                    decodedBlockMap
                )
            } else if (header.motionMode() == MotionMode.LOCAL_WARPED) {
                reconstructLocalWarpedInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    frameLumaWidth,
                    frameLumaHeight,
                    referenceSurfaceSnapshots,
                    decodedBlockMap,
                    tileBounds
                )
            } else {
                reconstructSingleReferenceInterPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    frameLumaWidth,
                    frameLumaHeight,
                    referenceSurfaceSnapshots,
                    decodedBlockMap
                )
            }
            if (header.interIntra()) {
                applyInterIntraPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    tileBounds
                )
            }
            if (header.motionMode() == MotionMode.OBMC) {
                applyObmcPrediction(
                    lumaPlane,
                    chromaUPlane,
                    chromaVPlane,
                    header,
                    transformLayout,
                    chromaFormat,
                    frameHeader,
                    referenceSurfaceSnapshots,
                    decodedBlockMap,
                    tileBounds
                )
            }
        }
    }

    /** Applies OBMC blending to a single-reference inter predictor already stored in the output planes.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the OBMC state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used to find causal neighbors
     * @param tileBounds the tile boundaries that constrain causal neighbor lookup
     */
    private fun applyObmcPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds
    ) {
        val lumaX = header.position().x4 shl 2
        val lumaY = header.position().y4 shl 2
        val visibleLumaWidth = transformLayout.visibleWidthPixels()
        val visibleLumaHeight = transformLayout.visibleHeightPixels()
        applyObmcAboveNeighbors(
            lumaPlane,
            null,
            header,
            lumaX,
            lumaY,
            visibleLumaWidth,
            visibleLumaHeight,
            0,
            0,
            frameHeader,
            chromaFormat,
            referenceSurfaceSnapshots,
            decodedBlockMap,
            tileBounds
        )
        applyObmcLeftNeighbors(
            lumaPlane,
            null,
            header,
            lumaX,
            lumaY,
            visibleLumaWidth,
            visibleLumaHeight,
            0,
            0,
            frameHeader,
            chromaFormat,
            referenceSurfaceSnapshots,
            decodedBlockMap,
            tileBounds
        )

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return
        }

        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val visibleChromaWidth =
            visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX)
        val visibleChromaHeight =
            visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY)
        applyObmcAboveNeighbors(
            chromaUPlane,
            ChromaPlane.U,
            header,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            chromaSubsamplingX,
            chromaSubsamplingY,
            frameHeader,
            chromaFormat,
            referenceSurfaceSnapshots,
            decodedBlockMap,
            tileBounds
        )
        applyObmcLeftNeighbors(
            chromaUPlane,
            ChromaPlane.U,
            header,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            chromaSubsamplingX,
            chromaSubsamplingY,
            frameHeader,
            chromaFormat,
            referenceSurfaceSnapshots,
            decodedBlockMap,
            tileBounds
        )
        applyObmcAboveNeighbors(
            chromaVPlane,
            ChromaPlane.V,
            header,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            chromaSubsamplingX,
            chromaSubsamplingY,
            frameHeader,
            chromaFormat,
            referenceSurfaceSnapshots,
            decodedBlockMap,
            tileBounds
        )
        applyObmcLeftNeighbors(
            chromaVPlane,
            ChromaPlane.V,
            header,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            chromaSubsamplingX,
            chromaSubsamplingY,
            frameHeader,
            chromaFormat,
            referenceSurfaceSnapshots,
            decodedBlockMap,
            tileBounds
        )
    }

    /** Applies OBMC blending from already-decoded above neighbors.
     * 
     * @param destinationPlane the mutable destination plane containing the current predictor
     * @param chromaPlane the chroma plane selector, or `null` for luma
     * @param header the decoded block header that owns the OBMC state
     * @param destinationX the plane-local block origin X
     * @param destinationY the plane-local block origin Y
     * @param visibleWidth the visible block width in plane samples
     * @param visibleHeight the visible block height in plane samples
     * @param subsamplingX the horizontal chroma subsampling shift for this plane
     * @param subsamplingY the vertical chroma subsampling shift for this plane
     * @param frameHeader the frame header that owns the block
     * @param chromaFormat the active decoded chroma layout
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used to find causal neighbors
     * @param tileBounds the tile boundaries that constrain causal neighbor lookup
     */
    private fun applyObmcAboveNeighbors(
        destinationPlane: MutablePlaneBuffer,
        chromaPlane: ChromaPlane?,
        header: BlockHeader,
        destinationX: Int,
        destinationY: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        frameHeader: FrameHeader,
        chromaFormat: Av1ChromaFormat,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds
    ) {
        val blockX4 = header.position().x4
        val blockY4 = header.position().y4
        if (blockY4 <= tileBounds.lumaStartY shr 2 || visibleWidth <= 0 || visibleHeight <= 0) {
            return
        }
        if (chromaPlane != null
            && (header.size().width4() * (4 shr subsamplingX)
                    + header.size().height4() * (4 shr subsamplingY) < 16)
        ) {
            return
        }
        val lumaOverlap = min(header.size().heightPixels(), 64) shr 1
        val fullOverlap = max(1, lumaOverlap shr subsamplingY)
        val overlap = min(visibleHeight, fullOverlap)
        val mask = obmcMask(fullOverlap)
        val maxNeighbors = maximumObmcNeighbors(header.size().width4())
        var processed = 0
        val endX4 = blockX4 + header.size().width4()
        var scanX4 = blockX4
        while (scanX4 < endX4 && processed < maxNeighbors) {
            val neighbor = decodedBlockMap.leafAt(scanX4 + 1, blockY4 - 1)
            if (neighbor == null) {
                scanX4 += 2
                continue
            }
            val neighborHeader = neighbor.header
            val step4 = max(2, min(16, neighborHeader.size().width4()))
            if (isObmcNeighbor(neighborHeader)) {
                val relativeLumaX = max(0, scanX4 - blockX4) shl 2
                val planeRelativeX = relativeLumaX shr subsamplingX
                val overlapWidth4 = min(step4, header.size().width4())
                val predictionWidth = max(1, (overlapWidth4 shl 2) shr subsamplingX)
                val planeWidth = min(
                    visibleWidth - planeRelativeX,
                    predictionWidth
                )
                if (planeWidth > 0) {
                    blendObmcRegion(
                        destinationPlane,
                        chromaPlane,
                        neighborHeader,
                        destinationX + planeRelativeX,
                        destinationY,
                        planeWidth,
                        overlap,
                        predictionWidth,
                        fullOverlap,
                        mask,
                        true,
                        frameHeader,
                        chromaFormat,
                        referenceSurfaceSnapshots
                    )
                    processed++
                }
            }
            scanX4 += step4
        }
    }

    /** Applies OBMC blending from already-decoded left neighbors.
     * 
     * @param destinationPlane the mutable destination plane containing the current predictor
     * @param chromaPlane the chroma plane selector, or `null` for luma
     * @param header the decoded block header that owns the OBMC state
     * @param destinationX the plane-local block origin X
     * @param destinationY the plane-local block origin Y
     * @param visibleWidth the visible block width in plane samples
     * @param visibleHeight the visible block height in plane samples
     * @param subsamplingX the horizontal chroma subsampling shift for this plane
     * @param subsamplingY the vertical chroma subsampling shift for this plane
     * @param frameHeader the frame header that owns the block
     * @param chromaFormat the active decoded chroma layout
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used to find causal neighbors
     * @param tileBounds the tile boundaries that constrain causal neighbor lookup
     */
    private fun applyObmcLeftNeighbors(
        destinationPlane: MutablePlaneBuffer,
        chromaPlane: ChromaPlane?,
        header: BlockHeader,
        destinationX: Int,
        destinationY: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        frameHeader: FrameHeader,
        chromaFormat: Av1ChromaFormat,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds
    ) {
        val blockX4 = header.position().x4
        val blockY4 = header.position().y4
        if (blockX4 <= tileBounds.lumaStartX shr 2 || visibleWidth <= 0 || visibleHeight <= 0) {
            return
        }
        val lumaOverlap = min(header.size().widthPixels(), 64) shr 1
        val fullOverlap = max(1, lumaOverlap shr subsamplingX)
        val overlap = min(visibleWidth, fullOverlap)
        val mask = obmcMask(fullOverlap)
        val maxNeighbors = maximumObmcNeighbors(header.size().height4())
        var processed = 0
        val endY4 = blockY4 + header.size().height4()
        var scanY4 = blockY4
        while (scanY4 < endY4 && processed < maxNeighbors) {
            val neighbor = decodedBlockMap.leafAt(blockX4 - 1, scanY4 + 1)
            if (neighbor == null) {
                scanY4 += 2
                continue
            }
            val neighborHeader = neighbor.header
            val step4 = max(2, min(16, neighborHeader.size().height4()))
            if (isObmcNeighbor(neighborHeader)) {
                val relativeLumaY = max(0, scanY4 - blockY4) shl 2
                val planeRelativeY = relativeLumaY shr subsamplingY
                val overlapHeight4 = min(step4, header.size().height4())
                val predictionHeight = max(1, (overlapHeight4 shl 2) shr subsamplingY)
                val planeHeight = min(
                    visibleHeight - planeRelativeY,
                    predictionHeight
                )
                if (planeHeight > 0) {
                    blendObmcRegion(
                        destinationPlane,
                        chromaPlane,
                        neighborHeader,
                        destinationX,
                        destinationY + planeRelativeY,
                        overlap,
                        planeHeight,
                        fullOverlap,
                        predictionHeight,
                        mask,
                        false,
                        frameHeader,
                        chromaFormat,
                        referenceSurfaceSnapshots
                    )
                    processed++
                }
            }
            scanY4 += step4
        }
    }

    /** Blends one OBMC neighbor predictor into a destination region.
     * 
     * @param destinationPlane the mutable destination plane containing the current predictor
     * @param chromaPlane the chroma plane selector, or `null` for luma
     * @param neighborHeader the decoded neighbor block header supplying the primary predictor
     * @param destinationX the plane-local region origin X
     * @param destinationY the plane-local region origin Y
     * @param width the region width in plane samples
     * @param height the region height in plane samples
     * @param predictionWidth the complete neighbor-predictor width used for interpolation-filter selection
     * @param predictionHeight the complete neighbor-predictor height used for interpolation-filter selection
     * @param mask the OBMC mask for the varying axis
     * @param above whether the region is blended from an above neighbor
     * @param frameHeader the frame header that owns the current block
     * @param chromaFormat the active decoded chroma layout
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     */
    private fun blendObmcRegion(
        destinationPlane: MutablePlaneBuffer,
        chromaPlane: ChromaPlane?,
        neighborHeader: BlockHeader,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        predictionWidth: Int,
        predictionHeight: Int,
        mask: IntArray,
        above: Boolean,
        frameHeader: FrameHeader,
        chromaFormat: Av1ChromaFormat,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?
    ) {
        val referenceSurfaceSnapshot = requireReferenceSurfaceSnapshot(
            referenceSurfaceSnapshots,
            frameHeader,
            chromaFormat,
            destinationPlane.bitDepth(),
            neighborHeader.referenceFrame0()
        )
        val referencePlane: PaddedPlane?
        if (chromaPlane == null) {
            referencePlane = referenceSurfaceSnapshot.decodedPlanes.lumaPlane()
        } else if (chromaPlane == ChromaPlane.U) {
            referencePlane = requireNotNullCompat(
                referenceSurfaceSnapshot.decodedPlanes.chromaUPlane(),
                "referencePlanes.chromaUPlane()"
            )
        } else {
            referencePlane = requireNotNullCompat(
                referenceSurfaceSnapshot.decodedPlanes.chromaVPlane(),
                "referencePlanes.chromaVPlane()"
            )
        }
        val motionVector = requireNotNullCompat(
            neighborHeader.motionVector0(),
            "neighborHeader.motionVector0()"
        ).vector
        val denominatorX = if (chromaPlane == null) 8 else 8 shl chromaSubsamplingX(chromaFormat)
        val denominatorY = if (chromaPlane == null) 8 else 8 shl chromaSubsamplingY(chromaFormat)
        val horizontalFilter = resolveHorizontalInterpolationFilter(neighborHeader, frameHeader)
        val verticalFilter = resolveVerticalInterpolationFilter(neighborHeader, frameHeader)
        val referenceScale = referenceScale(
            frameHeader.frameSize().codedWidth(),
            frameHeader.frameSize().height(),
            referenceSurfaceSnapshot
        )
        for (y in 0..<height) {
            for (x in 0..<width) {
                val predictor = sampleInterPlaneValue(
                    referencePlane,
                    destinationX,
                    destinationY,
                    x,
                    y,
                    motionVector.columnEighthPel,
                    motionVector.rowEighthPel,
                    denominatorX,
                    denominatorY,
                    referenceScale,
                    predictionWidth,
                    predictionHeight,
                    horizontalFilter,
                    verticalFilter,
                    destinationPlane.maxSampleValue()
                )
                val current = destinationPlane.sample(destinationX + x, destinationY + y)
                val currentWeight = mask[if (above) y else x]
                destinationPlane.setSample(
                    destinationX + x,
                    destinationY + y,
                    blendMaskedCompoundSamples(predictor, current, currentWeight)
                )
            }
        }
    }

    /** Returns whether one decoded neighbor can provide an OBMC predictor.
     * 
     * @param header the decoded neighbor block header
     * @return whether the neighbor can provide an OBMC predictor
     */
    private fun isObmcNeighbor(header: BlockHeader): Boolean {
        val nonNullHeader = requireNotNullCompat(header, "header")
        return !nonNullHeader.intra() && !nonNullHeader.useIntrabc() && nonNullHeader.referenceFrame0() >= 0 && nonNullHeader.motionVector0() != null
    }

    /** Returns the AV1 OBMC neighbor limit for one axis measured in 4x4 units.
     * 
     * @param size4 the current block axis size in 4x4 units
     * @return the maximum number of causal neighbors to blend on that axis
     */
    private fun maximumObmcNeighbors(size4: Int): Int {
        if (size4 <= 2) {
            return 1
        }
        if (size4 <= 4) {
            return 2
        }
        if (size4 <= 8) {
            return 3
        }
        return 4
    }

    /** Returns the AV1 OBMC mask for the supplied overlap length.
     * 
     * @param length the overlap length in samples
     * @return the AV1 OBMC mask for the supplied overlap length
     */
    private fun obmcMask(length: Int): IntArray {
        return when (length) {
            1 -> OBMC_MASKS[0]
            2 -> OBMC_MASKS[1]
            4 -> OBMC_MASKS[2]
            8 -> OBMC_MASKS[3]
            16 -> OBMC_MASKS[4]
            32 -> OBMC_MASKS[5]
            64 -> OBMC_MASKS[6]
            else -> throw IllegalStateException("Unsupported OBMC overlap length: $length")
        }
    }

    /** Reconstructs an `intrabc` block from already reconstructed samples in the current frame.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the intrabc state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that defines the MI-grid reference boundary
     */
    private fun reconstructIntrabcPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader
    ) {
        val motionVector = requireNotNullCompat(
            header.motionVector0(),
            "header.motionVector0()"
        ).vector
        val frameLumaWidth = alignedFrameBoundaryDimension(frameHeader.frameSize().codedWidth())
        val frameLumaHeight = alignedFrameBoundaryDimension(frameHeader.frameSize().height())
        val lumaX = header.position().x4 shl 2
        val lumaY = header.position().y4 shl 2
        val visibleLumaWidth = transformLayout.visibleWidthPixels()
        val visibleLumaHeight = transformLayout.visibleHeightPixels()
        reconstructIntrabcPlanePrediction(
            lumaPlane,
            frameLumaWidth,
            frameLumaHeight,
            lumaX,
            lumaY,
            visibleLumaWidth,
            visibleLumaHeight,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            8,
            8
        )

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return
        }

        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val visibleChromaWidth =
            visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX)
        val visibleChromaHeight =
            visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY)
        val chromaDenominatorX = 8 shl chromaSubsamplingX
        val chromaDenominatorY = 8 shl chromaSubsamplingY
        val frameChromaWidth = chromaWidth(chromaFormat, frameLumaWidth)
        val frameChromaHeight = chromaHeight(chromaFormat, frameLumaHeight)
        reconstructIntrabcPlanePrediction(
            chromaUPlane,
            frameChromaWidth,
            frameChromaHeight,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            chromaDenominatorX,
            chromaDenominatorY
        )
        reconstructIntrabcPlanePrediction(
            chromaVPlane,
            frameChromaWidth,
            frameChromaHeight,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            chromaDenominatorX,
            chromaDenominatorY
        )
    }

    /** Reconstructs one `intrabc` plane directly from the current mutable reconstruction surface.
     * 
     * Normative displacement-vector validation guarantees that the complete source precedes and
     * does not overlap the destination block, so samples may be read and written in one pass.
     * 
     * @param plane                  the current mutable reconstruction plane
     * @param framePlaneWidth        the coded-frame plane width used for edge extension
     * @param framePlaneHeight       the coded-frame plane height used for edge extension
     * @param destinationX           the zero-based horizontal destination origin
     * @param destinationY           the zero-based vertical destination origin
     * @param width                  the visible prediction width in samples
     * @param height                 the visible prediction height in samples
     * @param sourceOffsetEighthPelX the horizontal displacement in luma eighth-pel units
     * @param sourceOffsetEighthPelY the vertical displacement in luma eighth-pel units
     * @param denominatorX           the plane-local horizontal denominator in luma eighth-pel units
     * @param denominatorY           the plane-local vertical denominator in luma eighth-pel units
     */
    private fun reconstructIntrabcPlanePrediction(
        plane: MutablePlaneBuffer,
        framePlaneWidth: Int,
        framePlaneHeight: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        sourceOffsetEighthPelX: Int,
        sourceOffsetEighthPelY: Int,
        denominatorX: Int,
        denominatorY: Int
    ) {
        for (y in 0..<height) {
            for (x in 0..<width) {
                plane.setSample(
                    destinationX + x,
                    destinationY + y,
                    sampleIntrabcPlaneValue(
                        plane,
                        framePlaneWidth,
                        framePlaneHeight,
                        destinationX + x,
                        destinationY + y,
                        sourceOffsetEighthPelX,
                        sourceOffsetEighthPelY,
                        denominatorX,
                        denominatorY
                    )
                )
            }
        }
    }

    /** Returns one same-frame bilinear predictor sample from the current mutable plane.
     * 
     * @param plane                  the current mutable reconstruction plane
     * @param framePlaneWidth        the coded-frame plane width used for edge extension
     * @param framePlaneHeight       the coded-frame plane height used for edge extension
     * @param destinationX           the zero-based horizontal destination coordinate
     * @param destinationY           the zero-based vertical destination coordinate
     * @param sourceOffsetEighthPelX the horizontal displacement in luma eighth-pel units
     * @param sourceOffsetEighthPelY the vertical displacement in luma eighth-pel units
     * @param denominatorX           the plane-local horizontal denominator in luma eighth-pel units
     * @param denominatorY           the plane-local vertical denominator in luma eighth-pel units
     * @return the predicted sample clipped to the plane bit depth
     */
    private fun sampleIntrabcPlaneValue(
        plane: MutablePlaneBuffer,
        framePlaneWidth: Int,
        framePlaneHeight: Int,
        destinationX: Int,
        destinationY: Int,
        sourceOffsetEighthPelX: Int,
        sourceOffsetEighthPelY: Int,
        denominatorX: Int,
        denominatorY: Int
    ): Int {
        val sourceNumeratorX = destinationX * denominatorX + sourceOffsetEighthPelX
        val sourceNumeratorY = destinationY * denominatorY + sourceOffsetEighthPelY
        val sourceX0 = floorDiv(sourceNumeratorX, denominatorX)
        val sourceY0 = floorDiv(sourceNumeratorY, denominatorY)
        val fractionX =
            interpolationPhase(floorMod(sourceNumeratorX, denominatorX), denominatorX)
        val fractionY =
            interpolationPhase(floorMod(sourceNumeratorY, denominatorY), denominatorY)
        val clampedSourceX0 = clamp(sourceX0, 0, framePlaneWidth - 1)
        val clampedSourceY0 = clamp(sourceY0, 0, framePlaneHeight - 1)
        if (fractionX == 0 && fractionY == 0) {
            return plane.sample(clampedSourceX0, clampedSourceY0)
        }

        val clampedSourceX1 = clamp(sourceX0 + 1, 0, framePlaneWidth - 1)
        val clampedSourceY1 = clamp(sourceY0 + 1, 0, framePlaneHeight - 1)
        val topLeft = plane.sample(clampedSourceX0, clampedSourceY0)
        val topRight = plane.sample(clampedSourceX1, clampedSourceY0)
        val bottomLeft = plane.sample(clampedSourceX0, clampedSourceY1)
        val bottomRight = plane.sample(clampedSourceX1, clampedSourceY1)
        if (fractionY == 0) {
            val intermediateBits = interPredictionIntermediateBits(plane.maxSampleValue())
            val horizontal = roundShift(
                bilinearFilterSum(topLeft, topRight, fractionX).toLong(),
                4 - intermediateBits
            )
            return clamp(
                roundShift(horizontal.toLong(), intermediateBits),
                0,
                plane.maxSampleValue()
            )
        }
        if (fractionX == 0) {
            return clamp(
                roundShift(bilinearFilterSum(topLeft, bottomLeft, fractionY).toLong(), 4),
                0,
                plane.maxSampleValue()
            )
        }

        val intermediateBits = interPredictionIntermediateBits(plane.maxSampleValue())
        val top = roundShift(
            bilinearFilterSum(topLeft, topRight, fractionX).toLong(),
            4 - intermediateBits
        )
        val bottom = roundShift(
            bilinearFilterSum(bottomLeft, bottomRight, fractionX).toLong(),
            4 - intermediateBits
        )
        return clamp(
            roundShift(bilinearFilterSum(top, bottom, fractionY).toLong(), 4 + intermediateBits),
            0,
            plane.maxSampleValue()
        )
    }

    /** Reconstructs single-reference inter prediction for one decoded block.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the inter state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used for sub-8x8 chroma motion derivation
     */
    private fun reconstructSingleReferenceInterPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        frameLumaWidth: Int,
        frameLumaHeight: Int,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap
    ) {
        val referenceSurfaceSnapshot =
            requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                chromaFormat,
                lumaPlane.bitDepth(),
                header.referenceFrame0()
            )
        val referencePlanes = referenceSurfaceSnapshot.decodedPlanes
        val referenceScale =
            referenceScale(frameLumaWidth, frameLumaHeight, referenceSurfaceSnapshot)
        val motionVector = requireNotNullCompat(
            header.motionVector0(),
            "header.motionVector0()"
        ).vector
        val horizontalInterpolationFilter =
            resolveHorizontalInterpolationFilter(header, frameHeader)
        val verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader)
        val lumaX = header.position().x4 shl 2
        val lumaY = header.position().y4 shl 2
        val visibleLumaWidth = transformLayout.visibleWidthPixels()
        val visibleLumaHeight = transformLayout.visibleHeightPixels()
        val lumaBlockWidth = header.size().widthPixels()
        val lumaBlockHeight = header.size().heightPixels()
        reconstructInterPlanePrediction(
            lumaPlane,
            referencePlanes.lumaPlane(),
            frameLumaWidth,
            frameLumaHeight,
            lumaX,
            lumaY,
            visibleLumaWidth,
            visibleLumaHeight,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            8,
            8,
            referenceScale,
            lumaBlockWidth,
            lumaBlockHeight,
            horizontalInterpolationFilter,
            verticalInterpolationFilter
        )
        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return
        }

        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val visibleChromaWidth =
            visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX)
        val visibleChromaHeight =
            visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY)
        val chromaBlockWidth = header.size().widthPixels() shr chromaSubsamplingX
        val chromaBlockHeight = header.size().heightPixels() shr chromaSubsamplingY
        val frameChromaWidth = chromaWidth(chromaFormat, frameLumaWidth)
        val frameChromaHeight = chromaHeight(chromaFormat, frameLumaHeight)
        val chromaDenominatorX = 8 shl chromaSubsamplingX
        val chromaDenominatorY = 8 shl chromaSubsamplingY

        if (reconstructSub8x8ChromaInterPrediction(
                chromaUPlane,
                chromaVPlane,
                header,
                chromaFormat,
                frameHeader,
                frameChromaWidth,
                frameChromaHeight,
                visibleChromaWidth,
                visibleChromaHeight,
                referenceSurfaceSnapshots,
                decodedBlockMap
            )
        ) {
            return
        }

        reconstructInterPlanePrediction(
            chromaUPlane,
            requireNotNullCompat(
                referencePlanes.chromaUPlane(),
                "referencePlanes.chromaUPlane()"
            ),
            frameChromaWidth,
            frameChromaHeight,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            chromaDenominatorX,
            chromaDenominatorY,
            referenceScale,
            chromaBlockWidth,
            chromaBlockHeight,
            horizontalInterpolationFilter,
            verticalInterpolationFilter
        )
        reconstructInterPlanePrediction(
            chromaVPlane,
            requireNotNullCompat(
                referencePlanes.chromaVPlane(),
                "referencePlanes.chromaVPlane()"
            ),
            frameChromaWidth,
            frameChromaHeight,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            chromaDenominatorX,
            chromaDenominatorY,
            referenceScale,
            chromaBlockWidth,
            chromaBlockHeight,
            horizontalInterpolationFilter,
            verticalInterpolationFilter
        )
    }

    /** Reconstructs a shared sub-8x8 chroma footprint from its causal luma-block motion vectors.
     * 
     * AV1 divides an otherwise shared 8x8-luma chroma footprint into two or four prediction
     * regions when every required causal luma block is inter-coded. If any required neighbor
     * cannot supply an inter predictor, the caller must use the current block for the complete
     * chroma footprint instead.
     * 
     * @param chromaUPlane the mutable chroma U destination plane
     * @param chromaVPlane the mutable chroma V destination plane
     * @param header the current decoded block header
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param frameChromaWidth the current frame chroma width in samples
     * @param frameChromaHeight the current frame chroma height in samples
     * @param visibleChromaWidth the visible shared chroma-footprint width
     * @param visibleChromaHeight the visible shared chroma-footprint height
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used to resolve causal luma blocks
     * @return whether sub-8x8 chroma derivation reconstructed the footprint
     */
    private fun reconstructSub8x8ChromaInterPrediction(
        chromaUPlane: MutablePlaneBuffer,
        chromaVPlane: MutablePlaneBuffer,
        header: BlockHeader,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        frameChromaWidth: Int,
        frameChromaHeight: Int,
        visibleChromaWidth: Int,
        visibleChromaHeight: Int,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap
    ): Boolean {
        val subsamplingX = chromaSubsamplingX(chromaFormat)
        val subsamplingY = chromaSubsamplingY(chromaFormat)
        val splitHorizontally = subsamplingX == 1 && header.size().width4() == 1
        val splitVertically = subsamplingY == 1 && header.size().height4() == 1
        if (!splitHorizontally && !splitVertically) {
            return false
        }

        val x4 = header.position().x4
        val y4 = header.position().y4
        val leftHeader = if (splitHorizontally)
            interHeaderAt(decodedBlockMap, x4 - 1, y4)
        else
            null
        val aboveHeader = if (splitVertically)
            interHeaderAt(decodedBlockMap, x4, y4 - 1)
        else
            null
        val aboveLeftHeader = if (splitHorizontally && splitVertically)
            interHeaderAt(decodedBlockMap, x4 - 1, y4 - 1)
        else
            null
        if ((splitHorizontally && leftHeader == null)
            || (splitVertically && aboveHeader == null)
            || (splitHorizontally && splitVertically && aboveLeftHeader == null)
        ) {
            return false
        }

        val chromaX = chromaBlockX(header, subsamplingX)
        val chromaY = chromaBlockY(header, subsamplingY)
        val regionWidth = header.size().width4() * (4 shr subsamplingX)
        val regionHeight = header.size().height4() * (4 shr subsamplingY)
        val currentOffsetX = if (splitHorizontally) regionWidth else 0
        val currentOffsetY = if (splitVertically) regionHeight else 0

        if (aboveLeftHeader != null) {
            reconstructSub8x8ChromaRegion(
                chromaUPlane,
                chromaVPlane,
                aboveLeftHeader,
                chromaFormat,
                frameHeader,
                frameChromaWidth,
                frameChromaHeight,
                chromaX,
                chromaY,
                min(regionWidth, visibleChromaWidth),
                min(regionHeight, visibleChromaHeight),
                referenceSurfaceSnapshots
            )
        }
        if (leftHeader != null) {
            reconstructSub8x8ChromaRegion(
                chromaUPlane,
                chromaVPlane,
                leftHeader,
                chromaFormat,
                frameHeader,
                frameChromaWidth,
                frameChromaHeight,
                chromaX,
                chromaY + currentOffsetY,
                min(regionWidth, visibleChromaWidth),
                min(regionHeight, visibleChromaHeight - currentOffsetY),
                referenceSurfaceSnapshots
            )
        }
        if (aboveHeader != null) {
            reconstructSub8x8ChromaRegion(
                chromaUPlane,
                chromaVPlane,
                aboveHeader,
                chromaFormat,
                frameHeader,
                frameChromaWidth,
                frameChromaHeight,
                chromaX + currentOffsetX,
                chromaY,
                min(regionWidth, visibleChromaWidth - currentOffsetX),
                min(regionHeight, visibleChromaHeight),
                referenceSurfaceSnapshots
            )
        }
        reconstructSub8x8ChromaRegion(
            chromaUPlane,
            chromaVPlane,
            header,
            chromaFormat,
            frameHeader,
            frameChromaWidth,
            frameChromaHeight,
            chromaX + currentOffsetX,
            chromaY + currentOffsetY,
            min(regionWidth, visibleChromaWidth - currentOffsetX),
            min(regionHeight, visibleChromaHeight - currentOffsetY),
            referenceSurfaceSnapshots
        )
        return true
    }

    /** Returns the inter block header covering one decoded luma coordinate, or `null`.
     * 
     * @param decodedBlockMap the decoded leaf map to query
     * @param x4 the tile-relative luma X coordinate in 4x4 units
     * @param y4 the tile-relative luma Y coordinate in 4x4 units
     * @return the usable inter block header, or `null`
     */
    private fun interHeaderAt(
        decodedBlockMap: DecodedBlockMap,
        x4: Int,
        y4: Int
    ): BlockHeader? {
        if (x4 < 0 || y4 < 0) {
            return null
        }
        val leafNode = decodedBlockMap.leafAt(x4, y4)
        if (leafNode == null) {
            return null
        }
        val header = leafNode.header
        return if (isObmcNeighbor(header)) header else null
    }

    /** Reconstructs one sub-8x8 chroma prediction region from a luma block's primary reference.
     * 
     * Empty regions at a cropped frame edge are ignored.
     * 
     * @param chromaUPlane the mutable chroma U destination plane
     * @param chromaVPlane the mutable chroma V destination plane
     * @param sourceHeader the luma block header supplying the reference and motion vector
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param frameChromaWidth the current frame chroma width in samples
     * @param frameChromaHeight the current frame chroma height in samples
     * @param destinationX the chroma-plane destination X coordinate
     * @param destinationY the chroma-plane destination Y coordinate
     * @param width the visible region width in chroma samples
     * @param height the visible region height in chroma samples
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     */
    private fun reconstructSub8x8ChromaRegion(
        chromaUPlane: MutablePlaneBuffer,
        chromaVPlane: MutablePlaneBuffer,
        sourceHeader: BlockHeader,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        frameChromaWidth: Int,
        frameChromaHeight: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?
    ) {
        if (width <= 0 || height <= 0) {
            return
        }
        val referenceSurfaceSnapshot = requireReferenceSurfaceSnapshot(
            referenceSurfaceSnapshots,
            frameHeader,
            chromaFormat,
            chromaUPlane.bitDepth(),
            sourceHeader.referenceFrame0()
        )
        val motionVector = requireNotNullCompat(
            sourceHeader.motionVector0(),
            "sourceHeader.motionVector0()"
        ).vector
        val subsamplingX = chromaSubsamplingX(chromaFormat)
        val subsamplingY = chromaSubsamplingY(chromaFormat)
        val denominatorX = 8 shl subsamplingX
        val denominatorY = 8 shl subsamplingY
        val horizontalFilter = resolveHorizontalInterpolationFilter(sourceHeader, frameHeader)
        val verticalFilter = resolveVerticalInterpolationFilter(sourceHeader, frameHeader)
        val referencePlanes = referenceSurfaceSnapshot.decodedPlanes
        val referenceScale = referenceScale(
            frameHeader.frameSize().codedWidth(),
            frameHeader.frameSize().height(),
            referenceSurfaceSnapshot
        )
        reconstructInterPlanePrediction(
            chromaUPlane,
            requireNotNullCompat(
                referencePlanes.chromaUPlane(),
                "referencePlanes.chromaUPlane()"
            ),
            frameChromaWidth,
            frameChromaHeight,
            destinationX,
            destinationY,
            width,
            height,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            denominatorX,
            denominatorY,
            referenceScale,
            width,
            height,
            horizontalFilter,
            verticalFilter
        )
        reconstructInterPlanePrediction(
            chromaVPlane,
            requireNotNullCompat(
                referencePlanes.chromaVPlane(),
                "referencePlanes.chromaVPlane()"
            ),
            frameChromaWidth,
            frameChromaHeight,
            destinationX,
            destinationY,
            width,
            height,
            motionVector.columnEighthPel,
            motionVector.rowEighthPel,
            denominatorX,
            denominatorY,
            referenceScale,
            width,
            height,
            horizontalFilter,
            verticalFilter
        )
    }

    /** Returns whether a single-reference block uses frame-level affine global warped prediction.
     * 
     * @param header the decoded block header
     * @param frameHeader the frame header that owns the block
     * @return whether the block uses a non-translation global warp
     */
    private fun usesGlobalWarpedPrediction(
        header: BlockHeader,
        frameHeader: FrameHeader
    ): Boolean {
        if (header.singleInterMode() != SingleInterPredictionMode.GLOBALMV || frameHeader.forceIntegerMotionVectors()
            || min(header.size().widthPixels(), header.size().heightPixels()) < 8
        ) {
            return false
        }
        val type = frameHeader.globalMotion(header.referenceFrame0()).type()
        return type == GlobalMotionType.ROTATION_ZOOM || type == GlobalMotionType.AFFINE
    }

    /** Returns the affine global model used by one reference of a compound prediction plane.
     * 
     * Global warped prediction is disabled for non-global compound modes, forced-integer motion,
     * sub-8x8 plane blocks, scaled references, and global models that fail AV1 shear
     * normalization. In those cases the caller must use the decoded block-center global motion
     * vector as an ordinary translation predictor.
     * 
     * @param header the decoded block header
     * @param frameHeader the frame header that owns the block
     * @param referenceFrame the zero-based LAST-through-ALTREF reference position
     * @param referenceScale the current-to-reference frame scale factors
     * @param planeBlockWidth the coded block width in plane samples
     * @param planeBlockHeight the coded block height in plane samples
     * @return the normalized affine model, or `null` when translation prediction is required
     */
    private fun compoundGlobalWarpModel(
        header: BlockHeader,
        frameHeader: FrameHeader,
        referenceFrame: Int,
        referenceScale: ReferenceScale,
        planeBlockWidth: Int,
        planeBlockHeight: Int
    ): WarpedMotion.Model? {
        if (header.compoundInterMode() != CompoundInterPredictionMode.GLOBALMV_GLOBALMV || frameHeader.forceIntegerMotionVectors()
            || planeBlockWidth < 8 || planeBlockHeight < 8 || referenceScale.scaled
        ) {
            return null
        }
        val parameters = frameHeader.globalMotion(referenceFrame)
        if (parameters.type() != GlobalMotionType.ROTATION_ZOOM
            && parameters.type() != GlobalMotionType.AFFINE
        ) {
            return null
        }
        val model = WarpedMotion.fromGlobalMotion(parameters)
        return if (model.affine()) model else null
    }

    /** Reconstructs a single-reference inter predictor using frame-level affine global motion.
     * 
     * Scaled references cannot use warped prediction and fall back to the block-center global
     * motion vector already stored in the decoded block header.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the inter state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param frameLumaWidth the current coded-frame luma width
     * @param frameLumaHeight the current coded-frame luma height
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used by the translation fallback
     */
    private fun reconstructGlobalWarpedInterPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        frameLumaWidth: Int,
        frameLumaHeight: Int,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap
    ) {
        val referenceSurfaceSnapshot = requireReferenceSurfaceSnapshot(
            referenceSurfaceSnapshots,
            frameHeader,
            chromaFormat,
            lumaPlane.bitDepth(),
            header.referenceFrame0()
        )
        val model =
            WarpedMotion.fromGlobalMotion(frameHeader.globalMotion(header.referenceFrame0()))
        if (referenceScale(
                frameLumaWidth,
                frameLumaHeight,
                referenceSurfaceSnapshot
            ).scaled || !model.affine()
        ) {
            reconstructSingleReferenceInterPrediction(
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                header,
                transformLayout,
                chromaFormat,
                frameHeader,
                frameLumaWidth,
                frameLumaHeight,
                referenceSurfaceSnapshots,
                decodedBlockMap
            )
            return
        }
        reconstructWarpedInterPrediction(
            lumaPlane,
            chromaUPlane,
            chromaVPlane,
            header,
            transformLayout,
            chromaFormat,
            frameHeader,
            referenceSurfaceSnapshot.decodedPlanes,
            model
        )
    }

    /** Reconstructs a single-reference inter predictor using an affine local warped motion model.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the inter state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
     * @param tileBounds the tile-local boundaries that constrain causal sample lookup
     */
    private fun reconstructLocalWarpedInterPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        frameLumaWidth: Int,
        frameLumaHeight: Int,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds
    ) {
        val referenceSurfaceSnapshot =
            requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                chromaFormat,
                lumaPlane.bitDepth(),
                header.referenceFrame0()
            )
        val referencePlanes = referenceSurfaceSnapshot.decodedPlanes
        val model = estimateLocalWarpModel(header, decodedBlockMap, tileBounds)
        if (!model.affine()) {
            reconstructSingleReferenceInterPrediction(
                lumaPlane,
                chromaUPlane,
                chromaVPlane,
                header,
                transformLayout,
                chromaFormat,
                frameHeader,
                frameLumaWidth,
                frameLumaHeight,
                referenceSurfaceSnapshots,
                decodedBlockMap
            )
            return
        }
        reconstructWarpedInterPrediction(
            lumaPlane,
            chromaUPlane,
            chromaVPlane,
            header,
            transformLayout,
            chromaFormat,
            frameHeader,
            referencePlanes,
            model
        )
    }

    /** Applies one normalized affine model to all visible planes of a single-reference block.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the inter state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that supplies resolved interpolation filters
     * @param referencePlanes the decoded planes of the selected reference frame
     * @param model the normalized affine warped-motion model
     */
    private fun reconstructWarpedInterPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        referencePlanes: DecodedSurface,
        model: WarpedMotion.Model
    ) {
        val lumaX = header.position().x4 shl 2
        val lumaY = header.position().y4 shl 2
        val visibleLumaWidth = transformLayout.visibleWidthPixels()
        val visibleLumaHeight = transformLayout.visibleHeightPixels()
        WarpedMotion.predictPlane(
            lumaPlane,
            referencePlanes.lumaPlane(),
            lumaX,
            lumaY,
            visibleLumaWidth,
            visibleLumaHeight,
            header.size().widthPixels(),
            header.size().heightPixels(),
            header.position().x4,
            header.position().y4,
            0,
            0,
            model
        )

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return
        }

        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val visibleChromaWidth =
            visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX)
        val visibleChromaHeight =
            visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY)
        val codedChromaWidth = codedChromaBlockWidth(header, chromaSubsamplingX)
        val codedChromaHeight = codedChromaBlockHeight(header, chromaSubsamplingY)
        val referenceChromaUPlane = requireNotNullCompat(
            referencePlanes.chromaUPlane(),
            "referencePlanes.chromaUPlane()"
        )
        val referenceChromaVPlane = requireNotNullCompat(
            referencePlanes.chromaVPlane(),
            "referencePlanes.chromaVPlane()"
        )

        if (codedChromaWidth < 8 || codedChromaHeight < 8) {
            val motionVector = requireNotNullCompat(
                header.motionVector0(),
                "header.motionVector0()"
            ).vector
            val chromaDenominatorX = 8 shl chromaSubsamplingX
            val chromaDenominatorY = 8 shl chromaSubsamplingY
            val horizontalInterpolationFilter =
                resolveHorizontalInterpolationFilter(header, frameHeader)
            val verticalInterpolationFilter =
                resolveVerticalInterpolationFilter(header, frameHeader)
            reconstructInterPlanePrediction(
                chromaUPlane,
                referenceChromaUPlane,
                referenceChromaUPlane.width(),
                referenceChromaUPlane.height(),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnEighthPel,
                motionVector.rowEighthPel,
                chromaDenominatorX,
                chromaDenominatorY,
                IDENTITY_REFERENCE_SCALE,
                codedChromaWidth,
                codedChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
            )
            reconstructInterPlanePrediction(
                chromaVPlane,
                referenceChromaVPlane,
                referenceChromaVPlane.width(),
                referenceChromaVPlane.height(),
                chromaX,
                chromaY,
                visibleChromaWidth,
                visibleChromaHeight,
                motionVector.columnEighthPel,
                motionVector.rowEighthPel,
                chromaDenominatorX,
                chromaDenominatorY,
                IDENTITY_REFERENCE_SCALE,
                codedChromaWidth,
                codedChromaHeight,
                horizontalInterpolationFilter,
                verticalInterpolationFilter
            )
            return
        }

        WarpedMotion.predictPlane(
            chromaUPlane,
            referenceChromaUPlane,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            codedChromaWidth,
            codedChromaHeight,
            header.position().x4,
            header.position().y4,
            chromaSubsamplingX,
            chromaSubsamplingY,
            model
        )
        WarpedMotion.predictPlane(
            chromaVPlane,
            referenceChromaVPlane,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            codedChromaWidth,
            codedChromaHeight,
            header.position().x4,
            header.position().y4,
            chromaSubsamplingX,
            chromaSubsamplingY,
            model
        )
    }

    /** Estimates one local warped affine motion model from causal same-reference neighbors.
     * 
     * @param header the decoded current block header
     * @param decodedBlockMap the decoded leaf map used to find causal local-warp samples
     * @param tileBounds the tile-local boundaries that constrain neighbor lookup
     * @return one local warped affine motion model for the current block
     */
    private fun estimateLocalWarpModel(
        header: BlockHeader,
        decodedBlockMap: DecodedBlockMap,
        tileBounds: TileSampleBounds
    ): WarpedMotion.Model {
        val baseMotionVector = requireNotNullCompat(
            header.motionVector0(),
            "header.motionVector0()"
        ).vector
        val blockX4 = header.position().x4
        val blockY4 = header.position().y4
        val blockWidth4 = header.size().width4()
        val blockHeight4 = header.size().height4()
        val tileStartX4 = tileBounds.lumaStartX shr 2
        val tileStartY4 = tileBounds.lumaStartY shr 2
        val tileEndX4 = tileBounds.lumaEndX shr 2
        val tileEndY4 = tileBounds.lumaEndY shr 2
        val visibleWidth4 = min(blockWidth4, tileEndX4 - blockX4)
        val visibleHeight4 = min(blockHeight4, tileEndY4 - blockY4)
        val currentLeaf = requireNotNullCompat(
            decodedBlockMap.leafAt(blockX4, blockY4),
            "currentLeaf"
        )
        val masks = findLocalWarpSampleMasks(
            header,
            currentLeaf,
            decodedBlockMap,
            blockX4,
            blockY4,
            blockWidth4,
            blockHeight4,
            visibleWidth4,
            visibleHeight4,
            tileStartX4,
            tileStartY4,
            tileEndX4
        )
        val samples: Array<Sample> = arrayOfNullsCompat<Sample>(WarpedMotion.SAMPLE_CAPACITY)
        val sampleCount = collectLocalWarpSamples(
            decodedBlockMap,
            blockX4,
            blockY4,
            blockWidth4,
            masks,
            samples
        )
        return WarpedMotion.derive(
            samples,
            sampleCount,
            blockWidth4,
            blockHeight4,
            baseMotionVector,
            blockX4,
            blockY4
        )
    }

    /** Finds the projectable local-warp neighbor masks in AV1 top/left edge order.
     * 
     * @param currentHeader the current block header
     * @param currentLeaf the current partition leaf
     * @param decodedBlockMap the decoded leaf map
     * @param blockX4 the current block X origin
     * @param blockY4 the current block Y origin
     * @param blockWidth4 the current block width
     * @param blockHeight4 the current block height
     * @param visibleWidth4 the visible block width
     * @param visibleHeight4 the visible block height
     * @param tileStartX4 the tile X origin
     * @param tileStartY4 the tile Y origin
     * @param tileEndX4 the exclusive tile X boundary
     * @return the top and left masks
     */
    private fun findLocalWarpSampleMasks(
        currentHeader: BlockHeader,
        currentLeaf: TilePartitionTreeReader.LeafNode,
        decodedBlockMap: DecodedBlockMap,
        blockX4: Int,
        blockY4: Int,
        blockWidth4: Int,
        blockHeight4: Int,
        visibleWidth4: Int,
        visibleHeight4: Int,
        tileStartX4: Int,
        tileStartY4: Int,
        tileEndX4: Int
    ): LongArray {
        var topMask: Long = 0
        var leftMask: Long = 0
        var count = 0
        val haveTop = blockY4 > tileStartY4
        val haveLeft = blockX4 > tileStartX4
        var haveTopLeft = haveTop && haveLeft
        var haveTopRight = max(blockWidth4, blockHeight4) < 32 && haveTop
                && blockX4 + blockWidth4 < tileEndX4

        if (haveTop) {
            val directTop = requireNotNullCompat(
                decodedBlockMap.leafAt(blockX4, blockY4 - 1),
                "directTop"
            )
            if (isLocalWarpReferenceNeighbor(currentHeader, directTop.header)) {
                topMask = topMask or 1L
                count = 1
            }
            var aboveWidth4 = directTop.header.size().width4()
            if (aboveWidth4 >= blockWidth4) {
                val offset = blockX4 and (aboveWidth4 - 1)
                if (offset != 0) {
                    haveTopLeft = false
                }
                if (aboveWidth4 - offset > blockWidth4) {
                    haveTopRight = false
                }
            } else {
                var mask = 1L shl aboveWidth4
                var x = aboveWidth4
                while (x < visibleWidth4) {
                    val neighbor = requireNotNullCompat(
                        decodedBlockMap.leafAt(blockX4 + x, blockY4 - 1),
                        "topNeighbor"
                    )
                    if (isLocalWarpReferenceNeighbor(currentHeader, neighbor.header)) {
                        topMask = topMask or mask
                        if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                            return longArrayOf(topMask, leftMask)
                        }
                    }
                    aboveWidth4 = neighbor.header.size().width4()
                    x += aboveWidth4
                    mask = mask shl aboveWidth4
                }
            }
        }
        if (haveLeft) {
            val directLeft = requireNotNullCompat(
                decodedBlockMap.leafAt(blockX4 - 1, blockY4),
                "directLeft"
            )
            if (isLocalWarpReferenceNeighbor(currentHeader, directLeft.header)) {
                leftMask = leftMask or 1L
                if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                    return longArrayOf(topMask, leftMask)
                }
            }
            var leftHeight4 = directLeft.header.size().height4()
            if (leftHeight4 >= blockHeight4) {
                if ((blockY4 and (leftHeight4 - 1)) != 0) {
                    haveTopLeft = false
                }
            } else {
                var mask = 1L shl leftHeight4
                var y = leftHeight4
                while (y < visibleHeight4) {
                    val neighbor = requireNotNullCompat(
                        decodedBlockMap.leafAt(blockX4 - 1, blockY4 + y),
                        "leftNeighbor"
                    )
                    if (isLocalWarpReferenceNeighbor(currentHeader, neighbor.header)) {
                        leftMask = leftMask or mask
                        if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                            return longArrayOf(topMask, leftMask)
                        }
                    }
                    leftHeight4 = neighbor.header.size().height4()
                    y += leftHeight4
                    mask = mask shl leftHeight4
                }
            }
        }
        if (haveTopLeft) {
            val topLeft = decodedBlockMap.leafAt(blockX4 - 1, blockY4 - 1)
            if (topLeft != null && isLocalWarpReferenceNeighbor(currentHeader, topLeft.header)) {
                leftMask = leftMask or (1L shl 32)
                if (++count >= WarpedMotion.SAMPLE_CAPACITY) {
                    return longArrayOf(topMask, leftMask)
                }
            }
        }
        if (haveTopRight) {
            val topRight = decodedBlockMap.leafAt(
                blockX4 + blockWidth4,
                blockY4 - 1
            )
            if (topRight != null && decodedBlockMap.isCausal(topRight, currentLeaf)
                && isLocalWarpReferenceNeighbor(currentHeader, topRight.header)
            ) {
                topMask = topMask or (1L shl 32)
            }
        }
        return longArrayOf(topMask, leftMask)
    }

    /** Converts top and left projectable masks into affine motion samples.
     * 
     * @param decodedBlockMap the decoded leaf map
     * @param blockX4 the current block X origin
     * @param blockY4 the current block Y origin
     * @param blockWidth4 the current block width
     * @param masks the top and left projectable masks
     * @param samples the destination sample array
     * @return the populated sample count
     */
    private fun collectLocalWarpSamples(
        decodedBlockMap: DecodedBlockMap,
        blockX4: Int,
        blockY4: Int,
        blockWidth4: Int,
        masks: LongArray,
        samples: Array<Sample>
    ): Int {
        val topMask = masks[0]
        val leftMask = masks[1]
        var count = 0
        if ((topMask and 0xFFFFFFFFL) == 1L && (leftMask ushr 32) == 0L) {
            val neighbor = requireNotNullCompat(
                decodedBlockMap.leafAt(blockX4, blockY4 - 1),
                "directTop"
            ).header
            val offset = blockX4 and (neighbor.size().width4() - 1)
            samples[count++] = localWarpSample(-offset, 0, 1, -1, neighbor)
        } else {
            var offset = 0
            var mask = topMask.toInt()
            while (count < samples.size && mask != 0) {
                val trailingZeros = mask.countTrailingZeroBits()
                offset += trailingZeros
                mask = mask ushr trailingZeros
                val neighbor = requireNotNullCompat(
                    decodedBlockMap.leafAt(blockX4 + offset, blockY4 - 1),
                    "topNeighbor"
                ).header
                samples[count++] = localWarpSample(offset, 0, 1, -1, neighbor)
                mask = mask and 1.inv()
            }
        }
        if (count < samples.size && leftMask == 1L) {
            val directLeft = requireNotNullCompat(
                decodedBlockMap.leafAt(blockX4 - 1, blockY4),
                "directLeft"
            ).header
            val offset = blockY4 and (directLeft.size().height4() - 1)
            val neighbor = requireNotNullCompat(
                decodedBlockMap.leafAt(blockX4 - 1, blockY4 - offset),
                "alignedLeft"
            ).header
            samples[count++] = localWarpSample(0, -offset, -1, 1, neighbor)
        } else {
            var offset = 0
            var mask = leftMask.toInt()
            while (count < samples.size && mask != 0) {
                val trailingZeros = mask.countTrailingZeroBits()
                offset += trailingZeros
                mask = mask ushr trailingZeros
                val neighbor = requireNotNullCompat(
                    decodedBlockMap.leafAt(blockX4 - 1, blockY4 + offset),
                    "leftNeighbor"
                ).header
                samples[count++] = localWarpSample(0, offset, -1, 1, neighbor)
                mask = mask and 1.inv()
            }
        }
        if (count < samples.size && (leftMask ushr 32) != 0L) {
            val neighbor = requireNotNullCompat(
                decodedBlockMap.leafAt(blockX4 - 1, blockY4 - 1),
                "topLeft"
            ).header
            samples[count++] = localWarpSample(0, 0, -1, -1, neighbor)
        }
        if (count < samples.size && (topMask ushr 32) != 0L) {
            val neighbor = requireNotNullCompat(
                decodedBlockMap.leafAt(blockX4 + blockWidth4, blockY4 - 1),
                "topRight"
            ).header
            samples[count++] = localWarpSample(blockWidth4, 0, 1, -1, neighbor)
        }
        check(count != 0) { "Local warped motion requires at least one projectable sample" }
        return count
    }

    /** Creates one affine sample from a causal neighbor block.
     * 
     * @param deltaX4 the neighbor center offset X in 4x4 units
     * @param deltaY4 the neighbor center offset Y in 4x4 units
     * @param widthSign the signed neighbor-width contribution
     * @param heightSign the signed neighbor-height contribution
     * @param neighborHeader the projectable neighbor header
     * @return one affine projection sample
     */
    private fun localWarpSample(
        deltaX4: Int,
        deltaY4: Int,
        widthSign: Int,
        heightSign: Int,
        neighborHeader: BlockHeader
    ): Sample {
        val motionVector = requireNotNullCompat(
            neighborHeader.motionVector0(),
            "neighborHeader.motionVector0()"
        ).vector
        val sourceX = 16 * (2 * deltaX4 + widthSign * neighborHeader.size().width4()) - 8
        val sourceY = 16 * (2 * deltaY4 + heightSign * neighborHeader.size().height4()) - 8
        return Sample(
            sourceX,
            sourceY,
            sourceX + motionVector.columnEighthPel,
            sourceY + motionVector.rowEighthPel
        )
    }

    /** Returns whether one neighbor supplies a compatible single-reference motion vector.
     * 
     * @param currentHeader the current block header
     * @param neighborHeader the candidate neighbor header
     * @return whether the neighbor is projectable for local warped motion
     */
    private fun isLocalWarpReferenceNeighbor(
        currentHeader: BlockHeader,
        neighborHeader: BlockHeader
    ): Boolean {
        val checkedNeighbor = requireNotNullCompat(neighborHeader, "neighborHeader")
        return !checkedNeighbor.intra() && !checkedNeighbor.useIntrabc() && !checkedNeighbor.compoundReference() && !checkedNeighbor.interIntra() && checkedNeighbor.referenceFrame0() == currentHeader.referenceFrame0() && checkedNeighbor.motionVector0() != null
    }

    /** Applies AV1 inter-intra blending to the already built single-reference inter predictor.
     * 
     * @param lumaPlane the mutable luma destination plane containing the inter predictor
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the inter-intra state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     */
    private fun applyInterIntraPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        tileBounds: TileSampleBounds
    ) {
        val mode = requireNotNullCompat(
            header.interIntraMode(),
            "header.interIntraMode()"
        )
        val lumaX = header.position().x4 shl 2
        val lumaY = header.position().y4 shl 2
        val visibleLumaWidth = transformLayout.visibleWidthPixels()
        val visibleLumaHeight = transformLayout.visibleHeightPixels()
        val lumaPredictionWidth = header.size().widthPixels()
        val lumaPredictionHeight = header.size().heightPixels()

        val lumaIntraPlane: MutableSamplePlane = BlockOverlayPlane(
            lumaPlane,
            lumaX,
            lumaY,
            lumaPredictionWidth,
            lumaPredictionHeight
        )
        intraPredictor.predictLuma(
            lumaIntraPlane,
            lumaX,
            lumaY,
            lumaPredictionWidth,
            lumaPredictionHeight,
            mode.toLumaPredictionMode(),
            0,
            false,
            false,
            -1,
            -1,
            tileBounds.lumaStartX,
            tileBounds.lumaStartY,
            tileBounds.lumaEndX,
            tileBounds.lumaEndY
        )
        blendInterIntraPlane(
            lumaPlane,
            lumaIntraPlane,
            header,
            mode,
            lumaX,
            lumaY,
            visibleLumaWidth,
            visibleLumaHeight,
            0,
            0
        )

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return
        }

        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val visibleChromaWidth =
            visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX)
        val visibleChromaHeight =
            visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY)
        val chromaPredictionWidth = header.size().widthPixels() shr chromaSubsamplingX
        val chromaPredictionHeight = header.size().heightPixels() shr chromaSubsamplingY

        val chromaUIntraPlane: MutableSamplePlane = BlockOverlayPlane(
            chromaUPlane,
            chromaX,
            chromaY,
            chromaPredictionWidth,
            chromaPredictionHeight
        )
        intraPredictor.predictChroma(
            chromaUIntraPlane,
            chromaX,
            chromaY,
            chromaPredictionWidth,
            chromaPredictionHeight,
            mode.toUvPredictionMode(),
            0,
            false,
            false,
            -1,
            -1,
            tileBounds.chromaStartX,
            tileBounds.chromaStartY,
            tileBounds.chromaEndX,
            tileBounds.chromaEndY
        )
        blendInterIntraPlane(
            chromaUPlane,
            chromaUIntraPlane,
            header,
            mode,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            chromaSubsamplingX,
            chromaSubsamplingY
        )

        val chromaVIntraPlane: MutableSamplePlane = BlockOverlayPlane(
            chromaVPlane,
            chromaX,
            chromaY,
            chromaPredictionWidth,
            chromaPredictionHeight
        )
        intraPredictor.predictChroma(
            chromaVIntraPlane,
            chromaX,
            chromaY,
            chromaPredictionWidth,
            chromaPredictionHeight,
            mode.toUvPredictionMode(),
            0,
            false,
            false,
            -1,
            -1,
            tileBounds.chromaStartX,
            tileBounds.chromaStartY,
            tileBounds.chromaEndX,
            tileBounds.chromaEndY
        )
        blendInterIntraPlane(
            chromaVPlane,
            chromaVIntraPlane,
            header,
            mode,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            chromaSubsamplingX,
            chromaSubsamplingY
        )
    }

    /** Blends one inter predictor plane with its intra predictor using an AV1 inter-intra mask.
     * 
     * @param destinationPlane the mutable destination plane containing the inter predictor
     * @param intraPlane the mutable intra predictor plane
     * @param header the decoded block header that owns the inter-intra state
     * @param mode the decoded inter-intra prediction mode
     * @param originX the destination-plane block origin X
     * @param originY the destination-plane block origin Y
     * @param width the visible block width in destination-plane samples
     * @param height the visible block height in destination-plane samples
     * @param subsamplingX the horizontal chroma subsampling shift for this plane
     * @param subsamplingY the vertical chroma subsampling shift for this plane
     */
    private fun blendInterIntraPlane(
        destinationPlane: MutablePlaneBuffer,
        intraPlane: MutableSamplePlane,
        header: BlockHeader,
        mode: InterIntraPredictionMode,
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ) {
        for (y in 0..<height) {
            for (x in 0..<width) {
                val mask = InterIntraMasks.maskValue(
                    mode,
                    header.interIntraWedge(),
                    header.interIntraWedgeIndex(),
                    header.size(),
                    x,
                    y,
                    subsamplingX,
                    subsamplingY
                )
                val interSample = destinationPlane.sample(originX + x, originY + y)
                val intraSample = intraPlane.sample(originX + x, originY + y)
                destinationPlane.setSample(
                    originX + x,
                    originY + y,
                    (interSample * (64 - mask) + intraSample * mask + 32) shr 6
                )
            }
        }
    }

    /** Reconstructs compound-reference inter prediction by averaging or masked-blending two
     * independently sampled reference surfaces.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param chromaUPlane the mutable chroma U destination plane, or `null`
     * @param chromaVPlane the mutable chroma V destination plane, or `null`
     * @param header the decoded block header that owns the inter state
     * @param transformLayout the decoded transform layout for the block
     * @param chromaFormat the active decoded chroma layout
     * @param frameHeader the frame header that owns the block
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     */
    private fun reconstructCompoundInterPrediction(
        lumaPlane: MutablePlaneBuffer,
        chromaUPlane: MutablePlaneBuffer?,
        chromaVPlane: MutablePlaneBuffer?,
        header: BlockHeader,
        transformLayout: TransformLayout,
        chromaFormat: Av1ChromaFormat,
        frameHeader: FrameHeader,
        frameLumaWidth: Int,
        frameLumaHeight: Int,
        orderHintBits: Int,
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?
    ) {
        val referenceSurfaceSnapshot0 =
            requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                chromaFormat,
                lumaPlane.bitDepth(),
                header.referenceFrame0()
            )
        val referenceSurfaceSnapshot1 =
            requireReferenceSurfaceSnapshot(
                referenceSurfaceSnapshots,
                frameHeader,
                chromaFormat,
                lumaPlane.bitDepth(),
                header.referenceFrame1()
            )
        val referencePlanes0 = referenceSurfaceSnapshot0.decodedPlanes
        val referencePlanes1 = referenceSurfaceSnapshot1.decodedPlanes
        val referenceScale0 =
            referenceScale(frameLumaWidth, frameLumaHeight, referenceSurfaceSnapshot0)
        val referenceScale1 =
            referenceScale(frameLumaWidth, frameLumaHeight, referenceSurfaceSnapshot1)
        val motionVector0 = requireNotNullCompat(
            header.motionVector0(),
            "header.motionVector0()"
        ).vector
        val motionVector1 = requireNotNullCompat(
            header.motionVector1(),
            "header.motionVector1()"
        ).vector
        val compoundPredictionType =
            requireNotNullCompat(
                header.compoundPredictionType(),
                "header.compoundPredictionType()"
            )
        val jointWeight = if (compoundPredictionType == CompoundPredictionType.WEIGHTED_AVERAGE)
            jointCompoundWeight(
                frameHeader,
                referenceSurfaceSnapshot0.frameHeader,
                referenceSurfaceSnapshot1.frameHeader,
                orderHintBits
            )
        else
            8
        val horizontalInterpolationFilter =
            resolveHorizontalInterpolationFilter(header, frameHeader)
        val verticalInterpolationFilter = resolveVerticalInterpolationFilter(header, frameHeader)
        val lumaX = header.position().x4 shl 2
        val lumaY = header.position().y4 shl 2
        val visibleLumaWidth = transformLayout.visibleWidthPixels()
        val visibleLumaHeight = transformLayout.visibleHeightPixels()
        val lumaBlockWidth = header.size().widthPixels()
        val lumaBlockHeight = header.size().heightPixels()
        val lumaGlobalWarpModel0 = compoundGlobalWarpModel(
            header,
            frameHeader,
            header.referenceFrame0(),
            referenceScale0,
            lumaBlockWidth,
            lumaBlockHeight
        )
        val lumaGlobalWarpModel1 = compoundGlobalWarpModel(
            header,
            frameHeader,
            header.referenceFrame1(),
            referenceScale1,
            lumaBlockWidth,
            lumaBlockHeight
        )
        val segmentMask = if (compoundPredictionType == CompoundPredictionType.SEGMENT)
            IntArray(visibleLumaWidth * visibleLumaHeight)
        else
            null

        reconstructCompoundInterPlanePrediction(
            lumaPlane,
            referencePlanes0.lumaPlane(),
            referencePlanes1.lumaPlane(),
            lumaX,
            lumaY,
            visibleLumaWidth,
            visibleLumaHeight,
            header.position().x4,
            header.position().y4,
            motionVector0.columnEighthPel,
            motionVector0.rowEighthPel,
            motionVector1.columnEighthPel,
            motionVector1.rowEighthPel,
            8,
            8,
            referenceScale0,
            referenceScale1,
            lumaGlobalWarpModel0,
            lumaGlobalWarpModel1,
            lumaBlockWidth,
            lumaBlockHeight,
            horizontalInterpolationFilter,
            verticalInterpolationFilter,
            compoundPredictionType,
            header.compoundMaskSign(),
            header.compoundWedgeIndex(),
            header.size(),
            0,
            0,
            true,
            lumaPlane.bitDepth(),
            jointWeight,
            segmentMask,
            visibleLumaWidth,
            visibleLumaHeight
        )

        if (!header.hasChroma() || chromaUPlane == null || chromaVPlane == null) {
            return
        }

        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val visibleChromaWidth =
            visibleChromaBlockWidth(header, transformLayout, chromaSubsamplingX)
        val visibleChromaHeight =
            visibleChromaBlockHeight(header, transformLayout, chromaSubsamplingY)
        val chromaBlockWidth = header.size().widthPixels() shr chromaSubsamplingX
        val chromaBlockHeight = header.size().heightPixels() shr chromaSubsamplingY
        val chromaDenominatorX = 8 shl chromaSubsamplingX
        val chromaDenominatorY = 8 shl chromaSubsamplingY
        val chromaGlobalWarpModel0 = compoundGlobalWarpModel(
            header,
            frameHeader,
            header.referenceFrame0(),
            referenceScale0,
            chromaBlockWidth,
            chromaBlockHeight
        )
        val chromaGlobalWarpModel1 = compoundGlobalWarpModel(
            header,
            frameHeader,
            header.referenceFrame1(),
            referenceScale1,
            chromaBlockWidth,
            chromaBlockHeight
        )

        reconstructCompoundInterPlanePrediction(
            chromaUPlane,
            requireNotNullCompat(
                referencePlanes0.chromaUPlane(),
                "referencePlanes0.chromaUPlane()"
            ),
            requireNotNullCompat(
                referencePlanes1.chromaUPlane(),
                "referencePlanes1.chromaUPlane()"
            ),
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            header.position().x4,
            header.position().y4,
            motionVector0.columnEighthPel,
            motionVector0.rowEighthPel,
            motionVector1.columnEighthPel,
            motionVector1.rowEighthPel,
            chromaDenominatorX,
            chromaDenominatorY,
            referenceScale0,
            referenceScale1,
            chromaGlobalWarpModel0,
            chromaGlobalWarpModel1,
            chromaBlockWidth,
            chromaBlockHeight,
            horizontalInterpolationFilter,
            verticalInterpolationFilter,
            compoundPredictionType,
            header.compoundMaskSign(),
            header.compoundWedgeIndex(),
            header.size(),
            chromaSubsamplingX,
            chromaSubsamplingY,
            false,
            chromaUPlane.bitDepth(),
            jointWeight,
            segmentMask,
            visibleLumaWidth,
            visibleLumaHeight
        )
        reconstructCompoundInterPlanePrediction(
            chromaVPlane,
            requireNotNullCompat(
                referencePlanes0.chromaVPlane(),
                "referencePlanes0.chromaVPlane()"
            ),
            requireNotNullCompat(
                referencePlanes1.chromaVPlane(),
                "referencePlanes1.chromaVPlane()"
            ),
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            header.position().x4,
            header.position().y4,
            motionVector0.columnEighthPel,
            motionVector0.rowEighthPel,
            motionVector1.columnEighthPel,
            motionVector1.rowEighthPel,
            chromaDenominatorX,
            chromaDenominatorY,
            referenceScale0,
            referenceScale1,
            chromaGlobalWarpModel0,
            chromaGlobalWarpModel1,
            chromaBlockWidth,
            chromaBlockHeight,
            horizontalInterpolationFilter,
            verticalInterpolationFilter,
            compoundPredictionType,
            header.compoundMaskSign(),
            header.compoundWedgeIndex(),
            header.size(),
            chromaSubsamplingX,
            chromaSubsamplingY,
            false,
            chromaVPlane.bitDepth(),
            jointWeight,
            segmentMask,
            visibleLumaWidth,
            visibleLumaHeight
        )
    }

    /** Reconstructs one inter-predicted plane using AV1 reference scaling and subpel filtering.
     * 
     * Same-size integer-aligned predictions use a direct copy. Differently sized reference frames
     * use the normative Q14 scale factors and Q10 per-sample stepping before interpolation.
     * 
     * @param destinationPlane the mutable destination plane
     * @param referencePlane the immutable reference plane
     * @param framePlaneWidth the current coded-frame width in samples for this plane
     * @param framePlaneHeight the current coded-frame height in samples for this plane
     * @param destinationX the zero-based horizontal destination coordinate
     * @param destinationY the zero-based vertical destination coordinate
     * @param width the copied width in samples
     * @param height the copied height in samples
     * @param sourceOffsetEighthPelX the signed horizontal motion-vector component in luma eighth-pel units
     * @param sourceOffsetEighthPelY the signed vertical motion-vector component in luma eighth-pel units
     * @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
     * @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
     * @param referenceScale the scale factors derived from the current and reference luma dimensions
     * @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
     * @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
     * @param horizontalFilterMode the effective horizontal interpolation filter mode
     * @param verticalFilterMode the effective vertical interpolation filter mode
     */
    private fun reconstructInterPlanePrediction(
        destinationPlane: MutablePlaneBuffer,
        referencePlane: PaddedPlane,
        framePlaneWidth: Int,
        framePlaneHeight: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        sourceOffsetEighthPelX: Int,
        sourceOffsetEighthPelY: Int,
        denominatorX: Int,
        denominatorY: Int,
        referenceScale: ReferenceScale,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter
    ) {
        val nonNullReferenceScale: ReferenceScale =
            requireNotNullCompat(referenceScale, "referenceScale")
        if (!nonNullReferenceScale.scaled && framePlaneWidth == referencePlane.width() && framePlaneHeight == referencePlane.height() && floorMod(
                sourceOffsetEighthPelX,
                denominatorX
            ) == 0 && floorMod(sourceOffsetEighthPelY, denominatorY) == 0
        ) {
            copyReferencePlaneBlock(
                destinationPlane,
                referencePlane,
                destinationX,
                destinationY,
                destinationX + sourceOffsetEighthPelX / denominatorX,
                destinationY + sourceOffsetEighthPelY / denominatorY,
                width,
                height
            )
            return
        }

        if (!nonNullReferenceScale.scaled) {
            reconstructUnscaledInterPlanePrediction(
                destinationPlane,
                referencePlane,
                destinationX,
                destinationY,
                width,
                height,
                sourceOffsetEighthPelX,
                sourceOffsetEighthPelY,
                denominatorX,
                denominatorY,
                widthForFilterSelection,
                heightForFilterSelection,
                horizontalFilterMode,
                verticalFilterMode
            )
            return
        }

        for (y in 0..<height) {
            for (x in 0..<width) {
                destinationPlane.setSample(
                    destinationX + x,
                    destinationY + y,
                    sampleInterPlaneValue(
                        referencePlane,
                        destinationX,
                        destinationY,
                        x,
                        y,
                        sourceOffsetEighthPelX,
                        sourceOffsetEighthPelY,
                        denominatorX,
                        denominatorY,
                        nonNullReferenceScale,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        horizontalFilterMode,
                        verticalFilterMode,
                        destinationPlane.maxSampleValue()
                    )
                )
            }
        }
    }

    /** Reconstructs one unscaled inter-predicted plane with block-level separable filtering.
     * 
     * Motion-vector fractions and filter kernels are constant across an unscaled block. For a
     * two-dimensional fixed filter, the horizontal pass is therefore computed once for each
     * required source row and reused by all eight vertical taps.
     * 
     * @param destinationPlane the mutable destination plane
     * @param referencePlane the immutable reference plane
     * @param destinationX the zero-based horizontal destination coordinate
     * @param destinationY the zero-based vertical destination coordinate
     * @param width the predicted width in samples
     * @param height the predicted height in samples
     * @param sourceOffsetEighthPelX the signed horizontal motion-vector component
     * @param sourceOffsetEighthPelY the signed vertical motion-vector component
     * @param denominatorX the plane-local horizontal denominator
     * @param denominatorY the plane-local vertical denominator
     * @param widthForFilterSelection the sampled block width used for filter selection
     * @param heightForFilterSelection the sampled block height used for filter selection
     * @param horizontalFilterMode the effective horizontal interpolation filter
     * @param verticalFilterMode the effective vertical interpolation filter
     */
    private fun reconstructUnscaledInterPlanePrediction(
        destinationPlane: MutablePlaneBuffer,
        referencePlane: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        sourceOffsetEighthPelX: Int,
        sourceOffsetEighthPelY: Int,
        denominatorX: Int,
        denominatorY: Int,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter
    ) {
        val sourceX0 = destinationX + floorDiv(sourceOffsetEighthPelX, denominatorX)
        val sourceY0 = destinationY + floorDiv(sourceOffsetEighthPelY, denominatorY)
        val phaseX =
            interpolationPhase(floorMod(sourceOffsetEighthPelX, denominatorX), denominatorX)
        val phaseY =
            interpolationPhase(floorMod(sourceOffsetEighthPelY, denominatorY), denominatorY)
        if (phaseX == 0 && phaseY == 0) {
            copyReferencePlaneBlock(
                destinationPlane,
                referencePlane,
                destinationX,
                destinationY,
                sourceX0,
                sourceY0,
                width,
                height
            )
            return
        }
        if (horizontalFilterMode == InterpolationFilter.BILINEAR
            && verticalFilterMode == InterpolationFilter.BILINEAR
        ) {
            reconstructUnscaledBilinearInterPlanePrediction(
                destinationPlane,
                referencePlane,
                destinationX,
                destinationY,
                sourceX0,
                sourceY0,
                width,
                height,
                phaseX,
                phaseY
            )
            return
        }
        check(
            !(!isConcreteInterpolationFilter(horizontalFilterMode) || !isConcreteInterpolationFilter(
                verticalFilterMode
            ) || horizontalFilterMode == InterpolationFilter.BILINEAR || verticalFilterMode == InterpolationFilter.BILINEAR)
        ) { "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters" }

        val horizontalFilter = if (phaseX == 0)
            null
        else
            selectSubpelFilter(horizontalFilterMode, phaseX, widthForFilterSelection)
        val verticalFilter = if (phaseY == 0)
            null
        else
            selectSubpelFilter(verticalFilterMode, phaseY, heightForFilterSelection)
        val maximumSampleValue = destinationPlane.maxSampleValue()
        val intermediateBits = interPredictionIntermediateBits(maximumSampleValue)
        if (verticalFilter == null) {
            val checkedHorizontalFilter =
                requireNotNullCompat(horizontalFilter, "horizontalFilter")
            val referenceStride: Int = width + INTER_FILTER_TAP_COUNT - 1
            val referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0 - INTER_FILTER_START_OFFSET,
                sourceY0,
                referenceStride,
                height
            )
            for (y in 0..<height) {
                val referenceRowOffset = y * referenceStride
                for (x in 0..<width) {
                    var filtered: Long = 0
                    for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                        filtered += checkedHorizontalFilter[tapIndex].toLong() * (referenceSamples[referenceRowOffset + x + tapIndex].toInt() and 0xFFFF)
                    }
                    val intermediate = roundShift(filtered, INTER_FILTER_BITS - intermediateBits)
                    destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        clamp(
                            roundShift(intermediate.toLong(), intermediateBits),
                            0,
                            maximumSampleValue
                        )
                    )
                }
            }
            return
        }
        if (horizontalFilter == null) {
            val referenceRowCount: Int = height + INTER_FILTER_TAP_COUNT - 1
            val referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0,
                sourceY0 - INTER_FILTER_START_OFFSET,
                width,
                referenceRowCount
            )
            for (y in 0..<height) {
                for (x in 0..<width) {
                    var filtered: Long = 0
                    for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                        filtered += verticalFilter[tapIndex].toLong() * (referenceSamples[(y + tapIndex) * width + x].toInt() and 0xFFFF)
                    }
                    destinationPlane.setSample(
                        destinationX + x,
                        destinationY + y,
                        clamp(
                            roundShift(filtered, INTER_FILTER_BITS),
                            0,
                            maximumSampleValue
                        )
                    )
                }
            }
            return
        }

        val horizontalRowCount: Int = height + INTER_FILTER_TAP_COUNT - 1
        val horizontalSamples = interPredictionWorkspace
            .horizontalSamples(width * horizontalRowCount)
        val referenceStride: Int = width + INTER_FILTER_TAP_COUNT - 1
        val referenceSamples = copyExtendedReferenceRegion(
            referencePlane,
            sourceX0 - INTER_FILTER_START_OFFSET,
            sourceY0 - INTER_FILTER_START_OFFSET,
            referenceStride,
            horizontalRowCount
        )
        val firstPassRound: Int = INTER_FILTER_BITS - intermediateBits
        for (row in 0..<horizontalRowCount) {
            val rowOffset = row * width
            val referenceRowOffset = row * referenceStride
            for (x in 0..<width) {
                var filtered: Long = 0
                for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                    filtered += horizontalFilter[tapIndex].toLong() * (referenceSamples[referenceRowOffset + x + tapIndex].toInt() and 0xFFFF)
                }
                horizontalSamples[rowOffset + x] = roundShift(filtered, firstPassRound)
            }
        }
        for (y in 0..<height) {
            for (x in 0..<width) {
                var combined: Long = 0
                for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                    combined += verticalFilter[tapIndex].toLong() * horizontalSamples[(y + tapIndex) * width + x]
                }
                destinationPlane.setSample(
                    destinationX + x,
                    destinationY + y,
                    clamp(
                        roundShift(combined, INTER_FILTER_BITS + intermediateBits),
                        0,
                        maximumSampleValue
                    )
                )
            }
        }
    }

    /** Reconstructs one unscaled bilinear inter-predicted plane from constant block phases.
     * 
     * @param destinationPlane the mutable destination plane
     * @param referencePlane the immutable reference plane
     * @param destinationX the zero-based horizontal destination coordinate
     * @param destinationY the zero-based vertical destination coordinate
     * @param sourceX0 the integer horizontal source origin
     * @param sourceY0 the integer vertical source origin
     * @param width the predicted width in samples
     * @param height the predicted height in samples
     * @param phaseX the horizontal interpolation phase in `[0, 15]`
     * @param phaseY the vertical interpolation phase in `[0, 15]`
     */
    private fun reconstructUnscaledBilinearInterPlanePrediction(
        destinationPlane: MutablePlaneBuffer,
        referencePlane: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        sourceX0: Int,
        sourceY0: Int,
        width: Int,
        height: Int,
        phaseX: Int,
        phaseY: Int
    ) {
        val maximumSampleValue = destinationPlane.maxSampleValue()
        val intermediateBits = interPredictionIntermediateBits(maximumSampleValue)
        val referenceStride = if (phaseX == 0) width else width + 1
        val referenceRowCount = if (phaseY == 0) height else height + 1
        val referenceSamples = copyExtendedReferenceRegion(
            referencePlane,
            sourceX0,
            sourceY0,
            referenceStride,
            referenceRowCount
        )
        for (y in 0..<height) {
            val topRowOffset = y * referenceStride
            val bottomRowOffset = (y + 1) * referenceStride
            for (x in 0..<width) {
                val topLeft = referenceSamples[topRowOffset + x].toInt() and 0xFFFF
                val sample: Int
                if (phaseY == 0) {
                    val topRight = referenceSamples[topRowOffset + x + 1].toInt() and 0xFFFF
                    val horizontal = roundShift(
                        bilinearFilterSum(topLeft, topRight, phaseX).toLong(),
                        4 - intermediateBits
                    )
                    sample = roundShift(horizontal.toLong(), intermediateBits)
                } else if (phaseX == 0) {
                    val bottomLeft = referenceSamples[bottomRowOffset + x].toInt() and 0xFFFF
                    sample = roundShift(bilinearFilterSum(topLeft, bottomLeft, phaseY).toLong(), 4)
                } else {
                    val topRight = referenceSamples[topRowOffset + x + 1].toInt() and 0xFFFF
                    val bottomLeft = referenceSamples[bottomRowOffset + x].toInt() and 0xFFFF
                    val bottomRight = referenceSamples[bottomRowOffset + x + 1].toInt() and 0xFFFF
                    val top = roundShift(
                        bilinearFilterSum(topLeft, topRight, phaseX).toLong(),
                        4 - intermediateBits
                    )
                    val bottom = roundShift(
                        bilinearFilterSum(bottomLeft, bottomRight, phaseX).toLong(),
                        4 - intermediateBits
                    )
                    sample = roundShift(
                        bilinearFilterSum(top, bottom, phaseY).toLong(),
                        4 + intermediateBits
                    )
                }
                destinationPlane.setSample(
                    destinationX + x,
                    destinationY + y,
                    clamp(sample, 0, maximumSampleValue)
                )
            }
        }
    }

    /** Reconstructs one compound inter-predicted plane by blending two independently predicted
     * reference planes.
     * 
     * @param destinationPlane the mutable destination plane
     * @param referencePlane0 the primary immutable reference plane
     * @param referencePlane1 the secondary immutable reference plane
     * @param destinationX the zero-based horizontal destination coordinate
     * @param destinationY the zero-based vertical destination coordinate
     * @param width the copied width in samples
     * @param height the copied height in samples
     * @param blockX4 the luma block X origin in 4x4 units
     * @param blockY4 the luma block Y origin in 4x4 units
     * @param sourceOffsetEighthPelX0 the primary signed horizontal motion-vector component in luma eighth-pel units
     * @param sourceOffsetEighthPelY0 the primary signed vertical motion-vector component in luma eighth-pel units
     * @param sourceOffsetEighthPelX1 the secondary signed horizontal motion-vector component in luma eighth-pel units
     * @param sourceOffsetEighthPelY1 the secondary signed vertical motion-vector component in luma eighth-pel units
     * @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
     * @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
     * @param referenceScale0 the primary reference scale factors
     * @param referenceScale1 the secondary reference scale factors
     * @param globalWarpModel0 the primary affine global model, or `null` for translation prediction
     * @param globalWarpModel1 the secondary affine global model, or `null` for translation prediction
     * @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
     * @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
     * @param horizontalFilterMode the effective horizontal interpolation filter mode
     * @param verticalFilterMode the effective vertical interpolation filter mode
     * @param compoundPredictionType the decoded compound prediction blend type
     * @param maskSign whether the decoded segment or wedge mask uses inverted source order
     * @param wedgeIndex the decoded compound wedge index, or `-1`
     * @param blockSize the luma block size that owns this plane prediction
     * @param subsamplingX the horizontal chroma subsampling shift for this plane
     * @param subsamplingY the vertical chroma subsampling shift for this plane
     * @param lumaPlane whether this invocation reconstructs the luma plane and must populate the segment mask
     * @param bitDepth the decoded sample bit depth
     * @param jointWeight the decoded joint compound weight for weighted average prediction
     * @param segmentMask the luma-domain segment mask to populate or reuse, or `null`
     * @param segmentMaskWidth the luma-domain segment mask width
     * @param segmentMaskHeight the luma-domain segment mask height
     */
    private fun reconstructCompoundInterPlanePrediction(
        destinationPlane: MutablePlaneBuffer,
        referencePlane0: PaddedPlane,
        referencePlane1: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        blockX4: Int,
        blockY4: Int,
        sourceOffsetEighthPelX0: Int,
        sourceOffsetEighthPelY0: Int,
        sourceOffsetEighthPelX1: Int,
        sourceOffsetEighthPelY1: Int,
        denominatorX: Int,
        denominatorY: Int,
        referenceScale0: ReferenceScale,
        referenceScale1: ReferenceScale,
        globalWarpModel0: WarpedMotion.Model?,
        globalWarpModel1: WarpedMotion.Model?,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter,
        compoundPredictionType: CompoundPredictionType,
        maskSign: Boolean,
        wedgeIndex: Int,
        blockSize: BlockSize,
        subsamplingX: Int,
        subsamplingY: Int,
        lumaPlane: Boolean,
        bitDepth: Int,
        jointWeight: Int,
        segmentMask: IntArray?,
        segmentMaskWidth: Int,
        segmentMaskHeight: Int
    ) {
        val nonNullCompoundPredictionType =
            requireNotNullCompat(
                compoundPredictionType,
                "compoundPredictionType"
            )
        val nonNullReferenceScale0: ReferenceScale =
            requireNotNullCompat(referenceScale0, "referenceScale0")
        val nonNullReferenceScale1: ReferenceScale =
            requireNotNullCompat(referenceScale1, "referenceScale1")
        val warpedPrediction0 = if (globalWarpModel0 == null)
            null
        else
            WarpedMotion.predictCompoundPlane(
                referencePlane0,
                width,
                height,
                widthForFilterSelection,
                heightForFilterSelection,
                blockX4,
                blockY4,
                subsamplingX,
                subsamplingY,
                bitDepth,
                globalWarpModel0
            )
        val warpedPrediction1 = if (globalWarpModel1 == null)
            null
        else
            WarpedMotion.predictCompoundPlane(
                referencePlane1,
                width,
                height,
                widthForFilterSelection,
                heightForFilterSelection,
                blockX4,
                blockY4,
                subsamplingX,
                subsamplingY,
                bitDepth,
                globalWarpModel1
            )
        val maximumSampleValue = destinationPlane.maxSampleValue()
        val postRoundBits = interPredictionIntermediateBits(maximumSampleValue)
        val predictionLength = width * height
        val useUnscaledPrediction0 = warpedPrediction0 == null && !nonNullReferenceScale0.scaled
        val useUnscaledPrediction1 = warpedPrediction1 == null && !nonNullReferenceScale1.scaled
        val workspace = if (useUnscaledPrediction0 || useUnscaledPrediction1)
            interPredictionWorkspace
        else
            null
        var prediction0 = warpedPrediction0
        if (useUnscaledPrediction0) {
            prediction0 = requireNotNullCompat(workspace, "workspace")
                .compoundPrediction0(predictionLength)
            reconstructUnscaledCompoundInterPlanePrediction(
                referencePlane0,
                destinationX,
                destinationY,
                width,
                height,
                sourceOffsetEighthPelX0,
                sourceOffsetEighthPelY0,
                denominatorX,
                denominatorY,
                widthForFilterSelection,
                heightForFilterSelection,
                horizontalFilterMode,
                verticalFilterMode,
                maximumSampleValue,
                prediction0
            )
        }
        var prediction1 = warpedPrediction1
        if (useUnscaledPrediction1) {
            prediction1 = requireNotNullCompat(workspace, "workspace")
                .compoundPrediction1(predictionLength)
            reconstructUnscaledCompoundInterPlanePrediction(
                referencePlane1,
                destinationX,
                destinationY,
                width,
                height,
                sourceOffsetEighthPelX1,
                sourceOffsetEighthPelY1,
                denominatorX,
                denominatorY,
                widthForFilterSelection,
                heightForFilterSelection,
                horizontalFilterMode,
                verticalFilterMode,
                maximumSampleValue,
                prediction1
            )
        }
        for (y in 0..<height) {
            for (x in 0..<width) {
                val predictionIndex = y * width + x
                val sample0 = if (prediction0 != null)
                    prediction0[predictionIndex]
                else
                    sampleCompoundInterPlaneValue(
                        referencePlane0,
                        destinationX,
                        destinationY,
                        x,
                        y,
                        sourceOffsetEighthPelX0,
                        sourceOffsetEighthPelY0,
                        denominatorX,
                        denominatorY,
                        nonNullReferenceScale0,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        horizontalFilterMode,
                        verticalFilterMode,
                        maximumSampleValue
                    )
                val sample1 = if (prediction1 != null)
                    prediction1[predictionIndex]
                else
                    sampleCompoundInterPlaneValue(
                        referencePlane1,
                        destinationX,
                        destinationY,
                        x,
                        y,
                        sourceOffsetEighthPelX1,
                        sourceOffsetEighthPelY1,
                        denominatorX,
                        denominatorY,
                        nonNullReferenceScale1,
                        widthForFilterSelection,
                        heightForFilterSelection,
                        horizontalFilterMode,
                        verticalFilterMode,
                        maximumSampleValue
                    )
                val sample = when (nonNullCompoundPredictionType) {
                    CompoundPredictionType.AVERAGE -> averageCompoundPredictions(
                        sample0,
                        sample1,
                        postRoundBits
                    )

                    CompoundPredictionType.WEIGHTED_AVERAGE -> weightedAverageCompoundPredictions(
                        sample0,
                        sample1,
                        jointWeight,
                        postRoundBits
                    )

                    CompoundPredictionType.WEDGE -> {
                        val mask = InterIntraMasks.compoundWedgeMaskValue(
                            blockSize,
                            wedgeIndex,
                            maskSign,
                            x,
                            y,
                            subsamplingX,
                            subsamplingY
                        )
                        blendMaskedCompoundPredictions(sample0, sample1, mask, postRoundBits)
                    }

                    CompoundPredictionType.SEGMENT -> {
                        val mask = segmentCompoundMaskValue(
                            sample0,
                            sample1,
                            bitDepth,
                            postRoundBits,
                            maskSign,
                            x,
                            y,
                            subsamplingX,
                            subsamplingY,
                            lumaPlane,
                            segmentMask,
                            segmentMaskWidth,
                            segmentMaskHeight
                        )
                        blendMaskedCompoundPredictions(sample0, sample1, mask, postRoundBits)
                    }
                }
                destinationPlane.setSample(
                    destinationX + x,
                    destinationY + y,
                    clamp(sample, 0, maximumSampleValue)
                )
            }
        }
    }

    /** Writes one unscaled compound predictor using block-level separable filtering.
     * 
     * The destination retains the AV1 compound post-filter fractional bits. For a
     * two-dimensional fixed filter, horizontally filtered rows are computed once and reused by
     * the vertical pass.
     * 
     * @param referencePlane the immutable reference plane
     * @param destinationX the zero-based horizontal prediction origin
     * @param destinationY the zero-based vertical prediction origin
     * @param width the predicted width in samples
     * @param height the predicted height in samples
     * @param sourceOffsetEighthPelX the signed horizontal motion-vector component
     * @param sourceOffsetEighthPelY the signed vertical motion-vector component
     * @param denominatorX the plane-local horizontal denominator
     * @param denominatorY the plane-local vertical denominator
     * @param widthForFilterSelection the sampled block width used for filter selection
     * @param heightForFilterSelection the sampled block height used for filter selection
     * @param horizontalFilterMode the effective horizontal interpolation filter
     * @param verticalFilterMode the effective vertical interpolation filter
     * @param maximumSampleValue the maximum legal sample value for the destination bit depth
     * @param destination predictor storage with room for at least `width * height` samples
     */
    private fun reconstructUnscaledCompoundInterPlanePrediction(
        referencePlane: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        sourceOffsetEighthPelX: Int,
        sourceOffsetEighthPelY: Int,
        denominatorX: Int,
        denominatorY: Int,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter,
        maximumSampleValue: Int,
        destination: IntArray
    ) {
        val nonNullDestination = requireNotNullCompat(destination, "destination")
        val requiredLength = width * height
        require(nonNullDestination.size >= requiredLength) { "Compound prediction destination too short: " + nonNullDestination.size }
        val sourceX0 = destinationX + floorDiv(sourceOffsetEighthPelX, denominatorX)
        val sourceY0 = destinationY + floorDiv(sourceOffsetEighthPelY, denominatorY)
        val phaseX =
            interpolationPhase(floorMod(sourceOffsetEighthPelX, denominatorX), denominatorX)
        val phaseY =
            interpolationPhase(floorMod(sourceOffsetEighthPelY, denominatorY), denominatorY)
        val postRoundBits = interPredictionIntermediateBits(maximumSampleValue)
        if (phaseX == 0 && phaseY == 0) {
            val referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0,
                sourceY0,
                width,
                height
            )
            for (y in 0..<height) {
                val rowOffset = y * width
                for (x in 0..<width) {
                    nonNullDestination[rowOffset + x] =
                        (referenceSamples[rowOffset + x].toInt() and 0xFFFF) shl postRoundBits
                }
            }
            return
        }
        if (horizontalFilterMode == InterpolationFilter.BILINEAR
            && verticalFilterMode == InterpolationFilter.BILINEAR
        ) {
            reconstructUnscaledBilinearCompoundInterPlanePrediction(
                referencePlane,
                sourceX0,
                sourceY0,
                width,
                height,
                phaseX,
                phaseY,
                postRoundBits,
                nonNullDestination
            )
            return
        }
        check(
            !(!isConcreteInterpolationFilter(horizontalFilterMode) || !isConcreteInterpolationFilter(
                verticalFilterMode
            ) || horizontalFilterMode == InterpolationFilter.BILINEAR || verticalFilterMode == InterpolationFilter.BILINEAR)
        ) { "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters" }

        val horizontalFilter = if (phaseX == 0)
            null
        else
            selectSubpelFilter(horizontalFilterMode, phaseX, widthForFilterSelection)
        val verticalFilter = if (phaseY == 0)
            null
        else
            selectSubpelFilter(verticalFilterMode, phaseY, heightForFilterSelection)
        val firstPassRound: Int = INTER_FILTER_BITS - postRoundBits
        if (verticalFilter == null) {
            val checkedHorizontalFilter =
                requireNotNullCompat(horizontalFilter, "horizontalFilter")
            val referenceStride: Int = width + INTER_FILTER_TAP_COUNT - 1
            val referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0 - INTER_FILTER_START_OFFSET,
                sourceY0,
                referenceStride,
                height
            )
            for (y in 0..<height) {
                val rowOffset = y * width
                val referenceRowOffset = y * referenceStride
                for (x in 0..<width) {
                    var filtered: Long = 0
                    for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                        filtered += checkedHorizontalFilter[tapIndex].toLong() * (referenceSamples[referenceRowOffset + x + tapIndex].toInt() and 0xFFFF)
                    }
                    nonNullDestination[rowOffset + x] = roundShift(filtered, firstPassRound)
                }
            }
            return
        }
        if (horizontalFilter == null) {
            val referenceRowCount: Int = height + INTER_FILTER_TAP_COUNT - 1
            val referenceSamples = copyExtendedReferenceRegion(
                referencePlane,
                sourceX0,
                sourceY0 - INTER_FILTER_START_OFFSET,
                width,
                referenceRowCount
            )
            for (y in 0..<height) {
                val rowOffset = y * width
                for (x in 0..<width) {
                    var filtered: Long = 0
                    for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                        filtered += verticalFilter[tapIndex].toLong() * (referenceSamples[(y + tapIndex) * width + x].toInt() and 0xFFFF)
                    }
                    nonNullDestination[rowOffset + x] = roundShift(filtered, firstPassRound)
                }
            }
            return
        }

        val horizontalRowCount: Int = height + INTER_FILTER_TAP_COUNT - 1
        val horizontalSamples = interPredictionWorkspace
            .horizontalSamples(width * horizontalRowCount)
        val referenceStride: Int = width + INTER_FILTER_TAP_COUNT - 1
        val referenceSamples = copyExtendedReferenceRegion(
            referencePlane,
            sourceX0 - INTER_FILTER_START_OFFSET,
            sourceY0 - INTER_FILTER_START_OFFSET,
            referenceStride,
            horizontalRowCount
        )
        for (row in 0..<horizontalRowCount) {
            val rowOffset = row * width
            val referenceRowOffset = row * referenceStride
            for (x in 0..<width) {
                var filtered: Long = 0
                for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                    filtered += horizontalFilter[tapIndex].toLong() * (referenceSamples[referenceRowOffset + x + tapIndex].toInt() and 0xFFFF)
                }
                horizontalSamples[rowOffset + x] = roundShift(filtered, firstPassRound)
            }
        }
        for (y in 0..<height) {
            val rowOffset = y * width
            for (x in 0..<width) {
                var combined: Long = 0
                for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
                    combined += verticalFilter[tapIndex].toLong() * horizontalSamples[(y + tapIndex) * width + x]
                }
                nonNullDestination[rowOffset + x] = roundShift(combined, INTER_FILTER_BITS)
            }
        }
    }

    /** Writes one unscaled bilinear compound predictor with constant block phases.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceX0 the integer horizontal source origin
     * @param sourceY0 the integer vertical source origin
     * @param width the predicted width in samples
     * @param height the predicted height in samples
     * @param phaseX the horizontal interpolation phase in `[0, 15]`
     * @param phaseY the vertical interpolation phase in `[0, 15]`
     * @param postRoundBits the fractional bits retained for compound blending
     * @param destination predictor storage with room for at least `width * height` samples
     */
    private fun reconstructUnscaledBilinearCompoundInterPlanePrediction(
        referencePlane: PaddedPlane,
        sourceX0: Int,
        sourceY0: Int,
        width: Int,
        height: Int,
        phaseX: Int,
        phaseY: Int,
        postRoundBits: Int,
        destination: IntArray
    ) {
        val firstPassRound = 4 - postRoundBits
        val referenceStride = if (phaseX == 0) width else width + 1
        val referenceRowCount = if (phaseY == 0) height else height + 1
        val referenceSamples = copyExtendedReferenceRegion(
            referencePlane,
            sourceX0,
            sourceY0,
            referenceStride,
            referenceRowCount
        )
        for (y in 0..<height) {
            val rowOffset = y * width
            val topRowOffset = y * referenceStride
            val bottomRowOffset = (y + 1) * referenceStride
            for (x in 0..<width) {
                val topLeft = referenceSamples[topRowOffset + x].toInt() and 0xFFFF
                val predictor: Int
                if (phaseY == 0) {
                    val topRight = referenceSamples[topRowOffset + x + 1].toInt() and 0xFFFF
                    predictor = roundShift(
                        bilinearFilterSum(topLeft, topRight, phaseX).toLong(),
                        firstPassRound
                    )
                } else if (phaseX == 0) {
                    val bottomLeft = referenceSamples[bottomRowOffset + x].toInt() and 0xFFFF
                    predictor = roundShift(
                        bilinearFilterSum(topLeft, bottomLeft, phaseY).toLong(),
                        firstPassRound
                    )
                } else {
                    val topRight = referenceSamples[topRowOffset + x + 1].toInt() and 0xFFFF
                    val bottomLeft = referenceSamples[bottomRowOffset + x].toInt() and 0xFFFF
                    val bottomRight = referenceSamples[bottomRowOffset + x + 1].toInt() and 0xFFFF
                    val top = roundShift(
                        bilinearFilterSum(topLeft, topRight, phaseX).toLong(),
                        firstPassRound
                    )
                    val bottom = roundShift(
                        bilinearFilterSum(bottomLeft, bottomRight, phaseX).toLong(),
                        firstPassRound
                    )
                    predictor = roundShift(bilinearFilterSum(top, bottom, phaseY).toLong(), 4)
                }
                destination[rowOffset + x] = predictor
            }
        }
    }

    /** Returns one compound inter predictor while retaining the AV1 post-filter fractional bits.
     * 
     * Unlike [#sampleInterPlaneValue(PaddedPlane , int, int, int, int, int, int, int, int,
     * /// ReferenceScale, int, int, FrameHeader.InterpolationFilter,
     * /// FrameHeader.InterpolationFilter, int)], this method neither rounds to sample precision nor
     * clips the predictor. Compound blending performs both operations after combining the two
     * predictors.
     * 
     * @param referencePlane the immutable reference plane
     * @param destinationX the zero-based horizontal prediction origin
     * @param destinationY the zero-based vertical prediction origin
     * @param sampleX the block-local horizontal sample offset
     * @param sampleY the block-local vertical sample offset
     * @param sourceOffsetEighthPelX the signed horizontal motion-vector component in luma eighth-pel units
     * @param sourceOffsetEighthPelY the signed vertical motion-vector component in luma eighth-pel units
     * @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
     * @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
     * @param referenceScale the scale factors derived from the current and reference luma dimensions
     * @param widthForFilterSelection the sampled block width used for reduced-width filter selection
     * @param heightForFilterSelection the sampled block height used for reduced-width filter selection
     * @param horizontalFilterMode the effective horizontal interpolation filter mode
     * @param verticalFilterMode the effective vertical interpolation filter mode
     * @param maximumSampleValue the maximum legal sample value for the destination bit depth
     * @return one signed predictor with the AV1 compound post-filter fractional bits retained
     */
    private fun sampleCompoundInterPlaneValue(
        referencePlane: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        sampleX: Int,
        sampleY: Int,
        sourceOffsetEighthPelX: Int,
        sourceOffsetEighthPelY: Int,
        denominatorX: Int,
        denominatorY: Int,
        referenceScale: ReferenceScale,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter,
        maximumSampleValue: Int
    ): Int {
        val nonNullReferenceScale: ReferenceScale =
            requireNotNullCompat(referenceScale, "referenceScale")
        val sourceNumeratorX: Int
        val sourceNumeratorY: Int
        val interpolationDenominatorX: Int
        val interpolationDenominatorY: Int
        if (nonNullReferenceScale.scaled) {
            sourceNumeratorX = scaledReferenceSourceNumerator(
                destinationX,
                sampleX,
                sourceOffsetEighthPelX,
                denominatorX,
                nonNullReferenceScale.horizontalFactor
            )
            sourceNumeratorY = scaledReferenceSourceNumerator(
                destinationY,
                sampleY,
                sourceOffsetEighthPelY,
                denominatorY,
                nonNullReferenceScale.verticalFactor
            )
            interpolationDenominatorX = 1 shl SCALED_INTER_SUBPEL_BITS
            interpolationDenominatorY = 1 shl SCALED_INTER_SUBPEL_BITS
        } else {
            sourceNumeratorX = (destinationX + sampleX) * denominatorX + sourceOffsetEighthPelX
            sourceNumeratorY = (destinationY + sampleY) * denominatorY + sourceOffsetEighthPelY
            interpolationDenominatorX = denominatorX
            interpolationDenominatorY = denominatorY
        }
        val postRoundBits = interPredictionIntermediateBits(maximumSampleValue)
        if (floorMod(sourceNumeratorX, interpolationDenominatorX) == 0
            && floorMod(sourceNumeratorY, interpolationDenominatorY) == 0
        ) {
            val sample = referencePlane.sample(
                clamp(
                    floorDiv(sourceNumeratorX, interpolationDenominatorX),
                    0,
                    referencePlane.width() - 1
                ),
                clamp(
                    floorDiv(sourceNumeratorY, interpolationDenominatorY),
                    0,
                    referencePlane.height() - 1
                )
            )
            return sample shl postRoundBits
        }
        return filteredCompoundInterpolateAt(
            referencePlane,
            sourceNumeratorX,
            sourceNumeratorY,
            interpolationDenominatorX,
            interpolationDenominatorY,
            widthForFilterSelection,
            heightForFilterSelection,
            horizontalFilterMode,
            verticalFilterMode,
            maximumSampleValue
        )
    }

    /** Returns one fixed-filter compound predictor before the final compound blend and post-round.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceNumeratorX the source horizontal numerator in plane-local sample units
     * @param sourceNumeratorY the source vertical numerator in plane-local sample units
     * @param denominatorX the horizontal interpolation denominator
     * @param denominatorY the vertical interpolation denominator
     * @param widthForFilterSelection the sampled block width in pixels
     * @param heightForFilterSelection the sampled block height in pixels
     * @param horizontalFilterMode the effective horizontal interpolation filter
     * @param verticalFilterMode the effective vertical interpolation filter
     * @param maximumSampleValue the maximum legal sample value for the destination bit depth
     * @return one signed predictor retaining the AV1 compound post-filter fractional bits
     */
    private fun filteredCompoundInterpolateAt(
        referencePlane: PaddedPlane,
        sourceNumeratorX: Int,
        sourceNumeratorY: Int,
        denominatorX: Int,
        denominatorY: Int,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter,
        maximumSampleValue: Int
    ): Int {
        if (horizontalFilterMode == InterpolationFilter.BILINEAR
            && verticalFilterMode == InterpolationFilter.BILINEAR
        ) {
            return bilinearCompoundInterpolateAt(
                referencePlane,
                sourceNumeratorX,
                sourceNumeratorY,
                denominatorX,
                denominatorY,
                maximumSampleValue
            )
        }
        check(
            !(!isConcreteInterpolationFilter(horizontalFilterMode) || !isConcreteInterpolationFilter(
                verticalFilterMode
            ) || horizontalFilterMode == InterpolationFilter.BILINEAR || verticalFilterMode == InterpolationFilter.BILINEAR)
        ) { "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters" }

        val sourceY0 = floorDiv(sourceNumeratorY, denominatorY)
        val phaseY = interpolationPhase(floorMod(sourceNumeratorY, denominatorY), denominatorY)
        val sourceX0 = floorDiv(sourceNumeratorX, denominatorX)
        val phaseX = interpolationPhase(floorMod(sourceNumeratorX, denominatorX), denominatorX)
        val postRoundBits = interPredictionIntermediateBits(maximumSampleValue)
        if (phaseX == 0 && phaseY == 0) {
            return referencePlane.sample(
                clamp(sourceX0, 0, referencePlane.width() - 1),
                clamp(sourceY0, 0, referencePlane.height() - 1)
            ) shl postRoundBits
        }
        val firstPassRound: Int = INTER_FILTER_BITS - postRoundBits
        val horizontalFilter =
            if (phaseX == 0) null else selectSubpelFilter(
                horizontalFilterMode,
                phaseX,
                widthForFilterSelection
            )
        val verticalFilter =
            if (phaseY == 0) null else selectSubpelFilter(
                verticalFilterMode,
                phaseY,
                heightForFilterSelection
            )
        if (verticalFilter == null) {
            return roundShift(
                horizontalInterpolate(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    requireNotNullCompat(horizontalFilter, "horizontalFilter")
                ),
                firstPassRound
            )
        }
        if (horizontalFilter == null) {
            return roundShift(
                verticalInterpolate(
                    referencePlane,
                    sourceX0,
                    sourceY0,
                    verticalFilter
                ),
                firstPassRound
            )
        }

        var combined: Long = 0
        for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
            val sourceY = clamp(
                sourceY0 + tapIndex - INTER_FILTER_START_OFFSET,
                0,
                referencePlane.height() - 1
            )
            val horizontallyFiltered = roundShift(
                horizontalInterpolate(referencePlane, sourceX0, sourceY, horizontalFilter),
                firstPassRound
            )
            combined += verticalFilter[tapIndex].toLong() * horizontallyFiltered
        }
        return roundShift(combined, INTER_FILTER_BITS)
    }

    /** Returns one bilinear compound predictor before the final compound blend and post-round.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceNumeratorX the source horizontal numerator in plane-local sample units
     * @param sourceNumeratorY the source vertical numerator in plane-local sample units
     * @param denominatorX the horizontal interpolation denominator
     * @param denominatorY the vertical interpolation denominator
     * @param maximumSampleValue the maximum legal sample value for the destination bit depth
     * @return one signed predictor retaining the AV1 compound post-filter fractional bits
     */
    private fun bilinearCompoundInterpolateAt(
        referencePlane: PaddedPlane,
        sourceNumeratorX: Int,
        sourceNumeratorY: Int,
        denominatorX: Int,
        denominatorY: Int,
        maximumSampleValue: Int
    ): Int {
        val sourceY0 = floorDiv(sourceNumeratorY, denominatorY)
        val fractionY =
            interpolationPhase(floorMod(sourceNumeratorY, denominatorY), denominatorY)
        val clampedSourceY0 = clamp(sourceY0, 0, referencePlane.height() - 1)
        val clampedSourceY1 = clamp(sourceY0 + 1, 0, referencePlane.height() - 1)
        val sourceX0 = floorDiv(sourceNumeratorX, denominatorX)
        val fractionX =
            interpolationPhase(floorMod(sourceNumeratorX, denominatorX), denominatorX)
        val clampedSourceX0 = clamp(sourceX0, 0, referencePlane.width() - 1)
        val clampedSourceX1 = clamp(sourceX0 + 1, 0, referencePlane.width() - 1)
        val topLeft = referencePlane.sample(clampedSourceX0, clampedSourceY0)
        val topRight = referencePlane.sample(clampedSourceX1, clampedSourceY0)
        val bottomLeft = referencePlane.sample(clampedSourceX0, clampedSourceY1)
        val bottomRight = referencePlane.sample(clampedSourceX1, clampedSourceY1)
        val postRoundBits = interPredictionIntermediateBits(maximumSampleValue)
        val firstPassRound = 4 - postRoundBits
        if (fractionY == 0) {
            return roundShift(
                bilinearFilterSum(topLeft, topRight, fractionX).toLong(),
                firstPassRound
            )
        }
        if (fractionX == 0) {
            return roundShift(
                bilinearFilterSum(topLeft, bottomLeft, fractionY).toLong(),
                firstPassRound
            )
        }
        val top =
            roundShift(bilinearFilterSum(topLeft, topRight, fractionX).toLong(), firstPassRound)
        val bottom = roundShift(
            bilinearFilterSum(bottomLeft, bottomRight, fractionX).toLong(),
            firstPassRound
        )
        return roundShift(bilinearFilterSum(top, bottom, fractionY).toLong(), 4)
    }

    /** Returns one inter-predicted plane sample at a block-local destination offset.
     * 
     * @param referencePlane the immutable reference plane
     * @param destinationX the zero-based horizontal prediction origin
     * @param destinationY the zero-based vertical prediction origin
     * @param sampleX the block-local horizontal sample offset
     * @param sampleY the block-local vertical sample offset
     * @param sourceOffsetEighthPelX the signed horizontal motion-vector component in luma eighth-pel units
     * @param sourceOffsetEighthPelY the signed vertical motion-vector component in luma eighth-pel units
     * @param denominatorX the plane-local horizontal denominator expressed in luma eighth-pel units
     * @param denominatorY the plane-local vertical denominator expressed in luma eighth-pel units
     * @param referenceScale the scale factors derived from the current and reference luma dimensions
     * @param widthForFilterSelection the sampled block width in pixels used for AV1 reduced-width filter selection
     * @param heightForFilterSelection the sampled block height in pixels used for AV1 reduced-width filter selection
     * @param horizontalFilterMode the effective horizontal interpolation filter mode
     * @param verticalFilterMode the effective vertical interpolation filter mode
     * @param maximumSampleValue the maximum legal output sample value for the destination bit depth
     * @return one predicted plane sample
     */
    private fun sampleInterPlaneValue(
        referencePlane: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        sampleX: Int,
        sampleY: Int,
        sourceOffsetEighthPelX: Int,
        sourceOffsetEighthPelY: Int,
        denominatorX: Int,
        denominatorY: Int,
        referenceScale: ReferenceScale,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter,
        maximumSampleValue: Int
    ): Int {
        val nonNullReferenceScale: ReferenceScale =
            requireNotNullCompat(referenceScale, "referenceScale")
        val sourceNumeratorX: Int
        val sourceNumeratorY: Int
        val interpolationDenominatorX: Int
        val interpolationDenominatorY: Int
        if (nonNullReferenceScale.scaled) {
            sourceNumeratorX = scaledReferenceSourceNumerator(
                destinationX,
                sampleX,
                sourceOffsetEighthPelX,
                denominatorX,
                nonNullReferenceScale.horizontalFactor
            )
            sourceNumeratorY = scaledReferenceSourceNumerator(
                destinationY,
                sampleY,
                sourceOffsetEighthPelY,
                denominatorY,
                nonNullReferenceScale.verticalFactor
            )
            interpolationDenominatorX = 1 shl SCALED_INTER_SUBPEL_BITS
            interpolationDenominatorY = 1 shl SCALED_INTER_SUBPEL_BITS
        } else {
            sourceNumeratorX = (destinationX + sampleX) * denominatorX + sourceOffsetEighthPelX
            sourceNumeratorY = (destinationY + sampleY) * denominatorY + sourceOffsetEighthPelY
            interpolationDenominatorX = denominatorX
            interpolationDenominatorY = denominatorY
        }
        if (floorMod(sourceNumeratorX, interpolationDenominatorX) == 0
            && floorMod(sourceNumeratorY, interpolationDenominatorY) == 0
        ) {
            return referencePlane.sample(
                clamp(
                    floorDiv(sourceNumeratorX, interpolationDenominatorX),
                    0,
                    referencePlane.width() - 1
                ),
                clamp(
                    floorDiv(sourceNumeratorY, interpolationDenominatorY),
                    0,
                    referencePlane.height() - 1
                )
            )
        }
        return filteredInterpolateAt(
            referencePlane,
            sourceNumeratorX,
            sourceNumeratorY,
            interpolationDenominatorX,
            interpolationDenominatorY,
            widthForFilterSelection,
            heightForFilterSelection,
            horizontalFilterMode,
            verticalFilterMode,
            maximumSampleValue
        )
    }

    /** Copies one rectangular reference-plane footprint into the destination plane with edge
     * extension.
     * 
     * @param destinationPlane the mutable destination plane
     * @param referencePlane the immutable reference plane
     * @param destinationX the zero-based horizontal destination coordinate
     * @param destinationY the zero-based vertical destination coordinate
     * @param sourceX the zero-based horizontal source coordinate
     * @param sourceY the zero-based vertical source coordinate
     * @param width the copied width in samples
     * @param height the copied height in samples
     */
    private fun copyReferencePlaneBlock(
        destinationPlane: MutablePlaneBuffer,
        referencePlane: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int
    ) {
        destinationPlane.copyExtendedBlockFrom(
            referencePlane,
            destinationX,
            destinationY,
            sourceX,
            sourceY,
            width,
            height
        )
    }

    /** Copies one edge-extended reference region into reusable tightly packed storage.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceX the horizontal source origin
     * @param sourceY the vertical source origin
     * @param width the positive copied width in samples
     * @param height the positive copied height in samples
     * @return reusable row-major reference storage whose active prefix has `width * height` samples
     */
    private fun copyExtendedReferenceRegion(
        referencePlane: PaddedPlane,
        sourceX: Int,
        sourceY: Int,
        width: Int,
        height: Int
    ): ShortArray {
        val requiredLength = multiplyExact(width, height)
        val referenceSamples = interPredictionWorkspace.referenceSamples(requiredLength)
        for (row in 0..<height) {
            referencePlane.copyExtendedRowTo(
                sourceX,
                sourceY + row,
                referenceSamples,
                row * width,
                width
            )
        }
        return referenceSamples
    }

    /** Returns one fixed-filter interpolated unsigned sample at the supplied plane-local source
     * numerator coordinates.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceNumeratorX the source horizontal numerator in plane-local sample units
     * @param sourceNumeratorY the source vertical numerator in plane-local sample units
     * @param denominatorX the horizontal interpolation denominator
     * @param denominatorY the vertical interpolation denominator
     * @param widthForFilterSelection the sampled block width in pixels
     * @param heightForFilterSelection the sampled block height in pixels
     * @param horizontalFilterMode the effective horizontal interpolation filter
     * @param verticalFilterMode the effective vertical interpolation filter
     * @param maximumSampleValue the maximum legal output sample value for the destination bit depth
     * @return one fixed-filter interpolated unsigned sample
     */
    private fun filteredInterpolateAt(
        referencePlane: PaddedPlane,
        sourceNumeratorX: Int,
        sourceNumeratorY: Int,
        denominatorX: Int,
        denominatorY: Int,
        widthForFilterSelection: Int,
        heightForFilterSelection: Int,
        horizontalFilterMode: InterpolationFilter,
        verticalFilterMode: InterpolationFilter,
        maximumSampleValue: Int
    ): Int {
        if (horizontalFilterMode == InterpolationFilter.BILINEAR
            && verticalFilterMode == InterpolationFilter.BILINEAR
        ) {
            return bilinearInterpolateAt(
                referencePlane,
                sourceNumeratorX,
                sourceNumeratorY,
                denominatorX,
                denominatorY,
                maximumSampleValue
            )
        }
        check(
            !(!isConcreteInterpolationFilter(horizontalFilterMode) || !isConcreteInterpolationFilter(
                verticalFilterMode
            ) || horizontalFilterMode == InterpolationFilter.BILINEAR || verticalFilterMode == InterpolationFilter.BILINEAR)
        ) { "Inter reconstruction requires resolved matching BILINEAR or EIGHT_TAP_* filters" }

        val sourceY0 = floorDiv(sourceNumeratorY, denominatorY)
        val phaseY = interpolationPhase(floorMod(sourceNumeratorY, denominatorY), denominatorY)
        val sourceX0 = floorDiv(sourceNumeratorX, denominatorX)
        val phaseX = interpolationPhase(floorMod(sourceNumeratorX, denominatorX), denominatorX)
        if (phaseX == 0 && phaseY == 0) {
            return referencePlane.sample(
                clamp(sourceX0, 0, referencePlane.width() - 1),
                clamp(sourceY0, 0, referencePlane.height() - 1)
            )
        }

        val horizontalFilter =
            if (phaseX == 0) null else selectSubpelFilter(
                horizontalFilterMode,
                phaseX,
                widthForFilterSelection
            )
        val verticalFilter =
            if (phaseY == 0) null else selectSubpelFilter(
                verticalFilterMode,
                phaseY,
                heightForFilterSelection
            )
        val intermediateBits = interPredictionIntermediateBits(maximumSampleValue)
        if (verticalFilter == null) {
            val filtered = horizontalInterpolate(
                referencePlane,
                sourceX0,
                sourceY0,
                requireNotNullCompat(horizontalFilter, "horizontalFilter")
            )
            val intermediate = roundShift(filtered, INTER_FILTER_BITS - intermediateBits)
            return clamp(roundShift(intermediate.toLong(), intermediateBits), 0, maximumSampleValue)
        }
        if (horizontalFilter == null) {
            val filtered = verticalInterpolate(
                referencePlane,
                sourceX0,
                sourceY0,
                requireNotNullCompat(verticalFilter, "verticalFilter")
            )
            return clamp(roundShift(filtered, INTER_FILTER_BITS), 0, maximumSampleValue)
        }

        var combined: Long = 0
        for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
            val sourceY = clamp(
                sourceY0 + tapIndex - INTER_FILTER_START_OFFSET,
                0,
                referencePlane.height() - 1
            )
            val filtered =
                horizontalInterpolate(referencePlane, sourceX0, sourceY, horizontalFilter)
            val horizontallyFiltered = roundShift(filtered, INTER_FILTER_BITS - intermediateBits)
            combined += verticalFilter[tapIndex].toLong() * horizontallyFiltered
        }
        return clamp(
            roundShift(combined, INTER_FILTER_BITS + intermediateBits),
            0,
            maximumSampleValue
        )
    }

    /** Returns one bilinearly interpolated unsigned sample at the supplied plane-local source
     * numerator coordinates.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceNumeratorX the source horizontal numerator in plane-local sample units
     * @param sourceNumeratorY the source vertical numerator in plane-local sample units
     * @param denominatorX the horizontal interpolation denominator
     * @param denominatorY the vertical interpolation denominator
     * @param maximumSampleValue the maximum legal output sample value for the destination bit depth
     * @return one bilinearly interpolated unsigned sample
     */
    private fun bilinearInterpolateAt(
        referencePlane: PaddedPlane,
        sourceNumeratorX: Int,
        sourceNumeratorY: Int,
        denominatorX: Int,
        denominatorY: Int,
        maximumSampleValue: Int
    ): Int {
        val sourceY0 = floorDiv(sourceNumeratorY, denominatorY)
        val fractionY =
            interpolationPhase(floorMod(sourceNumeratorY, denominatorY), denominatorY)
        val clampedSourceY0 = clamp(sourceY0, 0, referencePlane.height() - 1)
        val clampedSourceY1 = clamp(sourceY0 + 1, 0, referencePlane.height() - 1)
        val sourceX0 = floorDiv(sourceNumeratorX, denominatorX)
        val fractionX =
            interpolationPhase(floorMod(sourceNumeratorX, denominatorX), denominatorX)
        val clampedSourceX0 = clamp(sourceX0, 0, referencePlane.width() - 1)
        val clampedSourceX1 = clamp(sourceX0 + 1, 0, referencePlane.width() - 1)
        val topLeft = referencePlane.sample(clampedSourceX0, clampedSourceY0)
        val topRight = referencePlane.sample(clampedSourceX1, clampedSourceY0)
        val bottomLeft = referencePlane.sample(clampedSourceX0, clampedSourceY1)
        val bottomRight = referencePlane.sample(clampedSourceX1, clampedSourceY1)
        if (fractionY == 0) {
            val intermediateBits = interPredictionIntermediateBits(maximumSampleValue)
            val horizontal = roundShift(
                bilinearFilterSum(topLeft, topRight, fractionX).toLong(),
                4 - intermediateBits
            )
            return clamp(roundShift(horizontal.toLong(), intermediateBits), 0, maximumSampleValue)
        }
        if (fractionX == 0) {
            return clamp(
                roundShift(bilinearFilterSum(topLeft, bottomLeft, fractionY).toLong(), 4),
                0,
                maximumSampleValue
            )
        }

        val intermediateBits = interPredictionIntermediateBits(maximumSampleValue)
        val top = roundShift(
            bilinearFilterSum(topLeft, topRight, fractionX).toLong(),
            4 - intermediateBits
        )
        val bottom = roundShift(
            bilinearFilterSum(bottomLeft, bottomRight, fractionX).toLong(),
            4 - intermediateBits
        )
        return clamp(
            roundShift(bilinearFilterSum(top, bottom, fractionY).toLong(), 4 + intermediateBits),
            0,
            maximumSampleValue
        )
    }

    /** Returns one filtered horizontal interpolation sum before normalization.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceX0 the integer horizontal source position
     * @param sourceY the integer vertical source position
     * @param filter the selected AV1 subpel filter taps
     * @return one filtered horizontal interpolation sum before normalization
     */
    private fun horizontalInterpolate(
        referencePlane: PaddedPlane,
        sourceX0: Int,
        sourceY: Int,
        filter: IntArray
    ): Long {
        var filtered: Long = 0
        val clampedY = clamp(sourceY, 0, referencePlane.height() - 1)
        for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
            val sourceX = clamp(
                sourceX0 + tapIndex - INTER_FILTER_START_OFFSET,
                0,
                referencePlane.width() - 1
            )
            filtered += filter[tapIndex].toLong() * referencePlane.sample(sourceX, clampedY)
        }
        return filtered
    }

    /** Returns one filtered vertical interpolation sum before normalization.
     * 
     * @param referencePlane the immutable reference plane
     * @param sourceX the integer horizontal source position
     * @param sourceY0 the integer vertical source position
     * @param filter the selected AV1 subpel filter taps
     * @return one filtered vertical interpolation sum before normalization
     */
    private fun verticalInterpolate(
        referencePlane: PaddedPlane,
        sourceX: Int,
        sourceY0: Int,
        filter: IntArray
    ): Long {
        var filtered: Long = 0
        val clampedX = clamp(sourceX, 0, referencePlane.width() - 1)
        for (tapIndex in 0..<INTER_FILTER_TAP_COUNT) {
            val sourceY = clamp(
                sourceY0 + tapIndex - INTER_FILTER_START_OFFSET,
                0,
                referencePlane.height() - 1
            )
            filtered += filter[tapIndex].toLong() * referencePlane.sample(clampedX, sourceY)
        }
        return filtered
    }

    /** Returns the selected AV1 fixed-filter taps for one fractional phase and sampled axis size.
     * 
     * @param filterMode the requested interpolation filter mode
     * @param phase the normalized AV1 fractional phase in `[1, 15]`
     * @param axisSize the sampled axis size in pixels
     * @return the selected AV1 fixed-filter taps
     */
    private fun selectSubpelFilter(
        filterMode: InterpolationFilter,
        phase: Int,
        axisSize: Int
    ): IntArray {
        check(!(phase <= 0 || phase >= INTER_FILTER_PHASES)) { "AV1 fixed-filter phase out of range: $phase" }
        return when (filterMode) {
            InterpolationFilter.EIGHT_TAP_REGULAR -> (if (axisSize <= 4) SMALL_REGULAR_SUBPEL_FILTERS else REGULAR_SUBPEL_FILTERS)[phase - 1]
            InterpolationFilter.EIGHT_TAP_SMOOTH -> (if (axisSize <= 4) SMALL_SMOOTH_SUBPEL_FILTERS else SMOOTH_SUBPEL_FILTERS)[phase - 1]
            InterpolationFilter.EIGHT_TAP_SHARP -> (if (axisSize <= 4) SMALL_REGULAR_SUBPEL_FILTERS else SHARP_SUBPEL_FILTERS)[phase - 1]
            else -> throw IllegalStateException(
                "AV1 fixed-filter selection requires an EIGHT_TAP_REGULAR, EIGHT_TAP_SMOOTH, or EIGHT_TAP_SHARP filter"
            )
        }
    }

    /** Returns AV1 reference scale factors for one current/reference frame pair.
     * 
     * @param currentWidth the current coded luma width
     * @param currentHeight the current coded luma height
     * @param referenceSurfaceSnapshot the stored reference surface
     * @return the fixed-point reference scale factors
     */
    private fun referenceScale(
        currentWidth: Int,
        currentHeight: Int,
        referenceSurfaceSnapshot: ReferenceSurfaceSnapshot
    ): ReferenceScale {
        val nonNullSnapshot =
            requireNotNullCompat(
                referenceSurfaceSnapshot,
                "referenceSurfaceSnapshot"
            )
        val referenceSize = nonNullSnapshot.frameHeader.frameSize()
        val referenceWidth = referenceSize.upscaledWidth()
        val referenceHeight = referenceSize.height()
        if (referenceWidth == currentWidth && referenceHeight == currentHeight) {
            return IDENTITY_REFERENCE_SCALE
        }
        return ReferenceScale(
            referenceScaleFactor(referenceWidth, currentWidth),
            referenceScaleFactor(referenceHeight, currentHeight),
            true
        )
    }

    /** Returns one AV1 Q14 reference scale factor.
     * 
     * @param referenceExtent the stored reference luma extent
     * @param currentExtent the current coded luma extent
     * @return the rounded Q14 scale factor
     */
    private fun referenceScaleFactor(referenceExtent: Int, currentExtent: Int): Int {
        check(!(referenceExtent <= 0 || currentExtent <= 0)) { "Reference scaling requires positive frame dimensions" }
        return toIntExact(
            ((referenceExtent.toLong() shl REFERENCE_SCALE_BITS) + (currentExtent shr 1)) / currentExtent
        )
    }

    /** Returns one Q10 source coordinate for scaled AV1 inter prediction.
     * 
     * The block origin uses AV1's signed center-offset scale formula. Subsequent samples advance by
     * the rounded Q10 scale step, matching the scaled motion-compensation process.
     * 
     * @param destinationOrigin the prediction origin in plane samples
     * @param sampleOffset the block-local sample offset
     * @param motionVectorEighthPel the motion-vector component in luma eighth-pel units
     * @param planeDenominator the plane-local denominator in luma eighth-pel units
     * @param scaleFactor the Q14 luma-domain reference scale factor
     * @return the scaled source coordinate in Q10 plane-sample units
     */
    private fun scaledReferenceSourceNumerator(
        destinationOrigin: Int,
        sampleOffset: Int,
        motionVectorEighthPel: Int,
        planeDenominator: Int,
        scaleFactor: Int
    ): Int {
        check(!(planeDenominator != 8 && planeDenominator != 16)) { "Unexpected inter-prediction plane denominator: $planeDenominator" }
        val originalPosition = (destinationOrigin.toLong() * 16
                + motionVectorEighthPel.toLong() * (16 / planeDenominator))
        val scaledPosition = (originalPosition * scaleFactor
                + (scaleFactor - REFERENCE_SCALE_IDENTITY).toLong() * 8)
        val blockStart = roundShiftSigned(scaledPosition, 8) + 32
        val step = (scaleFactor + 8) shr 4
        return toIntExact(blockStart.toLong() + sampleOffset.toLong() * step)
    }

    /** Returns the normalized AV1 subpel phase for the supplied plane-local fraction.
     * 
     * @param fraction the plane-local source fraction
     * @param denominator the plane-local interpolation denominator
     * @return the normalized AV1 subpel phase in `[0, 15]`
     */
    private fun interpolationPhase(fraction: Int, denominator: Int): Int {
        if (fraction == 0) {
            return 0
        }
        return multiplyExact(fraction, INTER_FILTER_PHASES) / denominator
    }

    /** Returns the number of fractional bits retained between horizontal and vertical inter
     * filtering for the supplied decoded sample range.
     * 
     * @param maximumSampleValue the maximum legal sample value for the decoded bit depth
     * @return `4` for 8- and 10-bit samples, or `2` for 12-bit samples
     */
    private fun interPredictionIntermediateBits(maximumSampleValue: Int): Int {
        return when (maximumSampleValue) {
            255, 1023 -> 4
            4095 -> 2
            else -> throw IllegalArgumentException(
                "Unsupported AV1 inter-prediction sample range: $maximumSampleValue"
            )
        }
    }

    /** Applies AV1 `Round2` to one value using an arithmetic right shift.
     * 
     * @param value the value to round
     * @param bits the number of low bits to discard, including zero
     * @return the rounded value
     */
    private fun roundShift(value: Long, bits: Int): Int {
        if (bits == 0) {
            return toIntExact(value)
        }
        return toIntExact((value + (1L shl (bits - 1))) shr bits)
    }

    /** Rounds one signed integer by the requested arithmetic right shift.
     * 
     * @param value the signed value to round
     * @param bits the number of low bits to discard
     * @return the rounded signed value
     */
    private fun roundShiftSigned(value: Long, bits: Int): Int {
        val roundingOffset = 1L shl (bits - 1)
        if (value >= 0) {
            return ((value + roundingOffset) shr bits).toInt()
        }
        return -(((-value) + roundingOffset) shr bits).toInt()
    }

    /** Returns whether one interpolation filter is resolved to a concrete prediction kernel.
     * 
     * @param filterMode the interpolation filter mode
     * @return whether the filter is concrete rather than switchable
     */
    private fun isConcreteInterpolationFilter(filterMode: InterpolationFilter): Boolean {
        return filterMode == InterpolationFilter.BILINEAR || filterMode == InterpolationFilter.EIGHT_TAP_REGULAR || filterMode == InterpolationFilter.EIGHT_TAP_SMOOTH || filterMode == InterpolationFilter.EIGHT_TAP_SHARP
    }

    /** Returns one unnormalized AV1 bilinear-filter sum.
     * 
     * @param first the sample at the integer source position
     * @param second the sample one position after the integer source position
     * @param phase the subpel phase in `[0, 15]`
     * @return the filtered sum with four fractional bits
     */
    private fun bilinearFilterSum(first: Int, second: Int, phase: Int): Int {
        return 16 * first + phase * (second - first)
    }

    /** Returns one simple average-compound sample from two higher-precision predictors.
     * 
     * @param primaryPrediction the primary higher-precision predictor
     * @param secondaryPrediction the secondary higher-precision predictor
     * @param postRoundBits the fractional predictor bits discarded after blending
     * @return the averaged compound sample
     */
    private fun averageCompoundPredictions(
        primaryPrediction: Int,
        secondaryPrediction: Int,
        postRoundBits: Int
    ): Int {
        return roundShift(primaryPrediction.toLong() + secondaryPrediction, 1 + postRoundBits)
    }

    /** Returns one weighted average-compound sample from two higher-precision predictors.
     * 
     * @param primaryPrediction the primary higher-precision predictor
     * @param secondaryPrediction the secondary higher-precision predictor
     * @param primaryWeight the primary predictor weight in sixteenths
     * @param postRoundBits the fractional predictor bits discarded after blending
     * @return one weighted average-compound sample
     */
    private fun weightedAverageCompoundPredictions(
        primaryPrediction: Int,
        secondaryPrediction: Int,
        primaryWeight: Int,
        postRoundBits: Int
    ): Int {
        return roundShift(
            primaryPrediction.toLong() * primaryWeight
                    + secondaryPrediction.toLong() * (16 - primaryWeight),
            4 + postRoundBits
        )
    }

    /** Returns one masked compound sample using the supplied secondary-source weight.
     * 
     * @param primarySample the primary predicted sample
     * @param secondarySample the secondary predicted sample
     * @param secondaryWeight the secondary predictor weight in `[0, 64]`
     * @return one masked compound sample
     */
    private fun blendMaskedCompoundSamples(
        primarySample: Int,
        secondarySample: Int,
        secondaryWeight: Int
    ): Int {
        return (primarySample * (64 - secondaryWeight) + secondarySample * secondaryWeight + 32) shr 6
    }

    /** Returns one masked compound sample from two higher-precision predictors.
     * 
     * @param primaryPrediction the primary higher-precision predictor
     * @param secondaryPrediction the secondary higher-precision predictor
     * @param secondaryWeight the secondary predictor weight in `[0, 64]`
     * @param postRoundBits the fractional predictor bits discarded after blending
     * @return one masked compound sample
     */
    private fun blendMaskedCompoundPredictions(
        primaryPrediction: Int,
        secondaryPrediction: Int,
        secondaryWeight: Int,
        postRoundBits: Int
    ): Int {
        return roundShift(
            primaryPrediction.toLong() * (64 - secondaryWeight)
                    + secondaryPrediction.toLong() * secondaryWeight,
            6 + postRoundBits
        )
    }

    /** Returns one segment-compound mask value as the effective secondary-source blend weight.
     * 
     * @param primarySample the primary higher-precision predictor
     * @param secondarySample the secondary higher-precision predictor
     * @param bitDepth the decoded sample bit depth
     * @param postRoundBits the fractional predictor bits discarded after blending
     * @param maskSign whether the decoded segment mask uses inverted source order
     * @param x the plane-local sample X coordinate inside the block
     * @param y the plane-local sample Y coordinate inside the block
     * @param subsamplingX the horizontal chroma subsampling shift for this plane
     * @param subsamplingY the vertical chroma subsampling shift for this plane
     * @param lumaPlane whether the caller is reconstructing luma and must populate the segment mask
     * @param segmentMask the luma-domain segment mask to populate or reuse, or `null`
     * @param segmentMaskWidth the luma-domain segment mask width
     * @param segmentMaskHeight the luma-domain segment mask height
     * @return one segment-compound mask value as the effective secondary-source blend weight
     */
    private fun segmentCompoundMaskValue(
        primarySample: Int,
        secondarySample: Int,
        bitDepth: Int,
        postRoundBits: Int,
        maskSign: Boolean,
        x: Int,
        y: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        lumaPlane: Boolean,
        segmentMask: IntArray?,
        segmentMaskWidth: Int,
        segmentMaskHeight: Int
    ): Int {
        val mask: Int
        if (lumaPlane) {
            mask = lumaSegmentCompoundMaskValue(
                primarySample,
                secondarySample,
                bitDepth,
                postRoundBits
            )
            if (segmentMask != null && x < segmentMaskWidth && y < segmentMaskHeight) {
                segmentMask[y * segmentMaskWidth + x] = mask
            }
        } else {
            checkNotNull(segmentMask) { "Chroma segment compound prediction requires a luma segment mask" }
            mask = chromaSegmentCompoundMaskValue(
                segmentMask,
                segmentMaskWidth,
                segmentMaskHeight,
                x,
                y,
                subsamplingX,
                subsamplingY,
                maskSign
            )
        }
        return if (maskSign) mask else 64 - mask
    }

    /** Returns one luma-domain segment-compound mask value.
     * 
     * @param primarySample the primary higher-precision predictor
     * @param secondarySample the secondary higher-precision predictor
     * @param bitDepth the decoded sample bit depth
     * @param postRoundBits the fractional predictor bits retained by each input
     * @return one luma-domain segment-compound mask value
     */
    private fun lumaSegmentCompoundMaskValue(
        primarySample: Int,
        secondarySample: Int,
        bitDepth: Int,
        postRoundBits: Int
    ): Int {
        val roundedDifference = roundShift(
            abs(primarySample.toLong() - secondarySample),
            postRoundBits + bitDepth - 8
        )
        val scaledDifference = roundedDifference shr 4
        return min(38 + scaledDifference, 64)
    }

    /** Returns one chroma segment-compound mask value derived from the luma-domain segment mask.
     * 
     * @param segmentMask the luma-domain segment mask
     * @param segmentMaskWidth the luma-domain segment mask width
     * @param segmentMaskHeight the luma-domain segment mask height
     * @param x the chroma-plane sample X coordinate inside the block
     * @param y the chroma-plane sample Y coordinate inside the block
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param maskSign whether the decoded segment mask uses inverted source order
     * @return one chroma segment-compound mask value
     */
    private fun chromaSegmentCompoundMaskValue(
        segmentMask: IntArray,
        segmentMaskWidth: Int,
        segmentMaskHeight: Int,
        x: Int,
        y: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        maskSign: Boolean
    ): Int {
        val lumaX = x shl subsamplingX
        val lumaY = y shl subsamplingY
        val horizontalSpan = 1 shl subsamplingX
        val verticalSpan = 1 shl subsamplingY
        var sum = 0
        for (yy in 0..<verticalSpan) {
            val sampleY = min(segmentMaskHeight - 1, lumaY + yy)
            for (xx in 0..<horizontalSpan) {
                val sampleX = min(segmentMaskWidth - 1, lumaX + xx)
                sum += segmentMask[sampleY * segmentMaskWidth + sampleX]
            }
        }
        val subsamplingShift = subsamplingX + subsamplingY
        val roundingOffset = if (subsamplingShift == 0) 0 else 1 shl (subsamplingShift - 1)
        val invertedMaskAdjustment = if (maskSign && subsamplingShift != 0) 1 else 0
        return (sum + roundingOffset - invertedMaskAdjustment) shr subsamplingShift
    }

    /** Returns the joint compound primary weight for one decoded reference pair.
     * 
     * @param frameHeader the current frame header
     * @param referenceHeader0 the primary reference frame header
     * @param referenceHeader1 the secondary reference frame header
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @return the joint compound primary weight for one decoded reference pair
     */
    private fun jointCompoundWeight(
        frameHeader: FrameHeader,
        referenceHeader0: FrameHeader,
        referenceHeader1: FrameHeader,
        orderHintBits: Int
    ): Int {
        val distance1 = min(
            abs(
                orderHintDifference(
                    orderHintBits,
                    referenceHeader0.frameOffset(),
                    frameHeader.frameOffset()
                )
            ),
            31
        )
        val distance0 = min(
            abs(
                orderHintDifference(
                    orderHintBits,
                    referenceHeader1.frameOffset(),
                    frameHeader.frameOffset()
                )
            ),
            31
        )
        val order = distance0 <= distance1
        val quantDistanceWeight =
            arrayOf<IntArray>(intArrayOf(2, 3), intArrayOf(2, 5), intArrayOf(2, 7))
        val quantDistanceLookup = arrayOf<IntArray>(
            intArrayOf(9, 7),
            intArrayOf(11, 5),
            intArrayOf(12, 4),
            intArrayOf(13, 3)
        )
        var k: Int
        k = 0
        while (k < quantDistanceWeight.size) {
            val c0 = quantDistanceWeight[k][if (order) 1 else 0]
            val c1 = quantDistanceWeight[k][if (order) 0 else 1]
            val distance0Scaled = distance0 * c0
            val distance1Scaled = distance1 * c1
            if ((distance0 > distance1 && distance0Scaled < distance1Scaled)
                || (distance0 <= distance1 && distance0Scaled > distance1Scaled)
            ) {
                break
            }
            k++
        }
        return quantDistanceLookup[k][if (order) 1 else 0]
    }

    /** Returns the wrapped order-hint difference `poc0 - poc1`.
     * 
     * @param orderHintBits the number of order-hint bits declared by the sequence
     * @param poc0 the minuend order hint
     * @param poc1 the subtrahend order hint
     * @return the wrapped order-hint difference `poc0 - poc1`
     */
    private fun orderHintDifference(orderHintBits: Int, poc0: Int, poc1: Int): Int {
        if (orderHintBits == 0) {
            return 0
        }
        val mask = 1 shl (orderHintBits - 1)
        val diff = poc0 - poc1
        return (diff and (mask - 1)) - (diff and mask)
    }

    /** Resolves the effective horizontal interpolation filter for one inter block.
     * 
     * @param header the decoded block header that owns the inter state
     * @param frameHeader the frame header that owns the block
     * @return the effective horizontal interpolation filter
     */
    private fun resolveHorizontalInterpolationFilter(
        header: BlockHeader,
        frameHeader: FrameHeader
    ): InterpolationFilter {
        if (frameHeader.subpelFilterMode() != InterpolationFilter.SWITCHABLE) {
            return frameHeader.subpelFilterMode()
        }
        val interpolationFilter = header.horizontalInterpolationFilter()
        check(!(interpolationFilter == null || !isConcreteInterpolationFilter(interpolationFilter))) { "Inter reconstruction requires a resolved horizontal interpolation filter" }
        return interpolationFilter
    }

    /** Resolves the effective vertical interpolation filter for one inter block.
     * 
     * @param header the decoded block header that owns the inter state
     * @param frameHeader the frame header that owns the block
     * @return the effective vertical interpolation filter
     */
    private fun resolveVerticalInterpolationFilter(
        header: BlockHeader,
        frameHeader: FrameHeader
    ): InterpolationFilter {
        if (frameHeader.subpelFilterMode() != InterpolationFilter.SWITCHABLE) {
            return frameHeader.subpelFilterMode()
        }
        val interpolationFilter = header.verticalInterpolationFilter()
        check(!(interpolationFilter == null || !isConcreteInterpolationFilter(interpolationFilter))) { "Inter reconstruction requires a resolved vertical interpolation filter" }
        return interpolationFilter
    }

    /** Returns one compatible stored reference surface for the supplied internal LAST..ALTREF
     * reference position.
     * 
     * @param referenceSurfaceSnapshots the stored reference surfaces addressable by AV1 slot index
     * @param frameHeader the frame header that owns the block
     * @param chromaFormat the active decoded chroma layout
     * @param bitDepth the decoded sample bit depth of the current frame
     * @param referenceFramePosition the internal LAST..ALTREF reference position
     * @return one compatible stored reference surface for the supplied reference position
     */
    private fun requireReferenceSurfaceSnapshot(
        referenceSurfaceSnapshots: Array<ReferenceSurfaceSnapshot?>?,
        frameHeader: FrameHeader,
        chromaFormat: Av1ChromaFormat,
        bitDepth: Int,
        referenceFramePosition: Int
    ): ReferenceSurfaceSnapshot {
        check(!(referenceFramePosition < 0 || referenceFramePosition >= 7)) { "Inter reconstruction requires one valid reference-frame position" }
        val referenceSlot = frameHeader.referenceFrameIndex(referenceFramePosition)
        check(!(referenceSlot < 0 || referenceSlot >= referenceSurfaceSnapshots!!.size)) { "Inter reconstruction requires one populated stored reference surface" }

        val referenceSurfaceSnapshot = referenceSurfaceSnapshots[referenceSlot]
        checkNotNull(referenceSurfaceSnapshot) { "Inter reconstruction requires one populated stored reference surface" }

        val referencePlanes = referenceSurfaceSnapshot.decodedPlanes
        check(referencePlanes.bitDepth() == bitDepth) { "Inter reconstruction requires a stored reference surface whose bit depth matches the current frame" }
        check(referencePlanes.chromaFormat() == chromaFormat) { "Inter reconstruction requires matching reference chroma format: $chromaFormat" }
        return referenceSurfaceSnapshot
    }

    /** Reconstructs one luma palette block directly into the destination plane.
     * 
     * Palette indices are stored as two packed 4-bit entries per byte in raster order with the
     * invisible right and bottom edges already replicated by the syntax layer.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param header the decoded block header that owns the luma palette state
     * @param lumaX the zero-based horizontal luma sample coordinate
     * @param lumaY the zero-based vertical luma sample coordinate
     * @param lumaWidth the stored luma block width in pixels
     * @param lumaHeight the stored luma block height in pixels
     */
    private fun reconstructLumaPalette(
        lumaPlane: MutablePlaneBuffer,
        header: BlockHeader,
        lumaX: Int,
        lumaY: Int,
        lumaWidth: Int,
        lumaHeight: Int
    ) {
        reconstructPalettePlane(
            lumaPlane,
            lumaX,
            lumaY,
            lumaWidth,
            lumaHeight,
            header.size().widthPixels(),
            header,
            0
        )
    }

    /** Reconstructs one chroma palette block directly into the destination planes.
     * 
     * Packed palette indices follow the geometry exposed by `TileBlockHeaderReader`; only the
     * visible `YUV420`, `YUV422`, or `YUV444` chroma footprint is written to the output planes.
     * 
     * @param chromaUPlane the mutable chroma U destination plane
     * @param chromaVPlane the mutable chroma V destination plane
     * @param header the decoded block header that owns the chroma palette state
     * @param visibleChromaWidth the exact visible chroma width in pixels
     * @param visibleChromaHeight the exact visible chroma height in pixels
     */
    private fun reconstructChromaPalette(
        chromaUPlane: MutablePlaneBuffer,
        chromaVPlane: MutablePlaneBuffer,
        header: BlockHeader,
        chromaFormat: Av1ChromaFormat,
        visibleChromaWidth: Int,
        visibleChromaHeight: Int
    ) {
        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val chromaX = chromaBlockX(header, chromaSubsamplingX)
        val chromaY = chromaBlockY(header, chromaSubsamplingY)
        val fullChromaWidth = codedChromaBlockWidth(header, chromaSubsamplingX)
        reconstructPalettePlane(
            chromaUPlane,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            fullChromaWidth,
            header,
            1
        )
        reconstructPalettePlane(
            chromaVPlane,
            chromaX,
            chromaY,
            visibleChromaWidth,
            visibleChromaHeight,
            fullChromaWidth,
            header,
            2
        )
    }

    /** Reconstructs one palette-mapped sample plane directly into the destination plane.
     * 
     * @param plane the mutable destination plane
     * @param startX the zero-based horizontal destination coordinate
     * @param startY the zero-based vertical destination coordinate
     * @param visibleWidth the visible block width in pixels before destination-frame clipping
     * @param visibleHeight the visible block height in pixels before destination-frame clipping
     * @param packedFullWidth the coded palette-map width in pixels used to compute the packed stride
     * @param header the block header that owns the palette state
     * @param palettePlane the palette plane index, where `0` is Y, `1` is U, and `2` is V
     */
    private fun reconstructPalettePlane(
        plane: MutablePlaneBuffer,
        startX: Int,
        startY: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        packedFullWidth: Int,
        header: BlockHeader,
        palettePlane: Int
    ) {
        val nonNullHeader = requireNotNullCompat(header, "header")
        require(palettePlane in 0..2) { "Palette plane index out of range: $palettePlane" }
        check(!(packedFullWidth <= 0 || (packedFullWidth and 1) != 0)) { "Palette map width must be a positive even value: $packedFullWidth" }
        val paletteSampleCount = if (palettePlane == 0)
            nonNullHeader.yPaletteSampleCount()
        else
            nonNullHeader.uvPaletteSampleCount()
        check(paletteSampleCount >= packedFullWidth * visibleHeight) { "Packed palette index map is shorter than the visible footprint" }
        val paletteSize =
            if (palettePlane == 0) nonNullHeader.yPaletteSize() else nonNullHeader.uvPaletteSize()

        val clippedVisibleWidth = min(visibleWidth, plane.width() - startX)
        val clippedVisibleHeight = min(visibleHeight, plane.height() - startY)
        if (clippedVisibleWidth <= 0 || clippedVisibleHeight <= 0) {
            return
        }

        for (y in 0..<clippedVisibleHeight) {
            val sampleRow = y * packedFullWidth
            for (x in 0..<clippedVisibleWidth) {
                val sampleIndex = sampleRow + x
                val paletteIndex = if (palettePlane == 0)
                    nonNullHeader.yPaletteIndex(sampleIndex)
                else
                    nonNullHeader.uvPaletteIndex(sampleIndex)
                check(!(paletteIndex < 0 || paletteIndex >= paletteSize)) { "Palette index out of range: $paletteIndex" }
                val paletteColor = when (palettePlane) {
                    0 -> nonNullHeader.yPaletteColor(paletteIndex)
                    1 -> nonNullHeader.uPaletteColor(paletteIndex)
                    2 -> nonNullHeader.vPaletteColor(paletteIndex)
                    else -> throw AssertionError("Unreachable palette plane: $palettePlane")
                }
                plane.setSample(startX + x, startY + y, paletteColor)
            }
        }
    }

    /** Clamps one integer value to the supplied inclusive bounds.
     * 
     * @param value the value to clamp
     * @param minimum the inclusive lower bound
     * @param maximum the inclusive upper bound
     * @return the clamped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(maximum, value))
    }

    /** Reconstructs decoded luma residuals into the destination plane.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param residualLayout the decoded luma residual layout
     * @param header the decoded block header that owns the residuals
     * @param frameHeader the frame header that owns the active quantization state
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructLumaResiduals(
        lumaPlane: MutablePlaneBuffer,
        residualLayout: ResidualLayout,
        header: BlockHeader,
        frameHeader: FrameHeader,
        strictStdCompliance: Boolean
    ) {
        val qIndex = blockQIndex(header, frameHeader)
        val quantization = frameHeader.quantization()
        for (unitIndex in 0..<residualLayout.lumaUnitCount()) {
            reconstructLumaResidualUnit(
                lumaPlane,
                residualLayout.lumaUnit(unitIndex),
                qIndex,
                quantization,
                strictStdCompliance
            )
        }
    }

    /** Reconstructs intra luma by predicting and applying residuals per transform unit.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param residualLayout the decoded luma residual layout
     * @param header the decoded block header that owns the residuals
     * @param frameHeader the frame header that owns the active quantization state
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructIntraLuma(
        lumaPlane: MutablePlaneBuffer,
        residualLayout: ResidualLayout,
        header: BlockHeader,
        frameHeader: FrameHeader,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        tileBounds: TileSampleBounds,
        strictStdCompliance: Boolean
    ) {
        val yMode =
            requireNotNullCompat(header.yMode(), "header.yMode()")
        val qIndex = blockQIndex(header, frameHeader)
        val quantization = frameHeader.quantization()
        for (unitIndex in 0..<residualLayout.lumaUnitCount()) {
            val residualUnit = residualLayout.lumaUnit(unitIndex)
            val predictionX = residualUnit.position().x4 shl 2
            val predictionY = residualUnit.position().y4 shl 2
            val predictionWidth = residualUnit.size().widthPixels()
            val predictionHeight = residualUnit.size().heightPixels()
            intraPredictor.predictLuma(
                lumaPlane,
                predictionX,
                predictionY,
                predictionWidth,
                predictionHeight,
                yMode,
                header.yAngle(),
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                availableDirectionalTopReferenceLength(
                    lumaPlane,
                    predictionX,
                    predictionY,
                    predictionWidth,
                    predictionHeight,
                    tileBounds.lumaEndX,
                    header.size(),
                    header.position().x4 shl 2,
                    header.position().y4 shl 2,
                    0,
                    0
                ),
                availableDirectionalLeftReferenceLength(
                    lumaPlane,
                    predictionX,
                    predictionY,
                    predictionWidth,
                    predictionHeight,
                    tileBounds.lumaEndY,
                    header.size(),
                    header.position().x4 shl 2,
                    header.position().y4 shl 2,
                    0,
                    0
                ),
                tileBounds.lumaStartX,
                tileBounds.lumaStartY,
                tileBounds.lumaEndX,
                tileBounds.lumaEndY
            )
            reconstructLumaResidualUnit(
                lumaPlane,
                residualUnit,
                qIndex,
                quantization,
                strictStdCompliance
            )
        }
    }

    /** Reconstructs filter-intra luma by predicting and applying residuals per transform unit.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param residualLayout the decoded luma residual layout
     * @param header the decoded block header that owns the residuals
     * @param frameHeader the frame header that owns the active quantization state
     * @param filterIntraMode the decoded filter-intra mode
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructFilterIntraLuma(
        lumaPlane: MutablePlaneBuffer,
        residualLayout: ResidualLayout,
        header: BlockHeader,
        frameHeader: FrameHeader,
        filterIntraMode: FilterIntraMode,
        tileBounds: TileSampleBounds,
        strictStdCompliance: Boolean
    ) {
        val qIndex = blockQIndex(header, frameHeader)
        val quantization = frameHeader.quantization()
        for (unitIndex in 0..<residualLayout.lumaUnitCount()) {
            val residualUnit = residualLayout.lumaUnit(unitIndex)
            intraPredictor.predictFilterIntraLuma(
                lumaPlane,
                residualUnit.position().x4 shl 2,
                residualUnit.position().y4 shl 2,
                residualUnit.size().widthPixels(),
                residualUnit.size().heightPixels(),
                filterIntraMode,
                tileBounds.lumaStartX,
                tileBounds.lumaStartY,
                tileBounds.lumaEndX,
                tileBounds.lumaEndY
            )
            reconstructLumaResidualUnit(
                lumaPlane,
                residualUnit,
                qIndex,
                quantization,
                strictStdCompliance
            )
        }
    }

    /** Reconstructs one decoded luma residual unit into the destination plane.
     * 
     * @param lumaPlane the mutable luma destination plane
     * @param residualUnit the decoded residual unit
     * @param qIndex the block-local quantizer index after delta-q updates
     * @param quantization the frame-level quantization state
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructLumaResidualUnit(
        lumaPlane: MutablePlaneBuffer,
        residualUnit: TransformResidualUnit,
        qIndex: Int,
        quantization: QuantizationInfo,
        strictStdCompliance: Boolean
    ) {
        if (residualUnit.allZero()) {
            return
        }
        val destinationX = residualUnit.position().x4 shl 2
        val destinationY = residualUnit.position().y4 shl 2
        val writtenWidth = min(residualUnit.size().widthPixels(), lumaPlane.width() - destinationX)
        val writtenHeight =
            min(residualUnit.size().heightPixels(), lumaPlane.height() - destinationY)
        if (residualUnit.endOfBlockIndex() == 0 && residualUnit.transformType() == TransformType.DCT_DCT) {
            val dequantizedDcCoefficient = LumaDequantizer.dequantizeDcCoefficient(
                residualUnit,
                qIndex,
                quantization.yDcDelta(),
                lumaPlane.bitDepth(),
                quantization.useQuantizationMatrices(),
                quantization.quantizationMatrixY()
            )
            inverseTransformer.reconstructAndAddDcOnlyDctBlock(
                lumaPlane,
                destinationX,
                destinationY,
                residualUnit.size(),
                lumaPlane.bitDepth(),
                writtenWidth,
                writtenHeight,
                strictStdCompliance,
                dequantizedDcCoefficient
            )
            return
        }

        val dequantizedCoefficients = inverseTransformer.coefficientBuffer(residualUnit.size())
        LumaDequantizer.dequantize(
            residualUnit,
            qIndex,
            quantization.yDcDelta(),
            lumaPlane.bitDepth(),
            quantization.useQuantizationMatrices(),
            quantization.quantizationMatrixY(),
            dequantizedCoefficients
        )
        inverseTransformer.reconstructAndAddResidualBlock(
            lumaPlane,
            destinationX,
            destinationY,
            residualUnit.size(),
            residualUnit.transformType(),
            lumaPlane.bitDepth(),
            writtenWidth,
            writtenHeight,
            strictStdCompliance,
            dequantizedCoefficients
        )
    }

    /** Returns the block-local quantizer index after segment-level delta-q.
     * 
     * `BlockHeader.qIndex()` carries the superblock delta-q runtime value. AV1 applies the active
     * segment delta on top of that value when deriving dequantizers for the block.
     * 
     * @param header the decoded block header
     * @param frameHeader the frame header that owns the active segmentation state
     * @return the block-local quantizer index after segment-level delta-q
     */
    private fun blockQIndex(header: BlockHeader, frameHeader: FrameHeader): Int {
        val segmentation = frameHeader.segmentation()
        if (!segmentation.enabled()) {
            return header.qIndex()
        }
        return QuantizerTables.clampedQIndex(
            header.qIndex() + segmentation.segment(header.segmentId()).deltaQ()
        )
    }

    /** Returns the available top-edge directional reference length for one transform prediction.
     * 
     * @param plane the mutable plane whose written samples are tracked
     * @param x the plane-local transform X coordinate
     * @param y the plane-local transform Y coordinate
     * @param width the transform width in plane samples
     * @param height the transform height in plane samples
     * @param rightBoundary the exclusive sample column available to this tile
     * @param blockSize the luma-grid size of the owning coded block
     * @param blockX the owning block's plane-local X coordinate
     * @param blockY the owning block's plane-local Y coordinate
     * @param subsamplingX the plane's horizontal chroma-subsampling shift
     * @param subsamplingY the plane's vertical chroma-subsampling shift
     * @return the available top-edge directional reference length
     */
    private fun availableDirectionalTopReferenceLength(
        plane: MutablePlaneBuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rightBoundary: Int,
        blockSize: BlockSize,
        blockX: Int,
        blockY: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Int {
        var availableLength = max(0, min(width, rightBoundary - x))
        if (availableLength < width) {
            return availableLength
        }
        if (!permitsTopRightExtensionWithinBlock(
                x,
                y,
                width,
                blockSize,
                blockX,
                blockY,
                subsamplingX,
                subsamplingY
            )
        ) {
            return availableLength
        }
        val maximumExtraLength = min(width, height)
        for (extra in 0..<maximumExtraLength) {
            if (x + width + extra >= rightBoundary) {
                break
            }
            if (!isDirectionalReferenceSampleDecoded(
                    plane,
                    x + width + extra,
                    y - 1
                )
            ) {
                break
            }
            availableLength++
        }
        return availableLength
    }

    /** Returns the available left-edge directional reference length for one transform prediction.
     * 
     * @param plane the mutable plane whose written samples are tracked
     * @param x the plane-local transform X coordinate
     * @param y the plane-local transform Y coordinate
     * @param width the transform width in plane samples
     * @param height the transform height in plane samples
     * @param bottomBoundary the exclusive sample row available to this tile
     * @param blockSize the luma-grid size of the owning coded block
     * @param blockX the owning block's plane-local X coordinate
     * @param blockY the owning block's plane-local Y coordinate
     * @param subsamplingX the plane's horizontal chroma-subsampling shift
     * @param subsamplingY the plane's vertical chroma-subsampling shift
     * @return the available left-edge directional reference length
     */
    private fun availableDirectionalLeftReferenceLength(
        plane: MutablePlaneBuffer,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        bottomBoundary: Int,
        blockSize: BlockSize,
        blockX: Int,
        blockY: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Int {
        var availableLength = max(0, min(height, bottomBoundary - y))
        if (availableLength < height) {
            return availableLength
        }
        if (!permitsBottomLeftExtensionWithinBlock(
                x,
                y,
                height,
                blockSize,
                blockX,
                blockY,
                subsamplingX,
                subsamplingY
            )
        ) {
            return availableLength
        }
        val maximumExtraLength = min(width, height)
        for (extra in 0..<maximumExtraLength) {
            if (y + height + extra >= bottomBoundary) {
                break
            }
            if (!isDirectionalReferenceSampleDecoded(
                    plane,
                    x - 1,
                    y + height + extra
                )
            ) {
                break
            }
            availableLength++
        }
        return availableLength
    }

    /** Returns whether the owning block permits top-right references beyond one transform's top edge.
     * 
     * AV1 decodes blocks wider than 64 luma samples as 64-wide regions in raster order. A sample
     * may therefore already have been written while still being unavailable to a transform that
     * would cross one of those causal region boundaries.
     * 
     * @param transformX the transform's plane-local X coordinate
     * @param transformY the transform's plane-local Y coordinate
     * @param transformWidth the transform width in plane samples
     * @param blockSize the luma-grid size of the owning coded block
     * @param blockX the owning block's plane-local X coordinate
     * @param blockY the owning block's plane-local Y coordinate
     * @param subsamplingX the plane's horizontal chroma-subsampling shift
     * @param subsamplingY the plane's vertical chroma-subsampling shift
     * @return whether top-right extension is permitted within the owning block
     */
    private fun permitsTopRightExtensionWithinBlock(
        transformX: Int,
        transformY: Int,
        transformWidth: Int,
        blockSize: BlockSize,
        blockX: Int,
        blockY: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Boolean {
        val rowOffset4 = (transformY - blockY) shr 2
        if (rowOffset4 <= 0) {
            return true
        }

        val columnOffset4 = (transformX - blockX) shr 2
        val transformWidth4 = transformWidth shr 2
        val blockWidth4 = max(1, blockSize.width4() shr subsamplingX)
        if (blockSize.widthPixels() <= 64) {
            return columnOffset4 + transformWidth4 < blockWidth4
        }

        val regionWidth4 = 16 shr subsamplingX
        val regionHeight4 = 16 shr subsamplingY
        if (rowOffset4 == regionHeight4 && columnOffset4 + transformWidth4 == regionWidth4) {
            return true
        }
        return columnOffset4 % regionWidth4 + transformWidth4 < regionWidth4
    }

    /** Returns whether the owning block permits bottom-left references beyond one transform's left edge.
     * 
     * @param transformX the transform's plane-local X coordinate
     * @param transformY the transform's plane-local Y coordinate
     * @param transformHeight the transform height in plane samples
     * @param blockSize the luma-grid size of the owning coded block
     * @param blockX the owning block's plane-local X coordinate
     * @param blockY the owning block's plane-local Y coordinate
     * @param subsamplingX the plane's horizontal chroma-subsampling shift
     * @param subsamplingY the plane's vertical chroma-subsampling shift
     * @return whether bottom-left extension is permitted within the owning block
     */
    private fun permitsBottomLeftExtensionWithinBlock(
        transformX: Int,
        transformY: Int,
        transformHeight: Int,
        blockSize: BlockSize,
        blockX: Int,
        blockY: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Boolean {
        val columnOffset4 = (transformX - blockX) shr 2
        if (columnOffset4 <= 0) {
            return true
        }

        if (blockSize.widthPixels() > 64) {
            val regionWidth4 = 16 shr subsamplingX
            val columnOffsetWithinRegion4 = columnOffset4 % regionWidth4
            if (columnOffsetWithinRegion4 == 0) {
                val rowOffset4 = (transformY - blockY) shr 2
                val regionHeight4 = 16 shr subsamplingY
                val blockHeight4 = max(1, blockSize.height4() shr subsamplingY)
                val availableRegionHeight4 = min(blockHeight4, regionHeight4)
                val transformHeight4 = transformHeight shr 2
                return rowOffset4 % regionHeight4 + transformHeight4 < availableRegionHeight4
            }
        }
        return false
    }

    /** Returns whether one directional reference sample has already been reconstructed.
     * 
     * @param plane the mutable plane whose written samples are tracked
     * @param sampleX the plane-local reference sample X coordinate
     * @param sampleY the plane-local reference sample Y coordinate
     * @return whether the reference sample is causally available
     */
    private fun isDirectionalReferenceSampleDecoded(
        plane: MutablePlaneBuffer,
        sampleX: Int,
        sampleY: Int
    ): Boolean {
        return plane.hasWrittenSample(sampleX, sampleY)
    }

    /** Reconstructs intra chroma by predicting and applying residuals per transform unit.
     * 
     * @param chromaUPlane the mutable chroma U destination plane
     * @param chromaVPlane the mutable chroma V destination plane
     * @param residualLayout the decoded chroma residual layout
     * @param header the decoded block header that owns the residuals
     * @param frameHeader the frame header that owns the active quantization state
     * @param chromaFormat the active decoded chroma layout
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     */
    private fun reconstructIntraChroma(
        chromaUPlane: MutablePlaneBuffer,
        chromaVPlane: MutablePlaneBuffer,
        transformLayout: TransformLayout,
        residualLayout: ResidualLayout,
        header: BlockHeader,
        frameHeader: FrameHeader,
        chromaFormat: Av1ChromaFormat,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        tileBounds: TileSampleBounds,
        strictStdCompliance: Boolean
    ) {
        val uvMode =
            requireNotNullCompat(header.uvMode(), "header.uvMode()")
        val quantization = frameHeader.quantization()
        val qIndex = blockQIndex(header, frameHeader)
        reconstructIntraChromaPlane(
            chromaUPlane,
            transformLayout,
            residualLayout,
            true,
            header,
            uvMode,
            header.uvAngle(),
            intraEdgeFilterEnabled,
            smoothEdgeReferences,
            qIndex,
            quantization,
            chromaFormat,
            chromaUPlane,
            tileBounds,
            strictStdCompliance
        )
        reconstructIntraChromaPlane(
            chromaVPlane,
            transformLayout,
            residualLayout,
            false,
            header,
            uvMode,
            header.uvAngle(),
            intraEdgeFilterEnabled,
            smoothEdgeReferences,
            qIndex,
            quantization,
            chromaFormat,
            chromaVPlane,
            tileBounds,
            strictStdCompliance
        )
    }

    /** Reconstructs one intra chroma plane per transform unit.
     * 
     * @param chromaPlane the mutable destination chroma plane
     * @param transformLayout the decoded chroma transform layout
     * @param residualLayout the decoded transform residual layout
     * @param chromaU whether to reconstruct the U plane rather than the V plane
     * @param header the decoded block header that owns the transform units
     * @param uvMode the chroma intra prediction mode
     * @param uvAngle the derived chroma directional prediction angle
     * @param intraEdgeFilterEnabled whether directional intra-edge filtering is enabled by the sequence header
     * @param smoothEdgeReferences whether the neighboring reference edges are marked as smooth predictors
     * @param qIndex the block-local quantizer index after delta-q updates
     * @param quantization the frame-level quantization state
     * @param chromaFormat the active decoded chroma layout
     * @param referencePlane the mutable plane whose written samples are tracked
     * @param tileBounds the tile-local sample boundaries used by intra prediction references
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructIntraChromaPlane(
        chromaPlane: MutablePlaneBuffer,
        transformLayout: TransformLayout,
        residualLayout: ResidualLayout,
        chromaU: Boolean,
        header: BlockHeader,
        uvMode: UvIntraPredictionMode,
        uvAngle: Int,
        intraEdgeFilterEnabled: Boolean,
        smoothEdgeReferences: Boolean,
        qIndex: Int,
        quantization: QuantizationInfo,
        chromaFormat: Av1ChromaFormat,
        referencePlane: MutablePlaneBuffer,
        tileBounds: TileSampleBounds,
        strictStdCompliance: Boolean
    ) {
        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        val blockX = chromaBlockX(header, chromaSubsamplingX)
        val blockY = chromaBlockY(header, chromaSubsamplingY)
        val appliedResiduals = resetAppliedChromaResiduals(residualLayout.chromaUnitCount())
        for (transformIndex in 0..<transformLayout.chromaUnitCount()) {
            val transformUnit = transformLayout.chromaUnit(transformIndex)
            val predictionX = transformUnit.position.x4 shl (2 - chromaSubsamplingX)
            val predictionY = transformUnit.position.y4 shl (2 - chromaSubsamplingY)
            val predictionWidth = transformUnit.size.widthPixels()
            val predictionHeight = transformUnit.size.heightPixels()
            intraPredictor.predictChroma(
                chromaPlane,
                predictionX,
                predictionY,
                predictionWidth,
                predictionHeight,
                uvMode,
                uvAngle,
                intraEdgeFilterEnabled,
                smoothEdgeReferences,
                availableDirectionalTopReferenceLength(
                    referencePlane,
                    predictionX,
                    predictionY,
                    predictionWidth,
                    predictionHeight,
                    tileBounds.chromaEndX,
                    header.size(),
                    blockX,
                    blockY,
                    chromaSubsamplingX,
                    chromaSubsamplingY
                ),
                availableDirectionalLeftReferenceLength(
                    referencePlane,
                    predictionX,
                    predictionY,
                    predictionWidth,
                    predictionHeight,
                    tileBounds.chromaEndY,
                    header.size(),
                    blockX,
                    blockY,
                    chromaSubsamplingX,
                    chromaSubsamplingY
                ),
                tileBounds.chromaStartX,
                tileBounds.chromaStartY,
                tileBounds.chromaEndX,
                tileBounds.chromaEndY
            )
            for (i in 0..<residualLayout.chromaUnitCount()) {
                val residualUnit = chromaResidualUnit(residualLayout, i, chromaU)
                if (!appliedResiduals[i] && sameTransformUnit(transformUnit, residualUnit)) {
                    reconstructChromaResidualUnit(
                        chromaPlane,
                        residualUnit,
                        chromaU,
                        qIndex,
                        quantization,
                        chromaSubsamplingX,
                        chromaSubsamplingY,
                        strictStdCompliance
                    )
                    appliedResiduals[i] = true
                }
            }
        }

        for (i in 0..<residualLayout.chromaUnitCount()) {
            if (!appliedResiduals[i]) {
                reconstructChromaResidualUnit(
                    chromaPlane,
                    chromaResidualUnit(residualLayout, i, chromaU),
                    chromaU,
                    qIndex,
                    quantization,
                    chromaSubsamplingX,
                    chromaSubsamplingY,
                    strictStdCompliance
                )
            }
        }
    }

    /** Returns reusable zero-filled flags for one block's chroma residual units.
     * 
     * @param requiredLength the number of residual-unit flags required by the current block
     * @return reusable zero-filled flag storage
     */
    private fun resetAppliedChromaResiduals(requiredLength: Int): BooleanArray {
        if (appliedChromaResiduals.size < requiredLength) {
            appliedChromaResiduals = BooleanArray(requiredLength)
        } else {
            fillCompat(appliedChromaResiduals, 0, requiredLength, false)
        }
        return appliedChromaResiduals
    }

    /** Returns whether one residual unit maps exactly to one transform unit.
     * 
     * @param transformUnit the transform unit
     * @param residualUnit the residual unit
     * @return whether both units share the same position and transform size
     */
    private fun sameTransformUnit(
        transformUnit: TransformUnit,
        residualUnit: TransformResidualUnit
    ): Boolean {
        return transformUnit.position == residualUnit.position()
                && transformUnit.size == residualUnit.size()
    }

    /** Reconstructs decoded chroma residuals into the destination planes.
     * 
     * @param chromaUPlane the mutable chroma U destination plane
     * @param chromaVPlane the mutable chroma V destination plane
     * @param residualLayout the decoded residual layout
     * @param frameHeader the frame header that owns the active quantization state
     * @param chromaFormat the active decoded chroma layout
     * @param qIndex the block-local quantizer index after delta-q updates
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructChromaResiduals(
        chromaUPlane: MutablePlaneBuffer,
        chromaVPlane: MutablePlaneBuffer,
        residualLayout: ResidualLayout,
        frameHeader: FrameHeader,
        chromaFormat: Av1ChromaFormat,
        qIndex: Int,
        strictStdCompliance: Boolean
    ) {
        val quantization = frameHeader.quantization()
        reconstructChromaPlaneResiduals(
            chromaUPlane,
            residualLayout,
            true,
            qIndex,
            quantization,
            chromaFormat,
            strictStdCompliance
        )
        reconstructChromaPlaneResiduals(
            chromaVPlane,
            residualLayout,
            false,
            qIndex,
            quantization,
            chromaFormat,
            strictStdCompliance
        )
    }

    /** Reconstructs one chroma-plane residual array into the supplied destination plane.
     * 
     * @param chromaPlane the mutable destination chroma plane
     * @param residualLayout the decoded transform residual layout
     * @param chromaU whether to reconstruct the U plane rather than the V plane
     * @param qIndex the block-local quantizer index after delta-q updates
     * @param quantization the frame-level quantization state
     * @param chromaFormat the active decoded chroma layout
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructChromaPlaneResiduals(
        chromaPlane: MutablePlaneBuffer,
        residualLayout: ResidualLayout,
        chromaU: Boolean,
        qIndex: Int,
        quantization: QuantizationInfo,
        chromaFormat: Av1ChromaFormat,
        strictStdCompliance: Boolean
    ) {
        val chromaSubsamplingX = chromaSubsamplingX(chromaFormat)
        val chromaSubsamplingY = chromaSubsamplingY(chromaFormat)
        for (unitIndex in 0..<residualLayout.chromaUnitCount()) {
            reconstructChromaResidualUnit(
                chromaPlane,
                chromaResidualUnit(residualLayout, unitIndex, chromaU),
                chromaU,
                qIndex,
                quantization,
                chromaSubsamplingX,
                chromaSubsamplingY,
                strictStdCompliance
            )
        }
    }

    /** Returns one plane-specific chroma residual unit.
     * 
     * @param residualLayout the decoded residual layout
     * @param index the zero-based chroma unit index
     * @param chromaU whether to select the U plane rather than the V plane
     * @return the selected chroma residual unit
     */
    private fun chromaResidualUnit(
        residualLayout: ResidualLayout,
        index: Int,
        chromaU: Boolean
    ): TransformResidualUnit {
        return if (chromaU) residualLayout.chromaUUnit(index) else residualLayout.chromaVUnit(index)
    }

    /** Reconstructs one decoded chroma residual unit into the destination plane.
     * 
     * @param chromaPlane the mutable destination chroma plane
     * @param residualUnit the decoded residual unit
     * @param chromaU whether the residual belongs to the U plane rather than the V plane
     * @param qIndex the block-local quantizer index after delta-q updates
     * @param quantization the frame-level quantization state
     * @param chromaSubsamplingX the horizontal chroma subsampling shift
     * @param chromaSubsamplingY the vertical chroma subsampling shift
     * @param strictStdCompliance whether malformed transform values must be rejected
     */
    private fun reconstructChromaResidualUnit(
        chromaPlane: MutablePlaneBuffer,
        residualUnit: TransformResidualUnit,
        chromaU: Boolean,
        qIndex: Int,
        quantization: QuantizationInfo,
        chromaSubsamplingX: Int,
        chromaSubsamplingY: Int,
        strictStdCompliance: Boolean
    ) {
        if (residualUnit.allZero()) {
            return
        }
        val dcDelta = if (chromaU) quantization.uDcDelta() else quantization.vDcDelta()
        val acDelta = if (chromaU) quantization.uAcDelta() else quantization.vAcDelta()
        val quantizationMatrix =
            if (chromaU) quantization.quantizationMatrixU() else quantization.quantizationMatrixV()
        val destinationX = residualUnit.position().x4 shl (2 - chromaSubsamplingX)
        val destinationY = residualUnit.position().y4 shl (2 - chromaSubsamplingY)
        val writtenWidth =
            min(residualUnit.size().widthPixels(), chromaPlane.width() - destinationX)
        val writtenHeight =
            min(residualUnit.size().heightPixels(), chromaPlane.height() - destinationY)
        if (residualUnit.endOfBlockIndex() == 0 && residualUnit.transformType() == TransformType.DCT_DCT) {
            val dequantizedDcCoefficient = ChromaDequantizer.dequantizeDcCoefficient(
                residualUnit,
                qIndex,
                dcDelta,
                chromaPlane.bitDepth(),
                quantization.useQuantizationMatrices(),
                quantizationMatrix
            )
            inverseTransformer.reconstructAndAddDcOnlyDctBlock(
                chromaPlane,
                destinationX,
                destinationY,
                residualUnit.size(),
                chromaPlane.bitDepth(),
                writtenWidth,
                writtenHeight,
                strictStdCompliance,
                dequantizedDcCoefficient
            )
            return
        }

        val dequantizedCoefficients = inverseTransformer.coefficientBuffer(residualUnit.size())
        ChromaDequantizer.dequantize(
            residualUnit,
            qIndex,
            dcDelta,
            acDelta,
            chromaPlane.bitDepth(),
            quantization.useQuantizationMatrices(),
            quantizationMatrix,
            dequantizedCoefficients
        )
        inverseTransformer.reconstructAndAddResidualBlock(
            chromaPlane,
            destinationX,
            destinationY,
            residualUnit.size(),
            residualUnit.transformType(),
            chromaPlane.bitDepth(),
            writtenWidth,
            writtenHeight,
            strictStdCompliance,
            dequantizedCoefficients
        )
    }

    /** Returns the horizontal chroma subsampling shift for one decoded chroma format.
     * 
     * @param chromaFormat the active decoded chroma layout
     * @return the horizontal chroma subsampling shift for one decoded chroma format
     */
    private fun chromaSubsamplingX(chromaFormat: Av1ChromaFormat): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
        }
    }

    /** Returns the vertical chroma subsampling shift for one decoded chroma format.
     * 
     * @param chromaFormat the active decoded chroma layout
     * @return the vertical chroma subsampling shift for one decoded chroma format
     */
    private fun chromaSubsamplingY(chromaFormat: Av1ChromaFormat): Int {
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420 -> 1
        }
    }

    /** Returns the chroma-plane dimension corresponding to one visible luma span.
     * 
     * @param lumaDimension the visible luma span in pixels
     * @param subsamplingShift the chroma subsampling shift for the axis
     * @return the corresponding chroma-plane dimension
     */
    private fun chromaDimension(lumaDimension: Int, subsamplingShift: Int): Int {
        return (lumaDimension + (1 shl subsamplingShift) - 1) shr subsamplingShift
    }

    /** Returns the subsampled luma footprint stored for one CFL block before normative padding.
     * 
     * AV1 stores each reconstructed luma transform at its full transform size. A partial frame-edge
     * block may therefore populate either part or all of its chroma transform footprint depending
     * on the decoded luma transform sizes.
     * 
     * @param transformLayout the decoded transform layout for the owning block
     * @param lumaX the luma origin of the shared chroma footprint
     * @param lumaY the luma origin of the shared chroma footprint
     * @param codedChromaWidth the complete chroma prediction width
     * @param codedChromaHeight the complete chroma prediction height
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @return the populated chroma-domain footprint before edge padding
     */
    private fun cflStoredSize(
        transformLayout: TransformLayout,
        lumaX: Int,
        lumaY: Int,
        codedChromaWidth: Int,
        codedChromaHeight: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): CflStoredSize {
        if (transformLayout.lumaUnitCount() == 0) {
            return CflStoredSize(codedChromaWidth, codedChromaHeight)
        }

        var storedLumaEndX = lumaX
        var storedLumaEndY = lumaY
        for (unitIndex in 0..<transformLayout.lumaUnitCount()) {
            val unit = transformLayout.lumaUnit(unitIndex)
            storedLumaEndX = max(
                storedLumaEndX,
                (unit.position.x4 shl 2) + unit.size.widthPixels()
            )
            storedLumaEndY = max(
                storedLumaEndY,
                (unit.position.y4 shl 2) + unit.size.heightPixels()
            )
        }
        val storedWidth = clamp(
            chromaDimension(storedLumaEndX - lumaX, subsamplingX),
            1,
            codedChromaWidth
        )
        val storedHeight = clamp(
            chromaDimension(storedLumaEndY - lumaY, subsamplingY),
            1,
            codedChromaHeight
        )
        return CflStoredSize(storedWidth, storedHeight)
    }

    /** Returns the luma-grid origin for one chroma block span.
     * 
     * @param position4 the luma-grid coordinate of the syntax block
     * @param size4 the luma-grid span of the syntax block
     * @param subsamplingShift the chroma subsampling shift for the axis
     * @return the luma-grid origin for the shared chroma footprint
     */
    private fun chromaOrigin4(position4: Int, size4: Int, subsamplingShift: Int): Int {
        if (subsamplingShift == 0 || size4 > subsamplingShift) {
            return position4
        }
        val mask = (1 shl subsamplingShift) - 1
        return position4 and mask.inv()
    }

    /** Returns the chroma-plane X coordinate for one block.
     * 
     * @param header the decoded block header
     * @param subsamplingShift the horizontal chroma subsampling shift
     * @return the chroma-plane X coordinate for the shared chroma footprint
     */
    private fun chromaBlockX(header: BlockHeader, subsamplingShift: Int): Int {
        return chromaOrigin4(
            header.position().x4,
            header.size().width4(),
            subsamplingShift
        ) shl (2 - subsamplingShift)
    }

    /** Returns the chroma-plane Y coordinate for one block.
     * 
     * @param header the decoded block header
     * @param subsamplingShift the vertical chroma subsampling shift
     * @return the chroma-plane Y coordinate for the shared chroma footprint
     */
    private fun chromaBlockY(header: BlockHeader, subsamplingShift: Int): Int {
        return chromaOrigin4(
            header.position().y4,
            header.size().height4(),
            subsamplingShift
        ) shl (2 - subsamplingShift)
    }

    /** Returns the luma-plane X coordinate that backs one chroma block.
     * 
     * @param header the decoded block header
     * @param subsamplingShift the horizontal chroma subsampling shift
     * @return the luma-plane X coordinate for the shared chroma footprint
     */
    private fun chromaLumaBlockX(header: BlockHeader, subsamplingShift: Int): Int {
        return chromaOrigin4(header.position().x4, header.size().width4(), subsamplingShift) shl 2
    }

    /** Returns the luma-plane Y coordinate that backs one chroma block.
     * 
     * @param header the decoded block header
     * @param subsamplingShift the vertical chroma subsampling shift
     * @return the luma-plane Y coordinate for the shared chroma footprint
     */
    private fun chromaLumaBlockY(header: BlockHeader, subsamplingShift: Int): Int {
        return chromaOrigin4(header.position().y4, header.size().height4(), subsamplingShift) shl 2
    }

    /** Returns the visible chroma width for one decoded block.
     * 
     * @param header the decoded block header
     * @param transformLayout the decoded transform layout for the block
     * @param subsamplingShift the horizontal chroma subsampling shift
     * @return the visible chroma width for the shared chroma footprint
     */
    private fun visibleChromaBlockWidth(
        header: BlockHeader,
        transformLayout: TransformLayout,
        subsamplingShift: Int
    ): Int {
        val originX4 = chromaOrigin4(header.position().x4, header.size().width4(), subsamplingShift)
        val visibleLumaEndX = (header.position().x4 shl 2) + transformLayout.visibleWidthPixels()
        return chromaDimension(visibleLumaEndX - (originX4 shl 2), subsamplingShift)
    }

    /** Returns the visible chroma height for one decoded block.
     * 
     * @param header the decoded block header
     * @param transformLayout the decoded transform layout for the block
     * @param subsamplingShift the vertical chroma subsampling shift
     * @return the visible chroma height for the shared chroma footprint
     */
    private fun visibleChromaBlockHeight(
        header: BlockHeader,
        transformLayout: TransformLayout,
        subsamplingShift: Int
    ): Int {
        val originY4 =
            chromaOrigin4(header.position().y4, header.size().height4(), subsamplingShift)
        val visibleLumaEndY = (header.position().y4 shl 2) + transformLayout.visibleHeightPixels()
        return chromaDimension(visibleLumaEndY - (originY4 shl 2), subsamplingShift)
    }

    /** Returns the coded chroma width for one decoded block.
     * 
     * @param header the decoded block header
     * @param subsamplingShift the horizontal chroma subsampling shift
     * @return the coded chroma width for the shared chroma footprint
     */
    private fun codedChromaBlockWidth(header: BlockHeader, subsamplingShift: Int): Int {
        val originX4 = chromaOrigin4(header.position().x4, header.size().width4(), subsamplingShift)
        val codedLumaEndX = (header.position().x4 + header.size().width4()) shl 2
        return chromaDimension(codedLumaEndX - (originX4 shl 2), subsamplingShift)
    }

    /** Returns the coded chroma height for one decoded block.
     * 
     * @param header the decoded block header
     * @param subsamplingShift the vertical chroma subsampling shift
     * @return the coded chroma height for the shared chroma footprint
     */
    private fun codedChromaBlockHeight(header: BlockHeader, subsamplingShift: Int): Int {
        val originY4 =
            chromaOrigin4(header.position().y4, header.size().height4(), subsamplingShift)
        val codedLumaEndY = (header.position().y4 + header.size().height4()) shl 2
        return chromaDimension(codedLumaEndY - (originY4 shl 2), subsamplingShift)
    }

    companion object {
        /** The number of coefficients in one 8-tap interpolation kernel. */
        private const val INTER_FILTER_TAP_COUNT = 8

        /** The signed source-sample offset of the first tap relative to the integer source position. */
        private const val INTER_FILTER_START_OFFSET = 3

        /** The AV1 fixed-filter normalization shift. */
        private const val INTER_FILTER_BITS = 6

        /** The supported AV1 fractional phases for fixed interpolation filters. */
        private const val INTER_FILTER_PHASES = 16

        /** The fixed-point precision of an AV1 reference-frame scale factor. */
        private const val REFERENCE_SCALE_BITS = 14

        /** The identity AV1 reference-frame scale factor. */
        val REFERENCE_SCALE_IDENTITY = 1 shl REFERENCE_SCALE_BITS

        /** The fixed-point precision of scaled inter-prediction source coordinates. */
        private const val SCALED_INTER_SUBPEL_BITS = 10

        /** The largest luma block dimension permitted for inter-intra prediction. */
        private const val INTER_INTRA_MAX_BLOCK_DIMENSION = 32

        /** The identity reference geometry used by unscaled inter and same-frame `intrabc` prediction. */
        val IDENTITY_REFERENCE_SCALE =
            ReferenceScale(REFERENCE_SCALE_IDENTITY, REFERENCE_SCALE_IDENTITY, false)

        /** The AV1 OBMC blend masks indexed by overlap length `1, 2, 4, 8, 16, 32, 64`. */
        val OBMC_MASKS: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(64),
            intArrayOf(45, 64),
            intArrayOf(39, 50, 59, 64),
            intArrayOf(36, 42, 48, 53, 57, 61, 64, 64),
            intArrayOf(34, 37, 40, 43, 46, 49, 52, 54, 56, 58, 60, 61, 64, 64, 64, 64),
            intArrayOf(
                33, 35, 36, 38, 40, 41, 43, 44, 45, 47, 48, 50, 51, 52, 53, 55,
                56, 57, 58, 59, 60, 60, 61, 62, 64, 64, 64, 64, 64, 64, 64, 64
            ),
            intArrayOf(
                33, 34, 35, 35, 36, 37, 38, 39, 40, 40, 41, 42, 43, 44, 44, 44,
                45, 46, 47, 47, 48, 49, 50, 51, 51, 51, 52, 52, 53, 54, 55, 56,
                56, 56, 57, 57, 58, 58, 59, 60, 60, 60, 60, 60, 61, 62, 62, 62,
                62, 62, 63, 63, 63, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64
            )
        )

        /** The default AV1 regular 8-tap subpel filters in `dav1d_mc_subpel_filters` order. */
        val REGULAR_SUBPEL_FILTERS: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(0, 1, -3, 63, 4, -1, 0, 0),
            intArrayOf(0, 1, -5, 61, 9, -2, 0, 0),
            intArrayOf(0, 1, -6, 58, 14, -4, 1, 0),
            intArrayOf(0, 1, -7, 55, 19, -5, 1, 0),
            intArrayOf(0, 1, -7, 51, 24, -6, 1, 0),
            intArrayOf(0, 1, -8, 47, 29, -6, 1, 0),
            intArrayOf(0, 1, -7, 42, 33, -6, 1, 0),
            intArrayOf(0, 1, -7, 38, 38, -7, 1, 0),
            intArrayOf(0, 1, -6, 33, 42, -7, 1, 0),
            intArrayOf(0, 1, -6, 29, 47, -8, 1, 0),
            intArrayOf(0, 1, -6, 24, 51, -7, 1, 0),
            intArrayOf(0, 1, -5, 19, 55, -7, 1, 0),
            intArrayOf(0, 1, -4, 14, 58, -6, 1, 0),
            intArrayOf(0, 0, -2, 9, 61, -5, 1, 0),
            intArrayOf(0, 0, -1, 4, 63, -3, 1, 0)
        )

        /** The default AV1 smooth 8-tap subpel filters in `dav1d_mc_subpel_filters` order. */
        val SMOOTH_SUBPEL_FILTERS: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(0, 1, 14, 31, 17, 1, 0, 0),
            intArrayOf(0, 0, 13, 31, 18, 2, 0, 0),
            intArrayOf(0, 0, 11, 31, 20, 2, 0, 0),
            intArrayOf(0, 0, 10, 30, 21, 3, 0, 0),
            intArrayOf(0, 0, 9, 29, 22, 4, 0, 0),
            intArrayOf(0, 0, 8, 28, 23, 5, 0, 0),
            intArrayOf(0, -1, 8, 27, 24, 6, 0, 0),
            intArrayOf(0, -1, 7, 26, 26, 7, -1, 0),
            intArrayOf(0, 0, 6, 24, 27, 8, -1, 0),
            intArrayOf(0, 0, 5, 23, 28, 8, 0, 0),
            intArrayOf(0, 0, 4, 22, 29, 9, 0, 0),
            intArrayOf(0, 0, 3, 21, 30, 10, 0, 0),
            intArrayOf(0, 0, 2, 20, 31, 11, 0, 0),
            intArrayOf(0, 0, 2, 18, 31, 13, 0, 0),
            intArrayOf(0, 0, 1, 17, 31, 14, 1, 0)
        )

        /** The default AV1 sharp 8-tap subpel filters in `dav1d_mc_subpel_filters` order. */
        val SHARP_SUBPEL_FILTERS: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(-1, 1, -3, 63, 4, -1, 1, 0),
            intArrayOf(-1, 3, -6, 62, 8, -3, 2, -1),
            intArrayOf(-1, 4, -9, 60, 13, -5, 3, -1),
            intArrayOf(-2, 5, -11, 58, 19, -7, 3, -1),
            intArrayOf(-2, 5, -11, 54, 24, -9, 4, -1),
            intArrayOf(-2, 5, -12, 50, 30, -10, 4, -1),
            intArrayOf(-2, 5, -12, 45, 35, -11, 5, -1),
            intArrayOf(-2, 6, -12, 40, 40, -12, 6, -2),
            intArrayOf(-1, 5, -11, 35, 45, -12, 5, -2),
            intArrayOf(-1, 4, -10, 30, 50, -12, 5, -2),
            intArrayOf(-1, 4, -9, 24, 54, -11, 5, -2),
            intArrayOf(-1, 3, -7, 19, 58, -11, 5, -2),
            intArrayOf(-1, 3, -5, 13, 60, -9, 4, -1),
            intArrayOf(-1, 2, -3, 8, 62, -6, 3, -1),
            intArrayOf(0, 1, -1, 4, 63, -3, 1, -1)
        )

        /** The reduced-width AV1 regular 8-tap subpel filters used when the sampled axis is at most
         * four samples wide. */
        val SMALL_REGULAR_SUBPEL_FILTERS: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(0, 0, -2, 63, 4, -1, 0, 0),
            intArrayOf(0, 0, -4, 61, 9, -2, 0, 0),
            intArrayOf(0, 0, -5, 58, 14, -3, 0, 0),
            intArrayOf(0, 0, -6, 55, 19, -4, 0, 0),
            intArrayOf(0, 0, -6, 51, 24, -5, 0, 0),
            intArrayOf(0, 0, -7, 47, 29, -5, 0, 0),
            intArrayOf(0, 0, -6, 42, 33, -5, 0, 0),
            intArrayOf(0, 0, -6, 38, 38, -6, 0, 0),
            intArrayOf(0, 0, -5, 33, 42, -6, 0, 0),
            intArrayOf(0, 0, -5, 29, 47, -7, 0, 0),
            intArrayOf(0, 0, -5, 24, 51, -6, 0, 0),
            intArrayOf(0, 0, -4, 19, 55, -6, 0, 0),
            intArrayOf(0, 0, -3, 14, 58, -5, 0, 0),
            intArrayOf(0, 0, -2, 9, 61, -4, 0, 0),
            intArrayOf(0, 0, -1, 4, 63, -2, 0, 0)
        )

        /** The reduced-width AV1 smooth 8-tap subpel filters used when the sampled axis is at most
         * four samples wide. */
        val SMALL_SMOOTH_SUBPEL_FILTERS: Array<IntArray> = arrayOf<IntArray>(
            intArrayOf(0, 0, 15, 31, 17, 1, 0, 0),
            intArrayOf(0, 0, 13, 31, 18, 2, 0, 0),
            intArrayOf(0, 0, 11, 31, 20, 2, 0, 0),
            intArrayOf(0, 0, 10, 30, 21, 3, 0, 0),
            intArrayOf(0, 0, 9, 29, 22, 4, 0, 0),
            intArrayOf(0, 0, 8, 28, 23, 5, 0, 0),
            intArrayOf(0, 0, 7, 27, 24, 6, 0, 0),
            intArrayOf(0, 0, 6, 26, 26, 6, 0, 0),
            intArrayOf(0, 0, 6, 24, 27, 7, 0, 0),
            intArrayOf(0, 0, 5, 23, 28, 8, 0, 0),
            intArrayOf(0, 0, 4, 22, 29, 9, 0, 0),
            intArrayOf(0, 0, 3, 21, 30, 10, 0, 0),
            intArrayOf(0, 0, 2, 20, 31, 11, 0, 0),
            intArrayOf(0, 0, 2, 18, 31, 13, 0, 0),
            intArrayOf(0, 0, 1, 17, 31, 15, 0, 0)
        )
    }
}
