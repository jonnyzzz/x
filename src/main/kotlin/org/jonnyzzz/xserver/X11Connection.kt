package org.jonnyzzz.xserver

import java.io.EOFException
import java.net.Socket

internal class X11Connection(
    private val socket: Socket,
    private val width: Int,
    private val height: Int,
) {
    fun run() {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val setupPrefix = input.readExactly(12)
        val byteOrder = ByteOrder.fromSetupByte(setupPrefix[0].toInt() and 0xff)
        val major = byteOrder.u16(setupPrefix, 2)
        val minor = byteOrder.u16(setupPrefix, 4)
        val authNameLength = byteOrder.u16(setupPrefix, 6)
        val authDataLength = byteOrder.u16(setupPrefix, 8)

        val authNamePad = paddedLength(authNameLength)
        val authDataPad = paddedLength(authDataLength)
        if (authNamePad > 0) input.readExactly(authNamePad)
        if (authDataPad > 0) input.readExactly(authDataPad)

        val reply = SetupReply.success(
            byteOrder = byteOrder,
            clientMajor = major,
            clientMinor = minor,
            width = width,
            height = height,
        )
        output.write(reply)
        output.flush()

        // Request dispatch starts in the next milestone. Keeping the connection
        // open after setup lets raw protocol tests validate the negotiated state.
        while (input.read() != -1) {
            // Discard for now.
        }
    }

    private fun paddedLength(length: Int): Int = (length + 3) and -4
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
