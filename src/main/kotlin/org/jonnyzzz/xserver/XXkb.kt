package org.jonnyzzz.xserver

internal object XXkb {
    const val MajorOpcode = 132
    const val FirstEvent = 66
    const val FirstError = 169
    const val MajorVersion = 1
    const val MinorVersion = 0

    const val UseExtension = 0
    const val SelectEvents = 1
    const val Bell = 3
    const val GetState = 4
    const val LatchLockState = 5
    const val GetControls = 6
    const val SetControls = 7
    const val GetMap = 8
    const val GetCompatMap = 10
    const val SetCompatMap = 11
    const val GetIndicatorState = 12
    const val GetIndicatorMap = 13
    const val SetIndicatorMap = 14
    const val GetNamedIndicator = 15
    const val SetNamedIndicator = 16
    const val GetNames = 17
    const val PerClientFlags = 21
    const val ListComponents = 22
    const val GetKbdByName = 23
    const val GetDeviceInfo = 24
    const val SetDebuggingFlags = 101

    const val BoolCtrlRepeatKeys = 1 shl 0
    const val XiFeatureButtonActions = 1 shl 1
    const val XiFeatureIndicatorNames = 1 shl 2
    const val XiFeatureIndicatorMaps = 1 shl 3
    const val XiFeatureIndicatorState = 1 shl 4
    const val DefaultMouseKeysButton = 1
    const val DefaultGroupCount = 1
    const val DefaultRepeatDelay = 660
    const val DefaultRepeatInterval = 40

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            UseExtension -> "UseExtension"
            SelectEvents -> "SelectEvents"
            Bell -> "Bell"
            GetState -> "GetState"
            LatchLockState -> "LatchLockState"
            GetControls -> "GetControls"
            SetControls -> "SetControls"
            GetMap -> "GetMap"
            9 -> "SetMap"
            GetCompatMap -> "GetCompatMap"
            SetCompatMap -> "SetCompatMap"
            GetIndicatorState -> "GetIndicatorState"
            GetIndicatorMap -> "GetIndicatorMap"
            SetIndicatorMap -> "SetIndicatorMap"
            GetNamedIndicator -> "GetNamedIndicator"
            SetNamedIndicator -> "SetNamedIndicator"
            GetNames -> "GetNames"
            18 -> "SetNames"
            PerClientFlags -> "PerClientFlags"
            ListComponents -> "ListComponents"
            GetKbdByName -> "GetKbdByName"
            GetDeviceInfo -> "GetDeviceInfo"
            25 -> "SetDeviceInfo"
            SetDebuggingFlags -> "SetDebuggingFlags"
            else -> "Unknown"
        }
}
