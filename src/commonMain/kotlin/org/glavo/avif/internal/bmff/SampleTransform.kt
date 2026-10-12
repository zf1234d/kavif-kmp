// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.Av1DecodedPlane
import org.glavo.avif.Av1DecodedPlanes
import org.glavo.avif.AvifBitDepth


/** Parsed AVIF Sample Transform expression and its ordered input image sources.
 * 
 * The expression uses the postfix token encoding from a `sato` derived image item. Arithmetic is
 * evaluated in its signaled signed 8-, 16-, 32-, or 64-bit domain with saturation after each
 * arithmetic operation. Color output is clamped to the unsigned range selected by the `sato`
 * item's `pixi` property and, when present, its limited-range `nclx` color information. */
class SampleTransform(
    bitDepth: AvifBitDepth,
    /** Whether color output uses the full unsigned sample range. */
    private val fullRange: Boolean,
    intermediateBitDepth: Int,
    tokenCodes: IntArray,
    constantValues: LongArray,
    inputs: Array<Input>,
    primaryInputIndex: Int
) {
    /** The reconstructed sample bit depth. */
    private val bitDepth: AvifBitDepth

    /** The signed intermediate arithmetic bit depth. */
    private val intermediateBitDepth: Int

    /** The inclusive minimum intermediate value. */
    private val intermediateMinValue: Long

    /** The inclusive maximum intermediate value. */
    private val intermediateMaxValue: Long

    /** Postfix token codes in evaluation order. */
    private val tokenCodes: IntArray

    /** Signed constant values corresponding to constant tokens. */
    private val constantValues: LongArray

    /** Ordered Sample Transform inputs. */
    private val inputs: Array<Input>

    /** The zero-based input index containing the primary image item. */
    private val primaryInputIndex: Int

    init {
        this.bitDepth = requireNotNullCompat(bitDepth, "bitDepth")
        require(!(intermediateBitDepth != 8 && intermediateBitDepth != 16 && intermediateBitDepth != 32 && intermediateBitDepth != 64)) { "Unsupported intermediate bit depth: $intermediateBitDepth" }
        this.intermediateBitDepth = intermediateBitDepth
        this.intermediateMinValue = if (intermediateBitDepth == 64) Long.MIN_VALUE else
            -(1L shl (intermediateBitDepth - 1))
        this.intermediateMaxValue = if (intermediateBitDepth == 64) Long.MAX_VALUE else
            (1L shl (intermediateBitDepth - 1)) - 1L
        this.tokenCodes = requireNotNullCompat(tokenCodes, "tokenCodes").copyOf()
        this.constantValues =
            requireNotNullCompat(constantValues, "constantValues").copyOf()
        this.inputs = requireNotNullCompat(inputs, "inputs").copyOf()
        require(!(this.tokenCodes.size == 0 || this.tokenCodes.size > 255)) { "Sample Transform token count must be in [1, 255]" }
        require(this.constantValues.size == this.tokenCodes.size) { "constantValues length must match tokenCodes length" }
        require(!(this.inputs.size == 0 || this.inputs.size > LAST_INPUT)) { "Sample Transform input count must be in [1, 32]" }
        require(!(primaryInputIndex < 0 || primaryInputIndex >= this.inputs.size)) { "primaryInputIndex out of range: $primaryInputIndex" }
        this.primaryInputIndex = primaryInputIndex
        for (i in this.inputs.indices) {
            requireNotNullCompat(this.inputs[i], "inputs[$i]")
        }
        validateExpression()
    }

    /** Returns the reconstructed sample bit depth.
     * 
     * @return the reconstructed sample bit depth
     */
    fun bitDepth(): AvifBitDepth {
        return bitDepth
    }

    /** Returns whether color output uses the full unsigned sample range.
     * 
     * @return whether color output uses the full unsigned sample range
     */
    fun fullRange(): Boolean {
        return fullRange
    }

    /** Returns the number of ordered input images.
     * 
     * @return the input image count
     */
    fun inputCount(): Int {
        return inputs.size
    }

    /** Returns one ordered input descriptor.
     * 
     * @param index the zero-based input index
     * @return the input descriptor
     */
    fun input(index: Int): Input {
        return inputs[index]
    }

    /** Returns the index of the input containing the primary image item.
     * 
     * @return the zero-based primary input index
     */
    fun primaryInputIndex(): Int {
        return primaryInputIndex
    }

    /** Applies this expression to decoded input planes.
     * 
     * Every input must have identical coded and render dimensions, chroma layout, and plane
     * dimensions. Input bit depths may differ. The returned planes use tightly packed rows and the
     * bit depth and color range declared by the Sample Transform.
     * 
     * @param inputPlanes decoded planes in `dimg` reference order
     * @return the reconstructed planes
     */
    fun apply(inputPlanes: Array<Av1DecodedPlanes>): Av1DecodedPlanes {
        return applyPlanes(inputPlanes, fullRange)
    }

    /** Applies this expression to decoded alpha input planes.
     * 
     * Alpha output always uses the complete unsigned range selected by the Sample Transform bit
     * depth, independently of the color image's `nclx` range signaling.
     * 
     * @param inputPlanes decoded alpha planes in `dimg` reference order
     * @return the reconstructed alpha planes
     */
    fun applyAlpha(inputPlanes: Array<Av1DecodedPlanes>): Av1DecodedPlanes {
        return applyPlanes(inputPlanes, true)
    }

    /** Applies this expression with an explicit output range policy.
     * 
     * @param inputPlanes decoded planes in `dimg` reference order
     * @param outputFullRange whether the output uses the full unsigned sample range
     * @return the reconstructed planes
     */
    private fun applyPlanes(
        inputPlanes: Array<Av1DecodedPlanes>,
        outputFullRange: Boolean
    ): Av1DecodedPlanes {
        val checkedInputs = requireNotNullCompat(inputPlanes, "inputPlanes")
        require(checkedInputs.size == inputs.size) { "Decoded Sample Transform input count mismatch: " + checkedInputs.size + " != " + inputs.size }
        val first = requireNotNullCompat(checkedInputs[0], "inputPlanes[0]")
        for (i in 1..<checkedInputs.size) {
            validateCompatiblePlanes(
                first,
                requireNotNullCompat(checkedInputs[i], "inputPlanes[$i]")
            )
        }

        val lumaPlane = applyPlane(lumaPlanes(checkedInputs), false, outputFullRange)
        var chromaUPlane: Av1DecodedPlane? = null
        var chromaVPlane: Av1DecodedPlane? = null
        if (first.chromaFormat() != Av1ChromaFormat.MONOCHROME) {
            chromaUPlane = applyPlane(chromaPlanes(checkedInputs, true), true, outputFullRange)
            chromaVPlane = applyPlane(chromaPlanes(checkedInputs, false), true, outputFullRange)
        }
        return Av1DecodedPlanes(
            bitDepth,
            first.chromaFormat(),
            first.codedWidth(),
            first.codedHeight(),
            first.renderWidth(),
            first.renderHeight(),
            lumaPlane,
            chromaUPlane,
            chromaVPlane
        )
    }

    /** Validates the postfix expression and its input references. */
    private fun validateExpression() {
        var stackSize = 0
        for (tokenIndex in tokenCodes.indices) {
            val tokenCode = tokenCodes[tokenIndex]
            if (tokenCode == CONSTANT) {
                val constant = constantValues[tokenIndex]
                require(constant in intermediateMinValue..intermediateMaxValue) { "Sample Transform constant exceeds intermediate range: $constant" }
                stackSize++
            } else if (tokenCode >= FIRST_INPUT && tokenCode <= LAST_INPUT) {
                require(tokenCode <= inputs.size) { "Sample Transform input index out of range: $tokenCode" }
                stackSize++
            } else if (tokenCode >= FIRST_UNARY_OPERATOR && tokenCode <= LAST_UNARY_OPERATOR) {
                require(stackSize >= 1) { "Sample Transform unary operator has no operand" }
            } else if (tokenCode >= FIRST_BINARY_OPERATOR && tokenCode <= LAST_BINARY_OPERATOR) {
                require(stackSize >= 2) { "Sample Transform binary operator has fewer than two operands" }
                stackSize--
            } else {
                throw IllegalArgumentException("Reserved Sample Transform token code: $tokenCode")
            }
        }
        require(stackSize == 1) { "Sample Transform expression leaves $stackSize stack values" }
    }

    /** Applies the expression to one component plane.
     * 
     * @param inputPlanes the matching component plane from every input image
     * @param chroma whether the component is chroma rather than luma
     * @param outputFullRange whether the output uses the full unsigned sample range
     * @return the reconstructed component plane
     */
    private fun applyPlane(
        inputPlanes: Array<Av1DecodedPlane>,
        chroma: Boolean,
        outputFullRange: Boolean
    ): Av1DecodedPlane {
        val first = inputPlanes[0]
        val width = first.width()
        val height = first.height()
        for (i in 1..<inputPlanes.size) {
            val plane = inputPlanes[i]
            require(!(plane.width() != width || plane.height() != height)) { "Sample Transform input component dimensions differ" }
        }

        val output = ShortArray(multiplyExact(width, height))
        val stack = LongArray(tokenCodes.size)
        val rangeShift = bitDepth.bits() - 8
        val minSample = if (outputFullRange) 0 else 16 shl rangeShift
        val maxSample = if (outputFullRange)
            bitDepth.maxSampleValue()
        else
            (if (chroma) 240 else 235) shl rangeShift
        for (y in 0..<height) {
            for (x in 0..<width) {
                val value = evaluateSample(inputPlanes, x, y, stack)
                output[y * width + x] =
                    max(minSample.toLong(), min(maxSample.toLong(), value)).toShort()
            }
        }
        return Av1DecodedPlane(width, height, width, output)
    }

    /** Evaluates one output sample.
     * 
     * @param inputPlanes the matching component plane from every input image
     * @param x the sample x coordinate
     * @param y the sample y coordinate
     * @param stack reusable expression stack storage
     * @return the signed expression result before final unsigned clamping
     */
    private fun evaluateSample(
        inputPlanes: Array<Av1DecodedPlane>,
        x: Int,
        y: Int,
        stack: LongArray
    ): Long {
        var stackSize = 0
        for (tokenIndex in tokenCodes.indices) {
            val tokenCode = tokenCodes[tokenIndex]
            if (tokenCode == CONSTANT) {
                stack[stackSize++] = constantValues[tokenIndex]
            } else if (tokenCode <= LAST_INPUT) {
                val sample = inputPlanes[tokenCode - 1].sample(x, y)
                require(sample <= intermediateMaxValue) { "Sample Transform input exceeds intermediate range: $sample" }
                stack[stackSize++] = sample.toLong()
            } else if (tokenCode <= LAST_UNARY_OPERATOR) {
                stack[stackSize - 1] = applyUnary(stack[stackSize - 1], tokenCode)
            } else {
                stack[stackSize - 2] =
                    applyBinary(stack[stackSize - 2], stack[stackSize - 1], tokenCode)
                stackSize--
            }
        }
        return stack[0]
    }

    /** Applies one signed intermediate-width unary operation.
     * 
     * @param operand the operand
     * @param operator the operator token code
     * @return the operation result
     */
    private fun applyUnary(operand: Long, operator: Int): Long {
        return when (operator) {
            NEGATION -> negate(operand)
            ABSOLUTE -> if (operand >= 0) operand else negate(operand)
            NOT -> clampIntermediate(operand.inv())
            BIT_SCAN_REVERSE -> if (operand <= 0) 0L else (63 - operand.countLeadingZeroBits()).toLong()
            else -> throw IllegalArgumentException("Unsupported Sample Transform unary operator: $operator")
        }
    }

    /** Applies one signed intermediate-width binary operation.
     * 
     * @param left the left operand
     * @param right the right operand
     * @param operator the operator token code
     * @return the operation result
     */
    private fun applyBinary(left: Long, right: Long, operator: Int): Long {
        return when (operator) {
            SUM -> add(left, right)
            DIFFERENCE -> subtract(left, right)
            PRODUCT -> multiply(left, right)
            QUOTIENT -> divide(left, right)
            AND -> left and right
            OR -> left or right
            XOR -> left xor right
            POWER -> power(left, right)
            MINIMUM -> min(left, right)
            MAXIMUM -> max(left, right)
            else -> throw IllegalArgumentException("Unsupported Sample Transform binary operator: $operator")
        }
    }

    /** Computes signed integer exponentiation using the Sample Transform edge-case rules.
     * 
     * @param base the base operand
     * @param exponent the exponent operand
     * @return the saturated signed intermediate-width result
     */
    private fun power(base: Long, exponent: Long): Long {
        if (base == 0L || base == 1L) {
            return base
        }
        if (base == -1L) {
            return (if ((exponent and 1L) == 0L) 1 else -1).toLong()
        }
        if (exponent == 0L) {
            return 1
        }
        if (exponent == 1L) {
            return base
        }
        if (exponent < 0) {
            return 0
        }

        var result: Long = 1
        var factor = base
        var remaining = exponent
        while (remaining != 0L) {
            if ((remaining and 1L) != 0L) {
                result = multiply(result, factor)
            }
            remaining = remaining ushr 1
            if (remaining != 0L) {
                factor = multiply(factor, factor)
            }
        }
        return result
    }

    /** Negates one intermediate value with signed saturation.
     * 
     * @param value the value to negate
     * @return the saturated negated value
     */
    private fun negate(value: Long): Long {
        if (value == Long.MIN_VALUE) {
            return intermediateMaxValue
        }
        return clampIntermediate(-value)
    }

    /** Adds two intermediate values with signed saturation.
     * 
     * @param left the left operand
     * @param right the right operand
     * @return the saturated sum
     */
    private fun add(left: Long, right: Long): Long {
        try {
            return clampIntermediate(addExact(left, right))
        } catch (exception: ArithmeticException) {
            return if (left < 0) intermediateMinValue else intermediateMaxValue
        }
    }

    /** Subtracts two intermediate values with signed saturation.
     * 
     * @param left the left operand
     * @param right the right operand
     * @return the saturated difference
     */
    private fun subtract(left: Long, right: Long): Long {
        try {
            return clampIntermediate(subtractExact(left, right))
        } catch (exception: ArithmeticException) {
            return if (left < 0) intermediateMinValue else intermediateMaxValue
        }
    }

    /** Multiplies two intermediate values with signed saturation.
     * 
     * @param left the left operand
     * @param right the right operand
     * @return the saturated product
     */
    private fun multiply(left: Long, right: Long): Long {
        try {
            return clampIntermediate(multiplyExact(left, right))
        } catch (exception: ArithmeticException) {
            return if ((left < 0) == (right < 0)) intermediateMaxValue else intermediateMinValue
        }
    }

    /** Divides two intermediate values with the Sample Transform zero-divisor rule.
     * 
     * @param left the dividend
     * @param right the divisor
     * @return the saturated quotient, or `left` when `right` is zero
     */
    private fun divide(left: Long, right: Long): Long {
        if (right == 0L) {
            return left
        }
        if (left == Long.MIN_VALUE && right == -1L) {
            return intermediateMaxValue
        }
        return clampIntermediate(left / right)
    }

    /** Clamps one signed value to the configured intermediate range.
     * 
     * @param value the value to clamp
     * @return the saturated intermediate value
     */
    private fun clampIntermediate(value: Long): Long {
        if (value <= intermediateMinValue) {
            return intermediateMinValue
        }
        if (value >= intermediateMaxValue) {
            return intermediateMaxValue
        }
        return value
    }

    /** One ordered Sample Transform input and its matching optional alpha image.
     * 
     * @param colorSource the color image source
     * @param colorBitDepth the coded bit depth required for a matching alpha image
     * @param alphaSource the matching alpha image source, or `null`
     */
    data class Input(
        val colorSource: AvifImageSource,
        val colorBitDepth: AvifBitDepth,
        val alphaSource: AvifImageSource?
    ) {
        /** Creates an ordered Sample Transform input. */
        init {
            requireNotNullCompat(colorSource, "colorSource")
            requireNotNullCompat(colorBitDepth, "colorBitDepth")
        }
    }

    companion object {
        /** The constant operand token code. */
        private const val CONSTANT = 0

        /** The first one-based input-image token code. */
        private const val FIRST_INPUT = 1

        /** The last one-based input-image token code. */
        private const val LAST_INPUT = 32

        /** The first unary-operator token code. */
        private const val FIRST_UNARY_OPERATOR = 64

        /** The negation operator token code. */
        private const val NEGATION = 64

        /** The absolute-value operator token code. */
        private const val ABSOLUTE = 65

        /** The bitwise-not operator token code. */
        private const val NOT = 66

        /** The bit-scan-reverse operator token code. */
        private const val BIT_SCAN_REVERSE = 67

        /** The last unary-operator token code. */
        private const val LAST_UNARY_OPERATOR = 67

        /** The first binary-operator token code. */
        private const val FIRST_BINARY_OPERATOR = 128

        /** The addition operator token code. */
        private const val SUM = 128

        /** The subtraction operator token code. */
        private const val DIFFERENCE = 129

        /** The multiplication operator token code. */
        private const val PRODUCT = 130

        /** The division operator token code. */
        private const val QUOTIENT = 131

        /** The bitwise-and operator token code. */
        private const val AND = 132

        /** The bitwise-or operator token code. */
        private const val OR = 133

        /** The bitwise-exclusive-or operator token code. */
        private const val XOR = 134

        /** The exponentiation operator token code. */
        private const val POWER = 135

        /** The minimum operator token code. */
        private const val MINIMUM = 136

        /** The maximum operator token code. */
        private const val MAXIMUM = 137

        /** The last binary-operator token code. */
        private const val LAST_BINARY_OPERATOR = 137

        /** Validates that two decoded input images have the same plane geometry.
         * 
         * @param expected the first decoded input
         * @param actual another decoded input
         */
        fun validateCompatiblePlanes(expected: Av1DecodedPlanes, actual: Av1DecodedPlanes) {
            require(!(actual.chromaFormat() != expected.chromaFormat() || actual.codedWidth() != expected.codedWidth() || actual.codedHeight() != expected.codedHeight())) { "Sample Transform input plane layouts differ" }
        }

        /** Collects luma planes from decoded inputs.
         * 
         * @param inputPlanes the decoded inputs
         * @return the ordered luma planes
         */
        fun lumaPlanes(inputPlanes: Array<Av1DecodedPlanes>): Array<Av1DecodedPlane> {
            return Array(inputPlanes.size) { i -> inputPlanes[i].lumaPlane() }
        }

        /** Collects one chroma component from decoded inputs.
         * 
         * @param inputPlanes the decoded inputs
         * @param chromaU whether to collect U rather than V
         * @return the ordered chroma planes
         */
        fun chromaPlanes(
            inputPlanes: Array<Av1DecodedPlanes>,
            chromaU: Boolean
        ): Array<Av1DecodedPlane> {
            return Array(inputPlanes.size) { i ->
                val plane =
                    if (chromaU) inputPlanes[i].chromaUPlane() else inputPlanes[i].chromaVPlane()
                requireNotNull(plane) { "Sample Transform chroma input is missing" }
            }
        }
    }
}
