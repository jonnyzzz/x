package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class XShmProtocolTest {
    @Test
    fun `MIT-SHM exposes query-only version metadata`() {
        withServer { socket, port ->
            socket.getOutputStream().write(queryExtensionRequest("MIT-SHM"))
            socket.getOutputStream().write(request(XShm.MajorOpcode, XShm.QueryVersion, ByteArray(0)))
            socket.getOutputStream().flush()

            val extension = readReply(socket.getInputStream())
            assertEquals(1, extension[8].toInt())
            assertEquals(XShm.MajorOpcode, extension[9].toInt() and 0xff)
            assertEquals(XShm.FirstEvent, extension[10].toInt() and 0xff)
            assertEquals(XShm.FirstError, extension[11].toInt() and 0xff)

            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(0, version[1].toInt() and 0xff, "shared pixmaps must not be advertised before shm pixmap backing exists")
            assertEquals(XShm.MajorVersion, u16le(version, 8))
            assertEquals(XShm.MinorVersion, u16le(version, 10))
            assertEquals(0, u16le(version, 12))
            assertEquals(0, u16le(version, 14))
            assertEquals(XShm.ZPixmap, version[16].toInt() and 0xff)

            assertContains(httpGet(port, "/text.txt"), "MIT-SHM supported=true")
        }
    }

    @Test
    fun `MIT-SHM QueryVersion validates empty request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XShm.MajorOpcode, XShm.QueryVersion, u32(0)))
            out.write(request(XShm.MajorOpcode, XShm.QueryVersion, ByteArray(0)))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XShm.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XShm.QueryVersion)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(XShm.MajorVersion, u16le(version, 8))
            assertEquals(XShm.MinorVersion, u16le(version, 10))
        }
    }

    @Test
    fun `MIT-SHM Attach validates framing and reports unavailable backing without recording unsupported request`() {
        withServer { socket, port ->
            val invalidBool = shmAttachBody(0x0020_0400, shmid = 1, readOnly = 2)
            val attachBody = shmAttachBody(0x0020_0401, shmid = 2, readOnly = 1)
            val oversizedAttach = shmAttachBody(0x0020_0402, shmid = 3, readOnly = 0) + u32(0)
            val out = socket.getOutputStream()
            out.write(request(XShm.MajorOpcode, XShm.Attach, ByteArray(8)))
            out.write(request(XShm.MajorOpcode, XShm.Attach, oversizedAttach))
            out.write(request(XShm.MajorOpcode, XShm.Attach, shmAttachBody(X11Ids.RootWindow, shmid = 1, readOnly = 0)))
            out.write(request(XShm.MajorOpcode, XShm.Attach, invalidBool))
            out.write(request(XShm.MajorOpcode, XShm.Attach, attachBody))
            out.write(request(XShm.MajorOpcode, XShm.QueryVersion, ByteArray(0)))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XShm.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XShm.Attach)
            assertError(socket.getInputStream(), error = 16, opcode = XShm.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XShm.Attach)
            assertError(socket.getInputStream(), error = 14, opcode = XShm.MajorOpcode, badValue = X11Ids.RootWindow, sequence = 3, minorOpcode = XShm.Attach)
            assertError(socket.getInputStream(), error = 2, opcode = XShm.MajorOpcode, badValue = 2, sequence = 4, minorOpcode = XShm.Attach)
            assertError(socket.getInputStream(), error = 10, opcode = XShm.MajorOpcode, badValue = 2, sequence = 5, minorOpcode = XShm.Attach)
            val version = readReply(socket.getInputStream())
            assertEquals(6, u16le(version, 2))
            assertEquals(XShm.MajorVersion, u16le(version, 8))
            val unsupportedSection = httpGet(port, "/text.txt").substringAfter("Unsupported requests:")
            assertContains(unsupportedSection, "- None.")
            assertFalse(unsupportedSection.contains("MIT-SHM.Attach:"))
        }
    }

    @Test
    fun `MIT-SHM Detach validates framing and reports unknown segment as BadSeg`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XShm.MajorOpcode, XShm.Detach, ByteArray(0)))
            out.write(request(XShm.MajorOpcode, XShm.Detach, u32(0x0020_0400) + u32(0)))
            out.write(request(XShm.MajorOpcode, XShm.Detach, u32(0x0020_0401)))
            out.write(request(XShm.MajorOpcode, XShm.QueryVersion, ByteArray(0)))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XShm.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XShm.Detach)
            assertError(socket.getInputStream(), error = 16, opcode = XShm.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XShm.Detach)
            assertError(socket.getInputStream(), error = XShm.BadSeg, opcode = XShm.MajorOpcode, badValue = 0x0020_0401, sequence = 3, minorOpcode = XShm.Detach)
            val version = readReply(socket.getInputStream())
            assertEquals(4, u16le(version, 2))
            assertEquals(XShm.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `MIT-SHM shared memory image requests remain BadImplementation and recover stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(request(XShm.MajorOpcode, 3, ByteArray(40)))
            out.write(request(XShm.MajorOpcode, 4, ByteArray(24)))
            out.write(request(XShm.MajorOpcode, XShm.QueryVersion, ByteArray(0)))
            out.flush()

            assertError(socket.getInputStream(), error = 17, opcode = XShm.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = 3)
            assertError(socket.getInputStream(), error = 17, opcode = XShm.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = 4)
            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(XShm.MajorVersion, u16le(version, 8))
            assertContains(httpGet(port, "/text.txt"), "MIT-SHM.PutImage:")
            assertContains(httpGet(port, "/text.txt"), "MIT-SHM.GetImage:")
        }
    }

    private fun withServer(block: (Socket, Int) -> Unit) {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                block(socket, server.localPort)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16le(prefix, 6) * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(4 + ((nameBytes.size + 3) and -4))
        put16le(body, 0, nameBytes.size)
        nameBytes.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun shmAttachBody(shmseg: Int, shmid: Int, readOnly: Int): ByteArray =
        ByteArray(12).also {
            put32le(it, 0, shmseg)
            put32le(it, 4, shmid)
            it[8] = readOnly.toByte()
        }

    private fun assertError(input: InputStream, error: Int, opcode: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payloadUnits = u32le(header, 4)
        return header + input.readExactly(payloadUnits * 4)
    }

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
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

    private fun u32(value: Int): ByteArray {
        val bytes = ByteArray(4)
        put32le(bytes, 0, value)
        return bytes
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
