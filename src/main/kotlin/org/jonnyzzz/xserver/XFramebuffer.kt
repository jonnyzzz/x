package org.jonnyzzz.xserver

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

    private var pixels: IntArray = IntArray(this.width * this.height) { opaque(backgroundPixel) }
    private var painted: Boolean = painted
    private var cachedDataUri: String? = null

    fun resize(width: Int, height: Int, backgroundPixel: Int) {
        val (newWidth, newHeight) = framebufferSize(width, height)
        if (newWidth == this.width && newHeight == this.height) return

        val oldPixels = pixels
        val oldWidth = this.width
        val oldHeight = this.height
        val newPixels = IntArray(newWidth * newHeight) { opaque(backgroundPixel) }
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

    fun fill(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pixel: Int,
        preserveAlpha: Boolean = false,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean {
        val bounds = clippedBounds(x, y, width, height) ?: return false
        val color = if (preserveAlpha) pixel else opaque(pixel)
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val offset = row * this.width
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles)) continue
                pixels[offset + column] = color
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    fun drawPoint(
        x: Int,
        y: Int,
        pixel: Int,
        lineWidth: Int = 1,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean {
        val size = lineWidth.coerceAtLeast(1)
        val radiusBefore = (size - 1) / 2
        return fill(
            x = x - radiusBefore,
            y = y - radiusBefore,
            width = size,
            height = size,
            pixel = pixel,
            clipRectangles = clipRectangles,
        )
    }

    fun drawLine(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        pixel: Int,
        lineWidth: Int = 1,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean {
        var x = x1
        var y = y1
        val dx = kotlin.math.abs(x2 - x1)
        val dy = kotlin.math.abs(y2 - y1)
        val stepX = if (x1 < x2) 1 else -1
        val stepY = if (y1 < y2) 1 else -1
        var error = dx - dy
        var painted = false

        while (true) {
            painted = drawPoint(x, y, pixel, lineWidth, clipRectangles) || painted
            if (x == x2 && y == y2) break
            val twiceError = error * 2
            if (twiceError > -dy) {
                error -= dy
                x += stepX
            }
            if (twiceError < dx) {
                error += dx
                y += stepY
            }
        }
        return painted
    }

    fun drawRectangleOutline(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pixel: Int,
        lineWidth: Int = 1,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean {
        if (width < 0 || height < 0) return false
        var painted = false
        val right = x + width
        val bottom = y + height
        painted = drawLine(x, y, right, y, pixel, lineWidth, clipRectangles) || painted
        if (height > 0) painted = drawLine(x, bottom, right, bottom, pixel, lineWidth, clipRectangles) || painted
        if (height > 1) {
            painted = drawLine(x, y + 1, x, bottom - 1, pixel, lineWidth, clipRectangles) || painted
            if (width > 0) painted = drawLine(right, y + 1, right, bottom - 1, pixel, lineWidth, clipRectangles) || painted
        }
        return painted
    }

    fun fillPolygon(
        points: List<XPoint>,
        pixel: Int,
        fillRule: Int = XGraphicsContext.EvenOddRule,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean {
        if (points.size < 3) return false
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val color = opaque(pixel)
        var painted = false
        for (y in minY until maxY) {
            val scanY = y + 0.5
            val intersections = mutableListOf<Double>()
            for (index in points.indices) {
                val start = points[index]
                val end = points[(index + 1) % points.size]
                if (start.y == end.y) continue
                val low = minOf(start.y, end.y)
                val high = maxOf(start.y, end.y)
                if (scanY < low || scanY >= high) continue
                val t = (scanY - start.y) / (end.y - start.y).toDouble()
                intersections += start.x + t * (end.x - start.x)
            }
            intersections.sort()
            if (fillRule == XGraphicsContext.WindingRule) {
                painted = fillWindingScanline(y, points, scanY, color, clipRectangles) || painted
            } else {
                var index = 0
                while (index + 1 < intersections.size) {
                    painted = fillScanlineSpan(y, intersections[index], intersections[index + 1], color, clipRectangles) || painted
                    index += 2
                }
            }
        }
        if (painted) markPainted()
        return painted
    }

    fun drawArc(
        arc: XArcCommand,
        pixel: Int,
        lineWidth: Int = 1,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean {
        val points = sampledArcPoints(arc)
        if (points.isEmpty()) return false
        var painted = false
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            painted = drawLine(start.x, start.y, end.x, end.y, pixel, lineWidth, clipRectangles) || painted
        }
        if (points.size == 1) {
            painted = drawPoint(points[0].x, points[0].y, pixel, lineWidth, clipRectangles) || painted
        }
        return painted
    }

    fun fillArc(
        arc: XArcCommand,
        pixel: Int,
        arcMode: Int,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean {
        if (arc.width <= 0 || arc.height <= 0 || arc.angle2 == 0) return false
        if (abs(arc.angle2) >= FullCircleAngle) {
            return fillEllipse(arc, pixel, clipRectangles)
        }

        val arcPoints = sampledArcPoints(arc)
        if (arcPoints.size < 2) return false
        val polygon = if (arcMode == ArcChord) {
            arcPoints
        } else {
            listOf(XPoint(arc.centerX().roundToInt(), arc.centerY().roundToInt())) + arcPoints
        }
        return fillPolygon(polygon, pixel, clipRectangles = clipRectangles)
    }

    fun blendSolidOver(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBounds(bounds, clipRectangles) { x, y ->
            val maskAlpha = mask?.alphaAt(maskX + x - destinationX, maskY + y - destinationY) ?: 255
            over(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun copyFrom(
        source: XFramebuffer,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        operation: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
    ): XImagePixels? {
        val bounds = clippedCopyBounds(
            sourceWidth = source.width,
            sourceHeight = source.height,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
        ) ?: return null

        val copied = IntArray(bounds.width * bounds.height)
        var painted = false
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val sx = bounds.sourceX + column
                val sy = bounds.sourceY + row
                val dx = bounds.destinationX + column
                val dy = bounds.destinationY + row
                val sourcePixel = source.pixels[sy * source.width + sx]
                copied[row * bounds.width + column] = sourcePixel
                if (!insideClip(dx, dy, clipRectangles)) continue
                val index = dy * this.width + dx
                val maskAlpha = mask?.alphaAt(maskX + dx - destinationX, maskY + dy - destinationY) ?: 255
                pixels[index] = when (operation) {
                    XRender.OpClear -> 0
                    XRender.OpSrc -> withMask(sourcePixel, maskAlpha)
                    XRender.OpOver -> over(sourcePixel, pixels[index], maskAlpha)
                    else -> over(sourcePixel, pixels[index], maskAlpha)
                }
                painted = true
            }
        }
        if (painted) markPainted()
        return XImagePixels(bounds.width, bounds.height, copied)
    }

    fun putImage(x: Int, y: Int, image: XImagePixels, clipRectangles: List<XRectangleCommand>? = null): Boolean {
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

        var painted = false
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val dx = bounds.destinationX + column
                val dy = bounds.destinationY + row
                if (!insideClip(dx, dy, clipRectangles)) continue
                pixels[dy * this.width + dx] = image.pixels[(bounds.sourceY + row) * image.width + bounds.sourceX + column]
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    fun copyAreaTo(
        destination: XFramebuffer,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
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
        var painted = false
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val dx = bounds.destinationX + column
                val dy = bounds.destinationY + row
                if (!insideClip(dx, dy, clipRectangles)) continue
                destination.pixels[dy * destination.width + dx] = copied[row * bounds.width + column]
                painted = true
            }
        }
        if (painted) destination.markPainted()
        return XImagePixels(bounds.width, bounds.height, copied)
    }

    fun copyPlaneTo(
        destination: XFramebuffer,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        bitPlane: Int,
        foreground: Int,
        background: Int,
        clipRectangles: List<XRectangleCommand>? = null,
    ): XImagePixels? {
        if (bitPlane == 0 || bitPlane.countOneBits() != 1) return null
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
        val foregroundPixel = opaque(foreground)
        val backgroundPixel = opaque(background)
        var painted = false
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val sourcePixel = pixels[(bounds.sourceY + row) * this.width + bounds.sourceX + column]
                val targetPixel = if ((sourcePixel and bitPlane) != 0) foregroundPixel else backgroundPixel
                copied[row * bounds.width + column] = targetPixel
                val dx = bounds.destinationX + column
                val dy = bounds.destinationY + row
                if (!insideClip(dx, dy, clipRectangles)) continue
                destination.pixels[dy * destination.width + dx] = targetPixel
                painted = true
            }
        }
        if (painted) destination.markPainted()
        return XImagePixels(bounds.width, bounds.height, copied)
    }

    fun pixelAt(x: Int, y: Int): Int? =
        if (x in 0 until width && y in 0 until height) {
            pixels[y * width + x]
        } else {
            null
        }

    fun alphaAt(x: Int, y: Int): Int =
        pixelAt(x, y)?.let { (it ushr 24) and 0xff } ?: 0

    fun hasPaintedContent(): Boolean = painted

    fun firstPaintedPixel(): Int? {
        if (!painted) return null
        return pixels.firstOrNull { ((it ushr 24) and 0xff) > 0 }
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

    private fun compositeBounds(
        bounds: CopyBounds,
        clipRectangles: List<XRectangleCommand>?,
        compose: (x: Int, y: Int) -> Int,
    ): Boolean {
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val offset = row * this.width
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles)) continue
                pixels[offset + column] = compose(column, row)
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    private fun fillEllipse(
        arc: XArcCommand,
        pixel: Int,
        clipRectangles: List<XRectangleCommand>?,
    ): Boolean {
        val bounds = clippedBounds(arc.x, arc.y, arc.width, arc.height) ?: return false
        val radiusX = arc.width / 2.0
        val radiusY = arc.height / 2.0
        if (radiusX <= 0.0 || radiusY <= 0.0) return false
        val centerX = arc.centerX()
        val centerY = arc.centerY()
        val color = opaque(pixel)
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val normalizedY = ((row + 0.5) - centerY) / radiusY
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles)) continue
                val normalizedX = ((column + 0.5) - centerX) / radiusX
                if (normalizedX * normalizedX + normalizedY * normalizedY > 1.0) continue
                pixels[row * width + column] = color
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    private fun sampledArcPoints(arc: XArcCommand): List<XPoint> {
        if (arc.width <= 0 || arc.height <= 0 || arc.angle2 == 0) return emptyList()
        val radiusX = arc.width / 2.0
        val radiusY = arc.height / 2.0
        if (radiusX <= 0.0 || radiusY <= 0.0) return emptyList()
        val start = arc.angle1 / 64.0
        val extent = arc.angle2.coerceIn(-FullCircleAngle, FullCircleAngle) / 64.0
        val steps = ceil(maxOf(radiusX, radiusY) * abs(extent) / 90.0).roundToInt().coerceAtLeast(1)
        val centerX = arc.centerX()
        val centerY = arc.centerY()
        val points = ArrayList<XPoint>(steps + 1)
        for (index in 0..steps) {
            val angle = start + extent * index / steps
            val radians = angle * PI / 180.0
            val point = XPoint(
                x = (centerX + radiusX * cos(radians)).roundToInt(),
                y = (centerY - radiusY * sin(radians)).roundToInt(),
            )
            if (points.lastOrNull() != point) points += point
        }
        return points
    }

    private fun fillWindingScanline(
        y: Int,
        points: List<XPoint>,
        scanY: Double,
        color: Int,
        clipRectangles: List<XRectangleCommand>?,
    ): Boolean {
        val events = mutableListOf<Pair<Double, Int>>()
        for (index in points.indices) {
            val start = points[index]
            val end = points[(index + 1) % points.size]
            if (start.y == end.y) continue
            val low = minOf(start.y, end.y)
            val high = maxOf(start.y, end.y)
            if (scanY < low || scanY >= high) continue
            val t = (scanY - start.y) / (end.y - start.y).toDouble()
            val x = start.x + t * (end.x - start.x)
            val direction = if (end.y > start.y) 1 else -1
            events += x to direction
        }
        events.sortBy { it.first }
        var painted = false
        var winding = 0
        var previousX: Double? = null
        var index = 0
        while (index < events.size) {
            val x = events[index].first
            previousX?.let { previous ->
                if (winding != 0) {
                    painted = fillScanlineSpan(y, previous, x, color, clipRectangles) || painted
                }
            }
            while (index < events.size && events[index].first == x) {
                winding += events[index].second
                index += 1
            }
            previousX = x
        }
        return painted
    }

    private fun fillScanlineSpan(
        y: Int,
        leftIntersection: Double,
        rightIntersection: Double,
        color: Int,
        clipRectangles: List<XRectangleCommand>?,
    ): Boolean {
        val left = ceil(leftIntersection).toInt()
        val right = ceil(rightIntersection).toInt()
        var painted = false
        for (x in left until right) {
            if (x !in 0 until width || y !in 0 until height) continue
            if (!insideClip(x, y, clipRectangles)) continue
            pixels[y * width + x] = color
            painted = true
        }
        return painted
    }

    private fun insideClip(x: Int, y: Int, clipRectangles: List<XRectangleCommand>?): Boolean =
        clipRectangles == null || clipRectangles.any { rectangle ->
            x >= rectangle.x &&
                y >= rectangle.y &&
                x < rectangle.x + rectangle.width &&
                y < rectangle.y + rectangle.height
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
        private const val FullCircleAngle = 360 * 64
        private const val ArcChord = 0

        private fun framebufferSize(width: Int, height: Int): Pair<Int, Int> {
            val safeWidth = width.coerceAtLeast(0)
            val safeHeight = height.coerceAtLeast(0)
            if (safeWidth == 0 || safeHeight == 0) return 0 to 0
            val pixels = safeWidth.toLong() * safeHeight.toLong()
            if (pixels > MaxPixels) return 0 to 0
            return safeWidth to safeHeight
        }

        fun opaque(pixel: Int): Int = 0xff00_0000.toInt() or (pixel and 0x00ff_ffff)

        fun argb(pixel: Int): Int = pixel

        fun over(source: Int, destination: Int, maskAlpha: Int = 255): Int {
            val sourceAlpha = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
            if (sourceAlpha <= 0) return destination
            if (sourceAlpha >= 255) return source or 0xff00_0000.toInt()
            val inverse = 255 - sourceAlpha
            val destinationAlpha = (destination ushr 24) and 0xff
            val outAlpha = sourceAlpha + (destinationAlpha * inverse + 127) / 255
            fun channel(shift: Int): Int {
                val sourceChannel = (source ushr shift) and 0xff
                val destinationChannel = (destination ushr shift) and 0xff
                return (sourceChannel * sourceAlpha + destinationChannel * inverse + 127) / 255
            }
            return (outAlpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        fun withMask(source: Int, maskAlpha: Int): Int {
            if (maskAlpha >= 255) return source
            val alpha = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
            return (alpha shl 24) or (source and 0x00ff_ffff)
        }

        fun imageDataUri(image: XImagePixels): String {
            val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
            val output = ByteArrayOutputStream()
            ImageIO.write(buffered, "png", output)
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
        }
    }
}

private fun XArcCommand.centerX(): Double = x + width / 2.0

private fun XArcCommand.centerY(): Double = y + height / 2.0

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
