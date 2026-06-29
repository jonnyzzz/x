package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XXCMiscProtocolTest {
    @Test
    fun `XC-MISC reports version allocates XIDs and validates lengths`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("XC-MISC"))
                out.write(listExtensionsRequest())
                out.write(request(XXCMisc.MajorOpcode, XXCMisc.GetVersion, ByteArray(0)))
                out.write(request(XXCMisc.MajorOpcode, XXCMisc.GetVersion, u16(99) + u16(99)))
                out.write(request(XXCMisc.MajorOpcode, XXCMisc.GetXIDRange, u32(0)))
                out.write(request(XXCMisc.MajorOpcode, XXCMisc.GetXIDRange, ByteArray(0)))
                out.write(request(XXCMisc.MajorOpcode, XXCMisc.GetXIDList, ByteArray(0)))
                out.write(request(XXCMisc.MajorOpcode, XXCMisc.GetXIDList, u32(3)))
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XXCMisc.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XXCMisc.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XXCMisc.FirstError, extension[11].toInt() and 0xff)

                val extensions = readReply(socket.getInputStream())
                assertContains(extensionNames(extensions), "XC-MISC")

                assertError(socket.getInputStream(), error = 16, sequence = 3, opcode = XXCMisc.MajorOpcode, minorOpcode = XXCMisc.GetVersion)

                val version = readReply(socket.getInputStream())
                assertEquals(4, u16le(version, 2))
                assertEquals(XXCMisc.MajorVersion, u16le(version, 8))
                assertEquals(XXCMisc.MinorVersion, u16le(version, 10))

                assertError(socket.getInputStream(), error = 16, sequence = 5, opcode = XXCMisc.MajorOpcode, minorOpcode = XXCMisc.GetXIDRange)

                val range = readReply(socket.getInputStream())
                assertEquals(6, u16le(range, 2))
                val rangeStart = u32le(range, 8)
                val rangeCount = u32le(range, 12)
                assertTrue(rangeStart >= X11Ids.ResourceIdBase)
                assertTrue(rangeCount in 1..XXCMisc.MaxIdsPerReply)

                assertError(socket.getInputStream(), error = 16, sequence = 7, opcode = XXCMisc.MajorOpcode, minorOpcode = XXCMisc.GetXIDList)

                val list = readReply(socket.getInputStream())
                assertEquals(8, u16le(list, 2))
                assertEquals(3, u32le(list, 4))
                assertEquals(3, u32le(list, 8))
                val ids = listOf(u32le(list, 32), u32le(list, 36), u32le(list, 40))
                assertEquals(ids.distinct(), ids)
                assertTrue(ids.all { it >= X11Ids.ResourceIdBase })
                assertTrue(ids.none { it in rangeStart until rangeStart + rangeCount })

                out.write(createWindowRequest(rangeStart))
                out.write(getGeometryRequest(rangeStart))
                out.write(createWindowRequest(ids.first(), x = 7, y = 8, width = 30, height = 40))
                out.write(getGeometryRequest(ids.first()))
                out.flush()

                val rangeGeometry = readReply(socket.getInputStream())
                assertEquals(10, u16le(rangeGeometry, 2))
                assertEquals(X11Ids.RootWindow, u32le(rangeGeometry, 8))
                assertEquals(20, u16le(rangeGeometry, 16))
                assertEquals(10, u16le(rangeGeometry, 18))

                val listGeometry = readReply(socket.getInputStream())
                assertEquals(12, u16le(listGeometry, 2))
                assertEquals(7, u16le(listGeometry, 12))
                assertEquals(8, u16le(listGeometry, 14))
                assertEquals(30, u16le(listGeometry, 16))
                assertEquals(40, u16le(listGeometry, 18))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XC-MISC reservations are client scoped and released on disconnect`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val reservedId = Socket("127.0.0.1", server.localPort).use { owner ->
                owner.soTimeout = 2_000
                setup(owner)
                val ownerOut = owner.getOutputStream()
                ownerOut.write(request(XXCMisc.MajorOpcode, XXCMisc.GetXIDRange, ByteArray(0)))
                ownerOut.flush()
                u32le(readReply(owner.getInputStream()), 8)
            }

            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(createWindowRequest(reservedId))
                out.write(getGeometryRequest(reservedId))
                out.flush()

                val geometry = readReply(socket.getInputStream())
                assertEquals(2, u16le(geometry, 2))
                assertEquals(20, u16le(geometry, 16))
                assertEquals(10, u16le(geometry, 18))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XC-MISC reservations cannot be stolen by another connected client`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { other ->
                    owner.soTimeout = 2_000
                    other.soTimeout = 2_000
                    setup(owner)
                    setup(other)

                    val ownerOut = owner.getOutputStream()
                    ownerOut.write(request(XXCMisc.MajorOpcode, XXCMisc.GetXIDRange, ByteArray(0)))
                    ownerOut.flush()
                    val reservedId = u32le(readReply(owner.getInputStream()), 8)

                    val otherOut = other.getOutputStream()
                    otherOut.write(createWindowRequest(reservedId))
                    otherOut.flush()
                    assertError(other.getInputStream(), error = 14, sequence = 1, opcode = 1, minorOpcode = 0)

                    ownerOut.write(createWindowRequest(reservedId))
                    ownerOut.write(getGeometryRequest(reservedId))
                    ownerOut.flush()
                    val geometry = readReply(owner.getInputStream())
                    assertEquals(3, u16le(geometry, 2))
                    assertEquals(20, u16le(geometry, 16))
                    assertEquals(10, u16le(geometry, 18))
                }
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

    private fun listExtensionsRequest(): ByteArray =
        request(99, 0, ByteArray(0))

    private fun createWindowRequest(id: Int, x: Int = 5, y: Int = 6, width: Int = 20, height: Int = 10): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, id)
        put32le(body, 4, X11Ids.RootWindow)
        put16le(body, 8, x)
        put16le(body, 10, y)
        put16le(body, 12, width)
        put16le(body, 14, height)
        put16le(body, 16, 0)
        put16le(body, 18, XWindowClass.InputOutput)
        put32le(body, 20, X11Ids.RootVisual)
        return request(1, X11Ids.RootDepth, body)
    }

    private fun getGeometryRequest(drawable: Int): ByteArray =
        request(14, 0, u32(drawable))

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
        if (header[0].toInt() != 1) return header
        val extra = u32le(header, 4) * 4
        return header + input.readExactly(extra)
    }

    private fun assertError(input: InputStream, error: Int, sequence: Int, opcode: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
    }

    private fun extensionNames(reply: ByteArray): List<String> {
        val names = mutableListOf<String>()
        var offset = 32
        repeat(reply[1].toInt() and 0xff) {
            val length = reply[offset++].toInt() and 0xff
            names += reply.copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return names
    }

    private fun u16(value: Int): ByteArray =
        ByteArray(2).also { put16le(it, 0, value) }

    private fun u32(value: Int): ByteArray =
        ByteArray(4).also { put32le(it, 0, value) }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

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

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("Unexpected EOF")
            offset += read
        }
        return bytes
    }
}
