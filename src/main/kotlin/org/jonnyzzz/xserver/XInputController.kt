package org.jonnyzzz.xserver

class XInputController internal constructor(
    private val state: X11State,
) {
    fun click(x: Int, y: Int, button: Int = 1): XInputResult {
        requireValidButton(button)
        return clickResolved(x = x, y = y, button = button, buttonName = buttonName(button))
    }

    fun click(x: Int, y: Int, button: String): XInputResult {
        val buttonNumber = buttonNumber(button)
        requireValidButton(buttonNumber)
        return clickResolved(x = x, y = y, button = buttonNumber, buttonName = button)
    }

    fun pointerDown(x: Int, y: Int, button: Int = 1): XInputResult {
        requireValidButton(button)
        return pointerResolved(x = x, y = y, button = button, buttonName = buttonName(button), pressed = true)
    }

    fun pointerUp(x: Int, y: Int, button: Int = 1): XInputResult {
        requireValidButton(button)
        return pointerResolved(x = x, y = y, button = button, buttonName = buttonName(button), pressed = false)
    }

    fun keyDown(keycode: Int, modifiers: Int = 0): XInputResult {
        requireValidKeycode(keycode)
        requireValidModifiers(modifiers)
        return keyResolved(keycode = keycode, modifiers = modifiers, pressed = true)
    }

    fun keyUp(keycode: Int, modifiers: Int = 0): XInputResult {
        requireValidKeycode(keycode)
        requireValidModifiers(modifiers)
        return keyResolved(keycode = keycode, modifiers = modifiers, pressed = false)
    }

    private fun requireValidButton(button: Int) {
        require(button in 1..255) { "X11 pointer button must be in 1..255" }
    }

    private fun requireValidKeycode(keycode: Int) {
        require(keycode in XKeyboard.MinKeycode..XKeyboard.MaxKeycode) {
            "X11 keycode must be in ${XKeyboard.MinKeycode}..${XKeyboard.MaxKeycode}"
        }
    }

    private fun requireValidModifiers(modifiers: Int) {
        require((modifiers and CoreKeyModifierMask.inv()) == 0) { "X11 key modifiers must fit the core modifier mask" }
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

    private fun keyResolved(keycode: Int, modifiers: Int, pressed: Boolean): XInputResult {
        val dispatch = state.keyboardKey(
            keycode = keycode,
            modifiers = modifiers,
            pressed = pressed,
        )
        val result = XInputResult(
            targetWindowId = dispatch.targetWindowId,
            deliveredEvents = dispatch.deliveredEvents,
        )
        state.recordInputOperation(
            kind = if (pressed) "key-down" else "key-up",
            x = 0,
            y = 0,
            button = keycode.toString(),
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

    private companion object {
        const val CoreKeyModifierMask = 0x00ff
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
    fun sendKeyEvent(event: XKeyEvent)
    fun sendMappingNotifyEvent(event: XMappingNotifyEvent)
    fun sendExposeEvent(event: XExposeEvent)
    fun sendMapNotifyEvent(event: XMapNotifyEvent)
    fun sendMapRequestEvent(event: XMapRequestEvent)
    fun sendFocusEvent(event: XFocusEvent)
    fun sendResizeRequestEvent(event: XResizeRequestEvent)
    fun sendConfigureRequestEvent(event: XConfigureRequestEvent)
    fun sendCreateNotifyEvent(event: XCreateNotifyEvent)
    fun sendDestroyNotifyEvent(event: XDestroyNotifyEvent)
    fun sendUnmapNotifyEvent(event: XUnmapNotifyEvent)
    fun sendReparentNotifyEvent(event: XReparentNotifyEvent)
    fun sendCirculateNotifyEvent(event: XCirculateNotifyEvent)
    fun sendCirculateRequestEvent(event: XCirculateRequestEvent)
    fun sendConfigureNotifyEvent(event: XConfigureNotifyEvent)
    fun sendPropertyNotifyEvent(event: XPropertyNotifyEvent)
    fun sendSelectionClearEvent(event: XSelectionClearEvent)
    fun sendSelectionRequestEvent(event: XSelectionRequestEvent)
    fun sendXFixesSelectionNotifyEvent(event: XXFixesSelectionNotifyEvent)
    fun sendXFixesCursorNotifyEvent(event: XXFixesCursorNotifyEvent)
    fun sendShapeNotifyEvent(event: XShapeNotifyEvent)
    fun sendSyncCounterNotifyEvent(event: XSyncCounterNotifyEvent)
    fun sendSyncAlarmNotifyEvent(event: XSyncAlarmNotifyEvent)
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

internal data class XKeyEvent(
    val type: XKeyEventType,
    val keycode: Int,
    val rootX: Int,
    val rootY: Int,
    val eventWindowId: Int,
    val childWindowId: Int,
    val eventX: Int,
    val eventY: Int,
    val state: Int,
    val time: Int,
)

internal enum class XKeyEventType(val code: Int) {
    KeyPress(2),
    KeyRelease(3),
}

internal data class XKeyDispatch(
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

internal data class XExposeEvent(
    val windowId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val count: Int = 0,
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

internal data class XMapRequestEvent(
    val parentId: Int,
    val windowId: Int,
)

internal data class XMapRequestDispatch(
    val sink: XEventSink,
    val event: XMapRequestEvent,
)

internal data class XFocusEvent(
    val type: XFocusEventType,
    val windowId: Int,
    val mode: Int = 0,
    val detail: Int = 3,
)

internal enum class XFocusEventType(val code: Int) {
    FocusIn(9),
    FocusOut(10),
}

internal data class XFocusDispatch(
    val sink: XEventSink,
    val event: XFocusEvent,
)

internal data class XResizeRequestEvent(
    val windowId: Int,
    val width: Int,
    val height: Int,
)

internal data class XResizeRequestDispatch(
    val sink: XEventSink,
    val event: XResizeRequestEvent,
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

internal data class XDestroyNotifyEvent(
    val eventWindowId: Int,
    val windowId: Int,
)

internal data class XDestroyNotifyDispatch(
    val sink: XEventSink,
    val event: XDestroyNotifyEvent,
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

internal data class XReparentNotifyEvent(
    val eventWindowId: Int,
    val windowId: Int,
    val parentId: Int,
    val x: Int,
    val y: Int,
    val overrideRedirect: Boolean = false,
)

internal data class XReparentNotifyDispatch(
    val sink: XEventSink,
    val event: XReparentNotifyEvent,
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

internal data class XCirculateRequestEvent(
    val parentId: Int,
    val windowId: Int,
    val place: Int,
)

internal data class XCirculateRequestDispatch(
    val sink: XEventSink,
    val event: XCirculateRequestEvent,
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

internal data class XConfigureRequestEvent(
    val parentId: Int,
    val windowId: Int,
    val siblingId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val stackMode: Int,
    val valueMask: Int,
)

internal data class XConfigureRequestDispatch(
    val sink: XEventSink,
    val event: XConfigureRequestEvent,
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

internal data class XXFixesSelectionNotifyEvent(
    val subtype: Int,
    val windowId: Int,
    val ownerWindowId: Int,
    val selection: Int,
    val timestamp: Int,
    val selectionTimestamp: Int,
)

internal data class XXFixesSelectionNotifyDispatch(
    val sink: XEventSink,
    val event: XXFixesSelectionNotifyEvent,
)

internal data class XXFixesCursorNotifyEvent(
    val subtype: Int,
    val windowId: Int,
    val cursorSerial: Int,
    val timestamp: Int,
    val name: Int = 0,
)

internal data class XXFixesCursorNotifyDispatch(
    val sink: XEventSink,
    val event: XXFixesCursorNotifyEvent,
)

internal data class XShapeNotifyEvent(
    val kind: Int,
    val windowId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val timestamp: Int,
    val shaped: Boolean,
)

internal data class XShapeNotifyDispatch(
    val sink: XEventSink,
    val event: XShapeNotifyEvent,
)

internal data class XSyntheticEvent(
    val bytes: ByteArray,
    val sourceByteOrder: ByteOrder,
)

internal object XEventMasks {
    const val ValidCoreMask = 0x01ff_ffff
    const val ValidDeviceEventMask = 0x0000_3f4f
    const val ValidPointerEventMask = 0x0000_7ffc
    const val KeyPress = 1 shl 0
    const val KeyRelease = 1 shl 1
    const val ButtonPress = 1 shl 2
    const val ButtonRelease = 1 shl 3
    const val PointerMotion = 1 shl 6
    const val Exposure = 1 shl 15
    const val StructureNotify = 1 shl 17
    const val ResizeRedirect = 1 shl 18
    const val SubstructureNotify = 1 shl 19
    const val SubstructureRedirect = 1 shl 20
    const val FocusChange = 1 shl 21
    const val PropertyChange = 1 shl 22

    fun forPointerType(type: XPointerEventType): Int =
        when (type) {
            XPointerEventType.ButtonPress -> ButtonPress
            XPointerEventType.ButtonRelease -> ButtonRelease
            XPointerEventType.MotionNotify -> PointerMotion
        }

    fun forKeyType(type: XKeyEventType): Int =
        when (type) {
            XKeyEventType.KeyPress -> KeyPress
            XKeyEventType.KeyRelease -> KeyRelease
        }
}
