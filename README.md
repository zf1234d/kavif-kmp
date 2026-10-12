# kavif-kmp

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.zf1234d/kavif-kmp.svg)](https://central.sonatype.com/artifact/io.github.zf1234d/kavif-kmp)

English | [简体中文](README_zh-CN.md)

A pure-Kotlin AV1 / AVIF decoder for Kotlin Multiplatform.

## Features

- Still AVIF images and AVIS image sequences (every frame, in presentation order).
- Grid-derived images (`dimg`) with cell composition and edge trimming.
- Auxiliary images: alpha (including the legacy alpha layout without `ispe`) and depth.
- Gain maps with metadata parsing and tone mapping.
- Color: `nclx` CICP conversion (BT.601/709/2020, FCC, SMPTE 240M, YCgCo, identity), ICC profile,
  Exif and XMP metadata passthrough.
- Sample formats: 8 / 10 / 12-bit, monochrome, 4:2:0, 4:2:2 and 4:4:4.
- AV1 tools: loop filter, CDEF, loop restoration, intra/inter prediction, warped motion, OBMC,
  palette, intra block copy, film grain synthesis.
- Item transforms: `irot`, `imir`, `clap`.

## Requirements

| Target | Minimum |
| --- | --- |
| JVM | Java 8 |
| Android | API 21 (Android 5.0) |

## Usage

Add the dependency to your `build.gradle.kts`:

```kotlin
implementation("io.github.zf1234d:kavif-kmp:<version>")
```

### Decode an image

```kotlin
import org.glavo.avif.AvifImage

val image = AvifImage.read(bytes)
val frame = image.firstFrame()

val width = frame.width()
val height = frame.height()

// 8-bit packed, non-premultiplied ARGB in 0xAARRGGBB.
val argb: IntArray = frame.intPixels()

// 16-bit packed, non-premultiplied ARGB in 0xAAAA_RRRR_GGGG_BBBB.
val argb16: LongArray = frame.longPixels()
```

`intPixels()` and `longPixels()` are interchangeable. An 8-bit frame expands each channel to
16-bit on `longPixels()`; a 16-bit frame reduces each channel to 8-bit with rounding on
`intPixels()`.

### Decode an image sequence

```kotlin
val frames = AvifImage.read(bytes).frames() // every frame, in presentation order
```

### Read metadata

```kotlin
val info = image.info()

val alphaPresent = info.alphaPresent()
val colorInfo = info.colorInfo()   // CICP primaries / transfer / matrix, or null
val iccProfile = info.iccProfile()
val exif = info.exif()
val xmp = info.xmp()
```

### Android

Provides `Bitmap` helpers. Decoding is CPU-bound; call it off the main thread.

```kotlin
import io.github.zf1234d.avif.decodeBitmap
import io.github.zf1234d.avif.decodeBitmaps
import io.github.zf1234d.avif.toBitmap

val bitmap: Bitmap = decodeBitmap(bytes)              // first frame as a Bitmap
val bitmaps: List<Bitmap> = decodeBitmaps(bytes)      // every frame as a Bitmap
val frameBitmap: Bitmap = frame.toBitmap()            // any AvifFrame as a Bitmap
```

`Bitmap` is always 8-bit `ARGB_8888`; HDR (10/12-bit) content is rounded down. Use `longPixels()`
if your pipeline needs full precision before tone mapping.

## Building from source

Requirements: JDK 17 or newer, and an Android SDK for the Android target.

```bash
# compile all targets and run all tests
./gradlew build

# JVM test suite only
./gradlew jvmTest

# Android AAR, JVM JAR, metadata
./gradlew assemble

# install to ~/.m2 for local testing
./gradlew publishToMavenLocal
```

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

Some source files are derived from [`javif`](https://github.com/Glavo/javif) and remain under the
Mozilla Public License 2.0, with the original copyright preserved in the file headers. See
[LICENSE-MPL](LICENSE-MPL) for the license text.

## AI-generated content

This project (source code and documentation alike) was produced with AI assistance and reviewed
and iteratively debugged by a human. AI-generated content may contain errors, omissions,
outdated or misleading information; read the code and verify its behavior yourself before use.

## Acknowledgements

[javif](https://github.com/Glavo/javif) by Glavo.
