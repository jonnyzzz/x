plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "org.jonnyzzz"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

application {
    mainClass.set("org.jonnyzzz.xserver.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
}

tasks.test {
    useJUnitPlatform()
    listOf("x.intellijSmoke", "x.intellijUrl", "x.intellijImage").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}

tasks.register<Exec>("dockerBuildX11Client") {
    group = "verification"
    description = "Builds the Docker image with X11 and IntelliJ runtime dependencies."
    commandLine(
        "docker",
        "build",
        "-t",
        "jonnyzzz-x/x11-client:latest",
        "docker/x11-client",
    )
}

tasks.register<Exec>("dockerBuildX11Reference") {
    group = "verification"
    description = "Builds the Docker image with Xvfb for reference-only comparison tests."
    dependsOn("dockerBuildX11Client")
    commandLine(
        "docker",
        "build",
        "-t",
        "jonnyzzz-x/x11-reference:latest",
        "docker/x11-reference",
    )
}

tasks.register("dockerBuildX11Images") {
    group = "verification"
    description = "Builds all local Docker images used by the X11 integration tests."
    dependsOn("dockerBuildX11Client", "dockerBuildX11Reference")
}
