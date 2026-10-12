// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif

import org.glavo.avif.av1.Av1DecoderConfig
import org.glavo.avif.internal.io.AvifDataSource
import kotlin.math.min

class AvifImageReaderFactory private constructor(
    private val av1DecoderConfig: Av1DecoderConfig,
    private val outputPixelFormat: AvifPixelFormat?,
    private val inputSizeLimit: Long,
    private val metadataSizeLimit: Long,
) {
    fun av1DecoderConfig(): Av1DecoderConfig = av1DecoderConfig
    fun outputPixelFormat(): AvifPixelFormat? = outputPixelFormat
    fun inputSizeLimit(): Long = inputSizeLimit
    fun metadataSizeLimit(): Long = metadataSizeLimit

    fun withAv1DecoderConfig(value: Av1DecoderConfig): AvifImageReaderFactory {
        return if (value === av1DecoderConfig) this
        else AvifImageReaderFactory(value, outputPixelFormat, inputSizeLimit, metadataSizeLimit)
    }

    fun withOutputPixelFormat(value: AvifPixelFormat?): AvifImageReaderFactory {
        return if (value === outputPixelFormat) this
        else AvifImageReaderFactory(av1DecoderConfig, value, inputSizeLimit, metadataSizeLimit)
    }

    fun withInputSizeLimit(value: Long): AvifImageReaderFactory {
        if (value < 0) throw IllegalArgumentException("inputSizeLimit must be non-negative")
        return if (value == inputSizeLimit) this
        else AvifImageReaderFactory(av1DecoderConfig, outputPixelFormat, value, metadataSizeLimit)
    }

    fun withMetadataSizeLimit(value: Long): AvifImageReaderFactory {
        if (value < 0) throw IllegalArgumentException("metadataSizeLimit must be non-negative")
        return if (value == metadataSizeLimit) this
        else AvifImageReaderFactory(av1DecoderConfig, outputPixelFormat, inputSizeLimit, value)
    }

    fun open(source: ByteArray): AvifImageReader {
        validateInputSize(source.size.toLong())
        return AvifImageReader(AvifDataSource.ofBytes(source), this)
    }

    private fun validateInputSize(inputSize: Long) {
        if (inputSize > maximumInputSize()) {
            throw inputTooLarge(maximumInputSize())
        }
    }

    private fun maximumInputSize(): Long {
        return if (inputSizeLimit == 0L) MAX_SUPPORTED_INPUT_SIZE
        else min(inputSizeLimit, MAX_SUPPORTED_INPUT_SIZE)
    }

    companion object {
        private const val DEFAULT_INPUT_SIZE_LIMIT = 256L * 1024L * 1024L
        private const val DEFAULT_METADATA_SIZE_LIMIT = 64L * 1024L * 1024L
        private const val MAX_SUPPORTED_INPUT_SIZE = Int.MAX_VALUE - 8L

        val DEFAULT: AvifImageReaderFactory = AvifImageReaderFactory(
            Av1DecoderConfig.DEFAULT,
            null,
            DEFAULT_INPUT_SIZE_LIMIT,
            DEFAULT_METADATA_SIZE_LIMIT,
        )

        fun inputTooLarge(maximumInputSize: Long): AvifDecodeException {
            return AvifDecodeException(
                AvifErrorCode.INPUT_TOO_LARGE,
                "AVIF input exceeds supported size limit: $maximumInputSize bytes",
                null,
            )
        }
    }
}
