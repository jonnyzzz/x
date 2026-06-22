package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class XQueryBestSizeProtocolTest {
    @Test
    fun `QueryBestSize validates shape class and returns nonzero best dimensions`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryBestSizeRequest(CursorShape, X11Ids.RootWindow, width = 0, height = 0))
                out.write(queryBestSizeRequest(TileShape, X11Ids.RootWindow, width = 17, height = 19))
                out.write(queryBestSizeRequest(StippleShape, X11Ids.RootWindow, width = 5, height = 0))
                out.flush()

                val cursor = readReply(socket.getInputStream())
                assertEquals(1, cursor[0].toInt())
                assertEquals(1, u16le(cursor, 8))
                assertEquals(1, u16le(cursor, 10))

                val tile = readReply(socket.getInputStream())
                assertEquals(17, u16le(tile, 8))
                assertEquals(19, u16le(tile, 10))

                val stipple = readReply(socket.getInputStream())
                assertEquals(5, u16le(stipple, 8))
                assertEquals(1, u16le(stipple, 10))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `QueryBestSize reports protocol errors for invalid class and drawable`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryBestSizeRequest(shapeClass = 3, drawable = X11Ids.RootWindow, width = 8, height = 8))
                out.write(queryBestSizeRequest(shapeClass = CursorShape, drawable = 0x0020_dead, width = 8, height = 8))
                out.flush()

                val classError = socket.getInputStream().readExactly(32)
                assertEquals(0, classError[0].toInt())
                assertEquals(2, classError[1].toInt() and 0xff)
                assertEquals(3, u32le(classError, 4))
                assertEquals(97, classError[10].toInt() and 0xff)

                val drawableError = socket.getInputStream().readExactly(32)
                assertEquals(0, drawableError[0].toInt())
                assertEquals(9, drawableError[1].toInt() and 0xff)
                assertEquals(0x0020_dead, u32le(drawableError, 4))
                assertEquals(97, drawableError[10].toInt() and 0xff)
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

    private fun queryBestSizeRequest(shapeClass: Int, drawable: Int, width: Int, height: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, drawable)
        put16le(body, 4, width)
        put16le(body, 6, height)
        return request(97, shapeClass, body)
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

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private companion object {
        const val CursorShape = 0
        const val TileShape = 1
        const val StippleShape = 2
    }
}
