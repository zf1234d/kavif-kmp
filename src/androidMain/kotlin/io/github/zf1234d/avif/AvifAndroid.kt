// Android Bitmap integration for the pure-Kotlin AVIF decoder.
// SPDX-License-Identifier: Apache-2.0
package io.github.zf1234d.avif

import android.graphics.Bitmap
import org.glavo.avif.AvifFrame
import org.glavo.avif.AvifImage

/** Decodes one decoded [AvifFrame] into a mutable [Bitmap]. */
fun AvifFrame.toBitmap(): Bitmap {
    val argb = intPixels()
    return Bitmap.createBitmap(argb, width(), height(), Bitmap.Config.ARGB_8888)
}

/** Decodes the first frame of this image into a [Bitmap]. */
fun AvifImage.toBitmap(): Bitmap = firstFrame().toBitmap()

/** Reads an AVIF byte array and returns its first frame as a [Bitmap]. */
fun decodeBitmap(source: ByteArray): Bitmap = AvifImage.read(source).toBitmap()

/** Reads an AVIF byte array and returns every frame as a [Bitmap]. */
fun decodeBitmaps(source: ByteArray): List<Bitmap> = AvifImage.read(source).frames().map { it.toBitmap() }