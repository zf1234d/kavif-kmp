// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif

import org.glavo.avif.internal.PixelBuffers

class AvifFrame {
    private val width: Int
    private val height: Int
    private val bitDepth: AvifBitDepth
    private val chromaFormat: Av1ChromaFormat
    private val frameIndex: Int
    private val pixelFormat: AvifPixelFormat
    private var intPixels: IntArray?
    private var longPixels: LongArray?

    constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat,
        frameIndex: Int,
        pixels: IntArray,
    ) : this(width, height, bitDepth, chromaFormat, frameIndex, PixelBuffers.immutableIntPixels(pixels), null)

    constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat,
        frameIndex: Int,
        pixels: LongArray,
    ) : this(width, height, bitDepth, chromaFormat, frameIndex, null, PixelBuffers.immutableLongPixels(pixels))

    private constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat,
        frameIndex: Int,
        intPixels: IntArray?,
        longPixels: LongArray?,
    ) {
        if (intPixels == null && longPixels == null) {
            throw IllegalArgumentException("At least one pixel representation is required")
        }
        val pixelCount = checkedPixelCount(width, height)
        if (intPixels != null && intPixels.size != pixelCount) {
            throw IllegalArgumentException("Int pixel count does not match dimensions: ${intPixels.size}")
        }
        if (longPixels != null && longPixels.size != pixelCount) {
            throw IllegalArgumentException("Long pixel count does not match dimensions: ${longPixels.size}")
        }
        if (frameIndex < 0) {
            throw IllegalArgumentException("frameIndex < 0: $frameIndex")
        }
        this.width = width
        this.height = height
        this.bitDepth = bitDepth
        this.chromaFormat = chromaFormat
        this.frameIndex = frameIndex
        this.pixelFormat = if (intPixels != null) AvifPixelFormat.ARGB_8888 else AvifPixelFormat.ARGB_16161616
        this.intPixels = intPixels
        this.longPixels = longPixels
    }

    fun width(): Int = width
    fun height(): Int = height
    fun bitDepth(): AvifBitDepth = bitDepth
    fun chromaFormat(): Av1ChromaFormat = chromaFormat
    fun frameIndex(): Int = frameIndex
    fun pixelFormat(): AvifPixelFormat = pixelFormat

    fun intPixels(): IntArray = requireIntPixels().copyOf()

    fun longPixels(): LongArray = requireLongPixels().copyOf()

    private fun requireIntPixels(): IntArray {
        var pixels = intPixels
        if (pixels == null) {
            pixels = PixelBuffers.convertLongPixelsToIntPixels(requireLongPixels())
            intPixels = pixels
        }
        return pixels
    }

    private fun requireLongPixels(): LongArray {
        var pixels = longPixels
        if (pixels == null) {
            pixels = PixelBuffers.convertIntPixelsToLongPixels(requireIntPixels())
            longPixels = pixels
        }
        return pixels
    }

    companion object {
        fun fromOwnedPixels(
            width: Int,
            height: Int,
            bitDepth: AvifBitDepth,
            chromaFormat: Av1ChromaFormat,
            frameIndex: Int,
            pixels: IntArray,
        ): AvifFrame {
            return AvifFrame(width, height, bitDepth, chromaFormat, frameIndex, pixels, null)
        }

        fun fromOwnedPixels(
            width: Int,
            height: Int,
            bitDepth: AvifBitDepth,
            chromaFormat: Av1ChromaFormat,
            frameIndex: Int,
            pixels: LongArray,
        ): AvifFrame {
            return AvifFrame(width, height, bitDepth, chromaFormat, frameIndex, null, pixels)
        }

        fun checkedPixelCount(width: Int, height: Int): Int {
            if (width <= 0) throw IllegalArgumentException("width <= 0: $width")
            if (height <= 0) throw IllegalArgumentException("height <= 0: $height")
            val pixelCount = width.toLong() * height
            if (pixelCount > Int.MAX_VALUE) {
                throw IllegalArgumentException("Frame dimensions are too large: ${width}x$height")
            }
            return pixelCount.toInt()
        }
    }
}
