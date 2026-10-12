// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.bitstream

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** AV1 OBU types defined by the bitstream specification. */
enum class ObuType
/** Creates an OBU type with a numeric identifier.
 * 
 * @param id the numeric OBU type identifier
 */(
    /** The numeric OBU type identifier. */
    private val id: Int
) {
    /** Sequence header OBU. */
    SEQUENCE_HEADER(1),

    /** Temporal delimiter OBU. */
    TEMPORAL_DELIMITER(2),

    /** Frame header OBU. */
    FRAME_HEADER(3),

    /** Tile group OBU. */
    TILE_GROUP(4),

    /** Metadata OBU. */
    METADATA(5),

    /** Combined frame OBU. */
    FRAME(6),

    /** Redundant frame header OBU. */
    REDUNDANT_FRAME_HEADER(7),

    /** Large Scale Tile list OBU. */
    TILE_LIST(8),

    /** Padding OBU. */
    PADDING(15);

    /** Returns the numeric OBU type identifier.
     * 
     * @return the numeric OBU type identifier
     */
    fun id(): Int {
        return id
    }

    companion object {
        /** Maps a numeric OBU type identifier to an enum constant.
         * 
         * @param id the numeric OBU type identifier
         * @return the matching OBU type, or `null`
         */
        fun fromId(id: Int): ObuType? {
            for (type in entries) {
                if (type.id == id) {
                    return type
                }
            }
            return null
        }
    }
}
