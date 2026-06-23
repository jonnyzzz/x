package org.jonnyzzz.xserver

internal object XRender {
    const val MajorOpcode = 130
    const val FirstEvent = 96
    const val FirstError = 160
    const val MajorVersion = 0
    const val MinorVersion = 11

    const val Argb32Format = 0x0000_0029
    const val Rgb24Format = 0x0000_002a
    const val A8Format = 0x0000_002b
    const val A1Format = 0x0000_002c

    const val OpClear = 0
    const val OpSrc = 1
    const val OpOver = 3

    const val CPRepeat = 1 shl 0

    const val RepeatNone = 0
    const val RepeatNormal = 1
    const val RepeatPad = 2
    const val RepeatReflect = 3

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            0 -> "QueryVersion"
            1 -> "QueryPictFormats"
            2 -> "QueryPictIndexValues"
            4 -> "CreatePicture"
            5 -> "ChangePicture"
            6 -> "SetPictureClipRectangles"
            7 -> "FreePicture"
            8 -> "Composite"
            10 -> "Trapezoids"
            11 -> "Triangles"
            12 -> "TriStrip"
            13 -> "TriFan"
            17 -> "CreateGlyphSet"
            18 -> "ReferenceGlyphSet"
            19 -> "FreeGlyphSet"
            20 -> "AddGlyphs"
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
