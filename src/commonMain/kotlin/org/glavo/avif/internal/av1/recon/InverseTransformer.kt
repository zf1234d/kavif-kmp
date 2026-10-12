// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.model.TransformKernel
import org.glavo.avif.internal.av1.model.TransformSize
import org.glavo.avif.internal.av1.model.TransformType


/** Inverse-transform helper with instance-owned storage for the residual reconstruction path.
 * 
 * Staged integer kernels are used for all modeled transform axes. This class exposes one method
 * that reconstructs a residual sample block and one method that adds that block into an already
 * predicted plane.
 * One instance must not execute transform operations concurrently. */
internal class InverseTransformer
/** Creates an inverse transformer with isolated reusable storage and clip state. */
{
    /** Reusable buffers owned by this transformer. */
    private val reconstructionWorkspace = Workspace()

    /** The stage-local clip range active during the current synchronous transform operation. */
    private var activeClipRange: ClipRange = FULL_INT_CLIP_RANGE

    /** Returns the reusable coefficient buffer for one transform size.
     * 
     * The buffer is invalidated by the next residual reconstruction of the same size on this
     * transformer and must not escape the immediate decode operation.
     * 
     * @param transformSize the transform size that determines the exact buffer length
     * @return this transformer's reusable coefficient buffer
     */
    fun coefficientBuffer(transformSize: TransformSize): IntArray {
        return reconstructionWorkspace.coefficientBuffer(transformSize)
    }

    /** Reconstructs one residual sample block from dequantized transform coefficients while using
     * the active frame bit depth to mirror dav1d's stage-local clip ranges.
     * 
     * @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @param transformType the transform type to reconstruct
     * @param bitDepth the decoded sample bit depth
     * @return one signed residual sample block in natural raster order
     */
    /** Reconstructs one residual sample block from dequantized `DCT_DCT` coefficients.
     * 
     * Input and output both use natural raster order. The returned samples are signed residuals
     * that are ready to add to a predictor plane of the same dimensions.
     * 
     * @param dequantizedCoefficients the dequantized `DCT_DCT` coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @return one signed residual sample block in natural raster order
     */
    /** Reconstructs one residual sample block from dequantized transform coefficients.
     * 
     * Input and output both use natural raster order. The returned samples are signed residuals
     * that are ready to add to a predictor plane of the same dimensions.
     * 
     * @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @param transformType the transform type to reconstruct
     * @return one signed residual sample block in natural raster order
     */
    fun reconstructResidualBlock(
        dequantizedCoefficients: IntArray,
        transformSize: TransformSize,
        transformType: TransformType = TransformType.DCT_DCT,
        bitDepth: Int = 8
    ): IntArray {
        return reconstructResidualBlock(
            dequantizedCoefficients,
            transformSize,
            transformType,
            bitDepth,
            false,
            null
        )
    }

    /** Reconstructs one residual block while optionally rejecting nonconformant transform ranges.
     * 
     * @param dequantizedCoefficients the dequantized transform coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @param transformType the transform type to reconstruct
     * @param bitDepth the decoded sample bit depth
     * @param strictStdCompliance whether transform conformance ranges must be enforced
     * @return one signed residual sample block in natural raster order
     */
    fun reconstructResidualBlock(
        dequantizedCoefficients: IntArray,
        transformSize: TransformSize,
        transformType: TransformType,
        bitDepth: Int,
        strictStdCompliance: Boolean
    ): IntArray {
        return reconstructResidualBlock(
            dequantizedCoefficients,
            transformSize,
            transformType,
            bitDepth,
            strictStdCompliance,
            null
        )
    }

    /** Reconstructs one residual block using either isolated result storage or a reusable workspace.
     * 
     * @param dequantizedCoefficients the dequantized coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @param transformType the transform type to reconstruct
     * @param bitDepth the decoded sample bit depth
     * @param strictStdCompliance whether transform conformance ranges must be enforced
     * @param workspace the reusable workspace, or `null` to return independently owned storage
     * @return one signed residual sample block in natural raster order
     */
    private fun reconstructResidualBlock(
        dequantizedCoefficients: IntArray,
        transformSize: TransformSize,
        transformType: TransformType,
        bitDepth: Int,
        strictStdCompliance: Boolean,
        workspace: Workspace?
    ): IntArray {
        val nonNullCoefficients =
            requireNotNullCompat(dequantizedCoefficients, "dequantizedCoefficients")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val nonNullTransformType =
            requireNotNullCompat(transformType, "transformType")
        require(!(bitDepth != 8 && bitDepth != 10 && bitDepth != 12)) { "Unsupported bitDepth: $bitDepth" }
        val transformArea: Int = checkedTransformArea(nonNullTransformSize)
        require(nonNullCoefficients.size == transformArea) { "dequantizedCoefficients length does not match transform area" }

        if (nonNullTransformType == TransformType.WHT_WHT) {
            return reconstructWalshHadamard(
                nonNullCoefficients,
                nonNullTransformSize,
                bitDepth,
                strictStdCompliance,
                workspace
            )
        }

        if (nonNullTransformType != TransformType.DCT_DCT) {
            return reconstructGenericTransform(
                nonNullCoefficients,
                nonNullTransformSize,
                nonNullTransformType,
                bitDepth,
                strictStdCompliance,
                workspace
            )
        }

        return reconstructDctDct(
            nonNullCoefficients,
            nonNullTransformSize,
            bitDepth,
            strictStdCompliance,
            workspace
        )
    }

    /** Reconstructs one residual sample block from dequantized `DCT_DCT` coefficients.
     * 
     * @param coefficients the dequantized `DCT_DCT` coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @param bitDepth the decoded sample bit depth
     * @param strictStdCompliance whether transform conformance ranges must be enforced
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @return one signed residual sample block in natural raster order
     */
    private fun reconstructDctDct(
        coefficients: IntArray,
        transformSize: TransformSize,
        bitDepth: Int,
        strictStdCompliance: Boolean,
        workspace: Workspace?
    ): IntArray {
        val rowClipRange = conformanceClipRange(rowClipRange(bitDepth), strictStdCompliance)
        val columnClipRange = conformanceClipRange(columnClipRange(bitDepth), strictStdCompliance)
        return when (transformSize) {
            TransformSize.TX_4X4 -> reconstructFourByFour(
                coefficients,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.TX_8X8 -> reconstructEightByEight(
                coefficients,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.TX_16X16 -> reconstructSixteenBySixteen(
                coefficients,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_4X8 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                0,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_8X4 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                0,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_4X16 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_16X4 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_8X16 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_16X8 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.TX_32X32 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                2,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.TX_64X64 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                2,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_16X32 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_32X16 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_32X64 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_64X32 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                1,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_8X32 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                2,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_32X8 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                2,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_16X64 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                2,
                rowClipRange,
                columnClipRange,
                workspace
            )

            TransformSize.RTX_64X16 -> reconstructRectangularDctDct(
                coefficients,
                transformSize,
                2,
                rowClipRange,
                columnClipRange,
                workspace
            )
        }
    }

    /** Adds one already reconstructed residual block into the supplied predictor plane.
     * 
     * Sample addition delegates clipping to `MutablePlaneBuffer`, so callers may pass signed
     * residual values directly.
     * 
     * @param plane the destination predictor plane
     * @param x the zero-based destination x coordinate
     * @param y the zero-based destination y coordinate
     * @param transformSize the residual block size
     * @param residualSamples the signed residual sample block in natural raster order
     */
    fun addResidualBlock(
        plane: MutablePlaneBuffer,
        x: Int,
        y: Int,
        transformSize: TransformSize,
        residualSamples: IntArray
    ) {
        addResidualBlock(
            plane,
            x,
            y,
            transformSize,
            transformSize.widthPixels(),
            transformSize.heightPixels(),
            residualSamples
        )
    }

    /** Adds a caller-selected rectangular portion of one reconstructed residual block into the
     * supplied predictor plane.
     * 
     * Sample addition delegates clipping to `MutablePlaneBuffer`, so callers may pass signed
     * residual values directly.
     * 
     * @param plane the destination predictor plane
     * @param x the zero-based destination x coordinate
     * @param y the zero-based destination y coordinate
     * @param transformSize the coded residual block size
     * @param writtenWidthPixels the residual width to write in pixels
     * @param writtenHeightPixels the residual height to write in pixels
     * @param residualSamples the signed residual sample block in natural raster order
     */
    fun addResidualBlock(
        plane: MutablePlaneBuffer,
        x: Int,
        y: Int,
        transformSize: TransformSize,
        writtenWidthPixels: Int,
        writtenHeightPixels: Int,
        residualSamples: IntArray
    ) {
        val nonNullPlane = requireNotNullCompat(plane, "plane")
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        val nonNullResidualSamples =
            requireNotNullCompat(residualSamples, "residualSamples")
        val transformArea: Int = checkedTransformArea(nonNullTransformSize)
        require(nonNullResidualSamples.size == transformArea) { "residualSamples length does not match transform area" }
        require(!(writtenWidthPixels <= 0 || writtenWidthPixels > nonNullTransformSize.widthPixels())) { "writtenWidthPixels out of range: $writtenWidthPixels" }
        require(!(writtenHeightPixels <= 0 || writtenHeightPixels > nonNullTransformSize.heightPixels())) { "writtenHeightPixels out of range: $writtenHeightPixels" }

        val transformWidth = nonNullTransformSize.widthPixels()
        for (row in 0..<writtenHeightPixels) {
            for (column in 0..<writtenWidthPixels) {
                val sampleIndex = row * transformWidth + column
                val predicted = nonNullPlane.sample(x + column, y + row)
                nonNullPlane.setSample(
                    x + column,
                    y + row,
                    predicted + nonNullResidualSamples[sampleIndex]
                )
            }
        }
    }

    /** Reconstructs and immediately applies one residual block using reusable instance storage.
     * 
     * The coefficient array must be the buffer returned by [Workspace#coefficientBuffer(TransformSize)]
     * or another exact-length array that is not modified until this method returns.
     * 
     * @param plane the destination predictor plane
     * @param x the zero-based destination x coordinate
     * @param y the zero-based destination y coordinate
     * @param transformSize the coded residual block size
     * @param transformType the transform type to reconstruct
     * @param bitDepth the decoded sample bit depth
     * @param writtenWidthPixels the residual width to write in pixels
     * @param writtenHeightPixels the residual height to write in pixels
     * @param strictStdCompliance whether transform conformance ranges must be enforced
     * @param dequantizedCoefficients the dequantized transform coefficients
     */
    fun reconstructAndAddResidualBlock(
        plane: MutablePlaneBuffer,
        x: Int,
        y: Int,
        transformSize: TransformSize,
        transformType: TransformType,
        bitDepth: Int,
        writtenWidthPixels: Int,
        writtenHeightPixels: Int,
        strictStdCompliance: Boolean,
        dequantizedCoefficients: IntArray
    ) {
        val residualSamples = reconstructResidualBlock(
            dequantizedCoefficients,
            transformSize,
            transformType,
            bitDepth,
            strictStdCompliance,
            reconstructionWorkspace
        )
        addResidualBlock(
            plane,
            x,
            y,
            transformSize,
            writtenWidthPixels,
            writtenHeightPixels,
            residualSamples
        )
    }

    /** Reconstructs and immediately applies one DC-only `DCT_DCT` residual block.
     * 
     * @param plane the destination predictor plane
     * @param x the zero-based destination x coordinate
     * @param y the zero-based destination y coordinate
     * @param transformSize the coded residual block size
     * @param bitDepth the decoded sample bit depth
     * @param writtenWidthPixels the residual width to write in pixels
     * @param writtenHeightPixels the residual height to write in pixels
     * @param strictStdCompliance whether transform conformance ranges must be enforced
     * @param dequantizedDcCoefficient the dequantized DC coefficient
     */
    fun reconstructAndAddDcOnlyDctBlock(
        plane: MutablePlaneBuffer,
        x: Int,
        y: Int,
        transformSize: TransformSize,
        bitDepth: Int,
        writtenWidthPixels: Int,
        writtenHeightPixels: Int,
        strictStdCompliance: Boolean,
        dequantizedDcCoefficient: Int
    ) {
        val nonNullTransformSize =
            requireNotNullCompat(transformSize, "transformSize")
        require(!(writtenWidthPixels <= 0 || writtenWidthPixels > nonNullTransformSize.widthPixels())) { "writtenWidthPixels out of range: $writtenWidthPixels" }
        require(!(writtenHeightPixels <= 0 || writtenHeightPixels > nonNullTransformSize.heightPixels())) { "writtenHeightPixels out of range: $writtenHeightPixels" }
        val residualSample = reconstructDcOnlyDctDct(
            dequantizedDcCoefficient,
            nonNullTransformSize,
            bitDepth,
            strictStdCompliance
        )
        requireNotNullCompat(plane, "plane").addConstantBlock(
            x, y, writtenWidthPixels, writtenHeightPixels, residualSample
        )
    }

    /** Reconstructs the constant residual sample produced by one DC-only `DCT_DCT` block.
     * 
     * Only one row and one column transform are required because a DCT DC basis has the same
     * value at every output position.
     * 
     * @param dcCoefficient the dequantized DC coefficient
     * @param transformSize the transform size to reconstruct
     * @param bitDepth the decoded sample bit depth
     * @param strictStdCompliance whether transform conformance ranges must be enforced
     * @return the constant signed residual sample
     */
    private fun reconstructDcOnlyDctDct(
        dcCoefficient: Int,
        transformSize: TransformSize,
        bitDepth: Int,
        strictStdCompliance: Boolean
    ): Int {
        val width = transformSize.widthPixels()
        val height = transformSize.heightPixels()
        val scratchLength = max(width, height)
        val input = reconstructionWorkspace.scratchInput(scratchLength)
        val output = reconstructionWorkspace.scratchOutput(scratchLength)
        fillCompat(input, 0, width, 0)
        input[0] = if (width * 2 == height || height * 2 == width)
            positiveRoundShift(dcCoefficient.toLong() * 181, 8)
        else
            dcCoefficient

        val rowClipRange = conformanceClipRange(rowClipRange(bitDepth), strictStdCompliance)
        val columnClipRange = conformanceClipRange(columnClipRange(bitDepth), strictStdCompliance)
        withActiveClipRange(rowClipRange) {
            inverseDctDctAxis(transformSize, input, output, width)
        }
        val columnInput = clipToRange(
            positiveRoundShift(
                output[0].toLong(),
                intermediateTransformShift(transformSize)
            ).toLong(),
            columnClipRange
        )

        fillCompat(input, 0, height, 0)
        input[0] = columnInput
        withActiveClipRange(columnClipRange) {
            inverseDctDctAxis(transformSize, input, output, height)
        }
        return positiveRoundShift(output[0].toLong(), 4)
    }

    /** Applies the DCT kernel used by one axis of the supplied two-dimensional transform size.
     * 
     * @param transformSize the two-dimensional transform size
     * @param input the transform input vector
     * @param output the transform output vector
     * @param length the active vector length
     */
    private fun inverseDctDctAxis(
        transformSize: TransformSize,
        input: IntArray,
        output: IntArray,
        length: Int
    ) {
        when (transformSize) {
            TransformSize.TX_4X4 -> inverseDct4(input, output)
            TransformSize.TX_8X8 -> inverseDct8(input, output)
            TransformSize.TX_16X16 -> inverseDct16(input, output)
            else -> inverseDct(input, output, length)
        }
    }

    /** Reconstructs one `TX_4X4` `DCT_DCT` residual block.
     * 
     * @param coefficients the dequantized `TX_4X4` coefficients in natural raster order
     * @param rowClipRange the row-pass clip range
     * @param columnClipRange the column-pass clip range
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @return one signed `TX_4X4` residual sample block
     */
    private fun reconstructFourByFour(
        coefficients: IntArray,
        rowClipRange: ClipRange,
        columnClipRange: ClipRange,
        workspace: Workspace?
    ): IntArray {
        val buffer = intermediateBuffer(workspace, TransformSize.TX_4X4)
        val output = outputBuffer(workspace, TransformSize.TX_4X4)
        val scratchIn = scratchInput(workspace, 4)
        val scratchOut = scratchOutput(workspace, 4)
        for (row in 0..3) {
            val rowOffset = row shl 2
            scratchIn[0] = coefficients[rowOffset]
            scratchIn[1] = coefficients[rowOffset + 1]
            scratchIn[2] = coefficients[rowOffset + 2]
            scratchIn[3] = coefficients[rowOffset + 3]
            if ((scratchIn[0] or scratchIn[1] or scratchIn[2] or scratchIn[3]) == 0) {
                fillCompat(buffer, rowOffset, rowOffset + 4, 0)
                continue
            }
            withActiveClipRange(rowClipRange) { inverseDct4(scratchIn, scratchOut) }
            buffer[rowOffset] = clipToRange(scratchOut[0].toLong(), columnClipRange)
            buffer[rowOffset + 1] = clipToRange(scratchOut[1].toLong(), columnClipRange)
            buffer[rowOffset + 2] = clipToRange(scratchOut[2].toLong(), columnClipRange)
            buffer[rowOffset + 3] = clipToRange(scratchOut[3].toLong(), columnClipRange)
        }

        for (column in 0..3) {
            scratchIn[0] = buffer[column]
            scratchIn[1] = buffer[4 + column]
            scratchIn[2] = buffer[8 + column]
            scratchIn[3] = buffer[12 + column]
            withActiveClipRange(columnClipRange) { inverseDct4(scratchIn, scratchOut) }
            output[column] = positiveRoundShift(scratchOut[0].toLong(), 4)
            output[4 + column] = positiveRoundShift(scratchOut[1].toLong(), 4)
            output[8 + column] = positiveRoundShift(scratchOut[2].toLong(), 4)
            output[12 + column] = positiveRoundShift(scratchOut[3].toLong(), 4)
        }
        return output
    }

    /** Reconstructs one `TX_8X8` `DCT_DCT` residual block.
     * 
     * @param coefficients the dequantized `TX_8X8` coefficients in natural raster order
     * @param rowClipRange the row-pass clip range
     * @param columnClipRange the column-pass clip range
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @return one signed `TX_8X8` residual sample block
     */
    private fun reconstructEightByEight(
        coefficients: IntArray,
        rowClipRange: ClipRange,
        columnClipRange: ClipRange,
        workspace: Workspace?
    ): IntArray {
        val buffer = intermediateBuffer(workspace, TransformSize.TX_8X8)
        val output = outputBuffer(workspace, TransformSize.TX_8X8)
        val scratchIn = scratchInput(workspace, 8)
        val scratchOut = scratchOutput(workspace, 8)
        for (row in 0..7) {
            val rowOffset = row shl 3
            var rowNonZero = 0
            for (column in 0..7) {
                val coefficient = coefficients[rowOffset + column]
                scratchIn[column] = coefficient
                rowNonZero = rowNonZero or coefficient
            }
            if (rowNonZero == 0) {
                fillCompat(buffer, rowOffset, rowOffset + 8, 0)
                continue
            }
            withActiveClipRange(rowClipRange) { inverseDct8(scratchIn, scratchOut) }
            for (column in 0..7) {
                buffer[rowOffset + column] = clipToRange(
                    positiveRoundShift(scratchOut[column].toLong(), 1).toLong(),
                    columnClipRange
                )
            }
        }

        for (column in 0..7) {
            for (row in 0..7) {
                scratchIn[row] = buffer[(row shl 3) + column]
            }
            withActiveClipRange(columnClipRange) { inverseDct8(scratchIn, scratchOut) }
            for (row in 0..7) {
                output[(row shl 3) + column] = positiveRoundShift(scratchOut[row].toLong(), 4)
            }
        }
        return output
    }

    /** Reconstructs one `TX_16X16` `DCT_DCT` residual block.
     * 
     * This follows the same staged integer transform and scaling schedule used by `dav1d` for the
     * non-rectangular `16x16` inverse transform: one row pass, one intermediate shift by `2`,
     * one column pass, and one final shift by `4`.
     * 
     * @param coefficients the dequantized `TX_16X16` coefficients in natural raster order
     * @param rowClipRange the row-pass clip range
     * @param columnClipRange the column-pass clip range
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @return one signed `TX_16X16` residual sample block
     */
    private fun reconstructSixteenBySixteen(
        coefficients: IntArray,
        rowClipRange: ClipRange,
        columnClipRange: ClipRange,
        workspace: Workspace?
    ): IntArray {
        val buffer = intermediateBuffer(workspace, TransformSize.TX_16X16)
        val output = outputBuffer(workspace, TransformSize.TX_16X16)
        val scratchIn = scratchInput(workspace, 16)
        val scratchOut = scratchOutput(workspace, 16)
        for (row in 0..15) {
            val rowOffset = row shl 4
            var rowNonZero = 0
            for (column in 0..15) {
                val coefficient = coefficients[rowOffset + column]
                scratchIn[column] = coefficient
                rowNonZero = rowNonZero or coefficient
            }
            if (rowNonZero == 0) {
                fillCompat(buffer, rowOffset, rowOffset + 16, 0)
                continue
            }
            withActiveClipRange(rowClipRange) { inverseDct16(scratchIn, scratchOut) }
            for (column in 0..15) {
                buffer[rowOffset + column] = clipToRange(
                    positiveRoundShift(scratchOut[column].toLong(), 2).toLong(),
                    columnClipRange
                )
            }
        }

        for (column in 0..15) {
            for (row in 0..15) {
                scratchIn[row] = buffer[(row shl 4) + column]
            }
            withActiveClipRange(columnClipRange) { inverseDct16(scratchIn, scratchOut) }
            for (row in 0..15) {
                output[(row shl 4) + column] = positiveRoundShift(scratchOut[row].toLong(), 4)
            }
        }
        return output
    }

    /** Reconstructs one supported rectangular `DCT_DCT` residual block.
     * 
     * This follows the same high-level schedule used by `dav1d` for rectangular transforms:
     * apply one pre-scale for `2:1` shapes, run one row transform, apply the size-specific
     * intermediate shift, run one column transform, then finish with the shared `>> 4`
     * post-transform scaling.
     * 
     * @param coefficients the dequantized coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @param intermediateShift the AV1 size-specific intermediate shift applied after the first pass
     * @param rowClipRange the row-pass clip range
     * @param columnClipRange the column-pass clip range
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @return one signed rectangular residual sample block
     */
    private fun reconstructRectangularDctDct(
        coefficients: IntArray,
        transformSize: TransformSize,
        intermediateShift: Int,
        rowClipRange: ClipRange,
        columnClipRange: ClipRange,
        workspace: Workspace?
    ): IntArray {
        val width = transformSize.widthPixels()
        val height = transformSize.heightPixels()
        val buffer = intermediateBuffer(workspace, transformSize)
        val output = outputBuffer(workspace, transformSize)
        val scratchLength = max(width, height)
        val rowScratchIn = scratchInput(workspace, scratchLength)
        val rowScratchOut = scratchOutput(workspace, scratchLength)
        val columnScratchIn = rowScratchIn
        val columnScratchOut = rowScratchOut
        val requiresRectangularPrescale = width * 2 == height || height * 2 == width

        for (row in 0..<height) {
            val rowOffset = row * width
            var rowNonZero = 0
            for (column in 0..<width) {
                val coefficient = coefficients[rowOffset + column]
                val transformInput = if (requiresRectangularPrescale)
                    positiveRoundShift(coefficient.toLong() * 181, 8)
                else
                    coefficient
                rowScratchIn[column] = transformInput
                rowNonZero = rowNonZero or transformInput
            }
            if (rowNonZero == 0) {
                fillCompat(buffer, rowOffset, rowOffset + width, 0)
                continue
            }
            withActiveClipRange(rowClipRange) {
                inverseDct(rowScratchIn, rowScratchOut, width)
            }
            for (column in 0..<width) {
                buffer[rowOffset + column] = clipToRange(
                    positiveRoundShift(rowScratchOut[column].toLong(), intermediateShift).toLong(),
                    columnClipRange
                )
            }
        }

        for (column in 0..<width) {
            for (row in 0..<height) {
                columnScratchIn[row] = buffer[row * width + column]
            }
            withActiveClipRange(columnClipRange) {
                inverseDct(columnScratchIn, columnScratchOut, height)
            }
            for (row in 0..<height) {
                output[row * width + column] = positiveRoundShift(columnScratchOut[row].toLong(), 4)
            }
        }
        return output
    }

    /** Reconstructs one non-`DCT_DCT` residual block through staged one-dimensional kernels.
     * 
     * @param coefficients the dequantized coefficients in natural raster order
     * @param transformSize the transform size to reconstruct
     * @param transformType the explicit transform type to reconstruct
     * @param bitDepth the decoded sample bit depth
     * @param strictStdCompliance whether transform conformance ranges must be enforced
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @return one signed residual sample block in natural raster order
     */
    private fun reconstructGenericTransform(
        coefficients: IntArray,
        transformSize: TransformSize,
        transformType: TransformType,
        bitDepth: Int,
        strictStdCompliance: Boolean,
        workspace: Workspace?
    ): IntArray {
        val width = transformSize.widthPixels()
        val height = transformSize.heightPixels()
        val intermediateShift = intermediateTransformShift(transformSize)
        val requiresRectangularPrescale = width * 2 == height || height * 2 == width
        val rowClipRange = conformanceClipRange(rowClipRange(bitDepth), strictStdCompliance)
        val columnClipRange = conformanceClipRange(columnClipRange(bitDepth), strictStdCompliance)
        val buffer = intermediateBuffer(workspace, transformSize)
        val output = outputBuffer(workspace, transformSize)
        val scratchLength = max(width, height)
        val rowScratchIn = scratchInput(workspace, scratchLength)
        val rowScratchOut = scratchOutput(workspace, scratchLength)
        val columnScratchIn = rowScratchIn
        val columnScratchOut = rowScratchOut

        for (row in 0..<height) {
            val rowOffset = row * width
            var rowNonZero = 0
            for (column in 0..<width) {
                val coefficient = coefficients[rowOffset + column]
                val transformInput = if (requiresRectangularPrescale)
                    positiveRoundShift(coefficient.toLong() * 181, 8)
                else
                    coefficient
                rowScratchIn[column] = transformInput
                rowNonZero = rowNonZero or transformInput
            }
            if (rowNonZero == 0) {
                fillCompat(buffer, rowOffset, rowOffset + width, 0)
                continue
            }
            withActiveClipRange(rowClipRange) {
                inverseKernel(
                    transformType.horizontalKernel(),
                    rowScratchIn,
                    rowScratchOut,
                    width
                )
            }
            for (column in 0..<width) {
                buffer[rowOffset + column] = clipToRange(
                    positiveRoundShift(rowScratchOut[column].toLong(), intermediateShift).toLong(),
                    columnClipRange
                )
            }
        }

        for (column in 0..<width) {
            for (row in 0..<height) {
                columnScratchIn[row] = buffer[row * width + column]
            }
            withActiveClipRange(columnClipRange) {
                inverseKernel(
                    transformType.verticalKernel(),
                    columnScratchIn,
                    columnScratchOut,
                    height
                )
            }
            for (row in 0..<height) {
                output[row * width + column] = positiveRoundShift(columnScratchOut[row].toLong(), 4)
            }
        }
        return output
    }

    /** Reconstructs one AV1 lossless `WHT_WHT` residual block.
     * 
     * AV1 lossless blocks always use a coded `TX_4X4` transform. The initial `>> 2` matches the
     * scalar `dav1d` path; the input is already converted into this decoder's natural row-major
     * coefficient order before reaching the inverse transform.
     * 
     * @param coefficients the dequantized lossless coefficients in natural raster order
     * @param transformSize the active transform size
     * @param bitDepth the decoded sample bit depth
     * @param strictStdCompliance whether lossless residual ranges must be enforced
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @return one signed `TX_4X4` residual sample block
     */
    private fun reconstructWalshHadamard(
        coefficients: IntArray,
        transformSize: TransformSize,
        bitDepth: Int,
        strictStdCompliance: Boolean,
        workspace: Workspace?
    ): IntArray {
        check(transformSize == TransformSize.TX_4X4) { "WHT_WHT is only valid for TX_4X4 lossless blocks" }

        val tmp = intermediateBuffer(workspace, TransformSize.TX_4X4)
        val output = outputBuffer(workspace, TransformSize.TX_4X4)
        val scratch = scratchInput(workspace, 4)
        for (y in 0..3) {
            val rowOffset = y shl 2
            for (x in 0..3) {
                scratch[x] = coefficients[rowOffset + x] shr 2
            }
            inverseWalshHadamard4(scratch)
            for (x in 0..3) {
                tmp[(y shl 2) + x] = scratch[x]
            }
        }

        for (x in 0..3) {
            for (y in 0..3) {
                scratch[y] = tmp[(y shl 2) + x]
            }
            inverseWalshHadamard4(scratch)
            for (y in 0..3) {
                output[(y shl 2) + x] = scratch[y]
            }
        }
        if (strictStdCompliance) {
            validateLosslessResidualRange(output, bitDepth)
        }
        return output
    }

    /** Validates that lossless residual samples fit a signed `1 + BitDepth`-bit value.
     * 
     * @param residualSamples the reconstructed lossless residual samples
     * @param bitDepth the decoded sample bit depth
     */
    private fun validateLosslessResidualRange(residualSamples: IntArray, bitDepth: Int) {
        val minimum = -(1 shl bitDepth)
        val maximum = (1 shl bitDepth) - 1
        for (residualSample in residualSamples) {
            if (residualSample < minimum || residualSample > maximum) {
                throw InvalidFrameReconstructionException(
                    "Lossless residual does not fit a signed " + (bitDepth + 1) + "-bit value"
                )
            }
        }
    }

    /** Returns the intermediate transform buffer for one isolated or reusable reconstruction.
     * 
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @param transformSize the transform size that determines the buffer length
     * @return the intermediate transform buffer
     */
    private fun intermediateBuffer(workspace: Workspace?, transformSize: TransformSize): IntArray {
        return if (workspace == null)
            IntArray(checkedTransformArea(transformSize))
        else
            workspace.intermediateBuffer(transformSize)
    }

    /** Returns the output buffer for one isolated or reusable reconstruction.
     * 
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @param transformSize the transform size that determines the buffer length
     * @return the residual output buffer
     */
    private fun outputBuffer(workspace: Workspace?, transformSize: TransformSize): IntArray {
        return if (workspace == null)
            IntArray(checkedTransformArea(transformSize))
        else
            workspace.outputBuffer(transformSize)
    }

    /** Returns one input scratch vector with at least the requested length.
     * 
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @param length the required vector length
     * @return the input scratch vector
     */
    private fun scratchInput(workspace: Workspace?, length: Int): IntArray {
        return if (workspace == null) IntArray(length) else workspace.scratchInput(length)
    }

    /** Returns one output scratch vector with at least the requested length.
     * 
     * @param workspace the reusable workspace, or `null` for isolated storage
     * @param length the required vector length
     * @return the output scratch vector
     */
    private fun scratchOutput(workspace: Workspace?, length: Int): IntArray {
        return if (workspace == null) IntArray(length) else workspace.scratchOutput(length)
    }

    /** Applies one in-place AV1 inverse Walsh-Hadamard transform to four samples.
     * 
     * @param values the four-sample vector to transform in place
     */
    private fun inverseWalshHadamard4(values: IntArray) {
        val in0 = values[0]
        val in1 = values[1]
        val in2 = values[2]
        val in3 = values[3]

        val t0 = in0 + in1
        val t2 = in2 - in3
        val t4 = (t0 - t2) shr 1
        val t3 = t4 - in3
        val t1 = t4 - in1

        values[0] = t0 - t3
        values[1] = t3
        values[2] = t1
        values[3] = t2 + t1
    }

    /** Reconstructs one supported one-dimensional inverse DCT vector.
     * 
     * @param input the dequantized input vector
     * @param output the reconstructed output vector
     * @param length the vector length in samples
     */
    private fun inverseDct(input: IntArray, output: IntArray, length: Int) {
        when (length) {
            4 -> inverseDct4Dav1d(input, output)
            8 -> inverseDct8Dav1d(input, output)
            16 -> inverseDct16Dav1d(input, output)
            32 -> inverseDct32Dav1d(input, output)
            64 -> inverseDct64Aom(input, output)
            else -> throw IllegalStateException("Unsupported inverse DCT length: $length")
        }
    }

    /** Reconstructs one supported one-dimensional inverse transform vector.
     * 
     * @param kernel the one-dimensional kernel to apply
     * @param input the dequantized input vector
     * @param output the reconstructed output vector
     * @param length the vector length in samples
     */
    private fun inverseKernel(
        kernel: TransformKernel,
        input: IntArray,
        output: IntArray,
        length: Int
    ) {
        when (kernel) {
            TransformKernel.DCT -> inverseDct(input, output, length)
            TransformKernel.ADST -> inverseAdst(input, output, length)
            TransformKernel.FLIPADST -> inverseFlipAdst(input, output, length)
            TransformKernel.IDENTITY -> inverseIdentity(input, output, length)
            TransformKernel.WHT -> throw IllegalStateException("WHT uses the dedicated lossless transform path")
        }
    }

    /** Returns the size-specific intermediate inverse-transform shift used between the two 1-D
     * transform passes.
     * 
     * @param transformSize the transform size
     * @return the intermediate shift in bits
     */
    private fun intermediateTransformShift(transformSize: TransformSize): Int {
        return when (transformSize) {
            TransformSize.TX_4X4, TransformSize.RTX_4X8, TransformSize.RTX_8X4 -> 0
            TransformSize.RTX_4X16, TransformSize.TX_8X8, TransformSize.RTX_8X16, TransformSize.RTX_16X4, TransformSize.RTX_16X8, TransformSize.RTX_16X32, TransformSize.RTX_32X16, TransformSize.RTX_32X64, TransformSize.RTX_64X32 -> 1
            TransformSize.RTX_8X32, TransformSize.TX_16X16, TransformSize.TX_32X32, TransformSize.TX_64X64, TransformSize.RTX_32X8, TransformSize.RTX_16X64, TransformSize.RTX_64X16 -> 2
        }
    }

    /** Reconstructs one one-dimensional `DCT_4` vector.
     * 
     * This follows the libaom staged integer `av1_idct4` schedule and preserves the same
     * clamp locations between half-butterfly stages.
     * 
     * @param input the dequantized `DCT_4` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct4(input: IntArray, output: IntArray) {
        val input0 = input[0]
        val input1 = input[2]
        val input2 = input[1]
        val input3 = input[3]
        val step0 = halfBtf(COSPI[32], input0, COSPI[32], input1)
        val step1 = halfBtf(COSPI[32], input0, -COSPI[32], input1)
        val step2 = halfBtf(COSPI[48], input2, -COSPI[16], input3)
        val step3 = halfBtf(COSPI[16], input2, COSPI[48], input3)

        output[0] = clip(step0.toLong() + step3)
        output[1] = clip(step1.toLong() + step2)
        output[2] = clip(step1.toLong() - step2)
        output[3] = clip(step0.toLong() - step3)
    }

    /** Reconstructs one one-dimensional `DCT_8` vector.
     * 
     * This follows the libaom staged integer `av1_idct8` schedule and preserves the same
     * clamp locations between half-butterfly stages.
     * 
     * @param input the dequantized `DCT_8` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct8(input: IntArray, output: IntArray) {
        val step = reconstructionWorkspace.kernelStep()

        output[0] = input[0]
        output[1] = input[4]
        output[2] = input[2]
        output[3] = input[6]
        output[4] = input[1]
        output[5] = input[5]
        output[6] = input[3]
        output[7] = input[7]

        step[0] = output[0]
        step[1] = output[1]
        step[2] = output[2]
        step[3] = output[3]
        step[4] = halfBtf(COSPI[56], output[4], -COSPI[8], output[7])
        step[5] = halfBtf(COSPI[24], output[5], -COSPI[40], output[6])
        step[6] = halfBtf(COSPI[40], output[5], COSPI[24], output[6])
        step[7] = halfBtf(COSPI[8], output[4], COSPI[56], output[7])

        output[0] = halfBtf(COSPI[32], step[0], COSPI[32], step[1])
        output[1] = halfBtf(COSPI[32], step[0], -COSPI[32], step[1])
        output[2] = halfBtf(COSPI[48], step[2], -COSPI[16], step[3])
        output[3] = halfBtf(COSPI[16], step[2], COSPI[48], step[3])
        output[4] = clip(step[4].toLong() + step[5])
        output[5] = clip(step[4].toLong() - step[5])
        output[6] = clip(-step[6].toLong() + step[7])
        output[7] = clip(step[6].toLong() + step[7])

        step[0] = clip(output[0].toLong() + output[3])
        step[1] = clip(output[1].toLong() + output[2])
        step[2] = clip(output[1].toLong() - output[2])
        step[3] = clip(output[0].toLong() - output[3])
        step[4] = output[4]
        step[5] = halfBtf(-COSPI[32], output[5], COSPI[32], output[6])
        step[6] = halfBtf(COSPI[32], output[5], COSPI[32], output[6])
        step[7] = output[7]

        output[0] = clip(step[0].toLong() + step[7])
        output[1] = clip(step[1].toLong() + step[6])
        output[2] = clip(step[2].toLong() + step[5])
        output[3] = clip(step[3].toLong() + step[4])
        output[4] = clip(step[3].toLong() - step[4])
        output[5] = clip(step[2].toLong() - step[5])
        output[6] = clip(step[1].toLong() - step[6])
        output[7] = clip(step[0].toLong() - step[7])
    }

    /** Reconstructs one one-dimensional `DCT_16` vector.
     * 
     * This follows the libaom staged integer `av1_idct16` schedule and preserves the same
     * clamp locations between half-butterfly stages.
     * 
     * @param input the dequantized `DCT_16` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct16(input: IntArray, output: IntArray) {
        val step = reconstructionWorkspace.kernelStep()

        output[0] = input[0]
        output[1] = input[8]
        output[2] = input[4]
        output[3] = input[12]
        output[4] = input[2]
        output[5] = input[10]
        output[6] = input[6]
        output[7] = input[14]
        output[8] = input[1]
        output[9] = input[9]
        output[10] = input[5]
        output[11] = input[13]
        output[12] = input[3]
        output[13] = input[11]
        output[14] = input[7]
        output[15] = input[15]

        step[0] = output[0]
        step[1] = output[1]
        step[2] = output[2]
        step[3] = output[3]
        step[4] = output[4]
        step[5] = output[5]
        step[6] = output[6]
        step[7] = output[7]
        step[8] = halfBtf(COSPI[60], output[8], -COSPI[4], output[15])
        step[9] = halfBtf(COSPI[28], output[9], -COSPI[36], output[14])
        step[10] = halfBtf(COSPI[44], output[10], -COSPI[20], output[13])
        step[11] = halfBtf(COSPI[12], output[11], -COSPI[52], output[12])
        step[12] = halfBtf(COSPI[52], output[11], COSPI[12], output[12])
        step[13] = halfBtf(COSPI[20], output[10], COSPI[44], output[13])
        step[14] = halfBtf(COSPI[36], output[9], COSPI[28], output[14])
        step[15] = halfBtf(COSPI[4], output[8], COSPI[60], output[15])

        output[0] = step[0]
        output[1] = step[1]
        output[2] = step[2]
        output[3] = step[3]
        output[4] = halfBtf(COSPI[56], step[4], -COSPI[8], step[7])
        output[5] = halfBtf(COSPI[24], step[5], -COSPI[40], step[6])
        output[6] = halfBtf(COSPI[40], step[5], COSPI[24], step[6])
        output[7] = halfBtf(COSPI[8], step[4], COSPI[56], step[7])
        output[8] = clip(step[8].toLong() + step[9])
        output[9] = clip(step[8].toLong() - step[9])
        output[10] = clip(-step[10].toLong() + step[11])
        output[11] = clip(step[10].toLong() + step[11])
        output[12] = clip(step[12].toLong() + step[13])
        output[13] = clip(step[12].toLong() - step[13])
        output[14] = clip(-step[14].toLong() + step[15])
        output[15] = clip(step[14].toLong() + step[15])

        step[0] = halfBtf(COSPI[32], output[0], COSPI[32], output[1])
        step[1] = halfBtf(COSPI[32], output[0], -COSPI[32], output[1])
        step[2] = halfBtf(COSPI[48], output[2], -COSPI[16], output[3])
        step[3] = halfBtf(COSPI[16], output[2], COSPI[48], output[3])
        step[4] = clip(output[4].toLong() + output[5])
        step[5] = clip(output[4].toLong() - output[5])
        step[6] = clip(-output[6].toLong() + output[7])
        step[7] = clip(output[6].toLong() + output[7])
        step[8] = output[8]
        step[9] = halfBtf(-COSPI[16], output[9], COSPI[48], output[14])
        step[10] = halfBtf(-COSPI[48], output[10], -COSPI[16], output[13])
        step[11] = output[11]
        step[12] = output[12]
        step[13] = halfBtf(-COSPI[16], output[10], COSPI[48], output[13])
        step[14] = halfBtf(COSPI[48], output[9], COSPI[16], output[14])
        step[15] = output[15]

        output[0] = clip(step[0].toLong() + step[3])
        output[1] = clip(step[1].toLong() + step[2])
        output[2] = clip(step[1].toLong() - step[2])
        output[3] = clip(step[0].toLong() - step[3])
        output[4] = step[4]
        output[5] = halfBtf(-COSPI[32], step[5], COSPI[32], step[6])
        output[6] = halfBtf(COSPI[32], step[5], COSPI[32], step[6])
        output[7] = step[7]
        output[8] = clip(step[8].toLong() + step[11])
        output[9] = clip(step[9].toLong() + step[10])
        output[10] = clip(step[9].toLong() - step[10])
        output[11] = clip(step[8].toLong() - step[11])
        output[12] = clip(-step[12].toLong() + step[15])
        output[13] = clip(-step[13].toLong() + step[14])
        output[14] = clip(step[13].toLong() + step[14])
        output[15] = clip(step[12].toLong() + step[15])

        step[0] = clip(output[0].toLong() + output[7])
        step[1] = clip(output[1].toLong() + output[6])
        step[2] = clip(output[2].toLong() + output[5])
        step[3] = clip(output[3].toLong() + output[4])
        step[4] = clip(output[3].toLong() - output[4])
        step[5] = clip(output[2].toLong() - output[5])
        step[6] = clip(output[1].toLong() - output[6])
        step[7] = clip(output[0].toLong() - output[7])
        step[8] = output[8]
        step[9] = output[9]
        step[10] = halfBtf(-COSPI[32], output[10], COSPI[32], output[13])
        step[11] = halfBtf(-COSPI[32], output[11], COSPI[32], output[12])
        step[12] = halfBtf(COSPI[32], output[11], COSPI[32], output[12])
        step[13] = halfBtf(COSPI[32], output[10], COSPI[32], output[13])
        step[14] = output[14]
        step[15] = output[15]

        output[0] = clip(step[0].toLong() + step[15])
        output[1] = clip(step[1].toLong() + step[14])
        output[2] = clip(step[2].toLong() + step[13])
        output[3] = clip(step[3].toLong() + step[12])
        output[4] = clip(step[4].toLong() + step[11])
        output[5] = clip(step[5].toLong() + step[10])
        output[6] = clip(step[6].toLong() + step[9])
        output[7] = clip(step[7].toLong() + step[8])
        output[8] = clip(step[7].toLong() - step[8])
        output[9] = clip(step[6].toLong() - step[9])
        output[10] = clip(step[5].toLong() - step[10])
        output[11] = clip(step[4].toLong() - step[11])
        output[12] = clip(step[3].toLong() - step[12])
        output[13] = clip(step[2].toLong() - step[13])
        output[14] = clip(step[1].toLong() - step[14])
        output[15] = clip(step[0].toLong() - step[15])
    }

    /** Reconstructs one one-dimensional `DCT_32` vector using the scalar `dav1d` schedule.
     * 
     * This keeps the same even/odd split and rounding points as `inv_dct32_1d_internal_c` for the
     * non-`tx64` case. Matching that path avoids coefficient-dependent drift in large `DCT_DCT`
     * residuals.
     * 
     * @param input the dequantized `DCT_32` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct32Dav1d(input: IntArray, output: IntArray) {
        val workspace = reconstructionWorkspace
        val evenInput = workspace.evenInput16()
        val evenOutput = workspace.evenOutput16()
        for (i in 0..15) {
            evenInput[i] = input[i shl 1]
        }
        inverseDct16Dav1d(evenInput, evenOutput)

        val in1 = input[1]
        val in3 = input[3]
        val in5 = input[5]
        val in7 = input[7]
        val in9 = input[9]
        val in11 = input[11]
        val in13 = input[13]
        val in15 = input[15]
        val in17 = input[17]
        val in19 = input[19]
        val in21 = input[21]
        val in23 = input[23]
        val in25 = input[25]
        val in27 = input[27]
        val in29 = input[29]
        val in31 = input[31]

        var t16a = positiveRoundShift(in1.toLong() * 201 - in31.toLong() * (4091 - 4096), 12) - in31
        var t17a =
            positiveRoundShift(in17.toLong() * (3035 - 4096) - in15.toLong() * 2751, 12) + in17
        var t18a =
            positiveRoundShift(in9.toLong() * 1751 - in23.toLong() * (3703 - 4096), 12) - in23
        var t19a =
            positiveRoundShift(in25.toLong() * (3857 - 4096) - in7.toLong() * 1380, 12) + in25
        var t20a = positiveRoundShift(in5.toLong() * 995 - in27.toLong() * (3973 - 4096), 12) - in27
        var t21a =
            positiveRoundShift(in21.toLong() * (3513 - 4096) - in11.toLong() * 2106, 12) + in21
        var t22a = positiveRoundShift(in13.toLong() * 1220 - in19.toLong() * 1645, 11)
        var t23a = positiveRoundShift(in29.toLong() * (4052 - 4096) - in3.toLong() * 601, 12) + in29
        var t24a = positiveRoundShift(in29.toLong() * 601 + in3.toLong() * (4052 - 4096), 12) + in3
        var t25a = positiveRoundShift(in13.toLong() * 1645 + in19.toLong() * 1220, 11)
        var t26a =
            positiveRoundShift(in21.toLong() * 2106 + in11.toLong() * (3513 - 4096), 12) + in11
        var t27a = positiveRoundShift(in5.toLong() * (3973 - 4096) + in27.toLong() * 995, 12) + in5
        var t28a = positiveRoundShift(in25.toLong() * 1380 + in7.toLong() * (3857 - 4096), 12) + in7
        var t29a = positiveRoundShift(in9.toLong() * (3703 - 4096) + in23.toLong() * 1751, 12) + in9
        var t30a =
            positiveRoundShift(in17.toLong() * 2751 + in15.toLong() * (3035 - 4096), 12) + in15
        var t31a = positiveRoundShift(in1.toLong() * (4091 - 4096) + in31.toLong() * 201, 12) + in1

        var t16 = clip(t16a.toLong() + t17a)
        var t17 = clip(t16a.toLong() - t17a)
        var t18 = clip(t19a.toLong() - t18a)
        var t19 = clip(t19a.toLong() + t18a)
        var t20 = clip(t20a.toLong() + t21a)
        var t21 = clip(t20a.toLong() - t21a)
        var t22 = clip(t23a.toLong() - t22a)
        var t23 = clip(t23a.toLong() + t22a)
        var t24 = clip(t24a.toLong() + t25a)
        var t25 = clip(t24a.toLong() - t25a)
        var t26 = clip(t27a.toLong() - t26a)
        var t27 = clip(t27a.toLong() + t26a)
        var t28 = clip(t28a.toLong() + t29a)
        var t29 = clip(t28a.toLong() - t29a)
        var t30 = clip(t31a.toLong() - t30a)
        var t31 = clip(t31a.toLong() + t30a)

        t17a = positiveRoundShift(t30.toLong() * 799 - t17.toLong() * (4017 - 4096), 12) - t17
        t30a = positiveRoundShift(t30.toLong() * (4017 - 4096) + t17.toLong() * 799, 12) + t30
        t18a = positiveRoundShift(-(t29.toLong() * (4017 - 4096) + t18.toLong() * 799), 12) - t29
        t29a = positiveRoundShift(t29.toLong() * 799 - t18.toLong() * (4017 - 4096), 12) - t18
        t21a = positiveRoundShift(t26.toLong() * 1703 - t21.toLong() * 1138, 11)
        t26a = positiveRoundShift(t26.toLong() * 1138 + t21.toLong() * 1703, 11)
        t22a = positiveRoundShift(-(t25.toLong() * 1138 + t22.toLong() * 1703), 11)
        t25a = positiveRoundShift(t25.toLong() * 1703 - t22.toLong() * 1138, 11)

        t16a = clip(t16.toLong() + t19)
        t17 = clip(t17a.toLong() + t18a)
        t18 = clip(t17a.toLong() - t18a)
        t19a = clip(t16.toLong() - t19)
        t20a = clip(t23.toLong() - t20)
        t21 = clip(t22a.toLong() - t21a)
        t22 = clip(t22a.toLong() + t21a)
        t23a = clip(t23.toLong() + t20)
        t24a = clip(t24.toLong() + t27)
        t25 = clip(t25a.toLong() + t26a)
        t26 = clip(t25a.toLong() - t26a)
        t27a = clip(t24.toLong() - t27)
        t28a = clip(t31.toLong() - t28)
        t29 = clip(t30a.toLong() - t29a)
        t30 = clip(t30a.toLong() + t29a)
        t31a = clip(t31.toLong() + t28)

        t18a = positiveRoundShift(t29.toLong() * 1567 - t18.toLong() * (3784 - 4096), 12) - t18
        t29a = positiveRoundShift(t29.toLong() * (3784 - 4096) + t18.toLong() * 1567, 12) + t29
        t19 = positiveRoundShift(t28a.toLong() * 1567 - t19a.toLong() * (3784 - 4096), 12) - t19a
        t28 = positiveRoundShift(t28a.toLong() * (3784 - 4096) + t19a.toLong() * 1567, 12) + t28a
        t20 = positiveRoundShift(-(t27a.toLong() * (3784 - 4096) + t20a.toLong() * 1567), 12) - t27a
        t27 = positiveRoundShift(t27a.toLong() * 1567 - t20a.toLong() * (3784 - 4096), 12) - t20a
        t21a = positiveRoundShift(-(t26.toLong() * (3784 - 4096) + t21.toLong() * 1567), 12) - t26
        t26a = positiveRoundShift(t26.toLong() * 1567 - t21.toLong() * (3784 - 4096), 12) - t21

        t16 = clip(t16a.toLong() + t23a)
        t17a = clip(t17.toLong() + t22)
        t18 = clip(t18a.toLong() + t21a)
        t19a = clip(t19.toLong() + t20)
        t20a = clip(t19.toLong() - t20)
        t21 = clip(t18a.toLong() - t21a)
        t22a = clip(t17.toLong() - t22)
        t23 = clip(t16a.toLong() - t23a)
        t24 = clip(t31a.toLong() - t24a)
        t25a = clip(t30.toLong() - t25)
        t26 = clip(t29a.toLong() - t26a)
        t27a = clip(t28.toLong() - t27)
        t28a = clip(t28.toLong() + t27)
        t29 = clip(t29a.toLong() + t26a)
        t30a = clip(t30.toLong() + t25)
        t31 = clip(t31a.toLong() + t24a)

        t20 = positiveRoundShift((t27a - t20a).toLong() * 181, 8)
        t27 = positiveRoundShift((t27a + t20a).toLong() * 181, 8)
        t21a = positiveRoundShift((t26 - t21).toLong() * 181, 8)
        t26a = positiveRoundShift((t26 + t21).toLong() * 181, 8)
        t22 = positiveRoundShift((t25a - t22a).toLong() * 181, 8)
        t25 = positiveRoundShift((t25a + t22a).toLong() * 181, 8)
        t23a = positiveRoundShift((t24 - t23).toLong() * 181, 8)
        t24a = positiveRoundShift((t24 + t23).toLong() * 181, 8)

        output[0] = clip(evenOutput[0].toLong() + t31)
        output[1] = clip(evenOutput[1].toLong() + t30a)
        output[2] = clip(evenOutput[2].toLong() + t29)
        output[3] = clip(evenOutput[3].toLong() + t28a)
        output[4] = clip(evenOutput[4].toLong() + t27)
        output[5] = clip(evenOutput[5].toLong() + t26a)
        output[6] = clip(evenOutput[6].toLong() + t25)
        output[7] = clip(evenOutput[7].toLong() + t24a)
        output[8] = clip(evenOutput[8].toLong() + t23a)
        output[9] = clip(evenOutput[9].toLong() + t22)
        output[10] = clip(evenOutput[10].toLong() + t21a)
        output[11] = clip(evenOutput[11].toLong() + t20)
        output[12] = clip(evenOutput[12].toLong() + t19a)
        output[13] = clip(evenOutput[13].toLong() + t18)
        output[14] = clip(evenOutput[14].toLong() + t17a)
        output[15] = clip(evenOutput[15].toLong() + t16)
        output[16] = clip(evenOutput[15].toLong() - t16)
        output[17] = clip(evenOutput[14].toLong() - t17a)
        output[18] = clip(evenOutput[13].toLong() - t18)
        output[19] = clip(evenOutput[12].toLong() - t19a)
        output[20] = clip(evenOutput[11].toLong() - t20)
        output[21] = clip(evenOutput[10].toLong() - t21a)
        output[22] = clip(evenOutput[9].toLong() - t22)
        output[23] = clip(evenOutput[8].toLong() - t23a)
        output[24] = clip(evenOutput[7].toLong() - t24a)
        output[25] = clip(evenOutput[6].toLong() - t25)
        output[26] = clip(evenOutput[5].toLong() - t26a)
        output[27] = clip(evenOutput[4].toLong() - t27)
        output[28] = clip(evenOutput[3].toLong() - t28a)
        output[29] = clip(evenOutput[2].toLong() - t29)
        output[30] = clip(evenOutput[1].toLong() - t30a)
        output[31] = clip(evenOutput[0].toLong() - t31)
    }

    /** Reconstructs one non-`tx64` one-dimensional `DCT_4` vector using the scalar `dav1d`
     * schedule.
     * 
     * @param input the dequantized `DCT_4` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct4Dav1d(input: IntArray, output: IntArray) {
        val in0 = input[0]
        val in1 = input[1]
        val in2 = input[2]
        val in3 = input[3]

        val t0 = positiveRoundShift((in0 + in2).toLong() * 181, 8)
        val t1 = positiveRoundShift((in0 - in2).toLong() * 181, 8)
        val t2 = positiveRoundShift(in1.toLong() * 1567 - in3.toLong() * (3784 - 4096), 12) - in3
        val t3 = positiveRoundShift(in1.toLong() * (3784 - 4096) + in3.toLong() * 1567, 12) + in1

        output[0] = clip(t0.toLong() + t3)
        output[1] = clip(t1.toLong() + t2)
        output[2] = clip(t1.toLong() - t2)
        output[3] = clip(t0.toLong() - t3)
    }

    /** Reconstructs one non-`tx64` one-dimensional `DCT_8` vector using the scalar `dav1d`
     * schedule.
     * 
     * @param input the dequantized `DCT_8` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct8Dav1d(input: IntArray, output: IntArray) {
        val workspace = reconstructionWorkspace
        val evenInput = workspace.evenInput4()
        val evenOutput = workspace.evenOutput4()
        for (i in 0..3) {
            evenInput[i] = input[i shl 1]
        }
        inverseDct4Dav1d(evenInput, evenOutput)

        val in1 = input[1]
        val in3 = input[3]
        val in5 = input[5]
        val in7 = input[7]

        val t4a = positiveRoundShift(in1.toLong() * 799 - in7.toLong() * (4017 - 4096), 12) - in7
        var t5a = positiveRoundShift(in5.toLong() * 1703 - in3.toLong() * 1138, 11)
        var t6a = positiveRoundShift(in5.toLong() * 1138 + in3.toLong() * 1703, 11)
        val t7a = positiveRoundShift(in1.toLong() * (4017 - 4096) + in7.toLong() * 799, 12) + in1

        val t4 = clip(t4a.toLong() + t5a)
        t5a = clip(t4a.toLong() - t5a)
        val t7 = clip(t7a.toLong() + t6a)
        t6a = clip(t7a.toLong() - t6a)

        val t5 = positiveRoundShift((t6a - t5a).toLong() * 181, 8)
        val t6 = positiveRoundShift((t6a + t5a).toLong() * 181, 8)

        output[0] = clip(evenOutput[0].toLong() + t7)
        output[1] = clip(evenOutput[1].toLong() + t6)
        output[2] = clip(evenOutput[2].toLong() + t5)
        output[3] = clip(evenOutput[3].toLong() + t4)
        output[4] = clip(evenOutput[3].toLong() - t4)
        output[5] = clip(evenOutput[2].toLong() - t5)
        output[6] = clip(evenOutput[1].toLong() - t6)
        output[7] = clip(evenOutput[0].toLong() - t7)
    }

    /** Reconstructs one non-`tx64` one-dimensional `DCT_16` vector using the scalar `dav1d`
     * schedule.
     * 
     * @param input the dequantized `DCT_16` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct16Dav1d(input: IntArray, output: IntArray) {
        val workspace = reconstructionWorkspace
        val evenInput = workspace.evenInput8()
        val evenOutput = workspace.evenOutput8()
        for (i in 0..7) {
            evenInput[i] = input[i shl 1]
        }
        inverseDct8Dav1d(evenInput, evenOutput)

        val in1 = input[1]
        val in3 = input[3]
        val in5 = input[5]
        val in7 = input[7]
        val in9 = input[9]
        val in11 = input[11]
        val in13 = input[13]
        val in15 = input[15]

        var t8a = positiveRoundShift(in1.toLong() * 401 - in15.toLong() * (4076 - 4096), 12) - in15
        var t9a = positiveRoundShift(in9.toLong() * 1583 - in7.toLong() * 1299, 11)
        var t10a =
            positiveRoundShift(in5.toLong() * 1931 - in11.toLong() * (3612 - 4096), 12) - in11
        val t11a =
            positiveRoundShift(in13.toLong() * (3920 - 4096) - in3.toLong() * 1189, 12) + in13
        val t12a = positiveRoundShift(in13.toLong() * 1189 + in3.toLong() * (3920 - 4096), 12) + in3
        var t13a = positiveRoundShift(in5.toLong() * (3612 - 4096) + in11.toLong() * 1931, 12) + in5
        var t14a = positiveRoundShift(in9.toLong() * 1299 + in7.toLong() * 1583, 11)
        val t15a = positiveRoundShift(in1.toLong() * (4076 - 4096) + in15.toLong() * 401, 12) + in1

        val t8 = clip(t8a.toLong() + t9a)
        var t9 = clip(t8a.toLong() - t9a)
        var t10 = clip(t11a.toLong() - t10a)
        var t11 = clip(t11a.toLong() + t10a)
        var t12 = clip(t12a.toLong() + t13a)
        var t13 = clip(t12a.toLong() - t13a)
        var t14 = clip(t15a.toLong() - t14a)
        val t15 = clip(t15a.toLong() + t14a)

        t9a = positiveRoundShift(t14.toLong() * 1567 - t9.toLong() * (3784 - 4096), 12) - t9
        t14a = positiveRoundShift(t14.toLong() * (3784 - 4096) + t9.toLong() * 1567, 12) + t14
        t10a = positiveRoundShift(-(t13.toLong() * (3784 - 4096) + t10.toLong() * 1567), 12) - t13
        t13a = positiveRoundShift(t13.toLong() * 1567 - t10.toLong() * (3784 - 4096), 12) - t10

        t8a = clip(t8.toLong() + t11)
        t9 = clip(t9a.toLong() + t10a)
        t10 = clip(t9a.toLong() - t10a)
        val t11b = clip(t8.toLong() - t11)
        val t12b = clip(t15.toLong() - t12)
        t13 = clip(t14a.toLong() - t13a)
        t14 = clip(t14a.toLong() + t13a)
        val t15b = clip(t15.toLong() + t12)

        t10a = positiveRoundShift((t13 - t10).toLong() * 181, 8)
        t13a = positiveRoundShift((t13 + t10).toLong() * 181, 8)
        t11 = positiveRoundShift((t12b - t11b).toLong() * 181, 8)
        t12 = positiveRoundShift((t12b + t11b).toLong() * 181, 8)

        output[0] = clip(evenOutput[0].toLong() + t15b)
        output[1] = clip(evenOutput[1].toLong() + t14)
        output[2] = clip(evenOutput[2].toLong() + t13a)
        output[3] = clip(evenOutput[3].toLong() + t12)
        output[4] = clip(evenOutput[4].toLong() + t11)
        output[5] = clip(evenOutput[5].toLong() + t10a)
        output[6] = clip(evenOutput[6].toLong() + t9)
        output[7] = clip(evenOutput[7].toLong() + t8a)
        output[8] = clip(evenOutput[7].toLong() - t8a)
        output[9] = clip(evenOutput[6].toLong() - t9)
        output[10] = clip(evenOutput[5].toLong() - t10a)
        output[11] = clip(evenOutput[4].toLong() - t11)
        output[12] = clip(evenOutput[3].toLong() - t12)
        output[13] = clip(evenOutput[2].toLong() - t13a)
        output[14] = clip(evenOutput[1].toLong() - t14)
        output[15] = clip(evenOutput[0].toLong() - t15b)
    }

    /** Reconstructs one one-dimensional `DCT_64` vector with the staged AOM reference schedule.
     * 
     * This follows `av1_idct64()` stage-for-stage, including its fixed-point rounding and clipping
     * schedule.
     * 
     * @param input the dequantized `DCT_64` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseDct64Aom(input: IntArray, output: IntArray) {
        val step = reconstructionWorkspace.kernelStep()
        var bf0: IntArray?
        var bf1: IntArray?

        bf1 = output
        bf1[0] = input[0]
        bf1[1] = input[32]
        bf1[2] = input[16]
        bf1[3] = input[48]
        bf1[4] = input[8]
        bf1[5] = input[40]
        bf1[6] = input[24]
        bf1[7] = input[56]
        bf1[8] = input[4]
        bf1[9] = input[36]
        bf1[10] = input[20]
        bf1[11] = input[52]
        bf1[12] = input[12]
        bf1[13] = input[44]
        bf1[14] = input[28]
        bf1[15] = input[60]
        bf1[16] = input[2]
        bf1[17] = input[34]
        bf1[18] = input[18]
        bf1[19] = input[50]
        bf1[20] = input[10]
        bf1[21] = input[42]
        bf1[22] = input[26]
        bf1[23] = input[58]
        bf1[24] = input[6]
        bf1[25] = input[38]
        bf1[26] = input[22]
        bf1[27] = input[54]
        bf1[28] = input[14]
        bf1[29] = input[46]
        bf1[30] = input[30]
        bf1[31] = input[62]
        bf1[32] = input[1]
        bf1[33] = input[33]
        bf1[34] = input[17]
        bf1[35] = input[49]
        bf1[36] = input[9]
        bf1[37] = input[41]
        bf1[38] = input[25]
        bf1[39] = input[57]
        bf1[40] = input[5]
        bf1[41] = input[37]
        bf1[42] = input[21]
        bf1[43] = input[53]
        bf1[44] = input[13]
        bf1[45] = input[45]
        bf1[46] = input[29]
        bf1[47] = input[61]
        bf1[48] = input[3]
        bf1[49] = input[35]
        bf1[50] = input[19]
        bf1[51] = input[51]
        bf1[52] = input[11]
        bf1[53] = input[43]
        bf1[54] = input[27]
        bf1[55] = input[59]
        bf1[56] = input[7]
        bf1[57] = input[39]
        bf1[58] = input[23]
        bf1[59] = input[55]
        bf1[60] = input[15]
        bf1[61] = input[47]
        bf1[62] = input[31]
        bf1[63] = input[63]

        bf0 = output
        bf1 = step
        arraycopy(bf0, 0, bf1, 0, 32)
        bf1[32] = halfBtf(COSPI[63], bf0[32], -COSPI[1], bf0[63])
        bf1[33] = halfBtf(COSPI[31], bf0[33], -COSPI[33], bf0[62])
        bf1[34] = halfBtf(COSPI[47], bf0[34], -COSPI[17], bf0[61])
        bf1[35] = halfBtf(COSPI[15], bf0[35], -COSPI[49], bf0[60])
        bf1[36] = halfBtf(COSPI[55], bf0[36], -COSPI[9], bf0[59])
        bf1[37] = halfBtf(COSPI[23], bf0[37], -COSPI[41], bf0[58])
        bf1[38] = halfBtf(COSPI[39], bf0[38], -COSPI[25], bf0[57])
        bf1[39] = halfBtf(COSPI[7], bf0[39], -COSPI[57], bf0[56])
        bf1[40] = halfBtf(COSPI[59], bf0[40], -COSPI[5], bf0[55])
        bf1[41] = halfBtf(COSPI[27], bf0[41], -COSPI[37], bf0[54])
        bf1[42] = halfBtf(COSPI[43], bf0[42], -COSPI[21], bf0[53])
        bf1[43] = halfBtf(COSPI[11], bf0[43], -COSPI[53], bf0[52])
        bf1[44] = halfBtf(COSPI[51], bf0[44], -COSPI[13], bf0[51])
        bf1[45] = halfBtf(COSPI[19], bf0[45], -COSPI[45], bf0[50])
        bf1[46] = halfBtf(COSPI[35], bf0[46], -COSPI[29], bf0[49])
        bf1[47] = halfBtf(COSPI[3], bf0[47], -COSPI[61], bf0[48])
        bf1[48] = halfBtf(COSPI[61], bf0[47], COSPI[3], bf0[48])
        bf1[49] = halfBtf(COSPI[29], bf0[46], COSPI[35], bf0[49])
        bf1[50] = halfBtf(COSPI[45], bf0[45], COSPI[19], bf0[50])
        bf1[51] = halfBtf(COSPI[13], bf0[44], COSPI[51], bf0[51])
        bf1[52] = halfBtf(COSPI[53], bf0[43], COSPI[11], bf0[52])
        bf1[53] = halfBtf(COSPI[21], bf0[42], COSPI[43], bf0[53])
        bf1[54] = halfBtf(COSPI[37], bf0[41], COSPI[27], bf0[54])
        bf1[55] = halfBtf(COSPI[5], bf0[40], COSPI[59], bf0[55])
        bf1[56] = halfBtf(COSPI[57], bf0[39], COSPI[7], bf0[56])
        bf1[57] = halfBtf(COSPI[25], bf0[38], COSPI[39], bf0[57])
        bf1[58] = halfBtf(COSPI[41], bf0[37], COSPI[23], bf0[58])
        bf1[59] = halfBtf(COSPI[9], bf0[36], COSPI[55], bf0[59])
        bf1[60] = halfBtf(COSPI[49], bf0[35], COSPI[15], bf0[60])
        bf1[61] = halfBtf(COSPI[17], bf0[34], COSPI[47], bf0[61])
        bf1[62] = halfBtf(COSPI[33], bf0[33], COSPI[31], bf0[62])
        bf1[63] = halfBtf(COSPI[1], bf0[32], COSPI[63], bf0[63])

        bf0 = step
        bf1 = output
        arraycopy(bf0, 0, bf1, 0, 16)
        bf1[16] = halfBtf(COSPI[62], bf0[16], -COSPI[2], bf0[31])
        bf1[17] = halfBtf(COSPI[30], bf0[17], -COSPI[34], bf0[30])
        bf1[18] = halfBtf(COSPI[46], bf0[18], -COSPI[18], bf0[29])
        bf1[19] = halfBtf(COSPI[14], bf0[19], -COSPI[50], bf0[28])
        bf1[20] = halfBtf(COSPI[54], bf0[20], -COSPI[10], bf0[27])
        bf1[21] = halfBtf(COSPI[22], bf0[21], -COSPI[42], bf0[26])
        bf1[22] = halfBtf(COSPI[38], bf0[22], -COSPI[26], bf0[25])
        bf1[23] = halfBtf(COSPI[6], bf0[23], -COSPI[58], bf0[24])
        bf1[24] = halfBtf(COSPI[58], bf0[23], COSPI[6], bf0[24])
        bf1[25] = halfBtf(COSPI[26], bf0[22], COSPI[38], bf0[25])
        bf1[26] = halfBtf(COSPI[42], bf0[21], COSPI[22], bf0[26])
        bf1[27] = halfBtf(COSPI[10], bf0[20], COSPI[54], bf0[27])
        bf1[28] = halfBtf(COSPI[50], bf0[19], COSPI[14], bf0[28])
        bf1[29] = halfBtf(COSPI[18], bf0[18], COSPI[46], bf0[29])
        bf1[30] = halfBtf(COSPI[34], bf0[17], COSPI[30], bf0[30])
        bf1[31] = halfBtf(COSPI[2], bf0[16], COSPI[62], bf0[31])
        bf1[32] = clip(bf0[32].toLong() + bf0[33])
        bf1[33] = clip(bf0[32].toLong() - bf0[33])
        bf1[34] = clip(-bf0[34].toLong() + bf0[35])
        bf1[35] = clip(bf0[34].toLong() + bf0[35])
        bf1[36] = clip(bf0[36].toLong() + bf0[37])
        bf1[37] = clip(bf0[36].toLong() - bf0[37])
        bf1[38] = clip(-bf0[38].toLong() + bf0[39])
        bf1[39] = clip(bf0[38].toLong() + bf0[39])
        bf1[40] = clip(bf0[40].toLong() + bf0[41])
        bf1[41] = clip(bf0[40].toLong() - bf0[41])
        bf1[42] = clip(-bf0[42].toLong() + bf0[43])
        bf1[43] = clip(bf0[42].toLong() + bf0[43])
        bf1[44] = clip(bf0[44].toLong() + bf0[45])
        bf1[45] = clip(bf0[44].toLong() - bf0[45])
        bf1[46] = clip(-bf0[46].toLong() + bf0[47])
        bf1[47] = clip(bf0[46].toLong() + bf0[47])
        bf1[48] = clip(bf0[48].toLong() + bf0[49])
        bf1[49] = clip(bf0[48].toLong() - bf0[49])
        bf1[50] = clip(-bf0[50].toLong() + bf0[51])
        bf1[51] = clip(bf0[50].toLong() + bf0[51])
        bf1[52] = clip(bf0[52].toLong() + bf0[53])
        bf1[53] = clip(bf0[52].toLong() - bf0[53])
        bf1[54] = clip(-bf0[54].toLong() + bf0[55])
        bf1[55] = clip(bf0[54].toLong() + bf0[55])
        bf1[56] = clip(bf0[56].toLong() + bf0[57])
        bf1[57] = clip(bf0[56].toLong() - bf0[57])
        bf1[58] = clip(-bf0[58].toLong() + bf0[59])
        bf1[59] = clip(bf0[58].toLong() + bf0[59])
        bf1[60] = clip(bf0[60].toLong() + bf0[61])
        bf1[61] = clip(bf0[60].toLong() - bf0[61])
        bf1[62] = clip(-bf0[62].toLong() + bf0[63])
        bf1[63] = clip(bf0[62].toLong() + bf0[63])

        bf0 = output
        bf1 = step
        arraycopy(bf0, 0, bf1, 0, 8)
        bf1[8] = halfBtf(COSPI[60], bf0[8], -COSPI[4], bf0[15])
        bf1[9] = halfBtf(COSPI[28], bf0[9], -COSPI[36], bf0[14])
        bf1[10] = halfBtf(COSPI[44], bf0[10], -COSPI[20], bf0[13])
        bf1[11] = halfBtf(COSPI[12], bf0[11], -COSPI[52], bf0[12])
        bf1[12] = halfBtf(COSPI[52], bf0[11], COSPI[12], bf0[12])
        bf1[13] = halfBtf(COSPI[20], bf0[10], COSPI[44], bf0[13])
        bf1[14] = halfBtf(COSPI[36], bf0[9], COSPI[28], bf0[14])
        bf1[15] = halfBtf(COSPI[4], bf0[8], COSPI[60], bf0[15])
        bf1[16] = clip(bf0[16].toLong() + bf0[17])
        bf1[17] = clip(bf0[16].toLong() - bf0[17])
        bf1[18] = clip(-bf0[18].toLong() + bf0[19])
        bf1[19] = clip(bf0[18].toLong() + bf0[19])
        bf1[20] = clip(bf0[20].toLong() + bf0[21])
        bf1[21] = clip(bf0[20].toLong() - bf0[21])
        bf1[22] = clip(-bf0[22].toLong() + bf0[23])
        bf1[23] = clip(bf0[22].toLong() + bf0[23])
        bf1[24] = clip(bf0[24].toLong() + bf0[25])
        bf1[25] = clip(bf0[24].toLong() - bf0[25])
        bf1[26] = clip(-bf0[26].toLong() + bf0[27])
        bf1[27] = clip(bf0[26].toLong() + bf0[27])
        bf1[28] = clip(bf0[28].toLong() + bf0[29])
        bf1[29] = clip(bf0[28].toLong() - bf0[29])
        bf1[30] = clip(-bf0[30].toLong() + bf0[31])
        bf1[31] = clip(bf0[30].toLong() + bf0[31])
        bf1[32] = bf0[32]
        bf1[33] = halfBtf(-COSPI[4], bf0[33], COSPI[60], bf0[62])
        bf1[34] = halfBtf(-COSPI[60], bf0[34], -COSPI[4], bf0[61])
        bf1[35] = bf0[35]
        bf1[36] = bf0[36]
        bf1[37] = halfBtf(-COSPI[36], bf0[37], COSPI[28], bf0[58])
        bf1[38] = halfBtf(-COSPI[28], bf0[38], -COSPI[36], bf0[57])
        bf1[39] = bf0[39]
        bf1[40] = bf0[40]
        bf1[41] = halfBtf(-COSPI[20], bf0[41], COSPI[44], bf0[54])
        bf1[42] = halfBtf(-COSPI[44], bf0[42], -COSPI[20], bf0[53])
        bf1[43] = bf0[43]
        bf1[44] = bf0[44]
        bf1[45] = halfBtf(-COSPI[52], bf0[45], COSPI[12], bf0[50])
        bf1[46] = halfBtf(-COSPI[12], bf0[46], -COSPI[52], bf0[49])
        bf1[47] = bf0[47]
        bf1[48] = bf0[48]
        bf1[49] = halfBtf(-COSPI[52], bf0[46], COSPI[12], bf0[49])
        bf1[50] = halfBtf(COSPI[12], bf0[45], COSPI[52], bf0[50])
        bf1[51] = bf0[51]
        bf1[52] = bf0[52]
        bf1[53] = halfBtf(-COSPI[20], bf0[42], COSPI[44], bf0[53])
        bf1[54] = halfBtf(COSPI[44], bf0[41], COSPI[20], bf0[54])
        bf1[55] = bf0[55]
        bf1[56] = bf0[56]
        bf1[57] = halfBtf(-COSPI[36], bf0[38], COSPI[28], bf0[57])
        bf1[58] = halfBtf(COSPI[28], bf0[37], COSPI[36], bf0[58])
        bf1[59] = bf0[59]
        bf1[60] = bf0[60]
        bf1[61] = halfBtf(-COSPI[4], bf0[34], COSPI[60], bf0[61])
        bf1[62] = halfBtf(COSPI[60], bf0[33], COSPI[4], bf0[62])
        bf1[63] = bf0[63]

        bf0 = step
        bf1 = output
        arraycopy(bf0, 0, bf1, 0, 4)
        bf1[4] = halfBtf(COSPI[56], bf0[4], -COSPI[8], bf0[7])
        bf1[5] = halfBtf(COSPI[24], bf0[5], -COSPI[40], bf0[6])
        bf1[6] = halfBtf(COSPI[40], bf0[5], COSPI[24], bf0[6])
        bf1[7] = halfBtf(COSPI[8], bf0[4], COSPI[56], bf0[7])
        bf1[8] = clip(bf0[8].toLong() + bf0[9])
        bf1[9] = clip(bf0[8].toLong() - bf0[9])
        bf1[10] = clip(-bf0[10].toLong() + bf0[11])
        bf1[11] = clip(bf0[10].toLong() + bf0[11])
        bf1[12] = clip(bf0[12].toLong() + bf0[13])
        bf1[13] = clip(bf0[12].toLong() - bf0[13])
        bf1[14] = clip(-bf0[14].toLong() + bf0[15])
        bf1[15] = clip(bf0[14].toLong() + bf0[15])
        bf1[16] = bf0[16]
        bf1[17] = halfBtf(-COSPI[8], bf0[17], COSPI[56], bf0[30])
        bf1[18] = halfBtf(-COSPI[56], bf0[18], -COSPI[8], bf0[29])
        bf1[19] = bf0[19]
        bf1[20] = bf0[20]
        bf1[21] = halfBtf(-COSPI[40], bf0[21], COSPI[24], bf0[26])
        bf1[22] = halfBtf(-COSPI[24], bf0[22], -COSPI[40], bf0[25])
        bf1[23] = bf0[23]
        bf1[24] = bf0[24]
        bf1[25] = halfBtf(-COSPI[40], bf0[22], COSPI[24], bf0[25])
        bf1[26] = halfBtf(COSPI[24], bf0[21], COSPI[40], bf0[26])
        bf1[27] = bf0[27]
        bf1[28] = bf0[28]
        bf1[29] = halfBtf(-COSPI[8], bf0[18], COSPI[56], bf0[29])
        bf1[30] = halfBtf(COSPI[56], bf0[17], COSPI[8], bf0[30])
        bf1[31] = bf0[31]
        bf1[32] = clip(bf0[32].toLong() + bf0[35])
        bf1[33] = clip(bf0[33].toLong() + bf0[34])
        bf1[34] = clip(bf0[33].toLong() - bf0[34])
        bf1[35] = clip(bf0[32].toLong() - bf0[35])
        bf1[36] = clip(-bf0[36].toLong() + bf0[39])
        bf1[37] = clip(-bf0[37].toLong() + bf0[38])
        bf1[38] = clip(bf0[37].toLong() + bf0[38])
        bf1[39] = clip(bf0[36].toLong() + bf0[39])
        bf1[40] = clip(bf0[40].toLong() + bf0[43])
        bf1[41] = clip(bf0[41].toLong() + bf0[42])
        bf1[42] = clip(bf0[41].toLong() - bf0[42])
        bf1[43] = clip(bf0[40].toLong() - bf0[43])
        bf1[44] = clip(-bf0[44].toLong() + bf0[47])
        bf1[45] = clip(-bf0[45].toLong() + bf0[46])
        bf1[46] = clip(bf0[45].toLong() + bf0[46])
        bf1[47] = clip(bf0[44].toLong() + bf0[47])
        bf1[48] = clip(bf0[48].toLong() + bf0[51])
        bf1[49] = clip(bf0[49].toLong() + bf0[50])
        bf1[50] = clip(bf0[49].toLong() - bf0[50])
        bf1[51] = clip(bf0[48].toLong() - bf0[51])
        bf1[52] = clip(-bf0[52].toLong() + bf0[55])
        bf1[53] = clip(-bf0[53].toLong() + bf0[54])
        bf1[54] = clip(bf0[53].toLong() + bf0[54])
        bf1[55] = clip(bf0[52].toLong() + bf0[55])
        bf1[56] = clip(bf0[56].toLong() + bf0[59])
        bf1[57] = clip(bf0[57].toLong() + bf0[58])
        bf1[58] = clip(bf0[57].toLong() - bf0[58])
        bf1[59] = clip(bf0[56].toLong() - bf0[59])
        bf1[60] = clip(-bf0[60].toLong() + bf0[63])
        bf1[61] = clip(-bf0[61].toLong() + bf0[62])
        bf1[62] = clip(bf0[61].toLong() + bf0[62])
        bf1[63] = clip(bf0[60].toLong() + bf0[63])

        bf0 = output
        bf1 = step
        bf1[0] = halfBtf(COSPI[32], bf0[0], COSPI[32], bf0[1])
        bf1[1] = halfBtf(COSPI[32], bf0[0], -COSPI[32], bf0[1])
        bf1[2] = halfBtf(COSPI[48], bf0[2], -COSPI[16], bf0[3])
        bf1[3] = halfBtf(COSPI[16], bf0[2], COSPI[48], bf0[3])
        bf1[4] = clip(bf0[4].toLong() + bf0[5])
        bf1[5] = clip(bf0[4].toLong() - bf0[5])
        bf1[6] = clip(-bf0[6].toLong() + bf0[7])
        bf1[7] = clip(bf0[6].toLong() + bf0[7])
        bf1[8] = bf0[8]
        bf1[9] = halfBtf(-COSPI[16], bf0[9], COSPI[48], bf0[14])
        bf1[10] = halfBtf(-COSPI[48], bf0[10], -COSPI[16], bf0[13])
        bf1[11] = bf0[11]
        bf1[12] = bf0[12]
        bf1[13] = halfBtf(-COSPI[16], bf0[10], COSPI[48], bf0[13])
        bf1[14] = halfBtf(COSPI[48], bf0[9], COSPI[16], bf0[14])
        bf1[15] = bf0[15]
        bf1[16] = clip(bf0[16].toLong() + bf0[19])
        bf1[17] = clip(bf0[17].toLong() + bf0[18])
        bf1[18] = clip(bf0[17].toLong() - bf0[18])
        bf1[19] = clip(bf0[16].toLong() - bf0[19])
        bf1[20] = clip(-bf0[20].toLong() + bf0[23])
        bf1[21] = clip(-bf0[21].toLong() + bf0[22])
        bf1[22] = clip(bf0[21].toLong() + bf0[22])
        bf1[23] = clip(bf0[20].toLong() + bf0[23])
        bf1[24] = clip(bf0[24].toLong() + bf0[27])
        bf1[25] = clip(bf0[25].toLong() + bf0[26])
        bf1[26] = clip(bf0[25].toLong() - bf0[26])
        bf1[27] = clip(bf0[24].toLong() - bf0[27])
        bf1[28] = clip(-bf0[28].toLong() + bf0[31])
        bf1[29] = clip(-bf0[29].toLong() + bf0[30])
        bf1[30] = clip(bf0[29].toLong() + bf0[30])
        bf1[31] = clip(bf0[28].toLong() + bf0[31])
        bf1[32] = bf0[32]
        bf1[33] = bf0[33]
        bf1[34] = halfBtf(-COSPI[8], bf0[34], COSPI[56], bf0[61])
        bf1[35] = halfBtf(-COSPI[8], bf0[35], COSPI[56], bf0[60])
        bf1[36] = halfBtf(-COSPI[56], bf0[36], -COSPI[8], bf0[59])
        bf1[37] = halfBtf(-COSPI[56], bf0[37], -COSPI[8], bf0[58])
        bf1[38] = bf0[38]
        bf1[39] = bf0[39]
        bf1[40] = bf0[40]
        bf1[41] = bf0[41]
        bf1[42] = halfBtf(-COSPI[40], bf0[42], COSPI[24], bf0[53])
        bf1[43] = halfBtf(-COSPI[40], bf0[43], COSPI[24], bf0[52])
        bf1[44] = halfBtf(-COSPI[24], bf0[44], -COSPI[40], bf0[51])
        bf1[45] = halfBtf(-COSPI[24], bf0[45], -COSPI[40], bf0[50])
        bf1[46] = bf0[46]
        bf1[47] = bf0[47]
        bf1[48] = bf0[48]
        bf1[49] = bf0[49]
        bf1[50] = halfBtf(-COSPI[40], bf0[45], COSPI[24], bf0[50])
        bf1[51] = halfBtf(-COSPI[40], bf0[44], COSPI[24], bf0[51])
        bf1[52] = halfBtf(COSPI[24], bf0[43], COSPI[40], bf0[52])
        bf1[53] = halfBtf(COSPI[24], bf0[42], COSPI[40], bf0[53])
        bf1[54] = bf0[54]
        bf1[55] = bf0[55]
        bf1[56] = bf0[56]
        bf1[57] = bf0[57]
        bf1[58] = halfBtf(-COSPI[8], bf0[37], COSPI[56], bf0[58])
        bf1[59] = halfBtf(-COSPI[8], bf0[36], COSPI[56], bf0[59])
        bf1[60] = halfBtf(COSPI[56], bf0[35], COSPI[8], bf0[60])
        bf1[61] = halfBtf(COSPI[56], bf0[34], COSPI[8], bf0[61])
        bf1[62] = bf0[62]
        bf1[63] = bf0[63]

        bf0 = step
        bf1 = output
        bf1[0] = clip(bf0[0].toLong() + bf0[3])
        bf1[1] = clip(bf0[1].toLong() + bf0[2])
        bf1[2] = clip(bf0[1].toLong() - bf0[2])
        bf1[3] = clip(bf0[0].toLong() - bf0[3])
        bf1[4] = bf0[4]
        bf1[5] = halfBtf(-COSPI[32], bf0[5], COSPI[32], bf0[6])
        bf1[6] = halfBtf(COSPI[32], bf0[5], COSPI[32], bf0[6])
        bf1[7] = bf0[7]
        bf1[8] = clip(bf0[8].toLong() + bf0[11])
        bf1[9] = clip(bf0[9].toLong() + bf0[10])
        bf1[10] = clip(bf0[9].toLong() - bf0[10])
        bf1[11] = clip(bf0[8].toLong() - bf0[11])
        bf1[12] = clip(-bf0[12].toLong() + bf0[15])
        bf1[13] = clip(-bf0[13].toLong() + bf0[14])
        bf1[14] = clip(bf0[13].toLong() + bf0[14])
        bf1[15] = clip(bf0[12].toLong() + bf0[15])
        bf1[16] = bf0[16]
        bf1[17] = bf0[17]
        bf1[18] = halfBtf(-COSPI[16], bf0[18], COSPI[48], bf0[29])
        bf1[19] = halfBtf(-COSPI[16], bf0[19], COSPI[48], bf0[28])
        bf1[20] = halfBtf(-COSPI[48], bf0[20], -COSPI[16], bf0[27])
        bf1[21] = halfBtf(-COSPI[48], bf0[21], -COSPI[16], bf0[26])
        bf1[22] = bf0[22]
        bf1[23] = bf0[23]
        bf1[24] = bf0[24]
        bf1[25] = bf0[25]
        bf1[26] = halfBtf(-COSPI[16], bf0[21], COSPI[48], bf0[26])
        bf1[27] = halfBtf(-COSPI[16], bf0[20], COSPI[48], bf0[27])
        bf1[28] = halfBtf(COSPI[48], bf0[19], COSPI[16], bf0[28])
        bf1[29] = halfBtf(COSPI[48], bf0[18], COSPI[16], bf0[29])
        bf1[30] = bf0[30]
        bf1[31] = bf0[31]
        bf1[32] = clip(bf0[32].toLong() + bf0[39])
        bf1[33] = clip(bf0[33].toLong() + bf0[38])
        bf1[34] = clip(bf0[34].toLong() + bf0[37])
        bf1[35] = clip(bf0[35].toLong() + bf0[36])
        bf1[36] = clip(bf0[35].toLong() - bf0[36])
        bf1[37] = clip(bf0[34].toLong() - bf0[37])
        bf1[38] = clip(bf0[33].toLong() - bf0[38])
        bf1[39] = clip(bf0[32].toLong() - bf0[39])
        bf1[40] = clip(-bf0[40].toLong() + bf0[47])
        bf1[41] = clip(-bf0[41].toLong() + bf0[46])
        bf1[42] = clip(-bf0[42].toLong() + bf0[45])
        bf1[43] = clip(-bf0[43].toLong() + bf0[44])
        bf1[44] = clip(bf0[43].toLong() + bf0[44])
        bf1[45] = clip(bf0[42].toLong() + bf0[45])
        bf1[46] = clip(bf0[41].toLong() + bf0[46])
        bf1[47] = clip(bf0[40].toLong() + bf0[47])
        bf1[48] = clip(bf0[48].toLong() + bf0[55])
        bf1[49] = clip(bf0[49].toLong() + bf0[54])
        bf1[50] = clip(bf0[50].toLong() + bf0[53])
        bf1[51] = clip(bf0[51].toLong() + bf0[52])
        bf1[52] = clip(bf0[51].toLong() - bf0[52])
        bf1[53] = clip(bf0[50].toLong() - bf0[53])
        bf1[54] = clip(bf0[49].toLong() - bf0[54])
        bf1[55] = clip(bf0[48].toLong() - bf0[55])
        bf1[56] = clip(-bf0[56].toLong() + bf0[63])
        bf1[57] = clip(-bf0[57].toLong() + bf0[62])
        bf1[58] = clip(-bf0[58].toLong() + bf0[61])
        bf1[59] = clip(-bf0[59].toLong() + bf0[60])
        bf1[60] = clip(bf0[59].toLong() + bf0[60])
        bf1[61] = clip(bf0[58].toLong() + bf0[61])
        bf1[62] = clip(bf0[57].toLong() + bf0[62])
        bf1[63] = clip(bf0[56].toLong() + bf0[63])

        bf0 = output
        bf1 = step
        bf1[0] = clip(bf0[0].toLong() + bf0[7])
        bf1[1] = clip(bf0[1].toLong() + bf0[6])
        bf1[2] = clip(bf0[2].toLong() + bf0[5])
        bf1[3] = clip(bf0[3].toLong() + bf0[4])
        bf1[4] = clip(bf0[3].toLong() - bf0[4])
        bf1[5] = clip(bf0[2].toLong() - bf0[5])
        bf1[6] = clip(bf0[1].toLong() - bf0[6])
        bf1[7] = clip(bf0[0].toLong() - bf0[7])
        bf1[8] = bf0[8]
        bf1[9] = bf0[9]
        bf1[10] = halfBtf(-COSPI[32], bf0[10], COSPI[32], bf0[13])
        bf1[11] = halfBtf(-COSPI[32], bf0[11], COSPI[32], bf0[12])
        bf1[12] = halfBtf(COSPI[32], bf0[11], COSPI[32], bf0[12])
        bf1[13] = halfBtf(COSPI[32], bf0[10], COSPI[32], bf0[13])
        bf1[14] = bf0[14]
        bf1[15] = bf0[15]
        bf1[16] = clip(bf0[16].toLong() + bf0[23])
        bf1[17] = clip(bf0[17].toLong() + bf0[22])
        bf1[18] = clip(bf0[18].toLong() + bf0[21])
        bf1[19] = clip(bf0[19].toLong() + bf0[20])
        bf1[20] = clip(bf0[19].toLong() - bf0[20])
        bf1[21] = clip(bf0[18].toLong() - bf0[21])
        bf1[22] = clip(bf0[17].toLong() - bf0[22])
        bf1[23] = clip(bf0[16].toLong() - bf0[23])
        bf1[24] = clip(-bf0[24].toLong() + bf0[31])
        bf1[25] = clip(-bf0[25].toLong() + bf0[30])
        bf1[26] = clip(-bf0[26].toLong() + bf0[29])
        bf1[27] = clip(-bf0[27].toLong() + bf0[28])
        bf1[28] = clip(bf0[27].toLong() + bf0[28])
        bf1[29] = clip(bf0[26].toLong() + bf0[29])
        bf1[30] = clip(bf0[25].toLong() + bf0[30])
        bf1[31] = clip(bf0[24].toLong() + bf0[31])
        bf1[32] = bf0[32]
        bf1[33] = bf0[33]
        bf1[34] = bf0[34]
        bf1[35] = bf0[35]
        bf1[36] = halfBtf(-COSPI[16], bf0[36], COSPI[48], bf0[59])
        bf1[37] = halfBtf(-COSPI[16], bf0[37], COSPI[48], bf0[58])
        bf1[38] = halfBtf(-COSPI[16], bf0[38], COSPI[48], bf0[57])
        bf1[39] = halfBtf(-COSPI[16], bf0[39], COSPI[48], bf0[56])
        bf1[40] = halfBtf(-COSPI[48], bf0[40], -COSPI[16], bf0[55])
        bf1[41] = halfBtf(-COSPI[48], bf0[41], -COSPI[16], bf0[54])
        bf1[42] = halfBtf(-COSPI[48], bf0[42], -COSPI[16], bf0[53])
        bf1[43] = halfBtf(-COSPI[48], bf0[43], -COSPI[16], bf0[52])
        bf1[44] = bf0[44]
        bf1[45] = bf0[45]
        bf1[46] = bf0[46]
        bf1[47] = bf0[47]
        bf1[48] = bf0[48]
        bf1[49] = bf0[49]
        bf1[50] = bf0[50]
        bf1[51] = bf0[51]
        bf1[52] = halfBtf(-COSPI[16], bf0[43], COSPI[48], bf0[52])
        bf1[53] = halfBtf(-COSPI[16], bf0[42], COSPI[48], bf0[53])
        bf1[54] = halfBtf(-COSPI[16], bf0[41], COSPI[48], bf0[54])
        bf1[55] = halfBtf(-COSPI[16], bf0[40], COSPI[48], bf0[55])
        bf1[56] = halfBtf(COSPI[48], bf0[39], COSPI[16], bf0[56])
        bf1[57] = halfBtf(COSPI[48], bf0[38], COSPI[16], bf0[57])
        bf1[58] = halfBtf(COSPI[48], bf0[37], COSPI[16], bf0[58])
        bf1[59] = halfBtf(COSPI[48], bf0[36], COSPI[16], bf0[59])
        bf1[60] = bf0[60]
        bf1[61] = bf0[61]
        bf1[62] = bf0[62]
        bf1[63] = bf0[63]

        bf0 = step
        bf1 = output
        bf1[0] = clip(bf0[0].toLong() + bf0[15])
        bf1[1] = clip(bf0[1].toLong() + bf0[14])
        bf1[2] = clip(bf0[2].toLong() + bf0[13])
        bf1[3] = clip(bf0[3].toLong() + bf0[12])
        bf1[4] = clip(bf0[4].toLong() + bf0[11])
        bf1[5] = clip(bf0[5].toLong() + bf0[10])
        bf1[6] = clip(bf0[6].toLong() + bf0[9])
        bf1[7] = clip(bf0[7].toLong() + bf0[8])
        bf1[8] = clip(bf0[7].toLong() - bf0[8])
        bf1[9] = clip(bf0[6].toLong() - bf0[9])
        bf1[10] = clip(bf0[5].toLong() - bf0[10])
        bf1[11] = clip(bf0[4].toLong() - bf0[11])
        bf1[12] = clip(bf0[3].toLong() - bf0[12])
        bf1[13] = clip(bf0[2].toLong() - bf0[13])
        bf1[14] = clip(bf0[1].toLong() - bf0[14])
        bf1[15] = clip(bf0[0].toLong() - bf0[15])
        bf1[16] = bf0[16]
        bf1[17] = bf0[17]
        bf1[18] = bf0[18]
        bf1[19] = bf0[19]
        bf1[20] = halfBtf(-COSPI[32], bf0[20], COSPI[32], bf0[27])
        bf1[21] = halfBtf(-COSPI[32], bf0[21], COSPI[32], bf0[26])
        bf1[22] = halfBtf(-COSPI[32], bf0[22], COSPI[32], bf0[25])
        bf1[23] = halfBtf(-COSPI[32], bf0[23], COSPI[32], bf0[24])
        bf1[24] = halfBtf(COSPI[32], bf0[23], COSPI[32], bf0[24])
        bf1[25] = halfBtf(COSPI[32], bf0[22], COSPI[32], bf0[25])
        bf1[26] = halfBtf(COSPI[32], bf0[21], COSPI[32], bf0[26])
        bf1[27] = halfBtf(COSPI[32], bf0[20], COSPI[32], bf0[27])
        bf1[28] = bf0[28]
        bf1[29] = bf0[29]
        bf1[30] = bf0[30]
        bf1[31] = bf0[31]
        bf1[32] = clip(bf0[32].toLong() + bf0[47])
        bf1[33] = clip(bf0[33].toLong() + bf0[46])
        bf1[34] = clip(bf0[34].toLong() + bf0[45])
        bf1[35] = clip(bf0[35].toLong() + bf0[44])
        bf1[36] = clip(bf0[36].toLong() + bf0[43])
        bf1[37] = clip(bf0[37].toLong() + bf0[42])
        bf1[38] = clip(bf0[38].toLong() + bf0[41])
        bf1[39] = clip(bf0[39].toLong() + bf0[40])
        bf1[40] = clip(bf0[39].toLong() - bf0[40])
        bf1[41] = clip(bf0[38].toLong() - bf0[41])
        bf1[42] = clip(bf0[37].toLong() - bf0[42])
        bf1[43] = clip(bf0[36].toLong() - bf0[43])
        bf1[44] = clip(bf0[35].toLong() - bf0[44])
        bf1[45] = clip(bf0[34].toLong() - bf0[45])
        bf1[46] = clip(bf0[33].toLong() - bf0[46])
        bf1[47] = clip(bf0[32].toLong() - bf0[47])
        bf1[48] = clip(-bf0[48].toLong() + bf0[63])
        bf1[49] = clip(-bf0[49].toLong() + bf0[62])
        bf1[50] = clip(-bf0[50].toLong() + bf0[61])
        bf1[51] = clip(-bf0[51].toLong() + bf0[60])
        bf1[52] = clip(-bf0[52].toLong() + bf0[59])
        bf1[53] = clip(-bf0[53].toLong() + bf0[58])
        bf1[54] = clip(-bf0[54].toLong() + bf0[57])
        bf1[55] = clip(-bf0[55].toLong() + bf0[56])
        bf1[56] = clip(bf0[55].toLong() + bf0[56])
        bf1[57] = clip(bf0[54].toLong() + bf0[57])
        bf1[58] = clip(bf0[53].toLong() + bf0[58])
        bf1[59] = clip(bf0[52].toLong() + bf0[59])
        bf1[60] = clip(bf0[51].toLong() + bf0[60])
        bf1[61] = clip(bf0[50].toLong() + bf0[61])
        bf1[62] = clip(bf0[49].toLong() + bf0[62])
        bf1[63] = clip(bf0[48].toLong() + bf0[63])

        bf0 = output
        bf1 = step
        bf1[0] = clip(bf0[0].toLong() + bf0[31])
        bf1[1] = clip(bf0[1].toLong() + bf0[30])
        bf1[2] = clip(bf0[2].toLong() + bf0[29])
        bf1[3] = clip(bf0[3].toLong() + bf0[28])
        bf1[4] = clip(bf0[4].toLong() + bf0[27])
        bf1[5] = clip(bf0[5].toLong() + bf0[26])
        bf1[6] = clip(bf0[6].toLong() + bf0[25])
        bf1[7] = clip(bf0[7].toLong() + bf0[24])
        bf1[8] = clip(bf0[8].toLong() + bf0[23])
        bf1[9] = clip(bf0[9].toLong() + bf0[22])
        bf1[10] = clip(bf0[10].toLong() + bf0[21])
        bf1[11] = clip(bf0[11].toLong() + bf0[20])
        bf1[12] = clip(bf0[12].toLong() + bf0[19])
        bf1[13] = clip(bf0[13].toLong() + bf0[18])
        bf1[14] = clip(bf0[14].toLong() + bf0[17])
        bf1[15] = clip(bf0[15].toLong() + bf0[16])
        bf1[16] = clip(bf0[15].toLong() - bf0[16])
        bf1[17] = clip(bf0[14].toLong() - bf0[17])
        bf1[18] = clip(bf0[13].toLong() - bf0[18])
        bf1[19] = clip(bf0[12].toLong() - bf0[19])
        bf1[20] = clip(bf0[11].toLong() - bf0[20])
        bf1[21] = clip(bf0[10].toLong() - bf0[21])
        bf1[22] = clip(bf0[9].toLong() - bf0[22])
        bf1[23] = clip(bf0[8].toLong() - bf0[23])
        bf1[24] = clip(bf0[7].toLong() - bf0[24])
        bf1[25] = clip(bf0[6].toLong() - bf0[25])
        bf1[26] = clip(bf0[5].toLong() - bf0[26])
        bf1[27] = clip(bf0[4].toLong() - bf0[27])
        bf1[28] = clip(bf0[3].toLong() - bf0[28])
        bf1[29] = clip(bf0[2].toLong() - bf0[29])
        bf1[30] = clip(bf0[1].toLong() - bf0[30])
        bf1[31] = clip(bf0[0].toLong() - bf0[31])
        bf1[32] = bf0[32]
        bf1[33] = bf0[33]
        bf1[34] = bf0[34]
        bf1[35] = bf0[35]
        bf1[36] = bf0[36]
        bf1[37] = bf0[37]
        bf1[38] = bf0[38]
        bf1[39] = bf0[39]
        bf1[40] = halfBtf(-COSPI[32], bf0[40], COSPI[32], bf0[55])
        bf1[41] = halfBtf(-COSPI[32], bf0[41], COSPI[32], bf0[54])
        bf1[42] = halfBtf(-COSPI[32], bf0[42], COSPI[32], bf0[53])
        bf1[43] = halfBtf(-COSPI[32], bf0[43], COSPI[32], bf0[52])
        bf1[44] = halfBtf(-COSPI[32], bf0[44], COSPI[32], bf0[51])
        bf1[45] = halfBtf(-COSPI[32], bf0[45], COSPI[32], bf0[50])
        bf1[46] = halfBtf(-COSPI[32], bf0[46], COSPI[32], bf0[49])
        bf1[47] = halfBtf(-COSPI[32], bf0[47], COSPI[32], bf0[48])
        bf1[48] = halfBtf(COSPI[32], bf0[47], COSPI[32], bf0[48])
        bf1[49] = halfBtf(COSPI[32], bf0[46], COSPI[32], bf0[49])
        bf1[50] = halfBtf(COSPI[32], bf0[45], COSPI[32], bf0[50])
        bf1[51] = halfBtf(COSPI[32], bf0[44], COSPI[32], bf0[51])
        bf1[52] = halfBtf(COSPI[32], bf0[43], COSPI[32], bf0[52])
        bf1[53] = halfBtf(COSPI[32], bf0[42], COSPI[32], bf0[53])
        bf1[54] = halfBtf(COSPI[32], bf0[41], COSPI[32], bf0[54])
        bf1[55] = halfBtf(COSPI[32], bf0[40], COSPI[32], bf0[55])
        bf1[56] = bf0[56]
        bf1[57] = bf0[57]
        bf1[58] = bf0[58]
        bf1[59] = bf0[59]
        bf1[60] = bf0[60]
        bf1[61] = bf0[61]
        bf1[62] = bf0[62]
        bf1[63] = bf0[63]

        bf0 = step
        bf1 = output
        bf1[0] = clip(bf0[0].toLong() + bf0[63])
        bf1[1] = clip(bf0[1].toLong() + bf0[62])
        bf1[2] = clip(bf0[2].toLong() + bf0[61])
        bf1[3] = clip(bf0[3].toLong() + bf0[60])
        bf1[4] = clip(bf0[4].toLong() + bf0[59])
        bf1[5] = clip(bf0[5].toLong() + bf0[58])
        bf1[6] = clip(bf0[6].toLong() + bf0[57])
        bf1[7] = clip(bf0[7].toLong() + bf0[56])
        bf1[8] = clip(bf0[8].toLong() + bf0[55])
        bf1[9] = clip(bf0[9].toLong() + bf0[54])
        bf1[10] = clip(bf0[10].toLong() + bf0[53])
        bf1[11] = clip(bf0[11].toLong() + bf0[52])
        bf1[12] = clip(bf0[12].toLong() + bf0[51])
        bf1[13] = clip(bf0[13].toLong() + bf0[50])
        bf1[14] = clip(bf0[14].toLong() + bf0[49])
        bf1[15] = clip(bf0[15].toLong() + bf0[48])
        bf1[16] = clip(bf0[16].toLong() + bf0[47])
        bf1[17] = clip(bf0[17].toLong() + bf0[46])
        bf1[18] = clip(bf0[18].toLong() + bf0[45])
        bf1[19] = clip(bf0[19].toLong() + bf0[44])
        bf1[20] = clip(bf0[20].toLong() + bf0[43])
        bf1[21] = clip(bf0[21].toLong() + bf0[42])
        bf1[22] = clip(bf0[22].toLong() + bf0[41])
        bf1[23] = clip(bf0[23].toLong() + bf0[40])
        bf1[24] = clip(bf0[24].toLong() + bf0[39])
        bf1[25] = clip(bf0[25].toLong() + bf0[38])
        bf1[26] = clip(bf0[26].toLong() + bf0[37])
        bf1[27] = clip(bf0[27].toLong() + bf0[36])
        bf1[28] = clip(bf0[28].toLong() + bf0[35])
        bf1[29] = clip(bf0[29].toLong() + bf0[34])
        bf1[30] = clip(bf0[30].toLong() + bf0[33])
        bf1[31] = clip(bf0[31].toLong() + bf0[32])
        bf1[32] = clip(bf0[31].toLong() - bf0[32])
        bf1[33] = clip(bf0[30].toLong() - bf0[33])
        bf1[34] = clip(bf0[29].toLong() - bf0[34])
        bf1[35] = clip(bf0[28].toLong() - bf0[35])
        bf1[36] = clip(bf0[27].toLong() - bf0[36])
        bf1[37] = clip(bf0[26].toLong() - bf0[37])
        bf1[38] = clip(bf0[25].toLong() - bf0[38])
        bf1[39] = clip(bf0[24].toLong() - bf0[39])
        bf1[40] = clip(bf0[23].toLong() - bf0[40])
        bf1[41] = clip(bf0[22].toLong() - bf0[41])
        bf1[42] = clip(bf0[21].toLong() - bf0[42])
        bf1[43] = clip(bf0[20].toLong() - bf0[43])
        bf1[44] = clip(bf0[19].toLong() - bf0[44])
        bf1[45] = clip(bf0[18].toLong() - bf0[45])
        bf1[46] = clip(bf0[17].toLong() - bf0[46])
        bf1[47] = clip(bf0[16].toLong() - bf0[47])
        bf1[48] = clip(bf0[15].toLong() - bf0[48])
        bf1[49] = clip(bf0[14].toLong() - bf0[49])
        bf1[50] = clip(bf0[13].toLong() - bf0[50])
        bf1[51] = clip(bf0[12].toLong() - bf0[51])
        bf1[52] = clip(bf0[11].toLong() - bf0[52])
        bf1[53] = clip(bf0[10].toLong() - bf0[53])
        bf1[54] = clip(bf0[9].toLong() - bf0[54])
        bf1[55] = clip(bf0[8].toLong() - bf0[55])
        bf1[56] = clip(bf0[7].toLong() - bf0[56])
        bf1[57] = clip(bf0[6].toLong() - bf0[57])
        bf1[58] = clip(bf0[5].toLong() - bf0[58])
        bf1[59] = clip(bf0[4].toLong() - bf0[59])
        bf1[60] = clip(bf0[3].toLong() - bf0[60])
        bf1[61] = clip(bf0[2].toLong() - bf0[61])
        bf1[62] = clip(bf0[1].toLong() - bf0[62])
        bf1[63] = clip(bf0[0].toLong() - bf0[63])
    }

    /** Reconstructs one supported one-dimensional inverse ADST vector.
     * 
     * @param input the dequantized input vector
     * @param output the reconstructed output vector
     * @param length the vector length in samples
     */
    private fun inverseAdst(input: IntArray, output: IntArray, length: Int) {
        when (length) {
            4 -> inverseAdst4(input, output)
            8 -> inverseAdst8(input, output)
            16 -> inverseAdst16(input, output)
            else -> throw IllegalStateException("Unsupported inverse ADST length: $length")
        }
    }

    /** Reconstructs one supported one-dimensional inverse FLIPADST vector.
     * 
     * @param input the dequantized input vector
     * @param output the reconstructed output vector
     * @param length the vector length in samples
     */
    private fun inverseFlipAdst(input: IntArray, output: IntArray, length: Int) {
        val scratch = reconstructionWorkspace.flipAdstScratch()
        inverseAdst(input, scratch, length)
        for (i in 0..<length) {
            output[i] = scratch[length - 1 - i]
        }
    }

    /** Reconstructs one one-dimensional `ADST_4` vector.
     * 
     * The arithmetic matches the AOM `av1_iadst4(..., cos_bit = 12)` path.
     * 
     * @param input the dequantized `ADST_4` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseAdst4(input: IntArray, output: IntArray) {
        val x0 = input[0]
        val x1 = input[1]
        val x2 = input[2]
        val x3 = input[3]

        if ((x0 or x1 or x2 or x3) == 0) {
            output[0] = 0
            output[1] = 0
            output[2] = 0
            output[3] = 0
            return
        }

        var s0 = 1321L * x0
        var s1 = 2482L * x0
        var s2 = 3344L * x1
        var s3 = 3803L * x2
        val s4 = 1321L * x2
        val s5 = 2482L * x3
        val s6 = 3803L * x3
        val s7 = saturatedInt((x0 - x2).toLong() + x3)

        s0 += s3
        s1 -= s4
        s3 = s2
        s2 = 3344L * s7

        s0 += s5
        s1 -= s6

        val y0 = s0 + s3
        val y1 = s1 + s3
        val y2 = s2
        val y3 = s0 + s1 - s3

        output[0] = positiveRoundShift(y0, 12)
        output[1] = positiveRoundShift(y1, 12)
        output[2] = positiveRoundShift(y2, 12)
        output[3] = positiveRoundShift(y3, 12)
    }

    /** Reconstructs one one-dimensional `ADST_8` vector.
     * 
     * The arithmetic matches the AOM `av1_iadst8(..., cos_bit = 12)` path.
     * 
     * @param input the dequantized `ADST_8` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseAdst8(input: IntArray, output: IntArray) {
        val b0 = input[7]
        val b1 = input[0]
        val b2 = input[5]
        val b3 = input[2]
        val b4 = input[3]
        val b5 = input[4]
        val b6 = input[1]
        val b7 = input[6]

        val s0 = halfBtf(COSPI[4], b0, COSPI[60], b1)
        val s1 = halfBtf(COSPI[60], b0, -COSPI[4], b1)
        val s2 = halfBtf(COSPI[20], b2, COSPI[44], b3)
        val s3 = halfBtf(COSPI[44], b2, -COSPI[20], b3)
        val s4 = halfBtf(COSPI[36], b4, COSPI[28], b5)
        val s5 = halfBtf(COSPI[28], b4, -COSPI[36], b5)
        val s6 = halfBtf(COSPI[52], b6, COSPI[12], b7)
        val s7 = halfBtf(COSPI[12], b6, -COSPI[52], b7)

        val t0 = clip(s0.toLong() + s4)
        val t1 = clip(s1.toLong() + s5)
        val t2 = clip(s2.toLong() + s6)
        val t3 = clip(s3.toLong() + s7)
        val t4 = clip(s0.toLong() - s4)
        val t5 = clip(s1.toLong() - s5)
        val t6 = clip(s2.toLong() - s6)
        val t7 = clip(s3.toLong() - s7)

        val u4 = halfBtf(COSPI[16], t4, COSPI[48], t5)
        val u5 = halfBtf(COSPI[48], t4, -COSPI[16], t5)
        val u6 = halfBtf(-COSPI[48], t6, COSPI[16], t7)
        val u7 = halfBtf(COSPI[16], t6, COSPI[48], t7)

        val v0 = clip(t0.toLong() + t2)
        val v1 = clip(t1.toLong() + t3)
        val v2 = clip(t0.toLong() - t2)
        val v3 = clip(t1.toLong() - t3)
        val v4 = clip(u4.toLong() + u6)
        val v5 = clip(u5.toLong() + u7)
        val v6 = clip(u4.toLong() - u6)
        val v7 = clip(u5.toLong() - u7)

        val w2 = halfBtf(COSPI[32], v2, COSPI[32], v3)
        val w3 = halfBtf(COSPI[32], v2, -COSPI[32], v3)
        val w6 = halfBtf(COSPI[32], v6, COSPI[32], v7)
        val w7 = halfBtf(COSPI[32], v6, -COSPI[32], v7)

        output[0] = v0
        output[1] = clip(-v4.toLong())
        output[2] = w6
        output[3] = clip(-w2.toLong())
        output[4] = w3
        output[5] = clip(-w7.toLong())
        output[6] = v5
        output[7] = clip(-v1.toLong())
    }

    /** Reconstructs one one-dimensional `ADST_16` vector.
     * 
     * @param input the dequantized `ADST_16` input vector
     * @param output the reconstructed output vector
     */
    private fun inverseAdst16(input: IntArray, output: IntArray) {
        val in0 = input[0]
        val in1 = input[1]
        val in2 = input[2]
        val in3 = input[3]
        val in4 = input[4]
        val in5 = input[5]
        val in6 = input[6]
        val in7 = input[7]
        val in8 = input[8]
        val in9 = input[9]
        val in10 = input[10]
        val in11 = input[11]
        val in12 = input[12]
        val in13 = input[13]
        val in14 = input[14]
        val in15 = input[15]

        var t0 = positiveRoundShift(in15.toLong() * (4091 - 4096) + in0.toLong() * 201, 12) + in15
        var t1 = positiveRoundShift(in15.toLong() * 201 - in0.toLong() * (4091 - 4096), 12) - in0
        var t2 = positiveRoundShift(in13.toLong() * (3973 - 4096) + in2.toLong() * 995, 12) + in13
        var t3 = positiveRoundShift(in13.toLong() * 995 - in2.toLong() * (3973 - 4096), 12) - in2
        var t4 = positiveRoundShift(in11.toLong() * (3703 - 4096) + in4.toLong() * 1751, 12) + in11
        var t5 = positiveRoundShift(in11.toLong() * 1751 - in4.toLong() * (3703 - 4096), 12) - in4
        var t6 = positiveRoundShift(in9.toLong() * 1645 + in6.toLong() * 1220, 11)
        var t7 = positiveRoundShift(in9.toLong() * 1220 - in6.toLong() * 1645, 11)
        var t8 = positiveRoundShift(in7.toLong() * 2751 + in8.toLong() * (3035 - 4096), 12) + in8
        var t9 = positiveRoundShift(in7.toLong() * (3035 - 4096) - in8.toLong() * 2751, 12) + in7
        var t10 = positiveRoundShift(in5.toLong() * 2106 + in10.toLong() * (3513 - 4096), 12) + in10
        var t11 = positiveRoundShift(in5.toLong() * (3513 - 4096) - in10.toLong() * 2106, 12) + in5
        var t12 = positiveRoundShift(in3.toLong() * 1380 + in12.toLong() * (3857 - 4096), 12) + in12
        var t13 = positiveRoundShift(in3.toLong() * (3857 - 4096) - in12.toLong() * 1380, 12) + in3
        var t14 = positiveRoundShift(in1.toLong() * 601 + in14.toLong() * (4052 - 4096), 12) + in14
        var t15 = positiveRoundShift(in1.toLong() * (4052 - 4096) - in14.toLong() * 601, 12) + in1

        val t0a = clip(t0.toLong() + t8)
        val t1a = clip(t1.toLong() + t9)
        var t2a = clip(t2.toLong() + t10)
        var t3a = clip(t3.toLong() + t11)
        var t4a = clip(t4.toLong() + t12)
        var t5a = clip(t5.toLong() + t13)
        var t6a = clip(t6.toLong() + t14)
        var t7a = clip(t7.toLong() + t15)
        var t8a = clip(t0.toLong() - t8)
        var t9a = clip(t1.toLong() - t9)
        var t10a = clip(t2.toLong() - t10)
        var t11a = clip(t3.toLong() - t11)
        var t12a = clip(t4.toLong() - t12)
        var t13a = clip(t5.toLong() - t13)
        var t14a = clip(t6.toLong() - t14)
        var t15a = clip(t7.toLong() - t15)

        t8 = positiveRoundShift(t8a.toLong() * (4017 - 4096) + t9a.toLong() * 799, 12) + t8a
        t9 = positiveRoundShift(t8a.toLong() * 799 - t9a.toLong() * (4017 - 4096), 12) - t9a
        t10 = positiveRoundShift(t10a.toLong() * 2276 + t11a.toLong() * (3406 - 4096), 12) + t11a
        t11 = positiveRoundShift(t10a.toLong() * (3406 - 4096) - t11a.toLong() * 2276, 12) + t10a
        t12 = positiveRoundShift(t13a.toLong() * (4017 - 4096) - t12a.toLong() * 799, 12) + t13a
        t13 = positiveRoundShift(t13a.toLong() * 799 + t12a.toLong() * (4017 - 4096), 12) + t12a
        t14 = positiveRoundShift(t15a.toLong() * 2276 - t14a.toLong() * (3406 - 4096), 12) - t14a
        t15 = positiveRoundShift(t15a.toLong() * (3406 - 4096) + t14a.toLong() * 2276, 12) + t15a

        t0 = clip(t0a.toLong() + t4a)
        t1 = clip(t1a.toLong() + t5a)
        t2 = clip(t2a.toLong() + t6a)
        t3 = clip(t3a.toLong() + t7a)
        t4 = clip(t0a.toLong() - t4a)
        t5 = clip(t1a.toLong() - t5a)
        t6 = clip(t2a.toLong() - t6a)
        t7 = clip(t3a.toLong() - t7a)
        t8a = clip(t8.toLong() + t12)
        t9a = clip(t9.toLong() + t13)
        t10a = clip(t10.toLong() + t14)
        t11a = clip(t11.toLong() + t15)
        t12a = clip(t8.toLong() - t12)
        t13a = clip(t9.toLong() - t13)
        t14a = clip(t10.toLong() - t14)
        t15a = clip(t11.toLong() - t15)

        t4a = positiveRoundShift(t4.toLong() * (3784 - 4096) + t5.toLong() * 1567, 12) + t4
        t5a = positiveRoundShift(t4.toLong() * 1567 - t5.toLong() * (3784 - 4096), 12) - t5
        t6a = positiveRoundShift(t7.toLong() * (3784 - 4096) - t6.toLong() * 1567, 12) + t7
        t7a = positiveRoundShift(t7.toLong() * 1567 + t6.toLong() * (3784 - 4096), 12) + t6
        t12 = positiveRoundShift(t12a.toLong() * (3784 - 4096) + t13a.toLong() * 1567, 12) + t12a
        t13 = positiveRoundShift(t12a.toLong() * 1567 - t13a.toLong() * (3784 - 4096), 12) - t13a
        t14 = positiveRoundShift(t15a.toLong() * (3784 - 4096) - t14a.toLong() * 1567, 12) + t15a
        t15 = positiveRoundShift(t15a.toLong() * 1567 + t14a.toLong() * (3784 - 4096), 12) + t14a

        output[0] = clip(t0.toLong() + t2)
        output[15] = clip(-clip(t1.toLong() + t3).toLong())
        t2a = clip(t0.toLong() - t2)
        t3a = clip(t1.toLong() - t3)
        output[3] = clip(-clip(t4a.toLong() + t6a).toLong())
        output[12] = clip(t5a.toLong() + t7a)
        t6 = clip(t4a.toLong() - t6a)
        t7 = clip(t5a.toLong() - t7a)
        output[1] = clip(-clip(t8a.toLong() + t10a).toLong())
        output[14] = clip(t9a.toLong() + t11a)
        t10 = clip(t8a.toLong() - t10a)
        t11 = clip(t9a.toLong() - t11a)
        output[2] = clip(t12.toLong() + t14)
        output[13] = clip(-clip(t13.toLong() + t15).toLong())
        t14a = clip(t12.toLong() - t14)
        t15a = clip(t13.toLong() - t15)

        output[7] = clip(-positiveRoundShift((t2a + t3a).toLong() * 181, 8).toLong())
        output[8] = positiveRoundShift((t2a - t3a).toLong() * 181, 8)
        output[4] = positiveRoundShift((t6 + t7).toLong() * 181, 8)
        output[11] = clip(-positiveRoundShift((t6 - t7).toLong() * 181, 8).toLong())
        output[6] = positiveRoundShift((t10 + t11).toLong() * 181, 8)
        output[9] = clip(-positiveRoundShift((t10 - t11).toLong() * 181, 8).toLong())
        output[5] = clip(-positiveRoundShift((t14a + t15a).toLong() * 181, 8).toLong())
        output[10] = positiveRoundShift((t14a - t15a).toLong() * 181, 8)
    }

    /** Reconstructs one supported one-dimensional inverse identity vector.
     * 
     * Identity kernels scale their inputs without a stage-local clamp. The surrounding
     * two-dimensional transform applies the required input and inter-pass clipping.
     * 
     * @param input the dequantized input vector
     * @param output the reconstructed output vector
     * @param length the vector length in samples
     */
    private fun inverseIdentity(input: IntArray, output: IntArray, length: Int) {
        when (length) {
            4 -> {
                for (i in 0..3) {
                    val value = input[i]
                    output[i] =
                        saturatedInt(value.toLong() + positiveRoundShift(value.toLong() * 1697, 12))
                }
            }

            8 -> {
                for (i in 0..7) {
                    output[i] = saturatedInt(input[i].toLong() * 2)
                }
            }

            16 -> {
                for (i in 0..15) {
                    val value = input[i]
                    output[i] = saturatedInt(
                        value.toLong() * 2 + positiveRoundShift(
                            value.toLong() * 1697,
                            11
                        )
                    )
                }
            }

            32 -> {
                for (i in 0..31) {
                    output[i] = saturatedInt(input[i].toLong() * 4)
                }
            }

            else -> throw IllegalStateException("Unsupported inverse identity length: $length")
        }
    }

    /** Applies one AV1 half-butterfly operation with inverse-transform rounding.
     * 
     * @param weight0 the first cosine weight
     * @param value0 the first source value
     * @param weight1 the second cosine weight
     * @param value1 the second source value
     * @return the rounded half-butterfly result
     */
    private fun halfBtf(weight0: Int, value0: Int, weight1: Int, value1: Int): Int {
        return positiveRoundShift(
            weight0.toLong() * value0 + weight1.toLong() * value1,
            INV_COS_BIT
        )
    }

    /** Applies the positive-bias arithmetic shifts used by `dav1d` inverse-transform kernels.
     * 
     * @param value the signed value to shift
     * @param bitCount the positive number of bits to shift away, or `0`
     * @return the rounded signed result
     */
    private fun positiveRoundShift(value: Long, bitCount: Int): Int {
        require(bitCount >= 0) { "bitCount < 0: $bitCount" }
        if (bitCount == 0) {
            return saturatedInt(value)
        }
        return saturatedInt((value + (1L shl (bitCount - 1))) shr bitCount)
    }

    /** Saturates one `long` into the signed `int` range.
     * 
     * @param value the value to saturate
     * @return the saturated `int`
     */
    private fun saturatedInt(value: Long): Int {
        if (value > Int.MAX_VALUE) {
            return Int.MAX_VALUE
        }
        if (value < Int.MIN_VALUE) {
            return Int.MIN_VALUE
        }
        return value.toInt()
    }

    /** Saturates one intermediate transform value into the signed `int` range.
     * 
     * @param value the intermediate transform value
     * @return the saturated `int`
     */
    private fun clip(value: Long): Int {
        // Every supported stage range contains the signed 16-bit domain. Most transform
        // intermediates stay inside it, so avoid consulting the active stage state there.
        // A narrowing round trip checks both signed bounds with one comparison.
        if (value.toShort().toLong() == value) {
            return value.toInt()
        }
        return clipToRange(value, activeClipRange)
    }

    /** Clamps one intermediate value into one explicit inverse-transform clip range.
     * 
     * @param value the value to validate and clamp
     * @param clipRange the active clip range
     * @return the clamped value
     */
    private fun clipToRange(value: Long, clipRange: ClipRange): Int {
        if (clipRange.rejectOutOfRange
            && (value < clipRange.minimum || value > clipRange.maximum)
        ) {
            throw InvalidFrameReconstructionException(
                "Inverse-transform intermediate value exceeds its conformance range"
            )
        }
        return max(
            clipRange.minimum,
            min(clipRange.maximum, saturatedInt(value))
        )
    }

    /** Returns a clip range with strict overflow rejection enabled when requested.
     * 
     * @param clipRange the base normative clip range
     * @param strictStdCompliance whether out-of-range values must be rejected
     * @return the base range or an equivalent strict conformance range
     */
    private fun conformanceClipRange(
        clipRange: ClipRange,
        strictStdCompliance: Boolean
    ): ClipRange {
        return if (strictStdCompliance)
            ClipRange(clipRange.minimum, clipRange.maximum, true)
        else
            clipRange
    }

    /** Runs one exact inverse-transform stage while overriding the active clip range.
     * 
     * @param clipRange the temporary clip range
     * @param action the stage body to execute
     */
    private fun withActiveClipRange(clipRange: ClipRange, action: () -> Unit) {
        val previous = activeClipRange
        activeClipRange = clipRange
        try {
            action()
        } finally {
            activeClipRange = previous
        }
    }

    /** Returns the dav1d row-pass clip range for one supported decoded bit depth.
     * 
     * @param bitDepth the decoded sample bit depth
     * @return the stage-local row-pass clip range
     */
    private fun rowClipRange(bitDepth: Int): ClipRange {
        return when (bitDepth) {
            8 -> ROW_CLIP_RANGE_8
            10 -> ROW_CLIP_RANGE_10
            12 -> ROW_CLIP_RANGE_12
            else -> throw IllegalArgumentException("Unsupported bitDepth: $bitDepth")
        }
    }

    /** Returns the dav1d column-domain clip range for one supported decoded bit depth.
     * 
     * @param bitDepth the decoded sample bit depth
     * @return the stage-local column-domain clip range
     */
    private fun columnClipRange(bitDepth: Int): ClipRange {
        return when (bitDepth) {
            8 -> COLUMN_CLIP_RANGE_8
            10 -> COLUMN_CLIP_RANGE_10
            12 -> COLUMN_CLIP_RANGE_12
            else -> throw IllegalArgumentException("Unsupported bitDepth: $bitDepth")
        }
    }

    /** Reusable transformer-owned storage for dequantization and inverse transforms.
     * 
     * Arrays are allocated lazily per transform size and remain owned by the current decode
     * transformer. Callers must consume their contents before beginning another residual operation. */
    class Workspace
    /** Creates an initially empty reusable workspace. */
    {
        /** Exact-length dequantized-coefficient buffers indexed by transform-size ordinal. */
        val coefficientBuffers: Array<IntArray>? =
            arrayOfNullsCompat<IntArray>(TransformSize.entries.size)

        /** Exact-length intermediate transform buffers indexed by transform-size ordinal. */
        val intermediateBuffers: Array<IntArray>? =
            arrayOfNullsCompat<IntArray>(TransformSize.entries.size)

        /** Exact-length residual output buffers indexed by transform-size ordinal. */
        val outputBuffers: Array<IntArray>? =
            arrayOfNullsCompat<IntArray>(TransformSize.entries.size)

        /** Shared input vector for one-dimensional transform passes. */
        val scratchInput = IntArray(64)

        /** Shared output vector for one-dimensional transform passes. */
        val scratchOutput = IntArray(64)

        /** Shared staging vector for non-recursive AOM inverse-DCT kernels. */
        val kernelStep = IntArray(64)

        /** Four-sample even-input vector used by the recursive dav1d inverse DCT. */
        val evenInput4 = IntArray(4)

        /** Four-sample even-output vector used by the recursive dav1d inverse DCT. */
        val evenOutput4 = IntArray(4)

        /** Eight-sample even-input vector used by the recursive dav1d inverse DCT. */
        val evenInput8 = IntArray(8)

        /** Eight-sample even-output vector used by the recursive dav1d inverse DCT. */
        val evenOutput8 = IntArray(8)

        /** Sixteen-sample even-input vector used by the recursive dav1d inverse DCT. */
        val evenInput16 = IntArray(16)

        /** Sixteen-sample even-output vector used by the recursive dav1d inverse DCT. */
        val evenOutput16 = IntArray(16)

        /** Shared output vector used before reversing one FLIPADST result. */
        val flipAdstScratch = IntArray(16)

        /** Returns an exact-length coefficient buffer for one transform size.
         * 
         * @param transformSize the transform size that determines the buffer length
         * @return the reusable coefficient buffer
         */
        fun coefficientBuffer(transformSize: TransformSize): IntArray {
            return buffer(coefficientBuffers, transformSize)
        }

        /** Returns an exact-length intermediate buffer for one transform size.
         * 
         * @param transformSize the transform size that determines the buffer length
         * @return the reusable intermediate buffer
         */
        fun intermediateBuffer(transformSize: TransformSize): IntArray {
            return buffer(intermediateBuffers, transformSize)
        }

        /** Returns an exact-length output buffer for one transform size.
         * 
         * @param transformSize the transform size that determines the buffer length
         * @return the reusable output buffer
         */
        fun outputBuffer(transformSize: TransformSize): IntArray {
            return buffer(outputBuffers, transformSize)
        }

        /** Returns the shared input scratch vector after validating the requested length.
         * 
         * @param length the required vector length
         * @return the shared input scratch vector
         */
        fun scratchInput(length: Int): IntArray {
            requireScratchLength(length)
            return scratchInput
        }

        /** Returns the shared output scratch vector after validating the requested length.
         * 
         * @param length the required vector length
         * @return the shared output scratch vector
         */
        fun scratchOutput(length: Int): IntArray {
            requireScratchLength(length)
            return scratchOutput
        }

        /** Returns the shared staging vector for one non-recursive AOM inverse-DCT kernel.
         * 
         * @return the shared 64-sample staging vector
         */
        fun kernelStep(): IntArray {
            return kernelStep
        }

        /** Returns the four-sample recursive even-input vector.
         * 
         * @return the shared four-sample input vector
         */
        fun evenInput4(): IntArray {
            return evenInput4
        }

        /** Returns the four-sample recursive even-output vector.
         * 
         * @return the shared four-sample output vector
         */
        fun evenOutput4(): IntArray {
            return evenOutput4
        }

        /** Returns the eight-sample recursive even-input vector.
         * 
         * @return the shared eight-sample input vector
         */
        fun evenInput8(): IntArray {
            return evenInput8
        }

        /** Returns the eight-sample recursive even-output vector.
         * 
         * @return the shared eight-sample output vector
         */
        fun evenOutput8(): IntArray {
            return evenOutput8
        }

        /** Returns the sixteen-sample recursive even-input vector.
         * 
         * @return the shared sixteen-sample input vector
         */
        fun evenInput16(): IntArray {
            return evenInput16
        }

        /** Returns the sixteen-sample recursive even-output vector.
         * 
         * @return the shared sixteen-sample output vector
         */
        fun evenOutput16(): IntArray {
            return evenOutput16
        }

        /** Returns the shared FLIPADST reversal vector.
         * 
         * @return the shared sixteen-sample reversal vector
         */
        fun flipAdstScratch(): IntArray {
            return flipAdstScratch
        }

        companion object {
            /** Returns one lazily allocated exact-length transform buffer.
             * 
             * @param buffers the per-transform-size buffer table
             * @param transformSize the transform size that selects the buffer
             * @return the selected reusable buffer
             */
            fun buffer(buffers: Array<IntArray>?, transformSize: TransformSize): IntArray {
                val nonNullTransformSize =
                    requireNotNullCompat(transformSize, "transformSize")
                val index = nonNullTransformSize.ordinal
                var buffer: IntArray? = buffers!![index]
                if (buffer == null) {
                    buffer = IntArray(checkedTransformArea(nonNullTransformSize))
                    buffers[index] = buffer
                }
                return buffer
            }

            /** Validates one requested scratch-vector length.
             * 
             * @param length the requested vector length
             */
            fun requireScratchLength(length: Int) {
                require(length in 0..64) { "Unsupported scratch length: $length" }
            }
        }
    }

    /** One clip range used by the exact inverse-transform kernels.
     * 
     * @param minimum the inclusive minimum value
     * @param maximum the inclusive maximum value
     * @param rejectOutOfRange whether values outside the range must be rejected instead of clamped
     */
    data class ClipRange(val minimum: Int, val maximum: Int, val rejectOutOfRange: Boolean)

    companion object {
        /** The AV1 inverse-transform cosine precision. */
        private const val INV_COS_BIT = 12

        /** The AV1 inverse-transform cosine table at precision `12`. */
        val COSPI: IntArray = intArrayOf(
            4096, 4095, 4091, 4085, 4076, 4065, 4052, 4036,
            4017, 3996, 3973, 3948, 3920, 3889, 3857, 3822,
            3784, 3745, 3703, 3659, 3612, 3564, 3513, 3461,
            3406, 3349, 3290, 3229, 3166, 3102, 3035, 2967,
            2896, 2824, 2751, 2675, 2598, 2520, 2440, 2359,
            2276, 2191, 2106, 2019, 1931, 1842, 1751, 1660,
            1567, 1474, 1380, 1285, 1189, 1092, 995, 897,
            799, 700, 601, 501, 401, 301, 201, 101,
        )

        /** The unrestricted clip range used by helper tests that do not supply bit-depth context. */
        val FULL_INT_CLIP_RANGE = ClipRange(Int.MIN_VALUE, Int.MAX_VALUE, false)

        /** The row-pass clip range for 8-bit samples. */
        val ROW_CLIP_RANGE_8 =
            ClipRange(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(), false)

        /** The row-pass clip range for 10-bit samples. */
        val ROW_CLIP_RANGE_10 = ClipRange(-(1 shl 17), (1 shl 17) - 1, false)

        /** The row-pass clip range for 12-bit samples. */
        val ROW_CLIP_RANGE_12 = ClipRange(-(1 shl 19), (1 shl 19) - 1, false)

        /** The column-pass clip range for 8-bit samples. */
        val COLUMN_CLIP_RANGE_8: ClipRange = ROW_CLIP_RANGE_8

        /** The column-pass clip range for 10-bit samples. */
        val COLUMN_CLIP_RANGE_10: ClipRange = ROW_CLIP_RANGE_8

        /** The column-pass clip range for 12-bit samples. */
        val COLUMN_CLIP_RANGE_12: ClipRange = ROW_CLIP_RANGE_10

        /** Returns the transform area in samples.
         * 
         * @param transformSize the transform size to validate
         * @return the transform area in samples
         */
        fun checkedTransformArea(transformSize: TransformSize): Int {
            return transformSize.widthPixels() * transformSize.heightPixels()
        }
    }
}
