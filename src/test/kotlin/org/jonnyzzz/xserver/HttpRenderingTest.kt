package org.jonnyzzz.xserver

import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HttpRenderingTest {
    @Test
    fun `same socket serves svg and textual snapshots from x11 state`() {
        XServer(ServerOptions(port = 0, width = 3840, height = 2160, dpi = 100)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            createMappedWindow(server.localPort, 0x0020_0001, "one", x = 20, y = 30, width = 120, height = 90)
            createMappedWindow(server.localPort, 0x0020_0002, "two", x = 80, y = 70, width = 140, height = 100)

            val html = httpGet(server.localPort, "/")
            assertContains(html.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
            assertContains(html.body, "<!-- ${RenderCredit.Text} -->")
            assertContains(html.body, "<svg")
            assertContains(html.body, "one")
            assertContains(html.body, "two")
            assertContains(html.body, "<footer>${RenderCredit.Text}</footer>")
            assertContains(html.body, "3840 x 2160")
            assertContains(html.body, "<dt>DPI</dt><dd>100</dd>")

            val svg = httpGet(server.localPort, "/screen.svg")
            assertContains(svg.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
            assertContains(svg.body, "<!-- ${RenderCredit.Text} -->")
            assertContains(svg.body, "0x200001")
            assertContains(svg.body, "0x200002")
            assertContains(svg.body, RenderCredit.Text)

            val text = httpGet(server.localPort, "/text.txt")
            assertContains(text.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
            assertContains(text.body, "Focus: 0x200002")
            assertContains(text.body, "Screen: 3840 x 2160")
            assertContains(text.body, "DPI: 100")
            assertContains(text.body, "0x200002 overlaps 0x200001")
            assertContains(text.body, RenderCredit.Text)

            val textHtml = httpGet(server.localPort, "/text")
            assertContains(textHtml.body, "<!-- ${RenderCredit.Text} -->")
            assertContains(textHtml.body, "<footer>${RenderCredit.Text}</footer>")

            server.close()
            serverThread.join(1_000)
        }
    }

    private fun createMappedWindow(
        port: Int,
        id: Int,
        name: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            out.flush()
            val prefix = input.readExactly(8)
            assertEquals(1, prefix[0].toInt())
            input.readExactly(u16le(prefix, 6) * 4)

            out.write(createWindowRequest(id, x, y, width, height))
            out.write(changePropertyRequest(id, name))
            out.write(mapWindowRequest(id))
            out.flush()
            Thread.sleep(100)
        }
    }

    private fun createWindowRequest(id: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
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
        put16le(bytes, 20, 1)
        put16le(bytes, 22, 1)
        put32le(bytes, 24, X11Ids.RootVisual)
        put32le(bytes, 28, 0)
        return bytes
    }

    private fun changePropertyRequest(window: Int, value: String): ByteArray {
        val data = value.encodeToByteArray()
        val padded = (data.size + 3) and -4
        val bytes = ByteArray(24 + padded)
        bytes[0] = 18
        bytes[1] = 0
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, window)
        put32le(bytes, 8, 39)
        put32le(bytes, 12, 31)
        bytes[16] = 8
        put32le(bytes, 20, data.size)
        data.copyInto(bytes, 24)
        return bytes
    }

    private fun mapWindowRequest(id: Int): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = 8
        put16le(bytes, 2, 2)
        put32le(bytes, 4, id)
        return bytes
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
