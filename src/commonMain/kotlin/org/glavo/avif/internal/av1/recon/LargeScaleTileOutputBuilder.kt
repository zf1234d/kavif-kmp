// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane


/** Builds one Large Scale Tile output frame from decoded camera-frame tile regions. */
class LargeScaleTileOutputBuilder(
    bitDepth: Int,
    chromaFormat: Av1ChromaFormat,
    tileWidth: Int,
    tileHeight: Int,
    outputTileColumns: Int,
    outputTileRows: Int
) {
    /** The output sample bit depth. */
    private val bitDepth: Int

    /** The output chroma layout. */
    private val chromaFormat: Av1ChromaFormat

    /** The source and destination tile width in luma samples. */
    private val tileWidth: Int

    /** The source and destination tile height in luma samples. */
    private val tileHeight: Int

    /** The output tile-column count. */
    private val outputTileColumns: Int

    /** The output tile-row count. */
    private val outputTileRows: Int

    /** The output luma width. */
    private val outputWidth: Int

    /** The output luma height. */
    private val outputHeight: Int

    /** The mutable output luma samples. */
    private val lumaSamples: ShortArray

    /** The mutable output U samples, or `null` for monochrome output. */
    private val chromaUSamples: ShortArray?

    /** The mutable output V samples, or `null` for monochrome output. */
    private val chromaVSamples: ShortArray?

    /** Whether ownership of the output arrays has been transferred. */
    private var built = false

    init {
        require(!(bitDepth != 8 && bitDepth != 10 && bitDepth != 12)) { "Unsupported bitDepth: $bitDepth" }
        require(!(tileWidth <= 0 || tileHeight <= 0)) { "Tile dimensions must be positive" }
        require(!(outputTileColumns <= 0 || outputTileRows <= 0)) { "Output tile dimensions must be positive" }
        this.bitDepth = bitDepth
        this.chromaFormat = requireNotNullCompat(chromaFormat, "chromaFormat")
        this.tileWidth = tileWidth
        this.tileHeight = tileHeight
        this.outputTileColumns = outputTileColumns
        this.outputTileRows = outputTileRows
        this.outputWidth = multiplyExact(tileWidth, outputTileColumns)
        this.outputHeight = multiplyExact(tileHeight, outputTileRows)
        this.lumaSamples = ShortArray(multiplyExact(outputWidth, outputHeight))

        val chromaWidth: Int = chromaWidth(chromaFormat, outputWidth)
        val chromaHeight: Int = chromaHeight(chromaFormat, outputHeight)
        if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
            this.chromaUSamples = null
            this.chromaVSamples = null
        } else {
            val chromaLength = multiplyExact(chromaWidth, chromaHeight)
            this.chromaUSamples = ShortArray(chromaLength)
            this.chromaVSamples = ShortArray(chromaLength)
        }
    }

    /** Copies one decoded source tile into an output raster position.
     * 
     * @param source the reconstructed common camera frame containing the decoded tile
     * @param sourceTileColumn the source tile column
     * @param sourceTileRow the source tile row
     * @param outputTileIndex the zero-based destination tile index in raster order
     */
    fun copyTile(
        source: DecodedSurface,
        sourceTileColumn: Int,
        sourceTileRow: Int,
        outputTileIndex: Int
    ) {
        ensureMutable()
        val checkedSource = requireNotNullCompat(source, "source")
        require(!(checkedSource.bitDepth() != bitDepth || checkedSource.chromaFormat() != chromaFormat)) { "Source tile color configuration differs from the output" }
        require(!(sourceTileColumn < 0 || sourceTileRow < 0)) { "Source tile coordinates must be non-negative" }
        val outputTileCount = multiplyExact(outputTileColumns, outputTileRows)
        require(!(outputTileIndex < 0 || outputTileIndex >= outputTileCount)) { "outputTileIndex out of range: $outputTileIndex" }

        val sourceX = multiplyExact(sourceTileColumn, tileWidth)
        val sourceY = multiplyExact(sourceTileRow, tileHeight)
        require(
            !(sourceX + tileWidth > checkedSource.codedWidth()
                    || sourceY + tileHeight > checkedSource.codedHeight())
        ) { "Source tile exceeds the reconstructed camera frame" }
        val destinationX = outputTileIndex % outputTileColumns * tileWidth
        val destinationY = outputTileIndex / outputTileColumns * tileHeight
        copyPlane(
            checkedSource.lumaPlane(),
            sourceX,
            sourceY,
            tileWidth,
            tileHeight,
            lumaSamples,
            outputWidth,
            destinationX,
            destinationY
        )

        if (chromaFormat != Av1ChromaFormat.MONOCHROME) {
            val chromaTileWidth: Int = chromaWidth(chromaFormat, tileWidth)
            val chromaTileHeight: Int = chromaHeight(chromaFormat, tileHeight)
            val sourceChromaX: Int = chromaWidth(chromaFormat, sourceX)
            val sourceChromaY: Int = chromaHeight(chromaFormat, sourceY)
            val destinationChromaX: Int = chromaWidth(chromaFormat, destinationX)
            val destinationChromaY: Int = chromaHeight(chromaFormat, destinationY)
            val outputChromaWidth: Int = chromaWidth(chromaFormat, outputWidth)
            copyPlane(
                requireNotNullCompat(
                    checkedSource.chromaUPlane(),
                    "source.chromaUPlane"
                ),
                sourceChromaX,
                sourceChromaY,
                chromaTileWidth,
                chromaTileHeight,
                requireNotNullCompat(chromaUSamples, "chromaUSamples"),
                outputChromaWidth,
                destinationChromaX,
                destinationChromaY
            )
            copyPlane(
                requireNotNullCompat(
                    checkedSource.chromaVPlane(),
                    "source.chromaVPlane"
                ),
                sourceChromaX,
                sourceChromaY,
                chromaTileWidth,
                chromaTileHeight,
                requireNotNullCompat(chromaVSamples, "chromaVSamples"),
                outputChromaWidth,
                destinationChromaX,
                destinationChromaY
            )
        }
    }

    /** Copies one previously written output tile to another output raster position.
     * 
     * @param sourceOutputTileIndex the zero-based source tile index in raster order
     * @param outputTileIndex the zero-based destination tile index in raster order
     */
    fun copyOutputTile(sourceOutputTileIndex: Int, outputTileIndex: Int) {
        ensureMutable()
        validateOutputTileIndex(sourceOutputTileIndex, "sourceOutputTileIndex")
        validateOutputTileIndex(outputTileIndex, "outputTileIndex")
        if (sourceOutputTileIndex == outputTileIndex) {
            return
        }

        val sourceX = sourceOutputTileIndex % outputTileColumns * tileWidth
        val sourceY = sourceOutputTileIndex / outputTileColumns * tileHeight
        val destinationX = outputTileIndex % outputTileColumns * tileWidth
        val destinationY = outputTileIndex / outputTileColumns * tileHeight
        copyStoredPlane(
            lumaSamples,
            outputWidth,
            sourceX,
            sourceY,
            tileWidth,
            tileHeight,
            destinationX,
            destinationY
        )

        if (chromaFormat != Av1ChromaFormat.MONOCHROME) {
            val chromaTileWidth: Int = chromaWidth(chromaFormat, tileWidth)
            val chromaTileHeight: Int = chromaHeight(chromaFormat, tileHeight)
            val outputChromaWidth: Int = chromaWidth(chromaFormat, outputWidth)
            val sourceChromaX: Int = chromaWidth(chromaFormat, sourceX)
            val sourceChromaY: Int = chromaHeight(chromaFormat, sourceY)
            val destinationChromaX: Int = chromaWidth(chromaFormat, destinationX)
            val destinationChromaY: Int = chromaHeight(chromaFormat, destinationY)
            copyStoredPlane(
                requireNotNullCompat(chromaUSamples, "chromaUSamples"),
                outputChromaWidth,
                sourceChromaX,
                sourceChromaY,
                chromaTileWidth,
                chromaTileHeight,
                destinationChromaX,
                destinationChromaY
            )
            copyStoredPlane(
                requireNotNullCompat(chromaVSamples, "chromaVSamples"),
                outputChromaWidth,
                sourceChromaX,
                sourceChromaY,
                chromaTileWidth,
                chromaTileHeight,
                destinationChromaX,
                destinationChromaY
            )
        }
    }

    /** Transfers the accumulated samples into one immutable decoded-plane snapshot.
     * 
     * This builder must not be used after this method returns.
     * 
     * @return the assembled output frame
     */
    fun build(): DecodedSurface {
        ensureMutable()
        built = true
        val luma: PaddedPlane = PaddedPlane.fromOwnedSamples(
            outputWidth,
            outputHeight,
            outputWidth,
            lumaSamples
        )
        var chromaU: PaddedPlane? = null
        var chromaV: PaddedPlane? = null
        if (chromaFormat != Av1ChromaFormat.MONOCHROME) {
            val width: Int = chromaWidth(chromaFormat, outputWidth)
            val height: Int = chromaHeight(chromaFormat, outputHeight)
            chromaU = PaddedPlane.fromOwnedSamples(
                width,
                height,
                width,
                requireNotNullCompat(chromaUSamples, "chromaUSamples")
            )
            chromaV = PaddedPlane.fromOwnedSamples(
                width,
                height,
                width,
                requireNotNullCompat(chromaVSamples, "chromaVSamples")
            )
        }
        return DecodedSurface(
            bitDepth,
            chromaFormat,
            outputWidth,
            outputHeight,
            outputWidth,
            outputHeight,
            luma,
            chromaU,
            chromaV
        )
    }

    /** Validates one output tile index.
     * 
     * @param outputTileIndex the index to validate
     * @param parameterName the parameter name used in an exception message
     */
    private fun validateOutputTileIndex(outputTileIndex: Int, parameterName: String) {
        val outputTileCount = multiplyExact(outputTileColumns, outputTileRows)
        require(!(outputTileIndex < 0 || outputTileIndex >= outputTileCount)) { parameterName + " out of range: $outputTileIndex" }
    }

    /** Ensures that sample ownership has not already been transferred. */
    private fun ensureMutable() {
        check(!built) { "Large Scale Tile output has already been built" }
    }

    companion object {
        /** Copies one rectangular plane region into tightly packed destination storage.
         * 
         * @param source the immutable source plane
         * @param sourceX the source-region X coordinate
         * @param sourceY the source-region Y coordinate
         * @param width the copied width
         * @param height the copied height
         * @param destination the destination sample storage
         * @param destinationStride the destination row stride
         * @param destinationX the destination-region X coordinate
         * @param destinationY the destination-region Y coordinate
         */
        fun copyPlane(
            source: PaddedPlane,
            sourceX: Int,
            sourceY: Int,
            width: Int,
            height: Int,
            destination: ShortArray,
            destinationStride: Int,
            destinationX: Int,
            destinationY: Int
        ) {
            for (y in 0..<height) {
                val destinationOffset = (destinationY + y) * destinationStride + destinationX
                for (x in 0..<width) {
                    destination[destinationOffset + x] =
                        source.sample(sourceX + x, sourceY + y).toShort()
                }
            }
        }

        /** Copies one rectangular region within the builder's tightly packed plane storage.
         * 
         * @param samples the mutable plane storage
         * @param stride the plane row stride
         * @param sourceX the source-region X coordinate
         * @param sourceY the source-region Y coordinate
         * @param width the copied width
         * @param height the copied height
         * @param destinationX the destination-region X coordinate
         * @param destinationY the destination-region Y coordinate
         */
        fun copyStoredPlane(
            samples: ShortArray,
            stride: Int,
            sourceX: Int,
            sourceY: Int,
            width: Int,
            height: Int,
            destinationX: Int,
            destinationY: Int
        ) {
            for (y in 0..<height) {
                val sourceOffset = (sourceY + y) * stride + sourceX
                val destinationOffset = (destinationY + y) * stride + destinationX
                arraycopy(samples, sourceOffset, samples, destinationOffset, width)
            }
        }

        /** Returns the chroma-plane width for one luma width.
         * 
         * @param format the chroma layout
         * @param lumaWidth the luma width
         * @return the chroma width
         */
        fun chromaWidth(format: Av1ChromaFormat, lumaWidth: Int): Int {
            return when (format) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420, Av1ChromaFormat.YUV422 -> lumaWidth shr 1
                Av1ChromaFormat.YUV444 -> lumaWidth
            }
        }

        /** Returns the chroma-plane height for one luma height.
         * 
         * @param format the chroma layout
         * @param lumaHeight the luma height
         * @return the chroma height
         */
        fun chromaHeight(format: Av1ChromaFormat, lumaHeight: Int): Int {
            return when (format) {
                Av1ChromaFormat.MONOCHROME -> 0
                Av1ChromaFormat.YUV420 -> lumaHeight shr 1
                Av1ChromaFormat.YUV422, Av1ChromaFormat.YUV444 -> lumaHeight
            }
        }
    }
}
