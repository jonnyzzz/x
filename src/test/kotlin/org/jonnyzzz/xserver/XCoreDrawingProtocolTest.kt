package org.jonnyzzz.xserver

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class XCoreDrawingProtocolTest {
    @Test
    fun `protocol diagnostics stay in state without writing stderr by default`() {
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        System.setErr(PrintStream(capturedErr, true, StandardCharsets.UTF_8.name()))
        try {
            XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
                val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
                Socket("127.0.0.1", server.localPort).use { socket ->
                    setup(socket)
                    val out = socket.getOutputStream()
                    out.write(createWindowRawRequest(WindowId, valueMask = 1, values = listOf(0)))
                    out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 1, 0x00ee_ddcc))
                    out.write(renderQueryVersionRequest())
                    out.write(glxQueryVersionRequest())
                    out.flush()

                    val renderVersion = readReply(socket.getInputStream())
                    assertEquals(XRender.MajorVersion, u32le(renderVersion, 8))
                    val glxVersion = readReply(socket.getInputStream())
                    assertEquals(XGlx.MajorVersion, u32le(glxVersion, 8))
                }

                waitForStateContains(server.localPort, """"renderOperations":1""")
                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "RENDER operations:")
                assertContains(text, "QueryVersion")
                assertContains(text, "GLX operations:")
                server.close()
                serverThread.join(1_000)
            }
        } finally {
            System.err.flush()
            System.setErr(originalErr)
        }
        assertEquals("", capturedErr.toString(StandardCharsets.UTF_8.name()))
    }

    @Test
    fun `PolyPoint paints exact framebuffer pixels for origin and previous coordinate modes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcLineWidthRequest(GcId, lineWidth = 5))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(2 to 3, 6 to 3)))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 1, points = listOf(8 to 3, 2 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 5))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(1, image[0].toInt())
                assertEquals(24, image[1].toInt() and 0xff)
                assertEquals(60, u32le(image, 4))
                assertEquals(X11Ids.RootVisual, u32le(image, 8))
                assertEquals(240, u32le(image, 12))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 12, 2, 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 12, 6, 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 12, 8, 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 12, 10, 3))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 3, 3))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 2, 4))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 1, 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyPoint paints pixmap framebuffer content`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 8, height = 8))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(polyPointRequest(PixmapId, GcId, coordMode = 0, points = listOf(1 to 1, 5 to 4)))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0, u32le(image, 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 5, 4))
                assertEquals(0xff00_0000.toInt(), pixelAt(image, 8, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `window background pixmap tiles on ClearArea without immediate attribute repaint`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 5, height = 4))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24Request(WindowId, width = 5, height = 4, pixel = 0x0012_3456))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(Red, Green, Blue, 0x0000_0000),
                    ),
                )
                out.write(changeWindowBackgroundPixmapRequest(WindowId, PixmapId))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.write(clearAreaRequest(WindowId, x = 0, y = 0, width = 0, height = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 4))
                out.flush()

                val unchanged = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(unchanged, 1, 0, 0))

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 5, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 2, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 5, 0, 1))
                assertEquals(0xff00_0000.toInt(), pixelAt(image, 5, 1, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 5, 3, 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 5, 4, 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ClearArea with background pixmap preserves window-origin tile phase`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 5, height = 4))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24Request(WindowId, width = 5, height = 4, pixel = 0x0012_3456))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(Red, Green, Blue, 0x0000_0000),
                    ),
                )
                out.write(changeWindowBackgroundPixmapRequest(WindowId, PixmapId))
                out.write(putImage24Request(WindowId, width = 5, height = 4, pixel = 0x0012_3456))
                out.write(clearAreaRequest(WindowId, x = 1, y = 1, width = 3, height = 2))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 5, 0, 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(image, 5, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 5, 2, 1))
                assertEquals(0xff00_0000.toInt(), pixelAt(image, 5, 3, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 5, 1, 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 2, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 5, 3, 2))
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 5, 4, 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `clipped negative ClearArea with background pixmap preserves window-origin tile phase`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 5, height = 4))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(Red, Green, Blue, 0x0000_0000),
                    ),
                )
                out.write(changeWindowBackgroundPixmapRequest(WindowId, PixmapId))
                out.write(putImage24Request(WindowId, width = 5, height = 4, pixel = 0x0012_3456))
                out.write(clearAreaRequest(WindowId, x = -1, y = -1, width = 3, height = 3))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 5, 1, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 5, 0, 1))
                assertEquals(0xff00_0000.toInt(), pixelAt(image, 5, 1, 1))
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 5, 2, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `core drawing applies GC plane mask to framebuffer pixels`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24PixelsRequest(WindowId, width = 1, height = 1, pixels = listOf(0x0012_3456)))
                out.write(changeGcRasterRequest(GcId, planeMask = 0x0000_ff00))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_0056.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `core drawing applies GC xor raster operation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = 0x000f_0f0f))
                out.write(putImage24PixelsRequest(WindowId, width = 1, height = 1, pixels = listOf(0x0012_3456)))
                out.write(changeGcRasterRequest(GcId, function = GXxor))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff1d_3b59.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage and CopyArea apply GC raster operation and plane mask`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24PixelsRequest(WindowId, width = 2, height = 1, pixels = listOf(0x0012_3456, 0x0012_3456)))
                out.write(putImage24PixelsRequest(PixmapId, width = 1, height = 1, pixels = listOf(0x0000_0f00)))
                out.write(changeGcRasterRequest(GcId, function = GXxor, planeMask = 0x0000_ff00))
                out.write(putImage24PixelsRequest(WindowId, width = 1, height = 1, pixels = listOf(0x0000_0f00)))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 0, destinationX = 1, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                assertNoExposure(socket.getInputStream().readExactly(32), sequence = 8, drawable = WindowId, majorOpcode = 62)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3b56.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xff12_3b56.toInt(), pixelAt(image, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `invalid GC function reports Value error without changing GC`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcRasterRequest(GcId, function = GXxor))
                out.write(changeGcRasterRequest(GcId, function = 16))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(16, u32le(error, 4))
                assertEquals(56, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ffff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeGC on unknown GC reports GC error`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(changeGcRasterRequest(GcId, function = GXxor))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(13, error[1].toInt() and 0xff)
                assertEquals(GcId, u32le(error, 4))
                assertEquals(56, error[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyGC copies selected foreground and clip mask into destination GC`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(1, 0, 2, 1))))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0008_0004))
                out.write(polyPointRequest(WindowId, GcId + 1, coordMode = 0, points = listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyGC reports errors for unknown GC and invalid mask`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(copyGcBadLengthRequest(bodySize = 8))
                out.write(copyGcBadLengthRequest(bodySize = 16))
                out.write(copyGcRequest(GcId + 1, GcId, mask = 0x0000_0004))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0000_0004))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0080_0000))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 57, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 57, badValue = 0, sequence = 4)

                val missingSource = socket.getInputStream().readExactly(32)
                assertEquals(0, missingSource[0].toInt())
                assertEquals(13, missingSource[1].toInt() and 0xff)
                assertEquals(GcId + 1, u32le(missingSource, 4))
                assertEquals(57, missingSource[10].toInt() and 0xff)

                val missingDestination = socket.getInputStream().readExactly(32)
                assertEquals(0, missingDestination[0].toInt())
                assertEquals(13, missingDestination[1].toInt() and 0xff)
                assertEquals(GcId + 1, u32le(missingDestination, 4))
                assertEquals(57, missingDestination[10].toInt() and 0xff)

                val invalidMask = socket.getInputStream().readExactly(32)
                assertEquals(0, invalidMask[0].toInt())
                assertEquals(2, invalidMask[1].toInt() and 0xff)
                assertEquals(0x0080_0000, u32le(invalidMask, 4))
                assertEquals(57, invalidMask[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyGC rejects source and destination GCs with different drawable depths`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2, depth = 8))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue, drawable = PixmapId))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0000_0004))
                out.write(polyPointRequest(PixmapId, GcId + 1, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(8, error[1].toInt() and 0xff)
                assertEquals(0, u32le(error, 4))
                assertEquals(57, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(4, u32le(image, 12))
                assertEquals(0xff, image[32].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreatePixmap validates source drawable before creating pixmap resource`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingDrawable = 0x0020_9999
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2, drawable = missingDrawable))
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId + 1, foreground = Red, drawable = PixmapId))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Blue, drawable = PixmapId))
                out.write(polyPointRequest(PixmapId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val pixmapError = socket.getInputStream().readExactly(32)
                assertEquals(0, pixmapError[0].toInt())
                assertEquals(9, pixmapError[1].toInt() and 0xff)
                assertEquals(missingDrawable, u32le(pixmapError, 4))
                assertEquals(53, pixmapError[10].toInt() and 0xff)

                val gcError = socket.getInputStream().readExactly(32)
                assertEquals(0, gcError[0].toInt())
                assertEquals(9, gcError[1].toInt() and 0xff)
                assertEquals(PixmapId, u32le(gcError, 4))
                assertEquals(55, gcError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreatePixmap validates length dimensions and depth without reserving id`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapBadLengthRequest(bodySize = 8))
                out.write(createPixmapBadLengthRequest(bodySize = 16))
                out.write(createPixmapRequest(PixmapId, width = 0, height = 1))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 0))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 7))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Blue, drawable = PixmapId))
                out.write(polyPointRequest(PixmapId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 53, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 53, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 53, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 53, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 53, badValue = 7, sequence = 6)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreatePixmap rejects duplicate resource id without replacing existing pixmap`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1))
                out.write(createGcRequest(GcId, foreground = Blue, drawable = PixmapId))
                out.write(polyPointRequest(PixmapId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(getGeometryRequest(PixmapId))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(PixmapId, u32le(duplicateError, 4))
                assertEquals(53, duplicateError[10].toInt() and 0xff)

                val geometry = readReply(socket.getInputStream())
                assertEquals(1, u16le(geometry, 16))
                assertEquals(1, u16le(geometry, 18))

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow rejects duplicate resource id without replacing existing window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 3, height = 2))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(createWindowRequest(WindowId, width = 7, height = 6))
                out.write(getGeometryRequest(WindowId))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(1, duplicateError[10].toInt() and 0xff)

                val geometry = readReply(socket.getInputStream())
                assertEquals(3, u16le(geometry, 16))
                assertEquals(2, u16le(geometry, 18))

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateCursor rejects duplicate resource id without replacing existing resource`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1))
                out.write(createPixmapRequest(PixmapId + 1, width = 1, height = 1, depth = 1))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(createCursorRequest(WindowId, source = PixmapId, mask = PixmapId + 1))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(93, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateCursor validates pixmap ids and length with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val source = PixmapId + 20
                val mask = PixmapId + 21
                val missingSource = PixmapId + 22
                val missingMask = PixmapId + 23
                val cursor = PixmapId + 24
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(source, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(mask, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(request(93, 0, ByteArray(24)))
                out.write(createCursorRequest(cursor, source = missingSource, mask = 0))
                out.write(createCursorRequest(cursor, source = source, mask = missingMask))
                out.write(createCursorRequest(cursor, source = source, mask = 0))
                out.write(recolorCursorRequest(cursor))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0, green = 0, blue = 0xffff))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 93, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 4, opcode = 93, badValue = missingSource, sequence = 4)
                assertError(socket.getInputStream(), error = 4, opcode = 93, badValue = missingMask, sequence = 5)

                val blue = readReply(socket.getInputStream())
                assertEquals(Blue, u32le(blue, 16))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateCursor validates pixmap depth size and hotspot with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val source = PixmapId + 30
                val mask = PixmapId + 31
                val deepSource = PixmapId + 32
                val deepMask = PixmapId + 33
                val smallMask = PixmapId + 34
                val cursor = PixmapId + 35
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(source, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(mask, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(deepSource, width = 2, height = 2, depth = 24, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(deepMask, width = 2, height = 2, depth = 24, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(smallMask, width = 1, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = deepSource, mask = 0))
                out.write(createCursorRequest(cursor, source = source, mask = deepMask))
                out.write(createCursorRequest(cursor, source = source, mask = smallMask))
                out.write(createCursorRequest(cursor, source = source, mask = 0, x = 2, y = 0))
                out.write(createCursorRequest(cursor, source = source, mask = 0, x = 0, y = 2))
                out.write(createCursorRequest(cursor, source = source, mask = mask, x = 1, y = 1))
                out.write(recolorCursorRequest(cursor))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0xffff, green = 0, blue = 0))
                out.flush()

                assertError(socket.getInputStream(), error = 8, opcode = 93, badValue = 0, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 93, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 8, opcode = 93, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 8, opcode = 93, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 8, opcode = 93, badValue = 0, sequence = 10)

                val red = readReply(socket.getInputStream())
                assertEquals(13, u16le(red, 2))
                assertEquals(Red, u32le(red, 16))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGlyphCursor rejects duplicate resource id with glyph cursor opcode`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(openFontRequest(PixmapId))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(createGlyphCursorRequest(WindowId, sourceFont = PixmapId, maskFont = 0))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(94, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGlyphCursor validates fonts and length with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val sourceFont = PixmapId + 40
                val maskFont = PixmapId + 41
                val missingSourceFont = PixmapId + 42
                val missingMaskFont = PixmapId + 43
                val cursor = PixmapId + 44
                val out = socket.getOutputStream()
                out.write(openFontRequest(sourceFont))
                out.write(openFontRequest(maskFont))
                out.write(request(94, 0, ByteArray(24)))
                out.write(request(94, 0, ByteArray(32)))
                out.write(createGlyphCursorRequest(cursor, sourceFont = missingSourceFont, maskFont = 0))
                out.write(createGlyphCursorRequest(cursor, sourceFont = sourceFont, maskFont = missingMaskFont))
                out.write(
                    createGlyphCursorRequest(
                        cursor,
                        sourceFont = sourceFont,
                        maskFont = maskFont,
                        sourceChar = 0x1234,
                        maskChar = 0x5678,
                        foregroundRed = 0x0101,
                        foregroundGreen = 0x0202,
                        foregroundBlue = 0x0303,
                        backgroundRed = 0x0404,
                        backgroundGreen = 0x0505,
                        backgroundBlue = 0x0606,
                    ),
                )
                out.write(
                    recolorCursorRequest(
                        cursor,
                        foregroundRed = 0x1111,
                        foregroundGreen = 0x2222,
                        foregroundBlue = 0x3333,
                        backgroundRed = 0xaaaa,
                        backgroundGreen = 0xbbbb,
                        backgroundBlue = 0xcccc,
                    ),
                )
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0, green = 0xffff, blue = 0))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 94, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 94, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 7, opcode = 94, badValue = missingSourceFont, sequence = 5)
                assertError(socket.getInputStream(), error = 7, opcode = 94, badValue = missingMaskFont, sequence = 6)

                val green = readReply(socket.getInputStream())
                assertEquals(9, u16le(green, 2))
                assertEquals(0x0000_ff00, u32le(green, 16))

                val stateJson = httpGet(server.localPort, "/state.json")
                val cursorJson = Regex("""\{"id":"0x${cursor.toUInt().toString(16)}".*?\}""").find(stateJson)?.value.orEmpty()
                assertContains(cursorJson, """"kind":"glyph"""")
                assertContains(cursorJson, """"sourceFont":"0x${sourceFont.toUInt().toString(16)}"""")
                assertContains(cursorJson, """"maskFont":"0x${maskFont.toUInt().toString(16)}"""")
                assertContains(cursorJson, """"sourceChar":4660""")
                assertContains(cursorJson, """"maskChar":22136""")
                assertContains(cursorJson, """"foreground":"0x111122223333"""")
                assertContains(cursorJson, """"background":"0xaaaabbbbcccc"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RecolorCursor validates cursor id and length without replacing cursor resource`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val cursor = PixmapId + 80
                val missingCursor = cursor + 1
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                out.write(
                    recolorCursorRequest(
                        cursor,
                        foregroundRed = 0x1111,
                        foregroundGreen = 0x2222,
                        foregroundBlue = 0x3333,
                        backgroundRed = 0xaaaa,
                        backgroundGreen = 0xbbbb,
                        backgroundBlue = 0xcccc,
                    ),
                )
                out.write(recolorCursorRequest(missingCursor))
                out.write(request(96, 0, ByteArray(12)))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0xffff, green = 0, blue = 0))
                out.flush()

                assertError(socket.getInputStream(), error = 6, opcode = 96, badValue = missingCursor, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 96, badValue = 0, sequence = 5)

                val red = readReply(socket.getInputStream())
                assertEquals(6, u16le(red, 2))
                assertEquals(Red, u32le(red, 16))

                val stateJson = httpGet(server.localPort, "/state.json")
                val cursorJson = Regex("""\{"id":"0x${cursor.toUInt().toString(16)}".*?\}""").find(stateJson)?.value.orEmpty()
                assertContains(cursorJson, """"kind":"pixmap"""")
                assertContains(cursorJson, """"sourcePixmap":"0x${PixmapId.toUInt().toString(16)}"""")
                assertContains(cursorJson, """"maskPixmap":null""")
                assertContains(cursorJson, """"hotspotX":0""")
                assertContains(cursorJson, """"hotspotY":0""")
                assertContains(cursorJson, """"foreground":"0x111122223333"""")
                assertContains(cursorJson, """"background":"0xaaaabbbbcccc"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryBestSize validates length class and drawable with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingDrawable = PixmapId + 950
                val out = socket.getOutputStream()
                out.write(request(97, 0, ByteArray(4)))
                out.write(request(97, 0, ByteArray(12)))
                out.write(queryBestSizeRequest(sizeClass = 3, drawable = X11Ids.RootWindow, width = 4, height = 5))
                out.write(queryBestSizeRequest(sizeClass = 0, drawable = missingDrawable, width = 4, height = 5))
                out.write(queryBestSizeRequest(sizeClass = 0, drawable = X11Ids.RootWindow, width = 0, height = 7))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 97, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 97, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 97, badValue = 3, sequence = 3)
                assertError(socket.getInputStream(), error = 9, opcode = 97, badValue = missingDrawable, sequence = 4)

                val reply = readReply(socket.getInputStream())
                assertEquals(5, u16le(reply, 2))
                assertEquals(1, u16le(reply, 8))
                assertEquals(7, u16le(reply, 10))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryExtension validates length and returns extension presence with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val truncatedName = ByteArray(4)
                put16le(truncatedName, 0, 1)
                val overlongEmptyName = ByteArray(8)
                val out = socket.getOutputStream()
                out.write(request(98, 0, ByteArray(0)))
                out.write(request(98, 0, truncatedName))
                out.write(request(98, 0, overlongEmptyName))
                out.write(queryExtensionRequest("NOT-PRESENT"))
                out.write(queryExtensionRequest("GLX"))
                out.write(queryExtensionRequest("XFIXES"))
                out.write(queryExtensionRequest("SHAPE"))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 98, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 98, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 98, badValue = 0, sequence = 3)

                val missing = readReply(socket.getInputStream())
                assertEquals(4, u16le(missing, 2))
                assertEquals(0, missing[8].toInt())

                val glx = readReply(socket.getInputStream())
                assertEquals(5, u16le(glx, 2))
                assertEquals(1, glx[8].toInt())
                assertEquals(128, glx[9].toInt() and 0xff)
                assertEquals(0, glx[10].toInt() and 0xff)
                assertEquals(128, glx[11].toInt() and 0xff)

                val xfixes = readReply(socket.getInputStream())
                assertEquals(6, u16le(xfixes, 2))
                assertEquals(1, xfixes[8].toInt())
                assertEquals(XFixes.MajorOpcode, xfixes[9].toInt() and 0xff)
                assertEquals(XFixes.FirstEvent, xfixes[10].toInt() and 0xff)
                assertEquals(XFixes.FirstError, xfixes[11].toInt() and 0xff)

                val shape = readReply(socket.getInputStream())
                assertEquals(7, u16le(shape, 2))
                assertEquals(1, shape[8].toInt())
                assertEquals(XShape.MajorOpcode, shape[9].toInt() and 0xff)
                assertEquals(XShape.FirstEvent, shape[10].toInt() and 0xff)
                assertEquals(XShape.FirstError, shape[11].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ListExtensions validates length and returns names with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(99, 0, ByteArray(4)))
                out.write(request(99, 0, ByteArray(0)))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 99, badValue = 0, sequence = 1)

                val reply = readReply(socket.getInputStream())
                assertEquals(14, reply[1].toInt() and 0xff)
                assertEquals(2, u16le(reply, 2))
                assertEquals(33, u32le(reply, 4))
                var offset = 32
                val names = mutableListOf<String>()
                repeat(reply[1].toInt() and 0xff) {
                    val length = reply[offset++].toInt() and 0xff
                    names += reply.copyOfRange(offset, offset + length).decodeToString()
                    offset += length
                }
                assertEquals(listOf("GLX", "BIG-REQUESTS", "RENDER", "MIT-SHM", "XFIXES", "SHAPE", "XKEYBOARD", "XINERAMA", "XTEST", "XC-MISC", "MIT-SUNDRY-NONSTANDARD", "MIT-SCREEN-SAVER", "SYNC", "RANDR"), names)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES v1 requests validate framing and return cursor image`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                server.input.click(7, 9)
                val out = socket.getOutputStream()
                out.write(request(XFixes.MajorOpcode, XFixes.QueryVersion, ByteArray(4)))
                out.write(xfixesQueryVersionRequest(6, 0))
                out.write(xfixesQueryVersionRequest(0, 0))
                out.write(request(XFixes.MajorOpcode, XFixes.SelectSelectionInput, ByteArray(8)))
                out.write(xfixesSelectSelectionInputRequest(WindowId + 99, selection = 1))
                out.write(xfixesSelectSelectionInputRequest(X11Ids.RootWindow, selection = 1))
                out.write(request(XFixes.MajorOpcode, XFixes.SelectCursorInput, ByteArray(4)))
                out.write(xfixesSelectCursorInputRequest(WindowId + 99))
                out.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow))
                out.write(request(XFixes.MajorOpcode, XFixes.GetCursorImage, ByteArray(4)))
                out.write(xfixesGetCursorImageRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.QueryVersion, badValue = 0, sequence = 1)

                val version = readReply(socket.getInputStream())
                assertEquals(2, u16le(version, 2))
                assertEquals(XFixes.MajorVersion, u32le(version, 8))
                assertEquals(XFixes.MinorVersion, u32le(version, 12))

                val oldVersion = readReply(socket.getInputStream())
                assertEquals(3, u16le(oldVersion, 2))
                assertEquals(0, u32le(oldVersion, 8))
                assertEquals(0, u32le(oldVersion, 12))

                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SelectSelectionInput, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 3, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SelectSelectionInput, badValue = WindowId + 99, sequence = 5)
                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SelectCursorInput, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 3, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SelectCursorInput, badValue = WindowId + 99, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.GetCursorImage, badValue = 0, sequence = 10)

                val cursor = readReply(socket.getInputStream())
                assertEquals(11, u16le(cursor, 2))
                assertEquals(1, u32le(cursor, 4))
                assertEquals(7, u16le(cursor, 8))
                assertEquals(9, u16le(cursor, 10))
                assertEquals(1, u16le(cursor, 12))
                assertEquals(1, u16le(cursor, 14))
                assertEquals(0, u16le(cursor, 16))
                assertEquals(0, u16le(cursor, 18))
                assertEquals(1, u32le(cursor, 20))
                assertEquals(0, u32le(cursor, 32))

                val pointer = readReply(socket.getInputStream())
                assertEquals(12, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SelectSelectionInput validates selection atom and event mask`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingAtom = 0x0020_2001
                val out = socket.getOutputStream()
                out.write(xfixesSelectSelectionInputRequest(X11Ids.RootWindow, selection = missingAtom))
                out.write(
                    xfixesSelectSelectionInputRequest(
                        X11Ids.RootWindow,
                        selection = PrimaryAtom,
                        eventMask = XFixes.SelectionNotifyMask + 1,
                    ),
                )
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 5, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SelectSelectionInput, badValue = missingAtom, sequence = 1)
                assertError(socket.getInputStream(), error = 2, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SelectSelectionInput, badValue = XFixes.SelectionNotifyMask + 1, sequence = 2)
                assertEquals(3, u16le(readReply(socket.getInputStream()), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SelectCursorInput validates event mask`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val badMask = XFixes.DisplayCursorNotifyMask + 1
                val out = socket.getOutputStream()
                out.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow, eventMask = badMask))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SelectCursorInput, badValue = badMask, sequence = 1)
                assertEquals(2, u16le(readReply(socket.getInputStream()), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SelectCursorInput delivers display cursor notify events and unsubscribe stops delivery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { observer ->
                Socket("127.0.0.1", server.localPort).use { owner ->
                    observer.soTimeout = 2_000
                    owner.soTimeout = 2_000
                    setup(observer)
                    setup(owner)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observer.getInputStream()), 2))

                    val cursor = PixmapId + 90
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                    ownerOut.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                    ownerOut.write(grabPointerRequest(X11Ids.RootWindow, cursor = cursor))
                    ownerOut.flush()
                    assertEquals(1, readReply(owner.getInputStream())[0].toInt())
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 2,
                        timestamp = 1,
                    )

                    ownerOut.write(changeActivePointerGrabRequest(cursor = 0))
                    ownerOut.flush()
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 3,
                        timestamp = 1,
                    )

                    observerOut.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow, eventMask = 0))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(4, u16le(readReply(observer.getInputStream()), 2))

                    ownerOut.write(changeActivePointerGrabRequest(cursor = cursor))
                    ownerOut.flush()
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    val observerReply = readReply(observer.getInputStream())
                    assertEquals(1, observerReply[0].toInt())
                    assertEquals(5, u16le(observerReply, 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES cursor notify fires when grabbed cursor owner disconnects`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { observer ->
                Socket("127.0.0.1", server.localPort).use { owner ->
                    observer.soTimeout = 2_000
                    owner.soTimeout = 2_000
                    setup(observer)
                    setup(owner)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observer.getInputStream()), 2))

                    val cursor = PixmapId + 100
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                    ownerOut.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                    ownerOut.write(grabPointerRequest(X11Ids.RootWindow, cursor = cursor))
                    ownerOut.flush()
                    assertEquals(1, readReply(owner.getInputStream())[0].toInt())
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 2,
                        timestamp = 1,
                    )

                    owner.close()
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 3,
                        timestamp = 1,
                    )

                    observerOut.write(xfixesGetCursorImageRequest())
                    observerOut.flush()
                    val image = readReply(observer.getInputStream())
                    assertEquals(3, u32le(image, 20))
                    assertEquals(0x0000_0000, u32le(image, 32))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES cursor notify tracks window cursor changes recolor and GetCursorImage serial`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { observer ->
                Socket("127.0.0.1", server.localPort).use { owner ->
                    observer.soTimeout = 2_000
                    owner.soTimeout = 2_000
                    setup(observer)
                    setup(owner)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observer.getInputStream()), 2))

                    val cursor = PixmapId + 91
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                    ownerOut.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                    ownerOut.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, cursor))
                    ownerOut.write(
                        recolorCursorRequest(
                            cursor,
                            foregroundRed = 0,
                            foregroundBlue = 0xffff,
                            backgroundRed = 0,
                            backgroundGreen = 0xffff,
                        ),
                    )
                    ownerOut.write(xfixesGetCursorImageRequest())
                    ownerOut.flush()

                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 2,
                        timestamp = 1,
                    )
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 3,
                        timestamp = 1,
                    )
                    val cursorImage = readReply(owner.getInputStream())
                    assertEquals(3, u32le(cursorImage, 20))
                    assertEquals(0xff00_ff00.toInt(), u32le(cursorImage, 32))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES GetCursorImage returns displayed pixmap cursor pixels`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val source = PixmapId + 91
                val mask = PixmapId + 92
                val cursor = PixmapId + 93
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(source, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(mask, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createGcRequest(GcId, foreground = 1, background = 0, drawable = source))
                out.write(putImageBitmapRequest(source, GcId, width = 2, height = 2, bits = listOf(true, false, false, true)))
                out.write(putImageBitmapRequest(mask, GcId, width = 2, height = 2, bits = listOf(true, true, false, true)))
                out.write(
                    createCursorRequest(
                        cursor,
                        source = source,
                        mask = mask,
                        x = 1,
                        y = 0,
                        foregroundRed = 0xffff,
                        foregroundGreen = 0,
                        foregroundBlue = 0,
                        backgroundRed = 0,
                        backgroundGreen = 0xffff,
                        backgroundBlue = 0,
                    ),
                )
                out.write(putImageBitmapRequest(source, GcId, width = 2, height = 2, bits = listOf(false, true, true, false)))
                out.write(putImageBitmapRequest(mask, GcId, width = 2, height = 2, bits = listOf(false, false, true, false)))
                out.write(freePixmapRequest(source))
                out.write(freePixmapRequest(mask))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, cursor))
                out.write(xfixesGetCursorImageRequest())
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(4, u32le(image, 4))
                assertEquals(2, u16le(image, 12))
                assertEquals(2, u16le(image, 14))
                assertEquals(1, u16le(image, 16))
                assertEquals(0, u16le(image, 18))
                assertEquals(2, u32le(image, 20))
                assertEquals(0xffff_0000.toInt(), u32le(image, 32))
                assertEquals(0xff00_ff00.toInt(), u32le(image, 36))
                assertEquals(0x0000_0000, u32le(image, 40))
                assertEquals(0xffff_0000.toInt(), u32le(image, 44))

                out.write(freeCursorRequest(cursor))
                out.write(xfixesGetCursorImageRequest())
                out.flush()

                val retainedImage = readReply(socket.getInputStream())
                assertEquals(4, u32le(retainedImage, 4))
                assertEquals(2, u16le(retainedImage, 12))
                assertEquals(2, u16le(retainedImage, 14))
                assertEquals(2, u32le(retainedImage, 20))
                assertEquals(0xffff_0000.toInt(), u32le(retainedImage, 32))
                assertEquals(0xff00_ff00.toInt(), u32le(retainedImage, 36))
                assertEquals(0x0000_0000, u32le(retainedImage, 40))
                assertEquals(0xffff_0000.toInt(), u32le(retainedImage, 44))

                val replacementSource = PixmapId + 94
                out.write(createPixmapRequest(replacementSource, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(putImageBitmapRequest(replacementSource, GcId, width = 2, height = 2, bits = listOf(true, true, true, true)))
                out.write(
                    createCursorRequest(
                        cursor,
                        source = replacementSource,
                        mask = 0,
                        foregroundRed = 0,
                        foregroundGreen = 0,
                        foregroundBlue = 0xffff,
                    ),
                )
                out.write(
                    recolorCursorRequest(
                        cursor,
                        foregroundRed = 0xffff,
                        foregroundGreen = 0xffff,
                        foregroundBlue = 0xffff,
                        backgroundRed = 0,
                        backgroundGreen = 0,
                        backgroundBlue = 0,
                    ),
                )
                out.write(xfixesGetCursorImageRequest())
                out.flush()

                val retainedAfterReusedId = readReply(socket.getInputStream())
                assertEquals(4, u32le(retainedAfterReusedId, 4))
                assertEquals(2, u16le(retainedAfterReusedId, 12))
                assertEquals(2, u16le(retainedAfterReusedId, 14))
                assertEquals(2, u32le(retainedAfterReusedId, 20))
                assertEquals(0xffff_0000.toInt(), u32le(retainedAfterReusedId, 32))
                assertEquals(0xff00_ff00.toInt(), u32le(retainedAfterReusedId, 36))
                assertEquals(0x0000_0000, u32le(retainedAfterReusedId, 40))
                assertEquals(0xffff_0000.toInt(), u32le(retainedAfterReusedId, 44))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES GetCursorImage stops at displayed child cursor without cached pixels`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val source = PixmapId + 96
                val rootCursor = PixmapId + 97
                val font = PixmapId + 98
                val childCursor = PixmapId + 99
                val child = WindowId + 11
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(source, width = 2, height = 2, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createGcRequest(GcId, foreground = 1, background = 0, drawable = source))
                out.write(putImageBitmapRequest(source, GcId, width = 2, height = 2, bits = listOf(true, true, true, true)))
                out.write(createCursorRequest(rootCursor, source = source, mask = 0))
                out.write(openFontRequest(font))
                out.write(createGlyphCursorRequest(childCursor, sourceFont = font, maskFont = 0))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, rootCursor))
                out.write(createWindowRequest(child, x = 0, y = 0, width = 20, height = 20, cursor = childCursor))
                out.write(mapWindowRequest(child))
                out.write(xfixesGetCursorImageRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), child)
                val image = readReply(socket.getInputStream())
                assertEquals(1, u32le(image, 4))
                assertEquals(1, u16le(image, 12))
                assertEquals(1, u16le(image, 14))
                assertEquals(0, u16le(image, 16))
                assertEquals(0, u16le(image, 18))
                assertEquals(0x0000_0000, u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES v2 cursor names round trip and follow displayed cursor snapshots`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val source = PixmapId + 100
                val cursor = PixmapId + 101
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(source, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = source, mask = 0, x = 0, y = 0))
                out.write(xfixesGetCursorNameRequest(cursor))
                out.write(xfixesSetCursorNameRequest(cursor, "left_ptr"))
                out.write(xfixesGetCursorNameRequest(cursor))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, cursor))
                out.write(xfixesGetCursorImageAndNameRequest())
                out.write(freeCursorRequest(cursor))
                out.write(xfixesGetCursorImageAndNameRequest())
                out.flush()

                val unnamed = readReply(socket.getInputStream())
                assertEquals(0, u32le(unnamed, 8))
                assertEquals(0, u16le(unnamed, 12))
                assertEquals(0, u32le(unnamed, 4))

                val named = readReply(socket.getInputStream())
                val atom = u32le(named, 8)
                assertTrue(atom != 0)
                assertEquals("left_ptr", named.copyOfRange(32, 32 + u16le(named, 12)).decodeToString())

                val imageAndName = readReply(socket.getInputStream())
                assertEquals(7, u16le(imageAndName, 2))
                assertEquals(3, u32le(imageAndName, 4))
                assertEquals(1, u16le(imageAndName, 12))
                assertEquals(1, u16le(imageAndName, 14))
                assertEquals(2, u32le(imageAndName, 20))
                assertEquals(atom, u32le(imageAndName, 24))
                assertEquals("left_ptr", imageAndName.copyOfRange(36, 36 + u16le(imageAndName, 28)).decodeToString())

                val retained = readReply(socket.getInputStream())
                assertEquals(3, u32le(retained, 4))
                assertEquals(2, u32le(retained, 20))
                assertEquals(atom, u32le(retained, 24))
                assertEquals("left_ptr", retained.copyOfRange(36, 36 + u16le(retained, 28)).decodeToString())

                val replacementSource = PixmapId + 102
                out.write(createPixmapRequest(replacementSource, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = replacementSource, mask = 0, foregroundBlue = 0xffff))
                out.write(xfixesSetCursorNameRequest(cursor, "text"))
                out.write(xfixesGetCursorImageAndNameRequest())
                out.flush()

                val retainedAfterReusedId = readReply(socket.getInputStream())
                assertEquals(3, u32le(retainedAfterReusedId, 4))
                assertEquals(2, u32le(retainedAfterReusedId, 20))
                assertEquals(atom, u32le(retainedAfterReusedId, 24))
                assertEquals("left_ptr", retainedAfterReusedId.copyOfRange(36, 36 + u16le(retainedAfterReusedId, 28)).decodeToString())
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES v2 cursor name requests validate framing cursors and recover stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val missingCursor = PixmapId + 102
                val out = socket.getOutputStream()
                out.write(request(XFixes.MajorOpcode, XFixes.SetCursorName, ByteArray(4)))
                out.write(xfixesSetCursorNameRequest(missingCursor, "missing"))
                out.write(request(XFixes.MajorOpcode, XFixes.GetCursorName, ByteArray(0)))
                out.write(xfixesGetCursorNameRequest(missingCursor))
                out.write(request(XFixes.MajorOpcode, XFixes.GetCursorImageAndName, ByteArray(4)))
                out.write(request(XFixes.MajorOpcode, XFixes.ChangeCursor, ByteArray(4)))
                out.write(xfixesChangeCursorRequest(missingCursor, missingCursor + 1))
                out.write(request(XFixes.MajorOpcode, XFixes.ChangeCursorByName, ByteArray(4)))
                out.write(xfixesChangeCursorByNameRequest(missingCursor, "missing"))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SetCursorName, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 6, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.SetCursorName, badValue = missingCursor, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.GetCursorName, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 6, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.GetCursorName, badValue = missingCursor, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.GetCursorImageAndName, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeCursor, badValue = 0, sequence = 6)
                assertError(socket.getInputStream(), error = 6, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeCursor, badValue = missingCursor, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeCursorByName, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 6, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeCursorByName, badValue = missingCursor, sequence = 9)
                assertEquals(10, u16le(readReply(socket.getInputStream()), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES v2 ChangeCursor replaces displayed cursor content and keeps destination name`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val destinationSource = PixmapId + 103
                val sourceSource = PixmapId + 104
                val destinationCursor = PixmapId + 105
                val sourceCursor = PixmapId + 106
                val out = socket.getOutputStream()
                out.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow))
                out.write(queryPointerRequest())
                out.write(createPixmapRequest(destinationSource, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(sourceSource, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createGcRequest(GcId, foreground = 1, background = 0, drawable = destinationSource))
                out.write(putImageBitmapRequest(destinationSource, GcId, width = 1, height = 1, bits = listOf(true)))
                out.write(putImageBitmapRequest(sourceSource, GcId, width = 1, height = 1, bits = listOf(true)))
                out.write(createCursorRequest(destinationCursor, source = destinationSource, mask = 0))
                out.write(createCursorRequest(sourceCursor, source = sourceSource, mask = 0, foregroundRed = 0, foregroundBlue = 0xffff))
                out.write(xfixesSetCursorNameRequest(destinationCursor, "left_ptr"))
                out.write(xfixesGetCursorNameRequest(destinationCursor))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, destinationCursor))
                out.write(xfixesChangeCursorRequest(sourceCursor, destinationCursor))
                out.write(xfixesGetCursorImageAndNameRequest())
                out.flush()

                assertEquals(2, u16le(readReply(socket.getInputStream()), 2))
                val name = readReply(socket.getInputStream())
                val atom = u32le(name, 8)
                assertTrue(atom != 0)
                assertXFixesCursorNotify(
                    socket.getInputStream().readExactly(32),
                    sequence = 12,
                    window = X11Ids.RootWindow,
                    cursorSerial = 2,
                    timestamp = 1,
                    name = atom,
                )
                assertXFixesCursorNotify(
                    socket.getInputStream().readExactly(32),
                    sequence = 13,
                    window = X11Ids.RootWindow,
                    cursorSerial = 3,
                    timestamp = 1,
                    name = atom,
                )
                val changed = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), u32le(changed, 32))
                assertEquals(atom, u32le(changed, 24))
                assertEquals("left_ptr", changed.copyOfRange(36, 36 + u16le(changed, 28)).decodeToString())
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES v2 ChangeCursorByName replaces named cursors and escapes semantic names`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val destinationSource = PixmapId + 107
                val sourceSource = PixmapId + 108
                val destinationCursor = PixmapId + 109
                val sourceCursor = PixmapId + 110
                val cursorName = "line\nquote\"tab\tzero\u0000"
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(destinationSource, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createPixmapRequest(sourceSource, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createGcRequest(GcId, foreground = 1, background = 0, drawable = destinationSource))
                out.write(putImageBitmapRequest(destinationSource, GcId, width = 1, height = 1, bits = listOf(true)))
                out.write(putImageBitmapRequest(sourceSource, GcId, width = 1, height = 1, bits = listOf(true)))
                out.write(createCursorRequest(destinationCursor, source = destinationSource, mask = 0))
                out.write(createCursorRequest(sourceCursor, source = sourceSource, mask = 0, foregroundRed = 0, foregroundGreen = 0xffff))
                out.write(xfixesSetCursorNameRequest(destinationCursor, cursorName))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, destinationCursor))
                out.write(xfixesChangeCursorByNameRequest(sourceCursor, cursorName))
                out.write(xfixesGetCursorImageAndNameRequest())
                out.flush()

                val changed = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(changed, 32))
                assertEquals(cursorName, changed.copyOfRange(36, 36 + u16le(changed, 28)).decodeToString())
                val cursorJson = Regex("""\{"id":"0x${destinationCursor.toUInt().toString(16)}".*?\}""").find(httpGet(server.localPort, "/state.json"))?.value.orEmpty()
                assertContains(cursorJson, "\\n")
                assertContains(cursorJson, "\\\"")
                assertContains(cursorJson, "\\t")
                assertContains(cursorJson, "\\u0000")
                assertFalse(cursorJson.contains('\n'))
                assertFalse(cursorJson.contains('\u0000'))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateCursor rejects oversized cursor image snapshots and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val source = PixmapId + 94
                val cursor = PixmapId + 95
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(source, width = 65535, height = 65535, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = source, mask = 0))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 11, opcode = 93, badValue = 0, sequence = 2)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt() and 0xff)
                assertEquals(3, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES cursor notify tracks passive GrabButton cursor activation and release`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { observer ->
                Socket("127.0.0.1", server.localPort).use { owner ->
                    observer.soTimeout = 2_000
                    owner.soTimeout = 2_000
                    setup(observer)
                    setup(owner)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observer.getInputStream()), 2))

                    val cursor = PixmapId + 92
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                    ownerOut.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                    ownerOut.write(grabButtonRequest(X11Ids.RootWindow, eventMask = 0, cursor = cursor))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(4, u16le(readReply(owner.getInputStream()), 2))

                    server.input.pointerDown(10, 10, button = 1)
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 2,
                        timestamp = 1,
                    )

                    server.input.pointerUp(10, 10, button = 1)
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 3,
                        timestamp = 2,
                    )
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES cursor notify tracks mapped window cursor under stationary pointer`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { observer ->
                Socket("127.0.0.1", server.localPort).use { owner ->
                    observer.soTimeout = 2_000
                    owner.soTimeout = 2_000
                    setup(observer)
                    setup(owner)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(xfixesSelectCursorInputRequest(X11Ids.RootWindow))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observer.getInputStream()), 2))

                    val child = WindowId + 701
                    val cursor = PixmapId + 93
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                    ownerOut.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                    ownerOut.write(createWindowRequest(child, x = 0, y = 0, width = 20, height = 20, cursor = cursor))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.write(unmapWindowRequest(child))
                    ownerOut.flush()

                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 2,
                        timestamp = 1,
                    )
                    assertXFixesCursorNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        window = X11Ids.RootWindow,
                        cursorSerial = 3,
                        timestamp = 1,
                    )
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SelectSelectionInput delivers SetSelectionOwner notify events`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { observer ->
                Socket("127.0.0.1", server.localPort).use { owner ->
                    observer.soTimeout = 2_000
                    owner.soTimeout = 2_000
                    setup(observer)
                    setup(owner)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(
                        xfixesSelectSelectionInputRequest(
                            X11Ids.RootWindow,
                            selection = PrimaryAtom,
                            eventMask = XFixes.SetSelectionOwnerNotifyMask,
                        ),
                    )
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observer.getInputStream()), 2))

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                    ownerOut.write(getSelectionOwnerRequest(PrimaryAtom))
                    ownerOut.flush()
                    assertEquals(WindowId, u32le(readReply(owner.getInputStream()), 8))
                    assertXFixesSelectionNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        subtype = XFixes.SetSelectionOwnerNotify,
                        window = X11Ids.RootWindow,
                        owner = WindowId,
                        selection = PrimaryAtom,
                        timestamp = 1,
                    )

                    ownerOut.write(setSelectionOwnerRequest(0, PrimaryAtom))
                    ownerOut.write(getSelectionOwnerRequest(PrimaryAtom))
                    ownerOut.flush()
                    val clear = owner.getInputStream().readExactly(32)
                    assertEquals(29, clear[0].toInt() and 0xff)
                    assertEquals(4, u16le(clear, 2))
                    assertEquals(WindowId, u32le(clear, 8))
                    assertEquals(PrimaryAtom, u32le(clear, 12))
                    assertEquals(0, u32le(readReply(owner.getInputStream()), 8))
                    assertXFixesSelectionNotify(
                        observer.getInputStream().readExactly(32),
                        sequence = 2,
                        subtype = XFixes.SetSelectionOwnerNotify,
                        window = X11Ids.RootWindow,
                        owner = 0,
                        selection = PrimaryAtom,
                        timestamp = 1,
                    )
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SelectSelectionInput delivers owner loss notify subtypes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { observer ->
                observer.soTimeout = 2_000
                setup(observer)
                val observerOut = observer.getOutputStream()
                observerOut.write(
                    xfixesSelectSelectionInputRequest(
                        X11Ids.RootWindow,
                        selection = PrimaryAtom,
                        eventMask = XFixes.SelectionNotifyMask,
                    ),
                )
                observerOut.write(queryPointerRequest())
                observerOut.flush()
                assertEquals(2, u16le(readReply(observer.getInputStream()), 2))

                Socket("127.0.0.1", server.localPort).use { owner ->
                    owner.soTimeout = 2_000
                    setup(owner)
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                    ownerOut.write(destroyWindowRequest(WindowId))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(4, u16le(readReply(owner.getInputStream()), 2))
                }
                assertXFixesSelectionNotify(
                    observer.getInputStream().readExactly(32),
                    sequence = 2,
                    subtype = XFixes.SetSelectionOwnerNotify,
                    window = X11Ids.RootWindow,
                    owner = WindowId,
                    selection = PrimaryAtom,
                    timestamp = 1,
                )
                assertXFixesSelectionNotify(
                    observer.getInputStream().readExactly(32),
                    sequence = 2,
                    subtype = XFixes.SelectionWindowDestroyNotify,
                    window = X11Ids.RootWindow,
                    owner = 0,
                    selection = PrimaryAtom,
                    timestamp = 1,
                    selectionTimestamp = 1,
                )

                val retainedWindow = WindowId + 1
                Socket("127.0.0.1", server.localPort).use { owner ->
                    owner.soTimeout = 2_000
                    setup(owner)
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(retainedWindow))
                    ownerOut.write(setSelectionOwnerRequest(retainedWindow, PrimaryAtom))
                    ownerOut.write(setCloseDownModeRequest(XCloseDownMode.RetainPermanent))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(4, u16le(readReply(owner.getInputStream()), 2))
                    closeClientAndWait(owner)
                }
                assertXFixesSelectionNotify(
                    observer.getInputStream().readExactly(32),
                    sequence = 2,
                    subtype = XFixes.SetSelectionOwnerNotify,
                    window = X11Ids.RootWindow,
                    owner = retainedWindow,
                    selection = PrimaryAtom,
                    timestamp = 1,
                )
                assertXFixesSelectionNotify(
                    observer.getInputStream().readExactly(32),
                    sequence = 2,
                    subtype = XFixes.SelectionClientCloseNotify,
                    window = X11Ids.RootWindow,
                    owner = 0,
                    selection = PrimaryAtom,
                    timestamp = 1,
                    selectionTimestamp = 1,
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES ChangeSaveSet mirrors core save set validation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { managerSocket ->
                    ownerSocket.soTimeout = 2_000
                    managerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(managerSocket)
                    val child = WindowId + 88
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(child))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val managerOut = managerSocket.getOutputStream()
                    val frame = WindowId + 89
                    managerOut.write(request(XFixes.MajorOpcode, XFixes.ChangeSaveSet, ByteArray(4)))
                    managerOut.write(xfixesChangeSaveSetRequest(mode = 2, target = XFixes.SaveSetNearest, map = XFixes.SaveSetMap, window = child))
                    managerOut.write(xfixesChangeSaveSetRequest(mode = 0, target = 2, map = XFixes.SaveSetMap, window = child))
                    managerOut.write(xfixesChangeSaveSetRequest(mode = 0, target = XFixes.SaveSetNearest, map = 2, window = child))
                    managerOut.write(xfixesChangeSaveSetRequest(mode = 0, target = XFixes.SaveSetNearest, map = XFixes.SaveSetMap, window = WindowId + 99))
                    managerOut.write(createWindowRequest(frame, x = 10, y = 10, width = 50, height = 40))
                    managerOut.write(reparentWindowRequest(child, frame, x = 7, y = 8))
                    managerOut.write(xfixesChangeSaveSetRequest(mode = 0, target = XFixes.SaveSetNearest, map = XFixes.SaveSetMap, window = child))
                    managerOut.write(queryPointerRequest())
                    managerOut.flush()

                    assertError(managerSocket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeSaveSet, badValue = 0, sequence = 1)
                    assertError(managerSocket.getInputStream(), error = 2, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeSaveSet, badValue = 2, sequence = 2)
                    assertError(managerSocket.getInputStream(), error = 2, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeSaveSet, badValue = 2, sequence = 3)
                    assertError(managerSocket.getInputStream(), error = 2, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeSaveSet, badValue = 2, sequence = 4)
                    assertError(managerSocket.getInputStream(), error = 3, opcode = XFixes.MajorOpcode, minorOpcode = XFixes.ChangeSaveSet, badValue = WindowId + 99, sequence = 5)

                    val pointer = readReply(managerSocket.getInputStream())
                    assertEquals(9, u16le(pointer, 2))
                    closeClientAndWait(managerSocket)
                    assertEquals(listOf(child), waitForRootChildren(server.localPort) { it == listOf(child) })
                    val json = httpGet(server.localPort, "/state.json")
                    assertContains(
                        json,
                        windowJsonId(child) + ""","parent":"${X11Ids.RootWindow.toJsonHex()}","x":17,"y":18,"localX":17,"localY":18,"width":40,"height":30""",
                    )
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render query requests validate lengths and indexed format kind`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(renderQueryVersionRequest(bodySize = 4))
                out.write(renderQueryVersionRequest())
                out.write(renderRequest(1, ByteArray(4)))
                out.write(renderRequest(1, ByteArray(0)))
                out.write(renderRequest(2, ByteArray(0)))
                out.write(renderQueryPictIndexValuesRequest(0x7fff_0001))
                out.write(renderRequest(2, ByteArray(8).also { put32le(it, 0, 0x7fff_0002) }))
                out.write(renderQueryPictIndexValuesRequest(XRender.Argb32Format))
                out.write(renderRequest(3, ByteArray(0)))
                out.write(renderQueryDithersRequest(WindowId + 0x44))
                out.write(renderQueryDithersRequest(X11Ids.RootWindow))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 0, badValue = 0, sequence = 1)
                val version = readReply(socket.getInputStream())
                assertEquals(1, version[0].toInt())
                assertEquals(2, u16le(version, 2))
                assertEquals(XRender.MajorVersion, u32le(version, 8))
                assertEquals(XRender.MinorVersion, u32le(version, 12))

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 1, badValue = 0, sequence = 3)
                val formats = readReply(socket.getInputStream())
                assertEquals(1, formats[0].toInt())
                assertEquals(4, u16le(formats, 2))
                assertEquals(4, u32le(formats, 8))

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 2, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = 2, badValue = 0x7fff_0001, sequence = 6)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 2, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 8, opcode = XRender.MajorOpcode, minorOpcode = 2, badValue = XRender.Argb32Format, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 3, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 9, opcode = XRender.MajorOpcode, minorOpcode = 3, badValue = WindowId + 0x44, sequence = 10)
                val dithers = readReply(socket.getInputStream())
                assertEquals(1, dithers[0].toInt())
                assertEquals(11, u16le(dithers, 2))
                assertEquals(0, u32le(dithers, 4))
                assertEquals(0, u32le(dithers, 8))
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(12, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render picture requests validate resources value masks and update repeat`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x300
                val missingDrawable = WindowId + 409
                val missingPicture = picture + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderRequest(4, ByteArray(12)))
                out.write(renderCreatePictureRequest(picture, valueMask = XRender.CPRepeat))
                out.write(renderCreatePictureRequest(picture, format = 0x7fff_1001))
                out.write(renderCreatePictureRequest(picture, drawable = missingDrawable))
                out.write(renderCreatePictureRequest(picture, format = XRender.A8Format))
                out.write(renderCreatePictureRequest(picture, WindowId, XRender.Rgb24Format, 0x0000_2000, 0))
                out.write(renderCreatePictureRequest(picture, WindowId, XRender.Rgb24Format, XRender.CPRepeat, XRender.RepeatNormal))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderRequest(5, ByteArray(4)))
                out.write(renderChangePictureRequest(missingPicture, valueMask = 0))
                out.write(renderChangePictureRequest(picture, valueMask = XRender.CPRepeat))
                out.write(renderChangePictureRequest(picture, 0x0000_2000, 0))
                out.write(renderChangePictureRequest(picture, XRender.CPRepeat, XRender.RepeatPad))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = 0x7fff_1001, sequence = 4)
                assertError(socket.getInputStream(), error = 9, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = missingDrawable, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = XRender.A8Format, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = 0x0000_2000, sequence = 7)
                assertError(socket.getInputStream(), error = 14, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = picture, sequence = 9)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 0, sequence = 10)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = missingPicture, sequence = 11)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 0, sequence = 12)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 0x0000_2000, sequence = 13)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(15, u16le(pointer, 2))
                assertContains(
                    httpGet(server.localPort, "/state.json"),
                    """"id":"0x${picture.toString(16)}","drawable":"0x${WindowId.toString(16)}","kind":"window","format":${XRender.Rgb24Format},"repeat":"pad"""",
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render picture requests validate picture attribute values before mutating state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x330
                val missingPicture = picture + 1
                val missingPixmap = PixmapId + 0x331
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(picture, WindowId, XRender.Rgb24Format, XRender.CPComponentAlpha, 2))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderChangePictureRequest(picture, XRender.CPRepeat, 99))
                out.write(renderChangePictureRequest(picture, XRender.CPGraphicsExposure, 2))
                out.write(renderChangePictureRequest(picture, XRender.CPComponentAlpha, 2))
                out.write(renderChangePictureRequest(picture, XRender.CPSubwindowMode, 2))
                out.write(renderChangePictureRequest(picture, XRender.CPPolyEdge, 2))
                out.write(renderChangePictureRequest(picture, XRender.CPPolyMode, 2))
                out.write(renderChangePictureRequest(picture, XRender.CPAlphaMap, missingPicture))
                out.write(renderChangePictureRequest(picture, XRender.CPClipMask, missingPixmap))
                out.write(renderChangePictureRequest(picture, XRender.CPRepeat, XRender.RepeatPad))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = 2, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 99, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 2, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 2, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 2, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 2, sequence = 8)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 2, sequence = 9)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = missingPicture, sequence = 10)
                assertError(socket.getInputStream(), error = 4, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = missingPixmap, sequence = 11)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(13, u16le(pointer, 2))
                val json = httpGet(server.localPort, "/state.json")
                assertContains(
                    json,
                    """"id":"0x${picture.toString(16)}","drawable":"0x${WindowId.toString(16)}","kind":"window","format":${XRender.Rgb24Format},"repeat":"pad"""",
                )
                assertContains(json, """"alphaMap":"none"""")
                assertContains(json, """"graphicsExposure":false""")
                assertContains(json, """"subwindowMode":0""")
                assertContains(json, """"polyEdge":1""")
                assertContains(json, """"polyMode":0""")
                assertContains(json, """"componentAlpha":false""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render SetPictureClipRectangles validates request framing and picture resources`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x330
                val missingPicture = picture + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderRequest(6, ByteArray(4)))
                out.write(renderSetPictureClipRectanglesRaw(ByteArray(12).also { put32le(it, 0, picture) }))
                out.write(renderSetPictureClipRectanglesRequest(missingPicture, 0, 0, emptyList()))
                out.write(
                    renderSetPictureClipRectanglesRequest(
                        picture,
                        originX = 5,
                        originY = -2,
                        rectangles = listOf(
                            XRectangleCommand(1, 2, 3, 4),
                            XRectangleCommand(-3, 6, 2, 1),
                        ),
                    ),
                )
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 6, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 6, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 6, badValue = missingPicture, sequence = 5)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(7, u16le(pointer, 2))
                val state = httpGet(server.localPort, "/state.json")
                assertContains(state, """"id":"0x${picture.toString(16)}"""")
                assertContains(state, """"clipRectangles":2""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render FreePicture validates request framing and removes picture resources`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x360
                val missingPicture = picture + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderRequest(7, ByteArray(0)))
                out.write(renderRequest(7, ByteArray(8).also { put32le(it, 0, picture) }))
                out.write(renderFreePictureRequest(WindowId))
                out.write(renderFreePictureRequest(missingPicture))
                out.write(renderFreePictureRequest(picture))
                out.write(renderChangePictureRequest(picture, valueMask = 0))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 7, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 7, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 7, badValue = WindowId, sequence = 5)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 7, badValue = missingPicture, sequence = 6)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = picture, sequence = 8)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(9, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"renderPictures":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render SetPictureTransform validates framing picture resources and matrix invertibility`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x390
                val missingPicture = picture + 1
                val transform = listOf(
                    0x0001_0000,
                    0,
                    0x0002_0000,
                    0,
                    0x0001_0000,
                    0x0003_0000,
                    0,
                    0,
                    0x0001_0000,
                )
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderRequest(28, ByteArray(36)))
                out.write(renderSetPictureTransformRaw(ByteArray(44).also { put32le(it, 0, picture) }))
                out.write(renderSetPictureTransformRequest(missingPicture, transform))
                out.write(renderSetPictureTransformRequest(picture, List(9) { 0 }))
                out.write(renderSetPictureTransformRequest(picture, transform))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 28, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 28, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 28, badValue = missingPicture, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 28, badValue = 0, sequence = 6)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(8, u16le(pointer, 2))
                assertContains(
                    httpGet(server.localPort, "/state.json"),
                    """"transform":["0x10000","0x0","0x20000","0x0","0x10000","0x30000","0x0","0x0","0x10000"]""",
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render SetPictureFilter validates framing picture resources and filter names`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x3c0
                val missingPicture = picture + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderRequest(30, ByteArray(4)))
                out.write(renderSetPictureFilterRequest(missingPicture, "nearest"))
                out.write(renderSetPictureFilterRaw(ByteArray(12).also {
                    put32le(it, 0, picture)
                    put16le(it, 4, 5)
                    "ne".encodeToByteArray().copyInto(it, 8)
                }))
                out.write(renderSetPictureFilterRequest(picture, "unsupported"))
                out.write(renderSetPictureFilterRequest(picture, "bilinear", 0x0001_0000, 0x0002_0000))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = missingPicture, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = 0, sequence = 6)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(8, u16le(pointer, 2))
                val state = httpGet(server.localPort, "/state.json")
                assertContains(state, """"filter":"bilinear"""")
                assertContains(state, """"filterValues":["0x10000","0x20000"]""")
                assertEquals(false, state.contains("unsupported"))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render Composite validates framing and picture resources`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val source = PixmapId + 0x3f0
                val destination = source + 1
                val missingSource = source + 2
                val missingMask = source + 3
                val missingDestination = source + 4
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(source))
                out.write(renderCreatePictureRequest(destination))
                out.write(renderRequest(8, ByteArray(28)))
                out.write(renderCompositeRaw(ByteArray(36).also {
                    it[0] = XRender.OpSrc.toByte()
                    put32le(it, 4, source)
                    put32le(it, 12, destination)
                }))
                out.write(renderCompositeRequest(missingSource, destination = destination))
                out.write(renderCompositeRequest(source, mask = missingMask, destination = destination))
                out.write(renderCompositeRequest(source, destination = missingDestination))
                out.write(renderCompositeRequest(source, destination = destination))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = missingSource, sequence = 6)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = missingMask, sequence = 7)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = missingDestination, sequence = 8)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(10, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"drawings":1""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render Composite validates operator values`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val source = PixmapId + 0x410
                val destination = source + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(source))
                out.write(renderCreatePictureRequest(destination))
                out.write(renderCompositeRequest(source, destination = destination, operation = 0x2c))
                out.write(renderCompositeRequest(source, destination = destination, operation = XRender.OpBlendMultiply))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = 0x2c, sequence = 4)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(6, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"drawings":1""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render FillRectangles validates framing destination picture and operators`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x430
                val missingPicture = picture + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderRequest(26, ByteArray(12)))
                out.write(renderFillRectanglesRaw(ByteArray(20).also {
                    it[0] = XRender.OpSrc.toByte()
                    put32le(it, 4, picture)
                }))
                out.write(renderFillRectanglesRequest(missingPicture))
                out.write(renderFillRectanglesRequest(picture, operation = 0x2c))
                out.write(renderFillRectanglesRequest(picture, operation = XRender.OpBlendMultiply))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 26, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 26, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 26, badValue = missingPicture, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = XRender.MajorOpcode, minorOpcode = 26, badValue = 0x2c, sequence = 6)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(8, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"drawings":1""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render CreateCursor validates framing source picture and duplicate ids`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val picture = PixmapId + 0x450
                val cursor = picture + 1
                val missingPicture = picture + 2
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePictureRequest(picture))
                out.write(renderRequest(27, ByteArray(8)))
                out.write(renderCreateCursorRaw(ByteArray(16).also {
                    put32le(it, 0, cursor)
                    put32le(it, 4, picture)
                }))
                out.write(renderCreateCursorRequest(cursor, missingPicture))
                out.write(renderCreateCursorRequest(cursor, picture))
                out.write(renderCreateCursorRequest(cursor, picture))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 27, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = XRender.MajorOpcode, minorOpcode = 27, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 27, badValue = missingPicture, sequence = 5)
                assertError(socket.getInputStream(), error = 14, opcode = XRender.MajorOpcode, minorOpcode = 27, badValue = cursor, sequence = 7)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(8, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render CreateCursor snapshots displayed source picture pixels for XFIXES cursor image`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val pixmap = PixmapId + 0x460
                val picture = pixmap + 1
                val cursor = picture + 1
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(pixmap, width = 2, height = 1, depth = 32, drawable = X11Ids.RootWindow))
                out.write(renderCreatePictureRequest(picture, drawable = pixmap, format = XRender.Argb32Format))
                out.write(renderFillRectanglesRequest(picture, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, x = 0, y = 0))
                out.write(renderFillRectanglesRequest(picture, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0x8000, x = 1, y = 0))
                out.write(renderCreateCursorRequest(cursor, picture, x = 1, y = 0))
                out.write(renderFillRectanglesRequest(picture, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, x = 1, y = 0))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, cursor))
                out.write(xfixesGetCursorImageRequest())
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(2, u32le(image, 4))
                assertEquals(2, u16le(image, 12))
                assertEquals(1, u16le(image, 14))
                assertEquals(1, u16le(image, 16))
                assertEquals(0, u16le(image, 18))
                assertEquals(2, u32le(image, 20))
                assertEquals(0xffff_0000.toInt(), u32le(image, 32))
                assertEquals(0x8000_ff00.toInt(), u32le(image, 36))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Render CreateAnimCursor exposes first frame image for XFIXES cursor image`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val pixmap = PixmapId + 0x470
                val picture = pixmap + 1
                val firstFrameCursor = picture + 1
                val secondFrameCursor = firstFrameCursor + 1
                val animatedCursor = secondFrameCursor + 1
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(pixmap, width = 2, height = 1, depth = 32, drawable = X11Ids.RootWindow))
                out.write(renderCreatePictureRequest(picture, drawable = pixmap, format = XRender.Argb32Format))
                out.write(renderFillRectanglesRequest(picture, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, x = 0, y = 0))
                out.write(renderFillRectanglesRequest(picture, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff, x = 1, y = 0))
                out.write(renderCreateCursorRequest(firstFrameCursor, picture, x = 1, y = 0))
                out.write(renderFillRectanglesRequest(picture, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, x = 0, y = 0))
                out.write(renderFillRectanglesRequest(picture, red = 0xffff, green = 0xffff, blue = 0x0000, alpha = 0xffff, x = 1, y = 0))
                out.write(renderCreateCursorRequest(secondFrameCursor, picture, x = 0, y = 0))
                out.write(renderCreateAnimCursorRequest(animatedCursor, firstFrameCursor to 100, secondFrameCursor to 200))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 14, animatedCursor))
                out.write(xfixesGetCursorImageRequest())
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(2, u32le(image, 4))
                assertEquals(2, u16le(image, 12))
                assertEquals(1, u16le(image, 14))
                assertEquals(1, u16le(image, 16))
                assertEquals(0, u16le(image, 18))
                assertEquals(2, u32le(image, 20))
                assertEquals(0xffff_0000.toInt(), u32le(image, 32))
                assertEquals(0xff00_ff00.toInt(), u32le(image, 36))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `OpenFont rejects duplicate resource id without replacing existing resource`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(openFontRequest(WindowId))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(45, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `OpenFont and CloseFont validate lengths and font resources`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val font = PixmapId + 700
                val missingFont = font + 1
                val out = socket.getOutputStream()
                out.write(malformedOpenFontRequest(font, declaredNameLength = 5, nameBytes = byteArrayOf()))
                out.write(openFontRequest(font))
                out.write(openFontRequest(font))
                out.write(request(46, 0, ByteArray(0)))
                out.write(closeFontRequest(missingFont))
                out.write(closeFontRequest(font))
                out.write(queryFontRequest(font))
                out.write(openFontRequest(font))
                out.write(queryFontRequest(font))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 45, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 14, opcode = 45, badValue = font, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 46, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 7, opcode = 46, badValue = missingFont, sequence = 5)
                assertError(socket.getInputStream(), error = 7, opcode = 47, badValue = font, sequence = 7)

                val reply = readReply(socket.getInputStream())
                assertEquals(8, u16le(reply, 10))
                assertEquals(12, u16le(reply, 52))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `typed free resource requests validate lengths and resource classes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val cursor = PixmapId + 900
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 8, height = 8, depth = 1))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(createColormapRequest(ColormapId, window = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = PixmapId, mask = PixmapId))
                out.write(request(54, 0, ByteArray(0)))
                out.write(request(60, 0, ByteArray(0)))
                out.write(request(79, 0, ByteArray(0)))
                out.write(request(95, 0, ByteArray(0)))
                out.write(freePixmapRequest(WindowId))
                out.write(freeGcRequest(WindowId))
                out.write(freeColormapRequest(WindowId))
                out.write(freeCursorRequest(WindowId))
                out.write(freePixmapRequest(PixmapId))
                out.write(freeGcRequest(GcId))
                out.write(freeColormapRequest(ColormapId))
                out.write(freeCursorRequest(cursor))
                out.write(getFontPathRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 54, badValue = 0, sequence = 6)
                assertError(socket.getInputStream(), error = 16, opcode = 60, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 79, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 95, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 4, opcode = 54, badValue = WindowId, sequence = 10)
                assertError(socket.getInputStream(), error = 13, opcode = 60, badValue = WindowId, sequence = 11)
                assertError(socket.getInputStream(), error = 12, opcode = 79, badValue = WindowId, sequence = 12)
                assertError(socket.getInputStream(), error = 6, opcode = 95, badValue = WindowId, sequence = 13)

                val reply = readReply(socket.getInputStream())
                assertEquals(1, reply[0].toInt())
                assertEquals(18, u16le(reply, 2))
                assertEquals(0, u16le(reply, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateColormap rejects duplicate resource id without replacing existing resource`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(createColormapRequest(WindowId, window = WindowId))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(78, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateColormap validates request fields and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingWindow = WindowId + 100
                val unsupportedVisual = X11Ids.RootVisual + 1
                val validColormap = ColormapId + 40
                val out = socket.getOutputStream()
                out.write(request(78, 0, ByteArray(8)))
                out.write(request(78, 0, ByteArray(16)))
                out.write(createColormapRequest(ColormapId, window = X11Ids.RootWindow, alloc = 2))
                out.write(createColormapRequest(X11Ids.DefaultColormap, window = X11Ids.RootWindow))
                out.write(createColormapRequest(ColormapId, window = missingWindow))
                out.write(createColormapRequest(ColormapId, window = X11Ids.RootWindow, visual = unsupportedVisual))
                out.write(createColormapRequest(ColormapId, window = X11Ids.RootWindow, alloc = 1))
                out.write(createColormapRequest(validColormap, window = X11Ids.RootWindow))
                out.write(installColormapRequest(validColormap))
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 78, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 78, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 78, badValue = 2, sequence = 3)
                assertError(socket.getInputStream(), error = 14, opcode = 78, badValue = X11Ids.DefaultColormap, sequence = 4)
                assertError(socket.getInputStream(), error = 3, opcode = 78, badValue = missingWindow, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = 78, badValue = unsupportedVisual, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 78, badValue = 1, sequence = 7)

                assertEquals(listOf(validColormap), installedColormaps(readReply(socket.getInputStream())))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyColormapAndFree creates an installable colormap from an existing source`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val copiedColormap = ColormapId + 10
                val out = socket.getOutputStream()
                out.write(copyColormapAndFreeRequest(copiedColormap, X11Ids.DefaultColormap))
                out.write(installColormapRequest(copiedColormap))
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.flush()

                assertEquals(listOf(copiedColormap), installedColormaps(readReply(socket.getInputStream())))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyColormapAndFree validates ids and length before creating resource`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val copiedColormap = ColormapId + 11
                val missingSource = ColormapId + 12
                val out = socket.getOutputStream()
                out.write(createColormapRequest(ColormapId, window = X11Ids.RootWindow))
                out.write(copyColormapAndFreeRequest(ColormapId, X11Ids.DefaultColormap))
                out.write(copyColormapAndFreeRequest(copiedColormap, missingSource))
                out.write(request(80, 0, ByteArray(4)))
                out.write(copyColormapAndFreeRequest(copiedColormap, ColormapId))
                out.write(installColormapRequest(copiedColormap))
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(ColormapId, u32le(duplicateError, 4))
                assertEquals(80, duplicateError[10].toInt() and 0xff)

                val sourceError = socket.getInputStream().readExactly(32)
                assertEquals(0, sourceError[0].toInt())
                assertEquals(12, sourceError[1].toInt() and 0xff)
                assertEquals(missingSource, u32le(sourceError, 4))
                assertEquals(80, sourceError[10].toInt() and 0xff)

                val lengthError = socket.getInputStream().readExactly(32)
                assertEquals(0, lengthError[0].toInt())
                assertEquals(16, lengthError[1].toInt() and 0xff)
                assertEquals(0, u32le(lengthError, 4))
                assertEquals(80, lengthError[10].toInt() and 0xff)

                assertEquals(listOf(copiedColormap), installedColormaps(readReply(socket.getInputStream())))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `InstallColormap and UninstallColormap update installed colormap list`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.write(createColormapRequest(ColormapId, window = X11Ids.RootWindow))
                out.write(installColormapRequest(ColormapId))
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.write(uninstallColormapRequest(ColormapId))
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.write(createColormapRequest(ColormapId + 2, window = X11Ids.RootWindow))
                out.write(installColormapRequest(ColormapId + 2))
                out.write(freeColormapRequest(ColormapId + 2))
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.flush()

                assertEquals(listOf(X11Ids.DefaultColormap), installedColormaps(readReply(socket.getInputStream())))
                assertEquals(listOf(ColormapId), installedColormaps(readReply(socket.getInputStream())))
                assertEquals(listOf(X11Ids.DefaultColormap), installedColormaps(readReply(socket.getInputStream())))
                assertEquals(listOf(X11Ids.DefaultColormap), installedColormaps(readReply(socket.getInputStream())))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `InstallColormap and ListInstalledColormaps validate resource ids without mutating installed state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingColormap = ColormapId + 1
                val missingWindow = WindowId + 100
                val out = socket.getOutputStream()
                out.write(createColormapRequest(ColormapId, window = X11Ids.RootWindow))
                out.write(installColormapRequest(ColormapId))
                out.write(installColormapRequest(missingColormap))
                out.write(uninstallColormapRequest(missingColormap))
                out.write(listInstalledColormapsRequest(missingWindow))
                out.write(listInstalledColormapsRequest(X11Ids.RootWindow))
                out.flush()

                val installError = socket.getInputStream().readExactly(32)
                assertEquals(0, installError[0].toInt())
                assertEquals(12, installError[1].toInt() and 0xff)
                assertEquals(missingColormap, u32le(installError, 4))
                assertEquals(81, installError[10].toInt() and 0xff)

                val uninstallError = socket.getInputStream().readExactly(32)
                assertEquals(0, uninstallError[0].toInt())
                assertEquals(12, uninstallError[1].toInt() and 0xff)
                assertEquals(missingColormap, u32le(uninstallError, 4))
                assertEquals(82, uninstallError[10].toInt() and 0xff)

                val windowError = socket.getInputStream().readExactly(32)
                assertEquals(0, windowError[0].toInt())
                assertEquals(3, windowError[1].toInt() and 0xff)
                assertEquals(missingWindow, u32le(windowError, 4))
                assertEquals(83, windowError[10].toInt() and 0xff)

                assertEquals(listOf(ColormapId), installedColormaps(readReply(socket.getInputStream())))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `AllocColor validates colormap and returns visual color with pixel`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingColormap = ColormapId + 24
                val out = socket.getOutputStream()
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0x1234, green = 0x5678, blue = 0x9abc))
                out.write(allocColorRequest(missingColormap, red = 0xffff, green = 0, blue = 0))
                out.write(request(84, 0, ByteArray(8)))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0xffff, green = 0, blue = 0))
                out.flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(0, u32le(reply, 4))
                assertEquals(0x1212, u16le(reply, 8))
                assertEquals(0x5656, u16le(reply, 10))
                assertEquals(0x9a9a, u16le(reply, 12))
                assertEquals(0x0012_569a, u32le(reply, 16))
                assertEquals(0, u32le(reply, 20))

                val colormapError = socket.getInputStream().readExactly(32)
                assertEquals(0, colormapError[0].toInt())
                assertEquals(12, colormapError[1].toInt() and 0xff)
                assertEquals(missingColormap, u32le(colormapError, 4))
                assertEquals(84, colormapError[10].toInt() and 0xff)

                val lengthError = socket.getInputStream().readExactly(32)
                assertEquals(0, lengthError[0].toInt())
                assertEquals(16, lengthError[1].toInt() and 0xff)
                assertEquals(84, lengthError[10].toInt() and 0xff)

                val red = readReply(socket.getInputStream())
                assertEquals(Red, u32le(red, 16))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `AllocColorCells and AllocColorPlanes validate requests before TrueColor allocation failure`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingColormap = ColormapId + 25
                val out = socket.getOutputStream()
                out.write(allocColorCellsRequest(X11Ids.DefaultColormap, colors = 1, planes = 1))
                out.write(allocColorCellsRequest(missingColormap, colors = 1, planes = 0))
                out.write(request(86, 2, ByteArray(8)))
                out.write(request(86, 0, ByteArray(4)))
                out.write(allocColorCellsRequest(X11Ids.DefaultColormap, colors = 0, planes = 0))
                out.write(allocColorPlanesRequest(X11Ids.DefaultColormap, colors = 1, reds = 1, greens = 1, blues = 1))
                out.write(allocColorPlanesRequest(missingColormap, colors = 1, reds = 1, greens = 0, blues = 0))
                out.write(request(87, 3, ByteArray(12)))
                out.write(request(87, 0, ByteArray(8)))
                out.write(allocColorPlanesRequest(X11Ids.DefaultColormap, colors = 0, reds = 0, greens = 0, blues = 0))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0, green = 0xffff, blue = 0))
                out.flush()

                assertError(socket.getInputStream(), error = 11, opcode = 86, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 12, opcode = 86, badValue = missingColormap, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 86, badValue = 2, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 86, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 86, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 11, opcode = 87, badValue = 0, sequence = 6)
                assertError(socket.getInputStream(), error = 12, opcode = 87, badValue = missingColormap, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 87, badValue = 3, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 87, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 2, opcode = 87, badValue = 0, sequence = 10)

                val green = readReply(socket.getInputStream())
                assertEquals(0x0000_ff00, u32le(green, 16))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FreeColors validates colormap and length while preserving stateless colors`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingColormap = ColormapId + 26
                val out = socket.getOutputStream()
                out.write(freeColorsRequest(X11Ids.DefaultColormap, planeMask = 0, pixels = listOf(Red, 0x0000_ff00)))
                out.write(freeColorsRequest(missingColormap, planeMask = 0, pixels = listOf(Red)))
                out.write(request(88, 0, ByteArray(4)))
                out.write(freeColorsRequest(X11Ids.DefaultColormap, planeMask = 0, pixels = listOf(0x0100_0000)))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0, green = 0, blue = 0xffff))
                out.flush()

                assertError(socket.getInputStream(), error = 12, opcode = 88, badValue = missingColormap, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 88, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 88, badValue = 0x0100_0000, sequence = 4)

                val blue = readReply(socket.getInputStream())
                assertEquals(Blue, u32le(blue, 16))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `StoreColors and StoreNamedColor reject TrueColor mutation after validation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingColormap = ColormapId + 27
                val out = socket.getOutputStream()
                out.write(storeColorsRequest(X11Ids.DefaultColormap, listOf(XStoreColorItem(Red, 0xffff, 0, 0, flags = 0x07))))
                out.write(storeColorsRequest(missingColormap, listOf(XStoreColorItem(Red, 0xffff, 0, 0, flags = 0x07))))
                out.write(storeColorsRequest(X11Ids.DefaultColormap, listOf(XStoreColorItem(0x0100_0000, 0xffff, 0, 0, flags = 0x07))))
                out.write(storeColorsRequest(X11Ids.DefaultColormap, listOf(XStoreColorItem(Red, 0xffff, 0, 0, flags = 0x08))))
                out.write(request(89, 0, ByteArray(8)))
                out.write(storeNamedColorRequest(X11Ids.DefaultColormap, Red, "red", flags = 0x07))
                out.write(storeNamedColorRequest(missingColormap, Red, "red", flags = 0x07))
                out.write(storeNamedColorRequest(X11Ids.DefaultColormap, 0x0100_0000, "red", flags = 0x07))
                out.write(storeNamedColorRequest(X11Ids.DefaultColormap, Red, "definitely-not-a-color", flags = 0x07))
                out.write(storeNamedColorRequest(X11Ids.DefaultColormap, Red, "red", flags = 0x08))
                out.write(request(90, 0x07, ByteArray(8)))
                out.write(request(90, 0x08, ByteArray(8)))
                out.write(malformedStoreNamedColorRequest(X11Ids.DefaultColormap, Red, declaredNameLength = 5, actualName = "red"))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0xffff, green = 0, blue = 0))
                out.flush()

                assertError(socket.getInputStream(), error = 10, opcode = 89, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 12, opcode = 89, badValue = missingColormap, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 89, badValue = 0x0100_0000, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 89, badValue = 0x08, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 89, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 10, opcode = 90, badValue = 0, sequence = 6)
                assertError(socket.getInputStream(), error = 12, opcode = 90, badValue = missingColormap, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 90, badValue = 0x0100_0000, sequence = 8)
                assertError(socket.getInputStream(), error = 15, opcode = 90, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 2, opcode = 90, badValue = 0x08, sequence = 10)
                assertError(socket.getInputStream(), error = 16, opcode = 90, badValue = 0, sequence = 11)
                assertError(socket.getInputStream(), error = 16, opcode = 90, badValue = 0, sequence = 12)
                assertError(socket.getInputStream(), error = 16, opcode = 90, badValue = 0, sequence = 13)

                val red = readReply(socket.getInputStream())
                assertEquals(Red, u32le(red, 16))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryColors validates colormap pixels and length before returning color triples`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingColormap = ColormapId + 28
                val out = socket.getOutputStream()
                out.write(queryColorsRequest(X11Ids.DefaultColormap, listOf(Red, 0x0012_569a)))
                out.write(queryColorsRequest(missingColormap, listOf(Red)))
                out.write(queryColorsRequest(X11Ids.DefaultColormap, listOf(0x0100_0000)))
                out.write(request(91, 0, ByteArray(0)))
                out.write(queryColorsRequest(X11Ids.DefaultColormap, emptyList()))
                out.flush()

                val colors = readReply(socket.getInputStream())
                assertEquals(1, colors[0].toInt())
                assertEquals(1, u16le(colors, 2))
                assertEquals(4, u32le(colors, 4))
                assertEquals(2, u16le(colors, 8))
                assertQueriedColor(colors, index = 0, red = 0xffff, green = 0, blue = 0)
                assertQueriedColor(colors, index = 1, red = 0x1212, green = 0x5656, blue = 0x9a9a)

                assertError(socket.getInputStream(), error = 12, opcode = 91, badValue = missingColormap, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 91, badValue = 0x0100_0000, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 91, badValue = 0, sequence = 4)

                val empty = readReply(socket.getInputStream())
                assertEquals(1, empty[0].toInt())
                assertEquals(5, u16le(empty, 2))
                assertEquals(0, u32le(empty, 4))
                assertEquals(0, u16le(empty, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `AllocNamedColor returns pixel and exact visual color triples`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                socket.getOutputStream().write(allocNamedColorRequest(X11Ids.DefaultColormap, "Red"))
                socket.getOutputStream().flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(0, u32le(reply, 4))
                assertEquals(Red, u32le(reply, 8))
                assertColorTriples(reply, offset = 12, red = 0xffff, green = 0, blue = 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LookupColor uses opcode 92 and resolves normalized names and hex colors`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "Light Gray"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "#00ff80"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "#123456789abc"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "rgb:1/23/456"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "RGBi:1.0/0.5/0.0"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "CIEXYZ:0.95047/1.0/1.08883"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "gray50"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "linen"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "snow1"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "blue4"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "DarkSeaGreen4"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "web gray"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "x11 gray"))
                out.flush()

                val gray = readReply(socket.getInputStream())
                assertEquals(0, u32le(gray, 4))
                assertColorTriples(gray, offset = 8, red = 0xd3d3, green = 0xd3d3, blue = 0xd3d3)

                val hex = readReply(socket.getInputStream())
                assertColorTriples(
                    hex,
                    offset = 8,
                    red = 0,
                    green = 0xff00,
                    blue = 0x8000,
                    visualRed = 0,
                    visualGreen = 0xffff,
                    visualBlue = 0x8080,
                )

                val highPrecisionHex = readReply(socket.getInputStream())
                assertColorTriples(
                    highPrecisionHex,
                    offset = 8,
                    red = 0x1234,
                    green = 0x5678,
                    blue = 0x9abc,
                    visualRed = 0x1212,
                    visualGreen = 0x5656,
                    visualBlue = 0x9a9a,
                )

                val rgb = readReply(socket.getInputStream())
                assertColorTriples(
                    rgb,
                    offset = 8,
                    red = 0x1111,
                    green = 0x2323,
                    blue = 0x4564,
                    visualRed = 0x1111,
                    visualGreen = 0x2323,
                    visualBlue = 0x4545,
                )

                val rgbi = readReply(socket.getInputStream())
                assertColorTriples(
                    rgbi,
                    offset = 8,
                    red = 0xffff,
                    green = 0x8000,
                    blue = 0,
                    visualRed = 0xffff,
                    visualGreen = 0x8080,
                    visualBlue = 0,
                )

                val cieXyz = readReply(socket.getInputStream())
                assertColorTriples(
                    cieXyz,
                    offset = 8,
                    red = 0xffff,
                    green = 0xffff,
                    blue = 0xfffa,
                    visualRed = 0xffff,
                    visualGreen = 0xffff,
                    visualBlue = 0xffff,
                )

                val gray50 = readReply(socket.getInputStream())
                assertColorTriples(gray50, offset = 8, red = 0x7f7f, green = 0x7f7f, blue = 0x7f7f)

                val linen = readReply(socket.getInputStream())
                assertColorTriples(linen, offset = 8, red = 0xfafa, green = 0xf0f0, blue = 0xe6e6)

                val snow1 = readReply(socket.getInputStream())
                assertColorTriples(snow1, offset = 8, red = 0xffff, green = 0xfafa, blue = 0xfafa)

                val blue4 = readReply(socket.getInputStream())
                assertColorTriples(blue4, offset = 8, red = 0, green = 0, blue = 0x8b8b)

                val darkSeaGreen4 = readReply(socket.getInputStream())
                assertColorTriples(darkSeaGreen4, offset = 8, red = 0x6969, green = 0x8b8b, blue = 0x6969)

                val webGray = readReply(socket.getInputStream())
                assertColorTriples(webGray, offset = 8, red = 0x8080, green = 0x8080, blue = 0x8080)

                val x11Gray = readReply(socket.getInputStream())
                assertColorTriples(x11Gray, offset = 8, red = 0xbebe, green = 0xbebe, blue = 0xbebe)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `named color requests validate colormap name and length`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingColormap = ColormapId + 20
                val malformed = ByteArray(8).also {
                    put32le(it, 0, X11Ids.DefaultColormap)
                    put16le(it, 4, 1)
                }
                val out = socket.getOutputStream()
                out.write(allocNamedColorRequest(missingColormap, "red"))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "definitely-not-a-color"))
                out.write(request(85, 0, malformed))
                out.write(lookupColorRequest(X11Ids.DefaultColormap, "blue"))
                out.flush()

                val colormapError = socket.getInputStream().readExactly(32)
                assertEquals(0, colormapError[0].toInt())
                assertEquals(12, colormapError[1].toInt() and 0xff)
                assertEquals(missingColormap, u32le(colormapError, 4))
                assertEquals(85, colormapError[10].toInt() and 0xff)

                val nameError = socket.getInputStream().readExactly(32)
                assertEquals(0, nameError[0].toInt())
                assertEquals(15, nameError[1].toInt() and 0xff)
                assertEquals(92, nameError[10].toInt() and 0xff)

                val lengthError = socket.getInputStream().readExactly(32)
                assertEquals(0, lengthError[0].toInt())
                assertEquals(16, lengthError[1].toInt() and 0xff)
                assertEquals(85, lengthError[10].toInt() and 0xff)

                val reply = readReply(socket.getInputStream())
                assertColorTriples(reply, offset = 8, red = 0, green = 0, blue = 0xffff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `invalid CreateGC function reports Value error before usable GC creation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRasterRequest(GcId, function = 16))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(16, u32le(error, 4))
                assertEquals(55, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC validates drawable and duplicate id before mutating GC state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val missingDrawable = 0x0020_9999
                out.write(createGcRequest(GcId, foreground = Red, drawable = missingDrawable))
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val drawableError = socket.getInputStream().readExactly(32)
                assertEquals(0, drawableError[0].toInt())
                assertEquals(9, drawableError[1].toInt() and 0xff)
                assertEquals(missingDrawable, u32le(drawableError, 4))
                assertEquals(55, drawableError[10].toInt() and 0xff)

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(GcId, u32le(duplicateError, 4))
                assertEquals(55, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC and ChangeGC validate value list length and font resources`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingFont = PixmapId + 1_100
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(request(55, 0, ByteArray(8)))
                out.write(createGcRawRequest(GcId, mask = 0x0000_0004))
                out.write(createGcRawRequest(GcId, mask = 0, values = listOf(Red)))
                out.write(createGcRawRequest(GcId, mask = 0x0000_4000, values = listOf(missingFont)))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(changeGcRawRequest(GcId, mask = 0x0000_0004))
                out.write(changeGcRawRequest(GcId, mask = 0, values = listOf(Red)))
                out.write(changeGcRawRequest(GcId, mask = 0x0000_4000, values = listOf(missingFont)))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 55, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 55, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 55, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 7, opcode = 55, badValue = missingFont, sequence = 5)
                assertError(socket.getInputStream(), error = 16, opcode = 56, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 56, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 7, opcode = 56, badValue = missingFont, sequence = 9)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC and ChangeGC reject invalid graphics exposures bool values`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRawRequest(GcId, mask = 0x0001_0000, values = listOf(2)))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(changeGcRawRequest(GcId, mask = 0x0001_0000, values = listOf(2)))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 55, badValue = 2, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 56, badValue = 2, sequence = 4)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC and ChangeGC reject invalid cap and join styles`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRawRequest(GcId, mask = 0x0000_0040, values = listOf(4)))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(changeGcRawRequest(GcId, mask = 0x0000_0080, values = listOf(3)))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 55, badValue = 4, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 56, badValue = 3, sequence = 4)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC and ChangeGC reject invalid subwindow mode values`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRawRequest(GcId, mask = 0x0000_8000, values = listOf(2)))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(changeGcRawRequest(GcId, mask = 0x0000_8000, values = listOf(2)))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 55, badValue = 2, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 56, badValue = 2, sequence = 4)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC graphics exposures bool ignores value list padding bytes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1))
                out.write(createGcRawRequest(GcId, mask = 0x0001_0004, values = listOf(Red, 0xffff_ff00.toInt())))
                out.write(putImage24PixelsRequest(PixmapId, width = 1, height = 1, pixels = listOf(Green)))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC and ChangeGC reject undefined value mask bits`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val undefinedMask = 0x0080_0000
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRawRequest(GcId, mask = undefinedMask))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(changeGcRawRequest(GcId, mask = undefinedMask))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val createError = socket.getInputStream().readExactly(32)
                assertEquals(0, createError[0].toInt())
                assertEquals(2, createError[1].toInt() and 0xff)
                assertEquals(undefinedMask, u32le(createError, 4))
                assertEquals(55, createError[10].toInt() and 0xff)

                val changeError = socket.getInputStream().readExactly(32)
                assertEquals(0, changeError[0].toInt())
                assertEquals(2, changeError[1].toInt() and 0xff)
                assertEquals(undefinedMask, u32le(changeError, 4))
                assertEquals(56, changeError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ClearArea validates exposures flag and emits selected exposure event`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 12, height = 10, eventMask = XEventMasks.Exposure))
                out.write(mapWindowRequest(WindowId))
                out.write(clearAreaRequest(WindowId, x = -1, y = -1, width = 3, height = 3, exposures = 1))
                out.write(clearAreaRequest(WindowId, x = 20, y = 20, width = 3, height = 3, exposures = 1))
                out.write(clearAreaRequest(WindowId, x = 0, y = 0, width = 1, height = 1, exposures = 2))
                out.write(getInputFocusRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                assertExpose(
                    socket.getInputStream().readExactly(32),
                    windowId = WindowId,
                    sequence = 3,
                    x = 0,
                    y = 0,
                    width = 2,
                    height = 2,
                    count = 0,
                )
                assertError(socket.getInputStream(), error = 2, opcode = 61, badValue = 2, sequence = 5)

                val focus = readReply(socket.getInputStream())
                assertEquals(6, u16le(focus, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ClearArea exposure flag does not deliver to unselected clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 12, height = 10))
                out.write(mapWindowRequest(WindowId))
                out.write(clearAreaRequest(WindowId, x = 1, y = 1, width = 3, height = 3, exposures = 1))
                out.write(getInputFocusRequest())
                out.flush()

                assertExpose(socket.getInputStream().readExactly(32), WindowId, sequence = 2)
                val focus = readReply(socket.getInputStream())
                assertEquals(4, u16le(focus, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ClearArea with ParentRelative background uses current parent background pixel`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent, width = 6, height = 5))
                out.write(createWindowRequest(child, parent = parent, width = 3, height = 2, backgroundPixmap = XWindowBackground.ParentRelative))
                out.write(createGcRequest(GcId, foreground = Red, drawable = child))
                out.write(putImage24Request(child, width = 3, height = 2, pixel = 0x0012_3456))
                out.write(changeWindowAttributesRawRequest(parent, 1 shl 1, Green))
                out.write(clearAreaRequest(child, x = 0, y = 0, width = 0, height = 0))
                out.write(getImageRequest(child, x = 0, y = 0, width = 3, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 3, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 3, 2, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ClearArea with ParentRelative background aligns to parent pixmap tile origin`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent, width = 6, height = 5))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2, drawable = parent))
                out.write(createGcRequest(GcId, foreground = Red, drawable = parent))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(Red, Green, Blue, 0x0000_0000),
                    ),
                )
                out.write(changeWindowBackgroundPixmapRequest(parent, PixmapId))
                out.write(createWindowRequest(child, parent = parent, x = 1, y = 0, width = 2, height = 2, backgroundPixmap = XWindowBackground.ParentRelative))
                out.write(clearAreaRequest(child, x = 0, y = 0, width = 0, height = 0))
                out.write(getImageRequest(child, x = 0, y = 0, width = 2, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 2, 1, 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(image, 2, 0, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 2, 1, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ClearArea with background None leaves framebuffer contents unchanged`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent, width = 3, height = 2, backgroundPixmap = XWindowBackground.None))
                out.write(createWindowRequest(child, parent = parent, width = 3, height = 2, backgroundPixmap = XWindowBackground.ParentRelative))
                out.write(createGcRequest(GcId, foreground = Red, drawable = parent))
                out.write(createGcRequest(GcId + 1, foreground = Red, drawable = child))
                out.write(putImage24Request(parent, width = 3, height = 2, pixel = 0x0012_3456))
                out.write(putImage24Request(child, width = 3, height = 2, pixel = 0x0006_0708, gc = GcId + 1))
                out.write(clearAreaRequest(parent, x = 0, y = 0, width = 0, height = 0))
                out.write(getImageRequest(parent, x = 0, y = 0, width = 3, height = 2))
                out.write(clearAreaRequest(child, x = 0, y = 0, width = 0, height = 0))
                out.write(getImageRequest(child, x = 0, y = 0, width = 3, height = 2))
                out.flush()

                val parentImage = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(parentImage, 3, 0, 0))
                assertEquals(0xff12_3456.toInt(), pixelAt(parentImage, 3, 2, 1))
                val childImage = readReply(socket.getInputStream())
                assertEquals(0xff06_0708.toInt(), pixelAt(childImage, 3, 0, 0))
                assertEquals(0xff06_0708.toInt(), pixelAt(childImage, 3, 2, 1))

                val json = httpGet(server.localPort, "/state.json")
                val parentJson = Regex("""\{"id":"0x${parent.toUInt().toString(16)}".*?\}""").find(json)?.value.orEmpty()
                val childJson = Regex("""\{"id":"0x${child.toUInt().toString(16)}".*?\}""").find(json)?.value.orEmpty()
                assertContains(parentJson, """"backgroundPixmap":"0x0"""")
                assertContains(childJson, """"backgroundPixmap":"0x1"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyLine PolySegment and PolyRectangle paint framebuffer pixels`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Green))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(1 to 1, 5 to 1, 5 to 4)))
                out.write(polySegmentRequest(WindowId, GcId, segments = listOf((7 to 1) to (10 to 1))))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(1, 6, 5, 4), XRectangleCommand(8, 6, 0, 3))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 11))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 3, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 5, 3))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 8, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 1, 6))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 6, 10))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 3, 7))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 8, 8))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 9, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyFillRectangle honors GC tiled fill style and tile origin`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 1, yOrigin = 1))
                out.write(freePixmapRequest(PixmapId))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(2, 1, 3, 3))))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(2, 1, 4, 3))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 7, height = 5))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 1, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 7, 2, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 3, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 7, 4, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 5, 1))
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 7, 2, 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 7, 3, 2))
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 7, 4, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 7, 2, 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 3, 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeGC rejects missing tiled fill pixmap without changing fill style`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val missingPixmap = 0x0020_9999
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = missingPixmap, xOrigin = 0, yOrigin = 0))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(1, 1, 2, 2))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(4, error[1].toInt() and 0xff)
                assertEquals(missingPixmap, u32le(error, 4))
                assertEquals(56, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 1, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateGC and ChangeGC reject tile and stipple depth mismatches without changing fill style`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val depthOnePixmap = PixmapId
                val depthTwentyFourPixmap = PixmapId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(depthOnePixmap, width = 2, height = 2, depth = 1))
                out.write(createPixmapRequest(depthTwentyFourPixmap, width = 2, height = 2, depth = 24))
                out.write(createGcRawRequest(GcId, mask = 0x0000_0400, values = listOf(depthOnePixmap)))
                out.write(createGcRawRequest(GcId, mask = 0x0000_0800, values = listOf(depthTwentyFourPixmap)))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = depthOnePixmap, xOrigin = 0, yOrigin = 0))
                out.write(changeGcStippledFillRequest(GcId, fillStyle = FillStippled, stipplePixmap = depthTwentyFourPixmap, xOrigin = 0, yOrigin = 0))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(1, 1, 2, 2))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                assertError(socket.getInputStream(), error = 8, opcode = 55, badValue = depthOnePixmap, sequence = 4)
                assertError(socket.getInputStream(), error = 8, opcode = 55, badValue = depthTwentyFourPixmap, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = 56, badValue = depthOnePixmap, sequence = 7)
                assertError(socket.getInputStream(), error = 8, opcode = 56, badValue = depthTwentyFourPixmap, sequence = 8)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 2, 2))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FillPoly and PolyFillArc honor GC tiled fill style`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 0, points = listOf(1 to 1, 5 to 1, 5 to 4, 1 to 4)))
                out.write(polyArcRequest(WindowId, GcId, filled = true, arcs = listOf(XArcCommand(8, 1, 5, 5, 0, 360 * 64))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 14, height = 8))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 14, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 14, 2, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 14, 1, 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 14, 2, 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 14, 10, 3))
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 14, 11, 3))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 14, 13, 3))

                val stateJson = httpGet(server.localPort, "/state.json")
                val fillPolyJson = Regex("""\{"drawable":"0x${WindowId.toUInt().toString(16)}","kind":"FillPoly".*?\}""")
                    .find(stateJson)?.value.orEmpty()
                val fillArcJson = Regex("""\{"drawable":"0x${WindowId.toUInt().toString(16)}","kind":"FillArc".*?\}""")
                    .find(stateJson)?.value.orEmpty()
                assertContains(fillPolyJson, """"fillStyle":1,"fillRule":0,"tilePixmap":"0x${PixmapId.toUInt().toString(16)}"""")
                assertContains(fillPolyJson, """"tileStippleOrigin":[0,0],"arcMode":1""")
                assertContains(stateJson, """"points":[{"x":1,"y":1},{"x":5,"y":1},{"x":5,"y":4},{"x":1,"y":4}]""")
                assertContains(fillArcJson, """"fillStyle":1,"fillRule":0,"tilePixmap":"0x${PixmapId.toUInt().toString(16)}"""")
                assertContains(fillArcJson, """"tileStippleOrigin":[0,0],"arcMode":1""")
                assertContains(stateJson, """"arcs":[{"x":8,"y":1,"width":5,"height":5,"angle1":0,"angle2":23040}]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyFillRectangle honors stippled and opaque stippled fill styles`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2, depth = 1))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(createGcRequest(GcId + 1, foreground = 1, background = 0, drawable = PixmapId))
                out.write(putImageBitmapRequest(PixmapId, gc = GcId + 1, width = 2, height = 2, bits = listOf(true, false, false, true)))
                out.write(changeGcStippledFillRequest(GcId, fillStyle = FillStippled, stipplePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(1, 1, 2, 2))))
                out.write(changeGcStippledFillRequest(GcId, fillStyle = FillOpaqueStippled, stipplePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(4, 1, 2, 2))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 7, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 1, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 2, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 1, 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 2, 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 7, 4, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 5, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 4, 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 7, 5, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `drawing command state retains non-default GC fill context for core primitives`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val stipplePixmap = PixmapId + 1
                val bitmapGc = GcId + 1
                val solidGc = GcId + 2
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createPixmapRequest(stipplePixmap, width = 2, height = 2, depth = 1))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(createGcRequest(bitmapGc, foreground = 1, background = 0, drawable = stipplePixmap))
                out.write(createGcRequest(solidGc, foreground = Green))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(Red, Green, Blue, 0x0012_3456),
                    ),
                )
                out.write(putImageBitmapRequest(stipplePixmap, gc = bitmapGc, width = 2, height = 2, bits = listOf(true, false, false, true)))
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 1, yOrigin = 2))
                out.write(changeGcFillRuleRequest(GcId, fillRule = WindingRule))
                out.write(changeGcArcModeRequest(GcId, arcMode = ArcChord))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(1 to 1, 5 to 1)))
                out.write(polySegmentRequest(WindowId, GcId, segments = listOf((1 to 3) to (5 to 3))))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(1, 5, 4, 3))))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = listOf(XArcCommand(8, 1, 5, 5, 0, 180 * 64))))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 0, points = listOf(10 to 1, 14 to 1, 14 to 4, 10 to 4)))
                out.write(polyArcRequest(WindowId, GcId, filled = true, arcs = listOf(XArcCommand(16, 1, 5, 5, 0, FullCircleAngle))))
                out.write(changeGcStippledFillRequest(GcId, fillStyle = FillOpaqueStippled, stipplePixmap = stipplePixmap, xOrigin = 3, yOrigin = 4))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(22, 1, 2, 2))))
                out.write(polyFillRectangleRequest(WindowId, solidGc, rectangles = listOf(XRectangleCommand(26, 1, 2, 2))))
                out.flush()

                waitForStateContains(server.localPort, """"drawings":10""")
                val stateJson = httpGet(server.localPort, "/state.json")
                val tiledContext =
                    """"fillStyle":1,"fillRule":1,"tilePixmap":"0x${PixmapId.toUInt().toString(16)}","stipplePixmap":null,"tileStippleOrigin":[1,2],"arcMode":0"""
                val stippledContext =
                    """"fillStyle":3,"fillRule":1,"tilePixmap":"0x${PixmapId.toUInt().toString(16)}","stipplePixmap":"0x${stipplePixmap.toUInt().toString(16)}","tileStippleOrigin":[3,4],"arcMode":0"""
                val solidContext =
                    """"fillStyle":0,"fillRule":0,"tilePixmap":null,"stipplePixmap":null,"tileStippleOrigin":[0,0],"arcMode":1"""
                assertEquals(6, Regex(Regex.escape(tiledContext)).findAll(stateJson).count())
                assertContains(stateJson, stippledContext)
                assertContains(stateJson, solidContext)
                assertContains(stateJson, """"kind":"Segment"""")
                assertContains(stateJson, """"kind":"Rectangle"""")
                assertContains(stateJson, """"kind":"Arc"""")
                assertContains(stateJson, """"points":[{"x":1,"y":3},{"x":5,"y":3}]""")
                assertContains(stateJson, """"rectangles":[{"x":1,"y":5,"width":4,"height":3}]""")
                assertContains(stateJson, """"arcs":[{"x":8,"y":1,"width":5,"height":5,"angle1":0,"angle2":11520}]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyGC preserves tiled fill snapshot`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(freePixmapRequest(PixmapId))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0000_3500))
                out.write(polyFillRectangleRequest(WindowId, GcId + 1, rectangles = listOf(XRectangleCommand(1, 1, 2, 2))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 4, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 2, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 4, 1, 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetDashes and LineOnOffDash paint dashed PolyLine into framebuffer`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2, 2)))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 0, 7 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 4, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 5, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 6, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 7, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LineOnOffDash leaves prior pixels visible when PolyRectangle repaints same outline`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val rectangle = XRectangleCommand(2, 2, 8, 4)
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(changeGcLineStyleRequest(GcId + 1, lineStyle = 1))
                out.write(setDashesRequest(GcId + 1, dashOffset = 0, dashes = listOf(1, 1)))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(rectangle)))
                out.write(polyRectangleRequest(WindowId, GcId + 1, rectangles = listOf(rectangle)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 14, height = 10))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 14, 10, 0xffff_0000.toInt()) > 0)
                assertTrue(countPixels(image, 14, 10, 0xff00_00ff.toInt()) > 0)
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 14, 2, 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 14, 3, 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 14, 4, 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 14, 5, 2))

                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"kind":"Rectangle"""")
                assertContains(stateJson, """"background":"0xffffff"""")
                assertContains(stateJson, """"lineStyle":1""")
                assertContains(stateJson, """"dashes":[1,1]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LineDoubleDash paints GC background into off dashes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 2))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2, 2)))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 0, 7 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 1, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 2, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 4, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 8, 5, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 6, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 7, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LineDoubleDash paints PolyRectangle off dashes with GC background`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createGcRequest(GcId, foreground = Blue, background = Green))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 2))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(1, 1)))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(2, 2, 8, 4))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 14, height = 10))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 14, 10, 0xff00_00ff.toInt()) > 0)
                assertTrue(countPixels(image, 14, 10, 0xff00_ff00.toInt()) > 0)
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 14, 2, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 14, 3, 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 14, 4, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 14, 5, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LineOnOffDash PolyRectangle uses tiled fill source for on dashes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = 0x00aa_00aa))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2, 1)))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(1, 1, 4, 3))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 7))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 8, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 2, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 3, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LineOnOffDash PolyLine uses tiled fill source for on dashes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = 0x00aa_00aa))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2, 1)))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(1 to 1, 5 to 1)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 8, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 2, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 3, 1))
                assertEquals(0, countPixels(image, 8, 4, 0xffaa_00aa.toInt()))

                val stateJson = httpGet(server.localPort, "/state.json")
                val lineJson = Regex("""\{"drawable":"0x${WindowId.toUInt().toString(16)}","kind":"Line".*?\}""")
                    .find(stateJson)?.value.orEmpty()
                assertContains(lineJson, """"lineStyle":1""")
                assertContains(lineJson, """"fillStyle":1,"fillRule":0,"tilePixmap":"0x${PixmapId.toUInt().toString(16)}"""")
                assertContains(lineJson, """"tileStippleOrigin":[0,0],"arcMode":1""")
                assertContains(stateJson, """"points":[{"x":1,"y":1},{"x":5,"y":1}]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LineOnOffDash PolySegment uses tiled fill source for on dashes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = 0x00aa_00aa))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2, 1)))
                out.write(polySegmentRequest(WindowId, GcId, segments = listOf((1 to 1) to (5 to 1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 8, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 2, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 3, 1))
                assertEquals(0, countPixels(image, 8, 4, 0xffaa_00aa.toInt()))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeGC dash offset and single dash value affect PolyLine phase`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(changeGcDashAttributesRequest(GcId, dashOffset = 1, dash = 2))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 0, 3 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 3, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `odd SetDashes list repeats to an even pattern`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(1, 2, 3)))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 0, 6 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 7, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 0, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 2, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 4, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 7, 5, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 6, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolySegment resets dash phase for each segment`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(1, 1)))
                out.write(polySegmentRequest(WindowId, GcId, segments = listOf((0 to 0) to (3 to 0), (0 to 1) to (3 to 1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 0, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 1, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyLine carries dash phase through joined segments`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2, 2)))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 0, 2 to 0, 4 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 4, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyGC copies dashed line attributes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(changeGcLineStyleRequest(GcId, lineStyle = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2, 2)))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0030_0020))
                out.write(polyLineRequest(WindowId, GcId + 1, points = listOf(0 to 0, 3 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyGC copies cap and join styles into semantic drawing state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRawRequest(GcId, mask = 0x0000_00c4, values = listOf(Red, 3, 2)))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0000_00c0))
                out.write(polyLineRequest(WindowId, GcId + 1, points = listOf(0 to 0, 3 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 3, 0))

                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"kind":"Line","framebufferBacked":true""")
                assertContains(stateJson, """"capStyle":3,"joinStyle":2""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetDashes reports errors for unknown GC and zero dash length`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(setDashesBadLengthRequest(bodySize = 4))
                out.write(setDashesBadLengthRequest(bodySize = 16, dashCount = 1))
                out.write(setDashesBadLengthRequest(bodySize = 8, dashCount = 1))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2)))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = emptyList()))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(0)))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 58, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 58, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 58, badValue = 0, sequence = 4)

                assertError(socket.getInputStream(), error = 13, opcode = 58, badValue = GcId, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 58, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 58, badValue = 0, sequence = 8)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetClipRectangles reports request errors and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(setClipRectanglesBadLengthRequest(bodySize = 4))
                out.write(setClipRectanglesBadLengthRequest(bodySize = 12))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = emptyList(), ordering = 4))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = emptyList()))
                out.write(freeGcRequest(GcId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(1, 0, 2, 1)), ordering = 3))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 0, 3 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 59, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 59, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 59, badValue = 4, sequence = 4)
                assertError(socket.getInputStream(), error = 13, opcode = 59, badValue = GcId, sequence = 5)
                assertError(socket.getInputStream(), error = 13, opcode = 60, badValue = GcId, sequence = 6)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyPoint and PolyLine report request errors and recover stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(polyPointBadLengthRequest())
                out.write(polyLineBadLengthRequest())
                out.write(polyPointRequest(WindowId, GcId, coordMode = 2, points = emptyList()))
                out.write(polyLineRequest(WindowId, GcId, coordMode = 2, points = emptyList()))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = emptyList()))
                out.write(polyLineRequest(WindowId, GcId, coordMode = 0, points = listOf(2 to 0, 3 to 0)))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 1, points = emptyList()))
                out.write(polyLineRequest(WindowId, GcId, coordMode = 1, points = emptyList()))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(1 to 0)))
                out.write(polyLineRequest(WindowId, GcId, coordMode = 0, points = listOf(2 to 0, 3 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 64, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 65, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 64, badValue = 2, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 65, badValue = 2, sequence = 5)
                assertError(socket.getInputStream(), error = 13, opcode = 64, badValue = GcId, sequence = 6)
                assertError(socket.getInputStream(), error = 13, opcode = 65, badValue = GcId, sequence = 7)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 3, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolySegment reports request errors and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(polySegmentBadLengthRequest(bodySize = 4))
                out.write(polySegmentBadLengthRequest(bodySize = 12))
                out.write(polySegmentRequest(WindowId, GcId, segments = emptyList()))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(polySegmentRequest(WindowId, GcId, segments = emptyList()))
                out.write(polySegmentRequest(WindowId, GcId, segments = listOf((0 to 0) to (1 to 0), (3 to 0) to (4 to 0))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 66, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 66, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 13, opcode = 66, badValue = GcId, sequence = 4)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 2, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 4, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyRectangle and PolyFillRectangle report request errors and recover stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(polyRectangleBadLengthRequest(opcode = 67, bodySize = 4))
                out.write(polyRectangleBadLengthRequest(opcode = 70, bodySize = 12))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = emptyList()))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(3, 0, 2, 2))))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = emptyList()))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = emptyList()))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(3, 0, 2, 2))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 2))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 67, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 70, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 13, opcode = 67, badValue = GcId, sequence = 4)
                assertError(socket.getInputStream(), error = 13, opcode = 70, badValue = GcId, sequence = 5)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 2, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 4, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 0, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 1, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 2, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 3, 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 5, 4, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyArc and PolyFillArc report request errors and recover stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(polyArcBadLengthRequest(opcode = 68, bodySize = 4))
                out.write(polyArcBadLengthRequest(opcode = 71, bodySize = 16))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = emptyList()))
                out.write(polyArcRequest(WindowId, GcId, filled = true, arcs = listOf(XArcCommand(14, 0, 10, 10, 0, FullCircleAngle))))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = emptyList()))
                out.write(polyArcRequest(WindowId, GcId + 1, filled = true, arcs = emptyList()))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = listOf(XArcCommand(0, 0, 10, 10, 0, FullCircleAngle))))
                out.write(polyArcRequest(WindowId, GcId + 1, filled = true, arcs = listOf(XArcCommand(14, 0, 10, 10, 0, FullCircleAngle))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 26, height = 12))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 68, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 71, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 13, opcode = 68, badValue = GcId, sequence = 4)
                assertError(socket.getInputStream(), error = 13, opcode = 71, badValue = GcId, sequence = 5)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 26, 5, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 26, 5, 5))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 26, 19, 5))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 26, 13, 5))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FillPoly reports request errors and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val missingDrawable = WindowId + 0x7777
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(fillPolyBadLengthRequest(bodySize = 8))
                out.write(fillPolyRequest(WindowId, GcId, shape = 3, coordMode = 0, points = emptyList()))
                out.write(fillPolyRequest(WindowId, GcId, shape = 2, coordMode = 2, points = emptyList()))
                out.write(fillPolyRequest(WindowId, GcId, shape = 2, coordMode = 0, points = emptyList()))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(fillPolyRequest(missingDrawable, GcId, shape = 2, coordMode = 0, points = listOf(1 to 1, 5 to 1, 1 to 5)))
                out.write(fillPolyRequest(WindowId, GcId, shape = 2, coordMode = 1, points = emptyList()))
                out.write(fillPolyRequest(WindowId, GcId, shape = 2, coordMode = 0, points = listOf(1 to 1, 5 to 1, 1 to 5)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 6, height = 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 69, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 69, badValue = 3, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 69, badValue = 2, sequence = 4)
                assertError(socket.getInputStream(), error = 13, opcode = 69, badValue = GcId, sequence = 5)
                assertError(socket.getInputStream(), error = 9, opcode = 69, badValue = missingDrawable, sequence = 7)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 6, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 6, 2, 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 6, 1, 4))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 6, 5, 5))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `core drawing primitives reject drawable and GC depth mismatches before drawing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 8, height = 8))
                out.write(createPixmapRequest(PixmapId, width = 8, height = 8, depth = 8, drawable = WindowId))
                out.write(createGcRequest(GcId, foreground = Red, drawable = PixmapId))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(polyLineRequest(WindowId, GcId, coordMode = 0, points = listOf(0 to 0, 1 to 0)))
                out.write(polySegmentRequest(WindowId, GcId, segments = listOf((0 to 0) to (1 to 0))))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(0, 0, 2, 2))))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = listOf(XArcCommand(0, 0, 4, 4, 0, FullCircleAngle))))
                out.write(fillPolyRequest(WindowId, GcId, shape = 2, coordMode = 0, points = listOf(0 to 0, 2 to 0, 0 to 2)))
                out.write(polyFillRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(0, 0, 2, 2))))
                out.write(polyArcRequest(WindowId, GcId, filled = true, arcs = listOf(XArcCommand(0, 0, 4, 4, 0, FullCircleAngle))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 2))
                out.flush()

                for ((index, opcode) in (64..71).withIndex()) {
                    assertError(socket.getInputStream(), error = 8, opcode = opcode, badValue = WindowId, sequence = 4 + index)
                }

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 2, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 2, 0, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 2, 1, 1))
                assertContains(httpGet(server.localPort, "/state.json"), """"drawings":0""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage reports request errors and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val missingDrawable = WindowId + 0x7777
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(putImageBadLengthRequest(bodySize = 16))
                out.write(putImageRawRequest(WindowId, GcId, format = 3, width = 0, height = 0, depth = 24, data = ByteArray(0)))
                out.write(putImage24PixelsRequest(WindowId, width = 1, height = 1, pixels = listOf(Red)))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 8))
                out.write(createGcRequest(GcId + 1, foreground = Red, drawable = PixmapId))
                out.write(putImage24PixelsRequest(missingDrawable, width = 1, height = 1, pixels = listOf(Red)))
                out.write(putImageRawRequest(WindowId, GcId + 1, format = 2, width = 1, height = 1, depth = 24, data = ByteArray(4)))
                out.write(putImageRawRequest(WindowId, GcId, format = 2, width = 1, height = 1, leftPad = 1, depth = 24, data = ByteArray(4)))
                out.write(putImageRawRequest(WindowId, GcId, format = 2, width = 1, height = 1, depth = 8, data = ByteArray(4)))
                out.write(putImageRawRequest(WindowId, GcId, format = 2, width = 2, height = 2, depth = 24, data = ByteArray(4)))
                out.write(putImageRawRequest(WindowId, GcId, format = 2, width = 1, height = 1, depth = 24, data = ByteArray(8)))
                out.write(putImage24PixelsRequest(WindowId, width = 2, height = 1, pixels = listOf(Red, Green)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 72, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 72, badValue = 3, sequence = 3)
                assertError(socket.getInputStream(), error = 13, opcode = 72, badValue = GcId, sequence = 4)
                assertError(socket.getInputStream(), error = 9, opcode = 72, badValue = missingDrawable, sequence = 8)
                assertError(socket.getInputStream(), error = 8, opcode = 72, badValue = WindowId, sequence = 9)
                assertError(socket.getInputStream(), error = 8, opcode = 72, badValue = WindowId, sequence = 10)
                assertError(socket.getInputStream(), error = 8, opcode = 72, badValue = WindowId, sequence = 11)
                assertError(socket.getInputStream(), error = 16, opcode = 72, badValue = 0, sequence = 12)
                assertError(socket.getInputStream(), error = 16, opcode = 72, badValue = 0, sequence = 13)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage decodes bitmap and XY pixmap formats`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(
                    putImageBitmapRequest(
                        WindowId,
                        GcId,
                        width = 4,
                        height = 1,
                        leftPad = 1,
                        bits = listOf(true, false, true, false),
                    ),
                )
                out.write(
                    putImageXyPixmapRequest(
                        WindowId,
                        GcId,
                        width = 2,
                        height = 1,
                        y = 1,
                        depth = 24,
                        pixels = listOf(Red, Green),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 0, 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 4, 1, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 2, 1))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage bitmap maps GC pixels through drawable depth`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1, depth = 8))
                out.write(createGcRequest(GcId, foreground = 0x80, background = 0x00, drawable = PixmapId))
                out.write(
                    putImageBitmapRequest(
                        PixmapId,
                        GcId,
                        width = 2,
                        height = 1,
                        bits = listOf(true, false),
                    ),
                )
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(8, image[1].toInt() and 0xff)
                assertEquals(4, u32le(image, 12))
                assertEquals(0x80, image[32].toInt() and 0xff)
                assertEquals(0x00, image[33].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage and GetImage ZPixmap honor depth eight packing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 3, height = 1, depth = 8))
                out.write(createGcRequest(GcId, foreground = 0, drawable = PixmapId))
                out.write(
                    putImageRawRequest(
                        PixmapId,
                        GcId,
                        format = 2,
                        width = 3,
                        height = 1,
                        depth = 8,
                        data = byteArrayOf(0x12, 0x80.toByte(), 0xff.toByte(), 0),
                    ),
                )
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(8, image[1].toInt() and 0xff)
                assertEquals(4, u32le(image, 12))
                assertEquals(0x12, image[32].toInt() and 0xff)
                assertEquals(0x80, image[33].toInt() and 0xff)
                assertEquals(0xff, image[34].toInt() and 0xff)
                assertEquals(0, image[35].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage and GetImage ZPixmap honor depth one packing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 9, height = 1, depth = 1))
                out.write(createGcRequest(GcId, foreground = 0, drawable = PixmapId))
                out.write(
                    putImageRawRequest(
                        PixmapId,
                        GcId,
                        format = 2,
                        width = 9,
                        height = 1,
                        depth = 1,
                        data = byteArrayOf(0x85.toByte(), 0x01, 0, 0),
                    ),
                )
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 9, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(1, image[1].toInt() and 0xff)
                assertEquals(4, u32le(image, 12))
                assertEquals(0x85, image[32].toInt() and 0xff)
                assertEquals(0x01, image[33].toInt() and 0xff)
                assertEquals(0, image[34].toInt() and 0xff)
                assertEquals(0, image[35].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage and GetImage ZPixmap honor depth four packing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 3, height = 1, depth = 4))
                out.write(createGcRequest(GcId, foreground = 0, drawable = PixmapId))
                out.write(
                    putImageRawRequest(
                        PixmapId,
                        GcId,
                        format = 2,
                        width = 3,
                        height = 1,
                        depth = 4,
                        data = byteArrayOf(0x02, 0x0a, 0x0f, 0),
                    ),
                )
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(4, image[1].toInt() and 0xff)
                assertEquals(4, u32le(image, 12))
                assertEquals(0x02, image[32].toInt() and 0xff)
                assertEquals(0x0a, image[33].toInt() and 0xff)
                assertEquals(0x0f, image[34].toInt() and 0xff)
                assertEquals(0, image[35].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `core drawing honors GC clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 2, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(3, 2, 4, 4))))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 3, 11 to 3)))
                out.write(polyRectangleRequest(WindowId, GcId, rectangles = listOf(XRectangleCommand(5, 1, 3, 5))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 8))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 4, 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 12, 5, 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 12, 8, 3))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 9, 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 12, 5, 4))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 4, 4))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 9, 4))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `empty GC clip region suppresses framebuffer drawing and svg overlays`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = emptyList()))
                out.write(polyLineRequest(WindowId, GcId, points = listOf(0 to 1, 6 to 1)))
                out.write(mapWindowRequest(WindowId))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 7, height = 2))
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 7, 3, 1))
                val svg = httpGet(server.localPort, "/screen.svg")
                assertEquals(true, svg.contains("""data-window-id="0x200001""""))
                assertEquals(true, svg.contains("""class="framebuffer-image""""))
                assertEquals(false, svg.contains("<polyline"))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage and CopyArea honor GC clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 4, height = 2))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24Request(PixmapId, width = 4, height = 2, pixel = Green))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(1, 0, 2, 3))))
                out.write(putImage24Request(WindowId, width = 4, height = 1, pixel = Red))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 1, destinationX = 0, destinationY = 2, width = 4, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 3))
                out.flush()

                assertNoExposure(socket.getInputStream().readExactly(32), sequence = 7, drawable = WindowId, majorOpcode = 62)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 0, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 4, 1, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 4, 2, 2))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `core GC subwindow mode clips mapped child windows`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent, width = 6, height = 3, backgroundPixel = 0x00ff_ffff))
                out.write(createWindowRequest(child, parent = parent, x = 2, y = 0, width = 2, height = 3, backgroundPixel = Green))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(createGcRequest(GcId, foreground = Red, drawable = parent))
                out.write(polyFillRectangleRequest(parent, GcId, rectangles = listOf(XRectangleCommand(0, 0, 6, 3))))
                out.write(getImageRequest(parent, x = 0, y = 0, width = 6, height = 1))
                out.write(changeGcRawRequest(GcId, mask = 0x0000_8004, values = listOf(Blue, 1)))
                out.write(polyFillRectangleRequest(parent, GcId, rectangles = listOf(XRectangleCommand(0, 0, 6, 3))))
                out.write(getImageRequest(parent, x = 0, y = 0, width = 6, height = 1))
                out.flush()

                assertExpose(socket.getInputStream().readExactly(32), parent, sequence = 3)
                assertExpose(socket.getInputStream().readExactly(32), child, sequence = 4)
                val clipped = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(clipped, 6, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(clipped, 6, 1, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, 6, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, 6, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(clipped, 6, 4, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(clipped, 6, 5, 0))

                val included = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(included, 6, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(included, 6, 1, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(included, 6, 2, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(included, 6, 3, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(included, 6, 4, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(included, 6, 5, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea and CopyPlane honor GC subwindow mode for source windows`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val source = WindowId
                val child = WindowId + 1
                val destination = WindowId + 2
                val out = socket.getOutputStream()
                out.write(createWindowRequest(source, width = 4, height = 3, backgroundPixel = Green))
                out.write(createWindowRequest(child, parent = source, x = 1, y = 0, width = 2, height = 3, backgroundPixel = Green))
                out.write(createWindowRequest(destination, width = 4, height = 3, backgroundPixel = Blue))
                out.write(createGcRequest(GcId, foreground = Green, drawable = source))
                out.write(putImage24Request(source, width = 4, height = 3, pixel = Green, gc = GcId))
                out.write(mapWindowRequest(source))
                out.write(mapWindowRequest(child))
                out.write(createGcRequest(GcId + 1, foreground = Red, background = Green, drawable = destination))
                out.write(putImage24Request(destination, width = 4, height = 3, pixel = Blue, gc = GcId + 1))
                out.write(copyAreaRequest(source, destination, GcId + 1, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 4, height = 1))
                out.write(getImageRequest(destination, x = 0, y = 0, width = 4, height = 1))
                out.write(copyPlaneRequest(source, destination, GcId + 1, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 1, width = 4, height = 1, bitPlane = 0x0000_0100))
                out.write(getImageRequest(destination, x = 0, y = 1, width = 4, height = 1))
                out.write(changeGcRawRequest(GcId + 1, mask = 0x0000_8000, values = listOf(1)))
                out.write(copyAreaRequest(source, destination, GcId + 1, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 2, width = 4, height = 1))
                out.write(getImageRequest(destination, x = 0, y = 2, width = 4, height = 1))
                out.flush()

                assertExpose(socket.getInputStream().readExactly(32), source, sequence = 6)
                assertExpose(socket.getInputStream().readExactly(32), child, sequence = 7)
                assertGraphicsExposure(
                    socket.getInputStream().readExactly(32),
                    sequence = 10,
                    drawable = destination,
                    rectangle = XRectangleCommand(1, 0, 2, 1),
                    majorOpcode = 62,
                    count = 0,
                )
                val defaultArea = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(defaultArea, 4, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(defaultArea, 4, 1, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(defaultArea, 4, 2, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(defaultArea, 4, 3, 0))

                assertGraphicsExposure(
                    socket.getInputStream().readExactly(32),
                    sequence = 12,
                    drawable = destination,
                    rectangle = XRectangleCommand(1, 1, 2, 1),
                    majorOpcode = 63,
                    count = 0,
                )
                val defaultPlane = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(defaultPlane, 4, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(defaultPlane, 4, 1, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(defaultPlane, 4, 2, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(defaultPlane, 4, 3, 0))

                assertNoExposure(socket.getInputStream().readExactly(32), sequence = 15, drawable = destination, majorOpcode = 62)
                val includedArea = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(includedArea, 4, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(includedArea, 4, 1, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(includedArea, 4, 2, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(includedArea, 4, 3, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyPlane paints foreground and background pixels through GC clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 4, height = 2))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 4,
                        height = 2,
                        pixels = listOf(
                            1, 0, 1, 0,
                            0, 1, 0, 1,
                        ),
                    ),
                )
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(1, 0, 2, 2))))
                out.write(copyPlaneRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 4, height = 2, bitPlane = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 2))
                out.flush()

                assertNoExposure(socket.getInputStream().readExactly(32), sequence = 6, drawable = WindowId, majorOpcode = 63)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 1, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 2, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 4, 3, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 4, 1, 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 4, 2, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea and CopyPlane suppress NoExposure when graphics exposures are false`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1))
                out.write(createGcRawRequest(GcId, mask = 0x0001_000c, values = listOf(Red, Blue, 0)))
                out.write(createGcRequest(GcId + 1, foreground = Red))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0001_0000))
                out.write(putImage24PixelsRequest(PixmapId, width = 2, height = 1, pixels = listOf(1, 0)))
                out.write(copyPlaneRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1, bitPlane = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId + 1, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val planeImage = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(planeImage, 2, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(planeImage, 2, 1, 0))

                val areaImage = readReply(socket.getInputStream())
                assertEquals(0xff00_0001.toInt(), pixelAt(areaImage, 2, 0, 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(areaImage, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea and CopyPlane emit GraphicsExposure for out of bounds source regions`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, backgroundPixel = Blue))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(putImage24PixelsRequest(PixmapId, width = 2, height = 1, pixels = listOf(Green, Blue)))
                out.write(putImage24PixelsRequest(WindowId, width = 2, height = 2, pixels = listOf(Red, Red, Red, Red)))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = -1, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = 3, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.write(copyPlaneRequest(PixmapId, WindowId, GcId, sourceX = -1, sourceY = 0, destinationX = 0, destinationY = 1, width = 2, height = 1, bitPlane = 0x0000_0100))
                out.write(getImageRequest(WindowId, x = 0, y = 1, width = 2, height = 1))
                out.flush()

                assertGraphicsExposure(
                    socket.getInputStream().readExactly(32),
                    sequence = 6,
                    drawable = WindowId,
                    rectangle = XRectangleCommand(0, 0, 1, 1),
                    majorOpcode = 62,
                    count = 0,
                )
                val partialAreaImage = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(partialAreaImage, 2, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(partialAreaImage, 2, 1, 0))

                assertGraphicsExposure(
                    socket.getInputStream().readExactly(32),
                    sequence = 8,
                    drawable = WindowId,
                    rectangle = XRectangleCommand(0, 0, 2, 1),
                    majorOpcode = 62,
                    count = 0,
                )
                val fullAreaImage = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(fullAreaImage, 2, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(fullAreaImage, 2, 1, 0))

                assertGraphicsExposure(
                    socket.getInputStream().readExactly(32),
                    sequence = 10,
                    drawable = WindowId,
                    rectangle = XRectangleCommand(0, 1, 1, 1),
                    majorOpcode = 63,
                    count = 0,
                )
                val planeImage = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(planeImage, 2, 0, 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(planeImage, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea exposure regions honor GC clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, backgroundPixel = Blue))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24PixelsRequest(PixmapId, width = 2, height = 1, pixels = listOf(Green, Red)))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(1, 0, 1, 1))))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = -1, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                assertNoExposure(socket.getInputStream().readExactly(32), sequence = 6, drawable = WindowId, majorOpcode = 62)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea exposure regions treat overlapping GC clip rectangles as a union`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, backgroundPixel = Blue))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24PixelsRequest(PixmapId, width = 2, height = 1, pixels = listOf(Green, Red)))
                out.write(
                    setClipRectanglesRequest(
                        GcId,
                        clipXOrigin = 0,
                        clipYOrigin = 0,
                        rectangles = listOf(
                            XRectangleCommand(0, 0, 2, 1),
                            XRectangleCommand(0, 0, 1, 1),
                        ),
                    ),
                )
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = -1, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                assertGraphicsExposure(
                    socket.getInputStream().readExactly(32),
                    sequence = 6,
                    drawable = WindowId,
                    rectangle = XRectangleCommand(0, 0, 1, 1),
                    majorOpcode = 62,
                    count = 0,
                )
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea background repaint from source exposure is retained in semantic drawing stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, backgroundPixel = Blue))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = 3, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                assertGraphicsExposure(
                    socket.getInputStream().readExactly(32),
                    sequence = 4,
                    drawable = WindowId,
                    rectangle = XRectangleCommand(0, 0, 2, 1),
                    majorOpcode = 62,
                    count = 0,
                )
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 2, 1, 0))
                assertContains(httpGet(server.localPort, "/state.json"), """"drawings":1""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea with empty GC clip does not add semantic copy drawing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = emptyList()))
                out.write(copyAreaRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertNoExposure(socket.getInputStream().readExactly(32), sequence = 5, drawable = WindowId, majorOpcode = 62)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 1, 0, 0))
                assertContains(httpGet(server.localPort, "/state.json"), """"drawings":0""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyPlane rejects invalid bit plane without drawing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(putImage24PixelsRequest(PixmapId, width = 2, height = 1, pixels = listOf(1, 0)))
                out.write(copyPlaneRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1, bitPlane = 3))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(3, u32le(error, 4))
                assertEquals(63, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyPlane rejects bit plane outside source depth`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1, depth = 1))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(copyPlaneRequest(PixmapId, WindowId, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 2, height = 1, bitPlane = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 63, badValue = 2, sequence = 4)
                val pointer = readReply(socket.getInputStream())
                assertEquals(5, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CopyArea and CopyPlane reject drawable and GC mismatches before copying`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val destination = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createWindowRequest(destination))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 1, depth = 1))
                out.write(createGcRequest(GcId, foreground = Red, drawable = PixmapId))
                out.write(copyAreaRequest(WindowId, destination, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(createGcRequest(GcId + 1, foreground = Red))
                out.write(copyAreaRequest(PixmapId, destination, GcId + 1, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(copyPlaneRequest(WindowId, destination, GcId, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 1, height = 1, bitPlane = 1))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 8, opcode = 62, badValue = GcId, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = 62, badValue = destination, sequence = 7)
                assertError(socket.getInputStream(), error = 8, opcode = 63, badValue = GcId, sequence = 8)
                val pointer = readReply(socket.getInputStream())
                assertEquals(9, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `fixed-size copy and clear requests validate length and stream recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(61, 0, ByteArray(8)))
                out.write(request(61, 0, ByteArray(16)))
                out.write(request(62, 0, ByteArray(20)))
                out.write(request(62, 0, ByteArray(28)))
                out.write(request(63, 0, ByteArray(24)))
                out.write(request(63, 0, ByteArray(32)))
                out.write(getInputFocusRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 61, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 61, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 62, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 62, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 63, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 16, opcode = 63, badValue = 0, sequence = 6)

                val focus = readReply(socket.getInputStream())
                assertEquals(7, u16le(focus, 2))
                assertEquals(X11Ids.RootWindow, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `invalid coordinate mode reports Value error without drawing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 2, points = listOf(2 to 3)))
                out.write(polyLineRequest(WindowId, GcId, coordMode = 3, points = listOf(1 to 1, 4 to 1)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 5))
                out.flush()

                val pointError = socket.getInputStream().readExactly(32)
                assertEquals(0, pointError[0].toInt())
                assertEquals(2, pointError[1].toInt() and 0xff)
                assertEquals(2, u32le(pointError, 4))
                assertEquals(64, pointError[10].toInt() and 0xff)

                val lineError = socket.getInputStream().readExactly(32)
                assertEquals(0, lineError[0].toInt())
                assertEquals(2, lineError[1].toInt() and 0xff)
                assertEquals(3, u32le(lineError, 4))
                assertEquals(65, lineError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 2, 3))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 1, 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetImage reports request errors and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val missingDrawable = WindowId + 0x7777
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(getImageBadLengthRequest(bodySize = 12))
                out.write(getImageBadLengthRequest(bodySize = 20))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1, format = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1, format = 3))
                out.write(getImageRequest(missingDrawable, x = 0, y = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 39, y = 29, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 73, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 73, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 73, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 73, badValue = 3, sequence = 5)
                assertError(socket.getInputStream(), error = 9, opcode = 73, badValue = missingDrawable, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 73, badValue = WindowId, sequence = 7)

                val image = readReply(socket.getInputStream())
                assertEquals(1, image[0].toInt())
                assertEquals(8, u16le(image, 2))
                assertEquals(1, u32le(image, 4))
                assertEquals(4, u32le(image, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetImage validates bounds and applies plane mask`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = 0x0012_3456))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(2 to 3)))
                out.write(getImageRequest(WindowId, x = 2, y = 3, width = 1, height = 1, planeMask = 0x00ff_0000))
                out.write(getImageRequest(WindowId, x = 2, y = 3, width = 1, height = 1, planeMask = 0x0000_0010, format = 1))
                out.write(getImageRequest(WindowId, x = 39, y = 29, width = 2, height = 1))
                out.flush()

                val masked = readReply(socket.getInputStream())
                assertEquals(1, masked[0].toInt())
                assertEquals(24, masked[1].toInt() and 0xff)
                assertEquals(1, u32le(masked, 4))
                assertEquals(X11Ids.RootVisual, u32le(masked, 8))
                assertEquals(4, u32le(masked, 12))
                assertEquals(0x0012_0000, pixelAt(masked, 1, 0, 0))

                val xyPixmap = readReply(socket.getInputStream())
                assertEquals(1, xyPixmap[0].toInt())
                assertEquals(24, xyPixmap[1].toInt() and 0xff)
                assertEquals(1, u32le(xyPixmap, 4))
                assertEquals(X11Ids.RootVisual, u32le(xyPixmap, 8))
                assertEquals(4, u32le(xyPixmap, 12))
                assertEquals(1, xyPixmap[32].toInt() and 0xff)
                assertEquals(0, xyPixmap[33].toInt() and 0xff)
                assertEquals(0, xyPixmap[34].toInt() and 0xff)
                assertEquals(0, xyPixmap[35].toInt() and 0xff)

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(8, error[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(error, 4))
                assertEquals(73, error[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetImage XYPixmap encodes selected planes most significant first`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(putImage24PixelsRequest(WindowId, width = 2, height = 1, pixels = listOf(0x0080_0000, 0x0000_0001)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1, planeMask = 0x0080_0001, format = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(1, image[0].toInt())
                assertEquals(24, image[1].toInt() and 0xff)
                assertEquals(2, u32le(image, 4))
                assertEquals(X11Ids.RootVisual, u32le(image, 8))
                assertEquals(8, u32le(image, 12))
                assertEquals(1, image[32].toInt() and 0xff)
                assertZeroBytes(image, 33, 36)
                assertEquals(2, image[36].toInt() and 0xff)
                assertZeroBytes(image, 37, 40)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PutImage and GetImage use advertised image byte order for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket, byteOrderByte = 0x42)
                val out = socket.getOutputStream()
                out.write(createWindowRequestBigEndian(WindowId))
                out.write(createGcRequestBigEndian(GcId, WindowId))
                out.write(
                    putImage24PixelsRequestBigEndian(
                        WindowId,
                        width = 2,
                        height = 1,
                        pixels = listOf(0x0012_3456, 0x0000_00ff),
                    ),
                )
                out.write(getImageRequestBigEndian(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream(), byteOrderByte = 0x42)
                assertEquals(1, image[0].toInt())
                assertEquals(24, image[1].toInt() and 0xff)
                assertEquals(2, u32be(image, 4))
                assertEquals(X11Ids.RootVisual, u32be(image, 8))
                assertEquals(8, u32be(image, 12))
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 2, 0, 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 2, 1, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FillPoly rejects invalid coordinate mode without drawing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 2))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 5))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(2, u32le(error, 4))
                assertEquals(69, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 2, 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FillPoly paints clipped framebuffer pixels for origin and previous coordinate modes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Green))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 0, points = listOf(1 to 1, 5 to 1, 5 to 5, 1 to 5)))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(8, 1, 2, 4))))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 1, points = listOf(7 to 1, 4 to 0, 0 to 4, -4 to 0)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 7))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 2, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 4, 4))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 6, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 8, 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 12, 9, 4))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 7, 2))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 12, 10, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FillPoly paints pixmap framebuffer content`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 8, height = 8))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(fillPolyRequest(PixmapId, GcId, coordMode = 0, points = listOf(1 to 1, 6 to 1, 6 to 6, 1 to 6)))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0, u32le(image, 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 8, 2, 2))
                assertEquals(0xff00_0000.toInt(), pixelAt(image, 8, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FillPoly honors GC fill rule for complex polygons`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val repeatedLoop = listOf(1 to 1, 6 to 1, 6 to 6, 1 to 6, 1 to 1, 6 to 1, 6 to 6, 1 to 6)
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 0, points = repeatedLoop))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 8))
                out.write(changeGcFillRuleRequest(GcId, fillRule = WindingRule))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 0, points = repeatedLoop))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                val evenOddImage = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(evenOddImage, 8, 2, 2))

                val windingImage = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(windingImage, 8, 2, 2))
                assertEquals(0xffff_ffff.toInt(), pixelAt(windingImage, 8, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `invalid GC fill rule reports Value error without changing GC`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val repeatedLoop = listOf(1 to 1, 6 to 1, 6 to 6, 1 to 6, 1 to 1, 6 to 1, 6 to 6, 1 to 6)
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcFillRuleRequest(GcId, fillRule = 2))
                out.write(fillPolyRequest(WindowId, GcId, coordMode = 0, points = repeatedLoop))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(2, u32le(error, 4))
                assertEquals(56, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 8, 2, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FillPoly rejects invalid shape without drawing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(fillPolyRequest(WindowId, GcId, shape = 3, coordMode = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 5))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(3, u32le(error, 4))
                assertEquals(69, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 5, 2, 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyArc paints outlines from 12 byte arc records`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 50, height = 30))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(
                    polyArcRequest(
                        WindowId,
                        GcId,
                        filled = false,
                        arcs = listOf(
                            XArcCommand(10, 10, 10, 10, 0, FullCircleAngle),
                            XArcCommand(30, 10, 10, 10, 0, FullCircleAngle),
                        ),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 45, height = 25))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 45, 15, 10))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 45, 15, 15))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 45, 35, 10))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `LineOnOffDash leaves prior pixels visible when PolyArc repaints same outline`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val arc = XArcCommand(4, 4, 20, 20, 0, FullCircleAngle)
                out.write(createWindowRequest(WindowId, width = 40, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(changeGcLineStyleRequest(GcId + 1, lineStyle = 1))
                out.write(setDashesRequest(GcId + 1, dashOffset = 0, dashes = listOf(1, 1)))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = listOf(arc)))
                out.write(polyArcRequest(WindowId, GcId + 1, filled = false, arcs = listOf(arc)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 30, height = 30))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 30, 30, 0xffff_0000.toInt()) > 0)
                assertTrue(countPixels(image, 30, 30, 0xff00_00ff.toInt()) > 0)

                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"kind":"Arc"""")
                assertContains(stateJson, """"background":"0xffffff"""")
                assertContains(stateJson, """"lineStyle":1""")
                assertContains(stateJson, """"dashes":[1,1]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `dashed one-pixel PolyArc uses dash phase instead of always painting foreground`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(createGcRequest(GcId + 2, foreground = Blue, background = Green))
                out.write(changeGcLineStyleRequest(GcId + 1, lineStyle = 1))
                out.write(changeGcLineStyleRequest(GcId + 2, lineStyle = 2))
                out.write(setDashesRequest(GcId + 1, dashOffset = 1, dashes = listOf(1, 1)))
                out.write(setDashesRequest(GcId + 2, dashOffset = 1, dashes = listOf(1, 1)))
                out.write(polyPointRequest(WindowId, GcId, coordMode = 0, points = listOf(5 to 5)))
                out.write(polyArcRequest(WindowId, GcId + 1, filled = false, arcs = listOf(XArcCommand(4, 4, 1, 1, 0, -1))))
                out.write(polyArcRequest(WindowId, GcId + 2, filled = false, arcs = listOf(XArcCommand(14, 4, 1, 1, 0, -1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 20, height = 10))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 20, 5, 5))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, 20, 15, 5))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `one-pixel PolyArc uses tiled fill source for outline pixel`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = 0x00aa_00aa))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = listOf(XArcCommand(4, 4, 1, 1, 0, -1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 10, height = 10))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff12_3456.toInt(), pixelAt(image, 10, 5, 5))
                assertEquals(0, countPixels(image, 10, 10, 0xffaa_00aa.toInt()))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyArc outline segments use tiled fill source`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 30, height = 20))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2))
                out.write(createGcRequest(GcId, foreground = 0x00aa_00aa))
                out.write(
                    putImage24PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        pixels = listOf(
                            Red, Green,
                            Blue, 0x0012_3456,
                        ),
                    ),
                )
                out.write(changeGcTiledFillRequest(GcId, tilePixmap = PixmapId, xOrigin = 0, yOrigin = 0))
                out.write(polyArcRequest(WindowId, GcId, filled = false, arcs = listOf(XArcCommand(4, 4, 8, 8, 0, 90 * 64))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 18, height = 14))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 18, 12, 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 18, 12, 7))
                assertEquals(0, countPixels(image, 18, 14, 0xffaa_00aa.toInt()))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyFillArc paints clipped window and pixmap framebuffer pixels without svg overlays`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 8, height = 8))
                out.write(createGcRequest(GcId, foreground = Green))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(mapWindowRequest(WindowId))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = listOf(XRectangleCommand(12, 10, 6, 10))))
                out.write(polyArcRequest(WindowId, GcId, filled = true, arcs = listOf(XArcCommand(10, 10, 10, 10, 0, FullCircleAngle))))
                out.write(polyArcRequest(PixmapId, GcId + 1, filled = true, arcs = listOf(XArcCommand(1, 1, 6, 6, 0, FullCircleAngle))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 22, height = 22))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                val windowImage = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(windowImage, 22, 15, 15))
                assertEquals(0xffff_ffff.toInt(), pixelAt(windowImage, 22, 11, 15))
                assertEquals(0xffff_ffff.toInt(), pixelAt(windowImage, 22, 10, 10))

                val pixmapImage = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(pixmapImage, 8, 4, 4))
                assertEquals(0xff00_0000.toInt(), pixelAt(pixmapImage, 8, 0, 0))

                val svg = httpGet(server.localPort, "/screen.svg")
                assertEquals(true, svg.contains("""data-window-id="0x200001""""))
                assertEquals(true, svg.contains("""class="framebuffer-image""""))
                assertEquals(false, svg.contains("<ellipse"))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyFillArc honors partial angles and GC arc mode`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(changeGcArcModeRequest(GcId + 1, arcMode = ArcChord))
                out.write(polyArcRequest(WindowId, GcId, filled = true, arcs = listOf(XArcCommand(10, 10, 20, 20, 0, 90 * 64))))
                out.write(polyArcRequest(WindowId, GcId + 1, filled = true, arcs = listOf(XArcCommand(40, 10, 20, 20, 0, 180 * 64))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 65, height = 35))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 65, 24, 16))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 65, 15, 16))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 65, 50, 15))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 65, 50, 24))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `invalid GC arc mode reports Value error without changing GC`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 40, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(changeGcArcModeRequest(GcId, arcMode = 2))
                out.write(polyArcRequest(WindowId, GcId, filled = true, arcs = listOf(XArcCommand(10, 10, 20, 20, 0, 90 * 64))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 35, height = 35))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(2, u32le(error, 4))
                assertEquals(56, error[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, 35, 24, 16))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 35, 15, 16))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ImageText reports request errors and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingDrawable = WindowId + 0x7777
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(imageTextBadLengthRequest(opcode = 76, textLength = 1, bodySize = 8))
                out.write(imageTextBadLengthRequest(opcode = 77, textLength = 1, bodySize = 20))
                out.write(imageText8Request(WindowId, GcId, x = 4, y = 18, text = "I"))
                out.write(createGcRequest(GcId, foreground = Green, background = Blue))
                out.write(imageText8Request(missingDrawable, GcId, x = 4, y = 18, text = "I"))
                out.write(createPixmapRequest(PixmapId, width = 40, height = 36, depth = 8))
                out.write(imageText8Request(PixmapId, GcId, x = 4, y = 18, text = "I"))
                out.write(imageText16Request(WindowId, GcId, x = 4, y = 18, char2b = listOf(0 to 'I'.code)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 20, height = 24))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 76, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 77, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 13, opcode = 76, badValue = GcId, sequence = 4)
                assertError(socket.getInputStream(), error = 9, opcode = 76, badValue = missingDrawable, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 76, badValue = PixmapId, sequence = 8)

                val image = readReply(socket.getInputStream())
                assertEquals(10, u16le(image, 2))
                assertTrue(countPixels(image, 20, 24, 0xff00_ff00.toInt()) > 0)
                assertTrue(countPixels(image, 20, 24, 0xff00_00ff.toInt()) > 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ImageText8 paints background and foreground into window framebuffer`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(imageText8Request(WindowId, GcId, x = 4, y = 18, text = "Hi"))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 30, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 30, 24, 0xffff_0000.toInt()) > 0)
                assertTrue(countPixels(image, 30, 24, 0xff00_00ff.toInt()) > 100)
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 30, 0, 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ImageText8 ignores GC function and clipped text does not render svg overlay`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(changeGcRasterRequest(GcId, function = GXnoop))
                out.write(imageText8Request(WindowId, GcId, x = 4, y = 18, text = "A"))
                out.write(setClipRectanglesRequest(GcId, clipXOrigin = 0, clipYOrigin = 0, rectangles = emptyList()))
                out.write(imageText8Request(WindowId, GcId, x = 4, y = 34, text = "Hidden"))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 64, height = 40))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 64, 40, 0xffff_0000.toInt()) > 0)
                assertTrue(countPixels(image, 64, 40, 0xff00_00ff.toInt()) > 0)
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, 64, 4, 34))
                assertFalse(httpGet(server.localPort, "/screen.svg").contains(">Hidden<"))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ImageText16 decodes CHAR2B cells and paints background`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(imageText16Request(WindowId, GcId, x = 4, y = 18, char2b = listOf(0x00 to 'A'.code, 0x01 to 0x80)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 30, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 30, 24, 0xffff_0000.toInt()) > 0)
                assertTrue(countPixels(image, 30, 24, 0xff00_00ff.toInt()) > 100)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryFont reports synthetic text metrics in CHARINFO and font fields`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(queryFontRequest(GcId))
                out.flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(8, u16le(reply, 10))
                assertEquals(8, u16le(reply, 12))
                assertEquals(12, u16le(reply, 14))
                assertEquals(4, u16le(reply, 16))
                assertEquals(8, u16le(reply, 26))
                assertEquals(8, u16le(reply, 28))
                assertEquals(12, u16le(reply, 30))
                assertEquals(4, u16le(reply, 32))
                assertEquals(12, u16le(reply, 52))
                assertEquals(4, u16le(reply, 54))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `font query requests validate fontable and pattern framing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingFontable = GcId + 900
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(request(47, 0, ByteArray(0)))
                out.write(queryFontRequest(missingFontable))
                out.write(request(48, 2, ByteArray(0)))
                out.write(request(48, 2, queryTextExtentsBody(GcId, char2b = emptyList())))
                out.write(queryTextExtentsRequest(missingFontable, char2b = emptyList()))
                out.write(queryTextExtentsOddPaddingBadLengthRequest(GcId))
                out.write(request(49, 0, ByteArray(0)))
                out.write(malformedListFontsRequest(declaredPatternLength = 5, patternBytes = byteArrayOf()))
                out.write(listFontsRequest("*", maxNames = 100))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 47, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 7, opcode = 47, badValue = missingFontable, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 48, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 48, badValue = 2, sequence = 6)
                assertError(socket.getInputStream(), error = 7, opcode = 48, badValue = missingFontable, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 48, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 49, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 16, opcode = 49, badValue = 0, sequence = 10)

                val reply = readReply(socket.getInputStream())
                assertEquals(1, reply[0].toInt())
                assertEquals(11, u16le(reply, 2))
                assertEquals(2, u32le(reply, 4))
                assertEquals(listOf("fixed"), fontNames(reply))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryTextExtents reports synthetic fixed metrics for even and odd CHAR2B strings`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(queryTextExtentsRequest(GcId, char2b = listOf(0x00 to 'O'.code, 0x00 to 'K'.code)))
                out.write(queryTextExtentsRequest(GcId, char2b = listOf(0x00 to 'A'.code, 0x01 to 0x80, 0x00 to 'B'.code)))
                out.flush()

                val even = readReply(socket.getInputStream())
                assertEquals(1, even[0].toInt())
                assertEquals(0, even[1].toInt())
                assertEquals(0, u32le(even, 4))
                assertEquals(12, u16le(even, 8))
                assertEquals(4, u16le(even, 10))
                assertEquals(12, u16le(even, 12))
                assertEquals(4, u16le(even, 14))
                assertEquals(16, u32le(even, 16))
                assertEquals(0, u32le(even, 20))
                assertEquals(16, u32le(even, 24))

                val odd = readReply(socket.getInputStream())
                assertEquals(24, u32le(odd, 16))
                assertEquals(24, u32le(odd, 24))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryTextExtents keeps font metrics and zero overall bounds for empty string`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(queryTextExtentsRequest(GcId, char2b = emptyList()))
                out.flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(12, u16le(reply, 8))
                assertEquals(4, u16le(reply, 10))
                assertEquals(0, u16le(reply, 12))
                assertEquals(0, u16le(reply, 14))
                assertEquals(0, u32le(reply, 16))
                assertEquals(0, u32le(reply, 20))
                assertEquals(0, u32le(reply, 24))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ListFontsWithInfo returns synthetic fixed font info and terminal reply`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(listFontsWithInfoRequest("*", maxNames = 100))
                out.write(listFontsWithInfoRequest("fixed", maxNames = 0))
                out.flush()

                val info = readReply(socket.getInputStream())
                assertEquals(68, info.size)
                assertEquals(1, info[0].toInt())
                assertEquals("fixed".length, info[1].toInt() and 0xff)
                assertEquals(1, u16le(info, 2))
                assertEquals(9, u32le(info, 4))
                assertEquals(8, u16le(info, 10))
                assertEquals(8, u16le(info, 26))
                assertEquals(12, u16le(info, 52))
                assertEquals(4, u16le(info, 54))
                assertEquals("fixed", info.copyOfRange(60, 65).decodeToString())
                assertZeroBytes(info, 65, 68)

                val terminal = readReply(socket.getInputStream())
                assertListFontsWithInfoTerminal(terminal, sequence = 1)

                val maxZeroTerminal = readReply(socket.getInputStream())
                assertListFontsWithInfoTerminal(maxZeroTerminal, sequence = 2)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ListFontsWithInfo validates pattern length and preserves stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(50, 0, ByteArray(0)))
                out.write(malformedListFontsWithInfoRequest(declaredPatternLength = 5, patternBytes = byteArrayOf()))
                out.write(getFontPathRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 50, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 50, badValue = 0, sequence = 2)
                val recovered = readReply(socket.getInputStream())
                assertEquals(1, recovered[0].toInt())
                assertEquals(3, u16le(recovered, 2))
                assertEquals(emptyList(), fontPathEntries(recovered))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetFontPath returns default empty server font path`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(getFontPathRequest())
                out.flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(1, reply[0].toInt())
                assertEquals(0, reply[1].toInt() and 0xff)
                assertEquals(1, u16le(reply, 2))
                assertEquals(0, u32le(reply, 4))
                assertEquals(0, u16le(reply, 8))
                assertEquals(emptyList(), fontPathEntries(reply))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetFontPath updates GetFontPath and empty list restores default path`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(setFontPathRequest("/usr/share/fonts", "misc"))
                out.write(getFontPathRequest())
                out.flush()

                val updated = readReply(socket.getInputStream())
                assertEquals(1, updated[0].toInt())
                assertEquals(0, updated[1].toInt() and 0xff)
                assertEquals(2, u16le(updated, 2))
                assertEquals(2, u16le(updated, 8))
                assertEquals(listOf("/usr/share/fonts", "misc"), fontPathEntries(updated))
                assertContains(httpGet(server.localPort, "/state.json"), """"fontPath":["/usr/share/fonts","misc"]""")

                out.write(setFontPathRequest())
                out.write(getFontPathRequest())
                out.flush()

                val reset = readReply(socket.getInputStream())
                assertEquals(1, reset[0].toInt())
                assertEquals(4, u16le(reset, 2))
                assertEquals(0, u16le(reset, 8))
                assertEquals(emptyList(), fontPathEntries(reset))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetFontPath validates malformed string list length and preserves stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(malformedSetFontPathRequest(count = 1, bytes = byteArrayOf()))
                out.write(malformedSetFontPathRequest(count = 1, bytes = byteArrayOf(4, 'm'.code.toByte())))
                out.write(getFontPathRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 51, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 51, badValue = 0, sequence = 2)
                val recovered = readReply(socket.getInputStream())
                assertEquals(1, recovered[0].toInt())
                assertEquals(3, u16le(recovered, 2))
                assertEquals(emptyList(), fontPathEntries(recovered))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyText reports request errors and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingDrawable = WindowId + 0x7777
                val missingFont = WindowId + 0x7778
                val font = WindowId + 0x7779
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(polyTextBadLengthRequest(opcode = 74, bodySize = 8))
                out.write(polyTextBadLengthRequest(opcode = 75, bodySize = 8))
                out.write(polyText8Request(WindowId, GcId, x = 2, y = 14, delta = 0, text = "I"))
                out.write(createGcRequest(GcId, foreground = Green))
                out.write(polyText8Request(missingDrawable, GcId, x = 2, y = 14, delta = 0, text = "I"))
                out.write(createPixmapRequest(PixmapId, width = 40, height = 36, depth = 8))
                out.write(polyText8Request(PixmapId, GcId, x = 2, y = 14, delta = 0, text = "I"))
                out.write(polyTextMalformedElementRequest(WindowId, GcId))
                out.write(polyTextFontItemRequest(WindowId, GcId, font = missingFont))
                out.write(openFontRequest(font))
                out.write(polyTextFontItemRequest(WindowId, GcId, font = font, text = "I"))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 20, height = 20))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 74, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 75, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 13, opcode = 74, badValue = GcId, sequence = 4)
                assertError(socket.getInputStream(), error = 9, opcode = 74, badValue = missingDrawable, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 74, badValue = PixmapId, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 74, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 7, opcode = 74, badValue = missingFont, sequence = 10)

                val image = readReply(socket.getInputStream())
                assertEquals(13, u16le(image, 2))
                assertTrue(countPixels(image, 20, 20, 0xff00_ff00.toInt()) > 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyText8 paints pixmap framebuffer content and honors item delta`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 40, height = 36))
                out.write(createGcRequest(GcId, foreground = Green))
                out.write(polyText8Request(PixmapId, GcId, x = 2, y = 14, delta = 0, text = "III", padding = byteArrayOf(255.toByte(), 1, 2)))
                out.write(polyText8Request(PixmapId, GcId, x = 2, y = 30, delta = 10, text = "I"))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 40, height = 36))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 40, 36, 0xff00_ff00.toInt()) > 0)
                val baselineLeft = firstPixelX(image, 40, yRange = 2 until 16, pixel = 0xff00_ff00.toInt()) ?: error("missing baseline text pixels")
                val shiftedLeft = firstPixelX(image, 40, yRange = 18 until 32, pixel = 0xff00_ff00.toInt()) ?: error("missing shifted text pixels")
                assertEquals(10, shiftedLeft - baselineLeft)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `PolyText16 paints pixmap framebuffer content and honors item delta`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 40, height = 36))
                out.write(createGcRequest(GcId, foreground = Green))
                out.write(polyText16Request(PixmapId, GcId, x = 2, y = 14, delta = 0, char2b = listOf(0 to 'I'.code)))
                out.write(polyText16Request(PixmapId, GcId, x = 2, y = 30, delta = 10, char2b = listOf(0 to 'I'.code)))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 40, height = 36))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertTrue(countPixels(image, 40, 36, 0xff00_ff00.toInt()) > 0)
                val baselineLeft = firstPixelX(image, 40, yRange = 2 until 16, pixel = 0xff00_ff00.toInt()) ?: error("missing baseline text pixels")
                val shiftedLeft = firstPixelX(image, 40, yRange = 18 until 32, pixel = 0xff00_ff00.toInt()) ?: error("missing shifted text pixels")
                assertEquals(10, shiftedLeft - baselineLeft)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetMotionEvents replies with empty motion history for valid window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(getMotionEventsRequest(WindowId, start = 0, stop = 0))
                out.write(queryPointerRequest())
                out.flush()

                val motion = readReply(socket.getInputStream())
                assertEquals(1, motion[0].toInt())
                assertEquals(2, u16le(motion, 2))
                assertEquals(0, u32le(motion, 4))
                assertEquals(0, u32le(motion, 8))
                assertZeroBytes(motion, 12, 32)

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetMotionEvents returns window relative motion history filtered by time`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, x = 10, y = 20, width = 50, height = 40))
                out.write(mapWindowRequest(WindowId))
                out.write(warpPointerRequest(destinationWindow = WindowId, destinationX = 5, destinationY = 6))
                out.write(warpPointerRequest(destinationWindow = WindowId, destinationX = 7, destinationY = 8))
                out.write(getMotionEventsRequest(WindowId, start = 1, stop = 0))
                out.write(getMotionEventsRequest(WindowId, start = 2, stop = 2))
                out.write(getMotionEventsRequest(WindowId, start = 3, stop = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)

                val allMotion = readReply(socket.getInputStream())
                assertEquals(1, allMotion[0].toInt())
                assertEquals(5, u16le(allMotion, 2))
                assertEquals(4, u32le(allMotion, 4))
                assertEquals(2, u32le(allMotion, 8))
                assertMotionEvent(allMotion, index = 0, time = 1, x = 5, y = 6)
                assertMotionEvent(allMotion, index = 1, time = 2, x = 7, y = 8)

                val filteredMotion = readReply(socket.getInputStream())
                assertEquals(6, u16le(filteredMotion, 2))
                assertEquals(2, u32le(filteredMotion, 4))
                assertEquals(1, u32le(filteredMotion, 8))
                assertMotionEvent(filteredMotion, index = 0, time = 2, x = 7, y = 8)

                val emptyMotion = readReply(socket.getInputStream())
                assertEquals(7, u16le(emptyMotion, 2))
                assertEquals(0, u32le(emptyMotion, 4))
                assertEquals(0, u32le(emptyMotion, 8))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(8, u16le(pointer, 2))
                assertEquals(17, u16le(pointer, 16))
                assertEquals(28, u16le(pointer, 18))
                assertEquals(17, u16le(pointer, 20))
                assertEquals(28, u16le(pointer, 22))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetMotionEvents validates window and request length and preserves stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missing = WindowId + 111
                out.write(getMotionEventsRequest(missing, start = 0, stop = 0))
                out.write(getMotionEventsBadLengthRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 3, opcode = 39, badValue = missing, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 39, badValue = 0, sequence = 2)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `TranslateCoordinates converts source window coordinates to destination window coordinates`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val destination = WindowId + 1
                out.write(createWindowRequest(WindowId, x = 20, y = 10, width = 20, height = 20))
                out.write(createWindowRequest(destination, x = 5, y = 4, width = 50, height = 40))
                out.write(translateCoordinatesRequest(sourceWindow = WindowId, destinationWindow = destination, sourceX = 7, sourceY = 5))
                out.flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(1, reply[0].toInt())
                assertEquals(1, reply[1].toInt() and 0xff)
                assertEquals(3, u16le(reply, 2))
                assertEquals(0, u32le(reply, 8))
                assertEquals(22, u16le(reply, 12))
                assertEquals(11, u16le(reply, 14))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `TranslateCoordinates reports mapped child containing translated point`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val child = WindowId + 1
                out.write(createWindowRequest(WindowId, x = 5, y = 4, width = 50, height = 40))
                out.write(createWindowRequest(child, x = 18, y = 8, width = 10, height = 10, parent = WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(mapWindowRequest(child))
                out.write(
                    translateCoordinatesRequest(
                        sourceWindow = X11Ids.RootWindow,
                        destinationWindow = WindowId,
                        sourceX = 27,
                        sourceY = 15,
                    ),
                )
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                assertMapAndExpose(socket.getInputStream(), child)
                val reply = readReply(socket.getInputStream())
                assertEquals(1, reply[0].toInt())
                assertEquals(1, reply[1].toInt() and 0xff)
                assertEquals(5, u16le(reply, 2))
                assertEquals(child, u32le(reply, 8))
                assertEquals(22, u16le(reply, 12))
                assertEquals(11, u16le(reply, 14))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `TranslateCoordinates validates windows and request length and preserves stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missing = WindowId + 111
                out.write(translateCoordinatesRequest(sourceWindow = missing, destinationWindow = X11Ids.RootWindow))
                out.write(translateCoordinatesRequest(sourceWindow = X11Ids.RootWindow, destinationWindow = missing))
                out.write(translateCoordinatesBadLengthRequest())
                out.write(translateCoordinatesBadLengthRequest(bodySize = 16))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 3, opcode = 40, badValue = missing, sequence = 1)
                assertError(socket.getInputStream(), error = 3, opcode = 40, badValue = missing, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 40, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 40, badValue = 0, sequence = 4)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `WarpPointer moves pointer to destination window coordinates`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, x = 20, y = 15, width = 40, height = 30))
                out.write(mapWindowRequest(WindowId))
                out.write(warpPointerRequest(destinationWindow = WindowId, destinationX = 7, destinationY = 9))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(1, pointer[1].toInt() and 0xff)
                assertEquals(4, u16le(pointer, 2))
                assertEquals(X11Ids.RootWindow, u32le(pointer, 8))
                assertEquals(WindowId, u32le(pointer, 12))
                assertEquals(27, u16le(pointer, 16))
                assertEquals(24, u16le(pointer, 18))
                assertEquals(27, u16le(pointer, 20))
                assertEquals(24, u16le(pointer, 22))
                assertEquals(0, u16le(pointer, 24))
                assertContains(httpGet(server.localPort, "/state.json"), """"pointer":{"x":27,"y":24""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `WarpPointer moves pointer relative to current root position when destination is None`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(warpPointerRequest(destinationWindow = 0, destinationX = 10, destinationY = 11))
                out.write(warpPointerRequest(destinationWindow = 0, destinationX = -3, destinationY = 5))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
                assertEquals(7, u16le(pointer, 16))
                assertEquals(16, u16le(pointer, 18))
                assertEquals(7, u16le(pointer, 20))
                assertEquals(16, u16le(pointer, 22))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `WarpPointer source rectangle outside pointer leaves position unchanged`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, x = 10, y = 10, width = 20, height = 20))
                out.write(mapWindowRequest(WindowId))
                out.write(warpPointerRequest(destinationWindow = 0, destinationX = 12, destinationY = 12))
                out.write(
                    warpPointerRequest(
                        sourceWindow = WindowId,
                        sourceX = 5,
                        sourceY = 5,
                        sourceWidth = 6,
                        sourceHeight = 6,
                        destinationWindow = 0,
                        destinationX = 50,
                        destinationY = 50,
                    ),
                )
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
                assertEquals(12, u16le(pointer, 16))
                assertEquals(12, u16le(pointer, 18))
                assertEquals(WindowId, u32le(pointer, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `WarpPointer sends MotionNotify to selected destination window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, x = 20, y = 15, width = 40, height = 30, eventMask = 1 shl 6))
                out.write(mapWindowRequest(WindowId))
                out.write(warpPointerRequest(destinationWindow = WindowId, destinationX = 3, destinationY = 4))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                val motion = socket.getInputStream().readExactly(32)
                assertEquals(6, motion[0].toInt() and 0xff)
                assertEquals(0, motion[1].toInt() and 0xff)
                assertEquals(3, u16le(motion, 2))
                assertEquals(X11Ids.RootWindow, u32le(motion, 8))
                assertEquals(WindowId, u32le(motion, 12))
                assertEquals(0, u32le(motion, 16))
                assertEquals(23, u16le(motion, 20))
                assertEquals(19, u16le(motion, 22))
                assertEquals(3, u16le(motion, 24))
                assertEquals(4, u16le(motion, 26))
                assertEquals(0, u16le(motion, 28))
                assertEquals(1, motion[30].toInt() and 0xff)

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(4, u16le(pointer, 2))
                assertEquals(23, u16le(pointer, 16))
                assertEquals(19, u16le(pointer, 18))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `WarpPointer clamps movement to active pointer grab confine window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, x = 10, y = 10, width = 20, height = 20))
                out.write(mapWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId, confineTo = WindowId))
                out.write(warpPointerRequest(destinationWindow = 0, destinationX = 100, destinationY = 80))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt() and 0xff)
                assertEquals(3, u16le(grab, 2))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
                assertEquals(WindowId, u32le(pointer, 12))
                assertEquals(29, u16le(pointer, 16))
                assertEquals(29, u16le(pointer, 18))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `pointer button input clamps to active pointer grab confine window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, x = 10, y = 10, width = 20, height = 20))
                out.write(mapWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId, eventMask = XEventMasks.ButtonPress, confineTo = WindowId))
                out.flush()

                assertMapAndExpose(input, WindowId)
                val grab = readReply(input)
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt() and 0xff)
                assertEquals(3, u16le(grab, 2))

                val down = server.input.pointerDown(100, 80, button = 1)
                assertEquals(WindowId, down.targetWindowId)
                assertEquals(1, down.deliveredEvents)
                assertEquals(29, down.rootX)
                assertEquals(29, down.rootY)

                val button = input.readExactly(32)
                assertButtonEvent(button, type = 4, detail = 1)
                assertEquals(X11Ids.RootWindow, u32le(button, 8))
                assertEquals(WindowId, u32le(button, 12))
                assertEquals(0, u32le(button, 16))
                assertEquals(29, u16le(button, 20))
                assertEquals(29, u16le(button, 22))
                assertEquals(19, u16le(button, 24))
                assertEquals(19, u16le(button, 26))
                assertEquals(0, u16le(button, 28))
                assertEquals(1, button[30].toInt() and 0xff)

                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(input)
                assertEquals(1, pointer[0].toInt())
                assertEquals(WindowId, u32le(pointer, 12))
                assertEquals(29, u16le(pointer, 16))
                assertEquals(29, u16le(pointer, 18))

                val state = httpGet(server.localPort, "/state.json")
                assertContains(state, """"kind":"pointer-down","x":29,"y":29""")
                assertContains(state, """"inputGrabs":[{"kind":"pointer"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `WarpPointer validates windows and request length and preserves stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missing = WindowId + 111
                out.write(warpPointerRequest(sourceWindow = missing, destinationWindow = 0))
                out.write(warpPointerRequest(sourceWindow = 0, destinationWindow = missing))
                out.write(warpPointerBadLengthRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 3, opcode = 41, badValue = missing, sequence = 1)
                assertError(socket.getInputStream(), error = 3, opcode = 41, badValue = missing, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 41, badValue = 0, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `pointer button input clamps to parent-clipped confine window root bounds`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val confine = WindowId + 1
                out.write(createWindowRequest(parent, x = 0, y = 0, width = 20, height = 20))
                out.write(createWindowRequest(confine, parent = parent, x = 25, y = 5, width = 10, height = 10))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(confine))
                out.write(grabPointerRequest(X11Ids.RootWindow, eventMask = XEventMasks.ButtonPress, confineTo = confine))
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, confine)
                val grab = readReply(input)
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt() and 0xff)

                val down = server.input.pointerDown(100, 80, button = 1)
                assertEquals(X11Ids.RootWindow, down.targetWindowId)
                assertEquals(1, down.deliveredEvents)
                assertEquals(34, down.rootX)
                assertEquals(14, down.rootY)

                val button = input.readExactly(32)
                assertButtonEvent(button, type = 4, detail = 1)
                assertEquals(X11Ids.RootWindow, u32le(button, 8))
                assertEquals(X11Ids.RootWindow, u32le(button, 12))
                assertEquals(34, u16le(button, 20))
                assertEquals(14, u16le(button, 22))
                assertEquals(34, u16le(button, 24))
                assertEquals(14, u16le(button, 26))
                val state = httpGet(server.localPort, "/state.json")
                assertContains(state, """"kind":"pointer-down","x":34,"y":14""")
                assertContains(state, """"inputGrabs":[{"kind":"pointer"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabPointer replies success status for valid window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(grabPointerRequest(X11Ids.RootWindow))
                out.write(queryPointerRequest())
                out.flush()

                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt() and 0xff)
                assertEquals(0, u32le(grab, 4))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(1, pointer[1].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabPointer reports NotViewable for unmapped or offscreen windows`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val confine = WindowId + 1
                val offscreen = WindowId + 2
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId))
                out.write(createWindowRequest(confine))
                out.write(grabPointerRequest(X11Ids.RootWindow, confineTo = confine))
                out.write(createWindowRequest(offscreen, x = 200, y = 200, width = 10, height = 10))
                out.write(mapWindowRequest(offscreen))
                out.write(grabPointerRequest(X11Ids.RootWindow, confineTo = offscreen))
                out.flush()

                val unmappedGrab = readReply(socket.getInputStream())
                assertEquals(1, unmappedGrab[0].toInt())
                assertEquals(4, unmappedGrab[1].toInt() and 0xff)
                assertEquals(2, u16le(unmappedGrab, 2))

                val unmappedConfine = readReply(socket.getInputStream())
                assertEquals(1, unmappedConfine[0].toInt())
                assertEquals(4, unmappedConfine[1].toInt() and 0xff)
                assertEquals(4, u16le(unmappedConfine, 2))

                assertMapAndExpose(socket.getInputStream(), offscreen)
                val offscreenConfine = readReply(socket.getInputStream())
                assertEquals(1, offscreenConfine[0].toInt())
                assertEquals(4, offscreenConfine[1].toInt() and 0xff)
                assertEquals(7, u16le(offscreenConfine, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabPointer reports InvalidTime for future or stale timestamps`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                server.input.pointerDown(1, 1)
                val out = socket.getOutputStream()
                out.write(grabPointerRequest(X11Ids.RootWindow, time = 3))
                out.write(grabPointerRequest(X11Ids.RootWindow, time = 2))
                out.write(ungrabPointerRequest(time = 2))
                out.write(grabPointerRequest(X11Ids.RootWindow, time = 1))
                out.write(queryPointerRequest())
                out.flush()

                val futureGrab = readReply(socket.getInputStream())
                assertEquals(1, futureGrab[0].toInt())
                assertEquals(3, futureGrab[1].toInt() and 0xff)
                assertEquals(1, u16le(futureGrab, 2))

                val validGrab = readReply(socket.getInputStream())
                assertEquals(1, validGrab[0].toInt())
                assertEquals(0, validGrab[1].toInt() and 0xff)
                assertEquals(2, u16le(validGrab, 2))

                val staleGrab = readReply(socket.getInputStream())
                assertEquals(1, staleGrab[0].toInt())
                assertEquals(3, staleGrab[1].toInt() and 0xff)
                assertEquals(4, u16le(staleGrab, 2))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabPointer clears active grab and is replyless`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(grabPointerRequest(X11Ids.RootWindow))
                out.flush()

                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(ungrabPointerRequest())
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(1, pointer[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabPointer validates request length and stream recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(ungrabPointerBadLengthRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 27, badValue = 0, sequence = 1)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(2, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabPointer only releases requesting client's active grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(grabPointerRequest(X11Ids.RootWindow))
                    ownerOut.flush()
                    assertEquals(1, readReply(owner.getInputStream())[0].toInt())
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                    val otherOut = other.getOutputStream()
                    otherOut.write(ungrabPointerRequest())
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()
                    val pointer = readReply(other.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(2, u16le(pointer, 2))
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeActivePointerGrab is replyless and updates active grab parameters by timestamp`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val cursor = PixmapId + 90
                server.input.pointerDown(1, 1)
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                out.write(grabPointerRequest(X11Ids.RootWindow, time = 2, eventMask = 0x0004))
                out.flush()

                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertEquals(3, u16le(grab, 2))

                out.write(changeActivePointerGrabRequest(cursor = cursor, time = 1, eventMask = 0x0040))
                out.write(queryPointerRequest())
                out.flush()

                val oldTimePointer = readReply(socket.getInputStream())
                assertEquals(1, oldTimePointer[0].toInt())
                assertEquals(5, u16le(oldTimePointer, 2))
                val unchangedJson = httpGet(server.localPort, "/state.json")
                assertContains(unchangedJson, """"inputGrabs":[{"kind":"pointer","window":"0x${X11Ids.RootWindow.toString(16)}","ownerEvents":false,"eventMask":"0x4"""")
                assertContains(unchangedJson, """"cursor":null,"time":2""")

                out.write(changeActivePointerGrabRequest(cursor = cursor, time = 2, eventMask = 0x0040))
                out.write(queryPointerRequest())
                out.flush()

                val changedPointer = readReply(socket.getInputStream())
                assertEquals(1, changedPointer[0].toInt())
                assertEquals(7, u16le(changedPointer, 2))
                val changedJson = httpGet(server.localPort, "/state.json")
                assertContains(changedJson, """"inputGrabs":[{"kind":"pointer","window":"0x${X11Ids.RootWindow.toString(16)}","ownerEvents":false,"eventMask":"0x40"""")
                assertContains(changedJson, """"cursor":"0x${cursor.toString(16)}","time":2""")

                out.write(changeActivePointerGrabRequest(cursor = 0, time = 3, eventMask = 0x0004))
                out.write(queryPointerRequest())
                out.flush()

                val futureTimePointer = readReply(socket.getInputStream())
                assertEquals(1, futureTimePointer[0].toInt())
                assertEquals(9, u16le(futureTimePointer, 2))
                val futureIgnoredJson = httpGet(server.localPort, "/state.json")
                assertContains(futureIgnoredJson, """"eventMask":"0x40"""")
                assertContains(futureIgnoredJson, """"cursor":"0x${cursor.toString(16)}","time":2""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeActivePointerGrab only changes requesting client's active grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val cursor = PixmapId + 91
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                    ownerOut.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                    ownerOut.write(grabPointerRequest(X11Ids.RootWindow, time = 1, eventMask = 0x0004))
                    ownerOut.flush()
                    assertEquals(3, u16le(readReply(owner.getInputStream()), 2))

                    val otherOut = other.getOutputStream()
                    otherOut.write(changeActivePointerGrabRequest(cursor = cursor, time = 1, eventMask = 0x0040))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    val pointer = readReply(other.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(2, u16le(pointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"eventMask":"0x4"""")
                    assertContains(stateJson, """"cursor":null""")
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeActivePointerGrab validates cursor event mask and length with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(changeActivePointerGrabRequest(eventMask = 0x8000))
                out.write(changeActivePointerGrabRequest(cursor = PixmapId + 92, eventMask = 0x0004))
                out.write(changeActivePointerGrabBadLengthRequest())
                out.write(grabPointerOversizedRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 30, badValue = 0x8000, sequence = 1)
                assertError(socket.getInputStream(), error = 6, opcode = 30, badValue = PixmapId + 92, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 30, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 26, badValue = 0, sequence = 4)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabButton is replyless and records passive button grab state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabButtonRequest(WindowId, ownerEvents = 1, button = 0, modifiers = 0x8000))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"passiveButtonGrabs":[{"window":"0x${WindowId.toString(16)}","ownerEvents":true,"eventMask":"0xc","pointerMode":0,"keyboardMode":0,"confineTo":null,"cursor":null,"button":0,"buttonName":"AnyButton","modifiers":32768,"modifiersName":"AnyModifier","releasedCombinations":[]}]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton activates pointer grab and routes button events to grab owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabButtonRequest(WindowId, eventMask = XEventMasks.ButtonPress or XEventMasks.ButtonRelease))
                out.write(mapWindowRequest(WindowId))
                out.flush()

                assertMapAndExpose(input, WindowId)

                val down = server.input.pointerDown(10, 10, button = 1)
                assertEquals(1, down.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 4, detail = 1)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer","window":"0x${WindowId.toString(16)}","ownerEvents":false,"eventMask":"0xc"""")

                val up = server.input.pointerUp(10, 10, button = 1)
                assertEquals(1, up.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 5, detail = 1)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton does not activate while another logical button is down`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    owner.soTimeout = 250
                    observer.soTimeout = 2_000
                    setup(owner)
                    setup(observer)
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabButtonRequest(WindowId, button = 2))
                    ownerOut.write(mapWindowRequest(WindowId))
                    ownerOut.flush()
                    assertMapAndExpose(owner.getInputStream(), WindowId)

                    val observerInput = observer.getInputStream()
                    val observerOut = observer.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(WindowId, XEventMasks.ButtonPress or XEventMasks.ButtonRelease))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(1, readReply(observerInput)[0].toInt())

                    assertEquals(1, server.input.pointerDown(10, 10, button = 1).deliveredEvents)
                    assertButtonEvent(observerInput.readExactly(32), type = 4, detail = 1)
                    assertEquals(1, server.input.pointerDown(10, 10, button = 2).deliveredEvents)
                    assertButtonEvent(observerInput.readExactly(32), type = 4, detail = 2)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                    assertFailsWith<SocketTimeoutException> {
                        owner.getInputStream().readExactly(32)
                    }

                    assertEquals(1, server.input.pointerUp(10, 10, button = 2).deliveredEvents)
                    assertButtonEvent(observerInput.readExactly(32), type = 5, detail = 2)
                    assertEquals(1, server.input.pointerUp(10, 10, button = 1).deliveredEvents)
                    assertButtonEvent(observerInput.readExactly(32), type = 5, detail = 1)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton matching prefers ancestor grabs over descendant grabs`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ancestorOwner ->
                Socket("127.0.0.1", server.localPort).use { descendantOwner ->
                    ancestorOwner.soTimeout = 2_000
                    descendantOwner.soTimeout = 250
                    setup(ancestorOwner)
                    setup(descendantOwner)

                    val descendantOut = descendantOwner.getOutputStream()
                    descendantOut.write(createWindowRequest(WindowId))
                    descendantOut.write(grabButtonRequest(WindowId))
                    descendantOut.write(mapWindowRequest(WindowId))
                    descendantOut.flush()
                    assertMapAndExpose(descendantOwner.getInputStream(), WindowId)

                    val ancestorInput = ancestorOwner.getInputStream()
                    val ancestorOut = ancestorOwner.getOutputStream()
                    ancestorOut.write(grabButtonRequest(X11Ids.RootWindow))
                    ancestorOut.write(queryPointerRequest())
                    ancestorOut.flush()
                    assertEquals(1, readReply(ancestorInput)[0].toInt())

                    assertEquals(1, server.input.pointerDown(10, 10, button = 1).deliveredEvents)
                    assertButtonEvent(ancestorInput.readExactly(32), type = 4, detail = 1)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer","window":"0x${X11Ids.RootWindow.toString(16)}"""")
                    assertFailsWith<SocketTimeoutException> {
                        descendantOwner.getInputStream().readExactly(32)
                    }

                    assertEquals(1, server.input.pointerUp(10, 10, button = 1).deliveredEvents)
                    assertButtonEvent(ancestorInput.readExactly(32), type = 5, detail = 1)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton owner events use normal owner selection before grab window fallback`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, eventMask = XEventMasks.ButtonPress or XEventMasks.ButtonRelease))
                out.write(grabButtonRequest(WindowId, ownerEvents = 1, eventMask = 0))
                out.write(mapWindowRequest(WindowId))
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertEquals(1, server.input.pointerDown(10, 10, button = 1).deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 4, detail = 1)
                assertEquals(1, server.input.pointerUp(10, 10, button = 1).deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 5, detail = 1)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton owner events ignore owner ancestor selection when child selects first`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    owner.soTimeout = 250
                    observer.soTimeout = 250
                    setup(owner)
                    setup(observer)
                    val child = WindowId + 1
                    val ownerInput = owner.getInputStream()
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId, width = 80, height = 60, eventMask = XEventMasks.ButtonPress))
                    ownerOut.write(createWindowRequest(child, parent = WindowId, x = 10, y = 10, width = 20, height = 20))
                    ownerOut.write(grabButtonRequest(WindowId, ownerEvents = 1, eventMask = 0))
                    ownerOut.write(mapWindowRequest(WindowId))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.flush()
                    assertMapAndExpose(ownerInput, WindowId)
                    assertMapAndExpose(ownerInput, child)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(child, XEventMasks.ButtonPress))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(1, readReply(observer.getInputStream())[0].toInt())

                    assertEquals(0, server.input.pointerDown(15, 15, button = 1).deliveredEvents)
                    assertFailsWith<SocketTimeoutException> {
                        ownerInput.readExactly(32)
                    }
                    assertFailsWith<SocketTimeoutException> {
                        observer.getInputStream().readExactly(32)
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton active grab routes pointer motion to grab owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    owner.soTimeout = 2_000
                    observer.soTimeout = 250
                    setup(owner)
                    setup(observer)
                    val ownerInput = owner.getInputStream()
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(
                        grabButtonRequest(
                            WindowId,
                            eventMask = XEventMasks.ButtonPress or XEventMasks.ButtonRelease or XEventMasks.PointerMotion,
                        ),
                    )
                    ownerOut.write(mapWindowRequest(WindowId))
                    ownerOut.flush()
                    assertMapAndExpose(ownerInput, WindowId)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(WindowId, XEventMasks.PointerMotion))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(1, readReply(observer.getInputStream())[0].toInt())

                    assertEquals(1, server.input.pointerDown(10, 10, button = 1).deliveredEvents)
                    assertButtonEvent(ownerInput.readExactly(32), type = 4, detail = 1)

                    ownerOut.write(warpPointerRequest(destinationX = 1, destinationY = 1))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    val motion = ownerInput.readExactly(32)
                    assertEquals(6, motion[0].toInt() and 0xff)
                    assertEquals(WindowId, u32le(motion, 12))
                    assertEquals(11, u16le(motion, 20))
                    assertEquals(11, u16le(motion, 22))
                    assertEquals(11, u16le(motion, 24))
                    assertEquals(11, u16le(motion, 26))
                    assertEquals(1 shl 8, u16le(motion, 28))
                    assertEquals(1, readReply(ownerInput)[0].toInt())
                    assertFailsWith<SocketTimeoutException> {
                        observer.getInputStream().readExactly(32)
                    }

                    assertEquals(1, server.input.pointerUp(11, 11, button = 1).deliveredEvents)
                    assertButtonEvent(ownerInput.readExactly(32), type = 5, detail = 1)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton does not activate with nonviewable confine window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val confine = WindowId + 1
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, eventMask = XEventMasks.ButtonPress or XEventMasks.ButtonRelease))
                out.write(createWindowRequest(confine))
                out.write(grabButtonRequest(WindowId, confineTo = confine))
                out.write(mapWindowRequest(WindowId))
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertEquals(1, server.input.pointerDown(10, 10, button = 1).deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 4, detail = 1)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                assertEquals(1, server.input.pointerUp(10, 10, button = 1).deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 5, detail = 1)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabButton invalid ancestor grab blocks descendant activation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ancestorOwner ->
                Socket("127.0.0.1", server.localPort).use { descendantOwner ->
                    Socket("127.0.0.1", server.localPort).use { observer ->
                        ancestorOwner.soTimeout = 250
                        descendantOwner.soTimeout = 250
                        observer.soTimeout = 2_000
                        setup(ancestorOwner)
                        setup(descendantOwner)
                        setup(observer)

                        val confine = WindowId + 1
                        val ancestorOut = ancestorOwner.getOutputStream()
                        ancestorOut.write(createWindowRequest(confine))
                        ancestorOut.write(grabButtonRequest(X11Ids.RootWindow, confineTo = confine))
                        ancestorOut.write(queryPointerRequest())
                        ancestorOut.flush()
                        assertEquals(1, readReply(ancestorOwner.getInputStream())[0].toInt())

                        val descendantOut = descendantOwner.getOutputStream()
                        descendantOut.write(createWindowRequest(WindowId))
                        descendantOut.write(grabButtonRequest(WindowId))
                        descendantOut.write(mapWindowRequest(WindowId))
                        descendantOut.flush()
                        assertMapAndExpose(descendantOwner.getInputStream(), WindowId)

                        val observerInput = observer.getInputStream()
                        val observerOut = observer.getOutputStream()
                        observerOut.write(changeWindowEventMaskRequest(WindowId, XEventMasks.ButtonPress or XEventMasks.ButtonRelease))
                        observerOut.write(queryPointerRequest())
                        observerOut.flush()
                        assertEquals(1, readReply(observerInput)[0].toInt())

                        assertEquals(1, server.input.pointerDown(10, 10, button = 1).deliveredEvents)
                        assertButtonEvent(observerInput.readExactly(32), type = 4, detail = 1)
                        assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                        assertFailsWith<SocketTimeoutException> {
                            ancestorOwner.getInputStream().readExactly(32)
                        }
                        assertFailsWith<SocketTimeoutException> {
                            descendantOwner.getInputStream().readExactly(32)
                        }

                        assertEquals(1, server.input.pointerUp(10, 10, button = 1).deliveredEvents)
                        assertButtonEvent(observerInput.readExactly(32), type = 5, detail = 1)
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabButton releases matching passive grabs and preserves stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabButtonRequest(WindowId, button = 1, modifiers = 4))
                out.write(grabButtonRequest(WindowId, button = 2, modifiers = 4))
                out.write(grabButtonRequest(WindowId, button = 6, modifiers = 1))
                out.write(ungrabButtonRequest(WindowId, button = 0, modifiers = 4))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(6, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"button":6,"buttonName":"6","modifiers":1""")
                assertFalse(stateJson.contains(""""button":1,"buttonName":"1","modifiers":4"""))
                assertFalse(stateJson.contains(""""button":2,"buttonName":"2","modifiers":4"""))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabButton releases exact combinations from wildcard passive button grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabButtonRequest(WindowId, button = 0, modifiers = 0x8000))
                    ownerOut.write(ungrabButtonRequest(WindowId, button = 1, modifiers = 0))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    val ownerPointer = readReply(owner.getInputStream())
                    assertEquals(1, ownerPointer[0].toInt())
                    assertEquals(4, u16le(ownerPointer, 2))
                    val partiallyReleasedJson = httpGet(server.localPort, "/state.json")
                    assertContains(partiallyReleasedJson, """"button":0,"buttonName":"AnyButton","modifiers":32768""")
                    assertContains(partiallyReleasedJson, """"releasedCombinations":[{"button":1,"buttonName":"1","modifiers":0,"modifiersName":"0x0"}]""")

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabButtonRequest(WindowId, button = 1, modifiers = 0))
                    otherOut.write(queryPointerRequest())
                    otherOut.write(grabButtonRequest(WindowId, button = 2, modifiers = 0))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    val otherPointer = readReply(other.getInputStream())
                    assertEquals(1, otherPointer[0].toInt())
                    assertEquals(2, u16le(otherPointer, 2))
                    assertError(other.getInputStream(), error = 10, opcode = 28, badValue = 0, sequence = 3)
                    val recoveredPointer = readReply(other.getInputStream())
                    assertEquals(1, recoveredPointer[0].toInt())
                    assertEquals(4, u16le(recoveredPointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"button":0,"buttonName":"AnyButton","modifiers":32768""")
                    assertContains(stateJson, """"releasedCombinations":[{"button":1,"buttonName":"1","modifiers":0,"modifiersName":"0x0"}]""")
                    assertContains(stateJson, """},{"window":"0x${WindowId.toString(16)}","ownerEvents":false,"eventMask":"0xc","pointerMode":0,"keyboardMode":0,"confineTo":null,"cursor":null,"button":1,"buttonName":"1","modifiers":0""")
                    assertFalse(stateJson.contains(""""button":2,"buttonName":"2","modifiers":0"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabButton preserves wildcard modifier release patterns in state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabButtonRequest(WindowId, button = 0, modifiers = 0x8000))
                    ownerOut.write(ungrabButtonRequest(WindowId, button = 1, modifiers = 0x8000))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    val pointer = readReply(owner.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(4, u16le(pointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"button":0,"buttonName":"AnyButton","modifiers":32768""")
                    assertContains(stateJson, """"releasedCombinations":[{"button":1,"buttonName":"1","modifiers":32768,"modifiersName":"AnyModifier"}]""")
                    assertEquals(1, """"button":1,"buttonName":"1","modifiers":32768,"modifiersName":"AnyModifier"""".toRegex().findAll(stateJson).count())

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabButtonRequest(WindowId, button = 1, modifiers = 5))
                    otherOut.write(queryPointerRequest())
                    otherOut.write(grabButtonRequest(WindowId, button = 2, modifiers = 5))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    val releasedPointer = readReply(other.getInputStream())
                    assertEquals(1, releasedPointer[0].toInt())
                    assertEquals(2, u16le(releasedPointer, 2))
                    assertError(other.getInputStream(), error = 10, opcode = 28, badValue = 0, sequence = 3)
                    val recoveredPointer = readReply(other.getInputStream())
                    assertEquals(1, recoveredPointer[0].toInt())
                    assertEquals(4, u16le(recoveredPointer, 2))
                    val updatedJson = httpGet(server.localPort, "/state.json")
                    assertContains(updatedJson, """},{"window":"0x${WindowId.toString(16)}","ownerEvents":false,"eventMask":"0xc","pointerMode":0,"keyboardMode":0,"confineTo":null,"cursor":null,"button":1,"buttonName":"1","modifiers":5""")
                    assertFalse(updatedJson.contains(""""button":2,"buttonName":"2","modifiers":5"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabButton honors AnyButton release patterns during conflict checks`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabButtonRequest(WindowId, button = 0, modifiers = 0x8000))
                    ownerOut.write(ungrabButtonRequest(WindowId, button = 0, modifiers = 2))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    val ownerPointer = readReply(owner.getInputStream())
                    assertEquals(1, ownerPointer[0].toInt())
                    assertEquals(4, u16le(ownerPointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"releasedCombinations":[{"button":0,"buttonName":"AnyButton","modifiers":2,"modifiersName":"0x2"}]""")

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabButtonRequest(WindowId, button = 1, modifiers = 2))
                    otherOut.write(queryPointerRequest())
                    otherOut.write(grabButtonRequest(WindowId, button = 1, modifiers = 3))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    val releasedPointer = readReply(other.getInputStream())
                    assertEquals(1, releasedPointer[0].toInt())
                    assertEquals(2, u16le(releasedPointer, 2))
                    assertError(other.getInputStream(), error = 10, opcode = 28, badValue = 0, sequence = 3)
                    val recoveredPointer = readReply(other.getInputStream())
                    assertEquals(1, recoveredPointer[0].toInt())
                    assertEquals(4, u16le(recoveredPointer, 2))
                    val updatedJson = httpGet(server.localPort, "/state.json")
                    assertContains(updatedJson, """},{"window":"0x${WindowId.toString(16)}","ownerEvents":false,"eventMask":"0xc","pointerMode":0,"keyboardMode":0,"confineTo":null,"cursor":null,"button":1,"buttonName":"1","modifiers":2""")
                    assertFalse(updatedJson.contains(""""button":1,"buttonName":"1","modifiers":3"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabButton reports BadAccess for conflicting passive grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabButtonRequest(WindowId, button = 0, modifiers = 0x8000))
                    ownerOut.flush()
                    assertContains(httpGet(server.localPort, "/state.json"), """"passiveButtonGrabs":[{""")

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabButtonRequest(WindowId, button = 1, modifiers = 0))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    assertError(other.getInputStream(), error = 10, opcode = 28, badValue = 0, sequence = 1)
                    val pointer = readReply(other.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(2, u16le(pointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"button":0,"buttonName":"AnyButton","modifiers":32768""")
                    assertFalse(stateJson.contains(""""button":1,"buttonName":"1","modifiers":0"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabButton and UngrabButton validate values and request length`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabButtonRequest(WindowId, ownerEvents = 2))
                out.write(grabButtonRequest(WindowId, pointerMode = 2))
                out.write(grabButtonRequest(WindowId, modifiers = 0x0100))
                out.write(grabButtonBadLengthRequest())
                out.write(ungrabButtonRequest(WindowId, modifiers = 0x0100))
                out.write(ungrabButtonBadLengthRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 28, badValue = 2, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 28, badValue = 2, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 28, badValue = 0x0100, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 28, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 29, badValue = 0x0100, sequence = 6)
                assertError(socket.getInputStream(), error = 16, opcode = 29, badValue = 0, sequence = 7)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(8, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"passiveButtonGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabKey is replyless and records passive key grab state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyRequest(WindowId, ownerEvents = 1, key = 0, modifiers = 0x8000, pointerMode = 1))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"passiveKeyGrabs":[{"window":"0x${WindowId.toString(16)}","ownerEvents":true,"key":0,"keyName":"AnyKey","modifiers":32768,"modifiersName":"AnyModifier","pointerMode":1,"keyboardMode":0,"releasedCombinations":[]}]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `key input delivers selected key events to focus window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, eventMask = XEventMasks.KeyPress or XEventMasks.KeyRelease))
                out.write(mapWindowRequest(WindowId))
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertEquals(1, server.input.keyDown(10, modifiers = 4).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = WindowId, state = 4)
                assertEquals(1, server.input.keyUp(10, modifiers = 4).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 3, detail = 10, eventWindow = WindowId, state = 4)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `key input resolves focus ancestor to pointer descendant`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val child = WindowId + 1
                out.write(createWindowRequest(parent, width = 80, height = 60))
                out.write(createWindowRequest(child, parent = parent, width = 20, height = 20, eventMask = XEventMasks.KeyPress))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(setInputFocusRequest(parent, revertTo = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, child)
                assertEquals(1, readReply(input)[0].toInt())
                assertEquals(1, server.input.keyDown(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = child)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `key input propagation stops at first selected window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val child = WindowId + 1
                out.write(createWindowRequest(parent, width = 80, height = 60, eventMask = XEventMasks.KeyPress))
                out.write(createWindowRequest(child, parent = parent, width = 20, height = 20, eventMask = XEventMasks.KeyPress))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(setInputFocusRequest(parent, revertTo = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, child)
                assertEquals(1, readReply(input)[0].toInt())
                assertEquals(1, server.input.keyDown(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = child)
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
    fun `key input does not propagate above real focus ancestor`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val focus = WindowId
                val child = WindowId + 1
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.KeyPress))
                out.write(createWindowRequest(focus, width = 80, height = 60))
                out.write(createWindowRequest(child, parent = focus, width = 20, height = 20))
                out.write(mapWindowRequest(focus))
                out.write(mapWindowRequest(child))
                out.write(setInputFocusRequest(focus, revertTo = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, focus)
                assertMapAndExpose(input, child)
                assertEquals(1, readReply(input)[0].toInt())
                assertEquals(0, server.input.keyDown(10).deliveredEvents)
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
    fun `key input outside focus does not propagate to root selection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val focus = WindowId
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.KeyPress))
                out.write(createWindowRequest(focus, x = 60, y = 60, width = 20, height = 20))
                out.write(mapWindowRequest(focus))
                out.write(setInputFocusRequest(focus, revertTo = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, focus)
                assertEquals(1, readReply(input)[0].toInt())
                assertEquals(0, server.input.keyDown(10).deliveredEvents)
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
    fun `QueryKeymap reports currently pressed keycodes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                out.write(queryKeymapRequest())
                out.flush()
                assertKeymap(readReply(input), sequence = 1)

                server.input.keyDown(10)
                server.input.keyDown(63)
                assertContains(httpGet(server.localPort, "/state.json"), """"keycodesDown":[10,63]""")
                out.write(queryKeymapRequest())
                out.flush()
                assertKeymap(readReply(input), sequence = 2, 10, 63)

                server.input.keyUp(10)
                out.write(queryKeymapRequest())
                out.flush()
                assertKeymap(readReply(input), sequence = 3, 63)

                server.input.keyUp(63)
                out.write(queryKeymapRequest())
                out.flush()
                assertKeymap(readReply(input), sequence = 4)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `active GrabKeyboard receives key events when input focus is None`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val child = WindowId + 1
                out.write(createWindowRequest(parent, width = 80, height = 60))
                out.write(createWindowRequest(child, parent = parent, width = 20, height = 20))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(grabKeyboardRequest(parent))
                out.write(setInputFocusRequest(0, revertTo = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, child)
                val grab = readReply(input)
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt())
                assertEquals(1, readReply(input)[0].toInt())
                assertEquals(1, server.input.keyDown(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = parent, childWindow = child)
                assertEquals(1, server.input.keyUp(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 3, detail = 10, eventWindow = parent, childWindow = child)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `active GrabKeyboard uses None child when pointer is outside grab window subtree`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val child = WindowId + 1
                out.write(createWindowRequest(parent, x = 30, y = 30, width = 40, height = 30))
                out.write(createWindowRequest(child, parent = parent, width = 20, height = 20))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(setInputFocusRequest(child, revertTo = 2))
                out.write(grabKeyboardRequest(parent))
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, child)
                val grab = readReply(input)
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt())
                assertEquals(1, server.input.keyDown(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = parent, childWindow = 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabKey activates keyboard grab and routes key events to grab owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    owner.soTimeout = 2_000
                    observer.soTimeout = 250
                    setup(owner)
                    setup(observer)
                    val ownerInput = owner.getInputStream()
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabKeyRequest(WindowId, key = 10))
                    ownerOut.write(mapWindowRequest(WindowId))
                    ownerOut.flush()
                    assertMapAndExpose(ownerInput, WindowId)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(WindowId, XEventMasks.KeyPress or XEventMasks.KeyRelease))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(1, readReply(observer.getInputStream())[0].toInt())

                    assertEquals(1, server.input.keyDown(10).deliveredEvents)
                    assertKeyEvent(ownerInput.readExactly(32), type = 2, detail = 10, eventWindow = WindowId)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard","window":"0x${WindowId.toString(16)}"""")
                    assertFailsWith<SocketTimeoutException> {
                        observer.getInputStream().readExactly(32)
                    }

                    assertEquals(1, server.input.keyUp(10).deliveredEvents)
                    assertKeyEvent(ownerInput.readExactly(32), type = 3, detail = 10, eventWindow = WindowId)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                    assertFailsWith<SocketTimeoutException> {
                        observer.getInputStream().readExactly(32)
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabKey releases active grab when focus becomes None before key release`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyRequest(WindowId, key = 10))
                out.write(mapWindowRequest(WindowId))
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertEquals(1, server.input.keyDown(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = WindowId)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard","window":"0x${WindowId.toString(16)}"""")

                out.write(setInputFocusRequest(0, revertTo = 2))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(1, readReply(input)[0].toInt())

                assertEquals(1, server.input.keyUp(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 3, detail = 10, eventWindow = WindowId)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabKey owner events use normal owner selection before grab window fallback`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, eventMask = XEventMasks.KeyPress or XEventMasks.KeyRelease))
                out.write(grabKeyRequest(X11Ids.RootWindow, ownerEvents = 1, key = 10))
                out.write(mapWindowRequest(WindowId))
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertEquals(1, server.input.keyDown(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = WindowId)
                assertEquals(1, server.input.keyUp(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 3, detail = 10, eventWindow = WindowId)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabKey owner events fall back to grab window when owner has no normal selection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    owner.soTimeout = 2_000
                    observer.soTimeout = 250
                    setup(owner)
                    setup(observer)
                    val parent = WindowId
                    val child = WindowId + 1
                    val ownerInput = owner.getInputStream()
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(parent, width = 80, height = 60))
                    ownerOut.write(createWindowRequest(child, parent = parent, width = 20, height = 20))
                    ownerOut.write(grabKeyRequest(parent, ownerEvents = 1, key = 10))
                    ownerOut.write(mapWindowRequest(parent))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.flush()
                    assertMapAndExpose(ownerInput, parent)
                    assertMapAndExpose(ownerInput, child)

                    val observerOut = observer.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(child, XEventMasks.KeyPress or XEventMasks.KeyRelease))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(1, readReply(observer.getInputStream())[0].toInt())

                    assertEquals(1, server.input.keyDown(10).deliveredEvents)
                    assertKeyEvent(ownerInput.readExactly(32), type = 2, detail = 10, eventWindow = parent, childWindow = child)
                    assertFailsWith<SocketTimeoutException> {
                        observer.getInputStream().readExactly(32)
                    }

                    assertEquals(1, server.input.keyUp(10).deliveredEvents)
                    assertKeyEvent(ownerInput.readExactly(32), type = 3, detail = 10, eventWindow = parent, childWindow = child)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                    assertFailsWith<SocketTimeoutException> {
                        observer.getInputStream().readExactly(32)
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabKey owner events ignore owner selections above real focus`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val child = WindowId + 1
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.KeyPress))
                out.write(createWindowRequest(parent, width = 80, height = 60))
                out.write(createWindowRequest(child, parent = parent, width = 20, height = 20))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(grabKeyRequest(parent, ownerEvents = 1, key = 10))
                out.write(setInputFocusRequest(parent, revertTo = 2))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, child)
                assertEquals(1, readReply(input)[0].toInt())
                assertEquals(1, server.input.keyDown(10).deliveredEvents)
                assertKeyEvent(input.readExactly(32), type = 2, detail = 10, eventWindow = parent, childWindow = child)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `passive GrabKey skips released wildcard combination during activation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { observer ->
                    owner.soTimeout = 250
                    observer.soTimeout = 2_000
                    setup(owner)
                    setup(observer)
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabKeyRequest(WindowId, key = 0, modifiers = 0x8000))
                    ownerOut.write(ungrabKeyRequest(WindowId, key = 10, modifiers = 0))
                    ownerOut.write(mapWindowRequest(WindowId))
                    ownerOut.flush()
                    assertMapAndExpose(owner.getInputStream(), WindowId)

                    val observerInput = observer.getInputStream()
                    val observerOut = observer.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(WindowId, XEventMasks.KeyPress or XEventMasks.KeyRelease))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(1, readReply(observerInput)[0].toInt())

                    assertEquals(1, server.input.keyDown(10).deliveredEvents)
                    assertKeyEvent(observerInput.readExactly(32), type = 2, detail = 10, eventWindow = WindowId)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                    assertFailsWith<SocketTimeoutException> {
                        owner.getInputStream().readExactly(32)
                    }

                    assertEquals(1, server.input.keyUp(10).deliveredEvents)
                    assertKeyEvent(observerInput.readExactly(32), type = 3, detail = 10, eventWindow = WindowId)

                    owner.soTimeout = 2_000
                    ownerOut.write(ungrabKeyRequest(WindowId, key = 11, modifiers = 0x8000))
                    ownerOut.write(ungrabKeyRequest(WindowId, key = 0, modifiers = 2))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(1, readReply(owner.getInputStream())[0].toInt())
                    owner.soTimeout = 250

                    assertEquals(1, server.input.keyDown(11, modifiers = 5).deliveredEvents)
                    assertKeyEvent(observerInput.readExactly(32), type = 2, detail = 11, eventWindow = WindowId, state = 5)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                    assertFailsWith<SocketTimeoutException> {
                        owner.getInputStream().readExactly(32)
                    }
                    assertEquals(1, server.input.keyUp(11, modifiers = 5).deliveredEvents)
                    assertKeyEvent(observerInput.readExactly(32), type = 3, detail = 11, eventWindow = WindowId, state = 5)

                    assertEquals(1, server.input.keyDown(12, modifiers = 2).deliveredEvents)
                    assertKeyEvent(observerInput.readExactly(32), type = 2, detail = 12, eventWindow = WindowId, state = 2)
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
                    assertFailsWith<SocketTimeoutException> {
                        owner.getInputStream().readExactly(32)
                    }
                    assertEquals(1, server.input.keyUp(12, modifiers = 2).deliveredEvents)
                    assertKeyEvent(observerInput.readExactly(32), type = 3, detail = 12, eventWindow = WindowId, state = 2)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabKey releases matching passive key grabs and preserves stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyRequest(WindowId, key = 10, modifiers = 4))
                out.write(grabKeyRequest(WindowId, key = 11, modifiers = 4))
                out.write(grabKeyRequest(WindowId, key = 12, modifiers = 1))
                out.write(ungrabKeyRequest(WindowId, key = 0, modifiers = 4))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(6, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"key":12,"keyName":"12","modifiers":1""")
                assertFalse(stateJson.contains(""""key":10,"keyName":"10","modifiers":4"""))
                assertFalse(stateJson.contains(""""key":11,"keyName":"11","modifiers":4"""))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabKey releases exact combinations from wildcard passive key grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabKeyRequest(WindowId, key = 0, modifiers = 0x8000))
                    ownerOut.write(ungrabKeyRequest(WindowId, key = 10, modifiers = 0))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    val ownerPointer = readReply(owner.getInputStream())
                    assertEquals(1, ownerPointer[0].toInt())
                    assertEquals(4, u16le(ownerPointer, 2))
                    val partiallyReleasedJson = httpGet(server.localPort, "/state.json")
                    assertContains(partiallyReleasedJson, """"key":0,"keyName":"AnyKey","modifiers":32768""")
                    assertContains(partiallyReleasedJson, """"releasedCombinations":[{"key":10,"keyName":"10","modifiers":0,"modifiersName":"0x0"}]""")

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabKeyRequest(WindowId, key = 10, modifiers = 0))
                    otherOut.write(queryPointerRequest())
                    otherOut.write(grabKeyRequest(WindowId, key = 11, modifiers = 0))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    val otherPointer = readReply(other.getInputStream())
                    assertEquals(1, otherPointer[0].toInt())
                    assertEquals(2, u16le(otherPointer, 2))
                    assertError(other.getInputStream(), error = 10, opcode = 33, badValue = 0, sequence = 3)
                    val recoveredPointer = readReply(other.getInputStream())
                    assertEquals(1, recoveredPointer[0].toInt())
                    assertEquals(4, u16le(recoveredPointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"key":0,"keyName":"AnyKey","modifiers":32768""")
                    assertContains(stateJson, """"releasedCombinations":[{"key":10,"keyName":"10","modifiers":0,"modifiersName":"0x0"}]""")
                    assertContains(stateJson, """},{"window":"0x${WindowId.toString(16)}","ownerEvents":false,"key":10,"keyName":"10","modifiers":0""")
                    assertFalse(stateJson.contains(""""key":11,"keyName":"11","modifiers":0"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabKey preserves wildcard release patterns in state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabKeyRequest(WindowId, key = 0, modifiers = 0x8000))
                    ownerOut.write(ungrabKeyRequest(WindowId, key = 10, modifiers = 0x8000))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    val pointer = readReply(owner.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(4, u16le(pointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"key":0,"keyName":"AnyKey","modifiers":32768""")
                    assertContains(stateJson, """"releasedCombinations":[{"key":10,"keyName":"10","modifiers":32768,"modifiersName":"AnyModifier"}]""")
                    assertEquals(1, """"key":10,"keyName":"10","modifiers":32768,"modifiersName":"AnyModifier"""".toRegex().findAll(stateJson).count())

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabKeyRequest(WindowId, key = 10, modifiers = 5))
                    otherOut.write(queryPointerRequest())
                    otherOut.write(grabKeyRequest(WindowId, key = 11, modifiers = 5))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    val releasedPointer = readReply(other.getInputStream())
                    assertEquals(1, releasedPointer[0].toInt())
                    assertEquals(2, u16le(releasedPointer, 2))
                    assertError(other.getInputStream(), error = 10, opcode = 33, badValue = 0, sequence = 3)
                    val recoveredPointer = readReply(other.getInputStream())
                    assertEquals(1, recoveredPointer[0].toInt())
                    assertEquals(4, u16le(recoveredPointer, 2))
                    val updatedJson = httpGet(server.localPort, "/state.json")
                    assertContains(updatedJson, """},{"window":"0x${WindowId.toString(16)}","ownerEvents":false,"key":10,"keyName":"10","modifiers":5""")
                    assertFalse(updatedJson.contains(""""key":11,"keyName":"11","modifiers":5"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabKey honors AnyKey release patterns during conflict checks`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabKeyRequest(WindowId, key = 0, modifiers = 0x8000))
                    ownerOut.write(ungrabKeyRequest(WindowId, key = 0, modifiers = 2))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    val ownerPointer = readReply(owner.getInputStream())
                    assertEquals(1, ownerPointer[0].toInt())
                    assertEquals(4, u16le(ownerPointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"releasedCombinations":[{"key":0,"keyName":"AnyKey","modifiers":2,"modifiersName":"0x2"}]""")

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabKeyRequest(WindowId, key = 10, modifiers = 2))
                    otherOut.write(queryPointerRequest())
                    otherOut.write(grabKeyRequest(WindowId, key = 10, modifiers = 3))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    val releasedPointer = readReply(other.getInputStream())
                    assertEquals(1, releasedPointer[0].toInt())
                    assertEquals(2, u16le(releasedPointer, 2))
                    assertError(other.getInputStream(), error = 10, opcode = 33, badValue = 0, sequence = 3)
                    val recoveredPointer = readReply(other.getInputStream())
                    assertEquals(1, recoveredPointer[0].toInt())
                    assertEquals(4, u16le(recoveredPointer, 2))
                    val updatedJson = httpGet(server.localPort, "/state.json")
                    assertContains(updatedJson, """},{"window":"0x${WindowId.toString(16)}","ownerEvents":false,"key":10,"keyName":"10","modifiers":2""")
                    assertFalse(updatedJson.contains(""""key":10,"keyName":"10","modifiers":3"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabKey same client later grabs replace overlapping passive key combinations`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyRequest(WindowId, key = 10, modifiers = 0))
                out.write(grabKeyRequest(WindowId, ownerEvents = 1, key = 0, modifiers = 0x8000, pointerMode = 1))
                out.write(queryPointerRequest())
                out.flush()

                val wildcardPointer = readReply(socket.getInputStream())
                assertEquals(1, wildcardPointer[0].toInt())
                assertEquals(4, u16le(wildcardPointer, 2))
                val wildcardJson = httpGet(server.localPort, "/state.json")
                assertContains(wildcardJson, """"key":0,"keyName":"AnyKey","modifiers":32768""")
                assertContains(wildcardJson, """"ownerEvents":true""")
                assertFalse(wildcardJson.contains(""""key":10,"keyName":"10","modifiers":0"""))

                out.write(ungrabKeyRequest(WindowId, key = 0, modifiers = 0x8000))
                out.write(queryPointerRequest())
                out.flush()

                val clearedPointer = readReply(socket.getInputStream())
                assertEquals(1, clearedPointer[0].toInt())
                assertEquals(6, u16le(clearedPointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"passiveKeyGrabs":[]""")

                out.write(grabKeyRequest(WindowId, key = 0, modifiers = 0x8000))
                out.write(grabKeyRequest(WindowId, key = 10, modifiers = 0))
                out.write(queryPointerRequest())
                out.flush()

                val concretePointer = readReply(socket.getInputStream())
                assertEquals(1, concretePointer[0].toInt())
                assertEquals(9, u16le(concretePointer, 2))
                val concreteJson = httpGet(server.localPort, "/state.json")
                assertContains(concreteJson, """"key":0,"keyName":"AnyKey","modifiers":32768""")
                assertContains(concreteJson, """"releasedCombinations":[{"key":10,"keyName":"10","modifiers":0,"modifiersName":"0x0"}]""")
                assertContains(concreteJson, """},{"window":"0x${WindowId.toString(16)}","ownerEvents":false,"key":10,"keyName":"10","modifiers":0""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabKey reports BadAccess for conflicting passive key grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabKeyRequest(WindowId, key = 0, modifiers = 0x8000))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    val ownerPointer = readReply(owner.getInputStream())
                    assertEquals(1, ownerPointer[0].toInt())
                    assertEquals(3, u16le(ownerPointer, 2))
                    assertContains(httpGet(server.localPort, "/state.json"), """"passiveKeyGrabs":[{""")

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabKeyRequest(WindowId, key = 10, modifiers = 0))
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()

                    assertError(other.getInputStream(), error = 10, opcode = 33, badValue = 0, sequence = 1)
                    val pointer = readReply(other.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(2, u16le(pointer, 2))
                    val stateJson = httpGet(server.localPort, "/state.json")
                    assertContains(stateJson, """"key":0,"keyName":"AnyKey","modifiers":32768""")
                    assertFalse(stateJson.contains(""""key":10,"keyName":"10","modifiers":0"""))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabKey and UngrabKey validate values and request length`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyRequest(WindowId, ownerEvents = 2))
                out.write(grabKeyRequest(WindowId, key = 1))
                out.write(grabKeyRequest(WindowId, modifiers = 0x0100))
                out.write(grabKeyRequest(WindowId, pointerMode = 2))
                out.write(grabKeyRequest(WindowId, keyboardMode = 2))
                out.write(grabKeyBadLengthRequest())
                out.write(ungrabKeyRequest(WindowId, key = 1))
                out.write(ungrabKeyBadLengthRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 33, badValue = 2, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 33, badValue = 1, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 33, badValue = 0x0100, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 33, badValue = 2, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 33, badValue = 2, sequence = 6)
                assertError(socket.getInputStream(), error = 16, opcode = 33, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 34, badValue = 1, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 34, badValue = 0, sequence = 9)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(10, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"passiveKeyGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabPointer and GrabKeyboard report AlreadyGrabbed for another client's active grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(grabPointerRequest(X11Ids.RootWindow))
                    ownerOut.flush()
                    assertEquals(0, readReply(owner.getInputStream())[1].toInt() and 0xff)

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabPointerRequest(X11Ids.RootWindow))
                    otherOut.write(ungrabPointerRequest())
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()
                    assertEquals(1, readReply(other.getInputStream())[1].toInt() and 0xff)
                    assertEquals(1, readReply(other.getInputStream())[0].toInt())
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                    ownerOut.write(ungrabPointerRequest())
                    ownerOut.write(grabKeyboardRequest(X11Ids.RootWindow))
                    ownerOut.flush()
                    assertEquals(1, readReply(owner.getInputStream())[0].toInt())

                    otherOut.write(grabKeyboardRequest(X11Ids.RootWindow))
                    otherOut.write(ungrabKeyboardRequest())
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()
                    assertEquals(1, readReply(other.getInputStream())[1].toInt() and 0xff)
                    assertEquals(1, readReply(other.getInputStream())[0].toInt())
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard"""")
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabPointer and UngrabKeyboard ignore timestamps older than grab time`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                server.input.pointerDown(1, 1)
                val out = socket.getOutputStream()
                out.write(grabPointerRequest(X11Ids.RootWindow, time = 2))
                out.flush()
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)

                out.write(ungrabPointerRequest(time = 1))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(1, readReply(socket.getInputStream())[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(ungrabPointerRequest(time = 2))
                out.write(grabKeyboardRequest(X11Ids.RootWindow, time = 2))
                out.flush()
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)

                out.write(ungrabKeyboardRequest(time = 1))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(1, readReply(socket.getInputStream())[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard"""")

                out.write(ungrabKeyboardRequest(time = 2))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(1, readReply(socket.getInputStream())[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroyWindow clears active pointer grab for destroyed grab window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), WindowId)
                assertEquals(1, readReply(socket.getInputStream())[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(destroyWindowRequest(WindowId))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroyWindow delivers StructureNotify and parent SubstructureNotify`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val child = WindowId + 404
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(child, eventMask = XEventMasks.StructureNotify))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(destroyWindowRequest(child))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertDestroyNotify(
                        ownerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = child,
                        window = child,
                    )
                    assertEquals(4, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertDestroyNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        eventWindow = X11Ids.RootWindow,
                        window = child,
                    )
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroyWindow delivers inferiors before destroyed window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId + 405
                val nested = WindowId + 406
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(nested, parent = parent))
                out.write(changeWindowEventMaskRequest(parent, XEventMasks.StructureNotify or XEventMasks.SubstructureNotify))
                out.write(destroyWindowRequest(parent))
                out.write(queryPointerRequest())
                out.flush()

                assertDestroyNotify(
                    socket.getInputStream().readExactly(32),
                    sequence = 4,
                    eventWindow = parent,
                    window = nested,
                )
                assertDestroyNotify(
                    socket.getInputStream().readExactly(32),
                    sequence = 4,
                    eventWindow = parent,
                    window = parent,
                )
                assertEquals(5, u16le(readReply(socket.getInputStream()), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow clears active pointer grab for grab or confine window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val confine = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), WindowId)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(unmapWindowRequest(WindowId))
                out.write(queryPointerRequest())
                out.flush()
                val grabWindowPointer = readReply(socket.getInputStream())
                assertEquals(1, grabWindowPointer[0].toInt())
                assertEquals(5, u16le(grabWindowPointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")

                out.write(createWindowRequest(confine, x = 10, y = 10, width = 20, height = 20))
                out.write(mapWindowRequest(confine))
                out.write(grabPointerRequest(X11Ids.RootWindow, confineTo = confine))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), confine)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(unmapWindowRequest(confine))
                out.write(queryPointerRequest())
                out.flush()
                val confinePointer = readReply(socket.getInputStream())
                assertEquals(1, confinePointer[0].toInt())
                assertEquals(10, u16le(confinePointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow clears active pointer grab when confine window leaves root`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val confine = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(confine, x = 10, y = 10, width = 20, height = 20))
                out.write(mapWindowRequest(confine))
                out.write(grabPointerRequest(X11Ids.RootWindow, confineTo = confine))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), confine)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(changeWindowEventMaskRequest(confine, XEventMasks.StructureNotify))
                out.write(configureWindowRequest(confine, 0x0003, 200, 200))
                out.write(queryPointerRequest())
                out.flush()

                val configure = socket.getInputStream().readExactly(32)
                assertEquals(22, configure[0].toInt() and 0xff)
                assertEquals(5, u16le(configure, 2))
                assertEquals(confine, u32le(configure, 4))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(6, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow keeps active pointer grab when confine window is parent clipped but inside root`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val confine = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent, x = 0, y = 0, width = 10, height = 10))
                out.write(createWindowRequest(confine, parent = parent, x = 30, y = 0, width = 10, height = 10))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(confine))
                out.write(grabPointerRequest(X11Ids.RootWindow, confineTo = confine))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), parent)
                assertMapAndExpose(socket.getInputStream(), confine)
                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(changeWindowEventMaskRequest(parent, XEventMasks.StructureNotify))
                out.write(configureWindowRequest(parent, 0x0004, 11))
                out.write(queryPointerRequest())
                out.flush()

                val configure = socket.getInputStream().readExactly(32)
                assertEquals(22, configure[0].toInt() and 0xff)
                assertEquals(7, u16le(configure, 2))
                assertEquals(parent, u32le(configure, 4))
                val expose = socket.getInputStream().readExactly(32)
                assertEquals(12, expose[0].toInt() and 0xff)
                assertEquals(parent, u32le(expose, 4))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(8, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroyWindow validates request length and window id without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 401
                val out = socket.getOutputStream()
                out.write(request(4, 0, ByteArray(0)))
                out.write(request(4, 0, ByteArray(8)))
                out.write(destroyWindowRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 4, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 4, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 4, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroyWindow on root is a no-op and keeps children queryable`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(destroyWindowRequest(X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(queryPointerRequest())
                out.flush()

                val tree = readReply(socket.getInputStream())
                assertEquals(3, u16le(tree, 2))
                assertEquals(listOf(WindowId), treeChildren(tree))
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
                assertEquals(X11Ids.RootWindow, u32le(pointer, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroySubwindows destroys direct children and descendants but keeps parent`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val first = WindowId + 1
                val nested = WindowId + 2
                val second = WindowId + 3
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(first, parent = parent))
                out.write(createWindowRequest(nested, parent = first))
                out.write(createWindowRequest(second, parent = parent))
                out.write(destroySubwindowsRequest(parent))
                out.write(queryTreeRequest(parent))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertTrue(treeChildren(readReply(socket.getInputStream())).isEmpty())
                val rootChildren = treeChildren(readReply(socket.getInputStream()))
                assertTrue(parent in rootChildren)
                assertFalse(first in rootChildren)
                assertFalse(second in rootChildren)
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, windowJsonId(parent))
                assertFalse(json.contains(windowJsonId(first)))
                assertFalse(json.contains(windowJsonId(nested)))
                assertFalse(json.contains(windowJsonId(second)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroySubwindows delivers SubstructureNotify for direct children in order`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId + 407
                val first = WindowId + 408
                val nested = WindowId + 409
                val second = WindowId + 410
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(first, parent = parent))
                out.write(createWindowRequest(nested, parent = first))
                out.write(createWindowRequest(second, parent = parent))
                out.write(changeWindowEventMaskRequest(parent, XEventMasks.SubstructureNotify))
                out.write(destroySubwindowsRequest(parent))
                out.write(queryPointerRequest())
                out.flush()

                assertDestroyNotify(
                    socket.getInputStream().readExactly(32),
                    sequence = 6,
                    eventWindow = parent,
                    window = first,
                )
                assertDestroyNotify(
                    socket.getInputStream().readExactly(32),
                    sequence = 6,
                    eventWindow = parent,
                    window = second,
                )
                assertEquals(7, u16le(readReply(socket.getInputStream()), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroySubwindows validates request length and parent window without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 400
                val out = socket.getOutputStream()
                out.write(request(5, 0, ByteArray(0)))
                out.write(destroySubwindowsRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 5, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 3, opcode = 5, badValue = missing, sequence = 2)
                val pointer = readReply(socket.getInputStream())
                assertEquals(3, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ReparentWindow validates length windows and ancestry without mutating tree`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val grandchild = WindowId + 2
                val missingWindow = WindowId + 403
                val missingParent = WindowId + 404
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(child, parent = parent))
                out.write(createWindowRequest(grandchild, parent = child))
                out.write(request(7, 0, ByteArray(8)))
                out.write(request(7, 0, ByteArray(16)))
                out.write(reparentWindowRequest(missingWindow, X11Ids.RootWindow, x = 1, y = 2))
                out.write(reparentWindowRequest(child, missingParent, x = 1, y = 2))
                out.write(reparentWindowRequest(X11Ids.RootWindow, parent, x = 1, y = 2))
                out.write(reparentWindowRequest(child, child, x = 1, y = 2))
                out.write(reparentWindowRequest(parent, grandchild, x = 1, y = 2))
                out.write(queryTreeRequest(parent))
                out.write(queryTreeRequest(child))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 7, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 7, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 3, opcode = 7, badValue = missingWindow, sequence = 6)
                assertError(socket.getInputStream(), error = 3, opcode = 7, badValue = missingParent, sequence = 7)
                assertError(socket.getInputStream(), error = 8, opcode = 7, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 8, opcode = 7, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 8, opcode = 7, badValue = 0, sequence = 10)
                val parentTree = readReply(socket.getInputStream())
                assertEquals(11, u16le(parentTree, 2))
                assertEquals(listOf(child), treeChildren(parentTree))
                val childTree = readReply(socket.getInputStream())
                assertEquals(12, u16le(childTree, 2))
                assertEquals(listOf(grandchild), treeChildren(childTree))
                val pointer = readReply(socket.getInputStream())
                assertEquals(13, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ReparentWindow moves window to new parent and updates local coordinates`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val firstParent = WindowId
                val secondParent = WindowId + 1
                val child = WindowId + 2
                val out = socket.getOutputStream()
                out.write(createWindowRequest(firstParent))
                out.write(createWindowRequest(secondParent))
                out.write(createWindowRequest(child, parent = firstParent, x = 3, y = 4))
                out.write(reparentWindowRequest(child, secondParent, x = 7, y = 8))
                out.write(queryTreeRequest(firstParent))
                out.write(queryTreeRequest(secondParent))
                out.flush()

                assertTrue(treeChildren(readReply(socket.getInputStream())).isEmpty())
                assertEquals(listOf(child), treeChildren(readReply(socket.getInputStream())))
                val json = httpGet(server.localPort, "/state.json")
                assertContains(
                    json,
                    windowJsonId(child) + ""","parent":"${secondParent.toJsonHex()}","x":7,"y":8,"localX":7,"localY":8,"width":40,"height":30""",
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ReparentWindow delivers StructureNotify and old and new parent SubstructureNotify`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val oldParent = WindowId + 411
                    val newParent = WindowId + 412
                    val child = WindowId + 413
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(oldParent))
                    ownerOut.write(createWindowRequest(newParent))
                    ownerOut.write(createWindowRequest(child, parent = oldParent, overrideRedirect = true, eventMask = XEventMasks.StructureNotify))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(4, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(oldParent, XEventMasks.SubstructureNotify))
                    observerOut.write(changeWindowEventMaskRequest(newParent, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(reparentWindowRequest(child, newParent, x = 7, y = 8))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertReparentNotify(
                        ownerSocket.getInputStream().readExactly(32),
                        sequence = 5,
                        eventWindow = child,
                        window = child,
                        parent = newParent,
                        x = 7,
                        y = 8,
                        overrideRedirect = true,
                    )
                    assertEquals(6, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertReparentNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = oldParent,
                        window = child,
                        parent = newParent,
                        x = 7,
                        y = 8,
                        overrideRedirect = true,
                    )
                    assertReparentNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = newParent,
                        window = child,
                        parent = newParent,
                        x = 7,
                        y = 8,
                        overrideRedirect = true,
                    )
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ReparentWindow automatically unmaps and remaps mapped window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val oldParent = WindowId + 414
                val newParent = WindowId + 415
                val child = WindowId + 416
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(oldParent))
                out.write(createWindowRequest(newParent))
                out.write(createWindowRequest(child, parent = oldParent, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(oldParent))
                out.write(mapWindowRequest(newParent))
                out.write(mapWindowRequest(child))
                out.write(reparentWindowRequest(child, newParent, x = 9, y = 10))
                out.write(queryPointerRequest())
                out.flush()

                assertExpose(input.readExactly(32), oldParent)
                assertExpose(input.readExactly(32), newParent)
                assertSelectedMapAndExpose(input, child)
                assertUnmapNotify(input.readExactly(32), sequence = 7, eventWindow = child, window = child)
                assertReparentNotify(
                    input.readExactly(32),
                    sequence = 7,
                    eventWindow = child,
                    window = child,
                    parent = newParent,
                    x = 9,
                    y = 10,
                )
                assertMapNotify(input.readExactly(32), sequence = 7, eventWindow = child, window = child)
                assertExpose(input.readExactly(32), child)
                assertEquals(8, u16le(readReply(input), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeWindowAttributes validates value mask length and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 406
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(request(2, 0, ByteArray(4)))
                out.write(changeWindowAttributesRawRequest(WindowId, 0x0000_0001))
                out.write(changeWindowAttributesRawRequest(WindowId, 0, 0))
                out.write(changeWindowAttributesRawRequest(WindowId, 0x0000_8000, 0))
                out.write(changeWindowEventMaskRequest(missing, XEventMasks.PropertyChange))
                out.write(changeWindowEventMaskRequest(WindowId, 0xfe00_0000.toInt()))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 12, 1 shl 5))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 4, 11))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 5, 11))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 6, 3))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 9, 2))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 10, 2))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 13, ColormapId + 501))
                out.write(changeWindowAttributesRawRequest(WindowId, 1 shl 14, PixmapId + 501))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.PropertyChange))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 2, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 2, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 2, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 0x0000_8000, sequence = 5)
                assertError(socket.getInputStream(), error = 3, opcode = 2, badValue = missing, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 0xfe00_0000.toInt(), sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 1 shl 5, sequence = 8)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 11, sequence = 9)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 11, sequence = 10)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 3, sequence = 11)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 2, sequence = 12)
                assertError(socket.getInputStream(), error = 2, opcode = 2, badValue = 2, sequence = 13)
                assertError(socket.getInputStream(), error = 12, opcode = 2, badValue = ColormapId + 501, sequence = 14)
                assertError(socket.getInputStream(), error = 6, opcode = 2, badValue = PixmapId + 501, sequence = 15)
                val pointer = readReply(socket.getInputStream())
                assertEquals(17, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow validates value mask parent and dimensions without reserving id`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingParent = WindowId + 406
                val out = socket.getOutputStream()
                out.write(request(1, 24, ByteArray(24)))
                out.write(createWindowRawRequest(WindowId, valueMask = 1 shl 11))
                out.write(createWindowRawRequest(WindowId, valueMask = 0x0000_8000, values = listOf(0)))
                out.write(createWindowRawRequest(WindowId, parent = missingParent))
                out.write(createWindowRawRequest(WindowId, width = 0))
                out.write(createWindowRequest(WindowId, eventMask = 0xfe00_0000.toInt()))
                out.write(createWindowRequest(WindowId, doNotPropagateMask = 1 shl 5))
                out.write(createWindowRequest(WindowId, bitGravity = 11))
                out.write(createWindowRequest(WindowId, winGravity = 11))
                out.write(createWindowRequest(WindowId, backingStore = 3))
                out.write(createWindowRequest(WindowId, overrideRedirectRaw = 2))
                out.write(createWindowRequest(WindowId, saveUnderRaw = 2))
                out.write(createWindowRequest(WindowId, colormap = ColormapId + 502))
                out.write(createWindowRequest(WindowId, cursor = PixmapId + 502))
                out.write(createWindowRequest(WindowId))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 1, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 1, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 0x0000_8000, sequence = 3)
                assertError(socket.getInputStream(), error = 3, opcode = 1, badValue = missingParent, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 0xfe00_0000.toInt(), sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 1 shl 5, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 11, sequence = 8)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 11, sequence = 9)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 3, sequence = 10)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 2, sequence = 11)
                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 2, sequence = 12)
                assertError(socket.getInputStream(), error = 12, opcode = 1, badValue = ColormapId + 502, sequence = 13)
                assertError(socket.getInputStream(), error = 6, opcode = 1, badValue = PixmapId + 502, sequence = 14)
                val pointer = readReply(socket.getInputStream())
                assertEquals(16, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow and ChangeWindowAttributes validate background pixmap`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingPixmap = PixmapId + 503
                val depthMismatchWindow = WindowId + 1
                val parentRelativeWindow = WindowId + 2
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2, depth = 8, drawable = WindowId))
                out.write(createWindowRequest(depthMismatchWindow, backgroundPixmap = missingPixmap))
                out.write(changeWindowAttributesRawRequest(WindowId, 1, missingPixmap))
                out.write(createWindowRequest(depthMismatchWindow, backgroundPixmap = PixmapId))
                out.write(changeWindowAttributesRawRequest(WindowId, 1, PixmapId))
                out.write(createWindowRequest(parentRelativeWindow, backgroundPixmap = XWindowBackground.ParentRelative))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 4, opcode = 1, badValue = missingPixmap, sequence = 3)
                assertError(socket.getInputStream(), error = 4, opcode = 2, badValue = missingPixmap, sequence = 4)
                assertError(socket.getInputStream(), error = 8, opcode = 1, badValue = PixmapId, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = 2, badValue = PixmapId, sequence = 6)
                val pointer = readReply(socket.getInputStream())
                assertEquals(8, u16le(pointer, 2))
                val json = httpGet(server.localPort, "/state.json")
                val parentRelativeJson = Regex("""\{"id":"0x${parentRelativeWindow.toUInt().toString(16)}".*?\}""").find(json)?.value.orEmpty()
                assertContains(parentRelativeJson, """"backgroundPixmap":"0x1"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow and ChangeWindowAttributes validate and preserve border attributes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingPixmap = PixmapId + 504
                val parent = WindowId
                val child = WindowId + 1
                val copied = WindowId + 2
                val changed = WindowId + 3
                val pixmapped = WindowId + 4
                val validPixmap = PixmapId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent, borderPixel = 0x0001_0203))
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2, depth = 8, drawable = parent))
                out.write(createPixmapRequest(validPixmap, width = 2, height = 2, drawable = parent))
                out.write(createWindowRequest(child, parent = parent, borderPixmap = missingPixmap))
                out.write(changeWindowAttributesRawRequest(parent, 1 shl 2, missingPixmap))
                out.write(createWindowRequest(child, parent = parent, borderPixmap = PixmapId))
                out.write(changeWindowAttributesRawRequest(parent, 1 shl 2, PixmapId))
                out.write(createWindowRequest(copied, parent = parent))
                out.write(createWindowRequest(changed, parent = parent, borderPixmap = XWindowBorder.CopyFromParent))
                out.write(changeWindowAttributesRawRequest(changed, (1 shl 2) or (1 shl 3), XWindowBorder.CopyFromParent, 0x0004_0506))
                out.write(createWindowRequest(pixmapped, parent = parent, borderPixmap = validPixmap))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 3, 0x0007_0809))
                out.write(changeWindowAttributesRawRequest(X11Ids.RootWindow, 1 shl 2, XWindowBorder.CopyFromParent))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 4, opcode = 1, badValue = missingPixmap, sequence = 4)
                assertError(socket.getInputStream(), error = 4, opcode = 2, badValue = missingPixmap, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = 1, badValue = PixmapId, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 2, badValue = PixmapId, sequence = 7)
                val pointer = readReply(socket.getInputStream())
                assertEquals(14, u16le(pointer, 2))

                val json = httpGet(server.localPort, "/state.json")
                val rootJson = Regex("""\{"id":"0x${X11Ids.RootWindow.toUInt().toString(16)}".*?\}""").find(json)?.value.orEmpty()
                val copiedJson = Regex("""\{"id":"0x${copied.toUInt().toString(16)}".*?\}""").find(json)?.value.orEmpty()
                val changedJson = Regex("""\{"id":"0x${changed.toUInt().toString(16)}".*?\}""").find(json)?.value.orEmpty()
                val pixmappedJson = Regex("""\{"id":"0x${pixmapped.toUInt().toString(16)}".*?\}""").find(json)?.value.orEmpty()
                assertContains(rootJson, """"borderPixel":0""")
                assertContains(rootJson, """"borderPixmap":null""")
                assertContains(copiedJson, """"borderPixel":66051""")
                assertContains(copiedJson, """"borderPixmap":null""")
                assertContains(changedJson, """"borderPixel":263430""")
                assertContains(changedJson, """"borderPixmap":null""")
                assertContains(pixmappedJson, """"borderPixmap":"0x${validPixmap.toUInt().toString(16)}"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow and ChangeWindowAttributes preserve scalar window attributes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val created = WindowId
                val changed = WindowId + 1
                val padded = WindowId + 2
                val changedMask = (1 shl 4) or (1 shl 5) or (1 shl 6) or (1 shl 7) or (1 shl 8) or (1 shl 9) or (1 shl 10)
                val out = socket.getOutputStream()
                out.write(
                    createWindowRequest(
                        created,
                        bitGravity = XWindowGravity.Static,
                        winGravity = XWindowGravity.SouthEast,
                        backingStore = XBackingStore.Always,
                        backingPlanes = 0x00ff_00ff,
                        backingPixel = 0x0012_3456,
                        saveUnder = true,
                    ),
                )
                out.write(createWindowRequest(changed))
                out.write(createWindowRequest(padded))
                out.write(changeWindowAttributesRawRequest(changed, changedMask, 1, 2, 1, 0x0f0f_0f0f, 0x0001_0203, 1, 0))
                out.write(
                    changeWindowAttributesRawRequest(
                        padded,
                        changedMask,
                        0x0000_010a,
                        0x0000_0209,
                        0x0000_0302,
                        0x0f0f_0f0f,
                        0x0001_0203,
                        0x0000_0401,
                        0x0000_0501,
                    ),
                )
                out.write(getWindowAttributesRequest(created))
                out.write(getWindowAttributesRequest(changed))
                out.write(getWindowAttributesRequest(padded))
                out.flush()

                val createdAttributes = readReply(socket.getInputStream())
                val changedAttributes = readReply(socket.getInputStream())
                val paddedAttributes = readReply(socket.getInputStream())
                assertEquals(XBackingStore.Always, createdAttributes[1].toInt() and 0xff)
                assertEquals(XWindowGravity.Static, createdAttributes[14].toInt() and 0xff)
                assertEquals(XWindowGravity.SouthEast, createdAttributes[15].toInt() and 0xff)
                assertEquals(0x00ff_00ff, u32le(createdAttributes, 16))
                assertEquals(0x0012_3456, u32le(createdAttributes, 20))
                assertEquals(1, createdAttributes[24].toInt() and 0xff)
                assertEquals(0, createdAttributes[27].toInt() and 0xff)
                assertEquals(1, changedAttributes[1].toInt() and 0xff)
                assertEquals(1, changedAttributes[14].toInt() and 0xff)
                assertEquals(2, changedAttributes[15].toInt() and 0xff)
                assertEquals(0x0f0f_0f0f, u32le(changedAttributes, 16))
                assertEquals(0x0001_0203, u32le(changedAttributes, 20))
                assertEquals(0, changedAttributes[24].toInt() and 0xff)
                assertEquals(1, changedAttributes[27].toInt() and 0xff)
                assertEquals(XBackingStore.Always, paddedAttributes[1].toInt() and 0xff)
                assertEquals(XWindowGravity.Static, paddedAttributes[14].toInt() and 0xff)
                assertEquals(XWindowGravity.SouthEast, paddedAttributes[15].toInt() and 0xff)
                assertEquals(0x0f0f_0f0f, u32le(paddedAttributes, 16))
                assertEquals(0x0001_0203, u32le(paddedAttributes, 20))
                assertEquals(1, paddedAttributes[24].toInt() and 0xff)
                assertEquals(1, paddedAttributes[27].toInt() and 0xff)
                val stateJson = httpGet(server.localPort, "/state.json")
                val createdJson = Regex("""\{"id":"0x${created.toUInt().toString(16)}".*?\}""").find(stateJson)?.value.orEmpty()
                val changedJson = Regex("""\{"id":"0x${changed.toUInt().toString(16)}".*?\}""").find(stateJson)?.value.orEmpty()
                val paddedJson = Regex("""\{"id":"0x${padded.toUInt().toString(16)}".*?\}""").find(stateJson)?.value.orEmpty()
                assertContains(createdJson, """"bitGravity":10""")
                assertContains(createdJson, """"winGravity":9""")
                assertContains(createdJson, """"backingStore":2""")
                assertContains(createdJson, """"backingPlanes":16711935""")
                assertContains(createdJson, """"backingPixel":1193046""")
                assertContains(createdJson, """"saveUnder":true""")
                assertContains(changedJson, """"bitGravity":1""")
                assertContains(changedJson, """"winGravity":2""")
                assertContains(changedJson, """"backingStore":1""")
                assertContains(changedJson, """"backingPlanes":252645135""")
                assertContains(changedJson, """"backingPixel":66051""")
                assertContains(changedJson, """"saveUnder":false""")
                assertContains(changedJson, """"overrideRedirect":true""")
                assertContains(paddedJson, """"bitGravity":10""")
                assertContains(paddedJson, """"winGravity":9""")
                assertContains(paddedJson, """"backingStore":2""")
                assertContains(paddedJson, """"backingPlanes":252645135""")
                assertContains(paddedJson, """"backingPixel":66051""")
                assertContains(paddedJson, """"saveUnder":true""")
                assertContains(paddedJson, """"overrideRedirect":true""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow and ChangeWindowAttributes preserve colormap in attributes and state snapshot`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val inherited = ColormapId + 510
                val changed = ColormapId + 511
                val out = socket.getOutputStream()
                out.write(createColormapRequest(inherited, window = X11Ids.RootWindow))
                out.write(createColormapRequest(changed, window = X11Ids.RootWindow))
                out.write(createWindowRequest(parent, colormap = inherited))
                out.write(createWindowRequest(child, parent = parent, colormap = 0))
                out.write(getWindowAttributesRequest(child))
                out.write(changeWindowAttributesRawRequest(child, 1 shl 13, changed))
                out.write(getWindowAttributesRequest(parent))
                out.write(getWindowAttributesRequest(child))
                out.write(installColormapRequest(changed))
                out.write(getWindowAttributesRequest(child))
                out.flush()

                val childInheritedAttributes = readReply(socket.getInputStream())
                val parentAttributes = readReply(socket.getInputStream())
                val childAttributes = readReply(socket.getInputStream())
                val childInstalledAttributes = readReply(socket.getInputStream())
                assertEquals(inherited, u32le(childInheritedAttributes, 28))
                assertEquals(0, childInheritedAttributes[25].toInt() and 0xff)
                assertEquals(inherited, u32le(parentAttributes, 28))
                assertEquals(changed, u32le(childAttributes, 28))
                assertEquals(0, parentAttributes[25].toInt() and 0xff)
                assertEquals(0, childAttributes[25].toInt() and 0xff)
                assertEquals(changed, u32le(childInstalledAttributes, 28))
                assertEquals(1, childInstalledAttributes[25].toInt() and 0xff)
                val stateJson = httpGet(server.localPort, "/state.json")
                val parentJson = Regex("""\{"id":"0x${parent.toUInt().toString(16)}".*?\}""").find(stateJson)?.value.orEmpty()
                val childJson = Regex("""\{"id":"0x${child.toUInt().toString(16)}".*?\}""").find(stateJson)?.value.orEmpty()
                assertContains(parentJson, """"colormap":"0x${inherited.toUInt().toString(16)}"""")
                assertContains(childJson, """"colormap":"0x${changed.toUInt().toString(16)}"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow and ChangeWindowAttributes preserve cursor in state snapshot`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val firstWindow = WindowId
                val secondWindow = WindowId + 1
                val source = PixmapId + 510
                val cursor = PixmapId + 511
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(source, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = source, mask = 0))
                out.write(createWindowRequest(firstWindow, cursor = cursor))
                out.write(createWindowRequest(secondWindow))
                out.write(changeWindowAttributesRawRequest(secondWindow, 1 shl 14, cursor))
                out.write(queryPointerRequest())
                out.flush()

                val pointerAfterSet = readReply(socket.getInputStream())
                assertEquals(6, u16le(pointerAfterSet, 2))
                val stateJsonAfterSet = httpGet(server.localPort, "/state.json")
                val firstWindowJson = Regex("""\{"id":"0x${firstWindow.toUInt().toString(16)}".*?\}""").find(stateJsonAfterSet)?.value.orEmpty()
                val secondWindowJsonAfterSet = Regex("""\{"id":"0x${secondWindow.toUInt().toString(16)}".*?\}""").find(stateJsonAfterSet)?.value.orEmpty()
                assertContains(firstWindowJson, """"cursor":"0x${cursor.toUInt().toString(16)}"""")
                assertContains(secondWindowJsonAfterSet, """"cursor":"0x${cursor.toUInt().toString(16)}"""")

                out.write(changeWindowAttributesRawRequest(secondWindow, 1 shl 14, 0))
                out.write(queryPointerRequest())
                out.flush()

                val pointerAfterClear = readReply(socket.getInputStream())
                assertEquals(8, u16le(pointerAfterClear, 2))
                val stateJsonAfterClear = httpGet(server.localPort, "/state.json")
                val secondWindowJsonAfterClear = Regex("""\{"id":"0x${secondWindow.toUInt().toString(16)}".*?\}""").find(stateJsonAfterClear)?.value.orEmpty()
                assertContains(secondWindowJsonAfterClear, """"cursor":null""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow validates depth class visual and InputOnly rules without reserving id`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val badVisual = X11Ids.RootVisual + 1
                val out = socket.getOutputStream()
                out.write(createWindowRawRequest(WindowId, windowClass = 3))
                out.write(createWindowRawRequest(WindowId, depth = 32))
                out.write(createWindowRawRequest(WindowId, visual = badVisual))
                out.write(createWindowRawRequest(WindowId, depth = 24, windowClass = XWindowClass.InputOnly))
                out.write(createWindowRawRequest(WindowId, depth = 0, windowClass = XWindowClass.InputOnly, borderWidth = 1))
                out.write(createWindowRawRequest(WindowId, depth = 0, windowClass = XWindowClass.InputOnly, valueMask = 1 shl 1, values = listOf(0)))
                out.write(createWindowRequest(WindowId))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 1, badValue = 3, sequence = 1)
                assertError(socket.getInputStream(), error = 8, opcode = 1, badValue = 32, sequence = 2)
                assertError(socket.getInputStream(), error = 8, opcode = 1, badValue = badVisual, sequence = 3)
                assertError(socket.getInputStream(), error = 8, opcode = 1, badValue = 24, sequence = 4)
                assertError(socket.getInputStream(), error = 8, opcode = 1, badValue = 1, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = 1, badValue = 1 shl 1, sequence = 6)
                val pointer = readReply(socket.getInputStream())
                assertEquals(8, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `InputOnly windows stay observable but are not drawables or visible SVG surfaces`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, x = 5, y = 7, width = 40, height = 30, depth = 0, windowClass = XWindowClass.InputOnly))
                out.write(createGcRequest(GcId, foreground = Red, drawable = WindowId))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.StructureNotify))
                out.write(mapWindowRequest(WindowId))
                out.flush()

                assertError(socket.getInputStream(), error = 8, opcode = 55, badValue = WindowId, sequence = 2)
                val mapNotify = socket.getInputStream().readExactly(32)
                assertMapNotify(mapNotify, sequence = 4, eventWindow = WindowId, window = WindowId)
                assertFailsWith<SocketTimeoutException> {
                    socket.getInputStream().readExactly(32)
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"id":"0x200001","parent":"0x26","x":5,"y":7""")
                assertContains(json, """"class":"InputOnly","depth":0,"visual":"0x28"""")
                val svg = httpGet(server.localPort, "/screen.svg")
                assertFalse(svg.contains("""data-window-id="0x200001""""))
                assertFalse(svg.contains("""data-drawable-id="0x200001""""))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `InputOnly windows preserve core protocol invariants after creation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val inputOnly = WindowId
                val inputOutput = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(inputOnly, depth = 0, windowClass = XWindowClass.InputOnly))
                out.write(createWindowRequest(inputOutput))
                out.write(reparentWindowRequest(inputOutput, inputOnly, x = 1, y = 2))
                out.write(changeWindowAttributesRawRequest(inputOnly, 1 shl 1, 0))
                out.write(configureWindowRequest(inputOnly, 0x0010, 1))
                out.write(getWindowAttributesRequest(inputOnly))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 8, opcode = 7, badValue = inputOnly, sequence = 3)
                assertError(socket.getInputStream(), error = 8, opcode = 2, badValue = 1 shl 1, sequence = 4)
                assertError(socket.getInputStream(), error = 8, opcode = 12, badValue = 1, sequence = 5)
                val attributes = readReply(socket.getInputStream())
                assertEquals(6, u16le(attributes, 2))
                assertEquals(X11Ids.RootVisual, u32le(attributes, 8))
                assertEquals(XWindowClass.InputOnly, u16le(attributes, 12))
                val pointer = readReply(socket.getInputStream())
                assertEquals(7, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `InputOnly windows reject graphics requests but still answer GetGeometry`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val inputOnly = WindowId
                val inputOutput = WindowId + 1
                val rectangle = XRectangleCommand(0, 0, 2, 2)
                val arc = XArcCommand(0, 0, 4, 4, 0, 90 * 64)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(inputOnly, x = 3, y = 4, width = 10, height = 9, depth = 0, windowClass = XWindowClass.InputOnly))
                out.write(createWindowRequest(inputOutput))
                out.write(createGcRequest(GcId, foreground = Red, drawable = inputOutput))
                out.write(clearAreaRequest(inputOnly, 0, 0, 1, 1))
                out.write(copyAreaRequest(inputOnly, inputOutput, GcId, 0, 0, 0, 0, 1, 1))
                out.write(copyAreaRequest(inputOutput, inputOnly, GcId, 0, 0, 0, 0, 1, 1))
                out.write(copyPlaneRequest(inputOnly, inputOutput, GcId, 0, 0, 0, 0, 1, 1, 1))
                out.write(polyPointRequest(inputOnly, GcId, coordMode = 0, points = listOf(0 to 0)))
                out.write(polyLineRequest(inputOnly, GcId, points = listOf(0 to 0, 1 to 1)))
                out.write(polySegmentRequest(inputOnly, GcId, segments = listOf((0 to 0) to (1 to 1))))
                out.write(polyRectangleRequest(inputOnly, GcId, rectangles = listOf(rectangle)))
                out.write(polyFillRectangleRequest(inputOnly, GcId, rectangles = listOf(rectangle)))
                out.write(polyArcRequest(inputOnly, GcId, filled = false, arcs = listOf(arc)))
                out.write(polyArcRequest(inputOnly, GcId, filled = true, arcs = listOf(arc)))
                out.write(getImageRequest(inputOnly, x = 0, y = 0, width = 1, height = 1))
                out.write(getGeometryRequest(inputOnly))
                out.flush()

                assertError(socket.getInputStream(), error = 8, opcode = 61, badValue = inputOnly, sequence = 4)
                assertError(socket.getInputStream(), error = 8, opcode = 62, badValue = inputOnly, sequence = 5)
                assertError(socket.getInputStream(), error = 8, opcode = 62, badValue = inputOnly, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 63, badValue = inputOnly, sequence = 7)
                assertError(socket.getInputStream(), error = 8, opcode = 64, badValue = inputOnly, sequence = 8)
                assertError(socket.getInputStream(), error = 8, opcode = 65, badValue = inputOnly, sequence = 9)
                assertError(socket.getInputStream(), error = 8, opcode = 66, badValue = inputOnly, sequence = 10)
                assertError(socket.getInputStream(), error = 8, opcode = 67, badValue = inputOnly, sequence = 11)
                assertError(socket.getInputStream(), error = 8, opcode = 70, badValue = inputOnly, sequence = 12)
                assertError(socket.getInputStream(), error = 8, opcode = 68, badValue = inputOnly, sequence = 13)
                assertError(socket.getInputStream(), error = 8, opcode = 71, badValue = inputOnly, sequence = 14)
                assertError(socket.getInputStream(), error = 8, opcode = 73, badValue = inputOnly, sequence = 15)
                val geometry = readReply(socket.getInputStream())
                assertEquals(16, u16le(geometry, 2))
                assertEquals(0, geometry[1].toInt() and 0xff)
                assertEquals(3, u16le(geometry, 12))
                assertEquals(4, u16le(geometry, 14))
                assertEquals(10, u16le(geometry, 16))
                assertEquals(9, u16le(geometry, 18))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `core drawing requests validate missing window and GC before mutation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val source = WindowId
                val destination = WindowId + 1
                val missingWindow = WindowId + 404
                val missingGc = GcId + 404
                val out = socket.getOutputStream()
                out.write(createWindowRequest(source))
                out.write(createWindowRequest(destination))
                out.write(clearAreaRequest(missingWindow, x = 0, y = 0, width = 1, height = 1))
                out.write(copyAreaRequest(source, destination, missingGc, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(copyPlaneRequest(source, destination, missingGc, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 1, height = 1, bitPlane = 1))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 3, opcode = 61, badValue = missingWindow, sequence = 3)
                assertError(socket.getInputStream(), error = 13, opcode = 62, badValue = missingGc, sequence = 4)
                assertError(socket.getInputStream(), error = 13, opcode = 63, badValue = missingGc, sequence = 5)
                val pointer = readReply(socket.getInputStream())
                assertEquals(6, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapWindow validates request length and window id without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 402
                val out = socket.getOutputStream()
                out.write(request(8, 0, ByteArray(0)))
                out.write(request(8, 0, ByteArray(8)))
                out.write(mapWindowRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 8, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 8, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 8, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CreateWindow delivers SubstructureNotify to another client selecting parent`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val child = WindowId + 403
                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(child, x = 7, y = 8, width = 33, height = 22, borderWidth = 2, overrideRedirect = true))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertCreateNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        parent = X11Ids.RootWindow,
                        window = child,
                        x = 7,
                        y = 8,
                        width = 33,
                        height = 22,
                        borderWidth = 2,
                        overrideRedirect = true,
                    )

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapWindow ignores already mapped window without duplicate events`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.StructureNotify))
                out.write(mapWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(queryPointerRequest())
                out.flush()

                assertSelectedMapAndExpose(input, WindowId)
                val pointer = readReply(input)
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapWindow delivers SubstructureNotify to another client selecting parent`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val child = WindowId + 404
                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(child))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertExpose(ownerSocket.getInputStream().readExactly(32), child)
                    assertEquals(3, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertCreateNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        parent = X11Ids.RootWindow,
                        window = child,
                    )
                    assertMapNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        eventWindow = X11Ids.RootWindow,
                        window = child,
                    )

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapWindow delivers MapRequest to another client selecting parent SubstructureRedirect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val child = WindowId + 424
                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(child))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    ownerOut.flush()

                    val children = treeChildren(readReply(ownerSocket.getInputStream()))
                    assertTrue(child in children)
                    assertContains(httpGet(server.localPort, "/state.json"), """"mapped":false""")
                    assertMapRequest(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        parent = X11Ids.RootWindow,
                        window = child,
                    )
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapWindow requester SubstructureRedirect selection does not self redirect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val child = WindowId + 425
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(child))
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(3, u16le(readReply(input), 2))

                out.write(mapWindowRequest(child))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertExpose(input.readExactly(32), child)
                val children = treeChildren(readReply(input))
                assertTrue(child in children)
                val childJson = httpGet(server.localPort, "/state.json")
                    .substringAfter(windowJsonId(child))
                    .substringBefore("}")
                assertContains(childJson, """"mapped":true""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapWindow override redirect bypasses parent SubstructureRedirect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val child = WindowId + 426
                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(child, overrideRedirect = true))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertExpose(ownerSocket.getInputStream().readExactly(32), child)
                    assertEquals(3, u16le(readReply(ownerSocket.getInputStream()), 2))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeWindowAttributes rejects duplicate SubstructureRedirect selection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { firstSocket ->
                Socket("127.0.0.1", server.localPort).use { secondSocket ->
                    firstSocket.soTimeout = 2_000
                    secondSocket.soTimeout = 2_000
                    setup(firstSocket)
                    setup(secondSocket)

                    val firstOut = firstSocket.getOutputStream()
                    firstOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                    firstOut.write(queryPointerRequest())
                    firstOut.flush()
                    assertEquals(2, u16le(readReply(firstSocket.getInputStream()), 2))

                    val secondOut = secondSocket.getOutputStream()
                    secondOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                    secondOut.write(queryPointerRequest())
                    secondOut.flush()
                    assertError(secondSocket.getInputStream(), error = 10, opcode = 2, badValue = 0, sequence = 1)
                    assertEquals(2, u16le(readReply(secondSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeWindowAttributes duplicate SubstructureRedirect rejection has no attribute side effects`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { firstSocket ->
                    Socket("127.0.0.1", server.localPort).use { secondSocket ->
                        ownerSocket.soTimeout = 2_000
                        firstSocket.soTimeout = 2_000
                        secondSocket.soTimeout = 2_000
                        setup(ownerSocket)
                        setup(firstSocket)
                        setup(secondSocket)

                        val child = WindowId + 427
                        val ownerOut = ownerSocket.getOutputStream()
                        ownerOut.write(createWindowRequest(child))
                        ownerOut.write(queryPointerRequest())
                        ownerOut.flush()
                        assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                        val firstOut = firstSocket.getOutputStream()
                        firstOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                        firstOut.write(changeWindowEventMaskRequest(child, XEventMasks.SubstructureRedirect))
                        firstOut.write(queryPointerRequest())
                        firstOut.flush()
                        assertEquals(3, u16le(readReply(firstSocket.getInputStream()), 2))

                        val duplicateMask = (1 shl 9) or (1 shl 11)
                        val secondOut = secondSocket.getOutputStream()
                        secondOut.write(changeWindowAttributesRawRequest(child, duplicateMask, 1, XEventMasks.SubstructureRedirect))
                        secondOut.write(queryPointerRequest())
                        secondOut.flush()
                        assertError(secondSocket.getInputStream(), error = 10, opcode = 2, badValue = 0, sequence = 1)
                        assertEquals(2, u16le(readReply(secondSocket.getInputStream()), 2))

                        ownerOut.write(mapWindowRequest(child))
                        ownerOut.write(queryPointerRequest())
                        ownerOut.flush()

                        assertMapRequest(firstSocket.getInputStream().readExactly(32), sequence = 3, parent = X11Ids.RootWindow, window = child)
                        val ownerReply = ownerSocket.getInputStream().readExactly(32)
                        assertEquals(1, ownerReply[0].toInt() and 0xff)
                        assertEquals(4, u16le(ownerReply, 2))
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapSubwindows validates request length and parent window without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 403
                val out = socket.getOutputStream()
                out.write(request(9, 0, ByteArray(0)))
                out.write(request(9, 0, ByteArray(8)))
                out.write(mapSubwindowsRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 9, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 9, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 9, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapSubwindows maps only unmapped children in top to bottom order`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val bottom = WindowId + 1
                val middle = WindowId + 2
                val top = WindowId + 3
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(bottom, parent = parent, eventMask = XEventMasks.StructureNotify))
                out.write(createWindowRequest(middle, parent = parent, eventMask = XEventMasks.StructureNotify))
                out.write(createWindowRequest(top, parent = parent, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(middle))
                out.write(mapSubwindowsRequest(parent))
                out.write(queryPointerRequest())
                out.flush()

                assertMapNotify(input.readExactly(32), sequence = 5, eventWindow = middle, window = middle)
                assertMapNotify(input.readExactly(32), sequence = 6, eventWindow = top, window = top)
                assertMapNotify(input.readExactly(32), sequence = 6, eventWindow = bottom, window = bottom)
                val pointer = readReply(input)
                assertEquals(1, pointer[0].toInt())
                assertEquals(7, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapSubwindows redirects unmapped children to parent SubstructureRedirect selector`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val parent = WindowId + 428
                    val bottom = parent + 1
                    val top = parent + 2
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(parent))
                    ownerOut.write(createWindowRequest(bottom, parent = parent))
                    ownerOut.write(createWindowRequest(top, parent = parent))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(4, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(parent, XEventMasks.SubstructureRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(mapSubwindowsRequest(parent))
                    ownerOut.write(getWindowAttributesRequest(top))
                    ownerOut.write(getWindowAttributesRequest(bottom))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertMapRequest(observerSocket.getInputStream().readExactly(32), sequence = 2, parent = parent, window = top)
                    assertMapRequest(observerSocket.getInputStream().readExactly(32), sequence = 2, parent = parent, window = bottom)
                    val topAttributes = readReply(ownerSocket.getInputStream())
                    val bottomAttributes = readReply(ownerSocket.getInputStream())
                    assertEquals(0, topAttributes[26].toInt() and 0xff)
                    assertEquals(0, bottomAttributes[26].toInt() and 0xff)
                    assertEquals(8, u16le(readReply(ownerSocket.getInputStream()), 2))

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapSubwindows mixes redirected override and already mapped children independently`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val parent = WindowId + 431
                    val redirected = parent + 1
                    val overrideChild = parent + 2
                    val alreadyMapped = parent + 3
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(parent))
                    ownerOut.write(createWindowRequest(redirected, parent = parent))
                    ownerOut.write(createWindowRequest(overrideChild, parent = parent, overrideRedirect = true))
                    ownerOut.write(createWindowRequest(alreadyMapped, parent = parent))
                    ownerOut.write(mapWindowRequest(alreadyMapped))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(6, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(parent, XEventMasks.SubstructureRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(mapSubwindowsRequest(parent))
                    ownerOut.write(getWindowAttributesRequest(overrideChild))
                    ownerOut.write(getWindowAttributesRequest(redirected))
                    ownerOut.write(getWindowAttributesRequest(alreadyMapped))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    val overrideAttributes = readReply(ownerSocket.getInputStream())
                    val redirectedAttributes = readReply(ownerSocket.getInputStream())
                    val alreadyMappedAttributes = readReply(ownerSocket.getInputStream())
                    assertEquals(1, overrideAttributes[26].toInt() and 0xff)
                    assertEquals(0, redirectedAttributes[26].toInt() and 0xff)
                    assertEquals(1, alreadyMappedAttributes[26].toInt() and 0xff)
                    assertEquals(11, u16le(readReply(ownerSocket.getInputStream()), 2))

                    assertMapRequest(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        parent = parent,
                        window = redirected,
                    )
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `MapWindow exposes already mapped inferiors when ancestor becomes viewable`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId + 435
                val child = parent + 1
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(child, parent = parent))
                out.write(mapWindowRequest(child))
                out.write(queryPointerRequest())
                out.flush()

                val childUnviewablePointer = readReply(input)
                assertEquals(4, u16le(childUnviewablePointer, 2))

                out.write(mapWindowRequest(parent))
                out.write(queryPointerRequest())
                out.flush()

                assertExpose(input.readExactly(32), parent)
                assertExpose(input.readExactly(32), child)
                val parentViewablePointer = readReply(input)
                assertEquals(6, u16le(parentViewablePointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ReparentWindow clears active pointer grab for mapped grab window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(child, x = 5, y = 5, width = 20, height = 20))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(grabPointerRequest(child))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), parent)
                assertMapAndExpose(socket.getInputStream(), child)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(reparentWindowRequest(child, parent, x = 7, y = 8))
                out.write(queryPointerRequest())
                out.flush()

                assertExpose(socket.getInputStream().readExactly(32), child)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(7, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ReparentWindow clears active pointer grab for mapped confine window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val confine = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(confine, x = 5, y = 5, width = 20, height = 20))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(confine))
                out.write(grabPointerRequest(X11Ids.RootWindow, confineTo = confine))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), parent)
                assertMapAndExpose(socket.getInputStream(), confine)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(reparentWindowRequest(confine, parent, x = 7, y = 8))
                out.write(queryPointerRequest())
                out.flush()

                assertExpose(socket.getInputStream().readExactly(32), confine)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(7, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow keeps pointer grab when confine window is clipped by parent but inside root`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val confine = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent, x = 0, y = 0, width = 20, height = 20))
                out.write(createWindowRequest(confine, parent = parent, x = 25, y = 5, width = 10, height = 10))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(confine))
                out.write(grabPointerRequest(X11Ids.RootWindow, confineTo = confine))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), parent)
                assertMapAndExpose(socket.getInputStream(), confine)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(configureWindowRequest(parent, 0x0003, 10, 0))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ReparentWindow clears active keyboard grab for mapped grab window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val child = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(child, x = 5, y = 5, width = 20, height = 20))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(grabKeyboardRequest(child))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), parent)
                assertMapAndExpose(socket.getInputStream(), child)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard"""")

                out.write(reparentWindowRequest(child, parent, x = 7, y = 8))
                out.write(queryPointerRequest())
                out.flush()

                assertExpose(socket.getInputStream().readExactly(32), child)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(7, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow validates request length and window id without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 403
                val out = socket.getOutputStream()
                out.write(request(10, 0, ByteArray(0)))
                out.write(request(10, 0, ByteArray(8)))
                out.write(unmapWindowRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 10, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 10, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 10, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow ignores already unmapped window without duplicate events`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(WindowId, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(WindowId))
                out.write(unmapWindowRequest(WindowId))
                out.write(unmapWindowRequest(WindowId))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertUnmapNotify(input.readExactly(32), sequence = 3, eventWindow = WindowId, window = WindowId)
                val pointer = readReply(input)
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow delivers SubstructureNotify to another client selecting parent`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val child = WindowId + 405
                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(child))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.write(unmapWindowRequest(child))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertMapAndExpose(ownerSocket.getInputStream(), child)
                    assertEquals(4, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertCreateNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        parent = X11Ids.RootWindow,
                        window = child,
                    )
                    assertMapNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        eventWindow = X11Ids.RootWindow,
                        window = child,
                    )
                    assertUnmapNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        eventWindow = X11Ids.RootWindow,
                        window = child,
                    )

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow exposes parent and lower overlapping sibling`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val parent = WindowId + 410
                val lower = WindowId + 411
                val top = WindowId + 412

                out.write(createWindowRequest(parent, width = 60, height = 45, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(lower, parent = parent, x = 0, y = 0, width = 30, height = 30, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(top, parent = parent, x = 10, y = 10, width = 30, height = 30, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(lower))
                out.write(mapWindowRequest(top))
                out.write(unmapWindowRequest(top))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, lower)
                assertMapAndExpose(input, top)
                assertUnmapNotify(input.readExactly(32), sequence = 7, eventWindow = top, window = top)
                assertExpose(input.readExactly(32), parent, sequence = 7, width = 60, height = 45, count = 0)
                assertExpose(input.readExactly(32), lower, sequence = 7, width = 30, height = 30, count = 0)
                assertEquals(8, u16le(readReply(input), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow does not expose when higher siblings already covered target`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val parent = WindowId + 416
                val target = WindowId + 417
                val cover = WindowId + 418

                out.write(createWindowRequest(parent, width = 60, height = 45, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(target, parent = parent, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.StructureNotify))
                out.write(createWindowRequest(cover, parent = parent, x = 10, y = 10, width = 20, height = 20))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(target))
                out.write(mapWindowRequest(cover))
                out.write(unmapWindowRequest(target))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, target)
                assertMapAndExpose(input, cover)
                assertUnmapNotify(input.readExactly(32), sequence = 7, eventWindow = target, window = target)
                assertEquals(8, u16le(readReply(input), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow exposes mapped descendants of uncovered lower sibling`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val parent = WindowId + 419
                val lower = WindowId + 420
                val lowerChild = WindowId + 421
                val top = WindowId + 422

                out.write(createWindowRequest(parent, width = 70, height = 55, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(lower, parent = parent, x = 0, y = 0, width = 40, height = 40, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(lowerChild, parent = lower, x = 15, y = 15, width = 10, height = 10, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(top, parent = parent, x = 10, y = 10, width = 30, height = 30, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(lower))
                out.write(mapWindowRequest(lowerChild))
                out.write(mapWindowRequest(top))
                out.write(unmapWindowRequest(top))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, lower)
                assertMapAndExpose(input, lowerChild)
                assertMapAndExpose(input, top)
                assertUnmapNotify(input.readExactly(32), sequence = 9, eventWindow = top, window = top)
                assertExpose(input.readExactly(32), lower, sequence = 9, width = 40, height = 40, count = 0)
                assertExpose(input.readExactly(32), lowerChild, sequence = 9, width = 10, height = 10, count = 0)
                assertEquals(10, u16le(readReply(input), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow exposes topmost lower sibling only when it covers lower siblings`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val parent = WindowId + 423
                val bottom = WindowId + 424
                val middle = WindowId + 425
                val top = WindowId + 426

                out.write(createWindowRequest(parent, width = 60, height = 45, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(bottom, parent = parent, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(middle, parent = parent, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(top, parent = parent, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(bottom))
                out.write(mapWindowRequest(middle))
                out.write(mapWindowRequest(top))
                out.write(unmapWindowRequest(top))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, bottom)
                assertMapAndExpose(input, middle)
                assertMapAndExpose(input, top)
                assertUnmapNotify(input.readExactly(32), sequence = 9, eventWindow = top, window = top)
                assertExpose(input.readExactly(32), middle, sequence = 9, width = 20, height = 20, count = 0)
                assertEquals(10, u16le(readReply(input), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow does not expose parent when lower sibling covers exposed region`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val parent = WindowId + 427
                val lower = WindowId + 428
                val top = WindowId + 429

                out.write(createWindowRequest(parent, width = 60, height = 45, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(lower, parent = parent, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(top, parent = parent, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(lower))
                out.write(mapWindowRequest(top))
                out.write(unmapWindowRequest(top))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, lower)
                assertMapAndExpose(input, top)
                assertUnmapNotify(input.readExactly(32), sequence = 7, eventWindow = top, window = top)
                assertExpose(input.readExactly(32), lower, sequence = 7, width = 20, height = 20, count = 0)
                assertEquals(8, u16le(readReply(input), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapSubwindows validates request length and parent window without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 404
                val out = socket.getOutputStream()
                out.write(request(11, 0, ByteArray(0)))
                out.write(request(11, 0, ByteArray(8)))
                out.write(unmapSubwindowsRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 11, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 11, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 11, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapSubwindows exposes parent after unmapping visible children`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val parent = WindowId + 413
                val first = WindowId + 414
                val second = WindowId + 415

                out.write(createWindowRequest(parent, width = 60, height = 45, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(first, parent = parent, x = 0, y = 0, width = 30, height = 30, eventMask = XEventMasks.StructureNotify))
                out.write(createWindowRequest(second, parent = parent, x = 10, y = 10, width = 30, height = 30, eventMask = XEventMasks.StructureNotify))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(second))
                out.write(unmapSubwindowsRequest(parent))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, first)
                assertMapAndExpose(input, second)
                assertUnmapNotify(input.readExactly(32), sequence = 7, eventWindow = first, window = first)
                assertUnmapNotify(input.readExactly(32), sequence = 7, eventWindow = second, window = second)
                assertExpose(input.readExactly(32), parent, sequence = 7, width = 60, height = 45, count = 0)
                assertEquals(8, u16le(readReply(input), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapSubwindows unmaps only mapped children in bottom to top order`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val bottom = WindowId + 1
                val middle = WindowId + 2
                val top = WindowId + 3
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(parent, eventMask = XEventMasks.SubstructureNotify))
                out.write(createWindowRequest(bottom, parent = parent))
                out.write(createWindowRequest(middle, parent = parent))
                out.write(createWindowRequest(top, parent = parent))
                out.write(mapWindowRequest(bottom))
                out.write(mapWindowRequest(middle))
                out.write(mapWindowRequest(top))
                out.write(unmapWindowRequest(middle))
                out.write(unmapSubwindowsRequest(parent))
                out.write(queryPointerRequest())
                out.flush()

                assertCreateNotify(input.readExactly(32), sequence = 2, parent = parent, window = bottom)
                assertCreateNotify(input.readExactly(32), sequence = 3, parent = parent, window = middle)
                assertCreateNotify(input.readExactly(32), sequence = 4, parent = parent, window = top)
                assertMapNotify(input.readExactly(32), sequence = 5, eventWindow = parent, window = bottom)
                assertMapNotify(input.readExactly(32), sequence = 6, eventWindow = parent, window = middle)
                assertMapNotify(input.readExactly(32), sequence = 7, eventWindow = parent, window = top)
                assertUnmapNotify(input.readExactly(32), sequence = 8, eventWindow = parent, window = middle)
                assertUnmapNotify(input.readExactly(32), sequence = 9, eventWindow = parent, window = bottom)
                assertUnmapNotify(input.readExactly(32), sequence = 9, eventWindow = parent, window = top)
                val pointer = readReply(input)
                assertEquals(1, pointer[0].toInt())
                assertEquals(10, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetWindowAttributes validates request length and window without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 405
                val out = socket.getOutputStream()
                out.write(request(3, 0, ByteArray(0)))
                out.write(request(3, 0, ByteArray(8)))
                out.write(getWindowAttributesRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 3, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 3, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 3, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetWindowAttributes reports map state and selected event masks`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)
                    val parent = WindowId + 406
                    val child = parent + 1
                    val ownerMask = XEventMasks.StructureNotify
                    val observerMask = XEventMasks.PropertyChange
                    val doNotPropagateMask = XEventMasks.KeyPress or XEventMasks.ButtonPress
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(parent))
                    ownerOut.write(createWindowRequest(child, parent = parent, eventMask = ownerMask, doNotPropagateMask = doNotPropagateMask))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(3, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(child, observerMask))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(mapWindowRequest(parent))
                    ownerOut.write(mapWindowRequest(child))
                    ownerOut.write(unmapWindowRequest(parent))
                    ownerOut.write(getWindowAttributesRequest(parent))
                    ownerOut.write(getWindowAttributesRequest(child))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertMapAndExpose(ownerSocket.getInputStream(), parent)
                    assertMapAndExpose(ownerSocket.getInputStream(), child)
                    val parentAttributes = readReply(ownerSocket.getInputStream())
                    val childAttributes = readReply(ownerSocket.getInputStream())
                    assertEquals(0, parentAttributes[26].toInt() and 0xff)
                    assertEquals(0, u32le(parentAttributes, 32))
                    assertEquals(0, u32le(parentAttributes, 36))
                    assertEquals(0, u16le(parentAttributes, 40))
                    assertEquals(1, childAttributes[26].toInt() and 0xff)
                    assertEquals(ownerMask or observerMask, u32le(childAttributes, 32))
                    assertEquals(ownerMask, u32le(childAttributes, 36))
                    assertEquals(doNotPropagateMask, u16le(childAttributes, 40))
                    assertEquals(9, u16le(readReply(ownerSocket.getInputStream()), 2))

                    observerOut.write(getWindowAttributesRequest(child))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()

                    val observerAttributes = readReply(observerSocket.getInputStream())
                    assertEquals(ownerMask or observerMask, u32le(observerAttributes, 32))
                    assertEquals(observerMask, u32le(observerAttributes, 36))
                    assertEquals(4, u16le(readReply(observerSocket.getInputStream()), 2))

                    observerOut.write(changeWindowEventMaskRequest(child, 0))
                    observerOut.write(getWindowAttributesRequest(child))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()

                    val observerAttributesAfterClear = readReply(observerSocket.getInputStream())
                    assertEquals(ownerMask, u32le(observerAttributesAfterClear, 32))
                    assertEquals(0, u32le(observerAttributesAfterClear, 36))
                    assertEquals(7, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapSubwindows unmaps all direct child windows`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val parent = WindowId
                val first = WindowId + 1
                val second = WindowId + 2
                val out = socket.getOutputStream()
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(first, parent = parent))
                out.write(createWindowRequest(second, parent = parent))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(second))
                out.write(getWindowAttributesRequest(first))
                out.write(getWindowAttributesRequest(second))
                out.write(unmapSubwindowsRequest(parent))
                out.write(getWindowAttributesRequest(first))
                out.write(getWindowAttributesRequest(second))
                out.flush()

                assertMapAndExpose(socket.getInputStream(), parent)
                assertMapAndExpose(socket.getInputStream(), first)
                assertMapAndExpose(socket.getInputStream(), second)
                val firstMapped = readReply(socket.getInputStream())
                val secondMapped = readReply(socket.getInputStream())
                assertEquals(2, firstMapped[26].toInt() and 0xff)
                assertEquals(2, secondMapped[26].toInt() and 0xff)

                val firstUnmapped = readReply(socket.getInputStream())
                val secondUnmapped = readReply(socket.getInputStream())
                assertEquals(0, firstUnmapped[26].toInt() and 0xff)
                assertEquals(0, secondUnmapped[26].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow validates value mask length and window without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 405
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(request(12, 0, ByteArray(0)))
                out.write(configureWindowRequest(WindowId, 0x0001))
                out.write(request(12, 0, ByteArray(12).also {
                    put32le(it, 0, WindowId)
                }))
                out.write(configureWindowRequest(WindowId, 0x0080, 0))
                out.write(configureWindowRequest(missing, 0))
                out.write(configureWindowRequest(WindowId, 0x0060, WindowId, 0))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 12, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 12, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 12, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 12, badValue = 0x0080, sequence = 5)
                assertError(socket.getInputStream(), error = 3, opcode = 12, badValue = missing, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 12, badValue = WindowId, sequence = 7)
                val pointer = readReply(socket.getInputStream())
                assertEquals(8, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow validates stack mode and sibling constraints without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val first = WindowId
                val second = WindowId + 1
                val nested = WindowId + 2
                val missing = WindowId + 499
                val out = socket.getOutputStream()
                out.write(createWindowRequest(first))
                out.write(createWindowRequest(second))
                out.write(createWindowRequest(nested, parent = first))
                out.write(configureWindowRequest(first, 0x0020, second))
                out.write(configureWindowRequest(first, 0x0040, 5))
                out.write(configureWindowRequest(first, 0x0060, missing, 0))
                out.write(configureWindowRequest(first, 0x0060, nested, 0))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertError(socket.getInputStream(), error = 8, opcode = 12, badValue = second, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 12, badValue = 5, sequence = 5)
                assertError(socket.getInputStream(), error = 3, opcode = 12, badValue = missing, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 12, badValue = nested, sequence = 7)
                assertEquals(listOf(first, second), treeChildren(readReply(socket.getInputStream())))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow restacks with stack mode and final geometry`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val first = WindowId
                val second = WindowId + 1
                val third = WindowId + 2
                val nested = WindowId + 3
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(first, x = 0, y = 0, width = 20, height = 20))
                out.write(createWindowRequest(nested, parent = first, x = 1, y = 1, width = 5, height = 5))
                out.write(createWindowRequest(second, x = 50, y = 50, width = 20, height = 20))
                out.write(createWindowRequest(third, x = 80, y = 80, width = 10, height = 10))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(second))
                out.write(mapWindowRequest(third))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(changeWindowEventMaskRequest(first, XEventMasks.StructureNotify))
                out.write(changeWindowEventMaskRequest(third, XEventMasks.StructureNotify))
                out.write(configureWindowRequest(first, 0x0040, 0))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(configureWindowRequest(first, 0x0060, second, 1))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(configureWindowRequest(third, 0x0060, first, 0))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(configureWindowRequest(first, 0x0043, 50, 50, 2))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(configureWindowRequest(first, 0x0040, 3))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(getGeometryRequest(first))
                out.flush()

                assertMapAndExpose(input, first)
                assertMapAndExpose(input, second)
                assertMapAndExpose(input, third)
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                assertConfigureNotify(input.readExactly(32), sequence = 11, window = first, aboveSibling = third)
                assertEquals(listOf(second, third, first), treeChildren(readReply(input)))
                assertConfigureNotify(input.readExactly(32), sequence = 13, window = first, aboveSibling = 0)
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                assertConfigureNotify(input.readExactly(32), sequence = 15, window = third, aboveSibling = first)
                assertEquals(listOf(first, third, second), treeChildren(readReply(input)))
                assertConfigureNotify(input.readExactly(32), sequence = 17, window = first, aboveSibling = second)
                assertEquals(listOf(third, second, first), treeChildren(readReply(input)))
                assertConfigureNotify(input.readExactly(32), sequence = 19, window = first, aboveSibling = 0)
                assertEquals(listOf(first, third, second), treeChildren(readReply(input)))
                val geometry = readReply(input)
                assertEquals(21, u16le(geometry, 2))
                assertEquals(50, u16le(geometry, 12))
                assertEquals(50, u16le(geometry, 14))

                val json = httpGet(server.localPort, "/state.json")
                assertEquals(
                    true,
                    json.indexOf(windowJsonId(first)) < json.indexOf(windowJsonId(nested)),
                    "ConfigureWindow restacking should keep a window's descendants after the parent in snapshot/render order",
                )
                assertEquals(
                    true,
                    json.indexOf(windowJsonId(nested)) < json.indexOf(windowJsonId(third)),
                    "ConfigureWindow Above should place the moving window after the sibling's whole subtree in snapshot/render order",
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow delivers selected structure notifications without requester local event`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val first = WindowId + 430
                    val second = WindowId + 431
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(first, x = 0, y = 0, width = 40, height = 30, overrideRedirect = true))
                    ownerOut.write(createWindowRequest(second, x = 50, y = 0, width = 20, height = 20))
                    ownerOut.write(mapWindowRequest(first))
                    ownerOut.write(mapWindowRequest(second))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertMapAndExpose(ownerSocket.getInputStream(), first)
                    assertMapAndExpose(ownerSocket.getInputStream(), second)
                    assertEquals(5, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(first, XEventMasks.StructureNotify))
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(configureWindowRequest(first, 0x0003, 11, 12))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertEquals(7, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertConfigureNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = first,
                        window = first,
                        aboveSibling = 0,
                        x = 11,
                        y = 12,
                        width = 40,
                        height = 30,
                        borderWidth = 0,
                        overrideRedirect = true,
                    )
                    assertConfigureNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = X11Ids.RootWindow,
                        window = first,
                        aboveSibling = 0,
                        x = 11,
                        y = 12,
                        width = 40,
                        height = 30,
                        borderWidth = 0,
                        overrideRedirect = true,
                    )

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(4, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow delivers selected notifications for unmapped windows`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val window = WindowId + 432
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(window, x = 0, y = 0, width = 40, height = 30, overrideRedirect = true))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(window, XEventMasks.StructureNotify))
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(configureWindowRequest(window, 0x0003, 7, 8))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertEquals(4, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertConfigureNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = window,
                        window = window,
                        aboveSibling = 0,
                        x = 7,
                        y = 8,
                        width = 40,
                        height = 30,
                        borderWidth = 0,
                        overrideRedirect = true,
                    )
                    assertConfigureNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = X11Ids.RootWindow,
                        window = window,
                        aboveSibling = 0,
                        x = 7,
                        y = 8,
                        width = 40,
                        height = 30,
                        borderWidth = 0,
                        overrideRedirect = true,
                    )

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(4, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow delivers ConfigureRequest to parent SubstructureRedirect without changing geometry`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val window = WindowId + 433
                    val sibling = WindowId + 435
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(sibling, x = 50, y = 2, width = 20, height = 20))
                    ownerOut.write(createWindowRequest(window, x = 1, y = 2, width = 40, height = 30))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(3, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(configureWindowRequest(window, 0x006f, 11, 12, 33, 22, sibling, XStackMode.Below))
                    ownerOut.write(getGeometryRequest(window))
                    ownerOut.flush()

                    assertConfigureRequest(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        parent = X11Ids.RootWindow,
                        window = window,
                        sibling = sibling,
                        x = 11,
                        y = 12,
                        width = 33,
                        height = 22,
                        borderWidth = 0,
                        stackMode = XStackMode.Below,
                        valueMask = 0x006f,
                    )
                    val geometry = readReply(ownerSocket.getInputStream())
                    assertEquals(5, u16le(geometry, 2))
                    assertEquals(1, u16le(geometry, 12))
                    assertEquals(2, u16le(geometry, 14))
                    assertEquals(40, u16le(geometry, 16))
                    assertEquals(30, u16le(geometry, 18))

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow delivers ResizeRequest to window ResizeRedirect while preserving size`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val window = WindowId + 439
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(window, x = 1, y = 2, width = 40, height = 30))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(window, XEventMasks.ResizeRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(configureWindowRequest(window, 0x000f, 11, 12, 33, 22))
                    ownerOut.write(getGeometryRequest(window))
                    ownerOut.flush()

                    assertResizeRequest(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        window = window,
                        width = 33,
                        height = 22,
                    )
                    val geometry = readReply(ownerSocket.getInputStream())
                    assertEquals(4, u16le(geometry, 2))
                    assertEquals(11, u16le(geometry, 12))
                    assertEquals(12, u16le(geometry, 14))
                    assertEquals(40, u16le(geometry, 16))
                    assertEquals(30, u16le(geometry, 18))

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow requester ResizeRedirect selection does not self redirect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)

                val window = WindowId + 442
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(window, x = 1, y = 2, width = 40, height = 30))
                out.write(changeWindowEventMaskRequest(window, XEventMasks.ResizeRedirect))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(3, u16le(readReply(input), 2))

                out.write(configureWindowRequest(window, 0x000c, 33, 22))
                out.write(getGeometryRequest(window))
                out.flush()

                val geometry = readReply(input)
                assertEquals(5, u16le(geometry, 2))
                assertEquals(33, u16le(geometry, 16))
                assertEquals(22, u16le(geometry, 18))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow override redirect does not bypass window ResizeRedirect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val window = WindowId + 443
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(window, x = 1, y = 2, width = 40, height = 30, overrideRedirect = true))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(window, XEventMasks.ResizeRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(configureWindowRequest(window, 0x000c, 33, 22))
                    ownerOut.write(getGeometryRequest(window))
                    ownerOut.flush()

                    assertResizeRequest(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        window = window,
                        width = 33,
                        height = 22,
                    )
                    val geometry = readReply(ownerSocket.getInputStream())
                    assertEquals(4, u16le(geometry, 2))
                    assertEquals(40, u16le(geometry, 16))
                    assertEquals(30, u16le(geometry, 18))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow parent SubstructureRedirect takes precedence over window ResizeRedirect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { resizeSocket ->
                    Socket("127.0.0.1", server.localPort).use { parentSocket ->
                        ownerSocket.soTimeout = 2_000
                        resizeSocket.soTimeout = 2_000
                        parentSocket.soTimeout = 2_000
                        setup(ownerSocket)
                        setup(resizeSocket)
                        setup(parentSocket)

                        val window = WindowId + 440
                        val ownerOut = ownerSocket.getOutputStream()
                        ownerOut.write(createWindowRequest(window, x = 1, y = 2, width = 40, height = 30))
                        ownerOut.write(queryPointerRequest())
                        ownerOut.flush()
                        assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                        val resizeOut = resizeSocket.getOutputStream()
                        resizeOut.write(changeWindowEventMaskRequest(window, XEventMasks.ResizeRedirect))
                        resizeOut.write(queryPointerRequest())
                        resizeOut.flush()
                        assertEquals(2, u16le(readReply(resizeSocket.getInputStream()), 2))

                        val parentOut = parentSocket.getOutputStream()
                        parentOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                        parentOut.write(queryPointerRequest())
                        parentOut.flush()
                        assertEquals(2, u16le(readReply(parentSocket.getInputStream()), 2))

                        ownerOut.write(configureWindowRequest(window, 0x000f, 11, 12, 33, 22))
                        ownerOut.write(getGeometryRequest(window))
                        ownerOut.flush()

                        assertConfigureRequest(
                            parentSocket.getInputStream().readExactly(32),
                            sequence = 2,
                            parent = X11Ids.RootWindow,
                            window = window,
                            sibling = 0,
                            x = 11,
                            y = 12,
                            width = 33,
                            height = 22,
                            borderWidth = 0,
                            stackMode = XStackMode.Above,
                            valueMask = 0x000f,
                        )
                        val geometry = readReply(ownerSocket.getInputStream())
                        assertEquals(4, u16le(geometry, 2))
                        assertEquals(1, u16le(geometry, 12))
                        assertEquals(2, u16le(geometry, 14))
                        assertEquals(40, u16le(geometry, 16))
                        assertEquals(30, u16le(geometry, 18))

                        resizeSocket.soTimeout = 250
                        assertFailsWith<SocketTimeoutException> {
                            resizeSocket.getInputStream().readExactly(32)
                        }
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeWindowAttributes rejects duplicate ResizeRedirect selection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { firstSocket ->
                    Socket("127.0.0.1", server.localPort).use { secondSocket ->
                        ownerSocket.soTimeout = 2_000
                        firstSocket.soTimeout = 2_000
                        secondSocket.soTimeout = 2_000
                        setup(ownerSocket)
                        setup(firstSocket)
                        setup(secondSocket)

                        val window = WindowId + 441
                        ownerSocket.getOutputStream().write(createWindowRequest(window))
                        ownerSocket.getOutputStream().write(queryPointerRequest())
                        ownerSocket.getOutputStream().flush()
                        assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                        val firstOut = firstSocket.getOutputStream()
                        firstOut.write(changeWindowEventMaskRequest(window, XEventMasks.ResizeRedirect))
                        firstOut.write(queryPointerRequest())
                        firstOut.flush()
                        assertEquals(2, u16le(readReply(firstSocket.getInputStream()), 2))

                        val secondOut = secondSocket.getOutputStream()
                        secondOut.write(changeWindowEventMaskRequest(window, XEventMasks.ResizeRedirect))
                        secondOut.write(queryPointerRequest())
                        secondOut.flush()
                        assertError(secondSocket.getInputStream(), error = 10, opcode = 2, badValue = 0, sequence = 1)
                        assertEquals(2, u16le(readReply(secondSocket.getInputStream()), 2))
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeWindowAttributes rejects duplicate ButtonPress selection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { firstSocket ->
                Socket("127.0.0.1", server.localPort).use { secondSocket ->
                    firstSocket.soTimeout = 2_000
                    secondSocket.soTimeout = 2_000
                    setup(firstSocket)
                    setup(secondSocket)

                    val firstOut = firstSocket.getOutputStream()
                    firstOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.ButtonPress))
                    firstOut.write(queryPointerRequest())
                    firstOut.flush()
                    assertEquals(2, u16le(readReply(firstSocket.getInputStream()), 2))

                    val secondOut = secondSocket.getOutputStream()
                    secondOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.ButtonPress))
                    secondOut.write(queryPointerRequest())
                    secondOut.flush()
                    assertError(secondSocket.getInputStream(), error = 10, opcode = 2, badValue = 0, sequence = 1)
                    assertEquals(2, u16le(readReply(secondSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow override redirect bypasses parent SubstructureRedirect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    val window = WindowId + 434
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(window, x = 1, y = 2, width = 40, height = 30, overrideRedirect = true))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                    ownerOut.write(configureWindowRequest(window, 0x000f, 11, 12, 33, 22))
                    ownerOut.write(getGeometryRequest(window))
                    ownerOut.flush()

                    val geometry = readReply(ownerSocket.getInputStream())
                    assertEquals(4, u16le(geometry, 2))
                    assertEquals(11, u16le(geometry, 12))
                    assertEquals(12, u16le(geometry, 14))
                    assertEquals(33, u16le(geometry, 16))
                    assertEquals(22, u16le(geometry, 18))

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow conditional restack ignores unmapped occluding siblings`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val first = WindowId
                val second = WindowId + 1
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(createWindowRequest(first, x = 0, y = 0, width = 20, height = 20))
                out.write(createWindowRequest(second, x = 5, y = 5, width = 20, height = 20))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(configureWindowRequest(first, 0x0040, 2))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(mapWindowRequest(first))
                out.write(configureWindowRequest(first, 0x0060, second, 2))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertEquals(listOf(first, second), treeChildren(readReply(input)))
                assertEquals(listOf(first, second), treeChildren(readReply(input)))
                assertMapAndExpose(input, first)
                assertEquals(listOf(first, second), treeChildren(readReply(input)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow rejects zero dimensions without changing geometry`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 40, height = 30))
                out.write(configureWindowRequest(WindowId, 0x0004, 0))
                out.write(configureWindowRequest(WindowId, 0x0008, 0))
                out.write(getGeometryRequest(WindowId))
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 12, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 12, badValue = 0, sequence = 3)
                val geometry = readReply(socket.getInputStream())
                assertEquals(4, u16le(geometry, 2))
                assertEquals(40, u16le(geometry, 16))
                assertEquals(30, u16le(geometry, 18))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConfigureWindow updates modeled geometry fields`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 40, height = 30))
                out.write(configureWindowRequest(WindowId, 0x001f, 11, 12, 50, 35, 3))
                out.write(getGeometryRequest(WindowId))
                out.flush()

                val geometry = readReply(socket.getInputStream())
                assertEquals(3, u16le(geometry, 2))
                assertEquals(11, u16le(geometry, 12))
                assertEquals(12, u16le(geometry, 14))
                assertEquals(50, u16le(geometry, 16))
                assertEquals(35, u16le(geometry, 18))
                assertEquals(3, u16le(geometry, 20))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetGeometry validates request length and drawable without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 406
                val out = socket.getOutputStream()
                out.write(request(14, 0, ByteArray(0)))
                out.write(request(14, 0, ByteArray(8)))
                out.write(getGeometryRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 14, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 14, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 9, opcode = 14, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryTree validates request length and window without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 407
                val out = socket.getOutputStream()
                out.write(request(15, 0, ByteArray(0)))
                out.write(request(15, 0, ByteArray(8)))
                out.write(queryTreeRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 15, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 15, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 15, badValue = missing, sequence = 3)
                val pointer = readReply(socket.getInputStream())
                assertEquals(4, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `InternAtom validates padded name length and only-if-exists without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(16, 0, ByteArray(0)))
                out.write(request(16, 0, ByteArray(4).also {
                    put16le(it, 0, 4)
                }))
                out.write(request(16, 0, ByteArray(12).also {
                    put16le(it, 0, 1)
                    it[4] = 'A'.code.toByte()
                }))
                out.write(internAtomRequest("JONNYZZZ_TEST_ATOM"))
                out.write(internAtomRequest("JONNYZZZ_MISSING_ATOM", onlyIfExists = true))
                out.write(internAtomRequest("PRIMARY", onlyIfExists = true))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 16, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 16, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 16, badValue = 0, sequence = 3)
                val created = readReply(socket.getInputStream())
                assertEquals(4, u16le(created, 2))
                assertTrue(u32le(created, 8) > 0)
                val missing = readReply(socket.getInputStream())
                assertEquals(5, u16le(missing, 2))
                assertEquals(0, u32le(missing, 8))
                val existing = readReply(socket.getInputStream())
                assertEquals(6, u16le(existing, 2))
                assertEquals(PrimaryAtom, u32le(existing, 8))
                val pointer = readReply(socket.getInputStream())
                assertEquals(7, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetAtomName validates request length and atom id without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = 0x00ff_ffff
                val out = socket.getOutputStream()
                out.write(request(17, 0, ByteArray(0)))
                out.write(request(17, 0, ByteArray(8)))
                out.write(getAtomNameRequest(missing))
                out.write(getAtomNameRequest(PrimaryAtom))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 17, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 17, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 5, opcode = 17, badValue = missing, sequence = 3)
                val primary = readReply(socket.getInputStream())
                assertEquals(4, u16le(primary, 2))
                val nameLength = u16le(primary, 8)
                assertEquals("PRIMARY", primary.copyOfRange(32, 32 + nameLength).decodeToString())
                val pointer = readReply(socket.getInputStream())
                assertEquals(5, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `FreeCursor clears active pointer grab that references cursor`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val cursor = PixmapId + 90
                val out = socket.getOutputStream()
                out.write(createPixmapRequest(PixmapId, width = 1, height = 1, depth = 1, drawable = X11Ids.RootWindow))
                out.write(createCursorRequest(cursor, source = PixmapId, mask = 0))
                out.write(grabPointerRequest(X11Ids.RootWindow, cursor = cursor))
                out.flush()
                assertEquals(1, readReply(socket.getInputStream())[0].toInt())
                val grabbed = httpGet(server.localPort, "/state.json")
                assertContains(grabbed, """"inputGrabs":[{"kind":"pointer"""")
                assertContains(grabbed, """"cursor":"0x${cursor.toString(16)}"""")

                out.write(freeCursorRequest(cursor))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabKeyboard replies success status for valid window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(grabKeyboardRequest(X11Ids.RootWindow))
                out.write(queryPointerRequest())
                out.flush()

                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertEquals(0, grab[1].toInt() and 0xff)
                assertEquals(0, u32le(grab, 4))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(1, pointer[1].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabKeyboard reports NotViewable or InvalidTime without activating grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                server.input.pointerDown(1, 1)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyboardRequest(WindowId))
                out.write(grabKeyboardRequest(X11Ids.RootWindow, time = 3))
                out.write(grabKeyboardRequest(X11Ids.RootWindow, time = 2))
                out.write(ungrabKeyboardRequest(time = 2))
                out.write(grabKeyboardRequest(X11Ids.RootWindow, time = 1))
                out.write(queryPointerRequest())
                out.flush()

                val notViewable = readReply(socket.getInputStream())
                assertEquals(1, notViewable[0].toInt())
                assertEquals(4, notViewable[1].toInt() and 0xff)
                assertEquals(2, u16le(notViewable, 2))

                val futureGrab = readReply(socket.getInputStream())
                assertEquals(1, futureGrab[0].toInt())
                assertEquals(3, futureGrab[1].toInt() and 0xff)
                assertEquals(3, u16le(futureGrab, 2))

                val validGrab = readReply(socket.getInputStream())
                assertEquals(1, validGrab[0].toInt())
                assertEquals(0, validGrab[1].toInt() and 0xff)
                assertEquals(4, u16le(validGrab, 2))

                val staleGrab = readReply(socket.getInputStream())
                assertEquals(1, staleGrab[0].toInt())
                assertEquals(3, staleGrab[1].toInt() and 0xff)
                assertEquals(6, u16le(staleGrab, 2))

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(7, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabKeyboard validates request length and stream recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(grabKeyboardOversizedRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 31, badValue = 0, sequence = 1)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(2, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabKeyboard clears active grab and is replyless`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(grabKeyboardRequest(X11Ids.RootWindow))
                out.flush()

                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard"""")

                out.write(ungrabKeyboardRequest())
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(1, pointer[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UngrabKeyboard validates request length and stream recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(ungrabKeyboardBadLengthRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 32, badValue = 0, sequence = 1)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(2, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `AllowEvents is replyless and records input control state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(allowEventsRequest(mode = 6))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(2, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"inputControlOperations":[{"id":1,"operation":"AllowEvents","mode":6,"modeName":"AsyncBoth","time":1}]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `AllowEvents ignores stale or future timestamps for active grabs`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                server.input.pointerDown(1, 1)
                val out = socket.getOutputStream()
                out.write(grabPointerRequest(X11Ids.RootWindow, time = 2))
                out.write(allowEventsRequest(mode = 6, time = 3))
                out.write(allowEventsRequest(mode = 6, time = 1))
                out.write(allowEventsRequest(mode = 6, time = 2))
                out.write(allowEventsRequest(mode = 7))
                out.write(queryPointerRequest())
                out.flush()

                val grab = readReply(socket.getInputStream())
                assertEquals(0, grab[1].toInt() and 0xff)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(6, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"inputControlOperations":[{"id":1,"operation":"AllowEvents","mode":6,"modeName":"AsyncBoth","time":2},{"id":2,"operation":"AllowEvents","mode":7,"modeName":"SyncBoth","time":2}]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `AllowEvents validates mode and request length with stream recovery`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(allowEventsRequest(mode = 8))
                out.write(allowEventsBadLengthRequest())
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 35, badValue = 8, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 35, badValue = 0, sequence = 2)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputControlOperations":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow clears active keyboard grab for grab window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(grabKeyboardRequest(WindowId))
                out.flush()
                assertMapAndExpose(socket.getInputStream(), WindowId)
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard"""")

                out.write(unmapWindowRequest(WindowId))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(5, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[]""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabServer and UngrabServer are replyless and update state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(grabServerRequest())
                out.write(queryPointerRequest())
                out.flush()

                val pointerWhileGrabbed = readReply(socket.getInputStream())
                assertEquals(1, pointerWhileGrabbed[0].toInt())
                assertEquals(2, u16le(pointerWhileGrabbed, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"serverGrabbed":true""")

                out.write(ungrabServerRequest())
                out.write(queryPointerRequest())
                out.flush()

                val pointerAfterUngrab = readReply(socket.getInputStream())
                assertEquals(1, pointerAfterUngrab[0].toInt())
                assertEquals(4, u16le(pointerAfterUngrab, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"serverGrabbed":false""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabServer and UngrabServer validate empty request bodies`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(36, 0, ByteArray(4)))
                out.write(request(37, 0, ByteArray(4)))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 36, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 37, badValue = 0, sequence = 2)
                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"serverGrabbed":false""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabServer delays other client requests until UngrabServer`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 300
                    setup(owner)
                    setup(other)
                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(grabServerRequest())
                    ownerOut.flush()
                    waitForStateContains(server.localPort, """"serverGrabbed":true""")

                    other.getOutputStream().write(queryPointerRequest())
                    other.getOutputStream().write(queryPointerRequest())
                    other.getOutputStream().flush()
                    assertFailsWith<SocketTimeoutException> {
                        readReply(other.getInputStream())
                    }

                    ownerOut.write(ungrabServerRequest())
                    ownerOut.flush()
                    other.soTimeout = 2_000
                    val pointer = readReply(other.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(1, u16le(pointer, 2))
                    val secondPointer = readReply(other.getInputStream())
                    assertEquals(1, secondPointer[0].toInt())
                    assertEquals(2, u16le(secondPointer, 2))
                    assertContains(httpGet(server.localPort, "/state.json"), """"serverGrabbed":false""")
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GrabServer is released when owner disconnects`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)
                    owner.getOutputStream().write(grabServerRequest())
                    owner.getOutputStream().flush()
                    waitForStateContains(server.localPort, """"serverGrabbed":true""")

                    owner.close()
                    waitForStateContains(server.localPort, """"serverGrabbed":false""")

                    other.getOutputStream().write(queryPointerRequest())
                    other.getOutputStream().flush()
                    val pointer = readReply(other.getInputStream())
                    assertEquals(1, pointer[0].toInt())
                    assertEquals(1, u16le(pointer, 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetInputFocus updates GetInputFocus reply`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createWindowRequest(WindowId + 1))
                out.write(mapWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId + 1))
                out.write(setInputFocusRequest(WindowId, revertTo = 2))
                out.write(getInputFocusRequest())
                out.flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                assertMapAndExpose(socket.getInputStream(), WindowId + 1)
                val focus = readReply(socket.getInputStream())
                assertEquals(1, focus[0].toInt())
                assertEquals(2, focus[1].toInt() and 0xff)
                assertEquals(0, u32le(focus, 4))
                assertEquals(WindowId, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetInputFocus emits FocusIn and FocusOut to FocusChange selections`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val first = WindowId
                val second = WindowId + 1
                out.write(createWindowRequest(first))
                out.write(createWindowRequest(second))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(second))
                out.write(setInputFocusRequest(0, revertTo = 2))
                out.write(changeWindowEventMaskRequest(first, XEventMasks.FocusChange))
                out.write(changeWindowEventMaskRequest(second, XEventMasks.FocusChange))
                out.write(setInputFocusRequest(first, revertTo = 2))
                out.write(setInputFocusRequest(second, revertTo = 2))
                out.flush()

                assertMapAndExpose(input, first)
                assertMapAndExpose(input, second)
                assertFocusEvent(input.readExactly(32), type = 9, sequence = 8, window = first)
                assertFocusEvent(input.readExactly(32), type = 10, sequence = 9, window = first)
                assertFocusEvent(input.readExactly(32), type = 9, sequence = 9, window = second)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow reverts focus to None and emits FocusOut`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(setInputFocusRequest(WindowId, revertTo = 0))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.FocusChange))
                out.write(unmapWindowRequest(WindowId))
                out.write(getInputFocusRequest())
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertFocusEvent(input.readExactly(32), type = 10, sequence = 5, window = WindowId)
                val focus = readReply(input)
                assertEquals(0, focus[1].toInt() and 0xff)
                assertEquals(0, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroyWindow reverts focus to None and emits FocusOut`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(setInputFocusRequest(WindowId, revertTo = 0))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.FocusChange))
                out.write(destroyWindowRequest(WindowId))
                out.write(getInputFocusRequest())
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertFocusEvent(input.readExactly(32), type = 10, sequence = 5, window = WindowId)
                val focus = readReply(input)
                assertEquals(0, focus[1].toInt() and 0xff)
                assertEquals(0, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow reverts focus to closest viewable parent`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val child = WindowId + 1
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(child, parent = parent))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(setInputFocusRequest(child, revertTo = 2))
                out.write(changeWindowEventMaskRequest(child, XEventMasks.FocusChange))
                out.write(changeWindowEventMaskRequest(parent, XEventMasks.FocusChange))
                out.write(unmapWindowRequest(child))
                out.write(getInputFocusRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, child)
                assertFocusEvent(input.readExactly(32), type = 10, sequence = 8, window = child)
                assertFocusEvent(input.readExactly(32), type = 9, sequence = 8, window = parent)
                val focus = readReply(input)
                assertEquals(0, focus[1].toInt() and 0xff)
                assertEquals(parent, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `UnmapWindow ancestor reverts non-viewable focused child`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val parent = WindowId
                val child = WindowId + 1
                out.write(createWindowRequest(parent))
                out.write(createWindowRequest(child, parent = parent))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(child))
                out.write(setInputFocusRequest(child, revertTo = 2))
                out.write(changeWindowEventMaskRequest(child, XEventMasks.FocusChange))
                out.write(unmapWindowRequest(parent))
                out.write(getInputFocusRequest())
                out.flush()

                assertMapAndExpose(input, parent)
                assertMapAndExpose(input, child)
                assertFocusEvent(input.readExactly(32), type = 10, sequence = 7, window = child)
                val focus = readReply(input)
                assertEquals(0, focus[1].toInt() and 0xff)
                assertEquals(X11Ids.RootWindow, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetInputFocus ignored by timestamp does not emit FocusChange events`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                server.input.pointerDown(1, 1)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                out.write(setInputFocusRequest(X11Ids.RootWindow, revertTo = 2))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.FocusChange))
                out.write(setInputFocusRequest(WindowId, revertTo = 2, time = Int.MAX_VALUE))
                out.write(getInputFocusRequest())
                out.write(setInputFocusRequest(WindowId, revertTo = 2, time = 2))
                out.write(getInputFocusRequest())
                out.write(setInputFocusRequest(0, revertTo = 2, time = 1))
                out.write(getInputFocusRequest())
                out.flush()

                assertMapAndExpose(input, WindowId)

                val futureIgnored = input.readExactly(32)
                assertEquals(1, futureIgnored[0].toInt() and 0xff)
                assertEquals(6, u16le(futureIgnored, 2))
                assertEquals(X11Ids.RootWindow, u32le(futureIgnored, 8))

                assertFocusEvent(input.readExactly(32), type = 9, sequence = 7, window = WindowId)
                val validFocus = input.readExactly(32)
                assertEquals(1, validFocus[0].toInt() and 0xff)
                assertEquals(8, u16le(validFocus, 2))
                assertEquals(WindowId, u32le(validFocus, 8))

                val staleIgnored = input.readExactly(32)
                assertEquals(1, staleIgnored[0].toInt() and 0xff)
                assertEquals(10, u16le(staleIgnored, 2))
                assertEquals(WindowId, u32le(staleIgnored, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetInputFocus rejects unmapped window without changing focus`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(setInputFocusRequest(WindowId, revertTo = 2))
                out.write(getInputFocusRequest())
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(8, error[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(error, 4))
                assertEquals(42, error[10].toInt() and 0xff)

                val focus = readReply(socket.getInputStream())
                assertEquals(1, focus[0].toInt())
                assertEquals(0, focus[1].toInt() and 0xff)
                assertEquals(X11Ids.RootWindow, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetInputFocus rejects mapped child of unmapped parent without changing focus`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val childId = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createWindowRequest(childId, parent = WindowId))
                out.write(mapWindowRequest(childId))
                out.write(setInputFocusRequest(childId, revertTo = 2))
                out.write(getInputFocusRequest())
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(8, error[1].toInt() and 0xff)
                assertEquals(childId, u32le(error, 4))
                assertEquals(42, error[10].toInt() and 0xff)

                val focus = readReply(socket.getInputStream())
                assertEquals(X11Ids.RootWindow, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetInputFocus ignores stale and future timestamps without changing focus`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                server.input.pointerDown(1, 1)
                val out = socket.getOutputStream()
                out.write(setInputFocusRequest(1, revertTo = 2, time = Int.MAX_VALUE))
                out.write(getInputFocusRequest())
                out.write(setInputFocusRequest(1, revertTo = 2, time = 2))
                out.write(getInputFocusRequest())
                out.write(setInputFocusRequest(0, revertTo = 2, time = 1))
                out.write(getInputFocusRequest())
                out.write(setInputFocusRequest(0, revertTo = 2, time = 0))
                out.write(getInputFocusRequest())
                out.flush()

                val futureIgnored = readReply(socket.getInputStream())
                assertEquals(2, u16le(futureIgnored, 2))
                assertEquals(X11Ids.RootWindow, u32le(futureIgnored, 8))

                val validExplicit = readReply(socket.getInputStream())
                assertEquals(4, u16le(validExplicit, 2))
                assertEquals(1, u32le(validExplicit, 8))

                val staleIgnored = readReply(socket.getInputStream())
                assertEquals(6, u16le(staleIgnored, 2))
                assertEquals(1, u32le(staleIgnored, 8))

                val currentTimeAccepted = readReply(socket.getInputStream())
                assertEquals(8, u16le(currentTimeAccepted, 2))
                assertEquals(0, u32le(currentTimeAccepted, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetInputFocus validates exact request length and stream recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(setInputFocusOversizedRequest())
                out.write(getInputFocusRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 42, badValue = 0, sequence = 1)
                val focus = readReply(socket.getInputStream())
                assertEquals(2, u16le(focus, 2))
                assertEquals(X11Ids.RootWindow, u32le(focus, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Empty-body reply requests validate length and stream recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(43, 0, ByteArray(4)))
                out.write(request(44, 0, ByteArray(4)))
                out.write(request(52, 0, ByteArray(4)))
                out.write(getInputFocusRequest())
                out.write(queryKeymapRequest())
                out.write(getFontPathRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 43, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 16, opcode = 44, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 52, badValue = 0, sequence = 3)

                val focus = readReply(socket.getInputStream())
                assertEquals(4, u16le(focus, 2))
                assertEquals(0, focus[1].toInt() and 0xff)
                assertEquals(X11Ids.RootWindow, u32le(focus, 8))

                val keymap = readReply(socket.getInputStream())
                assertEquals(5, u16le(keymap, 2))
                assertEquals(2, u32le(keymap, 4))
                for (index in 8 until 40) {
                    assertEquals(0, keymap[index].toInt() and 0xff)
                }

                val fontPath = readReply(socket.getInputStream())
                assertEquals(6, u16le(fontPath, 2))
                assertEquals(0, u32le(fontPath, 4))
                assertEquals(0, u16le(fontPath, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Bell validates signed percent and preserves connection after errors`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(bellRequest(50))
                out.write(bellRequest(-100))
                out.write(bellRequest(101))
                out.write(bellRequest(-101))
                out.write(request(104, 0, ByteArray(4)))
                out.write(getPointerControlRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 104, badValue = 101, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 104, badValue = -101, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 104, badValue = 0, sequence = 5)

                val pointer = readReply(socket.getInputStream())
                assertEquals(6, u16le(pointer, 2))
                assertEquals(2, u16le(pointer, 8))
                assertEquals(1, u16le(pointer, 10))
                assertEquals(4, u16le(pointer, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeKeyboardControl updates GetKeyboardControl and state snapshot`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(getKeyboardControlRequest())
                out.write(
                    changeKeyboardControlRequest(
                        0x01 to 25,
                        0x02 to 70,
                        0x04 to 440,
                        0x08 to 120,
                        0x10 to 3,
                        0x20 to 1,
                        0x40 to 40,
                        0x80 to 0,
                    ),
                )
                out.write(getKeyboardControlRequest())
                out.write(changeKeyboardControlRequest(0x20 to 0))
                out.write(changeKeyboardControlRequest(0x80 to 0))
                out.write(getKeyboardControlRequest())
                out.write(changeKeyboardControlRequest(0x01 to -1, 0x02 to -1, 0x04 to -1, 0x08 to -1, 0x80 to 2))
                out.write(getKeyboardControlRequest())
                out.flush()

                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 1,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = 0,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = emptySet(),
                )
                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 3,
                    keyClickPercent = 25,
                    bellPercent = 70,
                    bellPitch = 440,
                    bellDuration = 120,
                    ledMask = 0x4,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = setOf(40),
                )
                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 6,
                    keyClickPercent = 25,
                    bellPercent = 70,
                    bellPitch = 440,
                    bellDuration = 120,
                    ledMask = 0,
                    globalAutoRepeat = false,
                    autoRepeatDisabledKeycodes = setOf(40),
                )
                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 8,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = 0,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = setOf(40),
                )
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"keyboardControl":{"keyClickPercent":0,"bellPercent":50,"bellPitch":400,"bellDuration":100,"ledMask":"0x0","globalAutoRepeat":true""")
                assertContains(json, """"autoRepeats":["0xff","0xff","0xff","0xff","0xff","0xfe"""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeKeyboardControl reads typed LISTofVALUE slots in mask order`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(
                    changeKeyboardControlRawRequest(
                        0x01 or 0x02 or 0x04 or 0x08 or 0x10 or 0x20 or 0x40 or 0x80,
                        keyboardControlValueSlot(25, 0xaa, 0xbb, 0xcc),
                        keyboardControlValueSlot(70, 0xaa, 0xbb, 0xcc),
                        keyboardControlValueSlot(0xb8, 0x01, 0xbb, 0xcc),
                        keyboardControlValueSlot(120, 0, 0xbb, 0xcc),
                        keyboardControlValueSlot(3, 0xaa, 0xbb, 0xcc),
                        keyboardControlValueSlot(1, 0xaa, 0xbb, 0xcc),
                        keyboardControlValueSlot(40, 0xaa, 0xbb, 0xcc),
                        keyboardControlValueSlot(0, 0xaa, 0xbb, 0xcc),
                    ),
                )
                out.write(getKeyboardControlRequest())
                out.write(
                    changeKeyboardControlRawRequest(
                        0x01 or 0x02 or 0x04 or 0x08,
                        keyboardControlValueSlot(0xff, 0xaa, 0xbb, 0xcc),
                        keyboardControlValueSlot(0xff, 0xaa, 0xbb, 0xcc),
                        keyboardControlValueSlot(0xff, 0xff, 0xbb, 0xcc),
                        keyboardControlValueSlot(0xff, 0xff, 0xbb, 0xcc),
                    ),
                )
                out.write(getKeyboardControlRequest())
                out.flush()

                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 2,
                    keyClickPercent = 25,
                    bellPercent = 70,
                    bellPitch = 440,
                    bellDuration = 120,
                    ledMask = 0x4,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = setOf(40),
                )
                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 4,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = 0x4,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = setOf(40),
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeKeyboardControl reads big-endian typed LISTofVALUE slots`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket, byteOrderByte = 0x42)
                val out = socket.getOutputStream()
                out.write(
                    changeKeyboardControlRawRequestBigEndian(
                        0x01 or 0x02 or 0x04 or 0x08 or 0x10 or 0x20 or 0x40 or 0x80,
                        keyboardControlValueSlot(0xaa, 0xbb, 0xcc, 25),
                        keyboardControlValueSlot(0xaa, 0xbb, 0xcc, 70),
                        keyboardControlValueSlot(0xaa, 0xbb, 0x01, 0xb8),
                        keyboardControlValueSlot(0xaa, 0xbb, 0, 120),
                        keyboardControlValueSlot(0xaa, 0xbb, 0xcc, 3),
                        keyboardControlValueSlot(0xaa, 0xbb, 0xcc, 1),
                        keyboardControlValueSlot(0xaa, 0xbb, 0xcc, 40),
                        keyboardControlValueSlot(0xaa, 0xbb, 0xcc, 0),
                    ),
                )
                out.write(getKeyboardControlRequestBigEndian())
                out.flush()

                assertKeyboardControl(
                    readReply(socket.getInputStream(), byteOrderByte = 0x42),
                    sequence = 2,
                    keyClickPercent = 25,
                    bellPercent = 70,
                    bellPitch = 440,
                    bellDuration = 120,
                    ledMask = 0x4,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = setOf(40),
                    byteOrderByte = 0x42,
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeKeyboardControl handles LED and auto-repeat edge controls`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(changeKeyboardControlRequest(0x20 to 1))
                out.write(getKeyboardControlRequest())
                out.flush()

                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 2,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = -1,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = emptySet(),
                )
                assertContains(httpGet(server.localPort, "/state.json"), """"ledMask":"0xffffffff"""")

                out.write(changeKeyboardControlRequest(0x10 to 32, 0x20 to 0))
                out.write(getKeyboardControlRequest())
                out.write(changeKeyboardControlRequest(0x40 to 40, 0x80 to 0))
                out.write(getKeyboardControlRequest())
                out.write(changeKeyboardControlRequest(0x40 to 40, 0x80 to 2))
                out.write(getKeyboardControlRequest())
                out.flush()

                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 4,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = Int.MAX_VALUE,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = emptySet(),
                )
                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 6,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = Int.MAX_VALUE,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = setOf(40),
                )
                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 8,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = Int.MAX_VALUE,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = emptySet(),
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeKeyboardControl validates values matches and length and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(changeKeyboardControlRequest(0x01 to -2))
                out.write(changeKeyboardControlRequest(0x02 to 101))
                out.write(changeKeyboardControlRequest(0x04 to -2))
                out.write(changeKeyboardControlRequest(0x08 to -2))
                out.write(changeKeyboardControlRequest(0x10 to 0))
                out.write(changeKeyboardControlRequest(0x20 to 2))
                out.write(changeKeyboardControlRequest(0x40 to 7))
                out.write(changeKeyboardControlRequest(0x80 to 3))
                out.write(changeKeyboardControlRequest(0x10 to 3))
                out.write(changeKeyboardControlRequest(0x40 to 40))
                out.write(changeKeyboardControlRequest(0x100 to 1))
                out.write(request(102, 0, ByteArray(0)))
                out.write(request(102, 0, ByteArray(8)))
                out.write(request(103, 0, ByteArray(4)))
                out.write(getKeyboardControlRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = -2, sequence = 1)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = 101, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = -2, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = -2, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = 2, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = 7, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = 3, sequence = 8)
                assertError(socket.getInputStream(), error = 8, opcode = 102, badValue = 3, sequence = 9)
                assertError(socket.getInputStream(), error = 8, opcode = 102, badValue = 40, sequence = 10)
                assertError(socket.getInputStream(), error = 2, opcode = 102, badValue = 0x100, sequence = 11)
                assertError(socket.getInputStream(), error = 16, opcode = 102, badValue = 0, sequence = 12)
                assertError(socket.getInputStream(), error = 16, opcode = 102, badValue = 0, sequence = 13)
                assertError(socket.getInputStream(), error = 16, opcode = 103, badValue = 0, sequence = 14)
                assertKeyboardControl(
                    readReply(socket.getInputStream()),
                    sequence = 15,
                    keyClickPercent = 0,
                    bellPercent = 50,
                    bellPitch = 400,
                    bellDuration = 100,
                    ledMask = 0,
                    globalAutoRepeat = true,
                    autoRepeatDisabledKeycodes = emptySet(),
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetPointerMapping updates GetPointerMapping and validates failures`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(getPointerMappingRequest())
                val identity = pointerMapping()
                val swapped = pointerMapping(1 to 3, 3 to 1)
                val disabled = pointerMapping(1 to 0, 3 to 4, 4 to 3)
                val duplicate = pointerMapping(1 to 2)
                out.write(setPointerMappingRequest(*swapped))
                out.write(getPointerMappingRequest())
                out.write(setPointerMappingRequest(*disabled))
                out.write(getPointerMappingRequest())
                out.write(setPointerMappingRequest(*duplicate))
                out.write(setPointerMappingRequest(1, 2))
                out.write(request(116, 3, ByteArray(8)))
                out.write(request(117, 0, ByteArray(4)))
                out.write(getPointerMappingRequest())
                out.flush()

                assertPointerMapping(readReply(socket.getInputStream()), 1, *identity)
                assertMappingStatus(readReply(socket.getInputStream()), sequence = 2, status = 0)
                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 2)
                assertPointerMapping(readReply(socket.getInputStream()), 3, *swapped)
                assertMappingStatus(readReply(socket.getInputStream()), sequence = 4, status = 0)
                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 4)
                assertPointerMapping(readReply(socket.getInputStream()), 5, *disabled)

                assertError(socket.getInputStream(), error = 2, opcode = 116, badValue = 2, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 116, badValue = 2, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 116, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 117, badValue = 0, sequence = 9)

                assertPointerMapping(readReply(socket.getInputStream()), 10, *disabled)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeKeyboardMapping updates GetKeyboardMapping and state snapshot`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(changeKeyboardMappingRequest(38, 2, 0x0061, 0x0041, 0x0062, 0x0042))
                out.write(getKeyboardMappingRequest(37, 3))
                out.flush()

                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 1, request = 1, firstKeycode = 38, count = 2)
                assertKeyboardMapping(readReply(socket.getInputStream()), sequence = 2, keysymsPerKeycode = 2, 0xffe3, 0, 0x0061, 0x0041, 0x0062, 0x0042)
                val state = httpGet(server.localPort, "/state.json")
                assertContains(state, """"keyboardMapping":{"keysymsPerKeycode":2""")
                assertContains(state, """{"keycode":38,"keysyms":["0x61","0x41"]}""")
                assertContains(state, """{"keycode":39,"keysyms":["0x62","0x42"]}""")

                out.write(changeKeyboardMappingRequest(40, 1, 0x0063))
                out.write(getKeyboardMappingRequest(38, 3))
                out.flush()

                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 3, request = 1, firstKeycode = 40, count = 1)
                assertKeyboardMapping(readReply(socket.getInputStream()), sequence = 4, keysymsPerKeycode = 2, 0x0061, 0x0041, 0x0062, 0x0042, 0x0063, 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Keyboard mapping requests validate length and keycode range and recover stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(101, 0, ByteArray(0)))
                out.write(getKeyboardMappingRequest(7, 1))
                out.write(getKeyboardMappingRequest(255, 2))
                out.write(malformedChangeKeyboardMappingRequest(keycodeCount = 1, firstKeycode = 38, keysymsPerKeycode = 0))
                out.write(changeKeyboardMappingRequest(7, 1, 0x0061))
                out.write(changeKeyboardMappingRequest(255, 1, 0x0061, 0x0062))
                out.write(request(100, 38, ByteArray(0)))
                out.write(malformedChangeKeyboardMappingRequest(keycodeCount = 1, firstKeycode = 38, keysymsPerKeycode = 2, 0x0061))
                out.write(changeKeyboardMappingRequest(40, 1, 0x0063))
                out.write(getKeyboardMappingRequest(40, 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 101, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 2, opcode = 101, badValue = 7, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 101, badValue = 255, sequence = 3)
                assertError(socket.getInputStream(), error = 2, opcode = 100, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 2, opcode = 100, badValue = 7, sequence = 5)
                assertError(socket.getInputStream(), error = 2, opcode = 100, badValue = 255, sequence = 6)
                assertError(socket.getInputStream(), error = 16, opcode = 100, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 100, badValue = 0, sequence = 8)
                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 9, request = 1, firstKeycode = 40, count = 1)
                assertKeyboardMapping(readReply(socket.getInputStream()), sequence = 10, keysymsPerKeycode = 2, 0x0063, 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Pointer mapping remaps button events and reports busy while altered button is down`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val buttonMask = (1 shl 2) or (1 shl 3)
                out.write(createWindowRequest(WindowId, eventMask = buttonMask))
                out.write(mapWindowRequest(WindowId))
                val swapped = pointerMapping(1 to 3, 3 to 1)
                out.write(setPointerMappingRequest(*swapped))
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertMappingStatus(readReply(input), sequence = 3, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 3)

                val down = server.input.pointerDown(10, 10, button = 1)
                assertEquals(1, down.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 4, detail = 3)

                out.write(setPointerMappingRequest(*pointerMapping()))
                out.write(getPointerMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 4, status = 1)
                assertPointerMapping(readReply(input), 5, *swapped)

                val up = server.input.pointerUp(10, 10, button = 1)
                assertEquals(1, up.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 5, detail = 3)

                out.write(setPointerMappingRequest(*pointerMapping(1 to 0, 3 to 1)))
                out.flush()
                assertMappingStatus(readReply(input), sequence = 6, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 6)

                val disabled = server.input.click(10, 10, button = 1)
                assertEquals(0, disabled.deliveredEvents)
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
    fun `Input controller delivers high numbered pointer buttons`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val buttonMask = XEventMasks.ButtonPress or XEventMasks.ButtonRelease
                out.write(createWindowRequest(WindowId, eventMask = buttonMask))
                out.write(mapWindowRequest(WindowId))
                out.write(getPointerMappingRequest())
                out.flush()

                assertMapAndExpose(input, WindowId)
                assertPointerMapping(readReply(input), 3, *pointerMapping())

                val down = server.input.pointerDown(10, 10, button = 6)
                assertEquals(1, down.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 4, detail = 6)
                assertContains(httpGet(server.localPort, "/state.json"), """"logicalButtonsDown":[6]""")

                out.write(setPointerMappingRequest(*pointerMapping(6 to 7, 7 to 6)))
                out.write(getPointerMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 4, status = 1)
                assertPointerMapping(readReply(input), 5, *pointerMapping())

                val up = server.input.pointerUp(10, 10, button = 6)
                assertEquals(1, up.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 5, detail = 6)
                assertContains(httpGet(server.localPort, "/state.json"), """"logicalButtonsDown":[]""")

                out.write(setPointerMappingRequest(*pointerMapping(6 to 7, 7 to 6)))
                out.flush()
                assertMappingStatus(readReply(input), sequence = 6, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 6)
                val click = server.input.click(10, 10, button = 6)
                assertEquals(2, click.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 4, detail = 7)
                assertButtonEvent(input.readExactly(32), type = 5, detail = 7)

                out.write(setPointerMappingRequest(*pointerMapping(6 to 0)))
                out.flush()
                assertMappingStatus(readReply(input), sequence = 7, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 7)

                val disabled = server.input.click(10, 10, button = 6)
                assertEquals(0, disabled.deliveredEvents)
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
    fun `SetModifierMapping updates GetModifierMapping and validates failures`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(getModifierMappingRequest())
                out.write(setModifierMappingRequest(1, 50, 66, 37, 64, 77, 0, 133, 92))
                out.write(getModifierMappingRequest())
                out.write(setModifierMappingRequest(2, 50, 62, 66, 0, 37, 105, 64, 108, 77, 0, 0, 0, 133, 134, 92, 0))
                out.write(getModifierMappingRequest())
                out.write(setModifierMappingRequest(1, 7, 0, 37, 64, 77, 0, 133, 92))
                out.write(request(118, 1, ByteArray(4)))
                out.write(request(118, 1, ByteArray(12)))
                out.write(request(119, 0, ByteArray(4)))
                out.write(getModifierMappingRequest())
                out.flush()

                assertModifierMapping(readReply(socket.getInputStream()), sequence = 1, keycodesPerModifier = 2, 50, 62, 66, 0, 37, 105, 64, 108, 0, 0, 0, 0, 133, 134, 0, 0)
                assertMappingStatus(readReply(socket.getInputStream()), sequence = 2, status = 0)
                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 2, request = 0)
                assertModifierMapping(readReply(socket.getInputStream()), 3, 1, 50, 66, 37, 64, 77, 0, 133, 92)
                assertMappingStatus(readReply(socket.getInputStream()), sequence = 4, status = 0)
                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 4, request = 0)
                assertModifierMapping(readReply(socket.getInputStream()), 5, 2, 50, 62, 66, 0, 37, 105, 64, 108, 77, 0, 0, 0, 133, 134, 92, 0)

                assertError(socket.getInputStream(), error = 2, opcode = 118, badValue = 7, sequence = 6)
                assertError(socket.getInputStream(), error = 16, opcode = 118, badValue = 0, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 118, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 119, badValue = 0, sequence = 9)

                assertModifierMapping(readReply(socket.getInputStream()), 10, 2, 50, 62, 66, 0, 37, 105, 64, 108, 77, 0, 0, 0, 133, 134, 92, 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetModifierMapping reports busy while affected modifier keys are down`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val initial = intArrayOf(50, 66, 37, 64, 77, 0, 133, 92)
                val changed = intArrayOf(51, 66, 37, 64, 77, 0, 133, 92)
                val expanded = intArrayOf(50, 62, 66, 0, 37, 105, 64, 108, 77, 0, 0, 0, 133, 134, 92, 0)
                out.write(setModifierMappingRequest(1, *initial))
                out.flush()
                assertMappingStatus(readReply(input), sequence = 1, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 1, request = 0)

                server.input.keyDown(51)
                assertContains(httpGet(server.localPort, "/state.json"), """"keycodesDown":[51]""")
                out.write(setModifierMappingRequest(1, *changed))
                out.write(getModifierMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 2, status = 1)
                assertModifierMapping(readReply(input), sequence = 3, keycodesPerModifier = 1, *initial)

                server.input.keyUp(51)
                server.input.keyDown(50)
                assertContains(httpGet(server.localPort, "/state.json"), """"keycodesDown":[50]""")
                out.write(setModifierMappingRequest(2, *expanded))
                out.write(getModifierMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 4, status = 1)
                assertModifierMapping(readReply(input), sequence = 5, keycodesPerModifier = 1, *initial)

                out.write(setModifierMappingRequest(1, *changed))
                out.write(getModifierMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 6, status = 1)
                assertModifierMapping(readReply(input), sequence = 7, keycodesPerModifier = 1, *initial)

                server.input.keyUp(50)
                assertContains(httpGet(server.localPort, "/state.json"), """"keycodesDown":[]""")
                out.write(setModifierMappingRequest(1, *changed))
                out.write(getModifierMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 8, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 8, request = 0)
                assertModifierMapping(readReply(input), sequence = 9, keycodesPerModifier = 1, *changed)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetModifierMapping allows unchanged and unaffected modifiers while keys are down`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val initial = intArrayOf(50, 62, 66, 0, 37, 105, 64, 108, 77, 0, 0, 0, 133, 134, 92, 0)
                val reorderedShift = intArrayOf(62, 50, 66, 0, 37, 105, 64, 108, 77, 0, 0, 0, 133, 134, 92, 0)
                val changedControlOnly = intArrayOf(62, 50, 66, 0, 38, 105, 64, 108, 77, 0, 0, 0, 133, 134, 92, 0)
                out.write(setModifierMappingRequest(2, *initial))
                out.flush()
                assertMappingStatus(readReply(input), sequence = 1, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 1, request = 0)

                server.input.keyDown(50)
                out.write(setModifierMappingRequest(2, *reorderedShift))
                out.write(getModifierMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 2, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 2, request = 0)
                assertModifierMapping(readReply(input), sequence = 3, keycodesPerModifier = 2, *reorderedShift)

                out.write(setModifierMappingRequest(2, *changedControlOnly))
                out.write(getModifierMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 4, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 4, request = 0)
                assertModifierMapping(readReply(input), sequence = 5, keycodesPerModifier = 2, *changedControlOnly)

                server.input.keyUp(50)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangePointerControl updates selected fields and validates booleans and values`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(getPointerControlRequest())
                out.write(changePointerControlRequest(numerator = 3, denominator = 2, threshold = 9, doAcceleration = 1, doThreshold = 1))
                out.write(getPointerControlRequest())
                out.write(changePointerControlRequest(numerator = 7, denominator = 5, threshold = 4, doAcceleration = 0, doThreshold = 1))
                out.write(getPointerControlRequest())
                out.write(changePointerControlRequest(numerator = 8, denominator = 6, threshold = 99, doAcceleration = 1, doThreshold = 0))
                out.write(getPointerControlRequest())
                out.write(changePointerControlRequest(numerator = -2, denominator = 0, threshold = 11, doAcceleration = 0, doThreshold = 1))
                out.write(getPointerControlRequest())
                out.write(changePointerControlRequest(numerator = 9, denominator = 7, threshold = -2, doAcceleration = 1, doThreshold = 0))
                out.write(getPointerControlRequest())
                out.write(changePointerControlRequest(numerator = 1, denominator = 1, threshold = 0, doAcceleration = 2, doThreshold = 0))
                out.write(changePointerControlRequest(numerator = 1, denominator = 1, threshold = 0, doAcceleration = 0, doThreshold = 3))
                out.write(changePointerControlRequest(numerator = -2, denominator = 1, threshold = 0, doAcceleration = 1, doThreshold = 0))
                out.write(changePointerControlRequest(numerator = 1, denominator = 0, threshold = 0, doAcceleration = 1, doThreshold = 0))
                out.write(changePointerControlRequest(numerator = 1, denominator = -2, threshold = 0, doAcceleration = 1, doThreshold = 0))
                out.write(changePointerControlRequest(numerator = 1, denominator = 1, threshold = -2, doAcceleration = 0, doThreshold = 1))
                out.write(request(105, 0, ByteArray(4)))
                out.write(request(106, 0, ByteArray(4)))
                out.write(getPointerControlRequest())
                out.write(changePointerControlRequest(numerator = -1, denominator = -1, threshold = -1, doAcceleration = 1, doThreshold = 1))
                out.write(getPointerControlRequest())
                out.flush()

                assertPointerControl(readReply(socket.getInputStream()), sequence = 1, numerator = 2, denominator = 1, threshold = 4)
                assertPointerControl(readReply(socket.getInputStream()), sequence = 3, numerator = 3, denominator = 2, threshold = 9)
                assertPointerControl(readReply(socket.getInputStream()), sequence = 5, numerator = 3, denominator = 2, threshold = 4)
                assertPointerControl(readReply(socket.getInputStream()), sequence = 7, numerator = 8, denominator = 6, threshold = 4)
                assertPointerControl(readReply(socket.getInputStream()), sequence = 9, numerator = 8, denominator = 6, threshold = 11)
                assertPointerControl(readReply(socket.getInputStream()), sequence = 11, numerator = 9, denominator = 7, threshold = 11)

                assertError(socket.getInputStream(), error = 2, opcode = 105, badValue = 2, sequence = 12)
                assertError(socket.getInputStream(), error = 2, opcode = 105, badValue = 3, sequence = 13)
                assertError(socket.getInputStream(), error = 2, opcode = 105, badValue = -2, sequence = 14)
                assertError(socket.getInputStream(), error = 2, opcode = 105, badValue = 0, sequence = 15)
                assertError(socket.getInputStream(), error = 2, opcode = 105, badValue = -2, sequence = 16)
                assertError(socket.getInputStream(), error = 2, opcode = 105, badValue = -2, sequence = 17)
                assertError(socket.getInputStream(), error = 16, opcode = 105, badValue = 0, sequence = 18)
                assertError(socket.getInputStream(), error = 16, opcode = 106, badValue = 0, sequence = 19)

                assertPointerControl(readReply(socket.getInputStream()), sequence = 20, numerator = 9, denominator = 7, threshold = 11)
                assertPointerControl(readReply(socket.getInputStream()), sequence = 22, numerator = 2, denominator = 1, threshold = 4)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetScreenSaver updates GetScreenSaver reply and restores defaults`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(getScreenSaverRequest())
                out.write(setScreenSaverRequest(timeout = 12, interval = 34, preferBlanking = 1, allowExposures = 0))
                out.write(getScreenSaverRequest())
                out.write(setScreenSaverRequest(timeout = -1, interval = -1, preferBlanking = 2, allowExposures = 2))
                out.write(getScreenSaverRequest())
                out.flush()

                val initial = readReply(socket.getInputStream())
                assertEquals(1, u16le(initial, 2))
                assertEquals(0, u16le(initial, 8))
                assertEquals(0, u16le(initial, 10))
                assertEquals(0, initial[12].toInt() and 0xff)
                assertEquals(0, initial[13].toInt() and 0xff)

                val updated = readReply(socket.getInputStream())
                assertEquals(3, u16le(updated, 2))
                assertEquals(12, u16le(updated, 8))
                assertEquals(34, u16le(updated, 10))
                assertEquals(1, updated[12].toInt() and 0xff)
                assertEquals(0, updated[13].toInt() and 0xff)

                val defaults = readReply(socket.getInputStream())
                assertEquals(5, u16le(defaults, 2))
                assertEquals(0, u16le(defaults, 8))
                assertEquals(0, u16le(defaults, 10))
                assertEquals(0, defaults[12].toInt() and 0xff)
                assertEquals(0, defaults[13].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetScreenSaver rejects invalid values without changing state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(setScreenSaverRequest(timeout = 12, interval = 34, preferBlanking = 1, allowExposures = 0))
                out.write(setScreenSaverRequest(timeout = -2, interval = 34, preferBlanking = 1, allowExposures = 0))
                out.write(setScreenSaverRequest(timeout = 12, interval = -2, preferBlanking = 1, allowExposures = 0))
                out.write(setScreenSaverRequest(timeout = 12, interval = 34, preferBlanking = 3, allowExposures = 0))
                out.write(setScreenSaverRequest(timeout = 12, interval = 34, preferBlanking = 1, allowExposures = 3))
                out.write(getScreenSaverRequest())
                out.flush()

                val timeoutError = socket.getInputStream().readExactly(32)
                assertEquals(0, timeoutError[0].toInt())
                assertEquals(2, timeoutError[1].toInt() and 0xff)
                assertEquals(2, u16le(timeoutError, 2))
                assertEquals(-2, u32le(timeoutError, 4))
                assertEquals(107, timeoutError[10].toInt() and 0xff)

                val intervalError = socket.getInputStream().readExactly(32)
                assertEquals(0, intervalError[0].toInt())
                assertEquals(2, intervalError[1].toInt() and 0xff)
                assertEquals(3, u16le(intervalError, 2))
                assertEquals(-2, u32le(intervalError, 4))
                assertEquals(107, intervalError[10].toInt() and 0xff)

                val preferError = socket.getInputStream().readExactly(32)
                assertEquals(0, preferError[0].toInt())
                assertEquals(2, preferError[1].toInt() and 0xff)
                assertEquals(4, u16le(preferError, 2))
                assertEquals(3, u32le(preferError, 4))
                assertEquals(107, preferError[10].toInt() and 0xff)

                val exposuresError = socket.getInputStream().readExactly(32)
                assertEquals(0, exposuresError[0].toInt())
                assertEquals(2, exposuresError[1].toInt() and 0xff)
                assertEquals(5, u16le(exposuresError, 2))
                assertEquals(3, u32le(exposuresError, 4))
                assertEquals(107, exposuresError[10].toInt() and 0xff)

                val reply = readReply(socket.getInputStream())
                assertEquals(6, u16le(reply, 2))
                assertEquals(12, u16le(reply, 8))
                assertEquals(34, u16le(reply, 10))
                assertEquals(1, reply[12].toInt() and 0xff)
                assertEquals(0, reply[13].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetScreenSaver validates length and preserves connection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(setScreenSaverRequest(timeout = 12, interval = 34, preferBlanking = 1, allowExposures = 0))
                out.write(request(108, 0, ByteArray(4)))
                out.write(getScreenSaverRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 108, badValue = 0, sequence = 2)

                val screenSaver = readReply(socket.getInputStream())
                assertEquals(3, u16le(screenSaver, 2))
                assertEquals(0, u32le(screenSaver, 4))
                assertEquals(12, u16le(screenSaver, 8))
                assertEquals(34, u16le(screenSaver, 10))
                assertEquals(1, screenSaver[12].toInt() and 0xff)
                assertEquals(0, screenSaver[13].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ForceScreenSaver validates mode and length and preserves connection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(forceScreenSaverRequest(0))
                out.write(forceScreenSaverRequest(1))
                out.write(forceScreenSaverRequest(2))
                out.write(request(115, 0, ByteArray(4)))
                out.write(getScreenSaverRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 115, badValue = 2, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 115, badValue = 0, sequence = 4)

                val screenSaver = readReply(socket.getInputStream())
                assertEquals(5, u16le(screenSaver, 2))
                assertEquals(0, u32le(screenSaver, 4))
                assertEquals(0, u16le(screenSaver, 8))
                assertEquals(0, u16le(screenSaver, 10))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `Access control hosts round trip through ChangeHosts ListHosts and SetAccessControl`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val ipv4 = AccessHost(0, listOf(127, 0, 0, 1))
                val serverInterpreted = AccessHost(5, "localuser\u0000jonny".encodeToByteArray().map { it.toInt() and 0xff })
                val ipv6 = AccessHost(6, listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))

                out.write(listHostsRequest())
                out.write(setAccessControlRequest(1))
                out.write(listHostsRequest())
                out.write(changeHostsRequest(0, ipv4))
                out.write(changeHostsRequest(0, serverInterpreted))
                out.write(changeHostsRequest(0, ipv6))
                out.write(listHostsRequest())
                out.write(changeHostsRequest(1, ipv4))
                out.write(listHostsRequest())
                out.flush()

                assertListHosts(readReply(socket.getInputStream()), sequence = 1, enabled = false)
                assertListHosts(readReply(socket.getInputStream()), sequence = 3, enabled = true)
                assertListHosts(readReply(socket.getInputStream()), sequence = 7, enabled = true, ipv4, serverInterpreted, ipv6)
                assertListHosts(readReply(socket.getInputStream()), sequence = 9, enabled = true, serverInterpreted, ipv6)

                assertSetupFailure(server.localPort)

                out.write(changeHostsRequest(0, ipv4))
                out.flush()
                assertSetupSuccess(server.localPort)

                out.write(setAccessControlRequest(0))
                out.write(listHostsRequest())
                out.write(setAccessControlRequest(2))
                out.write(request(111, 0, ByteArray(4)))
                out.write(request(110, 0, ByteArray(4)))
                out.write(changeHostsBadLengthRequest())
                out.write(changeHostsRequest(2, ipv4))
                out.write(changeHostsRequest(0, AccessHost(4, listOf(1, 2, 3, 4))))
                out.write(changeHostsRequest(0, AccessHost(0, listOf(127, 0, 1))))
                out.write(changeHostsRequest(0, AccessHost(5, "localuser".encodeToByteArray().map { it.toInt() and 0xff })))
                out.write(listHostsRequest())
                out.flush()

                assertListHosts(readReply(socket.getInputStream()), sequence = 12, enabled = false, serverInterpreted, ipv6, ipv4)

                assertError(socket.getInputStream(), error = 2, opcode = 111, badValue = 2, sequence = 13)
                assertError(socket.getInputStream(), error = 16, opcode = 111, badValue = 0, sequence = 14)
                assertError(socket.getInputStream(), error = 16, opcode = 110, badValue = 0, sequence = 15)
                assertError(socket.getInputStream(), error = 16, opcode = 109, badValue = 0, sequence = 16)
                assertError(socket.getInputStream(), error = 2, opcode = 109, badValue = 2, sequence = 17)
                assertError(socket.getInputStream(), error = 2, opcode = 109, badValue = 4, sequence = 18)
                assertError(socket.getInputStream(), error = 2, opcode = 109, badValue = 3, sequence = 19)
                assertError(socket.getInputStream(), error = 2, opcode = 109, badValue = 5, sequence = 20)

                assertListHosts(readReply(socket.getInputStream()), sequence = 21, enabled = false, serverInterpreted, ipv6, ipv4)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow restacks occluding mapped children and validates failures`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val first = WindowId + 100
                val second = WindowId + 101
                val third = WindowId + 102
                val nested = WindowId + 103
                val missing = WindowId + 199

                out.write(createWindowRequest(first, x = 0, y = 0, width = 30, height = 30))
                out.write(createWindowRequest(nested, parent = first, x = 1, y = 1, width = 10, height = 10))
                out.write(createWindowRequest(second, x = 10, y = 10, width = 30, height = 30))
                out.write(createWindowRequest(third, x = 80, y = 80, width = 20, height = 20))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(nested))
                out.write(mapWindowRequest(second))
                out.write(mapWindowRequest(third))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                out.write(circulateWindowRequest(0, X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(circulateWindowRequest(1, X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(circulateWindowRequest(0, third))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.write(circulateWindowRequest(2, X11Ids.RootWindow))
                out.write(request(13, 0, ByteArray(8)))
                out.write(circulateWindowRequest(0, missing))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertMapAndExpose(input, first)
                assertMapAndExpose(input, nested)
                assertMapAndExpose(input, second)
                assertMapAndExpose(input, third)
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                assertCirculateNotify(input.readExactly(32), sequence = 11, eventWindow = X11Ids.RootWindow, window = first, place = 0)
                assertEquals(listOf(second, third, first), treeChildren(readReply(input)))
                assertCirculateNotify(input.readExactly(32), sequence = 13, eventWindow = X11Ids.RootWindow, window = first, place = 1)
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                val json = httpGet(server.localPort, "/state.json")
                assertEquals(
                    true,
                    json.indexOf(windowJsonId(first)) < json.indexOf(windowJsonId(nested)),
                    "CirculateWindow should keep a restacked window's descendants after the parent in snapshot/render order",
                )
                assertError(input, error = 2, opcode = 13, badValue = 2, sequence = 17)
                assertError(input, error = 16, opcode = 13, badValue = 0, sequence = 18)
                assertError(input, error = 3, opcode = 13, badValue = missing, sequence = 19)
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow exposes raised lowest mapped child`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val first = WindowId + 110
                val second = WindowId + 111

                out.write(createWindowRequest(first, x = 0, y = 0, width = 30, height = 30, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(second, x = 10, y = 10, width = 30, height = 30))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(second))
                out.write(circulateWindowRequest(XCirculateResult.RaiseLowest, X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertMapAndExpose(input, first)
                assertMapAndExpose(input, second)
                assertExpose(input.readExactly(32), first, sequence = 5, width = 30, height = 30, count = 0)
                assertEquals(listOf(second, first), treeChildren(readReply(input)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow exposes sibling uncovered by lowered highest child`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val first = WindowId + 112
                val second = WindowId + 113
                val third = WindowId + 114

                out.write(createWindowRequest(first, x = 0, y = 0, width = 30, height = 30))
                out.write(createWindowRequest(second, x = 10, y = 10, width = 30, height = 30, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(third, x = 12, y = 12, width = 10, height = 10))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(second))
                out.write(mapWindowRequest(third))
                out.write(circulateWindowRequest(XCirculateResult.LowerHighest, X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertMapAndExpose(input, first)
                assertMapAndExpose(input, second)
                assertMapAndExpose(input, third)
                assertExpose(input.readExactly(32), second, sequence = 7, width = 30, height = 30, count = 0)
                assertEquals(listOf(third, first, second), treeChildren(readReply(input)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow exposes topmost lower owner when lowering highest child`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val bottom = WindowId + 117
                val middle = WindowId + 118
                val top = WindowId + 119

                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.Exposure))
                out.write(createWindowRequest(bottom, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(middle, x = 10, y = 10, width = 20, height = 20, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(top, x = 10, y = 10, width = 20, height = 20))
                out.write(mapWindowRequest(bottom))
                out.write(mapWindowRequest(middle))
                out.write(mapWindowRequest(top))
                out.write(circulateWindowRequest(XCirculateResult.LowerHighest, X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertMapAndExpose(input, bottom)
                assertMapAndExpose(input, middle)
                assertMapAndExpose(input, top)
                assertExpose(input.readExactly(32), middle, sequence = 8, width = 20, height = 20, count = 0)
                assertEquals(listOf(top, bottom, middle), treeChildren(readReply(input)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow exposes descendants uncovered by lowered highest child`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val lower = WindowId + 120
                val lowerChild = WindowId + 121
                val top = WindowId + 122

                out.write(createWindowRequest(lower, x = 0, y = 0, width = 40, height = 40, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(lowerChild, parent = lower, x = 15, y = 15, width = 10, height = 10, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(top, x = 10, y = 10, width = 30, height = 30))
                out.write(mapWindowRequest(lower))
                out.write(mapWindowRequest(lowerChild))
                out.write(mapWindowRequest(top))
                out.write(circulateWindowRequest(XCirculateResult.LowerHighest, X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertMapAndExpose(input, lower)
                assertMapAndExpose(input, lowerChild)
                assertMapAndExpose(input, top)
                assertExpose(input.readExactly(32), lower, sequence = 7, width = 40, height = 40, count = 0)
                assertExpose(input.readExactly(32), lowerChild, sequence = 7, width = 10, height = 10, count = 0)
                assertEquals(listOf(top, lower), treeChildren(readReply(input)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow exposes descendants of raised lowest child`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val first = WindowId + 123
                val child = WindowId + 124
                val second = WindowId + 125

                out.write(createWindowRequest(first, x = 0, y = 0, width = 40, height = 40, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(child, parent = first, x = 15, y = 15, width = 10, height = 10, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(second, x = 10, y = 10, width = 30, height = 30))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(child))
                out.write(mapWindowRequest(second))
                out.write(circulateWindowRequest(XCirculateResult.RaiseLowest, X11Ids.RootWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertMapAndExpose(input, first)
                assertMapAndExpose(input, child)
                assertMapAndExpose(input, second)
                assertExpose(input.readExactly(32), first, sequence = 7, width = 40, height = 40, count = 0)
                assertExpose(input.readExactly(32), child, sequence = 7, width = 10, height = 10, count = 0)
                assertEquals(listOf(second, first), treeChildren(readReply(input)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow does not expose siblings when lowered highest child is InputOnly`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                val first = WindowId + 115
                val inputOnly = WindowId + 116

                out.write(createWindowRequest(first, x = 0, y = 0, width = 30, height = 30, eventMask = XEventMasks.Exposure))
                out.write(createWindowRequest(inputOnly, x = 10, y = 10, width = 30, height = 30, depth = 0, windowClass = XWindowClass.InputOnly))
                out.write(mapWindowRequest(first))
                out.write(mapWindowRequest(inputOnly))
                out.write(circulateWindowRequest(XCirculateResult.LowerHighest, X11Ids.RootWindow))
                out.write(queryPointerRequest())
                out.flush()

                assertMapAndExpose(input, first)
                val pointer = input.readExactly(32)
                assertEquals(1, pointer[0].toInt() and 0xff)
                assertEquals(6, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow delivers CirculateRequest to parent SubstructureRedirect without restacking`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val first = WindowId + 436
                    val second = WindowId + 437
                    val third = WindowId + 438
                    val ownerOut = ownerSocket.getOutputStream()
                    val ownerInput = ownerSocket.getInputStream()
                    ownerOut.write(createWindowRequest(first, x = 0, y = 0, width = 30, height = 30))
                    ownerOut.write(createWindowRequest(second, x = 10, y = 10, width = 30, height = 30))
                    ownerOut.write(createWindowRequest(third, x = 80, y = 80, width = 20, height = 20))
                    ownerOut.write(mapWindowRequest(first))
                    ownerOut.write(mapWindowRequest(second))
                    ownerOut.write(mapWindowRequest(third))
                    ownerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    ownerOut.flush()

                    assertMapAndExpose(ownerInput, first)
                    assertMapAndExpose(ownerInput, second)
                    assertMapAndExpose(ownerInput, third)
                    assertEquals(listOf(first, second, third), treeChildren(readReply(ownerInput)))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureRedirect))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(circulateWindowRequest(XCirculateResult.LowerHighest, X11Ids.RootWindow))
                    ownerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    ownerOut.flush()

                    assertCirculateRequest(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        parent = X11Ids.RootWindow,
                        window = second,
                        place = XCirculateResult.Bottom,
                    )
                    assertEquals(listOf(first, second, third), treeChildren(readReply(ownerInput)))

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `CirculateWindow delivers selected structure notifications without requester local event`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)

                    val first = WindowId + 420
                    val second = WindowId + 421
                    val third = WindowId + 422
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(first, x = 0, y = 0, width = 30, height = 30))
                    ownerOut.write(createWindowRequest(second, x = 10, y = 10, width = 30, height = 30))
                    ownerOut.write(createWindowRequest(third, x = 80, y = 80, width = 20, height = 20))
                    ownerOut.write(mapWindowRequest(first))
                    ownerOut.write(mapWindowRequest(second))
                    ownerOut.write(mapWindowRequest(third))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertMapAndExpose(ownerSocket.getInputStream(), first)
                    assertMapAndExpose(ownerSocket.getInputStream(), second)
                    assertMapAndExpose(ownerSocket.getInputStream(), third)
                    assertEquals(7, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(first, XEventMasks.StructureNotify))
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))

                    ownerOut.write(circulateWindowRequest(0, X11Ids.RootWindow))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()

                    assertEquals(9, u16le(readReply(ownerSocket.getInputStream()), 2))
                    assertCirculateNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = first,
                        window = first,
                        place = 0,
                    )
                    assertCirculateNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 3,
                        eventWindow = X11Ids.RootWindow,
                        window = first,
                        place = 0,
                    )

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(4, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES ChangeSaveSet supports root target and unmap processing`() {
        XServer(ServerOptions(port = 0, width = 160, height = 120)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { appSocket ->
                Socket("127.0.0.1", server.localPort).use { frameSocket ->
                    appSocket.soTimeout = 2_000
                    frameSocket.soTimeout = 2_000
                    setup(appSocket)
                    setup(frameSocket)

                    val middle = WindowId + 90
                    val appWindow = WindowId + 91
                    val appOut = appSocket.getOutputStream()
                    appOut.write(createWindowRequest(middle, x = 5, y = 6, width = 70, height = 55))
                    appOut.write(createWindowRequest(appWindow, parent = middle, x = 7, y = 8, width = 20, height = 15))
                    appOut.write(mapWindowRequest(middle))
                    appOut.write(mapWindowRequest(appWindow))
                    appOut.write(setInputFocusRequest(appWindow, revertTo = 0))
                    appOut.write(changeWindowEventMaskRequest(appWindow, XEventMasks.FocusChange))
                    appOut.flush()
                    val appInput = appSocket.getInputStream()
                    assertMapAndExpose(appInput, middle)
                    assertMapAndExpose(appInput, appWindow)

                    val frame = WindowId + 92
                    val frameOut = frameSocket.getOutputStream()
                    frameOut.write(createWindowRequest(frame, x = 10, y = 10, width = 90, height = 70))
                    frameOut.write(reparentWindowRequest(middle, frame, x = 5, y = 6))
                    frameOut.write(
                        xfixesChangeSaveSetRequest(
                            mode = 0,
                            target = XFixes.SaveSetRoot,
                            map = XFixes.SaveSetUnmap,
                            window = appWindow,
                        ),
                    )
                    frameOut.write(queryPointerRequest())
                    frameOut.flush()
                    assertEquals(4, u16le(readReply(frameSocket.getInputStream()), 2))
                    closeClientAndWait(frameSocket)

                    assertFocusEvent(appInput.readExactly(32), type = 10, sequence = 6, window = appWindow)
                    assertEquals(listOf(appWindow), waitForRootChildren(server.localPort) { it == listOf(appWindow) })
                    val json = httpGet(server.localPort, "/state.json")
                    val windowJson = json.substringAfter(windowJsonId(appWindow)).substringBefore("}")
                    assertContains(
                        json,
                        windowJsonId(appWindow) + ""","parent":"${X11Ids.RootWindow.toJsonHex()}","x":22,"y":24,"localX":22,"localY":24,"width":20,"height":15""",
                    )
                    assertContains(windowJson, """"mapped":false""")
                    assertFalse(json.contains(windowJsonId(middle)))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeSaveSet reparents and maps saved windows when frame owner closes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { appSocket ->
                appSocket.soTimeout = 2_000
                setup(appSocket)
                val appWindow = WindowId + 200
                appSocket.getOutputStream().write(createWindowRequest(appWindow, width = 20, height = 15))
                appSocket.getOutputStream().flush()

                val frameWindow = WindowId + 201
                Socket("127.0.0.1", server.localPort).use { frameSocket ->
                    frameSocket.soTimeout = 2_000
                    setup(frameSocket)
                    val out = frameSocket.getOutputStream()
                    out.write(createWindowRequest(frameWindow, x = 10, y = 10, width = 50, height = 40))
                    out.write(reparentWindowRequest(appWindow, frameWindow, x = 7, y = 8))
                    out.write(changeSaveSetRequest(0, appWindow))
                    out.flush()
                    closeClientAndWait(frameSocket)
                }

                assertEquals(listOf(appWindow), waitForRootChildren(server.localPort) { it == listOf(appWindow) })
                val json = httpGet(server.localPort, "/state.json")
                val windowJson = json.substringAfter(windowJsonId(appWindow)).substringBefore("}")
                assertContains(
                    json,
                    windowJsonId(appWindow) + ""","parent":"${X11Ids.RootWindow.toJsonHex()}","x":17,"y":18,"localX":17,"localY":18,"width":20,"height":15""",
                )
                assertContains(windowJson, """"mapped":true""")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeSaveSet delete removes saved window from close processing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { appSocket ->
                appSocket.soTimeout = 2_000
                setup(appSocket)
                val appWindow = WindowId + 210
                appSocket.getOutputStream().write(createWindowRequest(appWindow, width = 20, height = 15))
                appSocket.getOutputStream().flush()

                val frameWindow = WindowId + 211
                Socket("127.0.0.1", server.localPort).use { frameSocket ->
                    frameSocket.soTimeout = 2_000
                    setup(frameSocket)
                    val out = frameSocket.getOutputStream()
                    out.write(createWindowRequest(frameWindow, x = 10, y = 10, width = 50, height = 40))
                    out.write(reparentWindowRequest(appWindow, frameWindow, x = 7, y = 8))
                    out.write(changeSaveSetRequest(0, appWindow))
                    out.write(changeSaveSetRequest(1, appWindow))
                    out.flush()
                    closeClientAndWait(frameSocket)
                }

                assertEquals(emptyList(), waitForRootChildren(server.localPort) { it.isEmpty() })
                assertFalse(httpGet(server.localPort, "/state.json").contains(windowJsonId(appWindow)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeSaveSet reparents past non-owned ancestors under closing client windows`() {
        XServer(ServerOptions(port = 0, width = 160, height = 120)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { appSocket ->
                appSocket.soTimeout = 2_000
                setup(appSocket)
                val middle = WindowId + 220
                val appWindow = WindowId + 221
                val out = appSocket.getOutputStream()
                out.write(createWindowRequest(middle, x = 5, y = 6, width = 60, height = 45))
                out.write(createWindowRequest(appWindow, parent = middle, x = 7, y = 8, width = 20, height = 15))
                out.flush()

                val frameWindow = WindowId + 222
                Socket("127.0.0.1", server.localPort).use { frameSocket ->
                    frameSocket.soTimeout = 2_000
                    setup(frameSocket)
                    val frameOut = frameSocket.getOutputStream()
                    frameOut.write(createWindowRequest(frameWindow, x = 10, y = 10, width = 90, height = 70))
                    frameOut.write(reparentWindowRequest(middle, frameWindow, x = 5, y = 6))
                    frameOut.write(changeSaveSetRequest(0, appWindow))
                    frameOut.flush()
                    closeClientAndWait(frameSocket)
                }

                assertEquals(listOf(appWindow), waitForRootChildren(server.localPort) { it == listOf(appWindow) })
                val json = httpGet(server.localPort, "/state.json")
                assertContains(
                    json,
                    windowJsonId(appWindow) + ""","parent":"${X11Ids.RootWindow.toJsonHex()}","x":22,"y":24,"localX":22,"localY":24,"width":20,"height":15""",
                )
                assertFalse(json.contains(windowJsonId(middle)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeSaveSet validates mode length window and ownership without side effects`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 299
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changeSaveSetRequest(2, WindowId))
                out.write(request(6, 0, ByteArray(8)))
                out.write(changeSaveSetRequest(0, missing))
                out.write(changeSaveSetRequest(0, WindowId))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 6, badValue = 2, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 6, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 3, opcode = 6, badValue = missing, sequence = 4)
                assertError(socket.getInputStream(), error = 8, opcode = 6, badValue = 0, sequence = 5)
                assertEquals(listOf(WindowId), treeChildren(readReply(socket.getInputStream())))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetCloseDownMode validates mode and length and preserves connection`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(setCloseDownModeRequest(0))
                out.write(setCloseDownModeRequest(1))
                out.write(setCloseDownModeRequest(2))
                out.write(setCloseDownModeRequest(3))
                out.write(request(112, 0, ByteArray(4)))
                out.write(getScreenSaverRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 112, badValue = 3, sequence = 4)
                assertError(socket.getInputStream(), error = 16, opcode = 112, badValue = 0, sequence = 5)

                val screenSaver = readReply(socket.getInputStream())
                assertEquals(6, u16le(screenSaver, 2))
                assertEquals(0, u32le(screenSaver, 4))
                assertEquals(0, u16le(screenSaver, 8))
                assertEquals(0, u16le(screenSaver, 10))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetCloseDownMode retain modes preserve resources across disconnect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val retainedWindows = listOf(1 to WindowId + 20, 2 to WindowId + 22)
            val transientWindow = WindowId + 21

            for ((mode, retainedWindow) in retainedWindows) {
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 2_000
                    setup(socket)
                    val out = socket.getOutputStream()
                    out.write(createWindowRequest(retainedWindow))
                    out.write(setCloseDownModeRequest(mode))
                    out.write(queryTreeRequest(X11Ids.RootWindow))
                    out.flush()

                    val beforeClose = treeChildren(readReply(socket.getInputStream()))
                    assertTrue(retainedWindow in beforeClose)
                    closeClientAndWait(socket)
                }

                val retainedChildren = waitForRootChildren(server.localPort) { retainedWindow in it }
                assertTrue(retainedWindow in retainedChildren)
            }

            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(transientWindow))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                val beforeClose = treeChildren(readReply(socket.getInputStream()))
                assertTrue(transientWindow in beforeClose)
                closeClientAndWait(socket)
            }

            val finalChildren = waitForRootChildren(server.localPort) {
                retainedWindows.all { (_, retainedWindow) -> retainedWindow in it } && transientWindow !in it
            }
            for ((_, retainedWindow) in retainedWindows) {
                assertTrue(retainedWindow in finalChildren)
            }
            assertFalse(transientWindow in finalChildren)
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `setup assigns distinct resource id bases so client close down does not collide`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { firstSocket ->
                Socket("127.0.0.1", server.localPort).use { secondSocket ->
                    firstSocket.soTimeout = 2_000
                    secondSocket.soTimeout = 2_000
                    val firstSetup = setupReply(firstSocket)
                    val secondSetup = setupReply(secondSocket)
                    val firstBase = u32le(firstSetup, 12)
                    val secondBase = u32le(secondSetup, 12)
                    assertEquals(X11Ids.ResourceIdMask, u32le(firstSetup, 16))
                    assertEquals(X11Ids.ResourceIdMask, u32le(secondSetup, 16))
                    assertNotEquals(firstBase, secondBase)

                    val firstWindow = firstBase or 1
                    val secondWindow = secondBase or 1
                    val firstOut = firstSocket.getOutputStream()
                    firstOut.write(createWindowRequest(firstWindow))
                    firstOut.write(queryTreeRequest(X11Ids.RootWindow))
                    firstOut.flush()
                    assertTrue(firstWindow in treeChildren(readReply(firstSocket.getInputStream())))

                    val secondOut = secondSocket.getOutputStream()
                    secondOut.write(createWindowRequest(secondWindow))
                    secondOut.write(changePropertyRequest(secondWindow, PrimaryAtom, StringAtom, "one"))
                    secondOut.write(queryTreeRequest(X11Ids.RootWindow))
                    secondOut.flush()
                    assertTrue(secondWindow in treeChildren(readReply(secondSocket.getInputStream())))

                    closeClientAndWait(firstSocket)

                    secondOut.write(changePropertyRequest(secondWindow, PrimaryAtom, StringAtom, "two"))
                    secondOut.write(getPropertyRequest(secondWindow, PrimaryAtom, StringAtom))
                    secondOut.flush()
                    assertPropertyReply(readReply(secondSocket.getInputStream()), sequence = 5, type = StringAtom, value = "two")
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `client disconnect delivers DestroyNotify to clients selecting parent`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)
                    val window = WindowId + 420
                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(createWindowRequest(window))
                    ownerOut.write(queryPointerRequest())
                    ownerOut.flush()
                    assertEquals(2, u16le(readReply(ownerSocket.getInputStream()), 2))

                    val observerOut = observerSocket.getOutputStream()
                    observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                    closeClientAndWait(ownerSocket)

                    observerOut.write(queryPointerRequest())
                    observerOut.flush()
                    assertDestroyNotify(
                        observerSocket.getInputStream().readExactly(32),
                        sequence = 2,
                        eventWindow = X11Ids.RootWindow,
                        window = window,
                    )
                    assertEquals(3, u16le(readReply(observerSocket.getInputStream()), 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `KillClient validates request length and resource id without closing caller`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missing = WindowId + 300
                val out = socket.getOutputStream()
                out.write(request(113, 0, ByteArray(0)))
                out.write(killClientRequest(missing))
                out.write(queryPointerRequest())
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 113, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 2, opcode = 113, badValue = missing, sequence = 2)
                val pointer = readReply(socket.getInputStream())
                assertEquals(3, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `KillClient retained resource delivers DestroyNotify to clients selecting parent`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val window = WindowId + 422
            Socket("127.0.0.1", server.localPort).use { retainedSocket ->
                retainedSocket.soTimeout = 2_000
                setup(retainedSocket)
                val retainedOut = retainedSocket.getOutputStream()
                retainedOut.write(createWindowRequest(window))
                retainedOut.write(setCloseDownModeRequest(XCloseDownMode.RetainTemporary))
                retainedOut.write(queryPointerRequest())
                retainedOut.flush()
                assertEquals(3, u16le(readReply(retainedSocket.getInputStream()), 2))
                closeClientAndWait(retainedSocket)
            }

            waitForRootChildren(server.localPort) { window in it }

            Socket("127.0.0.1", server.localPort).use { observerSocket ->
                observerSocket.soTimeout = 2_000
                setup(observerSocket)
                val observerOut = observerSocket.getOutputStream()
                observerOut.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.SubstructureNotify))
                observerOut.write(queryPointerRequest())
                observerOut.flush()
                assertEquals(2, u16le(readReply(observerSocket.getInputStream()), 2))

                observerOut.write(killClientRequest(window))
                observerOut.write(queryPointerRequest())
                observerOut.flush()
                assertDestroyNotify(
                    observerSocket.getInputStream().readExactly(32),
                    sequence = 3,
                    eventWindow = X11Ids.RootWindow,
                    window = window,
                )
                assertEquals(4, u16le(readReply(observerSocket.getInputStream()), 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `KillClient closes live destroy-mode client before later requests observe resources`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val victimWindow = WindowId + 30
            Socket("127.0.0.1", server.localPort).use { victimSocket ->
                victimSocket.soTimeout = 2_000
                setup(victimSocket)
                val victimOut = victimSocket.getOutputStream()
                victimOut.write(createWindowRequest(victimWindow))
                victimOut.write(queryTreeRequest(X11Ids.RootWindow))
                victimOut.flush()
                assertTrue(victimWindow in treeChildren(readReply(victimSocket.getInputStream())))

                Socket("127.0.0.1", server.localPort).use { killerSocket ->
                    killerSocket.soTimeout = 2_000
                    setup(killerSocket)
                    val killerOut = killerSocket.getOutputStream()
                    killerOut.write(killClientRequest(victimWindow))
                    killerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    killerOut.flush()

                    val children = treeChildren(readReply(killerSocket.getInputStream()))
                    assertFalse(victimWindow in children)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `KillClient prevents killed client request queued behind GrabServer from dispatching`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { victimSocket ->
                    ownerSocket.soTimeout = 2_000
                    victimSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(victimSocket)
                    val victimWindow = WindowId + 31
                    val victimOut = victimSocket.getOutputStream()
                    victimOut.write(createWindowRequest(victimWindow))
                    victimOut.write(queryTreeRequest(X11Ids.RootWindow))
                    victimOut.flush()
                    assertTrue(victimWindow in treeChildren(readReply(victimSocket.getInputStream())))

                    val ownerOut = ownerSocket.getOutputStream()
                    ownerOut.write(grabServerRequest())
                    ownerOut.flush()
                    waitForStateContains(server.localPort, """"serverGrabbed":true""")

                    victimOut.write(changePropertyRequest(X11Ids.RootWindow, PrimaryAtom, StringAtom, "stale"))
                    victimOut.write(queryPointerRequest())
                    victimOut.flush()
                    victimSocket.soTimeout = 300
                    assertFailsWith<SocketTimeoutException> {
                        readReply(victimSocket.getInputStream())
                    }

                    ownerOut.write(killClientRequest(victimWindow))
                    ownerOut.write(ungrabServerRequest())
                    ownerOut.write(getPropertyRequest(X11Ids.RootWindow, PrimaryAtom, StringAtom))
                    ownerOut.flush()

                    val property = readReply(ownerSocket.getInputStream())
                    assertEquals(4, u16le(property, 2))
                    assertEquals(0, u32le(property, 8))
                    assertEquals(0, u32le(property, 16))
                    waitForRootChildren(server.localPort) { victimWindow !in it }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `KillClient destroys retained clients by resource and AllTemporary`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val permanentWindow = WindowId + 32
            val temporaryWindow = WindowId + 34

            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(permanentWindow))
                out.write(setCloseDownModeRequest(1))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()
                assertTrue(permanentWindow in treeChildren(readReply(socket.getInputStream())))
                closeClientAndWait(socket)
            }

            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(temporaryWindow))
                out.write(setCloseDownModeRequest(2))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()
                assertTrue(temporaryWindow in treeChildren(readReply(socket.getInputStream())))
                closeClientAndWait(socket)
            }

            waitForRootChildren(server.localPort) { permanentWindow in it && temporaryWindow in it }

            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(killClientRequest(permanentWindow))
                out.write(killClientRequest(0))
                out.write(queryTreeRequest(X11Ids.RootWindow))
                out.flush()

                val children = treeChildren(readReply(socket.getInputStream()))
                assertFalse(permanentWindow in children)
                assertFalse(temporaryWindow in children)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `KillClient processes save set when destroying retained client resources`() {
        XServer(ServerOptions(port = 0, width = 160, height = 120)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { appSocket ->
                appSocket.soTimeout = 2_000
                setup(appSocket)
                val appWindow = WindowId + 36
                val appOut = appSocket.getOutputStream()
                appOut.write(createWindowRequest(appWindow, width = 20, height = 15))
                appOut.flush()

                val frameWindow = WindowId + 38
                Socket("127.0.0.1", server.localPort).use { frameSocket ->
                    frameSocket.soTimeout = 2_000
                    setup(frameSocket)
                    val frameOut = frameSocket.getOutputStream()
                    frameOut.write(createWindowRequest(frameWindow, x = 10, y = 10, width = 50, height = 40))
                    frameOut.write(reparentWindowRequest(appWindow, frameWindow, x = 7, y = 8))
                    frameOut.write(changeSaveSetRequest(0, appWindow))
                    frameOut.write(setCloseDownModeRequest(1))
                    frameOut.write(queryTreeRequest(X11Ids.RootWindow))
                    frameOut.flush()
                    assertTrue(frameWindow in treeChildren(readReply(frameSocket.getInputStream())))
                    closeClientAndWait(frameSocket)
                }

                waitForRootChildren(server.localPort) { frameWindow in it }

                Socket("127.0.0.1", server.localPort).use { killerSocket ->
                    killerSocket.soTimeout = 2_000
                    setup(killerSocket)
                    val killerOut = killerSocket.getOutputStream()
                    killerOut.write(killClientRequest(frameWindow))
                    killerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    killerOut.flush()

                    val children = treeChildren(readReply(killerSocket.getInputStream()))
                    assertTrue(appWindow in children)
                    assertFalse(frameWindow in children)
                }

                val json = httpGet(server.localPort, "/state.json")
                assertContains(
                    json,
                    windowJsonId(appWindow) + ""","parent":"${X11Ids.RootWindow.toJsonHex()}","x":17,"y":18,"localX":17,"localY":18,"width":20,"height":15""",
                )
                assertFalse(json.contains(windowJsonId(frameWindow)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DestroyWindow processes save set when destroying retained client windows`() {
        XServer(ServerOptions(port = 0, width = 160, height = 120)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { appSocket ->
                appSocket.soTimeout = 2_000
                setup(appSocket)
                val appWindow = WindowId + 40
                val appOut = appSocket.getOutputStream()
                appOut.write(createWindowRequest(appWindow, width = 20, height = 15))
                appOut.flush()

                val frameWindow = WindowId + 42
                Socket("127.0.0.1", server.localPort).use { frameSocket ->
                    frameSocket.soTimeout = 2_000
                    setup(frameSocket)
                    val frameOut = frameSocket.getOutputStream()
                    frameOut.write(createWindowRequest(frameWindow, x = 10, y = 10, width = 50, height = 40))
                    frameOut.write(reparentWindowRequest(appWindow, frameWindow, x = 7, y = 8))
                    frameOut.write(changeSaveSetRequest(0, appWindow))
                    frameOut.write(setCloseDownModeRequest(1))
                    frameOut.write(queryTreeRequest(X11Ids.RootWindow))
                    frameOut.flush()
                    assertTrue(frameWindow in treeChildren(readReply(frameSocket.getInputStream())))
                    closeClientAndWait(frameSocket)
                }

                waitForRootChildren(server.localPort) { frameWindow in it }

                Socket("127.0.0.1", server.localPort).use { destroyerSocket ->
                    destroyerSocket.soTimeout = 2_000
                    setup(destroyerSocket)
                    val destroyerOut = destroyerSocket.getOutputStream()
                    destroyerOut.write(destroyWindowRequest(frameWindow))
                    destroyerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    destroyerOut.flush()

                    val children = treeChildren(readReply(destroyerSocket.getInputStream()))
                    assertTrue(appWindow in children)
                    assertFalse(frameWindow in children)
                }

                val json = httpGet(server.localPort, "/state.json")
                assertContains(
                    json,
                    windowJsonId(appWindow) + ""","parent":"${X11Ids.RootWindow.toJsonHex()}","x":17,"y":18,"localX":17,"localY":18,"width":20,"height":15""",
                )
                assertFalse(json.contains(windowJsonId(frameWindow)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `KillClient targets reused resource current owner after cross-client destroy`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val reusedWindow = WindowId + 44
            Socket("127.0.0.1", server.localPort).use { staleOwnerSocket ->
                staleOwnerSocket.soTimeout = 2_000
                setup(staleOwnerSocket)
                val staleOwnerOut = staleOwnerSocket.getOutputStream()
                staleOwnerOut.write(createWindowRequest(reusedWindow))
                staleOwnerOut.write(queryTreeRequest(X11Ids.RootWindow))
                staleOwnerOut.flush()
                assertTrue(reusedWindow in treeChildren(readReply(staleOwnerSocket.getInputStream())))

                Socket("127.0.0.1", server.localPort).use { destroyerSocket ->
                    destroyerSocket.soTimeout = 2_000
                    setup(destroyerSocket)
                    val destroyerOut = destroyerSocket.getOutputStream()
                    destroyerOut.write(destroyWindowRequest(reusedWindow))
                    destroyerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    destroyerOut.flush()
                    assertFalse(reusedWindow in treeChildren(readReply(destroyerSocket.getInputStream())))
                }

                staleOwnerOut.write(setCloseDownModeRequest(1))
                staleOwnerOut.write(queryPointerRequest())
                staleOwnerOut.flush()
                readReply(staleOwnerSocket.getInputStream())
                closeClientAndWait(staleOwnerSocket)
            }

            Socket("127.0.0.1", server.localPort).use { currentOwnerSocket ->
                currentOwnerSocket.soTimeout = 2_000
                setup(currentOwnerSocket)
                val currentOwnerOut = currentOwnerSocket.getOutputStream()
                currentOwnerOut.write(createWindowRequest(reusedWindow))
                currentOwnerOut.write(queryTreeRequest(X11Ids.RootWindow))
                currentOwnerOut.flush()
                assertTrue(reusedWindow in treeChildren(readReply(currentOwnerSocket.getInputStream())))

                Socket("127.0.0.1", server.localPort).use { killerSocket ->
                    killerSocket.soTimeout = 2_000
                    setup(killerSocket)
                    val killerOut = killerSocket.getOutputStream()
                    killerOut.write(killClientRequest(reusedWindow))
                    killerOut.write(queryTreeRequest(X11Ids.RootWindow))
                    killerOut.flush()
                    assertFalse(reusedWindow in treeChildren(readReply(killerSocket.getInputStream())))
                }

                currentOwnerSocket.soTimeout = 500
                assertFailsWith<Exception> {
                    currentOwnerOut.write(queryPointerRequest())
                    currentOwnerOut.flush()
                    readReply(currentOwnerSocket.getInputStream())
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `NoOperation is replyless and ignores padded request bytes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(noOperationRequest())
                out.write(noOperationRequest(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(3, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeProperty validates request fields and preserves property on append mismatch`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingWindow = WindowId + 408
                val missingProperty = 0x00ff_fffe
                val missingType = 0x00ff_fffd
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(request(18, 0, ByteArray(0)))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 8, mode = 3, data = "x".encodeToByteArray()))
                out.write(changePropertyRawRequest(missingWindow, PrimaryAtom, StringAtom, format = 8, data = "x".encodeToByteArray()))
                out.write(changePropertyRawRequest(WindowId, missingProperty, StringAtom, format = 8, data = "x".encodeToByteArray()))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, missingType, format = 8, data = "x".encodeToByteArray()))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 7, data = ByteArray(0)))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 8, units = 5, data = "xx".encodeToByteArray()))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 32, units = -1, data = ByteArray(0)))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, AtomAtom, format = 8, mode = 2, data = "two".encodeToByteArray()))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 16, mode = 2, data = byteArrayOf(0x34, 0x12)))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 18, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 18, badValue = 3, sequence = 3)
                assertError(socket.getInputStream(), error = 3, opcode = 18, badValue = missingWindow, sequence = 4)
                assertError(socket.getInputStream(), error = 5, opcode = 18, badValue = missingProperty, sequence = 5)
                assertError(socket.getInputStream(), error = 5, opcode = 18, badValue = missingType, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 18, badValue = 7, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 18, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 18, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 8, opcode = 18, badValue = 0, sequence = 11)
                assertError(socket.getInputStream(), error = 8, opcode = 18, badValue = 0, sequence = 12)
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 13, type = StringAtom, value = "one")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeProperty implements replace prepend append and sends PropertyNotify`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.PropertyChange))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "middle"))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 8, mode = 1, data = "pre-".encodeToByteArray()))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 8, mode = 2, data = "-post".encodeToByteArray()))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, StringAtom, format = 8, mode = 2, units = 0, data = ByteArray(0)))
                out.write(changePropertyRawRequest(WindowId, PrimaryAtom, AtomAtom, format = 8, mode = 2, units = 0, data = ByteArray(0)))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.flush()

                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 3, window = WindowId, atom = PrimaryAtom)
                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 4, window = WindowId, atom = PrimaryAtom)
                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 5, window = WindowId, atom = PrimaryAtom)
                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 6, window = WindowId, atom = PrimaryAtom)
                assertError(socket.getInputStream(), error = 8, opcode = 18, badValue = 0, sequence = 7)
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 8, type = StringAtom, value = "pre-middle-post")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ChangeProperty stores 16 and 32 bit values independent of client byte order`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { littleSocket ->
                Socket("127.0.0.1", server.localPort).use { bigSocket ->
                    littleSocket.soTimeout = 2_000
                    bigSocket.soTimeout = 2_000
                    setup(littleSocket)
                    setup(bigSocket, byteOrderByte = 0x42)

                    val big32 = ByteArray(4)
                    put32be(big32, 0, 0x0102_0304)
                    bigSocket.getOutputStream().write(
                        changePropertyRawRequestBigEndian(X11Ids.RootWindow, PrimaryAtom, AtomAtom, format = 32, data = big32),
                    )
                    bigSocket.getOutputStream().write(getPropertyRequestBigEndian(X11Ids.RootWindow, PrimaryAtom, AtomAtom))
                    bigSocket.getOutputStream().flush()

                    assertPropertyReplyBytes(
                        readReply(bigSocket.getInputStream(), byteOrderByte = 0x42),
                        sequence = 2,
                        type = AtomAtom,
                        format = 32,
                        data = big32,
                        byteOrderByte = 0x42,
                    )

                    val little32 = ByteArray(4)
                    put32le(little32, 0, 0x0102_0304)
                    littleSocket.getOutputStream().write(getPropertyRequest(X11Ids.RootWindow, PrimaryAtom, AtomAtom))
                    littleSocket.getOutputStream().flush()
                    assertPropertyReplyBytes(
                        readReply(littleSocket.getInputStream()),
                        sequence = 1,
                        type = AtomAtom,
                        format = 32,
                        data = little32,
                    )

                    val little16 = byteArrayOf(0x34, 0x12, 0x78, 0x56)
                    littleSocket.getOutputStream().write(
                        changePropertyRawRequest(
                            X11Ids.RootWindow,
                            StringAtom,
                            StringAtom,
                            format = 16,
                            mode = 1,
                            data = little16.copyOfRange(0, 2),
                        ),
                    )
                    littleSocket.getOutputStream().write(
                        changePropertyRawRequest(
                            X11Ids.RootWindow,
                            StringAtom,
                            StringAtom,
                            format = 16,
                            mode = 2,
                            data = little16.copyOfRange(2, 4),
                        ),
                    )
                    littleSocket.getOutputStream().write(getPropertyRequest(X11Ids.RootWindow, StringAtom, StringAtom))
                    littleSocket.getOutputStream().flush()
                    assertPropertyReplyBytes(
                        readReply(littleSocket.getInputStream()),
                        sequence = 4,
                        type = StringAtom,
                        format = 16,
                        data = little16,
                    )

                    val big16 = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    bigSocket.getOutputStream().write(getPropertyRequestBigEndian(X11Ids.RootWindow, StringAtom, StringAtom))
                    bigSocket.getOutputStream().flush()
                    assertPropertyReplyBytes(
                        readReply(bigSocket.getInputStream(), byteOrderByte = 0x42),
                        sequence = 3,
                        type = StringAtom,
                        format = 16,
                        data = big16,
                        byteOrderByte = 0x42,
                    )
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DeleteProperty validates request fields and removes existing property`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingWindow = WindowId + 409
                val missingProperty = 0x00ff_fffc
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(request(19, 0, ByteArray(0)))
                out.write(request(19, 0, ByteArray(12)))
                out.write(deletePropertyRequest(missingWindow, PrimaryAtom))
                out.write(deletePropertyRequest(WindowId, missingProperty))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(deletePropertyRequest(WindowId, PrimaryAtom))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 19, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 19, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 3, opcode = 19, badValue = missingWindow, sequence = 4)
                assertError(socket.getInputStream(), error = 5, opcode = 19, badValue = missingProperty, sequence = 5)
                assertNoPropertyReply(readReply(socket.getInputStream()), sequence = 8)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `DeleteProperty sends PropertyNotify only when property existed`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.PropertyChange))
                out.write(deletePropertyRequest(WindowId, PrimaryAtom))
                out.write(deletePropertyRequest(WindowId, PrimaryAtom))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.flush()

                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 4, window = WindowId, atom = PrimaryAtom, state = 1)
                assertNoPropertyReply(readReply(socket.getInputStream()), sequence = 6)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetProperty validates request fields and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingWindow = WindowId + 410
                val missingProperty = 0x00ff_fffb
                val missingType = 0x00ff_fffa
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(request(20, 0, ByteArray(0)))
                out.write(request(20, 0, ByteArray(24)))
                out.write(getPropertyRawRequest(missingWindow, PrimaryAtom, StringAtom))
                out.write(getPropertyRawRequest(WindowId, missingProperty, StringAtom))
                out.write(getPropertyRawRequest(WindowId, PrimaryAtom, missingType))
                out.write(getPropertyRawRequest(WindowId, PrimaryAtom, StringAtom, delete = 2))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(getPropertyRawRequest(WindowId, PrimaryAtom, StringAtom, longOffset = -1))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 20, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 20, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 3, opcode = 20, badValue = missingWindow, sequence = 4)
                assertError(socket.getInputStream(), error = 5, opcode = 20, badValue = missingProperty, sequence = 5)
                assertError(socket.getInputStream(), error = 5, opcode = 20, badValue = missingType, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 20, badValue = 2, sequence = 7)
                assertError(socket.getInputStream(), error = 2, opcode = 20, badValue = -1, sequence = 9)
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 10, type = StringAtom, value = "one")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetProperty returns mismatch metadata and AnyPropertyType value`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "hello"))
                out.write(getPropertyRawRequest(WindowId, PrimaryAtom, AtomAtom, delete = 1))
                out.write(getPropertyRawRequest(WindowId, PrimaryAtom, 0))
                out.write(getPropertyRequest(WindowId, AtomAtom, StringAtom))
                out.flush()

                assertPropertyReplyBytes(
                    readReply(socket.getInputStream()),
                    sequence = 3,
                    type = StringAtom,
                    format = 8,
                    data = ByteArray(0),
                    bytesAfter = 5,
                )
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 4, type = StringAtom, value = "hello")
                assertNoPropertyReply(readReply(socket.getInputStream()), sequence = 5)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GetProperty delete removes only after complete read and sends PropertyNotify`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "abcdef"))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.PropertyChange))
                out.write(getPropertyRawRequest(WindowId, PrimaryAtom, StringAtom, delete = 1, longLength = 1))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.write(getPropertyRawRequest(WindowId, PrimaryAtom, StringAtom, delete = 1, longOffset = 1, longLength = 1))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.flush()

                assertPropertyReply(readReply(socket.getInputStream()), sequence = 4, type = StringAtom, value = "abcd", bytesAfter = 2)
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 5, type = StringAtom, value = "abcdef")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 6, type = StringAtom, value = "ef")
                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 6, window = WindowId, atom = PrimaryAtom, state = 1)
                assertNoPropertyReply(readReply(socket.getInputStream()), sequence = 7)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ListProperties validates length window and returns sorted atoms`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingWindow = WindowId + 411
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(request(21, 0, ByteArray(0)))
                out.write(request(21, 0, ByteArray(8)))
                out.write(listPropertiesRequest(missingWindow))
                out.write(changePropertyRequest(WindowId, AtomAtom, StringAtom, "two"))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(listPropertiesRequest(WindowId))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 21, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 21, badValue = 0, sequence = 3)
                assertError(socket.getInputStream(), error = 3, opcode = 21, badValue = missingWindow, sequence = 4)
                assertListPropertiesReply(readReply(socket.getInputStream()), sequence = 7, PrimaryAtom, AtomAtom)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RotateProperties rotates property values by positive and negative delta`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(changePropertyRequest(WindowId, AtomAtom, StringAtom, "two"))
                out.write(changePropertyRequest(WindowId, StringAtom, StringAtom, "three"))
                out.write(rotatePropertiesRequest(WindowId, delta = 1, PrimaryAtom, AtomAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, AtomAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, StringAtom, StringAtom))
                out.write(rotatePropertiesRequest(WindowId, delta = -1, PrimaryAtom, AtomAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, AtomAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, StringAtom, StringAtom))
                out.flush()

                assertPropertyReply(readReply(socket.getInputStream()), sequence = 6, type = StringAtom, value = "two")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 7, type = StringAtom, value = "three")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 8, type = StringAtom, value = "one")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 10, type = StringAtom, value = "one")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 11, type = StringAtom, value = "two")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 12, type = StringAtom, value = "three")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RotateProperties validates atoms matches window length and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingWindow = 0x0020_7777
                val missingProperty = 2
                out.write(createWindowRequest(WindowId))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(changePropertyRequest(WindowId, AtomAtom, StringAtom, "two"))
                out.write(changePropertyRequest(WindowId, StringAtom, StringAtom, "three"))
                out.write(rotatePropertiesRequest(missingWindow, delta = 1, PrimaryAtom))
                out.write(rotatePropertiesRequest(WindowId, delta = 1, PrimaryAtom, 0, AtomAtom))
                out.write(rotatePropertiesRequest(WindowId, delta = 1, PrimaryAtom, PrimaryAtom))
                out.write(rotatePropertiesRequest(WindowId, delta = 1, PrimaryAtom, missingProperty))
                out.write(request(114, 0, ByteArray(4)))
                out.write(rotatePropertiesBadLengthRequest(WindowId))
                out.write(rotatePropertiesRequest(WindowId, delta = 0, PrimaryAtom, missingProperty))
                out.write(rotatePropertiesRequest(WindowId, delta = 0, PrimaryAtom))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, AtomAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, StringAtom, StringAtom))
                out.flush()

                assertError(socket.getInputStream(), error = 3, opcode = 114, badValue = missingWindow, sequence = 5)
                assertError(socket.getInputStream(), error = 5, opcode = 114, badValue = 0, sequence = 6)
                assertError(socket.getInputStream(), error = 8, opcode = 114, badValue = PrimaryAtom, sequence = 7)
                assertError(socket.getInputStream(), error = 8, opcode = 114, badValue = missingProperty, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 114, badValue = 0, sequence = 9)
                assertError(socket.getInputStream(), error = 16, opcode = 114, badValue = 0, sequence = 10)
                assertError(socket.getInputStream(), error = 8, opcode = 114, badValue = missingProperty, sequence = 11)
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 13, type = StringAtom, value = "one")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 14, type = StringAtom, value = "two")
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 15, type = StringAtom, value = "three")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RotateProperties sends PropertyNotify for effective rotations only`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(changePropertyRequest(WindowId, PrimaryAtom, StringAtom, "one"))
                out.write(changePropertyRequest(WindowId, AtomAtom, StringAtom, "two"))
                out.write(changePropertyRequest(WindowId, StringAtom, StringAtom, "three"))
                out.write(changeWindowEventMaskRequest(WindowId, XEventMasks.PropertyChange))
                out.write(rotatePropertiesRequest(WindowId, delta = 1, PrimaryAtom, AtomAtom, StringAtom))
                out.write(rotatePropertiesRequest(WindowId, delta = 3, PrimaryAtom, AtomAtom, StringAtom))
                out.write(getPropertyRequest(WindowId, PrimaryAtom, StringAtom))
                out.flush()

                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 6, window = WindowId, atom = PrimaryAtom)
                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 6, window = WindowId, atom = AtomAtom)
                assertPropertyNotifyEvent(socket.getInputStream().readExactly(32), sequence = 6, window = WindowId, atom = StringAtom)
                assertPropertyReply(readReply(socket.getInputStream()), sequence = 8, type = StringAtom, value = "two")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetSelectionOwner updates and clears GetSelectionOwner reply`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                out.write(getSelectionOwnerRequest(PrimaryAtom))
                out.write(setSelectionOwnerRequest(0, PrimaryAtom))
                out.write(getSelectionOwnerRequest(PrimaryAtom))
                out.flush()

                val owner = readReply(socket.getInputStream())
                assertEquals(1, owner[0].toInt())
                assertEquals(0, u32le(owner, 4))
                assertEquals(WindowId, u32le(owner, 8))

                val clear = socket.getInputStream().readExactly(32)
                assertEquals(29, clear[0].toInt() and 0xff)
                assertEquals(4, u16le(clear, 2))
                assertEquals(WindowId, u32le(clear, 8))
                assertEquals(PrimaryAtom, u32le(clear, 12))

                val cleared = readReply(socket.getInputStream())
                assertEquals(1, cleared[0].toInt())
                assertEquals(0, u32le(cleared, 4))
                assertEquals(0, u32le(cleared, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetSelectionOwner sends SelectionClear to displaced owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { firstOwnerSocket ->
                Socket("127.0.0.1", server.localPort).use { secondOwnerSocket ->
                    firstOwnerSocket.soTimeout = 2_000
                    secondOwnerSocket.soTimeout = 2_000
                    setup(firstOwnerSocket)
                    setup(secondOwnerSocket)
                    val secondWindow = WindowId + 1
                    firstOwnerSocket.getOutputStream().write(createWindowRequest(WindowId))
                    firstOwnerSocket.getOutputStream().write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                    firstOwnerSocket.getOutputStream().flush()
                    var observedOwner: ByteArray? = null
                    var attempts = 0
                    while (observedOwner == null && attempts < 20) {
                        attempts += 1
                        secondOwnerSocket.getOutputStream().write(getSelectionOwnerRequest(PrimaryAtom))
                        secondOwnerSocket.getOutputStream().flush()
                        val reply = readReply(secondOwnerSocket.getInputStream())
                        if (u32le(reply, 8) == WindowId) {
                            observedOwner = reply
                        } else {
                            Thread.sleep(25)
                        }
                    }
                    assertEquals(WindowId, u32le(observedOwner ?: error("selection owner was not set"), 8))

                    secondOwnerSocket.getOutputStream().write(createWindowRequest(secondWindow))
                    secondOwnerSocket.getOutputStream().write(setSelectionOwnerRequest(secondWindow, PrimaryAtom))
                    secondOwnerSocket.getOutputStream().flush()

                    val clear = firstOwnerSocket.getInputStream().readExactly(32)
                    assertEquals(29, clear[0].toInt() and 0xff)
                    assertEquals(2, u16le(clear, 2))
                    assertEquals(1, u32le(clear, 4))
                    assertEquals(WindowId, u32le(clear, 8))
                    assertEquals(PrimaryAtom, u32le(clear, 12))

                    secondOwnerSocket.getOutputStream().write(getSelectionOwnerRequest(PrimaryAtom))
                    secondOwnerSocket.getOutputStream().flush()
                    val owner = readReply(secondOwnerSocket.getInputStream())
                    assertEquals(secondWindow, u32le(owner, 8))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetSelectionOwner ignores future timestamps without clearing current owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                out.write(setSelectionOwnerRequest(0, PrimaryAtom, time = 2))
                out.write(getSelectionOwnerRequest(PrimaryAtom))
                out.flush()

                val owner = readReply(socket.getInputStream())
                assertEquals(4, u16le(owner, 2))
                assertEquals(WindowId, u32le(owner, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetSelectionOwner and GetSelectionOwner reject malformed lengths and recover stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(setSelectionOwnerRequest(WindowId, PrimaryAtom, extraBytes = 4))
                out.write(getSelectionOwnerRequest(PrimaryAtom, extraBytes = 4))
                out.write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                out.write(getSelectionOwnerRequest(PrimaryAtom))
                out.flush()

                assertError(socket.getInputStream(), error = 16, opcode = 22, badValue = 0, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 23, badValue = 0, sequence = 3)
                val owner = readReply(socket.getInputStream())
                assertEquals(5, u16le(owner, 2))
                assertEquals(WindowId, u32le(owner, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SetSelectionOwner clears owner when owner client disconnects`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { observerSocket ->
                    ownerSocket.soTimeout = 2_000
                    observerSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(observerSocket)
                    ownerSocket.getOutputStream().write(createWindowRequest(WindowId))
                    ownerSocket.getOutputStream().write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                    ownerSocket.getOutputStream().flush()

                    var owner: ByteArray? = null
                    var ownerAttempts = 0
                    while (owner == null && ownerAttempts < 20) {
                        ownerAttempts += 1
                        observerSocket.getOutputStream().write(getSelectionOwnerRequest(PrimaryAtom))
                        observerSocket.getOutputStream().flush()
                        val reply = readReply(observerSocket.getInputStream())
                        if (u32le(reply, 8) == WindowId) {
                            owner = reply
                        } else {
                            Thread.sleep(25)
                        }
                    }
                    assertEquals(WindowId, u32le(owner ?: error("selection owner was not set"), 8))

                    ownerSocket.close()
                    var cleared: ByteArray? = null
                    var attempts = 0
                    while (cleared == null && attempts < 20) {
                        attempts += 1
                        observerSocket.getOutputStream().write(getSelectionOwnerRequest(PrimaryAtom))
                        observerSocket.getOutputStream().flush()
                        val reply = readReply(observerSocket.getInputStream())
                        if (u32le(reply, 8) == 0) {
                            cleared = reply
                        } else {
                            Thread.sleep(25)
                        }
                    }
                    assertEquals(0, u32le(cleared ?: error("selection owner was not cleared"), 8))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConvertSelection without owner sends SelectionNotify with property none`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(convertSelectionRequest(WindowId, PrimaryAtom, AtomAtom, StringAtom, time = 77))
                out.flush()

                val event = socket.getInputStream().readExactly(32)
                assertEquals(31, event[0].toInt() and 0xff)
                assertEquals(2, u16le(event, 2))
                assertEquals(77, u32le(event, 4))
                assertEquals(WindowId, u32le(event, 8))
                assertEquals(PrimaryAtom, u32le(event, 12))
                assertEquals(AtomAtom, u32le(event, 16))
                assertEquals(0, u32le(event, 20))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConvertSelection rejects unknown requestor window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val unknownWindow = WindowId + 1
                socket.getOutputStream().write(
                    convertSelectionRequest(unknownWindow, PrimaryAtom, AtomAtom, property = 0),
                )
                socket.getOutputStream().flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(3, error[1].toInt() and 0xff)
                assertEquals(unknownWindow, u32le(error, 4))
                assertEquals(24, error[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConvertSelection rejects undefined atom fields with matching bad values`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val unknownSelection = 0x0020_2001
                val unknownTarget = 0x0020_2002
                val unknownProperty = 0x0020_2003
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(convertSelectionRequest(WindowId, unknownSelection, AtomAtom, property = 0))
                out.write(convertSelectionRequest(WindowId, PrimaryAtom, unknownTarget, property = 0))
                out.write(convertSelectionRequest(WindowId, PrimaryAtom, AtomAtom, property = unknownProperty))
                out.flush()

                for (badValue in listOf(unknownSelection, unknownTarget, unknownProperty)) {
                    val error = socket.getInputStream().readExactly(32)
                    assertEquals(0, error[0].toInt())
                    assertEquals(5, error[1].toInt() and 0xff)
                    assertEquals(badValue, u32le(error, 4))
                    assertEquals(24, error[10].toInt() and 0xff)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConvertSelection rejects oversized request before semantic validation`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val unknownWindow = WindowId + 1
                socket.getOutputStream().write(
                    convertSelectionRequest(unknownWindow, PrimaryAtom, AtomAtom, property = 0, extraBytes = 4),
                )
                socket.getOutputStream().flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(16, error[1].toInt() and 0xff)
                assertEquals(0, u32le(error, 4))
                assertEquals(24, error[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `ConvertSelection with owner sends SelectionRequest to owner client`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { requestorSocket ->
                    ownerSocket.soTimeout = 2_000
                    requestorSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(requestorSocket)
                    val requestor = WindowId + 1
                    ownerSocket.getOutputStream().write(createWindowRequest(WindowId))
                    ownerSocket.getOutputStream().write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                    ownerSocket.getOutputStream().flush()
                    var observedOwner: ByteArray? = null
                    var attempts = 0
                    while (observedOwner == null && attempts < 20) {
                        attempts += 1
                        requestorSocket.getOutputStream().write(getSelectionOwnerRequest(PrimaryAtom))
                        requestorSocket.getOutputStream().flush()
                        val reply = readReply(requestorSocket.getInputStream())
                        if (u32le(reply, 8) == WindowId) {
                            observedOwner = reply
                        } else {
                            Thread.sleep(25)
                        }
                    }
                    assertEquals(WindowId, u32le(observedOwner ?: error("selection owner was not set"), 8))

                    requestorSocket.getOutputStream().write(createWindowRequest(requestor))
                    requestorSocket.getOutputStream().write(
                        convertSelectionRequest(requestor, PrimaryAtom, AtomAtom, StringAtom, time = 77),
                    )
                    requestorSocket.getOutputStream().flush()

                    val event = ownerSocket.getInputStream().readExactly(32)
                    assertEquals(30, event[0].toInt() and 0xff)
                    assertEquals(2, u16le(event, 2))
                    assertEquals(77, u32le(event, 4))
                    assertEquals(WindowId, u32le(event, 8))
                    assertEquals(requestor, u32le(event, 12))
                    assertEquals(PrimaryAtom, u32le(event, 16))
                    assertEquals(AtomAtom, u32le(event, 20))
                    assertEquals(StringAtom, u32le(event, 24))

                    requestorSocket.soTimeout = 250
                    assertFailsWith<SocketTimeoutException> {
                        requestorSocket.getInputStream().readExactly(32)
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent with empty mask delivers synthetic SelectionNotify to destination owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { ownerSocket ->
                Socket("127.0.0.1", server.localPort).use { requestorSocket ->
                    ownerSocket.soTimeout = 2_000
                    requestorSocket.soTimeout = 2_000
                    setup(ownerSocket)
                    setup(requestorSocket)
                    val requestor = WindowId + 1
                    ownerSocket.getOutputStream().write(createWindowRequest(WindowId))
                    ownerSocket.getOutputStream().write(setSelectionOwnerRequest(WindowId, PrimaryAtom))
                    ownerSocket.getOutputStream().flush()
                    requestorSocket.getOutputStream().write(createWindowRequest(requestor))
                    requestorSocket.getOutputStream().flush()

                    var observedOwner: ByteArray? = null
                    var attempts = 0
                    while (observedOwner == null && attempts < 20) {
                        attempts += 1
                        requestorSocket.getOutputStream().write(getSelectionOwnerRequest(PrimaryAtom))
                        requestorSocket.getOutputStream().flush()
                        val reply = readReply(requestorSocket.getInputStream())
                        if (u32le(reply, 8) == WindowId) {
                            observedOwner = reply
                        } else {
                            Thread.sleep(25)
                        }
                    }
                    assertEquals(WindowId, u32le(observedOwner ?: error("selection owner was not set"), 8))

                    requestorSocket.getOutputStream().write(
                        convertSelectionRequest(requestor, PrimaryAtom, AtomAtom, StringAtom, time = 77),
                    )
                    requestorSocket.getOutputStream().flush()

                    val selectionRequest = ownerSocket.getInputStream().readExactly(32)
                    assertEquals(30, selectionRequest[0].toInt() and 0xff)
                    ownerSocket.getOutputStream().write(getSelectionOwnerRequest(PrimaryAtom))
                    ownerSocket.getOutputStream().flush()
                    val ownerReply = readReply(ownerSocket.getInputStream())
                    assertEquals(WindowId, u32le(ownerReply, 8))
                    ownerSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = requestor,
                            event = selectionNotifyEvent(requestor, PrimaryAtom, AtomAtom, StringAtom, time = 77),
                        ),
                    )
                    ownerSocket.getOutputStream().flush()

                    val selectionNotify = requestorSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or 31, selectionNotify[0].toInt() and 0xff)
                    assertEquals(attempts + 2, u16le(selectionNotify, 2))
                    assertEquals(77, u32le(selectionNotify, 4))
                    assertEquals(requestor, u32le(selectionNotify, 8))
                    assertEquals(PrimaryAtom, u32le(selectionNotify, 12))
                    assertEquals(AtomAtom, u32le(selectionNotify, 16))
                    assertEquals(StringAtom, u32le(selectionNotify, 20))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent swaps synthetic SelectionNotify fields for big endian destination owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { recipientSocket ->
                    senderSocket.soTimeout = 2_000
                    recipientSocket.soTimeout = 2_000
                    setup(senderSocket)
                    setup(recipientSocket, byteOrderByte = 0x42)
                    val requestor = WindowId + 2
                    recipientSocket.getOutputStream().write(createWindowRequestBigEndian(requestor, width = 31, height = 17))
                    recipientSocket.getOutputStream().write(getGeometryRequestBigEndian(requestor))
                    recipientSocket.getOutputStream().flush()

                    val geometry = readReply(recipientSocket.getInputStream(), byteOrderByte = 0x42)
                    assertEquals(1, geometry[0].toInt())
                    assertEquals(2, u16be(geometry, 2))
                    assertEquals(31, u16be(geometry, 16))
                    assertEquals(17, u16be(geometry, 18))

                    senderSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = requestor,
                            event = selectionNotifyEvent(requestor, PrimaryAtom, AtomAtom, StringAtom, time = 77),
                        ),
                    )
                    senderSocket.getOutputStream().flush()

                    val event = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or 31, event[0].toInt() and 0xff)
                    assertEquals(2, u16be(event, 2))
                    assertEquals(77, u32be(event, 4))
                    assertEquals(requestor, u32be(event, 8))
                    assertEquals(PrimaryAtom, u32be(event, 12))
                    assertEquals(AtomAtom, u32be(event, 16))
                    assertEquals(StringAtom, u32be(event, 20))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent swaps synthetic ClientMessage fields by format for big endian destination owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { recipientSocket ->
                    senderSocket.soTimeout = 2_000
                    recipientSocket.soTimeout = 2_000
                    setup(senderSocket)
                    setup(recipientSocket, byteOrderByte = 0x42)
                    val recipient = WindowId + 15
                    recipientSocket.getOutputStream().write(createWindowRequestBigEndian(recipient, width = 31, height = 17))
                    recipientSocket.getOutputStream().write(getGeometryRequestBigEndian(recipient))
                    recipientSocket.getOutputStream().flush()

                    val geometry = readReply(recipientSocket.getInputStream(), byteOrderByte = 0x42)
                    assertEquals(1, geometry[0].toInt())
                    assertEquals(2, u16be(geometry, 2))
                    assertEquals(31, u16be(geometry, 16))
                    assertEquals(17, u16be(geometry, 18))

                    val data32 = listOf(0x0102_0304, 0x1112_1314, 0x2122_2324, 0x3132_3334, 0x4142_4344)
                    val data8 = ByteArray(20) { index -> (index + 1).toByte() }
                    senderSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = recipient,
                            event = clientMessageEvent(recipient, PrimaryAtom, format = 32, data32 = data32),
                        ),
                    )
                    senderSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = recipient,
                            event = clientMessageEvent(recipient, StringAtom, format = 8, data8 = data8),
                        ),
                    )
                    senderSocket.getOutputStream().flush()

                    val format32 = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or 33, format32[0].toInt() and 0xff)
                    assertEquals(32, format32[1].toInt() and 0xff)
                    assertEquals(2, u16be(format32, 2))
                    assertEquals(recipient, u32be(format32, 4))
                    assertEquals(PrimaryAtom, u32be(format32, 8))
                    data32.forEachIndexed { index, value ->
                        assertEquals(value, u32be(format32, 12 + index * 4))
                    }

                    val format8 = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or 33, format8[0].toInt() and 0xff)
                    assertEquals(8, format8[1].toInt() and 0xff)
                    assertEquals(2, u16be(format8, 2))
                    assertEquals(recipient, u32be(format8, 4))
                    assertEquals(StringAtom, u32be(format8, 8))
                    assertEquals(data8.toList(), format8.copyOfRange(12, 32).toList())
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent accepts synthetic XFixes SelectionNotify for advertised extension event code`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { recipientSocket ->
                    senderSocket.soTimeout = 2_000
                    recipientSocket.soTimeout = 2_000
                    setup(senderSocket)
                    setup(recipientSocket)
                    val recipient = WindowId + 3
                    recipientSocket.getOutputStream().write(createWindowRequest(recipient))
                    recipientSocket.getOutputStream().write(getGeometryRequest(recipient))
                    recipientSocket.getOutputStream().flush()
                    val geometry = readReply(recipientSocket.getInputStream())
                    assertEquals(1, geometry[0].toInt())
                    assertEquals(2, u16le(geometry, 2))

                    senderSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = recipient,
                            event = xfixesSelectionNotifyEvent(
                                window = recipient,
                                owner = WindowId + 4,
                                selection = PrimaryAtom,
                                timestamp = 77,
                                selectionTimestamp = 66,
                            ),
                        ),
                    )
                    senderSocket.getOutputStream().flush()

                    val event = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or (XFixes.FirstEvent + XFixes.SelectionNotify), event[0].toInt() and 0xff)
                    assertEquals(XFixes.SetSelectionOwnerNotify, event[1].toInt() and 0xff)
                    assertEquals(2, u16le(event, 2))
                    assertEquals(recipient, u32le(event, 4))
                    assertEquals(WindowId + 4, u32le(event, 8))
                    assertEquals(PrimaryAtom, u32le(event, 12))
                    assertEquals(77, u32le(event, 16))
                    assertEquals(66, u32le(event, 20))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent swaps synthetic XFixes SelectionNotify fields for big endian destination owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { recipientSocket ->
                    senderSocket.soTimeout = 2_000
                    recipientSocket.soTimeout = 2_000
                    setup(senderSocket)
                    setup(recipientSocket, byteOrderByte = 0x42)
                    val recipient = WindowId + 5
                    recipientSocket.getOutputStream().write(createWindowRequestBigEndian(recipient, width = 31, height = 17))
                    recipientSocket.getOutputStream().write(getGeometryRequestBigEndian(recipient))
                    recipientSocket.getOutputStream().flush()

                    val geometry = readReply(recipientSocket.getInputStream(), byteOrderByte = 0x42)
                    assertEquals(1, geometry[0].toInt())
                    assertEquals(2, u16be(geometry, 2))
                    assertEquals(31, u16be(geometry, 16))
                    assertEquals(17, u16be(geometry, 18))

                    senderSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = recipient,
                            event = xfixesSelectionNotifyEvent(
                                window = recipient,
                                owner = WindowId + 6,
                                selection = PrimaryAtom,
                                timestamp = 77,
                                selectionTimestamp = 66,
                            ),
                        ),
                    )
                    senderSocket.getOutputStream().flush()

                    val event = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or (XFixes.FirstEvent + XFixes.SelectionNotify), event[0].toInt() and 0xff)
                    assertEquals(XFixes.SetSelectionOwnerNotify, event[1].toInt() and 0xff)
                    assertEquals(2, u16be(event, 2))
                    assertEquals(recipient, u32be(event, 4))
                    assertEquals(WindowId + 6, u32be(event, 8))
                    assertEquals(PrimaryAtom, u32be(event, 12))
                    assertEquals(77, u32be(event, 16))
                    assertEquals(66, u32be(event, 20))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent swaps synthetic CreateNotify fields for big endian destination owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { recipientSocket ->
                    senderSocket.soTimeout = 2_000
                    recipientSocket.soTimeout = 2_000
                    setup(senderSocket)
                    setup(recipientSocket, byteOrderByte = 0x42)
                    val destination = WindowId + 11
                    val createdWindow = WindowId + 12
                    recipientSocket.getOutputStream().write(createWindowRequestBigEndian(destination))
                    recipientSocket.getOutputStream().write(getGeometryRequestBigEndian(destination))
                    recipientSocket.getOutputStream().flush()
                    assertEquals(2, u16be(readReply(recipientSocket.getInputStream(), byteOrderByte = 0x42), 2))

                    senderSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = destination,
                            event = createNotifyEvent(
                                parent = destination,
                                window = createdWindow,
                                x = 3,
                                y = 4,
                                width = 31,
                                height = 17,
                                borderWidth = 2,
                            ),
                        ),
                    )
                    senderSocket.getOutputStream().flush()

                    val event = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or 16, event[0].toInt() and 0xff)
                    assertEquals(2, u16be(event, 2))
                    assertEquals(destination, u32be(event, 4))
                    assertEquals(createdWindow, u32be(event, 8))
                    assertEquals(3, u16be(event, 12))
                    assertEquals(4, u16be(event, 14))
                    assertEquals(31, u16be(event, 16))
                    assertEquals(17, u16be(event, 18))
                    assertEquals(2, u16be(event, 20))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent swaps synthetic ResizeRequest fields for big endian destination owner`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { recipientSocket ->
                    senderSocket.soTimeout = 2_000
                    recipientSocket.soTimeout = 2_000
                    setup(senderSocket)
                    setup(recipientSocket, byteOrderByte = 0x42)
                    val destination = WindowId + 13
                    recipientSocket.getOutputStream().write(createWindowRequestBigEndian(destination))
                    recipientSocket.getOutputStream().write(getGeometryRequestBigEndian(destination))
                    recipientSocket.getOutputStream().flush()
                    assertEquals(2, u16be(readReply(recipientSocket.getInputStream(), byteOrderByte = 0x42), 2))

                    senderSocket.getOutputStream().write(
                        sendEventRequest(
                            destination = destination,
                            event = resizeRequestEvent(window = destination, width = 31, height = 17),
                        ),
                    )
                    senderSocket.getOutputStream().flush()

                    val event = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or 25, event[0].toInt() and 0xff)
                    assertEquals(2, u16be(event, 2))
                    assertEquals(destination, u32be(event, 4))
                    assertEquals(31, u16be(event, 8))
                    assertEquals(17, u16be(event, 10))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent routes by destination even when SelectionNotify requestor differs`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { destinationSocket ->
                    Socket("127.0.0.1", server.localPort).use { payloadSocket ->
                        senderSocket.soTimeout = 2_000
                        destinationSocket.soTimeout = 2_000
                        payloadSocket.soTimeout = 2_000
                        setup(senderSocket)
                        setup(destinationSocket)
                        setup(payloadSocket)
                        val destination = WindowId + 3
                        val payloadRequestor = WindowId + 4
                        destinationSocket.getOutputStream().write(createWindowRequest(destination))
                        destinationSocket.getOutputStream().write(getGeometryRequest(destination))
                        destinationSocket.getOutputStream().flush()
                        val destinationGeometry = readReply(destinationSocket.getInputStream())
                        assertEquals(2, u16le(destinationGeometry, 2))
                        payloadSocket.getOutputStream().write(createWindowRequest(payloadRequestor))
                        payloadSocket.getOutputStream().write(getGeometryRequest(payloadRequestor))
                        payloadSocket.getOutputStream().flush()
                        val payloadGeometry = readReply(payloadSocket.getInputStream())
                        assertEquals(2, u16le(payloadGeometry, 2))

                        senderSocket.getOutputStream().write(
                            sendEventRequest(
                                destination = destination,
                                event = selectionNotifyEvent(payloadRequestor, PrimaryAtom, AtomAtom, StringAtom, time = 77),
                            ),
                        )
                        senderSocket.getOutputStream().flush()

                        val event = destinationSocket.getInputStream().readExactly(32)
                        assertEquals(0x80 or 31, event[0].toInt() and 0xff)
                        assertEquals(2, u16le(event, 2))
                        assertEquals(77, u32le(event, 4))
                        assertEquals(payloadRequestor, u32le(event, 8))
                        assertEquals(PrimaryAtom, u32le(event, 12))
                        assertEquals(AtomAtom, u32le(event, 16))
                        assertEquals(StringAtom, u32le(event, 20))

                        payloadSocket.soTimeout = 250
                        assertFailsWith<SocketTimeoutException> {
                            payloadSocket.getInputStream().readExactly(32)
                        }
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent preserves KeymapNotify bytes without sequence stamping`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { senderSocket ->
                Socket("127.0.0.1", server.localPort).use { recipientSocket ->
                    senderSocket.soTimeout = 2_000
                    recipientSocket.soTimeout = 2_000
                    setup(senderSocket)
                    setup(recipientSocket)
                    val destination = WindowId + 5
                    recipientSocket.getOutputStream().write(createWindowRequest(destination))
                    recipientSocket.getOutputStream().write(getGeometryRequest(destination))
                    recipientSocket.getOutputStream().flush()
                    val geometry = readReply(recipientSocket.getInputStream())
                    assertEquals(2, u16le(geometry, 2))

                    val keymap = ByteArray(32)
                    keymap[0] = 11
                    for (index in 1 until keymap.size) {
                        keymap[index] = (0xa0 + index).toByte()
                    }
                    senderSocket.getOutputStream().write(sendEventRequest(destination, keymap))
                    senderSocket.getOutputStream().flush()

                    val event = recipientSocket.getInputStream().readExactly(32)
                    assertEquals(0x80 or 11, event[0].toInt() and 0xff)
                    for (index in 1 until keymap.size) {
                        assertEquals(keymap[index], event[index], "keymap byte $index")
                    }
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent propagation stops at destination do not propagate mask`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val ancestor = WindowId + 6
                val intermediate = WindowId + 7
                val destination = WindowId + 8
                val buttonPressMask = 1 shl 2
                socket.getOutputStream().write(createWindowRequest(ancestor, eventMask = buttonPressMask))
                socket.getOutputStream().write(createWindowRequest(intermediate, parent = ancestor))
                socket.getOutputStream().write(createWindowRequest(destination, parent = intermediate, doNotPropagateMask = buttonPressMask))
                socket.getOutputStream().write(
                    sendEventRequest(
                        destination = destination,
                        event = ByteArray(32).also { it[0] = 4 },
                        eventMask = buttonPressMask,
                        propagate = true,
                    ),
                )
                socket.getOutputStream().flush()

                socket.soTimeout = 250
                assertFailsWith<SocketTimeoutException> {
                    socket.getInputStream().readExactly(32)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent InputFocus propagation may deliver to focus window itself`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val focus = WindowId + 9
                val destination = WindowId + 10
                val buttonPressMask = 1 shl 2
                socket.getOutputStream().write(createWindowRequest(focus, eventMask = buttonPressMask))
                socket.getOutputStream().write(createWindowRequest(destination, parent = focus))
                socket.getOutputStream().write(mapWindowRequest(focus))
                socket.getOutputStream().write(mapWindowRequest(destination))
                socket.getOutputStream().write(setInputFocusRequest(focus, revertTo = 2))
                socket.getOutputStream().write(
                    sendEventRequest(
                        destination = 1,
                        event = ByteArray(32).also { it[0] = 4 },
                        eventMask = buttonPressMask,
                        propagate = true,
                    ),
                )
                socket.getOutputStream().flush()

                assertMapAndExpose(socket.getInputStream(), focus)
                assertMapAndExpose(socket.getInputStream(), destination)
                val event = socket.getInputStream().readExactly(32)
                assertEquals(0x80 or 4, event[0].toInt() and 0xff)
                assertEquals(6, u16le(event, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent to InputFocus resolves PointerRoot focus to pointer window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                socket.getOutputStream().write(createWindowRequest(WindowId))
                socket.getOutputStream().write(mapWindowRequest(WindowId))
                socket.getOutputStream().write(setInputFocusRequest(1, revertTo = 2))
                socket.getOutputStream().write(
                    sendEventRequest(
                        destination = 1,
                        event = selectionNotifyEvent(WindowId, PrimaryAtom, AtomAtom, StringAtom, time = 77),
                    ),
                )
                socket.getOutputStream().flush()

                assertMapAndExpose(socket.getInputStream(), WindowId)
                val event = socket.getInputStream().readExactly(32)
                assertEquals(0x80 or 31, event[0].toInt() and 0xff)
                assertEquals(4, u16le(event, 2))
                assertEquals(77, u32le(event, 4))
                assertEquals(WindowId, u32le(event, 8))
                assertEquals(PrimaryAtom, u32le(event, 12))
                assertEquals(AtomAtom, u32le(event, 16))
                assertEquals(StringAtom, u32le(event, 20))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent rejects invalid event code`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val invalidEvent = ByteArray(32)
                invalidEvent[0] = 1
                socket.getOutputStream().write(createWindowRequest(WindowId))
                socket.getOutputStream().write(sendEventRequest(WindowId, invalidEvent))
                socket.getOutputStream().flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(1, u32le(error, 4))
                assertEquals(25, error[10].toInt() and 0xff)

                val extensionEvent = ByteArray(32)
                extensionEvent[0] = 64
                socket.getOutputStream().write(sendEventRequest(WindowId, extensionEvent))
                socket.getOutputStream().flush()
                val extensionError = socket.getInputStream().readExactly(32)
                assertEquals(0, extensionError[0].toInt())
                assertEquals(2, extensionError[1].toInt() and 0xff)
                assertEquals(64, u32le(extensionError, 4))
                assertEquals(25, extensionError[10].toInt() and 0xff)

                val unmodeledRandrNotify = ByteArray(32)
                unmodeledRandrNotify[0] = (XRandr.FirstEvent + XRandr.Notify).toByte()
                unmodeledRandrNotify[1] = 0
                socket.getOutputStream().write(sendEventRequest(WindowId, unmodeledRandrNotify))
                socket.getOutputStream().flush()
                val randrSubtypeError = socket.getInputStream().readExactly(32)
                assertEquals(0, randrSubtypeError[0].toInt())
                assertEquals(2, randrSubtypeError[1].toInt() and 0xff)
                assertEquals(XRandr.FirstEvent + XRandr.Notify, u32le(randrSubtypeError, 4))
                assertEquals(25, randrSubtypeError[10].toInt() and 0xff)

                socket.getOutputStream().write(
                    sendEventRequest(
                        destination = WindowId,
                        event = selectionNotifyEvent(WindowId, PrimaryAtom, AtomAtom, StringAtom, time = 77),
                        eventMask = 0xfe00_0000.toInt(),
                    ),
                )
                socket.getOutputStream().flush()
                val maskError = socket.getInputStream().readExactly(32)
                assertEquals(0, maskError[0].toInt())
                assertEquals(2, maskError[1].toInt() and 0xff)
                assertEquals(0xfe00_0000.toInt(), u32le(maskError, 4))
                assertEquals(25, maskError[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SendEvent rejects invalid request fields and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val event = selectionNotifyEvent(WindowId, PrimaryAtom, AtomAtom, StringAtom, time = 77)
                val unknownWindow = WindowId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(sendEventRequest(WindowId, event, propagateOpcode = 2))
                out.write(sendEventRequest(unknownWindow, event))
                out.write(sendEventRequest(WindowId, event, extraBytes = 4))
                out.write(sendEventRequest(WindowId, event))
                out.flush()

                assertError(socket.getInputStream(), error = 2, opcode = 25, badValue = 2, sequence = 2)
                assertError(socket.getInputStream(), error = 3, opcode = 25, badValue = unknownWindow, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 25, badValue = 0, sequence = 4)

                val delivered = socket.getInputStream().readExactly(32)
                assertEquals(0x80 or 31, delivered[0].toInt() and 0xff)
                assertEquals(5, u16le(delivered, 2))
                assertEquals(77, u32le(delivered, 4))
                assertEquals(WindowId, u32le(delivered, 8))
                assertEquals(PrimaryAtom, u32le(delivered, 12))
                assertEquals(AtomAtom, u32le(delivered, 16))
                assertEquals(StringAtom, u32le(delivered, 20))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket, byteOrderByte: Int = 0x6c) {
        setupReply(socket, byteOrderByte)
    }

    private fun setupReply(socket: Socket, byteOrderByte: Int = 0x6c): ByteArray {
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
        val payload = socket.getInputStream().readExactly(payloadUnits * 4)
        return prefix + payload
    }

    private fun assertSetupSuccess(port: Int) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            setup(socket)
        }
    }

    private fun assertSetupFailure(port: Int) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            val setup = ByteArray(12)
            setup[0] = 0x6c
            put16le(setup, 2, 11)
            socket.getOutputStream().write(setup)
            socket.getOutputStream().flush()

            val prefix = socket.getInputStream().readExactly(8)
            assertEquals(0, prefix[0].toInt())
            val reasonLength = prefix[1].toInt() and 0xff
            assertEquals(11, u16le(prefix, 2))
            assertEquals(0, u16le(prefix, 4))
            val payloadUnits = u16le(prefix, 6)
            val reason = socket.getInputStream().readExactly(payloadUnits * 4)
            assertEquals("Access denied", reason.copyOfRange(0, reasonLength).decodeToString())
            assertZeroBytes(reason, reasonLength, reason.size)
        }
    }

    private fun createWindowRequest(
        id: Int,
        x: Int = 0,
        y: Int = 0,
        width: Int = 40,
        height: Int = 30,
        parent: Int = X11Ids.RootWindow,
        depth: Int = 24,
        windowClass: Int = XWindowClass.InputOutput,
        visual: Int = X11Ids.RootVisual,
        borderWidth: Int = 0,
        backgroundPixmap: Int? = null,
        backgroundPixel: Int? = null,
        borderPixmap: Int? = null,
        borderPixel: Int? = null,
        bitGravity: Int? = null,
        winGravity: Int? = null,
        backingStore: Int? = null,
        backingPlanes: Int? = null,
        backingPixel: Int? = null,
        overrideRedirect: Boolean? = null,
        overrideRedirectRaw: Int? = null,
        saveUnder: Boolean? = null,
        saveUnderRaw: Int? = null,
        eventMask: Int? = null,
        doNotPropagateMask: Int? = null,
        colormap: Int? = null,
        cursor: Int? = null,
    ): ByteArray {
        val extraValues = listOfNotNull(
            backgroundPixmap?.let { (1 shl 0) to it },
            backgroundPixel?.let { (1 shl 1) to it },
            borderPixmap?.let { (1 shl 2) to it },
            borderPixel?.let { (1 shl 3) to it },
            bitGravity?.let { (1 shl 4) to it },
            winGravity?.let { (1 shl 5) to it },
            backingStore?.let { (1 shl 6) to it },
            backingPlanes?.let { (1 shl 7) to it },
            backingPixel?.let { (1 shl 8) to it },
            (overrideRedirectRaw ?: overrideRedirect?.let { if (it) 1 else 0 })?.let { (1 shl 9) to it },
            (saveUnderRaw ?: saveUnder?.let { if (it) 1 else 0 })?.let { (1 shl 10) to it },
            eventMask?.let { (1 shl 11) to it },
            doNotPropagateMask?.let { (1 shl 12) to it },
            colormap?.let { (1 shl 13) to it },
            cursor?.let { (1 shl 14) to it },
        )
        val body = ByteArray(28 + extraValues.size * 4)
        put32le(body, 0, id)
        put32le(body, 4, parent)
        put16le(body, 8, x)
        put16le(body, 10, y)
        put16le(body, 12, width)
        put16le(body, 14, height)
        put16le(body, 16, borderWidth)
        put16le(body, 18, windowClass)
        put32le(body, 20, visual)
        var valueMask = 0
        var offset = 28
        for ((mask, value) in extraValues) {
            valueMask = valueMask or mask
            put32le(body, offset, value)
            offset += 4
        }
        put32le(body, 24, valueMask)
        return request(1, depth, body)
    }

    private fun createWindowRawRequest(
        id: Int,
        x: Int = 0,
        y: Int = 0,
        width: Int = 40,
        height: Int = 30,
        parent: Int = X11Ids.RootWindow,
        depth: Int = 24,
        windowClass: Int = XWindowClass.InputOutput,
        visual: Int = X11Ids.RootVisual,
        borderWidth: Int = 0,
        valueMask: Int = 0,
        values: List<Int> = emptyList(),
    ): ByteArray {
        val body = ByteArray(28 + values.size * 4)
        put32le(body, 0, id)
        put32le(body, 4, parent)
        put16le(body, 8, x)
        put16le(body, 10, y)
        put16le(body, 12, width)
        put16le(body, 14, height)
        put16le(body, 16, borderWidth)
        put16le(body, 18, windowClass)
        put32le(body, 20, visual)
        put32le(body, 24, valueMask)
        values.forEachIndexed { index, value ->
            put32le(body, 28 + index * 4, value)
        }
        return request(1, depth, body)
    }

    private fun destroyWindowRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(4, 0, body)
    }

    private fun destroySubwindowsRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(5, 0, body)
    }

    private fun createWindowRequestBigEndian(
        id: Int,
        width: Int = 40,
        height: Int = 30,
        parent: Int = X11Ids.RootWindow,
    ): ByteArray {
        val body = ByteArray(28)
        put32be(body, 0, id)
        put32be(body, 4, parent)
        put16be(body, 12, width)
        put16be(body, 14, height)
        put16be(body, 18, 1)
        put32be(body, 20, X11Ids.RootVisual)
        return requestBigEndian(1, 24, body)
    }

    private fun createPixmapRequest(id: Int, width: Int, height: Int, depth: Int = 24, drawable: Int = WindowId): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, drawable)
        put16le(body, 8, width)
        put16le(body, 10, height)
        return request(53, depth, body)
    }

    private fun createPixmapBadLengthRequest(bodySize: Int): ByteArray =
        request(53, 24, ByteArray(bodySize))

    private fun getGeometryRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(14, 0, body)
    }

    private fun getGeometryRequestBigEndian(id: Int): ByteArray {
        val body = ByteArray(4)
        put32be(body, 0, id)
        return requestBigEndian(14, 0, body)
    }

    private fun freePixmapRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(54, 0, body)
    }

    private fun freeGcRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(60, 0, body)
    }

    private fun createCursorRequest(
        cursor: Int,
        source: Int,
        mask: Int,
        x: Int = 0,
        y: Int = 0,
        foregroundRed: Int = 0xffff,
        foregroundGreen: Int = 0,
        foregroundBlue: Int = 0,
        backgroundRed: Int = 0xffff,
        backgroundGreen: Int = 0,
        backgroundBlue: Int = 0,
    ): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, cursor)
        put32le(body, 4, source)
        put32le(body, 8, mask)
        put16le(body, 12, foregroundRed)
        put16le(body, 14, foregroundGreen)
        put16le(body, 16, foregroundBlue)
        put16le(body, 18, backgroundRed)
        put16le(body, 20, backgroundGreen)
        put16le(body, 22, backgroundBlue)
        put16le(body, 24, x)
        put16le(body, 26, y)
        return request(93, 0, body)
    }

    private fun freeCursorRequest(cursor: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, cursor)
        return request(95, 0, body)
    }

    private fun createGlyphCursorRequest(
        cursor: Int,
        sourceFont: Int,
        maskFont: Int,
        sourceChar: Int = 0,
        maskChar: Int = 0,
        foregroundRed: Int = 0xffff,
        foregroundGreen: Int = 0,
        foregroundBlue: Int = 0,
        backgroundRed: Int = 0xffff,
        backgroundGreen: Int = 0,
        backgroundBlue: Int = 0,
    ): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, cursor)
        put32le(body, 4, sourceFont)
        put32le(body, 8, maskFont)
        put16le(body, 12, sourceChar)
        put16le(body, 14, maskChar)
        put16le(body, 16, foregroundRed)
        put16le(body, 18, foregroundGreen)
        put16le(body, 20, foregroundBlue)
        put16le(body, 22, backgroundRed)
        put16le(body, 24, backgroundGreen)
        put16le(body, 26, backgroundBlue)
        return request(94, 0, body)
    }

    private fun recolorCursorRequest(
        cursor: Int,
        foregroundRed: Int = 0xffff,
        foregroundGreen: Int = 0,
        foregroundBlue: Int = 0,
        backgroundRed: Int = 0xffff,
        backgroundGreen: Int = 0,
        backgroundBlue: Int = 0,
    ): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, cursor)
        put16le(body, 4, foregroundRed)
        put16le(body, 6, foregroundGreen)
        put16le(body, 8, foregroundBlue)
        put16le(body, 10, backgroundRed)
        put16le(body, 12, backgroundGreen)
        put16le(body, 14, backgroundBlue)
        return request(96, 0, body)
    }

    private fun queryBestSizeRequest(sizeClass: Int, drawable: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, drawable)
        put16le(body, 4, width)
        put16le(body, 6, height)
        return request(97, sizeClass, body)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(4 + paddedLength(nameBytes.size))
        put16le(body, 0, nameBytes.size)
        nameBytes.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun xfixesQueryVersionRequest(major: Int, minor: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, major)
        put32le(body, 4, minor)
        return request(XFixes.MajorOpcode, XFixes.QueryVersion, body)
    }

    private fun xfixesChangeSaveSetRequest(mode: Int, target: Int, map: Int, window: Int): ByteArray {
        val body = ByteArray(8)
        body[0] = mode.toByte()
        body[1] = target.toByte()
        body[2] = map.toByte()
        put32le(body, 4, window)
        return request(XFixes.MajorOpcode, XFixes.ChangeSaveSet, body)
    }

    private fun xfixesSelectSelectionInputRequest(window: Int, selection: Int, eventMask: Int = 1): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, selection)
        put32le(body, 8, eventMask)
        return request(XFixes.MajorOpcode, XFixes.SelectSelectionInput, body)
    }

    private fun xfixesSelectCursorInputRequest(window: Int, eventMask: Int = 1): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, eventMask)
        return request(XFixes.MajorOpcode, XFixes.SelectCursorInput, body)
    }

    private fun xfixesGetCursorImageRequest(): ByteArray =
        request(XFixes.MajorOpcode, XFixes.GetCursorImage, ByteArray(0))

    private fun xfixesSetCursorNameRequest(cursor: Int, name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(8 + paddedLength(nameBytes.size))
        put32le(body, 0, cursor)
        put16le(body, 4, nameBytes.size)
        nameBytes.copyInto(body, 8)
        return request(XFixes.MajorOpcode, XFixes.SetCursorName, body)
    }

    private fun xfixesGetCursorNameRequest(cursor: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, cursor)
        return request(XFixes.MajorOpcode, XFixes.GetCursorName, body)
    }

    private fun xfixesGetCursorImageAndNameRequest(): ByteArray =
        request(XFixes.MajorOpcode, XFixes.GetCursorImageAndName, ByteArray(0))

    private fun xfixesChangeCursorRequest(source: Int, destination: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, source)
        put32le(body, 4, destination)
        return request(XFixes.MajorOpcode, XFixes.ChangeCursor, body)
    }

    private fun xfixesChangeCursorByNameRequest(source: Int, name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(8 + paddedLength(nameBytes.size))
        put32le(body, 0, source)
        put16le(body, 4, nameBytes.size)
        nameBytes.copyInto(body, 8)
        return request(XFixes.MajorOpcode, XFixes.ChangeCursorByName, body)
    }

    private fun openFontRequest(font: Int, name: String = "fixed"): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(8 + paddedLength(nameBytes.size))
        put32le(body, 0, font)
        put16le(body, 4, nameBytes.size)
        nameBytes.copyInto(body, 8)
        return request(45, 0, body)
    }

    private fun malformedOpenFontRequest(font: Int, declaredNameLength: Int, nameBytes: ByteArray): ByteArray {
        val body = ByteArray(8 + paddedLength(nameBytes.size))
        put32le(body, 0, font)
        put16le(body, 4, declaredNameLength)
        nameBytes.copyInto(body, 8)
        return request(45, 0, body)
    }

    private fun closeFontRequest(font: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, font)
        return request(46, 0, body)
    }

    private fun createColormapRequest(
        colormap: Int,
        window: Int,
        visual: Int = X11Ids.RootVisual,
        alloc: Int = 0,
    ): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, colormap)
        put32le(body, 4, window)
        put32le(body, 8, visual)
        return request(78, alloc, body)
    }

    private fun freeColormapRequest(colormap: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, colormap)
        return request(79, 0, body)
    }

    private fun copyColormapAndFreeRequest(colormap: Int, source: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, colormap)
        put32le(body, 4, source)
        return request(80, 0, body)
    }

    private fun installColormapRequest(colormap: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, colormap)
        return request(81, 0, body)
    }

    private fun uninstallColormapRequest(colormap: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, colormap)
        return request(82, 0, body)
    }

    private fun listInstalledColormapsRequest(window: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, window)
        return request(83, 0, body)
    }

    private fun allocColorRequest(colormap: Int, red: Int, green: Int, blue: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, colormap)
        put16le(body, 4, red)
        put16le(body, 6, green)
        put16le(body, 8, blue)
        return request(84, 0, body)
    }

    private fun allocColorCellsRequest(colormap: Int, colors: Int, planes: Int, contiguous: Int = 0): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, colormap)
        put16le(body, 4, colors)
        put16le(body, 6, planes)
        return request(86, contiguous, body)
    }

    private fun allocColorPlanesRequest(
        colormap: Int,
        colors: Int,
        reds: Int,
        greens: Int,
        blues: Int,
        contiguous: Int = 0,
    ): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, colormap)
        put16le(body, 4, colors)
        put16le(body, 6, reds)
        put16le(body, 8, greens)
        put16le(body, 10, blues)
        return request(87, contiguous, body)
    }

    private fun freeColorsRequest(colormap: Int, planeMask: Int, pixels: List<Int>): ByteArray {
        val body = ByteArray(8 + pixels.size * 4)
        put32le(body, 0, colormap)
        put32le(body, 4, planeMask)
        pixels.forEachIndexed { index, pixel ->
            put32le(body, 8 + index * 4, pixel)
        }
        return request(88, 0, body)
    }

    private fun storeColorsRequest(colormap: Int, colors: List<XStoreColorItem>): ByteArray {
        val body = ByteArray(4 + colors.size * 12)
        put32le(body, 0, colormap)
        colors.forEachIndexed { index, color ->
            val offset = 4 + index * 12
            put32le(body, offset, color.pixel)
            put16le(body, offset + 4, color.red)
            put16le(body, offset + 6, color.green)
            put16le(body, offset + 8, color.blue)
            body[offset + 10] = color.flags.toByte()
        }
        return request(89, 0, body)
    }

    private fun storeNamedColorRequest(colormap: Int, pixel: Int, name: String, flags: Int): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(12 + paddedLength(nameBytes.size))
        put32le(body, 0, colormap)
        put32le(body, 4, pixel)
        put16le(body, 8, nameBytes.size)
        nameBytes.copyInto(body, 12)
        return request(90, flags, body)
    }

    private fun malformedStoreNamedColorRequest(colormap: Int, pixel: Int, declaredNameLength: Int, actualName: String): ByteArray {
        val nameBytes = actualName.encodeToByteArray()
        val body = ByteArray(12 + paddedLength(nameBytes.size))
        put32le(body, 0, colormap)
        put32le(body, 4, pixel)
        put16le(body, 8, declaredNameLength)
        nameBytes.copyInto(body, 12)
        return request(90, 0x07, body)
    }

    private fun queryColorsRequest(colormap: Int, pixels: List<Int>): ByteArray {
        val body = ByteArray(4 + pixels.size * 4)
        put32le(body, 0, colormap)
        pixels.forEachIndexed { index, pixel ->
            put32le(body, 4 + index * 4, pixel)
        }
        return request(91, 0, body)
    }

    private fun allocNamedColorRequest(colormap: Int, name: String): ByteArray =
        namedColorRequest(opcode = 85, colormap = colormap, name = name)

    private fun lookupColorRequest(colormap: Int, name: String): ByteArray =
        namedColorRequest(opcode = 92, colormap = colormap, name = name)

    private fun namedColorRequest(opcode: Int, colormap: Int, name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(8 + paddedLength(nameBytes.size))
        put32le(body, 0, colormap)
        put16le(body, 4, nameBytes.size)
        nameBytes.copyInto(body, 8)
        return request(opcode, 0, body)
    }

    private fun grabPointerRequest(window: Int, cursor: Int = 0, time: Int = 0, eventMask: Int = 0, confineTo: Int = 0): ByteArray {
        val body = ByteArray(20)
        put32le(body, 0, window)
        put16le(body, 4, eventMask)
        body[6] = 0
        body[7] = 0
        put32le(body, 8, confineTo)
        put32le(body, 12, cursor)
        put32le(body, 16, time)
        return request(26, 0, body)
    }

    private fun ungrabPointerRequest(time: Int = 0): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, time)
        return request(27, 0, body)
    }

    private fun ungrabPointerBadLengthRequest(): ByteArray =
        request(27, 0, ByteArray(0))

    private fun grabPointerOversizedRequest(): ByteArray =
        request(26, 0, ByteArray(24))

    private fun changeActivePointerGrabRequest(cursor: Int = 0, time: Int = 0, eventMask: Int = 0): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, cursor)
        put32le(body, 4, time)
        put16le(body, 8, eventMask)
        return request(30, 0, body)
    }

    private fun changeActivePointerGrabBadLengthRequest(): ByteArray =
        request(30, 0, ByteArray(0))

    private fun grabButtonRequest(
        window: Int,
        ownerEvents: Int = 0,
        eventMask: Int = 0x000c,
        pointerMode: Int = 0,
        keyboardMode: Int = 0,
        confineTo: Int = 0,
        cursor: Int = 0,
        button: Int = 1,
        modifiers: Int = 0,
    ): ByteArray {
        val body = ByteArray(20)
        put32le(body, 0, window)
        put16le(body, 4, eventMask)
        body[6] = pointerMode.toByte()
        body[7] = keyboardMode.toByte()
        put32le(body, 8, confineTo)
        put32le(body, 12, cursor)
        body[16] = button.toByte()
        put16le(body, 18, modifiers)
        return request(28, ownerEvents, body)
    }

    private fun grabButtonBadLengthRequest(): ByteArray =
        request(28, 0, ByteArray(0))

    private fun ungrabButtonRequest(window: Int, button: Int = 1, modifiers: Int = 0): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put16le(body, 4, modifiers)
        return request(29, button, body)
    }

    private fun ungrabButtonBadLengthRequest(): ByteArray =
        request(29, 1, ByteArray(0))

    private fun grabKeyRequest(
        window: Int,
        ownerEvents: Int = 0,
        modifiers: Int = 0,
        key: Int = 8,
        pointerMode: Int = 0,
        keyboardMode: Int = 0,
    ): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put16le(body, 4, modifiers)
        body[6] = key.toByte()
        body[7] = pointerMode.toByte()
        body[8] = keyboardMode.toByte()
        return request(33, ownerEvents, body)
    }

    private fun grabKeyBadLengthRequest(): ByteArray =
        request(33, 0, ByteArray(0))

    private fun ungrabKeyRequest(window: Int, key: Int = 8, modifiers: Int = 0): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put16le(body, 4, modifiers)
        return request(34, key, body)
    }

    private fun ungrabKeyBadLengthRequest(): ByteArray =
        request(34, 8, ByteArray(0))

    private fun grabKeyboardRequest(window: Int, time: Int = 0): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, time)
        body[8] = 0
        body[9] = 0
        return request(31, 0, body)
    }

    private fun grabKeyboardOversizedRequest(): ByteArray =
        request(31, 0, ByteArray(16))

    private fun ungrabKeyboardRequest(time: Int = 0): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, time)
        return request(32, 0, body)
    }

    private fun ungrabKeyboardBadLengthRequest(): ByteArray =
        request(32, 0, ByteArray(0))

    private fun allowEventsRequest(mode: Int, time: Int = 0): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, time)
        return request(35, mode, body)
    }

    private fun allowEventsBadLengthRequest(): ByteArray =
        request(35, 0, ByteArray(0))

    private fun grabServerRequest(): ByteArray =
        request(36, 0, ByteArray(0))

    private fun ungrabServerRequest(): ByteArray =
        request(37, 0, ByteArray(0))

    private fun setInputFocusRequest(window: Int, revertTo: Int, time: Int = 0): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, time)
        return request(42, revertTo, body)
    }

    private fun setInputFocusOversizedRequest(): ByteArray =
        request(42, 0, ByteArray(12))

    private fun getInputFocusRequest(): ByteArray =
        request(43, 0, ByteArray(0))

    private fun queryKeymapRequest(): ByteArray =
        request(44, 0, ByteArray(0))

    private fun queryTreeRequest(window: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, window)
        return request(15, 0, body)
    }

    private fun changeSaveSetRequest(mode: Int, window: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, window)
        return request(6, mode, body)
    }

    private fun reparentWindowRequest(window: Int, parent: Int, x: Int, y: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, parent)
        put16le(body, 8, x)
        put16le(body, 10, y)
        return request(7, 0, body)
    }

    private fun circulateWindowRequest(direction: Int, window: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, window)
        return request(13, direction, body)
    }

    private fun setScreenSaverRequest(timeout: Int, interval: Int, preferBlanking: Int, allowExposures: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, timeout)
        put16le(body, 2, interval)
        body[4] = preferBlanking.toByte()
        body[5] = allowExposures.toByte()
        return request(107, 0, body)
    }

    private fun getScreenSaverRequest(): ByteArray =
        request(108, 0, ByteArray(0))

    private fun forceScreenSaverRequest(mode: Int): ByteArray =
        request(115, mode, ByteArray(0))

    private fun setCloseDownModeRequest(mode: Int): ByteArray =
        request(112, mode, ByteArray(0))

    private fun killClientRequest(resource: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, resource)
        return request(113, 0, body)
    }

    private fun noOperationRequest(vararg bytes: Int): ByteArray =
        request(127, 0, ByteArray(bytes.size) { index -> bytes[index].toByte() })

    private fun setAccessControlRequest(mode: Int): ByteArray =
        request(111, mode, ByteArray(0))

    private fun listHostsRequest(): ByteArray =
        request(110, 0, ByteArray(0))

    private fun changeHostsRequest(mode: Int, host: AccessHost): ByteArray {
        val body = ByteArray(4 + paddedLength(host.address.size))
        body[0] = host.family.toByte()
        put16le(body, 2, host.address.size)
        host.address.forEachIndexed { index, value -> body[4 + index] = value.toByte() }
        return request(109, mode, body)
    }

    private fun changeHostsBadLengthRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 2, 5)
        return request(109, 0, body)
    }

    private fun bellRequest(percent: Int): ByteArray =
        request(104, percent and 0xff, ByteArray(0))

    private fun getKeyboardControlRequest(): ByteArray =
        request(103, 0, ByteArray(0))

    private fun getKeyboardControlRequestBigEndian(): ByteArray =
        requestBigEndian(103, 0, ByteArray(0))

    private fun changeKeyboardControlRequest(vararg values: Pair<Int, Int>): ByteArray {
        val mask = values.fold(0) { acc, (bit, _) -> acc or bit }
        val body = ByteArray(4 + values.size * 4)
        put32le(body, 0, mask)
        values.forEachIndexed { index, (_, value) ->
            put32le(body, 4 + index * 4, value)
        }
        return request(102, 0, body)
    }

    private fun changeKeyboardControlRawRequest(mask: Int, vararg valueSlots: ByteArray): ByteArray {
        val body = ByteArray(4 + valueSlots.size * 4)
        put32le(body, 0, mask)
        valueSlots.forEachIndexed { index, bytes ->
            assertEquals(4, bytes.size)
            bytes.copyInto(body, 4 + index * 4)
        }
        return request(102, 0, body)
    }

    private fun changeKeyboardControlRawRequestBigEndian(mask: Int, vararg valueSlots: ByteArray): ByteArray {
        val body = ByteArray(4 + valueSlots.size * 4)
        put32be(body, 0, mask)
        valueSlots.forEachIndexed { index, bytes ->
            assertEquals(4, bytes.size)
            bytes.copyInto(body, 4 + index * 4)
        }
        return requestBigEndian(102, 0, body)
    }

    private fun keyboardControlValueSlot(vararg bytes: Int): ByteArray {
        assertEquals(4, bytes.size)
        return ByteArray(4) { index -> bytes[index].toByte() }
    }

    private fun setPointerMappingRequest(vararg mapping: Int): ByteArray {
        val body = ByteArray(paddedLength(mapping.size))
        mapping.forEachIndexed { index, value -> body[index] = value.toByte() }
        return request(116, mapping.size, body)
    }

    private fun pointerMapping(vararg overrides: Pair<Int, Int>): IntArray {
        val mapping = IntArray(255) { index -> index + 1 }
        overrides.forEach { (physical, logical) ->
            mapping[physical - 1] = logical
        }
        return mapping
    }

    private fun getPointerMappingRequest(): ByteArray =
        request(117, 0, ByteArray(0))

    private fun setModifierMappingRequest(keycodesPerModifier: Int, vararg keycodes: Int): ByteArray {
        assertEquals(8 * keycodesPerModifier, keycodes.size)
        val body = ByteArray(paddedLength(keycodes.size))
        keycodes.forEachIndexed { index, value -> body[index] = value.toByte() }
        return request(118, keycodesPerModifier, body)
    }

    private fun getModifierMappingRequest(): ByteArray =
        request(119, 0, ByteArray(0))

    private fun changePointerControlRequest(
        numerator: Int,
        denominator: Int,
        threshold: Int,
        doAcceleration: Int,
        doThreshold: Int,
    ): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, numerator)
        put16le(body, 2, denominator)
        put16le(body, 4, threshold)
        body[6] = doAcceleration.toByte()
        body[7] = doThreshold.toByte()
        return request(105, 0, body)
    }

    private fun getPointerControlRequest(): ByteArray =
        request(106, 0, ByteArray(0))

    private fun changePropertyRequest(window: Int, property: Int, type: Int, value: String): ByteArray {
        val data = value.encodeToByteArray()
        val body = ByteArray(20 + paddedLength(data.size))
        put32le(body, 0, window)
        put32le(body, 4, property)
        put32le(body, 8, type)
        body[12] = 8
        put32le(body, 16, data.size)
        data.copyInto(body, 20)
        return request(18, 0, body)
    }

    private fun changePropertyRawRequest(
        window: Int,
        property: Int,
        type: Int,
        format: Int,
        mode: Int = 0,
        units: Int? = null,
        data: ByteArray,
    ): ByteArray {
        val body = ByteArray(20 + paddedLength(data.size))
        put32le(body, 0, window)
        put32le(body, 4, property)
        put32le(body, 8, type)
        body[12] = format.toByte()
        put32le(body, 16, units ?: when (format) {
            8 -> data.size
            16 -> data.size / 2
            32 -> data.size / 4
            else -> 0
        })
        data.copyInto(body, 20)
        return request(18, mode, body)
    }

    private fun changePropertyRawRequestBigEndian(
        window: Int,
        property: Int,
        type: Int,
        format: Int,
        mode: Int = 0,
        units: Int? = null,
        data: ByteArray,
    ): ByteArray {
        val body = ByteArray(20 + paddedLength(data.size))
        put32be(body, 0, window)
        put32be(body, 4, property)
        put32be(body, 8, type)
        body[12] = format.toByte()
        put32be(body, 16, units ?: when (format) {
            8 -> data.size
            16 -> data.size / 2
            32 -> data.size / 4
            else -> 0
        })
        data.copyInto(body, 20)
        return requestBigEndian(18, mode, body)
    }

    private fun changeWindowEventMaskRequest(window: Int, eventMask: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, 1 shl 11)
        put32le(body, 8, eventMask)
        return request(2, 0, body)
    }

    private fun changeWindowAttributesRawRequest(window: Int, mask: Int, vararg values: Int): ByteArray {
        val body = ByteArray(8 + values.size * 4)
        put32le(body, 0, window)
        put32le(body, 4, mask)
        values.forEachIndexed { index, value ->
            put32le(body, 8 + index * 4, value)
        }
        return request(2, 0, body)
    }

    private fun getPropertyRequest(window: Int, property: Int, type: Int): ByteArray {
        return getPropertyRawRequest(window, property, type)
    }

    private fun getPropertyRawRequest(
        window: Int,
        property: Int,
        type: Int,
        delete: Int = 0,
        longOffset: Int = 0,
        longLength: Int = 1024,
    ): ByteArray {
        val body = ByteArray(20)
        put32le(body, 0, window)
        put32le(body, 4, property)
        put32le(body, 8, type)
        put32le(body, 12, longOffset)
        put32le(body, 16, longLength)
        return request(20, delete, body)
    }

    private fun getPropertyRequestBigEndian(window: Int, property: Int, type: Int): ByteArray {
        val body = ByteArray(20)
        put32be(body, 0, window)
        put32be(body, 4, property)
        put32be(body, 8, type)
        put32be(body, 16, 1024)
        return requestBigEndian(20, 0, body)
    }

    private fun deletePropertyRequest(window: Int, property: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, property)
        return request(19, 0, body)
    }

    private fun listPropertiesRequest(window: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, window)
        return request(21, 0, body)
    }

    private fun rotatePropertiesRequest(window: Int, delta: Int, vararg properties: Int): ByteArray {
        val body = ByteArray(8 + properties.size * 4)
        put32le(body, 0, window)
        put16le(body, 4, properties.size)
        put16le(body, 6, delta)
        properties.forEachIndexed { index, property ->
            put32le(body, 8 + index * 4, property)
        }
        return request(114, 0, body)
    }

    private fun rotatePropertiesBadLengthRequest(window: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put16le(body, 4, 2)
        put16le(body, 6, 1)
        return request(114, 0, body)
    }

    private fun setSelectionOwnerRequest(owner: Int, selection: Int, time: Int = 0, extraBytes: Int = 0): ByteArray {
        val body = ByteArray(12 + extraBytes)
        put32le(body, 0, owner)
        put32le(body, 4, selection)
        put32le(body, 8, time)
        return request(22, 0, body)
    }

    private fun getSelectionOwnerRequest(selection: Int, extraBytes: Int = 0): ByteArray =
        request(23, 0, ByteArray(4 + extraBytes).also { put32le(it, 0, selection) })

    private fun convertSelectionRequest(
        requestor: Int,
        selection: Int,
        target: Int,
        property: Int,
        time: Int = 0,
        extraBytes: Int = 0,
    ): ByteArray {
        val body = ByteArray(20 + extraBytes)
        put32le(body, 0, requestor)
        put32le(body, 4, selection)
        put32le(body, 8, target)
        put32le(body, 12, property)
        put32le(body, 16, time)
        return request(24, 0, body)
    }

    private fun sendEventRequest(
        destination: Int,
        event: ByteArray,
        eventMask: Int = 0,
        propagate: Boolean = false,
        propagateOpcode: Int = if (propagate) 1 else 0,
        extraBytes: Int = 0,
    ): ByteArray {
        val body = ByteArray(40 + extraBytes)
        put32le(body, 0, destination)
        put32le(body, 4, eventMask)
        event.copyInto(body, 8, endIndex = 32)
        return request(25, propagateOpcode, body)
    }

    private fun selectionNotifyEvent(requestor: Int, selection: Int, target: Int, property: Int, time: Int): ByteArray {
        val event = ByteArray(32)
        event[0] = 31
        put32le(event, 4, time)
        put32le(event, 8, requestor)
        put32le(event, 12, selection)
        put32le(event, 16, target)
        put32le(event, 20, property)
        return event
    }

    private fun clientMessageEvent(
        window: Int,
        type: Int,
        format: Int,
        data32: List<Int> = emptyList(),
        data8: ByteArray = ByteArray(0),
    ): ByteArray {
        val event = ByteArray(32)
        event[0] = 33
        event[1] = format.toByte()
        put32le(event, 4, window)
        put32le(event, 8, type)
        when (format) {
            32 -> data32.forEachIndexed { index, value -> put32le(event, 12 + index * 4, value) }
            8 -> data8.copyInto(event, 12, endIndex = data8.size.coerceAtMost(20))
        }
        return event
    }

    private fun xfixesSelectionNotifyEvent(window: Int, owner: Int, selection: Int, timestamp: Int, selectionTimestamp: Int): ByteArray {
        val event = ByteArray(32)
        event[0] = (XFixes.FirstEvent + XFixes.SelectionNotify).toByte()
        event[1] = XFixes.SetSelectionOwnerNotify.toByte()
        put32le(event, 4, window)
        put32le(event, 8, owner)
        put32le(event, 12, selection)
        put32le(event, 16, timestamp)
        put32le(event, 20, selectionTimestamp)
        return event
    }

    private fun createNotifyEvent(parent: Int, window: Int, x: Int, y: Int, width: Int, height: Int, borderWidth: Int): ByteArray {
        val event = ByteArray(32)
        event[0] = 16
        put32le(event, 4, parent)
        put32le(event, 8, window)
        put16le(event, 12, x)
        put16le(event, 14, y)
        put16le(event, 16, width)
        put16le(event, 18, height)
        put16le(event, 20, borderWidth)
        return event
    }

    private fun resizeRequestEvent(window: Int, width: Int, height: Int): ByteArray {
        val event = ByteArray(32)
        event[0] = 25
        put32le(event, 4, window)
        put16le(event, 8, width)
        put16le(event, 10, height)
        return event
    }

    private fun queryPointerRequest(): ByteArray =
        request(38, 0, ByteArray(4).also { put32le(it, 0, X11Ids.RootWindow) })

    private fun getWindowAttributesRequest(window: Int): ByteArray =
        request(3, 0, ByteArray(4).also { put32le(it, 0, window) })

    private fun getMotionEventsRequest(window: Int, start: Int, stop: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, start)
        put32le(body, 8, stop)
        return request(39, 0, body)
    }

    private fun getMotionEventsBadLengthRequest(): ByteArray =
        request(39, 0, ByteArray(0))

    private fun translateCoordinatesRequest(sourceWindow: Int, destinationWindow: Int, sourceX: Int = 0, sourceY: Int = 0): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, sourceWindow)
        put32le(body, 4, destinationWindow)
        put16le(body, 8, sourceX)
        put16le(body, 10, sourceY)
        return request(40, 0, body)
    }

    private fun translateCoordinatesBadLengthRequest(bodySize: Int = 8): ByteArray =
        request(40, 0, ByteArray(bodySize))

    private fun warpPointerRequest(
        sourceWindow: Int = 0,
        destinationWindow: Int = 0,
        sourceX: Int = 0,
        sourceY: Int = 0,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
        destinationX: Int = 0,
        destinationY: Int = 0,
    ): ByteArray {
        val body = ByteArray(20)
        put32le(body, 0, sourceWindow)
        put32le(body, 4, destinationWindow)
        put16le(body, 8, sourceX)
        put16le(body, 10, sourceY)
        put16le(body, 12, sourceWidth)
        put16le(body, 14, sourceHeight)
        put16le(body, 16, destinationX)
        put16le(body, 18, destinationY)
        return request(41, 0, body)
    }

    private fun warpPointerBadLengthRequest(): ByteArray =
        request(41, 0, ByteArray(16))

    private fun mapWindowRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(8, 0, body)
    }

    private fun mapSubwindowsRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(9, 0, body)
    }

    private fun unmapWindowRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(10, 0, body)
    }

    private fun unmapSubwindowsRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(11, 0, body)
    }

    private fun configureWindowRequest(window: Int, mask: Int, vararg values: Int): ByteArray {
        val body = ByteArray(8 + values.size * 4)
        put32le(body, 0, window)
        put16le(body, 4, mask)
        values.forEachIndexed { index, value ->
            put32le(body, 8 + index * 4, value)
        }
        return request(12, 0, body)
    }

    private fun internAtomRequest(name: String, onlyIfExists: Boolean = false): ByteArray {
        val bytes = name.encodeToByteArray()
        val body = ByteArray(4 + paddedLength(bytes.size))
        put16le(body, 0, bytes.size)
        bytes.copyInto(body, 4)
        return request(16, if (onlyIfExists) 1 else 0, body)
    }

    private fun getAtomNameRequest(atom: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, atom)
        return request(17, 0, body)
    }

    private fun changeWindowBackgroundPixmapRequest(id: Int, pixmap: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, 0x0000_0001)
        put32le(body, 8, pixmap)
        return request(2, 0, body)
    }

    private fun clearAreaRequest(id: Int, x: Int, y: Int, width: Int, height: Int, exposures: Int = 0): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put16le(body, 4, x)
        put16le(body, 6, y)
        put16le(body, 8, width)
        put16le(body, 10, height)
        return request(61, exposures, body)
    }

    private fun createGcRequest(id: Int, foreground: Int, background: Int? = null, drawable: Int = WindowId): ByteArray {
        val values = mutableListOf(foreground)
        var mask = 0x0000_0004
        if (background != null) {
            mask = mask or 0x0000_0008
            values += background
        }
        val body = ByteArray(12 + values.size * 4)
        put32le(body, 0, id)
        put32le(body, 4, drawable)
        put32le(body, 8, mask)
        values.forEachIndexed { index, value ->
            put32le(body, 12 + index * 4, value)
        }
        return request(55, 0, body)
    }

    private fun createGcRequestBigEndian(id: Int, drawable: Int, foreground: Int = 0): ByteArray {
        val body = ByteArray(16)
        put32be(body, 0, id)
        put32be(body, 4, drawable)
        put32be(body, 8, 0x0000_0004)
        put32be(body, 12, foreground)
        return requestBigEndian(55, 0, body)
    }

    private fun createGcRasterRequest(id: Int, function: Int): ByteArray {
        return createGcRawRequest(id, mask = 0x0000_0001, values = listOf(function))
    }

    private fun createGcRawRequest(id: Int, mask: Int, values: List<Int> = emptyList()): ByteArray {
        val body = ByteArray(12 + values.size * 4)
        put32le(body, 0, id)
        put32le(body, 4, WindowId)
        put32le(body, 8, mask)
        values.forEachIndexed { index, value ->
            put32le(body, 12 + index * 4, value)
        }
        return request(55, 0, body)
    }

    private fun changeGcLineWidthRequest(id: Int, lineWidth: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, 0x0000_0010)
        put32le(body, 8, lineWidth)
        return request(56, 0, body)
    }

    private fun changeGcLineStyleRequest(id: Int, lineStyle: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, 0x0000_0020)
        put32le(body, 8, lineStyle)
        return request(56, 0, body)
    }

    private fun changeGcDashAttributesRequest(id: Int, dashOffset: Int, dash: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, id)
        put32le(body, 4, 0x0030_0000)
        put32le(body, 8, dashOffset)
        put32le(body, 12, dash)
        return request(56, 0, body)
    }

    private fun changeGcFillRuleRequest(id: Int, fillRule: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, 0x0000_0200)
        put32le(body, 8, fillRule)
        return request(56, 0, body)
    }

    private fun changeGcTiledFillRequest(id: Int, tilePixmap: Int, xOrigin: Int, yOrigin: Int): ByteArray {
        val body = ByteArray(24)
        put32le(body, 0, id)
        put32le(body, 4, 0x0000_3500)
        put32le(body, 8, 1)
        put32le(body, 12, tilePixmap)
        put32le(body, 16, xOrigin)
        put32le(body, 20, yOrigin)
        return request(56, 0, body)
    }

    private fun changeGcStippledFillRequest(id: Int, fillStyle: Int, stipplePixmap: Int, xOrigin: Int, yOrigin: Int): ByteArray {
        val body = ByteArray(24)
        put32le(body, 0, id)
        put32le(body, 4, 0x0000_3900)
        put32le(body, 8, fillStyle)
        put32le(body, 12, stipplePixmap)
        put32le(body, 16, xOrigin)
        put32le(body, 20, yOrigin)
        return request(56, 0, body)
    }

    private fun changeGcArcModeRequest(id: Int, arcMode: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put32le(body, 4, 0x0040_0000)
        put32le(body, 8, arcMode)
        return request(56, 0, body)
    }

    private fun changeGcRasterRequest(id: Int, function: Int? = null, planeMask: Int? = null): ByteArray {
        val values = mutableListOf<Int>()
        var mask = 0
        if (function != null) {
            mask = mask or 0x0000_0001
            values += function
        }
        if (planeMask != null) {
            mask = mask or 0x0000_0002
            values += planeMask
        }
        val body = ByteArray(8 + values.size * 4)
        put32le(body, 0, id)
        put32le(body, 4, mask)
        values.forEachIndexed { index, value ->
            put32le(body, 8 + index * 4, value)
        }
        return request(56, 0, body)
    }

    private fun changeGcRawRequest(id: Int, mask: Int, values: List<Int> = emptyList()): ByteArray {
        val body = ByteArray(8 + values.size * 4)
        put32le(body, 0, id)
        put32le(body, 4, mask)
        values.forEachIndexed { index, value ->
            put32le(body, 8 + index * 4, value)
        }
        return request(56, 0, body)
    }

    private fun copyGcRequest(source: Int, destination: Int, mask: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, source)
        put32le(body, 4, destination)
        put32le(body, 8, mask)
        return request(57, 0, body)
    }

    private fun copyGcBadLengthRequest(bodySize: Int): ByteArray =
        request(57, 0, ByteArray(bodySize))

    private fun setDashesRequest(gc: Int, dashOffset: Int, dashes: List<Int>): ByteArray {
        val paddedDashBytes = (dashes.size + 3) and -4
        val body = ByteArray(8 + paddedDashBytes)
        put32le(body, 0, gc)
        put16le(body, 4, dashOffset)
        put16le(body, 6, dashes.size)
        dashes.forEachIndexed { index, dash ->
            body[8 + index] = dash.toByte()
        }
        return request(58, 0, body)
    }

    private fun setDashesBadLengthRequest(bodySize: Int, dashCount: Int = 0): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, GcId)
        if (bodySize >= 8) put16le(body, 6, dashCount)
        return request(58, 0, body)
    }

    private fun polyPointRequest(drawable: Int, gc: Int, coordMode: Int, points: List<Pair<Int, Int>>): ByteArray {
        val body = ByteArray(8 + points.size * 4)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        var offset = 8
        for ((x, y) in points) {
            put16le(body, offset, x)
            put16le(body, offset + 2, y)
            offset += 4
        }
        return request(64, coordMode, body)
    }

    private fun polyPointBadLengthRequest(): ByteArray =
        request(64, 0, ByteArray(4))

    private fun polyLineRequest(drawable: Int, gc: Int, coordMode: Int = 0, points: List<Pair<Int, Int>>): ByteArray {
        val body = ByteArray(8 + points.size * 4)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        var offset = 8
        for ((x, y) in points) {
            put16le(body, offset, x)
            put16le(body, offset + 2, y)
            offset += 4
        }
        return request(65, coordMode, body)
    }

    private fun polyLineBadLengthRequest(): ByteArray =
        request(65, 0, ByteArray(4))

    private fun polySegmentRequest(drawable: Int, gc: Int, segments: List<Pair<Pair<Int, Int>, Pair<Int, Int>>>): ByteArray {
        val body = ByteArray(8 + segments.size * 8)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        var offset = 8
        for ((start, end) in segments) {
            put16le(body, offset, start.first)
            put16le(body, offset + 2, start.second)
            put16le(body, offset + 4, end.first)
            put16le(body, offset + 6, end.second)
            offset += 8
        }
        return request(66, 0, body)
    }

    private fun polySegmentBadLengthRequest(bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        if (bodySize >= 8) put32le(body, 4, GcId)
        return request(66, 0, body)
    }

    private fun polyRectangleRequest(drawable: Int, gc: Int, rectangles: List<XRectangleCommand>): ByteArray {
        val body = ByteArray(8 + rectangles.size * 8)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        var offset = 8
        for (rectangle in rectangles) {
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
            offset += 8
        }
        return request(67, 0, body)
    }

    private fun polyFillRectangleRequest(drawable: Int, gc: Int, rectangles: List<XRectangleCommand>): ByteArray {
        val body = ByteArray(8 + rectangles.size * 8)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        var offset = 8
        for (rectangle in rectangles) {
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
            offset += 8
        }
        return request(70, 0, body)
    }

    private fun polyRectangleBadLengthRequest(opcode: Int, bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        if (bodySize >= 8) put32le(body, 4, GcId)
        return request(opcode, 0, body)
    }

    private fun polyArcRequest(drawable: Int, gc: Int, filled: Boolean, arcs: List<XArcCommand>): ByteArray {
        val body = ByteArray(8 + arcs.size * 12)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        var offset = 8
        for (arc in arcs) {
            put16le(body, offset, arc.x)
            put16le(body, offset + 2, arc.y)
            put16le(body, offset + 4, arc.width)
            put16le(body, offset + 6, arc.height)
            put16le(body, offset + 8, arc.angle1)
            put16le(body, offset + 10, arc.angle2)
            offset += 12
        }
        return request(if (filled) 71 else 68, 0, body)
    }

    private fun polyArcBadLengthRequest(opcode: Int, bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        if (bodySize >= 8) put32le(body, 4, GcId)
        return request(opcode, 0, body)
    }

    private fun setClipRectanglesRequest(
        gc: Int,
        clipXOrigin: Int,
        clipYOrigin: Int,
        rectangles: List<XRectangleCommand>,
        ordering: Int = 0,
    ): ByteArray {
        val body = ByteArray(8 + rectangles.size * 8)
        put32le(body, 0, gc)
        put16le(body, 4, clipXOrigin)
        put16le(body, 6, clipYOrigin)
        var offset = 8
        for (rectangle in rectangles) {
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
            offset += 8
        }
        return request(59, ordering, body)
    }

    private fun setClipRectanglesBadLengthRequest(bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, GcId)
        return request(59, 0, body)
    }

    private fun fillPolyRequest(
        drawable: Int,
        gc: Int,
        shape: Int = 2,
        coordMode: Int,
        points: List<Pair<Int, Int>> = listOf(1 to 1, 4 to 1, 1 to 4),
    ): ByteArray {
        val body = ByteArray(12 + points.size * 4)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        body[8] = shape.toByte()
        body[9] = coordMode.toByte()
        var offset = 12
        for ((x, y) in points) {
            put16le(body, offset, x)
            put16le(body, offset + 2, y)
            offset += 4
        }
        return request(69, 0, body)
    }

    private fun fillPolyBadLengthRequest(bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        if (bodySize >= 8) put32le(body, 4, GcId)
        return request(69, 0, body)
    }

    private fun putImageBadLengthRequest(bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        if (bodySize >= 8) put32le(body, 4, GcId)
        return request(72, 2, body)
    }

    private fun getImageBadLengthRequest(bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        return request(73, 2, body)
    }

    private fun copyAreaRequest(
        source: Int,
        destination: Int,
        gc: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val body = ByteArray(24)
        put32le(body, 0, source)
        put32le(body, 4, destination)
        put32le(body, 8, gc)
        put16le(body, 12, sourceX)
        put16le(body, 14, sourceY)
        put16le(body, 16, destinationX)
        put16le(body, 18, destinationY)
        put16le(body, 20, width)
        put16le(body, 22, height)
        return request(62, 0, body)
    }

    private fun copyPlaneRequest(
        source: Int,
        destination: Int,
        gc: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        bitPlane: Int,
    ): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, source)
        put32le(body, 4, destination)
        put32le(body, 8, gc)
        put16le(body, 12, sourceX)
        put16le(body, 14, sourceY)
        put16le(body, 16, destinationX)
        put16le(body, 18, destinationY)
        put16le(body, 20, width)
        put16le(body, 22, height)
        put32le(body, 24, bitPlane)
        return request(63, 0, body)
    }

    private fun putImage24Request(drawable: Int, width: Int, height: Int, pixel: Int, gc: Int = GcId): ByteArray {
        return putImage24PixelsRequest(drawable, width, height, List(width * height) { pixel }, gc)
    }

    private fun putImage24PixelsRequest(drawable: Int, width: Int, height: Int, pixels: List<Int>, gc: Int = GcId): ByteArray {
        require(pixels.size == width * height)
        val data = ByteArray(width * height * 4)
        for ((index, pixel) in pixels.withIndex()) {
            put32le(data, index * 4, pixel)
        }
        val body = ByteArray(20 + data.size)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, width)
        put16le(body, 10, height)
        body[17] = 24
        data.copyInto(body, 20)
        return request(72, 2, body)
    }

    private fun putImage24PixelsRequestBigEndian(drawable: Int, width: Int, height: Int, pixels: List<Int>, gc: Int = GcId): ByteArray {
        require(pixels.size == width * height)
        val data = ByteArray(width * height * 4)
        for ((index, pixel) in pixels.withIndex()) {
            put32le(data, index * 4, pixel)
        }
        val body = ByteArray(20 + data.size)
        put32be(body, 0, drawable)
        put32be(body, 4, gc)
        put16be(body, 8, width)
        put16be(body, 10, height)
        body[17] = 24
        data.copyInto(body, 20)
        return requestBigEndian(72, 2, body)
    }

    private fun putImageRawRequest(
        drawable: Int,
        gc: Int,
        format: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        leftPad: Int = 0,
        depth: Int,
        data: ByteArray,
    ): ByteArray {
        val body = ByteArray(20 + data.size)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, width)
        put16le(body, 10, height)
        put16le(body, 12, x)
        put16le(body, 14, y)
        body[16] = leftPad.toByte()
        body[17] = depth.toByte()
        data.copyInto(body, 20)
        return request(72, format, body)
    }

    private fun putImageBitmapRequest(
        drawable: Int,
        gc: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        leftPad: Int = 0,
        bits: List<Boolean>,
    ): ByteArray {
        require(bits.size == width * height)
        val stride = paddedLength((leftPad + width + 7) / 8)
        val data = ByteArray(stride * height)
        for ((index, bit) in bits.withIndex()) {
            if (!bit) continue
            val row = index / width
            val column = index % width
            val bitIndex = column + leftPad
            val offset = row * stride + bitIndex / 8
            data[offset] = (data[offset].toInt() or (1 shl (bitIndex % 8))).toByte()
        }
        return putImageRawRequest(drawable, gc, format = 0, width = width, height = height, x = x, y = y, leftPad = leftPad, depth = 1, data = data)
    }

    private fun putImageXyPixmapRequest(
        drawable: Int,
        gc: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        leftPad: Int = 0,
        depth: Int,
        pixels: List<Int>,
    ): ByteArray {
        require(pixels.size == width * height)
        val stride = paddedLength((leftPad + width + 7) / 8)
        val planeBytes = stride * height
        val data = ByteArray(planeBytes * depth)
        for (planeIndex in 0 until depth) {
            val bit = depth - 1 - planeIndex
            val mask = 1 shl bit
            val planeOffset = planeIndex * planeBytes
            for ((index, pixel) in pixels.withIndex()) {
                if ((pixel and mask) == 0) continue
                val row = index / width
                val column = index % width
                val bitIndex = column + leftPad
                val offset = planeOffset + row * stride + bitIndex / 8
                data[offset] = (data[offset].toInt() or (1 shl (bitIndex % 8))).toByte()
            }
        }
        return putImageRawRequest(drawable, gc, format = 1, width = width, height = height, x = x, y = y, leftPad = leftPad, depth = depth, data = data)
    }

    private fun putImage8PixelsRequest(drawable: Int, gc: Int = GcId, width: Int, height: Int, alphas: ByteArray): ByteArray {
        require(alphas.size == width * height)
        val stride = paddedLength(width)
        val data = ByteArray(stride * height)
        for (row in 0 until height) {
            alphas.copyInto(
                destination = data,
                destinationOffset = row * stride,
                startIndex = row * width,
                endIndex = (row + 1) * width,
            )
        }
        val body = ByteArray(20 + data.size)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, width)
        put16le(body, 10, height)
        body[17] = 8
        data.copyInto(body, 20)
        return request(72, 2, body)
    }

    private fun imageText8Request(drawable: Int, gc: Int, x: Int, y: Int, text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        val body = ByteArray(paddedLength(12 + bytes.size))
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, x)
        put16le(body, 10, y)
        bytes.copyInto(body, 12)
        return request(76, bytes.size, body)
    }

    private fun imageText16Request(drawable: Int, gc: Int, x: Int, y: Int, char2b: List<Pair<Int, Int>>): ByteArray {
        val body = ByteArray(paddedLength(12 + char2b.size * 2))
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, x)
        put16le(body, 10, y)
        var offset = 12
        for ((byte1, byte2) in char2b) {
            body[offset] = byte1.toByte()
            body[offset + 1] = byte2.toByte()
            offset += 2
        }
        return request(77, char2b.size, body)
    }

    private fun imageTextBadLengthRequest(opcode: Int, textLength: Int, bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        if (bodySize >= 8) put32le(body, 4, GcId)
        if (bodySize >= 10) put16le(body, 8, 4)
        if (bodySize >= 12) put16le(body, 10, 18)
        return request(opcode, textLength, body)
    }

    private fun polyText8Request(
        drawable: Int,
        gc: Int,
        x: Int,
        y: Int,
        delta: Int,
        text: String,
        padding: ByteArray = ByteArray(0),
    ): ByteArray {
        val bytes = text.encodeToByteArray()
        require(bytes.size in 0..254)
        val body = ByteArray(paddedLength(14 + bytes.size))
        require(padding.size <= body.size - 14 - bytes.size)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, x)
        put16le(body, 10, y)
        body[12] = bytes.size.toByte()
        body[13] = delta.toByte()
        bytes.copyInto(body, 14)
        padding.copyInto(body, 14 + bytes.size)
        return request(74, 0, body)
    }

    private fun polyText16Request(drawable: Int, gc: Int, x: Int, y: Int, delta: Int, char2b: List<Pair<Int, Int>>): ByteArray {
        require(char2b.size in 0..254)
        val body = ByteArray(paddedLength(14 + char2b.size * 2))
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, x)
        put16le(body, 10, y)
        body[12] = char2b.size.toByte()
        body[13] = delta.toByte()
        var offset = 14
        for ((byte1, byte2) in char2b) {
            body[offset] = byte1.toByte()
            body[offset + 1] = byte2.toByte()
            offset += 2
        }
        return request(75, 0, body)
    }

    private fun polyTextBadLengthRequest(opcode: Int, bodySize: Int): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 4) put32le(body, 0, WindowId)
        if (bodySize >= 8) put32le(body, 4, GcId)
        return request(opcode, 0, body)
    }

    private fun polyTextMalformedElementRequest(drawable: Int, gc: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, 2)
        put16le(body, 10, 14)
        body[12] = 4
        body[13] = 0
        body[14] = 'I'.code.toByte()
        return request(74, 0, body)
    }

    private fun polyTextFontItemRequest(drawable: Int, gc: Int, font: Int, text: String = ""): ByteArray {
        val bytes = text.encodeToByteArray()
        require(bytes.size in 0..254)
        val body = ByteArray(paddedLength(17 + if (bytes.isEmpty()) 0 else 2 + bytes.size))
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, 2)
        put16le(body, 10, 14)
        body[12] = 255.toByte()
        put32be(body, 13, font)
        if (bytes.isNotEmpty()) {
            body[17] = bytes.size.toByte()
            body[18] = 0
            bytes.copyInto(body, 19)
        }
        return request(74, 0, body)
    }

    private fun queryFontRequest(fontable: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, fontable)
        return request(47, 0, body)
    }

    private fun queryTextExtentsRequest(fontable: Int, char2b: List<Pair<Int, Int>>): ByteArray {
        val oddLength = char2b.size % 2
        return request(48, oddLength, queryTextExtentsBody(fontable, char2b))
    }

    private fun queryTextExtentsBody(fontable: Int, char2b: List<Pair<Int, Int>>): ByteArray {
        val oddLength = char2b.size % 2
        val body = ByteArray(4 + char2b.size * 2 + if (oddLength != 0) 2 else 0)
        put32le(body, 0, fontable)
        var offset = 4
        for ((byte1, byte2) in char2b) {
            body[offset] = byte1.toByte()
            body[offset + 1] = byte2.toByte()
            offset += 2
        }
        return body
    }

    private fun queryTextExtentsOddPaddingBadLengthRequest(fontable: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, fontable)
        return request(48, 1, body)
    }

    private fun listFontsRequest(pattern: String, maxNames: Int): ByteArray {
        val patternBytes = pattern.toByteArray(StandardCharsets.ISO_8859_1)
        val body = ByteArray(4 + paddedLength(patternBytes.size))
        put16le(body, 0, maxNames)
        put16le(body, 2, patternBytes.size)
        patternBytes.copyInto(body, 4)
        return request(49, 0, body)
    }

    private fun malformedListFontsRequest(declaredPatternLength: Int, patternBytes: ByteArray): ByteArray {
        val body = ByteArray(4 + paddedLength(patternBytes.size))
        put16le(body, 0, 100)
        put16le(body, 2, declaredPatternLength)
        patternBytes.copyInto(body, 4)
        return request(49, 0, body)
    }

    private fun listFontsWithInfoRequest(pattern: String, maxNames: Int): ByteArray {
        val patternBytes = pattern.toByteArray(StandardCharsets.ISO_8859_1)
        val body = ByteArray(4 + paddedLength(patternBytes.size))
        put16le(body, 0, maxNames)
        put16le(body, 2, patternBytes.size)
        patternBytes.copyInto(body, 4)
        return request(50, 0, body)
    }

    private fun malformedListFontsWithInfoRequest(declaredPatternLength: Int, patternBytes: ByteArray): ByteArray {
        val body = ByteArray(4 + paddedLength(patternBytes.size))
        put16le(body, 0, 100)
        put16le(body, 2, declaredPatternLength)
        patternBytes.copyInto(body, 4)
        return request(50, 0, body)
    }

    private fun setFontPathRequest(vararg path: String): ByteArray {
        val encoded = path.map { it.toByteArray(StandardCharsets.ISO_8859_1) }
        val payloadSize = encoded.sumOf { 1 + it.size }
        val body = ByteArray(4 + paddedLength(payloadSize))
        put16le(body, 0, encoded.size)
        var offset = 4
        for (entry in encoded) {
            body[offset++] = entry.size.toByte()
            entry.copyInto(body, offset)
            offset += entry.size
        }
        return request(51, 0, body)
    }

    private fun malformedSetFontPathRequest(count: Int, bytes: ByteArray): ByteArray {
        val body = ByteArray(4 + paddedLength(bytes.size))
        put16le(body, 0, count)
        bytes.copyInto(body, 4)
        return request(51, 0, body)
    }

    private fun getFontPathRequest(): ByteArray =
        request(52, 0, ByteArray(0))

    private fun getKeyboardMappingRequest(firstKeycode: Int, count: Int): ByteArray {
        val body = ByteArray(4)
        body[0] = firstKeycode.toByte()
        body[1] = count.toByte()
        return request(101, 0, body)
    }

    private fun changeKeyboardMappingRequest(firstKeycode: Int, keysymsPerKeycode: Int, vararg keysyms: Int): ByteArray {
        require(keysymsPerKeycode > 0)
        require(keysyms.size % keysymsPerKeycode == 0)
        return malformedChangeKeyboardMappingRequest(
            keycodeCount = keysyms.size / keysymsPerKeycode,
            firstKeycode = firstKeycode,
            keysymsPerKeycode = keysymsPerKeycode,
            keysyms = keysyms,
        )
    }

    private fun malformedChangeKeyboardMappingRequest(keycodeCount: Int, firstKeycode: Int, keysymsPerKeycode: Int, vararg keysyms: Int): ByteArray {
        val body = ByteArray(4 + keysyms.size * 4)
        body[0] = firstKeycode.toByte()
        body[1] = keysymsPerKeycode.toByte()
        keysyms.forEachIndexed { index, keysym ->
            put32le(body, 4 + index * 4, keysym)
        }
        return request(100, keycodeCount, body)
    }

    private fun fontPathEntries(reply: ByteArray): List<String> {
        val count = u16le(reply, 8)
        var offset = 32
        return List(count) {
            val length = reply[offset].toInt() and 0xff
            offset += 1
            val value = String(reply, offset, length, StandardCharsets.ISO_8859_1)
            offset += length
            value
        }
    }

    private fun fontNames(reply: ByteArray): List<String> {
        val count = u16le(reply, 8)
        var offset = 32
        return List(count) {
            val length = reply[offset].toInt() and 0xff
            offset += 1
            val value = String(reply, offset, length, StandardCharsets.ISO_8859_1)
            offset += length
            value
        }
    }

    private fun getImageRequest(
        drawable: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        planeMask: Int = 0xffff_ffff.toInt(),
        format: Int = 2,
    ): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, drawable)
        put16le(body, 4, x)
        put16le(body, 6, y)
        put16le(body, 8, width)
        put16le(body, 10, height)
        put32le(body, 12, planeMask)
        return request(73, format, body)
    }

    private fun getImageRequestBigEndian(
        drawable: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        planeMask: Int = 0xffff_ffff.toInt(),
        format: Int = 2,
    ): ByteArray {
        val body = ByteArray(16)
        put32be(body, 0, drawable)
        put16be(body, 4, x)
        put16be(body, 6, y)
        put16be(body, 8, width)
        put16be(body, 10, height)
        put32be(body, 12, planeMask)
        return requestBigEndian(73, format, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun requestBigEndian(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16be(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun renderRequest(minorOpcode: Int, body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, minorOpcode, body)

    private fun renderQueryVersionRequest(bodySize: Int = 8): ByteArray {
        val body = ByteArray(bodySize)
        if (bodySize >= 8) {
            put32le(body, 0, XRender.MajorVersion)
            put32le(body, 4, XRender.MinorVersion)
        }
        return renderRequest(0, body)
    }

    private fun glxQueryVersionRequest(): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, XGlx.MajorVersion)
        put32le(body, 4, XGlx.MinorVersion)
        return request(XGlx.MajorOpcode, XGlx.QueryVersion, body)
    }

    private fun renderQueryPictIndexValuesRequest(format: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, format)
        return renderRequest(2, body)
    }

    private fun renderQueryDithersRequest(drawable: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, drawable)
        return renderRequest(3, body)
    }

    private fun renderCreatePictureRequest(
        picture: Int,
        drawable: Int = WindowId,
        format: Int = XRender.Rgb24Format,
        valueMask: Int = 0,
        vararg values: Int,
    ): ByteArray {
        val body = ByteArray(16 + values.size * 4)
        put32le(body, 0, picture)
        put32le(body, 4, drawable)
        put32le(body, 8, format)
        put32le(body, 12, valueMask)
        values.forEachIndexed { index, value ->
            put32le(body, 16 + index * 4, value)
        }
        return renderRequest(4, body)
    }

    private fun renderChangePictureRequest(picture: Int, valueMask: Int, vararg values: Int): ByteArray {
        val body = ByteArray(8 + values.size * 4)
        put32le(body, 0, picture)
        put32le(body, 4, valueMask)
        values.forEachIndexed { index, value ->
            put32le(body, 8 + index * 4, value)
        }
        return renderRequest(5, body)
    }

    private fun renderSetPictureClipRectanglesRequest(
        picture: Int,
        originX: Int,
        originY: Int,
        rectangles: List<XRectangleCommand>,
    ): ByteArray {
        val body = ByteArray(8 + rectangles.size * 8)
        put32le(body, 0, picture)
        put16le(body, 4, originX)
        put16le(body, 6, originY)
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 8 + index * 8
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
        }
        return renderSetPictureClipRectanglesRaw(body)
    }

    private fun renderSetPictureClipRectanglesRaw(body: ByteArray): ByteArray =
        renderRequest(6, body)

    private fun renderFreePictureRequest(picture: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, picture)
        return renderRequest(7, body)
    }

    private fun renderSetPictureTransformRequest(picture: Int, transform: List<Int>): ByteArray {
        require(transform.size == 9)
        val body = ByteArray(40)
        put32le(body, 0, picture)
        transform.forEachIndexed { index, value ->
            put32le(body, 4 + index * 4, value)
        }
        return renderSetPictureTransformRaw(body)
    }

    private fun renderSetPictureTransformRaw(body: ByteArray): ByteArray =
        renderRequest(28, body)

    private fun renderSetPictureFilterRequest(picture: Int, name: String, vararg values: Int): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val valuesOffset = (8 + nameBytes.size + 3) and -4
        val body = ByteArray(valuesOffset + values.size * 4)
        put32le(body, 0, picture)
        put16le(body, 4, nameBytes.size)
        nameBytes.copyInto(body, 8)
        values.forEachIndexed { index, value ->
            put32le(body, valuesOffset + index * 4, value)
        }
        return renderSetPictureFilterRaw(body)
    }

    private fun renderSetPictureFilterRaw(body: ByteArray): ByteArray =
        renderRequest(30, body)

    private fun renderCompositeRequest(
        source: Int,
        mask: Int = 0,
        destination: Int,
        operation: Int = XRender.OpSrc,
    ): ByteArray {
        val body = ByteArray(32)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, mask)
        put32le(body, 12, destination)
        put16le(body, 28, 1)
        put16le(body, 30, 1)
        return renderCompositeRaw(body)
    }

    private fun renderCompositeRaw(body: ByteArray): ByteArray =
        renderRequest(8, body)

    private fun renderFillRectanglesRequest(
        picture: Int,
        operation: Int = XRender.OpSrc,
        red: Int = 0xffff,
        green: Int = 0x0000,
        blue: Int = 0x0000,
        alpha: Int = 0xffff,
        x: Int = 0,
        y: Int = 0,
        width: Int = 1,
        height: Int = 1,
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
        return renderFillRectanglesRaw(body)
    }

    private fun renderFillRectanglesRaw(body: ByteArray): ByteArray =
        renderRequest(26, body)

    private fun renderCreateCursorRequest(cursor: Int, source: Int, x: Int = 0, y: Int = 0): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, cursor)
        put32le(body, 4, source)
        put16le(body, 8, x)
        put16le(body, 10, y)
        return renderCreateCursorRaw(body)
    }

    private fun renderCreateCursorRaw(body: ByteArray): ByteArray =
        renderRequest(27, body)

    private fun renderCreateAnimCursorRequest(cursor: Int, vararg elements: Pair<Int, Int>): ByteArray {
        val body = ByteArray(4 + elements.size * 8)
        put32le(body, 0, cursor)
        elements.forEachIndexed { index, (sourceCursor, delay) ->
            val offset = 4 + index * 8
            put32le(body, offset, sourceCursor)
            put32le(body, offset + 4, delay)
        }
        return renderRequest(31, body)
    }

    private fun readReply(input: InputStream, byteOrderByte: Int = 0x6c): ByteArray {
        val header = input.readExactly(32)
        val payloadUnits = if (byteOrderByte == 0x42) u32be(header, 4) else u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
    }

    private fun assertMapAndExpose(input: InputStream, windowId: Int) {
        val first = input.readExactly(32)
        if ((first[0].toInt() and 0xff) == 19) {
            assertMapNotify(first, sequence = u16le(first, 2), eventWindow = windowId, window = windowId)
            assertExpose(input.readExactly(32), windowId)
        } else {
            assertExpose(first, windowId)
        }
    }

    private fun assertSelectedMapAndExpose(input: InputStream, windowId: Int, eventWindow: Int = windowId) {
        val map = input.readExactly(32)
        assertMapNotify(map, sequence = u16le(map, 2), eventWindow = eventWindow, window = windowId)
        assertExpose(input.readExactly(32), windowId)
    }

    private fun assertMapNotify(event: ByteArray, sequence: Int, eventWindow: Int, window: Int) {
        assertEquals(19, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(eventWindow, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertEquals(0, event[12].toInt() and 0xff)
        assertZeroBytes(event, 13, 32)
    }

    private fun assertMapRequest(
        event: ByteArray,
        sequence: Int,
        parent: Int,
        window: Int,
    ) {
        assertEquals(20, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(parent, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertZeroBytes(event, 12, 32)
    }

    private fun assertCreateNotify(
        event: ByteArray,
        sequence: Int,
        parent: Int,
        window: Int,
        x: Int = 0,
        y: Int = 0,
        width: Int = 40,
        height: Int = 30,
        borderWidth: Int = 0,
        overrideRedirect: Boolean = false,
    ) {
        assertEquals(16, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(parent, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertEquals(x, u16le(event, 12))
        assertEquals(y, u16le(event, 14))
        assertEquals(width, u16le(event, 16))
        assertEquals(height, u16le(event, 18))
        assertEquals(borderWidth, u16le(event, 20))
        assertEquals(if (overrideRedirect) 1 else 0, event[22].toInt() and 0xff)
        assertZeroBytes(event, 23, 32)
    }

    private fun assertDestroyNotify(
        event: ByteArray,
        sequence: Int,
        eventWindow: Int,
        window: Int,
    ) {
        assertEquals(17, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(eventWindow, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertZeroBytes(event, 12, 32)
    }

    private fun assertReparentNotify(
        event: ByteArray,
        sequence: Int,
        eventWindow: Int,
        window: Int,
        parent: Int,
        x: Int,
        y: Int,
        overrideRedirect: Boolean = false,
    ) {
        assertEquals(21, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(eventWindow, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertEquals(parent, u32le(event, 12))
        assertEquals(x, u16le(event, 16))
        assertEquals(y, u16le(event, 18))
        assertEquals(if (overrideRedirect) 1 else 0, event[20].toInt() and 0xff)
        assertZeroBytes(event, 21, 32)
    }

    private fun assertExpose(
        expose: ByteArray,
        windowId: Int,
        sequence: Int? = null,
        x: Int? = null,
        y: Int? = null,
        width: Int? = null,
        height: Int? = null,
        count: Int? = null,
    ) {
        assertEquals(12, expose[0].toInt() and 0xff)
        sequence?.let { assertEquals(it, u16le(expose, 2)) }
        assertEquals(windowId, u32le(expose, 4))
        x?.let { assertEquals(it, u16le(expose, 8)) }
        y?.let { assertEquals(it, u16le(expose, 10)) }
        width?.let { assertEquals(it, u16le(expose, 12)) }
        height?.let { assertEquals(it, u16le(expose, 14)) }
        count?.let { assertEquals(it, u16le(expose, 16)) }
    }

    private fun assertNoExposure(event: ByteArray, sequence: Int, drawable: Int, majorOpcode: Int, minorOpcode: Int = 0) {
        assertEquals(14, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(drawable, u32le(event, 4))
        assertEquals(minorOpcode, u16le(event, 8))
        assertEquals(majorOpcode, event[10].toInt() and 0xff)
        assertZeroBytes(event, 11, 32)
    }

    private fun assertGraphicsExposure(
        event: ByteArray,
        sequence: Int,
        drawable: Int,
        rectangle: XRectangleCommand,
        majorOpcode: Int,
        count: Int,
        minorOpcode: Int = 0,
    ) {
        assertEquals(13, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(drawable, u32le(event, 4))
        assertEquals(rectangle.x, u16le(event, 8))
        assertEquals(rectangle.y, u16le(event, 10))
        assertEquals(rectangle.width, u16le(event, 12))
        assertEquals(rectangle.height, u16le(event, 14))
        assertEquals(minorOpcode, u16le(event, 16))
        assertEquals(count, u16le(event, 18))
        assertEquals(majorOpcode, event[20].toInt() and 0xff)
        assertZeroBytes(event, 21, 32)
    }

    private fun assertUnmapNotify(event: ByteArray, sequence: Int, eventWindow: Int, window: Int) {
        assertEquals(18, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(eventWindow, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertEquals(0, event[12].toInt() and 0xff)
        assertZeroBytes(event, 13, 32)
    }

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun waitForStateContains(port: Int, expected: String) {
        repeat(20) {
            if (httpGet(port, "/state.json").contains(expected)) return
            Thread.sleep(50)
        }
        assertContains(httpGet(port, "/state.json"), expected)
    }

    private fun windowJsonId(id: Int): String =
        """"id":"0x${id.toString(16)}""""

    private fun Int.toJsonHex(): String =
        "0x${toUInt().toString(16)}"

    private fun pixelAt(reply: ByteArray, imageWidth: Int, x: Int, y: Int): Int =
        u32le(reply, 32 + (y * imageWidth + x) * 4)

    private fun installedColormaps(reply: ByteArray): List<Int> {
        val count = u16le(reply, 8)
        return (0 until count).map { index -> u32le(reply, 32 + index * 4) }
    }

    private fun treeChildren(reply: ByteArray): List<Int> {
        assertEquals(1, reply[0].toInt())
        val count = u16le(reply, 16)
        return (0 until count).map { index -> u32le(reply, 32 + index * 4) }
    }

    private fun waitForRootChildren(port: Int, predicate: (List<Int>) -> Boolean): List<Int> {
        var latest = emptyList<Int>()
        repeat(40) {
            latest = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                socket.getOutputStream().write(queryTreeRequest(X11Ids.RootWindow))
                socket.getOutputStream().flush()
                treeChildren(readReply(socket.getInputStream()))
            }
            if (predicate(latest)) return latest
            Thread.sleep(25)
        }
        return latest
    }

    private fun closeClientAndWait(socket: Socket) {
        socket.shutdownOutput()
        while (socket.getInputStream().read() != -1) {
            // Drain until the server closes the socket after running connection cleanup.
        }
    }

    private fun assertColorTriples(
        reply: ByteArray,
        offset: Int,
        red: Int,
        green: Int,
        blue: Int,
        visualRed: Int = red,
        visualGreen: Int = green,
        visualBlue: Int = blue,
    ) {
        assertEquals(red, u16le(reply, offset))
        assertEquals(green, u16le(reply, offset + 2))
        assertEquals(blue, u16le(reply, offset + 4))
        assertEquals(visualRed, u16le(reply, offset + 6))
        assertEquals(visualGreen, u16le(reply, offset + 8))
        assertEquals(visualBlue, u16le(reply, offset + 10))
    }

    private fun assertQueriedColor(reply: ByteArray, index: Int, red: Int, green: Int, blue: Int) {
        val offset = 32 + index * 8
        assertEquals(red, u16le(reply, offset))
        assertEquals(green, u16le(reply, offset + 2))
        assertEquals(blue, u16le(reply, offset + 4))
        assertEquals(0, u16le(reply, offset + 6))
    }

    private fun assertPointerControl(reply: ByteArray, sequence: Int, numerator: Int, denominator: Int, threshold: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(0, u32le(reply, 4))
        assertEquals(numerator, u16le(reply, 8))
        assertEquals(denominator, u16le(reply, 10))
        assertEquals(threshold, u16le(reply, 12))
    }

    private fun assertPropertyReply(reply: ByteArray, sequence: Int, type: Int, value: String, bytesAfter: Int = 0) {
        val bytes = value.encodeToByteArray()
        assertPropertyReplyBytes(reply, sequence, type, format = 8, data = bytes, bytesAfter = bytesAfter)
        assertEquals(value, reply.copyOfRange(32, 32 + bytes.size).decodeToString())
    }

    private fun assertNoPropertyReply(reply: ByteArray, sequence: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(0, u32le(reply, 4))
        assertEquals(0, u32le(reply, 8))
        assertEquals(0, u32le(reply, 12))
        assertEquals(0, u32le(reply, 16))
        assertZeroBytes(reply, 20, 32)
        assertEquals(32, reply.size)
    }

    private fun assertPropertyReplyBytes(
        reply: ByteArray,
        sequence: Int,
        type: Int,
        format: Int,
        data: ByteArray,
        bytesAfter: Int = 0,
        byteOrderByte: Int = 0x6c,
    ) {
        val u16 = if (byteOrderByte == 0x42) ::u16be else ::u16le
        val u32 = if (byteOrderByte == 0x42) ::u32be else ::u32le
        assertEquals(1, reply[0].toInt())
        assertEquals(format, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16(reply, 2))
        assertEquals(paddedLength(data.size) / 4, u32(reply, 4))
        assertEquals(type, u32(reply, 8))
        assertEquals(bytesAfter, u32(reply, 12))
        assertEquals(data.size / (format / 8), u32(reply, 16))
        assertZeroBytes(reply, 20, 32)
        assertEquals(data.toList(), reply.copyOfRange(32, 32 + data.size).toList())
        assertZeroBytes(reply, 32 + data.size, reply.size)
    }

    private fun assertPropertyNotifyEvent(event: ByteArray, sequence: Int, window: Int, atom: Int, state: Int = 0) {
        assertEquals(28, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(window, u32le(event, 4))
        assertEquals(atom, u32le(event, 8))
        assertEquals(0, u32le(event, 12))
        assertEquals(state, event[16].toInt() and 0xff)
        assertZeroBytes(event, 17, 32)
    }

    private fun assertListPropertiesReply(reply: ByteArray, sequence: Int, vararg atoms: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(atoms.size, u32le(reply, 4))
        assertEquals(atoms.size, u16le(reply, 8))
        assertZeroBytes(reply, 10, 32)
        atoms.forEachIndexed { index, atom ->
            assertEquals(atom, u32le(reply, 32 + index * 4))
        }
        assertEquals(32 + atoms.size * 4, reply.size)
    }

    private fun assertPointerMapping(reply: ByteArray, sequence: Int, vararg mapping: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(mapping.size, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        val payloadBytes = paddedLength(mapping.size)
        assertEquals(payloadBytes / 4, u32le(reply, 4))
        assertZeroBytes(reply, 8, 32)
        mapping.forEachIndexed { index, value ->
            assertEquals(value, reply[32 + index].toInt() and 0xff)
        }
        assertZeroBytes(reply, 32 + mapping.size, 32 + payloadBytes)
    }

    private fun assertModifierMapping(reply: ByteArray, sequence: Int, keycodesPerModifier: Int, vararg keycodes: Int) {
        assertEquals(8 * keycodesPerModifier, keycodes.size)
        assertEquals(1, reply[0].toInt())
        assertEquals(keycodesPerModifier, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        val payloadBytes = paddedLength(keycodes.size)
        assertEquals(payloadBytes / 4, u32le(reply, 4))
        assertZeroBytes(reply, 8, 32)
        keycodes.forEachIndexed { index, value ->
            assertEquals(value, reply[32 + index].toInt() and 0xff)
        }
        assertZeroBytes(reply, 32 + keycodes.size, 32 + payloadBytes)
    }

    private fun assertKeymap(reply: ByteArray, sequence: Int, vararg downKeycodes: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(2, u32le(reply, 4))
        assertEquals(40, reply.size)
        val expected = ByteArray(32)
        for (keycode in downKeycodes) {
            expected[keycode / 8] = (expected[keycode / 8].toInt() or (1 shl (keycode % 8))).toByte()
        }
        expected.forEachIndexed { index, value ->
            assertEquals(value.toInt() and 0xff, reply[8 + index].toInt() and 0xff, "keymap byte $index")
        }
    }

    private fun assertKeyboardMapping(reply: ByteArray, sequence: Int, keysymsPerKeycode: Int, vararg keysyms: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(keysymsPerKeycode, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(keysyms.size, u32le(reply, 4))
        assertZeroBytes(reply, 8, 32)
        keysyms.forEachIndexed { index, keysym ->
            assertEquals(keysym, u32le(reply, 32 + index * 4))
        }
        assertEquals(32 + keysyms.size * 4, reply.size)
    }

    private fun assertKeyboardControl(
        reply: ByteArray,
        sequence: Int,
        keyClickPercent: Int,
        bellPercent: Int,
        bellPitch: Int,
        bellDuration: Int,
        ledMask: Int,
        globalAutoRepeat: Boolean,
        autoRepeatDisabledKeycodes: Set<Int>,
        byteOrderByte: Int = 0x6c,
    ) {
        fun read16(offset: Int) = if (byteOrderByte == 0x42) u16be(reply, offset) else u16le(reply, offset)
        fun read32(offset: Int) = if (byteOrderByte == 0x42) u32be(reply, offset) else u32le(reply, offset)
        assertEquals(1, reply[0].toInt())
        assertEquals(if (globalAutoRepeat) 1 else 0, reply[1].toInt() and 0xff)
        assertEquals(sequence, read16(2))
        assertEquals(5, read32(4))
        assertEquals(ledMask, read32(8))
        assertEquals(keyClickPercent, reply[12].toInt() and 0xff)
        assertEquals(bellPercent, reply[13].toInt() and 0xff)
        assertEquals(bellPitch, read16(14))
        assertEquals(bellDuration, read16(16))
        assertZeroBytes(reply, 18, 20)
        assertEquals(52, reply.size)
        for (keycode in XKeyboard.MinKeycode..XKeyboard.MaxKeycode) {
            val byte = reply[20 + keycode / 8].toInt() and 0xff
            val enabled = (byte and (1 shl (keycode % 8))) != 0
            assertEquals(keycode !in autoRepeatDisabledKeycodes, enabled, "auto-repeat keycode $keycode")
        }
    }

    private fun assertListHosts(reply: ByteArray, sequence: Int, enabled: Boolean, vararg hosts: AccessHost) {
        assertEquals(1, reply[0].toInt())
        assertEquals(if (enabled) 1 else 0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        val payloadBytes = hosts.sumOf { 4 + paddedLength(it.address.size) }
        assertEquals(payloadBytes / 4, u32le(reply, 4))
        assertEquals(hosts.size, u16le(reply, 8))
        assertZeroBytes(reply, 10, 32)
        var offset = 32
        for (host in hosts) {
            assertEquals(host.family, reply[offset].toInt() and 0xff)
            assertEquals(0, reply[offset + 1].toInt() and 0xff)
            assertEquals(host.address.size, u16le(reply, offset + 2))
            host.address.forEachIndexed { index, value ->
                assertEquals(value, reply[offset + 4 + index].toInt() and 0xff)
            }
            val nextOffset = offset + 4 + paddedLength(host.address.size)
            assertZeroBytes(reply, offset + 4 + host.address.size, nextOffset)
            offset = nextOffset
        }
        assertEquals(32 + payloadBytes, reply.size)
    }

    private fun assertMappingStatus(reply: ByteArray, sequence: Int, status: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(status, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(0, u32le(reply, 4))
        assertZeroBytes(reply, 8, 32)
    }

    private fun assertMappingNotify(event: ByteArray, sequence: Int, request: Int = 2, firstKeycode: Int = 0, count: Int = 0) {
        assertEquals(34, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(request, event[4].toInt() and 0xff)
        assertEquals(firstKeycode, event[5].toInt() and 0xff)
        assertEquals(count, event[6].toInt() and 0xff)
        assertZeroBytes(event, 7, 32)
    }

    private fun assertConfigureNotify(
        event: ByteArray,
        sequence: Int,
        window: Int,
        aboveSibling: Int,
        eventWindow: Int = window,
        x: Int? = null,
        y: Int? = null,
        width: Int? = null,
        height: Int? = null,
        borderWidth: Int? = null,
        overrideRedirect: Boolean = false,
    ) {
        assertEquals(22, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(eventWindow, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertEquals(aboveSibling, u32le(event, 12))
        if (x != null) assertEquals(x, u16le(event, 16))
        if (y != null) assertEquals(y, u16le(event, 18))
        if (width != null) assertEquals(width, u16le(event, 20))
        if (height != null) assertEquals(height, u16le(event, 22))
        if (borderWidth != null) assertEquals(borderWidth, u16le(event, 24))
        assertEquals(if (overrideRedirect) 1 else 0, event[26].toInt() and 0xff)
        assertZeroBytes(event, 27, 32)
    }

    private fun assertConfigureRequest(
        event: ByteArray,
        sequence: Int,
        parent: Int,
        window: Int,
        sibling: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        borderWidth: Int,
        stackMode: Int,
        valueMask: Int,
    ) {
        assertEquals(23, event[0].toInt() and 0xff)
        assertEquals(stackMode, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(parent, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertEquals(sibling, u32le(event, 12))
        assertEquals(x, u16le(event, 16))
        assertEquals(y, u16le(event, 18))
        assertEquals(width, u16le(event, 20))
        assertEquals(height, u16le(event, 22))
        assertEquals(borderWidth, u16le(event, 24))
        assertEquals(valueMask, u16le(event, 26))
        assertZeroBytes(event, 28, 32)
    }

    private fun assertResizeRequest(event: ByteArray, sequence: Int, window: Int, width: Int, height: Int) {
        assertEquals(25, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(window, u32le(event, 4))
        assertEquals(width, u16le(event, 8))
        assertEquals(height, u16le(event, 10))
        assertZeroBytes(event, 12, 32)
    }

    private fun assertCirculateNotify(event: ByteArray, sequence: Int, eventWindow: Int, window: Int, place: Int) {
        assertEquals(26, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(eventWindow, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertZeroBytes(event, 12, 16)
        assertEquals(place, event[16].toInt() and 0xff)
        assertZeroBytes(event, 17, 32)
    }

    private fun assertCirculateRequest(event: ByteArray, sequence: Int, parent: Int, window: Int, place: Int) {
        assertEquals(27, event[0].toInt() and 0xff)
        assertEquals(0, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(parent, u32le(event, 4))
        assertEquals(window, u32le(event, 8))
        assertZeroBytes(event, 12, 16)
        assertEquals(place, event[16].toInt() and 0xff)
        assertZeroBytes(event, 17, 32)
    }

    private fun assertXFixesSelectionNotify(
        event: ByteArray,
        sequence: Int,
        subtype: Int,
        window: Int,
        owner: Int,
        selection: Int,
        timestamp: Int = 0,
        selectionTimestamp: Int = timestamp,
    ) {
        assertEquals(XFixes.FirstEvent + XFixes.SelectionNotify, event[0].toInt() and 0xff)
        assertEquals(subtype, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(window, u32le(event, 4))
        assertEquals(owner, u32le(event, 8))
        assertEquals(selection, u32le(event, 12))
        assertEquals(timestamp, u32le(event, 16))
        assertEquals(selectionTimestamp, u32le(event, 20))
        assertZeroBytes(event, 24, 32)
    }

    private fun assertXFixesCursorNotify(
        event: ByteArray,
        sequence: Int,
        subtype: Int = XFixes.DisplayCursorNotify,
        window: Int,
        cursorSerial: Int,
        timestamp: Int = 0,
        name: Int = 0,
    ) {
        assertEquals(XFixes.FirstEvent + XFixes.CursorNotify, event[0].toInt() and 0xff)
        assertEquals(subtype, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(window, u32le(event, 4))
        assertEquals(cursorSerial, u32le(event, 8))
        assertEquals(timestamp, u32le(event, 12))
        assertEquals(name, u32le(event, 16))
        assertZeroBytes(event, 20, 32)
    }

    private fun assertButtonEvent(event: ByteArray, type: Int, detail: Int) {
        assertEquals(type, event[0].toInt() and 0xff)
        assertEquals(detail, event[1].toInt() and 0xff)
    }

    private fun assertFocusEvent(event: ByteArray, type: Int, sequence: Int, window: Int, detail: Int = 3, mode: Int = 0) {
        assertEquals(type, event[0].toInt() and 0xff)
        assertEquals(detail, event[1].toInt() and 0xff)
        assertEquals(sequence, u16le(event, 2))
        assertEquals(window, u32le(event, 4))
        assertEquals(mode, event[8].toInt() and 0xff)
        assertZeroBytes(event, 9, 32)
    }

    private fun assertKeyEvent(
        event: ByteArray,
        type: Int,
        detail: Int,
        eventWindow: Int,
        state: Int = 0,
        childWindow: Int? = null,
    ) {
        assertEquals(type, event[0].toInt() and 0xff)
        assertEquals(detail, event[1].toInt() and 0xff)
        assertEquals(X11Ids.RootWindow, u32le(event, 8))
        assertEquals(eventWindow, u32le(event, 12))
        childWindow?.let { assertEquals(it, u32le(event, 16)) }
        assertEquals(state, u16le(event, 28))
        assertEquals(1, event[30].toInt() and 0xff)
    }

    private fun assertMotionEvent(reply: ByteArray, index: Int, time: Int, x: Int, y: Int) {
        val offset = 32 + index * 8
        assertEquals(time, u32le(reply, offset))
        assertEquals(x, i16le(reply, offset + 4))
        assertEquals(y, i16le(reply, offset + 6))
    }

    private fun assertError(input: InputStream, error: Int, opcode: Int, badValue: Int, sequence: Int, minorOpcode: Int = 0) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
        assertEquals(0, reply[11].toInt() and 0xff)
        assertZeroBytes(reply, 12, 32)
    }

    private fun assertListFontsWithInfoTerminal(reply: ByteArray, sequence: Int) {
        assertEquals(60, reply.size)
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(7, u32le(reply, 4))
        assertZeroBytes(reply, 8, 60)
    }

    private fun assertZeroBytes(bytes: ByteArray, from: Int, until: Int) {
        for (index in from until until) {
            assertEquals(0, bytes[index].toInt() and 0xff, "byte $index")
        }
    }

    private fun countPixels(reply: ByteArray, imageWidth: Int, imageHeight: Int, pixel: Int): Int {
        var count = 0
        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                if (pixelAt(reply, imageWidth, x, y) == pixel) count += 1
            }
        }
        return count
    }

    private fun firstPixelX(reply: ByteArray, imageWidth: Int, yRange: IntRange, pixel: Int): Int? {
        for (x in 0 until imageWidth) {
            for (y in yRange) {
                if (pixelAt(reply, imageWidth, x, y) == pixel) return x
            }
        }
        return null
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

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun i16le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset).toShort().toInt()

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun paddedLength(length: Int): Int = (length + 3) and -4

    private data class XStoreColorItem(
        val pixel: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
        val flags: Int,
    )

    private data class AccessHost(
        val family: Int,
        val address: List<Int>,
    )

    private companion object {
        const val WindowId = 0x0020_0001
        const val PixmapId = 0x0020_0100
        const val GcId = 0x0020_1001
        const val ColormapId = 0x0020_2001
        const val PrimaryAtom = 1
        const val AtomAtom = 4
        const val StringAtom = 31
        const val Red = 0x00ff_0000
        const val Green = 0x0000_ff00
        const val Blue = 0x0000_00ff
        const val GXnoop = 0x5
        const val GXxor = 0x6
        const val FillStippled = 2
        const val FillOpaqueStippled = 3
        const val FullCircleAngle = 360 * 64
        const val ArcChord = 0
        const val WindingRule = 1
    }
}
