// Compatibility helpers replacing Java-only APIs with Kotlin multiplatform equivalents.
// SPDX-License-Identifier: Apache-2.0
package org.glavo.avif.internal.compat

import kotlin.math.roundToInt
import kotlin.math.roundToLong

typealias AutoAvifCloseable = AutoCloseable

open class AvifIoException : Exception {
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}

class ExceptionInInitializerError(cause: Throwable) : Error(cause)

class EOFException(message: String = "Unexpected end of input") : AvifIoException(message)

object Charsets {
    const val ISO_8859_1: String = "ISO-8859-1"
    const val UTF_8: String = "UTF-8"
}

class ByteArrayOutput(capacity: Int = 32) {
    private var buffer: ByteArray = ByteArray(maxOf(32, capacity))
    private var count: Int = 0

    fun write(b: Int) {
        ensureCapacity(count + 1)
        buffer[count++] = b.toByte()
    }

    fun write(src: ByteArray, offset: Int = 0, length: Int = src.size) {
        ensureCapacity(count + length)
        src.copyInto(buffer, count, offset, offset + length)
        count += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(count)

    fun toString(charset: String): String {
        val bytes = toByteArray()
        return if (charset == Charsets.ISO_8859_1) {
            bytes.decodeToIso88591()
        } else {
            bytes.decodeToString()
        }
    }

    fun size(): Int = count

    private fun ensureCapacity(min: Int) {
        if (min <= buffer.size) return
        var n = buffer.size
        while (n < min) n *= 2
        buffer = buffer.copyOf(n)
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun toUnsignedInt(value: Byte): Int = value.toInt() and 0xFF

@Suppress("NOTHING_TO_INLINE")
inline fun toUnsignedInt(value: Int): Int = value

@Suppress("NOTHING_TO_INLINE")
inline fun shortToUnsignedInt(value: Short): Int = value.toInt() and 0xFFFF

@Suppress("NOTHING_TO_INLINE")
inline fun toUnsignedLong(value: Int): Long = value.toLong() and 0xFFFFFFFFL

fun checkIndexCompat(index: Int, length: Int): Int {
    if (index < 0 || index >= length) {
        throw IndexOutOfBoundsException("Index $index out of bounds for length $length")
    }
    return index
}

fun toIntExact(value: Long): Int {
    val i = value.toInt()
    if (i.toLong() != value) throw ArithmeticException("toIntExact overflow: $value")
    return i
}

fun multiplyExact(a: Int, b: Int): Int {
    val r = a.toLong() * b.toLong()
    if (r < Int.MIN_VALUE || r > Int.MAX_VALUE) throw ArithmeticException("multiplyExact overflow")
    return r.toInt()
}

fun multiplyExact(a: Long, b: Long): Long {
    val r = a * b
    if (a != 0L && r / a != b) throw ArithmeticException("multiplyExact overflow")
    return r
}

fun addExact(a: Int, b: Int): Int {
    val r = a.toLong() + b.toLong()
    if (r < Int.MIN_VALUE || r > Int.MAX_VALUE) throw ArithmeticException("addExact overflow")
    return r.toInt()
}

fun addExact(a: Long, b: Long): Long {
    val r = a + b
    if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("addExact overflow")
    return r
}

fun subtractExact(a: Int, b: Int): Int {
    val r = a.toLong() - b.toLong()
    if (r < Int.MIN_VALUE || r > Int.MAX_VALUE) throw ArithmeticException("subtractExact overflow")
    return r.toInt()
}

fun subtractExact(a: Long, b: Long): Long {
    val r = a - b
    if (((a xor b) and (a xor r)) < 0) throw ArithmeticException("subtractExact overflow")
    return r
}

fun floorDiv(x: Int, y: Int): Int {
    var q = x / y
    if ((x xor y) < 0 && q * y != x) q--
    return q
}

fun floorDiv(x: Long, y: Long): Long {
    var q = x / y
    if ((x xor y) < 0L && q * y != x) q--
    return q
}

fun floorMod(x: Int, y: Int): Int {
    val r = x % y
    return if ((r xor y) < 0 && r != 0) r + y else r
}

fun floorMod(x: Long, y: Long): Long {
    val r = x % y
    return if ((r xor y) < 0L && r != 0L) r + y else r
}

fun compareUnsigned(a: Int, b: Int): Int = a.toUInt().compareTo(b.toUInt())
fun compareUnsigned(a: Long, b: Long): Int = a.toULong().compareTo(b.toULong())

@Suppress("UNCHECKED_CAST")
inline fun <reified T> arrayOfNullsCompat(size: Int): Array<T> =
    arrayOfNulls<T>(size) as Array<T>

@Suppress("UNCHECKED_CAST")
fun <T> nonNullArray(source: Array<T?>): Array<T> = source as Array<T>

@Suppress("UNCHECKED_CAST")
fun <T> nullableArray(source: Array<T>): Array<T?> = source as Array<T?>

fun contentEqualsCompat(
    a: ByteArray, aFrom: Int, aTo: Int,
    b: ByteArray, bFrom: Int, bTo: Int
): Boolean {
    val length = aTo - aFrom
    if (length != bTo - bFrom) return false
    for (i in 0 until length) {
        if (a[aFrom + i] != b[bFrom + i]) return false
    }
    return true
}

// Java Math.round == floor(x + 0.5); kotlin.math.round uses half-to-even, so use roundToLong/roundToInt instead.
fun roundCompat(value: Double): Long = value.roundToLong()
fun roundCompat(value: Float): Int = value.roundToInt()

fun countLeadingZeroBits(value: Int): Int = value.countLeadingZeroBits()
fun countLeadingZeroBits(value: Long): Int = value.countLeadingZeroBits()
fun countTrailingZeroBits(value: Int): Int = value.countTrailingZeroBits()
fun bitCount(value: Int): Int = value.countOneBits()
fun bitCount(value: Long): Int = value.countOneBits()

fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun arraycopy(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun arraycopy(src: ShortArray, srcPos: Int, dest: ShortArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun arraycopy(src: LongArray, srcPos: Int, dest: LongArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun arraycopy(src: BooleanArray, srcPos: Int, dest: BooleanArray, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun <T> arraycopy(src: Array<out T>, srcPos: Int, dest: Array<T>, destPos: Int, length: Int) {
    src.copyInto(dest, destPos, srcPos, srcPos + length)
}

fun copyOfCompat(source: ByteArray, newLength: Int): ByteArray = source.copyOf(newLength)
fun copyOfCompat(source: IntArray, newLength: Int): IntArray = source.copyOf(newLength)
fun copyOfCompat(source: ShortArray, newLength: Int): ShortArray = source.copyOf(newLength)
fun copyOfCompat(source: LongArray, newLength: Int): LongArray = source.copyOf(newLength)
fun copyOfCompat(source: BooleanArray, newLength: Int): BooleanArray = source.copyOf(newLength)
@Suppress("UNCHECKED_CAST")
fun <T> copyOfCompat(source: Array<T>, newLength: Int): Array<T> = source.copyOf(newLength) as Array<T>

fun fillCompat(a: ByteArray, value: Byte) { a.fill(value) }
fun fillCompat(a: ByteArray, value: Int) { a.fill(value.toByte()) }
fun fillCompat(a: IntArray, value: Int) { a.fill(value) }
fun fillCompat(a: ShortArray, value: Short) { a.fill(value) }
fun fillCompat(a: ShortArray, value: Int) { a.fill(value.toShort()) }
fun fillCompat(a: LongArray, value: Long) { a.fill(value) }
fun fillCompat(a: BooleanArray, value: Boolean) { a.fill(value) }
fun <T> fillCompat(a: Array<T>, value: T) { a.fill(value) }

fun fillCompat(a: ByteArray, fromIndex: Int, toIndex: Int, value: Byte) { a.fill(value, fromIndex, toIndex) }
fun fillCompat(a: ByteArray, fromIndex: Int, toIndex: Int, value: Int) { a.fill(value.toByte(), fromIndex, toIndex) }
fun fillCompat(a: IntArray, fromIndex: Int, toIndex: Int, value: Int) { a.fill(value, fromIndex, toIndex) }
fun fillCompat(a: ShortArray, fromIndex: Int, toIndex: Int, value: Short) { a.fill(value, fromIndex, toIndex) }
fun fillCompat(a: ShortArray, fromIndex: Int, toIndex: Int, value: Int) { a.fill(value.toShort(), fromIndex, toIndex) }
fun fillCompat(a: LongArray, fromIndex: Int, toIndex: Int, value: Long) { a.fill(value, fromIndex, toIndex) }
fun fillCompat(a: BooleanArray, fromIndex: Int, toIndex: Int, value: Boolean) { a.fill(value, fromIndex, toIndex) }
fun <T> fillCompat(a: Array<T>, fromIndex: Int, toIndex: Int, value: T) { a.fill(value, fromIndex, toIndex) }

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun decodeBase64(src: String): ByteArray {
    val cleaned = src.filter { it != '\n' && it != '\r' && it != ' ' }
    return kotlin.io.encoding.Base64.Mime.decode(cleaned)
}

fun gzipDecompress(compressed: ByteArray, expectedLength: Int): ByteArray {
    val result = inflateGzip(compressed)
    if (expectedLength >= 0 && result.size != expectedLength) {
        throw AvifIoException("gzip payload length " + result.size + " != expected $expectedLength")
    }
    return result
}

fun ByteArray.decodeToIso88591(): String {
    val chars = CharArray(size)
    for (i in indices) chars[i] = (this[i].toInt() and 0xFF).toChar()
    return chars.concatToString()
}

fun encodeIso88591(text: String): ByteArray {
    val bytes = ByteArray(text.length)
    for (i in text.indices) bytes[i] = text[i].code.toByte()
    return bytes
}

@Suppress("UNCHECKED_CAST")
fun <T> requireNotNullCompat(value: T?, name: String): T {
    if (value == null) throw NullPointerException("$name is null")
    return value as T
}

@Suppress("UNCHECKED_CAST")
fun <T> requireNotNullCompat(value: T?): T {
    if (value == null) throw NullPointerException("null")
    return value as T
}

fun checkFromIndexSize(offset: Int, length: Int, size: Int) {
    if ((size or offset or length) < 0 || offset > size - length) {
        throw IndexOutOfBoundsException("offset=$offset length=$length size=$size")
    }
}

fun copyOfRangeCompat(source: ByteArray, from: Int, to: Int): ByteArray = source.copyOfRange(from, to)
