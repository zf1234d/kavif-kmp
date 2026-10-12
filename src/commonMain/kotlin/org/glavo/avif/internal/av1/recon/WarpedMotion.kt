// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.model.FrameHeader.GlobalMotionParams
import org.glavo.avif.internal.av1.model.FrameHeader.GlobalMotionType
import org.glavo.avif.internal.av1.model.MotionVector


/** Derives and applies AV1 affine warped-motion models.
 * 
 * The implementation uses the integer least-squares, shear-parameter normalization, and
 * separable 193-phase interpolation process defined by AV1. Coordinates supplied to [Sample]
 * are relative to the current block origin and use eighth-pel units. */
internal object WarpedMotion {
    /** The maximum number of projection samples accepted by AV1 local warped motion. */
    const val SAMPLE_CAPACITY: Int = 8

    /** The reciprocal lookup table used by AV1 affine-model division. */
    private val DIVISOR_LOOKUP: IntArray = intArrayOf(
        16384, 16320, 16257, 16194, 16132, 16070, 16009, 15948, 15888, 15828, 15768,
        15709, 15650, 15592, 15534, 15477, 15420, 15364, 15308, 15252, 15197, 15142,
        15087, 15033, 14980, 14926, 14873, 14821, 14769, 14717, 14665, 14614, 14564,
        14513, 14463, 14413, 14364, 14315, 14266, 14218, 14170, 14122, 14075, 14028,
        13981, 13935, 13888, 13843, 13797, 13752, 13707, 13662, 13618, 13574, 13530,
        13487, 13443, 13400, 13358, 13315, 13273, 13231, 13190, 13148, 13107, 13066,
        13026, 12985, 12945, 12906, 12866, 12827, 12788, 12749, 12710, 12672, 12633,
        12596, 12558, 12520, 12483, 12446, 12409, 12373, 12336, 12300, 12264, 12228,
        12193, 12157, 12122, 12087, 12053, 12018, 11984, 11950, 11916, 11882, 11848,
        11815, 11782, 11749, 11716, 11683, 11651, 11619, 11586, 11555, 11523, 11491,
        11460, 11429, 11398, 11367, 11336, 11305, 11275, 11245, 11215, 11185, 11155,
        11125, 11096, 11067, 11038, 11009, 10980, 10951, 10923, 10894, 10866, 10838,
        10810, 10782, 10755, 10727, 10700, 10673, 10645, 10618, 10592, 10565, 10538,
        10512, 10486, 10460, 10434, 10408, 10382, 10356, 10331, 10305, 10280, 10255,
        10230, 10205, 10180, 10156, 10131, 10107, 10082, 10058, 10034, 10010, 9986,
        9963, 9939, 9916, 9892, 9869, 9846, 9823, 9800, 9777, 9754, 9732,
        9709, 9687, 9664, 9642, 9620, 9598, 9576, 9554, 9533, 9511, 9489,
        9468, 9447, 9425, 9404, 9383, 9362, 9341, 9321, 9300, 9279, 9259,
        9239, 9218, 9198, 9178, 9158, 9138, 9118, 9098, 9079, 9059, 9039,
        9020, 9001, 8981, 8962, 8943, 8924, 8905, 8886, 8867, 8849, 8830,
        8812, 8793, 8775, 8756, 8738, 8720, 8702, 8684, 8666, 8648, 8630,
        8613, 8595, 8577, 8560, 8542, 8525, 8508, 8490, 8473, 8456, 8439,
        8422, 8405, 8389, 8372, 8355, 8339, 8322, 8306, 8289, 8273, 8257,
        8240, 8224, 8208, 8192
    )

    /** The normative AV1 warped-motion filters for phases in `[-1, 2]`. */
    private val FILTERS: Array<IntArray> = arrayOf<IntArray>( // [-1, 0)
        intArrayOf(0, 0, 127, 1, 0, 0, 0, 0),
        intArrayOf(0, -1, 127, 2, 0, 0, 0, 0),
        intArrayOf(1, -3, 127, 4, -1, 0, 0, 0),
        intArrayOf(1, -4, 126, 6, -2, 1, 0, 0),
        intArrayOf(1, -5, 126, 8, -3, 1, 0, 0),
        intArrayOf(1, -6, 125, 11, -4, 1, 0, 0),
        intArrayOf(1, -7, 124, 13, -4, 1, 0, 0),
        intArrayOf(2, -8, 123, 15, -5, 1, 0, 0),
        intArrayOf(2, -9, 122, 18, -6, 1, 0, 0),
        intArrayOf(2, -10, 121, 20, -6, 1, 0, 0),
        intArrayOf(2, -11, 120, 22, -7, 2, 0, 0),
        intArrayOf(2, -12, 119, 25, -8, 2, 0, 0),
        intArrayOf(3, -13, 117, 27, -8, 2, 0, 0),
        intArrayOf(3, -13, 116, 29, -9, 2, 0, 0),
        intArrayOf(3, -14, 114, 32, -10, 3, 0, 0),
        intArrayOf(3, -15, 113, 35, -10, 2, 0, 0),
        intArrayOf(3, -15, 111, 37, -11, 3, 0, 0),
        intArrayOf(3, -16, 109, 40, -11, 3, 0, 0),
        intArrayOf(3, -16, 108, 42, -12, 3, 0, 0),
        intArrayOf(4, -17, 106, 45, -13, 3, 0, 0),
        intArrayOf(4, -17, 104, 47, -13, 3, 0, 0),
        intArrayOf(4, -17, 102, 50, -14, 3, 0, 0),
        intArrayOf(4, -17, 100, 52, -14, 3, 0, 0),
        intArrayOf(4, -18, 98, 55, -15, 4, 0, 0),
        intArrayOf(4, -18, 96, 58, -15, 3, 0, 0),
        intArrayOf(4, -18, 94, 60, -16, 4, 0, 0),
        intArrayOf(4, -18, 91, 63, -16, 4, 0, 0),
        intArrayOf(4, -18, 89, 65, -16, 4, 0, 0),
        intArrayOf(4, -18, 87, 68, -17, 4, 0, 0),
        intArrayOf(4, -18, 85, 70, -17, 4, 0, 0),
        intArrayOf(4, -18, 82, 73, -17, 4, 0, 0),
        intArrayOf(4, -18, 80, 75, -17, 4, 0, 0),
        intArrayOf(4, -18, 78, 78, -18, 4, 0, 0),
        intArrayOf(4, -17, 75, 80, -18, 4, 0, 0),
        intArrayOf(4, -17, 73, 82, -18, 4, 0, 0),
        intArrayOf(4, -17, 70, 85, -18, 4, 0, 0),
        intArrayOf(4, -17, 68, 87, -18, 4, 0, 0),
        intArrayOf(4, -16, 65, 89, -18, 4, 0, 0),
        intArrayOf(4, -16, 63, 91, -18, 4, 0, 0),
        intArrayOf(4, -16, 60, 94, -18, 4, 0, 0),
        intArrayOf(3, -15, 58, 96, -18, 4, 0, 0),
        intArrayOf(4, -15, 55, 98, -18, 4, 0, 0),
        intArrayOf(3, -14, 52, 100, -17, 4, 0, 0),
        intArrayOf(3, -14, 50, 102, -17, 4, 0, 0),
        intArrayOf(3, -13, 47, 104, -17, 4, 0, 0),
        intArrayOf(3, -13, 45, 106, -17, 4, 0, 0),
        intArrayOf(3, -12, 42, 108, -16, 3, 0, 0),
        intArrayOf(3, -11, 40, 109, -16, 3, 0, 0),
        intArrayOf(3, -11, 37, 111, -15, 3, 0, 0),
        intArrayOf(2, -10, 35, 113, -15, 3, 0, 0),
        intArrayOf(3, -10, 32, 114, -14, 3, 0, 0),
        intArrayOf(2, -9, 29, 116, -13, 3, 0, 0),
        intArrayOf(2, -8, 27, 117, -13, 3, 0, 0),
        intArrayOf(2, -8, 25, 119, -12, 2, 0, 0),
        intArrayOf(2, -7, 22, 120, -11, 2, 0, 0),
        intArrayOf(1, -6, 20, 121, -10, 2, 0, 0),
        intArrayOf(1, -6, 18, 122, -9, 2, 0, 0),
        intArrayOf(1, -5, 15, 123, -8, 2, 0, 0),
        intArrayOf(1, -4, 13, 124, -7, 1, 0, 0),
        intArrayOf(1, -4, 11, 125, -6, 1, 0, 0),
        intArrayOf(1, -3, 8, 126, -5, 1, 0, 0),
        intArrayOf(1, -2, 6, 126, -4, 1, 0, 0),
        intArrayOf(0, -1, 4, 127, -3, 1, 0, 0),
        intArrayOf(0, 0, 2, 127, -1, 0, 0, 0),  // [0, 1)
        intArrayOf(0, 0, 0, 127, 1, 0, 0, 0),
        intArrayOf(0, 0, -1, 127, 2, 0, 0, 0),
        intArrayOf(0, 1, -3, 127, 4, -2, 1, 0),
        intArrayOf(0, 1, -5, 127, 6, -2, 1, 0),
        intArrayOf(0, 2, -6, 126, 8, -3, 1, 0),
        intArrayOf(-1, 2, -7, 126, 11, -4, 2, -1),
        intArrayOf(-1, 3, -8, 125, 13, -5, 2, -1),
        intArrayOf(-1, 3, -10, 124, 16, -6, 3, -1),
        intArrayOf(-1, 4, -11, 123, 18, -7, 3, -1),
        intArrayOf(-1, 4, -12, 122, 20, -7, 3, -1),
        intArrayOf(-1, 4, -13, 121, 23, -8, 3, -1),
        intArrayOf(-2, 5, -14, 120, 25, -9, 4, -1),
        intArrayOf(-1, 5, -15, 119, 27, -10, 4, -1),
        intArrayOf(-1, 5, -16, 118, 30, -11, 4, -1),
        intArrayOf(-2, 6, -17, 116, 33, -12, 5, -1),
        intArrayOf(-2, 6, -17, 114, 35, -12, 5, -1),
        intArrayOf(-2, 6, -18, 113, 38, -13, 5, -1),
        intArrayOf(-2, 7, -19, 111, 41, -14, 6, -2),
        intArrayOf(-2, 7, -19, 110, 43, -15, 6, -2),
        intArrayOf(-2, 7, -20, 108, 46, -15, 6, -2),
        intArrayOf(-2, 7, -20, 106, 49, -16, 6, -2),
        intArrayOf(-2, 7, -21, 104, 51, -16, 7, -2),
        intArrayOf(-2, 7, -21, 102, 54, -17, 7, -2),
        intArrayOf(-2, 8, -21, 100, 56, -18, 7, -2),
        intArrayOf(-2, 8, -22, 98, 59, -18, 7, -2),
        intArrayOf(-2, 8, -22, 96, 62, -19, 7, -2),
        intArrayOf(-2, 8, -22, 94, 64, -19, 7, -2),
        intArrayOf(-2, 8, -22, 91, 67, -20, 8, -2),
        intArrayOf(-2, 8, -22, 89, 69, -20, 8, -2),
        intArrayOf(-2, 8, -22, 87, 72, -21, 8, -2),
        intArrayOf(-2, 8, -21, 84, 74, -21, 8, -2),
        intArrayOf(-2, 8, -22, 82, 77, -21, 8, -2),
        intArrayOf(-2, 8, -21, 79, 79, -21, 8, -2),
        intArrayOf(-2, 8, -21, 77, 82, -22, 8, -2),
        intArrayOf(-2, 8, -21, 74, 84, -21, 8, -2),
        intArrayOf(-2, 8, -21, 72, 87, -22, 8, -2),
        intArrayOf(-2, 8, -20, 69, 89, -22, 8, -2),
        intArrayOf(-2, 8, -20, 67, 91, -22, 8, -2),
        intArrayOf(-2, 7, -19, 64, 94, -22, 8, -2),
        intArrayOf(-2, 7, -19, 62, 96, -22, 8, -2),
        intArrayOf(-2, 7, -18, 59, 98, -22, 8, -2),
        intArrayOf(-2, 7, -18, 56, 100, -21, 8, -2),
        intArrayOf(-2, 7, -17, 54, 102, -21, 7, -2),
        intArrayOf(-2, 7, -16, 51, 104, -21, 7, -2),
        intArrayOf(-2, 6, -16, 49, 106, -20, 7, -2),
        intArrayOf(-2, 6, -15, 46, 108, -20, 7, -2),
        intArrayOf(-2, 6, -15, 43, 110, -19, 7, -2),
        intArrayOf(-2, 6, -14, 41, 111, -19, 7, -2),
        intArrayOf(-1, 5, -13, 38, 113, -18, 6, -2),
        intArrayOf(-1, 5, -12, 35, 114, -17, 6, -2),
        intArrayOf(-1, 5, -12, 33, 116, -17, 6, -2),
        intArrayOf(-1, 4, -11, 30, 118, -16, 5, -1),
        intArrayOf(-1, 4, -10, 27, 119, -15, 5, -1),
        intArrayOf(-1, 4, -9, 25, 120, -14, 5, -2),
        intArrayOf(-1, 3, -8, 23, 121, -13, 4, -1),
        intArrayOf(-1, 3, -7, 20, 122, -12, 4, -1),
        intArrayOf(-1, 3, -7, 18, 123, -11, 4, -1),
        intArrayOf(-1, 3, -6, 16, 124, -10, 3, -1),
        intArrayOf(-1, 2, -5, 13, 125, -8, 3, -1),
        intArrayOf(-1, 2, -4, 11, 126, -7, 2, -1),
        intArrayOf(0, 1, -3, 8, 126, -6, 2, 0),
        intArrayOf(0, 1, -2, 6, 127, -5, 1, 0),
        intArrayOf(0, 1, -2, 4, 127, -3, 1, 0),
        intArrayOf(0, 0, 0, 2, 127, -1, 0, 0),  // [1, 2)
        intArrayOf(0, 0, 0, 1, 127, 0, 0, 0),
        intArrayOf(0, 0, 0, -1, 127, 2, 0, 0),
        intArrayOf(0, 0, 1, -3, 127, 4, -1, 0),
        intArrayOf(0, 0, 1, -4, 126, 6, -2, 1),
        intArrayOf(0, 0, 1, -5, 126, 8, -3, 1),
        intArrayOf(0, 0, 1, -6, 125, 11, -4, 1),
        intArrayOf(0, 0, 1, -7, 124, 13, -4, 1),
        intArrayOf(0, 0, 2, -8, 123, 15, -5, 1),
        intArrayOf(0, 0, 2, -9, 122, 18, -6, 1),
        intArrayOf(0, 0, 2, -10, 121, 20, -6, 1),
        intArrayOf(0, 0, 2, -11, 120, 22, -7, 2),
        intArrayOf(0, 0, 2, -12, 119, 25, -8, 2),
        intArrayOf(0, 0, 3, -13, 117, 27, -8, 2),
        intArrayOf(0, 0, 3, -13, 116, 29, -9, 2),
        intArrayOf(0, 0, 3, -14, 114, 32, -10, 3),
        intArrayOf(0, 0, 3, -15, 113, 35, -10, 2),
        intArrayOf(0, 0, 3, -15, 111, 37, -11, 3),
        intArrayOf(0, 0, 3, -16, 109, 40, -11, 3),
        intArrayOf(0, 0, 3, -16, 108, 42, -12, 3),
        intArrayOf(0, 0, 4, -17, 106, 45, -13, 3),
        intArrayOf(0, 0, 4, -17, 104, 47, -13, 3),
        intArrayOf(0, 0, 4, -17, 102, 50, -14, 3),
        intArrayOf(0, 0, 4, -17, 100, 52, -14, 3),
        intArrayOf(0, 0, 4, -18, 98, 55, -15, 4),
        intArrayOf(0, 0, 4, -18, 96, 58, -15, 3),
        intArrayOf(0, 0, 4, -18, 94, 60, -16, 4),
        intArrayOf(0, 0, 4, -18, 91, 63, -16, 4),
        intArrayOf(0, 0, 4, -18, 89, 65, -16, 4),
        intArrayOf(0, 0, 4, -18, 87, 68, -17, 4),
        intArrayOf(0, 0, 4, -18, 85, 70, -17, 4),
        intArrayOf(0, 0, 4, -18, 82, 73, -17, 4),
        intArrayOf(0, 0, 4, -18, 80, 75, -17, 4),
        intArrayOf(0, 0, 4, -18, 78, 78, -18, 4),
        intArrayOf(0, 0, 4, -17, 75, 80, -18, 4),
        intArrayOf(0, 0, 4, -17, 73, 82, -18, 4),
        intArrayOf(0, 0, 4, -17, 70, 85, -18, 4),
        intArrayOf(0, 0, 4, -17, 68, 87, -18, 4),
        intArrayOf(0, 0, 4, -16, 65, 89, -18, 4),
        intArrayOf(0, 0, 4, -16, 63, 91, -18, 4),
        intArrayOf(0, 0, 4, -16, 60, 94, -18, 4),
        intArrayOf(0, 0, 3, -15, 58, 96, -18, 4),
        intArrayOf(0, 0, 4, -15, 55, 98, -18, 4),
        intArrayOf(0, 0, 3, -14, 52, 100, -17, 4),
        intArrayOf(0, 0, 3, -14, 50, 102, -17, 4),
        intArrayOf(0, 0, 3, -13, 47, 104, -17, 4),
        intArrayOf(0, 0, 3, -13, 45, 106, -17, 4),
        intArrayOf(0, 0, 3, -12, 42, 108, -16, 3),
        intArrayOf(0, 0, 3, -11, 40, 109, -16, 3),
        intArrayOf(0, 0, 3, -11, 37, 111, -15, 3),
        intArrayOf(0, 0, 2, -10, 35, 113, -15, 3),
        intArrayOf(0, 0, 3, -10, 32, 114, -14, 3),
        intArrayOf(0, 0, 2, -9, 29, 116, -13, 3),
        intArrayOf(0, 0, 2, -8, 27, 117, -13, 3),
        intArrayOf(0, 0, 2, -8, 25, 119, -12, 2),
        intArrayOf(0, 0, 2, -7, 22, 120, -11, 2),
        intArrayOf(0, 0, 1, -6, 20, 121, -10, 2),
        intArrayOf(0, 0, 1, -6, 18, 122, -9, 2),
        intArrayOf(0, 0, 1, -5, 15, 123, -8, 2),
        intArrayOf(0, 0, 1, -4, 13, 124, -7, 1),
        intArrayOf(0, 0, 1, -4, 11, 125, -6, 1),
        intArrayOf(0, 0, 1, -3, 8, 126, -5, 1),
        intArrayOf(0, 0, 1, -2, 6, 126, -4, 1),
        intArrayOf(0, 0, 0, -1, 4, 127, -3, 1),
        intArrayOf(0, 0, 0, 0, 2, 127, -1, 0),  // Dummy row replicating phase 191.
        intArrayOf(0, 0, 0, 0, 2, 127, -1, 0)
    )

    /** Derives one AV1 local affine model from projectable neighbor samples.
     * 
     * @param samples the projectable motion samples
     * @param sampleCount the number of populated samples
     * @param blockWidth4 the current block width in 4x4 units
     * @param blockHeight4 the current block height in 4x4 units
     * @param motionVector the current block motion vector
     * @param blockX4 the current block X origin in 4x4 units
     * @param blockY4 the current block Y origin in 4x4 units
     * @return the derived and normalized model
     */
    fun derive(
        samples: Array<Sample>,
        sampleCount: Int,
        blockWidth4: Int,
        blockHeight4: Int,
        motionVector: MotionVector,
        blockX4: Int,
        blockY4: Int
    ): Model {
        val selected: Array<Sample> =
            copyOfCompat(requireNotNullCompat(samples, "samples"), sampleCount)
        val checkedMotionVector = requireNotNullCompat(motionVector, "motionVector")
        require(!(sampleCount <= 0 || sampleCount > SAMPLE_CAPACITY)) { "sampleCount out of range: $sampleCount" }

        val differences = IntArray(sampleCount)
        var accepted = 0
        val threshold = 4 * clamp(max(blockWidth4, blockHeight4), 4, 28)
        for (i in 0..<sampleCount) {
            val sample: Sample = requireNotNullCompat(selected[i], "samples[i]")
            val difference = abs(
                sample.destinationX - sample.sourceX
                        - checkedMotionVector.columnEighthPel
            ) + abs(
                sample.destinationY - sample.sourceY
                        - checkedMotionVector.rowEighthPel
            )
            if (difference > threshold) {
                differences[i] = -1
            } else {
                differences[i] = difference
                accepted++
            }
        }
        if (accepted == 0) {
            accepted = 1
        } else {
            var front = 0
            var back = sampleCount - 1
            var replaced = 0
            while (replaced < sampleCount - accepted) {
                while (differences[front] != -1) {
                    front++
                }
                while (differences[back] == -1) {
                    back--
                }
                if (front > back) {
                    break
                }
                differences[front] = differences[back]
                selected[front] = selected[back]
                replaced++
                front++
                back--
            }
        }

        val matrix = IntArray(6)
        if (!findAffine(
                selected,
                accepted,
                blockWidth4,
                blockHeight4,
                checkedMotionVector,
                blockX4,
                blockY4,
                matrix
            )
        ) {
            return WarpedMotion.Model(false, matrix, 0, 0, 0, 0)
        }
        return normalizeShear(matrix)
    }

    /** Creates a normalized affine model from decoded frame-level global-motion parameters.
     * 
     * @param parameters the decoded rotation-zoom or affine global-motion parameters
     * @return the normalized global warped-motion model
     */
    fun fromGlobalMotion(parameters: GlobalMotionParams): Model {
        val nonNullParameters = requireNotNullCompat(parameters, "parameters")
        require(
            !(nonNullParameters.type() != GlobalMotionType.ROTATION_ZOOM
                    && nonNullParameters.type() != GlobalMotionType.AFFINE)
        ) { "Global motion is not affine: " + nonNullParameters.type() }
        val matrix = IntArray(6)
        for (index in matrix.indices) {
            matrix[index] = nonNullParameters.matrix(index)
        }
        return normalizeShear(matrix)
    }

    /** Applies one affine warped model to a plane region.
     * 
     * @param destinationPlane the mutable destination plane
     * @param referencePlane the immutable reference plane
     * @param destinationX the destination block X origin in plane samples
     * @param destinationY the destination block Y origin in plane samples
     * @param visibleWidth the visible destination width in plane samples
     * @param visibleHeight the visible destination height in plane samples
     * @param codedWidth the coded block width in plane samples
     * @param codedHeight the coded block height in plane samples
     * @param blockX4 the luma block X origin in 4x4 units
     * @param blockY4 the luma block Y origin in 4x4 units
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param model the normalized affine model
     */
    fun predictPlane(
        destinationPlane: MutablePlaneBuffer,
        referencePlane: PaddedPlane,
        destinationX: Int,
        destinationY: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        codedWidth: Int,
        codedHeight: Int,
        blockX4: Int,
        blockY4: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        model: Model
    ) {
        val checkedDestination =
            requireNotNullCompat(destinationPlane, "destinationPlane")
        val checkedReference = requireNotNullCompat(referencePlane, "referencePlane")
        val checkedModel = requireNotNullCompat(model, "model")
        val predictions = predictPlaneSamples(
            checkedReference,
            visibleWidth,
            visibleHeight,
            codedWidth,
            codedHeight,
            blockX4,
            blockY4,
            subsamplingX,
            subsamplingY,
            checkedDestination.bitDepth(),
            0,
            checkedModel
        )
        for (y in 0..<visibleHeight) {
            for (x in 0..<visibleWidth) {
                checkedDestination.setSample(
                    destinationX + x,
                    destinationY + y,
                    clamp(predictions[y * visibleWidth + x], 0, checkedDestination.maxSampleValue())
                )
            }
        }
    }

    /** Returns affine warped predictors retaining AV1's compound-inter fractional precision.
     * 
     * The returned caller-owned array is row-major and contains `visibleWidth * visibleHeight`
     * signed predictors. Each value retains four fractional bits for 8- and 10-bit input, or two
     * fractional bits for 12-bit input. Values are neither clipped nor rounded to sample precision.
     * 
     * @param referencePlane the immutable reference plane
     * @param visibleWidth the visible prediction width in plane samples
     * @param visibleHeight the visible prediction height in plane samples
     * @param codedWidth the coded block width in plane samples
     * @param codedHeight the coded block height in plane samples
     * @param blockX4 the luma block X origin in 4x4 units
     * @param blockY4 the luma block Y origin in 4x4 units
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param bitDepth the decoded sample bit depth
     * @param model the normalized affine model
     * @return the row-major higher-precision compound predictors
     */
    fun predictCompoundPlane(
        referencePlane: PaddedPlane,
        visibleWidth: Int,
        visibleHeight: Int,
        codedWidth: Int,
        codedHeight: Int,
        blockX4: Int,
        blockY4: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        bitDepth: Int,
        model: Model
    ): IntArray {
        val retainedBits = if (bitDepth == 12) 2 else 4
        return predictPlaneSamples(
            referencePlane,
            visibleWidth,
            visibleHeight,
            codedWidth,
            codedHeight,
            blockX4,
            blockY4,
            subsamplingX,
            subsamplingY,
            bitDepth,
            retainedBits,
            model
        )
    }

    /** Computes one affine warped plane while retaining the requested output precision.
     * 
     * @param referencePlane the immutable reference plane
     * @param visibleWidth the visible prediction width in plane samples
     * @param visibleHeight the visible prediction height in plane samples
     * @param codedWidth the coded block width in plane samples
     * @param codedHeight the coded block height in plane samples
     * @param blockX4 the luma block X origin in 4x4 units
     * @param blockY4 the luma block Y origin in 4x4 units
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param bitDepth the decoded sample bit depth
     * @param retainedBits the number of fractional bits retained in each result
     * @param model the normalized affine model
     * @return the row-major signed predictors at the requested precision
     */
    private fun predictPlaneSamples(
        referencePlane: PaddedPlane,
        visibleWidth: Int,
        visibleHeight: Int,
        codedWidth: Int,
        codedHeight: Int,
        blockX4: Int,
        blockY4: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        bitDepth: Int,
        retainedBits: Int,
        model: Model
    ): IntArray {
        val checkedReference = requireNotNullCompat(referencePlane, "referencePlane")
        val checkedModel = requireNotNullCompat(model, "model")
        require(checkedModel.affine()) { "Warped prediction requires an affine model" }
        val intermediateBits = if (bitDepth == 12) 2 else 4
        require(!(bitDepth != 8 && bitDepth != 10 && bitDepth != 12)) { "Unsupported warped-prediction bit depth: $bitDepth" }
        require(retainedBits in 0..intermediateBits) { "retainedBits out of range: $retainedBits" }
        val predictions = IntArray(visibleWidth * visibleHeight)
        var blockY = 0
        while (blockY < codedHeight) {
            val sourceY = blockY4 * 4 + ((blockY + 4) shl subsamplingY)
            val matrix3Y = checkedModel.matrix(3).toLong() * sourceY + checkedModel.matrix(0)
            val matrix5Y = checkedModel.matrix(5).toLong() * sourceY + checkedModel.matrix(1)
            var blockX = 0
            while (blockX < codedWidth) {
                val sourceX = blockX4 * 4 + ((blockX + 4) shl subsamplingX)
                val mappedX =
                    (checkedModel.matrix(2).toLong() * sourceX + matrix3Y) shr subsamplingX
                val mappedY =
                    (checkedModel.matrix(4).toLong() * sourceX + matrix5Y) shr subsamplingY
                val integerX = (mappedX shr 16).toInt() - 4
                val phaseX = (((mappedX.toInt() and 0xFFFF)
                        - checkedModel.alpha() * 4 - checkedModel.beta() * 7)) and 0x3F.inv()
                val integerY = (mappedY shr 16).toInt() - 4
                val phaseY = (((mappedY.toInt() and 0xFFFF)
                        - checkedModel.gamma() * 4 - checkedModel.delta() * 4)) and 0x3F.inv()
                predict8x8(
                    checkedReference,
                    predictions,
                    visibleWidth,
                    blockX,
                    blockY,
                    max(0, min(8, visibleWidth - blockX)),
                    max(0, min(8, visibleHeight - blockY)),
                    integerX,
                    integerY,
                    phaseX,
                    phaseY,
                    checkedModel,
                    intermediateBits,
                    retainedBits
                )
                blockX += 8
            }
            blockY += 8
        }
        return predictions
    }

    /** Predicts one 8x8 affine warped region.
     * 
     * @param referencePlane the immutable reference plane
     * @param predictions the row-major destination predictor array
     * @param predictionStride the destination predictor row stride
     * @param destinationX the destination X origin within the predictor array
     * @param destinationY the destination Y origin within the predictor array
     * @param visibleWidth the visible width to store
     * @param visibleHeight the visible height to store
     * @param sourceX the integer reference X origin
     * @param sourceY the integer reference Y origin
     * @param phaseX the starting horizontal warped phase
     * @param phaseY the starting vertical warped phase
     * @param model the normalized affine model
     * @param intermediateBits the horizontal intermediate precision
     * @param retainedBits the fractional precision retained after vertical filtering
     */
    private fun predict8x8(
        referencePlane: PaddedPlane,
        predictions: IntArray,
        predictionStride: Int,
        destinationX: Int,
        destinationY: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sourceX: Int,
        sourceY: Int,
        phaseX: Int,
        phaseY: Int,
        model: Model,
        intermediateBits: Int,
        retainedBits: Int
    ) {
        val intermediate = Array<IntArray>(15) { IntArray(8) }
        var horizontalPhase = phaseX
        run {
            var y = 0
            while (y < 15) {
                val sampleY = WarpedMotion.clamp(sourceY - 3 + y, 0, referencePlane.height() - 1)
                var pixelPhase = horizontalPhase
                var x = 0
                while (x < 8) {
                    val filter = WarpedMotion.filter(pixelPhase)
                    var sum: Long = 0
                    for (tap in 0..7) {
                        val sampleX =
                            WarpedMotion.clamp(sourceX + x + tap - 3, 0, referencePlane.width() - 1)
                        sum += filter[tap].toLong() * referencePlane.sample(sampleX, sampleY)
                    }
                    intermediate[y][x] = WarpedMotion.roundShiftBiased(sum, 7 - intermediateBits)
                    x++
                    pixelPhase += model.alpha()
                }
                y++
                horizontalPhase += model.beta()
            }
        }

        var verticalPhase = phaseY
        var y = 0
        while (y < 8) {
            var pixelPhase = verticalPhase
            var x = 0
            while (x < 8) {
                val filter = filter(pixelPhase)
                var sum: Long = 0
                for (tap in 0..7) {
                    sum += filter[tap].toLong() * intermediate[y + tap][x]
                }
                if (x < visibleWidth && y < visibleHeight) {
                    predictions[(destinationY + y) * predictionStride + destinationX + x] =
                        roundShiftBiased(sum, 7 + intermediateBits - retainedBits)
                }
                x++
                pixelPhase += model.gamma()
            }
            y++
            verticalPhase += model.delta()
        }
    }

    /** Returns the warped filter selected by one fixed-point phase.
     * 
     * @param phase the phase in AV1 warped-filter precision
     * @return the selected eight-tap filter
     */
    private fun filter(phase: Int): IntArray {
        val index = 64 + ((phase + 512) shr 10)
        check(!(index < 0 || index >= FILTERS.size)) { "Warped-motion filter phase out of range: $phase" }
        return FILTERS[index]
    }

    /** Solves the AV1 integer affine least-squares system.
     * 
     * @param samples the selected projection samples
     * @param sampleCount the number of selected samples
     * @param blockWidth4 the current block width in 4x4 units
     * @param blockHeight4 the current block height in 4x4 units
     * @param motionVector the current block motion vector
     * @param blockX4 the current block X origin in 4x4 units
     * @param blockY4 the current block Y origin in 4x4 units
     * @param matrix the destination affine matrix
     * @return whether the system has a nonzero determinant
     */
    private fun findAffine(
        samples: Array<Sample>,
        sampleCount: Int,
        blockWidth4: Int,
        blockHeight4: Int,
        motionVector: MotionVector,
        blockX4: Int,
        blockY4: Int,
        matrix: IntArray
    ): Boolean {
        var a00 = 0
        var a01 = 0
        var a11 = 0
        var bx0 = 0
        var bx1 = 0
        var by0 = 0
        var by1 = 0
        val relativeCenterY = (2 * blockHeight4 - 1) * 8
        val relativeCenterX = (2 * blockWidth4 - 1) * 8
        val destinationCenterY = relativeCenterY + motionVector.rowEighthPel
        val destinationCenterX = relativeCenterX + motionVector.columnEighthPel
        val absoluteCenterY = blockY4 * 4 + 2 * blockHeight4 - 1
        val absoluteCenterX = blockX4 * 4 + 2 * blockWidth4 - 1

        for (i in 0..<sampleCount) {
            val sample: Sample = requireNotNullCompat(samples[i], "samples[i]")
            val destinationX = sample.destinationX - destinationCenterX
            val destinationY = sample.destinationY - destinationCenterY
            val sourceX = sample.sourceX - relativeCenterX
            val sourceY = sample.sourceY - relativeCenterY
            if (abs(sourceX - destinationX) < 256 && abs(sourceY - destinationY) < 256) {
                a00 += ((sourceX * sourceX) shr 2) + sourceX * 2 + 8
                a01 += ((sourceX * sourceY) shr 2) + sourceX + sourceY + 4
                a11 += ((sourceY * sourceY) shr 2) + sourceY * 2 + 8
                bx0 += ((sourceX * destinationX) shr 2) + sourceX + destinationX + 8
                bx1 += ((sourceY * destinationX) shr 2) + sourceY + destinationX + 4
                by0 += ((sourceX * destinationY) shr 2) + sourceX + destinationY + 4
                by1 += ((sourceY * destinationY) shr 2) + sourceY + destinationY + 8
            }
        }

        val determinant = a00.toLong() * a11 - a01.toLong() * a01
        if (determinant == 0L) {
            return false
        }
        val absoluteDeterminant = abs(determinant)
        var shift: Int = 63 - absoluteDeterminant.countLeadingZeroBits()
        val exponentRemainder = absoluteDeterminant - (1L shl shift)
        val lookupIndex = if (shift > 8)
            (exponentRemainder + (1L shl (shift - 9))) shr (shift - 8)
        else
            exponentRemainder shl (8 - shift)
        var inverseDeterminant = DIVISOR_LOOKUP[lookupIndex.toInt()]
        if (determinant < 0) {
            inverseDeterminant = -inverseDeterminant
        }
        shift += 14
        shift -= 16
        if (shift < 0) {
            inverseDeterminant = inverseDeterminant shl (-shift)
            shift = 0
        }

        matrix[2] = multiplyAndRoundDiagonal(
            a11.toLong() * bx0 - a01.toLong() * bx1,
            inverseDeterminant,
            shift
        )
        matrix[3] = multiplyAndRoundNonDiagonal(
            a00.toLong() * bx1 - a01.toLong() * bx0,
            inverseDeterminant,
            shift
        )
        matrix[4] = multiplyAndRoundNonDiagonal(
            a11.toLong() * by0 - a01.toLong() * by1,
            inverseDeterminant,
            shift
        )
        matrix[5] = multiplyAndRoundDiagonal(
            a00.toLong() * by1 - a01.toLong() * by0,
            inverseDeterminant,
            shift
        )
        matrix[0] = clamp(
            motionVector.columnEighthPel * 0x2000
                    - (absoluteCenterX * (matrix[2] - 0x10000) + absoluteCenterY * matrix[3]),
            -0x800000,
            0x7FFFFF
        )
        matrix[1] = clamp(
            motionVector.rowEighthPel * 0x2000
                    - (absoluteCenterX * matrix[4] + absoluteCenterY * (matrix[5] - 0x10000)),
            -0x800000,
            0x7FFFFF
        )
        return true
    }

    /** Normalizes affine shear parameters and checks AV1's validity bounds.
     * 
     * @param matrix the six affine matrix entries
     * @return the normalized model
     */
    private fun normalizeShear(matrix: IntArray): Model {
        if (matrix[2] <= 0) {
            return WarpedMotion.Model(false, matrix, 0, 0, 0, 0)
        }
        val alpha = clipWarpParameter(matrix[2] - 0x10000)
        val beta = clipWarpParameter(matrix[3])

        val divisor = abs(matrix[2])
        var shift: Int = 31 - divisor.countLeadingZeroBits()
        val remainder = divisor - (1 shl shift)
        val lookupIndex = if (shift > 8)
            (remainder + (1 shl (shift - 9))) shr (shift - 8)
        else
            remainder shl (8 - shift)
        var inverse = DIVISOR_LOOKUP[lookupIndex]
        if (matrix[2] < 0) {
            inverse = -inverse
        }
        shift += 14
        val verticalCross = matrix[4].toLong() * 0x10000 * inverse
        val rounding = (1 shl shift) shr 1
        val gamma =
            clipWarpParameter(applySign((abs(verticalCross) + rounding) shr shift, verticalCross))
        val diagonalCross = matrix[3].toLong() * matrix[4] * inverse
        val delta = clipWarpParameter(
            (matrix[5]
                    - applySign((abs(diagonalCross) + rounding) shr shift, diagonalCross)
                    - 0x10000)
        )
        val affine = 4 * abs(alpha) + 7 * abs(beta) < 0x10000
                && 4 * abs(gamma) + 4 * abs(delta) < 0x10000
        return WarpedMotion.Model(affine, matrix, alpha, beta, gamma, delta)
    }

    /** Multiplies by a reciprocal and clips one diagonal affine entry.
     * 
     * @param value the determinant numerator
     * @param inverseDeterminant the normalized reciprocal
     * @param shift the reciprocal normalization shift
     * @return the clipped diagonal entry
     */
    private fun multiplyAndRoundDiagonal(
        value: Long,
        inverseDeterminant: Int,
        shift: Int
    ): Int {
        val product = value * inverseDeterminant
        val rounded = applySign((abs(product) + ((1L shl shift) shr 1)) shr shift, product)
        return clamp(rounded, 0xE001, 0x11FFF)
    }

    /** Multiplies by a reciprocal and clips one non-diagonal affine entry.
     * 
     * @param value the determinant numerator
     * @param inverseDeterminant the normalized reciprocal
     * @param shift the reciprocal normalization shift
     * @return the clipped non-diagonal entry
     */
    private fun multiplyAndRoundNonDiagonal(
        value: Long,
        inverseDeterminant: Int,
        shift: Int
    ): Int {
        val product = value * inverseDeterminant
        val rounded = applySign((abs(product) + ((1L shl shift) shr 1)) shr shift, product)
        return clamp(rounded, -0x1FFF, 0x1FFF)
    }

    /** Clips and quantizes one normalized shear parameter.
     * 
     * @param value the unnormalized parameter
     * @return the signed multiple of 64 in AV1's 16-bit parameter range
     */
    private fun clipWarpParameter(value: Int): Int {
        val clipped = clamp(value, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val magnitude = (abs(clipped) + 32) shr 6
        return (if (clipped < 0) -magnitude else magnitude) shl 6
    }

    /** Applies the sign of one value to a nonnegative magnitude.
     * 
     * @param magnitude the nonnegative magnitude
     * @param signedValue the value that supplies the sign
     * @return the signed integer magnitude
     */
    private fun applySign(magnitude: Long, signedValue: Long): Int {
        val result = magnitude.toInt()
        return if (signedValue < 0) -result else result
    }

    /** Rounds one signed value by adding the normative positive bias before shifting.
     * 
     * @param value the signed value
     * @param shift the nonnegative right shift
     * @return the rounded value
     */
    private fun roundShiftBiased(value: Long, shift: Int): Int {
        return ((value + ((1L shl shift) shr 1)) shr shift).toInt()
    }

    /** Clips one integer to an inclusive range.
     * 
     * @param value the value to clip
     * @param minimum the inclusive minimum
     * @param maximum the inclusive maximum
     * @return the clipped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(maximum, value))
    }

    /** One projectable neighbor motion sample.
     * 
     * @param sourceX the neighbor center X before applying its motion vector
     * @param sourceY the neighbor center Y before applying its motion vector
     * @param destinationX the neighbor center X after applying its motion vector
     * @param destinationY the neighbor center Y after applying its motion vector
     */
    internal data class Sample(
        val sourceX: Int,
        val sourceY: Int,
        val destinationX: Int,
        val destinationY: Int
    )

    /** One normalized local affine warped-motion model. */
    internal class Model(
        /** Whether the derived model passed AV1's affine shear constraints. */
        val affine: Boolean, matrix: IntArray,
        /** The normalized horizontal X derivative. */
        val alpha: Int,
        /** The normalized horizontal Y derivative. */
        val beta: Int,
        /** The normalized vertical X derivative. */
        val gamma: Int,
        /** The normalized vertical Y derivative. */
        val delta: Int
    ) {
        /** The six AV1 affine matrix entries. */
        val matrix: IntArray

        init {
            this.matrix = requireNotNullCompat(matrix, "matrix").copyOf(matrix.size)
        }

        /** Returns whether this model can use affine warped prediction.
         * 
         * @return whether this model can use affine warped prediction
         */
        fun affine(): Boolean {
            return affine
        }

        /** Returns one affine matrix entry.
         * 
         * @param index the matrix index in `[0, 5]`
         * @return the selected matrix entry
         */
        fun matrix(index: Int): Int {
            return matrix[checkIndexCompat(index, matrix.size)]
        }

        /** Returns the normalized horizontal X derivative.
         * 
         * @return the normalized horizontal X derivative
         */
        fun alpha(): Int {
            return alpha
        }

        /** Returns the normalized horizontal Y derivative.
         * 
         * @return the normalized horizontal Y derivative
         */
        fun beta(): Int {
            return beta
        }

        /** Returns the normalized vertical X derivative.
         * 
         * @return the normalized vertical X derivative
         */
        fun gamma(): Int {
            return gamma
        }

        /** Returns the normalized vertical Y derivative.
         * 
         * @return the normalized vertical Y derivative
         */
        fun delta(): Int {
            return delta
        }
    }
}
