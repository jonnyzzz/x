package org.jonnyzzz.xserver

internal enum class ByteOrder {
    LsbFirst,
    MsbFirst;

    fun u16(bytes: ByteArray, offset: Int): Int =
        when (this) {
            LsbFirst -> (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            MsbFirst -> ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        }

    fun i16(bytes: ByteArray, offset: Int): Int {
        val value = u16(bytes, offset)
        return if ((value and 0x8000) == 0) value else value - 0x10000
    }

    fun u32(bytes: ByteArray, offset: Int): Int =
        when (this) {
            LsbFirst -> (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

            MsbFirst -> ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        }

    fun put16(bytes: ByteArray, offset: Int, value: Int) {
        when (this) {
            LsbFirst -> {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
            }
            MsbFirst -> {
                bytes[offset] = (value ushr 8).toByte()
                bytes[offset + 1] = value.toByte()
            }
        }
    }

    fun put32(bytes: ByteArray, offset: Int, value: Int) {
        when (this) {
            LsbFirst -> {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
                bytes[offset + 2] = (value ushr 16).toByte()
                bytes[offset + 3] = (value ushr 24).toByte()
            }
            MsbFirst -> {
                bytes[offset] = (value ushr 24).toByte()
                bytes[offset + 1] = (value ushr 16).toByte()
                bytes[offset + 2] = (value ushr 8).toByte()
                bytes[offset + 3] = value.toByte()
            }
        }
    }

    companion object {
        fun fromSetupByte(value: Int): ByteOrder =
            when (value) {
                0x6c -> LsbFirst
                0x42 -> MsbFirst
                else -> throw IllegalArgumentException("Unsupported X11 byte order byte: 0x${value.toString(16)}")
            }
    }
}

internal object SetupReply {
    private const val ReleaseNumber = 1
    private const val ResourceIdBase = 0x0020_0000
    private const val ResourceIdMask = 0x001f_ffff
    private const val MotionBufferSize = 256
    private const val RootWindowId = X11Ids.RootWindow
    private const val DefaultColormapId = X11Ids.DefaultColormap
    private const val WhitePixel = 0x00ff_ffff
    private const val BlackPixel = 0x0000_0000
    private const val RootVisualId = X11Ids.RootVisual

    fun success(
        byteOrder: ByteOrder,
        clientMajor: Int,
        clientMinor: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val vendor = "jonnyzzz/x".encodeToByteArray()
        val vendorPaddedLength = paddedLength(vendor.size)
        val pixmapFormatsLength = 1
        val screensLength = 1
        val depthsLength = 1
        val visualsLength = 1
        val screenBytes = 40 + 8 + 24
        val additionalLength = 32 + vendorPaddedLength + 8 + screenBytes
        val reply = ByteArray(8 + additionalLength)

        reply[0] = 1
        byteOrder.put16(reply, 2, clientMajor.coerceAtLeast(11))
        byteOrder.put16(reply, 4, clientMinor)
        byteOrder.put16(reply, 6, additionalLength / 4)
        byteOrder.put32(reply, 8, ReleaseNumber)
        byteOrder.put32(reply, 12, ResourceIdBase)
        byteOrder.put32(reply, 16, ResourceIdMask)
        byteOrder.put32(reply, 20, MotionBufferSize)
        byteOrder.put16(reply, 24, vendor.size)
        byteOrder.put16(reply, 26, 0xffff)
        reply[28] = screensLength.toByte()
        reply[29] = pixmapFormatsLength.toByte()
        reply[30] = 0
        reply[31] = 0
        reply[32] = 32
        reply[33] = 32
        reply[34] = 8
        reply[35] = 255.toByte()
        vendor.copyInto(reply, 40)

        var offset = 40 + vendorPaddedLength
        reply[offset] = 24
        reply[offset + 1] = 32
        reply[offset + 2] = 32
        reply[offset + 3] = 0
        byteOrder.put32(reply, offset + 4, 32)
        offset += 8

        byteOrder.put32(reply, offset, RootWindowId)
        byteOrder.put32(reply, offset + 4, DefaultColormapId)
        byteOrder.put32(reply, offset + 8, WhitePixel)
        byteOrder.put32(reply, offset + 12, BlackPixel)
        byteOrder.put32(reply, offset + 16, 0)
        byteOrder.put16(reply, offset + 20, width)
        byteOrder.put16(reply, offset + 22, height)
        byteOrder.put16(reply, offset + 24, 270)
        byteOrder.put16(reply, offset + 26, 203)
        byteOrder.put16(reply, offset + 28, 1)
        byteOrder.put16(reply, offset + 30, 1)
        byteOrder.put32(reply, offset + 32, RootVisualId)
        reply[offset + 36] = 0
        reply[offset + 37] = 0
        reply[offset + 38] = 24
        reply[offset + 39] = depthsLength.toByte()
        offset += 40

        reply[offset] = 24
        byteOrder.put16(reply, offset + 2, visualsLength)
        offset += 8

        byteOrder.put32(reply, offset, RootVisualId)
        reply[offset + 4] = 4
        reply[offset + 5] = 8
        byteOrder.put16(reply, offset + 6, 256)
        byteOrder.put32(reply, offset + 8, 0x00ff_0000)
        byteOrder.put32(reply, offset + 12, 0x0000_ff00)
        byteOrder.put32(reply, offset + 16, 0x0000_00ff)

        return reply
    }

    private fun paddedLength(length: Int): Int = (length + 3) and -4
}

internal object X11Ids {
    const val RootWindow = 0x0000_0026
    const val DefaultColormap = 0x0000_0027
    const val RootVisual = 0x0000_0028
}
