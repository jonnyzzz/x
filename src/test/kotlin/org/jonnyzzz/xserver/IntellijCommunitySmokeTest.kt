package org.jonnyzzz.xserver

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.BindMode
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
        val url = System.getProperty("x.intellijUrl") ?: System.getenv("X_INTELLIJ_URL")
        val image = System.getProperty("x.intellijImage")
            ?: System.getenv("X_INTELLIJ_IMAGE")
            ?: "jonnyzzz-x/x11-client:latest"
        assumeTrue(imageExists(image), "Build $image first with ./gradlew dockerBuildX11Client")

        XServer(ServerOptions(host = "0.0.0.0", port = port, width = 1280, height = 900)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            GenericContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("ubuntu"))
                .withFileSystemBind(projectRoot().toString(), "/workspace/jonnyzzz-x", BindMode.READ_WRITE)
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
                        command -v git
                        check() {
                          echo "check: ${'$'}*"
                          "${'$'}@"
                        }
                        if [ -n "${url.orEmpty()}" ]; then
                          export IDEA_URL="${url.orEmpty()}"
                        fi
                        DISPLAY=host.docker.internal:$display \
                        IDEA_PROJECT=/workspace/jonnyzzz-x \
                        IDEA_TRUST_PROJECT=true \
                        run-intellij >/tmp/idea-run-smoke.log 2>&1 &
                        pid=${'$'}!
                        opened=0
                        for _ in ${'$'}(seq 1 120); do
                          if grep -q "Project frame set to Project(name=" /tmp/idea-log/idea.log 2>/dev/null; then
                            opened=1
                            break
                          fi
                          if ! kill -0 "${'$'}pid" 2>/dev/null; then
                            break
                          fi
                          sleep 1
                        done
                        if [ "${'$'}opened" -ne 1 ]; then
                          cat /tmp/idea-run-smoke.log 2>/dev/null || true
                          tail -200 /tmp/idea-log/idea.log 2>/dev/null || true
                          exit 1
                        fi
                        kill "${'$'}pid" 2>/dev/null || true
                        wait "${'$'}pid" 2>/dev/null || true
                        check test -x /usr/lib/jvm/jbr-25/bin/java
                        check grep -q 'name value="jbr-25"' /tmp/idea-config/options/jdk.table.xml
                        check grep -q 'ide.experimental.ui.onboarding' /tmp/idea-config/options/ide.general.xml
                        check grep -q 'experimental.ui.on.first.startup' /tmp/idea-config/options/other.xml
                        check sh -lc 'find /root/.java/.userPrefs/jetbrains -name prefs.xml -exec grep -l "euacommunity_accepted_version" {} + | grep -q .'
                        check grep -q -- "-Didea.trust.all.projects=true" /tmp/idea-extra.vmoptions
                        check grep -q "componentStore=/workspace/jonnyzzz-x" /tmp/idea-log/idea.log
                        if grep -q "Download JDK" /tmp/idea-log/idea.log; then echo "unexpected Download JDK log"; exit 1; fi
                        if grep -q "Cannot Run Git" /tmp/idea-log/idea.log; then echo "unexpected Cannot Run Git log"; exit 1; fi
                        if grep -q "Project is not trusted" /tmp/idea-log/idea.log; then echo "unexpected Project is not trusted log"; exit 1; fi
                        """.trimIndent(),
                    )
                    assertEquals(0, result.exitCode, result.stderr + result.stdout)
                    assertFalse(
                        container.execInContainer("sh", "-lc", "grep -q 'Project is not trusted' /tmp/idea-log/idea.log").exitCode == 0,
                        "IntelliJ should not reject the mounted jonnyzzz-x project as untrusted",
                    )
                }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun projectRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

    private fun isPortAvailable(port: Int): Boolean =
        runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

    private fun imageExists(image: String): Boolean =
        runCatching {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec()
        }.isSuccess
}
