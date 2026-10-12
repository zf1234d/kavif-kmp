// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.AvifBitDepth
import org.glavo.avif.AvifPixelFormat
import org.glavo.avif.internal.PixelBuffers


/** Decoded AV1 frame output exposed by the public API. */
class Av1DecodedFrame private constructor(
    width: Int,
    height: Int,
    bitDepth: AvifBitDepth,
    chromaFormat: Av1ChromaFormat,
    frameType: Av1FrameType,
    visible: Boolean,
    presentationIndex: Long,
    temporalId: Int,
    spatialId: Int,
    intPixels: IntBuf?,
    longPixels: LongBuf?
) {
    /** The output frame width in pixels. */
    private val width: Int

    /** The output frame height in pixels. */
    private val height: Int

    /** The decoded bit depth. */
    private val bitDepth: AvifBitDepth

    /** The chroma layout of the decoded frame. */
    private val chromaFormat: Av1ChromaFormat

    /** The AV1 frame type. */
    private val frameType: Av1FrameType

    /** Whether the frame is visible. */
    private val visible: Boolean

    /** The zero-based presentation index of the frame. */
    private val presentationIndex: Long

    /** The packed ARGB pixel format used by this frame's native storage. */
    private val pixelFormat: AvifPixelFormat

    /** The AV1 temporal-layer identifier carried by the frame OBU. */
    private val temporalId: Int

    /** The AV1 spatial-layer identifier carried by the frame OBU. */
    private val spatialId: Int

    /** Packed non-premultiplied ARGB pixels in `0xAARRGGBB` format, or `null` until converted. */
    private var intPixels: IntBuf?

    /** Packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format, or `null` until converted. */
    private var longPixels: LongBuf?

    /** Creates a decoded frame from a packed `int` ARGB pixel buffer.
     * 
     * The pixel buffer is stored as a read-only slice without copying. Callers must only pass
     * immutable storage or storage they will never mutate after construction.
     * 
     * @param width             the output frame width in pixels
     * @param height            the output frame height in pixels
     * @param bitDepth          the decoded bit depth
     * @param chromaFormat       the chroma layout
     * @param frameType         the AV1 frame type
     * @param visible           whether the frame is visible
     * @param presentationIndex the zero-based presentation index
     * @param pixels            the packed non-premultiplied ARGB pixels
     */
    constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat,
        frameType: Av1FrameType,
        visible: Boolean,
        presentationIndex: Long,
        pixels: IntBuf
    ) : this(
        width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
        0, 0, PixelBuffers.immutableIntPixels(pixels), null
    )

    /** Creates a decoded frame from a packed `int` ARGB pixel buffer with AV1 layer identifiers.
     * 
     * The pixel buffer is stored as a read-only slice without copying. Callers must only pass
     * immutable storage or storage they will never mutate after construction.
     * 
     * @param width             the output frame width in pixels
     * @param height            the output frame height in pixels
     * @param bitDepth          the decoded bit depth
     * @param chromaFormat       the chroma layout
     * @param frameType         the AV1 frame type
     * @param visible           whether the frame is visible
     * @param presentationIndex the zero-based presentation index
     * @param temporalId        the AV1 temporal-layer identifier in `[0, 7]`
     * @param spatialId         the AV1 spatial-layer identifier in `[0, 3]`
     * @param pixels            the packed non-premultiplied ARGB pixels
     */
    constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat,
        frameType: Av1FrameType,
        visible: Boolean,
        presentationIndex: Long,
        temporalId: Int,
        spatialId: Int,
        pixels: IntBuf
    ) : this(
        width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
        temporalId, spatialId, PixelBuffers.immutableIntPixels(pixels), null
    )

    /** Creates a decoded frame from a packed `long` ARGB pixel buffer.
     * 
     * The pixel buffer is stored as a read-only slice without copying. Callers must only pass
     * immutable storage or storage they will never mutate after construction.
     * 
     * @param width             the output frame width in pixels
     * @param height            the output frame height in pixels
     * @param bitDepth          the decoded bit depth
     * @param chromaFormat       the chroma layout
     * @param frameType         the AV1 frame type
     * @param visible           whether the frame is visible
     * @param presentationIndex the zero-based presentation index
     * @param pixels            the packed non-premultiplied ARGB pixels
     */
    constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat,
        frameType: Av1FrameType,
        visible: Boolean,
        presentationIndex: Long,
        pixels: LongBuf
    ) : this(
        width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
        0, 0, null, PixelBuffers.immutableLongPixels(pixels)
    )

    /** Creates a decoded frame from a packed `long` ARGB pixel buffer with AV1 layer identifiers.
     * 
     * The pixel buffer is stored as a read-only slice without copying. Callers must only pass
     * immutable storage or storage they will never mutate after construction.
     * 
     * @param width             the output frame width in pixels
     * @param height            the output frame height in pixels
     * @param bitDepth          the decoded bit depth
     * @param chromaFormat       the chroma layout
     * @param frameType         the AV1 frame type
     * @param visible           whether the frame is visible
     * @param presentationIndex the zero-based presentation index
     * @param temporalId        the AV1 temporal-layer identifier in `[0, 7]`
     * @param spatialId         the AV1 spatial-layer identifier in `[0, 3]`
     * @param pixels            the packed non-premultiplied ARGB pixels
     */
    constructor(
        width: Int,
        height: Int,
        bitDepth: AvifBitDepth,
        chromaFormat: Av1ChromaFormat,
        frameType: Av1FrameType,
        visible: Boolean,
        presentationIndex: Long,
        temporalId: Int,
        spatialId: Int,
        pixels: LongBuf
    ) : this(
        width, height, bitDepth, chromaFormat, frameType, visible, presentationIndex,
        temporalId, spatialId, null, PixelBuffers.immutableLongPixels(pixels)
    )

    init {
        require(!(intPixels == null && longPixels == null)) { "At least one pixel representation is required" }
        val pixelCount: Int = checkedPixelCount(width, height)
        require(!(intPixels != null && intPixels.remaining() != pixelCount)) { "Int pixel count does not match dimensions: " + intPixels!!.remaining() }
        require(!(longPixels != null && longPixels.remaining() != pixelCount)) { "Long pixel count does not match dimensions: " + longPixels!!.remaining() }
        require(presentationIndex >= 0) { "presentationIndex < 0: $presentationIndex" }
        require(temporalId in 0..7) { "temporalId out of range: $temporalId" }
        require(spatialId in 0..3) { "spatialId out of range: $spatialId" }
        this.width = width
        this.height = height
        this.bitDepth = requireNotNullCompat(bitDepth, "bitDepth")
        this.chromaFormat = requireNotNullCompat(chromaFormat, "chromaFormat")
        this.frameType = requireNotNullCompat(frameType, "frameType")
        this.visible = visible
        this.presentationIndex = presentationIndex
        this.pixelFormat =
            if (intPixels != null) AvifPixelFormat.ARGB_8888 else AvifPixelFormat.ARGB_16161616
        this.temporalId = temporalId
        this.spatialId = spatialId
        this.intPixels = intPixels
        this.longPixels = longPixels
    }

    /** Returns the output frame width in pixels.
     * 
     * @return the output frame width
     */
    fun width(): Int {
        return width
    }

    /** Returns the output frame height in pixels.
     * 
     * @return the output frame height
     */
    fun height(): Int {
        return height
    }

    /** Returns the decoded bit depth.
     * 
     * @return the decoded bit depth
     */
    fun bitDepth(): AvifBitDepth {
        return bitDepth
    }

    /** Returns the chroma layout of the decoded frame.
     * 
     * @return the chroma layout
     */
    fun chromaFormat(): Av1ChromaFormat {
        return chromaFormat
    }

    /** Returns the AV1 frame type.
     * 
     * @return the AV1 frame type
     */
    fun frameType(): Av1FrameType {
        return frameType
    }

    /** Returns whether the frame is visible.
     * 
     * @return whether the frame is visible
     */
    fun visible(): Boolean {
        return visible
    }

    /** Returns the zero-based presentation index of the frame.
     * 
     * @return the presentation index
     */
    fun presentationIndex(): Long {
        return presentationIndex
    }

    /** Returns the packed ARGB pixel format used by this frame's native storage.
     * 
     * @return the packed ARGB pixel format
     */
    fun pixelFormat(): AvifPixelFormat {
        return pixelFormat
    }

    /** Returns the AV1 temporal-layer identifier carried by the frame OBU.
     * 
     * @return the temporal-layer identifier in `[0, 7]`
     */
    fun temporalId(): Int {
        return temporalId
    }

    /** Returns the AV1 spatial-layer identifier carried by the frame OBU.
     * 
     * @return the spatial-layer identifier in `[0, 3]`
     */
    fun spatialId(): Int {
        return spatialId
    }

    /** Returns packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
     * 
     * If this frame was constructed from `long` pixels, the returned data is created lazily by
     * reducing each unsigned 16-bit channel to 8 bits with rounding.
     * 
     * @return the packed ARGB pixels
     */
    fun intPixels(): IntArray {
        val buffer = intPixelBuffer()
        val result = IntArray(buffer.remaining())
        buffer.get(result)
        return result
    }

    /** Returns a read-only view of packed non-premultiplied ARGB pixels in `0xAARRGGBB` format.
     * 
     * If this frame was constructed from `long` pixels, the buffer is created lazily and cached.
     * 
     * @return a read-only view of packed non-premultiplied ARGB pixels
     */
    fun intPixelBuffer(): IntBuf {
        var pixels = intPixels
        if (pixels == null) {
            pixels = PixelBuffers.convertLongPixelsToIntPixels(requireLongPixels())
            intPixels = pixels
        }
        return pixels.slice()
    }

    /** Returns packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
     * 
     * If this frame was constructed from `int` pixels, the returned data is created lazily by
     * expanding each unsigned 8-bit channel to 16 bits.
     * 
     * @return the packed ARGB pixels
     */
    fun longPixels(): LongArray {
        val buffer = longPixelBuffer()
        val result = LongArray(buffer.remaining())
        buffer.get(result)
        return result
    }

    /** Returns a read-only view of packed non-premultiplied ARGB pixels in `0xAAAA_RRRR_GGGG_BBBB` format.
     * 
     * If this frame was constructed from `int` pixels, the buffer is created lazily and cached.
     * 
     * @return a read-only view of packed non-premultiplied ARGB pixels
     */
    fun longPixelBuffer(): LongBuf {
        var pixels = longPixels
        if (pixels == null) {
            pixels = PixelBuffers.convertIntPixelsToLongPixels(requireIntPixels())
            longPixels = pixels
        }
        return pixels.slice()
    }

    /** Returns the cached `int` pixels or throws if they are unavailable.
     * 
     * @return the cached `int` pixels
     */
    private fun requireIntPixels(): IntBuf {
        val pixels = intPixels
        checkNotNull(pixels) { "Int pixels are unavailable" }
        return pixels
    }

    /** Returns the cached `long` pixels or throws if they are unavailable.
     * 
     * @return the cached `long` pixels
     */
    private fun requireLongPixels(): LongBuf {
        val pixels = longPixels
        checkNotNull(pixels) { "Long pixels are unavailable" }
        return pixels
    }

    companion object {
        /** Returns the exact pixel count for validated positive dimensions.
         * 
         * @param width the frame width in pixels
         * @param height the frame height in pixels
         * @return the pixel count
         */
        fun checkedPixelCount(width: Int, height: Int): Int {
            require(width > 0) { "width <= 0: $width" }
            require(height > 0) { "height <= 0: $height" }
            val pixelCount = width.toLong() * height
            require(pixelCount <= Int.MAX_VALUE) { "Frame dimensions are too large: ${width}x$height" }
            return pixelCount.toInt()
        }
    }
}
