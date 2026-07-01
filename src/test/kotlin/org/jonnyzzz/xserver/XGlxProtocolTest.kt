package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XGlxProtocolTest {
    @Test
    fun `core extension queries expose GLX and SGI alias`() {
        withServer { socket ->
            val glx = queryExtension(socket, "GLX")
            assertEquals(1, glx[8].toInt())
            assertEquals(XGlx.MajorOpcode, glx[9].toInt() and 0xff)
            assertEquals(XGlx.FirstEvent, glx[10].toInt() and 0xff)
            assertEquals(XGlx.FirstError, glx[11].toInt() and 0xff)

            val alias = queryExtension(socket, "SGI-GLX")
            assertEquals(1, alias[8].toInt())
            assertEquals(XGlx.MajorOpcode, alias[9].toInt() and 0xff)

            writeRequest(socket, 99, 0, ByteArray(0))
            val list = readReply(socket.getInputStream())
            assertTrue(list.copyOfRange(32, list.size).decodeToString().contains("GLX"), list.contentToString())
        }
    }

    @Test
    fun `GLX replies with version strings and framebuffer configs`() {
        withServer { socket ->
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryVersion, u32(1) + u32(4))
            val version = readReply(socket.getInputStream())
            assertEquals(1, u32le(version, 8))
            assertEquals(4, u32le(version, 12))

            assertEquals("jonnyzzz/x", queryServerString(socket, XGlx.VendorName))
            assertEquals("1.4", queryServerString(socket, XGlx.VersionName))
            assertEquals("", queryServerString(socket, XGlx.ExtensionsName))

            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetFBConfigs, u32(0))
            val fbConfigs = readReply(socket.getInputStream())
            assertEquals(1, u32le(fbConfigs, 8))
            assertEquals(XGlx.FbConfigAttributePairs, u32le(fbConfigs, 12))
            assertEquals(0x800B, u32le(fbConfigs, 32))
            assertEquals(X11Ids.RootVisual, u32le(fbConfigs, 36))
            val fbConfigAttributes = attributeMap(fbConfigs, offset = 32, count = XGlx.FbConfigAttributePairs)
            assertEquals(XGlx.WindowBit or XGlx.PixmapBit or XGlx.PbufferBit, fbConfigAttributes.getValue(XGlx.DrawableType))

            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetVisualConfigs, u32(0))
            val visuals = readReply(socket.getInputStream())
            assertEquals(1, u32le(visuals, 8))
            assertEquals(XGlx.VisualConfigValues, u32le(visuals, 12))
            assertEquals(X11Ids.RootVisual, u32le(visuals, 32))
        }
    }

    @Test
    fun `GLX QueryVersion validates fixed request length`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryVersion, u32(1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryVersion, u32(1) + u32(4) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryVersion, u32(1) + u32(4))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryVersion, sequence = 1)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryVersion, sequence = 2)
            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, u32le(version, 8))
            assertEquals(4, u32le(version, 12))
        }
    }

    @Test
    fun `GLX screen query requests validate fixed length and screen value`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetVisualConfigs, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetVisualConfigs, u32(1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetVisualConfigs, u32(0) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetVisualConfigs, u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetFBConfigs, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetFBConfigs, u32(1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetFBConfigs, u32(0) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetFBConfigs, u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryExtensionsString, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryExtensionsString, u32(1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryExtensionsString, u32(0) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryExtensionsString, u32(0))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.GetVisualConfigs, sequence = 1)
            assertGlxError(socket.getInputStream(), error = 2, badValue = 1, minorOpcode = XGlx.GetVisualConfigs, sequence = 2)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.GetVisualConfigs, sequence = 3)
            val visuals = readReply(socket.getInputStream())
            assertEquals(4, u16le(visuals, 2))
            assertEquals(1, u32le(visuals, 8))
            assertEquals(XGlx.VisualConfigValues, u32le(visuals, 12))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.GetFBConfigs, sequence = 5)
            assertGlxError(socket.getInputStream(), error = 2, badValue = 1, minorOpcode = XGlx.GetFBConfigs, sequence = 6)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.GetFBConfigs, sequence = 7)
            val fbConfigs = readReply(socket.getInputStream())
            assertEquals(8, u16le(fbConfigs, 2))
            assertEquals(1, u32le(fbConfigs, 8))
            assertEquals(XGlx.FbConfigAttributePairs, u32le(fbConfigs, 12))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryExtensionsString, sequence = 9)
            assertGlxError(socket.getInputStream(), error = 2, badValue = 1, minorOpcode = XGlx.QueryExtensionsString, sequence = 10)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryExtensionsString, sequence = 11)
            val extensions = readReply(socket.getInputStream())
            assertEquals(12, u16le(extensions, 2))
            assertEquals(0, u32le(extensions, 12))
        }
    }

    @Test
    fun `GLX client info requests accept framed metadata without replies`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            writeRequest(socket, XGlx.MajorOpcode, XGlx.ClientInfo, glxClientInfoBody("GLX_EXT_visual_info"))
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.SetClientInfoARB,
                glxSetClientInfoBody(wordsPerVersion = 2, versionWords = listOf(4, 6), glExtensions = "GL_ARB_multisample!", glxExtensions = "GLX_ARB_create_context?"),
            )
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.SetClientInfo2ARB,
                glxSetClientInfoBody(wordsPerVersion = 3, versionWords = listOf(4, 6, 0), glExtensions = "GL_EXT_texture!", glxExtensions = "GLX_EXT_texture_from_pixmap?"),
            )
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX client info requests validate advertised payload lengths`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            writeRequest(socket, XGlx.MajorOpcode, XGlx.ClientInfo, u32(1) + u32(4) + u32(4))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.ClientInfo, u32(1) + u32(4) + u32(1) + padded(byteArrayOf(0)) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.SetClientInfoARB, u32(1) + u32(1) + u32(1) + u32(4) + u32(6) + padded(byteArrayOf(1, 2)))
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.SetClientInfo2ARB,
                u32(1) + u32(1) + u32(1) + u32(4) + u32(6) + u32(0) + padded(byteArrayOf(3, 4)),
            )
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.ClientInfo, sequence = 1)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.ClientInfo, sequence = 2)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.SetClientInfoARB, sequence = 3)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.SetClientInfo2ARB, sequence = 4)
            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX vendor private requests report unsupported private request and recover stream`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val vendorCode = 0x0001_0004
            val vendorReplyCode = 0x0001_0005
            writeRequest(socket, XGlx.MajorOpcode, XGlx.VendorPrivate, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.VendorPrivate, u32(vendorCode) + u32(0x1234_5678))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.VendorPrivateWithReply, u32(vendorReplyCode))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.VendorPrivate, sequence = 1)
            assertGlxError(
                socket.getInputStream(),
                error = XGlx.BadUnsupportedPrivateRequest,
                badValue = vendorCode,
                minorOpcode = XGlx.VendorPrivate,
                sequence = 2,
            )
            assertGlxError(
                socket.getInputStream(),
                error = XGlx.BadUnsupportedPrivateRequest,
                badValue = vendorReplyCode,
                minorOpcode = XGlx.VendorPrivateWithReply,
                sequence = 3,
            )
            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))

            val text = httpGet(socket, "/text.txt")
            val unsupportedSection = text.substringAfter("Unsupported requests:").substringBefore("\n\nGLX operations:")
            assertEquals("\n- None.", unsupportedSection)
        }
    }

    @Test
    fun `GLX context lifecycle is modeled and make current returns a tag`() {
        withServer { socket ->
            val contextId = 0x0020_0100
            val windowId = X11Ids.RootWindow
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.CreateNewContext,
                u32(contextId) +
                    u32(XGlx.RootFbConfigId) +
                    u32(0) +
                    u32(XGlx.RgbaType) +
                    u32(0) +
                    byteArrayOf(0, 0, 0, 0),
            )

            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.MakeContextCurrent,
                u32(0) + u32(windowId) + u32(windowId) + u32(contextId),
            )
            val makeCurrent = readReply(socket.getInputStream())
            assertEquals(contextId, u32le(makeCurrent, 8))

            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId))
            val direct = readReply(socket.getInputStream())
            assertEquals(0, direct[8].toInt())

            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyContext, u32(contextId))
        }
    }

    @Test
    fun `GLX MakeCurrent requests validate fixed request length`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0104
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(contextId) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, 5, u32(X11Ids.RootWindow) + u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, 5, u32(X11Ids.RootWindow) + u32(contextId) + u32(0) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, 5, u32(X11Ids.RootWindow) + u32(contextId) + u32(0))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.MakeContextCurrent, sequence = 2)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.MakeContextCurrent, sequence = 3)
            val contextCurrent = readReply(socket.getInputStream())
            assertEquals(4, u16le(contextCurrent, 2))
            assertEquals(contextId, u32le(contextCurrent, 8))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = 5, sequence = 5)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = 5, sequence = 6)
            val legacyCurrent = readReply(socket.getInputStream())
            assertEquals(7, u16le(legacyCurrent, 2))
            assertEquals(contextId, u32le(legacyCurrent, 8))
        }
    }

    @Test
    fun `GLX MakeCurrent requests reject missing context and preserve unbind`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val missingContext = 0x0020_0108
            val missingLegacyContext = 0x0020_0109
            val contextId = 0x0020_010a
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(missingContext))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeCurrent, u32(X11Ids.RootWindow) + u32(missingLegacyContext) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeCurrent, u32(X11Ids.RootWindow) + u32(0) + u32(contextId))

            assertGlxError(socket.getInputStream(), error = XGlx.BadContext, badValue = missingContext, minorOpcode = XGlx.MakeContextCurrent, sequence = 1)
            assertGlxError(socket.getInputStream(), error = XGlx.BadContext, badValue = missingLegacyContext, minorOpcode = XGlx.MakeCurrent, sequence = 2)
            val current = readReply(socket.getInputStream())
            assertEquals(4, u16le(current, 2))
            assertEquals(contextId, u32le(current, 8))
            val unbound = readReply(socket.getInputStream())
            assertEquals(5, u16le(unbound, 2))
            assertEquals(0, u32le(unbound, 8))
        }
    }

    @Test
    fun `GLX MakeCurrent requests reject missing old context tag and recover stream`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_010b
            val missingOldTag = 0x0020_010c
            val missingLegacyOldTag = 0x0020_010d
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(missingOldTag) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeCurrent, u32(X11Ids.RootWindow) + u32(0) + u32(missingLegacyOldTag))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(contextId))

            assertGlxError(socket.getInputStream(), error = XGlx.BadContextTag, badValue = missingOldTag, minorOpcode = XGlx.MakeContextCurrent, sequence = 2)
            assertGlxError(socket.getInputStream(), error = XGlx.BadContextTag, badValue = missingLegacyOldTag, minorOpcode = XGlx.MakeCurrent, sequence = 3)
            val current = readReply(socket.getInputStream())
            assertEquals(4, u16le(current, 2))
            assertEquals(contextId, u32le(current, 8))
        }
    }

    @Test
    fun `GLX MakeCurrent requests validate drawables and expose current binding`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_010e
            val missingDraw = 0x0020_010f
            val missingRead = 0x0020_0110
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(missingDraw) + u32(X11Ids.RootWindow) + u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(missingRead) + u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeCurrent, u32(missingDraw) + u32(contextId) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(0) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(contextId))

            assertGlxError(socket.getInputStream(), error = XGlx.BadDrawable, badValue = missingDraw, minorOpcode = XGlx.MakeContextCurrent, sequence = 2)
            assertGlxError(socket.getInputStream(), error = XGlx.BadDrawable, badValue = missingRead, minorOpcode = XGlx.MakeContextCurrent, sequence = 3)
            assertGlxError(socket.getInputStream(), error = XGlx.BadDrawable, badValue = missingDraw, minorOpcode = XGlx.MakeCurrent, sequence = 4)
            val current = readReply(socket.getInputStream())
            assertEquals(5, u16le(current, 2))
            assertEquals(contextId, u32le(current, 8))

            val boundJson = httpGet(socket, "/state.json")
            assertTrue(
                boundJson.contains(""""glxContexts":[{"id":"0x${contextId.toString(16)}","fbConfig":"0x${XGlx.RootFbConfigId.toString(16)}","screen":0,"renderType":"0x${XGlx.RgbaType.toString(16)}","direct":false,"currentDrawDrawable":"0x${X11Ids.RootWindow.toString(16)}","currentReadDrawable":"0x${X11Ids.RootWindow.toString(16)}"}]"""),
                boundJson,
            )

            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeCurrent, u32(X11Ids.RootWindow) + u32(0) + u32(contextId))
            val unbound = readReply(socket.getInputStream())
            assertEquals(6, u16le(unbound, 2))
            assertEquals(0, u32le(unbound, 8))

            val unboundJson = httpGet(socket, "/state.json")
            assertTrue(unboundJson.contains(""""currentDrawDrawable":null,"currentReadDrawable":null"""), unboundJson)
        }
    }

    @Test
    fun `GLX fixed size string and direct queries validate request length`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0103
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryServerString, u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryServerString, u32(0) + u32(XGlx.VendorName) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryServerString, u32(1) + u32(XGlx.VendorName))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryServerString, u32(0) + u32(XGlx.VendorName))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = true))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryServerString, sequence = 1)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryServerString, sequence = 2)
            assertGlxError(socket.getInputStream(), error = 2, badValue = 1, minorOpcode = XGlx.QueryServerString, sequence = 3)

            val vendor = readReply(socket.getInputStream())
            assertEquals(4, u16le(vendor, 2))
            val vendorLength = u32le(vendor, 12)
            assertEquals("jonnyzzz/x", vendor.copyOfRange(32, 32 + vendorLength).decodeToString())

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.IsDirect, sequence = 5)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.IsDirect, sequence = 7)

            val direct = readReply(socket.getInputStream())
            assertEquals(8, u16le(direct, 2))
            assertEquals(1, direct[8].toInt())
        }
    }

    @Test
    fun `GLX IsDirect rejects missing context and recovers stream`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val missingContext = 0x0020_0104
            val contextId = 0x0020_0105
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(missingContext))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = true))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId))

            assertGlxError(socket.getInputStream(), error = XGlx.BadContext, badValue = missingContext, minorOpcode = XGlx.IsDirect, sequence = 1)
            val direct = readReply(socket.getInputStream())
            assertEquals(3, u16le(direct, 2))
            assertEquals(1, direct[8].toInt())
        }
    }

    @Test
    fun `GLX WaitGL WaitX and SwapBuffers accept valid modeled resources without replies`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0110
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitGL, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitX, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.SwapBuffers, u32(0) + u32(X11Ids.RootWindow))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX WaitGL WaitX and SwapBuffers validate fixed request length`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0111
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitGL, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitGL, u32(contextId) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitX, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitX, u32(contextId) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.SwapBuffers, u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.SwapBuffers, u32(0) + u32(X11Ids.RootWindow) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitGL, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitX, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.SwapBuffers, u32(0) + u32(X11Ids.RootWindow))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.WaitGL, sequence = 2)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.WaitGL, sequence = 3)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.WaitX, sequence = 4)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.WaitX, sequence = 5)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.SwapBuffers, sequence = 6)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.SwapBuffers, sequence = 7)
            val pointer = readReply(socket.getInputStream())
            assertEquals(11, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX WaitGL WaitX and SwapBuffers validate context tags and drawables`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val badTag = 0x0020_0120
            val missingDrawable = 0x0020_0121
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitGL, u32(badTag))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.WaitX, u32(badTag))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.SwapBuffers, u32(badTag) + u32(X11Ids.RootWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.SwapBuffers, u32(0) + u32(missingDrawable))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val waitGlError = socket.getInputStream().readExactly(32)
            assertEquals(0, waitGlError[0].toInt())
            assertEquals(XGlx.BadContextTag, waitGlError[1].toInt() and 0xff)
            assertEquals(badTag, u32le(waitGlError, 4))
            assertEquals(XGlx.WaitGL, u16le(waitGlError, 8))
            assertEquals(XGlx.MajorOpcode, waitGlError[10].toInt() and 0xff)

            val waitXError = socket.getInputStream().readExactly(32)
            assertEquals(0, waitXError[0].toInt())
            assertEquals(XGlx.BadContextTag, waitXError[1].toInt() and 0xff)
            assertEquals(badTag, u32le(waitXError, 4))
            assertEquals(XGlx.WaitX, u16le(waitXError, 8))
            assertEquals(XGlx.MajorOpcode, waitXError[10].toInt() and 0xff)

            val swapTagError = socket.getInputStream().readExactly(32)
            assertEquals(0, swapTagError[0].toInt())
            assertEquals(XGlx.BadContextTag, swapTagError[1].toInt() and 0xff)
            assertEquals(badTag, u32le(swapTagError, 4))
            assertEquals(XGlx.SwapBuffers, u16le(swapTagError, 8))
            assertEquals(XGlx.MajorOpcode, swapTagError[10].toInt() and 0xff)

            val swapDrawableError = socket.getInputStream().readExactly(32)
            assertEquals(0, swapDrawableError[0].toInt())
            assertEquals(XGlx.BadDrawable, swapDrawableError[1].toInt() and 0xff)
            assertEquals(missingDrawable, u32le(swapDrawableError, 4))
            assertEquals(XGlx.SwapBuffers, u16le(swapDrawableError, 8))
            assertEquals(XGlx.MajorOpcode, swapDrawableError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX UseXFont accepts valid context tag and font without a reply`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0128
            val font = 0x0020_0129
            val gc = 0x0020_012a
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, 45, 0, openFontBody(font))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.UseXFont, useXFontBody(contextTag = contextId, fontable = font))
            writeRequest(socket, 55, 0, createGcBody(gc))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.UseXFont, useXFontBody(contextTag = contextId, fontable = gc))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val pointer = readReply(socket.getInputStream())
            assertEquals(6, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX UseXFont validates request length context tag and fontable`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_012a
            val badTag = 0x0020_012b
            val missingFont = 0x0020_012c
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.UseXFont, u32(contextId) + u32(missingFont))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.UseXFont, useXFontBody(contextTag = badTag, fontable = missingFont))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.UseXFont, useXFontBody(contextTag = contextId, fontable = missingFont))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.UseXFont, sequence = 2)
            assertGlxError(socket.getInputStream(), error = XGlx.BadContextTag, badValue = badTag, minorOpcode = XGlx.UseXFont, sequence = 3)
            assertGlxError(socket.getInputStream(), error = 7, badValue = missingFont, minorOpcode = XGlx.UseXFont, sequence = 4)
            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX Render accepts valid context tag and validates missing context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_012d
            val badTag = 0x0020_012e
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.Render, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.Render, u32(badTag))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = XGlx.BadContextTag, badValue = badTag, minorOpcode = XGlx.Render, sequence = 3)
            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX RenderLarge accepts sequenced chunks without replies`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_012f
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.Render, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = byteArrayOf(9, 8, 7, 6), requestNumber = 2, requestTotal = 2))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = XGlx.BadLargeRequest, badValue = XGlx.Render, minorOpcode = XGlx.Render, sequence = 3)
            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX RenderLarge accepts Xorg padded command totals`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_013b
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1) + byteArrayOf(7), requestNumber = 1, requestTotal = 1))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val pointer = readReply(socket.getInputStream())
            assertEquals(3, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX RenderLarge tracks pending chunks per context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val firstContext = 0x0020_0134
            val secondContext = 0x0020_0135
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(firstContext, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(secondContext, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(firstContext, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(secondContext, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(secondContext, data = byteArrayOf(9, 8, 7, 6), requestNumber = 2, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(firstContext, data = byteArrayOf(5, 4, 3, 2), requestNumber = 2, requestTotal = 2))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val pointer = readReply(socket.getInputStream())
            assertEquals(7, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX RenderLarge pending state is shared across clients for same context`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            connect(server.localPort).use { firstClient ->
                connect(server.localPort).use { secondClient ->
                    firstClient.soTimeout = 2_000
                    secondClient.soTimeout = 2_000
                    val contextId = 0x0020_0139
                    writeRequest(firstClient, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
                    writeRequest(firstClient, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
                    writeRequest(firstClient, 38, 0, u32(X11Ids.RootWindow))
                    assertEquals(3, u16le(readReply(firstClient.getInputStream()), 2))
                    writeRequest(secondClient, XGlx.MajorOpcode, XGlx.Render, u32(contextId))
                    assertGlxError(secondClient.getInputStream(), error = XGlx.BadLargeRequest, badValue = XGlx.Render, minorOpcode = XGlx.Render, sequence = 1)
                    writeRequest(firstClient, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = byteArrayOf(1, 2, 3, 4), requestNumber = 2, requestTotal = 2))
                    writeRequest(firstClient, 38, 0, u32(X11Ids.RootWindow))

                    val pointer = readReply(firstClient.getInputStream())
                    assertEquals(5, u16le(pointer, 2))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `GLX DestroyContext clears pending large render state`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0138
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyContext, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyContext, u32(contextId) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = byteArrayOf(1, 2, 3, 4), requestNumber = 2, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyContext, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.Render, u32(contextId))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.DestroyContext, sequence = 3)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.DestroyContext, sequence = 4)
            val pointer = readReply(socket.getInputStream())
            assertEquals(10, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX DestroyContext rejects missing context and recovers stream`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val missingContext = 0x0020_013c
            val contextId = 0x0020_013d
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyContext, u32(missingContext))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyContext, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyContext, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId))

            assertGlxError(
                socket.getInputStream(),
                error = XGlx.BadContext,
                badValue = missingContext,
                minorOpcode = XGlx.DestroyContext,
                sequence = 1,
            )
            assertGlxError(
                socket.getInputStream(),
                error = XGlx.BadContext,
                badValue = contextId,
                minorOpcode = XGlx.DestroyContext,
                sequence = 4,
            )
            val direct = readReply(socket.getInputStream())
            assertEquals(6, u16le(direct, 2))
            assertEquals(0, direct[8].toInt())
        }
    }

    @Test
    fun `GLX MakeCurrent requests reject pending large render for old context tag`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_013a
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.MakeContextCurrent, u32(contextId) + u32(X11Ids.RootWindow) + u32(X11Ids.RootWindow) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, 5, u32(X11Ids.RootWindow) + u32(0) + u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = byteArrayOf(1, 2, 3, 4), requestNumber = 2, requestTotal = 2))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = XGlx.BadLargeRequest, badValue = XGlx.MakeContextCurrent, minorOpcode = XGlx.MakeContextCurrent, sequence = 3)
            assertGlxError(socket.getInputStream(), error = XGlx.BadLargeRequest, badValue = 5, minorOpcode = 5, sequence = 4)
            val pointer = readReply(socket.getInputStream())
            assertEquals(6, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX RenderLarge validates length context tag and sequence`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0132
            val badTag = 0x0020_0133
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, u32(contextId) + u16(1) + u16(1) + u32(5) + byteArrayOf(1, 2, 3, 4))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(badTag, data = u32(8) + u32(1), requestNumber = 1, requestTotal = 1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1), requestNumber = 2, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(contextId, data = byteArrayOf(1, 2, 3, 4), requestNumber = 3, requestTotal = 2))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.RenderLarge, sequence = 2)
            assertGlxError(socket.getInputStream(), error = XGlx.BadContextTag, badValue = badTag, minorOpcode = XGlx.RenderLarge, sequence = 3)
            assertGlxError(socket.getInputStream(), error = XGlx.BadLargeRequest, badValue = 2, minorOpcode = XGlx.RenderLarge, sequence = 4)
            assertGlxError(socket.getInputStream(), error = XGlx.BadLargeRequest, badValue = 3, minorOpcode = XGlx.RenderLarge, sequence = 6)
            val pointer = readReply(socket.getInputStream())
            assertEquals(7, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX CopyContext accepts modeled contexts without a reply`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val source = 0x0020_0130
            val destination = 0x0020_0131
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(source, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(destination, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, destination, mask = 0, contextTag = source))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX CopyContext rejects pending large render for context tag`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val source = 0x0020_0136
            val destination = 0x0020_0137
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(source, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(destination, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(source, data = u32(12) + u32(1), requestNumber = 1, requestTotal = 2))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, destination, mask = 0, contextTag = source))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.RenderLarge, renderLargeBody(source, data = byteArrayOf(1, 2, 3, 4), requestNumber = 2, requestTotal = 2))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = XGlx.BadLargeRequest, badValue = XGlx.CopyContext, minorOpcode = XGlx.CopyContext, sequence = 4)
            val pointer = readReply(socket.getInputStream())
            assertEquals(6, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX CopyContext validates source destination and context tag`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val source = 0x0020_0140
            val destination = 0x0020_0141
            val missingSource = 0x0020_0142
            val missingDestination = 0x0020_0143
            val badTag = 0x0020_0144
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(source, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(destination, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(missingSource, destination))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, missingDestination))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, destination, contextTag = badTag))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, destination, contextTag = destination))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val missingSourceError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingSourceError[0].toInt())
            assertEquals(XGlx.BadContext, missingSourceError[1].toInt() and 0xff)
            assertEquals(missingSource, u32le(missingSourceError, 4))
            assertEquals(XGlx.CopyContext, u16le(missingSourceError, 8))
            assertEquals(XGlx.MajorOpcode, missingSourceError[10].toInt() and 0xff)

            val missingDestinationError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingDestinationError[0].toInt())
            assertEquals(XGlx.BadContext, missingDestinationError[1].toInt() and 0xff)
            assertEquals(missingDestination, u32le(missingDestinationError, 4))
            assertEquals(XGlx.CopyContext, u16le(missingDestinationError, 8))
            assertEquals(XGlx.MajorOpcode, missingDestinationError[10].toInt() and 0xff)

            val badTagError = socket.getInputStream().readExactly(32)
            assertEquals(0, badTagError[0].toInt())
            assertEquals(XGlx.BadContextTag, badTagError[1].toInt() and 0xff)
            assertEquals(badTag, u32le(badTagError, 4))
            assertEquals(XGlx.CopyContext, u16le(badTagError, 8))
            assertEquals(XGlx.MajorOpcode, badTagError[10].toInt() and 0xff)

            val tagMismatchError = socket.getInputStream().readExactly(32)
            assertEquals(0, tagMismatchError[0].toInt())
            assertEquals(8, tagMismatchError[1].toInt() and 0xff)
            assertEquals(source, u32le(tagMismatchError, 4))
            assertEquals(XGlx.CopyContext, u16le(tagMismatchError, 8))
            assertEquals(XGlx.MajorOpcode, tagMismatchError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(7, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX fixed context and drawable queries validate request length`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val source = 0x0020_0145
            val destination = 0x0020_0146
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(source, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(destination, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, destination).copyOf(12))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, destination) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(source) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(X11Ids.RootWindow) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CopyContext, copyContextBody(source, destination))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(source))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CopyContext, sequence = 3)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CopyContext, sequence = 4)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryContext, sequence = 5)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryContext, sequence = 6)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.GetDrawableAttributes, sequence = 7)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.GetDrawableAttributes, sequence = 8)

            val contextReply = readReply(socket.getInputStream())
            assertEquals(10, u16le(contextReply, 2))
            assertEquals(5, u32le(contextReply, 8))

            val drawableReply = readReply(socket.getInputStream())
            assertEquals(11, u16le(drawableReply, 2))
            assertEquals(5, u32le(drawableReply, 8))
        }
    }

    @Test
    fun `GLX CreateNewContext rejects duplicate resource id without replacing existing context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0100
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = true))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId))

            val duplicateError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateError[0].toInt())
            assertEquals(11, duplicateError[1].toInt() and 0xff)
            assertEquals(contextId, u32le(duplicateError, 4))
            assertEquals(XGlx.CreateNewContext, u16le(duplicateError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateError[10].toInt() and 0xff)

            val direct = readReply(socket.getInputStream())
            assertEquals(0, direct[8].toInt())
        }
    }

    @Test
    fun `GLX CreateContext rejects duplicate resource id without replacing existing context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0100
            writeRequest(socket, XGlx.MajorOpcode, 3, createContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, 3, createContextBody(contextId, direct = true))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId))

            val duplicateError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateError[0].toInt())
            assertEquals(11, duplicateError[1].toInt() and 0xff)
            assertEquals(contextId, u32le(duplicateError, 4))
            assertEquals(3, u16le(duplicateError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateError[10].toInt() and 0xff)

            val direct = readReply(socket.getInputStream())
            assertEquals(0, direct[8].toInt())
        }
    }

    @Test
    fun `GLX CreateContext and CreateNewContext validate fixed request length before creating context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val legacyContext = 0x0020_0101
            val fbConfigContext = 0x0020_0102
            writeRequest(socket, XGlx.MajorOpcode, 3, createContextBody(legacyContext, direct = false).copyOf(16))
            writeRequest(socket, XGlx.MajorOpcode, 3, createContextBody(legacyContext, direct = true) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, 3, createContextBody(legacyContext, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(legacyContext))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(fbConfigContext, direct = false).copyOf(20))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(fbConfigContext, direct = false) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(fbConfigContext, direct = true))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(fbConfigContext))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = 3, sequence = 1)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = 3, sequence = 2)
            val legacyDirect = readReply(socket.getInputStream())
            assertEquals(4, u16le(legacyDirect, 2))
            assertEquals(0, legacyDirect[8].toInt())

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CreateNewContext, sequence = 5)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CreateNewContext, sequence = 6)
            val fbConfigDirect = readReply(socket.getInputStream())
            assertEquals(8, u16le(fbConfigDirect, 2))
            assertEquals(1, fbConfigDirect[8].toInt())
        }
    }

    @Test
    fun `GLX context creation validates screen visual and fbconfig`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val legacyContext = 0x0020_0108
            val fbConfigContext = 0x0020_0109
            val attribsContext = 0x0020_010a
            val validContext = 0x0020_010b
            val badVisual = X11Ids.RootVisual + 1
            val badFbConfig = XGlx.RootFbConfigId + 1
            val badRenderType = XGlx.RgbaType + 1
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContext, createContextBody(legacyContext, direct = false, screen = 1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContext, createContextBody(legacyContext, direct = false, visual = badVisual))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(fbConfigContext, direct = false, screen = 1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(fbConfigContext, direct = false, fbConfig = badFbConfig))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(fbConfigContext, direct = false, renderType = badRenderType))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(attribsContext, direct = false, screen = 1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(attribsContext, direct = false, fbConfig = badFbConfig))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(validContext, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(validContext))

            assertGlxError(socket.getInputStream(), error = 2, badValue = 1, minorOpcode = XGlx.CreateContext, sequence = 1)
            assertGlxError(socket.getInputStream(), error = 2, badValue = badVisual, minorOpcode = XGlx.CreateContext, sequence = 2)
            assertGlxError(socket.getInputStream(), error = 2, badValue = 1, minorOpcode = XGlx.CreateNewContext, sequence = 3)
            assertGlxError(socket.getInputStream(), error = XGlx.BadFBConfig, badValue = badFbConfig, minorOpcode = XGlx.CreateNewContext, sequence = 4)
            assertGlxError(socket.getInputStream(), error = 2, badValue = badRenderType, minorOpcode = XGlx.CreateNewContext, sequence = 5)
            assertGlxError(socket.getInputStream(), error = 2, badValue = 1, minorOpcode = XGlx.CreateContextAttribsARB, sequence = 6)
            assertGlxError(socket.getInputStream(), error = XGlx.BadFBConfig, badValue = badFbConfig, minorOpcode = XGlx.CreateContextAttribsARB, sequence = 7)
            val query = readReply(socket.getInputStream())
            assertEquals(9, u16le(query, 2))
            assertEquals(5, u32le(query, 8))
        }
    }

    @Test
    fun `GLX CreateContextAttribs validates attributed request length before creating context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val shortContext = 0x0020_0103
            val overlongContext = 0x0020_0104
            val mismatchedContext = 0x0020_0105
            val validContext = 0x0020_0106
            val hugeCountContext = 0x0020_0107
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(shortContext, direct = true).copyOf(20))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(overlongContext, direct = true) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(mismatchedContext, direct = true, attributes = listOf(XGlx.RenderType to XGlx.RgbaType)).copyOf(24))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(hugeCountContext, direct = true).copyOf(20) + u32(0x2000_0000))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(validContext, direct = true, attributes = listOf(XGlx.RenderType to XGlx.RgbaType)))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(validContext))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(overlongContext))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CreateContextAttribsARB, sequence = 1)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CreateContextAttribsARB, sequence = 2)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CreateContextAttribsARB, sequence = 3)
            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.CreateContextAttribsARB, sequence = 4)
            val direct = readReply(socket.getInputStream())
            assertEquals(6, u16le(direct, 2))
            assertEquals(1, direct[8].toInt())
            assertGlxError(socket.getInputStream(), error = XGlx.BadContext, badValue = overlongContext, minorOpcode = XGlx.QueryContext, sequence = 7)
        }
    }

    @Test
    fun `GLX CreateContextAttribs validates render type attribute before creating context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val badContext = 0x0020_0108
            val validContext = 0x0020_0109
            val badRenderType = 0
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.CreateContextAttribsARB,
                createContextAttribsBody(badContext, direct = false, attributes = listOf(XGlx.RenderType to badRenderType)),
            )
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(badContext))
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.CreateContextAttribsARB,
                createContextAttribsBody(validContext, direct = false, attributes = listOf(XGlx.RenderType to XGlx.RgbaType)),
            )
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(validContext))

            assertGlxError(socket.getInputStream(), error = 2, badValue = badRenderType, minorOpcode = XGlx.CreateContextAttribsARB, sequence = 1)
            assertGlxError(socket.getInputStream(), error = XGlx.BadContext, badValue = badContext, minorOpcode = XGlx.QueryContext, sequence = 2)
            val query = readReply(socket.getInputStream())
            assertEquals(4, u16le(query, 2))
            assertEquals(5, u32le(query, 8))
            val attributes = attributeMap(query, offset = 32, count = u32le(query, 8))
            assertEquals(XGlx.RgbaType, attributes.getValue(XGlx.RenderType))
        }
    }

    @Test
    fun `GLX CreateContextAttribs rejects duplicate resource id without replacing existing context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0100
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateContextAttribsARB, createContextAttribsBody(contextId, direct = true))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.IsDirect, u32(contextId))

            val duplicateError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateError[0].toInt())
            assertEquals(11, duplicateError[1].toInt() and 0xff)
            assertEquals(contextId, u32le(duplicateError, 4))
            assertEquals(XGlx.CreateContextAttribsARB, u16le(duplicateError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateError[10].toInt() and 0xff)

            val direct = readReply(socket.getInputStream())
            assertEquals(0, direct[8].toInt())
        }
    }

    @Test
    fun `GLX QueryContext returns context attributes and recovers after missing context`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val contextId = 0x0020_0150
            val missingContext = 0x0020_0151
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateNewContext, createNewContextBody(contextId, direct = false))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(contextId) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(contextId))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryContext, u32(missingContext))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.QueryContext, sequence = 2)
            val query = readReply(socket.getInputStream())
            assertEquals(3, u16le(query, 2))
            assertEquals(10, u32le(query, 4))
            assertEquals(5, u32le(query, 8))
            val attributes = attributeMap(query, offset = 32, count = u32le(query, 8))
            assertEquals(0, attributes.getValue(XGlx.ShareContextExt))
            assertEquals(XGlx.RootFbConfigId, attributes.getValue(XGlx.VisualIdExt))
            assertEquals(0, attributes.getValue(XGlx.ScreenExt))
            assertEquals(XGlx.RootFbConfigId, attributes.getValue(XGlx.FbConfigId))
            assertEquals(XGlx.RgbaType, attributes.getValue(XGlx.RenderType))

            val missingError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingError[0].toInt())
            assertEquals(XGlx.BadContext, missingError[1].toInt() and 0xff)
            assertEquals(missingContext, u32le(missingError, 4))
            assertEquals(XGlx.QueryContext, u16le(missingError, 8))
            assertEquals(XGlx.MajorOpcode, missingError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX CreateGLXPixmap models pixmap resource and validates duplicate ids`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0200
            val glxPixmap = 0x0020_0201
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(8) + u16(8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateGLXPixmap, createGlxPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateGLXPixmap, createGlxPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val duplicateError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateError[0].toInt())
            assertEquals(11, duplicateError[1].toInt() and 0xff)
            assertEquals(glxPixmap, u32le(duplicateError, 4))
            assertEquals(XGlx.CreateGLXPixmap, u16le(duplicateError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(
                json.contains(""""glxPixmaps":[{"id":"0x${glxPixmap.toString(16)}","pixmap":"0x${pixmap.toString(16)}","visual":"0x${X11Ids.RootVisual.toString(16)}","fbConfig":"0x${XGlx.RootFbConfigId.toString(16)}","screen":0,"width":8,"height":8,"depth":24,"eventMask":0,"textureTarget":${XGlx.Texture2DExt}}]"""),
                json,
            )
        }
    }

    @Test
    fun `GLX CreateGLXPixmap distinguishes missing drawable from non pixmap drawable`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val missingPixmap = 0x0020_0300
            val glxPixmap = 0x0020_0301
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateGLXPixmap, createGlxPixmapBody(missingPixmap, glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateGLXPixmap, createGlxPixmapBody(X11Ids.RootWindow, glxPixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val missingError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingError[0].toInt())
            assertEquals(9, missingError[1].toInt() and 0xff)
            assertEquals(missingPixmap, u32le(missingError, 4))
            assertEquals(XGlx.CreateGLXPixmap, u16le(missingError, 8))
            assertEquals(XGlx.MajorOpcode, missingError[10].toInt() and 0xff)

            val nonPixmapError = socket.getInputStream().readExactly(32)
            assertEquals(0, nonPixmapError[0].toInt())
            assertEquals(4, nonPixmapError[1].toInt() and 0xff)
            assertEquals(X11Ids.RootWindow, u32le(nonPixmapError, 4))
            assertEquals(XGlx.CreateGLXPixmap, u16le(nonPixmapError, 8))
            assertEquals(XGlx.MajorOpcode, nonPixmapError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(3, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX pixmap keeps backing geometry after core pixmap resource is freed`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0400
            val glxPixmap = 0x0020_0401
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(11) + u16(13))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateGLXPixmap, createGlxPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, 54, 0, u32(pixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(json.contains(""""pixmaps":[]"""), json)
            assertTrue(
                json.contains(""""glxPixmaps":[{"id":"0x${glxPixmap.toString(16)}","pixmap":"0x${pixmap.toString(16)}","visual":"0x${X11Ids.RootVisual.toString(16)}","fbConfig":"0x${XGlx.RootFbConfigId.toString(16)}","screen":0,"width":11,"height":13,"depth":24,"eventMask":0,"textureTarget":${XGlx.TextureRectangleExt}}]"""),
                json,
            )
        }
    }

    @Test
    fun `GLX CreatePixmap models fbconfig pixmap resource and validates duplicate ids`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0500
            val glxPixmap = 0x0020_0501
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(9) + u16(7))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePixmap, createFbConfigPixmapBody(pixmap, glxPixmap, 0x20 to 0x8000))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePixmap, createFbConfigPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val duplicateError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateError[0].toInt())
            assertEquals(11, duplicateError[1].toInt() and 0xff)
            assertEquals(glxPixmap, u32le(duplicateError, 4))
            assertEquals(XGlx.CreatePixmap, u16le(duplicateError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(
                json.contains(""""glxPixmaps":[{"id":"0x${glxPixmap.toString(16)}","pixmap":"0x${pixmap.toString(16)}","visual":"0x${X11Ids.RootVisual.toString(16)}","fbConfig":"0x${XGlx.RootFbConfigId.toString(16)}","screen":0,"width":9,"height":7,"depth":24,"eventMask":0,"textureTarget":${XGlx.TextureRectangleExt}}]"""),
                json,
            )
        }
    }

    @Test
    fun `GLX CreatePixmap validates fbconfig and attribute framing`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0600
            val badFbConfig = XGlx.RootFbConfigId + 1
            val glxPixmap = 0x0020_0601
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(8) + u16(8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePixmap, createFbConfigPixmapBody(pixmap, glxPixmap, fbConfig = badFbConfig))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePixmap, u32(0) + u32(XGlx.RootFbConfigId) + u32(pixmap) + u32(glxPixmap) + u32(1))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val fbConfigError = socket.getInputStream().readExactly(32)
            assertEquals(0, fbConfigError[0].toInt())
            assertEquals(XGlx.BadFBConfig, fbConfigError[1].toInt() and 0xff)
            assertEquals(badFbConfig, u32le(fbConfigError, 4))
            assertEquals(XGlx.CreatePixmap, u16le(fbConfigError, 8))
            assertEquals(XGlx.MajorOpcode, fbConfigError[10].toInt() and 0xff)

            val lengthError = socket.getInputStream().readExactly(32)
            assertEquals(0, lengthError[0].toInt())
            assertEquals(16, lengthError[1].toInt() and 0xff)
            assertEquals(0, u32le(lengthError, 4))
            assertEquals(XGlx.CreatePixmap, u16le(lengthError, 8))
            assertEquals(XGlx.MajorOpcode, lengthError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(json.contains(""""glxPixmaps":[]"""), json)
        }
    }

    @Test
    fun `GLX GetDrawableAttributes returns modeled pixmap attributes and recovers after missing drawable`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0800
            val glxPixmap = 0x0020_0801
            val missingDrawable = 0x0020_0802
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(9) + u16(7))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePixmap, createFbConfigPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(glxPixmap) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(missingDrawable))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.GetDrawableAttributes, sequence = 3)
            val attributesReply = readReply(socket.getInputStream())
            assertEquals(4, u16le(attributesReply, 2))
            assertEquals(16, u32le(attributesReply, 4))
            assertEquals(8, u32le(attributesReply, 8))
            val attributes = attributeMap(attributesReply, offset = 32, count = u32le(attributesReply, 8))
            assertEquals(0, attributes.getValue(XGlx.YInvertedExt))
            assertEquals(9, attributes.getValue(XGlx.Width))
            assertEquals(7, attributes.getValue(XGlx.Height))
            assertEquals(0, attributes.getValue(XGlx.ScreenExt))
            assertEquals(XGlx.TextureRectangleExt, attributes.getValue(XGlx.TextureTargetExt))
            assertEquals(0, attributes.getValue(XGlx.EventMask))
            assertEquals(XGlx.RootFbConfigId, attributes.getValue(XGlx.FbConfigId))
            assertEquals(XGlx.PixmapBit, attributes.getValue(XGlx.DrawableType))

            val missingError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingError[0].toInt())
            assertEquals(XGlx.BadDrawable, missingError[1].toInt() and 0xff)
            assertEquals(missingDrawable, u32le(missingError, 4))
            assertEquals(XGlx.GetDrawableAttributes, u16le(missingError, 8))
            assertEquals(XGlx.MajorOpcode, missingError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(6, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX GetDrawableAttributes returns naked window attributes`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(X11Ids.RootWindow))

            val attributesReply = readReply(socket.getInputStream())
            assertEquals(1, u16le(attributesReply, 2))
            assertEquals(10, u32le(attributesReply, 4))
            assertEquals(5, u32le(attributesReply, 8))
            val attributes = attributeMap(attributesReply, offset = 32, count = u32le(attributesReply, 8))
            assertEquals(0, attributes.getValue(XGlx.YInvertedExt))
            assertEquals(640, attributes.getValue(XGlx.Width))
            assertEquals(480, attributes.getValue(XGlx.Height))
            assertEquals(0, attributes.getValue(XGlx.ScreenExt))
            assertEquals(XGlx.WindowBit, attributes.getValue(XGlx.DrawableType))
        }
    }

    @Test
    fun `GLX GetDrawableAttributes rejects non GLX core pixmap`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0900
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(8) + u16(8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(pixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val error = socket.getInputStream().readExactly(32)
            assertEquals(0, error[0].toInt())
            assertEquals(XGlx.BadDrawable, error[1].toInt() and 0xff)
            assertEquals(pixmap, u32le(error, 4))
            assertEquals(XGlx.GetDrawableAttributes, u16le(error, 8))
            assertEquals(XGlx.MajorOpcode, error[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(3, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX ChangeDrawableAttributes updates event mask and preserves texture target`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0a00
            val glxPixmap = 0x0020_0a01
            val eventMask = 0x1357
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(9) + u16(7))
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.CreatePixmap,
                createFbConfigPixmapBody(pixmap, glxPixmap, XGlx.TextureTargetExt to XGlx.Texture2DExt),
            )
            writeRequest(
                socket,
                XGlx.MajorOpcode,
                XGlx.ChangeDrawableAttributes,
                changeDrawableAttributesBody(glxPixmap, 0x20 to 0x8000, XGlx.EventMask to eventMask),
            )
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(glxPixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val attributesReply = readReply(socket.getInputStream())
            assertEquals(4, u16le(attributesReply, 2))
            assertEquals(16, u32le(attributesReply, 4))
            assertEquals(8, u32le(attributesReply, 8))
            val attributes = attributeMap(attributesReply, offset = 32, count = u32le(attributesReply, 8))
            assertEquals(XGlx.Texture2DExt, attributes.getValue(XGlx.TextureTargetExt))
            assertEquals(eventMask, attributes.getValue(XGlx.EventMask))
            assertEquals(XGlx.PixmapBit, attributes.getValue(XGlx.DrawableType))

            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(json.contains(""""eventMask":$eventMask,"textureTarget":${XGlx.Texture2DExt}"""), json)
        }
    }

    @Test
    fun `GLX ChangeDrawableAttributes validates drawable and attribute framing`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0b00
            val glxPixmap = 0x0020_0b01
            val missingDrawable = 0x0020_0b02
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(8) + u16(8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePixmap, createFbConfigPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.ChangeDrawableAttributes, u32(glxPixmap) + u32(1))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.ChangeDrawableAttributes, changeDrawableAttributesBody(missingDrawable))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val lengthError = socket.getInputStream().readExactly(32)
            assertEquals(0, lengthError[0].toInt())
            assertEquals(16, lengthError[1].toInt() and 0xff)
            assertEquals(0, u32le(lengthError, 4))
            assertEquals(XGlx.ChangeDrawableAttributes, u16le(lengthError, 8))
            assertEquals(XGlx.MajorOpcode, lengthError[10].toInt() and 0xff)

            val missingError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingError[0].toInt())
            assertEquals(XGlx.BadDrawable, missingError[1].toInt() and 0xff)
            assertEquals(missingDrawable, u32le(missingError, 4))
            assertEquals(XGlx.ChangeDrawableAttributes, u16le(missingError, 8))
            assertEquals(XGlx.MajorOpcode, missingError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
        }
    }

    @Test
    fun `GLX CreateWindow models window drawable attributes`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val window = 0x0020_0c00
            val glxWindow = 0x0020_0c01
            val eventMask = 0x2468
            writeRequest(socket, 1, 24, createWindowBody(window, width = 17, height = 19))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateWindow, createGlxWindowBody(window, glxWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.ChangeDrawableAttributes, changeDrawableAttributesBody(glxWindow, XGlx.EventMask to eventMask))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(glxWindow))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val attributesReply = readReply(socket.getInputStream())
            assertEquals(4, u16le(attributesReply, 2))
            assertEquals(16, u32le(attributesReply, 4))
            assertEquals(8, u32le(attributesReply, 8))
            val attributes = attributeMap(attributesReply, offset = 32, count = u32le(attributesReply, 8))
            assertEquals(0, attributes.getValue(XGlx.YInvertedExt))
            assertEquals(17, attributes.getValue(XGlx.Width))
            assertEquals(19, attributes.getValue(XGlx.Height))
            assertEquals(0, attributes.getValue(XGlx.ScreenExt))
            assertEquals(XGlx.TextureRectangleExt, attributes.getValue(XGlx.TextureTargetExt))
            assertEquals(eventMask, attributes.getValue(XGlx.EventMask))
            assertEquals(XGlx.RootFbConfigId, attributes.getValue(XGlx.FbConfigId))
            assertEquals(XGlx.WindowBit, attributes.getValue(XGlx.DrawableType))

            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(
                json.contains(""""glxWindows":[{"id":"0x${glxWindow.toString(16)}","window":"0x${window.toString(16)}","fbConfig":"0x${XGlx.RootFbConfigId.toString(16)}","screen":0,"width":17,"height":19,"eventMask":$eventMask}]"""),
                json,
            )
        }
    }

    @Test
    fun `GLX CreateWindow and DestroyWindow validate resources and recover stream`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val missingWindow = 0x0020_0d00
            val window = 0x0020_0d01
            val glxWindow = 0x0020_0d02
            val missingGlxWindow = 0x0020_0d03
            val secondGlxWindow = 0x0020_0d04
            writeRequest(socket, 1, 24, createWindowBody(window, width = 8, height = 8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateWindow, createGlxWindowBody(window, glxWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateWindow, createGlxWindowBody(missingWindow, glxWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateWindow, createGlxWindowBody(window, glxWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateWindow, createGlxWindowBody(window, secondGlxWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyWindow, ByteArray(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyWindow, u32(glxWindow) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyWindow, u32(glxWindow))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyWindow, u32(missingGlxWindow))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val missingWindowError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingWindowError[0].toInt())
            assertEquals(3, missingWindowError[1].toInt() and 0xff)
            assertEquals(missingWindow, u32le(missingWindowError, 4))
            assertEquals(XGlx.CreateWindow, u16le(missingWindowError, 8))
            assertEquals(XGlx.MajorOpcode, missingWindowError[10].toInt() and 0xff)

            val duplicateError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateError[0].toInt())
            assertEquals(11, duplicateError[1].toInt() and 0xff)
            assertEquals(glxWindow, u32le(duplicateError, 4))
            assertEquals(XGlx.CreateWindow, u16le(duplicateError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateError[10].toInt() and 0xff)

            val duplicateBackingError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateBackingError[0].toInt())
            assertEquals(11, duplicateBackingError[1].toInt() and 0xff)
            assertEquals(secondGlxWindow, u32le(duplicateBackingError, 4))
            assertEquals(XGlx.CreateWindow, u16le(duplicateBackingError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateBackingError[10].toInt() and 0xff)

            val shortDestroyError = socket.getInputStream().readExactly(32)
            assertEquals(0, shortDestroyError[0].toInt())
            assertEquals(16, shortDestroyError[1].toInt() and 0xff)
            assertEquals(0, u32le(shortDestroyError, 4))
            assertEquals(XGlx.DestroyWindow, u16le(shortDestroyError, 8))
            assertEquals(XGlx.MajorOpcode, shortDestroyError[10].toInt() and 0xff)

            val longDestroyError = socket.getInputStream().readExactly(32)
            assertEquals(0, longDestroyError[0].toInt())
            assertEquals(16, longDestroyError[1].toInt() and 0xff)
            assertEquals(0, u32le(longDestroyError, 4))
            assertEquals(XGlx.DestroyWindow, u16le(longDestroyError, 8))
            assertEquals(XGlx.MajorOpcode, longDestroyError[10].toInt() and 0xff)

            val missingGlxWindowError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingGlxWindowError[0].toInt())
            assertEquals(XGlx.BadWindow, missingGlxWindowError[1].toInt() and 0xff)
            assertEquals(missingGlxWindow, u32le(missingGlxWindowError, 4))
            assertEquals(XGlx.DestroyWindow, u16le(missingGlxWindowError, 8))
            assertEquals(XGlx.MajorOpcode, missingGlxWindowError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(10, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(json.contains(""""glxWindows":[]"""), json)
        }
    }

    @Test
    fun `GLX CreatePbuffer models drawable attributes and event mask`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pbuffer = 0x0020_0e00
            val eventMask = 0x3456
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePbuffer, createPbufferBody(pbuffer, width = 13, height = 11))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.ChangeDrawableAttributes, changeDrawableAttributesBody(pbuffer, XGlx.EventMask to eventMask))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetDrawableAttributes, u32(pbuffer))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val attributesReply = readReply(socket.getInputStream())
            assertEquals(3, u16le(attributesReply, 2))
            assertEquals(18, u32le(attributesReply, 4))
            assertEquals(9, u32le(attributesReply, 8))
            val attributes = attributeMap(attributesReply, offset = 32, count = u32le(attributesReply, 8))
            assertEquals(0, attributes.getValue(XGlx.YInvertedExt))
            assertEquals(13, attributes.getValue(XGlx.Width))
            assertEquals(11, attributes.getValue(XGlx.Height))
            assertEquals(0, attributes.getValue(XGlx.ScreenExt))
            assertEquals(XGlx.TextureRectangleExt, attributes.getValue(XGlx.TextureTargetExt))
            assertEquals(eventMask, attributes.getValue(XGlx.EventMask))
            assertEquals(XGlx.RootFbConfigId, attributes.getValue(XGlx.FbConfigId))
            assertEquals(1, attributes.getValue(XGlx.PreservedContents))
            assertEquals(XGlx.PbufferBit, attributes.getValue(XGlx.DrawableType))

            val pointer = readReply(socket.getInputStream())
            assertEquals(4, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(
                json.contains(""""glxPbuffers":[{"id":"0x${pbuffer.toString(16)}","fbConfig":"0x${XGlx.RootFbConfigId.toString(16)}","screen":0,"width":13,"height":11,"eventMask":$eventMask}]"""),
                json,
            )
        }
    }

    @Test
    fun `GLX CreatePbuffer and DestroyPbuffer validate resources and framing`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pbuffer = 0x0020_0f00
            val missingPbuffer = 0x0020_0f01
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePbuffer, createPbufferBody(pbuffer, width = 8, height = 8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePbuffer, createPbufferBody(pbuffer, width = 4, height = 4))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyPbuffer, u32(pbuffer) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyPbuffer, u32(pbuffer))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyPbuffer, u32(missingPbuffer))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val duplicateError = socket.getInputStream().readExactly(32)
            assertEquals(0, duplicateError[0].toInt())
            assertEquals(11, duplicateError[1].toInt() and 0xff)
            assertEquals(pbuffer, u32le(duplicateError, 4))
            assertEquals(XGlx.CreatePbuffer, u16le(duplicateError, 8))
            assertEquals(XGlx.MajorOpcode, duplicateError[10].toInt() and 0xff)

            val lengthError = socket.getInputStream().readExactly(32)
            assertEquals(0, lengthError[0].toInt())
            assertEquals(16, lengthError[1].toInt() and 0xff)
            assertEquals(0, u32le(lengthError, 4))
            assertEquals(XGlx.DestroyPbuffer, u16le(lengthError, 8))
            assertEquals(XGlx.MajorOpcode, lengthError[10].toInt() and 0xff)

            val missingError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingError[0].toInt())
            assertEquals(XGlx.BadPbuffer, missingError[1].toInt() and 0xff)
            assertEquals(missingPbuffer, u32le(missingError, 4))
            assertEquals(XGlx.DestroyPbuffer, u16le(missingError, 8))
            assertEquals(XGlx.MajorOpcode, missingError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(6, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(json.contains(""""glxPbuffers":[]"""), json)
        }
    }

    @Test
    fun `GLX DestroyPixmap validates fixed request length and recovers stream`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0700
            val glxPixmap = 0x0020_0701
            val missingGlxPixmap = 0x0020_0702
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(8) + u16(8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreatePixmap, createFbConfigPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyPixmap, u32(glxPixmap) + u32(0))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyPixmap, u32(glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyPixmap, u32(missingGlxPixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            assertGlxError(socket.getInputStream(), error = 16, badValue = 0, minorOpcode = XGlx.DestroyPixmap, sequence = 3)
            val missingError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingError[0].toInt())
            assertEquals(XGlx.BadPixmap, missingError[1].toInt() and 0xff)
            assertEquals(5, u16le(missingError, 2))
            assertEquals(missingGlxPixmap, u32le(missingError, 4))
            assertEquals(XGlx.DestroyPixmap, u16le(missingError, 8))
            assertEquals(XGlx.MajorOpcode, missingError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(6, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(json.contains(""""glxPixmaps":[]"""), json)
        }
    }

    @Test
    fun `GLX DestroyGLXPixmap frees modeled resource and recovers stream`() {
        withServer { socket ->
            socket.soTimeout = 2_000
            val pixmap = 0x0020_0200
            val glxPixmap = 0x0020_0201
            val missingGlxPixmap = 0x0020_0202
            writeRequest(socket, 53, 24, u32(pixmap) + u32(X11Ids.RootWindow) + u16(8) + u16(8))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.CreateGLXPixmap, createGlxPixmapBody(pixmap, glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyGLXPixmap, u32(glxPixmap))
            writeRequest(socket, XGlx.MajorOpcode, XGlx.DestroyGLXPixmap, u32(missingGlxPixmap))
            writeRequest(socket, 38, 0, u32(X11Ids.RootWindow))

            val missingError = socket.getInputStream().readExactly(32)
            assertEquals(0, missingError[0].toInt())
            assertEquals(XGlx.BadPixmap, missingError[1].toInt() and 0xff)
            assertEquals(missingGlxPixmap, u32le(missingError, 4))
            assertEquals(XGlx.DestroyGLXPixmap, u16le(missingError, 8))
            assertEquals(XGlx.MajorOpcode, missingError[10].toInt() and 0xff)

            val pointer = readReply(socket.getInputStream())
            assertEquals(5, u16le(pointer, 2))
            val json = httpGet(socket, "/state.json")
            assertTrue(json.contains(""""glxPixmaps":[]"""), json)
        }
    }

    private fun queryExtension(socket: Socket, name: String): ByteArray {
        val bytes = name.encodeToByteArray()
        writeRequest(socket, 98, 0, u16(bytes.size) + byteArrayOf(0, 0) + padded(bytes))
        return readReply(socket.getInputStream())
    }

    private fun queryServerString(socket: Socket, name: Int): String {
        writeRequest(socket, XGlx.MajorOpcode, XGlx.QueryServerString, u32(0) + u32(name))
        val reply = readReply(socket.getInputStream())
        val length = u32le(reply, 12)
        return reply.copyOfRange(32, 32 + length).decodeToString()
    }

    private fun withServer(block: (Socket) -> Unit) {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            connect(server.localPort).use { socket ->
                block(socket)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun connect(port: Int): Socket {
        val socket = Socket("127.0.0.1", port)
        try {
            socket.getOutputStream().write(
                byteArrayOf(
                    0x6c,
                    0,
                    11,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                ),
            )
            socket.getOutputStream().flush()

            val prefix = socket.getInputStream().readExactly(8)
            val additionalUnits = u16le(prefix, 6)
            socket.getInputStream().readExactly(additionalUnits * 4)
            return socket
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }

    private fun writeRequest(socket: Socket, opcode: Int, minorOpcode: Int, body: ByteArray) {
        val units = (4 + body.size) / 4
        socket.getOutputStream().write(byteArrayOf(opcode.toByte(), minorOpcode.toByte()) + u16(units) + body)
        socket.getOutputStream().flush()
    }

    private fun createNewContextBody(
        context: Int,
        direct: Boolean,
        fbConfig: Int = XGlx.RootFbConfigId,
        screen: Int = 0,
        renderType: Int = XGlx.RgbaType,
    ): ByteArray =
        u32(context) +
            u32(fbConfig) +
            u32(screen) +
            u32(renderType) +
            u32(0) +
            byteArrayOf(if (direct) 1 else 0, 0, 0, 0)

    private fun createContextBody(
        context: Int,
        direct: Boolean,
        visual: Int = X11Ids.RootVisual,
        screen: Int = 0,
    ): ByteArray =
        u32(context) +
            u32(visual) +
            u32(screen) +
            u32(0) +
            byteArrayOf(if (direct) 1 else 0, 0, 0, 0)

    private fun createContextAttribsBody(
        context: Int,
        direct: Boolean,
        fbConfig: Int = XGlx.RootFbConfigId,
        screen: Int = 0,
        attributes: List<Pair<Int, Int>> = emptyList(),
    ): ByteArray =
        u32(context) +
            u32(fbConfig) +
            u32(screen) +
            u32(0) +
            byteArrayOf(if (direct) 1 else 0, 0, 0, 0) +
            u32(attributes.size) +
            attributes.flatMap { (attribute, value) -> (u32(attribute) + u32(value)).toList() }.toByteArray()

    private fun copyContextBody(
        source: Int,
        destination: Int,
        mask: Int = 0,
        contextTag: Int = 0,
    ): ByteArray =
        u32(source) + u32(destination) + u32(mask) + u32(contextTag)

    private fun useXFontBody(
        contextTag: Int,
        fontable: Int,
        first: Int = 0,
        count: Int = 1,
        listBase: Int = 1,
    ): ByteArray =
        u32(contextTag) + u32(fontable) + u32(first) + u32(count) + u32(listBase)

    private fun openFontBody(font: Int, name: String = "fixed"): ByteArray {
        val nameBytes = name.encodeToByteArray()
        return u32(font) + u16(nameBytes.size) + byteArrayOf(0, 0) + padded(nameBytes)
    }

    private fun createGcBody(gc: Int, drawable: Int = X11Ids.RootWindow): ByteArray =
        u32(gc) + u32(drawable) + u32(0)

    private fun renderLargeBody(
        contextTag: Int,
        data: ByteArray,
        requestNumber: Int,
        requestTotal: Int,
    ): ByteArray =
        u32(contextTag) + u16(requestNumber) + u16(requestTotal) + u32(data.size) + padded(data)

    private fun glxClientInfoBody(extensions: String): ByteArray {
        val extensionBytes = extensions.encodeToByteArray()
        return u32(1) + u32(4) + u32(extensionBytes.size) + padded(extensionBytes)
    }

    private fun glxSetClientInfoBody(
        wordsPerVersion: Int,
        versionWords: List<Int>,
        glExtensions: String,
        glxExtensions: String,
    ): ByteArray {
        val glBytes = glExtensions.encodeToByteArray()
        val glxBytes = glxExtensions.encodeToByteArray()
        require(versionWords.size % wordsPerVersion == 0)
        return u32(versionWords.size / wordsPerVersion) +
            u32(glBytes.size) +
            u32(glxBytes.size) +
            versionWords.flatMap { u32(it).toList() }.toByteArray() +
            padded(glBytes) +
            padded(glxBytes)
    }

    private fun createGlxPixmapBody(pixmap: Int, glxPixmap: Int, visual: Int = X11Ids.RootVisual): ByteArray =
        u32(0) + u32(visual) + u32(pixmap) + u32(glxPixmap)

    private fun createFbConfigPixmapBody(
        pixmap: Int,
        glxPixmap: Int,
        vararg attributes: Pair<Int, Int>,
        fbConfig: Int = XGlx.RootFbConfigId,
    ): ByteArray =
        u32(0) +
            u32(fbConfig) +
            u32(pixmap) +
            u32(glxPixmap) +
            u32(attributes.size) +
            attributes.flatMap { (attribute, value) -> (u32(attribute) + u32(value)).toList() }.toByteArray()

    private fun changeDrawableAttributesBody(
        drawable: Int,
        vararg attributes: Pair<Int, Int>,
    ): ByteArray =
        u32(drawable) +
            u32(attributes.size) +
            attributes.flatMap { (attribute, value) -> (u32(attribute) + u32(value)).toList() }.toByteArray()

    private fun createWindowBody(window: Int, width: Int, height: Int): ByteArray =
        u32(window) +
            u32(X11Ids.RootWindow) +
            u16(0) +
            u16(0) +
            u16(width) +
            u16(height) +
            u16(0) +
            u16(1) +
            u32(X11Ids.RootVisual) +
            u32(0)

    private fun createGlxWindowBody(
        window: Int,
        glxWindow: Int,
        vararg attributes: Pair<Int, Int>,
        fbConfig: Int = XGlx.RootFbConfigId,
    ): ByteArray =
        u32(0) +
            u32(fbConfig) +
            u32(window) +
            u32(glxWindow) +
            u32(attributes.size) +
            attributes.flatMap { (attribute, value) -> (u32(attribute) + u32(value)).toList() }.toByteArray()

    private fun createPbufferBody(
        pbuffer: Int,
        width: Int,
        height: Int,
        vararg attributes: Pair<Int, Int>,
        fbConfig: Int = XGlx.RootFbConfigId,
    ): ByteArray =
        u32(0) +
            u32(fbConfig) +
            u32(pbuffer) +
            u32(attributes.size + 2) +
            u32(XGlx.PbufferWidth) +
            u32(width) +
            u32(XGlx.PbufferHeight) +
            u32(height) +
            attributes.flatMap { (attribute, value) -> (u32(attribute) + u32(value)).toList() }.toByteArray()

    private fun httpGet(socket: Socket, path: String): String =
        java.net.URI("http://127.0.0.1:${socket.port}$path").toURL().readText()

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payloadUnits = u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
    }

    private fun attributeMap(reply: ByteArray, offset: Int, count: Int): Map<Int, Int> =
        (0 until count).associate { index ->
            u32le(reply, offset + index * 8) to u32le(reply, offset + index * 8 + 4)
        }

    private fun assertGlxError(input: InputStream, error: Int, badValue: Int, minorOpcode: Int, sequence: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(XGlx.MajorOpcode, reply[10].toInt() and 0xff)
    }

    private fun padded(bytes: ByteArray): ByteArray =
        bytes + ByteArray(((bytes.size + 3) and -4) - bytes.size)

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

    private fun u16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun u32(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
