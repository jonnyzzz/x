package org.jonnyzzz.xserver

import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HttpRenderingTest {
    @Test
    fun `same socket serves svg and textual snapshots from x11 state`() {
        XServer(ServerOptions(port = 0, width = 3840, height = 2160, dpi = 100)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            createMappedWindow(server.localPort, 0x0020_0001, "one", x = 20, y = 30, width = 120, height = 90)
            createMappedWindow(server.localPort, 0x0020_0002, "two", x = 80, y = 70, width = 140, height = 100)

            val html = httpGet(server.localPort, "/")
            assertContains(html.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
            assertContains(html.body, "<!--${RenderCredit.Text}-->")
            assertContains(html.body, "<svg")
            assertContains(html.body, "one")
            assertContains(html.body, "two")
            assertContains(html.body, """class="window-map"""")
            assertContains(html.body, """class="window-contents"""")
            assertContains(html.body, """grid-template-columns: minmax(180px, 21vw) minmax(640px, 1fr)""")
            assertContains(html.body, """class="screen-map-svg"""")
            assertContains(html.body, """class="window-preview-svg"""")
            assertContains(html.body, """class="window-background"""")
            assertContains(html.body, """data-input-surface="true"""")
            assertContains(html.body, "fetch('/input/click'")
            assertContains(html.body, """button: 'left'""")
            assertContains(html.body, """data-origin-x="80"""")
            assertContains(html.body, """data-origin-x="20"""")
            assertContains(html.body, "<strong>two</strong>")
            assertContains(html.body, "<span>0x200002</span>")
            assertContains(html.body, "140x100 focused")
            assertContains(html.body, "<strong>one</strong>")
            assertContains(html.body, "<span>0x200001</span>")
            assertContains(html.body, "120x90 overlaps=")
            assertEquals(
                true,
                html.body.indexOf("<strong>two</strong>") < html.body.indexOf("<strong>one</strong>"),
                "Focused window preview should be rendered before the overlapped non-focused window",
            )
            assertContains(html.body, """<footer>by <a href="https://github.com/jonnyzzz/x">@jonnyzzz</a> <a href="https://linkedin.com/in/jonnyzzz">https://linkedin.com/in/jonnyzzz</a></footer>""")
            assertContains(html.body, "3840 x 2160")
            assertContains(html.body, "<dt>DPI</dt><dd>100</dd>")

            val svg = httpGet(server.localPort, "/screen.svg")
            assertContains(svg.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
            assertContains(svg.body, "<!--${RenderCredit.Text}-->")
            assertContains(svg.body, "0x200001")
            assertContains(svg.body, "0x200002")
            assertContains(svg.body, """data-drawable-id="0x200001"""")
            assertContains(svg.body, "<polyline")
            assertContains(svg.body, """class="framebuffer-image"""")
            assertContains(svg.body, """href="data:image/png;base64,""")
            assertFalse(svg.body.contains("""width="65533""""))
            assertContains(svg.body, RenderCredit.Text)

            val text = httpGet(server.localPort, "/text.txt")
            assertContains(text.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
            assertContains(text.body, "Focus: 0x200002")
            assertContains(text.body, "Screen: 3840 x 2160")
            assertContains(text.body, "DPI: 100")
            assertContains(text.body, "0x200002 overlaps 0x200001")
            assertContains(text.body, RenderCredit.Text)

            val json = httpGet(server.localPort, "/state.json")
            assertContains(json.body, """"drawings":2""")
            assertContains(json.body, """"inputOperations":[]""")

            val textHtml = httpGet(server.localPort, "/text")
            assertContains(textHtml.body, "<!--${RenderCredit.Text}-->")
            assertContains(textHtml.body, """<footer>by <a href="https://github.com/jonnyzzz/x">@jonnyzzz</a> <a href="https://linkedin.com/in/jonnyzzz">https://linkedin.com/in/jonnyzzz</a></footer>""")

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `copy area from pixmap renders stored pixmap image into window preview`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
                out.write(changePropertyRequest(0x0020_0001, "pixmap target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1001, 0x0020_0001))
                out.write(putImageRequest(0x0020_0100, 0x0020_1001))
                out.write(copyAreaRequest(0x0020_0100, 0x0020_0001, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)
            }

            val html = httpGet(server.localPort, "/")
            assertContains(html.body, "pixmap target")
            assertContains(html.body, """data-window-id="0x200001"""")
            assertContains(html.body, """<image""")
            assertContains(html.body, """class="framebuffer-image"""")
            assertContains(html.body, """width="160"""")
            assertContains(html.body, """height="120"""")
            assertContains(html.body, """href="data:image/png;base64,""")
            assertContains(httpGet(server.localPort, "/state.json").body, """"drawings":2""")

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `unsupported copy area does not draw diagnostic rectangle artifacts`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
                out.write(createWindowRequest(0x0020_0002, 30, 40, 160, 120))
                out.write(changePropertyRequest(0x0020_0002, "copy target"))
                out.write(createGcRequest(0x0020_1001, 0x0020_0002))
                out.write(copyAreaRequest(0x0020_0001, 0x0020_0002, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.write(mapWindowRequest(0x0020_0002))
                out.flush()
                Thread.sleep(100)
            }

            val html = httpGet(server.localPort, "/")
            assertContains(html.body, "copy target")
            assertEquals(
                false,
                html.body.contains("""data-drawable-id="0x200002"><rect x="50" y="60""""),
                "Unsupported CopyArea must not render fake diagnostic rectangle outlines into app previews",
            )

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `get property clamps overflowing offsets and lengths`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
                out.write(changePropertyRequest(0x0020_0001, "short"))
                out.write(getPropertyRequest(0x0020_0001, longOffset = 0x4000_0000, longLength = 0x7fff_ffff))
                out.flush()

                val reply = input.readExactly(32)
                assertEquals(1, reply[0].toInt())
                assertEquals(0, u16le(reply, 16))
            }
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
            if (id == 0x0020_0001) {
                out.write(createGcRequest(0x0020_1001, id))
                out.write(polyLineRequest(id, 0x0020_1001))
                out.write(putImageRequest(id, 0x0020_1001))
                out.write(createWindowRequest(0x0020_0003, 3, 3, 65_533, 65_533, parent = id))
                out.write(mapWindowRequest(0x0020_0003))
            }
            out.write(mapWindowRequest(id))
            out.flush()
            Thread.sleep(100)
        }
    }

    private fun createWindowRequest(
        id: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        parent: Int = X11Ids.RootWindow,
    ): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = 1
        bytes[1] = 24
        put16le(bytes, 2, 8)
        put32le(bytes, 4, id)
        put32le(bytes, 8, parent)
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

    private fun createGcRequest(gc: Int, drawable: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 55
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, gc)
        put32le(bytes, 8, drawable)
        put32le(bytes, 12, 0x0000_0014)
        put32le(bytes, 16, 0x0000_0000)
        put32le(bytes, 20, 8)
        return bytes
    }

    private fun setup(out: java.io.OutputStream, input: java.io.InputStream) {
        out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16le(prefix, 6) * 4)
    }

    private fun createPixmapRequest(id: Int, width: Int, height: Int): ByteArray {
        val bytes = ByteArray(16)
        bytes[0] = 53
        bytes[1] = 24
        put16le(bytes, 2, 4)
        put32le(bytes, 4, id)
        put32le(bytes, 8, X11Ids.RootWindow)
        put16le(bytes, 12, width)
        put16le(bytes, 14, height)
        return bytes
    }

    private fun copyAreaRequest(source: Int, destination: Int, gc: Int): ByteArray {
        val bytes = ByteArray(28)
        bytes[0] = 62
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, source)
        put32le(bytes, 8, destination)
        put32le(bytes, 12, gc)
        put16le(bytes, 16, 40)
        put16le(bytes, 18, 30)
        put16le(bytes, 20, 50)
        put16le(bytes, 22, 60)
        put16le(bytes, 24, 2)
        put16le(bytes, 26, 2)
        return bytes
    }

    private fun polyLineRequest(drawable: Int, gc: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 65
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, drawable)
        put32le(bytes, 8, gc)
        put16le(bytes, 12, 12)
        put16le(bytes, 14, 12)
        put16le(bytes, 16, 96)
        put16le(bytes, 18, 70)
        put16le(bytes, 20, 24)
        put16le(bytes, 22, 78)
        return bytes
    }

    private fun putImageRequest(drawable: Int, gc: Int): ByteArray {
        val bytes = ByteArray(40)
        bytes[0] = 72
        bytes[1] = 2
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, drawable)
        put32le(bytes, 8, gc)
        put16le(bytes, 12, 2)
        put16le(bytes, 14, 2)
        put16le(bytes, 16, 40)
        put16le(bytes, 18, 30)
        bytes[21] = 24
        put32le(bytes, 24, 0x00ff_0000)
        put32le(bytes, 28, 0x0000_ff00)
        put32le(bytes, 32, 0x0000_00ff)
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

    private fun getPropertyRequest(window: Int, longOffset: Int, longLength: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 20
        bytes[1] = 0
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, window)
        put32le(bytes, 8, 39)
        put32le(bytes, 12, 31)
        put32le(bytes, 16, longOffset)
        put32le(bytes, 20, longLength)
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
