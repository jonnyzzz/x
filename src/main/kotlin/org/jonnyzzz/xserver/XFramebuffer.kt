package org.jonnyzzz.xserver

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

internal class XFramebuffer(
    width: Int,
    height: Int,
    backgroundPixel: Int = 0,
    painted: Boolean = false,
) {
    private val initialSize = framebufferSize(width, height)

    var width: Int = initialSize.first
        private set
    var height: Int = initialSize.second
        private set

    private var pixels: IntArray = IntArray(this.width * this.height) { argb(backgroundPixel) }
    private var painted: Boolean = painted
    private var cachedDataUri: String? = null

    fun resize(width: Int, height: Int, backgroundPixel: Int) {
        val (newWidth, newHeight) = framebufferSize(width, height)
        if (newWidth == this.width && newHeight == this.height) return

        val oldPixels = pixels
        val oldWidth = this.width
        val oldHeight = this.height
        val newPixels = IntArray(newWidth * newHeight) { argb(backgroundPixel) }
        val copyWidth = minOf(oldWidth, newWidth)
        val copyHeight = minOf(oldHeight, newHeight)
        for (y in 0 until copyHeight) {
            oldPixels.copyInto(
                destination = newPixels,
                destinationOffset = y * newWidth,
                startIndex = y * oldWidth,
                endIndex = y * oldWidth + copyWidth,
            )
        }

        this.width = newWidth
        this.height = newHeight
        pixels = newPixels
        invalidate()
    }

    fun fill(x: Int, y: Int, width: Int, height: Int, pixel: Int): Boolean {
        val bounds = clippedBounds(x, y, width, height) ?: return false
        val color = argb(pixel)
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val offset = row * this.width
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                pixels[offset + column] = color
            }
        }
        markPainted()
        return true
    }

    fun putImage(x: Int, y: Int, image: XImagePixels): Boolean {
        val bounds = clippedCopyBounds(
            sourceWidth = image.width,
            sourceHeight = image.height,
            sourceX = 0,
            sourceY = 0,
            destinationX = x,
            destinationY = y,
            width = image.width,
            height = image.height,
        ) ?: return false

        for (row in 0 until bounds.height) {
            image.pixels.copyInto(
                destination = pixels,
                destinationOffset = (bounds.destinationY + row) * this.width + bounds.destinationX,
                startIndex = (bounds.sourceY + row) * image.width + bounds.sourceX,
                endIndex = (bounds.sourceY + row) * image.width + bounds.sourceX + bounds.width,
            )
        }
        markPainted()
        return true
    }

    fun copyAreaTo(
        destination: XFramebuffer,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
    ): XImagePixels? {
        val bounds = destination.clippedCopyBounds(
            sourceWidth = this.width,
            sourceHeight = this.height,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
        ) ?: return null

        val copied = IntArray(bounds.width * bounds.height)
        for (row in 0 until bounds.height) {
            pixels.copyInto(
                destination = copied,
                destinationOffset = row * bounds.width,
                startIndex = (bounds.sourceY + row) * this.width + bounds.sourceX,
                endIndex = (bounds.sourceY + row) * this.width + bounds.sourceX + bounds.width,
            )
        }
        for (row in 0 until bounds.height) {
            copied.copyInto(
                destination = destination.pixels,
                destinationOffset = (bounds.destinationY + row) * destination.width + bounds.destinationX,
                startIndex = row * bounds.width,
                endIndex = row * bounds.width + bounds.width,
            )
        }
        destination.markPainted()
        return XImagePixels(bounds.width, bounds.height, copied)
    }

    fun pixelAt(x: Int, y: Int): Int? =
        if (x in 0 until width && y in 0 until height) {
            pixels[y * width + x]
        } else {
            null
        }

    fun toDataUri(): String? {
        if (!painted || width <= 0 || height <= 0) return null
        cachedDataUri?.let { return it }
        return imageDataUri(XImagePixels(width, height, pixels.copyOf())).also {
            cachedDataUri = it
        }
    }

    private fun clippedBounds(x: Int, y: Int, width: Int, height: Int): CopyBounds? {
        val right = minOf(this.width, x + width)
        val bottom = minOf(this.height, y + height)
        val left = maxOf(0, x)
        val top = maxOf(0, y)
        if (right <= left || bottom <= top) return null
        return CopyBounds(
            sourceX = 0,
            sourceY = 0,
            destinationX = left,
            destinationY = top,
            width = right - left,
            height = bottom - top,
        )
    }

    private fun clippedCopyBounds(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
    ): CopyBounds? {
        var sx = sourceX
        var sy = sourceY
        var dx = destinationX
        var dy = destinationY
        var w = width
        var h = height

        if (w <= 0 || h <= 0 || sourceWidth <= 0 || sourceHeight <= 0 || this.width <= 0 || this.height <= 0) return null
        if (sx < 0) {
            dx -= sx
            w += sx
            sx = 0
        }
        if (sy < 0) {
            dy -= sy
            h += sy
            sy = 0
        }
        if (dx < 0) {
            sx -= dx
            w += dx
            dx = 0
        }
        if (dy < 0) {
            sy -= dy
            h += dy
            dy = 0
        }

        w = minOf(w, sourceWidth - sx, this.width - dx)
        h = minOf(h, sourceHeight - sy, this.height - dy)
        if (w <= 0 || h <= 0) return null

        return CopyBounds(sx, sy, dx, dy, w, h)
    }

    private fun markPainted() {
        painted = true
        invalidate()
    }

    private fun invalidate() {
        cachedDataUri = null
    }

    private data class CopyBounds(
        val sourceX: Int,
        val sourceY: Int,
        val destinationX: Int,
        val destinationY: Int,
        val width: Int,
        val height: Int,
    )

    companion object {
        private const val MaxPixels = 16_777_216

        private fun framebufferSize(width: Int, height: Int): Pair<Int, Int> {
            val safeWidth = width.coerceAtLeast(0)
            val safeHeight = height.coerceAtLeast(0)
            if (safeWidth == 0 || safeHeight == 0) return 0 to 0
            val pixels = safeWidth.toLong() * safeHeight.toLong()
            if (pixels > MaxPixels) return 0 to 0
            return safeWidth to safeHeight
        }

        fun argb(pixel: Int): Int = 0xff00_0000.toInt() or (pixel and 0x00ff_ffff)

        fun imageDataUri(image: XImagePixels): String {
            val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
            val output = ByteArrayOutputStream()
            ImageIO.write(buffered, "png", output)
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
        }
    }
}

internal data class XImagePixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is XImagePixels &&
            width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}
