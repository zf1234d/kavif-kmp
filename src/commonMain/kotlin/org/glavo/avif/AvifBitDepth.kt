// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif

enum class AvifBitDepth(private val bits: Int, private val defaultPixelFormat: AvifPixelFormat) {
    EIGHT_BITS(8, AvifPixelFormat.ARGB_8888),
    TEN_BITS(10, AvifPixelFormat.ARGB_16161616),
    TWELVE_BITS(12, AvifPixelFormat.ARGB_16161616),
    SIXTEEN_BITS(16, AvifPixelFormat.ARGB_16161616);

    fun bits(): Int = bits

    fun maxSampleValue(): Int = (1 shl bits) - 1

    fun defaultPixelFormat(): AvifPixelFormat = defaultPixelFormat

    fun isEightBit(): Boolean = this == EIGHT_BITS

    fun isHighBitDepth(): Boolean = this != EIGHT_BITS

    override fun toString(): String = bits.toString()

    companion object {
        fun fromBits(bits: Int): AvifBitDepth {
            return when (bits) {
                8 -> EIGHT_BITS
                10 -> TEN_BITS
                12 -> TWELVE_BITS
                16 -> SIXTEEN_BITS
                else -> throw IllegalArgumentException("Unsupported bit depth: $bits")
            }
        }
    }
}
