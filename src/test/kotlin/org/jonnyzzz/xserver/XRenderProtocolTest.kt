package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XRenderProtocolTest {
    @Test
    fun `RENDER extension exposes version and picture formats`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
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
                socket.soTimeout = 5_000
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
    fun `RENDER FillRectangles honors explicit empty destination picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(PictureId, rectangles = emptyList()))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpSrc))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff, operation = XRender.OpClear))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetPictureClipRegion clips and clears RENDER picture drawing`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(xfixesQueryVersionRequest(2, 0))
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    xfixesCreateRegion(
                        RegionId,
                        listOf(
                            XRectangleCommand(0, 0, 1, 1),
                            XRectangleCommand(2, 0, 1, 1),
                        ),
                    ),
                )
                out.write(xfixesSetPictureClipRegion(PixmapPictureId, RegionId, originX = 1, originY = 0))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.write(xfixesCreateRegion(EmptyRegionId, emptyList()))
                out.write(xfixesSetPictureClipRegion(PixmapPictureId, EmptyRegionId, originX = 0, originY = 0))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.write(xfixesSetPictureClipRegion(PixmapPictureId, region = 0, originX = 0, originY = 0))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(xfixesCreateRegion(SourceClipRegionId, listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(xfixesSetPictureClipRegion(SolidPictureId, SourceClipRegionId, originX = 0, originY = 0))
                out.write(
                    renderComposite(
                        source = SolidPictureId,
                        destination = PixmapPictureId,
                        width = 2,
                        height = 1,
                        operation = XRender.OpSrc,
                        sourceX = 0,
                        sourceY = 0,
                        destinationX = 0,
                        destinationY = 0,
                    ),
                )
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val version = readReply(socket.getInputStream())
                assertEquals(2, u32le(version, 8))
                assertEquals(0, u32le(version, 12))

                val clipped = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(clipped, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(clipped, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(clipped, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(clipped, imageWidth = 4, x = 3, y = 0))

                val emptyClipped = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(emptyClipped, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(emptyClipped, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(emptyClipped, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(emptyClipped, imageWidth = 4, x = 3, y = 0))

                val cleared = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(cleared, imageWidth = 4, x = 0, y = 0))

                val sourceClipped = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(sourceClipped, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(sourceClipped, imageWidth = 4, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES regions validate resources and recover stream`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val missingRegion = RegionId + 1
                val missingPicture = PixmapPictureId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(xfixesCreateRegion(0, emptyList()))
                out.write(xfixesCreateRegionRaw(ByteArray(8).also { put32le(it, 0, RegionId + 2) }))
                out.write(xfixesSetPictureClipRegionRaw(ByteArray(8).also { put32le(it, 0, PixmapPictureId) }))
                out.write(xfixesDestroyRegionRaw(ByteArray(8).also { put32le(it, 0, RegionId) }))
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(xfixesCreateRegion(RegionId, emptyList()))
                out.write(xfixesSetPictureClipRegion(PixmapPictureId, missingRegion))
                out.write(xfixesDestroyRegion(RegionId))
                out.write(xfixesDestroyRegion(RegionId))
                out.write(xfixesSetPictureClipRegion(missingPicture, region = 0))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XFixes.CreateRegion)
                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 5, minorOpcode = XFixes.CreateRegion)
                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 6, minorOpcode = XFixes.SetPictureClipRegion)
                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 7, minorOpcode = XFixes.DestroyRegion)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = RegionId, sequence = 9, minorOpcode = XFixes.CreateRegion)
                assertExtensionError(socket.getInputStream(), error = XFixes.BadRegion, opcode = XFixes.MajorOpcode, badValue = missingRegion, sequence = 10, minorOpcode = XFixes.SetPictureClipRegion)
                assertExtensionError(socket.getInputStream(), error = XFixes.BadRegion, opcode = XFixes.MajorOpcode, badValue = RegionId, sequence = 12, minorOpcode = XFixes.DestroyRegion)
                assertExtensionError(socket.getInputStream(), error = XRender.PictureError, opcode = XFixes.MajorOpcode, badValue = missingPicture, sequence = 13, minorOpcode = XFixes.SetPictureClipRegion)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetRegion validates region before rectangle alignment`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val missingRegion = RegionId + 1
                val out = socket.getOutputStream()
                out.write(xfixesSetRegionRaw(ByteArray(8).also { put32le(it, 0, missingRegion) }))
                out.write(xfixesCreateRegion(RegionId, emptyList()))
                out.write(xfixesSetRegionRaw(ByteArray(8).also { put32le(it, 0, RegionId) }))
                out.flush()

                assertExtensionError(socket.getInputStream(), error = XFixes.BadRegion, opcode = XFixes.MajorOpcode, badValue = missingRegion, sequence = 1, minorOpcode = XFixes.SetRegion)
                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XFixes.SetRegion)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetWindowShapeRegion validates resources`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val missingWindow = WindowId + 1
                val inputOnly = WindowId + 2
                val missingRegion = RegionId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createInputOnlyWindowRequest(inputOnly))
                out.write(xfixesSetWindowShapeRegionRaw(ByteArray(12).also { put32le(it, 0, WindowId) }))
                out.write(xfixesSetWindowShapeRegion(missingWindow, XFixes.ShapeClip, 0, 0, 0))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, 0, 0, missingRegion))
                out.write(xfixesSetWindowShapeRegion(WindowId, 3, 0, 0, 0))
                out.write(xfixesSetWindowShapeRegion(inputOnly, XFixes.ShapeClip, 0, 0, 0))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, 0, 0, 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XFixes.SetWindowShapeRegion)
                assertExtensionError(socket.getInputStream(), error = 3, opcode = XFixes.MajorOpcode, badValue = missingWindow, sequence = 4, minorOpcode = XFixes.SetWindowShapeRegion)
                assertExtensionError(socket.getInputStream(), error = XFixes.BadRegion, opcode = XFixes.MajorOpcode, badValue = missingRegion, sequence = 5, minorOpcode = XFixes.SetWindowShapeRegion)
                assertExtensionError(socket.getInputStream(), error = 2, opcode = XFixes.MajorOpcode, badValue = 3, sequence = 6, minorOpcode = XFixes.SetWindowShapeRegion)
                assertExtensionError(socket.getInputStream(), error = 8, opcode = XFixes.MajorOpcode, badValue = inputOnly, sequence = 7, minorOpcode = XFixes.SetWindowShapeRegion)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromWindow validates resources and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val missingWindow = WindowId + 1
                val inputOnly = WindowId + 2
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createInputOnlyWindowRequest(inputOnly))
                out.write(xfixesCreateRegionFromWindowRaw(ByteArray(8).also { put32le(it, 0, RegionId) }))
                out.write(xfixesCreateRegionFromWindow(0, WindowId, XFixes.ShapeBounding))
                out.write(xfixesCreateRegionFromWindow(RegionId, missingWindow, XFixes.ShapeBounding))
                out.write(xfixesCreateRegionFromWindow(RegionId, WindowId, 3))
                out.write(xfixesCreateRegionFromWindow(RegionId, inputOnly, XFixes.ShapeClip))
                out.write(xfixesCreateRegion(RegionBId, emptyList()))
                out.write(xfixesCreateRegionFromWindow(RegionBId, WindowId, XFixes.ShapeBounding))
                out.write(xfixesCreateRegionFromWindow(RegionId, WindowId, XFixes.ShapeBounding))
                out.write(xfixesFetchRegion(RegionId))
                out.flush()

                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XFixes.CreateRegionFromWindow)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XFixes.CreateRegionFromWindow)
                assertExtensionError(socket.getInputStream(), error = 3, opcode = XFixes.MajorOpcode, badValue = missingWindow, sequence = 5, minorOpcode = XFixes.CreateRegionFromWindow)
                assertExtensionError(socket.getInputStream(), error = 2, opcode = XFixes.MajorOpcode, badValue = 3, sequence = 6, minorOpcode = XFixes.CreateRegionFromWindow)
                assertExtensionError(socket.getInputStream(), error = 8, opcode = XFixes.MajorOpcode, badValue = inputOnly, sequence = 7, minorOpcode = XFixes.CreateRegionFromWindow)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = RegionBId, sequence = 9, minorOpcode = XFixes.CreateRegionFromWindow)
                assertRegionReply(socket.getInputStream(), XRectangleCommand(-1, -1, 102, 82), listOf(XRectangleCommand(-1, -1, 102, 82)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromWindow snapshots window shapes`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(xfixesCreateRegion(RegionBId, listOf(XRectangleCommand(1, 0, 2, 1))))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 1, yOffset = 2, region = RegionBId))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeInput, xOffset = 3, yOffset = 4, region = RegionBId))
                out.write(xfixesCreateRegionFromWindow(RegionId, WindowId, XFixes.ShapeClip))
                out.write(xfixesCreateRegionFromWindow(RegionResultId, WindowId, XFixes.ShapeInput))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 0, yOffset = 0, region = 0))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeInput, xOffset = 0, yOffset = 0, region = 0))
                out.write(xfixesFetchRegion(RegionId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesCreateRegionFromWindow(RegionExtentsId, WindowId, XFixes.ShapeBounding))
                out.write(xfixesFetchRegion(RegionExtentsId))
                out.flush()

                assertRegionReply(socket.getInputStream(), XRectangleCommand(2, 2, 2, 1), listOf(XRectangleCommand(2, 2, 2, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(4, 4, 2, 1), listOf(XRectangleCommand(4, 4, 2, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(-1, -1, 102, 82), listOf(XRectangleCommand(-1, -1, 102, 82)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES InvertRegion validates framing and resources`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val missingRegion = RegionId + 1
                val missingDestination = RegionId + 2
                val out = socket.getOutputStream()
                out.write(xfixesInvertRegionRaw(ByteArray(12).also { put32le(it, 0, missingRegion) }))
                out.write(xfixesCreateRegion(RegionResultId, emptyList()))
                out.write(xfixesInvertRegion(missingRegion, x = 0, y = 0, width = 1, height = 1, destination = RegionResultId))
                out.write(xfixesCreateRegion(RegionId, emptyList()))
                out.write(xfixesInvertRegion(RegionId, x = 0, y = 0, width = 1, height = 1, destination = missingDestination))
                out.flush()

                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XFixes.InvertRegion)
                assertExtensionError(socket.getInputStream(), error = XFixes.BadRegion, opcode = XFixes.MajorOpcode, badValue = missingRegion, sequence = 3, minorOpcode = XFixes.InvertRegion)
                assertExtensionError(socket.getInputStream(), error = XFixes.BadRegion, opcode = XFixes.MajorOpcode, badValue = missingDestination, sequence = 5, minorOpcode = XFixes.InvertRegion)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetWindowShapeRegion applies copied clip to core drawing`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(PutImageGcId, WindowId))
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 1, yOffset = 0, region = RegionId))
                out.write(xfixesSetRegion(RegionId, listOf(XRectangleCommand(3, 0, 1, 1))))
                out.write(polyFillRectangle(WindowId, PutImageGcId, listOf(XRectangleCommand(0, 0, 5, 1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 1))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 0, yOffset = 0, region = 0))
                out.write(polyFillRectangle(WindowId, PutImageGcId, listOf(XRectangleCommand(4, 0, 1, 1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 1))
                out.flush()

                val clipped = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 0, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(clipped, imageWidth = 5, x = 1, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 2, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 3, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 4, y = 0))

                val unclipped = readReply(socket.getInputStream())
                assertEquals(0xff00_0000.toInt(), pixelAt(unclipped, imageWidth = 5, x = 1, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(unclipped, imageWidth = 5, x = 4, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetWindowShapeRegion intersects with GC clip and leaves pixmaps unaffected`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val pixmapGc = PutImageGcId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 3, height = 1))
                out.write(createGcRequest(PutImageGcId, WindowId))
                out.write(createGcRequest(pixmapGc, PixmapId))
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(1, 0, 2, 1))))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 0, yOffset = 0, region = RegionId))
                out.write(setClipRectangles(PutImageGcId, originX = 0, originY = 0, rectangles = listOf(XRectangleCommand(2, 0, 2, 1))))
                out.write(polyFillRectangle(WindowId, PutImageGcId, listOf(XRectangleCommand(0, 0, 5, 1))))
                out.write(polyFillRectangle(PixmapId, pixmapGc, listOf(XRectangleCommand(0, 0, 3, 1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val windowImage = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(windowImage, imageWidth = 5, x = 0, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(windowImage, imageWidth = 5, x = 1, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(windowImage, imageWidth = 5, x = 2, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(windowImage, imageWidth = 5, x = 3, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(windowImage, imageWidth = 5, x = 4, y = 0))

                val pixmapImage = readReply(socket.getInputStream())
                assertEquals(0xff00_0000.toInt(), pixelAt(pixmapImage, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(pixmapImage, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(pixmapImage, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetWindowShapeRegion clips CopyPlane and copy exposure background repair`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val copyGc = PutImageGcId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(MaskPixmapId, depth = 1, width = 3, height = 1))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(createGcRequest(PutImageGcId, MaskPixmapId))
                out.write(putImage1OnlyRequest(MaskPixmapId, width = 3, height = 1, bits = listOf(true, true, true)))
                out.write(createGcRequest(copyGc, WindowId, graphicsExposures = false))
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(1, 0, 1, 1))))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 0, yOffset = 0, region = RegionId))
                out.write(copyPlaneRequest(MaskPixmapId, WindowId, copyGc, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 3, height = 1, bitPlane = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 0, yOffset = 0, region = 0))
                out.write(polyFillRectangle(WindowId, copyGc, listOf(XRectangleCommand(0, 0, 3, 1))))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 0, yOffset = 0, region = RegionId))
                out.write(copyAreaRequest(PixmapId, WindowId, copyGc, sourceX = 0, sourceY = 0, destinationX = 0, destinationY = 0, width = 3, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val copyPlaneImage = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(copyPlaneImage, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(copyPlaneImage, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(copyPlaneImage, imageWidth = 3, x = 2, y = 0))

                val exposureRepairImage = readReply(socket.getInputStream())
                assertEquals(0xff00_0000.toInt(), pixelAt(exposureRepairImage, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(exposureRepairImage, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(exposureRepairImage, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetWindowShapeRegion clips RENDER fill destinations`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(1, 0, 1, 1))))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeClip, xOffset = 0, yOffset = 0, region = RegionId))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetWindowShapeRegion applies input shape to pointer hit testing`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(mapWindowRequest(WindowId))
                server.input.click(12, 20)
                out.write(queryPointerRequest())
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(xfixesSetWindowShapeRegion(WindowId, XFixes.ShapeInput, xOffset = 0, yOffset = 0, region = RegionId))
                out.write(queryPointerRequest())
                out.flush()

                val beforeShape = readReplySkippingEvents(socket.getInputStream())
                assertEquals(WindowId, u32le(beforeShape, 12))

                val afterShape = readReplySkippingEvents(socket.getInputStream())
                assertEquals(0, u32le(afterShape, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromGC and Picture validate resources`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val missingGc = PutImageGcId + 1
                val missingPicture = PixmapPictureId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(createGcRequest(PutImageGcId, WindowId))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(xfixesCreateRegionFromGcRaw(ByteArray(4).also { put32le(it, 0, RegionBId) }))
                out.write(xfixesCreateRegionFromGc(0, PutImageGcId))
                out.write(xfixesCreateRegionFromGc(RegionBId, PutImageGcId))
                out.write(xfixesCreateRegion(RegionId, emptyList()))
                out.write(xfixesCreateRegionFromGc(RegionId, PutImageGcId))
                out.write(xfixesCreateRegionFromGc(RegionBId, missingGc))
                out.write(xfixesCreateRegionFromPictureRaw(ByteArray(4).also { put32le(it, 0, RegionResultId) }))
                out.write(xfixesCreateRegionFromPicture(0, PixmapPictureId))
                out.write(xfixesCreateRegionFromPicture(RegionResultId, PixmapPictureId))
                out.write(xfixesCreateRegionFromPicture(RegionResultId, missingPicture))
                out.flush()

                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 5, minorOpcode = XFixes.CreateRegionFromGC)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 6, minorOpcode = XFixes.CreateRegionFromGC)
                assertExtensionError(socket.getInputStream(), error = 8, opcode = XFixes.MajorOpcode, badValue = PutImageGcId, sequence = 7, minorOpcode = XFixes.CreateRegionFromGC)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = RegionId, sequence = 9, minorOpcode = XFixes.CreateRegionFromGC)
                assertExtensionError(socket.getInputStream(), error = 13, opcode = XFixes.MajorOpcode, badValue = missingGc, sequence = 10, minorOpcode = XFixes.CreateRegionFromGC)
                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 11, minorOpcode = XFixes.CreateRegionFromPicture)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 12, minorOpcode = XFixes.CreateRegionFromPicture)
                assertExtensionError(socket.getInputStream(), error = 8, opcode = XFixes.MajorOpcode, badValue = PixmapPictureId, sequence = 13, minorOpcode = XFixes.CreateRegionFromPicture)
                assertExtensionError(socket.getInputStream(), error = XRender.PictureError, opcode = XFixes.MajorOpcode, badValue = missingPicture, sequence = 14, minorOpcode = XFixes.CreateRegionFromPicture)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromBitmap validates resources`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val missingPixmap = MaskPixmapId + 100
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(xfixesCreateRegionFromBitmapRaw(ByteArray(4).also { put32le(it, 0, RegionBId) }))
                out.write(xfixesCreateRegionFromBitmap(0, PixmapId))
                out.write(xfixesCreateRegionFromBitmap(RegionBId, missingPixmap))
                out.write(xfixesCreateRegionFromBitmap(RegionBId, PixmapId))
                out.write(xfixesCreateRegion(RegionId, emptyList()))
                out.write(xfixesCreateRegionFromBitmap(RegionId, missingPixmap))
                out.flush()

                assertExtensionError(socket.getInputStream(), error = 16, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XFixes.CreateRegionFromBitmap)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XFixes.CreateRegionFromBitmap)
                assertExtensionError(socket.getInputStream(), error = 4, opcode = XFixes.MajorOpcode, badValue = missingPixmap, sequence = 5, minorOpcode = XFixes.CreateRegionFromBitmap)
                assertExtensionError(socket.getInputStream(), error = 8, opcode = XFixes.MajorOpcode, badValue = PixmapId, sequence = 6, minorOpcode = XFixes.CreateRegionFromBitmap)
                assertExtensionError(socket.getInputStream(), error = 14, opcode = XFixes.MajorOpcode, badValue = RegionId, sequence = 8, minorOpcode = XFixes.CreateRegionFromBitmap)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromBitmap treats default bitmap pixels as unset`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(MaskPixmapId, depth = 1, width = 3, height = 2))
                out.write(xfixesCreateRegionFromBitmap(RegionId, MaskPixmapId))
                out.write(xfixesFetchRegion(RegionId))
                out.flush()

                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 0, 0), emptyList())
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromBitmap snapshots bitmap pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(MaskPixmapId, depth = 1, width = 4, height = 2))
                out.write(createGcRequest(PutImageGcId, MaskPixmapId))
                out.write(
                    putImage1OnlyRequest(
                        MaskPixmapId,
                        width = 4,
                        height = 2,
                        bits = listOf(
                            true,
                            true,
                            false,
                            false,
                            false,
                            true,
                            true,
                            true,
                        ),
                    ),
                )
                out.write(xfixesCreateRegionFromBitmap(RegionId, MaskPixmapId))
                out.write(putImage1OnlyRequest(MaskPixmapId, width = 4, height = 2, bits = List(8) { false }))
                out.write(xfixesFetchRegion(RegionId))
                out.flush()

                assertRegionReply(
                    socket.getInputStream(),
                    XRectangleCommand(0, 0, 4, 2),
                    listOf(
                        XRectangleCommand(0, 0, 2, 1),
                        XRectangleCommand(1, 1, 3, 1),
                    ),
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromGC and Picture snapshot clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 4, height = 4))
                out.write(createGcRequest(PutImageGcId, WindowId))
                out.write(setClipRectangles(PutImageGcId, originX = 2, originY = 3, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(xfixesCreateRegionFromGc(RegionId, PutImageGcId))
                out.write(setClipRectangles(PutImageGcId, originX = 0, originY = 0, rectangles = listOf(XRectangleCommand(3, 3, 1, 1))))
                out.write(xfixesFetchRegion(RegionId))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderSetPictureClipRectangles(PixmapPictureId, originX = 1, originY = 2, rectangles = listOf(XRectangleCommand(1, 0, 2, 1))))
                out.write(xfixesCreateRegionFromPicture(RegionBId, PixmapPictureId))
                out.write(renderSetPictureClipRectangles(PixmapPictureId, originX = 0, originY = 0, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(xfixesFetchRegion(RegionBId))
                out.flush()

                assertRegionReply(socket.getInputStream(), XRectangleCommand(2, 3, 1, 1), listOf(XRectangleCommand(2, 3, 1, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(2, 2, 2, 1), listOf(XRectangleCommand(2, 2, 2, 1)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES CreateRegionFromPicture snapshots clip mask pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 4, height = 4))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 2))
                out.write(
                    putImage8Request(
                        MaskPixmapId,
                        width = 3,
                        height = 2,
                        alphas = byteArrayOf(
                            0xff.toByte(),
                            0xff.toByte(),
                            0x00,
                            0x00,
                            0xff.toByte(),
                            0xff.toByte(),
                        ),
                    ),
                )
                out.write(
                    renderCreatePictureWithAttributes(
                        PixmapPictureId,
                        PixmapId,
                        XRender.Rgb24Format,
                        XRender.CPClipXOrigin to 1,
                        XRender.CPClipYOrigin to 2,
                        XRender.CPClipMask to MaskPixmapId,
                    ),
                )
                out.write(xfixesCreateRegionFromPicture(RegionId, PixmapPictureId))
                out.write(putImage8OnlyRequest(MaskPixmapId, width = 3, height = 2, alphas = ByteArray(6)))
                out.write(renderChangePictureClipMaskNone(PixmapPictureId))
                out.write(xfixesFetchRegion(RegionId))
                out.flush()

                assertRegionReply(
                    socket.getInputStream(),
                    XRectangleCommand(1, 2, 3, 2),
                    listOf(
                        XRectangleCommand(1, 2, 2, 1),
                        XRectangleCommand(2, 3, 2, 1),
                    ),
                )
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES region operations update and fetch rectangle sets`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(0, 0, 2, 1))))
                out.write(xfixesCreateRegion(RegionBId, listOf(XRectangleCommand(1, 0, 2, 1))))
                out.write(xfixesCreateRegion(RegionResultId, emptyList()))
                out.write(xfixesCreateRegion(RegionExtentsId, emptyList()))
                out.write(
                    xfixesCreateRegion(
                        VerticalRegionId,
                        listOf(
                            XRectangleCommand(0, 0, 1, 1),
                            XRectangleCommand(0, 1, 1, 1),
                        ),
                    ),
                )
                out.write(
                    xfixesCreateRegion(
                        OverlapRegionId,
                        listOf(
                            XRectangleCommand(0, 0, 2, 1),
                            XRectangleCommand(1, 0, 2, 1),
                            XRectangleCommand(4, 0, 0, 1),
                        ),
                    ),
                )
                out.write(xfixesFetchRegion(VerticalRegionId))
                out.write(xfixesFetchRegion(OverlapRegionId))
                out.write(xfixesInvertRegion(RegionExtentsId, x = 0, y = 0, width = 2, height = 1, destination = RegionResultId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesInvertRegion(RegionId, x = 0, y = 0, width = 2, height = 1, destination = RegionResultId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesRegionExtents(RegionExtentsId, RegionResultId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesCopyRegion(RegionId, RegionResultId))
                out.write(xfixesTranslateRegion(RegionResultId, dx = 1, dy = 1))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesUnionRegion(RegionId, RegionBId, RegionResultId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesRegionExtents(RegionResultId, RegionExtentsId))
                out.write(xfixesFetchRegion(RegionExtentsId))
                out.write(xfixesIntersectRegion(RegionId, RegionBId, RegionResultId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesSubtractRegion(RegionId, RegionBId, RegionResultId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesInvertRegion(RegionId, x = 0, y = 0, width = 3, height = 1, destination = RegionResultId))
                out.write(xfixesFetchRegion(RegionResultId))
                out.write(xfixesSetRegion(RegionResultId, listOf(XRectangleCommand(2, 0, 1, 1))))
                out.write(xfixesFetchRegion(RegionResultId))
                out.flush()

                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 1, 2), listOf(XRectangleCommand(0, 0, 1, 2)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 3, 1), listOf(XRectangleCommand(0, 0, 3, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 2, 1), listOf(XRectangleCommand(0, 0, 2, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 0, 0), emptyList())
                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 0, 0), emptyList())
                assertRegionReply(socket.getInputStream(), XRectangleCommand(1, 1, 2, 1), listOf(XRectangleCommand(1, 1, 2, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 3, 1), listOf(XRectangleCommand(0, 0, 3, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 3, 1), listOf(XRectangleCommand(0, 0, 3, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(1, 0, 1, 1), listOf(XRectangleCommand(1, 0, 1, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(0, 0, 1, 1), listOf(XRectangleCommand(0, 0, 1, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(2, 0, 1, 1), listOf(XRectangleCommand(2, 0, 1, 1)))
                assertRegionReply(socket.getInputStream(), XRectangleCommand(2, 0, 1, 1), listOf(XRectangleCommand(2, 0, 1, 1)))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XFIXES SetGCClipRegion applies region clips to core drawing`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createGcRequest(PutImageGcId, WindowId))
                out.write(xfixesCreateRegion(RegionId, listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(xfixesSetGcClipRegion(PutImageGcId, RegionId, originX = 1, originY = 0))
                out.write(xfixesSetRegion(RegionId, listOf(XRectangleCommand(3, 0, 1, 1))))
                out.write(polyFillRectangle(WindowId, PutImageGcId, listOf(XRectangleCommand(0, 0, 5, 1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 1))
                out.write(xfixesSetGcClipRegion(PutImageGcId, region = 0, originX = 0, originY = 0))
                out.write(polyFillRectangle(WindowId, PutImageGcId, listOf(XRectangleCommand(4, 0, 1, 1))))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 1))
                out.flush()

                val clipped = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 0, y = 0))
                assertEquals(0xff00_0000.toInt(), pixelAt(clipped, imageWidth = 5, x = 1, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 2, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 3, y = 0))
                assertEquals(0xffff_ffff.toInt(), pixelAt(clipped, imageWidth = 5, x = 4, y = 0))

                val cleared = readReply(socket.getInputStream())
                assertEquals(0xff00_0000.toInt(), pixelAt(cleared, imageWidth = 5, x = 4, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreatePicture rejects duplicate resource id without replacing existing picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 4, height = 4))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 16, height = 16, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreatePicture(PictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 2, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(PictureId, u32le(duplicateError, 4))
                assertEquals(4, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale validates framing and picture resources`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val missingPicture = PictureId + 1
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderRaw(9, ByteArray(24)))
                out.write(renderScale(missingPicture, PictureId))
                out.write(renderScale(PictureId, missingPicture))
                out.write(renderScale(PictureId, PictureId, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = 9)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingPicture, sequence = 4, minorOpcode = 9)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingPicture, sequence = 5, minorOpcode = 9)
                val image = readReply(socket.getInputStream())
                assertEquals(7, u16le(image, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale applies color factor to source pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x4000, blue = 0x2000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x2000, green = 0x6000, blue = 0xc000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderScale(PixmapPictureId, PictureId, colorScale = 0x8000, alphaScale = 0x1_0000, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff40_2010.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff10_3060.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
                assertContains(httpGet(server.localPort, "/text.txt"), "Scale")

                out.write(renderScale(PixmapPictureId, PictureId, colorScale = -1, alphaScale = 0x1_0000, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val saturated = readReply(socket.getInputStream())
                assertEquals(0xffff_ffff.toInt(), pixelAt(saturated, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale clips transformed non repeated source misses`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
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
                out.write(renderScale(PixmapPictureId, PictureId, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale clips bilinear transformed non repeated source misses`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureFilter(PixmapPictureId, "bilinear", values = emptyList()))
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
                out.write(renderScale(PixmapPictureId, PictureId, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale keeps bilinear transformed edge samples`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureFilter(PixmapPictureId, "bilinear", values = emptyList()))
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
                out.write(renderScale(PixmapPictureId, PictureId, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8000_fe00.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale honors drawable source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(PixmapPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderScale(PixmapPictureId, PictureId, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale honors solid source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(SolidPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderScale(SolidPictureId, PictureId, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale honors explicit empty source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(SolidPictureId, rectangles = emptyList()))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderScale(SolidPictureId, PictureId, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER ChangePicture clip mask None clears source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(SolidPictureId, rectangles = emptyList()))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderScale(SolidPictureId, PictureId, width = 1, height = 1))
                out.write(renderChangePictureClipMaskNone(SolidPictureId))
                out.write(renderScale(SolidPictureId, PictureId, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale honors gradient source picture clip rectangles`() {
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
                        p2 = 1 to 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderSetPictureClipRectangles(GradientPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderScale(GradientPictureId, PictureId, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Scale honors transformed drawable source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 3, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
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
                out.write(renderSetPictureClipRectangles(PixmapPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderScale(PixmapPictureId, PictureId, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateSolidFill rejects duplicate resource id without replacing existing solid picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(SolidPictureId, u32le(duplicateError, 4))
                assertEquals(33, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateSolidFill validates framing and duplicate ids`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFillRaw(ByteArray(8).also {
                    put32le(it, 0, SolidPictureId)
                }))
                out.write(renderCreateSolidFillRaw(ByteArray(16).also {
                    put32le(it, 0, SolidPictureId)
                    put16le(it, 4, 0x0000)
                    put16le(it, 6, 0xffff)
                    put16le(it, 8, 0x0000)
                    put16le(it, 10, 0xffff)
                }))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = 33)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 33)
                assertError(socket.getInputStream(), error = 14, badValue = SolidPictureId, sequence = 6, minorOpcode = 33)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateLinearGradient rejects duplicate resource id without replacing existing gradient picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 1 to 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 1 to 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderComposite(GradientPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(GradientPictureId, u32le(duplicateError, 4))
                assertEquals(34, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateLinearGradient validates stop framing and duplicate ids`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateLinearGradientRaw(ByteArray(20).also {
                    put32le(it, 0, GradientPictureId)
                    putFixedPoint(it, 4, 0, 0)
                    putFixedPoint(it, 12, 1, 0)
                }))
                out.write(renderCreateLinearGradientRaw(ByteArray(28).also {
                    put32le(it, 0, GradientPictureId)
                    putFixedPoint(it, 4, 0, 0)
                    putFixedPoint(it, 12, 1, 0)
                    put32le(it, 20, 1)
                    put32le(it, 24, 0)
                }))
                out.write(renderCreateLinearGradientRaw(ByteArray(40).also {
                    put32le(it, 0, GradientPictureId)
                    putFixedPoint(it, 4, 0, 0)
                    putFixedPoint(it, 12, 1, 0)
                    put32le(it, 20, 1)
                    put32le(it, 24, 0)
                    put16le(it, 28, 0x0000)
                    put16le(it, 30, 0xffff)
                    put16le(it, 32, 0x0000)
                    put16le(it, 34, 0xffff)
                }))
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 1 to 0,
                        stops = listOf(0),
                        colors = listOf(RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff)),
                    ),
                )
                out.write(
                    renderCreateLinearGradient(
                        GradientPictureId,
                        p1 = 0 to 0,
                        p2 = 1 to 0,
                        stops = listOf(0),
                        colors = listOf(RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff)),
                    ),
                )
                out.write(renderComposite(GradientPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = 34)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 34)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 34)
                assertError(socket.getInputStream(), error = 14, badValue = GradientPictureId, sequence = 7, minorOpcode = 34)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateRadialGradient rejects duplicate resource id without replacing existing gradient picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateRadialGradient(
                        GradientRadialPictureId,
                        innerCenter = 0 to 0,
                        innerRadius = 0,
                        outerCenter = 0 to 0,
                        outerRadius = 0x0001_0000,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(
                    renderCreateRadialGradient(
                        GradientRadialPictureId,
                        innerCenter = 0 to 0,
                        innerRadius = 0,
                        outerCenter = 0 to 0,
                        outerRadius = 0x0001_0000,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderComposite(GradientRadialPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(GradientRadialPictureId, u32le(duplicateError, 4))
                assertEquals(35, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateRadialGradient validates stop framing and duplicate ids`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateRadialGradientRaw(ByteArray(28).also {
                    put32le(it, 0, GradientRadialPictureId)
                    putFixedPointRaw(it, 4, 0, 0)
                    putFixedPointRaw(it, 12, 0, 0)
                    put32le(it, 20, 0)
                    put32le(it, 24, 0x0001_0000)
                }))
                out.write(renderCreateRadialGradientRaw(ByteArray(36).also {
                    put32le(it, 0, GradientRadialPictureId)
                    putFixedPointRaw(it, 4, 0, 0)
                    putFixedPointRaw(it, 12, 0, 0)
                    put32le(it, 20, 0)
                    put32le(it, 24, 0x0001_0000)
                    put32le(it, 28, 1)
                    put32le(it, 32, 0)
                }))
                out.write(renderCreateRadialGradientRaw(ByteArray(48).also {
                    put32le(it, 0, GradientRadialPictureId)
                    putFixedPointRaw(it, 4, 0, 0)
                    putFixedPointRaw(it, 12, 0, 0)
                    put32le(it, 20, 0)
                    put32le(it, 24, 0x0001_0000)
                    put32le(it, 28, 1)
                    put32le(it, 32, 0)
                    put16le(it, 36, 0x0000)
                    put16le(it, 38, 0xffff)
                    put16le(it, 40, 0x0000)
                    put16le(it, 42, 0xffff)
                }))
                out.write(
                    renderCreateRadialGradient(
                        GradientRadialPictureId,
                        innerCenter = 0 to 0,
                        innerRadius = 0,
                        outerCenter = 0 to 0,
                        outerRadius = 0x0001_0000,
                        stops = listOf(0),
                        colors = listOf(RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff)),
                    ),
                )
                out.write(
                    renderCreateRadialGradient(
                        GradientRadialPictureId,
                        innerCenter = 0 to 0,
                        innerRadius = 0,
                        outerCenter = 0 to 0,
                        outerRadius = 0x0001_0000,
                        stops = listOf(0),
                        colors = listOf(RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff)),
                    ),
                )
                out.write(renderComposite(GradientRadialPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = 35)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 35)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 35)
                assertError(socket.getInputStream(), error = 14, badValue = GradientRadialPictureId, sequence = 7, minorOpcode = 35)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateConicalGradient rejects duplicate resource id without replacing existing gradient picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderCreateConicalGradient(
                        GradientConicalPictureId,
                        center = 0 to 0,
                        angle = 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(
                    renderCreateConicalGradient(
                        GradientConicalPictureId,
                        center = 0 to 0,
                        angle = 0,
                        stops = listOf(0, 0x0001_0000),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(renderComposite(GradientConicalPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(GradientConicalPictureId, u32le(duplicateError, 4))
                assertEquals(36, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateConicalGradient validates stop framing and duplicate ids`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateConicalGradientRaw(ByteArray(16).also {
                    put32le(it, 0, GradientConicalPictureId)
                    putFixedPointRaw(it, 4, 0, 0)
                    put32le(it, 12, 0)
                }))
                out.write(renderCreateConicalGradientRaw(ByteArray(24).also {
                    put32le(it, 0, GradientConicalPictureId)
                    putFixedPointRaw(it, 4, 0, 0)
                    put32le(it, 12, 0)
                    put32le(it, 16, 1)
                    put32le(it, 20, 0)
                }))
                out.write(renderCreateConicalGradientRaw(ByteArray(36).also {
                    put32le(it, 0, GradientConicalPictureId)
                    putFixedPointRaw(it, 4, 0, 0)
                    put32le(it, 12, 0)
                    put32le(it, 16, 1)
                    put32le(it, 20, 0)
                    put16le(it, 24, 0x0000)
                    put16le(it, 26, 0xffff)
                    put16le(it, 28, 0x0000)
                    put16le(it, 30, 0xffff)
                }))
                out.write(
                    renderCreateConicalGradient(
                        GradientConicalPictureId,
                        center = 0 to 0,
                        angle = 0,
                        stops = listOf(0),
                        colors = listOf(RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff)),
                    ),
                )
                out.write(
                    renderCreateConicalGradient(
                        GradientConicalPictureId,
                        center = 0 to 0,
                        angle = 0,
                        stops = listOf(0),
                        colors = listOf(RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff)),
                    ),
                )
                out.write(renderComposite(GradientConicalPictureId, PictureId, width = 1, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = 36)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 36)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 36)
                assertError(socket.getInputStream(), error = 14, badValue = GradientConicalPictureId, sequence = 7, minorOpcode = 36)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateCursor rejects duplicate resource id without replacing existing resource`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateCursor(WindowId, PictureId))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(27, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateAnimCursor rejects duplicate resource id without replacing existing resource`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateAnimCursor(WindowId))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(31, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreateAnimCursor validates framing source cursors and duplicate ids`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val sourceCursor = WindowId + 0x180
                val animatedCursor = sourceCursor + 1
                val missingCursor = sourceCursor + 2
                val animationDelay = Int.MIN_VALUE + 5
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateCursor(sourceCursor, PictureId))
                out.write(request(XRender.MajorOpcode, 31, ByteArray(0)))
                out.write(renderCreateAnimCursorRaw(ByteArray(8).also {
                    put32le(it, 0, animatedCursor)
                    put32le(it, 4, sourceCursor)
                }))
                out.write(renderCreateAnimCursor(animatedCursor))
                out.write(renderCreateAnimCursor(animatedCursor, missingCursor to 75))
                out.write(renderCreateAnimCursor(animatedCursor, sourceCursor to animationDelay))
                out.write(renderCreateAnimCursorRaw(ByteArray(8).also {
                    put32le(it, 0, animatedCursor)
                    put32le(it, 4, sourceCursor)
                }))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 31)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 31)
                assertError(socket.getInputStream(), error = 2, badValue = 0, sequence = 6, minorOpcode = 31)
                assertError(socket.getInputStream(), error = 6, badValue = missingCursor, sequence = 7, minorOpcode = 31)
                assertError(socket.getInputStream(), error = 14, badValue = animatedCursor, sequence = 9, minorOpcode = 31)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))

                val stateJson = httpGet(server.localPort, "/state.json")
                assertContains(stateJson, """"id":"0x${animatedCursor.toUInt().toString(16)}","kind":"animated"""")
                assertContains(
                    stateJson,
                    """"animation":[{"cursor":"0x${sourceCursor.toUInt().toString(16)}","delay":${animationDelay.toUInt().toLong()}}]""",
                )
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
    fun `RENDER picture attributes are retained in semantic snapshot`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(
                    renderCreatePictureWithAttributes(
                        PictureId,
                        WindowId,
                        XRender.Rgb24Format,
                        XRender.CPRepeat to XRender.RepeatNormal,
                        XRender.CPAlphaMap to MaskPictureId,
                        XRender.CPAlphaXOrigin to -2,
                        XRender.CPAlphaYOrigin to 3,
                        XRender.CPClipXOrigin to 4,
                        XRender.CPClipYOrigin to -5,
                        XRender.CPClipMask to MaskPixmapId,
                        XRender.CPGraphicsExposure to 1,
                        XRender.CPSubwindowMode to 1,
                        XRender.CPPolyEdge to 1,
                        XRender.CPPolyMode to 1,
                        XRender.CPDither to 0x1234_5678,
                    ),
                )
                out.write(
                    renderChangePictureAttributes(
                        PictureId,
                        XRender.CPClipXOrigin to -8,
                        XRender.CPPolyMode to XRender.PolyModeImprecise,
                    ),
                )
                out.flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""clipOrigin":[-8,-5]""")
                }
                val json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"alphaMap":"0x${MaskPictureId.toString(16)}"""")
                assertContains(json, """"alphaOrigin":[-2,3]""")
                assertContains(json, """"clipOrigin":[-8,-5]""")
                assertContains(json, """"clipMask":"0x${MaskPixmapId.toString(16)}"""")
                assertContains(json, """"graphicsExposure":true""")
                assertContains(json, """"subwindowMode":1""")
                assertContains(json, """"polyEdge":1""")
                assertContains(json, """"polyMode":1""")
                assertContains(json, """"dither":"0x12345678"""")

                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "alphaMap=0x${MaskPictureId.toString(16)}")
                assertContains(text, "alphaOrigin=-2,3")
                assertContains(text, "clipOrigin=-8,-5")
                assertContains(text, "clipMask=0x${MaskPixmapId.toString(16)}")
                assertContains(text, "graphicsExposure=true")
                assertContains(text, "subwindowMode=1")
                assertContains(text, "polyEdge=1")
                assertContains(text, "polyMode=1")
                assertContains(text, "dither=0x12345678")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER clip mask attribute replaces rectangle clips and None clears it`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 4, height = 4))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(
                    renderSetPictureClipRectangles(
                        PictureId,
                        rectangles = listOf(
                            XRectangleCommand(0, 0, 1, 1),
                            XRectangleCommand(2, 2, 1, 1),
                        ),
                    ),
                )
                out.write(renderChangePictureAttributes(PictureId, XRender.CPClipMask to MaskPixmapId))
                out.flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""clipMask":"0x${MaskPixmapId.toString(16)}"""")
                }
                var json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"clipMask":"0x${MaskPixmapId.toString(16)}"""")
                assertContains(json, """"clipRectangles":0""")
                var text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "clipMask=0x${MaskPixmapId.toString(16)}")
                assertContains(text, "clips=0")

                out.write(renderChangePictureAttributes(PictureId, XRender.CPClipMask to 0))
                out.flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""clipMask":"none"""")
                }
                json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"clipMask":"none"""")
                assertContains(json, """"clipRectangles":0""")
                text = httpGet(server.localPort, "/text.txt")
                assertFalse(text.contains("clipMask=0x${MaskPixmapId.toString(16)}"))
                assertContains(text, "clips=0")

                out.write(renderChangePictureAttributes(PictureId, XRender.CPClipMask to MaskPixmapId))
                out.write(
                    renderSetPictureClipRectangles(
                        PictureId,
                        rectangles = listOf(XRectangleCommand(1, 1, 2, 2)),
                    ),
                )
                out.flush()

                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""clipRectangles":1""")
                }
                json = httpGet(server.localPort, "/state.json")
                assertContains(json, """"clipMask":"none"""")
                assertContains(json, """"clipRectangles":1""")
                text = httpGet(server.localPort, "/text.txt")
                assertFalse(text.contains("clipMask=0x${MaskPixmapId.toString(16)}"))
                assertContains(text, "clips=1")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER FillRectangles honors destination clip mask pixels and origin`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 2, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 2))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 2, alphas = byteArrayOf(0xff.toByte(), 0x00, 0x00, 0xff.toByte())))
                out.write(
                    renderChangePictureAttributes(
                        PictureId,
                        XRender.CPClipXOrigin to 1,
                        XRender.CPClipYOrigin to 0,
                        XRender.CPClipMask to MaskPixmapId,
                    ),
                )
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 2, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 2))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER FillRectangles retains destination clip mask after FreePixmap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 1))
                out.write(putImage8Request(MaskPixmapId, width = 3, height = 1, alphas = byteArrayOf(0xff.toByte(), 0x00, 0xff.toByte())))
                out.write(renderChangePictureAttributes(PictureId, XRender.CPClipMask to MaskPixmapId))
                out.write(freePixmapRequest(MaskPixmapId))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER FillRectangles keeps clip mask stable across pixmap id reuse`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 1))
                out.write(putImage8Request(MaskPixmapId, width = 3, height = 1, alphas = byteArrayOf(0xff.toByte(), 0x00, 0x00)))
                out.write(renderChangePictureAttributes(PictureId, XRender.CPClipMask to MaskPixmapId))
                out.write(freePixmapRequest(MaskPixmapId))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 1))
                out.write(putImage8OnlyRequest(MaskPixmapId, width = 3, height = 1, alphas = byteArrayOf(0x00, 0xff.toByte(), 0xff.toByte())))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CreatePicture retains initial destination clip mask after FreePixmap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(SolidPictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(SolidPictureId, x = 0, y = 0, width = 3, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 1))
                out.write(putImage8Request(MaskPixmapId, width = 3, height = 1, alphas = byteArrayOf(0xff.toByte(), 0x00, 0xff.toByte())))
                out.write(renderCreatePictureWithAttributes(PictureId, WindowId, XRender.Rgb24Format, XRender.CPClipMask to MaskPixmapId))
                out.write(freePixmapRequest(MaskPixmapId))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Composite honors destination clip mask pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 1))
                out.write(putImage8Request(MaskPixmapId, width = 3, height = 1, alphas = byteArrayOf(0xff.toByte(), 0x00, 0xff.toByte())))
                out.write(renderChangePictureAttributes(PictureId, XRender.CPClipMask to MaskPixmapId))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, width = 3, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Composite honors source clip mask pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 3, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 1))
                out.write(putImage8Request(MaskPixmapId, width = 3, height = 1, alphas = byteArrayOf(0xff.toByte(), 0x00, 0xff.toByte())))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureAttributes(SolidPictureId, XRender.CPClipMask to MaskPixmapId))
                out.write(renderComposite(SolidPictureId, PictureId, width = 3, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER QueryPictIndexValues and QueryDithers return empty modeled tables and validate errors`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val missingDrawable = WindowId + 0x5a00
                val missingFormat = XRender.Argb32Format + 0x5a00
                out.write(createWindowRequest(WindowId))
                out.write(renderQueryPictIndexValues(missingFormat))
                out.write(renderQueryPictIndexValues(XRender.Argb32Format))
                out.write(renderQueryDithers(WindowId))
                out.write(renderQueryDithers(missingDrawable))
                out.flush()

                assertError(socket.getInputStream(), error = XRender.PictFormatError, badValue = missingFormat, sequence = 2, minorOpcode = 2)
                assertError(socket.getInputStream(), error = 8, badValue = XRender.Argb32Format, sequence = 3, minorOpcode = 2)
                val dithers = readReply(socket.getInputStream())
                assertEquals(0, u32le(dithers, 4))
                assertEquals(4, u16le(dithers, 2))
                assertEquals(0, u32le(dithers, 8))
                assertEquals(32, dithers.size)
                assertError(socket.getInputStream(), error = 9, badValue = missingDrawable, sequence = 5, minorOpcode = 3)

                waitUntil {
                    httpGet(server.localPort, "/text.txt").contains("QueryDithers")
                }
                val text = httpGet(server.localPort, "/text.txt")
                assertContains(text, "QueryPictIndexValues")
                assertContains(text, "format=0x${XRender.Argb32Format.toString(16)}")
                assertContains(text, "drawable=0x${WindowId.toString(16)}")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER QueryPictIndexValues and QueryDithers report Length errors for malformed request sizes`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XRender.MajorOpcode, 2, ByteArray(0)))
                out.write(request(XRender.MajorOpcode, 3, ByteArray(8)))
                out.write(renderQueryDithers(X11Ids.RootWindow))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = 2)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 2, minorOpcode = 3)
                val recovered = readReply(socket.getInputStream())
                assertEquals(3, u16le(recovered, 2))
                assertEquals(0, u32le(recovered, 8))
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
    fun `RENDER composite applies source alpha map pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 1, alphas = byteArrayOf(0x00, 0x80.toByte())))
                out.write(
                    renderChangePictureAttributes(
                        PixmapPictureId,
                        XRender.CPAlphaMap to MaskPictureId,
                        XRender.CPAlphaXOrigin to -1,
                    ),
                )
                out.write(renderComposite(PixmapPictureId, PictureId, width = 2, height = 1, operation = XRender.OpOver, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                val first = pixelAt(image, imageWidth = 2, x = 0, y = 0)
                val second = pixelAt(image, imageWidth = 2, x = 1, y = 0)
                assertEquals(0xff80_007f.toInt(), first, "first=${first.toUInt().toString(16)}")
                assertEquals(0xff00_00ff.toInt(), second, "second=${second.toUInt().toString(16)}")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite applies solid source alpha map pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 1, alphas = byteArrayOf(0x80.toByte(), 0x00)))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderChangePictureAttributes(
                        SolidPictureId,
                        XRender.CPAlphaMap to MaskPictureId,
                    ),
                )
                out.write(renderComposite(SolidPictureId, PictureId, width = 2, height = 1, operation = XRender.OpOver, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                val first = pixelAt(image, imageWidth = 2, x = 0, y = 0)
                val second = pixelAt(image, imageWidth = 2, x = 1, y = 0)
                assertEquals(0xff80_007f.toInt(), first, "first=${first.toUInt().toString(16)}")
                assertEquals(0xff00_00ff.toInt(), second, "second=${second.toUInt().toString(16)}")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER alpha map ignores repeat and clips to pixmap geometry`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderChangePictureAttributes(MaskPictureId, XRender.CPRepeat to XRender.RepeatPad))
                out.write(renderChangePictureAttributes(PixmapPictureId, XRender.CPAlphaMap to MaskPictureId))
                out.write(renderComposite(PixmapPictureId, PictureId, width = 2, height = 1, operation = XRender.OpOver, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff80_007f.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER clipped source clips alpha map misses`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(PixmapPictureId, rectangles = listOf(XRectangleCommand(0, 0, 2, 1))))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderChangePictureAttributes(PixmapPictureId, XRender.CPAlphaMap to MaskPictureId))
                out.write(renderComposite(PixmapPictureId, PictureId, width = 2, height = 1, operation = XRender.OpSrc, destinationX = 0, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x80ff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER ChangePicture rejects solid alpha map picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureAttributes(PictureId, XRender.CPAlphaMap to SolidPictureId))
                out.flush()

                assertError(socket.getInputStream(), error = 8, badValue = SolidPictureId, sequence = 4, minorOpcode = 5)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite snapshots same drawable source with alpha map`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 2, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 3, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 3, height = 1, alphas = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte())))
                out.write(
                    renderChangePictureAttributes(
                        PictureId,
                        XRender.CPAlphaMap to MaskPictureId,
                    ),
                )
                out.write(renderSetPictureClipRectangles(PictureId, rectangles = listOf(XRectangleCommand(0, 0, 3, 1))))
                out.write(renderComposite(PictureId, PictureId, width = 2, height = 1, operation = XRender.OpSrc, sourceX = 0, destinationX = 1, destinationY = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite applies mask picture alpha map pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 1, alphas = byteArrayOf(0xff.toByte(), 0xff.toByte())))
                out.write(createPixmapRequest(PixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.A8Format))
                out.write(putImage8OnlyRequest(PixmapId, width = 2, height = 1, alphas = byteArrayOf(0x80.toByte(), 0x00)))
                out.write(
                    renderChangePictureAttributes(
                        MaskPictureId,
                        XRender.CPAlphaMap to PixmapPictureId,
                    ),
                )
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpOver))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                val first = pixelAt(image, imageWidth = 2, x = 0, y = 0)
                val second = pixelAt(image, imageWidth = 2, x = 1, y = 0)
                assertEquals(0xff80_007f.toInt(), first, "first=${first.toUInt().toString(16)}")
                assertEquals(0xff00_00ff.toInt(), second, "second=${second.toUInt().toString(16)}")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER mask alpha map miss clips OpSrc pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 1, alphas = byteArrayOf(0xff.toByte(), 0xff.toByte())))
                out.write(createPixmapRequest(PixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.A8Format))
                out.write(putImage8OnlyRequest(PixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderChangePictureAttributes(MaskPictureId, XRender.CPAlphaMap to PixmapPictureId))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x80ff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER mask alpha map does not extend mask drawable geometry`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0xff.toByte())))
                out.write(createPixmapRequest(PixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.A8Format))
                out.write(putImage8OnlyRequest(PixmapId, width = 2, height = 1, alphas = byteArrayOf(0xff.toByte(), 0xff.toByte())))
                out.write(renderChangePictureAttributes(MaskPictureId, XRender.CPAlphaMap to PixmapPictureId))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER bilinear mask alpha map does not extend mask drawable geometry`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0xff.toByte())))
                out.write(renderSetPictureFilter(MaskPictureId, "bilinear", values = emptyList()))
                out.write(createPixmapRequest(PixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.A8Format))
                out.write(putImage8OnlyRequest(PixmapId, width = 2, height = 1, alphas = byteArrayOf(0xff.toByte(), 0xff.toByte())))
                out.write(renderChangePictureAttributes(MaskPictureId, XRender.CPAlphaMap to PixmapPictureId))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER component alpha map miss clips OpSrc pixels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 32, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(MaskPictureId, componentAlpha = true))
                out.write(createPixmapRequest(PixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.A8Format))
                out.write(putImage8Request(PixmapId, width = 1, height = 1, alphas = byteArrayOf(0xff.toByte())))
                out.write(renderChangePictureAttributes(MaskPictureId, XRender.CPAlphaMap to PixmapPictureId))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER component alpha mask preserves transparent source misses`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 32, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(MaskPictureId, componentAlpha = true))
                out.write(renderComposite(PixmapPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER component alpha mask preserves transparent mask misses`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(MaskPictureId, componentAlpha = true))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0x00ff_0000, pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER triangles clip source alpha map misses`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 2, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureAttributes(SolidPictureId, XRender.CPAlphaMap to MaskPictureId))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(6 to 4, 14 to 4, 6 to 12)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 16, height = 16))
                out.flush()

                val image = readReply(socket.getInputStream())
                val unchanged = pixelAt(image, imageWidth = 16, x = 15, y = 15)
                assertEquals(0x80ff_0000.toInt(), pixelAt(image, imageWidth = 16, x = 6, y = 4))
                assertEquals(unchanged, pixelAt(image, imageWidth = 16, x = 7, y = 4))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite applies component alpha mask channels`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(MaskPictureId, componentAlpha = true))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
                waitUntil {
                    httpGet(server.localPort, "/state.json").contains(""""componentAlpha":true""")
                }
                assertContains(httpGet(server.localPort, "/text.txt"), "componentAlpha=true")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER component alpha repeated self composite snapshots source before writes`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 2, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePicture(PictureId, repeat = XRender.RepeatPad))
                out.write(createPixmapRequest(MaskPixmapId, depth = 32, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 2, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(MaskPictureId, componentAlpha = true))
                out.write(renderComposite(PictureId, PictureId, mask = MaskPictureId, sourceX = 0, sourceY = 0, destinationX = 1, destinationY = 0, width = 2, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER component alpha OpSrc gray mask preserves source color like scalar mask`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(createPixmapRequest(MaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8080, green = 0x8080, blue = 0x8080, alpha = 0x8080))
                out.write(renderChangePictureComponentAlpha(MaskPictureId, componentAlpha = true))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 1, height = 1, operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x80ff_0000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite honors mask picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 2, height = 1, alphas = byteArrayOf(0xff.toByte(), 0xff.toByte())))
                out.write(renderSetPictureClipRectangles(MaskPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite applies solid mask alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(MaskPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0x8000))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff80_007f.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
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
    fun `RENDER OpDisjointClear clears with composite masks and fill rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointClear, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointClear, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff, operation = XRender.OpDisjointClear))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointClear, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x7f00_007f, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x7f00_0000, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointClear solid composite returns clear result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointClear,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0000, image.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointClear clears with composite masks and fill rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointClear, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointClear, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff, operation = XRender.OpConjointClear))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointClear, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x7f00_007f, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x7f00_0000, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointClear solid composite returns clear result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpConjointClear,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0000, image.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointSrc replaces destination with source and masks`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointSrc, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointSrc, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointSrc))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpSrc))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointSrc, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x80ff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff80_0000.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointSrc solid composite returns source result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointSrc,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), image.pixels.single())
        assertEquals(0xffff_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointSrc replaces destination with source and masks`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointSrc, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointSrc, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpConjointSrc))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointSrc, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x80ff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff80_0000.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointSrc solid composite returns source result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpConjointSrc,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), image.pixels.single())
        assertEquals(0xffff_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointDst preserves destination for composite masks and fill rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointDst, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0xff.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointDst, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointDst))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointDst, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointDst solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointDst,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_00ff.toInt(), image.pixels.single())
        assertEquals(0x8000_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointDst preserves destination for composite masks and fill rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointDst, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0xff.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointDst, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpConjointDst))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointDst, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointDst solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointDst,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_00ff.toInt(), image.pixels.single())
        assertEquals(0x8000_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointOver composites source over disjoint destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointOver, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointOver, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpDisjointOver))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointOver, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xc080_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8040_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xc080_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xc040_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointOver solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointOver,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xc080_00ff.toInt(), image.pixels.single())
        assertEquals(0xc080_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointOver,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xc040_00ff.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xc040_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val fractionalDestinationFactorImage = state.composite(
            operation = XRender.OpDisjointOver,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff80_00fd.toInt(), fractionalDestinationFactorImage.pixels.single())
        assertEquals(0xff80_00fd.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x0000))),
        )

        val transparentDestinationImage = state.composite(
            operation = XRender.OpDisjointOver,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8080_00ff.toInt(), transparentDestinationImage.pixels.single())
        assertEquals(0x8080_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointOver composites source over conjoint destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointOver, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointOver, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpConjointOver))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointOver, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4040_0000, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8040_0040.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointOver solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        val lowAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putPicture(
            XPicture(
                id = lowAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointOver,
            source = state.picture(lowAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8040_0040.toInt(), image.pixels.single())
        assertEquals(0x8040_0040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val saturatedSourceImage = state.composite(
            operation = XRender.OpConjointOver,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8080_0000.toInt(), saturatedSourceImage.pixels.single())
        assertEquals(0x8080_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointOver,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8040_0040.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8040_0040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointOverReverse composites source excess over destination`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointOverReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointOverReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpConjointOverReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointOverReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8040_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4000_00ff, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8040_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointOverReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        val lowAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putPicture(
            XPicture(
                id = lowAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val unchangedImage = state.composite(
            operation = XRender.OpConjointOverReverse,
            source = state.picture(lowAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_00ff.toInt(), unchangedImage.pixels.single())
        assertEquals(0x8000_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val sourceExcessImage = state.composite(
            operation = XRender.OpConjointOverReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8040_00ff.toInt(), sourceExcessImage.pixels.single())
        assertEquals(0x8040_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointOverReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8040_00ff.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8040_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointIn composites source limited by destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointIn, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x40)))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointIn, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000, operation = XRender.OpConjointIn))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointIn, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4040_0000, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x4040_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointIn solid composite returns source image and stores limited pixels`() {
        val state = X11State(width = 16, height = 16)
        val lowAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putPicture(
            XPicture(
                id = lowAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointIn,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), image.pixels.single())
        assertEquals(0x8080_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val lowAlphaImage = state.composite(
            operation = XRender.OpConjointIn,
            source = state.picture(lowAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x40ff_0000, lowAlphaImage.pixels.single())
        assertEquals(0x4040_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointInReverse composites destination limited by source alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointInReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x40)))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointInReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000, operation = XRender.OpConjointInReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointInReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8000_0080.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4000_0040, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x4000_0040, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_0040.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointInReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        val lowAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putPicture(
            XPicture(
                id = lowAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointInReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0080.toInt(), image.pixels.single())
        assertEquals(0x8000_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val lowAlphaImage = state.composite(
            operation = XRender.OpConjointInReverse,
            source = state.picture(lowAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x4000_0040, lowAlphaImage.pixels.single())
        assertEquals(0x4000_0040, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointInReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0040.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8000_0040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointIn composites source only where alpha ranges overlap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 5, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 5, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))
                out.write(renderFillRectangles(PixmapPictureId, x = 4, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointIn, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointIn, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointIn))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointIn, destinationX = 3, destinationY = 0, width = 1, height = 1))

                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointIn, destinationX = 4, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 5, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 5, x = 0, y = 0))
                assertEquals(0x0101_0000, pixelAt(image, imageWidth = 5, x = 1, y = 0))
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 5, x = 2, y = 0))
                assertEquals(0x8001_0000.toInt(), pixelAt(image, imageWidth = 5, x = 3, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 5, x = 4, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointIn solid composite returns source image and stores blended pixels`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointIn,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), image.pixels.single())
        assertEquals(0x8080_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointIn,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8001_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0x8000))),
        )

        val noOverlapImage = state.composite(
            operation = XRender.OpDisjointIn,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), noOverlapImage.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointInReverse composites destination only where alpha ranges overlap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 5, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 5, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))
                out.write(renderFillRectangles(PixmapPictureId, x = 4, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointInReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointInReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointInReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0x8000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointInReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))

                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointInReverse, destinationX = 4, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 5, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8000_0080.toInt(), pixelAt(image, imageWidth = 5, x = 0, y = 0))
                assertEquals(0x0100_0001, pixelAt(image, imageWidth = 5, x = 1, y = 0))
                assertEquals(0x8000_0080.toInt(), pixelAt(image, imageWidth = 5, x = 2, y = 0))
                assertEquals(0x8000_0001.toInt(), pixelAt(image, imageWidth = 5, x = 3, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 5, x = 4, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointInReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointInReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0080.toInt(), image.pixels.single())
        assertEquals(0x8000_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x8000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointInReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0001.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8000_0001.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x8000, alpha = 0x8000))),
        )

        val noOverlapImage = state.composite(
            operation = XRender.OpDisjointInReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0000, noOverlapImage.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointOut composites source excess outside destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointOut, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointOut, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpConjointOut))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointOut, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x4040_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x4040_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x4000_0000, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointOut solid composite returns source image and stores source excess`() {
        val state = X11State(width = 16, height = 16)
        val lowAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putPicture(
            XPicture(
                id = lowAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointOut,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x80ff_0000.toInt(), image.pixels.single())
        assertEquals(0x4040_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val lowAlphaImage = state.composite(
            operation = XRender.OpConjointOut,
            source = state.picture(lowAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x40ff_0000, lowAlphaImage.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointOut,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x80ff_0000.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x4000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointOutReverse composites destination excess outside source alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointOutReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointOutReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000, operation = XRender.OpConjointOutReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointOutReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x4000_0040, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x6000_0060, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x4000_0040, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x4000_0070, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointOutReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        val highAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putPicture(
            XPicture(
                id = highAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointOutReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x4000_0040, image.pixels.single())
        assertEquals(0x4000_0040, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val highAlphaImage = state.composite(
            operation = XRender.OpConjointOutReverse,
            source = state.picture(highAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0000, highAlphaImage.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointOutReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x4000_0070, componentAlphaImage.pixels.single())
        assertEquals(0x4000_0070, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointAtop combines conjoint source and destination contributions`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointAtop, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointAtop, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000, operation = XRender.OpConjointAtop))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointAtop, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8040_0040.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8020_0060.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8040_0040.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_0070.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointAtop solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        val highAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putPicture(
            XPicture(
                id = highAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointAtop,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8040_0040.toInt(), image.pixels.single())
        assertEquals(0x8040_0040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val highAlphaImage = state.composite(
            operation = XRender.OpConjointAtop,
            source = state.picture(highAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x4040_0000, highAlphaImage.pixels.single())
        assertEquals(0x4040_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointAtop,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0070.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8000_0070.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointAtopReverse combines conjoint source excess and destination limit`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointAtopReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointAtopReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpConjointAtopReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointAtopReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8040_0040.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4000_0040, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8040_0040.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_0020.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointAtopReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        val lowAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putPicture(
            XPicture(
                id = lowAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x4000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8040_0040.toInt(), image.pixels.single())
        assertEquals(0x8040_0040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val lowAlphaImage = state.composite(
            operation = XRender.OpConjointAtopReverse,
            source = state.picture(lowAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x4000_0040, lowAlphaImage.pixels.single())
        assertEquals(0x4000_0040, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x4000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0020.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8000_0020.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpConjointXor combines only conjoint alpha excess`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpConjointXor, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpConjointXor, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpConjointXor))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpConjointXor, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x7f00_0080, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpConjointXor solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        val equalAlphaSolidPictureId = 0x0020_1007
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putPicture(
            XPicture(
                id = equalAlphaSolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpConjointXor,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x7f7f_0000, image.pixels.single())
        assertEquals(0x7f7f_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val equalAlphaImage = state.composite(
            operation = XRender.OpConjointXor,
            source = state.picture(equalAlphaSolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0000, equalAlphaImage.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpConjointXor,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x7f00_0080, componentAlphaImage.pixels.single())
        assertEquals(0x7f00_0080, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointOut limits source to remaining destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 5, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 5, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))
                out.write(renderFillRectangles(PixmapPictureId, x = 4, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointOut, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointOut, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointOut))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointOut, destinationX = 3, destinationY = 0, width = 1, height = 1))

                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointOut, destinationX = 4, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 5, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 5, x = 0, y = 0))
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 5, x = 1, y = 0))
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 5, x = 2, y = 0))
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 5, x = 3, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 5, x = 4, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointOut solid composite returns source image and stores limited source`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointOut,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), image.pixels.single())
        assertEquals(0x7f7f_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointOut,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x7f7f_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val noRemainingAlphaImage = state.composite(
            operation = XRender.OpDisjointOut,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), noRemainingAlphaImage.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointOutReverse limits destination to inverse source alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointOutReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointOutReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointOutReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0x8000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointOutReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x7f00_007f, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x0000_007f, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointOutReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointOutReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0000, image.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x8000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointOutReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_007f, componentAlphaImage.pixels.single())
        assertEquals(0x0000_007f, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x0000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val zeroSourceAlphaImage = state.composite(
            operation = XRender.OpDisjointOutReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0080.toInt(), zeroSourceAlphaImage.pixels.single())
        assertEquals(0x8000_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointAtop combines disjoint source and destination contributions`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointAtop, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointAtop, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointAtop))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointAtop, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8001_007f.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8001_0080.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointAtop solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointAtop,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8080_0000.toInt(), image.pixels.single())
        assertEquals(0x8080_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointAtop,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8001_0080.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8001_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x0000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val zeroSourceAlphaImage = state.composite(
            operation = XRender.OpDisjointAtop,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0080.toInt(), zeroSourceAlphaImage.pixels.single())
        assertEquals(0x8000_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x7f00, green = 0x0000, blue = 0x0000, alpha = 0xfe00),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0200, green = 0x0000, blue = 0x0000, alpha = 0x0200))),
        )

        val roundingImage = state.composite(
            operation = XRender.OpDisjointAtop,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0200_0000, roundingImage.pixels.single())
        assertEquals(0x0200_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointAtopReverse combines disjoint source and destination contributions`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointAtopReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointAtopReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointAtopReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointAtopReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff7f_0080.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x807f_0001.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff7f_0080.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff7f_0000.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointAtopReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff7f_0080.toInt(), image.pixels.single())
        assertEquals(0xff7f_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff7f_0000.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff7f_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x0000))),
        )

        val zeroDestinationAlphaImage = state.composite(
            operation = XRender.OpDisjointAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_0000.toInt(), zeroDestinationAlphaImage.pixels.single())
        assertEquals(0xffff_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x7f00, green = 0x0000, blue = 0x0000, alpha = 0x0200),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0200, green = 0x0000, blue = 0x0000, alpha = 0xfe00))),
        )

        val roundingImage = state.composite(
            operation = XRender.OpDisjointAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0200_0000, roundingImage.pixels.single())
        assertEquals(0x0200_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointXor combines disjoint source and destination contributions`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointXor, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointXor, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDisjointXor))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointXor, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xfe7f_007f.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x7f7f_0080, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointXor solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointXor,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x7f7f_0000, image.pixels.single())
        assertEquals(0x7f7f_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpDisjointXor,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x7f7f_0080, componentAlphaImage.pixels.single())
        assertEquals(0x7f7f_0080, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x0000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val zeroSourceAlphaImage = state.composite(
            operation = XRender.OpDisjointXor,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0080.toInt(), zeroSourceAlphaImage.pixels.single())
        assertEquals(0x8000_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x7f00, green = 0x0000, blue = 0x0000, alpha = 0x0100),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0200, green = 0x0000, blue = 0x0000, alpha = 0x0100))),
        )

        val roundingImage = state.composite(
            operation = XRender.OpDisjointXor,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0200_0000, roundingImage.pixels.single())
        assertEquals(0x0200_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendMultiply darkens overlapping source and destination colors`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendMultiply, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendMultiply, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpBlendMultiply))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendMultiply, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff7f_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xc040_007f.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff7f_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff40_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendMultiply solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpBlendMultiply,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff7f_0000.toInt(), image.pixels.single())
        assertEquals(0xff7f_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendMultiply,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff40_00ff.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff40_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendScreen brightens overlapping source and destination colors`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendScreen, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendScreen, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpBlendScreen))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendScreen, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xc080_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xffff_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff80_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendScreen solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpBlendScreen,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_00ff.toInt(), image.pixels.single())
        assertEquals(0xffff_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendScreen,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff80_00ff.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff80_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendOverlay switches multiply and screen by destination channel`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendOverlay, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendOverlay, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpBlendOverlay))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendOverlay, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff7f_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xc040_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff7f_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff40_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendOverlay solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpBlendOverlay,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff7f_00ff.toInt(), image.pixels.single())
        assertEquals(0xff7f_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendOverlay,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff40_00ff.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff40_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendDarken selects the darker source and destination contributions`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendDarken, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendDarken, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpBlendDarken))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendDarken, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_007f.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff00_00bf.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_007f.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendDarken solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendDarken,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff00_007f.toInt(), image.pixels.single())
        assertEquals(0xff00_007f.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendDarken,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff00_00ff.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff00_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendLighten selects the lighter source and destination contributions`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendLighten, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendLighten, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpBlendLighten))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendLighten, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff80_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff40_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff80_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff40_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendLighten solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendLighten,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff80_00ff.toInt(), image.pixels.single())
        assertEquals(0xff80_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendLighten,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff40_00ff.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff40_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendColorDodge brightens destination by inverted source`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x4000, green = 0x4000, blue = 0x4000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendColorDodge, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendColorDodge, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpBlendColorDodge))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendColorDodge, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff81_4040.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff60_4040.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff81_4040.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff60_4040.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendColorDodge solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x4000, green = 0x4000, blue = 0x4000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendColorDodge,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff81_4040.toInt(), image.pixels.single())
        assertEquals(0xff81_4040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x4000, green = 0x4000, blue = 0x4000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendColorDodge,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff60_4040.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff60_4040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendColorBurn darkens destination by source`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0xc000, green = 0xc000, blue = 0xc000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendColorBurn, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendColorBurn, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpBlendColorBurn))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendColorBurn, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff81_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xffa1_6060.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff81_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xffa1_c0c0.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendColorBurn solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0xc000, green = 0xc000, blue = 0xc000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendColorBurn,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff81_0000.toInt(), image.pixels.single())
        assertEquals(0xff81_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0xc000, green = 0xc000, blue = 0xc000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendColorBurn,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffa1_c0c0.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xffa1_c0c0.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))),
        )

        val whiteDestinationImage = state.composite(
            operation = XRender.OpBlendColorBurn,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_ffff.toInt(), whiteDestinationImage.pixels.single())
        assertEquals(0xffff_ffff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendHardLight switches multiply and screen by source channel`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0x4000, green = 0xc000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendHardLight, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendHardLight, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0x4000, green = 0xc000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpBlendHardLight))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendHardLight, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff40_c000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff60_a040.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff40_c000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff60_a080.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendHardLight solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x4000, green = 0xc000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendHardLight,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff40_c000.toInt(), image.pixels.single())
        assertEquals(0xff40_c000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendHardLight,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff60_a080.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff60_a080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendSoftLight applies source driven soft contrast`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0x4000, green = 0xc000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendSoftLight, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendSoftLight, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0x4000, green = 0xc000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpBlendSoftLight))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendSoftLight, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff60_9b40.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff70_8d60.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff60_9b40.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff70_8d80.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendSoftLight solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x4000, green = 0xc000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendSoftLight,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff60_9b40.toInt(), image.pixels.single())
        assertEquals(0xff60_9b40.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendSoftLight,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff70_8d80.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff70_8d80.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendDifference computes absolute source destination delta`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x2000, green = 0x8000, blue = 0xe000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xc000, green = 0x4000, blue = 0xa000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendDifference, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendDifference, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xc000, green = 0x4000, blue = 0xa000, alpha = 0xffff, operation = XRender.OpBlendDifference))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendDifference, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffa0_4040.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff60_6090.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xffa0_4040.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff60_60e0.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendDifference solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xc000, green = 0x4000, blue = 0xa000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0x8000, blue = 0xe000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendDifference,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffa0_4040.toInt(), image.pixels.single())
        assertEquals(0xffa0_4040.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0x8000, blue = 0xe000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendDifference,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff60_60e0.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff60_60e0.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendExclusion blends source and destination with reduced contrast`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x2000, green = 0x8000, blue = 0xe000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xc000, green = 0x4000, blue = 0xa000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendExclusion, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendExclusion, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xc000, green = 0x4000, blue = 0xa000, alpha = 0xffff, operation = XRender.OpBlendExclusion))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendExclusion, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffb0_8067.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff68_80a3.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xffb0_8067.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff68_80e0.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendExclusion solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xc000, green = 0x4000, blue = 0xa000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0x8000, blue = 0xe000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendExclusion,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffb0_8067.toInt(), image.pixels.single())
        assertEquals(0xffb0_8067.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0x8000, blue = 0xe000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendExclusion,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff68_80e0.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff68_80e0.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendHSLHue keeps destination luminosity and saturation with source hue`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendHSLHue, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendHSLHue, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff, operation = XRender.OpBlendHSLHue))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendHSLHue, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xfff9_5959.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff8d_8d6d.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xfff9_5959.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff8d_8d6d.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendHSLHue solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendHSLHue,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xfff9_5959.toInt(), image.pixels.single())
        assertEquals(0xfff9_5959.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendHSLHue,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff8d_8d6d.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff8d_8d6d.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val grayHueImage = state.composite(
            operation = XRender.OpBlendHSLHue,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff89_8989.toInt(), grayHueImage.pixels.single())
        assertEquals(0xff89_8989.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendHSLSaturation keeps destination hue and luminosity with source saturation`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendHSLSaturation, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendHSLSaturation, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff, operation = XRender.OpBlendHSLSaturation))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendHSLSaturation, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff0b_cb7e.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff16_c67f.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff0b_cb7e.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff16_c67f.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendHSLSaturation solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendHSLSaturation,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff0b_cb7e.toInt(), image.pixels.single())
        assertEquals(0xff0b_cb7e.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendHSLSaturation,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff16_c67f.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff16_c67f.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val graySaturationImage = state.composite(
            operation = XRender.OpBlendHSLSaturation,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff89_8989.toInt(), graySaturationImage.pixels.single())
        assertEquals(0xff89_8989.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendHSLColor keeps destination luminosity with source hue and saturation`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendHSLColor, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendHSLColor, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff, operation = XRender.OpBlendHSLColor))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendHSLColor, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_5656.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff90_8b6b.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xffff_5656.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff90_8b6b.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendHSLColor solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendHSLColor,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffff_5656.toInt(), image.pixels.single())
        assertEquals(0xffff_5656.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendHSLColor,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff90_8b6b.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff90_8b6b.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val grayColorImage = state.composite(
            operation = XRender.OpBlendHSLColor,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff89_8989.toInt(), grayColorImage.pixels.single())
        assertEquals(0xff89_8989.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpBlendHSLLuminosity keeps destination hue and saturation with source luminosity`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpBlendHSLLuminosity, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpBlendHSLLuminosity, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff, operation = XRender.OpBlendHSLLuminosity))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpBlendHSLLuminosity, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_8952.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xff10_a469.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_8952.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff10_a469.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpBlendHSLLuminosity solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xe000, green = 0x2000, blue = 0x2000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val image = state.composite(
            operation = XRender.OpBlendHSLLuminosity,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff00_8952.toInt(), image.pixels.single())
        assertEquals(0xff00_8952.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpBlendHSLLuminosity,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff10_a469.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff10_a469.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0x8000, green = 0x8000, blue = 0x8000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x2000, green = 0xc000, blue = 0x8000, alpha = 0xffff))),
        )

        val grayLuminosityImage = state.composite(
            operation = XRender.OpBlendHSLLuminosity,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff17_b777.toInt(), grayLuminosityImage.pixels.single())
        assertEquals(0xff17_b777.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER composite honors source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(PixmapPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite samples transparent black outside non repeated source bounds`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER identity self composite samples from original drawable snapshot`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 2, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderComposite(PictureId, PictureId, operation = XRender.OpSrc, sourceX = 0, sourceY = 0, destinationX = 1, destinationY = 0, width = 2, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 3, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 3, x = 1, y = 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 3, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER clipped self composite samples from original drawable snapshot`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val sourcePictureId = PictureId + 0x80
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreatePicture(sourcePictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 2, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 3, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(sourcePictureId, rectangles = listOf(XRectangleCommand(0, 0, 2, 1))))
                out.write(renderComposite(sourcePictureId, PictureId, operation = XRender.OpSrc, sourceX = 0, sourceY = 0, destinationX = 1, destinationY = 0, width = 3, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xffff_ff00.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
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
    fun `RENDER OpSaturate limits source contribution to remaining destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpSaturate, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpSaturate, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpSaturate))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpSaturate, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffbf_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xc080_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xc080_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff80_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpSaturate solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val image = state.composite(
            operation = XRender.OpSaturate,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffbf_00ff.toInt(), image.pixels.single())
        assertEquals(0xffbf_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(0x00ff_0000)),
        )

        val alphaZeroRedComponentMaskImage = state.composite(
            operation = XRender.OpSaturate,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x40bf_00ff, alphaZeroRedComponentMaskImage.pixels.single())
        assertEquals(0x40bf_00ff, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val fullAlphaDestinationImage = state.composite(
            operation = XRender.OpSaturate,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff00_00ff.toInt(), fullAlphaDestinationImage.pixels.single())
        assertEquals(0xff00_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDisjointOverReverse matches saturate source contribution`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDisjointOverReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDisjointOverReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0x8000, operation = XRender.OpDisjointOverReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDisjointOverReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffbf_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xc080_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xc080_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff80_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDisjointOverReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )

        val image = state.composite(
            operation = XRender.OpDisjointOverReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xffbf_00ff.toInt(), image.pixels.single())
        assertEquals(0xffbf_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x4000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(0x00ff_0000)),
        )

        val alphaZeroRedComponentMaskImage = state.composite(
            operation = XRender.OpDisjointOverReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x40bf_00ff, alphaZeroRedComponentMaskImage.pixels.single())
        assertEquals(0x40bf_00ff, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))),
        )

        val fullAlphaDestinationImage = state.composite(
            operation = XRender.OpDisjointOverReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff00_00ff.toInt(), fullAlphaDestinationImage.pixels.single())
        assertEquals(0xff00_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpDst preserves destination for composite masks and fill rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpDst, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0xff.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpDst, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpDst))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpDst, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpDst solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpDst,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_00ff.toInt(), image.pixels.single())
        assertEquals(0x8000_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpOverReverse composites destination over source`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpOverReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpOverReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpOverReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpOverReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff7f_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0xc040_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff7f_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff40_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpOverReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpOverReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff7f_00ff.toInt(), image.pixels.single())
        assertEquals(0xff7f_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpIn multiplies source by destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpIn, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpIn, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpIn))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpIn, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4040_0000, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8040_0000.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpOut multiplies source by inverse destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpOut, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpOut, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpOut))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpOut, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4040_0000, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x7f40_0000, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpInReverse multiplies destination by source alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpInReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpInReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpInReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpInReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8000_0080.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4000_0040, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8000_0080.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8000_0000.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpInReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpInReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0080.toInt(), image.pixels.single())
        assertEquals(0x8000_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpInReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_0000.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8000_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpOutReverse multiplies destination by inverse source alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpOutReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpOutReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpOutReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpOutReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x4000_0040, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x0000_0080, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpOutReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpOutReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0000, image.pixels.single())
        assertEquals(0x0000_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpOutReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x0000_0080, componentAlphaImage.pixels.single())
        assertEquals(0x0000_0080, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpAtop composites source over destination while preserving destination alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpAtop, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpAtop, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpAtop))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpAtop, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8040_0040.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x8080_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x8040_0080.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpAtop solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpAtop,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8080_0000.toInt(), image.pixels.single())
        assertEquals(0x8080_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpAtop,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8040_0080.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0x8040_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpAtopReverse composites destination over source while preserving source alpha`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpAtopReverse, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpAtopReverse, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpAtopReverse))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpAtopReverse, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff7f_0080.toInt(), pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x8040_0040.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff7f_0080.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0xff40_0000.toInt(), pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpAtopReverse solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff7f_0080.toInt(), image.pixels.single())
        assertEquals(0xff7f_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpAtopReverse,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0xff40_0000.toInt(), componentAlphaImage.pixels.single())
        assertEquals(0xff40_0000.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
    }

    @Test
    fun `RENDER OpXor composites source and destination outside their overlap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 32, width = 4, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000, operation = XRender.OpSrc))

                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, operation = XRender.OpXor, destinationX = 0, destinationY = 0, width = 1, height = 1))

                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 1, height = 1))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(putImage8Request(MaskPixmapId, width = 1, height = 1, alphas = byteArrayOf(0x80.toByte())))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = MaskPictureId, operation = XRender.OpXor, destinationX = 1, destinationY = 0, width = 1, height = 1))

                out.write(renderFillRectangles(PixmapPictureId, x = 2, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff, operation = XRender.OpXor))

                out.write(createPixmapRequest(ComponentMaskPixmapId, depth = 32, width = 1, height = 1))
                out.write(renderCreatePicture(ComponentMaskPictureId, ComponentMaskPixmapId, XRender.Argb32Format))
                out.write(renderFillRectangles(ComponentMaskPictureId, x = 0, y = 0, width = 1, height = 1, red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderChangePictureComponentAlpha(ComponentMaskPictureId, componentAlpha = true))
                out.write(renderComposite(SolidPictureId, PixmapPictureId, mask = ComponentMaskPictureId, operation = XRender.OpXor, destinationX = 3, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(PixmapId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 0, y = 0))
                assertEquals(0x7f40_0040, pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0x7f7f_0000, pixelAt(image, imageWidth = 4, x = 2, y = 0))
                assertEquals(0x7f40_0080, pixelAt(image, imageWidth = 4, x = 3, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER OpXor solid composite returns destination result image`() {
        val state = X11State(width = 16, height = 16)
        state.putPixmap(XPixmap(PixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(XPicture(PixmapPictureId, drawableId = PixmapId, format = XRender.Argb32Format))
        state.putPixmap(XPixmap(MaskPixmapId, width = 1, height = 1, depth = 8))
        state.putPicture(XPicture(MaskPictureId, drawableId = MaskPixmapId, format = XRender.A8Format))
        state.putPixmap(XPixmap(ComponentMaskPixmapId, width = 1, height = 1, depth = 32))
        state.putPicture(
            XPicture(
                id = ComponentMaskPictureId,
                drawableId = ComponentMaskPixmapId,
                format = XRender.Argb32Format,
                componentAlpha = true,
            ),
        )
        state.putPicture(
            XPicture(
                id = SolidPictureId,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
            ),
        )
        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )

        val image = state.composite(
            operation = XRender.OpXor,
            source = state.picture(SolidPictureId)!!,
            mask = null,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x7f7f_0000, image.pixels.single())
        assertEquals(0x7f7f_0000, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x8000, green = 0x0000, blue = 0x0000, alpha = 0xffff))),
        )

        val componentAlphaImage = state.composite(
            operation = XRender.OpXor,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x7f40_0080, componentAlphaImage.pixels.single())
        assertEquals(0x7f40_0080, state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = MaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(0x0000_0000)),
        )

        val zeroMaskImage = state.composite(
            operation = XRender.OpXor,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(MaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_00ff.toInt(), zeroMaskImage.pixels.single())
        assertEquals(0x8000_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(0x0000_0000)),
        )

        val zeroComponentMaskImage = state.composite(
            operation = XRender.OpXor,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x8000_00ff.toInt(), zeroComponentMaskImage.pixels.single())
        assertEquals(0x8000_00ff.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())

        state.putImage(
            drawableId = PixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(XRender.argb32Pixel(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0x8000))),
        )
        state.putImage(
            drawableId = ComponentMaskPixmapId,
            x = 0,
            y = 0,
            image = XImagePixels(1, 1, intArrayOf(0x00ff_0000)),
        )

        val alphaZeroRedComponentMaskImage = state.composite(
            operation = XRender.OpXor,
            source = state.picture(SolidPictureId)!!,
            mask = state.picture(ComponentMaskPictureId)!!,
            destination = state.picture(PixmapPictureId)!!,
            sourceX = 0,
            sourceY = 0,
            maskX = 0,
            maskY = 0,
            destinationX = 0,
            destinationY = 0,
            width = 1,
            height = 1,
        )!!

        assertEquals(0x807f_0080.toInt(), alphaZeroRedComponentMaskImage.pixels.single())
        assertEquals(0x807f_0080.toInt(), state.getImage(PixmapId, x = 0, y = 0, width = 1, height = 1)!!.pixels.single())
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
    fun `RENDER clipped transformed source drawable miss paints transparent`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 1, height = 1))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(PixmapPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
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
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 0, width = 1, height = 1))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0x0000_0000, pixelAt(image, imageWidth = 1, x = 0, y = 0))
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
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 5, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
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
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 2, width = 1, height = 1))
                out.write(renderChangePicture(PixmapPictureId, repeat = XRender.RepeatPad))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 3, width = 1, height = 1))
                out.write(renderChangePicture(PixmapPictureId, repeat = XRender.RepeatNormal))
                out.write(renderComposite(PixmapPictureId, PictureId, operation = XRender.OpSrc, destinationX = 0, destinationY = 4, width = 1, height = 1))

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
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 5))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff80_8000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 0))
                assertEquals(0xff80_007f.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 1))
                assertEquals(0x8000_fe00.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 2))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 3))
                assertEquals(0xff80_8000.toInt(), pixelAt(image, imageWidth = 1, x = 0, y = 4))
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
                assertContains(json, """"drawingCommands":[{"drawable":"0x${WindowId.toUInt().toString(16)}","kind":"FillRectangle","framebufferBacked":true,"sourceDrawable":null""")
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
                assertContains(json, """"drawingCommands":[{"drawable":"0x${WindowId.toUInt().toString(16)}","kind":"FillRectangle","framebufferBacked":true,"sourceDrawable":null""")
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
    fun `RENDER Trapezoids validates request framing resources and mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingSource = SolidPictureId + 0x100
                val missingDestination = PictureId + 0x100
                val unknownMaskFormat = 0x7fff_1010
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 12, height = 10, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderTrapezoidsRaw(trapezoidsHeader(SolidPictureId, PictureId).copyOf(16)))
                out.write(renderTrapezoidsRaw(trapezoidsHeader(SolidPictureId, PictureId).copyOf(24)))
                out.write(renderTrapezoidsRaw(trapezoidsHeader(SolidPictureId, PictureId, operation = XRender.OpBlendMaximum + 1)))
                out.write(renderTrapezoidsRaw(trapezoidsHeader(missingSource, PictureId)))
                out.write(renderTrapezoidsRaw(trapezoidsHeader(SolidPictureId, missingDestination)))
                out.write(renderTrapezoidsRaw(trapezoidsHeader(SolidPictureId, PictureId, maskFormat = unknownMaskFormat)))
                out.write(renderTrapezoidsRaw(trapezoidsHeader(SolidPictureId, PictureId, maskFormat = XRender.Rgb24Format)))
                out.write(renderTrapezoids(SolidPictureId, PictureId, x = 3, y = 2, width = 5, height = 4, maskFormat = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 10))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 10)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = 10)
                assertError(socket.getInputStream(), error = 2, badValue = XRender.OpBlendMaximum + 1, sequence = 7, minorOpcode = 10)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingSource, sequence = 8, minorOpcode = 10)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingDestination, sequence = 9, minorOpcode = 10)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, badValue = unknownMaskFormat, sequence = 10, minorOpcode = 10)
                assertError(socket.getInputStream(), error = 8, badValue = XRender.Rgb24Format, sequence = 11, minorOpcode = 10)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 12, x = 4, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 2, y = 2))
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
    fun `RENDER trapezoids honor source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 12, height = 10, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(SolidPictureId, rectangles = listOf(XRectangleCommand(0, 0, 2, 2))))
                out.write(renderTrapezoids(SolidPictureId, PictureId, x = 5, y = 4, width = 6, height = 4))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 10))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 12, x = 5, y = 4))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 8, y = 6))
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
    fun `RENDER AddTraps validates request framing and alpha mask targets`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingPicture = MaskPictureId + 1
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 16, height = 12, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 1, width = 16, height = 12))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A1Format))
                out.write(renderFillRectangles(MaskPictureId, x = 0, y = 0, width = 16, height = 12, red = 0x0000, green = 0x0000, blue = 0x0000, alpha = 0x0000))
                out.write(renderAddTrapsRaw(ByteArray(4).also { put32le(it, 0, MaskPictureId) }))
                out.write(renderAddTrapsRaw(ByteArray(8).also { put32le(it, 0, missingPicture) }))
                out.write(ByteArray(12).also {
                    put32le(it, 0, MaskPictureId)
                    put16le(it, 4, 0)
                    put16le(it, 6, 0)
                    put32le(it, 8, 0)
                }.let(::renderAddTrapsRaw))
                out.write(renderAddTrapsRaw(ByteArray(8).also { put32le(it, 0, PictureId) }))
                out.write(renderAddTraps(MaskPictureId, xOffset = 0, yOffset = 0, x = 4, y = 3, width = 5, height = 4))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderComposite(SolidPictureId, PictureId, mask = MaskPictureId, width = 16, height = 12))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 16, height = 12))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 7, minorOpcode = 32)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingPicture, sequence = 8, minorOpcode = 32)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = 32)
                assertError(socket.getInputStream(), error = 8, badValue = PictureId, sequence = 10, minorOpcode = 32)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 16, x = 6, y = 5))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 16, x = 3, y = 3))
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
    fun `RENDER ColorTrapezoids interpolates span colors into destination framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 16, height = 16, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderColorTrapezoids(
                        PictureId,
                        left = 2,
                        right = 10,
                        top = 2,
                        bottom = 10,
                        topLeft = RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                        topRight = RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                        bottomLeftColor = RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        bottomRightColor = RenderColor(red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 16, height = 16))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff91_5050.toInt(), pixelAt(image, imageWidth = 16, x = 4, y = 4))
                assertEquals(0xff62_cf50.toInt(), pixelAt(image, imageWidth = 16, x = 8, y = 4))
                assertEquals(0xff62_50cf.toInt(), pixelAt(image, imageWidth = 16, x = 4, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 16, x = 11, y = 4))
                assertContains(httpGet(server.localPort, "/text.txt"), "ColorTrapezoids")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER ColorTrapezoids validates request framing operator and destination picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingDestination = PictureId + 0x200
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 12, height = 10, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderColorTrapezoidsRaw(colorTrapezoidsHeader(PictureId).copyOf(4)))
                out.write(renderColorTrapezoidsRaw(colorTrapezoidsHeader(PictureId).copyOf(16)))
                out.write(renderColorTrapezoidsRaw(colorTrapezoidsHeader(PictureId, operation = XRender.OpBlendMaximum + 1)))
                out.write(renderColorTrapezoidsRaw(colorTrapezoidsHeader(missingDestination)))
                out.write(
                    renderColorTrapezoids(
                        PictureId,
                        left = 3,
                        right = 8,
                        top = 2,
                        bottom = 7,
                        topLeft = RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                        topRight = RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                        bottomLeftColor = RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                        bottomRightColor = RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 10))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 14)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 14)
                assertError(socket.getInputStream(), error = 2, badValue = XRender.OpBlendMaximum + 1, sequence = 6, minorOpcode = 14)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingDestination, sequence = 7, minorOpcode = 14)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 12, x = 4, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 2, y = 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER ColorTrapezoids honors independent top and bottom spans`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                val green = RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff)
                val white = RenderColor(red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff)
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 14, height = 12, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderColorTrapezoids(
                        PictureId,
                        left = 2,
                        right = 10,
                        top = 2,
                        bottom = 10,
                        bottomLeft = 4,
                        bottomRight = 8,
                        topLeft = green,
                        topRight = green,
                        bottomLeftColor = green,
                        bottomRightColor = white,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 14, height = 12))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff26_ff26.toInt(), pixelAt(image, imageWidth = 14, x = 4, y = 8))
                assertEquals(0xffa9_ffa9.toInt(), pixelAt(image, imageWidth = 14, x = 7, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 14, x = 2, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 14, x = 9, y = 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Transform maps source quad into destination quad`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 2))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 1, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 1, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 12, height = 10, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderTransform(
                        PixmapPictureId,
                        PictureId,
                        sourceQuad = listOf(0 to 0, 2 to 0, 2 to 2, 0 to 2),
                        destinationQuad = listOf(4 to 3, 8 to 3, 8 to 7, 4 to 7),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 10))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 12, x = 4, y = 3))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 12, x = 7, y = 3))
                assertEquals(0xffffff00.toInt(), pixelAt(image, imageWidth = 12, x = 4, y = 6))
                assertEquals(0xffffffff.toInt(), pixelAt(image, imageWidth = 12, x = 7, y = 6))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 3, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 8, y = 3))
                assertContains(httpGet(server.localPort, "/text.txt"), "Transform")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Transform honors source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(createPixmapRequest(PixmapId, depth = 24, width = 2, height = 2))
                out.write(renderCreatePicture(PixmapPictureId, PixmapId, XRender.Rgb24Format))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 0, y = 1, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PixmapPictureId, x = 1, y = 1, width = 1, height = 1, red = 0xffff, green = 0xffff, blue = 0xffff, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(PixmapPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 12, height = 10, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderTransform(
                        PixmapPictureId,
                        PictureId,
                        sourceQuad = listOf(0 to 0, 2 to 0, 2 to 2, 0 to 2),
                        destinationQuad = listOf(4 to 3, 8 to 3, 8 to 7, 4 to 7),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 10))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 12, x = 4, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 7, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 4, y = 6))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Transform clips solid source pictures by source coordinates`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(SolidPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 8, height = 8, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderTransform(
                        SolidPictureId,
                        PictureId,
                        sourceQuad = listOf(0 to 0, 2 to 0, 2 to 2, 0 to 2),
                        destinationQuad = listOf(2 to 2, 6 to 2, 6 to 6, 2 to 6),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 8, x = 2, y = 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 8, x = 5, y = 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Transform snapshots self source before painting destination`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 4, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 1, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderTransform(
                        PictureId,
                        PictureId,
                        sourceQuad = listOf(0 to 0, 2 to 0, 2 to 1, 0 to 1),
                        destinationQuad = listOf(1 to 0, 3 to 0, 3 to 1, 1 to 1),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Transform validates framing operator and pictures`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingSource = SolidPictureId + 0x300
                val missingDestination = PictureId + 0x300
                val validBody = renderTransformBody(SolidPictureId, PictureId)
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 8, height = 8, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderTransformRaw(validBody.copyOf(76)))
                out.write(renderTransformRaw(validBody.copyOf(84)))
                out.write(renderTransformRaw(renderTransformBody(SolidPictureId, PictureId, operation = XRender.OpBlendMaximum + 1)))
                out.write(renderTransformRaw(renderTransformBody(missingSource, PictureId)))
                out.write(renderTransformRaw(renderTransformBody(SolidPictureId, missingDestination)))
                out.write(renderTransformRaw(renderTransformBody(SolidPictureId, PictureId, filter = 1)))
                out.write(
                    renderTransformRaw(
                        renderTransformBody(
                            SolidPictureId,
                            PictureId,
                            destinationQuad = listOf(2 to 2, 5 to 2, 4 to 5, 2 to 5),
                        ),
                    ),
                )
                out.write(renderTransform(SolidPictureId, PictureId, destinationQuad = listOf(2 to 2, 5 to 2, 5 to 5, 2 to 5)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 16)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = 16)
                assertError(socket.getInputStream(), error = 2, badValue = XRender.OpBlendMaximum + 1, sequence = 7, minorOpcode = 16)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingSource, sequence = 8, minorOpcode = 16)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingDestination, sequence = 9, minorOpcode = 16)
                assertError(socket.getInputStream(), error = 2, badValue = 1, sequence = 10, minorOpcode = 16)
                assertError(socket.getInputStream(), error = 2, badValue = 0, sequence = 11, minorOpcode = 16)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 8, x = 3, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 8, x = 1, y = 1))
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
    fun `RENDER triangles honor source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 32, height = 24, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(SolidPictureId, rectangles = listOf(XRectangleCommand(0, 0, 3, 3))))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(8 to 6, 24 to 6, 8 to 20)))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 32, height = 24))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 32, x = 10, y = 8))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 32, x = 12, y = 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER Triangles validates request framing resources and mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingSource = SolidPictureId + 0x200
                val missingDestination = PictureId + 0x200
                val unknownMaskFormat = 0x7fff_2010
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 12, height = 10, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderTrianglesRaw(trianglesHeader(SolidPictureId, PictureId).copyOf(16)))
                out.write(renderTrianglesRaw(trianglesHeader(SolidPictureId, PictureId).copyOf(28)))
                out.write(renderTrianglesRaw(trianglesHeader(SolidPictureId, PictureId, operation = XRender.OpBlendMaximum + 1)))
                out.write(renderTrianglesRaw(trianglesHeader(missingSource, PictureId)))
                out.write(renderTrianglesRaw(trianglesHeader(SolidPictureId, missingDestination)))
                out.write(renderTrianglesRaw(trianglesHeader(SolidPictureId, PictureId, maskFormat = unknownMaskFormat)))
                out.write(renderTrianglesRaw(trianglesHeader(SolidPictureId, PictureId, maskFormat = XRender.Rgb24Format)))
                out.write(renderTriangles(SolidPictureId, PictureId, points = listOf(3 to 2, 8 to 2, 3 to 7), maskFormat = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 10))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 11)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = 11)
                assertError(socket.getInputStream(), error = 2, badValue = XRender.OpBlendMaximum + 1, sequence = 7, minorOpcode = 11)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingSource, sequence = 8, minorOpcode = 11)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingDestination, sequence = 9, minorOpcode = 11)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, badValue = unknownMaskFormat, sequence = 10, minorOpcode = 11)
                assertError(socket.getInputStream(), error = 8, badValue = XRender.Rgb24Format, sequence = 11, minorOpcode = 11)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 12, x = 4, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 2, y = 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER ColorTriangles interpolates vertex colors into destination framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 16, height = 16, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(
                    renderColorTriangles(
                        PictureId,
                        points = listOf(0 to 0, 12 to 0, 0 to 12),
                        colors = listOf(
                            RenderColor(red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff),
                            RenderColor(red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff),
                        ),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 16, height = 16))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffbf_2020.toInt(), pixelAt(image, imageWidth = 16, x = 1, y = 1))
                assertEquals(0xff40_6060.toInt(), pixelAt(image, imageWidth = 16, x = 4, y = 4))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 16, x = 13, y = 1))
                assertContains(httpGet(server.localPort, "/text.txt"), "ColorTriangles")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER ColorTriangles validates request framing operator and destination picture`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingDestination = PictureId + 0x200
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 12, height = 10, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderColorTrianglesRaw(colorTrianglesHeader(PictureId).copyOf(4)))
                out.write(renderColorTrianglesRaw(colorTrianglesHeader(PictureId).copyOf(16)))
                out.write(renderColorTrianglesRaw(colorTrianglesHeader(PictureId, operation = XRender.OpBlendMaximum + 1)))
                out.write(renderColorTrianglesRaw(colorTrianglesHeader(missingDestination)))
                out.write(
                    renderColorTriangles(
                        PictureId,
                        points = listOf(2 to 2, 8 to 2, 2 to 8),
                        colors = List(3) { RenderColor(red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff) },
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 12, height = 10))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 15)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 15)
                assertError(socket.getInputStream(), error = 2, badValue = XRender.OpBlendMaximum + 1, sequence = 6, minorOpcode = 15)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingDestination, sequence = 7, minorOpcode = 15)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 12, x = 3, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 12, x = 1, y = 1))
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
    fun `RENDER TriStrip validates request framing resources and mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingSource = SolidPictureId + 0x300
                val missingDestination = PictureId + 0x300
                val unknownMaskFormat = 0x7fff_3010
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 16, height = 12, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriangleMeshRaw(12, triangleMeshHeader(SolidPictureId, PictureId).copyOf(16)))
                out.write(renderTriangleMeshRaw(12, triangleMeshHeader(SolidPictureId, PictureId).copyOf(24)))
                out.write(renderTriangleMeshRaw(12, triangleMeshHeader(SolidPictureId, PictureId, operation = XRender.OpBlendMaximum + 1)))
                out.write(renderTriangleMeshRaw(12, triangleMeshHeader(missingSource, PictureId)))
                out.write(renderTriangleMeshRaw(12, triangleMeshHeader(SolidPictureId, missingDestination)))
                out.write(renderTriangleMeshRaw(12, triangleMeshHeader(SolidPictureId, PictureId, maskFormat = unknownMaskFormat)))
                out.write(renderTriangleMeshRaw(12, triangleMeshHeader(SolidPictureId, PictureId, maskFormat = XRender.Rgb24Format)))
                out.write(renderTriStrip(SolidPictureId, PictureId, points = listOf(3 to 2, 10 to 2, 3 to 8, 10 to 8), maskFormat = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 16, height = 12))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 12)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = 12)
                assertError(socket.getInputStream(), error = 2, badValue = XRender.OpBlendMaximum + 1, sequence = 7, minorOpcode = 12)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingSource, sequence = 8, minorOpcode = 12)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingDestination, sequence = 9, minorOpcode = 12)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, badValue = unknownMaskFormat, sequence = 10, minorOpcode = 12)
                assertError(socket.getInputStream(), error = 8, badValue = XRender.Rgb24Format, sequence = 11, minorOpcode = 12)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 16, x = 4, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 16, x = 2, y = 2))
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
    fun `RENDER TriFan validates shared request framing and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val unknownMaskFormat = 0x7fff_4010
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 16, height = 12, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(renderTriangleMeshRaw(13, triangleMeshHeader(SolidPictureId, PictureId).copyOf(16)))
                out.write(renderTriangleMeshRaw(13, triangleMeshHeader(SolidPictureId, PictureId, maskFormat = unknownMaskFormat)))
                out.write(renderTriFan(SolidPictureId, PictureId, points = listOf(5 to 2, 3 to 8, 11 to 8), maskFormat = 0))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 16, height = 12))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 13)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, badValue = unknownMaskFormat, sequence = 6, minorOpcode = 13)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), pixelAt(image, imageWidth = 16, x = 5, y = 4))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 16, x = 2, y = 2))
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
                val input = socket.getInputStream()
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

                val image = readReply(input)
                assertEquals(0xffcc_0033.toInt(), pixelAt(image, imageWidth = 24, x = 4, y = 3))
                assertEquals(0xff80_0080.toInt(), pixelAt(image, imageWidth = 24, x = 10, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 24, x = 3, y = 3))
                out.write(mapWindowRequest(WindowId))
                out.flush()
                val expose = input.readExactly(32)
                assertEquals(12, expose[0].toInt() and 0xff)
                assertEquals(WindowId, u32le(expose, 4))
                val svg = httpGet(server.localPort, "/screen.svg")
                assertContains(svg, """class="framebuffer-image"""")
                assertFalse(svg.contains("RENDER.CompositeGlyphs32"), "Framebuffer-backed glyph rendering must not be double-rendered as a synthetic SVG text overlay")
                assertContains(httpGet(server.localPort, "/text.txt"), "CompositeGlyphs32")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER composite glyphs honor source picture clip rectangles`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 2, height = 1, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddA8Glyph(GlyphSetId, GlyphId, width = 2, height = 1, xOff = 2, alphas = ByteArray(2) { 0xff.toByte() }))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderSetPictureClipRectangles(SolidPictureId, rectangles = listOf(XRectangleCommand(0, 0, 1, 1))))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 0,
                        deltaY = 0,
                        glyphIds = listOf(GlyphId),
                        operation = XRender.OpSrc,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 1))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 0, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 0))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CompositeGlyphs applies glyph element deltaY once`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 8, height = 8, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddA8Glyph(GlyphSetId, GlyphId, width = 2, height = 2, xOff = 2, alphas = ByteArray(4) { 0xff.toByte() }))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 3,
                        deltaY = 2,
                        glyphIds = listOf(GlyphId),
                        operation = XRender.OpSrc,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 8, height = 8))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 8, x = 3, y = 2))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 8, x = 4, y = 3))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 8, x = 3, y = 4))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CompositeGlyphs rejects missing glyph and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingGlyph = GlyphId + 1
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 4, height = 5, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddA8Glyph(GlyphSetId, GlyphId, width = 2, height = 2, xOff = 2, alphas = ByteArray(4) { 0xff.toByte() }))
                out.write(renderCompositeGlyphs32(SolidPictureId, PictureId, GlyphSetId, sourceX = 0, sourceY = 0, deltaX = 1, deltaY = 1, glyphIds = listOf(GlyphId, missingGlyph), operation = XRender.OpSrc))
                out.write(renderCompositeGlyphs32(SolidPictureId, PictureId, GlyphSetId, sourceX = 0, sourceY = 0, deltaX = 1, deltaY = 3, glyphIds = listOf(GlyphId), operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 5))
                out.flush()

                assertError(socket.getInputStream(), error = XRender.GlyphError, badValue = missingGlyph, sequence = 7, minorOpcode = 25)
                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 0))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 3))
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

    @Test
    fun `RENDER CreateGlyphSet rejects duplicate resource id without replacing existing glyph set`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 4, height = 2, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddA8Glyph(GlyphSetId, GlyphId, width = 4, height = 2, xOff = 4, alphas = ByteArray(8) { 0xff.toByte() }))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A1Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 0,
                        deltaY = 0,
                        glyphIds = listOf(GlyphId),
                        operation = XRender.OpSrc,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(GlyphSetId, u32le(duplicateError, 4))
                assertEquals(17, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER ReferenceGlyphSet rejects duplicate resource id without replacing existing resource`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderReferenceGlyphSet(WindowId, GlyphSetId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 1, height = 1, red = 0x0000, green = 0xffff, blue = 0x0000, alpha = 0xffff))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 1, height = 1))
                out.flush()

                val duplicateError = socket.getInputStream().readExactly(32)
                assertEquals(0, duplicateError[0].toInt())
                assertEquals(14, duplicateError[1].toInt() and 0xff)
                assertEquals(WindowId, u32le(duplicateError, 4))
                assertEquals(18, u16le(duplicateError, 8))
                assertEquals(XRender.MajorOpcode, duplicateError[10].toInt() and 0xff)

                val image = readReply(socket.getInputStream())
                assertEquals(0xff00_ff00.toInt(), u32le(image, 32))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER glyph set lifecycle validates framing formats and resources`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val referencedGlyphSet = GlyphSetId + 1
                val missingGlyphSet = GlyphSetId + 0x100
                val unknownFormat = 0x7fff_5010
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateGlyphSetRaw(ByteArray(4)))
                out.write(renderCreateGlyphSetRaw(ByteArray(12).also {
                    put32le(it, 0, GlyphSetId)
                    put32le(it, 4, XRender.A8Format)
                }))
                out.write(renderCreateGlyphSet(GlyphSetId, unknownFormat))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderReferenceGlyphSetRaw(ByteArray(4)))
                out.write(renderReferenceGlyphSetRaw(ByteArray(12).also {
                    put32le(it, 0, referencedGlyphSet)
                    put32le(it, 4, GlyphSetId)
                }))
                out.write(renderReferenceGlyphSetRaw(ByteArray(20).also {
                    put32le(it, 0, referencedGlyphSet)
                    put32le(it, 4, GlyphSetId)
                }))
                out.write(renderReferenceGlyphSet(referencedGlyphSet, missingGlyphSet))
                out.write(renderReferenceGlyphSet(referencedGlyphSet, GlyphSetId))
                out.write(renderFreeGlyphSetRaw(ByteArray(0)))
                out.write(renderFreeGlyphSetRaw(ByteArray(8).also {
                    put32le(it, 0, missingGlyphSet)
                }))
                out.write(renderFreeGlyphSet(missingGlyphSet))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderAddA8Glyph(GlyphSetId, GlyphId, width = 2, height = 2, xOff = 2, alphas = ByteArray(4) { 0xff.toByte() }))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = referencedGlyphSet,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 1,
                        deltaY = 1,
                        glyphIds = listOf(GlyphId),
                        operation = XRender.OpSrc,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = 17)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 17)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, badValue = unknownFormat, sequence = 5, minorOpcode = 17)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 7, minorOpcode = 18)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 8, minorOpcode = 18)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = 18)
                assertError(socket.getInputStream(), error = XRender.GlyphSetError, badValue = missingGlyphSet, sequence = 10, minorOpcode = 18)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 12, minorOpcode = 19)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 13, minorOpcode = 19)
                assertError(socket.getInputStream(), error = XRender.GlyphSetError, badValue = missingGlyphSet, sequence = 14, minorOpcode = 19)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER AddGlyphs and FreeGlyphs validate glyph tables and resources`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingGlyphSet = GlyphSetId + 0x200
                val missingGlyph = GlyphId + 1
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddGlyphsRaw(ByteArray(4)))
                out.write(renderAddGlyphsRaw(ByteArray(8).also {
                    put32le(it, 0, GlyphSetId)
                    put32le(it, 4, 1)
                }))
                out.write(renderAddGlyphsRaw(renderAddA8GlyphBody(missingGlyphSet, GlyphId, width = 1, height = 1, xOff = 1, alphas = byteArrayOf(0xff.toByte()))))
                out.write(renderAddGlyphsRaw(renderAddA8GlyphBody(GlyphSetId, GlyphId, width = 2, height = 2, xOff = 2, alphas = ByteArray(4) { 0xff.toByte() }).copyOf(28)))
                out.write(renderAddGlyphsRaw(renderAddA8GlyphBody(GlyphSetId, GlyphId, width = 2, height = 2, xOff = 2, alphas = ByteArray(4) { 0xff.toByte() })))
                out.write(renderFreeGlyphsRaw(ByteArray(0)))
                out.write(renderFreeGlyphs(missingGlyphSet, emptyList()))
                out.write(renderFreeGlyphs(GlyphSetId, listOf(missingGlyph)))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 1,
                        deltaY = 1,
                        glyphIds = listOf(GlyphId),
                        operation = XRender.OpSrc,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = 20)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 20)
                assertError(socket.getInputStream(), error = XRender.GlyphSetError, badValue = missingGlyphSet, sequence = 6, minorOpcode = 20)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 7, minorOpcode = 20)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = 22)
                assertError(socket.getInputStream(), error = XRender.GlyphSetError, badValue = missingGlyphSet, sequence = 10, minorOpcode = 22)
                assertError(socket.getInputStream(), error = XRender.GlyphError, badValue = missingGlyph, sequence = 11, minorOpcode = 22)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER AddGlyphsFromPicture samples source picture into glyph mask`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 4, height = 4, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 2))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(
                    putImage8Request(
                        MaskPixmapId,
                        width = 2,
                        height = 2,
                        alphas = byteArrayOf(
                            0xff.toByte(),
                            0x00,
                            0x00,
                            0xff.toByte(),
                        ),
                    ),
                )
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddGlyphsFromPicture(GlyphSetId, MaskPictureId, GlyphId, width = 2, height = 2, xOff = 2))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 1,
                        deltaY = 1,
                        glyphIds = listOf(GlyphId),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 2))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 2, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 2))
                assertContains(httpGet(server.localPort, "/text.txt"), "AddGlyphsFromPicture")
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER AddGlyphsFromPicture parses multiple glyph ids and source offsets`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val secondGlyph = GlyphId + 1
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderFillRectangles(PictureId, x = 0, y = 0, width = 5, height = 3, red = 0x0000, green = 0x0000, blue = 0xffff, alpha = 0xffff))
                out.write(createPixmapRequest(MaskPixmapId, depth = 8, width = 2, height = 2))
                out.write(renderCreatePicture(MaskPictureId, MaskPixmapId, XRender.A8Format))
                out.write(
                    putImage8Request(
                        MaskPixmapId,
                        width = 2,
                        height = 2,
                        alphas = byteArrayOf(
                            0xff.toByte(),
                            0x00,
                            0x00,
                            0xff.toByte(),
                        ),
                    ),
                )
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(
                    renderAddGlyphsFromPicture(
                        GlyphSetId,
                        MaskPictureId,
                        listOf(
                            RenderPictureGlyph(glyphId = GlyphId, width = 1, height = 1, xOff = 2),
                            RenderPictureGlyph(glyphId = secondGlyph, width = 1, height = 1, sourceX = 1, sourceY = 1),
                        ),
                    ),
                )
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 1,
                        deltaY = 1,
                        glyphIds = listOf(GlyphId, secondGlyph),
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 5, height = 3))
                out.flush()

                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 5, x = 1, y = 1))
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 5, x = 3, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 5, x = 2, y = 1))
                assertEquals(0xff00_00ff.toInt(), pixelAt(image, imageWidth = 5, x = 1, y = 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER AddGlyphsFromPicture validates framing resources and recovers stream`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingGlyphSet = GlyphSetId + 0x400
                val missingPicture = SolidPictureId + 0x400
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddGlyphsFromPictureRaw(ByteArray(8)))
                out.write(renderAddGlyphsFromPictureRaw(renderAddGlyphsFromPictureBody(GlyphSetId, SolidPictureId, GlyphId, width = 1, height = 1).copyOf(28)))
                out.write(renderAddGlyphsFromPicture(missingGlyphSet, SolidPictureId, GlyphId, width = 1, height = 1))
                out.write(renderAddGlyphsFromPicture(GlyphSetId, missingPicture, GlyphId, width = 1, height = 1))
                out.write(renderAddGlyphsFromPicture(GlyphSetId, SolidPictureId, GlyphId, width = 0xffff, height = 0xffff))
                out.write(
                    renderAddGlyphsFromPicture(
                        GlyphSetId,
                        SolidPictureId,
                        listOf(
                            RenderPictureGlyph(glyphId = GlyphId, width = 4096, height = 4096),
                            RenderPictureGlyph(glyphId = GlyphId + 1, width = 4096, height = 4096),
                        ),
                    ),
                )
                out.write(renderAddGlyphsFromPicture(GlyphSetId, SolidPictureId, GlyphId, width = 1, height = 1))
                out.write(
                    renderCompositeGlyphs32(
                        source = SolidPictureId,
                        destination = PictureId,
                        glyphSet = GlyphSetId,
                        sourceX = 0,
                        sourceY = 0,
                        deltaX = 1,
                        deltaY = 1,
                        glyphIds = listOf(GlyphId),
                        operation = XRender.OpSrc,
                    ),
                )
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 2, height = 2))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = 21)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = 21)
                assertError(socket.getInputStream(), error = XRender.GlyphSetError, badValue = missingGlyphSet, sequence = 7, minorOpcode = 21)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingPicture, sequence = 8, minorOpcode = 21)
                assertError(socket.getInputStream(), error = 11, badValue = 0, sequence = 9, minorOpcode = 21)
                assertError(socket.getInputStream(), error = 11, badValue = 0, sequence = 10, minorOpcode = 21)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 2, x = 1, y = 1))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RENDER CompositeGlyphs validates framing resources and mask format`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missingSource = SolidPictureId + 0x300
                val missingDestination = PictureId + 0x300
                val missingGlyphSet = GlyphSetId + 0x300
                val unknownMaskFormat = 0x7fff_7010
                out.write(createWindowRequest(WindowId))
                out.write(renderCreatePicture(PictureId, WindowId, XRender.Rgb24Format))
                out.write(renderCreateSolidFill(SolidPictureId, red = 0xffff, green = 0x0000, blue = 0x0000, alpha = 0xffff))
                out.write(renderCreateGlyphSet(GlyphSetId, XRender.A8Format))
                out.write(renderAddA8Glyph(GlyphSetId, GlyphId, width = 2, height = 2, xOff = 2, alphas = ByteArray(4) { 0xff.toByte() }))
                out.write(renderCompositeGlyphsRaw(25, compositeGlyphsHeader(SolidPictureId, PictureId, GlyphSetId).copyOf(20)))
                out.write(renderCompositeGlyphs32(SolidPictureId, PictureId, GlyphSetId, sourceX = 0, sourceY = 0, deltaX = 1, deltaY = 1, glyphIds = listOf(GlyphId), operation = XRender.OpBlendMaximum + 1))
                out.write(renderCompositeGlyphsRaw(25, compositeGlyphsHeader(missingSource, PictureId, GlyphSetId)))
                out.write(renderCompositeGlyphsRaw(25, compositeGlyphsHeader(SolidPictureId, missingDestination, GlyphSetId)))
                out.write(renderCompositeGlyphsRaw(25, compositeGlyphsHeader(SolidPictureId, PictureId, GlyphSetId, maskFormat = unknownMaskFormat)))
                out.write(renderCompositeGlyphsRaw(25, compositeGlyphsHeader(SolidPictureId, PictureId, missingGlyphSet)))
                out.write(renderCompositeGlyphsRaw(25, compositeGlyphsHeader(SolidPictureId, PictureId, GlyphSetId) + ByteArray(4)))
                out.write(
                    renderCompositeGlyphsRaw(
                        25,
                        compositeGlyphsHeader(SolidPictureId, PictureId, GlyphSetId) + ByteArray(12).also {
                            it[0] = 0xff.toByte()
                            put32le(it, 8, missingGlyphSet)
                        },
                    ),
                )
                out.write(
                    renderCompositeGlyphsRaw(
                        25,
                        compositeGlyphsHeader(SolidPictureId, PictureId, GlyphSetId) + ByteArray(8).also {
                            it[0] = 1
                        },
                    ),
                )
                out.write(renderCompositeGlyphs32(SolidPictureId, PictureId, GlyphSetId, sourceX = 0, sourceY = 0, deltaX = 1, deltaY = 1, glyphIds = listOf(GlyphId), operation = XRender.OpSrc))
                out.write(getImageRequest(WindowId, x = 0, y = 0, width = 4, height = 4))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = 25)
                assertError(socket.getInputStream(), error = 2, badValue = XRender.OpBlendMaximum + 1, sequence = 7, minorOpcode = 25)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingSource, sequence = 8, minorOpcode = 25)
                assertError(socket.getInputStream(), error = XRender.PictureError, badValue = missingDestination, sequence = 9, minorOpcode = 25)
                assertError(socket.getInputStream(), error = XRender.PictFormatError, badValue = unknownMaskFormat, sequence = 10, minorOpcode = 25)
                assertError(socket.getInputStream(), error = XRender.GlyphSetError, badValue = missingGlyphSet, sequence = 11, minorOpcode = 25)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 12, minorOpcode = 25)
                assertError(socket.getInputStream(), error = XRender.GlyphSetError, badValue = missingGlyphSet, sequence = 13, minorOpcode = 25)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 14, minorOpcode = 25)
                val image = readReply(socket.getInputStream())
                assertEquals(0xffff_0000.toInt(), pixelAt(image, imageWidth = 4, x = 1, y = 1))
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

    private fun xfixesQueryVersionRequest(major: Int, minor: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, major)
        put32le(body, 4, minor)
        return request(XFixes.MajorOpcode, XFixes.QueryVersion, body)
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

    private fun createInputOnlyWindowRequest(id: Int): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, id)
        put32le(body, 4, X11Ids.RootWindow)
        put16le(body, 8, 10)
        put16le(body, 10, 20)
        put16le(body, 12, 100)
        put16le(body, 14, 80)
        put16le(body, 18, 2)
        put32le(body, 20, X11Ids.RootVisual)
        return request(1, 0, body)
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

    private fun mapWindowRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(8, 0, body)
    }

    private fun renderCreatePicture(picture: Int, drawable: Int, format: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, picture)
        put32le(body, 4, drawable)
        put32le(body, 8, format)
        return request(XRender.MajorOpcode, 4, body)
    }

    private fun renderCreatePictureWithAttributes(picture: Int, drawable: Int, format: Int, vararg attributes: Pair<Int, Int>): ByteArray {
        val sortedAttributes = attributes.sortedBy { it.first.countTrailingZeroBits() }
        val valueMask = sortedAttributes.fold(0) { mask, attribute -> mask or attribute.first }
        val body = ByteArray(16 + sortedAttributes.size * 4)
        put32le(body, 0, picture)
        put32le(body, 4, drawable)
        put32le(body, 8, format)
        put32le(body, 12, valueMask)
        sortedAttributes.forEachIndexed { index, attribute ->
            put32le(body, 16 + index * 4, attribute.second)
        }
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
        return renderCreateSolidFillRaw(body)
    }

    private fun renderCreateSolidFillRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 33, body)

    private fun renderCreateCursor(cursor: Int, source: Int, x: Int = 0, y: Int = 0): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, cursor)
        put32le(body, 4, source)
        put16le(body, 8, x)
        put16le(body, 10, y)
        return request(XRender.MajorOpcode, 27, body)
    }

    private fun renderCreateAnimCursor(cursor: Int, vararg elements: Pair<Int, Int>): ByteArray {
        val body = ByteArray(4 + elements.size * 8)
        put32le(body, 0, cursor)
        elements.forEachIndexed { index, (sourceCursor, delay) ->
            val offset = 4 + index * 8
            put32le(body, offset, sourceCursor)
            put32le(body, offset + 4, delay)
        }
        return renderCreateAnimCursorRaw(body)
    }

    private fun renderCreateAnimCursorRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 31, body)

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
        return renderCreateLinearGradientRaw(body)
    }

    private fun renderCreateLinearGradientRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 34, body)

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
        return renderCreateRadialGradientRaw(body)
    }

    private fun renderCreateRadialGradientRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 35, body)

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
        return renderCreateConicalGradientRaw(body)
    }

    private fun renderCreateConicalGradientRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 36, body)

    private fun renderChangePicture(picture: Int, repeat: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, picture)
        put32le(body, 4, XRender.CPRepeat)
        put32le(body, 8, repeat)
        return request(XRender.MajorOpcode, 5, body)
    }

    private fun renderChangePictureAttributes(picture: Int, vararg attributes: Pair<Int, Int>): ByteArray {
        val sortedAttributes = attributes.sortedBy { it.first.countTrailingZeroBits() }
        val valueMask = sortedAttributes.fold(0) { mask, attribute -> mask or attribute.first }
        val body = ByteArray(8 + sortedAttributes.size * 4)
        put32le(body, 0, picture)
        put32le(body, 4, valueMask)
        sortedAttributes.forEachIndexed { index, attribute ->
            put32le(body, 8 + index * 4, attribute.second)
        }
        return request(XRender.MajorOpcode, 5, body)
    }

    private fun renderChangePictureClipMaskNone(picture: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, picture)
        put32le(body, 4, XRender.CPClipMask)
        put32le(body, 8, 0)
        return request(XRender.MajorOpcode, 5, body)
    }

    private fun renderChangePictureComponentAlpha(picture: Int, componentAlpha: Boolean): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, picture)
        put32le(body, 4, XRender.CPComponentAlpha)
        put32le(body, 8, if (componentAlpha) 1 else 0)
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

    private fun renderSetPictureClipRectangles(
        picture: Int,
        originX: Int = 0,
        originY: Int = 0,
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
        return request(XRender.MajorOpcode, 6, body)
    }

    private fun xfixesCreateRegion(region: Int, rectangles: List<XRectangleCommand>): ByteArray {
        val body = ByteArray(4 + rectangles.size * 8)
        put32le(body, 0, region)
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 4 + index * 8
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
        }
        return xfixesCreateRegionRaw(body)
    }

    private fun xfixesCreateRegionRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.CreateRegion, body)

    private fun xfixesCreateRegionFromBitmap(region: Int, bitmap: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, region)
        put32le(body, 4, bitmap)
        return xfixesCreateRegionFromBitmapRaw(body)
    }

    private fun xfixesCreateRegionFromBitmapRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.CreateRegionFromBitmap, body)

    private fun xfixesCreateRegionFromWindow(region: Int, window: Int, kind: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, region)
        put32le(body, 4, window)
        body[8] = kind.toByte()
        return xfixesCreateRegionFromWindowRaw(body)
    }

    private fun xfixesCreateRegionFromWindowRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.CreateRegionFromWindow, body)

    private fun xfixesCreateRegionFromGc(region: Int, gc: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, region)
        put32le(body, 4, gc)
        return xfixesCreateRegionFromGcRaw(body)
    }

    private fun xfixesCreateRegionFromGcRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.CreateRegionFromGC, body)

    private fun xfixesCreateRegionFromPicture(region: Int, picture: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, region)
        put32le(body, 4, picture)
        return xfixesCreateRegionFromPictureRaw(body)
    }

    private fun xfixesCreateRegionFromPictureRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.CreateRegionFromPicture, body)

    private fun xfixesDestroyRegion(region: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, region)
        return xfixesDestroyRegionRaw(body)
    }

    private fun xfixesDestroyRegionRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.DestroyRegion, body)

    private fun xfixesSetRegion(region: Int, rectangles: List<XRectangleCommand>): ByteArray {
        val body = ByteArray(4 + rectangles.size * 8)
        put32le(body, 0, region)
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 4 + index * 8
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
        }
        return xfixesSetRegionRaw(body)
    }

    private fun xfixesSetRegionRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.SetRegion, body)

    private fun xfixesCopyRegion(source: Int, destination: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, source)
        put32le(body, 4, destination)
        return request(XFixes.MajorOpcode, XFixes.CopyRegion, body)
    }

    private fun xfixesUnionRegion(source1: Int, source2: Int, destination: Int): ByteArray =
        xfixesCombineRegion(XFixes.UnionRegion, source1, source2, destination)

    private fun xfixesIntersectRegion(source1: Int, source2: Int, destination: Int): ByteArray =
        xfixesCombineRegion(XFixes.IntersectRegion, source1, source2, destination)

    private fun xfixesSubtractRegion(source1: Int, source2: Int, destination: Int): ByteArray =
        xfixesCombineRegion(XFixes.SubtractRegion, source1, source2, destination)

    private fun xfixesCombineRegion(minorOpcode: Int, source1: Int, source2: Int, destination: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, source1)
        put32le(body, 4, source2)
        put32le(body, 8, destination)
        return request(XFixes.MajorOpcode, minorOpcode, body)
    }

    private fun xfixesInvertRegion(source: Int, x: Int, y: Int, width: Int, height: Int, destination: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, source)
        put16le(body, 4, x)
        put16le(body, 6, y)
        put16le(body, 8, width)
        put16le(body, 10, height)
        put32le(body, 12, destination)
        return xfixesInvertRegionRaw(body)
    }

    private fun xfixesInvertRegionRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.InvertRegion, body)

    private fun xfixesTranslateRegion(region: Int, dx: Int, dy: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, region)
        put16le(body, 4, dx)
        put16le(body, 6, dy)
        return request(XFixes.MajorOpcode, XFixes.TranslateRegion, body)
    }

    private fun xfixesRegionExtents(source: Int, destination: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, source)
        put32le(body, 4, destination)
        return request(XFixes.MajorOpcode, XFixes.RegionExtents, body)
    }

    private fun xfixesFetchRegion(region: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, region)
        return request(XFixes.MajorOpcode, XFixes.FetchRegion, body)
    }

    private fun xfixesSetGcClipRegion(gc: Int, region: Int, originX: Int, originY: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, gc)
        put32le(body, 4, region)
        put16le(body, 8, originX)
        put16le(body, 10, originY)
        return request(XFixes.MajorOpcode, XFixes.SetGCClipRegion, body)
    }

    private fun xfixesSetWindowShapeRegion(
        window: Int,
        kind: Int,
        xOffset: Int,
        yOffset: Int,
        region: Int,
    ): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, window)
        body[4] = kind.toByte()
        put16le(body, 8, xOffset)
        put16le(body, 10, yOffset)
        put32le(body, 12, region)
        return xfixesSetWindowShapeRegionRaw(body)
    }

    private fun xfixesSetWindowShapeRegionRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.SetWindowShapeRegion, body)

    private fun xfixesSetPictureClipRegion(
        picture: Int,
        region: Int,
        originX: Int = 0,
        originY: Int = 0,
    ): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, picture)
        put32le(body, 4, region)
        put16le(body, 8, originX)
        put16le(body, 10, originY)
        return xfixesSetPictureClipRegionRaw(body)
    }

    private fun xfixesSetPictureClipRegionRaw(body: ByteArray): ByteArray =
        request(XFixes.MajorOpcode, XFixes.SetPictureClipRegion, body)

    private fun renderQueryPictIndexValues(format: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, format)
        return request(XRender.MajorOpcode, 2, body)
    }

    private fun renderQueryDithers(drawable: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, drawable)
        return request(XRender.MajorOpcode, 3, body)
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

    private fun trapezoidsHeader(
        source: Int,
        destination: Int,
        operation: Int = XRender.OpSrc,
        maskFormat: Int = XRender.A8Format,
    ): ByteArray {
        val body = ByteArray(20)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, maskFormat)
        return body
    }

    private fun renderTrapezoidsRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 10, body)

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

    private fun renderAddTrapsRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 32, body)

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

    private fun renderCreateGlyphSetRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 17, body)

    private fun renderReferenceGlyphSet(glyphSet: Int, existing: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, glyphSet)
        put32le(body, 4, existing)
        return request(XRender.MajorOpcode, 18, body)
    }

    private fun renderReferenceGlyphSetRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 18, body)

    private fun renderFreeGlyphSet(glyphSet: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, glyphSet)
        return request(XRender.MajorOpcode, 19, body)
    }

    private fun renderFreeGlyphSetRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 19, body)

    private fun renderAddGlyphsRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 20, body)

    private fun renderAddGlyphsFromPictureRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 21, body)

    private fun renderAddGlyphsFromPicture(
        glyphSet: Int,
        source: Int,
        glyphId: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        xOff: Int = 0,
        yOff: Int = 0,
        sourceX: Int = 0,
        sourceY: Int = 0,
    ): ByteArray {
        return renderAddGlyphsFromPicture(
            glyphSet,
            source,
            listOf(RenderPictureGlyph(glyphId, width, height, x, y, xOff, yOff, sourceX, sourceY)),
        )
    }

    private fun renderAddGlyphsFromPicture(
        glyphSet: Int,
        source: Int,
        glyphs: List<RenderPictureGlyph>,
    ): ByteArray {
        return renderAddGlyphsFromPictureRaw(renderAddGlyphsFromPictureBody(glyphSet, source, glyphs))
    }

    private fun renderAddGlyphsFromPictureBody(
        glyphSet: Int,
        source: Int,
        glyphId: Int,
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        xOff: Int = 0,
        yOff: Int = 0,
        sourceX: Int = 0,
        sourceY: Int = 0,
    ): ByteArray {
        return renderAddGlyphsFromPictureBody(
            glyphSet,
            source,
            listOf(RenderPictureGlyph(glyphId, width, height, x, y, xOff, yOff, sourceX, sourceY)),
        )
    }

    private fun renderAddGlyphsFromPictureBody(
        glyphSet: Int,
        source: Int,
        glyphs: List<RenderPictureGlyph>,
    ): ByteArray {
        val idsOffset = 12
        val glyphInfoOffset = idsOffset + glyphs.size * 4
        val body = ByteArray(glyphInfoOffset + glyphs.size * 16)
        put32le(body, 0, glyphSet)
        put32le(body, 4, source)
        put32le(body, 8, glyphs.size)
        glyphs.forEachIndexed { index, glyph ->
            put32le(body, idsOffset + index * 4, glyph.glyphId)
            val offset = glyphInfoOffset + index * 16
            put16le(body, offset, glyph.width)
            put16le(body, offset + 2, glyph.height)
            put16le(body, offset + 4, glyph.x)
            put16le(body, offset + 6, glyph.y)
            put16le(body, offset + 8, glyph.xOff)
            put16le(body, offset + 10, glyph.yOff)
            put16le(body, offset + 12, glyph.sourceX)
            put16le(body, offset + 14, glyph.sourceY)
        }
        return body
    }

    private fun renderScale(
        source: Int,
        destination: Int,
        colorScale: Int = 0x1_0000,
        alphaScale: Int = 0x1_0000,
        sourceX: Int = 0,
        sourceY: Int = 0,
        destinationX: Int = 0,
        destinationY: Int = 0,
        width: Int = 20,
        height: Int = 10,
    ): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, source)
        put32le(body, 4, destination)
        put32le(body, 8, colorScale)
        put32le(body, 12, alphaScale)
        put16le(body, 16, sourceX)
        put16le(body, 18, sourceY)
        put16le(body, 20, destinationX)
        put16le(body, 22, destinationY)
        put16le(body, 24, width)
        put16le(body, 26, height)
        return request(XRender.MajorOpcode, 9, body)
    }

    private fun renderRaw(minorOpcode: Int, body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, minorOpcode, body)

    private fun renderFreeGlyphs(glyphSet: Int, glyphIds: List<Int>): ByteArray {
        val body = ByteArray(4 + glyphIds.size * 4)
        put32le(body, 0, glyphSet)
        glyphIds.forEachIndexed { index, glyphId ->
            put32le(body, 4 + index * 4, glyphId)
        }
        return request(XRender.MajorOpcode, 22, body)
    }

    private fun renderFreeGlyphsRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 22, body)

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
        return renderAddGlyphsRaw(renderAddA8GlyphBody(glyphSet, glyphId, width, height, x, y, xOff, yOff, alphas))
    }

    private fun renderAddA8GlyphBody(
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
        return body
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
        maskFormat: Int = XRender.A8Format,
    ): ByteArray {
        val idsOffset = 32
        val paddedSize = (idsOffset + glyphIds.size * 4 + 3) and -4
        val body = compositeGlyphsHeader(source, destination, glyphSet, operation, maskFormat, sourceX, sourceY).copyOf(paddedSize)
        body[24] = glyphIds.size.toByte()
        put16le(body, 28, deltaX)
        put16le(body, 30, deltaY)
        glyphIds.forEachIndexed { index, glyphId ->
            put32le(body, idsOffset + index * 4, glyphId)
        }
        return request(XRender.MajorOpcode, 25, body)
    }

    private fun renderCompositeGlyphsRaw(minorOpcode: Int, body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, minorOpcode, body)

    private fun compositeGlyphsHeader(
        source: Int,
        destination: Int,
        glyphSet: Int,
        operation: Int = XRender.OpOver,
        maskFormat: Int = XRender.A8Format,
        sourceX: Int = 0,
        sourceY: Int = 0,
    ): ByteArray {
        val body = ByteArray(24)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, maskFormat)
        put32le(body, 16, glyphSet)
        put16le(body, 20, sourceX)
        put16le(body, 22, sourceY)
        return body
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

    private fun trianglesHeader(
        source: Int,
        destination: Int,
        operation: Int = XRender.OpSrc,
        maskFormat: Int = XRender.A8Format,
    ): ByteArray {
        val body = ByteArray(20)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, maskFormat)
        return body
    }

    private fun renderTrianglesRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 11, body)

    private fun renderColorTrapezoids(
        destination: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        topLeft: RenderColor,
        topRight: RenderColor,
        bottomLeftColor: RenderColor,
        bottomRightColor: RenderColor,
        bottomLeft: Int = left,
        bottomRight: Int = right,
        operation: Int = XRender.OpSrc,
    ): ByteArray {
        val body = ByteArray(64)
        body[0] = operation.toByte()
        put32le(body, 4, destination)
        putColorSpan(body, 8, left = left, right = right, y = top, leftColor = topLeft, rightColor = topRight)
        putColorSpan(body, 36, left = bottomLeft, right = bottomRight, y = bottom, leftColor = bottomLeftColor, rightColor = bottomRightColor)
        return renderColorTrapezoidsRaw(body)
    }

    private fun colorTrapezoidsHeader(
        destination: Int,
        operation: Int = XRender.OpSrc,
    ): ByteArray {
        val body = ByteArray(8)
        body[0] = operation.toByte()
        put32le(body, 4, destination)
        return body
    }

    private fun putColorSpan(
        body: ByteArray,
        offset: Int,
        left: Int,
        right: Int,
        y: Int,
        leftColor: RenderColor,
        rightColor: RenderColor,
    ) {
        putFixed(body, offset, left)
        putFixed(body, offset + 4, right)
        putFixed(body, offset + 8, y)
        putRenderColor(body, offset + 12, leftColor)
        putRenderColor(body, offset + 20, rightColor)
    }

    private fun putRenderColor(body: ByteArray, offset: Int, color: RenderColor) {
        put16le(body, offset, color.red)
        put16le(body, offset + 2, color.green)
        put16le(body, offset + 4, color.blue)
        put16le(body, offset + 6, color.alpha)
    }

    private fun renderColorTrapezoidsRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 14, body)

    private fun renderColorTriangles(
        destination: Int,
        points: List<Pair<Int, Int>>,
        colors: List<RenderColor>,
        operation: Int = XRender.OpSrc,
    ): ByteArray {
        require(points.size == 3)
        require(colors.size == 3)
        val body = ByteArray(56)
        body[0] = operation.toByte()
        put32le(body, 4, destination)
        var offset = 8
        points.zip(colors).forEach { (point, color) ->
            putFixedPoint(body, offset, point.first, point.second)
            put16le(body, offset + 8, color.red)
            put16le(body, offset + 10, color.green)
            put16le(body, offset + 12, color.blue)
            put16le(body, offset + 14, color.alpha)
            offset += 16
        }
        return renderColorTrianglesRaw(body)
    }

    private fun colorTrianglesHeader(
        destination: Int,
        operation: Int = XRender.OpSrc,
    ): ByteArray {
        val body = ByteArray(8)
        body[0] = operation.toByte()
        put32le(body, 4, destination)
        return body
    }

    private fun renderColorTrianglesRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 15, body)

    private fun renderTransform(
        source: Int,
        destination: Int,
        sourceQuad: List<Pair<Int, Int>> = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1),
        destinationQuad: List<Pair<Int, Int>> = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1),
        operation: Int = XRender.OpSrc,
        filter: Int = 0,
    ): ByteArray =
        renderTransformRaw(renderTransformBody(source, destination, sourceQuad, destinationQuad, operation, filter))

    private fun renderTransformBody(
        source: Int,
        destination: Int,
        sourceQuad: List<Pair<Int, Int>> = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1),
        destinationQuad: List<Pair<Int, Int>> = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1),
        operation: Int = XRender.OpSrc,
        filter: Int = 0,
    ): ByteArray {
        require(sourceQuad.size == 4)
        require(destinationQuad.size == 4)
        val body = ByteArray(80)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        putFixedQuad(body, 12, sourceQuad)
        putFixedQuad(body, 44, destinationQuad)
        put32le(body, 76, filter)
        return body
    }

    private fun putFixedQuad(bytes: ByteArray, offset: Int, quad: List<Pair<Int, Int>>) {
        quad.forEachIndexed { index, point ->
            putFixedPoint(bytes, offset + index * 8, point.first, point.second)
        }
    }

    private fun renderTransformRaw(body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, 16, body)

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

    private fun triangleMeshHeader(
        source: Int,
        destination: Int,
        operation: Int = XRender.OpSrc,
        maskFormat: Int = XRender.A8Format,
    ): ByteArray {
        val body = ByteArray(20)
        body[0] = operation.toByte()
        put32le(body, 4, source)
        put32le(body, 8, destination)
        put32le(body, 12, maskFormat)
        return body
    }

    private fun renderTriangleMeshRaw(minorOpcode: Int, body: ByteArray): ByteArray =
        request(XRender.MajorOpcode, minorOpcode, body)

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
        return createGcRequest(PutImageGcId, drawable) + putImage8OnlyRequest(drawable, width, height, alphas)
    }

    private fun putImage8OnlyRequest(drawable: Int, width: Int, height: Int, alphas: ByteArray): ByteArray {
        val stride = (width + 3) and -4
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * stride + x] = alphas[y * width + x]
            }
        }
        val body = ByteArray(20 + data.size)
        put32le(body, 0, drawable)
        put32le(body, 4, PutImageGcId)
        put16le(body, 8, width)
        put16le(body, 10, height)
        body[17] = 8
        data.copyInto(body, 20)
        return request(72, 2, body)
    }

    private fun putImage1OnlyRequest(drawable: Int, width: Int, height: Int, bits: List<Boolean>): ByteArray {
        require(bits.size == width * height)
        val stride = ((width + 7) / 8 + 3) and -4
        val data = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bits[y * width + x]) {
                    val offset = y * stride + x / 8
                    data[offset] = (data[offset].toInt() or (1 shl (x % 8))).toByte()
                }
            }
        }
        val body = ByteArray(20 + data.size)
        put32le(body, 0, drawable)
        put32le(body, 4, PutImageGcId)
        put16le(body, 8, width)
        put16le(body, 10, height)
        body[17] = 1
        data.copyInto(body, 20)
        return request(72, 1, body)
    }

    private fun freePixmapRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(54, 0, body)
    }

    private fun createGcRequest(
        id: Int,
        drawable: Int,
        foreground: Int? = null,
        background: Int? = null,
        graphicsExposures: Boolean? = null,
    ): ByteArray {
        val values = mutableListOf<Int>()
        var mask = 0
        if (foreground != null) {
            mask = mask or 0x0000_0004
            values += foreground
        }
        if (background != null) {
            mask = mask or 0x0000_0008
            values += background
        }
        if (graphicsExposures != null) {
            mask = mask or 0x0001_0000
            values += if (graphicsExposures) 1 else 0
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

    private fun setClipRectangles(gc: Int, originX: Int, originY: Int, rectangles: List<XRectangleCommand>): ByteArray {
        val body = ByteArray(8 + rectangles.size * 8)
        put32le(body, 0, gc)
        put16le(body, 4, originX)
        put16le(body, 6, originY)
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 8 + index * 8
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
        }
        return request(59, 0, body)
    }

    private fun polyFillRectangle(drawable: Int, gc: Int, rectangles: List<XRectangleCommand>): ByteArray {
        val body = ByteArray(8 + rectangles.size * 8)
        put32le(body, 0, drawable)
        put32le(body, 4, gc)
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 8 + index * 8
            put16le(body, offset, rectangle.x)
            put16le(body, offset + 2, rectangle.y)
            put16le(body, offset + 4, rectangle.width)
            put16le(body, offset + 6, rectangle.height)
        }
        return request(70, 0, body)
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

    private fun queryPointerRequest(): ByteArray =
        request(38, 0, ByteArray(4).also { put32le(it, 0, X11Ids.RootWindow) })

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

    private fun readReplySkippingEvents(input: InputStream): ByteArray {
        while (true) {
            val header = input.readExactly(32)
            val type = header[0].toInt() and 0xff
            if (type == 0 || type == 1) {
                val payloadUnits = if (type == 1) u32le(header, 4) else 0
                return header + input.readExactly(payloadUnits * 4)
            }
        }
    }

    private fun assertRegionReply(input: InputStream, extents: XRectangleCommand, rectangles: List<XRectangleCommand>) {
        val reply = readReply(input)
        assertEquals(rectangles.size * 2, u32le(reply, 4))
        assertEquals(extents.x, i16le(reply, 8))
        assertEquals(extents.y, i16le(reply, 10))
        assertEquals(extents.width, u16le(reply, 12))
        assertEquals(extents.height, u16le(reply, 14))
        rectangles.forEachIndexed { index, rectangle ->
            val offset = 32 + index * 8
            assertEquals(rectangle.x, i16le(reply, offset))
            assertEquals(rectangle.y, i16le(reply, offset + 2))
            assertEquals(rectangle.width, u16le(reply, offset + 4))
            assertEquals(rectangle.height, u16le(reply, offset + 6))
        }
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        assertExtensionError(input, error = error, opcode = XRender.MajorOpcode, badValue = badValue, sequence = sequence, minorOpcode = minorOpcode)
    }

    private fun assertExtensionError(input: InputStream, error: Int, opcode: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
        assertEquals(0, reply[11].toInt() and 0xff)
        for (index in 12 until 32) {
            assertEquals(0, reply[index].toInt() and 0xff, "byte $index")
        }
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

    private fun i16le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset).toShort().toInt()

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
        const val ComponentMaskPixmapId = 0x0020_2003
        const val ComponentMaskPictureId = 0x0020_2004
        const val PixmapId = 0x0020_0100
        const val PixmapPictureId = 0x0020_0101
        const val RegionId = 0x0020_5001
        const val EmptyRegionId = 0x0020_5002
        const val SourceClipRegionId = 0x0020_5003
        const val RegionBId = 0x0020_5004
        const val RegionResultId = 0x0020_5005
        const val RegionExtentsId = 0x0020_5006
        const val OverlapRegionId = 0x0020_5007
        const val VerticalRegionId = 0x0020_5008
        const val GlyphSetId = 0x0020_3001
        const val GlyphId = 0x0000_0041
        const val PutImageGcId = 0x0020_4001
    }

    private data class RenderPictureGlyph(
        val glyphId: Int,
        val width: Int,
        val height: Int,
        val x: Int = 0,
        val y: Int = 0,
        val xOff: Int = 0,
        val yOff: Int = 0,
        val sourceX: Int = 0,
        val sourceY: Int = 0,
    )

    private data class RenderColor(
        val red: Int,
        val green: Int,
        val blue: Int,
        val alpha: Int,
    )
}
