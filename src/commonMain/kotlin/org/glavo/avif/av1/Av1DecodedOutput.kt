// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1DecodedPlanes
import org.glavo.avif.AvifBitDepth
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.output.ArgbOutput
import org.glavo.avif.internal.av1.output.YuvToRgbTransform


/** Immutable decoded AV1 presentation output with YUV planes and frame metadata.
 * 
 * Plane access does not perform color conversion. [toFrame()] converts the same output to packed
 * ARGB on first use and caches the resulting [Av1DecodedFrame]. */
class Av1DecodedOutput internal constructor(
    planes: DecodedSurface,
    colorConfig: Av1ColorConfig,
    frameType: Av1FrameType,
    visible: Boolean,
    presentationIndex: Long,
    temporalId: Int,
    spatialId: Int
) {
    /** The internal postprocessed presentation surface used for color conversion. */
    private val surface: DecodedSurface

    /** The public visible presentation planes. */
    private val planes: Av1DecodedPlanes

    /** The color configuration used to interpret the planes. */
    private val colorConfig: Av1ColorConfig

    /** The frame category of the presented surface. */
    private val frameType: Av1FrameType

    /** Whether the output is visible. */
    private val visible: Boolean

    /** The zero-based presentation index. */
    private val presentationIndex: Long

    /** The temporal-layer identifier of the presentation request. */
    private val temporalId: Int

    /** The spatial-layer identifier of the presentation request. */
    private val spatialId: Int

    /** The cached packed-pixel representation, or `null` before conversion. */
    private var frame: Av1DecodedFrame? = null

    init {
        require(presentationIndex >= 0) { "presentationIndex < 0: $presentationIndex" }
        require(temporalId in 0..7) { "temporalId out of range: $temporalId" }
        require(spatialId in 0..3) { "spatialId out of range: $spatialId" }
        this.surface = requireNotNullCompat(planes, "planes")
        this.planes = planes.toDecodedPlanes()
        this.colorConfig = requireNotNullCompat(colorConfig, "colorConfig")
        this.frameType = requireNotNullCompat(frameType, "frameType")
        this.visible = visible
        this.presentationIndex = presentationIndex
        this.temporalId = temporalId
        this.spatialId = spatialId
    }

    /** Returns the postprocessed YUV presentation planes.
     * 
     * @return the immutable presentation planes
     */
    fun planes(): Av1DecodedPlanes {
        return planes
    }

    /** Returns the color configuration used to interpret the planes.
     * 
     * @return the AV1 color configuration
     */
    fun colorConfig(): Av1ColorConfig {
        return colorConfig
    }

    /** Returns the frame category of the presented surface.
     * 
     * For `show_existing_frame`, this is the category of the referenced surface.
     * 
     * @return the AV1 frame category
     */
    fun frameType(): Av1FrameType {
        return frameType
    }

    /** Returns whether the output is visible.
     * 
     * @return whether the output is visible
     */
    fun visible(): Boolean {
        return visible
    }

    /** Returns the zero-based presentation index.
     * 
     * @return the presentation index
     */
    fun presentationIndex(): Long {
        return presentationIndex
    }

    /** Returns the temporal-layer identifier of the presentation request.
     * 
     * @return the temporal-layer identifier in `[0, 7]`
     */
    fun temporalId(): Int {
        return temporalId
    }

    /** Returns the spatial-layer identifier of the presentation request.
     * 
     * @return the spatial-layer identifier in `[0, 3]`
     */
    fun spatialId(): Int {
        return spatialId
    }

    /** Converts this output to packed non-premultiplied ARGB pixels.
     * 
     * The first call performs color conversion. Later calls return the same immutable frame
     * instance.
     * 
     * @return the packed-pixel frame
     * @throws UnsupportedOperationException if the color configuration cannot be converted
     */
    fun toFrame(): Av1DecodedFrame {
        var result = frame
        if (result == null) {
            val transform: YuvToRgbTransform =
                YuvToRgbTransform.Companion.fromColorConfig(colorConfig)
            result = when (surface.bitDepth()) {
                8 -> Av1DecodedFrame(
                    surface.codedWidth(),
                    surface.codedHeight(),
                    AvifBitDepth.EIGHT_BITS,
                    surface.chromaFormat(),
                    frameType,
                    visible,
                    presentationIndex,
                    temporalId,
                    spatialId,
                    IntBuf.wrap(ArgbOutput.toOpaqueArgbPixels(surface, transform)).asReadOnlyBuffer()
                )

                10, 12 -> Av1DecodedFrame(
                    surface.codedWidth(),
                    surface.codedHeight(),
                    AvifBitDepth.Companion.fromBits(surface.bitDepth()),
                    surface.chromaFormat(),
                    frameType,
                    visible,
                    presentationIndex,
                    temporalId,
                    spatialId,
                    LongBuf.wrap(ArgbOutput.toOpaqueArgbLongPixels(surface, transform))
                        .asReadOnlyBuffer()
                )

                else -> throw IllegalArgumentException(
                    "Unsupported decoded bit depth: " + surface.bitDepth()
                )
            }
            frame = result
        }
        return result
    }
}
