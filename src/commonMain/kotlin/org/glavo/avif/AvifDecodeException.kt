// Converted from javif (MPL-2.0) https://github.com/Glavo/javif
// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Glavo
package org.glavo.avif

import org.glavo.avif.internal.compat.AvifIoException

class AvifDecodeException : AvifIoException {
    private val code: AvifErrorCode
    private val byteOffset: Long?

    constructor(code: AvifErrorCode, message: String?, byteOffset: Long?) : this(code, message, byteOffset, null)

    constructor(code: AvifErrorCode, message: String?, byteOffset: Long?, cause: Throwable?) : super(message, cause) {
        this.code = code
        this.byteOffset = byteOffset
    }

    fun code(): AvifErrorCode = code

    fun byteOffset(): Long? = byteOffset
}
