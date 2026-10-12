// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.output

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane


/** Converts complete decoded surfaces into opaque ARGB pixel arrays.
 * 
 * Conversion covers every stored luma sample and uses point-sampled chroma expansion for `YUV420`,
 * `YUV422`, and `YUV444`. AV1 render dimensions are display hints and do not crop or resample the
 * decoded planes. */
object ArgbOutput {
    /** Converts one decoded-plane snapshot into opaque ARGB pixels.
     * 
     * @param decodedPlanes the decoded planes to convert
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return packed opaque non-premultiplied ARGB pixels in presentation order
     */
    fun toOpaqueArgbPixels(decodedPlanes: DecodedSurface, transform: YuvToRgbTransform): IntArray {
        val checkedDecodedPlanes = requireIntOutputDecodedPlanes(decodedPlanes)
        val checkedTransform = requireNotNullCompat(transform, "transform")

        val outputWidth = checkedDecodedPlanes.codedWidth()
        val outputHeight = checkedDecodedPlanes.codedHeight()
        val pixelCount = checkedPixelCount(outputWidth, outputHeight)
        val pixels = IntArray(pixelCount)

        val chromaFormat = checkedDecodedPlanes.chromaFormat()
        val bitDepth = checkedDecodedPlanes.bitDepth()
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME -> convertOpaqueI400(
                checkedDecodedPlanes.lumaPlane(),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )

            Av1ChromaFormat.YUV420 -> convertOpaqueI420(
                checkedDecodedPlanes.lumaPlane(),
                requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )

            Av1ChromaFormat.YUV422 -> convertOpaqueI422(
                checkedDecodedPlanes.lumaPlane(),
                requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )

            Av1ChromaFormat.YUV444 -> convertOpaqueI444(
                checkedDecodedPlanes.lumaPlane(),
                requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )
        }
    }

    /** Converts one decoded-plane snapshot into opaque 16-bit-per-channel ARGB pixels.
     * 
     * @param decodedPlanes the decoded planes to convert
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return packed opaque non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format
     */
    fun toOpaqueArgbLongPixels(
        decodedPlanes: DecodedSurface,
        transform: YuvToRgbTransform
    ): LongArray {
        val checkedDecodedPlanes = requireLongOutputDecodedPlanes(decodedPlanes)
        val checkedTransform = requireNotNullCompat(transform, "transform")

        val outputWidth = checkedDecodedPlanes.codedWidth()
        val outputHeight = checkedDecodedPlanes.codedHeight()
        val pixelCount = checkedPixelCount(outputWidth, outputHeight)
        val pixels = LongArray(pixelCount)

        val chromaFormat = checkedDecodedPlanes.chromaFormat()
        val bitDepth = checkedDecodedPlanes.bitDepth()
        return when (chromaFormat) {
            Av1ChromaFormat.MONOCHROME -> convertOpaqueLongI400(
                checkedDecodedPlanes.lumaPlane(),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )

            Av1ChromaFormat.YUV420 -> convertOpaqueLongI420(
                checkedDecodedPlanes.lumaPlane(),
                requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )

            Av1ChromaFormat.YUV422 -> convertOpaqueLongI422(
                checkedDecodedPlanes.lumaPlane(),
                requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )

            Av1ChromaFormat.YUV444 -> convertOpaqueLongI444(
                checkedDecodedPlanes.lumaPlane(),
                requireChromaPlane(checkedDecodedPlanes.chromaUPlane(), "chromaUPlane"),
                requireChromaPlane(checkedDecodedPlanes.chromaVPlane(), "chromaVPlane"),
                outputWidth,
                outputHeight,
                bitDepth,
                pixels,
                checkedTransform
            )
        }
    }

    /** Validates that one decoded-plane snapshot is supported by 8-bit output.
     * 
     * @param decodedPlanes the decoded planes to validate
     * @return the validated decoded planes
     */
    private fun requireIntOutputDecodedPlanes(decodedPlanes: DecodedSurface): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        require(!(checkedDecodedPlanes.bitDepth() != 8 && checkedDecodedPlanes.bitDepth() != 10 && checkedDecodedPlanes.bitDepth() != 12 && checkedDecodedPlanes.bitDepth() != 16)) {
            ("8-bit ARGB output requires 8-bit, 10-bit, 12-bit, or 16-bit decoded planes: "
                    + checkedDecodedPlanes.bitDepth())
        }
        return checkedDecodedPlanes
    }

    /** Validates that one decoded-plane snapshot is supported by high-bit-depth output.
     * 
     * @param decodedPlanes the decoded planes to validate
     * @return the validated decoded planes
     */
    private fun requireLongOutputDecodedPlanes(decodedPlanes: DecodedSurface): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        require(!(checkedDecodedPlanes.bitDepth() != 8 && checkedDecodedPlanes.bitDepth() != 10 && checkedDecodedPlanes.bitDepth() != 12 && checkedDecodedPlanes.bitDepth() != 16)) {
            ("High-bit-depth ARGB output requires 8-bit, 10-bit, 12-bit, or 16-bit decoded planes: "
                    + checkedDecodedPlanes.bitDepth())
        }
        return checkedDecodedPlanes
    }

    /** Validates one output size and returns its pixel count.
     * 
     * @param outputWidth the output width
     * @param outputHeight the output height
     * @return the output pixel count
     */
    private fun checkedPixelCount(outputWidth: Int, outputHeight: Int): Int {
        try {
            return multiplyExact(outputWidth, outputHeight)
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("output area is too large", exception)
        }
    }

    /** Returns one required chroma plane or throws an explanatory failure.
     * 
     * @param plane the candidate chroma plane
     * @param name the argument name used in the failure message
     * @return the required chroma plane
     */
    private fun requireChromaPlane(plane: PaddedPlane?, name: String): PaddedPlane {
        requireNotNull(plane) { name + " is required for chroma ARGB output" }
        return plane
    }

    /** Converts one monochrome plane snapshot into opaque grayscale ARGB pixels.
     * 
     * @param lumaPlane the decoded luma plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for grayscale expansion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueI400(
        lumaPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: IntArray,
        transform: YuvToRgbTransform
    ): IntArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val pixelRow = y * outputWidth
            for (x in 0..<outputWidth) {
                pixels[pixelRow + x] = transform.toOpaqueGrayArgb(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }

    /** Converts one monochrome plane snapshot into opaque 16-bit-per-channel grayscale ARGB pixels.
     * 
     * @param lumaPlane the decoded luma plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for grayscale expansion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueLongI400(
        lumaPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: LongArray,
        transform: YuvToRgbTransform
    ): LongArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val pixelRow = y * outputWidth
            for (x in 0..<outputWidth) {
                pixels[pixelRow + x] = transform.toOpaqueGrayArgb64(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }

    /** Converts one `YUV420` plane snapshot into opaque ARGB pixels with point-sampled chroma.
     * 
     * @param lumaPlane the decoded luma plane
     * @param chromaUPlane the decoded chroma U plane
     * @param chromaVPlane the decoded chroma V plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueI420(
        lumaPlane: PaddedPlane,
        chromaUPlane: PaddedPlane,
        chromaVPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: IntArray,
        transform: YuvToRgbTransform
    ): IntArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val chromaUSamples = chromaUPlane.sampleBuffer()
        val chromaVSamples = chromaVPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        val chromaUStride = chromaUPlane.stride()
        val chromaVStride = chromaVPlane.stride()

        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val chromaURow = (y shr 1) * chromaUStride
            val chromaVRow = (y shr 1) * chromaVStride
            val pixelRow = y * outputWidth

            var x = 0
            while (x + 1 < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                val uSample = chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF
                val vSample = chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF

                pixels[pixelRow + x] = transform.toOpaqueArgb(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb(
                    lumaSamples.get(lumaRow + x + 1).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                x += 2
            }

            if (x < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF,
                    chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }

    /** Converts one `YUV420` plane snapshot into opaque 16-bit-per-channel ARGB pixels with
     * point-sampled chroma.
     * 
     * @param lumaPlane the decoded luma plane
     * @param chromaUPlane the decoded chroma U plane
     * @param chromaVPlane the decoded chroma V plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueLongI420(
        lumaPlane: PaddedPlane,
        chromaUPlane: PaddedPlane,
        chromaVPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: LongArray,
        transform: YuvToRgbTransform
    ): LongArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val chromaUSamples = chromaUPlane.sampleBuffer()
        val chromaVSamples = chromaVPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        val chromaUStride = chromaUPlane.stride()
        val chromaVStride = chromaVPlane.stride()

        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val chromaURow = (y shr 1) * chromaUStride
            val chromaVRow = (y shr 1) * chromaVStride
            val pixelRow = y * outputWidth

            var x = 0
            while (x + 1 < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                val uSample = chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF
                val vSample = chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF

                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb64(
                    lumaSamples.get(lumaRow + x + 1).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                x += 2
            }

            if (x < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF,
                    chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }

    /** Converts one `YUV422` plane snapshot into opaque ARGB pixels with horizontally shared chroma.
     * 
     * @param lumaPlane the decoded luma plane
     * @param chromaUPlane the decoded chroma U plane
     * @param chromaVPlane the decoded chroma V plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueI422(
        lumaPlane: PaddedPlane,
        chromaUPlane: PaddedPlane,
        chromaVPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: IntArray,
        transform: YuvToRgbTransform
    ): IntArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val chromaUSamples = chromaUPlane.sampleBuffer()
        val chromaVSamples = chromaVPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        val chromaUStride = chromaUPlane.stride()
        val chromaVStride = chromaVPlane.stride()

        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val chromaURow = y * chromaUStride
            val chromaVRow = y * chromaVStride
            val pixelRow = y * outputWidth

            var x = 0
            while (x + 1 < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                val uSample = chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF
                val vSample = chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF

                pixels[pixelRow + x] = transform.toOpaqueArgb(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb(
                    lumaSamples.get(lumaRow + x + 1).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                x += 2
            }

            if (x < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF,
                    chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }

    /** Converts one `YUV422` plane snapshot into opaque 16-bit-per-channel ARGB pixels with
     * horizontally shared chroma.
     * 
     * @param lumaPlane the decoded luma plane
     * @param chromaUPlane the decoded chroma U plane
     * @param chromaVPlane the decoded chroma V plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueLongI422(
        lumaPlane: PaddedPlane,
        chromaUPlane: PaddedPlane,
        chromaVPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: LongArray,
        transform: YuvToRgbTransform
    ): LongArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val chromaUSamples = chromaUPlane.sampleBuffer()
        val chromaVSamples = chromaVPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        val chromaUStride = chromaUPlane.stride()
        val chromaVStride = chromaVPlane.stride()

        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val chromaURow = y * chromaUStride
            val chromaVRow = y * chromaVStride
            val pixelRow = y * outputWidth

            var x = 0
            while (x + 1 < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                val uSample = chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF
                val vSample = chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF

                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                pixels[pixelRow + x + 1] = transform.toOpaqueArgb64(
                    lumaSamples.get(lumaRow + x + 1).toInt() and 0xFFFF,
                    uSample,
                    vSample,
                    bitDepth
                )
                x += 2
            }

            if (x < outputWidth) {
                val chromaIndexU = chromaURow + (x shr 1)
                val chromaIndexV = chromaVRow + (x shr 1)
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    chromaUSamples.get(chromaIndexU).toInt() and 0xFFFF,
                    chromaVSamples.get(chromaIndexV).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }

    /** Converts one `YUV444` plane snapshot into opaque ARGB pixels with one chroma sample per pixel.
     * 
     * @param lumaPlane the decoded luma plane
     * @param chromaUPlane the decoded chroma U plane
     * @param chromaVPlane the decoded chroma V plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueI444(
        lumaPlane: PaddedPlane,
        chromaUPlane: PaddedPlane,
        chromaVPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: IntArray,
        transform: YuvToRgbTransform
    ): IntArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val chromaUSamples = chromaUPlane.sampleBuffer()
        val chromaVSamples = chromaVPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        val chromaUStride = chromaUPlane.stride()
        val chromaVStride = chromaVPlane.stride()

        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val chromaURow = y * chromaUStride
            val chromaVRow = y * chromaVStride
            val pixelRow = y * outputWidth
            for (x in 0..<outputWidth) {
                pixels[pixelRow + x] = transform.toOpaqueArgb(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    chromaUSamples.get(chromaURow + x).toInt() and 0xFFFF,
                    chromaVSamples.get(chromaVRow + x).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }

    /** Converts one `YUV444` plane snapshot into opaque 16-bit-per-channel ARGB pixels with one
     * chroma sample per luma sample.
     * 
     * @param lumaPlane the decoded luma plane
     * @param chromaUPlane the decoded chroma U plane
     * @param chromaVPlane the decoded chroma V plane
     * @param outputWidth the decoded output width
     * @param outputHeight the decoded output height
     * @param bitDepth the decoded sample bit depth
     * @param pixels the destination ARGB pixel buffer
     * @param transform the fixed-point YUV-to-RGB transform used for color conversion
     * @return the filled destination pixel buffer
     */
    private fun convertOpaqueLongI444(
        lumaPlane: PaddedPlane,
        chromaUPlane: PaddedPlane,
        chromaVPlane: PaddedPlane,
        outputWidth: Int,
        outputHeight: Int,
        bitDepth: Int,
        pixels: LongArray,
        transform: YuvToRgbTransform
    ): LongArray {
        val lumaSamples = lumaPlane.sampleBuffer()
        val chromaUSamples = chromaUPlane.sampleBuffer()
        val chromaVSamples = chromaVPlane.sampleBuffer()
        val lumaStride = lumaPlane.stride()
        val chromaUStride = chromaUPlane.stride()
        val chromaVStride = chromaVPlane.stride()

        for (y in 0..<outputHeight) {
            val lumaRow = y * lumaStride
            val chromaURow = y * chromaUStride
            val chromaVRow = y * chromaVStride
            val pixelRow = y * outputWidth
            for (x in 0..<outputWidth) {
                pixels[pixelRow + x] = transform.toOpaqueArgb64(
                    lumaSamples.get(lumaRow + x).toInt() and 0xFFFF,
                    chromaUSamples.get(chromaURow + x).toInt() and 0xFFFF,
                    chromaVSamples.get(chromaVRow + x).toInt() and 0xFFFF,
                    bitDepth
                )
            }
        }
        return pixels
    }
}
