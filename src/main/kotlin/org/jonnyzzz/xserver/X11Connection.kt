package org.jonnyzzz.xserver

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

internal class X11Connection(
    private val socket: Socket,
    private val state: X11State,
) {
    private lateinit var byteOrder: ByteOrder
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var sequence = 0
    private val trace = java.lang.Boolean.getBoolean("x.trace")

    fun run() {
        input = socket.getInputStream()
        output = socket.getOutputStream()
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
        )
        output.write(reply)
        output.flush()

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
    }

    private fun dispatch(opcode: Int, minorOpcode: Int, body: ByteArray) {
        when (opcode) {
            1 -> createWindow(body)
            2 -> unitReplyless()
            3 -> getWindowAttributes(body)
            4 -> destroyWindow(body)
            6 -> unitReplyless()
            7 -> unitReplyless()
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
            56 -> unitReplyless()
            60 -> closeResource(body)
            in 61..72 -> unitReplyless()
            73 -> getImage(body)
            in 74..77 -> unitReplyless()
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
        )
        state.putWindow(window)
    }

    private fun destroyWindow(body: ByteArray) {
        if (body.size >= 4) state.removeWindow(byteOrder.u32(body, 0))
    }

    private fun mapWindow(body: ByteArray) {
        if (body.size < 4) return
        val window = state.window(byteOrder.u32(body, 0)) ?: return
        window.mapped = true
        sendMapNotify(window)
        sendExpose(window)
    }

    private fun unmapWindow(body: ByteArray) {
        if (body.size >= 4) state.window(byteOrder.u32(body, 0))?.mapped = false
    }

    private fun mapSubwindows(body: ByteArray) {
        if (body.size < 4) return
        for (child in state.childrenOf(byteOrder.u32(body, 0))) {
            child.mapped = true
            sendMapNotify(child)
            sendExpose(child)
        }
    }

    private fun unmapSubwindows(body: ByteArray) {
        if (body.size < 4) return
        for (child in state.childrenOf(byteOrder.u32(body, 0))) {
            child.mapped = false
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
        if ((mask and 0x0001) != 0) window.x = next()
        if ((mask and 0x0002) != 0) window.y = next()
        if ((mask and 0x0004) != 0) window.width = next()
        if ((mask and 0x0008) != 0) window.height = next()
        if ((mask and 0x0010) != 0) window.borderWidth = next()
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
        val longOffset = byteOrder.u32(body, 12) * 4
        val longLength = byteOrder.u32(body, 16) * 4
        val property = window.properties[propertyId]
        if (property == null || (requestedType != 0 && requestedType != property.type)) {
            val reply = reply(extra = 0, payloadUnits = 0)
            byteOrder.put32(reply, 8, 0)
            byteOrder.put32(reply, 12, 0)
            byteOrder.put32(reply, 16, 0)
            return write(reply)
        }
        val available = property.data.drop(longOffset).toByteArray()
        val value = available.take(longLength).toByteArray()
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
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
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
        if (body.size < 16) return
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
        if (body.size >= 8) state.putGc(byteOrder.u32(body, 0))
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

    private fun reply(extra: Int, payloadUnits: Int): ByteArray {
        val bytes = ByteArray(32 + payloadUnits * 4)
        bytes[0] = 1
        bytes[1] = extra.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, payloadUnits)
        return bytes
    }

    private fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
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
