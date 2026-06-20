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
}
