// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.model

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Incrementally assembled AV1 frame state before pixel decoding begins. */
class FrameAssembly(
    sequenceHeader: SequenceHeader,
    frameHeader: FrameHeader,
    referenceFrameHeaders: Array<FrameHeader?>?,
    streamOffset: Long,
    obuIndex: Int
) {
    /** The active sequence header for this frame. */
    private val sequenceHeader: SequenceHeader

    /** The parsed frame header for this frame. */
    private val frameHeader: FrameHeader

    /** The refreshed reference-frame headers indexed by runtime reference slot. */
    private val referenceFrameHeaders: Array<FrameHeader?>?

    /** The byte offset of the first OBU that belongs to this frame. */
    private val streamOffset: Long

    /** The zero-based index of the first OBU that belongs to this frame. */
    private val obuIndex: Int

    /** The total number of tiles declared by the frame header. */
    private val totalTiles: Int

    /** The tile bitstreams collected so far, indexed by frame tile index. */
    private val tileBitstreams: Array<TileBitstream?>?

    /** The number of tile groups collected so far. */
    private var tileGroupCount = 0

    /** The number of tile bitstreams collected so far. */
    private var collectedTileCount = 0

    /** The next tile index that must be covered by the next tile group. */
    private var nextTileIndex = 0

    /** Creates a new in-progress frame assembly.
     * 
     * @param sequenceHeader the active sequence header
     * @param frameHeader the parsed frame header
     * @param streamOffset the byte offset of the first frame OBU
     * @param obuIndex the zero-based index of the first frame OBU
     */
    constructor(
        sequenceHeader: SequenceHeader,
        frameHeader: FrameHeader,
        streamOffset: Long,
        obuIndex: Int
    ) : this(sequenceHeader, frameHeader, arrayOfNullsCompat<FrameHeader?>(8), streamOffset, obuIndex)

    init {
        this.sequenceHeader =
            requireNotNullCompat(sequenceHeader, "sequenceHeader")
        this.frameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        val nonNullReferenceFrameHeaders =
            requireNotNullCompat(
                referenceFrameHeaders,
                "referenceFrameHeaders"
            )
        require(nonNullReferenceFrameHeaders.size == 8) { "referenceFrameHeaders.length != 8: " + nonNullReferenceFrameHeaders.size }
        this.referenceFrameHeaders =
            nonNullReferenceFrameHeaders.copyOf()
        require(streamOffset >= 0) { "streamOffset < 0: $streamOffset" }
        require(obuIndex >= 0) { "obuIndex < 0: $obuIndex" }

        val tileCount = frameHeader.tiling().columns() * frameHeader.tiling().rows()
        require(tileCount > 0) { "Frame header does not describe any tiles" }

        this.streamOffset = streamOffset
        this.obuIndex = obuIndex
        this.totalTiles = tileCount
        this.tileBitstreams = arrayOfNullsCompat<TileBitstream?>(tileCount)
    }

    /** Returns the active sequence header for this frame.
     * 
     * @return the active sequence header for this frame
     */
    fun sequenceHeader(): SequenceHeader {
        return sequenceHeader
    }

    /** Returns the parsed frame header for this frame.
     * 
     * @return the parsed frame header for this frame
     */
    fun frameHeader(): FrameHeader {
        return frameHeader
    }

    /** Returns the refreshed reference-frame header for one internal LAST..ALTREF reference index.
     * 
     * @param referenceFrame the internal LAST..ALTREF reference index
     * @return the refreshed reference-frame header for the supplied reference, or `null`
     */
    fun referenceFrameHeader(referenceFrame: Int): FrameHeader? {
        val referenceSlot = frameHeader.referenceFrameIndex(referenceFrame)
        if (referenceSlot < 0 || referenceSlot >= referenceFrameHeaders!!.size) {
            return null
        }
        return referenceFrameHeaders[referenceSlot]
    }

    /** Returns the byte offset of the first OBU that belongs to this frame.
     * 
     * @return the byte offset of the first frame OBU
     */
    fun streamOffset(): Long {
        return streamOffset
    }

    /** Returns the zero-based index of the first OBU that belongs to this frame.
     * 
     * @return the zero-based index of the first frame OBU
     */
    fun obuIndex(): Int {
        return obuIndex
    }

    /** Returns the total number of tiles declared by the frame header.
     * 
     * @return the total number of tiles declared by the frame header
     */
    fun totalTiles(): Int {
        return totalTiles
    }

    /** Returns the next tile index that must be covered by the next tile group.
     * 
     * @return the next tile index that must be covered
     */
    fun nextTileIndex(): Int {
        return nextTileIndex
    }

    val isComplete: Boolean
        /** Returns whether the tile groups collected so far cover the whole frame.
         * 
         * @return whether the tile groups collected so far cover the whole frame
         */
        get() = nextTileIndex >= totalTiles

    /** Returns the number of tile groups collected so far.
     * 
     * @return the number of tile groups collected so far
     */
    fun tileGroupCount(): Int {
        return tileGroupCount
    }

    /** Returns the number of tile bitstreams collected so far.
     * 
     * @return the number of collected tile bitstreams
     */
    fun collectedTileCount(): Int {
        return collectedTileCount
    }

    /** Returns a collected tile bitstream by tile index.
     * 
     * @param tileIndex the zero-based tile index within the frame
     * @return the collected tile bitstream
     * @throws IllegalArgumentException if the index is outside the frame or its tile bitstream
     * has not been collected
     */
    fun tileBitstream(tileIndex: Int): TileBitstream {
        require(!(tileIndex < 0 || tileIndex >= totalTiles)) { "Tile index out of frame range: $tileIndex" }
        val tile: TileBitstream = tileBitstreams!![tileIndex]!!
        requireNotNull(tile) { "Tile bitstream has not been collected: $tileIndex" }
        return tile
    }

    /** Adds one arbitrary tile bitstream for partial camera-frame decoding.
     * 
     * This operation may be repeated for distinct tile indices, but it must not be mixed with
     * [addTileGroup].
     * 
     * @param tile the tile bitstream to add
     * @throws IllegalArgumentException if the tile index is outside the frame or was already added
     * @throws IllegalStateException if a sequential tile group was already added
     */
    fun addTileForPartialDecode(tile: TileBitstream) {
        check(tileGroupCount == 0) { "Partial tiles cannot be added after tile groups" }
        val checkedTile = requireNotNullCompat(tile, "tile")
        val tileIndex = checkedTile.tileIndex()
        require(!(tileIndex < 0 || tileIndex >= totalTiles)) { "Tile index out of frame range: $tileIndex" }
        require(tileBitstreams!![tileIndex] == null) { "Tile bitstream has already been collected: $tileIndex" }
        tileBitstreams[tileIndex] = checkedTile
        collectedTileCount++
    }

    /** Appends one tile group's bitstreams and advances the expected tile cursor.
     * 
     * @param header the parsed tile-group header
     * @param tiles the parsed per-tile bitstream views
     * @throws IllegalArgumentException if the group does not continue the frame's sequential tile
     * coverage or its tile entries do not match the header
     * @throws IllegalStateException if an arbitrary tile was already added for partial decoding
     */
    fun addTileGroup(header: TileGroupHeader, tiles: Array<TileBitstream>) {
        check(collectedTileCount == nextTileIndex) { "Tile groups cannot be added after partial tiles" }
        val checkedHeader = requireNotNullCompat(header, "header")
        require(checkedHeader.totalTileCount == totalTiles) { "Tile-group header belongs to a different frame layout" }
        require(checkedHeader.startTileIndex == nextTileIndex) { "Tile groups must be added in frame order" }
        val checkedTiles = requireNotNullCompat(tiles, "tiles")
        require(checkedTiles.size == checkedHeader.tileCount()) { "Tile entry count does not match the tile-group header" }
        for (i in checkedTiles.indices) {
            val expectedTileIndex = checkedHeader.startTileIndex + i
            val tile = requireNotNullCompat(checkedTiles[i], "tiles[$i]")
            require(tile.tileIndex() == expectedTileIndex) { "Tile entry index mismatch: expected $expectedTileIndex but was " + tile.tileIndex() }
            tileBitstreams!![expectedTileIndex] = tile
        }
        tileGroupCount++
        collectedTileCount += checkedTiles.size
        nextTileIndex = checkedHeader.endTileIndex + 1
    }
}
