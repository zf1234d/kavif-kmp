// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Immutable AVIF item property retained without interpreting its type-specific payload. */
class AvifOpaqueItemProperty(type: String, userType: ByteArray?, payload: ByteArray) {
    /** The four-character property box type. */
    private val type: String

    /** The UUID box user type, or `null` for non-UUID properties. */
    private val userType: ByteArray?

    /** The property box payload after any UUID user type. */
    private val payload: ByteArray?

    init {
        this.type = validateType(type)
        if ("uuid" == type) {
            require(!(userType == null || userType.size != UUID_USER_TYPE_LENGTH)) { "uuid item properties require a 16-byte userType" }
        } else require(userType == null) { "Only uuid item properties may carry userType" }

        this.userType = immutableBytes(userType)
        this.payload = requireNotNullCompat(
            immutableBytes(
                requireNotNullCompat(
                    payload,
                    "payload"
                )
            )
        )
    }

    /** Returns the four-character property box type.
     * 
     * @return the four-character property box type
     */
    fun type(): String {
        return type
    }

    /** Returns the UUID box user type.
     * 
     * Non-UUID properties return `null`.
     * 
     * @return a read-only view of the UUID user type, or `null`
     */
    fun userType(): ByteArray? {
        return userType?.copyOf()
    }

    /** Returns the property box payload after any UUID user type.
     * 
     * @return a read-only view of the property payload
     */
    fun payload(): ByteArray {
        return payload!!.copyOf()
    }

    companion object {
        /** The byte length of a UUID box user type. */
        private const val UUID_USER_TYPE_LENGTH = 16

        /** Validates one four-character box type string.
         * 
         * @param type the type string
         * @return the validated type string
         */
        fun validateType(type: String): String {
            requireNotNullCompat(type, "type")
            require(type.length == 4) { "type must contain exactly four characters: $type" }
            for (i in 0..<type.length) {
                require(type.get(i).code <= 0xFF) { "type must be an ISO-8859-1 four-character code: $type" }
            }
            return type
        }

        /** Creates immutable byte-buffer storage for one optional payload.
         * 
         * @param bytes the source bytes, or `null`
         * @return immutable byte-buffer storage, or `null`
         */
        fun immutableBytes(bytes: ByteArray?): ByteArray? {
            if (bytes == null) {
                return null
            }
            return bytes.copyOf()
        }

        /** Returns a read-only view over immutable payload storage.
         * 
         * @param bytes the immutable payload storage, or `null`
         * @return a read-only view, or `null`
         */
        fun byteView(bytes: ByteArray?): ByteArray? {
            return bytes?.copyOf()
        }
    }
}
