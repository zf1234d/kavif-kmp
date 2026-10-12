// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.internal.av1.model.BlockPosition
import org.glavo.avif.internal.av1.model.BlockSize
import org.glavo.avif.internal.av1.model.MotionVector


/** Validates AV1 same-frame displacement vectors against tile and decode-order constraints. */
internal object IntrabcDisplacementValidator {
    /** The AV1 hardware pipeline delay expressed in 64x64 superblocks. */
    private const val DELAY_SB64 = 4

    /** Requires one decoded displacement vector to be legal for its tile-local block.
     * 
     * @param tileContext        the active tile decode context
     * @param position           the tile-relative block origin in 4x4 units
     * @param size               the decoded block size
     * @param displacementVector the decoded same-frame displacement vector
     * @param chromaReference    whether this block owns chroma prediction samples
     * @throws InvalidDisplacementVectorException if the displacement vector is invalid
     */
    fun requireValid(
        tileContext: TileDecodeContext,
        position: BlockPosition,
        size: BlockSize,
        displacementVector: MotionVector,
        chromaReference: Boolean
    ) {
        val nonNullTileContext =
            requireNotNullCompat(tileContext, "tileContext")
        val nonNullPosition = requireNotNullCompat(position, "position")
        val nonNullSize = requireNotNullCompat(size, "size")
        val nonNullDisplacementVector =
            requireNotNullCompat(displacementVector, "displacementVector")
        val colorConfig = nonNullTileContext.sequenceHeader().colorConfig()
        val tileStartX4 = nonNullTileContext.startX() shr 2
        val tileStartY4 = nonNullTileContext.startY() shr 2
        if (!isValid(
                nonNullDisplacementVector,
                tileStartX4 + nonNullPosition.x4,
                tileStartY4 + nonNullPosition.y4,
                nonNullSize.widthPixels(),
                nonNullSize.heightPixels(),
                tileStartX4,
                tileStartY4,
                tileStartX4 + nonNullTileContext.codedWidth4(),
                tileStartY4 + nonNullTileContext.codedHeight4(),
                nonNullTileContext.superblockSize(),
                chromaReference && colorConfig.chromaFormat != Av1ChromaFormat.MONOCHROME,
                colorConfig.chromaSubsamplingX,
                colorConfig.chromaSubsamplingY
            )
        ) {
            throw InvalidDisplacementVectorException(
                ("Invalid intrabc displacement vector at "
                        + nonNullPosition.x4 + "," + nonNullPosition.y4
                        + ": row=" + nonNullDisplacementVector.rowEighthPel
                        + ", column=" + nonNullDisplacementVector.columnEighthPel)
            )
        }
    }

    /** Returns whether one frame-relative displacement vector satisfies the normative AV1 rules.
     * 
     * Tile boundaries are expressed in 4x4 coding units and their end coordinates are exclusive.
     * 
     * @param displacementVector the displacement vector in luma eighth-pel units
     * @param blockX4            the frame-relative block X origin in 4x4 units
     * @param blockY4            the frame-relative block Y origin in 4x4 units
     * @param blockWidth         the luma block width in pixels
     * @param blockHeight        the luma block height in pixels
     * @param tileStartX4        the inclusive frame-relative tile X origin in 4x4 units
     * @param tileStartY4        the inclusive frame-relative tile Y origin in 4x4 units
     * @param tileEndX4          the exclusive frame-relative tile X end in 4x4 units
     * @param tileEndY4          the exclusive frame-relative tile Y end in 4x4 units
     * @param superblockSize     the active superblock size in pixels, either 64 or 128
     * @param chromaReference    whether the block owns chroma prediction samples
     * @param subsamplingX       whether chroma is horizontally subsampled
     * @param subsamplingY       whether chroma is vertically subsampled
     * @return whether the displacement vector is legal
     */
    fun isValid(
        displacementVector: MotionVector,
        blockX4: Int,
        blockY4: Int,
        blockWidth: Int,
        blockHeight: Int,
        tileStartX4: Int,
        tileStartY4: Int,
        tileEndX4: Int,
        tileEndY4: Int,
        superblockSize: Int,
        chromaReference: Boolean,
        subsamplingX: Boolean,
        subsamplingY: Boolean
    ): Boolean {
        val nonNullDisplacementVector =
            requireNotNullCompat(displacementVector, "displacementVector")
        require(!(superblockSize != 64 && superblockSize != 128)) { "superblockSize must be 64 or 128: $superblockSize" }
        require(!(blockWidth <= 0 || blockHeight <= 0)) { "block dimensions must be positive" }
        require(!(tileStartX4 < 0 || tileStartY4 < 0 || tileEndX4 <= tileStartX4 || tileEndY4 <= tileStartY4)) { "invalid tile bounds" }

        val displacementRow = nonNullDisplacementVector.rowEighthPel
        val displacementColumn = nonNullDisplacementVector.columnEighthPel
        if ((displacementRow and 7) != 0 || (displacementColumn and 7) != 0) {
            return false
        }

        val sourceTopEdge = blockY4.toLong() * 4 * 8 + displacementRow
        val sourceLeftEdge = blockX4.toLong() * 4 * 8 + displacementColumn
        val sourceBottomEdge = (blockY4.toLong() * 4 + blockHeight) * 8 + displacementRow
        val sourceRightEdge = (blockX4.toLong() * 4 + blockWidth) * 8 + displacementColumn
        val tileTopEdge = tileStartY4.toLong() * 4 * 8
        val tileLeftEdge = tileStartX4.toLong() * 4 * 8
        val tileBottomEdge = tileEndY4.toLong() * 4 * 8
        val tileRightEdge = tileEndX4.toLong() * 4 * 8
        if (sourceTopEdge < tileTopEdge || sourceLeftEdge < tileLeftEdge || sourceBottomEdge > tileBottomEdge || sourceRightEdge > tileRightEdge) {
            return false
        }

        if (chromaReference) {
            if (blockWidth < 8 && subsamplingX && sourceLeftEdge < tileLeftEdge + 4L * 8) {
                return false
            }
            if (blockHeight < 8 && subsamplingY && sourceTopEdge < tileTopEdge + 4L * 8) {
                return false
            }
        }

        val mibSizeLog2 = if (superblockSize == 128) 5 else 4
        val activeSuperblockRow = (blockY4 shr mibSizeLog2).toLong()
        val activeSb64Column = (blockX4.toLong() * 4) shr 6
        val sourceSuperblockRow = ((sourceBottomEdge shr 3) - 1) / superblockSize
        val sourceSb64Column = ((sourceRightEdge shr 3) - 1) shr 6
        val totalSb64PerRow = ((tileEndX4 - tileStartX4 - 1L) shr 4) + 1
        val activeSb64 = activeSuperblockRow * totalSb64PerRow + activeSb64Column
        val sourceSb64 = sourceSuperblockRow * totalSb64PerRow + sourceSb64Column
        if (sourceSb64 >= activeSb64 - DELAY_SB64) {
            return false
        }

        val gradient = 1 + DELAY_SB64 + (if (superblockSize > 64) 1 else 0)
        val wavefrontOffset = gradient.toLong() * (activeSuperblockRow - sourceSuperblockRow)
        return sourceSuperblockRow <= activeSuperblockRow
                && sourceSb64Column < activeSb64Column - DELAY_SB64 + wavefrontOffset
    }

    /** Signals that a decoded same-frame displacement vector violates the AV1 bitstream rules. */
    internal class InvalidDisplacementVectorException
    /** Creates an invalid-displacement exception with a diagnostic message.
     * 
     * @param message the diagnostic message
     */
        (message: String?) : IllegalStateException(message)
}
