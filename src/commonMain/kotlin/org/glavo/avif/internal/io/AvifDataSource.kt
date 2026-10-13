// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif.internal.io

import org.glavo.avif.AvifDecodeException
import org.glavo.avif.AvifErrorCode
import org.glavo.avif.internal.compat.AvifIoException
import org.glavo.avif.internal.compat.ByteBuf

abstract class AvifDataSource : AutoCloseable {
    abstract fun limit(): Long
    abstract fun isSeekable(): Boolean
    abstract fun readByte(position: Long): Byte
    abstract fun readFully(position: Long, destination: ByteArray, destOffset: Int, length: Int)

    fun readFully(position: Long, destination: ByteArray) {
        readFully(position, destination, 0, destination.size)
    }

    fun readFully(position: Long, destination: ByteBuf) {
        val length = destination.remaining()
        if (length == 0) return
        val bytes = ByteArray(length)
        readFully(position, bytes, 0, length)
        destination.put(bytes)
    }

    fun readBytes(position: Long, length: Int): ByteArray {
        if (length < 0) {
            throw IllegalArgumentException("length < 0: $length")
        }
        val result = ByteArray(length)
        readFully(position, result)
        return result
    }

    companion object {
        fun ofBytes(bytes: ByteArray): AvifDataSource = MemorySource(bytes)

        internal fun seekableSourceRequired(position: Long): AvifDecodeException {
            return AvifDecodeException(
                AvifErrorCode.SEEKABLE_SOURCE_REQUIRED,
                "AVIF input requires backward access at byte offset $position; use ByteArray input",
                position,
            )
        }
    }

    class MemorySource(private val bytes: ByteArray) : AvifDataSource() {
        var closed: Boolean = false

        override fun limit(): Long = bytes.size.toLong()

        override fun isSeekable(): Boolean = true

        override fun readByte(position: Long): Byte {
            ensureRange(position, 1L)
            return bytes[position.toInt()]
        }

        override fun readFully(position: Long, destination: ByteArray, destOffset: Int, length: Int) {
            ensureRange(position, length.toLong())
            if (length == 0) return
            bytes.copyInto(destination, destOffset, position.toInt(), position.toInt() + length)
        }

        override fun close() {
            closed = true
        }

        fun ensureRange(position: Long, length: Long) {
            if (closed) {
                throw AvifIoException("AVIF data source is closed")
            }
            val size = bytes.size.toLong()
            if (position < 0 || length < 0 || position > size || length > size - position) {
                throw AvifIoException("Source range outside seekable input: $position + $length")
            }
        }
    }
}
