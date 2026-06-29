package org.jonnyzzz.xserver

internal object XXCMisc {
    const val MajorOpcode = 137
    const val FirstEvent = 0
    const val FirstError = 0
    const val MajorVersion = 1
    const val MinorVersion = 1
    const val MaxIdsPerReply = 4096

    const val GetVersion = 0
    const val GetXIDRange = 1
    const val GetXIDList = 2

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            GetVersion -> "GetVersion"
            GetXIDRange -> "GetXIDRange"
            GetXIDList -> "GetXIDList"
            else -> "Unknown"
        }
}
