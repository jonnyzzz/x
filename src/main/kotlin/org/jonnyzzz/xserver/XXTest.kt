package org.jonnyzzz.xserver

internal object XXTest {
    const val MajorOpcode = 136
    const val FirstEvent = 0
    const val FirstError = 0
    const val MajorVersion = 2
    const val MinorVersion = 2

    const val GetVersion = 0
    const val CompareCursor = 1
    const val FakeInput = 2
    const val GrabControl = 3

    const val CursorNone = 0
    const val CursorCurrent = 1

    const val KeyPress = 2
    const val KeyRelease = 3
    const val ButtonPress = 4
    const val ButtonRelease = 5
    const val MotionNotify = 6

    fun operationName(minorOpcode: Int): String =
        when (minorOpcode) {
            GetVersion -> "GetVersion"
            CompareCursor -> "CompareCursor"
            FakeInput -> "FakeInput"
            GrabControl -> "GrabControl"
            else -> "Unknown"
        }
}
