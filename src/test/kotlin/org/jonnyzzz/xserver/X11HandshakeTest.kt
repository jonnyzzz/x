package org.jonnyzzz.xserver

import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class X11HandshakeTest {
    private data class SetupPixmapFormat(val depth: Int, val bitsPerPixel: Int, val scanlinePad: Int)
    private data class SetupDepth(val depth: Int, val visualCount: Int)

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
                val screenOffset = rest.screenOffset()
                assertEquals(3840, u16le(rest, screenOffset + 20))
                assertEquals(2160, u16le(rest, screenOffset + 22))
                assertEquals(975, u16le(rest, screenOffset + 24))
                assertEquals(549, u16le(rest, screenOffset + 26))
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

    @Test
    fun `setup advertises pixmap formats for render compatible depths`() {
        val expected = listOf(
            SetupPixmapFormat(depth = 1, bitsPerPixel = 1, scanlinePad = 32),
            SetupPixmapFormat(depth = 4, bitsPerPixel = 8, scanlinePad = 32),
            SetupPixmapFormat(depth = 8, bitsPerPixel = 8, scanlinePad = 32),
            SetupPixmapFormat(depth = 24, bitsPerPixel = 32, scanlinePad = 32),
            SetupPixmapFormat(depth = 32, bitsPerPixel = 32, scanlinePad = 32),
        )
        XServer(ServerOptions(port = 0)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                writeLittleEndianSetup(socket)
                val rest = readSetupRest(socket)

                assertEquals(expected, rest.pixmapFormats())
                assertEquals(
                    listOf(
                        SetupDepth(depth = 1, visualCount = 0),
                        SetupDepth(depth = 4, visualCount = 0),
                        SetupDepth(depth = 8, visualCount = 0),
                        SetupDepth(depth = 24, visualCount = 1),
                        SetupDepth(depth = 32, visualCount = 0),
                    ),
                    rest.screenDepths(),
                )
                assertEquals(24, rest[rest.screenOffset() + 38].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `reports current input masks in setup screen`() {
        XServer(ServerOptions(port = 0)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                writeLittleEndianSetup(owner)
                readSetupRest(owner)

                val eventMask = XEventMasks.SubstructureNotify or XEventMasks.PropertyChange
                owner.getOutputStream().write(changeWindowEventMaskRequest(X11Ids.RootWindow, eventMask))
                owner.getOutputStream().write(queryPointerRequest())
                owner.getOutputStream().flush()

                val pointerReply = owner.getInputStream().readExactly(32)
                assertEquals(1, pointerReply[0].toInt())
                assertEquals(2, u16le(pointerReply, 2))

                Socket("127.0.0.1", server.localPort).use { observer ->
                    writeLittleEndianSetup(observer)
                    val rest = readSetupRest(observer)
                    assertEquals(eventMask, u32le(rest, rest.screenOffset() + 16))
                }

                Socket("127.0.0.1", server.localPort).use { observer ->
                    writeBigEndianSetup(observer)
                    val rest = readSetupRest(observer, bigEndian = true)
                    assertEquals(eventMask, u32be(rest, rest.screenOffset(bigEndian = true) + 16))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun writeLittleEndianSetup(socket: Socket) {
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
    }

    private fun writeBigEndianSetup(socket: Socket) {
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
    }

    private fun readSetupRest(socket: Socket, bigEndian: Boolean = false): ByteArray {
        val prefix = socket.getInputStream().readExactly(8)
        assertEquals(1, prefix[0].toInt())
        val additionalUnits = if (bigEndian) u16be(prefix, 6) else u16le(prefix, 6)
        assertTrue(additionalUnits > 0)
        return socket.getInputStream().readExactly(additionalUnits * 4)
    }

    private fun changeWindowEventMaskRequest(window: Int, eventMask: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, 1 shl 11)
        put32le(body, 8, eventMask)
        return request(2, 0, body)
    }

    private fun queryPointerRequest(): ByteArray =
        request(38, 0, ByteArray(4).also { put32le(it, 0, X11Ids.RootWindow) })

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray =
        ByteArray(4 + body.size).also {
            it[0] = opcode.toByte()
            it[1] = minorOpcode.toByte()
            put16le(it, 2, it.size / 4)
            body.copyInto(it, 4)
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

    private fun ByteArray.decodeVendor(): String {
        val vendorLength = u16le(this, 16)
        return copyOfRange(32, 32 + vendorLength).decodeToString()
    }

    private fun ByteArray.pixmapFormats(): List<SetupPixmapFormat> {
        val count = this[21].toInt() and 0xff
        val offset = 32 + paddedLength(u16le(this, 16))
        return (0 until count).map { index ->
            val formatOffset = offset + index * 8
            SetupPixmapFormat(
                depth = this[formatOffset].toInt() and 0xff,
                bitsPerPixel = this[formatOffset + 1].toInt() and 0xff,
                scanlinePad = this[formatOffset + 2].toInt() and 0xff,
            )
        }
    }

    private fun ByteArray.screenOffset(bigEndian: Boolean = false): Int {
        val vendorLength = if (bigEndian) u16be(this, 16) else u16le(this, 16)
        return 32 + paddedLength(vendorLength) + (this[21].toInt() and 0xff) * 8
    }

    private fun ByteArray.screenDepths(): List<SetupDepth> {
        var offset = screenOffset() + 40
        val count = this[screenOffset() + 39].toInt() and 0xff
        return (0 until count).map {
            val depth = this[offset].toInt() and 0xff
            val visualCount = u16le(this, offset + 2)
            offset += 8 + visualCount * 24
            SetupDepth(depth, visualCount)
        }
    }

    private fun paddedLength(length: Int): Int = (length + 3) and -4

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

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
