package org.jonnyzzz.xserver

internal object SvgScreenRenderer {
    fun html(snapshot: XScreenSnapshot): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta http-equiv="refresh" content="1">
          <title>X screen</title>
          <style>
            html, body { margin: 0; min-height: 100%; background: #15171c; color: #e7e9ee; font-family: system-ui, sans-serif; }
            main { display: grid; grid-template-columns: minmax(0, 1fr) 320px; min-height: 100vh; }
            .screen { display: grid; place-items: center; padding: 24px; }
            svg { width: min(100%, ${snapshot.width}px); height: auto; background: #20242c; box-shadow: 0 0 0 1px #3b4252; }
            aside { border-left: 1px solid #303642; padding: 18px; background: #111318; overflow: auto; }
            h1 { font-size: 16px; margin: 0 0 12px; }
            dl { display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; margin: 0 0 18px; font-size: 13px; }
            dt { color: #aab2c0; }
            dd { margin: 0; overflow-wrap: anywhere; }
            code { color: #d4dcff; }
          </style>
        </head>
        <body>
          <main>
            <section class="screen">
              ${svg(snapshot)}
            </section>
            <aside>
              <h1>X server state</h1>
              <dl>
                <dt>Screen</dt><dd>${snapshot.width} x ${snapshot.height}</dd>
                <dt>Windows</dt><dd>${snapshot.windows.size}</dd>
                <dt>Mapped</dt><dd>${snapshot.windows.count { it.mapped }}</dd>
              </dl>
              ${windowList(snapshot)}
            </aside>
          </main>
        </body>
        </html>
        """.trimIndent()

    fun svg(snapshot: XScreenSnapshot): String =
        buildString {
            append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${snapshot.width} ${snapshot.height}" role="img" aria-label="X screen">""")
            append("""<rect x="0" y="0" width="${snapshot.width}" height="${snapshot.height}" fill="#20242c"/>""")
            append("""<g font-family="monospace" font-size="12">""")
            snapshot.windows
                .filter { it.mapped && it.id != X11Ids.RootWindow }
                .forEachIndexed { index, window ->
                    val color = palette[index % palette.size]
                    val strokeWidth = if (window.focused) 4 else 2
                    append("""<rect data-window-id="${window.idHex}" x="${window.x}" y="${window.y}" width="${window.width}" height="${window.height}" fill="$color" fill-opacity="0.28" stroke="$color" stroke-width="$strokeWidth"/>""")
                    append("""<text x="${window.x + 6}" y="${window.y + 18}" fill="#f8fafc">${escape(window.label)}</text>""")
                }
            snapshot.overlaps.forEach {
                append("""<rect x="${it.x}" y="${it.y}" width="${it.width}" height="${it.height}" fill="#ff5c7a" fill-opacity="0.2" stroke="#ff5c7a" stroke-dasharray="4 3"/>""")
            }
            append("</g></svg>")
        }

    fun json(snapshot: XScreenSnapshot): String =
        buildString {
            append("""{"width":${snapshot.width},"height":${snapshot.height},"windows":[""")
            snapshot.windows.forEachIndexed { index, window ->
                if (index > 0) append(',')
                append('{')
                append(""""id":"${window.idHex}","parent":"${window.parentIdHex}","x":${window.x},"y":${window.y},"width":${window.width},"height":${window.height},"mapped":${window.mapped}""")
                append('}')
            }
            append("]}")
        }

    private fun windowList(snapshot: XScreenSnapshot): String =
        buildString {
            append("<dl>")
            for (window in snapshot.windows) {
                append("<dt><code>").append(window.idHex).append("</code></dt>")
                append("<dd>")
                append(escape(window.label))
                append(" ")
                append(window.width).append("x").append(window.height)
                append(if (window.mapped) " mapped" else " unmapped")
                append("</dd>")
            }
            append("</dl>")
        }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private val palette = listOf(
        "#8bd5ca",
        "#f5a97f",
        "#c6a0f6",
        "#eed49f",
        "#91d7e3",
        "#ed8796",
    )
}
