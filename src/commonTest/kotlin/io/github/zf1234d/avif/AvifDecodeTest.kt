// SPDX-License-Identifier: Apache-2.0
package io.github.zf1234d.avif

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.glavo.avif.AvifImage
import org.glavo.avif.AvifPixelFormat

@OptIn(ExperimentalEncodingApi::class)
class AvifDecodeTest {
    @Test
    fun decodesWhite1x1() {
        val image = AvifImage.read(Base64.decode(WHITE_1X1))
        val frame = image.firstFrame()
        assertEquals(1, frame.width())
        assertEquals(1, frame.height())
        assertEquals(AvifPixelFormat.ARGB_8888, frame.pixelFormat())
        assertContentEquals(intArrayOf(0xFFFDFDFD.toInt()), frame.intPixels())
    }

    @Test
    fun decodesExtendedPixi() {
        val image = AvifImage.read(Base64.decode(EXTENDED_PIXI))
        val frame = image.firstFrame()
        assertEquals(4, frame.width())
        assertEquals(4, frame.height())
        assertContentEquals(
            intArrayOf(
                4278223872.toInt(), 4278235392.toInt(), 4283323725.toInt(), 4286218617.toInt(),
                4278235392.toInt(), 4278246656.toInt(), 4286284410.toInt(), 4289179302.toInt(),
                4283389518.toInt(), 4286284410.toInt(), 4294911743.toInt(), 4294923007.toInt(),
                4286218617.toInt(), 4289179302.toInt(), 4294923007.toInt(), 4294934015.toInt(),
            ),
            frame.intPixels(),
        )
    }

    @Test
    fun readFramesPreservesEveryFrame() {
        val image = AvifImage.read(Base64.decode(EXTENDED_PIXI))
        assertEquals(1, image.frames().size)
        assertEquals(4, image.info().width())
        assertEquals(4, image.info().height())
    }

    private companion object {
        private const val WHITE_1X1 =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUEAAADybWV0YQAAAAAAAAAoaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAGxpYmF2aWYAAAAADnBpdG0AAAAAAAEAAAAeaWxvYwAAAABEAAABAAEAAAABAAABGgAAABcAAAAoaWluZgAAAAAAAQAAABppbmZlAgAAAAABAABhdjAxQ29sb3IAAAAAamlwcnAAAABLaXBjbwAAABRpc3BlAAAAAAAAAAEAAAABAAAAEHBpeGkAAAAAAwgICAAAAAxhdjFDgSAAAAAAABNjb2xybmNseAABAA0ABoAAAAAXaXBtYQAAAAAAAAABAAEEAQKDBAAAAB9tZGF0EgAKBzgABhAQ0GkyCh/wP///xAAAr3A="
        private const val EXTENDED_PIXI =
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAADxbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAAB5pbG9jAAAAAEQAAAEAAQAAAAEAAAEZAAAAMQAAAChpaW5mAAAAAAABAAAAGmluZmUCAAAAAAEAAGF2MDFDb2xvcgAAAABwaXBycAAAAFFpcGNvAAAAFGlzcGUAAAAAAAAABAAAAAQAAAAWcGl4aQAAAAEDCAgIAgACIAIgAAAADGF2MUOBAA0AAAAAE2NvbHJuY2x4AAIAAgACgAAAABdpcG1hAAAAAAAAAAEAAQQBAoMEAAAAOW1kYXQSAAoFGAR9gUgyJhfACSSSRABOQpsmXwal4c1e451a75FxWtQXOrIX0TGFWyby7pvW"
    }
}