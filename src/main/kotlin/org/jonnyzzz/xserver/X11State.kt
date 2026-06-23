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
    private val pictures = linkedMapOf<Int, XPicture>()
    private val glyphSets = linkedMapOf<Int, XGlyphSet>()
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
    private val glxContexts = linkedMapOf<Int, XGlxContext>()
    private var nextGlxOperationId: Int = 1
    private val glxOperations = mutableListOf<XGlxOperation>()
    private var nextRenderOperationId: Int = 1
    private val renderOperations = mutableListOf<XRenderOperation>()
    private val requestCounts = linkedMapOf<String, Int>()
    private val extensionQueries = mutableListOf<XExtensionQuery>()
    private var nextExtensionQueryId: Int = 1
    private val unsupportedRequests = mutableListOf<XUnsupportedRequest>()
    private var nextUnsupportedRequestId: Int = 1

    val extensions = listOf(
        XExtension(
            name = "GLX",
            majorOpcode = XGlx.MajorOpcode,
            firstEvent = XGlx.FirstEvent,
            firstError = XGlx.FirstError,
            aliases = setOf("SGI-GLX"),
        ),
        XExtension(
            name = "BIG-REQUESTS",
            majorOpcode = XBigRequests.MajorOpcode,
            firstEvent = XBigRequests.FirstEvent,
            firstError = XBigRequests.FirstError,
        ),
        XExtension(
            name = "RENDER",
            majorOpcode = XRender.MajorOpcode,
            firstEvent = XRender.FirstEvent,
            firstError = XRender.FirstError,
        ),
    )

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
    fun removeWindow(id: Int): Set<Int> {
        val removed = windowSubtreeIds(id)
        if (removed.isEmpty()) return emptySet()
        for (windowId in removed) {
            windows.remove(windowId)
        }
        removeEventSelections(removed)
        if (focusWindowId in removed) focusWindowId = X11Ids.RootWindow
        return removed
    }

    @Synchronized
    fun removeClientResources(resourceIds: Set<Int>) {
        if (resourceIds.isEmpty()) return
        val removedWindows = linkedSetOf<Int>()
        for (id in resourceIds) {
            if (id != X11Ids.RootWindow && windows.containsKey(id)) {
                removedWindows += removeWindow(id)
            }
        }
        val ids = resourceIds - removedWindows
        for (id in ids) {
            pixmaps.remove(id)
            gcs.remove(id)
            fonts.remove(id)
            cursors.remove(id)
            if (id != X11Ids.DefaultColormap) colormaps.remove(id)
            glxContexts.remove(id)
            pictures.remove(id)
            glyphSets.remove(id)
        }
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
        if (width != null || height != null) {
            window.framebuffer.resize(window.width, window.height, window.backgroundPixel)
        }
        return window
    }

    @Synchronized
    fun updateWindowAttributes(
        id: Int,
        backgroundPixel: Int? = null,
        backgroundPixmapId: Int? = null,
    ): XWindow? {
        val window = windows[id] ?: return null
        backgroundPixel?.let {
            window.backgroundPixel = it
            window.backgroundPixmapId = null
        }
        if (backgroundPixmapId != null) {
            window.backgroundPixmapId = backgroundPixmapId.takeIf { it != 0 }
        }
        return window
    }

    @Synchronized
    fun paintWindowBackground(windowId: Int, rectangle: XRectangleCommand? = null): Boolean {
        val window = windows[windowId] ?: return false
        val target = rectangle ?: XRectangleCommand(0, 0, window.width, window.height)
        val backgroundPixmap = window.backgroundPixmapId?.let { pixmaps[it] }
        if (backgroundPixmap != null) {
            return backgroundPixmap.framebuffer.tileTo(
                destination = window.framebuffer,
                destinationX = target.x,
                destinationY = target.y,
                width = target.width,
                height = target.height,
            )
        }
        return window.framebuffer.fill(target.x, target.y, target.width, target.height, window.backgroundPixel)
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
                framebufferDataUri = window.framebuffer.toDataUri(),
            )
        }
        val pixmapSnapshots = pixmaps.values.map { pixmap ->
            XPixmapSnapshot(
                id = pixmap.id,
                width = pixmap.width,
                height = pixmap.height,
                depth = pixmap.depth,
                painted = pixmap.framebuffer.hasPaintedContent(),
                framebufferDataUri = pixmap.framebuffer.toDataUri(),
                pictureIds = pictures.values
                    .filter { it.drawableId == pixmap.id }
                    .map { it.id },
                matchingWindowIds = windowSnapshots
                    .filter { it.mapped && it.width == pixmap.width && it.height == pixmap.height }
                    .map { it.id },
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
            pixmaps = pixmapSnapshots,
            overlaps = overlaps(windowSnapshots),
            drawings = drawings.toList(),
            inputOperations = inputOperations.toList(),
            glxOperations = glxOperations.toList(),
            renderOperations = renderOperations.toList(),
            renderPictures = pictures.values.map { picture ->
                XRenderPictureSnapshot(
                    id = picture.id,
                    drawableId = picture.drawableId,
                    drawableKind = picture.drawableId?.let { drawableId ->
                        when {
                            windows.containsKey(drawableId) -> "window"
                            pixmaps.containsKey(drawableId) -> "pixmap"
                            else -> "missing"
                        }
                    } ?: "solid",
                    format = picture.format,
                    solidPixel = picture.solidPixel,
                    clipRectangles = picture.clipRectangles.size,
                )
            },
            requestCounts = requestCounts.toList().map { XRequestCount(it.first, it.second) },
            extensionQueries = extensionQueries.toList(),
            unsupportedRequests = unsupportedRequests.toList(),
        )
    }

    @Synchronized
    fun recordRequest(name: String) {
        requestCounts[name] = (requestCounts[name] ?: 0) + 1
        if (requestCounts.size > MaxRequestCounts) {
            val first = requestCounts.keys.firstOrNull()
            if (first != null) requestCounts.remove(first)
        }
    }

    @Synchronized
    fun recordExtensionQuery(name: String, supported: Boolean) {
        extensionQueries += XExtensionQuery(
            id = nextExtensionQueryId++,
            name = name,
            supported = supported,
        )
        if (extensionQueries.size > MaxExtensionQueries) {
            extensionQueries.removeAt(0)
        }
    }

    @Synchronized
    fun recordUnsupportedRequest(opcode: Int, minorOpcode: Int, name: String) {
        unsupportedRequests += XUnsupportedRequest(
            id = nextUnsupportedRequestId++,
            opcode = opcode,
            minorOpcode = minorOpcode,
            name = name,
        )
        if (unsupportedRequests.size > MaxUnsupportedRequests) {
            unsupportedRequests.removeAt(0)
        }
    }

    @Synchronized
    fun putGlxContext(context: XGlxContext) {
        glxContexts[context.id] = context
    }

    @Synchronized
    fun removeGlxContext(id: Int) {
        glxContexts.remove(id)
    }

    @Synchronized
    fun glxContext(id: Int): XGlxContext? = glxContexts[id]

    @Synchronized
    fun recordGlxOperation(
        minorOpcode: Int,
        operation: String,
        detail: String = "",
    ) {
        glxOperations += XGlxOperation(
            id = nextGlxOperationId++,
            minorOpcode = minorOpcode,
            operation = operation,
            detail = detail,
        )
        if (glxOperations.size > MaxGlxOperations) {
            glxOperations.removeAt(0)
        }
    }

    @Synchronized
    fun putPicture(picture: XPicture) {
        pictures[picture.id] = picture
    }

    @Synchronized
    fun updatePicture(id: Int, valueMask: Int) {
        pictures[id]?.valueMask = valueMask
    }

    @Synchronized
    fun updatePictureClip(id: Int, rectangles: List<XRectangleCommand>) {
        pictures[id]?.clipRectangles = rectangles
    }

    @Synchronized
    fun removePicture(id: Int) {
        pictures.remove(id)
    }

    @Synchronized
    fun picture(id: Int): XPicture? = pictures[id]

    @Synchronized
    fun putGlyphSet(glyphSet: XGlyphSet) {
        glyphSets[glyphSet.id] = glyphSet
    }

    @Synchronized
    fun referenceGlyphSet(id: Int, existingId: Int) {
        val existing = glyphSets[existingId] ?: return
        glyphSets[id] = existing.copy(id = id)
    }

    @Synchronized
    fun removeGlyphSet(id: Int) {
        glyphSets.remove(id)
    }

    @Synchronized
    fun glyphSetFormat(id: Int): Int? = glyphSets[id]?.format

    @Synchronized
    fun glyph(glyphSetId: Int, glyphId: Int): XGlyph? = glyphSets[glyphSetId]?.glyphs?.get(glyphId)

    @Synchronized
    fun addGlyphs(glyphSetId: Int, glyphs: List<XGlyph>) {
        val glyphSet = glyphSets[glyphSetId] ?: return
        for (glyph in glyphs) {
            glyphSet.glyphs[glyph.id] = glyph
        }
    }

    @Synchronized
    fun removeGlyphs(glyphSetId: Int, glyphIds: List<Int>) {
        val glyphSet = glyphSets[glyphSetId] ?: return
        for (id in glyphIds) {
            glyphSet.glyphs.remove(id)
        }
    }

    @Synchronized
    fun recordRenderOperation(
        minorOpcode: Int,
        operation: String,
        detail: String = "",
    ) {
        renderOperations += XRenderOperation(
            id = nextRenderOperationId++,
            minorOpcode = minorOpcode,
            operation = operation,
            detail = detail,
        )
        if (renderOperations.size > MaxRenderOperations) {
            renderOperations.removeAt(0)
        }
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
    fun putImage(
        drawableId: Int,
        x: Int,
        y: Int,
        image: XImagePixels,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        return framebuffer.putImage(x, y, image, clipRectangles, function, planeMask)
    }

    @Synchronized
    fun copyArea(
        sourceDrawableId: Int,
        destinationDrawableId: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): XImagePixels? {
        val source = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val destination = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return null
        return source.copyAreaTo(
            destination = destination,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            clipRectangles = clipRectangles,
            function = function,
            planeMask = planeMask,
        )
    }

    @Synchronized
    fun copyPlane(
        sourceDrawableId: Int,
        destinationDrawableId: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        bitPlane: Int,
        foreground: Int,
        background: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): XImagePixels? {
        val source = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val destination = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return null
        return source.copyPlaneTo(
            destination = destination,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            bitPlane = bitPlane,
            foreground = foreground,
            background = background,
            clipRectangles = clipRectangles,
            function = function,
            planeMask = planeMask,
        )
    }

    @Synchronized
    fun composite(
        operation: Int,
        source: XPicture,
        mask: XPicture?,
        destination: XPicture,
        sourceX: Int,
        sourceY: Int,
        maskX: Int,
        maskY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
    ): XImagePixels? {
        val destinationDrawableId = destination.drawableId ?: return null
        val destinationFramebuffer = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return null
        val maskFramebuffer = mask?.drawableId?.let { windows[it]?.framebuffer ?: pixmaps[it]?.framebuffer }
        val solid = source.solidPixel
        if (solid != null) {
            return when (operation) {
                XRender.OpClear -> {
                    destinationFramebuffer.fill(destinationX, destinationY, width, height, 0, preserveAlpha = true)
                    XImagePixels(width, height, IntArray(width * height))
                }
                XRender.OpSrc -> {
                    if (maskFramebuffer == null && destination.clipRectangles.isEmpty()) {
                        destinationFramebuffer.fill(destinationX, destinationY, width, height, solid, preserveAlpha = true)
                    } else {
                        destinationFramebuffer.copyFrom(
                            source = XFramebuffer(width, height, painted = true).also { it.fill(0, 0, width, height, solid, preserveAlpha = true) },
                            sourceX = 0,
                            sourceY = 0,
                            destinationX = destinationX,
                            destinationY = destinationY,
                            width = width,
                            height = height,
                            operation = XRender.OpSrc,
                            clipRectangles = destination.clipRectangles.takeIf { it.isNotEmpty() },
                            mask = maskFramebuffer,
                            maskX = maskX,
                            maskY = maskY,
                        )
                    }
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                else -> {
                    destinationFramebuffer.blendSolidOver(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = destination.clipRectangles.takeIf { it.isNotEmpty() },
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
            }
        }
        val sourceDrawableId = source.drawableId ?: return null
        val sourceFramebuffer = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        return destinationFramebuffer.copyFrom(
            source = sourceFramebuffer,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            operation = operation,
            clipRectangles = destination.clipRectangles.takeIf { it.isNotEmpty() },
            mask = maskFramebuffer,
            maskX = maskX,
            maskY = maskY,
        )
    }

    @Synchronized
    fun getImage(
        drawableId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): XImagePixels? {
        if (width <= 0 || height <= 0) return XImagePixels(0, 0, IntArray(0))
        if (width.toLong() * height.toLong() > MaxGetImagePixels) return null
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return null
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                pixels[row * width + column] = framebuffer.pixelAt(x + column, y + row) ?: 0
            }
        }
        return XImagePixels(width, height, pixels)
    }

    @Synchronized
    fun fillRectangles(
        drawableId: Int,
        pixel: Int,
        rectangles: List<XRectangleCommand>,
        preserveAlpha: Boolean = false,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        for (rectangle in rectangles) {
            painted = framebuffer.fill(rectangle.x, rectangle.y, rectangle.width, rectangle.height, pixel, preserveAlpha, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun drawPoints(
        drawableId: Int,
        pixel: Int,
        points: List<XPoint>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        for (point in points) {
            painted = framebuffer.drawPoint(point.x, point.y, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun drawPolyline(
        drawableId: Int,
        pixel: Int,
        points: List<XPoint>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            painted = framebuffer.drawLine(start.x, start.y, end.x, end.y, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun drawSegments(
        drawableId: Int,
        pixel: Int,
        points: List<XPoint>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        var index = 0
        while (index + 1 < points.size) {
            val start = points[index]
            val end = points[index + 1]
            painted = framebuffer.drawLine(start.x, start.y, end.x, end.y, pixel, lineWidth, clipRectangles, function, planeMask) || painted
            index += 2
        }
        return painted
    }

    @Synchronized
    fun drawRectangleOutlines(
        drawableId: Int,
        pixel: Int,
        rectangles: List<XRectangleCommand>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        for (rectangle in rectangles) {
            painted = framebuffer.drawRectangleOutline(rectangle.x, rectangle.y, rectangle.width, rectangle.height, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun drawArcs(
        drawableId: Int,
        pixel: Int,
        arcs: List<XArcCommand>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        for (arc in arcs) {
            painted = framebuffer.drawArc(arc, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun fillArcs(
        drawableId: Int,
        pixel: Int,
        arcs: List<XArcCommand>,
        arcMode: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        for (arc in arcs) {
            painted = framebuffer.fillArc(arc, pixel, arcMode, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun fillPolygon(
        drawableId: Int,
        pixel: Int,
        points: List<XPoint>,
        fillRule: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        return framebuffer.fillPolygon(points, pixel, fillRule, clipRectangles, function, planeMask)
    }

    @Synchronized
    fun drawText(
        drawableId: Int,
        x: Int,
        baselineY: Int,
        text: String,
        foreground: Int,
        background: Int? = null,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        return framebuffer.drawText(
            x = x,
            baselineY = baselineY,
            text = text,
            foreground = foreground,
            background = background,
            clipRectangles = clipRectangles,
            function = function,
            planeMask = planeMask,
        )
    }

    @Synchronized
    fun renderFillRectangles(
        operation: Int,
        destination: XPicture,
        pixel: Int,
        rectangles: List<XRectangleCommand>,
    ): Boolean {
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        var painted = false
        for (rectangle in rectangles) {
            painted = when (operation) {
                XRender.OpClear -> framebuffer.fill(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 0, preserveAlpha = true)
                XRender.OpSrc -> framebuffer.fill(rectangle.x, rectangle.y, rectangle.width, rectangle.height, pixel, preserveAlpha = true)
                XRender.OpOver -> framebuffer.blendSolidOver(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = destination.clipRectangles.takeIf { it.isNotEmpty() },
                )
                else -> framebuffer.blendSolidOver(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = destination.clipRectangles.takeIf { it.isNotEmpty() },
                )
            } || painted
        }
        return painted
    }

    @Synchronized
    fun compositeGlyphs(
        operation: Int,
        source: XPicture,
        destination: XPicture,
        glyphSetId: Int,
        placements: List<XGlyphPlacement>,
    ): Boolean {
        val destinationDrawableId = destination.drawableId ?: return false
        val destinationFramebuffer = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return false
        val glyphSet = glyphSets[glyphSetId] ?: return false
        val sourceFramebuffer = source.drawableId?.let { windows[it]?.framebuffer ?: pixmaps[it]?.framebuffer }
        val sourcePixel = source.solidPixel ?: sourceFramebuffer?.firstPaintedPixel() ?: return false
        var painted = false
        for (placement in placements) {
            val glyph = glyphSet.glyphs[placement.glyphId] ?: continue
            val mask = glyph.mask ?: continue
            val destinationX = placement.x - glyph.x
            val destinationY = placement.y - glyph.y
            painted = destinationFramebuffer.blendSolidOver(
                pixel = sourcePixel,
                destinationX = destinationX,
                destinationY = destinationY,
                width = glyph.width,
                height = glyph.height,
                clipRectangles = destination.clipRectangles.takeIf { it.isNotEmpty() },
                mask = mask,
            ) || painted
        }
        return painted
    }

    @Synchronized
    fun putGc(gc: XGraphicsContext) {
        gcs[gc.id] = gc
    }

    @Synchronized
    fun hasGc(id: Int): Boolean = gcs.containsKey(id)

    @Synchronized
    fun hasResource(id: Int): Boolean =
        windows.containsKey(id) ||
            pixmaps.containsKey(id) ||
            gcs.containsKey(id) ||
            fonts.contains(id) ||
            cursors.contains(id) ||
            colormaps.contains(id) ||
            pictures.containsKey(id) ||
            glyphSets.containsKey(id) ||
            glxContexts.containsKey(id)

    @Synchronized
    fun updateGc(
        id: Int,
        foreground: Int? = null,
        background: Int? = null,
        lineWidth: Int? = null,
        function: Int? = null,
        planeMask: Int? = null,
        fontId: Int? = null,
        clipXOrigin: Int? = null,
        clipYOrigin: Int? = null,
        fillRule: Int? = null,
        arcMode: Int? = null,
    ) {
        val gc = gcs.getOrPut(id) { XGraphicsContext(id) }
        foreground?.let { gc.foreground = it }
        background?.let { gc.background = it }
        lineWidth?.let { gc.lineWidth = it }
        function?.let { gc.function = it }
        planeMask?.let { gc.planeMask = it }
        fontId?.let { gc.fontId = it }
        clipXOrigin?.let { gc.clipXOrigin = it }
        clipYOrigin?.let { gc.clipYOrigin = it }
        fillRule?.let { gc.fillRule = it }
        arcMode?.let { gc.arcMode = it }
    }

    @Synchronized
    fun updateGcClip(
        id: Int,
        clipXOrigin: Int? = null,
        clipYOrigin: Int? = null,
        clipRectangles: List<XRectangleCommand>? = null,
    ) {
        val gc = gcs.getOrPut(id) { XGraphicsContext(id) }
        clipXOrigin?.let { gc.clipXOrigin = it }
        clipYOrigin?.let { gc.clipYOrigin = it }
        gc.clipRectangles = clipRectangles
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
        glxContexts.remove(id)
        pictures.remove(id)
        glyphSets.remove(id)
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

    fun extension(name: String): XExtension? = extensions.firstOrNull { it.name == name || name in it.aliases }

    fun extensionByMajorOpcode(majorOpcode: Int): XExtension? = extensions.firstOrNull { it.majorOpcode == majorOpcode }

    companion object {
        private const val MaxDrawingCommands = 10_000
        private const val MaxInputOperations = 200
        private const val MaxGlxOperations = 200
        private const val MaxRenderOperations = 400
        private const val MaxGetImagePixels = 16_777_216
        private const val MaxRequestCounts = 256
        private const val MaxExtensionQueries = 200
        private const val MaxUnsupportedRequests = 200

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

    private fun windowSubtreeIds(rootId: Int): Set<Int> {
        if (rootId == X11Ids.RootWindow || !windows.containsKey(rootId)) return emptySet()
        val removed = linkedSetOf(rootId)
        var changed: Boolean
        do {
            changed = false
            for (window in windows.values) {
                if (window.parentId in removed && removed.add(window.id)) {
                    changed = true
                }
            }
        } while (changed)
        return removed
    }

    private fun removeEventSelections(windowIds: Set<Int>) {
        for (selections in eventSinks.values) {
            for (windowId in windowIds) {
                selections.remove(windowId)
            }
        }
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
    var backgroundPixmapId: Int? = null,
    val properties: MutableMap<Int, XProperty> = linkedMapOf(),
    val framebuffer: XFramebuffer = XFramebuffer(width, height, backgroundPixel),
)

internal data class XPixmap(
    val id: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val framebuffer: XFramebuffer = XFramebuffer(width, height),
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
    var function: Int = GXcopy,
    var planeMask: Int = -1,
    var fontId: Int = 0,
) {
    var clipXOrigin: Int = 0
    var clipYOrigin: Int = 0
    var clipRectangles: List<XRectangleCommand>? = null
    var fillRule: Int = EvenOddRule
    var arcMode: Int = ArcPieSlice

    fun effectiveClipRectangles(): List<XRectangleCommand>? =
        clipRectangles?.map { rectangle ->
            XRectangleCommand(
                x = rectangle.x + clipXOrigin,
                y = rectangle.y + clipYOrigin,
                width = rectangle.width,
                height = rectangle.height,
            )
        }

    companion object {
        const val GXclear = 0x0
        const val GXand = 0x1
        const val GXandReverse = 0x2
        const val GXcopy = 0x3
        const val GXandInverted = 0x4
        const val GXnoop = 0x5
        const val GXxor = 0x6
        const val GXor = 0x7
        const val GXnor = 0x8
        const val GXequiv = 0x9
        const val GXinvert = 0xa
        const val GXorReverse = 0xb
        const val GXcopyInverted = 0xc
        const val GXorInverted = 0xd
        const val GXnand = 0xe
        const val GXset = 0xf
        const val EvenOddRule = 0
        const val WindingRule = 1
        const val ArcChord = 0
        const val ArcPieSlice = 1
    }
}

internal data class XPicture(
    val id: Int,
    val drawableId: Int?,
    val format: Int,
    var valueMask: Int = 0,
    val solidPixel: Int? = null,
    var clipRectangles: List<XRectangleCommand> = emptyList(),
)

internal data class XGlyphSet(
    val id: Int,
    val format: Int,
    val glyphs: MutableMap<Int, XGlyph> = linkedMapOf(),
)

internal data class XGlyph(
    val id: Int,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val xOff: Int,
    val yOff: Int,
    val mask: XFramebuffer? = null,
)

internal data class XGlyphPlacement(
    val glyphId: Int,
    val x: Int,
    val y: Int,
)

internal enum class XDrawingKind {
    Clear,
    Line,
    Segment,
    Rectangle,
    FillPoly,
    FillRectangle,
    Arc,
    FillArc,
    Text,
    PutImage,
    CopyArea,
    CopyPlane,
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
    val arcs: List<XArcCommand> = emptyList(),
    val text: String = "",
    val imageDataUri: String? = null,
    val sourceDrawableId: Int? = null,
    val framebufferBacked: Boolean = false,
)

internal data class XRectangleCommand(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class XArcCommand(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val angle1: Int,
    val angle2: Int,
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
    val aliases: Set<String> = emptySet(),
)

internal data class XScreenSnapshot(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val widthMillimeters: Int,
    val heightMillimeters: Int,
    val focusWindowId: Int,
    val windows: List<XWindowSnapshot>,
    val pixmaps: List<XPixmapSnapshot>,
    val overlaps: List<XWindowOverlap>,
    val drawings: List<XDrawingCommand>,
    val inputOperations: List<XInputOperation>,
    val glxOperations: List<XGlxOperation>,
    val renderOperations: List<XRenderOperation>,
    val renderPictures: List<XRenderPictureSnapshot>,
    val requestCounts: List<XRequestCount>,
    val extensionQueries: List<XExtensionQuery>,
    val unsupportedRequests: List<XUnsupportedRequest>,
)

internal data class XRequestCount(
    val name: String,
    val count: Int,
)

internal data class XExtensionQuery(
    val id: Int,
    val name: String,
    val supported: Boolean,
)

internal data class XUnsupportedRequest(
    val id: Int,
    val opcode: Int,
    val minorOpcode: Int,
    val name: String,
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

internal data class XGlxContext(
    val id: Int,
    val fbConfigId: Int,
    val screen: Int,
    val renderType: Int,
    val direct: Boolean,
)

internal data class XGlxOperation(
    val id: Int,
    val minorOpcode: Int,
    val operation: String,
    val detail: String,
)

internal data class XRenderOperation(
    val id: Int,
    val minorOpcode: Int,
    val operation: String,
    val detail: String,
)

internal data class XRenderPictureSnapshot(
    val id: Int,
    val drawableId: Int?,
    val drawableKind: String,
    val format: Int,
    val solidPixel: Int?,
    val clipRectangles: Int,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val drawableIdHex: String get() = drawableId?.let { "0x${it.toUInt().toString(16)}" } ?: "none"
}

internal data class XPixmapSnapshot(
    val id: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val painted: Boolean,
    val framebufferDataUri: String?,
    val pictureIds: List<Int>,
    val matchingWindowIds: List<Int>,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val pictureIdHexes: List<String> get() = pictureIds.map { "0x${it.toUInt().toString(16)}" }
    val matchingWindowIdHexes: List<String> get() = matchingWindowIds.map { "0x${it.toUInt().toString(16)}" }
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
    val framebufferDataUri: String?,
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
