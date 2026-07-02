package org.jonnyzzz.xserver

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.net.URLDecoder

internal class HttpScreenConnection(
    input: InputStream,
    private val output: OutputStream,
    private val state: X11State,
    private val inputController: XInputController,
) {
    private val reader = BufferedReader(InputStreamReader(input, StandardCharsets.US_ASCII))

    fun run() {
        val request = reader.readLine().orEmpty()
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
            if (name.isNotEmpty()) headers[name] = line.substringAfter(':').trim()
        }
        val requestBody = readBody(headers)

        val parts = request.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val route = path.substringBefore('?')
        if (route == "/input/click") {
            if (method != "POST" && method != "GET") {
                return respond(405, "text/plain; charset=utf-8", "Method not allowed\n", method == "HEAD")
            }
            return click(path, requestBody, method == "HEAD")
        }
        if (route == "/input/key") {
            if (method != "POST" && method != "GET") {
                return respond(405, "text/plain; charset=utf-8", "Method not allowed\n", method == "HEAD")
            }
            return key(path, requestBody, method == "HEAD")
        }
        if (method != "GET" && method != "HEAD") {
            return respond(405, "text/plain; charset=utf-8", "Method not allowed\n", method == "HEAD")
        }

        val body = when (route) {
            "", "/" -> SvgScreenRenderer.html(state.snapshot())
            "/screen.svg" -> SvgScreenRenderer.svg(state.snapshot())
            "/text" -> TextScreenRenderer.html(state.snapshot())
            "/text.txt" -> TextScreenRenderer.plain(state.snapshot())
            "/state.json" -> SvgScreenRenderer.json(state.snapshot())
            else -> return respond(404, "text/plain; charset=utf-8", "Not found\n", method == "HEAD")
        }
        val contentType = when (route) {
            "/screen.svg" -> "image/svg+xml; charset=utf-8"
            "/text.txt" -> "text/plain; charset=utf-8"
            "/state.json" -> "application/json; charset=utf-8"
            else -> "text/html; charset=utf-8"
        }
        respond(200, contentType, body, method == "HEAD")
    }

    private fun click(path: String, requestBody: String, headOnly: Boolean) {
        val params = parameters(path.substringAfter('?', missingDelimiterValue = "")) + parameters(requestBody)
        val x = params["x"]?.toIntOrNull()
        val y = params["y"]?.toIntOrNull()
        if (x == null || y == null) {
            return respond(400, "application/json; charset=utf-8", """{"error":"x and y are required"}""" + "\n", headOnly)
        }
        val button = params["button"].orEmpty().ifEmpty { "left" }
        val result = runCatching { inputController.click(x, y, button) }
            .getOrElse {
                return respond(400, "application/json; charset=utf-8", """{"error":"${escapeJson(it.message ?: "invalid click")}"}""" + "\n", headOnly)
            }
        val target = result.targetWindowIdHex
        val body = buildString {
            append("""{"targetWindow":""")
            if (target == null) append("null") else append('"').append(target).append('"')
            append(""","deliveredEvents":""").append(result.deliveredEvents).append("}\n")
        }
        respond(200, "application/json; charset=utf-8", body, headOnly)
    }

    private fun key(path: String, requestBody: String, headOnly: Boolean) {
        val params = parameters(path.substringAfter('?', missingDelimiterValue = "")) + parameters(requestBody)
        val keycodeParam = params["keycode"]
        val keycode = keycodeParam?.let(::parseInt)
        if (keycode == null) {
            val message = if (keycodeParam == null) "keycode is required" else "invalid keycode"
            return respond(400, "application/json; charset=utf-8", """{"error":"$message"}""" + "\n", headOnly)
        }
        val modifiersParam = params["modifiers"]
        val modifiers = if (modifiersParam == null) {
            0
        } else {
            parseInt(modifiersParam)
                ?: return respond(400, "application/json; charset=utf-8", """{"error":"invalid modifiers"}""" + "\n", headOnly)
        }
        val action = params["action"].orEmpty().ifEmpty { "down" }.lowercase()
        val result = runCatching {
            when (action) {
                "down", "key-down", "press" -> inputController.keyDown(keycode, modifiers)
                "up", "key-up", "release" -> inputController.keyUp(keycode, modifiers)
                else -> throw IllegalArgumentException("unsupported key action: $action")
            }
        }.getOrElse {
            return respond(400, "application/json; charset=utf-8", """{"error":"${escapeJson(it.message ?: "invalid key")}"}""" + "\n", headOnly)
        }
        val target = result.targetWindowIdHex
        val body = buildString {
            append("""{"targetWindow":""")
            if (target == null) append("null") else append('"').append(target).append('"')
            append(""","deliveredEvents":""").append(result.deliveredEvents).append("}\n")
        }
        respond(200, "application/json; charset=utf-8", body, headOnly)
    }

    private fun respond(status: Int, contentType: String, body: String, headOnly: Boolean) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
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
                append(RenderCredit.HeaderName).append(": ").append(RenderCredit.Text).append("\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII),
        )
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    private fun readBody(headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull() ?: return ""
        if (length <= 0) return ""
        val chars = CharArray(length)
        var offset = 0
        while (offset < length) {
            val read = reader.read(chars, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return chars.concatToString(0, offset)
    }

    private fun parameters(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        return value.split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=', missingDelimiterValue = "")
                if (name.isEmpty()) return@mapNotNull null
                val rawValue = pair.substringAfter('=', missingDelimiterValue = "")
                decode(name) to decode(rawValue)
            }
            .toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

    private fun parseInt(value: String): Int? =
        value.toIntOrNull() ?: value.takeIf { it.startsWith("0x", ignoreCase = true) }?.drop(2)?.toIntOrNull(16)

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

}
