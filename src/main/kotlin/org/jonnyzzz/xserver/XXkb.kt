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
    const val SetMap = 9
    const val GetCompatMap = 10
    const val SetCompatMap = 11
    const val GetIndicatorState = 12
    const val GetIndicatorMap = 13
    const val SetIndicatorMap = 14
    const val GetNamedIndicator = 15
    const val SetNamedIndicator = 16
    const val GetNames = 17
    const val SetNames = 18
    const val GetGeometry = 19
    const val SetGeometry = 20
    const val PerClientFlags = 21
    const val ListComponents = 22
    const val GetKbdByName = 23
    const val GetDeviceInfo = 24
    const val SetDeviceInfo = 25
    const val SetDebuggingFlags = 101

    const val BoolCtrlRepeatKeys = 1 shl 0
    const val MapPartKeyTypes = 1 shl 0
    const val MapPartKeySyms = 1 shl 1
    const val MapPartModifierMap = 1 shl 2
    const val MapPartExplicitComponents = 1 shl 3
    const val MapPartKeyActions = 1 shl 4
    const val MapPartKeyBehaviors = 1 shl 5
    const val MapPartVirtualMods = 1 shl 6
    const val MapPartVirtualModMap = 1 shl 7
    const val NameDetailKeycodes = 1 shl 0
    const val NameDetailGeometry = 1 shl 1
    const val NameDetailSymbols = 1 shl 2
    const val NameDetailPhysSymbols = 1 shl 3
    const val NameDetailTypes = 1 shl 4
    const val NameDetailCompat = 1 shl 5
    const val NameDetailKeyTypeNames = 1 shl 6
    const val NameDetailKtLevelNames = 1 shl 7
    const val NameDetailIndicatorNames = 1 shl 8
    const val NameDetailKeyNames = 1 shl 9
    const val NameDetailKeyAliases = 1 shl 10
    const val NameDetailVirtualModNames = 1 shl 11
    const val NameDetailGroupNames = 1 shl 12
    const val NameDetailRgNames = 1 shl 13
    const val XiFeatureButtonActions = 1 shl 1
    const val XiFeatureIndicatorNames = 1 shl 2
    const val XiFeatureIndicatorMaps = 1 shl 3
    const val XiFeatureIndicatorState = 1 shl 4
    const val XiFeatureIndicators = XiFeatureIndicatorNames or XiFeatureIndicatorMaps or XiFeatureIndicatorState
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
            SetMap -> "SetMap"
            GetCompatMap -> "GetCompatMap"
            SetCompatMap -> "SetCompatMap"
            GetIndicatorState -> "GetIndicatorState"
            GetIndicatorMap -> "GetIndicatorMap"
            SetIndicatorMap -> "SetIndicatorMap"
            GetNamedIndicator -> "GetNamedIndicator"
            SetNamedIndicator -> "SetNamedIndicator"
            GetNames -> "GetNames"
            SetNames -> "SetNames"
            GetGeometry -> "GetGeometry"
            SetGeometry -> "SetGeometry"
            PerClientFlags -> "PerClientFlags"
            ListComponents -> "ListComponents"
            GetKbdByName -> "GetKbdByName"
            GetDeviceInfo -> "GetDeviceInfo"
            SetDeviceInfo -> "SetDeviceInfo"
            SetDebuggingFlags -> "SetDebuggingFlags"
            else -> "Unknown"
        }
}
