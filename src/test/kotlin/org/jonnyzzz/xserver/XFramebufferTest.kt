package org.jonnyzzz.xserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XFramebufferTest {
    @Test
    fun `copy area clips source and destination while preserving pixel contents`() {
        val source = XFramebuffer(4, 3)
        val destination = XFramebuffer(3, 3)
        val image = XImagePixels(
            width = 4,
            height = 3,
            pixels = intArrayOf(
                XFramebuffer.argb(0x0000_0001),
                XFramebuffer.argb(0x0000_0002),
                XFramebuffer.argb(0x0000_0003),
                XFramebuffer.argb(0x0000_0004),
                XFramebuffer.argb(0x0000_0005),
                XFramebuffer.argb(0x0000_0006),
                XFramebuffer.argb(0x0000_0007),
                XFramebuffer.argb(0x0000_0008),
                XFramebuffer.argb(0x0000_0009),
                XFramebuffer.argb(0x0000_000a),
                XFramebuffer.argb(0x0000_000b),
                XFramebuffer.argb(0x0000_000c),
            ),
        )
        source.putImage(0, 0, image)

        val copied = source.copyAreaTo(
            destination = destination,
            sourceX = 1,
            sourceY = 1,
            destinationX = 0,
            destinationY = 0,
            width = 3,
            height = 2,
        )

        assertNotNull(copied)
        assertEquals(3, copied.width)
        assertEquals(2, copied.height)
        assertEquals(XFramebuffer.argb(0x0000_0006), destination.pixelAt(0, 0))
        assertEquals(XFramebuffer.argb(0x0000_0007), destination.pixelAt(1, 0))
        assertEquals(XFramebuffer.argb(0x0000_0008), destination.pixelAt(2, 0))
        assertEquals(XFramebuffer.argb(0x0000_000a), destination.pixelAt(0, 1))
        assertEquals(XFramebuffer.argb(0x0000_000b), destination.pixelAt(1, 1))
        assertEquals(XFramebuffer.argb(0x0000_000c), destination.pixelAt(2, 1))
    }

    @Test
    fun `empty framebuffers do not emit an image`() {
        assertNull(XFramebuffer(2, 2).toDataUri())
    }
}
