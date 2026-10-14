// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.postfilter

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.av1.Av1ColorConfig
import org.glavo.avif.internal.av1.image.DecodedSurface
import org.glavo.avif.internal.av1.image.PaddedPlane
import org.glavo.avif.internal.av1.model.FrameHeader
import org.glavo.avif.internal.av1.model.FrameHeader.FilmGrainParams
import org.glavo.avif.internal.av1.model.FrameHeader.FilmGrainPoint


/** Applies AV1 film-grain synthesis in the dav1d scalar order.
 * 
 * Stored reference surfaces remain post-filter and pre-grain. Synthesis is applied only when one
 * frame is presented, using the normative Gaussian sequence, auto-regressive grain LUTs,
 * per-32x32 block offsets, optional overlap blending, and per-plane scaling functions. */
object FilmGrainSynthesizer {
    /** The luma grain lookup width. */
    private const val GRAIN_WIDTH = 82

    /** The luma grain lookup height. */
    private const val GRAIN_HEIGHT = 73

    /** The subsampled chroma grain lookup width. */
    private const val SUBSAMPLED_GRAIN_WIDTH = 44

    /** The subsampled chroma grain lookup height. */
    private const val SUBSAMPLED_GRAIN_HEIGHT = 38

    /** The AV1 film-grain application block size in luma samples. */
    private const val BLOCK_SIZE = 32

    /** The border skipped before auto-regressive filtering inside grain lookup tables. */
    private const val AUTO_REGRESSIVE_PAD = 3

    /** The Cb plane index used by AV1 film-grain syntax. */
    private const val CHROMA_CB = 0

    /** The Cr plane index used by AV1 film-grain syntax. */
    private const val CHROMA_CR = 1

    /** The XOR seed offset for the Cb grain table. */
    private const val CHROMA_CB_SEED_OFFSET = 0xB524

    /** The XOR seed offset for the Cr grain table. */
    private const val CHROMA_CR_SEED_OFFSET = 0x49D8

    /** The AV1 matrix-coefficients code whose chroma planes use the luma restricted range. */
    private const val MATRIX_IDENTITY = 0

    /** The compressed AV1 Gaussian sequence from the AV1 specification. */
    private const val GAUSSIAN_SEQUENCE_GZIP_BASE64 = """
            H4sIAAAAAAAAChXXkZfqbxcF8H2e53vfFQTBQBAEQRAEQRAEAwNBMDAQBEEQBEEQBANBEAQDQRAEQRAEQRAMBEEQBEEQDAwE/W7POftd9x8468BZe39OEUVX4QI9LLjAlr2QRMFfcImuPiEPtkLPMvrFV6Z9Gx3d2sCGiPgjH8zhJ1TcWu+SYhxzl5CM7f2EQ9eWpZSY1zJvfmVD1+VJZxq4RYZd3vyeHZlypD3cOJSUu/OLfXeQO/JcsC2/mtIHimEZ6q4rO57txAYDzvbABTcbSZx37FjBkke8uHfp48yin9qOD5tyolMp6xkF9l2du6hpn74qL1jalLeQlzyuzD9bz7I1/83UO0qoBKCkcWmhIWe9ydy6vEmJaY7cC7+eJUQY4kVqOrA5xjxIDAVXxi8KcrS2Fa3LIrpo2pAreUiSb1LxNS6Y8B+8Ic2aDKyPoqsj87zYi1R92X/ZyB2kbyub2Mn1bIoyj5KVC4Z4hAmTEmGHHM7s6swnMP6TQsJlfeRfEJcPrOxqPfStbAek+PNc29j1pMWjVdwvUi6FAeasGCQrydD9AxyYjrqMya8unl2XsqXOLeV7aCj0UxJ/knxlkRcLLEgDH4TEbY/IBq7LjvzagC/hpGftW8adbCFjPtgOG0tzzhEznPDqryHjmpLlCgspMscx6hgjJSc0bGtdHK1vM93pyh6ssMq162DOjqsgh6C/yPPEBpb6IXsrWYovGPvsf10u7ByGyALuXR5Y48X6vsGZH+qbVLVsX5rQCfMuCitcwpYVlF0uGsrYZd2YV5ZlqEWZccasxSWBvItcXff8kQkj6UtKMpKXFj/Q5N1noxRnGNi7Vl3XJtjhrlsOwxRV9tzAjaVjFav7uWT17Gc68EvNSppJV2UzrOTAr2gYpvII32EY4u6se92wxilXriUVjYcNI0lby8+eO1nZ1Hp++9xFNY3jbHf8SkcuTMo32nizKZPo6RuLqNoFuZCQYCNUbCYNacmvVrBAUkc2loTWbeGq+oaUm0oNG7nImt2/M7SeGyYxl5gkoj7a4a4TK6Pl74ww54tLyF3L8iKRLXwSbRvw7kr4QcMa6ErKRjJhSscM9iZ3u6DIuq0x073+MCsl9PwglDDDK2J6wskGfmlXjmVi35pn8S8sK2/yZTXe/nuEz+jdZojZiF1k2eLDCpJGGWu+oYMltvy1vdz0N9ysJi0ktMe4u+ojdHCy9+fFDpqIstbBMsyeHfTc2qWkyoffYxq6GmND9n6tBcak7RPuxjd2sJU2Sz7ujsixgDi+EZektWwUir6uJw59jHfZu63+yFYTOLgx+9LDhySl7OIW0MTednjlg/coK7Ho/bmSknSkakW2dOvSfHc9xiWNknV8E4kwl83fm0VoP9tylrXLyYNJrLDUEj8lL3c5ujrvWDAhYM3KckCbDU7Qkp2kkHIlOaOnMR7kDU1Jo2e/8qEHHjBk00qYadcybh0SvmAjd9GePWzoL2GoE/3WkstwhrT8cm8FGfFTY5qUhi7/XQ5KrP1LUNmh6o6M21I22mPPrTiQCA1LuSBvMrEzE8hwyxh3TKLMtiSiGm78eV6YRRNjbNHlQ4s4hDTPbiANOWLJD0uEjVVc9m/Z7eUQlqGIjc44+ztiwr7RkgI6zOPAHd/xhiw+MbG7ffFqLTu7h85wsKt28SYrFuzuPqXiflCxNats2c5a3LuZjmXNOQdhw6X/QIZz2/qT7CXJreui7FNcucg+kbGKf9GxHVmLls/Ls4KFZF1FS2hIHX175US3enFBer4u4+dDbpzaq1Rkrt+WkSBtPniVPFISLEHIhGcr8MiTFDj0r0wGsIZh1LWCa8jQXTkMkKuU+XBVduWuJatYzaf1iB4ekrGjVJC1f1tUw1ivLsGRfOIFW5QYk4bGw45Z2dkuVNBgSqBl24YxHhi6pjtGaVlb2z5ZcWfE3JvGIviUzvhrWdlK3iZS5EDWUmRMmgxWllh0Qlke3HKCWChF4yjprlK1T3RkT/AdHUwtjw7y0reJTPzREq7jVhi4HHIhw+yfPCry4+LubnlNuR8ruLnlWeJK+v4DXftEFaVwsaIr4sCidX0MWdfg2VY+LX0U9GzzqMmWr8tcgx1Dzq4c8MIjoz+/ssVBirJi0E8rWjwc/nUSM/iRBk9hg87/XjHgkCWZosO6ZH3dqq4kaSsi4Qr81o4k+evmdnOBr1K3ucLHJcY5e6xZ1aq42Fh2Vn6uNObGrGFrbUlbljN+/e9oKwRfZcFvmGKwlXTDC7KIbC1pLUpF9ni4oZ1cHT8c6EMPWpVCtOWPxvyb22IoWX3oi7yyij0nGvHAgTuiqlfsGWwhDfu1lq2Q99eQxxeuMmeWZ792G/zqwA1ZYUnOEsde6zhLxLENrGs5B241kql18csug73YG3Ju7F5d00V+JXO5cGPx51Q2tuSXn+KKo0sjaBl9eUWfA/zigQI3DKiFAdM21AW3ePctu1iPezQwx4YlTsJeilFPlrwDuMoglLFGXT7cXmaouRLLsmXFvfGKODqSxzLM7YIDirxKHz9MSM7WoS+QvD40bxttY8BPN7E8p/qrW5d3HzaQks/iHd9oEmjzzSVlwCqOWgglZu3oqpjjwg9MUVVgioZ05BGurs+ZizFtEcZWR9rd8Hhu/A8a8oITc5JETRf6iXr0xRe3fhbdwG0kSE82GNhMD3/mksTdZVjm3nXx5ZdujhwKVnQp6UaD6MPNpetHbmJbO6MkVeb0lYcw1Jml+Gl3X7KWXtzUj+VN17zbZ+hjjbndQ1OqMpIPWUjGjThAilnuQ9B3qaHLDo7WlTgb2NlDr3Zn0c9w1gr77KARdiEuI9toy20xBdD5LxfN5ZtfdpQGDy4tv5pzY8u4Fh9Ski90dazveuPA+jZmMzye8Cs8LLL4M9iOOUnw7I9uYFvNMa05t8VrNNZR6Mo3A7O88NXHZSiFP3U/1waKaNiMDVmh43NWsYz07BzKug5tmdkQOcX/Fr4jbQxtJWlrR1m5MMVPHJhhw8fCjj/PvL3J2N8RR1pP1pSjDGzMGV98W76ZdiOpa81OLoGy5HC1T7uGm3xij4UO7Z/LOuFTdzrSISta5DdfZM6rbGzl6/7mi/bGH8vIySaYubJMMfQL5p4XGzKSofX07OI65Ts+5KE1qeovFjzh59llTTuW+bv1Jy4xx8+fmcRsbmP7sm30Hj4tx4CWlvQqr9bk1A/4EZ0lWN61OLcWSrziYg/Ju7XNmZZE2EuKR9mEEiv4lBsm0n4WtKd3986Jxf7JRRIuj7N8cKpbmeJHfy2Fmt5Z8kf/LTHXxBchcwu8cmR73nWnRy0SvhHlCVvihgdaHISam+vBMr7BHG9S5jmAbfmyVljbFQmWLWsf/qhjK7k7GxzZwpYSkNAm0xKXjS7DUltY8Y6iFLTqllxJUyLEOEXk3tz5X/Lbzc7W4EpTKMjIJ9HDBCtEXPpGCOGh7zbSnOQ4eKaR07T/eub4+LsMK9RtLVts9RZW/H5W8aNT67En+zDGR9THDN+ycY0wQkP7NnDbECTr9pywrQd864+bc+5uyMkWTYuHJiPeLSHj0NSurqzLBOLoR1dW5KZlqWvBdvbigl1xYio6hBU+kLCj1nVkC6mhIWNpWsAOVR416f+prSNZ1LGyN+miI71w4kkettS172Pk2vhySc4kwtzylnN31O3CAKBhG7xpkIy9yvo5ct+WdzHpMi9TbLnRB6qS17yb2cV+rMS4vfuL/vst0+GfaYsooOY7/og75wge7qQT/882G8ywsIe+se2b/NA8e8+j/WrWWtJAUi/PC1MuKSPU9MIkb5ZmHUW34gJjjNFEGie78MUvmcYvk5KWpV9Iyv4PpseChgAQAAA=
            
            """

    /** The decoded AV1 Gaussian sequence. */
    private val GAUSSIAN_SEQUENCE: IntArray = decodeGaussianSequence()

    /** Applies film grain to one post-filter decoded frame.
     * 
     * @param decodedPlanes the post-filter, pre-grain planes to present
     * @param frameHeader the normalized frame header that owns the grain parameters
     * @param colorConfig the active sequence color configuration
     * @return the grain-applied presentation planes
     */
    fun apply(
        decodedPlanes: DecodedSurface,
        frameHeader: FrameHeader,
        colorConfig: Av1ColorConfig
    ): DecodedSurface {
        val checkedDecodedPlanes =
            requireNotNullCompat(decodedPlanes, "decodedPlanes")
        val checkedFrameHeader = requireNotNullCompat(frameHeader, "frameHeader")
        val checkedColorConfig = requireNotNullCompat(colorConfig, "colorConfig")
        val filmGrain = checkedFrameHeader.filmGrain()
        if (!filmGrain.applyGrain()) {
            return checkedDecodedPlanes
        }

        val bitDepth = checkedDecodedPlanes.bitDepth()
        val lumaScaling = generateScaling(bitDepth, filmGrain.yPoints())
        val lumaGrain = generateLumaGrain(bitDepth, filmGrain)
        val lumaPlane = if (filmGrain.yPoints().size != 0)
            applyLumaGrain(
                checkedDecodedPlanes.lumaPlane(),
                bitDepth,
                filmGrain,
                lumaScaling,
                lumaGrain
            )
        else
            checkedDecodedPlanes.lumaPlane()

        var chromaUPlane = checkedDecodedPlanes.chromaUPlane()
        var chromaVPlane = checkedDecodedPlanes.chromaVPlane()
        if (checkedDecodedPlanes.hasChroma()) {
            val chromaFormat = checkedDecodedPlanes.chromaFormat()
            val subsamplingX = chromaSubsamplingX(chromaFormat)
            val subsamplingY = chromaSubsamplingY(chromaFormat)
            val identityMatrixCoefficients =
                checkedColorConfig.matrixCoefficients == MATRIX_IDENTITY
            val sourceLumaPlane = checkedDecodedPlanes.lumaPlane()

            val sourceChromaUPlane =
                requireNotNullCompat(chromaUPlane, "chromaUPlane")
            val sourceChromaVPlane =
                requireNotNullCompat(chromaVPlane, "chromaVPlane")
            chromaUPlane = FilmGrainSynthesizer.applyChromaPlaneIfNeeded(
                sourceChromaUPlane,
                sourceLumaPlane,
                bitDepth,
                filmGrain,
                lumaScaling,
                lumaGrain,
                CHROMA_CB,
                subsamplingX,
                subsamplingY,
                identityMatrixCoefficients
            )
            chromaVPlane = FilmGrainSynthesizer.applyChromaPlaneIfNeeded(
                sourceChromaVPlane,
                sourceLumaPlane,
                bitDepth,
                filmGrain,
                lumaScaling,
                lumaGrain,
                CHROMA_CR,
                subsamplingX,
                subsamplingY,
                identityMatrixCoefficients
            )
        }

        return DecodedSurface(
            bitDepth,
            checkedDecodedPlanes.chromaFormat(),
            checkedDecodedPlanes.codedWidth(),
            checkedDecodedPlanes.codedHeight(),
            checkedDecodedPlanes.renderWidth(),
            checkedDecodedPlanes.renderHeight(),
            lumaPlane,
            chromaUPlane,
            chromaVPlane
        )
    }

    /** Applies chroma grain when the selected chroma plane has an active scaling source.
     * 
     * @param chromaPlane the post-filter chroma plane
     * @param lumaPlane the post-filter luma plane
     * @param bitDepth the decoded bit depth
     * @param filmGrain the normalized film-grain parameters
     * @param lumaScaling the generated luma scaling table
     * @param lumaGrain the generated luma grain lookup table
     * @param chromaPlaneIndex the AV1 chroma plane index, `0` for Cb and `1` for Cr
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param identityMatrixCoefficients whether chroma uses the luma restricted range
     * @return the grain-applied chroma plane, or the source plane when it has no active grain
     */
    private fun applyChromaPlaneIfNeeded(
        chromaPlane: PaddedPlane,
        lumaPlane: PaddedPlane,
        bitDepth: Int,
        filmGrain: FilmGrainParams,
        lumaScaling: IntArray,
        lumaGrain: Array<IntArray>,
        chromaPlaneIndex: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        identityMatrixCoefficients: Boolean
    ): PaddedPlane {
        val points = if (chromaPlaneIndex == CHROMA_CB)
            filmGrain.cbPoints()
        else
            filmGrain.crPoints()
        if (!filmGrain.chromaScalingFromLuma() && points.size == 0) {
            return chromaPlane
        }

        val scaling = if (filmGrain.chromaScalingFromLuma())
            lumaScaling
        else
            generateScaling(bitDepth, points)
        val chromaGrain = generateChromaGrain(
            bitDepth,
            filmGrain,
            lumaGrain,
            chromaPlaneIndex,
            subsamplingX,
            subsamplingY
        )
        return applyChromaGrain(
            chromaPlane,
            lumaPlane,
            bitDepth,
            filmGrain,
            scaling,
            chromaGrain,
            chromaPlaneIndex,
            subsamplingX,
            subsamplingY,
            identityMatrixCoefficients
        )
    }

    /** Applies generated grain to one luma plane.
     * 
     * @param lumaPlane the post-filter luma plane
     * @param bitDepth the decoded bit depth
     * @param filmGrain the normalized film-grain parameters
     * @param scaling the generated luma scaling table
     * @param grainLookup the generated luma grain lookup table
     * @return the grain-applied luma plane
     */
    private fun applyLumaGrain(
        lumaPlane: PaddedPlane,
        bitDepth: Int,
        filmGrain: FilmGrainParams,
        scaling: IntArray,
        grainLookup: Array<IntArray>
    ): PaddedPlane {
        val outputSamples = lumaPlane.samples()
        val rowCount = (lumaPlane.height() + BLOCK_SIZE - 1) / BLOCK_SIZE
        for (row in 0..<rowCount) {
            applyLumaGrainRow(
                lumaPlane,
                outputSamples,
                bitDepth,
                filmGrain,
                scaling,
                grainLookup,
                row
            )
        }
        return PaddedPlane.fromOwnedSamples(
            lumaPlane.width(),
            lumaPlane.height(),
            lumaPlane.stride(),
            outputSamples
        )
    }

    /** Applies generated grain to one luma film-grain block row.
     * 
     * @param lumaPlane the post-filter luma plane
     * @param outputSamples the mutable output samples
     * @param bitDepth the decoded bit depth
     * @param filmGrain the normalized film-grain parameters
     * @param scaling the generated luma scaling table
     * @param grainLookup the generated luma grain lookup table
     * @param rowNumber the zero-based film-grain block row
     */
    private fun applyLumaGrainRow(
        lumaPlane: PaddedPlane,
        outputSamples: ShortArray,
        bitDepth: Int,
        filmGrain: FilmGrainParams,
        scaling: IntArray,
        grainLookup: Array<IntArray>,
        rowNumber: Int
    ) {
        val blockY = rowNumber * BLOCK_SIZE
        val blockHeight = min(BLOCK_SIZE, lumaPlane.height() - blockY)
        val rowOffsetCount = if (filmGrain.overlapEnabled() && rowNumber > 0) 2 else 1
        val seeds = createBlockRowSeeds(filmGrain.grainSeed(), rowNumber, rowOffsetCount)
        val offsets: Array<IntArray> = Array<IntArray>(2) { IntArray(2) }
        val minimumSample = restrictedRangeMinimum(bitDepth, filmGrain.clipToRestrictedRange())
        val maximumSample =
            restrictedRangeMaximum(bitDepth, true, filmGrain.clipToRestrictedRange())
        val grainMinimum = -(128 shl (bitDepth - 8))
        val grainMaximum = (128 shl (bitDepth - 8)) - 1

        var blockX = 0
        while (blockX < lumaPlane.width()) {
            val blockWidth = min(BLOCK_SIZE, lumaPlane.width() - blockX)
            updateOffsets(filmGrain.overlapEnabled(), blockX, seeds, offsets, rowOffsetCount)

            val yStart = if (filmGrain.overlapEnabled() && rowNumber > 0) min(2, blockHeight) else 0
            val xStart = if (filmGrain.overlapEnabled() && blockX > 0) min(2, blockWidth) else 0
            for (y in yStart..<blockHeight) {
                for (x in xStart..<blockWidth) {
                    val grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y)
                    addLumaNoise(
                        lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                        minimumSample, maximumSample
                    )
                }
                for (x in 0..<xStart) {
                    var grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y)
                    val old = sampleLookup(grainLookup, offsets, 0, 0, 1, 0, x, y)
                    grain = clamp(
                        round2(
                            old * overlapWeight(0, x, true) + grain * overlapWeight(0, x, false),
                            5
                        ),
                        grainMinimum, grainMaximum
                    )
                    addLumaNoise(
                        lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                        minimumSample, maximumSample
                    )
                }
            }
            for (y in 0..<yStart) {
                for (x in xStart..<blockWidth) {
                    var grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y)
                    val old = sampleLookup(grainLookup, offsets, 0, 0, 0, 1, x, y)
                    grain = clamp(
                        round2(
                            old * overlapWeight(0, y, true) + grain * overlapWeight(0, y, false),
                            5
                        ),
                        grainMinimum, grainMaximum
                    )
                    addLumaNoise(
                        lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                        minimumSample, maximumSample
                    )
                }
                for (x in 0..<xStart) {
                    var top = sampleLookup(grainLookup, offsets, 0, 0, 0, 1, x, y)
                    var old = sampleLookup(grainLookup, offsets, 0, 0, 1, 1, x, y)
                    top = clamp(
                        round2(
                            old * overlapWeight(0, x, true) + top * overlapWeight(0, x, false),
                            5
                        ),
                        grainMinimum, grainMaximum
                    )

                    var grain = sampleLookup(grainLookup, offsets, 0, 0, 0, 0, x, y)
                    old = sampleLookup(grainLookup, offsets, 0, 0, 1, 0, x, y)
                    grain = clamp(
                        round2(
                            old * overlapWeight(0, x, true) + grain * overlapWeight(0, x, false),
                            5
                        ),
                        grainMinimum, grainMaximum
                    )

                    grain = clamp(
                        round2(
                            top * overlapWeight(0, y, true) + grain * overlapWeight(0, y, false),
                            5
                        ),
                        grainMinimum, grainMaximum
                    )
                    addLumaNoise(
                        lumaPlane, outputSamples, blockX, blockY, x, y, scaling, filmGrain, grain,
                        minimumSample, maximumSample
                    )
                }
            }
            blockX += BLOCK_SIZE
        }
    }

    /** Applies generated grain to one chroma plane.
     * 
     * @param chromaPlane the post-filter chroma plane
     * @param lumaPlane the post-filter luma plane
     * @param bitDepth the decoded bit depth
     * @param filmGrain the normalized film-grain parameters
     * @param scaling the active scaling table
     * @param grainLookup the generated chroma grain lookup table
     * @param chromaPlaneIndex the AV1 chroma plane index
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param identityMatrixCoefficients whether chroma uses the luma restricted range
     * @return the grain-applied chroma plane
     */
    private fun applyChromaGrain(
        chromaPlane: PaddedPlane,
        lumaPlane: PaddedPlane,
        bitDepth: Int,
        filmGrain: FilmGrainParams,
        scaling: IntArray,
        grainLookup: Array<IntArray>,
        chromaPlaneIndex: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        identityMatrixCoefficients: Boolean
    ): PaddedPlane {
        val outputSamples = chromaPlane.samples()
        val rowCount = (lumaPlane.height() + BLOCK_SIZE - 1) / BLOCK_SIZE
        for (row in 0..<rowCount) {
            applyChromaGrainRow(
                chromaPlane,
                lumaPlane,
                outputSamples,
                bitDepth,
                filmGrain,
                scaling,
                grainLookup,
                chromaPlaneIndex,
                subsamplingX,
                subsamplingY,
                identityMatrixCoefficients,
                row
            )
        }
        return PaddedPlane.fromOwnedSamples(
            chromaPlane.width(),
            chromaPlane.height(),
            chromaPlane.stride(),
            outputSamples
        )
    }

    /** Applies generated grain to one chroma film-grain block row.
     * 
     * @param chromaPlane the post-filter chroma plane
     * @param lumaPlane the post-filter luma plane
     * @param outputSamples the mutable output samples
     * @param bitDepth the decoded bit depth
     * @param filmGrain the normalized film-grain parameters
     * @param scaling the active scaling table
     * @param grainLookup the generated chroma grain lookup table
     * @param chromaPlaneIndex the AV1 chroma plane index
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param identityMatrixCoefficients whether chroma uses the luma restricted range
     * @param rowNumber the zero-based film-grain block row
     */
    private fun applyChromaGrainRow(
        chromaPlane: PaddedPlane,
        lumaPlane: PaddedPlane,
        outputSamples: ShortArray,
        bitDepth: Int,
        filmGrain: FilmGrainParams,
        scaling: IntArray,
        grainLookup: Array<IntArray>,
        chromaPlaneIndex: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        identityMatrixCoefficients: Boolean,
        rowNumber: Int
    ) {
        val blockY = (rowNumber * BLOCK_SIZE) shr subsamplingY
        if (blockY >= chromaPlane.height()) {
            return
        }

        val lumaRowsInBlock = min(BLOCK_SIZE, lumaPlane.height() - rowNumber * BLOCK_SIZE)
        val blockHeight = (lumaRowsInBlock + subsamplingY) shr subsamplingY
        val rowOffsetCount = if (filmGrain.overlapEnabled() && rowNumber > 0) 2 else 1
        val seeds = createBlockRowSeeds(filmGrain.grainSeed(), rowNumber, rowOffsetCount)
        val offsets: Array<IntArray> = Array<IntArray>(2) { IntArray(2) }
        val minimumSample = restrictedRangeMinimum(bitDepth, filmGrain.clipToRestrictedRange())
        val maximumSample = restrictedRangeMaximum(
            bitDepth,
            identityMatrixCoefficients,
            filmGrain.clipToRestrictedRange()
        )
        val grainMinimum = -(128 shl (bitDepth - 8))
        val grainMaximum = (128 shl (bitDepth - 8)) - 1
        val chromaBlockSize = BLOCK_SIZE shr subsamplingX

        var blockX = 0
        while (blockX < chromaPlane.width()) {
            val blockWidth = min(chromaBlockSize, chromaPlane.width() - blockX)
            updateOffsets(filmGrain.overlapEnabled(), blockX, seeds, offsets, rowOffsetCount)

            val yStart = if (filmGrain.overlapEnabled() && rowNumber > 0) min(
                2 shr subsamplingY,
                blockHeight
            ) else
                0
            val xStart = if (filmGrain.overlapEnabled() && blockX > 0) min(
                2 shr subsamplingX,
                blockWidth
            ) else
                0
            for (y in yStart..<blockHeight) {
                for (x in xStart..<blockWidth) {
                    val grain =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y)
                    addChromaNoise(
                        chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                        filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                        minimumSample, maximumSample, bitDepth
                    )
                }
                for (x in 0..<xStart) {
                    var grain =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y)
                    val old =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 1, 0, x, y)
                    grain = clamp(
                        round2(
                            old * overlapWeight(subsamplingX, x, true)
                                    + grain * overlapWeight(subsamplingX, x, false), 5
                        ),
                        grainMinimum, grainMaximum
                    )
                    addChromaNoise(
                        chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                        filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                        minimumSample, maximumSample, bitDepth
                    )
                }
            }
            for (y in 0..<yStart) {
                for (x in xStart..<blockWidth) {
                    var grain =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y)
                    val old =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 1, x, y)
                    grain = clamp(
                        round2(
                            old * overlapWeight(subsamplingY, y, true)
                                    + grain * overlapWeight(subsamplingY, y, false), 5
                        ),
                        grainMinimum, grainMaximum
                    )
                    addChromaNoise(
                        chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                        filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                        minimumSample, maximumSample, bitDepth
                    )
                }
                for (x in 0..<xStart) {
                    var top =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 1, x, y)
                    var old =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 1, 1, x, y)
                    top = clamp(
                        round2(
                            old * overlapWeight(subsamplingX, x, true)
                                    + top * overlapWeight(subsamplingX, x, false), 5
                        ),
                        grainMinimum, grainMaximum
                    )

                    var grain =
                        sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 0, 0, x, y)
                    old = sampleLookup(grainLookup, offsets, subsamplingX, subsamplingY, 1, 0, x, y)
                    grain = clamp(
                        round2(
                            old * overlapWeight(subsamplingX, x, true)
                                    + grain * overlapWeight(subsamplingX, x, false), 5
                        ),
                        grainMinimum, grainMaximum
                    )

                    grain = clamp(
                        round2(
                            top * overlapWeight(subsamplingY, y, true)
                                    + grain * overlapWeight(subsamplingY, y, false), 5
                        ),
                        grainMinimum, grainMaximum
                    )
                    addChromaNoise(
                        chromaPlane, lumaPlane, outputSamples, blockX, blockY, x, y, scaling,
                        filmGrain, grain, chromaPlaneIndex, subsamplingX, subsamplingY,
                        minimumSample, maximumSample, bitDepth
                    )
                }
            }
            blockX += chromaBlockSize
        }
    }

    /** Adds one luma grain sample to the output plane.
     * 
     * @param lumaPlane the post-filter luma plane
     * @param outputSamples the mutable output samples
     * @param blockX the block's horizontal plane coordinate
     * @param blockY the block's vertical plane coordinate
     * @param x the horizontal coordinate inside the block
     * @param y the vertical coordinate inside the block
     * @param scaling the generated scaling table
     * @param filmGrain the normalized film-grain parameters
     * @param grain the signed grain value
     * @param minimumSample the inclusive output minimum
     * @param maximumSample the inclusive output maximum
     */
    private fun addLumaNoise(
        lumaPlane: PaddedPlane,
        outputSamples: ShortArray,
        blockX: Int,
        blockY: Int,
        x: Int,
        y: Int,
        scaling: IntArray,
        filmGrain: FilmGrainParams,
        grain: Int,
        minimumSample: Int,
        maximumSample: Int
    ) {
        val source = lumaPlane.sample(blockX + x, blockY + y)
        val noise = round2(scaling[source] * grain, filmGrain.scalingShift())
        val offset = (blockY + y) * lumaPlane.stride() + blockX + x
        outputSamples[offset] = clamp(source + noise, minimumSample, maximumSample).toShort()
    }

    /** Adds one chroma grain sample to the output plane.
     * 
     * @param chromaPlane the post-filter chroma plane
     * @param lumaPlane the post-filter luma plane
     * @param outputSamples the mutable output samples
     * @param blockX the block's horizontal chroma coordinate
     * @param blockY the block's vertical chroma coordinate
     * @param x the horizontal coordinate inside the block
     * @param y the vertical coordinate inside the block
     * @param scaling the active scaling table
     * @param filmGrain the normalized film-grain parameters
     * @param grain the signed grain value
     * @param chromaPlaneIndex the AV1 chroma plane index
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param minimumSample the inclusive output minimum
     * @param maximumSample the inclusive output maximum
     * @param bitDepth the decoded bit depth
     */
    private fun addChromaNoise(
        chromaPlane: PaddedPlane,
        lumaPlane: PaddedPlane,
        outputSamples: ShortArray,
        blockX: Int,
        blockY: Int,
        x: Int,
        y: Int,
        scaling: IntArray,
        filmGrain: FilmGrainParams,
        grain: Int,
        chromaPlaneIndex: Int,
        subsamplingX: Int,
        subsamplingY: Int,
        minimumSample: Int,
        maximumSample: Int,
        bitDepth: Int
    ) {
        val chromaX = blockX + x
        val chromaY = blockY + y
        if (chromaX >= chromaPlane.width() || chromaY >= chromaPlane.height()) {
            return
        }

        val source = chromaPlane.sample(chromaX, chromaY)
        val lumaX = chromaX shl subsamplingX
        val lumaY = chromaY shl subsamplingY
        var averageLuma =
            lumaPlane.sample(min(lumaX, lumaPlane.width() - 1), min(lumaY, lumaPlane.height() - 1))
        if (subsamplingX != 0) {
            averageLuma = (averageLuma + lumaPlane.sample(
                min(lumaX + 1, lumaPlane.width() - 1),
                min(lumaY, lumaPlane.height() - 1)
            ) + 1) shr 1
        }

        var scalingIndex = averageLuma
        if (!filmGrain.chromaScalingFromLuma()) {
            val combined = (averageLuma * chromaLumaMultiplier(filmGrain, chromaPlaneIndex)
                    + source * chromaMultiplier(filmGrain, chromaPlaneIndex))
            scalingIndex = clamp(
                (combined shr 6) + (chromaOffset(filmGrain, chromaPlaneIndex) shl (bitDepth - 8)),
                0,
                (1 shl bitDepth) - 1
            )
        }

        val noise = round2(scaling[scalingIndex] * grain, filmGrain.scalingShift())
        val offset = chromaY * chromaPlane.stride() + chromaX
        outputSamples[offset] = clamp(source + noise, minimumSample, maximumSample).toShort()
    }

    /** Generates the luma grain lookup table.
     * 
     * @param bitDepth the decoded bit depth
     * @param filmGrain the normalized film-grain parameters
     * @return the generated luma grain lookup table
     */
    private fun generateLumaGrain(bitDepth: Int, filmGrain: FilmGrainParams): Array<IntArray> {
        val grain: Array<IntArray> = Array<IntArray>(GRAIN_HEIGHT) { IntArray(GRAIN_WIDTH) }
        val state = intArrayOf(filmGrain.grainSeed())
        val shift = 4 - (bitDepth - 8) + filmGrain.grainScaleShift()
        val grainMinimum = -(128 shl (bitDepth - 8))
        val grainMaximum = (128 shl (bitDepth - 8)) - 1
        for (y in 0..<GRAIN_HEIGHT) {
            for (x in 0..<GRAIN_WIDTH) {
                grain[y][x] = round2(GAUSSIAN_SEQUENCE[randomNumber(11, state)], shift)
            }
        }

        val lag = filmGrain.arCoeffLag()
        val coefficients = filmGrain.arCoefficientsY()
        if (coefficients.size == 0) {
            return grain
        }
        for (y in AUTO_REGRESSIVE_PAD..<GRAIN_HEIGHT) {
            for (x in AUTO_REGRESSIVE_PAD..<GRAIN_WIDTH - AUTO_REGRESSIVE_PAD) {
                var coefficientIndex = 0
                var sum = 0
                for (dy in -lag..0) {
                    var reachedCurrent = false
                    for (dx in -lag..lag) {
                        if (dx == 0 && dy == 0) {
                            reachedCurrent = true
                            break
                        }
                        sum += coefficients[coefficientIndex++] * grain[y + dy][x + dx]
                    }
                    if (reachedCurrent) {
                        break
                    }
                }
                grain[y][x] = clamp(
                    grain[y][x] + round2(sum, filmGrain.arCoeffShift()),
                    grainMinimum, grainMaximum
                )
            }
        }
        return grain
    }

    /** Generates one chroma grain lookup table.
     * 
     * @param bitDepth the decoded bit depth
     * @param filmGrain the normalized film-grain parameters
     * @param lumaGrain the generated luma grain lookup table
     * @param chromaPlaneIndex the AV1 chroma plane index
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @return the generated chroma grain lookup table
     */
    private fun generateChromaGrain(
        bitDepth: Int,
        filmGrain: FilmGrainParams,
        lumaGrain: Array<IntArray>,
        chromaPlaneIndex: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Array<IntArray> {
        val grainWidth = if (subsamplingX != 0) SUBSAMPLED_GRAIN_WIDTH else GRAIN_WIDTH
        val grainHeight = if (subsamplingY != 0) SUBSAMPLED_GRAIN_HEIGHT else GRAIN_HEIGHT
        val grain: Array<IntArray> = Array<IntArray>(grainHeight) { IntArray(grainWidth) }
        val state = intArrayOf(
            filmGrain.grainSeed() xor (if (chromaPlaneIndex == CHROMA_CR) CHROMA_CR_SEED_OFFSET else CHROMA_CB_SEED_OFFSET)
        )
        val shift = 4 - (bitDepth - 8) + filmGrain.grainScaleShift()
        val grainMinimum = -(128 shl (bitDepth - 8))
        val grainMaximum = (128 shl (bitDepth - 8)) - 1
        for (y in 0..<grainHeight) {
            for (x in 0..<grainWidth) {
                grain[y][x] = round2(GAUSSIAN_SEQUENCE[randomNumber(11, state)], shift)
            }
        }

        val lag = filmGrain.arCoeffLag()
        val coefficients = if (chromaPlaneIndex == CHROMA_CB)
            filmGrain.arCoefficientsCb()
        else
            filmGrain.arCoefficientsCr()
        val hasLumaScalingPoints = filmGrain.yPoints().size != 0
        for (y in AUTO_REGRESSIVE_PAD..<grainHeight) {
            for (x in AUTO_REGRESSIVE_PAD..<grainWidth - AUTO_REGRESSIVE_PAD) {
                var coefficientIndex = 0
                var sum = 0
                for (dy in -lag..0) {
                    var reachedCurrent = false
                    for (dx in -lag..lag) {
                        if (dx == 0 && dy == 0) {
                            if (hasLumaScalingPoints) {
                                sum += (averagedLumaGrain(
                                    lumaGrain,
                                    x,
                                    y,
                                    subsamplingX,
                                    subsamplingY
                                )
                                        * coefficients[coefficientIndex])
                            }
                            reachedCurrent = true
                            break
                        }
                        sum += coefficients[coefficientIndex++] * grain[y + dy][x + dx]
                    }
                    if (reachedCurrent) {
                        break
                    }
                }
                grain[y][x] = clamp(
                    grain[y][x] + round2(sum, filmGrain.arCoeffShift()),
                    grainMinimum, grainMaximum
                )
            }
        }
        return grain
    }

    /** Returns the averaged colocated luma grain contribution for chroma AR synthesis.
     * 
     * @param lumaGrain the generated luma grain lookup table
     * @param x the chroma grain x coordinate
     * @param y the chroma grain y coordinate
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @return the averaged colocated luma grain value
     */
    private fun averagedLumaGrain(
        lumaGrain: Array<IntArray>,
        x: Int,
        y: Int,
        subsamplingX: Int,
        subsamplingY: Int
    ): Int {
        val lumaX = ((x - AUTO_REGRESSIVE_PAD) shl subsamplingX) + AUTO_REGRESSIVE_PAD
        val lumaY = ((y - AUTO_REGRESSIVE_PAD) shl subsamplingY) + AUTO_REGRESSIVE_PAD
        var sum = 0
        for (dy in 0..subsamplingY) {
            for (dx in 0..subsamplingX) {
                sum += lumaGrain[lumaY + dy][lumaX + dx]
            }
        }
        return round2(sum, subsamplingX + subsamplingY)
    }

    /** Generates one full sample-domain scaling table.
     * 
     * @param bitDepth the decoded bit depth
     * @param points the ordered film-grain scaling points
     * @return one full sample-domain scaling table
     */
    private fun generateScaling(bitDepth: Int, points: Array<FilmGrainPoint>): IntArray {
        val scalingSize = 1 shl bitDepth
        val scaling = IntArray(scalingSize)
        if (points.size == 0) {
            return scaling
        }

        val shiftX = bitDepth - 8
        val firstLimit = points[0].value() shl shiftX
        fill(scaling, 0, firstLimit, points[0].scaling())
        for (index in 0..<points.size - 1) {
            val startX = points[index].value()
            val startY = points[index].scaling()
            val endX = points[index + 1].value()
            val endY = points[index + 1].scaling()
            val deltaX = endX - startX
            val deltaY = endY - startY
            val delta = deltaY * ((0x10000 + (deltaX shr 1)) / deltaX)
            var x = 0
            var d = 0x8000
            while (x < deltaX) {
                scaling[(startX + x) shl shiftX] = startY + (d shr 16)
                d += delta
                x++
            }
        }
        val finalStart = points[points.size - 1].value() shl shiftX
        fill(scaling, finalStart, scaling.size, points[points.size - 1].scaling())

        if (shiftX != 0) {
            val pad = 1 shl shiftX
            val round = pad shr 1
            for (index in 0..<points.size - 1) {
                val start = points[index].value() shl shiftX
                val end = points[index + 1].value() shl shiftX
                var x = 0
                while (x < end - start) {
                    val range = scaling[start + x + pad] - scaling[start + x]
                    var n = 1
                    var r = round
                    while (n < pad) {
                        r += range
                        scaling[start + x + n] = scaling[start + x] + (r shr shiftX)
                        n++
                    }
                    x += pad
                }
            }
        }
        return scaling
    }

    /** Fills a half-open integer range in one array.
     * 
     * @param array the destination array
     * @param start the inclusive start index
     * @param end the exclusive end index
     * @param value the value to write
     */
    private fun fill(array: IntArray, start: Int, end: Int, value: Int) {
        for (index in start..<end) {
            array[index] = value
        }
    }

    /** Samples one generated grain lookup table with dav1d block offsets.
     * 
     * @param grainLookup the generated grain lookup table
     * @param offsets the current and neighboring block offsets
     * @param subsamplingX the horizontal chroma subsampling shift
     * @param subsamplingY the vertical chroma subsampling shift
     * @param blockOffsetX the neighbor block column offset
     * @param blockOffsetY the neighbor block row offset
     * @param x the horizontal coordinate inside the current block
     * @param y the vertical coordinate inside the current block
     * @return one signed grain lookup sample
     */
    private fun sampleLookup(
        grainLookup: Array<IntArray>,
        offsets: Array<IntArray>,
        subsamplingX: Int,
        subsamplingY: Int,
        blockOffsetX: Int,
        blockOffsetY: Int,
        x: Int,
        y: Int
    ): Int {
        val randomValue = offsets[blockOffsetX][blockOffsetY]
        val offsetX =
            AUTO_REGRESSIVE_PAD + (2 shr subsamplingX) * (AUTO_REGRESSIVE_PAD + (randomValue shr 4))
        val offsetY =
            AUTO_REGRESSIVE_PAD + (2 shr subsamplingY) * (AUTO_REGRESSIVE_PAD + (randomValue and 0xF))
        return grainLookup[offsetY + y + (BLOCK_SIZE shr subsamplingY) * blockOffsetY][offsetX + x + (BLOCK_SIZE shr subsamplingX) * blockOffsetX]
    }

    /** Updates the current block offset cache.
     * 
     * @param overlapEnabled whether overlap between neighboring blocks is enabled
     * @param blockX the block's horizontal coordinate
     * @param seeds the mutable row seeds
     * @param offsets the mutable block offsets
     * @param rowOffsetCount the number of active row offsets
     */
    private fun updateOffsets(
        overlapEnabled: Boolean,
        blockX: Int,
        seeds: IntArray,
        offsets: Array<IntArray>,
        rowOffsetCount: Int
    ) {
        if (overlapEnabled && blockX > 0) {
            for (row in 0..<rowOffsetCount) {
                offsets[1][row] = offsets[0][row]
            }
        }
        for (row in 0..<rowOffsetCount) {
            offsets[0][row] = randomNumber(8, seeds, row)
        }
    }

    /** Creates the row-local LFSR seeds used to choose block offsets.
     * 
     * @param grainSeed the frame grain seed
     * @param rowNumber the current film-grain block row
     * @param rowOffsetCount the number of active row offsets
     * @return the row-local LFSR seeds
     */
    private fun createBlockRowSeeds(grainSeed: Int, rowNumber: Int, rowOffsetCount: Int): IntArray {
        val seeds = IntArray(rowOffsetCount)
        for (rowOffset in 0..<rowOffsetCount) {
            val seedRow = rowNumber - rowOffset
            var seed = grainSeed
            seed = seed xor (((seedRow * 37 + 178) and 0xFF) shl 8)
            seed = seed xor ((seedRow * 173 + 105) and 0xFF)
            seeds[rowOffset] = seed
        }
        return seeds
    }

    /** Returns one pseudo-random number and updates the selected seed.
     * 
     * @param bits the requested bit count
     * @param states the mutable LFSR states
     * @param index the selected state index
     * @return one pseudo-random number
     */
    private fun randomNumber(bits: Int, states: IntArray, index: Int): Int {
        val current = states[index]
        val bit =
            ((current ushr 0) xor (current ushr 1) xor (current ushr 3) xor (current ushr 12)) and 1
        val next = (current ushr 1) or (bit shl 15)
        states[index] = next
        return (next ushr (16 - bits)) and ((1 shl bits) - 1)
    }

    /** Returns one pseudo-random number and updates the seed.
     * 
     * @param bits the requested bit count
     * @param state the mutable single-element LFSR state
     * @return one pseudo-random number
     */
    private fun randomNumber(bits: Int, state: IntArray): Int {
        return randomNumber(bits, state, 0)
    }

    /** Returns the overlap blending weight.
     * 
     * @param subsampling the active subsampling shift for the blended axis
     * @param offset the coordinate inside the overlapped area
     * @param oldSample whether the weight is for the neighboring block
     * @return the overlap blending weight
     */
    private fun overlapWeight(subsampling: Int, offset: Int, oldSample: Boolean): Int {
        if (subsampling != 0) {
            return if (oldSample) 23 else 22
        }
        if (offset == 0) {
            return if (oldSample) 27 else 17
        }
        return if (oldSample) 17 else 27
    }

    /** Returns the horizontal chroma subsampling shift for one chroma format.
     * 
     * @param chromaFormat the decoded chroma format
     * @return the horizontal chroma subsampling shift
     */
    private fun chromaSubsamplingX(chromaFormat: Av1ChromaFormat): Int {
        return if (chromaFormat == Av1ChromaFormat.YUV420 || chromaFormat == Av1ChromaFormat.YUV422) 1 else 0
    }

    /** Returns the vertical chroma subsampling shift for one chroma format.
     * 
     * @param chromaFormat the decoded chroma format
     * @return the vertical chroma subsampling shift
     */
    private fun chromaSubsamplingY(chromaFormat: Av1ChromaFormat): Int {
        return if (chromaFormat == Av1ChromaFormat.YUV420) 1 else 0
    }

    /** Returns the chroma component multiplier for one chroma plane.
     * 
     * @param filmGrain the normalized film-grain parameters
     * @param chromaPlaneIndex the AV1 chroma plane index
     * @return the chroma component multiplier
     */
    private fun chromaMultiplier(filmGrain: FilmGrainParams, chromaPlaneIndex: Int): Int {
        return if (chromaPlaneIndex == CHROMA_CB) filmGrain.cbMult() else filmGrain.crMult()
    }

    /** Returns the chroma luma multiplier for one chroma plane.
     * 
     * @param filmGrain the normalized film-grain parameters
     * @param chromaPlaneIndex the AV1 chroma plane index
     * @return the chroma luma multiplier
     */
    private fun chromaLumaMultiplier(filmGrain: FilmGrainParams, chromaPlaneIndex: Int): Int {
        return if (chromaPlaneIndex == CHROMA_CB) filmGrain.cbLumaMult() else filmGrain.crLumaMult()
    }

    /** Returns the chroma scaling offset for one chroma plane.
     * 
     * @param filmGrain the normalized film-grain parameters
     * @param chromaPlaneIndex the AV1 chroma plane index
     * @return the chroma scaling offset
     */
    private fun chromaOffset(filmGrain: FilmGrainParams, chromaPlaneIndex: Int): Int {
        return if (chromaPlaneIndex == CHROMA_CB) filmGrain.cbOffset() else filmGrain.crOffset()
    }

    /** Returns the inclusive minimum sample after optional restricted-range clipping.
     * 
     * @param bitDepth the decoded bit depth
     * @param restrictedRange whether restricted-range clipping is enabled
     * @return the inclusive minimum sample after optional restricted-range clipping
     */
    private fun restrictedRangeMinimum(bitDepth: Int, restrictedRange: Boolean): Int {
        return if (restrictedRange) 16 shl (bitDepth - 8) else 0
    }

    /** Returns the inclusive maximum sample after optional restricted-range clipping.
     * 
     * @param bitDepth the decoded bit depth
     * @param useLumaRange whether the restricted-range maximum is 235 rather than 240
     * @param restrictedRange whether restricted-range clipping is enabled
     * @return the inclusive maximum sample after optional restricted-range clipping
     */
    private fun restrictedRangeMaximum(
        bitDepth: Int,
        useLumaRange: Boolean,
        restrictedRange: Boolean
    ): Int {
        if (!restrictedRange) {
            return (1 shl bitDepth) - 1
        }
        return (if (useLumaRange) 235 else 240) shl (bitDepth - 8)
    }

    /** Rounds one signed integer by a power-of-two divisor with positive bias.
     * 
     * @param value the signed input value
     * @param shift the divisor shift
     * @return the rounded value
     */
    private fun round2(value: Int, shift: Int): Int {
        return (value + ((1 shl shift) shr 1)) shr shift
    }

    /** Clamps one signed value into one inclusive integer range.
     * 
     * @param value the candidate value
     * @param minimum the inclusive lower bound
     * @param maximum the inclusive upper bound
     * @return the clamped value
     */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(maximum, value))
    }

    /** Decodes the compressed AV1 Gaussian sequence payload.
     * 
     * @return the decoded AV1 Gaussian sequence
     */
    private fun decodeGaussianSequence(): IntArray {
        val compressed = decodeBase64(GAUSSIAN_SEQUENCE_GZIP_BASE64)
        try {
            val bytes = gzipDecompress(compressed, 4096)
            check(bytes.size == 4096) { "Decoded Gaussian sequence has length " + bytes.size + ", expected 4096" }

            val sequence = IntArray(2048)
            for (index in sequence.indices) {
                sequence[index] =
                    ((bytes[index * 2].toInt() and 0xFF) or ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
                        .toInt()
            }
            return sequence
        } catch (e: AvifIoException) {
            throw IllegalStateException("Failed to decode film grain Gaussian sequence", e)
        }
    }
}
