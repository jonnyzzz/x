package org.jonnyzzz.xserver

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val bounds = clippedBounds(x, y, width, height) ?: return false
        val color = if (preserveAlpha) pixel else opaque(pixel)
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val offset = row * this.width
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles)) continue
                pixels[offset + column] = if (preserveAlpha && function == XGraphicsContext.GXcopy && planeMask == -1) {
                    color
                } else {
                    corePixel(source = color, destination = pixels[offset + column], function = function, planeMask = planeMask)
                }
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
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
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
            function = function,
            planeMask = planeMask,
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
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
        dashPattern: XDashPattern? = null,
        includeFirstPoint: Boolean = true,
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
            val dashPixel = if (dashPattern == null) pixel else dashPattern.pixel()
            if ((includeFirstPoint || x != x1 || y != y1) && dashPixel != null) {
                painted = drawPoint(x, y, dashPixel, lineWidth, clipRectangles, function, planeMask) || painted
            }
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
            dashPattern?.advance()
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
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        if (width < 0 || height < 0) return false
        var painted = false
        val right = x + width
        val bottom = y + height
        painted = drawLine(x, y, right, y, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        if (height > 0) painted = drawLine(x, bottom, right, bottom, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        if (height > 1) {
            painted = drawLine(x, y + 1, x, bottom - 1, pixel, lineWidth, clipRectangles, function, planeMask) || painted
            if (width > 0) painted = drawLine(right, y + 1, right, bottom - 1, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    fun fillPolygon(
        points: List<XPoint>,
        pixel: Int,
        fillRule: Int = XGraphicsContext.EvenOddRule,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
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
                painted = fillWindingScanline(y, points, scanY, color, clipRectangles, function, planeMask) || painted
            } else {
                var index = 0
                while (index + 1 < intersections.size) {
                    painted = fillScanlineSpan(y, intersections[index], intersections[index + 1], color, clipRectangles, function, planeMask) || painted
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
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val points = sampledArcPoints(arc)
        if (points.isEmpty()) return false
        var painted = false
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            painted = drawLine(start.x, start.y, end.x, end.y, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        }
        if (points.size == 1) {
            painted = drawPoint(points[0].x, points[0].y, pixel, lineWidth, clipRectangles, function, planeMask) || painted
        }
        return painted
    }

    fun fillArc(
        arc: XArcCommand,
        pixel: Int,
        arcMode: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        if (arc.width <= 0 || arc.height <= 0 || arc.angle2 == 0) return false
        if (abs(arc.angle2) >= FullCircleAngle) {
            return fillEllipse(arc, pixel, clipRectangles, function, planeMask)
        }

        val arcPoints = sampledArcPoints(arc)
        if (arcPoints.size < 2) return false
        val polygon = if (arcMode == ArcChord) {
            arcPoints
        } else {
            listOf(XPoint(arc.centerX().roundToInt(), arc.centerY().roundToInt())) + arcPoints
        }
        return fillPolygon(polygon, pixel, clipRectangles = clipRectangles, function = function, planeMask = planeMask)
    }

    fun drawText(
        x: Int,
        baselineY: Int,
        text: String,
        foreground: Int,
        background: Int? = null,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        if (text.isEmpty()) return false
        val textWidth = (text.length * TextCellWidth).coerceAtLeast(1)
        val top = baselineY - TextAscent
        var painted = false
        if (background != null) {
            painted = fill(
                x = x,
                y = top,
                width = textWidth,
                height = TextCellHeight,
                pixel = background,
                clipRectangles = clipRectangles,
                function = function,
                planeMask = planeMask,
            ) || painted
        }

        val color = opaque(foreground)
        for (row in 0 until TextCellHeight) {
            val dy = top + row
            if (dy !in 0 until height) continue
            for (column in 0 until textWidth) {
                val dx = x + column
                if (dx !in 0 until width) continue
                if (!insideClip(dx, dy, clipRectangles)) continue
                if (!textPixel(text, column, row)) continue
                val index = dy * width + dx
                pixels[index] = corePixel(source = color, destination = pixels[index], function = function, planeMask = planeMask)
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
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
        maskAlphaAt: ((x: Int, y: Int) -> Int)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBounds(bounds, clipRectangles) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
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
        maskAlphaAt: ((x: Int, y: Int) -> Int)? = null,
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
                val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + dx - destinationX, maskY + dy - destinationY)
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

    fun compositeGenerated(
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
        maskAlphaAt: ((x: Int, y: Int) -> Int)? = null,
        sourcePixelAt: (x: Int, y: Int) -> Int,
    ): XImagePixels? {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return null

        val generated = IntArray(bounds.width * bounds.height)
        var painted = false
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val dx = bounds.destinationX + column
                val dy = bounds.destinationY + row
                val sx = sourceX + dx - destinationX
                val sy = sourceY + dy - destinationY
                val sourcePixel = sourcePixelAt(sx, sy)
                generated[row * bounds.width + column] = sourcePixel
                if (!insideClip(dx, dy, clipRectangles)) continue
                val index = dy * this.width + dx
                val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + dx - destinationX, maskY + dy - destinationY)
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
        return XImagePixels(bounds.width, bounds.height, generated)
    }

    fun putImage(
        x: Int,
        y: Int,
        image: XImagePixels,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
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
                val index = dy * this.width + dx
                val sourcePixel = image.pixels[(bounds.sourceY + row) * image.width + bounds.sourceX + column]
                pixels[index] = if (usesCoreRaster(function, planeMask)) {
                    corePixel(source = sourcePixel, destination = pixels[index], function = function, planeMask = planeMask)
                } else {
                    sourcePixel
                }
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
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
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
                val index = dy * destination.width + dx
                val sourcePixel = copied[row * bounds.width + column]
                destination.pixels[index] = if (usesCoreRaster(function, planeMask)) {
                    corePixel(source = sourcePixel, destination = destination.pixels[index], function = function, planeMask = planeMask)
                } else {
                    sourcePixel
                }
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
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
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
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val sourcePixel = pixels[(bounds.sourceY + row) * this.width + bounds.sourceX + column]
                val targetPixel = if ((sourcePixel and bitPlane) != 0) foregroundPixel else backgroundPixel
                copied[row * bounds.width + column] = targetPixel
            }
        }

        var painted = false
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val targetPixel = copied[row * bounds.width + column]
                val dx = bounds.destinationX + column
                val dy = bounds.destinationY + row
                if (!insideClip(dx, dy, clipRectangles)) continue
                val index = dy * destination.width + dx
                destination.pixels[index] = if (usesCoreRaster(function, planeMask)) {
                    corePixel(source = targetPixel, destination = destination.pixels[index], function = function, planeMask = planeMask)
                } else {
                    targetPixel
                }
                painted = true
            }
        }
        if (painted) destination.markPainted()
        return XImagePixels(bounds.width, bounds.height, copied)
    }

    fun compositeTrapezoids(
        pixel: Int,
        operation: Int,
        trapezoids: List<XTrapezoidCommand>,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean =
        compositeTrapezoids(
            operation = operation,
            trapezoids = trapezoids,
            clipRectangles = clipRectangles,
        ) { _, _ -> pixel }

    fun compositeTrapezoids(
        operation: Int,
        trapezoids: List<XTrapezoidCommand>,
        clipRectangles: List<XRectangleCommand>? = null,
        sourcePixelAt: (x: Int, y: Int) -> Int,
    ): Boolean {
        var painted = false
        for (trapezoid in trapezoids) {
            painted = compositeTrapezoid(operation, trapezoid, clipRectangles, sourcePixelAt) || painted
        }
        if (painted) markPainted()
        return painted
    }

    fun compositeTriangles(
        pixel: Int,
        operation: Int,
        triangles: List<XTriangleCommand>,
        clipRectangles: List<XRectangleCommand>? = null,
    ): Boolean =
        compositeTriangles(
            operation = operation,
            triangles = triangles,
            clipRectangles = clipRectangles,
        ) { _, _ -> pixel }

    fun compositeTriangles(
        operation: Int,
        triangles: List<XTriangleCommand>,
        clipRectangles: List<XRectangleCommand>? = null,
        sourcePixelAt: (x: Int, y: Int) -> Int,
    ): Boolean {
        var painted = false
        for (triangle in triangles) {
            painted = compositeTriangle(operation, triangle, clipRectangles, sourcePixelAt) || painted
        }
        if (painted) markPainted()
        return painted
    }

    fun tileTo(
        destination: XFramebuffer,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (this.width <= 0 || this.height <= 0) return false
        val bounds = destination.clippedBounds(destinationX, destinationY, width, height) ?: return false
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                val sourceX = column.floorMod(this.width)
                val sourceY = row.floorMod(this.height)
                destination.pixels[row * destination.width + column] = pixels[sourceY * this.width + sourceX]
                painted = true
            }
        }
        if (painted) destination.markPainted()
        return painted
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
        function: Int,
        planeMask: Int,
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
                val index = row * width + column
                pixels[index] = corePixel(source = color, destination = pixels[index], function = function, planeMask = planeMask)
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    private fun compositeTrapezoid(
        operation: Int,
        trapezoid: XTrapezoidCommand,
        clipRectangles: List<XRectangleCommand>?,
        sourcePixelAt: (x: Int, y: Int) -> Int,
    ): Boolean {
        val top = trapezoid.top.fixedToDouble()
        val bottom = trapezoid.bottom.fixedToDouble()
        if (bottom <= top) return false
        val startY = maxOf(0, floor(top).toInt())
        val endY = minOf(height, ceil(bottom).toInt())
        var painted = false
        for (y in startY until endY) {
            val sampleY = y + 0.5
            if (sampleY < top || sampleY >= bottom) continue
            val left = trapezoid.left.xAt(sampleY)
            val right = trapezoid.right.xAt(sampleY)
            val minX = minOf(left, right)
            val maxX = maxOf(left, right)
            val startX = maxOf(0, floor(minX).toInt())
            val endX = minOf(width, ceil(maxX).toInt())
            for (x in startX until endX) {
                if (!insideClip(x, y, clipRectangles)) continue
                val coverage = trapezoidCoverage(x, y, trapezoid, top, bottom)
                if (coverage == 0) continue
                val maskAlpha = coverage * 255 / TrapezoidSamples
                val index = y * width + x
                val pixel = sourcePixelAt(x, y)
                pixels[index] = when (operation) {
                    XRender.OpClear -> if (coverage == TrapezoidSamples) 0 else over(0, pixels[index], maskAlpha)
                    XRender.OpSrc -> if (coverage == TrapezoidSamples) pixel else withMask(pixel, maskAlpha)
                    XRender.OpOver -> over(pixel, pixels[index], maskAlpha)
                    else -> over(pixel, pixels[index], maskAlpha)
                }
                painted = true
            }
        }
        return painted
    }

    private fun trapezoidCoverage(
        x: Int,
        y: Int,
        trapezoid: XTrapezoidCommand,
        top: Double,
        bottom: Double,
    ): Int {
        var covered = 0
        for (sampleYIndex in 0 until TrapezoidSampleGrid) {
            val sampleY = y + (sampleYIndex + 0.5) / TrapezoidSampleGrid
            if (sampleY < top || sampleY >= bottom) continue
            val left = trapezoid.left.xAt(sampleY)
            val right = trapezoid.right.xAt(sampleY)
            val minX = minOf(left, right)
            val maxX = maxOf(left, right)
            for (sampleXIndex in 0 until TrapezoidSampleGrid) {
                val sampleX = x + (sampleXIndex + 0.5) / TrapezoidSampleGrid
                if (sampleX >= minX && sampleX < maxX) covered += 1
            }
        }
        return covered
    }

    private fun compositeTriangle(
        operation: Int,
        triangle: XTriangleCommand,
        clipRectangles: List<XRectangleCommand>?,
        sourcePixelAt: (x: Int, y: Int) -> Int,
    ): Boolean {
        val x1 = triangle.p1.x.fixedToDouble()
        val y1 = triangle.p1.y.fixedToDouble()
        val x2 = triangle.p2.x.fixedToDouble()
        val y2 = triangle.p2.y.fixedToDouble()
        val x3 = triangle.p3.x.fixedToDouble()
        val y3 = triangle.p3.y.fixedToDouble()
        val area = edge(x1, y1, x2, y2, x3, y3)
        if (area == 0.0) return false
        val startX = maxOf(0, floor(minOf(x1, x2, x3)).toInt())
        val endX = minOf(width, ceil(maxOf(x1, x2, x3)).toInt())
        val startY = maxOf(0, floor(minOf(y1, y2, y3)).toInt())
        val endY = minOf(height, ceil(maxOf(y1, y2, y3)).toInt())
        var painted = false
        for (y in startY until endY) {
            for (x in startX until endX) {
                if (!insideClip(x, y, clipRectangles)) continue
                val coverage = triangleCoverage(x, y, x1, y1, x2, y2, x3, y3, area)
                if (coverage == 0) continue
                val maskAlpha = coverage * 255 / TrapezoidSamples
                val index = y * width + x
                val pixel = sourcePixelAt(x, y)
                pixels[index] = renderPixel(pixel, pixels[index], operation, coverage, maskAlpha)
                painted = true
            }
        }
        return painted
    }

    private fun triangleCoverage(
        x: Int,
        y: Int,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        area: Double,
    ): Int {
        var covered = 0
        for (sampleYIndex in 0 until TrapezoidSampleGrid) {
            val sampleY = y + (sampleYIndex + 0.5) / TrapezoidSampleGrid
            for (sampleXIndex in 0 until TrapezoidSampleGrid) {
                val sampleX = x + (sampleXIndex + 0.5) / TrapezoidSampleGrid
                val e1 = edge(x1, y1, x2, y2, sampleX, sampleY)
                val e2 = edge(x2, y2, x3, y3, sampleX, sampleY)
                val e3 = edge(x3, y3, x1, y1, sampleX, sampleY)
                if (area > 0.0) {
                    if (e1 >= 0.0 && e2 >= 0.0 && e3 >= 0.0) covered += 1
                } else if (e1 <= 0.0 && e2 <= 0.0 && e3 <= 0.0) {
                    covered += 1
                }
            }
        }
        return covered
    }

    private fun renderPixel(source: Int, destination: Int, operation: Int, coverage: Int, maskAlpha: Int): Int =
        when (operation) {
            XRender.OpClear -> if (coverage == TrapezoidSamples) 0 else over(0, destination, maskAlpha)
            XRender.OpSrc -> if (coverage == TrapezoidSamples) source else withMask(source, maskAlpha)
            XRender.OpOver -> over(source, destination, maskAlpha)
            else -> over(source, destination, maskAlpha)
        }

    private fun sampledMaskAlpha(
        mask: XFramebuffer?,
        maskAlphaAt: ((x: Int, y: Int) -> Int)?,
        x: Int,
        y: Int,
    ): Int =
        maskAlphaAt?.invoke(x, y) ?: mask?.alphaAt(x, y) ?: 255

    private fun edge(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double): Double =
        (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1)

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
        function: Int,
        planeMask: Int,
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
                    painted = fillScanlineSpan(y, previous, x, color, clipRectangles, function, planeMask) || painted
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
        function: Int,
        planeMask: Int,
    ): Boolean {
        val left = ceil(leftIntersection).toInt()
        val right = ceil(rightIntersection).toInt()
        var painted = false
        for (x in left until right) {
            if (x !in 0 until width || y !in 0 until height) continue
            if (!insideClip(x, y, clipRectangles)) continue
            val index = y * width + x
            pixels[index] = corePixel(source = color, destination = pixels[index], function = function, planeMask = planeMask)
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

    private fun usesCoreRaster(function: Int, planeMask: Int): Boolean =
        function != XGraphicsContext.GXcopy || planeMask != -1

    private fun Int.floorMod(modulus: Int): Int {
        val result = this % modulus
        return if (result < 0) result + modulus else result
    }

    private fun Int.fixedToDouble(): Double = this / FixedOne

    private fun XFixedLine.xAt(y: Double): Double {
        val y1 = p1.y.fixedToDouble()
        val y2 = p2.y.fixedToDouble()
        val x1 = p1.x.fixedToDouble()
        val x2 = p2.x.fixedToDouble()
        if (y1 == y2) return x1
        return x1 + (y - y1) * (x2 - x1) / (y2 - y1)
    }

    private fun corePixel(source: Int, destination: Int, function: Int, planeMask: Int): Int {
        val mask = planeMask and CorePixelMask
        val sourcePixel = source and CorePixelMask
        val destinationPixel = destination and CorePixelMask
        val functionPixel = when (function) {
            XGraphicsContext.GXclear -> 0
            XGraphicsContext.GXand -> sourcePixel and destinationPixel
            XGraphicsContext.GXandReverse -> sourcePixel and destinationPixel.inv()
            XGraphicsContext.GXcopy -> sourcePixel
            XGraphicsContext.GXandInverted -> sourcePixel.inv() and destinationPixel
            XGraphicsContext.GXnoop -> destinationPixel
            XGraphicsContext.GXxor -> sourcePixel xor destinationPixel
            XGraphicsContext.GXor -> sourcePixel or destinationPixel
            XGraphicsContext.GXnor -> (sourcePixel or destinationPixel).inv()
            XGraphicsContext.GXequiv -> (sourcePixel xor destinationPixel).inv()
            XGraphicsContext.GXinvert -> destinationPixel.inv()
            XGraphicsContext.GXorReverse -> sourcePixel or destinationPixel.inv()
            XGraphicsContext.GXcopyInverted -> sourcePixel.inv()
            XGraphicsContext.GXorInverted -> sourcePixel.inv() or destinationPixel
            XGraphicsContext.GXnand -> (sourcePixel and destinationPixel).inv()
            XGraphicsContext.GXset -> CorePixelMask
            else -> sourcePixel
        } and CorePixelMask
        return (destination and 0xff00_0000.toInt()) or ((destinationPixel and mask.inv()) or (functionPixel and mask))
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
        private const val CorePixelMask = 0x00ff_ffff
        private const val FixedOne = 65_536.0
        private const val TrapezoidSampleGrid = 4
        private const val TrapezoidSamples = TrapezoidSampleGrid * TrapezoidSampleGrid
        const val TextCellWidth = 8
        const val TextAscent = 12
        const val TextDescent = 4
        const val TextCellHeight = TextAscent + TextDescent

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

        private fun textPixel(text: String, column: Int, row: Int): Boolean {
            val charIndex = column / TextCellWidth
            if (charIndex !in text.indices || text[charIndex] == ' ') return false
            val cellX = column % TextCellWidth
            if (cellX !in 1..6 || row !in 2..11) return false
            if (cellX == 1 || cellX == 6 || row == 2 || row == 11) return true
            return (((text[charIndex].code + row * 17) ushr (cellX - 2)) and 1) != 0
        }
    }
}

private fun XArcCommand.centerX(): Double = x + width / 2.0

private fun XArcCommand.centerY(): Double = y + height / 2.0

internal class XDashPattern private constructor(
    private val lineStyle: Int,
    private val dashes: List<Int>,
    private var phase: Int,
    private val foreground: Int,
    private val background: Int,
) {
    fun pixel(): Int? {
        if (lineStyle == XGraphicsContext.LineSolid || dashes.isEmpty()) return foreground
        var remaining = phase % dashes.sum()
        for ((index, dash) in dashes.withIndex()) {
            if (remaining < dash) {
                return when {
                    index % 2 == 0 -> foreground
                    lineStyle == XGraphicsContext.LineDoubleDash -> background
                    else -> null
                }
            }
            remaining -= dash
        }
        return foreground
    }

    fun advance() {
        phase = (phase + 1) % dashes.sum()
    }

    companion object {
        fun create(lineStyle: Int, dashOffset: Int, dashes: List<Int>, foreground: Int, background: Int): XDashPattern? {
            if (lineStyle == XGraphicsContext.LineSolid) return null
            val positiveDashes = dashes.filter { it > 0 }
            if (positiveDashes.isEmpty()) return null
            val normalized = if (positiveDashes.size % 2 == 0) positiveDashes else positiveDashes + positiveDashes
            return XDashPattern(
                lineStyle = lineStyle,
                dashes = normalized,
                phase = dashOffset.coerceAtLeast(0) % normalized.sum(),
                foreground = foreground,
                background = background,
            )
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
