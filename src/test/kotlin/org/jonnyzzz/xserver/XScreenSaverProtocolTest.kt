package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XScreenSaverProtocolTest {
    @Test
    fun `ScreenSaver reports extension version and query info`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("MIT-SCREEN-SAVER"))
                out.write(queryExtensionRequest("SCREEN-SAVER"))
                out.write(listExtensionsRequest())
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.QueryVersion, ByteArray(0)))
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.QueryVersion, byteArrayOf(1, 1, 0, 0)))
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.QueryInfo, ByteArray(0)))
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.QueryInfo, u32leBytes(0x0102_0304)))
                out.write(queryInfoRequest(X11Ids.RootWindow))
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XScreenSaver.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XScreenSaver.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XScreenSaver.FirstError, extension[11].toInt() and 0xff)

                val alias = readReply(socket.getInputStream())
                assertEquals(1, alias[8].toInt() and 0xff)
                assertEquals(XScreenSaver.MajorOpcode, alias[9].toInt() and 0xff)

                val extensions = extensionNames(readReply(socket.getInputStream()))
                assertContains(extensions, "MIT-SCREEN-SAVER")
                assertFalse("SCREEN-SAVER" in extensions)

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = XScreenSaver.QueryVersion)

                val version = readReply(socket.getInputStream())
                assertEquals(5, u16le(version, 2))
                assertEquals(0, u32le(version, 4))
                assertEquals(XScreenSaver.MajorVersion, u16le(version, 8))
                assertEquals(XScreenSaver.MinorVersion, u16le(version, 10))

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = XScreenSaver.QueryInfo)
                assertError(socket.getInputStream(), error = 9, badValue = 0x0102_0304, sequence = 7, minorOpcode = XScreenSaver.QueryInfo)

                val info = readReply(socket.getInputStream())
                assertEquals(8, u16le(info, 2))
                assertEquals(0, u32le(info, 4))
                assertEquals(XScreenSaver.StateDisabled, info[1].toInt() and 0xff)
                assertEquals(XScreenSaver.SaverWindow, u32le(info, 8))
                assertEquals(0, u32le(info, 12))
                assertEquals(0, u32le(info, 16))
                assertEquals(0, u32le(info, 20))
                assertEquals(XScreenSaver.KindInternal, info[24].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ScreenSaver validates no-op requests and reflects selected event mask`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.SelectInput, ByteArray(4)))
                out.write(selectInputRequest(0x0102_0304, XScreenSaver.NotifyMask))
                out.write(selectInputRequest(X11Ids.RootWindow, 4))
                out.write(selectInputRequest(X11Ids.RootWindow, XScreenSaver.EventMask))
                out.write(queryInfoRequest(X11Ids.RootWindow))
                out.write(selectInputRequest(X11Ids.RootWindow, 0))
                out.write(queryInfoRequest(X11Ids.RootWindow))
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.SetAttributes, ByteArray(20)))
                out.write(setAttributesRequest(0x0102_0304, width = 20, height = 10))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 0, height = 10))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10, windowClass = 3))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10, valueMask = 0x8000_0000.toInt()))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10, borderWidth = 1, windowClass = XWindowClass.InputOnly))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10, depth = 1))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10, visual = 0x0102_0304))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10, valueMask = 1, values = intArrayOf(0x0102_0304)))
                out.write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10))
                out.write(queryInfoRequest(X11Ids.RootWindow))
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.UnsetAttributes, ByteArray(0)))
                out.write(unsetAttributesRequest(0x0102_0304))
                out.write(unsetAttributesRequest(X11Ids.RootWindow))
                out.write(queryInfoRequest(X11Ids.RootWindow))
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.Suspend, ByteArray(0)))
                out.write(suspendRequest(2))
                out.write(suspendRequest(0))
                out.write(request(XScreenSaver.MajorOpcode, XScreenSaver.QueryVersion, byteArrayOf(1, 1, 0, 0)))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = XScreenSaver.SelectInput)
                assertError(socket.getInputStream(), error = 9, badValue = 0x0102_0304, sequence = 2, minorOpcode = XScreenSaver.SelectInput)
                assertError(socket.getInputStream(), error = 2, badValue = 4, sequence = 3, minorOpcode = XScreenSaver.SelectInput)

                val selectedInfo = readReply(socket.getInputStream())
                assertEquals(5, u16le(selectedInfo, 2))
                assertEquals(XScreenSaver.EventMask, u32le(selectedInfo, 20))

                val clearedInfo = readReply(socket.getInputStream())
                assertEquals(7, u16le(clearedInfo, 2))
                assertEquals(0, u32le(clearedInfo, 20))

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 8, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 9, badValue = 0x0102_0304, sequence = 9, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 2, badValue = 0, sequence = 10, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 2, badValue = 3, sequence = 11, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 2, badValue = 0x8000_0000.toInt(), sequence = 12, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 8, badValue = 1, sequence = 13, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 8, badValue = 1, sequence = 14, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 8, badValue = 0x0102_0304, sequence = 15, minorOpcode = XScreenSaver.SetAttributes)
                assertError(socket.getInputStream(), error = 4, badValue = 0x0102_0304, sequence = 16, minorOpcode = XScreenSaver.SetAttributes)

                val externalInfo = readReply(socket.getInputStream())
                assertEquals(18, u16le(externalInfo, 2))
                assertEquals(XScreenSaver.KindExternal, externalInfo[24].toInt() and 0xff)

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 19, minorOpcode = XScreenSaver.UnsetAttributes)
                assertError(socket.getInputStream(), error = 9, badValue = 0x0102_0304, sequence = 20, minorOpcode = XScreenSaver.UnsetAttributes)

                val internalInfo = readReply(socket.getInputStream())
                assertEquals(22, u16le(internalInfo, 2))
                assertEquals(XScreenSaver.KindInternal, internalInfo[24].toInt() and 0xff)

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 23, minorOpcode = XScreenSaver.Suspend)

                val recovered = readReply(socket.getInputStream())
                assertEquals(26, u16le(recovered, 2))
                assertEquals(XScreenSaver.MajorVersion, u16le(recovered, 8))
                assertEquals(XScreenSaver.MinorVersion, u16le(recovered, 10))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ScreenSaver attributes are exclusive and released by unset or disconnect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)
                    owner.getOutputStream().apply {
                        write(setAttributesRequest(X11Ids.RootWindow, width = 20, height = 10))
                        write(queryInfoRequest(X11Ids.RootWindow))
                        flush()
                    }
                    val ownerInfo = readReply(owner.getInputStream())
                    assertEquals(2, u16le(ownerInfo, 2))
                    assertEquals(XScreenSaver.KindExternal, ownerInfo[24].toInt() and 0xff)

                    other.getOutputStream().apply {
                        write(setAttributesRequest(X11Ids.RootWindow, width = 30, height = 12))
                        write(queryInfoRequest(X11Ids.RootWindow))
                        flush()
                    }
                    assertError(other.getInputStream(), error = 10, badValue = 0, sequence = 1, minorOpcode = XScreenSaver.SetAttributes)
                    val otherBlockedInfo = readReply(other.getInputStream())
                    assertEquals(2, u16le(otherBlockedInfo, 2))
                    assertEquals(XScreenSaver.KindExternal, otherBlockedInfo[24].toInt() and 0xff)

                    owner.getOutputStream().apply {
                        write(unsetAttributesRequest(X11Ids.RootWindow))
                        write(request(XScreenSaver.MajorOpcode, XScreenSaver.QueryVersion, byteArrayOf(1, 1, 0, 0)))
                        flush()
                    }
                    val ownerRecovered = readReply(owner.getInputStream())
                    assertEquals(4, u16le(ownerRecovered, 2))

                    other.getOutputStream().apply {
                        write(setAttributesRequest(X11Ids.RootWindow, width = 30, height = 12))
                        write(queryInfoRequest(X11Ids.RootWindow))
                        flush()
                    }
                    val otherInfo = readReply(other.getInputStream())
                    assertEquals(4, u16le(otherInfo, 2))
                    assertEquals(XScreenSaver.KindExternal, otherInfo[24].toInt() and 0xff)
                }
            }

            val observer = Socket("127.0.0.1", server.localPort)
            observer.use {
                it.soTimeout = 2_000
                setup(it)
                val temporaryOwner = Socket("127.0.0.1", server.localPort)
                temporaryOwner.soTimeout = 2_000
                setup(temporaryOwner)
                temporaryOwner.getOutputStream().write(setAttributesRequest(X11Ids.RootWindow, width = 40, height = 14))
                temporaryOwner.getOutputStream().flush()
                temporaryOwner.close()
                waitForKind(it, XScreenSaver.KindInternal)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ScreenSaver swaps replies for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket, byteOrderByte = 0x42)
                val out = socket.getOutputStream()
                out.write(requestBe(XScreenSaver.MajorOpcode, XScreenSaver.QueryVersion, byteArrayOf(1, 1, 0, 0)))
                out.write(selectInputRequestBe(X11Ids.RootWindow, XScreenSaver.CycleMask))
                out.write(requestBe(XScreenSaver.MajorOpcode, XScreenSaver.QueryInfo, u32beBytes(X11Ids.RootWindow)))
                out.flush()

                val version = readReply(socket.getInputStream(), byteOrderByte = 0x42)
                assertEquals(1, u16be(version, 2))
                assertEquals(0, u32be(version, 4))
                assertEquals(XScreenSaver.MajorVersion, u16be(version, 8))
                assertEquals(XScreenSaver.MinorVersion, u16be(version, 10))

                val info = readReply(socket.getInputStream(), byteOrderByte = 0x42)
                assertEquals(3, u16be(info, 2))
                assertEquals(0, u32be(info, 4))
                assertEquals(XScreenSaver.StateDisabled, info[1].toInt() and 0xff)
                assertEquals(XScreenSaver.SaverWindow, u32be(info, 8))
                assertEquals(0, u32be(info, 12))
                assertEquals(0, u32be(info, 16))
                assertEquals(XScreenSaver.CycleMask, u32be(info, 20))
                assertEquals(XScreenSaver.KindInternal, info[24].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket, byteOrderByte: Int = 0x6c) {
        val setup = ByteArray(12)
        setup[0] = byteOrderByte.toByte()
        when (byteOrderByte) {
            0x42 -> put16be(setup, 2, 11)
            else -> put16le(setup, 2, 11)
        }
        socket.getOutputStream().write(setup)
        socket.getOutputStream().flush()
        val prefix = socket.getInputStream().readExactly(8)
        assertEquals(1, prefix[0].toInt())
        val payloadUnits = if (byteOrderByte == 0x42) u16be(prefix, 6) else u16le(prefix, 6)
        socket.getInputStream().readExactly(payloadUnits * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val padded = (encoded.size + 3) and -4
        val body = ByteArray(4 + padded)
        put16le(body, 0, encoded.size)
        encoded.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun listExtensionsRequest(): ByteArray =
        request(99, 0, ByteArray(0))

    private fun queryInfoRequest(drawable: Int): ByteArray =
        request(XScreenSaver.MajorOpcode, XScreenSaver.QueryInfo, u32leBytes(drawable))

    private fun selectInputRequest(drawable: Int, eventMask: Int): ByteArray =
        request(
            XScreenSaver.MajorOpcode,
            XScreenSaver.SelectInput,
            ByteArray(8).also {
                put32le(it, 0, drawable)
                put32le(it, 4, eventMask)
            },
        )

    private fun selectInputRequestBe(drawable: Int, eventMask: Int): ByteArray =
        requestBe(
            XScreenSaver.MajorOpcode,
            XScreenSaver.SelectInput,
            ByteArray(8).also {
                put32be(it, 0, drawable)
                put32be(it, 4, eventMask)
            },
        )

    private fun setAttributesRequest(
        drawable: Int,
        width: Int,
        height: Int,
        borderWidth: Int = 0,
        windowClass: Int = XWindowClass.CopyFromParent,
        depth: Int = 0,
        visual: Int = XWindowClass.CopyFromParent,
        valueMask: Int = 0,
        values: IntArray = IntArray(valueMask.countOneBits()),
    ): ByteArray =
        request(
            XScreenSaver.MajorOpcode,
            XScreenSaver.SetAttributes,
            ByteArray(24 + valueMask.countOneBits() * 4).also {
                put32le(it, 0, drawable)
                put16le(it, 8, width)
                put16le(it, 10, height)
                put16le(it, 12, borderWidth)
                it[14] = windowClass.toByte()
                it[15] = depth.toByte()
                put32le(it, 16, visual)
                put32le(it, 20, valueMask)
                values.forEachIndexed { index, value -> put32le(it, 24 + index * 4, value) }
            },
        )

    private fun unsetAttributesRequest(drawable: Int): ByteArray =
        request(XScreenSaver.MajorOpcode, XScreenSaver.UnsetAttributes, u32leBytes(drawable))

    private fun suspendRequest(value: Int): ByteArray =
        request(XScreenSaver.MajorOpcode, XScreenSaver.Suspend, u32leBytes(value))

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun requestBe(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16be(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun readReply(input: InputStream, byteOrderByte: Int = 0x6c): ByteArray {
        val header = input.readExactly(32)
        if (header[0].toInt() != 1) return header
        val payloadUnits = if (byteOrderByte == 0x42) u32be(header, 4) else u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
    }

    private fun extensionNames(reply: ByteArray): List<String> {
        var offset = 32
        val names = mutableListOf<String>()
        repeat(reply[1].toInt() and 0xff) {
            val length = reply[offset++].toInt() and 0xff
            names += reply.copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return names
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(XScreenSaver.MajorOpcode, reply[10].toInt() and 0xff)
    }

    private fun waitForKind(socket: Socket, kind: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        repeat(20) {
            out.write(queryInfoRequest(X11Ids.RootWindow))
            out.flush()
            val info = readReply(input)
            if ((info[24].toInt() and 0xff) == kind) return
            Thread.sleep(25)
        }
        error("ScreenSaver kind did not become $kind")
    }

    private fun u32leBytes(value: Int): ByteArray =
        ByteArray(4).also { put32le(it, 0, value) }

    private fun u32beBytes(value: Int): ByteArray =
        ByteArray(4).also { put32be(it, 0, value) }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("EOF after $offset of $size bytes")
            offset += read
        }
        return bytes
    }
}
