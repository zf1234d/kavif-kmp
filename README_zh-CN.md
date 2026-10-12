# kavif-kmp

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.zf1234d/kavif-kmp.svg)](https://central.sonatype.com/artifact/io.github.zf1234d/kavif-kmp)

[English](README.md) | 简体中文

面向 Kotlin Multiplatform 的纯 Kotlin AV1 / AVIF 解码库。

## 功能特性

- 静态 AVIF 图像与 AVIS 图像序列(所有帧,按呈现顺序)。
- 网格图(`dimg`),含单元格合成与边缘裁剪。
- 辅助图像:alpha(包括缺少 `ispe` 的 legacy 布局)与 depth。
- Gain map,含元数据解析与色调映射。
- 色彩:`nclx` CICP 转换(BT.601/709/2020、FCC、SMPTE 240M、YCgCo、identity)、ICC profile、
  Exif 与 XMP 元数据透传。
- 采样格式:8 / 10 / 12 位、monochrome、4:2:0、4:2:2、4:4:4。
- AV1 工具:环路滤波、CDEF、环路恢复、帧内/帧间预测、warped motion、OBMC、调色板、
  帧内块复制、胶片颗粒合成。
- 图像变换:`irot`、`imir`、`clap`。

## 环境要求

| 目标 | 最低要求 |
| --- | --- |
| JVM | Java 8 |
| Android | API 21(Android 5.0) |

## 用法

在 `build.gradle.kts` 中添加依赖:

```kotlin
implementation("io.github.zf1234d:kavif-kmp:<version>")
```

### 解码图像

```kotlin
import org.glavo.avif.AvifImage

val image = AvifImage.read(bytes)
val frame = image.firstFrame()

val width = frame.width()
val height = frame.height()

// 8 位 packed、非预乘 ARGB,格式 0xAARRGGBB。
val argb: IntArray = frame.intPixels()

// 16 位 packed、非预乘 ARGB,格式 0xAAAA_RRRR_GGGG_BBBB。
val argb16: LongArray = frame.longPixels()
```

`intPixels()` 与 `longPixels()` 可以互换。8 位帧在 `longPixels()` 时将每个通道扩展到 16 位;
16 位帧在 `intPixels()` 时将每个通道四舍五入降到 8 位。

### 解码图像序列

```kotlin
val frames = AvifImage.read(bytes).frames() // 所有帧,按呈现顺序
```

### 读取元数据

```kotlin
val info = image.info()

val alphaPresent = info.alphaPresent()
val colorInfo = info.colorInfo()   // CICP primaries / transfer / matrix,可能为 null
val iccProfile = info.iccProfile()
val exif = info.exif()
val xmp = info.xmp()
```

### Android

提供 `Bitmap` 辅助方法(解码是 CPU 密集操作,请在后台调度器上执行):

```kotlin
import io.github.zf1234d.avif.decodeBitmap
import io.github.zf1234d.avif.decodeBitmaps
import io.github.zf1234d.avif.toBitmap

val bitmap: Bitmap = decodeBitmap(bytes)              // 第一帧的 Bitmap
val bitmaps: List<Bitmap> = decodeBitmaps(bytes)      // 所有帧的 Bitmap
val frameBitmap: Bitmap = frame.toBitmap()            // 任意 AvifFrame 的 Bitmap
```

`Bitmap` 始终是 8 位 `ARGB_8888`;HDR(10/12 位)内容会被四舍五入降低精度。若需要在色调映射前
保留完整精度,请使用 `longPixels()`。

## 从源码构建

环境要求:

- JDK 17 或更新版本。
- Android 目标需要 Android SDK;若不在默认路径,请设置 `ANDROID_HOME`。

```bash
# 编译所有目标并运行全部测试
./gradlew build

# 仅 JVM 测试
./gradlew jvmTest

# Android AAR、JVM JAR、metadata
./gradlew assemble

# 安装到 ~/.m2 供本地测试
./gradlew publishToMavenLocal
```

## 许可证

本项目以 Apache License 2.0 授权,全文见 [LICENSE](LICENSE)。

部分源文件派生自 [`javif`](https://github.com/Glavo/javif),仍遵循 Mozilla Public License 2.0,
文件头部保留原始版权声明。许可证全文见 [LICENSE-MPL](LICENSE-MPL)。

## AI 生成声明

本项目(含源代码与文档)由 AI 辅助生成,并经过人工复核与迭代调试。AI 生成内容可能包含错误、遗漏、过时或误导性信息,使用前请自行阅读代码并独立验证其行为。

## 致谢

Glavo 的 [javif](https://github.com/Glavo/javif)。