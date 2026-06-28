package org.jonnyzzz.xserver

internal object XRender {
    const val MajorOpcode = 130
    const val FirstEvent = 0
    const val FirstError = 160
    const val MajorVersion = 0
    const val MinorVersion = 11
    const val PictFormatError = FirstError
    const val PictureError = FirstError + 1
    const val GlyphSetError = FirstError + 3
    const val GlyphError = FirstError + 4

    const val Argb32Format = 0x0000_0029
    const val Rgb24Format = 0x0000_002a
    const val A8Format = 0x0000_002b
    const val A1Format = 0x0000_002c
    val DirectFormats = setOf(Argb32Format, Rgb24Format, A8Format, A1Format)
    val PictFormats = DirectFormats

    const val OpClear = 0
    const val OpSrc = 1
    const val OpDst = 2
    const val OpOver = 3
    const val OpIn = 5
    const val OpInReverse = 6
    const val OpOut = 7
    const val OpOutReverse = 8
    const val OpAtop = 9
    const val OpAtopReverse = 10
    const val OpXor = 11
    const val OpAdd = 12
    const val OpSaturate = 13
    const val OpMaximum = 13
    const val OpDisjointClear = 0x10
    const val OpDisjointMaximum = 0x1b
    const val OpConjointClear = 0x20
    const val OpConjointMaximum = 0x2b
    const val OpBlendMultiply = 0x30
    const val OpBlendMaximum = 0x3e

    const val CPRepeat = 1 shl 0
    const val CPAlphaMap = 1 shl 1
    const val CPAlphaXOrigin = 1 shl 2
    const val CPAlphaYOrigin = 1 shl 3
    const val CPClipXOrigin = 1 shl 4
    const val CPClipYOrigin = 1 shl 5
    const val CPClipMask = 1 shl 6
    const val CPGraphicsExposure = 1 shl 7
    const val CPSubwindowMode = 1 shl 8
    const val CPPolyEdge = 1 shl 9
    const val CPPolyMode = 1 shl 10
    const val CPDither = 1 shl 11
    const val CPComponentAlpha = 1 shl 12
    const val PictureAttributeMask = 0x0000_1fff

    const val RepeatNone = 0
    const val RepeatNormal = 1
    const val RepeatPad = 2
    const val RepeatReflect = 3
    const val SubwindowModeClipByChildren = 0
    const val SubwindowModeIncludeInferiors = 1
    const val PolyEdgeSharp = 0
    const val PolyEdgeSmooth = 1
    const val PolyModePrecise = 0
    const val PolyModeImprecise = 1
    const val LegacyTransformFilterNearest = 0
    const val FilterNearest = "nearest"

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            0 -> "QueryVersion"
            1 -> "QueryPictFormats"
            2 -> "QueryPictIndexValues"
            3 -> "QueryDithers"
            4 -> "CreatePicture"
            5 -> "ChangePicture"
            6 -> "SetPictureClipRectangles"
            7 -> "FreePicture"
            8 -> "Composite"
            9 -> "Scale"
            10 -> "Trapezoids"
            11 -> "Triangles"
            12 -> "TriStrip"
            13 -> "TriFan"
            14 -> "ColorTrapezoids"
            15 -> "ColorTriangles"
            16 -> "Transform"
            17 -> "CreateGlyphSet"
            18 -> "ReferenceGlyphSet"
            19 -> "FreeGlyphSet"
            20 -> "AddGlyphs"
            21 -> "AddGlyphsFromPicture"
            22 -> "FreeGlyphs"
            23 -> "CompositeGlyphs8"
            24 -> "CompositeGlyphs16"
            25 -> "CompositeGlyphs32"
            26 -> "FillRectangles"
            27 -> "CreateCursor"
            28 -> "SetPictureTransform"
            29 -> "QueryFilters"
            30 -> "SetPictureFilter"
            31 -> "CreateAnimCursor"
            32 -> "AddTraps"
            33 -> "CreateSolidFill"
            34 -> "CreateLinearGradient"
            35 -> "CreateRadialGradient"
            36 -> "CreateConicalGradient"
            else -> "Unknown"
        }

    fun isAlphaMaskFormat(format: Int): Boolean =
        format == A8Format || format == A1Format

    fun isValidOperator(operation: Int): Boolean =
        operation in OpClear..OpMaximum ||
            operation in OpDisjointClear..OpDisjointMaximum ||
            operation in OpConjointClear..OpConjointMaximum ||
            operation in OpBlendMultiply..OpBlendMaximum

    fun isValidRepeat(repeat: Int): Boolean =
        repeat in RepeatNone..RepeatReflect

    fun isValidBoolValue(value: Int): Boolean =
        value == 0 || value == 1

    fun isValidSubwindowMode(subwindowMode: Int): Boolean =
        subwindowMode == SubwindowModeClipByChildren || subwindowMode == SubwindowModeIncludeInferiors

    fun isValidPolyEdge(polyEdge: Int): Boolean =
        polyEdge == PolyEdgeSharp || polyEdge == PolyEdgeSmooth

    fun isValidPolyMode(polyMode: Int): Boolean =
        polyMode == PolyModePrecise || polyMode == PolyModeImprecise

    fun formatDepth(format: Int): Int? =
        when (format) {
            Argb32Format -> 32
            Rgb24Format -> 24
            A8Format -> 8
            A1Format -> 1
            else -> null
        }

    fun argb32Pixel(red: Int, green: Int, blue: Int, alpha: Int): Int =
        ((alpha ushr 8).coerceIn(0, 255) shl 24) or
            ((red ushr 8).coerceIn(0, 255) shl 16) or
            ((green ushr 8).coerceIn(0, 255) shl 8) or
            (blue ushr 8).coerceIn(0, 255)

    fun repeatName(repeat: Int): String =
        when (repeat) {
            RepeatNone -> "none"
            RepeatNormal -> "normal"
            RepeatPad -> "pad"
            RepeatReflect -> "reflect"
            else -> "unknown-$repeat"
        }
}
