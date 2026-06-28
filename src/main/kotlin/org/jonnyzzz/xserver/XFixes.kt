package org.jonnyzzz.xserver

internal object XFixes {
    const val MajorOpcode = 133
    const val FirstEvent = 68
    const val FirstError = 172
    const val MajorVersion = 2
    const val MinorVersion = 0
    const val BadRegion = FirstError

    const val QueryVersion = 0
    const val ChangeSaveSet = 1
    const val SelectSelectionInput = 2
    const val SelectCursorInput = 3
    const val GetCursorImage = 4
    const val CreateRegion = 5
    const val DestroyRegion = 10
    const val SetRegion = 11
    const val CopyRegion = 12
    const val UnionRegion = 13
    const val IntersectRegion = 14
    const val SubtractRegion = 15
    const val InvertRegion = 16
    const val TranslateRegion = 17
    const val RegionExtents = 18
    const val FetchRegion = 19
    const val SetGCClipRegion = 20
    const val SetPictureClipRegion = 22

    const val SaveSetNearest = 0
    const val SaveSetRoot = 1
    const val SaveSetMap = 0
    const val SaveSetUnmap = 1

    const val SelectionNotify = 0
    const val CursorNotify = 1
    const val SetSelectionOwnerNotify = 0
    const val SelectionWindowDestroyNotify = 1
    const val SelectionClientCloseNotify = 2
    const val SetSelectionOwnerNotifyMask = 1 shl 0
    const val SelectionWindowDestroyNotifyMask = 1 shl 1
    const val SelectionClientCloseNotifyMask = 1 shl 2
    const val SelectionNotifyMask =
        SetSelectionOwnerNotifyMask or SelectionWindowDestroyNotifyMask or SelectionClientCloseNotifyMask

    const val DisplayCursorNotify = 0
    const val DisplayCursorNotifyMask = 1 shl 0

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            QueryVersion -> "QueryVersion"
            ChangeSaveSet -> "ChangeSaveSet"
            SelectSelectionInput -> "SelectSelectionInput"
            SelectCursorInput -> "SelectCursorInput"
            GetCursorImage -> "GetCursorImage"
            CreateRegion -> "CreateRegion"
            DestroyRegion -> "DestroyRegion"
            SetRegion -> "SetRegion"
            CopyRegion -> "CopyRegion"
            UnionRegion -> "UnionRegion"
            IntersectRegion -> "IntersectRegion"
            SubtractRegion -> "SubtractRegion"
            InvertRegion -> "InvertRegion"
            TranslateRegion -> "TranslateRegion"
            RegionExtents -> "RegionExtents"
            FetchRegion -> "FetchRegion"
            SetGCClipRegion -> "SetGCClipRegion"
            SetPictureClipRegion -> "SetPictureClipRegion"
            else -> "Unknown"
        }
}
