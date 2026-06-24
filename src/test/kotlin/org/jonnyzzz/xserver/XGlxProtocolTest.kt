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

            writeRequest(socket, XGlx.MajorOpcode, XGlx.GetVisualConfigs, u32(0))
            val visuals = readReply(socket.getInputStream())
            assertEquals(1, u32le(visuals, 8))
            assertEquals(XGlx.VisualConfigValues, u32le(visuals, 12))
            assertEquals(X11Ids.RootVisual, u32le(visuals, 32))
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

            writeRequest(socket, XGlx.MajorOpcode, 4, u32(contextId))
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
                json.contains(""""glxPixmaps":[{"id":"0x${glxPixmap.toString(16)}","pixmap":"0x${pixmap.toString(16)}","visual":"0x${X11Ids.RootVisual.toString(16)}","screen":0,"width":8,"height":8,"depth":24}]"""),
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
                json.contains(""""glxPixmaps":[{"id":"0x${glxPixmap.toString(16)}","pixmap":"0x${pixmap.toString(16)}","visual":"0x${X11Ids.RootVisual.toString(16)}","screen":0,"width":11,"height":13,"depth":24}]"""),
                json,
            )
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
            Socket("127.0.0.1", server.localPort).use { socket ->
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
                block(socket)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun writeRequest(socket: Socket, opcode: Int, minorOpcode: Int, body: ByteArray) {
        val units = (4 + body.size) / 4
        socket.getOutputStream().write(byteArrayOf(opcode.toByte(), minorOpcode.toByte()) + u16(units) + body)
        socket.getOutputStream().flush()
    }

    private fun createNewContextBody(context: Int, direct: Boolean): ByteArray =
        u32(context) +
            u32(XGlx.RootFbConfigId) +
            u32(0) +
            u32(XGlx.RgbaType) +
            u32(0) +
            byteArrayOf(if (direct) 1 else 0, 0, 0, 0)

    private fun createContextBody(context: Int, direct: Boolean): ByteArray =
        u32(context) +
            u32(X11Ids.RootVisual) +
            u32(0) +
            u32(0) +
            byteArrayOf(if (direct) 1 else 0, 0, 0, 0)

    private fun createContextAttribsBody(context: Int, direct: Boolean): ByteArray =
        u32(context) +
            u32(XGlx.RootFbConfigId) +
            u32(0) +
            u32(0) +
            byteArrayOf(if (direct) 1 else 0, 0, 0, 0) +
            u32(0)

    private fun createGlxPixmapBody(pixmap: Int, glxPixmap: Int, visual: Int = X11Ids.RootVisual): ByteArray =
        u32(0) + u32(visual) + u32(pixmap) + u32(glxPixmap)

    private fun httpGet(socket: Socket, path: String): String =
        java.net.URI("http://127.0.0.1:${socket.port}$path").toURL().readText()

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payloadUnits = u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
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
