// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.model.FrameHeader.RestorationType


/** Decoded AV1 loop-restoration unit syntax for one image plane.
 * 
 * A unit can disable restoration locally, or carry either Wiener coefficients or self-guided
 * restoration projection coefficients. Frame-level `SWITCHABLE` restoration is resolved to one
 * of these concrete per-unit types before instances of this class are stored. */
class RestorationUnit private constructor(
    type: RestorationType,
    horizontalWienerCoefficient0: Int,
    horizontalWienerCoefficient1: Int,
    horizontalWienerCoefficient2: Int,
    verticalWienerCoefficient0: Int,
    verticalWienerCoefficient1: Int,
    verticalWienerCoefficient2: Int,
    selfGuidedSet: Int,
    selfGuidedProjectionCoefficient0: Int,
    selfGuidedProjectionCoefficient1: Int
) {
    /** The concrete restoration type for this unit. */
    private val type: RestorationType

    /** The first horizontal Wiener coefficient. */
    private val horizontalWienerCoefficient0: Int

    /** The second horizontal Wiener coefficient. */
    private val horizontalWienerCoefficient1: Int

    /** The third horizontal Wiener coefficient. */
    private val horizontalWienerCoefficient2: Int

    /** The first vertical Wiener coefficient. */
    private val verticalWienerCoefficient0: Int

    /** The second vertical Wiener coefficient. */
    private val verticalWienerCoefficient1: Int

    /** The third vertical Wiener coefficient. */
    private val verticalWienerCoefficient2: Int

    /** The self-guided restoration parameter set index. */
    private val selfGuidedSet: Int

    /** The first self-guided projection coefficient. */
    private val selfGuidedProjectionCoefficient0: Int

    /** The second self-guided projection coefficient. */
    private val selfGuidedProjectionCoefficient1: Int

    init {
        this.type = requireNotNullCompat(type, "type")
        require(type != RestorationType.SWITCHABLE) { "Restoration units must carry a concrete restoration type" }
        this.horizontalWienerCoefficient0 = horizontalWienerCoefficient0
        this.horizontalWienerCoefficient1 = horizontalWienerCoefficient1
        this.horizontalWienerCoefficient2 = horizontalWienerCoefficient2
        this.verticalWienerCoefficient0 = verticalWienerCoefficient0
        this.verticalWienerCoefficient1 = verticalWienerCoefficient1
        this.verticalWienerCoefficient2 = verticalWienerCoefficient2
        require(!(selfGuidedSet < 0 || selfGuidedSet >= 16)) { "selfGuidedSet out of range: $selfGuidedSet" }
        this.selfGuidedSet = selfGuidedSet
        this.selfGuidedProjectionCoefficient0 = selfGuidedProjectionCoefficient0
        this.selfGuidedProjectionCoefficient1 = selfGuidedProjectionCoefficient1
    }

    /** Returns the concrete restoration type for this unit.
     * 
     * @return the concrete restoration type for this unit
     */
    fun type(): RestorationType {
        return type
    }

    /** Returns one Wiener coefficient without allocating a coefficient table.
     * 
     * @param pass the pass index, where zero is horizontal and one is vertical
     * @param coefficient the coefficient index in `[0, 3)`
     * @return the selected Wiener coefficient
     */
    fun wienerCoefficient(pass: Int, coefficient: Int): Int {
        checkIndexCompat(coefficient, 3)
        return when (checkIndexCompat(pass, 2)) {
            0 -> when (coefficient) {
                0 -> horizontalWienerCoefficient0
                1 -> horizontalWienerCoefficient1
                2 -> horizontalWienerCoefficient2
                else -> throw AssertionError()
            }

            1 -> when (coefficient) {
                0 -> verticalWienerCoefficient0
                1 -> verticalWienerCoefficient1
                2 -> verticalWienerCoefficient2
                else -> throw AssertionError()
            }

            else -> throw AssertionError()
        }
    }

    /** Returns the self-guided restoration parameter set index.
     * 
     * @return the self-guided restoration parameter set index
     */
    fun selfGuidedSet(): Int {
        return selfGuidedSet
    }

    /** Returns one self-guided projection coefficient without allocating an array.
     * 
     * @param index the coefficient index in `[0, 2)`
     * @return the selected projection coefficient
     */
    fun selfGuidedProjectionCoefficient(index: Int): Int {
        return when (checkIndexCompat(index, 2)) {
            0 -> selfGuidedProjectionCoefficient0
            1 -> selfGuidedProjectionCoefficient1
            else -> throw AssertionError()
        }
    }

    companion object {
        /** A local unit with no loop restoration. */
        val NONE = RestorationUnit(
            RestorationType.NONE,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        )

        /** Returns a disabled restoration unit.
         * 
         * @return a disabled restoration unit
         */
        fun none(): RestorationUnit {
            return NONE
        }

        /** Creates one Wiener restoration unit.
         * 
         * @param coefficients the horizontal and vertical Wiener coefficients, in that order
         * @return one Wiener restoration unit
         */
        fun wiener(coefficients: Array<IntArray>): RestorationUnit {
            val checkedCoefficients: Array<IntArray> =
                requireNotNullCompat(coefficients, "coefficients")
            require(checkedCoefficients.size == 2) { "Wiener coefficient table must have two passes" }
            val horizontal =
                requireNotNullCompat(checkedCoefficients[0], "coefficients[0]")
            val vertical =
                requireNotNullCompat(checkedCoefficients[1], "coefficients[1]")
            require(!(horizontal.size != 3 || vertical.size != 3)) { "Each Wiener coefficient pass must contain three coefficients" }
            return wiener(
                horizontal[0], horizontal[1], horizontal[2],
                vertical[0], vertical[1], vertical[2]
            )
        }

        /** Creates one Wiener restoration unit from scalar coefficients.
         * 
         * @param horizontal0 the first horizontal coefficient
         * @param horizontal1 the second horizontal coefficient
         * @param horizontal2 the third horizontal coefficient
         * @param vertical0 the first vertical coefficient
         * @param vertical1 the second vertical coefficient
         * @param vertical2 the third vertical coefficient
         * @return one Wiener restoration unit
         */
        fun wiener(
            horizontal0: Int,
            horizontal1: Int,
            horizontal2: Int,
            vertical0: Int,
            vertical1: Int,
            vertical2: Int
        ): RestorationUnit {
            return RestorationUnit(
                RestorationType.WIENER,
                horizontal0,
                horizontal1,
                horizontal2,
                vertical0,
                vertical1,
                vertical2,
                0,
                0,
                0
            )
        }

        /** Creates one self-guided restoration unit.
         * 
         * @param set the self-guided restoration parameter set index
         * @param projectionCoefficients the two projection coefficients
         * @return one self-guided restoration unit
         */
        fun selfGuided(set: Int, projectionCoefficients: IntArray): RestorationUnit {
            val checkedCoefficients =
                requireNotNullCompat(projectionCoefficients, "projectionCoefficients")
            require(checkedCoefficients.size == 2) { "projectionCoefficients length must be 2" }
            return selfGuided(set, checkedCoefficients[0], checkedCoefficients[1])
        }

        /** Creates one self-guided restoration unit from scalar projection coefficients.
         * 
         * @param set the self-guided restoration parameter set index
         * @param coefficient0 the first projection coefficient
         * @param coefficient1 the second projection coefficient
         * @return one self-guided restoration unit
         */
        fun selfGuided(set: Int, coefficient0: Int, coefficient1: Int): RestorationUnit {
            return RestorationUnit(
                RestorationType.SELF_GUIDED,
                0,
                0,
                0,
                0,
                0,
                0,
                set,
                coefficient0,
                coefficient1
            )
        }
    }
}
