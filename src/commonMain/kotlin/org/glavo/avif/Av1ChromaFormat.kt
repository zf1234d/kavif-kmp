// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** AV1 luma and chroma plane layouts exposed by decoded image and frame metadata. */
enum class Av1ChromaFormat {
    /** Monochrome content with no chroma planes. */
    MONOCHROME,

    /** YUV content with 4:2:0 chroma subsampling. */
    YUV420,

    /** YUV content with 4:2:2 chroma subsampling. */
    YUV422,

    /** YUV content with full-resolution 4:4:4 chroma. */
    YUV444
}
