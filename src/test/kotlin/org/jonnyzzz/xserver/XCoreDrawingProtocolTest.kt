package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XCoreDrawingProtocolTest {
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
                out.write(copyGcRequest(GcId + 1, GcId, mask = 0x0000_0004))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0000_0004))
                out.write(createGcRequest(GcId + 1, foreground = Blue))
                out.write(copyGcRequest(GcId, GcId + 1, mask = 0x0080_0000))
                out.flush()

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
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, 1, 0, 0))
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
    fun `CreateGlyphCursor rejects duplicate resource id with glyph cursor opcode`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createGcRequest(GcId, foreground = Blue))
                out.write(createGlyphCursorRequest(WindowId, sourceFont = GcId, maskFont = GcId))
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
    fun `RecolorCursor validates cursor id and length without replacing cursor resource`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val cursor = PixmapId + 80
                val missingCursor = cursor + 1
                val out = socket.getOutputStream()
                out.write(createCursorRequest(cursor, source = PixmapId, mask = PixmapId + 1))
                out.write(recolorCursorRequest(cursor))
                out.write(recolorCursorRequest(missingCursor))
                out.write(request(96, 0, ByteArray(12)))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0xffff, green = 0, blue = 0))
                out.flush()

                assertError(socket.getInputStream(), error = 6, opcode = 96, badValue = missingCursor, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 96, badValue = 0, sequence = 4)

                val red = readReply(socket.getInputStream())
                assertEquals(5, u16le(red, 2))
                assertEquals(Red, u32le(red, 16))
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
                out.write(allocColorPlanesRequest(X11Ids.DefaultColormap, colors = 1, reds = 1, greens = 1, blues = 1))
                out.write(allocColorPlanesRequest(missingColormap, colors = 1, reds = 1, greens = 0, blues = 0))
                out.write(request(87, 3, ByteArray(12)))
                out.write(request(87, 0, ByteArray(8)))
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0, green = 0xffff, blue = 0))
                out.flush()

                assertError(socket.getInputStream(), error = 11, opcode = 86, badValue = 0, sequence = 1)
                assertError(socket.getInputStream(), error = 12, opcode = 86, badValue = missingColormap, sequence = 2)
                assertError(socket.getInputStream(), error = 2, opcode = 86, badValue = 2, sequence = 3)
                assertError(socket.getInputStream(), error = 16, opcode = 86, badValue = 0, sequence = 4)
                assertError(socket.getInputStream(), error = 11, opcode = 87, badValue = 0, sequence = 5)
                assertError(socket.getInputStream(), error = 12, opcode = 87, badValue = missingColormap, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 87, badValue = 3, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 87, badValue = 0, sequence = 8)

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
                out.write(allocColorRequest(X11Ids.DefaultColormap, red = 0, green = 0, blue = 0xffff))
                out.flush()

                assertError(socket.getInputStream(), error = 12, opcode = 88, badValue = missingColormap, sequence = 2)
                assertError(socket.getInputStream(), error = 16, opcode = 88, badValue = 0, sequence = 3)

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
                out.write(createPixmapRequest(PixmapId, width = 2, height = 2, depth = 8))
                out.write(createGcRequest(GcId, foreground = Red, background = Blue))
                out.write(
                    putImage8PixelsRequest(
                        PixmapId,
                        width = 2,
                        height = 2,
                        alphas = byteArrayOf(0xff.toByte(), 0x00, 0x00, 0xff.toByte()),
                    ),
                )
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
    fun `SetDashes reports errors for unknown GC and zero dash length`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(2)))
                out.write(createGcRequest(GcId, foreground = Red))
                out.write(setDashesRequest(GcId, dashOffset = 0, dashes = listOf(0)))
                out.flush()

                val missingGc = socket.getInputStream().readExactly(32)
                assertEquals(0, missingGc[0].toInt())
                assertEquals(13, missingGc[1].toInt() and 0xff)
                assertEquals(GcId, u32le(missingGc, 4))
                assertEquals(58, missingGc[10].toInt() and 0xff)

                val zeroDash = socket.getInputStream().readExactly(32)
                assertEquals(0, zeroDash[0].toInt())
                assertEquals(2, zeroDash[1].toInt() and 0xff)
                assertEquals(0, u32le(zeroDash, 4))
                assertEquals(58, zeroDash[10].toInt() and 0xff)
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

                val mapNotify = socket.getInputStream().readExactly(32)
                assertEquals(19, mapNotify[0].toInt())
                val expose = socket.getInputStream().readExactly(32)
                assertEquals(12, expose[0].toInt())
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

                val mapNotify = socket.getInputStream().readExactly(32)
                assertEquals(19, mapNotify[0].toInt())
                val expose = socket.getInputStream().readExactly(32)
                assertEquals(12, expose[0].toInt())
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
    fun `ListFontsWithInfo returns terminal reply for empty synthetic font catalog`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(listFontsWithInfoRequest("*", maxNames = 100))
                out.write(listFontsWithInfoRequest("fixed", maxNames = 1))
                out.flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(60, reply.size)
                assertEquals(1, reply[0].toInt())
                assertEquals(0, reply[1].toInt() and 0xff)
                assertEquals(1, u16le(reply, 2))
                assertEquals(7, u32le(reply, 4))
                assertZeroBytes(reply, 8, 60)

                val paddedPatternReply = readReply(socket.getInputStream())
                assertEquals(60, paddedPatternReply.size)
                assertEquals(1, paddedPatternReply[0].toInt())
                assertEquals(0, paddedPatternReply[1].toInt() and 0xff)
                assertEquals(2, u16le(paddedPatternReply, 2))
                assertEquals(7, u32le(paddedPatternReply, 4))
                assertZeroBytes(paddedPatternReply, 8, 60)
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
    fun `PolyText8 paints pixmap framebuffer content and honors item delta`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId, width = 80, height = 40))
                out.write(createPixmapRequest(PixmapId, width = 40, height = 36))
                out.write(createGcRequest(GcId, foreground = Green))
                out.write(polyText8Request(PixmapId, GcId, x = 2, y = 14, delta = 0, text = "I"))
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
    fun `GrabPointer replies success status for valid window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId))
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
    fun `UngrabPointer clears active grab and is replyless`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId))
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
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabPointerRequest(WindowId))
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
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createCursorRequest(cursor, source = PixmapId, mask = PixmapId + 1))
                out.write(grabPointerRequest(WindowId, time = 5, eventMask = 0x0004))
                out.flush()

                val grab = readReply(socket.getInputStream())
                assertEquals(1, grab[0].toInt())
                assertEquals(3, u16le(grab, 2))

                out.write(changeActivePointerGrabRequest(cursor = cursor, time = 4, eventMask = 0x0040))
                out.write(queryPointerRequest())
                out.flush()

                val oldTimePointer = readReply(socket.getInputStream())
                assertEquals(1, oldTimePointer[0].toInt())
                assertEquals(5, u16le(oldTimePointer, 2))
                val unchangedJson = httpGet(server.localPort, "/state.json")
                assertContains(unchangedJson, """"inputGrabs":[{"kind":"pointer","window":"0x${WindowId.toString(16)}","ownerEvents":false,"eventMask":"0x4"""")
                assertContains(unchangedJson, """"cursor":null,"time":5""")

                out.write(changeActivePointerGrabRequest(cursor = cursor, time = 5, eventMask = 0x0040))
                out.write(queryPointerRequest())
                out.flush()

                val changedPointer = readReply(socket.getInputStream())
                assertEquals(1, changedPointer[0].toInt())
                assertEquals(7, u16le(changedPointer, 2))
                val changedJson = httpGet(server.localPort, "/state.json")
                assertContains(changedJson, """"inputGrabs":[{"kind":"pointer","window":"0x${WindowId.toString(16)}","ownerEvents":false,"eventMask":"0x40"""")
                assertContains(changedJson, """"cursor":"0x${cursor.toString(16)}","time":5""")

                out.write(changeActivePointerGrabRequest(cursor = 0, time = 6, eventMask = 0x0004))
                out.write(queryPointerRequest())
                out.flush()

                val futureTimePointer = readReply(socket.getInputStream())
                assertEquals(1, futureTimePointer[0].toInt())
                assertEquals(9, u16le(futureTimePointer, 2))
                val futureIgnoredJson = httpGet(server.localPort, "/state.json")
                assertContains(futureIgnoredJson, """"eventMask":"0x40"""")
                assertContains(futureIgnoredJson, """"cursor":"0x${cursor.toString(16)}","time":5""")
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
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(createCursorRequest(cursor, source = PixmapId, mask = PixmapId + 1))
                    ownerOut.write(grabPointerRequest(WindowId, time = 1, eventMask = 0x0004))
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
                assertContains(stateJson, """"passiveButtonGrabs":[{"window":"0x${WindowId.toString(16)}","ownerEvents":true,"eventMask":"0xc","pointerMode":0,"keyboardMode":0,"confineTo":null,"cursor":null,"button":0,"buttonName":"AnyButton","modifiers":32768,"modifiersName":"AnyModifier"}]""")
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
    fun `UngrabButton specific combination does not clear wildcard passive grab`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabButtonRequest(WindowId, button = 0, modifiers = 0x8000))
                out.write(ungrabButtonRequest(WindowId, button = 1, modifiers = 0))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(4, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"button":0,"buttonName":"AnyButton","modifiers":32768""")

                out.write(ungrabButtonRequest(WindowId, button = 0, modifiers = 0x8000))
                out.write(queryPointerRequest())
                out.flush()

                val clearedPointer = readReply(socket.getInputStream())
                assertEquals(1, clearedPointer[0].toInt())
                assertEquals(6, u16le(clearedPointer, 2))
                assertContains(httpGet(server.localPort, "/state.json"), """"passiveButtonGrabs":[]""")
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
                    ownerOut.write(createWindowRequest(WindowId))
                    ownerOut.write(grabPointerRequest(WindowId))
                    ownerOut.flush()
                    assertEquals(0, readReply(owner.getInputStream())[1].toInt() and 0xff)

                    val otherOut = other.getOutputStream()
                    otherOut.write(grabPointerRequest(WindowId))
                    otherOut.write(ungrabPointerRequest())
                    otherOut.write(queryPointerRequest())
                    otherOut.flush()
                    assertEquals(1, readReply(other.getInputStream())[1].toInt() and 0xff)
                    assertEquals(1, readReply(other.getInputStream())[0].toInt())
                    assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                    ownerOut.write(ungrabPointerRequest())
                    ownerOut.write(grabKeyboardRequest(WindowId))
                    ownerOut.flush()
                    assertEquals(1, readReply(owner.getInputStream())[0].toInt())

                    otherOut.write(grabKeyboardRequest(WindowId))
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
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabPointerRequest(WindowId, time = 5))
                out.flush()
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)

                out.write(ungrabPointerRequest(time = 4))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(1, readReply(socket.getInputStream())[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"pointer"""")

                out.write(ungrabPointerRequest(time = 5))
                out.write(grabKeyboardRequest(WindowId, time = 7))
                out.flush()
                assertEquals(0, readReply(socket.getInputStream())[1].toInt() and 0xff)

                out.write(ungrabKeyboardRequest(time = 6))
                out.write(queryPointerRequest())
                out.flush()
                assertEquals(1, readReply(socket.getInputStream())[0].toInt())
                assertContains(httpGet(server.localPort, "/state.json"), """"inputGrabs":[{"kind":"keyboard"""")

                out.write(ungrabKeyboardRequest(time = 7))
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
                out.write(grabPointerRequest(WindowId))
                out.flush()
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

                repeat(6) { index ->
                    val event = socket.getInputStream().readExactly(32)
                    assertEquals(if (index % 2 == 0) 19 else 12, event[0].toInt() and 0xff)
                }
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
                val pointer = readReply(socket.getInputStream())
                assertEquals(8, u16le(pointer, 2))
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
                out.write(createWindowRequest(WindowId))
                out.write(createCursorRequest(cursor, source = PixmapId, mask = PixmapId + 1))
                out.write(grabPointerRequest(WindowId, cursor = cursor))
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
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyboardRequest(WindowId))
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
    fun `UngrabKeyboard clears active grab and is replyless`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(grabKeyboardRequest(WindowId))
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
                out.write(allowEventsRequest(mode = 6, time = 0x8000_0000.toInt()))
                out.write(queryPointerRequest())
                out.flush()

                val pointer = readReply(socket.getInputStream())
                assertEquals(1, pointer[0].toInt())
                assertEquals(2, u16le(pointer, 2))
                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"inputControlOperations":[{"id":1,"operation":"AllowEvents","mode":6,"modeName":"AsyncBoth","time":2147483648}]""")
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

                val mapNotify = socket.getInputStream().readExactly(32)
                assertEquals(19, mapNotify[0].toInt())
                val expose = socket.getInputStream().readExactly(32)
                assertEquals(12, expose[0].toInt())
                val secondMapNotify = socket.getInputStream().readExactly(32)
                assertEquals(19, secondMapNotify[0].toInt())
                val secondExpose = socket.getInputStream().readExactly(32)
                assertEquals(12, secondExpose[0].toInt())
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

                val mapNotify = socket.getInputStream().readExactly(32)
                assertEquals(19, mapNotify[0].toInt())
                val expose = socket.getInputStream().readExactly(32)
                assertEquals(12, expose[0].toInt())
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
                out.write(setPointerMappingRequest(3, 2, 1))
                out.write(getPointerMappingRequest())
                out.write(setPointerMappingRequest(0, 2, 4))
                out.write(getPointerMappingRequest())
                out.write(setPointerMappingRequest(2, 2, 0))
                out.write(setPointerMappingRequest(1, 2))
                out.write(request(116, 3, ByteArray(8)))
                out.write(request(117, 0, ByteArray(4)))
                out.write(getPointerMappingRequest())
                out.flush()

                assertPointerMapping(readReply(socket.getInputStream()), 1, 1, 2, 3)
                assertMappingStatus(readReply(socket.getInputStream()), sequence = 2, status = 0)
                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 2)
                assertPointerMapping(readReply(socket.getInputStream()), 3, 3, 2, 1)
                assertMappingStatus(readReply(socket.getInputStream()), sequence = 4, status = 0)
                assertMappingNotify(socket.getInputStream().readExactly(32), sequence = 4)
                assertPointerMapping(readReply(socket.getInputStream()), 5, 0, 2, 4)

                assertError(socket.getInputStream(), error = 2, opcode = 116, badValue = 2, sequence = 6)
                assertError(socket.getInputStream(), error = 2, opcode = 116, badValue = 2, sequence = 7)
                assertError(socket.getInputStream(), error = 16, opcode = 116, badValue = 0, sequence = 8)
                assertError(socket.getInputStream(), error = 16, opcode = 117, badValue = 0, sequence = 9)

                assertPointerMapping(readReply(socket.getInputStream()), 10, 0, 2, 4)
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
                assertKeyboardMapping(readReply(socket.getInputStream()), sequence = 2, keysymsPerKeycode = 2, 0, 0, 0x0061, 0x0041, 0x0062, 0x0042)
                assertContains(
                    httpGet(server.localPort, "/state.json"),
                    """"keyboardMapping":{"keysymsPerKeycode":2,"keycodes":[{"keycode":38,"keysyms":["0x61","0x41"]},{"keycode":39,"keysyms":["0x62","0x42"]}]}""",
                )

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
                assertKeyboardMapping(readReply(socket.getInputStream()), sequence = 10, keysymsPerKeycode = 1, 0x0063)
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
                out.write(setPointerMappingRequest(3, 2, 1))
                out.flush()

                assertEquals(19, input.readExactly(32)[0].toInt() and 0xff)
                assertEquals(12, input.readExactly(32)[0].toInt() and 0xff)
                assertMappingStatus(readReply(input), sequence = 3, status = 0)
                assertMappingNotify(input.readExactly(32), sequence = 3)

                val down = server.input.pointerDown(10, 10, button = 1)
                assertEquals(1, down.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 4, detail = 3)

                out.write(setPointerMappingRequest(1, 2, 3))
                out.write(getPointerMappingRequest())
                out.flush()
                assertMappingStatus(readReply(input), sequence = 4, status = 1)
                assertPointerMapping(readReply(input), 5, 3, 2, 1)

                val up = server.input.pointerUp(10, 10, button = 1)
                assertEquals(1, up.deliveredEvents)
                assertButtonEvent(input.readExactly(32), type = 5, detail = 3)

                out.write(setPointerMappingRequest(0, 2, 1))
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

                assertModifierMapping(readReply(socket.getInputStream()), sequence = 1, keycodesPerModifier = 0)
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

                repeat(4) {
                    assertEquals(19, input.readExactly(32)[0].toInt() and 0xff)
                    assertEquals(12, input.readExactly(32)[0].toInt() and 0xff)
                }
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                assertCirculateNotify(input.readExactly(32), sequence = 10, eventWindow = X11Ids.RootWindow, window = first, place = 0)
                assertEquals(listOf(second, third, first), treeChildren(readReply(input)))
                assertCirculateNotify(input.readExactly(32), sequence = 12, eventWindow = X11Ids.RootWindow, window = first, place = 1)
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
                val json = httpGet(server.localPort, "/state.json")
                assertEquals(
                    true,
                    json.indexOf(windowJsonId(first)) < json.indexOf(windowJsonId(nested)),
                    "CirculateWindow should keep a restacked window's descendants after the parent in snapshot/render order",
                )
                assertError(input, error = 2, opcode = 13, badValue = 2, sequence = 16)
                assertError(input, error = 16, opcode = 13, badValue = 0, sequence = 17)
                assertError(input, error = 3, opcode = 13, badValue = missing, sequence = 18)
                assertEquals(listOf(first, second, third), treeChildren(readReply(input)))
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

                assertEquals(19, socket.getInputStream().readExactly(32)[0].toInt() and 0xff)
                assertEquals(12, socket.getInputStream().readExactly(32)[0].toInt() and 0xff)
                assertEquals(19, socket.getInputStream().readExactly(32)[0].toInt() and 0xff)
                assertEquals(12, socket.getInputStream().readExactly(32)[0].toInt() and 0xff)
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

                val mapNotify = socket.getInputStream().readExactly(32)
                assertEquals(19, mapNotify[0].toInt() and 0xff)
                val expose = socket.getInputStream().readExactly(32)
                assertEquals(12, expose[0].toInt() and 0xff)
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
        eventMask: Int? = null,
        doNotPropagateMask: Int? = null,
    ): ByteArray {
        val extraValues = listOfNotNull(eventMask, doNotPropagateMask)
        val body = ByteArray(28 + extraValues.size * 4)
        put32le(body, 0, id)
        put32le(body, 4, parent)
        put16le(body, 8, x)
        put16le(body, 10, y)
        put16le(body, 12, width)
        put16le(body, 14, height)
        put16le(body, 18, 1)
        put32le(body, 20, X11Ids.RootVisual)
        var valueMask = 0
        var offset = 28
        if (eventMask != null) {
            valueMask = valueMask or (1 shl 11)
            put32le(body, offset, eventMask)
            offset += 4
        }
        if (doNotPropagateMask != null) {
            valueMask = valueMask or (1 shl 12)
            put32le(body, offset, doNotPropagateMask)
        }
        put32le(body, 24, valueMask)
        return request(1, 24, body)
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

    private fun createCursorRequest(cursor: Int, source: Int, mask: Int): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, cursor)
        put32le(body, 4, source)
        put32le(body, 8, mask)
        put16le(body, 12, 0xffff)
        put16le(body, 18, 0xffff)
        return request(93, 0, body)
    }

    private fun freeCursorRequest(cursor: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, cursor)
        return request(95, 0, body)
    }

    private fun createGlyphCursorRequest(cursor: Int, sourceFont: Int, maskFont: Int): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, cursor)
        put32le(body, 4, sourceFont)
        put32le(body, 8, maskFont)
        put16le(body, 16, 0xffff)
        put16le(body, 22, 0xffff)
        return request(94, 0, body)
    }

    private fun recolorCursorRequest(cursor: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, cursor)
        put16le(body, 4, 0xffff)
        put16le(body, 10, 0xffff)
        return request(96, 0, body)
    }

    private fun openFontRequest(font: Int, name: String = "fixed"): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(8 + paddedLength(nameBytes.size))
        put32le(body, 0, font)
        put16le(body, 4, nameBytes.size)
        nameBytes.copyInto(body, 8)
        return request(45, 0, body)
    }

    private fun createColormapRequest(colormap: Int, window: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, colormap)
        put32le(body, 4, window)
        put32le(body, 8, X11Ids.RootVisual)
        return request(78, 0, body)
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

    private fun setInputFocusRequest(window: Int, revertTo: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, 0)
        return request(42, revertTo, body)
    }

    private fun getInputFocusRequest(): ByteArray =
        request(43, 0, ByteArray(0))

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

    private fun setSelectionOwnerRequest(owner: Int, selection: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, owner)
        put32le(body, 4, selection)
        put32le(body, 8, 0)
        return request(22, 0, body)
    }

    private fun getSelectionOwnerRequest(selection: Int): ByteArray =
        request(23, 0, ByteArray(4).also { put32le(it, 0, selection) })

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

    private fun sendEventRequest(destination: Int, event: ByteArray, eventMask: Int = 0, propagate: Boolean = false): ByteArray {
        val body = ByteArray(40)
        put32le(body, 0, destination)
        put32le(body, 4, eventMask)
        event.copyInto(body, 8, endIndex = 32)
        return request(25, if (propagate) 1 else 0, body)
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

    private fun clearAreaRequest(id: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, id)
        put16le(body, 4, x)
        put16le(body, 6, y)
        put16le(body, 8, width)
        put16le(body, 10, height)
        return request(61, 0, body)
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

    private fun setClipRectanglesRequest(
        gc: Int,
        clipXOrigin: Int,
        clipYOrigin: Int,
        rectangles: List<XRectangleCommand>,
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

    private fun putImage24Request(drawable: Int, width: Int, height: Int, pixel: Int): ByteArray {
        return putImage24PixelsRequest(drawable, width, height, List(width * height) { pixel })
    }

    private fun putImage24PixelsRequest(drawable: Int, width: Int, height: Int, pixels: List<Int>): ByteArray {
        require(pixels.size == width * height)
        val data = ByteArray(width * height * 4)
        for ((index, pixel) in pixels.withIndex()) {
            put32le(data, index * 4, pixel)
        }
        val body = ByteArray(20 + data.size)
        put32le(body, 0, drawable)
        put32le(body, 4, GcId)
        put16le(body, 8, width)
        put16le(body, 10, height)
        body[17] = 24
        data.copyInto(body, 20)
        return request(72, 2, body)
    }

    private fun putImage8PixelsRequest(drawable: Int, width: Int, height: Int, alphas: ByteArray): ByteArray {
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
        put32le(body, 4, GcId)
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

    private fun polyText8Request(drawable: Int, gc: Int, x: Int, y: Int, delta: Int, text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        require(bytes.size in 0..254)
        val body = ByteArray(paddedLength(14 + bytes.size))
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        put16le(body, 8, x)
        put16le(body, 10, y)
        body[12] = bytes.size.toByte()
        body[13] = delta.toByte()
        bytes.copyInto(body, 14)
        return request(74, 0, body)
    }

    private fun queryFontRequest(fontable: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, fontable)
        return request(47, 0, body)
    }

    private fun queryTextExtentsRequest(fontable: Int, char2b: List<Pair<Int, Int>>): ByteArray {
        val oddLength = char2b.size % 2
        val body = ByteArray(4 + char2b.size * 2 + if (oddLength != 0) 2 else 0)
        put32le(body, 0, fontable)
        var offset = 4
        for ((byte1, byte2) in char2b) {
            body[offset] = byte1.toByte()
            body[offset + 1] = byte2.toByte()
            offset += 2
        }
        return request(48, oddLength, body)
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

    private fun readReply(input: InputStream, byteOrderByte: Int = 0x6c): ByteArray {
        val header = input.readExactly(32)
        val payloadUnits = if (byteOrderByte == 0x42) u32be(header, 4) else u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
    }

    private fun assertMapAndExpose(input: InputStream, windowId: Int) {
        val map = input.readExactly(32)
        assertEquals(19, map[0].toInt() and 0xff)
        assertEquals(windowId, u32le(map, 8))
        val expose = input.readExactly(32)
        assertEquals(12, expose[0].toInt() and 0xff)
        assertEquals(windowId, u32le(expose, 4))
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

    private fun assertButtonEvent(event: ByteArray, type: Int, detail: Int) {
        assertEquals(type, event[0].toInt() and 0xff)
        assertEquals(detail, event[1].toInt() and 0xff)
    }

    private fun assertError(input: InputStream, error: Int, opcode: Int, badValue: Int, sequence: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(0, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
        assertEquals(0, reply[11].toInt() and 0xff)
        assertZeroBytes(reply, 12, 32)
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
