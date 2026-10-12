// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable AVIF image transform metadata from `clap`, `irot`, and `imir` item properties. */
class AvifImageTransformInfo(
    cleanApertureCropX: Int,
    cleanApertureCropY: Int,
    cleanApertureCropWidth: Int,
    cleanApertureCropHeight: Int,
    rotationCode: Int,
    mirrorAxis: Int
) {
    /** The clean-aperture crop x coordinate, or -1 when absent. */
    private val cleanApertureCropX: Int

    /** The clean-aperture crop y coordinate, or -1 when absent. */
    private val cleanApertureCropY: Int

    /** The clean-aperture crop width, or -1 when absent. */
    private val cleanApertureCropWidth: Int

    /** The clean-aperture crop height, or -1 when absent. */
    private val cleanApertureCropHeight: Int

    /** The AVIF `irot` rotation code, or -1 when absent. */
    private val rotationCode: Int

    /** The AVIF `imir` mirror axis, or -1 when absent. */
    private val mirrorAxis: Int

    init {
        require(
            !(!isAbsentCleanAperture(
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight
            )
                    && (cleanApertureCropX < 0 || cleanApertureCropY < 0 || cleanApertureCropWidth <= 0 || cleanApertureCropHeight <= 0))
        ) { "Invalid clean-aperture crop parameters" }
        require(rotationCode in -1..3) { "rotationCode must be -1 or between 0 and 3: $rotationCode" }
        require(mirrorAxis in -1..1) { "mirrorAxis must be -1, 0, or 1: $mirrorAxis" }
        require(
            !(isAbsentCleanAperture(
                cleanApertureCropX,
                cleanApertureCropY,
                cleanApertureCropWidth,
                cleanApertureCropHeight
            )
                    && rotationCode == -1 && mirrorAxis == -1)
        ) { "At least one image transform must be present" }

        this.cleanApertureCropX = cleanApertureCropX
        this.cleanApertureCropY = cleanApertureCropY
        this.cleanApertureCropWidth = cleanApertureCropWidth
        this.cleanApertureCropHeight = cleanApertureCropHeight
        this.rotationCode = rotationCode
        this.mirrorAxis = mirrorAxis
    }

    /** Returns whether a clean-aperture crop is present.
     * 
     * @return whether a clean-aperture crop is present
     */
    fun hasCleanApertureCrop(): Boolean {
        return cleanApertureCropX >= 0
    }

    /** Returns the clean-aperture crop x coordinate.
     * 
     * A value of -1 means no clean-aperture crop is present.
     * 
     * @return the clean-aperture crop x coordinate, or -1 when absent
     */
    fun cleanApertureCropX(): Int {
        return cleanApertureCropX
    }

    /** Returns the clean-aperture crop y coordinate.
     * 
     * A value of -1 means no clean-aperture crop is present.
     * 
     * @return the clean-aperture crop y coordinate, or -1 when absent
     */
    fun cleanApertureCropY(): Int {
        return cleanApertureCropY
    }

    /** Returns the clean-aperture crop width.
     * 
     * A value of -1 means no clean-aperture crop is present.
     * 
     * @return the clean-aperture crop width, or -1 when absent
     */
    fun cleanApertureCropWidth(): Int {
        return cleanApertureCropWidth
    }

    /** Returns the clean-aperture crop height.
     * 
     * A value of -1 means no clean-aperture crop is present.
     * 
     * @return the clean-aperture crop height, or -1 when absent
     */
    fun cleanApertureCropHeight(): Int {
        return cleanApertureCropHeight
    }

    /** Returns whether an `irot` rotation transform is present.
     * 
     * @return whether an `irot` rotation transform is present
     */
    fun hasRotation(): Boolean {
        return rotationCode >= 0
    }

    /** Returns the AVIF `irot` rotation code.
     * 
     * Values 0 through 3 represent 0, 90, 180, and 270 degrees counter-clockwise.
     * A value of -1 means the property is absent.
     * 
     * @return the rotation code, or -1 when absent
     */
    fun rotationCode(): Int {
        return rotationCode
    }

    /** Returns the clockwise-equivalent rotation in degrees.
     * 
     * A value of -1 means the `irot` property is absent.
     * 
     * @return the clockwise-equivalent rotation in degrees, or -1 when absent
     */
    fun rotationDegreesClockwise(): Int {
        return if (rotationCode < 0) -1 else ((4 - rotationCode) and 3) * 90
    }

    /** Returns the counter-clockwise rotation in degrees encoded by `irot`.
     * 
     * A value of -1 means the `irot` property is absent.
     * 
     * @return the counter-clockwise rotation in degrees, or -1 when absent
     */
    fun rotationDegreesCounterClockwise(): Int {
        return if (rotationCode < 0) -1 else rotationCode * 90
    }

    /** Returns whether an `imir` mirror transform is present.
     * 
     * @return whether an `imir` mirror transform is present
     */
    fun hasMirror(): Boolean {
        return mirrorAxis >= 0
    }

    /** Returns the AVIF `imir` mirror axis.
     * 
     * A value of 0 mirrors over the horizontal axis, 1 mirrors over the vertical axis,
     * and -1 means the property is absent.
     * 
     * @return the mirror axis, or -1 when absent
     */
    fun mirrorAxis(): Int {
        return mirrorAxis
    }

    companion object {
        /** Returns whether clean-aperture parameters represent an absent property.
         * 
         * @param cleanApertureCropX the clean-aperture crop x coordinate
         * @param cleanApertureCropY the clean-aperture crop y coordinate
         * @param cleanApertureCropWidth the clean-aperture crop width
         * @param cleanApertureCropHeight the clean-aperture crop height
         * @return whether the clean-aperture property is absent
         */
        fun isAbsentCleanAperture(
            cleanApertureCropX: Int,
            cleanApertureCropY: Int,
            cleanApertureCropWidth: Int,
            cleanApertureCropHeight: Int
        ): Boolean {
            return cleanApertureCropX == -1 && cleanApertureCropY == -1 && cleanApertureCropWidth == -1 && cleanApertureCropHeight == -1
        }
    }
}
