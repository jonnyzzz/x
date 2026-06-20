package org.jonnyzzz.xserver

import java.io.Closeable
import java.io.EOFException
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class XServer(private val options: ServerOptions) : Closeable {
    private val state = X11State(width = options.width, height = options.height, dpi = options.dpi)
    private val serverSocket = ServerSocket(
        options.port,
        50,
        InetAddress.getByName(options.host),
    )
    private val clients: ExecutorService = Executors.newCachedThreadPool { runnable ->
        thread(start = false, isDaemon = true, name = "x-client") {
            runnable.run()
        }
    }
    private var closed = false

    val localPort: Int
        get() = serverSocket.localPort

    fun serveForever() {
        while (!closed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (closed) return
                throw e
            }
            clients.execute { handleClient(socket) }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use {
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                input.mark(4)
                val prefix = ByteArray(4)
                val read = input.read(prefix)
                input.reset()
                if (read >= 3 && prefix.isHttpMethodPrefix()) {
                    HttpScreenConnection(input, output, state).run()
                } else {
                    X11Connection(input, output, state).run()
                }
            }
        } catch (_: SocketException) {
            // Clients may close immediately after reading the setup reply.
        } catch (_: EOFException) {
            // Clients may close immediately after reading the setup reply.
        }
    }

    override fun close() {
        closed = true
        serverSocket.close()
        clients.shutdownNow()
        clients.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun ByteArray.isHttpMethodPrefix(): Boolean =
        startsWith("GET") || startsWith("HEAD") || startsWith("POST")

    private fun ByteArray.startsWith(value: String): Boolean =
        value.indices.all { index -> this[index] == value[index].code.toByte() }
}
