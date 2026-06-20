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
        assumeDockerAndImage(REFERENCE_IMAGE)

        GenericContainer(DockerImageName.parse(REFERENCE_IMAGE).asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "300")
            .use { container ->
                container.start()
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
        assumeDockerAndImage(CLIENT_IMAGE)
        val port = 6207
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()

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

    @Test
    fun `window manager smoke exposes independent windows and overlap over http`() {
        assumeDockerAndImage(CLIENT_IMAGE)
        val port = 6209
        assumeTrue(isPortAvailable(port), "Port $port is not available")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 800, height = 600)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }

            GenericContainer(DockerImageName.parse(CLIENT_IMAGE).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "300")
                .use { container ->
                    container.start()

                    val display = port - 6000
                    val result = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        export DISPLAY=host.docker.internal:$display
                        twm >/tmp/twm.log 2>&1 &
                        sleep 1
                        timeout 6s xlogo -geometry 180x120+40+40 >/tmp/xlogo.log 2>&1 &
                        timeout 6s xclock -geometry 180x120+110+90 >/tmp/xclock.log 2>&1 &
                        sleep 2
                        curl -fsS http://host.docker.internal:$port/text.txt > /tmp/screen.txt
                        curl -fsS http://host.docker.internal:$port/screen.svg > /tmp/screen.svg
                        cat /tmp/screen.txt
                        printf '\n--- SVG ---\n'
                        cat /tmp/screen.svg
                        """.trimIndent(),
                    )
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    assertTrue(result.stdout.contains("Focus:"), result.stdout)
                    assertTrue(result.stdout.contains("Overlap and focus:"), result.stdout)
                    assertTrue(result.stdout.contains("overlaps"), result.stdout)
                    assertTrue(result.stdout.contains("data-window-id="), result.stdout)
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

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun assumeDockerAndImage(image: String) {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        val imageExists = runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess
        assumeTrue(imageExists, "Build $image first with ./gradlew dockerBuildX11Images")
    }

    private companion object {
        const val CLIENT_IMAGE = "jonnyzzz-x/x11-client:latest"
        const val REFERENCE_IMAGE = "jonnyzzz-x/x11-reference:latest"
    }
}
