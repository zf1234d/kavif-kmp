// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.model.TransformResidualUnit
import org.glavo.avif.internal.av1.model.TransformSize
import org.glavo.avif.internal.av1.model.TransformType


/** Dequantizes AV1 chroma transform coefficients.
 * 
 * Applies the AV1 `8-bit`, `10-bit`, and `12-bit` QTX lookup tables, optional frame-level
 * quantization matrices, transform-size shifts, and coefficient saturation. */
internal object ChromaDequantizer {
    /** Dequantizes one chroma transform residual unit into transform-domain coefficients.
     * 
     * DC and AC both apply the caller-provided plane-specific delta quantizers on top of the
     * block-local `qindex`. All-zero units return a fresh all-zero array of the matching
     * transform area.
     * 
     * @param residualUnit the chroma residual unit to dequantize
     * @param context the block-local dequantization context
     * @return one dequantized transform-domain coefficient block in natural raster order
     */
    fun dequantize(residualUnit: TransformResidualUnit, context: Context): IntArray {
        val nonNullResidualUnit =
            requireNotNullCompat(residualUnit, "residualUnit")
        val dequantizedCoefficients = IntArray(nonNullResidualUnit.coefficientCount())
        dequantize(nonNullResidualUnit, context, dequantizedCoefficients)
        return dequantizedCoefficients
    }

    /** Dequantizes one chroma transform residual unit into caller-owned exact-length storage.
     * 
     * The complete destination array is overwritten. DC and AC use the plane-specific delta
     * quantizers supplied through the context.
     * 
     * @param residualUnit the chroma residual unit to dequantize
     * @param context the block-local dequantization context
     * @param destination the exact-length destination in natural raster order
     */
    fun dequantize(
        residualUnit: TransformResidualUnit,
        context: Context,
        destination: IntArray
    ) {
        val nonNullResidualUnit =
            requireNotNullCompat(residualUnit, "residualUnit")
        val nonNullContext = requireNotNullCompat(context, "context")
        dequantize(
            nonNullResidualUnit,
            nonNullContext.qIndex,
            nonNullContext.dcDelta,
            nonNullContext.acDelta,
            nonNullContext.bitDepth,
            nonNullContext.useQuantizationMatrices,
            nonNullContext.quantizationMatrix,
            destination
        )
    }

    /** Dequantizes only the DC coefficient of one chroma residual unit.
     * 
     * @param residualUnit the chroma residual unit to dequantize
     * @param qIndex the block-local chroma quantizer index in `[0, 255]`
     * @param dcDelta the plane-local DC delta quantizer
     * @param bitDepth the decoded sample bit depth
     * @param useQuantizationMatrices whether frame-level quantization matrices are enabled
     * @param quantizationMatrixIndex the chroma quantization matrix index in `[0, 15]`
     * @return the signed dequantized DC coefficient, or zero for an all-zero unit
     */
    fun dequantizeDcCoefficient(
        residualUnit: TransformResidualUnit,
        qIndex: Int,
        dcDelta: Int,
        bitDepth: Int,
        useQuantizationMatrices: Boolean,
        quantizationMatrixIndex: Int
    ): Int {
        val nonNullResidualUnit =
            requireNotNullCompat(residualUnit, "residualUnit")
        validateParameters(qIndex, bitDepth, quantizationMatrixIndex)

        val quantizationMatrix: ByteArray? = quantizationMatrix(
            nonNullResidualUnit,
            useQuantizationMatrices,
            quantizationMatrixIndex
        )
        return scaledDcCoefficient(
            nonNullResidualUnit,
            qIndex,
            dcDelta,
            QuantizerTables.dequantizationShift(nonNullResidualUnit.size()),
            bitDepth,
            quantizationMatrix
        )
    }

    /** Dequantizes one chroma transform residual unit from scalar plane parameters.
     * 
     * This allocation-free entry point overwrites the complete exact-length destination array.
     * 
     * @param residualUnit the chroma residual unit to dequantize
     * @param qIndex the block-local chroma quantizer index in `[0, 255]`
     * @param dcDelta the plane-local DC delta quantizer
     * @param acDelta the plane-local AC delta quantizer
     * @param bitDepth the decoded sample bit depth
     * @param useQuantizationMatrices whether frame-level quantization matrices are enabled
     * @param quantizationMatrixIndex the chroma quantization matrix index in `[0, 15]`
     * @param destination the exact-length destination in natural raster order
     */
    fun dequantize(
        residualUnit: TransformResidualUnit,
        qIndex: Int,
        dcDelta: Int,
        acDelta: Int,
        bitDepth: Int,
        useQuantizationMatrices: Boolean,
        quantizationMatrixIndex: Int,
        destination: IntArray
    ) {
        val nonNullResidualUnit =
            requireNotNullCompat(residualUnit, "residualUnit")
        val nonNullDestination = requireNotNullCompat(destination, "destination")
        validateParameters(qIndex, bitDepth, quantizationMatrixIndex)

        val coefficientCount = nonNullResidualUnit.coefficientCount()
        require(nonNullDestination.size == coefficientCount) { "destination length does not match transform area" }
        if (nonNullResidualUnit.allZero()) {
            fillCompat(nonNullDestination, 0)
            return
        }

        val dequantizationShift = QuantizerTables.dequantizationShift(nonNullResidualUnit.size())
        val quantizationMatrix: ByteArray? = quantizationMatrix(
            nonNullResidualUnit,
            useQuantizationMatrices,
            quantizationMatrixIndex
        )
        nonNullDestination[0] = scaledDcCoefficient(
            nonNullResidualUnit,
            qIndex,
            dcDelta,
            dequantizationShift,
            bitDepth,
            quantizationMatrix
        )

        val acQuantizer = QuantizerTables.acQuantizer(
            qIndex + acDelta,
            bitDepth
        )
        for (coefficientIndex in 1..<coefficientCount) {
            nonNullDestination[coefficientIndex] = scaledCoefficient(
                nonNullResidualUnit.coefficient(coefficientIndex),
                acQuantizer,
                dequantizationShift,
                bitDepth,
                matrixValue(quantizationMatrix, nonNullResidualUnit.size(), coefficientIndex)
            )
        }
    }

    /** Validates scalar chroma dequantization parameters shared by both entry points.
     * 
     * @param qIndex the block-local chroma quantizer index
     * @param bitDepth the decoded sample bit depth
     * @param quantizationMatrixIndex the chroma quantization matrix index
     */
    private fun validateParameters(qIndex: Int, bitDepth: Int, quantizationMatrixIndex: Int) {
        require(qIndex in 0..255) { "qIndex out of range: $qIndex" }
        check(!(bitDepth != 8 && bitDepth != 10 && bitDepth != 12)) { "Unsupported chroma dequantization bit depth: $bitDepth" }
        require(quantizationMatrixIndex in 0..15) { "quantizationMatrixIndex out of range: $quantizationMatrixIndex" }
    }

    /** Scales one chroma DC coefficient from already validated parameters.
     * 
     * @param residualUnit the chroma residual unit
     * @param qIndex the block-local chroma quantizer index
     * @param dcDelta the plane-local DC delta quantizer
     * @param dequantizationShift the transform-size dequantization shift
     * @param bitDepth the decoded sample bit depth
     * @param quantizationMatrix the active quantization matrix, or `null`
     * @return the signed dequantized DC coefficient
     */
    private fun scaledDcCoefficient(
        residualUnit: TransformResidualUnit,
        qIndex: Int,
        dcDelta: Int,
        dequantizationShift: Int,
        bitDepth: Int,
        quantizationMatrix: ByteArray?
    ): Int {
        return scaledCoefficient(
            residualUnit.coefficient(0),
            QuantizerTables.dcQuantizer(qIndex + dcDelta, bitDepth),
            dequantizationShift,
            bitDepth,
            matrixValue(quantizationMatrix, residualUnit.size(), 0)
        )
    }

    /** Returns the active quantization matrix for one residual unit, or `null` when no matrix applies.
     * 
     * @param residualUnit the residual unit to dequantize
     * @param useQuantizationMatrices whether frame-level quantization matrices are enabled
     * @param quantizationMatrixIndex the chroma quantization matrix index
     * @return the active quantization matrix, or `null` when no matrix applies
     */
    private fun quantizationMatrix(
        residualUnit: TransformResidualUnit,
        useQuantizationMatrices: Boolean,
        quantizationMatrixIndex: Int
    ): ByteArray? {
        if (!useQuantizationMatrices || !usesQuantizationMatrix(residualUnit.transformType())) {
            return null
        }
        return QuantizationMatrixTables.matrix(quantizationMatrixIndex, true, residualUnit.size())
    }

    /** Returns whether one transform type applies frame-level quantization matrices.
     * 
     * @param transformType the transform type to test
     * @return whether the transform type applies frame-level quantization matrices
     */
    private fun usesQuantizationMatrix(transformType: TransformType): Boolean {
        return when (requireNotNullCompat(transformType, "transformType")) {
            TransformType.DCT_DCT, TransformType.ADST_DCT, TransformType.DCT_ADST, TransformType.ADST_ADST, TransformType.FLIPADST_DCT, TransformType.DCT_FLIPADST, TransformType.FLIPADST_FLIPADST, TransformType.ADST_FLIPADST, TransformType.FLIPADST_ADST -> true
            TransformType.IDTX, TransformType.V_DCT, TransformType.H_DCT, TransformType.V_ADST, TransformType.H_ADST, TransformType.V_FLIPADST, TransformType.H_FLIPADST, TransformType.WHT_WHT -> false
        }
    }

    /** Returns the matrix scale at one natural coefficient index.
     * 
     * A neutral scale is returned when no matrix applies or when a 64-wide/high transform index is
     * outside the entropy-coded 32-coefficient span.
     * 
     * @param quantizationMatrix the active quantization matrix, or `null`
     * @param transformSize the active transform size
     * @param coefficientIndex the natural raster coefficient index
     * @return the quantization-matrix scale, or `32` for neutral scaling
     */
    private fun matrixValue(
        quantizationMatrix: ByteArray?,
        transformSize: TransformSize,
        coefficientIndex: Int
    ): Int {
        if (quantizationMatrix == null) {
            return 32
        }
        val transformWidth = transformSize.widthPixels()
        val matrixWidth = QuantizationMatrixTables.matrixWidth(transformSize)
        val matrixHeight = QuantizationMatrixTables.matrixHeight(transformSize)
        val x = coefficientIndex % transformWidth
        val y = coefficientIndex / transformWidth
        if (x >= matrixWidth || y >= matrixHeight) {
            return 32
        }
        return quantizationMatrix[y * matrixWidth + x].toInt() and 0xFF
    }

    /** Multiplies one quantized coefficient by one dequantizer with AV1 coefficient saturation.
     * 
     * @param coefficient the quantized transform coefficient
     * @param quantizer the active dequantizer
     * @param dequantizationShift the transform-size dequantization shift
     * @param bitDepth the decoded sample bit depth
     * @param matrixValue the active quantization-matrix scale, or `32` for neutral scaling
     * @return the scaled transform coefficient
     */
    private fun scaledCoefficient(
        coefficient: Int,
        quantizer: Int,
        dequantizationShift: Int,
        bitDepth: Int,
        matrixValue: Int
    ): Int {
        var magnitude = if (coefficient < 0) -coefficient.toLong() else coefficient.toLong()
        val extendedToken = magnitude >= 15
        if (extendedToken) {
            magnitude = magnitude and 0xFFFFFL
        }
        val effectiveQuantizer = (quantizer * matrixValue + 16) shr 5
        var scaled = effectiveQuantizer * magnitude
        if (extendedToken) {
            scaled = scaled and 0xFFFFFFL
        }
        scaled = scaled shr dequantizationShift
        scaled =
            min(scaled, (coefficientMaximum(bitDepth) + (if (coefficient < 0) 1 else 0)).toLong())
        if (coefficient < 0) {
            scaled = -scaled
        }
        return scaled.toInt()
    }

    /** Returns the maximum positive transform coefficient for one AV1 bit depth.
     * 
     * @param bitDepth the decoded sample bit depth
     * @return the maximum positive transform coefficient
     */
    private fun coefficientMaximum(bitDepth: Int): Int {
        return (128 shl bitDepth) - 1
    }

    /** Block-local chroma dequantization parameters.
     * 
     * The block-local `qindex` already includes any superblock-level delta-q updates. Plane-local
     * DC and AC deltas are carried explicitly so U and V can reuse the same logic with different
     * quantizer adjustments.
     * 
     * @param qIndex the block-local chroma AC quantizer index in `[0, 255]`
     * @param dcDelta the plane-local DC delta quantizer
     * @param acDelta the plane-local AC delta quantizer
     * @param bitDepth the decoded sample bit depth
     * @param useQuantizationMatrices whether frame-level quantization matrices are enabled
     * @param quantizationMatrix the chroma quantization matrix index in `[0, 15]`
     */
    internal data class Context constructor(
        val qIndex: Int,
        val dcDelta: Int,
        val acDelta: Int,
        val bitDepth: Int,
        val useQuantizationMatrices: Boolean = false,
        val quantizationMatrix: Int = 0
    ) {
        init {
            require(qIndex in 0..255) { "qIndex out of range: $qIndex" }
            require(bitDepth > 0) { "bitDepth <= 0: $bitDepth" }
            require(quantizationMatrix in 0..15) { "quantizationMatrix out of range: $quantizationMatrix" }
        }
    }
}
