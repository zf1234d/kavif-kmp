// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.InterIntraPredictionMode


/** Generates AV1 inter-intra blend and wedge masks for the reconstruction path. */
internal object InterIntraMasks {
    /** The AV1 wedge master template width and height. */
    private const val WEDGE_MASTER_SIZE = 64

    /** The number of AV1 wedge mask indices. */
    private const val WEDGE_INDEX_COUNT = 16

    /** Horizontal wedge direction. */
    private const val WEDGE_HORIZONTAL = 0

    /** Vertical wedge direction. */
    private const val WEDGE_VERTICAL = 1

    /** 27-degree oblique wedge direction. */
    private const val WEDGE_OBLIQUE27 = 2

    /** 63-degree oblique wedge direction. */
    private const val WEDGE_OBLIQUE63 = 3

    /** 117-degree oblique wedge direction. */
    private const val WEDGE_OBLIQUE117 = 4

    /** 153-degree oblique wedge direction. */
    private const val WEDGE_OBLIQUE153 = 5

    /** Odd-row oblique wedge master border. */
    private val WEDGE_MASTER_BORDER_ODD: IntArray =
        intArrayOf(1, 2, 6, 18, 37, 53, 60, 63)

    /** Even-row oblique wedge master border. */
    private val WEDGE_MASTER_BORDER_EVEN: IntArray =
        intArrayOf(1, 4, 11, 27, 46, 58, 62, 63)

    /** Vertical wedge master border. */
    private val WEDGE_MASTER_BORDER_VERTICAL: IntArray =
        intArrayOf(0, 2, 7, 21, 43, 57, 62, 64)

    /** Inter-intra one-dimensional non-DC blend weights. */
    private val INTER_INTRA_WEIGHTS_1D: IntArray = intArrayOf(
        60, 52, 45, 39, 34, 30, 26, 22, 19, 17, 15, 13, 11, 10, 8, 7,
        6, 6, 5, 4, 4, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1
    )

    /** Wedge codebook used when the block is taller than it is wide. */
    private val WEDGE_CODEBOOK_HGTW: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(WEDGE_OBLIQUE27, 4, 4), intArrayOf(WEDGE_OBLIQUE63, 4, 4),
        intArrayOf(WEDGE_OBLIQUE117, 4, 4), intArrayOf(WEDGE_OBLIQUE153, 4, 4),
        intArrayOf(WEDGE_HORIZONTAL, 4, 2), intArrayOf(WEDGE_HORIZONTAL, 4, 4),
        intArrayOf(WEDGE_HORIZONTAL, 4, 6), intArrayOf(WEDGE_VERTICAL, 4, 4),
        intArrayOf(WEDGE_OBLIQUE27, 4, 2), intArrayOf(WEDGE_OBLIQUE27, 4, 6),
        intArrayOf(WEDGE_OBLIQUE153, 4, 2), intArrayOf(WEDGE_OBLIQUE153, 4, 6),
        intArrayOf(WEDGE_OBLIQUE63, 2, 4), intArrayOf(WEDGE_OBLIQUE63, 6, 4),
        intArrayOf(WEDGE_OBLIQUE117, 2, 4), intArrayOf(WEDGE_OBLIQUE117, 6, 4)
    )

    /** Wedge codebook used when the block is wider than it is tall. */
    private val WEDGE_CODEBOOK_HLTW: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(WEDGE_OBLIQUE27, 4, 4), intArrayOf(WEDGE_OBLIQUE63, 4, 4),
        intArrayOf(WEDGE_OBLIQUE117, 4, 4), intArrayOf(WEDGE_OBLIQUE153, 4, 4),
        intArrayOf(WEDGE_VERTICAL, 2, 4), intArrayOf(WEDGE_VERTICAL, 4, 4),
        intArrayOf(WEDGE_VERTICAL, 6, 4), intArrayOf(WEDGE_HORIZONTAL, 4, 4),
        intArrayOf(WEDGE_OBLIQUE27, 4, 2), intArrayOf(WEDGE_OBLIQUE27, 4, 6),
        intArrayOf(WEDGE_OBLIQUE153, 4, 2), intArrayOf(WEDGE_OBLIQUE153, 4, 6),
        intArrayOf(WEDGE_OBLIQUE63, 2, 4), intArrayOf(WEDGE_OBLIQUE63, 6, 4),
        intArrayOf(WEDGE_OBLIQUE117, 2, 4), intArrayOf(WEDGE_OBLIQUE117, 6, 4)
    )

    /** Wedge codebook used when the block is square. */
    private val WEDGE_CODEBOOK_HEQW: Array<IntArray> = arrayOf<IntArray>(
        intArrayOf(WEDGE_OBLIQUE27, 4, 4), intArrayOf(WEDGE_OBLIQUE63, 4, 4),
        intArrayOf(WEDGE_OBLIQUE117, 4, 4), intArrayOf(WEDGE_OBLIQUE153, 4, 4),
        intArrayOf(WEDGE_HORIZONTAL, 4, 2), intArrayOf(WEDGE_HORIZONTAL, 4, 6),
        intArrayOf(WEDGE_VERTICAL, 2, 4), intArrayOf(WEDGE_VERTICAL, 6, 4),
        intArrayOf(WEDGE_OBLIQUE27, 4, 2), intArrayOf(WEDGE_OBLIQUE27, 4, 6),
        intArrayOf(WEDGE_OBLIQUE153, 4, 2), intArrayOf(WEDGE_OBLIQUE153, 4, 6),
        intArrayOf(WEDGE_OBLIQUE63, 2, 4), intArrayOf(WEDGE_OBLIQUE63, 6, 4),
        intArrayOf(WEDGE_OBLIQUE117, 2, 4), intArrayOf(WEDGE_OBLIQUE117, 6, 4)
    )

    /** The generated luma-domain wedge master masks indexed by direction. */
    private val WEDGE_MASTER: Array<IntArray> = createWedgeMaster()

    /** Returns whether the supplied block size can signal inter-intra prediction.
     * 
     * @param size the block size to test
     * @return whether the supplied block size can signal inter-intra prediction
     */
    fun supportsInterIntra(size: BlockSize): Boolean {
        return when (requireNotNullCompat(size, "size")) {
            BlockSize.SIZE_32X32, BlockSize.SIZE_32X16, BlockSize.SIZE_16X32, BlockSize.SIZE_16X16, BlockSize.SIZE_16X8, BlockSize.SIZE_8X16, BlockSize.SIZE_8X8 -> true
            else -> false
        }
    }

    /** Returns whether the supplied block size can signal compound wedge prediction.
     * 
     * @param size the block size to test
     * @return whether the supplied block size can signal compound wedge prediction
     */
    fun supportsCompoundWedge(size: BlockSize): Boolean {
        return when (requireNotNullCompat(size, "size")) {
            BlockSize.SIZE_32X32, BlockSize.SIZE_32X16, BlockSize.SIZE_32X8, BlockSize.SIZE_16X32, BlockSize.SIZE_16X16, BlockSize.SIZE_16X8, BlockSize.SIZE_8X32, BlockSize.SIZE_8X16, BlockSize.SIZE_8X8 -> true
            else -> false
        }
    }

    /** Returns one inter-intra blend mask value in `[0, 64]`.
     * 
     * @param mode the decoded inter-intra prediction mode
     * @param wedge whether the mask is a wedge mask instead of a smooth blend mask
     * @param wedgeIndex the decoded wedge index, or `-1` for non-wedge blending
     * @param size the luma block size that owns the prediction
     * @param x the plane-local sample X coordinate inside the block
     * @param y the plane-local sample Y coordinate inside the block
     * @param subsamplingX the horizontal chroma subsampling shift for the current plane
     * @param subsamplingY the vertical chroma subsampling shift for the current plane
     * @return one inter-intra blend mask value in `[0, 64]`
     */
    fun maskValue(
        mode: InterIntraPredictionMode,
        wedge: Boolean,
        wedgeIndex: Int,
        size: BlockSize,
        x: Int,
        y: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Int {
        val nonNullMode = requireNotNullCompat(mode, "mode")
        val nonNullSize = requireNotNullCompat(size, "size")
        require(supportsInterIntra(nonNullSize)) { "Inter-intra mask is not defined for block size: $nonNullSize" }
        require(!(subsamplingX < 0 || subsamplingX > 1 || subsamplingY < 0 || subsamplingY > 1)) { "Subsampling shifts must be in [0, 1]" }
        if (!wedge) {
            return blendMaskValue(nonNullMode, nonNullSize, x, y, subsamplingX, subsamplingY)
        }
        require(!(wedgeIndex < 0 || wedgeIndex >= WEDGE_INDEX_COUNT)) { "wedgeIndex out of range: $wedgeIndex" }
        return subsampledWedgeMaskValue(nonNullSize, wedgeIndex, x, y, subsamplingX, subsamplingY)
    }

    /** Returns one compound wedge mask value as the effective secondary-source blend weight.
     * 
     * @param size the luma block size that owns the prediction
     * @param wedgeIndex the decoded wedge index
     * @param maskSign whether the compound mask uses inverted source order
     * @param x the plane-local sample X coordinate inside the block
     * @param y the plane-local sample Y coordinate inside the block
     * @param subsamplingX the horizontal chroma subsampling shift for the current plane
     * @param subsamplingY the vertical chroma subsampling shift for the current plane
     * @return one compound wedge mask value as the effective secondary-source blend weight
     */
    fun compoundWedgeMaskValue(
        size: BlockSize,
        wedgeIndex: Int,
        maskSign: Boolean,
        x: Int,
        y: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Int {
        val nonNullSize = requireNotNullCompat(size, "size")
        require(supportsCompoundWedge(nonNullSize)) { "Compound wedge mask is not defined for block size: $nonNullSize" }
        require(!(wedgeIndex < 0 || wedgeIndex >= WEDGE_INDEX_COUNT)) { "wedgeIndex out of range: $wedgeIndex" }
        require(!(subsamplingX < 0 || subsamplingX > 1 || subsamplingY < 0 || subsamplingY > 1)) { "Subsampling shifts must be in [0, 1]" }

        if (subsamplingX == 0 && subsamplingY == 0) {
            val mask = lumaWedgeMaskValue(nonNullSize, wedgeIndex, x, y)
            return if (maskSign) mask else 64 - mask
        }
        val mask = subsampledWedgeMaskValue(
            nonNullSize,
            wedgeIndex,
            x,
            y,
            subsamplingX,
            subsamplingY,
            if (maskSign) 1 else 0
        )
        return if (maskSign) mask else 64 - mask
    }

    /** Returns one smooth-blend inter-intra mask value.
     * 
     * @param mode the decoded inter-intra prediction mode
     * @param size the luma block size that owns the prediction
     * @param x the plane-local sample X coordinate inside the block
     * @param y the plane-local sample Y coordinate inside the block
     * @param subsamplingX the horizontal chroma subsampling shift for the current plane
     * @param subsamplingY the vertical chroma subsampling shift for the current plane
     * @return one smooth-blend inter-intra mask value
     */
    private fun blendMaskValue(
        mode: InterIntraPredictionMode,
        size: BlockSize,
        x: Int,
        y: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Int {
        if (mode == InterIntraPredictionMode.DC) {
            return 32
        }
        val planeWidth = size.widthPixels() shr subsamplingX
        val planeHeight = size.heightPixels() shr subsamplingY
        val step = 32 / max(planeWidth, planeHeight)
        return when (mode) {
            InterIntraPredictionMode.VERTICAL -> INTER_INTRA_WEIGHTS_1D[min(
                y * step,
                INTER_INTRA_WEIGHTS_1D.size - 1
            )]

            InterIntraPredictionMode.HORIZONTAL -> INTER_INTRA_WEIGHTS_1D[min(
                x * step,
                INTER_INTRA_WEIGHTS_1D.size - 1
            )]

            InterIntraPredictionMode.SMOOTH -> INTER_INTRA_WEIGHTS_1D[min(
                min(x, y) * step,
                INTER_INTRA_WEIGHTS_1D.size - 1
            )]

            InterIntraPredictionMode.DC -> 32
        }
    }

    /** Returns one wedge mask value after chroma subsampling with AV1's rounding sign applied.
     * 
     * @param size the luma block size that owns the prediction
     * @param wedgeIndex the decoded wedge index
     * @param x the plane-local sample X coordinate inside the block
     * @param y the plane-local sample Y coordinate inside the block
     * @param subsamplingX the horizontal chroma subsampling shift for the current plane
     * @param subsamplingY the vertical chroma subsampling shift for the current plane
     * @param roundingSign the AV1 chroma wedge rounding sign in `[0, 1]`
     * @return one wedge mask value after chroma subsampling when needed
     */
    /** Returns one wedge mask value after chroma subsampling when needed.
     * 
     * @param size the luma block size that owns the prediction
     * @param wedgeIndex the decoded wedge index
     * @param x the plane-local sample X coordinate inside the block
     * @param y the plane-local sample Y coordinate inside the block
     * @param subsamplingX the horizontal chroma subsampling shift for the current plane
     * @param subsamplingY the vertical chroma subsampling shift for the current plane
     * @return one wedge mask value after chroma subsampling when needed
     */
    private fun subsampledWedgeMaskValue(
        size: BlockSize,
        wedgeIndex: Int,
        x: Int,
        y: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        roundingSign: Int = 0
    ): Int {
        if (subsamplingX == 0 && subsamplingY == 0) {
            return lumaWedgeMaskValue(size, wedgeIndex, x, y)
        }

        val lumaX = x shl subsamplingX
        val lumaY = y shl subsamplingY
        val horizontalSpan = 1 shl subsamplingX
        val verticalSpan = 1 shl subsamplingY
        var sum = 0
        for (yy in 0..<verticalSpan) {
            for (xx in 0..<horizontalSpan) {
                sum += lumaWedgeMaskValue(size, wedgeIndex, lumaX + xx, lumaY + yy)
            }
            if (subsamplingX != 0) {
                sum++
            }
        }
        return (sum - roundingSign) shr (subsamplingX + subsamplingY)
    }

    /** Returns one luma-domain wedge mask value.
     * 
     * @param size the luma block size that owns the prediction
     * @param wedgeIndex the decoded wedge index
     * @param x the luma sample X coordinate inside the block
     * @param y the luma sample Y coordinate inside the block
     * @return one luma-domain wedge mask value
     */
    private fun lumaWedgeMaskValue(size: BlockSize, wedgeIndex: Int, x: Int, y: Int): Int {
        val width = size.widthPixels()
        val height = size.heightPixels()
        val codebook = wedgeCodebook(width, height)
        val code = codebook[wedgeIndex]
        val sourceX = 32 - ((width * code[1]) shr 3) + x
        val sourceY = 32 - ((height * code[2]) shr 3) + y
        val value = WEDGE_MASTER[code[0]][sourceY * WEDGE_MASTER_SIZE + sourceX]
        return if (((wedgeSigns(size) ushr wedgeIndex) and 1) != 0) 64 - value else value
    }

    /** Returns the wedge codebook for one luma block geometry.
     * 
     * @param width the luma block width in samples
     * @param height the luma block height in samples
     * @return the wedge codebook for one luma block geometry
     */
    private fun wedgeCodebook(width: Int, height: Int): Array<IntArray> {
        if (height > width) {
            return WEDGE_CODEBOOK_HGTW
        }
        if (height < width) {
            return WEDGE_CODEBOOK_HLTW
        }
        return WEDGE_CODEBOOK_HEQW
    }

    /** Returns the per-index sign bits used by AV1's canonical wedge masks.
     * 
     * @param size the luma block size that owns the prediction
     * @return the per-index sign bits used by AV1's canonical wedge masks
     */
    private fun wedgeSigns(size: BlockSize): Int {
        return when (size) {
            BlockSize.SIZE_32X32, BlockSize.SIZE_16X16, BlockSize.SIZE_8X8 -> 0x7bfb
            BlockSize.SIZE_32X16, BlockSize.SIZE_16X32, BlockSize.SIZE_16X8, BlockSize.SIZE_8X16 -> 0x7beb
            BlockSize.SIZE_32X8 -> 0x6beb
            BlockSize.SIZE_8X32 -> 0x7aeb
            else -> throw IllegalArgumentException("Wedge mask is not defined for block size: $size")
        }
    }

    /** Creates the six AV1 wedge master masks.
     * 
     * @return the six AV1 wedge master masks
     */
    private fun createWedgeMaster(): Array<IntArray> {
        val master: Array<IntArray> =
            Array<IntArray>(6) { IntArray(WEDGE_MASTER_SIZE * WEDGE_MASTER_SIZE) }
        for (y in 0..<WEDGE_MASTER_SIZE) {
            insertBorder(
                master[WEDGE_VERTICAL],
                y * WEDGE_MASTER_SIZE,
                WEDGE_MASTER_BORDER_VERTICAL,
                32
            )
        }
        var y = 0
        var ctr = 48
        while (y < WEDGE_MASTER_SIZE) {
            insertBorder(
                master[WEDGE_OBLIQUE63],
                y * WEDGE_MASTER_SIZE,
                WEDGE_MASTER_BORDER_EVEN,
                ctr
            )
            insertBorder(
                master[WEDGE_OBLIQUE63],
                (y + 1) * WEDGE_MASTER_SIZE,
                WEDGE_MASTER_BORDER_ODD,
                ctr - 1
            )
            y += 2
            ctr--
        }
        transpose(master[WEDGE_OBLIQUE27], master[WEDGE_OBLIQUE63])
        transpose(master[WEDGE_HORIZONTAL], master[WEDGE_VERTICAL])
        hflip(master[WEDGE_OBLIQUE117], master[WEDGE_OBLIQUE63])
        hflip(master[WEDGE_OBLIQUE153], master[WEDGE_OBLIQUE27])
        return master
    }

    /** Inserts one wedge border into a master-mask row.
     * 
     * @param destination the destination master-mask plane
     * @param destinationOffset the row offset inside the destination plane
     * @param source the eight-sample border ramp
     * @param center the center coordinate of the inserted border
     */
    private fun insertBorder(
        destination: IntArray,
        destinationOffset: Int,
        source: IntArray,
        center: Int
    ) {
        if (center > 4) {
            fillCompat(destination, destinationOffset, destinationOffset + center - 4, 0)
        }
        val copyOffset = max(center, 4) - 4
        val sourceOffset = max(4 - center, 0)
        val length = min(64 - center, 8)
        arraycopy(source, sourceOffset, destination, destinationOffset + copyOffset, length)
        if (center < 60) {
            fillCompat(destination, destinationOffset + center + 4, destinationOffset + 64, 64)
        }
    }

    /** Transposes one 64x64 wedge master mask.
     * 
     * @param destination the destination master-mask plane
     * @param source the source master-mask plane
     */
    private fun transpose(destination: IntArray, source: IntArray) {
        for (y in 0..<WEDGE_MASTER_SIZE) {
            for (x in 0..<WEDGE_MASTER_SIZE) {
                destination[x * WEDGE_MASTER_SIZE + y] = source[y * WEDGE_MASTER_SIZE + x]
            }
        }
    }

    /** Horizontally flips one 64x64 wedge master mask.
     * 
     * @param destination the destination master-mask plane
     * @param source the source master-mask plane
     */
    private fun hflip(destination: IntArray, source: IntArray) {
        for (y in 0..<WEDGE_MASTER_SIZE) {
            val rowOffset = y * WEDGE_MASTER_SIZE
            for (x in 0..<WEDGE_MASTER_SIZE) {
                destination[rowOffset + WEDGE_MASTER_SIZE - 1 - x] = source[rowOffset + x]
            }
        }
    }
}
