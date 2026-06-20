package org.jonnyzzz.xserver

fun main(args: Array<String>) {
    val options = ServerOptions.parse(args)
    XServer(options).use { server ->
        println("X server listening on ${options.host}:${options.port}")
        server.serveForever()
    }
}

data class ServerOptions(
    val host: String = "127.0.0.1",
    val port: Int = 6000,
    val width: Int = 1024,
    val height: Int = 768,
) {
    companion object {
        fun parse(args: Array<String>): ServerOptions {
            var host = "127.0.0.1"
            var port = 6000
            var width = 1024
            var height = 768

            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--host" -> host = args.valueAfter(index++, arg)
                    "--port" -> port = args.valueAfter(index++, arg).toInt()
                    "--width" -> width = args.valueAfter(index++, arg).toInt()
                    "--height" -> height = args.valueAfter(index++, arg).toInt()
                    else -> error("Unknown argument: $arg")
                }
                index++
            }

            return ServerOptions(host, port, width, height)
        }

        private fun Array<String>.valueAfter(index: Int, option: String): String =
            getOrNull(index + 1) ?: error("Missing value for $option")
    }
}
