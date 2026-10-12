// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.bmff

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min
import org.glavo.avif.Av1ChromaFormat
import org.glavo.avif.AvifAuxiliaryImageInfo
import org.glavo.avif.AvifBitDepth
import org.glavo.avif.AvifColorInfo
import org.glavo.avif.AvifDecodeException
import org.glavo.avif.AvifErrorCode
import org.glavo.avif.AvifGainMapInfo
import org.glavo.avif.AvifGainMapMetadata
import org.glavo.avif.AvifImageInfo
import org.glavo.avif.AvifImageTransformInfo
import org.glavo.avif.AvifOpaqueItemProperty
import org.glavo.avif.AvifSequenceInfo
import org.glavo.avif.AvifSignedFraction
import org.glavo.avif.AvifUnsignedFraction
import org.glavo.avif.internal.io.AvifDataSource


/** Parses AVIF and AVIS image sources from BMFF containers. */
class AvifContainerParser private constructor(source: AvifDataSource, metadataSizeLimit: Long) {
    /** The retained container data source. */
    private val source: AvifDataSource

    /** The configured cumulative estimated limit for retained non-image metadata, or zero for no limit. */
    private val metadataSizeLimit: Long

    /** The cumulative estimated byte size reserved for retained non-image metadata. */
    private var retainedMetadataSize: Long = 0

    /** The parsed metadata state. */
    private val meta = MetaState()

    /** Whether an AVIF-compatible `ftyp` box was parsed. */
    private var compatibleFileTypeSeen = false

    /** Whether an `avis` brand was parsed. */
    private var avisBrandSeen = false

    /** Whether a `tmap` compatible brand was parsed. */
    private var tmapBrandSeen = false

    init {
        require(metadataSizeLimit >= 0) { "metadataSizeLimit < 0: $metadataSizeLimit" }
        this.source = requireNotNullCompat(source, "source")
        this.metadataSizeLimit = metadataSizeLimit
    }

    /** Parses AVIF container data.
     * 
     * @return parsed AVIF container data
     * @throws AvifDecodeException if the container is malformed or unsupported
     */
    private fun parse(): AvifContainer {
        val input = BoxInput(source)
        val uniqueTopLevelBoxes: MutableSet<String> = HashSet<String>()
        while (input.hasRemaining()) {
            val header = input.readBoxHeader()
            if (header.sizeZero && !allowsTopLevelSizeZero(header.type)) {
                throw parseFailed(
                    "Top-level BMFF box cannot have size 0: " + header.type,
                    header.offset.toLong()
                )
            }
            val payload = input.slice(header.payloadOffset, header.payloadSize)
            when (header.type) {
                "ftyp" -> {
                    unique(uniqueTopLevelBoxes, header.type, header.offset)
                    parseFileType(payload)
                }

                "meta" -> {
                    unique(uniqueTopLevelBoxes, header.type, header.offset)
                    parseMeta(header, payload)
                }

                "moov" -> {
                    unique(uniqueTopLevelBoxes, header.type, header.offset)
                    avisBrandSeen = true
                    parseMoov(payload)
                }

                else -> {}
            }
            input.skipBoxPayload(header)
            if (forwardMetadataComplete(header.type)) {
                break
            }
        }

        if (!compatibleFileTypeSeen) {
            throw AvifDecodeException(
                AvifErrorCode.INVALID_FTYP,
                "Missing AVIF-compatible ftyp box",
                0L
            )
        }
        if (avisBrandSeen) {
            return parseSequenceImage()
        }
        if (meta.primaryItemId == 0) {
            throw AvifDecodeException(
                AvifErrorCode.MISSING_IMAGE_ITEM,
                "Primary item is not specified",
                null
            )
        }

        val primaryItem = meta.item(meta.primaryItemId)
        if (primaryItem == null || primaryItem.hasUnsupportedEssentialProperty) {
            throw AvifDecodeException(
                AvifErrorCode.MISSING_IMAGE_ITEM,
                "Primary item is not usable",
                null
            )
        }
        if ("av01" != primaryItem.type && "grid" != primaryItem.type) {
            throw unsupported("Unsupported primary item type: " + primaryItem.type, null)
        }

        if ("grid" == primaryItem.type) {
            return parseGridContainer(primaryItem)
        }

        validateOperatingPointStructure(primaryItem, "Primary image")

        val ispe: ImageSpatialExtents? =
            primaryItem.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
        if (ispe == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "Primary AV1 item is missing ispe",
                null
            )
        }
        val av1Config: Av1Config? = primaryItem.firstProperty<Av1Config>(Av1Config::class)
        if (av1Config == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "Primary AV1 item is missing av1C",
                null
            )
        }

        val alphaSource = parseAuxiliarySource(
            primaryItem,
            AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE,
            "Alpha",
            ispe.width,
            ispe.height
        )
        validateItemPremultipliedAlpha(primaryItem, alphaSource, "Primary image")
        val alphaPremultiplied = itemAlphaPremultiplied(primaryItem)
        val depthSource = parseAuxiliarySource(
            primaryItem,
            AvifAuxiliaryImageInfo.Companion.DEPTH_TYPE,
            "Depth",
            ispe.width,
            ispe.height
        )
        val sampleTransform = parseSampleTransform(
            primaryItem,
            ispe.width,
            ispe.height,
            alphaSource != null
        )

        val primarySource: AvifImageSource = AvifImageSource.Companion.item(
            itemPayload(primaryItem),
            operatingPoint(primaryItem),
            selectedSpatialLayer(primaryItem),
            ispe.width,
            ispe.height
        )
        val metadata = collectMetadataPayloads(primaryItem)
        val transformInfo: AvifImageTransformInfo? = extractTransformInfo(
            primaryItem,
            ispe.width,
            ispe.height
        )
        val displaySize: DisplaySize =
            transformedDisplaySize(ispe.width, ispe.height, transformInfo)
        val gainMapPayloads = gainMapPayloads(primaryItem.id)
        val info = AvifImageInfo(
            displaySize.width,
            displaySize.height,
            if (sampleTransform != null)
                sampleTransform.bitDepth()
            else
                AvifBitDepth.Companion.fromBits(av1Config.bitDepth()),
            av1Config.chromaFormat()
        ).withTransformInfo(transformInfo)
            .withAuxiliaryImages(null, auxiliaryImages(primaryItem.id, ispe.width, ispe.height))
            .withAlpha(alphaSource != null, alphaPremultiplied)
            .withGainMapInfo(gainMapPayloads.info)
            .withColorInfo(primaryItem.firstProperty<AvifColorInfo>(AvifColorInfo::class))
            .withIccProfile(metadata.iccProfile)
            .withExif(metadata.exif)
            .withXmp(metadata.xmp)
            .withOpaqueItemProperties(opaqueItemProperties(primaryItem))

        return AvifContainer(
            info,
            primarySource,
            alphaSource,
            depthSource,
            gainMapPayloads.source,
            sampleTransform
        )
    }

    /** Returns whether a forward-only source has supplied all required top-level metadata.
     * 
     * Seekable sources continue through the complete container so malformed trailing boxes remain
     * observable. Forward-only sources stop before media payloads or unrelated trailing data and
     * leave those bytes available for lazy frame decoding.
     * 
     * @param parsedBoxType the type of the top-level box most recently parsed
     * @return whether top-level parsing can finish without reading another box
     */
    private fun forwardMetadataComplete(parsedBoxType: String): Boolean {
        if (source.isSeekable() || !compatibleFileTypeSeen) {
            return false
        }
        if (avisBrandSeen) {
            return "moov" == parsedBoxType
        }
        return "meta" == parsedBoxType && meta.primaryItemId != 0
    }

    /** Parses a grid derived image container.
     * 
     * @param gridItem the primary grid item
     * @return the parsed AVIF container
     * @throws AvifDecodeException if the grid is malformed or unsupported
     */
    private fun parseGridContainer(gridItem: Item): AvifContainer {
        val colorGrid = parseGridPayloads(gridItem)
        val alphaSource = parseGridAuxiliarySource(
            gridItem,
            colorGrid,
            AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE,
            "Alpha"
        )
        validateItemPremultipliedAlpha(gridItem, alphaSource, "Primary grid")
        val alphaPremultiplied = itemAlphaPremultiplied(gridItem)
        val depthSource = parseGridAuxiliarySource(
            gridItem,
            colorGrid,
            AvifAuxiliaryImageInfo.Companion.DEPTH_TYPE,
            "Depth"
        )
        val sampleTransform = parseSampleTransform(
            gridItem,
            colorGrid.outputWidth,
            colorGrid.outputHeight,
            alphaSource != null
        )
        val metadata = collectMetadataPayloads(gridItem)
        val transformInfo: AvifImageTransformInfo? = extractTransformInfo(
            gridItem,
            colorGrid.outputWidth,
            colorGrid.outputHeight
        )
        val displaySize: DisplaySize = transformedDisplaySize(
            colorGrid.outputWidth,
            colorGrid.outputHeight,
            transformInfo
        )
        val gainMapPayloads = gainMapPayloads(gridItem.id)

        val info = AvifImageInfo(
            displaySize.width,
            displaySize.height,
            if (sampleTransform != null)
                sampleTransform.bitDepth()
            else
                AvifBitDepth.Companion.fromBits(colorGrid.representativeAv1C.bitDepth()),
            colorGrid.representativeAv1C.chromaFormat()
        ).withTransformInfo(transformInfo)
            .withAuxiliaryImages(
                null,
                auxiliaryImages(gridItem.id, colorGrid.outputWidth, colorGrid.outputHeight)
            )
            .withAlpha(alphaSource != null, alphaPremultiplied)
            .withGainMapInfo(gainMapPayloads.info)
            .withColorInfo(gridItem.firstProperty<AvifColorInfo>(AvifColorInfo::class))
            .withIccProfile(metadata.iccProfile)
            .withExif(metadata.exif)
            .withXmp(metadata.xmp)
            .withOpaqueItemProperties(opaqueItemProperties(gridItem))

        return AvifContainer(
            info,
            colorGrid.source,
            alphaSource,
            depthSource,
            gainMapPayloads.source,
            sampleTransform
        )
    }

    /** Parses a preferred `sato` Sample Transform alternative for one primary still image.
     * 
     * @param primaryItem the primary color item or grid
     * @param expectedWidth the reconstructed image width
     * @param expectedHeight the reconstructed image height
     * @param primaryAlphaPresent whether the primary image has alpha
     * @return the parsed Sample Transform, or `null` when no preferred alternative is present
     * @throws AvifDecodeException if the selected Sample Transform is malformed or unsupported
     */
    private fun parseSampleTransform(
        primaryItem: Item,
        expectedWidth: Int,
        expectedHeight: Int,
        primaryAlphaPresent: Boolean
    ): SampleTransform? {
        val transformItem = findSampleTransformItem(primaryItem.id)
        if (transformItem == null) {
            return null
        }
        val expressionInput = BoxInput(mergeItemExtents(transformItem))
        val header = expressionInput.readU8()
        val version = header ushr 6
        val arithmeticBitDepthCode = header and 0x03
        if (version != 0) {
            return null
        }
        val intermediateBitDepth = 1 shl (arithmeticBitDepthCode + 3)
        validateTransformProperties(primaryItem, transformItem, "Sample Transform item")

        val transformIspe: ImageSpatialExtents? =
            transformItem.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
        if (transformIspe == null) {
            throw parseFailed("Sample Transform item is missing ispe: " + transformItem.id, 0)
        }
        if (transformIspe.width != expectedWidth || transformIspe.height != expectedHeight) {
            throw parseFailed("Sample Transform ispe differs from the primary image", 0)
        }
        val pixelInformation: PixelInformation? =
            transformItem.firstProperty<PixelInformation>(PixelInformation::class)
        if (pixelInformation == null) {
            throw parseFailed("Sample Transform item is missing pixi: " + transformItem.id, 0)
        }
        // Omitted color properties inherit the primary fallback's interpretation. Any explicitly
        // associated color information must still agree across the transform and its inputs.
        val colorReferenceItem =
            if (hasColorInformation(transformItem)) transformItem else primaryItem
        val outputColorInfo =
            colorReferenceItem.firstProperty<AvifColorInfo>(AvifColorInfo::class)
        val outputBitCount = pixelInformation.bitsPerChannel[0]
        for (bitCount in pixelInformation.bitsPerChannel) {
            if (bitCount != outputBitCount) {
                throw parseFailed("Sample Transform pixi channel depths differ", 0)
            }
        }
        val outputBitDepth: AvifBitDepth?
        try {
            outputBitDepth = AvifBitDepth.Companion.fromBits(outputBitCount)
        } catch (exception: IllegalArgumentException) {
            throw unsupported(
                "Unsupported Sample Transform output bit depth: $outputBitCount",
                null
            )
        }

        val inputIds = transformItem.dimgCellIds
        if (inputIds.isEmpty() || inputIds.size > 32) {
            throw parseFailed("Sample Transform input count must be in [1, 32]", 0)
        }
        val inputs: Array<SampleTransform.Input> =
            arrayOfNullsCompat<SampleTransform.Input>(inputIds.size)
        var representativeConfig: Av1Config? = null
        var primaryInputIndex = -1
        val primaryPremultiplied = itemAlphaPremultiplied(primaryItem)

        for (inputIndex in inputIds.indices) {
            val inputId = inputIds.get(inputIndex)
            val inputItem = meta.item(inputId)
            if (inputItem == null) {
                throw parseFailed("Sample Transform input item not found: $inputId", 0)
            }
            if (inputItem.hasUnsupportedEssentialProperty) {
                throw AvifDecodeException(
                    AvifErrorCode.MISSING_IMAGE_ITEM,
                    "Sample Transform input item is not usable: $inputId",
                    null
                )
            }
            if (inputId == primaryItem.id) {
                if (primaryInputIndex >= 0) {
                    throw parseFailed(
                        "Sample Transform references the primary image item more than once",
                        0
                    )
                }
                primaryInputIndex = inputIndex
            }
            val inputIspe: ImageSpatialExtents? =
                inputItem.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
            if (inputIspe == null) {
                throw parseFailed("Sample Transform input item is missing ispe: $inputId", 0)
            }
            if (inputIspe.width != expectedWidth || inputIspe.height != expectedHeight) {
                throw parseFailed("Sample Transform input dimensions differ: $inputId", 0)
            }
            val inputPixelInformation: PixelInformation? =
                inputItem.firstProperty<PixelInformation>(PixelInformation::class)
            if (inputPixelInformation == null) {
                throw parseFailed("Sample Transform input item is missing pixi: $inputId", 0)
            }
            if (inputPixelInformation.bitsPerChannel.size != pixelInformation.bitsPerChannel.size) {
                throw parseFailed("Sample Transform input channel count differs: $inputId", 0)
            }
            if (hasColorInformation(inputItem) && !sameColorInformation(
                    colorReferenceItem,
                    inputItem
                )
            ) {
                throw parseFailed("Sample Transform input color information differs: $inputId", 0)
            }

            val colorSource: AvifImageSource?
            val alphaSource: AvifImageSource?
            val inputConfig: Av1Config?
            if ("av01" == inputItem.type) {
                validateOperatingPointStructure(inputItem, "Sample Transform input image")
                inputConfig = inputItem.firstProperty<Av1Config>(Av1Config::class)
                if (inputConfig == null) {
                    throw parseFailed("Sample Transform AV1 input is missing av1C: $inputId", 0)
                }
                colorSource = AvifImageSource.Companion.item(
                    itemPayload(inputItem),
                    operatingPoint(inputItem),
                    selectedSpatialLayer(inputItem),
                    expectedWidth,
                    expectedHeight
                )
                alphaSource = parseAuxiliarySource(
                    inputItem,
                    AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE,
                    "Sample Transform alpha",
                    expectedWidth,
                    expectedHeight
                )
            } else if ("grid" == inputItem.type) {
                val inputGrid = parseGridPayloads(inputItem)
                if (inputGrid.outputWidth != expectedWidth || inputGrid.outputHeight != expectedHeight) {
                    throw parseFailed(
                        "Sample Transform input grid dimensions differ: $inputId",
                        0
                    )
                }
                inputConfig = inputGrid.representativeAv1C
                colorSource = inputGrid.source
                alphaSource = parseGridAuxiliarySource(
                    inputItem,
                    inputGrid,
                    AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE,
                    "Sample Transform alpha"
                )
            } else {
                throw unsupported(
                    "Unsupported Sample Transform input item type: " + inputItem.type,
                    null
                )
            }

            for (bitCount in inputPixelInformation.bitsPerChannel) {
                if (bitCount != inputConfig.bitDepth()) {
                    throw parseFailed(
                        "Sample Transform input pixi differs from av1C: $inputId",
                        0
                    )
                }
            }

            if (representativeConfig == null) {
                representativeConfig = inputConfig
            } else if (representativeConfig.monochrome != inputConfig.monochrome || representativeConfig.chromaSubsamplingX != inputConfig.chromaSubsamplingX || representativeConfig.chromaSubsamplingY != inputConfig.chromaSubsamplingY || representativeConfig.chromaSamplePosition != inputConfig.chromaSamplePosition) {
                throw parseFailed("Sample Transform input chroma layouts differ", 0)
            }

            if ((alphaSource != null) != primaryAlphaPresent) {
                throw unsupported(
                    "Sample Transform inputs must either all have alpha or all omit alpha",
                    null
                )
            }
            if (alphaSource != null) {
                validateItemPremultipliedAlpha(inputItem, alphaSource, "Sample Transform input")
                if (itemAlphaPremultiplied(inputItem) != primaryPremultiplied) {
                    throw unsupported(
                        "Sample Transform input alpha premultiplication differs",
                        null
                    )
                }
            }
            inputs[inputIndex] = SampleTransform.Input(
                colorSource,
                AvifBitDepth.Companion.fromBits(inputConfig.bitDepth()),
                alphaSource
            )
        }

        if (primaryInputIndex < 0) {
            throw parseFailed("Sample Transform does not reference the primary image item", 0)
        }
        checkNotNull(representativeConfig)
        val expectedChannelCount = if (representativeConfig.monochrome) 1 else 3
        if (pixelInformation.bitsPerChannel.size != expectedChannelCount) {
            throw parseFailed("Sample Transform pixi channel count differs from its inputs", 0)
        }

        val tokenCount = expressionInput.readU8()
        if (tokenCount == 0) {
            throw parseFailed(
                "Sample Transform expression has no tokens",
                expressionInput.offset() - 1L
            )
        }
        val tokenCodes = IntArray(tokenCount)
        val constantValues = LongArray(tokenCount)
        var hasReservedToken = false
        for (tokenIndex in 0..<tokenCount) {
            val tokenCode = expressionInput.readU8()
            tokenCodes[tokenIndex] = tokenCode
            if (tokenCode == 0) {
                constantValues[tokenIndex] = when (intermediateBitDepth) {
                    8 -> expressionInput.readI8().toLong()
                    16 -> expressionInput.readI16().toLong()
                    32 -> expressionInput.readI32().toLong()
                    64 -> expressionInput.readI64()
                    else -> throw AssertionError("Unexpected intermediate bit depth: $intermediateBitDepth")
                }
            } else if (isReservedSampleTransformToken(tokenCode)) {
                hasReservedToken = true
            }
        }
        if (expressionInput.hasRemaining()) {
            throw parseFailed(
                "Sample Transform expression has trailing bytes",
                expressionInput.offset().toLong()
            )
        }
        if (hasReservedToken) {
            return null
        }
        try {
            return SampleTransform(
                outputBitDepth,
                outputColorInfo == null || outputColorInfo.fullRange(),
                intermediateBitDepth,
                tokenCodes,
                constantValues,
                inputs,
                primaryInputIndex
            )
        } catch (exception: IllegalArgumentException) {
            throw parseFailed("Invalid Sample Transform expression: " + exception.message, 0)
        }
    }

    /** Finds the first usable `sato` item preferred to the primary image in an `altr` group.
     * 
     * @param primaryItemId the primary image item id
     * @return the preferred Sample Transform item, or `null`
     */
    private fun findSampleTransformItem(primaryItemId: Int): Item? {
        for (group in meta.entityGroups) {
            if ("altr" != group.type) {
                continue
            }
            var primaryIndex = -1
            for (i in group.entityIds.indices) {
                if (group.entityIds[i] == primaryItemId) {
                    primaryIndex = i
                    break
                }
            }
            if (primaryIndex < 0) {
                continue
            }
            for (i in 0..<primaryIndex) {
                val candidate = meta.item(group.entityIds[i])
                if (candidate != null && candidate.id != primaryItemId && "sato" == candidate.type
                    && !candidate.extents.isEmpty() && !candidate.hasUnsupportedEssentialProperty
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    /** Parses the payloads and geometry for one `grid` derived image item.
     * 
     * @param gridItem the grid item
     * @return parsed grid payloads and geometry
     * @throws AvifDecodeException if the grid is malformed or unsupported
     */
    private fun parseGridPayloads(gridItem: Item): GridPayloads {
        validateOperatingPointStructure(gridItem, "Grid image")
        val gridPayload = mergeItemExtents(gridItem)
        val input = BoxInput(gridPayload)
        val version = input.readU8()
        val flags = input.readU8()
        if (version != 0) {
            throw unsupported("Unsupported grid version: $version", null)
        }
        if ((flags and 1.inv()) != 0) {
            throw parseFailed("grid contains reserved flags: $flags", 1)
        }
        val rowsMinusOne = input.readU8()
        val columnsMinusOne = input.readU8()
        val rows = rowsMinusOne + 1
        val columns = columnsMinusOne + 1
        if (rows <= 0 || columns <= 0) {
            throw parseFailed("grid dimensions must be positive", 2)
        }

        val outputWidthValue: Long
        val outputHeightValue: Long
        if ((flags and 1) == 0) {
            outputWidthValue = input.readU16().toLong()
            outputHeightValue = input.readU16().toLong()
        } else {
            outputWidthValue = input.readU32()
            outputHeightValue = input.readU32()
        }
        if (outputWidthValue == 0L || outputHeightValue == 0L) {
            throw parseFailed("grid output dimensions must be positive", input.offset().toLong())
        }
        if (outputWidthValue > Int.MAX_VALUE || outputHeightValue > Int.MAX_VALUE) {
            throw unsupported(
                ("Grid output dimensions exceed the supported Java array range: "
                        + outputWidthValue + "x$outputHeightValue"),
                null
            )
        }
        if (input.hasRemaining()) {
            throw parseFailed("grid payload has trailing bytes", input.offset().toLong())
        }
        val outputWidth = outputWidthValue.toInt()
        val outputHeight = outputHeightValue.toInt()

        val expectedCellCount = rows * columns
        val cellIds = gridItem.dimgCellIds
        if (cellIds.size != expectedCellCount) {
            throw parseFailed(
                "grid dimg cell count mismatch: expected $expectedCellCount but got " + cellIds.size,
                0
            )
        }

        var representativeAv1C: Av1Config? = null
        val cellPayloads: MutableList<AvifPayload> = ArrayList<AvifPayload>(expectedCellCount)
        val cellOperatingPoints = IntArray(expectedCellCount)
        val cellSelectedSpatialLayers = IntArray(expectedCellCount)
        val cellWidths = IntArray(expectedCellCount)
        val cellHeights = IntArray(expectedCellCount)

        for (i in 0..<expectedCellCount) {
            val cellId = cellIds.get(i)
            val cellItem = meta.item(cellId)
            if (cellItem == null) {
                throw parseFailed("grid dimg cell item not found: $cellId", 0)
            }
            if ("av01" != cellItem.type) {
                throw unsupported("Unsupported grid cell item type: " + cellItem.type, null)
            }
            if (cellItem.hasUnsupportedEssentialProperty) {
                throw AvifDecodeException(
                    AvifErrorCode.MISSING_IMAGE_ITEM,
                    "Grid cell item is not usable: $cellId",
                    null
                )
            }
            validateOperatingPointStructure(cellItem, "Grid cell image")
            val cellIspe: ImageSpatialExtents? =
                cellItem.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
            if (cellIspe == null) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Grid cell item is missing ispe: $cellId",
                    null
                )
            }
            val cellAv1C: Av1Config? = cellItem.firstProperty<Av1Config>(Av1Config::class)
            if (cellAv1C == null) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Grid cell item is missing av1C: $cellId",
                    null
                )
            }
            if (representativeAv1C == null) {
                representativeAv1C = cellAv1C
            }
            cellPayloads.add(itemPayload(cellItem))
            cellOperatingPoints[i] = operatingPoint(cellItem)
            cellSelectedSpatialLayers[i] = selectedSpatialLayer(cellItem)
            cellWidths[i] = cellIspe.width
            cellHeights[i] = cellIspe.height
        }

        checkNotNull(representativeAv1C)

        val payloads: Array<AvifPayload> =
            cellPayloads.toTypedArray()
        return GridPayloads(
            rows,
            columns,
            outputWidth,
            outputHeight,
            representativeAv1C,
            AvifImageSource.Companion.grid(
                payloads,
                cellOperatingPoints,
                cellSelectedSpatialLayers,
                cellWidths,
                cellHeights,
                rows,
                columns,
                outputWidth,
                outputHeight
            )
        )
    }

    /** Parses AV1 auxiliary payloads for a non-grid color item.
     * 
     * @param imageItem the color image item
     * @param auxiliaryType the auxiliary image type string
     * @param label the diagnostic auxiliary label
     * @param expectedWidth the expected auxiliary width
     * @param expectedHeight the expected auxiliary height
     * @return the auxiliary image source, or `null` when no matching auxiliary image is present
     * @throws AvifDecodeException if auxiliary data is malformed or unsupported
     */
    private fun parseAuxiliarySource(
        imageItem: Item,
        auxiliaryType: String,
        label: String,
        expectedWidth: Int,
        expectedHeight: Int
    ): AvifImageSource? {
        val auxiliaryItem = findAuxiliaryItem(imageItem.id, auxiliaryType)
        if (auxiliaryItem == null) {
            return null
        }
        if (auxiliaryItem.hasUnsupportedEssentialProperty) {
            throw AvifDecodeException(
                AvifErrorCode.MISSING_IMAGE_ITEM,
                label + " auxiliary item is not usable: " + auxiliaryItem.id,
                null
            )
        }
        if ("av01" != auxiliaryItem.type) {
            throw unsupported(
                "Unsupported $label auxiliary item type: " + auxiliaryItem.type,
                null
            )
        }
        validateOperatingPointStructure(auxiliaryItem, label + " auxiliary image")
        val outputWidth: Int
        val outputHeight: Int
        if (isAlphaAuxiliaryType(auxiliaryType)) {
            validateAlphaAuxiliaryItemDimensions(
                auxiliaryItem,
                label,
                expectedWidth,
                expectedHeight
            )
            validateTransformProperties(imageItem, auxiliaryItem, label)
            outputWidth = expectedWidth
            outputHeight = expectedHeight
        } else {
            val auxiliaryIspe = requireAuxiliaryItemProperties(auxiliaryItem, label)
            outputWidth = auxiliaryIspe.width
            outputHeight = auxiliaryIspe.height
        }
        return AvifImageSource.Companion.item(
            itemPayload(auxiliaryItem),
            operatingPoint(auxiliaryItem),
            selectedSpatialLayer(auxiliaryItem),
            outputWidth,
            outputHeight
        )
    }

    /** Parses auxiliary payloads for a grid color item.
     * 
     * @param gridItem the color grid item
     * @param colorGrid the parsed color grid
     * @param auxiliaryType the auxiliary image type string
     * @param label the diagnostic auxiliary label
     * @return the auxiliary image source, or `null` when no matching auxiliary image is present
     * @throws AvifDecodeException if auxiliary data is malformed or unsupported
     */
    private fun parseGridAuxiliarySource(
        gridItem: Item,
        colorGrid: GridPayloads,
        auxiliaryType: String,
        label: String
    ): AvifImageSource? {
        val auxiliaryItem = findAuxiliaryItem(gridItem.id, auxiliaryType)
        if (auxiliaryItem != null) {
            if (auxiliaryItem.hasUnsupportedEssentialProperty) {
                throw AvifDecodeException(
                    AvifErrorCode.MISSING_IMAGE_ITEM,
                    label + " auxiliary item is not usable: " + auxiliaryItem.id,
                    null
                )
            }
            if (isAlphaAuxiliaryType(auxiliaryType)) {
                validateTransformProperties(gridItem, auxiliaryItem, label)
            }
            if ("grid" == auxiliaryItem.type) {
                val auxiliaryGrid = parseGridPayloads(auxiliaryItem)
                if (isAlphaAuxiliaryType(auxiliaryType)) {
                    validateAlphaGridDimensions(colorGrid, auxiliaryGrid, label)
                }
                return auxiliaryGrid.source
            }
            if ("av01" != auxiliaryItem.type) {
                throw unsupported(
                    "Unsupported $label auxiliary item type: " + auxiliaryItem.type,
                    null
                )
            }
            validateOperatingPointStructure(auxiliaryItem, label + " auxiliary image")
            val outputWidth: Int
            val outputHeight: Int
            if (isAlphaAuxiliaryType(auxiliaryType)) {
                validateAlphaAuxiliaryItemDimensions(
                    auxiliaryItem,
                    label,
                    colorGrid.outputWidth,
                    colorGrid.outputHeight
                )
                outputWidth = colorGrid.outputWidth
                outputHeight = colorGrid.outputHeight
            } else {
                val auxiliaryIspe = requireAuxiliaryItemProperties(auxiliaryItem, label)
                outputWidth = auxiliaryIspe.width
                outputHeight = auxiliaryIspe.height
            }
            return AvifImageSource.Companion.item(
                itemPayload(auxiliaryItem),
                operatingPoint(auxiliaryItem),
                selectedSpatialLayer(auxiliaryItem),
                outputWidth,
                outputHeight
            )
        }

        return parsePerCellGridAuxiliarySource(gridItem, colorGrid, auxiliaryType, label)
    }

    /** Parses the legacy grid-auxiliary shape where each color cell has its own auxiliary item.
     * 
     * @param gridItem the color grid item
     * @param colorGrid the parsed color grid
     * @param auxiliaryType the auxiliary image type string
     * @param label the diagnostic auxiliary label
     * @return the auxiliary image source, or `null` when no complete per-cell auxiliary set is present
     * @throws AvifDecodeException if auxiliary data is malformed or unsupported
     */
    private fun parsePerCellGridAuxiliarySource(
        gridItem: Item,
        colorGrid: GridPayloads,
        auxiliaryType: String,
        label: String
    ): AvifImageSource? {
        val cellIds = gridItem.dimgCellIds
        val auxiliaryCellPayloads: Array<AvifPayload> =
            arrayOfNullsCompat<AvifPayload>(cellIds.size)
        val auxiliaryCellOperatingPoints = IntArray(cellIds.size)
        val auxiliaryCellSelectedSpatialLayers = IntArray(cellIds.size)
        val auxiliaryCellWidths = IntArray(cellIds.size)
        val auxiliaryCellHeights = IntArray(cellIds.size)
        for (i in cellIds.indices) {
            val cellId = cellIds.get(i)
            val colorCellItem = requireItem(cellId, -1L)
            val auxiliaryCellItem = findAuxiliaryItem(cellId, auxiliaryType)
            if (auxiliaryCellItem == null) {
                return null
            }
            if ("av01" != auxiliaryCellItem.type) {
                throw unsupported(
                    "Unsupported per-cell $label auxiliary item type: " + auxiliaryCellItem.type,
                    null
                )
            }
            if (auxiliaryCellItem.dimgForId != 0) {
                throw unsupported(
                    "Per-cell $label auxiliary item is also a derived-image cell: " + auxiliaryCellItem.id,
                    null
                )
            }
            if (auxiliaryCellItem.hasUnsupportedEssentialProperty) {
                throw AvifDecodeException(
                    AvifErrorCode.MISSING_IMAGE_ITEM,
                    "Per-cell $label auxiliary item is not usable: " + auxiliaryCellItem.id,
                    null
                )
            }
            validateOperatingPointStructure(
                auxiliaryCellItem,
                "Per-cell $label auxiliary image"
            )
            val colorIspe: ImageSpatialExtents? =
                checkNotNull(colorCellItem.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class))
            if (isAlphaAuxiliaryType(auxiliaryType)) {
                validateAlphaAuxiliaryItemDimensions(
                    auxiliaryCellItem,
                    label,
                    colorIspe!!.width,
                    colorIspe.height
                )
                validateTransformProperties(colorCellItem, auxiliaryCellItem, "Per-cell $label")
                auxiliaryCellWidths[i] = colorIspe.width
                auxiliaryCellHeights[i] = colorIspe.height
            } else {
                val auxiliaryIspe = requireAuxiliaryItemProperties(auxiliaryCellItem, label)
                if (auxiliaryIspe.width != colorIspe!!.width || auxiliaryIspe.height != colorIspe.height) {
                    throw parseFailed(
                        "Per-cell $label auxiliary dimensions must match the associated color cell"
                    )
                }
                auxiliaryCellWidths[i] = auxiliaryIspe.width
                auxiliaryCellHeights[i] = auxiliaryIspe.height
            }
            auxiliaryCellPayloads[i] = itemPayload(auxiliaryCellItem)
            auxiliaryCellOperatingPoints[i] = operatingPoint(auxiliaryCellItem)
            auxiliaryCellSelectedSpatialLayers[i] = selectedSpatialLayer(auxiliaryCellItem)
        }
        return AvifImageSource.Companion.grid(
            auxiliaryCellPayloads,
            auxiliaryCellOperatingPoints,
            auxiliaryCellSelectedSpatialLayers,
            auxiliaryCellWidths,
            auxiliaryCellHeights,
            colorGrid.rows,
            colorGrid.columns,
            colorGrid.outputWidth,
            colorGrid.outputHeight
        )
    }

    /** Requires the spatial extents and AV1 configuration of one non-alpha auxiliary item.
     * 
     * @param auxiliaryItem the auxiliary item
     * @param label the diagnostic auxiliary label
     * @return the auxiliary image spatial extents
     * @throws AvifDecodeException if a required property is absent
     */
    private fun requireAuxiliaryItemProperties(
        auxiliaryItem: Item,
        label: String
    ): ImageSpatialExtents {
        val auxiliaryIspe: ImageSpatialExtents? =
            auxiliaryItem.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
        if (auxiliaryIspe == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " auxiliary item is missing ispe: " + auxiliaryItem.id,
                null
            )
        }
        if (auxiliaryItem.firstProperty<Av1Config>(Av1Config::class) == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " auxiliary item is missing av1C: " + auxiliaryItem.id,
                null
            )
        }
        return auxiliaryIspe
    }

    /** Validates one alpha auxiliary item against the expected output dimensions.
     * 
     * Legacy alpha items are allowed to omit `ispe`; in that case AVIF readers treat the alpha
     * canvas as matching the associated master image.
     * 
     * @param auxiliaryItem the alpha auxiliary item
     * @param label the diagnostic auxiliary label
     * @param expectedWidth the expected alpha width
     * @param expectedHeight the expected alpha height
     * @throws AvifDecodeException if the item is malformed or has unsupported dimensions
     */
    private fun validateAlphaAuxiliaryItemDimensions(
        auxiliaryItem: Item,
        label: String,
        expectedWidth: Int,
        expectedHeight: Int
    ) {
        val auxiliaryIspe: ImageSpatialExtents? =
            auxiliaryItem.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
        if (auxiliaryIspe != null
            && (auxiliaryIspe.width != expectedWidth || auxiliaryIspe.height != expectedHeight)
        ) {
            throw parseFailed(label + " auxiliary image dimensions must match the master image")
        }
        if (auxiliaryItem.firstProperty<Av1Config>(Av1Config::class) == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " auxiliary item is missing av1C: " + auxiliaryItem.id,
                null
            )
        }
    }

    /** Collects metadata payloads associated with one rendered image item.
     * 
     * @param imageItem the color or grid image item
     * @return the associated metadata payloads
     * @throws AvifDecodeException if metadata item extents are malformed
     */
    private fun collectMetadataPayloads(imageItem: Item): MetadataPayloads {
        var iccProfile: ByteArray? = null
        val iccProperty: IccColorProfile? =
            imageItem.firstProperty<IccColorProfile>(IccColorProfile::class)
        if (iccProperty != null) {
            iccProfile = iccProperty.profile
        }

        var exif: ByteArray? = null
        var xmp: ByteArray? = null
        for (item in meta.items.values) {
            if (item.descForId != imageItem.id) {
                continue
            }
            if (exif == null && "Exif" == item.type) {
                exif = exifPayload(item)
            } else if (xmp == null && "mime" == item.type
                && (XMP_CONTENT_TYPE == item.contentType || "XMP" == item.name)
            ) {
                xmp = mergeItemExtents(item)
            }
        }
        return MetadataPayloads(iccProfile, exif, xmp)
    }

    /** Reads and normalizes an Exif metadata item.
     * 
     * @param item the Exif item
     * @return the Exif payload without the AVIF Exif header offset field
     * @throws AvifDecodeException if the Exif item is malformed
     */
    private fun exifPayload(item: Item): ByteArray {
        val payload = mergeItemExtents(item)
        if (payload.size < 4) {
            throw parseFailed("Exif item is missing exif_tiff_header_offset: " + item.id, 0)
        }
        return copyOfRangeCompat(payload, 4, payload.size)
    }

    /** Validates progressive dependencies referenced by one renderable image item.
     * 
     * @param item the image item to validate
     * @param label the diagnostic label used in failure messages
     * @throws AvifDecodeException if a progressive dependency item is missing
     */
    private fun validateOperatingPointStructure(item: Item, label: String) {
        for (depId in item.progDeps) {
            val depItem = meta.item(depId)
            if (depItem == null) {
                throw parseFailed(label + " progressive dependency item not found: $depId", 0)
            }
        }
    }

    /** Parses an `ftyp` box.
     * 
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseFileType(input: BoxInput) {
        val majorBrand = input.readFourCc()
        input.skip(4)

        var hasAvif = "avif" == majorBrand
        var hasAvis = "avis" == majorBrand
        var hasTmap = "tmap" == majorBrand
        while (input.remaining() >= 4) {
            val brand = input.readFourCc()
            hasAvif = hasAvif or ("avif" == brand)
            hasAvis = hasAvis or ("avis" == brand)
            hasTmap = hasTmap or ("tmap" == brand)
        }
        if (input.remaining() != 0) {
            throw parseFailed(
                "ftyp compatible brands length is not divisible by four",
                input.offset().toLong()
            )
        }
        compatibleFileTypeSeen = hasAvif || hasAvis
        avisBrandSeen = hasAvis
        tmapBrandSeen = hasTmap
    }

    /** Parses a root `meta` box.
     * 
     * @param header the enclosing box header
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseMeta(header: BoxHeader, input: BoxInput) {
        readFullBox(input)
        var firstChild = true
        val uniqueBoxes: MutableSet<String> = HashSet<String>()
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            val payload = input.slice(child.payloadOffset, child.payloadSize)
            if (firstChild && "hdlr" != child.type) {
                throw parseFailed("meta box must start with hdlr", child.offset.toLong())
            }
            firstChild = false

            when (child.type) {
                "hdlr" -> {
                    unique(uniqueBoxes, "hdlr", child.offset)
                    parseHandler(payload)
                }

                "pitm" -> {
                    unique(uniqueBoxes, "pitm", child.offset)
                    parsePrimaryItem(payload)
                }

                "iloc" -> {
                    unique(uniqueBoxes, "iloc", child.offset)
                    parseItemLocation(payload)
                }

                "iinf" -> {
                    unique(uniqueBoxes, "iinf", child.offset)
                    parseItemInfo(payload)
                }

                "iprp" -> {
                    unique(uniqueBoxes, "iprp", child.offset)
                    parseItemProperties(child, payload)
                }

                "iref" -> {
                    unique(uniqueBoxes, "iref", child.offset)
                    parseItemReference(payload)
                }

                "grpl" -> {
                    unique(uniqueBoxes, "grpl", child.offset)
                    parseGroupsList(payload)
                }

                "idat" -> {
                    unique(uniqueBoxes, "idat", child.offset)
                    meta.idatOffset = payload.offset().toLong()
                    meta.idatLength = payload.remaining()
                    payload.skip(payload.remaining())
                }

                else -> {}
            }
            input.skipBoxPayload(child)
        }
        if (firstChild) {
            throw parseFailed("meta box has no child boxes", header.offset.toLong())
        }
    }

    /** Parses a `pitm` box.
     * 
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parsePrimaryItem(input: BoxInput) {
        val fullBox: FullBox = readFullBox(input)
        meta.primaryItemId = if (fullBox.version == 0) input.readU16() else checkedU32ToInt(
            input.readU32(),
            input.offset() - 4
        )
    }

    /** Parses an `iloc` box.
     * 
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseItemLocation(input: BoxInput) {
        val fullBox: FullBox = readFullBox(input)
        if (fullBox.version > 2) {
            throw unsupported("Unsupported iloc version: " + fullBox.version, input.offset())
        }
        val packedSizes = input.readU8()
        val offsetSize = packedSizes ushr 4
        val lengthSize = packedSizes and 0x0F
        val packedBaseAndIndex = input.readU8()
        val baseOffsetSize = packedBaseAndIndex ushr 4
        val indexSize = if (fullBox.version == 0) 0 else (packedBaseAndIndex and 0x0F)
        validateIlocFieldSize(offsetSize, input.offset())
        validateIlocFieldSize(lengthSize, input.offset())
        validateIlocFieldSize(baseOffsetSize, input.offset())
        validateIlocFieldSize(indexSize, input.offset())

        val itemCount = if (fullBox.version < 2) input.readU16() else checkedU32ToInt(
            input.readU32(),
            input.offset() - 4
        )
        for (i in 0..<itemCount) {
            val itemId = if (fullBox.version < 2) input.readU16() else checkedU32ToInt(
                input.readU32(),
                input.offset() - 4
            )
            val item = requireItem(itemId, input.offset().toLong())
            var constructionMethod = 0
            if (fullBox.version > 0) {
                val packedConstruction = input.readU16()
                if ((packedConstruction and 0xFFF0) != 0) {
                    throw parseFailed(
                        "iloc reserved bits must be zero",
                        (input.offset() - 2).toLong()
                    )
                }
                constructionMethod = packedConstruction and 0x000F
                if (constructionMethod != 0 && constructionMethod != 1) {
                    throw unsupported(
                        "Unsupported iloc construction method: $constructionMethod",
                        input.offset() - 2
                    )
                }
            }
            input.skip(2)
            val baseOffset: Long = readUx(input, baseOffsetSize)
            val extentCount = input.readU16()
            reserveRetainedElements(
                extentCount.toLong(),
                RETAINED_EXTENT_SIZE,
                input.offset() - 2L,
                "iloc extents"
            )
            item.extents.clear()
            item.idatStored = constructionMethod == 1
            for (extentIndex in 0..<extentCount) {
                if (indexSize > 0) {
                    readUx(input, indexSize)
                }
                val extentOffset: Long = readUx(input, offsetSize)
                val extentLength: Long = readUx(input, lengthSize)
                val absoluteOffset: Long = checkedAdd(baseOffset, extentOffset, input.offset())
                item.extents.add(
                    Extent(
                        absoluteOffset,
                        checkedU64ToInt(extentLength, input.offset())
                    )
                )
            }
        }
    }

    /** Parses an `iinf` box.
     * 
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseItemInfo(input: BoxInput) {
        val fullBox: FullBox = readFullBox(input)
        val entryCount = if (fullBox.version == 0) input.readU16() else checkedU32ToInt(
            input.readU32(),
            input.offset() - 4
        )
        for (i in 0..<entryCount) {
            val infe = input.readBoxHeader()
            if ("infe" != infe.type) {
                throw parseFailed("iinf entry is not infe", infe.offset.toLong())
            }
            parseItemInfoEntry(input.slice(infe.payloadOffset, infe.payloadSize), infe.offset)
            input.skipBoxPayload(infe)
        }
    }

    /** Parses an `infe` item info entry.
     * 
     * @param input the box payload input
     * @param boxOffset the enclosing box offset
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseItemInfoEntry(input: BoxInput, boxOffset: Int) {
        val fullBox: FullBox = readFullBox(input)
        if (fullBox.version < 2 || fullBox.version > 3) {
            throw unsupported("Unsupported infe version: " + fullBox.version, boxOffset)
        }
        val itemId = if (fullBox.version == 2) input.readU16() else checkedU32ToInt(
            input.readU32(),
            input.offset() - 4
        )
        input.skip(2)
        val item = requireItem(itemId, input.offset().toLong())
        item.type = input.readFourCc()
        reserveMetadataBytes(
            input.remaining().toLong(),
            input.offset().toLong(),
            "item information strings"
        )
        item.name = readNullTerminatedString(input)
        if ("mime" == item.type) {
            item.contentType = readNullTerminatedString(input)
            if (input.hasRemaining()) {
                readNullTerminatedString(input)
            }
        }
    }

    /** Parses an `iprp` box.
     * 
     * @param header the enclosing box header
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseItemProperties(header: BoxHeader, input: BoxInput) {
        var propertyContainerSeen = false
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            val payload = input.slice(child.payloadOffset, child.payloadSize)
            when (child.type) {
                "ipco" -> {
                    if (propertyContainerSeen) {
                        throw parseFailed(
                            "Box[iprp] contains a duplicate unique box of type 'ipco'",
                            child.offset.toLong()
                        )
                    }
                    propertyContainerSeen = true
                    parseItemPropertyContainer(child, payload)
                }

                "ipma" -> parseItemPropertyAssociation(payload)
                else -> {}
            }
            input.skipBoxPayload(child)
        }
        if (meta.properties.isEmpty()) {
            throw parseFailed("iprp contains no parsed properties", header.offset.toLong())
        }
    }

    /** Parses an `ipco` box.
     * 
     * @param header the enclosing box header
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseItemPropertyContainer(header: BoxHeader, input: BoxInput) {
        while (input.hasRemaining()) {
            val propertyHeader = input.readBoxHeader()
            val payload = input.slice(propertyHeader.payloadOffset, propertyHeader.payloadSize)
            reserveMetadataBytes(
                RETAINED_PROPERTY_SIZE,
                propertyHeader.offset.toLong(),
                "item property structure"
            )
            meta.properties.add(parseProperty(propertyHeader, payload))
            input.skipBoxPayload(propertyHeader)
        }
        if (meta.properties.isEmpty()) {
            throw parseFailed("ipco contains no properties", header.offset.toLong())
        }
    }

    /** Parses one item property.
     * 
     * @param header the property box header
     * @param input the property payload input
     * @return the parsed property
     * @throws AvifDecodeException if the property is malformed
     */
    private fun parseProperty(header: BoxHeader, input: BoxInput): Property {
        return when (header.type) {
            "ispe" -> parseIspe(input)
            "av1C" -> parseAv1C(input)
            "colr" -> parseColr(input)
            "auxC" -> parseAuxC(input)
            "pixi" -> parsePixi(input)
            "pasp" -> parsePasp(input)
            "clap" -> parseClap(input)
            "irot" -> parseIrot(input)
            "imir" -> parseImir(input)
            "a1op" -> parseA1op(input)
            "lsel" -> parseLsel(input)
            else -> parseOpaqueProperty(header, input)
        }
    }

    /** Parses an opaque or currently unsupported item property.
     * 
     * @param header the property box header
     * @param input the property payload input
     * @return the parsed opaque property
     * @throws AvifDecodeException if a UUID property is missing its user type
     */
    private fun parseOpaqueProperty(header: BoxHeader, input: BoxInput): OpaqueProperty {
        reserveMetadataBytes(
            input.remaining().toLong(),
            input.offset().toLong(),
            header.type + " item property"
        )
        var userType: ByteArray? = null
        if ("uuid" == header.type) {
            if (input.remaining() < 16) {
                throw parseFailed(
                    "uuid item property is missing its 16-byte user type",
                    input.offset().toLong()
                )
            }
            userType = input.readBytes(16)
        }
        return OpaqueProperty(header.type, userType, input.readBytes(input.remaining()))
    }

    /** Parses a `colr` property.
     * 
     * @param input the property payload input
     * @return the parsed property
     * @throws AvifDecodeException if the property is malformed
     */
    private fun parseColr(input: BoxInput): Property {
        val colourType = input.readFourCc()
        if ("prof" == colourType || "rICC" == colourType) {
            reserveMetadataBytes(
                input.remaining().toLong(),
                input.offset().toLong(),
                "ICC color profile"
            )
            val profile = input.readBytes(input.remaining())
            if (profile.size == 0) {
                throw parseFailed("ICC color profile payload is empty", input.offset().toLong())
            }
            return IccColorProfile(profile)
        }
        if ("nclx" != colourType) {
            reserveMetadataBytes(
                input.remaining().toLong() + 4L,
                (input.offset() - 4).toLong(),
                "opaque colr property"
            )
            val payload = ByteArrayOutput()
            payload.write(encodeIso88591(colourType))
            payload.write(input.readBytes(input.remaining()))
            return OpaqueProperty("colr", null, payload.toByteArray())
        }
        val colorPrimaries = input.readU16()
        val transferCharacteristics = input.readU16()
        val matrixCoefficients = input.readU16()
        val packedRange = input.readU8()
        if ((packedRange and 0x7F) != 0) {
            throw parseFailed("nclx reserved bits must be zero", (input.offset() - 1).toLong())
        }
        return ColorProperty(
            AvifColorInfo(
                colorPrimaries,
                transferCharacteristics,
                matrixCoefficients,
                (packedRange and 0x80) != 0
            )
        )
    }

    /** Parses an `auxC` property.
     * 
     * @param input the property payload input
     * @return the parsed property
     * @throws AvifDecodeException if the property is malformed
     */
    private fun parseAuxC(input: BoxInput): AuxiliaryType {
        readFullBox(input)
        reserveMetadataBytes(
            input.remaining().toLong(),
            input.offset().toLong(),
            "auxiliary type property"
        )
        val output = ByteArrayOutput()
        while (input.hasRemaining()) {
            val value = input.readU8()
            if (value == 0) {
                break
            }
            output.write(value)
        }
        return AuxiliaryType(output.toString(Charsets.ISO_8859_1))
    }

    /** Creates an AVIF container from parsed sequence data. */
    private fun parseSequenceImage(): AvifContainer {
        val s = meta.moovState
        if (s.seqHeaderObu == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "AVIS container has no usable AV1 color track",
                null
            )
        }
        if (s.width <= 0 || s.height <= 0 || s.bitDepth <= 0 || s.chromaFormat == null) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "AVIS color track is missing required image configuration",
                null
            )
        }
        if (s.mediaTimescale <= 0) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "AVIS color track has no positive media timescale",
                null
            )
        }
        validateSequencePremultipliedAlphaReferences()
        val colorPayloads = sequencePayloads(s, "Color sequence")
        val alphaPayloads = sequenceAuxiliaryPayloads(
            meta.moovAlphaState,
            colorPayloads.sampleCount,
            "Alpha sequence"
        )
        val depthPayloads = sequenceAuxiliaryPayloads(
            meta.moovDepthState,
            colorPayloads.sampleCount,
            "Depth sequence"
        )
        val dur: Long = sequenceDuration(s, colorPayloads)
        val repetitionCount: Int = sequenceRepetitionCount(s)
        val info = AvifImageInfo(
            s.width,
            s.height,
            AvifBitDepth.Companion.fromBits(s.bitDepth),
            checkNotNull(s.chromaFormat)
        ).withSequenceInfo(
            AvifSequenceInfo(
                colorPayloads.sampleCount,
                s.mediaTimescale,
                dur,
                repetitionCount,
                colorPayloads.frameDeltas
            )
        )
            .withAuxiliaryImages(
                meta.moovAuxiliaryTypes.toTypedArray(),
                null
            )
            .withAlpha(alphaPayloads != null, sequenceAlphaPremultiplied())
            .withColorInfo(s.colr)
            .withIccProfile(s.iccProfile)
        return AvifContainer(
            info,
            colorPayloads.payloads,
            alphaPayloads,
            depthPayloads
        )
    }

    /** Returns whether the selected AVIS color track is premultiplied by the selected alpha track.
     * 
     * @return whether the selected AVIS sequence uses premultiplied alpha
     */
    private fun sequenceAlphaPremultiplied(): Boolean {
        val alphaTrack = meta.moovAlphaState
        return alphaTrack != null && alphaTrack.trackId != 0 && meta.moovState.premultipliedByTrackIds.contains(
            alphaTrack.trackId
        )
    }

    /** Extracts sample payloads and frame deltas from one AVIS track.
     * 
     * @param track the parsed track state
     * @param label the diagnostic label
     * @return extracted sample payloads
     * @throws AvifDecodeException if sample data is missing or truncated
     */
    private fun sequencePayloads(track: MoovState, label: String): SequencePayloads {
        if (track.sampleSizes.isEmpty()) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " has no samples",
                null
            )
        }
        if (track.chunkOffsets.isEmpty()) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " has no chunk offsets",
                null
            )
        }
        val sampleCount = track.sampleSizes.size
        reserveRetainedElements(
            sampleCount.toLong(),
            RETAINED_SEQUENCE_PAYLOAD_SIZE,
            -1L,
            label + " resolved sample payloads"
        )
        if (track.sampleDeltas.size != sampleCount) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " timing table does not cover all samples",
                null
            )
        }
        if (track.sampleToChunkEntries.isEmpty()) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " has no sample-to-chunk table",
                null
            )
        }
        val payloads: Array<AvifPayload> =
            arrayOfNullsCompat<AvifPayload>(sampleCount)
        val deltas = IntArray(sampleCount)

        var sampleIndex = 0
        var entryIndex = 0
        var chunkIndex = 1
        while (chunkIndex <= track.chunkOffsets.size && sampleIndex < sampleCount) {
            while (entryIndex + 1 < track.sampleToChunkEntries.size
                && chunkIndex >= track.sampleToChunkEntries.get(entryIndex + 1).firstChunk
            ) {
                entryIndex++
            }
            val entry = track.sampleToChunkEntries.get(entryIndex)
            val exclusiveSampleEnd = min(
                sampleCount.toLong(),
                sampleIndex.toLong() + entry.samplesPerChunk
            ).toInt()
            sampleIndex = collectSequentialChunkSamples(
                track,
                label,
                payloads,
                deltas,
                sampleIndex,
                chunkIndex - 1,
                exclusiveSampleEnd
            )
            chunkIndex++
        }
        if (sampleIndex != sampleCount) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                label + " sample-to-chunk table does not cover all samples",
                null
            )
        }
        return SequencePayloads(payloads, deltas, sampleCount)
    }

    /** Collects sequential sample descriptors from one chunk.
     * 
     * @param track the parsed track state
     * @param label the diagnostic label
     * @param payloads the destination sample payloads
     * @param deltas the destination frame deltas
     * @param firstSampleIndex the first sample index to copy
     * @param chunkIndex the zero-based chunk index
     * @param exclusiveSampleEnd the exclusive sample index end
     * @return the next uncopied sample index
     * @throws AvifDecodeException if the chunk sample data is outside the source
     */
    private fun collectSequentialChunkSamples(
        track: MoovState,
        label: String,
        payloads: Array<AvifPayload>,
        deltas: IntArray,
        firstSampleIndex: Int,
        chunkIndex: Int,
        exclusiveSampleEnd: Int
    ): Int {
        var offset = track.chunkOffsets.get(chunkIndex).toLong()
        var sampleIndex = firstSampleIndex
        while (sampleIndex < exclusiveSampleEnd) {
            val size = track.sampleSizes.get(sampleIndex)
            payloads[sampleIndex] = sequenceSample(label, sampleIndex, offset, size)
            deltas[sampleIndex] = track.sampleDeltas.get(sampleIndex)
            offset += size.toLong()
            sampleIndex++
        }
        return sampleIndex
    }

    /** Describes one AVIS sample payload without copying its contents.
     * 
     * @param label the diagnostic label
     * @param sampleIndex the zero-based sample index
     * @param offset the absolute payload offset
     * @param size the sample size in bytes
     * @return the sample payload descriptor
     * @throws AvifDecodeException if the sample is outside the source
     */
    private fun sequenceSample(
        label: String,
        sampleIndex: Int,
        offset: Long,
        size: Int
    ): AvifPayload {
        if (offset < 0 || size < 0 || offset > source.limit() || size > source.limit() - offset) {
            throw AvifDecodeException(
                if (source.isSeekable()) AvifErrorCode.TRUNCATED_DATA else AvifErrorCode.INPUT_TOO_LARGE,
                if (source.isSeekable())
                    label + " sample outside source: $sampleIndex"
                else
                    label + " sample exceeds the configured input-size limit: $sampleIndex",
                offset
            )
        }
        return AvifPayload.Companion.ofRanges(source, longArrayOf(offset), intArrayOf(size))
    }

    /** Extracts auxiliary sample payloads and validates the sample count.
     * 
     * @param track the parsed auxiliary track state, or `null`
     * @param colorSampleCount the color track sample count
     * @param label the diagnostic label
     * @return extracted auxiliary payloads, or `null`
     * @throws AvifDecodeException if the auxiliary sample table is malformed
     */
    private fun sequenceAuxiliaryPayloads(
        track: MoovState?,
        colorSampleCount: Int,
        label: String
    ): Array<AvifPayload>? {
        if (track == null) {
            return null
        }
        val payloads = sequencePayloads(track, label)
        if (payloads.sampleCount != colorSampleCount) {
            throw unsupported(
                (label + " sample count does not match color sequence: " + payloads.sampleCount
                        + " != $colorSampleCount"),
                null
            )
        }
        return payloads.payloads
    }

    /** Parses a `moov` box for AVIS image sequences.
     * 
     * Navigates to supported color and auxiliary-image tracks and extracts frame metadata.
     * 
     * @param input the moov box payload
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseMoov(input: BoxInput) {
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            if ("trak" == child.type) {
                reserveMetadataBytes(
                    RETAINED_MOOV_STATE_SIZE,
                    child.offset.toLong(),
                    "AVIS track state"
                )
                val parsedTrack = MoovState()
                val trakPayload = input.slice(child.payloadOffset, child.payloadSize)
                parseMoovTrack(trakPayload, parsedTrack)
                finalizeMoovTrack(parsedTrack)
                inferLegacyMoovAuxiliaryType(parsedTrack)
                if (isMoovImageTrack(parsedTrack)) {
                    if (parsedTrack.auxiliaryType != null) {
                        meta.moovAuxiliaryCandidates.add(parsedTrack)
                    } else if (parsedTrack.seqHeaderObu != null
                        && (meta.moovState.seqHeaderObu == null
                                || moovColorTrackPreference(parsedTrack) > moovColorTrackPreference(
                            meta.moovState
                        ))
                    ) {
                        meta.moovState = parsedTrack
                    }
                }
            }
            input.skipBoxPayload(child)
        }
        resolveMoovAuxiliaryTracks()
    }

    /** Resolves AVIS auxiliary tracks against the selected color track. */
    private fun resolveMoovAuxiliaryTracks() {
        meta.moovAuxiliaryTypes.clear()
        meta.moovAlphaState = null
        meta.moovDepthState = null
        for (candidate in meta.moovAuxiliaryCandidates) {
            if (candidate.seqHeaderObu == null || !auxiliaryTrackMatchesColor(
                    candidate,
                    meta.moovState
                )
            ) {
                continue
            }
            if (AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE == candidate.auxiliaryType) {
                validateMoovAlphaTrackGeometry(candidate, meta.moovState)
            }
            if (!meta.moovAuxiliaryTypes.contains(candidate.auxiliaryType)) {
                meta.moovAuxiliaryTypes.add(candidate.auxiliaryType!!)
            }
            if (AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE == candidate.auxiliaryType && meta.moovAlphaState == null) {
                meta.moovAlphaState = candidate
            }
            if (AvifAuxiliaryImageInfo.Companion.DEPTH_TYPE == candidate.auxiliaryType && meta.moovDepthState == null) {
                meta.moovDepthState = candidate
            }
        }
    }

    /** Validates `prem` references from the selected AVIS color track.
     * 
     * @throws AvifDecodeException if the color track's premultiplied-alpha references are malformed
     */
    private fun validateSequencePremultipliedAlphaReferences() {
        val premultipliedByTrackIds = meta.moovState.premultipliedByTrackIds
        if (premultipliedByTrackIds.isEmpty()) {
            return
        }
        val alphaTrack = meta.moovAlphaState
        if (alphaTrack == null || !premultipliedByTrackIds.contains(alphaTrack.trackId)) {
            throw parseFailed("AVIS prem track references do not target the selected alpha auxiliary track")
        }
    }

    /** Parses a `trak` box and extracts video track metadata. */
    private fun parseMoovTrack(input: BoxInput, track: MoovState) {
        var trackHeaderSeen = false
        var mediaSeen = false
        var edtsSeen = false
        var trefSeen = false
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            val payload = input.slice(child.payloadOffset, child.payloadSize)
            when (child.type) {
                "tkhd" -> {
                    if (trackHeaderSeen) {
                        throw parseFailed(
                            "Box[trak] contains a duplicate unique box of type 'tkhd'",
                            child.offset.toLong()
                        )
                    }
                    trackHeaderSeen = true
                    parseMoovTkhd(payload, track)
                }

                "tref" -> {
                    if (trefSeen) {
                        throw parseFailed(
                            "Box[trak] contains a duplicate unique box of type 'tref'",
                            child.offset.toLong()
                        )
                    }
                    trefSeen = true
                    parseMoovTref(payload, track)
                }

                "edts" -> {
                    if (edtsSeen) {
                        throw parseFailed(
                            "Box[trak] contains a duplicate unique box of type 'edts'",
                            child.offset.toLong()
                        )
                    }
                    edtsSeen = true
                    parseMoovEdts(payload, track)
                }

                "mdia" -> {
                    if (mediaSeen) {
                        throw parseFailed(
                            "Box[trak] contains a duplicate unique box of type 'mdia'",
                            child.offset.toLong()
                        )
                    }
                    mediaSeen = true
                    parseMoovMdia(payload, track)
                }

                else -> {}
            }
            input.skipBoxPayload(child)
        }
    }

    /** Parses a `tref` box for AVIS track references.
     * 
     * @param input the tref box payload
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseMoovTref(input: BoxInput, track: MoovState) {
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            val payload = input.slice(child.payloadOffset, child.payloadSize)
            when (child.type) {
                "auxl" -> parseMoovTrackReference(payload, track.auxiliaryForTrackIds, "auxl")
                "prem" -> parseMoovTrackReference(payload, track.premultipliedByTrackIds, "prem")
                else -> {}
            }
            input.skipBoxPayload(child)
        }
    }

    /** Parses one track-reference type box.
     * 
     * @param input the reference box payload
     * @param target the destination track-id list
     * @param type the reference box type
     * @throws AvifDecodeException if the reference payload is malformed
     */
    private fun parseMoovTrackReference(
        input: BoxInput,
        target: MutableList<Int>,
        type: String
    ) {
        if (input.remaining() == 0 || (input.remaining() and 3) != 0) {
            throw parseFailed(
                "Box[tref]/$type must contain one or more 32-bit track IDs",
                input.offset().toLong()
            )
        }
        reserveRetainedElements(
            (input.remaining() / 4).toLong(),
            RETAINED_SAMPLE_INTEGER_SIZE,
            input.offset().toLong(),
            "AVIS $type track references"
        )
        while (input.hasRemaining()) {
            val trackId: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            if (trackId <= 0) {
                throw parseFailed(
                    "Box[tref]/$type track_ID must be positive",
                    (input.offset() - 4).toLong()
                )
            }
            target.add(trackId)
        }
    }

    /** Parses a `mdia` box to reach the media information and sample table. */
    private fun parseMoovMdia(input: BoxInput, track: MoovState) {
        // Resolve the handler before interpreting sample-table restrictions. BMFF does not make
        // child-box order significant, and non-image tracks may use otherwise unsupported tables.
        val handlerScan = input.slice(input.offset(), input.remaining())
        var hdlrSeen = false
        while (handlerScan.hasRemaining()) {
            val child = handlerScan.readBoxHeader()
            if ("hdlr" == child.type) {
                if (hdlrSeen) {
                    throw parseFailed(
                        "Box[mdia] contains a duplicate unique box of type 'hdlr'",
                        child.offset.toLong()
                    )
                }
                hdlrSeen = true
                parseMoovHandler(handlerScan.slice(child.payloadOffset, child.payloadSize), track)
            }
            handlerScan.skipBoxPayload(child)
        }
        var mediaHeaderSeen = false
        var mediaInfoSeen = false
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            val payload = input.slice(child.payloadOffset, child.payloadSize)
            when (child.type) {
                "mdhd" -> {
                    if (mediaHeaderSeen) {
                        throw parseFailed(
                            "Box[mdia] contains a duplicate unique box of type 'mdhd'",
                            child.offset.toLong()
                        )
                    }
                    mediaHeaderSeen = true
                    parseMoovMdhd(payload, track)
                }

                "hdlr" -> {}
                "minf" -> {
                    if (mediaInfoSeen) {
                        throw parseFailed(
                            "Box[mdia] contains a duplicate unique box of type 'minf'",
                            child.offset.toLong()
                        )
                    }
                    mediaInfoSeen = true
                    parseMoovMinf(payload, track)
                }

                else -> {}
            }
            input.skipBoxPayload(child)
        }
    }

    /** Parses a `minf` box to reach the sample table. */
    private fun parseMoovMinf(input: BoxInput, track: MoovState) {
        var sampleTableSeen = false
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            if ("stbl" == child.type) {
                if (sampleTableSeen) {
                    throw parseFailed(
                        "Box[minf] contains a duplicate unique box of type 'stbl'",
                        child.offset.toLong()
                    )
                }
                sampleTableSeen = true
                val stblPayload = input.slice(child.payloadOffset, child.payloadSize)
                parseMoovStbl(stblPayload, track)
            }
            input.skipBoxPayload(child)
        }
    }

    /** Parses a `stbl` (sample table) box and extracts all available sample metadata. */
    private fun parseMoovStbl(input: BoxInput, track: MoovState) {
        val seenTables: MutableSet<String> = HashSet<String>()
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            val payload = input.slice(child.payloadOffset, child.payloadSize)
            when (child.type) {
                "stsd" -> {
                    unique(seenTables, child.type, child.offset)
                    parseMoovStsd(payload, track)
                }

                "stts" -> {
                    unique(seenTables, child.type, child.offset)
                    parseMoovStts(payload, track)
                }

                "stco", "co64" -> {
                    if (seenTables.contains("stco") || seenTables.contains("co64")) {
                        throw parseFailed(
                            "AVIS sample table contains multiple chunk-offset tables",
                            child.offset.toLong()
                        )
                    }
                    seenTables.add(child.type)
                    if ("stco" == child.type) {
                        parseMoovStco(payload, track)
                    } else {
                        parseMoovCo64(payload, track)
                    }
                }

                "stsc" -> {
                    unique(seenTables, child.type, child.offset)
                    parseMoovStsc(payload, track)
                }

                "stsz" -> {
                    unique(seenTables, child.type, child.offset)
                    parseMoovStsz(payload, track)
                }

                "stss" -> {
                    unique(seenTables, child.type, child.offset)
                    parseMoovStss(payload, track)
                }

                else -> {}
            }
            input.skipBoxPayload(child)
        }
    }

    /** Parses `stsd` and extracts the av01 sample entry with av1C config. */
    private fun parseMoovStsd(input: BoxInput, track: MoovState) {
        readFullBox(input)
        val entryCount: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        enforceSequenceTableEntryCount(entryCount, "stsd", input.offset() - 4)
        track.sampleDescriptionCount = entryCount
        if (entryCount != 1 && isPotentialMoovImageTrack(track)) {
            throw parseFailed(
                "AVIS stsd must contain exactly one sample description",
                (input.offset() - 4).toLong()
            )
        }
        for (entryIndex in 0..<entryCount) {
            val entry = input.readBoxHeader()
            if ("av01" == entry.type) {
                track.containsAv1SampleDescription = true
                if (entryCount == 1) {
                    val av01i = input.slice(entry.payloadOffset, entry.payloadSize)
                    parseMoovAv01Entry(av01i, track)
                }
            }
            input.skipBoxPayload(entry)
        }
        if (input.hasRemaining()) {
            throw parseFailed(
                "AVIS stsd contains trailing data after its sample descriptions",
                input.offset().toLong()
            )
        }
    }

    /** Extracts av1C and colr from an av01 sample entry. */
    private fun parseMoovAv01Entry(input: BoxInput, track: MoovState) {
        input.skip(24)
        val w = input.readU16()
        val h = input.readU16()
        if (w > 0 && track.width == 0) track.width = w
        if (h > 0 && track.height == 0) track.height = h
        input.skip(50)
        var av1ConfigSeen = false
        var auxiliaryTypeSeen = false
        while (input.hasRemaining()) {
            val child = input.readBoxHeader()
            val payload = input.slice(child.payloadOffset, child.payloadSize)
            when (child.type) {
                "av1C" -> {
                    if (av1ConfigSeen) {
                        throw parseFailed(
                            "AVIS av01 entry contains multiple av1C boxes",
                            child.offset.toLong()
                        )
                    }
                    av1ConfigSeen = true
                    val c: Av1Config = parseAv1C(payload)
                    track.av1Config = c
                    track.bitDepth = c.bitDepth()
                    track.chromaFormat = c.chromaFormat()
                }

                "colr" -> {
                    val p = parseColr(payload)
                    if (p is ColorProperty) track.colr = p.colorInfo
                    if (p is IccColorProfile) track.iccProfile = p.profile
                }

                "auxi" -> {
                    if (auxiliaryTypeSeen) {
                        throw parseFailed(
                            "AVIS av01 entry contains multiple auxi boxes",
                            child.offset.toLong()
                        )
                    }
                    auxiliaryTypeSeen = true
                    track.auxiliaryType = parseMoovAuxiliaryType(payload)
                }

                else -> {}
            }
            input.skipBoxPayload(child)
        }
    }

    /** Parses an `auxi` sample-entry box for an AVIS auxiliary track.
     * 
     * @param input the box payload input
     * @return the auxiliary image type string
     * @throws AvifDecodeException if the box is malformed or unsupported
     */
    private fun parseMoovAuxiliaryType(input: BoxInput): String {
        val fullBox: FullBox = readFullBox(input)
        if (fullBox.version != 0 || fullBox.flags != 0) {
            throw unsupported("Unsupported auxi version/flags", input.offset())
        }
        reserveMetadataBytes(
            input.remaining().toLong(),
            input.offset().toLong(),
            "AVIS auxiliary type"
        )
        return readNullTerminatedString(input)
    }

    /** Parses `stts` for sample timing deltas. */
    private fun parseMoovStts(input: BoxInput, track: MoovState) {
        input.skip(4)
        val n: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        enforceSequenceTableEntryCount(n, "stts", input.offset() - 4)
        for (i in 0..<n) {
            val sc: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val sd: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            if (sc <= 0) {
                throw parseFailed(
                    "stts sample_count must be positive",
                    (input.offset() - 8).toLong()
                )
            }
            if (sd <= 0) {
                throw parseFailed(
                    "stts sample_delta must be positive",
                    (input.offset() - 4).toLong()
                )
            }
            enforceSequenceSampleCount(
                track.sampleDeltas.size,
                sc,
                "stts",
                input.offset() - 8
            )
            reserveRetainedElements(
                sc.toLong(),
                RETAINED_SAMPLE_INTEGER_SIZE,
                input.offset() - 8L,
                "stts expanded sample deltas"
            )
            for (j in 0..<sc) track.sampleDeltas.add(sd)
        }
    }

    /** Parses `stco` for chunk offsets. */
    private fun parseMoovStco(input: BoxInput, track: MoovState) {
        input.skip(4)
        val n: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        enforceSequenceTableEntryCount(n, "stco", input.offset() - 4)
        reserveRetainedElements(
            n.toLong(),
            RETAINED_SAMPLE_INTEGER_SIZE,
            input.offset() - 4L,
            "stco chunk offsets"
        )
        for (i in 0..<n) track.chunkOffsets.add(
            checkedU32ToInt(
                input.readU32(),
                input.offset() - 4
            )
        )
    }

    /** Parses `co64` for 64-bit chunk offsets. */
    private fun parseMoovCo64(input: BoxInput, track: MoovState) {
        input.skip(4)
        val n: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        enforceSequenceTableEntryCount(n, "co64", input.offset() - 4)
        reserveRetainedElements(
            n.toLong(),
            RETAINED_SAMPLE_INTEGER_SIZE,
            input.offset() - 4L,
            "co64 chunk offsets"
        )
        for (i in 0..<n) track.chunkOffsets.add(
            checkedU64ToInt(
                input.readU64(),
                input.offset() - 8
            )
        )
    }

    /** Parses `stsc` for sample-to-chunk layout. */
    private fun parseMoovStsc(input: BoxInput, track: MoovState) {
        input.skip(4)
        val n: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        enforceSequenceTableEntryCount(n, "stsc", input.offset() - 4)
        reserveRetainedElements(
            n.toLong(),
            RETAINED_SAMPLE_TO_CHUNK_SIZE,
            input.offset() - 4L,
            "stsc entries"
        )
        for (i in 0..<n) {
            val firstChunk: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val samplesPerChunk: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val sampleDescriptionIndex: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            if (firstChunk <= 0) {
                throw parseFailed(
                    "stsc first_chunk must be positive",
                    (input.offset() - 12).toLong()
                )
            }
            if (i == 0 && firstChunk != 1) {
                throw parseFailed(
                    "stsc first entry must start at chunk 1",
                    (input.offset() - 12).toLong()
                )
            }
            if (samplesPerChunk <= 0) {
                throw parseFailed(
                    "stsc samples_per_chunk must be positive",
                    (input.offset() - 8).toLong()
                )
            }
            track.hasNonPrimarySampleDescriptionReference =
                track.hasNonPrimarySampleDescriptionReference or (sampleDescriptionIndex != 1)
            if (!track.sampleToChunkEntries.isEmpty()) {
                val previous = track.sampleToChunkEntries.get(
                    track.sampleToChunkEntries.size - 1
                )
                if (firstChunk <= previous.firstChunk) {
                    throw parseFailed(
                        "stsc first_chunk entries must be strictly increasing",
                        (input.offset() - 12).toLong()
                    )
                }
            }
            track.sampleToChunkEntries.add(SampleToChunkEntry(firstChunk, samplesPerChunk))
        }
    }

    /** Parses `stsz` for sample sizes. */
    private fun parseMoovStsz(input: BoxInput, track: MoovState) {
        input.skip(4)
        val ss: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        val sc: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        enforceSequenceSampleCount(
            track.sampleSizes.size,
            sc,
            "stsz",
            input.offset() - 4
        )
        reserveRetainedElements(
            sc.toLong(),
            RETAINED_SAMPLE_INTEGER_SIZE,
            input.offset() - 4L,
            "stsz sample sizes"
        )
        if (ss == 0) {
            for (i in 0..<sc) track.sampleSizes.add(
                checkedU32ToInt(
                    input.readU32(),
                    input.offset() - 4
                )
            )
        } else {
            for (i in 0..<sc) track.sampleSizes.add(ss)
        }
    }

    /** Parses an `ipma` box.
     * 
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseItemPropertyAssociation(input: BoxInput) {
        val fullBox: FullBox = readFullBox(input)
        if (fullBox.version > 1 || (fullBox.flags and 1.inv()) != 0) {
            throw unsupported("Unsupported ipma version/flags", input.offset())
        }
        val widePropertyIndex = (fullBox.flags and 1) != 0
        val entryCount: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
        for (entryIndex in 0..<entryCount) {
            val itemId = if (fullBox.version == 0) input.readU16() else checkedU32ToInt(
                input.readU32(),
                input.offset() - 4
            )
            val item = requireItem(itemId, input.offset().toLong())
            val associationCount = input.readU8()
            for (associationIndex in 0..<associationCount) {
                val rawAssociation = if (widePropertyIndex) input.readU16() else input.readU8()
                val essential = (rawAssociation and (if (widePropertyIndex) 0x8000 else 0x80)) != 0
                val propertyIndex = rawAssociation and (if (widePropertyIndex) 0x7FFF else 0x7F)
                if (propertyIndex == 0) {
                    if (essential) {
                        throw parseFailed(
                            "ipma property index zero cannot be essential",
                            input.offset().toLong()
                        )
                    }
                    continue
                }
                val zeroBasedIndex = propertyIndex - 1
                if (zeroBasedIndex >= meta.properties.size) {
                    throw parseFailed("ipma references a missing property", input.offset().toLong())
                }
                val property = meta.properties.get(zeroBasedIndex)
                if (property is OpaqueProperty && essential) {
                    item.hasUnsupportedEssentialProperty = true
                }
                if (!essential && (property is OperatingPoint || property is LayerSelector)) {
                    throw parseFailed(
                        ("Item $itemId associates "
                                + (if (property is OperatingPoint) "a1op" else "lsel")
                                + " as non-essential"),
                        input.offset().toLong()
                    )
                }
                val transformativePropertyType: String? = transformativePropertyType(property)
                if (!essential && transformativePropertyType != null) {
                    continue
                }
                reserveMetadataBytes(
                    RETAINED_REFERENCE_SIZE,
                    input.offset().toLong(),
                    "item property association"
                )
                item.properties.add(property)
            }
        }
    }

    /** Parses an `iref` box.
     * 
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseItemReference(input: BoxInput) {
        val fullBox: FullBox = readFullBox(input)
        if (fullBox.version > 1) {
            throw unsupported("Unsupported iref version: " + fullBox.version, input.offset())
        }
        while (input.hasRemaining()) {
            val reference = input.readBoxHeader()
            val payload = input.slice(reference.payloadOffset, reference.payloadSize)
            val fromId = if (fullBox.version == 0) payload.readU16() else checkedU32ToInt(
                payload.readU32(),
                payload.offset() - 4
            )
            val referenceCount = payload.readU16()
            val fromItem = requireItem(fromId, payload.offset().toLong())
            for (i in 0..<referenceCount) {
                val toId = if (fullBox.version == 0) payload.readU16() else checkedU32ToInt(
                    payload.readU32(),
                    payload.offset() - 4
                )
                val toItem = requireItem(toId, payload.offset().toLong())
                if ("auxl" == reference.type) {
                    fromItem.auxForId = toItem.id
                }
                if ("prem" == reference.type) {
                    fromItem.premultipliedById = toItem.id
                }
                if ("dimg" == reference.type) {
                    reserveMetadataBytes(
                        RETAINED_REFERENCE_SIZE,
                        payload.offset().toLong(),
                        "dimg item reference"
                    )
                    toItem.dimgForId = fromItem.id
                    fromItem.dimgCellIds.add(toItem.id)
                }
                if ("prog" == reference.type) {
                    reserveMetadataBytes(
                        RETAINED_REFERENCE_SIZE,
                        payload.offset().toLong(),
                        "prog item reference"
                    )
                    fromItem.progDeps.add(toItem.id)
                }
                if ("cdsc" == reference.type) {
                    fromItem.descForId = toItem.id
                }
            }
            input.skipBoxPayload(reference)
        }
    }

    /** Parses a `grpl` box.
     * 
     * @param input the box payload input
     * @throws AvifDecodeException if the box is malformed
     */
    private fun parseGroupsList(input: BoxInput) {
        while (input.hasRemaining()) {
            val group = input.readBoxHeader()
            val payload = input.slice(group.payloadOffset, group.payloadSize)
            readFullBox(payload)
            val groupId: Int = checkedU32ToInt(payload.readU32(), payload.offset() - 4)
            val entityCount: Int = checkedU32ToInt(payload.readU32(), payload.offset() - 4)
            reserveMetadataBytes(
                RETAINED_ENTITY_GROUP_SIZE,
                group.offset.toLong(),
                "entity group structure"
            )
            reserveRetainedElements(
                entityCount.toLong(),
                4.toLong(),
                payload.offset() - 4L,
                "entity group identifiers"
            )
            val entityIds = IntArray(entityCount)
            for (i in 0..<entityCount) {
                entityIds[i] = checkedU32ToInt(payload.readU32(), payload.offset() - 4)
            }
            meta.entityGroups.add(EntityGroup(group.type, groupId, entityIds))
            input.skipBoxPayload(group)
        }
    }

    /** Finds an auxiliary item for the supplied master image item id.
     * 
     * @param itemId the master image item id
     * @param auxiliaryType the requested auxiliary image type string
     * @return the auxiliary item, or `null`
     */
    private fun findAuxiliaryItem(itemId: Int, auxiliaryType: String): Item? {
        val masterItem = meta.item(itemId)
        for (item in meta.items.values) {
            if (item.auxForId == itemId || (masterItem != null && masterItem.auxForId == item.id)) {
                val aux: AuxiliaryType? =
                    item.firstProperty<AuxiliaryType>(AuxiliaryType::class)
                if (aux != null && auxiliaryType == aux.type) {
                    return item
                }
            }
        }
        return null
    }

    /** Returns whether one item declares premultiplied alpha semantics.
     * 
     * @param imageItem the color image item
     * @return whether the color item is premultiplied by that auxiliary item
     */
    private fun itemAlphaPremultiplied(imageItem: Item): Boolean {
        val auxiliaryItem =
            findAuxiliaryItem(imageItem.id, AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE)
        return auxiliaryItem != null && imageItem.premultipliedById == auxiliaryItem.id
    }

    /** Validates `prem` references from a still-image or grid item.
     * 
     * @param imageItem the color image or grid item
     * @param alphaSource the resolved alpha image source, or `null`
     * @param label the diagnostic image label
     * @throws AvifDecodeException if the item's premultiplied-alpha reference is malformed
     */
    private fun validateItemPremultipliedAlpha(
        imageItem: Item,
        alphaSource: AvifImageSource?,
        label: String
    ) {
        if (imageItem.premultipliedById == 0) {
            return
        }
        val referencedItem = meta.item(imageItem.premultipliedById)
        val auxiliaryType: AuxiliaryType? = if (referencedItem != null)
            referencedItem.firstProperty<AuxiliaryType>(AuxiliaryType::class)
        else
            null
        if (alphaSource == null || auxiliaryType == null || (AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE != auxiliaryType.type)) {
            throw parseFailed(
                (label + " prem reference does not target a usable alpha auxiliary image: "
                        + imageItem.premultipliedById)
            )
        }
    }

    /** Returns auxiliary image descriptors associated with one master image item.
     * 
     * @param itemId the master image item id
     * @param fallbackWidth the master image width used by legacy alpha items without `ispe`
     * @param fallbackHeight the master image height used by legacy alpha items without `ispe`
     * @return auxiliary image descriptors
     */
    private fun auxiliaryImages(
        itemId: Int,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): Array<AvifAuxiliaryImageInfo> {
        val masterItem = meta.item(itemId)
        val images = ArrayList<AvifAuxiliaryImageInfo>()
        for (item in meta.items.values) {
            if (item.auxForId == itemId || (masterItem != null && masterItem.auxForId == item.id)) {
                val aux: AuxiliaryType? =
                    item.firstProperty<AuxiliaryType>(AuxiliaryType::class)
                if (aux != null) {
                    images.add(auxiliaryImageInfo(item, aux, fallbackWidth, fallbackHeight))
                }
            }
        }
        return images.toTypedArray()
    }

    /** Returns the gain-map descriptor and decodable payloads associated with one base image item.
     * 
     * @param baseItemId the base image item id
     * @return the gain-map descriptor and payloads, or empty data
     * @throws AvifDecodeException if the `tmap` metadata payload is malformed
     */
    private fun gainMapPayloads(baseItemId: Int): GainMapPayloads {
        if (!tmapBrandSeen) {
            return GainMapPayloads.Companion.empty()
        }
        for (item in meta.items.values) {
            if ("tmap" != item.type || item.hasUnsupportedEssentialProperty) {
                continue
            }
            if (item.dimgCellIds.size != 2) {
                continue
            }
            val referencedBaseItemId = item.dimgCellIds.get(0)
            val gainMapItemId = item.dimgCellIds.get(1)
            if (referencedBaseItemId != baseItemId || gainMapItemId == baseItemId) {
                continue
            }
            if (!isPreferredAlternativeTo(item.id, baseItemId)) {
                continue
            }
            val gainMapItem = meta.item(gainMapItemId)
            if (gainMapItem == null || gainMapItem.hasUnsupportedEssentialProperty) {
                return GainMapPayloads.Companion.empty()
            }
            validateOperatingPointStructure(gainMapItem, "Gain-map image")

            val toneMapMetadata = toneMapMetadata(item)
            if (toneMapMetadata == null) {
                return GainMapPayloads.Companion.empty()
            }
            val toneMappedDimensions: ItemDimensions = itemDimensions(item)

            if ("grid" == gainMapItem.type) {
                val grid = parseGridPayloads(gainMapItem)
                val info: AvifGainMapInfo = Companion.gainMapInfo(
                    item,
                    baseItemId,
                    gainMapItem,
                    toneMappedDimensions,
                    ItemDimensions(grid.outputWidth, grid.outputHeight),
                    AvifBitDepth.Companion.fromBits(grid.representativeAv1C.bitDepth()),
                    grid.representativeAv1C.chromaFormat(),
                    toneMapMetadata
                )
                return GainMapPayloads.Companion.grid(info, grid)
            }

            val gainMapDimensions: ItemDimensions = itemDimensions(gainMapItem)
            val gainMapAv1Config = if ("av01" == gainMapItem.type)
                itemAv1Config(gainMapItem)
            else
                null
            val gainMapBitDepth: AvifBitDepth? = if (gainMapAv1Config != null)
                AvifBitDepth.Companion.fromBits(gainMapAv1Config.bitDepth())
            else
                null
            val gainMapChromaFormat = if (gainMapAv1Config != null)
                gainMapAv1Config.chromaFormat()
            else
                null
            val info: AvifGainMapInfo = gainMapInfo(
                item,
                baseItemId,
                gainMapItem,
                toneMappedDimensions,
                gainMapDimensions,
                gainMapBitDepth,
                gainMapChromaFormat,
                toneMapMetadata
            )
            if ("av01" == gainMapItem.type) {
                return GainMapPayloads.Companion.item(
                    info,
                    AvifImageSource.Companion.item(
                        itemPayload(gainMapItem),
                        operatingPoint(gainMapItem),
                        selectedSpatialLayer(gainMapItem),
                        gainMapDimensions.width,
                        gainMapDimensions.height
                    )
                )
            }
            return GainMapPayloads.Companion.descriptorOnly(info)
        }
        return GainMapPayloads.Companion.empty()
    }

    /** Parses the tone-map metadata payload from one `tmap` item.
     * 
     * @param item the `tmap` item
     * @return parsed tone-map metadata, or `null` when the metadata version is unsupported or malformed
     * @throws AvifDecodeException if the item payload header is malformed
     */
    private fun toneMapMetadata(item: Item): ToneMapMetadata? {
        val payload = mergeItemExtents(item)
        if (payload.size < 1) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "tmap item payload is too short: " + item.id,
                null
            )
        }
        val input = BoxInput(payload)
        val version = input.readU8()
        if (version != 0) {
            return null
        }
        if (input.remaining() < 4) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "tmap item payload is too short: " + item.id,
                null
            )
        }
        val minimumVersion = input.readU16()
        if (minimumVersion > SUPPORTED_GAIN_MAP_METADATA_VERSION) {
            return null
        }
        val writerVersion = input.readU16()
        if (writerVersion < minimumVersion) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "tmap writer_version is less than minimum_version: $writerVersion < $minimumVersion",
                null
            )
        }

        val metadata: AvifGainMapMetadata?
        try {
            metadata = parseGainMapMetadata(input)
        } catch (exception: AvifDecodeException) {
            return null
        }
        if (writerVersion <= SUPPORTED_GAIN_MAP_METADATA_VERSION && input.hasRemaining()) {
            return null
        }
        return ToneMapMetadata(version, minimumVersion, writerVersion, metadata)
    }

    /** Returns the AV1 configuration for an image item or a representative grid cell.
     * 
     * @param item the item to inspect
     * @return the AV1 configuration, or `null`
     */
    private fun itemAv1Config(item: Item): Av1Config? {
        var av1Config: Av1Config? = item.firstProperty<Av1Config>(Av1Config::class)
        if (av1Config != null) {
            return av1Config
        }
        if ("grid" != item.type) {
            return null
        }
        for (cellId in item.dimgCellIds) {
            val cell = meta.item(cellId)
            if (cell != null) {
                av1Config = cell.firstProperty<Av1Config>(Av1Config::class)
                if (av1Config != null) {
                    return av1Config
                }
            }
        }
        return null
    }

    /** Returns whether one entity id is a preferred alternative to another.
     * 
     * @param preferredId the entity id that must appear first
     * @param alternativeId the entity id that must appear after `preferredId`
     * @return whether `preferredId` is a preferred alternative to `alternativeId`
     */
    private fun isPreferredAlternativeTo(preferredId: Int, alternativeId: Int): Boolean {
        for (group in meta.entityGroups) {
            if ("altr" != group.type) {
                continue
            }
            var preferredSeen = false
            for (entityId in group.entityIds) {
                if (entityId == preferredId) {
                    preferredSeen = true
                } else if (entityId == alternativeId) {
                    return preferredSeen
                }
            }
        }
        return false
    }

    /** Describes item extents as one logical payload without copying their contents.
     * 
     * @param item the item whose extents should be described
     * @return the logical item payload
     * @throws AvifDecodeException if the item data is malformed, truncated, or too large
     */
    private fun itemPayload(item: Item): AvifPayload {
        if (item.extents.isEmpty()) {
            throw AvifDecodeException(
                AvifErrorCode.TRUNCATED_DATA,
                "Item has no extents: " + item.id,
                null
            )
        }
        var storageOffset = 0L
        var storageLength = source.limit()
        if (item.idatStored) {
            if (meta.idatOffset < 0) {
                throw AvifDecodeException(
                    AvifErrorCode.TRUNCATED_DATA,
                    "Item is stored in missing idat: " + item.id,
                    null
                )
            }
            storageOffset = meta.idatOffset
            storageLength = meta.idatLength.toLong()
        }

        val offsets = LongArray(item.extents.size)
        val lengths = IntArray(item.extents.size)
        var totalLength = 0L
        for (i in item.extents.indices) {
            val extent = item.extents.get(i)
            if (extent.offset < 0 || extent.offset > storageLength || extent.length > storageLength - extent.offset) {
                val exceedsProgressiveLimit = !source.isSeekable() && !item.idatStored
                throw AvifDecodeException(
                    if (exceedsProgressiveLimit) AvifErrorCode.INPUT_TOO_LARGE else AvifErrorCode.TRUNCATED_DATA,
                    if (exceedsProgressiveLimit)
                        "Item extent exceeds the configured input-size limit: " + item.id
                    else
                        "Item extent is outside available data: " + item.id,
                    extent.offset
                )
            }
            offsets[i] = storageOffset + extent.offset
            lengths[i] = extent.length
            totalLength += extent.length.toLong()
            if (totalLength > Int.MAX_VALUE - 8L) {
                throw unsupported("Item payload exceeds supported size: " + item.id, null)
            }
        }
        return AvifPayload.Companion.ofRanges(source, offsets, lengths)
    }

    /** Merges item extents into one contiguous payload.
     * 
     * @param item the item whose extents should be merged
     * @return one contiguous item payload
     * @throws AvifDecodeException if the item data is malformed or truncated
     */
    private fun mergeItemExtents(item: Item): ByteArray {
        val payload = itemPayload(item)
        reserveMetadataBytes(payload.length().toLong(), -1L, "item " + item.id + " payload")
        try {
            return payload.readBytes()
        } catch (exception: AvifDecodeException) {
            throw exception
        } catch (exception: AvifIoException) {
            throw AvifDecodeException(
                AvifErrorCode.BMFF_PARSE_FAILED,
                "Cannot read item payload " + item.id + ": " + exception.message,
                null,
                exception
            )
        }
    }

    /** Returns an existing item or creates one within the retained-metadata budget.
     * 
     * @param itemId the item identifier
     * @param offset the associated source offset, or `-1` when unavailable
     * @return the existing or newly created item
     * @throws AvifDecodeException if creating the item would exceed the metadata limit
     */
    private fun requireItem(itemId: Int, offset: Long): Item {
        val existing = meta.item(itemId)
        if (existing != null) {
            return existing
        }
        reserveMetadataBytes(RETAINED_ITEM_SIZE, offset, "item structure")
        val created = Item(itemId)
        meta.items.put(itemId, created)
        return created
    }

    /** Reserves a repeated retained structure within the metadata budget.
     * 
     * @param elementCount the number of retained elements
     * @param estimatedElementSize the estimated retained size of each element
     * @param offset the associated source offset, or `-1` when unavailable
     * @param description the structure description used in diagnostics
     * @throws AvifDecodeException if retaining the elements would exceed the metadata limit
     */
    private fun reserveRetainedElements(
        elementCount: Long,
        estimatedElementSize: Long,
        offset: Long,
        description: String
    ) {
        require(!(elementCount < 0 || estimatedElementSize < 0)) { "negative retained-metadata estimate" }
        val byteCount: Long
        try {
            byteCount = multiplyExact(elementCount, estimatedElementSize)
        } catch (exception: ArithmeticException) {
            throw AvifDecodeException(
                AvifErrorCode.INPUT_TOO_LARGE,
                "AVIF retained metadata size overflows the supported range: $description",
                if (offset >= 0) offset else null,
                exception
            )
        }
        reserveMetadataBytes(byteCount, offset, description)
    }

    /** Reserves bytes from the cumulative retained non-image metadata budget.
     * 
     * @param byteCount the actual or estimated number of bytes about to be retained
     * @param offset the associated source offset, or `-1` when the metadata spans extents
     * @param description the metadata description used in diagnostics
     * @throws AvifDecodeException if the configured or implementation metadata limit would be exceeded
     */
    private fun reserveMetadataBytes(byteCount: Long, offset: Long, description: String) {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        val effectiveLimit = if (metadataSizeLimit == 0L)
            MAX_RETAINED_METADATA_SIZE
        else min(metadataSizeLimit, MAX_RETAINED_METADATA_SIZE)
        if (byteCount > effectiveLimit - retainedMetadataSize) {
            val configuredLimitApplies =
                metadataSizeLimit != 0L && metadataSizeLimit <= MAX_RETAINED_METADATA_SIZE
            throw AvifDecodeException(
                AvifErrorCode.INPUT_TOO_LARGE,
                ("AVIF retained metadata exceeds "
                        + (if (configuredLimitApplies) "configured" else "implementation")
                        + " size limit: $description ($effectiveLimit bytes)"),
                if (offset >= 0) offset else null
            )
        }
        retainedMetadataSize += byteCount
    }

    /** Parsed item dimensions, or an unknown marker.
     * 
     * @param width the item width in pixels, or -1 when unknown
     * @param height the item height in pixels, or -1 when unknown
     */
    data class ItemDimensions(val width: Int, val height: Int) {
        companion object {
            /** Unknown item dimensions. */
            val UNKNOWN = ItemDimensions(-1, -1)
        }
    }

    /** Extracted AVIS sample payloads for one track.
     * 
     * @param payloads the AV1 OBU payloads for each sample in order
     * @param frameDeltas the frame duration deltas in media timescale units
     * @param sampleCount the number of samples
     */
    data class SequencePayloads(
        val payloads: Array<AvifPayload>,
        val frameDeltas: IntArray,
        val sampleCount: Int
    ) {
        /** Creates extracted AVIS sample payloads for one track. */
        init {
            requireNotNullCompat(
                payloads, "payloads"
            )
            requireNotNullCompat(
                frameDeltas, "frameDeltas"
            )
        }
    }

    /** One `stsc` sample-to-chunk entry.
     * 
     * @param firstChunk the one-based first chunk using this layout
     * @param samplesPerChunk the number of samples stored in each covered chunk
     */
    data class SampleToChunkEntry(val firstChunk: Int, val samplesPerChunk: Int)

    /** Parsed `tmap` metadata.
     * 
     * @param version the metadata version field
     * @param minimumVersion the minimum supported metadata version field
     * @param writerVersion the writer metadata version field
     * @param metadata the parsed gain-map metadata
     */
    data class ToneMapMetadata(
        val version: Int,
        val minimumVersion: Int,
        val writerVersion: Int,
        val metadata: AvifGainMapMetadata
    ) {
        /** Creates parsed `tmap` metadata. */
        init {
            requireNotNullCompat(metadata, "metadata")
        }
    }

    /** Parsed BMFF entity group.
     * 
     * @param type the grouping type
     * @param groupId the group id
     * @param entityIds the entity ids in group order
     */
    data class EntityGroup(
        val type: String,
        val groupId: Int,
        val entityIds: IntArray
    ) {
        /** Creates a parsed BMFF entity group. */
        init {
            requireNotNullCompat(type, "type")
            requireNotNullCompat(
                entityIds, "entityIds"
            )
        }
    }

    /** Mutable parser state for one root `meta` box. */
    class MetaState {
        /** The primary item id from `pitm`. */
        var primaryItemId = 0

        /** Parsed items keyed by item id. */
        val items: MutableMap<Int, Item> = HashMap<Int, Item>()

        /** Parsed item properties from `ipco`. */
        val properties: MutableList<Property> = ArrayList<Property>()

        /** Parsed entity groups from `grpl`. */
        val entityGroups: MutableList<EntityGroup> = ArrayList<EntityGroup>()

        /** Auxiliary image type strings parsed from AVIS auxiliary tracks. */
        val moovAuxiliaryTypes: MutableList<String> = ArrayList<String>()

        /** Parsed AVIS auxiliary track candidates. */
        val moovAuxiliaryCandidates: MutableList<MoovState> = ArrayList<MoovState>()

        /** The parsed AVIS alpha auxiliary track state, or `null`. */
        var moovAlphaState: MoovState? = null

        /** The parsed AVIS depth auxiliary track state, or `null`. */
        var moovDepthState: MoovState? = null

        /** The absolute offset of the optional `idat` payload, or `-1` when absent. */
        var idatOffset = -1L

        /** The optional `idat` payload length. */
        var idatLength = 0

        /** The parsed AVIS moov state. */
        var moovState = MoovState()

        /** Returns one item by id.
         * 
         * @param itemId the item id
         * @return the item, or `null`
         */
        fun item(itemId: Int): Item? {
            return items.get(itemId)
        }
    }

    /** Mutable parser state for AVIS moov parsing. */
    class MoovState {
        /** The parsed track ID from `tkhd`, or zero when absent. */
        var trackId = 0

        /** The parsed image sequence width. */
        var width = 0

        /** The parsed image sequence height. */
        var height = 0

        /** The parsed image sequence bit depth. */
        var bitDepth = 0

        /** The parsed image sequence chroma format, or `null`. */
        var chromaFormat: Av1ChromaFormat? = null

        /** The parsed image sequence color information, or `null`. */
        var colr: AvifColorInfo? = null

        /** The parsed image sequence ICC profile payload, or `null`. */
        var iccProfile: ByteArray? = null

        /** The parsed media handler type, or `null` when the track has no `hdlr` box. */
        var mediaHandlerType: String? = null

        /** The parsed image sequence media timescale. */
        var mediaTimescale = 0

        /** The parsed image sequence media duration. */
        var mediaDuration: Long = 0

        /** The parsed track duration from `tkhd`. */
        var trackDuration: Long = 0

        /** Whether an edit list was parsed for this track. */
        var editListSeen = false

        /** Whether the edit list signals repetition semantics. */
        var editListRepeating = false

        /** The edit-list segment duration for repeating tracks. */
        var editListSegmentDuration: Long = 0

        /** The parsed AV1 sequence header OBU, or `null` before an AV1 track is found. */
        var seqHeaderObu: ByteArray? = null

        /** The parsed AV1 sample-entry configuration, or `null` when absent. */
        var av1Config: Av1Config? = null

        /** The number of sample descriptions declared by `stsd`. */
        var sampleDescriptionCount = 0

        /** Whether `stsd` contains at least one AV1 sample description. */
        var containsAv1SampleDescription = false

        /** Whether `stsc` references a sample description other than the first. */
        var hasNonPrimarySampleDescriptionReference = false

        /** The greatest one-based sync sample number declared by `stss`. */
        var maximumSyncSampleNumber = 0

        /** The AVIS auxiliary track type, or `null` for the selected color track. */
        var auxiliaryType: String? = null

        /** The `tref/auxl` color track IDs referenced by this auxiliary track. */
        val auxiliaryForTrackIds: MutableList<Int> = ArrayList<Int>()

        /** The `tref/prem` alpha track IDs referenced by this color track. */
        val premultipliedByTrackIds: MutableList<Int> = ArrayList<Int>()

        /** The parsed sample timing deltas. */
        val sampleDeltas: MutableList<Int> = ArrayList<Int>()

        /** The parsed chunk offsets. */
        val chunkOffsets: MutableList<Int> = ArrayList<Int>()

        /** The parsed sample-to-chunk entries. */
        val sampleToChunkEntries: MutableList<SampleToChunkEntry> =
            ArrayList<SampleToChunkEntry>()

        /** The parsed sample sizes. */
        val sampleSizes: MutableList<Int> = ArrayList<Int>()
    }

    /** Parsed grid payloads and geometry.
     * 
     * @param rows the grid row count
     * @param columns the grid column count
     * @param outputWidth the reconstructed output width
     * @param outputHeight the reconstructed output height
     * @param representativeAv1C the representative AV1 configuration from the first grid cell
     * @param source the normalized grid image source
     */
    data class GridPayloads(
        val rows: Int,
        val columns: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val representativeAv1C: Av1Config,
        val source: AvifImageSource
    ) {
        /** Creates parsed grid payloads and geometry. */
        init {
            requireNotNullCompat(representativeAv1C, "representativeAv1C")
            requireNotNullCompat(source, "source")
        }
    }

    /** Parsed gain-map descriptor and decodable image payloads.
     * 
     * @param info the public gain-map descriptor, or `null`
     * @param source the normalized gain-map image source, or `null` when unavailable
     */
    data class GainMapPayloads(val info: AvifGainMapInfo?, val source: AvifImageSource?) {
        companion object {
            /** Creates empty gain-map payloads.
             * 
             * @return empty gain-map payloads
             */
            fun empty(): GainMapPayloads {
                return GainMapPayloads(null, null)
            }

            /** Creates descriptor-only gain-map payloads.
             * 
             * @param info the public gain-map descriptor
             * @return descriptor-only gain-map payloads
             */
            fun descriptorOnly(info: AvifGainMapInfo): GainMapPayloads {
                return GainMapPayloads(requireNotNullCompat(info, "info"), null)
            }

            /** Creates gain-map payloads from one standalone item.
             * 
             * @param info the public gain-map descriptor
             * @param source the standalone gain-map image source
             * @return gain-map payloads for the item
             */
            fun item(info: AvifGainMapInfo, source: AvifImageSource): GainMapPayloads {
                return GainMapPayloads(
                    requireNotNullCompat(info, "info"),
                    requireNotNullCompat(source, "source")
                )
            }

            /** Creates gain-map payloads from grid data.
             * 
             * @param info the public gain-map descriptor
             * @param grid the parsed gain-map grid
             * @return gain-map payloads for the grid
             */
            fun grid(info: AvifGainMapInfo, grid: GridPayloads): GainMapPayloads {
                return GainMapPayloads(
                    requireNotNullCompat(info, "info"),
                    requireNotNullCompat(grid, "grid").source
                )
            }
        }
    }

    /** Parsed image metadata payloads.
     * 
     * @param iccProfile the ICC profile payload, or `null`
     * @param exif the Exif metadata payload, or `null`
     * @param xmp the XMP metadata payload, or `null`
     */
    data class MetadataPayloads(
        val iccProfile: ByteArray?,
        val exif: ByteArray?,
        val xmp: ByteArray?
    )

    /** Mutable parser state for one item. */
    class Item(
        val id: Int
    ) {
        /** The item type. */
        var type = ""

        /** The item name from `infe`. */
        var name = ""

        /** The MIME content type for `mime` items, or an empty string. */
        var contentType = ""

        /** Whether the item extents are stored in `idat`. */
        var idatStored = false

        /** The item extents. */
        val extents: MutableList<Extent> = ArrayList<Extent>()

        /** The associated item properties. */
        val properties: MutableList<Property> = ArrayList<Property>()

        /** Whether the item has an unsupported essential property. */
        var hasUnsupportedEssentialProperty = false

        /** The master image item id for auxiliary items. */
        var auxForId = 0

        /** The auxiliary item id that premultiplies this color item, or 0. */
        var premultipliedById = 0

        /** The image item id described by this metadata item, or 0. */
        var descForId = 0

        /** The grid item id for dimg cell items, or 0. */
        var dimgForId = 0

        /** The dimg cell item ids for a grid item, in row-major order. */
        val dimgCellIds: MutableList<Int> = ArrayList<Int>()

        /** The progressive dependency item ids from `prog` references. */
        val progDeps: MutableList<Int> = ArrayList<Int>()

        /** Finds the first associated property assignable to one type.
         * 
         * @param type the property class
         * @param <T> the property type
         * @return the first matching property, or `null`
        </T> */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> firstProperty(type: kotlin.reflect.KClass<T>): T? {
            for (property in properties) {
                if (type.isInstance(property)) {
                    return property as T
                }
                if (type == AvifColorInfo::class && property is ColorProperty) {
                    return property.colorInfo as T
                }
            }
            return null
        }
    }

    /** One item extent.
     * 
     * @param offset the extent byte offset
     * @param length the extent byte length
     */
    data class Extent(val offset: Long, val length: Int)

    /** Display dimensions after applying AVIF item transforms.
     * 
     * @param width the transformed display width
     * @param height the transformed display height
     */
    data class DisplaySize(val width: Int, val height: Int)

    /** One full-box header.
     * 
     * @param version the full-box version
     * @param flags the full-box flags
     */
    data class FullBox(val version: Int, val flags: Int)

    /** Marker interface for parsed item properties. */
    interface Property

    /** Parsed `ispe` item property.
     * 
     * @param width the image width
     * @param height the image height
     */
    data class ImageSpatialExtents(val width: Int, val height: Int) : Property

    /** Parsed `av1C` item property.
     * 
     * @param seqProfile the AV1 sequence profile
     * @param seqLevelIdx0 the AV1 sequence level index
     * @param highBitDepth whether high bit depth is signaled
     * @param twelveBit whether twelve-bit depth is signaled
     * @param monochrome whether monochrome output is signaled
     * @param chromaSubsamplingX whether horizontal chroma subsampling is signaled
     * @param chromaSubsamplingY whether vertical chroma subsampling is signaled
     * @param chromaSamplePosition the chroma sample position
     */
    data class Av1Config(
        val seqProfile: Int,
        val seqLevelIdx0: Int,
        val highBitDepth: Boolean,
        val twelveBit: Boolean,
        val monochrome: Boolean,
        val chromaSubsamplingX: Boolean,
        val chromaSubsamplingY: Boolean,
        val chromaSamplePosition: Int
    ) : Property {
        /** Returns the decoded bit depth.
         * 
         * @return the decoded bit depth
         */
        fun bitDepth(): Int {
            return if (highBitDepth) (if (twelveBit) 12 else 10) else 8
        }

        /** Returns the AV1 chroma sampling layout.
         * 
         * @return the AV1 chroma sampling layout
         */
        fun chromaFormat(): Av1ChromaFormat {
            if (monochrome) {
                return Av1ChromaFormat.MONOCHROME
            }
            if (chromaSubsamplingX && chromaSubsamplingY) {
                return Av1ChromaFormat.YUV420
            }
            if (chromaSubsamplingX) {
                return Av1ChromaFormat.YUV422
            }
            return Av1ChromaFormat.YUV444
        }

        /** Constructs a reduced-still-picture AV1 SEQUENCE_HEADER OBU from this configuration.
         * 
         * @param width the image width
         * @param height the image height
         * @return the SEQUENCE_HEADER OBU bytes ready for decoding
         */
        fun seqHeaderObu(width: Int, height: Int): ByteArray {
            val payload = reducedStillSeqHdrPayload(width, height)
            val obu = ByteArrayOutput()
            obu.write((1 shl 3) or (1 shl 1))
            writeLeb128(obu, payload.size)
            obu.write(payload)
            return obu.toByteArray()
        }

        /** Builds the payload portion of a reduced still-picture SEQUENCE_HEADER. */
        fun reducedStillSeqHdrPayload(frameWidth: Int, frameHeight: Int): ByteArray {
            val mfw = frameWidth - 1
            val mfh = frameHeight - 1
            var fb = 32 - mfw.countLeadingZeroBits()
            if (fb < 1) fb = 1
            var fh = 32 - mfh.countLeadingZeroBits()
            if (fh < 1) fh = 1
            val w = SeqBitWriter()
            w.bits(0, 3)
            w.flag(true)
            w.flag(true)
            w.bits(0, 3)
            w.bits(0, 5)
            w.bits(fb.toLong(), 4)
            w.bits(fh.toLong(), 4)
            w.bits(mfw.toLong(), fb + 1)
            w.bits(mfh.toLong(), fh + 1)
            w.flag(false)
            w.flag(true)
            w.flag(true)
            w.flag(false)
            w.flag(true)
            w.flag(true)
            w.flag(false)
            if (monochrome) {
                w.flag(true)
                w.flag(false)
                w.flag(true)
                w.flag(true)
            } else {
                w.flag(false)
                w.flag(false)
                w.flag(false)
                w.bits(1, 2)
                w.flag(true)
                w.flag(true)
            }
            w.flag(false)
            w.trail()
            return w.toBytes()
        }
    }

    /** MSB-first bit writer for sequence-header construction. */
    class SeqBitWriter {
        val out = ByteArrayOutput()
        var cur = 0
        var cnt = 0

        fun flag(v: Boolean) {
            bit(if (v) 1 else 0)
        }

        fun bits(v: Long, w: Int) {
            for (b in w - 1 downTo 0) bit(((v ushr b) and 1L).toInt())
        }

        fun trail() {
            bit(1)
            while (cnt != 0) bit(0)
        }

        fun bit(b: Int) {
            cur = (cur shl 1) or (b and 1)
            if (++cnt == 8) {
                out.write(cur)
                cur = 0
                cnt = 0
            }
        }

        fun toBytes(): ByteArray {
            check(cnt <= 0) { "not byte aligned" }
            return out.toByteArray()
        }
    }

    /** Parsed `colr` item property carrying `nclx` color information.
     * 
     * @param colorInfo the parsed color information
     */
    data class ColorProperty(val colorInfo: AvifColorInfo) : Property {
        /** Creates a color property. */
        init {
            requireNotNullCompat(colorInfo, "colorInfo")
        }
    }

    /** Parsed `colr` item property carrying an ICC profile.
     * 
     * @param profile the ICC profile payload
     */
    data class IccColorProfile(val profile: ByteArray) : Property {
        /** Creates an ICC color profile property. */
        init {
            requireNotNullCompat(
                profile, "profile"
            )
        }
    }

    /** Parsed `auxC` item property.
     * 
     * @param type the auxiliary image type string
     */
    data class AuxiliaryType(val type: String) : Property {
        /** Creates an auxiliary type property. */
        init {
            requireNotNullCompat(type, "type")
        }
    }

    /** Opaque or currently unsupported item property.
     * 
     * @param type the property type
     * @param userType the UUID property user type, or `null`
     * @param payload the property payload after any UUID user type
     */
    data class OpaqueProperty(
        val type: String,
        val userType: ByteArray?,
        val payload: ByteArray
    ) : Property {
        /** Creates a public opaque item property descriptor.
         * 
         * @return the public opaque item property descriptor
         */
        fun toOpaqueItemProperty(): AvifOpaqueItemProperty {
            return AvifOpaqueItemProperty(type, userType, payload)
        }

        /** Creates an opaque property. */
        init {
            requireNotNullCompat(type, "type")
            requireNotNullCompat(
                payload, "payload"
            )
        }
    }

    /** Parsed `pixi` item property.
     * 
     * @param bitsPerChannel the bits-per-channel array
     */
    class PixelInformation(bitsPerChannel: IntArray) : Property {
        val bitsPerChannel: IntArray

        /** Creates a pixel information property with an independent channel-depth array. */
        init {
            var bitsPerChannel = bitsPerChannel
            bitsPerChannel =
                requireNotNullCompat(bitsPerChannel, "bitsPerChannel")
                    .copyOf()
            this.bitsPerChannel = bitsPerChannel
        }
    }

    /** Parsed `pasp` item property.
     * 
     * @param hSpacing the horizontal relative spacing
     * @param vSpacing the vertical relative spacing
     */
    data class PixelAspectRatio(val hSpacing: Int, val vSpacing: Int) : Property

    /** Parsed `clap` item property.
     * 
     * @param cleanApertureWidthN the clean aperture width numerator
     * @param cleanApertureWidthD the clean aperture width denominator
     * @param cleanApertureHeightN the clean aperture height numerator
     * @param cleanApertureHeightD the clean aperture height denominator
     * @param horizOffN the horizontal offset numerator
     * @param horizOffD the horizontal offset denominator
     * @param vertOffN the vertical offset numerator
     * @param vertOffD the vertical offset denominator
     */
    data class CleanAperture(
        val cleanApertureWidthN: Int,
        val cleanApertureWidthD: Int,
        val cleanApertureHeightN: Int,
        val cleanApertureHeightD: Int,
        val horizOffN: Int,
        val horizOffD: Int,
        val vertOffN: Int,
        val vertOffD: Int
    ) : Property

    /** Parsed `irot` item property.
     * 
     * @param rotation the rotation angle code
     */
    data class ImageRotation(val rotation: Int) : Property

    /** Parsed `imir` item property.
     * 
     * @param axis the mirror axis
     */
    data class ImageMirror(val axis: Int) : Property

    /** Parsed `a1op` item property.
     * 
     * @param operatingPoint the operating point index
     */
    data class OperatingPoint(val operatingPoint: Int) : Property

    /** Parsed `lsel` item property.
     * 
     * @param layerId the selected AV1 spatial-layer identifier, or `65535` for progressive selection
     */
    data class LayerSelector(val layerId: Int) : Property
    companion object {
        /** The MIME content type used by AVIF XMP metadata items. */
        private const val XMP_CONTENT_TYPE = "application/rdf+xml"

        /** The maximum gain-map metadata version supported by this parser. */
        private const val SUPPORTED_GAIN_MAP_METADATA_VERSION = 0

        /** Internal marker for an indefinite track duration. */
        const val INDEFINITE_TRACK_DURATION = -1L

        /** The maximum number of sequence samples materialized from one track's sample tables. */
        private const val MAX_SEQUENCE_SAMPLE_COUNT = 1000000

        /** The default maximum cumulative estimated size of retained non-image metadata. */
        const val DEFAULT_METADATA_SIZE_LIMIT = 64L * 1024L * 1024L

        /** The implementation limit for estimated retained non-image metadata. */
        const val MAX_RETAINED_METADATA_SIZE = 256L * 1024L * 1024L

        /** The estimated retained size of one parsed item and its collection headers. */
        private const val RETAINED_ITEM_SIZE = 256L

        /** The estimated retained size of one parsed item property. */
        private const val RETAINED_PROPERTY_SIZE = 64L

        /** The estimated retained size of one item extent. */
        private const val RETAINED_EXTENT_SIZE = 32L

        /** The estimated retained size of one reference stored in a collection. */
        private const val RETAINED_REFERENCE_SIZE = 24L

        /** The estimated retained base size of one entity group. */
        private const val RETAINED_ENTITY_GROUP_SIZE = 48L

        /** The estimated retained size of one AVIS track parser state. */
        private const val RETAINED_MOOV_STATE_SIZE = 256L

        /** The estimated retained size of one boxed sample-table integer. */
        private const val RETAINED_SAMPLE_INTEGER_SIZE = 24L

        /** The estimated retained size of one sample-to-chunk entry. */
        private const val RETAINED_SAMPLE_TO_CHUNK_SIZE = 32L

        /** The estimated retained size of one resolved sequence payload and its array entries. */
        private const val RETAINED_SEQUENCE_PAYLOAD_SIZE = 48L

        /** Parses AVIF container data.
         * 
         * The returned container retains the supplied array for lazy AV1 payload reads. The caller
         * must not modify the array while using the returned container.
         * 
         * @param source the source bytes
         * @return parsed AVIF container data
         * @throws AvifDecodeException if the container is malformed or unsupported
         */
        fun parse(source: ByteArray): AvifContainer {
            return parse(
                AvifDataSource.Companion.ofBytes(
                    requireNotNullCompat(
                        source,
                        "source"
                    )
                ),
                DEFAULT_METADATA_SIZE_LIMIT
            )
        }

        /** Parses AVIF container data with a cumulative retained-metadata estimate limit.
         * 
         * @param source the retained positional source
         * @param metadataSizeLimit the cumulative metadata size limit in bytes, or zero for no
         * configured limit
         * @return parsed AVIF container data
         * @throws AvifDecodeException if the container is malformed, unsupported, or exceeds the
         * configured metadata limit
         */
        /** Parses AVIF container data from a retained positional source.
         * 
         * The returned container may retain payload ranges backed by `source`; the source must remain
         * open while those payloads are decoded.
         * 
         * @param source the retained positional source
         * @return parsed AVIF container data
         * @throws AvifDecodeException if the container is malformed or unsupported
         */
        fun parse(
            source: AvifDataSource,
            metadataSizeLimit: Long = DEFAULT_METADATA_SIZE_LIMIT
        ): AvifContainer {
            return AvifContainerParser(source, metadataSizeLimit).parse()
        }

        /** Returns whether a Sample Transform token code is reserved by AVIF.
         * 
         * @param tokenCode the unsigned 8-bit token code
         * @return whether the token is reserved
         */
        fun isReservedSampleTransformToken(tokenCode: Int): Boolean {
            return (tokenCode >= 33 && tokenCode <= 63)
                    || (tokenCode >= 68 && tokenCode <= 127)
                    || tokenCode >= 138
        }

        /** Returns whether two items carry the same color-information properties.
         * 
         * Parsed `nclx` and ICC properties are compared by value. Unknown `colr` properties are
         * compared by their preserved payload so that their semantics are not guessed.
         * 
         * @param first the first image item
         * @param second the second image item
         * @return whether both items have equal color information in association order
         */
        fun sameColorInformation(first: Item, second: Item): Boolean {
            val firstProperties = ArrayList<Property>()
            val secondProperties = ArrayList<Property>()
            for (property in first.properties) {
                if (isColorInformationProperty(property)) {
                    firstProperties.add(property)
                }
            }
            for (property in second.properties) {
                if (isColorInformationProperty(property)) {
                    secondProperties.add(property)
                }
            }
            if (firstProperties.size != secondProperties.size) {
                return false
            }
            for (i in firstProperties.indices) {
                val firstProperty = firstProperties.get(i)
                val secondProperty = secondProperties.get(i)
                if (firstProperty is ColorProperty
                    && secondProperty is ColorProperty
                ) {
                    val firstInfo = firstProperty.colorInfo
                    val secondInfo = secondProperty.colorInfo
                    if (firstInfo.colorPrimaries() != secondInfo.colorPrimaries() || firstInfo.transferCharacteristics() != secondInfo.transferCharacteristics() || firstInfo.matrixCoefficients() != secondInfo.matrixCoefficients() || firstInfo.fullRange() != secondInfo.fullRange()) {
                        return false
                    }
                } else if (firstProperty is IccColorProfile
                    && secondProperty is IccColorProfile
                ) {
                    if (!firstProperty.profile.contentEquals(secondProperty.profile)) {
                        return false
                    }
                } else if (firstProperty is OpaqueProperty
                    && secondProperty is OpaqueProperty
                ) {
                    if (!firstProperty.payload.contentEquals(secondProperty.payload)) {
                        return false
                    }
                } else {
                    return false
                }
            }
            return true
        }

        /** Returns whether one parsed property carries color information.
         * 
         * @param property the parsed item property
         * @return whether the property originated from a `colr` property
         */
        fun isColorInformationProperty(property: Property): Boolean {
            return property is ColorProperty
                    || property is IccColorProfile
                    || (property is OpaqueProperty && "colr" == property.type)
        }

        /** Returns whether one item has any associated color-information property.
         * 
         * @param item the image item
         * @return whether the item has a `colr` property
         */
        fun hasColorInformation(item: Item): Boolean {
            for (property in item.properties) {
                if (isColorInformationProperty(property)) {
                    return true
                }
            }
            return false
        }

        /** Validates that an alpha grid matches the color grid canvas dimensions.
         * 
         * @param colorGrid the color grid
         * @param auxiliaryGrid the auxiliary grid
         * @param label the diagnostic auxiliary label
         * @throws AvifDecodeException if dimensions differ
         */
        fun validateAlphaGridDimensions(
            colorGrid: GridPayloads,
            auxiliaryGrid: GridPayloads,
            label: String
        ) {
            if (auxiliaryGrid.outputWidth != colorGrid.outputWidth || auxiliaryGrid.outputHeight != colorGrid.outputHeight) {
                throw parseFailed(label + " grid dimensions must match the master grid")
            }
        }

        /** Returns whether an auxiliary type is the AVIF alpha auxiliary type.
         * 
         * @param auxiliaryType the auxiliary image type string
         * @return whether the type describes an alpha auxiliary image
         */
        fun isAlphaAuxiliaryType(auxiliaryType: String): Boolean {
            return AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE == auxiliaryType
        }

        /** Validates explicitly associated transform properties against a master image item.
         * 
         * An item may omit all transformative properties and use the master image's presentation. If
         * it explicitly associates any transformative property, `clap`, `irot`, and `imir` must all
         * match the master image exactly.
         * 
         * @param imageItem the master image item
         * @param comparedItem the item whose transform properties are checked
         * @param label the diagnostic item label
         * @throws AvifDecodeException if explicit transform properties differ from the master image
         */
        fun validateTransformProperties(
            imageItem: Item,
            comparedItem: Item,
            label: String
        ) {
            if (!hasTransformProperties(comparedItem)) {
                return
            }
            if (!sameCleanApertureProperty(imageItem, comparedItem) || !sameImageRotationProperty(
                    imageItem,
                    comparedItem
                ) || !sameImageMirrorProperty(imageItem, comparedItem)
            ) {
                throw unsupported(
                    label + " transform properties differ from the master image",
                    null
                )
            }
        }

        /** Returns whether one item has any AVIF transformative property.
         * 
         * @param item the item to inspect
         * @return whether the item has a `clap`, `irot`, or `imir` property
         */
        fun hasTransformProperties(item: Item): Boolean {
            return item.firstProperty<CleanAperture>(CleanAperture::class) != null || item.firstProperty<ImageRotation>(
                ImageRotation::class
            ) != null || item.firstProperty<ImageMirror>(ImageMirror::class) != null
        }

        /** Returns whether two items have equal `clap` properties.
         * 
         * @param firstItem the first item
         * @param secondItem the second item
         * @return whether the `clap` properties are both absent or equal
         */
        fun sameCleanApertureProperty(firstItem: Item, secondItem: Item): Boolean {
            val first: CleanAperture? =
                firstItem.firstProperty<CleanAperture>(CleanAperture::class)
            val second: CleanAperture? =
                secondItem.firstProperty<CleanAperture>(CleanAperture::class)
            if (first === second) {
                return true
            }
            if (first == null || second == null) {
                return false
            }
            return first.cleanApertureWidthN == second.cleanApertureWidthN && first.cleanApertureWidthD == second.cleanApertureWidthD && first.cleanApertureHeightN == second.cleanApertureHeightN && first.cleanApertureHeightD == second.cleanApertureHeightD && first.horizOffN == second.horizOffN && first.horizOffD == second.horizOffD && first.vertOffN == second.vertOffN && first.vertOffD == second.vertOffD
        }

        /** Returns whether two items have equal `irot` properties.
         * 
         * @param firstItem the first item
         * @param secondItem the second item
         * @return whether the `irot` properties are both absent or equal
         */
        fun sameImageRotationProperty(firstItem: Item, secondItem: Item): Boolean {
            val first: ImageRotation? =
                firstItem.firstProperty<ImageRotation>(ImageRotation::class)
            val second: ImageRotation? =
                secondItem.firstProperty<ImageRotation>(ImageRotation::class)
            if (first === second) {
                return true
            }
            return first != null && second != null && first.rotation == second.rotation
        }

        /** Returns whether two items have equal `imir` properties.
         * 
         * @param firstItem the first item
         * @param secondItem the second item
         * @return whether the `imir` properties are both absent or equal
         */
        fun sameImageMirrorProperty(firstItem: Item, secondItem: Item): Boolean {
            val first: ImageMirror? = firstItem.firstProperty<ImageMirror>(ImageMirror::class)
            val second: ImageMirror? =
                secondItem.firstProperty<ImageMirror>(ImageMirror::class)
            if (first === second) {
                return true
            }
            return first != null && second != null && first.axis == second.axis
        }

        /** Collects opaque item properties associated with one rendered image item.
         * 
         * @param imageItem the color or grid image item
         * @return the opaque item properties in association order
         */
        fun opaqueItemProperties(imageItem: Item): Array<AvifOpaqueItemProperty> {
            val result = ArrayList<AvifOpaqueItemProperty>()
            for (property in imageItem.properties) {
                if (property is OpaqueProperty) {
                    result.add(property.toOpaqueItemProperty())
                }
            }
            return result.toTypedArray()
        }

        /** Extracts typed image-transform metadata from the properties of one item.
         * 
         * @param item the item whose properties are searched
         * @param imageWidth the image width before clap
         * @param imageHeight the image height before clap
         * @return the normalized image-transform metadata, or `null` when no transform is present
         */
        fun extractTransformInfo(
            item: Item,
            imageWidth: Int,
            imageHeight: Int
        ): AvifImageTransformInfo? {
            var clapCropX = -1
            var clapCropY = -1
            var clapCropWidth = -1
            var clapCropHeight = -1
            var rotationCode = -1
            var mirrorAxis = -1

            val clap: CleanAperture? = item.firstProperty<CleanAperture>(CleanAperture::class)
            if (clap != null) {
                clapCropWidth =
                    ((clap.cleanApertureWidthN.toLong() + clap.cleanApertureWidthD - 1L) / clap.cleanApertureWidthD).toInt()
                clapCropHeight =
                    ((clap.cleanApertureHeightN.toLong() + clap.cleanApertureHeightD - 1L) / clap.cleanApertureHeightD).toInt()
                clapCropX = roundedCleanApertureOrigin(
                    imageWidth,
                    clap.cleanApertureWidthN, clap.cleanApertureWidthD,
                    clap.horizOffN, clap.horizOffD
                )
                clapCropY = roundedCleanApertureOrigin(
                    imageHeight,
                    clap.cleanApertureHeightN, clap.cleanApertureHeightD,
                    clap.vertOffN, clap.vertOffD
                )
                if (clapCropX >= 0 && clapCropY >= 0 && clapCropWidth > 0 && clapCropHeight > 0 && clapCropX < imageWidth && clapCropY < imageHeight) {
                    clapCropWidth = min(clapCropWidth, imageWidth - clapCropX)
                    clapCropHeight = min(clapCropHeight, imageHeight - clapCropY)
                }
                if (clapCropX < 0 || clapCropY < 0 || clapCropWidth <= 0 || clapCropHeight <= 0 || clapCropX >= imageWidth || clapCropY >= imageHeight) {
                    clapCropX = -1
                    clapCropY = -1
                    clapCropWidth = -1
                    clapCropHeight = -1
                }
            }

            val irot: ImageRotation? = item.firstProperty<ImageRotation>(ImageRotation::class)
            if (irot != null) {
                rotationCode = irot.rotation
            }

            val imir: ImageMirror? = item.firstProperty<ImageMirror>(ImageMirror::class)
            if (imir != null) {
                mirrorAxis = imir.axis
            }

            if (clapCropX == -1 && rotationCode == -1 && mirrorAxis == -1) {
                return null
            }
            return AvifImageTransformInfo(
                clapCropX,
                clapCropY,
                clapCropWidth,
                clapCropHeight,
                rotationCode,
                mirrorAxis
            )
        }

        /** Returns the display dimensions after applying clean-aperture cropping and rotation.
         * 
         * @param imageWidth the image width before item transforms
         * @param imageHeight the image height before item transforms
         * @param transformInfo the normalized transform metadata, or `null` when no transform is present
         * @return the transformed display dimensions
         */
        fun transformedDisplaySize(
            imageWidth: Int,
            imageHeight: Int,
            transformInfo: AvifImageTransformInfo?
        ): DisplaySize {
            val width = if (transformInfo != null && transformInfo.hasCleanApertureCrop())
                transformInfo.cleanApertureCropWidth()
            else
                imageWidth
            val height = if (transformInfo != null && transformInfo.hasCleanApertureCrop())
                transformInfo.cleanApertureCropHeight()
            else
                imageHeight
            val rotationCode = if (transformInfo == null) -1 else transformInfo.rotationCode()
            return if (rotationCode == 1 || rotationCode == 3)
                DisplaySize(height, width)
            else
                DisplaySize(width, height)
        }

        /** Converts one clean-aperture center offset to an integer crop origin.
         * 
         * The `clap` offset locates the clean-aperture center relative to the uncropped image center;
         * it is not the top-left crop coordinate. Non-integral coordinates are rounded to the nearest
         * pixel, with half-pixel ties rounded toward the positive direction.
         * 
         * @param imageDimension the uncropped image dimension
         * @param apertureNumerator the clean-aperture dimension numerator
         * @param apertureDenominator the clean-aperture dimension denominator
         * @param offsetNumerator the signed center-offset numerator
         * @param offsetDenominator the center-offset denominator
         * @return the normalized crop origin, or -1 if it is negative or exceeds the integer range
         */
        fun roundedCleanApertureOrigin(
            imageDimension: Int,
            apertureNumerator: Int,
            apertureDenominator: Int,
            offsetNumerator: Int,
            offsetDenominator: Int
        ): Int {
            val apertureDenominatorValue = BigInt(apertureDenominator.toLong())
            val offsetDenominatorValue = BigInt(offsetDenominator.toLong())
            val commonDenominator = apertureDenominatorValue
                .multiply(offsetDenominatorValue)
                .shiftLeft(1)
            val numerator = BigInt(imageDimension.toLong())
                .multiply(apertureDenominatorValue)
                .multiply(offsetDenominatorValue)
                .subtract(
                    BigInt(apertureNumerator.toLong()).multiply(offsetDenominatorValue)
                )
                .add(
                    BigInt(offsetNumerator.toLong()).multiply(apertureDenominatorValue)
                        .shiftLeft(1)
                )
            if (numerator.signum() < 0) {
                return -1
            }

            val rounded = numerator.add(commonDenominator.shiftRight(1)).divide(commonDenominator)
            return if (rounded.bitLength() <= 31) rounded.toInt() else -1
        }

        /** Returns the AV1 operating point selected for one image item.
         * 
         * @param item the image item
         * @return the `a1op` value, or zero when the property is absent
         * @throws AvifDecodeException if the item has more than one `a1op` property
         */
        fun operatingPoint(item: Item): Int {
            val operatingPoint: OperatingPoint? =
                Companion.uniqueProperty<OperatingPoint>(item, OperatingPoint::class, "a1op")
            return if (operatingPoint != null) operatingPoint.operatingPoint else 0
        }

        /** Returns the AV1 spatial layer selected for one image item.
         * 
         * The absent selector and the special `layer_id` value `65535` both select the highest output
         * spatial layer from the chosen operating point.
         * 
         * @param item the image item
         * @return the selected spatial-layer identifier, or [AvifImageSource#HIGHEST_SPATIAL_LAYER]
         * @throws AvifDecodeException if the item has more than one `lsel` property
         */
        fun selectedSpatialLayer(item: Item): Int {
            val layerSelector: LayerSelector? =
                Companion.uniqueProperty<LayerSelector>(item, LayerSelector::class, "lsel")
            return if (layerSelector == null || layerSelector.layerId == 0xFFFF)
                AvifImageSource.Companion.HIGHEST_SPATIAL_LAYER
            else
                layerSelector.layerId
        }

        /** Returns the unique item property of one type.
         * 
         * @param item the image item
         * @param propertyClass the requested property class
         * @param propertyType the property box type used in diagnostics
         * @param <T> the parsed property type
         * @return the property, or `null` when absent
         * @throws AvifDecodeException if more than one matching property is associated with the item
        </T> */
        fun <T : Any> uniqueProperty(
            item: Item,
            propertyClass: kotlin.reflect.KClass<T>,
            propertyType: String
        ): T? {
            var result: T? = null
            for (property in item.properties) {
                if (!propertyClass.isInstance(property)) {
                    continue
                }
                if (result != null) {
                    throw parseFailed("Item " + item.id + " has more than one $propertyType property")
                }
                @Suppress("UNCHECKED_CAST")
                result = property as T
            }
            return result
        }

        /** Parses a `hdlr` box.
         * 
         * @param input the box payload input
         * @throws AvifDecodeException if the box is malformed
         */
        fun parseHandler(input: BoxInput) {
            readFullBox(input)
            val preDefined = input.readU32()
            if (preDefined != 0L) {
                throw parseFailed("hdlr pre_defined must be zero", (input.offset() - 4).toLong())
            }
            val handlerType = input.readFourCc()
            if ("pict" != handlerType) {
                throw parseFailed("meta handler_type must be pict", (input.offset() - 4).toLong())
            }
        }

        /** Parses an `ispe` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parseIspe(input: BoxInput): ImageSpatialExtents {
            readFullBox(input)
            val width: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val height: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            if (width <= 0 || height <= 0) {
                throw parseFailed("ispe dimensions must be positive", input.offset().toLong())
            }
            return ImageSpatialExtents(width, height)
        }

        /** Parses an `av1C` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parseAv1C(input: BoxInput): Av1Config {
            val first = input.readU8()
            if ((first ushr 7) != 1 || (first and 0x7F) != 1) {
                throw parseFailed("av1C marker/version is invalid", (input.offset() - 1).toLong())
            }
            val second = input.readU8()
            val third = input.readU8()
            input.readU8()
            val seqProfile = second ushr 5
            val seqLevelIdx0 = second and 0x1F
            val highBitDepth = ((third ushr 6) and 1) != 0
            val twelveBit = ((third ushr 5) and 1) != 0
            val monochrome = ((third ushr 4) and 1) != 0
            val chromaSubsamplingX = ((third ushr 3) and 1) != 0
            val chromaSubsamplingY = ((third ushr 2) and 1) != 0
            val chromaSamplePosition = third and 0x03
            return Av1Config(
                seqProfile,
                seqLevelIdx0,
                highBitDepth,
                twelveBit,
                monochrome,
                chromaSubsamplingX,
                chromaSubsamplingY,
                chromaSamplePosition
            )
        }

        /** Parses a `pixi` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parsePixi(input: BoxInput): PixelInformation {
            readFullBox(input)
            val numChannels = input.readU8()
            if (numChannels < 1) {
                throw parseFailed(
                    "pixi must have at least one channel",
                    (input.offset() - 1).toLong()
                )
            }
            if (numChannels > input.remaining()) {
                throw parseFailed(
                    "pixi numChannels exceeds available payload",
                    (input.offset() - 1).toLong()
                )
            }
            val bitsPerChannel = IntArray(numChannels)
            for (i in 0..<numChannels) {
                bitsPerChannel[i] = input.readU8()
            }
            return PixelInformation(bitsPerChannel)
        }

        /** Parses a `pasp` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parsePasp(input: BoxInput): PixelAspectRatio {
            val hSpacing: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val vSpacing: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            if (hSpacing <= 0 || vSpacing <= 0) {
                throw parseFailed("pasp spacing values must be positive", input.offset().toLong())
            }
            return PixelAspectRatio(hSpacing, vSpacing)
        }

        /** Parses a `clap` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parseClap(input: BoxInput): CleanAperture {
            val cleanApertureWidthN: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val cleanApertureWidthD: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val cleanApertureHeightN: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val cleanApertureHeightD: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val horizOffN = input.readU32().toInt()
            val horizOffD: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val vertOffN = input.readU32().toInt()
            val vertOffD: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            if (cleanApertureWidthD <= 0 || cleanApertureHeightD <= 0 || horizOffD <= 0 || vertOffD <= 0) {
                throw parseFailed(
                    "clap denominator values must be positive",
                    input.offset().toLong()
                )
            }
            return CleanAperture(
                cleanApertureWidthN, cleanApertureWidthD,
                cleanApertureHeightN, cleanApertureHeightD,
                horizOffN, horizOffD,
                vertOffN, vertOffD
            )
        }

        /** Parses an `irot` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parseIrot(input: BoxInput): ImageRotation {
            val packed = input.readU8()
            if ((packed and 0xFC) != 0) {
                throw parseFailed("irot reserved bits must be zero", (input.offset() - 1).toLong())
            }
            val rotation = packed and 0x03
            return ImageRotation(rotation)
        }

        /** Parses an `imir` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parseImir(input: BoxInput): ImageMirror {
            val packed = input.readU8()
            if ((packed and 0xFE) != 0) {
                throw parseFailed("imir reserved bits must be zero", (input.offset() - 1).toLong())
            }
            val axis = packed and 0x01
            if (axis != 0 && axis != 1) {
                throw parseFailed("imir axis must be 0 or 1", (input.offset() - 1).toLong())
            }
            return ImageMirror(axis)
        }

        /** Parses an `a1op` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parseA1op(input: BoxInput): OperatingPoint {
            if (input.remaining() != 1) {
                throw parseFailed(
                    "a1op payload must contain exactly one byte",
                    input.offset().toLong()
                )
            }
            val operatingPoint = input.readU8()
            if (operatingPoint > 31) {
                throw parseFailed(
                    "a1op operating point exceeds maximum",
                    (input.offset() - 1).toLong()
                )
            }
            return OperatingPoint(operatingPoint)
        }

        /** Parses an `lsel` property.
         * 
         * @param input the property payload input
         * @return the parsed property
         * @throws AvifDecodeException if the property is malformed
         */
        fun parseLsel(input: BoxInput): LayerSelector {
            if (input.remaining() != 2) {
                throw parseFailed(
                    "lsel payload must contain exactly two bytes",
                    input.offset().toLong()
                )
            }
            val layerId = input.readU16()
            if (layerId > 3 && layerId != 0xFFFF) {
                throw parseFailed(
                    "lsel layer_id must be in [0, 3] or 65535",
                    (input.offset() - 2).toLong()
                )
            }
            return LayerSelector(layerId)
        }

        /** Returns whether one top-level BMFF box may use size 0 to extend to EOF.
         * 
         * @param type the box type
         * @return whether this parser accepts size 0 for the box type
         */
        fun allowsTopLevelSizeZero(type: String): Boolean {
            return "mdat" == type || "meta" == type || "moov" == type
        }

        /** Resolves and validates the total sequence duration.
         * 
         * @param track the parsed color track state
         * @param payloads the extracted color sample payloads
         * @return the reconciled duration in media timescale units
         * @throws AvifDecodeException if the advertised duration conflicts with per-frame durations
         */
        fun sequenceDuration(track: MoovState, payloads: SequencePayloads): Long {
            val frameDurationSum: Long = frameDurationSum(payloads.frameDeltas)
            if (track.editListRepeating && track.editListSegmentDuration != frameDurationSum) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    ("Color sequence edit-list duration does not match frame durations: "
                            + track.editListSegmentDuration + " != $frameDurationSum"),
                    null
                )
            }
            if (track.mediaDuration == 0L) {
                return frameDurationSum
            }
            if (track.mediaDuration != frameDurationSum) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    ("Color sequence media duration does not match frame durations: "
                            + track.mediaDuration + " != $frameDurationSum"),
                    null
                )
            }
            return track.mediaDuration
        }

        /** Resolves the animated-sequence repetition count from a parsed edit list.
         * 
         * @param track the parsed color track state
         * @return the public repetition-count value
         * @throws AvifDecodeException if a repeating edit list has an invalid track duration
         */
        fun sequenceRepetitionCount(track: MoovState): Int {
            if (!track.editListSeen) {
                return AvifSequenceInfo.Companion.REPETITION_COUNT_UNKNOWN
            }
            if (!track.editListRepeating) {
                return 0
            }
            if (track.trackDuration == INDEFINITE_TRACK_DURATION) {
                return AvifSequenceInfo.Companion.REPETITION_COUNT_INFINITE
            }
            if (track.trackDuration == 0L) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Invalid repeating edit-list track duration",
                    null
                )
            }
            val segmentDuration = track.editListSegmentDuration
            val repetitionCount = ((track.trackDuration / segmentDuration)
                    + (if (track.trackDuration % segmentDuration != 0L) 1 else 0)
                    - 1)
            return if (repetitionCount > Int.MAX_VALUE)
                AvifSequenceInfo.Companion.REPETITION_COUNT_INFINITE
            else repetitionCount.toInt()
        }

        /** Sums per-frame duration deltas.
         * 
         * @param frameDeltas the frame duration deltas
         * @return the total duration
         */
        fun frameDurationSum(frameDeltas: IntArray): Long {
            var sum: Long = 0
            for (frameDelta in frameDeltas) {
                sum += frameDelta.toLong()
            }
            return sum
        }

        /** Finalizes AV1 configuration that depends on the complete parsed track geometry.
         * 
         * @param track the parsed track to finalize
         */
        fun finalizeMoovTrack(track: MoovState) {
            if (isPotentialMoovImageTrack(track)
                && track.containsAv1SampleDescription
                && track.sampleDescriptionCount != 1
            ) {
                throw parseFailed("AVIS stsd must contain exactly one sample description")
            }
            if (isPotentialMoovImageTrack(track)
                && track.av1Config != null && track.hasNonPrimarySampleDescriptionReference
            ) {
                throw parseFailed("AVIS stsc sample_description_index must be 1")
            }
            if (isPotentialMoovImageTrack(track)
                && track.av1Config != null && track.maximumSyncSampleNumber > track.sampleSizes.size
            ) {
                throw parseFailed("AVIS stss sample number is outside the sample table")
            }
            val av1Config = track.av1Config
            if (av1Config != null && track.width > 0 && track.height > 0) {
                track.seqHeaderObu = av1Config.seqHeaderObu(track.width, track.height)
            }
        }

        /** Infers the alpha type for legacy monochrome AVIS auxiliary tracks without `auxi`.
         * 
         * Early AVIF sequence encoders identified alpha tracks only through a `tref/auxl`
         * relationship. A monochrome track with that relationship is treated as alpha so that its
         * samples remain associated with the selected color sequence.
         * 
         * @param track the parsed track state
         */
        fun inferLegacyMoovAuxiliaryType(track: MoovState) {
            if (track.auxiliaryType == null && !track.auxiliaryForTrackIds.isEmpty() && track.chromaFormat == Av1ChromaFormat.MONOCHROME) {
                track.auxiliaryType = AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE
            }
        }

        /** Returns whether a parsed `trak` can be used as an AVIS image-sequence track.
         * 
         * @param track the parsed track state
         * @return whether the track handler describes a color or auxiliary image track
         */
        fun isMoovImageTrack(track: MoovState): Boolean {
            val handlerType = track.mediaHandlerType
            return handlerType == null || "pict" == handlerType
                    || "vide" == handlerType
                    || ("auxv" == handlerType && track.auxiliaryType != null)
        }

        /** Returns whether a handler may describe an AVIS image track before its sample entry is parsed.
         * 
         * @param track the partially parsed track state
         * @return whether the handler permits an AV1 image sample entry
         */
        fun isPotentialMoovImageTrack(track: MoovState): Boolean {
            val handlerType = track.mediaHandlerType
            return handlerType == null || "pict" == handlerType
                    || "vide" == handlerType
                    || "auxv" == handlerType
        }

        /** Returns the deterministic selection priority for one AVIS color-track candidate.
         * 
         * A conforming `pict` handler takes precedence over legacy `vide` or omitted handler
         * signaling. Tracks at the same priority retain file order.
         * 
         * @param track the parsed color-track candidate
         * @return the candidate priority
         */
        fun moovColorTrackPreference(track: MoovState): Int {
            return if ("pict" == track.mediaHandlerType) 2 else 1
        }

        /** Returns whether an auxiliary AVIS track is associated with the selected color track.
         * 
         * @param auxiliaryTrack the candidate auxiliary track
         * @param colorTrack the selected color track
         * @return whether the auxiliary track applies to the selected color track
         */
        fun auxiliaryTrackMatchesColor(
            auxiliaryTrack: MoovState,
            colorTrack: MoovState
        ): Boolean {
            return auxiliaryTrack.auxiliaryForTrackIds.isEmpty()
                    || (colorTrack.trackId != 0 && auxiliaryTrack.auxiliaryForTrackIds.contains(
                colorTrack.trackId
            ))
        }

        /** Validates that one matched AVIS alpha track has the color track's geometry.
         * 
         * @param auxiliaryTrack the matched alpha track
         * @param colorTrack the selected color track
         * @throws AvifDecodeException if the alpha track dimensions differ
         */
        fun validateMoovAlphaTrackGeometry(
            auxiliaryTrack: MoovState,
            colorTrack: MoovState
        ) {
            if (auxiliaryTrack.width > 0 && colorTrack.width > 0 && auxiliaryTrack.width != colorTrack.width) {
                throw parseFailed(
                    (moovAuxiliaryLabel(auxiliaryTrack) + " sequence track width does not match color sequence: "
                            + auxiliaryTrack.width + " != " + colorTrack.width)
                )
            }
            if (auxiliaryTrack.height > 0 && colorTrack.height > 0 && auxiliaryTrack.height != colorTrack.height) {
                throw parseFailed(
                    (moovAuxiliaryLabel(auxiliaryTrack) + " sequence track height does not match color sequence: "
                            + auxiliaryTrack.height + " != " + colorTrack.height)
                )
            }
        }

        /** Returns a diagnostic label for one AVIS auxiliary track.
         * 
         * @param auxiliaryTrack the auxiliary track
         * @return the diagnostic label
         */
        fun moovAuxiliaryLabel(auxiliaryTrack: MoovState): String {
            if (AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE == auxiliaryTrack.auxiliaryType) {
                return "Alpha"
            }
            if (AvifAuxiliaryImageInfo.Companion.DEPTH_TYPE == auxiliaryTrack.auxiliaryType) {
                return "Depth"
            }
            return "Auxiliary"
        }

        /** Parses a `tkhd` box for track dimensions. */
        fun parseMoovTkhd(input: BoxInput, track: MoovState) {
            val fb: FullBox = readFullBox(input)
            if (fb.version == 1) {
                input.skip(16)
            } else {
                input.skip(8)
            }
            track.trackId = checkedU32ToInt(input.readU32(), input.offset() - 4)
            input.skip(4)
            track.trackDuration =
                if (fb.version == 1) readTrackDuration64(input) else readTrackDuration32(input)
            input.skip(52)
            val w = input.readU32()
            val h = input.readU32()
            val width: Int = checkedU32ToInt(w ushr 16, input.offset())
            val height: Int = checkedU32ToInt(h ushr 16, input.offset())
            if (width > 0 && track.width == 0) {
                track.width = width
            }
            if (height > 0 && track.height == 0) {
                track.height = height
            }
        }

        /** Parses an `edts` box for AVIS edit-list metadata. */
        fun parseMoovEdts(input: BoxInput, track: MoovState) {
            var elstSeen = false
            while (input.hasRemaining()) {
                val child = input.readBoxHeader()
                if ("elst" == child.type) {
                    if (elstSeen) {
                        throw parseFailed(
                            "More than one [elst] box was found",
                            child.offset.toLong()
                        )
                    }
                    elstSeen = true
                    val payload = input.slice(child.payloadOffset, child.payloadSize)
                    parseMoovElst(payload, track)
                }
                input.skipBoxPayload(child)
            }
            if (!elstSeen) {
                throw parseFailed("Box[edts] contains no [elst] box", input.offset().toLong())
            }
        }

        /** Parses an `elst` box for AVIS repetition metadata. */
        fun parseMoovElst(input: BoxInput, track: MoovState) {
            val fb: FullBox = readFullBox(input)
            track.editListSeen = true
            if ((fb.flags and 1) == 0) {
                track.editListRepeating = false
                return
            }
            if (fb.version != 0 && fb.version != 1) {
                throw parseFailed(
                    "Box[elst] has an unsupported version: " + fb.version,
                    (input.offset() - 4).toLong()
                )
            }
            val entryCount: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            if (entryCount != 1) {
                throw parseFailed(
                    "Box[elst] contains an entry_count != 1: $entryCount",
                    (input.offset() - 4).toLong()
                )
            }
            val segmentDuration = if (fb.version == 1) input.readU64() else input.readU32()
            if (segmentDuration == 0L) {
                throw parseFailed(
                    "Box[elst] has a zero segment_duration",
                    (input.offset() - (if (fb.version == 1) 8 else 4)).toLong()
                )
            }
            input.skip(if (fb.version == 1) 8 else 4)
            input.skip(4)
            track.editListRepeating = true
            track.editListSegmentDuration = segmentDuration
        }

        /** Parses an AVIS media handler box.
         * 
         * @param input the box payload input
         * @throws AvifDecodeException if the handler box is malformed
         */
        fun parseMoovHandler(input: BoxInput, track: MoovState) {
            readFullBox(input)
            val preDefined = input.readU32()
            if (preDefined != 0L) {
                throw parseFailed(
                    "mdia hdlr pre_defined must be zero",
                    (input.offset() - 4).toLong()
                )
            }
            track.mediaHandlerType = input.readFourCc()
        }

        /** Parses an `mdhd` box for media timescale and duration. */
        fun parseMoovMdhd(input: BoxInput, track: MoovState) {
            val fb: FullBox = readFullBox(input)
            if (fb.version == 1) {
                input.skip(16)
            } else {
                input.skip(8)
            }
            val timescale: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            val duration = if (fb.version == 1) input.readU64() else input.readU32()
            track.mediaTimescale = timescale
            track.mediaDuration = duration
        }

        /** Rejects a compressed sample-table run before expanding it beyond the parser resource limit.
         * 
         * @param existingCount the number of samples already materialized for the current track
         * @param additionalCount the number of samples declared by the current table entry
         * @param boxType the sample-table box type used in diagnostics
         * @param offset the byte offset of the declared count
         * @throws AvifDecodeException if the expanded sample count exceeds the supported limit
         */
        fun enforceSequenceSampleCount(
            existingCount: Int,
            additionalCount: Int,
            boxType: String,
            offset: Int
        ) {
            val expandedCount = existingCount.toLong() + additionalCount
            if (expandedCount > MAX_SEQUENCE_SAMPLE_COUNT) {
                throw unsupported(
                    boxType + " sample count exceeds the supported limit: $expandedCount",
                    offset
                )
            }
        }

        /** Rejects an AVIS table before retaining an excessive number of entries.
         * 
         * @param entryCount the declared table entry count
         * @param boxType the table box type used in diagnostics
         * @param offset the source offset of the entry count
         * @throws AvifDecodeException if the table exceeds the parser resource limit
         */
        fun enforceSequenceTableEntryCount(entryCount: Int, boxType: String, offset: Int) {
            if (entryCount > MAX_SEQUENCE_SAMPLE_COUNT) {
                throw unsupported(
                    boxType + " entry count exceeds the supported limit: $entryCount",
                    offset
                )
            }
        }

        /** Parses `stss` for sync sample indices. */
        fun parseMoovStss(input: BoxInput, track: MoovState) {
            input.skip(4)
            val n: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
            enforceSequenceTableEntryCount(n, "stss", input.offset() - 4)
            for (i in 0..<n) {
                val sampleNumber: Int = checkedU32ToInt(input.readU32(), input.offset() - 4)
                if (sampleNumber <= 0) {
                    throw parseFailed(
                        "stss sample number is outside the sample table",
                        (input.offset() - 4).toLong()
                    )
                }
                track.maximumSyncSampleNumber = max(track.maximumSyncSampleNumber, sampleNumber)
            }
        }

        /** Returns the box type for a parsed item property that changes the rendered image geometry.
         * 
         * @param property the parsed item property
         * @return the transformative property type, or `null`
         */
        fun transformativePropertyType(property: Property): String? {
            if (property is CleanAperture) {
                return "clap"
            }
            if (property is ImageRotation) {
                return "irot"
            }
            if (property is ImageMirror) {
                return "imir"
            }
            return null
        }

        /** Creates a public auxiliary image descriptor from one parsed item.
         * 
         * @param item the auxiliary item
         * @param auxiliaryType the parsed auxiliary image type
         * @param fallbackWidth the master image width used by legacy alpha items without `ispe`
         * @param fallbackHeight the master image height used by legacy alpha items without `ispe`
         * @return an auxiliary image descriptor
         */
        fun auxiliaryImageInfo(
            item: Item,
            auxiliaryType: AuxiliaryType,
            fallbackWidth: Int,
            fallbackHeight: Int
        ): AvifAuxiliaryImageInfo {
            val ispe: ImageSpatialExtents? =
                item.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
            val av1Config: Av1Config? = item.firstProperty<Av1Config>(Av1Config::class)
            val alphaWithoutIspe =
                ispe == null && AvifAuxiliaryImageInfo.Companion.ALPHA_TYPE == auxiliaryType.type
            val width =
                if (ispe != null) ispe.width else (if (alphaWithoutIspe) fallbackWidth else -1)
            val height =
                if (ispe != null) ispe.height else (if (alphaWithoutIspe) fallbackHeight else -1)
            val bitDepth: AvifBitDepth? =
                if (av1Config != null) AvifBitDepth.Companion.fromBits(av1Config.bitDepth()) else null
            val chromaFormat = if (av1Config != null) av1Config.chromaFormat() else null
            return AvifAuxiliaryImageInfo(
                item.id,
                auxiliaryType.type,
                item.type,
                width,
                height,
                bitDepth,
                chromaFormat
            )
        }

        /** Creates a gain-map descriptor from parsed association metadata.
         * 
         * @param toneMappedItem the `tmap` derived image item
         * @param baseItemId the base image item id
         * @param gainMapItem the gain-map image item
         * @param toneMappedDimensions the tone-mapped item dimensions
         * @param gainMapDimensions the gain-map item dimensions
         * @param gainMapBitDepth the gain-map AV1 bit depth, or `null`
         * @param gainMapChromaFormat the gain-map AV1 chroma format, or `null`
         * @param metadata the parsed tone-map metadata
         * @return a gain-map descriptor
         */
        fun gainMapInfo(
            toneMappedItem: Item,
            baseItemId: Int,
            gainMapItem: Item,
            toneMappedDimensions: ItemDimensions,
            gainMapDimensions: ItemDimensions,
            gainMapBitDepth: AvifBitDepth?,
            gainMapChromaFormat: Av1ChromaFormat?,
            metadata: ToneMapMetadata
        ): AvifGainMapInfo {
            return AvifGainMapInfo(
                toneMappedItem.id,
                baseItemId,
                gainMapItem.id,
                toneMappedItem.type,
                gainMapItem.type
            ).withToneMappedSize(toneMappedDimensions.width, toneMappedDimensions.height)
                .withGainMapImage(
                    gainMapDimensions.width,
                    gainMapDimensions.height,
                    gainMapBitDepth,
                    gainMapChromaFormat
                )
                .withToneMappedColorInfo(toneMappedItem.firstProperty<AvifColorInfo>(AvifColorInfo::class))
                .withToneMappedIccProfile(iccProfile(toneMappedItem))
                .withGainMapColorInfo(gainMapItem.firstProperty<AvifColorInfo>(AvifColorInfo::class))
                .withMetadata(
                    metadata.version,
                    metadata.minimumVersion,
                    metadata.writerVersion,
                    metadata.metadata
                )
        }

        /** Returns the ICC profile property payload for one item.
         * 
         * @param item the item whose properties are searched
         * @return the ICC profile payload, or `null` when absent
         */
        fun iccProfile(item: Item): ByteArray? {
            val iccProfile: IccColorProfile? =
                item.firstProperty<IccColorProfile>(IccColorProfile::class)
            return if (iccProfile != null) iccProfile.profile else null
        }

        /** Parses ISO 21496-1 gain-map metadata from a `tmap` payload body.
         * 
         * @param input the input positioned after the version fields
         * @return parsed gain-map metadata
         * @throws AvifDecodeException if the metadata is malformed
         */
        fun parseGainMapMetadata(input: BoxInput): AvifGainMapMetadata {
            val packedFlags = input.readU8()
            val multichannel = (packedFlags and 0x80) != 0
            val useBaseColorSpace = (packedFlags and 0x40) != 0
            val channelCount = if (multichannel) 3 else 1

            val baseHdrHeadroom: AvifUnsignedFraction = readUnsignedFraction(input)
            val alternateHdrHeadroom: AvifUnsignedFraction = readUnsignedFraction(input)
            val gainMapMin = arrayOfNullsCompat<AvifSignedFraction>(3)
            val gainMapMax = arrayOfNullsCompat<AvifSignedFraction>(3)
            val gainMapGamma = arrayOfNullsCompat<AvifUnsignedFraction>(3)
            val baseOffset = arrayOfNullsCompat<AvifSignedFraction>(3)
            val alternateOffset = arrayOfNullsCompat<AvifSignedFraction>(3)

            for (c in 0..<channelCount) {
                gainMapMin[c] = readSignedFraction(input)
                gainMapMax[c] = readSignedFraction(input)
                gainMapGamma[c] = readUnsignedFraction(input)
                baseOffset[c] = readSignedFraction(input)
                alternateOffset[c] = readSignedFraction(input)
            }
            for (c in channelCount..2) {
                gainMapMin[c] = gainMapMin[0]
                gainMapMax[c] = gainMapMax[0]
                gainMapGamma[c] = gainMapGamma[0]
                baseOffset[c] = baseOffset[0]
                alternateOffset[c] = alternateOffset[0]
            }

            try {
                return AvifGainMapMetadata(
                    multichannel,
                    useBaseColorSpace,
                    baseHdrHeadroom,
                    alternateHdrHeadroom,
                    gainMapMin,
                    gainMapMax,
                    gainMapGamma,
                    baseOffset,
                    alternateOffset
                )
            } catch (exception: IllegalArgumentException) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Invalid gain-map metadata: " + exception.message,
                    null,
                    exception
                )
            }
        }

        /** Reads one unsigned gain-map metadata fraction.
         * 
         * @param input the source input
         * @return one unsigned fraction
         * @throws AvifDecodeException if the input is truncated or invalid
         */
        fun readUnsignedFraction(input: BoxInput): AvifUnsignedFraction {
            val numerator = input.readU32()
            val denominator = input.readU32()
            try {
                return AvifUnsignedFraction(numerator, denominator)
            } catch (exception: IllegalArgumentException) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Invalid unsigned gain-map fraction: " + exception.message,
                    null,
                    exception
                )
            }
        }

        /** Reads one signed gain-map metadata fraction.
         * 
         * @param input the source input
         * @return one signed fraction
         * @throws AvifDecodeException if the input is truncated or invalid
         */
        fun readSignedFraction(input: BoxInput): AvifSignedFraction {
            val numerator = input.readU32().toInt()
            val denominator = input.readU32()
            try {
                return AvifSignedFraction(numerator, denominator)
            } catch (exception: IllegalArgumentException) {
                throw AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Invalid signed gain-map fraction: " + exception.message,
                    null,
                    exception
                )
            }
        }

        /** Returns image dimensions from an item's `ispe` property.
         * 
         * @param item the image item
         * @return known or unknown item dimensions
         */
        fun itemDimensions(item: Item): ItemDimensions {
            val ispe: ImageSpatialExtents? =
                item.firstProperty<ImageSpatialExtents>(ImageSpatialExtents::class)
            return if (ispe != null) ItemDimensions(
                ispe.width,
                ispe.height
            ) else ItemDimensions.Companion.UNKNOWN
        }

        /** Reads one full-box header.
         * 
         * @param input the box payload input
         * @return one full-box header
         * @throws AvifDecodeException if the box is truncated
         */
        fun readFullBox(input: BoxInput): FullBox {
            val version = input.readU8()
            val flags = input.readU24()
            return FullBox(version, flags)
        }

        /** Ensures one unique child box is not repeated.
         * 
         * @param seen the seen box types
         * @param type the current box type
         * @param offset the current box offset
         * @throws AvifDecodeException if the box repeats
         */
        fun unique(seen: MutableSet<String>, type: String, offset: Int) {
            if (!seen.add(type)) {
                throw parseFailed("duplicate unique box: $type", offset.toLong())
            }
        }

        /** Reads a BMFF variable-width unsigned integer.
         * 
         * @param input the input to read from
         * @param byteCount the encoded byte count
         * @return the parsed value
         * @throws AvifDecodeException if the size is unsupported or the input is truncated
         */
        fun readUx(input: BoxInput, byteCount: Int): Long {
            return when (byteCount) {
                0 -> 0L
                4 -> input.readU32()
                8 -> input.readU64()
                else -> throw parseFailed(
                    "unsupported variable integer byte count: $byteCount",
                    input.offset().toLong()
                )
            }
        }

        /** Reads a 32-bit track duration while preserving the indefinite-duration marker.
         * 
         * @param input the input to read from
         * @return the parsed duration, or `INDEFINITE_TRACK_DURATION`
         * @throws AvifDecodeException if the input is truncated
         */
        fun readTrackDuration32(input: BoxInput): Long {
            val value = input.readU32()
            return if (value == 0xFFFFFFFFL) INDEFINITE_TRACK_DURATION else value
        }

        /** Reads a 64-bit track duration while preserving the indefinite-duration marker.
         * 
         * @param input the input to read from
         * @return the parsed duration, or `INDEFINITE_TRACK_DURATION`
         * @throws AvifDecodeException if the input is truncated or the value exceeds the supported range
         */
        fun readTrackDuration64(input: BoxInput): Long {
            val high = input.readU32()
            val low = input.readU32()
            if (high == 0xFFFFFFFFL && low == 0xFFFFFFFFL) {
                return INDEFINITE_TRACK_DURATION
            }
            if ((high and 0x80000000L) != 0L) {
                throw parseFailed(
                    "64-bit track duration exceeds supported range",
                    (input.offset() - 8).toLong()
                )
            }
            return (high shl 32) or low
        }

        /** Validates an `iloc` variable integer field size.
         * 
         * @param byteCount the byte count to validate
         * @param offset the associated byte offset
         * @throws AvifDecodeException if the byte count is invalid
         */
        fun validateIlocFieldSize(byteCount: Int, offset: Int) {
            if (byteCount != 0 && byteCount != 4 && byteCount != 8) {
                throw parseFailed("invalid iloc field size: $byteCount", offset.toLong())
            }
        }

        /** Adds two unsigned offsets with overflow checking.
         * 
         * @param left the left value
         * @param right the right value
         * @param offset the associated byte offset
         * @return the checked sum
         * @throws AvifDecodeException if the sum overflows
         */
        fun checkedAdd(left: Long, right: Long, offset: Int): Long {
            if (right > Long.MAX_VALUE - left) {
                throw parseFailed("integer overflow while merging iloc offsets", offset.toLong())
            }
            return left + right
        }

        /** Converts a parsed unsigned 32-bit value to `int`.
         * 
         * @param value the parsed unsigned value
         * @param offset the associated byte offset
         * @return the converted value
         * @throws AvifDecodeException if the value exceeds `Int.MAX_VALUE`
         */
        fun checkedU32ToInt(value: Long, offset: Int): Int {
            if (value > Int.MAX_VALUE) {
                throw parseFailed("32-bit value exceeds supported range: $value", offset.toLong())
            }
            return value.toInt()
        }

        /** Converts a parsed unsigned 64-bit value to `int`.
         * 
         * @param value the parsed unsigned value
         * @param offset the associated byte offset
         * @return the converted value
         * @throws AvifDecodeException if the value exceeds `Int.MAX_VALUE`
         */
        fun checkedU64ToInt(value: Long, offset: Int): Int {
            if (value > Int.MAX_VALUE) {
                throw parseFailed("64-bit value exceeds supported range: $value", offset.toLong())
            }
            return value.toInt()
        }

        /** Creates a BMFF parse failure.
         * 
         * @param message the failure message
         * @return a BMFF parse failure without a specific byte offset
         */
        fun parseFailed(message: String): AvifDecodeException {
            return AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, null)
        }

        /** Creates a BMFF parse failure at a specific input offset.
         * 
         * @param message the failure message
         * @param offset the associated byte offset
         * @return a BMFF parse failure
         */
        fun parseFailed(message: String, offset: Long): AvifDecodeException {
            return AvifDecodeException(AvifErrorCode.BMFF_PARSE_FAILED, message, offset)
        }

        /** Creates an unsupported-feature failure.
         * 
         * @param message the failure message
         * @param offset the associated byte offset, or `null`
         * @return an unsupported-feature failure
         */
        fun unsupported(message: String, offset: Int?): AvifDecodeException {
            return AvifDecodeException(
                AvifErrorCode.UNSUPPORTED_FEATURE,
                message,
                if (offset == null) null else offset.toLong()
            )
        }

        /** Writes a LEB128 unsigned value to the stream. */
        fun writeLeb128(out: ByteArrayOutput, value: Int) {
            var v = value
            do {
                var b = v and 0x7F
                v = v ushr 7
                if (v != 0) b = b or 0x80
                out.write(b)
            } while (v != 0)
        }

        /** Reads one null-terminated UTF-8 string.
         * 
         * @param input the source input
         * @return the decoded string without the terminator
         * @throws AvifDecodeException if the string is not terminated within this input
         */
        fun readNullTerminatedString(input: BoxInput): String {
            val output = ByteArrayOutput()
            while (input.hasRemaining()) {
                val value = input.readU8()
                if (value == 0) {
                    return output.toString(Charsets.UTF_8)
                }
                output.write(value)
            }
            throw parseFailed("unterminated BMFF string", input.offset().toLong())
        }
    }
}
