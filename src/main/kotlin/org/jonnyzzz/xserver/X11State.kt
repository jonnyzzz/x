package org.jonnyzzz.xserver

internal class X11State(
    val width: Int,
    val height: Int,
) {
    private val windows = linkedMapOf<Int, XWindow>()
    private val pixmaps = linkedMapOf<Int, XPixmap>()
    private val gcs = linkedSetOf<Int>()
    private val fonts = linkedSetOf<Int>()
    private val cursors = linkedSetOf<Int>()
    private val colormaps = linkedSetOf(X11Ids.DefaultColormap)
    private val atomIds = linkedMapOf<String, Int>()
    private val atomNames = linkedMapOf<Int, String>()
    private var nextAtomId = 69
    private var focusWindowId: Int = X11Ids.RootWindow

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
            XWindowSnapshot(
                id = window.id,
                parentId = window.parentId,
                x = window.x,
                y = window.y,
                width = window.width,
                height = window.height,
                borderWidth = window.borderWidth,
                mapped = window.mapped,
                focused = window.id == focusWindowId,
                stackingIndex = index,
                label = window.label(),
            )
        }
        return XScreenSnapshot(
            width = width,
            height = height,
            focusWindowId = focusWindowId,
            windows = windowSnapshots,
            overlaps = overlaps(windowSnapshots),
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
    fun putGc(id: Int) {
        gcs += id
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

    private fun overlaps(windows: List<XWindowSnapshot>): List<XWindowOverlap> {
        val mapped = windows.filter { it.mapped && it.id != X11Ids.RootWindow }
        val result = mutableListOf<XWindowOverlap>()
        for (lowerIndex in mapped.indices) {
            for (upperIndex in lowerIndex + 1 until mapped.size) {
                val lower = mapped[lowerIndex]
                val upper = mapped[upperIndex]
                val left = maxOf(lower.x, upper.x)
                val top = maxOf(lower.y, upper.y)
                val right = minOf(lower.x + lower.width, upper.x + upper.width)
                val bottom = minOf(lower.y + lower.height, upper.y + upper.height)
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
}

internal data class XWindow(
    val id: Int,
    val parentId: Int,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var borderWidth: Int,
    var mapped: Boolean = false,
    val properties: MutableMap<Int, XProperty> = linkedMapOf(),
)

internal data class XPixmap(
    val id: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
)

internal data class XDrawable(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val depth: Int,
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
    val focusWindowId: Int,
    val windows: List<XWindowSnapshot>,
    val overlaps: List<XWindowOverlap>,
)

internal data class XWindowSnapshot(
    val id: Int,
    val parentId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val mapped: Boolean,
    val focused: Boolean,
    val stackingIndex: Int,
    val label: String,
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
