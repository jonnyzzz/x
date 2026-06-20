package org.jonnyzzz.xserver

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal class HttpScreenConnection(
    input: InputStream,
    private val output: OutputStream,
    private val state: X11State,
) {
    private val reader = BufferedReader(InputStreamReader(input, StandardCharsets.US_ASCII))

    fun run() {
        val request = reader.readLine().orEmpty()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = request.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        if (method != "GET" && method != "HEAD") {
            return respond(405, "text/plain; charset=utf-8", "Method not allowed\n", method == "HEAD")
        }

        val body = when (path.substringBefore('?')) {
            "", "/" -> SvgScreenRenderer.html(state.snapshot())
            "/screen.svg" -> SvgScreenRenderer.svg(state.snapshot())
            "/text" -> TextScreenRenderer.html(state.snapshot())
            "/text.txt" -> TextScreenRenderer.plain(state.snapshot())
            "/state.json" -> SvgScreenRenderer.json(state.snapshot())
            else -> return respond(404, "text/plain; charset=utf-8", "Not found\n", method == "HEAD")
        }
        val contentType = when (path.substringBefore('?')) {
            "/screen.svg" -> "image/svg+xml; charset=utf-8"
            "/text.txt" -> "text/plain; charset=utf-8"
            "/state.json" -> "application/json; charset=utf-8"
            else -> "text/html; charset=utf-8"
        }
        respond(200, contentType, body, method == "HEAD")
    }

    private fun respond(status: Int, contentType: String, body: String, headOnly: Boolean) {
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        output.write(
            buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII),
        )
        if (!headOnly) output.write(bytes)
        output.flush()
    }
}
