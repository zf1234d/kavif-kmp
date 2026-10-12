// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Packed non-premultiplied ARGB formats exposed by decoded AVIF frames. */
enum class AvifPixelFormat {
    /** Stores each pixel in an `IntBuf` element as `0xAARRGGBB`. */
    ARGB_8888,

    /** Stores each pixel in a `LongBuf` element as `0xAAAA_RRRR_GGGG_BBBB`. */
    ARGB_16161616
}
