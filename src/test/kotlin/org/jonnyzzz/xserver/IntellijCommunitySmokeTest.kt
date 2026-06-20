package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals

class IntellijCommunitySmokeTest {
    @Test
    fun `intellij community from github releases starts against kotlin x server`() {
        assumeTrue(
            System.getProperty("x.intellijSmoke") == "true" || System.getenv("X_INTELLIJ_SMOKE") == "true",
            "Set -Dx.intellijSmoke=true or X_INTELLIJ_SMOKE=true to download and run the heavyweight IntelliJ smoke",
        )
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

        val port = 6208
        assumeTrue(isPortAvailable(port), "Port $port is not available")
        val url = System.getProperty("x.intellijUrl")
            ?: System.getenv("X_INTELLIJ_URL")
            ?: "https://github.com/JetBrains/intellij-community/releases/download/idea/2026.1.3/idea-2026.1.3.tar.gz"
        val image = System.getProperty("x.intellijImage")
            ?: System.getenv("X_INTELLIJ_IMAGE")
            ?: "jonnyzzz-x/x11-client:latest"
        assumeTrue(imageExists(image), "Build $image first with ./gradlew dockerBuildX11Client")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 1280, height = 900)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
                .withCommand("sleep", "900")
                .use { container ->
                    container.start()
                    val display = port - 6000
                    val result = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        command -v run-intellij
                        mkdir -p /tmp/demo-project
                        set +e
                        DISPLAY=host.docker.internal:$display \
                        IDEA_URL="$url" \
                        timeout 45s run-intellij
                        code=${'$'}?
                        set -e
                        test ${'$'}code -eq 124
                        """.trimIndent(),
                    )
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun imageExists(image: String): Boolean =
        runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess
}
