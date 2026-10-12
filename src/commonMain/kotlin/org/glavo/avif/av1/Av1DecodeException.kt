// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.av1

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Checked exception thrown when AV1 input cannot be decoded. */
class Av1DecodeException constructor(
    code: Av1DecodeErrorCode,
    stage: Av1DecodeStage,
    message: String?,
    /** The byte offset of the relevant OBU or payload location when known. */
    private val streamOffset: Long?,
    /** The zero-based OBU index when known. */
    private val obuIndex: Int?,
    /** The zero-based frame index when known. */
    private val frameIndex: Int?,
    cause: Throwable? = null
) : AvifIoException(message, cause) {
    /** The stable error code describing the failure category. */
    private val code: Av1DecodeErrorCode

    /** The high-level decode stage where the failure occurred. */
    private val stage: Av1DecodeStage

    /** Creates an exception with an underlying cause.
     * 
     * @param code the stable error code
     * @param stage the decode stage
     * @param message the human-readable error message
     * @param streamOffset the byte offset when known
     * @param obuIndex the OBU index when known
     * @param frameIndex the frame index when known
     * @param cause the underlying cause, if any
     */
    init {
        this.code = requireNotNullCompat(code, "code")
        this.stage = requireNotNullCompat(stage, "stage")
    }

    /** Returns the stable error code.
     * 
     * @return the stable error code
     */
    fun code(): Av1DecodeErrorCode {
        return code
    }

    /** Returns the high-level decode stage where the failure occurred.
     * 
     * @return the failing decode stage
     */
    fun stage(): Av1DecodeStage {
        return stage
    }

    /** Returns the relevant byte offset when known.
     * 
     * @return the relevant byte offset, or `null`
     */
    fun streamOffset(): Long? {
        return streamOffset
    }

    /** Returns the relevant OBU index when known.
     * 
     * @return the relevant OBU index, or `null`
     */
    fun obuIndex(): Int? {
        return obuIndex
    }

    /** Returns the relevant frame index when known.
     * 
     * @return the relevant frame index, or `null`
     */
    fun frameIndex(): Int? {
        return frameIndex
    }
}
