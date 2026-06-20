package org.jonnyzzz.xserver

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XvfbContainerTest {
    @Test
    fun `docker baseline can run xdpyinfo against xvfb`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

        GenericContainer(DockerImageName.parse("debian:stable-slim"))
            .withCommand("sleep", "300")
            .use { container ->
                container.start()
                assertEquals(
                    0,
                    container.execInContainer(
                        "sh",
                        "-lc",
                        "apt-get update && apt-get install -y --no-install-recommends xvfb x11-utils",
                    ).exitCode,
                )
                val result = container.execInContainer(
                    "sh",
                    "-lc",
                    "Xvfb :99 -screen 0 640x480x24 >/tmp/xvfb.log 2>&1 & " +
                        "for i in $(seq 1 50); do DISPLAY=:99 xdpyinfo >/tmp/xdpyinfo.log 2>&1 && break; sleep 0.1; done; " +
                        "cat /tmp/xdpyinfo.log",
                )
                assertEquals(0, result.exitCode, result.stderr)
                assertTrue(result.stdout.contains("dimensions:    640x480 pixels"), result.stdout)
            }
    }

    @Test
    fun `docker x11 tools can query kotlin server`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        val port = 6207
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse("debian:stable-slim"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()
                    assertEquals(
                        0,
                        container.execInContainer(
                            "sh",
                            "-lc",
                            "apt-get update && apt-get install -y --no-install-recommends netcat-openbsd x11-apps x11-utils",
                        ).exitCode,
                    )
                    assertClientSucceeds(container, "nc -vz host.docker.internal $port")

                    assertClientSucceeds(container, port, "xdpyinfo")
                    assertClientSucceeds(container, port, "xwininfo -root")
                    assertClientSucceeds(container, port, "xprop -root")
                    assertClientKeepsRunning(container, port, "xlogo")
                    assertClientKeepsRunning(container, port, "xclock")
                    assertClientKeepsRunning(container, port, "xeyes")
                    assertClientKeepsRunning(container, port, "xcalc")
                }

            server.close()
            serverThread.join(1_000)
        }
    }

    private fun assertClientSucceeds(
        container: GenericContainer<*>,
        port: Int,
        command: String,
    ) {
        val display = port - 6000
        val result = container.execInContainer(
            "sh",
            "-lc",
            "DISPLAY=host.docker.internal:$display $command",
        )
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun assertClientKeepsRunning(
        container: GenericContainer<*>,
        port: Int,
        command: String,
    ) {
        val display = port - 6000
        val result = container.execInContainer(
            "sh",
            "-lc",
            "DISPLAY=host.docker.internal:$display timeout 2s $command",
        )
        assertEquals(124, result.exitCode, result.stderr + result.stdout)
    }

    private fun assertClientSucceeds(
        container: GenericContainer<*>,
        command: String,
    ) {
        val result = container.execInContainer("sh", "-lc", command)
        assertEquals(0, result.exitCode, result.stderr + result.stdout)
    }

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
}
