// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.decode.TileBlockHeaderReader.BlockHeader
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode
import org.glavo.avif.internal.av1.model.ResidualLayout
import org.glavo.avif.internal.av1.model.TransformLayout
import org.glavo.avif.internal.av1.model.TransformResidualUnit
import org.glavo.avif.internal.av1.model.TransformSize
import org.glavo.avif.internal.av1.model.TransformType
import org.glavo.avif.internal.av1.model.TransformUnit
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode


/** Reads AV1 luma and chroma transform-coefficient syntax from one tile bitstream.
 * 
 * The reader decodes the luma, chroma-U, and chroma-V residual units exposed by
 * `TransformLayout`, including pixel-clipped chroma footprints along the frame fringe.
 * Retained transform and residual positions are frame-relative, while coefficient-neighbor
 * contexts remain tile-local as required by AV1 tile entropy independence.
 * `TX_4X4` keeps its dedicated context helpers, while larger transform sizes use the same
 * coefficient-neighbor token contexts so multi-coefficient blocks do not fall back to fixed
 * probabilities once AC residuals are present.
 * 
 * @param strictStdCompliance whether malformed coefficient syntax must be rejected
 */
class TileResidualSyntaxReader constructor(
    tileContext: TileDecodeContext,
    strictStdCompliance: Boolean = false
) {
    /** The tile-local decode state that owns the active sequence and frame headers. */
    private val tileContext: TileDecodeContext

    /** The typed syntax reader used to consume coefficient-side entropy symbols. */
    private val syntaxReader: TileSyntaxReader

    /** The tile origin on the frame luma-grid X axis. */
    private val frameOffsetX4: Int

    /** The tile origin on the frame luma-grid Y axis. */
    private val frameOffsetY4: Int

    /** Reusable zero-filled coefficient-level context grid for the current residual unit. */
    private val coefficientLevelGrid = CoefficientLevelGrid()

    init {
        this.tileContext = requireNotNullCompat(tileContext, "tileContext")
        this.syntaxReader = TileSyntaxReader(this.tileContext, strictStdCompliance)
        this.frameOffsetX4 = this.tileContext.startX() shr 2
        this.frameOffsetY4 = this.tileContext.startY() shr 2
    }

    /** Decodes residual syntax for one already-decoded block.
     * 
     * @param header the decoded leaf block header whose position is tile-local
     * @param transformLayout the frame-relative decoded transform layout for the same block
     * @param neighborContext the mutable neighbor context that supplies coefficient skip contexts
     * @return the decoded residual layout for the supplied block
     */
    fun read(
        header: BlockHeader,
        transformLayout: TransformLayout,
        neighborContext: BlockNeighborContext
    ): ResidualLayout {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val nonNullTransformLayout =
            requireNotNullCompat(transformLayout, "transformLayout")
        val nonNullNeighborContext =
            requireNotNullCompat(neighborContext, "neighborContext")
        require(!(nonNullHeader.position().x4 + frameOffsetX4 != nonNullTransformLayout.position().x4 || nonNullHeader.position().y4 + frameOffsetY4 != nonNullTransformLayout.position().y4 || nonNullHeader.size() != nonNullTransformLayout.blockSize())) { "Header and transform layout must describe the same block" }

        val residualUnits: Array<TransformResidualUnit> =
            arrayOfNullsCompat<TransformResidualUnit>(nonNullTransformLayout.lumaUnitCount())
        val hasChromaResiduals =
            nonNullHeader.hasChroma() && nonNullTransformLayout.chromaUnitCount() != 0
        val chromaUnitCount =
            if (hasChromaResiduals) nonNullTransformLayout.chromaUnitCount() else 0
        val chromaUUnits: Array<TransformResidualUnit> = if (chromaUnitCount == 0)
            EMPTY_RESIDUAL_UNITS
        else
            arrayOfNullsCompat<TransformResidualUnit>(chromaUnitCount)
        val chromaVUnits: Array<TransformResidualUnit> = if (chromaUnitCount == 0)
            EMPTY_RESIDUAL_UNITS
        else
            arrayOfNullsCompat<TransformResidualUnit>(chromaUnitCount)

        val blockPosition = nonNullTransformLayout.position()
        val visibleWidth4 = nonNullTransformLayout.visibleWidth4()
        val visibleHeight4 = nonNullTransformLayout.visibleHeight4()
        val onlyCoefficientRegion = visibleWidth4 <= 16 && visibleHeight4 <= 16
        var offsetY4 = 0
        while (offsetY4 < visibleHeight4) {
            val regionStartY4 = blockPosition.y4 + offsetY4
            val regionEndY4 = blockPosition.y4 + min(offsetY4 + 16, visibleHeight4)
            var offsetX4 = 0
            while (offsetX4 < visibleWidth4) {
                val regionStartX4 = blockPosition.x4 + offsetX4
                val regionEndX4 = blockPosition.x4 + min(offsetX4 + 16, visibleWidth4)
                readLumaResidualRegion(
                    nonNullHeader,
                    nonNullTransformLayout,
                    residualUnits,
                    nonNullNeighborContext,
                    regionStartX4,
                    regionStartY4,
                    regionEndX4,
                    regionEndY4
                )
                if (hasChromaResiduals) {
                    readChromaResidualRegion(
                        nonNullHeader,
                        nonNullTransformLayout,
                        residualUnits,
                        chromaUUnits,
                        CHROMA_PLANE_U,
                        nonNullNeighborContext,
                        onlyCoefficientRegion,
                        regionStartX4,
                        regionStartY4,
                        regionEndX4,
                        regionEndY4
                    )
                    readChromaResidualRegion(
                        nonNullHeader,
                        nonNullTransformLayout,
                        residualUnits,
                        chromaVUnits,
                        CHROMA_PLANE_V,
                        nonNullNeighborContext,
                        onlyCoefficientRegion,
                        regionStartX4,
                        regionStartY4,
                        regionEndX4,
                        regionEndY4
                    )
                }
                offsetX4 += 16
            }
            offsetY4 += 16
        }
        return ResidualLayout.fromOwnedUnits(
            nonNullTransformLayout.position(),
            nonNullTransformLayout.blockSize(),
            residualUnits,
            chromaUUnits,
            chromaVUnits
        )
    }

    /** Decodes the luma transform units whose origins lie in one 64x64 coefficient region.
     * 
     * @param header the owning leaf block header
     * @param transformLayout the owning block-level transform layout
     * @param residualUnits the destination array parallel to the layout's luma units
     * @param neighborContext the mutable neighbor context that supplies entropy contexts
     * @param regionStartX4 the inclusive region start on the luma-grid X axis
     * @param regionStartY4 the inclusive region start on the luma-grid Y axis
     * @param regionEndX4 the exclusive region end on the luma-grid X axis
     * @param regionEndY4 the exclusive region end on the luma-grid Y axis
     */
    private fun readLumaResidualRegion(
        header: BlockHeader,
        transformLayout: TransformLayout,
        residualUnits: Array<TransformResidualUnit>,
        neighborContext: BlockNeighborContext,
        regionStartX4: Int,
        regionStartY4: Int,
        regionEndX4: Int,
        regionEndY4: Int
    ) {
        for (i in 0..<transformLayout.lumaUnitCount()) {
            val transformUnit = transformLayout.lumaUnit(i)
            if (!isInCoefficientRegion(
                    transformUnit,
                    regionStartX4,
                    regionStartY4,
                    regionEndX4,
                    regionEndY4
                )
            ) {
                continue
            }

            val visibleWidthPixels: Int = visibleLumaWidthPixels(transformLayout, transformUnit)
            val visibleHeightPixels: Int = visibleLumaHeightPixels(transformLayout, transformUnit)
            val residualUnit = if (header.skip())
                createAllZeroUnit(
                    transformUnit.position,
                    transformUnit.size,
                    visibleWidthPixels,
                    visibleHeightPixels
                )
            else
                readLumaResidualUnit(header, transformLayout, transformUnit, neighborContext)
            residualUnits[i] = residualUnit
            neighborContext.updateLumaCoefficientContext(
                transformUnit.position.x4 - frameOffsetX4,
                transformUnit.position.y4 - frameOffsetY4,
                transformUnit.size,
                residualUnit.coefficientContextByte()
            )
        }
    }

    /** Returns the horizontal chroma subsampling shift used by the active sequence chroma format.
     * 
     * @return the horizontal chroma subsampling shift used by the active sequence chroma format
     */
    private fun chromaSubsamplingX(): Int {
        return when (tileContext.sequenceHeader().colorConfig().chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> 1
        }
    }

    /** Returns the vertical chroma subsampling shift used by the active sequence chroma format.
     * 
     * @return the vertical chroma subsampling shift used by the active sequence chroma format
     */
    private fun chromaSubsamplingY(): Int {
        return when (tileContext.sequenceHeader().colorConfig().chromaFormat) {
            Av1ChromaFormat.MONOCHROME, Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> 0
            Av1ChromaFormat.YUV420 -> 1
        }
    }

    /** Decodes one non-skipped luma transform residual unit.
     * 
     * @param header the owning leaf block header
     * @param transformLayout the owning block-level transform layout
     * @param transformUnit the luma transform unit to decode
     * @param neighborContext the mutable neighbor context that supplies entropy contexts
     * @return the decoded luma transform residual unit
     */
    private fun readLumaResidualUnit(
        header: BlockHeader,
        transformLayout: TransformLayout,
        transformUnit: TransformUnit,
        neighborContext: BlockNeighborContext
    ): TransformResidualUnit {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val nonNullTransformUnit =
            requireNotNullCompat(transformUnit, "transformUnit")
        val nonNullTransformLayout =
            requireNotNullCompat(transformLayout, "transformLayout")
        val nonNullNeighborContext =
            requireNotNullCompat(neighborContext, "neighborContext")

        val visibleWidthPixels: Int =
            visibleLumaWidthPixels(nonNullTransformLayout, nonNullTransformUnit)
        val visibleHeightPixels: Int =
            visibleLumaHeightPixels(nonNullTransformLayout, nonNullTransformUnit)
        val localX4 = nonNullTransformUnit.position.x4 - frameOffsetX4
        val localY4 = nonNullTransformUnit.position.y4 - frameOffsetY4
        val coefficientSkipContext = nonNullNeighborContext.lumaCoefficientSkipContext(
            nonNullHeader.size(),
            localX4,
            localY4,
            nonNullTransformUnit.size
        )
        if (syntaxReader.readCoefficientSkipFlag(
                nonNullTransformUnit.size,
                coefficientSkipContext
            )
        ) {
            return createAllZeroUnit(
                nonNullTransformUnit.position,
                nonNullTransformUnit.size,
                visibleWidthPixels,
                visibleHeightPixels
            )
        }

        return readNonSkippedResidualUnit(
            nonNullTransformUnit.position,
            nonNullTransformUnit.size,
            visibleWidthPixels,
            visibleHeightPixels,
            false,
            lumaTransformType(nonNullHeader, nonNullTransformUnit.size),
            nonNullNeighborContext.lumaDcSignContext(localX4, localY4, nonNullTransformUnit.size)
        )
    }

    /** Decodes the chroma transform units whose origins lie in one 64x64 luma-grid coefficient region.
     * 
     * @param header the owning leaf block header
     * @param transformLayout the owning block-level transform layout
     * @param lumaResidualUnits the decoded luma residual units used for inter transform-type inference
     * @param residualUnits the destination array parallel to the layout's chroma units
     * @param plane the chroma plane index, where `0` is U and `1` is V
     * @param neighborContext the mutable neighbor context that supplies and stores coefficient contexts
     * @param includeAllUnits whether this is the block's only coefficient region
     * @param regionStartX4 the inclusive region start on the shared luma-grid X axis
     * @param regionStartY4 the inclusive region start on the shared luma-grid Y axis
     * @param regionEndX4 the exclusive region end on the shared luma-grid X axis
     * @param regionEndY4 the exclusive region end on the shared luma-grid Y axis
     */
    private fun readChromaResidualRegion(
        header: BlockHeader,
        transformLayout: TransformLayout,
        lumaResidualUnits: Array<TransformResidualUnit>,
        residualUnits: Array<TransformResidualUnit>,
        plane: Int,
        neighborContext: BlockNeighborContext,
        includeAllUnits: Boolean,
        regionStartX4: Int,
        regionStartY4: Int,
        regionEndX4: Int,
        regionEndY4: Int
    ) {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val nonNullTransformLayout =
            requireNotNullCompat(transformLayout, "transformLayout")
        val nonNullLumaResidualUnits = requireNotNullCompat(
            lumaResidualUnits,
            "lumaResidualUnits"
        )
        val nonNullResidualUnits =
            requireNotNullCompat(residualUnits, "residualUnits")
        val nonNullNeighborContext =
            requireNotNullCompat(neighborContext, "neighborContext")

        for (i in 0..<nonNullTransformLayout.chromaUnitCount()) {
            val transformUnit = nonNullTransformLayout.chromaUnit(i)
            if (!includeAllUnits && !isInCoefficientRegion(
                    transformUnit,
                    regionStartX4,
                    regionStartY4,
                    regionEndX4,
                    regionEndY4
                )
            ) {
                continue
            }
            val unitPosition = transformUnit.position
            val unitSize = transformUnit.size
            val localX4 = unitPosition.x4 - frameOffsetX4
            val localY4 = unitPosition.y4 - frameOffsetY4
            val visibleUnitWidthPixels =
                visibleChromaUnitWidthPixels(nonNullTransformLayout, transformUnit)
            val visibleUnitHeightPixels =
                visibleChromaUnitHeightPixels(nonNullTransformLayout, transformUnit)
            val residualUnit: TransformResidualUnit?
            if (nonNullHeader.skip()) {
                residualUnit = createAllZeroUnit(
                    unitPosition,
                    unitSize,
                    visibleUnitWidthPixels,
                    visibleUnitHeightPixels
                )
            } else {
                val coefficientSkipContext = nonNullNeighborContext.chromaCoefficientSkipContext(
                    plane,
                    nonNullHeader.size(),
                    localX4,
                    localY4,
                    unitSize
                )
                val coefficientSkip =
                    syntaxReader.readCoefficientSkipFlag(unitSize, coefficientSkipContext)
                if (coefficientSkip) {
                    residualUnit = createAllZeroUnit(
                        unitPosition,
                        unitSize,
                        visibleUnitWidthPixels,
                        visibleUnitHeightPixels
                    )
                } else {
                    residualUnit = readNonSkippedResidualUnit(
                        unitPosition,
                        unitSize,
                        visibleUnitWidthPixels,
                        visibleUnitHeightPixels,
                        true,
                        chromaTransformType(
                            nonNullHeader,
                            unitSize,
                            lumaTransformTypeAt(
                                nonNullLumaResidualUnits,
                                transformUnit,
                                nonNullTransformLayout.position()
                            )
                        ),
                        nonNullNeighborContext.chromaDcSignContext(
                            plane,
                            localX4,
                            localY4,
                            unitSize
                        )
                    )
                }
            }
            nonNullNeighborContext.updateChromaCoefficientContext(
                plane,
                localX4,
                localY4,
                unitSize,
                residualUnit.coefficientContextByte()
            )
            nonNullResidualUnits[i] = residualUnit
        }
    }

    /** Decodes one non-skipped transform residual unit using the supplied plane-specific contexts.
     * 
     * @param position the transform origin in the current block-position grid
     * @param transformSize the active transform size
     * @param visibleWidthPixels the exact visible residual width in pixels
     * @param visibleHeightPixels the exact visible residual height in pixels
     * @param chroma whether the syntax belongs to a chroma plane
     * @param transformType the active transform type
     * @param dcSignContext the DC-sign context for the current unit
     * @return the decoded non-skipped transform residual unit
     */
    private fun readNonSkippedResidualUnit(
        position: BlockPosition,
        transformSize: TransformSize,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        chroma: Boolean,
        transformType: TransformType,
        dcSignContext: Int
    ): TransformResidualUnit {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val nonNullTransformType =
            requireNotNullCompat(transformType, "transformType")

        val endOfBlockIndex = syntaxReader.readEndOfBlockIndex(
            nonNullTransformSize,
            chroma,
            nonNullTransformType.oneDimensional()
        )
        if (nonNullTransformSize == TransformSize.TX_4X4) {
            return readFourByFourResidualUnit(
                nonNullPosition,
                visibleWidthPixels,
                visibleHeightPixels,
                chroma,
                nonNullTransformType,
                endOfBlockIndex,
                dcSignContext
            )
        }
        return readGenericResidualUnit(
            nonNullPosition,
            nonNullTransformSize,
            visibleWidthPixels,
            visibleHeightPixels,
            chroma,
            nonNullTransformType,
            endOfBlockIndex,
            dcSignContext
        )
    }

    /** Decodes the fully supported two-dimensional `TX_4X4` residual syntax for one plane.
     * 
     * @param position the transform origin in the current block-position grid
     * @param visibleWidthPixels the exact visible residual width in pixels
     * @param visibleHeightPixels the exact visible residual height in pixels
     * @param chroma whether the syntax belongs to a chroma plane
     * @param transformType the active transform type
     * @param endOfBlockIndex the already-decoded end-of-block scan index
     * @param dcSignContext the plane-specific DC-sign context
     * @return the decoded `TX_4X4` residual unit
     */
    private fun readFourByFourResidualUnit(
        position: BlockPosition,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        chroma: Boolean,
        transformType: TransformType,
        endOfBlockIndex: Int,
        dcSignContext: Int
    ): TransformResidualUnit {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullTransformType =
            requireNotNullCompat(transformType, "transformType")
        val coefficients = if (endOfBlockIndex > 0) IntArray(16) else null
        val levelBytes = coefficientLevelGrid.reset(
            FOUR_BY_FOUR_LEVEL_GRID_SIZE,
            FOUR_BY_FOUR_LEVEL_GRID_SIZE
        )

        if (endOfBlockIndex > 0) {
            val decodedCoefficients = requireNotNullCompat(coefficients, "coefficients")
            val lastX: Int = fourByFourLevelX(nonNullTransformType, endOfBlockIndex)
            val lastY: Int = fourByFourLevelY(nonNullTransformType, endOfBlockIndex)
            var lastToken = syntaxReader.readEndOfBlockBaseToken(
                TransformSize.TX_4X4,
                chroma,
                endOfBlockTokenContext(endOfBlockIndex, TransformSize.TX_4X4)
            )
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                    TransformSize.TX_4X4,
                    chroma,
                    fourByFourEndOfBlockHighTokenContext(nonNullTransformType, lastX, lastY)
                )
            }
            decodedCoefficients[fourByFourCoefficientIndex(nonNullTransformType, endOfBlockIndex)] =
                lastToken
            levelBytes.set(lastX, lastY, coefficientLevelByte(lastToken))

            for (scanIndex in endOfBlockIndex - 1 downTo 1) {
                val x: Int = fourByFourLevelX(nonNullTransformType, scanIndex)
                val y: Int = fourByFourLevelY(nonNullTransformType, scanIndex)
                var token = syntaxReader.readBaseToken(
                    TransformSize.TX_4X4,
                    chroma,
                    fourByFourBaseTokenContext(nonNullTransformType, levelBytes, x, y)
                )
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                        TransformSize.TX_4X4,
                        chroma,
                        fourByFourHighTokenContext(nonNullTransformType, levelBytes, x, y)
                    )
                }
                decodedCoefficients[fourByFourCoefficientIndex(nonNullTransformType, scanIndex)] =
                    token
                levelBytes.set(x, y, coefficientLevelByte(token))
            }
        }

        val dcBaseContext = if (nonNullTransformType.oneDimensional())
            fourByFourBaseTokenContext(nonNullTransformType, levelBytes, 0, 0)
        else
            0
        var dcToken = if (endOfBlockIndex == 0)
            syntaxReader.readEndOfBlockBaseToken(TransformSize.TX_4X4, chroma, 0)
        else
            syntaxReader.readBaseToken(TransformSize.TX_4X4, chroma, dcBaseContext)
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(
                TransformSize.TX_4X4,
                chroma,
                fourByFourDcHighTokenContext(nonNullTransformType, levelBytes)
            )
        }
        if (endOfBlockIndex == 0) {
            return createDcOnlyResidualUnit(
                nonNullPosition,
                TransformSize.TX_4X4,
                nonNullTransformType,
                visibleWidthPixels,
                visibleHeightPixels,
                chroma,
                dcSignContext,
                dcToken
            )
        }

        val decodedCoefficients = requireNotNullCompat(coefficients, "coefficients")
        var cumulativeLevel = 0
        var dcSign = 0
        if (dcToken != 0) {
            val negative = syntaxReader.readDcSignFlag(chroma, dcSignContext)
            dcSign = if (negative) -1 else 1
            if (dcToken == 15) {
                dcToken = maskCoefficientMagnitude(dcToken + syntaxReader.readCoefficientGolomb())
            }
            decodedCoefficients[0] = if (negative) -dcToken else dcToken
            cumulativeLevel += dcToken
        }

        for (scanIndex in 1..endOfBlockIndex) {
            val coefficientIndex: Int = fourByFourCoefficientIndex(nonNullTransformType, scanIndex)
            var token = decodedCoefficients[coefficientIndex]
            if (token == 0) {
                continue
            }
            val negative = syntaxReader.readCoefficientSignFlag()
            if (token == 15) {
                token = maskCoefficientMagnitude(token + syntaxReader.readCoefficientGolomb())
            }
            decodedCoefficients[coefficientIndex] = if (negative) -token else token
            cumulativeLevel += token
        }

        return TransformResidualUnit.fromOwnedCoefficients(
            nonNullPosition,
            TransformSize.TX_4X4,
            nonNullTransformType,
            endOfBlockIndex,
            decodedCoefficients,
            visibleWidthPixels,
            visibleHeightPixels,
            createNonZeroCoefficientContextByte(cumulativeLevel, dcSign)
        )
    }

    /** Decodes one non-`TX_4X4` residual unit with coefficient-neighbor token contexts.
     * 
     * @param position the transform origin in the current block-position grid
     * @param transformSize the active transform size
     * @param visibleWidthPixels the exact visible residual width in pixels
     * @param visibleHeightPixels the exact visible residual height in pixels
     * @param chroma whether the syntax belongs to a chroma plane
     * @param transformType the active transform type
     * @param endOfBlockIndex the already-decoded end-of-block scan index
     * @param dcSignContext the plane-specific DC-sign context
     * @return the decoded larger-transform residual unit
     */
    private fun readGenericResidualUnit(
        position: BlockPosition,
        transformSize: TransformSize,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        chroma: Boolean,
        transformType: TransformType,
        endOfBlockIndex: Int,
        dcSignContext: Int
    ): TransformResidualUnit {
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val nonNullTransformType =
            requireNotNullCompat(transformType, "transformType")
        val coefficients = if (endOfBlockIndex > 0)
            IntArray(nonNullTransformSize.widthPixels() * nonNullTransformSize.heightPixels())
        else
            null
        val levelBytes = resetGenericLevelGrid(nonNullTransformSize, nonNullTransformType)
        if (endOfBlockIndex > 0) {
            val decodedCoefficients = requireNotNullCompat(coefficients, "coefficients")
            val lastX: Int =
                genericLevelX(nonNullTransformSize, nonNullTransformType, endOfBlockIndex)
            val lastY: Int =
                genericLevelY(nonNullTransformSize, nonNullTransformType, endOfBlockIndex)
            var lastToken = syntaxReader.readEndOfBlockBaseToken(
                nonNullTransformSize,
                chroma,
                endOfBlockTokenContext(endOfBlockIndex, nonNullTransformSize)
            )
            if (lastToken == 3) {
                lastToken = syntaxReader.readHighToken(
                    nonNullTransformSize,
                    chroma,
                    genericEndOfBlockHighTokenContext(nonNullTransformType, lastX, lastY)
                )
            }
            decodedCoefficients[coefficientIndex(
                nonNullTransformSize,
                nonNullTransformType,
                endOfBlockIndex
            )] = lastToken
            levelBytes.set(lastX, lastY, coefficientLevelByte(lastToken))

            for (scanIndex in endOfBlockIndex - 1 downTo 1) {
                val x: Int = genericLevelX(nonNullTransformSize, nonNullTransformType, scanIndex)
                val y: Int = genericLevelY(nonNullTransformSize, nonNullTransformType, scanIndex)
                var token = syntaxReader.readBaseToken(
                    nonNullTransformSize,
                    chroma,
                    genericBaseTokenContext(
                        nonNullTransformSize,
                        nonNullTransformType,
                        levelBytes,
                        x,
                        y
                    )
                )
                if (token == 3) {
                    token = syntaxReader.readHighToken(
                        nonNullTransformSize,
                        chroma,
                        genericHighTokenContext(nonNullTransformType, levelBytes, x, y)
                    )
                }
                decodedCoefficients[coefficientIndex(
                    nonNullTransformSize,
                    nonNullTransformType,
                    scanIndex
                )] = token
                levelBytes.set(x, y, coefficientLevelByte(token))
            }
        }

        val dcBaseContext = if (endOfBlockIndex > 0 && nonNullTransformType.oneDimensional())
            genericBaseTokenContext(nonNullTransformSize, nonNullTransformType, levelBytes, 0, 0)
        else
            0
        var dcToken = if (endOfBlockIndex == 0)
            syntaxReader.readEndOfBlockBaseToken(nonNullTransformSize, chroma, 0)
        else
            syntaxReader.readBaseToken(nonNullTransformSize, chroma, dcBaseContext)
        if (dcToken == 3) {
            dcToken = syntaxReader.readHighToken(
                nonNullTransformSize,
                chroma,
                genericDcHighTokenContext(nonNullTransformType, levelBytes)
            )
        }
        if (endOfBlockIndex == 0) {
            return createDcOnlyResidualUnit(
                nonNullPosition,
                nonNullTransformSize,
                nonNullTransformType,
                visibleWidthPixels,
                visibleHeightPixels,
                chroma,
                dcSignContext,
                dcToken
            )
        }

        val decodedCoefficients = requireNotNullCompat(coefficients, "coefficients")
        var cumulativeLevel = 0
        var dcSign = 0
        if (dcToken != 0) {
            val negative = syntaxReader.readDcSignFlag(chroma, dcSignContext)
            dcSign = if (negative) -1 else 1
            if (dcToken == 15) {
                dcToken = maskCoefficientMagnitude(dcToken + syntaxReader.readCoefficientGolomb())
            }
            decodedCoefficients[0] = if (negative) -dcToken else dcToken
            cumulativeLevel += dcToken
        }

        for (scanIndex in 1..endOfBlockIndex) {
            val coefficientIndex: Int =
                coefficientIndex(nonNullTransformSize, nonNullTransformType, scanIndex)
            var token = decodedCoefficients[coefficientIndex]
            if (token == 0) {
                continue
            }
            val negative = syntaxReader.readCoefficientSignFlag()
            if (token == 15) {
                token = maskCoefficientMagnitude(token + syntaxReader.readCoefficientGolomb())
            }
            decodedCoefficients[coefficientIndex] = if (negative) -token else token
            cumulativeLevel += token
        }

        return TransformResidualUnit.fromOwnedCoefficients(
            nonNullPosition,
            nonNullTransformSize,
            nonNullTransformType,
            endOfBlockIndex,
            decodedCoefficients,
            visibleWidthPixels,
            visibleHeightPixels,
            createNonZeroCoefficientContextByte(cumulativeLevel, dcSign)
        )
    }

    /** Finishes one residual unit whose end-of-block index identifies only the DC coefficient.
     * 
     * @param position the transform origin in the current block-position grid
     * @param transformSize the active transform size
     * @param transformType the active transform type
     * @param visibleWidthPixels the exact visible residual width in pixels
     * @param visibleHeightPixels the exact visible residual height in pixels
     * @param chroma whether the syntax belongs to a chroma plane
     * @param dcSignContext the plane-specific DC-sign context
     * @param dcToken the decoded unsigned DC token before sign and Golomb extension
     * @return one compact DC-only residual unit
     */
    private fun createDcOnlyResidualUnit(
        position: BlockPosition,
        transformSize: TransformSize,
        transformType: TransformType,
        visibleWidthPixels: Int,
        visibleHeightPixels: Int,
        chroma: Boolean,
        dcSignContext: Int,
        dcToken: Int
    ): TransformResidualUnit {
        var magnitude = dcToken
        var signedDcCoefficient = 0
        var dcSign = 0
        if (magnitude != 0) {
            val negative = syntaxReader.readDcSignFlag(chroma, dcSignContext)
            dcSign = if (negative) -1 else 1
            if (magnitude == 15) {
                magnitude =
                    maskCoefficientMagnitude(magnitude + syntaxReader.readCoefficientGolomb())
            }
            signedDcCoefficient = if (negative) -magnitude else magnitude
        }
        return TransformResidualUnit.dcOnly(
            position,
            transformSize,
            transformType,
            signedDcCoefficient,
            visibleWidthPixels,
            visibleHeightPixels,
            createNonZeroCoefficientContextByte(magnitude, dcSign)
        )
    }

    /** Returns the exact visible chroma width in pixels for one chroma transform unit.
     * 
     * @param transformLayout the owning block-level transform layout
     * @param transformUnit the chroma transform unit whose visible width should be measured
     * @return the exact visible chroma width in pixels for the supplied unit
     */
    private fun visibleChromaUnitWidthPixels(
        transformLayout: TransformLayout,
        transformUnit: TransformUnit
    ): Int {
        val nonNullTransformLayout =
            requireNotNullCompat(transformLayout, "transformLayout")
        val nonNullTransformUnit =
            requireNotNullCompat(transformUnit, "transformUnit")
        val shift = chromaSubsamplingX()
        val originX4: Int = chromaOrigin4(
            nonNullTransformLayout.position().x4,
            nonNullTransformLayout.blockSize().width4(),
            shift
        )
        val visibleBlockWidthPixels: Int = chromaDimension(
            ((nonNullTransformLayout.position().x4 shl 2) + nonNullTransformLayout.visibleWidthPixels()) - (originX4 shl 2),
            shift
        )
        val relativeLumaX = (nonNullTransformUnit.position.x4 - originX4) shl 2
        val relativeChromaX = relativeLumaX shr shift
        return min(
            nonNullTransformUnit.size.widthPixels(),
            max(0, visibleBlockWidthPixels - relativeChromaX)
        )
    }

    /** Returns the exact visible chroma height in pixels for one chroma transform unit.
     * 
     * @param transformLayout the owning block-level transform layout
     * @param transformUnit the chroma transform unit whose visible height should be measured
     * @return the exact visible chroma height in pixels for the supplied unit
     */
    private fun visibleChromaUnitHeightPixels(
        transformLayout: TransformLayout,
        transformUnit: TransformUnit
    ): Int {
        val nonNullTransformLayout =
            requireNotNullCompat(transformLayout, "transformLayout")
        val nonNullTransformUnit =
            requireNotNullCompat(transformUnit, "transformUnit")
        val shift = chromaSubsamplingY()
        val originY4: Int = chromaOrigin4(
            nonNullTransformLayout.position().y4,
            nonNullTransformLayout.blockSize().height4(),
            shift
        )
        val visibleBlockHeightPixels: Int = chromaDimension(
            ((nonNullTransformLayout.position().y4 shl 2) + nonNullTransformLayout.visibleHeightPixels()) - (originY4 shl 2),
            shift
        )
        val relativeLumaY = (nonNullTransformUnit.position.y4 - originY4) shl 2
        val relativeChromaY = relativeLumaY shr shift
        return min(
            nonNullTransformUnit.size.heightPixels(),
            max(0, visibleBlockHeightPixels - relativeChromaY)
        )
    }

    /** Selects the luma transform type for one non-skipped luma residual unit.
     * 
     * @param header the owning leaf block header
     * @param transformSize the active luma transform size
     * @return the luma transform type for the supplied residual unit
     */
    private fun lumaTransformType(
        header: BlockHeader,
        transformSize: TransformSize
    ): TransformType {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val intraTransform: Boolean = intraTransformType(nonNullHeader)
        if (tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId())) {
            return TransformType.WHT_WHT
        }
        if (nonNullTransformSize.maxSquareLevel() + (if (intraTransform) 1 else 0)
            >= TransformSize.TX_64X64.maxSquareLevel()
        ) {
            return TransformType.DCT_DCT
        }
        if (tileContext.frameHeader().segmentation().qIndex(nonNullHeader.segmentId()) == 0) {
            return TransformType.DCT_DCT
        }
        if (intraTransform) {
            return syntaxReader.readIntraTransformType(
                nonNullTransformSize,
                lumaModeForTransformType(nonNullHeader),
                tileContext.frameHeader().reducedTransformSet()
            )
        }
        return syntaxReader.readInterTransformType(
            nonNullTransformSize,
            tileContext.frameHeader().reducedTransformSet()
        )
    }

    /** Selects the chroma transform type for one non-skipped chroma residual unit.
     * 
     * @param header the owning leaf block header
     * @param transformSize the active chroma transform size
     * @param lumaTransformType the colocated luma transform type for inter-derived chroma transforms
     * @return the chroma transform type for the supplied residual unit
     */
    private fun chromaTransformType(
        header: BlockHeader,
        transformSize: TransformSize,
        lumaTransformType: TransformType
    ): TransformType {
        val nonNullHeader = requireNotNullCompat(header, "header")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val nonNullLumaTransformType =
            requireNotNullCompat(lumaTransformType, "lumaTransformType")
        val intraTransform: Boolean = intraTransformType(nonNullHeader)
        if (tileContext.frameHeader().segmentation().lossless(nonNullHeader.segmentId())) {
            return TransformType.WHT_WHT
        }
        if (nonNullTransformSize.maxSquareLevel() + (if (intraTransform) 1 else 0)
            >= TransformSize.TX_64X64.maxSquareLevel()
        ) {
            return TransformType.DCT_DCT
        }
        if (intraTransform) {
            return uvIntraTransformType(nonNullHeader.uvMode())
        }
        return chromaInterTransformType(nonNullTransformSize, nonNullLumaTransformType)
    }

    /** Resets the padded larger-transform `levels` grid used by coefficient token contexts.
     * 
     * @param transformSize the active transform size
     * @param transformType the active transform type
     * @return the reusable padded zero-filled level grid
     */
    private fun resetGenericLevelGrid(
        transformSize: TransformSize,
        transformType: TransformType
    ): CoefficientLevelGrid {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val nonNullTransformType =
            requireNotNullCompat(transformType, "transformType")
        val width: Int
        val height: Int
        if (!nonNullTransformType.oneDimensional()) {
            width = clippedCoefficientWidth(nonNullTransformSize)
            height = clippedCoefficientHeight(nonNullTransformSize)
        } else if (verticalOneDimensional(nonNullTransformType)) {
            width = 4 shl min(nonNullTransformSize.log2Width4(), 3)
            height = coefficientCount(nonNullTransformSize) / width
        } else {
            width = 4 shl min(nonNullTransformSize.log2Height4(), 3)
            height = coefficientCount(nonNullTransformSize) / width
        }
        return coefficientLevelGrid.reset(width + 5, height + 5)
    }

    /** Reusable flat coefficient-level grid addressed in AV1's X-major scratch layout. */
    class CoefficientLevelGrid
    /** Creates an empty reusable coefficient-level grid. */
    {
        /** The number of stored Y coordinates per X coordinate. */
        var height = 0

        /** The flattened X-major level values. */
        var values = IntArray(0)

        /** Resizes this grid if necessary and clears the active extent.
         * 
         * @param width the stored X extent
         * @param height the stored Y extent
         * @return this zero-filled grid
         */
        fun reset(width: Int, height: Int): CoefficientLevelGrid {
            require(!(width <= 0 || height <= 0)) { "Coefficient level-grid dimensions must be positive" }
            val requiredLength = multiplyExact(width, height)
            if (values.size < requiredLength) {
                values = IntArray(requiredLength)
            } else {
                fillCompat(values, 0, requiredLength, 0)
            }
            this.height = height
            return this
        }

        /** Returns one stored level value.
         * 
         * @param x the zero-based X coordinate
         * @param y the zero-based Y coordinate
         * @return the stored level value
         */
        fun get(x: Int, y: Int): Int {
            return values[x * height + y]
        }

        /** Stores one level value.
         * 
         * @param x the zero-based X coordinate
         * @param y the zero-based Y coordinate
         * @param value the replacement level value
         */
        fun set(x: Int, y: Int, value: Int) {
            values[x * height + y] = value
        }
    }

    companion object {
        /** Shared empty residual-unit sequence for blocks without modeled chroma residuals. */
        val EMPTY_RESIDUAL_UNITS: Array<TransformResidualUnit> =
            arrayOfNullsCompat<TransformResidualUnit>(0)

        /** The AV1 coefficient-context byte written for all-zero transform blocks. */
        private const val ALL_ZERO_COEFFICIENT_CONTEXT_BYTE = 0x40

        /** The AV1 20-bit coefficient-magnitude mask applied after Golomb extension. */
        private const val COEFFICIENT_MAGNITUDE_MASK = 0xFFFFF

        /** The chroma-U plane index used by chroma coefficient-context helpers. */
        private const val CHROMA_PLANE_U = 0

        /** The chroma-V plane index used by chroma coefficient-context helpers. */
        private const val CHROMA_PLANE_V = 1

        /** The `dav1d` two-dimensional `TX_4X4` scan converted into row-major coefficient storage. */
        val FOUR_BY_FOUR_STORAGE_SCAN: IntArray = intArrayOf(
            0, 1, 4, 8,
            5, 2, 3, 6,
            9, 12, 13, 10,
            7, 11, 14, 15
        )

        /** The `dav1d` scratch-layout `TX_4X4` scan used by coefficient-token contexts. */
        val FOUR_BY_FOUR_CONTEXT_SCAN: IntArray = intArrayOf(
            0, 4, 1, 2,
            5, 8, 12, 9,
            6, 3, 7, 10,
            13, 14, 11, 15
        )

        /** The `dav1d`-compatible two-dimensional storage scan tables for every modeled transform size. */
        val DEFAULT_STORAGE_SCANS: Array<IntArray> = createDefaultScans(false)

        /** The `dav1d` scratch-layout two-dimensional context scan tables for every modeled transform size. */
        val DEFAULT_CONTEXT_SCANS: Array<IntArray> = createDefaultScans(true)

        /** The `dav1d` transform types inferred from chroma intra prediction modes. */
        val UV_INTRA_TRANSFORM_TYPES: Array<TransformType> =
            arrayOf<TransformType>(
                TransformType.DCT_DCT,
                TransformType.ADST_DCT,
                TransformType.DCT_ADST,
                TransformType.DCT_DCT,
                TransformType.ADST_ADST,
                TransformType.ADST_DCT,
                TransformType.DCT_ADST,
                TransformType.DCT_ADST,
                TransformType.ADST_DCT,
                TransformType.ADST_ADST,
                TransformType.ADST_DCT,
                TransformType.DCT_ADST,
                TransformType.ADST_ADST,
                TransformType.DCT_DCT
            )

        /** The `dav1d` luma modes used as transform-type context for filter-intra blocks. */
        val FILTER_INTRA_TRANSFORM_MODES: Array<LumaIntraPredictionMode> =
            arrayOf<LumaIntraPredictionMode>(
                LumaIntraPredictionMode.DC,
                LumaIntraPredictionMode.VERTICAL,
                LumaIntraPredictionMode.HORIZONTAL,
                LumaIntraPredictionMode.HORIZONTAL_DOWN,
                LumaIntraPredictionMode.DC
            )

        /** The `dav1d` low-token context offsets for square, wide, and tall 2D transforms. */
        val LEVEL_CONTEXT_OFFSETS: Array<Array<IntArray>> =
            arrayOf<Array<IntArray>>(
                arrayOf<IntArray>(
                    intArrayOf(0, 1, 6, 6, 21),
                    intArrayOf(1, 6, 6, 21, 21),
                    intArrayOf(6, 6, 21, 21, 21),
                    intArrayOf(6, 21, 21, 21, 21),
                    intArrayOf(21, 21, 21, 21, 21)
                ),
                arrayOf<IntArray>(
                    intArrayOf(0, 16, 6, 6, 21),
                    intArrayOf(16, 16, 6, 21, 21),
                    intArrayOf(16, 16, 21, 21, 21),
                    intArrayOf(16, 16, 21, 21, 21),
                    intArrayOf(16, 16, 21, 21, 21)
                ),
                arrayOf<IntArray>(
                    intArrayOf(0, 11, 11, 11, 11),
                    intArrayOf(11, 11, 11, 11, 11),
                    intArrayOf(6, 6, 21, 21, 21),
                    intArrayOf(6, 21, 21, 21, 21),
                    intArrayOf(21, 21, 21, 21, 21)
                )
            )

        /** The padded `levels` grid size used by the `TX_4X4` token-context helpers. */
        private const val FOUR_BY_FOUR_LEVEL_GRID_SIZE = 8

        /** Returns whether a transform-unit origin lies inside one coefficient region.
         * 
         * @param transformUnit the transform unit to test
         * @param regionStartX4 the inclusive region start on the shared luma-grid X axis
         * @param regionStartY4 the inclusive region start on the shared luma-grid Y axis
         * @param regionEndX4 the exclusive region end on the shared luma-grid X axis
         * @param regionEndY4 the exclusive region end on the shared luma-grid Y axis
         * @return `true` when the unit origin lies inside the region
         */
        fun isInCoefficientRegion(
            transformUnit: TransformUnit,
            regionStartX4: Int,
            regionStartY4: Int,
            regionEndX4: Int,
            regionEndY4: Int
        ): Boolean {
            val position =
                requireNotNullCompat(transformUnit, "transformUnit").position
            return position.x4 >= regionStartX4 && position.x4 < regionEndX4 && position.y4 >= regionStartY4 && position.y4 < regionEndY4
        }

        /** Creates one all-zero transform residual unit.
         * 
         * @param position the transform origin in the current block-position grid
         * @param transformSize the transform size whose residual syntax is all-zero
         * @param visibleWidthPixels the exact visible residual width in pixels
         * @param visibleHeightPixels the exact visible residual height in pixels
         * @return one all-zero transform residual unit
         */
        fun createAllZeroUnit(
            position: BlockPosition,
            transformSize: TransformSize,
            visibleWidthPixels: Int,
            visibleHeightPixels: Int
        ): TransformResidualUnit {
            val nonNullPosition = requireNotNullCompat(position, "position")
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            return TransformResidualUnit.allZero(
                nonNullPosition,
                nonNullTransformSize,
                visibleWidthPixels,
                visibleHeightPixels,
                ALL_ZERO_COEFFICIENT_CONTEXT_BYTE
            )
        }

        /** Returns the exact visible luma width in pixels for one transform unit inside the current
         * block-level transform layout.
         * 
         * @param transformLayout the owning block-level transform layout
         * @param transformUnit the transform unit whose visible width should be measured
         * @return the exact visible luma width in pixels for the supplied transform unit
         */
        fun visibleLumaWidthPixels(
            transformLayout: TransformLayout,
            transformUnit: TransformUnit
        ): Int {
            val nonNullTransformLayout =
                requireNotNullCompat(transformLayout, "transformLayout")
            val nonNullTransformUnit =
                requireNotNullCompat(transformUnit, "transformUnit")
            val relativeX =
                (nonNullTransformUnit.position.x4 - nonNullTransformLayout.position().x4) shl 2
            return min(
                nonNullTransformUnit.size.widthPixels(),
                max(0, nonNullTransformLayout.visibleWidthPixels() - relativeX)
            )
        }

        /** Returns the exact visible luma height in pixels for one transform unit inside the current
         * block-level transform layout.
         * 
         * @param transformLayout the owning block-level transform layout
         * @param transformUnit the transform unit whose visible height should be measured
         * @return the exact visible luma height in pixels for the supplied transform unit
         */
        fun visibleLumaHeightPixels(
            transformLayout: TransformLayout,
            transformUnit: TransformUnit
        ): Int {
            val nonNullTransformLayout =
                requireNotNullCompat(transformLayout, "transformLayout")
            val nonNullTransformUnit =
                requireNotNullCompat(transformUnit, "transformUnit")
            val relativeY =
                (nonNullTransformUnit.position.y4 - nonNullTransformLayout.position().y4) shl 2
            return min(
                nonNullTransformUnit.size.heightPixels(),
                max(0, nonNullTransformLayout.visibleHeightPixels() - relativeY)
            )
        }

        /** Returns the luma-grid origin for one chroma block span.
         * 
         * @param position4 the luma-grid coordinate of the syntax block
         * @param size4 the luma-grid span of the syntax block
         * @param subsamplingShift the chroma subsampling shift for the axis
         * @return the luma-grid origin for the shared chroma footprint
         */
        fun chromaOrigin4(position4: Int, size4: Int, subsamplingShift: Int): Int {
            if (subsamplingShift == 0 || size4 > subsamplingShift) {
                return position4
            }
            val mask = (1 shl subsamplingShift) - 1
            return position4 and mask.inv()
        }

        /** Returns the chroma-plane dimension corresponding to one visible luma span.
         * 
         * @param lumaDimension the visible luma span in pixels
         * @param subsamplingShift the chroma subsampling shift for the axis
         * @return the corresponding chroma-plane dimension
         */
        fun chromaDimension(lumaDimension: Int, subsamplingShift: Int): Int {
            return (lumaDimension + (1 shl subsamplingShift) - 1) shr subsamplingShift
        }

        /** Returns whether this block follows the intra transform-type branch.
         * 
         * @param header the owning leaf block header
         * @return whether this block follows the intra transform-type branch
         */
        fun intraTransformType(header: BlockHeader): Boolean {
            val nonNullHeader = requireNotNullCompat(header, "header")
            return nonNullHeader.intra() && !nonNullHeader.useIntrabc()
        }

        /** Returns the luma intra mode used as transform-type context.
         * 
         * @param header the owning leaf block header
         * @return the luma intra mode used as transform-type context
         */
        fun lumaModeForTransformType(header: BlockHeader): LumaIntraPredictionMode {
            val nonNullHeader = requireNotNullCompat(header, "header")
            val filterIntraMode = nonNullHeader.filterIntraMode()
            if (filterIntraMode != null) {
                return FILTER_INTRA_TRANSFORM_MODES[filterIntraMode.symbolIndex()]
            }
            val yMode = nonNullHeader.yMode()
            return if (yMode != null) yMode else LumaIntraPredictionMode.DC
        }

        /** Returns the transform type inferred from one chroma intra prediction mode.
         * 
         * @param uvMode the chroma intra prediction mode, or `null`
         * @return the transform type inferred from the supplied chroma intra prediction mode
         */
        fun uvIntraTransformType(uvMode: UvIntraPredictionMode?): TransformType {
            if (uvMode == null) {
                return TransformType.DCT_DCT
            }
            return UV_INTRA_TRANSFORM_TYPES[uvMode.symbolIndex()]
        }

        /** Returns the chroma inter transform type inferred from the colocated luma transform type.
         * 
         * @param transformSize the active chroma transform size
         * @param lumaTransformType the colocated luma transform type
         * @return the chroma inter transform type inferred from the supplied luma transform type
         */
        fun chromaInterTransformType(
            transformSize: TransformSize,
            lumaTransformType: TransformType
        ): TransformType {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            val nonNullLumaTransformType =
                requireNotNullCompat(lumaTransformType, "lumaTransformType")
            if (nonNullTransformSize.maxSquareLevel() == TransformSize.TX_32X32.maxSquareLevel()) {
                return if (nonNullLumaTransformType == TransformType.IDTX) TransformType.IDTX else TransformType.DCT_DCT
            }
            if (nonNullTransformSize.minSquareLevel() == TransformSize.TX_16X16.minSquareLevel()
                && isLargeChromaInterTransformExcluded(nonNullLumaTransformType)
            ) {
                return TransformType.DCT_DCT
            }
            return nonNullLumaTransformType
        }

        /** Returns whether a luma transform type is excluded from 16x16 chroma inter transforms.
         * 
         * @param transformType the luma transform type
         * @return whether the supplied transform type is excluded from 16x16 chroma inter transforms
         */
        fun isLargeChromaInterTransformExcluded(transformType: TransformType): Boolean {
            return when (requireNotNullCompat(transformType, "transformType")) {
                TransformType.V_ADST, TransformType.H_ADST, TransformType.V_FLIPADST, TransformType.H_FLIPADST -> true
                else -> false
            }
        }

        /** Returns the transform type of the luma residual unit colocated with one chroma transform unit.
         * 
         * Entries outside the current coefficient region may not have been decoded yet and are
         * ignored while locating the colocated unit.
         * 
         * @param lumaResidualUnits the partially decoded luma residual-unit array
         * @param chromaUnit the chroma transform unit whose colocated luma type should be found
         * @param blockPosition the owning syntax block's luma-grid origin
         * @return the transform type of the colocated decoded luma unit, or `DCT_DCT` if none exists
         */
        fun lumaTransformTypeAt(
            lumaResidualUnits: Array<TransformResidualUnit>,
            chromaUnit: TransformUnit,
            blockPosition: BlockPosition
        ): TransformType {
            val nonNullLumaResidualUnits = requireNotNullCompat(
                lumaResidualUnits,
                "lumaResidualUnits"
            )
            val nonNullChromaUnit = requireNotNullCompat(chromaUnit, "chromaUnit")
            val nonNullBlockPosition =
                requireNotNullCompat(blockPosition, "blockPosition")
            val x4 = max(nonNullChromaUnit.position.x4, nonNullBlockPosition.x4)
            val y4 = max(nonNullChromaUnit.position.y4, nonNullBlockPosition.y4)
            for (lumaResidualUnit in nonNullLumaResidualUnits) {
                val lumaPosition = lumaResidualUnit.position()
                val lumaSize = lumaResidualUnit.size()
                if (x4 >= lumaPosition.x4 && x4 < lumaPosition.x4 + lumaSize.width4() && y4 >= lumaPosition.y4 && y4 < lumaPosition.y4 + lumaSize.height4()) {
                    return lumaResidualUnit.transformType()
                }
            }
            return TransformType.DCT_DCT
        }

        /** Returns the natural coefficient index addressed by one scan index and transform class.
         * 
         * @param transformSize the active transform size
         * @param transformType the active transform type
         * @param scanIndex the zero-based scan index
         * @return the natural coefficient index addressed by the supplied scan index
         */
        fun coefficientIndex(
            transformSize: TransformSize,
            transformType: TransformType,
            scanIndex: Int
        ): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (!nonNullTransformType.oneDimensional()) {
                return DEFAULT_STORAGE_SCANS[nonNullTransformSize.ordinal][scanIndex]
            }
            if (verticalOneDimensional(nonNullTransformType)) {
                return scanIndex
            }
            val row = scanIndex and (nonNullTransformSize.heightPixels() - 1)
            val column = scanIndex shr (nonNullTransformSize.log2Height4() + 2)
            return (row shl (nonNullTransformSize.log2Width4() + 2)) or column
        }

        /** Returns the coefficient-context grid X coordinate for one larger-transform scan index.
         * 
         * @param transformSize the active transform size
         * @param transformType the active transform type
         * @param scanIndex the zero-based scan index
         * @return the coefficient-context grid X coordinate
         */
        fun genericLevelX(
            transformSize: TransformSize,
            transformType: TransformType,
            scanIndex: Int
        ): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (!nonNullTransformType.oneDimensional()) {
                val contextIndex: Int =
                    DEFAULT_CONTEXT_SCANS[nonNullTransformSize.ordinal][scanIndex]
                val contextHeight: Int = clippedCoefficientHeight(nonNullTransformSize)
                return contextIndex shr contextHeight.countTrailingZeroBits()
            }
            val log2Primary = if (verticalOneDimensional(nonNullTransformType)) min(
                nonNullTransformSize.log2Width4(),
                3
            ) else min(nonNullTransformSize.log2Height4(), 3)
            return scanIndex and ((4 shl log2Primary) - 1)
        }

        /** Returns the coefficient-context grid Y coordinate for one larger-transform scan index.
         * 
         * @param transformSize the active transform size
         * @param transformType the active transform type
         * @param scanIndex the zero-based scan index
         * @return the coefficient-context grid Y coordinate
         */
        fun genericLevelY(
            transformSize: TransformSize,
            transformType: TransformType,
            scanIndex: Int
        ): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (!nonNullTransformType.oneDimensional()) {
                val contextIndex: Int =
                    DEFAULT_CONTEXT_SCANS[nonNullTransformSize.ordinal][scanIndex]
                return contextIndex and (clippedCoefficientHeight(nonNullTransformSize) - 1)
            }
            val log2Primary = if (verticalOneDimensional(nonNullTransformType)) min(
                nonNullTransformSize.log2Width4(),
                3
            ) else min(nonNullTransformSize.log2Height4(), 3)
            return scanIndex shr (log2Primary + 2)
        }

        /** Returns the base-token context for one larger-transform coefficient.
         * 
         * @param transformSize the active transform size
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @param x the coefficient-context grid X coordinate
         * @param y the coefficient-context grid Y coordinate
         * @return the base-token context for the supplied coefficient
         */
        fun genericBaseTokenContext(
            transformSize: TransformSize,
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            var magnitude: Int = genericHighMagnitude(nonNullTransformType, levelBytes, x, y)
            val offset: Int
            if (nonNullTransformType.oneDimensional()) {
                magnitude += levelBytes.get(x, y + 3) + levelBytes.get(x, y + 4)
                offset = 26 + (if (y > 1) 10 else y * 5)
            } else {
                magnitude += levelBytes.get(x, y + 2) + levelBytes.get(x + 2, y)
                offset = LEVEL_CONTEXT_OFFSETS[levelContextOffsetIndex(nonNullTransformSize)][min(y, 4)][min(x, 4)]
            }
            return offset + (if (magnitude > 512) 4 else (magnitude + 64) shr 7)
        }

        /** Returns the high-token context for one larger-transform non-DC coefficient.
         * 
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @param x the coefficient-context grid X coordinate
         * @param y the coefficient-context grid Y coordinate
         * @return the high-token context for the supplied coefficient
         */
        fun genericHighTokenContext(
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            val magnitude: Int =
                genericHighMagnitude(nonNullTransformType, levelBytes, x, y) and 0x3F
            val baseContext: Int = genericEndOfBlockHighTokenContext(nonNullTransformType, x, y)
            return baseContext + (if (magnitude > 12) 6 else (magnitude + 1) shr 1)
        }

        /** Returns the high-token context for one larger-transform end-of-block coefficient.
         * 
         * @param transformType the active transform type
         * @param x the coefficient-context grid X coordinate
         * @param y the coefficient-context grid Y coordinate
         * @return the high-token context for the supplied end-of-block coefficient
         */
        fun genericEndOfBlockHighTokenContext(
            transformType: TransformType,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (nonNullTransformType.oneDimensional()) {
                return if (y != 0) 14 else 7
            }
            return if ((x or y) > 1) 14 else 7
        }

        /** Returns the high-token context for one larger-transform DC coefficient.
         * 
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @return the high-token context for the DC coefficient
         */
        fun genericDcHighTokenContext(
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            var magnitude = if (nonNullTransformType.oneDimensional())
                genericHighMagnitude(nonNullTransformType, levelBytes, 0, 0)
            else
                levelBytes.get(0, 1) + levelBytes.get(1, 0) + levelBytes.get(1, 1)
            magnitude = magnitude and 0x3F
            return if (magnitude > 12) 6 else (magnitude + 1) shr 1
        }

        /** Returns the `dav1d` high-magnitude accumulator for one larger-transform coefficient position.
         * 
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @param x the coefficient-context grid X coordinate
         * @param y the coefficient-context grid Y coordinate
         * @return the high-magnitude accumulator for the supplied coefficient position
         */
        fun genericHighMagnitude(
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            var magnitude = levelBytes.get(x, y + 1) + levelBytes.get(x + 1, y)
            if (!nonNullTransformType.oneDimensional()) {
                magnitude += levelBytes.get(x + 1, y + 1)
            } else {
                magnitude += levelBytes.get(x, y + 2)
            }
            return magnitude
        }

        /** Returns the `dav1d` low-context offset table index for one two-dimensional transform size.
         * 
         * @param transformSize the active transform size
         * @return `0` for square, `1` for wide, or `2` for tall
         */
        fun levelContextOffsetIndex(transformSize: TransformSize): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            if (nonNullTransformSize.widthPixels() == nonNullTransformSize.heightPixels()) {
                return 0
            }
            return if (nonNullTransformSize.widthPixels() > nonNullTransformSize.heightPixels()) 1 else 2
        }

        /** Returns the coefficient count modeled by entropy syntax for the supplied transform size.
         * 
         * @param transformSize the active transform size
         * @return the modeled coefficient count
         */
        fun coefficientCount(transformSize: TransformSize): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            return clippedCoefficientWidth(nonNullTransformSize) * clippedCoefficientHeight(
                nonNullTransformSize
            )
        }

        /** Returns the natural `TX_4X4` coefficient index addressed by one scan index and transform class.
         * 
         * @param transformType the active transform type
         * @param scanIndex the zero-based scan index
         * @return the natural `TX_4X4` coefficient index addressed by the supplied scan index
         */
        fun fourByFourCoefficientIndex(transformType: TransformType, scanIndex: Int): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (!nonNullTransformType.oneDimensional()) {
                return FOUR_BY_FOUR_STORAGE_SCAN[scanIndex]
            }
            if (!verticalOneDimensional(nonNullTransformType)) {
                val x = scanIndex and 3
                val y = scanIndex shr 2
                return (x shl 2) or y
            }
            return scanIndex
        }

        /** Returns the `levels` grid X coordinate for one `TX_4X4` scan index.
         * 
         * @param transformType the active transform type
         * @param scanIndex the zero-based scan index
         * @return the `levels` grid X coordinate for the supplied scan index
         */
        fun fourByFourLevelX(transformType: TransformType, scanIndex: Int): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (nonNullTransformType.oneDimensional()) {
                return scanIndex and 3
            }
            return FOUR_BY_FOUR_CONTEXT_SCAN[scanIndex] shr 2
        }

        /** Returns the `levels` grid Y coordinate for one `TX_4X4` scan index.
         * 
         * @param transformType the active transform type
         * @param scanIndex the zero-based scan index
         * @return the `levels` grid Y coordinate for the supplied scan index
         */
        fun fourByFourLevelY(transformType: TransformType, scanIndex: Int): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (nonNullTransformType.oneDimensional()) {
                return scanIndex shr 2
            }
            return FOUR_BY_FOUR_CONTEXT_SCAN[scanIndex] and 3
        }

        /** Returns whether a transform type belongs to the vertical one-dimensional class.
         * 
         * @param transformType the transform type to test
         * @return whether the supplied transform type belongs to the vertical one-dimensional class
         */
        fun verticalOneDimensional(transformType: TransformType): Boolean {
            return when (requireNotNullCompat(transformType, "transformType")) {
                TransformType.V_DCT, TransformType.V_ADST, TransformType.V_FLIPADST -> true
                else -> false
            }
        }

        /** Returns the end-of-block base-token context for one supported end-of-block index.
         * 
         * @param endOfBlockIndex the supported end-of-block index
         * @param transformSize the active transform size
         * @return the end-of-block base-token context for the supplied index
         */
        fun endOfBlockTokenContext(
            endOfBlockIndex: Int,
            transformSize: TransformSize
        ): Int {
            require(endOfBlockIndex >= 0) { "endOfBlockIndex < 0: $endOfBlockIndex" }
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            val tx2dSizeContext = min(nonNullTransformSize.log2Width4(), 3) + min(
                nonNullTransformSize.log2Height4(),
                3
            )
            return (1 + (if (endOfBlockIndex > (2 shl tx2dSizeContext)) 1 else 0)
                    + (if (endOfBlockIndex > (4 shl tx2dSizeContext)) 1 else 0))
        }

        /** Returns the stored level byte written into the local `levels` grid for one coefficient token.
         * 
         * @param token the decoded coefficient token before the optional Golomb extension
         * @return the stored level byte written into the local `levels` grid
         */
        fun coefficientLevelByte(token: Int): Int {
            if (token == 0) {
                return 0
            }
            if (token < 3) {
                return token * 0x41
            }
            return token + (3 shl 6)
        }

        /** Returns the `br_tok` high-token context for the last non-zero `TX_4X4` coefficient.
         * 
         * @param transformType the active transform type
         * @param x the zero-based coefficient column in `[0, 4)`
         * @param y the zero-based coefficient row in `[0, 4)`
         * @return the `br_tok` high-token context for the supplied coefficient
         */
        fun fourByFourEndOfBlockHighTokenContext(
            transformType: TransformType,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            if (nonNullTransformType.oneDimensional()) {
                return if (y != 0) 14 else 7
            }
            return if ((x or y) > 1) 14 else 7
        }

        /** Returns the base-token context for one non-EOB `TX_4X4` coefficient.
         * 
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @param x the zero-based coefficient column in `[0, 4)`
         * @param y the zero-based coefficient row in `[0, 4)`
         * @return the base-token context for the supplied coefficient
         */
        fun fourByFourBaseTokenContext(
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            var magnitude: Int = fourByFourHighMagnitude(nonNullTransformType, levelBytes, x, y)
            val offset: Int
            if (nonNullTransformType.oneDimensional()) {
                magnitude += levelBytes.get(x, y + 3) + levelBytes.get(x, y + 4)
                offset = 26 + (if (y > 1) 10 else y * 5)
            } else {
                magnitude += levelBytes.get(x, y + 2) + levelBytes.get(x + 2, y)
                offset = LEVEL_CONTEXT_OFFSETS[0][min(y, 4)][min(x, 4)]
            }
            return offset + (if (magnitude > 512) 4 else (magnitude + 64) shr 7)
        }

        /** Returns the high-token context for one non-EOB `TX_4X4` coefficient.
         * 
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @param x the zero-based coefficient column in `[0, 4)`
         * @param y the zero-based coefficient row in `[0, 4)`
         * @return the high-token context for the supplied coefficient
         */
        fun fourByFourHighTokenContext(
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            val magnitude: Int =
                fourByFourHighMagnitude(nonNullTransformType, levelBytes, x, y) and 0x3F
            val baseContext = if (nonNullTransformType.oneDimensional())
                (if (y > 0) 14 else 7)
            else
                (if ((x or y) > 1) 14 else 7)
            return baseContext + (if (magnitude > 12) 6 else (magnitude + 1) shr 1)
        }

        /** Returns the DC high-token context for one `TX_4X4` residual block.
         * 
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @return the DC high-token context for the current residual block
         */
        fun fourByFourDcHighTokenContext(
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            val magnitude: Int =
                fourByFourHighMagnitude(nonNullTransformType, levelBytes, 0, 0) and 0x3F
            return if (magnitude > 12) 6 else (magnitude + 1) shr 1
        }

        /** Returns the `dav1d` high-magnitude accumulator for one `TX_4X4` coefficient position.
         * 
         * @param transformType the active transform type
         * @param levelBytes the padded local `levels` grid
         * @param x the zero-based coefficient column in `[0, 4)`
         * @param y the zero-based coefficient row in `[0, 4)`
         * @return the `dav1d` high-magnitude accumulator for the supplied coefficient position
         */
        fun fourByFourHighMagnitude(
            transformType: TransformType,
            levelBytes: CoefficientLevelGrid,
            x: Int,
            y: Int
        ): Int {
            val nonNullTransformType =
                requireNotNullCompat(transformType, "transformType")
            var magnitude = levelBytes.get(x, y + 1) + levelBytes.get(x + 1, y)
            if (!nonNullTransformType.oneDimensional()) {
                magnitude += levelBytes.get(x + 1, y + 1)
            } else {
                magnitude += levelBytes.get(x, y + 2)
            }
            return magnitude
        }

        /** Creates the stored coefficient-context byte for one non-zero transform unit.
         * 
         * @param cumulativeLevel the sum of decoded absolute coefficient levels, clamped later to six bits
         * @param dcSign the decoded DC sign as `-1`, `0`, or `1`; this remains non-zero when a Golomb-extended
         * DC magnitude wraps to zero under the AV1 20-bit coefficient mask
         * @return the stored coefficient-context byte for one non-zero transform unit
         */
        fun createNonZeroCoefficientContextByte(cumulativeLevel: Int, dcSign: Int): Int {
            val magnitude = min(cumulativeLevel, 63)
            if (dcSign < 0) {
                return magnitude
            }
            if (dcSign > 0) {
                return magnitude or 0x80
            }
            return magnitude or 0x40
        }

        /** Applies the AV1 20-bit coefficient-magnitude wrap after Golomb extension.
         * 
         * @param magnitude the unmasked coefficient magnitude
         * @return the low 20 bits of the supplied magnitude
         */
        fun maskCoefficientMagnitude(magnitude: Int): Int {
            return magnitude and COEFFICIENT_MAGNITUDE_MASK
        }

        /** Returns the entropy-coded coefficient width for the supplied transform size.
         * 
         * AV1 codes at most a 32-sample coefficient span on either axis for 64-wide or 64-high
         * transform sizes. The remaining inverse-transform coefficients stay zero.
         * 
         * @param transformSize the active transform size
         * @return the entropy-coded coefficient width
         */
        fun clippedCoefficientWidth(transformSize: TransformSize): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            return 4 shl min(nonNullTransformSize.log2Width4(), 3)
        }

        /** Returns the entropy-coded coefficient height for the supplied transform size.
         * 
         * AV1 codes at most a 32-sample coefficient span on either axis for 64-wide or 64-high
         * transform sizes. The remaining inverse-transform coefficients stay zero.
         * 
         * @param transformSize the active transform size
         * @return the entropy-coded coefficient height
         */
        fun clippedCoefficientHeight(transformSize: TransformSize): Int {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            return 4 shl min(nonNullTransformSize.log2Height4(), 3)
        }

        /** Creates the `dav1d`-compatible two-dimensional scan table for every modeled transform size.
         * 
         * @return the `dav1d`-compatible two-dimensional scan table for every modeled transform size
         */
        fun createDefaultScans(contextLayout: Boolean): Array<IntArray> {
            val transformSizes = TransformSize.entries.toTypedArray()
            val scans: Array<IntArray> = arrayOfNullsCompat<IntArray>(transformSizes.size)
            for (transformSize in transformSizes) {
                scans[transformSize.ordinal] = createDefaultScan(transformSize, contextLayout)
            }
            return scans
        }

        /** Creates the `dav1d`-compatible two-dimensional scan table for one modeled transform size.
         * 
         * `dav1d` stores two-dimensional coefficient scan entries in the transform scratch layout
         * addressed as `coeff[y + x * sh]`. This method can emit either those raw scratch indices for
         * coefficient-token contexts or the same scan converted into this decoder's row-major natural
         * coefficient layout for residual storage.
         * 
         * @param transformSize the modeled transform size whose scan should be created
         * @param contextLayout whether to emit the raw `dav1d` scratch-layout scan indices
         * @return the `dav1d`-compatible two-dimensional scan table for the supplied transform size
         */
        fun createDefaultScan(transformSize: TransformSize, contextLayout: Boolean): IntArray {
            val nonNullTransformSize =
                requireNotNullCompat(transformSize, "transformSize")
            val codedWidth: Int = clippedCoefficientWidth(nonNullTransformSize)
            val codedHeight: Int = clippedCoefficientHeight(nonNullTransformSize)
            val outputWidth = nonNullTransformSize.widthPixels()
            val scan = IntArray(codedWidth * codedHeight)
            var nextIndex = 0
            for (diagonal in 0..<codedWidth + codedHeight - 1) {
                val rowStart = max(0, diagonal - (codedWidth - 1))
                val rowEnd = min(codedHeight - 1, diagonal)
                val descendingRows = if (codedWidth == codedHeight)
                    (diagonal and 1) == 0
                else
                    codedWidth > codedHeight
                if (descendingRows) {
                    for (row in rowEnd downTo rowStart) {
                        val column = diagonal - row
                        scan[nextIndex++] =
                            scanIndex(row, column, outputWidth, codedHeight, contextLayout)
                    }
                } else {
                    for (row in rowStart..rowEnd) {
                        val column = diagonal - row
                        scan[nextIndex++] =
                            scanIndex(row, column, outputWidth, codedHeight, contextLayout)
                    }
                }
            }
            return scan
        }

        /** Converts one generated scan coordinate into this decoder's row-major coefficient index.
         * 
         * @param row the generated diagonal row coordinate
         * @param column the generated diagonal column coordinate
         * @param outputWidth the row-major coefficient row stride
         * @param codedHeight the entropy-coded coefficient height used as the scratch stride
         * @param contextLayout whether to emit the raw `dav1d` scratch-layout scan index
         * @return the selected coefficient index
         */
        fun scanIndex(
            row: Int,
            column: Int,
            outputWidth: Int,
            codedHeight: Int,
            contextLayout: Boolean
        ): Int {
            if (contextLayout) {
                return column * codedHeight + row
            }
            return row * outputWidth + column
        }
    }
}
