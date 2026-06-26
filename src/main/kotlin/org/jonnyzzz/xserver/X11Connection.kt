package org.jonnyzzz.xserver

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal class X11Connection(
    private val input: InputStream,
    private val output: OutputStream,
    private val state: X11State,
    private val remoteAddress: ByteArray? = null,
) : XEventSink {
    private lateinit var byteOrder: ByteOrder
    private var sequence = 0
    private val trace = java.lang.Boolean.getBoolean("x.trace")
    private val writeLock = Any()
    private val closeDownLock = Any()
    private val ownedResources = linkedSetOf<Int>()
    private var closeDownMode = XCloseDownMode.Destroy
    @Volatile
    private var killed = false
    @Volatile
    private var closeDownHandled = false

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

            val address = remoteAddress
            if (address != null && !state.acceptsHostAddress(address)) {
                write(
                    SetupReply.failure(
                        byteOrder = byteOrder,
                        clientMajor = major,
                        clientMinor = minor,
                        reason = "Access denied",
                    ),
                )
                return
            }

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
                state.processWhenServerGrabAllows(this) {
                    sequence = (sequence + 1) and 0xffff
                    if (trace) {
                        System.err.println("x11 seq=$sequence opcode=$opcode minor=$minorOpcode units=$units body=${body.size}")
                    }
                    state.recordRequest(requestName(opcode, minorOpcode))
                    dispatch(opcode, minorOpcode, body, units)
                }
            }
        } finally {
            closeDownClient()
        }
    }

    private fun dispatch(opcode: Int, minorOpcode: Int, body: ByteArray, requestUnits: Int) {
        state.extensionByMajorOpcode(opcode)?.let { extension ->
            if (extension.name == "GLX") {
                glx(minorOpcode, body, opcode)
                return
            }
            if (extension.name == "BIG-REQUESTS") {
                bigRequests(minorOpcode, body, requestUnits, opcode)
                return
            }
            if (extension.name == "RENDER") {
                render(minorOpcode, body, opcode)
                return
            }
        }
        when (opcode) {
            1 -> createWindow(minorOpcode, body)
            2 -> changeWindowAttributes(body)
            3 -> getWindowAttributes(body)
            4 -> destroyWindow(body)
            5 -> destroySubwindows(body)
            6 -> changeSaveSet(minorOpcode, body)
            7 -> reparentWindow(body)
            8 -> mapWindow(body)
            9 -> mapSubwindows(body)
            10 -> unmapWindow(body)
            11 -> unmapSubwindows(body)
            12 -> configureWindow(body)
            13 -> circulateWindow(minorOpcode, body)
            14 -> getGeometry(body)
            15 -> queryTree(body)
            16 -> internAtom(minorOpcode, body)
            17 -> getAtomName(body)
            18 -> changeProperty(minorOpcode, body)
            19 -> deleteProperty(body)
            20 -> getProperty(minorOpcode, body)
            21 -> listProperties(body)
            22 -> setSelectionOwner(body)
            23 -> getSelectionOwner(body)
            24 -> convertSelection(body)
            25 -> sendEvent(minorOpcode, body)
            26 -> grabPointer(minorOpcode, body)
            27 -> ungrabPointer(body)
            28 -> grabButton(minorOpcode, body)
            29 -> ungrabButton(minorOpcode, body)
            30 -> changeActivePointerGrab(body)
            31 -> grabKeyboard(minorOpcode, body)
            32 -> ungrabKeyboard(body)
            33 -> grabKey(minorOpcode, body)
            34 -> ungrabKey(minorOpcode, body)
            35 -> allowEvents(minorOpcode, body)
            36 -> grabServer(body)
            37 -> ungrabServer(body)
            38 -> queryPointer(body)
            39 -> getMotionEvents(body)
            40 -> translateCoordinates(body)
            41 -> warpPointer(body)
            42 -> setInputFocus(minorOpcode, body)
            43 -> getInputFocus(body)
            44 -> queryKeymap(body)
            45 -> openFont(body)
            46 -> closeResource(opcode = 46, body = body, error = 7, exists = state::hasFont)
            47 -> queryFont(body)
            48 -> queryTextExtents(minorOpcode, body)
            49 -> listFonts(body)
            50 -> listFontsWithInfo(body)
            51 -> setFontPath(body)
            52 -> getFontPath(body)
            53 -> createPixmap(minorOpcode, body)
            54 -> closeResource(opcode = 54, body = body, error = 4, exists = state::hasPixmap)
            55 -> createGc(body)
            56 -> changeGc(body)
            57 -> copyGc(body)
            58 -> setDashes(body)
            59 -> setClipRectangles(minorOpcode, body)
            60 -> closeResource(opcode = 60, body = body, error = 13, exists = state::hasGc)
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
            74 -> polyText(opcode = 74, body = body, is16Bit = false)
            75 -> polyText(opcode = 75, body = body, is16Bit = true)
            76 -> imageText(minorOpcode, body, is16Bit = false)
            77 -> imageText(minorOpcode, body, is16Bit = true)
            78 -> createColormap(minorOpcode, body)
            79 -> closeResource(opcode = 79, body = body, error = 12, exists = state::hasColormap)
            80 -> copyColormapAndFree(body)
            81 -> installColormap(body)
            82 -> uninstallColormap(body)
            83 -> listInstalledColormaps(body)
            84 -> allocColor(body)
            85 -> allocNamedColor(body)
            86 -> allocColorCells(minorOpcode, body)
            87 -> allocColorPlanes(minorOpcode, body)
            88 -> freeColors(body)
            89 -> storeColors(body)
            90 -> storeNamedColor(minorOpcode, body)
            91 -> queryColors(body)
            92 -> lookupColor(body)
            93 -> createCursor(body)
            94 -> createGlyphCursor(body)
            95 -> closeResource(opcode = 95, body = body, error = 6, exists = state::hasCursor)
            96 -> recolorCursor(body)
            97 -> queryBestSize(minorOpcode, body)
            98 -> queryExtension(body)
            99 -> listExtensions(body)
            100 -> changeKeyboardMapping(minorOpcode, body)
            101 -> getKeyboardMapping(body)
            102 -> changeKeyboardControl(body)
            103 -> getKeyboardControl(body)
            104 -> bell(minorOpcode, body)
            105 -> changePointerControl(body)
            106 -> getPointerControl(body)
            107 -> setScreenSaver(body)
            108 -> getScreenSaver(body)
            109 -> changeHosts(minorOpcode, body)
            110 -> listHosts(body)
            111 -> setAccessControl(minorOpcode, body)
            112 -> setCloseDownMode(minorOpcode, body)
            113 -> killClient(body)
            114 -> rotateProperties(body)
            115 -> forceScreenSaver(minorOpcode, body)
            116 -> setPointerMapping(minorOpcode, body)
            117 -> getPointerMapping(body)
            118 -> setModifierMapping(minorOpcode, body)
            119 -> getModifierMapping(body)
            127 -> noOperation(body)
            else -> unsupportedRequest(opcode, minorOpcode, requestName(opcode, minorOpcode))
        }
    }

    private fun glx(minorOpcode: Int, body: ByteArray, majorOpcode: Int) {
        val operation = XGlx.operationName(minorOpcode)
        val detail = glxDetail(minorOpcode, body)
        state.recordGlxOperation(minorOpcode, operation, detail)

        when (minorOpcode) {
            XGlx.QueryVersion -> glxQueryVersion(body)
            3 -> glxCreateContext(body)
            4 -> glxDestroyContext(body)
            5 -> glxMakeCurrent(body, isContextCurrent = false)
            XGlx.IsDirect -> glxIsDirect(body)
            XGlx.WaitGL, XGlx.WaitX -> glxWait(body, minorOpcode)
            XGlx.CopyContext -> glxCopyContext(body)
            XGlx.SwapBuffers -> glxSwapBuffers(body)
            XGlx.UseXFont -> glxUseXFont(body)
            XGlx.Render -> glxRender(body)
            XGlx.RenderLarge -> glxRenderLarge(body)
            XGlx.CreateGLXPixmap -> glxCreatePixmap(body)
            XGlx.GetVisualConfigs -> glxGetVisualConfigs(body)
            XGlx.DestroyGLXPixmap -> glxDestroyPixmap(body)
            XGlx.QueryExtensionsString -> glxQueryExtensionsString(body)
            XGlx.QueryServerString -> glxQueryServerString(body)
            XGlx.ClientInfo -> glxClientInfo(body)
            XGlx.GetFBConfigs -> glxGetFbConfigs(body)
            XGlx.CreatePixmap -> glxCreateFbConfigPixmap(body)
            XGlx.DestroyPixmap -> glxDestroyFbConfigPixmap(body)
            XGlx.CreateNewContext -> glxCreateNewContext(body)
            XGlx.QueryContext -> glxQueryContext(body)
            XGlx.MakeContextCurrent -> glxMakeCurrent(body, isContextCurrent = true)
            XGlx.CreatePbuffer -> glxCreatePbuffer(body)
            XGlx.DestroyPbuffer -> glxDestroyPbuffer(body)
            XGlx.GetDrawableAttributes -> glxGetDrawableAttributes(body)
            XGlx.ChangeDrawableAttributes -> glxChangeDrawableAttributes(body)
            XGlx.CreateWindow -> glxCreateWindow(body)
            XGlx.DestroyWindow -> glxDestroyWindow(body)
            XGlx.SetClientInfoARB -> glxSetClientInfo(body, minorOpcode = XGlx.SetClientInfoARB, versionWords = 2)
            XGlx.CreateContextAttribsARB -> glxCreateContextAttribs(body)
            XGlx.SetClientInfo2ARB -> glxSetClientInfo(body, minorOpcode = XGlx.SetClientInfo2ARB, versionWords = 3)
            else -> unsupportedRequest(majorOpcode, minorOpcode, operation)
        }
    }

    private fun glxQueryVersion(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.QueryVersion, badValue = 0)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, XGlx.MajorVersion)
        byteOrder.put32(reply, 12, XGlx.MinorVersion)
        write(reply)
    }

    private fun bigRequests(minorOpcode: Int, body: ByteArray, requestUnits: Int, majorOpcode: Int) {
        if (minorOpcode != XBigRequests.Enable) {
            return unsupportedRequest(majorOpcode, minorOpcode, "BIG-REQUESTS.$minorOpcode")
        }
        if (requestUnits != 1 || body.isNotEmpty()) return writeError(error = 16, opcode = majorOpcode, minorOpcode = minorOpcode, badValue = 0)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, XBigRequests.MaximumRequestLength)
        write(reply)
    }

    private fun render(minorOpcode: Int, body: ByteArray, majorOpcode: Int) {
        val operation = XRender.operationName(minorOpcode)
        val detail = renderDetail(minorOpcode, body)
        state.recordRenderOperation(minorOpcode, operation, detail)

        when (minorOpcode) {
            0 -> renderQueryVersion(body)
            1 -> renderQueryPictFormats(body)
            2 -> renderQueryPictIndexValues(body)
            3 -> renderBadImplementation(minorOpcode)
            4 -> renderCreatePicture(body)
            5 -> renderChangePicture(body)
            6 -> renderSetPictureClipRectangles(body)
            7 -> renderFreePicture(body)
            8 -> renderComposite(body)
            9 -> renderBadImplementation(minorOpcode)
            10 -> renderTrapezoids(body)
            11 -> renderTriangles(body)
            12 -> renderTriStrip(body)
            13 -> renderTriFan(body)
            14 -> renderBadImplementation(minorOpcode)
            15 -> renderBadImplementation(minorOpcode)
            16 -> renderBadImplementation(minorOpcode)
            17 -> renderCreateGlyphSet(body)
            18 -> renderReferenceGlyphSet(body)
            19 -> renderFreeGlyphSet(body)
            20 -> renderAddGlyphs(body)
            21 -> renderBadImplementation(minorOpcode)
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

    private fun renderQueryVersion(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 0, badValue = 0)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, XRender.MajorVersion)
        byteOrder.put32(reply, 12, XRender.MinorVersion)
        write(reply)
    }

    private fun renderQueryPictFormats(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 1, badValue = 0)
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

    private fun renderQueryPictIndexValues(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 2, badValue = 0)
        val format = byteOrder.u32(body, 0)
        if (format !in XRender.PictFormats) {
            return writeError(error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = 2, badValue = format)
        }
        if (format in XRender.DirectFormats) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = 2, badValue = format)
        }
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, 0)
        write(reply)
    }

    private fun renderCreatePicture(body: ByteArray) {
        if (body.size < 16) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = id)
        val drawable = byteOrder.u32(body, 4)
        val drawableState = state.drawable(drawable) ?: return writeError(error = 9, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = drawable)
        val format = byteOrder.u32(body, 8)
        if (format !in XRender.PictFormats) {
            return writeError(error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = format)
        }
        if (drawableState.depth != XRender.formatDepth(format)) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = 4, badValue = format)
        }
        val valueMask = byteOrder.u32(body, 12)
        if (!validateRenderPictureValueLength(valueMask, body, valuesOffset = 16, minorOpcode = 4)) return
        val attributes = renderPictureAttributes(valueMask, body, valuesOffset = 16)
        state.putPicture(
            XPicture(
                id = id,
                drawableId = drawable,
                format = format,
                valueMask = valueMask,
                repeat = attributes.repeat ?: XRender.RepeatNone,
            ),
        )
        own(id)
    }

    private fun renderChangePicture(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = 0)
        val picture = byteOrder.u32(body, 0)
        if (state.picture(picture) == null) {
            return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 5, badValue = picture)
        }
        val valueMask = byteOrder.u32(body, 4)
        if (!validateRenderPictureValueLength(valueMask, body, valuesOffset = 8, minorOpcode = 5)) return
        val attributes = renderPictureAttributes(valueMask, body, valuesOffset = 8)
        state.updatePicture(picture, valueMask, repeat = attributes.repeat)
    }

    private fun validateRenderPictureValueLength(valueMask: Int, body: ByteArray, valuesOffset: Int, minorOpcode: Int): Boolean {
        if ((valueMask and XRender.PictureAttributeMask.inv()) != 0) {
            writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = valueMask)
            return false
        }
        if (body.size != valuesOffset + valueMask.countOneBits() * 4) {
            writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
            return false
        }
        return true
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
        if (body.size < 8 || (body.size - 8) % 8 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 6, badValue = 0)
        }
        val picture = byteOrder.u32(body, 0)
        if (state.picture(picture) == null) {
            return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 6, badValue = picture)
        }
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
        if (body.size != 4) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 7, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.picture(id) == null) {
            return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 7, badValue = id)
        }
        state.removePicture(id)
        ownedResources.remove(id)
    }

    private fun renderComposite(body: ByteArray) {
        if (body.size != 32) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = 0)
        val operation = body[0].toInt() and 0xff
        if (!XRender.isValidOperator(operation)) {
            return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = operation)
        }
        val sourceId = byteOrder.u32(body, 4)
        val source = state.picture(sourceId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = sourceId)
        val maskId = byteOrder.u32(body, 8)
        val mask = if (maskId == 0) {
            null
        } else {
            state.picture(maskId)
                ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = maskId)
        }
        val destinationId = byteOrder.u32(body, 12)
        val destination = state.picture(destinationId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 8, badValue = destinationId)
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
        if (body.size < 20 || (body.size - 20) % 40 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 10, badValue = 0)
        }
        val operation = body[0].toInt() and 0xff
        if (!XRender.isValidOperator(operation)) {
            return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = 10, badValue = operation)
        }
        val sourceId = byteOrder.u32(body, 4)
        val source = state.picture(sourceId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 10, badValue = sourceId)
        val destinationId = byteOrder.u32(body, 8)
        val destination = state.picture(destinationId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 10, badValue = destinationId)
        val maskFormat = byteOrder.u32(body, 12)
        if (maskFormat != 0 && maskFormat !in XRender.PictFormats) {
            return writeError(error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = 10, badValue = maskFormat)
        }
        if (maskFormat != 0 && !XRender.isAlphaMaskFormat(maskFormat)) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = 10, badValue = maskFormat)
        }
        val effectiveMaskFormat = maskFormat.takeIf { it != 0 } ?: XRender.A8Format
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 16)
        val sourceY = byteOrder.i16(body, 18)
        val trapezoids = trapezoids(body, 20)
        if (trapezoids.isEmpty()) return
        val painted = state.renderTrapezoids(
            operation = operation,
            source = source,
            destination = destination,
            maskFormat = effectiveMaskFormat,
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
        if (body.size < 20 || (body.size - 20) % 24 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 11, badValue = 0)
        }
        val operation = body[0].toInt() and 0xff
        if (!XRender.isValidOperator(operation)) {
            return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = 11, badValue = operation)
        }
        val sourceId = byteOrder.u32(body, 4)
        val source = state.picture(sourceId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 11, badValue = sourceId)
        val destinationId = byteOrder.u32(body, 8)
        val destination = state.picture(destinationId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 11, badValue = destinationId)
        val maskFormat = byteOrder.u32(body, 12)
        if (maskFormat != 0 && maskFormat !in XRender.PictFormats) {
            return writeError(error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = 11, badValue = maskFormat)
        }
        if (maskFormat != 0 && !XRender.isAlphaMaskFormat(maskFormat)) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = 11, badValue = maskFormat)
        }
        val effectiveMaskFormat = maskFormat.takeIf { it != 0 } ?: XRender.A8Format
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 16)
        val sourceY = byteOrder.i16(body, 18)
        val triangles = triangles(body, 20)
        if (triangles.isEmpty()) return
        val painted = state.renderTriangles(
            operation = operation,
            source = source,
            destination = destination,
            maskFormat = effectiveMaskFormat,
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
        renderTriangleMesh(body, minorOpcode = 12) { points ->
            points.windowed(size = 3, step = 1).map { XTriangleCommand(it[0], it[1], it[2]) }
        }
    }

    private fun renderTriFan(body: ByteArray) {
        renderTriangleMesh(body, minorOpcode = 13) { points ->
            val anchor = points.firstOrNull() ?: return@renderTriangleMesh emptyList()
            points.drop(1).windowed(size = 2, step = 1).map { XTriangleCommand(anchor, it[0], it[1]) }
        }
    }

    private fun renderTriangleMesh(body: ByteArray, minorOpcode: Int, trianglesFrom: (List<XFixedPoint>) -> List<XTriangleCommand>) {
        if (body.size < 20 || (body.size - 20) % 8 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
        }
        val operation = body[0].toInt() and 0xff
        if (!XRender.isValidOperator(operation)) {
            return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = operation)
        }
        val sourceId = byteOrder.u32(body, 4)
        val source = state.picture(sourceId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = sourceId)
        val destinationId = byteOrder.u32(body, 8)
        val destination = state.picture(destinationId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = destinationId)
        val maskFormat = byteOrder.u32(body, 12)
        if (maskFormat != 0 && maskFormat !in XRender.PictFormats) {
            return writeError(error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = maskFormat)
        }
        if (maskFormat != 0 && !XRender.isAlphaMaskFormat(maskFormat)) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = maskFormat)
        }
        val effectiveMaskFormat = maskFormat.takeIf { it != 0 } ?: XRender.A8Format
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 16)
        val sourceY = byteOrder.i16(body, 18)
        val triangles = trianglesFrom(fixedPoints(body, 20))
        if (triangles.isEmpty()) return
        val painted = state.renderTriangles(
            operation = operation,
            source = source,
            destination = destination,
            maskFormat = effectiveMaskFormat,
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
        if (body.size != 8) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 17, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 17, badValue = id)
        val format = byteOrder.u32(body, 4)
        if (format !in XRender.PictFormats) {
            return writeError(error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = 17, badValue = format)
        }
        state.putGlyphSet(XGlyphSet(id, format))
        own(id)
    }

    private fun renderReferenceGlyphSet(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 18, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 18, badValue = id)
        val existingId = byteOrder.u32(body, 4)
        if (!state.hasGlyphSet(existingId)) {
            return writeError(error = XRender.GlyphSetError, opcode = XRender.MajorOpcode, minorOpcode = 18, badValue = existingId)
        }
        state.referenceGlyphSet(id, existingId)
        own(id)
    }

    private fun renderFreeGlyphSet(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 19, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (!state.hasGlyphSet(id)) {
            return writeError(error = XRender.GlyphSetError, opcode = XRender.MajorOpcode, minorOpcode = 19, badValue = id)
        }
        state.removeGlyphSet(id)
        ownedResources.remove(id)
    }

    private fun renderAddGlyphs(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 20, badValue = 0)
        val glyphSet = byteOrder.u32(body, 0)
        val format = state.glyphSetFormat(glyphSet)
            ?: return writeError(error = XRender.GlyphSetError, opcode = XRender.MajorOpcode, minorOpcode = 20, badValue = glyphSet)
        val glyphsLength = byteOrder.u32(body, 4).toUInt().toLong()
        val imageTableOffset = 8L + glyphsLength * 16L
        if (imageTableOffset > body.size) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 20, badValue = 0)
        }
        var idOffset = 8
        var infoOffset = idOffset + (glyphsLength * 4).toInt()
        var imageOffset = imageTableOffset.toInt()
        val glyphs = mutableListOf<XGlyph>()
        repeat(glyphsLength.toInt()) { index ->
            val width = byteOrder.u16(body, infoOffset)
            val height = byteOrder.u16(body, infoOffset + 2)
            val imageSize = glyphImageByteSizeLong(format, width, height)
            if (imageOffset.toLong() + imageSize > body.size) {
                return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 20, badValue = 0)
            }
            val mask = decodeGlyphMask(
                format = format,
                width = width,
                height = height,
                data = body,
                offset = imageOffset,
            )
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
            imageOffset += imageSize.toInt()
            infoOffset += 12
        }
        if (imageOffset != body.size) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 20, badValue = 0)
        state.addGlyphs(glyphSet, glyphs)
    }

    private fun renderBadImplementation(minorOpcode: Int) {
        writeError(error = 17, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
    }

    private fun renderFreeGlyphs(body: ByteArray) {
        if (body.size < 4 || (body.size - 4) % 4 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 22, badValue = 0)
        }
        val glyphSet = byteOrder.u32(body, 0)
        if (!state.hasGlyphSet(glyphSet)) {
            return writeError(error = XRender.GlyphSetError, opcode = XRender.MajorOpcode, minorOpcode = 22, badValue = glyphSet)
        }
        var offset = 4
        while (offset + 4 <= body.size) {
            val glyphId = byteOrder.u32(body, offset)
            if (!state.removeGlyph(glyphSet, glyphId)) {
                return writeError(error = XRender.GlyphError, opcode = XRender.MajorOpcode, minorOpcode = 22, badValue = glyphId)
            }
            offset += 4
        }
    }

    private fun renderCompositeGlyphs(minorOpcode: Int, body: ByteArray) {
        if (body.size < 24) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
        val operation = body[0].toInt() and 0xff
        if (!XRender.isValidOperator(operation)) {
            return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = operation)
        }
        val sourceId = byteOrder.u32(body, 4)
        val source = state.picture(sourceId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = sourceId)
        val destinationId = byteOrder.u32(body, 8)
        val destination = state.picture(destinationId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = destinationId)
        val maskFormat = byteOrder.u32(body, 12)
        if (maskFormat != 0 && maskFormat !in XRender.PictFormats) {
            return writeError(error = XRender.PictFormatError, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = maskFormat)
        }
        val glyphSetId = byteOrder.u32(body, 16)
        if (!state.hasGlyphSet(glyphSetId)) {
            return writeError(error = XRender.GlyphSetError, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = glyphSetId)
        }
        val destinationDrawableId = destination.drawableId ?: return
        val sourceX = byteOrder.i16(body, 20)
        val sourceY = byteOrder.i16(body, 22)
        val parseResult = compositeGlyphPlacements(minorOpcode, body, glyphSetId)
        parseResult.error?.let {
            return writeError(error = it.error, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = it.badValue)
        }
        val placementsByGlyphSet = parseResult.placements
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

    private fun compositeGlyphPlacements(minorOpcode: Int, body: ByteArray, initialGlyphSetId: Int): XCompositeGlyphParseResult {
        val glyphIdBytes = when (minorOpcode) {
            23 -> 1
            24 -> 2
            else -> 4
        }
        var glyphSetId = initialGlyphSetId
        var x = 0
        var y = 0
        var offset = 24
        val result = linkedMapOf<Int, MutableList<XGlyphPlacement>>()
        while (offset < body.size) {
            if (offset + 8 > body.size) return XCompositeGlyphParseResult.badLength()
            val length = body[offset].toInt() and 0xff
            val deltaX = byteOrder.i16(body, offset + 4)
            val deltaY = byteOrder.i16(body, offset + 6)
            if (length == 0xff) {
                if (offset + 12 > body.size) return XCompositeGlyphParseResult.badLength()
                x += deltaX
                y += deltaY
                val nextGlyphSetId = byteOrder.u32(body, offset + 8)
                if (!state.hasGlyphSet(nextGlyphSetId)) {
                    return XCompositeGlyphParseResult.error(XRender.GlyphSetError, nextGlyphSetId)
                }
                glyphSetId = nextGlyphSetId
                offset += 12
                continue
            }

            x += deltaX
            y += deltaY
            offset += 8
            val glyphBytes = length * glyphIdBytes
            val paddedGlyphBytes = (glyphBytes + 3) and -4
            if (offset + paddedGlyphBytes > body.size) return XCompositeGlyphParseResult.badLength()
            repeat(length) {
                val glyphOffset = offset + it * glyphIdBytes
                val glyphId = when (glyphIdBytes) {
                    1 -> body[glyphOffset].toInt() and 0xff
                    2 -> byteOrder.u16(body, glyphOffset)
                    else -> byteOrder.u32(body, glyphOffset)
                }
                val glyph = state.glyph(glyphSetId, glyphId)
                    ?: return XCompositeGlyphParseResult.error(XRender.GlyphError, glyphId)
                result.getOrPut(glyphSetId) { mutableListOf() } += XGlyphPlacement(glyphId, x, y)
                x += glyph.xOff
                y += glyph.yOff
            }
            offset += paddedGlyphBytes
        }
        return XCompositeGlyphParseResult(result)
    }

    private fun renderFillRectangles(body: ByteArray) {
        if (body.size < 16 || (body.size - 16) % 8 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 26, badValue = 0)
        }
        val operation = body[0].toInt() and 0xff
        if (!XRender.isValidOperator(operation)) {
            return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = 26, badValue = operation)
        }
        val destinationId = byteOrder.u32(body, 4)
        val destination = state.picture(destinationId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 26, badValue = destinationId)
        val destinationDrawableId = destination.drawableId ?: return
        val pixel = XRender.argb32Pixel(
            red = byteOrder.u16(body, 8),
            green = byteOrder.u16(body, 10),
            blue = byteOrder.u16(body, 12),
            alpha = byteOrder.u16(body, 14),
        )
        val rectangles = rectangles(body, 16)
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
        if (body.size != 12) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 27, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 27, badValue = id)
        val source = byteOrder.u32(body, 4)
        if (state.picture(source) == null) {
            return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 27, badValue = source)
        }
        state.putCursor(id)
        own(id)
    }

    private fun renderSetPictureTransform(body: ByteArray) {
        if (body.size != 40) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 28, badValue = 0)
        val picture = byteOrder.u32(body, 0)
        if (state.picture(picture) == null) {
            return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 28, badValue = picture)
        }
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
        if (body.size < 8) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = 0)
        val picture = byteOrder.u32(body, 0)
        if (state.picture(picture) == null) {
            return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = picture)
        }
        val filterLength = byteOrder.u16(body, 4)
        val valuesOffset = paddedLength(8 + filterLength)
        if (body.size < valuesOffset) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = 0)
        }
        val name = body.copyOfRange(8, 8 + filterLength).decodeToString()
        if (name !in RenderFilterNames) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = 30, badValue = 0)
        }
        val values = mutableListOf<Int>()
        var offset = valuesOffset
        while (offset + 4 <= body.size) {
            values += byteOrder.u32(body, offset)
            offset += 4
        }
        state.updatePictureFilter(picture, name, values)
    }

    private fun renderCreateAnimCursor(body: ByteArray) {
        if (body.size < 4) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 31, badValue = 0)
        }
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 31, badValue = id)
        if ((body.size - 4) % 8 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 31, badValue = 0)
        }
        if (body.size == 4) return writeError(error = 2, opcode = XRender.MajorOpcode, minorOpcode = 31, badValue = 0)
        var offset = 4
        while (offset < body.size) {
            val cursor = byteOrder.u32(body, offset)
            if (!state.hasCursor(cursor)) {
                return writeError(error = 6, opcode = XRender.MajorOpcode, minorOpcode = 31, badValue = cursor)
            }
            offset += 8
        }
        state.putCursor(id)
        own(id)
    }

    private fun renderAddTraps(body: ByteArray) {
        if (body.size < 8 || (body.size - 8) % 24 != 0) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 32, badValue = 0)
        }
        val pictureId = byteOrder.u32(body, 0)
        val picture = state.picture(pictureId)
            ?: return writeError(error = XRender.PictureError, opcode = XRender.MajorOpcode, minorOpcode = 32, badValue = pictureId)
        if (!XRender.isAlphaMaskFormat(picture.format)) {
            return writeError(error = 8, opcode = XRender.MajorOpcode, minorOpcode = 32, badValue = pictureId)
        }
        val xOffset = byteOrder.i16(body, 4)
        val yOffset = byteOrder.i16(body, 6)
        val traps = offsetTrapezoids(traps(body, 8), xOffset, yOffset)
        if (traps.isEmpty()) return
        state.addTraps(picture, traps)
    }

    private fun renderCreateSolidFill(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 33, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 33, badValue = id)
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
        if (body.size < 24) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 34, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 34, badValue = id)
        val stopsCount = byteOrder.u32(body, 20)
        if (stopsCount < 0) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 34, badValue = 0)
        val colorOffset = 24L + stopsCount.toLong() * 4L
        val requiredSize = colorOffset + stopsCount.toLong() * 8L
        if (requiredSize != body.size.toLong()) {
            return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 34, badValue = 0)
        }
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
        if (body.size < 32) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 35, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 35, badValue = id)
        val stops = renderGradientStopsExact(body, countOffset = 28, stopsOffset = 32, minorOpcode = 35) ?: return
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
        if (body.size < 20) return writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = 36, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = XRender.MajorOpcode, minorOpcode = 36, badValue = id)
        val stops = renderGradientStopsExact(body, countOffset = 16, stopsOffset = 20, minorOpcode = 36) ?: return
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

    private fun renderGradientStopsExact(
        body: ByteArray,
        countOffset: Int,
        stopsOffset: Int,
        minorOpcode: Int,
    ): Pair<List<Int>, List<Int>>? {
        val stopsCount = byteOrder.u32(body, countOffset)
        if (stopsCount < 0) {
            writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
            return null
        }
        val colorOffset = stopsOffset.toLong() + stopsCount.toLong() * 4L
        val requiredSize = colorOffset + stopsCount.toLong() * 8L
        if (requiredSize != body.size.toLong()) {
            writeError(error = 16, opcode = XRender.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
            return null
        }
        return renderGradientStops(body, countOffset, stopsOffset)
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
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.GetVisualConfigs, badValue = 0)
        if (!glxScreenIsValid(body, offset = 0, minorOpcode = XGlx.GetVisualConfigs)) return
        val config = XGlx.visualConfig()
        val reply = reply(extra = 0, payloadUnits = config.size)
        byteOrder.put32(reply, 8, 1)
        byteOrder.put32(reply, 12, XGlx.VisualConfigValues)
        putIntArray(reply, 32, config)
        write(reply)
    }

    private fun glxGetFbConfigs(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.GetFBConfigs, badValue = 0)
        if (!glxScreenIsValid(body, offset = 0, minorOpcode = XGlx.GetFBConfigs)) return
        val config = XGlx.fbConfig()
        val reply = reply(extra = 0, payloadUnits = config.size)
        byteOrder.put32(reply, 8, 1)
        byteOrder.put32(reply, 12, XGlx.FbConfigAttributePairs)
        putIntArray(reply, 32, config)
        write(reply)
    }

    private fun glxQueryExtensionsString(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.QueryExtensionsString, badValue = 0)
        if (!glxScreenIsValid(body, offset = 0, minorOpcode = XGlx.QueryExtensionsString)) return
        glxStringReply(XGlx.serverString(XGlx.ExtensionsName))
    }

    private fun glxQueryServerString(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.QueryServerString, badValue = 0)
        if (!glxScreenIsValid(body, offset = 0, minorOpcode = XGlx.QueryServerString)) return
        val name = byteOrder.u32(body, 4)
        glxStringReply(XGlx.serverString(name))
    }

    private fun glxClientInfo(body: ByteArray) {
        if (body.size < 12) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.ClientInfo, badValue = 0)
        val extensionBytes = Integer.toUnsignedLong(byteOrder.u32(body, 8))
        val expectedBytes = 12L + paddedLength(extensionBytes)
        glxCheckBodyLength(body, expectedBytes = expectedBytes, minorOpcode = XGlx.ClientInfo)
    }

    private fun glxSetClientInfo(body: ByteArray, minorOpcode: Int, versionWords: Int) {
        if (body.size < 12) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
        val versions = byteOrder.u32(body, 0).toUInt().toLong()
        val glExtensionBytes = byteOrder.u32(body, 4).toUInt().toLong()
        val glxExtensionBytes = byteOrder.u32(body, 8).toUInt().toLong()
        val expectedBytes = 12L +
            versions * versionWords * 4L +
            paddedLength(glExtensionBytes) +
            paddedLength(glxExtensionBytes)
        glxCheckBodyLength(body, expectedBytes = expectedBytes, minorOpcode = minorOpcode)
    }

    private fun glxCheckBodyLength(
        body: ByteArray,
        expectedBytes: Long,
        minorOpcode: Int,
    ) {
        if (expectedBytes > Int.MAX_VALUE || body.size.toLong() != expectedBytes) {
            writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
        }
    }

    private fun glxStringReply(value: String) {
        val bytes = value.encodeToByteArray()
        val reply = reply(extra = 0, payloadUnits = paddedLength(bytes.size) / 4)
        byteOrder.put32(reply, 12, bytes.size)
        bytes.copyInto(reply, 32)
        write(reply)
    }

    private fun glxCreateContext(body: ByteArray) {
        if (body.size != 20) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = 3, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val visual = byteOrder.u32(body, 4)
        val screen = byteOrder.u32(body, 8)
        if (state.hasResource(context)) return writeError(error = 11, opcode = XGlx.MajorOpcode, minorOpcode = 3, badValue = context)
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
        if (body.size != 24) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateNewContext, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val fbConfig = byteOrder.u32(body, 4)
        val screen = byteOrder.u32(body, 8)
        val renderType = byteOrder.u32(body, 12)
        if (state.hasResource(context)) return writeError(error = 11, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateNewContext, badValue = context)
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
        if (body.size < 24) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateContextAttribsARB, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val fbConfig = byteOrder.u32(body, 4)
        val screen = byteOrder.u32(body, 8)
        val attribCount = byteOrder.u32(body, 20).toUInt().toLong()
        // Xorg validates CreateContextAttribsARB by comparing the request
        // length to sizeof(request) + numAttribs * 8 and reports BadLength.
        val expectedSize = 24L + attribCount * 8L
        if (expectedSize > Int.MAX_VALUE || body.size != expectedSize.toInt()) {
            return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateContextAttribsARB, badValue = 0)
        }
        if (state.hasResource(context)) return writeError(error = 11, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateContextAttribsARB, badValue = context)
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

    private fun glxCreatePixmap(body: ByteArray) {
        if (body.size != 16) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateGLXPixmap, badValue = 0)
        val screen = byteOrder.u32(body, 0)
        val visual = byteOrder.u32(body, 4)
        val pixmap = byteOrder.u32(body, 8)
        val glxPixmap = byteOrder.u32(body, 12)
        if (screen != 0) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateGLXPixmap, badValue = screen)
        if (visual != X11Ids.RootVisual) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateGLXPixmap, badValue = visual)
        createGlxPixmapResource(
            minorOpcode = XGlx.CreateGLXPixmap,
            pixmap = pixmap,
            glxPixmap = glxPixmap,
            visual = visual,
            fbConfig = XGlx.RootFbConfigId,
            screen = screen,
        )
    }

    private fun glxCreateFbConfigPixmap(body: ByteArray) {
        if (body.size < 20) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePixmap, badValue = 0)
        val screen = byteOrder.u32(body, 0)
        val fbConfig = byteOrder.u32(body, 4)
        val pixmap = byteOrder.u32(body, 8)
        val glxPixmap = byteOrder.u32(body, 12)
        val attribCount = byteOrder.u32(body, 16).toUInt().toLong()
        if (attribCount > (UInt.MAX_VALUE.toLong() shr 3)) {
            return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePixmap, badValue = byteOrder.u32(body, 16))
        }
        val expectedSize = 20L + attribCount * 8L
        if (expectedSize > Int.MAX_VALUE || body.size != expectedSize.toInt()) {
            return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePixmap, badValue = 0)
        }
        val attributes = glxAttributePairs(body, 20, attribCount.toInt())
        if (screen != 0) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePixmap, badValue = screen)
        if (fbConfig != XGlx.RootFbConfigId) return writeError(error = XGlx.BadFBConfig, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePixmap, badValue = fbConfig)
        createGlxPixmapResource(
            minorOpcode = XGlx.CreatePixmap,
            pixmap = pixmap,
            glxPixmap = glxPixmap,
            visual = X11Ids.RootVisual,
            fbConfig = fbConfig,
            screen = screen,
            attributes = attributes,
        )
    }

    private fun createGlxPixmapResource(
        minorOpcode: Int,
        pixmap: Int,
        glxPixmap: Int,
        visual: Int,
        fbConfig: Int,
        screen: Int,
        attributes: List<Pair<Int, Int>> = emptyList(),
    ) {
        if (state.hasResource(glxPixmap)) return writeError(error = 11, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = glxPixmap)
        if (state.drawable(pixmap) == null) return writeError(error = 9, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = pixmap)
        val backingPixmap = state.pixmap(pixmap) ?: return writeError(error = 4, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = pixmap)
        state.putGlxPixmap(
            XGlxPixmap(
                id = glxPixmap,
                pixmapId = pixmap,
                visualId = visual,
                fbConfigId = fbConfig,
                screen = screen,
                width = backingPixmap.width,
                height = backingPixmap.height,
                depth = backingPixmap.depth,
                textureTarget = glxTextureTarget(backingPixmap.width, backingPixmap.height, attributes),
            ),
        )
        own(glxPixmap)
    }

    private fun glxDestroyPixmap(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyGLXPixmap, badValue = 0)
        val glxPixmap = byteOrder.u32(body, 0)
        if (!state.hasGlxPixmap(glxPixmap)) return writeError(error = XGlx.BadPixmap, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyGLXPixmap, badValue = glxPixmap)
        state.removeGlxPixmap(glxPixmap)
        ownedResources.remove(glxPixmap)
    }

    private fun glxDestroyFbConfigPixmap(body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyPixmap, badValue = 0)
        val glxPixmap = byteOrder.u32(body, 0)
        if (!state.hasGlxPixmap(glxPixmap)) return writeError(error = XGlx.BadPixmap, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyPixmap, badValue = glxPixmap)
        state.removeGlxPixmap(glxPixmap)
        ownedResources.remove(glxPixmap)
    }

    private fun glxDestroyContext(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = 4, badValue = 0)
        val context = byteOrder.u32(body, 0)
        state.removeGlxContext(context)
        ownedResources.remove(context)
    }

    private fun glxCreateWindow(body: ByteArray) {
        if (body.size < 20) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateWindow, badValue = 0)
        val screen = byteOrder.u32(body, 0)
        val fbConfig = byteOrder.u32(body, 4)
        val window = byteOrder.u32(body, 8)
        val glxWindow = byteOrder.u32(body, 12)
        val attribCount = byteOrder.u32(body, 16).toUInt().toLong()
        if (attribCount > (UInt.MAX_VALUE.toLong() shr 3)) {
            return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateWindow, badValue = byteOrder.u32(body, 16))
        }
        val expectedSize = 20L + attribCount * 8L
        if (expectedSize > Int.MAX_VALUE || body.size != expectedSize.toInt()) {
            return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateWindow, badValue = 0)
        }
        if (screen != 0) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateWindow, badValue = screen)
        if (fbConfig != XGlx.RootFbConfigId) return writeError(error = XGlx.BadFBConfig, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateWindow, badValue = fbConfig)
        if (state.window(window) == null) return writeError(error = 3, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateWindow, badValue = window)
        if (state.hasResource(glxWindow) || state.hasGlxWindowForWindow(window)) {
            return writeError(error = 11, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreateWindow, badValue = glxWindow)
        }
        state.putGlxWindow(
            XGlxWindow(
                id = glxWindow,
                windowId = window,
                fbConfigId = fbConfig,
                screen = screen,
            ),
        )
        own(glxWindow)
    }

    private fun glxDestroyWindow(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyWindow, badValue = 0)
        val glxWindow = byteOrder.u32(body, 0)
        if (!state.hasGlxWindow(glxWindow)) return writeError(error = XGlx.BadWindow, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyWindow, badValue = glxWindow)
        state.removeGlxWindow(glxWindow)
        ownedResources.remove(glxWindow)
    }

    private fun glxCreatePbuffer(body: ByteArray) {
        if (body.size < 16) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePbuffer, badValue = 0)
        val screen = byteOrder.u32(body, 0)
        val fbConfig = byteOrder.u32(body, 4)
        val pbuffer = byteOrder.u32(body, 8)
        val attribCount = byteOrder.u32(body, 12).toUInt().toLong()
        if (attribCount > (UInt.MAX_VALUE.toLong() shr 3)) {
            return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePbuffer, badValue = byteOrder.u32(body, 12))
        }
        val expectedSize = 16L + attribCount * 8L
        if (expectedSize > Int.MAX_VALUE || body.size != expectedSize.toInt()) {
            return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePbuffer, badValue = 0)
        }
        val attributes = glxAttributePairs(body, 16, attribCount.toInt())
        if (screen != 0) return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePbuffer, badValue = screen)
        if (fbConfig != XGlx.RootFbConfigId) return writeError(error = XGlx.BadFBConfig, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePbuffer, badValue = fbConfig)
        if (state.hasResource(pbuffer)) return writeError(error = 11, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CreatePbuffer, badValue = pbuffer)
        val width = attributes.lastOrNull { (attribute, _) -> attribute == XGlx.PbufferWidth }?.second ?: 0
        val height = attributes.lastOrNull { (attribute, _) -> attribute == XGlx.PbufferHeight }?.second ?: 0
        state.putGlxPbuffer(
            XGlxPbuffer(
                id = pbuffer,
                fbConfigId = fbConfig,
                screen = screen,
                width = width,
                height = height,
            ),
        )
        own(pbuffer)
    }

    private fun glxDestroyPbuffer(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyPbuffer, badValue = 0)
        val pbuffer = byteOrder.u32(body, 0)
        if (!state.hasGlxPbuffer(pbuffer)) return writeError(error = XGlx.BadPbuffer, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.DestroyPbuffer, badValue = pbuffer)
        state.removeGlxPbuffer(pbuffer)
        ownedResources.remove(pbuffer)
    }

    private fun glxQueryContext(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.QueryContext, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val glxContext = state.glxContext(context)
            ?: return writeError(error = XGlx.BadContext, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.QueryContext, badValue = context)
        val attributes = intArrayOf(
            XGlx.ShareContextExt,
            0,
            XGlx.VisualIdExt,
            glxContext.fbConfigId,
            XGlx.ScreenExt,
            glxContext.screen,
            XGlx.FbConfigId,
            glxContext.fbConfigId,
            XGlx.RenderType,
            glxContext.renderType,
        )
        val reply = reply(extra = 0, payloadUnits = attributes.size)
        byteOrder.put32(reply, 8, attributes.size / 2)
        putIntArray(reply, 32, attributes)
        write(reply)
    }

    private fun glxMakeCurrent(body: ByteArray, isContextCurrent: Boolean) {
        val minorOpcode = if (isContextCurrent) XGlx.MakeContextCurrent else 5
        val oldTagOffset = if (isContextCurrent) 0 else 8
        val contextOffset = if (isContextCurrent) 12 else 4
        val expectedSize = if (isContextCurrent) 16 else 12
        if (body.size != expectedSize) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
        val oldTag = byteOrder.u32(body, oldTagOffset)
        if (oldTag != 0 && state.glxContext(oldTag) != null && glxRejectPendingLargeRender(oldTag, minorOpcode)) return
        val context = byteOrder.u32(body, contextOffset)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, if (context == 0 || state.glxContext(context) != null) context else 0)
        write(reply)
    }

    private fun glxWait(body: ByteArray, minorOpcode: Int) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = 0)
        val contextTag = byteOrder.u32(body, 0)
        if (contextTag != 0 && state.glxContext(contextTag) == null) {
            return writeError(error = XGlx.BadContextTag, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = contextTag)
        }
        if (contextTag != 0 && glxRejectPendingLargeRender(contextTag, minorOpcode)) return
    }

    private fun glxSwapBuffers(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.SwapBuffers, badValue = 0)
        val contextTag = byteOrder.u32(body, 0)
        val drawable = byteOrder.u32(body, 4)
        if (contextTag != 0 && state.glxContext(contextTag) == null) {
            return writeError(error = XGlx.BadContextTag, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.SwapBuffers, badValue = contextTag)
        }
        if (contextTag != 0 && glxRejectPendingLargeRender(contextTag, XGlx.SwapBuffers)) return
        if (state.glxPixmap(drawable) == null &&
            state.glxWindow(drawable) == null &&
            state.glxPbuffer(drawable) == null &&
            state.window(drawable) == null
        ) {
            return writeError(error = XGlx.BadDrawable, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.SwapBuffers, badValue = drawable)
        }
    }

    private fun glxUseXFont(body: ByteArray) {
        if (body.size != 20) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.UseXFont, badValue = 0)
        val contextTag = byteOrder.u32(body, 0)
        val fontable = byteOrder.u32(body, 4)
        if (state.glxContext(contextTag) == null) {
            return writeError(error = XGlx.BadContextTag, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.UseXFont, badValue = contextTag)
        }
        if (glxRejectPendingLargeRender(contextTag, XGlx.UseXFont)) return
        if (!state.hasFontable(fontable)) {
            return writeError(error = 7, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.UseXFont, badValue = fontable)
        }
    }

    private fun glxRender(body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.Render, badValue = 0)
        val contextTag = byteOrder.u32(body, 0)
        if (state.glxContext(contextTag) == null) {
            return writeError(error = XGlx.BadContextTag, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.Render, badValue = contextTag)
        }
        if (glxRejectPendingLargeRender(contextTag, XGlx.Render)) return
    }

    private fun glxRenderLarge(body: ByteArray) {
        if (body.size < 12) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = 0)
        val contextTag = byteOrder.u32(body, 0)
        val requestNumber = byteOrder.u16(body, 4)
        val requestTotal = byteOrder.u16(body, 6)
        val dataBytes = byteOrder.u32(body, 8).toUInt().toLong()
        if (state.glxContext(contextTag) == null) {
            return writeError(error = XGlx.BadContextTag, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = contextTag)
        }
        val expectedBytes = 12L + paddedLength(dataBytes)
        if (expectedBytes > Int.MAX_VALUE || body.size.toLong() != expectedBytes) {
            state.removeGlxLargeRender(contextTag)
            return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = 0)
        }
        val pending = state.glxLargeRender(contextTag)
        if (pending == null) {
            if (requestNumber != 1 || requestTotal < 1) {
                return writeError(error = XGlx.BadLargeRequest, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = requestNumber)
            }
            if (dataBytes < 8 || body.size < 20) {
                return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = 0)
            }
            val commandBytes = byteOrder.u32(body, 12).toUInt().toLong()
            val paddedTotalBytes = paddedLength(commandBytes)
            if (paddedTotalBytes < paddedLength(dataBytes)) {
                return writeError(error = XGlx.BadLargeRequest, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = dataBytes.toInt())
            }
            if (requestNumber == requestTotal) {
                if (paddedLength(dataBytes) != paddedTotalBytes) {
                    return writeError(error = XGlx.BadLargeRequest, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = dataBytes.toInt())
                }
                return
            }
            state.putGlxLargeRender(
                XGlxLargeRenderState(
                    contextTag = contextTag,
                    requestTotal = requestTotal,
                    requestsSoFar = 1,
                    bytesSoFar = dataBytes,
                    paddedTotalBytes = paddedTotalBytes,
                ),
            )
            return
        }
        if (pending.contextTag != contextTag || requestNumber != pending.requestsSoFar + 1 || requestTotal != pending.requestTotal) {
            state.removeGlxLargeRender(contextTag)
            return writeError(error = XGlx.BadLargeRequest, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = requestNumber)
        }
        val bytesSoFar = pending.bytesSoFar + dataBytes
        if (bytesSoFar > pending.paddedTotalBytes) {
            state.removeGlxLargeRender(contextTag)
            return writeError(error = XGlx.BadLargeRequest, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = dataBytes.toInt())
        }
        if (requestNumber == requestTotal) {
            state.removeGlxLargeRender(contextTag)
            // Xorg/Xvfb compare the padded accumulated count here because common
            // clients pad the large-command total but not per-request dataBytes.
            if (paddedLength(bytesSoFar) != pending.paddedTotalBytes) {
                return writeError(error = XGlx.BadLargeRequest, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.RenderLarge, badValue = dataBytes.toInt())
            }
        } else {
            state.putGlxLargeRender(pending.copy(requestsSoFar = requestNumber, bytesSoFar = bytesSoFar))
        }
    }

    private fun glxRejectPendingLargeRender(contextTag: Int, minorOpcode: Int): Boolean {
        val pending = state.glxLargeRender(contextTag) ?: return false
        if (pending.contextTag != contextTag) return false
        writeError(error = XGlx.BadLargeRequest, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = minorOpcode)
        return true
    }

    private fun glxCopyContext(body: ByteArray) {
        if (body.size != 16) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CopyContext, badValue = 0)
        val source = byteOrder.u32(body, 0)
        val destination = byteOrder.u32(body, 4)
        val contextTag = byteOrder.u32(body, 12)
        val sourceContext = state.glxContext(source)
            ?: return writeError(error = XGlx.BadContext, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CopyContext, badValue = source)
        state.glxContext(destination)
            ?: return writeError(error = XGlx.BadContext, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CopyContext, badValue = destination)
        if (contextTag != 0) {
            val tagContext = state.glxContext(contextTag)
                ?: return writeError(error = XGlx.BadContextTag, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CopyContext, badValue = contextTag)
            if (tagContext.id != sourceContext.id) {
                return writeError(error = 8, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.CopyContext, badValue = source)
            }
            if (glxRejectPendingLargeRender(contextTag, XGlx.CopyContext)) return
        }
    }

    private fun glxGetDrawableAttributes(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.GetDrawableAttributes, badValue = 0)
        val drawableId = byteOrder.u32(body, 0)
        val glxPixmap = state.glxPixmap(drawableId)
        val glxWindow = state.glxWindow(drawableId)
        val glxPbuffer = state.glxPbuffer(drawableId)
        val window = glxWindow?.let { state.window(it.windowId) } ?: state.window(drawableId)
        if (glxPixmap == null && glxPbuffer == null && window == null) {
            return writeError(error = XGlx.BadDrawable, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.GetDrawableAttributes, badValue = drawableId)
        }
        val attributes = mutableListOf(
            XGlx.YInvertedExt to 0,
            XGlx.Width to (glxPixmap?.width ?: glxPbuffer?.width ?: window!!.width),
            XGlx.Height to (glxPixmap?.height ?: glxPbuffer?.height ?: window!!.height),
            XGlx.ScreenExt to (glxPixmap?.screen ?: glxPbuffer?.screen ?: glxWindow?.screen ?: 0),
        )
        if (glxPixmap != null) {
            attributes += XGlx.TextureTargetExt to glxPixmap.textureTarget
            attributes += XGlx.EventMask to glxPixmap.eventMask
            attributes += XGlx.FbConfigId to glxPixmap.fbConfigId
            attributes += XGlx.DrawableType to XGlx.PixmapBit
        } else if (glxPbuffer != null) {
            attributes += XGlx.TextureTargetExt to glxTextureTarget(glxPbuffer.width, glxPbuffer.height)
            attributes += XGlx.EventMask to glxPbuffer.eventMask
            attributes += XGlx.FbConfigId to glxPbuffer.fbConfigId
            attributes += XGlx.PreservedContents to 1
            attributes += XGlx.DrawableType to XGlx.PbufferBit
        } else if (glxWindow != null) {
            attributes += XGlx.TextureTargetExt to glxTextureTarget(window!!.width, window.height)
            attributes += XGlx.EventMask to glxWindow.eventMask
            attributes += XGlx.FbConfigId to glxWindow.fbConfigId
            attributes += XGlx.DrawableType to XGlx.WindowBit
        } else {
            attributes += XGlx.DrawableType to XGlx.WindowBit
        }
        val flat = attributes.flatMap { (attribute, value) -> listOf(attribute, value) }.toIntArray()
        val reply = reply(extra = 0, payloadUnits = flat.size)
        byteOrder.put32(reply, 8, attributes.size)
        putIntArray(reply, 32, flat)
        write(reply)
    }

    private fun glxChangeDrawableAttributes(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.ChangeDrawableAttributes, badValue = 0)
        val drawableId = byteOrder.u32(body, 0)
        val attribCount = byteOrder.u32(body, 4).toUInt().toLong()
        if (attribCount > (UInt.MAX_VALUE.toLong() shr 3)) {
            return writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.ChangeDrawableAttributes, badValue = byteOrder.u32(body, 4))
        }
        val expectedSize = 8L + attribCount * 8L
        if (expectedSize > Int.MAX_VALUE || body.size != expectedSize.toInt()) {
            return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.ChangeDrawableAttributes, badValue = 0)
        }
        val glxPixmap = state.glxPixmap(drawableId)
        val glxWindow = state.glxWindow(drawableId)
        val glxPbuffer = state.glxPbuffer(drawableId)
        if (glxPixmap == null && glxWindow == null && glxPbuffer == null) {
            return writeError(error = XGlx.BadDrawable, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.ChangeDrawableAttributes, badValue = drawableId)
        }
        val eventMask = glxAttributePairs(body, 8, attribCount.toInt())
            .lastOrNull { (attribute, _) -> attribute == XGlx.EventMask }
            ?.second
        if (eventMask != null) {
            if (glxPixmap != null) {
                state.putGlxPixmap(glxPixmap.copy(eventMask = eventMask))
            } else if (glxWindow != null) {
                state.putGlxWindow(glxWindow.copy(eventMask = eventMask))
            } else if (glxPbuffer != null) {
                state.putGlxPbuffer(glxPbuffer.copy(eventMask = eventMask))
            }
        }
    }

    private fun glxAttributePairs(body: ByteArray, offset: Int, count: Int): List<Pair<Int, Int>> =
        List(count) { index ->
            val pairOffset = offset + index * 8
            byteOrder.u32(body, pairOffset) to byteOrder.u32(body, pairOffset + 4)
        }

    private fun glxTextureTarget(width: Int, height: Int, attributes: List<Pair<Int, Int>> = emptyList()): Int {
        for ((attribute, value) in attributes) {
            if (attribute == XGlx.TextureTargetExt && (value == XGlx.Texture2DExt || value == XGlx.TextureRectangleExt)) {
                return value
            }
        }
        return if (width.isPowerOfTwo() && height.isPowerOfTwo()) XGlx.Texture2DExt else XGlx.TextureRectangleExt
    }

    private fun Int.isPowerOfTwo(): Boolean = this > 0 && (this and (this - 1)) == 0

    private fun glxIsDirect(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = XGlx.MajorOpcode, minorOpcode = XGlx.IsDirect, badValue = 0)
        val context = byteOrder.u32(body, 0)
        val reply = reply(extra = 0, payloadUnits = 0)
        reply[8] = if (state.glxContext(context)?.direct == true) 1 else 0
        write(reply)
    }

    private fun glxScreenIsValid(body: ByteArray, offset: Int, minorOpcode: Int): Boolean {
        // Callers validate fixed request length before using this shared screen check.
        val screen = byteOrder.u32(body, offset)
        if (screen == 0) return true
        writeError(error = 2, opcode = XGlx.MajorOpcode, minorOpcode = minorOpcode, badValue = screen)
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
            XGlx.SetClientInfoARB -> "versions=${u32(0)} glBytes=${u32(4)} glxBytes=${u32(8)}"
            XGlx.SetClientInfo2ARB -> "versions=${u32(0)} glBytes=${u32(4)} glxBytes=${u32(8)}"
            XGlx.GetFBConfigs -> "screen=${u32(0)}"
            XGlx.CreateNewContext -> "context=${hex(0)} fbconfig=${hex(4)} screen=${u32(8)} renderType=${hex(12)} direct=${body.getOrNull(20)?.toInt() == 1}"
            XGlx.QueryContext -> "context=${hex(0)}"
            XGlx.MakeContextCurrent -> "oldTag=${hex(0)} drawable=${hex(4)} readDrawable=${hex(8)} context=${hex(12)}"
            XGlx.CreatePbuffer -> "screen=${u32(0)} fbconfig=${hex(4)} pbuffer=${hex(8)} attribs=${u32(12)}"
            XGlx.DestroyPbuffer -> "pbuffer=${hex(0)}"
            XGlx.GetDrawableAttributes -> "drawable=${hex(0)}"
            XGlx.ChangeDrawableAttributes -> "drawable=${hex(0)} attribs=${u32(4)}"
            XGlx.CreateWindow -> "screen=${u32(0)} fbconfig=${hex(4)} window=${hex(8)} glxWindow=${hex(12)} attribs=${u32(16)}"
            XGlx.DestroyWindow -> "glxWindow=${hex(0)}"
            XGlx.CreateContextAttribsARB -> "context=${hex(0)} fbconfig=${hex(4)} screen=${u32(8)} share=${hex(12)} direct=${body.getOrNull(16)?.toInt() == 1} attribs=${u32(20)}"
            XGlx.Render -> "contextTag=${hex(0)} bytes=${(body.size - 4).coerceAtLeast(0)}"
            XGlx.RenderLarge -> "contextTag=${hex(0)} dataBytes=${u32(8)} request=${if (body.size >= 12) "${byteOrder.u16(body, 4)}/${byteOrder.u16(body, 6)}" else "n/a"}"
            XGlx.WaitGL, XGlx.WaitX -> "contextTag=${hex(0)}"
            XGlx.CopyContext -> "source=${hex(0)} destination=${hex(4)} mask=${hex(8)} contextTag=${hex(12)}"
            XGlx.SwapBuffers -> "contextTag=${hex(0)} drawable=${hex(4)}"
            XGlx.UseXFont -> "contextTag=${hex(0)} font=${hex(4)} first=${u32(8)} count=${u32(12)} listBase=${u32(16)}"
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

    private fun createWindow(requestedDepth: Int, body: ByteArray) {
        if (body.size < 28) return writeError(16, 1, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val parent = byteOrder.u32(body, 4)
        val width = byteOrder.u16(body, 12)
        val height = byteOrder.u16(body, 14)
        val borderWidth = byteOrder.u16(body, 16)
        val requestedClass = byteOrder.u16(body, 18)
        val requestedVisual = byteOrder.u32(body, 20)
        val mask = byteOrder.u32(body, 24)
        val expectedSize = 28 + mask.countOneBits() * 4
        if (body.size != expectedSize) return writeError(error = 16, opcode = 1, badValue = 0)
        if ((mask and WindowAttributeValueMask.inv()) != 0) {
            return writeError(error = 2, opcode = 1, badValue = mask)
        }
        if (state.hasResource(id)) return writeError(error = 14, opcode = 1, badValue = id)
        val parentWindow = state.window(parent) ?: return writeError(error = 3, opcode = 1, badValue = parent)
        if (width == 0) return writeError(error = 2, opcode = 1, badValue = width)
        if (height == 0) return writeError(error = 2, opcode = 1, badValue = height)
        val windowClass = when (requestedClass) {
            XWindowClass.CopyFromParent -> parentWindow.windowClass
            XWindowClass.InputOutput, XWindowClass.InputOnly -> requestedClass
            else -> return writeError(error = 2, opcode = 1, badValue = requestedClass)
        }
        val depth = if (requestedDepth == 0 && windowClass != XWindowClass.InputOnly) parentWindow.depth else requestedDepth
        val visual = if (requestedVisual == XWindowClass.CopyFromParent) parentWindow.visual else requestedVisual
        when (windowClass) {
            XWindowClass.InputOutput -> {
                if (parentWindow.windowClass == XWindowClass.InputOnly) return writeError(error = 8, opcode = 1, badValue = parent)
                if (depth != X11Ids.RootDepth) return writeError(error = 8, opcode = 1, badValue = requestedDepth)
                if (visual != X11Ids.RootVisual) return writeError(error = 8, opcode = 1, badValue = requestedVisual)
            }
            XWindowClass.InputOnly -> {
                if (requestedDepth != 0) return writeError(error = 8, opcode = 1, badValue = requestedDepth)
                if (visual != X11Ids.RootVisual) return writeError(error = 8, opcode = 1, badValue = requestedVisual)
                if (borderWidth != 0) return writeError(error = 8, opcode = 1, badValue = borderWidth)
                if ((mask and InputOnlyWindowAttributeValueMask.inv()) != 0) {
                    return writeError(error = 8, opcode = 1, badValue = mask)
                }
            }
        }
        val attributes = windowAttributeValues(body, maskOffset = 24, valuesOffset = 28)
        attributes.eventMask?.let {
            if ((it and XEventMasks.ValidCoreMask.inv()) != 0) return writeError(error = 2, opcode = 1, badValue = it)
        }
        attributes.doNotPropagateMask?.let {
            if ((it and XEventMasks.ValidDeviceEventMask.inv()) != 0) return writeError(error = 2, opcode = 1, badValue = it)
        }
        val window = XWindow(
            id = id,
            parentId = parent,
            windowClass = windowClass,
            depth = depth,
            visual = visual,
            x = byteOrder.i16(body, 8),
            y = byteOrder.i16(body, 10),
            width = width,
            height = height,
            borderWidth = borderWidth,
            backgroundPixel = attributes.backgroundPixel ?: 0x00ff_ffff,
            backgroundPixmapId = attributes.backgroundPixmapId?.takeIf { it != 0 },
            overrideRedirect = attributes.overrideRedirect ?: false,
            doNotPropagateMask = attributes.doNotPropagateMask ?: 0,
        )
        state.putWindow(window, this)
        own(id)
        sendCreateNotify(state.createNotifySinks(window))
        attributes.eventMask?.let { state.selectEvents(this, id, it) }
    }

    private fun changeWindowAttributes(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = 2, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val mask = byteOrder.u32(body, 4)
        val expectedSize = 8 + mask.countOneBits() * 4
        if (body.size != expectedSize) return writeError(error = 16, opcode = 2, badValue = 0)
        if ((mask and WindowAttributeValueMask.inv()) != 0) {
            return writeError(error = 2, opcode = 2, badValue = mask)
        }
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 2, badValue = windowId)
        if (window.windowClass == XWindowClass.InputOnly && (mask and InputOnlyWindowAttributeValueMask.inv()) != 0) {
            return writeError(error = 8, opcode = 2, badValue = mask)
        }
        val attributes = windowAttributeValues(body, maskOffset = 4, valuesOffset = 8)
        attributes.eventMask?.let {
            if ((it and XEventMasks.ValidCoreMask.inv()) != 0) return writeError(error = 2, opcode = 2, badValue = it)
            if (!state.canSelectEvents(this, windowId, it)) return writeError(error = 10, opcode = 2, badValue = 0)
        }
        attributes.doNotPropagateMask?.let {
            if ((it and XEventMasks.ValidDeviceEventMask.inv()) != 0) return writeError(error = 2, opcode = 2, badValue = it)
        }
        if (attributes.backgroundPixel != null || attributes.backgroundPixmapId != null) {
            state.updateWindowAttributes(windowId, backgroundPixel = attributes.backgroundPixel, backgroundPixmapId = attributes.backgroundPixmapId)
        }
        attributes.overrideRedirect?.let { state.updateWindowAttributes(windowId, overrideRedirect = it) }
        attributes.doNotPropagateMask?.let { state.updateWindowAttributes(windowId, doNotPropagateMask = it) }
        attributes.eventMask?.let { state.selectEvents(this, windowId, it) }
    }

    private fun destroyWindow(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 4, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        state.window(windowId) ?: return writeError(error = 3, opcode = 4, badValue = windowId)
        val removal = state.removeWindowWithDestroyNotify(windowId)
        ownedResources.removeAll(removal.removedResources)
        sendDestroyNotify(removal.destroyNotifyDispatches)
    }

    private fun destroySubwindows(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 5, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        state.window(windowId) ?: return writeError(error = 3, opcode = 5, badValue = windowId)
        for (child in state.childrenOf(windowId)) {
            val removal = state.removeWindowWithDestroyNotify(child.id)
            ownedResources.removeAll(removal.removedResources)
            sendDestroyNotify(removal.destroyNotifyDispatches)
        }
    }

    private fun changeSaveSet(mode: Int, body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 6, badValue = 0)
        if (mode !in XSaveSetMode.Insert..XSaveSetMode.Delete) {
            return writeError(error = 2, opcode = 6, badValue = mode)
        }
        val windowId = byteOrder.u32(body, 0)
        state.window(windowId) ?: return writeError(error = 3, opcode = 6, badValue = windowId)
        val owner = state.windowOwner(windowId)
        if (owner == null || owner == this) return writeError(error = 8, opcode = 6, badValue = 0)
        state.changeSaveSet(this, windowId, insert = mode == XSaveSetMode.Insert)
    }

    private fun reparentWindow(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 7, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val parentId = byteOrder.u32(body, 4)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 7, badValue = windowId)
        val parent = state.window(parentId) ?: return writeError(error = 3, opcode = 7, badValue = parentId)
        if (window.windowClass == XWindowClass.InputOutput && parent.windowClass == XWindowClass.InputOnly) {
            return writeError(error = 8, opcode = 7, badValue = parentId)
        }
        if (!state.canReparentWindow(windowId, parentId)) {
            return writeError(error = 8, opcode = 7, badValue = 0)
        }
        val wasMapped = window.mapped
        if (wasMapped) {
            val notifications = state.unmapNotifySinks(window)
            state.unmapWindow(windowId)
            sendUnmapNotify(notifications)
        }
        val oldParentId = window.parentId
        val reparented = state.reparentWindow(
            id = windowId,
            parentId = parentId,
            x = byteOrder.i16(body, 8),
            y = byteOrder.i16(body, 10),
        ) ?: return
        sendReparentNotify(state.reparentNotifySinks(reparented, oldParentId))
        if (wasMapped) {
            val notifications = state.mapNotifySinks(reparented)
            val mapped = state.mapWindow(windowId) ?: return
            if (mapped.windowClass == XWindowClass.InputOutput) {
                state.paintWindowBackground(mapped.id)
            }
            sendMapNotify(notifications)
            if (mapped.windowClass == XWindowClass.InputOutput) {
                sendExpose(mapped)
            }
        }
    }

    private fun mapWindow(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 8, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val current = state.window(windowId) ?: return writeError(error = 3, opcode = 8, badValue = windowId)
        if (current.mapped) return
        if (!current.overrideRedirect) {
            val mapRequests = state.mapRequestSinks(this, current)
            if (mapRequests.isNotEmpty()) {
                sendMapRequest(mapRequests)
                return
            }
        }
        val notifications = state.mapNotifySinks(current)
        val window = state.mapWindow(windowId) ?: return
        if (window.windowClass == XWindowClass.InputOutput) {
            state.paintWindowBackground(window.id)
        }
        sendMapNotify(notifications)
        if (window.windowClass == XWindowClass.InputOutput) {
            sendExpose(window)
        }
    }

    private fun unmapWindow(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 10, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 10, badValue = windowId)
        if (!window.mapped) return
        val notifications = state.unmapNotifySinks(window)
        state.unmapWindow(windowId)
        sendUnmapNotify(notifications)
    }

    private fun mapSubwindows(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 9, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        state.window(windowId) ?: return writeError(error = 3, opcode = 9, badValue = windowId)
        for (child in state.childrenOf(windowId).asReversed()) {
            if (!child.mapped) {
                if (!child.overrideRedirect) {
                    val mapRequests = state.mapRequestSinks(this, child)
                    if (mapRequests.isNotEmpty()) {
                        sendMapRequest(mapRequests)
                        continue
                    }
                }
                val notifications = state.mapNotifySinks(child)
                val mapped = state.mapWindow(child.id) ?: continue
                if (mapped.windowClass == XWindowClass.InputOutput) {
                    state.paintWindowBackground(mapped.id)
                }
                sendMapNotify(notifications)
                if (mapped.windowClass == XWindowClass.InputOutput) {
                    sendExpose(mapped)
                }
            }
        }
    }

    private fun unmapSubwindows(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 11, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        state.window(windowId) ?: return writeError(error = 3, opcode = 11, badValue = windowId)
        for (child in state.childrenOf(windowId)) {
            if (child.mapped) {
                val notifications = state.unmapNotifySinks(child)
                state.unmapWindow(child.id)
                sendUnmapNotify(notifications)
            }
        }
    }

    private fun configureWindow(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = 12, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val mask = byteOrder.u16(body, 4)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 12, badValue = windowId)
        val expectedSize = 8 + mask.countOneBits() * 4
        if (body.size != expectedSize) return writeError(error = 16, opcode = 12, badValue = 0)
        if ((mask and ConfigureWindowValueMask.inv()) != 0) {
            return writeError(error = 2, opcode = 12, badValue = mask)
        }
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
        val siblingId = if ((mask and 0x0020) != 0) next() else null
        val stackMode = if ((mask and 0x0040) != 0) next() else null
        if (width == 0) return writeError(error = 2, opcode = 12, badValue = width)
        if (height == 0) return writeError(error = 2, opcode = 12, badValue = height)
        if (window.windowClass == XWindowClass.InputOnly && borderWidth != null && borderWidth != 0) {
            return writeError(error = 8, opcode = 12, badValue = borderWidth)
        }
        if (siblingId != null && stackMode == null) {
            return writeError(error = 8, opcode = 12, badValue = siblingId)
        }
        if (stackMode != null && stackMode !in XStackMode.Above..XStackMode.Opposite) {
            return writeError(error = 2, opcode = 12, badValue = stackMode)
        }
        if (siblingId != null) {
            val sibling = state.window(siblingId) ?: return writeError(error = 3, opcode = 12, badValue = siblingId)
            if (sibling.id == window.id || sibling.parentId != window.parentId) {
                return writeError(error = 8, opcode = 12, badValue = siblingId)
            }
        }
        if (!window.overrideRedirect) {
            val configureRequests = state.configureRequestSinks(
                requester = this,
                window = window,
                x = x,
                y = y,
                width = width,
                height = height,
                borderWidth = borderWidth,
                siblingId = siblingId,
                stackMode = stackMode,
                valueMask = mask,
            )
            if (configureRequests.isNotEmpty()) {
                sendConfigureRequest(configureRequests)
                return
            }
        }
        val requestedWidth = width ?: window.width
        val requestedHeight = height ?: window.height
        val resizeRedirected = requestedWidth != window.width || requestedHeight != window.height
        val resizeRequests = if (resizeRedirected) {
            state.resizeRequestSinks(this, window, requestedWidth, requestedHeight)
        } else {
            emptyList()
        }
        val effectiveWidth = width.takeIf { resizeRequests.isEmpty() }
        val effectiveHeight = height.takeIf { resizeRequests.isEmpty() }
        if (resizeRequests.isNotEmpty()) {
            sendResizeRequest(resizeRequests)
        }
        val configured = state.configureWindow(
            window.id,
            x = x,
            y = y,
            width = effectiveWidth,
            height = effectiveHeight,
            borderWidth = borderWidth,
            siblingId = siblingId,
            stackMode = stackMode,
        ) ?: return
        if (configured.changed) {
            sendConfigureNotify(state.configureNotifySinks(configured))
            if (configured.window.mapped && configured.window.windowClass == XWindowClass.InputOutput && configured.sizeChanged) sendExpose(configured.window)
        }
    }

    private fun circulateWindow(direction: Int, body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 13, badValue = 0)
        if (direction !in XCirculateResult.RaiseLowest..XCirculateResult.LowerHighest) {
            return writeError(error = 2, opcode = 13, badValue = direction)
        }
        val windowId = byteOrder.u32(body, 0)
        state.window(windowId) ?: return writeError(error = 3, opcode = 13, badValue = windowId)
        val target = state.circulateWindowTarget(windowId, direction) ?: return
        val requests = state.circulateRequestSinks(this, target)
        if (requests.isNotEmpty()) {
            sendCirculateRequest(requests)
            return
        }
        val result = state.circulateWindow(windowId, direction) ?: return
        sendCirculateNotify(state.circulateNotifySinks(result))
    }

    private fun getWindowAttributes(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 3, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 3, badValue = windowId)
        val reply = reply(extra = 0, payloadUnits = 3)
        byteOrder.put32(reply, 8, window.visual)
        byteOrder.put16(reply, 12, window.windowClass)
        reply[14] = 1
        reply[15] = 1
        byteOrder.put32(reply, 16, -1)
        byteOrder.put32(reply, 20, 0)
        reply[24] = 0
        reply[25] = 1
        reply[26] = if (window.mapped) 2 else 0
        reply[27] = if (window.overrideRedirect) 1 else 0
        byteOrder.put32(reply, 28, X11Ids.DefaultColormap)
        byteOrder.put32(reply, 32, 0)
        byteOrder.put32(reply, 36, 0)
        byteOrder.put16(reply, 40, 0)
        write(reply)
    }

    private fun getGeometry(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 14, badValue = 0)
        val drawableId = byteOrder.u32(body, 0)
        val drawable = state.drawableGeometry(drawableId) ?: return writeError(error = 9, opcode = 14, badValue = drawableId)
        val reply = reply(extra = drawable.depth, payloadUnits = 0)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put16(reply, 12, drawable.x)
        byteOrder.put16(reply, 14, drawable.y)
        byteOrder.put16(reply, 16, drawable.width)
        byteOrder.put16(reply, 18, drawable.height)
        byteOrder.put16(reply, 20, drawable.borderWidth)
        write(reply)
    }

    private fun coreDrawable(opcode: Int, drawableId: Int): XDrawable? {
        val window = state.window(drawableId)
        if (window?.windowClass == XWindowClass.InputOnly) {
            writeError(error = 8, opcode = opcode, badValue = drawableId)
            return null
        }
        return state.drawable(drawableId) ?: run {
            writeError(error = 9, opcode = opcode, badValue = drawableId)
            null
        }
    }

    private fun queryTree(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 15, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 15, badValue = windowId)
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
        if (body.size < 4) return writeError(error = 16, opcode = 16, badValue = 0)
        val onlyIfExists = onlyIfExistsOpcode != 0
        val nameLength = byteOrder.u16(body, 0)
        val expectedSize = 4 + paddedLength(nameLength)
        if (body.size != expectedSize) return writeError(error = 16, opcode = 16, badValue = 0)
        val name = body.copyOfRange(4, 4 + nameLength).decodeToString()
        val atom = state.internAtom(name, onlyIfExists)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, atom)
        write(reply)
    }

    private fun getAtomName(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 17, badValue = 0)
        val atom = byteOrder.u32(body, 0)
        val name = state.atomName(atom) ?: return writeError(error = 5, opcode = 17, badValue = atom)
        val bytes = name.encodeToByteArray()
        val reply = reply(extra = 0, payloadUnits = paddedLength(bytes.size) / 4)
        byteOrder.put16(reply, 8, bytes.size)
        bytes.copyInto(reply, 32)
        write(reply)
    }

    private fun changeProperty(mode: Int, body: ByteArray) {
        if (body.size < 20) return writeError(error = 16, opcode = 18, badValue = 0)
        if (mode !in XPropertyMode.Replace..XPropertyMode.Append) {
            return writeError(error = 2, opcode = 18, badValue = mode)
        }
        val format = body[12].toInt() and 0xff
        if (format !in XPropertyFormat.ValidFormats) return writeError(error = 2, opcode = 18, badValue = format)
        val unitCount = Integer.toUnsignedLong(byteOrder.u32(body, 16))
        val byteLength = unitCount * (format / 8)
        val expectedSize = 20L + paddedLength(byteLength)
        if (expectedSize > Int.MAX_VALUE || body.size != expectedSize.toInt()) {
            return writeError(error = 16, opcode = 18, badValue = 0)
        }
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 18, badValue = windowId)
        val property = byteOrder.u32(body, 4)
        if (state.atomName(property) == null) return writeError(error = 5, opcode = 18, badValue = property)
        val type = byteOrder.u32(body, 8)
        if (state.atomName(type) == null) return writeError(error = 5, opcode = 18, badValue = type)
        val data = propertyDataToServerOrder(format, body, 20, byteLength.toInt())
        val existing = window.properties[property]
        if (mode != XPropertyMode.Replace && existing != null && (existing.type != type || existing.format != format)) {
            return writeError(error = 8, opcode = 18, badValue = 0)
        }
        if (mode != XPropertyMode.Replace && existing != null && byteLength == 0L) {
            sendPropertyNotify(windowId, property, XPropertyState.NewValue)
            return
        }
        window.properties[property] = when {
            existing == null || mode == XPropertyMode.Replace -> XProperty(type = type, format = format, data = data)
            mode == XPropertyMode.Prepend -> existing.copy(data = data + existing.data)
            else -> existing.copy(data = existing.data + data)
        }
        sendPropertyNotify(windowId, property, XPropertyState.NewValue)
    }

    private fun deleteProperty(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 19, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 19, badValue = windowId)
        val property = byteOrder.u32(body, 4)
        if (state.atomName(property) == null) return writeError(error = 5, opcode = 19, badValue = property)
        if (window.properties.remove(property) != null) {
            sendPropertyNotify(windowId, property, XPropertyState.Deleted)
        }
    }

    private fun getProperty(deleteOpcode: Int, body: ByteArray) {
        if (body.size != 20) return writeError(error = 16, opcode = 20, badValue = 0)
        if (deleteOpcode !in XPropertyDelete.False..XPropertyDelete.True) {
            return writeError(error = 2, opcode = 20, badValue = deleteOpcode)
        }
        val delete = deleteOpcode != 0
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(3, 20, badValue = windowId)
        val propertyId = byteOrder.u32(body, 4)
        if (state.atomName(propertyId) == null) return writeError(error = 5, opcode = 20, badValue = propertyId)
        val requestedType = byteOrder.u32(body, 8)
        if (requestedType != XPropertyType.Any && state.atomName(requestedType) == null) {
            return writeError(error = 5, opcode = 20, badValue = requestedType)
        }
        val longOffsetUnits = byteOrder.u32(body, 12)
        val longOffset = Integer.toUnsignedLong(longOffsetUnits).saturatingTimes4()
        val longLength = Integer.toUnsignedLong(byteOrder.u32(body, 16)).saturatingTimes4()
        val property = window.properties[propertyId]
        if (property == null) {
            val reply = reply(extra = 0, payloadUnits = 0)
            byteOrder.put32(reply, 8, 0)
            byteOrder.put32(reply, 12, 0)
            byteOrder.put32(reply, 16, 0)
            return write(reply)
        }
        if (requestedType != XPropertyType.Any && requestedType != property.type) {
            val reply = reply(extra = property.format, payloadUnits = 0)
            byteOrder.put32(reply, 8, property.type)
            byteOrder.put32(reply, 12, property.data.size)
            byteOrder.put32(reply, 16, 0)
            return write(reply)
        }
        if (longOffset > property.data.size.toLong()) {
            return writeError(error = 2, opcode = 20, badValue = longOffsetUnits)
        }
        val available = property.data.drop(longOffset.toInt()).toByteArray()
        val value = available.take(longLength.coerceAtMost(available.size.toLong()).toInt()).toByteArray()
        val bytesAfter = (available.size - value.size).coerceAtLeast(0)
        val reply = reply(extra = property.format, payloadUnits = paddedLength(value.size) / 4)
        byteOrder.put32(reply, 8, property.type)
        byteOrder.put32(reply, 12, bytesAfter)
        byteOrder.put32(reply, 16, value.size / (property.format / 8))
        propertyDataForClientOrder(property.format, value).copyInto(reply, 32)
        val shouldDelete = delete && bytesAfter == 0
        if (shouldDelete) {
            window.properties.remove(propertyId)
        }
        write(reply)
        if (shouldDelete) {
            sendPropertyNotify(windowId, propertyId, XPropertyState.Deleted)
        }
    }

    private fun listProperties(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 21, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(3, 21, badValue = windowId)
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

    private fun rotateProperties(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = 114, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val count = byteOrder.u16(body, 4)
        val delta = byteOrder.i16(body, 6)
        if (body.size != 8 + count * 4) return writeError(error = 16, opcode = 114, badValue = 0)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 114, badValue = windowId)
        val atoms = List(count) { index -> byteOrder.u32(body, 8 + index * 4) }
        val invalidAtom = atoms.firstOrNull { state.atomName(it) == null }
        if (invalidAtom != null) return writeError(error = 5, opcode = 114, badValue = invalidAtom)
        val duplicate = atoms.firstOrNull { atom -> atoms.count { it == atom } > 1 }
        if (duplicate != null) return writeError(error = 8, opcode = 114, badValue = duplicate)
        val missing = atoms.firstOrNull { it !in window.properties }
        if (missing != null) return writeError(error = 8, opcode = 114, badValue = missing)
        if (count == 0) return
        val shift = ((delta % count) + count) % count
        if (shift == 0) return
        val values = atoms.map { window.properties.getValue(it) }
        atoms.forEachIndexed { index, atom ->
            window.properties[atom] = values[(index + shift) % count]
        }
        for (sink in state.propertyNotifySinks(windowId)) {
            for (atom in atoms) {
                runCatching { sink.sendPropertyNotifyEvent(XPropertyNotifyEvent(windowId = windowId, atom = atom, state = 0)) }
            }
        }
    }

    private fun setSelectionOwner(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 22, badValue = 0)
        val owner = byteOrder.u32(body, 0)
        val selection = byteOrder.u32(body, 4)
        val time = byteOrder.u32(body, 8)
        if (state.atomName(selection) == null) return writeError(error = 5, opcode = 22, badValue = selection)
        if (owner != 0 && state.window(owner) == null) return writeError(error = 3, opcode = 22, badValue = owner)
        val clear = state.setSelectionOwner(selection, owner, this, time)
        if (clear != null) {
            runCatching { clear.sink.sendSelectionClearEvent(clear.event) }
        }
    }

    private fun getSelectionOwner(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 23, badValue = 0)
        val selection = byteOrder.u32(body, 0)
        if (state.atomName(selection) == null) return writeError(error = 5, opcode = 23, badValue = selection)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, state.selectionOwner(selection))
        write(reply)
    }

    private fun convertSelection(body: ByteArray) {
        if (body.size != 20) return writeError(error = 16, opcode = 24, badValue = 0)
        val requestor = byteOrder.u32(body, 0)
        val selection = byteOrder.u32(body, 4)
        val target = byteOrder.u32(body, 8)
        val property = byteOrder.u32(body, 12)
        val time = byteOrder.u32(body, 16)
        if (state.window(requestor) == null) return writeError(error = 3, opcode = 24, badValue = requestor)
        if (state.atomName(selection) == null) return writeError(error = 5, opcode = 24, badValue = selection)
        if (state.atomName(target) == null) return writeError(error = 5, opcode = 24, badValue = target)
        if (property != 0 && state.atomName(property) == null) return writeError(error = 5, opcode = 24, badValue = property)

        val selectionRequest = state.selectionRequestDispatch(selection, requestor, target, property, time)
        if (selectionRequest == null) {
            sendSelectionNotify(requestor, selection, target, property = 0, time)
        } else {
            selectionRequest.sink.sendSelectionRequestEvent(selectionRequest.event)
        }
    }

    private fun sendEvent(propagateOpcode: Int, body: ByteArray) {
        if (body.size != 40) return writeError(error = 16, opcode = 25, badValue = 0)
        if (propagateOpcode !in 0..1) return writeError(error = 2, opcode = 25, badValue = propagateOpcode)
        val destination = byteOrder.u32(body, 0)
        val eventMask = byteOrder.u32(body, 4)
        if ((eventMask and XEventMasks.ValidCoreMask.inv()) != 0) return writeError(error = 2, opcode = 25, badValue = eventMask)
        val event = body.copyOfRange(8, 40)
        val eventCode = event[0].toInt() and 0x7f
        if (!validEventCode(eventCode)) return writeError(error = 2, opcode = 25, badValue = eventCode)
        val destinationWindow = when (destination) {
            0 -> state.pointerWindowId() ?: return
            1 -> state.sendEventInputFocusWindowId() ?: return
            else -> destination
        }
        if (state.window(destinationWindow) == null) return writeError(error = 3, opcode = 25, badValue = destination)
        event[0] = (event[0].toInt() or 0x80).toByte()
        val syntheticEvent = XSyntheticEvent(event, byteOrder)
        for (sink in state.sendEventSinks(destinationWindow, eventMask, propagateOpcode != 0, destination == 1)) {
            runCatching { sink.sendSyntheticEvent(syntheticEvent) }
        }
    }

    private fun validEventCode(eventCode: Int): Boolean =
        eventCode in 2..34

    private fun queryPointer(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 38, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        if (state.window(windowId) == null) return writeError(error = 3, opcode = 38, badValue = windowId)
        val pointer = state.queryPointer(windowId) ?: return writeError(error = 3, opcode = 38, badValue = windowId)
        val reply = reply(extra = 1, payloadUnits = 0)
        byteOrder.put32(reply, 8, X11Ids.RootWindow)
        byteOrder.put32(reply, 12, pointer.childWindowId)
        byteOrder.put16(reply, 16, pointer.rootX)
        byteOrder.put16(reply, 18, pointer.rootY)
        byteOrder.put16(reply, 20, pointer.windowX)
        byteOrder.put16(reply, 22, pointer.windowY)
        byteOrder.put16(reply, 24, pointer.mask)
        write(reply)
    }

    private fun grabPointer(ownerEvents: Int, body: ByteArray) {
        if (body.size != 20) return writeError(error = 16, opcode = 26, badValue = 0)
        if (ownerEvents !in 0..1) return writeError(error = 2, opcode = 26, badValue = ownerEvents)
        val grabWindow = byteOrder.u32(body, 0)
        if (state.window(grabWindow) == null) return writeError(error = 3, opcode = 26, badValue = grabWindow)
        val eventMask = byteOrder.u16(body, 4)
        if ((eventMask and XEventMasks.ValidPointerEventMask.inv()) != 0) {
            return writeError(error = 2, opcode = 26, badValue = eventMask)
        }
        val pointerMode = body[6].toInt() and 0xff
        if (pointerMode !in 0..1) return writeError(error = 2, opcode = 26, badValue = pointerMode)
        val keyboardMode = body[7].toInt() and 0xff
        if (keyboardMode !in 0..1) return writeError(error = 2, opcode = 26, badValue = keyboardMode)
        val confineTo = byteOrder.u32(body, 8)
        if (confineTo != 0 && state.window(confineTo) == null) return writeError(error = 3, opcode = 26, badValue = confineTo)
        val cursor = byteOrder.u32(body, 12)
        if (cursor != 0 && !state.hasCursor(cursor)) return writeError(error = 6, opcode = 26, badValue = cursor)
        val time = byteOrder.u32(body, 16)

        val status = state.grabPointer(
            XInputGrab(
                owner = this,
                kind = "pointer",
                windowId = grabWindow,
                ownerEvents = ownerEvents != 0,
                eventMask = eventMask,
                pointerMode = pointerMode,
                keyboardMode = keyboardMode,
                confineTo = confineTo.takeIf { it != 0 },
                cursor = cursor.takeIf { it != 0 },
                time = time,
            ),
        )
        write(reply(extra = status, payloadUnits = 0))
    }

    private fun ungrabPointer(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 27, badValue = 0)
        state.ungrabPointer(this, byteOrder.u32(body, 0))
    }

    private fun grabButton(ownerEvents: Int, body: ByteArray) {
        if (body.size != 20) return writeError(error = 16, opcode = 28, badValue = 0)
        if (ownerEvents !in 0..1) return writeError(error = 2, opcode = 28, badValue = ownerEvents)
        val grabWindow = byteOrder.u32(body, 0)
        if (state.window(grabWindow) == null) return writeError(error = 3, opcode = 28, badValue = grabWindow)
        val eventMask = byteOrder.u16(body, 4)
        if ((eventMask and XEventMasks.ValidPointerEventMask.inv()) != 0) {
            return writeError(error = 2, opcode = 28, badValue = eventMask)
        }
        val pointerMode = body[6].toInt() and 0xff
        if (pointerMode !in 0..1) return writeError(error = 2, opcode = 28, badValue = pointerMode)
        val keyboardMode = body[7].toInt() and 0xff
        if (keyboardMode !in 0..1) return writeError(error = 2, opcode = 28, badValue = keyboardMode)
        val confineTo = byteOrder.u32(body, 8)
        if (confineTo != 0 && state.window(confineTo) == null) return writeError(error = 3, opcode = 28, badValue = confineTo)
        val cursor = byteOrder.u32(body, 12)
        if (cursor != 0 && !state.hasCursor(cursor)) return writeError(error = 6, opcode = 28, badValue = cursor)
        val button = body[16].toInt() and 0xff
        val modifiers = byteOrder.u16(body, 18)
        if (!validGrabModifiers(modifiers)) return writeError(error = 2, opcode = 28, badValue = modifiers)

        val grabbed = state.grabButton(
            XPassiveButtonGrab(
                owner = this,
                windowId = grabWindow,
                ownerEvents = ownerEvents != 0,
                eventMask = eventMask,
                pointerMode = pointerMode,
                keyboardMode = keyboardMode,
                confineTo = confineTo.takeIf { it != 0 },
                cursor = cursor.takeIf { it != 0 },
                button = button,
                modifiers = modifiers,
            ),
        )
        if (!grabbed) writeError(error = 10, opcode = 28, badValue = 0)
    }

    private fun ungrabButton(button: Int, body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 29, badValue = 0)
        val grabWindow = byteOrder.u32(body, 0)
        if (state.window(grabWindow) == null) return writeError(error = 3, opcode = 29, badValue = grabWindow)
        val modifiers = byteOrder.u16(body, 4)
        if (!validGrabModifiers(modifiers)) return writeError(error = 2, opcode = 29, badValue = modifiers)
        state.ungrabButton(this, grabWindow, button, modifiers)
    }

    private fun changeActivePointerGrab(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 30, badValue = 0)
        val cursor = byteOrder.u32(body, 0)
        if (cursor != 0 && !state.hasCursor(cursor)) return writeError(error = 6, opcode = 30, badValue = cursor)
        val eventMask = byteOrder.u16(body, 8)
        if ((eventMask and XEventMasks.ValidPointerEventMask.inv()) != 0) {
            return writeError(error = 2, opcode = 30, badValue = eventMask)
        }
        state.changeActivePointerGrab(
            owner = this,
            eventMask = eventMask,
            cursor = cursor.takeIf { it != 0 },
            time = byteOrder.u32(body, 4),
        )
    }

    private fun grabKeyboard(ownerEvents: Int, body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 31, badValue = 0)
        if (ownerEvents !in 0..1) return writeError(error = 2, opcode = 31, badValue = ownerEvents)
        val grabWindow = byteOrder.u32(body, 0)
        if (state.window(grabWindow) == null) return writeError(error = 3, opcode = 31, badValue = grabWindow)
        val time = byteOrder.u32(body, 4)
        val pointerMode = body[8].toInt() and 0xff
        if (pointerMode !in 0..1) return writeError(error = 2, opcode = 31, badValue = pointerMode)
        val keyboardMode = body[9].toInt() and 0xff
        if (keyboardMode !in 0..1) return writeError(error = 2, opcode = 31, badValue = keyboardMode)

        val status = state.grabKeyboard(
            XInputGrab(
                owner = this,
                kind = "keyboard",
                windowId = grabWindow,
                ownerEvents = ownerEvents != 0,
                eventMask = 0,
                pointerMode = pointerMode,
                keyboardMode = keyboardMode,
                confineTo = null,
                cursor = null,
                time = time,
            ),
        )
        write(reply(extra = status, payloadUnits = 0))
    }

    private fun ungrabKeyboard(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 32, badValue = 0)
        state.ungrabKeyboard(this, byteOrder.u32(body, 0))
    }

    private fun grabKey(ownerEvents: Int, body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 33, badValue = 0)
        if (ownerEvents !in 0..1) return writeError(error = 2, opcode = 33, badValue = ownerEvents)
        val grabWindow = byteOrder.u32(body, 0)
        if (state.window(grabWindow) == null) return writeError(error = 3, opcode = 33, badValue = grabWindow)
        val modifiers = byteOrder.u16(body, 4)
        if (!validGrabModifiers(modifiers)) return writeError(error = 2, opcode = 33, badValue = modifiers)
        val key = body[6].toInt() and 0xff
        if (!validGrabKey(key)) return writeError(error = 2, opcode = 33, badValue = key)
        val pointerMode = body[7].toInt() and 0xff
        if (pointerMode !in 0..1) return writeError(error = 2, opcode = 33, badValue = pointerMode)
        val keyboardMode = body[8].toInt() and 0xff
        if (keyboardMode !in 0..1) return writeError(error = 2, opcode = 33, badValue = keyboardMode)

        val grabbed = state.grabKey(
            XPassiveKeyGrab(
                owner = this,
                windowId = grabWindow,
                ownerEvents = ownerEvents != 0,
                modifiers = modifiers,
                key = key,
                pointerMode = pointerMode,
                keyboardMode = keyboardMode,
            ),
        )
        if (!grabbed) writeError(error = 10, opcode = 33, badValue = 0)
    }

    private fun ungrabKey(key: Int, body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 34, badValue = 0)
        val grabWindow = byteOrder.u32(body, 0)
        if (state.window(grabWindow) == null) return writeError(error = 3, opcode = 34, badValue = grabWindow)
        val modifiers = byteOrder.u16(body, 4)
        if (!validGrabModifiers(modifiers)) return writeError(error = 2, opcode = 34, badValue = modifiers)
        if (!validGrabKey(key)) return writeError(error = 2, opcode = 34, badValue = key)
        state.ungrabKey(this, grabWindow, key, modifiers)
    }

    private fun validGrabKey(key: Int): Boolean =
        key == AnyKey || key in XKeyboard.MinKeycode..XKeyboard.MaxKeycode

    private fun validGrabModifiers(modifiers: Int): Boolean =
        modifiers == AnyModifier || (modifiers and KeyModifierMask.inv()) == 0

    private fun allowEvents(mode: Int, body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 35, badValue = 0)
        if (mode !in 0..7) return writeError(error = 2, opcode = 35, badValue = mode)
        state.allowEvents(
            owner = this,
            mode = mode,
            time = byteOrder.u32(body, 0),
        )
    }

    private fun grabServer(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 36, badValue = 0)
        state.grabServer(this)
    }

    private fun ungrabServer(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 37, badValue = 0)
        state.ungrabServer(this)
    }

    private fun getMotionEvents(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 39, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        if (state.window(windowId) == null) return writeError(error = 3, opcode = 39, badValue = windowId)
        val events = state.motionEvents(
            windowId = windowId,
            start = byteOrder.u32(body, 4),
            stop = byteOrder.u32(body, 8),
        )
        val reply = reply(extra = 0, payloadUnits = events.size * 2)
        byteOrder.put32(reply, 8, events.size)
        events.forEachIndexed { index, event ->
            val offset = 32 + index * 8
            byteOrder.put32(reply, offset, event.time)
            byteOrder.put16(reply, offset + 4, event.x)
            byteOrder.put16(reply, offset + 6, event.y)
        }
        write(reply)
    }

    private fun warpPointer(body: ByteArray) {
        if (body.size != 20) return writeError(error = 16, opcode = 41, badValue = 0)
        val sourceWindowId = byteOrder.u32(body, 0)
        if (sourceWindowId != 0 && state.window(sourceWindowId) == null) {
            return writeError(error = 3, opcode = 41, badValue = sourceWindowId)
        }
        val destinationWindowId = byteOrder.u32(body, 4)
        if (destinationWindowId != 0 && state.window(destinationWindowId) == null) {
            return writeError(error = 3, opcode = 41, badValue = destinationWindowId)
        }
        state.warpPointer(
            sourceWindowId = sourceWindowId,
            destinationWindowId = destinationWindowId,
            sourceX = byteOrder.i16(body, 8),
            sourceY = byteOrder.i16(body, 10),
            sourceWidth = byteOrder.u16(body, 12),
            sourceHeight = byteOrder.u16(body, 14),
            destinationX = byteOrder.i16(body, 16),
            destinationY = byteOrder.i16(body, 18),
        )
    }

    private fun translateCoordinates(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 40, badValue = 0)
        val sourceWindowId = byteOrder.u32(body, 0)
        if (state.window(sourceWindowId) == null) return writeError(error = 3, opcode = 40, badValue = sourceWindowId)
        val destinationWindowId = byteOrder.u32(body, 4)
        if (state.window(destinationWindowId) == null) return writeError(error = 3, opcode = 40, badValue = destinationWindowId)
        val translated = state.translateCoordinates(
            sourceWindowId = sourceWindowId,
            destinationWindowId = destinationWindowId,
            sourceX = byteOrder.i16(body, 8),
            sourceY = byteOrder.i16(body, 10),
        ) ?: return writeError(error = 3, opcode = 40, badValue = sourceWindowId)
        val reply = reply(extra = 1, payloadUnits = 0)
        byteOrder.put32(reply, 8, translated.childWindowId)
        byteOrder.put16(reply, 12, translated.destinationX)
        byteOrder.put16(reply, 14, translated.destinationY)
        write(reply)
    }

    private fun getInputFocus(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 43, badValue = 0)
        val (focusWindowId, revertTo) = state.inputFocus()
        val reply = reply(extra = 0, payloadUnits = 0)
        reply[1] = revertTo.toByte()
        byteOrder.put32(reply, 8, focusWindowId)
        write(reply)
    }

    private fun setInputFocus(revertTo: Int, body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 42, badValue = 0)
        if (revertTo !in 0..2) return writeError(error = 2, opcode = 42, badValue = revertTo)
        val focusWindowId = byteOrder.u32(body, 0)
        if (focusWindowId !in 0..1) {
            val window = state.window(focusWindowId) ?: return writeError(error = 3, opcode = 42, badValue = focusWindowId)
            if (!state.windowIsViewable(window.id)) return writeError(error = 8, opcode = 42, badValue = focusWindowId)
        }
        state.setInputFocus(focusWindowId, revertTo, byteOrder.u32(body, 4))
    }

    private fun queryKeymap(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 44, badValue = 0)
        write(reply(extra = 0, payloadUnits = 2))
    }

    private fun openFont(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = 45, badValue = 0)
        val nameLength = byteOrder.u16(body, 4)
        if (body.size != 8 + paddedLength(nameLength)) return writeError(error = 16, opcode = 45, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 45, badValue = id)
        state.putFont(id)
        own(id)
    }

    private fun closeResource(opcode: Int, body: ByteArray, error: Int, exists: (Int) -> Boolean) {
        if (body.size != 4) return writeError(error = 16, opcode = opcode, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (!exists(id)) return writeError(error = error, opcode = opcode, badValue = id)
        state.removeResource(id)
        ownedResources.remove(id)
    }

    private fun queryFont(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 47, badValue = 0)
        val fontable = byteOrder.u32(body, 0)
        if (!state.hasFontable(fontable)) return writeError(error = 7, opcode = 47, badValue = fontable)
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
        if (body.size < 4) return writeError(error = 16, opcode = 48, badValue = 0)
        if (oddLength !in 0..1) return writeError(error = 2, opcode = 48, badValue = oddLength)
        val padBytes = if (oddLength != 0) 2 else 0
        val stringBytes = body.size - 4 - padBytes
        if (stringBytes < 0) return writeError(error = 16, opcode = 48, badValue = 0)
        val fontable = byteOrder.u32(body, 0)
        if (!state.hasFontable(fontable)) return writeError(error = 7, opcode = 48, badValue = fontable)
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

    private fun listFonts(body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = 49, badValue = 0)
        val patternLength = byteOrder.u16(body, 2)
        if (body.size != 4 + paddedLength(patternLength)) return writeError(error = 16, opcode = 49, badValue = 0)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, 0)
        write(reply)
    }

    private fun listFontsWithInfo(body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = 50, badValue = 0)
        val patternLength = byteOrder.u16(body, 2)
        if (body.size != 4 + paddedLength(patternLength)) return writeError(error = 16, opcode = 50, badValue = 0)
        write(reply(extra = 0, payloadUnits = 7))
    }

    private fun getFontPath(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 52, badValue = 0)
        val path = state.fontPath().map { it.toByteArray(StandardCharsets.ISO_8859_1) }
        val payloadBytes = path.sumOf { 1 + it.size }
        val reply = reply(extra = 0, payloadUnits = paddedLength(payloadBytes) / 4)
        byteOrder.put16(reply, 8, path.size)
        var offset = 32
        for (entry in path) {
            reply[offset++] = entry.size.toByte()
            entry.copyInto(reply, offset)
            offset += entry.size
        }
        write(reply)
    }

    private fun setFontPath(body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = 51, badValue = 0)
        val count = byteOrder.u16(body, 0)
        var offset = 4
        val path = ArrayList<String>(count)
        repeat(count) {
            if (offset >= body.size) return writeError(error = 16, opcode = 51, badValue = 0)
            val length = body[offset].toInt() and 0xff
            offset += 1
            if (offset + length > body.size) return writeError(error = 16, opcode = 51, badValue = 0)
            path += String(body, offset, length, StandardCharsets.ISO_8859_1)
            offset += length
        }
        if (body.size != 4 + paddedLength(offset - 4)) return writeError(error = 16, opcode = 51, badValue = 0)
        state.setFontPath(path)
    }

    private fun createPixmap(depth: Int, body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 53, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val drawableId = byteOrder.u32(body, 4)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 53, badValue = id)
        val drawable = coreDrawable(opcode = 53, drawableId = drawableId) ?: return
        val width = byteOrder.u16(body, 8)
        val height = byteOrder.u16(body, 10)
        if (width == 0) return writeError(error = 2, opcode = 53, badValue = width)
        if (height == 0) return writeError(error = 2, opcode = 53, badValue = height)
        if (depth !in SupportedPixmapDepths) return writeError(error = 2, opcode = 53, badValue = depth)
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
        if (body.size < 12) return writeError(error = 16, opcode = 55, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val drawableId = byteOrder.u32(body, 4)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 55, badValue = id)
        val drawable = coreDrawable(opcode = 55, drawableId = drawableId) ?: return
        val mask = byteOrder.u32(body, 8)
        if (!validateGcValueLength(mask, body, 12, opcode = 55)) return
        if (!validateGcValues(mask, body, 12, opcode = 55)) return
        state.putGc(XGraphicsContext(id = id, drawableRootId = drawable.rootId, drawableDepth = drawable.depth))
        own(id)
        applyGcValues(id, mask, body, 12, opcode = 55)
    }

    private fun changeGc(body: ByteArray) {
        if (body.size < 8) return writeError(error = 16, opcode = 56, badValue = 0)
        val id = byteOrder.u32(body, 0)
        if (!state.hasGc(id)) return writeError(error = 13, opcode = 56, badValue = id)
        val mask = byteOrder.u32(body, 4)
        if (!validateGcValueLength(mask, body, 8, opcode = 56)) return
        applyGcValues(id, mask, body, 8, opcode = 56)
    }

    private fun copyGc(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 57, badValue = 0)
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
        if (body.size < 8) return writeError(error = 16, opcode = 58, badValue = 0)
        val gcId = byteOrder.u32(body, 0)
        val dashOffset = byteOrder.u16(body, 4)
        val dashCount = byteOrder.u16(body, 6)
        if (body.size != 8 + paddedLength(dashCount)) return writeError(error = 16, opcode = 58, badValue = 0)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 58, badValue = gcId)
        if (dashCount <= 0) return writeError(error = 2, opcode = 58, badValue = dashCount)
        val dashes = (0 until dashCount).map { body[8 + it].toInt() and 0xff }
        val invalidDash = dashes.firstOrNull { it == 0 }
        if (invalidDash != null) return writeError(error = 2, opcode = 58, badValue = invalidDash)
        state.updateGc(id = gcId, dashOffset = dashOffset, dashes = dashes)
    }

    private fun setClipRectangles(ordering: Int, body: ByteArray) {
        if (body.size < 8 || (body.size - 8) % 8 != 0) return writeError(error = 16, opcode = 59, badValue = 0)
        if (ordering !in 0..3) return writeError(error = 2, opcode = 59, badValue = ordering)
        val gcId = byteOrder.u32(body, 0)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 59, badValue = gcId)
        state.updateGcClip(
            id = gcId,
            clipXOrigin = byteOrder.i16(body, 4),
            clipYOrigin = byteOrder.i16(body, 6),
            clipRectangles = rectangles(body, 8),
        )
    }

    private fun clearArea(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 61, badValue = 0)
        val windowId = byteOrder.u32(body, 0)
        val window = state.window(windowId) ?: return writeError(error = 3, opcode = 61, badValue = windowId)
        if (window.windowClass == XWindowClass.InputOnly) return writeError(error = 8, opcode = 61, badValue = windowId)
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
        if (body.size != 24) return writeError(error = 16, opcode = 62, badValue = 0)
        val gcId = byteOrder.u32(body, 8)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 62, badValue = gcId)
        val gc = state.gc(gcId)
        val sourceDrawable = byteOrder.u32(body, 0)
        val destinationDrawable = byteOrder.u32(body, 4)
        val sourceX = byteOrder.i16(body, 12)
        val sourceY = byteOrder.i16(body, 14)
        val destinationX = byteOrder.i16(body, 16)
        val destinationY = byteOrder.i16(body, 18)
        val width = byteOrder.u16(body, 20)
        val height = byteOrder.u16(body, 22)
        coreDrawable(opcode = 62, drawableId = sourceDrawable) ?: return
        coreDrawable(opcode = 62, drawableId = destinationDrawable) ?: return
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
        if (body.size != 28) return writeError(error = 16, opcode = 63, badValue = 0)
        val gcId = byteOrder.u32(body, 8)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 63, badValue = gcId)
        val gc = state.gc(gcId)
        val sourceDrawable = byteOrder.u32(body, 0)
        val destinationDrawable = byteOrder.u32(body, 4)
        val sourceX = byteOrder.i16(body, 12)
        val sourceY = byteOrder.i16(body, 14)
        val destinationX = byteOrder.i16(body, 16)
        val destinationY = byteOrder.i16(body, 18)
        val width = byteOrder.u16(body, 20)
        val height = byteOrder.u16(body, 22)
        val bitPlane = byteOrder.u32(body, 24)
        coreDrawable(opcode = 63, drawableId = sourceDrawable) ?: return
        coreDrawable(opcode = 63, drawableId = destinationDrawable) ?: return
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
        if (body.size < 8) return writeError(error = 16, opcode = 64, badValue = 0)
        if (coordMode !in 0..1) return writeError(error = 2, opcode = 64, badValue = coordMode)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 64, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        coreDrawable(opcode = 64, drawableId = drawableId) ?: return
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
        if (body.size < 8) return writeError(error = 16, opcode = 65, badValue = 0)
        if (coordMode !in 0..1) return writeError(error = 2, opcode = 65, badValue = coordMode)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 65, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        coreDrawable(opcode = 65, drawableId = drawableId) ?: return
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
        if (body.size < 8 || (body.size - 8) % 8 != 0) return writeError(error = 16, opcode = 66, badValue = 0)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 66, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        coreDrawable(opcode = 66, drawableId = drawableId) ?: return
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
        val opcode = if (kind == XDrawingKind.FillRectangle) 70 else 67
        if (body.size < 8 || (body.size - 8) % 8 != 0) return writeError(error = 16, opcode = opcode, badValue = 0)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = opcode, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        coreDrawable(opcode = opcode, drawableId = drawableId) ?: return
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
        val opcode = if (filled) 71 else 68
        if (body.size < 8 || (body.size - 8) % 12 != 0) return writeError(error = 16, opcode = opcode, badValue = 0)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = opcode, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        coreDrawable(opcode = opcode, drawableId = drawableId) ?: return
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
        if (body.size < 12) return writeError(error = 16, opcode = 69, badValue = 0)
        val shape = body[8].toInt() and 0xff
        if (shape !in 0..2) return writeError(error = 2, opcode = 69, badValue = shape)
        val coordMode = body[9].toInt() and 0xff
        if (coordMode !in 0..1) return writeError(error = 2, opcode = 69, badValue = coordMode)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 69, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        coreDrawable(opcode = 69, drawableId = drawableId) ?: return
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
        if (body.size < 20) return writeError(error = 16, opcode = 72, badValue = 0)
        if (format !in 0..2) return writeError(error = 2, opcode = 72, badValue = format)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = 72, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        val drawable = coreDrawable(opcode = 72, drawableId = drawableId) ?: return
        if (gc.drawableRootId != drawable.rootId || gc.drawableDepth != drawable.depth) {
            return writeError(error = 8, opcode = 72, badValue = drawableId)
        }
        val width = byteOrder.u16(body, 8)
        val height = byteOrder.u16(body, 10)
        val x = byteOrder.i16(body, 12)
        val y = byteOrder.i16(body, 14)
        val leftPad = body[16].toInt() and 0xff
        val depth = body[17].toInt() and 0xff
        val dataByteLength = putImageDataByteLength(format, width, height, depth, leftPad, drawable.depth)
            ?: return writeError(error = 8, opcode = 72, badValue = drawableId)
        if (body.size - 20 != dataByteLength) return writeError(error = 16, opcode = 72, badValue = 0)
        val image = decodePutImage(
            format = format,
            width = width,
            height = height,
            depth = depth,
            leftPad = leftPad,
            drawableDepth = drawable.depth,
            data = body.copyOfRange(20, body.size),
            foreground = gc.foreground,
            background = gc.background,
        )
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

    private fun polyText(opcode: Int, body: ByteArray, is16Bit: Boolean) {
        if (body.size < 12) return writeError(error = 16, opcode = opcode, badValue = 0)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = opcode, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        val drawable = coreDrawable(opcode = opcode, drawableId = drawableId) ?: return
        if (gc.drawableRootId != drawable.rootId || gc.drawableDepth != drawable.depth) {
            return writeError(error = 8, opcode = opcode, badValue = drawableId)
        }
        val decoded = decodePolyText(body, is16Bit) ?: return writeError(error = 16, opcode = opcode, badValue = 0)
        val missingFont = decoded.fontIds.firstOrNull { !state.hasFont(it) }
        if (missingFont != null) return writeError(error = 7, opcode = opcode, badValue = missingFont)
        decoded.fontIds.lastOrNull()?.let { state.updateGc(id = gcId, fontId = it) }
        val runs = decoded.runs
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
        val byteLength = length * if (is16Bit) 2 else 1
        val opcode = if (is16Bit) 77 else 76
        if (body.size != paddedLength(12 + byteLength)) return writeError(error = 16, opcode = opcode, badValue = 0)
        val gcId = byteOrder.u32(body, 4)
        if (!state.hasGc(gcId)) return writeError(error = 13, opcode = opcode, badValue = gcId)
        val gc = state.gc(gcId)
        val drawableId = byteOrder.u32(body, 0)
        val drawable = coreDrawable(opcode = opcode, drawableId = drawableId) ?: return
        if (gc.drawableRootId != drawable.rootId || gc.drawableDepth != drawable.depth) {
            return writeError(error = 8, opcode = opcode, badValue = drawableId)
        }
        val x = byteOrder.i16(body, 8)
        val y = byteOrder.i16(body, 10)
        val textBytes = body.copyOfRange(12, 12 + byteLength)
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
        if (body.size != 16) return writeError(error = 16, opcode = 73, badValue = 0)
        if (format !in 1..2) return writeError(error = 2, opcode = 73, badValue = format)
        val drawableId = byteOrder.u32(body, 0)
        val drawable = coreDrawable(opcode = 73, drawableId = drawableId) ?: return
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
        val planes = (effectiveDepth - 1 downTo 0).filter { bit -> (planeMask and drawableMask and (1 shl bit)) != 0 }
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

    private fun createColormap(alloc: Int, body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 78, badValue = 0)
        if (alloc !in 0..1) return writeError(error = 2, opcode = 78, badValue = alloc)
        val id = byteOrder.u32(body, 0)
        val windowId = byteOrder.u32(body, 4)
        val visual = byteOrder.u32(body, 8)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 78, badValue = id)
        if (state.window(windowId) == null) return writeError(error = 3, opcode = 78, badValue = windowId)
        if (visual != X11Ids.RootVisual) return writeError(error = 8, opcode = 78, badValue = visual)
        if (alloc == 1) return writeError(error = 8, opcode = 78, badValue = alloc)
        state.putColormap(id)
        own(id)
    }

    private fun copyColormapAndFree(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 80, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val source = byteOrder.u32(body, 4)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 80, badValue = id)
        if (!state.hasColormap(source)) return writeError(error = 12, opcode = 80, badValue = source)
        state.putColormap(id)
        own(id)
    }

    private fun installColormap(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 81, badValue = 0)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 81, badValue = colormap)
        state.installColormap(colormap)
    }

    private fun uninstallColormap(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 82, badValue = 0)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 82, badValue = colormap)
        state.uninstallColormap(colormap)
    }

    private fun listInstalledColormaps(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 83, badValue = 0)
        val window = byteOrder.u32(body, 0)
        if (state.window(window) == null) return writeError(error = 3, opcode = 83, badValue = window)
        val colormaps = state.installedColormaps()
        val reply = reply(extra = 0, payloadUnits = colormaps.size)
        byteOrder.put16(reply, 8, colormaps.size)
        colormaps.forEachIndexed { index, colormap ->
            byteOrder.put32(reply, 32 + index * 4, colormap)
        }
        write(reply)
    }

    private fun allocColor(body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 84, badValue = 0)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 84, badValue = colormap)
        val red = byteOrder.u16(body, 4)
        val green = byteOrder.u16(body, 6)
        val blue = byteOrder.u16(body, 8)
        val color = XNamedColor.fromExact(red, green, blue)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, color.visualRed)
        byteOrder.put16(reply, 10, color.visualGreen)
        byteOrder.put16(reply, 12, color.visualBlue)
        byteOrder.put32(reply, 16, color.pixel)
        write(reply)
    }

    private fun allocNamedColor(body: ByteArray) {
        val color = namedColorRequest(opcode = 85, body = body) ?: return
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put32(reply, 8, color.pixel)
        putColorTriples(reply, 12, color)
        write(reply)
    }

    private fun allocColorCells(contiguous: Int, body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 86, badValue = 0)
        if (contiguous !in 0..1) return writeError(error = 2, opcode = 86, badValue = contiguous)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 86, badValue = colormap)
        val colors = byteOrder.u16(body, 4)
        if (colors == 0) return writeError(error = 2, opcode = 86, badValue = colors)
        writeError(error = 11, opcode = 86, badValue = 0)
    }

    private fun allocColorPlanes(contiguous: Int, body: ByteArray) {
        if (body.size != 12) return writeError(error = 16, opcode = 87, badValue = 0)
        if (contiguous !in 0..1) return writeError(error = 2, opcode = 87, badValue = contiguous)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 87, badValue = colormap)
        val colors = byteOrder.u16(body, 4)
        if (colors == 0) return writeError(error = 2, opcode = 87, badValue = colors)
        writeError(error = 11, opcode = 87, badValue = 0)
    }

    private fun freeColors(body: ByteArray) {
        if (body.size < 8 || (body.size - 8) % 4 != 0) return writeError(error = 16, opcode = 88, badValue = 0)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 88, badValue = colormap)
        var offset = 8
        while (offset < body.size) {
            val pixel = byteOrder.u32(body, offset)
            if (!isValidTrueColorPixel(pixel)) return writeError(error = 2, opcode = 88, badValue = pixel)
            offset += 4
        }
    }

    private fun storeColors(body: ByteArray) {
        if (body.size < 4 || (body.size - 4) % 12 != 0) return writeError(error = 16, opcode = 89, badValue = 0)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 89, badValue = colormap)
        var offset = 4
        while (offset < body.size) {
            val pixel = byteOrder.u32(body, offset)
            if (!isValidTrueColorPixel(pixel)) return writeError(error = 2, opcode = 89, badValue = pixel)
            val flags = body[offset + 10].toInt() and 0xff
            if (flags and XColorFlagMask != flags) return writeError(error = 2, opcode = 89, badValue = flags)
            offset += 12
        }
        if (body.size > 4) writeError(error = 10, opcode = 89, badValue = 0)
    }

    private fun storeNamedColor(flags: Int, body: ByteArray) {
        if (body.size < 12) return writeError(error = 16, opcode = 90, badValue = 0)
        val colormap = byteOrder.u32(body, 0)
        val pixel = byteOrder.u32(body, 4)
        val nameLength = byteOrder.u16(body, 8)
        if (body.size != 12 + paddedLength(nameLength)) return writeError(error = 16, opcode = 90, badValue = 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 90, badValue = colormap)
        if (!isValidTrueColorPixel(pixel)) return writeError(error = 2, opcode = 90, badValue = pixel)
        if (flags and XColorFlagMask != flags) return writeError(error = 2, opcode = 90, badValue = flags)
        val name = body.copyOfRange(12, 12 + nameLength).decodeToString()
        if (namedColor(name) == null) return writeError(error = 15, opcode = 90, badValue = 0)
        writeError(error = 10, opcode = 90, badValue = 0)
    }

    private fun isValidTrueColorPixel(pixel: Int): Boolean = pixel ushr 24 == 0

    private fun lookupColor(body: ByteArray) {
        val color = namedColorRequest(opcode = 92, body = body) ?: return
        val reply = reply(extra = 0, payloadUnits = 0)
        putColorTriples(reply, 8, color)
        write(reply)
    }

    private fun namedColorRequest(opcode: Int, body: ByteArray): XNamedColor? {
        if (body.size < 8) {
            writeError(error = 16, opcode = opcode, badValue = 0)
            return null
        }
        val colormap = byteOrder.u32(body, 0)
        val nameLength = byteOrder.u16(body, 4)
        if (body.size != 8 + paddedLength(nameLength)) {
            writeError(error = 16, opcode = opcode, badValue = 0)
            return null
        }
        if (!state.hasColormap(colormap)) {
            writeError(error = 12, opcode = opcode, badValue = colormap)
            return null
        }
        val name = body.copyOfRange(8, 8 + nameLength).decodeToString()
        return namedColor(name) ?: run {
            writeError(error = 15, opcode = opcode, badValue = 0)
            null
        }
    }

    private fun putColorTriples(reply: ByteArray, offset: Int, color: XNamedColor) {
        byteOrder.put16(reply, offset, color.exactRed)
        byteOrder.put16(reply, offset + 2, color.exactGreen)
        byteOrder.put16(reply, offset + 4, color.exactBlue)
        byteOrder.put16(reply, offset + 6, color.visualRed)
        byteOrder.put16(reply, offset + 8, color.visualGreen)
        byteOrder.put16(reply, offset + 10, color.visualBlue)
    }

    private fun namedColor(name: String): XNamedColor? {
        val normalized = name.trim().lowercase().replace(" ", "")
        XNamedColors[normalized]?.let { return XNamedColor.fromPixel(it) }
        parseGrayPercentage(normalized)?.let { return it }
        parseHexColor(normalized)?.let { return it }
        parseRgbColor(normalized)?.let { return it }
        return parseXcmsNumericColor(normalized)
    }

    private fun parseGrayPercentage(name: String): XNamedColor? {
        val digits = when {
            name.startsWith("gray") -> name.removePrefix("gray")
            name.startsWith("grey") -> name.removePrefix("grey")
            else -> return null
        }
        val percent = digits.toIntOrNull() ?: return null
        if (percent !in 0..100) return null
        val component = percent * 255 / 100
        val pixel = (component shl 16) or (component shl 8) or component
        return XNamedColor.fromPixel(pixel)
    }

    private fun parseHexColor(name: String): XNamedColor? {
        if (!name.startsWith("#")) return null
        val hex = name.drop(1)
        if (hex.length !in setOf(3, 6, 9, 12) || hex.any { it.digitToIntOrNull(16) == null }) return null
        val digitsPerComponent = hex.length / 3
        val red = parseHexColorComponent(hex.substring(0, digitsPerComponent))
        val green = parseHexColorComponent(hex.substring(digitsPerComponent, digitsPerComponent * 2))
        val blue = parseHexColorComponent(hex.substring(digitsPerComponent * 2))
        return XNamedColor.fromExact(red, green, blue)
    }

    private fun parseHexColorComponent(hex: String): Int {
        val value = hex.toInt(16)
        return when (hex.length) {
            1 -> value shl 12
            2 -> value shl 8
            3 -> value shl 4
            4 -> value
            else -> error("unsupported color component length ${hex.length}")
        }
    }

    private fun parseRgbColor(name: String): XNamedColor? {
        if (!name.startsWith("rgb:")) return null
        val components = name.removePrefix("rgb:").split("/")
        if (components.size != 3) return null
        val red = parseRgbColorComponent(components[0]) ?: return null
        val green = parseRgbColorComponent(components[1]) ?: return null
        val blue = parseRgbColorComponent(components[2]) ?: return null
        return XNamedColor.fromExact(red, green, blue)
    }

    private fun parseRgbColorComponent(hex: String): Int? {
        if (hex.isEmpty() || hex.length > 4 || hex.any { it.digitToIntOrNull(16) == null }) return null
        val value = hex.toInt(16)
        return when (hex.length) {
            1 -> value * 0x1111
            2 -> value * 0x0101
            3 -> (value shl 4) or (value ushr 8)
            4 -> value
            else -> null
        }
    }

    private fun parseXcmsNumericColor(name: String): XNamedColor? {
        val separator = name.indexOf(':')
        if (separator <= 0) return null
        val components = name.substring(separator + 1).split("/")
        if (components.size != 3) return null
        val values = components.map { it.toDoubleOrNull() ?: return null }
        if (values.any { !it.isFinite() }) return null
        return when (name.substring(0, separator)) {
            "rgbi" -> rgbIntensity(values[0], values[1], values[2])
            "ciexyz" -> xyzColor(values[0], values[1], values[2])
            "ciexyy" -> xyYColor(values[0], values[1], values[2])
            "cieuvy" -> uvYColor(values[0], values[1], values[2])
            "cielab" -> labColor(values[0], values[1], values[2])
            "cieluv" -> luvColor(values[0], values[1], values[2])
            "tekhvc" -> tekHvcColor(values[0], values[1], values[2])
            else -> null
        }
    }

    private fun rgbIntensity(red: Double, green: Double, blue: Double): XNamedColor? {
        if (red !in 0.0..1.0 || green !in 0.0..1.0 || blue !in 0.0..1.0) return null
        return XNamedColor.fromExact(componentFromUnit(red), componentFromUnit(green), componentFromUnit(blue))
    }

    private fun xyzColor(x: Double, y: Double, z: Double): XNamedColor? {
        if (x < 0.0 || y < 0.0 || z < 0.0) return null
        val linearRed = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val linearGreen = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val linearBlue = 0.0557 * x - 0.2040 * y + 1.0570 * z
        return rgbIntensity(srgbTransfer(linearRed), srgbTransfer(linearGreen), srgbTransfer(linearBlue))
    }

    private fun xyYColor(x: Double, y: Double, luminance: Double): XNamedColor? {
        if (x < 0.0 || y <= 0.0 || luminance < 0.0) return null
        val xyzX = x * luminance / y
        val xyzZ = (1.0 - x - y) * luminance / y
        return xyzColor(xyzX, luminance, xyzZ)
    }

    private fun uvYColor(u: Double, v: Double, luminance: Double): XNamedColor? {
        if (v <= 0.0 || luminance < 0.0) return null
        val x = 9.0 * u * luminance / (4.0 * v)
        val z = luminance * (12.0 - 3.0 * u - 20.0 * v) / (4.0 * v)
        return xyzColor(x, luminance, z)
    }

    private fun labColor(lightness: Double, a: Double, b: Double): XNamedColor? {
        if (lightness !in 0.0..100.0) return null
        val fy = (lightness + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0
        return xyzColor(D65_X * labInverse(fx), labInverse(fy), D65_Z * labInverse(fz))
    }

    private fun luvColor(lightness: Double, uStar: Double, vStar: Double): XNamedColor? {
        if (lightness !in 0.0..100.0) return null
        if (lightness == 0.0) return XNamedColor.fromExact(0, 0, 0)
        val uPrime = uStar / (13.0 * lightness) + D65_U_PRIME
        val vPrime = vStar / (13.0 * lightness) + D65_V_PRIME
        val luminance = labInverse((lightness + 16.0) / 116.0)
        return uvYColor(uPrime, vPrime, luminance)
    }

    private fun tekHvcColor(hue: Double, value: Double, chroma: Double): XNamedColor? {
        if (value !in 0.0..100.0 || chroma < 0.0) return null
        val radians = hue * PI / 180.0
        return luvColor(value, chroma * cos(radians), chroma * sin(radians))
    }

    private fun componentFromUnit(value: Double): Int =
        (value.coerceIn(0.0, 1.0) * 65535.0 + 0.5).toInt()

    private fun srgbTransfer(value: Double): Double {
        val clamped = value.coerceIn(0.0, 1.0)
        return if (clamped <= 0.0031308) {
            12.92 * clamped
        } else {
            1.055 * clamped.pow(1.0 / 2.4) - 0.055
        }
    }

    private fun labInverse(value: Double): Double =
        if (value > 6.0 / 29.0) value * value * value else 3.0 * (6.0 / 29.0).pow(2.0) * (value - 4.0 / 29.0)

    private fun queryColors(body: ByteArray) {
        if (body.size < 4 || (body.size - 4) % 4 != 0) return writeError(error = 16, opcode = 91, badValue = 0)
        val colormap = byteOrder.u32(body, 0)
        if (!state.hasColormap(colormap)) return writeError(error = 12, opcode = 91, badValue = colormap)
        val count = (body.size - 4) / 4
        var sourceOffset = 4
        repeat(count) {
            val pixel = byteOrder.u32(body, sourceOffset)
            if (!isValidTrueColorPixel(pixel)) return writeError(error = 2, opcode = 91, badValue = pixel)
            sourceOffset += 4
        }
        val reply = reply(extra = 0, payloadUnits = count * 2)
        byteOrder.put16(reply, 8, count)
        sourceOffset = 4
        var targetOffset = 32
        repeat(count) {
            val pixel = byteOrder.u32(body, sourceOffset)
            val color = XNamedColor.fromPixel(pixel)
            sourceOffset += 4
            byteOrder.put16(reply, targetOffset, color.visualRed)
            byteOrder.put16(reply, targetOffset + 2, color.visualGreen)
            byteOrder.put16(reply, targetOffset + 4, color.visualBlue)
            targetOffset += 8
        }
        write(reply)
    }

    private fun createCursor(body: ByteArray) {
        if (body.size != 28) return writeError(error = 16, opcode = 93, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val source = byteOrder.u32(body, 4)
        val mask = byteOrder.u32(body, 8)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 93, badValue = id)
        val sourcePixmap = state.pixmap(source) ?: return writeError(error = 4, opcode = 93, badValue = source)
        val maskPixmap = mask.takeIf { it != 0 }?.let {
            state.pixmap(it) ?: return writeError(error = 4, opcode = 93, badValue = it)
        }
        if (sourcePixmap.depth != 1 || maskPixmap?.depth?.let { it != 1 } == true) {
            return writeError(error = 8, opcode = 93, badValue = 0)
        }
        if (maskPixmap != null && (maskPixmap.width != sourcePixmap.width || maskPixmap.height != sourcePixmap.height)) {
            return writeError(error = 8, opcode = 93, badValue = 0)
        }
        val hotspotX = byteOrder.u16(body, 24)
        val hotspotY = byteOrder.u16(body, 26)
        if (hotspotX >= sourcePixmap.width || hotspotY >= sourcePixmap.height) {
            return writeError(error = 8, opcode = 93, badValue = 0)
        }
        state.putCursor(id)
        own(id)
    }

    private fun createGlyphCursor(body: ByteArray) {
        if (body.size != 28) return writeError(error = 16, opcode = 94, badValue = 0)
        val id = byteOrder.u32(body, 0)
        val sourceFont = byteOrder.u32(body, 4)
        val maskFont = byteOrder.u32(body, 8)
        if (state.hasResource(id)) return writeError(error = 14, opcode = 94, badValue = id)
        if (!state.hasFont(sourceFont)) return writeError(error = 7, opcode = 94, badValue = sourceFont)
        if (maskFont != 0 && !state.hasFont(maskFont)) return writeError(error = 7, opcode = 94, badValue = maskFont)
        state.putCursor(id)
        own(id)
    }

    private fun recolorCursor(body: ByteArray) {
        if (body.size != 16) return writeError(error = 16, opcode = 96, badValue = 0)
        val cursor = byteOrder.u32(body, 0)
        if (!state.hasCursor(cursor)) return writeError(error = 6, opcode = 96, badValue = cursor)
    }

    private fun queryBestSize(sizeClass: Int, body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 97, badValue = 0)
        if (sizeClass !in QueryBestSizeCursor..QueryBestSizeStipple) {
            return writeError(error = 2, opcode = 97, badValue = sizeClass)
        }
        val drawableId = byteOrder.u32(body, 0)
        coreDrawable(opcode = 97, drawableId = drawableId) ?: return
        val requestedWidth = byteOrder.u16(body, 4)
        val requestedHeight = byteOrder.u16(body, 6)
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, requestedWidth.coerceAtLeast(1))
        byteOrder.put16(reply, 10, requestedHeight.coerceAtLeast(1))
        write(reply)
    }

    private fun queryExtension(body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = 98, badValue = 0)
        val nameLength = byteOrder.u16(body, 0)
        if (body.size != 4 + paddedLength(nameLength)) return writeError(error = 16, opcode = 98, badValue = 0)
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
            5 -> "DestroySubwindows"
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
            39 -> "GetMotionEvents"
            40 -> "TranslateCoordinates"
            41 -> "WarpPointer"
            42 -> "SetInputFocus"
            43 -> "GetInputFocus"
            44 -> "QueryKeymap"
            45 -> "OpenFont"
            46 -> "CloseFont"
            47 -> "QueryFont"
            48 -> "QueryTextExtents"
            49 -> "ListFonts"
            50 -> "ListFontsWithInfo"
            51 -> "SetFontPath"
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
            80 -> "CopyColormapAndFree"
            81 -> "InstallColormap"
            82 -> "UninstallColormap"
            83 -> "ListInstalledColormaps"
            84 -> "AllocColor"
            85 -> "AllocNamedColor"
            86 -> "AllocColorCells"
            87 -> "AllocColorPlanes"
            88 -> "FreeColors"
            89 -> "StoreColors"
            90 -> "StoreNamedColor"
            91 -> "QueryColors"
            92 -> "LookupColor"
            93 -> "CreateCursor"
            94 -> "CreateGlyphCursor"
            95 -> "FreeCursor"
            96 -> "RecolorCursor"
            97 -> "QueryBestSize"
            98 -> "QueryExtension"
            99 -> "ListExtensions"
            100 -> "ChangeKeyboardMapping"
            101 -> "GetKeyboardMapping"
            102 -> "ChangeKeyboardControl"
            103 -> "GetKeyboardControl"
            104 -> "Bell"
            105 -> "ChangePointerControl"
            106 -> "GetPointerControl"
            107 -> "SetScreenSaver"
            108 -> "GetScreenSaver"
            109 -> "ChangeHosts"
            110 -> "ListHosts"
            111 -> "SetAccessControl"
            112 -> "SetCloseDownMode"
            113 -> "KillClient"
            114 -> "RotateProperties"
            115 -> "ForceScreenSaver"
            116 -> "SetPointerMapping"
            117 -> "GetPointerMapping"
            118 -> "SetModifierMapping"
            119 -> "GetModifierMapping"
            127 -> "NoOperation"
            else -> "Opcode$opcode/$minorOpcode"
        }

    private fun listExtensions(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 99, badValue = 0)
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
        if (body.size != 4) return writeError(error = 16, opcode = 101, badValue = 0)
        val firstKeycode = body[0].toInt() and 0xff
        val count = body[1].toInt() and 0xff
        if (firstKeycode !in XKeyboard.MinKeycode..XKeyboard.MaxKeycode) {
            return writeError(error = 2, opcode = 101, badValue = firstKeycode)
        }
        if (count > 0 && firstKeycode + count - 1 > XKeyboard.MaxKeycode) {
            return writeError(error = 2, opcode = 101, badValue = firstKeycode)
        }
        val mapping = state.keyboardMapping(firstKeycode, count)
        val reply = reply(extra = mapping.keysymsPerKeycode, payloadUnits = count * mapping.keysymsPerKeycode)
        var offset = 32
        for (keycode in firstKeycode until firstKeycode + count) {
            for (keysym in mapping.keysymsFor(keycode)) {
                byteOrder.put32(reply, offset, keysym)
                offset += 4
            }
        }
        write(reply)
    }

    private fun getKeyboardControl(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 103, badValue = 0)
        val keyboardControl = state.keyboardControl()
        val reply = reply(extra = 1, payloadUnits = 5)
        reply[1] = if (keyboardControl.globalAutoRepeat) 1 else 0
        byteOrder.put32(reply, 8, keyboardControl.ledMask)
        reply[12] = keyboardControl.keyClickPercent.toByte()
        reply[13] = keyboardControl.bellPercent.toByte()
        byteOrder.put16(reply, 14, keyboardControl.bellPitch)
        byteOrder.put16(reply, 16, keyboardControl.bellDuration)
        keyboardControl.autoRepeats.copyInto(reply, 20)
        write(reply)
    }

    private fun changeKeyboardControl(body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = 102, badValue = 0)
        val valueMask = byteOrder.u32(body, 0)
        if ((valueMask and XKeyboard.ControlMask.inv()) != 0) {
            return writeError(error = 2, opcode = 102, badValue = valueMask)
        }
        val valueCount = valueMask.countOneBits()
        if (body.size != 4 + valueCount * 4) return writeError(error = 16, opcode = 102, badValue = 0)
        var valueIndex = 0
        fun nextValueOffset(width: Int): Int {
            val offset = 4 + valueIndex++ * 4
            return when (byteOrder) {
                ByteOrder.LsbFirst -> offset
                ByteOrder.MsbFirst -> offset + 4 - width
            }
        }
        fun nextU8(): Int = body[nextValueOffset(1)].toInt() and 0xff
        fun nextI8(): Int = nextU8().let { if ((it and 0x80) == 0) it else it - 0x100 }
        fun nextI16(): Int = byteOrder.i16(body, nextValueOffset(2))
        var keyClickPercent: Int? = null
        var bellPercent: Int? = null
        var bellPitch: Int? = null
        var bellDuration: Int? = null
        var led: Int? = null
        var ledMode: Int? = null
        var key: Int? = null
        var autoRepeatMode: Int? = null

        if ((valueMask and XKeyboard.ControlKeyClickPercent) != 0) {
            keyClickPercent = nextI8()
            if (keyClickPercent !in -1..100) return writeError(error = 2, opcode = 102, badValue = keyClickPercent)
        }
        if ((valueMask and XKeyboard.ControlBellPercent) != 0) {
            bellPercent = nextI8()
            if (bellPercent !in -1..100) return writeError(error = 2, opcode = 102, badValue = bellPercent)
        }
        if ((valueMask and XKeyboard.ControlBellPitch) != 0) {
            bellPitch = nextI16()
            if (bellPitch < -1 || bellPitch > Short.MAX_VALUE) return writeError(error = 2, opcode = 102, badValue = bellPitch)
        }
        if ((valueMask and XKeyboard.ControlBellDuration) != 0) {
            bellDuration = nextI16()
            if (bellDuration < -1 || bellDuration > Short.MAX_VALUE) return writeError(error = 2, opcode = 102, badValue = bellDuration)
        }
        if ((valueMask and XKeyboard.ControlLed) != 0) {
            led = nextU8()
            if (led !in 1..32) return writeError(error = 2, opcode = 102, badValue = led)
        }
        if ((valueMask and XKeyboard.ControlLedMode) != 0) {
            ledMode = nextU8()
            if (ledMode !in XKeyboardLedMode.Off..XKeyboardLedMode.On) return writeError(error = 2, opcode = 102, badValue = ledMode)
        }
        if ((valueMask and XKeyboard.ControlKey) != 0) {
            key = nextU8()
            if (key !in XKeyboard.MinKeycode..XKeyboard.MaxKeycode) return writeError(error = 2, opcode = 102, badValue = key)
        }
        if ((valueMask and XKeyboard.ControlAutoRepeatMode) != 0) {
            autoRepeatMode = nextU8()
            if (autoRepeatMode !in XKeyboardAutoRepeatMode.Off..XKeyboardAutoRepeatMode.Default) {
                return writeError(error = 2, opcode = 102, badValue = autoRepeatMode)
            }
        }
        if (led != null && ledMode == null) return writeError(error = 8, opcode = 102, badValue = led)
        if (key != null && autoRepeatMode == null) return writeError(error = 8, opcode = 102, badValue = key)
        state.updateKeyboardControl(
            XKeyboardControlUpdate(
                keyClickPercent = keyClickPercent,
                bellPercent = bellPercent,
                bellPitch = bellPitch,
                bellDuration = bellDuration,
                led = led,
                ledMode = ledMode,
                key = key,
                autoRepeatMode = autoRepeatMode,
            ),
        )
    }

    private fun getPointerControl(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 106, badValue = 0)
        val pointerControl = state.pointerControl()
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, pointerControl.accelerationNumerator)
        byteOrder.put16(reply, 10, pointerControl.accelerationDenominator)
        byteOrder.put16(reply, 12, pointerControl.threshold)
        write(reply)
    }

    private fun bell(percentByte: Int, body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 104, badValue = 0)
        val percent = if (percentByte >= 128) percentByte - 256 else percentByte
        if (percent !in -100..100) return writeError(error = 2, opcode = 104, badValue = percent)
    }

    private fun changePointerControl(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 105, badValue = 0)
        val numerator = byteOrder.i16(body, 0)
        val denominator = byteOrder.i16(body, 2)
        val threshold = byteOrder.i16(body, 4)
        val doAcceleration = body[6].toInt() and 0xff
        val doThreshold = body[7].toInt() and 0xff
        if (doAcceleration !in 0..1) return writeError(error = 2, opcode = 105, badValue = doAcceleration)
        if (doThreshold !in 0..1) return writeError(error = 2, opcode = 105, badValue = doThreshold)
        if (doAcceleration == 1) {
            if (numerator < -1) return writeError(error = 2, opcode = 105, badValue = numerator)
            if (denominator < -1 || denominator == 0) return writeError(error = 2, opcode = 105, badValue = denominator)
        }
        if (doThreshold == 1 && threshold < -1) return writeError(error = 2, opcode = 105, badValue = threshold)
        state.setPointerControl(
            accelerationNumerator = if (doAcceleration == 1) {
                if (numerator == -1) XPointerControlSettings.DefaultAccelerationNumerator else numerator
            } else {
                null
            },
            accelerationDenominator = if (doAcceleration == 1) {
                if (denominator == -1) XPointerControlSettings.DefaultAccelerationDenominator else denominator
            } else {
                null
            },
            threshold = if (doThreshold == 1) {
                if (threshold == -1) XPointerControlSettings.DefaultThreshold else threshold
            } else {
                null
            },
        )
    }

    private fun getScreenSaver(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 108, badValue = 0)
        val screenSaver = state.screenSaver()
        val reply = reply(extra = 0, payloadUnits = 0)
        byteOrder.put16(reply, 8, screenSaver.timeout)
        byteOrder.put16(reply, 10, screenSaver.interval)
        reply[12] = screenSaver.preferBlanking.toByte()
        reply[13] = screenSaver.allowExposures.toByte()
        write(reply)
    }

    private fun setScreenSaver(body: ByteArray) {
        if (body.size != 8) return writeError(error = 16, opcode = 107, badValue = 0)
        val timeout = byteOrder.i16(body, 0)
        val interval = byteOrder.i16(body, 2)
        val preferBlanking = body[4].toInt() and 0xff
        val allowExposures = body[5].toInt() and 0xff
        if (timeout < -1) return writeError(error = 2, opcode = 107, badValue = timeout)
        if (interval < -1) return writeError(error = 2, opcode = 107, badValue = interval)
        if (preferBlanking !in 0..2) return writeError(error = 2, opcode = 107, badValue = preferBlanking)
        if (allowExposures !in 0..2) return writeError(error = 2, opcode = 107, badValue = allowExposures)
        state.setScreenSaver(timeout, interval, preferBlanking, allowExposures)
    }

    private fun changeHosts(mode: Int, body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = 109, badValue = 0)
        if (mode !in 0..1) return writeError(error = 2, opcode = 109, badValue = mode)
        val family = body[0].toInt() and 0xff
        val addressLength = byteOrder.u16(body, 2)
        if (body.size != 4 + paddedLength(addressLength)) {
            return writeError(error = 16, opcode = 109, badValue = 0)
        }
        val address = body.copyOfRange(4, 4 + addressLength).map { it.toInt() and 0xff }
        val invalidValue = invalidHostValue(family, address)
        if (invalidValue != null) return writeError(error = 2, opcode = 109, badValue = invalidValue)

        val host = XAccessHost(family = family, address = address)
        if (mode == 0) {
            state.insertAccessHost(host)
        } else {
            state.deleteAccessHost(host)
        }
    }

    private fun listHosts(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 110, badValue = 0)
        val hosts = state.accessHosts()
        val payloadBytes = hosts.sumOf { 4 + paddedLength(it.address.size) }
        val reply = reply(
            extra = if (state.accessControlEnabled()) 1 else 0,
            payloadUnits = payloadBytes / 4,
        )
        byteOrder.put16(reply, 8, hosts.size)
        var offset = 32
        for (host in hosts) {
            reply[offset] = host.family.toByte()
            byteOrder.put16(reply, offset + 2, host.address.size)
            host.address.forEachIndexed { index, value -> reply[offset + 4 + index] = value.toByte() }
            offset += 4 + paddedLength(host.address.size)
        }
        write(reply)
    }

    private fun setAccessControl(mode: Int, body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 111, badValue = 0)
        if (mode !in 0..1) return writeError(error = 2, opcode = 111, badValue = mode)
        state.setAccessControlEnabled(mode == 1)
    }

    private fun forceScreenSaver(mode: Int, body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 115, badValue = 0)
        if (mode !in 0..1) return writeError(error = 2, opcode = 115, badValue = mode)
    }

    private fun setCloseDownMode(mode: Int, body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 112, badValue = 0)
        if (mode !in XCloseDownMode.Destroy..XCloseDownMode.RetainTemporary) {
            return writeError(error = 2, opcode = 112, badValue = mode)
        }
        closeDownMode = mode
    }

    private fun killClient(body: ByteArray) {
        if (body.size != 4) return writeError(error = 16, opcode = 113, badValue = 0)
        val resource = byteOrder.u32(body, 0)
        if (resource == AllTemporary) {
            sendDestroyNotify(state.destroyTemporaryRetainedClients())
            return
        }
        if (!state.hasResource(resource)) return writeError(error = 2, opcode = 113, badValue = resource)
        state.destroyRetainedClientByResource(resource)?.let { notifications ->
            sendDestroyNotify(notifications)
            return
        }
        val client = state.liveClientOwningResource(resource)
            ?: return writeError(error = 2, opcode = 113, badValue = resource)
        client.killClient()
    }

    private fun setPointerMapping(count: Int, body: ByteArray) {
        if (body.size != paddedLength(count)) return writeError(error = 16, opcode = 116, badValue = 0)
        val current = state.pointerMapping()
        if (count != current.size) return writeError(error = 2, opcode = 116, badValue = count)
        val mapping = body.take(count).map { it.toInt() and 0xff }
        val nonZero = mapping.filter { it != 0 }
        if (nonZero.size != nonZero.toSet().size) {
            val duplicate = nonZero.first { value -> nonZero.count { it == value } > 1 }
            return writeError(error = 2, opcode = 116, badValue = duplicate)
        }
        val success = state.setPointerMappingIfIdle(mapping)
        val reply = reply(extra = if (success) 0 else 1, payloadUnits = 0)
        write(reply)
        if (success) {
            val event = XMappingNotifyEvent(request = 2)
            for (sink in state.mappingNotifySinks()) {
                runCatching { sink.sendMappingNotifyEvent(event) }
            }
        }
    }

    private fun getPointerMapping(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 117, badValue = 0)
        val map = state.pointerMapping().map { it.toByte() }.toByteArray()
        val reply = reply(extra = map.size, payloadUnits = paddedLength(map.size) / 4)
        map.copyInto(reply, 32)
        write(reply)
    }

    private fun changeKeyboardMapping(keycodeCount: Int, body: ByteArray) {
        if (body.size < 4) return writeError(error = 16, opcode = 100, badValue = 0)
        val firstKeycode = body[0].toInt() and 0xff
        val keysymsPerKeycode = body[1].toInt() and 0xff
        if (keysymsPerKeycode == 0) return writeError(error = 2, opcode = 100, badValue = 0)
        val expectedSize = 4 + keycodeCount * keysymsPerKeycode * 4
        if (body.size != expectedSize) return writeError(error = 16, opcode = 100, badValue = 0)
        if (firstKeycode !in XKeyboard.MinKeycode..XKeyboard.MaxKeycode) {
            return writeError(error = 2, opcode = 100, badValue = firstKeycode)
        }
        if (keycodeCount > 0 && firstKeycode + keycodeCount - 1 > XKeyboard.MaxKeycode) {
            return writeError(error = 2, opcode = 100, badValue = firstKeycode)
        }
        val keysymCount = keycodeCount * keysymsPerKeycode
        val keysyms = List(keysymCount) { index -> byteOrder.u32(body, 4 + index * 4) }
        state.setKeyboardMapping(firstKeycode, keysymsPerKeycode, keysyms)
        val event = XMappingNotifyEvent(request = 1, firstKeycode = firstKeycode, count = keycodeCount)
        for (sink in state.mappingNotifySinks()) {
            runCatching { sink.sendMappingNotifyEvent(event) }
        }
    }

    private fun setModifierMapping(keycodesPerModifier: Int, body: ByteArray) {
        val keycodeCount = 8 * keycodesPerModifier
        if (body.size != paddedLength(keycodeCount)) return writeError(error = 16, opcode = 118, badValue = 0)
        val keycodes = body.take(keycodeCount).map { it.toInt() and 0xff }
        val invalidKeycode = keycodes.firstOrNull { it != 0 && it !in XKeyboard.MinKeycode..XKeyboard.MaxKeycode }
        if (invalidKeycode != null) return writeError(error = 2, opcode = 118, badValue = invalidKeycode)
        state.setModifierMapping(keycodes)
        val reply = reply(extra = 0, payloadUnits = 0)
        write(reply)
        val event = XMappingNotifyEvent(request = 0)
        for (sink in state.mappingNotifySinks()) {
            runCatching { sink.sendMappingNotifyEvent(event) }
        }
    }

    private fun getModifierMapping(body: ByteArray) {
        if (body.isNotEmpty()) return writeError(error = 16, opcode = 119, badValue = 0)
        val map = state.modifierMapping().map { it.toByte() }.toByteArray()
        val reply = reply(extra = map.size / 8, payloadUnits = paddedLength(map.size) / 4)
        map.copyInto(reply, 32)
        write(reply)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun noOperation(body: ByteArray) = Unit

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

    private fun sendSelectionNotify(requestor: Int, selection: Int, target: Int, property: Int, time: Int) {
        val event = ByteArray(32)
        event[0] = 31
        byteOrder.put16(event, 2, sequence)
        byteOrder.put32(event, 4, time)
        byteOrder.put32(event, 8, requestor)
        byteOrder.put32(event, 12, selection)
        byteOrder.put32(event, 16, target)
        byteOrder.put32(event, 20, property)
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

    override fun sendMappingNotifyEvent(event: XMappingNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 34
        byteOrder.put16(bytes, 2, sequence)
        bytes[4] = event.request.toByte()
        bytes[5] = event.firstKeycode.toByte()
        bytes[6] = event.count.toByte()
        write(bytes)
    }

    override fun sendPropertyNotifyEvent(event: XPropertyNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 28
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.windowId)
        byteOrder.put32(bytes, 8, event.atom)
        byteOrder.put32(bytes, 12, event.time)
        bytes[16] = event.state.toByte()
        write(bytes)
    }

    override fun sendMapNotifyEvent(event: XMapNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 19
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.eventWindowId)
        byteOrder.put32(bytes, 8, event.windowId)
        bytes[12] = if (event.overrideRedirect) 1 else 0
        write(bytes)
    }

    override fun sendMapRequestEvent(event: XMapRequestEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 20
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.parentId)
        byteOrder.put32(bytes, 8, event.windowId)
        write(bytes)
    }

    override fun sendCreateNotifyEvent(event: XCreateNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 16
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.parentId)
        byteOrder.put32(bytes, 8, event.windowId)
        byteOrder.put16(bytes, 12, event.x)
        byteOrder.put16(bytes, 14, event.y)
        byteOrder.put16(bytes, 16, event.width)
        byteOrder.put16(bytes, 18, event.height)
        byteOrder.put16(bytes, 20, event.borderWidth)
        bytes[22] = if (event.overrideRedirect) 1 else 0
        write(bytes)
    }

    override fun sendDestroyNotifyEvent(event: XDestroyNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 17
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.eventWindowId)
        byteOrder.put32(bytes, 8, event.windowId)
        write(bytes)
    }

    override fun sendUnmapNotifyEvent(event: XUnmapNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 18
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.eventWindowId)
        byteOrder.put32(bytes, 8, event.windowId)
        bytes[12] = if (event.fromConfigure) 1 else 0
        write(bytes)
    }

    override fun sendReparentNotifyEvent(event: XReparentNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 21
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.eventWindowId)
        byteOrder.put32(bytes, 8, event.windowId)
        byteOrder.put32(bytes, 12, event.parentId)
        byteOrder.put16(bytes, 16, event.x)
        byteOrder.put16(bytes, 18, event.y)
        bytes[20] = if (event.overrideRedirect) 1 else 0
        write(bytes)
    }

    override fun sendCirculateNotifyEvent(event: XCirculateNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 26
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.eventWindowId)
        byteOrder.put32(bytes, 8, event.windowId)
        bytes[16] = event.place.toByte()
        write(bytes)
    }

    override fun sendCirculateRequestEvent(event: XCirculateRequestEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 27
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.parentId)
        byteOrder.put32(bytes, 8, event.windowId)
        bytes[16] = event.place.toByte()
        write(bytes)
    }

    override fun sendResizeRequestEvent(event: XResizeRequestEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 25
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.windowId)
        byteOrder.put16(bytes, 8, event.width)
        byteOrder.put16(bytes, 10, event.height)
        write(bytes)
    }

    override fun sendConfigureNotifyEvent(event: XConfigureNotifyEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 22
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.eventWindowId)
        byteOrder.put32(bytes, 8, event.windowId)
        byteOrder.put32(bytes, 12, event.aboveSiblingId)
        byteOrder.put16(bytes, 16, event.x)
        byteOrder.put16(bytes, 18, event.y)
        byteOrder.put16(bytes, 20, event.width)
        byteOrder.put16(bytes, 22, event.height)
        byteOrder.put16(bytes, 24, event.borderWidth)
        bytes[26] = if (event.overrideRedirect) 1 else 0
        write(bytes)
    }

    override fun sendConfigureRequestEvent(event: XConfigureRequestEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 23
        bytes[1] = event.stackMode.toByte()
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.parentId)
        byteOrder.put32(bytes, 8, event.windowId)
        byteOrder.put32(bytes, 12, event.siblingId)
        byteOrder.put16(bytes, 16, event.x)
        byteOrder.put16(bytes, 18, event.y)
        byteOrder.put16(bytes, 20, event.width)
        byteOrder.put16(bytes, 22, event.height)
        byteOrder.put16(bytes, 24, event.borderWidth)
        byteOrder.put16(bytes, 26, event.valueMask)
        write(bytes)
    }

    override fun sendSelectionClearEvent(event: XSelectionClearEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 29
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.time)
        byteOrder.put32(bytes, 8, event.ownerWindowId)
        byteOrder.put32(bytes, 12, event.selection)
        write(bytes)
    }

    override fun sendSelectionRequestEvent(event: XSelectionRequestEvent) {
        val bytes = ByteArray(32)
        bytes[0] = 30
        byteOrder.put16(bytes, 2, sequence)
        byteOrder.put32(bytes, 4, event.time)
        byteOrder.put32(bytes, 8, event.ownerWindowId)
        byteOrder.put32(bytes, 12, event.requestorWindowId)
        byteOrder.put32(bytes, 16, event.selection)
        byteOrder.put32(bytes, 20, event.target)
        byteOrder.put32(bytes, 24, event.property)
        write(bytes)
    }

    override fun sendSyntheticEvent(event: XSyntheticEvent) {
        val bytes = event.bytes.copyOf()
        if (event.sourceByteOrder != byteOrder) {
            swapSyntheticEvent(bytes, event.sourceByteOrder, byteOrder)
        }
        if ((bytes[0].toInt() and 0x7f) != 11) {
            byteOrder.put16(bytes, 2, sequence)
        }
        write(bytes)
    }

    private fun swapSyntheticEvent(bytes: ByteArray, source: ByteOrder, target: ByteOrder) {
        fun word16(offset: Int) = target.put16(bytes, offset, source.u16(bytes, offset))
        fun word32(offset: Int) = target.put32(bytes, offset, source.u32(bytes, offset))

        when (bytes[0].toInt() and 0x7f) {
            2, 3, 4, 5, 6, 7, 8 -> {
                word32(4)
                word32(8)
                word32(12)
                word32(16)
                word16(20)
                word16(22)
                word16(24)
                word16(26)
                word16(28)
            }
            9, 10, 15 -> word32(4)
            12 -> {
                word32(4)
                word16(8)
                word16(10)
                word16(12)
                word16(14)
                word16(16)
            }
            13 -> {
                word32(4)
                word16(8)
                word16(10)
                word16(12)
                word16(14)
                word16(16)
                word16(18)
            }
            14 -> {
                word32(4)
                word16(8)
            }
            16 -> {
                word32(4)
                word32(8)
                word16(12)
                word16(14)
                word16(16)
                word16(18)
                word16(20)
            }
            21 -> {
                word32(4)
                word32(8)
                word32(12)
                word16(16)
                word16(18)
            }
            22 -> {
                word32(4)
                word32(8)
                word32(12)
                word16(16)
                word16(18)
                word16(20)
                word16(22)
                word16(24)
            }
            17, 18, 19, 20, 26, 27 -> {
                word32(4)
                word32(8)
            }
            23 -> {
                word32(4)
                word32(8)
                word32(12)
                word16(16)
                word16(18)
                word16(20)
                word16(22)
                word16(24)
                word16(26)
            }
            24 -> {
                word32(4)
                word32(8)
                word16(12)
                word16(14)
            }
            25 -> {
                word32(4)
                word16(8)
                word16(10)
            }
            28 -> {
                word32(4)
                word32(8)
                word32(12)
            }
            29 -> {
                word32(4)
                word32(8)
                word32(12)
            }
            30 -> {
                word32(4)
                word32(8)
                word32(12)
                word32(16)
                word32(20)
                word32(24)
            }
            31 -> {
                word32(4)
                word32(8)
                word32(12)
                word32(16)
                word32(20)
            }
            32 -> {
                word32(4)
                word32(8)
            }
            33 -> {
                word32(4)
                word32(8)
                when (bytes[1].toInt() and 0xff) {
                    16 -> for (offset in 12 until 32 step 2) word16(offset)
                    32 -> for (offset in 12 until 32 step 4) word32(offset)
                }
            }
        }
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
        var overrideRedirect: Boolean? = null
        var eventMask: Int? = null
        var doNotPropagateMask: Int? = null
        for (bit in 0..14) {
            if ((mask and (1 shl bit)) == 0) continue
            if (offset + 4 > body.size) break
            val value = byteOrder.u32(body, offset)
            when (bit) {
                0 -> backgroundPixmapId = value
                1 -> backgroundPixel = value
                9 -> overrideRedirect = value != 0
                11 -> eventMask = value
                12 -> doNotPropagateMask = value
            }
            offset += 4
        }
        return WindowAttributeValues(backgroundPixmapId, backgroundPixel, overrideRedirect, eventMask, doNotPropagateMask)
    }

    private fun validateGcValueLength(mask: Int, body: ByteArray, valuesOffset: Int, opcode: Int): Boolean {
        if ((mask and GcValueMask.inv()) != 0) return true
        val valueCount = Integer.bitCount(mask)
        if (body.size != valuesOffset + valueCount * 4) {
            writeError(error = 16, opcode = opcode, badValue = 0)
            return false
        }
        return true
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
                14 -> if (value != 0 && !state.hasFont(value)) {
                    writeError(error = 7, opcode = opcode, badValue = value)
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

    private fun decodePolyText(body: ByteArray, is16Bit: Boolean): XPolyTextDecode? {
        val runs = mutableListOf<XTextRun>()
        val fontIds = mutableListOf<Int>()
        var offset = 12
        var x = byteOrder.i16(body, 8)
        val y = byteOrder.i16(body, 10)
        while (offset < body.size) {
            val remaining = body.size - offset
            if (remaining < 2) break
            val length = body[offset].toInt() and 0xff
            if (length == 255) {
                if (remaining < 5) {
                    if (remaining <= 3) break
                    return null
                }
                fontIds += ByteOrder.MsbFirst.u32(body, offset + 1)
                offset += 5
                continue
            }
            x += body[offset + 1].toInt().toByte().toInt()
            val byteLength = length * if (is16Bit) 2 else 1
            if (offset + 2 + byteLength > body.size) {
                if (remaining <= 3) break
                return null
            }
            val bytes = body.copyOfRange(offset + 2, offset + 2 + byteLength)
            val text = if (is16Bit) decodeText16(bytes) else decodeText8(bytes)
            if (text.isNotEmpty()) {
                runs += XTextRun(x, y, text)
                x += text.length * XFramebuffer.TextCellWidth
            }
            offset += 2 + byteLength
        }
        return XPolyTextDecode(runs, fontIds)
    }

    private fun decodePutImage(
        format: Int,
        width: Int,
        height: Int,
        depth: Int,
        leftPad: Int,
        drawableDepth: Int,
        data: ByteArray,
        foreground: Int,
        background: Int,
    ): XImagePixels? {
        if (width <= 0 || height <= 0) return null
        return when (format) {
            0 -> decodeBitmapImage(width, height, leftPad, drawableDepth, data, foreground, background)
            1 -> decodeXyPixmapImage(width, height, depth, leftPad, data)
            2 -> decodeZPixmapImage(width, height, depth, data)
            else -> null
        }
    }

    private fun decodeBitmapImage(
        width: Int,
        height: Int,
        leftPad: Int,
        drawableDepth: Int,
        data: ByteArray,
        foreground: Int,
        background: Int,
    ): XImagePixels {
        val stride = xyPlaneStrideBytes(width, leftPad)
        val pixels = IntArray(width * height)
        val foregroundPixel = imagePixelForDepth(foreground, drawableDepth)
        val backgroundPixel = imagePixelForDepth(background, drawableDepth)
        for (y in 0 until height) {
            val rowOffset = y * stride
            for (x in 0 until width) {
                pixels[y * width + x] = if (imagePlaneBit(data, rowOffset, x + leftPad)) foregroundPixel else backgroundPixel
            }
        }
        return XImagePixels(width, height, pixels)
    }

    private fun decodeXyPixmapImage(
        width: Int,
        height: Int,
        depth: Int,
        leftPad: Int,
        data: ByteArray,
    ): XImagePixels {
        val stride = xyPlaneStrideBytes(width, leftPad)
        val planeBytes = stride * height
        val pixels = IntArray(width * height)
        for (planeIndex in 0 until depth) {
            val bit = depth - 1 - planeIndex
            val planeOffset = planeIndex * planeBytes
            val mask = 1 shl bit
            for (y in 0 until height) {
                val rowOffset = planeOffset + y * stride
                for (x in 0 until width) {
                    if (imagePlaneBit(data, rowOffset, x + leftPad)) {
                        pixels[y * width + x] = pixels[y * width + x] or mask
                    }
                }
            }
        }
        for (index in pixels.indices) {
            pixels[index] = imagePixelForDepth(pixels[index], depth)
        }
        return XImagePixels(width, height, pixels)
    }

    private fun decodeZPixmapImage(
        width: Int,
        height: Int,
        depth: Int,
        data: ByteArray,
    ): XImagePixels? {
        if (depth !in setOf(8, 24, 32)) return null
        if (depth == 8) {
            val stride = paddedLength(width)
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
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * stride
            for (x in 0 until width) {
                val pixel = byteOrder.u32(data, rowOffset + x * bytesPerPixel)
                pixels[y * width + x] = imagePixelForDepth(pixel, depth)
            }
        }
        return XImagePixels(width, height, pixels)
    }

    private fun putImageDataByteLength(
        format: Int,
        width: Int,
        height: Int,
        depth: Int,
        leftPad: Int,
        drawableDepth: Int,
    ): Int? {
        if (width == 0 || height == 0) return 0
        val bytes = when (format) {
            0 -> {
                if (depth != 1 || leftPad >= 32) return null
                xyPlaneStrideBytes(width, leftPad).toLong() * height
            }
            1 -> {
                if (depth != drawableDepth || depth !in 1..32 || leftPad >= 32) return null
                xyPlaneStrideBytes(width, leftPad).toLong() * height * depth
            }
            2 -> {
                if (depth != drawableDepth || leftPad != 0) return null
                when (depth) {
                    8 -> paddedLength(width.toLong()) * height
                    24, 32 -> paddedLength(width.toLong() * 4L) * height
                    else -> return null
                }
            }
            else -> return null
        }
        return bytes.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun xyPlaneStrideBytes(width: Int, leftPad: Int): Int =
        paddedLength((leftPad + width + 7) / 8)

    private fun imagePlaneBit(data: ByteArray, rowOffset: Int, bitIndex: Int): Boolean =
        ((data[rowOffset + bitIndex / 8].toInt() ushr (bitIndex % 8)) and 1) != 0

    private fun imagePixelForDepth(pixel: Int, depth: Int): Int =
        when (depth) {
            8 -> (pixel and 0xff) shl 24
            24 -> XFramebuffer.opaque(pixel)
            32 -> XFramebuffer.argb(pixel)
            else -> pixel
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

    private fun glyphImageByteSizeLong(format: Int, width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0
        return when (format) {
            XRender.A8Format -> paddedLength(width.toLong()) * height.toLong()
            XRender.A1Format -> ((width.toLong() + 31L) / 32L) * 4L * height.toLong()
            XRender.Argb32Format, XRender.Rgb24Format -> width.toLong() * 4L * height.toLong()
            else -> 0
        }
    }

    private fun paddedLength(length: Int): Int = (length + 3) and -4

    private fun paddedLength(length: Long): Long = (length + 3L) and -4L

    private fun propertyDataToServerOrder(format: Int, source: ByteArray, offset: Int, length: Int): ByteArray {
        val data = source.copyOfRange(offset, offset + length)
        return if (format == 8 || byteOrder == ByteOrder.LsbFirst) data else data.withSwappedPropertyElements(format)
    }

    private fun propertyDataForClientOrder(format: Int, data: ByteArray): ByteArray =
        if (format == 8 || byteOrder == ByteOrder.LsbFirst) data else data.withSwappedPropertyElements(format)

    private fun ByteArray.withSwappedPropertyElements(format: Int): ByteArray {
        val swapped = copyOf()
        when (format) {
            16 -> {
                for (offset in swapped.indices step 2) {
                    val first = swapped[offset]
                    swapped[offset] = swapped[offset + 1]
                    swapped[offset + 1] = first
                }
            }
            32 -> {
                for (offset in swapped.indices step 4) {
                    val first = swapped[offset]
                    val second = swapped[offset + 1]
                    swapped[offset] = swapped[offset + 3]
                    swapped[offset + 1] = swapped[offset + 2]
                    swapped[offset + 2] = second
                    swapped[offset + 3] = first
                }
            }
        }
        return swapped
    }

    private fun sendPropertyNotify(windowId: Int, property: Int, propertyState: Int) {
        for (sink in state.propertyNotifySinks(windowId)) {
            runCatching { sink.sendPropertyNotifyEvent(XPropertyNotifyEvent(windowId = windowId, atom = property, state = propertyState)) }
        }
    }

    private fun sendMapNotify(notifications: List<XMapNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendMapNotifyEvent(notification.event) }
        }
    }

    private fun sendMapRequest(notifications: List<XMapRequestDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendMapRequestEvent(notification.event) }
        }
    }

    private fun sendCreateNotify(notifications: List<XCreateNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendCreateNotifyEvent(notification.event) }
        }
    }

    private fun sendDestroyNotify(notifications: List<XDestroyNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendDestroyNotifyEvent(notification.event) }
        }
    }

    private fun sendUnmapNotify(notifications: List<XUnmapNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendUnmapNotifyEvent(notification.event) }
        }
    }

    private fun sendReparentNotify(notifications: List<XReparentNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendReparentNotifyEvent(notification.event) }
        }
    }

    private fun sendCirculateNotify(notifications: List<XCirculateNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendCirculateNotifyEvent(notification.event) }
        }
    }

    private fun sendCirculateRequest(notifications: List<XCirculateRequestDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendCirculateRequestEvent(notification.event) }
        }
    }

    private fun sendResizeRequest(notifications: List<XResizeRequestDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendResizeRequestEvent(notification.event) }
        }
    }

    private fun sendConfigureNotify(notifications: List<XConfigureNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendConfigureNotifyEvent(notification.event) }
        }
    }

    private fun sendConfigureRequest(notifications: List<XConfigureRequestDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendConfigureRequestEvent(notification.event) }
        }
    }

    private fun invalidHostValue(family: Int, address: List<Int>): Int? =
        when (family) {
            XAccessHost.FamilyInternet -> if (address.size == 4) null else address.size
            XAccessHost.FamilyDECnet -> if (address.size == 2) null else address.size
            XAccessHost.FamilyChaos -> if (address.size == 2) null else address.size
            XAccessHost.FamilyServerInterpreted -> {
                val separator = address.indexOf(0)
                if (separator >= 0 && address.all { it in 0..0x7f }) null else family
            }
            XAccessHost.FamilyInternetV6 -> if (address.size == 16) null else address.size
            else -> family
        }

    private fun own(id: Int) {
        ownedResources += id
        state.markResourceOwner(id, this)
    }

    override fun isKilled(): Boolean = killed

    override fun killClient() {
        killed = true
        state.signalClientKilled(this)
        closeDownClient()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun closeDownClient() {
        val mode: Int
        val resources: Set<Int>
        synchronized(closeDownLock) {
            if (closeDownHandled) return
            closeDownHandled = true
            killed = true
            mode = closeDownMode
            resources = ownedResources.toSet()
        }
        state.releaseServerGrab(this)
        val destroyNotifyDispatches = when (mode) {
            XCloseDownMode.Destroy -> state.removeClientResources(this, resources)
            else -> {
                state.retainClientResources(this, resources, mode)
                emptyList()
            }
        }
        sendDestroyNotify(destroyNotifyDispatches)
        state.unregisterEventSink(this)
    }

    private companion object {
        const val AllTemporary = 0
        const val AnyKey = 0
        const val AnyModifier = 0x8000
        const val KeyModifierMask = 0x00ff
        const val GcValueMask = 0x007f_ffff
        const val WindowAttributeValueMask = 0x0000_7fff
        const val InputOnlyWindowAttributeValueMask = (1 shl 5) or (1 shl 9) or (1 shl 11) or (1 shl 12) or (1 shl 14)
        const val ConfigureWindowValueMask = 0x007f
        const val QueryBestSizeCursor = 0
        const val QueryBestSizeTile = 1
        const val QueryBestSizeStipple = 2
        const val XColorFlagMask = 0x07
        const val D65_X = 0.95047
        const val D65_Z = 1.08883
        const val D65_U_PRIME = 0.19783982482140777
        const val D65_V_PRIME = 0.4683363029324097
        val SupportedPixmapDepths = setOf(1, 4, 8, 24, 32)
        val RenderFilterNames = listOf("nearest", "bilinear", "fast", "good", "best")
        val RenderFilterAliases = listOf(0xffff, 0xffff, 0, 1, 1)
        val XNamedColors = mapOf(
            "aliceblue" to 0x00f0f8ff,
            "antiquewhite" to 0x00faebd7,
            "antiquewhite1" to 0x00ffefdb,
            "antiquewhite2" to 0x00eedfcc,
            "antiquewhite3" to 0x00cdc0b0,
            "antiquewhite4" to 0x008b8378,
            "aqua" to 0x0000ffff,
            "aquamarine" to 0x007fffd4,
            "aquamarine1" to 0x007fffd4,
            "aquamarine2" to 0x0076eec6,
            "aquamarine3" to 0x0066cdaa,
            "aquamarine4" to 0x00458b74,
            "azure" to 0x00f0ffff,
            "azure1" to 0x00f0ffff,
            "azure2" to 0x00e0eeee,
            "azure3" to 0x00c1cdcd,
            "azure4" to 0x00838b8b,
            "beige" to 0x00f5f5dc,
            "bisque" to 0x00ffe4c4,
            "bisque1" to 0x00ffe4c4,
            "bisque2" to 0x00eed5b7,
            "bisque3" to 0x00cdb79e,
            "bisque4" to 0x008b7d6b,
            "black" to 0x00000000,
            "blanchedalmond" to 0x00ffebcd,
            "blue" to 0x000000ff,
            "blue1" to 0x000000ff,
            "blue2" to 0x000000ee,
            "blue3" to 0x000000cd,
            "blue4" to 0x0000008b,
            "blueviolet" to 0x008a2be2,
            "brown" to 0x00a52a2a,
            "brown1" to 0x00ff4040,
            "brown2" to 0x00ee3b3b,
            "brown3" to 0x00cd3333,
            "brown4" to 0x008b2323,
            "burlywood" to 0x00deb887,
            "burlywood1" to 0x00ffd39b,
            "burlywood2" to 0x00eec591,
            "burlywood3" to 0x00cdaa7d,
            "burlywood4" to 0x008b7355,
            "cadetblue" to 0x005f9ea0,
            "cadetblue1" to 0x0098f5ff,
            "cadetblue2" to 0x008ee5ee,
            "cadetblue3" to 0x007ac5cd,
            "cadetblue4" to 0x0053868b,
            "chartreuse" to 0x007fff00,
            "chartreuse1" to 0x007fff00,
            "chartreuse2" to 0x0076ee00,
            "chartreuse3" to 0x0066cd00,
            "chartreuse4" to 0x00458b00,
            "chocolate" to 0x00d2691e,
            "chocolate1" to 0x00ff7f24,
            "chocolate2" to 0x00ee7621,
            "chocolate3" to 0x00cd661d,
            "chocolate4" to 0x008b4513,
            "coral" to 0x00ff7f50,
            "coral1" to 0x00ff7256,
            "coral2" to 0x00ee6a50,
            "coral3" to 0x00cd5b45,
            "coral4" to 0x008b3e2f,
            "cornflowerblue" to 0x006495ed,
            "cornsilk" to 0x00fff8dc,
            "cornsilk1" to 0x00fff8dc,
            "cornsilk2" to 0x00eee8cd,
            "cornsilk3" to 0x00cdc8b1,
            "cornsilk4" to 0x008b8878,
            "crimson" to 0x00dc143c,
            "cyan" to 0x0000ffff,
            "cyan1" to 0x0000ffff,
            "cyan2" to 0x0000eeee,
            "cyan3" to 0x0000cdcd,
            "cyan4" to 0x00008b8b,
            "darkblue" to 0x0000008b,
            "darkcyan" to 0x00008b8b,
            "darkgoldenrod" to 0x00b8860b,
            "darkgoldenrod1" to 0x00ffb90f,
            "darkgoldenrod2" to 0x00eead0e,
            "darkgoldenrod3" to 0x00cd950c,
            "darkgoldenrod4" to 0x008b6508,
            "darkgray" to 0x00a9a9a9,
            "darkgreen" to 0x00006400,
            "darkgrey" to 0x00a9a9a9,
            "darkkhaki" to 0x00bdb76b,
            "darkmagenta" to 0x008b008b,
            "darkolivegreen" to 0x00556b2f,
            "darkolivegreen1" to 0x00caff70,
            "darkolivegreen2" to 0x00bcee68,
            "darkolivegreen3" to 0x00a2cd5a,
            "darkolivegreen4" to 0x006e8b3d,
            "darkorange" to 0x00ff8c00,
            "darkorange1" to 0x00ff7f00,
            "darkorange2" to 0x00ee7600,
            "darkorange3" to 0x00cd6600,
            "darkorange4" to 0x008b4500,
            "darkorchid" to 0x009932cc,
            "darkorchid1" to 0x00bf3eff,
            "darkorchid2" to 0x00b23aee,
            "darkorchid3" to 0x009a32cd,
            "darkorchid4" to 0x0068228b,
            "darkred" to 0x008b0000,
            "darksalmon" to 0x00e9967a,
            "darkseagreen" to 0x008fbc8f,
            "darkseagreen1" to 0x00c1ffc1,
            "darkseagreen2" to 0x00b4eeb4,
            "darkseagreen3" to 0x009bcd9b,
            "darkseagreen4" to 0x00698b69,
            "darkslateblue" to 0x00483d8b,
            "darkslategray" to 0x002f4f4f,
            "darkslategray1" to 0x0097ffff,
            "darkslategray2" to 0x008deeee,
            "darkslategray3" to 0x0079cdcd,
            "darkslategray4" to 0x00528b8b,
            "darkslategrey" to 0x002f4f4f,
            "darkturquoise" to 0x0000ced1,
            "darkviolet" to 0x009400d3,
            "deeppink" to 0x00ff1493,
            "deeppink1" to 0x00ff1493,
            "deeppink2" to 0x00ee1289,
            "deeppink3" to 0x00cd1076,
            "deeppink4" to 0x008b0a50,
            "deepskyblue" to 0x0000bfff,
            "deepskyblue1" to 0x0000bfff,
            "deepskyblue2" to 0x0000b2ee,
            "deepskyblue3" to 0x00009acd,
            "deepskyblue4" to 0x0000688b,
            "dimgray" to 0x00696969,
            "dimgrey" to 0x00696969,
            "dodgerblue" to 0x001e90ff,
            "dodgerblue1" to 0x001e90ff,
            "dodgerblue2" to 0x001c86ee,
            "dodgerblue3" to 0x001874cd,
            "dodgerblue4" to 0x00104e8b,
            "firebrick" to 0x00b22222,
            "firebrick1" to 0x00ff3030,
            "firebrick2" to 0x00ee2c2c,
            "firebrick3" to 0x00cd2626,
            "firebrick4" to 0x008b1a1a,
            "floralwhite" to 0x00fffaf0,
            "forestgreen" to 0x00228b22,
            "fuchsia" to 0x00ff00ff,
            "gainsboro" to 0x00dcdcdc,
            "ghostwhite" to 0x00f8f8ff,
            "gold" to 0x00ffd700,
            "gold1" to 0x00ffd700,
            "gold2" to 0x00eec900,
            "gold3" to 0x00cdad00,
            "gold4" to 0x008b7500,
            "goldenrod" to 0x00daa520,
            "goldenrod1" to 0x00ffc125,
            "goldenrod2" to 0x00eeb422,
            "goldenrod3" to 0x00cd9b1d,
            "goldenrod4" to 0x008b6914,
            "gray" to 0x00bebebe,
            "gray0" to 0x00000000,
            "gray1" to 0x00030303,
            "gray10" to 0x001a1a1a,
            "gray100" to 0x00ffffff,
            "gray11" to 0x001c1c1c,
            "gray12" to 0x001f1f1f,
            "gray13" to 0x00212121,
            "gray14" to 0x00242424,
            "gray15" to 0x00262626,
            "gray16" to 0x00292929,
            "gray17" to 0x002b2b2b,
            "gray18" to 0x002e2e2e,
            "gray19" to 0x00303030,
            "gray2" to 0x00050505,
            "gray20" to 0x00333333,
            "gray21" to 0x00363636,
            "gray22" to 0x00383838,
            "gray23" to 0x003b3b3b,
            "gray24" to 0x003d3d3d,
            "gray25" to 0x00404040,
            "gray26" to 0x00424242,
            "gray27" to 0x00454545,
            "gray28" to 0x00474747,
            "gray29" to 0x004a4a4a,
            "gray3" to 0x00080808,
            "gray30" to 0x004d4d4d,
            "gray31" to 0x004f4f4f,
            "gray32" to 0x00525252,
            "gray33" to 0x00545454,
            "gray34" to 0x00575757,
            "gray35" to 0x00595959,
            "gray36" to 0x005c5c5c,
            "gray37" to 0x005e5e5e,
            "gray38" to 0x00616161,
            "gray39" to 0x00636363,
            "gray4" to 0x000a0a0a,
            "gray40" to 0x00666666,
            "gray41" to 0x00696969,
            "gray42" to 0x006b6b6b,
            "gray43" to 0x006e6e6e,
            "gray44" to 0x00707070,
            "gray45" to 0x00737373,
            "gray46" to 0x00757575,
            "gray47" to 0x00787878,
            "gray48" to 0x007a7a7a,
            "gray49" to 0x007d7d7d,
            "gray5" to 0x000d0d0d,
            "gray50" to 0x007f7f7f,
            "gray51" to 0x00828282,
            "gray52" to 0x00858585,
            "gray53" to 0x00878787,
            "gray54" to 0x008a8a8a,
            "gray55" to 0x008c8c8c,
            "gray56" to 0x008f8f8f,
            "gray57" to 0x00919191,
            "gray58" to 0x00949494,
            "gray59" to 0x00969696,
            "gray6" to 0x000f0f0f,
            "gray60" to 0x00999999,
            "gray61" to 0x009c9c9c,
            "gray62" to 0x009e9e9e,
            "gray63" to 0x00a1a1a1,
            "gray64" to 0x00a3a3a3,
            "gray65" to 0x00a6a6a6,
            "gray66" to 0x00a8a8a8,
            "gray67" to 0x00ababab,
            "gray68" to 0x00adadad,
            "gray69" to 0x00b0b0b0,
            "gray7" to 0x00121212,
            "gray70" to 0x00b3b3b3,
            "gray71" to 0x00b5b5b5,
            "gray72" to 0x00b8b8b8,
            "gray73" to 0x00bababa,
            "gray74" to 0x00bdbdbd,
            "gray75" to 0x00bfbfbf,
            "gray76" to 0x00c2c2c2,
            "gray77" to 0x00c4c4c4,
            "gray78" to 0x00c7c7c7,
            "gray79" to 0x00c9c9c9,
            "gray8" to 0x00141414,
            "gray80" to 0x00cccccc,
            "gray81" to 0x00cfcfcf,
            "gray82" to 0x00d1d1d1,
            "gray83" to 0x00d4d4d4,
            "gray84" to 0x00d6d6d6,
            "gray85" to 0x00d9d9d9,
            "gray86" to 0x00dbdbdb,
            "gray87" to 0x00dedede,
            "gray88" to 0x00e0e0e0,
            "gray89" to 0x00e3e3e3,
            "gray9" to 0x00171717,
            "gray90" to 0x00e5e5e5,
            "gray91" to 0x00e8e8e8,
            "gray92" to 0x00ebebeb,
            "gray93" to 0x00ededed,
            "gray94" to 0x00f0f0f0,
            "gray95" to 0x00f2f2f2,
            "gray96" to 0x00f5f5f5,
            "gray97" to 0x00f7f7f7,
            "gray98" to 0x00fafafa,
            "gray99" to 0x00fcfcfc,
            "green" to 0x0000ff00,
            "green1" to 0x0000ff00,
            "green2" to 0x0000ee00,
            "green3" to 0x0000cd00,
            "green4" to 0x00008b00,
            "greenyellow" to 0x00adff2f,
            "grey" to 0x00bebebe,
            "grey0" to 0x00000000,
            "grey1" to 0x00030303,
            "grey10" to 0x001a1a1a,
            "grey100" to 0x00ffffff,
            "grey11" to 0x001c1c1c,
            "grey12" to 0x001f1f1f,
            "grey13" to 0x00212121,
            "grey14" to 0x00242424,
            "grey15" to 0x00262626,
            "grey16" to 0x00292929,
            "grey17" to 0x002b2b2b,
            "grey18" to 0x002e2e2e,
            "grey19" to 0x00303030,
            "grey2" to 0x00050505,
            "grey20" to 0x00333333,
            "grey21" to 0x00363636,
            "grey22" to 0x00383838,
            "grey23" to 0x003b3b3b,
            "grey24" to 0x003d3d3d,
            "grey25" to 0x00404040,
            "grey26" to 0x00424242,
            "grey27" to 0x00454545,
            "grey28" to 0x00474747,
            "grey29" to 0x004a4a4a,
            "grey3" to 0x00080808,
            "grey30" to 0x004d4d4d,
            "grey31" to 0x004f4f4f,
            "grey32" to 0x00525252,
            "grey33" to 0x00545454,
            "grey34" to 0x00575757,
            "grey35" to 0x00595959,
            "grey36" to 0x005c5c5c,
            "grey37" to 0x005e5e5e,
            "grey38" to 0x00616161,
            "grey39" to 0x00636363,
            "grey4" to 0x000a0a0a,
            "grey40" to 0x00666666,
            "grey41" to 0x00696969,
            "grey42" to 0x006b6b6b,
            "grey43" to 0x006e6e6e,
            "grey44" to 0x00707070,
            "grey45" to 0x00737373,
            "grey46" to 0x00757575,
            "grey47" to 0x00787878,
            "grey48" to 0x007a7a7a,
            "grey49" to 0x007d7d7d,
            "grey5" to 0x000d0d0d,
            "grey50" to 0x007f7f7f,
            "grey51" to 0x00828282,
            "grey52" to 0x00858585,
            "grey53" to 0x00878787,
            "grey54" to 0x008a8a8a,
            "grey55" to 0x008c8c8c,
            "grey56" to 0x008f8f8f,
            "grey57" to 0x00919191,
            "grey58" to 0x00949494,
            "grey59" to 0x00969696,
            "grey6" to 0x000f0f0f,
            "grey60" to 0x00999999,
            "grey61" to 0x009c9c9c,
            "grey62" to 0x009e9e9e,
            "grey63" to 0x00a1a1a1,
            "grey64" to 0x00a3a3a3,
            "grey65" to 0x00a6a6a6,
            "grey66" to 0x00a8a8a8,
            "grey67" to 0x00ababab,
            "grey68" to 0x00adadad,
            "grey69" to 0x00b0b0b0,
            "grey7" to 0x00121212,
            "grey70" to 0x00b3b3b3,
            "grey71" to 0x00b5b5b5,
            "grey72" to 0x00b8b8b8,
            "grey73" to 0x00bababa,
            "grey74" to 0x00bdbdbd,
            "grey75" to 0x00bfbfbf,
            "grey76" to 0x00c2c2c2,
            "grey77" to 0x00c4c4c4,
            "grey78" to 0x00c7c7c7,
            "grey79" to 0x00c9c9c9,
            "grey8" to 0x00141414,
            "grey80" to 0x00cccccc,
            "grey81" to 0x00cfcfcf,
            "grey82" to 0x00d1d1d1,
            "grey83" to 0x00d4d4d4,
            "grey84" to 0x00d6d6d6,
            "grey85" to 0x00d9d9d9,
            "grey86" to 0x00dbdbdb,
            "grey87" to 0x00dedede,
            "grey88" to 0x00e0e0e0,
            "grey89" to 0x00e3e3e3,
            "grey9" to 0x00171717,
            "grey90" to 0x00e5e5e5,
            "grey91" to 0x00e8e8e8,
            "grey92" to 0x00ebebeb,
            "grey93" to 0x00ededed,
            "grey94" to 0x00f0f0f0,
            "grey95" to 0x00f2f2f2,
            "grey96" to 0x00f5f5f5,
            "grey97" to 0x00f7f7f7,
            "grey98" to 0x00fafafa,
            "grey99" to 0x00fcfcfc,
            "honeydew" to 0x00f0fff0,
            "honeydew1" to 0x00f0fff0,
            "honeydew2" to 0x00e0eee0,
            "honeydew3" to 0x00c1cdc1,
            "honeydew4" to 0x00838b83,
            "hotpink" to 0x00ff69b4,
            "hotpink1" to 0x00ff6eb4,
            "hotpink2" to 0x00ee6aa7,
            "hotpink3" to 0x00cd6090,
            "hotpink4" to 0x008b3a62,
            "indianred" to 0x00cd5c5c,
            "indianred1" to 0x00ff6a6a,
            "indianred2" to 0x00ee6363,
            "indianred3" to 0x00cd5555,
            "indianred4" to 0x008b3a3a,
            "indigo" to 0x004b0082,
            "ivory" to 0x00fffff0,
            "ivory1" to 0x00fffff0,
            "ivory2" to 0x00eeeee0,
            "ivory3" to 0x00cdcdc1,
            "ivory4" to 0x008b8b83,
            "khaki" to 0x00f0e68c,
            "khaki1" to 0x00fff68f,
            "khaki2" to 0x00eee685,
            "khaki3" to 0x00cdc673,
            "khaki4" to 0x008b864e,
            "lavender" to 0x00e6e6fa,
            "lavenderblush" to 0x00fff0f5,
            "lavenderblush1" to 0x00fff0f5,
            "lavenderblush2" to 0x00eee0e5,
            "lavenderblush3" to 0x00cdc1c5,
            "lavenderblush4" to 0x008b8386,
            "lawngreen" to 0x007cfc00,
            "lemonchiffon" to 0x00fffacd,
            "lemonchiffon1" to 0x00fffacd,
            "lemonchiffon2" to 0x00eee9bf,
            "lemonchiffon3" to 0x00cdc9a5,
            "lemonchiffon4" to 0x008b8970,
            "lightblue" to 0x00add8e6,
            "lightblue1" to 0x00bfefff,
            "lightblue2" to 0x00b2dfee,
            "lightblue3" to 0x009ac0cd,
            "lightblue4" to 0x0068838b,
            "lightcoral" to 0x00f08080,
            "lightcyan" to 0x00e0ffff,
            "lightcyan1" to 0x00e0ffff,
            "lightcyan2" to 0x00d1eeee,
            "lightcyan3" to 0x00b4cdcd,
            "lightcyan4" to 0x007a8b8b,
            "lightgoldenrod" to 0x00eedd82,
            "lightgoldenrod1" to 0x00ffec8b,
            "lightgoldenrod2" to 0x00eedc82,
            "lightgoldenrod3" to 0x00cdbe70,
            "lightgoldenrod4" to 0x008b814c,
            "lightgoldenrodyellow" to 0x00fafad2,
            "lightgray" to 0x00d3d3d3,
            "lightgreen" to 0x0090ee90,
            "lightgrey" to 0x00d3d3d3,
            "lightpink" to 0x00ffb6c1,
            "lightpink1" to 0x00ffaeb9,
            "lightpink2" to 0x00eea2ad,
            "lightpink3" to 0x00cd8c95,
            "lightpink4" to 0x008b5f65,
            "lightsalmon" to 0x00ffa07a,
            "lightsalmon1" to 0x00ffa07a,
            "lightsalmon2" to 0x00ee9572,
            "lightsalmon3" to 0x00cd8162,
            "lightsalmon4" to 0x008b5742,
            "lightseagreen" to 0x0020b2aa,
            "lightskyblue" to 0x0087cefa,
            "lightskyblue1" to 0x00b0e2ff,
            "lightskyblue2" to 0x00a4d3ee,
            "lightskyblue3" to 0x008db6cd,
            "lightskyblue4" to 0x00607b8b,
            "lightslateblue" to 0x008470ff,
            "lightslategray" to 0x00778899,
            "lightslategrey" to 0x00778899,
            "lightsteelblue" to 0x00b0c4de,
            "lightsteelblue1" to 0x00cae1ff,
            "lightsteelblue2" to 0x00bcd2ee,
            "lightsteelblue3" to 0x00a2b5cd,
            "lightsteelblue4" to 0x006e7b8b,
            "lightyellow" to 0x00ffffe0,
            "lightyellow1" to 0x00ffffe0,
            "lightyellow2" to 0x00eeeed1,
            "lightyellow3" to 0x00cdcdb4,
            "lightyellow4" to 0x008b8b7a,
            "lime" to 0x0000ff00,
            "limegreen" to 0x0032cd32,
            "linen" to 0x00faf0e6,
            "magenta" to 0x00ff00ff,
            "magenta1" to 0x00ff00ff,
            "magenta2" to 0x00ee00ee,
            "magenta3" to 0x00cd00cd,
            "magenta4" to 0x008b008b,
            "maroon" to 0x00b03060,
            "maroon1" to 0x00ff34b3,
            "maroon2" to 0x00ee30a7,
            "maroon3" to 0x00cd2990,
            "maroon4" to 0x008b1c62,
            "mediumaquamarine" to 0x0066cdaa,
            "mediumblue" to 0x000000cd,
            "mediumorchid" to 0x00ba55d3,
            "mediumorchid1" to 0x00e066ff,
            "mediumorchid2" to 0x00d15fee,
            "mediumorchid3" to 0x00b452cd,
            "mediumorchid4" to 0x007a378b,
            "mediumpurple" to 0x009370db,
            "mediumpurple1" to 0x00ab82ff,
            "mediumpurple2" to 0x009f79ee,
            "mediumpurple3" to 0x008968cd,
            "mediumpurple4" to 0x005d478b,
            "mediumseagreen" to 0x003cb371,
            "mediumslateblue" to 0x007b68ee,
            "mediumspringgreen" to 0x0000fa9a,
            "mediumturquoise" to 0x0048d1cc,
            "mediumvioletred" to 0x00c71585,
            "midnightblue" to 0x00191970,
            "mintcream" to 0x00f5fffa,
            "mistyrose" to 0x00ffe4e1,
            "mistyrose1" to 0x00ffe4e1,
            "mistyrose2" to 0x00eed5d2,
            "mistyrose3" to 0x00cdb7b5,
            "mistyrose4" to 0x008b7d7b,
            "moccasin" to 0x00ffe4b5,
            "navajowhite" to 0x00ffdead,
            "navajowhite1" to 0x00ffdead,
            "navajowhite2" to 0x00eecfa1,
            "navajowhite3" to 0x00cdb38b,
            "navajowhite4" to 0x008b795e,
            "navy" to 0x00000080,
            "navyblue" to 0x00000080,
            "oldlace" to 0x00fdf5e6,
            "olive" to 0x00808000,
            "olivedrab" to 0x006b8e23,
            "olivedrab1" to 0x00c0ff3e,
            "olivedrab2" to 0x00b3ee3a,
            "olivedrab3" to 0x009acd32,
            "olivedrab4" to 0x00698b22,
            "orange" to 0x00ffa500,
            "orange1" to 0x00ffa500,
            "orange2" to 0x00ee9a00,
            "orange3" to 0x00cd8500,
            "orange4" to 0x008b5a00,
            "orangered" to 0x00ff4500,
            "orangered1" to 0x00ff4500,
            "orangered2" to 0x00ee4000,
            "orangered3" to 0x00cd3700,
            "orangered4" to 0x008b2500,
            "orchid" to 0x00da70d6,
            "orchid1" to 0x00ff83fa,
            "orchid2" to 0x00ee7ae9,
            "orchid3" to 0x00cd69c9,
            "orchid4" to 0x008b4789,
            "palegoldenrod" to 0x00eee8aa,
            "palegreen" to 0x0098fb98,
            "palegreen1" to 0x009aff9a,
            "palegreen2" to 0x0090ee90,
            "palegreen3" to 0x007ccd7c,
            "palegreen4" to 0x00548b54,
            "paleturquoise" to 0x00afeeee,
            "paleturquoise1" to 0x00bbffff,
            "paleturquoise2" to 0x00aeeeee,
            "paleturquoise3" to 0x0096cdcd,
            "paleturquoise4" to 0x00668b8b,
            "palevioletred" to 0x00db7093,
            "palevioletred1" to 0x00ff82ab,
            "palevioletred2" to 0x00ee799f,
            "palevioletred3" to 0x00cd6889,
            "palevioletred4" to 0x008b475d,
            "papayawhip" to 0x00ffefd5,
            "peachpuff" to 0x00ffdab9,
            "peachpuff1" to 0x00ffdab9,
            "peachpuff2" to 0x00eecbad,
            "peachpuff3" to 0x00cdaf95,
            "peachpuff4" to 0x008b7765,
            "peru" to 0x00cd853f,
            "pink" to 0x00ffc0cb,
            "pink1" to 0x00ffb5c5,
            "pink2" to 0x00eea9b8,
            "pink3" to 0x00cd919e,
            "pink4" to 0x008b636c,
            "plum" to 0x00dda0dd,
            "plum1" to 0x00ffbbff,
            "plum2" to 0x00eeaeee,
            "plum3" to 0x00cd96cd,
            "plum4" to 0x008b668b,
            "powderblue" to 0x00b0e0e6,
            "purple" to 0x00a020f0,
            "purple1" to 0x009b30ff,
            "purple2" to 0x00912cee,
            "purple3" to 0x007d26cd,
            "purple4" to 0x00551a8b,
            "rebeccapurple" to 0x00663399,
            "red" to 0x00ff0000,
            "red1" to 0x00ff0000,
            "red2" to 0x00ee0000,
            "red3" to 0x00cd0000,
            "red4" to 0x008b0000,
            "rosybrown" to 0x00bc8f8f,
            "rosybrown1" to 0x00ffc1c1,
            "rosybrown2" to 0x00eeb4b4,
            "rosybrown3" to 0x00cd9b9b,
            "rosybrown4" to 0x008b6969,
            "royalblue" to 0x004169e1,
            "royalblue1" to 0x004876ff,
            "royalblue2" to 0x00436eee,
            "royalblue3" to 0x003a5fcd,
            "royalblue4" to 0x0027408b,
            "saddlebrown" to 0x008b4513,
            "salmon" to 0x00fa8072,
            "salmon1" to 0x00ff8c69,
            "salmon2" to 0x00ee8262,
            "salmon3" to 0x00cd7054,
            "salmon4" to 0x008b4c39,
            "sandybrown" to 0x00f4a460,
            "seagreen" to 0x002e8b57,
            "seagreen1" to 0x0054ff9f,
            "seagreen2" to 0x004eee94,
            "seagreen3" to 0x0043cd80,
            "seagreen4" to 0x002e8b57,
            "seashell" to 0x00fff5ee,
            "seashell1" to 0x00fff5ee,
            "seashell2" to 0x00eee5de,
            "seashell3" to 0x00cdc5bf,
            "seashell4" to 0x008b8682,
            "sienna" to 0x00a0522d,
            "sienna1" to 0x00ff8247,
            "sienna2" to 0x00ee7942,
            "sienna3" to 0x00cd6839,
            "sienna4" to 0x008b4726,
            "silver" to 0x00c0c0c0,
            "skyblue" to 0x0087ceeb,
            "skyblue1" to 0x0087ceff,
            "skyblue2" to 0x007ec0ee,
            "skyblue3" to 0x006ca6cd,
            "skyblue4" to 0x004a708b,
            "slateblue" to 0x006a5acd,
            "slateblue1" to 0x00836fff,
            "slateblue2" to 0x007a67ee,
            "slateblue3" to 0x006959cd,
            "slateblue4" to 0x00473c8b,
            "slategray" to 0x00708090,
            "slategray1" to 0x00c6e2ff,
            "slategray2" to 0x00b9d3ee,
            "slategray3" to 0x009fb6cd,
            "slategray4" to 0x006c7b8b,
            "slategrey" to 0x00708090,
            "snow" to 0x00fffafa,
            "snow1" to 0x00fffafa,
            "snow2" to 0x00eee9e9,
            "snow3" to 0x00cdc9c9,
            "snow4" to 0x008b8989,
            "springgreen" to 0x0000ff7f,
            "springgreen1" to 0x0000ff7f,
            "springgreen2" to 0x0000ee76,
            "springgreen3" to 0x0000cd66,
            "springgreen4" to 0x00008b45,
            "steelblue" to 0x004682b4,
            "steelblue1" to 0x0063b8ff,
            "steelblue2" to 0x005cacee,
            "steelblue3" to 0x004f94cd,
            "steelblue4" to 0x0036648b,
            "tan" to 0x00d2b48c,
            "tan1" to 0x00ffa54f,
            "tan2" to 0x00ee9a49,
            "tan3" to 0x00cd853f,
            "tan4" to 0x008b5a2b,
            "teal" to 0x00008080,
            "thistle" to 0x00d8bfd8,
            "thistle1" to 0x00ffe1ff,
            "thistle2" to 0x00eed2ee,
            "thistle3" to 0x00cdb5cd,
            "thistle4" to 0x008b7b8b,
            "tomato" to 0x00ff6347,
            "tomato1" to 0x00ff6347,
            "tomato2" to 0x00ee5c42,
            "tomato3" to 0x00cd4f39,
            "tomato4" to 0x008b3626,
            "turquoise" to 0x0040e0d0,
            "turquoise1" to 0x0000f5ff,
            "turquoise2" to 0x0000e5ee,
            "turquoise3" to 0x0000c5cd,
            "turquoise4" to 0x0000868b,
            "violet" to 0x00ee82ee,
            "violetred" to 0x00d02090,
            "violetred1" to 0x00ff3e96,
            "violetred2" to 0x00ee3a8c,
            "violetred3" to 0x00cd3278,
            "violetred4" to 0x008b2252,
            "webgray" to 0x00808080,
            "webgreen" to 0x00008000,
            "webgrey" to 0x00808080,
            "webmaroon" to 0x00800000,
            "webpurple" to 0x00800080,
            "wheat" to 0x00f5deb3,
            "wheat1" to 0x00ffe7ba,
            "wheat2" to 0x00eed8ae,
            "wheat3" to 0x00cdba96,
            "wheat4" to 0x008b7e66,
            "white" to 0x00ffffff,
            "whitesmoke" to 0x00f5f5f5,
            "x11gray" to 0x00bebebe,
            "x11green" to 0x0000ff00,
            "x11grey" to 0x00bebebe,
            "x11maroon" to 0x00b03060,
            "x11purple" to 0x00a020f0,
            "yellow" to 0x00ffff00,
            "yellow1" to 0x00ffff00,
            "yellow2" to 0x00eeee00,
            "yellow3" to 0x00cdcd00,
            "yellow4" to 0x008b8b00,
            "yellowgreen" to 0x009acd32,
        )
    }
}

private data class WindowAttributeValues(
    val backgroundPixmapId: Int? = null,
    val backgroundPixel: Int? = null,
    val overrideRedirect: Boolean? = null,
    val eventMask: Int? = null,
    val doNotPropagateMask: Int? = null,
)

internal object XCloseDownMode {
    const val Destroy = 0
    const val RetainPermanent = 1
    const val RetainTemporary = 2
}

private object XSaveSetMode {
    const val Insert = 0
    const val Delete = 1
}

private object XPropertyMode {
    const val Replace = 0
    const val Prepend = 1
    const val Append = 2
}

private object XPropertyFormat {
    val ValidFormats = setOf(8, 16, 32)
}

private object XPropertyState {
    const val NewValue = 0
    const val Deleted = 1
}

private object XPropertyDelete {
    const val False = 0
    const val True = 1
}

private object XPropertyType {
    const val Any = 0
}

private data class XRenderPictureAttributes(
    val repeat: Int? = null,
)

private data class XCompositeGlyphParseResult(
    val placements: Map<Int, List<XGlyphPlacement>> = emptyMap(),
    val error: XCompositeGlyphParseError? = null,
) {
    companion object {
        fun badLength(): XCompositeGlyphParseResult = error(16, 0)

        fun error(error: Int, badValue: Int): XCompositeGlyphParseResult =
            XCompositeGlyphParseResult(error = XCompositeGlyphParseError(error, badValue))
    }
}

private data class XCompositeGlyphParseError(
    val error: Int,
    val badValue: Int,
)

private data class XTextRun(
    val x: Int,
    val y: Int,
    val text: String,
)

private data class XPolyTextDecode(
    val runs: List<XTextRun>,
    val fontIds: List<Int>,
)

private data class XNamedColor(
    val pixel: Int,
    val exactRed: Int,
    val exactGreen: Int,
    val exactBlue: Int,
    val visualRed: Int,
    val visualGreen: Int,
    val visualBlue: Int,
) {
    companion object {
        fun fromPixel(pixel: Int): XNamedColor {
            val red = ((pixel ushr 16) and 0xff) * 257
            val green = ((pixel ushr 8) and 0xff) * 257
            val blue = (pixel and 0xff) * 257
            return XNamedColor(pixel, red, green, blue, red, green, blue)
        }

        fun fromExact(red: Int, green: Int, blue: Int): XNamedColor {
            val visualRed = ((red ushr 8) and 0xff) * 257
            val visualGreen = ((green ushr 8) and 0xff) * 257
            val visualBlue = ((blue ushr 8) and 0xff) * 257
            val pixel = ((red ushr 8) shl 16) or ((green ushr 8) shl 8) or (blue ushr 8)
            return XNamedColor(pixel, red, green, blue, visualRed, visualGreen, visualBlue)
        }
    }
}

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
