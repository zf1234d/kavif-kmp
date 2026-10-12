// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Parsed AV1 OBU header metadata.
 * 
 * @param type the parsed OBU type
 * @param extensionFlag whether the OBU carries an extension header
 * @param hasSizeField whether the OBU carries a size field
 * @param temporalId the temporal layer identifier
 * @param spatialId the spatial layer identifier
 */
data class ObuHeader(
    val type: ObuType,
    val extensionFlag: Boolean,
    val hasSizeField: Boolean,
    val temporalId: Int,
    val spatialId: Int
) {
    /** Creates a parsed OBU header. */
    init {
        require(temporalId in 0..7) { "temporalId out of range: $temporalId" }
        require(spatialId in 0..3) { "spatialId out of range: $spatialId" }
        require(!(!extensionFlag && (temporalId != 0 || spatialId != 0))) { "temporalId and spatialId must be zero when extensionFlag is false" }
        requireNotNullCompat(type, "type")
    }
}
