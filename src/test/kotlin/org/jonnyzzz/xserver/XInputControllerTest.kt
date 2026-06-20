package org.jonnyzzz.xserver

import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
                readUntilEvent(input, 19)

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
    ) {
        val event = readUntilEvent(input, type)
        assertEquals(1, event[1].toInt() and 0xff)
        assertEquals(rootX, u16le(event, 20))
        assertEquals(rootY, u16le(event, 22))
        assertEquals(eventX, u16le(event, 24))
        assertEquals(eventY, u16le(event, 26))
    }

    private fun readUntilEvent(input: java.io.InputStream, type: Int): ByteArray {
        repeat(20) {
            val event = input.readExactly(32)
            if ((event[0].toInt() and 0x7f) == type) return event
        }
        error("Did not receive event type $type")
    }

    private fun createWindowRequest(
        id: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = 1
        bytes[1] = 24
        put16le(bytes, 2, 8)
        put32le(bytes, 4, id)
        put32le(bytes, 8, X11Ids.RootWindow)
        put16le(bytes, 12, x)
        put16le(bytes, 14, y)
        put16le(bytes, 16, width)
        put16le(bytes, 18, height)
        put16le(bytes, 20, 0)
        put16le(bytes, 22, 1)
        put32le(bytes, 24, X11Ids.RootVisual)
        put32le(bytes, 28, 0)
        return bytes
    }

    private fun selectButtonEventsRequest(window: Int): ByteArray {
        val bytes = ByteArray(16)
        bytes[0] = 2
        put16le(bytes, 2, 4)
        put32le(bytes, 4, window)
        put32le(bytes, 8, 1 shl 11)
        put32le(bytes, 12, XEventMasks.ButtonPress or XEventMasks.ButtonRelease)
        return bytes
    }

    private fun mapWindowRequest(id: Int): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = 8
        put16le(bytes, 2, 2)
        put32le(bytes, 4, id)
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

    private data class HttpResponse(
        val headers: String,
        val body: String,
    )
}
