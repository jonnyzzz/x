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

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 1280, height = 900)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse("debian:stable-slim"))
                .withCommand("sleep", "900")
                .use { container ->
                    container.start()
                    assertEquals(
                        0,
                        container.execInContainer(
                            "sh",
                            "-lc",
                            "apt-get update && apt-get install -y --no-install-recommends " +
                                "ca-certificates curl tar gzip openjdk-21-jre " +
                                "libxrender1 libxtst6 libxi6 libfreetype6 libfontconfig1 libxext6 libx11-6 libxrandr2 libxss1",
                        ).exitCode,
                    )
                    val display = port - 6000
                    val result = container.execInContainer(
                        "sh",
                        "-lc",
                        """
                        set -eu
                        curl -L "$url" -o /tmp/idea.tar.gz
                        mkdir -p /opt/idea /tmp/idea-config /tmp/idea-system /tmp/idea-log
                        tar -xzf /tmp/idea.tar.gz -C /opt/idea --strip-components=1
                        cat > /tmp/idea.properties <<'EOF'
                        idea.config.path=/tmp/idea-config
                        idea.system.path=/tmp/idea-system
                        idea.log.path=/tmp/idea-log
                        EOF
                        set +e
                        export JAVA_HOME="${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}(command -v java)")")")"
                        DISPLAY=host.docker.internal:$display \
                        IDEA_PROPERTIES=/tmp/idea.properties \
                        timeout 45s /opt/idea/bin/idea.sh nosplash
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
}
