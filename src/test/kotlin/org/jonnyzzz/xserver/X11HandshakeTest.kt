package org.jonnyzzz.xserver

import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class X11HandshakeTest {
    @Test
    fun `returns setup success for little endian client`() {
        XServer(ServerOptions(port = 0, width = 3840, height = 2160, dpi = 100)).use { server ->
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
                assertEquals(1, prefix[0].toInt())
                assertEquals(11, u16le(prefix, 2))
                val additionalUnits = u16le(prefix, 6)
                assertTrue(additionalUnits > 0)
                val rest = socket.getInputStream().readExactly(additionalUnits * 4)
                assertEquals("jonnyzzz/x", rest.decodeVendor())
                assertEquals(3840, u16le(rest, 72))
                assertEquals(2160, u16le(rest, 74))
                assertEquals(975, u16le(rest, 76))
                assertEquals(549, u16le(rest, 78))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `returns setup success for big endian client`() {
        XServer(ServerOptions(port = 0)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.getOutputStream().write(
                    byteArrayOf(
                        0x42,
                        0,
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
                    ),
                )
                socket.getOutputStream().flush()

                val prefix = socket.getInputStream().readExactly(8)
                assertEquals(1, prefix[0].toInt())
                assertEquals(11, u16be(prefix, 2))
                assertTrue(u16be(prefix, 6) > 0)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun ByteArray.decodeVendor(): String {
        val vendorLength = u16le(this, 16)
        return copyOfRange(32, 32 + vendorLength).decodeToString()
    }

    private fun java.io.InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read == -1) error("Expected $size bytes, got $offset")
            offset += read
        }
        return bytes
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
