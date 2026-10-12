// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.recon

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** Signals that reconstructed values violate an AV1 bitstream conformance constraint. */
class InvalidFrameReconstructionException
/** Creates one invalid-frame-reconstruction exception.
 * 
 * @param message the diagnostic message
 */
internal constructor(message: String?) : IllegalStateException(message)
