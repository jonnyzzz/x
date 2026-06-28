package org.jonnyzzz.xserver

internal object XFixes {
    const val MajorOpcode = 133
    const val FirstEvent = 68
    const val FirstError = 172
    const val MajorVersion = 1
    const val MinorVersion = 0

    const val QueryVersion = 0
    const val ChangeSaveSet = 1
    const val SelectSelectionInput = 2
    const val SelectCursorInput = 3
    const val GetCursorImage = 4

    const val SaveSetNearest = 0
    const val SaveSetRoot = 1
    const val SaveSetMap = 0
    const val SaveSetUnmap = 1

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            ChangeSaveSet -> "ChangeSaveSet"
            SelectSelectionInput -> "SelectSelectionInput"
            SelectCursorInput -> "SelectCursorInput"
            GetCursorImage -> "GetCursorImage"
            else -> "Unknown"
        }
}
