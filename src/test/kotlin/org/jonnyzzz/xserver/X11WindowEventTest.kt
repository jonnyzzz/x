package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class X11WindowEventTest {
    @Test
    fun `configured mapped windows receive configure notify and expose`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                setup(socket)
                socket.getOutputStream().write(createWindowRequest(WindowId, x = 10, y = 20, width = 100, height = 80))
                socket.getOutputStream().write(mapWindowRequest(WindowId))
                socket.getOutputStream().flush()

                assertEquals(19, readEvent(socket.getInputStream(), type = 19)[0].toInt() and 0xff)
                assertEquals(12, readEvent(socket.getInputStream(), type = 12)[0].toInt() and 0xff)

                socket.getOutputStream().write(configureWindowRequest(WindowId, x = 30, y = 40, width = 320, height = 240))
                socket.getOutputStream().flush()

                val configure = readEvent(socket.getInputStream(), type = 22)
                assertEquals(WindowId, u32le(configure, 8))
                assertEquals(30, u16le(configure, 16))
                assertEquals(40, u16le(configure, 18))
                assertEquals(320, u16le(configure, 20))
                assertEquals(240, u16le(configure, 22))

                val expose = readEvent(socket.getInputStream(), type = 12)
                assertEquals(WindowId, u32le(expose, 4))
                assertEquals(320, u16le(expose, 12))
                assertEquals(240, u16le(expose, 14))
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

    private fun readEvent(input: InputStream, type: Int): ByteArray {
        repeat(10) {
            val event = input.readExactly(32)
            if ((event[0].toInt() and 0x7f) == type) return event
        }
        error("Did not receive event type $type")
    }

    private fun createWindowRequest(id: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = 1
        bytes[1] = 24
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, id)
        put32le(bytes, 8, X11Ids.RootWindow)
        put16le(bytes, 12, x)
        put16le(bytes, 14, y)
        put16le(bytes, 16, width)
        put16le(bytes, 18, height)
        put16le(bytes, 20, 1)
        put16le(bytes, 22, 1)
        put32le(bytes, 24, X11Ids.RootVisual)
        return bytes
    }

    private fun mapWindowRequest(id: Int): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = 8
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, id)
        return bytes
    }

    private fun configureWindowRequest(id: Int, x: Int, y: Int, width: Int, height: Int): ByteArray {
        val bytes = ByteArray(28)
        bytes[0] = 12
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, id)
        put16le(bytes, 8, 0x000f)
        put16le(bytes, 10, 0)
        put32le(bytes, 12, x)
        put32le(bytes, 16, y)
        put32le(bytes, 20, width)
        put32le(bytes, 24, height)
        return bytes
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
        const val WindowId = 0x0020_0001
    }
}
