// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.av1.Av1FrameType
import org.glavo.avif.internal.av1.decode.BlockNeighborContext.ProvisionalInterModeContext
import org.glavo.avif.internal.av1.decode.BlockNeighborContext.ProvisionalInterModeContext.ProvisionalMotionVectorCandidate
import org.glavo.avif.internal.av1.decode.BlockNeighborContext.SegmentPrediction
import org.glavo.avif.internal.av1.decode.TileDecodeContext.BlockSyntaxState
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode
import org.glavo.avif.internal.av1.model.CompoundPredictionType
import org.glavo.avif.internal.av1.model.FilterIntraMode
import org.glavo.avif.internal.av1.model.FrameHeader.FrameSize
import org.glavo.avif.internal.av1.model.FrameHeader.GlobalMotionType
import org.glavo.avif.internal.av1.model.FrameHeader.InterpolationFilter
import org.glavo.avif.internal.av1.model.FrameHeader.SegmentData
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode
import org.glavo.avif.internal.av1.model.InterMotionVector
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode
import org.glavo.avif.internal.av1.model.MotionMode
import org.glavo.avif.internal.av1.model.MotionVector
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode


/** Decodes one leaf block header inside a tile partition tree.
 * 
 * The reader combines entropy-coded syntax with tile-local state and neighboring block contexts,
 * and optionally publishes the decoded state to the supplied neighbor context. */
class TileBlockHeaderReader constructor(
    tileContext: TileDecodeContext,
    strictStdCompliance: Boolean = false
) {
    /** The tile-local decode state that owns the active frame and sequence headers. */
    private val tileContext: TileDecodeContext

    /** The typed syntax reader used to consume entropy-coded syntax elements. */
    private val syntaxReader: TileSyntaxReader

    /** Whether decoded conformance values outside their specified ranges must be rejected. */
    private val strictStdCompliance: Boolean

    init {
        val nonNullTileContext =
            requireNotNullCompat(tileContext, "tileContext")
        this.tileContext = nonNullTileContext
        this.syntaxReader = TileSyntaxReader(nonNullTileContext, strictStdCompliance)
        this.strictStdCompliance = strictStdCompliance
    }

    /** Returns the tile-local decode state that owns this block header reader.
     * 
     * @return the tile-local decode state that owns this block header reader
     */
    fun tileContext(): TileDecodeContext {
        return tileContext
    }

    /** Decodes one leaf block header and optionally updates the supplied neighbor context.
     * 
     * Callers that need to decode follow-up syntax using the pre-block neighbor state can disable
     * the automatic update and commit the header after later syntax stages finish.
     * 
     * @param position the local tile-relative block origin
     * @param size the block size to decode
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @param updateNeighborContext whether the neighbor context should be updated before returning
     * @return the decoded leaf block header
     */
    /** Decodes one leaf block header and updates the supplied neighbor context.
     * 
     * @param position the local tile-relative block origin
     * @param size the block size to decode
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @return the decoded leaf block header
     */
    fun read(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext,
        updateNeighborContext: Boolean = true
    ): BlockHeader {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        val nonNullNeighborContext =
            requireNotNullCompat(neighborContext, "neighborContext")
        val blockSyntaxState = tileContext.blockSyntaxState()
        blockSyntaxState.enterSuperblock(nonNullPosition, tileContext.superblockSize())
        val segmentation = tileContext.frameHeader().segmentation()
        val frameType = tileContext.frameHeader().frameType()
        val hasChroma = hasChroma(nonNullPosition, nonNullSize)
        var segmentId = 0
        var segmentPredicted = false
        if (segmentation.enabled()) {
            if (!segmentation.updateMap()) {
                segmentId = tileContext.referenceSegmentId(nonNullPosition, nonNullSize)
            } else if (segmentation.preskip()) {
                val segmentReadResult = readSegmentIdBeforeSkip(
                    nonNullPosition,
                    nonNullSize,
                    nonNullNeighborContext
                )
                segmentId = segmentReadResult.segmentId
                segmentPredicted = segmentReadResult.segmentPredicted
            }
        }
        val segmentDataBeforeSkip =
            if (segmentation.enabled() && segmentation.updateMap() && !segmentation.preskip())
                null
            else
                segmentation.segment(segmentId)
        var skipMode = false
        if ((frameType == Av1FrameType.INTER || frameType == Av1FrameType.SWITCH)
            && canDecodeSkipMode(nonNullSize, segmentDataBeforeSkip)
        ) {
            skipMode =
                syntaxReader.readSkipModeFlag(nonNullNeighborContext.skipModeContext(nonNullPosition))
        }
        var skip = skipMode || (segmentDataBeforeSkip != null && segmentDataBeforeSkip.skip())
        if (!skip) {
            val skipContext = nonNullNeighborContext.skipContext(nonNullPosition)
            skip = syntaxReader.readSkipFlag(skipContext)
        }
        val segmentData: SegmentData?
        if (segmentation.enabled() && segmentation.updateMap() && !segmentation.preskip()) {
            val segmentReadResult = readSegmentIdAfterSkip(
                nonNullPosition,
                nonNullSize,
                nonNullNeighborContext,
                skip
            )
            segmentId = segmentReadResult.segmentId
            segmentPredicted = segmentReadResult.segmentPredicted
            segmentData = segmentation.segment(segmentId)
        } else {
            checkNotNull(segmentDataBeforeSkip) { "Segment data must be known before skip when postskip decoding is disabled" }
            segmentData = segmentDataBeforeSkip
        }
        val cdefIndex = resolveCdefIndex(nonNullPosition, nonNullSize, skip, blockSyntaxState)
        val qIndex = applyDeltaSyntax(nonNullPosition, nonNullSize, skip, blockSyntaxState)
        val deltaLfValues = blockSyntaxState.currentDeltaLfValuesView()

        var useIntrabc = false
        val intra: Boolean
        if ((frameType == Av1FrameType.INTER || frameType == Av1FrameType.SWITCH) && skipMode) {
            intra = false
        } else if (frameType == Av1FrameType.INTER || frameType == Av1FrameType.SWITCH) {
            val segmentReferenceFrame = segmentData.referenceFrame()
            if (segmentReferenceFrame >= 0) {
                intra = segmentReferenceFrame == SEGMENT_REFERENCE_INTRA_FRAME
            } else if (segmentData.globalMotion()) {
                intra = false
            } else {
                intra = syntaxReader.readIntraBlockFlag(
                    nonNullNeighborContext.intraContext(nonNullPosition)
                )
            }
        } else if (tileContext.frameHeader().allowIntrabc()) {
            useIntrabc = syntaxReader.readUseIntrabcFlag()
            intra = !useIntrabc
        } else {
            intra = true
        }
        var compoundReference = false
        var referenceFrame0: Int = NO_REFERENCE_FRAME
        var referenceFrame1: Int = NO_REFERENCE_FRAME
        var singleInterMode: SingleInterPredictionMode? = null
        var compoundInterMode: CompoundInterPredictionMode? = null
        var drlIndex = -1
        var motionVector0: InterMotionVector? = null
        var motionVector1: InterMotionVector? = null
        var motionMode = MotionMode.SIMPLE
        var horizontalInterpolationFilter: InterpolationFilter? = null
        var verticalInterpolationFilter: InterpolationFilter? = null
        var compoundPredictionType: CompoundPredictionType? = null
        var compoundMaskSign = false
        var compoundWedgeIndex = -1
        var interIntra = false
        var interIntraMode: InterIntraPredictionMode? = null
        var interIntraWedge = false
        var interIntraWedgeIndex = -1
        if (!intra && !useIntrabc) {
            val selection = readInterReferenceSelection(
                nonNullPosition,
                nonNullSize,
                nonNullNeighborContext,
                segmentData,
                skipMode
            )
            compoundReference = selection.compoundReference
            referenceFrame0 = selection.referenceFrame0
            referenceFrame1 = selection.referenceFrame1
            if (skipMode) {
                compoundInterMode = CompoundInterPredictionMode.NEARESTMV_NEARESTMV
                drlIndex = 0
                val provisionalContext =
                    nonNullNeighborContext.provisionalInterModeContext(
                        nonNullPosition,
                        nonNullSize,
                        compoundReference,
                        referenceFrame0,
                        referenceFrame1,
                        globalMotionVector(nonNullPosition, nonNullSize, referenceFrame0),
                        globalMotionVector(nonNullPosition, nonNullSize, referenceFrame1),
                        tileContext.frameHeader().globalMotion(referenceFrame0).type(),
                        tileContext.frameHeader().globalMotion(referenceFrame1).type()
                    )
                val candidate =
                    provisionalContext.motionVectorCandidate(0)
                motionVector0 = resolveCompoundMotionVector0(
                    compoundInterMode,
                    candidate,
                    globalMotionVector(nonNullPosition, nonNullSize, referenceFrame0)
                )
                motionVector1 = resolveCompoundMotionVector1(
                    compoundInterMode,
                    candidate,
                    globalMotionVector(nonNullPosition, nonNullSize, referenceFrame1)
                )
            } else {
                val interModeSelection = readInterModeSelection(
                    nonNullPosition,
                    nonNullSize,
                    nonNullNeighborContext,
                    compoundReference,
                    referenceFrame0,
                    referenceFrame1,
                    segmentData
                )
                singleInterMode = interModeSelection.singleInterMode
                compoundInterMode = interModeSelection.compoundInterMode
                drlIndex = interModeSelection.drlIndex
                motionVector0 = interModeSelection.motionVector0
                motionVector1 = interModeSelection.motionVector1
            }
            if (compoundReference) {
                if (skipMode) {
                    compoundPredictionType = CompoundPredictionType.AVERAGE
                } else {
                    val compoundPredictionSelection = readCompoundPredictionSelection(
                        nonNullPosition,
                        nonNullSize,
                        nonNullNeighborContext,
                        referenceFrame0,
                        referenceFrame1
                    )
                    compoundPredictionType = compoundPredictionSelection.type
                    compoundMaskSign = compoundPredictionSelection.maskSign
                    compoundWedgeIndex = compoundPredictionSelection.wedgeIndex
                }
            }
            val canUseInterIntra = canDecodeInterIntra(nonNullSize, compoundReference)
            val useInterIntra = canUseInterIntra
                    && syntaxReader.readUseInterIntra(nonNullSize.yModeSizeContext())
            if (useInterIntra) {
                interIntra = true
                interIntraMode = syntaxReader.readInterIntraMode(nonNullSize.yModeSizeContext())
                val wedgeContext: Int = interIntraWedgeContext(nonNullSize)
                interIntraWedge = syntaxReader.readUseInterIntraWedge(wedgeContext)
                if (interIntraWedge) {
                    interIntraWedgeIndex = syntaxReader.readWedgeIndex(wedgeContext)
                }
            }
            if (!interIntra) {
                motionMode = readMotionMode(
                    nonNullPosition,
                    nonNullSize,
                    nonNullNeighborContext,
                    skipMode,
                    compoundReference,
                    referenceFrame0,
                    singleInterMode,
                    compoundInterMode
                )
            }
            val interpolationFilterSelection = readInterpolationFilterSelection(
                nonNullPosition,
                nonNullSize,
                nonNullNeighborContext,
                skipMode,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                singleInterMode,
                compoundInterMode,
                motionMode
            )
            horizontalInterpolationFilter =
                interpolationFilterSelection.horizontalInterpolationFilter
            verticalInterpolationFilter = interpolationFilterSelection.verticalInterpolationFilter
        }

        var yMode: LumaIntraPredictionMode? = null
        var uvMode: UvIntraPredictionMode? = null
        var yPaletteSize = 0
        var uvPaletteSize = 0
        var yPaletteColors: IntArray = EMPTY_INT_PAYLOAD
        var uPaletteColors: IntArray = EMPTY_INT_PAYLOAD
        var vPaletteColors: IntArray = EMPTY_INT_PAYLOAD
        var yPaletteIndices: ByteArray = EMPTY_BYTE_PAYLOAD
        var uvPaletteIndices: ByteArray = EMPTY_BYTE_PAYLOAD
        var filterIntraMode: FilterIntraMode? = null
        var yAngle = 0
        var uvAngle = 0
        var cflAlphaU = 0
        var cflAlphaV = 0
        if (useIntrabc) {
            val fallback = defaultIntrabcReferenceMotionVector(nonNullPosition)
            val predictor = nonNullNeighborContext.intrabcReferenceMotionVector(
                nonNullPosition,
                nonNullSize,
                fallback
            )
            motionVector0 = InterMotionVector.resolved(
                syntaxReader.readIntrabcMotionVectorResidual(
                    predictor
                )
            )
            yMode = LumaIntraPredictionMode.DC
            if (hasChroma) {
                uvMode = UvIntraPredictionMode.DC
            }
        } else if (intra) {
            if (frameType == Av1FrameType.INTER || frameType == Av1FrameType.SWITCH) {
                yMode = syntaxReader.readYMode(nonNullSize.yModeSizeContext())
            } else {
                yMode = syntaxReader.readKeyFrameYMode(
                    nonNullNeighborContext.aboveMode(nonNullPosition.x4),
                    nonNullNeighborContext.leftMode(nonNullPosition.y4)
                )
            }
            if (supportsAngleDelta(nonNullSize) && yMode.isDirectional) {
                yAngle = syntaxReader.readYAngleDelta(yMode)
            }
            if (hasChroma) {
                val cflAllowed = isCflAllowed(nonNullSize, segmentId)
                uvMode = syntaxReader.readUvMode(yMode, cflAllowed)
                if (uvMode == UvIntraPredictionMode.CFL) {
                    val cflAlpha = syntaxReader.readCflAlpha()
                    cflAlphaU = cflAlpha.alphaU
                    cflAlphaV = cflAlpha.alphaV
                } else if (supportsAngleDelta(nonNullSize) && uvMode.isDirectional) {
                    uvAngle = syntaxReader.readUvAngleDelta(uvMode)
                }
            }
            if (allowsPalette(nonNullSize)) {
                val paletteSizeContext = nonNullSize.paletteSizeContext()
                if (yMode == LumaIntraPredictionMode.DC) {
                    val paletteContext =
                        ((if (nonNullNeighborContext.abovePaletteSize(nonNullPosition.x4) > 0) 1 else 0)
                                + (if (nonNullNeighborContext.leftPaletteSize(nonNullPosition.y4) > 0) 1 else 0))
                    if (syntaxReader.readUseLumaPalette(paletteSizeContext, paletteContext)) {
                        yPaletteSize = syntaxReader.readPaletteSize(0, paletteSizeContext)
                        yPaletteColors = readPalettePlane(
                            0,
                            yPaletteSize,
                            nonNullPosition,
                            nonNullNeighborContext
                        )
                    }
                }
                if (hasChroma && uvMode == UvIntraPredictionMode.DC && syntaxReader.readUseChromaPalette(
                        if (yPaletteSize > 0) 1 else 0
                    )
                ) {
                    uvPaletteSize = syntaxReader.readPaletteSize(1, paletteSizeContext)
                    uPaletteColors =
                        readPalettePlane(1, uvPaletteSize, nonNullPosition, nonNullNeighborContext)
                    vPaletteColors = readChromaVPalette(uvPaletteSize)
                }
            }
            if (yPaletteSize == 0 && allowsFilterIntra(
                    nonNullSize,
                    yMode
                ) && syntaxReader.readUseFilterIntra(nonNullSize)
            ) {
                filterIntraMode = syntaxReader.readFilterIntraMode()
            }
        }

        if (yPaletteSize > 0) {
            yPaletteIndices = readPaletteIndices(0, yPaletteSize, nonNullPosition, nonNullSize)
        }
        if (uvPaletteSize > 0) {
            uvPaletteIndices = readPaletteIndices(1, uvPaletteSize, nonNullPosition, nonNullSize)
        }
        if (strictStdCompliance && motionVector0 != null) {
            validateMotionVector(motionVector0.vector)
            if (compoundReference) {
                validateMotionVector(
                    requireNotNullCompat(
                        motionVector1,
                        "motionVector1"
                    ).vector
                )
            }
        }
        val header = BlockHeader(
            nonNullPosition,
            nonNullSize,
            hasChroma,
            skip,
            skipMode,
            intra,
            useIntrabc,
            compoundReference,
            referenceFrame0,
            referenceFrame1,
            singleInterMode,
            compoundInterMode,
            drlIndex,
            motionVector0,
            motionVector1,
            motionMode,
            horizontalInterpolationFilter,
            verticalInterpolationFilter,
            compoundPredictionType,
            compoundMaskSign,
            compoundWedgeIndex,
            interIntra,
            interIntraMode,
            interIntraWedge,
            interIntraWedgeIndex,
            segmentPredicted,
            segmentId,
            cdefIndex,
            qIndex,
            deltaLfValues,
            yMode,
            uvMode,
            yPaletteSize,
            uvPaletteSize,
            yPaletteColors,
            uPaletteColors,
            vPaletteColors,
            yPaletteIndices,
            uvPaletteIndices,
            filterIntraMode,
            yAngle,
            uvAngle,
            cflAlphaU,
            cflAlphaV
        )
        if (updateNeighborContext) {
            nonNullNeighborContext.updateFromBlockHeader(header)
            nonNullNeighborContext.updateDefaultTransformContext(nonNullPosition, nonNullSize)
        }
        return header
    }

    /** Resolves the effective CDEF index for one block, decoding and caching it when needed.
     * 
     * @param position the local tile-relative block origin
     * @param size the current block size
     * @param skip whether the current block is skipped
     * @param blockSyntaxState the mutable tile-local block syntax state
     * @return the effective CDEF index for the current block, or `-1` when not yet known
     */
    private fun resolveCdefIndex(
        position: BlockPosition,
        size: BlockSize,
        skip: Boolean,
        blockSyntaxState: BlockSyntaxState
    ): Int {
        val cdef = tileContext.frameHeader().cdef()
        val quadrantIndex = cdefQuadrantIndex(position)
        val cachedIndex = blockSyntaxState.cdefIndex(quadrantIndex)
        if (skip) {
            return cachedIndex
        }
        if (cachedIndex >= 0) {
            return cachedIndex
        }

        val cdefIndex = if (cdef.bits() == 0) 0 else syntaxReader.readUnsignedBits(cdef.bits())
        fillCdefIndices(blockSyntaxState, quadrantIndex, size, cdefIndex)
        return cdefIndex
    }

    /** Applies superblock-level delta-q and delta-lf syntax when the current block starts a superblock.
     * 
     * @param position the local tile-relative block origin
     * @param size the current block size
     * @param skip whether the current block is skipped
     * @param blockSyntaxState the mutable tile-local block syntax state
     * @return the current luma AC quantizer index after any delta syntax was applied
     */
    private fun applyDeltaSyntax(
        position: BlockPosition,
        size: BlockSize,
        skip: Boolean,
        blockSyntaxState: BlockSyntaxState
    ): Int {
        if (!isSuperblockOrigin(position)) {
            return blockSyntaxState.currentQIndex()
        }

        val delta = tileContext.frameHeader().delta()
        val haveDeltaQ = delta.deltaQPresent()
                && (size.widthPixels() != tileContext.superblockSize() || size.heightPixels() != tileContext.superblockSize() || !skip)
        if (!haveDeltaQ) {
            return blockSyntaxState.currentQIndex()
        }

        val currentQIndex: Int = clip(
            blockSyntaxState.currentQIndex() + syntaxReader.readDeltaQValue(delta.deltaQResLog2()),
            1,
            255
        )
        blockSyntaxState.currentQIndex = currentQIndex
        if (delta.deltaLfPresent()) {
            val deltaLfCount = if (delta.deltaLfMulti())
                (if (tileContext.sequenceHeader()
                        .colorConfig().chromaFormat == Av1ChromaFormat.MONOCHROME
                ) 2 else 4)
            else
                1
            val contextOffset = if (delta.deltaLfMulti()) 1 else 0
            for (i in 0..<deltaLfCount) {
                val slot = if (contextOffset == 0) 0 else i
                val updatedValue: Int = clip(
                    blockSyntaxState.currentDeltaLfValue(slot)
                            + syntaxReader.readDeltaLfValue(
                        i + contextOffset,
                        delta.deltaLfResLog2()
                    ),
                    -63,
                    63
                )
                blockSyntaxState.setCurrentDeltaLfValue(slot, updatedValue)
            }
        }
        return currentQIndex
    }

    /** Returns whether the supplied block origin starts a new superblock.
     * 
     * @param position the local tile-relative block origin
     * @return whether the supplied block origin starts a new superblock
     */
    private fun isSuperblockOrigin(position: BlockPosition): Boolean {
        val superblockSize4 = tileContext.superblockSize() shr 2
        return position.x4 % superblockSize4 == 0 && position.y4 % superblockSize4 == 0
    }

    /** Returns the current CDEF quadrant index inside the active superblock.
     * 
     * @param position the local tile-relative block origin
     * @return the current CDEF quadrant index inside the active superblock
     */
    private fun cdefQuadrantIndex(position: BlockPosition): Int {
        if (tileContext.superblockSize() == 64) {
            return 0
        }
        return ((position.x4 and 16) shr 4) + ((position.y4 and 16) shr 3)
    }

    /** Fills the cached CDEF indices covered by the supplied block.
     * 
     * @param blockSyntaxState the mutable tile-local block syntax state
     * @param quadrantIndex the zero-based starting CDEF quadrant index
     * @param size the current block size
     * @param cdefIndex the decoded CDEF index
     */
    private fun fillCdefIndices(
        blockSyntaxState: BlockSyntaxState,
        quadrantIndex: Int,
        size: BlockSize,
        cdefIndex: Int
    ) {
        blockSyntaxState.setCdefIndex(quadrantIndex, cdefIndex)
        if (tileContext.superblockSize() == 64) {
            return
        }
        if (size.width4() > 16 && quadrantIndex + 1 < 4) {
            blockSyntaxState.setCdefIndex(quadrantIndex + 1, cdefIndex)
        }
        if (size.height4() > 16 && quadrantIndex + 2 < 4) {
            blockSyntaxState.setCdefIndex(quadrantIndex + 2, cdefIndex)
        }
        if (size.width4() == 32 && size.height4() == 32) {
            blockSyntaxState.setCdefIndex(3, cdefIndex)
        }
    }

    /** Returns whether the supplied block has chroma samples in the active frame layout.
     * 
     * @param position the local tile-relative block origin
     * @param size the block size to test
     * @return whether the supplied block has chroma samples in the active frame layout
     */
    private fun hasChroma(position: BlockPosition, size: BlockSize): Boolean {
        val chromaFormat = tileContext.sequenceHeader().colorConfig().chromaFormat
        if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
            return false
        }
        val subsamplingX =
            if (tileContext.sequenceHeader().colorConfig().chromaSubsamplingX) 1 else 0
        val subsamplingY =
            if (tileContext.sequenceHeader().colorConfig().chromaSubsamplingY) 1 else 0
        return (size.width4() > subsamplingX || (position.x4 and 1) != 0)
                && (size.height4() > subsamplingY || (position.y4 and 1) != 0)
    }

    /** Returns the AV1 fallback displacement-vector predictor for one intrabc block.
     * 
     * Blocks in the first superblock row of a tile reference an earlier horizontal region. Later
     * rows reference the superblock immediately above. The returned vector is expressed in
     * eighth-pel units even though intrabc displacement vectors have integer-pixel precision.
     * 
     * @param position the tile-relative block origin
     * @return the default intrabc displacement-vector predictor
     */
    private fun defaultIntrabcReferenceMotionVector(position: BlockPosition): MotionVector {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val superblockSize = tileContext.superblockSize()
        if ((nonNullPosition.y4 shl 2) < superblockSize) {
            return MotionVector(0, -(superblockSize + INTRABC_DELAY_PIXELS) * 8)
        }
        return MotionVector(-superblockSize * 8, 0)
    }

    /** Returns whether CFL syntax is available for the supplied block size in the active frame.
     * 
     * In lossless mode AV1 only allows CFL when the corresponding chroma block is 4x4. For
     * non-lossless blocks CFL is limited to luma partitions up to 32x32.
     * 
     * @param size the block size to test
     * @param segmentId the decoded segment identifier for the current block
     * @return whether CFL syntax is available for the supplied block size in the active frame
     */
    private fun isCflAllowed(size: BlockSize, segmentId: Int): Boolean {
        if (!tileContext.frameHeader().segmentation().lossless(segmentId)) {
            return size.widthPixels() <= 32 && size.heightPixels() <= 32
        }

        val subsamplingX =
            if (tileContext.sequenceHeader().colorConfig().chromaSubsamplingX) 1 else 0
        val subsamplingY =
            if (tileContext.sequenceHeader().colorConfig().chromaSubsamplingY) 1 else 0
        val chromaWidth4 = max(1, size.width4() shr subsamplingX)
        val chromaHeight4 = max(1, size.height4() shr subsamplingY)
        return chromaWidth4 == 1 && chromaHeight4 == 1
    }

    /** Returns whether compound-reference syntax is available for the supplied block and segment state.
     * 
     * @param size the block size to test
     * @param segmentData the final segment data for the current block
     * @return whether compound-reference syntax is available for the supplied block and segment state
     */
    private fun canDecodeCompoundReference(size: BlockSize, segmentData: SegmentData): Boolean {
        val nonNullSize = requireNotNullCompat(size, "size")
        val nonNullSegmentData = requireNotNullCompat(segmentData, "segmentData")
        return tileContext.frameHeader().switchableCompoundReferences()
                && min(
            nonNullSize.width4(),
            nonNullSize.height4()
        ) > 1 && nonNullSegmentData.referenceFrame() < 0 && !nonNullSegmentData.globalMotion() && !nonNullSegmentData.skip()
    }

    /** Returns whether inter-intra syntax is available for the supplied inter block state.
     * 
     * @param size the block size to test
     * @param compoundReference whether the current block uses compound references
     * @return whether inter-intra syntax is available for the supplied inter block state
     */
    private fun canDecodeInterIntra(
        size: BlockSize,
        compoundReference: Boolean
    ): Boolean {
        return tileContext.sequenceHeader().features().interIntra()
                && !compoundReference && supportsInterIntra(size)
    }

    /** Returns whether skip-mode syntax is available for the supplied block and segment state.
     * 
     * Postskip segmentation does not know the final segment features yet, so a `null` segment
     * state means the skip-mode gate should rely only on frame-level constraints.
     * 
     * @param size the block size to test
     * @param segmentData the already-known segment data, or `null` when postskip segmentation has
     * not decoded the final segment id yet
     * @return whether skip-mode syntax is available for the supplied block and segment state
     */
    private fun canDecodeSkipMode(size: BlockSize, segmentData: SegmentData?): Boolean {
        val nonNullSize = requireNotNullCompat(size, "size")
        return tileContext.frameHeader().skipModeEnabled()
                && min(nonNullSize.width4(), nonNullSize.height4()) > 1 && (segmentData == null
                || (!segmentData.globalMotion() && segmentData.referenceFrame() < 0 && !segmentData.skip()))
    }

    /** Decodes the inter reference selection for one non-intra block.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded block size
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @param segmentData the final segment data for the current block
     * @param skipMode whether skip mode is active for the current block
     * @return the decoded inter reference selection for one non-intra block
     */
    private fun readInterReferenceSelection(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext,
        segmentData: SegmentData,
        skipMode: Boolean
    ): InterReferenceSelection {
        if (skipMode) {
            return InterReferenceSelection(
                true,
                tileContext.frameHeader().skipModeReferenceIndex(0),
                tileContext.frameHeader().skipModeReferenceIndex(1)
            )
        }

        val compoundContext = neighborContext.compoundReferenceContext(position)
        val compoundReference = canDecodeCompoundReference(size, segmentData)
                && syntaxReader.readCompoundReferenceFlag(compoundContext)
        if (compoundReference) {
            return readCompoundReferenceSelection(position, neighborContext)
        }
        return InterReferenceSelection(
            false,
            readSingleReference(position, neighborContext, segmentData),
            NO_REFERENCE_FRAME
        )
    }

    /** Decodes the inter prediction mode and provisional dynamic-reference-list index for one block.
     * 
     * This stage uses the current bounded provisional candidate stack for `inter_mode` and `drl`
     * decoding. Candidate-only modes are promoted to final block vectors here, while `NEWMV`
     * components keep the candidate provisional until the residual has been decoded.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded block size
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @param compoundReference whether the current block uses compound references
     * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
     * @param segmentData the final segment data for the current block
     * @return the decoded inter prediction mode, dynamic-reference-list index, and motion-vector state
     */
    private fun readInterModeSelection(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        segmentData: SegmentData
    ): InterModeSelection {
        val provisionalContext =
            neighborContext.provisionalInterModeContext(
                position,
                size,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                globalMotionVector(position, size, referenceFrame0),
                if (compoundReference)
                    globalMotionVector(position, size, referenceFrame1)
                else
                    MotionVector.zero(),
                tileContext.frameHeader().globalMotion(referenceFrame0).type(),
                if (compoundReference)
                    tileContext.frameHeader().globalMotion(referenceFrame1).type()
                else
                    GlobalMotionType.IDENTITY
            )
        if (compoundReference) {
            val compoundInterMode =
                syntaxReader.readCompoundInterMode(provisionalContext.compoundInterModeContext())
            var drlIndex = 0
            if (compoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                if (provisionalContext.candidateCount() > 1) {
                    drlIndex += if (syntaxReader.readDrlBit(provisionalContext.drlContext(0))) 1 else 0
                    if (drlIndex == 1 && provisionalContext.candidateCount() > 2) {
                        drlIndex += if (syntaxReader.readDrlBit(provisionalContext.drlContext(1))) 1 else 0
                    }
                }
            } else if (compoundInterMode.usesNearMotionVector()) {
                drlIndex = 1
                if (provisionalContext.candidateCount() > 2) {
                    drlIndex += if (syntaxReader.readDrlBit(provisionalContext.drlContext(1))) 1 else 0
                    if (drlIndex == 2 && provisionalContext.candidateCount() > 3) {
                        drlIndex += if (syntaxReader.readDrlBit(provisionalContext.drlContext(2))) 1 else 0
                    }
                }
            }
            val candidate =
                provisionalContext.motionVectorCandidate(
                    motionVectorCandidateIndex(
                        compoundInterMode,
                        drlIndex
                    )
                )
            var motionVector0: InterMotionVector = resolveCompoundMotionVector0(
                compoundInterMode,
                candidate,
                globalMotionVector(position, size, referenceFrame0)
            )
            var motionVector1: InterMotionVector = resolveCompoundMotionVector1(
                compoundInterMode,
                candidate,
                globalMotionVector(position, size, referenceFrame1)
            )
            if (compoundInterMode == CompoundInterPredictionMode.NEWMV_NEARESTMV || compoundInterMode == CompoundInterPredictionMode.NEWMV_NEARMV || compoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                motionVector0 = decodeNewMotionVectorResidual(motionVector0)
            }
            if (compoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEWMV || compoundInterMode == CompoundInterPredictionMode.NEARMV_NEWMV || compoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                motionVector1 = decodeNewMotionVectorResidual(motionVector1)
            }
            return InterModeSelection(
                null,
                compoundInterMode,
                drlIndex,
                motionVector0,
                motionVector1
            )
        }

        if (segmentData.globalMotion() || segmentData.skip()) {
            val globalMotionVector: InterMotionVector = InterMotionVector.resolved(
                globalMotionVector(position, size, referenceFrame0)
            )
            return InterModeSelection(
                SingleInterPredictionMode.GLOBALMV,
                null,
                0,
                globalMotionVector,
                null
            )
        }

        val singleInterMode = syntaxReader.readSingleInterMode(
            provisionalContext.singleNewMvContext(),
            provisionalContext.singleGlobalMvContext(),
            provisionalContext.singleReferenceMvContext(),
            false,
            false
        )
        val drlIndex = when (singleInterMode) {
            SingleInterPredictionMode.GLOBALMV, SingleInterPredictionMode.NEARESTMV -> 0
            SingleInterPredictionMode.NEARMV -> readNearDrlIndex(provisionalContext)
            SingleInterPredictionMode.NEWMV -> readNewDrlIndex(provisionalContext)
        }
        var motionVector0: InterMotionVector = resolveSingleMotionVector(
            singleInterMode,
            provisionalContext.motionVectorCandidate(
                motionVectorCandidateIndex(
                    singleInterMode,
                    drlIndex
                )
            ),
            globalMotionVector(position, size, referenceFrame0)
        )
        if (singleInterMode == SingleInterPredictionMode.NEWMV) {
            motionVector0 = decodeNewMotionVectorResidual(motionVector0)
        }
        return InterModeSelection(singleInterMode, null, drlIndex, motionVector0, null)
    }

    /** Decodes the block-level inter motion-compensation mode.
     * 
     * The decoder reads the three-way motion-mode symbol when local warped motion has compatible
     * causal samples and the selected reference is not scaled; otherwise it reads the two-way
     * OBMC flag or returns simple prediction.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded block size
     * @param neighborContext the mutable neighbor context used for OBMC candidate availability
     * @param skipMode whether skip-mode syntax selected this block
     * @param compoundReference whether the block uses compound references
     * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
     * @param singleInterMode the decoded single-reference inter mode, or `null`
     * @param compoundInterMode the decoded compound inter mode, or `null`
     * @return the decoded motion-compensation mode
     */
    private fun readMotionMode(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext,
        skipMode: Boolean,
        compoundReference: Boolean,
        referenceFrame0: Int,
        singleInterMode: SingleInterPredictionMode?,
        compoundInterMode: CompoundInterPredictionMode?
    ): MotionMode {
        val frameHeader = tileContext.frameHeader()
        if (skipMode
            || !frameHeader.switchableMotionMode() || min(
                size.widthPixels(),
                size.heightPixels()
            ) < 8 || compoundReference
            || !neighborContext.hasOverlappableCandidates(position, size)
        ) {
            return MotionMode.SIMPLE
        }

        if (compoundInterMode != null) {
            return MotionMode.SIMPLE
        }
        if (singleInterMode == SingleInterPredictionMode.GLOBALMV && !frameHeader.forceIntegerMotionVectors() && (frameHeader.globalMotion(
                referenceFrame0
            ).type().ordinal
                    > GlobalMotionType.TRANSLATION.ordinal)
        ) {
            return MotionMode.SIMPLE
        }
        val referenceFrameHeader = tileContext.referenceFrameHeader(referenceFrame0)
        if (frameHeader.warpedMotion()
            && !frameHeader.forceIntegerMotionVectors() && (referenceFrameHeader == null
                    || !referenceFrameIsScaled(
                frameHeader.frameSize(),
                referenceFrameHeader.frameSize()
            ))
            && neighborContext.hasLocalWarpSamples(position, size, referenceFrame0)
        ) {
            return syntaxReader.readMotionMode(size.cdfIndex())
        }
        return if (syntaxReader.readUseObmc(size.cdfIndex())) MotionMode.OBMC else MotionMode.SIMPLE
    }

    /** Decodes the compound prediction blend type for one compound-reference block.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded block size
     * @param neighborContext the mutable neighbor context that supplies compound mask contexts
     * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order
     * @return the decoded compound prediction blend type
     */
    private fun readCompoundPredictionSelection(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext,
        referenceFrame0: Int,
        referenceFrame1: Int
    ): CompoundPredictionSelection {
        if (tileContext.sequenceHeader().features().maskedCompound()
            && syntaxReader.readUseMaskedCompound(neighborContext.maskedCompoundContext(position))
        ) {
            var wedgeIndex = -1
            val type: CompoundPredictionType?
            if (supportsCompoundWedge(size)) {
                val wedgeContext: Int = compoundWedgeContext(size)
                if (syntaxReader.readUseSegmentCompound(wedgeContext)) {
                    type = CompoundPredictionType.SEGMENT
                } else {
                    type = CompoundPredictionType.WEDGE
                    wedgeIndex = syntaxReader.readWedgeIndex(wedgeContext)
                }
            } else {
                type = CompoundPredictionType.SEGMENT
            }
            return CompoundPredictionSelection(
                type,
                syntaxReader.readCompoundMaskSign(),
                wedgeIndex
            )
        }

        if (tileContext.sequenceHeader().features().jointCompound()) {
            val context =
                jointCompoundContext(position, neighborContext, referenceFrame0, referenceFrame1)
            return CompoundPredictionSelection(
                if (syntaxReader.readUseAverageCompound(context))
                    CompoundPredictionType.AVERAGE
                else
                    CompoundPredictionType.WEIGHTED_AVERAGE,
                false,
                -1
            )
        }

        return CompoundPredictionSelection(CompoundPredictionType.AVERAGE, false, -1)
    }

    /** Returns the joint-compound entropy context for the current compound reference pair.
     * 
     * @param position the local tile-relative block origin
     * @param neighborContext the mutable neighbor context that supplies compound type state
     * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order
     * @return the joint-compound entropy context for the current compound reference pair
     */
    private fun jointCompoundContext(
        position: BlockPosition,
        neighborContext: BlockNeighborContext,
        referenceFrame0: Int,
        referenceFrame1: Int
    ): Int {
        val referenceHeader0 = tileContext.referenceFrameHeader(referenceFrame0)
        val referenceHeader1 = tileContext.referenceFrameHeader(referenceFrame1)
        check(!(referenceHeader0 == null || referenceHeader1 == null)) { "Joint compound prediction requires parsed reference frame order hints" }
        return neighborContext.jointCompoundContext(
            position,
            tileContext.frameHeader().frameOffset(),
            referenceHeader0.frameOffset(),
            referenceHeader1.frameOffset(),
            tileContext.sequenceHeader().features().orderHintBits()
        )
    }

    /** Decodes one switchable interpolation-filter selection for the current inter block.
     * 
     * Non-switchable frame-level filter modes leave the block-level filter state unavailable. For
     * switchable frames, modes that do not signal block-level filters default both directions to
     * regular 8-tap filtering so later neighbor contexts see the same state that AV1 stores on the
     * edges.
     * 
     * @param position the local tile-relative block origin
     * @param size the decoded block size
     * @param neighborContext the mutable neighbor context that supplies interpolation-filter contexts
     * @param skipMode whether the block uses frame-level skip-mode references
     * @param compoundReference whether the current block uses compound inter references
     * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
     * @param singleInterMode the decoded single-reference inter mode, or `null` for compound blocks
     * @param compoundInterMode the decoded compound inter mode, or `null` for single-reference blocks
     * @param motionMode the decoded motion-compensation mode
     * @return the decoded switchable interpolation-filter selection for the current inter block
     */
    private fun readInterpolationFilterSelection(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext,
        skipMode: Boolean,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        singleInterMode: SingleInterPredictionMode?,
        compoundInterMode: CompoundInterPredictionMode?,
        motionMode: MotionMode
    ): InterpolationFilterSelection {
        val subpelFilterMode = tileContext.frameHeader().subpelFilterMode()
        if (subpelFilterMode != InterpolationFilter.SWITCHABLE) {
            return InterpolationFilterSelection(null, null)
        }
        if (!requiresSwitchableInterpolationFilterSignaling(
                size,
                skipMode,
                compoundReference,
                referenceFrame0,
                referenceFrame1,
                singleInterMode,
                compoundInterMode,
                motionMode
            )
        ) {
            return InterpolationFilterSelection(
                InterpolationFilter.EIGHT_TAP_REGULAR,
                InterpolationFilter.EIGHT_TAP_REGULAR
            )
        }

        val verticalContext = neighborContext.interpolationFilterContext(
            position,
            referenceFrame0,
            referenceFrame1,
            0
        )
        val verticalInterpolationFilter =
            syntaxReader.readInterpolationFilter(0, verticalContext)
        val horizontalInterpolationFilter: InterpolationFilter?
        if (tileContext.sequenceHeader().features().dualFilter()) {
            val horizontalContext = neighborContext.interpolationFilterContext(
                position,
                referenceFrame0,
                referenceFrame1,
                1
            )
            horizontalInterpolationFilter =
                syntaxReader.readInterpolationFilter(1, horizontalContext)
        } else {
            horizontalInterpolationFilter = verticalInterpolationFilter
        }
        return InterpolationFilterSelection(
            horizontalInterpolationFilter,
            verticalInterpolationFilter
        )
    }

    /** Returns whether the current switchable inter block must decode interpolation-filter symbols.
     * 
     * AV1 derives syntax availability from the decoded inter and motion modes rather than from the
     * fractional parts of the resolved motion vectors. Small global-motion blocks retain explicit
     * filter signaling, while larger non-translation global and local-warped blocks do not.
     * 
     * @param size the decoded block size
     * @param skipMode whether the block uses frame-level skip-mode references
     * @param compoundReference whether the current block uses compound inter references
     * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
     * @param singleInterMode the decoded single-reference inter mode, or `null` for compound blocks
     * @param compoundInterMode the decoded compound inter mode, or `null` for single-reference blocks
     * @param motionMode the decoded motion-compensation mode
     * @return whether the current switchable inter block must decode explicit interpolation-filter symbols
     */
    private fun requiresSwitchableInterpolationFilterSignaling(
        size: BlockSize,
        skipMode: Boolean,
        compoundReference: Boolean,
        referenceFrame0: Int,
        referenceFrame1: Int,
        singleInterMode: SingleInterPredictionMode?,
        compoundInterMode: CompoundInterPredictionMode?,
        motionMode: MotionMode
    ): Boolean {
        requireNotNullCompat(size, "size")
        requireNotNullCompat(motionMode, "motionMode")
        if (skipMode || motionMode == MotionMode.LOCAL_WARPED) {
            return false
        }

        val smallestDimensionIsFourPixels = min(size.width4(), size.height4()) == 1
        val frameHeader = tileContext.frameHeader()
        if (compoundReference) {
            if ((requireNotNullCompat(
                    compoundInterMode,
                    "compoundInterMode"
                )
                        != CompoundInterPredictionMode.GLOBALMV_GLOBALMV)
                || smallestDimensionIsFourPixels
            ) {
                return true
            }
            return frameHeader.globalMotion(referenceFrame0).type() == GlobalMotionType.TRANSLATION
                    || frameHeader.globalMotion(referenceFrame1)
                .type() == GlobalMotionType.TRANSLATION
        }

        if ((requireNotNullCompat(singleInterMode, "singleInterMode")
                    != SingleInterPredictionMode.GLOBALMV)
            || smallestDimensionIsFourPixels
        ) {
            return true
        }
        return frameHeader.globalMotion(referenceFrame0).type() == GlobalMotionType.TRANSLATION
    }

    /** Returns the block-center global motion vector for one LAST-through-ALTREF reference.
     * 
     * Translation parameters use AV1's historical row/column matrix ordering. Affine and
     * rotation-zoom parameters are evaluated at the current block center in frame coordinates.
     * 
     * @param position the tile-relative block origin in 4x4 units
     * @param size the decoded block size
     * @param referenceFrame the reference in internal LAST-through-ALTREF order
     * @return the resolved global motion vector in eighth-pel units
     */
    private fun globalMotionVector(
        position: BlockPosition,
        size: BlockSize,
        referenceFrame: Int
    ): MotionVector {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        val frameHeader = tileContext.frameHeader()
        val parameters = frameHeader.globalMotion(referenceFrame)
        if (parameters.type() == GlobalMotionType.IDENTITY) {
            return MotionVector.zero()
        }

        var rowEighthPel: Int
        var columnEighthPel: Int
        if (parameters.type() == GlobalMotionType.TRANSLATION) {
            rowEighthPel = parameters.matrix(0) shr 13
            columnEighthPel = parameters.matrix(1) shr 13
        } else {
            val blockX4 = (tileContext.startX() shr 2) + nonNullPosition.x4
            val blockY4 = (tileContext.startY() shr 2) + nonNullPosition.y4
            val centerX = blockX4 * 4 + nonNullSize.width4() * 2 - 1
            val centerY = blockY4 * 4 + nonNullSize.height4() * 2 - 1
            val transformedX =
                ((parameters.matrix(2) - (1 shl 16)).toLong() * centerX + parameters.matrix(3)
                    .toLong() * centerY + parameters.matrix(0))
            val transformedY =
                ((parameters.matrix(5) - (1 shl 16)).toLong() * centerY + parameters.matrix(4)
                    .toLong() * centerX + parameters.matrix(1))
            val shift = if (frameHeader.allowHighPrecisionMotionVectors()) 13 else 14
            columnEighthPel = roundGlobalMotionComponent(transformedX, shift)
            rowEighthPel = roundGlobalMotionComponent(transformedY, shift)
            if (!frameHeader.allowHighPrecisionMotionVectors()) {
                columnEighthPel = columnEighthPel shl 1
                rowEighthPel = rowEighthPel shl 1
            }
        }

        if (frameHeader.forceIntegerMotionVectors()) {
            rowEighthPel = roundToIntegerMotionVectorPrecision(rowEighthPel)
            columnEighthPel = roundToIntegerMotionVectorPrecision(columnEighthPel)
        }
        return MotionVector(rowEighthPel, columnEighthPel)
    }

    /** Decodes one `NEWMV` residual around the supplied provisional motion-vector predictor.
     * 
     * @param predictor the provisional motion-vector predictor selected for the block
     * @return the fully decoded motion-vector state for the block
     */
    private fun decodeNewMotionVectorResidual(predictor: InterMotionVector): InterMotionVector {
        val nonNullPredictor = requireNotNullCompat(predictor, "predictor")
        val decodedMotionVector = syntaxReader.readMotionVectorResidual(nonNullPredictor.vector)
        return InterMotionVector.resolved(decodedMotionVector)
    }

    /** Decodes the provisional dynamic-reference-list index for a `NEWMV` single-reference block.
     * 
     * @param provisionalContext the provisional inter-mode context derived from neighbors
     * @return the decoded provisional dynamic-reference-list index
     */
    private fun readNewDrlIndex(provisionalContext: ProvisionalInterModeContext): Int {
        if (provisionalContext.candidateCount() <= 1) {
            return 0
        }
        var drlIndex = if (syntaxReader.readDrlBit(provisionalContext.drlContext(0))) 1 else 0
        if (drlIndex == 1 && provisionalContext.candidateCount() > 2) {
            drlIndex += if (syntaxReader.readDrlBit(provisionalContext.drlContext(1))) 1 else 0
        }
        return drlIndex
    }

    /** Decodes the provisional dynamic-reference-list index for a `NEARMV` single-reference block.
     * 
     * @param provisionalContext the provisional inter-mode context derived from neighbors
     * @return the decoded provisional dynamic-reference-list index
     */
    private fun readNearDrlIndex(provisionalContext: ProvisionalInterModeContext): Int {
        var drlIndex = 1
        if (provisionalContext.candidateCount() > 2) {
            drlIndex += if (syntaxReader.readDrlBit(provisionalContext.drlContext(1))) 1 else 0
            if (drlIndex == 2 && provisionalContext.candidateCount() > 3) {
                drlIndex += if (syntaxReader.readDrlBit(provisionalContext.drlContext(2))) 1 else 0
            }
        }
        return drlIndex
    }

    /** Decodes one compound inter reference pair.
     * 
     * @param position the local tile-relative block origin
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @return the decoded compound inter reference pair
     */
    private fun readCompoundReferenceSelection(
        position: BlockPosition,
        neighborContext: BlockNeighborContext
    ): InterReferenceSelection {
        if (syntaxReader.readCompoundDirectionFlag(neighborContext.compoundDirectionContext(position))) {
            val referenceFrame0: Int
            if (syntaxReader.readCompoundForwardReferenceFlag(
                    0,
                    neighborContext.forwardReferenceContext(position)
                )
            ) {
                referenceFrame0 = (LAST3_FRAME
                        + (if (syntaxReader.readCompoundForwardReferenceFlag(
                        2,
                        neighborContext.forwardReference2Context(position)
                    )
                ) 1 else 0))
            } else {
                referenceFrame0 = if (syntaxReader.readCompoundForwardReferenceFlag(
                        1,
                        neighborContext.forwardReference1Context(position)
                    )
                )
                    LAST2_FRAME
                else
                    LAST_FRAME
            }

            val referenceFrame1: Int
            if (syntaxReader.readCompoundBackwardReferenceFlag(
                    0,
                    neighborContext.backwardReferenceContext(position)
                )
            ) {
                referenceFrame1 = ALTREF_FRAME
            } else {
                referenceFrame1 = (BWDREF_FRAME
                        + (if (syntaxReader.readCompoundBackwardReferenceFlag(
                        1,
                        neighborContext.backwardReference1Context(position)
                    )
                ) 1 else 0))
            }
            return InterReferenceSelection(true, referenceFrame0, referenceFrame1)
        }

        if (syntaxReader.readCompoundUnidirectionalReferenceFlag(
                0,
                neighborContext.singleReferenceContext(position)
            )
        ) {
            return InterReferenceSelection(true, BWDREF_FRAME, ALTREF_FRAME)
        }

        var referenceFrame1: Int = (LAST2_FRAME
                + (if (syntaxReader.readCompoundUnidirectionalReferenceFlag(
                1,
                neighborContext.unidirectionalReference1Context(position)
            )
        ) 1 else 0))
        if (referenceFrame1 == LAST3_FRAME) {
            referenceFrame1 += if (syntaxReader.readCompoundUnidirectionalReferenceFlag(
                    2,
                    neighborContext.forwardReference2Context(position)
                )
            ) 1 else 0
        }
        return InterReferenceSelection(true, LAST_FRAME, referenceFrame1)
    }

    /** Decodes one single inter reference.
     * 
     * @param position the local tile-relative block origin
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @param segmentData the final segment data for the current block
     * @return the decoded single inter reference in internal LAST..ALTREF order
     */
    private fun readSingleReference(
        position: BlockPosition,
        neighborContext: BlockNeighborContext,
        segmentData: SegmentData
    ): Int {
        val segmentReferenceFrame = segmentData.referenceFrame()
        if (segmentReferenceFrame >= 0) {
            return segmentReferenceFrame - 1
        }
        if (segmentData.globalMotion() || segmentData.skip()) {
            return LAST_FRAME
        }

        val singleReferenceContext = neighborContext.singleReferenceContext(position)
        val backward = syntaxReader.readSingleReferenceFlag(0, singleReferenceContext)
        if (backward) {
            if (syntaxReader.readSingleReferenceFlag(
                    1,
                    neighborContext.backwardReferenceContext(position)
                )
            ) {
                return ALTREF_FRAME
            }
            return (BWDREF_FRAME
                    + (if (syntaxReader.readSingleReferenceFlag(
                    5,
                    neighborContext.backwardReference1Context(position)
                )
            ) 1 else 0))
        }
        val forwardReferenceContext = neighborContext.forwardReferenceContext(position)
        val last3OrGolden = syntaxReader.readSingleReferenceFlag(2, forwardReferenceContext)
        if (last3OrGolden) {
            return (LAST3_FRAME
                    + (if (syntaxReader.readSingleReferenceFlag(
                    4,
                    neighborContext.forwardReference2Context(position)
                )
            ) 1 else 0))
        }
        val forwardReference1Context = neighborContext.forwardReference1Context(position)
        val last2 = syntaxReader.readSingleReferenceFlag(3, forwardReference1Context)
        return if (last2)
            LAST2_FRAME
        else
            LAST_FRAME
    }

    /** Returns whether palette syntax is available for the supplied block size.
     * 
     * AV1 palette mode is limited to screen-content frames and blocks up to 64x64, excluding
     * the smallest 4x4 and 4x8 style blocks whose 4x4 dimensions sum to less than four.
     * 
     * @param size the block size to test
     * @return whether palette syntax is available for the supplied block size
     */
    private fun allowsPalette(size: BlockSize): Boolean {
        return tileContext.frameHeader().allowScreenContentTools()
                && max(size.width4(), size.height4()) <= 16 && size.width4() + size.height4() >= 4
    }

    /** Decodes one sorted palette plane using the current above/left palette cache.
     * 
     * AV1 palette entries for luma and U chroma reuse cached sorted entries from left and above
     * neighbors, then merge any newly coded values into a final sorted palette.
     * 
     * @param plane the palette plane index, where `0` is Y and `1` is U
     * @param paletteSize the decoded palette size in `[2, 8]`
     * @param position the local tile-relative block origin
     * @param neighborContext the mutable neighbor context that supplies palette caches
     * @return the decoded sorted palette entries
     */
    private fun readPalettePlane(
        plane: Int,
        paletteSize: Int,
        position: BlockPosition,
        neighborContext: BlockNeighborContext
    ): IntArray {
        val x4 = position.x4
        val y4 = position.y4
        val leftCacheSize =
            if (plane == 0) neighborContext.leftPaletteSize(y4) else neighborContext.leftChromaPaletteSize(
                y4
            )
        val aboveCacheSize = if (canReuseAbovePaletteCache(position))
            if (plane == 0) neighborContext.abovePaletteSize(x4) else neighborContext.aboveChromaPaletteSize(
                x4
            )
        else
            0
        val cache = IntArray(16)
        val cacheSize: Int =
            mergePaletteCache(cache, plane, x4, y4, leftCacheSize, aboveCacheSize, neighborContext)
        val usedCache = IntArray(8)
        var usedCacheSize = 0
        run {
            var i = 0
            while (i < cacheSize && usedCacheSize < paletteSize) {
                if (tileContext.msacDecoder().decodeBooleanEqui()) {
                    usedCache[usedCacheSize++] = cache[i]
                }
                i++
            }
        }

        val palette = IntArray(paletteSize)
        var insertIndex = usedCacheSize
        if (insertIndex < paletteSize) {
            val bitDepth = tileContext.sequenceHeader().colorConfig().bitDepth.bits()
            val max = (1 shl bitDepth) - 1
            val step = if (plane == 0) 1 else 0
            var previous = tileContext.msacDecoder().decodeBools(bitDepth)
            palette[insertIndex++] = previous
            if (insertIndex < paletteSize) {
                var bits = bitDepth - 3 + tileContext.msacDecoder().decodeBools(2)
                while (insertIndex < paletteSize) {
                    val delta = tileContext.msacDecoder().decodeBools(bits)
                    previous = min(previous + delta + step, max)
                    palette[insertIndex++] = previous
                    if (previous + step >= max) {
                        fillCompat(palette, insertIndex, paletteSize, max)
                        break
                    }
                    bits = min(bits, bitWidth(max - previous - step))
                }
            }
        }

        if (usedCacheSize == 0) {
            return palette
        }

        if (usedCacheSize == paletteSize) {
            return usedCache.copyOf(usedCacheSize)
        }

        val merged = IntArray(paletteSize)
        var usedIndex = 0
        var newIndex = usedCacheSize
        for (i in 0..<paletteSize) {
            if (usedIndex < usedCacheSize && (newIndex >= paletteSize || usedCache[usedIndex] <= palette[newIndex])) {
                merged[i] = usedCache[usedIndex++]
            } else {
                merged[i] = palette[newIndex++]
            }
        }
        return merged
    }

    /** Decodes one V chroma palette using the AV1 explicit chroma-V coding rules.
     * 
     * @param paletteSize the decoded chroma palette size in `[2, 8]`
     * @return the decoded V chroma palette entries
     */
    private fun readChromaVPalette(paletteSize: Int): IntArray {
        val palette = IntArray(paletteSize)
        val bitDepth = tileContext.sequenceHeader().colorConfig().bitDepth.bits()
        val max = (1 shl bitDepth) - 1
        if (tileContext.msacDecoder().decodeBooleanEqui()) {
            val bits = bitDepth - 4 + tileContext.msacDecoder().decodeBools(2)
            var previous = tileContext.msacDecoder().decodeBools(bitDepth)
            palette[0] = previous
            for (i in 1..<paletteSize) {
                var delta = tileContext.msacDecoder().decodeBools(bits)
                if (delta != 0 && tileContext.msacDecoder().decodeBooleanEqui()) {
                    delta = -delta
                }
                previous = (previous + delta) and max
                palette[i] = previous
            }
        } else {
            for (i in 0..<paletteSize) {
                palette[i] = tileContext.msacDecoder().decodeBools(bitDepth)
            }
        }
        return palette
    }

    /** Decodes one packed palette index map for luma or chroma.
     * 
     * AV1 signals palette indices in wave-front diagonal order, then packs the map to two 4-bit
     * entries per output byte. The token stream covers columns and rows inside the AV1 aligned
     * frame grid; any remaining coded-block edges are filled by replicating the last decoded
     * column and row.
     * 
     * @param plane the palette plane index, where `0` is luma and `1` is chroma
     * @param paletteSize the decoded palette size in `[2, 8]`
     * @param position the tile-local block position that owns the palette
     * @param size the block size that owns the palette
     * @return the packed palette index map, or an empty array when palette mode is disabled
     */
    private fun readPaletteIndices(
        plane: Int,
        paletteSize: Int,
        position: BlockPosition,
        size: BlockSize
    ): ByteArray {
        var fullWidth = size.widthPixels()
        var fullHeight = size.heightPixels()
        var visibleWidth: Int = visiblePaletteLumaDimension(
            tileContext.startX() + (position.x4 shl 2),
            fullWidth,
            alignedPaletteFrameDimension(tileContext.frameHeader().frameSize().codedWidth())
        )
        var visibleHeight: Int = visiblePaletteLumaDimension(
            tileContext.startY() + (position.y4 shl 2),
            fullHeight,
            alignedPaletteFrameDimension(tileContext.frameHeader().frameSize().height())
        )
        if (plane != 0) {
            val subsamplingX =
                if (tileContext.sequenceHeader().colorConfig().chromaSubsamplingX) 1 else 0
            val subsamplingY =
                if (tileContext.sequenceHeader().colorConfig().chromaSubsamplingY) 1 else 0
            val sub8WidthExtra: Int = chromaSub8Extra(fullWidth, subsamplingX)
            val sub8HeightExtra: Int = chromaSub8Extra(fullHeight, subsamplingY)
            fullWidth = planeDimension(fullWidth, subsamplingX, sub8WidthExtra)
            fullHeight = planeDimension(fullHeight, subsamplingY, sub8HeightExtra)
            visibleWidth = planeDimension(visibleWidth, subsamplingX, sub8WidthExtra)
            visibleHeight = planeDimension(visibleHeight, subsamplingY, sub8HeightExtra)
        }

        return readPaletteIndices(
            plane,
            paletteSize,
            visibleWidth,
            visibleHeight,
            fullWidth,
            fullHeight
        )
    }

    /** Decodes one packed palette index map for the supplied visible and coded dimensions.
     * 
     * @param plane the palette plane index, where `0` is luma and `1` is chroma
     * @param paletteSize the decoded palette size in `[2, 8]`
     * @param visibleWidth the visible palette width in pixels
     * @param visibleHeight the visible palette height in pixels
     * @param fullWidth the coded palette width in pixels
     * @param fullHeight the coded palette height in pixels
     * @return the packed palette index map
     */
    private fun readPaletteIndices(
        plane: Int,
        paletteSize: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        fullWidth: Int,
        fullHeight: Int
    ): ByteArray {
        val unpacked = ByteArray(fullWidth * fullHeight)
        unpacked[0] = syntaxReader.readPaletteInitialIndex(paletteSize).toByte()
        val order = IntArray(8)
        val scores = IntArray(PALETTE_COLOR_COUNT)
        for (i in 1..<visibleWidth + visibleHeight - 1) {
            val first = min(i, visibleWidth - 1)
            val last = max(0, i - visibleHeight + 1)
            for (x in first downTo last) {
                val y = i - x
                val context: Int =
                    buildPaletteOrder(unpacked, fullWidth, x, y, paletteSize, order, scores)
                val colorIndex = syntaxReader.readPaletteColorMapSymbol(plane, paletteSize, context)
                unpacked[y * fullWidth + x] = order[colorIndex].toByte()
            }
        }
        return finishPaletteIndices(unpacked, fullWidth, fullHeight, visibleWidth, visibleHeight)
    }

    /** Returns whether the above palette cache may be reused for the supplied block position.
     * 
     * AV1 does not reuse the above palette cache across 64x64 superblock row boundaries.
     * 
     * @param position the local tile-relative block origin
     * @return whether the above palette cache may be reused for the supplied block position
     */
    private fun canReuseAbovePaletteCache(position: BlockPosition): Boolean {
        val frameY4 = (tileContext.startY() shr 2) + position.y4
        return (frameY4 and 15) != 0
    }

    /** Returns whether filter-intra syntax is available for the supplied block.
     * 
     * Filter intra is only signaled for DC-predicted luma blocks whose longest side does not
     * exceed 32 pixels when the active sequence enables the tool.
     * 
     * @param size the block size to test
     * @param yMode the decoded luma intra prediction mode
     * @return whether filter-intra syntax is available for the supplied block
     */
    private fun allowsFilterIntra(size: BlockSize, yMode: LumaIntraPredictionMode): Boolean {
        return tileContext.sequenceHeader().features().filterIntra()
                && yMode == LumaIntraPredictionMode.DC && max(
            size.widthPixels(),
            size.heightPixels()
        ) <= 32
    }

    /** Reads a segment identifier from the preskip portion of the block header.
     * 
     * @param position the local tile-relative block origin
     * @param size the current block size
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @return the decoded preskip segment identifier
     */
    private fun readSegmentIdBeforeSkip(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext
    ): SegmentReadResult {
        val segmentation = tileContext.frameHeader().segmentation()
        if (!segmentation.enabled()) {
            return SegmentReadResult(false, 0)
        }
        if (!segmentation.updateMap()) {
            return SegmentReadResult(false, tileContext.referenceSegmentId(position, size))
        }
        val prediction = neighborContext.currentSegmentPrediction(position)
        if (segmentation.temporalUpdate()) {
            val segmentPredicted = syntaxReader.readSegmentPredictionFlag(
                neighborContext.segmentPredictionContext(position)
            )
            if (segmentPredicted) {
                return SegmentReadResult(true, tileContext.referenceSegmentId(position, size))
            }
        }

        val segmentId = decodeSegmentId(prediction, segmentation.lastActiveSegmentId())
        return SegmentReadResult(false, segmentId)
    }

    /** Reads a segment identifier from the postskip portion of the block header.
     * 
     * @param position the local tile-relative block origin
     * @param size the current block size
     * @param neighborContext the mutable neighbor context that supplies syntax contexts
     * @param skip whether the current block already decoded as skipped
     * @return the decoded postskip segment identifier
     */
    private fun readSegmentIdAfterSkip(
        position: BlockPosition,
        size: BlockSize,
        neighborContext: BlockNeighborContext,
        skip: Boolean
    ): SegmentReadResult {
        val segmentation = tileContext.frameHeader().segmentation()
        if (!segmentation.enabled()) {
            return SegmentReadResult(false, 0)
        }
        if (!segmentation.updateMap()) {
            return SegmentReadResult(false, tileContext.referenceSegmentId(position, size))
        }
        val prediction = neighborContext.currentSegmentPrediction(position)
        if (skip) {
            return SegmentReadResult(false, prediction.predictedSegmentId)
        }
        if (segmentation.temporalUpdate()) {
            val segmentPredicted = syntaxReader.readSegmentPredictionFlag(
                neighborContext.segmentPredictionContext(position)
            )
            if (segmentPredicted) {
                return SegmentReadResult(true, tileContext.referenceSegmentId(position, size))
            }
        }
        val segmentId = decodeSegmentId(prediction, segmentation.lastActiveSegmentId())
        return SegmentReadResult(false, segmentId)
    }

    /** Decodes one segment identifier from a predicted segment and segment-id context.
     * 
     * @param prediction the current-frame segment prediction derived from already-decoded neighbors
     * @param lastActiveSegmentId the highest active segment identifier, or `-1`
     * @return the decoded segment identifier
     */
    private fun decodeSegmentId(prediction: SegmentPrediction, lastActiveSegmentId: Int): Int {
        val diff = syntaxReader.readSegmentId(prediction.context)
        val segmentId: Int =
            negDeinterleave(diff, prediction.predictedSegmentId, lastActiveSegmentId + 1)
        if (strictStdCompliance && (segmentId < 0 || segmentId > lastActiveSegmentId || segmentId >= 8)) {
            val cause = IllegalArgumentException(
                "Decoded segment identifier exceeds LastActiveSegId"
            )
            throw InvalidFrameSyntaxException(cause.message, cause)
        }
        return validSegmentIdOrZero(segmentId, lastActiveSegmentId)
    }

    /** One decoded leaf block header.
     * 
     * Constructors take ownership of palette array arguments. Callers must not access or modify
     * those arrays after construction. Delta-LF values are copied into scalar fields. */
    class BlockHeader {
        /** The block origin in the coordinate space used by this header. */
        var position: BlockPosition

        /** The decoded block size. */
        val size: BlockSize

        /** Whether the block has chroma samples in the active frame layout. */
        val hasChroma: Boolean

        /** The decoded skip flag. */
        val skip: Boolean

        /** The decoded skip-mode flag. */
        val skipMode: Boolean

        /** Whether the block is intra-coded. */
        val intra: Boolean

        /** Whether the block uses `intrabc`. */
        val useIntrabc: Boolean

        /** Whether the block uses compound inter references. */
        val compoundReference: Boolean

        /** The primary inter reference in internal LAST..ALTREF order, or `-1`. */
        val referenceFrame0: Int

        /** The secondary inter reference in internal LAST..ALTREF order, or `-1`. */
        val referenceFrame1: Int

        /** The decoded single-reference inter mode, or `null` when not available. */
        val singleInterMode: SingleInterPredictionMode?

        /** The decoded compound inter mode, or `null` when not available. */
        val compoundInterMode: CompoundInterPredictionMode?

        /** The decoded dynamic-reference-list index, or `-1` when not available. */
        val drlIndex: Int

        /** The primary motion vector chosen for the block, or `null` when not available. */
        val motionVector0: InterMotionVector?

        /** The secondary motion vector chosen for the block, or `null` when not available. */
        val motionVector1: InterMotionVector?

        /** The decoded inter motion-compensation mode. */
        val motionMode: MotionMode

        /** The decoded horizontal switchable interpolation filter, or `null` when not available. */
        val horizontalInterpolationFilter: InterpolationFilter?

        /** The decoded vertical switchable interpolation filter, or `null` when not available. */
        val verticalInterpolationFilter: InterpolationFilter?

        /** The decoded compound prediction blend type, or `null` when not available. */
        val compoundPredictionType: CompoundPredictionType?

        /** Whether the decoded segment or wedge compound mask uses inverted source order. */
        val compoundMaskSign: Boolean

        /** The decoded compound wedge index, or `-1` when wedge blending is not used. */
        val compoundWedgeIndex: Int

        /** Whether the block uses inter-intra prediction. */
        val interIntra: Boolean

        /** The decoded inter-intra prediction mode, or `null` when not available. */
        val interIntraMode: InterIntraPredictionMode?

        /** Whether the inter-intra block uses a wedge mask. */
        val interIntraWedge: Boolean

        /** The decoded inter-intra wedge index, or `-1` when not available. */
        val interIntraWedgeIndex: Int

        /** Whether the block used temporal segmentation prediction. */
        val segmentPredicted: Boolean

        /** The decoded segment identifier for the block. */
        val segmentId: Int

        /** The decoded CDEF index for the current superblock quadrant, or `-1` when still unknown. */
        val cdefIndex: Int

        /** The current luma AC quantizer index after any superblock-level delta-q update. */
        val qIndex: Int

        /** The current delta-lf runtime slot 0 after any superblock-level updates. */
        val deltaLfValue0: Int

        /** The current delta-lf runtime slot 1 after any superblock-level updates. */
        val deltaLfValue1: Int

        /** The current delta-lf runtime slot 2 after any superblock-level updates. */
        val deltaLfValue2: Int

        /** The current delta-lf runtime slot 3 after any superblock-level updates. */
        val deltaLfValue3: Int

        /** The decoded luma intra prediction mode, or `null` for non-intra blocks. */
        val yMode: LumaIntraPredictionMode?

        /** The decoded chroma intra prediction mode, or `null` when not present. */
        val uvMode: UvIntraPredictionMode?

        /** The decoded luma palette size in `[0, 8]`, or `0` when palette mode is disabled. */
        val yPaletteSize: Int

        /** The decoded chroma palette size in `[0, 8]`, or `0` when palette mode is disabled. */
        val uvPaletteSize: Int

        /** The decoded luma palette entries, or an empty array when palette mode is disabled. */
        val yPaletteColors: IntArray

        /** The decoded U chroma palette entries, or an empty array when palette mode is disabled. */
        val uPaletteColors: IntArray

        /** The decoded V chroma palette entries, or an empty array when palette mode is disabled. */
        val vPaletteColors: IntArray

        /** The packed luma palette indices with two 4-bit entries per byte. */
        val yPaletteIndices: ByteArray

        /** The packed chroma palette indices with two 4-bit entries per byte. */
        val uvPaletteIndices: ByteArray

        /** The decoded filter-intra mode, or `null` when filter intra is disabled. */
        val filterIntraMode: FilterIntraMode?

        /** The decoded signed luma angle delta in `[-3, 3]`. */
        val yAngle: Int

        /** The decoded signed chroma angle delta in `[-3, 3]`. */
        val uvAngle: Int

        /** The decoded signed CFL alpha for chroma U. */
        val cflAlphaU: Int

        /** The decoded signed CFL alpha for chroma V. */
        val cflAlphaV: Int

        /** Creates one decoded leaf block header.
         * 
         * @param position the local tile-relative block origin
         * @param size the decoded block size
         * @param hasChroma whether the block has chroma samples in the active frame layout
         * @param skip the decoded skip flag
         * @param skipMode the decoded skip-mode flag
         * @param intra whether the block is intra-coded
         * @param useIntrabc whether the block uses `intrabc`
         * @param compoundReference whether the block uses compound inter references
         * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
         * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
         * @param singleInterMode the decoded single-reference inter mode, or `null`
         * @param compoundInterMode the decoded compound inter mode, or `null`
         * @param drlIndex the decoded dynamic-reference-list index, or `-1`
         * @param motionVector0 the primary motion vector chosen for the block, or `null`
         * @param motionVector1 the secondary motion vector chosen for the block, or `null`
         * @param motionMode the decoded inter motion-compensation mode
         * @param horizontalInterpolationFilter the decoded horizontal switchable interpolation filter, or `null`
         * @param verticalInterpolationFilter the decoded vertical switchable interpolation filter, or `null`
         * @param compoundPredictionType the decoded compound prediction blend type, or `null`
         * @param compoundMaskSign whether the decoded segment or wedge compound mask uses inverted source order
         * @param compoundWedgeIndex the decoded compound wedge index, or `-1`
         * @param interIntra whether the block uses inter-intra prediction
         * @param interIntraMode the decoded inter-intra prediction mode, or `null`
         * @param interIntraWedge whether the inter-intra block uses a wedge mask
         * @param interIntraWedgeIndex the decoded inter-intra wedge index, or `-1`
         * @param segmentPredicted whether the block used temporal segmentation prediction
         * @param segmentId the decoded segment identifier for the block
         * @param cdefIndex the decoded CDEF index for the current superblock quadrant, or `-1`
         * @param qIndex the current luma AC quantizer index after any superblock-level delta-q update
         * @param deltaLfValues the current delta-lf runtime slots after any superblock-level updates
         * @param yMode the decoded luma intra prediction mode, or `null`
         * @param uvMode the decoded chroma intra prediction mode, or `null`
         * @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
         * @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
         * @param yPaletteColors the decoded luma palette entries, or an empty array
         * @param uPaletteColors the decoded U chroma palette entries, or an empty array
         * @param vPaletteColors the decoded V chroma palette entries, or an empty array
         * @param yPaletteIndices the packed luma palette indices, or an empty array
         * @param uvPaletteIndices the packed chroma palette indices, or an empty array
         * @param filterIntraMode the decoded filter-intra mode, or `null`
         * @param yAngle the decoded signed luma angle delta in `[-3, 3]`
         * @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
         * @param cflAlphaU the decoded signed CFL alpha for chroma U
         * @param cflAlphaV the decoded signed CFL alpha for chroma V
         */
        constructor(
            position: BlockPosition,
            size: BlockSize,
            hasChroma: Boolean,
            skip: Boolean,
            skipMode: Boolean,
            intra: Boolean,
            useIntrabc: Boolean,
            compoundReference: Boolean,
            referenceFrame0: Int,
            referenceFrame1: Int,
            singleInterMode: SingleInterPredictionMode?,
            compoundInterMode: CompoundInterPredictionMode?,
            drlIndex: Int,
            motionVector0: InterMotionVector?,
            motionVector1: InterMotionVector?,
            motionMode: MotionMode,
            horizontalInterpolationFilter: InterpolationFilter?,
            verticalInterpolationFilter: InterpolationFilter?,
            compoundPredictionType: CompoundPredictionType?,
            compoundMaskSign: Boolean,
            compoundWedgeIndex: Int,
            interIntra: Boolean,
            interIntraMode: InterIntraPredictionMode?,
            interIntraWedge: Boolean,
            interIntraWedgeIndex: Int,
            segmentPredicted: Boolean,
            segmentId: Int,
            cdefIndex: Int,
            qIndex: Int,
            deltaLfValues: IntArray,
            yMode: LumaIntraPredictionMode?,
            uvMode: UvIntraPredictionMode?,
            yPaletteSize: Int,
            uvPaletteSize: Int,
            yPaletteColors: IntArray,
            uPaletteColors: IntArray,
            vPaletteColors: IntArray,
            yPaletteIndices: ByteArray,
            uvPaletteIndices: ByteArray,
            filterIntraMode: FilterIntraMode?,
            yAngle: Int,
            uvAngle: Int,
            cflAlphaU: Int,
            cflAlphaV: Int
        ) {
            this.position = requireNotNullCompat(position, "position")
            this.size = requireNotNullCompat(size, "size")
            this.hasChroma = hasChroma
            this.skip = skip
            this.skipMode = skipMode
            this.intra = intra
            this.useIntrabc = useIntrabc
            this.compoundReference = compoundReference
            this.referenceFrame0 = referenceFrame0
            this.referenceFrame1 = referenceFrame1
            this.singleInterMode = singleInterMode
            this.compoundInterMode = compoundInterMode
            this.drlIndex = drlIndex
            this.motionVector0 = motionVector0
            this.motionVector1 = motionVector1
            this.motionMode = requireNotNullCompat(motionMode, "motionMode")
            this.horizontalInterpolationFilter = horizontalInterpolationFilter
            this.verticalInterpolationFilter = verticalInterpolationFilter
            this.compoundPredictionType = compoundPredictionType
            this.compoundMaskSign = compoundMaskSign
            this.compoundWedgeIndex = compoundWedgeIndex
            this.interIntra = interIntra
            this.interIntraMode = interIntraMode
            this.interIntraWedge = interIntraWedge
            this.interIntraWedgeIndex = interIntraWedgeIndex
            this.segmentPredicted = segmentPredicted
            this.segmentId = segmentId
            this.cdefIndex = cdefIndex
            this.qIndex = qIndex
            val checkedDeltaLfValues =
                requireNotNullCompat(deltaLfValues, "deltaLfValues")
            require(checkedDeltaLfValues.size == 4) { "deltaLfValues length must be 4" }
            this.deltaLfValue0 = checkedDeltaLfValues[0]
            this.deltaLfValue1 = checkedDeltaLfValues[1]
            this.deltaLfValue2 = checkedDeltaLfValues[2]
            this.deltaLfValue3 = checkedDeltaLfValues[3]
            this.yMode = yMode
            this.uvMode = uvMode
            this.yPaletteSize = yPaletteSize
            this.uvPaletteSize = uvPaletteSize
            val checkedYPaletteColors =
                requireNotNullCompat(yPaletteColors, "yPaletteColors")
            val checkedUPaletteColors =
                requireNotNullCompat(uPaletteColors, "uPaletteColors")
            val checkedVPaletteColors =
                requireNotNullCompat(vPaletteColors, "vPaletteColors")
            val checkedYPaletteIndices =
                requireNotNullCompat(yPaletteIndices, "yPaletteIndices")
            val checkedUvPaletteIndices =
                requireNotNullCompat(uvPaletteIndices, "uvPaletteIndices")
            this.yPaletteColors =
                if (checkedYPaletteColors.size == 0) EMPTY_INT_PAYLOAD else checkedYPaletteColors
            this.uPaletteColors =
                if (checkedUPaletteColors.size == 0) EMPTY_INT_PAYLOAD else checkedUPaletteColors
            this.vPaletteColors =
                if (checkedVPaletteColors.size == 0) EMPTY_INT_PAYLOAD else checkedVPaletteColors
            this.yPaletteIndices =
                if (checkedYPaletteIndices.size == 0) EMPTY_BYTE_PAYLOAD else checkedYPaletteIndices
            this.uvPaletteIndices =
                if (checkedUvPaletteIndices.size == 0) EMPTY_BYTE_PAYLOAD else checkedUvPaletteIndices
            this.filterIntraMode = filterIntraMode
            this.yAngle = yAngle
            this.uvAngle = uvAngle
            this.cflAlphaU = cflAlphaU
            this.cflAlphaV = cflAlphaV
            require(this.yPaletteColors.size == yPaletteSize) { "yPaletteColors length does not match yPaletteSize" }
            require(this.uPaletteColors.size == uvPaletteSize) { "uPaletteColors length does not match uvPaletteSize" }
            require(this.vPaletteColors.size == uvPaletteSize) { "vPaletteColors length does not match uvPaletteSize" }
            require(!(yPaletteSize == 0 && this.yPaletteIndices.size != 0)) { "yPaletteIndices must be empty when yPaletteSize == 0" }
            require(!(uvPaletteSize == 0 && this.uvPaletteIndices.size != 0)) { "uvPaletteIndices must be empty when uvPaletteSize == 0" }
            require(cdefIndex >= -1) { "cdefIndex < -1: $cdefIndex" }
            require(qIndex in 0..255) { "qIndex out of range: $qIndex" }
            require(drlIndex in -1..3) { "drlIndex out of range: $drlIndex" }
            require(!((intra || useIntrabc) && (referenceFrame0 != NO_REFERENCE_FRAME || referenceFrame1 != NO_REFERENCE_FRAME))) { "Intra and intrabc blocks must not carry inter references" }
            require(
                !(intra
                        && (singleInterMode != null || compoundInterMode != null || drlIndex != -1 || motionVector0 != null || motionVector1 != null))
            ) { "Intra blocks must not carry inter-mode or motion-vector state" }
            require(
                !(useIntrabc
                        && (singleInterMode != null || compoundInterMode != null || drlIndex != -1 || motionVector1 != null))
            ) { "intrabc blocks must not carry inter-mode, DRL, or secondary motion-vector state" }
            require(
                !((intra || useIntrabc)
                        && (horizontalInterpolationFilter != null || verticalInterpolationFilter != null))
            ) { "Intra and intrabc blocks must not carry switchable interpolation filters" }
            require(!((intra || useIntrabc || compoundReference) && this.motionMode != MotionMode.SIMPLE)) { "Only single-reference inter blocks may carry non-simple motion modes" }
            require(
                !(this.motionMode != MotionMode.SIMPLE && min(
                    this.size.widthPixels(),
                    this.size.heightPixels()
                ) < 8)
            ) { "Non-simple motion modes require at least 8x8 luma samples" }
            require((horizontalInterpolationFilter == null) == (verticalInterpolationFilter == null)) { "Switchable interpolation filters must be both present or both absent" }
            require(!(!compoundReference && compoundPredictionType != null)) { "Single-reference blocks must not carry compound prediction type state" }
            require(!(compoundReference && compoundPredictionType == null)) { "Compound-reference blocks must carry compound prediction type state" }
            if (compoundPredictionType == CompoundPredictionType.WEDGE) {
                require(!(compoundWedgeIndex < 0 || compoundWedgeIndex >= 16)) { "compoundWedgeIndex out of range: $compoundWedgeIndex" }
            } else require(compoundWedgeIndex == -1) { "compoundWedgeIndex must be -1 when compound wedge prediction is disabled" }
            require(
                !(compoundMaskSign
                        && compoundPredictionType != CompoundPredictionType.SEGMENT && compoundPredictionType != CompoundPredictionType.WEDGE)
            ) { "compoundMaskSign requires segment or wedge compound prediction" }
            require(!((intra || useIntrabc || compoundReference) && interIntra)) { "Only single-reference inter blocks may carry inter-intra state" }
            require(!(interIntra && !supportsInterIntra(this.size))) { "Block size does not support inter-intra prediction: " + this.size }
            require(interIntra == (interIntraMode != null)) { "interIntra and interIntraMode availability must match" }
            require(!(interIntraWedge && !interIntra)) { "Wedge inter-intra state requires inter-intra prediction" }
            if (interIntraWedge) {
                require(!(interIntraWedgeIndex < 0 || interIntraWedgeIndex >= 16)) { "interIntraWedgeIndex out of range: $interIntraWedgeIndex" }
            } else require(interIntraWedgeIndex == -1) { "interIntraWedgeIndex must be -1 when wedge inter-intra is disabled" }
            if (!intra && !useIntrabc) {
                require(referenceFrame0 != NO_REFERENCE_FRAME) { "Inter blocks must carry a primary reference" }
                if (compoundReference) {
                    require(referenceFrame1 != NO_REFERENCE_FRAME) { "Compound-reference blocks must carry a secondary reference" }
                    require(singleInterMode == null) { "Compound-reference blocks must not carry a single inter mode" }
                } else require(referenceFrame1 == NO_REFERENCE_FRAME) { "Single-reference blocks must not carry a secondary reference" }
                require(!(!compoundReference && compoundInterMode != null)) { "Single-reference blocks must not carry a compound inter mode" }
                require(!(!compoundReference && motionVector1 != null)) { "Single-reference blocks must not carry a secondary motion vector" }
                require(!(compoundReference && compoundInterMode == null && drlIndex != -1)) { "Compound-reference blocks with DRL state must carry a compound inter mode" }
                require(!(!compoundReference && singleInterMode == null && drlIndex != -1)) { "Single-reference blocks with DRL state must carry a single inter mode" }
                require(!(motionVector1 != null && referenceFrame1 == NO_REFERENCE_FRAME)) { "Blocks without a secondary reference must not carry a secondary motion vector" }
            }
        }

        /** Creates a position-adjusted header that shares the source header's immutable payload.
         * 
         * @param position the replacement block origin
         * @param source the decoded header whose non-position state is retained
         */
        constructor(position: BlockPosition, source: BlockHeader) {
            this.position = requireNotNullCompat(position, "position")
            val nonNullSource = requireNotNullCompat(source, "source")
            this.size = nonNullSource.size
            this.hasChroma = nonNullSource.hasChroma
            this.skip = nonNullSource.skip
            this.skipMode = nonNullSource.skipMode
            this.intra = nonNullSource.intra
            this.useIntrabc = nonNullSource.useIntrabc
            this.compoundReference = nonNullSource.compoundReference
            this.referenceFrame0 = nonNullSource.referenceFrame0
            this.referenceFrame1 = nonNullSource.referenceFrame1
            this.singleInterMode = nonNullSource.singleInterMode
            this.compoundInterMode = nonNullSource.compoundInterMode
            this.drlIndex = nonNullSource.drlIndex
            this.motionVector0 = nonNullSource.motionVector0
            this.motionVector1 = nonNullSource.motionVector1
            this.motionMode = nonNullSource.motionMode
            this.horizontalInterpolationFilter = nonNullSource.horizontalInterpolationFilter
            this.verticalInterpolationFilter = nonNullSource.verticalInterpolationFilter
            this.compoundPredictionType = nonNullSource.compoundPredictionType
            this.compoundMaskSign = nonNullSource.compoundMaskSign
            this.compoundWedgeIndex = nonNullSource.compoundWedgeIndex
            this.interIntra = nonNullSource.interIntra
            this.interIntraMode = nonNullSource.interIntraMode
            this.interIntraWedge = nonNullSource.interIntraWedge
            this.interIntraWedgeIndex = nonNullSource.interIntraWedgeIndex
            this.segmentPredicted = nonNullSource.segmentPredicted
            this.segmentId = nonNullSource.segmentId
            this.cdefIndex = nonNullSource.cdefIndex
            this.qIndex = nonNullSource.qIndex
            this.deltaLfValue0 = nonNullSource.deltaLfValue0
            this.deltaLfValue1 = nonNullSource.deltaLfValue1
            this.deltaLfValue2 = nonNullSource.deltaLfValue2
            this.deltaLfValue3 = nonNullSource.deltaLfValue3
            this.yMode = nonNullSource.yMode
            this.uvMode = nonNullSource.uvMode
            this.yPaletteSize = nonNullSource.yPaletteSize
            this.uvPaletteSize = nonNullSource.uvPaletteSize
            this.yPaletteColors = nonNullSource.yPaletteColors
            this.uPaletteColors = nonNullSource.uPaletteColors
            this.vPaletteColors = nonNullSource.vPaletteColors
            this.yPaletteIndices = nonNullSource.yPaletteIndices
            this.uvPaletteIndices = nonNullSource.uvPaletteIndices
            this.filterIntraMode = nonNullSource.filterIntraMode
            this.yAngle = nonNullSource.yAngle
            this.uvAngle = nonNullSource.uvAngle
            this.cflAlphaU = nonNullSource.cflAlphaU
            this.cflAlphaV = nonNullSource.cflAlphaV
        }

        /** Creates one decoded leaf block header with inter-intra state defaulted to unavailable.
         * 
         * @param position the local tile-relative block origin
         * @param size the decoded block size
         * @param hasChroma whether the block has chroma samples in the active frame layout
         * @param skip the decoded skip flag
         * @param skipMode the decoded skip-mode flag
         * @param intra whether the block is intra-coded
         * @param useIntrabc whether the block uses `intrabc`
         * @param compoundReference whether the block uses compound inter references
         * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
         * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
         * @param singleInterMode the decoded single-reference inter mode, or `null`
         * @param compoundInterMode the decoded compound inter mode, or `null`
         * @param drlIndex the decoded dynamic-reference-list index, or `-1`
         * @param motionVector0 the primary motion vector chosen for the block, or `null`
         * @param motionVector1 the secondary motion vector chosen for the block, or `null`
         * @param horizontalInterpolationFilter the decoded horizontal switchable interpolation filter, or `null`
         * @param verticalInterpolationFilter the decoded vertical switchable interpolation filter, or `null`
         * @param segmentPredicted whether the block used temporal segmentation prediction
         * @param segmentId the decoded segment identifier for the block
         * @param cdefIndex the decoded CDEF index for the current superblock quadrant, or `-1`
         * @param qIndex the current luma AC quantizer index after any superblock-level delta-q update
         * @param deltaLfValues the current delta-lf runtime slots after any superblock-level updates
         * @param yMode the decoded luma intra prediction mode, or `null`
         * @param uvMode the decoded chroma intra prediction mode, or `null`
         * @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
         * @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
         * @param yPaletteColors the decoded luma palette entries, or an empty array
         * @param uPaletteColors the decoded U chroma palette entries, or an empty array
         * @param vPaletteColors the decoded V chroma palette entries, or an empty array
         * @param yPaletteIndices the packed luma palette indices, or an empty array
         * @param uvPaletteIndices the packed chroma palette indices, or an empty array
         * @param filterIntraMode the decoded filter-intra mode, or `null`
         * @param yAngle the decoded signed luma angle delta in `[-3, 3]`
         * @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
         * @param cflAlphaU the decoded signed CFL alpha for chroma U
         * @param cflAlphaV the decoded signed CFL alpha for chroma V
         */
        constructor(
            position: BlockPosition,
            size: BlockSize,
            hasChroma: Boolean,
            skip: Boolean,
            skipMode: Boolean,
            intra: Boolean,
            useIntrabc: Boolean,
            compoundReference: Boolean,
            referenceFrame0: Int,
            referenceFrame1: Int,
            singleInterMode: SingleInterPredictionMode?,
            compoundInterMode: CompoundInterPredictionMode?,
            drlIndex: Int,
            motionVector0: InterMotionVector?,
            motionVector1: InterMotionVector?,
            horizontalInterpolationFilter: InterpolationFilter?,
            verticalInterpolationFilter: InterpolationFilter?,
            segmentPredicted: Boolean,
            segmentId: Int,
            cdefIndex: Int,
            qIndex: Int,
            deltaLfValues: IntArray,
            yMode: LumaIntraPredictionMode?,
            uvMode: UvIntraPredictionMode?,
            yPaletteSize: Int,
            uvPaletteSize: Int,
            yPaletteColors: IntArray,
            uPaletteColors: IntArray,
            vPaletteColors: IntArray,
            yPaletteIndices: ByteArray,
            uvPaletteIndices: ByteArray,
            filterIntraMode: FilterIntraMode?,
            yAngle: Int,
            uvAngle: Int,
            cflAlphaU: Int,
            cflAlphaV: Int
        ) : this(
            position,
            size,
            hasChroma,
            skip,
            skipMode,
            intra,
            useIntrabc,
            compoundReference,
            referenceFrame0,
            referenceFrame1,
            singleInterMode,
            compoundInterMode,
            drlIndex,
            motionVector0,
            motionVector1,
            MotionMode.SIMPLE,
            horizontalInterpolationFilter,
            verticalInterpolationFilter,
            if (compoundReference) CompoundPredictionType.AVERAGE else null,
            false,
            -1,
            false,
            null,
            false,
            -1,
            segmentPredicted,
            segmentId,
            cdefIndex,
            qIndex,
            deltaLfValues,
            yMode,
            uvMode,
            yPaletteSize,
            uvPaletteSize,
            yPaletteColors,
            uPaletteColors,
            vPaletteColors,
            yPaletteIndices,
            uvPaletteIndices,
            filterIntraMode,
            yAngle,
            uvAngle,
            cflAlphaU,
            cflAlphaV
        )

        /** Creates one decoded leaf block header with switchable interpolation-filter state defaulted to unavailable.
         * 
         * @param position the local tile-relative block origin
         * @param size the decoded block size
         * @param hasChroma whether the block has chroma samples in the active frame layout
         * @param skip the decoded skip flag
         * @param skipMode the decoded skip-mode flag
         * @param intra whether the block is intra-coded
         * @param useIntrabc whether the block uses `intrabc`
         * @param compoundReference whether the block uses compound inter references
         * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
         * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
         * @param singleInterMode the decoded single-reference inter mode, or `null`
         * @param compoundInterMode the decoded compound inter mode, or `null`
         * @param drlIndex the decoded dynamic-reference-list index, or `-1`
         * @param motionVector0 the primary motion vector chosen for the block, or `null`
         * @param motionVector1 the secondary motion vector chosen for the block, or `null`
         * @param segmentPredicted whether the block used temporal segmentation prediction
         * @param segmentId the decoded segment identifier for the block
         * @param cdefIndex the decoded CDEF index for the current superblock quadrant, or `-1`
         * @param qIndex the current luma AC quantizer index after any superblock-level delta-q update
         * @param deltaLfValues the current delta-lf runtime slots after any superblock-level updates
         * @param yMode the decoded luma intra prediction mode, or `null`
         * @param uvMode the decoded chroma intra prediction mode, or `null`
         * @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
         * @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
         * @param yPaletteColors the decoded luma palette entries, or an empty array
         * @param uPaletteColors the decoded U chroma palette entries, or an empty array
         * @param vPaletteColors the decoded V chroma palette entries, or an empty array
         * @param yPaletteIndices the packed luma palette indices, or an empty array
         * @param uvPaletteIndices the packed chroma palette indices, or an empty array
         * @param filterIntraMode the decoded filter-intra mode, or `null`
         * @param yAngle the decoded signed luma angle delta in `[-3, 3]`
         * @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
         * @param cflAlphaU the decoded signed CFL alpha for chroma U
         * @param cflAlphaV the decoded signed CFL alpha for chroma V
         */
        constructor(
            position: BlockPosition,
            size: BlockSize,
            hasChroma: Boolean,
            skip: Boolean,
            skipMode: Boolean,
            intra: Boolean,
            useIntrabc: Boolean,
            compoundReference: Boolean,
            referenceFrame0: Int,
            referenceFrame1: Int,
            singleInterMode: SingleInterPredictionMode?,
            compoundInterMode: CompoundInterPredictionMode?,
            drlIndex: Int,
            motionVector0: InterMotionVector?,
            motionVector1: InterMotionVector?,
            segmentPredicted: Boolean,
            segmentId: Int,
            cdefIndex: Int,
            qIndex: Int,
            deltaLfValues: IntArray,
            yMode: LumaIntraPredictionMode?,
            uvMode: UvIntraPredictionMode?,
            yPaletteSize: Int,
            uvPaletteSize: Int,
            yPaletteColors: IntArray,
            uPaletteColors: IntArray,
            vPaletteColors: IntArray,
            yPaletteIndices: ByteArray,
            uvPaletteIndices: ByteArray,
            filterIntraMode: FilterIntraMode?,
            yAngle: Int,
            uvAngle: Int,
            cflAlphaU: Int,
            cflAlphaV: Int
        ) : this(
            position,
            size,
            hasChroma,
            skip,
            skipMode,
            intra,
            useIntrabc,
            compoundReference,
            referenceFrame0,
            referenceFrame1,
            singleInterMode,
            compoundInterMode,
            drlIndex,
            motionVector0,
            motionVector1,
            null,
            null,
            segmentPredicted,
            segmentId,
            cdefIndex,
            qIndex,
            deltaLfValues,
            yMode,
            uvMode,
            yPaletteSize,
            uvPaletteSize,
            yPaletteColors,
            uPaletteColors,
            vPaletteColors,
            yPaletteIndices,
            uvPaletteIndices,
            filterIntraMode,
            yAngle,
            uvAngle,
            cflAlphaU,
            cflAlphaV
        )

        /** Creates one decoded leaf block header with runtime delta state defaulted to unavailable.
         * 
         * @param position the local tile-relative block origin
         * @param size the decoded block size
         * @param hasChroma whether the block has chroma samples in the active frame layout
         * @param skip the decoded skip flag
         * @param skipMode the decoded skip-mode flag
         * @param intra whether the block is intra-coded
         * @param useIntrabc whether the block uses `intrabc`
         * @param compoundReference whether the block uses compound inter references
         * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
         * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
         * @param singleInterMode the decoded single-reference inter mode, or `null`
         * @param compoundInterMode the decoded compound inter mode, or `null`
         * @param drlIndex the decoded dynamic-reference-list index, or `-1`
         * @param motionVector0 the primary motion vector chosen for the block, or `null`
         * @param motionVector1 the secondary motion vector chosen for the block, or `null`
         * @param segmentPredicted whether the block used temporal segmentation prediction
         * @param segmentId the decoded segment identifier for the block
         * @param yMode the decoded luma intra prediction mode, or `null`
         * @param uvMode the decoded chroma intra prediction mode, or `null`
         * @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
         * @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
         * @param yPaletteColors the decoded luma palette entries, or an empty array
         * @param uPaletteColors the decoded U chroma palette entries, or an empty array
         * @param vPaletteColors the decoded V chroma palette entries, or an empty array
         * @param yPaletteIndices the packed luma palette indices, or an empty array
         * @param uvPaletteIndices the packed chroma palette indices, or an empty array
         * @param filterIntraMode the decoded filter-intra mode, or `null`
         * @param yAngle the decoded signed luma angle delta in `[-3, 3]`
         * @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
         * @param cflAlphaU the decoded signed CFL alpha for chroma U
         * @param cflAlphaV the decoded signed CFL alpha for chroma V
         */
        constructor(
            position: BlockPosition,
            size: BlockSize,
            hasChroma: Boolean,
            skip: Boolean,
            skipMode: Boolean,
            intra: Boolean,
            useIntrabc: Boolean,
            compoundReference: Boolean,
            referenceFrame0: Int,
            referenceFrame1: Int,
            singleInterMode: SingleInterPredictionMode?,
            compoundInterMode: CompoundInterPredictionMode?,
            drlIndex: Int,
            motionVector0: InterMotionVector?,
            motionVector1: InterMotionVector?,
            segmentPredicted: Boolean,
            segmentId: Int,
            yMode: LumaIntraPredictionMode?,
            uvMode: UvIntraPredictionMode?,
            yPaletteSize: Int,
            uvPaletteSize: Int,
            yPaletteColors: IntArray,
            uPaletteColors: IntArray,
            vPaletteColors: IntArray,
            yPaletteIndices: ByteArray,
            uvPaletteIndices: ByteArray,
            filterIntraMode: FilterIntraMode?,
            yAngle: Int,
            uvAngle: Int,
            cflAlphaU: Int,
            cflAlphaV: Int
        ) : this(
            position,
            size,
            hasChroma,
            skip,
            skipMode,
            intra,
            useIntrabc,
            compoundReference,
            referenceFrame0,
            referenceFrame1,
            singleInterMode,
            compoundInterMode,
            drlIndex,
            motionVector0,
            motionVector1,
            segmentPredicted,
            segmentId,
            -1,
            0,
            IntArray(4),
            yMode,
            uvMode,
            yPaletteSize,
            uvPaletteSize,
            yPaletteColors,
            uPaletteColors,
            vPaletteColors,
            yPaletteIndices,
            uvPaletteIndices,
            filterIntraMode,
            yAngle,
            uvAngle,
            cflAlphaU,
            cflAlphaV
        )

        /** Creates one decoded leaf block header with inter-mode state defaulted to unavailable.
         * 
         * @param position the local tile-relative block origin
         * @param size the decoded block size
         * @param hasChroma whether the block has chroma samples in the active frame layout
         * @param skip the decoded skip flag
         * @param skipMode the decoded skip-mode flag
         * @param intra whether the block is intra-coded
         * @param useIntrabc whether the block uses `intrabc`
         * @param compoundReference whether the block uses compound inter references
         * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order, or `-1`
         * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
         * @param segmentPredicted whether the block used temporal segmentation prediction
         * @param segmentId the decoded segment identifier for the block
         * @param yMode the decoded luma intra prediction mode, or `null`
         * @param uvMode the decoded chroma intra prediction mode, or `null`
         * @param yPaletteSize the decoded luma palette size in `[0, 8]`, or `0`
         * @param uvPaletteSize the decoded chroma palette size in `[0, 8]`, or `0`
         * @param yPaletteColors the decoded luma palette entries, or an empty array
         * @param uPaletteColors the decoded U chroma palette entries, or an empty array
         * @param vPaletteColors the decoded V chroma palette entries, or an empty array
         * @param yPaletteIndices the packed luma palette indices, or an empty array
         * @param uvPaletteIndices the packed chroma palette indices, or an empty array
         * @param filterIntraMode the decoded filter-intra mode, or `null`
         * @param yAngle the decoded signed luma angle delta in `[-3, 3]`
         * @param uvAngle the decoded signed chroma angle delta in `[-3, 3]`
         * @param cflAlphaU the decoded signed CFL alpha for chroma U
         * @param cflAlphaV the decoded signed CFL alpha for chroma V
         */
        constructor(
            position: BlockPosition,
            size: BlockSize,
            hasChroma: Boolean,
            skip: Boolean,
            skipMode: Boolean,
            intra: Boolean,
            useIntrabc: Boolean,
            compoundReference: Boolean,
            referenceFrame0: Int,
            referenceFrame1: Int,
            segmentPredicted: Boolean,
            segmentId: Int,
            yMode: LumaIntraPredictionMode?,
            uvMode: UvIntraPredictionMode?,
            yPaletteSize: Int,
            uvPaletteSize: Int,
            yPaletteColors: IntArray,
            uPaletteColors: IntArray,
            vPaletteColors: IntArray,
            yPaletteIndices: ByteArray,
            uvPaletteIndices: ByteArray,
            filterIntraMode: FilterIntraMode?,
            yAngle: Int,
            uvAngle: Int,
            cflAlphaU: Int,
            cflAlphaV: Int
        ) : this(
            position,
            size,
            hasChroma,
            skip,
            skipMode,
            intra,
            useIntrabc,
            compoundReference,
            referenceFrame0,
            referenceFrame1,
            null,
            null,
            -1,
            null,
            null,
            segmentPredicted,
            segmentId,
            -1,
            0,
            IntArray(4),
            yMode,
            uvMode,
            yPaletteSize,
            uvPaletteSize,
            yPaletteColors,
            uPaletteColors,
            vPaletteColors,
            yPaletteIndices,
            uvPaletteIndices,
            filterIntraMode,
            yAngle,
            uvAngle,
            cflAlphaU,
            cflAlphaV
        )

        /** Returns the block origin in the coordinate space used by this header.
         * 
         * @return the block origin in the coordinate space used by this header
         */
        fun position(): BlockPosition {
            return position
        }

        /** Returns a copy of this decoded block header with a replaced position.
         * 
         * @param position the replacement block origin
         * @return a copy of this decoded block header with a replaced position
         */
        fun withPosition(position: BlockPosition): BlockHeader {
            val nonNullPosition = requireNotNullCompat(position, "position")
            if (this.position.x4 == nonNullPosition.x4 && this.position.y4 == nonNullPosition.y4) {
                return this
            }
            return BlockHeader(nonNullPosition, this)
        }

        /** Relocates this newly decoded header after tile-local neighbor processing is complete.
         * 
         * This package-private operation must be used only before the header is published through
         * a partition leaf.
         * 
         * @param position the frame-relative replacement position
         */
        fun relocatePosition(position: BlockPosition) {
            this.position = requireNotNullCompat(position, "position")
        }

        /** Returns the decoded block size.
         * 
         * @return the decoded block size
         */
        fun size(): BlockSize {
            return size
        }

        /** Returns whether the block has chroma samples in the active frame layout.
         * 
         * @return whether the block has chroma samples in the active frame layout
         */
        fun hasChroma(): Boolean {
            return hasChroma
        }

        /** Returns the decoded skip flag.
         * 
         * @return the decoded skip flag
         */
        fun skip(): Boolean {
            return skip
        }

        /** Returns the decoded skip-mode flag.
         * 
         * @return the decoded skip-mode flag
         */
        fun skipMode(): Boolean {
            return skipMode
        }

        /** Returns whether the block is intra-coded.
         * 
         * @return whether the block is intra-coded
         */
        fun intra(): Boolean {
            return intra
        }

        /** Returns whether the block uses `intrabc`.
         * 
         * @return whether the block uses `intrabc`
         */
        fun useIntrabc(): Boolean {
            return useIntrabc
        }

        /** Returns whether the block uses compound inter references.
         * 
         * @return whether the block uses compound inter references
         */
        fun compoundReference(): Boolean {
            return compoundReference
        }

        /** Returns the primary inter reference in internal LAST..ALTREF order, or `-1`.
         * 
         * @return the primary inter reference in internal LAST..ALTREF order, or `-1`
         */
        fun referenceFrame0(): Int {
            return referenceFrame0
        }

        /** Returns the secondary inter reference in internal LAST..ALTREF order, or `-1`.
         * 
         * @return the secondary inter reference in internal LAST..ALTREF order, or `-1`
         */
        fun referenceFrame1(): Int {
            return referenceFrame1
        }

        /** Returns the decoded single-reference inter mode, or `null` when not available.
         * 
         * @return the decoded single-reference inter mode, or `null`
         */
        fun singleInterMode(): SingleInterPredictionMode? {
            return singleInterMode
        }

        /** Returns the decoded compound inter mode, or `null` when not available.
         * 
         * @return the decoded compound inter mode, or `null`
         */
        fun compoundInterMode(): CompoundInterPredictionMode? {
            return compoundInterMode
        }

        /** Returns the decoded dynamic-reference-list index, or `-1` when not available.
         * 
         * @return the decoded dynamic-reference-list index, or `-1`
         */
        fun drlIndex(): Int {
            return drlIndex
        }

        /** Returns the primary motion vector chosen for the block, or `null` when not available.
         * 
         * @return the primary motion vector chosen for the block, or `null`
         */
        fun motionVector0(): InterMotionVector? {
            return motionVector0
        }

        /** Returns the secondary motion vector chosen for the block, or `null` when not available.
         * 
         * @return the secondary motion vector chosen for the block, or `null`
         */
        fun motionVector1(): InterMotionVector? {
            return motionVector1
        }

        /** Returns the decoded inter motion-compensation mode.
         * 
         * @return the decoded inter motion-compensation mode
         */
        fun motionMode(): MotionMode {
            return motionMode
        }

        /** Returns the decoded horizontal switchable interpolation filter, or `null` when not available.
         * 
         * @return the decoded horizontal switchable interpolation filter, or `null` when not available
         */
        fun horizontalInterpolationFilter(): InterpolationFilter? {
            return horizontalInterpolationFilter
        }

        /** Returns the decoded vertical switchable interpolation filter, or `null` when not available.
         * 
         * @return the decoded vertical switchable interpolation filter, or `null` when not available
         */
        fun verticalInterpolationFilter(): InterpolationFilter? {
            return verticalInterpolationFilter
        }

        /** Returns the decoded compound prediction blend type, or `null` when not available.
         * 
         * @return the decoded compound prediction blend type, or `null`
         */
        fun compoundPredictionType(): CompoundPredictionType? {
            return compoundPredictionType
        }

        /** Returns whether the decoded segment or wedge compound mask uses inverted source order.
         * 
         * @return whether the decoded segment or wedge compound mask uses inverted source order
         */
        fun compoundMaskSign(): Boolean {
            return compoundMaskSign
        }

        /** Returns the decoded compound wedge index, or `-1` when wedge blending is not used.
         * 
         * @return the decoded compound wedge index, or `-1`
         */
        fun compoundWedgeIndex(): Int {
            return compoundWedgeIndex
        }

        /** Returns whether the block uses inter-intra prediction.
         * 
         * @return whether the block uses inter-intra prediction
         */
        fun interIntra(): Boolean {
            return interIntra
        }

        /** Returns the decoded inter-intra prediction mode, or `null` when not available.
         * 
         * @return the decoded inter-intra prediction mode, or `null`
         */
        fun interIntraMode(): InterIntraPredictionMode? {
            return interIntraMode
        }

        /** Returns whether the inter-intra block uses a wedge mask.
         * 
         * @return whether the inter-intra block uses a wedge mask
         */
        fun interIntraWedge(): Boolean {
            return interIntraWedge
        }

        /** Returns the decoded inter-intra wedge index, or `-1` when not available.
         * 
         * @return the decoded inter-intra wedge index, or `-1`
         */
        fun interIntraWedgeIndex(): Int {
            return interIntraWedgeIndex
        }

        /** Returns whether the block used temporal segmentation prediction.
         * 
         * @return whether the block used temporal segmentation prediction
         */
        fun segmentPredicted(): Boolean {
            return segmentPredicted
        }

        /** Returns the decoded segment identifier for the block.
         * 
         * @return the decoded segment identifier for the block
         */
        fun segmentId(): Int {
            return segmentId
        }

        /** Returns the decoded CDEF index for the current superblock quadrant, or `-1` when still unknown.
         * 
         * @return the decoded CDEF index for the current superblock quadrant, or `-1`
         */
        fun cdefIndex(): Int {
            return cdefIndex
        }

        /** Returns the current luma AC quantizer index after any superblock-level delta-q update.
         * 
         * @return the current luma AC quantizer index after any superblock-level delta-q update
         */
        fun qIndex(): Int {
            return qIndex
        }

        /** Returns a copy of the current delta-lf runtime slots.
         * 
         * When multi-component delta-lf is disabled, only slot `0` carries meaningful state.
         * 
         * @return a copy of the current delta-lf runtime slots
         */
        fun deltaLfValues(): IntArray {
            return intArrayOf(deltaLfValue0, deltaLfValue1, deltaLfValue2, deltaLfValue3)
        }

        /** Returns one current delta-lf runtime slot without copying the complete slot array.
         * 
         * @param index the zero-based slot index
         * @return the selected delta-lf value
         */
        fun deltaLfValue(index: Int): Int {
            return when (checkIndexCompat(index, 4)) {
                0 -> deltaLfValue0
                1 -> deltaLfValue1
                2 -> deltaLfValue2
                3 -> deltaLfValue3
                else -> throw AssertionError()
            }
        }

        /** Returns the decoded luma intra prediction mode, or `null` for non-intra blocks.
         * 
         * @return the decoded luma intra prediction mode, or `null`
         */
        fun yMode(): LumaIntraPredictionMode? {
            return yMode
        }

        /** Returns the decoded chroma intra prediction mode, or `null` when not present.
         * 
         * @return the decoded chroma intra prediction mode, or `null`
         */
        fun uvMode(): UvIntraPredictionMode? {
            return uvMode
        }

        /** Returns the decoded luma palette size in `[0, 8]`, or `0` when palette mode is disabled.
         * 
         * @return the decoded luma palette size in `[0, 8]`, or `0`
         */
        fun yPaletteSize(): Int {
            return yPaletteSize
        }

        /** Returns the decoded chroma palette size in `[0, 8]`, or `0` when palette mode is disabled.
         * 
         * @return the decoded chroma palette size in `[0, 8]`, or `0`
         */
        fun uvPaletteSize(): Int {
            return uvPaletteSize
        }

        /** Returns the decoded luma palette entries, or an empty array when palette mode is disabled.
         * 
         * @return the decoded luma palette entries, or an empty array
         */
        fun yPaletteColors(): IntArray {
            return yPaletteColors.copyOf()
        }

        /** Returns the decoded U chroma palette entries, or an empty array when palette mode is disabled.
         * 
         * @return the decoded U chroma palette entries, or an empty array
         */
        fun uPaletteColors(): IntArray {
            return uPaletteColors.copyOf()
        }

        /** Returns the decoded V chroma palette entries, or an empty array when palette mode is disabled.
         * 
         * @return the decoded V chroma palette entries, or an empty array
         */
        fun vPaletteColors(): IntArray {
            return vPaletteColors.copyOf()
        }

        /** Returns one decoded luma palette entry without copying the palette.
         * 
         * @param paletteIndex the palette entry index in `[0, yPaletteSize())`
         * @return the decoded luma palette entry
         */
        fun yPaletteColor(paletteIndex: Int): Int {
            return yPaletteColors[paletteIndex]
        }

        /** Returns one decoded U chroma palette entry without copying the palette.
         * 
         * @param paletteIndex the palette entry index in `[0, uvPaletteSize())`
         * @return the decoded U chroma palette entry
         */
        fun uPaletteColor(paletteIndex: Int): Int {
            return uPaletteColors[paletteIndex]
        }

        /** Returns one decoded V chroma palette entry without copying the palette.
         * 
         * @param paletteIndex the palette entry index in `[0, uvPaletteSize())`
         * @return the decoded V chroma palette entry
         */
        fun vPaletteColor(paletteIndex: Int): Int {
            return vPaletteColors[paletteIndex]
        }

        /** Returns one unpacked luma palette-map index without copying the packed map.
         * 
         * @param sampleIndex the raster-order sample index in the coded palette map
         * @return the decoded luma palette index
         */
        fun yPaletteIndex(sampleIndex: Int): Int {
            return packedPaletteIndex(yPaletteIndices, sampleIndex)
        }

        /** Returns one unpacked chroma palette-map index without copying the packed map.
         * 
         * @param sampleIndex the raster-order sample index in the coded chroma palette map
         * @return the decoded chroma palette index
         */
        fun uvPaletteIndex(sampleIndex: Int): Int {
            return packedPaletteIndex(uvPaletteIndices, sampleIndex)
        }

        /** Returns the coded number of luma samples represented by the packed palette map.
         * 
         * @return the coded luma palette-map sample count
         */
        fun yPaletteSampleCount(): Int {
            return yPaletteIndices.size shl 1
        }

        /** Returns the coded number of chroma samples represented by the packed palette map.
         * 
         * @return the coded chroma palette-map sample count
         */
        fun uvPaletteSampleCount(): Int {
            return uvPaletteIndices.size shl 1
        }

        /** Returns the packed luma palette indices with two 4-bit entries per byte.
         * 
         * Invisible right and bottom edges are already replicated to the coded block extent.
         * 
         * @return the packed luma palette indices with two 4-bit entries per byte
         */
        fun yPaletteIndices(): ByteArray {
            return yPaletteIndices.copyOf()
        }

        /** Returns the packed chroma palette indices with two 4-bit entries per byte.
         * 
         * Invisible right and bottom edges are already replicated to the coded block extent.
         * 
         * @return the packed chroma palette indices with two 4-bit entries per byte
         */
        fun uvPaletteIndices(): ByteArray {
            return uvPaletteIndices.copyOf()
        }

        /** Returns the decoded filter-intra mode, or `null` when filter intra is disabled.
         * 
         * @return the decoded filter-intra mode, or `null`
         */
        fun filterIntraMode(): FilterIntraMode? {
            return filterIntraMode
        }

        /** Returns the decoded signed luma angle delta in `[-3, 3]`.
         * 
         * @return the decoded signed luma angle delta in `[-3, 3]`
         */
        fun yAngle(): Int {
            return yAngle
        }

        /** Returns the decoded signed chroma angle delta in `[-3, 3]`.
         * 
         * @return the decoded signed chroma angle delta in `[-3, 3]`
         */
        fun uvAngle(): Int {
            return uvAngle
        }

        /** Returns the decoded signed CFL alpha for chroma U.
         * 
         * @return the decoded signed CFL alpha for chroma U
         */
        fun cflAlphaU(): Int {
            return cflAlphaU
        }

        /** Returns the decoded signed CFL alpha for chroma V.
         * 
         * @return the decoded signed CFL alpha for chroma V
         */
        fun cflAlphaV(): Int {
            return cflAlphaV
        }

        companion object {
            /** Returns one unpacked entry from a two-per-byte palette map.
             * 
             * @param packedIndices the packed palette map
             * @param sampleIndex the raster-order sample index
             * @return the unpacked palette index
             */
            fun packedPaletteIndex(packedIndices: ByteArray, sampleIndex: Int): Int {
                checkIndexCompat(sampleIndex, packedIndices.size shl 1)
                val packed = packedIndices[sampleIndex shr 1].toInt() and 0xFF
                return (packed shr ((sampleIndex and 1) shl 2)) and 0x0F
            }
        }
    }

    /** The decoded result of one segment-id read pass.
     * 
     * @param segmentPredicted whether the block used temporal segmentation prediction
     * @param segmentId the decoded segment identifier
     */
    data class SegmentReadResult(val segmentPredicted: Boolean, val segmentId: Int)

    /** The decoded switchable interpolation-filter selection for one inter block.
     * 
     * @param horizontalInterpolationFilter the decoded horizontal switchable interpolation filter, or `null`
     * @param verticalInterpolationFilter the decoded vertical switchable interpolation filter, or `null`
     */
    data class InterpolationFilterSelection(
        val horizontalInterpolationFilter: InterpolationFilter?,
        val verticalInterpolationFilter: InterpolationFilter?
    )

    /** The decoded inter reference selection for one block.
     * 
     * @param compoundReference whether the block uses compound inter references
     * @param referenceFrame0 the primary inter reference in internal LAST..ALTREF order
     * @param referenceFrame1 the secondary inter reference in internal LAST..ALTREF order, or `-1`
     */
    data class InterReferenceSelection(
        val compoundReference: Boolean,
        val referenceFrame0: Int,
        val referenceFrame1: Int
    )

    /** The decoded compound prediction blend selection for one compound-reference block.
     * 
     * @param type the decoded compound prediction blend type
     * @param maskSign whether the decoded segment or wedge mask uses inverted source order
     * @param wedgeIndex the decoded compound wedge index, or `-1`
     */
    data class CompoundPredictionSelection(
        val type: CompoundPredictionType,
        val maskSign: Boolean,
        val wedgeIndex: Int
    ) {
        init {
            requireNotNullCompat(type, "type")
        }
    }

    /** The decoded inter prediction mode and provisional dynamic-reference-list index for one block.
     * 
     * @param singleInterMode the decoded single-reference inter mode, or `null`
     * @param compoundInterMode the decoded compound inter mode, or `null`
     * @param drlIndex the decoded provisional dynamic-reference-list index
     * @param motionVector0 the decoded primary motion-vector state chosen for the block
     * @param motionVector1 the decoded secondary motion-vector state chosen for the block, or `null`
     */
    data class InterModeSelection(
        val singleInterMode: SingleInterPredictionMode?,
        val compoundInterMode: CompoundInterPredictionMode?,
        val drlIndex: Int,
        val motionVector0: InterMotionVector,
        val motionVector1: InterMotionVector?
    ) {
        init {
            requireNotNullCompat(motionVector0, "motionVector0")
        }
    }

    companion object {
        /** The number of palette colors supported by AV1 screen-content palette syntax. */
        private const val PALETTE_COLOR_COUNT = 8

        /** The AV1 score-to-hash multipliers for palette color-map context derivation. */
        val PALETTE_COLOR_HASH_MULTIPLIERS: IntArray = intArrayOf(1, 2, 2)

        /** The AV1 mapping from palette color-neighbor hash to color-map CDF context. */
        val PALETTE_COLOR_CONTEXTS_BY_HASH: IntArray =
            intArrayOf(-1, -1, 0, -1, -1, 4, 3, 2, 1)

        /** The horizontal delay required by the default intrabc displacement vector, in luma pixels. */
        private const val INTRABC_DELAY_PIXELS = 256

        /** The exclusive lower bound for each AV1 motion-vector component in eighth-pel units. */
        const val MOTION_VECTOR_LOWER_BOUND = -(1 shl 14)

        /** The exclusive upper bound for each AV1 motion-vector component in eighth-pel units. */
        const val MOTION_VECTOR_UPPER_BOUND = 1 shl 14

        /** Sentinel used when a block does not carry an inter reference frame. */
        const val NO_REFERENCE_FRAME = -1

        /** Shared immutable empty integer payload used by blocks without palette syntax. */
        val EMPTY_INT_PAYLOAD: IntArray = IntArray(0)

        /** Shared immutable empty byte payload used by blocks without palette syntax. */
        val EMPTY_BYTE_PAYLOAD: ByteArray = ByteArray(0)

        /** The AV1 segment reference-frame code that forces intra block syntax. */
        private const val SEGMENT_REFERENCE_INTRA_FRAME = 0

        /** The AV1 LAST_FRAME index in internal LAST..ALTREF order. */
        private const val LAST_FRAME = 0

        /** The AV1 LAST2_FRAME index in internal LAST..ALTREF order. */
        private const val LAST2_FRAME = 1

        /** The AV1 LAST3_FRAME index in internal LAST..ALTREF order. */
        private const val LAST3_FRAME = 2

        /** The AV1 BWDREF_FRAME index in internal LAST..ALTREF order. */
        private const val BWDREF_FRAME = 4

        /** The AV1 ALTREF_FRAME index in internal LAST..ALTREF order. */
        private const val ALTREF_FRAME = 6

        /** Validates one assigned motion vector against the AV1 component range.
         * 
         * @param motionVector the assigned motion vector
         */
        fun validateMotionVector(motionVector: MotionVector) {
            val nonNullMotionVector =
                requireNotNullCompat(motionVector, "motionVector")
            val row = nonNullMotionVector.rowEighthPel
            val column = nonNullMotionVector.columnEighthPel
            if (row <= MOTION_VECTOR_LOWER_BOUND || row >= MOTION_VECTOR_UPPER_BOUND || column <= MOTION_VECTOR_LOWER_BOUND || column >= MOTION_VECTOR_UPPER_BOUND) {
                val cause = IllegalArgumentException(
                    "Assigned motion vector exceeds the AV1 component range"
                )
                throw InvalidFrameSyntaxException(cause.message, cause)
            }
        }

        /** Clips one integer into the supplied inclusive range.
         * 
         * @param value the value to clip
         * @param min the inclusive lower bound
         * @param max the inclusive upper bound
         * @return the clipped value
         */
        fun clip(value: Int, min: Int, max: Int): Int {
            return max(min, min(max, value))
        }

        /** Returns whether the supplied block size can signal inter-intra prediction.
         * 
         * @param size the block size to test
         * @return whether the supplied block size can signal inter-intra prediction
         */
        fun supportsInterIntra(size: BlockSize): Boolean {
            return when (requireNotNullCompat(size, "size")) {
                BlockSize.SIZE_32X32, BlockSize.SIZE_32X16, BlockSize.SIZE_16X32, BlockSize.SIZE_16X16, BlockSize.SIZE_16X8, BlockSize.SIZE_8X16, BlockSize.SIZE_8X8 -> true
                else -> false
            }
        }

        /** Returns whether the supplied block size can signal compound wedge prediction.
         * 
         * @param size the block size to test
         * @return whether the supplied block size can signal compound wedge prediction
         */
        fun supportsCompoundWedge(size: BlockSize): Boolean {
            return when (requireNotNullCompat(size, "size")) {
                BlockSize.SIZE_32X32, BlockSize.SIZE_32X16, BlockSize.SIZE_32X8, BlockSize.SIZE_16X32, BlockSize.SIZE_16X16, BlockSize.SIZE_16X8, BlockSize.SIZE_8X32, BlockSize.SIZE_8X16, BlockSize.SIZE_8X8 -> true
                else -> false
            }
        }

        /** Returns the inter-intra wedge entropy context for the supplied block size.
         * 
         * @param size the block size to inspect
         * @return the inter-intra wedge entropy context for the supplied block size
         */
        fun interIntraWedgeContext(size: BlockSize): Int {
            return when (requireNotNullCompat(size, "size")) {
                BlockSize.SIZE_8X8 -> 0
                BlockSize.SIZE_8X16 -> 1
                BlockSize.SIZE_16X8 -> 2
                BlockSize.SIZE_16X16 -> 3
                BlockSize.SIZE_16X32 -> 4
                BlockSize.SIZE_32X16 -> 5
                BlockSize.SIZE_32X32 -> 6
                else -> throw IllegalArgumentException("Block size does not have an inter-intra wedge context: $size")
            }
        }

        /** Returns the compound wedge entropy context for the supplied block size.
         * 
         * @param size the block size to inspect
         * @return the compound wedge entropy context for the supplied block size
         */
        fun compoundWedgeContext(size: BlockSize): Int {
            return when (requireNotNullCompat(size, "size")) {
                BlockSize.SIZE_8X8 -> 0
                BlockSize.SIZE_8X16 -> 1
                BlockSize.SIZE_16X8 -> 2
                BlockSize.SIZE_16X16 -> 3
                BlockSize.SIZE_16X32 -> 4
                BlockSize.SIZE_32X16 -> 5
                BlockSize.SIZE_32X32 -> 6
                BlockSize.SIZE_8X32 -> 7
                BlockSize.SIZE_32X8 -> 8
                else -> throw IllegalArgumentException("Block size does not have a compound wedge context: $size")
            }
        }

        /** Returns whether inter prediction from one reference frame requires spatial scaling.
         * 
         * The current frame uses its coded width, while the stored reference surface uses the
         * reference frame's upscaled width. AV1 render-size hints do not participate in this test.
         * 
         * @param currentFrameSize the current frame dimensions
         * @param referenceFrameSize the selected reference frame dimensions
         * @return whether the reference surface dimensions differ from the current coded dimensions
         */
        fun referenceFrameIsScaled(
            currentFrameSize: FrameSize,
            referenceFrameSize: FrameSize
        ): Boolean {
            val nonNullCurrentFrameSize = requireNotNullCompat(
                currentFrameSize,
                "currentFrameSize"
            )
            val nonNullReferenceFrameSize = requireNotNullCompat(
                referenceFrameSize,
                "referenceFrameSize"
            )
            return nonNullCurrentFrameSize.codedWidth() != nonNullReferenceFrameSize.upscaledWidth()
                    || nonNullCurrentFrameSize.height() != nonNullReferenceFrameSize.height()
        }

        /** Returns the provisional motion-vector candidate index used by one single-reference mode.
         * 
         * @param singleInterMode the decoded single-reference inter mode
         * @param drlIndex the decoded provisional dynamic-reference-list index
         * @return the provisional motion-vector candidate index used by one single-reference mode
         */
        fun motionVectorCandidateIndex(
            singleInterMode: SingleInterPredictionMode,
            drlIndex: Int
        ): Int {
            return when (requireNotNullCompat(
                singleInterMode,
                "singleInterMode"
            )) {
                SingleInterPredictionMode.GLOBALMV, SingleInterPredictionMode.NEARESTMV -> 0
                SingleInterPredictionMode.NEARMV, SingleInterPredictionMode.NEWMV -> drlIndex
            }
        }

        /** Returns the provisional motion-vector candidate index used by one compound mode.
         * 
         * @param compoundInterMode the decoded compound inter mode
         * @param drlIndex the decoded provisional dynamic-reference-list index
         * @return the provisional motion-vector candidate index used by one compound mode
         */
        fun motionVectorCandidateIndex(
            compoundInterMode: CompoundInterPredictionMode,
            drlIndex: Int
        ): Int {
            val nonNullCompoundInterMode = requireNotNullCompat(
                compoundInterMode,
                "compoundInterMode"
            )
            if (nonNullCompoundInterMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV || nonNullCompoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEARESTMV || nonNullCompoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEWMV || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEARESTMV) {
                return 0
            }
            return drlIndex
        }

        /** Resolves the single-reference motion-vector predictor chosen for one decoded single inter mode.
         * 
         * @param singleInterMode the decoded single-reference inter mode
         * @param candidate the provisional motion-vector candidate selected for that mode
         * @param globalMotionVector the frame-level global motion vector for the selected reference
         * @return the single-reference motion-vector predictor chosen for the decoded mode
         */
        fun resolveSingleMotionVector(
            singleInterMode: SingleInterPredictionMode,
            candidate: ProvisionalMotionVectorCandidate,
            globalMotionVector: MotionVector
        ): InterMotionVector {
            val nonNullSingleInterMode = requireNotNullCompat(
                singleInterMode,
                "singleInterMode"
            )
            val nonNullCandidate =
                requireNotNullCompat(candidate, "candidate")
            val nonNullGlobalMotionVector =
                requireNotNullCompat(globalMotionVector, "globalMotionVector")
            return when (nonNullSingleInterMode) {
                SingleInterPredictionMode.GLOBALMV -> InterMotionVector.resolved(
                    nonNullGlobalMotionVector
                )

                SingleInterPredictionMode.NEARESTMV, SingleInterPredictionMode.NEARMV -> nonNullCandidate.motionVector0()
                    .asResolved()

                SingleInterPredictionMode.NEWMV -> nonNullCandidate.motionVector0().asPredicted()
            }
        }

        /** Resolves the first compound-reference motion-vector predictor chosen for one decoded compound mode.
         * 
         * @param compoundInterMode the decoded compound inter mode
         * @param candidate the provisional motion-vector candidate selected for that mode
         * @param globalMotionVector the frame-level global motion vector for the first reference
         * @return the first compound-reference motion-vector predictor chosen for the decoded mode
         */
        fun resolveCompoundMotionVector0(
            compoundInterMode: CompoundInterPredictionMode,
            candidate: ProvisionalMotionVectorCandidate,
            globalMotionVector: MotionVector
        ): InterMotionVector {
            val nonNullCompoundInterMode = requireNotNullCompat(
                compoundInterMode,
                "compoundInterMode"
            )
            val nonNullCandidate =
                requireNotNullCompat(candidate, "candidate")
            if (nonNullCompoundInterMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV) {
                return InterMotionVector.resolved(
                    requireNotNullCompat(
                        globalMotionVector,
                        "globalMotionVector"
                    )
                )
            }
            if (nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEARESTMV || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEARMV || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                return nonNullCandidate.motionVector0().asPredicted()
            }
            return nonNullCandidate.motionVector0().asResolved()
        }

        /** Resolves the second compound-reference motion-vector predictor chosen for one decoded compound mode.
         * 
         * @param compoundInterMode the decoded compound inter mode
         * @param candidate the provisional motion-vector candidate selected for that mode
         * @param globalMotionVector the frame-level global motion vector for the second reference
         * @return the second compound-reference motion-vector predictor chosen for the decoded mode
         */
        fun resolveCompoundMotionVector1(
            compoundInterMode: CompoundInterPredictionMode,
            candidate: ProvisionalMotionVectorCandidate,
            globalMotionVector: MotionVector
        ): InterMotionVector {
            val nonNullCompoundInterMode = requireNotNullCompat(
                compoundInterMode,
                "compoundInterMode"
            )
            val nonNullCandidate =
                requireNotNullCompat(candidate, "candidate")
            if (nonNullCompoundInterMode == CompoundInterPredictionMode.GLOBALMV_GLOBALMV) {
                return InterMotionVector.resolved(
                    requireNotNullCompat(
                        globalMotionVector,
                        "globalMotionVector"
                    )
                )
            }
            val motionVector1 = nonNullCandidate.motionVector1()
            checkNotNull(motionVector1) { "Compound provisional motion-vector candidates must carry a secondary vector" }
            if (nonNullCompoundInterMode == CompoundInterPredictionMode.NEARESTMV_NEWMV || nonNullCompoundInterMode == CompoundInterPredictionMode.NEARMV_NEWMV || nonNullCompoundInterMode == CompoundInterPredictionMode.NEWMV_NEWMV) {
                return motionVector1.asPredicted()
            }
            return motionVector1.asResolved()
        }

        /** Rounds one signed fixed-point global-motion component by its magnitude.
         * 
         * @param value the signed fixed-point component
         * @param shift the number of fractional bits to discard
         * @return the signed rounded component
         */
        fun roundGlobalMotionComponent(value: Long, shift: Int): Int {
            val roundedMagnitude = (abs(value) + (1L shl (shift - 1))) shr shift
            return (if (value < 0) -roundedMagnitude else roundedMagnitude).toInt()
        }

        /** Rounds one eighth-pel component to AV1's integer-motion-vector precision.
         * 
         * @param value the component in eighth-pel units
         * @return the component rounded to a multiple of eight
         */
        fun roundToIntegerMotionVectorPrecision(value: Int): Int {
            val signExtension = if (value < 0) -1 else 0
            return (value - signExtension + 3) and 7.inv()
        }

        /** Returns the luma palette dimension inside the AV1 aligned frame boundary.
         * 
         * @param start the absolute block start coordinate on one luma axis
         * @param fullDimension the coded block dimension on that axis
         * @param frameDimension the AV1 MI-grid-aligned frame dimension on that axis
         * @return the clipped luma dimension, in pixels
         */
        fun visiblePaletteLumaDimension(
            start: Int,
            fullDimension: Int,
            frameDimension: Int
        ): Int {
            return max(1, min(fullDimension, frameDimension - start))
        }

        /** Returns the AV1 luma frame dimension rounded up to the 8-pixel MI allocation grid.
         * 
         * @param frameDimension the unaligned frame dimension in pixels
         * @return the AV1 MI-grid-aligned frame dimension in pixels
         */
        fun alignedPaletteFrameDimension(frameDimension: Int): Int {
            return ((frameDimension + 7) shr 3) shl 3
        }

        /** Returns the extra chroma samples used by AV1 for sub-8x8 chroma palette blocks.
         * 
         * @param lumaDimension the coded luma block dimension in pixels
         * @param subsampling the chroma subsampling shift for the axis
         * @return the number of extra chroma samples on this axis
         */
        fun chromaSub8Extra(lumaDimension: Int, subsampling: Int): Int {
            return if ((lumaDimension shr subsampling) < 4) 2 else 0
        }

        /** Returns the palette dimension for a subsampled plane.
         * 
         * @param lumaDimension the luma dimension in pixels
         * @param subsampling the chroma subsampling shift for the axis
         * @param sub8Extra the extra samples required for sub-8x8 chroma blocks
         * @return the palette dimension for the subsampled plane
         */
        fun planeDimension(lumaDimension: Int, subsampling: Int, sub8Extra: Int): Int {
            return (lumaDimension shr subsampling) + sub8Extra
        }

        /** Builds the palette-order permutation and context for one palette color-map sample.
         * 
         * @param indices the unpacked palette indices decoded so far
         * @param stride the unpacked palette row stride in pixels
         * @param x the current X coordinate in pixels
         * @param y the current Y coordinate in pixels
         * @param paletteSize the number of active palette colors
         * @param order the reusable destination array that receives the palette-order permutation
         * @param scores the reusable workspace that receives palette-neighbor scores
         * @return the zero-based palette color-map context in `[0, 5)`
         */
        fun buildPaletteOrder(
            indices: ByteArray,
            stride: Int,
            x: Int,
            y: Int,
            paletteSize: Int,
            order: IntArray,
            scores: IntArray
        ): Int {
            fillCompat(scores, 0)
            for (i in 0..<PALETTE_COLOR_COUNT) {
                order[i] = i
            }

            if (x > 0) {
                scores[indices[y * stride + x - 1].toInt() and 0xFF] += 2
            }
            if (y > 0 && x > 0) {
                scores[indices[(y - 1) * stride + x - 1].toInt() and 0xFF]++
            }
            if (y > 0) {
                scores[indices[(y - 1) * stride + x].toInt() and 0xFF] += 2
            }

            for (i in PALETTE_COLOR_HASH_MULTIPLIERS.indices) {
                var maxScore = scores[i]
                var maxIndex = i
                for (j in i + 1..<paletteSize) {
                    if (scores[j] > maxScore) {
                        maxScore = scores[j]
                        maxIndex = j
                    }
                }
                if (maxIndex != i) {
                    val maxColorOrder = order[maxIndex]
                    for (j in maxIndex downTo i + 1) {
                        scores[j] = scores[j - 1]
                        order[j] = order[j - 1]
                    }
                    scores[i] = maxScore
                    order[i] = maxColorOrder
                }
            }

            var contextHash = 0
            for (i in PALETTE_COLOR_HASH_MULTIPLIERS.indices) {
                contextHash += scores[i] * PALETTE_COLOR_HASH_MULTIPLIERS[i]
            }
            val context: Int = PALETTE_COLOR_CONTEXTS_BY_HASH[contextHash]
            check(context >= 0) { "Invalid palette color context hash" }
            return context
        }

        /** Packs one unpacked palette map to two 4-bit entries per byte and fills invisible edges.
         * 
         * @param unpacked the unpacked palette map in raster order
         * @param fullWidth the coded palette width in pixels
         * @param fullHeight the coded palette height in pixels
         * @param visibleWidth the visible palette width in pixels
         * @param visibleHeight the visible palette height in pixels
         * @return the packed palette map with invisible edges replicated
         */
        fun finishPaletteIndices(
            unpacked: ByteArray,
            fullWidth: Int,
            fullHeight: Int,
            visibleWidth: Int,
            visibleHeight: Int
        ): ByteArray {
            val packedStride = fullWidth shr 1
            val visiblePackedWidth = visibleWidth shr 1
            val packed = ByteArray(packedStride * fullHeight)
            for (y in 0..<visibleHeight) {
                val sourceRow = y * fullWidth
                val packedRow = y * packedStride
                for (x in 0..<visiblePackedWidth) {
                    packed[packedRow + x] = ((unpacked[sourceRow + (x shl 1)].toInt() and 0x0F)
                            or ((unpacked[sourceRow + (x shl 1) + 1].toInt() and 0x0F) shl 4)).toByte()
                }
                if (visiblePackedWidth < packedStride) {
                    fillCompat(
                        packed,
                        packedRow + visiblePackedWidth,
                        packedRow + packedStride,
                        ((unpacked[sourceRow + visibleWidth - 1].toInt() and 0xFF) * 0x11).toByte()
                    )
                }
            }
            if (visibleHeight < fullHeight) {
                val lastVisibleRow = (visibleHeight - 1) * packedStride
                for (y in visibleHeight..<fullHeight) {
                    arraycopy(packed, lastVisibleRow, packed, y * packedStride, packedStride)
                }
            }
            return packed
        }

        /** Merges the current left and above palette caches into one sorted unique cache.
         * 
         * @param destination the destination array that receives the merged cache
         * @param plane the palette plane index, where `0` is Y and `1` is U
         * @param x4 the local X coordinate in 4x4 units
         * @param y4 the local Y coordinate in 4x4 units
         * @param leftCacheSize the left-edge palette size
         * @param aboveCacheSize the above-edge palette size
         * @param neighborContext the neighbor context that owns the palette caches
         * @return the merged cache size written into `destination`
         */
        fun mergePaletteCache(
            destination: IntArray,
            plane: Int,
            x4: Int,
            y4: Int,
            leftCacheSize: Int,
            aboveCacheSize: Int,
            neighborContext: BlockNeighborContext
        ): Int {
            var leftIndex = 0
            var aboveIndex = 0
            var cacheSize = 0
            while (leftIndex < leftCacheSize && aboveIndex < aboveCacheSize) {
                val left = neighborContext.leftPaletteEntry(plane, y4, leftIndex)
                val above = neighborContext.abovePaletteEntry(plane, x4, aboveIndex)
                if (left < above) {
                    cacheSize = appendUnique(destination, cacheSize, left)
                    leftIndex++
                } else {
                    if (above == left) {
                        leftIndex++
                    }
                    cacheSize = appendUnique(destination, cacheSize, above)
                    aboveIndex++
                }
            }
            while (leftIndex < leftCacheSize) {
                cacheSize = appendUnique(
                    destination,
                    cacheSize,
                    neighborContext.leftPaletteEntry(plane, y4, leftIndex++)
                )
            }
            while (aboveIndex < aboveCacheSize) {
                cacheSize = appendUnique(
                    destination,
                    cacheSize,
                    neighborContext.abovePaletteEntry(plane, x4, aboveIndex++)
                )
            }
            return cacheSize
        }

        /** Appends one value to a sorted unique cache when it differs from the previous entry.
         * 
         * @param destination the destination sorted unique cache
         * @param size the current number of written entries
         * @param value the next candidate palette value
         * @return the updated number of written entries
         */
        fun appendUnique(destination: IntArray, size: Int, value: Int): Int {
            if (size == 0 || destination[size - 1] != value) {
                destination[size] = value
                return size + 1
            }
            return size
        }

        /** Returns the minimum bit width needed to code a positive unsigned integer range.
         * 
         * @param value the positive unsigned integer range
         * @return the minimum bit width needed to code the supplied range
         */
        fun bitWidth(value: Int): Int {
            return Int.SIZE_BITS - value.countLeadingZeroBits()
        }

        /** Returns a valid segment identifier, or `0` when prediction produced an out-of-range value.
         * 
         * @param segmentId the predicted segment identifier
         * @param lastActiveSegmentId the highest active segment identifier, or `-1`
         * @return a segment identifier safe for the active segmentation table
         */
        fun validSegmentIdOrZero(segmentId: Int, lastActiveSegmentId: Int): Int {
            return if (segmentId >= 0 && segmentId <= lastActiveSegmentId && segmentId < 8) segmentId else 0
        }

        /** Applies the AV1 `neg_deinterleave` mapping used by segment-id decoding.
         * 
         * @param diff the decoded diff symbol
         * @param reference the predicted segment identifier
         * @param max the exclusive upper bound passed by the bitstream syntax
         * @return the deinterleaved segment identifier candidate
         */
        fun negDeinterleave(diff: Int, reference: Int, max: Int): Int {
            if (reference == 0) {
                return diff
            }
            if (reference >= max - 1) {
                return max - diff - 1
            }
            if (reference * 2 < max) {
                if (diff <= reference * 2) {
                    return if ((diff and 1) != 0)
                        reference + ((diff + 1) shr 1)
                    else
                        reference - (diff shr 1)
                }
                return diff
            }
            if (diff <= (max - reference - 1) * 2) {
                return if ((diff and 1) != 0)
                    reference + ((diff + 1) shr 1)
                else
                    reference - (diff shr 1)
            }
            return max - diff - 1
        }

        /** Returns whether the supplied block size supports directional angle-delta syntax.
         * 
         * @param size the block size to test
         * @return whether the supplied block size supports directional angle-delta syntax
         */
        fun supportsAngleDelta(size: BlockSize): Boolean {
            return size.width4() * size.height4() >= 4
        }
    }
}
