package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XRandrProtocolTest {
    @Test
    fun `RANDR reports read-only single screen resources and recovers after errors`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("RANDR"))
                out.write(listExtensionsRequest())
                out.write(request(XRandr.MajorOpcode, XRandr.QueryVersion, ByteArray(4)))
                out.write(randrQueryVersionRequest(1, 6))
                out.write(randrQueryVersionRequest(2, 0))
                out.write(request(XRandr.MajorOpcode, XRandr.SelectInput, ByteArray(4)))
                out.write(randrSelectInputRequest(X11Ids.RootWindow, 0x0100))
                out.write(randrSelectInputRequest(X11Ids.RootWindow, XRandr.EventMask))
                out.write(request(XRandr.MajorOpcode, XRandr.GetScreenInfo, ByteArray(0)))
                out.write(u32Request(XRandr.GetScreenInfo, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetScreenSizeRange, 0x0102_0304))
                out.write(u32Request(XRandr.GetScreenSizeRange, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetScreenResources, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetScreenResourcesCurrent, X11Ids.RootWindow))
                out.write(outputInfoRequest(0x0102_0304))
                out.write(outputInfoRequest(XRandr.OutputId))
                out.write(u32Request(XRandr.ListOutputProperties, XRandr.OutputId))
                out.write(crtcInfoRequest(0x0102_0304))
                out.write(crtcInfoRequest(XRandr.CrtcId))
                out.write(u32Request(XRandr.GetCrtcGammaSize, XRandr.CrtcId))
                out.write(u32Request(XRandr.GetCrtcGamma, XRandr.CrtcId))
                out.write(u32Request(XRandr.GetOutputPrimary, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetProviders, X11Ids.RootWindow))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 2))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 1))
                out.write(queryPointerRequest())
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XRandr.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XRandr.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XRandr.FirstError, extension[11].toInt() and 0xff)

                val extensions = readReply(socket.getInputStream())
                assertContains(extensionNames(extensions), "RANDR")

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = XRandr.QueryVersion)

                val version = readReply(socket.getInputStream())
                assertEquals(4, u16le(version, 2))
                assertEquals(XRandr.MajorVersion, u32le(version, 8))
                assertEquals(XRandr.MinorVersion, u32le(version, 12))

                val futureVersion = readReply(socket.getInputStream())
                assertEquals(5, u16le(futureVersion, 2))
                assertEquals(XRandr.MajorVersion, u32le(futureVersion, 8))
                assertEquals(XRandr.MinorVersion, u32le(futureVersion, 12))

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = XRandr.SelectInput)
                assertError(socket.getInputStream(), error = 2, badValue = 0x0100, sequence = 7, minorOpcode = XRandr.SelectInput)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = XRandr.GetScreenInfo)

                val screenInfo = readReply(socket.getInputStream())
                assertEquals(10, u16le(screenInfo, 2))
                assertEquals(3, u32le(screenInfo, 4))
                assertEquals(XRandr.Rotate0, screenInfo[1].toInt() and 0xff)
                assertEquals(X11Ids.RootWindow, u32le(screenInfo, 8))
                assertEquals(1, u32le(screenInfo, 16))
                assertEquals(1, u16le(screenInfo, 20))
                assertEquals(0, u16le(screenInfo, 22))
                assertEquals(XRandr.Rotate0, u16le(screenInfo, 24))
                assertEquals(XRandr.RefreshRate, u16le(screenInfo, 26))
                assertEquals(2, u16le(screenInfo, 28))
                assertEquals(120, u16le(screenInfo, 32))
                assertEquals(90, u16le(screenInfo, 34))
                assertEquals(32, u16le(screenInfo, 36))
                assertEquals(24, u16le(screenInfo, 38))
                assertEquals(1, u16le(screenInfo, 40))
                assertEquals(XRandr.RefreshRate, u16le(screenInfo, 42))

                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 11, minorOpcode = XRandr.GetScreenSizeRange)

                val range = readReply(socket.getInputStream())
                assertEquals(12, u16le(range, 2))
                assertEquals(120, u16le(range, 8))
                assertEquals(90, u16le(range, 10))
                assertEquals(120, u16le(range, 12))
                assertEquals(90, u16le(range, 14))

                assertScreenResources(readReply(socket.getInputStream()), sequence = 13)
                assertScreenResources(readReply(socket.getInputStream()), sequence = 14)

                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = 0x0102_0304, sequence = 15, minorOpcode = XRandr.GetOutputInfo)

                val outputInfo = readReply(socket.getInputStream())
                assertEquals(16, u16le(outputInfo, 2))
                assertEquals(5, u32le(outputInfo, 4))
                assertEquals(XRandr.Success, outputInfo[1].toInt() and 0xff)
                assertEquals(XRandr.CrtcId, u32le(outputInfo, 12))
                assertEquals(32, u32le(outputInfo, 16))
                assertEquals(24, u32le(outputInfo, 20))
                assertEquals(XRandr.Connected, outputInfo[24].toInt() and 0xff)
                assertEquals(XRandr.SubPixelUnknown, outputInfo[25].toInt() and 0xff)
                assertEquals(1, u16le(outputInfo, 26))
                assertEquals(1, u16le(outputInfo, 28))
                assertEquals(1, u16le(outputInfo, 30))
                assertEquals(0, u16le(outputInfo, 32))
                assertEquals(XRandr.OutputName.length, u16le(outputInfo, 34))
                assertEquals(XRandr.CrtcId, u32le(outputInfo, 36))
                assertEquals(XRandr.ModeId, u32le(outputInfo, 40))
                assertEquals(XRandr.OutputName, outputInfo.copyOfRange(44, 44 + XRandr.OutputName.length).decodeToString())

                val outputProperties = readReply(socket.getInputStream())
                assertEquals(17, u16le(outputProperties, 2))
                assertEquals(0, u32le(outputProperties, 4))
                assertEquals(0, u16le(outputProperties, 8))

                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 18, minorOpcode = XRandr.GetCrtcInfo)

                val crtcInfo = readReply(socket.getInputStream())
                assertEquals(19, u16le(crtcInfo, 2))
                assertEquals(2, u32le(crtcInfo, 4))
                assertEquals(XRandr.Success, crtcInfo[1].toInt() and 0xff)
                assertEquals(0, u16le(crtcInfo, 12))
                assertEquals(0, u16le(crtcInfo, 14))
                assertEquals(120, u16le(crtcInfo, 16))
                assertEquals(90, u16le(crtcInfo, 18))
                assertEquals(XRandr.ModeId, u32le(crtcInfo, 20))
                assertEquals(XRandr.Rotate0, u16le(crtcInfo, 24))
                assertEquals(XRandr.Rotate0, u16le(crtcInfo, 26))
                assertEquals(1, u16le(crtcInfo, 28))
                assertEquals(1, u16le(crtcInfo, 30))
                assertEquals(XRandr.OutputId, u32le(crtcInfo, 32))
                assertEquals(XRandr.OutputId, u32le(crtcInfo, 36))

                val gammaSize = readReply(socket.getInputStream())
                assertEquals(20, u16le(gammaSize, 2))
                assertEquals(0, u32le(gammaSize, 4))
                assertEquals(0, u16le(gammaSize, 8))

                val gamma = readReply(socket.getInputStream())
                assertEquals(21, u16le(gamma, 2))
                assertEquals(0, u32le(gamma, 4))

                val primary = readReply(socket.getInputStream())
                assertEquals(22, u16le(primary, 2))
                assertEquals(XRandr.OutputId, u32le(primary, 8))

                val providers = readReply(socket.getInputStream())
                assertEquals(23, u16le(providers, 2))
                assertEquals(0, u32le(providers, 4))
                assertEquals(0, u16le(providers, 12))

                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 24, minorOpcode = XRandr.GetMonitors)

                val monitors = readReply(socket.getInputStream())
                assertEquals(25, u16le(monitors, 2))
                assertEquals(7, u32le(monitors, 4))
                assertEquals(XRandr.Success, monitors[1].toInt() and 0xff)
                assertEquals(1, u32le(monitors, 12))
                assertEquals(1, u32le(monitors, 16))
                assertEquals(0, u32le(monitors, 32))
                assertEquals(1, monitors[36].toInt() and 0xff)
                assertEquals(1, monitors[37].toInt() and 0xff)
                assertEquals(1, u16le(monitors, 38))
                assertEquals(0, u16le(monitors, 40))
                assertEquals(0, u16le(monitors, 42))
                assertEquals(120, u16le(monitors, 44))
                assertEquals(90, u16le(monitors, 46))
                assertEquals(32, u32le(monitors, 48))
                assertEquals(24, u32le(monitors, 52))
                assertEquals(XRandr.OutputId, u32le(monitors, 56))

                val pointer = readReply(socket.getInputStream())
                assertEquals(26, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR validates secondary resource lookups and bad lengths`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(u32Request(XRandr.ListOutputProperties, 0x0102_0304))
                out.write(u32Request(XRandr.GetCrtcGammaSize, 0x0102_0304))
                out.write(u32Request(XRandr.GetCrtcGamma, 0x0102_0304))
                out.write(u32Request(XRandr.GetOutputPrimary, 0x0102_0304))
                out.write(u32Request(XRandr.GetProviders, 0x0102_0304))
                out.write(getMonitorsRequest(0x0102_0304, 1))
                out.write(request(XRandr.MajorOpcode, XRandr.GetOutputInfo, ByteArray(4)))
                out.write(request(XRandr.MajorOpcode, XRandr.GetCrtcInfo, ByteArray(4)))
                out.write(request(XRandr.MajorOpcode, XRandr.GetMonitors, ByteArray(4)))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = 0x0102_0304, sequence = 1, minorOpcode = XRandr.ListOutputProperties)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 2, minorOpcode = XRandr.GetCrtcGammaSize)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 3, minorOpcode = XRandr.GetCrtcGamma)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 4, minorOpcode = XRandr.GetOutputPrimary)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 5, minorOpcode = XRandr.GetProviders)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 6, minorOpcode = XRandr.GetMonitors)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 7, minorOpcode = XRandr.GetOutputInfo)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 8, minorOpcode = XRandr.GetCrtcInfo)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = XRandr.GetMonitors)

                val recovered = readReply(socket.getInputStream())
                assertEquals(10, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR swaps replies for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket, byteOrderByte = 0x42)
                val out = socket.getOutputStream()
                out.write(randrQueryVersionRequestBe(1, 6))
                out.write(u32RequestBe(XRandr.GetScreenResources, X11Ids.RootWindow))
                out.write(outputInfoRequestBe(XRandr.OutputId))
                out.write(crtcInfoRequestBe(XRandr.CrtcId))
                out.write(getMonitorsRequestBe(X11Ids.RootWindow, 1))
                out.flush()

                val version = readReply(socket.getInputStream())
                assertEquals(1, u16be(version, 2))
                assertEquals(XRandr.MajorVersion, u32be(version, 8))
                assertEquals(XRandr.MinorVersion, u32be(version, 12))

                val resources = readReplyBe(socket.getInputStream())
                assertEquals(2, u16be(resources, 2))
                assertEquals(12, u32be(resources, 4))
                assertEquals(1, u16be(resources, 16))
                assertEquals(1, u16be(resources, 18))
                assertEquals(1, u16be(resources, 20))
                assertEquals(XRandr.CrtcId, u32be(resources, 32))
                assertEquals(XRandr.OutputId, u32be(resources, 36))
                assertEquals(XRandr.ModeId, u32be(resources, 40))
                assertEquals(120, u16be(resources, 44))
                assertEquals(90, u16be(resources, 46))

                val output = readReplyBe(socket.getInputStream())
                assertEquals(3, u16be(output, 2))
                assertEquals(XRandr.CrtcId, u32be(output, 12))
                assertEquals(32, u32be(output, 16))
                assertEquals(24, u32be(output, 20))
                assertEquals(XRandr.CrtcId, u32be(output, 36))
                assertEquals(XRandr.ModeId, u32be(output, 40))

                val crtc = readReplyBe(socket.getInputStream())
                assertEquals(4, u16be(crtc, 2))
                assertEquals(120, u16be(crtc, 16))
                assertEquals(90, u16be(crtc, 18))
                assertEquals(XRandr.ModeId, u32be(crtc, 20))
                assertEquals(XRandr.OutputId, u32be(crtc, 32))

                val monitors = readReplyBe(socket.getInputStream())
                assertEquals(5, u16be(monitors, 2))
                assertEquals(7, u32be(monitors, 4))
                assertEquals(1, u32be(monitors, 12))
                assertEquals(1, u32be(monitors, 16))
                assertEquals(120, u16be(monitors, 44))
                assertEquals(90, u16be(monitors, 46))
                assertEquals(32, u32be(monitors, 48))
                assertEquals(24, u32be(monitors, 52))
                assertEquals(XRandr.OutputId, u32be(monitors, 56))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun assertScreenResources(reply: ByteArray, sequence: Int) {
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(12, u32le(reply, 4))
        assertEquals(1, u16le(reply, 16))
        assertEquals(1, u16le(reply, 18))
        assertEquals(1, u16le(reply, 20))
        assertEquals("120x90".length, u16le(reply, 22))
        assertEquals(XRandr.CrtcId, u32le(reply, 32))
        assertEquals(XRandr.OutputId, u32le(reply, 36))
        assertEquals(XRandr.ModeId, u32le(reply, 40))
        assertEquals(120, u16le(reply, 44))
        assertEquals(90, u16le(reply, 46))
        assertEquals(120 * 90 * XRandr.RefreshRate, u32le(reply, 48))
        assertEquals(120, u16le(reply, 52))
        assertEquals(120, u16le(reply, 54))
        assertEquals(120, u16le(reply, 56))
        assertEquals(0, u16le(reply, 58))
        assertEquals(90, u16le(reply, 60))
        assertEquals(90, u16le(reply, 62))
        assertEquals(90, u16le(reply, 64))
        assertEquals("120x90".length, u16le(reply, 66))
        assertEquals(0, u32le(reply, 68))
        assertEquals("120x90", reply.copyOfRange(72, 78).decodeToString())
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
        val extra = if (byteOrderByte == 0x42) u16be(prefix, 6) else u16le(prefix, 6)
        socket.getInputStream().readExactly(extra * 4)
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

    private fun randrQueryVersionRequest(major: Int, minor: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, major)
        put32le(body, 4, minor)
        return request(XRandr.MajorOpcode, XRandr.QueryVersion, body)
    }

    private fun randrQueryVersionRequestBe(major: Int, minor: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, major)
        put32be(body, 4, minor)
        return requestBe(XRandr.MajorOpcode, XRandr.QueryVersion, body)
    }

    private fun randrSelectInputRequest(window: Int, enable: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put16le(body, 4, enable)
        return request(XRandr.MajorOpcode, XRandr.SelectInput, body)
    }

    private fun u32Request(minorOpcode: Int, value: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, value)
        return request(XRandr.MajorOpcode, minorOpcode, body)
    }

    private fun u32RequestBe(minorOpcode: Int, value: Int): ByteArray {
        val body = ByteArray(4)
        put32be(body, 0, value)
        return requestBe(XRandr.MajorOpcode, minorOpcode, body)
    }

    private fun outputInfoRequest(output: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, output)
        return request(XRandr.MajorOpcode, XRandr.GetOutputInfo, body)
    }

    private fun outputInfoRequestBe(output: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, output)
        return requestBe(XRandr.MajorOpcode, XRandr.GetOutputInfo, body)
    }

    private fun crtcInfoRequest(crtc: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, crtc)
        return request(XRandr.MajorOpcode, XRandr.GetCrtcInfo, body)
    }

    private fun crtcInfoRequestBe(crtc: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, crtc)
        return requestBe(XRandr.MajorOpcode, XRandr.GetCrtcInfo, body)
    }

    private fun getMonitorsRequest(window: Int, getActive: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        body[4] = getActive.toByte()
        return request(XRandr.MajorOpcode, XRandr.GetMonitors, body)
    }

    private fun getMonitorsRequestBe(window: Int, getActive: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, window)
        body[4] = getActive.toByte()
        return requestBe(XRandr.MajorOpcode, XRandr.GetMonitors, body)
    }

    private fun queryPointerRequest(): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, X11Ids.RootWindow)
        return request(38, 0, body)
    }

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

    private fun assertError(input: InputStream, error: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val bytes = input.readExactly(32)
        assertEquals(0, bytes[0].toInt() and 0xff)
        assertEquals(error, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(badValue, u32le(bytes, 4))
        assertEquals(minorOpcode, u16le(bytes, 8))
        assertEquals(XRandr.MajorOpcode, bytes[10].toInt() and 0xff)
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32le(header, 4) * 4
        return if (extra == 0) header else header + input.readExactly(extra)
    }

    private fun readReplyBe(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32be(header, 4) * 4
        return if (extra == 0) header else header + input.readExactly(extra)
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("EOF")
            offset += read
        }
        return bytes
    }

    private fun extensionNames(reply: ByteArray): List<String> {
        val count = reply[1].toInt() and 0xff
        val names = mutableListOf<String>()
        var offset = 32
        repeat(count) {
            val length = reply[offset++].toInt() and 0xff
            names += reply.copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return names
    }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        put16le(bytes, offset, value)
        put16le(bytes, offset + 2, value ushr 16)
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
        put16be(bytes, offset, value ushr 16)
        put16be(bytes, offset + 2, value)
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset) or (u16le(bytes, offset + 2) shl 16)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        (u16be(bytes, offset) shl 16) or u16be(bytes, offset + 2)
}
