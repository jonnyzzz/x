package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XXTestProtocolTest {
    @Test
    fun `XTEST reports version compares cursor and validates request lengths`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("XTEST"))
                out.write(listExtensionsRequest())
                out.write(request(XXTest.MajorOpcode, XXTest.GetVersion, ByteArray(0)))
                out.write(xtestGetVersionRequest(2, 2))
                out.write(request(XXTest.MajorOpcode, XXTest.CompareCursor, u32(X11Ids.RootWindow)))
                out.write(xtestCompareCursorRequest(X11Ids.RootWindow, XXTest.CursorNone))
                out.write(xtestCompareCursorRequest(X11Ids.RootWindow, XXTest.CursorCurrent))
                out.write(xtestCompareCursorRequest(0x445566, XXTest.CursorCurrent))
                out.write(xtestCompareCursorRequest(X11Ids.RootWindow, 0x445567))
                out.write(request(XXTest.MajorOpcode, XXTest.GrabControl, ByteArray(0)))
                out.write(xtestGrabControlRequest(2))
                out.write(xtestGrabControlRequest(1))
                out.write(queryPointerRequest())
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XXTest.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XXTest.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XXTest.FirstError, extension[11].toInt() and 0xff)

                val extensions = readReply(socket.getInputStream())
                assertContains(extensionNames(extensions), "XTEST")

                assertError(socket.getInputStream(), error = 16, sequence = 3, minorOpcode = XXTest.GetVersion)

                val version = readReply(socket.getInputStream())
                assertEquals(XXTest.MajorVersion, version[1].toInt() and 0xff)
                assertEquals(4, u16le(version, 2))
                assertEquals(XXTest.MinorVersion, u16le(version, 8))

                assertError(socket.getInputStream(), error = 16, sequence = 5, minorOpcode = XXTest.CompareCursor)

                val none = readReply(socket.getInputStream())
                assertEquals(1, none[1].toInt() and 0xff)
                assertEquals(6, u16le(none, 2))

                val current = readReply(socket.getInputStream())
                assertEquals(1, current[1].toInt() and 0xff)
                assertEquals(7, u16le(current, 2))

                assertError(socket.getInputStream(), error = 3, badValue = 0x445566, sequence = 8, minorOpcode = XXTest.CompareCursor)
                assertError(socket.getInputStream(), error = 6, badValue = 0x445567, sequence = 9, minorOpcode = XXTest.CompareCursor)
                assertError(socket.getInputStream(), error = 16, sequence = 10, minorOpcode = XXTest.GrabControl)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 11, minorOpcode = XXTest.GrabControl)

                val pointer = readReply(socket.getInputStream())
                assertEquals(13, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XTEST fake input delivers pointer key and motion events and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.PointerMotion or XEventMasks.ButtonPress or XEventMasks.ButtonRelease or XEventMasks.KeyPress or XEventMasks.KeyRelease))
                out.write(request(XXTest.MajorOpcode, XXTest.FakeInput, ByteArray(0)))
                out.write(xtestFakeInputRequest(type = 255, detail = 0, x = 4, y = 5))
                out.write(xtestFakeInputRequest(type = XXTest.ButtonPress, detail = 0, x = 4, y = 5))
                out.write(xtestFakeInputRequest(type = XXTest.MotionNotify, detail = 0, root = 0x445566, x = 4, y = 5))
                out.write(xtestFakeInputRequest(type = XXTest.MotionNotify, detail = 2, x = 4, y = 5))
                out.write(xtestFakeInputRequest(type = XXTest.MotionNotify, detail = 0, x = 14, y = 15))
                out.write(xtestFakeInputRequest(type = XXTest.ButtonPress, detail = 1, x = 99, y = 99))
                out.write(xtestFakeInputRequest(type = XXTest.ButtonRelease, detail = 1, x = 99, y = 99))
                out.write(xtestFakeInputRequest(type = XXTest.KeyPress, detail = XKeyboard.MinKeycode, x = 14, y = 15))
                out.write(xtestFakeInputRequest(type = XXTest.KeyRelease, detail = XKeyboard.MinKeycode, x = 14, y = 15))
                out.write(xtestFakeInputRequest(type = XXTest.MotionNotify, detail = 1, x = 3, y = 4))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, sequence = 2, minorOpcode = XXTest.FakeInput)
                assertError(socket.getInputStream(), error = 2, badValue = 255, sequence = 3, minorOpcode = XXTest.FakeInput)
                assertError(socket.getInputStream(), error = 2, badValue = 0, sequence = 4, minorOpcode = XXTest.FakeInput)
                assertError(socket.getInputStream(), error = 3, badValue = 0x445566, sequence = 5, minorOpcode = XXTest.FakeInput)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 6, minorOpcode = XXTest.FakeInput)

                val motion = socket.getInputStream().readExactly(32)
                assertEquals(6, motion[0].toInt() and 0xff)
                assertEquals(7, u16le(motion, 2))
                assertEquals(14, u16le(motion, 20))
                assertEquals(15, u16le(motion, 22))

                val buttonPress = socket.getInputStream().readExactly(32)
                assertEquals(4, buttonPress[0].toInt() and 0xff)
                assertEquals(1, buttonPress[1].toInt() and 0xff)
                assertEquals(8, u16le(buttonPress, 2))
                assertEquals(14, u16le(buttonPress, 20))
                assertEquals(15, u16le(buttonPress, 22))

                val buttonRelease = socket.getInputStream().readExactly(32)
                assertEquals(5, buttonRelease[0].toInt() and 0xff)
                assertEquals(1, buttonRelease[1].toInt() and 0xff)
                assertEquals(9, u16le(buttonRelease, 2))
                assertEquals(14, u16le(buttonRelease, 20))
                assertEquals(15, u16le(buttonRelease, 22))

                val keyPress = socket.getInputStream().readExactly(32)
                assertEquals(2, keyPress[0].toInt() and 0xff)
                assertEquals(XKeyboard.MinKeycode, keyPress[1].toInt() and 0xff)
                assertEquals(10, u16le(keyPress, 2))

                val keyRelease = socket.getInputStream().readExactly(32)
                assertEquals(3, keyRelease[0].toInt() and 0xff)
                assertEquals(XKeyboard.MinKeycode, keyRelease[1].toInt() and 0xff)
                assertEquals(11, u16le(keyRelease, 2))

                val relativeMotion = socket.getInputStream().readExactly(32)
                assertEquals(6, relativeMotion[0].toInt() and 0xff)
                assertEquals(12, u16le(relativeMotion, 2))
                assertEquals(17, u16le(relativeMotion, 20))
                assertEquals(19, u16le(relativeMotion, 22))

                val pointer = readReply(socket.getInputStream())
                assertEquals(13, u16le(pointer, 2))
                assertEquals(17, u16le(pointer, 16))
                assertEquals(19, u16le(pointer, 18))

                val state = httpGet(server.localPort, "/state.json")
                assertContains(state, """"kind":"xtest-motion","x":17,"y":19""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XTEST GrabControl allows impervious client through another client server grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val otherOut = other.getOutputStream()
                    otherOut.write(xtestGrabControlRequest(1))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()
                    assertEquals(2, u16le(readReply(other.getInputStream()), 2))

                    owner.getOutputStream().write(request(36, 0, ByteArray(0)))
                    owner.getOutputStream().flush()

                    otherOut.write(queryPointerRequest())
                    otherOut.flush()
                    assertEquals(3, u16le(readReply(other.getInputStream()), 2))

                    otherOut.write(xtestGrabControlRequest(0))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()
                    other.soTimeout = 300
                    assertFailsWith<SocketTimeoutException> {
                        readReply(other.getInputStream())
                    }

                    owner.getOutputStream().write(request(37, 0, ByteArray(0)))
                    owner.getOutputStream().flush()
                    other.soTimeout = 2_000
                    assertEquals(5, u16le(readReply(other.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XTEST FakeInput delay only stalls the issuing client`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { delayed ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    delayed.soTimeout = 2_000
                    other.soTimeout = 300
                    setup(delayed)
                    setup(other)

                    delayed.getOutputStream().write(xtestFakeInputRequest(type = XXTest.MotionNotify, detail = 0, x = 7, y = 8, delay = 800))
                    delayed.getOutputStream().write(queryPointerRequest())
                    delayed.getOutputStream().flush()

                    other.getOutputStream().write(queryPointerRequest())
                    other.getOutputStream().flush()
                    val otherPointer = readReply(other.getInputStream())
                    assertEquals(1, u16le(otherPointer, 2))
                    assertEquals(0, u16le(otherPointer, 16))
                    assertEquals(0, u16le(otherPointer, 18))

                    delayed.soTimeout = 2_000
                    val delayedPointer = readReply(delayed.getInputStream())
                    assertEquals(2, u16le(delayedPointer, 2))
                    assertEquals(7, u16le(delayedPointer, 16))
                    assertEquals(8, u16le(delayedPointer, 18))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XTEST FakeInput delay starts after server grab allows the issuing client`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { delayed ->
                    owner.soTimeout = 2_000
                    delayed.soTimeout = 2_000
                    setup(owner)
                    setup(delayed)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(request(36, 0, ByteArray(0)))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(owner.getInputStream()), 2))

                    val delayedOut = delayed.getOutputStream()
                    delayedOut.write(xtestFakeInputRequest(type = XXTest.MotionNotify, detail = 0, x = 9, y = 10, delay = 500))
                    delayedOut.write(queryPointerRequest())
                    delayedOut.flush()

                    Thread.sleep(650)
                    ownerOut.write(request(37, 0, ByteArray(0)))
                    ownerOut.flush()

                    delayed.soTimeout = 200
                    assertFailsWith<SocketTimeoutException> {
                        readReply(delayed.getInputStream())
                    }

                    delayed.soTimeout = 1_000
                    val delayedPointer = readReply(delayed.getInputStream())
                    assertEquals(2, u16le(delayedPointer, 2))
                    assertEquals(9, u16le(delayedPointer, 16))
                    assertEquals(10, u16le(delayedPointer, 18))
                }
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

    private fun listExtensionsRequest(): ByteArray =
        request(99, 0, ByteArray(0))

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun queryPointerRequest(): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, X11Ids.RootWindow)
        return request(38, 0, body)
    }

    private fun changeWindowEventMaskRequest(window: Int, eventMask: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, 1 shl 11)
        put32le(body, 8, eventMask)
        return request(2, 0, body)
    }

    private fun xtestGetVersionRequest(major: Int, minor: Int): ByteArray {
        val body = ByteArray(4)
        body[0] = major.toByte()
        put16le(body, 2, minor)
        return request(XXTest.MajorOpcode, XXTest.GetVersion, body)
    }

    private fun xtestCompareCursorRequest(window: Int, cursor: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, cursor)
        return request(XXTest.MajorOpcode, XXTest.CompareCursor, body)
    }

    private fun xtestGrabControlRequest(impervious: Int): ByteArray {
        val body = ByteArray(4)
        body[0] = impervious.toByte()
        return request(XXTest.MajorOpcode, XXTest.GrabControl, body)
    }

    private fun xtestFakeInputRequest(type: Int, detail: Int, root: Int = X11Ids.RootWindow, x: Int, y: Int, delay: Int = 0): ByteArray {
        val body = ByteArray(32)
        body[0] = type.toByte()
        body[1] = detail.toByte()
        put32le(body, 4, delay)
        put32le(body, 8, root)
        put16le(body, 20, x)
        put16le(body, 22, y)
        return request(XXTest.MajorOpcode, XXTest.FakeInput, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int = 0, sequence: Int, minorOpcode: Int) {
        val bytes = input.readExactly(32)
        assertEquals(0, bytes[0].toInt() and 0xff)
        assertEquals(error, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(badValue, u32le(bytes, 4))
        assertEquals(minorOpcode, u16le(bytes, 8))
        assertEquals(XXTest.MajorOpcode, bytes[10].toInt() and 0xff)
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32le(header, 4) * 4
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

    private fun u32(value: Int): ByteArray {
        val bytes = ByteArray(4)
        put32le(bytes, 0, value)
        return bytes
    }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        put16le(bytes, offset, value)
        put16le(bytes, offset + 2, value ushr 16)
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset) or (u16le(bytes, offset + 2) shl 16)
}
