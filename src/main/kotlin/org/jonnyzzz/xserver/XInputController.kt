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

    fun pointerDown(x: Int, y: Int, button: Int = 1): XInputResult {
        require(button in 1..5) { "X11 pointer button must be in 1..5" }
        return pointerResolved(x = x, y = y, button = button, buttonName = buttonName(button), pressed = true)
    }

    fun pointerUp(x: Int, y: Int, button: Int = 1): XInputResult {
        require(button in 1..5) { "X11 pointer button must be in 1..5" }
        return pointerResolved(x = x, y = y, button = button, buttonName = buttonName(button), pressed = false)
    }

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

    private fun pointerResolved(x: Int, y: Int, button: Int, buttonName: String, pressed: Boolean): XInputResult {
        val dispatch = state.pointerButton(
            x = x,
            y = y,
            button = button,
            pressed = pressed,
        )
        val result = XInputResult(
            targetWindowId = dispatch.targetWindowId,
            deliveredEvents = dispatch.deliveredEvents,
        )
        state.recordInputOperation(
            kind = if (pressed) "pointer-down" else "pointer-up",
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
    fun isKilled(): Boolean = false
    fun killClient() = Unit
    fun sendPointerEvent(event: XPointerEvent)
    fun sendMappingNotifyEvent(event: XMappingNotifyEvent)
    fun sendMapNotifyEvent(event: XMapNotifyEvent)
    fun sendCreateNotifyEvent(event: XCreateNotifyEvent)
    fun sendUnmapNotifyEvent(event: XUnmapNotifyEvent)
    fun sendCirculateNotifyEvent(event: XCirculateNotifyEvent)
    fun sendConfigureNotifyEvent(event: XConfigureNotifyEvent)
    fun sendPropertyNotifyEvent(event: XPropertyNotifyEvent)
    fun sendSelectionClearEvent(event: XSelectionClearEvent)
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
    MotionNotify(6),
}

internal data class XPointerDispatch(
    val targetWindowId: Int?,
    val deliveredEvents: Int,
)

internal data class XPointerQuery(
    val childWindowId: Int,
    val rootX: Int,
    val rootY: Int,
    val windowX: Int,
    val windowY: Int,
    val mask: Int,
)

internal data class XTranslatedCoordinates(
    val childWindowId: Int,
    val destinationX: Int,
    val destinationY: Int,
)

internal data class XMappingNotifyEvent(
    val request: Int,
    val firstKeycode: Int = 0,
    val count: Int = 0,
)

internal data class XPropertyNotifyEvent(
    val windowId: Int,
    val atom: Int,
    val state: Int,
    val time: Int = 0,
)

internal data class XMapNotifyEvent(
    val eventWindowId: Int,
    val windowId: Int,
    val overrideRedirect: Boolean = false,
)

internal data class XMapNotifyDispatch(
    val sink: XEventSink,
    val event: XMapNotifyEvent,
)

internal data class XCreateNotifyEvent(
    val parentId: Int,
    val windowId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val overrideRedirect: Boolean = false,
)

internal data class XCreateNotifyDispatch(
    val sink: XEventSink,
    val event: XCreateNotifyEvent,
)

internal data class XUnmapNotifyEvent(
    val eventWindowId: Int,
    val windowId: Int,
    val fromConfigure: Boolean = false,
)

internal data class XUnmapNotifyDispatch(
    val sink: XEventSink,
    val event: XUnmapNotifyEvent,
)

internal data class XCirculateNotifyEvent(
    val eventWindowId: Int,
    val windowId: Int,
    val place: Int,
)

internal data class XCirculateNotifyDispatch(
    val sink: XEventSink,
    val event: XCirculateNotifyEvent,
)

internal data class XConfigureNotifyEvent(
    val eventWindowId: Int,
    val windowId: Int,
    val aboveSiblingId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val overrideRedirect: Boolean = false,
)

internal data class XConfigureNotifyDispatch(
    val sink: XEventSink,
    val event: XConfigureNotifyEvent,
)

internal data class XSelectionClearEvent(
    val time: Int,
    val ownerWindowId: Int,
    val selection: Int,
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

internal data class XSelectionClearDispatch(
    val sink: XEventSink,
    val event: XSelectionClearEvent,
)

internal data class XSyntheticEvent(
    val bytes: ByteArray,
    val sourceByteOrder: ByteOrder,
)

internal object XEventMasks {
    const val ValidCoreMask = 0x01ff_ffff
    const val ValidPointerEventMask = 0x0000_7ffc
    const val ButtonPress = 1 shl 2
    const val ButtonRelease = 1 shl 3
    const val PointerMotion = 1 shl 6
    const val StructureNotify = 1 shl 17
    const val SubstructureNotify = 1 shl 19
    const val PropertyChange = 1 shl 22

    fun forPointerType(type: XPointerEventType): Int =
        when (type) {
            XPointerEventType.ButtonPress -> ButtonPress
            XPointerEventType.ButtonRelease -> ButtonRelease
            XPointerEventType.MotionNotify -> PointerMotion
        }
}
