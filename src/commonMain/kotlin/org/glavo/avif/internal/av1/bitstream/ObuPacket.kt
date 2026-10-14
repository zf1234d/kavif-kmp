// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** A single OBU packet read from a forward-only AV1 OBU stream.
 * 
 * @param header the parsed OBU header
 * @param payload the raw OBU payload bytes
 * @param streamOffset the zero-based byte offset of the OBU header
 * @param obuIndex the zero-based OBU index within the stream
 */
data class ObuPacket(
    val header: ObuHeader,
    val payload: ByteArray,
    val streamOffset: Long,
    val obuIndex: Int
) {
    init {
        require(streamOffset >= 0) { "streamOffset < 0: $streamOffset" }
        require(obuIndex >= 0) { "obuIndex < 0: $obuIndex" }
        requireNotNullCompat(header, "header")
        requireNotNullCompat(
            payload, "payload"
        )
    }
}
