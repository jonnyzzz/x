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
    fun `RENDER picture transform and filter are retained in semantic snapshot`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderSetPictureTransform(
                        PictureId,
                        listOf(
                            0x0001_0000,
                            0,
                            0x0002_0000,
                            0,
                            0x0001_0000,
                            0x0003_0000,
                            0,
                            0,
                            0x0001_0000,
                        ),
                    ),
                )
                out.write(renderSetPictureFilter(PictureId, "bilinear", values = listOf(0x0001_0000)))
                out.flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""renderOperations":3""")
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"renderPictures":[{""")
                assertContains(json, """"filter":"bilinear"""")
                assertContains(json, """"filterValues":["0x10000"]""")
                assertContains(json, """"transform":["0x10000","0x0","0x20000","0x0","0x10000","0x30000","0x0","0x0","0x10000"]""")

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "SetPictureTransform")
                assertContains(text, "SetPictureFilter")
                assertContains(text, "filter=bilinear values=[0x10000]")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER QueryFilters advertises required filters and aliases`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderQueryFilters(WindowId))
                out.flush()

                val reply = readReply(socket.getInputStream())
                assertEquals(11, u32le(reply, 4))
                assertEquals(76, reply.size)
                assertEquals(5, u32le(reply, 8))
                assertEquals(5, u32le(reply, 12))
                val aliases = (0 until 5).map { index -> u16le(reply, 32 + index * 2) }
                assertEquals(listOf(0xffff, 0xffff, 0, 1, 1), aliases)
                assertEquals(0, u16le(reply, 42))
                assertEquals(listOf("nearest", "bilinear", "fast", "good", "best"), filterNames(reply))

                waitUntil {
                    httpGet(server.localPort, "/text.txt").contains("QueryFilters")
                }
                assertContains(httpGet(server.localPort, "/text.txt"), "drawable=0x200001")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER QueryFilters reports Length error for malformed request size`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                socket.getOutputStream().write(request(XRender.MajorOpcode, 29, ByteArray(8)))
                socket.getOutputStream().flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(16, error[1].toInt() and 0xff)
                assertEquals(8, u32le(error, 4))
                assertEquals(29, u16le(error, 8))
                assertEquals(XRender.MajorOpcode, error[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER QueryFilters reports Drawable error for unknown drawable`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val missingDrawable = 0x0020_9999
                out.write(renderQueryFilters(missingDrawable))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(9, error[1].toInt() and 0xff)
                assertEquals(missingDrawable, u32le(error, 4))
                assertEquals(29, u16le(error, 8))
                assertEquals(XRender.MajorOpcode, error[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER SetPictureFilter rejects unsupported filter name without mutating picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderSetPictureFilter(PictureId, "bilinear", values = listOf(0x0001_0000)))
                out.write(renderSetPictureFilter(PictureId, "unsupported", values = emptyList()))
                out.flush()

                val error = socket.getInputStream().readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(8, error[1].toInt() and 0xff)
                assertEquals(0, u32le(error, 4))
                assertEquals(30, u16le(error, 8))
                assertEquals(XRender.MajorOpcode, error[10].toInt() and 0xff)

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""filter":"bilinear"""")
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"filter":"bilinear"""")
                assertContains(json, """"filterValues":["0x10000"]""")
                assertEquals(false, json.contains("unsupported"))
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
    fun `RENDER composite clear applies A8 mask pixels`() {
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
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 2, operation = XRender.OpClear))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, u32le(image, 32))
                assertEquals(0x7f00_007f, u32le(image, 36))
                assertEquals(0xff00_00ff.toInt(), u32le(image, 40))
                assertEquals(0x0000_0000, u32le(image, 44))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER transformed mask picture samples mask coordinates`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 4, height = 4, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 1, alphas = byteArrayOf(0x00, 0xff.toByte())))
                out.write(
                    renderSetPictureTransform(
                        MaskPictureId,
                        listOf(
                            0x0001_0000,
                            0,
                            0x0001_0000,
                            0,
                            0x0001_0000,
                            0,
                            0,
                            0,
                            0x0001_0000,
                        ),
                    ),
                )
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpOver, destinationX = 0, destinationY = 0))
                out.write(renderChangePicture(MaskPictureId, repeat = XRender.RepeatPad))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpOver, destinationX = 0, destinationY = 1))
                out.write(renderChangePicture(MaskPictureId, repeat = XRender.RepeatNormal))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, maskX = 1, width = 3, height = 1, operation = XRender.OpOver, destinationX = 0, destinationY = 2))
                out.write(renderChangePicture(MaskPictureId, repeat = XRender.RepeatNone))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpOver, destinationX = 1, destinationY = 3))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 3))
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
    fun `RENDER OpAdd saturates solid and drawable source compositing`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0x0000, green = 0x8000, blue = 0x8000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x9000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpAdd))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 1, height = 1, operation = XRender.OpAdd, destinationX = 1, destinationY = 0))

                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpAdd, destinationX = 2, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff80.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff80_8080.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_8080.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER transformed pixmap source samples source picture coordinates`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 4, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderSetPictureTransform(
                        PixmapPictureId,
                        listOf(
                            0x0001_0000,
                            0,
                            0x0001_0000,
                            0,
                            0x0001_0000,
                            0,
                            0,
                            0,
                            0x0001_0000,
                        ),
                    ),
                )
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(renderChangePicture(PixmapPictureId, repeat = XRender.RepeatPad))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 1, width = 2, height = 1))
                out.write(renderChangePicture(PixmapPictureId, repeat = XRender.RepeatNormal))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, sourceX = 1, destinationX = 0, destinationY = 2, width = 3, height = 1))
                out.write(renderChangePicture(PixmapPictureId, repeat = XRender.RepeatReflect))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, sourceX = 1, destinationX = 0, destinationY = 3, width = 3, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 3))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER bilinear filter interpolates transformed source and mask pictures`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 4, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderSetPictureTransform(
                        PixmapPictureId,
                        listOf(
                            0x0001_0000,
                            0,
                            0x0000_8000,
                            0,
                            0x0001_0000,
                            0,
                            0,
                            0,
                            0x0001_0000,
                        ),
                    ),
                )
                out.write(renderSetPictureFilter(PixmapPictureId, "bilinear", values = emptyList()))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(
                    renderSetPictureTransform(
                        PixmapPictureId,
                        listOf(
                            0x0001_0000,
                            0,
                            0x0001_8000,
                            0,
                            0x0001_0000,
                            0,
                            0,
                            0,
                            0x0001_0000,
                        ),
                    ),
                )
                out.write(renderChangePicture(PixmapPictureId, repeat = XRender.RepeatPad))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 2, width = 1, height = 1))
                out.write(renderChangePicture(PixmapPictureId, repeat = XRender.RepeatNormal))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 3, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 1, alphas = byteArrayOf(0x00, 0xff.toByte())))
                out.write(
                    renderSetPictureTransform(
                        MaskPictureId,
                        listOf(
                            0x0001_0000,
                            0,
                            0x0000_8000,
                            0,
                            0x0001_0000,
                            0,
                            0,
                            0,
                            0x0001_0000,
                        ),
                    ),
                )
                out.write(renderSetPictureFilter(MaskPictureId, "good", values = emptyList()))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 1, height = 1, operation = XRender.OpOver, destinationX = 0, destinationY = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff80_8000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
                assertEquals(0xff80_007f.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 1))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 2))
                assertEquals(0xff80_8000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 3))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER linear gradient composites sampled source pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(
                    renderComposite(
                        GradientPictureId,
                        PictureId,
                        operation = XRender.OpSrc,
                        destinationX = 0,
                        destinationY = 0,
                        width = 11,
                        height = 1,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 11, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xfff2_000d.toInt(), pixelAt(image, imageWidth = 11, x = 0, y = 0))
                assertEquals(0xffbf_0040.toInt(), pixelAt(image, imageWidth = 11, x = 2, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 11, x = 10, y = 0))

                out.write(
                    renderComposite(
                        GradientPictureId,
                        PictureId,
                        operation = XRender.OpSrc,
                        sourceX = 2,
                        destinationX = 0,
                        destinationY = 1,
                        width = 1,
                        height = 1,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 1, width = 1, height = 1))
                out.flush()

                val shiftedImage = readReply(socket.getInputStream())
                assertEquals(0xffbf_0040.toInt(), pixelAt(shiftedImage, imageWidth = 1, x = 0, y = 0))

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""linearGradient"""")
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"kind":"linear-gradient"""")
                assertContains(json, """"stops":["0x0","0x10000"]""")
                assertContains(json, """"colors":["0xffff0000","0xff0000ff"]""")
                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "CreateLinearGradient")
                assertContains(text, "linearGradient=0x0,0x0->0xa0000,0x0")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER linear gradient repeat modes control spread samples`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )

                out.write(renderComposite(GradientPictureId, PictureId, operation = XRender.OpSrc, sourceX = 12, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(renderComposite(GradientPictureId, PictureId, operation = XRender.OpSrc, sourceX = 12, destinationX = 1, destinationY = 0, width = 1, height = 1))
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatNormal))
                out.write(renderComposite(GradientPictureId, PictureId, operation = XRender.OpSrc, sourceX = 12, destinationX = 2, destinationY = 0, width = 1, height = 1))
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatReflect))
                out.write(renderComposite(GradientPictureId, PictureId, operation = XRender.OpSrc, sourceX = 12, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xffbf_0040.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff40_00bf.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))

                out.write(
                    renderCreateLinearGradient(
                        GradientNarrowPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 0,
                        stops = listOf(0x0000_4000, 0x0000_c000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientNarrowPictureId, repeat = XRender.RepeatNormal))
                out.write(renderComposite(GradientNarrowPictureId, PictureId, operation = XRender.OpSrc, sourceX = 11, destinationX = 4, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 4, y = 0, width = 1, height = 1))
                out.flush()

                val wrappedNarrowStops = readReply(socket.getInputStream())
                assertEquals(0xffcc_0033.toInt(), pixelAt(wrappedNarrowStops, imageWidth = 1, x = 0, y = 0))

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""repeat":"reflect"""")
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"kind":"linear-gradient"""")
                assertContains(json, """"repeat":"reflect"""")
                assertContains(httpGet(server.localPort, "/text.txt"), "repeat=reflect")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER linear gradient applies source picture transform`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(
                    renderSetPictureTransform(
                        GradientPictureId,
                        listOf(
                            0x0001_0000,
                            0,
                            0x0002_0000,
                            0,
                            0x0001_0000,
                            0,
                            0,
                            0,
                            0x0001_0000,
                        ),
                    ),
                )
                out.write(renderComposite(GradientPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffbf_0040.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))

                out.write(renderComposite(GradientPictureId, PictureId, operation = XRender.OpSrc, sourceX = 2, destinationX = 1, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 1, y = 0, width = 1, height = 1))
                out.flush()

                val offsetImage = readReply(socket.getInputStream())
                assertEquals(0xff8c_0073.toInt(), pixelAt(offsetImage, imageWidth = 1, x = 0, y = 0))

                out.write(renderSetPictureTransform(GradientPictureId, List(9) { 0 }))
                out.flush()

                val transformError = socket.getInputStream().readExactly(32)
                assertEquals(0, transformError[0].toInt())
                assertEquals(2, transformError[1].toInt() and 0xff)
                assertEquals(0, u32le(transformError, 4))
                assertEquals(28, u16le(transformError, 8))
                assertEquals(XRender.MajorOpcode, transformError[10].toInt() and 0xff)

                out.write(renderComposite(GradientPictureId, PictureId, operation = XRender.OpSrc, destinationX = 2, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 2, y = 0, width = 1, height = 1))
                out.flush()

                val afterRejectedTransformImage = readReply(socket.getInputStream())
                assertEquals(0xffbf_0040.toInt(), pixelAt(afterRejectedTransformImage, imageWidth = 1, x = 0, y = 0))

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""transform":["0x10000","0x0","0x20000"""")
                }
                assertContains(httpGet(server.localPort, "/text.txt"), "SetPictureTransform")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER radial gradient composites sampled source pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateRadialGradient(
                        GradientRadialPictureId,
                        innerCenter = 0x0000_8000 to 0x0000_8000,
                        innerRadius = 0,
                        outerCenter = 0x0000_8000 to 0x0000_8000,
                        outerRadius = 0x0008_0000,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderComposite(GradientRadialPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 11, height = 1))
                out.write(renderChangePicture(GradientRadialPictureId, repeat = XRender.RepeatPad))
                out.write(renderComposite(GradientRadialPictureId, PictureId, operation = XRender.OpSrc, sourceX = 11, destinationX = 11, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 12, x = 0, y = 0))
                assertEquals(0xffbf_0040.toInt(), pixelAt(image, imageWidth = 12, x = 2, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 8, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 12, x = 10, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 11, y = 0))

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""radialGradient"""")
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"kind":"radial-gradient"""")
                assertContains(json, """"inner":"0x8000,0x8000,r=0x0"""")
                assertContains(json, """"outer":"0x8000,0x8000,r=0x80000"""")
                assertContains(json, """"stops":["0x0","0x10000"]""")
                assertContains(json, """"colors":["0xffff0000","0xff0000ff"]""")
                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "CreateRadialGradient")
                assertContains(text, "radialGradient=0x8000,0x8000,r=0x0->0x8000,0x8000,r=0x80000")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER conical gradient composites sampled source pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateConicalGradient(
                        GradientConicalPictureId,
                        center = 0x0001_8000 to 0x0001_8000,
                        angle = 90 shl 16,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderComposite(GradientConicalPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 3, height = 3))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 3))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffbf_0040.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 1))
                assertEquals(0xff40_00bf.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 1))
                assertEquals(0xff80_0080.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 2))

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""conicalGradient"""")
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"kind":"conical-gradient"""")
                assertContains(json, """"center":"0x18000,0x18000"""")
                assertContains(json, """"angle":"0x5a0000"""")
                assertContains(json, """"stops":["0x0","0x10000"]""")
                assertContains(json, """"colors":["0xffff0000","0xff0000ff"]""")
                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "CreateConicalGradient")
                assertContains(text, "conicalGradient=0x18000,0x18000 angle=0x5a0000")
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
    fun `RENDER trapezoids sample generated source using src origin`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 18, height = 12, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 10,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(renderTrapezoids(GradientPictureId, PictureId, x = 4, y = 3, width = 10, height = 5, sourceX = 2, sourceY = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 18, height = 12))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffcc_0033.toInt(), pixelAt(image, imageWidth = 18, x = 4, y = 3))
                assertEquals(0xff73_008c.toInt(), pixelAt(image, imageWidth = 18, x = 9, y = 5))
                assertEquals(0xff26_00d9.toInt(), pixelAt(image, imageWidth = 18, x = 13, y = 7))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 18, x = 3, y = 5))
                assertContains(httpGet(server.localPort, "/text.txt"), "Trapezoids")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER trapezoids honor A1 mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 18, height = 12, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderTrapezoids(SolidPictureId, PictureId, x = 5, y = 4, width = 6, height = 4, maskFormat = XRender.A1Format))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 18, height = 12))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 18, x = 5, y = 4))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 18, x = 10, y = 7))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 18, x = 4, y = 4))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 18, x = 11, y = 7))
                assertContains(httpGet(server.localPort, "/text.txt"), "Trapezoids")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER AddTraps builds A8 mask used by composite`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 20, height = 14, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 20, height = 14))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 20, height = 14, red = 0x0000, green = 0x0000, blue = 0x0000, alpha = 0x0000))
                out.write(renderAddTraps(MaskPictureId, xOffset = 3, yOffset = 2, x = 2, y = 2, width = 7, height = 5))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 20, height = 14))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 20, height = 14))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 20, x = 6, y = 5))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 20, x = 10, y = 5))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 20, x = 6, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 20, x = 3, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 20, x = 12, y = 8))
                assertContains(httpGet(server.localPort, "/text.txt"), "AddTraps")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER AddTraps accumulates overlapping A8 coverage`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val fixedOne = 1 shl 16
                val halfPixel = 1 shl 15
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 8, height = 8, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 8, height = 8))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 8, height = 8, red = 0x0000, green = 0x0000, blue = 0x0000, alpha = 0x0000))
                repeat(2) {
                    out.write(
                        renderAddTrapsRaw(
                            MaskPictureId,
                            left = 4 * fixedOne,
                            right = 4 * fixedOne + halfPixel,
                            top = 3 * fixedOne,
                            bottom = 7 * fixedOne,
                        ),
                    )
                }
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 8, height = 8))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xfffe_0001.toInt(), pixelAt(image, imageWidth = 8, x = 4, y = 4))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 8, x = 5, y = 4))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER triangles composite solid source into destination framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 32, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(8 to 6, 24 to 6, 8 to 20)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 32, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 32, x = 10, y = 8))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 32, x = 12, y = 12))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 32, x = 7, y = 6))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 32, x = 24, y = 20))
                assertContains(httpGet(server.localPort, "/text.txt"), "Triangles")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER triangles honor A1 mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 20, height = 16, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(6 to 4, 14 to 4, 6 to 12), maskFormat = XRender.A1Format))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 20, height = 16))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 20, x = 7, y = 5))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 20, x = 10, y = 5))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 20, x = 5, y = 5))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 20, x = 12, y = 10))
                assertContains(httpGet(server.localPort, "/text.txt"), "Triangles")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER A1 triangle clear uses quantized mask alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 20, height = 16, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(6 to 4, 14 to 4, 6 to 12), operation = XRender.OpClear, maskFormat = XRender.A1Format))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 20, height = 16))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 20, x = 7, y = 5))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 20, x = 13, y = 4))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 20, x = 14, y = 4))
                assertContains(httpGet(server.localPort, "/text.txt"), "Triangles")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER A8 triangle clear attenuates partial coverage`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 20, height = 16, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(6 to 4, 14 to 4, 6 to 12), operation = XRender.OpClear))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 20, height = 16))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 20, x = 7, y = 5))
                assertEquals(0x6000_0060, pixelAt(image, imageWidth = 20, x = 13, y = 4))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 20, x = 14, y = 4))
                assertContains(httpGet(server.localPort, "/text.txt"), "Triangles")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER triangles over blends solid source with destination framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 32, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(8 to 6, 24 to 6, 8 to 20), operation = XRender.OpOver))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 32, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff80_007f.toInt(), pixelAt(image, imageWidth = 32, x = 10, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 32, x = 7, y = 6))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER triangles sample generated source using src origin`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 32, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 10,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(
                    renderTriangles(
                        GradientPictureId,
                        PictureId,
                        points = listOf(4 to 3, 18 to 3, 4 to 17),
                        sourceX = 2,
                        sourceY = 1,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 32, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff99_0066.toInt(), pixelAt(image, imageWidth = 32, x = 6, y = 5))
                assertEquals(0xff40_00bf.toInt(), pixelAt(image, imageWidth = 32, x = 10, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 32, x = 3, y = 5))
                assertContains(httpGet(server.localPort, "/text.txt"), "Triangles")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER tri strip composites solid source into destination framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 36, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriStrip(SolidPictureId, PictureId, points = listOf(8 to 6, 22 to 6, 8 to 18, 22 to 18)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 36, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 36, x = 10, y = 8))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 36, x = 19, y = 15))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 36, x = 7, y = 6))
                assertContains(httpGet(server.localPort, "/text.txt"), "TriStrip")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER tri strip honors A1 mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 36, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriStrip(SolidPictureId, PictureId, points = listOf(8 to 6, 22 to 6, 8 to 18, 22 to 18), maskFormat = XRender.A1Format))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 36, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 36, x = 10, y = 8))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 36, x = 19, y = 15))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 36, x = 7, y = 6))
                assertContains(httpGet(server.localPort, "/text.txt"), "TriStrip")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER tri strip samples generated source using src origin`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 36, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 20 to 20,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(
                    renderTriStrip(
                        GradientPictureId,
                        PictureId,
                        points = listOf(4 to 3, 18 to 3, 4 to 17, 18 to 17),
                        sourceX = 1,
                        sourceY = 2,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 36, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffcc_0033.toInt(), pixelAt(image, imageWidth = 36, x = 6, y = 5))
                assertEquals(0xff66_0099.toInt(), pixelAt(image, imageWidth = 36, x = 15, y = 12))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 36, x = 3, y = 5))
                assertContains(httpGet(server.localPort, "/text.txt"), "TriStrip")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER tri fan composites solid source into destination framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 36, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriFan(SolidPictureId, PictureId, points = listOf(15 to 7, 7 to 18, 15 to 20, 25 to 18)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 36, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 36, x = 15, y = 10))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 36, x = 10, y = 16))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 36, x = 16, y = 18))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 36, x = 6, y = 18))
                assertContains(httpGet(server.localPort, "/text.txt"), "TriFan")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER tri fan honors A1 mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 36, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriFan(SolidPictureId, PictureId, points = listOf(15 to 7, 7 to 18, 15 to 20, 25 to 18), maskFormat = XRender.A1Format))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 36, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 36, x = 15, y = 10))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 36, x = 16, y = 18))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 36, x = 6, y = 18))
                assertContains(httpGet(server.localPort, "/text.txt"), "TriFan")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER tri fan samples generated source using src origin`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 36, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 10,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(
                    renderTriFan(
                        GradientPictureId,
                        PictureId,
                        points = listOf(15 to 7, 7 to 18, 15 to 20, 25 to 18),
                        sourceX = 3,
                        sourceY = 1,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 36, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff8c_0073.toInt(), pixelAt(image, imageWidth = 36, x = 10, y = 16))
                assertEquals(0xff26_00d9.toInt(), pixelAt(image, imageWidth = 36, x = 16, y = 18))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 36, x = 6, y = 18))
                assertContains(httpGet(server.localPort, "/text.txt"), "TriFan")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite glyphs sample generated source using src origin`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 24, height = 12, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddA8Glyph(GlyphSetId, GlyphId, width = 4, height = 2, xOff = 4, alphas = ByteArray(8) { 0xff.toByte() }))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 10 to 10,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderChangePicture(GradientPictureId, repeat = XRender.RepeatPad))
                out.write(
                    renderCompositeGlyphs32(
                        source = GradientPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 2,
                        sourceY = 1,
                        deltaX = 4,
                        deltaY = 3,
                        glyphIds = listOf(GlyphId, GlyphId),
                        operation = XRender.OpSrc,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 24, height = 12))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffcc_0033.toInt(), pixelAt(image, imageWidth = 24, x = 4, y = 3))
                assertEquals(0xff80_0080.toInt(), pixelAt(image, imageWidth = 24, x = 10, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 24, x = 3, y = 3))
                assertContains(httpGet(server.localPort, "/text.txt"), "CompositeGlyphs32")
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

    private fun renderComposite(
        source: Int,
        destination: Int,
        mask: Int = 0,
        width: Int = 20,
        height: Int = 10,
        operation: Int = XRender.OpOver,
        sourceX: Int = 0,
        sourceY: Int = 0,
        maskX: Int = 0,
        maskY: Int = 0,
        destinationX: Int = if (mask == 0) 12 else 0,
        destinationY: Int = if (mask == 0) 15 else 0,
    ): ByteArray {
        val body = ByteArray(32)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, mask)
        put32le(body, 12, destination)
        put16le(body, 16, sourceX)
        put16le(body, 18, sourceY)
        put16le(body, 20, maskX)
        put16le(body, 22, maskY)
        put16le(body, 24, destinationX)
        put16le(body, 26, destinationY)
        put16le(body, 28, width)
        put16le(body, 30, height)
        return request(XRender.MajorOpcode, 8, body)
    }

    private fun renderCreateLinearGradient(
        picture: Int,
        p1: Pair<Int, Int>,
        p2: Pair<Int, Int>,
        stops: List<Int>,
        colors: List<RenderColor>,
    ): ByteArray {
        require(stops.size == colors.size)
        val body = ByteArray(24 + stops.size * 4 + colors.size * 8)
        put32le(body, 0, picture)
        putFixedPoint(body, 4, p1.first, p1.second)
        putFixedPoint(body, 12, p2.first, p2.second)
        put32le(body, 20, stops.size)
        stops.forEachIndexed { index, stop ->
            put32le(body, 24 + index * 4, stop)
        }
        val colorOffset = 24 + stops.size * 4
        colors.forEachIndexed { index, color ->
            val offset = colorOffset + index * 8
            put16le(body, offset, color.red)
            put16le(body, offset + 2, color.green)
            put16le(body, offset + 4, color.blue)
            put16le(body, offset + 6, color.alpha)
        }
        return request(XRender.MajorOpcode, 34, body)
    }

    private fun renderCreateRadialGradient(
        picture: Int,
        innerCenter: Pair<Int, Int>,
        innerRadius: Int,
        outerCenter: Pair<Int, Int>,
        outerRadius: Int,
        stops: List<Int>,
        colors: List<RenderColor>,
    ): ByteArray {
        require(stops.size == colors.size)
        val body = ByteArray(32 + stops.size * 4 + colors.size * 8)
        put32le(body, 0, picture)
        putFixedPointRaw(body, 4, innerCenter.first, innerCenter.second)
        putFixedPointRaw(body, 12, outerCenter.first, outerCenter.second)
        put32le(body, 20, innerRadius)
        put32le(body, 24, outerRadius)
        put32le(body, 28, stops.size)
        stops.forEachIndexed { index, stop ->
            put32le(body, 32 + index * 4, stop)
        }
        val colorOffset = 32 + stops.size * 4
        colors.forEachIndexed { index, color ->
            val offset = colorOffset + index * 8
            put16le(body, offset, color.red)
            put16le(body, offset + 2, color.green)
            put16le(body, offset + 4, color.blue)
            put16le(body, offset + 6, color.alpha)
        }
        return request(XRender.MajorOpcode, 35, body)
    }

    private fun renderCreateConicalGradient(
        picture: Int,
        center: Pair<Int, Int>,
        angle: Int,
        stops: List<Int>,
        colors: List<RenderColor>,
    ): ByteArray {
        require(stops.size == colors.size)
        val body = ByteArray(20 + stops.size * 4 + colors.size * 8)
        put32le(body, 0, picture)
        putFixedPointRaw(body, 4, center.first, center.second)
        put32le(body, 12, angle)
        put32le(body, 16, stops.size)
        stops.forEachIndexed { index, stop ->
            put32le(body, 20 + index * 4, stop)
        }
        val colorOffset = 20 + stops.size * 4
        colors.forEachIndexed { index, color ->
            val offset = colorOffset + index * 8
            put16le(body, offset, color.red)
            put16le(body, offset + 2, color.green)
            put16le(body, offset + 4, color.blue)
            put16le(body, offset + 6, color.alpha)
        }
        return request(XRender.MajorOpcode, 36, body)
    }

    private fun renderChangePicture(picture: Int, repeat: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, picture)
        put32le(body, 4, XRender.CPRepeat)
        put32le(body, 8, repeat)
        return request(XRender.MajorOpcode, 5, body)
    }

    private fun renderSetPictureTransform(picture: Int, transform: List<Int>): ByteArray {
        require(transform.size == 9)
        val body = ByteArray(40)
        put32le(body, 0, picture)
        transform.forEachIndexed { index, value ->
            put32le(body, 4 + index * 4, value)
        }
        return request(XRender.MajorOpcode, 28, body)
    }

    private fun renderSetPictureFilter(picture: Int, name: String, values: List<Int>): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val valuesOffset = (8 + nameBytes.size + 3) and -4
        val body = ByteArray(valuesOffset + values.size * 4)
        put32le(body, 0, picture)
        put16le(body, 4, nameBytes.size)
        nameBytes.copyInto(body, 8)
        values.forEachIndexed { index, value ->
            put32le(body, valuesOffset + index * 4, value)
        }
        return request(XRender.MajorOpcode, 30, body)
    }

    private fun renderQueryFilters(drawable: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, drawable)
        return request(XRender.MajorOpcode, 29, body)
    }

    private fun renderTrapezoids(
        source: Int,
        destination: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        sourceX: Int = 0,
        sourceY: Int = 0,
        maskFormat: Int = XRender.A8Format,
    ): ByteArray {
        val body = ByteArray(60)
        body[0] = XRender.OpSrc.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, maskFormat)
        put16le(body, 16, sourceX)
        put16le(body, 18, sourceY)
        putFixed(body, 20, y)
        putFixed(body, 24, y + height)
        putFixedPoint(body, 28, x, y)
        putFixedPoint(body, 36, x, y + height)
        putFixedPoint(body, 44, x + width, y)
        putFixedPoint(body, 52, x + width, y + height)
        return request(XRender.MajorOpcode, 10, body)
    }

    private fun renderAddTraps(picture: Int, xOffset: Int, yOffset: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(32)
        put32le(body, 0, picture)
        put16le(body, 4, xOffset)
        put16le(body, 6, yOffset)
        putFixed(body, 8, x)
        putFixed(body, 12, x + width)
        putFixed(body, 16, y)
        putFixed(body, 20, x)
        putFixed(body, 24, x + width)
        putFixed(body, 28, y + height)
        return request(XRender.MajorOpcode, 32, body)
    }

    private fun renderAddTrapsRaw(
        picture: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        xOffset: Int = 0,
        yOffset: Int = 0,
    ): ByteArray {
        val body = ByteArray(32)
        put32le(body, 0, picture)
        put16le(body, 4, xOffset)
        put16le(body, 6, yOffset)
        put32le(body, 8, left)
        put32le(body, 12, right)
        put32le(body, 16, top)
        put32le(body, 20, left)
        put32le(body, 24, right)
        put32le(body, 28, bottom)
        return request(XRender.MajorOpcode, 32, body)
    }

    private fun renderCreateGlyphSet(glyphSet: Int, format: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, glyphSet)
        put32le(body, 4, format)
        return request(XRender.MajorOpcode, 17, body)
    }

    private fun renderAddA8Glyph(
        glyphSet: Int,
        glyphId: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        xOff: Int = 0,
        yOff: Int = 0,
        alphas: ByteArray,
    ): ByteArray {
        val stride = (width + 3) and -4
        val body = ByteArray(8 + 4 + 12 + stride * height)
        put32le(body, 0, glyphSet)
        put32le(body, 4, 1)
        put32le(body, 8, glyphId)
        put16le(body, 12, width)
        put16le(body, 14, height)
        put16le(body, 16, x)
        put16le(body, 18, y)
        put16le(body, 20, xOff)
        put16le(body, 22, yOff)
        for (row in 0 until height) {
            alphas.copyInto(
                destination = body,
                destinationOffset = 24 + row * stride,
                startIndex = row * width,
                endIndex = row * width + width,
            )
        }
        return request(XRender.MajorOpcode, 20, body)
    }

    private fun renderCompositeGlyphs32(
        source: Int,
        destination: Int,
        glyphSet: Int,
        sourceX: Int,
        sourceY: Int,
        deltaX: Int,
        deltaY: Int,
        glyphIds: List<Int>,
        operation: Int = XRender.OpOver,
    ): ByteArray {
        val idsOffset = 32
        val paddedSize = (idsOffset + glyphIds.size * 4 + 3) and -4
        val body = ByteArray(paddedSize)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, XRender.A8Format)
        put32le(body, 16, glyphSet)
        put16le(body, 20, sourceX)
        put16le(body, 22, sourceY)
        body[24] = glyphIds.size.toByte()
        put16le(body, 26, deltaX)
        put16le(body, 28, deltaY)
        glyphIds.forEachIndexed { index, glyphId ->
            put32le(body, idsOffset + index * 4, glyphId)
        }
        return request(XRender.MajorOpcode, 25, body)
    }

    private fun renderTriangles(
        source: Int,
        destination: Int,
        points: List<Pair<Int, Int>>,
        operation: Int = XRender.OpSrc,
        sourceX: Int = 0,
        sourceY: Int = 0,
        maskFormat: Int = XRender.A8Format,
    ): ByteArray {
        require(points.size == 3)
        val body = ByteArray(44)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, maskFormat)
        put16le(body, 16, sourceX)
        put16le(body, 18, sourceY)
        var offset = 20
        for ((x, y) in points) {
            putFixedPoint(body, offset, x, y)
            offset += 8
        }
        return request(XRender.MajorOpcode, 11, body)
    }

    private fun renderTriStrip(
        source: Int,
        destination: Int,
        points: List<Pair<Int, Int>>,
        sourceX: Int = 0,
        sourceY: Int = 0,
        maskFormat: Int = XRender.A8Format,
    ): ByteArray =
        renderTrianglePointList(
            minorOpcode = 12,
            source = source,
            destination = destination,
            points = points,
            sourceX = sourceX,
            sourceY = sourceY,
            maskFormat = maskFormat,
        )

    private fun renderTriFan(
        source: Int,
        destination: Int,
        points: List<Pair<Int, Int>>,
        sourceX: Int = 0,
        sourceY: Int = 0,
        maskFormat: Int = XRender.A8Format,
    ): ByteArray =
        renderTrianglePointList(
            minorOpcode = 13,
            source = source,
            destination = destination,
            points = points,
            sourceX = sourceX,
            sourceY = sourceY,
            maskFormat = maskFormat,
        )

    private fun renderTrianglePointList(
        minorOpcode: Int,
        source: Int,
        destination: Int,
        points: List<Pair<Int, Int>>,
        sourceX: Int,
        sourceY: Int,
        maskFormat: Int,
    ): ByteArray {
        val body = ByteArray(20 + points.size * 8)
        body[0] = XRender.OpSrc.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, maskFormat)
        put16le(body, 16, sourceX)
        put16le(body, 18, sourceY)
        var offset = 20
        for ((x, y) in points) {
            putFixedPoint(body, offset, x, y)
            offset += 8
        }
        return request(XRender.MajorOpcode, minorOpcode, body)
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

    private fun filterNames(reply: ByteArray): List<String> {
        val aliases = u32le(reply, 8)
        val filters = u32le(reply, 12)
        var offset = 32 + ((aliases * 2 + 3) and -4)
        return (0 until filters).map {
            val length = reply[offset].toInt() and 0xff
            val name = reply.copyOfRange(offset + 1, offset + 1 + length).decodeToString()
            offset += 1 + length
            name
        }
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

    private fun putFixedPointRaw(bytes: ByteArray, offset: Int, x: Int, y: Int) {
        put32le(bytes, offset, x)
        put32le(bytes, offset + 4, y)
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
        const val GradientPictureId = 0x0020_1003
        const val GradientNarrowPictureId = 0x0020_1004
        const val GradientRadialPictureId = 0x0020_1005
        const val GradientConicalPictureId = 0x0020_1006
        const val MaskPixmapId = 0x0020_2001
        const val MaskPictureId = 0x0020_2002
        const val PixmapId = 0x0020_0100
        const val PixmapPictureId = 0x0020_0101
        const val GlyphSetId = 0x0020_3001
        const val GlyphId = 0x0000_0041
    }

    private data class RenderColor(
        val red: Int,
        val green: Int,
        val blue: Int,
        val alpha: Int,
    )
}
