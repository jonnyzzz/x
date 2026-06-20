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
    }

    @Synchronized
    fun window(id: Int): XWindow? = windows[id]

    @Synchronized
    fun childrenOf(id: Int): List<XWindow> = windows.values.filter { it.parentId == id }

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
