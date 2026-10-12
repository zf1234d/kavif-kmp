// SPDX-License-Identifier: Apache-2.0
package io.github.zf1234d.avif

import kotlin.test.Test
import kotlin.test.assertEquals
import org.glavo.avif.internal.compat.roundCompat

class CompatRoundTest {
    @Test
    fun roundCompatMatchesJavaMathRoundOnTies() {
        assertEquals(1L, roundCompat(0.5))
        assertEquals(2L, roundCompat(1.5))
        assertEquals(3L, roundCompat(2.5))
        assertEquals(0L, roundCompat(-0.5))
        assertEquals(-1L, roundCompat(-1.5))
    }

    @Test
    fun roundCompatFloatMatchesJavaMathRoundOnTies() {
        assertEquals(1, roundCompat(0.5f))
        assertEquals(3, roundCompat(2.5f))
        assertEquals(0, roundCompat(-0.5f))
        assertEquals(-1, roundCompat(-1.5f))
    }

    @Test
    fun roundCompatKeepsNonTieBehavior() {
        assertEquals(2L, roundCompat(2.3))
        assertEquals(3L, roundCompat(2.7))
        assertEquals(2, roundCompat(2.3f))
        assertEquals(-2L, roundCompat(-2.3))
    }
}
