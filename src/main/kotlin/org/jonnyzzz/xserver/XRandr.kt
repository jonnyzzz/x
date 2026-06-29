package org.jonnyzzz.xserver

internal object XRandr {
    const val MajorOpcode = 141
    const val FirstEvent = 74
    const val FirstError = 177
    const val MajorVersion = 1
    const val MinorVersion = 6

    const val BadOutput = FirstError
    const val BadCrtc = FirstError + 1
    const val BadMode = FirstError + 2
    const val BadProvider = FirstError + 3
    const val BadLease = FirstError + 4

    const val QueryVersion = 0
    const val GetScreenInfo = 5
    const val SelectInput = 4
    const val GetScreenSizeRange = 6
    const val SetScreenSize = 7
    const val GetScreenResources = 8
    const val GetOutputInfo = 9
    const val ListOutputProperties = 10
    const val GetCrtcInfo = 20
    const val GetCrtcGammaSize = 22
    const val GetCrtcGamma = 23
    const val GetScreenResourcesCurrent = 25
    const val GetOutputPrimary = 31
    const val GetProviders = 32
    const val GetMonitors = 42

    const val Rotate0 = 1
    const val Connected = 0
    const val SubPixelUnknown = 0
    const val Success = 0

    const val ScreenChangeNotifyMask = 1 shl 0
    const val CrtcChangeNotifyMask = 1 shl 1
    const val OutputChangeNotifyMask = 1 shl 2
    const val OutputPropertyNotifyMask = 1 shl 3
    const val ProviderChangeNotifyMask = 1 shl 4
    const val ProviderPropertyNotifyMask = 1 shl 5
    const val ResourceChangeNotifyMask = 1 shl 6
    const val LeaseNotifyMask = 1 shl 7
    const val EventMask = ScreenChangeNotifyMask or
        CrtcChangeNotifyMask or
        OutputChangeNotifyMask or
        OutputPropertyNotifyMask or
        ProviderChangeNotifyMask or
        ProviderPropertyNotifyMask or
        ResourceChangeNotifyMask or
        LeaseNotifyMask

    const val ModeId = 0x0000_0200
    const val CrtcId = 0x0000_0201
    const val OutputId = 0x0000_0202

    const val RefreshRate = 60
    const val OutputName = "screen-0"

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            GetScreenInfo -> "GetScreenInfo"
            SelectInput -> "SelectInput"
            GetScreenSizeRange -> "GetScreenSizeRange"
            SetScreenSize -> "SetScreenSize"
            GetScreenResources -> "GetScreenResources"
            GetOutputInfo -> "GetOutputInfo"
            ListOutputProperties -> "ListOutputProperties"
            GetCrtcInfo -> "GetCrtcInfo"
            GetCrtcGammaSize -> "GetCrtcGammaSize"
            GetCrtcGamma -> "GetCrtcGamma"
            GetScreenResourcesCurrent -> "GetScreenResourcesCurrent"
            GetOutputPrimary -> "GetOutputPrimary"
            GetProviders -> "GetProviders"
            GetMonitors -> "GetMonitors"
            else -> "Unknown"
        }
}
