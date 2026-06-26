package org.jonnyzzz.xserver

internal object XXkb {
    const val MajorOpcode = 132
    const val FirstEvent = 66
    const val FirstError = 169
    const val MajorVersion = 1
    const val MinorVersion = 0

    const val UseExtension = 0
    const val SelectEvents = 1
    const val GetState = 4

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            UseExtension -> "UseExtension"
            SelectEvents -> "SelectEvents"
            3 -> "Bell"
            GetState -> "GetState"
            5 -> "LatchLockState"
            6 -> "GetControls"
            7 -> "SetControls"
            8 -> "GetMap"
            9 -> "SetMap"
            10 -> "GetCompatMap"
            11 -> "SetCompatMap"
            12 -> "GetIndicatorState"
            13 -> "GetIndicatorMap"
            14 -> "SetIndicatorMap"
            15 -> "GetNamedIndicator"
            16 -> "SetNamedIndicator"
            17 -> "GetNames"
            18 -> "SetNames"
            21 -> "PerClientFlags"
            22 -> "ListComponents"
            23 -> "GetKbdByName"
            24 -> "GetDeviceInfo"
            25 -> "SetDeviceInfo"
            101 -> "SetDebuggingFlags"
            else -> "Unknown"
        }
}
