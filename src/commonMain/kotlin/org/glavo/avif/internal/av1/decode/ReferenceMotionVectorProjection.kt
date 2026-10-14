// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import org.glavo.avif.av1.Av1FrameType
import org.glavo.avif.internal.av1.decode.TileDecodeContext.TemporalMotionBlock
import org.glavo.avif.internal.av1.model.FrameAssembly
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.InterMotionVector
import org.glavo.avif.internal.av1.model.MotionVector


/** Projects saved reference-frame motion fields onto the current frame's 8x8 grid.
 * 
 * The projection follows the temporal-motion setup and scaling rules from AV1 section 7.9.3.
 * Instances are immutable after construction and may be shared by every tile in one frame. */
class ReferenceMotionVectorProjection private constructor(
    frameHeader: FrameHeader,
    /** The projected field width in 8x8 units. */
    private val width8: Int,
    /** The projected field height in 8x8 units. */
    private val height8: Int,
    referenceToCurrentDistances: IntArray,
    referenceSignBiases: BooleanArray,
    blocks: Array<ProjectedTemporalBlock?>?
) {
    /** The current frame header whose precision and reference distances apply to projected candidates. */
    private val frameHeader: FrameHeader

    /** The current-reference distances indexed in internal LAST..ALTREF order. */
    private val referenceToCurrentDistances: IntArray

    /** The future-reference sign biases indexed in internal LAST..ALTREF order. */
    private val referenceSignBiases: BooleanArray

    /** The projected temporal blocks indexed in frame-relative 8x8 units. */
    private val blocks: Array<ProjectedTemporalBlock?>

    init {
        this.frameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        this.referenceToCurrentDistances = referenceToCurrentDistances.copyOf()
        this.referenceSignBiases = referenceSignBiases.copyOf()
        this.blocks = requireNotNullCompat(blocks, "blocks")
    }

    /** Returns whether the current frame enables reference-frame motion vectors.
     * 
     * @return whether the current frame enables reference-frame motion vectors
     */
    fun enabled(): Boolean {
        return frameHeader.useReferenceFrameMotionVectors()
    }

    /** Returns the temporal sign bias for one current-frame reference.
     * 
     * References whose order hint lies after the current frame have the opposite motion-vector
     * direction from past references when reused as extended spatial candidates.
     * 
     * @param referenceFrame the current reference in internal LAST..ALTREF order
     * @return whether the supplied reference has future-frame sign bias
     */
    fun signBias(referenceFrame: Int): Boolean {
        return referenceSignBiases[checkIndexCompat(referenceFrame, referenceSignBiases.size)]
    }

    /** Returns the projected predictor for one current reference at an 8x8 coordinate, or `null`.
     * 
     * @param x8 the frame-relative X coordinate in 8x8 units
     * @param y8 the frame-relative Y coordinate in 8x8 units
     * @param referenceFrame the current reference in internal LAST..ALTREF order
     * @return the projected motion-vector predictor, or `null`
     */
    fun motionVectorAt(x8: Int, y8: Int, referenceFrame: Int): MotionVector? {
        if (referenceFrame < 0 || referenceFrame >= referenceToCurrentDistances.size) {
            throw IndexOutOfBoundsException("referenceFrame out of range: $referenceFrame")
        }
        val block = blockAt(x8, y8)
        if (block == null) {
            return null
        }
        val projected: MotionVector = project(
            block.motionVector,
            referenceToCurrentDistances[referenceFrame],
            block.denominator
        )
        return fixPrecision(projected, frameHeader)
    }

    /** Returns one projected block, or `null` when the coordinate is outside or empty.
     * 
     * @param x8 the frame-relative X coordinate in 8x8 units
     * @param y8 the frame-relative Y coordinate in 8x8 units
     * @return one projected temporal block, or `null`
     */
    private fun blockAt(x8: Int, y8: Int): ProjectedTemporalBlock? {
        if (x8 < 0 || x8 >= width8 || y8 < 0 || y8 >= height8) {
            return null
        }
        return blocks[y8 * width8 + x8]
    }

    /** Projects one selected reference frame's saved motion field into the current frame.
     * 
     * @param currentAssembly the current frame assembly
     * @param sourceResult the selected source-frame syntax snapshot
     * @param sourceReference the source in current-frame LAST..ALTREF order
     * @param orderHintBits the sequence order-hint width
     * @param destination the mutable destination used only during construction
     */
    private fun projectSource(
        currentAssembly: FrameAssembly,
        sourceResult: ReferenceFrameSyntaxState,
        sourceReference: Int,
        orderHintBits: Int,
        destination: Array<ProjectedTemporalBlock?>?
    ) {
        val currentHeader = currentAssembly.frameHeader()
        val sourceHeader = currentAssembly.referenceFrameHeader(sourceReference)
        if (sourceHeader == null) {
            return
        }
        val sourceSyntaxHeader = sourceResult.frameHeader()
        if (!canProjectSourceMotionField(
                sourceSyntaxHeader.frameType(),
                sourceSyntaxHeader.frameSize().codedWidth(),
                sourceSyntaxHeader.frameSize().height(),
                currentHeader.frameSize().codedWidth(),
                currentHeader.frameSize().height()
            )
        ) {
            return
        }
        val sourceToCurrent: Int = orderHintDifference(
            orderHintBits,
            sourceHeader.frameOffset(),
            currentHeader.frameOffset()
        )
        if (abs(sourceToCurrent) > 31) {
            return
        }
        val projectionDistance = if (sourceReference < 4) -sourceToCurrent else sourceToCurrent
        val sourceSign = sourceReference - 4
        for (y8 in 0..<height8) {
            val projectedRowStart = y8 and 7.inv()
            val projectedRowEnd = min(projectedRowStart + 8, height8)
            for (x8 in 0..<width8) {
                val temporalBlock =
                    sourceResult.decodedTemporalMotionBlockAt(x8, y8)
                val saved: SavedTemporalMotion? =
                    selectSavedMotion(sourceResult, temporalBlock, orderHintBits)
                if (saved == null) {
                    continue
                }

                val referenceOffset = saved.referenceOffset
                if (referenceOffset <= 0 || referenceOffset > 31) {
                    continue
                }

                val offset: MotionVector =
                    project(saved.motionVector, projectionDistance, referenceOffset)
                val projectedX8: Int = x8 + applySign(
                    abs(offset.columnEighthPel) shr 6,
                    offset.columnEighthPel xor sourceSign
                )
                val projectedY8: Int = y8 + applySign(
                    abs(offset.rowEighthPel) shr 6,
                    offset.rowEighthPel xor sourceSign
                )
                val sourceColumnGroup = x8 and 7.inv()
                val projectedColumnStart = max(sourceColumnGroup - 8, 0)
                val projectedColumnEnd = min(sourceColumnGroup + 16, width8)
                if (projectedY8 >= projectedRowStart && projectedY8 < projectedRowEnd && projectedX8 >= projectedColumnStart && projectedX8 < projectedColumnEnd) {
                    destination!![projectedY8 * width8 + projectedX8] = ProjectedTemporalBlock(
                        saved.motionVector,
                        referenceOffset
                    )
                }
            }
        }
    }

    /** One saved temporal motion component and its signed source-to-reference offset.
     * 
     * @param motionVector the saved motion vector in eighth-pel units
     * @param referenceOffset the wrapped source-to-reference order-hint difference
     */
    data class SavedTemporalMotion(
        val motionVector: MotionVector,
        val referenceOffset: Int
    ) {
        init {
            requireNotNullCompat(motionVector, "motionVector")
            require(referenceOffset != 0) { "referenceOffset == 0" }
        }
    }

    /** One saved temporal motion component projected onto the current frame grid.
     * 
     * @param motionVector the original saved motion vector in eighth-pel units
     * @param denominator the positive source-reference distance in `[1, 31]`
     */
    data class ProjectedTemporalBlock(
        val motionVector: MotionVector,
        val denominator: Int
    ) {
        init {
            requireNotNullCompat(motionVector, "motionVector")
            require(!(denominator <= 0 || denominator > 31)) { "denominator out of range: $denominator" }
        }
    }

    companion object {
        /** Fixed-point reciprocals used by AV1 temporal motion-vector projection. */
        val DIV_MULTIPLIERS: IntArray = intArrayOf(
            0, 16384, 8192, 5461, 4096, 3276, 2730, 2340,
            2048, 1820, 1638, 1489, 1365, 1260, 1170, 1092,
            1024, 963, 910, 862, 819, 780, 744, 712,
            682, 655, 630, 606, 585, 564, 546, 528
        )

        /** Creates the projected temporal field for one completed frame assembly.
         * 
         * Missing reference syntax snapshots are treated as unavailable temporal sources. This is
         * required for legal random-access points whose runtime reference slots have not been filled.
         * 
         * @param assembly the current completed frame assembly
         * @param referenceSyntaxResults the syntax snapshots indexed by runtime reference slot
         * @return the immutable projected temporal field for the current frame
         */
        fun create(
            assembly: FrameAssembly,
            referenceSyntaxResults: Array<ReferenceFrameSyntaxState?>?
        ): ReferenceMotionVectorProjection {
            val nonNullAssembly = requireNotNullCompat(assembly, "assembly")
            val nonNullReferenceSyntaxResults =
                requireNotNullCompat(
                    referenceSyntaxResults,
                    "referenceSyntaxResults"
                )
            require(nonNullReferenceSyntaxResults.size == 8) { "referenceSyntaxResults.length != 8: " + nonNullReferenceSyntaxResults.size }

            val currentHeader = nonNullAssembly.frameHeader()
            val width8 = (currentHeader.frameSize().codedWidth() + 7) shr 3
            val height8 = (currentHeader.frameSize().height() + 7) shr 3
            val orderHintBits = nonNullAssembly.sequenceHeader().features().orderHintBits()
            val referenceDistances = IntArray(7)
            val referenceSignBiases = BooleanArray(7)
            for (referenceFrame in referenceDistances.indices) {
                val referenceHeader = nonNullAssembly.referenceFrameHeader(referenceFrame)
                if (referenceHeader != null) {
                    referenceDistances[referenceFrame] = clamp(
                        orderHintDifference(
                            orderHintBits,
                            currentHeader.frameOffset(),
                            referenceHeader.frameOffset()
                        ),
                        -31,
                        31
                    )
                    referenceSignBiases[referenceFrame] = hasFutureSignBias(
                        orderHintBits,
                        referenceHeader.frameOffset(),
                        currentHeader.frameOffset()
                    )
                }
            }
            val blocks: Array<ProjectedTemporalBlock?>? =
                arrayOfNullsCompat<ProjectedTemporalBlock?>(width8 * height8)
            val result = ReferenceMotionVectorProjection(
                currentHeader,
                width8,
                height8,
                referenceDistances,
                referenceSignBiases,
                blocks
            )
            if (!currentHeader.useReferenceFrameMotionVectors() || orderHintBits == 0) {
                return result
            }

            val sourceReferences: IntArray = selectTemporalSources(
                nonNullAssembly,
                nonNullReferenceSyntaxResults,
                orderHintBits
            )
            for (sourceReference in sourceReferences) {
                val slot = currentHeader.referenceFrameIndex(sourceReference)
                val sourceResult = nonNullReferenceSyntaxResults[slot]
                if (sourceResult != null) {
                    result.projectSource(
                        nonNullAssembly,
                        sourceResult,
                        sourceReference,
                        orderHintBits,
                        blocks
                    )
                }
            }
            return ReferenceMotionVectorProjection(
                currentHeader,
                width8,
                height8,
                referenceDistances,
                referenceSignBiases,
                blocks
            )
        }

        /** Returns the sign bias obtained from one reference-to-current order-hint difference.
         * 
         * Computing the reference-to-current direction directly is required when the two offsets are
         * exactly half of the modular order-hint range apart; reversing and negating the operands is
         * not equivalent at that boundary.
         * 
         * @param orderHintBits the number of order-hint bits
         * @param referenceOffset the reference frame order hint
         * @param currentOffset the current frame order hint
         * @return whether the reference lies in the positive future direction
         */
        fun hasFutureSignBias(
            orderHintBits: Int,
            referenceOffset: Int,
            currentOffset: Int
        ): Boolean {
            return orderHintDifference(orderHintBits, referenceOffset, currentOffset) > 0
        }

        /** Returns whether a saved frame can seed the current frame's temporal motion field.
         * 
         * Motion fields use an 8x8-aligned 4x4 frame grid, so pixel dimensions may differ within the
         * same final 8-pixel span without making the saved field incompatible.
         * 
         * @param sourceFrameType the saved frame type
         * @param sourceWidth the saved coded width in pixels
         * @param sourceHeight the saved coded height in pixels
         * @param currentWidth the current coded width in pixels
         * @param currentHeight the current coded height in pixels
         * @return whether the saved frame has an inter motion field with compatible grid dimensions
         */
        fun canProjectSourceMotionField(
            sourceFrameType: Av1FrameType,
            sourceWidth: Int,
            sourceHeight: Int,
            currentWidth: Int,
            currentHeight: Int
        ): Boolean {
            val nonNullSourceFrameType =
                requireNotNullCompat(sourceFrameType, "sourceFrameType")
            return nonNullSourceFrameType != Av1FrameType.KEY && nonNullSourceFrameType != Av1FrameType.INTRA && ((sourceWidth + 7) shr 3) == ((currentWidth + 7) shr 3) && ((sourceHeight + 7) shr 3) == ((currentHeight + 7) shr 3)
        }

        /** Selects the component that a decoded block saves in its reference-frame motion field.
         * 
         * Eligible components are visited in primary-to-secondary order, so an eligible secondary
         * component replaces the primary component. Selection intentionally precedes projection
         * validity checks: a selected half-range component may later make the saved sample unusable,
         * but must not expose the overwritten primary component as a fallback.
         * 
         * @param sourceResult the source-frame syntax snapshot
         * @param block the source temporal block, or `null`
         * @param orderHintBits the sequence order-hint width
         * @return the selected projectable motion component, or `null`
         */
        fun selectSavedMotion(
            sourceResult: ReferenceFrameSyntaxState,
            block: TemporalMotionBlock?,
            orderHintBits: Int
        ): SavedTemporalMotion? {
            if (block == null) {
                return null
            }
            var selected: SavedTemporalMotion? = savedMotion(
                sourceResult,
                block.referenceFrame0,
                block.motionVector0,
                orderHintBits
            )
            if (block.compoundReference) {
                val secondaryVector = block.motionVector1
                val secondary: SavedTemporalMotion? = savedMotion(
                    sourceResult,
                    block.referenceFrame1,
                    secondaryVector,
                    orderHintBits
                )
                if (secondary != null) {
                    selected = secondary
                }
            }
            return selected
        }

        /** Returns one component eligible for reference-frame motion-field storage, or `null`.
         * 
         * Storage uses the reference-to-source direction test from `ref_frame_side`. The returned
         * source-to-reference offset remains signed because the modular half-range is negative in
         * both directions; projection performs its stricter positive-distance check afterward.
         * 
         * @param sourceResult the source-frame syntax snapshot
         * @param referenceFrame the source-frame reference in internal LAST..ALTREF order
         * @param interMotionVector the saved motion-vector state, or `null`
         * @param orderHintBits the sequence order-hint width
         * @return one storable motion component and its signed reference offset, or `null`
         */
        fun savedMotion(
            sourceResult: ReferenceFrameSyntaxState,
            referenceFrame: Int,
            interMotionVector: InterMotionVector?,
            orderHintBits: Int
        ): SavedTemporalMotion? {
            if (interMotionVector == null || referenceFrame < 0 || referenceFrame >= 7) {
                return null
            }
            val sourceHeader = sourceResult.frameHeader()
            val referencedHeader = sourceResult.referenceFrameHeader(referenceFrame)
            if (referencedHeader == null) {
                return null
            }
            val vector = interMotionVector.vector
            if (!isSavedTemporalReference(
                    orderHintBits,
                    sourceHeader.frameOffset(),
                    referencedHeader.frameOffset()
                ) || (abs(vector.rowEighthPel) or abs(vector.columnEighthPel)) >= 4096
            ) {
                return null
            }
            return SavedTemporalMotion(
                vector,
                orderHintDifference(
                    orderHintBits,
                    sourceHeader.frameOffset(),
                    referencedHeader.frameOffset()
                )
            )
        }

        /** Returns whether one decoded reference is stored in a source frame's saved motion field.
         * 
         * The direction must be evaluated as `referenceOffset - sourceOffset`. At exactly half of the
         * modular order-hint range, reversing the operands still produces a negative value, so this
         * predicate is not equivalent to requiring a positive source-to-reference distance.
         * 
         * @param orderHintBits the number of order-hint bits
         * @param sourceOffset the decoded source frame order hint
         * @param referenceOffset the decoded component's reference-frame order hint
         * @return whether the component survives the AV1 reference-side storage filter
         */
        fun isSavedTemporalReference(
            orderHintBits: Int,
            sourceOffset: Int,
            referenceOffset: Int
        ): Boolean {
            return sourceOffset != referenceOffset
                    && orderHintDifference(orderHintBits, referenceOffset, sourceOffset) <= 0
        }

        /** Selects up to three reference frames whose temporal fields seed the current projection.
         * 
         * @param assembly the current frame assembly
         * @param referenceSyntaxResults the syntax snapshots indexed by runtime reference slot
         * @param orderHintBits the sequence order-hint width
         * @return the selected references in AV1 overwrite order
         */
        fun selectTemporalSources(
            assembly: FrameAssembly,
            referenceSyntaxResults: Array<ReferenceFrameSyntaxState?>?,
            orderHintBits: Int
        ): IntArray {
            val currentHeader = assembly.frameHeader()
            val selected = IntArray(3)
            var count = 0
            var targetCount = 2

            val lastResult: ReferenceFrameSyntaxState? =
                syntaxResult(currentHeader, referenceSyntaxResults, 0)
            val currentGoldenHeader = assembly.referenceFrameHeader(3)
            val lastAltHeader = if (lastResult == null)
                null
            else
                lastResult.referenceFrameHeader(6)
            if (lastResult != null && currentGoldenHeader != null && lastAltHeader != null && lastAltHeader.frameOffset() != currentGoldenHeader.frameOffset()) {
                selected[count++] = 0
                targetCount = 3
            }

            var referenceFrame = 4
            while (referenceFrame <= 5 && count < selected.size) {
                if (isProjectableFutureTemporalSource(
                        assembly,
                        syntaxResult(currentHeader, referenceSyntaxResults, referenceFrame),
                        referenceFrame,
                        orderHintBits
                    )
                ) {
                    selected[count++] = referenceFrame
                }
                referenceFrame++
            }
            if (count < targetCount && isProjectableFutureTemporalSource(
                    assembly,
                    syntaxResult(currentHeader, referenceSyntaxResults, 6),
                    6,
                    orderHintBits
                )
            ) {
                selected[count++] = 6
            }
            if (count < targetCount && syntaxResult(
                    currentHeader,
                    referenceSyntaxResults,
                    1
                ) != null
            ) {
                selected[count++] = 1
            }

            val result = IntArray(count)
            arraycopy(selected, 0, result, 0, count)
            return result
        }

        /** Returns the stored syntax result for one current-frame reference, or `null`.
         * 
         * @param currentHeader the current frame header
         * @param referenceSyntaxResults the syntax snapshots indexed by runtime reference slot
         * @param referenceFrame the current reference in internal LAST..ALTREF order
         * @return the stored syntax result, or `null`
         */
        fun syntaxResult(
            currentHeader: FrameHeader,
            referenceSyntaxResults: Array<ReferenceFrameSyntaxState?>?,
            referenceFrame: Int
        ): ReferenceFrameSyntaxState? {
            val slot = currentHeader.referenceFrameIndex(referenceFrame)
            return if (slot >= 0 && slot < referenceSyntaxResults!!.size) referenceSyntaxResults[slot] else null
        }

        /** Returns whether one backward reference is a projectable future temporal source.
         * 
         * An intra frame or incompatible motion-field grid does not consume one of the bounded
         * temporal projection slots, even when its order hint lies after the current frame.
         * 
         * @param assembly the current frame assembly
         * @param sourceResult the candidate source syntax result, or `null`
         * @param referenceFrame the candidate reference in internal LAST..ALTREF order
         * @param orderHintBits the sequence order-hint width
         * @return whether the candidate is a populated and projectable future temporal source
         */
        fun isProjectableFutureTemporalSource(
            assembly: FrameAssembly,
            sourceResult: ReferenceFrameSyntaxState?,
            referenceFrame: Int,
            orderHintBits: Int
        ): Boolean {
            if (sourceResult == null) {
                return false
            }
            val sourceHeader = assembly.referenceFrameHeader(referenceFrame)
            val currentHeader = assembly.frameHeader()
            val sourceSyntaxHeader = sourceResult.frameHeader()
            return sourceHeader != null && orderHintDifference(
                orderHintBits,
                sourceHeader.frameOffset(),
                currentHeader.frameOffset()
            ) > 0 && canProjectSourceMotionField(
                sourceSyntaxHeader.frameType(),
                sourceSyntaxHeader.frameSize().codedWidth(),
                sourceSyntaxHeader.frameSize().height(),
                currentHeader.frameSize().codedWidth(),
                currentHeader.frameSize().height()
            )
        }

        /** Scales one motion vector by a signed order-hint ratio and applies AV1 rounding and clipping.
         * 
         * @param vector the source motion vector in eighth-pel units
         * @param numerator the signed projection numerator in `[-31, 31]`
         * @param denominator the positive projection denominator in `[1, 31]`
         * @return the projected motion vector in eighth-pel units
         */
        fun project(vector: MotionVector, numerator: Int, denominator: Int): MotionVector {
            val nonNullVector = requireNotNullCompat(vector, "vector")
            require(numerator in -31..31) { "numerator out of range: $numerator" }
            require(!(denominator <= 0 || denominator >= DIV_MULTIPLIERS.size)) { "denominator out of range: $denominator" }
            val fraction: Int = numerator * DIV_MULTIPLIERS[denominator]
            return MotionVector(
                projectComponent(nonNullVector.rowEighthPel, fraction),
                projectComponent(nonNullVector.columnEighthPel, fraction)
            )
        }

        /** Projects one motion-vector component with AV1 signed rounding and clipping.
         * 
         * @param component the source component in eighth-pel units
         * @param fraction the signed Q14 scale factor
         * @return the projected component in eighth-pel units
         */
        fun projectComponent(component: Int, fraction: Int): Int {
            val scaled = component.toLong() * fraction
            val rounded = (scaled + 8192 + (scaled shr 63)) shr 14
            return clamp(rounded.toInt(), -16383, 16383)
        }

        /** Applies the current frame's signaled motion-vector precision restrictions.
         * 
         * @param vector the projected motion vector
         * @param frameHeader the current frame header
         * @return the precision-normalized motion vector
         */
        fun fixPrecision(vector: MotionVector, frameHeader: FrameHeader): MotionVector {
            val nonNullVector = requireNotNullCompat(vector, "vector")
            val nonNullHeader = requireNotNullCompat(frameHeader, "frameHeader")
            var row = nonNullVector.rowEighthPel
            var column = nonNullVector.columnEighthPel
            if (nonNullHeader.forceIntegerMotionVectors()) {
                row = (row - (row shr 15) + 3) and 7.inv()
                column = (column - (column shr 15) + 3) and 7.inv()
            } else if (!nonNullHeader.allowHighPrecisionMotionVectors()) {
                row = (row - (row shr 15)) and 1.inv()
                column = (column - (column shr 15)) and 1.inv()
            }
            return if (row == 0 && column == 0) MotionVector.zero() else MotionVector(
                row,
                column
            )
        }

        /** Applies the sign of one signed value to a non-negative magnitude.
         * 
         * @param magnitude the non-negative magnitude
         * @param signSource the signed value whose sign is applied
         * @return the signed magnitude
         */
        fun applySign(magnitude: Int, signSource: Int): Int {
            return if (signSource < 0) -magnitude else magnitude
        }

        /** Returns the wrapped order-hint difference `left - right`.
         * 
         * @param orderHintBits the number of order-hint bits
         * @param left the minuend order hint
         * @param right the subtrahend order hint
         * @return the wrapped signed order-hint difference
         */
        fun orderHintDifference(orderHintBits: Int, left: Int, right: Int): Int {
            if (orderHintBits == 0) {
                return 0
            }
            val signBit = 1 shl (orderHintBits - 1)
            val difference = left - right
            return (difference and (signBit - 1)) - (difference and signBit)
        }

        /** Clamps one integer to an inclusive range.
         * 
         * @param value the value to clamp
         * @param minimum the inclusive minimum
         * @param maximum the inclusive maximum
         * @return the clamped value
         */
        fun clamp(value: Int, minimum: Int, maximum: Int): Int {
            return max(minimum, min(maximum, value))
        }
    }
}
