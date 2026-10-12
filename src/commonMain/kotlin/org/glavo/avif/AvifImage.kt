// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif

class AvifImage internal constructor(
    private val info: AvifImageInfo,
    private val frames: List<AvifFrame>,
) {
    init {
        if (frames.size != info.frameCount()) {
            throw IllegalArgumentException(
                "Decoded frame count does not match image metadata: ${frames.size} != ${info.frameCount()}",
            )
        }
    }

    fun info(): AvifImageInfo = info

    fun frames(): List<AvifFrame> = frames

    fun firstFrame(): AvifFrame = frames[0]

    companion object {
        fun read(source: ByteArray): AvifImage = read(source, AvifImageReaderFactory.DEFAULT)

        fun read(source: ByteArray, factory: AvifImageReaderFactory): AvifImage {
            factory.open(source).use { reader ->
                return collect(reader)
            }
        }

        fun collect(reader: AvifImageReader): AvifImage {
            val info = reader.info()
            return AvifImage(info, reader.readAllFrames())
        }
    }
}
