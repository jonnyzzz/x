package org.jonnyzzz.xserver

class XInputController internal constructor(
    private val state: X11State,
) {
    fun click(x: Int, y: Int, button: Int = 1): XInputResult {
        require(button in 1..5) { "X11 pointer button must be in 1..5" }
        return clickResolved(x = x, y = y, button = button, buttonName = buttonName(button))
    }

    fun click(x: Int, y: Int, button: String): XInputResult =
        clickResolved(x = x, y = y, button = buttonNumber(button), buttonName = button)

    private fun clickResolved(x: Int, y: Int, button: Int, buttonName: String): XInputResult {
        val press = state.pointerButton(
            x = x,
            y = y,
            button = button,
            pressed = true,
        )
        val release = state.pointerButton(
            x = x,
            y = y,
            button = button,
            pressed = false,
        )
        val result = XInputResult(
            targetWindowId = release.targetWindowId ?: press.targetWindowId,
            deliveredEvents = press.deliveredEvents + release.deliveredEvents,
        )
        state.recordInputOperation(
            kind = "click",
            x = x,
            y = y,
            button = buttonName,
            targetWindowId = result.targetWindowId,
            deliveredEvents = result.deliveredEvents,
        )
        return result
    }

    private fun buttonNumber(value: String): Int =
        when (value.lowercase()) {
            "", "left", "primary" -> 1
            "middle" -> 2
            "right", "secondary" -> 3
            "wheel-up", "wheelup" -> 4
            "wheel-down", "wheeldown" -> 5
            else -> value.toIntOrNull() ?: throw IllegalArgumentException("unsupported pointer button: $value")
        }

    private fun buttonName(value: Int): String =
        when (value) {
            1 -> "left"
            2 -> "middle"
            3 -> "right"
            4 -> "wheel-up"
            5 -> "wheel-down"
            else -> value.toString()
        }
}

data class XInputResult(
    val targetWindowId: Int?,
    val deliveredEvents: Int,
) {
    val targetWindowIdHex: String? get() = targetWindowId?.let { "0x${it.toUInt().toString(16)}" }
}

internal interface XEventSink {
    fun sendPointerEvent(event: XPointerEvent)
    fun sendSelectionRequestEvent(event: XSelectionRequestEvent)
    fun sendSyntheticEvent(event: XSyntheticEvent)
}

internal data class XPointerEvent(
    val type: XPointerEventType,
    val button: Int,
    val rootX: Int,
    val rootY: Int,
    val eventWindowId: Int,
    val childWindowId: Int,
    val eventX: Int,
    val eventY: Int,
    val state: Int,
    val time: Int,
)

internal enum class XPointerEventType(val code: Int) {
    ButtonPress(4),
    ButtonRelease(5),
}

internal data class XPointerDispatch(
    val targetWindowId: Int?,
    val deliveredEvents: Int,
)

internal data class XSelectionRequestEvent(
    val time: Int,
    val ownerWindowId: Int,
    val requestorWindowId: Int,
    val selection: Int,
    val target: Int,
    val property: Int,
)

internal data class XSelectionRequestDispatch(
    val sink: XEventSink,
    val event: XSelectionRequestEvent,
)

internal data class XSyntheticEvent(
    val bytes: ByteArray,
    val sourceByteOrder: ByteOrder,
)

internal object XEventMasks {
    const val ValidCoreMask = 0x01ff_ffff
    const val ButtonPress = 1 shl 2
    const val ButtonRelease = 1 shl 3
    const val PointerMotion = 1 shl 6

    fun forPointerType(type: XPointerEventType): Int =
        when (type) {
            XPointerEventType.ButtonPress -> ButtonPress
            XPointerEventType.ButtonRelease -> ButtonRelease
        }
}
