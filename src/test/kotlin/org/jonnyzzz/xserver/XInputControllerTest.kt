package org.jonnyzzz.xserver

import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class XInputControllerTest {
    @Test
    fun `input API and HTTP click deliver X11 button events to selected window`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, x = 100, y = 120, width = 300, height = 200))
                out.write(selectButtonEventsRequest(0x0020_0001))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                readUntilEvent(input, 12)

                val direct = server.input.click(130, 150)
                assertEquals("0x200001", direct.targetWindowIdHex)
                assertEquals(2, direct.deliveredEvents)
                assertButtonEvent(input, type = 4, rootX = 130, rootY = 150, eventX = 30, eventY = 30)
                assertButtonEvent(input, type = 5, rootX = 130, rootY = 150, eventX = 30, eventY = 30)

                val http = httpPost(server.localPort, "/input/click", "x=140&y=155&button=left")
                assertContains(http.headers, "HTTP/1.1 200 OK")
                assertContains(http.body, """"targetWindow":"0x200001"""")
                assertContains(http.body, """"deliveredEvents":2""")
                assertButtonEvent(input, type = 4, rootX = 140, rootY = 155, eventX = 40, eventY = 35)
                assertButtonEvent(input, type = 5, rootX = 140, rootY = 155, eventX = 40, eventY = 35)

                val state = httpGet(server.localPort, "/state.json")
                assertContains(state.body, """"inputOperations":[""")
                assertContains(state.body, """"button":"left"""")
                assertContains(state.body, """"targetWindow":"0x200001"""")

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text.body, "Input operations:")
                assertContains(text.body, "click left at 140,155 target=0x200001 delivered=2")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `input API and HTTP move deliver X11 motion events to selected window`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val window = 0x0020_0001
                out.write(createWindowRequest(window, x = 100, y = 120, width = 300, height = 200))
                out.write(selectEventsRequest(window, XEventMasks.PointerMotion))
                out.write(mapWindowRequest(window))
                out.flush()
                readUntilEvent(input, 12)
                drainPendingEvents(socket, input)

                val first = server.input.move(130, 150)
                assertEquals("0x200001", first.targetWindowIdHex)
                assertEquals(0, first.deliveredEvents)
                assertNoEvent(socket, input)

                val direct = server.input.move(140, 155)
                assertEquals("0x200001", direct.targetWindowIdHex)
                assertEquals(1, direct.deliveredEvents)
                assertPointerEvent(input, type = 6, rootX = 140, rootY = 155, eventX = 40, eventY = 35, eventWindow = window)

                val http = httpPost(server.localPort, "/input/move", "x=150&y=160")
                assertContains(http.headers, "HTTP/1.1 200 OK")
                assertContains(http.body, """"targetWindow":"0x200001"""")
                assertContains(http.body, """"deliveredEvents":1""")
                assertPointerEvent(input, type = 6, rootX = 150, rootY = 160, eventX = 50, eventY = 40, eventWindow = window)

                val state = httpGet(server.localPort, "/state.json")
                assertContains(state.body, """"kind":"move"""")
                assertContains(state.body, """"button":"pointer"""")
                assertContains(state.body, """"targetWindow":"0x200001"""")

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text.body, "move pointer at 150,160 target=0x200001 delivered=1")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `input API move delivers button motion while pointer button is down`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val window = 0x0020_0001
                out.write(createWindowRequest(window, x = 100, y = 120, width = 300, height = 200))
                out.write(selectEventsRequest(window, XEventMasks.Button1Motion))
                out.write(mapWindowRequest(window))
                out.flush()
                readUntilEvent(input, 12)
                drainPendingEvents(socket, input)

                assertEquals(0, server.input.move(140, 155).deliveredEvents)
                assertNoEvent(socket, input)

                assertEquals(0, server.input.pointerDown(140, 155, button = 1).deliveredEvents)
                assertNoEvent(socket, input)

                val drag = server.input.move(150, 160)
                assertEquals("0x200001", drag.targetWindowIdHex)
                assertEquals(1, drag.deliveredEvents)
                assertPointerEvent(
                    input,
                    type = 6,
                    rootX = 150,
                    rootY = 160,
                    eventX = 50,
                    eventY = 40,
                    eventWindow = window,
                    state = 1 shl 8,
                )

                assertEquals(0, server.input.pointerUp(150, 160, button = 1).deliveredEvents)
                assertNoEvent(socket, input)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `HTTP key input delivers X11 key events to focused window`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val window = 0x0020_0001
                out.write(createWindowRequest(window, x = 100, y = 120, width = 300, height = 200))
                out.write(selectEventsRequest(window, XEventMasks.KeyPress or XEventMasks.KeyRelease))
                out.write(mapWindowRequest(window))
                out.write(setInputFocusRequest(window, revertTo = 2))
                out.flush()
                readUntilEvent(input, 12)

                val down = httpPost(server.localPort, "/input/key", "keycode=10&modifiers=0x4&action=down")
                assertContains(down.headers, "HTTP/1.1 200 OK")
                assertContains(down.body, """"targetWindow":"0x200001"""")
                assertContains(down.body, """"deliveredEvents":1""")
                assertKeyEvent(input, type = 2, detail = 10, eventWindow = window, state = 4)

                val up = httpPost(server.localPort, "/input/key", "keycode=10&modifiers=4&action=up")
                assertContains(up.headers, "HTTP/1.1 200 OK")
                assertContains(up.body, """"targetWindow":"0x200001"""")
                assertContains(up.body, """"deliveredEvents":1""")
                assertKeyEvent(input, type = 3, detail = 10, eventWindow = window, state = 4)

                val state = httpGet(server.localPort, "/state.json")
                assertContains(state.body, """"kind":"key-down"""")
                assertContains(state.body, """"kind":"key-up"""")
                assertContains(state.body, """"button":"10"""")
                assertContains(state.body, """"targetWindow":"0x200001"""")

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text.body, "key-down 10 at 0,0 target=0x200001 delivered=1")
                assertContains(text.body, "key-up 10 at 0,0 target=0x200001 delivered=1")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `HTTP move input validates coordinates`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val missing = httpPost(server.localPort, "/input/move", "x=10")
            assertContains(missing.headers, "HTTP/1.1 400 Bad Request")
            assertContains(missing.body, """"error":"x and y are required"""")

            val malformed = httpPost(server.localPort, "/input/move", "x=nope&y=10")
            assertContains(malformed.headers, "HTTP/1.1 400 Bad Request")
            assertContains(malformed.body, """"error":"x and y are required"""")

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `HTTP key input validates keycode modifiers and action`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val missingKeycode = httpPost(server.localPort, "/input/key", "action=down")
            assertContains(missingKeycode.headers, "HTTP/1.1 400 Bad Request")
            assertContains(missingKeycode.body, """"error":"keycode is required"""")

            val invalidKeycode = httpPost(server.localPort, "/input/key", "keycode=1&action=down")
            assertContains(invalidKeycode.headers, "HTTP/1.1 400 Bad Request")
            assertContains(invalidKeycode.body, "X11 keycode must be in")

            val malformedKeycode = httpPost(server.localPort, "/input/key", "keycode=nope&action=down")
            assertContains(malformedKeycode.headers, "HTTP/1.1 400 Bad Request")
            assertContains(malformedKeycode.body, """"error":"invalid keycode"""")

            val invalidModifiers = httpPost(server.localPort, "/input/key", "keycode=10&modifiers=0x100&action=down")
            assertContains(invalidModifiers.headers, "HTTP/1.1 400 Bad Request")
            assertContains(invalidModifiers.body, "X11 key modifiers must fit the core modifier mask")

            val malformedModifiers = httpPost(server.localPort, "/input/key", "keycode=10&modifiers=nope&action=down")
            assertContains(malformedModifiers.headers, "HTTP/1.1 400 Bad Request")
            assertContains(malformedModifiers.body, """"error":"invalid modifiers"""")

            val invalidAction = httpPost(server.localPort, "/input/key", "keycode=10&action=tap")
            assertContains(invalidAction.headers, "HTTP/1.1 400 Bad Request")
            assertContains(invalidAction.body, """"error":"unsupported key action: tap"""")

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `input click does not propagate button events through do-not-propagate mask`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val parent = 0x0020_0001
                val child = 0x0020_0002
                val buttonMask = XEventMasks.ButtonPress or XEventMasks.ButtonRelease
                out.write(createWindowRequest(parent, x = 100, y = 120, width = 300, height = 200))
                out.write(createWindowRequest(child, parent = parent, x = 10, y = 10, width = 50, height = 40, doNotPropagateMask = buttonMask))
                out.write(selectButtonEventsRequest(parent))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.flush()
                readUntilEvent(input, 12)
                readUntilEvent(input, 12)

                val direct = server.input.click(120, 140)
                assertEquals("0x200002", direct.targetWindowIdHex)
                assertEquals(0, direct.deliveredEvents)
                socket.soTimeout = 250
                assertFailsWith<SocketTimeoutException> {
                    input.readExactly(32)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `input click stops button propagation at first selected window`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val parent = 0x0020_0001
                val child = 0x0020_0002
                out.write(createWindowRequest(parent, x = 100, y = 120, width = 300, height = 200))
                out.write(createWindowRequest(child, parent = parent, x = 10, y = 10, width = 50, height = 40))
                out.write(selectButtonEventsRequest(parent))
                out.write(selectButtonEventsRequest(child))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.flush()
                readUntilEvent(input, 12)
                readUntilEvent(input, 12)

                val direct = server.input.click(120, 140)
                assertEquals("0x200002", direct.targetWindowIdHex)
                assertEquals(2, direct.deliveredEvents)
                assertButtonEvent(input, type = 4, rootX = 120, rootY = 140, eventX = 10, eventY = 10, eventWindow = child)
                assertButtonEvent(input, type = 5, rootX = 120, rootY = 140, eventX = 10, eventY = 10, eventWindow = child)
                socket.soTimeout = 250
                assertFailsWith<SocketTimeoutException> {
                    input.readExactly(32)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `WarpPointer motion does not propagate through do-not-propagate mask`() {
        XServer(ServerOptions(port = 0, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val parent = 0x0020_0001
                val child = 0x0020_0002
                out.write(createWindowRequest(parent, x = 100, y = 120, width = 300, height = 200))
                out.write(createWindowRequest(child, parent = parent, x = 10, y = 10, width = 50, height = 40, doNotPropagateMask = XEventMasks.PointerMotion))
                out.write(selectEventsRequest(parent, XEventMasks.PointerMotion))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.flush()
                readUntilEvent(input, 12)
                readUntilEvent(input, 12)
                drainPendingEvents(socket, input)

                out.write(warpPointerRequest(destinationWindow = child, destinationX = 5, destinationY = 5))
                out.flush()
                assertNoEvent(socket, input)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(out: java.io.OutputStream, input: java.io.InputStream) {
        out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16le(prefix, 6) * 4)
    }

    private fun assertButtonEvent(
        input: java.io.InputStream,
        type: Int,
        rootX: Int,
        rootY: Int,
        eventX: Int,
        eventY: Int,
        eventWindow: Int? = null,
    ) {
        val event = readUntilEvent(input, type)
        assertEquals(1, event[1].toInt() and 0xff)
        eventWindow?.let { assertEquals(it, u32le(event, 12)) }
        assertEquals(rootX, u16le(event, 20))
        assertEquals(rootY, u16le(event, 22))
        assertEquals(eventX, u16le(event, 24))
        assertEquals(eventY, u16le(event, 26))
    }

    private fun assertPointerEvent(
        input: java.io.InputStream,
        type: Int,
        rootX: Int,
        rootY: Int,
        eventX: Int,
        eventY: Int,
        eventWindow: Int? = null,
        state: Int = 0,
    ) {
        val event = readUntilEvent(input, type)
        assertEquals(0, event[1].toInt() and 0xff)
        eventWindow?.let { assertEquals(it, u32le(event, 12)) }
        assertEquals(rootX, u16le(event, 20))
        assertEquals(rootY, u16le(event, 22))
        assertEquals(eventX, u16le(event, 24))
        assertEquals(eventY, u16le(event, 26))
        assertEquals(state, u16le(event, 28))
    }

    private fun assertKeyEvent(
        input: java.io.InputStream,
        type: Int,
        detail: Int,
        eventWindow: Int,
        state: Int,
    ) {
        val event = readUntilEvent(input, type)
        assertEquals(detail, event[1].toInt() and 0xff)
        assertEquals(X11Ids.RootWindow, u32le(event, 8))
        assertEquals(eventWindow, u32le(event, 12))
        assertEquals(state, u16le(event, 28))
        assertEquals(1, event[30].toInt() and 0xff)
    }

    private fun readUntilEvent(input: java.io.InputStream, type: Int): ByteArray {
        repeat(20) {
            val event = input.readExactly(32)
            if ((event[0].toInt() and 0x7f) == type) return event
        }
        error("Did not receive event type $type")
    }

    private fun drainPendingEvents(socket: Socket, input: java.io.InputStream) {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = 100
        try {
            while (true) {
                input.readExactly(32)
            }
        } catch (_: SocketTimeoutException) {
        } finally {
            socket.soTimeout = previousTimeout
        }
    }

    private fun assertNoEvent(socket: Socket, input: java.io.InputStream) {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = 250
        try {
            val event = input.readExactly(32)
            fail("Unexpected event type=${event[0].toInt() and 0x7f} detail=${event[1].toInt() and 0xff} event=0x${u32le(event, 12).toUInt().toString(16)}")
        } catch (_: SocketTimeoutException) {
        } finally {
            socket.soTimeout = previousTimeout
        }
    }

    private fun createWindowRequest(
        id: Int,
        parent: Int = X11Ids.RootWindow,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        doNotPropagateMask: Int = 0,
    ): ByteArray {
        val valueCount = if (doNotPropagateMask != 0) 1 else 0
        val bytes = ByteArray(32 + valueCount * 4)
        bytes[0] = 1
        bytes[1] = 24
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, id)
        put32le(bytes, 8, parent)
        put16le(bytes, 12, x)
        put16le(bytes, 14, y)
        put16le(bytes, 16, width)
        put16le(bytes, 18, height)
        put16le(bytes, 20, 0)
        put16le(bytes, 22, 1)
        put32le(bytes, 24, X11Ids.RootVisual)
        if (doNotPropagateMask != 0) {
            put32le(bytes, 28, 1 shl 12)
            put32le(bytes, 32, doNotPropagateMask)
        } else {
            put32le(bytes, 28, 0)
        }
        return bytes
    }

    private fun selectButtonEventsRequest(window: Int): ByteArray {
        return selectEventsRequest(window, XEventMasks.ButtonPress or XEventMasks.ButtonRelease)
    }

    private fun selectEventsRequest(window: Int, eventMask: Int): ByteArray {
        val bytes = ByteArray(16)
        bytes[0] = 2
        put16le(bytes, 2, 4)
        put32le(bytes, 4, window)
        put32le(bytes, 8, 1 shl 11)
        put32le(bytes, 12, eventMask)
        return bytes
    }

    private fun mapWindowRequest(id: Int): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = 8
        put16le(bytes, 2, 2)
        put32le(bytes, 4, id)
        return bytes
    }

    private fun setInputFocusRequest(window: Int, revertTo: Int): ByteArray {
        val bytes = ByteArray(12)
        bytes[0] = 42
        bytes[1] = revertTo.toByte()
        put16le(bytes, 2, 3)
        put32le(bytes, 4, window)
        put32le(bytes, 8, 0)
        return bytes
    }

    private fun warpPointerRequest(destinationWindow: Int, destinationX: Int, destinationY: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 41
        put16le(bytes, 2, 6)
        put32le(bytes, 4, 0)
        put32le(bytes, 8, destinationWindow)
        put16le(bytes, 12, 0)
        put16le(bytes, 14, 0)
        put16le(bytes, 16, 0)
        put16le(bytes, 18, 0)
        put16le(bytes, 20, destinationX)
        put16le(bytes, 22, destinationY)
        return bytes
    }

    private fun httpPost(port: Int, path: String, body: String): HttpResponse =
        Socket("127.0.0.1", port).use { socket ->
            val request = buildString {
                append("POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Content-Type: application/x-www-form-urlencoded\r\n")
                append("Content-Length: ").append(body.length).append("\r\n")
                append("\r\n")
                append(body)
            }
            socket.getOutputStream().write(request.encodeToByteArray())
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().decodeToString()
            HttpResponse(
                headers = response.substringBefore("\r\n\r\n"),
                body = response.substringAfter("\r\n\r\n"),
            )
        }

    private fun httpGet(port: Int, path: String): HttpResponse =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().decodeToString()
            HttpResponse(
                headers = response.substringBefore("\r\n\r\n"),
                body = response.substringAfter("\r\n\r\n"),
            )
        }

    private fun java.io.InputStream.readExactly(size: Int): ByteArray {
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

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private data class HttpResponse(
        val headers: String,
        val body: String,
    )
}
