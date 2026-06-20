package org.jonnyzzz.xserver

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName
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
}
