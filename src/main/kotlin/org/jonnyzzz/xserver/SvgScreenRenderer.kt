package org.jonnyzzz.xserver

internal object SvgScreenRenderer {
    fun html(snapshot: XScreenSnapshot): String =
        XmlDom.html {
            attributes("lang" to "en")
            comment(RenderCredit.Text)
            element("head") {
                element("meta", "charset" to "utf-8")
                element("meta", "name" to "viewport", "content" to "width=device-width, initial-scale=1")
                element("meta", "http-equiv" to "refresh", "content" to "1")
                element("title") { text("X screen") }
                element("style") { text(screenCss(snapshot)) }
                element("script") { text(inputScript()) }
            }
            element("body") {
                element("main") {
                    element("section", "class" to "window-map") {
                        element("h1") { text("Windows map") }
                        svgElement(
                            "svg",
                            "class" to "screen-map-svg",
                            "data-input-surface" to "true",
                            "data-origin-x" to 0,
                            "data-origin-y" to 0,
                            "viewBox" to "0 0 ${snapshot.width} ${snapshot.height}",
                            "role" to "img",
                            "aria-label" to "X screen",
                        ) {
                            renderSvgContent(this, snapshot)
                        }
                    }
                    element("section", "class" to "window-contents") {
                        element("h1") { text("Window contents") }
                        renderWindowPreviews(this, snapshot)
                    }
                    element("section", "class" to "offscreen-surfaces") {
                        element("h1") { text("Offscreen surfaces") }
                        renderPixmapPreviews(this, snapshot)
                    }
                    renderStatePanel(this, snapshot)
                    renderFooter(this)
                }
            }
        }

    fun svg(snapshot: XScreenSnapshot): String =
        XmlDom.svg {
            attributes(
                "viewBox" to "0 0 ${snapshot.width} ${snapshot.height}",
                "role" to "img",
                "aria-label" to "X screen",
            )
            renderSvgContent(this, snapshot)
        }

    fun json(snapshot: XScreenSnapshot): String =
        buildString {
            append("""{"width":${snapshot.width},"height":${snapshot.height},"dpi":${snapshot.dpi},"widthMillimeters":${snapshot.widthMillimeters},"heightMillimeters":${snapshot.heightMillimeters},"pointer":{"x":${snapshot.pointer.x},"y":${snapshot.pointer.y},"mask":${snapshot.pointer.mask},"logicalButtonsDown":[""")
            snapshot.pointer.logicalButtonsDown.forEachIndexed { index, button ->
                if (index > 0) append(',')
                append(button)
            }
            append("""],"window":"${snapshot.pointer.windowIdHex}"},"keyboardState":{"modifierMask":${snapshot.keyboardState.modifierMask},"keycodesDown":[""")
            snapshot.keyboardState.keycodesDown.forEachIndexed { index, keycode ->
                if (index > 0) append(',')
                append(keycode)
            }
            append("""]},"fontPath":[""")
            snapshot.fontPath.forEachIndexed { index, path ->
                if (index > 0) append(',')
                append('"').append(escapeJson(path)).append('"')
            }
            append("""],"keyboardMapping":{"keysymsPerKeycode":${snapshot.keyboardMapping.keysymsPerKeycode},"keycodes":[""")
            snapshot.keyboardMapping.keycodes.forEachIndexed { index, keycode ->
                if (index > 0) append(',')
                append("""{"keycode":${keycode.keycode},"keysyms":[""")
                keycode.keysymHexes.forEachIndexed { keysymIndex, keysym ->
                    if (keysymIndex > 0) append(',')
                    append('"').append(keysym).append('"')
                }
                append("""]}""")
            }
            append("""]},"keyboardControl":{"keyClickPercent":${snapshot.keyboardControl.keyClickPercent},"bellPercent":${snapshot.keyboardControl.bellPercent},"bellPitch":${snapshot.keyboardControl.bellPitch},"bellDuration":${snapshot.keyboardControl.bellDuration},"ledMask":"${snapshot.keyboardControl.ledMaskHex}","globalAutoRepeat":${snapshot.keyboardControl.globalAutoRepeat},"autoRepeats":[""")
            snapshot.keyboardControl.autoRepeatsHex.forEachIndexed { index, value ->
                if (index > 0) append(',')
                append('"').append(value).append('"')
            }
            append("""]},"windows":[""")
            snapshot.windows.forEachIndexed { index, window ->
                if (index > 0) append(',')
                append('{')
                append(""""id":"${window.idHex}","parent":"${window.parentIdHex}","x":${window.x},"y":${window.y},"localX":${window.localX},"localY":${window.localY},"width":${window.width},"height":${window.height},"visibleX":${window.visibleX},"visibleY":${window.visibleY},"visibleWidth":${window.visibleWidth},"visibleHeight":${window.visibleHeight},"mapped":${window.mapped},"class":"${window.className}","depth":${window.depth},"visual":"${window.visualHex}","backgroundPixel":${window.backgroundPixel},"backgroundPixmap":""")
                window.backgroundPixmapIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","borderPixel":${window.borderPixel},"borderPixmap":""")
                window.borderPixmapIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","bitGravity":${window.bitGravity},"winGravity":${window.winGravity},"backingStore":${window.backingStore},"backingPlanes":${window.backingPlanes},"backingPixel":${window.backingPixel},"saveUnder":${window.saveUnder},"overrideRedirect":${window.overrideRedirect},"colormap":""")
                window.colormapIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","cursor":""")
                window.cursorIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","boundingShape":""")
                appendRectangles(window.boundingShape)
                append(""","clipShape":""")
                appendRectangles(window.clipShape)
                append(""","inputShape":""")
                appendRectangles(window.inputShape)
                append(""","renderShape":""")
                appendRectangles(window.renderShape)
                append('}')
            }
            append("""],"pixmaps":[""")
            snapshot.pixmaps.forEachIndexed { index, pixmap ->
                if (index > 0) append(',')
                append('{')
                append(""""id":"${pixmap.idHex}","width":${pixmap.width},"height":${pixmap.height},"depth":${pixmap.depth},"painted":${pixmap.painted},"pictures":[""")
                pixmap.pictureIdHexes.forEachIndexed { pictureIndex, pictureId ->
                    if (pictureIndex > 0) append(',')
                    append('"').append(pictureId).append('"')
                }
                append("""],"matchingWindows":[""")
                pixmap.matchingWindowIdHexes.forEachIndexed { windowIndex, windowId ->
                    if (windowIndex > 0) append(',')
                    append('"').append(windowId).append('"')
                }
                append("""],"retainedPicture":""")
                pixmap.retainedPictureIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append('}')
            }
            append("""],"cursors":[""")
            snapshot.cursors.forEachIndexed { index, cursor ->
                if (index > 0) append(',')
                append('{')
                append(""""id":"${cursor.idHex}","kind":"${escapeJson(cursor.kind)}","sourcePixmap":""")
                cursor.sourcePixmapIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","maskPixmap":""")
                cursor.maskPixmapIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","sourceFont":""")
                cursor.sourceFontIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","maskFont":""")
                cursor.maskFontIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","sourceChar":""")
                cursor.sourceChar?.let { append(it) } ?: append("null")
                append(""","maskChar":""")
                cursor.maskChar?.let { append(it) } ?: append("null")
                append(""","sourcePicture":""")
                cursor.sourcePictureIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","nameAtom":""")
                cursor.nameAtomHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","name":""")
                cursor.name?.let { append('"').append(escapeJson(it)).append('"') } ?: append("null")
                append(""","animation":[""")
                cursor.animationElements.forEachIndexed { elementIndex, element ->
                    if (elementIndex > 0) append(',')
                    append("""{"cursor":"${element.cursorIdHex}","delay":${element.delayMilliseconds}}""")
                }
                append("""],"hotspotX":""")
                cursor.hotspotX?.let { append(it) } ?: append("null")
                append(""","hotspotY":""")
                cursor.hotspotY?.let { append(it) } ?: append("null")
                append(""","foreground":"${cursor.foregroundHex}","background":"${cursor.backgroundHex}"}""")
            }
            append("""],"glxContexts":[""")
            snapshot.glxContexts.forEachIndexed { index, context ->
                if (index > 0) append(',')
                append("""{"id":"${context.idHex}","fbConfig":"${context.fbConfigIdHex}","screen":${context.screen},"renderType":"${context.renderTypeHex}","direct":${context.direct},"currentDrawDrawable":""")
                context.currentDrawDrawableIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","currentReadDrawable":""")
                context.currentReadDrawableIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append('}')
            }
            append("""],"glxPixmaps":[""")
            snapshot.glxPixmaps.forEachIndexed { index, pixmap ->
                if (index > 0) append(',')
                append("""{"id":"${pixmap.idHex}","pixmap":"${pixmap.pixmapIdHex}","visual":"${pixmap.visualIdHex}","fbConfig":"${pixmap.fbConfigIdHex}","screen":${pixmap.screen},"width":${pixmap.width},"height":${pixmap.height},"depth":${pixmap.depth},"eventMask":${pixmap.eventMask},"textureTarget":${pixmap.textureTarget}}""")
            }
            append("""],"glxWindows":[""")
            snapshot.glxWindows.forEachIndexed { index, window ->
                if (index > 0) append(',')
                append("""{"id":"${window.idHex}","window":"${window.windowIdHex}","fbConfig":"${window.fbConfigIdHex}","screen":${window.screen},"width":${window.width},"height":${window.height},"eventMask":${window.eventMask}}""")
            }
            append("""],"glxPbuffers":[""")
            snapshot.glxPbuffers.forEachIndexed { index, pbuffer ->
                if (index > 0) append(',')
                append("""{"id":"${pbuffer.idHex}","fbConfig":"${pbuffer.fbConfigIdHex}","screen":${pbuffer.screen},"width":${pbuffer.width},"height":${pbuffer.height},"eventMask":${pbuffer.eventMask}}""")
            }
            append("""],"drawings":${snapshot.drawings.size},"drawingCommands":[""")
            snapshot.drawings.forEachIndexed { index, drawing ->
                if (index > 0) append(',')
                append('{')
                append(
                    """"drawable":"0x${drawing.drawableId.toUInt().toString(16)}","kind":"${drawing.kind.name}","framebufferBacked":${drawing.framebufferBacked},"sourceDrawable":""",
                )
                drawing.sourceDrawableId?.let { append("\"0x${it.toUInt().toString(16)}\"") } ?: append("null")
                append(
                    ""","foreground":"0x${drawing.foreground.toUInt().toString(16)}","background":"0x${drawing.background.toUInt().toString(16)}","lineWidth":${drawing.lineWidth},"lineStyle":${drawing.lineStyle},"capStyle":${drawing.capStyle},"joinStyle":${drawing.joinStyle},"dashOffset":${drawing.dashOffset},"dashes":[""",
                )
                drawing.dashes.forEachIndexed { dashIndex, dash ->
                    if (dashIndex > 0) append(',')
                    append(dash)
                }
                append("""],"fillStyle":${drawing.fillStyle},"fillRule":${drawing.fillRule},"tilePixmap":""")
                drawing.tilePixmapId?.let { append("\"0x${it.toUInt().toString(16)}\"") } ?: append("null")
                append(""","stipplePixmap":""")
                drawing.stipplePixmapId?.let { append("\"0x${it.toUInt().toString(16)}\"") } ?: append("null")
                append(""","tileStippleOrigin":[${drawing.tileStippleXOrigin},${drawing.tileStippleYOrigin}],"arcMode":${drawing.arcMode},"rectangles":[""")
                drawing.rectangles.forEachIndexed { rectangleIndex, rectangle ->
                    if (rectangleIndex > 0) append(',')
                    append("""{"x":${rectangle.x},"y":${rectangle.y},"width":${rectangle.width},"height":${rectangle.height}}""")
                }
                append("""],"points":[""")
                drawing.points.forEachIndexed { pointIndex, point ->
                    if (pointIndex > 0) append(',')
                    append("""{"x":${point.x},"y":${point.y}}""")
                }
                append("""],"arcs":[""")
                drawing.arcs.forEachIndexed { arcIndex, arc ->
                    if (arcIndex > 0) append(',')
                    append("""{"x":${arc.x},"y":${arc.y},"width":${arc.width},"height":${arc.height},"angle1":${arc.angle1},"angle2":${arc.angle2}}""")
                }
                append("""]}""")
            }
            append("""],"renderOperations":${snapshot.renderOperations.size},"renderPictures":[""")
            snapshot.renderPictures.forEachIndexed { index, picture ->
                if (index > 0) append(',')
                append('{')
                append(""""id":"${picture.idHex}","drawable":"${picture.drawableIdHex}","kind":"${escapeJson(picture.drawableKind)}","format":${picture.format},"repeat":"${picture.repeatName}","alphaMap":"${picture.alphaMapHex}","alphaOrigin":[${picture.alphaXOrigin},${picture.alphaYOrigin}],"clipOrigin":[${picture.clipXOrigin},${picture.clipYOrigin}],"clipMask":"${picture.clipMaskHex}","clipRectangles":${picture.clipRectangles},"graphicsExposure":${picture.graphicsExposure},"subwindowMode":${picture.subwindowMode},"polyEdge":${picture.polyEdge},"polyMode":${picture.polyMode},"dither":"${picture.ditherHex}","componentAlpha":${picture.componentAlpha},"transform":[""")
                picture.transformHex.forEachIndexed { transformIndex, value ->
                    if (transformIndex > 0) append(',')
                    append('"').append(value).append('"')
                }
                append("""]""")
                if (picture.filterName != null) {
                    append(""","filter":"${escapeJson(picture.filterName)}","filterValues":[""")
                    picture.filterValueHex.forEachIndexed { valueIndex, value ->
                        if (valueIndex > 0) append(',')
                        append('"').append(value).append('"')
                    }
                    append(']')
                }
                picture.linearGradient?.let { gradient ->
                    append(""","linearGradient":{"p1":"${gradient.p1Hex}","p2":"${gradient.p2Hex}","stops":[""")
                    gradient.stopHex.forEachIndexed { stopIndex, value ->
                        if (stopIndex > 0) append(',')
                        append('"').append(value).append('"')
                    }
                    append("""],"colors":[""")
                    gradient.colorHex.forEachIndexed { colorIndex, value ->
                        if (colorIndex > 0) append(',')
                        append('"').append(value).append('"')
                    }
                    append("""]}""")
                }
                picture.radialGradient?.let { gradient ->
                    append(""","radialGradient":{"inner":"${gradient.innerHex}","outer":"${gradient.outerHex}","stops":[""")
                    gradient.stopHex.forEachIndexed { stopIndex, value ->
                        if (stopIndex > 0) append(',')
                        append('"').append(value).append('"')
                    }
                    append("""],"colors":[""")
                    gradient.colorHex.forEachIndexed { colorIndex, value ->
                        if (colorIndex > 0) append(',')
                        append('"').append(value).append('"')
                    }
                    append("""]}""")
                }
                picture.conicalGradient?.let { gradient ->
                    append(""","conicalGradient":{"center":"${gradient.centerHex}","angle":"${gradient.angleHex}","stops":[""")
                    gradient.stopHex.forEachIndexed { stopIndex, value ->
                        if (stopIndex > 0) append(',')
                        append('"').append(value).append('"')
                    }
                    append("""],"colors":[""")
                    gradient.colorHex.forEachIndexed { colorIndex, value ->
                        if (colorIndex > 0) append(',')
                        append('"').append(value).append('"')
                    }
                    append("""]}""")
                }
                append('}')
            }
            append("""],"inputOperations":[""")
            snapshot.inputOperations.forEachIndexed { index, operation ->
                if (index > 0) append(',')
                append('{')
                append(""""id":${operation.id},"kind":"${escapeJson(operation.kind)}","x":${operation.x},"y":${operation.y},"button":"${escapeJson(operation.button)}","targetWindow":""")
                operation.targetWindowIdHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","deliveredEvents":${operation.deliveredEvents}""")
                append('}')
            }
            append("""],"inputControlOperations":[""")
            snapshot.inputControlOperations.forEachIndexed { index, operation ->
                if (index > 0) append(',')
                append('{')
                append(""""id":${operation.id},"operation":"${escapeJson(operation.operation)}","mode":${operation.mode},"modeName":"${escapeJson(operation.modeName)}","time":${operation.timeUnsigned}""")
                append('}')
            }
            append("""],"inputGrabs":[""")
            snapshot.inputGrabs.forEachIndexed { index, grab ->
                if (index > 0) append(',')
                append('{')
                append(""""kind":"${escapeJson(grab.kind)}","window":"${grab.windowIdHex}","ownerEvents":${grab.ownerEvents},"eventMask":"${grab.eventMaskHex}","pointerMode":${grab.pointerMode},"keyboardMode":${grab.keyboardMode},"confineTo":""")
                grab.confineToHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","cursor":""")
                grab.cursorHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","time":${grab.timeUnsigned}""")
                append('}')
            }
            append("""],"passiveButtonGrabs":[""")
            snapshot.passiveButtonGrabs.forEachIndexed { index, grab ->
                if (index > 0) append(',')
                append('{')
                append(""""window":"${grab.windowIdHex}","ownerEvents":${grab.ownerEvents},"eventMask":"${grab.eventMaskHex}","pointerMode":${grab.pointerMode},"keyboardMode":${grab.keyboardMode},"confineTo":""")
                grab.confineToHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","cursor":""")
                grab.cursorHex?.let { append('"').append(it).append('"') } ?: append("null")
                append(""","button":${grab.button},"buttonName":"${escapeJson(grab.buttonName)}","modifiers":${grab.modifiers},"modifiersName":"${escapeJson(grab.modifiersName)}","releasedCombinations":[""")
                grab.releasedCombinations.forEachIndexed { combinationIndex, combination ->
                    if (combinationIndex > 0) append(',')
                    append("""{"button":${combination.button},"buttonName":"${escapeJson(combination.buttonName)}","modifiers":${combination.modifiers},"modifiersName":"${escapeJson(combination.modifiersName)}"}""")
                }
                append(']')
                append('}')
            }
            append("""],"passiveKeyGrabs":[""")
            snapshot.passiveKeyGrabs.forEachIndexed { index, grab ->
                if (index > 0) append(',')
                append('{')
                append(""""window":"${grab.windowIdHex}","ownerEvents":${grab.ownerEvents},"key":${grab.key},"keyName":"${escapeJson(grab.keyName)}","modifiers":${grab.modifiers},"modifiersName":"${escapeJson(grab.modifiersName)}","pointerMode":${grab.pointerMode},"keyboardMode":${grab.keyboardMode},"releasedCombinations":[""")
                grab.releasedCombinations.forEachIndexed { combinationIndex, combination ->
                    if (combinationIndex > 0) append(',')
                    append("""{"key":${combination.key},"keyName":"${escapeJson(combination.keyName)}","modifiers":${combination.modifiers},"modifiersName":"${escapeJson(combination.modifiersName)}"}""")
                }
                append(']')
                append('}')
            }
            append("""],"serverGrabbed":${snapshot.serverGrabbed}}""")
        }

    private fun screenCss(snapshot: XScreenSnapshot): String =
        """
        html, body { margin: 0; min-height: 100%; background: #15171c; color: #e7e9ee; font-family: system-ui, sans-serif; }
        main { display: grid; grid-template-columns: minmax(180px, 21vw) minmax(640px, 1fr); align-items: start; min-height: 100vh; }
        .window-map { padding: 18px; position: sticky; top: 0; }
        .window-map svg { width: 100%; height: auto; box-shadow: 0 0 0 1px #3b4252; cursor: crosshair; }
        .window-contents { border-left: 1px solid #303642; padding: 18px; background: #15171c; }
        .state { grid-column: 1 / -1; border-top: 1px solid #303642; padding: 18px; background: #111318; }
        .state-columns { display: grid; grid-template-columns: minmax(260px, 360px) minmax(0, 1fr); gap: 20px; }
        h1, h2 { font-size: 16px; margin: 0 0 12px; }
        dl { display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; margin: 0 0 18px; font-size: 13px; }
        dt { color: #aab2c0; }
        dd { margin: 0; overflow-wrap: anywhere; }
        .input-log { margin: 0; padding-left: 20px; color: #c8d0df; font: 12px/1.45 monospace; }
        code { color: #d4dcff; }
        .preview-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px; }
        .preview { border: 1px solid #303642; background: #111318; padding: 10px; }
        .preview header { color: #c8d0df; font: 13px/1.35 monospace; margin-bottom: 8px; overflow-wrap: anywhere; }
        .preview svg { width: min(100%, 1100px); height: auto; shape-rendering: crispEdges; cursor: crosshair; }
        .preview image { image-rendering: auto; }
        .primary-surface { margin-bottom: 10px; }
        .primary-surface > header { color: #e7e9ee; }
        .primary-surface svg { width: min(100%, 1100px); max-height: none; }
        .offscreen-surfaces { grid-column: 1 / -1; border-top: 1px solid #303642; padding: 18px; background: #15171c; }
        .surface-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 18px; }
        .surface { border: 1px solid #303642; background: #111318; padding: 10px; }
        .surface header { color: #c8d0df; font: 13px/1.35 monospace; margin-bottom: 8px; overflow-wrap: anywhere; }
        .surface svg { width: 100%; max-height: 760px; background: #f8fafc; shape-rendering: crispEdges; }
        .surface image { image-rendering: auto; }
        footer { grid-column: 1 / -1; padding: 10px 18px; border-top: 1px solid #303642; color: #aab2c0; font-size: 12px; }
        @media (max-width: 960px) {
          main { display: block; }
          .window-map { position: static; }
          .window-contents { border-left: 0; border-top: 1px solid #303642; }
          .state-columns { grid-template-columns: 1fr; }
        }
        """.trimIndent()

    private fun inputScript(): String =
        """
        document.addEventListener('DOMContentLoaded', () => {
          document.querySelectorAll('svg[data-input-surface="true"]').forEach((svg) => {
            svg.addEventListener('click', (event) => {
              const point = svg.createSVGPoint();
              point.x = event.clientX;
              point.y = event.clientY;
              const ctm = svg.getScreenCTM();
              if (!ctm) return;
              const local = point.matrixTransform(ctm.inverse());
              const x = Math.round(local.x + Number(svg.dataset.originX || 0));
              const y = Math.round(local.y + Number(svg.dataset.originY || 0));
              fetch('/input/click', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({ x, y, button: 'left' }).toString(),
              }).catch(() => {});
            });
          });
        });
        """.trimIndent()

    private fun XmlDom.definition(term: String, description: String) {
        element("dt") { text(term) }
        element("dd") { text(description) }
    }

    private fun renderFooter(builder: XmlDom) {
        builder.element("footer") {
            text("by ")
            element("a", "href" to "https://github.com/jonnyzzz/x") { text("@jonnyzzz") }
            text(" ")
            element("a", "href" to "https://linkedin.com/in/jonnyzzz") { text("https://linkedin.com/in/jonnyzzz") }
        }
    }

    private fun renderSvgContent(builder: XmlDom, snapshot: XScreenSnapshot) {
        val visibleWindows = snapshot.windows.filter {
            it.windowClass == XWindowClass.InputOutput &&
                it.mapped &&
                it.id != X11Ids.RootWindow &&
                it.visibleWidth > 0 &&
                it.visibleHeight > 0
        }
        val windowsById = snapshot.windows.associateBy { it.id }
        with(builder) {
            comment(RenderCredit.Text)
            svgElement("defs") {
                for (window in visibleWindows) {
                    svgElement("clipPath", "id" to clipId("screen", window), "clipPathUnits" to "userSpaceOnUse") {
                        renderClipRectangles(
                            builder = this,
                            window = window,
                            windowsById = windowsById,
                            originX = 0,
                            originY = 0,
                            base = XRectangleCommand(window.visibleX, window.visibleY, window.visibleWidth, window.visibleHeight),
                        )
                    }
                }
            }
            svgElement("g", "font-family" to "monospace", "font-size" to 32) {
                visibleWindows.forEachIndexed { index, window ->
                    val color = palette[index % palette.size]
                    val strokeWidth = if (window.focused) 8 else 4
                    svgElement("g", "clip-path" to "url(#${clipId("screen", window)})") {
                        svgElement(
                            "rect",
                            "data-window-id" to window.idHex,
                            "x" to window.visibleX,
                            "y" to window.visibleY,
                            "width" to window.visibleWidth,
                            "height" to window.visibleHeight,
                            "fill" to (windowBackgroundFill(window, snapshot.windows) ?: "none"),
                            "stroke" to color,
                            "stroke-width" to strokeWidth,
                        )
                        renderFramebuffers(this, snapshot, listOf(window), originX = 0, originY = 0, clipPrefix = "screen")
                        renderDrawings(this, snapshot, clipPrefix = "screen", drawableIds = setOf(window.id))
                        svgElement(
                            "rect",
                            "x" to window.visibleX,
                            "y" to window.visibleY,
                            "width" to window.visibleWidth,
                            "height" to minOf(46, window.visibleHeight),
                            "fill" to color,
                            "fill-opacity" to "0.85",
                        )
                        svgElement(
                            "text",
                            "x" to window.visibleX + 10,
                            "y" to window.visibleY + minOf(34, window.visibleHeight),
                            "fill" to "#111318",
                        ) {
                            text(window.label)
                        }
                    }
                }
                for (overlap in snapshot.overlaps) {
                    svgElement(
                        "rect",
                        "x" to overlap.x,
                        "y" to overlap.y,
                        "width" to overlap.width,
                        "height" to overlap.height,
                        "fill" to "#ff5c7a",
                        "fill-opacity" to "0.2",
                        "stroke" to "#ff5c7a",
                        "stroke-dasharray" to "4 3",
                    )
                }
                svgElement(
                    "text",
                    "x" to snapshot.width - 8,
                    "y" to snapshot.height - 8,
                    "fill" to "#aab2c0",
                    "text-anchor" to "end",
                ) {
                    text(RenderCredit.Text)
                }
            }
        }
    }

    private fun renderWindowPreviews(builder: XmlDom, snapshot: XScreenSnapshot) {
        val topWindows = snapshot.windows
            .filter {
                it.mapped &&
                    it.windowClass == XWindowClass.InputOutput &&
                    it.parentId == X11Ids.RootWindow &&
                    it.id != X11Ids.RootWindow &&
                    it.width >= 64 &&
                    it.height >= 64 &&
                    it.visibleWidth > 0 &&
                    it.visibleHeight > 0
            }
            .sortedWith(compareByDescending<XWindowSnapshot> { it.focused }.thenBy { it.stackingIndex })
        builder.element("div", "class" to "preview-grid") {
            for (window in topWindows) {
                element("article", "class" to "preview") {
                    element("header") {
                        element("strong") { text(window.displayTitle()) }
                        text(" ")
                        element("span") { text(window.idHex) }
                        text(" ")
                        text("${window.width}x${window.height}")
                        if (window.focused) text(" focused")
                        val overlapCount = snapshot.overlaps.count { it.lowerWindowId == window.id || it.upperWindowId == window.id }
                        if (overlapCount > 0) text(" overlaps=$overlapCount")
                    }
                    renderPrimaryPixmapPreview(this, snapshot, window)
                    renderWindowSvg(this, snapshot, window)
                    renderMatchingPixmapPreviews(this, snapshot, window)
                }
            }
        }
    }

    private fun renderPixmapPreviews(builder: XmlDom, snapshot: XScreenSnapshot) {
        val paintedPixmaps = snapshot.pixmaps
            .filter { it.painted && it.framebufferDataUri != null && it.width > 0 && it.height > 0 }
            .sortedWith(compareByDescending<XPixmapSnapshot> { it.width * it.height }.thenBy { it.id })
            .take(12)
        builder.element("div", "class" to "surface-grid") {
            if (paintedPixmaps.isEmpty()) {
                element("p") { text("No painted offscreen pixmaps.") }
            }
            for (pixmap in paintedPixmaps) {
                renderPixmapArticle(this, pixmap)
            }
        }
    }

    private fun renderPrimaryPixmapPreview(builder: XmlDom, snapshot: XScreenSnapshot, window: XWindowSnapshot) {
        val candidates = matchingPixmapCandidates(snapshot, window)
        val pixmap = candidates
            .firstOrNull { window.id in it.matchingWindowIds }
            ?: candidates.firstOrNull()
            ?: return
        builder.element("section", "class" to "primary-surface") {
            element("header") {
                element("strong") { text("Best painted surface") }
                text(" ")
                if (pixmap.retained) {
                    text("retained-picture=${pixmap.retainedPictureIdHex} drawable=${pixmap.idHex} ${pixmap.width}x${pixmap.height}")
                } else {
                    text("pixmap=${pixmap.idHex} ${pixmap.width}x${pixmap.height}")
                }
                if (pixmap.pictureIdHexes.isNotEmpty()) {
                    text(" pictures=${pixmap.pictureIdHexes.joinToString(",")}")
                }
            }
            renderPixmapSvg(this, pixmap)
        }
    }

    private fun renderMatchingPixmapPreviews(builder: XmlDom, snapshot: XScreenSnapshot, window: XWindowSnapshot) {
        val candidates = matchingPixmapCandidates(snapshot, window)
            .take(4)
        if (candidates.isEmpty()) return
        builder.element("div", "class" to "surface-grid") {
            for (pixmap in candidates) {
                renderPixmapArticle(this, pixmap)
            }
        }
    }

    private fun matchingPixmapCandidates(snapshot: XScreenSnapshot, window: XWindowSnapshot): List<XPixmapSnapshot> {
        val subtreeIds = subtreeWindows(snapshot, window).map { it.id }.toSet()
        val recentPaintByDrawable = snapshot.drawings
            .mapIndexedNotNull { index, drawing ->
                if (drawing.framebufferBacked) drawing.drawableId to index else null
            }
            .toMap()
        return snapshot.pixmaps
            .filter { pixmap ->
                pixmap.painted &&
                    pixmap.framebufferDataUri != null &&
                    pixmap.matchingWindowIds.any { it in subtreeIds }
            }
            .sortedWith(
                compareByDescending<XPixmapSnapshot> { window.id in it.matchingWindowIds }
                    .thenByDescending { it.width == window.width && it.height == window.height }
                    .thenBy { pixmap ->
                        if (window.id in pixmap.matchingWindowIds) {
                            pixmap.width.toLong() * pixmap.height.toLong() -
                                window.backingPixmapMatchWidth.toLong() * window.backingPixmapMatchHeight.toLong()
                        } else {
                            Long.MAX_VALUE
                        }
                    }
                    // If the bounded drawing log no longer contains a pixmap paint, fall back to stable id ordering.
                    .thenByDescending { recentPaintByDrawable[it.id] ?: -1 }
                    .thenBy { it.retained }
                    .thenBy { it.id },
            )
    }

    private fun renderPixmapArticle(builder: XmlDom, pixmap: XPixmapSnapshot) {
        builder.element("article", "class" to "surface") {
            element("header") {
                element("strong") {
                    text(
                        if (pixmap.retained) {
                            "Retained picture ${pixmap.retainedPictureIdHex}"
                        } else {
                            "Pixmap ${pixmap.idHex}"
                        },
                    )
                }
                if (pixmap.retained) {
                    text(" drawable=${pixmap.idHex}")
                }
                text(" ${pixmap.width}x${pixmap.height} depth=${pixmap.depth}")
                if (pixmap.pictureIdHexes.isNotEmpty()) {
                    text(" pictures=${pixmap.pictureIdHexes.joinToString(",")}")
                }
                if (pixmap.matchingWindowIdHexes.isNotEmpty()) {
                    text(" candidate-for=${pixmap.matchingWindowIdHexes.joinToString(",")}")
                }
            }
            renderPixmapSvg(this, pixmap)
        }
    }

    private fun renderStatePanel(builder: XmlDom, snapshot: XScreenSnapshot) {
        builder.element("aside", "class" to "state") {
            element("h1") { text("X server state") }
            element("div", "class" to "state-columns") {
                element("dl") {
                    definition("Screen", "${snapshot.width} x ${snapshot.height}")
                    definition("DPI", snapshot.dpi.toString())
                    definition("Physical", "${snapshot.widthMillimeters} x ${snapshot.heightMillimeters} mm")
                    definition("Windows", snapshot.windows.size.toString())
                    definition("Mapped", snapshot.windows.count { it.mapped }.toString())
                    definition("Pixmaps", snapshot.pixmaps.size.toString())
                    definition("Painted pixmaps", snapshot.pixmaps.count { it.painted }.toString())
                    definition("Drawings", snapshot.drawings.size.toString())
                }
                renderWindowList(this, snapshot)
            }
            if (snapshot.inputOperations.isNotEmpty()) {
                element("h2") { text("Input operations") }
                element("ol", "class" to "input-log") {
                    snapshot.inputOperations.takeLast(20).asReversed().forEach { operation ->
                        element("li") {
                            text("#${operation.id} ${operation.kind} ${operation.button} at ${operation.x},${operation.y} target=${operation.targetWindowIdHex ?: "none"} delivered=${operation.deliveredEvents}")
                        }
                    }
                }
            }
        }
    }

    private fun renderWindowSvg(builder: XmlDom, snapshot: XScreenSnapshot, rootWindow: XWindowSnapshot) {
        val subtree = subtreeWindows(snapshot, rootWindow)
            .filter {
                it.windowClass == XWindowClass.InputOutput &&
                    it.mapped &&
                    it.width > 0 &&
                    it.height > 0 &&
                    it.visibleWidth > 0 &&
                    it.visibleHeight > 0
            }
        val windowsById = snapshot.windows.associateBy { it.id }
        val clipPrefix = "preview-${rootWindow.idHex.drop(2)}"
        builder.svgElement(
            "svg",
            "class" to "window-preview-svg",
            "data-input-surface" to "true",
            "data-origin-x" to rootWindow.x,
            "data-origin-y" to rootWindow.y,
            "viewBox" to "0 0 ${rootWindow.width} ${rootWindow.height}",
            "role" to "img",
            "aria-label" to rootWindow.label,
        ) {
            comment(RenderCredit.Text)
            svgElement("defs") {
                for (window in subtree) {
                    svgElement("clipPath", "id" to clipId(clipPrefix, window), "clipPathUnits" to "userSpaceOnUse") {
                        renderClipRectangles(
                            builder = this,
                            window = window,
                            windowsById = windowsById,
                            originX = rootWindow.x,
                            originY = rootWindow.y,
                            base = XRectangleCommand(
                                window.visibleX - rootWindow.x,
                                window.visibleY - rootWindow.y,
                                window.visibleWidth,
                                window.visibleHeight,
                            ),
                        )
                    }
                }
            }
            windowBackgroundFill(rootWindow, snapshot.windows)?.let { fill ->
                svgElement("g", "clip-path" to "url(#${clipId(clipPrefix, rootWindow)})") {
                    svgElement("rect", "x" to 0, "y" to 0, "width" to rootWindow.width, "height" to rootWindow.height, "fill" to fill)
                }
            }
            subtree.forEach { window ->
                svgElement("g", "clip-path" to "url(#${clipId(clipPrefix, window)})") {
                    windowBackgroundFill(window, snapshot.windows)?.let { fill ->
                        svgElement(
                            "rect",
                            "class" to "window-background",
                            "x" to window.x - rootWindow.x,
                            "y" to window.y - rootWindow.y,
                            "width" to window.width,
                            "height" to window.height,
                            "fill" to fill,
                        )
                    }
                    renderFramebuffers(this, snapshot, listOf(window), originX = rootWindow.x, originY = rootWindow.y, clipPrefix = clipPrefix)
                    renderDrawings(
                        this,
                        snapshot,
                        clipPrefix = clipPrefix,
                        originX = rootWindow.x,
                        originY = rootWindow.y,
                        drawableIds = setOf(window.id),
                    )
                }
            }
            svgElement(
                "text",
                "x" to rootWindow.width - 6,
                "y" to rootWindow.height - 6,
                "fill" to "#5b6472",
                "text-anchor" to "end",
                "font-size" to 10,
            ) {
                text(RenderCredit.Text)
            }
        }
    }

    private fun subtreeWindows(snapshot: XScreenSnapshot, rootWindow: XWindowSnapshot): List<XWindowSnapshot> {
        val byParent = snapshot.windows.groupBy { it.parentId }
        val result = mutableListOf<XWindowSnapshot>()
        val queue = ArrayDeque<XWindowSnapshot>()
        queue += rootWindow
        while (queue.isNotEmpty()) {
            val window = queue.removeFirst()
            result += window
            byParent[window.id].orEmpty().forEach { queue += it }
        }
        return result.sortedBy { it.stackingIndex }
    }

    private fun renderPixmapSvg(builder: XmlDom, pixmap: XPixmapSnapshot) {
        val href = pixmap.framebufferDataUri
        builder.svgElement(
            "svg",
            "class" to "pixmap-preview-svg",
            "viewBox" to "0 0 ${pixmap.width} ${pixmap.height}",
            "role" to "img",
            "aria-label" to "Pixmap ${pixmap.idHex}",
        ) {
            comment(RenderCredit.Text)
            svgElement("rect", "x" to 0, "y" to 0, "width" to pixmap.width, "height" to pixmap.height, "fill" to "#f8fafc")
            if (href != null) {
                svgElement(
                    "image",
                    "class" to "pixmap-framebuffer-image",
                    "data-pixmap-id" to pixmap.idHex,
                    "data-picture-id" to pixmap.retainedPictureIdHex,
                    "data-source" to if (pixmap.retained) "retained-picture" else "pixmap",
                    "x" to 0,
                    "y" to 0,
                    "width" to pixmap.width,
                    "height" to pixmap.height,
                    "href" to href,
                    "preserveAspectRatio" to "none",
                )
            }
            svgElement(
                "text",
                "x" to pixmap.width - 6,
                "y" to pixmap.height - 6,
                "fill" to "#5b6472",
                "text-anchor" to "end",
                "font-size" to 10,
            ) {
                text(RenderCredit.Text)
            }
        }
    }

    private fun renderWindowList(builder: XmlDom, snapshot: XScreenSnapshot) {
        builder.element("dl") {
            for (window in snapshot.windows) {
                element("dt") {
                    element("code") { text(window.idHex) }
                }
                element("dd") {
                    text(window.label)
                    text(" ")
                    text("${window.width}x${window.height}")
                    text(" ${window.className}")
                    if (window.visibleWidth != window.width || window.visibleHeight != window.height) {
                        text(" visible ${window.visibleWidth}x${window.visibleHeight}")
                    }
                    text(if (window.mapped) " mapped" else " unmapped")
                }
            }
        }
    }

    private fun renderDrawings(
        builder: XmlDom,
        snapshot: XScreenSnapshot,
        clipPrefix: String,
        originX: Int = 0,
        originY: Int = 0,
        drawableIds: Set<Int>? = null,
    ) {
        val windows = snapshot.windows.associateBy { it.id }
        for (drawing in snapshot.drawings) {
            if (drawableIds != null && drawing.drawableId !in drawableIds) continue
            val window = windows[drawing.drawableId] ?: continue
            if (window.windowClass != XWindowClass.InputOutput) continue
            if (!window.mapped || window.visibleWidth <= 0 || window.visibleHeight <= 0) continue
            if (drawing.framebufferBacked) continue
            builder.svgElement(
                "g",
                "data-drawable-id" to window.idHex,
                "clip-path" to "url(#${clipId(clipPrefix, window)})",
                "transform" to "translate(${window.x - originX} ${window.y - originY})",
            ) {
                when (drawing.kind) {
                    XDrawingKind.Clear -> renderFilledRectangles(this, drawing, drawing.foreground)
                    XDrawingKind.FillRectangle -> renderFilledRectangles(this, drawing, drawing.foreground)
                    XDrawingKind.PutImage -> {
                        if (drawing.imageDataUri == null) {
                            renderFilledRectangles(this, drawing, drawing.foreground, opacity = "0.35")
                            renderOutlinedRectangles(this, drawing, "#5b6472", dash = "8 6")
                        } else {
                            renderImages(this, drawing)
                        }
                    }
                    XDrawingKind.CopyArea -> renderImages(this, drawing)
                    XDrawingKind.CopyPlane -> renderImages(this, drawing)
                    XDrawingKind.Rectangle -> renderOutlinedRectangles(this, drawing, pixelColor(drawing.foreground), dash = dashArray(drawing))
                    XDrawingKind.FillPoly -> renderPolygon(this, drawing)
                    XDrawingKind.Arc -> renderArcs(this, drawing, filled = false)
                    XDrawingKind.FillArc -> renderArcs(this, drawing, filled = true)
                    XDrawingKind.Line -> renderLine(this, drawing)
                    XDrawingKind.Segment -> renderSegments(this, drawing)
                    XDrawingKind.Text -> renderText(this, drawing)
                }
            }
        }
    }

    private fun renderFramebuffers(
        builder: XmlDom,
        snapshot: XScreenSnapshot,
        windows: List<XWindowSnapshot>,
        originX: Int,
        originY: Int,
        clipPrefix: String,
    ) {
        for (window in windows) {
            if (window.windowClass != XWindowClass.InputOutput) continue
            val surface = displaySurface(snapshot, window) ?: continue
            builder.svgElement(
                "g",
                "clip-path" to "url(#${clipId(clipPrefix, window)})",
            ) {
                svgElement(
                    "image",
                    "class" to surface.cssClass,
                    "data-window-id" to window.idHex,
                    "data-pixmap-id" to surface.pixmapIdHex,
                    "data-picture-id" to surface.pictureIdHex,
                    "data-source" to surface.source,
                    "x" to window.x - originX,
                    "y" to window.y - originY,
                    "width" to surface.width,
                    "height" to surface.height,
                    "href" to surface.href,
                    "preserveAspectRatio" to "none",
                )
            }
        }
    }

    private fun displaySurface(snapshot: XScreenSnapshot, window: XWindowSnapshot): XDisplaySurface? {
        val pixmap = matchingPixmapCandidates(snapshot, window)
            .firstOrNull { window.id in it.matchingWindowIds }
        val pixmapSurface = pixmap?.let(::pixmapDisplaySurface)
        if (pixmapSurface != null) {
            val windowPaintIndex = snapshot.latestFramebufferPaintIndex(window.id)
            val pixmapPaintIndex = snapshot.latestFramebufferPaintIndex(pixmap.id)
            if (windowPaintIndex < 0 || pixmapPaintIndex > windowPaintIndex) return pixmapSurface
        }
        window.framebufferDataUri?.let {
            return XDisplaySurface(
                href = it,
                source = "window-framebuffer",
                cssClass = "framebuffer-image",
                width = window.width,
                height = window.height,
            )
        }
        return pixmapSurface
    }

    private fun pixmapDisplaySurface(pixmap: XPixmapSnapshot): XDisplaySurface? {
        val href = pixmap.framebufferDataUri ?: return null
        return XDisplaySurface(
            href = href,
            source = if (pixmap.retained) "retained-picture" else "matching-pixmap",
            cssClass = "framebuffer-image backing-pixmap-image",
            width = pixmap.width,
            height = pixmap.height,
            pixmapIdHex = pixmap.idHex,
            pictureIdHex = pixmap.retainedPictureIdHex,
        )
    }

    // Covering pixmap matching is a presentation heuristic for IDE backing stores;
    // exact-size candidates stay ahead of oversized surfaces, then recent paints win.
    private fun XScreenSnapshot.latestFramebufferPaintIndex(drawableId: Int): Int =
        drawings.indexOfLast { it.drawableId == drawableId && it.framebufferBacked }

    private fun renderFilledRectangles(
        builder: XmlDom,
        drawing: XDrawingCommand,
        pixel: Int,
        opacity: String = "1",
    ) {
        for (rectangle in drawing.rectangles) {
            if (rectangle.width <= 0 || rectangle.height <= 0) continue
            builder.svgElement(
                "rect",
                "x" to rectangle.x,
                "y" to rectangle.y,
                "width" to rectangle.width,
                "height" to rectangle.height,
                "fill" to pixelColor(pixel),
                "fill-opacity" to opacity,
            )
        }
    }

    private fun renderOutlinedRectangles(
        builder: XmlDom,
        drawing: XDrawingCommand,
        color: String,
        dash: String? = null,
    ) {
        for (rectangle in drawing.rectangles) {
            if (rectangle.width <= 0 || rectangle.height <= 0) continue
            builder.svgElement(
                "rect",
                "x" to rectangle.x,
                "y" to rectangle.y,
                "width" to rectangle.width,
                "height" to rectangle.height,
                "fill" to "none",
                "stroke" to color,
                "stroke-width" to drawing.lineWidth.coerceAtLeast(1),
                "stroke-linejoin" to strokeLineJoin(drawing.joinStyle),
                "stroke-dasharray" to dash,
                "stroke-dashoffset" to dashOffset(drawing),
            )
        }
    }

    private fun renderImages(builder: XmlDom, drawing: XDrawingCommand) {
        val href = drawing.imageDataUri ?: return
        for (rectangle in drawing.rectangles) {
            if (rectangle.width <= 0 || rectangle.height <= 0) continue
            builder.svgElement(
                "image",
                "x" to rectangle.x,
                "y" to rectangle.y,
                "width" to rectangle.width,
                "height" to rectangle.height,
                "href" to href,
                "preserveAspectRatio" to "none",
            )
        }
    }

    private fun renderArcs(builder: XmlDom, drawing: XDrawingCommand, filled: Boolean) {
        val arcs = drawing.arcs.ifEmpty {
            drawing.rectangles.map { rectangle ->
                XArcCommand(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 0, 360 * 64)
            }
        }
        for (arc in arcs) {
            if (arc.width <= 0 || arc.height <= 0) continue
            builder.svgElement(
                "ellipse",
                "cx" to arc.x + arc.width / 2.0,
                "cy" to arc.y + arc.height / 2.0,
                "rx" to arc.width / 2.0,
                "ry" to arc.height / 2.0,
                "fill" to if (filled) pixelColor(drawing.foreground) else "none",
                "stroke" to if (filled) null else pixelColor(drawing.foreground),
                "stroke-width" to if (filled) null else drawing.lineWidth.coerceAtLeast(1),
                "stroke-linecap" to if (filled) null else strokeLineCap(drawing.capStyle),
                "stroke-linejoin" to if (filled) null else strokeLineJoin(drawing.joinStyle),
                "stroke-dasharray" to if (filled) null else dashArray(drawing),
                "stroke-dashoffset" to if (filled) null else dashOffset(drawing),
            )
        }
    }

    private fun renderLine(builder: XmlDom, drawing: XDrawingCommand) {
        if (drawing.points.size < 2) return
        builder.svgElement(
            "polyline",
            "points" to drawing.points.joinToString(" ") { "${it.x},${it.y}" },
            "fill" to "none",
            "stroke" to pixelColor(drawing.foreground),
            "stroke-width" to drawing.lineWidth.coerceAtLeast(1),
            "stroke-linecap" to strokeLineCap(drawing.capStyle),
            "stroke-linejoin" to strokeLineJoin(drawing.joinStyle),
            "stroke-dasharray" to dashArray(drawing),
            "stroke-dashoffset" to dashOffset(drawing),
        )
    }

    private fun renderSegments(builder: XmlDom, drawing: XDrawingCommand) {
        val color = pixelColor(drawing.foreground)
        drawing.points.chunked(2).forEach { segment ->
            if (segment.size != 2) return@forEach
            builder.svgElement(
                "line",
                "x1" to segment[0].x,
                "y1" to segment[0].y,
                "x2" to segment[1].x,
                "y2" to segment[1].y,
                "stroke" to color,
                "stroke-width" to drawing.lineWidth.coerceAtLeast(1),
                "stroke-linecap" to strokeLineCap(drawing.capStyle),
                "stroke-dasharray" to dashArray(drawing),
                "stroke-dashoffset" to dashOffset(drawing),
            )
        }
    }

    private fun strokeLineCap(capStyle: Int): String =
        when (capStyle) {
            XGraphicsContext.CapRound -> "round"
            XGraphicsContext.CapProjecting -> "square"
            else -> "butt"
        }

    private fun strokeLineJoin(joinStyle: Int): String =
        when (joinStyle) {
            XGraphicsContext.JoinRound -> "round"
            XGraphicsContext.JoinBevel -> "bevel"
            else -> "miter"
        }

    private fun dashArray(drawing: XDrawingCommand): String? {
        if (drawing.lineStyle == XGraphicsContext.LineSolid) return null
        val dashes = drawing.dashes.filter { it > 0 }
        if (dashes.isEmpty()) return null
        val normalized = if (dashes.size % 2 == 0) dashes else dashes + dashes
        return normalized.joinToString(" ")
    }

    private fun dashOffset(drawing: XDrawingCommand): Int? =
        drawing.dashOffset.takeIf { drawing.lineStyle != XGraphicsContext.LineSolid && it != 0 }

    private fun renderPolygon(builder: XmlDom, drawing: XDrawingCommand) {
        if (drawing.points.size < 3) return
        builder.svgElement(
            "polygon",
            "points" to drawing.points.joinToString(" ") { "${it.x},${it.y}" },
            "fill" to pixelColor(drawing.foreground),
        )
    }

    private fun renderText(builder: XmlDom, drawing: XDrawingCommand) {
        val point = drawing.points.firstOrNull() ?: return
        builder.svgElement(
            "text",
            "x" to point.x,
            "y" to point.y,
            "fill" to pixelColor(drawing.foreground),
            "font-size" to 24,
        ) {
            text(drawing.text)
        }
    }

    private fun pixelColor(pixel: Int): String =
        "#${(pixel and 0x00ff_ffff).toString(16).padStart(6, '0')}"

    private fun renderClipRectangles(
        builder: XmlDom,
        window: XWindowSnapshot,
        windowsById: Map<Int, XWindowSnapshot>,
        originX: Int,
        originY: Int,
        base: XRectangleCommand,
    ) {
        var rectangles = window.renderShape?.mapNotNull { rectangle ->
            intersectRectangles(
                XRectangleCommand(
                    x = window.x - originX + rectangle.x,
                    y = window.y - originY + rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                ),
                base,
            )
        } ?: listOf(base)
        var parentId = window.parentId
        while (parentId != X11Ids.RootWindow) {
            val ancestor = windowsById[parentId] ?: break
            val ancestorShape = ancestor.renderShape
            if (ancestorShape != null) {
                val ancestorVisible = XRectangleCommand(
                    ancestor.visibleX - originX,
                    ancestor.visibleY - originY,
                    ancestor.visibleWidth,
                    ancestor.visibleHeight,
                )
                val ancestorRectangles = ancestorShape.mapNotNull { rectangle ->
                    intersectRectangles(
                        XRectangleCommand(
                            x = ancestor.x - originX + rectangle.x,
                            y = ancestor.y - originY + rectangle.y,
                            width = rectangle.width,
                            height = rectangle.height,
                        ),
                        ancestorVisible,
                    )
                }
                rectangles = intersectRectangleSets(rectangles, ancestorRectangles)
            }
            parentId = ancestor.parentId
        }
        for (rectangle in rectangles) {
            builder.svgElement(
                "rect",
                "x" to rectangle.x,
                "y" to rectangle.y,
                "width" to rectangle.width,
                "height" to rectangle.height,
            )
        }
    }

    private fun intersectRectangleSets(first: List<XRectangleCommand>, second: List<XRectangleCommand>): List<XRectangleCommand> =
        first.flatMap { left ->
            second.mapNotNull { right -> intersectRectangles(left, right) }
        }

    private fun intersectRectangles(first: XRectangleCommand, second: XRectangleCommand): XRectangleCommand? {
        val left = maxOf(first.x, second.x)
        val top = maxOf(first.y, second.y)
        val right = minOf(first.x + first.width, second.x + second.width)
        val bottom = minOf(first.y + first.height, second.y + second.height)
        if (right <= left || bottom <= top) return null
        return XRectangleCommand(left, top, right - left, bottom - top)
    }

    private fun windowBackgroundFill(window: XWindowSnapshot, windows: List<XWindowSnapshot>): String? =
        windowBackgroundFill(window, windows.associateBy { it.id }, visited = mutableSetOf())

    private fun windowBackgroundFill(window: XWindowSnapshot, windows: Map<Int, XWindowSnapshot>, visited: MutableSet<Int>): String? {
        if (!visited.add(window.id)) return pixelColor(window.backgroundPixel)
        return when (window.backgroundPixmapId) {
            XWindowBackground.None -> null
            XWindowBackground.ParentRelative -> windows[window.parentId]?.let { windowBackgroundFill(it, windows, visited) }
            else -> pixelColor(window.backgroundPixel)
        }
    }

    private fun clipId(prefix: String, window: XWindowSnapshot): String =
        "clip-$prefix-${window.idHex.drop(2)}"

    private fun XWindowSnapshot.displayTitle(): String =
        label.trim().ifEmpty { idHex }

    private fun StringBuilder.appendRectangles(rectangles: List<XRectangleCommand>?) {
        if (rectangles == null) {
            append("null")
            return
        }
        append('[')
        rectangles.forEachIndexed { index, rectangle ->
            if (index > 0) append(',')
            append("""{"x":${rectangle.x},"y":${rectangle.y},"width":${rectangle.width},"height":${rectangle.height}}""")
        }
        append(']')
    }

    private fun escapeJson(value: String): String =
        buildString(value.length) {
            for (char in value) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char < ' ') {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }

    private data class XDisplaySurface(
        val href: String,
        val source: String,
        val cssClass: String,
        val width: Int,
        val height: Int,
        val pixmapIdHex: String? = null,
        val pictureIdHex: String? = null,
    )

    private val palette = listOf(
        "#8bd5ca",
        "#f5a97f",
        "#c6a0f6",
        "#eed49f",
        "#91d7e3",
        "#ed8796",
    )
}
