// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.entropy

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Mutable AV1 entropy CDF context seeded with the default tables for supported syntax elements.
 * 
 * All accessor methods return live mutable arrays intended to be passed directly to `MsacDecoder`
 * decode helpers that update CDFs in place. */
class CdfContext private constructor(
    skipCdfs: Array<IntArray>,
    skipModeCdfs: Array<IntArray>,
    intraCdfs: Array<IntArray>,
    compoundReferenceCdfs: Array<IntArray>,
    compoundDirectionCdfs: Array<IntArray>,
    singleReferenceCdfs: Array<Array<IntArray>>,
    compoundForwardReferenceCdfs: Array<Array<IntArray>>,
    compoundBackwardReferenceCdfs: Array<Array<IntArray>>,
    compoundUnidirectionalReferenceCdfs: Array<Array<IntArray>>,
    singleInterNewMvCdfs: Array<IntArray>,
    singleInterGlobalMvCdfs: Array<IntArray>,
    singleInterReferenceMvCdfs: Array<IntArray>,
    drlCdfs: Array<IntArray>,
    compoundInterModeCdfs: Array<IntArray>,
    motionModeCdfs: Array<IntArray>,
    obmcCdfs: Array<IntArray>,
    jointCompoundCdfs: Array<IntArray>,
    maskCompoundCdfs: Array<IntArray>,
    wedgeCompoundCdfs: Array<IntArray>,
    interIntraCdfs: Array<IntArray>,
    interIntraModeCdfs: Array<IntArray>,
    interIntraWedgeCdfs: Array<IntArray>,
    wedgeIndexCdfs: Array<IntArray>,
    interpolationFilterCdfs: Array<Array<IntArray>>,
    transformSizeCdfs: Array<Array<IntArray>>,
    transformPartitionCdfs: Array<IntArray>,
    interTransformTypeSet1Cdfs: Array<IntArray>,
    interTransformTypeSet2Cdf: IntArray,
    interTransformTypeSet3Cdfs: Array<IntArray>,
    intraTransformTypeSet1Cdfs: Array<Array<IntArray>>,
    intraTransformTypeSet2Cdfs: Array<Array<IntArray>>,
    coefficientSkipCdfs: Array<Array<IntArray>>,
    endOfBlockPrefixCdfs: Array<Array<Array<IntArray>>>,
    endOfBlockBaseTokenCdfs: Array<Array<Array<IntArray>>>,
    endOfBlockHighBitCdfs: Array<Array<Array<IntArray>>>,
    baseTokenCdfs: Array<Array<Array<IntArray>>>,
    dcSignCdfs: Array<Array<IntArray>>,
    highTokenCdfs: Array<Array<Array<IntArray>>>,
    deltaQCdf: IntArray,
    deltaLfCdfs: Array<IntArray>,
    motionVectorJointCdf: IntArray,
    motionVectorClassCdfs: Array<IntArray>,
    motionVectorSignCdfs: Array<IntArray>,
    motionVectorClass0Cdfs: Array<IntArray>,
    motionVectorClass0FpCdfs: Array<Array<IntArray>>,
    motionVectorClass0HpCdfs: Array<IntArray>,
    motionVectorClassNCdfs: Array<Array<IntArray>>,
    motionVectorClassNFpCdfs: Array<IntArray>,
    motionVectorClassNHpCdfs: Array<IntArray>,
    intrabcMotionVectorJointCdf: IntArray,
    intrabcMotionVectorClassCdfs: Array<IntArray>,
    intrabcMotionVectorSignCdfs: Array<IntArray>,
    intrabcMotionVectorClass0Cdfs: Array<IntArray>,
    intrabcMotionVectorClass0FpCdfs: Array<Array<IntArray>>,
    intrabcMotionVectorClass0HpCdfs: Array<IntArray>,
    intrabcMotionVectorClassNCdfs: Array<Array<IntArray>>,
    intrabcMotionVectorClassNFpCdfs: Array<IntArray>,
    intrabcMotionVectorClassNHpCdfs: Array<IntArray>,
    intrabcCdf: IntArray,
    restorationWienerCdf: IntArray,
    restorationSelfGuidedCdf: IntArray,
    restorationSwitchableCdf: IntArray,
    yModeCdfs: Array<IntArray>,
    useFilterIntraCdfs: Array<IntArray>,
    filterIntraCdf: IntArray,
    uvModeCdfs: Array<Array<IntArray>>,
    partitionCdfs: Array<Array<IntArray>>,
    lumaPaletteCdfs: Array<Array<IntArray>>,
    paletteSizeCdfs: Array<Array<IntArray>>,
    chromaPaletteCdfs: Array<IntArray>,
    colorMapCdfs: Array<Array<Array<IntArray>>>,
    segmentPredictionCdfs: Array<IntArray>,
    segmentIdCdfs: Array<IntArray>,
    keyFrameYModeCdfs: Array<Array<IntArray>>,
    angleDeltaCdfs: Array<IntArray>,
    cflSignCdf: IntArray,
    cflAlphaCdfs: Array<IntArray>
) {
    /** The mutable skip CDFs for skip-flag decoding. */
    private val skipCdfs: Array<IntArray>

    /** The mutable skip-mode CDFs for skip-mode decoding. */
    private val skipModeCdfs: Array<IntArray>

    /** The mutable intra/inter decision CDFs. */
    private val intraCdfs: Array<IntArray>

    /** The mutable compound-reference decision CDFs. */
    private val compoundReferenceCdfs: Array<IntArray>

    /** The mutable compound-direction decision CDFs. */
    private val compoundDirectionCdfs: Array<IntArray>

    /** The mutable single-reference selection CDFs. */
    private val singleReferenceCdfs: Array<Array<IntArray>>

    /** The mutable compound forward-reference selection CDFs. */
    private val compoundForwardReferenceCdfs: Array<Array<IntArray>>

    /** The mutable compound backward-reference selection CDFs. */
    private val compoundBackwardReferenceCdfs: Array<Array<IntArray>>

    /** The mutable compound unidirectional-reference selection CDFs. */
    private val compoundUnidirectionalReferenceCdfs: Array<Array<IntArray>>

    /** The mutable single-reference new-motion-vector CDFs. */
    private val singleInterNewMvCdfs: Array<IntArray>

    /** The mutable single-reference global-motion CDFs. */
    private val singleInterGlobalMvCdfs: Array<IntArray>

    /** The mutable single-reference reference-motion-vector CDFs. */
    private val singleInterReferenceMvCdfs: Array<IntArray>

    /** The mutable dynamic-reference-list selection CDFs. */
    private val drlCdfs: Array<IntArray>

    /** The mutable compound inter-mode CDFs. */
    private val compoundInterModeCdfs: Array<IntArray>

    /** The mutable motion-mode CDFs. */
    private val motionModeCdfs: Array<IntArray>

    /** The mutable OBMC selection CDFs. */
    private val obmcCdfs: Array<IntArray>

    /** The mutable joint-compound selection CDFs. */
    private val jointCompoundCdfs: Array<IntArray>

    /** The mutable masked-compound selection CDFs. */
    private val maskCompoundCdfs: Array<IntArray>

    /** The mutable wedge-vs-segment compound CDFs. */
    private val wedgeCompoundCdfs: Array<IntArray>

    /** The mutable inter-intra enable CDFs. */
    private val interIntraCdfs: Array<IntArray>

    /** The mutable inter-intra prediction-mode CDFs. */
    private val interIntraModeCdfs: Array<IntArray>

    /** The mutable inter-intra wedge enable CDFs. */
    private val interIntraWedgeCdfs: Array<IntArray>

    /** The mutable wedge-index CDFs. */
    private val wedgeIndexCdfs: Array<IntArray>

    /** The mutable switchable interpolation-filter CDFs. */
    private val interpolationFilterCdfs: Array<Array<IntArray>>

    /** The mutable transform-size CDFs. */
    private val transformSizeCdfs: Array<Array<IntArray>>

    /** The mutable inter transform-partition CDFs. */
    private val transformPartitionCdfs: Array<IntArray>

    /** The mutable inter transform-type CDFs for transform set 1. */
    private val interTransformTypeSet1Cdfs: Array<IntArray>

    /** The mutable inter transform-type CDF for transform set 2. */
    private val interTransformTypeSet2Cdf: IntArray

    /** The mutable inter transform-type CDFs for the reduced/large transform set. */
    private val interTransformTypeSet3Cdfs: Array<IntArray>

    /** The mutable intra transform-type CDFs for transform set 1. */
    private val intraTransformTypeSet1Cdfs: Array<Array<IntArray>>

    /** The mutable intra transform-type CDFs for transform set 2. */
    private val intraTransformTypeSet2Cdfs: Array<Array<IntArray>>

    /** The mutable coefficient-skip CDFs grouped by AV1 transform-context class. */
    private val coefficientSkipCdfs: Array<Array<IntArray>>

    /** The mutable end-of-block prefix CDFs grouped by clamped transform area. */
    private val endOfBlockPrefixCdfs: Array<Array<Array<IntArray>>>

    /** The mutable end-of-block base-token CDFs grouped by AV1 transform-context class. */
    private val endOfBlockBaseTokenCdfs: Array<Array<Array<IntArray>>>

    /** The mutable end-of-block high-bit CDFs grouped by AV1 transform-context class. */
    private val endOfBlockHighBitCdfs: Array<Array<Array<IntArray>>>

    /** The mutable coefficient base-token CDFs grouped by transform context, plane, and token context. */
    private val baseTokenCdfs: Array<Array<Array<IntArray>>>

    /** The mutable DC-sign CDFs for luma and chroma planes. */
    private val dcSignCdfs: Array<Array<IntArray>>

    /** The mutable coefficient high-token CDFs grouped by transform context, plane, and token context. */
    private val highTokenCdfs: Array<Array<Array<IntArray>>>

    /** The mutable delta-q CDF. */
    private val deltaQCdf: IntArray

    /** The mutable delta-lf CDFs. */
    private val deltaLfCdfs: Array<IntArray>

    /** The mutable motion-vector joint CDF. */
    private val motionVectorJointCdf: IntArray

    /** The mutable motion-vector class CDFs for vertical and horizontal components. */
    private val motionVectorClassCdfs: Array<IntArray>

    /** The mutable motion-vector sign CDFs for vertical and horizontal components. */
    private val motionVectorSignCdfs: Array<IntArray>

    /** The mutable class-0 motion-vector magnitude CDFs for vertical and horizontal components. */
    private val motionVectorClass0Cdfs: Array<IntArray>

    /** The mutable class-0 fractional motion-vector CDFs for vertical and horizontal components. */
    private val motionVectorClass0FpCdfs: Array<Array<IntArray>>

    /** The mutable class-0 high-precision motion-vector CDFs for vertical and horizontal components. */
    private val motionVectorClass0HpCdfs: Array<IntArray>

    /** The mutable non-class-0 motion-vector bit CDFs for vertical and horizontal components. */
    private val motionVectorClassNCdfs: Array<Array<IntArray>>

    /** The mutable non-class-0 fractional motion-vector CDFs for vertical and horizontal components. */
    private val motionVectorClassNFpCdfs: Array<IntArray>

    /** The mutable non-class-0 high-precision motion-vector CDFs for vertical and horizontal components. */
    private val motionVectorClassNHpCdfs: Array<IntArray>

    /** The mutable intrabc displacement-vector joint CDF. */
    private val intrabcMotionVectorJointCdf: IntArray

    /** The mutable intrabc displacement-vector class CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorClassCdfs: Array<IntArray>

    /** The mutable intrabc displacement-vector sign CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorSignCdfs: Array<IntArray>

    /** The mutable intrabc class-0 displacement-vector magnitude CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorClass0Cdfs: Array<IntArray>

    /** The mutable intrabc class-0 fractional displacement-vector CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorClass0FpCdfs: Array<Array<IntArray>>

    /** The mutable intrabc class-0 high-precision displacement-vector CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorClass0HpCdfs: Array<IntArray>

    /** The mutable intrabc non-class-0 displacement-vector bit CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorClassNCdfs: Array<Array<IntArray>>

    /** The mutable intrabc non-class-0 fractional displacement-vector CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorClassNFpCdfs: Array<IntArray>

    /** The mutable intrabc non-class-0 high-precision displacement-vector CDFs for vertical and horizontal components. */
    private val intrabcMotionVectorClassNHpCdfs: Array<IntArray>

    /** The mutable `intrabc` CDF. */
    private val intrabcCdf: IntArray

    /** The mutable Wiener restoration enable CDF. */
    private val restorationWienerCdf: IntArray

    /** The mutable self-guided restoration enable CDF. */
    private val restorationSelfGuidedCdf: IntArray

    /** The mutable switchable restoration CDF. */
    private val restorationSwitchableCdf: IntArray

    /** The mutable luma intra-mode CDFs. */
    private val yModeCdfs: Array<IntArray>

    /** The mutable `use_filter_intra` CDFs. */
    private val useFilterIntraCdfs: Array<IntArray>

    /** The mutable filter-intra-mode CDF. */
    private val filterIntraCdf: IntArray

    /** The mutable chroma intra-mode CDFs. */
    private val uvModeCdfs: Array<Array<IntArray>>

    /** The mutable partition CDFs. */
    private val partitionCdfs: Array<Array<IntArray>>

    /** The mutable luma palette-use CDFs. */
    private val lumaPaletteCdfs: Array<Array<IntArray>>

    /** The mutable palette-size CDFs for luma and chroma planes. */
    private val paletteSizeCdfs: Array<Array<IntArray>>

    /** The mutable chroma palette-use CDFs. */
    private val chromaPaletteCdfs: Array<IntArray>

    /** The mutable palette color-map CDFs for luma and chroma planes. */
    private val colorMapCdfs: Array<Array<Array<IntArray>>>

    /** The mutable segmentation-prediction CDFs. */
    private val segmentPredictionCdfs: Array<IntArray>

    /** The mutable segment-id CDFs. */
    private val segmentIdCdfs: Array<IntArray>

    /** The mutable key-frame luma intra-mode CDFs. */
    private val keyFrameYModeCdfs: Array<Array<IntArray>>

    /** The mutable directional angle-delta CDFs. */
    private val angleDeltaCdfs: Array<IntArray>

    /** The mutable CFL-sign CDF. */
    private val cflSignCdf: IntArray

    /** The mutable CFL-alpha CDFs. */
    private val cflAlphaCdfs: Array<IntArray>

    init {
        this.skipCdfs = requireNotNullCompat(skipCdfs, "skipCdfs")
        this.skipModeCdfs = requireNotNullCompat(skipModeCdfs, "skipModeCdfs")
        this.intraCdfs = requireNotNullCompat(intraCdfs, "intraCdfs")
        this.compoundReferenceCdfs =
            requireNotNullCompat(compoundReferenceCdfs, "compoundReferenceCdfs")
        this.compoundDirectionCdfs =
            requireNotNullCompat(compoundDirectionCdfs, "compoundDirectionCdfs")
        this.singleReferenceCdfs = requireNotNullCompat(
            singleReferenceCdfs,
            "singleReferenceCdfs"
        )
        this.compoundForwardReferenceCdfs = requireNotNullCompat(
            compoundForwardReferenceCdfs,
            "compoundForwardReferenceCdfs"
        )
        this.compoundBackwardReferenceCdfs = requireNotNullCompat(
            compoundBackwardReferenceCdfs,
            "compoundBackwardReferenceCdfs"
        )
        this.compoundUnidirectionalReferenceCdfs = requireNotNullCompat(
            compoundUnidirectionalReferenceCdfs,
            "compoundUnidirectionalReferenceCdfs"
        )
        this.singleInterNewMvCdfs =
            requireNotNullCompat(singleInterNewMvCdfs, "singleInterNewMvCdfs")
        this.singleInterGlobalMvCdfs = requireNotNullCompat(
            singleInterGlobalMvCdfs,
            "singleInterGlobalMvCdfs"
        )
        this.singleInterReferenceMvCdfs = requireNotNullCompat(
            singleInterReferenceMvCdfs,
            "singleInterReferenceMvCdfs"
        )
        this.drlCdfs = requireNotNullCompat(drlCdfs, "drlCdfs")
        this.compoundInterModeCdfs =
            requireNotNullCompat(compoundInterModeCdfs, "compoundInterModeCdfs")
        this.motionModeCdfs =
            requireNotNullCompat(motionModeCdfs, "motionModeCdfs")
        this.obmcCdfs = requireNotNullCompat(obmcCdfs, "obmcCdfs")
        this.jointCompoundCdfs =
            requireNotNullCompat(jointCompoundCdfs, "jointCompoundCdfs")
        this.maskCompoundCdfs =
            requireNotNullCompat(maskCompoundCdfs, "maskCompoundCdfs")
        this.wedgeCompoundCdfs =
            requireNotNullCompat(wedgeCompoundCdfs, "wedgeCompoundCdfs")
        this.interIntraCdfs =
            requireNotNullCompat(interIntraCdfs, "interIntraCdfs")
        this.interIntraModeCdfs =
            requireNotNullCompat(interIntraModeCdfs, "interIntraModeCdfs")
        this.interIntraWedgeCdfs =
            requireNotNullCompat(interIntraWedgeCdfs, "interIntraWedgeCdfs")
        this.wedgeIndexCdfs =
            requireNotNullCompat(wedgeIndexCdfs, "wedgeIndexCdfs")
        this.interpolationFilterCdfs = requireNotNullCompat(
            interpolationFilterCdfs,
            "interpolationFilterCdfs"
        )
        this.transformSizeCdfs =
            requireNotNullCompat(transformSizeCdfs, "transformSizeCdfs")
        this.transformPartitionCdfs = requireNotNullCompat(
            transformPartitionCdfs,
            "transformPartitionCdfs"
        )
        this.interTransformTypeSet1Cdfs = requireNotNullCompat(
            interTransformTypeSet1Cdfs,
            "interTransformTypeSet1Cdfs"
        )
        this.interTransformTypeSet2Cdf =
            requireNotNullCompat(interTransformTypeSet2Cdf, "interTransformTypeSet2Cdf")
        this.interTransformTypeSet3Cdfs = requireNotNullCompat(
            interTransformTypeSet3Cdfs,
            "interTransformTypeSet3Cdfs"
        )
        this.intraTransformTypeSet1Cdfs = requireNotNullCompat(
            intraTransformTypeSet1Cdfs,
            "intraTransformTypeSet1Cdfs"
        )
        this.intraTransformTypeSet2Cdfs = requireNotNullCompat(
            intraTransformTypeSet2Cdfs,
            "intraTransformTypeSet2Cdfs"
        )
        this.coefficientSkipCdfs = requireNotNullCompat(
            coefficientSkipCdfs,
            "coefficientSkipCdfs"
        )
        this.endOfBlockPrefixCdfs = requireNotNullCompat(
            endOfBlockPrefixCdfs,
            "endOfBlockPrefixCdfs"
        )
        this.endOfBlockBaseTokenCdfs = requireNotNullCompat(
            endOfBlockBaseTokenCdfs,
            "endOfBlockBaseTokenCdfs"
        )
        this.endOfBlockHighBitCdfs = requireNotNullCompat(
            endOfBlockHighBitCdfs,
            "endOfBlockHighBitCdfs"
        )
        this.baseTokenCdfs =
            requireNotNullCompat(baseTokenCdfs, "baseTokenCdfs")
        this.dcSignCdfs = requireNotNullCompat(dcSignCdfs, "dcSignCdfs")
        this.highTokenCdfs =
            requireNotNullCompat(highTokenCdfs, "highTokenCdfs")
        this.deltaQCdf = requireNotNullCompat(deltaQCdf, "deltaQCdf")
        this.deltaLfCdfs = requireNotNullCompat(deltaLfCdfs, "deltaLfCdfs")
        this.motionVectorJointCdf =
            requireNotNullCompat(motionVectorJointCdf, "motionVectorJointCdf")
        this.motionVectorClassCdfs =
            requireNotNullCompat(motionVectorClassCdfs, "motionVectorClassCdfs")
        this.motionVectorSignCdfs =
            requireNotNullCompat(motionVectorSignCdfs, "motionVectorSignCdfs")
        this.motionVectorClass0Cdfs = requireNotNullCompat(
            motionVectorClass0Cdfs,
            "motionVectorClass0Cdfs"
        )
        this.motionVectorClass0FpCdfs = requireNotNullCompat(
            motionVectorClass0FpCdfs,
            "motionVectorClass0FpCdfs"
        )
        this.motionVectorClass0HpCdfs = requireNotNullCompat(
            motionVectorClass0HpCdfs,
            "motionVectorClass0HpCdfs"
        )
        this.motionVectorClassNCdfs = requireNotNullCompat(
            motionVectorClassNCdfs,
            "motionVectorClassNCdfs"
        )
        this.motionVectorClassNFpCdfs = requireNotNullCompat(
            motionVectorClassNFpCdfs,
            "motionVectorClassNFpCdfs"
        )
        this.motionVectorClassNHpCdfs = requireNotNullCompat(
            motionVectorClassNHpCdfs,
            "motionVectorClassNHpCdfs"
        )
        this.intrabcMotionVectorJointCdf = requireNotNullCompat(
            intrabcMotionVectorJointCdf,
            "intrabcMotionVectorJointCdf"
        )
        this.intrabcMotionVectorClassCdfs = requireNotNullCompat(
            intrabcMotionVectorClassCdfs,
            "intrabcMotionVectorClassCdfs"
        )
        this.intrabcMotionVectorSignCdfs = requireNotNullCompat(
            intrabcMotionVectorSignCdfs,
            "intrabcMotionVectorSignCdfs"
        )
        this.intrabcMotionVectorClass0Cdfs = requireNotNullCompat(
            intrabcMotionVectorClass0Cdfs,
            "intrabcMotionVectorClass0Cdfs"
        )
        this.intrabcMotionVectorClass0FpCdfs = requireNotNullCompat(
            intrabcMotionVectorClass0FpCdfs,
            "intrabcMotionVectorClass0FpCdfs"
        )
        this.intrabcMotionVectorClass0HpCdfs = requireNotNullCompat(
            intrabcMotionVectorClass0HpCdfs,
            "intrabcMotionVectorClass0HpCdfs"
        )
        this.intrabcMotionVectorClassNCdfs = requireNotNullCompat(
            intrabcMotionVectorClassNCdfs,
            "intrabcMotionVectorClassNCdfs"
        )
        this.intrabcMotionVectorClassNFpCdfs = requireNotNullCompat(
            intrabcMotionVectorClassNFpCdfs,
            "intrabcMotionVectorClassNFpCdfs"
        )
        this.intrabcMotionVectorClassNHpCdfs = requireNotNullCompat(
            intrabcMotionVectorClassNHpCdfs,
            "intrabcMotionVectorClassNHpCdfs"
        )
        this.intrabcCdf = requireNotNullCompat(intrabcCdf, "intrabcCdf")
        this.restorationWienerCdf =
            requireNotNullCompat(restorationWienerCdf, "restorationWienerCdf")
        this.restorationSelfGuidedCdf =
            requireNotNullCompat(restorationSelfGuidedCdf, "restorationSelfGuidedCdf")
        this.restorationSwitchableCdf =
            requireNotNullCompat(restorationSwitchableCdf, "restorationSwitchableCdf")
        this.yModeCdfs = requireNotNullCompat(yModeCdfs, "yModeCdfs")
        this.useFilterIntraCdfs =
            requireNotNullCompat(useFilterIntraCdfs, "useFilterIntraCdfs")
        this.filterIntraCdf = requireNotNullCompat(filterIntraCdf, "filterIntraCdf")
        this.uvModeCdfs = requireNotNullCompat(uvModeCdfs, "uvModeCdfs")
        this.partitionCdfs =
            requireNotNullCompat(partitionCdfs, "partitionCdfs")
        this.lumaPaletteCdfs =
            requireNotNullCompat(lumaPaletteCdfs, "lumaPaletteCdfs")
        this.paletteSizeCdfs =
            requireNotNullCompat(paletteSizeCdfs, "paletteSizeCdfs")
        this.chromaPaletteCdfs =
            requireNotNullCompat(chromaPaletteCdfs, "chromaPaletteCdfs")
        this.colorMapCdfs =
            requireNotNullCompat(colorMapCdfs, "colorMapCdfs")
        this.segmentPredictionCdfs =
            requireNotNullCompat(segmentPredictionCdfs, "segmentPredictionCdfs")
        this.segmentIdCdfs = requireNotNullCompat(segmentIdCdfs, "segmentIdCdfs")
        this.keyFrameYModeCdfs =
            requireNotNullCompat(keyFrameYModeCdfs, "keyFrameYModeCdfs")
        this.angleDeltaCdfs =
            requireNotNullCompat(angleDeltaCdfs, "angleDeltaCdfs")
        this.cflSignCdf = requireNotNullCompat(cflSignCdf, "cflSignCdf")
        this.cflAlphaCdfs = requireNotNullCompat(cflAlphaCdfs, "cflAlphaCdfs")
    }

    /** Creates a deep copy of this mutable CDF context.
     * 
     * @return a deep copy of this mutable CDF context
     */
    fun copy(): CdfContext {
        return copy(false)
    }

    /** Creates a deep copy whose adaptive-symbol counters are reset for frame inheritance.
     * 
     * The returned context preserves every CDF threshold while setting each terminal update-count
     * slot to zero. The source context is not modified.
     * 
     * @return a deep copy with zero adaptive-symbol counters
     */
    fun copyWithResetSymbolCounters(): CdfContext {
        return copy(true)
    }

    /** Creates a deep copy and optionally resets every terminal adaptive-symbol counter.
     * 
     * @param resetSymbolCounters whether each copied CDF count slot should be set to zero
     * @return the requested deep copy
     */
    private fun copy(resetSymbolCounters: Boolean): CdfContext {
        return CdfContext(
            deepCopy(skipCdfs, resetSymbolCounters),
            deepCopy(skipModeCdfs, resetSymbolCounters),
            deepCopy(intraCdfs, resetSymbolCounters),
            deepCopy(compoundReferenceCdfs, resetSymbolCounters),
            deepCopy(compoundDirectionCdfs, resetSymbolCounters),
            deepCopy(singleReferenceCdfs, resetSymbolCounters),
            deepCopy(compoundForwardReferenceCdfs, resetSymbolCounters),
            deepCopy(compoundBackwardReferenceCdfs, resetSymbolCounters),
            deepCopy(compoundUnidirectionalReferenceCdfs, resetSymbolCounters),
            deepCopy(singleInterNewMvCdfs, resetSymbolCounters),
            deepCopy(singleInterGlobalMvCdfs, resetSymbolCounters),
            deepCopy(singleInterReferenceMvCdfs, resetSymbolCounters),
            deepCopy(drlCdfs, resetSymbolCounters),
            deepCopy(compoundInterModeCdfs, resetSymbolCounters),
            deepCopy(motionModeCdfs, resetSymbolCounters),
            deepCopy(obmcCdfs, resetSymbolCounters),
            deepCopy(jointCompoundCdfs, resetSymbolCounters),
            deepCopy(maskCompoundCdfs, resetSymbolCounters),
            deepCopy(wedgeCompoundCdfs, resetSymbolCounters),
            deepCopy(interIntraCdfs, resetSymbolCounters),
            deepCopy(interIntraModeCdfs, resetSymbolCounters),
            deepCopy(interIntraWedgeCdfs, resetSymbolCounters),
            deepCopy(wedgeIndexCdfs, resetSymbolCounters),
            deepCopy(interpolationFilterCdfs, resetSymbolCounters),
            deepCopy(transformSizeCdfs, resetSymbolCounters),
            deepCopy(transformPartitionCdfs, resetSymbolCounters),
            deepCopy(interTransformTypeSet1Cdfs, resetSymbolCounters),
            copyCdf(interTransformTypeSet2Cdf, resetSymbolCounters),
            deepCopy(interTransformTypeSet3Cdfs, resetSymbolCounters),
            deepCopy(intraTransformTypeSet1Cdfs, resetSymbolCounters),
            deepCopy(intraTransformTypeSet2Cdfs, resetSymbolCounters),
            deepCopy(coefficientSkipCdfs, resetSymbolCounters),
            deepCopy(endOfBlockPrefixCdfs, resetSymbolCounters),
            deepCopy(endOfBlockBaseTokenCdfs, resetSymbolCounters),
            deepCopy(endOfBlockHighBitCdfs, resetSymbolCounters),
            deepCopy(baseTokenCdfs, resetSymbolCounters),
            deepCopy(dcSignCdfs, resetSymbolCounters),
            deepCopy(highTokenCdfs, resetSymbolCounters),
            copyCdf(deltaQCdf, resetSymbolCounters),
            deepCopy(deltaLfCdfs, resetSymbolCounters),
            copyCdf(motionVectorJointCdf, resetSymbolCounters),
            deepCopy(motionVectorClassCdfs, resetSymbolCounters),
            deepCopy(motionVectorSignCdfs, resetSymbolCounters),
            deepCopy(motionVectorClass0Cdfs, resetSymbolCounters),
            deepCopy(motionVectorClass0FpCdfs, resetSymbolCounters),
            deepCopy(motionVectorClass0HpCdfs, resetSymbolCounters),
            deepCopy(motionVectorClassNCdfs, resetSymbolCounters),
            deepCopy(motionVectorClassNFpCdfs, resetSymbolCounters),
            deepCopy(motionVectorClassNHpCdfs, resetSymbolCounters),
            copyCdf(intrabcMotionVectorJointCdf, resetSymbolCounters),
            deepCopy(intrabcMotionVectorClassCdfs, resetSymbolCounters),
            deepCopy(intrabcMotionVectorSignCdfs, resetSymbolCounters),
            deepCopy(intrabcMotionVectorClass0Cdfs, resetSymbolCounters),
            deepCopy(intrabcMotionVectorClass0FpCdfs, resetSymbolCounters),
            deepCopy(intrabcMotionVectorClass0HpCdfs, resetSymbolCounters),
            deepCopy(intrabcMotionVectorClassNCdfs, resetSymbolCounters),
            deepCopy(intrabcMotionVectorClassNFpCdfs, resetSymbolCounters),
            deepCopy(intrabcMotionVectorClassNHpCdfs, resetSymbolCounters),
            copyCdf(intrabcCdf, resetSymbolCounters),
            copyCdf(restorationWienerCdf, resetSymbolCounters),
            copyCdf(restorationSelfGuidedCdf, resetSymbolCounters),
            copyCdf(restorationSwitchableCdf, resetSymbolCounters),
            deepCopy(yModeCdfs, resetSymbolCounters),
            deepCopy(useFilterIntraCdfs, resetSymbolCounters),
            copyCdf(filterIntraCdf, resetSymbolCounters),
            deepCopy(uvModeCdfs, resetSymbolCounters),
            deepCopy(partitionCdfs, resetSymbolCounters),
            deepCopy(lumaPaletteCdfs, resetSymbolCounters),
            deepCopy(paletteSizeCdfs, resetSymbolCounters),
            deepCopy(chromaPaletteCdfs, resetSymbolCounters),
            deepCopy(colorMapCdfs, resetSymbolCounters),
            deepCopy(segmentPredictionCdfs, resetSymbolCounters),
            deepCopy(segmentIdCdfs, resetSymbolCounters),
            deepCopy(keyFrameYModeCdfs, resetSymbolCounters),
            deepCopy(angleDeltaCdfs, resetSymbolCounters),
            copyCdf(cflSignCdf, resetSymbolCounters),
            deepCopy(cflAlphaCdfs, resetSymbolCounters)
        )
    }

    /** Returns the live mutable skip CDF for the supplied context index.
     * 
     * @param context the zero-based skip context index
     * @return the live mutable skip CDF for the supplied context index
     */
    fun mutableSkipCdf(context: Int): IntArray {
        return skipCdfs[checkIndexCompat(context, skipCdfs.size)]
    }

    /** Returns the live mutable skip-mode CDF for the supplied context index.
     * 
     * @param context the zero-based skip-mode context index
     * @return the live mutable skip-mode CDF for the supplied context index
     */
    fun mutableSkipModeCdf(context: Int): IntArray {
        return skipModeCdfs[checkIndexCompat(context, skipModeCdfs.size)]
    }

    /** Returns the live mutable intra/inter decision CDF for the supplied context index.
     * 
     * @param context the zero-based intra/inter context index
     * @return the live mutable intra/inter decision CDF for the supplied context index
     */
    fun mutableIntraCdf(context: Int): IntArray {
        return intraCdfs[checkIndexCompat(context, intraCdfs.size)]
    }

    /** Returns the live mutable compound-reference decision CDF for the supplied context index.
     * 
     * @param context the zero-based compound-reference context index in `[0, 5)`
     * @return the live mutable compound-reference decision CDF for the supplied context index
     */
    fun mutableCompoundReferenceCdf(context: Int): IntArray {
        return compoundReferenceCdfs[checkIndexCompat(context, compoundReferenceCdfs.size)]
    }

    /** Returns the live mutable compound-direction decision CDF for the supplied context index.
     * 
     * @param context the zero-based compound-direction context index in `[0, 5)`
     * @return the live mutable compound-direction decision CDF for the supplied context index
     */
    fun mutableCompoundDirectionCdf(context: Int): IntArray {
        return compoundDirectionCdfs[checkIndexCompat(context, compoundDirectionCdfs.size)]
    }

    /** Returns the live mutable single-reference selection CDF for the supplied table and context.
     * 
     * @param tableIndex the zero-based single-reference table index in `[0, 6)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the live mutable single-reference selection CDF for the supplied inputs
     */
    fun mutableSingleReferenceCdf(tableIndex: Int, context: Int): IntArray {
        val table = singleReferenceCdfs[checkIndexCompat(tableIndex, singleReferenceCdfs.size)]
        return table[checkIndexCompat(context, table.size)]
    }

    /** Returns the live mutable compound forward-reference selection CDF for the supplied table and context.
     * 
     * @param tableIndex the zero-based compound forward-reference table index in `[0, 3)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the live mutable compound forward-reference selection CDF for the supplied inputs
     */
    fun mutableCompoundForwardReferenceCdf(tableIndex: Int, context: Int): IntArray {
        val table = compoundForwardReferenceCdfs[checkIndexCompat(
            tableIndex,
            compoundForwardReferenceCdfs.size
        )]
        return table[checkIndexCompat(context, table.size)]
    }

    /** Returns the live mutable compound backward-reference selection CDF for the supplied table and context.
     * 
     * @param tableIndex the zero-based compound backward-reference table index in `[0, 2)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the live mutable compound backward-reference selection CDF for the supplied inputs
     */
    fun mutableCompoundBackwardReferenceCdf(tableIndex: Int, context: Int): IntArray {
        val table = compoundBackwardReferenceCdfs[checkIndexCompat(
            tableIndex,
            compoundBackwardReferenceCdfs.size
        )]
        return table[checkIndexCompat(context, table.size)]
    }

    /** Returns the live mutable compound unidirectional-reference selection CDF for the supplied table and context.
     * 
     * @param tableIndex the zero-based compound unidirectional-reference table index in `[0, 3)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the live mutable compound unidirectional-reference selection CDF for the supplied inputs
     */
    fun mutableCompoundUnidirectionalReferenceCdf(tableIndex: Int, context: Int): IntArray {
        val table = compoundUnidirectionalReferenceCdfs[checkIndexCompat(
            tableIndex,
            compoundUnidirectionalReferenceCdfs.size
        )]
        return table[checkIndexCompat(context, table.size)]
    }

    /** Returns the live mutable single-reference new-motion-vector CDF for the supplied context index.
     * 
     * @param context the zero-based single-reference new-motion-vector context index in `[0, 6)`
     * @return the live mutable single-reference new-motion-vector CDF for the supplied context index
     */
    fun mutableSingleInterNewMvCdf(context: Int): IntArray {
        return singleInterNewMvCdfs[checkIndexCompat(context, singleInterNewMvCdfs.size)]
    }

    /** Returns the live mutable single-reference global-motion CDF for the supplied context index.
     * 
     * @param context the zero-based single-reference global-motion context index in `[0, 2)`
     * @return the live mutable single-reference global-motion CDF for the supplied context index
     */
    fun mutableSingleInterGlobalMvCdf(context: Int): IntArray {
        return singleInterGlobalMvCdfs[checkIndexCompat(context, singleInterGlobalMvCdfs.size)]
    }

    /** Returns the live mutable single-reference reference-motion-vector CDF for the supplied context index.
     * 
     * @param context the zero-based single-reference reference-motion-vector context index in `[0, 6)`
     * @return the live mutable single-reference reference-motion-vector CDF for the supplied context index
     */
    fun mutableSingleInterReferenceMvCdf(context: Int): IntArray {
        return singleInterReferenceMvCdfs[checkIndexCompat(
            context,
            singleInterReferenceMvCdfs.size
        )]
    }

    /** Returns the live mutable dynamic-reference-list selection CDF for the supplied context index.
     * 
     * @param context the zero-based dynamic-reference-list context index in `[0, 3)`
     * @return the live mutable dynamic-reference-list selection CDF for the supplied context index
     */
    fun mutableDrlCdf(context: Int): IntArray {
        return drlCdfs[checkIndexCompat(context, drlCdfs.size)]
    }

    /** Returns the live mutable compound inter-mode CDF for the supplied context index.
     * 
     * @param context the zero-based compound inter-mode context index in `[0, 8)`
     * @return the live mutable compound inter-mode CDF for the supplied context index
     */
    fun mutableCompoundInterModeCdf(context: Int): IntArray {
        return compoundInterModeCdfs[checkIndexCompat(context, compoundInterModeCdfs.size)]
    }

    /** Returns the live mutable motion-mode CDF for the supplied block-size context index.
     * 
     * @param context the zero-based block-size context index in `[0, 22)`
     * @return the live mutable motion-mode CDF for the supplied context index
     */
    fun mutableMotionModeCdf(context: Int): IntArray {
        return motionModeCdfs[checkIndexCompat(context, motionModeCdfs.size)]
    }

    /** Returns the live mutable OBMC selection CDF for the supplied block-size context index.
     * 
     * @param context the zero-based block-size context index in `[0, 22)`
     * @return the live mutable OBMC selection CDF for the supplied context index
     */
    fun mutableObmcCdf(context: Int): IntArray {
        return obmcCdfs[checkIndexCompat(context, obmcCdfs.size)]
    }

    /** Returns the live mutable joint-compound selection CDF for the supplied context index.
     * 
     * @param context the zero-based joint-compound context index in `[0, 6)`
     * @return the live mutable joint-compound selection CDF for the supplied context index
     */
    fun mutableJointCompoundCdf(context: Int): IntArray {
        return jointCompoundCdfs[checkIndexCompat(context, jointCompoundCdfs.size)]
    }

    /** Returns the live mutable masked-compound selection CDF for the supplied context index.
     * 
     * @param context the zero-based masked-compound context index in `[0, 6)`
     * @return the live mutable masked-compound selection CDF for the supplied context index
     */
    fun mutableMaskCompoundCdf(context: Int): IntArray {
        return maskCompoundCdfs[checkIndexCompat(context, maskCompoundCdfs.size)]
    }

    /** Returns the live mutable wedge-vs-segment compound CDF for the supplied context index.
     * 
     * @param context the zero-based wedge context index in `[0, 9)`
     * @return the live mutable wedge-vs-segment compound CDF for the supplied context index
     */
    fun mutableWedgeCompoundCdf(context: Int): IntArray {
        return wedgeCompoundCdfs[checkIndexCompat(context, wedgeCompoundCdfs.size)]
    }

    /** Returns the live mutable inter-intra enable CDF for the supplied context index.
     * 
     * @param context the zero-based inter-intra context index in `[0, 4)`
     * @return the live mutable inter-intra enable CDF for the supplied context index
     */
    fun mutableInterIntraCdf(context: Int): IntArray {
        return interIntraCdfs[checkIndexCompat(context, interIntraCdfs.size)]
    }

    /** Returns the live mutable inter-intra prediction-mode CDF for the supplied context index.
     * 
     * @param context the zero-based inter-intra mode context index in `[0, 4)`
     * @return the live mutable inter-intra prediction-mode CDF for the supplied context index
     */
    fun mutableInterIntraModeCdf(context: Int): IntArray {
        return interIntraModeCdfs[checkIndexCompat(context, interIntraModeCdfs.size)]
    }

    /** Returns the live mutable inter-intra wedge enable CDF for the supplied context index.
     * 
     * @param context the zero-based wedge context index in `[0, 7)`
     * @return the live mutable inter-intra wedge enable CDF for the supplied context index
     */
    fun mutableInterIntraWedgeCdf(context: Int): IntArray {
        return interIntraWedgeCdfs[checkIndexCompat(context, interIntraWedgeCdfs.size)]
    }

    /** Returns the live mutable wedge-index CDF for the supplied context index.
     * 
     * @param context the zero-based wedge context index in `[0, 9)`
     * @return the live mutable wedge-index CDF for the supplied context index
     */
    fun mutableWedgeIndexCdf(context: Int): IntArray {
        return wedgeIndexCdfs[checkIndexCompat(context, wedgeIndexCdfs.size)]
    }

    /** Returns the live mutable switchable interpolation-filter CDF for the supplied direction and context.
     * 
     * Direction `0` selects the horizontal filter symbol and direction `1` selects the vertical
     * filter symbol.
     * 
     * @param direction the zero-based interpolation-filter direction index in `[0, 2)`
     * @param context the zero-based switchable interpolation-filter context index in `[0, 8)`
     * @return the live mutable switchable interpolation-filter CDF for the supplied direction and context
     */
    fun mutableInterpolationFilterCdf(direction: Int, context: Int): IntArray {
        val directionCdfs =
            interpolationFilterCdfs[checkIndexCompat(direction, interpolationFilterCdfs.size)]
        return directionCdfs[checkIndexCompat(context, directionCdfs.size)]
    }

    /** Returns the live mutable transform-size CDF for the supplied max-size table and context.
     * 
     * @param tableIndex the zero-based max-transform-size table index in `[0, 4)`
     * @param context the zero-based transform-size context index in `[0, 3)`
     * @return the live mutable transform-size CDF for the supplied inputs
     */
    fun mutableTransformSizeCdf(tableIndex: Int, context: Int): IntArray {
        val table = transformSizeCdfs[checkIndexCompat(tableIndex, transformSizeCdfs.size)]
        return table[checkIndexCompat(context, table.size)]
    }

    /** Returns the live mutable inter transform-partition CDF for the supplied table and context.
     * 
     * The table index follows `dav1d`'s `cat` formula from `read_tx_tree()`.
     * 
     * @param tableIndex the zero-based inter transform-partition table index in `[0, 7)`
     * @param context the zero-based context index in `[0, 3)`
     * @return the live mutable inter transform-partition CDF for the supplied inputs
     */
    fun mutableTransformPartitionCdf(tableIndex: Int, context: Int): IntArray {
        val baseIndex = checkIndexCompat(tableIndex, 7) * 3
        return transformPartitionCdfs[baseIndex + checkIndexCompat(context, 3)]
    }

    /** Returns the live mutable inter transform-type CDF for transform set 1.
     * 
     * @param minSquareLevel the smallest square transform level touched by the transform in `[0, 2)`
     * @return the live mutable inter transform-type CDF for transform set 1
     */
    fun mutableInterTransformTypeSet1Cdf(minSquareLevel: Int): IntArray {
        return interTransformTypeSet1Cdfs[checkIndexCompat(
            minSquareLevel,
            interTransformTypeSet1Cdfs.size
        )]
    }

    /** Returns the live mutable inter transform-type CDF for transform set 2.
     * 
     * @return the live mutable inter transform-type CDF for transform set 2
     */
    fun mutableInterTransformTypeSet2Cdf(): IntArray {
        return interTransformTypeSet2Cdf
    }

    /** Returns the live mutable inter transform-type CDF for the reduced/large transform set.
     * 
     * @param minSquareLevel the smallest square transform level touched by the transform in `[0, 4)`
     * @return the live mutable inter transform-type CDF for the reduced/large transform set
     */
    fun mutableInterTransformTypeSet3Cdf(minSquareLevel: Int): IntArray {
        return interTransformTypeSet3Cdfs[checkIndexCompat(
            minSquareLevel,
            interTransformTypeSet3Cdfs.size
        )]
    }

    /** Returns the live mutable intra transform-type CDF for transform set 1.
     * 
     * @param minSquareLevel the smallest square transform level touched by the transform in `[0, 2)`
     * @param yMode the zero-based luma intra prediction mode index in `[0, 13)`
     * @return the live mutable intra transform-type CDF for transform set 1
     */
    fun mutableIntraTransformTypeSet1Cdf(minSquareLevel: Int, yMode: Int): IntArray {
        val table = intraTransformTypeSet1Cdfs[checkIndexCompat(
            minSquareLevel,
            intraTransformTypeSet1Cdfs.size
        )]
        return table[checkIndexCompat(yMode, table.size)]
    }

    /** Returns the live mutable intra transform-type CDF for transform set 2.
     * 
     * @param minSquareLevel the smallest square transform level touched by the transform in `[0, 3)`
     * @param yMode the zero-based luma intra prediction mode index in `[0, 13)`
     * @return the live mutable intra transform-type CDF for transform set 2
     */
    fun mutableIntraTransformTypeSet2Cdf(minSquareLevel: Int, yMode: Int): IntArray {
        val table = intraTransformTypeSet2Cdfs[checkIndexCompat(
            minSquareLevel,
            intraTransformTypeSet2Cdfs.size
        )]
        return table[checkIndexCompat(yMode, table.size)]
    }

    /** Returns the live mutable coefficient-skip CDF for the supplied transform-context group and context index.
     * 
     * @param transformContextIndex the zero-based AV1 transform-context group index in `[0, 5)`
     * @param context the zero-based coefficient-skip context index in `[0, 13)`
     * @return the live mutable coefficient-skip CDF for the supplied inputs
     */
    fun mutableCoefficientSkipCdf(transformContextIndex: Int, context: Int): IntArray {
        val table =
            coefficientSkipCdfs[checkIndexCompat(transformContextIndex, coefficientSkipCdfs.size)]
        return table[checkIndexCompat(context, table.size)]
    }

    /** Returns the live mutable end-of-block prefix CDF for the supplied transform-area context.
     * 
     * @param tx2dSizeContext the clamped transform-area context in `[0, 7)`
     * @param chroma whether the syntax belongs to a chroma plane
     * @param oneDimensional whether the active transform type belongs to a 1D transform class
     * @return the live mutable end-of-block prefix CDF for the supplied inputs
     */
    fun mutableEndOfBlockPrefixCdf(
        tx2dSizeContext: Int,
        chroma: Boolean,
        oneDimensional: Boolean
    ): IntArray {
        val areaTable =
            endOfBlockPrefixCdfs[checkIndexCompat(tx2dSizeContext, endOfBlockPrefixCdfs.size)]
        val chromaTable = areaTable[if (chroma) 1 else 0]
        return chromaTable[if (oneDimensional) 1 else 0]
    }

    /** Returns the live mutable end-of-block base-token CDF for the supplied transform context.
     * 
     * @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based end-of-block base-token context in `[0, 4)`
     * @return the live mutable end-of-block base-token CDF for the supplied inputs
     */
    fun mutableEndOfBlockBaseTokenCdf(
        transformContextIndex: Int,
        chroma: Boolean,
        context: Int
    ): IntArray {
        val transformTable = endOfBlockBaseTokenCdfs[checkIndexCompat(
            transformContextIndex,
            endOfBlockBaseTokenCdfs.size
        )]
        val chromaTable = transformTable[if (chroma) 1 else 0]
        return chromaTable[checkIndexCompat(context, chromaTable.size)]
    }

    /** Returns the live mutable end-of-block high-bit CDF for the supplied transform context.
     * 
     * @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based end-of-block high-bit context index in `[0, 9)`
     * @return the live mutable end-of-block high-bit CDF for the supplied inputs
     */
    fun mutableEndOfBlockHighBitCdf(
        transformContextIndex: Int,
        chroma: Boolean,
        context: Int
    ): IntArray {
        val transformTable = endOfBlockHighBitCdfs[checkIndexCompat(
            transformContextIndex,
            endOfBlockHighBitCdfs.size
        )]
        val chromaTable = transformTable[if (chroma) 1 else 0]
        return chromaTable[checkIndexCompat(context, chromaTable.size)]
    }

    /** Returns the live mutable coefficient base-token CDF for the supplied transform context and base-token context.
     * 
     * @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based base-token context index
     * @return the live mutable coefficient base-token CDF for the supplied inputs
     */
    fun mutableBaseTokenCdf(transformContextIndex: Int, chroma: Boolean, context: Int): IntArray {
        val transformTable =
            baseTokenCdfs[checkIndexCompat(transformContextIndex, baseTokenCdfs.size)]
        val planeTable = transformTable[if (chroma) 1 else 0]
        return planeTable[checkIndexCompat(context, planeTable.size)]
    }

    /** Returns the live mutable DC-sign CDF for the supplied plane and context.
     * 
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based DC-sign context in `[0, 3)`
     * @return the live mutable DC-sign CDF for the supplied plane and context
     */
    fun mutableDcSignCdf(chroma: Boolean, context: Int): IntArray {
        val planeTable = dcSignCdfs[if (chroma) 1 else 0]
        return planeTable[checkIndexCompat(context, planeTable.size)]
    }

    /** Returns the live mutable DC high-token CDF for the supplied transform context and plane.
     * 
     * @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
     * @param chroma whether the syntax belongs to a chroma plane
     * @return the live mutable DC high-token CDF for the supplied inputs
     */
    fun mutableDcHighTokenCdf(transformContextIndex: Int, chroma: Boolean): IntArray {
        return mutableHighTokenCdf(transformContextIndex, chroma, 0)
    }

    /** Returns the live mutable coefficient high-token CDF for the supplied transform context and `br_tok` context.
     * 
     * @param transformContextIndex the AV1 transform-context group index in `[0, 5)`
     * @param chroma whether the syntax belongs to a chroma plane
     * @param context the zero-based `br_tok` context index
     * @return the live mutable coefficient high-token CDF for the supplied inputs
     */
    fun mutableHighTokenCdf(transformContextIndex: Int, chroma: Boolean, context: Int): IntArray {
        val checkedTransformContextIndex =
            checkIndexCompat(transformContextIndex, baseTokenCdfs.size)
        val transformTable =
            highTokenCdfs[min(checkedTransformContextIndex, highTokenCdfs.size - 1)]
        val planeTable = transformTable[if (chroma) 1 else 0]
        return planeTable[checkIndexCompat(context, planeTable.size)]
    }

    /** Returns the live mutable delta-q CDF.
     * 
     * @return the live mutable delta-q CDF
     */
    fun mutableDeltaQCdf(): IntArray {
        return deltaQCdf
    }

    /** Returns the live mutable delta-lf CDF for the supplied context index.
     * 
     * @param context the zero-based delta-lf context index in `[0, 5)`
     * @return the live mutable delta-lf CDF for the supplied context index
     */
    fun mutableDeltaLfCdf(context: Int): IntArray {
        return deltaLfCdfs[checkIndexCompat(context, deltaLfCdfs.size)]
    }

    /** Returns the live mutable motion-vector joint CDF.
     * 
     * @return the live mutable motion-vector joint CDF
     */
    fun mutableMotionVectorJointCdf(): IntArray {
        return motionVectorJointCdf
    }

    /** Returns the live mutable motion-vector class CDF for the supplied component.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable motion-vector class CDF for the supplied component
     */
    fun mutableMotionVectorClassCdf(component: Int): IntArray {
        return motionVectorClassCdfs[checkIndexCompat(component, motionVectorClassCdfs.size)]
    }

    /** Returns the live mutable motion-vector sign CDF for the supplied component.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable motion-vector sign CDF for the supplied component
     */
    fun mutableMotionVectorSignCdf(component: Int): IntArray {
        return motionVectorSignCdfs[checkIndexCompat(component, motionVectorSignCdfs.size)]
    }

    /** Returns the live mutable class-0 motion-vector magnitude CDF for the supplied component.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable class-0 motion-vector magnitude CDF for the supplied component
     */
    fun mutableMotionVectorClass0Cdf(component: Int): IntArray {
        return motionVectorClass0Cdfs[checkIndexCompat(component, motionVectorClass0Cdfs.size)]
    }

    /** Returns the live mutable class-0 fractional motion-vector CDF for the supplied component and integer bit.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @param integerBit the decoded class-0 integer bit in `[0, 2)`
     * @return the live mutable class-0 fractional motion-vector CDF for the supplied inputs
     */
    fun mutableMotionVectorClass0FpCdf(component: Int, integerBit: Int): IntArray {
        val tables =
            motionVectorClass0FpCdfs[checkIndexCompat(component, motionVectorClass0FpCdfs.size)]
        return tables[checkIndexCompat(integerBit, tables.size)]
    }

    /** Returns the live mutable class-0 high-precision motion-vector CDF for the supplied component.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable class-0 high-precision motion-vector CDF for the supplied component
     */
    fun mutableMotionVectorClass0HpCdf(component: Int): IntArray {
        return motionVectorClass0HpCdfs[checkIndexCompat(
            component,
            motionVectorClass0HpCdfs.size
        )]
    }

    /** Returns the live mutable non-class-0 motion-vector bit CDF for the supplied component and bit index.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @param bitIndex the zero-based motion-vector class bit index in `[0, 10)`
     * @return the live mutable non-class-0 motion-vector bit CDF for the supplied inputs
     */
    fun mutableMotionVectorClassNCdf(component: Int, bitIndex: Int): IntArray {
        val tables =
            motionVectorClassNCdfs[checkIndexCompat(component, motionVectorClassNCdfs.size)]
        return tables[checkIndexCompat(bitIndex, tables.size)]
    }

    /** Returns the live mutable non-class-0 fractional motion-vector CDF for the supplied component.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable non-class-0 fractional motion-vector CDF for the supplied component
     */
    fun mutableMotionVectorClassNFpCdf(component: Int): IntArray {
        return motionVectorClassNFpCdfs[checkIndexCompat(
            component,
            motionVectorClassNFpCdfs.size
        )]
    }

    /** Returns the live mutable non-class-0 high-precision motion-vector CDF for the supplied component.
     * 
     * @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable non-class-0 high-precision motion-vector CDF for the supplied component
     */
    fun mutableMotionVectorClassNHpCdf(component: Int): IntArray {
        return motionVectorClassNHpCdfs[checkIndexCompat(
            component,
            motionVectorClassNHpCdfs.size
        )]
    }

    /** Returns the live mutable intrabc displacement-vector joint CDF.
     * 
     * @return the live mutable intrabc displacement-vector joint CDF
     */
    fun mutableIntrabcMotionVectorJointCdf(): IntArray {
        return intrabcMotionVectorJointCdf
    }

    /** Returns the live mutable intrabc displacement-vector class CDF for the supplied component.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable intrabc displacement-vector class CDF for the supplied component
     */
    fun mutableIntrabcMotionVectorClassCdf(component: Int): IntArray {
        return intrabcMotionVectorClassCdfs[checkIndexCompat(
            component,
            intrabcMotionVectorClassCdfs.size
        )]
    }

    /** Returns the live mutable intrabc displacement-vector sign CDF for the supplied component.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable intrabc displacement-vector sign CDF for the supplied component
     */
    fun mutableIntrabcMotionVectorSignCdf(component: Int): IntArray {
        return intrabcMotionVectorSignCdfs[checkIndexCompat(
            component,
            intrabcMotionVectorSignCdfs.size
        )]
    }

    /** Returns the live mutable intrabc class-0 displacement-vector magnitude CDF for the supplied component.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable intrabc class-0 displacement-vector magnitude CDF for the supplied component
     */
    fun mutableIntrabcMotionVectorClass0Cdf(component: Int): IntArray {
        return intrabcMotionVectorClass0Cdfs[checkIndexCompat(
            component,
            intrabcMotionVectorClass0Cdfs.size
        )]
    }

    /** Returns the live mutable intrabc class-0 fractional displacement-vector CDF.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @param integerBit the decoded class-0 integer bit in `[0, 2)`
     * @return the live mutable intrabc class-0 fractional displacement-vector CDF for the supplied inputs
     */
    fun mutableIntrabcMotionVectorClass0FpCdf(component: Int, integerBit: Int): IntArray {
        val tables = intrabcMotionVectorClass0FpCdfs[checkIndexCompat(
            component,
            intrabcMotionVectorClass0FpCdfs.size
        )
        ]
        return tables[checkIndexCompat(integerBit, tables.size)]
    }

    /** Returns the live mutable intrabc class-0 high-precision displacement-vector CDF.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable intrabc class-0 high-precision displacement-vector CDF for the supplied component
     */
    fun mutableIntrabcMotionVectorClass0HpCdf(component: Int): IntArray {
        return intrabcMotionVectorClass0HpCdfs[checkIndexCompat(
            component,
            intrabcMotionVectorClass0HpCdfs.size
        )
        ]
    }

    /** Returns the live mutable intrabc non-class-0 displacement-vector bit CDF.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @param bitIndex the zero-based displacement-vector class bit index in `[0, 10)`
     * @return the live mutable intrabc non-class-0 displacement-vector bit CDF for the supplied inputs
     */
    fun mutableIntrabcMotionVectorClassNCdf(component: Int, bitIndex: Int): IntArray {
        val tables = intrabcMotionVectorClassNCdfs[checkIndexCompat(
            component,
            intrabcMotionVectorClassNCdfs.size
        )
        ]
        return tables[checkIndexCompat(bitIndex, tables.size)]
    }

    /** Returns the live mutable intrabc non-class-0 fractional displacement-vector CDF.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable intrabc non-class-0 fractional displacement-vector CDF for the supplied component
     */
    fun mutableIntrabcMotionVectorClassNFpCdf(component: Int): IntArray {
        return intrabcMotionVectorClassNFpCdfs[checkIndexCompat(
            component,
            intrabcMotionVectorClassNFpCdfs.size
        )
        ]
    }

    /** Returns the live mutable intrabc non-class-0 high-precision displacement-vector CDF.
     * 
     * @param component the zero-based displacement-vector component index, where `0` is vertical and `1` is horizontal
     * @return the live mutable intrabc non-class-0 high-precision displacement-vector CDF for the supplied component
     */
    fun mutableIntrabcMotionVectorClassNHpCdf(component: Int): IntArray {
        return intrabcMotionVectorClassNHpCdfs[checkIndexCompat(
            component,
            intrabcMotionVectorClassNHpCdfs.size
        )
        ]
    }

    /** Returns the live mutable `intrabc` CDF.
     * 
     * @return the live mutable `intrabc` CDF
     */
    fun mutableIntrabcCdf(): IntArray {
        return intrabcCdf
    }

    /** Returns the live mutable Wiener restoration enable CDF.
     * 
     * @return the live mutable Wiener restoration enable CDF
     */
    fun mutableRestorationWienerCdf(): IntArray {
        return restorationWienerCdf
    }

    /** Returns the live mutable self-guided restoration enable CDF.
     * 
     * @return the live mutable self-guided restoration enable CDF
     */
    fun mutableRestorationSelfGuidedCdf(): IntArray {
        return restorationSelfGuidedCdf
    }

    /** Returns the live mutable switchable restoration CDF.
     * 
     * @return the live mutable switchable restoration CDF
     */
    fun mutableRestorationSwitchableCdf(): IntArray {
        return restorationSwitchableCdf
    }

    /** Returns the live mutable luma intra-mode CDF for the supplied context index.
     * 
     * @param context the zero-based luma intra-mode context index
     * @return the live mutable luma intra-mode CDF for the supplied context index
     */
    fun mutableYModeCdf(context: Int): IntArray {
        return yModeCdfs[checkIndexCompat(context, yModeCdfs.size)]
    }

    /** Returns the live mutable `use_filter_intra` CDF for the supplied block-size index.
     * 
     * @param sizeIndex the zero-based block-size index in `dav1d` `N_BS_SIZES` order
     * @return the live mutable `use_filter_intra` CDF for the supplied block-size index
     */
    fun mutableUseFilterIntraCdf(sizeIndex: Int): IntArray {
        return useFilterIntraCdfs[checkIndexCompat(sizeIndex, useFilterIntraCdfs.size)]
    }

    /** Returns the live mutable filter-intra-mode CDF.
     * 
     * @return the live mutable filter-intra-mode CDF
     */
    fun mutableFilterIntraCdf(): IntArray {
        return filterIntraCdf
    }

    /** Returns the live mutable key-frame luma intra-mode CDF for the supplied above/left mode classes.
     * 
     * @param aboveMode the coarsened above-neighbor mode class
     * @param leftMode the coarsened left-neighbor mode class
     * @return the live mutable key-frame luma intra-mode CDF
     */
    fun mutableKeyFrameYModeCdf(aboveMode: Int, leftMode: Int): IntArray {
        val row = keyFrameYModeCdfs[checkIndexCompat(aboveMode, keyFrameYModeCdfs.size)]
        return row[checkIndexCompat(leftMode, row.size)]
    }

    /** Returns the live mutable chroma intra-mode CDF for the supplied Y-mode context.
     * 
     * @param cflAllowed whether the active frame allows CFL, selecting the larger UV-mode table
     * @param yMode the zero-based luma intra-mode index
     * @return the live mutable chroma intra-mode CDF for the supplied Y-mode context
     */
    fun mutableUvModeCdf(cflAllowed: Boolean, yMode: Int): IntArray {
        val table = uvModeCdfs[if (cflAllowed) 1 else 0]
        return table[checkIndexCompat(yMode, table.size)]
    }

    /** Returns the live mutable partition CDF for the supplied block level and context index.
     * 
     * @param blockLevel the zero-based block-size level
     * @param context the zero-based partition context index
     * @return the live mutable partition CDF for the supplied block level and context index
     */
    fun mutablePartitionCdf(blockLevel: Int, context: Int): IntArray {
        val level = partitionCdfs[checkIndexCompat(blockLevel, partitionCdfs.size)]
        return level[checkIndexCompat(context, level.size)]
    }

    /** Returns the live mutable luma palette-use CDF for the supplied size and palette contexts.
     * 
     * @param sizeContext the zero-based palette size context in `[0, 7)`
     * @param paletteContext the zero-based above/left palette context in `[0, 3)`
     * @return the live mutable luma palette-use CDF for the supplied contexts
     */
    fun mutableLumaPaletteCdf(sizeContext: Int, paletteContext: Int): IntArray {
        val level = lumaPaletteCdfs[checkIndexCompat(sizeContext, lumaPaletteCdfs.size)]
        return level[checkIndexCompat(paletteContext, level.size)]
    }

    /** Returns the live mutable palette-size CDF for the supplied plane and size context.
     * 
     * @param plane the palette plane index, where `0` is luma and `1` is chroma
     * @param sizeContext the zero-based palette size context in `[0, 7)`
     * @return the live mutable palette-size CDF for the supplied plane and size context
     */
    fun mutablePaletteSizeCdf(plane: Int, sizeContext: Int): IntArray {
        val table = paletteSizeCdfs[checkIndexCompat(plane, paletteSizeCdfs.size)]
        return table[checkIndexCompat(sizeContext, table.size)]
    }

    /** Returns the live mutable chroma palette-use CDF for the supplied context index.
     * 
     * @param paletteContext the zero-based chroma palette context in `[0, 2)`
     * @return the live mutable chroma palette-use CDF for the supplied context index
     */
    fun mutableChromaPaletteCdf(paletteContext: Int): IntArray {
        return chromaPaletteCdfs[checkIndexCompat(paletteContext, chromaPaletteCdfs.size)]
    }

    /** Returns the live mutable palette color-map CDF for the supplied plane, palette size, and context.
     * 
     * @param plane the palette plane index, where `0` is luma and `1` is chroma
     * @param paletteSizeIndex the zero-based palette-size-minus-two index in `[0, 7)`
     * @param context the zero-based color-map context in `[0, 5)`
     * @return the live mutable palette color-map CDF for the supplied inputs
     */
    fun mutableColorMapCdf(plane: Int, paletteSizeIndex: Int, context: Int): IntArray {
        val planeTable = colorMapCdfs[checkIndexCompat(plane, colorMapCdfs.size)]
        val sizeTable = planeTable[checkIndexCompat(paletteSizeIndex, planeTable.size)]
        return sizeTable[checkIndexCompat(context, sizeTable.size)]
    }

    /** Returns the live mutable segmentation-prediction CDF for the supplied context index.
     * 
     * @param context the zero-based segmentation-prediction context index in `[0, 3)`
     * @return the live mutable segmentation-prediction CDF for the supplied context index
     */
    fun mutableSegmentPredictionCdf(context: Int): IntArray {
        return segmentPredictionCdfs[checkIndexCompat(context, segmentPredictionCdfs.size)]
    }

    /** Returns the live mutable segment-id CDF for the supplied segment context.
     * 
     * @param context the zero-based segment-id context index in `[0, 3)`
     * @return the live mutable segment-id CDF for the supplied segment context
     */
    fun mutableSegmentIdCdf(context: Int): IntArray {
        return segmentIdCdfs[checkIndexCompat(context, segmentIdCdfs.size)]
    }

    /** Returns the live mutable directional angle-delta CDF for the supplied directional mode index.
     * 
     * @param directionalModeIndex the zero-based directional-mode index in `[0, 8)`
     * @return the live mutable directional angle-delta CDF for the supplied directional mode index
     */
    fun mutableAngleDeltaCdf(directionalModeIndex: Int): IntArray {
        return angleDeltaCdfs[checkIndexCompat(directionalModeIndex, angleDeltaCdfs.size)]
    }

    /** Returns the live mutable CFL-sign CDF.
     * 
     * @return the live mutable CFL-sign CDF
     */
    fun mutableCflSignCdf(): IntArray {
        return cflSignCdf
    }

    /** Returns the live mutable CFL-alpha CDF for the supplied sign context.
     * 
     * @param context the zero-based CFL-alpha context index in `[0, 6)`
     * @return the live mutable CFL-alpha CDF for the supplied sign context
     */
    fun mutableCflAlphaCdf(context: Int): IntArray {
        return cflAlphaCdfs[checkIndexCompat(context, cflAlphaCdfs.size)]
    }

    companion object {
        /** The transformed default skip CDFs. */
        val DEFAULT_SKIP_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(31671),
                intArrayOf(16515),
                intArrayOf(4576)
            )
        )

        /** The transformed default skip-mode CDFs. */
        val DEFAULT_SKIP_MODE_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(32621),
                intArrayOf(20708),
                intArrayOf(8127)
            )
        )

        /** The transformed default intra/inter decision CDFs. */
        val DEFAULT_INTRA_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(806),
                intArrayOf(16662),
                intArrayOf(20186),
                intArrayOf(26538)
            )
        )

        /** The transformed default compound-reference decision CDFs. */
        val DEFAULT_COMPOUND_REFERENCE_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(26828),
                    intArrayOf(24035),
                    intArrayOf(12031),
                    intArrayOf(10640),
                    intArrayOf(2901)
                )
            )

        /** The transformed default compound-direction decision CDFs. */
        val DEFAULT_COMPOUND_DIRECTION_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(1198),
                    intArrayOf(2070),
                    intArrayOf(9166),
                    intArrayOf(7499),
                    intArrayOf(22475)
                )
            )

        /** The transformed default single-reference selection CDFs. */
        val DEFAULT_SINGLE_REFERENCE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(intArrayOf(4897), intArrayOf(16973), intArrayOf(29744)),
                    arrayOf<IntArray>(intArrayOf(1555), intArrayOf(16751), intArrayOf(30279)),
                    arrayOf<IntArray>(intArrayOf(4236), intArrayOf(19647), intArrayOf(31194)),
                    arrayOf<IntArray>(intArrayOf(8650), intArrayOf(24773), intArrayOf(31895)),
                    arrayOf<IntArray>(intArrayOf(904), intArrayOf(11014), intArrayOf(26875)),
                    arrayOf<IntArray>(intArrayOf(1444), intArrayOf(15087), intArrayOf(30304))
                )
            )

        /** The transformed default compound forward-reference selection CDFs. */
        val DEFAULT_COMPOUND_FORWARD_REFERENCE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(intArrayOf(4946), intArrayOf(19891), intArrayOf(30731)),
                    arrayOf<IntArray>(intArrayOf(9468), intArrayOf(22441), intArrayOf(31059)),
                    arrayOf<IntArray>(intArrayOf(1503), intArrayOf(15160), intArrayOf(27544))
                )
            )

        /** The transformed default compound backward-reference selection CDFs. */
        val DEFAULT_COMPOUND_BACKWARD_REFERENCE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(intArrayOf(2235), intArrayOf(17182), intArrayOf(30606)),
                    arrayOf<IntArray>(intArrayOf(1423), intArrayOf(15175), intArrayOf(30489))
                )
            )

        /** The transformed default compound unidirectional-reference selection CDFs. */
        val DEFAULT_COMPOUND_UNIDIRECTIONAL_REFERENCE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(intArrayOf(5284), intArrayOf(23152), intArrayOf(31774)),
                    arrayOf<IntArray>(intArrayOf(3865), intArrayOf(14173), intArrayOf(25120)),
                    arrayOf<IntArray>(intArrayOf(3128), intArrayOf(15270), intArrayOf(26710))
                )
            )

        /** The transformed default single-reference new-motion-vector CDFs. */
        val DEFAULT_SINGLE_INTER_NEWMV_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(24035),
                    intArrayOf(16630),
                    intArrayOf(15339),
                    intArrayOf(8386),
                    intArrayOf(12222),
                    intArrayOf(4676)
                )
            )

        /** The transformed default single-reference global-motion CDFs. */
        val DEFAULT_SINGLE_INTER_GLOBALMV_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(2175),
                    intArrayOf(1054)
                )
            )

        /** The transformed default single-reference reference-motion-vector CDFs. */
        val DEFAULT_SINGLE_INTER_REFERENCE_MV_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(23974),
                    intArrayOf(24188),
                    intArrayOf(17848),
                    intArrayOf(28622),
                    intArrayOf(24312),
                    intArrayOf(19923)
                )
            )

        /** The transformed default dynamic-reference-list selection CDFs. */
        val DEFAULT_DRL_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(13104),
                intArrayOf(24560),
                intArrayOf(18945)
            )
        )

        /** The transformed default compound inter-mode CDFs. */
        val DEFAULT_COMPOUND_INTER_MODE_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(7760, 13823, 15808, 17641, 19156, 20666, 26891),
                    intArrayOf(10730, 19452, 21145, 22749, 24039, 25131, 28724),
                    intArrayOf(10664, 20221, 21588, 22906, 24295, 25387, 28436),
                    intArrayOf(13298, 16984, 20471, 24182, 25067, 25736, 26422),
                    intArrayOf(18904, 23325, 25242, 27432, 27898, 28258, 30758),
                    intArrayOf(10725, 17454, 20124, 22820, 24195, 25168, 26046),
                    intArrayOf(17125, 24273, 25814, 27492, 28214, 28704, 30592),
                    intArrayOf(13046, 23214, 24505, 25942, 27435, 28442, 29330)
                )
            )

        /** The transformed default motion-mode CDFs. */
        val DEFAULT_MOTION_MODE_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(10923, 21845),
                intArrayOf(10923, 21845),
                intArrayOf(10923, 21845),
                intArrayOf(7651, 24760),
                intArrayOf(4738, 24765),
                intArrayOf(5391, 25528),
                intArrayOf(19419, 26810),
                intArrayOf(5123, 23606),
                intArrayOf(11606, 24308),
                intArrayOf(26260, 29116),
                intArrayOf(20360, 28062),
                intArrayOf(21679, 26830),
                intArrayOf(29516, 30701),
                intArrayOf(28898, 30397),
                intArrayOf(30878, 31335),
                intArrayOf(32507, 32558),
                intArrayOf(10923, 21845),
                intArrayOf(10923, 21845),
                intArrayOf(28799, 31390),
                intArrayOf(26431, 30774),
                intArrayOf(28973, 31594),
                intArrayOf(29742, 31203)
            )
        )

        /** The transformed default OBMC selection CDFs. */
        val DEFAULT_OBMC_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(16384),
                intArrayOf(16384),
                intArrayOf(16384),
                intArrayOf(10437),
                intArrayOf(9371),
                intArrayOf(9301),
                intArrayOf(17432),
                intArrayOf(14423),
                intArrayOf(15142),
                intArrayOf(25817),
                intArrayOf(22823),
                intArrayOf(22083),
                intArrayOf(30128),
                intArrayOf(31014),
                intArrayOf(31560),
                intArrayOf(32638),
                intArrayOf(16384),
                intArrayOf(16384),
                intArrayOf(23664),
                intArrayOf(20901),
                intArrayOf(24008),
                intArrayOf(26879)
            )
        )

        /** The transformed default joint-compound selection CDFs. */
        val DEFAULT_JOINT_COMPOUND_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(18244),
                    intArrayOf(12865),
                    intArrayOf(7053),
                    intArrayOf(13259),
                    intArrayOf(9334),
                    intArrayOf(4644)
                )
            )

        /** The transformed default masked-compound selection CDFs. */
        val DEFAULT_MASK_COMPOUND_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(26607),
                intArrayOf(22891),
                intArrayOf(18840),
                intArrayOf(24594),
                intArrayOf(19934),
                intArrayOf(22674)
            )
        )

        /** The transformed default wedge-vs-segment compound CDFs. */
        val DEFAULT_WEDGE_COMPOUND_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(23431),
                    intArrayOf(13171),
                    intArrayOf(11470),
                    intArrayOf(9770),
                    intArrayOf(9100),
                    intArrayOf(8233),
                    intArrayOf(6172),
                    intArrayOf(11820),
                    intArrayOf(7701)
                )
            )

        /** The transformed default inter-intra enable CDFs. */
        val DEFAULT_INTER_INTRA_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(16384),
                intArrayOf(26887),
                intArrayOf(27597),
                intArrayOf(30237)
            )
        )

        /** The transformed default inter-intra prediction-mode CDFs. */
        val DEFAULT_INTER_INTRA_MODE_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(8192, 16384, 24576),
                    intArrayOf(1875, 11082, 27332),
                    intArrayOf(2473, 9996, 26388),
                    intArrayOf(4238, 11537, 25926)
                )
            )

        /** The transformed default inter-intra wedge enable CDFs. */
        val DEFAULT_INTER_INTRA_WEDGE_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(20036),
                    intArrayOf(24957),
                    intArrayOf(26704),
                    intArrayOf(27530),
                    intArrayOf(29564),
                    intArrayOf(29444),
                    intArrayOf(26872)
                )
            )

        /** The transformed default wedge-index CDFs. */
        val DEFAULT_WEDGE_INDEX_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(
                    2438,
                    4440,
                    6599,
                    8663,
                    11005,
                    12874,
                    15751,
                    18094,
                    20359,
                    22362,
                    24127,
                    25702,
                    27752,
                    29450,
                    31171
                ),
                intArrayOf(
                    806,
                    3266,
                    6005,
                    6738,
                    7218,
                    7367,
                    7771,
                    14588,
                    16323,
                    17367,
                    18452,
                    19422,
                    22839,
                    26127,
                    29629
                ),
                intArrayOf(
                    2779,
                    3738,
                    4683,
                    7213,
                    7775,
                    8017,
                    8655,
                    14357,
                    17939,
                    21332,
                    24520,
                    27470,
                    29456,
                    30529,
                    31656
                ),
                intArrayOf(
                    1684,
                    3625,
                    5675,
                    7108,
                    9302,
                    11274,
                    14429,
                    17144,
                    19163,
                    20961,
                    22884,
                    24471,
                    26719,
                    28714,
                    30877
                ),
                intArrayOf(
                    1142,
                    3491,
                    6277,
                    7314,
                    8089,
                    8355,
                    9023,
                    13624,
                    15369,
                    16730,
                    18114,
                    19313,
                    22521,
                    26012,
                    29550
                ),
                intArrayOf(
                    2742,
                    4195,
                    5727,
                    8035,
                    8980,
                    9336,
                    10146,
                    14124,
                    17270,
                    20533,
                    23434,
                    25972,
                    27944,
                    29570,
                    31416
                ),
                intArrayOf(
                    1727,
                    3948,
                    6101,
                    7796,
                    9841,
                    12344,
                    15766,
                    18944,
                    20638,
                    22038,
                    23963,
                    25311,
                    26988,
                    28766,
                    31012
                ),
                intArrayOf(
                    154,
                    987,
                    1925,
                    2051,
                    2088,
                    2111,
                    2151,
                    23033,
                    23703,
                    24284,
                    24985,
                    25684,
                    27259,
                    28883,
                    30911
                ),
                intArrayOf(
                    1135,
                    1322,
                    1493,
                    2635,
                    2696,
                    2737,
                    2770,
                    21016,
                    22935,
                    25057,
                    27251,
                    29173,
                    30089,
                    30960,
                    31933
                )
            )
        )

        /** The transformed default switchable interpolation-filter CDFs. */
        val DEFAULT_INTERPOLATION_FILTER_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(31935, 32720),
                        intArrayOf(5568, 32719),
                        intArrayOf(422, 2938),
                        intArrayOf(28244, 32608),
                        intArrayOf(31206, 31953),
                        intArrayOf(4862, 32121),
                        intArrayOf(770, 1152),
                        intArrayOf(20889, 25637)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31910, 32724),
                        intArrayOf(4120, 32712),
                        intArrayOf(305, 2247),
                        intArrayOf(27403, 32636),
                        intArrayOf(31022, 32009),
                        intArrayOf(2963, 32093),
                        intArrayOf(601, 943),
                        intArrayOf(14969, 21398)
                    )
                )
            )

        /** The transformed default transform-size CDFs. */
        val DEFAULT_TRANSFORM_SIZE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(19968),
                        intArrayOf(19968),
                        intArrayOf(24320)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(12272, 30172),
                        intArrayOf(12272, 30172),
                        intArrayOf(18677, 30848)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(12986, 15180),
                        intArrayOf(12986, 15180),
                        intArrayOf(24302, 25602)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(5782, 11475),
                        intArrayOf(5782, 11475),
                        intArrayOf(16803, 22759)
                    )
                )
            )

        /** The transformed default inter transform-partition CDFs. */
        val DEFAULT_TRANSFORM_PARTITION_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(28581),
                    intArrayOf(23846),
                    intArrayOf(20847),
                    intArrayOf(24315),
                    intArrayOf(18196),
                    intArrayOf(12133),
                    intArrayOf(18791),
                    intArrayOf(10887),
                    intArrayOf(11005),
                    intArrayOf(27179),
                    intArrayOf(20004),
                    intArrayOf(11281),
                    intArrayOf(26549),
                    intArrayOf(19308),
                    intArrayOf(14224),
                    intArrayOf(28015),
                    intArrayOf(21546),
                    intArrayOf(14400),
                    intArrayOf(28165),
                    intArrayOf(22401),
                    intArrayOf(16088)
                )
            )

        /** The transformed default inter transform-type CDFs for transform set 1. */
        val DEFAULT_INTER_TRANSFORM_TYPE_SET_1_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(
                        4458,
                        5560,
                        7695,
                        9709,
                        13330,
                        14789,
                        17537,
                        20266,
                        21504,
                        22848,
                        23934,
                        25474,
                        27727,
                        28915,
                        30631
                    ),
                    intArrayOf(
                        1645,
                        2573,
                        4778,
                        5711,
                        7807,
                        8622,
                        10522,
                        15357,
                        17674,
                        20408,
                        22517,
                        25010,
                        27116,
                        28856,
                        30749
                    )
                )
            )

        /** The transformed default inter transform-type CDF for transform set 2. */
        val DEFAULT_INTER_TRANSFORM_TYPE_SET_2_CDF: IntArray =
            inverse(770, 2421, 5225, 12907, 15819, 18927, 21561, 24089, 26595, 28526, 30529)

        /** The transformed default inter transform-type CDFs for the reduced/large transform set. */
        val DEFAULT_INTER_TRANSFORM_TYPE_SET_3_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(16384),
                    intArrayOf(4167),
                    intArrayOf(1998),
                    intArrayOf(748)
                )
            )

        /** The transformed default intra transform-type CDFs for transform set 1. */
        val DEFAULT_INTRA_TRANSFORM_TYPE_SET_1_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(1535, 8035, 9461, 12751, 23467, 27825),
                        intArrayOf(564, 3335, 9709, 10870, 18143, 28094),
                        intArrayOf(672, 3247, 3676, 11982, 19415, 23127),
                        intArrayOf(5279, 13885, 15487, 18044, 23527, 30252),
                        intArrayOf(4423, 6074, 7985, 10416, 25693, 29298),
                        intArrayOf(1486, 4241, 9460, 10662, 16456, 27694),
                        intArrayOf(439, 2838, 3522, 6737, 18058, 23754),
                        intArrayOf(1190, 4233, 4855, 11670, 20281, 24377),
                        intArrayOf(1045, 4312, 8647, 10159, 18644, 29335),
                        intArrayOf(202, 3734, 4747, 7298, 17127, 24016),
                        intArrayOf(447, 4312, 6819, 8884, 16010, 23858),
                        intArrayOf(277, 4369, 5255, 8905, 16465, 22271),
                        intArrayOf(3409, 5436, 10599, 15599, 19687, 24040)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(1870, 13742, 14530, 16498, 23770, 27698),
                        intArrayOf(326, 8796, 14632, 15079, 19272, 27486),
                        intArrayOf(484, 7576, 7712, 14443, 19159, 22591),
                        intArrayOf(1126, 15340, 15895, 17023, 20896, 30279),
                        intArrayOf(655, 4854, 5249, 5913, 22099, 27138),
                        intArrayOf(1299, 6458, 8885, 9290, 14851, 25497),
                        intArrayOf(311, 5295, 5552, 6885, 16107, 22672),
                        intArrayOf(883, 8059, 8270, 11258, 17289, 21549),
                        intArrayOf(741, 7580, 9318, 10345, 16688, 29046),
                        intArrayOf(110, 7406, 7915, 9195, 16041, 23329),
                        intArrayOf(363, 7974, 9357, 10673, 15629, 24474),
                        intArrayOf(153, 7647, 8112, 9936, 15307, 19996),
                        intArrayOf(3511, 6332, 11165, 15335, 19323, 23594)
                    )
                )
            )

        /** The transformed default intra transform-type CDFs for transform set 2. */
        val DEFAULT_INTRA_TRANSFORM_TYPE_SET_2_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214),
                        intArrayOf(6554, 13107, 19661, 26214)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(1127, 12814, 22772, 27483),
                        intArrayOf(145, 6761, 11980, 26667),
                        intArrayOf(362, 5887, 11678, 16725),
                        intArrayOf(385, 15213, 18587, 30693),
                        intArrayOf(25, 2914, 23134, 27903),
                        intArrayOf(60, 4470, 11749, 23991),
                        intArrayOf(37, 3332, 14511, 21448),
                        intArrayOf(157, 6320, 13036, 17439),
                        intArrayOf(119, 6719, 12906, 29396),
                        intArrayOf(47, 5537, 12576, 21499),
                        intArrayOf(269, 6076, 11258, 23115),
                        intArrayOf(83, 5615, 12001, 17228),
                        intArrayOf(1968, 5556, 12023, 18547)
                    )
                )
            )

        /** The transformed default DC-sign CDFs for luma and chroma planes. */
        val DEFAULT_DC_SIGN_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(16000),
                        intArrayOf(13056),
                        intArrayOf(18816)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(15232),
                        intArrayOf(12928),
                        intArrayOf(17280)
                    )
                )
            )


        /** The transformed default delta-q CDF. */
        val DEFAULT_DELTA_Q_CDF: IntArray = inverse(28160, 32120, 32677)

        /** The transformed default delta-lf CDFs. */
        val DEFAULT_DELTA_LF_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(28160, 32120, 32677),
                intArrayOf(28160, 32120, 32677),
                intArrayOf(28160, 32120, 32677),
                intArrayOf(28160, 32120, 32677),
                intArrayOf(28160, 32120, 32677)
            )
        )

        /** The transformed default motion-vector joint CDF. */
        val DEFAULT_MOTION_VECTOR_JOINT_CDF: IntArray =
            inverse(4096, 11264, 19328)

        /** The transformed default motion-vector class CDFs for vertical and horizontal components. */
        val DEFAULT_MOTION_VECTOR_CLASS_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(
                        28672,
                        30976,
                        31858,
                        32320,
                        32551,
                        32656,
                        32740,
                        32757,
                        32762,
                        32767
                    ),
                    intArrayOf(28672, 30976, 31858, 32320, 32551, 32656, 32740, 32757, 32762, 32767)
                )
            )

        /** The transformed default motion-vector sign CDFs for vertical and horizontal components. */
        val DEFAULT_MOTION_VECTOR_SIGN_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(16384),
                    intArrayOf(16384)
                )
            )

        /** The transformed default class-0 motion-vector magnitude CDFs for vertical and horizontal components. */
        val DEFAULT_MOTION_VECTOR_CLASS0_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(27648),
                    intArrayOf(27648)
                )
            )

        /** The transformed default class-0 fractional motion-vector CDFs for vertical and horizontal components. */
        val DEFAULT_MOTION_VECTOR_CLASS0_FP_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(16384, 24576, 26624),
                        intArrayOf(12288, 21248, 24128)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(16384, 24576, 26624),
                        intArrayOf(12288, 21248, 24128)
                    )
                )
            )

        /** The transformed default class-0 high-precision motion-vector CDFs. */
        val DEFAULT_MOTION_VECTOR_CLASS0_HP_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(20480),
                    intArrayOf(20480)
                )
            )

        /** The transformed default non-class-0 motion-vector bit CDFs for vertical and horizontal components. */
        val DEFAULT_MOTION_VECTOR_CLASSN_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(17408),
                        intArrayOf(17920),
                        intArrayOf(18944),
                        intArrayOf(20480),
                        intArrayOf(22528),
                        intArrayOf(24576),
                        intArrayOf(28672),
                        intArrayOf(29952),
                        intArrayOf(29952),
                        intArrayOf(30720)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(17408),
                        intArrayOf(17920),
                        intArrayOf(18944),
                        intArrayOf(20480),
                        intArrayOf(22528),
                        intArrayOf(24576),
                        intArrayOf(28672),
                        intArrayOf(29952),
                        intArrayOf(29952),
                        intArrayOf(30720)
                    )
                )
            )

        /** The transformed default non-class-0 fractional motion-vector CDFs. */
        val DEFAULT_MOTION_VECTOR_CLASSN_FP_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(8192, 17408, 21248),
                    intArrayOf(8192, 17408, 21248)
                )
            )

        /** The transformed default non-class-0 high-precision motion-vector CDFs. */
        val DEFAULT_MOTION_VECTOR_CLASSN_HP_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(16384),
                    intArrayOf(16384)
                )
            )

        /** The transformed default `intrabc` CDF. */
        val DEFAULT_INTRABC_CDF: IntArray = inverse(30531)

        /** The transformed default Wiener restoration enable CDF. */
        val DEFAULT_RESTORATION_WIENER_CDF: IntArray = inverse(11570)

        /** The transformed default self-guided restoration enable CDF. */
        val DEFAULT_RESTORATION_SELF_GUIDED_CDF: IntArray = inverse(16855)

        /** The transformed default switchable restoration CDF. */
        val DEFAULT_RESTORATION_SWITCHABLE_CDF: IntArray =
            inverse(9413, 22581)

        /** The transformed default luma intra-mode CDFs. */
        val DEFAULT_Y_MODE_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(
                    22801,
                    23489,
                    24293,
                    24756,
                    25601,
                    26123,
                    26606,
                    27418,
                    27945,
                    29228,
                    29685,
                    30349
                ),
                intArrayOf(
                    18673,
                    19845,
                    22631,
                    23318,
                    23950,
                    24649,
                    25527,
                    27364,
                    28152,
                    29701,
                    29984,
                    30852
                ),
                intArrayOf(
                    19770,
                    20979,
                    23396,
                    23939,
                    24241,
                    24654,
                    25136,
                    27073,
                    27830,
                    29360,
                    29730,
                    30659
                ),
                intArrayOf(
                    20155,
                    21301,
                    22838,
                    23178,
                    23261,
                    23533,
                    23703,
                    24804,
                    25352,
                    26575,
                    27016,
                    28049
                )
            )
        )

        /** The transformed default `use_filter_intra` CDFs. */
        val DEFAULT_USE_FILTER_INTRA_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(4621),
                    intArrayOf(6743),
                    intArrayOf(5893),
                    intArrayOf(7866),
                    intArrayOf(12551),
                    intArrayOf(9394),
                    intArrayOf(12408),
                    intArrayOf(14301),
                    intArrayOf(12756),
                    intArrayOf(22343),
                    intArrayOf(16384),
                    intArrayOf(16384),
                    intArrayOf(16384),
                    intArrayOf(16384),
                    intArrayOf(16384),
                    intArrayOf(16384),
                    intArrayOf(12770),
                    intArrayOf(10368),
                    intArrayOf(20229),
                    intArrayOf(18101),
                    intArrayOf(16384),
                    intArrayOf(16384)
                )
            )

        /** The transformed default filter-intra-mode CDF. */
        val DEFAULT_FILTER_INTRA_CDF: IntArray =
            inverse(8949, 12776, 17211, 29558)

        /** The transformed default chroma intra-mode CDFs. */
        val DEFAULT_UV_MODE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(
                            22631,
                            24152,
                            25378,
                            25661,
                            25986,
                            26520,
                            27055,
                            27923,
                            28244,
                            30059,
                            30941,
                            31961
                        ),
                        intArrayOf(
                            9513,
                            26881,
                            26973,
                            27046,
                            27118,
                            27664,
                            27739,
                            27824,
                            28359,
                            29505,
                            29800,
                            31796
                        ),
                        intArrayOf(
                            9845,
                            9915,
                            28663,
                            28704,
                            28757,
                            28780,
                            29198,
                            29822,
                            29854,
                            30764,
                            31777,
                            32029
                        ),
                        intArrayOf(
                            13639,
                            13897,
                            14171,
                            25331,
                            25606,
                            25727,
                            25953,
                            27148,
                            28577,
                            30612,
                            31355,
                            32493
                        ),
                        intArrayOf(
                            9764,
                            9835,
                            9930,
                            9954,
                            25386,
                            27053,
                            27958,
                            28148,
                            28243,
                            31101,
                            31744,
                            32363
                        ),
                        intArrayOf(
                            11825,
                            13589,
                            13677,
                            13720,
                            15048,
                            29213,
                            29301,
                            29458,
                            29711,
                            31161,
                            31441,
                            32550
                        ),
                        intArrayOf(
                            14175,
                            14399,
                            16608,
                            16821,
                            17718,
                            17775,
                            28551,
                            30200,
                            30245,
                            31837,
                            32342,
                            32667
                        ),
                        intArrayOf(
                            12885,
                            13038,
                            14978,
                            15590,
                            15673,
                            15748,
                            16176,
                            29128,
                            29267,
                            30643,
                            31961,
                            32461
                        ),
                        intArrayOf(
                            12026,
                            13661,
                            13874,
                            15305,
                            15490,
                            15726,
                            15995,
                            16273,
                            28443,
                            30388,
                            30767,
                            32416
                        ),
                        intArrayOf(
                            19052,
                            19840,
                            20579,
                            20916,
                            21150,
                            21467,
                            21885,
                            22719,
                            23174,
                            28861,
                            30379,
                            32175
                        ),
                        intArrayOf(
                            18627,
                            19649,
                            20974,
                            21219,
                            21492,
                            21816,
                            22199,
                            23119,
                            23527,
                            27053,
                            31397,
                            32148
                        ),
                        intArrayOf(
                            17026,
                            19004,
                            19997,
                            20339,
                            20586,
                            21103,
                            21349,
                            21907,
                            22482,
                            25896,
                            26541,
                            31819
                        ),
                        intArrayOf(
                            12124,
                            13759,
                            14959,
                            14992,
                            15007,
                            15051,
                            15078,
                            15166,
                            15255,
                            15753,
                            16039,
                            16606
                        )
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(
                            10407,
                            11208,
                            12900,
                            13181,
                            13823,
                            14175,
                            14899,
                            15656,
                            15986,
                            20086,
                            20995,
                            22455,
                            24212
                        ),
                        intArrayOf(
                            4532,
                            19780,
                            20057,
                            20215,
                            20428,
                            21071,
                            21199,
                            21451,
                            22099,
                            24228,
                            24693,
                            27032,
                            29472
                        ),
                        intArrayOf(
                            5273,
                            5379,
                            20177,
                            20270,
                            20385,
                            20439,
                            20949,
                            21695,
                            21774,
                            23138,
                            24256,
                            24703,
                            26679
                        ),
                        intArrayOf(
                            6740,
                            7167,
                            7662,
                            14152,
                            14536,
                            14785,
                            15034,
                            16741,
                            18371,
                            21520,
                            22206,
                            23389,
                            24182
                        ),
                        intArrayOf(
                            4987,
                            5368,
                            5928,
                            6068,
                            19114,
                            20315,
                            21857,
                            22253,
                            22411,
                            24911,
                            25380,
                            26027,
                            26376
                        ),
                        intArrayOf(
                            5370,
                            6889,
                            7247,
                            7393,
                            9498,
                            21114,
                            21402,
                            21753,
                            21981,
                            24780,
                            25386,
                            26517,
                            27176
                        ),
                        intArrayOf(
                            4816,
                            4961,
                            7204,
                            7326,
                            8765,
                            8930,
                            20169,
                            20682,
                            20803,
                            23188,
                            23763,
                            24455,
                            24940
                        ),
                        intArrayOf(
                            6608,
                            6740,
                            8529,
                            9049,
                            9257,
                            9356,
                            9735,
                            18827,
                            19059,
                            22336,
                            23204,
                            23964,
                            24793
                        ),
                        intArrayOf(
                            5998,
                            7419,
                            7781,
                            8933,
                            9255,
                            9549,
                            9753,
                            10417,
                            18898,
                            22494,
                            23139,
                            24764,
                            25989
                        ),
                        intArrayOf(
                            10660,
                            11298,
                            12550,
                            12957,
                            13322,
                            13624,
                            14040,
                            15004,
                            15534,
                            20714,
                            21789,
                            23443,
                            24861
                        ),
                        intArrayOf(
                            10522,
                            11530,
                            12552,
                            12963,
                            13378,
                            13779,
                            14245,
                            15235,
                            15902,
                            20102,
                            22696,
                            23774,
                            25838
                        ),
                        intArrayOf(
                            10099,
                            10691,
                            12639,
                            13049,
                            13386,
                            13665,
                            14125,
                            15163,
                            15636,
                            19676,
                            20474,
                            23519,
                            25208
                        ),
                        intArrayOf(
                            3144,
                            5087,
                            7382,
                            7504,
                            7593,
                            7690,
                            7801,
                            8064,
                            8232,
                            9248,
                            9875,
                            10521,
                            29048
                        )
                    )
                )
            )

        /** The transformed default partition CDFs. */
        val DEFAULT_PARTITION_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(27899, 28219, 28529, 32484, 32539, 32619, 32639),
                        intArrayOf(6607, 6990, 8268, 32060, 32219, 32338, 32371),
                        intArrayOf(5429, 6676, 7122, 32027, 32227, 32531, 32582),
                        intArrayOf(711, 966, 1172, 32448, 32538, 32617, 32664)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(20137, 21547, 23078, 29566, 29837, 30261, 30524, 30892, 31724),
                        intArrayOf(6732, 7490, 9497, 27944, 28250, 28515, 28969, 29630, 30104),
                        intArrayOf(5945, 7663, 8348, 28683, 29117, 29749, 30064, 30298, 32238),
                        intArrayOf(870, 1212, 1487, 31198, 31394, 31574, 31743, 31881, 32332)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(18462, 20920, 23124, 27647, 28227, 29049, 29519, 30178, 31544),
                        intArrayOf(7689, 9060, 12056, 24992, 25660, 26182, 26951, 28041, 29052),
                        intArrayOf(6015, 9009, 10062, 24544, 25409, 26545, 27071, 27526, 32047),
                        intArrayOf(1394, 2208, 2796, 28614, 29061, 29466, 29840, 30185, 31899)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(15597, 20929, 24571, 26706, 27664, 28821, 29601, 30571, 31902),
                        intArrayOf(7925, 11043, 16785, 22470, 23971, 25043, 26651, 28701, 29834),
                        intArrayOf(5414, 13269, 15111, 20488, 22360, 24500, 25537, 26336, 32117),
                        intArrayOf(2662, 6362, 8614, 20860, 23053, 24778, 26436, 27829, 31171)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(19132, 25510, 30392),
                        intArrayOf(13928, 19855, 28540),
                        intArrayOf(12522, 23679, 28629),
                        intArrayOf(9896, 18783, 25853)
                    )
                )
            )

        /** The transformed default luma palette-use CDFs. */
        val DEFAULT_LUMA_PALETTE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(31676),
                        intArrayOf(3419),
                        intArrayOf(1261)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31912),
                        intArrayOf(2859),
                        intArrayOf(980)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31823),
                        intArrayOf(3400),
                        intArrayOf(781)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(32030),
                        intArrayOf(3561),
                        intArrayOf(904)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(32309),
                        intArrayOf(7337),
                        intArrayOf(1462)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(32265),
                        intArrayOf(4015),
                        intArrayOf(1521)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(32450),
                        intArrayOf(7946),
                        intArrayOf(129)
                    )
                )
            )

        /** The transformed default palette-size CDFs for luma and chroma planes. */
        val DEFAULT_PALETTE_SIZE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(7952, 13000, 18149, 21478, 25527, 29241),
                        intArrayOf(7139, 11421, 16195, 19544, 23666, 28073),
                        intArrayOf(7788, 12741, 17325, 20500, 24315, 28530),
                        intArrayOf(8271, 14064, 18246, 21564, 25071, 28533),
                        intArrayOf(12725, 19180, 21863, 24839, 27535, 30120),
                        intArrayOf(9711, 14888, 16923, 21052, 25661, 27875),
                        intArrayOf(14940, 20797, 21678, 24186, 27033, 28999)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(8713, 19979, 27128, 29609, 31331, 32272),
                        intArrayOf(5839, 15573, 23581, 26947, 29848, 31700),
                        intArrayOf(4426, 11260, 17999, 21483, 25863, 29430),
                        intArrayOf(3228, 9464, 14993, 18089, 22523, 27420),
                        intArrayOf(3768, 8886, 13091, 17852, 22495, 27207),
                        intArrayOf(2464, 8451, 12861, 21632, 25525, 28555),
                        intArrayOf(1269, 5435, 10433, 18963, 21700, 25865)
                    )
                )
            )

        /** The transformed default chroma palette-use CDFs. */
        val DEFAULT_CHROMA_PALETTE_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(32461),
                    intArrayOf(21488)
                )
            )

        /** The transformed default palette color-map CDFs for luma and chroma planes. */
        val DEFAULT_COLOR_MAP_CDFS: Array<Array<Array<IntArray>>> =
            inverse4d(
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(28710),
                            intArrayOf(16384),
                            intArrayOf(10553),
                            intArrayOf(27036),
                            intArrayOf(31603)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(27877, 30490),
                            intArrayOf(11532, 25697),
                            intArrayOf(6544, 30234),
                            intArrayOf(23018, 28072),
                            intArrayOf(31915, 32385)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(25572, 28046, 30045),
                            intArrayOf(9478, 21590, 27256),
                            intArrayOf(7248, 26837, 29824),
                            intArrayOf(19167, 24486, 28349),
                            intArrayOf(31400, 31825, 32250)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(24779, 26955, 28576, 30282),
                            intArrayOf(8669, 20364, 24073, 28093),
                            intArrayOf(4255, 27565, 29377, 31067),
                            intArrayOf(19864, 23674, 26716, 29530),
                            intArrayOf(31646, 31893, 32147, 32426)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(23132, 25407, 26970, 28435, 30073),
                            intArrayOf(7443, 17242, 20717, 24762, 27982),
                            intArrayOf(6300, 24862, 26944, 28784, 30671),
                            intArrayOf(18916, 22895, 25267, 27435, 29652),
                            intArrayOf(31270, 31550, 31808, 32059, 32353)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(23105, 25199, 26464, 27684, 28931, 30318),
                            intArrayOf(6950, 15447, 18952, 22681, 25567, 28563),
                            intArrayOf(7560, 23474, 25490, 27203, 28921, 30708),
                            intArrayOf(18544, 22373, 24457, 26195, 28119, 30045),
                            intArrayOf(31198, 31451, 31670, 31882, 32123, 32391)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(21689, 23883, 25163, 26352, 27506, 28827, 30195),
                            intArrayOf(6892, 15385, 17840, 21606, 24287, 26753, 29204),
                            intArrayOf(5651, 23182, 25042, 26518, 27982, 29392, 30900),
                            intArrayOf(19349, 22578, 24418, 25994, 27524, 29031, 30448),
                            intArrayOf(31028, 31270, 31504, 31705, 31927, 32153, 32392)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(29089),
                            intArrayOf(16384),
                            intArrayOf(8713),
                            intArrayOf(29257),
                            intArrayOf(31610)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(25257, 29145),
                            intArrayOf(12287, 27293),
                            intArrayOf(7033, 27960),
                            intArrayOf(20145, 25405),
                            intArrayOf(30608, 31639)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(24210, 27175, 29903),
                            intArrayOf(9888, 22386, 27214),
                            intArrayOf(5901, 26053, 29293),
                            intArrayOf(18318, 22152, 28333),
                            intArrayOf(30459, 31136, 31926)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(22980, 25479, 27781, 29986),
                            intArrayOf(8413, 21408, 24859, 28874),
                            intArrayOf(2257, 29449, 30594, 31598),
                            intArrayOf(19189, 21202, 25915, 28620),
                            intArrayOf(31844, 32044, 32281, 32518)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(22217, 24567, 26637, 28683, 30548),
                            intArrayOf(7307, 16406, 19636, 24632, 28424),
                            intArrayOf(4441, 25064, 26879, 28942, 30919),
                            intArrayOf(17210, 20528, 23319, 26750, 29582),
                            intArrayOf(30674, 30953, 31396, 31735, 32207)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(21239, 23168, 25044, 26962, 28705, 30506),
                            intArrayOf(6545, 15012, 18004, 21817, 25503, 28701),
                            intArrayOf(3448, 26295, 27437, 28704, 30126, 31442),
                            intArrayOf(15889, 18323, 21704, 24698, 26976, 29690),
                            intArrayOf(30988, 31204, 31479, 31734, 31983, 32325)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(21442, 23288, 24758, 26246, 27649, 28980, 30563),
                            intArrayOf(5863, 14933, 17552, 20668, 23683, 26411, 29273),
                            intArrayOf(3415, 25810, 26877, 27990, 29223, 30394, 31618),
                            intArrayOf(17965, 20084, 22232, 23974, 26274, 28402, 30390),
                            intArrayOf(31190, 31329, 31516, 31679, 31825, 32026, 32322)
                        )
                    )
                )
            )

        /** The transformed default segmentation-prediction CDFs. */
        val DEFAULT_SEGMENT_PREDICTION_CDFS: Array<IntArray> =
            inverse2d(
                arrayOf<IntArray>(
                    intArrayOf(16384),
                    intArrayOf(16384),
                    intArrayOf(16384)
                )
            )

        /** The transformed default segment-id CDFs. */
        val DEFAULT_SEGMENT_ID_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(5622, 7893, 16093, 18233, 27809, 28373, 32533),
                intArrayOf(14274, 18230, 22557, 24935, 29980, 30851, 32344),
                intArrayOf(27527, 28487, 28723, 28890, 32397, 32647, 32679)
            )
        )

        /** The transformed default key-frame luma intra-mode CDFs. */
        val DEFAULT_KEY_FRAME_Y_MODE_CDFS: Array<Array<IntArray>> =
            inverse3d(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(
                            15588,
                            17027,
                            19338,
                            20218,
                            20682,
                            21110,
                            21825,
                            23244,
                            24189,
                            28165,
                            29093,
                            30466
                        ),
                        intArrayOf(
                            12016,
                            18066,
                            19516,
                            20303,
                            20719,
                            21444,
                            21888,
                            23032,
                            24434,
                            28658,
                            30172,
                            31409
                        ),
                        intArrayOf(
                            10052,
                            10771,
                            22296,
                            22788,
                            23055,
                            23239,
                            24133,
                            25620,
                            26160,
                            29336,
                            29929,
                            31567
                        ),
                        intArrayOf(
                            14091,
                            15406,
                            16442,
                            18808,
                            19136,
                            19546,
                            19998,
                            22096,
                            24746,
                            29585,
                            30958,
                            32462
                        ),
                        intArrayOf(
                            12122,
                            13265,
                            15603,
                            16501,
                            18609,
                            20033,
                            22391,
                            25583,
                            26437,
                            30261,
                            31073,
                            32475
                        )
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(
                            10023,
                            19585,
                            20848,
                            21440,
                            21832,
                            22760,
                            23089,
                            24023,
                            25381,
                            29014,
                            30482,
                            31436
                        ),
                        intArrayOf(
                            5983,
                            24099,
                            24560,
                            24886,
                            25066,
                            25795,
                            25913,
                            26423,
                            27610,
                            29905,
                            31276,
                            31794
                        ),
                        intArrayOf(
                            7444,
                            12781,
                            20177,
                            20728,
                            21077,
                            21607,
                            22170,
                            23405,
                            24469,
                            27915,
                            29090,
                            30492
                        ),
                        intArrayOf(
                            8537,
                            14689,
                            15432,
                            17087,
                            17408,
                            18172,
                            18408,
                            19825,
                            24649,
                            29153,
                            31096,
                            32210
                        ),
                        intArrayOf(
                            7543,
                            14231,
                            15496,
                            16195,
                            17905,
                            20717,
                            21984,
                            24516,
                            26001,
                            29675,
                            30981,
                            31994
                        )
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(
                            12613,
                            13591,
                            21383,
                            22004,
                            22312,
                            22577,
                            23401,
                            25055,
                            25729,
                            29538,
                            30305,
                            32077
                        ),
                        intArrayOf(
                            9687,
                            13470,
                            18506,
                            19230,
                            19604,
                            20147,
                            20695,
                            22062,
                            23219,
                            27743,
                            29211,
                            30907
                        ),
                        intArrayOf(
                            6183,
                            6505,
                            26024,
                            26252,
                            26366,
                            26434,
                            27082,
                            28354,
                            28555,
                            30467,
                            30794,
                            32086
                        ),
                        intArrayOf(
                            10718,
                            11734,
                            14954,
                            17224,
                            17565,
                            17924,
                            18561,
                            21523,
                            23878,
                            28975,
                            30287,
                            32252
                        ),
                        intArrayOf(
                            9194,
                            9858,
                            16501,
                            17263,
                            18424,
                            19171,
                            21563,
                            25961,
                            26561,
                            30072,
                            30737,
                            32463
                        )
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(
                            12602,
                            14399,
                            15488,
                            18381,
                            18778,
                            19315,
                            19724,
                            21419,
                            25060,
                            29696,
                            30917,
                            32409
                        ),
                        intArrayOf(
                            8203,
                            13821,
                            14524,
                            17105,
                            17439,
                            18131,
                            18404,
                            19468,
                            25225,
                            29485,
                            31158,
                            32342
                        ),
                        intArrayOf(
                            8451,
                            9731,
                            15004,
                            17643,
                            18012,
                            18425,
                            19070,
                            21538,
                            24605,
                            29118,
                            30078,
                            32018
                        ),
                        intArrayOf(
                            7714,
                            9048,
                            9516,
                            16667,
                            16817,
                            16994,
                            17153,
                            18767,
                            26743,
                            30389,
                            31536,
                            32528
                        ),
                        intArrayOf(
                            8843,
                            10280,
                            11496,
                            15317,
                            16652,
                            17943,
                            19108,
                            22718,
                            25769,
                            29953,
                            30983,
                            32485
                        )
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(
                            12578,
                            13671,
                            15979,
                            16834,
                            19075,
                            20913,
                            22989,
                            25449,
                            26219,
                            30214,
                            31150,
                            32477
                        ),
                        intArrayOf(
                            9563,
                            13626,
                            15080,
                            15892,
                            17756,
                            20863,
                            22207,
                            24236,
                            25380,
                            29653,
                            31143,
                            32277
                        ),
                        intArrayOf(
                            8356,
                            8901,
                            17616,
                            18256,
                            19350,
                            20106,
                            22598,
                            25947,
                            26466,
                            29900,
                            30523,
                            32261
                        ),
                        intArrayOf(
                            10835,
                            11815,
                            13124,
                            16042,
                            17018,
                            18039,
                            18947,
                            22753,
                            24615,
                            29489,
                            30883,
                            32482
                        ),
                        intArrayOf(
                            7618,
                            8288,
                            9859,
                            10509,
                            15386,
                            18657,
                            22903,
                            28776,
                            29180,
                            31355,
                            31802,
                            32593
                        )
                    )
                )
            )

        /** The transformed default directional angle-delta CDFs. */
        val DEFAULT_ANGLE_DELTA_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(2180, 5032, 7567, 22776, 26989, 30217),
                intArrayOf(2301, 5608, 8801, 23487, 26974, 30330),
                intArrayOf(3780, 11018, 13699, 19354, 23083, 31286),
                intArrayOf(4581, 11226, 15147, 17138, 21834, 28397),
                intArrayOf(1737, 10927, 14509, 19588, 22745, 28823),
                intArrayOf(2664, 10176, 12485, 17650, 21600, 30495),
                intArrayOf(2240, 11096, 15453, 20341, 22561, 28917),
                intArrayOf(3605, 10428, 12459, 17676, 21244, 30655)
            )
        )

        /** The transformed default CFL-sign CDF. */
        val DEFAULT_CFL_SIGN_CDF: IntArray =
            inverse(1418, 2123, 13340, 18405, 26972, 28343, 32294)

        /** The transformed default CFL-alpha CDFs. */
        val DEFAULT_CFL_ALPHA_CDFS: Array<IntArray> = inverse2d(
            arrayOf<IntArray>(
                intArrayOf(
                    7637,
                    20719,
                    31401,
                    32481,
                    32657,
                    32688,
                    32692,
                    32696,
                    32700,
                    32704,
                    32708,
                    32712,
                    32716,
                    32720,
                    32724
                ),
                intArrayOf(
                    14365,
                    23603,
                    28135,
                    31168,
                    32167,
                    32395,
                    32487,
                    32573,
                    32620,
                    32647,
                    32668,
                    32672,
                    32676,
                    32680,
                    32684
                ),
                intArrayOf(
                    11532,
                    22380,
                    28445,
                    31360,
                    32349,
                    32523,
                    32584,
                    32649,
                    32673,
                    32677,
                    32681,
                    32685,
                    32689,
                    32693,
                    32697
                ),
                intArrayOf(
                    26990,
                    31402,
                    32282,
                    32571,
                    32692,
                    32696,
                    32700,
                    32704,
                    32708,
                    32712,
                    32716,
                    32720,
                    32724,
                    32728,
                    32732
                ),
                intArrayOf(
                    17248,
                    26058,
                    28904,
                    30608,
                    31305,
                    31877,
                    32126,
                    32321,
                    32394,
                    32464,
                    32516,
                    32560,
                    32576,
                    32593,
                    32622
                ),
                intArrayOf(
                    14738,
                    21678,
                    25779,
                    27901,
                    29024,
                    30302,
                    30980,
                    31843,
                    32144,
                    32413,
                    32520,
                    32594,
                    32622,
                    32656,
                    32660
                )
            )
        )

        /** Creates a mutable CDF context seeded with the AV1 default tables for one base quantizer.
         * 
         * @param baseQIndex the frame base quantizer index in `[0, 256)`
         * @return a mutable CDF context seeded with the AV1 default tables for the supplied base quantizer
         */
        /** Creates a mutable CDF context seeded with the AV1 default tables.
         * 
         * @return a mutable CDF context seeded with the AV1 default tables
         */
        fun createDefault(baseQIndex: Int = 0): CdfContext {
            val qcat = CoefficientCdfDefaults.qcatForBaseQIndex(baseQIndex)
            return CdfContext(
                deepCopy(DEFAULT_SKIP_CDFS),
                deepCopy(DEFAULT_SKIP_MODE_CDFS),
                deepCopy(DEFAULT_INTRA_CDFS),
                deepCopy(DEFAULT_COMPOUND_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_DIRECTION_CDFS),
                deepCopy(DEFAULT_SINGLE_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_FORWARD_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_BACKWARD_REFERENCE_CDFS),
                deepCopy(DEFAULT_COMPOUND_UNIDIRECTIONAL_REFERENCE_CDFS),
                deepCopy(DEFAULT_SINGLE_INTER_NEWMV_CDFS),
                deepCopy(DEFAULT_SINGLE_INTER_GLOBALMV_CDFS),
                deepCopy(DEFAULT_SINGLE_INTER_REFERENCE_MV_CDFS),
                deepCopy(DEFAULT_DRL_CDFS),
                deepCopy(DEFAULT_COMPOUND_INTER_MODE_CDFS),
                deepCopy(DEFAULT_MOTION_MODE_CDFS),
                deepCopy(DEFAULT_OBMC_CDFS),
                deepCopy(DEFAULT_JOINT_COMPOUND_CDFS),
                deepCopy(DEFAULT_MASK_COMPOUND_CDFS),
                deepCopy(DEFAULT_WEDGE_COMPOUND_CDFS),
                deepCopy(DEFAULT_INTER_INTRA_CDFS),
                deepCopy(DEFAULT_INTER_INTRA_MODE_CDFS),
                deepCopy(DEFAULT_INTER_INTRA_WEDGE_CDFS),
                deepCopy(DEFAULT_WEDGE_INDEX_CDFS),
                deepCopy(DEFAULT_INTERPOLATION_FILTER_CDFS),
                deepCopy(DEFAULT_TRANSFORM_SIZE_CDFS),
                deepCopy(DEFAULT_TRANSFORM_PARTITION_CDFS),
                deepCopy(DEFAULT_INTER_TRANSFORM_TYPE_SET_1_CDFS),
                DEFAULT_INTER_TRANSFORM_TYPE_SET_2_CDF.copyOf(),
                deepCopy(DEFAULT_INTER_TRANSFORM_TYPE_SET_3_CDFS),
                deepCopy(DEFAULT_INTRA_TRANSFORM_TYPE_SET_1_CDFS),
                deepCopy(DEFAULT_INTRA_TRANSFORM_TYPE_SET_2_CDFS),
                CoefficientCdfDefaults.coefficientSkipCdfs(qcat),
                CoefficientCdfDefaults.endOfBlockPrefixCdfs(qcat),
                CoefficientCdfDefaults.endOfBlockBaseTokenCdfs(qcat),
                CoefficientCdfDefaults.endOfBlockHighBitCdfs(qcat),
                CoefficientCdfDefaults.baseTokenCdfs(qcat),
                deepCopy(DEFAULT_DC_SIGN_CDFS),
                CoefficientCdfDefaults.highTokenCdfs(qcat),
                DEFAULT_DELTA_Q_CDF.copyOf(),
                deepCopy(DEFAULT_DELTA_LF_CDFS),
                DEFAULT_MOTION_VECTOR_JOINT_CDF.copyOf(),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_SIGN_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_FP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_HP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_FP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_HP_CDFS),
                DEFAULT_MOTION_VECTOR_JOINT_CDF.copyOf(),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_SIGN_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_FP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASS0_HP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_FP_CDFS),
                deepCopy(DEFAULT_MOTION_VECTOR_CLASSN_HP_CDFS),
                DEFAULT_INTRABC_CDF.copyOf(),
                DEFAULT_RESTORATION_WIENER_CDF.copyOf(),
                DEFAULT_RESTORATION_SELF_GUIDED_CDF.copyOf(),
                DEFAULT_RESTORATION_SWITCHABLE_CDF.copyOf(),
                deepCopy(DEFAULT_Y_MODE_CDFS),
                deepCopy(DEFAULT_USE_FILTER_INTRA_CDFS),
                DEFAULT_FILTER_INTRA_CDF.copyOf(),
                deepCopy(DEFAULT_UV_MODE_CDFS),
                deepCopy(DEFAULT_PARTITION_CDFS),
                deepCopy(DEFAULT_LUMA_PALETTE_CDFS),
                deepCopy(DEFAULT_PALETTE_SIZE_CDFS),
                deepCopy(DEFAULT_CHROMA_PALETTE_CDFS),
                deepCopy(DEFAULT_COLOR_MAP_CDFS),
                deepCopy(DEFAULT_SEGMENT_PREDICTION_CDFS),
                deepCopy(DEFAULT_SEGMENT_ID_CDFS),
                deepCopy(DEFAULT_KEY_FRAME_Y_MODE_CDFS),
                deepCopy(DEFAULT_ANGLE_DELTA_CDFS),
                DEFAULT_CFL_SIGN_CDF.copyOf(),
                deepCopy(DEFAULT_CFL_ALPHA_CDFS)
            )
        }

        /** Converts raw `dav1d` CDF thresholds into inverse-ordered AV1 CDF arrays with a zero count slot.
         * 
         * @param rawCdf the raw threshold values copied from `dav1d`
         * @return the transformed inverse-ordered AV1 CDF array
         */
        fun inverse(vararg rawCdf: Int): IntArray {
            val transformed = IntArray(rawCdf.size + 1)
            for (i in rawCdf.indices) {
                transformed[i] = 32768 - rawCdf[i]
            }
            return transformed
        }

        /** Converts a two-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
         * 
         * @param rawCdfs the two-dimensional table of raw threshold values
         * @return the transformed inverse-ordered AV1 CDF arrays
         */
        fun inverse2d(rawCdfs: Array<IntArray>): Array<IntArray> {
            val transformed: Array<IntArray> = arrayOfNullsCompat<IntArray>(rawCdfs.size)
            for (i in rawCdfs.indices) {
                transformed[i] = inverse(*rawCdfs[i])
            }
            return transformed
        }

        /** Converts a three-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
         * 
         * @param rawCdfs the three-dimensional table of raw threshold values
         * @return the transformed inverse-ordered AV1 CDF arrays
         */
        fun inverse3d(rawCdfs: Array<Array<IntArray>>): Array<Array<IntArray>> {
            val transformed: Array<Array<IntArray>> = arrayOfNullsCompat<Array<IntArray>>(rawCdfs.size)
            for (i in rawCdfs.indices) {
                transformed[i] = inverse2d(rawCdfs[i])
            }
            return transformed
        }

        /** Converts a four-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
         * 
         * @param rawCdfs the four-dimensional table of raw threshold values
         * @return the transformed inverse-ordered AV1 CDF arrays
         */
        fun inverse4d(rawCdfs: Array<Array<Array<IntArray>>>): Array<Array<Array<IntArray>>> {
            val transformed: Array<Array<Array<IntArray>>> =
                arrayOfNullsCompat<Array<Array<IntArray>>>(rawCdfs.size)
            for (i in rawCdfs.indices) {
                transformed[i] = inverse3d(rawCdfs[i])
            }
            return transformed
        }

        /** Creates a copy of one CDF and optionally resets its terminal adaptive-symbol counter.
         * 
         * @param source the source CDF to copy
         * @param resetSymbolCounter whether the copied terminal count slot should be set to zero
         * @return a copy of the supplied CDF
         */
        fun copyCdf(source: IntArray, resetSymbolCounter: Boolean): IntArray {
            val copy = source.copyOf()
            if (resetSymbolCounter && copy.size > 0) {
                copy[copy.size - 1] = 0
            }
            return copy
        }

        /** Creates a deep copy of a two-dimensional CDF table and optionally resets all counters.
         * 
         * @param source the source table to copy
         * @param resetSymbolCounters whether each copied terminal count slot should be set to zero
         * @return a deep copy of the supplied table
         */
        /** Creates a deep copy of a two-dimensional integer table.
         * 
         * @param source the source table to copy
         * @return a deep copy of the supplied table
         */
        fun deepCopy(
            source: Array<IntArray>,
            resetSymbolCounters: Boolean = false
        ): Array<IntArray> {
            val copy: Array<IntArray> = arrayOfNullsCompat<IntArray>(source.size)
            for (i in source.indices) {
                copy[i] = copyCdf(source[i], resetSymbolCounters)
            }
            return copy
        }

        /** Creates a deep copy of a three-dimensional CDF table and optionally resets all counters.
         * 
         * @param source the source table to copy
         * @param resetSymbolCounters whether each copied terminal count slot should be set to zero
         * @return a deep copy of the supplied table
         */
        /** Creates a deep copy of a three-dimensional integer table.
         * 
         * @param source the source table to copy
         * @return a deep copy of the supplied table
         */
        fun deepCopy(
            source: Array<Array<IntArray>>,
            resetSymbolCounters: Boolean = false
        ): Array<Array<IntArray>> {
            val copy: Array<Array<IntArray>> = arrayOfNullsCompat<Array<IntArray>>(source.size)
            for (i in source.indices) {
                copy[i] = deepCopy(source[i], resetSymbolCounters)
            }
            return copy
        }

        /** Creates a deep copy of a four-dimensional CDF table and optionally resets all counters.
         * 
         * @param source the source table to copy
         * @param resetSymbolCounters whether each copied terminal count slot should be set to zero
         * @return a deep copy of the supplied table
         */
        /** Creates a deep copy of a four-dimensional integer table.
         * 
         * @param source the source table to copy
         * @return a deep copy of the supplied table
         */
        fun deepCopy(
            source: Array<Array<Array<IntArray>>>,
            resetSymbolCounters: Boolean = false
        ): Array<Array<Array<IntArray>>> {
            val copy: Array<Array<Array<IntArray>>> =
                arrayOfNullsCompat<Array<Array<IntArray>>>(source.size)
            for (i in source.indices) {
                copy[i] = deepCopy(source[i], resetSymbolCounters)
            }
            return copy
        }
    }
}
