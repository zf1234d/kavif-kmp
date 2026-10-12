// Pure Kotlin DEFLATE/gzip inflater used to expand the embedded dav1d and film-grain
// lookup tables without depending on java.util.zip, keeping the code multiplatform.
// SPDX-License-Identifier: Apache-2.0
package org.glavo.avif.internal.compat

private class BitReader(private val data: ByteArray, private var pos: Int) {
    private var bitBuffer = 0
    private var bitCount = 0

    fun bits(count: Int): Int {
        while (bitCount < count) {
            val next = if (pos < data.size) data[pos++].toInt() and 0xFF else 0
            bitBuffer = bitBuffer or (next shl bitCount)
            bitCount += 8
        }
        val value = bitBuffer and ((1 shl count) - 1)
        bitBuffer = bitBuffer ushr count
        bitCount -= count
        return value
    }

    fun alignToByte() {
        bitBuffer = 0
        bitCount = 0
    }

    fun position(): Int = pos
}

private class Huffman {
    val counts = IntArray(16)
    val symbols = IntArray(288)

    fun decode(reader: BitReader): Int {
        var code = 0
        var first = 0
        var index = 0
        var length = 1
        while (length <= 15) {
            code = code or reader.bits(1)
            val count = counts[length]
            if (code - first < count) {
                return symbols[index + (code - first)]
            }
            index += count
            first = (first + count) shl 1
            code = code shl 1
            length++
        }
        throw AvifIoException("Invalid DEFLATE Huffman code")
    }
}

private fun buildHuffman(lengths: IntArray, table: Huffman) {
    table.counts.fill(0)
    for (length in lengths) {
        table.counts[length]++
    }
    table.counts[0] = 0
    val offsets = IntArray(16)
    for (i in 1..15) {
        offsets[i] = offsets[i - 1] + table.counts[i - 1]
    }
    for (symbol in lengths.indices) {
        val length = lengths[symbol]
        if (length != 0) {
            table.symbols[offsets[length]++] = symbol
        }
    }
}

private val LENGTH_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258
)
private val LENGTH_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
    4, 4, 4, 4, 5, 5, 5, 5, 0
)
private val DIST_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577
)
private val DIST_EXTRA = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
    9, 9, 10, 10, 11, 11, 12, 12, 13, 13
)
private val CODE_LENGTH_ORDER = intArrayOf(
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
)

private class Output(initial: Int) {
    var data = ByteArray(if (initial > 0) initial else 64)
    var size = 0

    fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var capacity = data.size
        while (capacity < size + extra) capacity *= 2
        data = data.copyOf(capacity)
    }

    fun write(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    fun byteAt(index: Int): Int = data[index].toInt() and 0xFF
}

private val FIXED_LITERAL: Huffman = Huffman().also { table ->
    val lengths = IntArray(288)
    for (i in 0..143) lengths[i] = 8
    for (i in 144..255) lengths[i] = 9
    for (i in 256..279) lengths[i] = 7
    for (i in 280..287) lengths[i] = 8
    buildHuffman(lengths, table)
}

private val FIXED_DISTANCE: Huffman = Huffman().also { table ->
    buildHuffman(IntArray(30) { 5 }, table)
}

private fun inflate(reader: BitReader, output: Output) {
    val lengthTable = Huffman()
    val distanceTable = Huffman()
    var finalBlock = false
    while (!finalBlock) {
        finalBlock = reader.bits(1) != 0
        when (reader.bits(2)) {
            0 -> {
                reader.alignToByte()
                val length = reader.bits(16)
                reader.bits(16)
                for (i in 0 until length) {
                    output.write(reader.bits(8))
                }
            }
            1 -> inflateBlock(reader, output, FIXED_LITERAL, FIXED_DISTANCE)
            2 -> {
                val literalCount = reader.bits(5) + 257
                val distanceCount = reader.bits(5) + 1
                val codeLengthCount = reader.bits(4) + 4
                val codeLengthLengths = IntArray(19)
                for (i in 0 until codeLengthCount) {
                    codeLengthLengths[CODE_LENGTH_ORDER[i]] = reader.bits(3)
                }
                val codeLengthTable = Huffman()
                buildHuffman(codeLengthLengths, codeLengthTable)
                val lengths = IntArray(literalCount + distanceCount)
                var index = 0
                while (index < lengths.size) {
                    val symbol = codeLengthTable.decode(reader)
                    when {
                        symbol < 16 -> lengths[index++] = symbol
                        symbol == 16 -> {
                            val previous = lengths[index - 1]
                            val repeat = reader.bits(2) + 3
                            for (i in 0 until repeat) lengths[index++] = previous
                        }
                        symbol == 17 -> {
                            val repeat = reader.bits(3) + 3
                            for (i in 0 until repeat) lengths[index++] = 0
                        }
                        else -> {
                            val repeat = reader.bits(7) + 11
                            for (i in 0 until repeat) lengths[index++] = 0
                        }
                    }
                }
                buildHuffman(lengths.copyOfRange(0, literalCount), lengthTable)
                buildHuffman(lengths.copyOfRange(literalCount, lengths.size), distanceTable)
                inflateBlock(reader, output, lengthTable, distanceTable)
            }
            else -> throw AvifIoException("Invalid DEFLATE block type")
        }
    }
}

private fun inflateBlock(reader: BitReader, output: Output, literalTable: Huffman, distanceTable: Huffman) {
    while (true) {
        val symbol = literalTable.decode(reader)
        if (symbol < 256) {
            output.write(symbol)
        } else if (symbol == 256) {
            return
        } else {
            val lengthIndex = symbol - 257
            if (lengthIndex >= LENGTH_BASE.size) throw AvifIoException("Invalid DEFLATE length code")
            val length = LENGTH_BASE[lengthIndex] + reader.bits(LENGTH_EXTRA[lengthIndex])
            val distanceSymbol = distanceTable.decode(reader)
            if (distanceSymbol >= DIST_BASE.size) throw AvifIoException("Invalid DEFLATE distance code")
            val distance = DIST_BASE[distanceSymbol] + reader.bits(DIST_EXTRA[distanceSymbol])
            output.ensure(length)
            var source = output.size - distance
            for (i in 0 until length) {
                output.data[output.size++] = output.data[source++]
            }
        }
    }
}

fun inflateGzip(compressed: ByteArray): ByteArray {
    require(compressed.size >= 18) { "gzip payload is too small" }
    require((compressed[0].toInt() and 0xFF) == 0x1F && (compressed[1].toInt() and 0xFF) == 0x8B) {
        "not a gzip stream"
    }
    require((compressed[2].toInt() and 0xFF) == 8) { "unsupported gzip compression method" }
    val flags = compressed[3].toInt() and 0xFF
    var position = 10
    if (flags and 0x04 != 0) {
        val extraLength = (compressed[position].toInt() and 0xFF) or ((compressed[position + 1].toInt() and 0xFF) shl 8)
        position += 2 + extraLength
    }
    if (flags and 0x08 != 0) {
        while ((compressed[position].toInt() and 0xFF) != 0) position++
        position++
    }
    if (flags and 0x10 != 0) {
        while ((compressed[position].toInt() and 0xFF) != 0) position++
        position++
    }
    if (flags and 0x02 != 0) {
        position += 2
    }
    val reader = BitReader(compressed, position)
    val output = Output(0)
    inflate(reader, output)
    return output.data.copyOf(output.size)
}
