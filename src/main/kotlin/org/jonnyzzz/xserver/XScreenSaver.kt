package org.jonnyzzz.xserver

internal object XScreenSaver {
    const val MajorOpcode = 139
    const val FirstEvent = 71
    const val FirstError = 0
    const val SaverWindow = 0x0000_0100
    const val MajorVersion = 1
    const val MinorVersion = 1

    const val QueryVersion = 0
    const val QueryInfo = 1
    const val SelectInput = 2
    const val SetAttributes = 3
    const val UnsetAttributes = 4
    const val Suspend = 5

    const val NotifyMask = 1
    const val CycleMask = 2
    const val EventMask = NotifyMask or CycleMask

    const val StateOff = 0
    const val StateDisabled = 3

    const val KindBlanked = 0
    const val KindInternal = 1
    const val KindExternal = 2

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            QueryInfo -> "QueryInfo"
            SelectInput -> "SelectInput"
            SetAttributes -> "SetAttributes"
            UnsetAttributes -> "UnsetAttributes"
            Suspend -> "Suspend"
            else -> "Unknown"
        }
}
