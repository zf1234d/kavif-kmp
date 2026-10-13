// Compatibility buffers replacing java.nio for Kotlin Multiplatform.
// SPDX-License-Identifier: Apache-2.0
package org.glavo.avif.internal.compat

class ByteBuf private constructor(
    private val data: ByteArray,
    private val arrayOffsetValue: Int,
    private var pos: Int,
    private var lim: Int,
    private val cap: Int,
    private val readOnly: Boolean,
) {
    fun position(): Int = pos
    fun position(newPosition: Int): ByteBuf {
        require(newPosition in 0..lim) { "position out of range: $newPosition" }
        pos = newPosition
        return this
    }

    fun limit(): Int = lim
    fun limit(newLimit: Int): ByteBuf {
        require(newLimit in 0..cap) { "limit out of range: $newLimit" }
        lim = newLimit
        if (pos > lim) pos = lim
        return this
    }

    fun remaining(): Int = lim - pos
    fun hasRemaining(): Boolean = pos < lim
    fun capacity(): Int = cap
    fun isReadOnly(): Boolean = readOnly
    fun hasArray(): Boolean = true
    fun array(): ByteArray = data
    fun arrayOffset(): Int = arrayOffsetValue
    fun slice(): ByteBuf = ByteBuf(data, arrayOffsetValue + pos, 0, remaining(), remaining(), readOnly)
    fun slice(index: Int, length: Int): ByteBuf {
        require(index >= 0 && length >= 0 && index + length <= cap) { "slice out of range" }
        return ByteBuf(data, arrayOffsetValue + index, 0, length, length, readOnly)
    }

    fun asReadOnlyBuffer(): ByteBuf = ByteBuf(data, arrayOffsetValue, pos, lim, cap, true)
    fun duplicate(): ByteBuf = ByteBuf(data, arrayOffsetValue, pos, lim, cap, readOnly)

    fun get(): Byte {
        if (!hasRemaining()) throw AvifIoException("Buffer underflow")
        return data[arrayOffsetValue + pos++]
    }

    fun get(index: Int): Byte {
        if (index < 0 || index >= lim) throw IndexOutOfBoundsException("index $index")
        return data[arrayOffsetValue + index]
    }

    fun get(dst: ByteArray): ByteBuf = get(dst, 0, dst.size)

    fun get(dst: ByteArray, offset: Int, length: Int): ByteBuf {
        if (remaining() < length) throw AvifIoException("Buffer underflow")
        data.copyInto(dst, offset, arrayOffsetValue + pos, arrayOffsetValue + pos + length)
        pos += length
        return this
    }

    fun put(value: Byte): ByteBuf {
        checkWritable()
        if (!hasRemaining()) throw AvifIoException("Buffer overflow")
        data[arrayOffsetValue + pos++] = value
        return this
    }

    fun put(src: ByteArray): ByteBuf = put(src, 0, src.size)

    fun put(src: ByteArray, offset: Int, length: Int): ByteBuf {
        checkWritable()
        if (remaining() < length) throw AvifIoException("Buffer overflow")
        src.copyInto(data, arrayOffsetValue + pos, offset, offset + length)
        pos += length
        return this
    }

    fun put(src: ByteBuf): ByteBuf {
        val length = src.remaining()
        if (remaining() < length) throw AvifIoException("Buffer overflow")
        src.get(data, arrayOffsetValue + pos, length)
        pos += length
        return this
    }

    fun getShort(): Short {
        val lo = get().toInt() and 0xFF
        val hi = get().toInt() and 0xFF
        return (lo or (hi shl 8)).toShort()
    }

    fun getInt(): Int {
        val b0 = get().toInt() and 0xFF
        val b1 = get().toInt() and 0xFF
        val b2 = get().toInt() and 0xFF
        val b3 = get().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun getLong(): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((get().toLong() and 0xFFL) shl (8 * i))
        }
        return value
    }

    fun compact(): ByteBuf {
        checkWritable()
        val rem = remaining()
        if (pos > 0 && rem > 0) {
            data.copyInto(data, arrayOffsetValue, arrayOffsetValue + pos, arrayOffsetValue + lim)
        }
        pos = rem
        lim = cap
        return this
    }

    fun flip(): ByteBuf {
        lim = pos
        pos = 0
        return this
    }

    fun clear(): ByteBuf {
        pos = 0
        lim = cap
        return this
    }

    private fun checkWritable() {
        if (readOnly) throw AvifIoException("Buffer is read-only")
    }

    companion object {
        fun wrap(bytes: ByteArray): ByteBuf = ByteBuf(bytes, 0, 0, bytes.size, bytes.size, false)
        fun wrap(bytes: ByteArray, offset: Int, length: Int): ByteBuf {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            return ByteBuf(bytes, offset, 0, length, length, false)
        }

        fun allocate(capacity: Int): ByteBuf {
            require(capacity >= 0)
            return ByteBuf(ByteArray(capacity), 0, 0, capacity, capacity, false)
        }

        fun allocateDirect(capacity: Int): ByteBuf = allocate(capacity)
    }
}

class ShortBuf private constructor(
    private val data: ShortArray,
    private var pos: Int,
    private var lim: Int,
    private val cap: Int,
    private val readOnly: Boolean,
) {
    fun position(): Int = pos
    fun position(newPosition: Int): ShortBuf {
        pos = newPosition
        return this
    }

    fun limit(): Int = lim
    fun remaining(): Int = lim - pos
    fun hasRemaining(): Boolean = pos < lim
    fun capacity(): Int = cap

    fun slice(): ShortBuf = ShortBuf(data.copyOfRange(pos, lim), 0, remaining(), remaining(), readOnly)
    fun slice(index: Int, length: Int): ShortBuf = ShortBuf(data.copyOfRange(index, index + length), 0, length, length, readOnly)
    fun asReadOnlyBuffer(): ShortBuf = ShortBuf(data, pos, lim, cap, true)
    fun duplicate(): ShortBuf = ShortBuf(data, pos, lim, cap, readOnly)

    fun get(): Short = data[pos++]
    fun get(index: Int): Short = data[index]
    fun get(dst: ShortArray): ShortBuf = get(dst, 0, dst.size)
    fun get(dst: ShortArray, offset: Int, length: Int): ShortBuf {
        data.copyInto(dst, offset, pos, pos + length)
        pos += length
        return this
    }

    fun get(index: Int, dst: ShortArray, offset: Int, length: Int): ShortBuf {
        data.copyInto(dst, offset, index, index + length)
        return this
    }

    companion object {
        fun wrap(values: ShortArray): ShortBuf = ShortBuf(values, 0, values.size, values.size, false)
    }
}

class IntBuf private constructor(
    private val data: IntArray,
    private var pos: Int,
    private var lim: Int,
    private val cap: Int,
    private val readOnly: Boolean,
) {
    fun position(): Int = pos
    fun remaining(): Int = lim - pos
    fun hasRemaining(): Boolean = pos < lim
    fun slice(): IntBuf = IntBuf(data.copyOfRange(pos, lim), 0, remaining(), remaining(), readOnly)
    fun asReadOnlyBuffer(): IntBuf = IntBuf(data, pos, lim, cap, true)

    fun get(): Int = data[pos++]
    fun get(index: Int): Int = data[index]
    fun get(dst: IntArray): IntBuf = get(dst, 0, dst.size)
    fun get(dst: IntArray, offset: Int, length: Int): IntBuf {
        data.copyInto(dst, offset, pos, pos + length)
        pos += length
        return this
    }

    companion object {
        fun wrap(values: IntArray): IntBuf = IntBuf(values, 0, values.size, values.size, false)
    }
}

class LongBuf private constructor(
    private val data: LongArray,
    private var pos: Int,
    private var lim: Int,
    private val cap: Int,
    private val readOnly: Boolean,
) {
    fun position(): Int = pos
    fun remaining(): Int = lim - pos
    fun hasRemaining(): Boolean = pos < lim
    fun slice(): LongBuf = LongBuf(data.copyOfRange(pos, lim), 0, remaining(), remaining(), readOnly)
    fun asReadOnlyBuffer(): LongBuf = LongBuf(data, pos, lim, cap, true)

    fun get(): Long = data[pos++]
    fun get(index: Int): Long = data[index]
    fun get(dst: LongArray): LongBuf = get(dst, 0, dst.size)
    fun get(dst: LongArray, offset: Int, length: Int): LongBuf {
        data.copyInto(dst, offset, pos, pos + length)
        pos += length
        return this
    }

    companion object {
        fun wrap(values: LongArray): LongBuf = LongBuf(values, 0, values.size, values.size, false)
    }
}
