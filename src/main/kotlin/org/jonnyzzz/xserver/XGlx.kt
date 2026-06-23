package org.jonnyzzz.xserver

internal object XGlx {
    const val MajorOpcode = 128
    const val FirstEvent = 0
    const val FirstError = 128
    const val MajorVersion = 1
    const val MinorVersion = 4

    const val QueryVersion = 7
    const val IsDirect = 6
    const val GetVisualConfigs = 14
    const val QueryExtensionsString = 18
    const val QueryServerString = 19
    const val ClientInfo = 20
    const val GetFBConfigs = 21
    const val CreateNewContext = 24
    const val MakeContextCurrent = 26
    const val CreateContextAttribsARB = 34

    const val VendorName = 1
    const val VersionName = 2
    const val ExtensionsName = 3

    const val RgbaType = 0x8014
    const val RootFbConfigId = X11Ids.RootVisual

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            1 -> "Render"
            2 -> "RenderLarge"
            3 -> "CreateContext"
            4 -> "DestroyContext"
            5 -> "MakeCurrent"
            IsDirect -> "IsDirect"
            QueryVersion -> "QueryVersion"
            8 -> "WaitGL"
            9 -> "WaitX"
            10 -> "CopyContext"
            11 -> "SwapBuffers"
            12 -> "UseXFont"
            13 -> "CreateGLXPixmap"
            GetVisualConfigs -> "GetVisualConfigs"
            15 -> "DestroyGLXPixmap"
            16 -> "VendorPrivate"
            17 -> "VendorPrivateWithReply"
            QueryExtensionsString -> "QueryExtensionsString"
            QueryServerString -> "QueryServerString"
            ClientInfo -> "ClientInfo"
            GetFBConfigs -> "GetFBConfigs"
            22 -> "CreatePixmap"
            23 -> "DestroyPixmap"
            CreateNewContext -> "CreateNewContext"
            25 -> "QueryContext"
            MakeContextCurrent -> "MakeContextCurrent"
            27 -> "CreatePbuffer"
            28 -> "DestroyPbuffer"
            29 -> "GetDrawableAttributes"
            30 -> "ChangeDrawableAttributes"
            31 -> "CreateWindow"
            32 -> "DestroyWindow"
            33 -> "SetClientInfoARB"
            CreateContextAttribsARB -> "CreateContextAttribsARB"
            35 -> "SetClientInfo2ARB"
            else -> "Unknown"
        }

    fun serverString(name: Int): String =
        when (name) {
            VendorName -> "jonnyzzz/x"
            VersionName -> "$MajorVersion.$MinorVersion"
            ExtensionsName -> ""
            else -> ""
        }

    fun visualConfig(): IntArray =
        intArrayOf(
            X11Ids.RootVisual,
            XVisualClassTrueColor,
            1,
            8,
            8,
            8,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            24,
            24,
            0,
            0,
            0,
            0x20,
            0x8000,
            0x23,
            0x8000,
            0x25,
            0,
            0x26,
            0,
            0x27,
            0,
            0x28,
            0,
            0x24,
            0,
            100001,
            0,
            100000,
            0,
            0x8028,
            0,
            0,
            0,
        )

    fun fbConfig(): IntArray {
        val pairs = listOf(
            0x800B to X11Ids.RootVisual,
            0x8013 to RootFbConfigId,
            0x8012 to 1,
            4 to 1,
            0x8011 to 0x00000001,
            5 to 0,
            6 to 0,
            2 to 24,
            3 to 0,
            7 to 0,
            8 to 8,
            9 to 8,
            10 to 8,
            11 to 0,
            14 to 0,
            15 to 0,
            16 to 0,
            17 to 0,
            12 to 24,
            13 to 0,
            0x22 to 0x8002,
            0x20 to 0x8000,
            0x23 to 0x8000,
            0x25 to 0,
            0x26 to 0,
            0x27 to 0,
            0x28 to 0,
            0x24 to 0,
            0x8010 to 0x00000001,
            0x8016 to 0,
            0x8017 to 0,
            0x8018 to 0,
            100001 to 0,
            100000 to 0,
        )
        val values = IntArray(FbConfigAttributePairs * 2)
        var offset = 0
        for ((attribute, value) in pairs) {
            values[offset++] = attribute
            values[offset++] = value
        }
        return values
    }

    const val VisualConfigValues = 40
    const val FbConfigAttributePairs = 44

    private const val XVisualClassTrueColor = 4
}
