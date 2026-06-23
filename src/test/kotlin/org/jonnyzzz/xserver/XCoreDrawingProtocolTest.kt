package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
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

    private fun createWindowRequest(
        id: Int,
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

    private fun createGlyphCursorRequest(cursor: Int, sourceFont: Int, maskFont: Int): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, cursor)
        put32le(body, 4, sourceFont)
        put32le(body, 8, maskFont)
        put16le(body, 16, 0xffff)
        put16le(body, 22, 0xffff)
        return request(94, 0, body)
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

    private fun grabPointerRequest(window: Int): ByteArray {
        val body = ByteArray(20)
        put32le(body, 0, window)
        put16le(body, 4, 0)
        body[6] = 0
        body[7] = 0
        put32le(body, 8, 0)
        put32le(body, 12, 0)
        put32le(body, 16, 0)
        return request(26, 0, body)
    }

    private fun grabKeyboardRequest(window: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, 0)
        body[8] = 0
        body[9] = 0
        return request(31, 0, body)
    }

    private fun setInputFocusRequest(window: Int, revertTo: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, 0)
        return request(42, revertTo, body)
    }

    private fun getInputFocusRequest(): ByteArray =
        request(43, 0, ByteArray(0))

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

    private fun mapWindowRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(8, 0, body)
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

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun pixelAt(reply: ByteArray, imageWidth: Int, x: Int, y: Int): Int =
        u32le(reply, 32 + (y * imageWidth + x) * 4)

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

    private companion object {
        const val WindowId = 0x0020_0001
        const val PixmapId = 0x0020_0100
        const val GcId = 0x0020_1001
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
