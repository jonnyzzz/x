package org.jonnyzzz.xserver

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal class X11Connection(
    private val input: InputStream,
    private val output: OutputStream,
    private val state: X11State,
) : XEventSink {
    private lateinit var byteOrder: ByteOrder
    private var sequence = 0
    private val trace = java.lang.Boolean.getBoolean("x.trace")
    private val writeLock = Any()

    fun run() {
        try {
            val setupPrefix = input.readExactly(12)
            byteOrder = ByteOrder.fromSetupByte(setupPrefix[0].toInt() and 0xff)
            val major = byteOrder.u16(setupPrefix, 2)
            val minor = byteOrder.u16(setupPrefix, 4)
            val authNameLength = byteOrder.u16(setupPrefix, 6)
            val authDataLength = byteOrder.u16(setupPrefix, 8)

            val authNamePad = paddedLength(authNameLength)
            val authDataPad = paddedLength(authDataLength)
            if (authNamePad > 0) input.readExactly(authNamePad)
            if (authDataPad > 0) input.readExactly(authDataPad)

            val reply = SetupReply.success(
                byteOrder = byteOrder,
                clientMajor = major,
                clientMinor = minor,
                width = state.width,
                height = state.height,
                widthMillimeters = state.widthMillimeters,
                heightMillimeters = state.heightMillimeters,
            )
            write(reply)
            state.registerEventSink(this)

            while (true) {
                val header = input.readOrNull(4) ?: return
                val opcode = header[0].toInt() and 0xff
                val minorOpcode = header[1].toInt() and 0xff
                val units = byteOrder.u16(header, 2)
                if (units == 0) {
                    writeError(error = 1, opcode = opcode, minorOpcode = minorOpcode, badValue = 0)
                    return
                }
                val body = input.readExactly(units * 4 - 4)
                sequence = (sequence + 1) and 0xffff
                if (trace) {
                    System.err.println("x11 seq=$sequence opcode=$opcode minor=$minorOpcode units=$units body=${body.size}")
                }
                dispatch(opcode, minorOpcode, body)
            }
        } finally {
            state.unregisterEventSink(this)
        }
    }

    private fun dispatch(opcode: Int, minorOpcode: Int, body: ByteArray) {
        when (opcode) {
            1 -> createWindow(body)
            2 -> changeWindowAttributes(body)
            3 -> getWindowAttributes(body)
            4 -> destroyWindow(body)
            6 -> unitReplyless()
            7 -> reparentWindow(body)
            8 -> mapWindow(body)
            9 -> mapSubwindows(body)
            10 -> unmapWindow(body)
            11 -> unmapSubwindows(body)
            12 -> configureWindow(body)
            13 -> unitReplyless()
            14 -> getGeometry(body)
            15 -> queryTree(body)
            16 -> internAtom(minorOpcode, body)
            17 -> getAtomName(body)
            18 -> changeProperty(minorOpcode, body)
            19 -> deleteProperty(body)
            20 -> getProperty(minorOpcode, body)
            21 -> listProperties(body)
            22 -> unitReplyless()
            23 -> getSelectionOwner()
            25 -> unitReplyless()
            38 -> queryPointer()
            40 -> translateCoordinates(body)
            42 -> unitReplyless()
            43 -> getInputFocus()
            44 -> queryKeymap()
            45 -> openFont(body)
            46 -> closeResource(body)
            47 -> queryFont(body)
            49 -> listFonts()
            52 -> getFontPath()
            53 -> createPixmap(minorOpcode, body)
            54 -> closeResource(body)
            55 -> createGc(body)
            56 -> changeGc(body)
            60 -> closeResource(body)
            61 -> clearArea(body)
            62 -> copyArea(body)
            63 -> copyArea(body)
            64 -> polyPoint(minorOpcode, body)
            65 -> polyLine(minorOpcode, body)
            66 -> polySegment(body)
            67 -> polyRectangle(body, XDrawingKind.Rectangle)
            68 -> polyRectangle(body, XDrawingKind.Arc)
            69 -> fillPoly(body)
            70 -> polyRectangle(body, XDrawingKind.FillRectangle)
            71 -> polyRectangle(body, XDrawingKind.FillArc)
            72 -> putImage(minorOpcode, body)
            73 -> getImage(body)
            74 -> polyText(body, is16Bit = false)
            75 -> polyText(body, is16Bit = true)
            76 -> imageText(minorOpcode, body, is16Bit = false)
            77 -> imageText(minorOpcode, body, is16Bit = true)
            78 -> createColormap(body)
            79 -> closeResource(body)
            81 -> unitReplyless()
            83 -> listInstalledColormaps()
            84 -> allocColor(body)
            85 -> lookupColor()
            91 -> queryColors(body)
            93 -> createCursor(body)
            94 -> createCursor(body)
            95 -> closeResource(body)
            96 -> unitReplyless()
            97 -> queryBestSize(body)
            98 -> queryExtension(body)
            99 -> listExtensions()
            101 -> getKeyboardMapping(body)
            103 -> getKeyboardControl()
            105 -> unitReplyless()
            106 -> getPointerControl()
            107 -> unitReplyless()
            108 -> getScreenSaver()
            112 -> unitReplyless()
            116 -> getPointerMapping()
            117 -> getPointerMapping()
            118 -> getModifierMapping()
            119 -> getModifierMapping()
            127 -> unitReplyless()
            else -> writeError(error = 1, opcode = opcode, minorOpcode = minorOpcode, badValue = opcode)
        }
    }

    private fun createWindow(body: ByteArray) {
        if (body.size < 28) return writeError(16, 1, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val parent = byteOrder.u32(body, 4)
        val window = XWindow(
            id = id,
            parentId = parent,
            x = byteOrder.i16(body, 8),
            y = byteOrder.i16(body, 10),
            width = byteOrder.u16(body, 12),
            height = byteOrder.u16(body, 14),
            borderWidth = byteOrder.u16(body, 16),
            backgroundPixel = createWindowBackground(body),
        )
        state.putWindow(window)
        createWindowEventMask(body)?.let { state.selectEvents(this, id, it) }
    }

    private fun changeWindowAttributes(body: ByteArray) {
        if (body.size < 8) return
        val windowId = byteOrder.u32(body, 0)
        windowEventMask(body, maskOffset = 4, valuesOffset = 8)?.let { state.selectEvents(this, windowId, it) }
    }

    private fun destroyWindow(body: ByteArray) {
        if (body.size >= 4) state.removeWindow(byteOrder.u32(body, 0))
    }

    private fun reparentWindow(body: ByteArray) {
        if (body.size < 12) return
        state.reparentWindow(
            id = byteOrder.u32(body, 0),
            parentId = byteOrder.u32(body, 4),
            x = byteOrder.i16(body, 8),
            y = byteOrder.i16(body, 10),
        )
    }

    private fun mapWindow(body: ByteArray) {
        if (body.size < 4) return
        val window = state.mapWindow(byteOrder.u32(body, 0)) ?: return
        sendMapNotify(window)
        sendExpose(window)
    }

    private fun unmapWindow(body: ByteArray) {
        if (body.size >= 4) state.unmapWindow(byteOrder.u32(body, 0))
    }

    private fun mapSubwindows(body: ByteArray) {
        if (body.size < 4) return
        for (child in state.childrenOf(byteOrder.u32(body, 0))) {
            state.mapWindow(child.id)
            sendMapNotify(child)
            sendExpose(child)
        }
    }

    private fun unmapSubwindows(body: ByteArray) {
        if (body.size < 4) return
        for (child in state.childrenOf(byteOrder.u32(body, 0))) {
            state.unmapWindow(child.id)
        }
    }

    private fun configureWindow(body: ByteArray) {
        if (body.size < 6) return
        val window = state.window(byteOrder.u32(body, 0)) ?: return
        val mask = byteOrder.u16(body, 4)
        var offset = 8
        fun next(): Int {
            val value = byteOrder.u32(body, offset)
            offset += 4
            return value
        }
        val x = if ((mask and 0x0001) != 0) next() else null
        val y = if ((mask and 0x0002) != 0) next() else null
        val width = if ((mask and 0x0004) != 0) next() else null
        val height = if ((mask and 0x0008) != 0) next() else null
        val borderWidth = if ((mask and 0x0010) != 0) next() else null
        state.configureWindow(window.id, x = x, y = y, width = width, height = height, borderWidth = borderWidth)
    }

    private fun getWindowAttributes(body: ByteArray) {
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 3, badValue = byteOrder.u32(body, 0))
        val reply = reply(extra = 0, payloadUnits = 3)
        byteOrder.put32(reply, 8, X11Ids.RootVisual)
        byteOrder.put16(reply, 12, 1)
        reply[14] = 1
        reply[15] = 1
        byteOrder.put32(reply, 16, -1)
        byteOrder.put32(reply, 20, 0)
        reply[24] = 0
        reply[25] = 1
        reply[26] = if (window.mapped) 2 else 0
        reply[27] = 0
        byteOrder.put32(reply, 28, X11Ids.DefaultColormap)
        byteOrder.put32(reply, 32, 0)
        byteOrder.put32(reply, 36, 0)
        byteOrder.put16(reply, 40, 0)
        write(reply)
    }

    private fun getGeometry(body: ByteArray) {
        val drawable = state.drawable(byteOrder.u32(body, 0)) ?: return writeError(9, 14, badValue = byteOrder.u32(body, 0))
        val reply = reply(extra = drawable.depth, payloadUnits = 0)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put16(reply, 12, drawable.x)
        byteOrder.put16(reply, 14, drawable.y)
        byteOrder.put16(reply, 16, drawable.width)
        byteOrder.put16(reply, 18, drawable.height)
        byteOrder.put16(reply, 20, drawable.borderWidth)
        write(reply)
    }

    private fun queryTree(body: ByteArray) {
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 15, badValue = byteOrder.u32(body, 0))
        val children = state.childrenOf(window.id)
        val reply = reply(extra = 0, payloadUnits = children.size)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put32(reply, 12, window.parentId)
        byteOrder.put16(reply, 16, children.size)
        var offset = 32
        for (child in children) {
            byteOrder.put32(reply, offset, child.id)
            offset += 4
        }
        write(reply)
    }

    private fun internAtom(onlyIfExistsOpcode: Int, body: ByteArray) {
        val onlyIfExists = onlyIfExistsOpcode != 0
        val nameLength = byteOrder.u16(body, 0)
        val name = body.copyOfRange(4, 4 + nameLength).decodeToString()
        val atom = state.internAtom(name, onlyIfExists)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, atom)
        write(reply)
    }

    private fun getAtomName(body: ByteArray) {
        val atom = byteOrder.u32(body, 0)
        val name = state.atomName(atom) ?: return writeError(5, 17, badValue = atom)
        val bytes = name.encodeToByteArray()
        val reply = reply(extra = 0, payloadUnits = paddedLength(bytes.size) / 4)
        byteOrder.put16(reply, 8, bytes.size)
        bytes.copyInto(reply, 32)
        write(reply)
    }

    private fun changeProperty(mode: Int, body: ByteArray) {
        if (body.size < 20) return
        val window = state.window(byteOrder.u32(body, 0)) ?: return
        val property = byteOrder.u32(body, 4)
        val type = byteOrder.u32(body, 8)
        val format = body[12].toInt() and 0xff
        val units = byteOrder.u32(body, 16)
        val byteLength = units * (format / 8)
        val data = body.copyOfRange(20, 20 + byteLength.coerceAtMost(body.size - 20))
        val existing = window.properties[property]
        window.properties[property] = if (mode == 1 && existing != null) {
            existing.copy(data = existing.data + data)
        } else {
            XProperty(type = type, format = format, data = data)
        }
    }

    private fun deleteProperty(body: ByteArray) {
        state.window(byteOrder.u32(body, 0))?.properties?.remove(byteOrder.u32(body, 4))
    }

    private fun getProperty(deleteOpcode: Int, body: ByteArray) {
        val delete = deleteOpcode != 0
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 20, badValue = byteOrder.u32(body, 0))
        val propertyId = byteOrder.u32(body, 4)
        val requestedType = byteOrder.u32(body, 8)
        val longOffset = byteOrder.u32(body, 12).toLong().coerceAtLeast(0L).saturatingTimes4()
        val longLength = byteOrder.u32(body, 16).toLong().coerceAtLeast(0L).saturatingTimes4()
        val property = window.properties[propertyId]
        if (property == null || (requestedType != 0 && requestedType != property.type)) {
            val reply = reply(extra = 0, payloadUnits = 0)
            byteOrder.put32(reply, 8, 0)
            byteOrder.put32(reply, 12, 0)
            byteOrder.put32(reply, 16, 0)
            return write(reply)
        }
        val available = property.data.drop(longOffset.coerceAtMost(property.data.size.toLong()).toInt()).toByteArray()
        val value = available.take(longLength.coerceAtMost(available.size.toLong()).toInt()).toByteArray()
        val bytesAfter = (available.size - value.size).coerceAtLeast(0)
        val reply = reply(extra = property.format, payloadUnits = paddedLength(value.size) / 4)
        byteOrder.put32(reply, 8, property.type)
        byteOrder.put32(reply, 12, bytesAfter)
        byteOrder.put32(reply, 16, value.size / (property.format / 8))
        value.copyInto(reply, 32)
        write(reply)
        if (delete && bytesAfter == 0) window.properties.remove(propertyId)
    }

    private fun listProperties(body: ByteArray) {
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 21, badValue = byteOrder.u32(body, 0))
        val keys = window.properties.keys.sorted()
        val reply = reply(extra = 0, payloadUnits = keys.size)
        byteOrder.put16(reply, 8, keys.size)
        var offset = 32
        for (key in keys) {
            byteOrder.put32(reply, offset, key)
            offset += 4
        }
        write(reply)
    }

    private fun getSelectionOwner() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, 0)
        write(reply)
    }

    private fun queryPointer() {
        val reply = reply(extra = 1, payloadUnits = 0)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put32(reply, 12, 0)
        byteOrder.put16(reply, 16, 0)
        byteOrder.put16(reply, 18, 0)
        byteOrder.put16(reply, 20, 0)
        byteOrder.put16(reply, 22, 0)
        byteOrder.put16(reply, 24, 0)
        write(reply)
    }

    private fun translateCoordinates(body: ByteArray) {
        val reply = reply(extra = 1, payloadUnits = 0)
        byteOrder.put32(reply, 8, 0)
        byteOrder.put16(reply, 12, byteOrder.i16(body, 8))
        byteOrder.put16(reply, 14, byteOrder.i16(body, 10))
        write(reply)
    }

    private fun getInputFocus() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, state.snapshot().focusWindowId)
        write(reply)
    }

    private fun queryKeymap() {
        write(reply(extra = 0, payloadUnits = 2))
    }

    private fun openFont(body: ByteArray) {
        if (body.size >= 4) state.putFont(byteOrder.u32(body, 0))
    }

    private fun closeResource(body: ByteArray) {
        if (body.size >= 4) state.removeResource(byteOrder.u32(body, 0))
    }

    private fun queryFont(body: ByteArray) {
        val reply = reply(extra = 0, payloadUnits = 7)
        byteOrder.put32(reply, 8, 0)
        byteOrder.put16(reply, 12, 0)
        byteOrder.put16(reply, 14, 0)
        byteOrder.put16(reply, 16, 8)
        byteOrder.put16(reply, 18, 16)
        byteOrder.put16(reply, 20, 8)
        byteOrder.put16(reply, 22, 16)
        byteOrder.put16(reply, 24, 0)
        byteOrder.put16(reply, 26, 0)
        byteOrder.put16(reply, 28, 0)
        byteOrder.put16(reply, 30, 0)
        byteOrder.put32(reply, 56, 0)
        write(reply)
    }

    private fun listFonts() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0)
        write(reply)
    }

    private fun getFontPath() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0)
        write(reply)
    }

    private fun createPixmap(depth: Int, body: ByteArray) {
        if (body.size < 12) return
        state.putPixmap(
            XPixmap(
                id = byteOrder.u32(body, 0),
                width = byteOrder.u16(body, 8),
                height = byteOrder.u16(body, 10),
                depth = depth,
            ),
        )
    }

    private fun createGc(body: ByteArray) {
        if (body.size < 12) return
        val id = byteOrder.u32(body, 0)
        state.putGc(XGraphicsContext(id))
        applyGcValues(id, byteOrder.u32(body, 8), body, 12)
    }

    private fun changeGc(body: ByteArray) {
        if (body.size < 8) return
        applyGcValues(byteOrder.u32(body, 0), byteOrder.u32(body, 4), body, 8)
    }

    private fun clearArea(body: ByteArray) {
        if (body.size < 12) return
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return
        val x = byteOrder.i16(body, 4)
        val y = byteOrder.i16(body, 6)
        val rectangle = XRectangleCommand(
            x = x,
            y = y,
            width = byteOrder.u16(body, 8).takeIf { it > 0 } ?: (window.width - x),
            height = byteOrder.u16(body, 10).takeIf { it > 0 } ?: (window.height - y),
        )
        state.fillRectangles(windowId, window.backgroundPixel, listOf(rectangle))
        state.draw(
            XDrawingCommand(
                drawableId = windowId,
                kind = XDrawingKind.Clear,
                foreground = window.backgroundPixel,
                rectangles = listOf(rectangle),
            ),
        )
    }

    private fun copyArea(body: ByteArray) {
        if (body.size < 24) return
        val gc = state.gc(byteOrder.u32(body, 8))
        val sourceDrawable = byteOrder.u32(body, 0)
        val destinationDrawable = byteOrder.u32(body, 4)
        val sourceX = byteOrder.i16(body, 12)
        val sourceY = byteOrder.i16(body, 14)
        val destinationX = byteOrder.i16(body, 16)
        val destinationY = byteOrder.i16(body, 18)
        val width = byteOrder.u16(body, 20)
        val height = byteOrder.u16(body, 22)
        val image = state.copyArea(
            sourceDrawableId = sourceDrawable,
            destinationDrawableId = destinationDrawable,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
        ) ?: return
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawable,
                kind = XDrawingKind.CopyArea,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                rectangles = listOf(
                    XRectangleCommand(
                        x = destinationX,
                        y = destinationY,
                        width = width,
                        height = height,
                    ),
                ),
                imageDataUri = XFramebuffer.imageDataUri(image),
                sourceDrawableId = sourceDrawable,
            ),
        )
    }

    private fun polyPoint(coordMode: Int, body: ByteArray) {
        if (body.size < 12) return
        val gc = state.gc(byteOrder.u32(body, 4))
        state.draw(
            XDrawingCommand(
                drawableId = byteOrder.u32(body, 0),
                kind = XDrawingKind.FillRectangle,
                foreground = gc.foreground,
                rectangles = points(body, 8, coordMode).map { XRectangleCommand(it.x, it.y, 2, 2) },
            ),
        )
    }

    private fun polyLine(coordMode: Int, body: ByteArray) {
        if (body.size < 12) return
        val gc = state.gc(byteOrder.u32(body, 4))
        state.draw(
            XDrawingCommand(
                drawableId = byteOrder.u32(body, 0),
                kind = XDrawingKind.Line,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                points = points(body, 8, coordMode),
            ),
        )
    }

    private fun polySegment(body: ByteArray) {
        if (body.size < 16) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val points = mutableListOf<XPoint>()
        var offset = 8
        while (offset + 8 <= body.size) {
            points += XPoint(byteOrder.i16(body, offset), byteOrder.i16(body, offset + 2))
            points += XPoint(byteOrder.i16(body, offset + 4), byteOrder.i16(body, offset + 6))
            offset += 8
        }
        state.draw(
            XDrawingCommand(
                drawableId = byteOrder.u32(body, 0),
                kind = XDrawingKind.Segment,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                points = points,
            ),
        )
    }

    private fun polyRectangle(body: ByteArray, kind: XDrawingKind) {
        if (body.size < 16) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val rectangles = rectangles(body, 8)
        if (kind == XDrawingKind.FillRectangle) {
            state.fillRectangles(byteOrder.u32(body, 0), gc.foreground, rectangles)
        }
        state.draw(
            XDrawingCommand(
                drawableId = byteOrder.u32(body, 0),
                kind = kind,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                rectangles = rectangles,
            ),
        )
    }

    private fun fillPoly(body: ByteArray) {
        if (body.size < 16) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val coordMode = body[8].toInt() and 0xff
        state.draw(
            XDrawingCommand(
                drawableId = byteOrder.u32(body, 0),
                kind = XDrawingKind.Line,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                points = points(body, 12, coordMode),
            ),
        )
    }

    private fun putImage(format: Int, body: ByteArray) {
        if (body.size < 20) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val width = byteOrder.u16(body, 8)
        val height = byteOrder.u16(body, 10)
        val x = byteOrder.i16(body, 12)
        val y = byteOrder.i16(body, 14)
        val image = decodePutImage(format = format, width = width, height = height, depth = body[17].toInt() and 0xff, data = body.copyOfRange(20, body.size))
        val imageDataUri = image?.let { XFramebuffer.imageDataUri(it) }
        if (image != null) {
            state.putImage(drawableId, x, y, image)
        }
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.PutImage,
                foreground = gc.foreground,
                rectangles = listOf(
                    XRectangleCommand(
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                    ),
                ),
                imageDataUri = imageDataUri,
            ),
        )
    }

    private fun polyText(body: ByteArray, is16Bit: Boolean) {
        if (body.size < 12) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val textBytes = body.copyOfRange(12, body.size).filter { (it.toInt() and 0xff) in 32..126 }.toByteArray()
        if (textBytes.isEmpty()) return
        state.draw(
            XDrawingCommand(
                drawableId = byteOrder.u32(body, 0),
                kind = XDrawingKind.Text,
                foreground = gc.foreground,
                background = gc.background,
                points = listOf(XPoint(byteOrder.i16(body, 8), byteOrder.i16(body, 10))),
                text = if (is16Bit) textBytes.decodeToString().filterIndexed { index, _ -> index % 2 == 1 } else textBytes.decodeToString(),
            ),
        )
    }

    private fun imageText(length: Int, body: ByteArray, is16Bit: Boolean) {
        if (body.size < 12) return
        val byteLength = length * if (is16Bit) 2 else 1
        val textBytes = body.copyOfRange(12, (12 + byteLength).coerceAtMost(body.size))
        val gc = state.gc(byteOrder.u32(body, 4))
        state.draw(
            XDrawingCommand(
                drawableId = byteOrder.u32(body, 0),
                kind = XDrawingKind.Text,
                foreground = gc.foreground,
                background = gc.background,
                points = listOf(XPoint(byteOrder.i16(body, 8), byteOrder.i16(body, 10))),
                text = if (is16Bit) decodeText16(textBytes) else textBytes.decodeToString(),
            ),
        )
    }

    private fun getImage(body: ByteArray) {
        val width = byteOrder.u16(body, 12)
        val height = byteOrder.u16(body, 14)
        val bytes = ByteArray(width * height * 4)
        val reply = reply(extra = 24, payloadUnits = bytes.size / 4)
        byteOrder.put32(reply, 8, X11Ids.RootVisual)
        bytes.copyInto(reply, 32)
        write(reply)
    }

    private fun createColormap(body: ByteArray) {
        if (body.size >= 4) state.putColormap(byteOrder.u32(body, 0))
    }

    private fun listInstalledColormaps() {
        val reply = reply(extra = 0, payloadUnits = 1)
        byteOrder.put16(reply, 8, 1)
        byteOrder.put32(reply, 32, X11Ids.DefaultColormap)
        write(reply)
    }

    private fun allocColor(body: ByteArray) {
        val red = byteOrder.u16(body, 4)
        val green = byteOrder.u16(body, 6)
        val blue = byteOrder.u16(body, 8)
        val pixel = ((red and 0xff00) shl 8) or (green and 0xff00) or (blue ushr 8)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, red)
        byteOrder.put16(reply, 10, green)
        byteOrder.put16(reply, 12, blue)
        byteOrder.put16(reply, 14, red)
        byteOrder.put16(reply, 16, green)
        byteOrder.put16(reply, 18, blue)
        byteOrder.put32(reply, 20, pixel)
        write(reply)
    }

    private fun lookupColor() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0xffff)
        byteOrder.put16(reply, 10, 0xffff)
        byteOrder.put16(reply, 12, 0xffff)
        byteOrder.put16(reply, 14, 0xffff)
        byteOrder.put16(reply, 16, 0xffff)
        byteOrder.put16(reply, 18, 0xffff)
        write(reply)
    }

    private fun queryColors(body: ByteArray) {
        val count = (body.size - 4) / 4
        val reply = reply(extra = 0, payloadUnits = count * 2)
        byteOrder.put16(reply, 8, count)
        var sourceOffset = 4
        var targetOffset = 32
        repeat(count) {
            val pixel = byteOrder.u32(body, sourceOffset)
            sourceOffset += 4
            byteOrder.put16(reply, targetOffset, ((pixel ushr 16) and 0xff) * 257)
            byteOrder.put16(reply, targetOffset + 2, ((pixel ushr 8) and 0xff) * 257)
            byteOrder.put16(reply, targetOffset + 4, (pixel and 0xff) * 257)
            targetOffset += 8
        }
        write(reply)
    }

    private fun createCursor(body: ByteArray) {
        if (body.size >= 4) state.putCursor(byteOrder.u32(body, 0))
    }

    private fun queryBestSize(body: ByteArray) {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, byteOrder.u16(body, 4))
        byteOrder.put16(reply, 10, byteOrder.u16(body, 6))
        write(reply)
    }

    private fun queryExtension(body: ByteArray) {
        val nameLength = byteOrder.u16(body, 0)
        val name = body.copyOfRange(4, 4 + nameLength).decodeToString()
        val reply = reply(extra = 0, payloadUnits = 0)
        val extension = state.extension(name)
        if (extension != null) {
            reply[8] = 1
            reply[9] = extension.majorOpcode.toByte()
            reply[10] = extension.firstEvent.toByte()
            reply[11] = extension.firstError.toByte()
        }
        write(reply)
    }

    private fun listExtensions() {
        val names = state.extensions.map { it.name.encodeToByteArray() }
        val size = names.sumOf { 1 + it.size }
        val reply = reply(extra = names.size, payloadUnits = paddedLength(size) / 4)
        var offset = 32
        for (name in names) {
            reply[offset++] = name.size.toByte()
            name.copyInto(reply, offset)
            offset += name.size
        }
        write(reply)
    }

    private fun getKeyboardMapping(body: ByteArray) {
        val count = body.getOrNull(0)?.toInt()?.and(0xff) ?: 0
        val keysymsPerKeycode = 1
        val reply = reply(extra = keysymsPerKeycode, payloadUnits = count * keysymsPerKeycode)
        write(reply)
    }

    private fun getKeyboardControl() {
        val reply = reply(extra = 1, payloadUnits = 5)
        byteOrder.put32(reply, 8, 0)
        reply[12] = 50
        write(reply)
    }

    private fun getPointerControl() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 1)
        byteOrder.put16(reply, 10, 1)
        byteOrder.put16(reply, 12, 0)
        write(reply)
    }

    private fun getScreenSaver() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0)
        byteOrder.put16(reply, 10, 0)
        reply[12] = 0
        reply[13] = 0
        write(reply)
    }

    private fun getPointerMapping() {
        val map = byteArrayOf(1, 2, 3)
        val reply = reply(extra = map.size, payloadUnits = 1)
        map.copyInto(reply, 32)
        write(reply)
    }

    private fun getModifierMapping() {
        val reply = reply(extra = 0, payloadUnits = 0)
        write(reply)
    }

    private fun unitReplyless() = Unit

    private fun sendExpose(window: XWindow) {
        val event = ByteArray(32)
        event[0] = 12
        byteOrder.put16(event, 2, sequence)
        byteOrder.put32(event, 4, window.id)
        byteOrder.put16(event, 8, 0)
        byteOrder.put16(event, 10, 0)
        byteOrder.put16(event, 12, window.width)
        byteOrder.put16(event, 14, window.height)
        byteOrder.put16(event, 16, 0)
        write(event)
    }

    private fun sendMapNotify(window: XWindow) {
        val event = ByteArray(32)
        event[0] = 19
        byteOrder.put16(event, 2, sequence)
        byteOrder.put32(event, 4, window.parentId)
        byteOrder.put32(event, 8, window.id)
        write(event)
    }

    override fun sendPointerEvent(event: XPointerEvent) {
        val bytes = ByteArray(32)
        bytes[0] = event.type.code.toByte()
        bytes[1] = event.button.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.time)
        byteOrder.put32(bytes, 8, X11Ids.RootWindow)
        byteOrder.put32(bytes, 12, event.eventWindowId)
        byteOrder.put32(bytes, 16, event.childWindowId)
        byteOrder.put16(bytes, 20, event.rootX)
        byteOrder.put16(bytes, 22, event.rootY)
        byteOrder.put16(bytes, 24, event.eventX)
        byteOrder.put16(bytes, 26, event.eventY)
        byteOrder.put16(bytes, 28, event.state)
        bytes[30] = 1
        write(bytes)
    }

    private fun reply(extra: Int, payloadUnits: Int): ByteArray {
        val bytes = ByteArray(32 + payloadUnits * 4)
        bytes[0] = 1
        bytes[1] = extra.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, payloadUnits)
        return bytes
    }

    private fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun writeError(error: Int, opcode: Int, minorOpcode: Int = 0, badValue: Int) {
        val bytes = ByteArray(32)
        bytes[0] = 0
        bytes[1] = error.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, badValue)
        byteOrder.put16(bytes, 8, minorOpcode)
        bytes[10] = opcode.toByte()
        write(bytes)
    }

    private fun createWindowBackground(body: ByteArray): Int {
        if (body.size < 28) return 0x00ff_ffff
        val mask = byteOrder.u32(body, 24)
        var offset = 28
        var background = 0x00ff_ffff
        for (bit in 0..14) {
            if ((mask and (1 shl bit)) == 0) continue
            if (offset + 4 > body.size) break
            val value = byteOrder.u32(body, offset)
            if (bit == 1) background = value
            offset += 4
        }
        return background
    }

    private fun createWindowEventMask(body: ByteArray): Int? {
        if (body.size < 28) return null
        return windowEventMask(body, maskOffset = 24, valuesOffset = 28)
    }

    private fun windowEventMask(body: ByteArray, maskOffset: Int, valuesOffset: Int): Int? {
        if (body.size < valuesOffset || body.size < maskOffset + 4) return null
        val mask = byteOrder.u32(body, maskOffset)
        var offset = valuesOffset
        for (bit in 0..14) {
            if ((mask and (1 shl bit)) == 0) continue
            if (offset + 4 > body.size) break
            val value = byteOrder.u32(body, offset)
            if (bit == 11) return value
            offset += 4
        }
        return null
    }

    private fun applyGcValues(id: Int, mask: Int, body: ByteArray, valuesOffset: Int) {
        var offset = valuesOffset
        fun next(): Int? {
            if (offset + 4 > body.size) return null
            val value = byteOrder.u32(body, offset)
            offset += 4
            return value
        }
        var foreground: Int? = null
        var background: Int? = null
        var lineWidth: Int? = null
        var fontId: Int? = null
        for (bit in 0..22) {
            if ((mask and (1 shl bit)) == 0) continue
            val value = next() ?: break
            when (bit) {
                2 -> foreground = value
                3 -> background = value
                4 -> lineWidth = value.coerceAtLeast(1)
                14 -> fontId = value
            }
        }
        state.updateGc(id, foreground = foreground, background = background, lineWidth = lineWidth, fontId = fontId)
    }

    private fun Long.saturatingTimes4(): Long =
        if (this > Long.MAX_VALUE / 4) Long.MAX_VALUE else this * 4

    private fun points(body: ByteArray, startOffset: Int, coordMode: Int): List<XPoint> {
        val points = mutableListOf<XPoint>()
        var offset = startOffset
        var previousX = 0
        var previousY = 0
        while (offset + 4 <= body.size) {
            var x = byteOrder.i16(body, offset)
            var y = byteOrder.i16(body, offset + 2)
            if (coordMode == 1 && points.isNotEmpty()) {
                x += previousX
                y += previousY
            }
            points += XPoint(x, y)
            previousX = x
            previousY = y
            offset += 4
        }
        return points
    }

    private fun rectangles(body: ByteArray, startOffset: Int): List<XRectangleCommand> {
        val rectangles = mutableListOf<XRectangleCommand>()
        var offset = startOffset
        while (offset + 8 <= body.size) {
            rectangles += XRectangleCommand(
                x = byteOrder.i16(body, offset),
                y = byteOrder.i16(body, offset + 2),
                width = byteOrder.u16(body, offset + 4),
                height = byteOrder.u16(body, offset + 6),
            )
            offset += 8
        }
        return rectangles
    }

    private fun decodeText16(bytes: ByteArray): String =
        buildString {
            var offset = 0
            while (offset + 1 < bytes.size) {
                val value = byteOrder.u16(bytes, offset)
                if (value in 32..126) append(value.toChar())
                offset += 2
            }
        }

    private fun decodePutImage(
        format: Int,
        width: Int,
        height: Int,
        depth: Int,
        data: ByteArray,
    ): XImagePixels? {
        if (format != 2 || width <= 0 || height <= 0 || depth !in setOf(24, 32)) return null
        val bytesPerPixel = 4
        val stride = paddedLength(width * bytesPerPixel)
        if (data.size < stride * height) return null
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * stride
            for (x in 0 until width) {
                val pixel = byteOrder.u32(data, rowOffset + x * bytesPerPixel)
                pixels[y * width + x] = XFramebuffer.argb(pixel)
            }
        }
        return XImagePixels(width, height, pixels)
    }

    private fun paddedLength(length: Int): Int = (length + 3) and -4
}

private fun java.io.InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read == -1) throw EOFException("Expected $size bytes, got $offset")
        offset += read
    }
    return bytes
}

private fun java.io.InputStream.readOrNull(size: Int): ByteArray? {
    val first = read()
    if (first == -1) return null
    val bytes = ByteArray(size)
    bytes[0] = first.toByte()
    var offset = 1
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read == -1) throw EOFException("Expected $size bytes, got $offset")
        offset += read
    }
    return bytes
}
