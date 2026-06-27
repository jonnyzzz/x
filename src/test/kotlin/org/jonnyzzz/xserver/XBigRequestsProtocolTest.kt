package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XBigRequestsProtocolTest {
    @Test
    fun `BIG REQUESTS can be enabled and extended length requests are decoded`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)

                socket.getOutputStream().write(queryExtensionRequest("BIG-REQUESTS"))
                socket.getOutputStream().flush()
                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt())
                assertEquals(XBigRequests.MajorOpcode, extension[9].toInt() and 0xff)

                socket.getOutputStream().write(request(XBigRequests.MajorOpcode, XBigRequests.Enable, ByteArray(0)))
                socket.getOutputStream().flush()
                val enabled = readReply(socket.getInputStream())
                assertEquals(XBigRequests.MaximumRequestLength, u32le(enabled, 8))

                socket.getOutputStream().write(createWindowRequest(WindowId))
                socket.getOutputStream().write(createGcRequest(GcId, WindowId))
                socket.getOutputStream().write(extendedRequest(72, 2, putImageBody(WindowId, GcId)))
                socket.getOutputStream().flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""drawings":1""")
                }
                assertContains(httpGet(server.localPort, "/text.txt"), "PutImage: 1")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `extended length requests require BIG REQUESTS Enable`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                socket.soTimeout = 2_000

                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, WindowId))
                out.write(extendedRequest(72, 2, putImageBody(WindowId, GcId)))
                out.write(request(XBigRequests.MajorOpcode, XBigRequests.Enable, ByteArray(0)))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, opcode = 72, minorOpcode = 2, sequence = 3)
                val enabled = readReply(socket.getInputStream())
                assertEquals(4, u16le(enabled, 2))
                assertEquals(XBigRequests.MaximumRequestLength, u32le(enabled, 8))
                assertContains(httpGet(server.localPort, "/state.json"), """"drawings":0""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `BIG REQUESTS Enable validates fixed request length`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                socket.soTimeout = 2_000

                socket.getOutputStream().write(request(XBigRequests.MajorOpcode, XBigRequests.Enable, u32(0)))
                socket.getOutputStream().write(extendedRequest(XBigRequests.MajorOpcode, XBigRequests.Enable, ByteArray(0)))
                socket.getOutputStream().write(request(XBigRequests.MajorOpcode, XBigRequests.Enable, ByteArray(0)))
                socket.getOutputStream().flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, opcode = XBigRequests.MajorOpcode, minorOpcode = XBigRequests.Enable, sequence = 1)
                assertError(socket.getInputStream(), error = 16, badValue = 0, opcode = XBigRequests.MajorOpcode, minorOpcode = XBigRequests.Enable, sequence = 2)
                val enabled = readReply(socket.getInputStream())
                assertEquals(3, u16le(enabled, 2))
                assertEquals(XBigRequests.MaximumRequestLength, u32le(enabled, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket) {
        socket.getOutputStream().write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        socket.getOutputStream().flush()
        val prefix = socket.getInputStream().readExactly(8)
        assertEquals(1, prefix[0].toInt())
        socket.getInputStream().readExactly(u16le(prefix, 6) * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val padded = (encoded.size + 3) and -4
        val body = ByteArray(4 + padded)
        put16le(body, 0, encoded.size)
        encoded.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun extendedRequest(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(8 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, 0)
        put32le(bytes, 4, bytes.size / 4)
        body.copyInto(bytes, 8)
        return bytes
    }

    private fun createWindowRequest(id: Int): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, id)
        put32le(body, 4, X11Ids.RootWindow)
        put16le(body, 8, 10)
        put16le(body, 10, 20)
        put16le(body, 12, 100)
        put16le(body, 14, 80)
        put16le(body, 16, 1)
        put16le(body, 18, 1)
        put32le(body, 20, X11Ids.RootVisual)
        return request(1, 24, body)
    }

    private fun createGcRequest(gc: Int, drawable: Int): ByteArray {
        val body = ByteArray(20)
        put32le(body, 0, gc)
        put32le(body, 4, drawable)
        put32le(body, 8, 0x0000_0014)
        put32le(body, 12, 0x0000_0000)
        put32le(body, 16, 8)
        return request(55, 0, body)
    }

    private fun putImageBody(drawable: Int, gc: Int): ByteArray {
        val body = ByteArray(36)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, 2)
        put16le(body, 10, 2)
        put16le(body, 12, 40)
        put16le(body, 14, 30)
        body[17] = 24
        put32le(body, 20, 0x00ff_0000)
        put32le(body, 24, 0x0000_ff00)
        put32le(body, 28, 0x0000_00ff)
        return body
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payloadUnits = u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
    }

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertEquals(true, condition(), "Condition did not become true before timeout")
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read == -1) error("Expected $size bytes, got $offset")
            offset += read
        }
        return bytes
    }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int, opcode: Int, minorOpcode: Int, sequence: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
    }

    private fun u32(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private companion object {
        const val WindowId = 0x0020_0001
        const val GcId = 0x0020_1001
    }
}
