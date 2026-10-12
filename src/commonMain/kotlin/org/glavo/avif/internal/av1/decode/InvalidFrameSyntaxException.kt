// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.decode

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Signals that entropy-decoded frame syntax violates an AV1 structural constraint.
 * 
 * The public reader translates this internal failure into its contextual checked decode error. */
class InvalidFrameSyntaxException
/** Creates one invalid-frame-syntax exception.
 * 
 * @param message the diagnostic message
 * @param cause   the internal validation failure
 */
internal constructor(message: String?, cause: Throwable) : IllegalStateException(message, cause)
