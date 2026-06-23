package org.jonnyzzz.xserver

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger

internal class X11Connection(
    private val input: InputStream,
    private val output: OutputStream,
    private val state: X11State,
) : XEventSink {
    private lateinit var byteOrder: ByteOrder
    private var sequence = 0
    private val trace = java.lang.Boolean.getBoolean("x.trace")
    private val writeLock = Any()
    private val ownedResources = linkedSetOf<Int>()

    fun run() {
        try {
            val setupPrefix = input.readExactly(12)
            byteOrder = ByteOrder.fromSetupByte(setupPrefix[0].toInt() and 0xff)
            val major = byteOrder.u16(setupPrefix, 2)
            val minor = byteOrder.u16(setupPrefix, 4)
            val authNameLength = byteOrder.u16(setupPrefix, 6)
            val authDataLength = byteOrder.u16(setupPrefix, 8)

            val authNamePad = paddedLength(authNameLength)
            val authDataPad = paddedLength(authDataLength)
            if (authNamePad > 0) input.readExactly(authNamePad)
            if (authDataPad > 0) input.readExactly(authDataPad)

            val reply = SetupReply.success(
                byteOrder = byteOrder,
                clientMajor = major,
                clientMinor = minor,
                width = state.width,
                height = state.height,
                widthMillimeters = state.widthMillimeters,
                heightMillimeters = state.heightMillimeters,
            )
            write(reply)
            state.registerEventSink(this)

            while (true) {
                val header = input.readOrNull(4) ?: return
                val opcode = header[0].toInt() and 0xff
                val minorOpcode = header[1].toInt() and 0xff
                val units = byteOrder.u16(header, 2)
                val body = if (units == 0) {
                    val extendedLengthBytes = input.readExactly(4)
                    val extendedUnits = byteOrder.u32(extendedLengthBytes, 0)
                    if (extendedUnits < 2) {
                        writeError(error = 1, opcode = opcode, minorOpcode = minorOpcode, badValue = extendedUnits)
                        return
                    }
                    input.readExactly(extendedUnits * 4 - 8)
                } else {
                    input.readExactly(units * 4 - 4)
                }
                sequence = (sequence + 1) and 0xffff
                if (trace) {
                    System.err.println("x11 seq=$sequence opcode=$opcode minor=$minorOpcode units=$units body=${body.size}")
                }
                state.recordRequest(requestName(opcode, minorOpcode))
                dispatch(opcode, minorOpcode, body)
            }
        } finally {
            state.unregisterEventSink(this)
            state.removeClientResources(ownedResources)
        }
    }

    private fun dispatch(opcode: Int, minorOpcode: Int, body: ByteArray) {
        state.extensionByMajorOpcode(opcode)?.let { extension ->
            if (extension.name == "GLX") {
                glx(minorOpcode, body, opcode)
                return
            }
            if (extension.name == "BIG-REQUESTS") {
                bigRequests(minorOpcode, opcode)
                return
            }
            if (extension.name == "RENDER") {
                render(minorOpcode, body, opcode)
                return
            }
        }
        when (opcode) {
            1 -> createWindow(body)
            2 -> changeWindowAttributes(body)
            3 -> getWindowAttributes(body)
            4 -> destroyWindow(body)
            6 -> unitReplyless()
            7 -> reparentWindow(body)
            8 -> mapWindow(body)
            9 -> mapSubwindows(body)
            10 -> unmapWindow(body)
            11 -> unmapSubwindows(body)
            12 -> configureWindow(body)
            13 -> unitReplyless()
            14 -> getGeometry(body)
            15 -> queryTree(body)
            16 -> internAtom(minorOpcode, body)
            17 -> getAtomName(body)
            18 -> changeProperty(minorOpcode, body)
            19 -> deleteProperty(body)
            20 -> getProperty(minorOpcode, body)
            21 -> listProperties(body)
            22 -> unitReplyless()
            23 -> getSelectionOwner()
            24 -> unitReplyless()
            25 -> unitReplyless()
            26 -> unitReplyless()
            27 -> unitReplyless()
            28 -> unitReplyless()
            29 -> unitReplyless()
            30 -> unitReplyless()
            31 -> unitReplyless()
            32 -> unitReplyless()
            33 -> unitReplyless()
            34 -> unitReplyless()
            35 -> unitReplyless()
            36 -> unitReplyless()
            37 -> unitReplyless()
            38 -> queryPointer()
            40 -> translateCoordinates(body)
            42 -> unitReplyless()
            43 -> getInputFocus()
            44 -> queryKeymap()
            45 -> openFont(body)
            46 -> closeResource(body)
            47 -> queryFont(body)
            48 -> queryTextExtents(minorOpcode, body)
            49 -> listFonts()
            52 -> getFontPath()
            53 -> createPixmap(minorOpcode, body)
            54 -> closeResource(body)
            55 -> createGc(body)
            56 -> changeGc(body)
            57 -> copyGc(body)
            58 -> setDashes(body)
            59 -> setClipRectangles(minorOpcode, body)
            60 -> closeResource(body)
            61 -> clearArea(body)
            62 -> copyArea(body)
            63 -> copyPlane(body)
            64 -> polyPoint(minorOpcode, body)
            65 -> polyLine(minorOpcode, body)
            66 -> polySegment(body)
            67 -> polyRectangle(body, XDrawingKind.Rectangle)
            68 -> polyArc(body, filled = false)
            69 -> fillPoly(body)
            70 -> polyRectangle(body, XDrawingKind.FillRectangle)
            71 -> polyArc(body, filled = true)
            72 -> putImage(minorOpcode, body)
            73 -> getImage(minorOpcode, body)
            74 -> polyText(body, is16Bit = false)
            75 -> polyText(body, is16Bit = true)
            76 -> imageText(minorOpcode, body, is16Bit = false)
            77 -> imageText(minorOpcode, body, is16Bit = true)
            78 -> createColormap(body)
            79 -> closeResource(body)
            81 -> unitReplyless()
            83 -> listInstalledColormaps()
            84 -> allocColor(body)
            85 -> lookupColor()
            91 -> queryColors(body)
            93 -> createCursor(body)
            94 -> createCursor(body)
            95 -> closeResource(body)
            96 -> unitReplyless()
            97 -> queryBestSize(minorOpcode, body)
            98 -> queryExtension(body)
            99 -> listExtensions()
            101 -> getKeyboardMapping(body)
            103 -> getKeyboardControl()
            105 -> unitReplyless()
            106 -> getPointerControl()
            107 -> unitReplyless()
            108 -> getScreenSaver()
            112 -> unitReplyless()
            116 -> getPointerMapping()
            117 -> getPointerMapping()
            118 -> getModifierMapping()
            119 -> getModifierMapping()
            127 -> unitReplyless()
            else -> unsupportedRequest(opcode, minorOpcode, requestName(opcode, minorOpcode))
        }
    }

    private fun glx(minorOpcode: Int, body: ByteArray, majorOpcode: Int) {
        val operation = XGlx.operationName(minorOpcode)
        val detail = glxDetail(minorOpcode, body)
        state.recordGlxOperation(minorOpcode, operation, detail)
        System.err.println("glx seq=$sequence minor=$minorOpcode operation=$operation body=${body.size} $detail")

        when (minorOpcode) {
            XGlx.QueryVersion -> glxQueryVersion()
            3 -> glxCreateContext(body)
            4 -> glxDestroyContext(body)
            5 -> glxMakeCurrent(body, isContextCurrent = false)
            XGlx.IsDirect -> glxIsDirect(body)
            8, 9, 11 -> Unit
            1, 2 -> Unit
            XGlx.GetVisualConfigs -> glxGetVisualConfigs(body)
            XGlx.QueryExtensionsString -> glxStringReply(XGlx.serverString(XGlx.ExtensionsName))
            XGlx.QueryServerString -> glxQueryServerString(body)
            XGlx.ClientInfo -> Unit
            XGlx.GetFBConfigs -> glxGetFbConfigs(body)
            XGlx.CreateNewContext -> glxCreateNewContext(body)
            XGlx.MakeContextCurrent -> glxMakeCurrent(body, isContextCurrent = true)
            XGlx.CreateContextAttribsARB -> glxCreateContextAttribs(body)
            else -> unsupportedRequest(majorOpcode, minorOpcode, operation)
        }
    }

    private fun glxQueryVersion() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, XGlx.MajorVersion)
        byteOrder.put32(reply, 12, XGlx.MinorVersion)
        write(reply)
    }

    private fun bigRequests(minorOpcode: Int, majorOpcode: Int) {
        if (minorOpcode != XBigRequests.Enable) {
            return unsupportedRequest(majorOpcode, minorOpcode, "BIG-REQUESTS.$minorOpcode")
        }
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, XBigRequests.MaximumRequestLength)
        write(reply)
    }

    private fun render(minorOpcode: Int, body: ByteArray, majorOpcode: Int) {
        val operation = XRender.operationName(minorOpcode)
        val detail = renderDetail(minorOpcode, body)
        state.recordRenderOperation(minorOpcode, operation, detail)
        System.err.println("render seq=$sequence minor=$minorOpcode operation=$operation body=${body.size} $detail")

        when (minorOpcode) {
            0 -> renderQueryVersion()
            1 -> renderQueryPictFormats()
            2 -> renderQueryPictIndexValues()
            4 -> renderCreatePicture(body)
            5 -> renderChangePicture(body)
            6 -> renderSetPictureClipRectangles(body)
            7 -> renderFreePicture(body)
            8 -> renderComposite(body)
            10 -> renderTrapezoids(body)
            11 -> renderTriangles(body)
            12 -> renderTriStrip(body)
            13 -> renderTriFan(body)
            17 -> renderCreateGlyphSet(body)
            18 -> renderReferenceGlyphSet(body)
            19 -> renderFreeGlyphSet(body)
            20 -> renderAddGlyphs(body)
            22 -> renderFreeGlyphs(body)
            23, 24, 25 -> renderCompositeGlyphs(minorOpcode, body)
            26 -> renderFillRectangles(body)
            27 -> renderCreateCursor(body)
            28 -> renderSetPictureTransform(body)
            29 -> renderQueryFilters(body)
            30 -> renderSetPictureFilter(body)
            31 -> renderCreateAnimCursor(body)
            32 -> renderAddTraps(body)
            33 -> renderCreateSolidFill(body)
            34 -> renderCreateLinearGradient(body)
            35 -> renderCreateRadialGradient(body)
            36 -> renderCreateConicalGradient(body)
            else -> unsupportedRequest(majorOpcode, minorOpcode, "RENDER.$operation")
        }
    }

    private fun renderQueryVersion() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, XRender.MajorVersion)
        byteOrder.put32(reply, 12, XRender.MinorVersion)
        write(reply)
    }

    private fun renderQueryPictFormats() {
        val formats = ByteArray(28 * 4)
        putPictFormat(formats, 0, XRender.Argb32Format, depth = 32, redShift = 16, greenShift = 8, blueShift = 0, alphaShift = 24, alphaMask = 0xff)
        putPictFormat(formats, 28, XRender.Rgb24Format, depth = 24, redShift = 16, greenShift = 8, blueShift = 0, alphaShift = 0, alphaMask = 0)
        putPictFormat(formats, 56, XRender.A8Format, depth = 8, redShift = 0, redMask = 0, greenShift = 0, greenMask = 0, blueShift = 0, blueMask = 0, alphaShift = 0, alphaMask = 0xff)
        putPictFormat(formats, 84, XRender.A1Format, depth = 1, redShift = 0, redMask = 0, greenShift = 0, greenMask = 0, blueShift = 0, blueMask = 0, alphaShift = 0, alphaMask = 0x1)

        val screen = ByteArray(24)
        byteOrder.put32(screen, 0, 1)
        byteOrder.put32(screen, 4, XRender.Rgb24Format)
        screen[8] = 24
        byteOrder.put16(screen, 10, 1)
        byteOrder.put32(screen, 16, X11Ids.RootVisual)
        byteOrder.put32(screen, 20, XRender.Rgb24Format)

        val subpixels = ByteArray(4)
        byteOrder.put32(subpixels, 0, 5)
        val payload = formats + screen + subpixels
        val reply = reply(extra = 0, payloadUnits = payload.size / 4)
        byteOrder.put32(reply, 8, 4)
        byteOrder.put32(reply, 12, 1)
        byteOrder.put32(reply, 16, 1)
        byteOrder.put32(reply, 20, 1)
        byteOrder.put32(reply, 24, 1)
        payload.copyInto(reply, 32)
        write(reply)
    }

    private fun putPictFormat(
        bytes: ByteArray,
        offset: Int,
        id: Int,
        depth: Int,
        redShift: Int,
        greenShift: Int,
        blueShift: Int,
        alphaShift: Int,
        alphaMask: Int,
        redMask: Int = 0xff,
        greenMask: Int = 0xff,
        blueMask: Int = 0xff,
    ) {
        byteOrder.put32(bytes, offset, id)
        bytes[offset + 4] = 1
        bytes[offset + 5] = depth.toByte()
        byteOrder.put16(bytes, offset + 8, redShift)
        byteOrder.put16(bytes, offset + 10, redMask)
        byteOrder.put16(bytes, offset + 12, greenShift)
        byteOrder.put16(bytes, offset + 14, greenMask)
        byteOrder.put16(bytes, offset + 16, blueShift)
        byteOrder.put16(bytes, offset + 18, blueMask)
        byteOrder.put16(bytes, offset + 20, alphaShift)
        byteOrder.put16(bytes, offset + 22, alphaMask)
    }

    private fun renderQueryPictIndexValues() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, 0)
        write(reply)
    }

    private fun renderCreatePicture(body: ByteArray) {
        if (body.size < 16) return
        val id = byteOrder.u32(body, 0)
        val valueMask = byteOrder.u32(body, 12)
        val attributes = renderPictureAttributes(valueMask, body, valuesOffset = 16)
        state.putPicture(
            XPicture(
                id = id,
                drawableId = byteOrder.u32(body, 4),
                format = byteOrder.u32(body, 8),
                valueMask = valueMask,
                repeat = attributes.repeat ?: XRender.RepeatNone,
            ),
        )
        own(id)
    }

    private fun renderChangePicture(body: ByteArray) {
        if (body.size < 8) return
        val valueMask = byteOrder.u32(body, 4)
        val attributes = renderPictureAttributes(valueMask, body, valuesOffset = 8)
        state.updatePicture(byteOrder.u32(body, 0), valueMask, repeat = attributes.repeat)
    }

    private fun renderPictureAttributes(valueMask: Int, body: ByteArray, valuesOffset: Int): XRenderPictureAttributes {
        var offset = valuesOffset
        var repeat: Int? = null
        for (bit in 0..12) {
            val mask = 1 shl bit
            if ((valueMask and mask) == 0) continue
            if (offset + 4 > body.size) break
            val value = byteOrder.u32(body, offset)
            if (mask == XRender.CPRepeat) repeat = value
            offset += 4
        }
        return XRenderPictureAttributes(repeat = repeat)
    }

    private fun renderSetPictureClipRectangles(body: ByteArray) {
        if (body.size < 8) return
        val picture = byteOrder.u32(body, 0)
        val originX = byteOrder.i16(body, 4)
        val originY = byteOrder.i16(body, 6)
        state.updatePictureClip(
            picture,
            rectangles(body, 8).map { rectangle ->
                XRectangleCommand(
                    x = originX + rectangle.x,
                    y = originY + rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                )
            },
        )
    }

    private fun renderFreePicture(body: ByteArray) {
        if (body.size < 4) return
        val id = byteOrder.u32(body, 0)
        state.removePicture(id)
        ownedResources.remove(id)
    }

    private fun renderComposite(body: ByteArray) {
        if (body.size < 32) return
        val operation = body[0].toInt() and 0xff
        val source = state.picture(byteOrder.u32(body, 4)) ?: return
        val mask = byteOrder.u32(body, 8).takeIf { it != 0 }?.let { state.picture(it) }
        val destination = state.picture(byteOrder.u32(body, 12)) ?: return
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 16)
        val sourceY = byteOrder.i16(body, 18)
        val maskX = byteOrder.i16(body, 20)
        val maskY = byteOrder.i16(body, 22)
        val destinationX = byteOrder.i16(body, 24)
        val destinationY = byteOrder.i16(body, 26)
        val width = byteOrder.u16(body, 28)
        val height = byteOrder.u16(body, 30)
        val rectangle = XRectangleCommand(destinationX, destinationY, width, height)
        val image = state.composite(
            operation = operation,
            source = source,
            mask = mask,
            destination = destination,
            sourceX = sourceX,
            sourceY = sourceY,
            maskX = maskX,
            maskY = maskY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
        ) ?: return
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawableId,
                kind = if (source.solidPixel != null || source.linearGradient != null) XDrawingKind.FillRectangle else XDrawingKind.CopyArea,
                foreground = source.solidPixel ?: 0,
                rectangles = listOf(rectangle),
                imageDataUri = XFramebuffer.imageDataUri(image),
                sourceDrawableId = source.drawableId,
                framebufferBacked = true,
            ),
        )
    }

    private fun renderTrapezoids(body: ByteArray) {
        if (body.size < 20) return
        val operation = body[0].toInt() and 0xff
        val source = state.picture(byteOrder.u32(body, 4)) ?: return
        val destination = state.picture(byteOrder.u32(body, 8)) ?: return
        val maskFormat = byteOrder.u32(body, 12)
        if (!XRender.isAlphaMaskFormat(maskFormat)) return
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 16)
        val sourceY = byteOrder.i16(body, 18)
        val trapezoids = trapezoids(body, 20)
        if (trapezoids.isEmpty()) return
        val painted = state.renderTrapezoids(
            operation = operation,
            source = source,
            destination = destination,
            maskFormat = maskFormat,
            sourceX = sourceX,
            sourceY = sourceY,
            trapezoids = trapezoids,
        )
        if (!painted) return
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawableId,
                kind = XDrawingKind.FillPoly,
                foreground = source.solidPixel ?: 0,
                points = trapezoids.flatMap { trapezoid ->
                    listOf(
                        XPoint(trapezoid.left.p1.x.fixedToInt(), trapezoid.left.p1.y.fixedToInt()),
                        XPoint(trapezoid.right.p1.x.fixedToInt(), trapezoid.right.p1.y.fixedToInt()),
                        XPoint(trapezoid.right.p2.x.fixedToInt(), trapezoid.right.p2.y.fixedToInt()),
                        XPoint(trapezoid.left.p2.x.fixedToInt(), trapezoid.left.p2.y.fixedToInt()),
                    )
                },
                framebufferBacked = true,
            ),
        )
    }

    private fun renderTriangles(body: ByteArray) {
        if (body.size < 20) return
        val operation = body[0].toInt() and 0xff
        val source = state.picture(byteOrder.u32(body, 4)) ?: return
        val destination = state.picture(byteOrder.u32(body, 8)) ?: return
        val maskFormat = byteOrder.u32(body, 12)
        if (!XRender.isAlphaMaskFormat(maskFormat)) return
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 16)
        val sourceY = byteOrder.i16(body, 18)
        val triangles = triangles(body, 20)
        if (triangles.isEmpty()) return
        val painted = state.renderTriangles(
            operation = operation,
            source = source,
            destination = destination,
            maskFormat = maskFormat,
            sourceX = sourceX,
            sourceY = sourceY,
            triangles = triangles,
        )
        if (!painted) return
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawableId,
                kind = XDrawingKind.FillPoly,
                foreground = source.solidPixel ?: 0,
                points = triangles.flatMap { triangle ->
                    listOf(
                        XPoint(triangle.p1.x.fixedToInt(), triangle.p1.y.fixedToInt()),
                        XPoint(triangle.p2.x.fixedToInt(), triangle.p2.y.fixedToInt()),
                        XPoint(triangle.p3.x.fixedToInt(), triangle.p3.y.fixedToInt()),
                    )
                },
                framebufferBacked = true,
            ),
        )
    }

    private fun renderTriStrip(body: ByteArray) {
        renderTriangleMesh(body) { points ->
            points.windowed(size = 3, step = 1).map { XTriangleCommand(it[0], it[1], it[2]) }
        }
    }

    private fun renderTriFan(body: ByteArray) {
        renderTriangleMesh(body) { points ->
            val anchor = points.firstOrNull() ?: return@renderTriangleMesh emptyList()
            points.drop(1).windowed(size = 2, step = 1).map { XTriangleCommand(anchor, it[0], it[1]) }
        }
    }

    private fun renderTriangleMesh(body: ByteArray, trianglesFrom: (List<XFixedPoint>) -> List<XTriangleCommand>) {
        if (body.size < 20) return
        val operation = body[0].toInt() and 0xff
        val source = state.picture(byteOrder.u32(body, 4)) ?: return
        val destination = state.picture(byteOrder.u32(body, 8)) ?: return
        val maskFormat = byteOrder.u32(body, 12)
        if (!XRender.isAlphaMaskFormat(maskFormat)) return
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 16)
        val sourceY = byteOrder.i16(body, 18)
        val triangles = trianglesFrom(fixedPoints(body, 20))
        if (triangles.isEmpty()) return
        val painted = state.renderTriangles(
            operation = operation,
            source = source,
            destination = destination,
            maskFormat = maskFormat,
            sourceX = sourceX,
            sourceY = sourceY,
            triangles = triangles,
        )
        if (!painted) return
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawableId,
                kind = XDrawingKind.FillPoly,
                foreground = source.solidPixel ?: 0,
                points = triangles.flatMap { triangle ->
                    listOf(
                        XPoint(triangle.p1.x.fixedToInt(), triangle.p1.y.fixedToInt()),
                        XPoint(triangle.p2.x.fixedToInt(), triangle.p2.y.fixedToInt()),
                        XPoint(triangle.p3.x.fixedToInt(), triangle.p3.y.fixedToInt()),
                    )
                },
                framebufferBacked = true,
            ),
        )
    }

    private fun renderCreateGlyphSet(body: ByteArray) {
        if (body.size < 8) return
        val id = byteOrder.u32(body, 0)
        state.putGlyphSet(XGlyphSet(id, byteOrder.u32(body, 4)))
        own(id)
    }

    private fun renderReferenceGlyphSet(body: ByteArray) {
        if (body.size < 8) return
        val id = byteOrder.u32(body, 0)
        state.referenceGlyphSet(id, byteOrder.u32(body, 4))
        own(id)
    }

    private fun renderFreeGlyphSet(body: ByteArray) {
        if (body.size < 4) return
        val id = byteOrder.u32(body, 0)
        state.removeGlyphSet(id)
        ownedResources.remove(id)
    }

    private fun renderAddGlyphs(body: ByteArray) {
        if (body.size < 8) return
        val glyphSet = byteOrder.u32(body, 0)
        val format = state.glyphSetFormat(glyphSet)
        val glyphsLength = byteOrder.u32(body, 4).coerceAtMost((body.size - 8) / 16)
        var idOffset = 8
        var infoOffset = idOffset + glyphsLength * 4
        var imageOffset = infoOffset + glyphsLength * 12
        val glyphs = mutableListOf<XGlyph>()
        repeat(glyphsLength) { index ->
            if (infoOffset + 12 <= body.size) {
                val width = byteOrder.u16(body, infoOffset)
                val height = byteOrder.u16(body, infoOffset + 2)
                val mask = format?.let {
                    decodeGlyphMask(
                        format = it,
                        width = width,
                        height = height,
                        data = body,
                        offset = imageOffset,
                    )
                }
                glyphs += XGlyph(
                    id = byteOrder.u32(body, idOffset + index * 4),
                    width = width,
                    height = height,
                    x = byteOrder.i16(body, infoOffset + 4),
                    y = byteOrder.i16(body, infoOffset + 6),
                    xOff = byteOrder.i16(body, infoOffset + 8),
                    yOff = byteOrder.i16(body, infoOffset + 10),
                    mask = mask,
                )
                imageOffset += glyphImageByteSize(format, width, height)
            }
            infoOffset += 12
        }
        state.addGlyphs(glyphSet, glyphs)
    }

    private fun renderFreeGlyphs(body: ByteArray) {
        if (body.size < 4) return
        val glyphSet = byteOrder.u32(body, 0)
        val glyphIds = mutableListOf<Int>()
        var offset = 4
        while (offset + 4 <= body.size) {
            glyphIds += byteOrder.u32(body, offset)
            offset += 4
        }
        state.removeGlyphs(glyphSet, glyphIds)
    }

    private fun renderCompositeGlyphs(minorOpcode: Int, body: ByteArray) {
        if (body.size < 24) return
        val operation = body[0].toInt() and 0xff
        val source = state.picture(byteOrder.u32(body, 4)) ?: return
        val destination = state.picture(byteOrder.u32(body, 8)) ?: return
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 20)
        val sourceY = byteOrder.i16(body, 22)
        val placementsByGlyphSet = compositeGlyphPlacements(minorOpcode, body)
        val origin = placementsByGlyphSet.values.firstOrNull()?.firstOrNull() ?: return
        for ((glyphSetId, placements) in placementsByGlyphSet) {
            state.compositeGlyphs(operation, source, destination, glyphSetId, sourceX, sourceY, origin.x, origin.y, placements)
        }
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawableId,
                kind = XDrawingKind.Text,
                foreground = 0,
                points = listOf(XPoint(byteOrder.i16(body, 20), byteOrder.i16(body, 22))),
                text = "RENDER.${XRender.operationName(minorOpcode)} glyphs=${body.size - 24}",
            ),
        )
    }

    private fun compositeGlyphPlacements(minorOpcode: Int, body: ByteArray): Map<Int, List<XGlyphPlacement>> {
        val glyphIdBytes = when (minorOpcode) {
            23 -> 1
            24 -> 2
            else -> 4
        }
        var glyphSetId = byteOrder.u32(body, 16)
        var x = 0
        var y = 0
        var offset = 24
        val result = linkedMapOf<Int, MutableList<XGlyphPlacement>>()
        while (offset + 8 <= body.size) {
            val length = body[offset].toInt() and 0xff
            val deltaX = byteOrder.i16(body, offset + 2)
            val deltaY = byteOrder.i16(body, offset + 4)
            if (length == 0xff) {
                if (offset + 12 > body.size) break
                x += deltaX
                y += deltaY
                glyphSetId = byteOrder.u32(body, offset + 8)
                offset += 12
                continue
            }

            x += deltaX
            y += deltaY
            offset += 8
            repeat(length) {
                if (offset + glyphIdBytes > body.size) return result
                val glyphId = when (glyphIdBytes) {
                    1 -> body[offset].toInt() and 0xff
                    2 -> byteOrder.u16(body, offset)
                    else -> byteOrder.u32(body, offset)
                }
                result.getOrPut(glyphSetId) { mutableListOf() } += XGlyphPlacement(glyphId, x, y)
                val glyph = state.glyph(glyphSetId, glyphId)
                x += glyph?.xOff ?: 0
                y += glyph?.yOff ?: 0
                offset += glyphIdBytes
            }
            offset = (offset + 3) and -4
        }
        return result
    }

    private fun renderFillRectangles(body: ByteArray) {
        if (body.size < 16) return
        val destination = state.picture(byteOrder.u32(body, 4)) ?: return
        val destinationDrawableId = destination.drawableId ?: return
        val pixel = XRender.argb32Pixel(
            red = byteOrder.u16(body, 8),
            green = byteOrder.u16(body, 10),
            blue = byteOrder.u16(body, 12),
            alpha = byteOrder.u16(body, 14),
        )
        val rectangles = rectangles(body, 16)
        val operation = body[0].toInt() and 0xff
        val targetPixel = if (operation == XRender.OpClear) 0 else pixel
        state.renderFillRectangles(operation, destination, targetPixel, rectangles)
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawableId,
                kind = XDrawingKind.FillRectangle,
                foreground = targetPixel,
                rectangles = rectangles,
                framebufferBacked = true,
            ),
        )
    }

    private fun renderCreateCursor(body: ByteArray) {
        if (body.size < 4) return
        val id = byteOrder.u32(body, 0)
        state.putCursor(id)
        own(id)
    }

    private fun renderSetPictureTransform(body: ByteArray) {
        if (body.size < 40) return
        val picture = byteOrder.u32(body, 0)
        val transform = (0 until 9).map { index -> byteOrder.u32(body, 4 + index * 4) }
        if (!isInvertibleTransform(transform)) {
            return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = 28, badValue = 0)
        }
        state.updatePictureTransform(picture, transform)
    }

    private fun isInvertibleTransform(transform: List<Int>): Boolean {
        if (transform.size != 9) return false
        fun fixed(index: Int) = BigInteger.valueOf(transform[index].toLong())
        val a = fixed(0)
        val b = fixed(1)
        val c = fixed(2)
        val d = fixed(3)
        val e = fixed(4)
        val f = fixed(5)
        val g = fixed(6)
        val h = fixed(7)
        val i = fixed(8)
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return determinant != BigInteger.ZERO
    }

    private fun renderQueryFilters(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 29, badValue = body.size)
        val drawableId = byteOrder.u32(body, 0)
        state.drawable(drawableId) ?: return writeError(error = 9, opcode = XRender.MajorOpcode, minorOpcode = 29, badValue = drawableId)
        val aliasBytes = RenderFilterAliases.size * 2
        val paddedAliasBytes = paddedLength(aliasBytes)
        val nameBytes = RenderFilterNames.sumOf { 1 + it.encodeToByteArray().size }
        val payloadBytes = paddedLength(paddedAliasBytes + nameBytes)
        val reply = reply(extra = 0, payloadUnits = payloadBytes / 4)
        byteOrder.put32(reply, 8, RenderFilterAliases.size)
        byteOrder.put32(reply, 12, RenderFilterNames.size)
        var offset = 32
        for (alias in RenderFilterAliases) {
            byteOrder.put16(reply, offset, alias)
            offset += 2
        }
        offset = 32 + paddedAliasBytes
        for (filter in RenderFilterNames) {
            val bytes = filter.encodeToByteArray()
            reply[offset] = bytes.size.toByte()
            bytes.copyInto(reply, offset + 1)
            offset += 1 + bytes.size
        }
        write(reply)
    }

    private fun renderSetPictureFilter(body: ByteArray) {
        if (body.size < 8) return
        val picture = byteOrder.u32(body, 0)
        val filterLength = byteOrder.u16(body, 4)
        if (body.size < 8 + filterLength) return
        val name = body.copyOfRange(8, 8 + filterLength).decodeToString()
        if (name !in RenderFilterNames) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = 0)
        }
        val valuesOffset = paddedLength(8 + filterLength)
        val values = mutableListOf<Int>()
        var offset = valuesOffset
        while (offset + 4 <= body.size) {
            values += byteOrder.u32(body, offset)
            offset += 4
        }
        state.updatePictureFilter(picture, name, values)
    }

    private fun renderCreateAnimCursor(body: ByteArray) {
        if (body.size < 4) return
        val id = byteOrder.u32(body, 0)
        state.putCursor(id)
        own(id)
    }

    private fun renderAddTraps(body: ByteArray) {
        if (body.size < 8) return
        val picture = state.picture(byteOrder.u32(body, 0)) ?: return
        if (picture.format != XRender.A8Format) return
        val xOffset = byteOrder.i16(body, 4)
        val yOffset = byteOrder.i16(body, 6)
        val traps = offsetTrapezoids(traps(body, 8), xOffset, yOffset)
        if (traps.isEmpty()) return
        state.addTraps(picture, traps)
    }

    private fun renderCreateSolidFill(body: ByteArray) {
        if (body.size < 12) return
        val id = byteOrder.u32(body, 0)
        state.putPicture(
            XPicture(
                id = id,
                drawableId = null,
                format = XRender.Argb32Format,
                solidPixel = XRender.argb32Pixel(
                    red = byteOrder.u16(body, 4),
                    green = byteOrder.u16(body, 6),
                    blue = byteOrder.u16(body, 8),
                    alpha = byteOrder.u16(body, 10),
                ),
            ),
        )
        own(id)
    }

    private fun renderCreateLinearGradient(body: ByteArray) {
        if (body.size < 24) return
        val id = byteOrder.u32(body, 0)
        val stopsCount = byteOrder.u32(body, 20)
        if (stopsCount < 0) return
        val colorOffset = 24L + stopsCount.toLong() * 4L
        val requiredSize = colorOffset + stopsCount.toLong() * 8L
        if (requiredSize > body.size) return
        val stops = ArrayList<Int>(stopsCount)
        repeat(stopsCount) { index ->
            stops += byteOrder.u32(body, 24 + index * 4)
        }
        val colors = ArrayList<Int>(stopsCount)
        repeat(stopsCount) { index ->
            val offset = colorOffset.toInt() + index * 8
            colors += XRender.argb32Pixel(
                red = byteOrder.u16(body, offset),
                green = byteOrder.u16(body, offset + 2),
                blue = byteOrder.u16(body, offset + 4),
                alpha = byteOrder.u16(body, offset + 6),
            )
        }
        val gradient = XLinearGradient(
            p1 = XFixedPoint(byteOrder.u32(body, 4), byteOrder.u32(body, 8)),
            p2 = XFixedPoint(byteOrder.u32(body, 12), byteOrder.u32(body, 16)),
            stops = stops,
            colors = colors,
        )
        state.putPicture(
            XPicture(
                id = id,
                drawableId = null,
                format = XRender.Argb32Format,
                linearGradient = gradient,
            ),
        )
        own(id)
    }

    private fun renderCreateRadialGradient(body: ByteArray) {
        if (body.size < 32) return
        val id = byteOrder.u32(body, 0)
        val stops = renderGradientStops(body, countOffset = 28, stopsOffset = 32) ?: return
        val gradient = XRadialGradient(
            inner = XFixedCircle(
                center = XFixedPoint(byteOrder.u32(body, 4), byteOrder.u32(body, 8)),
                radius = byteOrder.u32(body, 20),
            ),
            outer = XFixedCircle(
                center = XFixedPoint(byteOrder.u32(body, 12), byteOrder.u32(body, 16)),
                radius = byteOrder.u32(body, 24),
            ),
            stops = stops.first,
            colors = stops.second,
        )
        state.putPicture(
            XPicture(
                id = id,
                drawableId = null,
                format = XRender.Argb32Format,
                radialGradient = gradient,
            ),
        )
        own(id)
    }

    private fun renderCreateConicalGradient(body: ByteArray) {
        if (body.size < 20) return
        val id = byteOrder.u32(body, 0)
        val stops = renderGradientStops(body, countOffset = 16, stopsOffset = 20) ?: return
        val gradient = XConicalGradient(
            center = XFixedPoint(byteOrder.u32(body, 4), byteOrder.u32(body, 8)),
            angle = byteOrder.u32(body, 12),
            stops = stops.first,
            colors = stops.second,
        )
        state.putPicture(
            XPicture(
                id = id,
                drawableId = null,
                format = XRender.Argb32Format,
                conicalGradient = gradient,
            ),
        )
        own(id)
    }

    private fun renderGradientStops(body: ByteArray, countOffset: Int, stopsOffset: Int): Pair<List<Int>, List<Int>>? {
        val stopsCount = byteOrder.u32(body, countOffset)
        if (stopsCount < 0) return null
        val colorOffset = stopsOffset.toLong() + stopsCount.toLong() * 4L
        val requiredSize = colorOffset + stopsCount.toLong() * 8L
        if (requiredSize > body.size) return null
        val stops = ArrayList<Int>(stopsCount)
        repeat(stopsCount) { index ->
            stops += byteOrder.u32(body, stopsOffset + index * 4)
        }
        val colors = ArrayList<Int>(stopsCount)
        repeat(stopsCount) { index ->
            val offset = colorOffset + index * 8
            colors += XRender.argb32Pixel(
                red = byteOrder.u16(body, offset.toInt()),
                green = byteOrder.u16(body, offset.toInt() + 2),
                blue = byteOrder.u16(body, offset.toInt() + 4),
                alpha = byteOrder.u16(body, offset.toInt() + 6),
            )
        }
        return stops to colors
    }

    private fun glxGetVisualConfigs(body: ByteArray) {
        if (!glxScreenIsValid(body, offset = 0)) return
        val config = XGlx.visualConfig()
        val reply = reply(extra = 0, payloadUnits = config.size)
        byteOrder.put32(reply, 8, 1)
        byteOrder.put32(reply, 12, XGlx.VisualConfigValues)
        putIntArray(reply, 32, config)
        write(reply)
    }

    private fun glxGetFbConfigs(body: ByteArray) {
        if (!glxScreenIsValid(body, offset = 0)) return
        val config = XGlx.fbConfig()
        val reply = reply(extra = 0, payloadUnits = config.size)
        byteOrder.put32(reply, 8, 1)
        byteOrder.put32(reply, 12, XGlx.FbConfigAttributePairs)
        putIntArray(reply, 32, config)
        write(reply)
    }

    private fun glxQueryServerString(body: ByteArray) {
        if (!glxScreenIsValid(body, offset = 0)) return
        val name = if (body.size >= 8) byteOrder.u32(body, 4) else 0
        glxStringReply(XGlx.serverString(name))
    }

    private fun glxStringReply(value: String) {
        val bytes = value.encodeToByteArray()
        val reply = reply(extra = 0, payloadUnits = paddedLength(bytes.size) / 4)
        byteOrder.put32(reply, 12, bytes.size)
        bytes.copyInto(reply, 32)
        write(reply)
    }

    private fun glxCreateContext(body: ByteArray) {
        if (body.size < 20) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = 3, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val visual = byteOrder.u32(body, 4)
        val screen = byteOrder.u32(body, 8)
        state.putGlxContext(
            XGlxContext(
                id = context,
                fbConfigId = visual,
                screen = screen,
                renderType = XGlx.RgbaType,
                direct = body[16].toInt() != 0,
            ),
        )
        own(context)
    }

    private fun glxCreateNewContext(body: ByteArray) {
        if (body.size < 24) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateNewContext, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val fbConfig = byteOrder.u32(body, 4)
        val screen = byteOrder.u32(body, 8)
        val renderType = byteOrder.u32(body, 12)
        state.putGlxContext(
            XGlxContext(
                id = context,
                fbConfigId = fbConfig,
                screen = screen,
                renderType = renderType,
                direct = body[20].toInt() != 0,
            ),
        )
        own(context)
    }

    private fun glxCreateContextAttribs(body: ByteArray) {
        if (body.size < 24) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateContextAttribsARB, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val fbConfig = byteOrder.u32(body, 4)
        val screen = byteOrder.u32(body, 8)
        state.putGlxContext(
            XGlxContext(
                id = context,
                fbConfigId = fbConfig,
                screen = screen,
                renderType = XGlx.RgbaType,
                direct = body[16].toInt() != 0,
            ),
        )
        own(context)
    }

    private fun glxDestroyContext(body: ByteArray) {
        if (body.size >= 4) {
            val context = byteOrder.u32(body, 0)
            state.removeGlxContext(context)
            ownedResources.remove(context)
        }
    }

    private fun glxMakeCurrent(body: ByteArray, isContextCurrent: Boolean) {
        val contextOffset = if (isContextCurrent) 12 else 4
        if (body.size < contextOffset + 4) {
            return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = if (isContextCurrent) XGlx.MakeContextCurrent else 5, badValue = 0)
        }
        val context = byteOrder.u32(body, contextOffset)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, if (context == 0 || state.glxContext(context) != null) context else 0)
        write(reply)
    }

    private fun glxIsDirect(body: ByteArray) {
        if (body.size < 4) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.IsDirect, badValue = 0)
        val reply = reply(extra = 0, payloadUnits = 0)
        reply[8] = 0
        write(reply)
    }

    private fun glxScreenIsValid(body: ByteArray, offset: Int): Boolean {
        val screen = if (body.size >= offset + 4) byteOrder.u32(body, offset) else 0
        if (screen == 0) return true
        writeError(error = 2, opcode = XGlx.MajorOpcode, badValue = screen)
        return false
    }

    private fun glxDetail(minorOpcode: Int, body: ByteArray): String {
        fun hex(offset: Int): String = if (body.size >= offset + 4) byteOrder.u32(body, offset).toHex() else "n/a"
        fun u32(offset: Int): String = if (body.size >= offset + 4) byteOrder.u32(body, offset).toString() else "n/a"
        return when (minorOpcode) {
            XGlx.QueryVersion -> "client=${u32(0)}.${u32(4)}"
            3 -> "context=${hex(0)} visual=${hex(4)} screen=${u32(8)} direct=${body.getOrNull(16)?.toInt() == 1}"
            4 -> "context=${hex(0)}"
            5 -> "drawable=${hex(0)} context=${hex(4)} oldTag=${hex(8)}"
            XGlx.IsDirect -> "context=${hex(0)}"
            XGlx.GetVisualConfigs -> "screen=${u32(0)}"
            XGlx.QueryExtensionsString -> "screen=${u32(0)}"
            XGlx.QueryServerString -> "screen=${u32(0)} name=${u32(4)}"
            XGlx.ClientInfo -> "client=${u32(0)}.${u32(4)} bytes=${u32(8)}"
            XGlx.GetFBConfigs -> "screen=${u32(0)}"
            XGlx.CreateNewContext -> "context=${hex(0)} fbconfig=${hex(4)} screen=${u32(8)} renderType=${hex(12)} direct=${body.getOrNull(20)?.toInt() == 1}"
            XGlx.MakeContextCurrent -> "oldTag=${hex(0)} drawable=${hex(4)} readDrawable=${hex(8)} context=${hex(12)}"
            XGlx.CreateContextAttribsARB -> "context=${hex(0)} fbconfig=${hex(4)} screen=${u32(8)} share=${hex(12)} direct=${body.getOrNull(16)?.toInt() == 1} attribs=${u32(20)}"
            1, 2 -> "contextTag=${hex(0)}"
            8, 9, 11 -> "drawable/context=${hex(0)}"
            else -> ""
        }
    }

    private fun renderDetail(minorOpcode: Int, body: ByteArray): String {
        fun hex(offset: Int): String = if (body.size >= offset + 4) byteOrder.u32(body, offset).toHex() else "n/a"
        fun u32(offset: Int): String = if (body.size >= offset + 4) byteOrder.u32(body, offset).toString() else "n/a"
        fun i16(offset: Int): String = if (body.size >= offset + 2) byteOrder.i16(body, offset).toString() else "n/a"
        fun u16(offset: Int): String = if (body.size >= offset + 2) byteOrder.u16(body, offset).toString() else "n/a"
        return when (minorOpcode) {
            0 -> "client=${u32(0)}.${u32(4)}"
            2 -> "format=${hex(0)}"
            4 -> "picture=${hex(0)} drawable=${hex(4)} format=${hex(8)} mask=${hex(12)}"
            5 -> "picture=${hex(0)} mask=${hex(4)}"
            6 -> "picture=${hex(0)} origin=${i16(4)},${i16(6)} rects=${(body.size - 8).coerceAtLeast(0) / 8}"
            7 -> "picture=${hex(0)}"
            8 -> "op=${body.getOrNull(0)?.toInt()?.and(0xff) ?: "n/a"} src=${hex(4)} mask=${hex(8)} dst=${hex(12)} dst=${i16(24)},${i16(26)} ${u16(28)}x${u16(30)}"
            10 -> "op=${body.getOrNull(0)?.toInt()?.and(0xff) ?: "n/a"} src=${hex(4)} dst=${hex(8)} maskFormat=${hex(12)} srcOrigin=${i16(16)},${i16(18)} traps=${(body.size - 20).coerceAtLeast(0) / 40}"
            11 -> "op=${body.getOrNull(0)?.toInt()?.and(0xff) ?: "n/a"} src=${hex(4)} dst=${hex(8)} maskFormat=${hex(12)} srcOrigin=${i16(16)},${i16(18)} triangles=${(body.size - 20).coerceAtLeast(0) / 24}"
            12, 13 -> "op=${body.getOrNull(0)?.toInt()?.and(0xff) ?: "n/a"} src=${hex(4)} dst=${hex(8)} maskFormat=${hex(12)} srcOrigin=${i16(16)},${i16(18)} points=${(body.size - 20).coerceAtLeast(0) / 8}"
            17 -> "glyphSet=${hex(0)} format=${hex(4)}"
            18 -> "glyphSet=${hex(0)} existing=${hex(4)}"
            19 -> "glyphSet=${hex(0)}"
            20 -> "glyphSet=${hex(0)} glyphs=${u32(4)}"
            22 -> "glyphSet=${hex(0)} glyphs=${(body.size - 4).coerceAtLeast(0) / 4}"
            23, 24, 25 -> "op=${body.getOrNull(0)?.toInt()?.and(0xff) ?: "n/a"} src=${hex(4)} dst=${hex(8)} glyphSet=${hex(16)} bytes=${(body.size - 24).coerceAtLeast(0)}"
            26 -> "op=${body.getOrNull(0)?.toInt()?.and(0xff) ?: "n/a"} dst=${hex(4)} color=${u16(8)},${u16(10)},${u16(12)},${u16(14)} rects=${(body.size - 16).coerceAtLeast(0) / 8}"
            27 -> "cursor=${hex(0)} source=${hex(4)} hotspot=${u16(8)},${u16(10)}"
            28 -> "picture=${hex(0)} transform=${(body.size - 4).coerceAtLeast(0) / 4}"
            29 -> "drawable=${hex(0)}"
            30 -> "picture=${hex(0)} filterLength=${u16(4)}"
            31 -> "cursor=${hex(0)} elements=${(body.size - 4).coerceAtLeast(0) / 8}"
            32 -> "picture=${hex(0)} offset=${i16(4)},${i16(6)} traps=${(body.size - 8).coerceAtLeast(0) / 24}"
            33 -> "picture=${hex(0)} color=${u16(4)},${u16(6)},${u16(8)},${u16(10)}"
            34 -> "picture=${hex(0)} p1=${hex(4)},${hex(8)} p2=${hex(12)},${hex(16)} stops=${u32(20)}"
            35, 36 -> "picture=${hex(0)}"
            else -> ""
        }
    }

    private fun createWindow(body: ByteArray) {
        if (body.size < 28) return writeError(16, 1, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val parent = byteOrder.u32(body, 4)
        val attributes = windowAttributeValues(body, maskOffset = 24, valuesOffset = 28)
        val window = XWindow(
            id = id,
            parentId = parent,
            x = byteOrder.i16(body, 8),
            y = byteOrder.i16(body, 10),
            width = byteOrder.u16(body, 12),
            height = byteOrder.u16(body, 14),
            borderWidth = byteOrder.u16(body, 16),
            backgroundPixel = attributes.backgroundPixel ?: 0x00ff_ffff,
            backgroundPixmapId = attributes.backgroundPixmapId?.takeIf { it != 0 },
        )
        if (attributes.backgroundPixmapId != null) {
            System.err.println("core seq=$sequence CreateWindow window=${id.toHex()} backgroundPixmap=${attributes.backgroundPixmapId.toHex()}")
        }
        state.putWindow(window)
        own(id)
        attributes.eventMask?.let { state.selectEvents(this, id, it) }
    }

    private fun changeWindowAttributes(body: ByteArray) {
        if (body.size < 8) return
        val windowId = byteOrder.u32(body, 0)
        val attributes = windowAttributeValues(body, maskOffset = 4, valuesOffset = 8)
        if (attributes.backgroundPixel != null || attributes.backgroundPixmapId != null) {
            state.updateWindowAttributes(windowId, backgroundPixel = attributes.backgroundPixel, backgroundPixmapId = attributes.backgroundPixmapId)
            System.err.println(
                "core seq=$sequence ChangeWindowAttributes window=${windowId.toHex()}" +
                    " backgroundPixel=${attributes.backgroundPixel?.toHex() ?: "none"}" +
                    " backgroundPixmap=${attributes.backgroundPixmapId?.toHex() ?: "none"}",
            )
        }
        attributes.eventMask?.let { state.selectEvents(this, windowId, it) }
    }

    private fun destroyWindow(body: ByteArray) {
        if (body.size >= 4) {
            ownedResources.removeAll(state.removeWindow(byteOrder.u32(body, 0)))
        }
    }

    private fun reparentWindow(body: ByteArray) {
        if (body.size < 12) return
        state.reparentWindow(
            id = byteOrder.u32(body, 0),
            parentId = byteOrder.u32(body, 4),
            x = byteOrder.i16(body, 8),
            y = byteOrder.i16(body, 10),
        )
    }

    private fun mapWindow(body: ByteArray) {
        if (body.size < 4) return
        val window = state.mapWindow(byteOrder.u32(body, 0)) ?: return
        state.paintWindowBackground(window.id)
        sendMapNotify(window)
        sendExpose(window)
    }

    private fun unmapWindow(body: ByteArray) {
        if (body.size >= 4) state.unmapWindow(byteOrder.u32(body, 0))
    }

    private fun mapSubwindows(body: ByteArray) {
        if (body.size < 4) return
        for (child in state.childrenOf(byteOrder.u32(body, 0))) {
            state.mapWindow(child.id)
            sendMapNotify(child)
            sendExpose(child)
        }
    }

    private fun unmapSubwindows(body: ByteArray) {
        if (body.size < 4) return
        for (child in state.childrenOf(byteOrder.u32(body, 0))) {
            state.unmapWindow(child.id)
        }
    }

    private fun configureWindow(body: ByteArray) {
        if (body.size < 6) return
        val window = state.window(byteOrder.u32(body, 0)) ?: return
        val mask = byteOrder.u16(body, 4)
        var offset = 8
        fun next(): Int {
            val value = byteOrder.u32(body, offset)
            offset += 4
            return value
        }
        val x = if ((mask and 0x0001) != 0) next() else null
        val y = if ((mask and 0x0002) != 0) next() else null
        val width = if ((mask and 0x0004) != 0) next() else null
        val height = if ((mask and 0x0008) != 0) next() else null
        val borderWidth = if ((mask and 0x0010) != 0) next() else null
        val configured = state.configureWindow(window.id, x = x, y = y, width = width, height = height, borderWidth = borderWidth) ?: return
        if (configured.mapped) {
            sendConfigureNotify(configured)
            if (width != null || height != null) sendExpose(configured)
        }
    }

    private fun getWindowAttributes(body: ByteArray) {
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 3, badValue = byteOrder.u32(body, 0))
        val reply = reply(extra = 0, payloadUnits = 3)
        byteOrder.put32(reply, 8, X11Ids.RootVisual)
        byteOrder.put16(reply, 12, 1)
        reply[14] = 1
        reply[15] = 1
        byteOrder.put32(reply, 16, -1)
        byteOrder.put32(reply, 20, 0)
        reply[24] = 0
        reply[25] = 1
        reply[26] = if (window.mapped) 2 else 0
        reply[27] = 0
        byteOrder.put32(reply, 28, X11Ids.DefaultColormap)
        byteOrder.put32(reply, 32, 0)
        byteOrder.put32(reply, 36, 0)
        byteOrder.put16(reply, 40, 0)
        write(reply)
    }

    private fun getGeometry(body: ByteArray) {
        val drawable = state.drawable(byteOrder.u32(body, 0)) ?: return writeError(9, 14, badValue = byteOrder.u32(body, 0))
        val reply = reply(extra = drawable.depth, payloadUnits = 0)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put16(reply, 12, drawable.x)
        byteOrder.put16(reply, 14, drawable.y)
        byteOrder.put16(reply, 16, drawable.width)
        byteOrder.put16(reply, 18, drawable.height)
        byteOrder.put16(reply, 20, drawable.borderWidth)
        write(reply)
    }

    private fun queryTree(body: ByteArray) {
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 15, badValue = byteOrder.u32(body, 0))
        val children = state.childrenOf(window.id)
        val reply = reply(extra = 0, payloadUnits = children.size)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put32(reply, 12, window.parentId)
        byteOrder.put16(reply, 16, children.size)
        var offset = 32
        for (child in children) {
            byteOrder.put32(reply, offset, child.id)
            offset += 4
        }
        write(reply)
    }

    private fun internAtom(onlyIfExistsOpcode: Int, body: ByteArray) {
        val onlyIfExists = onlyIfExistsOpcode != 0
        val nameLength = byteOrder.u16(body, 0)
        val name = body.copyOfRange(4, 4 + nameLength).decodeToString()
        val atom = state.internAtom(name, onlyIfExists)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, atom)
        write(reply)
    }

    private fun getAtomName(body: ByteArray) {
        val atom = byteOrder.u32(body, 0)
        val name = state.atomName(atom) ?: return writeError(5, 17, badValue = atom)
        val bytes = name.encodeToByteArray()
        val reply = reply(extra = 0, payloadUnits = paddedLength(bytes.size) / 4)
        byteOrder.put16(reply, 8, bytes.size)
        bytes.copyInto(reply, 32)
        write(reply)
    }

    private fun changeProperty(mode: Int, body: ByteArray) {
        if (body.size < 20) return
        val window = state.window(byteOrder.u32(body, 0)) ?: return
        val property = byteOrder.u32(body, 4)
        val type = byteOrder.u32(body, 8)
        val format = body[12].toInt() and 0xff
        val units = byteOrder.u32(body, 16)
        val byteLength = units * (format / 8)
        val data = body.copyOfRange(20, 20 + byteLength.coerceAtMost(body.size - 20))
        val existing = window.properties[property]
        window.properties[property] = if (mode == 1 && existing != null) {
            existing.copy(data = existing.data + data)
        } else {
            XProperty(type = type, format = format, data = data)
        }
    }

    private fun deleteProperty(body: ByteArray) {
        state.window(byteOrder.u32(body, 0))?.properties?.remove(byteOrder.u32(body, 4))
    }

    private fun getProperty(deleteOpcode: Int, body: ByteArray) {
        val delete = deleteOpcode != 0
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 20, badValue = byteOrder.u32(body, 0))
        val propertyId = byteOrder.u32(body, 4)
        val requestedType = byteOrder.u32(body, 8)
        val longOffset = byteOrder.u32(body, 12).toLong().coerceAtLeast(0L).saturatingTimes4()
        val longLength = byteOrder.u32(body, 16).toLong().coerceAtLeast(0L).saturatingTimes4()
        val property = window.properties[propertyId]
        if (property == null || (requestedType != 0 && requestedType != property.type)) {
            val reply = reply(extra = 0, payloadUnits = 0)
            byteOrder.put32(reply, 8, 0)
            byteOrder.put32(reply, 12, 0)
            byteOrder.put32(reply, 16, 0)
            return write(reply)
        }
        val available = property.data.drop(longOffset.coerceAtMost(property.data.size.toLong()).toInt()).toByteArray()
        val value = available.take(longLength.coerceAtMost(available.size.toLong()).toInt()).toByteArray()
        val bytesAfter = (available.size - value.size).coerceAtLeast(0)
        val reply = reply(extra = property.format, payloadUnits = paddedLength(value.size) / 4)
        byteOrder.put32(reply, 8, property.type)
        byteOrder.put32(reply, 12, bytesAfter)
        byteOrder.put32(reply, 16, value.size / (property.format / 8))
        value.copyInto(reply, 32)
        write(reply)
        if (delete && bytesAfter == 0) window.properties.remove(propertyId)
    }

    private fun listProperties(body: ByteArray) {
        val window = state.window(byteOrder.u32(body, 0)) ?: return writeError(3, 21, badValue = byteOrder.u32(body, 0))
        val keys = window.properties.keys.sorted()
        val reply = reply(extra = 0, payloadUnits = keys.size)
        byteOrder.put16(reply, 8, keys.size)
        var offset = 32
        for (key in keys) {
            byteOrder.put32(reply, offset, key)
            offset += 4
        }
        write(reply)
    }

    private fun getSelectionOwner() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, 0)
        write(reply)
    }

    private fun queryPointer() {
        val reply = reply(extra = 1, payloadUnits = 0)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put32(reply, 12, 0)
        byteOrder.put16(reply, 16, 0)
        byteOrder.put16(reply, 18, 0)
        byteOrder.put16(reply, 20, 0)
        byteOrder.put16(reply, 22, 0)
        byteOrder.put16(reply, 24, 0)
        write(reply)
    }

    private fun translateCoordinates(body: ByteArray) {
        val reply = reply(extra = 1, payloadUnits = 0)
        byteOrder.put32(reply, 8, 0)
        byteOrder.put16(reply, 12, byteOrder.i16(body, 8))
        byteOrder.put16(reply, 14, byteOrder.i16(body, 10))
        write(reply)
    }

    private fun getInputFocus() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, state.snapshot().focusWindowId)
        write(reply)
    }

    private fun queryKeymap() {
        write(reply(extra = 0, payloadUnits = 2))
    }

    private fun openFont(body: ByteArray) {
        if (body.size >= 4) {
            val id = byteOrder.u32(body, 0)
            state.putFont(id)
            own(id)
        }
    }

    private fun closeResource(body: ByteArray) {
        if (body.size >= 4) {
            val id = byteOrder.u32(body, 0)
            state.removeResource(id)
            ownedResources.remove(id)
        }
    }

    private fun queryFont(body: ByteArray) {
        val reply = reply(extra = 0, payloadUnits = 7)
        putCharInfo(reply, 8)
        putCharInfo(reply, 24)
        byteOrder.put16(reply, 40, 0)
        byteOrder.put16(reply, 42, 255)
        byteOrder.put16(reply, 44, '?'.code)
        byteOrder.put16(reply, 46, 0)
        reply[48] = 0
        reply[49] = 0
        reply[50] = 0
        reply[51] = 1
        byteOrder.put16(reply, 52, XFramebuffer.TextAscent)
        byteOrder.put16(reply, 54, XFramebuffer.TextDescent)
        byteOrder.put32(reply, 56, 0)
        write(reply)
    }

    private fun queryTextExtents(oddLength: Int, body: ByteArray) {
        if (body.size < 4) return writeError(error = 2, opcode = 48, badValue = 0)
        val padBytes = if (oddLength != 0) 2 else 0
        val stringBytes = (body.size - 4 - padBytes).coerceAtLeast(0)
        val charCount = stringBytes / 2
        val overallWidth = charCount * XFramebuffer.TextCellWidth
        val hasText = charCount > 0
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, XFramebuffer.TextAscent)
        byteOrder.put16(reply, 10, XFramebuffer.TextDescent)
        byteOrder.put16(reply, 12, if (hasText) XFramebuffer.TextAscent else 0)
        byteOrder.put16(reply, 14, if (hasText) XFramebuffer.TextDescent else 0)
        byteOrder.put32(reply, 16, overallWidth)
        byteOrder.put32(reply, 20, 0)
        byteOrder.put32(reply, 24, overallWidth)
        write(reply)
    }

    private fun listFonts() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0)
        write(reply)
    }

    private fun getFontPath() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0)
        write(reply)
    }

    private fun createPixmap(depth: Int, body: ByteArray) {
        if (body.size < 12) return
        val id = byteOrder.u32(body, 0)
        val drawableId = byteOrder.u32(body, 4)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 53, badValue = id)
        val drawable = state.drawable(drawableId) ?: return writeError(error = 9, opcode = 53, badValue = drawableId)
        val width = byteOrder.u16(body, 8)
        val height = byteOrder.u16(body, 10)
        System.err.println("core seq=$sequence CreatePixmap pixmap=${id.toHex()} depth=$depth ${width}x$height drawable=${drawableId.toHex()}")
        state.putPixmap(
            XPixmap(
                id = id,
                width = width,
                height = height,
                depth = depth,
                rootId = drawable.rootId,
            ),
        )
        own(id)
    }

    private fun createGc(body: ByteArray) {
        if (body.size < 12) return
        val id = byteOrder.u32(body, 0)
        val drawableId = byteOrder.u32(body, 4)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 55, badValue = id)
        val drawable = state.drawable(drawableId) ?: return writeError(error = 9, opcode = 55, badValue = drawableId)
        val mask = byteOrder.u32(body, 8)
        if (!validateGcValues(mask, body, 12, opcode = 55)) return
        state.putGc(XGraphicsContext(id = id, drawableRootId = drawable.rootId, drawableDepth = drawable.depth))
        own(id)
        applyGcValues(id, mask, body, 12, opcode = 55)
    }

    private fun changeGc(body: ByteArray) {
        if (body.size < 8) return
        val id = byteOrder.u32(body, 0)
        if (!state.hasGc(id)) return writeError(error = 13, opcode = 56, badValue = id)
        applyGcValues(id, byteOrder.u32(body, 4), body, 8, opcode = 56)
    }

    private fun copyGc(body: ByteArray) {
        if (body.size < 12) return
        val sourceId = byteOrder.u32(body, 0)
        val destinationId = byteOrder.u32(body, 4)
        val mask = byteOrder.u32(body, 8)
        if (!state.hasGc(sourceId)) return writeError(error = 13, opcode = 57, badValue = sourceId)
        if (!state.hasGc(destinationId)) return writeError(error = 13, opcode = 57, badValue = destinationId)
        if (!state.canCopyGc(sourceId, destinationId)) return writeError(error = 8, opcode = 57, badValue = 0)
        if ((mask and GcValueMask.inv()) != 0) return writeError(error = 2, opcode = 57, badValue = mask)
        state.copyGc(sourceId, destinationId, mask)
    }

    private fun setDashes(body: ByteArray) {
        if (body.size < 8) return
        val gcId = byteOrder.u32(body, 0)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 58, badValue = gcId)
        val dashOffset = byteOrder.u16(body, 4)
        val dashCount = byteOrder.u16(body, 6)
        if (dashCount <= 0) return writeError(error = 2, opcode = 58, badValue = dashCount)
        if (body.size < 8 + dashCount) return
        val dashes = (0 until dashCount).map { body[8 + it].toInt() and 0xff }
        val invalidDash = dashes.firstOrNull { it == 0 }
        if (invalidDash != null) return writeError(error = 2, opcode = 58, badValue = invalidDash)
        state.updateGc(id = gcId, dashOffset = dashOffset, dashes = dashes)
    }

    private fun setClipRectangles(ordering: Int, body: ByteArray) {
        if (body.size < 8) return
        if (ordering !in 0..3) return writeError(error = 2, opcode = 59, badValue = ordering)
        val gcId = byteOrder.u32(body, 0)
        state.updateGcClip(
            id = gcId,
            clipXOrigin = byteOrder.i16(body, 4),
            clipYOrigin = byteOrder.i16(body, 6),
            clipRectangles = rectangles(body, 8),
        )
    }

    private fun clearArea(body: ByteArray) {
        if (body.size < 12) return
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return
        val x = byteOrder.i16(body, 4)
        val y = byteOrder.i16(body, 6)
        val rectangle = XRectangleCommand(
            x = x,
            y = y,
            width = byteOrder.u16(body, 8).takeIf { it > 0 } ?: (window.width - x),
            height = byteOrder.u16(body, 10).takeIf { it > 0 } ?: (window.height - y),
        )
        state.paintWindowBackground(windowId, rectangle)
        state.draw(
            XDrawingCommand(
                drawableId = windowId,
                kind = XDrawingKind.Clear,
                foreground = window.backgroundPixel,
                rectangles = listOf(rectangle),
                framebufferBacked = true,
            ),
        )
    }

    private fun copyArea(body: ByteArray) {
        if (body.size < 24) return
        val gc = state.gc(byteOrder.u32(body, 8))
        val sourceDrawable = byteOrder.u32(body, 0)
        val destinationDrawable = byteOrder.u32(body, 4)
        val sourceX = byteOrder.i16(body, 12)
        val sourceY = byteOrder.i16(body, 14)
        val destinationX = byteOrder.i16(body, 16)
        val destinationY = byteOrder.i16(body, 18)
        val width = byteOrder.u16(body, 20)
        val height = byteOrder.u16(body, 22)
        System.err.println(
            "core seq=$sequence CopyArea src=${sourceDrawable.toHex()} dst=${destinationDrawable.toHex()}" +
                " srcXY=$sourceX,$sourceY dstXY=$destinationX,$destinationY ${width}x$height",
        )
        val image = state.copyArea(
            sourceDrawableId = sourceDrawable,
            destinationDrawableId = destinationDrawable,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            clipRectangles = gc.effectiveClipRectangles(),
            function = gc.function,
            planeMask = gc.planeMask,
        ) ?: return
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawable,
                kind = XDrawingKind.CopyArea,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                rectangles = listOf(
                    XRectangleCommand(
                        x = destinationX,
                        y = destinationY,
                        width = width,
                        height = height,
                    ),
                ),
                imageDataUri = XFramebuffer.imageDataUri(image),
                sourceDrawableId = sourceDrawable,
                framebufferBacked = true,
            ),
        )
    }

    private fun copyPlane(body: ByteArray) {
        if (body.size < 28) return
        val gc = state.gc(byteOrder.u32(body, 8))
        val sourceDrawable = byteOrder.u32(body, 0)
        val destinationDrawable = byteOrder.u32(body, 4)
        val sourceX = byteOrder.i16(body, 12)
        val sourceY = byteOrder.i16(body, 14)
        val destinationX = byteOrder.i16(body, 16)
        val destinationY = byteOrder.i16(body, 18)
        val width = byteOrder.u16(body, 20)
        val height = byteOrder.u16(body, 22)
        val bitPlane = byteOrder.u32(body, 24)
        if (bitPlane == 0 || bitPlane.countOneBits() != 1) {
            return writeError(error = 2, opcode = 63, badValue = bitPlane)
        }
        val image = state.copyPlane(
            sourceDrawableId = sourceDrawable,
            destinationDrawableId = destinationDrawable,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            bitPlane = bitPlane,
            foreground = gc.foreground,
            background = gc.background,
            clipRectangles = gc.effectiveClipRectangles(),
            function = gc.function,
            planeMask = gc.planeMask,
        ) ?: return
        state.draw(
            XDrawingCommand(
                drawableId = destinationDrawable,
                kind = XDrawingKind.CopyPlane,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                rectangles = listOf(
                    XRectangleCommand(
                        x = destinationX,
                        y = destinationY,
                        width = width,
                        height = height,
                    ),
                ),
                imageDataUri = XFramebuffer.imageDataUri(image),
                sourceDrawableId = sourceDrawable,
                framebufferBacked = true,
            ),
        )
    }

    private fun polyPoint(coordMode: Int, body: ByteArray) {
        if (body.size < 12) return
        if (coordMode !in 0..1) return writeError(error = 2, opcode = 64, badValue = coordMode)
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val points = points(body, 8, coordMode)
        state.drawPoints(drawableId, gc.foreground, points, lineWidth = 1, clipRectangles = gc.effectiveClipRectangles(), function = gc.function, planeMask = gc.planeMask)
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.FillRectangle,
                foreground = gc.foreground,
                rectangles = points.map { XRectangleCommand(it.x, it.y, 1, 1) },
                framebufferBacked = true,
            ),
        )
    }

    private fun polyLine(coordMode: Int, body: ByteArray) {
        if (body.size < 12) return
        if (coordMode !in 0..1) return writeError(error = 2, opcode = 65, badValue = coordMode)
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val points = points(body, 8, coordMode)
        state.drawPolyline(
            drawableId,
            gc.foreground,
            gc.background,
            points,
            gc.lineWidth,
            gc.lineStyle,
            gc.dashOffset,
            gc.dashes,
            gc.effectiveClipRectangles(),
            gc.function,
            gc.planeMask,
        )
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.Line,
                foreground = gc.foreground,
                background = gc.background,
                lineWidth = gc.lineWidth,
                lineStyle = gc.lineStyle,
                dashOffset = gc.dashOffset,
                dashes = gc.dashes,
                points = points,
                framebufferBacked = true,
            ),
        )
    }

    private fun polySegment(body: ByteArray) {
        if (body.size < 16) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val points = mutableListOf<XPoint>()
        var offset = 8
        while (offset + 8 <= body.size) {
            points += XPoint(byteOrder.i16(body, offset), byteOrder.i16(body, offset + 2))
            points += XPoint(byteOrder.i16(body, offset + 4), byteOrder.i16(body, offset + 6))
            offset += 8
        }
        state.drawSegments(
            drawableId,
            gc.foreground,
            gc.background,
            points,
            gc.lineWidth,
            gc.lineStyle,
            gc.dashOffset,
            gc.dashes,
            gc.effectiveClipRectangles(),
            gc.function,
            gc.planeMask,
        )
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.Segment,
                foreground = gc.foreground,
                background = gc.background,
                lineWidth = gc.lineWidth,
                lineStyle = gc.lineStyle,
                dashOffset = gc.dashOffset,
                dashes = gc.dashes,
                points = points,
                framebufferBacked = true,
            ),
        )
    }

    private fun polyRectangle(body: ByteArray, kind: XDrawingKind) {
        if (body.size < 16) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val rectangles = rectangles(body, 8)
        when (kind) {
            XDrawingKind.FillRectangle -> state.fillRectangles(
                drawableId = drawableId,
                pixel = gc.foreground,
                rectangles = rectangles,
                clipRectangles = gc.effectiveClipRectangles(),
                function = gc.function,
                planeMask = gc.planeMask,
                fillStyle = gc.fillStyle,
                background = gc.background,
                tilePixmap = gc.tilePixmap,
                stipplePixmap = gc.stipplePixmap,
                tileStippleXOrigin = gc.tileStippleXOrigin,
                tileStippleYOrigin = gc.tileStippleYOrigin,
            )
            XDrawingKind.Rectangle -> state.drawRectangleOutlines(drawableId, gc.foreground, rectangles, gc.lineWidth, gc.effectiveClipRectangles(), gc.function, gc.planeMask)
            else -> Unit
        }
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = kind,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                rectangles = rectangles,
                framebufferBacked = kind == XDrawingKind.FillRectangle || kind == XDrawingKind.Rectangle,
            ),
        )
    }

    private fun polyArc(body: ByteArray, filled: Boolean) {
        if (body.size < 20) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val arcs = arcs(body, 8)
        if (filled) {
            state.fillArcs(
                drawableId = drawableId,
                pixel = gc.foreground,
                arcs = arcs,
                arcMode = gc.arcMode,
                clipRectangles = gc.effectiveClipRectangles(),
                function = gc.function,
                planeMask = gc.planeMask,
                fillStyle = gc.fillStyle,
                background = gc.background,
                tilePixmap = gc.tilePixmap,
                stipplePixmap = gc.stipplePixmap,
                tileStippleXOrigin = gc.tileStippleXOrigin,
                tileStippleYOrigin = gc.tileStippleYOrigin,
            )
        } else {
            state.drawArcs(drawableId, gc.foreground, arcs, gc.lineWidth, gc.effectiveClipRectangles(), gc.function, gc.planeMask)
        }
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = if (filled) XDrawingKind.FillArc else XDrawingKind.Arc,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                arcs = arcs,
                framebufferBacked = true,
            ),
        )
    }

    private fun fillPoly(body: ByteArray) {
        if (body.size < 16) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val shape = body[8].toInt() and 0xff
        if (shape !in 0..2) return writeError(error = 2, opcode = 69, badValue = shape)
        val coordMode = body[9].toInt() and 0xff
        if (coordMode !in 0..1) return writeError(error = 2, opcode = 69, badValue = coordMode)
        val points = points(body, 12, coordMode)
        state.fillPolygon(
            drawableId = drawableId,
            pixel = gc.foreground,
            points = points,
            fillRule = gc.fillRule,
            clipRectangles = gc.effectiveClipRectangles(),
            function = gc.function,
            planeMask = gc.planeMask,
            fillStyle = gc.fillStyle,
            background = gc.background,
            tilePixmap = gc.tilePixmap,
            stipplePixmap = gc.stipplePixmap,
            tileStippleXOrigin = gc.tileStippleXOrigin,
            tileStippleYOrigin = gc.tileStippleYOrigin,
        )
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.FillPoly,
                foreground = gc.foreground,
                lineWidth = gc.lineWidth,
                points = points,
                framebufferBacked = true,
            ),
        )
    }

    private fun putImage(format: Int, body: ByteArray) {
        if (body.size < 20) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val width = byteOrder.u16(body, 8)
        val height = byteOrder.u16(body, 10)
        val x = byteOrder.i16(body, 12)
        val y = byteOrder.i16(body, 14)
        val image = decodePutImage(format = format, width = width, height = height, depth = body[17].toInt() and 0xff, data = body.copyOfRange(20, body.size))
        val imageDataUri = image?.let { XFramebuffer.imageDataUri(it) }
        if (image != null) {
            state.putImage(drawableId, x, y, image, clipRectangles = gc.effectiveClipRectangles(), function = gc.function, planeMask = gc.planeMask)
        }
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.PutImage,
                foreground = gc.foreground,
                rectangles = listOf(
                    XRectangleCommand(
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                    ),
                ),
                imageDataUri = imageDataUri,
                framebufferBacked = image != null,
            ),
        )
    }

    private fun polyText(body: ByteArray, is16Bit: Boolean) {
        if (body.size < 12) return
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val runs = decodePolyText(body, is16Bit)
        if (runs.isEmpty()) return
        for (run in runs) {
            state.drawText(
                drawableId = drawableId,
                x = run.x,
                baselineY = run.y,
                text = run.text,
                foreground = gc.foreground,
                clipRectangles = gc.effectiveClipRectangles(),
                function = gc.function,
                planeMask = gc.planeMask,
            )
        }
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.Text,
                foreground = gc.foreground,
                background = gc.background,
                points = runs.map { XPoint(it.x, it.y) },
                text = runs.joinToString("") { it.text },
                framebufferBacked = true,
            ),
        )
    }

    private fun imageText(length: Int, body: ByteArray, is16Bit: Boolean) {
        if (body.size < 12) return
        val byteLength = length * if (is16Bit) 2 else 1
        val textBytes = body.copyOfRange(12, (12 + byteLength).coerceAtMost(body.size))
        val gc = state.gc(byteOrder.u32(body, 4))
        val drawableId = byteOrder.u32(body, 0)
        val x = byteOrder.i16(body, 8)
        val y = byteOrder.i16(body, 10)
        val text = if (is16Bit) decodeText16(textBytes) else decodeText8(textBytes)
        state.drawText(
            drawableId = drawableId,
            x = x,
            baselineY = y,
            text = text,
            foreground = gc.foreground,
            background = gc.background,
            clipRectangles = gc.effectiveClipRectangles(),
            function = XGraphicsContext.GXcopy,
            planeMask = gc.planeMask,
        )
        state.draw(
            XDrawingCommand(
                drawableId = drawableId,
                kind = XDrawingKind.Text,
                foreground = gc.foreground,
                background = gc.background,
                points = listOf(XPoint(x, y)),
                text = text,
                framebufferBacked = true,
            ),
        )
    }

    private fun getImage(format: Int, body: ByteArray) {
        if (body.size < 16) return writeError(error = 2, opcode = 73, badValue = 0)
        if (format !in 1..2) return writeError(error = 2, opcode = 73, badValue = format)
        val drawableId = byteOrder.u32(body, 0)
        val drawable = state.drawable(drawableId) ?: return writeError(error = 9, opcode = 73, badValue = drawableId)
        val x = byteOrder.i16(body, 4)
        val y = byteOrder.i16(body, 6)
        val width = byteOrder.u16(body, 8)
        val height = byteOrder.u16(body, 10)
        if (x < 0 || y < 0 || x + width > drawable.width || y + height > drawable.height) {
            return writeError(error = 8, opcode = 73, badValue = drawableId)
        }
        val planeMask = byteOrder.u32(body, 12)
        val image = state.getImage(
            drawableId = drawableId,
            x = x,
            y = y,
            width = width,
            height = height,
        ) ?: return writeError(error = 11, opcode = 73, badValue = width * height)
        val bytes = when (format) {
            1 -> encodeXyPixmap(image, drawable.depth, planeMask)
            else -> encodeZPixmap(image, planeMask)
        }
        val reply = reply(extra = drawable.depth, payloadUnits = bytes.size / 4)
        byteOrder.put32(reply, 8, if (state.window(drawableId) != null) X11Ids.RootVisual else 0)
        byteOrder.put32(reply, 12, bytes.size)
        bytes.copyInto(reply, 32)
        write(reply)
    }

    private fun encodeZPixmap(image: XImagePixels, planeMask: Int): ByteArray {
        val bytes = ByteArray(image.width * image.height * 4)
        image.pixels.forEachIndexed { index, pixel ->
            byteOrder.put32(bytes, index * 4, pixel and planeMask)
        }
        return bytes
    }

    private fun encodeXyPixmap(image: XImagePixels, depth: Int, planeMask: Int): ByteArray {
        val effectiveDepth = depth.coerceIn(0, 32)
        val drawableMask = if (effectiveDepth >= 32) -1 else (1 shl effectiveDepth) - 1
        val planes = (0 until effectiveDepth).filter { bit -> (planeMask and drawableMask and (1 shl bit)) != 0 }
        val stride = paddedLength((image.width + 7) / 8)
        val bytes = ByteArray(stride * image.height * planes.size)
        for ((planeIndex, bit) in planes.withIndex()) {
            val planeOffset = planeIndex * stride * image.height
            for (y in 0 until image.height) {
                val rowOffset = planeOffset + y * stride
                for (x in 0 until image.width) {
                    val pixel = image.pixels[y * image.width + x]
                    if ((pixel and (1 shl bit)) == 0) continue
                    bytes[rowOffset + x / 8] = (bytes[rowOffset + x / 8].toInt() or (1 shl (x % 8))).toByte()
                }
            }
        }
        return bytes
    }

    private fun createColormap(body: ByteArray) {
        if (body.size >= 4) {
            val id = byteOrder.u32(body, 0)
            state.putColormap(id)
            own(id)
        }
    }

    private fun listInstalledColormaps() {
        val reply = reply(extra = 0, payloadUnits = 1)
        byteOrder.put16(reply, 8, 1)
        byteOrder.put32(reply, 32, X11Ids.DefaultColormap)
        write(reply)
    }

    private fun allocColor(body: ByteArray) {
        val red = byteOrder.u16(body, 4)
        val green = byteOrder.u16(body, 6)
        val blue = byteOrder.u16(body, 8)
        val pixel = ((red and 0xff00) shl 8) or (green and 0xff00) or (blue ushr 8)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, red)
        byteOrder.put16(reply, 10, green)
        byteOrder.put16(reply, 12, blue)
        byteOrder.put16(reply, 14, red)
        byteOrder.put16(reply, 16, green)
        byteOrder.put16(reply, 18, blue)
        byteOrder.put32(reply, 20, pixel)
        write(reply)
    }

    private fun lookupColor() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0xffff)
        byteOrder.put16(reply, 10, 0xffff)
        byteOrder.put16(reply, 12, 0xffff)
        byteOrder.put16(reply, 14, 0xffff)
        byteOrder.put16(reply, 16, 0xffff)
        byteOrder.put16(reply, 18, 0xffff)
        write(reply)
    }

    private fun queryColors(body: ByteArray) {
        val count = (body.size - 4) / 4
        val reply = reply(extra = 0, payloadUnits = count * 2)
        byteOrder.put16(reply, 8, count)
        var sourceOffset = 4
        var targetOffset = 32
        repeat(count) {
            val pixel = byteOrder.u32(body, sourceOffset)
            sourceOffset += 4
            byteOrder.put16(reply, targetOffset, ((pixel ushr 16) and 0xff) * 257)
            byteOrder.put16(reply, targetOffset + 2, ((pixel ushr 8) and 0xff) * 257)
            byteOrder.put16(reply, targetOffset + 4, (pixel and 0xff) * 257)
            targetOffset += 8
        }
        write(reply)
    }

    private fun createCursor(body: ByteArray) {
        if (body.size >= 4) {
            val id = byteOrder.u32(body, 0)
            state.putCursor(id)
            own(id)
        }
    }

    private fun queryBestSize(sizeClass: Int, body: ByteArray) {
        if (body.size < 8) return writeError(error = 2, opcode = 97, badValue = 0)
        if (sizeClass !in QueryBestSizeCursor..QueryBestSizeStipple) {
            return writeError(error = 2, opcode = 97, badValue = sizeClass)
        }
        val drawableId = byteOrder.u32(body, 0)
        state.drawable(drawableId) ?: return writeError(error = 9, opcode = 97, badValue = drawableId)
        val requestedWidth = byteOrder.u16(body, 4)
        val requestedHeight = byteOrder.u16(body, 6)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, requestedWidth.coerceAtLeast(1))
        byteOrder.put16(reply, 10, requestedHeight.coerceAtLeast(1))
        write(reply)
    }

    private fun queryExtension(body: ByteArray) {
        val nameLength = byteOrder.u16(body, 0)
        val name = body.copyOfRange(4, 4 + nameLength).decodeToString()
        val reply = reply(extra = 0, payloadUnits = 0)
        val extension = state.extension(name)
        state.recordExtensionQuery(name, extension != null)
        if (extension != null) {
            reply[8] = 1
            reply[9] = extension.majorOpcode.toByte()
            reply[10] = extension.firstEvent.toByte()
            reply[11] = extension.firstError.toByte()
        }
        write(reply)
    }

    private fun unsupportedRequest(opcode: Int, minorOpcode: Int, name: String) {
        state.recordUnsupportedRequest(opcode, minorOpcode, name)
        writeError(
            error = 1,
            opcode = opcode,
            minorOpcode = minorOpcode,
            badValue = if (opcode == XGlx.MajorOpcode || opcode == XRender.MajorOpcode) minorOpcode else opcode,
        )
    }

    private fun requestName(opcode: Int, minorOpcode: Int): String =
        when (opcode) {
            XGlx.MajorOpcode -> "GLX.${XGlx.operationName(minorOpcode)}"
            XBigRequests.MajorOpcode -> "BIG-REQUESTS.$minorOpcode"
            XRender.MajorOpcode -> "RENDER.${XRender.operationName(minorOpcode)}"
            1 -> "CreateWindow"
            2 -> "ChangeWindowAttributes"
            3 -> "GetWindowAttributes"
            4 -> "DestroyWindow"
            6 -> "ChangeSaveSet"
            7 -> "ReparentWindow"
            8 -> "MapWindow"
            9 -> "MapSubwindows"
            10 -> "UnmapWindow"
            11 -> "UnmapSubwindows"
            12 -> "ConfigureWindow"
            13 -> "CirculateWindow"
            14 -> "GetGeometry"
            15 -> "QueryTree"
            16 -> "InternAtom"
            17 -> "GetAtomName"
            18 -> "ChangeProperty"
            19 -> "DeleteProperty"
            20 -> "GetProperty"
            21 -> "ListProperties"
            22 -> "SetSelectionOwner"
            23 -> "GetSelectionOwner"
            24 -> "ConvertSelection"
            25 -> "SendEvent"
            26 -> "GrabPointer"
            27 -> "UngrabPointer"
            28 -> "GrabButton"
            29 -> "UngrabButton"
            30 -> "ChangeActivePointerGrab"
            31 -> "GrabKeyboard"
            32 -> "UngrabKeyboard"
            33 -> "GrabKey"
            34 -> "UngrabKey"
            35 -> "AllowEvents"
            36 -> "GrabServer"
            37 -> "UngrabServer"
            38 -> "QueryPointer"
            40 -> "TranslateCoordinates"
            42 -> "SetInputFocus"
            43 -> "GetInputFocus"
            44 -> "QueryKeymap"
            45 -> "OpenFont"
            46 -> "CloseFont"
            47 -> "QueryFont"
            48 -> "QueryTextExtents"
            49 -> "ListFonts"
            52 -> "GetFontPath"
            53 -> "CreatePixmap"
            54 -> "FreePixmap"
            55 -> "CreateGC"
            56 -> "ChangeGC"
            57 -> "CopyGC"
            58 -> "SetDashes"
            59 -> "SetClipRectangles"
            60 -> "FreeGC"
            61 -> "ClearArea"
            62 -> "CopyArea"
            63 -> "CopyPlane"
            64 -> "PolyPoint"
            65 -> "PolyLine"
            66 -> "PolySegment"
            67 -> "PolyRectangle"
            68 -> "PolyArc"
            69 -> "FillPoly"
            70 -> "PolyFillRectangle"
            71 -> "PolyFillArc"
            72 -> "PutImage"
            73 -> "GetImage"
            74 -> "PolyText8"
            75 -> "PolyText16"
            76 -> "ImageText8"
            77 -> "ImageText16"
            78 -> "CreateColormap"
            79 -> "FreeColormap"
            81 -> "InstallColormap"
            83 -> "ListInstalledColormaps"
            84 -> "AllocColor"
            85 -> "AllocNamedColor"
            91 -> "QueryColors"
            93 -> "CreateCursor"
            94 -> "CreateGlyphCursor"
            95 -> "FreeCursor"
            96 -> "RecolorCursor"
            97 -> "QueryBestSize"
            98 -> "QueryExtension"
            99 -> "ListExtensions"
            101 -> "GetKeyboardMapping"
            103 -> "GetKeyboardControl"
            105 -> "Bell"
            106 -> "GetPointerControl"
            107 -> "SetScreenSaver"
            108 -> "GetScreenSaver"
            112 -> "SetPointerMapping"
            116 -> "SetModifierMapping"
            117 -> "GetModifierMapping"
            118 -> "SetModifierMapping"
            119 -> "GetModifierMapping"
            127 -> "NoOperation"
            else -> "Opcode$opcode/$minorOpcode"
        }

    private fun listExtensions() {
        val names = state.extensions.map { it.name.encodeToByteArray() }
        val size = names.sumOf { 1 + it.size }
        val reply = reply(extra = names.size, payloadUnits = paddedLength(size) / 4)
        var offset = 32
        for (name in names) {
            reply[offset++] = name.size.toByte()
            name.copyInto(reply, offset)
            offset += name.size
        }
        write(reply)
    }

    private fun getKeyboardMapping(body: ByteArray) {
        val count = body.getOrNull(0)?.toInt()?.and(0xff) ?: 0
        val keysymsPerKeycode = 1
        val reply = reply(extra = keysymsPerKeycode, payloadUnits = count * keysymsPerKeycode)
        write(reply)
    }

    private fun getKeyboardControl() {
        val reply = reply(extra = 1, payloadUnits = 5)
        byteOrder.put32(reply, 8, 0)
        reply[12] = 50
        write(reply)
    }

    private fun getPointerControl() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 1)
        byteOrder.put16(reply, 10, 1)
        byteOrder.put16(reply, 12, 0)
        write(reply)
    }

    private fun getScreenSaver() {
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0)
        byteOrder.put16(reply, 10, 0)
        reply[12] = 0
        reply[13] = 0
        write(reply)
    }

    private fun getPointerMapping() {
        val map = byteArrayOf(1, 2, 3)
        val reply = reply(extra = map.size, payloadUnits = 1)
        map.copyInto(reply, 32)
        write(reply)
    }

    private fun getModifierMapping() {
        val reply = reply(extra = 0, payloadUnits = 0)
        write(reply)
    }

    private fun unitReplyless() = Unit

    private fun sendExpose(window: XWindow) {
        val event = ByteArray(32)
        event[0] = 12
        byteOrder.put16(event, 2, sequence)
        byteOrder.put32(event, 4, window.id)
        byteOrder.put16(event, 8, 0)
        byteOrder.put16(event, 10, 0)
        byteOrder.put16(event, 12, window.width)
        byteOrder.put16(event, 14, window.height)
        byteOrder.put16(event, 16, 0)
        write(event)
    }

    private fun sendMapNotify(window: XWindow) {
        val event = ByteArray(32)
        event[0] = 19
        byteOrder.put16(event, 2, sequence)
        byteOrder.put32(event, 4, window.parentId)
        byteOrder.put32(event, 8, window.id)
        write(event)
    }

    private fun sendConfigureNotify(window: XWindow) {
        val event = ByteArray(32)
        event[0] = 22
        byteOrder.put16(event, 2, sequence)
        byteOrder.put32(event, 4, window.id)
        byteOrder.put32(event, 8, window.id)
        byteOrder.put32(event, 12, 0)
        byteOrder.put16(event, 16, window.x)
        byteOrder.put16(event, 18, window.y)
        byteOrder.put16(event, 20, window.width)
        byteOrder.put16(event, 22, window.height)
        byteOrder.put16(event, 24, window.borderWidth)
        write(event)
    }

    override fun sendPointerEvent(event: XPointerEvent) {
        val bytes = ByteArray(32)
        bytes[0] = event.type.code.toByte()
        bytes[1] = event.button.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.time)
        byteOrder.put32(bytes, 8, X11Ids.RootWindow)
        byteOrder.put32(bytes, 12, event.eventWindowId)
        byteOrder.put32(bytes, 16, event.childWindowId)
        byteOrder.put16(bytes, 20, event.rootX)
        byteOrder.put16(bytes, 22, event.rootY)
        byteOrder.put16(bytes, 24, event.eventX)
        byteOrder.put16(bytes, 26, event.eventY)
        byteOrder.put16(bytes, 28, event.state)
        bytes[30] = 1
        write(bytes)
    }

    private fun reply(extra: Int, payloadUnits: Int): ByteArray {
        val bytes = ByteArray(32 + payloadUnits * 4)
        bytes[0] = 1
        bytes[1] = extra.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, payloadUnits)
        return bytes
    }

    private fun putCharInfo(bytes: ByteArray, offset: Int) {
        byteOrder.put16(bytes, offset, 0)
        byteOrder.put16(bytes, offset + 2, XFramebuffer.TextCellWidth)
        byteOrder.put16(bytes, offset + 4, XFramebuffer.TextCellWidth)
        byteOrder.put16(bytes, offset + 6, XFramebuffer.TextAscent)
        byteOrder.put16(bytes, offset + 8, XFramebuffer.TextDescent)
        byteOrder.put16(bytes, offset + 10, 0)
    }

    private fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun putIntArray(bytes: ByteArray, offset: Int, values: IntArray) {
        var target = offset
        for (value in values) {
            byteOrder.put32(bytes, target, value)
            target += 4
        }
    }

    private fun writeError(error: Int, opcode: Int, minorOpcode: Int = 0, badValue: Int) {
        val bytes = ByteArray(32)
        bytes[0] = 0
        bytes[1] = error.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, badValue)
        byteOrder.put16(bytes, 8, minorOpcode)
        bytes[10] = opcode.toByte()
        write(bytes)
    }

    private fun Int.toHex(): String = "0x${toUInt().toString(16)}"

    private fun windowAttributeValues(body: ByteArray, maskOffset: Int, valuesOffset: Int): WindowAttributeValues {
        if (body.size < valuesOffset || body.size < maskOffset + 4) return WindowAttributeValues()
        val mask = byteOrder.u32(body, maskOffset)
        var offset = valuesOffset
        var backgroundPixmapId: Int? = null
        var backgroundPixel: Int? = null
        var eventMask: Int? = null
        for (bit in 0..14) {
            if ((mask and (1 shl bit)) == 0) continue
            if (offset + 4 > body.size) break
            val value = byteOrder.u32(body, offset)
            when (bit) {
                0 -> backgroundPixmapId = value
                1 -> backgroundPixel = value
                11 -> eventMask = value
            }
            offset += 4
        }
        return WindowAttributeValues(backgroundPixmapId, backgroundPixel, eventMask)
    }

    private fun validateGcValues(mask: Int, body: ByteArray, valuesOffset: Int, opcode: Int): Boolean {
        if ((mask and GcValueMask.inv()) != 0) {
            writeError(error = 2, opcode = opcode, badValue = mask)
            return false
        }
        var offset = valuesOffset
        fun next(): Int? {
            if (offset + 4 > body.size) return null
            val value = byteOrder.u32(body, offset)
            offset += 4
            return value
        }
        for (bit in 0..22) {
            if ((mask and (1 shl bit)) == 0) continue
            val value = next() ?: break
            when (bit) {
                0 -> if (value !in XGraphicsContext.GXclear..XGraphicsContext.GXset) {
                    writeError(error = 2, opcode = opcode, badValue = value)
                    return false
                }
                5 -> if (value !in XGraphicsContext.LineSolid..XGraphicsContext.LineDoubleDash) {
                    writeError(error = 2, opcode = opcode, badValue = value)
                    return false
                }
                8 -> if (value !in XGraphicsContext.FillSolid..XGraphicsContext.FillOpaqueStippled) {
                    writeError(error = 2, opcode = opcode, badValue = value)
                    return false
                }
                9 -> if (value !in XGraphicsContext.EvenOddRule..XGraphicsContext.WindingRule) {
                    writeError(error = 2, opcode = opcode, badValue = value)
                    return false
                }
                10, 11 -> if (state.pixmapImage(value) == null) {
                    writeError(error = 4, opcode = opcode, badValue = value)
                    return false
                }
                20 -> if (value !in 0..0xffff) {
                    writeError(error = 2, opcode = opcode, badValue = value)
                    return false
                }
                21 -> if (value !in 1..0xff) {
                    writeError(error = 2, opcode = opcode, badValue = value)
                    return false
                }
                22 -> if (value !in XGraphicsContext.ArcChord..XGraphicsContext.ArcPieSlice) {
                    writeError(error = 2, opcode = opcode, badValue = value)
                    return false
                }
            }
        }
        return true
    }

    private fun applyGcValues(id: Int, mask: Int, body: ByteArray, valuesOffset: Int, opcode: Int) {
        if (!validateGcValues(mask, body, valuesOffset, opcode)) return
        var offset = valuesOffset
        fun next(): Int? {
            if (offset + 4 > body.size) return null
            val value = byteOrder.u32(body, offset)
            offset += 4
            return value
        }
        var foreground: Int? = null
        var background: Int? = null
        var lineWidth: Int? = null
        var lineStyle: Int? = null
        var function: Int? = null
        var planeMask: Int? = null
        var fontId: Int? = null
        var clipXOrigin: Int? = null
        var clipYOrigin: Int? = null
        var clearClipRectangles = false
        var fillStyle: Int? = null
        var fillRule: Int? = null
        var tilePixmapId: Int? = null
        var stipplePixmapId: Int? = null
        var tilePixmap: XImagePixels? = null
        var stipplePixmap: XImagePixels? = null
        var tileStippleXOrigin: Int? = null
        var tileStippleYOrigin: Int? = null
        var dashOffset: Int? = null
        var dashes: List<Int>? = null
        var arcMode: Int? = null
        for (bit in 0..22) {
            if ((mask and (1 shl bit)) == 0) continue
            val value = next() ?: break
            when (bit) {
                0 -> if (value in XGraphicsContext.GXclear..XGraphicsContext.GXset) {
                    function = value
                } else {
                    return writeError(error = 2, opcode = opcode, badValue = value)
                }
                1 -> planeMask = value
                2 -> foreground = value
                3 -> background = value
                4 -> lineWidth = value.coerceAtLeast(1)
                5 -> if (value in XGraphicsContext.LineSolid..XGraphicsContext.LineDoubleDash) {
                    lineStyle = value
                } else {
                    return writeError(error = 2, opcode = opcode, badValue = value)
                }
                8 -> if (value in XGraphicsContext.FillSolid..XGraphicsContext.FillOpaqueStippled) {
                    fillStyle = value
                } else {
                    return writeError(error = 2, opcode = opcode, badValue = value)
                }
                9 -> if (value in XGraphicsContext.EvenOddRule..XGraphicsContext.WindingRule) {
                    fillRule = value
                } else {
                    return writeError(error = 2, opcode = opcode, badValue = value)
                }
                10 -> {
                    tilePixmapId = value
                    tilePixmap = state.pixmapImage(value) ?: return writeError(error = 4, opcode = opcode, badValue = value)
                }
                11 -> {
                    stipplePixmapId = value
                    stipplePixmap = state.pixmapImage(value) ?: return writeError(error = 4, opcode = opcode, badValue = value)
                }
                12 -> tileStippleXOrigin = value.toShort().toInt()
                13 -> tileStippleYOrigin = value.toShort().toInt()
                14 -> fontId = value
                17 -> clipXOrigin = value.toShort().toInt()
                18 -> clipYOrigin = value.toShort().toInt()
                19 -> if (value == 0) clearClipRectangles = true
                20 -> if (value in 0..0xffff) {
                    dashOffset = value
                } else {
                    return writeError(error = 2, opcode = opcode, badValue = value)
                }
                21 -> if (value in 1..0xff) {
                    dashes = listOf(value)
                } else {
                    return writeError(error = 2, opcode = opcode, badValue = value)
                }
                22 -> if (value in XGraphicsContext.ArcChord..XGraphicsContext.ArcPieSlice) {
                    arcMode = value
                } else {
                    return writeError(error = 2, opcode = opcode, badValue = value)
                }
            }
        }
        state.updateGc(
            id = id,
            foreground = foreground,
            background = background,
            lineWidth = lineWidth,
            lineStyle = lineStyle,
            function = function,
            planeMask = planeMask,
            fontId = fontId,
            clipXOrigin = clipXOrigin,
            clipYOrigin = clipYOrigin,
            fillStyle = fillStyle,
            fillRule = fillRule,
            tilePixmapId = tilePixmapId,
            stipplePixmapId = stipplePixmapId,
            tilePixmap = tilePixmap,
            stipplePixmap = stipplePixmap,
            tileStippleXOrigin = tileStippleXOrigin,
            tileStippleYOrigin = tileStippleYOrigin,
            dashOffset = dashOffset,
            dashes = dashes,
            arcMode = arcMode,
        )
        if (clearClipRectangles) state.updateGcClip(id, clipRectangles = null)
    }

    private fun Long.saturatingTimes4(): Long =
        if (this > Long.MAX_VALUE / 4) Long.MAX_VALUE else this * 4

    private fun points(body: ByteArray, startOffset: Int, coordMode: Int): List<XPoint> {
        val points = mutableListOf<XPoint>()
        var offset = startOffset
        var previousX = 0
        var previousY = 0
        while (offset + 4 <= body.size) {
            var x = byteOrder.i16(body, offset)
            var y = byteOrder.i16(body, offset + 2)
            if (coordMode == 1 && points.isNotEmpty()) {
                x += previousX
                y += previousY
            }
            points += XPoint(x, y)
            previousX = x
            previousY = y
            offset += 4
        }
        return points
    }

    private fun rectangles(body: ByteArray, startOffset: Int): List<XRectangleCommand> {
        val rectangles = mutableListOf<XRectangleCommand>()
        var offset = startOffset
        while (offset + 8 <= body.size) {
            rectangles += XRectangleCommand(
                x = byteOrder.i16(body, offset),
                y = byteOrder.i16(body, offset + 2),
                width = byteOrder.u16(body, offset + 4),
                height = byteOrder.u16(body, offset + 6),
            )
            offset += 8
        }
        return rectangles
    }

    private fun arcs(body: ByteArray, startOffset: Int): List<XArcCommand> {
        val arcs = mutableListOf<XArcCommand>()
        var offset = startOffset
        while (offset + 12 <= body.size) {
            arcs += XArcCommand(
                x = byteOrder.i16(body, offset),
                y = byteOrder.i16(body, offset + 2),
                width = byteOrder.u16(body, offset + 4),
                height = byteOrder.u16(body, offset + 6),
                angle1 = byteOrder.i16(body, offset + 8),
                angle2 = byteOrder.i16(body, offset + 10),
            )
            offset += 12
        }
        return arcs
    }

    private fun trapezoids(body: ByteArray, startOffset: Int): List<XTrapezoidCommand> {
        val trapezoids = mutableListOf<XTrapezoidCommand>()
        var offset = startOffset
        while (offset + 40 <= body.size) {
            trapezoids += XTrapezoidCommand(
                top = byteOrder.u32(body, offset),
                bottom = byteOrder.u32(body, offset + 4),
                left = XFixedLine(
                    p1 = XFixedPoint(byteOrder.u32(body, offset + 8), byteOrder.u32(body, offset + 12)),
                    p2 = XFixedPoint(byteOrder.u32(body, offset + 16), byteOrder.u32(body, offset + 20)),
                ),
                right = XFixedLine(
                    p1 = XFixedPoint(byteOrder.u32(body, offset + 24), byteOrder.u32(body, offset + 28)),
                    p2 = XFixedPoint(byteOrder.u32(body, offset + 32), byteOrder.u32(body, offset + 36)),
                ),
            )
            offset += 40
        }
        return trapezoids
    }

    private fun offsetTrapezoids(trapezoids: List<XTrapezoidCommand>, xOffset: Int, yOffset: Int): List<XTrapezoidCommand> {
        if (xOffset == 0 && yOffset == 0) return trapezoids
        val fixedX = xOffset * 65_536
        val fixedY = yOffset * 65_536
        fun point(point: XFixedPoint): XFixedPoint =
            XFixedPoint(point.x + fixedX, point.y + fixedY)
        fun line(line: XFixedLine): XFixedLine =
            XFixedLine(point(line.p1), point(line.p2))
        return trapezoids.map { trap ->
            XTrapezoidCommand(
                top = trap.top + fixedY,
                bottom = trap.bottom + fixedY,
                left = line(trap.left),
                right = line(trap.right),
            )
        }
    }

    private fun traps(body: ByteArray, startOffset: Int): List<XTrapezoidCommand> {
        val traps = mutableListOf<XTrapezoidCommand>()
        var offset = startOffset
        while (offset + 24 <= body.size) {
            val topLeft = byteOrder.u32(body, offset)
            val topRight = byteOrder.u32(body, offset + 4)
            val topY = byteOrder.u32(body, offset + 8)
            val bottomLeft = byteOrder.u32(body, offset + 12)
            val bottomRight = byteOrder.u32(body, offset + 16)
            val bottomY = byteOrder.u32(body, offset + 20)
            traps += XTrapezoidCommand(
                top = topY,
                bottom = bottomY,
                left = XFixedLine(XFixedPoint(topLeft, topY), XFixedPoint(bottomLeft, bottomY)),
                right = XFixedLine(XFixedPoint(topRight, topY), XFixedPoint(bottomRight, bottomY)),
            )
            offset += 24
        }
        return traps
    }

    private fun triangles(body: ByteArray, startOffset: Int): List<XTriangleCommand> {
        val triangles = mutableListOf<XTriangleCommand>()
        var offset = startOffset
        while (offset + 24 <= body.size) {
            triangles += XTriangleCommand(
                p1 = XFixedPoint(byteOrder.u32(body, offset), byteOrder.u32(body, offset + 4)),
                p2 = XFixedPoint(byteOrder.u32(body, offset + 8), byteOrder.u32(body, offset + 12)),
                p3 = XFixedPoint(byteOrder.u32(body, offset + 16), byteOrder.u32(body, offset + 20)),
            )
            offset += 24
        }
        return triangles
    }

    private fun fixedPoints(body: ByteArray, startOffset: Int): List<XFixedPoint> {
        val points = mutableListOf<XFixedPoint>()
        var offset = startOffset
        while (offset + 8 <= body.size) {
            points += XFixedPoint(byteOrder.u32(body, offset), byteOrder.u32(body, offset + 4))
            offset += 8
        }
        return points
    }

    private fun Int.fixedToInt(): Int = this / 65_536

    private fun decodeText16(bytes: ByteArray): String =
        buildString {
            var offset = 0
            while (offset + 1 < bytes.size) {
                val value = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
                append(if (value in 32..126) value.toChar() else ' ')
                offset += 2
            }
        }

    private fun decodeText8(bytes: ByteArray): String =
        buildString(bytes.size) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append(if (value in 32..126) value.toChar() else ' ')
            }
        }

    private fun decodePolyText(body: ByteArray, is16Bit: Boolean): List<XTextRun> {
        val runs = mutableListOf<XTextRun>()
        var offset = 12
        var x = byteOrder.i16(body, 8)
        val y = byteOrder.i16(body, 10)
        while (offset < body.size) {
            val length = body[offset].toInt() and 0xff
            if (length == 255) {
                offset += 5
                continue
            }
            if (offset + 2 > body.size) break
            x += body[offset + 1].toInt().toByte().toInt()
            val byteLength = length * if (is16Bit) 2 else 1
            if (offset + 2 + byteLength > body.size) break
            val bytes = body.copyOfRange(offset + 2, offset + 2 + byteLength)
            val text = if (is16Bit) decodeText16(bytes) else decodeText8(bytes)
            if (text.isNotEmpty()) {
                runs += XTextRun(x, y, text)
                x += text.length * XFramebuffer.TextCellWidth
            }
            offset += 2 + byteLength
        }
        return runs
    }

    private fun decodePutImage(
        format: Int,
        width: Int,
        height: Int,
        depth: Int,
        data: ByteArray,
    ): XImagePixels? {
        if (format != 2 || width <= 0 || height <= 0 || depth !in setOf(8, 24, 32)) return null
        if (depth == 8) {
            val stride = paddedLength(width)
            if (data.size < stride * height) return null
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * stride
                for (x in 0 until width) {
                    val alpha = data[rowOffset + x].toInt() and 0xff
                    pixels[y * width + x] = alpha shl 24
                }
            }
            return XImagePixels(width, height, pixels)
        }
        val bytesPerPixel = 4
        val stride = paddedLength(width * bytesPerPixel)
        if (data.size < stride * height) return null
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * stride
            for (x in 0 until width) {
                val pixel = byteOrder.u32(data, rowOffset + x * bytesPerPixel)
                pixels[y * width + x] = if (depth == 24) XFramebuffer.opaque(pixel) else XFramebuffer.argb(pixel)
            }
        }
        return XImagePixels(width, height, pixels)
    }

    private fun decodeGlyphMask(
        format: Int,
        width: Int,
        height: Int,
        data: ByteArray,
        offset: Int,
    ): XFramebuffer? {
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        when (format) {
            XRender.A8Format -> {
                val stride = paddedLength(width)
                if (offset + stride * height > data.size) return null
                for (y in 0 until height) {
                    val rowOffset = offset + y * stride
                    for (x in 0 until width) {
                        val alpha = data[rowOffset + x].toInt() and 0xff
                        pixels[y * width + x] = alpha shl 24
                    }
                }
            }
            XRender.A1Format -> {
                val stride = ((width + 31) / 32) * 4
                if (offset + stride * height > data.size) return null
                for (y in 0 until height) {
                    val rowOffset = offset + y * stride
                    for (x in 0 until width) {
                        val byte = data[rowOffset + x / 8].toInt() and 0xff
                        val alpha = if ((byte and (0x80 ushr (x % 8))) != 0) 0xff else 0
                        pixels[y * width + x] = alpha shl 24
                    }
                }
            }
            XRender.Argb32Format,
            XRender.Rgb24Format,
            -> {
                val stride = width * 4
                if (offset + stride * height > data.size) return null
                for (y in 0 until height) {
                    val rowOffset = offset + y * stride
                    for (x in 0 until width) {
                        val pixel = byteOrder.u32(data, rowOffset + x * 4)
                        val alpha = if (format == XRender.Rgb24Format) 0xff else (pixel ushr 24) and 0xff
                        pixels[y * width + x] = alpha shl 24
                    }
                }
            }
            else -> return null
        }
        return XFramebuffer(width, height, painted = true).also { framebuffer ->
            framebuffer.putImage(0, 0, XImagePixels(width, height, pixels))
        }
    }

    private fun glyphImageByteSize(format: Int?, width: Int, height: Int): Int {
        if (format == null || width <= 0 || height <= 0) return 0
        return when (format) {
            XRender.A8Format -> paddedLength(width) * height
            XRender.A1Format -> ((width + 31) / 32) * 4 * height
            XRender.Argb32Format, XRender.Rgb24Format -> width * 4 * height
            else -> 0
        }
    }

    private fun paddedLength(length: Int): Int = (length + 3) and -4

    private fun own(id: Int) {
        ownedResources += id
    }

    private companion object {
        const val GcValueMask = 0x007f_ffff
        const val QueryBestSizeCursor = 0
        const val QueryBestSizeTile = 1
        const val QueryBestSizeStipple = 2
        val RenderFilterNames = listOf("nearest", "bilinear", "fast", "good", "best")
        val RenderFilterAliases = listOf(0xffff, 0xffff, 0, 1, 1)
    }
}

private data class WindowAttributeValues(
    val backgroundPixmapId: Int? = null,
    val backgroundPixel: Int? = null,
    val eventMask: Int? = null,
)

private data class XRenderPictureAttributes(
    val repeat: Int? = null,
)

private data class XTextRun(
    val x: Int,
    val y: Int,
    val text: String,
)

private fun java.io.InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read == -1) throw EOFException("Expected $size bytes, got $offset")
        offset += read
    }
    return bytes
}

private fun java.io.InputStream.readOrNull(size: Int): ByteArray? {
    val first = read()
    if (first == -1) return null
    val bytes = ByteArray(size)
    bytes[0] = first.toByte()
    var offset = 1
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read == -1) throw EOFException("Expected $size bytes, got $offset")
        offset += read
    }
    return bytes
}
