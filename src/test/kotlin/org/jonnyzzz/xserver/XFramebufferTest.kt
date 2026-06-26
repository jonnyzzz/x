package org.jonnyzzz.xserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XFramebufferTest {
    @Test
    fun `core raster operations apply all GX functions and plane masks`() {
        val source = 0x00f0_0f0f
        val destination = 0x0055_aa33
        val functions = listOf(
            XGraphicsContext.GXclear to 0,
            XGraphicsContext.GXand to (source and destination),
            XGraphicsContext.GXandReverse to (source and destination.inv()),
            XGraphicsContext.GXcopy to source,
            XGraphicsContext.GXandInverted to (source.inv() and destination),
            XGraphicsContext.GXnoop to destination,
            XGraphicsContext.GXxor to (source xor destination),
            XGraphicsContext.GXor to (source or destination),
            XGraphicsContext.GXnor to (source or destination).inv(),
            XGraphicsContext.GXequiv to (source xor destination).inv(),
            XGraphicsContext.GXinvert to destination.inv(),
            XGraphicsContext.GXorReverse to (source or destination.inv()),
            XGraphicsContext.GXcopyInverted to source.inv(),
            XGraphicsContext.GXorInverted to (source.inv() or destination),
            XGraphicsContext.GXnand to (source and destination).inv(),
            XGraphicsContext.GXset to 0x00ff_ffff,
        )

        for ((function, expected) in functions) {
            val framebuffer = XFramebuffer(1, 1)
            framebuffer.putImage(0, 0, XImagePixels(1, 1, intArrayOf(XFramebuffer.opaque(destination))))

            framebuffer.fill(0, 0, 1, 1, source, function = function)

            assertEquals(XFramebuffer.opaque(expected), framebuffer.pixelAt(0, 0), "function=$function")
        }

        val masked = XFramebuffer(1, 1)
        masked.putImage(0, 0, XImagePixels(1, 1, intArrayOf(XFramebuffer.opaque(destination))))
        masked.fill(0, 0, 1, 1, source, function = XGraphicsContext.GXxor, planeMask = 0x0000_ff00)

        val expectedMasked = (destination and 0x00ff_00ff) or ((source xor destination) and 0x0000_ff00)
        assertEquals(XFramebuffer.opaque(expectedMasked), masked.pixelAt(0, 0))
    }

    @Test
    fun `core raster no-op and empty plane mask preserve destination alpha`() {
        val noop = XFramebuffer(1, 1)
        noop.putImage(0, 0, XImagePixels(1, 1, intArrayOf(XFramebuffer.argb(0x8001_0203.toInt()))))

        noop.fill(0, 0, 1, 1, 0x00f0_0f0f, function = XGraphicsContext.GXnoop)

        assertEquals(XFramebuffer.argb(0x8001_0203.toInt()), noop.pixelAt(0, 0))

        val masked = XFramebuffer(1, 1)
        masked.putImage(0, 0, XImagePixels(1, 1, intArrayOf(XFramebuffer.argb(0x4004_0506))))

        masked.fill(0, 0, 1, 1, 0x00f0_0f0f, function = XGraphicsContext.GXxor, planeMask = 0)

        assertEquals(XFramebuffer.argb(0x4004_0506), masked.pixelAt(0, 0))
    }

    @Test
    fun `fully clipped fill returns false after previous painting`() {
        val framebuffer = XFramebuffer(1, 1)
        assertEquals(true, framebuffer.fill(0, 0, 1, 1, 0x0000_ff00))

        val painted = framebuffer.fill(
            0,
            0,
            1,
            1,
            0x00ff_0000,
            clipRectangles = emptyList(),
        )

        assertEquals(false, painted)
        assertEquals(XFramebuffer.opaque(0x0000_ff00), framebuffer.pixelAt(0, 0))
    }

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
    fun `copy plane snapshots overlapping source before writing destination`() {
        val framebuffer = XFramebuffer(3, 1)
        framebuffer.putImage(
            0,
            0,
            XImagePixels(
                3,
                1,
                intArrayOf(
                    XFramebuffer.opaque(0x0001_0000),
                    XFramebuffer.opaque(0),
                    XFramebuffer.opaque(0),
                ),
            ),
        )

        framebuffer.copyPlaneTo(
            destination = framebuffer,
            sourceX = 0,
            sourceY = 0,
            destinationX = 1,
            destinationY = 0,
            width = 2,
            height = 1,
            bitPlane = 0x0001_0000,
            foreground = 0x0001_0000,
            background = 0,
        )

        assertEquals(XFramebuffer.opaque(0x0001_0000), framebuffer.pixelAt(1, 0))
        assertEquals(XFramebuffer.opaque(0), framebuffer.pixelAt(2, 0))
    }

    @Test
    fun `empty framebuffers do not emit an image`() {
        assertNull(XFramebuffer(2, 2).toDataUri())
    }
}
