// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Header for one BMFF box.
 * 
 * @param type the four-character box type
 * @param offset the absolute byte offset of the box header
 * @param payloadOffset the absolute byte offset of the box payload
 * @param payloadSize the byte length of the box payload
 * @param sizeZero whether the serialized box used a size field of zero
 */
data class BoxHeader(
    val type: String,
    val offset: Int,
    val payloadOffset: Int,
    val payloadSize: Int,
    val sizeZero: Boolean
) {
    /** Returns the absolute byte offset immediately after this box.
     * 
     * @return the absolute byte offset immediately after this box
     */
    fun endOffset(): Int {
        return payloadOffset + payloadSize
    }

    /** Creates a BMFF box header. */
    init {
        requireNotNullCompat(type, "type")
    }
}
