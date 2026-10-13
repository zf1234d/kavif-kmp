// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif.internal.io

import org.glavo.avif.internal.compat.AutoAvifCloseable
import org.glavo.avif.internal.compat.AvifIoException
import org.glavo.avif.internal.compat.ByteBuf
import org.glavo.avif.internal.compat.EOFException
import org.glavo.avif.internal.compat.toUnsignedInt
import org.glavo.avif.internal.compat.toUnsignedLong
import org.glavo.avif.internal.compat.shortToUnsignedInt
import org.glavo.avif.internal.compat.toIntExact
import kotlin.math.min

abstract class BufferedInput : AutoAvifCloseable {
    protected val buffer: ByteBuf
    protected var closed: Boolean = false

    protected constructor(bufferSize: Int = DEFAULT_BUFFER_SIZE, direct: Boolean = false) {
        require(bufferSize > 0) { "bufferSize <= 0: $bufferSize" }
        this.buffer = ByteBuf.allocate(bufferSize)
        this.buffer.limit(0)
    }

    protected constructor(buffer: ByteBuf) {
        this.buffer = buffer
    }

    protected fun ensureOpen() {
        if (closed) throw AvifIoException("BufferedInput is closed")
    }

    fun ensureBufferRemaining(required: Int) {
        ensureOpen()
        require(required >= 0) { "required < 0: $required" }
        if (buffer.remaining() < required) {
            fillBuffer(required)
            if (buffer.remaining() < required) throw unexpectedEndOfInput()
        }
    }

    protected abstract fun fillBuffer(required: Int)

    protected fun prepareForFill(required: Int): ByteBuf {
        ensureOpen()
        require(required <= buffer.capacity()) {
            "required exceeds internal buffer capacity: $required > ${buffer.capacity()}"
        }
        buffer.compact()
        return buffer
    }

    protected fun skipBufferedBytes(len: Long): Long {
        val skipped = min(len, buffer.remaining().toLong()).toInt()
        buffer.position(buffer.position() + skipped)
        return skipped.toLong()
    }

    protected fun clearBuffer() {
        buffer.position(0)
        buffer.limit(0)
    }

    fun readByteArray(len: Int): ByteArray {
        if (len < 0 || len >= Int.MAX_VALUE - 8) {
            throw AvifIoException("Array length too large: $len")
        }
        if (len == 0) return ByteArray(0)
        ensureOpen()
        val result = ByteArray(len)
        var offset = 0
        while (offset < len) {
            if (!buffer.hasRemaining()) {
                val request = if (buffer.capacity() == 0) 1 else min(len - offset, buffer.capacity())
                ensureBufferRemaining(request)
            }
            val chunk = min(buffer.remaining(), len - offset)
            buffer.get(result, offset, chunk)
            offset += chunk
        }
        return result
    }

    open fun skip(len: Long) {
        require(len >= 0) { "len < 0: $len" }
        if (len == 0L) {
            ensureOpen()
            return
        }
        ensureOpen()
        var remaining = len - skipBufferedBytes(len)
        while (remaining > 0) {
            if (!buffer.hasRemaining()) {
                val request = if (buffer.capacity() == 0) 1 else min(remaining, buffer.capacity().toLong()).toInt()
                ensureBufferRemaining(request)
            }
            remaining -= skipBufferedBytes(remaining)
        }
    }

    fun readByte(): Byte {
        ensureBufferRemaining(1)
        return buffer.get()
    }

    fun readUnsignedByte(): Int = toUnsignedInt(readByte())

    fun readShortLE(): Short {
        ensureBufferRemaining(2)
        return buffer.getShort()
    }

    fun readUnsignedShortLE(): Int = shortToUnsignedInt(readShortLE())

    fun readUnsignedInt24LE(): Int {
        ensureBufferRemaining(3)
        return toUnsignedInt(buffer.get()) or
            (toUnsignedInt(buffer.get()) shl 8) or
            (toUnsignedInt(buffer.get()) shl 16)
    }

    fun readIntLE(): Int {
        ensureBufferRemaining(4)
        return buffer.getInt()
    }

    fun readUnsignedIntLE(): Long = toUnsignedLong(readIntLE())

    fun readLongLE(): Long {
        ensureBufferRemaining(8)
        return buffer.getLong()
    }

    open fun currentUnitRemaining(): Long {
        ensureOpen()
        return -1L
    }

    fun read(destination: ByteBuf): Int {
        ensureOpen()
        if (!destination.hasRemaining()) return 0
        if (!buffer.hasRemaining()) {
            try {
                ensureBufferRemaining(1)
            } catch (_: EOFException) {
                return -1
            }
        }
        val count = min(destination.remaining(), buffer.remaining())
        val originalLimit = buffer.limit()
        buffer.limit(buffer.position() + count)
        try {
            destination.put(buffer)
        } finally {
            buffer.limit(originalLimit)
        }
        return count
    }

    fun isOpen(): Boolean = !closed

    class OfByteBuf(source: ByteBuf) : BufferedInput(source.slice()) {
        override fun fillBuffer(required: Int) {
            throw unexpectedEndOfInput()
        }

        override fun currentUnitRemaining(): Long {
            ensureOpen()
            return buffer.remaining().toLong()
        }

        override fun skip(len: Long) {
            require(len >= 0) { "len < 0: $len" }
            if (len == 0L) {
                ensureOpen()
                return
            }
            ensureOpen()
            if (len > buffer.remaining()) throw unexpectedEndOfInput()
            buffer.position(buffer.position() + toIntExact(len))
        }

        override fun close() {
            closed = true
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192

        fun unexpectedEndOfInput(): EOFException = EOFException("Unexpected end of input")
    }
}
