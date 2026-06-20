package org.jonnyzzz.xserver

import kotlin.math.roundToInt

internal class X11State(
    val width: Int,
    val height: Int,
    val dpi: Int = 96,
) {
    val widthMillimeters: Int = pixelsToMillimeters(width, dpi)
    val heightMillimeters: Int = pixelsToMillimeters(height, dpi)
    private val windows = linkedMapOf<Int, XWindow>()
    private val pixmaps = linkedMapOf<Int, XPixmap>()
    private val gcs = linkedMapOf<Int, XGraphicsContext>()
    private val fonts = linkedSetOf<Int>()
    private val cursors = linkedSetOf<Int>()
    private val colormaps = linkedSetOf(X11Ids.DefaultColormap)
    private val atomIds = linkedMapOf<String, Int>()
    private val atomNames = linkedMapOf<Int, String>()
    private val eventSinks = linkedMapOf<XEventSink, MutableMap<Int, Int>>()
    private var nextAtomId = 69
    private var focusWindowId: Int = X11Ids.RootWindow
    private var pointerX: Int = 0
    private var pointerY: Int = 0
    private var pointerState: Int = 0
    private var inputTime: Int = 1
    private var nextInputOperationId: Int = 1
    private val inputOperations = mutableListOf<XInputOperation>()

    val extensions = listOf<XExtension>()

    init {
        putWindow(
            XWindow(
                id = X11Ids.RootWindow,
                parentId = 0,
                x = 0,
                y = 0,
                width = width,
                height = height,
                borderWidth = 0,
                mapped = true,
            ),
        )
        PredefinedAtoms.forEachIndexed { index, name ->
            val id = index + 1
            atomIds[name] = id
            atomNames[id] = name
        }
        window(X11Ids.RootWindow)?.properties?.put(
            atomIds.getValue("RESOURCE_MANAGER"),
            XProperty(
                type = atomIds.getValue("STRING"),
                format = 8,
                data = ByteArray(0),
            ),
        )
    }

    @Synchronized
    fun putWindow(window: XWindow) {
        windows[window.id] = window
    }

    @Synchronized
    fun removeWindow(id: Int) {
        windows.remove(id)
        windows.values.removeIf { it.parentId == id }
        if (focusWindowId == id) focusWindowId = X11Ids.RootWindow
    }

    @Synchronized
    fun window(id: Int): XWindow? = windows[id]

    @Synchronized
    fun childrenOf(id: Int): List<XWindow> = windows.values.filter { it.parentId == id }

    @Synchronized
    fun reparentWindow(id: Int, parentId: Int, x: Int, y: Int): XWindow? {
        val window = windows[id] ?: return null
        if (!windows.containsKey(parentId)) return null
        window.parentId = parentId
        window.x = x
        window.y = y
        return window
    }

    @Synchronized
    fun mapWindow(id: Int): XWindow? {
        val window = windows[id] ?: return null
        window.mapped = true
        focusWindowId = id
        return window
    }

    @Synchronized
    fun unmapWindow(id: Int) {
        windows[id]?.mapped = false
        if (focusWindowId == id) focusWindowId = X11Ids.RootWindow
    }

    @Synchronized
    fun registerEventSink(sink: XEventSink) {
        eventSinks.putIfAbsent(sink, linkedMapOf())
    }

    @Synchronized
    fun unregisterEventSink(sink: XEventSink) {
        eventSinks.remove(sink)
    }

    @Synchronized
    fun selectEvents(sink: XEventSink, windowId: Int, eventMask: Int) {
        if (!windows.containsKey(windowId)) return
        val selections = eventSinks.getOrPut(sink) { linkedMapOf() }
        if (eventMask == 0) {
            selections.remove(windowId)
        } else {
            selections[windowId] = eventMask
        }
    }

    fun pointerButton(x: Int, y: Int, button: Int, pressed: Boolean): XPointerDispatch {
        val deliveries = mutableListOf<Pair<XEventSink, XPointerEvent>>()
        val targetId: Int?
        synchronized(this) {
            pointerX = x.coerceIn(0, width - 1)
            pointerY = y.coerceIn(0, height - 1)
            val previousState = pointerState
            val type = if (pressed) XPointerEventType.ButtonPress else XPointerEventType.ButtonRelease
            val mask = XEventMasks.forPointerType(type)
            targetId = windowAt(pointerX, pointerY)?.id
            val path = targetId?.let { windowPathToRoot(it) }.orEmpty()
            val absoluteById = windows.values.associate { window -> window.id to absolutePosition(window) }
            val childByAncestor = childByAncestor(path)
            val time = inputTime++

            for ((sink, selections) in eventSinks) {
                for (window in path) {
                    val selectedMask = selections[window.id] ?: continue
                    if ((selectedMask and mask) == 0) continue
                    val absolute = absoluteById.getValue(window.id)
                    deliveries += sink to XPointerEvent(
                        type = type,
                        button = button,
                        rootX = pointerX,
                        rootY = pointerY,
                        eventWindowId = window.id,
                        childWindowId = childByAncestor[window.id] ?: 0,
                        eventX = pointerX - absolute.first,
                        eventY = pointerY - absolute.second,
                        state = previousState,
                        time = time,
                    )
                }
            }

            val buttonMask = buttonMask(button)
            pointerState = if (pressed) {
                pointerState or buttonMask
            } else {
                pointerState and buttonMask.inv()
            }
            if (targetId != null) focusWindowId = targetId
        }

        for ((sink, event) in deliveries) {
            sink.sendPointerEvent(event)
        }
        return XPointerDispatch(targetWindowId = targetId, deliveredEvents = deliveries.size)
    }

    @Synchronized
    fun recordInputOperation(
        kind: String,
        x: Int,
        y: Int,
        button: String,
        targetWindowId: Int?,
        deliveredEvents: Int,
    ) {
        inputOperations += XInputOperation(
            id = nextInputOperationId++,
            kind = kind,
            x = x,
            y = y,
            button = button,
            targetWindowId = targetWindowId,
            deliveredEvents = deliveredEvents,
        )
        if (inputOperations.size > MaxInputOperations) {
            inputOperations.removeAt(0)
        }
    }

    @Synchronized
    fun configureWindow(
        id: Int,
        x: Int? = null,
        y: Int? = null,
        width: Int? = null,
        height: Int? = null,
        borderWidth: Int? = null,
    ): XWindow? {
        val window = windows[id] ?: return null
        x?.let { window.x = it }
        y?.let { window.y = it }
        width?.let { window.width = it }
        height?.let { window.height = it }
        borderWidth?.let { window.borderWidth = it }
        return window
    }

    @Synchronized
    fun snapshot(): XScreenSnapshot {
        val windowSnapshots = windows.values.mapIndexed { index, window ->
            val absolute = absolutePosition(window)
            val visible = visibleBounds(window, absolute.first, absolute.second)
            XWindowSnapshot(
                id = window.id,
                parentId = window.parentId,
                x = absolute.first,
                y = absolute.second,
                localX = window.x,
                localY = window.y,
                width = window.width,
                height = window.height,
                borderWidth = window.borderWidth,
                mapped = window.mapped,
                focused = window.id == focusWindowId,
                stackingIndex = index,
                label = window.label(),
                visibleX = visible?.x ?: 0,
                visibleY = visible?.y ?: 0,
                visibleWidth = visible?.width ?: 0,
                visibleHeight = visible?.height ?: 0,
                backgroundPixel = window.backgroundPixel,
            )
        }
        return XScreenSnapshot(
            width = width,
            height = height,
            dpi = dpi,
            widthMillimeters = widthMillimeters,
            heightMillimeters = heightMillimeters,
            focusWindowId = focusWindowId,
            windows = windowSnapshots,
            overlaps = overlaps(windowSnapshots),
            drawings = drawings.toList(),
            inputOperations = inputOperations.toList(),
        )
    }

    @Synchronized
    fun drawable(id: Int): XDrawable? =
        windows[id]?.let { XDrawable(it.x, it.y, it.width, it.height, it.borderWidth, 24) }
            ?: pixmaps[id]?.let { XDrawable(0, 0, it.width, it.height, 0, it.depth) }

    @Synchronized
    fun putPixmap(pixmap: XPixmap) {
        pixmaps[pixmap.id] = pixmap
    }

    @Synchronized
    fun putPixmapImage(pixmapId: Int, image: XPixmapImage) {
        pixmaps[pixmapId]?.images?.add(image)
    }

    @Synchronized
    fun pixmapImageAt(pixmapId: Int, x: Int, y: Int, width: Int, height: Int): XPixmapImage? =
        pixmaps[pixmapId]?.images
            ?.asReversed()
            ?.firstOrNull { image ->
                image.x == x &&
                    image.y == y &&
                    image.width == width &&
                    image.height == height
            }
            ?: pixmaps[pixmapId]?.images?.lastOrNull()

    @Synchronized
    fun putGc(gc: XGraphicsContext) {
        gcs[gc.id] = gc
    }

    @Synchronized
    fun updateGc(
        id: Int,
        foreground: Int? = null,
        background: Int? = null,
        lineWidth: Int? = null,
        fontId: Int? = null,
    ) {
        val gc = gcs.getOrPut(id) { XGraphicsContext(id) }
        foreground?.let { gc.foreground = it }
        background?.let { gc.background = it }
        lineWidth?.let { gc.lineWidth = it }
        fontId?.let { gc.fontId = it }
    }

    @Synchronized
    fun gc(id: Int): XGraphicsContext = gcs[id] ?: XGraphicsContext(id)

    @Synchronized
    fun draw(command: XDrawingCommand) {
        drawings += command
        if (drawings.size > MaxDrawingCommands) {
            drawings.removeAt(0)
        }
    }

    @Synchronized
    fun putFont(id: Int) {
        fonts += id
    }

    @Synchronized
    fun putCursor(id: Int) {
        cursors += id
    }

    @Synchronized
    fun putColormap(id: Int) {
        colormaps += id
    }

    @Synchronized
    fun removeResource(id: Int) {
        pixmaps.remove(id)
        gcs.remove(id)
        fonts.remove(id)
        cursors.remove(id)
        colormaps.remove(id)
    }

    @Synchronized
    fun internAtom(name: String, onlyIfExists: Boolean): Int {
        atomIds[name]?.let { return it }
        if (onlyIfExists) return 0
        val id = nextAtomId++
        atomIds[name] = id
        atomNames[id] = name
        return id
    }

    @Synchronized
    fun atomName(id: Int): String? = atomNames[id]

    fun extension(name: String): XExtension? = extensions.firstOrNull { it.name == name }

    companion object {
        private const val MaxDrawingCommands = 10_000
        private const val MaxInputOperations = 200

        private fun pixelsToMillimeters(pixels: Int, dpi: Int): Int =
            ((pixels * 25.4) / dpi).roundToInt().coerceAtLeast(1)

        private val PredefinedAtoms = listOf(
            "PRIMARY",
            "SECONDARY",
            "ARC",
            "ATOM",
            "BITMAP",
            "CARDINAL",
            "COLORMAP",
            "CURSOR",
            "CUT_BUFFER0",
            "CUT_BUFFER1",
            "CUT_BUFFER2",
            "CUT_BUFFER3",
            "CUT_BUFFER4",
            "CUT_BUFFER5",
            "CUT_BUFFER6",
            "CUT_BUFFER7",
            "DRAWABLE",
            "FONT",
            "INTEGER",
            "PIXMAP",
            "POINT",
            "RECTANGLE",
            "RESOURCE_MANAGER",
            "RGB_COLOR_MAP",
            "RGB_BEST_MAP",
            "RGB_BLUE_MAP",
            "RGB_DEFAULT_MAP",
            "RGB_GRAY_MAP",
            "RGB_GREEN_MAP",
            "RGB_RED_MAP",
            "STRING",
            "VISUALID",
            "WINDOW",
            "WM_COMMAND",
            "WM_HINTS",
            "WM_CLIENT_MACHINE",
            "WM_ICON_NAME",
            "WM_ICON_SIZE",
            "WM_NAME",
            "WM_NORMAL_HINTS",
            "WM_SIZE_HINTS",
            "WM_ZOOM_HINTS",
            "MIN_SPACE",
            "NORM_SPACE",
            "MAX_SPACE",
            "END_SPACE",
            "SUPERSCRIPT_X",
            "SUPERSCRIPT_Y",
            "SUBSCRIPT_X",
            "SUBSCRIPT_Y",
            "UNDERLINE_POSITION",
            "UNDERLINE_THICKNESS",
            "STRIKEOUT_ASCENT",
            "STRIKEOUT_DESCENT",
            "ITALIC_ANGLE",
            "X_HEIGHT",
            "QUAD_WIDTH",
            "WEIGHT",
            "POINT_SIZE",
            "RESOLUTION",
            "COPYRIGHT",
            "NOTICE",
            "FONT_NAME",
            "FAMILY_NAME",
            "FULL_NAME",
            "CAP_HEIGHT",
            "WM_CLASS",
            "WM_TRANSIENT_FOR",
        )
    }

    private fun XWindow.label(): String {
        val wmName = atomIds["WM_NAME"] ?: return id.toHex()
        val string = atomIds["STRING"] ?: return id.toHex()
        val property = properties[wmName]
        if (property?.type == string && property.format == 8 && property.data.isNotEmpty()) {
            return property.data.decodeToString()
        }
        return if (id == X11Ids.RootWindow) "root" else id.toHex()
    }

    private fun absolutePosition(window: XWindow): Pair<Int, Int> {
        var x = window.x
        var y = window.y
        var parentId = window.parentId
        val visited = mutableSetOf(window.id)
        while (parentId != 0 && parentId != X11Ids.RootWindow && visited.add(parentId)) {
            val parent = windows[parentId] ?: break
            x += parent.x
            y += parent.y
            parentId = parent.parentId
        }
        return x to y
    }

    private fun visibleBounds(window: XWindow, absoluteX: Int, absoluteY: Int): XRectangle? {
        var left = absoluteX
        var top = absoluteY
        var right = absoluteX + window.width
        var bottom = absoluteY + window.height
        var parentId = window.parentId
        val visited = mutableSetOf(window.id)
        while (parentId != 0 && visited.add(parentId)) {
            val parent = windows[parentId] ?: break
            val parentAbsolute = absolutePosition(parent)
            left = maxOf(left, parentAbsolute.first)
            top = maxOf(top, parentAbsolute.second)
            right = minOf(right, parentAbsolute.first + parent.width)
            bottom = minOf(bottom, parentAbsolute.second + parent.height)
            parentId = parent.parentId
        }
        left = left.coerceIn(0, width)
        top = top.coerceIn(0, height)
        right = right.coerceIn(0, width)
        bottom = bottom.coerceIn(0, height)
        return if (right > left && bottom > top) {
            XRectangle(left, top, right - left, bottom - top)
        } else {
            null
        }
    }

    private fun overlaps(windows: List<XWindowSnapshot>): List<XWindowOverlap> {
        val mapped = windows.filter { it.mapped && it.id != X11Ids.RootWindow && it.visibleWidth > 0 && it.visibleHeight > 0 }
        val result = mutableListOf<XWindowOverlap>()
        for (lowerIndex in mapped.indices) {
            for (upperIndex in lowerIndex + 1 until mapped.size) {
                val lower = mapped[lowerIndex]
                val upper = mapped[upperIndex]
                val left = maxOf(lower.visibleX, upper.visibleX)
                val top = maxOf(lower.visibleY, upper.visibleY)
                val right = minOf(lower.visibleX + lower.visibleWidth, upper.visibleX + upper.visibleWidth)
                val bottom = minOf(lower.visibleY + lower.visibleHeight, upper.visibleY + upper.visibleHeight)
                if (right > left && bottom > top) {
                    result += XWindowOverlap(
                        lowerWindowId = lower.id,
                        upperWindowId = upper.id,
                        x = left,
                        y = top,
                        width = right - left,
                        height = bottom - top,
                    )
                }
            }
        }
        return result
    }

    private fun Int.toHex(): String = "0x${toUInt().toString(16)}"

    private fun windowAt(x: Int, y: Int): XWindow? =
        windows.values.toList()
            .asReversed()
            .firstOrNull { window ->
                window.mapped &&
                    visibleBounds(window, absolutePosition(window).first, absolutePosition(window).second)?.let { bounds ->
                        x >= bounds.x &&
                            y >= bounds.y &&
                            x < bounds.x + bounds.width &&
                            y < bounds.y + bounds.height
                    } == true
            }

    private fun windowPathToRoot(windowId: Int): List<XWindow> {
        val path = mutableListOf<XWindow>()
        var current = windows[windowId]
        val visited = mutableSetOf<Int>()
        while (current != null && visited.add(current.id)) {
            path += current
            current = windows[current.parentId]
        }
        return path
    }

    private fun childByAncestor(pathFromTarget: List<XWindow>): Map<Int, Int> {
        val result = linkedMapOf<Int, Int>()
        for (index in 1 until pathFromTarget.size) {
            result[pathFromTarget[index].id] = pathFromTarget[index - 1].id
        }
        return result
    }

    private fun buttonMask(button: Int): Int =
        if (button in 1..5) 1 shl (7 + button) else 0

    private val drawings = mutableListOf<XDrawingCommand>()

    private data class XRectangle(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

}

internal data class XWindow(
    val id: Int,
    var parentId: Int,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var borderWidth: Int,
    var mapped: Boolean = false,
    var backgroundPixel: Int = 0x00ff_ffff,
    val properties: MutableMap<Int, XProperty> = linkedMapOf(),
)

internal data class XPixmap(
    val id: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
) {
    val images: MutableList<XPixmapImage> = mutableListOf()
}

internal data class XPixmapImage(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val imageDataUri: String,
)

internal data class XDrawable(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val depth: Int,
)

internal data class XGraphicsContext(
    val id: Int,
    var foreground: Int = 0,
    var background: Int = 0x00ff_ffff,
    var lineWidth: Int = 1,
    var fontId: Int = 0,
)

internal enum class XDrawingKind {
    Clear,
    Line,
    Segment,
    Rectangle,
    FillRectangle,
    Arc,
    FillArc,
    Text,
    PutImage,
}

internal data class XPoint(
    val x: Int,
    val y: Int,
)

internal data class XDrawingCommand(
    val drawableId: Int,
    val kind: XDrawingKind,
    val foreground: Int,
    val background: Int = 0x00ff_ffff,
    val lineWidth: Int = 1,
    val points: List<XPoint> = emptyList(),
    val rectangles: List<XRectangleCommand> = emptyList(),
    val text: String = "",
    val imageDataUri: String? = null,
)

internal data class XRectangleCommand(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class XProperty(
    val type: Int,
    val format: Int,
    val data: ByteArray,
)

internal data class XExtension(
    val name: String,
    val majorOpcode: Int,
    val firstEvent: Int,
    val firstError: Int,
)

internal data class XScreenSnapshot(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val widthMillimeters: Int,
    val heightMillimeters: Int,
    val focusWindowId: Int,
    val windows: List<XWindowSnapshot>,
    val overlaps: List<XWindowOverlap>,
    val drawings: List<XDrawingCommand>,
    val inputOperations: List<XInputOperation>,
)

internal data class XInputOperation(
    val id: Int,
    val kind: String,
    val x: Int,
    val y: Int,
    val button: String,
    val targetWindowId: Int?,
    val deliveredEvents: Int,
) {
    val targetWindowIdHex: String? get() = targetWindowId?.let { "0x${it.toUInt().toString(16)}" }
}

internal data class XWindowSnapshot(
    val id: Int,
    val parentId: Int,
    val x: Int,
    val y: Int,
    val localX: Int,
    val localY: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val mapped: Boolean,
    val focused: Boolean,
    val stackingIndex: Int,
    val label: String,
    val visibleX: Int,
    val visibleY: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val backgroundPixel: Int,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val parentIdHex: String get() = "0x${parentId.toUInt().toString(16)}"
}

internal data class XWindowOverlap(
    val lowerWindowId: Int,
    val upperWindowId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val lowerWindowIdHex: String get() = "0x${lowerWindowId.toUInt().toString(16)}"
    val upperWindowIdHex: String get() = "0x${upperWindowId.toUInt().toString(16)}"
}
