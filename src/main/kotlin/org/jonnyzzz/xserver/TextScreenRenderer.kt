package org.jonnyzzz.xserver

internal object TextScreenRenderer {
    fun html(snapshot: XScreenSnapshot): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta http-equiv="refresh" content="1">
          <title>X screen text report</title>
          <style>
            body { margin: 0; padding: 24px; background: #111318; color: #e7e9ee; font-family: system-ui, sans-serif; }
            main { max-width: 980px; margin: 0 auto; }
            h1 { margin-top: 0; font-size: 22px; }
            pre { white-space: pre-wrap; background: #1b1f29; border: 1px solid #303642; padding: 16px; overflow: auto; }
          </style>
        </head>
        <body>
          <main>
            <h1>X screen text report</h1>
            <pre>${escape(plain(snapshot))}</pre>
          </main>
        </body>
        </html>
        """.trimIndent()

    fun plain(snapshot: XScreenSnapshot): String =
        buildString {
            appendLine("Screen: ${snapshot.width} x ${snapshot.height}")
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
        }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
