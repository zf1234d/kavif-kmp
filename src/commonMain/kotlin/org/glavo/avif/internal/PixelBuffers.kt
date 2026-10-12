// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif.internal

import org.glavo.avif.internal.compat.IntBuf
import org.glavo.avif.internal.compat.LongBuf

object PixelBuffers {
    fun immutableIntPixels(pixels: IntArray): IntArray = pixels.copyOf()

    fun immutableLongPixels(pixels: LongArray): LongArray = pixels.copyOf()

    fun immutableIntPixels(pixels: IntBuf): IntBuf {
        val copy = IntArray(pixels.remaining())
        pixels.slice().get(copy)
        return IntBuf.wrap(copy).asReadOnlyBuffer()
    }

    fun immutableLongPixels(pixels: LongBuf): LongBuf {
        val copy = LongArray(pixels.remaining())
        pixels.slice().get(copy)
        return LongBuf.wrap(copy).asReadOnlyBuffer()
    }

    fun convertLongPixelsToIntPixels(pixels: LongBuf): IntBuf {
        val source = LongArray(pixels.remaining())
        pixels.slice().get(source)
        return IntBuf.wrap(convertLongPixelsToIntPixels(source)).asReadOnlyBuffer()
    }

    fun convertIntPixelsToLongPixels(pixels: IntBuf): LongBuf {
        val source = IntArray(pixels.remaining())
        pixels.slice().get(source)
        return LongBuf.wrap(convertIntPixelsToLongPixels(source)).asReadOnlyBuffer()
    }

    fun convertIntPixelsToLongPixels(pixels: IntArray): LongArray {
        val converted = LongArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            converted[i] = (byteToWord((pixel ushr 24) and 0xFF).toLong() shl 48) or
                (byteToWord((pixel ushr 16) and 0xFF).toLong() shl 32) or
                (byteToWord((pixel ushr 8) and 0xFF).toLong() shl 16) or
                byteToWord(pixel and 0xFF).toLong()
        }
        return converted
    }

    fun convertLongPixelsToIntPixels(pixels: LongArray): IntArray {
        val converted = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            converted[i] = (wordToByte(((pixel ushr 48) and 0xFFFFL).toInt()) shl 24) or
                (wordToByte(((pixel ushr 32) and 0xFFFFL).toInt()) shl 16) or
                (wordToByte(((pixel ushr 16) and 0xFFFFL).toInt()) shl 8) or
                wordToByte((pixel and 0xFFFFL).toInt())
        }
        return converted
    }

    private fun byteToWord(value: Int): Int = value * 257

    private fun wordToByte(value: Int): Int = (value * 255 + 32767) / 65535
}
