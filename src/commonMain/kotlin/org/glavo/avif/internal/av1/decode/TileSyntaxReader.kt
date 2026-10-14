// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.av1.Av1FrameType
import org.glavo.avif.internal.av1.entropy.CdfContext
import org.glavo.avif.internal.av1.entropy.MsacDecoder
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.CompoundInterPredictionMode
import org.glavo.avif.internal.av1.model.FilterIntraMode
import org.glavo.avif.internal.av1.model.FrameHeader.InterpolationFilter
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationType
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode
import org.glavo.avif.internal.av1.model.LumaIntraPredictionMode
import org.glavo.avif.internal.av1.model.MotionMode
import org.glavo.avif.internal.av1.model.MotionVector
import org.glavo.avif.internal.av1.model.PartitionType
import org.glavo.avif.internal.av1.model.SingleInterPredictionMode
import org.glavo.avif.internal.av1.model.TransformSize
import org.glavo.avif.internal.av1.model.TransformType
import org.glavo.avif.internal.av1.model.UvIntraPredictionMode


/** Typed reader for the first block-level AV1 syntax elements inside one tile bitstream.
 * 
 * This reader is intentionally small and currently covers only syntax elements already backed by
 * `CdfContext`: partitioning, skip, skip mode, intra/inter, compound and single-reference
 * selection, inter prediction-mode symbols, inter-intra syntax, `intrabc`, Y/UV intra prediction modes, palette
 * presence and size signaling, CDEF and delta-q/delta-lf side syntax, motion-vector residuals,
 * loop-restoration unit syntax, filter intra, angle deltas, and CFL alpha. */
class TileSyntaxReader constructor(
    tileContext: TileDecodeContext,
    /** Whether malformed syntax outside conformance limits must be rejected. */
    private val strictStdCompliance: Boolean = false
) {
    /** The tile-local decode state that owns the mutable decoder and CDF context. */
    private val tileContext: TileDecodeContext

    /** The tile-local arithmetic decoder. */
    private val msacDecoder: MsacDecoder

    /** The tile-local mutable CDF context. */
    private val cdfContext: CdfContext

    init {
        this.tileContext = requireNotNullCompat(tileContext, "tileContext")
        this.msacDecoder = tileContext.msacDecoder()
        this.cdfContext = tileContext.cdfContext()
    }

    /** Returns the tile-local decode context that owns this syntax reader.
     * 
     * @return the tile-local decode context that owns this syntax reader
     */
    fun tileContext(): TileDecodeContext {
        return tileContext
    }

    /** Decodes one skip flag using the supplied skip-context index.
     * 
     * @param context the zero-based skip-context index in `[0, 3)`
     * @return the decoded skip flag
     */
    fun readSkipFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSkipCdf(context))
    }

    /** Decodes one skip-mode flag using the supplied skip-mode context index.
     * 
     * @param context the zero-based skip-mode context index in `[0, 3)`
     * @return the decoded skip-mode flag
     */
    fun readSkipModeFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSkipModeCdf(context))
    }

    /** Decodes one intra/inter decision for inter and switch frames.
     * 
     * For key and intra-only frames the bitstream does not signal this decision, so this method
     * returns `true` without consuming entropy-coded bits.
     * 
     * @param context the zero-based intra-context index in `[0, 4)`
     * @return `true` when the block is intra-coded, otherwise `false`
     */
    fun readIntraBlockFlag(context: Int): Boolean {
        val frameType = tileContext.frameHeader().frameType()
        if (frameType == Av1FrameType.KEY || frameType == Av1FrameType.INTRA) {
            return true
        }
        return !msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntraCdf(context))
    }

    /** Decodes one compound-reference decision for inter and switch frames.
     * 
     * @param context the zero-based compound-reference context index in `[0, 5)`
     * @return whether the block uses compound references
     */
    fun readCompoundReferenceFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundReferenceCdf(context))
    }

    /** Decodes one compound-reference direction decision.
     * 
     * @param context the zero-based compound-direction context index in `[0, 5)`
     * @return whether the compound block uses bi-directional references
     */
    fun readCompoundDirectionFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundDirectionCdf(context))
    }

    /** Decodes one single-reference selection flag from the supplied table and context.
     * 
     * @param tableIndex the zero-based single-reference table index in `[0, 6)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the decoded single-reference selection flag
     */
    fun readSingleReferenceFlag(tableIndex: Int, context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(
            cdfContext.mutableSingleReferenceCdf(
                tableIndex,
                context
            )
        )
    }

    /** Decodes one compound forward-reference selection flag from the supplied table and context.
     * 
     * @param tableIndex the zero-based compound forward-reference table index in `[0, 3)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the decoded compound forward-reference selection flag
     */
    fun readCompoundForwardReferenceFlag(tableIndex: Int, context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(
            cdfContext.mutableCompoundForwardReferenceCdf(
                tableIndex,
                context
            )
        )
    }

    /** Decodes one compound backward-reference selection flag from the supplied table and context.
     * 
     * @param tableIndex the zero-based compound backward-reference table index in `[0, 2)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the decoded compound backward-reference selection flag
     */
    fun readCompoundBackwardReferenceFlag(tableIndex: Int, context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(
            cdfContext.mutableCompoundBackwardReferenceCdf(
                tableIndex,
                context
            )
        )
    }

    /** Decodes one compound unidirectional-reference selection flag from the supplied table and context.
     * 
     * @param tableIndex the zero-based compound unidirectional-reference table index in `[0, 3)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the decoded compound unidirectional-reference selection flag
     */
    fun readCompoundUnidirectionalReferenceFlag(tableIndex: Int, context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(
            cdfContext.mutableCompoundUnidirectionalReferenceCdf(
                tableIndex,
                context
            )
        )
    }

    /** Decodes one single-reference inter-mode `newmv` flag from the supplied context index.
     * 
     * @param context the zero-based `newmv` context index in `[0, 6)`
     * @return whether the block follows the global/ref-mv branch instead of the new-mv branch
     */
    fun readSingleInterNewMvFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterNewMvCdf(context))
    }

    /** Decodes one single-reference inter-mode `globalmv` flag from the supplied context index.
     * 
     * @param context the zero-based `globalmv` context index in `[0, 2)`
     * @return whether the block uses nearest/ref-mv instead of global motion
     */
    fun readSingleInterGlobalMvFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterGlobalMvCdf(context))
    }

    /** Decodes one single-reference inter-mode `refmv` flag from the supplied context index.
     * 
     * @param context the zero-based `refmv` context index in `[0, 6)`
     * @return whether the block uses near motion vectors instead of the nearest candidate
     */
    fun readSingleInterReferenceMvFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterReferenceMvCdf(context))
    }

    /** Decodes one dynamic-reference-list selection bit from the supplied context index.
     * 
     * @param context the zero-based dynamic-reference-list context index in `[0, 3)`
     * @return the decoded dynamic-reference-list selection bit
     */
    fun readDrlBit(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(context))
    }

    /** Decodes one compound inter prediction mode from the supplied context index.
     * 
     * @param context the zero-based compound inter-mode context index in `[0, 8)`
     * @return the decoded compound inter prediction mode
     */
    fun readCompoundInterMode(context: Int): CompoundInterPredictionMode {
        val symbol =
            msacDecoder.decodeSymbolAdapt(cdfContext.mutableCompoundInterModeCdf(context), 7)
        return CompoundInterPredictionMode.fromSymbolIndex(symbol)
    }

    /** Decodes one block motion mode from the supplied block-size context index.
     * 
     * @param context the zero-based block-size context index in `[0, 22)`
     * @return the decoded block motion mode
     */
    fun readMotionMode(context: Int): MotionMode {
        val symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableMotionModeCdf(context), 2)
        return MotionMode.fromSymbolIndex(symbol)
    }

    /** Decodes one OBMC enable flag from the supplied block-size context index.
     * 
     * @param context the zero-based block-size context index in `[0, 22)`
     * @return whether the current block uses OBMC instead of simple prediction
     */
    fun readUseObmc(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableObmcCdf(context))
    }

    /** Decodes one masked-compound enable flag from the supplied context index.
     * 
     * @param context the zero-based masked-compound context index in `[0, 6)`
     * @return whether the current compound block uses a segment or wedge mask
     */
    fun readUseMaskedCompound(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableMaskCompoundCdf(context))
    }

    /** Decodes one joint-compound averaging flag from the supplied context index.
     * 
     * @param context the zero-based joint-compound context index in `[0, 6)`
     * @return whether the current compound block uses simple averaging instead of weighted averaging
     */
    fun readUseAverageCompound(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableJointCompoundCdf(context))
    }

    /** Decodes one wedge-vs-segment compound flag from the supplied context index.
     * 
     * @param context the zero-based wedge context index in `[0, 9)`
     * @return whether the current masked compound block uses a segment mask instead of a wedge mask
     */
    fun readUseSegmentCompound(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableWedgeCompoundCdf(context))
    }

    /** Decodes one equiprobable compound-mask sign flag.
     * 
     * @return the decoded compound-mask sign flag
     */
    fun readCompoundMaskSign(): Boolean {
        return msacDecoder.decodeBooleanEqui()
    }

    /** Decodes one inter-intra enable flag from the supplied context index.
     * 
     * @param context the zero-based inter-intra context index in `[0, 4)`
     * @return whether the current single-reference inter block uses inter-intra prediction
     */
    fun readUseInterIntra(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableInterIntraCdf(context))
    }

    /** Decodes one inter-intra prediction mode from the supplied context index.
     * 
     * @param context the zero-based inter-intra mode context index in `[0, 4)`
     * @return the decoded inter-intra prediction mode
     */
    fun readInterIntraMode(context: Int): InterIntraPredictionMode {
        val symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableInterIntraModeCdf(context), 3)
        return InterIntraPredictionMode.fromSymbolIndex(symbol)
    }

    /** Decodes one inter-intra wedge enable flag from the supplied context index.
     * 
     * @param context the zero-based wedge context index in `[0, 7)`
     * @return whether the current inter-intra block uses a wedge mask
     */
    fun readUseInterIntraWedge(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableInterIntraWedgeCdf(context))
    }

    /** Decodes one inter-intra wedge index from the supplied context index.
     * 
     * @param context the zero-based wedge context index in `[0, 9)`
     * @return the decoded wedge index in `[0, 16)`
     */
    fun readWedgeIndex(context: Int): Int {
        return msacDecoder.decodeSymbolAdapt(cdfContext.mutableWedgeIndexCdf(context), 15)
    }

    /** Decodes one switchable interpolation-filter symbol for the supplied direction and context.
     * 
     * Direction `0` decodes the vertical filter symbol and direction `1` decodes the horizontal
     * filter symbol. AV1 maps the three switchable symbols to its regular, smooth, and sharp
     * 8-tap kernels.
     * 
     * @param direction the zero-based interpolation-filter direction index in `[0, 2)`
     * @param context the zero-based switchable interpolation-filter context index in `[0, 8)`
     * @return the decoded switchable interpolation filter
     */
    fun readInterpolationFilter(direction: Int, context: Int): InterpolationFilter {
        val symbol = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableInterpolationFilterCdf(
                direction,
                context
            ), 2
        )
        return when (symbol) {
            0 -> InterpolationFilter.EIGHT_TAP_REGULAR
            1 -> InterpolationFilter.EIGHT_TAP_SMOOTH
            2 -> InterpolationFilter.EIGHT_TAP_SHARP
            else -> throw IllegalStateException("Unsupported switchable interpolation-filter symbol: $symbol")
        }
    }

    /** Decodes one luma transform-depth symbol for the supplied maximum transform size.
     * 
     * AV1 only signals transform depth for switchable transform mode when the maximum transform
     * size exceeds 4x4. The returned depth is therefore zero for `TX_4X4`.
     * 
     * @param maxTransformSize the largest luma transform size allowed for the current block
     * @param context the zero-based transform-size context index in `[0, 3)`
     * @return the decoded luma transform depth
     */
    fun readTransformDepth(maxTransformSize: TransformSize, context: Int): Int {
        val nonNullMaxTransformSize =
            requireNotNullCompat(maxTransformSize, "maxTransformSize")
        if (nonNullMaxTransformSize.maxSquareLevel() <= 0) {
            return 0
        }
        return msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableTransformSizeCdf(
                nonNullMaxTransformSize.maxSquareLevel() - 1,
                context
            ),
            min(nonNullMaxTransformSize.maxSquareLevel(), 2)
        )
    }

    /** Decodes one inter var-tx split flag from the supplied `dav1d` table and context.
     * 
     * @param tableIndex the zero-based `dav1d` transform-partition table index in `[0, 7)`
     * @param context the zero-based transform-partition context index in `[0, 3)`
     * @return whether the current transform region splits to smaller transforms
     */
    fun readTransformSplitFlag(tableIndex: Int, context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(
            cdfContext.mutableTransformPartitionCdf(
                tableIndex,
                context
            )
        )
    }

    /** Decodes one explicitly signaled luma intra transform type.
     * 
     * @param transformSize the active luma transform size
     * @param yMode the luma intra prediction mode used as transform-type context
     * @param reducedTransformSet whether the active frame uses the reduced transform-type set
     * @return the decoded luma intra transform type
     */
    fun readIntraTransformType(
        transformSize: TransformSize,
        yMode: LumaIntraPredictionMode,
        reducedTransformSet: Boolean
    ): TransformType {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val nonNullYMode = requireNotNullCompat(yMode, "yMode")
        val minSquareLevel = nonNullTransformSize.minSquareLevel()
        val yModeIndex = nonNullYMode.symbolIndex()
        if (reducedTransformSet || minSquareLevel == TransformSize.TX_16X16.minSquareLevel()) {
            val symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableIntraTransformTypeSet2Cdf(minSquareLevel, yModeIndex),
                4
            )
            return TRANSFORM_TYPES_PER_SET[symbol]
        }

        val symbol = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableIntraTransformTypeSet1Cdf(minSquareLevel, yModeIndex),
            6
        )
        return TRANSFORM_TYPES_PER_SET[symbol + 5]
    }

    /** Decodes one explicitly signaled luma inter transform type.
     * 
     * @param transformSize the active luma transform size
     * @param reducedTransformSet whether the active frame uses the reduced transform-type set
     * @return the decoded luma inter transform type
     */
    fun readInterTransformType(
        transformSize: TransformSize,
        reducedTransformSet: Boolean
    ): TransformType {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val minSquareLevel = nonNullTransformSize.minSquareLevel()
        if (reducedTransformSet || nonNullTransformSize.maxSquareLevel() == TransformSize.TX_32X32.maxSquareLevel()) {
            val useDctDct = msacDecoder.decodeBooleanAdapt(
                cdfContext.mutableInterTransformTypeSet3Cdf(minSquareLevel)
            )
            return if (useDctDct) TransformType.DCT_DCT else TransformType.IDTX
        }
        if (minSquareLevel == TransformSize.TX_16X16.minSquareLevel()) {
            val symbol =
                msacDecoder.decodeSymbolAdapt(cdfContext.mutableInterTransformTypeSet2Cdf(), 11)
            return TRANSFORM_TYPES_PER_SET[symbol + 12]
        }

        val symbol = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableInterTransformTypeSet1Cdf(minSquareLevel),
            15
        )
        return TRANSFORM_TYPES_PER_SET[symbol + 24]
    }

    /** Decodes one luma coefficient-skip flag for the supplied transform size and context.
     * 
     * @param transformSize the luma transform size that selects the AV1 coefficient-context group
     * @param context the zero-based coefficient-skip context index in `[0, 13)`
     * @return whether the current transform block is signaled as all-zero
     */
    fun readCoefficientSkipFlag(transformSize: TransformSize, context: Int): Boolean {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        return msacDecoder.decodeBooleanAdapt(
            cdfContext.mutableCoefficientSkipCdf(
                nonNullTransformSize.coefficientContextIndex(),
                context
            )
        )
    }

    /** Decodes the end-of-block prefix for one transform block.
     * 
     * The returned value follows the AV1 `eob` scan-index convention where `0` means a DC-only
     * block, positive values indicate that at least one AC coefficient is present, and `-1` is
     * never returned.
     * 
     * @param transformSize the active transform size
     * @param chroma whether the syntax belongs to a chroma plane
     * @param oneDimensional whether the active transform type belongs to a 1D transform class
     * @return the decoded end-of-block scan index
     */
    fun readEndOfBlockIndex(
        transformSize: TransformSize,
        chroma: Boolean,
        oneDimensional: Boolean
    ): Int {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val tx2dSizeContext =
            min(nonNullTransformSize.log2Width4(), 3) + min(nonNullTransformSize.log2Height4(), 3)
        var eob = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableEndOfBlockPrefixCdf(tx2dSizeContext, chroma, oneDimensional),
            4 + tx2dSizeContext
        )
        if (eob > 1) {
            val eobBin = eob - 2
            val eobHighBit = msacDecoder.decodeBooleanAdapt(
                cdfContext.mutableEndOfBlockHighBitCdf(
                    nonNullTransformSize.coefficientContextIndex(),
                    chroma,
                    eobBin
                )
            )
            eob = ((if (eobHighBit) 1 else 0) or 2) shl eobBin
            eob = eob or msacDecoder.decodeBools(eobBin)
        }
        return eob
    }

    /** Decodes one end-of-block/DC base token for the supplied transform context and table context.
     * 
     * AV1 stores this symbol as `token_minus_1`, so the returned token is in `[1, 3]`.
     * 
     * @param transformSize the active transform size
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based end-of-block base-token context in `[0, 4)`
     * @return the decoded end-of-block/DC base token in `[1, 3]`
     */
    fun readEndOfBlockBaseToken(transformSize: TransformSize, chroma: Boolean, context: Int): Int {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        return 1 + msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableEndOfBlockBaseTokenCdf(
                nonNullTransformSize.coefficientContextIndex(),
                chroma,
                context
            ),
            2
        )
    }

    /** Decodes one coefficient base token for the supplied transform context and table context.
     * 
     * The returned token is in `[0, 3]`, where zero means the coefficient level is zero and three
     * enters the high-token extension path.
     * 
     * @param transformSize the active transform size
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based coefficient base-token context
     * @return the decoded coefficient base token in `[0, 3]`
     */
    fun readBaseToken(transformSize: TransformSize, chroma: Boolean, context: Int): Int {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        return msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableBaseTokenCdf(
                nonNullTransformSize.coefficientContextIndex(),
                chroma,
                context
            ),
            3
        )
    }

    /** Decodes one coefficient high token from the supplied `br_tok` context.
     * 
     * @param transformSize the active transform size
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based `br_tok` context index
     * @return the decoded high token in `[3, 15]`
     */
    fun readHighToken(transformSize: TransformSize, chroma: Boolean, context: Int): Int {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        return msacDecoder.decodeHighToken(
            cdfContext.mutableHighTokenCdf(
                min(nonNullTransformSize.coefficientContextIndex(), 3),
                chroma,
                context
            )
        )
    }

    /** Decodes one DC-sign flag for the supplied plane and context.
     * 
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based DC-sign context in `[0, 3)`
     * @return whether the decoded DC coefficient is negative
     */
    fun readDcSignFlag(chroma: Boolean, context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableDcSignCdf(chroma, context))
    }

    /** Decodes one coefficient sign flag from the equiprobable residual-sign path.
     * 
     * @return whether the decoded coefficient is negative
     */
    fun readCoefficientSignFlag(): Boolean {
        return msacDecoder.decodeBooleanEqui()
    }

    /** Decodes one AV1 coefficient Golomb extension value.
     * 
     * @return the decoded coefficient Golomb extension value
     */
    fun readCoefficientGolomb(): Int {
        var length = 0
        var value = 1
        while (true) {
            val lengthBit = msacDecoder.decodeBooleanEqui()
            if (strictStdCompliance && length == 19 && !lengthBit) {
                val cause = IllegalArgumentException(
                    "Coefficient Golomb length exceeds the AV1 limit"
                )
                throw InvalidFrameSyntaxException(cause.message, cause)
            }
            if (lengthBit || length >= 32) {
                break
            }
            length++
        }
        while (length-- > 0) {
            value = (value shl 1) or (if (msacDecoder.decodeBooleanEqui()) 1 else 0)
        }
        return value - 1
    }

    /** Decodes an unsigned literal made of equiprobable binary values.
     * 
     * @param bitCount the number of equiprobable bits to decode
     * @return the decoded unsigned literal
     */
    fun readUnsignedBits(bitCount: Int): Int {
        return msacDecoder.decodeBools(bitCount)
    }

    /** Decodes one signed and resolution-scaled delta-q value.
     * 
     * @param resolutionLog2 the delta-q resolution log2
     * @return the decoded signed delta-q value after resolution scaling
     */
    fun readDeltaQValue(resolutionLog2: Int): Int {
        return readSignedDeltaValue(cdfContext.mutableDeltaQCdf(), resolutionLog2)
    }

    /** Decodes one signed and resolution-scaled delta-lf value for the supplied context.
     * 
     * @param context the zero-based delta-lf context index in `[0, 5)`
     * @param resolutionLog2 the delta-lf resolution log2
     * @return the decoded signed delta-lf value after resolution scaling
     */
    fun readDeltaLfValue(context: Int, resolutionLog2: Int): Int {
        return readSignedDeltaValue(cdfContext.mutableDeltaLfCdf(context), resolutionLog2)
    }

    /** Decodes one motion-vector residual around the supplied predictor.
     * 
     * The active frame header supplies the motion-vector precision mode. This syntax is available
     * for inter and switch frames. The returned vector is the fully decoded motion vector in
     * eighth-pel units.
     * 
     * @param referenceMotionVector the predictor that the residual is added to
     * @return the fully decoded motion vector in eighth-pel units
     */
    fun readMotionVectorResidual(referenceMotionVector: MotionVector): MotionVector {
        return readMotionVectorResidual(referenceMotionVector, false)
    }

    /** Decodes one intrabc displacement-vector residual around the supplied predictor.
     * 
     * Intrabc displacement vectors use their own adaptive CDF state and integer-pel precision.
     * This syntax is available only when the active frame enables `allow_intrabc`.
     * 
     * @param referenceMotionVector the predictor that the residual is added to
     * @return the fully decoded displacement vector in eighth-pel units
     */
    fun readIntrabcMotionVectorResidual(referenceMotionVector: MotionVector): MotionVector {
        return readMotionVectorResidual(referenceMotionVector, true)
    }

    /** Decodes one motion- or displacement-vector residual from the selected adaptive CDF set.
     * 
     * @param referenceMotionVector the predictor that the residual is added to
     * @param intrabc whether to use the independent intrabc displacement-vector CDFs
     * @return the fully decoded vector in eighth-pel units
     */
    private fun readMotionVectorResidual(
        referenceMotionVector: MotionVector,
        intrabc: Boolean
    ): MotionVector {
        val nonNullReferenceMotionVector =
            requireNotNullCompat(referenceMotionVector, "referenceMotionVector")
        val frameType = tileContext.frameHeader().frameType()
        check(
            !(intrabc && !tileContext.frameHeader().allowIntrabc())
        ) { "Intrabc displacement-vector residuals require allow_intrabc" }
        check(!(!intrabc && frameType != Av1FrameType.INTER && frameType != Av1FrameType.SWITCH)) { "Motion-vector residuals are only available in inter and switch frames" }

        val motionVectorPrecision = if (intrabc)
            -1
        else
            ((if (tileContext.frameHeader().allowHighPrecisionMotionVectors()) 1 else 0)
                    - (if (tileContext.frameHeader().forceIntegerMotionVectors()) 1 else 0))
        val jointCdf = if (intrabc)
            cdfContext.mutableIntrabcMotionVectorJointCdf()
        else
            cdfContext.mutableMotionVectorJointCdf()
        val motionVectorJoint = msacDecoder.decodeSymbolAdapt(jointCdf, 3)
        if (motionVectorJoint == MOTION_VECTOR_JOINT_NONE) {
            return nonNullReferenceMotionVector
        }

        var rowEighthPel = nonNullReferenceMotionVector.rowEighthPel
        var columnEighthPel = nonNullReferenceMotionVector.columnEighthPel
        if ((motionVectorJoint and MOTION_VECTOR_JOINT_VERTICAL) != 0) {
            rowEighthPel += readMotionVectorComponentDiff(0, motionVectorPrecision, intrabc)
        }
        if ((motionVectorJoint and MOTION_VECTOR_JOINT_HORIZONTAL) != 0) {
            columnEighthPel += readMotionVectorComponentDiff(1, motionVectorPrecision, intrabc)
        }
        return MotionVector(rowEighthPel, columnEighthPel)
    }

    /** Decodes one single-reference inter prediction mode from the supplied contexts and already-forced segment flags.
     * 
     * This method covers only the mode symbol itself. Motion-vector candidate lookup and residual
     * decoding remain the responsibility of higher decode layers.
     * 
     * @param newMvContext the zero-based `newmv` context index in `[0, 6)`
     * @param globalMvContext the zero-based `globalmv` context index in `[0, 2)`
     * @param referenceMvContext the zero-based `refmv` context index in `[0, 6)`
     * @param segmentGlobalMotion whether segment features force `GLOBALMV`
     * @param segmentSkip whether segment features force the global/nearest-ref branch
     * @return the decoded single-reference inter prediction mode
     */
    fun readSingleInterMode(
        newMvContext: Int,
        globalMvContext: Int,
        referenceMvContext: Int,
        segmentGlobalMotion: Boolean,
        segmentSkip: Boolean
    ): SingleInterPredictionMode {
        if (segmentGlobalMotion || segmentSkip || readSingleInterNewMvFlag(newMvContext)) {
            if (segmentGlobalMotion || segmentSkip || !readSingleInterGlobalMvFlag(globalMvContext)) {
                return SingleInterPredictionMode.GLOBALMV
            }
            return if (readSingleInterReferenceMvFlag(referenceMvContext))
                SingleInterPredictionMode.NEARMV
            else
                SingleInterPredictionMode.NEARESTMV
        }
        return SingleInterPredictionMode.NEWMV
    }

    /** Decodes one `use_intrabc` flag when the active frame allows it.
     * 
     * When the frame does not allow `intrabc`, this method returns `false` without consuming
     * entropy-coded bits.
     * 
     * @return the decoded `use_intrabc` flag
     */
    fun readUseIntrabcFlag(): Boolean {
        if (!tileContext.frameHeader().allowIntrabc()) {
            return false
        }
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntrabcCdf())
    }

    /** Decodes one concrete loop-restoration unit type for the supplied frame-level type.
     * 
     * @param frameType the frame-level restoration type for the active plane
     * @return one concrete loop-restoration unit type
     */
    fun readRestorationUnitType(frameType: RestorationType): RestorationType {
        val checkedFrameType = requireNotNullCompat(frameType, "frameType")
        return when (checkedFrameType) {
            RestorationType.NONE -> RestorationType.NONE
            RestorationType.WIENER -> if (msacDecoder.decodeBooleanAdapt(cdfContext.mutableRestorationWienerCdf()))
                RestorationType.WIENER
            else
                RestorationType.NONE

            RestorationType.SELF_GUIDED -> if (msacDecoder.decodeBooleanAdapt(cdfContext.mutableRestorationSelfGuidedCdf()))
                RestorationType.SELF_GUIDED
            else
                RestorationType.NONE

            RestorationType.SWITCHABLE -> when (msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableRestorationSwitchableCdf(),
                2
            )) {
                0 -> RestorationType.NONE
                1 -> RestorationType.WIENER
                2 -> RestorationType.SELF_GUIDED
                else -> throw IllegalStateException("Unsupported switchable restoration symbol")
            }
        }
    }

    /** Decodes one Wiener coefficient using AV1 restoration subexponential coding.
     * 
     * @param coefficientIndex the Wiener coefficient index in `[0, 3)`
     * @param reference the previous reference coefficient for this plane, pass, and coefficient
     * @return one decoded Wiener coefficient
     */
    fun readWienerCoefficient(coefficientIndex: Int, reference: Int): Int {
        val index = checkIndexCompat(coefficientIndex, WIENER_TAPS_MIN.size)
        return readSignedSubexpWithReference(
            WIENER_TAPS_MIN[index],
            WIENER_TAPS_MAX[index],
            WIENER_TAPS_K[index],
            reference
        )
    }

    /** Decodes one self-guided projection coefficient using AV1 restoration subexponential coding.
     * 
     * @param coefficientIndex the projection coefficient index in `[0, 2)`
     * @param reference the previous reference coefficient for this plane and coefficient
     * @return one decoded self-guided projection coefficient
     */
    fun readSelfGuidedProjectionCoefficient(coefficientIndex: Int, reference: Int): Int {
        val index = checkIndexCompat(coefficientIndex, SELF_GUIDED_PROJECTION_MIN.size)
        return readSignedSubexpWithReference(
            SELF_GUIDED_PROJECTION_MIN[index],
            SELF_GUIDED_PROJECTION_MAX[index],
            SELF_GUIDED_PROJECTION_SUBEXP_K,
            reference
        )
    }

    /** Returns the derived second self-guided projection coefficient when its filter radius is disabled.
     * 
     * @param firstReference the current first projection coefficient reference
     * @return the derived second projection coefficient
     */
    fun derivedSelfGuidedProjectionCoefficient1(firstReference: Int): Int {
        return clip(
            (1 shl SELF_GUIDED_PROJECTION_BITS) - firstReference,
            SELF_GUIDED_PROJECTION_MIN[1],
            SELF_GUIDED_PROJECTION_MAX[1]
        )
    }

    /** Decodes one inter-frame Y intra prediction mode using the supplied size-context index.
     * 
     * @param sizeContext the zero-based Y-mode size context in `[0, 4)`
     * @return the decoded luma intra prediction mode
     */
    fun readYMode(sizeContext: Int): LumaIntraPredictionMode {
        val symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableYModeCdf(sizeContext), 12)
        return LumaIntraPredictionMode.fromSymbolIndex(symbol)
    }

    /** Decodes one key-frame Y intra prediction mode using the supplied above and left luma modes.
     * 
     * @param aboveMode the already-decoded luma mode above the current block
     * @param leftMode the already-decoded luma mode left of the current block
     * @return the decoded luma intra prediction mode
     */
    fun readKeyFrameYMode(
        aboveMode: LumaIntraPredictionMode,
        leftMode: LumaIntraPredictionMode
    ): LumaIntraPredictionMode {
        val nonNullAboveMode =
            requireNotNullCompat(aboveMode, "aboveMode")
        val nonNullLeftMode = requireNotNullCompat(leftMode, "leftMode")
        val symbol = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableKeyFrameYModeCdf(
                nonNullAboveMode.contextIndex(),
                nonNullLeftMode.contextIndex()
            ),
            12
        )
        return LumaIntraPredictionMode.fromSymbolIndex(symbol)
    }

    /** Decodes one UV intra prediction mode using the supplied Y mode and CFL availability.
     * 
     * @param yMode the already-decoded luma intra prediction mode for the current block
     * @param cflAllowed whether CFL is allowed for this block
     * @return the decoded chroma intra prediction mode
     */
    fun readUvMode(yMode: LumaIntraPredictionMode, cflAllowed: Boolean): UvIntraPredictionMode {
        val nonNullYMode = requireNotNullCompat(yMode, "yMode")
        val symbol = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableUvModeCdf(cflAllowed, nonNullYMode.symbolIndex()),
            if (cflAllowed) 13 else 12
        )
        return UvIntraPredictionMode.fromSymbolIndex(symbol)
    }

    /** Decodes one `use_filter_intra` flag for the supplied block size.
     * 
     * @param size the current block size
     * @return whether filter intra is enabled for the current block
     */
    fun readUseFilterIntra(size: BlockSize): Boolean {
        val nonNullSize = requireNotNullCompat(size, "size")
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableUseFilterIntraCdf(nonNullSize.cdfIndex()))
    }

    /** Decodes one temporal segmentation-prediction flag.
     * 
     * @param context the zero-based segmentation-prediction context index in `[0, 3)`
     * @return whether the block uses temporal segmentation prediction
     */
    fun readSegmentPredictionFlag(context: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSegmentPredictionCdf(context))
    }

    /** Decodes one segment-id diff symbol for the supplied segment context.
     * 
     * @param context the zero-based segment-id context index in `[0, 3)`
     * @return the decoded segment-id diff symbol
     */
    fun readSegmentId(context: Int): Int {
        return msacDecoder.decodeSymbolAdapt(cdfContext.mutableSegmentIdCdf(context), 7)
    }

    /** Decodes one luma palette-use flag for the supplied size and above/left palette contexts.
     * 
     * @param sizeContext the zero-based palette size context in `[0, 7)`
     * @param paletteContext the zero-based above/left palette context in `[0, 3)`
     * @return whether the current block uses a luma palette
     */
    fun readUseLumaPalette(sizeContext: Int, paletteContext: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(
            cdfContext.mutableLumaPaletteCdf(
                sizeContext,
                paletteContext
            )
        )
    }

    /** Decodes one palette size for the supplied plane and size context.
     * 
     * AV1 codes palette sizes as `palette_size_minus_2`, so this method returns the decoded
     * palette size in `[2, 8]`.
     * 
     * @param plane the palette plane index, where `0` is luma and `1` is chroma
     * @param sizeContext the zero-based palette size context in `[0, 7)`
     * @return the decoded palette size in `[2, 8]`
     */
    fun readPaletteSize(plane: Int, sizeContext: Int): Int {
        return msacDecoder.decodeSymbolAdapt(
            cdfContext.mutablePaletteSizeCdf(plane, sizeContext),
            6
        ) + 2
    }

    /** Decodes one chroma palette-use flag for the supplied luma-palette context.
     * 
     * @param paletteContext the zero-based chroma palette context in `[0, 2)`
     * @return whether the current block uses a chroma palette
     */
    fun readUseChromaPalette(paletteContext: Int): Boolean {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableChromaPaletteCdf(paletteContext))
    }

    /** Decodes the first palette index in one color-map using the AV1 uniform coding rule.
     * 
     * @param paletteSize the decoded palette size in `[2, 8]`
     * @return the decoded first palette index in `[0, paletteSize)`
     */
    fun readPaletteInitialIndex(paletteSize: Int): Int {
        return msacDecoder.decodeUniform(paletteSize)
    }

    /** Decodes one palette color-map symbol for the supplied plane, palette size, and context.
     * 
     * The returned symbol indexes the local palette-order permutation for the current pixel.
     * 
     * @param plane the palette plane index, where `0` is luma and `1` is chroma
     * @param paletteSize the decoded palette size in `[2, 8]`
     * @param context the zero-based color-map context in `[0, 5)`
     * @return the decoded palette color-map symbol in `[0, paletteSize)`
     */
    fun readPaletteColorMapSymbol(plane: Int, paletteSize: Int, context: Int): Int {
        return msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableColorMapCdf(plane, paletteSize - 2, context),
            paletteSize - 1
        )
    }

    /** Decodes one filter-intra mode after `use_filter_intra` signaled `true`.
     * 
     * @return the decoded filter-intra mode
     */
    fun readFilterIntraMode(): FilterIntraMode {
        val symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableFilterIntraCdf(), 4)
        return FilterIntraMode.fromSymbolIndex(symbol)
    }

    /** Decodes one partition symbol using the supplied partition block level and context index.
     * 
     * @param blockLevel the partition block level that selects the CDF shape
     * @param context the zero-based partition context index in `[0, 4)`
     * @return the decoded partition type
     */
    fun readPartition(blockLevel: PartitionBlockLevel, context: Int): PartitionType {
        val nonNullBlockLevel =
            requireNotNullCompat(blockLevel, "blockLevel")
        val cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context)
        val symbol = msacDecoder.decodeSymbolAdapt(
            cdf,
            nonNullBlockLevel.symbolLimit()
        )
        return PartitionType.fromSymbolIndex(symbol)
    }

    /** Decodes one directional angle delta for the supplied luma intra prediction mode.
     * 
     * @param mode the already-decoded directional luma intra prediction mode
     * @return the decoded signed angle delta in `[-3, 3]`
     */
    fun readYAngleDelta(mode: LumaIntraPredictionMode): Int {
        val nonNullMode = requireNotNullCompat(mode, "mode")
        val symbol = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableAngleDeltaCdf(nonNullMode.angleDeltaContextIndex()),
            6
        )
        return symbol - 3
    }

    /** Decodes one directional angle delta for the supplied chroma intra prediction mode.
     * 
     * @param mode the already-decoded directional chroma intra prediction mode
     * @return the decoded signed angle delta in `[-3, 3]`
     */
    fun readUvAngleDelta(mode: UvIntraPredictionMode): Int {
        val nonNullMode = requireNotNullCompat(mode, "mode")
        val symbol = msacDecoder.decodeSymbolAdapt(
            cdfContext.mutableAngleDeltaCdf(nonNullMode.angleDeltaContextIndex()),
            6
        )
        return symbol - 3
    }

    /** Decodes the signed CFL alpha pair for one block.
     * 
     * @return the decoded signed CFL alpha pair for one block
     */
    fun readCflAlpha(): CflAlpha {
        val signSymbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCflSignCdf(), 7) + 1
        val signU = signSymbol / 3
        val signV = signSymbol - signU * 3
        val alphaU = if (signU == 0) 0 else decodeSignedCflAlpha(
            (if (signU == 2) 3 else 0) + signV,
            signU == 2
        )
        val alphaV = if (signV == 0) 0 else decodeSignedCflAlpha(
            (if (signV == 2) 3 else 0) + signU,
            signV == 2
        )
        return CflAlpha(alphaU, alphaV)
    }

    /** Decodes one signed CFL alpha value from the supplied sign context.
     * 
     * @param context the zero-based CFL-alpha sign context
     * @param positive whether the decoded alpha should be positive
     * @return the decoded signed CFL alpha value
     */
    private fun decodeSignedCflAlpha(context: Int, positive: Boolean): Int {
        val alpha = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCflAlphaCdf(context), 15) + 1
        return if (positive) alpha else -alpha
    }

    /** Decodes one signed delta value that uses the AV1 delta-q/delta-lf coding rule.
     * 
     * @param cdf the active AV1 inverse CDF table for the delta-magnitude prefix
     * @param resolutionLog2 the resolution log2 applied to non-zero decoded magnitudes
     * @return the decoded signed and resolution-scaled delta value
     */
    private fun readSignedDeltaValue(cdf: IntArray, resolutionLog2: Int): Int {
        var magnitude = msacDecoder.decodeSymbolAdapt(cdf, 3)
        if (magnitude == 3) {
            val bitCount = 1 + msacDecoder.decodeBools(3)
            magnitude = msacDecoder.decodeBools(bitCount) + 1 + (1 shl bitCount)
        }
        if (magnitude == 0) {
            return 0
        }
        if (msacDecoder.decodeBooleanEqui()) {
            magnitude = -magnitude
        }
        return magnitude shl resolutionLog2
    }

    /** Decodes one signed subexponential value recentered around a reference.
     * 
     * @param minimum the inclusive lower bound
     * @param maximum the inclusive upper bound
     * @param k the subexponential group width
     * @param reference the recentering reference value
     * @return the decoded signed subexponential value
     */
    private fun readSignedSubexpWithReference(
        minimum: Int,
        maximum: Int,
        k: Int,
        reference: Int
    ): Int {
        return minimum + msacDecoder.decodeSubexp(reference - minimum, maximum + 1 - minimum, k)
    }

    /** Decodes one signed motion-vector component residual.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @param motionVectorPrecision the active motion-vector precision mode: `-1` forces integer,
     * `0` disables high precision, and `1` allows high precision
     * @param intrabc whether to use the independent intrabc displacement-vector CDFs
     * @return the signed motion-vector component residual in eighth-pel units
     */
    private fun readMotionVectorComponentDiff(
        component: Int,
        motionVectorPrecision: Int,
        intrabc: Boolean
    ): Int {
        val signCdf = if (intrabc)
            cdfContext.mutableIntrabcMotionVectorSignCdf(component)
        else
            cdfContext.mutableMotionVectorSignCdf(component)
        val classCdf = if (intrabc)
            cdfContext.mutableIntrabcMotionVectorClassCdf(component)
        else
            cdfContext.mutableMotionVectorClassCdf(component)
        val negative = msacDecoder.decodeBooleanAdapt(signCdf)
        val motionVectorClass = msacDecoder.decodeSymbolAdapt(classCdf, 10)
        var integerMagnitude: Int
        var fractionalPart = 3
        var highPrecisionBit = 1

        if (motionVectorClass == 0) {
            val class0Cdf = if (intrabc)
                cdfContext.mutableIntrabcMotionVectorClass0Cdf(component)
            else
                cdfContext.mutableMotionVectorClass0Cdf(component)
            integerMagnitude = if (msacDecoder.decodeBooleanAdapt(class0Cdf)) 1 else 0
            if (motionVectorPrecision >= 0) {
                val class0FpCdf = if (intrabc)
                    cdfContext.mutableIntrabcMotionVectorClass0FpCdf(component, integerMagnitude)
                else
                    cdfContext.mutableMotionVectorClass0FpCdf(component, integerMagnitude)
                fractionalPart = msacDecoder.decodeSymbolAdapt(
                    class0FpCdf,
                    3
                )
                if (motionVectorPrecision > 0) {
                    val class0HpCdf = if (intrabc)
                        cdfContext.mutableIntrabcMotionVectorClass0HpCdf(component)
                    else
                        cdfContext.mutableMotionVectorClass0HpCdf(component)
                    highPrecisionBit = if (msacDecoder.decodeBooleanAdapt(class0HpCdf)) 1 else 0
                }
            }
        } else {
            integerMagnitude = 1 shl motionVectorClass
            for (bitIndex in 0..<motionVectorClass) {
                val classNCdf = if (intrabc)
                    cdfContext.mutableIntrabcMotionVectorClassNCdf(component, bitIndex)
                else
                    cdfContext.mutableMotionVectorClassNCdf(component, bitIndex)
                val bit = msacDecoder.decodeBooleanAdapt(classNCdf)
                if (bit) {
                    integerMagnitude = integerMagnitude or (1 shl bitIndex)
                }
            }
            if (motionVectorPrecision >= 0) {
                val classNFpCdf = if (intrabc)
                    cdfContext.mutableIntrabcMotionVectorClassNFpCdf(component)
                else
                    cdfContext.mutableMotionVectorClassNFpCdf(component)
                fractionalPart = msacDecoder.decodeSymbolAdapt(classNFpCdf, 3)
                if (motionVectorPrecision > 0) {
                    val classNHpCdf = if (intrabc)
                        cdfContext.mutableIntrabcMotionVectorClassNHpCdf(component)
                    else
                        cdfContext.mutableMotionVectorClassNHpCdf(component)
                    highPrecisionBit = if (msacDecoder.decodeBooleanAdapt(classNHpCdf)) 1 else 0
                }
            }
        }

        val diff = ((integerMagnitude shl 3) or (fractionalPart shl 1) or highPrecisionBit) + 1
        return if (negative) -diff else diff
    }

    /** Decodes a partition decision when only a horizontal split or no split is allowed.
     * 
     * @param blockLevel the partition block level that selects the CDF shape
     * @param context the zero-based partition context index in `[0, 4)`
     * @return `true` when the block splits into two square children, otherwise `false`
     */
    fun readHorizontalSplitOnly(blockLevel: PartitionBlockLevel, context: Int): Boolean {
        val nonNullBlockLevel =
            requireNotNullCompat(blockLevel, "blockLevel")
        val cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context)
        return msacDecoder.decodeBoolean(
            gatherVerticalAlikePartitionProbability(
                cdf,
                nonNullBlockLevel
            )
        )
    }

    /** Decodes a partition decision when only a vertical split or no split is allowed.
     * 
     * @param blockLevel the partition block level that selects the CDF shape
     * @param context the zero-based partition context index in `[0, 4)`
     * @return `true` when the block splits into two square children, otherwise `false`
     */
    fun readVerticalSplitOnly(blockLevel: PartitionBlockLevel, context: Int): Boolean {
        val nonNullBlockLevel =
            requireNotNullCompat(blockLevel, "blockLevel")
        val cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context)
        return msacDecoder.decodeBoolean(
            gatherHorizontalAlikePartitionProbability(
                cdf,
                nonNullBlockLevel
            )
        )
    }

    /** Partition block levels that select one of the five AV1 partition CDF groups. */
    enum class PartitionBlockLevel
    /** Creates one partition block level entry.
     * 
     * @param cdfIndex the CDF-table index inside `CdfContext`
     * @param symbolLimit the maximum decoded symbol value for this partition level
     */(
        /** The CDF-table index inside `CdfContext`. */
        val cdfIndex: Int,
        /** The maximum decoded symbol value for this partition level. */
        val symbolLimit: Int
    ) {
        /** Partition decisions for 128x128 blocks. */
        BLOCK_128X128(0, 7),

        /** Partition decisions for 64x64 blocks. */
        BLOCK_64X64(1, 9),

        /** Partition decisions for 32x32 blocks. */
        BLOCK_32X32(2, 9),

        /** Partition decisions for 16x16 blocks. */
        BLOCK_16X16(3, 9),

        /** Partition decisions for 8x8 blocks. */
        BLOCK_8X8(4, 3);

        /** Returns the CDF-table index inside `CdfContext`.
         * 
         * @return the CDF-table index inside `CdfContext`
         */
        fun cdfIndex(): Int {
            return cdfIndex
        }

        /** Returns the maximum decoded symbol value for this partition level.
         * 
         * @return the maximum decoded symbol value for this partition level
         */
        fun symbolLimit(): Int {
            return symbolLimit
        }
    }

    /** The signed CFL alpha pair decoded for one block.
     * 
     * @param alphaU the signed CFL alpha for chroma U
     * @param alphaV the signed CFL alpha for chroma V
     */
    data class CflAlpha(val alphaU: Int, val alphaV: Int)
    companion object {
        /** The `mv_joint` symbol that leaves both motion-vector components unchanged. */
        private const val MOTION_VECTOR_JOINT_NONE = 0

        /** The `mv_joint` bit that signals a horizontal motion-vector residual. */
        private const val MOTION_VECTOR_JOINT_HORIZONTAL = 1

        /** The `mv_joint` bit that signals a vertical motion-vector residual. */
        private const val MOTION_VECTOR_JOINT_VERTICAL = 2

        /** The Wiener coefficient lower bounds in AV1 coefficient order. */
        val WIENER_TAPS_MIN: IntArray = intArrayOf(-5, -23, -17)

        /** The Wiener coefficient upper bounds in AV1 coefficient order. */
        val WIENER_TAPS_MAX: IntArray = intArrayOf(10, 8, 46)

        /** The Wiener coefficient subexponential `k` parameters in AV1 coefficient order. */
        val WIENER_TAPS_K: IntArray = intArrayOf(1, 2, 3)

        /** The self-guided projection coefficient lower bounds. */
        val SELF_GUIDED_PROJECTION_MIN: IntArray = intArrayOf(-96, -32)

        /** The self-guided projection coefficient upper bounds. */
        val SELF_GUIDED_PROJECTION_MAX: IntArray = intArrayOf(31, 95)

        /** The self-guided projection coefficient subexponential `k` parameter. */
        private const val SELF_GUIDED_PROJECTION_SUBEXP_K = 4

        /** The self-guided projection coefficient precision. */
        private const val SELF_GUIDED_PROJECTION_BITS = 7

        /** The `dav1d` transform-type lookup table shared by intra and inter transform sets. */
        val TRANSFORM_TYPES_PER_SET: Array<TransformType> =
            arrayOf<TransformType>(
                TransformType.IDTX,
                TransformType.DCT_DCT,
                TransformType.ADST_ADST,
                TransformType.ADST_DCT,
                TransformType.DCT_ADST,
                TransformType.IDTX,
                TransformType.DCT_DCT,
                TransformType.V_DCT,
                TransformType.H_DCT,
                TransformType.ADST_ADST,
                TransformType.ADST_DCT,
                TransformType.DCT_ADST,
                TransformType.IDTX,
                TransformType.V_DCT,
                TransformType.H_DCT,
                TransformType.DCT_DCT,
                TransformType.ADST_DCT,
                TransformType.DCT_ADST,
                TransformType.FLIPADST_DCT,
                TransformType.DCT_FLIPADST,
                TransformType.ADST_ADST,
                TransformType.FLIPADST_FLIPADST,
                TransformType.ADST_FLIPADST,
                TransformType.FLIPADST_ADST,
                TransformType.IDTX,
                TransformType.V_DCT,
                TransformType.H_DCT,
                TransformType.V_ADST,
                TransformType.H_ADST,
                TransformType.V_FLIPADST,
                TransformType.H_FLIPADST,
                TransformType.DCT_DCT,
                TransformType.ADST_DCT,
                TransformType.DCT_ADST,
                TransformType.FLIPADST_DCT,
                TransformType.DCT_FLIPADST,
                TransformType.ADST_ADST,
                TransformType.FLIPADST_FLIPADST,
                TransformType.ADST_FLIPADST,
                TransformType.FLIPADST_ADST
            )

        /** Clips one integer into inclusive bounds.
         * 
         * @param value the input value
         * @param minimum the inclusive lower bound
         * @param maximum the inclusive upper bound
         * @return the clipped integer
         */
        fun clip(value: Int, minimum: Int, maximum: Int): Int {
            return max(minimum, min(value, maximum))
        }

        /** Gathers the effective split probability from a partition CDF when only horizontal partitioning or splitting is legal.
         * 
         * @param cdf the active partition CDF
         * @param blockLevel the partition block level that constrains the symbol set
         * @return the effective split probability in AV1 Q15 form
         */
        fun gatherVerticalAlikePartitionProbability(
            cdf: IntArray,
            blockLevel: PartitionBlockLevel
        ): Int {
            val symbolLimit = blockLevel.symbolLimit()
            var probability: Int =
                partitionElementProbability(cdf, PartitionType.VERTICAL.symbolIndex(), symbolLimit)
            probability += partitionElementProbability(
                cdf,
                PartitionType.SPLIT.symbolIndex(),
                symbolLimit
            )
            probability += partitionElementProbability(
                cdf,
                PartitionType.T_TOP_SPLIT.symbolIndex(),
                symbolLimit
            )
            probability += partitionElementProbability(
                cdf,
                PartitionType.T_LEFT_SPLIT.symbolIndex(),
                symbolLimit
            )
            probability += partitionElementProbability(
                cdf,
                PartitionType.T_RIGHT_SPLIT.symbolIndex(),
                symbolLimit
            )
            if (blockLevel != PartitionBlockLevel.BLOCK_128X128) {
                probability += partitionElementProbability(
                    cdf,
                    PartitionType.VERTICAL_4.symbolIndex(),
                    symbolLimit
                )
            }
            return probability
        }

        /** Gathers the effective split probability from a partition CDF when only vertical partitioning or splitting is legal.
         * 
         * @param cdf the active partition CDF
         * @param blockLevel the partition block level that constrains the symbol set
         * @return the effective split probability in AV1 Q15 form
         */
        fun gatherHorizontalAlikePartitionProbability(
            cdf: IntArray,
            blockLevel: PartitionBlockLevel
        ): Int {
            val symbolLimit = blockLevel.symbolLimit()
            var probability: Int = partitionElementProbability(
                cdf,
                PartitionType.HORIZONTAL.symbolIndex(),
                symbolLimit
            )
            probability += partitionElementProbability(
                cdf,
                PartitionType.SPLIT.symbolIndex(),
                symbolLimit
            )
            probability += partitionElementProbability(
                cdf,
                PartitionType.T_TOP_SPLIT.symbolIndex(),
                symbolLimit
            )
            probability += partitionElementProbability(
                cdf,
                PartitionType.T_BOTTOM_SPLIT.symbolIndex(),
                symbolLimit
            )
            probability += partitionElementProbability(
                cdf,
                PartitionType.T_LEFT_SPLIT.symbolIndex(),
                symbolLimit
            )
            if (blockLevel != PartitionBlockLevel.BLOCK_128X128) {
                probability += partitionElementProbability(
                    cdf,
                    PartitionType.HORIZONTAL_4.symbolIndex(),
                    symbolLimit
                )
            }
            return probability
        }

        /** Returns the probability mass of one partition symbol from an AV1 inverse CDF table.
         * 
         * @param cdf the active partition inverse CDF
         * @param symbol the partition symbol index
         * @param symbolLimit the maximum symbol index covered by the CDF
         * @return the probability mass of the supplied symbol in AV1 Q15 form
         */
        fun partitionElementProbability(cdf: IntArray, symbol: Int, symbolLimit: Int): Int {
            val upper = if (symbol == 0) 32768 else cdf[symbol - 1]
            val lower = if (symbol == symbolLimit) 0 else cdf[symbol]
            return upper - lower
        }
    }
}
