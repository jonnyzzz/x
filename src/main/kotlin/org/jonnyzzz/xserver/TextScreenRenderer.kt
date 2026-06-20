package org.jonnyzzz.xserver

internal object TextScreenRenderer {
    fun html(snapshot: XScreenSnapshot): String =
        XmlDom.html {
            attributes("lang" to "en")
            comment(RenderCredit.Text)
            element("head") {
                element("meta", "charset" to "utf-8")
                element("meta", "name" to "viewport", "content" to "width=device-width, initial-scale=1")
                element("meta", "http-equiv" to "refresh", "content" to "1")
                element("title") { text("X screen text report") }
                element("style") { text(textCss()) }
            }
            element("body") {
                element("main") {
                    element("h1") { text("X screen text report") }
                    element("pre") { text(plain(snapshot)) }
                    element("footer") {
                        text("by ")
                        element("a", "href" to "https://github.com/jonnyzzz/x") { text("@jonnyzzz") }
                        text(" ")
                        element("a", "href" to "https://linkedin.com/in/jonnyzzz") { text("https://linkedin.com/in/jonnyzzz") }
                    }
                }
            }
        }

    fun plain(snapshot: XScreenSnapshot): String =
        buildString {
            appendLine("Screen: ${snapshot.width} x ${snapshot.height}")
            appendLine("DPI: ${snapshot.dpi}")
            appendLine("Physical size: ${snapshot.widthMillimeters} x ${snapshot.heightMillimeters} mm")
            appendLine("Windows: ${snapshot.windows.size}")
            appendLine("Mapped windows: ${snapshot.windows.count { it.mapped }}")
            appendLine("Focus: ${snapshot.windows.firstOrNull { it.focused }?.idHex ?: "none"}")
            appendLine()
            appendLine("Window hierarchy and geometry:")
            for (window in snapshot.windows) {
                append("- ")
                append(window.idHex)
                append(" parent=")
                append(window.parentIdHex)
                append(" label=\"")
                append(window.label)
                append("\" geometry=")
                append(window.x).append(',').append(window.y)
                append(' ').append(window.width).append('x').append(window.height)
                append(" mapped=").append(window.mapped)
                append(" focused=").append(window.focused)
                append(" stack=").append(window.stackingIndex)
                appendLine()
            }
            appendLine()
            appendLine("Overlap and focus:")
            if (snapshot.overlaps.isEmpty()) {
                appendLine("- No mapped non-root windows overlap.")
            } else {
                for (overlap in snapshot.overlaps) {
                    append("- ")
                    append(overlap.upperWindowIdHex)
                    append(" overlaps ")
                    append(overlap.lowerWindowIdHex)
                    append(" at ")
                    append(overlap.x).append(',').append(overlap.y)
                    append(' ').append(overlap.width).append('x').append(overlap.height)
                    appendLine()
                }
            }
            appendLine()
            appendLine("Input operations:")
            if (snapshot.inputOperations.isEmpty()) {
                appendLine("- None.")
            } else {
                for (operation in snapshot.inputOperations.takeLast(20).asReversed()) {
                    append("- #")
                    append(operation.id)
                    append(' ')
                    append(operation.kind)
                    append(' ')
                    append(operation.button)
                    append(" at ")
                    append(operation.x).append(',').append(operation.y)
                    append(" target=")
                    append(operation.targetWindowIdHex ?: "none")
                    append(" delivered=")
                    append(operation.deliveredEvents)
                    appendLine()
                }
            }
            appendLine()
            appendLine(RenderCredit.Text)
        }

    private fun textCss(): String =
        """
        body { margin: 0; padding: 24px; background: #111318; color: #e7e9ee; font-family: system-ui, sans-serif; }
        main { max-width: 980px; margin: 0 auto; }
        h1 { margin-top: 0; font-size: 22px; }
        pre { white-space: pre-wrap; background: #1b1f29; border: 1px solid #303642; padding: 16px; overflow: auto; }
        footer { color: #aab2c0; font-size: 12px; margin-top: 18px; }
        """.trimIndent()
}
