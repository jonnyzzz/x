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
