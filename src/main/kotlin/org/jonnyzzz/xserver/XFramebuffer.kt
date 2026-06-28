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

internal data class XCopyResult(
    val image: XImagePixels,
    val destinationX: Int,
    val destinationY: Int,
) {
    val width: Int get() = image.width
    val height: Int get() = image.height
}

internal typealias XClipMask = (x: Int, y: Int) -> Boolean

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

    fun snapshot(): XImagePixels = XImagePixels(width, height, pixels.copyOf())

    fun snapshotRegion(x: Int, y: Int, width: Int, height: Int): XImagePixels {
        if (width <= 0 || height <= 0) return XImagePixels(0, 0, IntArray(0))
        val copied = IntArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                copied[row * width + column] = pixelAt(x + column, y + row) ?: 0
            }
        }
        return XImagePixels(width, height, copied)
    }

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
        clipMask: XClipMask? = null,
    ): Boolean {
        val bounds = clippedBounds(x, y, width, height) ?: return false
        val color = if (preserveAlpha) pixel else opaque(pixel)
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val offset = row * this.width
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles, clipMask)) continue
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

    fun fillPattern(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        patternWidth: Int,
        patternHeight: Int,
        patternXOrigin: Int,
        patternYOrigin: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
        pixelAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        if (patternWidth <= 0 || patternHeight <= 0) return false
        val bounds = clippedBounds(x, y, width, height) ?: return false
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val offset = row * this.width
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles)) continue
                val sourceX = (column - patternXOrigin).floorMod(patternWidth)
                val sourceY = (row - patternYOrigin).floorMod(patternHeight)
                val source = pixelAt(sourceX, sourceY)?.let { opaque(it) } ?: continue
                pixels[offset + column] = corePixel(
                    source = source,
                    destination = pixels[offset + column],
                    function = function,
                    planeMask = planeMask,
                )
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
    ): Boolean =
        fillPolygonWithSource(points, fillRule, clipRectangles, function, planeMask) { _, _ -> opaque(pixel) }

    fun fillPolygonPattern(
        points: List<XPoint>,
        fillRule: Int = XGraphicsContext.EvenOddRule,
        patternXOrigin: Int,
        patternYOrigin: Int,
        patternWidth: Int,
        patternHeight: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
        pixelAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        if (patternWidth <= 0 || patternHeight <= 0) return false
        return fillPolygonWithSource(points, fillRule, clipRectangles, function, planeMask) { x, y ->
            val sourceX = (x - patternXOrigin).floorMod(patternWidth)
            val sourceY = (y - patternYOrigin).floorMod(patternHeight)
            pixelAt(sourceX, sourceY)?.let { opaque(it) }
        }
    }

    private fun fillPolygonWithSource(
        points: List<XPoint>,
        fillRule: Int,
        clipRectangles: List<XRectangleCommand>?,
        function: Int,
        planeMask: Int,
        sourceAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        if (points.size < 3) return false
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
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
                painted = fillWindingScanline(y, points, scanY, clipRectangles, function, planeMask, sourceAt) || painted
            } else {
                var index = 0
                while (index + 1 < intersections.size) {
                    painted = fillScanlineSpan(y, intersections[index], intersections[index + 1], clipRectangles, function, planeMask, sourceAt) || painted
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
        return fillArcWithSource(arc, arcMode, clipRectangles, function, planeMask) { _, _ -> opaque(pixel) }
    }

    fun fillArcPattern(
        arc: XArcCommand,
        arcMode: Int,
        patternXOrigin: Int,
        patternYOrigin: Int,
        patternWidth: Int,
        patternHeight: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
        pixelAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        if (patternWidth <= 0 || patternHeight <= 0) return false
        return fillArcWithSource(arc, arcMode, clipRectangles, function, planeMask) { x, y ->
            val sourceX = (x - patternXOrigin).floorMod(patternWidth)
            val sourceY = (y - patternYOrigin).floorMod(patternHeight)
            pixelAt(sourceX, sourceY)?.let { opaque(it) }
        }
    }

    private fun fillArcWithSource(
        arc: XArcCommand,
        arcMode: Int,
        clipRectangles: List<XRectangleCommand>?,
        function: Int,
        planeMask: Int,
        sourceAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        if (arc.width <= 0 || arc.height <= 0 || arc.angle2 == 0) return false
        if (abs(arc.angle2) >= FullCircleAngle) {
            return fillEllipse(arc, clipRectangles, function, planeMask, sourceAt)
        }

        val arcPoints = sampledArcPoints(arc)
        if (arcPoints.size < 2) return false
        val polygon = if (arcMode == ArcChord) {
            arcPoints
        } else {
            listOf(XPoint(arc.centerX().roundToInt(), arc.centerY().roundToInt())) + arcPoints
        }
        return fillPolygonWithSource(polygon, XGraphicsContext.EvenOddRule, clipRectangles, function, planeMask, sourceAt)
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
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            over(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidOverReverse(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            overReverseOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidDisjointOver(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            disjointOverOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidAdd(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            add(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidSaturate(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            saturate(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidIn(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            inOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidOut(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            outOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidInReverse(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            inReverseOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidOutReverse(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            outReverseOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidAtop(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            atopOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidAtopReverse(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            atopReverseOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun blendSolidXor(
        pixel: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + x - destinationX, maskY + y - destinationY)
                ?: return@compositeBoundsOptional null
            xorOperator(pixel, pixels[y * this.width + x], maskAlpha)
        }
    }

    fun compositeSourceOverMask(
        sourceX: Int,
        sourceY: Int,
        originX: Int,
        originY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        operation: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer,
        sourcePixelAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        val bounds = clippedBounds(destinationX, destinationY, width, height) ?: return false
        return compositeBoundsOptional(bounds, clipRectangles, clipMask) { x, y ->
            val maskAlpha = mask.alphaAt(x - destinationX, y - destinationY)
            val sourcePixel = sourcePixelAt(sourceX + x - originX, sourceY + y - originY) ?: return@compositeBoundsOptional null
            renderPixel(sourcePixel, pixels[y * this.width + x], operation, maskAlpha)
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
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
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
        var minPaintedX = Int.MAX_VALUE
        var minPaintedY = Int.MAX_VALUE
        var maxPaintedX = Int.MIN_VALUE
        var maxPaintedY = Int.MIN_VALUE
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                val sx = bounds.sourceX + column
                val sy = bounds.sourceY + row
                val dx = bounds.destinationX + column
                val dy = bounds.destinationY + row
                val sourcePixel = source.pixels[sy * source.width + sx]
                copied[row * bounds.width + column] = sourcePixel
                if (!insideClip(dx, dy, clipRectangles, clipMask)) continue
                val index = dy * this.width + dx
                val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, maskX + dx - destinationX, maskY + dy - destinationY)
                    ?: continue
                pixels[index] = renderPixel(sourcePixel, pixels[index], operation, maskAlpha)
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
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
        maskPixelAt: ((x: Int, y: Int) -> Int?)? = null,
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
                if (!insideClip(dx, dy, clipRectangles, clipMask)) continue
                val index = dy * this.width + dx
                val mx = maskX + dx - destinationX
                val my = maskY + dy - destinationY
                pixels[index] = if (maskPixelAt != null) {
                    val maskPixel = maskPixelAt.invoke(mx, my) ?: continue
                    renderPixelComponentMask(sourcePixel, pixels[index], operation, maskPixel)
                } else {
                    val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, mx, my) ?: continue
                    renderPixel(sourcePixel, pixels[index], operation, maskAlpha)
                }
                painted = true
            }
        }
        if (painted) markPainted()
        return if (operation.returnsDestinationResultImage()) {
            snapshotRegion(bounds.destinationX, bounds.destinationY, bounds.width, bounds.height)
        } else {
            XImagePixels(bounds.width, bounds.height, generated)
        }
    }

    fun compositeGeneratedOptional(
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        operation: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        mask: XFramebuffer? = null,
        maskX: Int = 0,
        maskY: Int = 0,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)? = null,
        maskPixelAt: ((x: Int, y: Int) -> Int?)? = null,
        sourcePixelAt: (x: Int, y: Int) -> Int?,
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
                val sourcePixel = sourcePixelAt(sx, sy) ?: continue
                generated[row * bounds.width + column] = sourcePixel
                if (!insideClip(dx, dy, clipRectangles, clipMask)) continue
                val index = dy * this.width + dx
                val mx = maskX + dx - destinationX
                val my = maskY + dy - destinationY
                pixels[index] = if (maskPixelAt != null) {
                    val maskPixel = maskPixelAt.invoke(mx, my) ?: continue
                    renderPixelComponentMask(sourcePixel, pixels[index], operation, maskPixel)
                } else {
                    val maskAlpha = sampledMaskAlpha(mask, maskAlphaAt, mx, my) ?: continue
                    renderPixel(sourcePixel, pixels[index], operation, maskAlpha)
                }
                painted = true
            }
        }
        if (painted) markPainted()
        return if (operation.returnsDestinationResultImage()) {
            snapshotRegion(bounds.destinationX, bounds.destinationY, bounds.width, bounds.height)
        } else {
            XImagePixels(bounds.width, bounds.height, generated)
        }
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

        var minPaintedX = Int.MAX_VALUE
        var minPaintedY = Int.MAX_VALUE
        var maxPaintedX = Int.MIN_VALUE
        var maxPaintedY = Int.MIN_VALUE
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
    ): XCopyResult? {
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
        var minPaintedX = Int.MAX_VALUE
        var minPaintedY = Int.MAX_VALUE
        var maxPaintedX = Int.MIN_VALUE
        var maxPaintedY = Int.MIN_VALUE
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
                minPaintedX = minOf(minPaintedX, dx)
                minPaintedY = minOf(minPaintedY, dy)
                maxPaintedX = maxOf(maxPaintedX, dx)
                maxPaintedY = maxOf(maxPaintedY, dy)
            }
        }
        if (minPaintedX == Int.MAX_VALUE) return null
        destination.markPainted()
        return copiedResult(copied, bounds, minPaintedX, minPaintedY, maxPaintedX, maxPaintedY)
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
    ): XCopyResult? {
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

        var minPaintedX = Int.MAX_VALUE
        var minPaintedY = Int.MAX_VALUE
        var maxPaintedX = Int.MIN_VALUE
        var maxPaintedY = Int.MIN_VALUE
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
                minPaintedX = minOf(minPaintedX, dx)
                minPaintedY = minOf(minPaintedY, dy)
                maxPaintedX = maxOf(maxPaintedX, dx)
                maxPaintedY = maxOf(maxPaintedY, dy)
            }
        }
        if (minPaintedX == Int.MAX_VALUE) return null
        destination.markPainted()
        return copiedResult(copied, bounds, minPaintedX, minPaintedY, maxPaintedX, maxPaintedY)
    }

    private fun copiedResult(
        copied: IntArray,
        bounds: CopyBounds,
        minPaintedX: Int,
        minPaintedY: Int,
        maxPaintedX: Int,
        maxPaintedY: Int,
    ): XCopyResult {
        val resultWidth = maxPaintedX - minPaintedX + 1
        val resultHeight = maxPaintedY - minPaintedY + 1
        val resultPixels = IntArray(resultWidth * resultHeight)
        val sourceX = minPaintedX - bounds.destinationX
        val sourceY = minPaintedY - bounds.destinationY
        for (row in 0 until resultHeight) {
            copied.copyInto(
                destination = resultPixels,
                destinationOffset = row * resultWidth,
                startIndex = (sourceY + row) * bounds.width + sourceX,
                endIndex = (sourceY + row) * bounds.width + sourceX + resultWidth,
            )
        }
        return XCopyResult(
            image = XImagePixels(resultWidth, resultHeight, resultPixels),
            destinationX = minPaintedX,
            destinationY = minPaintedY,
        )
    }

    fun compositeTrapezoids(
        pixel: Int,
        operation: Int,
        trapezoids: List<XTrapezoidCommand>,
        maskFormat: Int = XRender.A8Format,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
    ): Boolean =
        compositeTrapezoids(
            operation = operation,
            trapezoids = trapezoids,
            maskFormat = maskFormat,
            clipRectangles = clipRectangles,
            clipMask = clipMask,
        ) { _, _ -> pixel }

    fun compositeTrapezoids(
        operation: Int,
        trapezoids: List<XTrapezoidCommand>,
        maskFormat: Int = XRender.A8Format,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        sourcePixelAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        var painted = false
        for (trapezoid in trapezoids) {
            painted = compositeTrapezoid(operation, trapezoid, maskFormat, clipRectangles, clipMask, sourcePixelAt) || painted
        }
        if (painted) markPainted()
        return painted
    }

    fun addTrapezoids(
        trapezoids: List<XTrapezoidCommand>,
        maskFormat: Int = XRender.A8Format,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
    ): Boolean {
        var painted = false
        for (trapezoid in trapezoids) {
            painted = addTrapezoidAlpha(trapezoid, maskFormat, clipRectangles, clipMask) || painted
        }
        if (painted) markPainted()
        return painted
    }

    fun compositeTriangles(
        pixel: Int,
        operation: Int,
        triangles: List<XTriangleCommand>,
        maskFormat: Int = XRender.A8Format,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
    ): Boolean =
        compositeTriangles(
            operation = operation,
            triangles = triangles,
            maskFormat = maskFormat,
            clipRectangles = clipRectangles,
            clipMask = clipMask,
        ) { _, _ -> pixel }

    fun compositeTriangles(
        operation: Int,
        triangles: List<XTriangleCommand>,
        maskFormat: Int = XRender.A8Format,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        sourcePixelAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        var painted = false
        for (triangle in triangles) {
            painted = compositeTriangle(operation, triangle, maskFormat, clipRectangles, clipMask, sourcePixelAt) || painted
        }
        if (painted) markPainted()
        return painted
    }

    fun compositeColoredTrapezoids(
        operation: Int,
        trapezoids: List<XColorTrapCommand>,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
    ): Boolean {
        var painted = false
        for (trapezoid in trapezoids) {
            painted = compositeColoredTrapezoid(operation, trapezoid, clipRectangles, clipMask) || painted
        }
        if (painted) markPainted()
        return painted
    }

    fun compositeTransformedQuad(
        operation: Int,
        sourceQuad: XFixedQuad,
        destinationQuad: XFixedQuad,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
        sourcePixelAt: (x: Double, y: Double) -> Int?,
    ): Boolean {
        val destination = destinationQuad.toDoubleQuad()
        val source = sourceQuad.toDoubleQuad()
        val ux = destination.p2.x - destination.p1.x
        val uy = destination.p2.y - destination.p1.y
        val vx = destination.p4.x - destination.p1.x
        val vy = destination.p4.y - destination.p1.y
        val determinant = ux * vy - uy * vx
        if (determinant == 0.0) return false
        val startX = maxOf(0, floor(destination.points.minOf { it.x }).toInt())
        val endX = minOf(width, ceil(destination.points.maxOf { it.x }).toInt())
        val startY = maxOf(0, floor(destination.points.minOf { it.y }).toInt())
        val endY = minOf(height, ceil(destination.points.maxOf { it.y }).toInt())
        var painted = false
        for (y in startY until endY) {
            val sampleY = y + 0.5
            for (x in startX until endX) {
                if (!insideClip(x, y, clipRectangles, clipMask)) continue
                val sampleX = x + 0.5
                val dx = sampleX - destination.p1.x
                val dy = sampleY - destination.p1.y
                val u = (dx * vy - dy * vx) / determinant
                val v = (ux * dy - uy * dx) / determinant
                if (u < 0.0 || u >= 1.0 || v < 0.0 || v >= 1.0) continue
                val sourceX = bilinearCoordinate(source.p1.x, source.p2.x, source.p3.x, source.p4.x, u, v)
                val sourceY = bilinearCoordinate(source.p1.y, source.p2.y, source.p3.y, source.p4.y, u, v)
                val pixel = sourcePixelAt(sourceX, sourceY) ?: continue
                val index = y * width + x
                pixels[index] = renderPixel(pixel, pixels[index], operation, maskAlpha = 255)
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    fun compositeColoredTriangles(
        operation: Int,
        triangles: List<XColorTriangleCommand>,
        clipRectangles: List<XRectangleCommand>? = null,
        clipMask: XClipMask? = null,
    ): Boolean {
        var painted = false
        for (triangle in triangles) {
            painted = compositeColoredTriangle(operation, triangle, clipRectangles, clipMask) || painted
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

    private fun compositeBoundsOptional(
        bounds: CopyBounds,
        clipRectangles: List<XRectangleCommand>?,
        clipMask: XClipMask?,
        compose: (x: Int, y: Int) -> Int?,
    ): Boolean {
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val offset = row * this.width
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles, clipMask)) continue
                val pixel = compose(column, row) ?: continue
                pixels[offset + column] = pixel
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    private fun fillEllipse(
        arc: XArcCommand,
        clipRectangles: List<XRectangleCommand>?,
        function: Int,
        planeMask: Int,
        sourceAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        val bounds = clippedBounds(arc.x, arc.y, arc.width, arc.height) ?: return false
        val radiusX = arc.width / 2.0
        val radiusY = arc.height / 2.0
        if (radiusX <= 0.0 || radiusY <= 0.0) return false
        val centerX = arc.centerX()
        val centerY = arc.centerY()
        var painted = false
        for (row in bounds.destinationY until bounds.destinationY + bounds.height) {
            val normalizedY = ((row + 0.5) - centerY) / radiusY
            for (column in bounds.destinationX until bounds.destinationX + bounds.width) {
                if (!insideClip(column, row, clipRectangles)) continue
                val normalizedX = ((column + 0.5) - centerX) / radiusX
                if (normalizedX * normalizedX + normalizedY * normalizedY > 1.0) continue
                val index = row * width + column
                val source = sourceAt(column, row) ?: continue
                pixels[index] = corePixel(source = source, destination = pixels[index], function = function, planeMask = planeMask)
                painted = true
            }
        }
        if (painted) markPainted()
        return painted
    }

    private fun compositeTrapezoid(
        operation: Int,
        trapezoid: XTrapezoidCommand,
        maskFormat: Int,
        clipRectangles: List<XRectangleCommand>?,
        clipMask: XClipMask?,
        sourcePixelAt: (x: Int, y: Int) -> Int?,
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
                if (!insideClip(x, y, clipRectangles, clipMask)) continue
                val coverage = trapezoidCoverage(x, y, trapezoid, top, bottom)
                if (coverage == 0) continue
                val maskAlpha = maskAlpha(maskFormat, coverage)
                if (maskAlpha == 0) continue
                val index = y * width + x
                val pixel = sourcePixelAt(x, y) ?: continue
                pixels[index] = renderPixel(pixel, pixels[index], operation, maskAlpha)
                painted = true
            }
        }
        return painted
    }

    private fun addTrapezoidAlpha(
        trapezoid: XTrapezoidCommand,
        maskFormat: Int,
        clipRectangles: List<XRectangleCommand>?,
        clipMask: XClipMask?,
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
                if (!insideClip(x, y, clipRectangles, clipMask)) continue
                val coverage = trapezoidCoverage(x, y, trapezoid, top, bottom)
                if (coverage == 0) continue
                val addedAlpha = maskAlpha(maskFormat, coverage)
                if (addedAlpha == 0) continue
                val index = y * width + x
                val alpha = ((pixels[index] ushr 24) and 0xff)
                val newAlpha = (alpha + addedAlpha).coerceAtMost(255)
                pixels[index] = (newAlpha shl 24) or 0x00ff_ffff
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

    private fun compositeColoredTrapezoid(
        operation: Int,
        colorTrap: XColorTrapCommand,
        clipRectangles: List<XRectangleCommand>?,
        clipMask: XClipMask?,
    ): Boolean {
        val trapezoid = colorTrap.toTrapezoid()
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
                if (!insideClip(x, y, clipRectangles, clipMask)) continue
                val coverage = trapezoidCoverage(x, y, trapezoid, top, bottom)
                if (coverage == 0) continue
                val maskAlpha = maskAlpha(XRender.A8Format, coverage)
                if (maskAlpha == 0) continue
                val source = interpolatedColorTrapPixel(colorTrap, left, right, top, bottom, x + 0.5, sampleY)
                val index = y * width + x
                pixels[index] = renderPixel(source, pixels[index], operation, maskAlpha)
                painted = true
            }
        }
        return painted
    }

    private fun interpolatedColorTrapPixel(
        trapezoid: XColorTrapCommand,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
        x: Double,
        y: Double,
    ): Int {
        val vertical = ((y - top) / (bottom - top)).coerceIn(0.0, 1.0)
        val horizontal = if (right == left) 0.0 else ((x - left) / (right - left)).coerceIn(0.0, 1.0)
        fun channel(channel: (XRenderColor) -> Int): Int {
            val topLeft = channel(trapezoid.top.leftColor) * (1.0 - vertical) * (1.0 - horizontal)
            val bottomLeft = channel(trapezoid.bottom.leftColor) * vertical * (1.0 - horizontal)
            val topRight = channel(trapezoid.top.rightColor) * (1.0 - vertical) * horizontal
            val bottomRight = channel(trapezoid.bottom.rightColor) * vertical * horizontal
            return (topLeft + bottomLeft + topRight + bottomRight).roundToInt().coerceIn(0, 0xffff)
        }
        return XRender.argb32Pixel(
            red = channel { it.red },
            green = channel { it.green },
            blue = channel { it.blue },
            alpha = channel { it.alpha },
        )
    }

    private fun bilinearCoordinate(p1: Double, p2: Double, p3: Double, p4: Double, u: Double, v: Double): Double =
        p1 * (1.0 - u) * (1.0 - v) +
            p2 * u * (1.0 - v) +
            p3 * u * v +
            p4 * (1.0 - u) * v

    private fun compositeTriangle(
        operation: Int,
        triangle: XTriangleCommand,
        maskFormat: Int,
        clipRectangles: List<XRectangleCommand>?,
        clipMask: XClipMask?,
        sourcePixelAt: (x: Int, y: Int) -> Int?,
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
                if (!insideClip(x, y, clipRectangles, clipMask)) continue
                val coverage = triangleCoverage(x, y, x1, y1, x2, y2, x3, y3, area)
                if (coverage == 0) continue
                val maskAlpha = maskAlpha(maskFormat, coverage)
                if (maskAlpha == 0) continue
                val index = y * width + x
                val pixel = sourcePixelAt(x, y) ?: continue
                pixels[index] = renderPixel(pixel, pixels[index], operation, maskAlpha)
                painted = true
            }
        }
        return painted
    }

    private fun compositeColoredTriangle(
        operation: Int,
        triangle: XColorTriangleCommand,
        clipRectangles: List<XRectangleCommand>?,
        clipMask: XClipMask?,
    ): Boolean {
        val x1 = triangle.p1.point.x.fixedToDouble()
        val y1 = triangle.p1.point.y.fixedToDouble()
        val x2 = triangle.p2.point.x.fixedToDouble()
        val y2 = triangle.p2.point.y.fixedToDouble()
        val x3 = triangle.p3.point.x.fixedToDouble()
        val y3 = triangle.p3.point.y.fixedToDouble()
        val area = edge(x1, y1, x2, y2, x3, y3)
        if (area == 0.0) return false
        val startX = maxOf(0, floor(minOf(x1, x2, x3)).toInt())
        val endX = minOf(width, ceil(maxOf(x1, x2, x3)).toInt())
        val startY = maxOf(0, floor(minOf(y1, y2, y3)).toInt())
        val endY = minOf(height, ceil(maxOf(y1, y2, y3)).toInt())
        var painted = false
        for (y in startY until endY) {
            val sampleY = y + 0.5
            for (x in startX until endX) {
                if (!insideClip(x, y, clipRectangles, clipMask)) continue
                val coverage = triangleCoverage(x, y, x1, y1, x2, y2, x3, y3, area)
                if (coverage == 0) continue
                val maskAlpha = maskAlpha(XRender.A8Format, coverage)
                if (maskAlpha == 0) continue
                val sampleX = x + 0.5
                val source = interpolatedColorTrianglePixel(triangle, x1, y1, x2, y2, x3, y3, area, sampleX, sampleY)
                val index = y * width + x
                pixels[index] = renderPixel(source, pixels[index], operation, maskAlpha)
                painted = true
            }
        }
        return painted
    }

    private fun interpolatedColorTrianglePixel(
        triangle: XColorTriangleCommand,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        area: Double,
        x: Double,
        y: Double,
    ): Int {
        val w1 = edge(x2, y2, x3, y3, x, y) / area
        val w2 = edge(x3, y3, x1, y1, x, y) / area
        val w3 = edge(x1, y1, x2, y2, x, y) / area
        fun channel(channel: (XRenderColor) -> Int): Int {
            val value = channel(triangle.p1.color) * w1 +
                channel(triangle.p2.color) * w2 +
                channel(triangle.p3.color) * w3
            return value.roundToInt().coerceIn(0, 0xffff)
        }
        return XRender.argb32Pixel(
            red = channel { it.red },
            green = channel { it.green },
            blue = channel { it.blue },
            alpha = channel { it.alpha },
        )
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

    private fun maskAlpha(maskFormat: Int, coverage: Int): Int =
        when (maskFormat) {
            XRender.A1Format -> if (coverage * 2 >= TrapezoidSamples) 255 else 0
            else -> coverage * 255 / TrapezoidSamples
        }

    private fun renderPixel(source: Int, destination: Int, operation: Int, maskAlpha: Int): Int =
        when (operation) {
            XRender.OpClear, XRender.OpDisjointClear -> clearWithMask(destination, maskAlpha)
            XRender.OpSrc, XRender.OpDisjointSrc -> if (maskAlpha >= 255) source else withMask(source, maskAlpha)
            XRender.OpDst, XRender.OpDisjointDst -> destination
            XRender.OpOver -> over(source, destination, maskAlpha)
            XRender.OpDisjointOver -> disjointOverOperator(source, destination, maskAlpha)
            XRender.OpOverReverse -> overReverseOperator(source, destination, maskAlpha)
            XRender.OpIn -> inOperator(source, destination, maskAlpha)
            XRender.OpInReverse -> inReverseOperator(source, destination, maskAlpha)
            XRender.OpOut -> outOperator(source, destination, maskAlpha)
            XRender.OpOutReverse -> outReverseOperator(source, destination, maskAlpha)
            XRender.OpAtop -> atopOperator(source, destination, maskAlpha)
            XRender.OpAtopReverse -> atopReverseOperator(source, destination, maskAlpha)
            XRender.OpXor -> xorOperator(source, destination, maskAlpha)
            XRender.OpAdd -> add(source, destination, maskAlpha)
            XRender.OpSaturate -> saturate(source, destination, maskAlpha)
            else -> over(source, destination, maskAlpha)
        }

    private fun renderPixelComponentMask(source: Int, destination: Int, operation: Int, mask: Int): Int =
        when (operation) {
            XRender.OpClear, XRender.OpDisjointClear -> clearWithComponentMask(destination, mask)
            XRender.OpSrc, XRender.OpDisjointSrc -> withComponentMask(source, mask)
            XRender.OpDst, XRender.OpDisjointDst -> destination
            XRender.OpOver -> overComponentMask(source, destination, mask)
            XRender.OpDisjointOver -> disjointOverComponentMask(source, destination, mask)
            XRender.OpOverReverse -> overReverseComponentMask(source, destination, mask)
            XRender.OpIn -> inComponentMask(source, destination, mask)
            XRender.OpInReverse -> inReverseComponentMask(source, destination, mask)
            XRender.OpOut -> outComponentMask(source, destination, mask)
            XRender.OpOutReverse -> outReverseComponentMask(source, destination, mask)
            XRender.OpAtop -> atopComponentMask(source, destination, mask)
            XRender.OpAtopReverse -> atopReverseComponentMask(source, destination, mask)
            XRender.OpXor -> xorComponentMask(source, destination, mask)
            XRender.OpAdd -> addComponentMask(source, destination, mask)
            XRender.OpSaturate -> saturateComponentMask(source, destination, mask)
            else -> overComponentMask(source, destination, mask)
        }

    private fun clearWithComponentMask(destination: Int, mask: Int): Int {
        fun channel(shift: Int): Int {
            val inverse = 255 - ((mask ushr shift) and 0xff)
            return (((destination ushr shift) and 0xff) * inverse + 127) / 255
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun withComponentMask(source: Int, mask: Int): Int {
        val maskAlpha = (mask ushr 24) and 0xff
        val alpha = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        fun channel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceChannel = (source ushr shift) and 0xff
            return if (maskAlpha == 0) {
                sourceChannel
            } else {
                ((sourceChannel * maskChannel + maskAlpha / 2) / maskAlpha).coerceAtMost(255)
            }
        }
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun overComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val inverse = 255 - sourceAlphaChannel
            val sourceChannel = (source ushr shift) and 0xff
            val destinationChannel = (destination ushr shift) and 0xff
            return (sourceChannel * sourceAlphaChannel + destinationChannel * inverse + 127) / 255
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            val inverse = 255 - sourceAlphaMasked
            val destinationAlpha = (destination ushr 24) and 0xff
            return sourceAlphaMasked + (destinationAlpha * inverse + 127) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun disjointOverComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val destinationFactor = disjointOverDestinationFactor(sourceAlphaChannel, destinationAlpha)
            val sourceChannel = (source ushr shift) and 0xff
            val destinationChannel = (destination ushr shift) and 0xff
            return (
                (sourceChannel * sourceAlphaChannel + 127) / 255 +
                    (destinationChannel * destinationFactor + 127) / 255
                ).coerceAtMost(255)
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            val destinationFactor = disjointOverDestinationFactor(sourceAlphaMasked, destinationAlpha)
            return (sourceAlphaMasked + (destinationAlpha * destinationFactor + 127) / 255).coerceAtMost(255)
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun overReverseComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        val inverseDestinationAlpha = 255 - destinationAlpha
        if (sourceAlpha <= 0 || inverseDestinationAlpha <= 0) return destination
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val sourceChannel = (source ushr shift) and 0xff
            val destinationChannel = (destination ushr shift) and 0xff
            return (
                destinationChannel +
                    (sourceChannel * sourceAlphaChannel * inverseDestinationAlpha + 32_512) / 65_025
                ).coerceAtMost(255)
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            return destinationAlpha + (sourceAlphaMasked * inverseDestinationAlpha + 127) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun addComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val sourceChannel = (source ushr shift) and 0xff
            val destinationChannel = (destination ushr shift) and 0xff
            return (destinationChannel + (sourceChannel * sourceAlphaChannel + 127) / 255).coerceAtMost(255)
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            val destinationAlpha = (destination ushr 24) and 0xff
            return (destinationAlpha + sourceAlphaMasked).coerceAtMost(255)
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun saturateComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        val remainingAlpha = 255 - destinationAlpha
        if (sourceAlpha <= 0 || remainingAlpha <= 0) return destination
        fun sourceAlphaFor(maskChannel: Int): Int = (sourceAlpha * maskChannel + 127) / 255
        val sourceAlphaMasked = sourceAlphaFor((mask ushr 24) and 0xff)
        val sourceAlphaRed = sourceAlphaFor((mask ushr 16) and 0xff)
        val sourceAlphaGreen = sourceAlphaFor((mask ushr 8) and 0xff)
        val sourceAlphaBlue = sourceAlphaFor(mask and 0xff)
        if (sourceAlphaMasked <= 0 && sourceAlphaRed <= 0 && sourceAlphaGreen <= 0 && sourceAlphaBlue <= 0) {
            return destination
        }
        fun colorChannel(shift: Int): Int {
            val sourceAlphaChannel = when (shift) {
                16 -> sourceAlphaRed
                8 -> sourceAlphaGreen
                else -> sourceAlphaBlue
            }
            val contributionAlpha = minOf(sourceAlphaChannel, remainingAlpha)
            val sourceChannel = ((source ushr shift) and 0xff) * contributionAlpha
            return (((destination ushr shift) and 0xff) + (sourceChannel + 127) / 255).coerceAtMost(255)
        }
        fun alphaChannel(): Int = destinationAlpha + minOf(sourceAlphaMasked, remainingAlpha)
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun inComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val sourceChannel = (source ushr shift) and 0xff
            return (sourceChannel * sourceAlphaChannel * destinationAlpha + 32_512) / 65_025
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            return (sourceAlphaMasked * destinationAlpha + 127) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun outComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val inverseDestinationAlpha = 255 - ((destination ushr 24) and 0xff)
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val sourceChannel = (source ushr shift) and 0xff
            return (sourceChannel * sourceAlphaChannel * inverseDestinationAlpha + 32_512) / 65_025
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            return (sourceAlphaMasked * inverseDestinationAlpha + 127) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun inReverseComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val destinationChannel = (destination ushr shift) and 0xff
            return (destinationChannel * sourceAlphaChannel * destinationAlpha + 32_512) / 65_025
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            return (destinationAlpha * sourceAlphaMasked + 127) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun outReverseComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val inverseSourceAlphaChannel = 255 - sourceAlphaChannel
            val destinationChannel = (destination ushr shift) and 0xff
            return (destinationChannel * inverseSourceAlphaChannel * destinationAlpha + 32_512) / 65_025
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            val inverseSourceAlphaMasked = 255 - sourceAlphaMasked
            return (destinationAlpha * inverseSourceAlphaMasked + 127) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun atopComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val inverseSourceAlphaChannel = 255 - sourceAlphaChannel
            val sourceChannel = (source ushr shift) and 0xff
            val destinationChannel = (destination ushr shift) and 0xff
            return (
                sourceChannel * sourceAlphaChannel * destinationAlpha +
                    destinationChannel * inverseSourceAlphaChannel * destinationAlpha +
                    32_512
                ) / 65_025
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            val inverseSourceAlphaMasked = 255 - sourceAlphaMasked
            return (
                sourceAlphaMasked * destinationAlpha +
                    destinationAlpha * inverseSourceAlphaMasked +
                    127
                ) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun atopReverseComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        val inverseDestinationAlpha = 255 - destinationAlpha
        fun colorChannel(shift: Int): Int {
            val maskChannel = (mask ushr shift) and 0xff
            val sourceAlphaChannel = (sourceAlpha * maskChannel + 127) / 255
            val sourceChannel = (source ushr shift) and 0xff
            val destinationChannel = (destination ushr shift) and 0xff
            return (
                destinationChannel * sourceAlphaChannel * destinationAlpha +
                    sourceChannel * sourceAlphaChannel * inverseDestinationAlpha +
                    32_512
                ) / 65_025
        }
        fun alphaChannel(): Int {
            val maskAlpha = (mask ushr 24) and 0xff
            val sourceAlphaMasked = (sourceAlpha * maskAlpha + 127) / 255
            return (
                destinationAlpha * sourceAlphaMasked +
                    sourceAlphaMasked * inverseDestinationAlpha +
                    127
                ) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun xorComponentMask(source: Int, destination: Int, mask: Int): Int {
        val sourceAlpha = (source ushr 24) and 0xff
        val destinationAlpha = (destination ushr 24) and 0xff
        fun sourceAlphaFor(maskChannel: Int): Int = (sourceAlpha * maskChannel + 127) / 255
        val sourceAlphaMasked = sourceAlphaFor((mask ushr 24) and 0xff)
        val sourceAlphaRed = sourceAlphaFor((mask ushr 16) and 0xff)
        val sourceAlphaGreen = sourceAlphaFor((mask ushr 8) and 0xff)
        val sourceAlphaBlue = sourceAlphaFor(mask and 0xff)
        if (sourceAlphaMasked <= 0 && sourceAlphaRed <= 0 && sourceAlphaGreen <= 0 && sourceAlphaBlue <= 0) {
            return destination
        }
        val inverseDestinationAlpha = 255 - destinationAlpha
        fun colorChannel(shift: Int): Int {
            val sourceAlphaChannel = when (shift) {
                16 -> sourceAlphaRed
                8 -> sourceAlphaGreen
                else -> sourceAlphaBlue
            }
            val inverseSourceAlphaChannel = 255 - sourceAlphaChannel
            val sourceChannel = (source ushr shift) and 0xff
            val destinationChannel = (destination ushr shift) and 0xff
            return (
                sourceChannel * sourceAlphaChannel * inverseDestinationAlpha +
                    destinationChannel * inverseSourceAlphaChannel * destinationAlpha +
                    32_512
                ) / 65_025
        }
        fun alphaChannel(): Int {
            val inverseSourceAlphaMasked = 255 - sourceAlphaMasked
            return (
                sourceAlphaMasked * inverseDestinationAlpha +
                    destinationAlpha * inverseSourceAlphaMasked +
                    127
                ) / 255
        }
        return (alphaChannel() shl 24) or (colorChannel(16) shl 16) or (colorChannel(8) shl 8) or colorChannel(0)
    }

    private fun clearWithMask(destination: Int, maskAlpha: Int): Int {
        if (maskAlpha >= 255) return 0
        val inverse = 255 - maskAlpha
        fun channel(shift: Int): Int = (((destination ushr shift) and 0xff) * inverse + 127) / 255
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun inOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val destinationAlpha = (destination ushr 24) and 0xff
        if (maskAlpha <= 0 || destinationAlpha <= 0) return 0
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        fun channel(shift: Int): Int {
            val sourceChannel = if (shift == 24) {
                sourceAlphaMasked
            } else {
                ((source ushr shift) and 0xff) * sourceAlphaMasked
            }
            return if (shift == 24) {
                (sourceChannel * destinationAlpha + 127) / 255
            } else {
                (sourceChannel * destinationAlpha + 32_512) / 65_025
            }
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun outOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val inverseDestinationAlpha = 255 - ((destination ushr 24) and 0xff)
        if (maskAlpha <= 0 || inverseDestinationAlpha <= 0) return 0
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        fun channel(shift: Int): Int {
            val sourceChannel = if (shift == 24) {
                sourceAlphaMasked
            } else {
                ((source ushr shift) and 0xff) * sourceAlphaMasked
            }
            return if (shift == 24) {
                (sourceChannel * inverseDestinationAlpha + 127) / 255
            } else {
                (sourceChannel * inverseDestinationAlpha + 32_512) / 65_025
            }
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun overReverseOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        val destinationAlpha = (destination ushr 24) and 0xff
        val inverseDestinationAlpha = 255 - destinationAlpha
        if (sourceAlphaMasked <= 0 || inverseDestinationAlpha <= 0) return destination
        fun channel(shift: Int): Int {
            val sourceChannel = if (shift == 24) {
                sourceAlphaMasked
            } else {
                ((source ushr shift) and 0xff) * sourceAlphaMasked
            }
            val sourceContribution = if (shift == 24) {
                (sourceChannel * inverseDestinationAlpha + 127) / 255
            } else {
                (sourceChannel * inverseDestinationAlpha + 32_512) / 65_025
            }
            return (((destination ushr shift) and 0xff) + sourceContribution).coerceAtMost(255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun disjointOverOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        val destinationAlpha = (destination ushr 24) and 0xff
        val destinationFactor = disjointOverDestinationFactor(sourceAlphaMasked, destinationAlpha)
        fun channel(shift: Int): Int {
            val sourceContribution = if (shift == 24) {
                sourceAlphaMasked
            } else {
                (((source ushr shift) and 0xff) * sourceAlphaMasked + 127) / 255
            }
            val destinationContribution = if (shift == 24) {
                (destinationAlpha * destinationFactor + 127) / 255
            } else {
                (((destination ushr shift) and 0xff) * destinationFactor + 127) / 255
            }
            return (sourceContribution + destinationContribution).coerceAtMost(255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun disjointOverDestinationFactor(sourceAlpha: Int, destinationAlpha: Int): Int {
        if (destinationAlpha <= 0) return 255
        val remainingAlpha = 255 - sourceAlpha
        if (remainingAlpha >= destinationAlpha) return 255
        if (remainingAlpha <= 0) return 0
        return ((remainingAlpha * 255 + destinationAlpha / 2) / destinationAlpha).coerceIn(0, 255)
    }

    private fun inReverseOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val destinationAlpha = (destination ushr 24) and 0xff
        if (maskAlpha <= 0 || destinationAlpha <= 0) return 0
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        fun channel(shift: Int): Int {
            val destinationChannel = if (shift == 24) {
                destinationAlpha
            } else {
                ((destination ushr shift) and 0xff) * destinationAlpha
            }
            return if (shift == 24) {
                (destinationChannel * sourceAlphaMasked + 127) / 255
            } else {
                (destinationChannel * sourceAlphaMasked + 32_512) / 65_025
            }
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun outReverseOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val destinationAlpha = (destination ushr 24) and 0xff
        if (maskAlpha <= 0) return destination
        if (destinationAlpha <= 0) return 0
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        val inverseSourceAlphaMasked = 255 - sourceAlphaMasked
        if (inverseSourceAlphaMasked <= 0) return 0
        fun channel(shift: Int): Int {
            val destinationChannel = if (shift == 24) {
                destinationAlpha
            } else {
                ((destination ushr shift) and 0xff) * destinationAlpha
            }
            return if (shift == 24) {
                (destinationChannel * inverseSourceAlphaMasked + 127) / 255
            } else {
                (destinationChannel * inverseSourceAlphaMasked + 32_512) / 65_025
            }
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun atopOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val destinationAlpha = (destination ushr 24) and 0xff
        if (destinationAlpha <= 0) return 0
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        if (sourceAlphaMasked <= 0) return destination
        val inverseSourceAlphaMasked = 255 - sourceAlphaMasked
        fun channel(shift: Int): Int {
            val destinationChannel = if (shift == 24) {
                destinationAlpha
            } else {
                ((destination ushr shift) and 0xff) * destinationAlpha
            }
            val sourceChannel = if (shift == 24) {
                sourceAlphaMasked
            } else {
                ((source ushr shift) and 0xff) * sourceAlphaMasked
            }
            return if (shift == 24) {
                (sourceChannel * destinationAlpha + destinationChannel * inverseSourceAlphaMasked + 127) / 255
            } else {
                (sourceChannel * destinationAlpha + destinationChannel * inverseSourceAlphaMasked + 32_512) / 65_025
            }
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun atopReverseOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        if (maskAlpha <= 0) return 0
        val destinationAlpha = (destination ushr 24) and 0xff
        val inverseDestinationAlpha = 255 - destinationAlpha
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        if (sourceAlphaMasked <= 0) return 0
        fun channel(shift: Int): Int {
            val destinationChannel = if (shift == 24) {
                destinationAlpha
            } else {
                ((destination ushr shift) and 0xff) * destinationAlpha
            }
            val sourceChannel = if (shift == 24) {
                sourceAlphaMasked
            } else {
                ((source ushr shift) and 0xff) * sourceAlphaMasked
            }
            return if (shift == 24) {
                (destinationChannel * sourceAlphaMasked + sourceChannel * inverseDestinationAlpha + 127) / 255
            } else {
                (destinationChannel * sourceAlphaMasked + sourceChannel * inverseDestinationAlpha + 32_512) / 65_025
            }
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun xorOperator(source: Int, destination: Int, maskAlpha: Int): Int {
        val destinationAlpha = (destination ushr 24) and 0xff
        val inverseDestinationAlpha = 255 - destinationAlpha
        val sourceAlphaMasked = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        if (sourceAlphaMasked <= 0) return destination
        val inverseSourceAlphaMasked = 255 - sourceAlphaMasked
        fun channel(shift: Int): Int {
            val destinationChannel = if (shift == 24) {
                destinationAlpha
            } else {
                ((destination ushr shift) and 0xff) * destinationAlpha
            }
            val sourceChannel = if (shift == 24) {
                sourceAlphaMasked
            } else {
                ((source ushr shift) and 0xff) * sourceAlphaMasked
            }
            return if (shift == 24) {
                (sourceChannel * inverseDestinationAlpha + destinationChannel * inverseSourceAlphaMasked + 127) / 255
            } else {
                (sourceChannel * inverseDestinationAlpha + destinationChannel * inverseSourceAlphaMasked + 32_512) / 65_025
            }
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun add(source: Int, destination: Int, maskAlpha: Int): Int {
        val sourceAlpha = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        if (sourceAlpha <= 0) return destination
        fun channel(shift: Int): Int {
            val sourceChannel = if (shift == 24) {
                sourceAlpha
            } else {
                (((source ushr shift) and 0xff) * sourceAlpha + 127) / 255
            }
            return (((destination ushr shift) and 0xff) + sourceChannel).coerceAtMost(255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun saturate(source: Int, destination: Int, maskAlpha: Int): Int {
        val sourceAlpha = (((source ushr 24) and 0xff) * maskAlpha + 127) / 255
        if (sourceAlpha <= 0) return destination
        val destinationAlpha = (destination ushr 24) and 0xff
        val contributionAlpha = minOf(sourceAlpha, 255 - destinationAlpha)
        if (contributionAlpha <= 0) return destination
        fun channel(shift: Int): Int {
            val sourceChannel = if (shift == 24) {
                contributionAlpha
            } else {
                (((source ushr shift) and 0xff) * contributionAlpha + 127) / 255
            }
            return (((destination ushr shift) and 0xff) + sourceChannel).coerceAtMost(255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun sampledMaskAlpha(
        mask: XFramebuffer?,
        maskAlphaAt: ((x: Int, y: Int) -> Int?)?,
        x: Int,
        y: Int,
    ): Int? {
        if (maskAlphaAt != null) return maskAlphaAt(x, y)
        return mask?.alphaAt(x, y) ?: 255
    }

    private fun Int.returnsDestinationResultImage(): Boolean =
        this == XRender.OpDst ||
            this == XRender.OpDisjointDst ||
            this == XRender.OpDisjointOver ||
            this == XRender.OpOverReverse ||
            this == XRender.OpInReverse ||
            this == XRender.OpOutReverse ||
            this == XRender.OpAtop ||
            this == XRender.OpAtopReverse ||
            this == XRender.OpXor ||
            this == XRender.OpSaturate

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
        clipRectangles: List<XRectangleCommand>?,
        function: Int,
        planeMask: Int,
        sourceAt: (x: Int, y: Int) -> Int?,
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
                    painted = fillScanlineSpan(y, previous, x, clipRectangles, function, planeMask, sourceAt) || painted
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
        clipRectangles: List<XRectangleCommand>?,
        function: Int,
        planeMask: Int,
        sourceAt: (x: Int, y: Int) -> Int?,
    ): Boolean {
        val left = ceil(leftIntersection).toInt()
        val right = ceil(rightIntersection).toInt()
        var painted = false
        for (x in left until right) {
            if (x !in 0 until width || y !in 0 until height) continue
            if (!insideClip(x, y, clipRectangles)) continue
            val index = y * width + x
            val source = sourceAt(x, y) ?: continue
            pixels[index] = corePixel(source = source, destination = pixels[index], function = function, planeMask = planeMask)
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

    private fun insideClip(x: Int, y: Int, clipRectangles: List<XRectangleCommand>?, clipMask: XClipMask?): Boolean =
        insideClip(x, y, clipRectangles) && (clipMask == null || clipMask(x, y))

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

    private fun XFixedQuad.toDoubleQuad(): DoubleQuad =
        DoubleQuad(
            p1 = p1.toDoublePoint(),
            p2 = p2.toDoublePoint(),
            p3 = p3.toDoublePoint(),
            p4 = p4.toDoublePoint(),
        )

    private fun XFixedPoint.toDoublePoint(): DoublePoint =
        DoublePoint(x.fixedToDouble(), y.fixedToDouble())

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

    private data class DoublePoint(
        val x: Double,
        val y: Double,
    )

    private data class DoubleQuad(
        val p1: DoublePoint,
        val p2: DoublePoint,
        val p3: DoublePoint,
        val p4: DoublePoint,
    ) {
        val points: List<DoublePoint> get() = listOf(p1, p2, p3, p4)
    }

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
