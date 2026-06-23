package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XRenderProtocolTest {
    @Test
    fun `RENDER extension exposes version and picture formats`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)

                socket.getOutputStream().write(queryExtensionRequest("RENDER"))
                socket.getOutputStream().flush()
                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt())
                assertEquals(XRender.MajorOpcode, extension[9].toInt() and 0xff)

                socket.getOutputStream().write(request(XRender.MajorOpcode, 0, queryVersionBody()))
                socket.getOutputStream().flush()
                val version = readReply(socket.getInputStream())
                assertEquals(XRender.MajorVersion, u32le(version, 8))
                assertEquals(XRender.MinorVersion, u32le(version, 12))

                socket.getOutputStream().write(request(XRender.MajorOpcode, 1, ByteArray(0)))
                socket.getOutputStream().flush()
                val formats = readReply(socket.getInputStream())
                assertEquals(4, u32le(formats, 8))
                assertEquals(1, u32le(formats, 12))
                assertEquals(1, u32le(formats, 16))
                assertEquals(1, u32le(formats, 20))
                assertEquals(1, u32le(formats, 24))
                assertEquals(XRender.Argb32Format, u32le(formats, 32))
                assertEquals(32, formats[37].toInt() and 0xff)

                assertContains(httpGet(server.localPort, "/text.txt"), "RENDER supported=true")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER picture fill and solid composite update drawable model`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(WindowId, x = 2, y = 3, width = 2, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(24, image[1].toInt() and 0xff)
                assertEquals(4, u32le(image, 4))
                assertEquals(0xffff_0000.toInt(), u32le(image, 32))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId))
                out.flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""renderOperations":4""")
                }
                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "FillRectangles")
                assertContains(text, "CreateSolidFill")
                assertContains(text, "Composite")
                assertContains(text, "Unsupported requests:\n- None.")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite applies A8 mask pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 4, height = 4, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 2))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 2, alphas = byteArrayOf(0xff.toByte(), 0x80.toByte(), 0x00, 0xff.toByte())))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 2))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), u32le(image, 32))
                assertEquals(0xff80_007f.toInt(), u32le(image, 36))
                assertEquals(0xff00_00ff.toInt(), u32le(image, 40))
                assertEquals(0xffff_0000.toInt(), u32le(image, 44))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER fill rectangles over blends with existing pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpOver))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff80_007f.toInt(), u32le(image, 32))
                assertEquals(0xff00_00ff.toInt(), u32le(image, 36))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER trapezoids composite solid source into destination framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 32, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderTrapezoids(SolidPictureId, PictureId, x = 10, y = 8, width = 12, height = 9))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 32, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 32, x = 10, y = 8))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 32, x = 21, y = 16))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 32, x = 9, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 32, x = 22, y = 16))
                assertContains(httpGet(server.localPort, "/text.txt"), "Trapezoids")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER picture targeting pixmap is exposed as painted offscreen surface`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 100, height = 80))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 100, height = 80, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""id":"0x200100","width":100,"height":80,"depth":24,"painted":true""")
                }

                val html = httpGet(server.localPort, "/")
                assertContains(html, "Pixmap 0x200100")
                assertContains(html, """class="pixmap-framebuffer-image"""")
                assertContains(html, "pictures=0x200101")

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "0x200100 geometry=100x80 depth=24 painted=true pictures=0x200101")
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

    private fun queryVersionBody(): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, XRender.MajorVersion)
        put32le(body, 4, XRender.MinorVersion)
        return body
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

    private fun getImageRequest(drawable: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, drawable)
        put16le(body, 4, x)
        put16le(body, 6, y)
        put16le(body, 8, width)
        put16le(body, 10, height)
        put32le(body, 12, 0xffff_ffff.toInt())
        return request(73, 2, body)
    }

    private fun renderCreatePicture(picture: Int, drawable: Int, format: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, picture)
        put32le(body, 4, drawable)
        put32le(body, 8, format)
        return request(XRender.MajorOpcode, 4, body)
    }

    private fun renderFillRectangles(
        picture: Int,
        x: Int = 2,
        y: Int = 3,
        width: Int = 40,
        height: Int = 30,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        operation: Int = XRender.OpSrc,
    ): ByteArray {
        val body = ByteArray(24)
        body[0] = operation.toByte()
        put32le(body, 4, picture)
        put16le(body, 8, red)
        put16le(body, 10, green)
        put16le(body, 12, blue)
        put16le(body, 14, alpha)
        put16le(body, 16, x)
        put16le(body, 18, y)
        put16le(body, 20, width)
        put16le(body, 22, height)
        return request(XRender.MajorOpcode, 26, body)
    }

    private fun renderCreateSolidFill(picture: Int, red: Int, green: Int, blue: Int, alpha: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, picture)
        put16le(body, 4, red)
        put16le(body, 6, green)
        put16le(body, 8, blue)
        put16le(body, 10, alpha)
        return request(XRender.MajorOpcode, 33, body)
    }

    private fun renderComposite(source: Int, destination: Int, mask: Int = 0, width: Int = 20, height: Int = 10): ByteArray {
        val body = ByteArray(32)
        body[0] = XRender.OpOver.toByte()
        put32le(body, 4, source)
        put32le(body, 8, mask)
        put32le(body, 12, destination)
        put16le(body, 24, if (mask == 0) 12 else 0)
        put16le(body, 26, if (mask == 0) 15 else 0)
        put16le(body, 28, width)
        put16le(body, 30, height)
        return request(XRender.MajorOpcode, 8, body)
    }

    private fun renderTrapezoids(source: Int, destination: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(60)
        body[0] = XRender.OpSrc.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, XRender.A8Format)
        putFixed(body, 20, y)
        putFixed(body, 24, y + height)
        putFixedPoint(body, 28, x, y)
        putFixedPoint(body, 36, x, y + height)
        putFixedPoint(body, 44, x + width, y)
        putFixedPoint(body, 52, x + width, y + height)
        return request(XRender.MajorOpcode, 10, body)
    }

    private fun createPixmapRequest(id: Int, depth: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, WindowId)
        put16le(body, 8, width)
        put16le(body, 10, height)
        return request(53, depth, body)
    }

    private fun putImage8Request(drawable: Int, width: Int, height: Int, alphas: ByteArray): ByteArray {
        val stride = (width + 3) and -4
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * stride + x] = alphas[y * width + x]
            }
        }
        val body = ByteArray(20 + data.size)
        put32le(body, 0, drawable)
        put32le(body, 4, 0)
        put16le(body, 8, width)
        put16le(body, 10, height)
        body[17] = 8
        data.copyInto(body, 20)
        return request(72, 2, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
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

    private fun pixelAt(reply: ByteArray, imageWidth: Int, x: Int, y: Int): Int =
        u32le(reply, 32 + (y * imageWidth + x) * 4)

    private fun putFixedPoint(bytes: ByteArray, offset: Int, x: Int, y: Int) {
        putFixed(bytes, offset, x)
        putFixed(bytes, offset + 4, y)
    }

    private fun putFixed(bytes: ByteArray, offset: Int, value: Int) {
        put32le(bytes, offset, value shl 16)
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

    private companion object {
        const val WindowId = 0x0020_0001
        const val PictureId = 0x0020_1001
        const val SolidPictureId = 0x0020_1002
        const val MaskPixmapId = 0x0020_2001
        const val MaskPictureId = 0x0020_2002
        const val PixmapId = 0x0020_0100
        const val PixmapPictureId = 0x0020_0101
    }
}
